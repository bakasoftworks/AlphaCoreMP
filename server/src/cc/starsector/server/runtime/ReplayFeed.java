package cc.starsector.server.runtime;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.function.DoubleSupplier;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

/**
 * The re-simulation's input source: a recorded reel's ROW records read back, in file order, by the
 * serve's input sites while it re-runs the same match without a client. Every value the recorded
 * serve took from another thread or from a person (the takes and declarations, the operator
 * commands, the session edges, the fly-in variant override, the live-roster ratio, the /suicide
 * requests, the command-transfer begins, the wrap picks) is handed back from its row; everything else
 * the serve RE-DERIVES by simulating. A recomputed value (the {@code staged} bit, a /suicide outcome, a
 * transfer verdict, the wrap's deployable seats) is the replay's own and is what the serve acts on: this
 * class only counts and prints where it differs from the recorded one, so a divergence is measured,
 * never hidden.
 *
 * <p>Reading model: rows only — the cursor's per-kind payload
 * choice skips every DOWN / UP payload without allocating it — into a bounded lookahead deque. The
 * deque exists for ONE cross-band read: the round-start gate's TAKES rows (stamped -1) sit ahead of the
 * frame-0 SESS LIVE rows naming the sessions those takes came from, so {@link #preMint} reads past the
 * gate rows to mint the sessions first and leaves every row buffered for its own claim. A row's
 * position is (round, tick, band) compared lexicographically, band GATE 0 &lt; PRE 1 &lt; POST 2 &lt;
 * WRAP 3 (a WRAP row stamps the round's last tick, so it sorts after that tick's POST rows); a reel's
 * file order is that order.
 *
 * <p>Each serve input site calls ONE claim keyed by (round, tick, band [, seat]); a single-row claim
 * removes the first buffered row of its key, a run claim (the commands, the session edges) every row
 * of it in file order. A row no site claimed is dropped LOUD (one stdout row per kind per
 * round at its first occurrence, the rest counted), so a re-simulation that consumed less than the
 * recording did is visible in its own output. A variant submission's row recorded with its body tail
 * is re-admitted through {@link CustomVariantLane#replayOffer}; a body-less one (recorded before the tail
 * existed) refuses the reel with an {@link IOException}, as does every kind the serve cannot re-feed (the
 * reserved kinds have no writer).
 *
 * <p>Single-threaded: the serve thread of a replay JVM only (no live client, unpaced). The reel is an
 * operator-supplied input file, bounded by {@link CcwrPullCursor#MAX_PAYLOAD_BYTES} per record and by
 * {@link #LOOKAHEAD_MAX_ROWS}, and every text field by {@link MatchRecordRows#UTF_MAX_BYTES} (a longer
 * length is a malformed row); this class prints no row's text field (command text, departure reasons,
 * variant ids) — they are returned to the caller, and its printed rows carry numbers only. The serve's
 * verb rows do print a command's text, so {@link #commandsAt} hands back only a command the live
 * grammar re-built from the row.
 *
 *
 */
public final class ReplayFeed implements Closeable {

    /**
     * The replay door's session verbs, implemented by the serve over its replay door, so this class
     * mints and ends sessions without importing the door (and its rows run without a socket).
     */
    public interface SessionDoor {
        /** Admit a session under its RECORDED (signed) id; observers carry seat 0 and ship 0. */
        void mint(long sessionId, int seat, long shipId, boolean observer, boolean admin);

        /** End a live session with its recorded departure reason (a server token). */
        void die(long sessionId, String departureReason);

        /** Clear the seat's post-admission tenure gate (the live door clears it on its reader thread). */
        void clearTenureGate(int seat);

        boolean isLive(long sessionId);
    }

    /**
     * The lookahead deque's row bound, DERIVED from the format rather than chosen: a well-formed reel's
     * round-start gate rows carry strictly increasing u16 ordinals (1..65,535, so at most 65,535 gate
     * rows), plus a 4,096-row allowance for frame 0's CMD and SESS rows that sit between the gate rows
     * and the frame-0 row the pre-mint stops at. A reel holding more rows ahead of frame 0 is refused
     * LOUD ({@link #preMint} throws) before any tick is simulated; in steady state the deque holds about
     * one frame's rows, so only the pre-mint can come near it.
     *
     * <p>There is deliberately NO byte ceiling: a recording's gate rows are bounded by its client wait
     * (the 120 s default at the gate's 20 ms sleep is about 6,000 rows, far under this bound); every
     * record is already capped by {@link CcwrPullCursor#MAX_PAYLOAD_BYTES}; the reel is an
     * operator-supplied input file, not wire input; and the row bound is the domain the format itself
     * fixes, where a byte figure would be a chosen number. The trade, said out loud: the worst case
     * this bound admits is about 69.6k full gate rows (255 takes of 63-byte frames, about 18 KB each),
     * about 1.2 GB of buffered payload.
     */
    public static final int LOOKAHEAD_MAX_ROWS = 65_535 + 4_096;

    /** The first container version that carries row records; an older reel has no inputs to feed. */
    private static final int ROWS_FORMAT_VERSION = 2;

    /** The gate's container stamp (rows written before frame 0). */
    private static final long GATE_TICK = -1L;

    /** Read the payload of every row kind (and END); DOWN / UP payloads are skipped unallocated. */
    private static final IntPredicate ROW_PAYLOADS = k -> k >= WireRecordSink.KIND_ROW_MIN;

    // Per-round loudness slots: a row kind's own number for the unconsumed-row line, then the named ones.
    private static final int SLOT_STAGED = 17;
    private static final int SLOT_ADMIT = 18;
    private static final int SLOT_FLYIN = 19;
    private static final int SLOT_SUICIDE = 20;
    private static final int SLOT_WRAP = 21;
    private static final int SLOT_SEAT_NOT_LIVE = 22;
    private static final int SLOT_TRANSFER = 23;
    private static final int SLOT_VSUB_OFFER = 24;
    private static final int SLOT_COUNT = 25;

    /** One buffered row: its kind, container stamp, prefix band + round, and the raw payload. */
    private record Row(int kind, long tick, int band, int round, byte[] payload) { }

    private final CcwrPullCursor cursor;
    private final ArrayDeque<Row> lookahead = new ArrayDeque<>();
    /** The last row read from the cursor (buffered or not): a read-ahead stops once it is past its key. */
    private Row lastPulled;
    /** Null while rows remain; then {@code end} (an END trailer), {@code eof} or {@code truncated}. */
    private String reelEnd;

    /** Recorded session id (signed) to its seat, for every session this feed minted and not yet ended. */
    private final HashMap<Long, Integer> seatBySession = new HashMap<>();
    /** Live minted sessions per seat (SESS seats are u8: 0..255). */
    private final int[] mintedPerSeat = new int[256];
    /** Last round each loudness slot printed for; Long.MIN_VALUE = never (no int round equals it). */
    private final long[] lastLoudRound = new long[SLOT_COUNT];

    private int gateRound = Integer.MIN_VALUE;
    private int lastGateOrdinal;
    private int rosterRound = Integer.MIN_VALUE;
    private double rosterCarried;
    private int suicideRound = Integer.MIN_VALUE;
    private long suicideTick;
    private final HashMap<Integer, MatchRecordRows.SuicRow> suicideKept = new HashMap<>();

    private long rowsConsumed;
    private long rowsUnconsumed;
    private long rowsLeft;
    private long stagedMismatch;
    private long strayDeposits;
    private long seatNotLive;
    private long admitUnminted;
    private long flyInUnrecorded;
    private long roundEndMismatch;
    private long suicideOutcomeMismatch;
    private long transferOutcomeMismatch;
    private long vsubOfferMismatch;
    private long wrapMismatch;
    private long rosterUnrecorded;
    private long cmdTextCut;

    private ReplayFeed(CcwrPullCursor cursor) {
        this.cursor = cursor;
        Arrays.fill(lastLoudRound, Long.MIN_VALUE);
    }

