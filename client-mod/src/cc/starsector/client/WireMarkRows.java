package cc.starsector.client;

import cc.starsector.client.net.PredictReconcileDatagramSession;
import cc.starsector.client.net.SnapGapMeter;
import cc.starsector.server.runtime.DatagramReliability;

import java.util.Locale;

/**
 * The WIRE side of a player's F9 press.
 * The stutter-ring dumps the
 * FRAME side (render dt), the serve's {@code worst_frame} rows the SIM side; this row is the
 * DOWN-PATH side: the reliability core's receiver-side snapshot drop estimate + hi-seen, the
 * chunk merger's AWSD epoch drops, the NACKs this client sent, and the interp lane's
 * arrival/jitter EWMAs — each as its cumulative value AND its delta since the previous mark
 * (mark #1: since the counters' birth), so ONE row answers "frames or wire?" at the press.
 * The read that motivates it: one flight's marks #1/#2 sat inside a 4-minute
 * tunnel-path storm no frame ring could see (seat 2 {@code awsd_dropped_epoch} 232 → 2078
 * while seat 1 on the same server read 0), reconstructed from ~4000 periodic rows by hand.
 *
 * <p>Two consumers: {@link #markRow} on the F9 press (right after the stutter-ring dump);
 * {@link #periodicTokens} appended to the merger-meter flush so the storm TIMELINE carries the
 * reliability gauges too (appended fields only — the grammar rule). The reliability core is
 * re-created on a re-attach, so a mark delta can read negative across one: shown raw, never
 * clamped (the sign IS the re-attach witness). Numbers only, no player text; render thread;
 * never throws (a fault yields one row saying so). B-clean: String/Locale/PilotWatchLog only.
 *
 * <p>One flight's marks read a CLEAN chain (reader, drops, GC, frames)
 * while the player saw the enemies hitch — so the row now also carries the merger's freshness
 * levers ({@code stale_folded} / {@code stale_fold_queued} / {@code snap_mixed_tick_max}, each with
 * its mark delta: a folded tick = a replica jumping two ticks at once) and the
 * {@link FoeStepMeter} window (which replica stepped how far vs its own speed × one tick, the
 * largest correction the glide/SNAP law applied, snaps) — "which enemy jumped and why" at the press.
 */
final class WireMarkRows {
    private static long prevHiSeen;
    private static long prevDropEst;
    private static long prevEpochDrops;
    private static long prevNackTx;
    private static long prevBusyGe50; // reader stall meter
    private static long prevOldTick; // freshness conflation count
    private static long prevSuperseded; // catch-up discard count
    private static long prevStaleFolded; // freshness lever
    private static long prevStaleFoldQueued; // freshness lever
    private static long prevMixedTickMax; // freshness lever
    private static long prevWallNanos = Long.MIN_VALUE;

    private WireMarkRows() {
    }

    /** The merger-meter flush tokens: the core's SNAP gauges (empty before a core exists). */
    static String periodicTokens(PredictReconcileCombatPlugin plugin) {
        PredictReconcileDatagramSession d = udp(plugin);
        DatagramReliability.Stats s = d == null ? null : d.reliabilityStats();
        if (s == null) {
            return "";
        }
        // The reader-thread stall meter + the freshness conflation counts ride
        // beside the loss gauges so a storm's timeline says stall-vs-path per flush.
        // snap_superseded_dropped = the un-taken chunks a newer tick's clear discarded
        // (the catch-up discard itself; old_tick_dropped counts only the older-tick refusals).
        // Three SNAP-gap counts ride the timeline (the full histogram is on
        // the mark row + the teardown receipt) — a storm's flushes then say wire-vs-local per flush.
        SnapGapMeter g = d.snapGapMeter();
        return " snap_hi_seen=" + s.snapHiSeen()
                + " snap_fresh=" + s.snapFreshDelivered()
                + " snap_stale_dropped=" + s.snapStaleDropped()
                + " snap_drop_est=" + s.snapDropEst()
                + String.format(Locale.ROOT, " reader_busy_max_ms=%.1f reader_busy_ge50=%d"
                        + " reader_gap_max_ms=%.1f old_tick_dropped=%d snap_superseded_dropped=%d",
                        d.readerBusyMaxMillis(), d.readerBusyOver50Ms(),
                        d.readerGapMaxMillis(), d.snapOldTickDropped(), d.snapSupersededDropped())
                + " snap_gap_ge33=" + g.wireGeMs(33)
                + " snap_gap_ge67=" + g.wireGeMs(67)
                + " snap_gap_local_ge33=" + g.localGe33();
    }

