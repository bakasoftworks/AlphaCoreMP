package cc.starsector.server.runtime;

import cc.starsector.server.protocol.GameplayEdgeEvent;
import cc.starsector.server.protocol.GameplayEdgeEventKind;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The per-run GPEE WATCH log: every effective (post-merge) gameplay edge
 * event the serve loop hands the wire, one NDJSON line each, PLUS the two forensic fields the
 * wire does not carry — the discharge's bearing SOURCE TIER and its weapon id — so the
 * collimator question ("is the wire {@code flightBearingDeg} for the stream mounts the
 * un-jittered composed ray?") is answerable per mount / per weapon offline, instead of from
 * run-level cumulative meters (66 % composed, all ships pooled, the split UNMEASURED;
 * the measured corpus held ZERO server per-discharge rows).
 *
 * <p>Line = the canonical {@link GameplayEdgeEventNdjsonLog#format} line with
 * {@code ,"round":R,"tier":"…","weaponId":"…"|null} spliced before the closing brace — a
 * SEPARATE forensic file, deliberately NOT the byte-stable B1 log shape (that shape is a
 * fixed-format contract with a fail-closed round-trip parser and a socket-publisher replay
 * consumer; a wire-less field does not belong in it). Tiers ({@link OnFireTruthMerger.SourceTag}):
 * {@code birth_direct} = confessed birth, the projectile's own velocity line (carries the
 * engine's per-shot spread draw); {@code birth_facing} = confessed zero-velocity ray birth, the
 * emitter's {@code shooterVel + speed·û(birth facing)} composition (jitter INCLUDED);
 * {@code birth_composed} = confessed birth, the emitter's
 * {@code shooterVel + speed·û(mount ray)} fallback (NO jitter);
 * {@code det_fallback} / {@code det_uncovered} / {@code det_lookup_miss} = detector-polled
 * passthrough classes; {@code det_identity} = a merger identity tick (knob off / no covered
 * set); {@code n/a} = non-discharge kinds.
 *
 * <p>Instrument only: knob {@code cc.server.gpeeLogPath} (INSTRUMENT class; blank / {@code off}
 * = no sink, the serve loop never calls in). Write-only sink on an operator-configured absolute
 * path (never wire-derived); the weapon id
 * is engine data, still sanitized to single-line printable ASCII. Bounded by {@link #LINE_CAP}
 * (~250 B/line ⇒ ≤ ~500 MB worst case, far above any watch); every failure is fail-soft and
 * LOUD once — a disk error must never crash a serve.
 *
 * <p><b>NO FILE I/O ON THE SIM THREAD (a stutter's root cause).</b> A recorded
 * match (a 14 TB spinning SATA HDD volume also taking the 134 MB stdout log + the 96 MB reel +
 * the kit logs) showed every one of the 18 combat-time 50–185 ms {@code det_edges} stalls on a
 * tick with ≥1 edge — any weapon, any kind — i.e. exactly the ticks this sink used to
 * {@code write}+{@code flush} synchronously from the serve thread (the stdout meter reads
 * {@code io_det=0.00} on those ticks: stdout is a separate handle, this file was the blocked
 * call). The sim thread now only FORMATS the line (pure CPU, ~µs) and hands it to a bounded
 * {@link ArrayBlockingQueue}; one daemon writer thread drains, writes and flushes (one flush per
 * burst, not per tick). Queue full = the line is DROPPED and counted (LOUD once) — the sim
 * thread never blocks on this sink, under any disk condition ({@code frame-loop-residency-earned}:
 * instrumentation defaults off-frame). Cross-thread state: the queue (hand-off) + volatiles
 * (retired flag, writer-side counters/error, read at close only).
 */
public final class GameplayEdgeEventWatchSink implements AutoCloseable {

    /** Hard line bound (a runaway multi-round soak can never fill the disk). */
    static final int LINE_CAP = 2_000_000;
    /** Bounded sim→writer hand-off (~250 B/line ⇒ ≤ ~2 MB resident); full = drop + count. */
    static final int QUEUE_CAP = 8192;
    /** writer-thread close signal (identity-compared; never a formatted line). */
    private static final String CLOSE_SIGNAL = new String("");
    /** How long close() waits for the writer thread to drain before interrupting it. */
    private static final long CLOSE_JOIN_MILLIS = 2_000L;

    private final String path;
    private final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAP);
    private final Thread writerThread;
    /** Sim-thread view: the sink stopped accepting (cap / close / writer failure). */
    private volatile boolean retired;
    /** Sim thread only: lines handed to the queue / lines dropped on a full queue. */
    private long lines;
    private long dropped;
    /** Writer thread only (volatile for the close receipt): lines written / first failure. */
    private volatile long written;
    private volatile String error = "";

    private GameplayEdgeEventWatchSink(String path, BufferedWriter writer) {
        this.path = path;
        this.writerThread = new Thread(() -> drain(writer), "gpee-sink");
        this.writerThread.setDaemon(true);
    }

    /**
     * Open (create/truncate) the sink at {@code path}, creating parent directories, and start
     * the writer thread; prints {@code npeer-serve gpee_log path=…} on success and returns
     * {@code null} after a LOUD {@code gpee_log_open_failed} line on any failure (fail-soft: the
     * serve runs without it).
     */
    public static GameplayEdgeEventWatchSink openOrNull(String path) {
        try {
            Path target = Path.of(path);
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8);
            GameplayEdgeEventWatchSink sink = new GameplayEdgeEventWatchSink(path, writer);
            sink.writerThread.start();
            System.out.println("npeer-serve gpee_log path=" + path + " writer=off-thread");
            return sink;
        } catch (Throwable open) {
            System.out.println("npeer-serve gpee_log_open_failed path=" + path
                    + " error=" + open.getClass().getName()
                    + ": " + (open.getMessage() == null ? "" : open.getMessage()));
            return null;
        }
    }

    /**
     * Append this tick's effective edge stream (post-merge, the list the decl dispatch + DOWN
     * compose consume); discharge tiers/weapon ids come from {@code merger}'s per-tick tags
     * ({@link OnFireTruthMerger#tagOf} — {@code null} = identity tick or non-discharge kind).
     * Serve thread: formats + enqueues only — never touches the file.
     */
    public void appendTick(long round, List<GameplayEdgeEvent> effectiveEdges,
            OnFireTruthMerger merger) {
        if (retired || effectiveEdges.isEmpty()) {
            return;
        }
        try {
            for (GameplayEdgeEvent event : effectiveEdges) {
                if (lines >= LINE_CAP) {
                    System.out.println("npeer-serve gpee_log_failed path=" + path + " lines=" + lines
                            + " error=line cap " + LINE_CAP + " reached — sink stopped, serve continues");
                    retire();
                    return;
                }
                String tier = "n/a";
                String weaponId = null;
                if (event.kind() == GameplayEdgeEventKind.WEAPON_DISCHARGE) {
                    OnFireTruthMerger.SourceTag tag =
                            merger == null ? null : merger.tagOf(event.entityId(), event.mountIndex());
                    tier = tag == null ? "det_identity" : tag.tier();
                    weaponId = tag == null ? null : tag.weaponId();
                }
                String canonical = GameplayEdgeEventNdjsonLog.format(event);
                StringBuilder line = new StringBuilder(canonical.length() + 64);
                line.append(canonical, 0, canonical.length() - 1)
                        .append(",\"round\":").append(round)
                        .append(",\"tier\":\"").append(tier).append('"')
                        .append(",\"weaponId\":");
                if (weaponId == null) {
                    line.append("null");
                } else {
                    line.append('"').append(printableAscii(weaponId)).append('"');
                }
                line.append("}\n");
                if (queue.offer(line.toString())) {
                    lines++;
                } else {
                    dropped++;
                    if (dropped == 1) {
                        System.out.println("npeer-serve gpee_log_drop path=" + path
                                + " lines=" + lines + " (queue full — the writer thread is behind"
                                + " the disk; dropping, never blocking the serve thread; dropped="
                                + " on the close row)");
                    }
                }
            }
        } catch (RuntimeException failure) {
            System.out.println("npeer-serve gpee_log_failed path=" + path + " lines=" + lines
                    + " error=" + failure.getClass().getName() + ": "
                    + (failure.getMessage() == null ? "" : failure.getMessage()));
            retire();
        }
    }

    /** Writer thread body: take → write; flush once per drained burst; retire LOUD on failure. */
    private void drain(BufferedWriter writer) {
        try (writer) {
            while (true) {
                String line = queue.take();
                if (line == CLOSE_SIGNAL) {
                    break;
                }
                writer.write(line);
                written++;
                if (queue.isEmpty()) {
                    writer.flush();
                }
            }
            writer.flush();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException failure) {
            error = failure.getClass().getName() + ": "
                    + (failure.getMessage() == null ? "" : failure.getMessage());
            System.out.println("npeer-serve gpee_log_failed path=" + path + " lines=" + written
                    + " error=" + error);
            retired = true;
        }
    }

    /** Single-line printable-ASCII law for the one engine string on the line (bounded, no quotes
     *  or backslashes — a JSON-safe token without an escaper). */
    private static String printableAscii(String raw) {
        int n = Math.min(raw.length(), 48);
        StringBuilder out = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            char c = raw.charAt(i);
            out.append(c >= 0x20 && c <= 0x7E && c != '"' && c != '\\' ? c : '?');
        }
        return out.toString();
    }

    /** Stop accepting and signal the writer to finish (later appends are no-ops). */
    private void retire() {
        retired = true;
        queue.offer(CLOSE_SIGNAL); // full queue: close() interrupts after the join window
    }

    /** Lines handed to the writer so far (the close receipt's count). */
    public long lines() {
        return lines;
    }

    /** Serve end (round over — blocking briefly here is fine): drain, join bounded, receipt. */
    @Override
    public void close() {
        retired = true;
        try {
            if (!queue.offer(CLOSE_SIGNAL, CLOSE_JOIN_MILLIS, TimeUnit.MILLISECONDS)) {
                writerThread.interrupt();
            }
            writerThread.join(CLOSE_JOIN_MILLIS);
            if (writerThread.isAlive()) {
                writerThread.interrupt();
                error = error.isEmpty() ? "close: writer thread did not drain in "
                        + CLOSE_JOIN_MILLIS + " ms (interrupted)" : error;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        System.out.println("npeer-serve gpee_log_closed path=" + path + " lines=" + written
                + " queued=" + lines + " dropped=" + dropped
                + " error=" + (error.isEmpty() ? "none" : error));
    }
}
