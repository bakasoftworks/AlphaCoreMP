package cc.starsector.server.match;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.CRC32;

/**
 * W4 all-skills per-side fleet-context — the SHARED half: the pure
 * walk/echo/shape law both jars carry. Split out of {@link AllSkillsFleetData}: that
 * class is java.lang.reflect throughout, and classloader B DENIES java.lang.reflect in the mod
 * jar (the B-deny gate measured 6 denied refs), so the client cannot ride the
 * reflective mint. The law that must be BYTE-IDENTICAL across ends lives here (JDK +
 * {@link MatchDescriptor} only, client build.ps1 allowlist); each end mints with its own
 * legal vehicle — server = reflective {@link AllSkillsFleetData}, client = the typed twin in
 * the client tree — and the {@code fleet_ctx} echo EQUALITY assert at watch grading is the
 * anti-drift guard replacing "one mint class both jars".
 */
public final class AllSkillsFleetLaw {

    private AllSkillsFleetLaw() {
    }

    /** Per-end station read for the walk law ({@code ShipVariantAPI.isStation()} both ends). */
    public interface StationCheck {
        boolean isStation(String variantId) throws Exception;
    }

    /** The minted context: the fab (as {@code FleetDataAPI}), its gate-2 token, and the echo. */
    public static final class Mint {
        /** The real engine {@code FleetData} holding the shadow roster (a {@code FleetDataAPI}). */
        public final Object fleetData;
        /** The fail-closed {@code CampaignFleetAPI} gate-2 token installed on the union stats. */
        public final Object fleetToken;
        /**
         * Canonical parity line body — IDENTICAL bytes on both ends for the same descriptor
         * (the watch grading assert lane; each end prefixes its own sidelog tag).
         */
        public final String echo;

        public Mint(Object fleetData, Object fleetToken, String echo) {
            this.fleetData = fleetData;
            this.fleetToken = fleetToken;
            this.echo = echo;
        }
    }

    /**
     * The SIDE-0 form of {@link #sideShipVariantIds} kept for callers that still need the
     * fail-closed shape: a null/empty roster or a walk that yields no ship THROWS here, where the
     * per-side walk answers an empty list (a side without document rows is lawful, it simply
     * gets no fleet data).
     */
    public static List<String> sideZeroShipVariantIds(List<MatchDescriptor.RosterRow> roster,
            StationCheck check) {
        if (roster == null || roster.isEmpty()) {
            throw new IllegalStateException("all-skills fleet-context: null/empty roster —"
                    + " a descriptor battle always carries roster rows");
        }
        List<String> ids = sideShipVariantIds(roster, 0, check);
        if (ids.isEmpty()) {
            throw new IllegalStateException("all-skills fleet-context: the side-0 walk produced"
                    + " ZERO ship rows — an empty fleet-data roster silently grants every"
                    + " threshold skill at maximum");
        }
        return ids;
    }

    /**
     * THE per-side roster walk law (one law, both ends): every roster row of absolute side
     * {@code side} (0 or 1) whose variant is not a STATION ({@code ShipVariantAPI.isStation()} —
     * the same engine hint both deploy lanes already key their station carve-outs on; this walk
     * skips stations, but the throttle's fleet key does not: {@link #key} counts a station's
     * fleet like any other, as the base game does, so on a side that holds a station the key
     * has more members than this list),
     * expanded {@code count} times, in declaration order. Seat and AI rows alike — the fleet is
     * the FULL roster of that side exactly like a campaign fleet counts reserves, not just
     * deployed hulls. A null roster or a side with no ship rows answers an EMPTY list, never a
     * throw: that side has no document roster (a wave-fed side) and its members keep a pair with
     * no fleet data. A station-check fault still THROWS naming the variant.
     *
     */
    public static List<String> sideShipVariantIds(List<MatchDescriptor.RosterRow> roster,
            int side, StationCheck check) {
        List<String> ids = new ArrayList<>();
        if (roster == null) {
            return ids;
        }
        for (MatchDescriptor.RosterRow row : roster) {
            if (row.side() != side) {
                continue;
            }
            boolean station;
            try {
                station = check.isStation(row.variantId());
            } catch (Exception e) {
                throw new IllegalStateException("all-skills fleet-context: station check failed"
                        + " for variant '" + row.variantId() + "' — fail-closed, a misclassified"
                        + " row skews the throttle sums: " + e, e);
            }
            if (station) {
                continue;
            }
            for (int i = 0; i < row.count(); i++) {
                ids.add(row.variantId());
            }
        }
        return ids;
    }

    /** The side-0 form of {@link #echoLine(int, int, float, float, float, float, float, float)}. */
    public static String echoLine(int members, float phaseDp, float phaseThr, float pcSpeed,
            float pcPeak, float combatDp, float bays) {
        return echoLine(0, members, phaseDp, phaseThr, pcSpeed, pcPeak, combatDp, bays);
    }

    /**
     * Pure echo formatting, one line per MINT of a side's fleet context (contract-row pinned —
     * grading greps these exact keys; {@code side} = the absolute side the fab was minted for,
     * always the first field after the tag).
     *
     */
    public static String echoLine(int side, int members, float phaseDp, float phaseThr,
            float pcSpeed, float pcPeak, float combatDp, float bays) {
        return "fleet_ctx side=" + side + " members=" + members
                + " phase_dp=" + Math.round(phaseDp)
                + " pc_thr=" + Math.round(phaseThr)
                + " pc_speed=" + Math.round(pcSpeed)
                + " pc_peak=" + Math.round(pcPeak)
                + " combat_dp=" + Math.round(combatDp)
                + " bays=" + Math.round(bays);
    }

