package cc.starsector.server.runtime;

import java.util.ArrayDeque;

/**
 * The adopt-serve ray hand-off: the serve loop's driver closure
 * ({@code EngineCombatBinding.mountFireDriverForShip}) posts the DECLARED ray here in the same
 * tick it force-fires the named mount; the engine's synchronous onFire dispatch
 * then hands {@code CcOnFireEmitter} the newborn projectile, which
 * consumes the matching entry (ship IDENTITY + mount ordinal — the {@link FireBirthSink} keying
 * idiom: the emitter's {@code weapon.getShip()} IS the serve loop's engine ship instance) and
 * re-poses the newborn onto exactly this ray BEFORE {@link FireBirthSink#record}, so GPEE
 * muzzle-truth carries the ADOPTED ray to victims for free.
 *
 * <p>Static + synchronized like {@link FireBirthSink}: posts and consumes both run on the serve
 * thread (the engine dispatch is single-threaded), the lock is for the round-line reader.
 * Bounded allocation (security standing rule): at most {@link #MAX_PENDING} entries — a post
 * beyond the cap drains the OLDEST as a capture miss (never unbounded growth, never a throw).
 *
 * <p>An entry the dispatch never consumed is a CAPTURE MISS — an engine-refused force-fire
 * (resource race; the head retries on its ε-clock) or an identity/mount mismatch. Stale entries
 * (posted before the current tick) are drained at each post; {@link #roundCaptureMisses} drains
 * everything at round end and returns-and-resets the counter for the round line
 * ({@code adopt_capture_miss=}).
 */
public final class AdoptServeRegistry {

    private static final int MAX_PENDING = 64;

    private static final class Entry {
        final Object ship;
        final int mountIndex;
        final float muzzleX;
        final float muzzleY;
        final float bearingDeg;
        final float speed;
        final long postTick;

        Entry(Object ship, int mountIndex, float muzzleX, float muzzleY,
                float bearingDeg, float speed, long postTick) {
            this.ship = ship;
            this.mountIndex = mountIndex;
            this.muzzleX = muzzleX;
            this.muzzleY = muzzleY;
            this.bearingDeg = bearingDeg;
            this.speed = speed;
            this.postTick = postTick;
        }
    }

    private static final ArrayDeque<Entry> pending = new ArrayDeque<>();
    private static long captureMisses;

    private AdoptServeRegistry() {
    }

    /** Post one adopted ray for the force-fired mount's newborn (drains stale entries first). */
    public static synchronized void post(Object ship, int mountIndex, float muzzleX,
            float muzzleY, float bearingDeg, float speed, long simTick) {
        drainStaleLocked(simTick);
        if (pending.size() >= MAX_PENDING) {
            pending.pollFirst();
            captureMisses++;
        }
        pending.addLast(new Entry(ship, mountIndex, muzzleX, muzzleY, bearingDeg, speed, simTick));
    }

    /**
     * Consume the posted ray for this ship (IDENTITY match) + mount, or null when none pends —
     * an organic birth on an un-adopted mount consumes nothing. Returns
     * {@code {muzzleX, muzzleY, bearingDeg, speed}}.
     */
    public static synchronized float[] consume(Object ship, int mountIndex) {
        if (ship == null || mountIndex < 0 || pending.isEmpty()) {
            return null;
        }
        java.util.Iterator<Entry> it = pending.iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (e.ship == ship && e.mountIndex == mountIndex) {
                it.remove();
                return new float[] {e.muzzleX, e.muzzleY, e.bearingDeg, e.speed};
            }
        }
        return null;
    }

    /** Round end: drain EVERYTHING as stale, then return-and-reset the miss counter. */
    public static synchronized long roundCaptureMisses() {
        captureMisses += pending.size();
        pending.clear();
        long misses = captureMisses;
        captureMisses = 0L;
        return misses;
    }

    private static void drainStaleLocked(long simTick) {
        while (!pending.isEmpty() && pending.peekFirst().postTick < simTick) {
            pending.pollFirst();
            captureMisses++;
        }
    }
}
