package cc.starsector.client;

import cc.starsector.server.match.AllSkillsFleetLaw;
import cc.starsector.server.match.AllSkillsFleetLaw.DigestCheck;
import cc.starsector.server.match.AllSkillsFleetLaw.DocumentIds;
import cc.starsector.server.match.AllSkillsFleetLaw.FleetFold;
import cc.starsector.server.match.AllSkillsFleetLaw.FleetKey;
import cc.starsector.server.match.AllSkillsFleetLaw.FleetRecord;
import cc.starsector.server.match.AllSkillsFleetLaw.FoldOutcome;
import cc.starsector.server.match.AllSkillsFleetLaw.FoldResult;
import cc.starsector.server.match.AllSkillsFleetLaw.OpenMismatch;
import cc.starsector.server.match.AllSkillsFleetLaw.Stamp;
import cc.starsector.server.match.AllSkillsFleetLaw.VacateOutcome;
import cc.starsector.server.match.AllSkillsFleetLaw.VacateResult;
import cc.starsector.server.match.MatchDescriptor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/**
 * The client's copy of the round's fleet record, folded from the fielded roster-append rows, and the
 * stamp each hull holds: which side key the server minted that hull's fleet context under.
 *
 * <p>WHY: the server mints every all-skills hull's fleet context from a key over its round record
 * (the document's non-seat rows plus every fielding written so far). The client must mint the SAME
 * key for the same hull, so it replays those writes in log order, per round epoch (the reset count
 * each roster-append frame already carries), and answers per wire id the stamp the server's fielding
 * read: {@code OPEN(side)} for a round-open row (the side's whole round-open key, re-resolved as more
 * round-open rows fold) or {@code KEY} = the side's key through that row. The record, the key, the
 * close and the per-row digest check are the shared law's ({@link AllSkillsFleetLaw.FleetFold}); this
 * class keeps one such fold per epoch in play and the diagnostics around it.
 *
 * <ul>
 * <li>A row of a new epoch starts that epoch's fold; {@link #epochApplied} drops every fold below the
 * newly applied reset. A stamp is overwritten by the next fielded row of its wire id and dropped by
 * that wire id's removal row ({@link #vacate}: the hull left the server's record) or with its epoch; a
 * respawn alone never clears it (the respawn paths run after the next life's row has folded).</li>
 * <li>A row is validated before the law sees it (the law throws on an id below 1 or a side outside
 * 0/1): it is refused and counted {@code bad_row}, never a throw into the caller's drain.</li>
 * <li>The goal a write records is {@link RealizedVariantId#goalOf} of the row's variant id: a
 * realized loadout id keys as the stock variant it was rolled from, any other id as itself.</li>
 * <li>Diagnostics carry bounded tokens only (hex digests, counts, wire ids, epochs; never a host
 * string) and are capped per kind per document; the census counts every one past the cap.</li>
 * </ul>
 * Pure (java.util + the shared law); every method is synchronized (the render thread folds and reads,
 * the mission build begins a document); nothing here waits on anything.
 *
 *
 *
 */
public final class FleetStamps {

    /** The census: every fold outcome and diagnostic counts here (a removal's own outcome counts in the removal
     *  census, {@link #vacateCount}); the token names it in the meter text. */
    public enum Census {
        FOLDED("folded"),
        MISMATCH("mismatch"),
        UNCLOSED("unclosed"),
        /** The law's entries bound: a row that would add a side's record or round-open entry past
         *  {@link AllSkillsFleetLaw#MAX_ENTRIES_PER_SIDE}; no count of changing writes refuses a row. */
        WRITES_BOUND("writes_bound"),
        CLOSED_SIDE("closed_side"),
        BAD_GOAL("bad_goal"),
        BAD_ROW("bad_row"),
        NO_DOCUMENT("no_document"),
        STALE_EPOCH("stale_epoch"),
        FOLD_ERROR("fold_error");

        private final String token;

        Census(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }
    }

    /**
     * Why a seat hull's mint found no stamp of its own, so took the side's CURRENT key (the unstamped census;
     * the token is the row's {@code kind=}). Checked in this order: {@code no_id} = the mint carries no wire id
     * (see {@link #NO_ID}); {@code refused} = the fold holding the hull's newest stamp marked its wire id (see
     * {@link #REFUSED}); {@code late_join} = no fielded row has folded since {@link #begin} (a late joiner's
     * hulls arrive ahead of its catch-up rows); {@code failopen} = the round in play was adopted through
     * {@link #noteFailOpen}; {@code reorder} = anything else (a seat hull that outran its own row; a hull whose
     * wire id was removed ({@link #vacate}) and is minted again before its despawn lands, which takes the side's
     * current key; also every side-0 document AI hull minted on a partial round-open set).
     *
     *
     */
    public enum Unstamped {
        LATE_JOIN("late_join"),
        REORDER("reorder"),
        FAILOPEN("failopen"),
        /**
         * A seat mint with wire id 0 = a mission-build hull: no stamp can name it, whatever has folded or a
         * fail-open mark says. Counted on its own so the build-time mints are read apart from the three wire
         * kinds (a build's own seat mints carry no wire id; counted under {@code late_join} they would make that
         * kind read non-zero on a flight with no late joiner).
         *
         *
         */
        NO_ID("no_id"),
        /**
         * A seat mint of a wire id whose newest stamp predates a row the server took: the fold refused that row
         * ({@code writes_bound}, {@code closed_side}, {@code bad_goal}) or threw on it ({@code fold_error}) while
         * its record held the wire id, so the law marked it. The mint still takes what it would take unmarked (the
         * stamp's key, or the side's current key with no stamp of that side); the count replaces {@code reorder}
         * or an uncounted stamped mint. The mark clears in the fold at the wire id's next accepted row or its
         * removal; this class adds no clearing of its own.
         *
         *
         */
        REFUSED("refused");

