package cc.starsector.server.match;

import cc.starsector.server.protocol.SeatRole;
import cc.starsector.server.runtime.WireTextLaw;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The server-minted match descriptor (demo-program R4): ONE authored definition of a
 * match — map, rules, sides, roster, seats — INTENDED to be consumed by both JVMs, replacing the
 * four hand-kept copies of the same 1v1 setup (client mission, scout gate reflection build, client
 * scout composition, fleet bench) whose agreement was held only by comments.
 *
 * <p><b>Wiring status (R4 partial, opt-in default-OFF at HEAD).</b> The descriptor lane is
 * default-OFF: with {@code cc.match.descriptor}/{@code CC_MATCH_DESCRIPTOR} unset,
 * {@code MatchMissionBuilder.buildFromConfiguredDescriptor} returns false and the hardcoded 1v1 body
 * ({@code cc_predict/MissionDefinition.java}) is the LIVE default. It is NOT yet consumed by the
 * real-engine combat serve loop (capabilities-ledger R4 row). Retiring the hardcoded copy is a
 * separate step; this class is the intended single-source definition, opt-in for now.
 *
 * <p>Game-free and immutable. This class (plus {@link MatchDescriptorCodec}) is bundled into the
 * client mod jar and loaded by Starsector's script classloader B in-game, so it keeps a trivial
 * static footprint: no I/O, no reflection, no {@code SecureRandom} — minting (which needs tokens)
 * lives in the server-only {@link MatchDescriptors}, which is deliberately NOT bundled.
 *
 * <p>Fail-closed at construction: every cap below throws rather than defaulting (the same
 * invalid-values-THROW discipline as the predict-lane knobs). Version is pinned to {@code 1};
 * a future shape change bumps it and widens the codec explicitly.
 *
 * <p>Seats follow the admission-handshake seat model:
 * <b>identity</b> = the per-seat opaque token (minted here, carry-only until R8 enforces it),
 * <b>role</b> = {@link SeatRole}, <b>mount</b> = {@code shipId} (the seat's current hull — the
 * deploy-order id the predict lane already uses via {@code cc.predict.shipId}).
 */
public final class MatchDescriptor {

    /** The only descriptor version this code mints or decodes. */
    public static final int VERSION = 1;

    /**
     * The per-seat placeholder prefix a REDACTED kit
     * descriptor carries instead of real tokens ({@code REDACTED-seat<seatId>}, minted by
     * {@code tools/MatchTokenRedact} at kit pack). Placeholders keep the codec + fail-closed
     * extraction shape (single-line, non-blank, ≤64, distinct per seat) so every existing decode
     * lane works unchanged; the door bounces one as {@code seat_token_unknown} (readable). Real
     * tokens live ONLY in the mint-time tokens sidecar and travel out-of-band (DM), pasted into
     * the join window → {@code cc.predict.seatToken}.
     */
    public static final String REDACTED_TOKEN_PREFIX = "REDACTED-";

    /** Whether a seat token is a kit-redaction placeholder (never admissible at the door). */
    public static boolean isRedactedToken(String token) {
        return token != null && token.startsWith(REDACTED_TOKEN_PREFIX);
    }

    /**
     * The build-time battle BACKDROP word — the OPTIONAL
     * {@code map.terrain} descriptor key. Presentation only (nebula tiles have no SIM reader
     * under vanilla settings, hyperspace mode is a background warp renderer),
     * so the CLIENT mission builder applies it at define time ({@code TerrainBackdrop}) and the
     * server never reads it beyond carrying it. Distinct from the LIVE {@code WireKnob.TERRAIN}
     * stat word (corona/pulsar/abyss): that one is a knob, this one is a descriptor row because
     * every lever behind it ({@code setNebulaTex}, {@code addNebula}, {@code setHyperspaceMode},
     * {@code setBackgroundSpriteName}) is build-time only. Words = vanilla battle
     * situations: {@code none} = today's empty battlefield (key ABSENT — old descriptors stay
     * byte-identical), {@code open} = an open-space battle (15 default blobs), {@code nebula} /
     * {@code nebula_blue} = a battle inside a nebula (100 blobs + the amber / blue tile texture),
     * {@code hyper} = a hyperspace battle (warp background, no blobs). Exact-table membership,
     * reject-never-clamp; the ops shells fold case BEFORE calling.
     */
    public static final String TERRAIN_NONE = "none";

