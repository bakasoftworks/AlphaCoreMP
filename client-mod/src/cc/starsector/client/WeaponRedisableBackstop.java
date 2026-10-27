package cc.starsector.client;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The weapon RE-DISABLE backstop of the v4 weapon-state
 * apply lane ({@code FoeStateLaneCoordinator.maintainWeaponState}). Pure, game-free, keyed by hull
 * identity + mount index (selftest rows).
 *
 * <p><b>The race it bounds.</b> The wire's per-mount disabled bit is a stateless boolean with no
 * duration. The lane applies it edge-triggered through the REAL engine seam
 * ({@code WeaponAPI.disable()} — which fires vanilla's "&lt;weapon&gt; Offline" float text +
 * sound). If the LOCAL engine then auto-repairs the mount (native regen on the own hull, any
 * residual on a replica) while the wire bit is still set, the next fresh snapshot disagrees again
 * and the lane re-{@code disable()}s — one Offline/Online cue per edge, every edge, the owner's
 * popup storm. {@link OwnWeaponRegenStall} removes the own-hull regen author; this backstop is
 * the bounded second line so a residual race can never spam the cue.
 *
 * <p><b>Law.</b> A mount the lane wire-disabled is not re-disabled within
 * {@link #HOLD_TICKS} of that apply while the wire bit has stayed set. The first CLEAR edge on
 * the wire ({@link #onWireClear}) forgets the entry, so a genuine new disable after a repair is
 * applied on its first tick. Skips are counted (the {@code predict_weapon_state_redisable_backstopped}
 * meter) and confessed once — never silent: a non-zero count on a flight IS the residual race,
 * data for the next leg, not a concealment.
 */
final class WeaponRedisableBackstop {

    /** 3 s at the 60 t/s server step — shorter than any vanilla EMP disable, longer than the
     *  1–2 tick repair-then-redisable churn the meters measured. */
    static final long HOLD_TICKS = 180L;

    /** Hull identity → per-mount tick of OUR last applied disable ({@code Long.MIN_VALUE} = none). */
    private final Map<Object, long[]> lastApply = new IdentityHashMap<Object, long[]>();

    private long backstopped;
    private boolean confessed;

    /**
     * Ask before a wire-driven {@code disable()}: true = apply (and the apply tick is recorded);
     * false = within the hold window of our own previous apply on this mount — skip.
     */
    boolean allowDisable(Object hull, int mountIndex, long simTick) {
        long[] ticks = slots(hull, mountIndex);
        long prev = ticks[mountIndex];
        if (prev != Long.MIN_VALUE && simTick - prev < HOLD_TICKS && simTick >= prev) {
            backstopped++;
            return false;
        }
        ticks[mountIndex] = simTick;
        return true;
    }

    /** The wire says ENABLED for this mount — the hold entry is forgotten (a new disable is fresh). */
    void onWireClear(Object hull, int mountIndex) {
        long[] ticks = lastApply.get(hull);
        if (ticks != null && mountIndex >= 0 && mountIndex < ticks.length) {
            ticks[mountIndex] = Long.MIN_VALUE;
        }
    }

    /** Round reset / hull gone: drop every entry. */
    void clear() {
        lastApply.clear();
    }

    long backstopped() {
        return backstopped;
    }

    /** One-shot confession the caller logs when the first skip happens (returns true once). */
    boolean confessOnce() {
        if (confessed) {
            return false;
        }
        confessed = true;
        return true;
    }

    private long[] slots(Object hull, int mountIndex) {
        long[] ticks = lastApply.get(hull);
        if (ticks == null || ticks.length <= mountIndex) {
            long[] grown = new long[Math.max(mountIndex + 1, ticks == null ? 8 : ticks.length * 2)];
            java.util.Arrays.fill(grown, Long.MIN_VALUE);
            if (ticks != null) {
                System.arraycopy(ticks, 0, grown, 0, ticks.length);
            }
            ticks = grown;
            lastApply.put(hull, ticks);
        }
        return ticks;
    }

    String meterTokens() {
        return " predict_weapon_state_redisable_backstopped=" + backstopped;
    }
}