        private final String token;

        Unstamped(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }
    }

    /**
     * What a hull mint takes: the key of its context ({@code key}, never null) and the unstamped row to say,
     * or null when the mint is uncounted or its kind is past its row cap (the census still counts it).
     */
    public record MintKey(FleetKey key, String rowOrNull) {
    }

    /**
     * What {@link #fold} or {@link #vacate} answered: the law's fielded-row result ({@code null} when the row
     * never reached the law: {@code bad_row}, {@code no_document}, {@code stale_epoch}, {@code fold_error};
     * always null for a removal, which re-points no hull) and the refusal token ({@code null} = accepted; else a
     * {@link Census#token()}, or {@code vacate_absent} for a removal of a wire id the record does not hold there).
     */
    public record Answer(FoldResult resultOrNull, String refusalOrNull) {
        public boolean accepted() {
            return refusalOrNull == null;
        }
    }

    /** Diagnostic rows per kind (MISMATCH, OPEN-UNCLOSED, REFUSED) per document; past it the census
     *  keeps counting and no row is written (a hostile host cannot flood the log through a fold). */
    public static final int DIAG_ROWS_PER_KIND = 16;
    /** Rows the caller has not taken yet (it takes after every fold); past it a row is counted only. */
    public static final int DIAG_PENDING_MAX = 64;

    private static final int DIAG_MISMATCH = 0;
    private static final int DIAG_UNCLOSED = 1;
    private static final int DIAG_REFUSED = 2;

    private static final FleetStamps SHARED = new FleetStamps();

    /** The app-wide instance: the roster-append lane folds into it, the mission build begins its
     *  document, the fleet-context mint reads it. A smoke builds its own. */
    public static FleetStamps shared() {
        return SHARED;
    }

    /** One epoch's fold plus its diagnostic latches. */
    private static final class EpochFold {
        final FleetFold fold;
        /** A round-open row of either side folded this epoch (arms the unclosed timer). */
        boolean openSeen;
        /** The clock at the first {@link FleetStamps#tick} after the first round-open row; -1 = not read yet. */
        long firstOpenMillis = -1L;
        boolean timerDone;
        final boolean[] unclosedSpoken = new boolean[2];

        EpochFold(FleetFold fold) {
            this.fold = fold;
        }
    }

    /** The match document the folds key over; null = none begun (every row is refused no_document). */
    private DocumentIds document;
    /** Epoch -> its fold, ascending; at most the applied epoch and the ones above it after a drop. */
    private final TreeMap<Long, EpochFold> folds = new TreeMap<Long, EpochFold>();
    /** The newest applied reset's epoch (>= 0), or -1 before the first. */
    private long appliedEpoch = -1L;
    /** A fold saw a round-open row whose unclosed timer has not run out: {@link #tick} reads the clock. */
    private boolean timerArmed;
    private final long[] census = new long[Census.values().length];
    /** The removal census per {@link VacateOutcome} (lifetime, as {@link #census}: no begin or replay resets it).
     * */
    private final long[] vacates = new long[VacateOutcome.values().length];
    private final int[] diagRows = new int[3];
    private final List<String> pending = new ArrayList<String>();
    /** Whether any fielded row folded since the last {@link #begin} (what tells a late joiner apart).
     *  The unstamped census's state, these four fields. */
    private boolean foldedSinceBegin;
    /** The epoch {@link #noteFailOpen} adopted ({@code -1} = none); a later applied epoch clears it. */
    private long failOpenEpoch = -1L;
    /** The unstamped census per {@link Unstamped} kind (lifetime) and its rows written this document. */
    private final long[] unstamped = new long[Unstamped.values().length];
    private final int[] unstampedRows = new int[Unstamped.values().length];
    /**
     * Per side, the OPEN mints taken while the side had folded no round-open row in the round in play: they
     * took the document-only key, which the server never held once it writes a round-open set. A count (never a
     * list), the epoch of the fold they were read from ({@code -1} = none), the key they took and the wire id of
     * the latest ({@code >= 0}). When that side closes in that epoch on another key, each is counted
     * {@link Unstamped#REORDER}; nothing waits for the close. Cleared by {@link #begin}, by a later applied
     * epoch and by the close itself.
     *
     */
    private final long[] openBeforeRows = new long[2];
    private final long[] openBeforeRowsEpoch = {-1L, -1L};
    private final FleetKey[] openBeforeRowsKey = new FleetKey[2];
    private final long[] openBeforeRowsId = new long[2];
    /** Per side, {@link #keyWorkPerRow}: sized once by {@link #begin} from the begun document; both 0 with none.
     * */
    private final int[] keyWorkPerRow = new int[2];