    /** The SNAP-gap receipt at teardown (plugin thread) — the whole-session
     * histogram, the maxima, the resolved stall bar and the deque's size. Numbers only. */
    static void teardownRow(PredictReconcileDatagramSession udp) {
        SnapGapMeter g = udp.snapGapMeter();
        PilotWatchLog.info("predict teardown: snap gap histogram"
                + g.cumulativeTokens()
                + String.format(Locale.ROOT, " snap_gap_max_ms=%.1f snap_local_max_ms=%.1f"
                        + " snaps_clocked=%d stall_bar_ms=%d stalls_kept=%d",
                        g.wireMaxMillis(), g.localMaxMillis(), g.snapsClocked(),
                        g.stallBarMillis(), g.stallsSnapshot().length));
    }

    private static final long[] prevSnapGapGe = new long[SnapGapMeter.BUCKET_MS.length]; // P0
    private static long prevSnapGapIdle; // P0
    private static long prevSnapGapLocal; // P0

    /**
     * The SNAP-gap histogram at the mark — every bucket cumulative(+delta
     * since the previous mark), the idle (≥ 1 s) and LOCAL classes apart, the window maxima
     * (taking them starts the next window, the {@code reader_gap_win_max_ms} idiom) and the wire
     * stalls ≥ the bar that ended within the last 60 s as {@code n[age_s:gap_ms,…]} newest first
     * (≤ 12 listed; {@code n} = the count among the ≤ 32 KEPT stalls — the bucket deltas carry
     * the full count past that). The 33–67 ms class (the one a hairpin test
     * sizes) = {@code ge33 − ge67}. Leading space; empty without a datagram session.
     */
    private static String snapGapMarkTokens(PredictReconcileDatagramSession d, long now) {
        if (d == null) {
            return "";
        }
        SnapGapMeter g = d.snapGapMeter();
        StringBuilder sb = new StringBuilder(256);
        for (int i = 0; i < SnapGapMeter.BUCKET_MS.length; i++) {
            long v = g.wireGe(i);
            sb.append(" snap_gap_ge").append(SnapGapMeter.BUCKET_MS[i]).append('=').append(v)
                    .append(String.format(Locale.ROOT, "(%+d)", v - prevSnapGapGe[i]));
            prevSnapGapGe[i] = v;
        }
        long idle = g.idleGe1000();
        long local = g.localGe33();
        sb.append(String.format(Locale.ROOT, " snap_gap_ge1000=%d(%+d) snap_gap_local_ge33=%d(%+d)"
                        + " snap_gap_max_ms=%.1f snap_gap_win_max_ms=%.1f snap_local_win_max_ms=%.1f"
                        + " snap_stall_bar_ms=%d snap_stalls_60s=%s",
                idle, idle - prevSnapGapIdle, local, local - prevSnapGapLocal,
                g.wireMaxMillis(), g.takeWireWindowMaxMillis(), g.takeLocalWindowMaxMillis(),
                g.stallBarMillis(), g.recentStallsToken(now, 60_000_000_000L, 12)));
        prevSnapGapIdle = idle;
        prevSnapGapLocal = local;
        return sb.toString();
    }

