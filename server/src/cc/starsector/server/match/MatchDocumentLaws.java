package cc.starsector.server.match;

import cc.starsector.server.protocol.SeatRole;

import java.util.List;

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

    private MatchDocumentLaws() {
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