    /**
     * A new match document: every fold, stamp, latch and the applied epoch are cleared (the folds of
     * the old document end first, so an unclosed side of theirs is still spoken). The census is kept:
     * it is the meter's lifetime count. A null or empty document ({@code DocumentIds.of(null)}) =
     * no document: rows are refused and counted {@code no_document} until one begins.
     * <p>It also re-arms the unstamped kinds (nothing folded since
     * this begin, no fail-open mark) and their per-document row caps; their census is kept. The OPEN mints
     * counted toward a later close are dropped with the folds.
     */
    public synchronized void begin(DocumentIds ids) {
        endFoldsBelow(Long.MAX_VALUE);
        document = ids == null || ids.idCount() == 0L ? null : ids;
        appliedEpoch = -1L;
        timerArmed = false;
        Arrays.fill(diagRows, 0);
        foldedSinceBegin = false;
        failOpenEpoch = -1L;
        Arrays.fill(unstampedRows, 0);
        clearOpenBeforeRows(0);
        clearOpenBeforeRows(1);
        // The per-row key-work bound, sized once from this document
        Arrays.fill(keyWorkPerRow, 0);
        List<MatchDescriptor.RosterRow> roster = document == null ? null : document.roster();
        if (roster != null) {
            long[] expanded = new long[2];
            for (MatchDescriptor.RosterRow row : roster) {
                if (row.pilot() != MatchDescriptor.PilotKind.SEAT) {
                    expanded[row.side()] += row.count();
                }
            }
            for (int side = 0; side < 2; side++) {
                keyWorkPerRow[side] = (int) Math.min(Integer.MAX_VALUE,
                        roster.size() + expanded[side] + AllSkillsFleetLaw.MAX_ENTRIES_PER_SIDE);
            }
        }
    }

    /**
     * An O(1) UPPER bound on the key work one fielded or removal row's fold on {@code side} does, read from the
     * begun document alone (no record, no fold state): the roster rows a whole-side key build walks (every document
     * row, both sides) plus the entries a key of that side can hold (the side's non-seat rows expanded by their
     * count, plus at most {@link AllSkillsFleetLaw#MAX_ENTRIES_PER_SIDE} record entries). The unit is one roster
     * row walked or one key entry built, and one fold row builds one such key. Range {@code >= 0}, saturating at
     * {@link Integer#MAX_VALUE}; 0 with no document (every row is then refused before any key is built) and for a
     * side that is not 0 or 1. The roster drain sums it to bound the key work a render pass does.
     *
     */
    public synchronized int keyWorkPerRow(int side) {
        return side == 0 || side == 1 ? keyWorkPerRow[side] : 0;
    }

    /**
     * Restart the fold in play for a replay. A re-admitted session is replayed the whole round log from the
     * round's start, so the fold must rebuild from empty, or every write folds twice: each round-open row is
     * refused {@code closed_side}, each mid-round row re-checks against the final record as a false MISMATCH and
     * its stamp is re-keyed over later entries. So every fold
     * in play (the applied epoch's and any above it) starts over empty, with its record, stamps, latches,
     * round-open rows and unclosed timer, and the per-document diagnostic row caps re-arm. The document, the
     * applied epoch and the lifetime census are kept; the fail-open mark is cleared. A dropped fold is not
     * spoken unclosed: the replay folds it again. The OPEN mints taken before any round-open row are kept: they
     * did take the document-only key, and the replay's close counts them as {@link #fold} would have.
     * <p>Meant for the re-admit edge of {@link RosterAppendLane}, called ahead of the replay's first row.
     *Never throws; never waits.
     *
     *
     */
    public synchronized void restartForReplay() {
        folds.clear();
        if (document != null && appliedEpoch >= 0L) {
            foldOf(appliedEpoch); // a read during the replay already answers the round in play
        }
        timerArmed = false;
        failOpenEpoch = -1L;
        Arrays.fill(diagRows, 0);
        Arrays.fill(unstampedRows, 0);
    }

