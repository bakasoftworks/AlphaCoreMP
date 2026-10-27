package cc.starsector.server.bridge;

import cc.starsector.server.adapter.CombatStateHashBoundary;
import cc.starsector.server.adapter.CombatWorldState;
import cc.starsector.server.adapter.ShipDurabilityView;
import cc.starsector.server.adapter.ShipMotionView;
import java.util.List;

/**
 * {@link CombatStateHashBoundary} that hashes live post-step combat state read
 * through {@link EngineCombatBinding}.
 *
 * <p>The hash mixes the authoritative world counts (entities, ships, fleet
 * managers), the bound time multiplier, and — when handles were supplied — every
 * installed ship's post-step transform/velocity/facing and durability state in
 * repository order. Motion and durability reads are optional and never throw: a
 * missing handle or read fault degrades to the older hash, recorded via
 * {@link #lastHashUsedMotion()}, {@link #lastHashUsedDurability()},
 * {@link #lastHashUsedLocation()}, {@link #lastHashMotionShipCount()}, and
 * {@link #lastHashDurabilityShipCount()}.
 */
public final class EngineBackedCombatStateHashBoundary implements CombatStateHashBoundary {

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private final EngineCombatBinding binding;
    private volatile boolean lastHashUsedLocation;
    private volatile boolean lastHashUsedMotion;
    private volatile boolean lastHashUsedDurability;
    private volatile int lastHashMotionShipCount;
    private volatile int lastHashDurabilityShipCount;
    private volatile long lastMotionSubHash;
    private volatile long lastDurabilitySubHash;

    public EngineBackedCombatStateHashBoundary(EngineCombatBinding binding) {
        this.binding = binding;
    }

    @Override
    public long stateHash(CombatWorldState worldState) {
        long h = FNV_OFFSET;
        h = mix(h, worldState.entityCount());
        h = mix(h, worldState.roster().shipCount());
        h = mix(h, worldState.fleetManagerCount());
        h = mix(h, Float.floatToIntBits(worldState.timeMult()));

        List<ShipMotionView> motions = binding.shipMotionsOrNull();
        if (motions != null && motions.size() == worldState.roster().shipCount()) {
            // Compute a motion-only sub-hash in parallel with the combined
            // hash so a cross-run compare can attribute any divergence to the motion
            // component (loc/vel/facing/angular velocity) independently of durability.
            long m = FNV_OFFSET;
            m = mix(m, motions.size());
            h = mix(h, motions.size());
            for (ShipMotionView motion : motions) {
                h = mix(h, Long.hashCode(motion.shipId()));
                h = mix(h, Float.floatToIntBits(motion.locX()));
                h = mix(h, Float.floatToIntBits(motion.locY()));
                h = mix(h, Float.floatToIntBits(motion.velX()));
                h = mix(h, Float.floatToIntBits(motion.velY()));
                h = mix(h, Float.floatToIntBits(motion.facing()));
                h = mix(h, Float.floatToIntBits(motion.angularVelocity()));
                h = mix(h, Float.floatToIntBits(motion.stepAmount()));
                h = mix(h, Float.floatToIntBits(motion.timeMult()));
                m = mix(m, Long.hashCode(motion.shipId()));
                m = mix(m, Float.floatToIntBits(motion.locX()));
                m = mix(m, Float.floatToIntBits(motion.locY()));
                m = mix(m, Float.floatToIntBits(motion.velX()));
                m = mix(m, Float.floatToIntBits(motion.velY()));
                m = mix(m, Float.floatToIntBits(motion.facing()));
                m = mix(m, Float.floatToIntBits(motion.angularVelocity()));
                m = mix(m, Float.floatToIntBits(motion.stepAmount()));
                m = mix(m, Float.floatToIntBits(motion.timeMult()));
            }
            lastMotionSubHash = m;
            lastHashUsedMotion = true;
            lastHashUsedLocation = true;
            lastHashMotionShipCount = motions.size();
        } else {
            lastMotionSubHash = 0L;
            lastHashUsedMotion = false;
            lastHashMotionShipCount = 0;
            float[] loc = binding.playerShipLocationOrNull();
            if (loc != null && loc.length == 2) {
                h = mix(h, Float.floatToIntBits(loc[0]));
                h = mix(h, Float.floatToIntBits(loc[1]));
                lastHashUsedLocation = true;
            } else {
                lastHashUsedLocation = false;
            }
        }
        List<ShipDurabilityView> durabilities = binding.shipDurabilitiesOrNull();
        if (durabilities != null && durabilities.size() == worldState.roster().shipCount()) {
            // durability-only sub-hash, parallel to the combined hash.
            long d = FNV_OFFSET;
            d = mix(d, durabilities.size());
            h = mix(h, durabilities.size());
            for (ShipDurabilityView durability : durabilities) {
                h = mix(h, Long.hashCode(durability.shipId()));
                h = mix(h, Float.floatToIntBits(durability.hitpoints()));
                h = mix(h, Float.floatToIntBits(durability.hullLevel()));
                h = mix(h, durability.armorCellCount());
                h = mix(h, Float.floatToIntBits(durability.armorTotal()));
                h = mix(h, Long.hashCode(durability.armorGridHash()));
                d = mix(d, Long.hashCode(durability.shipId()));
                d = mix(d, Float.floatToIntBits(durability.hitpoints()));
                d = mix(d, Float.floatToIntBits(durability.hullLevel()));
                d = mix(d, durability.armorCellCount());
                d = mix(d, Float.floatToIntBits(durability.armorTotal()));
                d = mix(d, Long.hashCode(durability.armorGridHash()));
            }
            lastDurabilitySubHash = d;
            lastHashUsedDurability = true;
            lastHashDurabilityShipCount = durabilities.size();
        } else {
            lastDurabilitySubHash = 0L;
            lastHashUsedDurability = false;
            lastHashDurabilityShipCount = 0;
        }
        return h;
    }

    /** Motion-only FNV sub-hash from the most recent {@link #stateHash} (0 if motion absent). */
    public long lastMotionSubHash() {
        return lastMotionSubHash;
    }

    /** Durability-only FNV sub-hash from the most recent {@link #stateHash} (0 if durability absent). */
    public long lastDurabilitySubHash() {
        return lastDurabilitySubHash;
    }

    /** True when the most recent hash incorporated a real player-ship location. */
    public boolean lastHashUsedLocation() {
        return lastHashUsedLocation;
    }

    /** True when the most recent hash incorporated the engine-backed motion view. */
    public boolean lastHashUsedMotion() {
        return lastHashUsedMotion;
    }

    /** Number of ship motion snapshots incorporated by the most recent hash. */
    public int lastHashMotionShipCount() {
        return lastHashMotionShipCount;
    }

    /** True when the most recent hash incorporated engine-backed durability. */
    public boolean lastHashUsedDurability() {
        return lastHashUsedDurability;
    }

    /** Number of ship durability snapshots incorporated by the most recent hash. */
    public int lastHashDurabilityShipCount() {
        return lastHashDurabilityShipCount;
    }

    private static long mix(long h, int value) {
        h ^= (value & 0xffffffffL);
        h *= FNV_PRIME;
        return h;
    }
}
