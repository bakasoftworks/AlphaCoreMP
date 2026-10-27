package cc.starsector.server.protocol;

import cc.starsector.server.adapter.ShipProfileView;
import java.util.Objects;

/**
 * One ship's authoritative static physical-profile state as it is replicated to
 * clients: the size/geometry/mass tier read off its engine hook ({@link ShipProfileView}).
 *
 * <p>This is the wire-carried counterpart of {@link ShipProfileView}, a per-ship tier
 * that sits alongside the ship-level {@link ShipFluxSnapshot} flux/shield record and the
 * per-mount {@link ShipWeaponMountSnapshot} weapon-readiness record. Where the motion
 * tier ({@link AuthoritativeEntitySnapshot}) says <em>where</em> a ship is and the flux
 * tier says how combat-effective it is, the physical-profile tier says <em>how big</em>
 * it is and <em>how it occupies space</em>: its hull-size class, collision radius, and
 * mass. A client needs this to weight relevance/priority (a capital ship matters at a
 * longer range than a frigate) and to reason about collision footprint — none of which
 * the motion, durability, flux, or weapon-mount tiers express. The read was
 * observed driving on the real engine (engine-driven hull-size /
 * collision-radius / mass mutations tracked through the product binding); this record is
 * what folds those scalars onto the AWSS wire.
 *
 * <p>It is keyed by {@code entityId} to the same stable network identity as the ship's
 * {@link AuthoritativeEntitySnapshot}, so a decoder pairs the profile to its ship. Like
 * the other snapshot tiers it carries only scalar/version-independent state and never an
 * engine-internal class (the engine {@code ShipAPI$HullSize} enum stays inside the
 * bridge): the hull size is carried as a project-owned fixed {@link #hullSizeWireCode()}
 * over the documented {@code ShipProfileView} size-class names, so a compact int — not a
 * version-specific enum or string — travels on each record and a decoder can recover the
 * size class without any external content table.
 */
public record ShipProfileSnapshot(
        long entityId,
        String hullSize,
        float collisionRadius,
        float mass) {

    /**
     * Project-owned canonical hull-size class names, in a fixed wire order. The wire
     * code of a hull size is its index here. This is the documented domain of
     * {@link ShipProfileView#hullSize()} (the {@link Enum#name()} of the engine's
     * {@code ShipAPI$HullSize}); the order is frozen so a decoded code always maps back
     * to the same class name across builds.
     */
    private static final String[] HULL_SIZE_NAMES = {
        "DEFAULT", "FIGHTER", "FRIGATE", "DESTROYER", "CRUISER", "CAPITAL_SHIP"
    };

    public ShipProfileSnapshot {
        if (entityId <= 0L) {
            throw new IllegalArgumentException("entityId must be > 0; got " + entityId);
        }
        Objects.requireNonNull(hullSize, "hullSize");
        if (hullSizeIndex(hullSize) < 0) {
            throw new IllegalArgumentException("unrecognized hull size class: " + hullSize);
        }
        requireFiniteNonNegative("collisionRadius", collisionRadius);
        requireFiniteNonNegative("mass", mass);
    }

    /** Stable project-owned wire encoding of {@link #hullSize()} (index into the frozen name table). */
    public int hullSizeWireCode() {
        return hullSizeIndex(hullSize);
    }

    /** Decode a hull-size class name from its {@link #hullSizeWireCode()}. */
    public static String hullSizeNameFromWireCode(int wireCode) {
        if (wireCode < 0 || wireCode >= HULL_SIZE_NAMES.length) {
            throw new IllegalArgumentException("ship profile hull size wire code out of range: " + wireCode);
        }
        return HULL_SIZE_NAMES[wireCode];
    }

    /**
     * Build the per-ship profile snapshot from the project-owned {@link ShipProfileView}
     * read model, keyed to the ship's already-resolved network {@code entityId} (distinct
     * from the view's within-tick repository {@code shipId()}).
     */
    public static ShipProfileSnapshot fromView(ShipProfileView view, long entityId) {
        Objects.requireNonNull(view, "view");
        return new ShipProfileSnapshot(
                entityId,
                view.hullSize(),
                view.collisionRadius(),
                view.mass());
    }

    /**
     * Reconstruct a profile snapshot from its wire fields, decoding the hull size from a
     * {@link #hullSizeWireCode()}. An out-of-range code is rejected so a corrupt frame
     * fails closed rather than silently dropping the size class.
     */
    public static ShipProfileSnapshot fromWire(
            long entityId,
            int hullSizeWireCode,
            float collisionRadius,
            float mass) {
        return new ShipProfileSnapshot(
                entityId,
                hullSizeNameFromWireCode(hullSizeWireCode),
                collisionRadius,
                mass);
    }

    private static int hullSizeIndex(String name) {
        for (int i = 0; i < HULL_SIZE_NAMES.length; i++) {
            if (HULL_SIZE_NAMES[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private static void requireFiniteNonNegative(String label, float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be finite; got " + value);
        }
        if (value < 0.0f) {
            throw new IllegalArgumentException(label + " must be >= 0; got " + value);
        }
    }
}
