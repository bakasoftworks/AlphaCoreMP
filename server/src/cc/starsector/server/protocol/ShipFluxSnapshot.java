package cc.starsector.server.protocol;

import cc.starsector.server.adapter.ShipFluxView;
import java.util.Objects;

/**
 * One ship's authoritative flux + shield state as it is replicated to clients: the
 * combat-effectiveness tier read through {@link ShipFluxView}.
 *
 * <p>This is the wire-carried counterpart of {@link ShipFluxView}, the parallel
 * critical-state tier the snapshot schema draft lists alongside the
 * already-replicated hull/armor {@link AuthoritativeEntitySnapshot} (durability) and
 * the transient {@link NonShipEntitySnapshot} (projectiles/beams) tiers. Flux is
 * central to Starsector combat — a ship near max flux cannot fire and drops its
 * shield, an overload disables it entirely — so hull/armor alone cannot tell a
 * client whether a ship is combat-effective; flux + shield state can. The
 * read was observed driving on the real engine; this record is
 * what folds those scalars onto the AWSS wire.
 *
 * <p>It is keyed by {@code entityId} to the same stable network identity as the
 * ship's {@link AuthoritativeEntitySnapshot}, so a decoder pairs flux to its ship.
 * Like the other snapshot tiers it carries only scalar, version-independent state
 * and never an internal engine class (the flux tracker / shield system stay inside
 * the bridge). The status booleans are packed onto the wire as a single
 * {@link #statusFlags()} int ({@code phased} since schema v10 — the
 * per-frame collision truth beside the v10 cloak state-machine tier).
 */
