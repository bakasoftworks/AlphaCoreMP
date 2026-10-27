package cc.starsector.server.runtime;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;

/**
 *
 * Two debug-only, admin-only verbs: /godmode (the caller's ship takes no damage) and /teleport (open the
 * tab map, teleport to the mouse cursor's spot on it). This class is the sim-thread effect half of
 * the two admin DEBUG verbs. The door admin-gates + binds the CALLER's seat and deposits
 * {@code GOD_MODE} / {@code TELEPORT} on the admin queue; the serve loop's operator switch calls
 * {@link #godModeCommand} / {@link #teleportCommand} and mails the returned line as the receipt (the D5 reply
 * law — never an optimistic door-side ack), and calls {@link #applyTick} once per sim tick.
 * Game-free by construction (the product pass compiles with no engine/API jar): every hull
 * touch goes through the {@link HullPort} — the reflective engine port lives in
 * {@code cc.starsector.server.bridge.AdminSeatDebugEnginePort}; contract rows pass a fake.
 *
 * <p>SIM-THREAD-ONLY by design (the {@link AdhocFoeSpawner} law): no lock here, never touch this
 * off the serve thread.
 *
 * <p><b>God mode = the engine's OWN invulnerability idiom</b>: {@code Ship.isHullDamageable()}
 * is {@code hullDamageTakenMult > 0}, and {@code Ship.disable}
 * parks a disabled hull with {@code modifyMult("core_disable", 0)} on
 * that same stat; vanilla dev {@code godMode} zeroes the damage's
 * multiplier inside {@code applyDamageInner} for the local player ship only (settings-keyed —
 * useless headless, and it protects the ENGINE's player ship, not a seat). We ride the public
 * {@code MutableShipStatsAPI} damage-taken mults (armor, EMP, engine, weapon) at 0 under
 * one greppable source id {@link #GODMODE_STAT_ID}, plus a per-tick hull top-up as the
 * belt-and-braces for any hull write that bypasses the damage lane. Re-applied whenever the
 * seat's live hull changes (respawn = a new hull with fresh stats); released on OFF.
 *
 * <p><b>The hull mult is NOT zero</b>:
 * {@code isHullDamageable()} is {@code hullDamageTakenMult > 0}
 * and {@code isTargetable()} folds it in, so a zeroed hull mult makes every foe AI DROP the
 * god-mode ship as a target —
 * nothing shoots at it, which defeats the "test under fire" purpose. The hull rides
 * {@link #GODMODE_HULL_DAMAGE_TAKEN_MULT} (1e-6: targetable, damage rounds to nothing) and the
 * per-tick top-up is the net for whatever trickles through.
 *
 * <p><b>The latch dies with the session</b>: a god-mode seat that is
 * vacated (BYE / death / kick / displacement / same-cid takeover) would otherwise hand
 * invulnerability to the NEXT claimant, who cannot turn it off ({@code ADMIN_ONLY}). Every
 * door vacate seam deposits {@link #silentGodModeOff} for the seat — the same queue verb the
 * chat toggle rides, with NO receipt session (nobody is mailed).
 *
 * <p><b>Teleport</b> = {@code ShipAPI.getLocation().set(x, y)} + velocity zeroed — the engine
 * reads the location vector directly each step, the wire SNAP carries the new pose, and the
 * client's predict-reconcile snaps to it (no client work: the server is the one authority).
 * The pose is re-validated ({@link ChatCommands#parseSpawnPoseOrNull}) and map-clamped
 * ({@link AdhocFoeSpawner#poseInsideMap}) HERE, exactly like the spawn pose — a hull placed past
 * the border would be an instant out-of-bounds withdraw.
 */
public final class AdminSeatDebugLane {

    /** The hull-touching seam (bridge-implemented via reflection; contract rows fake it). Every
     * method is fail-soft in the engine port: a throw is logged and reads as {@code false}. */
    public interface HullPort {
        /** Put the god-mode modifiers on {@code hull}; true when applied. */
        boolean applyGodMode(Object hull);

        /** Remove the god-mode modifiers from {@code hull} (a dead hull is a no-op). */
        void releaseGodMode(Object hull);

