package cc.starsector.client;

import java.util.Map;
import java.util.WeakHashMap;

//
/**
 * The own-engine RE-DISABLE backstop, mirroring {@link WeaponRedisableBackstop}
 * keyed by hull identity + engine index. Pure, game-free.
 *
 * <p><b>Law.</b> An engine the lane wire-disabled is not re-{@code disable()}d within
 * {@link #HOLD_TICKS} of that apply while the wire bit has stayed set (the local health tracker's
 * native repair racing a held wire bit would otherwise re-fire the flame-out FX/sound per snapshot).
 * The first CLEAR edge on the wire ({@link #onWireClear}) forgets the entry, so a genuine new
 * disable after a repair applies on its first tick. Skips are counted
 * ({@code predict_own_engine_redisable_backstopped}) and confessed once — a non-zero count IS the
 * residual race (data, not concealment). {@link OwnEngineRegenStall} removes the regen author;
 * this is the bounded second line.
 */
final class EngineRedisableBackstop {

    /** 3 s at the 60 t/s server step — same idiom as the weapon backstop. */
    static final long HOLD_TICKS = 180L;

    /** Weak keys: the replica instance would otherwise pin every reaped ship
     *  graph until match reset. Identity semantics hold — the engine's Ship overrides neither
     *  equals nor hashCode. */
    private final Map<Object, long[]> lastApply = new WeakHashMap<Object, long[]>();
    private long backstopped;
    private boolean confessed;

    /** Ask before a wire-driven {@code disable()}: true = apply (tick recorded); false = skip. */
    boolean allowDisable(Object hull, int engineIndex, long simTick) {
        long[] ticks = slots(hull, engineIndex);
        long prev = ticks[engineIndex];
        if (prev != Long.MIN_VALUE && simTick - prev < HOLD_TICKS && simTick >= prev) {
            backstopped++;
            return false;
        }
        ticks[engineIndex] = simTick;
        return true;
    }

    /** The wire says ENABLED for this engine — the hold entry is forgotten. */
    void onWireClear(Object hull, int engineIndex) {
        long[] ticks = lastApply.get(hull);
        if (ticks != null && engineIndex >= 0 && engineIndex < ticks.length) {
            ticks[engineIndex] = Long.MIN_VALUE;
        }
    }

    void clear() {
        lastApply.clear();
    }

    long backstopped() {
        return backstopped;
    }

    /** One-shot confession (returns true once). */
    boolean confessOnce() {
        if (confessed) {
            return false;
        }
        confessed = true;
        return true;
    }

    private long[] slots(Object hull, int engineIndex) {
        long[] ticks = lastApply.get(hull);
        if (ticks == null || ticks.length <= engineIndex) {
            long[] grown = new long[Math.max(engineIndex + 1, ticks == null ? 8 : ticks.length * 2)];
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
        return " predict_own_engine_redisable_backstopped=" + backstopped;
    }
}