    /**
     * The row a side with no document roster prints once (per change of its roster): its
     * members keep the commander pair with no fleet data, so every threshold skill there reads
     * its maximum — said out loud, never silent.
     *
     */
    public static String noDocumentRosterLine(int side) {
        return "fleet_ctx side=" + side + " fab=none reason=no_document_roster";
    }

    /**
     * The per-ship proof line, the SAME text on both ends: read from the created SHIP, never
     * from the fab. {@code id} = the ship's id token as the caller names it; {@code side} = its
     * absolute side; {@code fab} = the ship's member carries fleet data; {@code captDefault} =
     * that fleet data's commander IS the ship's captain (the bare-captain swap hazard's probe);
     * {@code pc} = the phase-cloak speed mod's effective value over a zero base; {@code fdp} =
     * the sum of the percent modifiers on flux dissipation. {@link Locale#ROOT}: a comma-decimal
     * default locale must not change the bytes.
     *
     */
    public static String formatShipEcho(String id, int side, boolean fab, boolean captDefault,
            float pc, float fdp) {
        return String.format(Locale.ROOT,
                "fleet_ctx_ship id=%s side=%d fab=%d capt_default=%d pc=%.2f fdp=%.2f",
                id, side, fab ? 1 : 0, captDefault ? 1 : 0, pc, fdp);
    }

    // ------------------------------------------------------------------ the fleet key
    //
    // Which hulls a side's fleet context counts, as ONE law both ends run. The engine's
    // commander-skill thresholds read the fleet's size, so a seat hull and a round-open AI hull
    // must be minted against the SAME member list on the server and on every client, or the
    // throttled stats diverge with nothing on the wire to show it. The record says which hulls
    // were FIELDED this round (one entry per wire id, the last fielding wins); the key turns the
    // document plus the record into an ordered entry list, a canonical text and a 32-bit digest;
    // the client-side state below replays the record from the server's flagged roster rows and
    // decides, per hull, which key it is minted under.

    /**
     * The longest goal id (UTF-16 chars) a record holds. Variant ids are short words; a longer id
     * is refused on both ends so a hostile row cannot grow the canonical text (the server's write
     * throws; the client refuses the row and counts it).
     */
    public static final int MAX_GOAL_CHARS = 64;

    /**
     * The client's bound on the record entries one side holds per round epoch: a wire id that
     * would become a side's 257th entry is refused {@code writes_bound} and counted; an entry
     * that moves to the other side frees its slot on the side it left, and a removal
     * ({@link FleetFold#foldVacated}) frees it outright. The same number bounds the wire ids a
     * side's ROUND-OPEN record holds, which no removal shrinks (the round-open key is the fleet
     * as it stood at round open): a round-open row that would add a side's 257th open wire id
     * is refused {@code writes_bound} too, so a side that never closes cannot grow its open
     * record by pairing round-open rows with removals. A lawful round-open set is at most 255
     * seats a side. These two keep the canonical text and every key build of the fold small;
     * no count of writes stands behind them.
     *
     */
    public static final int MAX_ENTRIES_PER_SIDE = 256;

    /** Key entry prefix of a hull the engine's AI flies (a document row or a non-seat write). */
    public static final String ENTRY_AI = "A:";

    /** Key entry prefix of a record write whose wire id is a document seat ship id. */
    public static final String ENTRY_SEAT = "S:";

    private static final Comparator<RecordEntry> ASCENDING_WIRE_ID =
            Comparator.comparingLong(RecordEntry::wireId);

    /**
     * Why {@code goalId} cannot enter a record, or null when it can: null, empty, longer than
     * {@link #MAX_GOAL_CHARS}, or holding a line break (the canonical text joins entries with one
     * line break, so an entry holding one could make two different keys share a text). The answer
     * names the length only, never the text: on the client a goal id arrives from the wire.
     *
     */
    public static String goalRefusalOrNull(String goalId) {
        if (goalId == null) {
            return "goal id is null";
        }
        int length = goalId.length();
        if (length == 0 || length > MAX_GOAL_CHARS) {
            return "goal id length " + length + " is outside 1.." + MAX_GOAL_CHARS;
        }
        if (goalId.indexOf('\n') >= 0) {
            return "goal id of length " + length + " holds a line break";
        }
        return null;
    }

    /**
     * Eight lowercase hex digits of a key digest, zero-padded, the digest read as unsigned (a
     * negative int prints its two's-complement bits; 0 prints eight zeros and is lawful).
     *
     */
    public static String hex8(int digest) {
        String hex = Integer.toHexString(digest);
        return "00000000".substring(hex.length()) + hex;
    }

    /**
     * {@link #echoLine(int, int, float, float, float, float, float, float)} with the key tail
     * {@code " key=<hex8>"}: {@code keyDigest} = {@link FleetKey#digest()} of the key the context
     * was minted under. The grader joins both ends' mint rows on (side, key).
     *
     */
    public static String echoLine(int side, int members, float phaseDp, float phaseThr,
            float pcSpeed, float pcPeak, float combatDp, float bays, int keyDigest) {
        return echoLine(side, members, phaseDp, phaseThr, pcSpeed, pcPeak, combatDp, bays)
                + " key=" + hex8(keyDigest);
    }