        /** Restore the hull's hitpoints to max when below (the per-tick belt-and-braces); returns
         * the hitpoints this call restored (0 when nothing was restored). */
        float topUpHull(Object hull);

        /** Set {@code hull}'s location to {@code (x, y)} and zero its velocity; true on success. */
        boolean setPose(Object hull, float x, float y);
    }

    /** The one {@code MutableStat} source id every god-mode modifier rides (greppable; the
     * {@code core_disable} shape). */
    public static final String GODMODE_STAT_ID = "cc_godmode";

    /** The god-mode HULL damage-taken multiplier — strictly positive so the
     * hull stays {@code isHullDamageable()} and therefore {@code isTargetable()} (foes keep
     * shooting), small enough that any hit rounds to nothing before the tick's top-up. The
     * four other damage-taken mults (armor, EMP, engine, weapon) stay at exactly 0.
     * Contract-pinned ({@code 0 < mult <= 1e-6}). */
    public static final float GODMODE_HULL_DAMAGE_TAKEN_MULT = 1e-6f;

    /**
     * The queue command every door VACATE seam deposits for a leaving seat —
     * a {@code GOD_MODE off} for that seat with receipt session 0 (the file/GUI mint shape:
     * {@code waveReceipt} mails nothing, so no chat line reaches anyone). The serve loop's
     * operator switch runs it on the sim thread exactly like the chat toggle: the seat leaves
     * the god set and the applied hull's modifiers are released. Idempotent on a seat that
     * never had god mode (OFF on OFF is a no-op).
     */
    public static OperatorCommandFile.Command silentGodModeOff(int seatId) {
        return new OperatorCommandFile.Command(OperatorCommandFile.Verb.GOD_MODE, seatId, 0f,
                false, "off", 0L);
    }

    /** Receipt when the seat has no live hull right now (dead / between fly-ins). For god mode
     * the toggle STILL latches and applies at the next fly-in; teleport just refuses. */
    public static final String NO_LIVE_SHIP = "no live ship for your seat";

    public static final String GODMODE_ON = "god mode ON - your ship takes no damage";
    public static final String GODMODE_ON_LATCHED =
            "god mode ON - applies when your ship is live";
    public static final String GODMODE_OFF = "god mode OFF";
    /** {@link #godModeHouseFile}'s refusal
     * when NO live session claims the seat — a latch minted there would outlive its owner (only a
     * session's vacate clears one), so the next claimant would inherit it. Keeps the
     * {@code refused} token and never starts with {@link #GODMODE_ON} (the rig row greps that prefix). */
    public static final String GODMODE_FILE_REFUSED_NOT_LIVE =
            "god mode refused - no live session on that seat (a house-file latch would outlive its owner)";
    /** {@link #godModeChat}'s refusal when the SENDER no longer
     * holds the seat at apply time (a kick / timeout / takeover between the door check and the drain) —
     * every mode, one rule. Mailed to the sender (chat text law); keeps the {@code refused} token and
     * never starts with {@link #GODMODE_ON}. */
    public static final String GODMODE_CHAT_REFUSED_NOT_BOUND =
            "god mode refused - your session no longer holds that seat";
    public static final String TELEPORT_MALFORMED = "teleport refused (malformed pose)";
    public static final String TELEPORT_OUTSIDE_MAP = "teleport refused (pose outside map)";
    public static final String TELEPORT_FAILED = "teleport failed (engine refused the pose)";
    /** {@link #teleportCommand}'s refusal when the SENDER no
     * longer holds the seat at apply time — the {@link #GODMODE_CHAT_REFUSED_NOT_BOUND} twin. Mailed to the
     * sender (chat text law); keeps the {@code refused} token and never starts with {@code "teleported to"}. */
    public static final String TELEPORT_CHAT_REFUSED_NOT_BOUND =
            "teleport refused - your session no longer holds that seat";

