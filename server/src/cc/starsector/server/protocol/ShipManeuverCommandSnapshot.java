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
 *
 * <p><b>The pilot triple ({@code pilotSeat}, {@code pilotMask}, {@code pilotFlags}) rides AWSD only
 * (schema v14).</b> It is the hull's pilot mark as the serve's own mod read it this tick: seat
 * 1..255 (0 = no mark), the serve's applied ability bits (slot k = bit k) and the mark's flags
 * (bit 0 = the pilot's MagicLib decision is followed on this hull). The AWSS maneuver tier
 * still serialises the mask byte alone, so every AWSS-decoded record carries (0, 0, 0) and the
 * AWSS wire stays byte-identical — a domain field AWSS never serialises, like the CR record's
 * fighter percent. The non-canonical shapes (seat 0 with a non-zero mask or flags) are refused.
 *
 *
 * <p><b>{@code pilotControls} (schema v15, AWSD only)</b>
 * is the pilot's APPLIED held-controls u16 the serve's mark carries (bit i = the pilot holds their
 * own binding of the i-th ship control); it is meaningful only while flags bit 1 (CONTROLS) is
 * set. The whole mark obeys ONE canonical-form law, {@link #pilotMarkRefusalOrNull}, shared with
 * the AWSD baseline so the serve's capture and every decode refuse the same shapes.
 */
public record ShipManeuverCommandSnapshot(long entityId, int maneuverMask, int pilotSeat, int pilotMask,
        int pilotFlags, int pilotControls) {

    /**
     * Wire bit of the engine-flare flag — the free top bit of the maneuver
     * byte; equals {@link ShipManeuverCommandView#BIT_ENGINE_FLARE}.
     */
    public static final int MANEUVER_BIT_ENGINE_FLARE = ShipManeuverCommandView.BIT_ENGINE_FLARE;

    /** The 8 legal bits (7 maneuver commands + bit 7 flare); anything above is rejected. */
    public static final int MANEUVER_MASK_BITS = 0xFF;

    /**
     * The mark's flags bits schema v15 defines: FOLLOW (bit 0), CONTROLS (bit 1), REPLICA_FOLLOW
     * (bit 2). Bits 3..7 are refused, so a later meaning for them rides a new version instead of
     * being silently dropped by a reader that predates it.
     */
    public static final int PILOT_FLAGS_DEFINED = ShipManeuverCommandView.PILOT_FLAG_FOLLOW
            | ShipManeuverCommandView.PILOT_FLAG_CONTROLS | ShipManeuverCommandView.PILOT_FLAG_REPLICA_FOLLOW;

    /** The legal width of {@code pilotControls}: u16, bit i = the i-th ship control. */
    public static final int PILOT_CONTROLS_BITS = 0xFFFF;

    public ShipManeuverCommandSnapshot {
        if (entityId <= 0L) {
            throw new IllegalArgumentException("entityId must be > 0; got " + entityId);
        }
        if ((maneuverMask & ~MANEUVER_MASK_BITS) != 0) {
            throw new IllegalArgumentException(
                    "maneuverMask must fit the 8-bit maneuver byte (mask <= 0xff); got 0x"
                            + Integer.toHexString(maneuverMask));
        }
        String refusal = pilotMarkRefusalOrNull(pilotSeat, pilotMask, pilotFlags, pilotControls);
        if (refusal != null) {
            throw new IllegalArgumentException("pilot mark refused: " + refusal);
        }
    }

    /**
     * The pilot mark's canonical-form law, checked on
     * the RESOLVED values (the record a keyframe or an applied delta produces), never on which
     * delta fields happened to ride: a seat leaving its hull is a triple change whose controls are
     * held at the anchor's, so a presence-based rule would refuse a routine edge on every screen.
     * One bit pattern per meaning: seat 0 (no mark) carries mask, flags and controls 0; flags
     * bits 3..7 are undefined; REPLICA_FOLLOW (bit 2) needs CONTROLS (bit 1), because a remote
     * screen that follows the pilot's ship change must also hear the pilot's held controls; a
     * clear CONTROLS bit carries controls 0, so "nothing held" has one shape. Returns the refused
     * rule's name or null. The reason never quotes the controls value: it is a pilot's held input.
     */
    public static String pilotMarkRefusalOrNull(int pilotSeat, int pilotMask, int pilotFlags, int pilotControls) {
        if (pilotSeat < 0 || pilotSeat > 0xFF) {
            return "pilotSeat outside 0..255";
        }
        if ((pilotMask & ~ShipManeuverCommandView.PILOT_MASK_BITS) != 0) {
            return "pilotMask outside one byte";
        }
        if ((pilotFlags & ~0xFF) != 0) {
            return "pilotFlags outside one byte";
        }
        if ((pilotControls & ~PILOT_CONTROLS_BITS) != 0) {
            return "pilotControls outside u16";
        }
        if (pilotSeat == ShipManeuverCommandView.PILOT_SEAT_NONE
                && (pilotMask != 0 || pilotFlags != 0 || pilotControls != 0)) {
            return "pilotSeat 0 (no mark) must carry mask, flags and controls 0";
        }
        if ((pilotFlags & ~PILOT_FLAGS_DEFINED) != 0) {
            return "pilotFlags carries an undefined bit (3..7)";
        }
        boolean controlsFlag = (pilotFlags & ShipManeuverCommandView.PILOT_FLAG_CONTROLS) != 0;
        if ((pilotFlags & ShipManeuverCommandView.PILOT_FLAG_REPLICA_FOLLOW) != 0 && !controlsFlag) {
            return "pilotFlags REPLICA_FOLLOW set without CONTROLS";
        }
        if (!controlsFlag && pilotControls != 0) {
            return "pilotControls non-zero while flags CONTROLS is clear";
        }
        return null;
    }

    /**
     * An UNMARKED record (pilot triple (0, 0, 0)) — the
     * shape every AWSS decode and every pre-v14 fixture mints.
     */
    public ShipManeuverCommandSnapshot(long entityId, int maneuverMask) {
        this(entityId, maneuverMask, ShipManeuverCommandView.PILOT_SEAT_NONE, 0, 0, 0);
    }

    /**
     * A marked record without the flags byte (pilotFlags
     * 0: the pilot's MagicLib decision is not followed on this hull) — the pair-shaped callers'
     * form.
     */
    public ShipManeuverCommandSnapshot(long entityId, int maneuverMask, int pilotSeat, int pilotMask) {
        this(entityId, maneuverMask, pilotSeat, pilotMask, 0, 0);
    }

    /**
     * A marked record with the flags byte but no held
     * controls (pilotControls 0) — the triple-shaped callers' form.
     */
    public ShipManeuverCommandSnapshot(long entityId, int maneuverMask, int pilotSeat, int pilotMask,
            int pilotFlags) {
        this(entityId, maneuverMask, pilotSeat, pilotMask, pilotFlags, 0);
    }

    /**
     * Build the per-ship maneuver-command snapshot from the project-owned
     * {@link ShipManeuverCommandView} read model, keyed to the ship's already-resolved
     * network {@code entityId} (distinct from the view's within-tick repository
     * {@code shipId()}). The view's pilot triple and held controls ride along for the AWSD encoder.
     */
    public static ShipManeuverCommandSnapshot fromView(ShipManeuverCommandView view, long entityId) {
        Objects.requireNonNull(view, "view");
        return new ShipManeuverCommandSnapshot(entityId, view.maneuverMask(), view.pilotSeat(), view.pilotMask(),
                view.pilotFlags(), view.pilotControls());
    }

    /**
     * Reconstruct from decoded wire fields, fail-closed: a corrupt frame whose mask
     * carries any bit above the 8-bit maneuver byte (or whose entityId is non-positive)
     * is rejected by the canonical constructor rather than carried. AWSS carries no pilot
     * triple, so the record is minted unmarked (0, 0, 0).
     */
    public static ShipManeuverCommandSnapshot fromWire(long entityId, int maneuverMask) {
        return new ShipManeuverCommandSnapshot(entityId, maneuverMask);
    }

    /**
     * Reconstruct a record from a seat + mask (flags 0),
     * fail-closed through the canonical constructor (seat 0..255, mask one byte, (0, mask != 0)
     * refused).
     */
    public static ShipManeuverCommandSnapshot fromWire(long entityId, int maneuverMask, int pilotSeat, int pilotMask) {
        return new ShipManeuverCommandSnapshot(entityId, maneuverMask, pilotSeat, pilotMask);
    }

    /**
     * Reconstruct an AWSD v14 record with its pilot triple,
     * fail-closed through the canonical constructor (seat 0..255, mask and flags one byte each,
     * seat 0 with a non-zero mask or flags refused).
     */
    public static ShipManeuverCommandSnapshot fromWire(long entityId, int maneuverMask, int pilotSeat, int pilotMask,
            int pilotFlags) {
        return new ShipManeuverCommandSnapshot(entityId, maneuverMask, pilotSeat, pilotMask, pilotFlags);
    }

    /**
     * Reconstruct an AWSD v15 record with its pilot
     * triple and held controls, fail-closed through the canonical constructor
     * ({@link #pilotMarkRefusalOrNull}).
     */
    public static ShipManeuverCommandSnapshot fromWire(long entityId, int maneuverMask, int pilotSeat, int pilotMask,
            int pilotFlags, int pilotControls) {
        return new ShipManeuverCommandSnapshot(entityId, maneuverMask, pilotSeat, pilotMask, pilotFlags,
                pilotControls);
    }
}