    /**
     * {@link #formatShipEcho(String, int, boolean, boolean, float, float)} with the key tail
     * {@code " key=<hex8>"}: {@code keyDigest} = the digest of the key the SHIP's context was
     * minted under (the body carries it), so a per-ship row joins its mint row on both ends.
     *
     */
    public static String formatShipEcho(String id, int side, boolean fab, boolean captDefault,
            float pc, float fdp, int keyDigest) {
        return formatShipEcho(id, side, fab, captDefault, pc, fdp) + " key=" + hex8(keyDigest);
    }

    /**
     * THE KEY of one side: the side's NON-SEAT document rows ({@code pilot != SEAT}) expanded
     * {@code count} times in declaration order, each {@code A:<variantId>}, STATIONS INCLUDED (no
     * station check: the base game counts a station's fleet like any other); then the record's
     * entries of that side by ASCENDING wire id, each {@code S:<goalId>} when the wire id is in
     * {@code seatShipIds}, else {@code A:<goalId>}. A document SEAT row never enters directly: a
     * seat counts only once a fielding wrote it, so a vacant seat is absent. {@code roster} null =
     * no document rows (a wave-fed side); {@code side} 0 or 1; {@code seatShipIds} and
     * {@code record} non-null (take the set from {@link DocumentIds} so both ends agree).
     *
     */
    public static FleetKey key(List<MatchDescriptor.RosterRow> roster, Set<Long> seatShipIds,
            int side, FleetRecord record) {
        requireSide(side);
        if (seatShipIds == null || record == null) {
            throw new IllegalArgumentException("fleet key: the seat-ship-id set and the record"
                    + " must not be null");
        }
        List<String> entries = new ArrayList<>();
        if (roster != null) {
            for (MatchDescriptor.RosterRow row : roster) {
                if (row.side() != side || row.pilot() == MatchDescriptor.PilotKind.SEAT) {
                    continue;
                }
                for (int i = 0; i < row.count(); i++) {
                    entries.add(ENTRY_AI + row.variantId());
                }
            }
        }
        for (RecordEntry entry : record.entriesOfSide(side)) {
            boolean seat = seatShipIds.contains(Long.valueOf(entry.wireId()));
            entries.add((seat ? ENTRY_SEAT : ENTRY_AI) + entry.goalId());
        }
        return new FleetKey(entries);
    }

    private static void requireSide(int side) {
        if (side != 0 && side != 1) {
            throw new IllegalArgumentException("fleet side must be 0 or 1, got: " + side);
        }
    }

    private static void requireWireId(long wireId) {
        if (wireId < 1) {
            throw new IllegalArgumentException("fleet wire id must be >= 1, got: " + wireId);
        }
    }

    /**
     * CRC32 over the canonical text's UTF-8 bytes, as an int. The charset is passed explicitly:
     * the JVM default differs between hosts, and the digest must not.
     */
    private static int digestOf(String canonicalText) {
        CRC32 crc = new CRC32();
        crc.update(canonicalText.getBytes(StandardCharsets.UTF_8));
        return (int) crc.getValue();
    }

    /**
     * One record entry, immutable: the wire id ({@code >= 1}), the absolute side it was fielded
     * on (0 or 1) and its goal id (the PICK's variant id; lawful per
     * {@link #goalRefusalOrNull}). {@code toString} prints bounded tokens only, never the goal.
     *
     */
    public static final class RecordEntry {
        private final long wireId;
        private final int side;
        private final String goalId;

        private RecordEntry(long wireId, int side, String goalId) {
            this.wireId = wireId;
            this.side = side;
            this.goalId = goalId;
        }

        public long wireId() {
            return wireId;
        }

        public int side() {
            return side;
        }