    /** {@code open}: an open-space battle backdrop (see {@link #TERRAIN_NONE}).
     * */
    public static final String TERRAIN_OPEN = "open";

    /** {@code nebula}: a battle inside an amber nebula (see {@link #TERRAIN_NONE}).
     * */
    public static final String TERRAIN_NEBULA = "nebula";

    /** {@code nebula_blue}: a battle inside a blue-tiled nebula (see {@link #TERRAIN_NONE}).
     * */
    public static final String TERRAIN_NEBULA_BLUE = "nebula_blue";

    /** {@code hyper}: a hyperspace battle (see {@link #TERRAIN_NONE}).
     * */
    public static final String TERRAIN_HYPER = "hyper";

    /**
     * The fixed {@code map.terrain} vocabulary (see {@link #TERRAIN_NONE}); order = the ops
     * combo order. IMMUTABLE (the former {@code public static final
     * String[]} let any caller in either JVM rewrite the descriptor law with
     * {@code TERRAIN_WORDS[i] = ...}; this class ships in the client jar too). The codec
     * carries the WORD string, never an index, so the wire bytes are unchanged; the client
     * {@code TerrainBackdrop} hand-matches these words — golden-pinned by
     * {@code MatchLawContractRows.matchDescriptorTerrainWordsGolden}.
     *
     */
    public static final List<String> TERRAIN_WORDS = List.of(
            TERRAIN_NONE, TERRAIN_OPEN, TERRAIN_NEBULA, TERRAIN_NEBULA_BLUE, TERRAIN_HYPER);

    /** Whether {@code word} is an exact member of {@link #TERRAIN_WORDS} (null = false; the
     *  null guard is load-bearing: {@code List.of(...).contains(null)} throws NPE).
     * */
    public static boolean isTerrainWord(String word) {
        return word != null && TERRAIN_WORDS.contains(word);
    }

    /** Who flies a roster row's ships: a human-held seat or the engine's own AI. */
    public enum PilotKind {
        /** Bound to an admitted seat (a human pilot via the intent lane). */
        SEAT,
        /** Flown by the engine's own ship AI (the D2 bot-capacity rows). */
        AI
    }

    /** One fleet side (exactly two per match; index 0 = the player/seat-1 side). */
    public static final class Side {
        private final String name;
        private final String tagline;
        private final String goal;

        public Side(String name, String tagline, String goal) {
            this.name = requireSingleLine("side.name", name, false);
            this.tagline = requireSingleLine("side.tagline", tagline, true);
            this.goal = requireSingleLine("side.goal", goal, false);
            if (!"ATTACK".equals(this.goal)) {
                // Only ATTACK is authored in v1; the refusal names the field, never the word read.
                throw new MatchDocumentRefused(MatchDocumentLaws.REASON_OUT_OF_RANGE, "side.goal");
            }
        }

        public String name() {
            return name;
        }

        public String tagline() {
            return tagline;
        }

        public String goal() {
            return goal;
        }
    }

    /** One roster row: {@code count} ships of {@code variantId} on side {@code side}. */
    public static final class RosterRow {
        private final int side;
        private final String variantId;
        private final int count;
        private final PilotKind pilot;

        public RosterRow(int side, String variantId, int count, PilotKind pilot) {
            if (side != 0 && side != 1) {
                throw new IllegalArgumentException("roster.side must be 0 or 1, got: " + side);
            }
            this.side = side;
            this.variantId = requireSingleLine("roster.variant", variantId, false);
            if (count < 1) {
                throw new IllegalArgumentException("roster.count must be >= 1, got: " + count);
            }
            this.count = count;
            if (pilot == null) {
                throw new IllegalArgumentException("roster.pilot must not be null");
            }
            this.pilot = pilot;
        }

        public int side() {
            return side;
        }

        public String variantId() {
            return variantId;
        }

        public int count() {
            return count;
        }

        public PilotKind pilot() {
            return pilot;
        }
    }

    /** One admitted seat: identity (token), role, side, and current hull mount (shipId). */
    public static final class Seat {
        private final int seatId;
        private final SeatRole role;
        private final int side;
        private final long shipId;
        private final String seatToken;