    /**
     * Fold one fielded row (a fleet-record write) of round {@code epoch} (>= 0, the frame's reset
     * count) in log order. {@code wireId} 1..2^32-1, {@code side} the row's ABSOLUTE side 0/1,
     * {@code carriedDigest} the u32 digest the row carries (its bit pattern; every value lawful).
     * Never throws: a malformed row is refused {@code bad_row}; the law's own refusals are counted
     * under their tokens; a throw inside the law is counted {@code fold_error} and marks the wire id
     * ({@link Unstamped#REFUSED}). A non-round-open row is digest-checked by the law (a MISMATCH is counted and
     * written as a row; the client keeps its own fold) and speaks its side's open key if that side is
     * still unclosed.
     * <p>The row that closes its side counts that side's OPEN mints taken
     * before any round-open row of this epoch, when the side closed on another key ({@link #countOpenBeforeRows}).
     */
    public synchronized Answer fold(long epoch, long wireId, int side, String variantId, boolean roundOpen,
            int carriedDigest) {
        if (epoch < 0L || wireId < 1L || wireId > 0xFFFFFFFFL || (side != 0 && side != 1) || variantId == null) {
            return refuse(Census.BAD_ROW, null, epoch, wireId, side);
        }
        if (document == null) {
            census[Census.NO_DOCUMENT.ordinal()]++; // a wiring state (no document begun), counted only
            return new Answer(null, Census.NO_DOCUMENT.token());
        }
        if (epoch < appliedEpoch) {
            return refuse(Census.STALE_EPOCH, null, epoch, wireId, side);
        }
        EpochFold e = foldOf(epoch);
        boolean wasClosed = e.fold.isClosed(side);
        FoldResult r;
        try {
            r = e.fold.foldFielded(wireId, side, RealizedVariantId.goalOf(variantId), roundOpen, carriedDigest);
        } catch (RuntimeException ex) {
            return foldError(e, ex, epoch, wireId, side);
        }
        if (!r.accepted()) {
            Census c = r.outcome() == FoldOutcome.WRITES_BOUND ? Census.WRITES_BOUND
                    : r.outcome() == FoldOutcome.CLOSED_SIDE ? Census.CLOSED_SIDE : Census.BAD_GOAL;
            return refuse(c, r, epoch, wireId, side);
        }
        census[Census.FOLDED.ordinal()]++;
        foldedSinceBegin = true;
        if (!wasClosed && e.fold.isClosed(side)) {
            countOpenBeforeRows(e, side);
        }
        if (roundOpen) {
            if (!e.openSeen) {
                e.openSeen = true;
                timerArmed = true;
            }
            return new Answer(r, null);
        }
        if (r.check() == DigestCheck.MISMATCH) {
            mismatch(epoch, wireId, side, r.stampOrNull().keyOrNull(), carriedDigest);
        }
        speakUnclosed(e, side, "row");
        return new Answer(r, null);
    }

    /**
     * Fold one REMOVAL row (the hull of {@code wireId} is neither seated nor in the world, so its entry leaves
     * the server's record) of round {@code epoch} in log order: the same never-throwing shell and epoch handling
     * as {@link #fold}. {@code epoch} >= 0 (the frame's reset count), {@code wireId} 1..2^32-1, {@code side} the
     * row's ABSOLUTE side 0/1, {@code carriedDigest} the u32 digest the row carries (the side's key AFTER the
     * removal; its bit pattern, every value lawful). A malformed row is refused {@code bad_row}, a row before any
     * document {@code no_document}, a row of an epoch below the applied one {@code stale_epoch}; a removal of a
     * wire id this epoch's record does not hold on {@code side} is refused {@code vacate_absent} (counted, one
     * capped row, the fold unchanged). A taken removal drops the wire id's entry, its stamp and its refused mark
     * (no other stamp moves; the round-open record and key are untouched), is counted {@code vacated}, and the
     * side's key AFTER the removal is checked against {@code carriedDigest}: a MISMATCH is counted and rowed as a
     * fielded row's is, its {@code have=} that post-removal key (the client keeps its own fold); then the side's
     * open key is spoken if the side is still unclosed, as after a fielded mid-round row. A throw inside the law
     * is counted {@code fold_error} and marks the wire id. The answer's result is always null: a removal stamps
     * and re-points no hull, so {@link Answer#accepted()} (= the entry was removed) is all it says.
     *
     *
     */
    public synchronized Answer vacate(long epoch, long wireId, int side, int carriedDigest) {
        if (epoch < 0L || wireId < 1L || wireId > 0xFFFFFFFFL || (side != 0 && side != 1)) {
            return refuse(Census.BAD_ROW, null, epoch, wireId, side);
        }
        if (document == null) {
            census[Census.NO_DOCUMENT.ordinal()]++; // a wiring state (no document begun), counted only
            return new Answer(null, Census.NO_DOCUMENT.token());
        }
        if (epoch < appliedEpoch) {
            return refuse(Census.STALE_EPOCH, null, epoch, wireId, side);
        }
        EpochFold e = foldOf(epoch);
        VacateResult r;
        try {
            r = e.fold.foldVacated(wireId, side, carriedDigest);
        } catch (RuntimeException ex) {
            return foldError(e, ex, epoch, wireId, side);
        }
        if (!r.removed()) {
            String token = VacateOutcome.VACATE_ABSENT.token();
            refusedRow(token, ++vacates[VacateOutcome.VACATE_ABSENT.ordinal()], epoch, wireId, side);
            return new Answer(null, token);
        }
        vacates[VacateOutcome.VACATED.ordinal()]++;
        if (r.check() == DigestCheck.MISMATCH) {
            mismatch(epoch, wireId, side, r.keyOrNull(), carriedDigest);
        }
        speakUnclosed(e, side, "row");
        return new Answer(null, null);
    }

