package cc.starsector.server.runtime;

import java.util.Locale;

/**
 * The per-session SNAP SEND-gap meter — the serve-side twin of the kit's arrival meter
 * {@code client-mod/src/cc/starsector/client/net/SnapGapMeter.java}.
 *
 * <p>WHY: flight {@code 141854} — the owner marked two "possible
 * stutters"; the kit seat's arrival histogram read {@code ge33=140 ge67=34 max 309.3 ms} against {@code 5 / 0 / 40.1}
 * on seat 1, with no kit frame hitch, no GC and no serve tick overrun near the marks. Nothing on the serve said when
 * each session's snapshots LEFT, so "the serve sent late to that one session" and "the path delivered late" could not
 * be told apart. This meter is the serve half of that split: the same histogram, clocked where the serve's fan hands
 * a session's snapshot chunks to the door ({@code PilotServeSession} fan → {@code
 * PilotIntentDatagramServer.sendTickToSession} → {@code DatagramReliability.snapSend} → the socket, all on the sim
 * thread; {@code PeerSendGate.sendLoop} is test-only and is NOT this site).
 *
 * <p>Mirrored from the kit's {@code SnapGapMeter}: bucket edges {@link #BUCKET_MS}
 * {@code {33,50,67,100,150,250}} ms; each bucket counts EVERY gap ≥ its edge, so
 * {@code ge67 − ge100} = the 67–100 class; gaps ≥ {@link #IDLE_BAR_MS} = the
 * idle / round-flip class, counted alone and kept OUT of the buckets and OUT of {@code max};
 * a gap below the lowest edge returns early, so {@code max} reads 0.0 until a gap ≥ 33 ms lands.
 * NOT mirrored, on purpose: the kit's LOCAL-stall class (a receive-side discriminator —
 * the serve's stamp is taken on the sending thread itself), its stall deque and its atomics (one thread here, below);
 * the kit's {@code 0L} first-SNAP sentinel (a {@code System.nanoTime()} origin is arbitrary and may be any sign, so
 * "armed" is a boolean); {@code clocked} counts GAPS measured (the kit's {@code snapsClocked} counts frames).
 *
 * <p>Kept, cumulative over the session's life (never reset at a round wrap): the six bucket counts, {@code ge1000},
 * {@code max} gap (ms, idle class excluded), {@code clocked} = gaps measured (the first SNAP send clocks no gap), and
 * {@code tick_skips} = sent snapshots whose {@code tick − prevSentTick > downEveryTicks} (off-ticks fan CTRL only, so a
 * delta of exactly the stride is normal) + {@code tick_skip_max} = the largest such delta. A thinned tick
 * ({@code snapSent == false}) is NOT clocked and does not move {@code prevSentTick}. Reading notes: at
 * {@code downEveryTicks = k} the nominal gap is k tick periods (k=2 at 60 Hz = 33.3 ms, so nearly every gap lands in
 * {@code ge33} — read the buckets against the stride, as the kit's arrival meter must); the fan START rotates per tick
 * ({@code fanStart}), so a seat's gaps carry up to one fan pass of jitter — real send-time variation, not removed.
 *
 * <p>Value domains: {@code sendEndNanos} = a {@code System.nanoTime()} value (arbitrary origin, any sign; only
 * differences are read). {@code tick} = the snapshot's simTick, ≥ 0, RESTARTS at 0 every round (fresh source per
 * round, {@code PilotServeSession} {@code lastSimTick = -1L}), so the first send of a new round has a negative delta —
 * never a skip. {@code downEveryTicks} ≥ 1 by the resolver's strict parse ({@code PilotServeResolver}); a value < 1
 * reads as 1.
 *
 * <p>Threads + residency (the frame-loop law: nothing rides the frame loop without earning it): the ONE writer is
 * the serve's sim thread ({@link #record}, from the per-session fan, beside the two {@code System.nanoTime()} stamps
 * the fan already takes — the send-end stamp is {@code sendStartNanos + sendNanos}, NO new clock read). {@link
 * #record}'s body = two subtractions, compares and plain-field writes: zero allocation, no lock, no logging, no
 * atomics. The readers ({@link #exportRow} from the round line, the round_teardown receipt and the serve end;
 * {@link #exportFinalRow} from the session-observe dead loop) run on
 * the SAME sim thread, so the plain fields are coherent by program order — a reader on any other thread needs a
 * publication change here first. Cost measured by {@code SnapSendGapMeterContractRows} bench row
 * ({@code snap_send_meter_bench}).
 *
 * <p>Lifetime: one per {@code PilotIntentDatagramServer.PeerSession} (field {@code snapSendGap}), minted with the
 * session at the door's single {@code new PeerSession(} site; NOT copied on takeover — a re-admitted session (fresh,
 * takeover, rejoin, observer) starts a fresh meter by construction. A session that died between the fan's live
 * snapshot and its send is still clocked once (the door no-ops the send) and is gone from the next printed table;
 * that last gap rides its {@link #exportFinalRow} row (printed once a later frame's session observe sees the death).
 *
 * <p>Export (row contract FIXED — the offline verdict reader parses both heads by the same token names):
 * {@link #exportRow} per LIVE session (cumulative over that session) and {@link #exportFinalRow} once per DEAD
 * session; the reader sums a seat's finals + its last live row. Numbers only, no player text.
 *
 * <p>DELETE TRIGGER: delete this meter, its {@code
 * snap_send_*} tokens and its reader row when two live test runs in a row read {@code CLEAN}
 * or {@code PATH} on every seat, or when the stutter class it splits is closed.
 */