    private final Map<Integer, Long> seatWireIdBySeatId;
    private final LongFunction<Object> liveShipByWireId;
    private final HullPort port;
    private final Set<Integer> godSeats = new LinkedHashSet<Integer>();
    /** The hull each god-mode seat's modifiers are CURRENTLY applied to (identity; a respawn
     * swaps the hull and the tick re-applies). */
    private final Map<Integer, Object> godAppliedShipBySeat = new HashMap<Integer, Object>();
    private int godApplies;
    private int teleports;
    /**
     * The APPLIED map's wire ids as a primitive set — the
     * source of the replica bit {@code ShipFluxSnapshot.FLAG_GOD} the live flux fold stamps (never
     * {@link #godSeats}, the latch: a latched seat with no live hull or a failed apply reads 0).
     * Rebuilt only when {@link #godAppliedDirty} (set at the four {@link #godAppliedShipBySeat}
     * writers) at the tail of {@link #godMode} / {@link #applyTick}; at most one entry per seat, so
     * the array grows once per serve in practice and a quiet tick allocates nothing.
     * A seat's wire id is band-fixed for the whole serve ({@code PilotServeResolver} is the one
     * writer of the seat → wire map), so the set never names a reused id.
     */
    private long[] godAppliedWires = new long[0];
    private int godAppliedWireCount;
    private boolean godAppliedDirty;
    private final LongPredicate godAppliedWirePredicate = this::isGodAppliedWire;
    // Serve-span meter totals + the previous round line's snapshot (the line prints deltas).
    private long godPredReads;
    private long godAppliedTicks;
    private long godTopups;
    private float godTopupMaxDeficit; // per round: reset by godRoundLineAndReset
    private long godWireUnmapped;
    private long roundBasePredReads;
    private long roundBaseAppliedTicks;
    private long roundBaseTopups;
    private long roundBaseWireUnmapped;

    /**
     * @param seatWireIdBySeatId the serve's seat → wire-id map (the SAME live map the pod
     *        driver reads; never copied — read per call)
     * @param liveShipByWireId wire id → the live engine hull or null (the serve passes
     *        {@code RealEngineDemoRoster::liveShipForWireIdOrNull}; contract rows pass a fake)
     * @param port the hull-touching seam (bridge engine port / contract fake)
     */
    public AdminSeatDebugLane(Map<Integer, Long> seatWireIdBySeatId,
            LongFunction<Object> liveShipByWireId, HullPort port) {
        this.seatWireIdBySeatId = Objects.requireNonNull(seatWireIdBySeatId, "seatWireIdBySeatId");
        this.liveShipByWireId = Objects.requireNonNull(liveShipByWireId, "liveShipByWireId");
        this.port = Objects.requireNonNull(port, "port");
    }

    /**
     * {@code /godmode} for {@code seatId}: {@code mode} = {@code toggle|on|off} (grammar-folded
     * by {@link ChatCommands}; anything else reads as toggle — the door only ever deposits the
     * three). Returns the receipt line (constant texts, contract-pinned).
     */
    public String godMode(int seatId, String mode) {
        boolean on;
        if ("on".equals(mode)) {
            on = true;
        } else if ("off".equals(mode)) {
            on = false;
        } else {
            on = !godSeats.contains(seatId);
        }
        if (!on) {
            godSeats.remove(seatId);
            Object applied = godAppliedShipBySeat.remove(seatId);
            godAppliedDirty |= applied != null;
            if (applied != null) {
                port.releaseGodMode(applied);
            }
            rebuildGodAppliedWiresIfDirty();
            return GODMODE_OFF;
        }
        godSeats.add(seatId);
        boolean live = applySeat(seatId);
        rebuildGodAppliedWiresIfDirty();
        return live ? GODMODE_ON : GODMODE_ON_LATCHED;
    }

