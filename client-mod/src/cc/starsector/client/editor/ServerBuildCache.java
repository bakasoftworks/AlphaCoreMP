package cc.starsector.client.editor;

import cc.starsector.client.BootVariantRegistry;
import cc.starsector.client.PilotWatchLog;
import cc.starsector.client.net.ClientVariantDefinition;
import cc.starsector.client.net.ClientVariantJson;
import cc.starsector.client.net.LocalVariantIdLaw;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The SERVER-BUILD definition cache: every ship-variant definition the VDEF
 * fold lane PUT into the engine registry (never a boot id, never a refused one) is also kept as
 * {@code saves/common/cc_variants/_server/<id>.variant} so the joiner editor can list and load
 * a "Server Builds" row AFTER combat teardown evicted the fold (16 of
 * 23 rows greyed "not in this game's variant list" — the kit's W7c B3 strip ships only the 7
 * descriptor customs, the rest live in the registry only between admission and teardown;
 * a client-side derivation, no wire change, Law A/B untouched).
 *
 * <p><b>Thread contract (frame-loop residency):</b> the fold runs on the render
 * thread inside combat; {@link #remember} only ENQUEUES (bounded, drop-on-full with a counter)
 * and ONE daemon writer thread does the {@link SettingsAPI#writeTextFileToCommon} I/O off-frame.
 * {@link #cached} is the editor's read (the editor game-thread hop, title screen).
 *
 * <p><b>Security posture:</b> wire-
 * DERIVED data written and read back through SettingsAPI only (no {@code java.io.File}, B-deny
 * safe); the id passes the editor id law BEFORE it composes a name; the path is composed only as
 * {@code cc_variants/_server/<id>.variant}; text capped at 64 KiB both ways; the reader is the
 * same {@link ClientVariantJson} law every saved build passes.
 *
 * <p><b>Disk bound: the id ledger.</b> SettingsAPI can
 * neither list nor date a common file, so the writer keeps its own list: {@link #LEDGER} names
 * every id it wrote, one per line, oldest write first (a re-write, or an identical re-delivery whose
 * disk write is skipped, moves its id to the newest end). The first item the writer drains in a
 * process loads the ledger, skips every line that fails the local id law (counted, never composed into
 * a file name) and keeps the newest {@link #LEDGER_KEEP} ids, deleting the older ones' files; from
 * then on every write that pushes the ledger past that bound deletes the oldest id's file.
 * {@link #LEDGER_KEEP} is one served session's distinct-id ceiling, and every delivery of the session
 * in progress is newer than any earlier session's, so no id of the live session is ever pruned. Files
 * written before the ledger existed are never listed, so never pruned: a finite residue of earlier kit
 * versions. The same holds for the appends of a process's last unpersisted interval (at most
 * {@link #LEDGER_PERSIST_MIN_MILLIS}) when it exits: the ledger persists on that cadence, never per
 * write. Pruning is harmless: the cache serves only the editor after teardown, and the next join
 * re-registers what its server still serves.
 *
 * <p><b>Write share.</b> The engine's one SettingsAPI keeps a
 * per-process cumulative common-write budget of 524,288,000 chars that is never reset, and a write it
 * refuses still spends it; every mod's
 * common writes share it, and a host varying a build's text could otherwise exhaust it in ~8,000
 * writes. This cache spends at most {@link #WRITE_BUDGET_CHARS} (10 %) of it, counting every text and
 * every ledger write on its own counter. Every write leaves room for one full ledger persist
 * ({@link #LEDGER_RESERVE_CHARS}) until the share runs out, so the ledger still lists what was written;
 * from then on caching stops for the rest of the process (each skipped build counted
 * {@code budgetSkipped=}, one row). Lawful cost MODEL: ~960 definitions × ~3 K chars = ~2.9 M per new server catalog, so ~18
 * catalogs a process; the model flips if a measured catalog is larger. An identical re-delivery (same
 * id, text length and text hash) skips its disk write (counted {@code deduped=}).
 */
public final class ServerBuildCache {

    /** SettingsAPI name prefix (the game appends {@code .data}). */
    static final String DIR = "cc_variants/_server/";
    static final int MAX_TEXT = 64 * 1024;
    /** Writer queue depth — a VDEF burst is ~25 definitions today; 256 leaves ×10 headroom. */
    static final int QUEUE_CAP = 256;

    private static final BlockingQueue<String[]> queue = new ArrayBlockingQueue<String[]>(QUEUE_CAP);
    private static final AtomicInteger dropped = new AtomicInteger();
    private static final AtomicInteger written = new AtomicInteger();
    private static final AtomicInteger failed = new AtomicInteger();
    private static volatile Thread writer;

    /**
     * The ledger's SettingsAPI name (on disk
     * {@code index.data} beside the cached builds; no id collides, every build name ends in
     * {@code .variant}) and the newest-id count it keeps.
     */
    static final String LEDGER = DIR + "index";
    static final int LEDGER_KEEP = BootVariantRegistry.MAX_FOLDED_PER_SESSION;

    /**
     * The ledger persist cadence. The ledger is rewritten
     * whole (SettingsAPI has no append) only when it changed: the first persist of a process at once,
     * then at most once per this interval, so a burst never rewrites it per write; the idle writer wakes
     * once per interval to flush a quiet tail. Cost: a ledger is at most {@link #LEDGER_RESERVE_CHARS}
     * = 249,600 chars, so over a 4 h process (14,400 s) a persist every N s costs 249,600 × 14,400 / N
     * chars; holding that within 10 % of the engine's budget needs N ≥ 68.6 s (60 s = 11.4 % fails,
     * 120 s = 5.71 %).
     */
    static final long LEDGER_PERSIST_MIN_MILLIS = 120_000L;
    /** One full ledger persist: {@link #LEDGER_KEEP} ids of at most 64 chars plus a newline each. */
    static final long LEDGER_RESERVE_CHARS = (long) LEDGER_KEEP * (LocalVariantIdLaw.MAX_CHARS + 1);
    /** The hard per-process share: 10 % of the engine's 524,288,000-char common-write budget. */
    static final long WRITE_BUDGET_CHARS = 52_428_800L;
    /** The ledger key of an id loaded from disk: its text is unknown, so its next write proceeds. */
    private static final long UNKNOWN = 0L;

    private static final AtomicInteger pruned = new AtomicInteger();
    private static final AtomicInteger ledgerLawless = new AtomicInteger();
    private static final AtomicInteger ledgerFailed = new AtomicInteger();
    private static final AtomicInteger deduped = new AtomicInteger();
    private static final AtomicInteger budgetSkipped = new AtomicInteger();
    /** Chars this process handed to SettingsAPI writes (texts + ledger, a disk-refused one too); writer thread spends. */
    private static final AtomicLong chars = new AtomicLong();
    /**
     * Writer thread only: written ids, oldest first, each mapped to its last written text's
     * {@link #textKey} ({@link #UNKNOWN} when loaded from disk); null until the first drained item loads it.
     */
    private static LinkedHashMap<String, Long> ledger;
    private static int ledgerUnpersisted;
    private static volatile int ledgerSize;
    /** Writer thread only: the share latch, and the last ledger persist attempt on {@link #clock}. */
    private static boolean budgetSpent;
    private static boolean persistedOnce;
    private static long lastPersistMillis;
    /** Monotonic ms clock for the persist cadence — package-private and settable: the rows inject it. */
    static LongSupplier clock = () -> System.nanoTime() / 1_000_000L;

    private ServerBuildCache() {
    }

    /**
     * Queue one folded definition for the off-frame writer. Never throws, never blocks: a lawless
     * id or oversize text is refused silently (counted), a full queue drops (counted, one log line).
     */
    public static void remember(String id, String text) {
        if (id == null || text == null || !EditorText.isId(id) || text.length() > MAX_TEXT) {
            dropped.incrementAndGet();
            return;
        }
        if (!queue.offer(new String[] { id, text })) {
            if (dropped.incrementAndGet() == 1) {
                PilotWatchLog.info("server-build cache: writer queue full at cap=" + QUEUE_CAP
                        + " — dropping (counted)");
            }
            return;
        }
        ensureWriter();
    }

    /** The cached definition for a published id, or null when absent / unreadable / lawless. */
    public static ClientVariantDefinition cached(String id) {
        if (id == null || !EditorText.isId(id)) {
            return null;
        }
        try {
            SettingsAPI settings = Global.getSettings();
            String name = DIR + id + ".variant";
            if (!settings.fileExistsInCommon(name)) {
                return null;
            }
            String text = settings.readTextFileFromCommon(name);
            if (text == null || text.isEmpty() || text.length() > MAX_TEXT) {
                return null;
            }
            return ClientVariantJson.parse(text);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** Census tokens for the teardown line (writes / failures / drops / dedupes / share / ledger counts this process). */
    public static String census() {
        return "written=" + written.get() + " failed=" + failed.get() + " dropped=" + dropped.get()
                + " deduped=" + deduped.get() + " budgetSkipped=" + budgetSkipped.get() + " chars=" + chars.get()
                + " ledger=" + ledgerSize + " pruned=" + pruned.get() + " ledgerLawless=" + ledgerLawless.get()
                + " ledgerFailed=" + ledgerFailed.get();
    }

    private static void ensureWriter() {
        if (writer != null) {
            return;
        }
        synchronized (ServerBuildCache.class) {
            if (writer != null) {
                return;
            }
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    drainForever();
                }
            }, "cc-server-build-cache");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            writer = t;
            t.start();
        }
    }

    /**
     * The writer loop; its poll times out once per
     * {@link #LEDGER_PERSIST_MIN_MILLIS} so a quiet tail's changed ledger still persists.
     */
    private static void drainForever() {
        while (true) {
            String[] item;
            try {
                item = queue.poll(LEDGER_PERSIST_MIN_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            if (item == null) {
                idleFlush(Global.getSettings());
            } else {
                writeOne(Global.getSettings(), item[0], item[1]);
            }
        }
    }

    /**
     * And: one drained item. The first call of the process loads and prunes the ledger. Once the share is
     * spent every item is skipped (counted). An id whose ledger key matches this text's skips the disk
     * write (counted) and moves to the newest end; any other text spends its share and is written, and a
     * successful write records its key, moves the id to the newest end and deletes the oldest id's file
     * once the ledger passes {@link #LEDGER_KEEP}; a failed or refused write appends nothing. Then a
     * changed ledger persists when due. The dedupe lives HERE, after the registry already took the
     * re-delivery: a rejoin's re-delivered builds must register again, so only the disk write is skipped.
     * A different text with the same length and 32-bit hash is skipped too and keeps the older text
     * cached (the same host's data either way). Writer thread only; never throws.
     */
    static void writeOne(SettingsAPI settings, String id, String text) {
        if (ledger == null) {
            ledger = loadAndPrune(settings);
        }
        long key = textKey(text);
        Long had = ledger.get(id);
        if (budgetSpent) {
            refuse();
        } else if (had != null && had.longValue() != UNKNOWN && had.longValue() == key) {
            deduped.incrementAndGet();
            moveToNewest(id, key);
        } else if (!admit(text.length(), LEDGER_RESERVE_CHARS)) {
            budgetSpent = true;
        } else if (writeText(settings, id, text)) {
            moveToNewest(id, key);
            trimToKeep(settings, ledger);
        }
        ledgerSize = ledger.size();
        persistIfDue(settings);
    }

    /** The idle writer's tail — a changed ledger persists once due. */
    static void idleFlush(SettingsAPI settings) {
        if (ledger != null) {
            persistIfDue(settings);
        }
    }

    private static boolean writeText(SettingsAPI settings, String id, String text) {
        try {
            settings.writeTextFileToCommon(DIR + id + ".variant", text);
            written.incrementAndGet();
            return true;
        } catch (IOException | RuntimeException unwritable) {
            if (failed.incrementAndGet() == 1) {
                PilotWatchLog.info("server-build cache: write failed for '" + id + "': "
                        + unwritable.getClass().getSimpleName() + " (counted, the writer stays up)");
            }
            return false;
        }
    }

    /** Length in the high half, the text's hash in the low half; never {@link #UNKNOWN} for a non-empty text. */
    private static long textKey(String text) {
        return ((long) text.length() << 32) | (text.hashCode() & 0xFFFF_FFFFL);
    }

    private static void moveToNewest(String id, long key) {
        ledger.remove(id);
        ledger.put(id, Long.valueOf(key));
        ledgerUnpersisted++;
    }

    /**
     * Spend {@code n} chars of the share, leaving at least
     * {@code keepBack} unspent; a refusal is counted (one row the first time) and its write is never
     * attempted, so it spends neither this share nor the engine's.
     */
    private static boolean admit(long n, long keepBack) {
        long spent = chars.get();
        if (spent + n + keepBack > WRITE_BUDGET_CHARS) {
            refuse();
            return false;
        }
        chars.set(spent + n);
        return true;
    }

    private static void refuse() {
        if (budgetSkipped.incrementAndGet() == 1) {
            PilotWatchLog.info("server-build cache: common-write share spent (chars=" + chars.get() + " of "
                    + WRITE_BUDGET_CHARS + ") - no further build is cached this process (counted)");
        }
    }

    /**
     * Persist a changed ledger — the first time in a process
     * at once, then only once {@link #LEDGER_PERSIST_MIN_MILLIS} passed since the last attempt.
     */
    private static void persistIfDue(SettingsAPI settings) {
        if (ledgerUnpersisted == 0) {
            return;
        }
        long now = clock.getAsLong();
        if (persistedOnce && now - lastPersistMillis < LEDGER_PERSIST_MIN_MILLIS) {
            return;
        }
        persistLedger(settings, now);
    }

    /**
     * Read the ledger once a process, skip (and count)
     * every line failing the local id law BEFORE it could compose a name, keep the newest
     * {@link #LEDGER_KEEP} ids and delete the older ones' files; a changed ledger is rewritten at the
     * next persist. An unreadable ledger restarts empty (counted, one row): its ids become unlisted
     * residue, never a wrong delete. A loaded id's text is unknown ({@link #UNKNOWN}), so its next write
     * proceeds.
     */
    private static LinkedHashMap<String, Long> loadAndPrune(SettingsAPI settings) {
        LinkedHashMap<String, Long> ids = new LinkedHashMap<String, Long>();
        int lawless = 0;
        try {
            if (settings.fileExistsInCommon(LEDGER)) {
                String text = settings.readTextFileFromCommon(LEDGER);
                for (String line : (text == null ? "" : text).split("\n")) {
                    String id = line.trim();
                    if (id.isEmpty()) {
                        continue;
                    }
                    if (!LocalVariantIdLaw.isLawful(id)) {
                        lawless++;
                        continue;
                    }
                    ids.remove(id);
                    ids.put(id, Long.valueOf(UNKNOWN));
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            ledgerFault("read", unreadable);
        }
        ledgerLawless.addAndGet(lawless);
        int listed = ids.size();
        int deleted = trimToKeep(settings, ids);
        if (lawless > 0 || deleted > 0) {
            ledgerUnpersisted++;
        }
        PilotWatchLog.info("server-build cache: ledger loaded ids=" + listed + " kept=" + ids.size()
                + " pruned=" + deleted + " lawless=" + lawless + " keep=" + LEDGER_KEEP);
        return ids;
    }

    /**
     * Drop the oldest ids until at most
     * {@link #LEDGER_KEEP} remain, deleting each dropped id's file when present; returns how many
     * ids were dropped (each with its text key). Ids in the map already passed the id law (at queue
     * time or at load).
     */
    private static int trimToKeep(SettingsAPI settings, LinkedHashMap<String, Long> ids) {
        int dropped = 0;
        Iterator<String> oldest = ids.keySet().iterator();
        while (ids.size() > LEDGER_KEEP && oldest.hasNext()) {
            String name = DIR + oldest.next() + ".variant";
            oldest.remove();
            dropped++;
            try {
                if (settings.fileExistsInCommon(name)) {
                    settings.deleteTextFileFromCommon(name);
                }
            } catch (RuntimeException undeletable) {
                ledgerFault("delete", undeletable);
            }
        }
        pruned.addAndGet(dropped);
        return dropped;
    }

    /**
     * And: rewrite the ledger whole
     * (SettingsAPI has no append; ids only on disk). Every attempt stamps the cadence, so a failing disk
     * or a spent share retries at most once per interval. Before the latch every write leaves one full
     * ledger's room, so the persist that would eat into it latches the share and takes that room itself,
     * as does the one persist after a refused text; once latched the ledger never changes again.
     */
    private static void persistLedger(SettingsAPI settings, long now) {
        persistedOnce = true;
        lastPersistMillis = now;
        StringBuilder text = new StringBuilder(ledger.size() * 24);
        for (String id : ledger.keySet()) {
            text.append(id).append('\n');
        }
        if (!budgetSpent && chars.get() + text.length() + LEDGER_RESERVE_CHARS > WRITE_BUDGET_CHARS) {
            budgetSpent = true;
        }
        if (!admit(text.length(), 0L)) {
            return;
        }
        try {
            settings.writeTextFileToCommon(LEDGER, text.toString());
            ledgerUnpersisted = 0;
        } catch (IOException | RuntimeException unwritable) {
            ledgerFault("write", unwritable);
        }
    }

    private static void ledgerFault(String op, Exception fault) {
        if (ledgerFailed.incrementAndGet() == 1) {
            PilotWatchLog.info("server-build cache: ledger " + op + " failed: " + fault.getClass().getSimpleName()
                    + " (counted, the writer stays up)");
        }
    }
}