public record ShipFluxSnapshot(
        long entityId,
        float currFlux,
        float maxFlux,
        float fluxLevel,
        float hardFlux,
        boolean overloaded,
        boolean venting,
        boolean hasShield,
        boolean shieldOn,
        boolean phased,
        int shieldFacingU8,
        boolean god,
        boolean driveLock) {

    /** Wire bit for {@link #overloaded()} in {@link #statusFlags()}. */
    public static final int FLAG_OVERLOADED = 1;
    /** Wire bit for {@link #venting()} in {@link #statusFlags()}. */
    public static final int FLAG_VENTING = 1 << 1;
    /** Wire bit for {@link #hasShield()} in {@link #statusFlags()}. */
    public static final int FLAG_HAS_SHIELD = 1 << 2;
    /** Wire bit for {@link #shieldOn()} in {@link #statusFlags()}. */
    public static final int FLAG_SHIELD_ON = 1 << 3;
    /**
     * Wire bit for {@link #phased()} in {@link #statusFlags()}: the
     * engine's per-frame collision-class truth {@code ShipAPI.isPhased()} — true through
     * IN/ACTIVE and through the OUT window while {@code effectLevel > 0.5}, exactly while
     * the ship is untargetable/collisionless. Sourced at the live fold by cross-ref of
     * the same tick's phase-cloak views (the {@link #FLAG_SHIELD_ON} pattern: steady
     * per-frame truth on the flux tier; the cloak STATE MACHINE rides the v10
     * {@link ShipPhaseCloakStateSnapshot} tier for mid-join seeding). Zero wire bytes:
     * pre-v10 frames simply never set the bit.
     */
    public static final int FLAG_PHASED = 1 << 4;
    /**
     * Wire bit for the god-mode replica mirror: server-authored,
     * set iff {@code AdminSeatDebugLane}'s APPLIED-hull map holds this entity's wire id on the
     * publishing frame (never the latch); DOWN-only on the AWSS {@code statusFlags} int and the
     * AWSD {@code fluxFlags} u8; the client mirrors it as presentation only (its own damage-taken
     * mults on the replica). Zero wire bytes; old kits mask-ignore the bit. The client twin =
     * {@code ServerEntityPose.FLAG_GOD} (parity row on the client selftest classpath).
     */
    public static final int FLAG_GOD = 1 << 5;
    /**
     * Wire bit for the travel-drive fire lock: server-authored, set for a
     * seat-piloted hull iff vanilla's own travel-drive click clause holds on the publishing frame
     * ({@code getTravelDrive() != null && shouldBlockCommands() && !getSpec().isFiringAllowed()})
     * — the one {@code noFiring} lock the server starts
     * and the client's engine does not run in the server's window (the ship system and the phase
     * cloak are activated locally, so they are NOT echoed). DOWN-only on the AWSS
     * {@code statusFlags} int and the AWSD {@code fluxFlags} u8; zero wire bytes; old kits ignore
     * the bit. The client twin = {@code ServerEntityPose.FLAG_DRIVE_LOCK} (parity row on the client
     * selftest classpath); the client refuses its own trigger while a fresh own pose carries it.
     */
    public static final int FLAG_DRIVE_LOCK = 1 << 6;
    private static final int ALL_FLAGS = FLAG_OVERLOADED | FLAG_VENTING | FLAG_HAS_SHIELD
            | FLAG_SHIELD_ON | FLAG_PHASED | FLAG_GOD | FLAG_DRIVE_LOCK;

    /**
     * Shield-facing wire grid: 360°/256 = 1.40625° per u8 step. The
     * quantization happens ONCE, here at the mint ({@link #fromView}); both wire
     * families (AWSD flux section, AWSS flux record) carry {@link #shieldFacingU8}
     * verbatim — no float round-trip, no cross-codec divergence. Shieldless ships mint
     * 0; consumers gate on {@link #FLAG_HAS_SHIELD}, never on the facing value.
     */
    public static final float SHIELD_FACING_STEP_DEG = 360f / 256f;

    public ShipFluxSnapshot {
        if (entityId <= 0L) {
            throw new IllegalArgumentException("entityId must be > 0; got " + entityId);
        }
        requireFinite("currFlux", currFlux);
        requireFinite("maxFlux", maxFlux);
        requireFinite("fluxLevel", fluxLevel);
        requireFinite("hardFlux", hardFlux);
        if (shieldFacingU8 < 0 || shieldFacingU8 > 0xFF) {
            throw new IllegalArgumentException(
                    "shieldFacingU8 outside wire u8 [0,255]: " + shieldFacingU8);
        }
    }

    /**
     * Back-compat constructor for a flux snapshot with no phased bit ({@code phased=false}
     * — every ship without a cloak, and every call site older than the phased bit). The faithful
     * degrade: a caller that has no cloak cross-ref asserts "not phased", which is exactly
     * what the engine reports for a cloakless hull.
     */
    public ShipFluxSnapshot(
            long entityId,
            float currFlux,
            float maxFlux,
            float fluxLevel,
            float hardFlux,
            boolean overloaded,
            boolean venting,
            boolean hasShield,
            boolean shieldOn) {
        this(entityId, currFlux, maxFlux, fluxLevel, hardFlux, overloaded, venting,
                hasShield, shieldOn, false);
    }

    /**
     * Back-compat constructor for a flux snapshot with no shield-facing byte
     * ({@code shieldFacingU8=0} — every call site older than the facing byte and every wire
     * frame below the facing-carrying schema tiers; consumers gate on
     * {@link #FLAG_HAS_SHIELD} so the pinned 0 is never read as a real facing).
     */
    public ShipFluxSnapshot(
            long entityId,
            float currFlux,
            float maxFlux,
            float fluxLevel,
            float hardFlux,
            boolean overloaded,
            boolean venting,
            boolean hasShield,
            boolean shieldOn,
            boolean phased) {
        this(entityId, currFlux, maxFlux, fluxLevel, hardFlux, overloaded, venting,
                hasShield, shieldOn, phased, 0);
    }

    /**
     * Back-compat constructor = the pre-{@link #FLAG_GOD}
     * canonical signature ({@code god=false}): every flux record not minted by the live fold's
     * god-mode predicate, and every caller that predates the {@code god} component.
     */
    public ShipFluxSnapshot(
            long entityId,
            float currFlux,
            float maxFlux,
            float fluxLevel,
            float hardFlux,
            boolean overloaded,
            boolean venting,
            boolean hasShield,
            boolean shieldOn,
            boolean phased,
            int shieldFacingU8) {
        this(entityId, currFlux, maxFlux, fluxLevel, hardFlux, overloaded, venting,
                hasShield, shieldOn, phased, shieldFacingU8, false);
    }

    /**
     * Back-compat constructor = the pre-{@link #FLAG_DRIVE_LOCK} canonical signature
     * ({@code driveLock=false}): every flux record not minted by the live fold's
     * drive-lock predicate, and every caller that predates the {@code driveLock} component.
     */
    public ShipFluxSnapshot(
            long entityId,
            float currFlux,
            float maxFlux,
            float fluxLevel,
            float hardFlux,
            boolean overloaded,
            boolean venting,
            boolean hasShield,
            boolean shieldOn,
            boolean phased,
            int shieldFacingU8,
            boolean god) {
        this(entityId, currFlux, maxFlux, fluxLevel, hardFlux, overloaded, venting,
                hasShield, shieldOn, phased, shieldFacingU8, god, false);
    }

    /** Stable wire packing of the status booleans into one int. */
    public int statusFlags() {
        int flags = 0;
        if (overloaded) {
            flags |= FLAG_OVERLOADED;
        }
        if (venting) {
            flags |= FLAG_VENTING;
        }
        if (hasShield) {
            flags |= FLAG_HAS_SHIELD;
        }
        if (shieldOn) {
            flags |= FLAG_SHIELD_ON;
        }
        if (phased) {
            flags |= FLAG_PHASED;
        }
        if (god) {
            flags |= FLAG_GOD;
        }
        return driveLock ? flags | FLAG_DRIVE_LOCK : flags;
    }

    /**
     * Build the per-ship flux snapshot from the project-owned {@link ShipFluxView}
     * read model, keyed to the ship's already-resolved network {@code entityId}
     * (distinct from the view's within-tick repository {@code shipId()}).
     * {@code phased} is NOT part of the flux view — the live fold cross-refs the same
     * tick's {@link cc.starsector.server.adapter.ShipPhaseCloakView} read and passes
     * the engine's {@code isPhased()} truth here (false for every
     * cloakless ship). {@code god} = the fold's applied-hull god-mode predicate ({@link #FLAG_GOD}).
     */
    public static ShipFluxSnapshot fromView(
            ShipFluxView view, long entityId, boolean phased, boolean god) {
        return fromView(view, entityId, phased, god, false);
    }

    /**
     * {@link #fromView(ShipFluxView, long, boolean, boolean)} plus {@code driveLock} = the fold's
     * seat-hull travel-drive lock predicate ({@link #FLAG_DRIVE_LOCK}).
     */
    public static ShipFluxSnapshot fromView(
            ShipFluxView view, long entityId, boolean phased, boolean god, boolean driveLock) {
        Objects.requireNonNull(view, "view");
        return new ShipFluxSnapshot(
                entityId,
                view.currFlux(),
                view.maxFlux(),
                view.fluxLevel(),
                view.hardFlux(),
                view.overloaded(),
                view.venting(),
                view.hasShield(),
                view.shieldOn(),
                phased,
                view.hasShield()
                        ? Math.floorMod(
                                Math.round(view.shieldFacing() / SHIELD_FACING_STEP_DEG), 256)
                        : 0,
                god,
                driveLock);
    }

    /**
     * Reconstruct a flux snapshot from its wire fields, decoding the six status
     * booleans from a {@link #statusFlags()} int. Unknown high bits are rejected so a
     * corrupt flag byte fails closed rather than silently dropping state (which is why
     * {@code ALL_FLAGS} MUST widen with every new bit — a v10 frame's
     * {@link #FLAG_PHASED} would otherwise be rejected here).
     */
    public static ShipFluxSnapshot fromWire(
            long entityId,
            float currFlux,
            float maxFlux,
            float fluxLevel,
            float hardFlux,
            int statusFlags) {
        return fromWire(entityId, currFlux, maxFlux, fluxLevel, hardFlux, statusFlags, 0);
    }

    /**
     * Widened wire reconstruction carrying the facing byte verbatim (
     * quantized ONCE at {@link #fromView}; a facing-carrying wire frame hands the u8
     * through here untouched). The 6-arg overload pins 0 for pre-facing schema tiers.
     */
    public static ShipFluxSnapshot fromWire(
            long entityId,
            float currFlux,
            float maxFlux,
            float fluxLevel,
            float hardFlux,
            int statusFlags,
            int shieldFacingU8) {
        if ((statusFlags & ~ALL_FLAGS) != 0) {
            throw new IllegalArgumentException("unknown ship flux status flag bits: " + statusFlags);
        }
        return new ShipFluxSnapshot(
                entityId,
                currFlux,
                maxFlux,
                fluxLevel,
                hardFlux,
                (statusFlags & FLAG_OVERLOADED) != 0,
                (statusFlags & FLAG_VENTING) != 0,
                (statusFlags & FLAG_HAS_SHIELD) != 0,
                (statusFlags & FLAG_SHIELD_ON) != 0,
                (statusFlags & FLAG_PHASED) != 0,
                shieldFacingU8,
                (statusFlags & FLAG_GOD) != 0,
                (statusFlags & FLAG_DRIVE_LOCK) != 0);
    }

    private static void requireFinite(String label, float value) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be finite; got " + value);
        }
    }
}
