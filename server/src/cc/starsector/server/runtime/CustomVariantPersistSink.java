package cc.starsector.server.runtime;

import cc.starsector.server.bridge.RealEngineVariantRegistrar;
import cc.starsector.server.ops.PeerText;
import cc.starsector.server.runtime.SubmittedVariantLedger.Accepted;
import cc.starsector.server.runtime.VariantDefinitionFrameCodec.VariantDefinition;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.json.JSONObject;

/**
 * The server re-serialization step: the OFF-THREAD
 * persistence writer for accepted customs — one {@code <persistDir>/<stamp>/<canonicalId>.variant}
 * per accept (the engine-JSON shape {@link RealEngineVariantRegistrar#toEngineJson} — what
 * {@code new HullVariantSpec(JSONObject)} and {@link VariantCatalogStore#parseVariant} both read)
 * plus one {@code ledger.ndjson} audit line per accept, and the {@code custom-export <id>} inbox
 * copy ({@code <inbox>/<id>.variant} + {@code <id>.meta}) on operator request.
 *
 * <p>Write-only EVIDENCE, never catalog: nothing parses these files back this task (a restart
 * forgets customs by design — {@link SubmittedVariantLedger} javadoc). The {@link
 * GameplayEdgeEventWatchSink} idiom verbatim (frame-loop residency law): the
 * sim thread's {@link #offer} only FORMATS (org.json escaping — player text is DATA)
 * and {@code queue.offer}s an immutable item; every {@code Files} call lives on the
 * {@value #THREAD_NAME} daemon (a sim-residency allow row). Queue full = the
 * item is dropped and counted (LOUD once); the serve-end {@link #offerAll} catch-up re-feeds the
 * ledger snapshot and the writer dedupes by id, so a dropped accept still lands at close.
 *
 * <p>Path law: the file name is the SERVER-MINTED canonical id
 * ({@code cc-v<serial>-<slug>}, slug = {@code [a-z0-9-]}) — never the client-local id or the
 * display name; {@link #lawfulFileStem} is the belt. The persist/inbox dirs are operator
 * configuration, never wire-derived. Every failure is fail-soft and LOUD once — a disk error
 * must never crash a serve.
 *
 * <p>The KEPT store's writer jobs ("Keep for everyone")
 * ride the SAME queue and thread — {@link #offerKeep} (an accepted custom of this run),
 * {@link #offerKeepFromExport} (an inbox file, read HERE under the kept-read law) and {@link #offerForget}
 * (a kept file moved back to the inbox). The sim thread only formats + enqueues; this writer never
 * overwrites a differing body (a Forget replaces only a byte-equal inbox twin; the {@code .meta} sidecar is
 * replaced, {@link #keepWrite}) and never deletes; after a job that changed the disk it runs the catalog
 * lane's kept re-scan ({@link #setKeptRescan}). A kept failure is counted ({@link #keptFailed}) and named in
 * {@link #keptLast}; it never retires the sink (retire stays the ledger writer's law).
 */
public final class CustomVariantPersistSink implements AutoCloseable {

    public static final String THREAD_NAME = "cc-custom-persist";
    public static final String LEDGER_FILE = "ledger.ndjson";
    public static final String VARIANT_EXT = ".variant";
    public static final String META_EXT = ".meta";
    /** The kept {@code .meta} field naming the ship a supersede's copy replaced. */
    public static final String SUPERSEDES_KEY = "supersedes";
    /** The ledger row's accept time (epoch ms); a KEEP's {@code .meta}
     *  carries it as the keep time, and the kept scan reads it for the kept copies' newest-first order. */
    public static final String ACCEPTED_AT_KEY = "acceptedAtMs";
    /** A KEEP_FROM_EXPORT {@code .meta}'s keep time (epoch ms), read
     *  first by the kept scan for the same order. */
    public static final String KEPT_AT_KEY = "keptAtMs";
    /** Sim thread only (the operator drain, like {@link #offerKeep}): custom id -> the ship it replaces, set by
     *  {@code CustomVariantOps.shipKeep} right before the keep and TAKEN by {@link #offerKeep}; never a file write.
     *  A put-back's inbox name -> the root it still stands for, set by
     *  {@code CustomVariantOps.shipRestoreKept} and TAKEN by {@link #offerKeepFromExport} the same way. */
    private final Map<String, String> supersedeNotes = new HashMap<>();

    /** The next {@link #offerKeep} of {@code ccvId} writes {@code supersedes=replacedId}. */
    public void noteSupersedes(String ccvId, String replacedId) {
        if (ccvId != null && replacedId != null) {
            supersedeNotes.put(ccvId, replacedId);
        }
    }

    /** The keep did not queue: drop its note so a later plain keep of the same id writes no link. */
    public void forgetSupersedeNote(String ccvId) {
        supersedeNotes.remove(ccvId);
    }
    /** Bounded caller→writer hand-off (customs are ≤ 64 per serve; exports are operator-rare). */
    static final int QUEUE_CAP = 256;
    private static final long CLOSE_JOIN_MILLIS = 2_000L;

    /** One queued writer job — the accept / export {@link Item}
     *  (byte-identical path) or one of the three kept-store jobs; the writer dispatches on the type. */
    sealed interface Job permits Item, KeepJob, KeepFromExportJob, ForgetJob {
    }

    /** One queued write, built whole on the caller thread: {@code ledgerLine == null} marks an
     * export (meta beside the variant, no ndjson row, never deduped). */
    record Item(Path dir, String canonicalId, String variantText, String ledgerLine, String metaText) implements Job {
    }

    /** KEEP: built whole on the sim thread ({@link #keptStem} + {@link #keptVariantText} are pure). */
    record KeepJob(Accepted a, Path keptDir, String stem, String text, String meta) implements Job {
    }

    /** KEEP_FROM_EXPORT: the writer reads {@code <inboxDir>/<name>.variant} (never its {@code .meta});
     *  {@code supersedesOrNull} = the link the new kept {@code .meta} carries (the sim thread's note, null = none). */
    record KeepFromExportJob(String name, Path inboxDir, Path keptDir, String supersedesOrNull) implements Job {
    }

    /** FORGET: move {@code <keptDir>/<stem>.variant} (+ {@code .meta}) back into the inbox. */
    record ForgetJob(String stem, Path keptDir, Path inboxDir) implements Job {
    }

    private static final Item CLOSE_SIGNAL = new Item(null, "", "", null, null);

