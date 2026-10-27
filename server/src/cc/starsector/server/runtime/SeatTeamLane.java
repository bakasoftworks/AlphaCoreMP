package cc.starsector.server.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntUnaryOperator;
import java.util.function.LongFunction;

/**
 * A debug/admin {@code /switchteams} (spec: "instant swaps the
 * person's team immediately while theyre alive, respawn waits till their next respawn ... should
 * flip which side of the map you respawn from, and from what direction"): the sim-thread effect
 * half of the team-switch verb, the {@link AdminSeatDebugLane} shape. The door admin-gates,
 * resolves the TARGET seat from the menu's wire id and deposits {@code SWITCH_TEAM} on the admin
 * queue; the serve loop's operator switch calls {@link #switchTeam} and mails the returned line as
 * the receipt (the D5 reply law — never an optimistic door-side ack). Game-free by construction:
 * every hull touch goes through the {@link HullPort} (the reflective engine port lives in
 * {@code cc.starsector.server.bridge.SeatTeamEnginePort}; contract rows pass a fake).
 *
 * <p>SIM-THREAD-ONLY by design (the {@link AdhocFoeSpawner} law): no lock here, never touch this
 * off the serve thread.
 *
 * <p><b>The state is ONE map</b>: a seat's NEXT fly-in side (absent = the descriptor's baked
 * side). The team of a seat's LIVE hull is never stored — it is the hull's own engine owner, read
 * through the port at request time, so a hull that changed hands some other way can never
 * disagree with a ledger here. {@code respawn} toggles the next fly-in side; {@code instant}
 * flips the live hull to the side opposite its CURRENT owner and pins the next fly-in to match
 * (no live hull ⇒ it degrades to the respawn toggle, said so in the receipt). The fly-in itself
 * needs no pose math: the roster spawns a seat with a null location, so the ENGINE places the
 * hull at its owner's home edge with the owner's default facing (side 0 = south / 90, side 1 =
 * north / 270).
 *
 * <p><b>The latch dies with the session</b> (the god-mode F3-2 law): every door vacate seam
 * deposits {@link #silentClear} so the NEXT claimant of the seat starts on the descriptor side.
 * A round reset clears everything ({@link #clearAll}) — the gated reset redeploys seats from the
 * descriptor, the {@code /ship} override precedent — EXCEPT in the PvP shape, where the teams are
 * the players' own choice: there the wrap keeps the map and the gated reset redeploys each seat on
 * its stored side ({@link #sidesBySeat}); every change of it arrives as a {@link #pickTeam} SET.
 *
 */
public final class SeatTeamLane {

    /** The hull-touching seam (bridge-implemented via reflection; contract rows fake it). */
    public interface HullPort {
        /** The hull's live engine owner ({@code 0}/{@code 1}), or {@code -1} when unreadable or
         * neutral (a hulk reads 100 — never a flip target). */
        int ownerOf(Object hull);

        /** The FULL live flip of {@code hull} (and everything that caches its side) to
         * {@code newOwner}; true when the hull now reads {@code newOwner}. Fail-soft: a throw
         * is logged in the port and reads as {@code false}. */
        boolean flipOwner(Object hull, int newOwner);
    }

    /** The two request modes (the chat grammar's + the house file's FIXED keywords). */
    public static final String MODE_INSTANT = "instant";
    public static final String MODE_RESPAWN = "respawn";
    /** Door-minted only (never parseable from chat or the house file): drop the seat's latch. */
    static final String MODE_CLEAR = "clear";

    public static final String NO_SUCH_SEAT = "team switch refused (no such seat)";
    public static final String FLIP_FAILED = "team switch failed (engine refused the flip)";

    /** Side → the human-facing team words (fortress vocabulary: the station's team spawns
     * south, the waves' team north). */
    public static String teamWords(int side) {
        return side == 0 ? "station team (south)" : "wave team (north)";
    }

    /** The {@code team-pick} words, by side index: 0 = south, 1 = north (fixed map-edge labels). */
    public static final String WORD_SOUTH = "south";
    public static final String WORD_NORTH = "north";
    /** The one refusal line of a {@link #pickTeam} whose seat changed hands since the door decided. */
    public static final String SEAT_CHANGED = "team switch refused (seat changed)";

    /**
     * The team words for {@code side}: in the PvP shape a fixed map-edge label by side index ("South
     * team" = 0, "North team" = 1; the shape has no station and no wave team of its own), else
     * {@link #teamWords(int)} byte-identical.
     *
     */
    public static String teamWords(int side, boolean pvp) {
        return pvp ? (side == 0 ? "South team" : "North team") : teamWords(side);
    }

    /** {@code "north"} / {@code "south"} → side 1 / 0; anything else → {@code -1}. */
    public static int sideOfWordOrNeg(String word) {
        return WORD_NORTH.equals(word) ? 1 : WORD_SOUTH.equals(word) ? 0 : -1;
    }

    /** Side 1 / 0 → {@code "north"} / {@code "south"} (the {@code team-pick} text). */
    public static String wordOfSide(int side) {
        return side == 1 ? WORD_NORTH : WORD_SOUTH;
    }

    /**
     * The queue command every door VACATE seam deposits for a leaving seat — receipt session 0
     * (nobody is mailed), the {@link AdminSeatDebugLane#silentGodModeOff} shape. Idempotent.
     */
    public static OperatorCommandFile.Command silentClear(int seatId) {
        return new OperatorCommandFile.Command(OperatorCommandFile.Verb.SWITCH_TEAM, seatId, 0f,
                false, MODE_CLEAR, 0L);
    }

