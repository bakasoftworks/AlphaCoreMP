package cc.starsector.client;

/**
 * Dead-source SERVER-DEALT classification law.
 *
 * <p>WHY: the earlier rule classified EVERY hull apply whose report-seam source was a dead
 * ship as server-dealt (acked, no replay entry). That was wrong:
 * the engine passes the firing
 * SHIP as the report-seam source for its PROJECTILE damage too,
 * so a shot still in flight when its firer died pushed NO entry — the bar snapped UP to wire, then
 * dropped at RTT (the exact class the hull ledger exists to prevent); and {@code ownKilled} is also
 * latched by a voluntary module DISMOUNT ({@code OwnMountLifecycleLane.noteOwnVoluntaryDismount}),
 * so a LIVE module classified dead. The fix keys on the KILL TICK instead of the kill boolean: the
 * death explosion / the hulk's first ram land within a few ticks of the kill snapshot (one
 * recorded case: the 2238..2244 flood, 7 ticks) while posthumous projectile hits land later and DO
 * need a replay entry; {@link #KILL_WINDOW_TICKS} = 30 t = 0.5 s margin. The dismount latches
 * {@code ownKilled} but never {@code ownDeathTick}, so it is ORDINARY by construction.
 *
 * <p>The old {@code !s.isAlive()} fallback is REMOVED: an un-latched dead body (a fighter, a
 * hulk-piece clone) is ORDINARY — a replay entry the wire retires or expires, honest and metered
 * by the existing ledger meters (never concealed). Adds no latency (a classification, no wait).
 * Caller: {@code PredictReconcileCombatPlugin.hullSourceClass} (né {@code serverDealtSource}). Contract rows j/k:
 * {@code CcHullLedgerContract}.
 */
final class ServerDealtSourceLaw {

    /** 0.5 s at 60 t/s: the kill-snapshot death flood (7 t on 194216) with margin. */
    static final long KILL_WINDOW_TICKS = 30L;

    /** An ordinary local apply (a replay entry). */
    static final int SRC_ORDINARY = 0;
    /** A latched-dead source inside the kill window: server-dealt, acked (no replay entry). */
    static final int SRC_DEAD_ACKED = 1;
    /** A latched-dead source OUTSIDE the window: the posthumous class — ordinary, but counted. */
    static final int SRC_DEAD_LATE = 2;

    private ServerDealtSourceLaw() {
    }

    /**
     * True iff a kill tick is latched and {@code nowTick} sits inside [deathTick, deathTick +
     * {@link #KILL_WINDOW_TICKS}]. WHY = the class javadoc.
     */
    static boolean deadSourceAcked(long deathTick, long nowTick) {
        return deathTick != Long.MIN_VALUE && nowTick >= deathTick
                && nowTick - deathTick <= KILL_WINDOW_TICKS;
    }

    /**
     * Classify a ship source by the kill tick of whichever identity it matched ({@code isOwn} /
     * {@code isFoe} / else the remote's {@code remoteDeathTick}, {@code Long.MIN_VALUE} when the
     * source is no known remote). WHY = the class javadoc.
     */
    static int classify(boolean isOwn, boolean isFoe, long ownDeathTick, long foeDeathTick,
            long remoteDeathTick, long nowTick) {
        long deathTick = isOwn ? ownDeathTick : isFoe ? foeDeathTick : remoteDeathTick;
        if (deathTick == Long.MIN_VALUE) {
            return SRC_ORDINARY;
        }
        return deadSourceAcked(deathTick, nowTick) ? SRC_DEAD_ACKED : SRC_DEAD_LATE;
    }
}
