package cc.starsector.client.editor;

import cc.starsector.client.PilotWatchLog;
import cc.starsector.client.net.ClientVariantDefinition;
import cc.starsector.client.net.ClientVariantJson;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

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

    /** Census tokens for the teardown line (writes / failures / drops so far this process). */
    public static String census() {
        return "written=" + written.get() + " failed=" + failed.get() + " dropped=" + dropped.get();
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

    private static void drainForever() {
        while (true) {
            String[] item;
            try {
                item = queue.take();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                Global.getSettings().writeTextFileToCommon(DIR + item[0] + ".variant", item[1]);
                written.incrementAndGet();
            } catch (IOException | RuntimeException unwritable) {
                if (failed.incrementAndGet() == 1) {
                    PilotWatchLog.info("server-build cache: write failed for '" + item[0] + "': "
                            + unwritable.getClass().getSimpleName() + " (counted, lane stays up)");
                }
            }
        }
    }
}