    /** The kept stem's head + its length bound, ≤ 28. */
    public static final String KEPT_STEM_PREFIX = "kept-";
    static final int KEPT_STEM_MAX_CHARS = 28;
    static final int KEPT_SLUG_MAX_CHARS = 16;
    /** {@link #keptLast} bound and the ban-answer memory bound ({@link #keptStemOf}; oldest dropped). */
    static final int KEPT_LAST_MAX_CHARS = 160;
    static final int KEPT_STEM_MEMORY_CAP = 256;

    private final Path dir;
    private final ArrayBlockingQueue<Job> queue = new ArrayBlockingQueue<>(QUEUE_CAP);
    private final Thread writerThread;
    /** Caller view: the sink stopped accepting (close / writer failure). */
    private volatile boolean retired;
    /** Caller thread only. */
    private long queued;
    private long dropped;
    /** Writer thread only (volatile for the close receipt). */
    private volatile long variantsWritten;
    private volatile long exportsWritten;
    /** An export item whose disk write THREW (counted; since L3-loops-1 the writer continues). */
    private volatile long exportsFailed;
    private volatile String error = "";
    private final Set<String> writtenIds = new HashSet<>();
    /** Consecutive item failures that retire the writer. */
    static final int RETIRE_AFTER_CONSECUTIVE_FAILURES = 8;
    /** Bound on the once-per-class failure log set. */
    private static final int LOGGED_FAILURE_CLASS_CAP = 16;
    /** writer-thread belt state (volatile meters for the receipt). */
    private volatile long persistFailed;
    private int consecutiveFailures;
    private volatile boolean writerRetired;
    private volatile long abandonedAfterRetire;
    private final Set<String> loggedFailureClasses = new HashSet<>();
    /** caller-thread offers refused because the sink was retired. */
    private long refusedRetired;
    /** The kept-store hooks (install-time) + meters (any thread reads). */
    private volatile Runnable keptRescanOrNull;
    private volatile Function<VariantDefinition, String> policyRefusalOrNull;
    private volatile String keptLast = "-";
    private final AtomicLong keptFailed = new AtomicLong();
    private volatile long keptWritten;
    private final ConcurrentHashMap<String, String> keptStemById = new ConcurrentHashMap<>();
    /** Writer thread only: {@link #keptStemById}'s insertion order (the oldest id drops past the cap; a Forget
     *  drops its stem's ids, {@link #dropKeptStem}). */
    private final ArrayDeque<String> keptStemOrder = new ArrayDeque<>();
    /** Where each kept job's outcome goes (install-time; the catalog lane's
     *  {@code noteKeptOutcome}, which hands it to the sim thread on its snapshot — this thread never mails), and the
     *  job's outcome as its handler decided it (writer thread only; reset per job). */
    private volatile BiConsumer<CatalogPromotionLane.KeptOutcome, Boolean> keptOutcomesOrNull;
    private String jobReason;
    private String jobStem;

    /** this serve's stamp (epoch ms at open, &gt; 0) - the suffix an export takes
     *  when an earlier serve's export with different bytes already holds its name ({@link #landExport}). */
    private final long openedMillis = System.currentTimeMillis();

    private CustomVariantPersistSink(Path dir) {
        this.dir = dir;
        this.writerThread = new Thread(this::drain, THREAD_NAME);
        this.writerThread.setDaemon(true);
    }

    /**
     * Create {@code dir} and start the writer; prints {@code custom-variant persist_sink path=…}
     * and returns null after a LOUD {@code persist_sink_open_failed} line on any failure
     * (fail-soft: the lane serves without persistence). {@code null} dir = persistence off.
     */
    public static CustomVariantPersistSink openOrNull(File dirOrNull) {
        if (dirOrNull == null) {
            System.out.println("custom-variant persist_sink path=none (custom.persistDir blank)");
            return null;
        }
        try {
            Path dir = dirOrNull.toPath().toAbsolutePath();
            Files.createDirectories(dir);
            CustomVariantPersistSink sink = new CustomVariantPersistSink(dir);
            sink.writerThread.start();
            System.out.println("custom-variant persist_sink path=" + dir + " writer=off-thread");
            return sink;
        } catch (Throwable open) {
            System.out.println("custom-variant persist_sink_open_failed path=" + dirOrNull
                    + " error=" + open.getClass().getName()
                    + ": " + (open.getMessage() == null ? "" : open.getMessage()));
            return null;
        }
    }

    public Path dir() {
        return dir;
    }

    /** Caller thread: items handed to the writer so far / dropped on a full queue. */
    public long queued() {
        return queued;
    }

    public long dropped() {
        return dropped;
    }

    /** {@code custom.exported}: inbox copies the WRITER actually landed on disk
     * (never the queue offer — a queued export can still fail or be dropped at close). */
    public long exportsWritten() {
        return exportsWritten;
    }

    /** Export items whose disk write threw (counted; the writer continues — L3-loops-1). */
    public long exportsFailed() {
        return exportsFailed;
    }

    /** Items (variant or export) whose disk write threw. */
    public long persistFailed() {
        return persistFailed;
    }

    /** Items lost to retirement — caller offers
     * refused while retired + queued items the retired writer abandoned. */
    public long persistRetired() {
        return refusedRetired + abandonedAfterRetire;
    }

    /** Whether the sink stopped accepting. */
    public boolean retired() {
        return retired;
    }

    /** Variant items the writer landed on disk. */
    public long variantsWritten() {
        return variantsWritten;
    }

    /** install-time — the catalog lane's kept re-scan ({@code
     *  CatalogPromotionLane::scanKept}), run on THIS writer thread after every kept job that changed the
     *  disk; null = none (a fixture sink). */
    public void setKeptRescan(Runnable rescanOrNull) {
        keptRescanOrNull = rescanOrNull;
    }

    /** install-time — each kept job's outcome ({@code rescanFollows} = the
     *  job changed the disk and the re-scan below will publish it); null = none (a fixture sink). */
    public void setKeptOutcomes(BiConsumer<CatalogPromotionLane.KeptOutcome, Boolean> outcomesOrNull) {
        keptOutcomesOrNull = outcomesOrNull;
    }

    /** Install-time: the serve's per-file custom policy (a refusal token, null = lawful) for a kept-from-export
     *  body; null = no writer-side check (the kept re-scan re-checks every listed body against the same policy). */
    public void setPolicyRefusal(Function<VariantDefinition, String> policyOrNull) {
        policyRefusalOrNull = policyOrNull;
    }

    /** The last kept-job verdict sentence (≤ {@value #KEPT_LAST_MAX_CHARS} chars; {@code -} = none yet). */
    public String keptLast() {
        return keptLast;
    }