    /**
     * Open a reel at its first record.
     *
     * @throws IOException the cursor's header gate, or a container version that predates row records
     */
    public static ReplayFeed open(Path reel) throws IOException {
        CcwrPullCursor cursor = CcwrPullCursor.open(reel);
        short version = cursor.header().formatVersion();
        if (version < ROWS_FORMAT_VERSION) {
            cursor.close();
            throw new IOException("reel format version " + version + " carries no input rows (rows start at"
                    + " version " + ROWS_FORMAT_VERSION + "): " + reel.getFileName());
        }
        return new ReplayFeed(cursor);
    }

    /**
     * Mint every frame-0 SESS LIVE row through {@code door} in FILE order (the recording's admission
     * order, which also orders the live set the serve iterates) BEFORE the round-start gate re-runs:
     * reads ahead past the gate rows until the first frame-0 FLYIN / ROSTER / TAKES row, a row past
     * frame 0's PRE band, or the reel's end. The rows stay buffered, so the session-edge site still
     * claims them at frame 0 (and only clears the tenure gates). A gate take naming a seat no row
     * minted is a session that took at the gate and ended before frame 0: it has no SESS row and its
     * id is nowhere in the reel, so the gate site counts {@link #noteAdmitUnminted} and still serves it
     * (lane A dropped the skip).
     *
     * @return the number of sessions minted
     * @throws IOException more than {@link #LOOKAHEAD_MAX_ROWS} rows ahead of frame 0, a malformed or
     *         un-feedable row
     */
    public int preMint(SessionDoor door) throws IOException {
        while (true) {
            Row r = pull(0, 0L, MatchRecordRows.BAND_PRE);
            if (r == null) {
                break;
            }
            int at = comparePosition(r, 0, 0L, MatchRecordRows.BAND_PRE);
            if (at > 0 || (at == 0 && (r.kind() == MatchRecordRows.KIND_FLYIN
                    || r.kind() == MatchRecordRows.KIND_ROSTER || r.kind() == MatchRecordRows.KIND_TAKES))) {
                break;
            }
        }
        int minted = 0;
        for (Row r : lookahead) {
            if (r.kind() != MatchRecordRows.KIND_SESS || !keyMatches(r, 0, 0L, MatchRecordRows.BAND_PRE)) {
                continue;
            }
            MatchRecordRows.SessRow s = decodeSess(r);
            if (s.event() == MatchRecordRows.SESS_LIVE) {
                mintRecorded(door, s);
                minted++;
            }
        }
        return minted;
    }

    /**
     * Claim the next round-start gate TAKES row of {@code round}, reading ahead if needed.
     *
     * @return the row, or null once the gate band of {@code round} is over
     * @throws IOException gate ordinals not strictly increasing within the round (a malformed reel)
     */
    public MatchRecordRows.TakesRow takeGate(int round) throws IOException {
        Row r = claim(MatchRecordRows.KIND_TAKES, round, GATE_TICK, MatchRecordRows.BAND_GATE);
        if (r == null) {
            return null;
        }
        MatchRecordRows.TakesRow row = decodeTakes(r);
        if (round != gateRound) {
            gateRound = round;
            lastGateOrdinal = 0;
        }
        if (row.gateOrdinal() <= lastGateOrdinal) {
            throw new IOException("round-start gate rows out of order in round " + round + ": ordinal "
                    + row.gateOrdinal() + " after " + lastGateOrdinal);
        }
        lastGateOrdinal = row.gateOrdinal();
        return row;
    }

    /**
     * The round-start gate's VSUB rows of {@code round} that sit, in
     * FILE order, before its next gate TAKES row — claimed and returned, while that TAKES row stays buffered
     * for {@link #takeGate}; reads ahead the way {@link #takeGate} does. The live gate drains its deposits at
     * the top of every pass and writes the pass's TAKES row after its takes (a pass that took nothing writes
     * VSUB rows and no TAKES row), so the rows ahead of TAKES k are exactly those the passes up to k drained.
     * With no TAKES row left, the call returns the trailing rows. Never a run claim: every gate VSUB row
     * shares the one key (round, -1, GATE), so only the file order splits them between passes.
     */
    public List<MatchRecordRows.VsubRow> gateVsubsBeforeNextTake(int round) throws IOException {
        List<MatchRecordRows.VsubRow> out = new ArrayList<>(1);
        for (Iterator<Row> it = lookahead.iterator(); it.hasNext(); ) {
            Row r = it.next();
            if (keyMatches(r, round, GATE_TICK, MatchRecordRows.BAND_GATE)) {
                if (r.kind() == MatchRecordRows.KIND_TAKES) {
                    return out;
                }
                if (r.kind() == MatchRecordRows.KIND_VSUB) {
                    it.remove();
                    rowsConsumed++;
                    out.add(decodeVsub(r));
                }
            }
        }
        while (lastPulled == null || comparePosition(lastPulled, round, GATE_TICK, MatchRecordRows.BAND_GATE) <= 0) {
            Row r = pull(round, GATE_TICK, MatchRecordRows.BAND_GATE);
            if (r == null) {
                break;
            }
            if (keyMatches(r, round, GATE_TICK, MatchRecordRows.BAND_GATE)) {
                if (r.kind() == MatchRecordRows.KIND_TAKES) {
                    return out;
                }
                if (r.kind() == MatchRecordRows.KIND_VSUB) {
                    lookahead.pollLast();
                    rowsConsumed++;
                    out.add(decodeVsub(r));
                }
            }
        }
        return out;
    }

    /**
     * Every recorded VSUB row of (round, tick, band) in file order,
     * claimed, with no read: an in-frame POST band is already buffered by {@link #takePre}. The gate band is
     * refused, since a run claim over its one shared key would take every pass's rows at once
     * ({@link #gateVsubsBeforeNextTake} splits them).
     */
    public List<MatchRecordRows.VsubRow> vsubsAt(int round, long tick, byte band) throws IOException {
        if (band == MatchRecordRows.BAND_GATE) {
            throw new IllegalArgumentException("vsubsAt never claims the gate band: see gateVsubsBeforeNextTake");
        }
        List<MatchRecordRows.VsubRow> out = new ArrayList<>(1);
        for (Row r : claimAll(MatchRecordRows.KIND_VSUB, round, tick, band)) {
            out.add(decodeVsub(r));
        }
        return out;
    }

    /** True while a session this feed minted holds {@code seat}. */
    public boolean isMinted(int seat) {
        return seat >= 0 && seat < mintedPerSeat.length && mintedPerSeat[seat] > 0;
    }

    /**
     * Does the reel hold frame (round, tick)? First drops LOUD every buffered row positioned before the
     * frame (no site claimed it), then reads ahead until the frame's PRE TAKES row is buffered (true) or
     * a row past it / the reel's end (false). After true, every PRE claim of this frame is a pure buffer
     * operation (the TAKES row is the frame's last PRE row).
     */
    public boolean hasFrame(int round, long tick) throws IOException {
        for (Iterator<Row> it = lookahead.iterator(); it.hasNext(); ) {
            Row r = it.next();
            if (comparePosition(r, round, tick, MatchRecordRows.BAND_PRE) < 0 && !isWrapOf(r, round)) {
                it.remove();
                dropUnconsumed(r);
            }
        }
        return bufferFrame(round, tick);
    }

    /**
     * The round's WRAP row sits BEFORE the frame after the reel's last tick, yet it is not stale: when
     * the reel ends the round first, {@link #hasFrame} runs past that frame and {@link #wrapPicks} claims
     * the row afterwards (the F4 shape). Both drop paths keep it; a WRAP row of an EARLIER round is dropped.
     */
    private static boolean isWrapOf(Row r, int round) {
        return r.kind() == MatchRecordRows.KIND_WRAP && r.round() == round;
    }

