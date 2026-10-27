package cc.starsector.server.runtime;

import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.match.PasswordSeatLedger;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The password join door's TEAM table: which side each claimed seat plays for, and which side each player last
 * played for. WHY a table and not the descriptor's seat sides: in the PvP shape a seat may play for the OTHER
 * team (a joiner whose team has room but no free seat of its own side takes a free seat of the other side, and
 * the seat plays for the joiner's team), so a team's size is not its side's claimed-seat count. Every other
 * shape follows the descriptor sides and records nothing here.
 *
 * <p>Two maps. {@code teamBySeat}: seat id -> the side that seat plays for, seeded at every admitted PvP claim
 * and dropped when the seat is vacated. {@code teamByCid}: client-instance id -> the side that player last
 * played for, kept across a vacate so a rejoin lands on the same team, bounded at {@link #MEMORY_CAP}: at the
 * bound the oldest DEPARTED player's memory goes first and a seated player's never does (the bound is above
 * twice the largest seat count, so a departed one always exists while the ledger stays consistent). The
 * per-side cap is the descriptor's seat count of that side; a team's size is DERIVED at decide time from the
 * ledger's live claims and {@code teamBySeat}, never a kept counter, so no unwind path can make it drift.
 *
 * <p>The team byte a joiner sends: 0 = none (the door picks), 1 = south (side 0), 2 = north (side 1). It is
 * honoured only when the descriptor seats a pilot on both sides ({@link MatchDescriptor#seatsPilotOnBothSides});
 * elsewhere a nonzero byte is ignored and counted, never refused. A nonzero byte always wins over the memory;
 * 0 means the memory, else the emptier team (a tie goes south).
 *
 * <p>Lock: every method runs under the door's lock (the reader thread's HELLO resolve, the door's vacate funnel,
 * and the chat verb's team switch); the ledger's own monitor is taken inside it, never the reverse. Lifetime:
 * one per door, so a new serve starts with an empty table; the round wrap leaves it alone. Server-only.
 *
 *
 */
public final class SeatTeamTable {

    /** The memory bound: above 2 x 255 seats, the largest seat count a match can mint. */
    public static final int MEMORY_CAP = 512;

    /**
     * One door decision: the ledger's {@code claim} (an admission, or a rejection whose reason the door sends),
     * the side the decision worked for ({@code -1} = none: a one-sided shape, a pick-free shape, or "both teams
     * full"; also the side a squat eviction must free), and what the door counts.
     *
     *
     * <p>{@code askedSide} = the joiner's explicit team pick when that team was FULL, else {@code -1}. The
     * cross-fill law: a full pick is never refused while the other team can take the player. A fresh joiner is
     * seated on the other team ({@code side} = the team it plays for), a player already holding a seat keeps
     * it on its own team; either way the door tells the player in-session. A pick whose team AND the other are
     * full stays {@code match_full} with {@code askedSide} {@code -1}.
     *
     */
    public record Outcome(PasswordSeatLedger.Claim claim, int side, boolean teamIgnored, boolean memoryFull,
            int memoryDropped, int askedSide) {
        /** True when the refusal is a capacity one ({@code match_full}: the door's squat eviction may free a seat
         *  and retry). A full team pick cross-fills instead, so it is admitted and never full. */
        public boolean full() {
            return claim.status() == PasswordSeatLedger.Status.MATCH_FULL;
        }
    }

    private final PasswordSeatLedger ledger;
    private final boolean twoSided;
    private final boolean pvp;
    private final int[] capBySide = new int[2];
    private final Map<Integer, Integer> seatSideById = new HashMap<Integer, Integer>();
    private final Map<Integer, Integer> teamBySeat = new HashMap<Integer, Integer>();
    private final LinkedHashMap<String, Integer> teamByCid = new LinkedHashMap<String, Integer>();
    /** Remembered players with no seat, oldest departure first (the eviction order). */
    private final LinkedHashSet<String> departed = new LinkedHashSet<String>();
    /** The last {@link #claim}'s tell, reset at every claim: the seat it admitted with a full pick ({@code -1} =
     *  none), the pick that was full, and whether the seat was already held (the player stays). The door's
     *  admission deposit reads it in the same lock hold as the claim. */
    private int toldSeat = -1;
    private int toldAsked = -1;
    private boolean toldHeld;

    /** {@code pvp} = the door serves the PvP shape (honoured only on a two-sided descriptor). */
    public SeatTeamTable(PasswordSeatLedger ledger, boolean pvp) {
        this.ledger = ledger;
        MatchDescriptor descriptor = ledger.descriptor();
        for (MatchDescriptor.Seat seat : descriptor.seats()) {
            seatSideById.put(seat.seatId(), seat.side());
            capBySide[seat.side()]++;
        }
        this.twoSided = MatchDescriptor.seatsPilotOnBothSides(descriptor);
        this.pvp = pvp && twoSided;
    }

    /** True when the door counts teams by this table (the PvP shape). */
    public boolean pvp() {
        return pvp;
    }

    /** True when a team pick means something on this descriptor (it seats a pilot on both sides). */
    public boolean teamHonoured() {
        return twoSided;
    }

    /** The team cap of {@code side}: the descriptor's seat count on that side. */
    public int cap(int side) {
        return capBySide[side];
    }

    /** The side seat {@code seatId} plays for: the table's entry, else its descriptor side ({@code -1} = no such seat). */
    public int effectiveSide(int seatId) {
        Integer team = teamBySeat.get(seatId);
        if (team != null) {
            return team;
        }
        Integer side = seatSideById.get(seatId);
        return side == null ? -1 : side;
    }

    /** The live player count of team {@code side}, derived now from the ledger's live claims. */
    public int effectiveCount(int side) {
        return effectiveCountExcluding(side, 0);
    }

    /** Does team {@code side} have room for one more player? */
    public boolean hasRoom(int side) {
        return effectiveCount(side) < capBySide[side];
    }

    /** Does team {@code side} have room for the holder of {@code seatId} (its own seat not counted)? The
     *  chat verb's switch test. */
    public boolean hasRoomFor(int side, int seatId) {
        return effectiveCountExcluding(side, seatId) < capBySide[side];
    }

    /** The emptier team that has room (a tie goes south, side 0); {@code -1} when neither has room. */
    public int emptierSide() {
        int south = effectiveCount(0);
        int north = effectiveCount(1);
        int first = south <= north ? 0 : 1;
        if ((first == 0 ? south : north) < capBySide[first]) {
            return first;
        }
        int second = 1 - first;
        return (second == 0 ? south : north) < capBySide[second] ? second : -1;
    }

    /** The side {@code cid} last played for, or {@code -1}. */
    public int rememberedSideOrMinusOne(String cid) {
        Integer side = cid == null ? null : teamByCid.get(cid);
        return side == null ? -1 : side;
    }

    /**
     * THE seam a team change reads after an admitted claim: the side seat {@code seatId} plays for when it differs
     * from the seat's descriptor side (a cross-filled seat, a restored team, a switched team), else {@code -1}.
     * The fly-in reads the descriptor side, so a seat whose answer is not {@code -1} needs its team applied.
     */
    public int movedSideOrMinusOne(int seatId) {
        Integer team = teamBySeat.get(seatId);
        Integer side = seatSideById.get(seatId);
        return team != null && side != null && team.intValue() != side.intValue() ? team : -1;
    }

    /**
     * The explicit team pick the last {@link #claim} found full, when that claim admitted seat {@code seatId}
     * (the outcome's {@code askedSide}), else {@code -1}. Read by the door's admission deposit in the claim's own
     * lock hold, for the seat that claim just admitted only.
     *
     */
    public int askedSideOrMinusOne(int seatId) {
        return seatId == toldSeat ? toldAsked : -1;
    }

    /**
     * The tell owed to the holder of {@code seatId} after a full pick ({@link #askedSideOrMinusOne}), else null:
     * "The South team is full (3 of 3). You are on the North team." for a fresh joiner, "... You stay on the
     * North team." for a held seat. Only the edge words and the asked team's live count and cap, read now;
     * never player text.
     *
     */
    public String crossFillNoticeOrNull(int seatId) {
        int asked = askedSideOrMinusOne(seatId);
        if (asked < 0) {
            return null;
        }
        return "The " + edgeWord(asked) + " team is full (" + effectiveCount(asked) + " of " + capBySide[asked]
                + "). You " + (toldHeld ? "stay on" : "are on") + " the " + edgeWord(effectiveSide(seatId))
                + " team.";
    }

    /**
     * Record that seat {@code seatId}, held by {@code cid}, plays for {@code side}, and remember it for the cid.
     * The caller checked the room ({@link #hasRoomFor}). Returns the memories dropped at the bound (0 or 1).
     */
    public int pick(int seatId, String cid, int side) {
        if (side != 0 && side != 1) {
            throw new IllegalArgumentException("side must be 0 or 1; got " + side);
        }
        if (!seatSideById.containsKey(seatId)) {
            throw new IllegalArgumentException("seat " + seatId + " is not a descriptor seat");
        }
        teamBySeat.put(seatId, side);
        if (cid == null || cid.isBlank()) {
            return 0;
        }
        departed.remove(cid);
        if (teamByCid.containsKey(cid)) {
            teamByCid.put(cid, side);
            return 0;
        }
        int dropped = 0;
        if (teamByCid.size() >= MEMORY_CAP) {
            dropped = 1;
            if (!evictOneLeft(cid)) {
                return dropped; // every remembered player is seated: the newcomer goes unremembered
            }
        }
        teamByCid.put(cid, side);
        return dropped;
    }

    /** Seat {@code seatId} left (its holder {@code cid}): the seat's team goes, the player's memory stays. */
    public void vacate(int seatId, String cid) {
        teamBySeat.remove(seatId);
        if (cid != null && teamByCid.containsKey(cid)) {
            departed.remove(cid);
            departed.add(cid);
        }
    }

    /** The number of remembered players (row reads). */
    public int memorySize() {
        return teamByCid.size();
    }

    /**
     * The door's claim for {@code cid}: the ledger claim placed by the team law. Off the PvP shape: a one-sided
     * descriptor ignores the byte (claimed exactly as before); a two-sided one claims first-free within the picked
     * side, a full picked side takes the other side's free seat (the cross-fill: a seat's side is its team there),
     * and a pick of 0 claims exactly as before. In PvP: a seated cid keeps its seat (an explicit pick of the
     * other team moves it when that team has room, else it stays on its own team, told); a fresh claim works for
     * the picked team, else the remembered one while it has room (its lack counted), else a pinned seat's own
     * team, else the emptier team; a full picked team is played for the OTHER team while that team could seat
     * the joiner (told; the memory records the team played), else {@code match_full}; the seat is a free one of
     * the team's side, else a free one of the other side (a seat may play for the other team). The ledger's
     * side verdict never leaves this method, so the door never sends {@code team_full}.
     * Cross-fill law:
     */
    public Outcome claim(String cid, long desiredShipId, int teamByte) {
        toldSeat = -1;
        toldAsked = -1;
        toldHeld = false;
        boolean pickSent = teamByte == 1 || teamByte == 2;
        boolean ignored = teamByte != 0 && (!pickSent || !twoSided);
        int requested = pickSent && twoSided ? teamByte - 1 : -1;
        if (!pvp) {
            PasswordSeatLedger.Claim sided = ledger.claim(cid, desiredShipId, requested);
            if (sided.status() == PasswordSeatLedger.Status.SIDE_FULL) { // the other side has a free seat
                return told(new Outcome(ledger.claim(cid, desiredShipId, 1 - requested), 1 - requested, ignored,
                        false, 0, requested), false);
            }
            return new Outcome(sided, requested, ignored, false, 0, -1);
        }
        MatchDescriptor.Seat held = ledger.claimedSeatOrNull(cid);
        if (held != null) {
            int side = effectiveSide(held.seatId());
            int asked = -1;
            if (requested >= 0 && requested != side) {
                if (hasRoomFor(requested, held.seatId())) {
                    side = requested;
                } else {
                    asked = requested; // the other team is full: the seat stays on its own team
                }
            }
            PasswordSeatLedger.Claim rejoined = ledger.claim(cid, desiredShipId);
            return told(new Outcome(rejoined, side, ignored, false, pick(held.seatId(), cid, side), asked), true);
        }
        boolean memoryFull = false;
        int want = requested;
        if (want < 0) {
            int remembered = rememberedSideOrMinusOne(cid);
            if (remembered >= 0 && hasRoom(remembered)) {
                want = remembered;
            } else {
                memoryFull = remembered >= 0;
            }
        }
        MatchDescriptor.Seat pinned = null;
        if (desiredShipId != 0L) {
            pinned = ledger.seatForShipOrNull(desiredShipId);
            if (pinned == null) {
                return new Outcome(ledger.claim(cid, desiredShipId), want, ignored, memoryFull, 0, -1); // seat_unknown
            }
        }
        if (want < 0) {
            want = pinned != null && hasRoom(pinned.side()) ? pinned.side() : emptierSide();
            if (want < 0) {
                return new Outcome(refused(PasswordSeatLedger.Status.MATCH_FULL), -1, ignored, memoryFull, 0, -1);
            }
        }
        int asked = -1;
        if (!hasRoom(want)) { // only an explicit pick reaches here: the memory, the pin and the balance checked room
            if (!hasRoom(1 - want) || !ledger.hasFreeClaimableSeat(-1)) {
                return new Outcome(refused(PasswordSeatLedger.Status.MATCH_FULL), want, ignored, memoryFull, 0, -1);
            }
            asked = want;
            want = 1 - want;
        }
        PasswordSeatLedger.Claim claim;
        if (pinned != null) {
            claim = ledger.claim(cid, desiredShipId); // the pin met the team's cap above
        } else {
            claim = ledger.claim(cid, 0L, want);
            if (claim.status() == PasswordSeatLedger.Status.SIDE_FULL) {
                claim = ledger.claim(cid, 0L, 1 - want); // the cross-fill: the seat plays for the joiner's team
            }
        }
        int dropped = claim.admitted() ? pick(claim.seat().seatId(), cid, want) : 0;
        return told(new Outcome(claim, want, ignored, memoryFull, dropped, asked), false);
    }

    /** Record {@code o}'s tell for the door's deposit when it admitted a full pick ({@code held} = the seat was
     *  already the player's); returns {@code o}. */
    private Outcome told(Outcome o, boolean held) {
        if (o.askedSide() >= 0 && o.claim().admitted()) {
            toldSeat = o.claim().seat().seatId();
            toldAsked = o.askedSide();
            toldHeld = held;
        }
        return o;
    }

    private static String edgeWord(int side) {
        return side == 0 ? "South" : "North";
    }

    private int effectiveCountExcluding(int side, int seatId) {
        Set<Integer> live = ledger.claimedSeatIds();
        int n = 0;
        for (Integer seat : live) {
            if (seat != seatId && effectiveSide(seat) == side) {
                n++;
            }
        }
        return n;
    }

    /** Free one memory slot for {@code keep}'s entry: the oldest departed player first, then (a player who left
     *  without a recorded departure) the oldest unseated one; a seated player never. */
    private boolean evictOneLeft(String keep) {
        for (Iterator<String> it = departed.iterator(); it.hasNext();) {
            String cid = it.next();
            it.remove();
            if (!cid.equals(keep) && ledger.claimedSeatOrNull(cid) == null && teamByCid.remove(cid) != null) {
                return true;
            }
        }
        for (Iterator<String> it = teamByCid.keySet().iterator(); it.hasNext();) {
            String cid = it.next();
            if (!cid.equals(keep) && ledger.claimedSeatOrNull(cid) == null) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    private static PasswordSeatLedger.Claim refused(PasswordSeatLedger.Status status) {
        return new PasswordSeatLedger.Claim(status, null, false);
    }
}