    /**
     * The HOUSE-FILE twin of
     * {@link #godMode} — callers with receipt session 0 only (the {@code god-mode <seat> on|off}
     * file verb, the vacate seam's {@link #silentGodModeOff}; no GUI mints one). {@code "off"} = exactly
     * {@code godMode(seat, "off")}: an operator can always clear. {@code "on"} with no live SESSION
     * on the seat ({@code seatHasLiveSession} = the door's {@code seatHasLiveSession}) refuses
     * WITHOUT touching the god set, the applied map or the port — a latch the CHAT verb minted
     * stays exactly as it is. The gate is the session, not the hull: the "no live hull"
     * guard let a vacated seat whose hull was still in play latch for the next claimant, while a
     * live session's dead hull MAY latch here ({@link #GODMODE_ON_LATCHED}) because that latch dies
     * with the session's vacate. The parser accepts {@code on|off} only; anything else
     * throws, so a parser change fails loud (the serve's operator fault belt counts it).
     */
    public String godModeHouseFile(int seatId, String mode, IntPredicate seatHasLiveSession) {
        if ("off".equals(mode)) {
            return godMode(seatId, "off");
        }
        if (!"on".equals(mode)) {
            throw new IllegalArgumentException("house-file god mode takes on|off only (seat " + seatId + ")");
        }
        if (!seatHasLiveSession.test(seatId)) {
            return GODMODE_FILE_REFUSED_NOT_LIVE;
        }
        return godMode(seatId, "on");
    }

    /** {@link #godModeChat}'s use-time question — does session
     * {@code sessionId} (signed; 0 is never minted, so it answers false) still hold {@code seatId}
     * (1..64)? Door-implemented under its lock ({@code PidsChatVerbs#sessionHoldsSeat}); contract rows
     * fake it. Also asked by {@link #teleportCommand} and the chat {@code /podtest} start
     * ({@code PodScenarioDriver}). */
    @FunctionalInterface
    public interface SessionSeatPredicate {
        boolean holds(long sessionId, int seatId);
    }

    /**
     * The CHAT twin of {@link #godModeHouseFile} — callers with
     * a receipt session != 0 (the door's {@code /godmode} deposit). The door drops a BYE'd / failed /
     * seat-vacated sender's chat before dispatch, but a kick, timeout or takeover can land between that
     * check and this sim-thread drain, after the vacate's {@link #silentGodModeOff} already drained: a
     * latch applied then would pass to the seat's next claimant. So the SENDER must still hold the seat
     * ({@code senderHoldsSeat}, read here at apply time, never cached) or EVERY mode ({@code on},
     * {@code off}, toggle — one rule) refuses with {@link #GODMODE_CHAT_REFUSED_NOT_BOUND} without
     * touching the god set, the applied map or the port. Refusing a stale {@code off} loses nothing:
     * the predicate turns false only at a vacate, and every vacate seam deposits the silent OFF. A
     * throwing predicate propagates before any state is touched (the serve's operator fault belt
     * counts it and mails its constant receipt).
     */
    public String godModeChat(int seatId, String mode, long senderSessionId,
            SessionSeatPredicate senderHoldsSeat) {
        if (!senderHoldsSeat.holds(senderSessionId, seatId)) {
            return GODMODE_CHAT_REFUSED_NOT_BOUND;
        }
        return godMode(seatId, mode);
    }

    /**
     * The serve's ONE
     * {@code GOD_MODE} entry (the operator switch in {@code PilotServeSession}, sim thread) — the router
     * between the two twins, so arm order and the predicate hand-off are pinned by contract rows instead
     * of a source-text count. {@code receiptSessionId == 0L} = the house file or the vacate seam's silent
     * OFF ({@link #silentGodModeOff}) → {@link #godModeHouseFile}, gated on a live SESSION
     * ({@code seatHasLiveSession}); the guard keyed on "no live hull" and let a VACATED seat whose hull
     * was still in play latch (the next claimant inherited god mode). Any other value (SIGNED — a negative
     * session id is normal) = a chat deposit → {@link #godModeChat}, gated on its SENDER still holding the
     * seat ({@code senderHoldsSeat} receives exactly {@code receiptSessionId} and {@code seatId}). Each arm
     * consults only its own predicate.
     */
    public String godModeCommand(int seatId, String mode, long receiptSessionId,
            IntPredicate seatHasLiveSession, SessionSeatPredicate senderHoldsSeat) {
        return receiptSessionId == 0L
                ? godModeHouseFile(seatId, mode, seatHasLiveSession)
                : godModeChat(seatId, mode, receiptSessionId, senderHoldsSeat);
    }

    public boolean isGodMode(int seatId) {
        return godSeats.contains(seatId);
    }

