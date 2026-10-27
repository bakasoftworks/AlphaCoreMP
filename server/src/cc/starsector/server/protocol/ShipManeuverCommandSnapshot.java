package cc.starsector.server.protocol;

import cc.starsector.server.adapter.ShipManeuverCommandView;
import java.util.Objects;

/**
 * One ship's authoritative maneuver-COMMAND state as it is replicated to clients:
 * the maneuver-command tier (schema v7), read off the bridge's
 * {@link ShipManeuverCommandView} (public {@code getEngineController()} 7-boolean
 * surface).
 *
 * <p>Why this is carried at all: the engine's thrust-plume/glow/contrail
 * presentation (and the engine sound loop) is driven ONLY by the maneuver commands
 * drained from the ship's per-frame command queue — the 7 controller booleans are
 * cleared at the top of every {@code processCommands} and re-set only by that
 * frame's commands; pose/velocity writes cannot light a plume. A pose-snapped foe
 * replica therefore idles its engines forever (the observed symptom). Carrying
 * the mask lets the client re-issue {@code giveCommand} per set bit through the
 * engine's own faithful path, so the REAL local controller drives flare/glow/
 * contrail/audio for every COMMAND-driven flare.
 *
 * <p><b>Bit 7 = the engine's real flare flag:
 * </b> not every plume is command-driven — vanilla's drone follower AI never
 * issues a command; it pokes the engine glow directly ({@code forceShowAccelerating()}
 * + {@code showAccelerating(dt)}), so bits 0..6
 * stay clear on the launch-out leg and the replica never flares. The server therefore
 * also reads the glow's own "flaring now" getter
 * (integrated headless) into the previously FREE bit 7 of the
 * same byte — exact for every glow source (AI, systems, scripts), zero wire growth. The
 * client replays vanilla's pair while bit 7 is set and bit 0 is clear
 * ({@code forceShowAccelerating()} un-blocks, {@code giveCommand(ACCELERATE)} is the
 * API-legal {@code showAccelerating} path).
 *
 * <p>Bit {@code i} (0..6) is the canonical wire-bit order the UP-lane
 * {@code PilotIntentFrame.heldControls} already uses (bit 0 ACCELERATE …
 * bit 6 STRAFE_RIGHT); bit 7 is the flare flag above — NOT the UP-lane USE_SYSTEM bit
 * (UP bits 7/8 = USE_SYSTEM/VENT are never carried here). The byte is the whole legal
 * width: a corrupt frame with any bit above {@code 0xFF} is rejected fail-closed. Keyed by
 * {@code entityId} to the same stable network identity as the ship's
 * {@link AuthoritativeEntitySnapshot}; like the other snapshot tiers it carries
 * only scalar, version-independent state and never an engine-internal class.
 */
public record ShipManeuverCommandSnapshot(long entityId, int maneuverMask) {

    /**
     * Wire bit of the engine-flare flag — the free top bit of the maneuver
     * byte; equals {@link ShipManeuverCommandView#BIT_ENGINE_FLARE}.
     */
    public static final int MANEUVER_BIT_ENGINE_FLARE = ShipManeuverCommandView.BIT_ENGINE_FLARE;

    /** The 8 legal bits (7 maneuver commands + bit 7 flare); anything above is rejected. */
    public static final int MANEUVER_MASK_BITS = 0xFF;

    public ShipManeuverCommandSnapshot {
        if (entityId <= 0L) {
            throw new IllegalArgumentException("entityId must be > 0; got " + entityId);
        }
        if ((maneuverMask & ~MANEUVER_MASK_BITS) != 0) {
            throw new IllegalArgumentException(
                    "maneuverMask must fit the 8-bit maneuver byte (mask <= 0xff); got 0x"
                            + Integer.toHexString(maneuverMask));
        }
    }

    /**
     * Build the per-ship maneuver-command snapshot from the project-owned
     * {@link ShipManeuverCommandView} read model, keyed to the ship's already-resolved
     * network {@code entityId} (distinct from the view's within-tick repository
     * {@code shipId()}).
     */
    public static ShipManeuverCommandSnapshot fromView(ShipManeuverCommandView view, long entityId) {
        Objects.requireNonNull(view, "view");
        return new ShipManeuverCommandSnapshot(entityId, view.maneuverMask());
    }

    /**
     * Reconstruct from decoded wire fields, fail-closed: a corrupt frame whose mask
     * carries any bit above the 8-bit maneuver byte (or whose entityId is non-positive)
     * is rejected by the canonical constructor rather than carried.
     */
    public static ShipManeuverCommandSnapshot fromWire(long entityId, int maneuverMask) {
        return new ShipManeuverCommandSnapshot(entityId, maneuverMask);
    }
}
