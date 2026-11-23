package cc.starsector.server.match;

import cc.starsector.server.protocol.SeatRole;

import java.util.List;
import java.util.function.Predicate;

/**
 * The bounds and value laws a match document must pass beyond its grammar - the ONE home of every
 * size cap the document's carriers derive from (the text the kit reads, the inflate buffer, the
 * deflated chunk train, the codec's list bounds). WHY one class: the window that receives a
 * document, the game that builds the mission from it and the server that serves it must refuse
 * exactly the same set, so the numbers live once and both trees read them.
 *
 * <p>{@link #check} runs in a fixed order and throws {@link MatchDocumentRefused} at the FIRST
 * law broken, naming OUR field (never the document's bytes). Shared: bundled into the client mod
 * jar and run under the game's script classloader, so this class is {@code String} / {@code List}
 * / {@code Math} code only - no IO, no regex.
 *
 * <p>{@link #checkShape}, {@link #checkLocalField} (MATCH-DOC-4096) and {@link #checkSideZeroFleet} (MDLF-1) are
 * the RECEIVING side's laws on what a document makes a game build locally: the join window and the game run
 * them, {@link #check} does not.
 *
 *
 */
public final class MatchDocumentLaws {

    /** The text, the grammar or a redaction law is wrong. */
    public static final String REASON_MALFORMED = "match_doc_malformed";
    /** A count, a length, an id or a map half is outside its bound. */
    public static final String REASON_OUT_OF_RANGE = "match_doc_out_of_range";
    /** The carried document is larger than the text / deflated / chunk caps below. */
    public static final String REASON_OVERSIZE = "match_doc_oversize";
    /** The carried document did not arrive whole inside its hold. */
    public static final String REASON_INCOMPLETE = "match_doc_incomplete";
    /** A roster variant id does not resolve in the game's loaded data. */
    public static final String REASON_VARIANT_MISSING = "match_doc_variant_missing";

    /** THE one text cap (UTF-8 bytes): about 1.25x the largest mintable redacted document; the inflate
     *  buffer and the kit-file read cap derive from it. */
    public static final int TEXT_MAX = 32768;
    /** A kit-file read stops one byte past the text cap, so an over-cap file is SEEN as over-cap. */
    public static final int FILE_READ_MAX = TEXT_MAX + 1;
    /** The deflated document's cap: {@link #CHUNKS_MAX} x {@link #CHUNK_MAX}. */
    public static final int DEFLATED_MAX = 8832;
    /** The chunk count one document reply may carry. */
    public static final int CHUNKS_MAX = 8;
    /** One chunk's payload: the 1164 B datagram minus the 20 B envelope, the 24 B header and the 16 B tag. */
    public static final int CHUNK_MAX = 1104;
    /** Seat ids ride the voice wire as an unsigned byte with 0 = observer, so one match holds at most 255 seats. */
    public static final int SEATS_MAX = 255;
    /** The highest seat id (the unsigned-byte seat wire, 0 reserved for the observer). */
    public static final int SEAT_ID_MAX = 255;
    /** Roster rows: one more than {@link #SEATS_MAX} (the mint groups rows per hull, far below this). */
    public static final int ROWS_MAX = 256;
    /** The summed roster ship count: every row expands into that many ships in the fleet expander. */
    public static final int SHIPS_MAX = 4096;
    /**
     * {@code match.id} length: the door composite's 128-char cap minus its 91 fixed chars
     * ({@code cc1;jar=} 8 + 32 hex + {@code ;data=} 6 + 32 hex + {@code ;hs=} 4 + 2 digits +
     * {@code ;match=} 7 = 91), so 128 - 91 = 37 - any longer id could never be presented at the door.
     */
    public static final int MATCH_ID_MAX = 37;
    /** {@code match.shape} length. */
    public static final int SHAPE_MAX = 64;
    /** A side name's length. */
    public static final int SIDE_NAME_MAX = 64;
    /** A side tagline's length. */
    public static final int TAGLINE_MAX = 120;
    /** A roster variant id's length: the twin of the door handshake's variant cap ({@code Handshake13.VARIANT_MAX},
     *  a server-only class this shared one cannot reference). */
    public static final int VARIANT_ID_MAX = 64;
    /** The smallest map half-extent, whole su: the twin of {@code MatchShapes.MAP_HALF_MIN} (server-only). */
    public static final float MAP_HALF_MIN = 2000f;
    /** The largest map half-extent, whole su: the twin of {@code MatchShapes.MAP_HALF_MAX} (server-only). */
    public static final float MAP_HALF_MAX = 60000f;
    /** The duel shape, a registry key by EXACT match ({@link #isRegistryShape}; never a prefix, so the rig's
     *  {@code vanilla:1v1} twin stays out): served as {@code seats:2} is, it fields only the own seat locally. */
    public static final String SHAPE_ONE_VS_ONE = "1v1";
    /** The prefix of this repo's own serverless compare windows ({@code vanilla:<shape>}): the WHOLE roster is
     *  fielded locally and the enemy side gets a deploying admiral, so no server mints it and no document earns
     *  it - only a window launched with the rig knob ({@link #checkShape}'s {@code vanillaRig}). */
    public static final String SHAPE_VANILLA_PREFIX = "vanilla:";
    /** The station hulls a registry document may make a game field at mission build (each clones its module
     *  cluster): 4x the largest lawful mint (1, the fortress). */
    public static final int STATION_FIELD_MAX = 4;