    /** Kept jobs the writer refused or failed (I/O); a refused OFFER is the caller's count (it got {@code false}). */
    public long keptFailed() {
        return keptFailed.get();
    }

    /** KEEP / KEEP_FROM_EXPORT jobs that landed a new kept file. */
    public long keptWritten() {
        return keptWritten;
    }

    /** The stem the kept store holds for this run's accepted {@code canonicalId} (its last KEEP: written or
     *  already kept), null = none this run or Forgotten since — the ban answer's input; a runtime memory, empty
     *  after a restart. */
    public String keptStemOf(String canonicalId) {
        return canonicalId == null ? null : keptStemById.get(canonicalId);
    }

    /**
     * Sim thread (the drain, right after {@code ledger.recordAccepted}): format the
     * {@code .variant} text + the ndjson line and enqueue — never touches the file. Returns
     * whether the item was queued (false = retired or dropped, counted + LOUD once).
     */
    public boolean offer(Accepted a) {
        Objects.requireNonNull(a, "accepted");
        if (retired) {
            refusedRetired++; // A retired-sink refusal is metered (persist_retired=)
            return false;
        }
        try {
            // The stem belt sits INSIDE the try — a mint bug
            // is LOUD + retires the sink (fail-soft); it never escapes onto the sim thread's drain,
            // where a throw would abort the accept's VACK + VDEF fan behind an already-recorded ledger row.
            String stem = lawfulFileStem(a.canonicalId());
            Item item = new Item(dir, stem, variantText(a), ledgerLine(a, System.currentTimeMillis()), null);
            return enqueue(item);
        } catch (RuntimeException failure) {
            System.out.println("custom-variant persist_sink_failed path=" + dir + " queued=" + queued
                    + " error=" + failure.getClass().getName() + ": "
                    + (failure.getMessage() == null ? "" : failure.getMessage()));
            retire();
            return false;
        }
    }

    /** Serve-end catch-up: re-offer the ledger snapshot (the writer dedupes by id). Returns the count queued. */
    public int offerAll(List<Accepted> snapshot) {
        int n = 0;
        for (Accepted a : snapshot) {
            if (offer(a)) {
                n++;
            }
        }
        return n;
    }

    /**
     * {@code custom-export <id>}: queue {@code <inboxDir>/<id>.variant} + {@code .meta}
     * — the standing mint flow's intake. Sim thread; same fail-soft law.
     */
    public boolean offerExport(Accepted a, File inboxDir) {
        Objects.requireNonNull(a, "accepted");
        Objects.requireNonNull(inboxDir, "inboxDir");
        if (retired) {
            refusedRetired++; // Metered (persist_retired=)
            return false;
        }
        try {
            String stem = lawfulFileStem(a.canonicalId()); // inside the try: same fail-soft law as offer()
            Item item = new Item(inboxDir.toPath().toAbsolutePath(), stem,
                    variantText(a), null, metaText(a, System.currentTimeMillis()));
            return enqueue(item);
        } catch (RuntimeException failure) {
            System.out.println("custom-variant persist_sink_failed path=" + dir + " queued=" + queued
                    + " error=" + failure.getClass().getName() + ": "
                    + (failure.getMessage() == null ? "" : failure.getMessage()));
            retire();
            return false;
        }
    }

    /**
     * {@code custom-keep <id>} — SIM thread: mint the kept stem, the
     * kept text (its {@code variantId} = the stem) and the meta, and enqueue a KEEP; never touches a file.
     * Fail-soft inside the {@code try} (the {@link #offer} law: nothing escapes onto the sim thread), but a
     * kept fault never retires the sink. Returns whether the job was queued (false = retired / full / fault).
     */
    public boolean offerKeep(Accepted a, Path keptDir) {
        Objects.requireNonNull(a, "accepted");
        Objects.requireNonNull(keptDir, "keptDir");
        if (retired) {
            refusedRetired++;
            return false;
        }
        String stem = a.canonicalId();
        String supersedes = supersedeNotes.remove(a.canonicalId());
        try {
            stem = keptStem(a.canonical());
            KeepJob job = new KeepJob(a, keptDir.toAbsolutePath(), stem, keptVariantText(a.canonical(), stem),
                    keptMetaText(a, stem, System.currentTimeMillis(), supersedes));
            return enqueue(job);
        } catch (RuntimeException failure) {
            keptOfferFailed("keep", stem, failure);
            return false;
        }
    }

    /** {@code custom-keep-export <name>} — SIM thread: enqueue the writer's read of {@code <inboxDir>/<name>.variant}
     *  ({@code name} passes {@link #lawfulFileStem} here, before any path is built). Same fail-soft law. */
    public boolean offerKeepFromExport(String name, Path inboxDir, Path keptDir) {
        Objects.requireNonNull(inboxDir, "inboxDir");
        Objects.requireNonNull(keptDir, "keptDir");
        if (retired) {
            refusedRetired++;
            return false;
        }
        String supersedes = supersedeNotes.remove(name);
        try {
            return enqueue(new KeepFromExportJob(lawfulFileStem(name), inboxDir.toAbsolutePath(),
                    keptDir.toAbsolutePath(), supersedes));
        } catch (RuntimeException failure) {
            keptOfferFailed("keep", String.valueOf(name), failure);
            return false;
        }
    }

    /** {@code custom-forget <stem>} — SIM thread: enqueue the move of a kept file back to the inbox. Same law. */
    public boolean offerForget(String stem, Path keptDir, Path inboxDir) {
        Objects.requireNonNull(keptDir, "keptDir");
        Objects.requireNonNull(inboxDir, "inboxDir");
        if (retired) {
            refusedRetired++;
            return false;
        }
        try {
            return enqueue(new ForgetJob(lawfulFileStem(stem), keptDir.toAbsolutePath(), inboxDir.toAbsolutePath()));
        } catch (RuntimeException failure) {
            keptOfferFailed("forget", String.valueOf(stem), failure);
            return false;
        }
    }

    /** A kept offer that could not be built (a mint bug): ONE row + {@link #keptLast}; the caller counts the false. */
    private void keptOfferFailed(String job, String stem, RuntimeException failure) {
        keptLast = boundLast("could not queue " + stem + " (" + failure.getClass().getSimpleName() + ")");
        System.out.println("custom-variant kept_" + job + "_failed stem=" + PeerText.quote(stem)
                + " error=" + failure.getClass().getSimpleName());
    }

