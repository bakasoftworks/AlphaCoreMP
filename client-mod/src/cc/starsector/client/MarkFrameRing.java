package cc.starsector.client;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.ViewportAPI;

/**
 * F9 MARK-FRAME RING: a mark shows
 * the scenario from both windows as rendered images, not just the wireframe.
 * Every armed window keeps the last {@code cc.markframes.preSeconds} of its
 * OWN rendered frames as JPEG bytes IN MEMORY; a mark (local F9, or another window's F9 seen through
 * the shared mark file) flushes that ring to disk plus {@code cc.markframes.postSeconds} more, so one
 * F9 yields the rendered image from the LOCAL seat, the KIT seat and the OBSERVER, paired by mark
 * number and by server sim tick ({@code lastSnapshotSimTick} = the pairing key with the server logs).
 * Nothing touches disk unless a mark happens.
 *
 * <p>Capture = the {@link ObserverFrameDump} path (a {@link BaseCombatLayeredRenderingPlugin} at
 * {@code JUST_BELOW_WIDGETS}, ONE {@code glReadPixels} per {@code cc.markframes.everyTicks} sim
 * ticks — default 4 = 15 fps at 60 tps — into a 2-slot ring of pooled direct buffers; both slots
 * busy = the frame is DROPPED, counted, never blocked on). The daemon {@value #THREAD_NAME} flips
 * rows, JPEG-encodes into a reusable in-memory sink and appends a {@link Frame} to the ring, bounded
 * by count, bytes ({@value #MAX_RING_BYTES}) AND age (pre window + slack), drop-oldest. The
 * render-thread cost of the readPixels call is measured (running avg + max µs) and printed on every
 * flush row and at teardown — residency law: the live client's frame loop only
 * carries what is measured.
 *
 * <p>Flush = on the encoder thread only: every ring frame with
 * {@code markTick - preTicks <= tick <= markTick + postTicks} ({@link MarkFrameGuard#inPreWindow};
 * the upper bound rejects a previous round's frames after a
 * tick-space restart) is written FLAT into the launcher-created arm dir as {@code m<markNo>-t<9-digit tick>-<wallMs>.jpg}
 * ({@code CREATE_NEW}: a collision = one logged row, never an overwrite), then every new encoded frame
 * until {@code markWall + postSeconds}; one {@code marks.ndjson} row per written frame and one
 * {@code mark} row per flush (with the readPixels stats). The mod jar may not mkdir
 * ({@code java.nio.file.Files} is classloader-B DENIED), hence flat names, launcher-created dir.
 *
 * <p>Cross-window trigger: all armed windows run on ONE box under ONE user, so the marking
 * window ALSO rewrites {@code <user.home>/}{@value #MARK_FILE_NAME} ({@code n= tick= wall= role=};
 * the {@link WindowPosMemory} write idiom) and every armed window's {@value #POLL_THREAD_NAME}
 * daemon polls it every {@value #POLL_MILLIS} ms: a NEW {@code (n, wall)} pair — not its own write,
 * not older than this window's arm time (a stale file from a previous flight never fires) — flushes
 * THIS window's ring around {@code tick} exactly like a local mark. Security law
 * (the mark file is a parsed input): parse
 * FAIL-CLOSED exactly like {@link WindowPosMemory#parse} (known keys, ints/longs only, size cap
 * {@value #MAX_MARK_FILE_BYTES}), raw bytes are never logged, the role is a log token only after
 * {@link WindowPosMemory#sanitizeName}.
 *
 * <p>Classloader-B law (gate {@code check-b-deny-refs.ps1}): every constant-pool ref here is B-LEGAL
 * — {@code FileChannel}/{@code Paths}/{@code Path}/{@code StandardOpenOption}/{@code ByteBuffer}/
 * {@code OutputStream}/{@code IOException}/{@code ImageIO}/{@code BufferedImage}/{@code GL11}; no
 * {@code java.io.File*}, no {@code java.nio.file.Files}, no {@code java.io.ByteArrayOutputStream}
 * (NOT on the allowlist — {@link ByteSink} is the in-memory sink instead), and a CREATE_NEW collision
 * is recognised by exception simple name, never by a {@code FileAlreadyExistsException} catch clause
 * (that name matches the {@code java.nio.file.File} deny prefix). Class-load discipline: the ring,
 * both threads, every nested class, ImageIO's JPEG writer and the FileChannel write seam first-touch
 * in {@link #startAtApplicationLoad} (a real in-memory warm-up encode + {@code warmup.jpg}), never
 * under the combat script SecurityManager. Fail-soft: any fault disarms the lane with one row; the
 * match, the render thread and the plugin are never affected.
 *
 * <p>Round law: the npeer {@code /wave wrap}
 * restarts the server tick space at 0, so the capture guard and the ring are ROUND-local —
 * {@link #onRoundReset} (called from the plugin's match-reset) re-arms {@code nextTick}, drops the ring
 * and bumps {@code ROUND_EPOCH}; a raw slot captured in the old round and still queued is skipped by the
 * encoder (counted as {@code stale_epoch=} in the readPixels stats), never inserted after the clear.
 */
