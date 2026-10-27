package cc.starsector.server.runtime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * The B.6 embryo operator surface: a periodic ONE-LINE server status print plus
 * the single stdin admin verb {@code stop}. Deliberately nothing richer — per the B.6 trap note,
 * operator surfaces balloon; anything beyond one file + one line + {@code stop} waits for real
 * operators to ask. The per-client correction stats slot is B.1's WATCH-tier instrument, added later.
 *
 * <p>Two sinks per interval, because the scout buffers the server JVM's stdout into
 * {@code events.ndjson} only after exit: a JSON {@code server_status} event to stdout (durable,
 * analyzable post-run) and a human-readable line appended to the status FILE (the LIVE surface —
 * tail it with {@code Get-Content -Wait}). A blank file path disables the file sink.
 *
 * <p>The {@code stop} verb reads {@code System.in} on a daemon thread (the scout launches the server
 * with inherited stdin, so typing {@code stop} + Enter in the watch shell reaches it). It only sets a
 * flag the gate's await/frame loops poll — the normal teardown path (terminal frame, verdict events,
 * enabled_mods restore) still runs, which is the whole point versus a hard kill. EOF or any read
 * error ends the thread silently (fail-soft: a non-console stdin just means no verb).
 */
public final class ServerStatusLine {

    private static volatile boolean stopRequested;
    private static volatile boolean verbInstalled;

    private final long intervalNanos;
    private final Path statusFile;
    private final String modsetHash;
    private final long startNanos;

    private long windowStartNanos;
    private long totalFrames;
    private long windowFrames;
    private long lastSnapshotsTotal;
    private long windowStartSnapshots;

    public ServerStatusLine(int everySeconds, String statusFilePath, String modsetHash) {
        this.intervalNanos = Math.max(1L, everySeconds) * 1_000_000_000L;
        this.modsetHash = modsetHash == null || modsetHash.isBlank() ? "unset" : modsetHash.trim();
        Path file = null;
        if (statusFilePath != null && !statusFilePath.isBlank()) {
            try {
                file = Path.of(statusFilePath.trim());
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                Files.writeString(file, "", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException | RuntimeException ignore) {
                file = null; // fail-soft: no file sink, stdout still carries the events
            }
        }
        this.statusFile = file;
        this.startNanos = System.nanoTime();
        this.windowStartNanos = startNanos;
    }

    /** True once the stdin {@code stop} verb fired; the gate's await/frame loops poll this. */
    public static boolean stopRequested() {
        return stopRequested;
    }

    /** Start the stdin verb reader once per JVM (idempotent, daemon, fail-soft on any stdin shape). */
    public static synchronized void installStopVerb() {
        if (verbInstalled) {
            return;
        }
        verbInstalled = true;
        Thread t = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if ("stop".equalsIgnoreCase(line.trim())) {
                        stopRequested = true;
                        System.out.println("{\"event\":\"server_stop_requested\",\"source\":\"stdin\"}");
                        return;
                    }
                }
            } catch (IOException | RuntimeException ignore) {
                // no usable stdin => no verb; the operator falls back to -Cleanup
            }
        }, "cc-server-stdin");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Count one server frame and print on the interval. {@code snapshotsSentTotal} is the caller's
     * ABSOLUTE down-send counter (the status line computes per-window deltas itself, so the gate
     * just passes the counter it already keeps).
     */
    public void onTick(int clientsConnected, long snapshotsSentTotal) {
        totalFrames++;
        windowFrames++;
        lastSnapshotsTotal = snapshotsSentTotal;
        maybePrint(clientsConnected, "run", false);
    }

    /** Interval print while AWAITING a client (no frame counted; tick/snap read 0 by construction). */
    public void heartbeat(int clientsConnected) {
        maybePrint(clientsConnected, "await", false);
    }

    /** One guaranteed final line at loop end, so even a fast game-free probe records the surface. */
    public void printFinal(int clientsConnected, long snapshotsSentTotal) {
        lastSnapshotsTotal = snapshotsSentTotal;
        maybePrint(clientsConnected, "final", true);
    }

    private void maybePrint(int clients, String phase, boolean force) {
        long now = System.nanoTime();
        long windowNanos = now - windowStartNanos;
        if (!force && windowNanos < intervalNanos) {
            return;
        }
        double windowSeconds = windowNanos / 1_000_000_000.0d;
        double tickPerS = windowSeconds > 0 ? windowFrames / windowSeconds : 0.0d;
        long windowSnapshots = lastSnapshotsTotal - windowStartSnapshots;
        double snapPerS = windowSeconds > 0 ? windowSnapshots / windowSeconds : 0.0d;
        long upSeconds = (now - startNanos) / 1_000_000_000L;
        String line = String.format(Locale.ROOT,
                "[cc-server] %s up=%ds clients=%d tick=%.1f/s snap=%.1f/s frames=%d modset=%s",
                phase, upSeconds, clients, tickPerS, snapPerS, totalFrames, modsetHash);
        System.out.println(String.format(Locale.ROOT,
                "{\"event\":\"server_status\",\"phase\":\"%s\",\"up_s\":\"%d\",\"clients\":\"%d\","
                        + "\"tick_per_s\":\"%.1f\",\"snap_per_s\":\"%.1f\",\"frames\":\"%d\",\"modset\":\"%s\"}",
                esc(phase), upSeconds, clients, tickPerS, snapPerS, totalFrames, esc(modsetHash)));
        if (statusFile != null) {
            try {
                Files.writeString(statusFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException | RuntimeException ignore) {
                // fail-soft: stdout already carried the event
            }
        }
        windowStartNanos = now;
        windowFrames = 0;
        windowStartSnapshots = lastSnapshotsTotal;
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            if (c < 0x20) {
                sb.append(' ');
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