public final class SnapSendGapMeter {
    /** Bucket edges, ms — the kit's {@code SnapGapMeter.BUCKET_MS} verbatim. */
    public static final int[] BUCKET_MS = {33, 50, 67, 100, 150, 250};
    /** Gaps ≥ this (ms) are the idle / round-flip class: counted alone, never bucketed, never in {@code max}. */
    public static final long IDLE_BAR_MS = 1000L;

    private static final long NANOS_PER_MS = 1_000_000L;
    private static final long IDLE_NANOS = IDLE_BAR_MS * NANOS_PER_MS;
    private static final long[] EDGE_NANOS = new long[BUCKET_MS.length];

    static {
        for (int i = 0; i < BUCKET_MS.length; i++) {
            EDGE_NANOS[i] = BUCKET_MS[i] * NANOS_PER_MS;
        }
    }

    // sim-thread-only state (one writer, same-thread readers — see the class javadoc)
    private final long[] ge = new long[BUCKET_MS.length];
    private long ge1000;
    private long maxGapNanos;
    private long clocked;
    private long tickSkips;
    private long tickSkipMax;
    private boolean armed;
    private long lastSendEndNanos;
    private long prevSentTick;

    /**
     * Sim thread, once per session per fanned tick, straight after the door's send returned.
     *
     * @param sendEndNanos   the send-returned stamp (the fan's {@code sendStartNanos + sendNanos}; any sign)
     * @param tick           the snapshot's simTick (≥ 0, restarts each round)
     * @param snapSent       true iff this tick handed ≥ 1 SNAP chunk to the session (false = a thinned tick: CTRL only)
     * @param downEveryTicks the serve's SNAP stride ({@code cc.net.downEveryTicks}, ≥ 1)
     */
    public void record(long sendEndNanos, long tick, boolean snapSent, int downEveryTicks) {
        if (!snapSent) {
            return; // a thinned tick: not clocked, prevSentTick unmoved
        }
        boolean hadPrevious = armed;
        long last = lastSendEndNanos;
        long prevTick = prevSentTick;
        armed = true;
        lastSendEndNanos = sendEndNanos;
        prevSentTick = tick;
        if (!hadPrevious) {
            return; // the first SNAP send clocks no gap
        }
        long skip = tick - prevTick;
        if (skip > (downEveryTicks > 1 ? downEveryTicks : 1)) {
            tickSkips++;
            if (skip > tickSkipMax) {
                tickSkipMax = skip;
            }
        }
        long gap = sendEndNanos - last;
        clocked++;
        if (gap < EDGE_NANOS[0]) {
            return; // the common case: on cadence
        }
        if (gap >= IDLE_NANOS) {
            ge1000++;
            return;
        }
        for (int i = 0; i < EDGE_NANOS.length; i++) {
            if (gap >= EDGE_NANOS[i]) {
                ge[i]++;
            }
        }
        if (gap > maxGapNanos) {
            maxGapNanos = gap;
        }
    }

