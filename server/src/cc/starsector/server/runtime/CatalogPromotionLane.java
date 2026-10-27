package cc.starsector.server.runtime;

import cc.starsector.server.ops.PeerText;
import cc.starsector.server.runtime.CustomVariantLane.Registrar;
import cc.starsector.server.runtime.CustomVariantLane.VariantFan;
import cc.starsector.server.runtime.CustomVariantLane.Verdict;
import cc.starsector.server.runtime.VariantDefinitionFrameCodec.VariantDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Live catalog promotion (dev promotions go live with no mod roll): the
 * editor's Mod catalog markers on a running dev WATCH serve. The joiner's card writes
 * {@code <kit env>/saves/common/cc_variants/_catalog/} — {@code <stem>.variant.data} bodies + the pending
 * {@code index.data} {@code {"promote":[stems],"remove":[stems]}} — and this lane MIRRORS that index:
 * a promoted stem registers (the full engine pass, no {@link VariantPolicy} tier = the boot scan's
 * posture), fans its VDEF, joins the admission spool and lists on {@code /ship}; a {@code remove} stem
 * is HIDDEN from the listing at once (Q1 default); a stem leaving either list returns to
 * the base state at the next poll. Registry entry, client folds and the spool frame of a delisted
 * promotion STAY (a seat may still fly it). Identity = the mod STEM (it survives the roll).
 *
 * <p>Threads: a daemon poller ({@link #POLLER_THREAD}, every {@link #POLL_INTERVAL_MILLIS} ms) stats
 * {@code index.data} (mtime + size), never lists the dir, never writes; a new generation → the strict
 * stem law BEFORE any path is built → only {@code <stem>.variant.data} (regular file, real parent ==
 * the dir, ≤ {@link #VARIANT_MAX_BYTES}) → {@code parseVariantText}, {@code variantId == stem}, ONE
 * frame ≤ 1164 B (encoded here, off-thread) → a published full-state snapshot. The SIM thread's
 * {@link #tick} (the registry's one writer, via {@code CustomVariantLane.drainTick}) runs ≤ 1 engine
 * pass per tick, then applies the {@link PickableVariants} overlay. Boot disk wins: a stem the engine
 * already resolves is skipped loud (a card UPDATE of a shipped stem rides the roll).
 *
 * <p>A live re-promote of a stem THIS serve registered with a DIFFERING body = a live UPDATE (it
 * supersedes the earlier refusal — the card's
 * "live now" must hold for an edit): the full engine pass again, the
 * registrar's OVERWRITE put, the stem's spool frame replaced IN PLACE, the new frame fanned — every
 * client folds a non-boot id overwrite=true, so both ends swap together and the next respawn flies the
 * new body. A ship already flying keeps the spec it was built from on BOTH ends; a late joiner minting
 * a replica of such a pre-update ship folds the new body (residue R7, bounded by that ship's life). A
 * refused update keeps the registered body flying, memoized per body like a first refusal. A SHIPPED
 * stem stays boot-disk-wins: the client's {@code BootVariantRegistry} Law A refuses a differing fan of
 * a boot id, so its update rides the roll. Every decided verdict (promoted / updated / refused /
 * shipped) is ONE server chat line to every seat ({@link #catalogLine}, the chat law's cut idiom) —
 * once per distinct body, never per poll.
 *
 * <p>Security: LOCAL-disk input from
 * a game-writable dir, never the wire. The dir is a sysprop-only INFRA knob whose real path must end
 * {@link #REQUIRED_TAIL} (boot THROWS otherwise); ids are lawful-charset stems; file text is DATA —
 * every file-derived string a log line carries is {@link PeerText#quote}d; a torn index keeps the
 * last good snapshot.
 *
 * <p>The SECOND source, KEPT ("Keep for everyone": a host's
 * accepted custom ship listed for every player, live and across restarts, no kit change):
 * every {@code <stem>.variant} under {@code <exportDir>/kept/}, a LISTING (no index), read by
 * {@link #scanKept} — once at boot on the one-shot {@link #KEPT_READER_THREAD} daemon or the serve's
 * {@link #keptBootListable}, whichever takes the scan lock first; it is public so
 * the Keep / Forget writer ({@link CustomVariantPersistSink}) re-scans on its own thread; no standing poll. A kept file passes the same
 * {@code readCandidate} law as a DEV body (NOFOLLOW, real parent, 64 KiB, nesting 64,
 * {@code variantId == stem}) PLUS the caller's per-file policy function; ≤ {@link #KEPT_CAP} read per
 * scan, ≤ {@link #KEPT_SCAN_MAX_ENTRIES} entries visited, an over-cap = a loud row + {@link #keptOverCap}.
 * Both sources merge into ONE published snapshot (DEV first; a case-fold duplicate = DEV wins, loud),
 * so {@link #tick} keeps its one engine pass per tick and the ONE {@code passCap}. Unlike a DEV stem, a
 * kept stem that LEAVES the listing clears its standing picks (the {@link HideListener}); its registry
 * entry and spool frame stay, as a DEV promotion's do. A kept registration chats
 * {@code ship '<stem>' added by the host …} and its live update {@code … changed by the host …}
 * ({@link #KEPT_ADDED_PHRASE} / {@link #KEPT_UPDATED_PHRASE}); a kept engine refusal or boot-disk skip is
 * a stdout row only (the DEV notices are roll wording). The scan also lists the inbox's export names
 * ({@link #inboxIds}; no body is opened) for the consoles' "Exported, not kept yet" list, and reads each listed
 * forgotten kept ship's {@code .meta} for its {@code supersedes} link ({@link #removedSupersededByOrNull}).
 */
public final class CatalogPromotionLane {

    /** The sysprop-only INFRA knob (no properties key, no {@code CC_*} env: the public operator
     * surfaces cannot teach it); set only by {@code arm-standing-watch.ps1}'s catalog block. */
    public static final String PROMOTE_DIR_PROPERTY = "cc.catalog.promoteDir";
    /** The dir's REAL path must end here (case-folded, {@code /}-normalized). */
    public static final String REQUIRED_TAIL = "saves/common/cc_variants/_catalog";
    /** The daemon's thread name (residency allow-list row {@code off-thread:cc-catalog-poller}). */
    public static final String POLLER_THREAD = "cc-catalog-poller";
    public static final long POLL_INTERVAL_MILLIS = 2000L;
    /** The client writer's own index bound ({@code EditorCatalog.INDEX_MAX_BYTES}). */
    public static final int INDEX_MAX_BYTES = 64 * 1024;
    public static final int VARIANT_MAX_BYTES = 64 * 1024;
    /** Ids read per index list ({@code EditorCatalog.PENDING_CAP}) = the overlay's promoted ceiling. */
    public static final int OVERLAY_CAP = 32;
    /**
     * The WHOLE-catalog bound the engine passes of one serve stay under: boot frames + this lane's
     * registrations + its refusal memos ≤ {@code MAX_VARIANTS_TOTAL} (the client's
     * VDEF queue is sized boot-catalog bound + the customs' {@code GLOBAL_CAP}, so a late joiner's
     * catch-up burst never overruns it; a refused body memo counts, so file input cannot drive
     * unbounded engine passes or heap). The instance's room = this minus the boot catalog size.
     * PINNED as its own literal: it used to ride
     * {@code MAX_VARIANTS_TOTAL}, so a raise of the wave budget would have quadrupled this
     * file-input engine-pass bound by accident; {@code WaveInflateContractRows.budgetRows} asserts it
     * stays ≤ the boot cap it shares the budget's boot term with.
     */
    public static final int REGISTERED_CAP = 256;
    /** {@code [}/{@code {} nesting a file may carry: the game's 2010 org.json recurses one frame per
     * level (a {@code StackOverflowError} is an Error no lane catch saw). */
    public static final int MAX_JSON_DEPTH = 64;
    /** Absent-index polls beside a stale {@code index.data.inprogress} before it counts as absent
     * (the engine's replace window is ≤ ~1 s; a failed rename leaves the file behind for good). */
    public static final int STALE_INPROGRESS_POLLS = 5;
    /** A parser's exception message (it echoes the bad value) is cut here before the log. */
    static final int REFUSAL_MESSAGE_MAX_CHARS = 160;
    /** Sim-thread walks re-armed after a {@code tick_fault} before the snapshot is dropped. */
    static final int FAULT_RETRIES = 3;
    /** The boot-disk notices ({@link #shippedNotice}) chatted per SNAPSHOT (= one poll) — a list of
     *  shipped stems used to chat up to {@link #OVERLAY_CAP} lines to every seat at once; the rest go
     *  to stdout only (the per-body memo still stands). */
    public static final int SHIPPED_NOTICE_CHAT_CAP = 2;
    static final String INDEX_FILE = "index.data";
    static final String VARIANT_SUFFIX = ".variant.data";

    /** The KEPT source's folder under the custom-export inbox
     *  ({@code <exportDir>/kept}; the wiring resolves it exactly as the export writer resolves the inbox). */
    public static final String KEPT_DIR_NAME = "kept";
    /** A kept file IS an export's twin, so it carries the export's extension (the DEV suffix stays {@link #VARIANT_SUFFIX}). */
    public static final String KEPT_SUFFIX = ".variant";
    /** Kept stems read per scan — its OWN literal, by law (never ride {@link #OVERLAY_CAP}, so a raise of
     *  either bound never moves the other). */
    public static final int KEPT_CAP = 32;
    /** Directory entries one scan visits (kept dir and inbox each) before it stops loud: a folder of junk
     *  never turns one scan into an unbounded walk. */
    public static final int KEPT_SCAN_MAX_ENTRIES = 256;
    /** Inbox names listed per scan PER PREFIX ({@code kept-…} and {@code cc-v…} each, "Exported, not kept yet"); the
     *  status file carries them, so it is bounded. */
    public static final int INBOX_LIST_CAP = 32;
    /** An export's file stem starts with the minted custom id's prefix ({@link SubmittedVariantLedger#ID_PREFIX}). */
    public static final String INBOX_PREFIX = SubmittedVariantLedger.ID_PREFIX;
    /** A Forget moves a kept file (stem {@code kept-…}) back to the inbox; it is listed there too. */
    public static final String INBOX_KEPT_PREFIX = "kept-";
    /** The one-shot boot reader's thread name (residency allow-list row {@code off-thread:cc-kept-reader}). */
    public static final String KEPT_READER_THREAD = "cc-kept-reader";
    /** The KEPT chat lines' head + phrases: {@code catalogLine(name, phrase, true)} yields the two KEPT room lines
     *  {@code '<name>' <phrase>}; each phrase ≤ 62 chars, far above a {@value #CHAT_NAME_MAX_CHARS}-char name's need.
     *  The head is the bare quote - the line names the ship, never its id. */
    static final String KEPT_LINE_HEAD = "'";
    /** The longest display name a chat line carries (the name is player text; {@link #chatShipName}). */
    public static final int CHAT_NAME_MAX_CHARS = 24;
    public static final String KEPT_ADDED_PHRASE = "added by the host - type /ship to fly it";
    public static final String KEPT_UPDATED_PHRASE = "changed by the host - the new build flies at your next respawn";

    /** The door's standing-pick clear for newly HIDDEN base ids and for KEPT stems that
     *  left the listing (a Forget, a removed file); returns seats cleared. */
    public interface HideListener {
        int onHidden(List<String> ids);
    }

    /** One server-built chat line to every live seat (the wiring's {@code door.fanServerChat(0, line)});
     * called on the sim thread with a {@link #catalogLine} only — its one file-derived part is the ship's display name,
     * bounded by {@link #chatShipName} (printable ASCII, ≤ {@value #CHAT_NAME_MAX_CHARS} chars). */
    public interface Chat {
        void line(String text);
    }

    /** One promote entry as a source read it: a lawful def + its frame, or a refusal token; {@code kept} =
     *  read from the KEPT source (its chat phrases, its left-the-listing pick clear). */
    private record Candidate(String stem, VariantDefinition def, byte[] frame, String refusal, boolean kept) {
    }

    /** One index generation, full state; {@code outcomes} = the kept-store writer's job outcomes since the last
     *  snapshot the sim thread took (≤ {@link #KEPT_OUTCOMES_CAP}). */
    private record Snapshot(List<Candidate> promote, List<String> hidden, List<KeptOutcome> outcomes) {
        Snapshot(List<Candidate> promote, List<String> hidden) {
            this(promote, hidden, List.of());
        }
    }

    /**
     * One kept-store writer job's outcome, handed to the sim thread on the snapshot: {@code reason} = a bounded token
     * ({@code written} / {@code forgot} / {@code already_kept} / the writer's refusal token / {@code io}),
     * {@code ccvIdOrNull} = the custom id a keep came from, {@code stemOrNull} = the kept stem written or moved,
     * {@code tokenOrNull} = the inbox name a keep-export read.
     */
    public record KeptOutcome(String reason, String ccvIdOrNull, String stemOrNull, String tokenOrNull) {
    }

    /** Writer outcomes one snapshot carries; past it an outcome is dropped LOUD and its caller's booking answers
     *  "no result" when it times out. */
    public static final int KEPT_OUTCOMES_CAP = 32;

    /** The DEV source (the sysprop knob's lawful real dir), or null = a KEPT-only lane. */
    private final Path devDirOrNull;
    /** The KEPT source ({@code <exportDir>/kept}, absolute; it may not
     *  exist yet), or null = a DEV-only lane; its per-file policy (null = pass; non-null = the refusal token). */
    private final Path keptDirOrNull;
    private final Function<VariantDefinition, String> keptRefusalOrNull;
    /** Serializes the two publishers (the DEV poller, a KEPT scan): each sets its part, then merges. */
    private final Object publishLock = new Object();
    /** Two KEPT scans (the boot reader, a Keep / Forget writer) never interleave. */
    private final Object scanLock = new Object();
    // publishLock only
    private Snapshot devPart = new Snapshot(List.of(), List.of());
    private List<Candidate> keptPart = List.of();
    /** Writer outcomes not yet on a published snapshot + the drops. */
    private final List<KeptOutcome> pendingOutcomes = new ArrayList<KeptOutcome>();
    private final List<KeptOutcome> heldForRescan = new ArrayList<KeptOutcome>();
    private volatile long keptOutcomesDropped;
    // written under scanLock / on the sim thread; read by the status lane on any thread
    private volatile List<String> keptIdsLast = List.of();
    private volatile List<String> inboxIdsLast = List.of();
    /** Written by {@link #listInbox} just BEFORE {@link #inboxIdsLast}, read
     *  on the sim thread: a listed inbox {@code kept-} stem -> the {@code supersedes} link its {@code .meta} carries
     *  (a forget moves the kept sidecar into the inbox beside the body); a stem with no link is absent. */
    private volatile Map<String, String> supersedesByRemoved = Map.of();
    private volatile boolean keptOverCapLast;
    private volatile boolean inboxOverCapLast;
    private volatile int keptRefusedLast;
    private volatile int keptListedCount;
    /** scanLock only: a kept scan completed (any cause), so the boot read reuses it instead of reading twice;
     *  and the stems that scan's bodies passed (refused and over-cap stems never in it). */
    private boolean keptScanDone;
    private List<String> keptAcceptedLast = List.of();
    /** Engine passes this serve may still spend: {@link #REGISTERED_CAP} minus the boot catalog. */
    private final int passCap;
    private final Registrar registrar;
    private final Predicate<String> resolvable;
    private final IdTables tables;
    private final VariantFan fanOrNull;
    private final PickableVariants pickable;
    private final HideListener hideListenerOrNull;
    private final Chat chatOrNull;
    private final AtomicReference<Snapshot> published = new AtomicReference<Snapshot>();
    /** Reader thread (the admission spool) reads; the sim thread appends. */
    private final CopyOnWriteArrayList<byte[]> spool = new CopyOnWriteArrayList<byte[]>();

    // poller thread only
    private String lastSignature;
    private int absentPolls;
    private int inprogressPolls;
    private Thread poller;
    private Thread keptReader;

    // sim thread only
    /** The kept stems the last {@link #apply} LISTED: one that leaves = its standing picks clear. */
    private Set<String> keptListedBefore = Set.of();
    private Snapshot work;
    private Set<String> workHiddenFolds = Set.of();
    private int cursor;
    private int faultRetries;
    private final Map<String, byte[]> registeredFrames = new HashMap<String, byte[]>();
    /** The last body the engine REFUSED per stem: an unchanged body never re-runs the pass. */
    private final Map<String, byte[]> refusedFrames = new HashMap<String, byte[]>();
    /** A SHIPPED stem's last chatted body — the roll-bound notice fires once per body, never per poll. */
    private final Map<String, byte[]> shippedNoticeFrames = new HashMap<String, byte[]>();
    /** Notices CHATTED for the snapshot under walk (reset when a new one is taken). */
    private int shippedNoticesChatted;
    /** Stem → its slot in {@link #spool}; an UPDATE replaces the frame in place (registration order kept). */
    private final Map<String, Integer> spoolIndex = new HashMap<String, Integer>();
    private int registerCalls;
    /** The host verbs' final-verdict book (install-time; null = none). Sim
     *  thread: the kept stems whose writer success waits for the listing (stem → the booking it settles), the last
     *  {@link #admit} exit's refusal token (null = listed / unchanged), and the mail faults the tick caught. */
    private volatile HostReceiptBook receiptsOrNull;
    /** {@code supersedeOrNull} = the supersede this written copy settles once it lists. */
    private record Await(String key, boolean restore, boolean forget, Supersede supersedeOrNull) {
    }
    private final Map<String, Await> awaiting = new HashMap<String, Await>();

    /** The hidden lane's settle of one supersede, on the sim thread: X leaves {@code /ship} (true) or not (false,
     *  the receipt then says "added"). {@code copyStem} = the kept stem that now lists in X's place. */
    public interface SupersedeHook {
        boolean apply(String copyStem);
    }

    /** One armed supersede: the ship replaced (a shipped id or a kept stem), which
     *  kind, and the hook that takes it off {@code /ship} once the copy lists. */
    public record Supersede(String replacedId, boolean replacedKept, SupersedeHook hook) {
    }

    /** Sim thread: custom id -> the supersede its keep carries, until the writer's outcome for that id arrives
     *  ({@link #receiptsTick} moves it onto the stem's {@link Await} or drops it). Bounded by {@link #KEPT_OUTCOMES_CAP}. */
    private final Map<String, Supersede> supersedes = new HashMap<String, Supersede>();
    /** Sim thread: restore keys whose put-back also forgot the copy that superseded them - their final body reads
     *  {@link HostReceiptBook#PUT_BACK_COPY_GONE} ({@link #noteCopyGone}). */
    private final Set<String> putBackCopyGone = new HashSet<String>();
    /** Written under {@link #scanLock} by every kept scan, read on the sim thread: kept copy stem -> the ship id /
     *  stem it supersedes (its {@code .meta}'s {@code supersedes}); a copy with no link is absent. */
    private volatile Map<String, String> supersedesByCopy = Map.of();
    /**
     * Written beside {@link #supersedesByCopy} by every kept scan, read
     * on the sim thread at a kept copy's registration: kept copy stem -> its keep time, epoch ms (&gt; 0). WHY: every
     * ship list shows the kept copies newest first ({@link PickableVariants#rememberKeptAt}), and only the copy's
     * sidecar (or, failing that, its file's modified time) knows when it was kept. A copy whose time is unknown is
     * absent.
     */
    private volatile Map<String, Long> keptAtByCopy = Map.of();
    /** A kept {@code .meta} is a few hundred bytes; past this the sidecar is read as "no link" and "no time". */
    static final int META_MAX_BYTES = 4096;

    /**
     * Arm a supersede: when the keep of {@code ccvId} is WRITTEN and its stem
     * LISTS, {@code hook} runs on the sim thread (X leaves {@code /ship}) and the booking settles "changed"; an
     * identical body ("already kept"), a writer refusal, an engine refusal or a copy that never lists DROPS it and X
     * stays (ruling viii: nothing hides behind a keep that changed nothing). Sim thread. False = the cap is full (one
     * loud row; the keep then ADDS the copy beside X).
     */
    public boolean expectSupersede(String ccvId, String replacedId, boolean replacedKept, SupersedeHook hook) {
        if (ccvId == null || replacedId == null || hook == null) {
            return false;
        }
        if (!supersedes.containsKey(ccvId) && supersedes.size() >= KEPT_OUTCOMES_CAP) {
            System.out.println("catalog-kept supersede_dropped id=" + ccvId + " reason=cap cap=" + KEPT_OUTCOMES_CAP);
            return false;
        }
        supersedes.put(ccvId, new Supersede(replacedId, replacedKept, hook));
        return true;
    }

    /** A put-back ({@code restoreKey} = the restore's booking key, the inbox token) also forgot the copy that superseded
     *  the ship: its final body is the copy-gone tail. Sim thread; bounded like the outcomes. */
    public void noteCopyGone(String restoreKey) {
        if (restoreKey == null) {
            return;
        }
        if (putBackCopyGone.size() >= KEPT_OUTCOMES_CAP) {
            putBackCopyGone.clear();
            System.out.println("catalog-kept copy_gone_notes_cleared cap=" + KEPT_OUTCOMES_CAP);
        }
        putBackCopyGone.add(restoreKey);
    }

    /** The kept copy whose {@code supersedes} link names {@code replacedId} (ASCII fold), or null = none kept now.
     *  Any thread (the last scan's view); the hidden lane's put-back reads it on the sim thread. */
    public String copySupersedingOrNull(String replacedId) {
        if (replacedId == null) {
            return null;
        }
        String fold = ChatCommands.asciiLower(replacedId);
        for (Map.Entry<String, String> e : supersedesByCopy.entrySet()) {
            if (ChatCommands.asciiLower(e.getValue()).equals(fold)) {
                return e.getKey();
            }
        }
        return null;
    }

    /** The ship id / stem a kept copy's own {@code supersedes} link names (ASCII fold on the copy's stem), or null =
     *  {@code copyStem} is not a kept copy with a link. Any thread (the last scan's view). A supersede OF a copy follows
     *  this link to the ROOT, so {@code /shiprestore X} still finds the newest copy after a copy-of-copy.
     * */
    public String supersededByOrNull(String copyStem) {
        if (copyStem == null) {
            return null;
        }
        String fold = ChatCommands.asciiLower(copyStem);
        for (Map.Entry<String, String> e : supersedesByCopy.entrySet()) {
            if (ChatCommands.asciiLower(e.getKey()).equals(fold)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** The ship id / stem a forgotten kept ship's inbox {@code .meta} names
     *  as the ship it took the place of (ASCII fold on the inbox stem), or null = no such stem listed, or no link.
     *  Any thread (the last scan's view). The twin of {@link #supersededByOrNull} for a ship off {@code /ship}: a
     *  removed copy of a copy follows it to the ROOT, whose newest copy stands in its place. */
    public String removedSupersededByOrNull(String removedStem) {
        if (removedStem == null) {
            return null;
        }
        String fold = ChatCommands.asciiLower(removedStem);
        for (Map.Entry<String, String> e : supersedesByRemoved.entrySet()) {
            if (ChatCommands.asciiLower(e.getKey()).equals(fold)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** Sim thread: the stem's awaited keep carries a supersede (the admit fan then says "changed", not "added"). */
    private boolean supersedePending(String stem) {
        Await a = awaiting.get(stem);
        return a != null && a.supersedeOrNull() != null;
    }

    /**
     * What the kept scan takes from one copy's {@code .meta}, in ONE
     * bounded read. {@code supersedesOrNull} = the supersede link; {@code keptAtMsOrNull} = the keep time (epoch ms,
     * &gt; 0); {@code unreadable} = a sidecar was there but could not be read (counted into the scan's one row).
     */
    private record KeptMeta(String supersedesOrNull, Long keptAtMsOrNull, boolean unreadable) {
    }

    /**
     * The kept {@code .meta} beside an accepted stem: its {@code supersedes} value when present and lawful under the
     * ship-id law, else null (absent, unreadable, over {@link #META_MAX_BYTES}, deep, not a string, unlawful) - a
     * damaged sidecar costs the link, never the ship. Same read law as the body: NOFOLLOW, the real parent.
     * The same parse yields the keep time: {@code keptAtMs} (a kept
     * export's), else {@code acceptedAtMs} (a keep's), each only as a whole number &gt; 0; a damaged sidecar costs
     * the time too (the scan then falls back to the file's modified time). Never throws.
     */
    private static KeptMeta readKeptMeta(Path realDir, String stem) {
        Path meta = realDir.resolve(stem + CustomVariantPersistSink.META_EXT);
        try {
            if (!Files.isRegularFile(meta, LinkOption.NOFOLLOW_LINKS)) {
                return new KeptMeta(null, null, false); // no sidecar: no link, no time, nothing wrong
            }
            if (!realDir.equals(meta.toRealPath().getParent())) {
                return new KeptMeta(null, null, true);
            }
            byte[] body = readBounded(meta, META_MAX_BYTES);
            if (nestingExceeds(body, MAX_JSON_DEPTH)) {
                return new KeptMeta(null, null, true);
            }
            JSONObject o = new JSONObject(new String(body, StandardCharsets.UTF_8));
            Object v = o.opt(CustomVariantPersistSink.SUPERSEDES_KEY);
            String supersedes = v instanceof String s && OperatorCommandFile.joinerDefaultTokenLawful(s) ? s : null;
            Long at = epochMsOrNull(o.opt(CustomVariantPersistSink.KEPT_AT_KEY));
            return new KeptMeta(supersedes, at != null ? at : epochMsOrNull(o.opt(CustomVariantPersistSink.ACCEPTED_AT_KEY)),
                    false);
        } catch (IOException | JSONException | RuntimeException | StackOverflowError bad) {
            return new KeptMeta(null, null, true);
        }
    }

    /** A sidecar time value as epoch ms when it is a whole number
     *  &gt; 0, else null (absent, a string, a fraction, zero or negative). */
    private static Long epochMsOrNull(Object v) {
        long ms;
        if (v instanceof Long l) {
            ms = l;
        } else if (v instanceof Integer i) {
            ms = i;
        } else {
            return null;
        }
        return ms > 0L ? ms : null;
    }

    /** A kept copy's file modified time as epoch ms (&gt; 0), the keep
     *  time's last fallback (a hand-placed copy, a sidecar without a time); null when it cannot be read. Never throws. */
    private static Long modifiedMsOrNull(Path file) {
        try {
            long ms = Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis();
            return ms > 0L ? ms : null;
        } catch (IOException | RuntimeException unreadable) {
            return null; // counted by the caller into the scan's time_unknown
        }
    }
    private String admitExit;
    private long receiptFaults;

    /** {@code dir} must already be {@link #requireLawfulDir}'s answer; {@code bootCatalogSize} = the
     * serve's boot VDEF frames (the whole-catalog cap's other half). */
    public CatalogPromotionLane(Path dir, int bootCatalogSize, Registrar registrar, Predicate<String> resolvable,
            IdTables tables, VariantFan fanOrNull, PickableVariants pickable, HideListener hideListenerOrNull) {
        this(dir, bootCatalogSize, registrar, resolvable, tables, fanOrNull, pickable, hideListenerOrNull, null);
    }

    /** The DEV-only shape — {@code chatOrNull} carries every decided verdict to the seats. */
    public CatalogPromotionLane(Path dir, int bootCatalogSize, Registrar registrar, Predicate<String> resolvable,
            IdTables tables, VariantFan fanOrNull, PickableVariants pickable, HideListener hideListenerOrNull,
            Chat chatOrNull) {
        this(dir, bootCatalogSize, registrar, resolvable, tables, fanOrNull, pickable, hideListenerOrNull, chatOrNull,
                null, null);
    }

    /**
     * The wiring's shape — DEV, KEPT or both. {@code devDirOrNull} must
     * already be {@link #requireLawfulDir}'s answer; {@code keptDirOrNull} = {@code <exportDir>/kept} (it may
     * not exist; {@link #scanKept} reads it); {@code keptRefusalOrNull} = the per-file policy a kept body
     * passes (null answer = pass). Neither source = {@link IllegalArgumentException}.
     */
    public CatalogPromotionLane(Path devDirOrNull, int bootCatalogSize, Registrar registrar, Predicate<String> resolvable,
            IdTables tables, VariantFan fanOrNull, PickableVariants pickable, HideListener hideListenerOrNull,
            Chat chatOrNull, Path keptDirOrNull, Function<VariantDefinition, String> keptRefusalOrNull) {
        if (devDirOrNull == null && keptDirOrNull == null) {
            throw new IllegalArgumentException("a lane needs a source");
        }
        this.chatOrNull = chatOrNull;
        this.devDirOrNull = devDirOrNull;
        this.keptDirOrNull = keptDirOrNull == null ? null : keptDirOrNull.toAbsolutePath();
        this.keptRefusalOrNull = keptRefusalOrNull;
        this.passCap = Math.max(0, REGISTERED_CAP - Math.max(0, bootCatalogSize));
        this.registrar = Objects.requireNonNull(registrar, "registrar");
        this.resolvable = Objects.requireNonNull(resolvable, "resolvable");
        this.tables = Objects.requireNonNull(tables, "tables");
        this.fanOrNull = fanOrNull;
        this.pickable = Objects.requireNonNull(pickable, "pickable");
        this.hideListenerOrNull = hideListenerOrNull;
    }

    /**
     * The DEV-only door: null (one quiet line) when NEITHER source exists — with no KEPT dir here, that is
     * the knob unset; null LOUD when the door has no allowlist to overlay; else the lane over the lawful
     * dir. A dir that fails {@link #requireLawfulDir} THROWS — a mis-pointed knob never serves silently.
     */
    public static CatalogPromotionLane openOrNull(PickableVariants pickableOrNull, int bootCatalogSize,
            Registrar registrar, Predicate<String> resolvable, IdTables tables, VariantFan fanOrNull,
            HideListener hideListenerOrNull) {
        return openOrNull(pickableOrNull, bootCatalogSize, registrar, resolvable, tables, fanOrNull, hideListenerOrNull, null);
    }

    /** The DEV-only door with the seats' chat line (null when NEITHER source exists = the knob unset). */
    public static CatalogPromotionLane openOrNull(PickableVariants pickableOrNull, int bootCatalogSize,
            Registrar registrar, Predicate<String> resolvable, IdTables tables, VariantFan fanOrNull,
            HideListener hideListenerOrNull, Chat chatOrNull) {
        return openOrNull(pickableOrNull, bootCatalogSize, registrar, resolvable, tables, fanOrNull, hideListenerOrNull,
                chatOrNull, null, null);
    }

    /**
     * The wiring's door, DEV-or-KEPT. It used to
     * return null whenever the knob was unset, which left the KEPT source dead in every package (no package
     * sets the INFRA knob). Now: the knob gates only the DEV poller (an unlawful knob still THROWS); null
     * (one quiet line) only when NEITHER source exists; null LOUD with no allowlist to overlay; else the lane.
     */
    public static CatalogPromotionLane openOrNull(PickableVariants pickableOrNull, int bootCatalogSize,
            Registrar registrar, Predicate<String> resolvable, IdTables tables, VariantFan fanOrNull,
            HideListener hideListenerOrNull, Chat chatOrNull, Path keptDirOrNull,
            Function<VariantDefinition, String> keptRefusalOrNull) {
        // the LITERAL key (== PROMOTE_DIR_PROPERTY): the knob-doc check pairs by literal only
        String raw = ServerConfig.resolvePath("cc.catalog.promoteDir", null, null, "");
        Path devDir = raw == null || raw.isBlank() ? null : requireLawfulDir(raw);
        if (devDir == null && keptDirOrNull == null) {
            System.out.println("catalog-promote off knob=" + PROMOTE_DIR_PROPERTY);
            return null;
        }
        if (pickableOrNull == null) {
            System.out.println("catalog-promote off reason=no_pickable_allowlist dir="
                    + PeerText.quote(String.valueOf(devDir != null ? devDir : keptDirOrNull)));
            return null;
        }
        if (devDir == null) {
            System.out.println("catalog-promote dev_off knob=" + PROMOTE_DIR_PROPERTY + " (kept source only)");
        }
        return new CatalogPromotionLane(devDir, bootCatalogSize, registrar, resolvable, tables, fanOrNull,
                pickableOrNull, hideListenerOrNull, chatOrNull, keptDirOrNull, keptRefusalOrNull);
    }

    /** The boot law: an existing directory whose REAL path ends {@link #REQUIRED_TAIL}. */
    public static Path requireLawfulDir(String raw) {
        Path real;
        try {
            real = Path.of(raw).toRealPath();
        } catch (IOException | RuntimeException bad) {
            throw new IllegalArgumentException(PROMOTE_DIR_PROPERTY + " unresolvable: " + PeerText.quote(raw)
                    + " (" + bad.getClass().getSimpleName() + ")", bad);
        }
        String norm = ChatCommands.asciiLower(real.toString().replace('\\', '/'));
        if (!Files.isDirectory(real) || !norm.endsWith("/" + REQUIRED_TAIL)) {
            throw new IllegalArgumentException(PROMOTE_DIR_PROPERTY + " must be an existing .../" + REQUIRED_TAIL
                    + " directory, got " + PeerText.quote(real.toString()));
        }
        return real;
    }

    /**
     * The strict stem law — the server twin of the card writer's {@code LocalVariantIdLaw}
     * ({@code EditorText.isId}), pinned by a vector row: {@code [0-9A-Za-z][0-9A-Za-z._-]*}, 1..64 chars,
     * no {@code ..}. It runs BEFORE any path is built, so no separator survives into one.
     */
    public static boolean stemLawful(String s) {
        if (s == null || s.isEmpty() || s.length() > PickableVariants.ID_MAX_CHARS || !alnum(s.charAt(0))) {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(alnum(c) || c == '.' || c == '_' || c == '-')) {
                return false;
            }
        }
        return !s.contains("..");
    }

    private static boolean alnum(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    /**
     * Start the sources' threads (idempotent): the DEV daemon poller only when the DEV dir exists;
     * the KEPT one-shot daemon {@link #KEPT_READER_THREAD} when the
     * KEPT dir is set — it runs the boot scan ONCE ({@link #keptBootScanOnce}: skipped when the serve's boot read
     * took it first) and exits: no standing poll.
     */
    public synchronized void start() {
        if (poller != null || keptReader != null) {
            return;
        }
        if (devDirOrNull != null) {
            Thread t = new Thread(this::pollLoop, POLLER_THREAD);
            t.setDaemon(true);
            poller = t;
            t.start();
            System.out.println("catalog-promote poller_started dir=" + PeerText.quote(devDirOrNull.toString()) + " every_ms="
                    + POLL_INTERVAL_MILLIS + " overlay_cap=" + OVERLAY_CAP + " pass_cap=" + passCap
                    + " (whole-catalog bound " + REGISTERED_CAP + ")");
        }
        if (keptDirOrNull != null) {
            Thread r = new Thread(this::keptBootScanOnce, KEPT_READER_THREAD);
            r.setDaemon(true);
            keptReader = r;
            System.out.println("catalog-kept reader_started dir=" + PeerText.quote(keptDirOrNull.toString())
                    + " cap=" + KEPT_CAP);
            r.start();
        }
    }

    private void pollLoop() {
        while (true) {
            try {
                pollOnce();
            } catch (RuntimeException | StackOverflowError fault) {
                // StackOverflowError: the belt under the depth pre-scan — the daemon must outlive any file
                System.out.println("catalog-promote poll_fault " + fault.getClass().getSimpleName()
                        + " (the last snapshot stands)");
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException stop) {
                return;
            }
        }
    }

    /**
     * ONE poll (the daemon's body; contract rows call it directly). True = a new snapshot was published.
     * The engine's write replaces {@code index.data} by delete-then-rename of {@code index.data.inprogress}
     * (≤ ~1 s absent): absent beside an {@code .inprogress} = torn, the last snapshot stands; absent for
     * two polls with none = the roll's "empty = gone" ({@code apply-catalog-markers.ps1}) = the empty state.
     * A KEPT-only lane (no DEV dir) never publishes here.
     */
    public boolean pollOnce() {
        Path dir = devDirOrNull;
        if (dir == null) {
            return false;
        }
        Path index = dir.resolve(INDEX_FILE);
        BasicFileAttributes attrs = null;
        try {
            attrs = Files.readAttributes(index, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException absent) {
            // fall through: absent
        }
        if (attrs == null || !attrs.isRegularFile()) {
            boolean inprogress = Files.exists(dir.resolve(INDEX_FILE + ".inprogress"), LinkOption.NOFOLLOW_LINKS);
            if (inprogress && ++inprogressPolls <= STALE_INPROGRESS_POLLS) {
                if (inprogressPolls == STALE_INPROGRESS_POLLS) {
                    System.out.println("catalog-promote stale_inprogress polls=" + inprogressPolls
                            + " (a failed engine rename; counted as absent from here)");
                }
                return false; // the engine's delete-then-rename window: torn, the last snapshot stands
            }
            if (!inprogress) {
                inprogressPolls = 0; // the stale count is CONSECUTIVE .inprogress sightings
            }
            if (++absentPolls < 2 || lastSignature == null || lastSignature.equals("absent")) {
                if (absentPolls >= 2 && lastSignature == null) {
                    lastSignature = "absent"; // never published: the base already IS the empty state
                }
                return false;
            }
            lastSignature = "absent";
            return publishDev(new Snapshot(List.of(), List.of()), "dev-index_absent");
        }
        absentPolls = 0;
        inprogressPolls = 0;
        String signature = attrs.lastModifiedTime().toMillis() + ":" + attrs.size();
        if (signature.equals(lastSignature)) {
            return false;
        }
        byte[] bytes;
        try {
            bytes = readBounded(index, INDEX_MAX_BYTES);
        } catch (IOException unreadable) {
            if (unreadable.getMessage() != null && unreadable.getMessage().startsWith("exceeds")) {
                lastSignature = signature; // logged once per generation
                System.out.println("catalog-promote index_refused reason=oversize cap=" + INDEX_MAX_BYTES
                        + " (the last snapshot stands)");
            }
            return false; // a mid-replace read retries at the next poll
        }
        lastSignature = signature;
        if (nestingExceeds(bytes, MAX_JSON_DEPTH)) {
            System.out.println("catalog-promote index_refused reason=nesting cap=" + MAX_JSON_DEPTH
                    + " (the last snapshot stands)");
            return false;
        }
        List<String> promoteStems;
        List<String> hidden;
        try {
            JSONObject o = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            promoteStems = lawfulStems(o.optJSONArray("promote"), "promote");
            hidden = lawfulStems(o.optJSONArray("remove"), "remove");
        } catch (JSONException | RuntimeException | StackOverflowError torn) {
            System.out.println("catalog-promote index_unreadable " + torn.getClass().getSimpleName()
                    + " (the last snapshot stands)");
            return false;
        }
        List<Candidate> candidates = new ArrayList<Candidate>(promoteStems.size());
        for (String stem : promoteStems) {
            candidates.add(readCandidate(dir, stem, VARIANT_SUFFIX, false));
        }
        return publishDev(new Snapshot(List.copyOf(candidates), List.copyOf(hidden)), "dev-index");
    }

    /** The DEV part replaced whole (the poller), then the merge. */
    private boolean publishDev(Snapshot dev, String cause) {
        synchronized (publishLock) {
            devPart = dev;
            mergeAndPublish(cause);
        }
        return true;
    }

    /** The KEPT part replaced whole (a scan), then the merge. Empty → empty publishes nothing: the merged
     *  state is unchanged, so the sim thread is not handed a redundant overlay apply (an absent or empty
     *  kept folder at boot = zero sim-thread work). */
    private void publishKept(List<Candidate> kept, String cause) {
        synchronized (publishLock) {
            // The writer successes held for THIS scan ride its snapshot
            pendingOutcomes.addAll(heldForRescan);
            heldForRescan.clear();
            if (kept.isEmpty() && keptPart.isEmpty() && pendingOutcomes.isEmpty()) {
                return;
            }
            keptPart = kept;
            mergeAndPublish(cause);
        }
    }

    /**
     * Caller holds {@link #publishLock}. ONE full-state snapshot of both
     * sources: the DEV promote list first, then every KEPT candidate whose ASCII-lower fold no DEV stem holds
     * (a duplicate = DEV wins, LOUD); the hidden list is the DEV index's. An unconsumed older snapshot is
     * simply superseded (the sim thread re-walks the merged state; memoized entries spend no pass).
     */
    private void mergeAndPublish(String cause) {
        Snapshot dev = devPart;
        Set<String> devFolds = new HashSet<String>();
        for (Candidate c : dev.promote()) {
            devFolds.add(ChatCommands.asciiLower(c.stem()));
        }
        List<Candidate> promote = new ArrayList<Candidate>(dev.promote());
        int kept = 0;
        for (Candidate c : keptPart) {
            if (devFolds.contains(ChatCommands.asciiLower(c.stem()))) {
                System.out.println("catalog-kept skip_dev_duplicate id=" + c.stem() + " (dev wins)");
                continue;
            }
            promote.add(c);
            kept++;
        }
        // The writer outcomes ride this snapshot; an unconsumed older one
        // is superseded, so its outcomes carry over (the sim thread only ever swaps the reference to null, so the
        // compare-and-set loop ends within one retry).
        List<KeptOutcome> fresh = List.copyOf(pendingOutcomes);
        pendingOutcomes.clear();
        Snapshot merged;
        while (true) {
            Snapshot prev = published.get();
            List<KeptOutcome> outcomes = fresh;
            if (prev != null && !prev.outcomes().isEmpty()) {
                List<KeptOutcome> carried = new ArrayList<KeptOutcome>(prev.outcomes());
                for (KeptOutcome o : fresh) {
                    if (carried.size() < KEPT_OUTCOMES_CAP) {
                        carried.add(o);
                    } else {
                        dropOutcome(o);
                    }
                }
                outcomes = List.copyOf(carried);
            }
            merged = new Snapshot(List.copyOf(promote), dev.hidden(), outcomes);
            if (published.compareAndSet(prev, merged)) {
                break;
            }
        }
        System.out.println("catalog-promote snapshot cause=" + cause + " promote=" + merged.promote().size()
                + " remove=" + merged.hidden().size() + " kept=" + kept
                + (merged.outcomes().isEmpty() ? "" : " outcomes=" + merged.outcomes().size()));
    }

    /**
     * The kept-store writer's job outcome (writer thread). It waits for the
     * next published snapshot: {@code rescanFollows} = the job changed the disk and its re-scan publishes next (so a
     * success rides the snapshot that lists or delists its stem); else it is published now. Past
     * {@link #KEPT_OUTCOMES_CAP} waiting outcomes it is dropped LOUD (its caller's booking then times out).
     */
    public void noteKeptOutcome(KeptOutcome outcome, boolean rescanFollows) {
        if (outcome == null) {
            return;
        }
        synchronized (publishLock) {
            if (pendingOutcomes.size() + heldForRescan.size() >= KEPT_OUTCOMES_CAP) {
                dropOutcome(outcome);
            } else if (rescanFollows) {
                heldForRescan.add(outcome); // only a KEPT scan publishes it: a DEV poll's snapshot predates the write
            } else {
                pendingOutcomes.add(outcome);
            }
            if (!rescanFollows) {
                mergeAndPublish("kept-outcome");
            }
        }
    }

    /** Caller holds {@link #publishLock}: ONE loud row per dropped outcome (its reason token only), counted. */
    private void dropOutcome(KeptOutcome outcome) {
        keptOutcomesDropped++;
        System.out.println("catalog-kept outcome_dropped reason=" + outcome.reason() + " cap=" + KEPT_OUTCOMES_CAP
                + " dropped=" + keptOutcomesDropped + " (its caller's answer times out to no result)");
    }

    /** Writer outcomes dropped at {@link #KEPT_OUTCOMES_CAP} (any thread reads). */
    public long keptOutcomesDropped() {
        return keptOutcomesDropped;
    }

    /** install-time — the book {@link #tick} settles and sweeps. */
    public void setReceiptBook(HostReceiptBook bookOrNull) {
        this.receiptsOrNull = bookOrNull;
    }

    /** One list's lawful stems, ≤ {@link #OVERLAY_CAP}, fold-deduped; an unlawful entry is never echoed,
     * and the skips print ONE summary line per list per generation (a 64 KiB list of junk is not a
     * 32 000-line log). */
    private static List<String> lawfulStems(JSONArray arr, String kind) {
        List<String> out = new ArrayList<String>();
        Set<String> folds = new HashSet<String>();
        int badStems = 0;
        int reserved = 0;
        int firstSkipped = -1;
        for (int i = 0; arr != null && i < arr.length(); i++) {
            Object raw = arr.opt(i);
            String s = raw instanceof String ? (String) raw : null;
            if (!stemLawful(s)) {
                badStems++;
                firstSkipped = firstSkipped < 0 ? i : firstSkipped;
                continue;
            }
            String fold = ChatCommands.asciiLower(s);
            if (fold.startsWith(SubmittedVariantLedger.ID_PREFIX) || fold.startsWith(VariantPolicy.WENDIGO_PREFIX)) {
                reserved++;
                firstSkipped = firstSkipped < 0 ? i : firstSkipped;
                continue;
            }
            if (!folds.add(fold)) {
                continue;
            }
            if (out.size() >= OVERLAY_CAP) {
                System.out.println("catalog-promote over_cap kind=" + kind + " cap=" + OVERLAY_CAP + " at_index=" + i);
                break;
            }
            out.add(s);
        }
        if (badStems + reserved > 0) {
            System.out.println("catalog-promote skip kind=" + kind + " bad_stems=" + badStems + " reserved_prefix="
                    + reserved + " first_index=" + firstSkipped);
        }
        return out;
    }

    /**
     * re-read the KEPT source and publish it — any thread (the boot
     * reader once; the Keep / Forget writer's re-scan on {@link CustomVariantPersistSink}'s thread); two scans never interleave
     * ({@link #scanLock}), and the merge takes {@link #publishLock}, which the DEV poller holds only for its
     * own merge. Never throws: a fault keeps the last kept part (the poller's belt).
     */
    public void scanKept() {
        scanKeptAs("kept-rescan");
    }

    /**
     * The serve's boot read of the kept ships that WILL list, on the CALLER's thread, before the port publishes.
     * WHY: the hidden-ships boot floor runs before the sim thread's first catalog apply puts a kept ship on the
     * listing, so a listing read there sees the shipped ships alone; a hidden file that hides every shipped ship
     * while kept ships remain would wrongly un-hide one at every restart (and the next save drops that hide).
     * The ONE kept scan runs here (the same body as {@link #scanKept}, publishing as it does) unless the boot
     * reader or a re-scan already completed one; either way it returns the stems that scan ACCEPTED, in
     * case-insensitive order: a refused body and a stem past {@link #KEPT_CAP} are never in it, and none ever on a
     * DEV-only lane.
     * Residue: a stem accepted here that the sim thread's registration then refuses never lists, so the floor
     * reads it as listed (a refusal at the engine is rare and loud on its own row). A scan fault returns the last
     * completed scan's stems (empty at boot), which keeps the floor's old, stricter reading.
     *
     */
    public List<String> keptBootListable() {
        synchronized (scanLock) {
            keptBootScanOnce();
            return keptAcceptedLast;
        }
    }

    /** The boot scan, once: the {@link #KEPT_READER_THREAD} daemon's body and {@link #keptBootListable}'s read. */
    private void keptBootScanOnce() {
        synchronized (scanLock) {
            if (!keptScanDone) {
                scanKeptAs("kept-boot");
            }
        }
    }

    private void scanKeptAs(String cause) {
        Path keptDir = keptDirOrNull;
        if (keptDir == null) {
            return;
        }
        synchronized (scanLock) {
            try {
                listInbox(keptDir.getParent());
                if (!Files.isDirectory(keptDir, LinkOption.NOFOLLOW_LINKS)) {
                    System.out.println("catalog-kept absent dir=" + PeerText.quote(keptDir.toString()));
                    keptIdsLast = List.of();
                    keptOverCapLast = false;
                    keptRefusedLast = 0;
                    supersedesByCopy = Map.of();
                    keptAtByCopy = Map.of();
                    publishKept(List.of(), cause);
                    keptAcceptedLast = List.of();
                    keptScanDone = true;
                    return;
                }
                Path real = keptDir.toRealPath(); // readCandidate's parent law compares REAL paths
                List<String> lawful = new ArrayList<String>();
                boolean over = false;
                int entries = 0;
                int badStems = 0;
                int reserved = 0;
                try (DirectoryStream<Path> listing = Files.newDirectoryStream(real, "*" + KEPT_SUFFIX)) {
                    for (Path p : listing) {
                        if (++entries > KEPT_SCAN_MAX_ENTRIES) {
                            over = true;
                            System.out.println("catalog-kept over_cap entries>=" + KEPT_SCAN_MAX_ENTRIES + " dir="
                                    + PeerText.quote(keptDir.toString()) + " (only the first " + KEPT_CAP
                                    + " lawful stems list - move files out)");
                            break;
                        }
                        String name = p.getFileName().toString();
                        String stem = name.endsWith(KEPT_SUFFIX) ? name.substring(0, name.length() - KEPT_SUFFIX.length()) : null;
                        if (!stemLawful(stem)) {
                            badStems++; // never echoed: the name is the file system's, not ours
                        } else if (PickableVariants.isReservedCustomId(stem)
                                || ChatCommands.asciiLower(stem).startsWith(VariantPolicy.WENDIGO_PREFIX)) {
                            reserved++;
                        } else {
                            lawful.add(stem);
                        }
                    }
                }
                lawful.sort(String.CASE_INSENSITIVE_ORDER);
                Set<String> folds = new HashSet<String>();
                List<String> stems = new ArrayList<String>(lawful.size());
                int foldDups = 0;
                for (String stem : lawful) {
                    if (folds.add(ChatCommands.asciiLower(stem))) {
                        stems.add(stem);
                    } else {
                        foldDups++; // a case-sensitive file system's twin: the first-sorted spelling reads
                    }
                }
                if (badStems + reserved + foldDups > 0) {
                    System.out.println("catalog-kept skip bad_stems=" + badStems + " reserved_prefix=" + reserved
                            + " fold_dups=" + foldDups);
                }
                if (stems.size() > KEPT_CAP) {
                    over = true; // never a silent subset
                    System.out.println("catalog-kept over_cap stems=" + stems.size() + " cap=" + KEPT_CAP);
                    stems = stems.subList(0, KEPT_CAP);
                }
                List<Candidate> read = new ArrayList<Candidate>(stems.size());
                List<String> accepted = new ArrayList<String>(stems.size());
                Map<String, String> links = new HashMap<String, String>();
                Map<String, Long> times = new HashMap<String, Long>();
                int refusedCount = 0;
                int metaUnread = 0;
                int timeUnknown = 0;
                for (String stem : stems) {
                    Candidate c = readCandidate(real, stem, KEPT_SUFFIX, true);
                    refusedCount += c.refusal() == null ? 0 : 1;
                    if (c.refusal() == null) {
                        accepted.add(stem);
                        // The copy's .meta "supersedes" link rides every scan, so a put-back after a restart
                        // still finds the copy to forget (absent = no link).
                        // the same one read yields the keep time for the newest-first order; no time in the
                        // sidecar = the copy file's modified time
                        KeptMeta meta = readKeptMeta(real, stem);
                        metaUnread += meta.unreadable() ? 1 : 0;
                        if (meta.supersedesOrNull() != null) {
                            links.put(stem, meta.supersedesOrNull());
                        }
                        Long at = meta.keptAtMsOrNull() != null ? meta.keptAtMsOrNull()
                                : modifiedMsOrNull(real.resolve(stem + KEPT_SUFFIX));
                        if (at != null) {
                            times.put(stem, at);
                        } else {
                            timeUnknown++;
                        }
                    }
                    read.add(c);
                }
                if (metaUnread + timeUnknown > 0) {
                    System.out.println("catalog-kept meta_unread=" + metaUnread + " time_unknown=" + timeUnknown);
                }
                keptIdsLast = List.copyOf(stems);
                keptOverCapLast = over;
                keptRefusedLast = refusedCount;
                supersedesByCopy = Map.copyOf(links);
                keptAtByCopy = Map.copyOf(times);
                publishKept(List.copyOf(read), cause);
                keptAcceptedLast = List.copyOf(accepted);
                keptScanDone = true;
            } catch (IOException | RuntimeException | StackOverflowError fault) {
                System.out.println("catalog-kept scan_fault " + fault.getClass().getSimpleName()
                        + " (the last kept part stands)");
            }
        }
    }

    /**
     * The inbox's export + forgotten-kept NAMES
     * — {@code cc-v…} / {@code kept-…} stems of regular {@code *.variant} files, whose
     * bodies are NEVER opened; ≤ {@link #KEPT_SCAN_MAX_ENTRIES} entries visited, ≤ {@link #INBOX_LIST_CAP} listed, each per
     * prefix (over = loud).
     * Listed whether or not the kept folder exists yet (the first Keep from an export needs this list).
     * A listing fault empties the list (and the links below) for this scan and never stops the kept read. Caller holds
     * {@link #scanLock}.
     * Each LISTED {@code kept-…} stem's {@code .meta} is read for its
     * {@code supersedes} link ({@link #readKeptMeta}: the kept scan's bounded read, NOFOLLOW, the real parent; never
     * an export's), published as {@link #supersedesByRemoved} before the names; unreadable sidecars = ONE count row.
     * The bounds hold PER PREFIX ({@link #listPrefix}) - the {@code kept-…}
     * names first, then the {@code cc-v…} names, each walked, sorted and cut on its own - so a busy match's exports
     * never push a removed kept ship (its put back and its link) out of the list; the list holds at most twice
     * {@link #INBOX_LIST_CAP} names.
     */
    private void listInbox(Path inbox) {
        List<String> names = new ArrayList<String>();
        boolean over = false;
        Path inboxReal = null;
        try {
            if (inbox != null && Files.isDirectory(inbox)) {
                inboxReal = inbox.toRealPath();
                List<String> exports = new ArrayList<String>();
                over = listPrefix(inbox, INBOX_KEPT_PREFIX, names);
                over |= listPrefix(inbox, INBOX_PREFIX, exports);
                names.addAll(exports);
            }
        } catch (IOException | RuntimeException fault) {
            names.clear();
            System.out.println("catalog-kept inbox_fault " + fault.getClass().getSimpleName() + " (no inbox names this scan)");
        }
        Map<String, String> links = new HashMap<String, String>();
        int metaUnread = 0;
        for (String stem : names) {
            if (inboxReal != null && stem.startsWith(INBOX_KEPT_PREFIX)) {
                KeptMeta meta = readKeptMeta(inboxReal, stem);
                metaUnread += meta.unreadable() ? 1 : 0;
                if (meta.supersedesOrNull() != null) {
                    links.put(stem, meta.supersedesOrNull());
                }
            }
        }
        if (metaUnread > 0) {
            System.out.println("catalog-kept inbox_meta_unread=" + metaUnread);
        }
        supersedesByRemoved = Map.copyOf(links);
        inboxIdsLast = List.copyOf(names);
        inboxOverCapLast = over;
    }

    /**
     * One prefix's inbox names into {@code into} - ≤ {@link #KEPT_SCAN_MAX_ENTRIES}
     * matching entries visited, sorted case-insensitively, cut to {@link #INBOX_LIST_CAP}; each overrun prints ONE row
     * naming the prefix (a constant, never a file name). True = an overrun. Caller holds {@link #scanLock}.
     */
    private static boolean listPrefix(Path inbox, String prefix, List<String> into) throws IOException {
        boolean over = false;
        int entries = 0;
        try (DirectoryStream<Path> listing = Files.newDirectoryStream(inbox, prefix + "*" + KEPT_SUFFIX)) {
            for (Path p : listing) {
                if (++entries > KEPT_SCAN_MAX_ENTRIES) {
                    over = true;
                    System.out.println("catalog-kept inbox_over_cap prefix=" + prefix + " entries>=" + KEPT_SCAN_MAX_ENTRIES
                            + " dir=" + PeerText.quote(inbox.toString()) + " (only names among the first entries list)");
                    break;
                }
                String name = p.getFileName().toString();
                String stem = name.endsWith(KEPT_SUFFIX) ? name.substring(0, name.length() - KEPT_SUFFIX.length()) : null;
                if (stemLawful(stem) && stem.startsWith(prefix) && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    into.add(stem);
                }
            }
        }
        into.sort(String.CASE_INSENSITIVE_ORDER);
        if (into.size() > INBOX_LIST_CAP) {
            over = true;
            System.out.println("catalog-kept inbox_over_cap prefix=" + prefix + " names=" + into.size() + " cap=" + INBOX_LIST_CAP);
            into.subList(INBOX_LIST_CAP, into.size()).clear();
        }
        return over;
    }

    /**
     * True when {@code [}/{@code {}/{@code (} nesting outside quoted strings exceeds {@code max} — this
     * jar's {@code JSONArray(JSONTokener)} opens on {@code (} as well as {@code [}, so
     * the parser recurses on all three. The scan honours both quote styles the 2010 tokener reads
     * ({@code "} and {@code '}) and that tokener has NO comment grammar ({@code nextClean} skips
     * whitespace only), so every opener the parser would recurse on is counted — {@link #pollLoop}'s
     * {@code StackOverflowError} catch is the belt beneath, not a path.
     */
    static boolean nestingExceeds(byte[] bytes, int max) {
        int depth = 0;
        int quote = 0;
        boolean escaped = false;
        for (byte b : bytes) {
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (b == '\\') {
                    escaped = true;
                } else if (b == quote) {
                    quote = 0;
                }
                continue;
            }
            if (b == '"' || b == '\'') {
                quote = b;
            } else if (b == '[' || b == '{' || b == '(') {
                if (++depth > max) {
                    return true;
                }
            } else if (b == ']' || b == '}' || b == ')') {
                depth--;
            }
        }
        return false;
    }

    /** One source's file read: {@code dir} = that source's REAL dir (the parent law compares against it);
     *  a KEPT body that parses also passes {@link #keptRefusalOrNull}. */
    private Candidate readCandidate(Path dir, String stem, String suffix, boolean kept) {
        Path file = dir.resolve(stem + suffix);
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !dir.equals(file.toRealPath().getParent())) {
                return refused(stem, "no_regular_variant_file", kept);
            }
            byte[] body = readBounded(file, VARIANT_MAX_BYTES);
            if (nestingExceeds(body, MAX_JSON_DEPTH)) {
                return refused(stem, "nesting cap=" + MAX_JSON_DEPTH, kept);
            }
            VariantDefinition def = VariantCatalogStore.parseVariantText(new String(body, StandardCharsets.UTF_8));
            if (!stem.equals(def.variantId())) {
                return refused(stem, "variant_id_mismatch id=" + PeerText.quote(def.variantId()), kept);
            }
            Function<VariantDefinition, String> policy = keptRefusalOrNull;
            String token = kept && policy != null ? policy.apply(def) : null;
            if (token != null) {
                return refused(stem, "policy " + PeerText.quote(PeerText.bound(token, REFUSAL_MESSAGE_MAX_CHARS)), true);
            }
            return new Candidate(stem, def, VariantDefinitionFrameCodec.encode(def, tables), null, kept);
        } catch (IOException | RuntimeException | StackOverflowError bad) {
            // the parser echoes the bad value in its message: bound it before the quote
            return refused(stem, "unreadable " + bad.getClass().getSimpleName() + ": "
                    + PeerText.quote(PeerText.bound(String.valueOf(bad.getMessage()), REFUSAL_MESSAGE_MAX_CHARS)), kept);
        }
    }

    private static Candidate refused(String stem, String reason, boolean kept) {
        System.out.println((kept ? "catalog-kept" : "catalog-promote") + " refused id=" + stem + " reason=" + reason);
        return new Candidate(stem, null, null, reason, kept);
    }

    private static byte[] readBounded(Path file, int cap) throws IOException {
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = in.readNBytes(cap + 1);
        }
        if (bytes.length > cap) {
            throw new IOException("exceeds " + cap + " B");
        }
        return bytes;
    }

    /**
     * SIM thread (the registry's one writer — {@code CustomVariantLane.drainTick}'s hook): take the
     * newest snapshot, spend ≤ 1 engine pass on it, and once every entry is decided apply the overlay +
     * clear the newly hidden ids' standing picks. Never throws: a fault drops the snapshot loud.
     */
    public void tick() {
        Snapshot next = published.getAndSet(null);
        if (next != null) {
            work = next;
            cursor = 0;
            faultRetries = 0;
            shippedNoticesChatted = 0; // The chat cap is per snapshot
            Set<String> folds = new HashSet<String>();
            for (String h : next.hidden()) {
                folds.add(ChatCommands.asciiLower(h));
            }
            workHiddenFolds = folds;
        }
        receiptsTick(next);
        Snapshot w = work;
        if (w == null) {
            return;
        }
        try {
            while (cursor < w.promote().size()) {
                Candidate c = w.promote().get(cursor++);
                admitExit = null;
                boolean spent = admit(c);
                if (c.kept() && admitExit != null) {
                    settleAdmitRefusal(c.stem(), admitExit);
                }
                if (spent) {
                    return; // this tick's engine pass is spent
                }
            }
            work = null;
            faultRetries = 0;
            apply(w);
            settleAwaiting();
        } catch (RuntimeException fault) {
            // The poller's signature latch would never republish a dropped
            // snapshot, so a walk is re-armed a bounded number of times (memoized entries re-walk pass-free)
            boolean retry = ++faultRetries <= FAULT_RETRIES;
            if (retry) {
                cursor = 0;
            } else {
                work = null;
            }
            System.out.println("catalog-promote tick_fault " + fault.getClass().getSimpleName() + ": "
                    + PeerText.quote(PeerText.bound(String.valueOf(fault.getMessage()), REFUSAL_MESSAGE_MAX_CHARS))
                    + (retry ? " (retry " + faultRetries + "/" + FAULT_RETRIES + " next tick)"
                            : " (snapshot dropped; the next index write retries)"));
        }
    }

    /**
     * The serve's BOOT walk of the snapshot the kept scan published — on the CALLER's thread (the serve thread, which
     * is the sim thread = the registry's one writer), BEFORE the port publishes. WHY:
     * {@link #tick} runs only from {@code CustomVariantLane.drainTick} (the round-start
     * gate and the in-round bands), so after a restart the kept ships were scanned ({@code kept-boot}, promote=1) but
     * registered only once a round began; until then the lobby's join probe, {@code /ship} and the editor's list
     * showed the shipped ships alone, and the host read their kept ship as lost. One tick per pass until the walk has
     * applied ({@code work == null}, nothing newer published) or the bound is reached (every candidate's pass + the
     * apply tick + the fault retries); a DEV index the poller publishes later rides the first gate tick as before.
     * Never throws ({@link #tick}'s own net). Returns the ticks spent (0 = nothing was published).
     */
    public int bootWalk() {
        int bound = KEPT_CAP + OVERLAY_CAP + FAULT_RETRIES + 2;
        int ticks = 0;
        while (ticks < bound && (work != null || published.get() != null)) {
            tick();
            ticks++;
        }
        System.out.println("catalog-promote boot_walk ticks=" + ticks + " kept_listed=" + keptListedCount
                + " registered=" + registeredFrames.size()
                + (work != null ? " (unfinished: the first gate tick continues it)" : ""));
        return ticks;
    }

    /**
     * The host verbs' final verdicts, sim thread. The new snapshot's writer
     * outcomes: a refusal settles its booking now; a write or a move waits for the listing — the walk's admit exit
     * ({@link #settleAdmitRefusal}) or the apply after it ({@link #settleAwaiting}) decides it. Then the sweep answers
     * every booking nothing settled in time. Its own catch: a mail fault never touches the walk.
     */
    private void receiptsTick(Snapshot nextOrNull) {
        HostReceiptBook book = receiptsOrNull;
        if (book == null) {
            return;
        }
        try {
            if (nextOrNull != null) {
                for (KeptOutcome o : nextOrNull.outcomes()) {
                    String key = o.ccvIdOrNull() != null ? o.ccvIdOrNull()
                            : o.tokenOrNull() != null ? o.tokenOrNull() : o.stemOrNull();
                    boolean success = "written".equals(o.reason()) || "forgot".equals(o.reason());
                    if (key == null) {
                        continue;
                    }
                    // The keep's armed supersede rides to the stem's Await on "written" alone; every other
                    // outcome drops it (an identical body hides nothing - ruling viii), one row each
                    Supersede supersede = supersedes.remove(key);
                    if ("already_kept".equals(o.reason())) {
                        // The same body kept again is a SUCCESS - the ship
                        // is on /ship as asked; the body names it (a kept stem is registered, so its name is known)
                        supersedeDropped(supersede, key, o.reason());
                        settleReceipt(key, HostReceiptBook.alreadyKept(shipNameOf(o.stemOrNull(), key)));
                    } else if (!success) {
                        supersedeDropped(supersede, key, o.reason());
                        settleReceipt(key, hostOutcomePhrase(o.reason()));
                    } else if (o.stemOrNull() != null && awaiting.size() < KEPT_OUTCOMES_CAP * 2) {
                        awaiting.put(o.stemOrNull(), new Await(key, o.tokenOrNull() != null, "forgot".equals(o.reason()),
                                "written".equals(o.reason()) ? supersede : null));
                    } else {
                        supersedeDropped(supersede, key, "awaiting_cap");
                    }
                }
            }
            if (book.pending() > 0) {
                book.sweep(System.currentTimeMillis());
            }
        } catch (RuntimeException mailFault) {
            receiptFaults++;
            System.out.println("catalog-kept receipt_fault " + mailFault.getClass().getSimpleName() + " faults=" + receiptFaults);
        }
    }

    /** A supersede that will not run: X stays listed, the host reads "added" / the refusal; ONE row says why. */
    private static void supersedeDropped(Supersede s, String key, String why) {
        if (s != null) {
            System.out.println("catalog-kept supersede_dropped id=" + key + " replaced=" + s.replacedId() + " reason=" + why
                    + " (the replaced ship stays on /ship)");
        }
    }

    /** A kept candidate the walk did not list: the booking waiting on its stem gets the exit's phrase. */
    private void settleAdmitRefusal(String stem, String exit) {
        Await a = awaiting.get(stem);
        if (a != null && !a.forget()) {
            awaiting.remove(stem);
            supersedeDropped(a.supersedeOrNull(), a.key(), exit);
            putBackCopyGone.remove(a.key());
            settleReceipt(a.key(), hostOutcomePhrase(exit));
        }
    }

    /** After the apply: a written stem now listed = kept (or put back); not listed = refused; a moved stem gone from
     *  the listing = removed (still listed: no verdict, the booking times out). Every waiting stem is decided. */
    private void settleAwaiting() {
        if (awaiting.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Await> e : awaiting.entrySet()) {
            String stem = e.getKey();
            Await a = e.getValue();
            boolean listed = keptListedBefore.contains(stem);
            // Every success body opens with the ship's quoted NAME (the stem
            // registered this serve, so the name is remembered even after it left the listing), never the stem
            String name = shipNameOf(stem, a.key());
            boolean copyGone = putBackCopyGone.remove(a.key());
            if (a.forget()) {
                if (!listed) {
                    settleReceipt(a.key(), HostReceiptBook.named(name, HostReceiptBook.REMOVED));
                }
            } else if (listed) {
                if (a.supersedeOrNull() != null) {
                    // The copy lists: NOW X leaves /ship (the hidden lane's hook,
                    // its own catch - a fault leaves X listed and the host reads "added" + one row)
                    settleReceipt(a.key(), runSupersede(a.supersedeOrNull(), stem) ? HostReceiptBook.changed(name)
                            : HostReceiptBook.added(name));
                } else if (a.restore()) {
                    settleReceipt(a.key(), HostReceiptBook.named(name,
                            copyGone ? HostReceiptBook.PUT_BACK_COPY_GONE : HostReceiptBook.PUT_BACK));
                } else {
                    settleReceipt(a.key(), HostReceiptBook.added(name));
                }
            } else {
                supersedeDropped(a.supersedeOrNull(), a.key(), "not_listed");
                settleReceipt(a.key(), hostOutcomePhrase("not_listed"));
            }
        }
        awaiting.clear();
    }

    /** The supersede's hook in its own catch: true = X left {@code /ship}. Sim thread. */
    private static boolean runSupersede(Supersede s, String copyStem) {
        try {
            boolean applied = s.hook().apply(copyStem);
            if (!applied) {
                System.out.println("catalog-kept supersede_failed copy=" + copyStem + " replaced=" + s.replacedId()
                        + " (the copy lists; the replaced ship stays - remove it by hand)");
            }
            return applied;
        } catch (RuntimeException hookFault) {
            System.out.println("catalog-kept supersede_failed copy=" + copyStem + " replaced=" + s.replacedId()
                    + " " + hookFault.getClass().getSimpleName() + ": "
                    + PeerText.quote(PeerText.bound(String.valueOf(hookFault.getMessage()), REFUSAL_MESSAGE_MAX_CHARS)));
            return false;
        }
    }

    /** The receipt's ship name - the remembered display name of
     *  {@code stemOrNull}, else the booking key (the id the host typed), bounded by {@link #chatShipName}. */
    private String shipNameOf(String stemOrNull, String key) {
        return chatShipName(stemOrNull == null ? null : pickable.displayNameOrNull(stemOrNull), key);
    }

    /** One settle in its own catch (counted + one row): the booking is forgotten before the mail runs. */
    private void settleReceipt(String key, String finalText) {
        HostReceiptBook book = receiptsOrNull;
        if (book == null) {
            return;
        }
        try {
            book.settle(key, finalText);
        } catch (RuntimeException mailFault) {
            receiptFaults++;
            System.out.println("catalog-kept receipt_fault " + mailFault.getClass().getSimpleName() + " faults=" + receiptFaults);
        }
    }

    /**
     * A writer outcome or an admit exit as the host ship verbs word it (each ≤ 43 chars).
     *
     */
    public static String hostOutcomePhrase(String reason) {
        return switch (String.valueOf(reason)) {
            case "name_clash", "inbox_name_taken" -> "name already used";
            case "already_kept" -> "already kept";
            case "pass_cap" -> "too many kept ships";
            case "not_kept" -> "already removed";
            case "io" -> "could not be saved";
            // the engine, the writer's unusable / policy tokens, a scan refusal, the memo, a base collision, a hidden
            // or engine-resolved id, a stem the apply did not list
            default -> "the server refused this build";
        };
    }

    /** True = an engine pass ran (the tick's budget). */
    private boolean admit(Candidate c) {
        if (c.refusal() != null) {
            admitExit = "poller"; // Each silent exit names itself for a booked host verb
            return false; // logged by the poller
        }
        String stem = c.stem();
        byte[] known = registeredFrames.get(stem);
        // A differing body for a stem THIS serve registered = a live UPDATE (the header's law)
        boolean update = known != null && !Arrays.equals(known, c.frame());
        if (known != null && !update) {
            return false; // the same body: listed, no pass, no re-fan
        }
        if (Arrays.equals(refusedFrames.get(stem), c.frame())) {
            admitExit = "memo";
            return false; // this body was refused already (a first pass or an update): memoized, silent
        }
        if (!update && pickable.baseHoldsFold(stem)) {
            // a shipped stem (or a case-variant of one) would register + fan + spool for the serve and never list.
            // The roll-bound wording is TRUE only for the shipped stem itself (the marker
            // UPDATEs its card in place); a case-variant is REFUSED by apply-catalog-markers.ps1 (the file
            // system folds case), so no roll carries it — the seat is told to rename.
            String canonical = pickable.baseCanonicalOrNull(stem);
            boolean exact = stem.equals(canonical);
            System.out.println("catalog-promote skip_base_collision id=" + stem + " (boot disk wins"
                    + (exact ? ")" : "; case-variant of " + canonical + " - the marker refuses it)"));
            if (!c.kept()) { // the roll-bound notices are DEV wording; a kept skip is this stdout row only
                shippedNotice(stem, candidateName(c), c.frame(), exact ? SHIPPED_EXACT_PHRASE : SHIPPED_CASE_CLASH_PHRASE);
            }
            admitExit = "base_collision";
            return false;
        }
        if (workHiddenFolds.contains(ChatCommands.asciiLower(stem))) {
            System.out.println("catalog-promote skip_hidden id=" + stem + " (hidden wins; no pass spent)");
            admitExit = "hidden";
            return false;
        }
        if (!update && resolvable.test(stem)) {
            // not a listed mod stem (the fold check above catches those) but the engine resolves it: a
            // VANILLA variant id (the marker refuses it — no roll) or an unlisted npc/ card (updates in place)
            System.out.println("catalog-promote skip_engine_resolves id=" + stem
                    + " (boot disk wins; an npc card's UPDATE rides the roll, a vanilla id never)");
            if (!c.kept()) {
                shippedNotice(stem, candidateName(c), c.frame(), SHIPPED_ENGINE_PHRASE);
            }
            admitExit = "engine_resolves";
            return false;
        }
        if (registeredFrames.size() + refusedFrames.size() >= passCap) {
            // an update at the cap is held too: its refusal memo would be one more entry (bounded, never silent)
            System.out.println("catalog-promote over_registered_cap id=" + stem + " pass_cap=" + passCap
                    + " registered=" + registeredFrames.size() + " refused=" + refusedFrames.size());
            admitExit = "pass_cap";
            return false;
        }
        Verdict verdict;
        try {
            verdict = update ? registrar.register(c.def(), true) : registrar.register(c.def());
            if (verdict == null) {
                verdict = Verdict.rejected(DoorRejectReasons.CUSTOM_ENGINE_FAULT, "registrar returned null");
            }
        } catch (RuntimeException | Error engineFailure) {
            verdict = Verdict.rejected(DoorRejectReasons.CUSTOM_ENGINE_FAULT,
                    engineFailure.getClass().getSimpleName() + ": " + engineFailure.getMessage());
        }
        registerCalls++;
        if (!verdict.isAccepted()) {
            refusedFrames.put(stem, c.frame());
            // the engine's detail may echo the body's own text: bound it before the quote
            System.out.println("catalog-promote " + (update ? "update_refused" : "engine_refused") + " id=" + stem
                    + " reason=" + verdict.reasonToken()
                    + " detail=" + PeerText.quote(PeerText.bound(String.valueOf(verdict.detail()), REFUSAL_MESSAGE_MAX_CHARS))
                    + (update ? " (the registered body keeps flying)" : ""));
            if (!c.kept()) { // a kept ship chats only its two listing lines, its refusal = this row
                chat(catalogLine(candidateName(c), update
                        ? "update refused: " + verdict.reasonToken() + " - the registered body keeps flying"
                        : "refused: " + verdict.reasonToken()));
            }
            admitExit = "engine";
            return true;
        }
        refusedFrames.remove(stem);
        registeredFrames.put(stem, c.frame());
        Integer slot = spoolIndex.get(stem);
        if (slot == null) {
            spool.add(c.frame()); // spool-visible BEFORE the fan (the lane's publish-before-fan order)
            spoolIndex.put(stem, spool.size() - 1);
        } else {
            spool.set(slot, c.frame()); // The update replaces in place — a late joiner folds the new body once
        }
        System.out.println("catalog-promote " + (update ? "updated" : "registered") + " id=" + stem
                + " hull=" + PeerText.quote(c.def().hullId())
                + " name=" + PeerText.quote(c.def().displayName()) + " registered=" + registeredFrames.size());
        // The registered body's display name rides every ship list's rows;
        // display only, one map put, sanitized at emission
        pickable.rememberName(stem, c.def().displayName());
        // The registered body's HULL rides beside its name: /ship's name+hull+id search and
        // the rows' hull label would otherwise miss every promoted ship until a restart's scan re-read its file
        pickable.rememberHull(stem, c.def().hullId());
        // A kept copy's keep time (the last kept scan's) orders every
        // ship list's kept rows newest first; an unknown time leaves the copy after the timed ones
        Long keptAt = c.kept() ? keptAtByCopy.get(stem) : null;
        if (keptAt != null) {
            pickable.rememberKeptAt(stem, keptAt);
        }
        if (fanOrNull != null) {
            try {
                fanOrNull.fanVariantDefinition(c.frame());
            } catch (RuntimeException fanFault) {
                // registered + spooled already: late joiners fold it; a live seat's miss is loud here
                System.out.println("catalog-promote fan_fault id=" + stem + " " + fanFault.getClass().getSimpleName());
            }
        }
        // A copy that supersedes another ship fans "changed by the host" - the room reads one ship, re-built
        chat(c.kept() ? catalogLine(candidateName(c), update || supersedePending(stem) ? KEPT_UPDATED_PHRASE : KEPT_ADDED_PHRASE, true)
                : catalogLine(candidateName(c), update ? "updated - the new body flies at your next respawn" : "promoted - live on /ship now"));
        return true;
    }

    /** The three boot-disk notice phrases (server constants: the line stays printable by construction;
     *  each ≤ 62 chars so a stem keeps ≥ 47 chars of room under the 120-char chat law before the cut). */
    static final String SHIPPED_EXACT_PHRASE = "is shipped in the mod - its update rides the next roll";
    static final String SHIPPED_CASE_CLASH_PHRASE = "clashes by case with a shipped stem - no roll, rename it";
    static final String SHIPPED_ENGINE_PHRASE = "is on the boot disk - npc card: next roll; vanilla id: no roll";

    /** The boot-disk notice for a stem the boot disk owns (either skip) — once per distinct body.
     *  At most {@link #SHIPPED_NOTICE_CHAT_CAP} of them CHAT per snapshot; the rest are
     *  stdout rows (the body memo stands either way, so a re-walk never re-notices). {@code name} = the chat line's
     *  ship name (the memo and the stdout row key on the stem). */
    private void shippedNotice(String stem, String name, byte[] frame, String phrase) {
        if (Arrays.equals(shippedNoticeFrames.put(stem, frame), frame)) {
            return;
        }
        if (shippedNoticesChatted < SHIPPED_NOTICE_CHAT_CAP) {
            shippedNoticesChatted++;
            chat(catalogLine(name, phrase));
        } else {
            System.out.println("catalog-promote shipped_notice_capped id=" + stem + " cap=" + SHIPPED_NOTICE_CHAT_CAP
                    + " (stdout only this poll: " + phrase + ")");
        }
    }

    /** One line to the seats; a chat fault never touches the registry / spool / fan already done. */
    private void chat(String line) {
        Chat chat = chatOrNull;
        if (chat == null) {
            return;
        }
        try {
            chat.line(line);
        } catch (RuntimeException chatFault) {
            System.out.println("catalog-promote chat_fault " + chatFault.getClass().getSimpleName());
        }
    }

    /**
     * The seats' catalog line held to the chat law ({@code PilotIntentDatagramServer.overrideClearedLine}'s
     * cut idiom: the name is cut to the room left, a trailing {@code ~} marking the cut).
     * the line carries the ship's DISPLAY NAME, never its id - and that name is player text sent to
     * every seat under author "server", so it is bounded HERE, the one seam every such line passes
     * ({@link #chatShipName}: printable ASCII only, ≤ {@value #CHAT_NAME_MAX_CHARS} chars); the phrase is a server
     * constant + a reject token, so the whole line is printable. Public for the contract row.
     */
    public static String catalogLine(String name, String phrase) {
        return catalogLine(name, phrase, false);
    }

    /** {@code kept} = the KEPT source's head ({@link #KEPT_LINE_HEAD}, the
     *  {@code '<name>' …} room lines); false = the DEV {@code catalog '<name>' …} line. */
    public static String catalogLine(String name, String phrase, boolean kept) {
        String head = kept ? KEPT_LINE_HEAD : "catalog '";
        String tail = "' " + phrase;
        String bounded = chatShipName(name, null);
        int room = ChatFrameCodec.TEXT_MAX_CHARS - head.length() - tail.length();
        String shown = bounded.length() <= room ? bounded : bounded.substring(0, Math.max(1, room - 1)) + "~";
        return head + shown + tail;
    }

    /**
     * A ship's display name as a chat line or a host receipt may carry it.
     * WHY: the name is player-authored (the builder typed it into the body), and these lines reach every seat or the
     * host under author "server" - so each char outside printable ASCII (0x20..0x7E) reads {@code ?}, the result is
     * cut to {@value #CHAT_NAME_MAX_CHARS} chars and trimmed. A null / blank name takes {@code fallbackOrNull} under
     * the same law (the id the host typed, a hull id); nothing usable = {@code a ship}. Never null, never empty.
     */
    public static String chatShipName(String nameOrNull, String fallbackOrNull) {
        String bounded = boundedName(nameOrNull);
        if (bounded.isEmpty()) {
            bounded = boundedName(fallbackOrNull);
        }
        return bounded.isEmpty() ? "a ship" : bounded;
    }

    private static String boundedName(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(raw.length(), CHAT_NAME_MAX_CHARS));
        for (int i = 0; i < raw.length() && out.length() < CHAT_NAME_MAX_CHARS; i++) {
            char c = raw.charAt(i);
            out.append(c >= 0x20 && c <= 0x7E ? c : '?');
        }
        return out.toString().trim();
    }

    /** The name a chat line shows for a candidate's body: its display name,
     *  else its hull id, else the stem (a DEV stem is the dev's own file name; a kept body always has a hull). */
    private static String candidateName(Candidate c) {
        VariantDefinition d = c.def();
        return chatShipName(d == null ? null : d.displayName(), d == null || isBlank(d.hullId()) ? c.stem() : d.hullId());
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** The overlay apply + ONE pick-clear call: newly hidden BASE ids, and
     *  the KEPT stems listed last apply and not now (a DEV stem leaving the index is NOT cleared: it STAYS). */
    private void apply(Snapshot w) {
        List<String> promoted = new ArrayList<String>();
        Set<String> keptNow = new HashSet<String>();
        for (Candidate c : w.promote()) {
            if (registeredFrames.containsKey(c.stem())) {
                promoted.add(c.stem());
                if (c.kept()) {
                    keptNow.add(c.stem());
                }
            }
        }
        PickableVariants.Applied applied = pickable.applyOverlay(promoted, w.hidden());
        keptNow.retainAll(applied.listedPromoted()); // listed = registered AND on the overlay (hidden / base fold drop it)
        List<String> left = new ArrayList<String>(keptListedBefore);
        left.removeAll(keptNow);
        List<String> toClear = new ArrayList<String>(applied.newlyHidden());
        toClear.addAll(left);
        keptListedBefore = keptNow;
        keptListedCount = keptNow.size();
        HideListener listener = hideListenerOrNull;
        int cleared = 0;
        if (listener != null && !toClear.isEmpty()) {
            try {
                cleared = listener.onHidden(List.copyOf(toClear));
            } catch (RuntimeException hideFault) {
                // the overlay is applied (the volatile write came first); a clear fault never hides that
                cleared = -1;
                System.out.println("catalog-promote hide_fault " + hideFault.getClass().getSimpleName() + ": "
                        + PeerText.quote(PeerText.bound(String.valueOf(hideFault.getMessage()), REFUSAL_MESSAGE_MAX_CHARS)));
            }
        }
        System.out.println("catalog-promote applied listed=" + applied.listedPromoted().size() + " newly_hidden="
                + applied.newlyHidden().size() + " picks_cleared=" + cleared + " kept_left=" + left.size());
    }

    /** The admission spool's tail: every body this lane registered, registration order — a delisted
     * id's frame STAYS. Any thread. */
    public List<byte[]> frames() {
        return List.copyOf(spool);
    }

    /** Engine passes run so far (contract rows). Sim thread. */
    public int registerCalls() {
        return registerCalls;
    }

    /** The KEPT status accessors (any thread; their NAMES are the
     *  status lane's contract). The kept stems LISTED at the last overlay apply. */
    public int keptListed() {
        return keptListedCount;
    }

    /** The stems the last kept scan read (≤ {@link #KEPT_CAP}, case-insensitive order; refused ones included). */
    public List<String> keptIds() {
        return keptIdsLast;
    }

    /** The inbox's forgotten-kept then export stems the last scan listed (≤ {@link #INBOX_LIST_CAP} of each prefix;
     *  no body opened). */
    public List<String> inboxIds() {
        return inboxIdsLast;
    }

    /** The last kept scan stopped at {@link #KEPT_SCAN_MAX_ENTRIES} entries or cut to {@link #KEPT_CAP} stems. */
    public boolean keptOverCap() {
        return keptOverCapLast;
    }

    /** The last inbox listing stopped at a prefix's entry bound or cut a prefix to {@link #INBOX_LIST_CAP} names. */
    public boolean inboxOverCap() {
        return inboxOverCapLast;
    }

    /** Kept candidates the last scan REFUSED before the engine (unreadable, id mismatch, policy). */
    public int keptRefused() {
        return keptRefusedLast;
    }

    /** The KEPT source dir (absolute), or null = a DEV-only lane. */
    public Path keptDirOrNull() {
        return keptDirOrNull;
    }
}