    /**
     * The frame's CMD rows in file order, as the operator-command parse the serve would have polled. Each
     * row is re-built through the live grammar ({@link #liveCommandOrNull}): a row no live mint produces
     * refuses the reel, and so does a verb name this build lacks. Neither refusal echoes reel text (the
     * message carries the row's index in the frame and a length) nor chains a cause whose message would.
     *
     */
    public OperatorCommandFile.ParseResult commandsAt(int round, long tick) throws IOException {
        bufferFrame(round, tick);
        List<OperatorCommandFile.Command> commands = new ArrayList<>();
        for (Row r : claimAll(MatchRecordRows.KIND_CMD, round, tick, MatchRecordRows.BAND_PRE)) {
            MatchRecordRows.CmdRow c;
            try {
                c = MatchRecordRows.decodeCmd(r.payload());
            } catch (IllegalArgumentException e) {
                throw malformed(r, e);
            }
            OperatorCommandFile.Verb verb;
            try {
                verb = OperatorCommandFile.Verb.valueOf(c.verbName());
            } catch (IllegalArgumentException unknownName) {
                // the name is reel data: never echoed, and never chained (valueOf's message quotes the name)
                throw new IOException(cmdRowAt(commands.size(), r) + " names a verb this build does not have"
                        + " (name length " + c.verbName().length() + "): a reel from another build");
            }
            OperatorCommandFile.Command command = liveCommandOrNull(verb, c);
            if (command == null) {
                throw new IOException(cmdRowAt(commands.size(), r) + " verb=" + verb + " is no command the live"
                        + " grammar mints (text length " + c.text().length() + "): a forged or corrupt reel");
            }
            commands.add(command);
        }
        return new OperatorCommandFile.ParseResult(commands, List.of());
    }

    private static String cmdRowAt(int index, Row r) {
        return "reel CMD row " + index + " (round=" + r.round() + " tick=" + r.tick() + ")";
    }

    /**
     * The command a recorded CMD row stands for, re-built through the live grammar, or null when no live
     * mint produces the row. The verb's house-file line goes through {@link OperatorCommandFile#parse} and
     * must yield the recorded verb, seat, speed, flag and text exactly; the door's own deposits that grammar
     * never mints pass through the parser guarding that deposit instead ({@link #doorDepositLawful}). The
     * recorded receipt session rides through (the file grammar always mints 0). WHY: a reel is an
     * operator-supplied file and the serve prints a command's text in its rows, so only text a live mint
     * could carry may reach them.
     *
     * <p>A key-bearing row the reel writer cut at its byte cap fails both checks yet stands for a live mint:
     * {@link #cutKeyRowLawful} admits it with its text still cut (never repaired) and {@code cmd_text_cut}
     * counts it on the summary.
     *
     * <p>A row whose cid key the serve recorded as its digest fails those checks too: {@link #digestedKeyRowLawful}
     * is tried last and admits it with the digest kept as its text. A reel written before keys were digested
     * still carries raw keys and replays through the first two checks unchanged.
     *
     * <p>A ban-cid's or cid unban's digest is the grammar's own key form, so that row passes the first check;
     * its older raw key passes it too when the key names a client id ({@link #cidBanKeyDigested}), the text
     * still kept as recorded.
     *
     */
    private OperatorCommandFile.Command liveCommandOrNull(OperatorCommandFile.Verb verb,
            MatchRecordRows.CmdRow c) {
        boolean lawful = mintLawful(verb, c);
        if (!lawful && cutKeyRowLawful(verb, c)) {
            cmdTextCut++;
            lawful = true;
        }
        lawful = lawful || digestedKeyRowLawful(verb, c);
        return lawful ? new OperatorCommandFile.Command(verb, c.seatId(), c.simSpeed(), c.autoOn(), c.text(),
                c.receiptSessionId()) : null;
    }

    /**
     * True when a live mint produces {@code c}: its house-file line parses to exactly the recorded verb, seat,
     * speed, flag and text, or it is a door deposit its own parser admits ({@link #doorDepositLawful}).
     *
     * <p>A ban-cid's or cid unban's recorded text may instead equal the parsed text once its raw hex key is
     * read as the digest the grammar now mints from it ({@link #cidBanKeyDigested}).
     *
     */
    private static boolean mintLawful(OperatorCommandFile.Verb verb, MatchRecordRows.CmdRow c) {
        OperatorCommandFile.Command unreceipted = new OperatorCommandFile.Command(verb, c.seatId(), c.simSpeed(),
                c.autoOn(), c.text(), 0L);
        List<OperatorCommandFile.Command> parsed = OperatorCommandFile.parse(List.of(houseLine(verb, c))).commands();
        return (parsed.size() == 1 && (parsed.get(0).equals(unreceipted)
                || parsed.get(0).equals(cidBanKeyDigested(unreceipted)))) || doorDepositLawful(verb, c);
    }

    /**
     * {@code c} with a ban-cid's or cid unban's key token passed through {@link OperatorCommandFile#recordedText}
     * (a hex key that decodes to a client id becomes that id's digest, a digest stays); every other verb's
     * command unchanged, since their keys stay the raw hex the grammar mints. WHY: the grammar now mints the
     * bans file's digest from a typed hex key, while a reel recorded before that still carries the raw key, and
     * its row must still compare equal to the mint it stood for. A hex key whose bytes are no text has no digest
     * and the grammar refuses it, so such a row compares unequal.
     *
     */
    private static OperatorCommandFile.Command cidBanKeyDigested(OperatorCommandFile.Command c) {
        boolean cidBan = c.verb() == OperatorCommandFile.Verb.BAN_CID || c.verb() == OperatorCommandFile.Verb.UNBAN;
        return cidBan ? new OperatorCommandFile.Command(c.verb(), c.seatId(), c.simSpeed(), c.autoOn(),
                OperatorCommandFile.recordedText(c), c.receiptSessionId()) : c;
    }

    /** The lawful one-byte cid key a digest token stands in as while {@link #digestedKeyRowLawful} re-parses a row. */
    private static final String DIGEST_PLACEHOLDER_KEY = "00";

    /**
     * A row whose cid-key token the serve recorded as its digest ({@link OperatorCommandFile#recordedText}): the
     * live grammar refuses it ({@code h1:} is off the kick, ban and session-ban key alphabet; the grammar takes a
     * ban-cid's or cid unban's digest, so of those only a ban retime's arrives here) yet it stands for a live
     * mint. Lawful iff the verb is one whose key the recording digests (kick, ban, ban-src, ban-cid, a session ban, a cid unban), the
     * token at that verb's key position is a digest ({@link OperatorCommandFile#isKeyDigest}: {@code h1:} then
     * exactly 32 lowercase hex), and the row with that ONE token replaced by the placeholder key
     * {@value #DIGEST_PLACEHOLDER_KEY} passes {@link #mintLawful} — so the seat, speed, flag and every other token
     * still obey a live mint, and a digest anywhere else (a session id, a term, a source key, a verb that carries
     * no key) refuses. The caller keeps the digest as the command's text: a replay door never compares a kick or
     * ban key (its occupants carry the door's own client ids), and recording it again yields the same row. WHY: the
     * reel must not carry the target's reversible key, and the host's recording of a kick or ban must still replay.
     *
     */
    private static boolean digestedKeyRowLawful(OperatorCommandFile.Verb verb, MatchRecordRows.CmdRow c) {
        String t = c.text();
        int at = switch (verb) {
            case KICK, BAN, BAN_SRC, BAN_CID -> 0;
            case BAN_SESSION -> 1;
            case UNBAN -> t.startsWith(BanSet.kindToken(BanSet.Kind.CID) + " ") ? 1 : -1;
            default -> -1;
        };
        String[] tokens = t.split(" ", -1);
        if (at < 0 || at >= tokens.length || !OperatorCommandFile.isKeyDigest(tokens[at])) {
            return false;
        }
        tokens[at] = DIGEST_PLACEHOLDER_KEY;
        return mintLawful(verb, new MatchRecordRows.CmdRow(c.verbName(), c.seatId(), c.simSpeed(), c.autoOn(),
                c.receiptSessionId(), String.join(" ", tokens)));
    }