    private MatchDocumentLaws() {
    }

    /** The n-peer registry prefixes ({@code seats:} / {@code gauntlet:} / {@code teams:} / {@code bastion:}): with
     *  {@code bots} and the exact {@code 1v1} they make up {@link #isRegistryShape}, the key every caller routes on. */
    public static boolean isNpeerShape(String shape) {
        return shape.startsWith("seats:") || shape.startsWith("gauntlet:")
                || shape.startsWith("teams:") || shape.startsWith("bastion:");
    }

    /**
     * THE registry-shape key list, single-sourced (moved here from the client's mission builder
     * with the local-field law, which keys on it): a registry shape fields ONLY the own seat locally - every other
     * hull spawns at first wire sight - so the mission build, the predict plugin and these laws must never disagree
     * on it. {@code bots} is a bare prefix (the mint canonicalizes {@code bots:0:N} to {@code bots:N}).
     * {@code 1v1} is a key by EXACT match. WHY: the server serves a
     * {@code 1v1} exactly as {@code seats:2} (same roster, a seat's pick re-points its hull), so a client that fields
     * the document's rows locally flies a hull the server never fielded and never takes the re-point. Only the
     * rig's {@code vanilla:} windows (and a window with no document) stay off the registry.
     */
    public static boolean isRegistryShape(String shape) {
        return SHAPE_ONE_VS_ONE.equals(shape) || shape.startsWith("bots") || isNpeerShape(shape);
    }

    /**
     * THE SHAPE LAW (MATCH-DOC-4096): a document's shape is a registry shape ({@code 1v1}
     * among them), else refused {@code match_doc_out_of_range shape} (OUR token, never the shape's text). WHY:
     * the shape picks how much of the roster a game fields LOCALLY, and a {@code vanilla:} shape fields all of it under an
     * admiral that deploys every zero-point reserve in one frame - a host-authored document must never choose
     * that. {@code vanillaRig} = the launching rig's own knob (never read from the document): only then is a
     * {@code vanilla:} shape taken. Runs at the join window (always {@code false}) and in the game's one load.
     */
    public static void checkShape(MatchDescriptor d, boolean vanillaRig) throws MatchDocumentRefused {
        String shape = d.shape();
        boolean lawful = isRegistryShape(shape) || (vanillaRig && shape.startsWith(SHAPE_VANILLA_PREFIX));
        if (!lawful) {
            throw new MatchDocumentRefused(REASON_OUT_OF_RANGE, "shape");
        }
    }

    /**
     * The hulls {@code d} makes a game field LOCALLY at mission build, as the local-field law counts them - an
     * UPPER bound on the mission build's own {@code addToFleet} calls beyond the one own-seat hull (rows: verify-
     * match V13 against the recording mission stub). A registry shape: its station rows' summed count on EITHER
     * side (the build fields the stations of the side the seat is not on; side-blind, so the bound holds whatever
     * seat this game gets). Any other shape (past the shape law only the rig's exempt {@code vanilla:} windows):
     * the whole roster's summed count.
     */
    public static int localFieldCount(MatchDescriptor d, Predicate<String> isStation) {
        boolean registry = isRegistryShape(d.shape());
        List<MatchDescriptor.RosterRow> roster = d.roster();
        long fielded = 0L;
        for (int i = 0; i < roster.size(); i++) {
            MatchDescriptor.RosterRow row = roster.get(i);
            if (!registry || isStation.test(row.variantId())) {
                fielded += row.count();
            }
        }
        return (int) Math.min(fielded, Integer.MAX_VALUE);
    }