        public String goalId() {
            return goalId;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof RecordEntry)) {
                return false;
            }
            RecordEntry that = (RecordEntry) other;
            return wireId == that.wireId && side == that.side && goalId.equals(that.goalId);
        }

        @Override
        public int hashCode() {
            return (Long.hashCode(wireId) * 31 + side) * 31 + goalId.hashCode();
        }

        @Override
        public String toString() {
            return "record_entry id=" + wireId + " side=" + side + " goal_len=" + goalId.length();
        }
    }

    /**
     * THE RECORD of one round: {@code wireId -> (absolute side, goalId)}, at most one entry per
     * wire id (a re-write replaces, so seats cannot inflate it). Mutable, NOT thread-safe, owned
     * by its caller; the caller clears it when the round resets. {@link #write} hands back the
     * entry it replaced so a caller whose fielding throws after the write can {@link #restore} it
     * (a record outlives a failed serve inside one JVM).
     *
     */
    public static final class FleetRecord {
        /** Write order (latest write last); the key re-sorts by wire id, so order is diagnostic. */
        private final Map<Long, RecordEntry> byWireId = new LinkedHashMap<>();

        /**
         * Writes {@code wireId -> (side, goalId)} and returns the entry it replaced, or null when
         * the wire id was new. THROWS {@link IllegalArgumentException} on a wire id below 1, a
         * side other than 0 / 1, or a goal {@link #goalRefusalOrNull} refuses (the message names
         * the length only); the record is unchanged then.
         */
        public RecordEntry write(long wireId, int side, String goalId) {
            requireWireId(wireId);
            requireSide(side);
            String refusal = goalRefusalOrNull(goalId);
            if (refusal != null) {
                throw new IllegalArgumentException("fleet record: " + refusal);
            }
            Long key = Long.valueOf(wireId);
            RecordEntry previous = byWireId.remove(key);
            byWireId.put(key, new RecordEntry(wireId, side, goalId));
            return previous;
        }

        /**
         * Undoes one {@link #write}: {@code previousOrNull} = what that write returned (null =
         * the wire id was new, so its entry is removed). An entry of another wire id throws.
         */
        public void restore(long wireId, RecordEntry previousOrNull) {
            requireWireId(wireId);
            Long key = Long.valueOf(wireId);
            if (previousOrNull == null) {
                byWireId.remove(key);
                return;
            }
            if (previousOrNull.wireId() != wireId) {
                throw new IllegalArgumentException("fleet record: restore of wire id " + wireId
                        + " was handed the entry of wire id " + previousOrNull.wireId());
            }
            byWireId.remove(key);
            byWireId.put(key, previousOrNull);
        }

        /**
         * Takes the entry of {@code wireId} out of the record and returns it, or null when the
         * record holds none (unchanged then). THROWS {@link IllegalArgumentException} on a wire id
         * below 1, as {@link #write} does. An entry leaves when its hull is neither seated nor in
         * the world; a ROUND-OPEN record is never removed from (it is the fleet as it stood at
         * round open).
         *
         */
        public RecordEntry remove(long wireId) {
            requireWireId(wireId);
            return byWireId.remove(Long.valueOf(wireId));
        }

        public void clear() {
            byWireId.clear();
        }

        public RecordEntry entryOrNull(long wireId) {
            return byWireId.get(Long.valueOf(wireId));
        }

        public int size() {
            return byWireId.size();
        }

        /** The entries of {@code side} (0 or 1) by ASCENDING wire id: the key's record order. */
        public List<RecordEntry> entriesOfSide(int side) {
            requireSide(side);
            List<RecordEntry> ofSide = new ArrayList<>();
            for (RecordEntry entry : byWireId.values()) {
                if (entry.side() == side) {
                    ofSide.add(entry);
                }
            }
            ofSide.sort(ASCENDING_WIRE_ID);
            return Collections.unmodifiableList(ofSide);
        }
    }

    /**
     * THE KEY value, immutable: the ordered entry list (each {@code A:<id>} or {@code S:<id>}),
     * the canonical text (entries joined by one line break; the empty key's text is empty) and
     * the digest (CRC32 of the text's UTF-8 as an int; 0 is LAWFUL, never "absent").
     * {@code equals} / {@code hashCode} ride the canonical TEXT, never the digest alone: a 32-bit
     * digest can collide and cannot be re-minted after a cache miss, the entry list can.
     * {@code toString} prints bounded tokens only (count + hex digest).
     *
     */
    public static final class FleetKey {
        private final List<String> entries;
        private final String canonicalText;
        private final int digest;

        private FleetKey(List<String> entries) {
            List<String> copy = new ArrayList<>(entries.size());
            for (String entry : entries) {
                if (entry == null || entry.length() <= ENTRY_AI.length()
                        || !(entry.startsWith(ENTRY_AI) || entry.startsWith(ENTRY_SEAT))) {
                    throw new IllegalArgumentException("fleet key: entry " + copy.size()
                            + " is not A:<id> or S:<id>");
                }
                if (entry.indexOf('\n') >= 0) {
                    throw new IllegalArgumentException("fleet key: entry " + copy.size()
                            + " holds a line break");
                }
                copy.add(entry);
            }
            this.entries = Collections.unmodifiableList(copy);
            this.canonicalText = String.join("\n", copy);
            this.digest = digestOf(canonicalText);
        }

        /**
         * A key from an entry list already in key order (each {@code A:<id>} or {@code S:<id>},
         * no line break; THROWS otherwise). {@link AllSkillsFleetLaw#key} is the law that BUILDS
         * one; this rebuilds one a caller kept as entries.
         */
        public static FleetKey of(List<String> entries) {
            if (entries == null) {
                throw new IllegalArgumentException("fleet key: null entry list");
            }
            return new FleetKey(entries);
        }

        public List<String> entries() {
            return entries;
        }

        public String canonicalText() {
            return canonicalText;
        }

        public int digest() {
            return digest;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof FleetKey
                    && canonicalText.equals(((FleetKey) other).canonicalText);
        }

        @Override
        public int hashCode() {
            return canonicalText.hashCode();
        }

        @Override
        public String toString() {
            return "fleet_key n=" + entries.size() + " key=" + hex8(digest);
        }
    }

    /**
     * The document's id facts both ends derive the SAME way: the dense wire-id contract (ids
     * 1..N in roster order x count, every row counted, SEAT rows like any other), the per-id
     * document side, and the SEAT-SHIP-ID set = every id a SEAT row covers in that walk plus
     * every document seat's {@code shipId} (the two agree on a well-formed document; the union
     * labels a seat hull {@code S:} even on a hand-made one). Immutable.
     *
     */
    public static final class DocumentIds {
        private final List<MatchDescriptor.RosterRow> roster;
        private final Set<Long> seatShipIds;
        private final Map<Long, Integer> sideById;

        private DocumentIds(List<MatchDescriptor.RosterRow> roster,
                List<MatchDescriptor.Seat> seats) {
            List<MatchDescriptor.RosterRow> rows = roster == null
                    ? Collections.<MatchDescriptor.RosterRow>emptyList() : roster;
            TreeSet<Long> seatIds = new TreeSet<>();
            TreeMap<Long, Integer> sides = new TreeMap<>();
            long id = 0L;
            for (MatchDescriptor.RosterRow row : rows) {
                for (int i = 0; i < row.count(); i++) {
                    id++;
                    sides.put(Long.valueOf(id), Integer.valueOf(row.side()));
                    if (row.pilot() == MatchDescriptor.PilotKind.SEAT) {
                        seatIds.add(Long.valueOf(id));
                    }
                }
            }
            if (seats != null) {
                for (MatchDescriptor.Seat seat : seats) {
                    seatIds.add(Long.valueOf(seat.shipId()));
                }
            }
            this.roster = Collections.unmodifiableList(new ArrayList<>(rows));
            this.seatShipIds = Collections.unmodifiableSet(seatIds);
            this.sideById = Collections.unmodifiableMap(sides);
        }

        /** The facts of {@code descriptor}; null = no document (no rows, no seats). */
        public static DocumentIds of(MatchDescriptor descriptor) {
            return descriptor == null ? new DocumentIds(null, null)
                    : new DocumentIds(descriptor.roster(), descriptor.seats());
        }

        /** The facts of a bare roster (no seat list: the SEAT rows alone name the seat ids). */
        public static DocumentIds ofRoster(List<MatchDescriptor.RosterRow> roster) {
            return new DocumentIds(roster, null);
        }

        public List<MatchDescriptor.RosterRow> roster() {
            return roster;
        }

        /** Ascending, unmodifiable; every member {@code >= 1}. */
        public Set<Long> seatShipIds() {
            return seatShipIds;
        }

        /** The dense id count N ({@code >= 0}): wire ids 1..N name document rows. */
        public long idCount() {
            return sideById.size();
        }

        /** The document side (0 or 1) of the row {@code wireId} names, or -1 when it names none. */
        public int documentSideOrMinusOne(long wireId) {
            Integer side = sideById.get(Long.valueOf(wireId));
            return side == null ? -1 : side.intValue();
        }

        /** {@link AllSkillsFleetLaw#key} over this document. */
        public FleetKey key(int side, FleetRecord record) {
            return AllSkillsFleetLaw.key(roster, seatShipIds, side, record);
        }
    }

    /**
     * What a hull's fleet context is minted under. {@code OPEN(side)} = a symbol, "the round-open
     * key of this side, this epoch", resolved when read (so every round-open hull sees the WHOLE
     * round-open set however its rows arrived); {@code KEY(key)} = the side's key as folded
     * through one mid-round row, kept as its entry list. A stamp belongs to one epoch: a fold of
     * another epoch resolves it to null.
     *
     */
    public static final class Stamp {
        /** The two stamp kinds. */
        public enum Kind {
            OPEN,
            KEY
        }

        private final Kind kind;
        private final long epoch;
        private final int side;
        private final FleetKey keyOrNull;

        private Stamp(Kind kind, long epoch, int side, FleetKey keyOrNull) {
            this.kind = kind;
            this.epoch = epoch;
            this.side = side;
            this.keyOrNull = keyOrNull;
        }

        public Kind kind() {
            return kind;
        }

        public long epoch() {
            return epoch;
        }

        /** The absolute side (0 or 1) of the row that stamped it. */
        public int side() {
            return side;
        }

        /** The key of a {@code KEY} stamp; null for {@code OPEN}. */
        public FleetKey keyOrNull() {
            return keyOrNull;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Stamp)) {
                return false;
            }
            Stamp that = (Stamp) other;
            return kind == that.kind && epoch == that.epoch && side == that.side
                    && (keyOrNull == null ? that.keyOrNull == null : keyOrNull.equals(that.keyOrNull));
        }

        @Override
        public int hashCode() {
            return ((kind.hashCode() * 31 + Long.hashCode(epoch)) * 31 + side) * 31
                    + (keyOrNull == null ? 0 : keyOrNull.hashCode());
        }

        @Override
        public String toString() {
            return kind == Kind.OPEN ? "OPEN(" + side + ")" : "KEY(" + side + "," + keyOrNull + ")";
        }
    }

    /** Why a fielded row was or was not taken; {@link #token()} is the counter / row word. */
    public enum FoldOutcome {
        ACCEPTED("accepted"),
        WRITES_BOUND("writes_bound"),
        CLOSED_SIDE("closed_side"),
        BAD_GOAL("bad_goal");

        private final String token;

        FoldOutcome(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }
    }

    /**
     * The per-row digest check: {@code MATCH} / {@code MISMATCH} for an accepted mid-round row
     * and for a taken removal row; {@code NOT_CHECKED} for a round-open row (its carried digest is the WHOLE round-open key,
     * so a per-row compare against the set folded so far would false-mismatch every row but the
     * last) and for a refused row.
     */
    public enum DigestCheck {
        MATCH,
        MISMATCH,
        NOT_CHECKED
    }

    /**
     * One {@link FleetFold#foldFielded} answer, immutable: the outcome; the stamp the row's hull
     * holds NOW (the new stamp when accepted; on a refusal the stamp it already held, null when
     * none); the digest check; whether this row closed its side.
     *
     */
    public static final class FoldResult {
        private final FoldOutcome outcome;
        private final Stamp stampOrNull;
        private final DigestCheck check;
        private final boolean closedSide;

        private FoldResult(FoldOutcome outcome, Stamp stampOrNull, DigestCheck check,
                boolean closedSide) {
            this.outcome = outcome;
            this.stampOrNull = stampOrNull;
            this.check = check;
            this.closedSide = closedSide;
        }

        public FoldOutcome outcome() {
            return outcome;
        }

        public boolean accepted() {
            return outcome == FoldOutcome.ACCEPTED;
        }

        public Stamp stampOrNull() {
            return stampOrNull;
        }

        public DigestCheck check() {
            return check;
        }

        /** True only on the round-open row whose fold made the side's open key match. */
        public boolean closedSide() {
            return closedSide;
        }
    }

    /**
     * The open-key diagnostic of an UNCLOSED side: the digest folded so far ({@code have}) and
     * the digest the side's round-open rows carry ({@code want}). Immutable.
     *
     */
    public static final class OpenMismatch {
        private final int have;
        private final int want;

        private OpenMismatch(int have, int want) {
            this.have = have;
            this.want = want;
        }

        public int have() {
            return have;
        }

        public int want() {
            return want;
        }
    }

    /** Why a removal row was or was not taken; {@link #token()} is the counter / row word. */
    public enum VacateOutcome {
        VACATED("vacated"),
        VACATE_ABSENT("vacate_absent");

        private final String token;

        VacateOutcome(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }
    }

    /**
     * One {@link FleetFold#foldVacated} answer, immutable: the outcome; the side's key AFTER the
     * removal (null when refused), so a MISMATCH row can name what this fold holds once the
     * removed wire id's stamp is gone; the check of that key against the carried digest
     * ({@code NOT_CHECKED} when refused).
     *
     */
    public static final class VacateResult {
        private final VacateOutcome outcome;
        private final FleetKey keyOrNull;
        private final DigestCheck check;

        private VacateResult(VacateOutcome outcome, FleetKey keyOrNull, DigestCheck check) {
            this.outcome = outcome;
            this.keyOrNull = keyOrNull;
            this.check = check;
        }

        public VacateOutcome outcome() {
            return outcome;
        }

        public boolean removed() {
            return outcome == VacateOutcome.VACATED;
        }

        /** The side's key after the removal; null for {@code vacate_absent}. */
        public FleetKey keyOrNull() {
            return keyOrNull;
        }

        public DigestCheck check() {
            return check;
        }
    }

    /**
     * THE CLIENT'S FOLD STATE of one round epoch: the server's record replayed from the flagged
     * roster rows in log order, the per-hull stamps, the round-open key of each side and its
     * close. Pure: no clock, no logging, no engine type; NOT thread-safe; one per epoch, owned by
     * its caller (a new epoch = a new fold, so every stamp of the old one resolves to null).
     * <ul>
     * <li>The record takes EVERY accepted row (round-open and mid-round): a mid-round row's
     * stamp is {@code KEY} = the side's key folded through that row, the key the server read for
     * that hull.</li>
     * <li>The open record takes only round-open rows: {@code OPEN(side)} resolves to the
     * document's non-seat rows + every round-open row of that side folded so far (a set, ordered
     * by wire id, so arrival order cannot matter).</li>
     * <li>THE CLOSE: a side is closed the moment its folded open-key digest equals the digest its
     * round-open rows carry; from then its open key is frozen and a further round-open row for it
     * is refused {@code closed_side} (a hostile host cannot keep re-stamping a side).</li>
     * <li>Bounds: {@link AllSkillsFleetLaw#MAX_ENTRIES_PER_SIDE} entries a side in the record
     * and as many wire ids a side in the open record ({@link #foldFielded}); a goal
     * {@link AllSkillsFleetLaw#goalRefusalOrNull} refuses is {@code bad_goal}. Every refusal is
     * counted and changes nothing but the refused mark ({@link #refusedMarked}). The CHANGING
     * writes a side takes are a meter ({@link #changingWrites}), never a refusal.</li>
     * <li>A REMOVAL ({@link #foldVacated}) takes an entry out of the record only: the open
     * record and a frozen open key never change for one.</li>
     * </ul>
     *
     */
    public static final class FleetFold {
        private final long epoch;
        private final DocumentIds document;
        private final FleetRecord record = new FleetRecord();
        private final FleetRecord openRecord = new FleetRecord();
        /**
         * Bounded: only an accepted row stamps, every stamped wire id is a record entry and a
         * removal drops its stamp, so the stamped wire ids stay a subset of the record's: at most
         * 2 x MAX_ENTRIES_PER_SIDE.
         */
        private final Map<Long, Stamp> stamps = new HashMap<>();
        /**
         * The refused marks ({@link #refusedMarked}). Bounded like the stamps: a wire id is
         * marked only while the record holds it, and a removal drops its mark.
         */
        private final Set<Long> refusedMarks = new HashSet<>();
        private final FleetKey[] frozenOpenKey = new FleetKey[2];
        private final int[] openWant = new int[2];
        private final long[] openRows = new long[2];
        /** A meter: counted on every accepted changing write, never read as a gate. */
        private final long[] changingWrites = new long[2];
        /** The record entries each side holds: kept beside the record so a bound check is O(1). */
        private final int[] entries = new int[2];
        /** The open record's wire ids each side holds: its own bound, as no removal shrinks it. */
        private final int[] openEntries = new int[2];
        private long refusedWritesBound;
        private long refusedClosedSide;
        private long refusedBadGoal;
        private long refusedVacateAbsent;
        private long mismatches;

        /**
         * {@code epoch} = the caller's round-epoch count (any long; stamps compare it for
         * equality only); {@code document} non-null ({@code DocumentIds.of(null)} is the empty
         * one).
         */
        public FleetFold(long epoch, DocumentIds document) {
            if (document == null) {
                throw new IllegalArgumentException("fleet fold: null document ids");
            }
            this.epoch = epoch;
            this.document = document;
        }

        public long epoch() {
            return epoch;
        }

        /**
         * Folds one FIELDED row ({@code wireId >= 1}, absolute {@code side} 0 / 1, the row's goal
         * id, whether it belongs to the round-open set, the key digest it carries; any int,
         * 0 included). A wire id below 1 or a side outside 0 / 1 THROWS
         * {@link IllegalArgumentException}: those are the caller's contract (validate the decoded
         * row first), never a refusal reason. Every other input answers a result, never a throw:
         * a refusal is counted and changes nothing else but the refused mark.
         * <p>An EQUAL re-write (this fold's record already holds (side, goal) for the wire id; a
         * round-open row is judged the same way) is accepted, stamped and digest-checked like any
         * row. A CHANGING write (a new wire id, a changed side or a changed goal) is counted on
         * the row side's {@link #changingWrites} meter and is never refused for that number: a
         * lifetime count refused only honest clients, late, and a rate bound would refuse a whole
         * side at once. The fold is bounded by its SIZE instead. A write that adds an entry to a
         * side (a new wire id, or one moving in from the other side) is refused while the side
         * holds {@link AllSkillsFleetLaw#MAX_ENTRIES_PER_SIDE}, and a move frees its slot on the
         * side it left. A round-open row that adds a wire id to the side's OPEN record is refused
         * while that holds as many: a removal shrinks the record but never the open record, so
         * without its own bound the open record of a side that never closes would grow with every
         * round-open row and removal pair. Both answer {@code writes_bound}, and neither refusal
         * is silent: a refused mid-round write leaves this fold's key short of the
         * server's, so the next CHECKED row of that side, an equal re-write included, reads
         * MISMATCH unless the server's writes since have brought its key back to this fold's; a
         * refused round-open row leaves the side's open key short of the digest its round-open
         * rows carry, so the side stays unclosed and {@link #openMismatch} answers.
         * <p>Any refused row ({@code writes_bound}, {@code closed_side}, {@code bad_goal}) of a
         * wire id the record holds MARKS that wire id ({@link #refusedMarked}): its hull keeps a
         * stamp the server's row superseded. The wire id's next accepted row clears the mark.
         *
         *
         *
         */
        public FoldResult foldFielded(long wireId, int side, String goalId, boolean roundOpen,
                int carriedDigest) {
            requireWireId(wireId);
            requireSide(side);
            Long id = Long.valueOf(wireId);
            Stamp held = stamps.get(id);
            if (goalRefusalOrNull(goalId) != null) {
                refusedBadGoal++;
                return refused(FoldOutcome.BAD_GOAL, id, held);
            }
            RecordEntry prior = record.entryOrNull(wireId);
            boolean equal = prior != null && prior.side() == side && prior.goalId().equals(goalId);
            boolean entersSide = prior == null || prior.side() != side;
            if (entersSide && entries[side] >= MAX_ENTRIES_PER_SIDE) {
                refusedWritesBound++;
                return refused(FoldOutcome.WRITES_BOUND, id, held);
            }
            if (roundOpen && isClosed(side)) {
                refusedClosedSide++;
                return refused(FoldOutcome.CLOSED_SIDE, id, held);
            }
            RecordEntry openPrior = roundOpen ? openRecord.entryOrNull(wireId) : null;
            boolean entersOpenSide = roundOpen && (openPrior == null || openPrior.side() != side);
            if (entersOpenSide && openEntries[side] >= MAX_ENTRIES_PER_SIDE) {
                refusedWritesBound++;
                return refused(FoldOutcome.WRITES_BOUND, id, held);
            }
            if (!equal) {
                changingWrites[side]++;
            }
            if (entersSide) {
                entries[side]++;
                if (prior != null) {
                    entries[prior.side()]--;
                }
            }
            record.write(wireId, side, goalId);
            refusedMarks.remove(id);
            boolean closedNow = false;
            FleetKey through;
            Stamp stamp;
            if (roundOpen) {
                if (entersOpenSide) {
                    openEntries[side]++;
                    if (openPrior != null) {
                        openEntries[openPrior.side()]--;
                    }
                }
                openRecord.write(wireId, side, goalId);
                openRows[side]++;
                openWant[side] = carriedDigest;
                through = openKey(side);
                if (through.digest() == carriedDigest) {
                    frozenOpenKey[side] = through;
                    closedNow = true;
                }
                stamp = new Stamp(Stamp.Kind.OPEN, epoch, side, null);
            } else {
                through = document.key(side, record);
                stamp = new Stamp(Stamp.Kind.KEY, epoch, side, through);
            }
            DigestCheck check = roundOpen ? DigestCheck.NOT_CHECKED : check(through, carriedDigest);
            stamps.put(id, stamp);
            return new FoldResult(FoldOutcome.ACCEPTED, stamp, check, closedNow);
        }

        private DigestCheck check(FleetKey through, int carriedDigest) {
            if (through.digest() == carriedDigest) {
                return DigestCheck.MATCH;
            }
            mismatches++;
            return DigestCheck.MISMATCH;
        }

        /** A refusal's answer: the stamp the hull already holds; a held wire id is marked. */
        private FoldResult refused(FoldOutcome outcome, Long id, Stamp held) {
            if (record.entryOrNull(id.longValue()) != null) {
                refusedMarks.add(id);
            }
            return new FoldResult(outcome, held, DigestCheck.NOT_CHECKED, false);
        }

        /**
         * Folds one REMOVAL row: the hull of {@code wireId} is neither seated nor in the world, so
         * its entry leaves the record. A wire id below 1 or a side outside 0 / 1 THROWS
         * {@link IllegalArgumentException} (the caller's contract, as in {@link #foldFielded});
         * the carried digest is any int, 0 included. When this fold's record holds the wire id on
         * {@code side}: the entry is removed, the side's entry count falls by one, the wire id's
         * stamp and refused mark are dropped (no other wire id's stamp moves), and the side's key
         * AFTER the removal is checked against the carried digest, a MISMATCH counted on
         * {@link #mismatches} as a mid-round row's is; the answer carries that key. Otherwise (no
         * entry, or one on the other side) the row is refused {@code vacate_absent}, counted, and
         * the fold is unchanged: a lawful serve sends no such row unless an intake refusal
         * (counted) dropped the row that wrote the entry.
         * <p>The OPEN record and a frozen open key are never touched: the round-open key is the
         * fleet as it stood at round open, on the server and on every client alike.
         *
         */
        public VacateResult foldVacated(long wireId, int side, int carriedDigest) {
            requireWireId(wireId);
            requireSide(side);
            RecordEntry held = record.entryOrNull(wireId);
            if (held == null || held.side() != side) {
                refusedVacateAbsent++;
                return new VacateResult(VacateOutcome.VACATE_ABSENT, null, DigestCheck.NOT_CHECKED);
            }
            Long id = Long.valueOf(wireId);
            record.remove(wireId);
            entries[side]--;
            stamps.remove(id);
            refusedMarks.remove(id);
            FleetKey after = document.key(side, record);
            return new VacateResult(VacateOutcome.VACATED, after, check(after, carriedDigest));
        }

        /**
         * Marks {@code wireId} as a refused row would, for a row whose fold THREW (the caller's
         * never-throws shell counts it {@code fold_error}): the hull keeps a stamp the server's
         * row superseded. Marks only a wire id the record holds and answers whether it marked.
         * Never throws, so a catch block can call it.
         *
         */
        public boolean markFoldError(long wireId) {
            if (record.entryOrNull(wireId) == null) {
                return false;
            }
            refusedMarks.add(Long.valueOf(wireId));
            return true;
        }

        /**
         * True from a refused row of {@code wireId} ({@code writes_bound}, {@code closed_side},
         * {@code bad_goal}) or a {@link #markFoldError}, taken while the record held it, until
         * the wire id's next accepted row or its removal: until then its hull's stamp predates a
         * row the server took. The marks die with the fold (one round epoch) and stay a subset of
         * the record's wire ids, so at most 2 x MAX_ENTRIES_PER_SIDE.
         *
         */
        public boolean refusedMarked(long wireId) {
            return refusedMarks.contains(Long.valueOf(wireId));
        }

        /** The stamp a side's document AI hull holds: {@code OPEN(side)} of this epoch, always. */
        public Stamp openStamp(int side) {
            requireSide(side);
            return new Stamp(Stamp.Kind.OPEN, epoch, side, null);
        }

        /** The stamp the hull of {@code wireId} holds this epoch, or null (an unstamped hull). */
        public Stamp stampOrNull(long wireId) {
            return stamps.get(Long.valueOf(wireId));
        }

        /**
         * The key {@code stamp} means NOW: {@code OPEN(side)} = {@link #openKey}; {@code KEY} =
         * its own key. Null for a null stamp or one of another epoch.
         */
        public FleetKey resolve(Stamp stamp) {
            if (stamp == null || stamp.epoch() != epoch) {
                return null;
            }
            return stamp.kind() == Stamp.Kind.OPEN ? openKey(stamp.side()) : stamp.keyOrNull();
        }

        /** The side's key over EVERY row folded so far: what an unstamped mint takes. */
        public FleetKey currentKey(int side) {
            return document.key(side, record);
        }

        /**
         * The side's round-open key: frozen once the side closed; before that the document's
         * non-seat rows + every round-open row of the side folded so far.
         */
        public FleetKey openKey(int side) {
            requireSide(side);
            FleetKey frozen = frozenOpenKey[side];
            return frozen != null ? frozen : document.key(side, openRecord);
        }

        /** True once the side's folded open key matched the digest its round-open rows carry. */
        public boolean isClosed(int side) {
            requireSide(side);
            return frozenOpenKey[side] != null;
        }

        /**
         * (have, want) of a side that folded at least one round-open row and is NOT closed; null
         * otherwise (closed, or no round-open row of the side yet). The caller decides when an
         * unclosed side is worth a diagnostic row.
         */
        public OpenMismatch openMismatch(int side) {
            requireSide(side);
            if (isClosed(side) || openRows[side] == 0) {
                return null;
            }
            return new OpenMismatch(openKey(side).digest(), openWant[side]);
        }

        /**
         * The METER of accepted CHANGING writes of {@code side} this epoch ({@code >= 0}, no
         * upper bound: no row is ever refused for this number); an equal re-write is not
         * counted, nor is a removal.
         */
        public long changingWrites(int side) {
            requireSide(side);
            return changingWrites[side];
        }

        /** Record entries {@code side} holds this epoch ({@code 0..MAX_ENTRIES_PER_SIDE}). */
        public int entries(int side) {
            requireSide(side);
            return entries[side];
        }

        /** Accepted round-open rows of {@code side} this epoch. */
        public long openRows(int side) {
            requireSide(side);
            return openRows[side];
        }

        public long refusedWritesBound() {
            return refusedWritesBound;
        }

        public long refusedClosedSide() {
            return refusedClosedSide;
        }

        public long refusedBadGoal() {
            return refusedBadGoal;
        }

        /** Removal rows refused {@code vacate_absent} this epoch. */
        public long refusedVacateAbsent() {
            return refusedVacateAbsent;
        }

        /**
         * Accepted mid-round rows and taken removal rows whose carried digest differed from this
         * fold's key.
         */
        public long mismatches() {
            return mismatches;
        }
    }
}