    /**
     * A key-bearing row whose text the reel writer cut at {@link MatchRecordRows#UTF_MAX_BYTES} bytes: a cid
     * key runs to {@code 2 * BanSet.CID_MAX_BYTES} hex chars, so a long one leaves an odd-length key or a
     * half-written term the live parse refuses. Lawful iff the verb carries a cid key (kick, ban, ban-src,
     * unban, a session ban, a cid ban), the text is EXACTLY at the cap (every char these grammars mint is one
     * byte, so the cut lands there and nowhere else), the text is a prefix of that verb's own grammar (its
     * complete leading tokens, then a prefix of the next one), and the seat, speed and flag obey the laws that
     * verb's mints obey. WHY: one long client id must not make the host's recording of its match unreplayable.
     *
     */
    private static boolean cutKeyRowLawful(OperatorCommandFile.Verb verb, MatchRecordRows.CmdRow c) {
        String t = c.text();
        int seat = c.seatId();
        boolean atCap = MatchRecordRows.utfEncodedLength(t) == MatchRecordRows.UTF_MAX_BYTES;
        if (!atCap || Float.floatToRawIntBits(c.simSpeed()) != 0 || c.autoOn() || seat < 0) {
            return false;
        }
        switch (verb) {
            case KICK, BAN, BAN_SRC: {
                // the house grammar's key-less line carries the same seat law as the keyed one
                List<OperatorCommandFile.Command> keyless = OperatorCommandFile.parse(List.of(houseLine(verb,
                        new MatchRecordRows.CmdRow(c.verbName(), seat, 0f, false, 0L, "")))).commands();
                return keyless.equals(List.of(new OperatorCommandFile.Command(verb, seat, 0f, false, "", 0L)))
                        && hexRun(t, 0);
            }
            case UNBAN: {
                // a source key is 40 chars at most, so only the cid kind reaches the cap
                String kind = BanSet.kindToken(BanSet.Kind.CID) + " ";
                return seat == 0 && t.startsWith(kind) && hexRun(t, kind.length());
            }
            case BAN_CID:
                return seat == 0 && keyThenTermPrefix(t, 0, BanSet::isCidKey);
            case BAN_SESSION: {
                int space = t.indexOf(' ');
                long sessionId;
                try {
                    sessionId = space > 0 ? Long.parseLong(t.substring(0, space)) : 0L;
                } catch (NumberFormatException notALong) {
                    return false; // the deposit parser refuses it too: the caller refuses the reel, naming the row
                }
                return seat <= OperatorCommandFile.POD_TEST_MAX_SEAT && sessionId != 0L
                        && keyThenTermPrefix(t, space + 1, key -> BanSet.cidOfKeyOrNull(key) != null);
            }
            default:
                return false;
        }
    }