    private final Map<Integer, Long> seatWireIdBySeatId;
    private final LongFunction<Object> liveShipByWireId;
    private final IntUnaryOperator descriptorSideOfSeat;
    private final HullPort port;
    private final Map<Integer, Integer> nextSideBySeat = new HashMap<Integer, Integer>();
    private int instantFlips;
    private boolean pvpWords;
    private int teamPicks;
    private int teamPicksRefused;

    /**
     * @param seatWireIdBySeatId the serve's seat → wire-id map (read per call, never copied)
     * @param liveShipByWireId wire id → the live engine hull or null
     * @param descriptorSideOfSeat seat id → the descriptor's baked side, or negative for an
     *        unminted seat (the serve passes {@code RealEngineDemoRoster::seatSideOrNeg})
     * @param port the hull-touching seam (bridge engine port / contract fake)
     */
    public SeatTeamLane(Map<Integer, Long> seatWireIdBySeatId,
            LongFunction<Object> liveShipByWireId, IntUnaryOperator descriptorSideOfSeat,
            HullPort port) {
        this.seatWireIdBySeatId = Objects.requireNonNull(seatWireIdBySeatId, "seatWireIdBySeatId");
        this.liveShipByWireId = Objects.requireNonNull(liveShipByWireId, "liveShipByWireId");
        this.descriptorSideOfSeat =
                Objects.requireNonNull(descriptorSideOfSeat, "descriptorSideOfSeat");
        this.port = Objects.requireNonNull(port, "port");
    }

    /**
     * {@code /switchteams} for {@code seatId}: {@code mode} = {@code instant|respawn} (grammar-
     * folded; the door also mints {@code clear}). Returns the receipt line (constant-shaped: the
     * seat NUMBER is the only variable — never peer text).
     */
    public String switchTeam(int seatId, String mode) {
        if (MODE_CLEAR.equals(mode)) {
            nextSideBySeat.remove(seatId);
            return "";
        }
        int descriptorSide = descriptorSideOfSeat.applyAsInt(seatId);
        if (descriptorSide != 0 && descriptorSide != 1) {
            return NO_SUCH_SEAT;
        }
        int pending = nextSideBySeat.getOrDefault(seatId, descriptorSide);
        if (MODE_INSTANT.equals(mode)) {
            Object hull = liveHullOrNull(seatId);
            int liveOwner = hull == null ? -1 : port.ownerOf(hull);
            if (liveOwner == 0 || liveOwner == 1) {
                int target = 1 - liveOwner;
                if (!port.flipOwner(hull, target)) {
                    return FLIP_FAILED;
                }
                nextSideBySeat.put(seatId, target);
                instantFlips++;
                return "seat " + seatId + " is now on the " + teamWords(target, pvpWords);
            }
            nextSideBySeat.put(seatId, 1 - pending);
            return "seat " + seatId + " has no live ship - next respawn on the "
                    + teamWords(1 - pending, pvpWords);
        }
        nextSideBySeat.put(seatId, 1 - pending);
        return "seat " + seatId + ": next respawn on the " + teamWords(1 - pending, pvpWords);
    }

    /**
     * Speak the PvP team words in every receipt ({@link #teamWords(int, boolean)}). The serve sets it
     * once, before its first frame, from the served shape; the default keeps the fortress words.
     *
     */
    public void usePvpTeamWords(boolean pvp) {
        this.pvpWords = pvp;
    }

    /**
     * SET seat {@code seatId}'s next fly-in side to {@code side} (0 / 1) — never a toggle. The carrier
     * of every team change the door decided ({@code TEAM_PICK}): the door already checked the room
     * under its lock, so here only the seat's holder is re-checked ({@code holderStill} = the serve's
     * "the session that asked still holds this seat"; a re-simulation answers it from the replayed
     * sessions). A changed or vacated holder, an unminted seat or a side outside 0 / 1 refuses
     * (false, counted) and changes nothing. A side equal to the descriptor's drops the entry, so the
     * fly-in reads the descriptor exactly as a seat never switched.
     *
     */
    public boolean pickTeam(int seatId, int side, java.util.function.BooleanSupplier holderStill) {
        int descriptorSide = descriptorSideOfSeat.applyAsInt(seatId);
        if ((side != 0 && side != 1) || (descriptorSide != 0 && descriptorSide != 1)
                || !holderStill.getAsBoolean()) {
            teamPicksRefused++;
            return false;
        }
        if (side == descriptorSide) {
            nextSideBySeat.remove(seatId);
        } else {
            nextSideBySeat.put(seatId, side);
        }
        teamPicks++;
        return true;
    }

    /**
     * Every seat's stored next fly-in side ({@code seatId -> 0 / 1}; an absent seat flies on its
     * descriptor side), as a read-only view: the gated round reset redeploys these seats on these
     * sides. Read on the serve thread only (the lane's own thread law).
     *
     */
    public Map<Integer, Integer> sidesBySeat() {
        return java.util.Collections.unmodifiableMap(nextSideBySeat);
    }

    /** Applied {@link #pickTeam} calls (row reads). */
    public int teamPickCount() {
        return teamPicks;
    }

    /** Refused {@link #pickTeam} calls (row reads). */
    public int teamPickRefusedCount() {
        return teamPicksRefused;
    }

    /** The side {@code seatId}'s next fly-in must use, or {@code -1} = the descriptor's. */
    public int nextFlyInSideOrNeg(int seatId) {
        Integer side = nextSideBySeat.get(seatId);
        return side == null ? -1 : side;
    }

    /** Round reset: every seat back to its descriptor side. */
    public void clearAll() {
        nextSideBySeat.clear();
    }

    public int instantFlipCount() {
        return instantFlips;
    }

    private Object liveHullOrNull(int seatId) {
        Long wire = seatWireIdBySeatId.get(seatId);
        return wire == null ? null : liveShipByWireId.apply(wire);
    }
}