final class MarkFrameRing {
    static final String THREAD_NAME = "cc-mark-frames";
    static final String POLL_THREAD_NAME = "cc-mark-frames-poll";
    static final String SIDECAR_NAME = "marks.ndjson";
    static final String MARK_FILE_NAME = ".cc-starsector-mark.properties";
    static final String DIR_PROP = "cc.markframes.dir";
    static final String EVERY_PROP = "cc.markframes.everyTicks";
    static final String PRE_PROP = "cc.markframes.preSeconds";
    static final String POST_PROP = "cc.markframes.postSeconds";
    static final int DEFAULT_EVERY_TICKS = 4;
    static final int DEFAULT_PRE_SECONDS = 10;
    static final int DEFAULT_POST_SECONDS = 5;
    /** Ring byte cap (≈40 KB × 15 fps × 10 s ≈ 6 MB expected; 24 MB = the hard ceiling). */
    static final int MAX_RING_BYTES = 24 << 20;
    static final int MAX_RING_FRAMES = 900;
    /** Ring age slack past the pre window — a cross-window mark arrives up to ~300 ms late. */
    static final long RING_SLACK_MS = 1000L;
    static final long POLL_MILLIS = 250L;
    static final int MAX_MARK_FILE_BYTES = 4096;
    static final int MAX_ACTIVE_FLUSHES = 4;
    private static final int RAW_SLOTS = 2;
    private static final int MAX_ERRORS = 5;
    private static final int PARSE_FAILS_BEFORE_ROW = 4;

    private static volatile boolean armed;
    private static volatile boolean disarmed;
    private static volatile String dir;
    private static volatile Path dirPath;
    private static volatile Path markFile;
    private static volatile String role = "unknown";
    private static volatile int everyTicks = DEFAULT_EVERY_TICKS;
    private static volatile int preTicks;
    /** The pre-window's upper slack (post seconds, floor 1 s). */
    private static volatile int postTicks;
    private static volatile long preMs;
    private static volatile long postMs;
    private static volatile long armedAtMs;

    private static final Slot[] RAW = new Slot[RAW_SLOTS];
    private static final ArrayBlockingQueue<Slot> QUEUE = new ArrayBlockingQueue<Slot>(RAW_SLOTS);
    /** Encoded frames, oldest first; guarded by {@code synchronized (RING)}. */
    private static final ArrayDeque<Frame> RING = new ArrayDeque<Frame>();
    private static long ringBytes; // guarded by RING
    /** Marks awaiting the encoder thread (local F9 + the poller's cross-window hits). */
    private static final ConcurrentLinkedQueue<Mark> MARKS = new ConcurrentLinkedQueue<Mark>();
    /** Open post windows; encoder thread only. */
    private static final List<Flush> ACTIVE = new ArrayList<Flush>();
    private static final AtomicInteger CAPTURED = new AtomicInteger();
    private static final AtomicInteger DROPPED = new AtomicInteger();
    private static final AtomicInteger ERRORS = new AtomicInteger();
    private static final AtomicInteger WRITTEN = new AtomicInteger();
    private static final AtomicInteger COLLISIONS = new AtomicInteger();
    /** Queued slots skipped because a round reset overtook them. */
    private static final AtomicInteger STALE_EPOCH = new AtomicInteger();
    /**
     * Bumped by {@link #onRoundReset} / {@link #onCombatEnd}
     * (plugin thread, the only writer; the render thread runs on the same thread and stamps it into
     * each {@link Slot}); the encoder drops a slot whose stamp is stale.
     */
    private static volatile int ROUND_EPOCH;
    /** Render-thread readPixels cost; written by the render thread only, read anywhere. */
    private static volatile long readN;
    private static volatile long readSumNs;
    private static volatile long readMaxNs;
    /** Render thread (volatile only so teardown's reset is seen). */
    private static volatile long nextTick = Long.MIN_VALUE;
    /** The (n, wall) pair THIS window last wrote to the mark file — the poller ignores it. */
    private static volatile int ownMarkNo = -1;
    private static volatile long ownMarkWall = Long.MIN_VALUE;

    /** One pooled raw capture: the direct RGBA buffer + stamp. */
    static final class Slot {
        final AtomicBoolean busy = new AtomicBoolean();
        ByteBuffer rgba;
        int w;
        int h;
        long tick;
        long wallMs;
        /** {@code ROUND_EPOCH} at capture. */
        int epoch;
    }

    /** One encoded ring frame. */
    static final class Frame {
        final long tick;
        final long wallMs;
        final int w;
        final int h;
        final byte[] jpeg;

        Frame(long tick, long wallMs, int w, int h, byte[] jpeg) {
            this.tick = tick;
            this.wallMs = wallMs;
            this.w = w;
            this.h = h;
            this.jpeg = jpeg;
        }
    }

    /** A mark request: local F9 ({@code local}) or another window's, read from the mark file. */
    static final class Mark {
        final int no;
        final long tick;
        final long wallMs;
        final String origin;
        final boolean local;