    /** Once per sim tick: re-apply to any god-mode seat whose live hull changed (respawn) and
     * top the hull up. One map read per god-mode seat; nothing when the set is empty. */
    public void applyTick() {
        for (Integer seatId : godSeats) {
            applySeat(seatId);
        }
        // Flush the applied-wire set BEFORE this frame's
        // publish folds it (same serve thread: applyTick precedes publishNextTick), then meter
        // the tick when any hull carries the mults (the round line pairs it with god_pred_reads).
        rebuildGodAppliedWiresIfDirty();
        if (!godAppliedShipBySeat.isEmpty()) {
            godAppliedTicks++;
        }
    }

    /**
     * True when a live hull carries the modifiers after this call. On a HULL SWAP while the
     * previous hull is still live (a {@code /transfer} re-key: the seat now drives another hull
     * and the old one goes back to the AI) the previous hull is RELEASED before the new one is
     * applied — without it the AI leftover stayed
     * invulnerable for the rest of the round. A dead previous hull is dropped without a port
     * call (release on a dead hull is a no-op anyway; a respawn is a fresh hull).
     */
    private boolean applySeat(int seatId) {
        Object hull = liveHullOrNull(seatId);
        if (hull == null) {
            // dead hull: nothing to release, re-apply on fly-in
            godAppliedDirty |= godAppliedShipBySeat.remove(seatId) != null;
            return false;
        }
        Object previous = godAppliedShipBySeat.get(seatId);
        if (previous != hull) {
            if (previous != null) {
                godAppliedShipBySeat.remove(seatId); // forget first: a failed apply must not re-release
                godAppliedDirty = true;
                port.releaseGodMode(previous);
            }
            if (!port.applyGodMode(hull)) {
                return false; // the port logged; retry next tick
            }
            godAppliedShipBySeat.put(seatId, hull);
            godAppliedDirty = true;
            godApplies++;
            System.out.println("npeer-serve god_mode_applied seat=" + seatId
                    + " applies=" + godApplies);
        }
        // Meter what the top-up restored — a count alone
        // cannot tell 1e-6-scaled dust from a real bypass of the damage-taken mults.
        float restored = port.topUpHull(hull);
        if (restored > 0f) {
            godTopups++;
            godTopupMaxDeficit = Math.max(godTopupMaxDeficit, restored);
        }
        return true;
    }

    /**
     * {@code /teleport @x,y} for {@code seatId}: grammar re-validation, the map clamp
     * (±(mapHalf + {@link AdhocFoeSpawner#POSE_MAP_MARGIN})), then the live hull's location is
     * SET and its velocity zeroed. Returns the receipt line.
     */
    public String teleport(int seatId, String poseToken, float mapHalf) {
        float[] pose = ChatCommands.parseSpawnPoseOrNull(poseToken);
        if (pose == null) {
            return TELEPORT_MALFORMED;
        }
        if (!AdhocFoeSpawner.poseInsideMap(pose, mapHalf)) {
            return TELEPORT_OUTSIDE_MAP;
        }
        Object hull = liveHullOrNull(seatId);
        if (hull == null) {
            return NO_LIVE_SHIP;
        }
        if (!port.setPose(hull, pose[0], pose[1])) {
            return TELEPORT_FAILED;
        }
        teleports++;
        return "teleported to " + AdhocFoeSpawner.formatPose(pose);
    }

    /**
     * The serve's ONE {@code TELEPORT} entry (the operator
     * switch in {@code PilotServeSession}, sim thread), the {@link #godModeChat} shape: the SENDER must still
     * hold the seat ({@code senderHoldsSeat} receives exactly {@code receiptSessionId} and {@code seatId},
     * read at apply time, never cached) or it refuses with {@link #TELEPORT_CHAT_REFUSED_NOT_BOUND} — no
     * port call, {@link #teleportCount} unchanged. Without it a kick / timeout / takeover landing between
     * the door's check and this drain moves the seat's NEXT claimant's hull. There is NO receipt-0 arm: no
     * house-file or GUI verb mints a {@code TELEPORT} ({@code OperatorCommandFile} parses none) and the door
     * never mints session id 0, so a receipt 0 is asked like any other id (signed; a negative id is normal)
     * and a false answer refuses. True ⇒ {@link #teleport}, unchanged. A throwing predicate propagates before
     * any state is touched (the serve's operator fault belt counts it and mails its constant receipt).
     */
    public String teleportCommand(int seatId, String poseToken, float mapHalf, long receiptSessionId,
            SessionSeatPredicate senderHoldsSeat) {
        if (!senderHoldsSeat.holds(receiptSessionId, seatId)) {
            return TELEPORT_CHAT_REFUSED_NOT_BOUND;
        }
        return teleport(seatId, poseToken, mapHalf);
    }