    /** The removal census count of {@code outcome} (lifetime; {@code >= 0}). */
    public synchronized long vacateCount(VacateOutcome outcome) {
        return vacates[outcome.ordinal()];
    }

    /**
     * A row whose fold THREW: the law marks the wire id as a refused row would (only a wire id its record holds;
     * the mark never throws), so that hull's next seat mint is counted {@link Unstamped#REFUSED}: its stamp
     * predates a row the server took. Then counted {@code fold_error} with its capped row (the exception's simple
     * name, numbers only).
     *
     */
    private Answer foldError(EpochFold e, RuntimeException ex, long epoch, long wireId, int side) {
        e.fold.markFoldError(wireId);
        long n = ++census[Census.FOLD_ERROR.ordinal()];
        diag(DIAG_REFUSED, "fleet_key REFUSED fold_error " + ex.getClass().getSimpleName() + " epoch=" + epoch
                + " id=" + wireId + " side=" + side + " (fold_error=" + n + ")");
        return new Answer(null, Census.FOLD_ERROR.token());
    }

    /** A digest MISMATCH: counted, and one capped row naming the key this fold holds ({@code have}, never null:
     *  the accepted row's stamp key, or a removal's post-removal key) against the carried digest.
     * */
    private void mismatch(long epoch, long wireId, int side, FleetKey have, int carriedDigest) {
        long n = ++census[Census.MISMATCH.ordinal()];
        diag(DIAG_MISMATCH, "fleet_key MISMATCH epoch=" + epoch + " id=" + wireId + " side=" + side
                + " have=" + AllSkillsFleetLaw.hex8(have.digest()) + " want="
                + AllSkillsFleetLaw.hex8(carriedDigest) + " (client keeps its own fold; mismatch=" + n + ")");
    }

    /**
     * The reset of round {@code epoch} was applied: every fold BELOW it ends (an unclosed side of theirs
     * is spoken, then its stamps are dropped) and the epoch's own fold exists from here on (empty if no
     * row of it folded yet), so a read during the reset's rebuild already answers this round. The same
     * epoch again (a respawn inside the round) or a lower one is a no-op: nothing is cleared per life.
     * <p>A later epoch also clears the {@link #noteFailOpen} mark.
     * <p>And drops the OPEN mints counted toward a close.
     */
    public synchronized void epochApplied(long epoch) {
        if (epoch < 0L || epoch < appliedEpoch) {
            return;
        }
        endFoldsBelow(epoch);
        if (epoch > appliedEpoch) { // a later round: the OPEN mints counted toward a close were the earlier one's
            clearOpenBeforeRows(0);
            clearOpenBeforeRows(1);
        }
        appliedEpoch = epoch;
        if (epoch > failOpenEpoch) {
            failOpenEpoch = -1L; // a later round was applied: the fail-open mark was the earlier one's
        }
        if (document != null) {
            foldOf(epoch);
        }
    }

    /**
     * The round {@code epoch} (>= 0) was adopted through the fail-open path (its reset applied before its
     * round-open rows were seen): {@link #epochApplied} plus a mark, so a seat mint of that round with no stamp
     * is counted {@link Unstamped#FAILOPEN}. The mark is cleared when a later epoch is applied or a document
     * begins; an epoch below the applied one is a no-op (it is not the round in play).
     *
     *
     */
    public synchronized void noteFailOpen(long epoch) {
        epochApplied(epoch);
        if (epoch >= 0L && epoch == appliedEpoch) {
            failOpenEpoch = epoch;
        }
    }