        Mark(int no, long tick, long wallMs, String origin, boolean local) {
            this.no = no;
            this.tick = tick;
            this.wallMs = wallMs;
            this.origin = origin;
            this.local = local;
        }
    }

    /** An open flush: the pre snapshot is written at open, then new frames until {@code deadlineMs}. */
    static final class Flush {
        final Mark mark;
        final long deadlineMs;
        int written;
        int collisions;
        long lastTick = Long.MIN_VALUE;
        long lastWall = Long.MIN_VALUE;

        Flush(Mark mark, long deadlineMs) {
            this.mark = mark;
            this.deadlineMs = deadlineMs;
        }
    }

    /** Growable in-memory {@link OutputStream} for ImageIO — {@code ByteArrayOutputStream} is B-denied. */
    static final class ByteSink extends OutputStream {
        private byte[] buf = new byte[256 * 1024];
        private int size;

        @Override
        public void write(int b) {
            ensure(1);
            buf[size++] = (byte) b;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            ensure(len);
            System.arraycopy(b, off, buf, size, len);
            size += len;
        }

        void reset() {
            size = 0;
        }

        int size() {
            return size;
        }

        byte[] toArray() {
            return Arrays.copyOf(buf, size);
        }

        private void ensure(int n) {
            if (size + n > buf.length) {
                buf = Arrays.copyOf(buf, Math.max(buf.length * 2, size + n));
            }
        }
    }

    private MarkFrameRing() {
    }

    // ----------------------------------------------------------------------------------------
    // Arm (application load, main thread)
    // ----------------------------------------------------------------------------------------

    /** Application-load arm + pre-warm; a no-op (one OFF row) unless {@value #DIR_PROP} is set. */
    static void startAtApplicationLoad() {
        if (armed || disarmed) {
            return;
        }
        String d = null;
        try {
            d = System.getProperty(DIR_PROP);
        } catch (Throwable ignore) {
            // unreadable property source = unset
        }
        d = d == null ? null : d.trim();
        if (d == null || d.isEmpty()) {
            PilotWatchLog.info("mark-frames: OFF (no " + DIR_PROP + "; the launcher's -MarkFrameDir arms it)");
            return;
        }
        try {
            armedAtMs = System.currentTimeMillis();
            dir = d;
            dirPath = Paths.get(d);
            everyTicks = Math.max(1, KnobParse.intOr(EVERY_PROP, DEFAULT_EVERY_TICKS, "markframes-every"));
            int pre = Math.max(1, Math.min(60, KnobParse.intOr(PRE_PROP, DEFAULT_PRE_SECONDS, "markframes-pre")));
            int post = Math.max(0, Math.min(60, KnobParse.intOr(POST_PROP, DEFAULT_POST_SECONDS, "markframes-post")));
            preMs = pre * 1000L;
            postMs = post * 1000L;
            preTicks = pre * tps();
            // Computed like preTicks; floor 1 s so post=0 still
            // keeps the frames encoded between the F9 press and the encoder's drainMarks.
            postTicks = Math.max(1, post) * tps();
            role = WindowPosMemory.resolveRole();
            for (int i = 0; i < RAW_SLOTS; i++) {
                RAW[i] = new Slot();
            }
            ImageIO.setUseCache(false); // no temp-file cache (java.io.File internals, and the SSD)
            // Warm-up = the whole seam end to end, in memory then on disk: JPEG writer, sink, ring
            // push/evict, mark queue, every nested class, the CREATE/TRUNCATE and APPEND channels.
            BufferedImage warm = new BufferedImage(2, 2, BufferedImage.TYPE_3BYTE_BGR);
            ((DataBufferByte) warm.getRaster().getDataBuffer()).getData()[0] = (byte) 0x7f;
            ByteSink sink = new ByteSink();
            if (!ImageIO.write(warm, "jpg", sink)) {
                throw new IOException("no ImageIO jpg writer");
            }
            byte[] warmBytes = sink.toArray();
            sink.reset();
            writeBytes(dirPath.resolve("warmup.jpg"), warmBytes, true);
            Frame wf = new Frame(0L, 0L, 2, 2, warmBytes);
            synchronized (RING) {
                RING.addLast(wf);
                RING.clear();
                ringBytes = 0L;
            }
            MARKS.offer(new Mark(0, 0L, 0L, role, true));
            MARKS.clear();
            new Flush(new Mark(0, 0L, 0L, role, false), 0L);
            new Capture(null); // nested class first-touched here, never under the combat SecurityManager
            appendSidecar("{\"schema\":\"cc-mark-frames-v1\",\"role\":\"" + role + "\",\"everyTicks\":" + everyTicks
                    + ",\"preSeconds\":" + pre + ",\"postSeconds\":" + post + ",\"preTicks\":" + preTicks
                    + ",\"openedMs\":" + armedAtMs + "}");
            String poll = armPoller();
            Thread t = new Thread(MarkFrameRing::encodeLoop, THREAD_NAME);
            t.setDaemon(true);
            t.start();
            armed = true;
            PilotWatchLog.info("mark-frames ARMED: dir=" + FallbackAnnounce.sanitize(dir, 120) + " role=" + role
                    + " every=" + everyTicks + " ticks pre=" + pre + "s (" + preTicks + " ticks) post=" + post
                    + "s ringCap=" + (MAX_RING_BYTES >> 20) + "MB/" + MAX_RING_FRAMES + " encoder=" + THREAD_NAME
                    + " poller=" + poll);
        } catch (Throwable t) {
            disarmed = true;
            PilotWatchLog.error("mark-frames: arm FAILED (disarmed; match unaffected)", t);
        }
    }

