package cc.starsector.server.runtime;

import cc.starsector.server.bridge.EngineBackedCombatStepBoundary;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Always-on per-round worst-frames ledger — the discovery-treadmill killer.
 *
 *
 * <p>Every serve frame reports its bracket costs here ({@link #observe}); the ledger keeps the
 * top-{@code capacity} frames per round ranked by work+tail nanos and emits them as single-line
 * {@code npeer-serve worst_frame} rows at the round boundary ({@link #flushRound} — called at
 * both the round-summary and round_teardown formatter sites, so a truncated round still
 * confesses). Unlike {@code work_frame_stall} (>100 ms, unconditional but threshold-gated),
 * these rows exist EVERY round on EVERY serve shape including lean feel-watch serves — the
 * point is that a future stutter self-reports WITH attribution from the run it happened in,
 * instead of commissioning a fresh forensic serve.
 *
 * <p>Attribution riders on each row: the frame's per-phase bracket split (pre|step|det|comp|
 * send|intent|tail + adv/cap, plus the pre sub-split pre_op|pre_sess|pre_seat|pre_wave —
 * a measured stutter was pre=115 ms causes=- because the
 * pre lane's silent phases (operator-file poll, session observe/receipt, seat fly-in, wave
 * drain) were indistinguishable; the sub-split names which one burned, and the remainder
 * pre-(op+sess+seat+wave) confesses out-of-lane time such as safepoint/scheduler theft),
 * the cause-fan events the loop {@link #note}d on that sim tick
 * (previous tick's causes ride as {@code prev:} when the stall tick itself was quiet — the
 * one-tick adjacency the specimens straddle), and a {@code first=} tag: whether any noted
 * cause key was the FIRST of its class this process — the JVM first-execution treadmill
 * signature (cause keys are compile-time literals at the note sites; no wire-derived text
 * reaches this sink — the single-line printable law).
 *
 * <p>Frame-loop residency: the steady-state cost
 * is one long compare per frame (below-floor early-out); notes fire only on event ticks.
 * Sim-thread only, no locking — same discipline as the loop's other per-frame state.
 */
final class WorstFramesLedger {

    private static final class Entry {
        long rankNanos;
        long tick;
        long frame;
        long workNanos;
        long[] phaseNanos; // pre, step, det, comp, send, intent, tail
        long advNanos;
        long capNanos;
        long[] preSubNanos; // op, sess, seat, wave (partition of pre; remainder = bookkeeping)
        long[] advSubNanos; // Eng, post (partition of adv), reinj (slice of eng)
        long advCpuNanos; // sim-thread CPU inside eng (-1 = unmeasurable)
        long wallMs; // Epoch ms at admission (= end of the frame's work+tail)
        String causes;
        boolean first;
    }

    private static final String[] PHASE_NAMES =
            {"pre", "step", "det", "comp", "send", "intent", "tail"};

    private final int capacity;
    private final List<Entry> entries = new ArrayList<>();
    /** Process-lifetime first-seen cause classes (the warmup-treadmill "first" notion). */
    private final Set<String> seenCauseKeys = new HashSet<>();
    private final ArrayDeque<String> tickCauses = new ArrayDeque<>(4);
    private long causeTick = Long.MIN_VALUE;
    private boolean tickFirst;
    private String prevCauses = "";
    private long prevCausesTick = Long.MIN_VALUE;

    /** Stutter BUDGET: a frame at/over this (work+tail) is a felt hitch (2 ticks). */
    static final long HITCH_NANOS = 33_400_000L;
    /** A frame at/over this is a STALL — the budget's counted class (the bar). */
    static final long STALL_NANOS = 100_000_000L;
    /** Per-round budget counters (frame 0 exempt); reset at flush. */
    private long budgetFrames;
    private long budgetOver33;
    private long budgetOver100;
    private long budgetOver100First;
    private long budgetMaxNanos;
    private long budgetMaxTick = -1L;

    WorstFramesLedger(int capacity) {
        this.capacity = capacity;
    }

    /** Mark a cause-fan event on {@code tick} (compile-time literal keys only; deduped per tick). */
    void note(long tick, String key) {
        if (tick != causeTick) {
            rollTick(tick);
        }
        if (!tickCauses.contains(key)) {
            tickCauses.add(key);
            if (seenCauseKeys.add(key)) {
                tickFirst = true;
            }
        }
    }

    /** An ATTRIBUTION note — names the dominant det sub-bracket of a
     *  stall tick ({@code det_*} keys). Deliberately does NOT touch the first-seen set, so the
     *  {@code first=} column keeps its warmup-treadmill meaning. */
    void noteAttrib(long tick, String key) {
        if (tick != causeTick) {
            rollTick(tick);
        }
        if (!tickCauses.contains(key)) {
            tickCauses.add(key);
        }
    }

    /** One call per frame, after the tail bracket is measured. Ranks by work+tail. */
    void observe(long tick, long frame, long workNanos,
            long preNanos, long stepNanos, long detNanos, long compNanos,
            long sendNanos, long intentNanos, long tailNanos,
            long advNanos, long capNanos,
            long preOpNanos, long preSessNanos, long preSeatNanos, long preWaveNanos,
            long advEngNanos, long advPostNanos, long reinjNanos, long advCpuNanos) {
        long rank = workNanos + tailNanos;
        // The budget count BEFORE the top-N admission (every frame counts, frame 0 exempt).
        if (frame != 0L) {
            budgetFrames++;
            if (rank >= HITCH_NANOS) {
                budgetOver33++;
            }
            if (rank >= STALL_NANOS) {
                budgetOver100++;
                if (tick == causeTick && tickFirst) {
                    budgetOver100First++;
                }
            }
            if (rank > budgetMaxNanos) {
                budgetMaxNanos = rank;
                budgetMaxTick = tick;
            }
        }
        if (entries.size() >= capacity && rank <= minRank()) {
            return;
        }
        Entry e = new Entry();
        e.rankNanos = rank;
        e.tick = tick;
        e.frame = frame;
        e.workNanos = workNanos;
        e.phaseNanos = new long[] {preNanos, stepNanos, detNanos, compNanos,
                sendNanos, intentNanos, tailNanos};
        e.advNanos = advNanos;
        e.capNanos = capNanos;
        e.preSubNanos = new long[] {preOpNanos, preSessNanos, preSeatNanos, preWaveNanos};
        e.advSubNanos = new long[] {advEngNanos, advPostNanos, reinjNanos};
        e.advCpuNanos = advCpuNanos;
        // The JFR anchor — stamped only for ADMITTED entries (past the top-N early return),
        // so the steady-state per-frame cost of this ledger stays one compare.
        e.wallMs = System.currentTimeMillis();
        if (tick == causeTick && !tickCauses.isEmpty()) {
            e.causes = String.join(",", tickCauses);
            e.first = tickFirst;
        } else if (tick == prevCausesTick + 1 && !prevCauses.isEmpty()) {
            e.causes = "prev:" + prevCauses;
        } else {
            e.causes = "-";
        }
        if (entries.size() >= capacity) {
            entries.remove(minIndex());
        }
        entries.add(e);
    }

    /** Emit the round's ranked rows and reset per-round state (first-seen set persists). */
    void flushRound(int round) {
        // The operator-visible stutter BUDGET — "fixed" is measurable, not felt
        // (the stutter history: three attributions, never a bound, no agreed bar). PASS = zero
        // frames ≥100 ms after frame 0 in this flush window; over33 = felt-hitch count (info);
        // over100_first = stalls whose cause class ran its first execution (warmup treadmill).
        if (budgetFrames > 0) {
            System.out.println(String.format(Locale.ROOT,
                    "npeer-serve stutter_budget round=%d frames=%d over33=%d over100=%d"
                            + " over100_first=%d max_ms=%.2f max_tick=%d budget_over100=0 verdict=%s",
                    round, budgetFrames, budgetOver33, budgetOver100, budgetOver100First,
                    budgetMaxNanos / 1_000_000.0d, budgetMaxTick,
                    budgetOver100 == 0 ? "PASS" : "FAIL"));
            budgetFrames = 0;
            budgetOver33 = 0;
            budgetOver100 = 0;
            budgetOver100First = 0;
            budgetMaxNanos = 0;
            budgetMaxTick = -1L;
        }
        if (entries.isEmpty()) {
            return;
        }
        entries.sort((a, b) -> Long.compare(b.rankNanos, a.rankNanos));
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            int dominant = 0;
            for (int p = 1; p < e.phaseNanos.length; p++) {
                if (e.phaseNanos[p] > e.phaseNanos[dominant]) {
                    dominant = p;
                }
            }
            System.out.println(String.format(Locale.ROOT,
                    "npeer-serve worst_frame round=%d rank=%d tick=%d frame=%d total_ms=%.2f"
                            + " dominant=%s pre=%.2f step=%.2f det=%.2f comp=%.2f send=%.2f"
                            + " intent=%.2f tail=%.2f adv=%.2f cap=%.2f causes=%s first=%d"
                            + " pre_op=%.2f pre_sess=%.2f pre_seat=%.2f pre_wave=%.2f"
                            // Appended fields only: adv= split into the engine's
                            // inner advance vs project post-step; ftreinj= = shim slice of adv_eng.
                            + " adv_eng=%.2f adv_post=%.2f ftreinj=%.2f"
                            // Appended fields only: adv_cpu= (sim-thread CPU
                            // inside adv_eng; -1 = unmeasurable) + wall_ms= (epoch ms at
                            // admission — the serve.jfr anchor).
                            + " adv_cpu=%.2f wall_ms=%d",
                    round, i + 1, e.tick, e.frame, e.workNanos / 1_000_000.0d,
                    PHASE_NAMES[dominant],
                    e.phaseNanos[0] / 1_000_000.0d, e.phaseNanos[1] / 1_000_000.0d,
                    e.phaseNanos[2] / 1_000_000.0d, e.phaseNanos[3] / 1_000_000.0d,
                    e.phaseNanos[4] / 1_000_000.0d, e.phaseNanos[5] / 1_000_000.0d,
                    e.phaseNanos[6] / 1_000_000.0d,
                    e.advNanos / 1_000_000.0d, e.capNanos / 1_000_000.0d,
                    e.causes, e.first ? 1 : 0,
                    e.preSubNanos[0] / 1_000_000.0d, e.preSubNanos[1] / 1_000_000.0d,
                    e.preSubNanos[2] / 1_000_000.0d, e.preSubNanos[3] / 1_000_000.0d,
                    e.advSubNanos[0] / 1_000_000.0d, e.advSubNanos[1] / 1_000_000.0d,
                    e.advSubNanos[2] / 1_000_000.0d,
                    EngineBackedCombatStepBoundary.advCpuMs(e.advCpuNanos), e.wallMs));
        }
        entries.clear();
        tickCauses.clear();
        causeTick = Long.MIN_VALUE;
        tickFirst = false;
        prevCauses = "";
        prevCausesTick = Long.MIN_VALUE;
    }

    private void rollTick(long tick) {
        prevCauses = tickCauses.isEmpty() ? "" : String.join(",", tickCauses);
        prevCausesTick = causeTick;
        tickCauses.clear();
        tickFirst = false;
        causeTick = tick;
    }

    private long minRank() {
        long min = Long.MAX_VALUE;
        for (int i = 0; i < entries.size(); i++) {
            min = Math.min(min, entries.get(i).rankNanos);
        }
        return min;
    }

    private int minIndex() {
        int idx = 0;
        for (int i = 1; i < entries.size(); i++) {
            if (entries.get(i).rankNanos < entries.get(idx).rankNanos) {
                idx = i;
            }
        }
        return idx;
    }
}