    private boolean enqueue(Job item) {
        if (queue.offer(item)) {
            queued++;
            return true;
        }
        dropped++;
        if (dropped == 1) {
            System.out.println("custom-variant persist_sink_drop path=" + dir + " queued=" + queued
                    + " (queue full — the writer thread is behind the disk; dropping, never"
                    + " blocking the sim thread; the close catch-up re-feeds the snapshot)");
        }
        return false;
    }

    // ------------------------------------------------------------------ pure formatting (caller thread)

    /** The on-disk {@code .variant} text: the engine-JSON shape, pretty-printed. */
    static String variantText(Accepted a) {
        try {
            return RealEngineVariantRegistrar.toEngineJson(a.canonical()).toString(2) + "\n";
        } catch (org.json.JSONException impossible) {
            throw new IllegalStateException("engine JSON of an accepted custom failed", impossible);
        }
    }

    /** One {@code ledger.ndjson} row — org.json escapes the player text (never hand-concatenated). */
    static String ledgerLine(Accepted a, long nowMs) {
        try {
            JSONObject o = new JSONObject();
            o.put("canonicalId", a.canonicalId());
            o.put("serial", a.serial());
            o.put("seat", a.seatId());
            o.put("session", a.sessionId());
            o.put("localId", a.localId());
            o.put("hullId", a.canonical().hullId());
            o.put("displayName", a.canonical().displayName());
            o.put("bodyCrc", a.bodyCrc());
            o.put("unusedOp", a.unusedOp());
            o.put(ACCEPTED_AT_KEY, nowMs);
            return o.toString() + "\n";
        } catch (org.json.JSONException impossible) {
            throw new IllegalStateException("ledger row of an accepted custom failed", impossible);
        }
    }

    /** The export's {@code .meta} sidecar (same fields, pretty, + the file it describes). */
    static String metaText(Accepted a, long nowMs) {
        try {
            JSONObject o = new JSONObject(ledgerLine(a, nowMs));
            o.put("file", a.canonicalId() + VARIANT_EXT);
            o.put("source", "custom-export");
            return o.toString(2) + "\n";
        } catch (org.json.JSONException impossible) {
            throw new IllegalStateException("meta row of an accepted custom failed", impossible);
        }
    }

    /** The belt under the minted-id law: {@code [A-Za-z0-9._-]}, 1..80, never a separator or
     * a dot-run — a violation is a mint bug, thrown here so no such name reaches a path. */
    static String lawfulFileStem(String canonicalId) {
        if (canonicalId == null || canonicalId.isEmpty() || canonicalId.length() > 80
                || canonicalId.contains("..")) {
            throw new IllegalArgumentException("unlawful canonical id for a file stem: " + canonicalId);
        }
        for (int i = 0; i < canonicalId.length(); i++) {
            char c = canonicalId.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            if (!ok) {
                throw new IllegalArgumentException("unlawful canonical id for a file stem: " + canonicalId);
            }
        }
        return canonicalId;
    }

    /**
     * The kept stem law —
     * {@code kept-<slug>-<hex6>}. {@code slug} = the display name's ASCII-lower {@code [a-z0-9]} runs joined
     * by {@code -}, cut to ≤ {@value #KEPT_SLUG_MAX_CHARS} chars with no trailing {@code -} (empty → the hull
     * id's slug, then {@code ship}): the host reads the builder's name in the kept list, but no player text
     * reaches a path beyond that charset. {@code hex6} = the first 6 hex of SHA-256 over the engine JSON with
     * its {@code variantId} REMOVED, so the same body mints the same stem under any {@code cc-v} id (an
     * earlier run's export of it included) and an equal kept file reads "already kept"; a 6-hex collision
     * of two different bodies is a refusal at the writer (bytes differ), never a swap. The result is ≤
     * {@value #KEPT_STEM_MAX_CHARS} chars of {@code [a-z0-9-]} and passes both {@link
     * CatalogPromotionLane#stemLawful} and {@link #lawfulFileStem}; a violation is a mint bug, thrown.
     * Public = the contract rows pin it (pure).
     */
    public static String keptStem(VariantDefinition v) {
        Objects.requireNonNull(v, "variant");
        String slug = keptSlug(v.displayName());
        if (slug.isEmpty()) {
            slug = keptSlug(v.hullId());
        }
        if (slug.isEmpty()) {
            slug = "ship";
        }
        String stem = KEPT_STEM_PREFIX + slug + "-" + sha256Hex(idNormalisedJson(v)).substring(0, 6);
        if (stem.length() > KEPT_STEM_MAX_CHARS || !stem.matches("[a-z0-9-]+")
                || !CatalogPromotionLane.stemLawful(stem)) {
            throw new IllegalStateException("kept stem mint broke its law: " + stem);
        }
        return lawfulFileStem(stem);
    }