    /**
     * The key a SEAT hull's mint takes on absolute {@code side} (0/1), for the hull of {@code wireId}
     * ({@code >= 1} a decoded u32; {@code 0} = no wire id, a mission-build document row): the key its own stamp
     * means now; with no stamp (wire id 0, no row of it folded in the epochs in play, a stamp of another side or
     * an epoch dropped) the side's {@link #currentKey}, counted by its {@link Unstamped} kind (wire id below 1 =
     * {@link Unstamped#NO_ID} before any other test). Null when this
     * fold does not hold {@code build} (no document begun, or another build's): the caller keeps its
     * document-only key, uncounted. Map reads only; never waits.
     * <p>An {@code OPEN} stamp (a round-open row) whose side has not
     * closed in its epoch means the round-open rows folded so far, a partial set the server never held (it
     * writes the whole set before any hull is fielded): that key is still returned, counted
     * {@link Unstamped#REORDER} as {@link #openMint} counts it, its row under the same cap.
     * <p>A wire id the fold holding its newest stamp has marked (a refused
     * or thrown row of it, while that fold's record held it) is counted {@link Unstamped#REFUSED} instead, on the
     * stamped path and the unstamped one alike (a stamp of the other side); the key taken is unchanged.
     *
     *
     */
    public synchronized MintKey seatMint(DocumentIds build, int side, long wireId) {
        if (!holds(build) || (side != 0 && side != 1)) {
            return null;
        }
        Stamp stamp = wireId >= 1L ? stampOrNull(wireId) : null;
        boolean refused = stamp != null && refusedMarked(stamp, wireId);
        FleetKey key = stamp != null && stamp.side() == side ? resolveOrNull(stamp) : null;
        if (key != null) {
            EpochFold of = stamp.kind() == Stamp.Kind.OPEN ? folds.get(Long.valueOf(stamp.epoch())) : null;
            boolean partial = of != null && !of.fold.isClosed(side);
            Unstamped counted = refused ? Unstamped.REFUSED : partial ? Unstamped.REORDER : null;
            return new MintKey(key, counted == null ? null : noteUnstamped(counted, wireId));
        }
        Unstamped kind = wireId < 1L ? Unstamped.NO_ID
                : refused ? Unstamped.REFUSED
                : !foldedSinceBegin ? Unstamped.LATE_JOIN
                : failOpenEpoch >= 0L && failOpenEpoch == appliedEpoch ? Unstamped.FAILOPEN : Unstamped.REORDER;
        return new MintKey(currentKey(side), noteUnstamped(kind, wireId));
    }

    /**
     * The key a side-0 document AI hull's mint takes on {@code side}: {@code OPEN(side)} of the round in play,
     * never unstamped (the document's own rows when no round is in play). If the side has folded at least one
     * round-open row this round and is not closed, the hull was minted on a partial round-open set: counted
     * {@link Unstamped#REORDER}. {@code wireId} names the hull in that row only (0 = a mission-build row). Null
     * when this fold does not hold {@code build}.
     * <p>A mint while the side has folded NO round-open row of the round
     * in play takes the document-only key uncounted, and is noted toward that side's close: if the side
     * closes in that epoch on another key, it is counted then ({@link #countOpenBeforeRows}). Nothing waits.
     * With no fold in play (no round) nothing is noted.
     *
     *
     */
    public synchronized MintKey openMint(DocumentIds build, int side, long wireId) {
        if (!holds(build) || (side != 0 && side != 1)) {
            return null;
        }
        EpochFold e = current();
        FleetKey key = e == null ? null : resolveOrNull(e.fold.openStamp(side));
        if (key == null) {
            return new MintKey(currentKey(side), null);
        }
        long rows = e.fold.openRows(side);
        if (rows == 0L) {
            noteOpenBeforeRows(e.fold.epoch(), side, key, wireId);
        }
        boolean early = rows > 0L && !e.fold.isClosed(side);
        return new MintKey(key, early ? noteUnstamped(Unstamped.REORDER, wireId) : null);
    }

    /** Note one OPEN mint taken before any round-open row of {@code epoch} on {@code side}: the count follows the
     *  round it was read from (a mint of another epoch restarts it), so it stays one counter per side.
     * */
    private void noteOpenBeforeRows(long epoch, int side, FleetKey key, long wireId) {
        if (openBeforeRowsEpoch[side] != epoch) {
            openBeforeRows[side] = 0L;
            openBeforeRowsEpoch[side] = epoch;
        }
        openBeforeRows[side]++;
        openBeforeRowsKey[side] = key;
        openBeforeRowsId[side] = wireId;
    }

    /** Drop the side's note of OPEN mints taken before any round-open row. */
    private void clearOpenBeforeRows(int side) {
        openBeforeRows[side] = 0L;
        openBeforeRowsEpoch[side] = -1L;
        openBeforeRowsKey[side] = null;
        openBeforeRowsId[side] = 0L;
    }

    /**
     * {@code side} just closed in {@code e}: the OPEN mints noted for that epoch before any of its round-open rows
     * took the document-only key. If the side closed on another key, each is counted {@link Unstamped#REORDER}
     * and one row (bounded tokens: kind, the latest wire id, side, the count) is queued for
     * {@link #takeDiagnostics} under the kind's per-document cap. A round-open row always adds a key entry, so
     * today a close is always on another key; the test stands so a mint that did take the closed key is never
     * counted. The note is spent either way. A note of another epoch is left for its own close.
     *
     */
    private void countOpenBeforeRows(EpochFold e, int side) {
        long n = openBeforeRows[side];
        if (n <= 0L || openBeforeRowsEpoch[side] != e.fold.epoch()) {
            return;
        }
        FleetKey took = openBeforeRowsKey[side];
        long id = openBeforeRowsId[side];
        clearOpenBeforeRows(side);
        if (took != null && took.equals(e.fold.openKey(side))) {
            return;
        }
        unstamped[Unstamped.REORDER.ordinal()] += n;
        if (unstampedRows[Unstamped.REORDER.ordinal()] >= DIAG_ROWS_PER_KIND || pending.size() >= DIAG_PENDING_MAX) {
            return;
        }
        unstampedRows[Unstamped.REORDER.ordinal()]++;
        pending.add("allskills fleet_ctx unstamped kind=" + Unstamped.REORDER.token() + " id=" + id + " side=" + side
                + " at=close mints=" + n);
    }