    /**
     * THE LOCAL-FIELD LAW (MATCH-DOC-4096): {@link #checkShape} first, then the hulls the
     * document makes this game field locally ({@link #localFieldCount}: its station rows, either side) stay within
     * {@link #STATION_FIELD_MAX}, else {@code match_doc_out_of_range roster.local}. Past the shape law a shape is a
     * registry one ({@code 1v1} too) or the rig's {@code vanilla:} window, which is exempt (its author is the rig),
     * so a side-0 station row is lawful on every shape (a registry build never puts a station in the seat's own
     * reserves; an own-side one rides the registry, as the fortress does).
     * WHY the cap sits far under {@link #SHIPS_MAX}: that bound is for hulls a registry shape spawns at
     * wire sight under the server's own pacing; the stations are built in one go at mission build from a
     * host-authored file.
     *
     * @param isStation the game's station test in the game (every variant resolved first); at the join window,
     *                  which has no game data, a test that is always false (nothing counts there: the game's own
     *                  load runs this law again with the real test)
     */
    public static void checkLocalField(MatchDescriptor d, Predicate<String> isStation, boolean vanillaRig)
            throws MatchDocumentRefused {
        checkShape(d, vanillaRig);
        if (d.shape().startsWith(SHAPE_VANILLA_PREFIX)) {
            return;
        }
        if (localFieldCount(d, isStation) > STATION_FIELD_MAX) {
            throw new MatchDocumentRefused(REASON_OUT_OF_RANGE, "roster.local");
        }
    }

    /**
     * THE SIDE-0 FLEET LAW (MDLF-1): at least one roster row is a side-0 ship that is not a
     * station, else {@code match_doc_out_of_range roster.side0}. WHY: the all-skills fleet context is minted from
     * exactly that walk ({@link AllSkillsFleetLaw#sideZeroShipVariantIds}), which THROWS on an empty one (an empty
     * fleet would grant every threshold skill at maximum). A host-authored document must never carry that throw
     * into a joiner's mission build or frame loop, and the all-skills bit arrives on the wire AFTER the document
     * is loaded, so the law binds every document whatever the match's flags, and every shape (the rig's
     * {@code vanilla:} one too). The test is the walk's own, row for row; no server mint has an empty walk.
     *
     * @param isStation as {@link #checkLocalField}'s: the game's station test in the game; at the join window the
     *                  always-false test, which then refuses only a document with no side-0 row at all
     */
    public static void checkSideZeroFleet(MatchDescriptor d, Predicate<String> isStation)
            throws MatchDocumentRefused {
        List<MatchDescriptor.RosterRow> roster = d.roster();
        for (int i = 0; i < roster.size(); i++) {
            MatchDescriptor.RosterRow row = roster.get(i);
            if (row.side() == 0 && row.count() >= 1 && !isStation.test(row.variantId())) {
                return;
            }
        }
        throw new MatchDocumentRefused(REASON_OUT_OF_RANGE, "roster.side0");
    }

    /**
     * Apply the laws to a decoded descriptor, in a fixed order; the FIRST broken law throws.
     * <ol>
     *   <li>Counts (out of range): seats &lt;= {@link #SEATS_MAX}, each seat id 1..{@link #SEAT_ID_MAX},
     *       roster rows &lt;= {@link #ROWS_MAX}, the summed ships &lt;= {@link #SHIPS_MAX} (named at
     *       the crossing row).</li>
     *   <li>Text (out of range): {@code match.id} &lt;= {@link #MATCH_ID_MAX}, never the
     *       {@link ContentFingerprints#MATCH_NONE} word (that word means "no document") and never carrying
     *       {@code ;} or {@code =} (the door composite's delimiters: such an id would reach the composite's
     *       own refusal, whose message echoes the value);
     *       {@code match.shape} &lt;= {@link #SHAPE_MAX} in {@code [a-z0-9:_-]}; side names and
     *       taglines within their caps; roster variant ids &lt;= {@link #VARIANT_ID_MAX} in printable
     *       ASCII 0x21..0x7E. Lengths are {@code String.length()}.</li>
     *   <li>Map (out of range): both halves whole and within
     *       {@link #MAP_HALF_MIN}..{@link #MAP_HALF_MAX}.</li>
     *   <li>Redaction (malformed; only when {@code requireRedacted}): every seat token is exactly
     *       its own {@code REDACTED-seat<id>} placeholder, and at least one seat is a PILOT. The
     *       join window and the server's preflight ask for it; the game passes false (a dev
     *       document may carry live tokens).</li>
     * </ol>
     *
     *
     */
    public static void check(MatchDescriptor d, boolean requireRedacted) throws MatchDocumentRefused {
        if (d == null) {
            throw new MatchDocumentRefused(REASON_MALFORMED, "descriptor");
        }
        checkCounts(d);
        checkText(d);
        checkMap(d);
        if (requireRedacted) {
            checkRedacted(d.seats());
        }
    }