    /** ASCII-lower {@code [a-z0-9]} runs joined by {@code -}, ≤ {@value #KEPT_SLUG_MAX_CHARS} chars, no edge {@code -}. */
    static String keptSlug(String text) {
        StringBuilder out = new StringBuilder();
        boolean gap = false;
        for (int i = 0; text != null && i < text.length() && out.length() < KEPT_SLUG_MAX_CHARS; i++) {
            char c = text.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                c = (char) (c + ('a' - 'A'));
            }
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                if (gap && out.length() > 0) {
                    out.append('-');
                }
                gap = false;
                out.append(c);
            } else {
                gap = true;
            }
        }
        String slug = out.length() > KEPT_SLUG_MAX_CHARS ? out.substring(0, KEPT_SLUG_MAX_CHARS) : out.toString();
        while (slug.endsWith("-")) {
            slug = slug.substring(0, slug.length() - 1);
        }
        return slug;
    }

    /** The engine JSON without its {@code variantId} member ({@code toString()}): the stem hash's input. */
    static String idNormalisedJson(VariantDefinition v) {
        JSONObject json = RealEngineVariantRegistrar.toEngineJson(v);
        json.remove("variantId");
        return json.toString();
    }

    private static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a required JVM algorithm", impossible);
        }
    }

    /** The kept {@code .variant} text: the engine JSON with {@code variantId} SET to the stem (the catalog
     *  lane's {@code variantId == stem} read law), pretty-printed + newline (the {@link #variantText} shape). */
    static String keptVariantText(VariantDefinition v, String stem) {
        try {
            JSONObject json = RealEngineVariantRegistrar.toEngineJson(v);
            json.put("variantId", stem);
            return json.toString(2) + "\n";
        } catch (org.json.JSONException impossible) {
            throw new IllegalStateException("engine JSON of a kept custom failed", impossible);
        }
    }

    /** A KEEP's {@code .meta}: the {@link #metaText} fields for the kept file + {@code keptFrom} (the accepted id). */
    static String keptMetaText(Accepted a, String stem, long nowMs) {
        return keptMetaText(a, stem, nowMs, null);
    }

    /**
     * + {@code supersedes} = the ship id (shipped) or kept stem this copy took the
     * place of on {@code /ship}, when the keep was {@code /shipkeep <id> replaces <X>}; absent for a plain keep. The
     * catalog lane's kept scan READS it (with the keep time, the only {@code .meta} fields anything reads), so
     * {@code /shiprestore X} finds the copy to forget after a restart. Written only when X passes the id law (the
     * splitter's shape guarantees it).
     */
    static String keptMetaText(Accepted a, String stem, long nowMs, String supersedesOrNull) {
        try {
            JSONObject o = new JSONObject(ledgerLine(a, nowMs));
            o.put("file", stem + VARIANT_EXT);
            o.put("source", "custom-keep");
            o.put("keptFrom", a.canonicalId());
            if (supersedesOrNull != null) {
                o.put(SUPERSEDES_KEY, supersedesOrNull);
            }
            return o.toString(2) + "\n";
        } catch (org.json.JSONException impossible) {
            throw new IllegalStateException("meta row of a kept custom failed", impossible);
        }
    }

    /** A KEEP_FROM_EXPORT's {@code .meta}: no ledger entry exists for an inbox file, so the body's facts +
     *  {@code keptFrom} (the inbox file's stem); the inbox file's own {@code .meta} is never read here.
     *  {@code supersedesOrNull} (a put-back's link, which the sim thread
     *  hands over through the note) is written as {@value #SUPERSEDES_KEY}; null = no link, as before. */
    static String keptExportMetaText(VariantDefinition v, String stem, String name, long nowMs,
            String supersedesOrNull) {
        try {
            JSONObject o = new JSONObject();
            o.put("hullId", v.hullId());
            o.put("displayName", v.displayName());
            o.put(KEPT_AT_KEY, nowMs);
            o.put("file", stem + VARIANT_EXT);
            o.put("source", "custom-keep");
            o.put("keptFrom", name);
            if (supersedesOrNull != null) {
                o.put(SUPERSEDES_KEY, supersedesOrNull);
            }
            return o.toString(2) + "\n";
        } catch (org.json.JSONException impossible) {
            throw new IllegalStateException("meta row of a kept export failed", impossible);
        }
    }

    // ------------------------------------------------------------------ writer thread

    /**
     * Writer body: take → temp+ATOMIC_MOVE the variant (+ meta) → append the ndjson row.
     * Per-item belt: one item's IOException /
     * RuntimeException used to retire the WHOLE sink, silently unpersisting every later accept.
     * Now a failed item counts {@code persist_failed} (LOUD once per failure class) and the writer
     * moves on; only {@value #RETIRE_AFTER_CONSECUTIVE_FAILURES} CONSECUTIVE failures retire it
     * (one LOUD {@code persist_sink_retired} line); a success resets the streak. After retire the
     * writer keeps taking (so close still joins) and counts each abandoned item into
     * {@code persist_retired}.
     */
    private void drain() {
        try {
            while (true) {
                Job job = queue.take();
                if (job == CLOSE_SIGNAL) {
                    break;
                }
                if (writerRetired) {
                    abandonedAfterRetire++;
                    continue;
                }
                if (job instanceof Item item) {
                    writeOne(item);
                } else {
                    runKeptJob(job);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * One kept-store job (writer thread). Each handler owns its catch
     * and reports whether the DISK changed (a write or a move — never a refusal or an "already kept"); only
     * then does the kept listing re-scan here, on this thread (no standing poll).
     */
    private void runKeptJob(Job job) {
        boolean changed = false;
        jobReason = null;
        jobStem = null;
        if (job instanceof KeepJob k) {
            changed = keepWrite(k.keptDir(), k.stem(), k.text(), k.meta(), k.a().canonicalId());
        } else if (job instanceof KeepFromExportJob e) {
            changed = keepFromExport(e);
        } else if (job instanceof ForgetJob f) {
            changed = forget(f);
        }
        Runnable rescan = keptRescanOrNull;
        noteOutcome(job, changed && rescan != null);
        if (changed) {
            rescanKept();
        }
    }

    /**
     * Hand the job's outcome to the catalog lane — the custom id (a keep of
     * this run's custom), the stem it wrote / moved, the inbox name (a keep-export) and a bounded reason token
     * ({@code written} / {@code forgot} / {@code already_kept} / a refusal token / {@code io}). Before the re-scan, so
     * a success rides the snapshot that lists (or delists) its stem. Its own catch: a hook fault never stops the job.
     */
    private void noteOutcome(Job job, boolean rescanFollows) {
        BiConsumer<CatalogPromotionLane.KeptOutcome, Boolean> hook = keptOutcomesOrNull;
        if (hook == null || jobReason == null) {
            return;
        }
        String ccvId = job instanceof KeepJob k ? k.a().canonicalId() : null;
        String token = job instanceof KeepFromExportJob e ? e.name() : null;
        String stem = jobStem != null ? jobStem
                : job instanceof KeepJob k ? k.stem() : job instanceof ForgetJob f ? f.stem() : null;
        try {
            hook.accept(new CatalogPromotionLane.KeptOutcome(jobReason, ccvId, stem, token), rescanFollows);
        } catch (RuntimeException fault) {
            System.out.println("custom-variant kept_outcome_failed " + fault.getClass().getSimpleName());
        }
    }

    /** The kept re-scan hook in its own catch: a fault is ONE row, the sink is not retired. */
    private void rescanKept() {
        Runnable rescan = keptRescanOrNull;
        if (rescan == null) {
            return;
        }
        try {
            rescan.run();
        } catch (RuntimeException fault) {
            System.out.println("custom-variant kept_rescan_failed " + fault.getClass().getSimpleName());
        }
    }

    /**
     * The KEEP write law (writer thread): {@code <keptDir>/<stem>.variant} absent → {@link #writeNoReplace} it +
     * its {@code .meta}; present with EQUAL bytes → "already kept"; present and different (a hand edit, a
     * 6-hex collision, a link) → refused, NEVER overwritten — also when the file lands between the exists check
     * and the move (the no-REPLACE move's {@link FileAlreadyExistsException} = the same {@code name_clash}).
     * {@code keptFromIdOrNull} = this run's accepted id (the ban answer's memory), null for an inbox file (its
     * {@code cc-v} serial may name another body this run). Returns whether the disk changed.
     *
     * <p>The {@code .meta} sidecar KEEPS {@link #writeAtomic}
     * (REPLACE): the only fields anything reads are {@value #SUPERSEDES_KEY} and the keep time ({@value #KEPT_AT_KEY},
     * else {@value #ACCEPTED_AT_KEY}) (the catalog lane's kept scan, bounded, fail-soft to "no link" and "no time")
     * - after a Forget moves it to the inbox, the inbox scan reads its link the same way - and the one state that leaves an orphan {@code <stem>.meta} with no
     * {@code .variant} is a Forget that failed between its two moves — a no-REPLACE sidecar would then answer a
     * FALSE {@code name_clash} for a ship whose {@code .variant} just landed and lists.
     */
    private boolean keepWrite(Path keptDir, String stem, String text, String meta, String keptFromIdOrNull) {
        boolean wrote = false;
        try {
            Files.createDirectories(keptDir);
            Path target = keptDir.resolve(stem + VARIANT_EXT);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (sameBytes(target, text.getBytes(StandardCharsets.UTF_8))) {
                    rememberKeptStem(keptFromIdOrNull, stem);
                    keptVerdict("already kept as " + stem + " - nothing changed"); // a success verdict, like a fresh write's
                    jobOutcome("already_kept", stem);
                    return false;
                }
                keptRefused("keep", stem, "name_clash", nameClashSentence(stem));
                return false;
            }
            try {
                writeNoReplace(target, text);
            } catch (FileAlreadyExistsException raced) {
                keptRefused("keep", stem, "name_clash", nameClashSentence(stem));
                return false;
            }
            wrote = true;
            writeAtomic(keptDir.resolve(stem + META_EXT), meta);
            keptWritten++;
            rememberKeptStem(keptFromIdOrNull, stem);
            keptVerdict("kept as " + stem + " - on /ship for every player");
            jobOutcome("written", stem);
            System.out.println("custom-variant kept stem=" + stem + " path=" + target);
            return true;
        } catch (IOException | RuntimeException failure) {
            keptIoFailed("keep", stem, failure, "could not save " + stem + " ("
                    + failure.getClass().getSimpleName() + ") - check that the server folder is writable");
            return wrote;
        }
    }

    /**
     * KEEP_FROM_EXPORT (writer thread): read {@code <inboxDir>/<name>.variant} under the kept-read law copied
     * from {@code CatalogPromotionLane.readCandidate} — NOFOLLOW, its REAL parent equals the inbox's real
     * path, ≤ {@link CatalogPromotionLane#VARIANT_MAX_BYTES}, nesting ≤ {@link CatalogPromotionLane#MAX_JSON_DEPTH},
     * {@code variantId == name}; the {@code .meta} is never read — then the serve's policy, then the KEEP
     * write. A refusal names a bounded reason token, never the file's text.
     */
    private boolean keepFromExport(KeepFromExportJob e) {
        String name = e.name();
        VariantDefinition v;
        try {
            Path inboxReal;
            Path file;
            try {
                inboxReal = e.inboxDir().toRealPath();
                file = inboxReal.resolve(name + VARIANT_EXT);
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        || !inboxReal.equals(file.toRealPath().getParent())) {
                    return exportUnusable(name, "missing");
                }
            } catch (NoSuchFileException | NotDirectoryException absent) {
                return exportUnusable(name, "missing");
            }
            byte[] body;
            try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                body = in.readNBytes(CatalogPromotionLane.VARIANT_MAX_BYTES + 1);
            }
            if (body.length > CatalogPromotionLane.VARIANT_MAX_BYTES
                    || CatalogPromotionLane.nestingExceeds(body, CatalogPromotionLane.MAX_JSON_DEPTH)) {
                return exportUnusable(name, "over_cap");
            }
            JSONObject json;
            try {
                json = new JSONObject(new String(body, StandardCharsets.UTF_8));
            } catch (Exception | StackOverflowError notJson) {
                return exportUnusable(name, "not_json");
            }
            try {
                v = VariantCatalogStore.parseVariant(json);
            } catch (Exception | StackOverflowError badBody) {
                return exportUnusable(name, "bad_body");
            }
            if (!name.equals(v.variantId())) {
                return exportUnusable(name, "id_mismatch");
            }
        } catch (IOException | RuntimeException failure) {
            keptIoFailed("keep", name, failure, "the exported file cannot be used (unreadable)");
            return false;
        }
        String stem = name;
        try {
            Function<VariantDefinition, String> policy = policyRefusalOrNull;
            String token = policy == null ? null : policy.apply(v);
            if (token != null) {
                String bounded = PeerText.bound(token, 64);
                keptRefused("keep", name, "policy", "refused by this server's custom ship rules (" + bounded + ")");
                return false;
            }
            stem = keptStem(v);
            return keepWrite(e.keptDir(), stem, keptVariantText(v, stem),
                    keptExportMetaText(v, stem, name, System.currentTimeMillis(), e.supersedesOrNull()), null);
        } catch (RuntimeException failure) {
            keptIoFailed("keep", stem, failure, "could not save " + stem + " ("
                    + failure.getClass().getSimpleName() + ") - check that the server folder is writable");
            return false;
        }
    }

    /** The {@code name_clash} sentence's one home (the exists check
     *  and the raced no-replace move answer alike). */
    private static String nameClashSentence(String stem) {
        return "refused: another kept ship is named " + stem + " - move it out first";
    }

    private boolean exportUnusable(String name, String token) {
        keptRefused("keep", name, token, "the exported file cannot be used (" + token + ")");
        return false;
    }

    /**
     * FORGET (writer thread): {@code <keptDir>/<stem>.variant} must be a regular file (NOFOLLOW) whose real
     * parent is the kept dir; the inbox must be a real directory holding no DIFFERENT {@code <stem>.variant}.
     * With no inbox file of that name, a plain {@link Files#move} (no {@code REPLACE_EXISTING}, no
     * {@code ATOMIC_MOVE}: a plain move FAILS on an existing target by contract, while an atomic move may replace
     * it — Windows does — so "never overwrite" holds even against a racing hand-drop; within one volume it is a
     * rename either way) of the {@code .variant}, then its {@code .meta}. No body is deleted.
     *
     * <p>An inbox {@code <stem>.variant} holding EXACTLY
     * the kept bytes is a TWIN — Keep-from-export reads its inbox file and never moves it, and a re-Keep after a
     * Forget writes a fresh copy beside the forgotten one (same stem = same body) — so the move REPLACES it
     * (no byte is lost; the one window left is a hand-drop landing between the twin probe and that move, which
     * the REPLACE would overwrite); a differing file still refuses {@code inbox_name_taken}. The {@code .meta}
     * moves with REPLACE: an inbox {@code <stem>.meta} only a prior Forget can leave, and its one reader (the
     * catalog lane's inbox scan, for the {@code supersedes} link) wants the ship just forgotten's link (the
     * {@link #keepWrite} sidecar reason). Once the {@code .variant} has moved the ship is off
     * {@code /ship}, so the ban answer's memory drops the stem ({@link #dropKeptStem}).
     */
    private boolean forget(ForgetJob f) {
        String stem = f.stem();
        boolean moved = false;
        try {
            Path src = f.keptDir().resolve(stem + VARIANT_EXT);
            Path keptReal;
            try {
                keptReal = f.keptDir().toRealPath();
            } catch (NoSuchFileException | NotDirectoryException absent) {
                keptRefused("forget", stem, "not_kept", "custom-forget " + stem + ": not a kept ship");
                return false;
            }
            if (!Files.isRegularFile(src, LinkOption.NOFOLLOW_LINKS) || !keptReal.equals(src.toRealPath().getParent())) {
                keptRefused("forget", stem, "not_kept", "custom-forget " + stem + ": not a kept ship");
                return false;
            }
            if (!Files.isDirectory(f.inboxDir(), LinkOption.NOFOLLOW_LINKS)) {
                throw new NotDirectoryException(f.inboxDir().toString());
            }
            Path inboxReal = f.inboxDir().toRealPath();
            Path srcMeta = keptReal.resolve(stem + META_EXT);
            boolean withMeta = Files.isRegularFile(srcMeta, LinkOption.NOFOLLOW_LINKS);
            Path keptVariant = keptReal.resolve(stem + VARIANT_EXT);
            Path dstVariant = inboxReal.resolve(stem + VARIANT_EXT);
            Path dstMeta = inboxReal.resolve(stem + META_EXT);
            boolean taken = Files.exists(dstVariant, LinkOption.NOFOLLOW_LINKS);
            boolean twin = taken && inboxTwin(keptVariant, dstVariant);
            if (taken && !twin) {
                keptRefused("forget", stem, "inbox_name_taken", "cannot forget " + stem
                        + ": variants-inbox has a file with that name - move it first");
                return false;
            }
            if (twin) {
                Files.move(keptVariant, dstVariant, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(keptVariant, dstVariant, LinkOption.NOFOLLOW_LINKS);
            }
            moved = true;
            dropKeptStem(stem);
            if (withMeta) {
                Files.move(srcMeta, dstMeta, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.REPLACE_EXISTING);
            }
            keptVerdict("forgot " + stem);
            jobOutcome("forgot", stem);
            System.out.println("custom-variant kept_forgot stem=" + stem + " to=" + dstVariant);
            return true;
        } catch (IOException | RuntimeException failure) {
            keptIoFailed("forget", stem, failure, "could not forget " + stem + " ("
                    + failure.getClass().getSimpleName() + ")");
            return moved;
        }
    }

    /** Equal-bytes probe of an existing kept file (NOFOLLOW; reads at most {@code want.length + 1} bytes). */
    private static boolean sameBytes(Path file, byte[] want) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            return Arrays.equals(in.readNBytes(want.length + 1), want);
        }
    }

    /**
     * Forget's twin probe — the inbox file is a regular file (NOFOLLOW,
     * {@link #sameBytes}) holding EXACTLY the kept file's bytes. The kept read stops at
     * {@link CatalogPromotionLane#VARIANT_MAX_BYTES} + 1, so a kept file longer than that is never a twin (the
     * inbox read takes one byte more and compares unequal), never a false match on a cut prefix.
     */
    private static boolean inboxTwin(Path keptFile, Path inboxFile) throws IOException {
        byte[] kept;
        try (InputStream in = Files.newInputStream(keptFile, LinkOption.NOFOLLOW_LINKS)) {
            kept = in.readNBytes(CatalogPromotionLane.VARIANT_MAX_BYTES + 1);
        }
        return sameBytes(inboxFile, kept);
    }

    /**
     * A forgotten stem leaves the ban answer's memory — every id
     * whose VALUE is {@code stem} ({@link #keptStemOf} then answers null, so {@code custom-ban} adds no "stays on
     * /ship" suffix). Writer thread only ({@link #keptStemOrder}'s owner); ids naming other stems stay.
     */
    private void dropKeptStem(String stem) {
        for (Iterator<String> ids = keptStemOrder.iterator(); ids.hasNext(); ) {
            if (keptStemById.remove(ids.next(), stem)) {
                ids.remove();
            }
        }
    }

    private void rememberKeptStem(String canonicalIdOrNull, String stem) {
        if (canonicalIdOrNull == null) {
            return;
        }
        if (keptStemById.put(canonicalIdOrNull, stem) == null) {
            keptStemOrder.addLast(canonicalIdOrNull);
            while (keptStemOrder.size() > KEPT_STEM_MEMORY_CAP) {
                keptStemById.remove(keptStemOrder.pollFirst());
            }
        }
    }

    private void keptVerdict(String sentence) {
        keptLast = boundLast(sentence);
    }

    /** The running job's outcome token + the stem it names (writer thread). */
    private void jobOutcome(String reason, String stem) {
        jobReason = reason;
        jobStem = stem;
    }

    /** A writer refusal: counted, named in {@link #keptLast}, ONE row with a bounded reason token. */
    private void keptRefused(String job, String stemOrName, String token, String sentence) {
        keptFailed.incrementAndGet();
        keptLast = boundLast(sentence);
        jobReason = token;
        System.out.println("custom-variant kept_" + job + "_refused stem=" + PeerText.quote(stemOrName)
                + " reason=" + token);
    }

    /** A kept-job I/O failure: ONE row, counted, named in {@link #keptLast}; the sink is NOT retired. */
    private void keptIoFailed(String job, String stemOrName, Throwable failure, String sentence) {
        keptFailed.incrementAndGet();
        keptLast = boundLast(sentence);
        jobReason = "io";
        System.out.println("custom-variant kept_" + job + "_failed stem=" + PeerText.quote(stemOrName)
                + " error=" + failure.getClass().getSimpleName());
    }

    private static String boundLast(String sentence) {
        return sentence.length() <= KEPT_LAST_MAX_CHARS ? sentence : sentence.substring(0, KEPT_LAST_MAX_CHARS);
    }

    /** One item under the per-item belt (writer thread). */
    private void writeOne(Item item) {
        boolean export = item.ledgerLine() == null;
        if (!export && !writtenIds.add(item.canonicalId())) {
            return; // catch-up re-feed of an id already on disk
        }
        try {
            Files.createDirectories(item.dir());
            if (export) {
                String landed = landExport(item);
                if (landed != null) {
                    exportsWritten++;
                    System.out.println("custom-variant exported id=" + item.canonicalId()
                            + " path=" + item.dir().resolve(landed + VARIANT_EXT));
                }
            } else {
                writeAtomic(item.dir().resolve(item.canonicalId() + VARIANT_EXT), item.variantText());
                Files.write(item.dir().resolve(LEDGER_FILE),
                        item.ledgerLine().getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                variantsWritten++;
            }
            consecutiveFailures = 0;
        } catch (IOException | RuntimeException failure) {
            if (export) {
                exportsFailed++; // the ops row custom.export_failed (the item never landed)
            } else {
                writtenIds.remove(item.canonicalId()); // the close catch-up may retry this id
            }
            persistFailed++;
            consecutiveFailures++;
            error = failure.getClass().getName() + ": "
                    + (failure.getMessage() == null ? "" : failure.getMessage());
            String failureClass = failure.getClass().getName();
            if (loggedFailureClasses.size() < LOGGED_FAILURE_CLASS_CAP && loggedFailureClasses.add(failureClass)) {
                System.out.println("custom-variant persist_item_failed path=" + dir + " id=" + item.canonicalId()
                        + " export=" + export + " persist_failed=" + persistFailed
                        + " consecutive=" + consecutiveFailures + " error=" + error
                        + " (first of this class — later ones count silently into persist_failed)");
            }
            if (consecutiveFailures >= RETIRE_AFTER_CONSECUTIVE_FAILURES) {
                writerRetired = true;
                retired = true;
                System.out.println("custom-variant persist_sink_retired consecutive=" + consecutiveFailures
                        + " path=" + dir + " variants=" + variantsWritten + " exports=" + exportsWritten
                        + " export_failed=" + exportsFailed + " persist_failed=" + persistFailed
                        + " last=" + error);
            }
        }
    }

    /**
     * An export lands with the NO-replace move ({@link #writeNoReplace}) - the
     * custom serial restarts every serve, so a later serve's {@code cc-v1-<slug>} can meet an earlier serve's export the
     * host has not kept yet, and a replace would hand a Keep of that name a body the host never saw. The same bytes
     * there = already landed (counted written, no row); other bytes = ONE retry under {@code <id>-<serve stamp>}, the
     * body re-identified to its file name (a keep from the inbox requires the two to match), with ONE
     * {@code export_clash} row - the earlier body is never replaced. A second clash refuses loud (null, counted
     * export_failed). The {@code .meta} sidecar follows the landed name. Writer thread.
     */
    private String landExport(Item item) throws IOException {
        String id = item.canonicalId();
        if (landedOrSame(item.dir().resolve(id + VARIANT_EXT), item.variantText())) {
            writeAtomic(item.dir().resolve(id + META_EXT), item.metaText());
            return id;
        }
        String suffixed = id + "-" + openedMillis;
        String body;
        String meta;
        try {
            body = new JSONObject(item.variantText()).put("variantId", suffixed).toString(2) + "\n";
            meta = new JSONObject(item.metaText()).put("file", suffixed + VARIANT_EXT).toString(2) + "\n";
        } catch (org.json.JSONException impossible) {
            throw new IllegalStateException("re-identifying a clashing export failed", impossible);
        }
        if (!landedOrSame(item.dir().resolve(suffixed + VARIANT_EXT), body)) {
            exportsFailed++; // the ops row custom.export_failed (both names hold other bodies; nothing replaced)
            System.out.println("custom-variant export_clash id=" + id + " kept=- reason=suffix_taken"
                    + " (the earlier bodies stay; this export did not land)");
            return null;
        }
        writeAtomic(item.dir().resolve(suffixed + META_EXT), meta);
        System.out.println("custom-variant export_clash id=" + id + " kept=" + suffixed
                + " (an earlier serve's export holds the plain name; it stays)");
        return suffixed;
    }

    /** true = {@code text} now sits at {@code file} - landed by the no-replace
     *  move, or the bytes already there equal it (read bounded, NOFOLLOW); false = another body holds the name. */
    private static boolean landedOrSame(Path file, String text) throws IOException {
        try {
            writeNoReplace(file, text);
            return true;
        } catch (FileAlreadyExistsException taken) {
            byte[] want = text.getBytes(StandardCharsets.UTF_8);
            try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                return java.util.Arrays.equals(in.readNBytes(want.length + 1), want);
            }
        }
    }

    /** Temp sibling + atomic move ({@code OpsSetupDraft.writeAtomic} verbatim). */
    static void writeAtomic(Path file, String text) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * The KEEP's never-overwrite landing — a temp sibling, then a
     * PLAIN {@link Files#move} with NO copy option, which FAILS on an existing target by contract
     * ({@link FileAlreadyExistsException}; the temp is deleted and the failure rethrown), so a file dropped after
     * the caller's exists check is never replaced. Never {@code ATOMIC_MOVE}: on Windows the JDK implements it
     * with {@code MOVEFILE_REPLACE_EXISTING}, so an "atomic no-replace" move silently replaces (a same-volume
     * rename is atomic at the file system anyway). Public for the contract row {@code keep-no-replace}.
     */
    public static void writeNoReplace(Path file, String text) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, file);
        } catch (IOException refused) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException tempStays) {
                refused.addSuppressed(tempStays);
            }
            throw refused;
        }
    }

    private void retire() {
        retired = true;
        queue.offer(CLOSE_SIGNAL);
    }

    /** Serve end (blocking briefly here is fine): drain, join bounded, receipt row. */
    @Override
    public void close() {
        retired = true;
        try {
            if (!queue.offer(CLOSE_SIGNAL, CLOSE_JOIN_MILLIS, TimeUnit.MILLISECONDS)) {
                writerThread.interrupt();
            }
            writerThread.join(CLOSE_JOIN_MILLIS);
            if (writerThread.isAlive()) {
                writerThread.interrupt();
                error = error.isEmpty() ? "close: writer thread did not drain in "
                        + CLOSE_JOIN_MILLIS + " ms (interrupted)" : error;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        System.out.println("custom-variant persist_sink_closed path=" + dir + " variants=" + variantsWritten
                + " exports=" + exportsWritten + " queued=" + queued + " dropped=" + dropped
                + " persist_failed=" + persistFailed + " persist_retired=" + persistRetired()
                + " error=" + (error.isEmpty() ? "none" : error));
    }
}