    /** {@link #currentKey} when this fold holds {@code build}, else null (the caller keeps its document-only
     *  key). What a lookup that is not a hull's (the mission commander, a post-spawn pair) follows.
     * */
    public synchronized FleetKey currentKeyOf(DocumentIds build, int side) {
        return holds(build) ? currentKey(side) : null;
    }

    /** The unstamped census count of {@code kind} (lifetime: a new document keeps it; {@code >= 0}). */
    public synchronized long unstampedCount(Unstamped kind) {
        return unstamped[kind.ordinal()];
    }

    /** Whether the document begun here is {@code build}'s: the same roster rows (by identity: the build's own
     *  decoded rows, never an equal-looking other document) and the same seat ids. */
    private boolean holds(DocumentIds build) {
        return document != null && build != null && (document == build
                || (document.roster().equals(build.roster()) && document.seatShipIds().equals(build.seatShipIds())));
    }

    /** Whether the fold that holds {@code stamp} (its epoch's; none once that epoch is dropped) marks
     *  {@code wireId} refused: the law's one read accessor, a set read. */
    private boolean refusedMarked(Stamp stamp, long wireId) {
        EpochFold of = folds.get(Long.valueOf(stamp.epoch()));
        return of != null && of.fold.refusedMarked(wireId);
    }

    /** Count one unstamped mint of {@code kind}; the row (bounded tokens: the kind and the wire id) while the
     *  kind is under its per-document cap, else null. */
    private String noteUnstamped(Unstamped kind, long wireId) {
        unstamped[kind.ordinal()]++;
        if (unstampedRows[kind.ordinal()] >= DIAG_ROWS_PER_KIND) {
            return null;
        }
        unstampedRows[kind.ordinal()]++;
        return "allskills fleet_ctx unstamped kind=" + kind.token() + " id=" + wireId;
    }

    /**
     * The unclosed timer: at the first call after an epoch's first round-open row the clock is read and
     * kept; once more than {@code unclosedAfterMillis} has passed, each side of that epoch still unclosed
     * is spoken (once). A diagnostic clock: nothing waits on it. Returns true when a diagnostic row is
     * waiting for {@link #takeDiagnostics}. The clock is read only while a timer runs, so a per-frame
     * caller pays one uncontended monitor and a flag test otherwise.
     */
    public synchronized boolean tick(LongSupplier clock, long unclosedAfterMillis) {
        if (!timerArmed) {
            return !pending.isEmpty();
        }
        long now = clock.getAsLong();
        boolean armed = false;
        for (EpochFold e : folds.values()) {
            if (!e.openSeen || e.timerDone) {
                continue;
            }
            if (e.firstOpenMillis < 0L) {
                e.firstOpenMillis = now;
                armed = true;
            } else if (now - e.firstOpenMillis > unclosedAfterMillis) {
                e.timerDone = true;
                speakUnclosed(e, 0, "timeout");
                speakUnclosed(e, 1, "timeout");
            } else {
                armed = true;
            }
        }
        timerArmed = armed;
        return !pending.isEmpty();
    }

