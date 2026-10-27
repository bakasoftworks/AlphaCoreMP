package cc.starsector.server.bridge;

import cc.starsector.server.bridge.RealEngineDemoRoster.RealEngineDemoRosterException;
import cc.starsector.server.match.MatchDescriptor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@link RealEngineDemoRoster}'s DESCRIPTOR-IDENTITY/LAYOUT family (cut 2 of the roster extraction
 * — cut 1 landed the
 * seat-rebind verbs as {@link SeatRebind}). Pure functions of the round's active
 * descriptor with ZERO engine reflection: the seatId-sorted ordinal law ({@link #seatSlot} /
 * {@link #fullLayoutSideOrdinals} / {@link #seatVariantSlotsBySide}), the server-authoritative
 * formation grid ({@link #formationPosition}), the descriptor-path seat name ({@link #seatShipName} /
 * {@link #resolveSeatShipName}) and the per-mille CR encoding ({@link #crPerMille}). Bodies MOVED
 * verbatim from {@code RealEngineDemoRoster} — zero behavior deltas.
 *
 * <p><b>State stays roster-owned.</b> These reads touch only the roster's package-private
 * {@code deployedDescriptor} (and {@code SPAWN_X}); this class holds no state. They carry NO
 * synchronization of their own — {@link #seatSlot} reads {@code deployedDescriptor} under the
 * <b>caller's</b> {@code RealEngineDemoRoster.class} monitor (every caller — the roster's
 * {@code static synchronized} deploy/fly-in verbs and {@link SeatRebind}'s
 * {@code synchronized (RealEngineDemoRoster.class)} bodies — already holds it). The public
 * {@code synchronized} entry {@code RealEngineDemoRoster.seatDeployedVariantId} deliberately stays
 * on the roster so its acquired monitor is unchanged: a relocated {@code static synchronized} would
 * silently lock this class instead of the roster (the cut-1 monitor law).
 */
public final class DescriptorLayout {

    private DescriptorLayout() {
    }

    /** One seat's descriptor slot under the sorted-seats/ordinal law (W7 B1b extraction —
     * the SAME find/sort/ordinal walk {@link RealEngineDemoRoster#midDeploySeat} always ran, shared
     * with the descriptor-pure reset enrichment). Caller holds the {@code RealEngineDemoRoster.class}
     * lock + a non-null {@code RealEngineDemoRoster.deployedDescriptor}. */
    record SeatSlot(MatchDescriptor.Seat seat, int side, String descriptorVariantId) {
    }

    /** The sorted-seats/ordinal law: seatId-sorted full seat list, FULL-layout per-side deploy
     * ordinal, roster slot → descriptor variant id (occupancy-independent).
     * THROWS on a seat the descriptor never minted. */
    static SeatSlot seatSlot(int seatId) {
        List<MatchDescriptor.Seat> seats = new ArrayList<>(RealEngineDemoRoster.deployedDescriptor.seats());
        seats.sort(Comparator.comparingInt(MatchDescriptor.Seat::seatId));
        int seatIndex = -1;
        for (int i = 0; i < seats.size(); i++) {
            if (seats.get(i).seatId() == seatId) {
                seatIndex = i;
                break;
            }
        }
        if (seatIndex < 0) {
            throw new RealEngineDemoRosterException(
                    "seat " + seatId + ": not a minted descriptor seat");
        }
        MatchDescriptor.Seat seat = seats.get(seatIndex);
        int side = seat.side();
        int ordinal = fullLayoutSideOrdinals(seats)[seatIndex];
        String descriptorVariantId =
                seatVariantSlotsBySide(RealEngineDemoRoster.deployedDescriptor.roster()).get(side).get(ordinal);
        return new SeatSlot(seat, side, descriptorVariantId);
    }

    /**
     * FULL-layout per-side deploy ordinal for each seat of the seatId-sorted full seat list —
     * occupancy-independent by construction: a seat's ordinal depends only on the mint, never on
     * who else joined. Public pure function, unit-asserted game-free
     * (ServerContractTests).
     */
    public static int[] fullLayoutSideOrdinals(List<MatchDescriptor.Seat> seatIdSortedSeats) {
        int[] ordinals = new int[seatIdSortedSeats.size()];
        int[] next = new int[2];
        for (int i = 0; i < seatIdSortedSeats.size(); i++) {
            ordinals[i] = next[seatIdSortedSeats.get(i).side()]++;
        }
        return ordinals;
    }

    /**
     * Per-side SEAT variant slots in roster declaration order — the occupancy-independent
     * seat-ordinal→variant mapping both {@link RealEngineDemoRoster#buildDescriptorSpecs} and
     * {@link RealEngineDemoRoster#midDeploySeat} consume. Public pure function,
     * unit-asserted game-free (ServerContractTests).
     */
    public static List<List<String>> seatVariantSlotsBySide(List<MatchDescriptor.RosterRow> roster) {
        List<List<String>> bySide = List.of(new ArrayList<>(), new ArrayList<>());
        for (MatchDescriptor.RosterRow row : roster) {
            if (row.pilot() == MatchDescriptor.PilotKind.SEAT) {
                for (int k = 0; k < row.count(); k++) {
                    bySide.get(row.side()).add(row.variantId());
                }
            }
        }
        return bySide;
    }

    /**
     * Formation slot for deploy ordinal {@code j} (0-based within its side) of
     * {@code sideShipCount} ships (server-only — the descriptor carries no positions
     * BY DESIGN; formation is server-authoritative). VANILLA-AXIS:
     * rows of up to 8 ships march away from the center line in 600-unit steps
     * starting at ∓{@code SPAWN_X} on the Y axis (side 0 = bottom, the engine's own home edge for
     * owner 0); within a row ships spread horizontally 400 apart, centered on x=0. j=0 of a
     * 1-ship side is exactly (0, ∓{@code SPAWN_X}), facing 90/270 toward the foe. 30 ships:
     * 4 rows, |x| ≤ 1400, |y| ≤ 3300 (inside the ±6000 map half). Package-private pure function,
     * unit-assertable game-free.
     */
    static float[] formationPosition(int side, int j, int sideShipCount) {
        float sign = side == 0 ? -1.0f : 1.0f;
        int col = j / 8;
        int row = j % 8;
        int shipsInCol = Math.min(8, sideShipCount - col * 8);
        float y = sign * (RealEngineDemoRoster.SPAWN_X + col * 600.0f);
        float x = (row - (shipsInCol - 1) / 2.0f) * 400.0f;
        return new float[] {x, y};
    }

    /**
     * Descriptor-path seat ship name: seats 1/2 keep the long-standing defaults +
     * literal {@code cc.match.seat1Name}/{@code cc.match.seat2Name} overrides (the check-knobs
     * literal-read idiom, validated ≤24 printable chars); seats ≥3 get a deterministic server-side
     * default — N-seat name replication is the D4.5 SNMS rider, deliberately NOT generalized here.
     */
    static String seatShipName(int seatId) {
        if (seatId == 1) {
            return resolveSeatShipName(1, System.getProperty("cc.match.seat1Name"), seatDefaultName(1));
        }
        if (seatId == 2) {
            return resolveSeatShipName(2, System.getProperty("cc.match.seat2Name"), seatDefaultName(2));
        }
        return seatDefaultName(seatId);
    }

    /** The player-visible seat default ship name — ONE form for every seat, ≤ 24 chars. */
    public static final String SEAT_DEFAULT_NAME_PREFIX = "AlphaCoreMP Seat ";

    /**
     * W11 (only public-facing text drops "CC"): the default engine ship name of
     * seat {@code seatId} when its {@code cc.match.seatNName} knob is unset — was the leaked
     * {@code "CC Server Mudskipper NL"} / {@code "CC Seat N"} pair. Reaches the target box, the
     * roster and the debrief on every client; no parser (tooling, contract rows, ops) keys on it.
     */
    public static String seatDefaultName(int seatId) {
        return SEAT_DEFAULT_NAME_PREFIX + seatId;
    }

    /**
     * Seat display names, server half: {@code cc.match.seat1Name}/{@code cc.match.seat2Name}
     * name the deploy-order roster ships (spec 0 = seat 1, spec 1 = seat 2) through the engine's own
     * {@code FleetMember.setShipName} slot — the SAME values the rendered clients apply at ship-bind, so
     * the parity walker's {@code ship.getName} row agrees across JVMs (evidence). Unset/blank
     * ⇒ the {@link #seatDefaultName} label (W11: was 'CC Server Mudskipper NL'). Same validation as the client
     * plugin (single-line printable, ≤24 chars); THROWS loud at deploy — a typo'd name must never
     * silently deploy under the fallback label.
     */
    static String resolveSeatShipName(int seat, String raw, String fallback) {
        String prop = "cc.match.seat" + seat + "Name";
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        String value = raw.trim();
        if (value.length() > 24) {
            throw new IllegalArgumentException(
                    prop + " must be <= 24 chars, got " + value.length() + ": '" + value + "'");
        }
        // The shared family law (C0/DEL + C1/NEL/LS/PS/bidi) — the same predicate the
        // seat-name fan applies at encode, so a knob value fails HERE, loud, never at the first SNAM.
        int bad = cc.starsector.server.runtime.WireTextLaw.firstUnprintableIndex(value);
        if (bad >= 0) {
            throw new IllegalArgumentException(
                    prop + " must be single-line printable (control char at index " + bad + ")");
        }
        System.out.println("real-engine-demo-roster seat-name: seat " + seat + " ship <- '" + value
                + "' (" + prop + ")");
        return value;
    }

    /** Descriptor deployCr (0..1 float) as the wire's clamped per-mille int. */
    static int crPerMille(float deployCr) {
        return Math.max(0, Math.min(1000, Math.round(deployCr * 1000f)));
    }
}