    private static void checkCounts(MatchDescriptor d) {
        List<MatchDescriptor.Seat> seats = d.seats();
        if (seats.size() > SEATS_MAX) {
            throw new MatchDocumentRefused(REASON_OUT_OF_RANGE, "seat.count");
        }
        for (int i = 0; i < seats.size(); i++) {
            int id = seats.get(i).seatId();
            if (id < 1 || id > SEAT_ID_MAX) {
                throw new MatchDocumentRefused(REASON_OUT_OF_RANGE, "seat." + i + ".id");
            }
        }
        List<MatchDescriptor.RosterRow> roster = d.roster();
        if (roster.size() > ROWS_MAX) {
            throw new MatchDocumentRefused(REASON_OUT_OF_RANGE, "roster.count");
        }
        long ships = 0L;
        for (int i = 0; i < roster.size(); i++) {
            ships += roster.get(i).count();
            if (ships > SHIPS_MAX) {
                throw new MatchDocumentRefused(REASON_OUT_OF_RANGE, "roster." + i + ".count");
            }
        }
    }

    private static void checkText(MatchDescriptor d) {
        String matchId = d.matchId();
        // WHY ';' and '=' are refused here: the id rides the door composite as its
        // `;match=<id>` term, whose component law (ContentFingerprints.requireComponent) throws `got '<value>'`
        // - a message the join window shows raw (JoinFingerprint) and the dial planner logs. Refusing the two
        // composite delimiters at THIS law keeps host-authored document text off that P1 echo surface; the
        // model already keeps the id inside printable ASCII 0x21..0x7E, so whitespace never reaches here.
        if (matchId.length() > MATCH_ID_MAX || ContentFingerprints.MATCH_NONE.equals(matchId)
                || matchId.indexOf(';') >= 0 || matchId.indexOf('=') >= 0) {
            throw new MatchDocumentRefused(REASON_OUT_OF_RANGE, "match.id");
        }
        String shape = d.shape();
        if (shape.length() > SHAPE_MAX || !isShapeText(shape)) {
            throw new MatchDocumentRefused(REASON_OUT_OF_RANGE, "match.shape");
        }
        List<MatchDescriptor.Side> sides = d.sides();
        for (int i = 0; i < sides.size(); i++) {
            if (sides.get(i).name().length() > SIDE_NAME_MAX) {
                throw new MatchDocumentRefused(REASON_OUT_OF_RANGE, "side." + i + ".name");
            }
            if (sides.get(i).tagline().length() > TAGLINE_MAX) {
                throw new MatchDocumentRefused(REASON_OUT_OF_RANGE, "side." + i + ".tagline");
            }
        }
        List<MatchDescriptor.RosterRow> roster = d.roster();
        for (int i = 0; i < roster.size(); i++) {
            String variant = roster.get(i).variantId();
            if (variant.length() > VARIANT_ID_MAX || !isPrintableAscii(variant)) {
                throw new MatchDocumentRefused(REASON_OUT_OF_RANGE, "roster." + i + ".variant");
            }
        }
    }

    private static void checkMap(MatchDescriptor d) {
        if (!isWholeHalf(d.halfWidth())) {
            throw new MatchDocumentRefused(REASON_OUT_OF_RANGE, "map.halfWidth");
        }
        if (!isWholeHalf(d.halfHeight())) {
            throw new MatchDocumentRefused(REASON_OUT_OF_RANGE, "map.halfHeight");
        }
    }

    private static void checkRedacted(List<MatchDescriptor.Seat> seats) {
        boolean pilot = false;
        for (int i = 0; i < seats.size(); i++) {
            MatchDescriptor.Seat seat = seats.get(i);
            String placeholder = MatchDescriptor.REDACTED_TOKEN_PREFIX + "seat" + seat.seatId();
            if (!placeholder.equals(seat.seatToken())) {
                throw new MatchDocumentRefused(REASON_MALFORMED, "seat." + i + ".token");
            }
            if (seat.role() == SeatRole.PILOT) {
                pilot = true;
            }
        }
        if (!pilot) {
            throw new MatchDocumentRefused(REASON_MALFORMED, "seat.count");
        }
    }

    /** Every char in {@code [a-z0-9:_-]} - the stock spec grammar ({@code bastion:fortress:rig}, {@code teams:2:1}). */
    private static boolean isShapeText(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == ':' || c == '_' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** Every char printable ASCII 0x21..0x7E (no space, no control, no non-ASCII). */
    private static boolean isPrintableAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    private static boolean isWholeHalf(float h) {
        return h == Math.rint(h) && h >= MAP_HALF_MIN && h <= MAP_HALF_MAX;
    }
}