    /** The diagnostic rows written since the last take (bounded tokens only), oldest first. */
    public synchronized List<String> takeDiagnostics() {
        if (pending.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>(pending);
        pending.clear();
        return out;
    }

    /** The stamp the hull of {@code wireId} holds: its newest fielded row's, searching the epochs in
     *  play from the newest down; null = unstamped (no fielded row of it folded in any epoch in play). */
    public synchronized Stamp stampOrNull(long wireId) {
        for (EpochFold e : folds.descendingMap().values()) {
            Stamp s = e.fold.stampOrNull(wireId);
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    /** {@code OPEN(side)} of the round in play (the applied epoch's fold, else the newest); null with no
     *  fold in play or a side outside 0/1. What a document AI hull of the side holds, always. */
    public synchronized Stamp openStamp(int side) {
        EpochFold e = current();
        return e == null || (side != 0 && side != 1) ? null : e.fold.openStamp(side);
    }

    /** The key {@code stamp} means NOW ({@code OPEN} re-resolved against its epoch's round-open set);
     *  null for a null stamp or one whose epoch was dropped. */
    public synchronized FleetKey resolveOrNull(Stamp stamp) {
        if (stamp == null) {
            return null;
        }
        EpochFold e = folds.get(Long.valueOf(stamp.epoch()));
        return e == null ? null : e.fold.resolve(stamp);
    }

    /** The side's key over every row folded in the round in play: what an unstamped mint takes. With a
     *  document but no fold yet = the document's own rows alone; null without a document. */
    public synchronized FleetKey currentKey(int side) {
        if (document == null || (side != 0 && side != 1)) {
            return null;
        }
        EpochFold e = current();
        return e != null ? e.fold.currentKey(side) : document.key(side, new FleetRecord());
    }

    /** True once the side's round-open key matched the digest its round-open rows carry, this round. */
    public synchronized boolean isClosed(int side) {
        EpochFold e = current();
        return e != null && (side == 0 || side == 1) && e.fold.isClosed(side);
    }

    /** The census count of {@code c} (lifetime: a new document keeps it). */
    public synchronized long count(Census c) {
        return census[c.ordinal()];
    }

    /** The census as meter tokens: {@code " fleet_key_<token>=<n>"} per {@link Census}, in enum order, then the
     *  removal census {@code " vacated=<n> vacate_absent=<n>"}, then the unstamped census
     *  {@code " allskills fleet_ctx unstamped census late_join=<n> reorder=<n> failopen=<n> no_id=<n> refused=<n>"}
     *  in {@link Unstamped} order (the grader reads that tail off the round-close line; {@code refused} last).
     *  The tail. The removal census. */
    public synchronized String meterText() {
        StringBuilder sb = new StringBuilder();
        for (Census c : Census.values()) {
            sb.append(" fleet_key_").append(c.token()).append('=').append(census[c.ordinal()]);
        }
        for (VacateOutcome o : VacateOutcome.values()) {
            sb.append(' ').append(o.token()).append('=').append(vacates[o.ordinal()]);
        }
        sb.append(" allskills fleet_ctx unstamped census");
        for (Unstamped k : Unstamped.values()) {
            sb.append(' ').append(k.token()).append('=').append(unstamped[k.ordinal()]);
        }
        return sb.toString();
    }

    private EpochFold foldOf(long epoch) {
        Long key = Long.valueOf(epoch);
        EpochFold e = folds.get(key);
        if (e == null) {
            e = new EpochFold(new FleetFold(epoch, document));
            folds.put(key, e);
        }
        return e;
    }

    /** The applied epoch's fold when one is in play, else the newest; null with none. */
    private EpochFold current() {
        EpochFold applied = appliedEpoch >= 0L ? folds.get(Long.valueOf(appliedEpoch)) : null;
        if (applied != null) {
            return applied;
        }
        Map.Entry<Long, EpochFold> top = folds.lastEntry();
        return top == null ? null : top.getValue();
    }

    /** End (speak, then drop) every fold of an epoch strictly below {@code limit}. */
    private void endFoldsBelow(long limit) {
        for (Iterator<EpochFold> it = folds.headMap(Long.valueOf(limit), false).values().iterator(); it.hasNext();) {
            EpochFold e = it.next();
            speakUnclosed(e, 0, "epoch-end");
            speakUnclosed(e, 1, "epoch-end");
            it.remove();
        }
    }

    /** OPEN-UNCLOSED, at most once per (epoch, side): only a side that folded a round-open row and
     *  did not close. */
    private void speakUnclosed(EpochFold e, int side, String trigger) {
        if (e.unclosedSpoken[side]) {
            return;
        }
        OpenMismatch m = e.fold.openMismatch(side);
        if (m == null) {
            return;
        }
        e.unclosedSpoken[side] = true;
        long n = ++census[Census.UNCLOSED.ordinal()];
        diag(DIAG_UNCLOSED, "fleet_key OPEN-UNCLOSED side=" + side + " have=" + AllSkillsFleetLaw.hex8(m.have())
                + " want=" + AllSkillsFleetLaw.hex8(m.want()) + " epoch=" + e.fold.epoch() + " at=" + trigger
                + " open_rows=" + e.fold.openRows(side) + " (unclosed=" + n + ")");
    }

    private Answer refuse(Census c, FoldResult r, long epoch, long wireId, int side) {
        refusedRow(c.token(), ++census[c.ordinal()], epoch, wireId, side);
        return new Answer(r, c.token());
    }

    /** The capped REFUSED row of a refusal counted {@code n} under {@code token} (numbers only).
     * */
    private void refusedRow(String token, long n, long epoch, long wireId, int side) {
        diag(DIAG_REFUSED, "fleet_key REFUSED " + token + " epoch=" + epoch + " id=" + wireId + " side=" + side
                + " (" + token + "=" + n + ")");
    }

    private void diag(int kind, String row) {
        if (diagRows[kind] >= DIAG_ROWS_PER_KIND) {
            return;
        }
        int written = ++diagRows[kind];
        if (pending.size() >= DIAG_PENDING_MAX) {
            return;
        }
        pending.add(written == DIAG_ROWS_PER_KIND
                ? row + " [row cap " + DIAG_ROWS_PER_KIND + " reached: later rows of this kind are counted only]"
                : row);
    }
}