    /** The cross-window poller: needs {@code user.home}; the mark file is created empty-ish if absent
     *  (warms the WRITE seam and spares the poller a NoSuchFile exception every 250 ms). */
    private static String armPoller() {
        String home = null;
        try {
            home = System.getProperty("user.home");
        } catch (Throwable ignore) {
            // unreadable = none
        }
        if (home == null || home.isBlank()) {
            return "off(no-user-home)";
        }
        Path mf = Paths.get(home, MARK_FILE_NAME);
        byte[] existing = readSmall(mf);
        if (existing == null) {
            try {
                writeBytes(mf, markFileText(0, 0L, 0L), false); // CREATE_NEW: a racing sibling window wins, fine
            } catch (Throwable ignore) {
                // exists already or unwritable — the poller reports the latter
            }
        } else {
            parseMark(new String(existing, StandardCharsets.UTF_8)); // parser first-touch at load
        }
        markFile = mf;
        Thread p = new Thread(new Poller(), POLL_THREAD_NAME);
        p.setDaemon(true);
        p.start();
        return POLL_THREAD_NAME + "@" + POLL_MILLIS + "ms";
    }

    private static int tps() {
        try {
            return Math.max(1, ServerTickRate.ticksPerSecond());
        } catch (Throwable t) {
            return ServerTickRate.DEFAULT_TICKS_PER_SECOND;
        }
    }

    /** Per-combat registration from {@code PredictReconcileCombatPlugin.init}. */
    static void registerIfArmed(CombatEngineAPI engine, PredictReconcileCombatPlugin plugin) {
        if (!armed || disarmed || engine == null || plugin == null) {
            return;
        }
        try {
            nextTick = Long.MIN_VALUE;
            engine.addLayeredRenderingPlugin(new Capture(plugin));
        } catch (Throwable t) {
            disarmed = true;
            PilotWatchLog.error("mark-frames: register FAILED (disarmed)", t);
        }
    }

    static String describe() {
        if (disarmed) {
            return "DISARMED (fault)";
        }
        if (armed) {
            return "armed dir=" + FallbackAnnounce.sanitize(dir, 96) + " role=" + role + " every=" + everyTicks
                    + " pre=" + (preMs / 1000L) + "s post=" + (postMs / 1000L) + "s";
        }
        return "off";
    }

    // ----------------------------------------------------------------------------------------
    // Mark + teardown entry points (plugin thread)
    // ----------------------------------------------------------------------------------------

    /** The local F9 (called right after {@code markWire.send}); enqueue only, never disk, never throws. */
    static void onMark(int markNo, long simTick) {
        if (!armed || disarmed) {
            return;
        }
        try {
            MARKS.offer(new Mark(markNo, simTick, System.currentTimeMillis(), role, true));
        } catch (Throwable t) {
            PilotWatchLog.info("mark-frames: onMark fault " + t);
        }
    }

    /**
     * The round wrap (plugin thread, from the match-reset
     * right after {@code lastSnapshotSimTick} goes back to {@link Long#MIN_VALUE}): the server tick space
     * restarts at 0, so re-arm the capture guard, drop the previous round's ring (its ticks would sit
     * above every new-round mark) and bump {@code ROUND_EPOCH} so a slot still queued from the old round
     * is never inserted after the clear. Open {@code ACTIVE} flushes run to their own deadline
     * ({@link #writeFrame}'s monotonic check keeps new-round frames out of an old-round flush). No-op
     * unless armed; never throws.
     */
    static void onRoundReset() {
        if (!armed) {
            return;
        }
        try {
            ROUND_EPOCH = ROUND_EPOCH + 1; // single writer (plugin thread)
            nextTick = Long.MIN_VALUE;
            int n;
            long b;
            synchronized (RING) {
                n = RING.size();
                b = ringBytes;
                RING.clear();
                ringBytes = 0L;
            }
            PilotWatchLog.info("mark-frames: round reset ring dropped frames=" + n + " bytes=" + b + " epoch="
                    + ROUND_EPOCH);
        } catch (Throwable t) {
            PilotWatchLog.info("mark-frames: round reset fault " + t);
        }
    }