    // ------------------------------------------------------------------ readers (the sim thread)

    /** Cumulative gaps ≥ {@code BUCKET_MS[bucket]} ms (idle class excluded). */
    public long ge(int bucket) {
        return ge[bucket];
    }

    /** Cumulative gaps ≥ 1 s (the idle / round-flip class; in no bucket, not in {@code max}). */
    public long ge1000() {
        return ge1000;
    }

    /** Longest gap ≥ the lowest edge and < 1 s, ms (0.0 until one lands). */
    public double maxGapMillis() {
        return maxGapNanos / 1e6d;
    }

    /** Gaps measured (every clocked SNAP send after the first). */
    public long clocked() {
        return clocked;
    }

    /** Sent snapshots whose tick advanced by more than the stride since the previous sent one. */
    public long tickSkips() {
        return tickSkips;
    }

    /** The largest such tick delta (0 = none). */
    public long tickSkipMax() {
        return tickSkipMax;
    }

    /**
     * The export row — contract FIXED (the offline verdict reader parses
     * these tokens by name, in this order): {@code npeer-serve snap_send round=<r> seat=<S> wire_id=<w> snap_send_gap_ge33=<n> …
     * snap_send_gap_ge250=<n> snap_send_gap_ge1000=<n> snap_send_gap_max_ms=<x.x> snap_send_clocked=<n>
     * snap_send_tick_skips=<n> snap_send_tick_skip_max=<n>}. {@code round} ≥ 0; {@code seat} = the session's seat id
     * (≥ 1 pilot, 0 observer, −1 unbound smoke door); {@code wireId} = its ship wire id (> 0 hull holder, 0 observer,
     * −1 unbound); {@code max_ms} one decimal under {@link Locale#ROOT} (a comma-decimal host never breaks the reader).
     * This live row is cumulative for ONE session; a seat's flight total = its {@link #exportFinalRow} rows + its last
     * live row.
     */
    public static String exportRow(SnapSendGapMeter m, int round, int seat, long wireId) {
        return buildRow("npeer-serve snap_send round=", m, round, seat, wireId);
    }

    /**
     * The DEAD session's final row: {@link #exportRow}'s tokens in the same order and value domains under the head
     * {@code npeer-serve snap_send_final round=}. WHY: the
     * live row prints only for LIVE sessions (round line, round_teardown receipt, serve end), so a session that died
     * mid-round lost its tail; {@code PilotServeSession}'s session-observe dead loop prints this ONCE per session death,
     * on the sim thread, right after its {@code npeer-serve session_dead} row. A distinct head ON PURPOSE: a reader
     * anchored on {@code ^npeer-serve snap_send round=} never takes a final for a live row. READER = the offline verdict reader
     * ({@code snap_split}): per seat it sums the finals and adds the last live row after
     * them (a final clears the dead session's earlier live rows, which it already includes). DELETE TRIGGER = the
     * {@code snap_split} row's own delete (the class javadoc's).
     */
    public static String exportFinalRow(SnapSendGapMeter m, int round, int seat, long wireId) {
        return buildRow("npeer-serve snap_send_final round=", m, round, seat, wireId);
    }

    /** The one token builder behind both heads ({@code head} ends in {@code round=}). */
    private static String buildRow(String head, SnapSendGapMeter m, int round, int seat, long wireId) {
        StringBuilder sb = new StringBuilder(320);
        sb.append(head).append(round);
        sb.append(" seat=").append(seat);
        sb.append(" wire_id=").append(wireId);
        for (int i = 0; i < BUCKET_MS.length; i++) {
            sb.append(" snap_send_gap_ge").append(BUCKET_MS[i]).append('=').append(m.ge[i]);
        }
        sb.append(" snap_send_gap_ge1000=").append(m.ge1000);
        sb.append(" snap_send_gap_max_ms=").append(String.format(Locale.ROOT, "%.1f", m.maxGapNanos / 1e6d));
        sb.append(" snap_send_clocked=").append(m.clocked);
        sb.append(" snap_send_tick_skips=").append(m.tickSkips);
        sb.append(" snap_send_tick_skip_max=").append(m.tickSkipMax);
        return sb.toString();
    }
}
