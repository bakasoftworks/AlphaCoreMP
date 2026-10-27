package cc.starsector.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Per-round client worst-frames ledger — stutter leg 2:
 * the client twin of the server's
 * {@code worst_frame} rows, so a client-side hitch self-reports WITH attribution from the run it
 * happened in instead of commissioning a fresh forensic watch.
 *
 * <p>Sample source: {@code measureFrameClock}'s already-computed {@code realDt} — entry-anchored
 * (immune to advance()'s early-return maze) and pause-gap-filtered. Frames excluded as pause/load
 * gaps (&gt; 0.5 s) are NOT ranked (a paused game would top every round) but are confessed as a
 * count + max on the round summary line ({@link #observeGap}). The summary line prints even when
 * the ring is empty — it is the "instrument was ON" witness a default-OFF diagnostic needs.
 *
 * <p>Attribution: PRCP {@link #note}s compile-time-literal cause keys on event frames ("walk" =
 * parity walk paid its getter burst; "xfer" = transfer edges intaken; "spawn" = replica bodies
 * minted; "reset" = round boundary rebuild; "split" = hulk-split family close-out, noted by
 * {@code FractureFamilyCoordinator} — heavy presentation frames self-attribute).
 * Cause work generally runs BEFORE the frame's own {@code measureFrameClock} call, so its wall
 * cost lands in the dt observed one frame LATER — the server ledger's one-tick adjacency, carried
 * the same way: the next entry rides the causes as {@code prev:} when its own frame was quiet.
 * {@code first=} marks a cause class's first occurrence this PROCESS (static set — a fresh
 * combat's plugin instance must not re-mint "first" for an already-warm class): the JVM
 * first-execution treadmill signature the leg-1 server fix convicted. The "walk"
 * tag is the walker-load decision instrument: if walk ticks don't correlate with
 * client worst frames, accept-as-is wins free.
 *
 * <p>Rung 1 — the F9 retro-dump ring: every measured frame (pause gaps included,
 * tagged "gap") also lands in a fixed retro ring covering the last ~10 s wall. An operator-marker
 * press ({@link #dumpMarker}) dumps the window's top-{@value #MARKER_TOP_K} frames at a ~5 ms
 * floor plus ≥5/≥10/≥20 ms counts, keyed {@code marker #N} — the felt-stutter detection floor
 * drops 33.3 → ~5 ms at ZERO steady-state rows (the felt stutters live below every
 * steady-state instrument floor; only a press pays a dump). Rung 3 — windowed re-arm: the top-K
 * rank ring flushes + re-arms every ~{@value #WINDOW_REARM_TICKS} authoritative ticks, so
 * world-load can never saturate a whole round's rank rows again (the bank's blindness);
 * window-flush summaries carry round-cumulative counters, frame ordinals stay round-local.
 *
 * <p>Knob {@code -Dcc.parity.frameLedger} then env {@code CC_PARITY_FRAME_LEDGER} ("1"/"true" =
 * ON), resolved once at class load, fail-closed OFF — the walker idiom. Watch launchers set the
 * env; the player kit sets nothing, so players run zero rows (never rides the kit).
 * <b>Debug-match arm:</b> the ledger ALSO arms whenever the served match is a
 * debug match ({@link MatchConfigFromWire#clientDebug()}, WELCOME bit1 — server-settable, so a
 * kit-launched operator window on a debug serve is never frame-time-blind again regardless of
 * launcher env; F9 marks once fell under every floor because only the env arm existed).
 * {@link #active()} = env arm OR the live wire bit (one volatile read per call). The retro ring's
 * arrays allocate LAZILY on the first active frame (players on a public serve pay zero bytes).
 *
 * <p>Render-thread only (advance()-driven), no locking. B-clean by construction: nanos + arrays
 * + StringBuilder + {@link PilotWatchLog} only — no io/nio-file/reflect/prefs/script refs. Cause
 * keys are compile-time literals; no wire-derived text reaches this sink.
 */
final class ClientWorstFramesLedger {

    /** Resolved once at class load (walker idiom): sysprop, then env, fail-closed OFF. */
    private static final boolean ENABLED = resolveEnabled();

    private static boolean resolveEnabled() {
        try {
            String raw = System.getProperty("cc.parity.frameLedger");
            if (raw == null || raw.isBlank()) {
                raw = System.getenv("CC_PARITY_FRAME_LEDGER");
            }
            if (raw == null || raw.isBlank()) {
                return false;
            }
            raw = raw.trim();
            if ("1".equals(raw) || "true".equalsIgnoreCase(raw)) {
                return true;
            }
            FallbackAnnounce.announceOnce("frame-ledger-knob-unparseable",
                    "cc.parity.frameLedger=" + raw + " unrecognized -> ledger OFF");
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final class Entry {
        long dtNanos; // rank key: the frame's unclamped wall dt
        long tick;    // latest authoritative simTick at observe (-1 = no feed yet)
        long frame;   // round-local measured-frame ordinal
        float amountMs; // the engine's CLAMPED dt — shows exactly what the clamp hid
        String causes;
        boolean first;
        float advMs;  // The plugin's OWN advance() wall INSIDE this frame's window
        float advPostPrevMs; // the part of advMs carried from the PREVIOUS advance's post-clock remainder
        float rndMs;  // the plugin's OWN renderInUICoords() wall inside the window (the previous frame's pass)
    }

    /** Process-lifetime first-seen cause classes (the warmup-treadmill "first" notion). */
    private static final Set<String> SEEN_CAUSE_KEYS = new HashSet<>();

    /** rank-ring re-arm cadence in authoritative ticks (~60 s at 60 Hz). */
    private static final int WINDOW_REARM_TICKS = 3600;
    /** retro-ring size (power of two; ~10 s of frames even at the 540 fps cap). */
    private static final int RING_CAPACITY = 8192;
    /** How far back a marker dump looks (~10 s wall). */
    private static final long RING_WINDOW_NANOS = 10_000_000_000L;
    /** dump-row floor (~5 ms) — steady 3–4 ms frames print ZERO rows. */
    private static final long MARKER_ROW_FLOOR_NANOS = 5_000_000L;
    /** Rows per marker dump (the rank ring's own top-8 idiom). */
    private static final int MARKER_TOP_K = 8;

    private final int capacity;
    private final List<Entry> entries = new ArrayList<>();
    /** Causes noted since the last observe (= the frame in flight); swapped, joined lazily. */
    private ArrayDeque<String> curCauses = new ArrayDeque<>(4);
    private ArrayDeque<String> prevCauses = new ArrayDeque<>(4);
    private boolean curFirst;
    private boolean prevFirst;
    private long frames;      // measured frames this round
    private long gapFrames;   // pause-gap-excluded frames this round
    private double maxGapSec; // worst excluded gap this round
    private int round = 1;    // client-local ordinal: 1 + resets seen (client has no wire round id)
    /** Rung 3 window anchor: first authoritative tick of the current rank window (-1 = unseeded). */
    private long windowStartTick = -1L;

    // Rung 1 retro ring — parallel arrays, allocated only when ON, overwritten in place
    // (zero steady-state allocation; the causes String is the entry's already-joined one or null).
    private long[] ringStampNanos;
    private long[] ringDtNanos;
    private long[] ringTick;
    private float[] ringAmountMs;
    private String[] ringCauses;
    private boolean[] ringFirst;
    private int ringNext;
    private long ringWrites;
    /** The WALL SPLIT: {@code total_ms} is the render-thread
     *  frame wall (clock point to clock point), {@code amount_ms} the engine's clamped sim dt;
     *  neither says how much of the frame the PLUGIN itself spent. These carry the plugin's own
     *  advance() and renderInUICoords() wall — measured BEFORE any per-lane timer is added (a
     *  hitch that is not ours needs no lane attribution at all).
     *
     *  <p><b>IN-WINDOW stamping.</b> A slot is fed at {@code measureFrameClock},
     *  which runs MID-advance, so its {@code total_ms} window is [previous clock point, this clock
     *  point] = the previous advance's POST-clock remainder + the previous frame's render pass +
     *  this advance's PRE-clock part. The pre-fix ledger patched the WHOLE advance wall onto the
     *  slot fed during it, so post-clock work (the reconcile, the death presenter, the missile
     *  lane) put its wall in the NEXT slot's {@code total_ms} while its {@code adv_ms} sat on a
     *  small-total slot that never ranked — the source of an "adv_ms small ⇒ not ours" misread.
     *  Now every plugin nanosecond since the last clock point accumulates in the two pending sums
     *  and lands on the slot whose window contains it, complete AT FEED TIME (nothing is patched
     *  after the fact): {@code adv_ms} = pre + post_prev, {@code adv_post_prev_ms} = the carried
     *  half alone (which side of the clock point the wall sat on), {@code rnd_ms} = the render
     *  wall inside the window. An advance that reaches no clock point (paused / early return)
     *  carries its whole wall forward the same way. */
    private float[] ringAdvMs;
    private float[] ringAdvPostPrevMs;
    private float[] ringRndMs;
    /** This advance's entry stamp ({@link #noteAdvanceEntry}); valid while {@link #advInFlight}. */
    private long advEntryNanos;
    private boolean advInFlight;
    /** This advance's clock point (the ledger's own feed/seed stamp); valid while {@link #clockedThisAdvance}. */
    private long clockNanos;
    private boolean clockedThisAdvance;
    /** Plugin advance / render wall accumulated since the last clock point (the next slot's window). */
    private long pendingAdvNanos;
    private long pendingRndNanos;
    /** Ring slot fed by the latest observe (the rank entry copies its stamped walls). */
    private int lastFed;

    ClientWorstFramesLedger(int capacity) {
        this.capacity = capacity;
        if (ENABLED) {
            ensureRing();
        }
    }

    /** ON when the env/sysprop knob armed it OR the served match is a debug match
     *  (WELCOME bit1, read live — goes dead the frame the bit reads false, like the members). */
    private static boolean active() {
        return ENABLED || MatchConfigFromWire.clientDebug();
    }

    /** Lazy ring allocation: the wire bit lands after construction, so the ring is
     *  minted on the first active frame; a no-op once allocated. */
    private void ensureRing() {
        if (ringStampNanos == null) {
            ringStampNanos = new long[RING_CAPACITY];
            ringDtNanos = new long[RING_CAPACITY];
            ringTick = new long[RING_CAPACITY];
            ringAmountMs = new float[RING_CAPACITY];
            ringCauses = new String[RING_CAPACITY];
            ringFirst = new boolean[RING_CAPACITY];
            ringAdvMs = new float[RING_CAPACITY];
            ringAdvPostPrevMs = new float[RING_CAPACITY];
            ringRndMs = new float[RING_CAPACITY];
        }
    }

    /** Entry of the plugin's advance(): the stamp the next clock point measures its PRE part from. */
    void noteAdvanceEntry(long entryNanos) {
        advEntryNanos = entryNanos;
        advInFlight = true;
        clockedThisAdvance = false;
    }

    /** {@code measureFrameClock}'s first-frame seed (a window boundary with no slot to feed): the
     *  next slot's window starts HERE, so what ran before it is outside every window. */
    void seedClock() {
        clockNanos = System.nanoTime();
        clockedThisAdvance = true;
        pendingAdvNanos = 0L;
        pendingRndNanos = 0L;
    }

    /** Exit of the plugin's advance(): carry the wall spent since this advance's clock point (the
     *  whole advance when it reached none) into the NEXT slot's window. */
    void noteAdvanceExit(long exitNanos) {
        long from = clockedThisAdvance ? clockNanos : advEntryNanos;
        boolean measured = advInFlight;
        clockedThisAdvance = false;
        advInFlight = false;
        if (ringAdvMs == null || !measured || !active()) {
            // OFF (or the debug bit dropped): carry nothing, so a later re-arm starts clean.
            pendingAdvNanos = 0L;
            pendingRndNanos = 0L;
            return;
        }
        pendingAdvNanos += Math.max(0L, exitNanos - from);
    }

    /** Verifier reads ({@code CcFrameLedgerContract}): the latest fed slot's stamped walls (-1 = no ring). */
    float lastAdvMs() {
        return ringAdvMs == null ? -1f : ringAdvMs[lastFed];
    }

    float lastAdvPostPrevMs() {
        return ringAdvPostPrevMs == null ? -1f : ringAdvPostPrevMs[lastFed];
    }

    float lastRndMs() {
        return ringRndMs == null ? -1f : ringRndMs[lastFed];
    }

    /** End of the plugin's renderInUICoords(): render wall inside the next slot's window. */
    void noteRenderWall(long nanos) {
        if (ringRndMs == null) {
            return;
        }
        pendingRndNanos += Math.max(0L, nanos);
    }

    /** Mark a cause on the frame in flight (compile-time literal keys only; deduped per frame). */
    void note(String key) {
        if (!active()) {
            return;
        }
        if (!curCauses.contains(key)) {
            curCauses.add(key);
            if (SEEN_CAUSE_KEYS.add(key)) {
                curFirst = true;
            }
        }
    }

    /** One call per measured frame with measureFrameClock's realDt (pause gaps go to observeGap). */
    void observe(double realDtSec, float amountSec, long simTick) {
        if (!active()) {
            return;
        }
        ensureRing();
        frames++;
        long tick = simTick == Long.MIN_VALUE ? -1L : simTick;
        // Windowed re-arm BEFORE ranking this frame — the flush leaves the
        // in-flight cause window alone (only a round/teardown flush clears it), so an event
        // frame straddling the window edge keeps its attribution.
        if (tick >= 0) {
            if (windowStartTick < 0L || tick < windowStartTick) {
                windowStartTick = tick;
            } else if (tick - windowStartTick >= WINDOW_REARM_TICKS) {
                flushRows("window");
                windowStartTick = tick;
            }
        }
        long dtNanos = (long) (realDtSec * 1_000_000_000.0d);
        // Resolve this frame's attribution ONCE — shared by the rank entry and the retro ring.
        String causes = null;
        boolean first = false;
        if (!curCauses.isEmpty()) {
            causes = String.join(",", curCauses);
            first = curFirst;
        } else if (!prevCauses.isEmpty()) {
            causes = "prev:" + String.join(",", prevCauses);
            first = prevFirst;
        }
        feedRing(dtNanos, tick, amountSec * 1000f, causes, first);
        if (entries.size() < capacity || dtNanos > minRank()) {
            Entry e = new Entry();
            e.dtNanos = dtNanos;
            e.tick = tick;
            e.frame = frames;
            e.amountMs = amountSec * 1000f;
            e.causes = causes == null ? "-" : causes;
            e.first = first;
            e.advMs = ringAdvMs[lastFed];
            e.advPostPrevMs = ringAdvPostPrevMs[lastFed];
            e.rndMs = ringRndMs[lastFed];
            if (entries.size() >= capacity) {
                entries.remove(minIndex());
            }
            entries.add(e);
        }
        // Roll the cause window every frame: what was noted during the frame just observed becomes
        // the NEXT entry's prev: (the one-frame adjacency — javadoc). Swap, don't allocate.
        ArrayDeque<String> spent = prevCauses;
        prevCauses = curCauses;
        prevFirst = curFirst;
        spent.clear();
        curCauses = spent;
        curFirst = false;
    }

    /** A pause/load gap (realDt past the pause threshold): confessed on the summary, never ranked
     * — but it DOES ride the retro ring tagged "gap" (a felt stutter is often exactly a
     * sub-second load gap; a marker press must be able to see it). */
    void observeGap(double realDtSec) {
        if (!active()) {
            return;
        }
        ensureRing();
        gapFrames++;
        if (realDtSec > maxGapSec) {
            maxGapSec = realDtSec;
        }
        feedRing((long) (realDtSec * 1_000_000_000.0d), -1L, 0f, "gap", false);
    }

    private void feedRing(long dtNanos, long tick, float amountMs, String causes, boolean first) {
        long stamp = System.nanoTime();
        ringStampNanos[ringNext] = stamp;
        ringDtNanos[ringNext] = dtNanos;
        ringTick[ringNext] = tick;
        ringAmountMs[ringNext] = amountMs;
        ringCauses[ringNext] = causes;
        ringFirst[ringNext] = first;
        // Close this slot's window HERE — the carried post-clock remainder + renders since
        // the last clock point, plus this advance's pre-clock part (0 with no advance in flight).
        long pre = advInFlight ? Math.max(0L, stamp - advEntryNanos) : 0L;
        ringAdvMs[ringNext] = (pendingAdvNanos + pre) / 1_000_000f;
        ringAdvPostPrevMs[ringNext] = pendingAdvNanos / 1_000_000f;
        ringRndMs[ringNext] = pendingRndNanos / 1_000_000f;
        pendingAdvNanos = 0L;
        pendingRndNanos = 0L;
        clockNanos = stamp;
        clockedThisAdvance = true;
        lastFed = ringNext;
        ringNext = (ringNext + 1) & (RING_CAPACITY - 1);
        ringWrites++;
    }

    /** The F9 retro-dump: top-{@value #MARKER_TOP_K} ring frames ≥5 ms in the
     * last ~10 s + ≥5/≥10/≥20 ms counts, keyed to the owner-marker ordinal so a "shortly after a
     * stutter" press maps straight back in time ({@code age_ms}). Uncapped like the marker row
     * itself (presses are owner-bounded); zero rows at steady state. */
    void dumpMarker(int markerNo, long simTick) {
        if (!active()) {
            return;
        }
        long now = System.nanoTime();
        // epoch_ms = the frame's END on the wall clock (the serve rows' wall_ms law), so a
        // client JFR can be windowed on a marker row; `wall_ms` on this side is a DURATION.
        long nowEpochMs = System.currentTimeMillis();
        int filled = (int) Math.min(ringWrites, (long) RING_CAPACITY);
        int inWindow = 0;
        int ge5 = 0;
        int ge10 = 0;
        int ge20 = 0;
        List<Integer> rows = new ArrayList<>();
        for (int i = 0; i < filled; i++) {
            if (now - ringStampNanos[i] > RING_WINDOW_NANOS) {
                continue;
            }
            inWindow++;
            long dt = ringDtNanos[i];
            if (dt >= MARKER_ROW_FLOOR_NANOS) {
                ge5++;
                rows.add(i);
            }
            if (dt >= 2L * MARKER_ROW_FLOOR_NANOS) {
                ge10++;
            }
            if (dt >= 4L * MARKER_ROW_FLOOR_NANOS) {
                ge20++;
            }
        }
        rows.sort((a, b) -> Long.compare(ringDtNanos[b], ringDtNanos[a]));
        int k = Math.min(rows.size(), MARKER_TOP_K);
        PilotWatchLog.info(String.format(Locale.ROOT,
                "predict stutter-ring marker #%d tick=%d window_frames=%d ge5=%d ge10=%d ge20=%d"
                        + " rows=%d epoch_ms=%d",
                markerNo, simTick == Long.MIN_VALUE ? -1L : simTick, inWindow, ge5, ge10, ge20,
                k, nowEpochMs));
        for (int r = 0; r < k; r++) {
            int i = rows.get(r);
            long ageNanos = now - ringStampNanos[i];
            PilotWatchLog.info(String.format(Locale.ROOT,
                    "predict stutter-ring marker #%d rank=%d age_ms=%.0f tick=%d total_ms=%.2f"
                            + " amount_ms=%.2f causes=%s first=%d adv_ms=%.2f adv_post_prev_ms=%.2f"
                            + " rnd_ms=%.2f epoch_ms=%d",
                    markerNo, r + 1, ageNanos / 1_000_000.0d, ringTick[i],
                    ringDtNanos[i] / 1_000_000.0d, ringAmountMs[i],
                    ringCauses[i] == null ? "-" : ringCauses[i], ringFirst[i] ? 1 : 0,
                    ringAdvMs[i], ringAdvPostPrevMs[i], ringRndMs[i],
                    nowEpochMs - ageNanos / 1_000_000L));
        }
    }

    /** Round boundary: flush the ENDED round's rows, re-arm, and tag the rebuild frame. */
    void resetRound() {
        if (!active()) {
            return;
        }
        flush("round");
        round++;
        note("reset");
    }

    /** Teardown flush — a truncated final round still confesses (server flushRound twin). */
    void flushTeardown() {
        if (!active()) {
            return;
        }
        flush("teardown");
    }

    /** Summary + ranked rows + rank re-arm. Round-cumulative counters: a mid-round "window"
     * flush (rung 3) prints counters-so-far and clears ONLY the rank rows. */
    private void flushRows(String where) {
        PilotWatchLog.info(String.format(Locale.ROOT,
                "predict worst_frame_summary [%s] round=%d frames=%d gaps=%d max_gap_ms=%.0f rows=%d",
                where, round, frames, gapFrames, maxGapSec * 1000.0d, entries.size()));
        entries.sort((a, b) -> Long.compare(b.dtNanos, a.dtNanos));
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            PilotWatchLog.info(String.format(Locale.ROOT,
                    "predict worst_frame round=%d rank=%d tick=%d frame=%d total_ms=%.2f"
                            + " amount_ms=%.2f causes=%s first=%d adv_ms=%.2f adv_post_prev_ms=%.2f"
                            + " rnd_ms=%.2f",
                    round, i + 1, e.tick, e.frame, e.dtNanos / 1_000_000.0d,
                    e.amountMs, e.causes, e.first ? 1 : 0, e.advMs, e.advPostPrevMs, e.rndMs));
        }
        entries.clear();
    }

    private void flush(String where) {
        flushRows(where);
        frames = 0;
        gapFrames = 0;
        maxGapSec = 0d;
        windowStartTick = -1L;
        curCauses.clear();
        prevCauses.clear();
        curFirst = false;
        prevFirst = false;
    }

    private long minRank() {
        long min = Long.MAX_VALUE;
        for (int i = 0; i < entries.size(); i++) {
            min = Math.min(min, entries.get(i).dtNanos);
        }
        return min;
    }

    private int minIndex() {
        int idx = 0;
        for (int i = 1; i < entries.size(); i++) {
            if (entries.get(i).dtNanos < entries.get(idx).dtNanos) {
                idx = i;
            }
        }
        return idx;
    }
}