    public int teleportCount() {
        return teleports;
    }

    public int godApplyCount() {
        return godApplies;
    }

    /**
     * The god-mode replica bit's predicate + its meters.
     * {@link #godAppliedWirePredicateOrNull} is what the live source's flux fold reads ONCE per
     * publish (armed per round in {@code PilotServeSession}'s post-mint setter block — the source
     * is re-minted every round while this lane lives for the serve): null when no hull
     * carries the mults (the fold then pays nothing per ship), else the one cached
     * predicate testing a published wire id against the applied set. Serve thread only, like
     * the lane. {@link #godRoundLineAndReset} = the per-round line (deltas since its last call;
     * counts + one float, no ids): {@code god_applied_ticks} moving while {@code god_pred_reads}
     * sits at 0 = the round's source lost the supplier; {@code god_topup_max_deficit} tells
     * rounding dust from a real bypass of the damage-taken mults.
     */
    public LongPredicate godAppliedWirePredicateOrNull() {
        godPredReads++;
        return godAppliedWireCount == 0 ? null : godAppliedWirePredicate;
    }

    public long godPredReads() {
        return godPredReads;
    }

    public long godAppliedTicks() {
        return godAppliedTicks;
    }

    public long godTopups() {
        return godTopups;
    }

    public float godTopupMaxDeficit() {
        return godTopupMaxDeficit;
    }

    public long godWireUnmapped() {
        return godWireUnmapped;
    }

    public String godRoundLineAndReset() {
        String line = "god-mode round god_pred_reads=" + (godPredReads - roundBasePredReads)
                + " god_applied_ticks=" + (godAppliedTicks - roundBaseAppliedTicks)
                + " god_topups=" + (godTopups - roundBaseTopups)
                + " god_topup_max_deficit=" + String.format(Locale.ROOT, "%.1f", godTopupMaxDeficit)
                + " god_wire_unmapped=" + (godWireUnmapped - roundBaseWireUnmapped);
        roundBasePredReads = godPredReads;
        roundBaseAppliedTicks = godAppliedTicks;
        roundBaseTopups = godTopups;
        roundBaseWireUnmapped = godWireUnmapped;
        godTopupMaxDeficit = 0f;
        return line;
    }

    private void rebuildGodAppliedWiresIfDirty() {
        if (!godAppliedDirty) {
            return;
        }
        godAppliedDirty = false;
        int needed = godAppliedShipBySeat.size();
        if (needed > godAppliedWires.length) {
            // sized to the serve's seat count: applied seats are a subset, so this is the one grow
            godAppliedWires = new long[Math.max(needed, seatWireIdBySeatId.size())];
        }
        int count = 0;
        for (Integer seatId : godAppliedShipBySeat.keySet()) {
            Long wire = seatWireIdBySeatId.get(seatId);
            if (wire == null) {
                godWireUnmapped++; // expected 0: applySeat resolved this seat's hull through the same map
                continue;
            }
            godAppliedWires[count++] = wire;
        }
        godAppliedWireCount = count;
    }

    private boolean isGodAppliedWire(long wireId) {
        for (int i = 0; i < godAppliedWireCount; i++) {
            if (godAppliedWires[i] == wireId) {
                return true;
            }
        }
        return false;
    }

    private Object liveHullOrNull(int seatId) {
        Long wire = seatWireIdBySeatId.get(seatId);
        return wire == null ? null : liveShipByWireId.apply(wire);
    }
}