    /** Combat end / plugin teardown: drop the ring (an open post window finishes on its own, best-effort).
     *  Bumps {@code ROUND_EPOCH} too, so a slot still queued from
     *  this combat never lands in the next combat's ring. */
    static void onCombatEnd() {
        if (!armed) {
            return;
        }
        try {
            ROUND_EPOCH = ROUND_EPOCH + 1; // single writer (plugin thread)
            nextTick = Long.MIN_VALUE;
            int n;
            long b;
            synchronized (RING) {
                n = RING.size();
                b = ringBytes;
                RING.clear();
                ringBytes = 0L;
            }
            PilotWatchLog.info("mark-frames: teardown ring dropped frames=" + n + " bytes=" + b + " captured="
                    + CAPTURED.get() + " dropped=" + DROPPED.get() + " written=" + WRITTEN.get() + " collisions="
                    + COLLISIONS.get() + " errors=" + ERRORS.get() + " " + readStats());
        } catch (Throwable t) {
            PilotWatchLog.info("mark-frames: teardown fault " + t);
        }
    }

    /** {@code readPixels avg=<µs> max=<µs> n=<count> stale_epoch=<k>} — the render-thread residency
     *  measurement; + the round-reset stale-slot skip count. */
    private static String readStats() {
        long n = readN;
        return "readPixels avg=" + (n == 0 ? 0L : (readSumNs / n) / 1000L) + "us max=" + (readMaxNs / 1000L)
                + "us n=" + n + " stale_epoch=" + STALE_EPOCH.get();
    }

    // ----------------------------------------------------------------------------------------
    // Capture (render thread)
    // ----------------------------------------------------------------------------------------

    /** The layered plugin; one per combat (the engine discards it with the combat). */
    static final class Capture extends BaseCombatLayeredRenderingPlugin {
        private final PredictReconcileCombatPlugin plugin;

        Capture(PredictReconcileCombatPlugin plugin) {
            super(CombatEngineLayers.JUST_BELOW_WIDGETS);
            this.plugin = plugin;
        }

        @Override
        public float getRenderRadius() {
            return 100000.0F; // plugin entity sits at origin; we read the framebuffer, not the world
        }

        @Override
        public boolean isExpired() {
            return false;
        }