    /** The F9 wire row (plugin thread, right after the frame-ring dump). */
    static void markRow(PredictReconcileCombatPlugin plugin, int markNo, long simTick) {
        try {
            PredictReconcileDatagramSession d = udp(plugin);
            DatagramReliability.Stats s = d == null ? null : d.reliabilityStats();
            long now = System.nanoTime();
            long hi = s == null ? 0L : s.snapHiSeen();
            long dropEst = s == null ? 0L : s.snapDropEst();
            long epochDrops = plugin.chunkMerger.awsdDroppedEpoch();
            long nackTx = plugin.awsdNackTx;
            long busyGe50 = d == null ? 0L : d.readerBusyOver50Ms();
            long oldTick = d == null ? 0L : d.snapOldTickDropped();
            long superseded = d == null ? 0L : d.snapSupersededDropped();
            // The reader maxima over the WINDOW since the previous mark (taking
            // them starts the next window) beside the birth-to-now pair — the threshold
            // reads the window value; one cold-start outlier no longer pins the whole flight.
            double busyWin = d == null ? 0d : d.takeReaderBusyWindowMaxMillis();
            double gapWin = d == null ? 0d : d.takeReaderGapWindowMaxMillis();
            // The freshness levers with
            // their mark deltas beside the foe-step meter's window read (which enemy jumped, and
            // WHY: frame / skip / same). stale_folded is a datagram-REORDER recovery count
            // (seq <= hiSeen re-routed by content tick), NOT a pose fold: a later finding retracted the earlier
            // "folded tick = two-tick jump" reading.
            long staleFolded = d == null ? 0L : d.snapStaleFolded();
            long staleFoldQueued = d == null ? 0L : d.snapStaleFoldQueued();
            long mixedTickMax = plugin.chunkMerger.mixedTickMax();
            String since = prevWallNanos == Long.MIN_VALUE
                    ? "birth"
                    : String.format(Locale.ROOT, "%.1fs", (now - prevWallNanos) / 1e9d);
            PilotWatchLog.info(String.format(Locale.ROOT,
                    "predict wire-mark #%d tick=%d since=%s snap_hi_seen=%d(%+d)"
                            + " snap_drop_est=%d(%+d) awsd_dropped_epoch=%d(%+d)"
                            + " awsd_nack_tx=%d(%+d) arrival_ewma_ms=%.2f jitter_ewma_ms=%.2f"
                            // The reader's own stall (busy) + the loop period + the
                            // freshness conflation counts — stall-vs-path at the mark; the
                            // *_win_* pair = since the previous mark.
                            + " reader_busy_max_ms=%.1f reader_busy_win_max_ms=%.1f reader_busy_ge50=%d(%+d)"
                            + " reader_gap_max_ms=%.1f reader_gap_win_max_ms=%.1f old_tick_dropped=%d(%+d)"
                            + " snap_superseded_dropped=%d(%+d)"
                            // The freshness levers (+d) + the foe-step window.
                            + " stale_folded=%d(%+d) stale_fold_queued=%d(%+d) snap_mixed_tick_max=%d(%+d)"
                            + "%s"
                            // The SNAP-gap histogram + the 60 s stall list.
                            + "%s"
                            + " core=%s",
                    markNo, simTick == Long.MIN_VALUE ? -1L : simTick, since,
                    hi, hi - prevHiSeen, dropEst, dropEst - prevDropEst,
                    epochDrops, epochDrops - prevEpochDrops, nackTx, nackTx - prevNackTx,
                    plugin.foeInterp.arrivalEwmaMs(), plugin.foeInterp.jitterEwmaMs(),
                    d == null ? 0d : d.readerBusyMaxMillis(), busyWin, busyGe50, busyGe50 - prevBusyGe50,
                    d == null ? 0d : d.readerGapMaxMillis(), gapWin, oldTick, oldTick - prevOldTick,
                    superseded, superseded - prevSuperseded,
                    staleFolded, staleFolded - prevStaleFolded,
                    staleFoldQueued, staleFoldQueued - prevStaleFoldQueued,
                    mixedTickMax, mixedTickMax - prevMixedTickMax,
                    plugin.foeStep.takeMarkTokens(),
                    snapGapMarkTokens(d, now),
                    s == null ? "none" : "live"));
            prevHiSeen = hi;
            prevDropEst = dropEst;
            prevEpochDrops = epochDrops;
            prevNackTx = nackTx;
            prevBusyGe50 = busyGe50;
            prevOldTick = oldTick;
            prevSuperseded = superseded;
            prevStaleFolded = staleFolded;
            prevStaleFoldQueued = staleFoldQueued;
            prevMixedTickMax = mixedTickMax;
            prevWallNanos = now;
        } catch (Throwable t) {
            PilotWatchLog.info("predict wire-mark #" + markNo + " FAULT "
                    + t.getClass().getSimpleName());
        }
    }

    private static PredictReconcileDatagramSession udp(PredictReconcileCombatPlugin plugin) {
        Object s = plugin.session;
        return s instanceof PredictReconcileDatagramSession d ? d : null;
    }
}
