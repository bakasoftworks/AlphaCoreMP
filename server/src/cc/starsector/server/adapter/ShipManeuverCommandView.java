package cc.starsector.server.adapter;

/**
 * Project-owned read model for one ship's maneuver COMMAND state at a simulation
 * step: which of the 7 maneuver commands the ship's engine controller is executing
 * this frame. This is the command-state carry — the engine's thrust-plume/glow/contrail
 * presentation (and the engine sound loop that rides the same glow level) is driven
 * ONLY by the per-frame command queue, never by pose or velocity, so a pose-snapped
 * foe replica idles its engines forever unless the server's maneuver truth crosses
 * the wire and the client re-issues the commands through the real local
 * {@code giveCommand} path.
 *
 * <p>All reads are public {@code ShipAPI.getEngineController()} /
 * {@code ShipEngineControllerAPI} boolean surface ({@code isAccelerating},
 * {@code isAcceleratingBackwards}, {@code isDecelerating}, {@code isTurningLeft},
 * {@code isTurningRight}, {@code isStrafingLeft}, {@code isStrafingRight}), kept
 * behind the bridge — mirror-symmetric with the client mod's capture of its own
 * pilot mask, and UNIFORM across seat-piloted and AI ships (a D4 gauntlet AI foe's
 * controller reads the same way a human seat's does; a seat ship's TURN bits read
 * false because the server replay drops turn — harmless, TURN never flares and
 * facing is pose-carried). Distinct from {@link ShipManeuverView}, the static
 * maneuverability stat-profile read. The within-tick {@code shipId} matches the
 * other per-ship views read in the same tick (repository order).
 */
public interface ShipManeuverCommandView {

    /**
     * Wire bit 7 = the engine's REAL "flaring now" flag:
     * the glow object's own getter,
     * read on the sim thread inside the same per-ship pass as the 7
     * command booleans. WHY: vanilla's drone follower AI never issues a command — it pokes
     * the glow directly, so a station drone's launch-out
     * plume has no command bit; this bit carries every glow source (AI, systems, scripts)
     * exactly, at zero wire growth (the byte's top bit was free).
     */
    int BIT_ENGINE_FLARE = 7;

    /**
     * Within-tick repository-order id, the same {@code shipId} the motion/durability/
     * engine-state views read in the same tick use — for pairing the views that
     * describe one ship at one step. Not stable across ticks when the roster changes.
     */
    long shipId();

    /**
     * The 7 maneuver-command booleans packed in the canonical wire-bit order the
     * UP-lane {@code PilotIntentFrame.heldControls} already uses: bit 0 ACCELERATE,
     * 1 ACCELERATE_BACKWARDS, 2 DECELERATE, 3 TURN_LEFT, 4 TURN_RIGHT,
     * 5 STRAFE_LEFT, 6 STRAFE_RIGHT; bit 7 = {@link #BIT_ENGINE_FLARE} (the engine's
     * flare flag, NOT the UP-lane USE_SYSTEM bit — UP bits 7/8 are never carried here).
     * Bits above {@code 0xFF} are never set.
     */
    int maneuverMask();

    /**
     * {@link #pilotSeat()} value for a hull with NO pilot
     * mark. Seats are 1..255, so 0 is free to mean "absent".
     */
    int PILOT_SEAT_NONE = 0;

    /** The legal width of {@link #pilotMask()}: one byte, ability slot k = bit k. */
    int PILOT_MASK_BITS = 0xFF;

    /**
     * The seat whose pilot mark this hull carries this tick, 1..255, or
     * {@link #PILOT_SEAT_NONE}. WHY it is read here: a joined pilot's ability toggles drive a
     * mod's per-hull behaviour (a drive flame, a subsystem) through a mark the serve writes on
     * the hull's own custom data before the step; the serve's mod reads that mark, but a
     * REMOTE client's copy of the hull never sees it, so the wire carries exactly what the
     * serve's mod read — one source, no second bookkeeping.
     */
    int pilotSeat();

    /**
     * The serve's applied ability bits for {@link #pilotSeat()} (0..255, slot k = bit k; the
     * low byte of the mark's mask), or 0 when the hull carries no mark.
     */
    int pilotMask();

    /** The mark's flags bit 0: the serve follows the pilot's MagicLib decision on this hull. */
    int PILOT_FLAG_FOLLOW = 0x01;

    /**
     * The mark's flags byte (0..255; the low byte of the
     * mark's {@code [3]}), or 0 when the hull carries no mark or a pre-flags (length 2) mark.
     * WHY it rides beside the pair: a remote copy of a piloted hull must gate its MagicLib
     * subsystem exactly as the serve does (follow the pilot's decision only where the serve does,
     * else the mod's own AI), so the wire relays the serve's flags rather than each window
     * re-deciding.
     */
    int pilotFlags();
}