        @Override
        public void render(CombatEngineLayers layer, ViewportAPI viewport) {
            if (!armed || disarmed || plugin == null) {
                return;
            }
            try {
                long tick = plugin.lastSnapshotSimTick;
                if (tick == Long.MIN_VALUE) {
                    return; // no authoritative frame yet — nothing to stamp
                }
                // The cadence maths lives in MarkFrameGuard;
                // onRoundReset re-arms nextTick when the round wrap restarts the tick space.
                long nt = MarkFrameGuard.nextCaptureTick(tick, nextTick, everyTicks);
                if (nt == Long.MIN_VALUE) {
                    return;
                }
                nextTick = nt;
                Slot s = null;
                for (int i = 0; i < RAW_SLOTS; i++) {
                    if (RAW[i].busy.compareAndSet(false, true)) {
                        s = RAW[i];
                        break;
                    }
                }
                if (s == null) {
                    DROPPED.incrementAndGet(); // encoder behind: drop, never block the render thread
                    return;
                }
                int w = Display.getWidth();
                int h = Display.getHeight();
                if (w <= 0 || h <= 0) {
                    s.busy.set(false);
                    return;
                }
                int need = w * h * 4;
                if (s.rgba == null || s.rgba.capacity() < need) {
                    s.rgba = BufferUtils.createByteBuffer(need);
                }
                s.rgba.clear();
                long t0 = System.nanoTime();
                GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, s.rgba);
                long dt = System.nanoTime() - t0;
                readN = readN + 1;
                readSumNs = readSumNs + dt;
                if (dt > readMaxNs) {
                    readMaxNs = dt;
                }
                s.w = w;
                s.h = h;
                s.tick = tick;
                s.epoch = ROUND_EPOCH; //
                s.wallMs = System.currentTimeMillis();
                if (!QUEUE.offer(s)) {
                    s.busy.set(false);
                    DROPPED.incrementAndGet();
                    return;
                }
                CAPTURED.incrementAndGet();
            } catch (Throwable t) {
                disarmed = true;
                PilotWatchLog.error("mark-frames: capture FAILED (disarmed; match unaffected)", t);
            }
        }
    }

    // ----------------------------------------------------------------------------------------
    // Encoder + flush (the one disk-writing thread)
    // ----------------------------------------------------------------------------------------

    private static void encodeLoop() {
        BufferedImage img = null;
        byte[] row = null;
        ByteSink sink = new ByteSink();
        for (;;) {
            Slot s;
            try {
                s = QUEUE.poll(200L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
            try {
                long now = System.currentTimeMillis();
                drainMarks(now);
                // A slot captured before a round reset is
                // skipped (counted), never encoded — the cheap early check; the authoritative one
                // re-reads the epoch under RING at insert (a reset racing the encode).
                if (s != null && !disarmed && s.epoch != ROUND_EPOCH) {
                    STALE_EPOCH.incrementAndGet();
                } else if (s != null && !disarmed) {
                    int w = s.w;
                    int h = s.h;
                    if (img == null || img.getWidth() != w || img.getHeight() != h) {
                        img = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
                    }
                    if (row == null || row.length < w * 4) {
                        row = new byte[w * 4];
                    }
                    byte[] dst = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
                    ByteBuffer src = s.rgba;
                    for (int y = 0; y < h; y++) {
                        src.position((h - 1 - y) * w * 4); // GL rows are bottom-up
                        src.get(row, 0, w * 4);
                        int di = y * w * 3;
                        for (int x = 0, si = 0; x < w; x++, si += 4, di += 3) {
                            dst[di] = row[si + 2];     // B
                            dst[di + 1] = row[si + 1]; // G
                            dst[di + 2] = row[si];     // R
                        }
                    }
                    sink.reset();
                    if (!ImageIO.write(img, "jpg", sink)) {
                        throw new IOException("no ImageIO jpg writer");
                    }
                    Frame f = new Frame(s.tick, s.wallMs, w, h, sink.toArray());
                    boolean stale;
                    synchronized (RING) {
                        // onRoundReset bumps the epoch BEFORE it clears under RING, so a slot whose
                        // epoch still matches here is inserted ahead of (and dropped by) any clear.
                        stale = s.epoch != ROUND_EPOCH;
                        if (!stale) {
                            RING.addLast(f);
                            ringBytes += f.jpeg.length;
                            evictLocked(now);
                        }
                    }
                    if (stale) {
                        STALE_EPOCH.incrementAndGet();
                    } else {
                        for (Flush fl : ACTIVE) {
                            if (f.wallMs <= fl.deadlineMs) {
                                writeFrame(fl, f);
                            }
                        }
                    }
                }
                closeExpired(now);
            } catch (Throwable t) {
                int n = ERRORS.incrementAndGet();
                if (n == 1) {
                    PilotWatchLog.error("mark-frames: encode/write error #1 (fail-soft)", t);
                }
                if (n >= MAX_ERRORS) {
                    disarmed = true;
                    PilotWatchLog.info("mark-frames: DISARMED after " + n + " errors; written=" + WRITTEN.get());
                }
            } finally {
                if (s != null) {
                    s.busy.set(false);
                }
            }
        }
    }

    /** Drop-oldest by count, bytes and age; caller holds {@code RING}. */
    private static void evictLocked(long now) {
        long oldest = now - preMs - RING_SLACK_MS;
        for (;;) {
            Frame o = RING.peekFirst();
            if (o == null) {
                return;
            }
            if (RING.size() > MAX_RING_FRAMES || ringBytes > MAX_RING_BYTES || o.wallMs < oldest) {
                RING.pollFirst();
                ringBytes -= o.jpeg.length;
            } else {
                return;
            }
        }
    }

    private static void drainMarks(long now) {
        for (;;) {
            Mark m = MARKS.poll();
            if (m == null) {
                return;
            }
            if (m.local) {
                // The cross-window trigger: record the pair as OURS first so the poller never
                // re-fires on it, then rewrite the shared file (TRUNCATE_EXISTING, the WindowPosMemory idiom).
                ownMarkNo = m.no;
                ownMarkWall = m.wallMs;
                try {
                    writeBytes(markFileOrNull(), markFileText(m.no, m.tick, m.wallMs), true);
                } catch (Throwable t) {
                    PilotWatchLog.info("mark-frames: mark file write failed (cross-window trigger lost) " + t);
                }
            }
            openFlush(m, now);
        }
    }

    private static Path markFileOrNull() throws IOException {
        Path p = markFile;
        if (p == null) {
            throw new IOException("no mark file (poller off)");
        }
        return p;
    }

    private static byte[] markFileText(int no, long tick, long wallMs) {
        String text = "# cc-starsector mark origin=" + role + "\n"
                + "n=" + no + "\ntick=" + tick + "\nwall=" + wallMs + "\nrole=" + role + "\n";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void openFlush(Mark m, long now) {
        if (ACTIVE.size() >= MAX_ACTIVE_FLUSHES) {
            PilotWatchLog.info("mark-frames: mark #" + m.no + " origin=" + m.origin + " SKIPPED (" + ACTIVE.size()
                    + " flushes open)");
            return;
        }
        Flush fl = new Flush(m, m.wallMs + postMs);
        Frame[] snap;
        int ringN;
        long ringB;
        synchronized (RING) {
            snap = RING.toArray(new Frame[0]);
            ringN = RING.size();
            ringB = ringBytes;
        }
        long fromTick = m.tick - preTicks;
        // Bounded ABOVE too (m.tick + postTicks) — a test flight's
        // round-1 tick-706 mark wrote the frozen round-0 frames t15900-t16560 through the old one-sided filter.
        int pre0 = preTicks;
        int post0 = postTicks;
        for (Frame f : snap) {
            if (MarkFrameGuard.inPreWindow(f.tick, m.tick, pre0, post0)) {
                writeFrame(fl, f);
            }
        }
        int pre = fl.written;
        String stats = readStats();
        appendSidecar("{\"mark\":" + m.no + ",\"tick\":" + m.tick + ",\"wallMs\":" + m.wallMs + ",\"role\":\"" + role
                + "\",\"origin\":\"" + m.origin + "\",\"local\":" + m.local + ",\"preFrames\":" + pre
                + ",\"ringFrames\":" + ringN + ",\"ringBytes\":" + ringB + ",\"readAvgUs\":"
                + (readN == 0 ? 0L : (readSumNs / readN) / 1000L) + ",\"readMaxUs\":" + (readMaxNs / 1000L)
                + ",\"readN\":" + readN + ",\"captured\":" + CAPTURED.get() + ",\"dropped\":" + DROPPED.get() + "}");
        PilotWatchLog.info("mark-frames: FLUSH #" + m.no + " tick=" + m.tick + " origin=" + m.origin + " local="
                + m.local + " pre=" + pre + " frames (ring " + ringN + "/" + ringB + " B, from tick " + fromTick
                + ".." + (m.tick + post0) + ") post<=" + (postMs / 1000L) + "s " + stats + " dropped=" + DROPPED.get());
        if (postMs > 0L && now <= fl.deadlineMs) {
            ACTIVE.add(fl);
        } else {
            closeFlush(fl);
        }
    }

    private static void closeExpired(long now) {
        for (int i = ACTIVE.size() - 1; i >= 0; i--) {
            Flush fl = ACTIVE.get(i);
            if (now > fl.deadlineMs) {
                ACTIVE.remove(i);
                closeFlush(fl);
            }
        }
    }

    private static void closeFlush(Flush fl) {
        appendSidecar("{\"flush\":" + fl.mark.no + ",\"tick\":" + fl.mark.tick + ",\"role\":\"" + role
                + "\",\"origin\":\"" + fl.mark.origin + "\",\"written\":" + fl.written + ",\"collisions\":"
                + fl.collisions + ",\"lastTick\":" + fl.lastTick + "}");
        PilotWatchLog.info("mark-frames: flush #" + fl.mark.no + " done written=" + fl.written + " collisions="
                + fl.collisions + " lastTick=" + fl.lastTick + " " + readStats());
    }

    /** One frame for one flush; monotonic per flush (the post frames always follow the pre snapshot). */
    private static void writeFrame(Flush fl, Frame f) {
        if (f.tick < fl.lastTick || (f.tick == fl.lastTick && f.wallMs <= fl.lastWall)) {
            return;
        }
        fl.lastTick = f.tick;
        fl.lastWall = f.wallMs;
        String name = String.format(Locale.ROOT, "m%d-t%09d-%d.jpg", fl.mark.no, f.tick, f.wallMs);
        try {
            writeBytes(dirPath.resolve(name), f.jpeg, false);
        } catch (IOException e) {
            // A same-name file = the n-clash case (two windows both at mark #n over overlapping ticks,
            // or a repeated mark): logged once, never an overwrite, never an error toward disarm.
            if ("FileAlreadyExistsException".equals(e.getClass().getSimpleName())) {
                fl.collisions++;
                if (COLLISIONS.incrementAndGet() == 1) {
                    PilotWatchLog.info("mark-frames: name collision (kept the first) " + name);
                }
                return;
            }
            throw new IllegalStateException("frame write " + name, e);
        }
        fl.written++;
        WRITTEN.incrementAndGet();
        appendSidecar("{\"mark\":" + fl.mark.no + ",\"tick\":" + f.tick + ",\"wallMs\":" + f.wallMs + ",\"file\":\""
                + name + "\",\"role\":\"" + role + "\",\"origin\":\"" + fl.mark.origin + "\",\"w\":" + f.w + ",\"h\":"
                + f.h + "}");
    }

    /** APPEND one row to {@value #SIDECAR_NAME}; a failed row is an encode error, not a disarm by itself. */
    private static void appendSidecar(String json) {
        try {
            writeAppend(dirPath.resolve(SIDECAR_NAME), (json + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            if (ERRORS.incrementAndGet() == 1) {
                PilotWatchLog.error("mark-frames: sidecar append error #1 (fail-soft)", t);
            }
        }
    }

    /** {@code replace} = CREATE + TRUNCATE_EXISTING (warmup + the mark file); else CREATE_NEW (frames). */
    private static void writeBytes(Path p, byte[] data, boolean replace) throws IOException {
        FileChannel ch = replace
                ? FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)
                : FileChannel.open(p, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            ByteBuffer b = ByteBuffer.wrap(data);
            while (b.hasRemaining()) {
                ch.write(b);
            }
        } finally {
            ch.close();
        }
    }

    private static void writeAppend(Path p, byte[] data) throws IOException {
        FileChannel ch = FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
        try {
            ByteBuffer b = ByteBuffer.wrap(data);
            while (b.hasRemaining()) {
                ch.write(b);
            }
        } finally {
            ch.close();
        }
    }

    /** Whole small file or {@code null} (absent / unreadable / over the cap). Never throws. */
    private static byte[] readSmall(Path p) {
        try {
            FileChannel ch = FileChannel.open(p, StandardOpenOption.READ);
            try {
                long size = ch.size();
                if (size < 0L || size > MAX_MARK_FILE_BYTES) {
                    return null;
                }
                ByteBuffer buf = ByteBuffer.allocate((int) size);
                while (buf.hasRemaining() && ch.read(buf) >= 0) {
                    // read until full or EOF
                }
                buf.flip();
                byte[] bytes = new byte[buf.remaining()];
                buf.get(bytes);
                return bytes;
            } finally {
                ch.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    // ----------------------------------------------------------------------------------------
    // Cross-window mark file (poller thread)
    // ----------------------------------------------------------------------------------------

    /**
     * FAIL-CLOSED parse of the mark file ({@link WindowPosMemory#parse} discipline): {@code key=value}
     * lines, keys {@code n} (int) {@code tick} {@code wall} (long) {@code role} (sanitized token) each
     * at most once, {@code #} comments and blanks ignored, ANYTHING else = {@code null}. Package-private
     * for a game-free unit check.
     */
    static Mark parseMark(String text) {
        int n = 0;
        long tick = 0L;
        long wall = 0L;
        String r = null;
        boolean[] seen = new boolean[4];
        for (String line : text.split("\\r?\\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.charAt(0) == '#') {
                continue;
            }
            int eq = s.indexOf('=');
            if (eq <= 0) {
                return null;
            }
            String key = s.substring(0, eq).trim();
            String val = s.substring(eq + 1).trim();
            int slot;
            if ("n".equals(key)) {
                slot = 0;
            } else if ("tick".equals(key)) {
                slot = 1;
            } else if ("wall".equals(key)) {
                slot = 2;
            } else if ("role".equals(key)) {
                slot = 3;
            } else {
                return null;
            }
            if (seen[slot]) {
                return null;
            }
            try {
                if (slot == 0) {
                    n = Integer.parseInt(val);
                } else if (slot == 1) {
                    tick = Long.parseLong(val);
                } else if (slot == 2) {
                    wall = Long.parseLong(val);
                } else {
                    r = WindowPosMemory.sanitizeName(val);
                }
            } catch (NumberFormatException e) {
                return null;
            }
            seen[slot] = true;
        }
        if (!(seen[0] && seen[1] && seen[2])) {
            return null;
        }
        return new Mark(n, tick, wall, r == null ? "unknown" : r, false);
    }

    /**
     * The {@value #POLL_THREAD_NAME} daemon: every {@value #POLL_MILLIS} ms read the shared mark file;
     * a NEW {@code (n, wall)} pair that is not this window's own write and not older than this
     * window's arm time (a previous flight's file) is enqueued as a cross-window mark. A torn read
     * (the writer's TRUNCATE + WRITE is not atomic) parses as {@code null} and is simply retried;
     * only {@value #PARSE_FAILS_BEFORE_ROW} consecutive failures earn one row. Faults retire the
     * lane after {@value #MAX_ERRORS} with one row; nothing here ever throws into the plugin.
     */
    private static final class Poller implements Runnable {
        @Override
        public void run() {
            int seenNo = Integer.MIN_VALUE;
            long seenWall = Long.MIN_VALUE;
            int parseFails = 0;
            boolean parseRowDone = false;
            int errors = 0;
            while (true) {
                try {
                    Thread.sleep(POLL_MILLIS);
                } catch (InterruptedException ie) {
                    return;
                }
                if (disarmed) {
                    return;
                }
                try {
                    byte[] bytes = readSmall(markFile);
                    if (bytes == null || bytes.length == 0) {
                        continue; // absent, empty (mid-rewrite) or oversized: nothing to do
                    }
                    Mark m = parseMark(new String(bytes, StandardCharsets.UTF_8));
                    if (m == null) {
                        if (++parseFails >= PARSE_FAILS_BEFORE_ROW && !parseRowDone) {
                            parseRowDone = true;
                            PilotWatchLog.info("mark-frames: mark file unparseable (ignored; fail-closed) bytes="
                                    + bytes.length);
                        }
                        continue;
                    }
                    parseFails = 0;
                    if (m.no == seenNo && m.wallMs == seenWall) {
                        continue; // already handled
                    }
                    seenNo = m.no;
                    seenWall = m.wallMs;
                    if (m.no == ownMarkNo && m.wallMs == ownMarkWall) {
                        continue; // our own write
                    }
                    long now = System.currentTimeMillis();
                    if (m.wallMs < armedAtMs || m.wallMs > now + 120000L) {
                        continue; // a previous flight's mark (or clock garbage): never fires
                    }
                    MARKS.offer(m);
                    PilotWatchLog.info("mark-frames: cross-window mark #" + m.no + " tick=" + m.tick + " origin="
                            + m.origin + " age=" + (now - m.wallMs) + "ms -> flushing this window's ring");
                } catch (Throwable t) {
                    if (++errors >= MAX_ERRORS) {
                        PilotWatchLog.info("mark-frames: poller DEAD " + t);
                        return;
                    }
                }
            }
        }
    }
}