        public Seat(int seatId, SeatRole role, int side, long shipId, String seatToken) {
            if (seatId < 1) {
                throw new IllegalArgumentException("seat.id must be >= 1, got: " + seatId);
            }
            this.seatId = seatId;
            if (role == null) {
                throw new IllegalArgumentException("seat.role must not be null");
            }
            this.role = role;
            if (side != 0 && side != 1) {
                throw new IllegalArgumentException("seat.side must be 0 or 1, got: " + side);
            }
            this.side = side;
            if (shipId < 1) {
                throw new IllegalArgumentException("seat.ship must be >= 1, got: " + shipId);
            }
            this.shipId = shipId;
            this.seatToken = requireSingleLine("seat.token", seatToken, false);
            if (this.seatToken.length() > 64) {
                throw new IllegalArgumentException(
                        "seat.token must be <= 64 chars, got " + this.seatToken.length());
            }
        }

        public int seatId() {
            return seatId;
        }

        public SeatRole role() {
            return role;
        }

        public int side() {
            return side;
        }

        public long shipId() {
            return shipId;
        }

        public String seatToken() {
            return seatToken;
        }
    }

    private final int version;
    private final String matchId;
    private final String shape;
    private final float halfWidth;
    private final float halfHeight;
    private final float deployCr;
    private final String endCondition;
    private final List<Side> sides;
    private final List<RosterRow> roster;
    private final List<Seat> seats;
    private final String terrain;

    /** The pre-slice-D shape: backdrop word {@link #TERRAIN_NONE} (every existing mint /
     *  decode / redact site keeps its bytes). */
    public MatchDescriptor(int version, String matchId, String shape,
                           float halfWidth, float halfHeight,
                           float deployCr, String endCondition,
                           List<Side> sides, List<RosterRow> roster, List<Seat> seats) {
        this(version, matchId, shape, halfWidth, halfHeight, deployCr, endCondition,
                sides, roster, seats, TERRAIN_NONE);
    }

    /** The full shape — {@code terrain} = a {@link #TERRAIN_WORDS} member
     *  (exact; an off-table word throws, the codec's unknown-key law for values). */
    public MatchDescriptor(int version, String matchId, String shape,
                           float halfWidth, float halfHeight,
                           float deployCr, String endCondition,
                           List<Side> sides, List<RosterRow> roster, List<Seat> seats,
                           String terrain) {
        if (!isTerrainWord(terrain)) {
            // Must be one of TERRAIN_WORDS; the refusal names the field, never the word read.
            throw new MatchDocumentRefused(MatchDocumentLaws.REASON_OUT_OF_RANGE, "map.terrain");
        }
        this.terrain = terrain;
        if (version != VERSION) {
            throw new IllegalArgumentException("descriptor version must be " + VERSION + ", got: " + version);
        }
        this.version = version;
        this.matchId = requireAsciiId("match.id", requireSingleLine("match.id", matchId, false));
        this.shape = requireSingleLine("match.shape", shape, false);
        if (!(halfWidth > 0f) || Float.isInfinite(halfWidth)) {
            throw new IllegalArgumentException("map.halfWidth must be finite > 0, got: " + halfWidth);
        }
        this.halfWidth = halfWidth;
        if (!(halfHeight > 0f) || Float.isInfinite(halfHeight)) {
            throw new IllegalArgumentException("map.halfHeight must be finite > 0, got: " + halfHeight);
        }
        this.halfHeight = halfHeight;
        if (!(deployCr > 0f) || deployCr > 1f) {
            throw new IllegalArgumentException("rules.deployCr must be in (0, 1], got: " + deployCr);
        }
        this.deployCr = deployCr;
        this.endCondition = requireSingleLine("rules.endCondition", endCondition, false);
        if (!"BUDGET".equals(this.endCondition)) {
            // Only BUDGET is authored in v1 (the engine-own isCombatOver path becomes an
            // authored value in a later version); an unknown condition must never silently run.
            throw new MatchDocumentRefused(MatchDocumentLaws.REASON_OUT_OF_RANGE, "rules.endCondition"); // field only, never the word
        }
        if (sides == null || sides.size() != 2) {
            throw new IllegalArgumentException("descriptor must have exactly 2 sides, got: "
                    + (sides == null ? "null" : Integer.toString(sides.size())));
        }
        this.sides = Collections.unmodifiableList(new ArrayList<Side>(sides));
        if (roster == null || roster.isEmpty()) {
            throw new IllegalArgumentException("descriptor roster must not be empty");
        }
        this.roster = Collections.unmodifiableList(new ArrayList<RosterRow>(roster));
        if (seats == null || seats.isEmpty()) {
            throw new IllegalArgumentException("descriptor must have at least one seat");
        }
        Set<Integer> seatIds = new HashSet<Integer>();
        Set<String> tokens = new HashSet<String>();
        for (Seat seat : seats) {
            if (!seatIds.add(seat.seatId())) {
                throw new IllegalArgumentException("duplicate seat.id: " + seat.seatId());
            }
            if (!tokens.add(seat.seatToken())) {
                throw new IllegalArgumentException("duplicate seat.token for seat.id " + seat.seatId());
            }
        }
        this.seats = Collections.unmodifiableList(new ArrayList<Seat>(seats));
    }