    /**
     * A chat deposit's tail from {@code from} on, cut anywhere: a cid-key prefix alone, or a complete key
     * ({@code keyLaw}: the parser's own key rule for that deposit) then one space and a prefix of
     * {@code term=<ladder token>}.
     *
     */
    private static boolean keyThenTermPrefix(String t, int from, Predicate<String> keyLaw) {
        int space = t.indexOf(' ', from);
        if (space < 0) {
            return hexRun(t, from);
        }
        if (!keyLaw.test(t.substring(from, space))) {
            return false;
        }
        String tail = t.substring(space + 1);
        for (BanTerm term : BanTerm.values()) {
            if ((OperatorCommandFile.TERM_PREFIX + term.token).startsWith(tail)) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code t} from {@code from} on is a non-empty run of the cid-key alphabet {@code [0-9a-f]}. */
    private static boolean hexRun(String t, int from) {
        for (int i = from; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (!((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f'))) {
                return false;
            }
        }
        return from < t.length();
    }

    /**
     * The house-file line that mints {@code c} under {@code verb}: the grammar's own verb word and argument
     * order. A verb without a seat, speed, flag or text argument leaves it out, so a recorded non-default
     * value fails the equality in {@link #liveCommandOrNull}. TELEPORT has no house-file form (""). The
     * switch is exhaustive on purpose: a new verb fails the compile until its line is written here.
     *
     */
    private static String houseLine(OperatorCommandFile.Verb verb, MatchRecordRows.CmdRow c) {
        String t = c.text();
        String seat = Integer.toString(c.seatId());
        String onOff = c.autoOn() ? "on" : "off";
        return switch (verb) {
            case KICK -> "kick " + seat + " " + t;
            case BAN -> "ban " + seat + " " + t;
            case BAN_SRC -> "ban-src " + seat + " " + t;
            case BAN_CID -> "ban-cid " + t;
            case ADMIN_CID -> "admin-cid " + t;
            case BAN_SESSION -> "ban-session " + t;
            case BAN_IP -> "ban-ip " + t;
            case UNBAN -> "unban " + t;
            case BANS -> "bans";
            case END_ROUND -> "end-round";
            case END_MATCH -> "end-match";
            case SET_SIM_SPEED -> "set-sim-speed " + Float.toString(c.simSpeed());
            case SET_SIM_SPEED_AUTO -> "sim-speed-auto " + onOff;
            case SKIP_WAVE -> "skip-wave";
            case PAUSE_WAVE -> "pause-wave";
            case VOICE -> "voice " + t;
            case SET_JOINER_DEFAULT -> "set-joiner-default " + (t.isEmpty() ? "none" : t);
            case SPAWN_FOE -> "spawn-foe " + t;
            case SPAWN_FRIEND -> "spawn-friend " + t;
            case DESPAWN -> "despawn " + t;
            case SET_WAVE_CATEGORY -> "wave-category " + t;
            case SET_WAVE_NUMBER -> "wave-set " + t;
            case SET_ENDLESS -> "endless " + t;
            case SET_WAVE_INFLATE -> "wave-inflate " + t;
            case SET_KNOB -> "knob " + t;
            case SET_TERRAIN -> "terrain " + t;
            case POD_TEST -> "pod-test " + t + " " + seat;
            case GOD_MODE -> "god-mode " + seat + " " + t;
            case TELEPORT -> "";
            case SWITCH_TEAM -> "switch-team " + seat + " " + t;
            case TEAM_PICK -> "team-pick " + seat + " " + t;
            case CUSTOM_SUBMISSIONS -> "custom-submissions " + onOff;
            case CUSTOM_REJECT -> "custom-reject " + seat;
            case CUSTOM_ALLOW -> "custom-allow " + seat;
            case CUSTOM_BAN -> "custom-ban " + t;
            case CUSTOM_EXPORT -> "custom-export " + t;
            case CUSTOM_UNBAN -> "custom-unban " + t;
            case CUSTOM_KEEP -> "custom-keep " + t;
            case CUSTOM_KEEP_EXPORT -> "custom-keep-export " + t;
            case CUSTOM_FORGET -> "custom-forget " + t;
            // The ship-list verbs, each the exact line its parse arm reads.
            case SHIP_HIDE -> "ship-hide " + t;
            case SHIP_SHOW -> "ship-show " + t;
            case SHIP_REMOVE -> "ship-remove " + t;
            case SHIP_RESTORE -> "ship-restore " + t;
            case SHIP_LIST -> "ship-list";
            case SHIP_KEEP -> "ship-keep " + t;
        };
    }

    /**
     * A row the house-file grammar never mints yet the live serve records: the door's own deposits, each
     * checked by the parser that guards it. An armed admin key records as {@link OperatorCommandFile#REDACTED};
     * a chat teleport carries the pose token {@link ChatCommands#parse} admits; a bare chat god-mode carries
     * that grammar's toggle word; a vacated seat's team latch clears with {@link SeatTeamLane#MODE_CLEAR}; a
     * chat ban carries the session deposit {@link OperatorCommandFile#parseBanSessionDepositOrNull} reads; a
     * chat ban retime carries a cid key plus its term. Every deposit is speed 0, flag off, seat in the
     * match's seat range (a caller's or a vacated seat is 1 or more).
     *
     */
    private static boolean doorDepositLawful(OperatorCommandFile.Verb verb, MatchRecordRows.CmdRow c) {
        String t = c.text();
        int seat = c.seatId();
        if (Float.floatToRawIntBits(c.simSpeed()) != 0 || c.autoOn() || seat < 0
                || seat > OperatorCommandFile.POD_TEST_MAX_SEAT) {
            return false;
        }
        switch (verb) {
            case ADMIN_CID:
                return seat == 0 && OperatorCommandFile.REDACTED.equals(t);
            case TELEPORT:
                return seat >= 1 && t.equals(ChatCommands.parse("/teleport " + t).pose());
            case GOD_MODE:
                return seat >= 1 && t.equals(ChatCommands.parse("/godmode").arg());
            case SWITCH_TEAM:
                return seat >= 1 && SeatTeamLane.MODE_CLEAR.equals(t);
            case BAN_SESSION:
                return OperatorCommandFile.parseBanSessionDepositOrNull(t) != null;
            case BAN_CID: {
                OperatorCommandFile.BanCidDeposit d = OperatorCommandFile.parseBanCidDepositOrNull(t);
                return seat == 0 && d != null && d.termOrNull() != null && BanSet.isCidKey(d.key());
            }
            default:
                return false;
        }
    }

    /**
     * Apply the frame's SESS rows in file order. DEAD ends the session if the door still holds it (a
     * replayed kick or ban at the previous frame's command point already ended the sessions
     * {@link #commandDeathsAhead} named; the serve re-derives the death either way) and forgets it. LIVE mints a session this feed has not minted yet, then clears the seat's tenure
     * gate at the recorded admission frame (observers hold seat 0 and have none).
     */
    public void applySessions(SessionDoor door, int round, long tick) throws IOException {
        bufferFrame(round, tick);
        for (Row r : claimAll(MatchRecordRows.KIND_SESS, round, tick, MatchRecordRows.BAND_PRE)) {
            MatchRecordRows.SessRow s = decodeSess(r);
            if (s.event() == MatchRecordRows.SESS_DEAD) {
                if (door.isLive(s.sessionId())) {
                    door.die(s.sessionId(), s.departureReason());
                }
                Integer seat = seatBySession.remove(s.sessionId());
                if (seat != null) {
                    mintedPerSeat[seat]--;
                }
            } else if (s.event() == MatchRecordRows.SESS_LIVE) {
                if (!seatBySession.containsKey(s.sessionId())) {
                    mintRecorded(door, s);
                }
                if (s.seat() > 0) {
                    door.clearTenureGate(s.seat());
                }
            } else {
                throw new IOException("reel SESS row with event " + s.event() + " (round=" + r.round()
                        + " tick=" + r.tick() + "): neither LIVE nor DEAD");
            }
        }
    }

    /** One session the reel's next frame ends for an operator verb: its signed id and its reason token. */
    public record CommandDeath(long sessionId, String departureReason) {
    }

    /**
     * The sessions the reel's NEXT frame (round, tick + 1) ends with the reason an operator kick
     * ({@code operator_kick}) or ban ({@code banned}) stamps, in file order, read WITHOUT claiming them
     * ({@link #applySessions} still claims them there). Why: a live kick or ban ends its session at the
     * frame's command point, after that frame's session edges were observed, so its DEAD row lands at the
     * next frame; the replayed verb ends the session in the frame it ran by taking this list as the
     * recording's answer (a replay door cannot redo the live key / source match). Reads ahead only when
     * this frame carries a CMD row (every kick and ban rides one); otherwise, or when the next frame is not
     * tick + 1 of this round (a round change), the list is empty and the DEAD row ends the session at the
     * next frame as before. Call after {@link #applySessions} of the same frame.
     *
     */
    public List<CommandDeath> commandDeathsAhead(int round, long tick) throws IOException {
        boolean commandFrame = false;
        for (Row r : lookahead) {
            commandFrame |= r.kind() == MatchRecordRows.KIND_CMD && keyMatches(r, round, tick, MatchRecordRows.BAND_PRE);
        }
        if (!commandFrame) {
            return List.of();
        }
        bufferThrough(round, tick + 1, MatchRecordRows.BAND_PRE);
        List<CommandDeath> out = new ArrayList<>(2);
        for (Row r : lookahead) {
            if (r.kind() != MatchRecordRows.KIND_SESS || !keyMatches(r, round, tick + 1, MatchRecordRows.BAND_PRE)) {
                continue;
            }
            MatchRecordRows.SessRow s = decodeSess(r);
            if (s.event() == MatchRecordRows.SESS_DEAD && ("operator_kick".equals(s.departureReason())
                    || "banned".equals(s.departureReason()))) {
                out.add(new CommandDeath(s.sessionId(), s.departureReason()));
            }
        }
        return out;
    }

    /**
     * The variant override the recorded hull deploy of {@code seat} used this frame: the FLYIN row's
     * {@code overrideIn} ("" = none = null). A module claim or transfer defer (the row's other paths)
     * and a missing row are inputs the reel does not carry: null, counted, one stdout row per round.
     */
    public String flyInOverrideOrNull(int round, long tick, int seat) throws IOException {
        bufferFrame(round, tick);
        int path = 0;
        for (Iterator<Row> it = lookahead.iterator(); it.hasNext(); ) {
            Row r = it.next();
            if (r.kind() != MatchRecordRows.KIND_FLYIN || !keyMatches(r, round, tick, MatchRecordRows.BAND_PRE)) {
                continue;
            }
            MatchRecordRows.FlyInRow f;
            try {
                f = MatchRecordRows.decodeFlyIn(r.payload());
            } catch (IllegalArgumentException e) {
                throw malformed(r, e);
            }
            if (f.seat() != seat) {
                continue;
            }
            it.remove();
            rowsConsumed++;
            if (f.path() == MatchRecordRows.FLYIN_HULL_DEPLOY) {
                return f.overrideIn().isEmpty() ? null : f.overrideIn();
            }
            path = f.path();
            break;
        }
        flyInUnrecorded++;
        if (firstThisRound(SLOT_FLYIN, round)) {
            System.out.println("npeer-serve replay_flyin_unrecorded seat=" + seat + " path=" + path + " round="
                    + round + " tick=" + tick + " (first this round)");
        }
        return null;
    }

    /**
     * The live-roster ratio for the wave stage's supplier: the recorder writes a ROSTER row only when
     * the value changes, so the feed CARRIES the last row's ratio through the round. A round with no
     * row yet carries {@code fallback} (one stdout row, counted). No read: {@link #hasFrame} buffered
     * the frame, and ROSTER rows are validated when read.
     */
    public double rosterRatio(int round, long tick, DoubleSupplier fallback) {
        Row r = claimBuffered(MatchRecordRows.KIND_ROSTER, round, tick, MatchRecordRows.BAND_PRE);
        if (r != null) {
            rosterCarried = MatchRecordRows.decodeRoster(r.payload()).ratio();
            rosterRound = round;
        } else if (rosterRound != round) {
            rosterUnrecorded++;
            System.out.println("npeer-serve replay_roster_unrecorded round=" + round + " tick=" + tick);
            rosterCarried = fallback.getAsDouble();
            rosterRound = round;
        }
        return rosterCarried;
    }

    /**
     * Claim the frame's PRE TAKES row (tick coverage: one per served frame, in-frame ordinal 0), drop
     * LOUD any other row of the frame's PRE band no site claimed, then read through the frame's POST
     * band so the /suicide claims are pure buffer operations.
     *
     * @throws IOException no TAKES row for the frame (call {@link #hasFrame} first), a non-zero ordinal,
     *         a malformed row
     */
    public MatchRecordRows.TakesRow takePre(int round, long tick) throws IOException {
        bufferFrame(round, tick);
        Row r = claimBuffered(MatchRecordRows.KIND_TAKES, round, tick, MatchRecordRows.BAND_PRE);
        if (r == null) {
            throw new IOException("reel holds no TAKES row for round=" + round + " tick=" + tick);
        }
        MatchRecordRows.TakesRow row = decodeTakes(r);
        if (row.gateOrdinal() != 0) {
            throw new IOException("reel TAKES row of round=" + round + " tick=" + tick + " carries gate ordinal "
                    + row.gateOrdinal() + " in the PRE band (in-frame rows carry 0)");
        }
        for (Iterator<Row> it = lookahead.iterator(); it.hasNext(); ) {
            Row left = it.next();
            if (keyMatches(left, round, tick, MatchRecordRows.BAND_PRE)) {
                it.remove();
                dropUnconsumed(left);
            }
        }
        bufferThrough(round, tick, MatchRecordRows.BAND_POST);
        return row;
    }

    /**
     * Did the recording drain a /suicide for {@code seat} this frame? Claims the POST SUIC row naming
     * the seat and keeps it for {@link #noteSuicideOutcome}. {@code doorFlag} = the replay door's own
     * flag, which nothing should set (a stray, counted). No read: {@link #takePre} buffered the band.
     */
    public boolean suicideRequested(int round, long tick, int seat, boolean doorFlag) {
        if (doorFlag) {
            strayDeposits++;
        }
        if (suicideRound != round || suicideTick != tick) {
            suicideKept.clear();
            suicideRound = round;
            suicideTick = tick;
        }
        for (Iterator<Row> it = lookahead.iterator(); it.hasNext(); ) {
            Row r = it.next();
            if (r.kind() != MatchRecordRows.KIND_SUIC || !keyMatches(r, round, tick, MatchRecordRows.BAND_POST)) {
                continue;
            }
            MatchRecordRows.SuicRow s = MatchRecordRows.decodeSuic(r.payload());
            if (s.seat() == seat) {
                it.remove();
                rowsConsumed++;
                suicideKept.put(seat, s);
                return true;
            }
        }
        return false;
    }

    /**
     * Compare the replay's /suicide outcome + ship with the kept row's; a mismatch (or no kept row:
     * recorded outcome 0) is counted, one stdout row per round.
     */
    public void noteSuicideOutcome(int round, long tick, int seat, long shipId, int outcome) {
        MatchRecordRows.SuicRow kept = suicideRound == round && suicideTick == tick ? suicideKept.remove(seat) : null;
        if (kept != null && kept.outcome() == outcome && kept.shipId() == shipId) {
            return;
        }
        suicideOutcomeMismatch++;
        if (firstThisRound(SLOT_SUICIDE, round)) {
            System.out.println("npeer-serve replay_suicide_outcome_mismatch seat=" + seat + " recorded="
                    + (kept == null ? 0 : kept.outcome()) + " recomputed=" + outcome + " recorded_ship="
                    + (kept == null ? 0L : kept.shipId()) + " recomputed_ship=" + shipId + " round=" + round
                    + " tick=" + tick + " (first this round; later ones count only)");
        }
    }

    /**
     * Re-apply the frame's PRE TRANSFER rows (the begins / return-homes that reached the arbiter off the
     * sim thread) in file order, each through {@link SeatRebindArbiter#replayTransfer}: the live law runs
     * with the recorded inputs and the recomputed row lands in the arbiter's journal, which the serve's
     * drain then records into this replay's own reel. A recomputed outcome or deny reason that differs
     * from the recorded pair is counted, one stdout row per round (numbers only: the hull label is text).
     *
     *
     * @throws IOException a row naming a verb this build does not have (a reel from another build)
     */
    public void replayTransfers(int round, long tick, SeatRebindArbiter arbiter) throws IOException {
        bufferFrame(round, tick);
        for (Row r : claimAll(MatchRecordRows.KIND_TRANSFER, round, tick, MatchRecordRows.BAND_PRE)) {
            MatchRecordRows.TransferRow recorded;
            try {
                recorded = MatchRecordRows.decodeTransfer(r.payload());
            } catch (IllegalArgumentException e) {
                throw malformed(r, e);
            }
            if (recorded.verb() != MatchRecordRows.TRANSFER_VERB_BEGIN
                    && recorded.verb() != MatchRecordRows.TRANSFER_VERB_RETURN_HOME) {
                throw new IOException("reel TRANSFER row names verb " + recorded.verb() + " (round=" + r.round()
                        + " tick=" + r.tick() + "): a reel from another build");
            }
            MatchRecordRows.TransferRow recomputed = arbiter.replayTransfer(recorded);
            // a return-home's targetHullId is the law's OUTPUT (the flight target it found), compared like the
            // verdict; a begin's is the recorded input
            if (recomputed.outcome() == recorded.outcome() && recomputed.denyReason() == recorded.denyReason()
                    && (recorded.verb() != MatchRecordRows.TRANSFER_VERB_RETURN_HOME
                            || recomputed.targetHullId() == recorded.targetHullId())) {
                continue;
            }
            transferOutcomeMismatch++;
            if (firstThisRound(SLOT_TRANSFER, round)) {
                System.out.println("npeer-serve replay_transfer_outcome_mismatch seat=" + recorded.seat()
                        + " recorded=" + recorded.outcome() + "/" + recorded.denyReason() + " recomputed="
                        + recomputed.outcome() + "/" + recomputed.denyReason()
                        + (recorded.verb() == MatchRecordRows.TRANSFER_VERB_RETURN_HOME
                                ? " target=" + recorded.targetHullId() + "/" + recomputed.targetHullId() : "")
                        + " round=" + round + " tick=" + tick
                        + " (first this round; later ones count only)");
            }
        }
    }

    public long transferOutcomeMismatches() {
        return transferOutcomeMismatch;
    }

    /** The reel has no frame where the re-simulation wanted one: the reel's round ended first. */
    public void noteReelEndedRound(int round, long tick) {
        roundEndMismatch++;
        System.out.println("npeer-serve replay_round_end_mismatch side=reel round=" + round + " tick=" + tick);
    }

    /**
     * The re-simulation closed {@code round} after {@code lastTick}: drop (counted, never buffered)
     * every row of the round still ahead of its WRAP row; if any is a frame's PRE TAKES row, the
     * recording served frames the replay did not (one stdout row naming the first and the count).
     * Stops at, and buffers, the WRAP row, a later round's row, or the reel's end.
     */
    public void noteRoundClosed(int round, long lastTick) throws IOException {
        long framesLeft = 0;
        long firstLeftTick = 0;
        for (Iterator<Row> it = lookahead.iterator(); it.hasNext(); ) {
            Row r = it.next();
            if (aheadOfWrap(r, round)) {
                it.remove();
                if (isPreTakes(r) && framesLeft++ == 0) {
                    firstLeftTick = r.tick();
                }
                dropUnconsumed(r);
            }
        }
        while (lastPulled == null || aheadOfWrap(lastPulled, round)) {
            Row r = pull(round, lastTick, MatchRecordRows.BAND_WRAP);
            if (r == null) {
                break;
            }
            if (aheadOfWrap(r, round)) {
                lookahead.pollLast();
                if (isPreTakes(r) && framesLeft++ == 0) {
                    firstLeftTick = r.tick();
                }
                dropUnconsumed(r);
            }
        }
        if (framesLeft > 0) {
            roundEndMismatch++;
            System.out.println("npeer-serve replay_round_end_mismatch side=replay round=" + round + " tick="
                    + firstLeftTick + " frames_left=" + framesLeft);
        }
    }

    /**
     * The recorded wrap picks of {@code round} (seat to variant pick, "" picks dropped; a fresh map
     * the caller owns). Matched by round: a round has ONE WRAP row, and a WRAP tick other than
     * {@code tick} is the round-end divergence {@link #noteRoundClosed} / {@link #noteReelEndedRound}
     * already count. An absent row returns an empty map; a row whose next round is not
     * {@code round + 1} or whose seats differ from the replay's {@code deployable} still returns its
     * picks; each case is counted with one stdout row.
     */
    public Map<Integer, String> wrapPicks(int round, long tick, SortedSet<Integer> deployable) throws IOException {
        Row r = claimWrap(round, tick);
        Map<Integer, String> picks = new LinkedHashMap<>();
        if (r == null) {
            noteWrapMismatch(round, "absent");
            return picks;
        }
        MatchRecordRows.WrapRow w;
        try {
            w = MatchRecordRows.decodeWrap(r.payload());
        } catch (IllegalArgumentException e) {
            throw malformed(r, e);
        }
        List<Integer> seats = new ArrayList<>(w.picks().size());
        for (MatchRecordRows.WrapPick p : w.picks()) {
            seats.add(p.seat());
            if (!p.pick().isEmpty()) {
                picks.put(p.seat(), p.pick());
            }
        }
        if (w.nextRound() != round + 1) {
            noteWrapMismatch(round, "next_round");
        } else if (!seats.equals(new ArrayList<>(deployable))) {
            noteWrapMismatch(round, "seats");
        }
        return picks;
    }

    /** The take's recorded {@code staged} bit differs from the replay's recomputed one (the serve stages per its own). */
    public void noteStagedMismatch(int round, long tick, int seat, boolean recorded, boolean recomputed) {
        stagedMismatch++;
        if (firstThisRound(SLOT_STAGED, round)) {
            System.out.println("npeer-serve replay_take_staged_mismatch round=" + round + " tick=" + tick + " seat="
                    + seat + " recorded=" + (recorded ? 1 : 0) + " recomputed=" + (recomputed ? 1 : 0)
                    + " (first this round; later ones count only)");
        }
    }

    /** A replay session's door held a deposit (a take, a declaration): the replay door should be sealed. */
    public void noteStray(int seat) {
        strayDeposits++;
    }

    /** A recorded take or declaration names a seat no live replay session holds. */
    public void noteSeatNotLive(int round, long tick, int seat) {
        seatNotLive++;
        if (firstThisRound(SLOT_SEAT_NOT_LIVE, round)) {
            System.out.println("npeer-serve replay_seat_not_live round=" + round + " tick=" + tick + " seat=" + seat
                    + " (first this round; later ones count only)");
        }
    }

    /** A gate take names a seat no frame-0 SESS LIVE row minted (see {@link #preMint}); counted, and still served. */
    public void noteAdmitUnminted(int round, int seat) {
        admitUnminted++;
        if (firstThisRound(SLOT_ADMIT, round)) {
            System.out.println("npeer-serve replay_admit_unminted round=" + round + " seat=" + seat
                    + " (first this round; later ones count only)");
        }
    }

    /**
     * A re-admitted VSUB row whose submit frame carries another submit
     * sequence than the row itself (a tampered or drifted reel): counted, one stdout row per round, numbers
     * only (the row's author key and canonical id are player-derived text).
     */
    public void noteVsubOfferMismatch(int round, int seat, int rowSeq, int frameSeq) {
        vsubOfferMismatch++;
        if (firstThisRound(SLOT_VSUB_OFFER, round)) {
            System.out.println("npeer-serve replay_vsub_offer_mismatch round=" + round + " seat=" + seat + " row_seq="
                    + rowSeq + " frame_seq=" + frameSeq + " (first this round; later ones count only)");
        }
    }

    public long vsubOfferMismatches() {
        return vsubOfferMismatch;
    }

    public long stagedMismatches() {
        return stagedMismatch;
    }

    public long strayDeposits() {
        return strayDeposits;
    }

    /** Rows claimed by a site, plus the spawn rows dropped on read (the differ compares those, not the serve). */
    public long rowsConsumed() {
        return rowsConsumed;
    }

    public long admitUnminted() {
        return admitUnminted;
    }

    /**
     * The summary tokens, after reading the reel's tail payload-skipping: {@code rows_left} counts the
     * row kinds (3..16) never reached, including rows still buffered (read ahead, never claimed).
     * Idempotent: a second call reads nothing and returns the same tokens. {@code cmd_text_cut} counts the
     * CMD rows replayed with a key the writer cut at its byte cap ({@link #cutKeyRowLawful}).
     */
    public String summaryTokens() throws IOException {
        rowsLeft += lookahead.size();
        lookahead.clear();
        while (reelEnd == null) {
            CcwrPullCursor.Record r = cursor.next(false);
            if (r == null) {
                reelEnd = cursor.truncated() ? "truncated" : "eof";
            } else if (r.kind() == WireRecordSink.KIND_END) {
                reelEnd = "end";
            } else if (r.kind() >= WireRecordSink.KIND_ROW_MIN && r.kind() <= MatchRecordRows.KIND_GRACE) {
                rowsLeft++;
            }
        }
        return "rows_consumed=" + rowsConsumed
                + " rows_unconsumed=" + rowsUnconsumed
                + " rows_left=" + rowsLeft
                + " staged_mismatch=" + stagedMismatch
                + " stray_deposits=" + strayDeposits
                + " seat_not_live=" + seatNotLive
                + " admit_unminted=" + admitUnminted
                + " flyin_unrecorded=" + flyInUnrecorded
                + " round_end_mismatch=" + roundEndMismatch
                + " suicide_outcome_mismatch=" + suicideOutcomeMismatch
                + " transfer_outcome_mismatch=" + transferOutcomeMismatch
                + " vsub_offer_mismatch=" + vsubOfferMismatch
                + " wrap_mismatch=" + wrapMismatch
                + " roster_unrecorded=" + rosterUnrecorded
                + " reel_end=" + reelEnd
                + " cmd_text_cut=" + cmdTextCut;
    }

    @Override
    public void close() throws IOException {
        cursor.close();
    }

    // ------------------------------------------------------------------ the row walk

    /**
     * Read records until ONE row is buffered (returned) or the reel ends (null, {@link #reelEnd}
     * latched). DOWN / UP are skipped, a spawn row is dropped and counted (the differ compares the two
     * reels' spawn rows; nothing feeds them), the fed kinds are buffered, every other kind refuses.
     */
    private Row pull(int towardRound, long towardTick, int towardBand) throws IOException {
        while (reelEnd == null) {
            CcwrPullCursor.Record rec = cursor.next(ROW_PAYLOADS);
            if (rec == null) {
                reelEnd = cursor.truncated() ? "truncated" : "eof";
                return null;
            }
            int kind = rec.kind();
            if (kind == WireRecordSink.KIND_DOWN || kind == WireRecordSink.KIND_UP) {
                continue;
            }
            if (kind == WireRecordSink.KIND_END) {
                reelEnd = "end";
                return null;
            }
            if (kind == MatchRecordRows.KIND_FSPN) {
                rowsConsumed++;
                continue;
            }
            if (!isFedKind(kind)) {
                throw new IOException("reel row kind " + kind + " at tick " + rec.tick()
                        + " cannot be re-simulated (fed kinds: 3-8, 11, 12, 14; kind 10 is dropped)");
            }
            if (lookahead.size() >= LOOKAHEAD_MAX_ROWS) {
                throw new IOException("replay lookahead reached its bound of " + LOOKAHEAD_MAX_ROWS
                        + " buffered rows reading toward round=" + towardRound + " tick=" + towardTick + " band="
                        + towardBand + ": the reel holds more rows ahead of that position than a well-formed"
                        + " recording can");
            }
            byte[] p = rec.payloadOrNull();
            Row row;
            try {
                int band = MatchRecordRows.bandOf(p);
                if (band > MatchRecordRows.BAND_WRAP) {
                    throw new IllegalArgumentException("band " + band + " is past WRAP");
                }
                row = new Row(kind, rec.tick(), band, MatchRecordRows.roundOf(p), p);
                // the claims that cannot throw decode SUIC + ROSTER, so a malformed one fails HERE; a
                // TRANSFER row is decoded here too, so a malformed one fails at the read, kind named
                if (kind == MatchRecordRows.KIND_SUIC) {
                    MatchRecordRows.decodeSuic(p);
                } else if (kind == MatchRecordRows.KIND_ROSTER) {
                    MatchRecordRows.decodeRoster(p);
                } else if (kind == MatchRecordRows.KIND_TRANSFER) {
                    MatchRecordRows.decodeTransfer(p);
                } else if (kind == MatchRecordRows.KIND_VSUB) {
                    // A VSUB row is re-admitted from its recorded submit
                    // frame, so one recorded without it refuses the reel here, before any tick; numbers only.
                    MatchRecordRows.VsubRow v = MatchRecordRows.decodeVsub(p);
                    if (!v.hasBody()) {
                        throw new IOException("reel VSUB row kind 8 at tick " + rec.tick() + " round " + row.round()
                                + " seat " + v.seat() + " carries no body (pre-body-tail reel, a refused encode or an"
                                + " over-cap body): cannot be re-simulated");
                    }
                }
            } catch (IllegalArgumentException e) {
                throw new IOException("malformed reel row kind=" + kind + " tick=" + rec.tick() + ": "
                        + e.getMessage(), e);
            }
            lookahead.addLast(row);
            lastPulled = row;
            return row;
        }
        return null;
    }

    private static boolean isFedKind(int kind) {
        return kind == MatchRecordRows.KIND_TAKES || kind == MatchRecordRows.KIND_SESS
                || kind == MatchRecordRows.KIND_CMD || kind == MatchRecordRows.KIND_FLYIN
                || kind == MatchRecordRows.KIND_SUIC || kind == MatchRecordRows.KIND_WRAP
                || kind == MatchRecordRows.KIND_ROSTER || kind == MatchRecordRows.KIND_TRANSFER
                || kind == MatchRecordRows.KIND_VSUB;
    }

    /** Read ahead until the frame's PRE TAKES row is buffered (true) or a row past it / the end (false). */
    private boolean bufferFrame(int round, long tick) throws IOException {
        for (Row r : lookahead) {
            if (isPreTakes(r) && keyMatches(r, round, tick, MatchRecordRows.BAND_PRE)) {
                return true;
            }
        }
        while (lastPulled == null || comparePosition(lastPulled, round, tick, MatchRecordRows.BAND_PRE) <= 0) {
            Row r = pull(round, tick, MatchRecordRows.BAND_PRE);
            if (r == null) {
                return false;
            }
            int at = comparePosition(r, round, tick, MatchRecordRows.BAND_PRE);
            if (at < 0 && !isWrapOf(r, round)) {
                lookahead.pollLast();
                dropUnconsumed(r);
            } else if (at == 0 && isPreTakes(r)) {
                return true;
            }
        }
        return false;
    }

    /** Read ahead until a row positioned after (round, tick, band) has been read, or the reel ends. */
    private void bufferThrough(int round, long tick, int band) throws IOException {
        while (lastPulled == null || comparePosition(lastPulled, round, tick, band) <= 0) {
            if (pull(round, tick, band) == null) {
                return;
            }
        }
    }

    /** Remove + return the first BUFFERED row of the key (no read), counted as consumed. */
    private Row claimBuffered(int kind, int round, long tick, int band) {
        for (Iterator<Row> it = lookahead.iterator(); it.hasNext(); ) {
            Row r = it.next();
            if (r.kind() == kind && keyMatches(r, round, tick, band)) {
                it.remove();
                rowsConsumed++;
                return r;
            }
        }
        return null;
    }

    /** {@link #claimBuffered}, reading ahead until a row past the key has been read. */
    private Row claim(int kind, int round, long tick, int band) throws IOException {
        Row found = claimBuffered(kind, round, tick, band);
        while (found == null && (lastPulled == null || comparePosition(lastPulled, round, tick, band) <= 0)) {
            Row r = pull(round, tick, band);
            if (r == null) {
                break;
            }
            if (r.kind() == kind && keyMatches(r, round, tick, band)) {
                lookahead.pollLast();
                rowsConsumed++;
                found = r;
            }
        }
        return found;
    }

    /** Every BUFFERED row of the key, in file order, removed + counted as consumed. */
    private List<Row> claimAll(int kind, int round, long tick, int band) {
        List<Row> run = new ArrayList<>(2);
        for (Iterator<Row> it = lookahead.iterator(); it.hasNext(); ) {
            Row r = it.next();
            if (r.kind() == kind && keyMatches(r, round, tick, band)) {
                it.remove();
                rowsConsumed++;
                run.add(r);
            }
        }
        return run;
    }

    /** The first WRAP row of {@code round} (any tick), reading ahead until a later round's row or the end. */
    private Row claimWrap(int round, long tick) throws IOException {
        for (Iterator<Row> it = lookahead.iterator(); it.hasNext(); ) {
            Row r = it.next();
            if (r.kind() == MatchRecordRows.KIND_WRAP && r.round() == round && r.band() == MatchRecordRows.BAND_WRAP) {
                it.remove();
                rowsConsumed++;
                return r;
            }
        }
        while (lastPulled == null || lastPulled.round() <= round) {
            Row r = pull(round, tick, MatchRecordRows.BAND_WRAP);
            if (r == null) {
                return null;
            }
            if (r.kind() == MatchRecordRows.KIND_WRAP && r.round() == round && r.band() == MatchRecordRows.BAND_WRAP) {
                lookahead.pollLast();
                rowsConsumed++;
                return r;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    private void mintRecorded(SessionDoor door, MatchRecordRows.SessRow s) {
        door.mint(s.sessionId(), s.seat(), s.shipId(), (s.flags() & MatchRecordRows.SESS_FLAG_OBSERVER) != 0,
                (s.flags() & MatchRecordRows.SESS_FLAG_ADMIN) != 0);
        seatBySession.put(s.sessionId(), s.seat());
        mintedPerSeat[s.seat()]++;
    }

    private void dropUnconsumed(Row r) {
        rowsUnconsumed++;
        if (firstThisRound(r.kind(), r.round())) {
            System.out.println("npeer-serve replay_row_unconsumed kind=" + r.kind() + " band=" + r.band() + " round="
                    + r.round() + " tick=" + r.tick() + " (first of this kind this round; later ones count only)");
        }
    }

    private void noteWrapMismatch(int round, String reason) {
        wrapMismatch++;
        if (firstThisRound(SLOT_WRAP, round)) {
            System.out.println("npeer-serve replay_wrap_mismatch round=" + round + " reason=" + reason);
        }
    }

    /** One loud row per slot per round: true the first time {@code slot} is hit in {@code round}. */
    private boolean firstThisRound(int slot, int round) {
        if (lastLoudRound[slot] == round) {
            return false;
        }
        lastLoudRound[slot] = round;
        return true;
    }

    /** A row of {@code round} (or an earlier one) positioned before the round's WRAP row. */
    private static boolean aheadOfWrap(Row r, int round) {
        return r.round() < round || (r.round() == round && r.band() != MatchRecordRows.BAND_WRAP);
    }

    private static boolean isPreTakes(Row r) {
        return r.kind() == MatchRecordRows.KIND_TAKES && r.band() == MatchRecordRows.BAND_PRE;
    }

    private static boolean keyMatches(Row r, int round, long tick, int band) {
        return r.round() == round && r.tick() == tick && r.band() == band;
    }

    /** (round, tick, band) lexicographic: negative = {@code r} sits before the key. */
    private static int comparePosition(Row r, int round, long tick, int band) {
        if (r.round() != round) {
            return Integer.compare(r.round(), round);
        }
        if (r.tick() != tick) {
            return Long.compare(r.tick(), tick);
        }
        return Integer.compare(r.band(), band);
    }

    private static MatchRecordRows.TakesRow decodeTakes(Row r) throws IOException {
        try {
            return MatchRecordRows.decodeTakes(r.payload());
        } catch (IllegalArgumentException e) {
            throw malformed(r, e);
        }
    }

    private static MatchRecordRows.SessRow decodeSess(Row r) throws IOException {
        try {
            return MatchRecordRows.decodeSess(r.payload());
        } catch (IllegalArgumentException e) {
            throw malformed(r, e);
        }
    }

    private static MatchRecordRows.VsubRow decodeVsub(Row r) throws IOException {
        try {
            return MatchRecordRows.decodeVsub(r.payload());
        } catch (IllegalArgumentException e) {
            throw malformed(r, e);
        }
    }

    private static IOException malformed(Row r, IllegalArgumentException e) {
        return new IOException("malformed reel row kind=" + r.kind() + " round=" + r.round() + " tick=" + r.tick()
                + ": " + e.getMessage(), e);
    }
}