    public int version() {
        return version;
    }

    public String matchId() {
        return matchId;
    }

    public String shape() {
        return shape;
    }

    public float halfWidth() {
        return halfWidth;
    }

    public float halfHeight() {
        return halfHeight;
    }

    public float deployCr() {
        return deployCr;
    }

    /** The build-time backdrop word ({@link #TERRAIN_WORDS}; {@link #TERRAIN_NONE} when the
     *  {@code map.terrain} key is absent). */
    public String terrain() {
        return terrain;
    }

    public String endCondition() {
        return endCondition;
    }

    public List<Side> sides() {
        return sides;
    }

    public List<RosterRow> roster() {
        return roster;
    }

    public List<Seat> seats() {
        return seats;
    }

    /**
     * Does {@code descriptor} seat a PILOT on BOTH sides? The ONE test for "a joiner's team pick means
     * something here": the join door honours a pick only on such a shape, and the join window shows its team
     * choice under the same test, so the two can never disagree. True for every shape whose human seats face
     * each other (1v1, the duels, the gauntlets, teams, PvP, and {@code seats:K}, whose pilot seats are minted
     * ceil / floor across both sides); false when every pilot seat sits on one side (the fortress,
     * {@code bots:K}) — there a pick is ignored, never refused. Null is false. Plain
     * {@code java.util} only: the class rides the kit jar.
     *
     */
    public static boolean seatsPilotOnBothSides(MatchDescriptor descriptor) {
        if (descriptor == null) {
            return false;
        }
        boolean side0 = false;
        boolean side1 = false;
        for (Seat seat : descriptor.seats) {
            if (seat.role() == SeatRole.PILOT) {
                side0 |= seat.side() == 0;
                side1 |= seat.side() == 1;
            }
        }
        return side0 && side1;
    }

    /** Non-blank (unless {@code allowEmpty}) and {@link WireTextLaw}-printable; */
    private static String requireSingleLine(String field, String value, boolean allowEmpty) {
        // The refusal names the field only (a structured MatchDocumentRefused): a document can come from a
        // hostile host and the text reaches windows and logs.
        if (value == null || (!allowEmpty && value.trim().isEmpty())) {
            throw new MatchDocumentRefused(MatchDocumentLaws.REASON_MALFORMED, field);
        }
        int bad = WireTextLaw.firstUnprintableIndex(value);
        if (bad >= 0) {
            // The shared wire-text convention: the refusal names the offending INDEX (our number), never the char.
            throw new MatchDocumentRefused(MatchDocumentLaws.REASON_OUT_OF_RANGE, field + " index " + bad);
        }
        return value;
    }

    /**
     * The id law the version-13 print needs: every char 0x21..0x7E (the {@code Handshake13} A-string law; the space,
     * DEL and every non-ASCII char refused). WHY here, at every mint / decode / derivation: {@link WireTextLaw} admits
     * non-ASCII printables by contract (chat needs them), so a {@code match.id} it passes could still be one no
     * version-13 print can carry. The refusal (a structured {@link MatchDocumentRefused}) names the field, never the id.
     *
     */
    private static String requireAsciiId(String field, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                throw new MatchDocumentRefused(MatchDocumentLaws.REASON_OUT_OF_RANGE, field); // never the id: the text reaches the join window
            }
        }
        return value;
    }
}
