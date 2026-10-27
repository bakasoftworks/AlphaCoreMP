package cc.starsector.client;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.ViewportAPI;

/**
 * OBSERVER tick-stamped frame dump
 * — the unattended eyes for the filmstrip observer-compare mode: a layered
 * rendering plugin at {@code JUST_BELOW_WIDGETS} (after every world/fx layer, before widgets)
 * reads the back buffer once per cadence and a daemon encoder writes {@code t<tick>-<wallMs>.jpg}
 * + a {@code frames.ndjson} sidecar row {@code {tick,wallMs,file,cx,cy,visW,visH,w,h,sx0,sy0,
 * pxKsu,pyKsu,viewMult}} (schema v2: the engine's OWN
 * world→screen affine at capture — {@code sx0/sy0 = convertWorldXtoScreenX(0)/YtoScreenY(0)},
 * {@code pxKsu/pyKsu = convertWorldWidthToScreenWidth(1000)/HeightToScreenHeight(1000)}, GL
 * screen y up from the bottom — so the filmstrip overlay projects with the engine's transform,
 * never a re-derivation) into the LAUNCHER-created directory {@code -Dcc.observer.frameDir}
 * (disk law: the HDD, never the SSD).
 * Armed when {@code cc.framedump.dir} is set on ANY window (own cadence/cap
 * knobs) or, observing, when {@link ObserverMode#ON} and the observer dir is set (default OFF); cadence
 * {@code cc.observer.frameEvery} server ticks (default 20 = 1 Hz), cap {@code cc.observer.frameMax}
 * (default 1800) then stop — no pileups. Stamp = {@code plugin.lastSnapshotSimTick} (the tick
 * the current frame reconciled to, render thread); {@code Long.MIN_VALUE} (no feed) = no frame.
 *
 * <p>Render-thread work = ONE {@code glReadPixels(RGBA, UNSIGNED_BYTE)} into a 2-slot ring of
 * pooled direct buffers; a frame whose slots are both busy is DROPPED (counted), never blocked
 * on. The encoder flips rows into a {@code TYPE_3BYTE_BGR} image and writes JPEG (≈250 KB vs
 * ≈2.5 MB PNG — triage-grade eyes on an HDD budget).
 *
 * <p>Classloader-B law (gate {@code check-b-deny-refs.ps1}; real predicate = {@code java.io*}
 * minus an allowlist + {@code java.nio.file.File*}): every
 * constant-pool ref here is B-LEGAL — {@code FileChannel}/{@code Channels}/{@code Paths}/
 * {@code StandardOpenOption}/{@code OutputStream}/{@code ImageIO}/{@code BufferedImage}/
 * {@code DataBufferByte}/{@code GL11}/{@code BufferUtils}/{@code Display}; {@code java.nio.file.Files}
 * and {@code java.io.File*} are DENIED, hence the launcher creates the dir and the client only
 * opens files inside it, and the sidecar rides {@code new PrintWriter(String)} = the ONE proven
 * B-legal write seam ({@link SmPassengerProbe}; a combat-time open works cold).
 * The JDK's own internals (ImageIO's streams) resolve on the bootstrap loader, never through B.
 *
 * <p>Started + pre-warmed at application load ({@link #startAtApplicationLoad}, from
 * {@link CcClientModPlugin}): the encoder thread, the ring, the nested classes, ImageIO's JPEG
 * writer and the FileChannel seam are all first-touched OUTSIDE the combat script SecurityManager
 * (which forbids NEW class loading) — a real {@code warmup.jpg} is written so a broken dir fails
 * at load with one log line, never mid-match. Fail-soft: any fault disarms the dump; the match
 * is never affected.
 */
final class ObserverFrameDump {
    static final String THREAD_NAME = "cc-observer-framedump";
    static final String SIDECAR_NAME = "frames.ndjson";
    private static final int RING = 2;
    private static final int MAX_ENCODE_ERRORS = 5;
    private static final int PROGRESS_EVERY = 60;

    private static volatile boolean armed;
    private static volatile boolean disarmed;
    private static volatile String dir;
    private static volatile int everyTicks = ObserverMode.DEFAULT_FRAME_EVERY_TICKS;
    private static volatile int frameMax = ObserverMode.DEFAULT_FRAME_MAX;
    private static final Slot[] RING_SLOTS = new Slot[RING];
    private static final ArrayBlockingQueue<Slot> QUEUE = new ArrayBlockingQueue<Slot>(RING);
    private static volatile PrintWriter sidecar;
    private static final AtomicInteger WRITTEN = new AtomicInteger();
    private static final AtomicInteger DROPPED = new AtomicInteger();
    private static final AtomicInteger ERRORS = new AtomicInteger();
    /** Render thread only (= the plugin thread, where registerIfArmed and onRoundReset
     *  re-arm {@code nextTick}). */
    private static int captured;
    private static long nextTick = Long.MIN_VALUE;
    private static boolean capLogged;

    /** One pooled capture: the direct RGBA buffer + the frame's stamp/meta. */
    static final class Slot {
        final AtomicBoolean busy = new AtomicBoolean();
        ByteBuffer rgba;
        int w;
        int h;
        long tick;
        long wallMs;
        float cx;
        float cy;
        float visW;
        float visH;
        // sidecar v2: the engine's affine sampled on the render thread at capture
        float sx0;
        float sy0;
        float pxKsu;
        float pyKsu;
        float viewMult;
    }

    private ObserverFrameDump() {
    }

    /** Application-load arm + pre-warm (B pre-warm pattern); a no-op unless observer + dir. */
    static void startAtApplicationLoad() {
        // cc.framedump.dir arms the SAME dump on ANY window (the piloted
        // seat-1 local window of the unattended podtest run; an operator's piloted session self-recording),
        // with its own cadence/cap knobs; else the legacy observer-only knobs when observing.
        String d = ObserverMode.frameDumpDir();
        int every;
        int max;
        if (d != null) {
            every = ObserverMode.frameDumpEveryTicks();
            max = ObserverMode.frameDumpMax();
        } else if (ObserverMode.ON) {
            d = ObserverMode.frameDir();
            if (d == null) {
                PilotWatchLog.info("observer frame dump: DISARMED (no " + ObserverMode.FRAME_DIR_PROP + ")");
                return;
            }
            every = ObserverMode.frameEveryTicks();
            max = ObserverMode.frameMax();
        } else {
            return;
        }
        try {
            dir = d;
            everyTicks = every;
            frameMax = max;
            for (int i = 0; i < RING; i++) {
                RING_SLOTS[i] = new Slot();
            }
            ImageIO.setUseCache(false); // no temp-file cache (java.io.File internals, and the SSD)
            BufferedImage warm = new BufferedImage(2, 2, BufferedImage.TYPE_3BYTE_BGR);
            ((DataBufferByte) warm.getRaster().getDataBuffer()).getData()[0] = (byte) 0x7f;
            writeJpeg(warm, "warmup.jpg", true); // the whole file seam, end to end, at load (replaceable)
            PrintWriter sc = new PrintWriter(Paths.get(dir, SIDECAR_NAME).toString());
            sc.println("{\"schema\":\"cc-observer-frames-v2\",\"everyTicks\":" + everyTicks
                    + ",\"frameMax\":" + frameMax + ",\"openedMs\":" + System.currentTimeMillis() + "}");
            sc.flush();
            if (sc.checkError()) {
                throw new IOException("sidecar open/write error: " + SIDECAR_NAME);
            }
            sidecar = sc;
            Thread t = new Thread(ObserverFrameDump::encodeLoop, THREAD_NAME);
            t.setDaemon(true);
            t.start();
            new Capture(null); // nested class first-touched here, never under the combat SecurityManager
            armed = true;
            PilotWatchLog.info("observer frame dump ARMED: dir=" + FallbackAnnounce.sanitize(dir, 120)
                    + " every=" + everyTicks + " ticks max=" + frameMax + " encoder=" + THREAD_NAME);
        } catch (Throwable t) {
            disarmed = true;
            PilotWatchLog.error("observer frame dump: arm FAILED (disarmed; match unaffected)", t);
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
            PilotWatchLog.error("observer frame dump: register FAILED (disarmed)", t);
        }
    }

    /**
     * The round wrap (plugin thread = the render thread, from
     * the match-reset right after {@code lastSnapshotSimTick} goes back to {@link Long#MIN_VALUE}): the
     * server tick space restarts at 0, so re-arm the capture guard (the reel froze at the
     * last round-0 tick). File names stay {@code t<tick>-<wallMs>.jpg}: the wallMs component keeps a
     * restarted tick from colliding with a round-0 name under CREATE_NEW, so no epoch is added; the
     * reel's tick column simply restarts (the wallMs orders it). No-op unless armed; never throws.
     */
    static void onRoundReset() {
        if (!armed || disarmed) {
            return;
        }
        try {
            long was = nextTick;
            nextTick = Long.MIN_VALUE;
            PilotWatchLog.info("observer frame dump: round reset capture guard re-armed (was nextTick="
                    + (was == Long.MIN_VALUE ? "none" : Long.toString(was)) + ") captured=" + captured + " written="
                    + WRITTEN.get() + " dropped=" + DROPPED.get());
        } catch (Throwable t) {
            PilotWatchLog.info("observer frame dump: round reset fault " + t);
        }
    }

    static String describe() {
        if (disarmed) {
            return "DISARMED (fault)";
        }
        if (armed) {
            return "armed dir=" + FallbackAnnounce.sanitize(dir, 96) + " every=" + everyTicks
                    + " max=" + frameMax;
        }
        return ObserverMode.ON ? "disarmed (no dir)" : "off";
    }

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
                    if (!plugin.vanillaWindow) {
                        return; // no authoritative frame yet — nothing to stamp
                    }
                    // A PURE vanilla: window never binds a session, so the
                    // snapshot tick stays MIN_VALUE for the whole reel — stamp by the engine clock at
                    // the standing 20 tps instead (the SAME t<tick> layout a served window dumps) so a
                    // serverless VANILLA REFERENCE reel lines up frame-for-frame beside a served reel
                    // (the ownerless vanilla-vs-server fidelity compare, run-mote-census-rig
                    // -VanillaTwin). Serverless only: a served window pre-bind still stamps nothing —
                    // a fake tick would mis-key the .ccwr observer-compare.
                    tick = vanillaClockTick();
                }
                if (captured >= frameMax) {
                    if (!capLogged) {
                        capLogged = true;
                        PilotWatchLog.info("observer frame dump: cap reached (" + frameMax + ") written="
                                + WRITTEN.get() + " dropped=" + DROPPED.get() + " errors=" + ERRORS.get());
                    }
                    return;
                }
                // The cadence maths lives in MarkFrameGuard;
                // onRoundReset re-arms nextTick when the round wrap restarts the tick space.
                long nt = MarkFrameGuard.nextCaptureTick(tick, nextTick, everyTicks);
                if (nt == Long.MIN_VALUE) {
                    return;
                }
                nextTick = nt;
                Slot s = null;
                for (int i = 0; i < RING; i++) {
                    if (RING_SLOTS[i].busy.compareAndSet(false, true)) {
                        s = RING_SLOTS[i];
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
                GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, s.rgba);
                s.w = w;
                s.h = h;
                s.tick = tick;
                s.wallMs = System.currentTimeMillis();
                org.lwjgl.util.vector.Vector2f c = viewport != null ? viewport.getCenter() : null;
                s.cx = c != null ? c.x : Float.NaN;
                s.cy = c != null ? c.y : Float.NaN;
                s.visW = viewport != null ? viewport.getVisibleWidth() : Float.NaN;
                s.visH = viewport != null ? viewport.getVisibleHeight() : Float.NaN;
                // v2: the engine's own affine (CombatViewport: (w - llx) / viewMult * worldXtoScreenX)
                s.sx0 = viewport != null ? viewport.convertWorldXtoScreenX(0.0F) : Float.NaN;
                s.sy0 = viewport != null ? viewport.convertWorldYtoScreenY(0.0F) : Float.NaN;
                s.pxKsu = viewport != null ? viewport.convertWorldWidthToScreenWidth(1000.0F) : Float.NaN;
                s.pyKsu = viewport != null ? viewport.convertWorldHeightToScreenHeight(1000.0F) : Float.NaN;
                s.viewMult = viewport != null ? viewport.getViewMult() : Float.NaN;
                if (!QUEUE.offer(s)) {
                    s.busy.set(false);
                    DROPPED.incrementAndGet();
                    return;
                }
                captured++;
            } catch (Throwable t) {
                disarmed = true;
                PilotWatchLog.error("observer frame dump: capture FAILED (disarmed; match unaffected)", t);
            }
        }
    }

    /**
     * Vanilla-window tick stand-in: combat elapsed time (paused time excluded) at the wire tick rate
     * ({@link ServerTickRate#ticksPerSecond()}, default 60 — the SAME clock the census lanes synthesize
     * from, so a twin reel's frame ticks line up with its own {@code predict … census tick=} rows and
     * with the served reel's snapshot ticks). The old literal 20 tps put the twin
     * frames on a 1/3 scale — a "same tick" twin/served sheet compared different sim seconds.
     */
    private static long vanillaClockTick() {
        CombatEngineAPI e = Global.getCombatEngine();
        if (e == null) {
            return Long.MIN_VALUE;
        }
        return Math.round(e.getTotalElapsedTime(false) * ServerTickRate.ticksPerSecond());
    }

    private static void encodeLoop() {
        BufferedImage img = null;
        byte[] row = null;
        for (;;) {
            Slot s;
            try {
                s = QUEUE.take();
            } catch (InterruptedException e) {
                return;
            }
            try {
                if (disarmed) {
                    continue;
                }
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
                String name = String.format(Locale.ROOT, "t%09d-%d.jpg", s.tick, s.wallMs);
                writeJpeg(img, name, false);
                PrintWriter sc = sidecar;
                if (sc != null) {
                    sc.println(String.format(Locale.ROOT,
                            "{\"tick\":%d,\"wallMs\":%d,\"file\":\"%s\",\"cx\":%.1f,\"cy\":%.1f,\"visW\":%.1f,\"visH\":%.1f,\"w\":%d,\"h\":%d,"
                                    + "\"sx0\":%.3f,\"sy0\":%.3f,\"pxKsu\":%.4f,\"pyKsu\":%.4f,\"viewMult\":%.5f}",
                            s.tick, s.wallMs, name, s.cx, s.cy, s.visW, s.visH, w, h,
                            s.sx0, s.sy0, s.pxKsu, s.pyKsu, s.viewMult));
                    sc.flush();
                }
                int n = WRITTEN.incrementAndGet();
                if (n % PROGRESS_EVERY == 0) {
                    PilotWatchLog.info("observer frame dump: written=" + n + " dropped=" + DROPPED.get()
                            + " errors=" + ERRORS.get() + " lastTick=" + s.tick);
                }
            } catch (Throwable t) {
                int n = ERRORS.incrementAndGet();
                if (n == 1) {
                    PilotWatchLog.error("observer frame dump: encode/write error #1 (fail-soft)", t);
                }
                if (n >= MAX_ENCODE_ERRORS) {
                    disarmed = true;
                    PilotWatchLog.info("observer frame dump: DISARMED after " + n + " errors; written=" + WRITTEN.get());
                }
            } finally {
                s.busy.set(false);
            }
        }
    }

    /** CREATE_NEW + WRITE through the B-legal channel seam; the JDK's ImageIO internals never touch B.
     * {@code replace} = CREATE + TRUNCATE_EXISTING — the warmup ONLY: a reused
     * frameDir must not disarm the whole dump on one CREATE_NEW collision; real frames stay
     * CREATE_NEW so a stamp collision surfaces as an encode error, never a silent overwrite. */
    private static void writeJpeg(BufferedImage img, String name, boolean replace) throws IOException {
        FileChannel ch = replace
                ? FileChannel.open(Paths.get(dir, name), StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
                : FileChannel.open(Paths.get(dir, name), StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
        OutputStream out = Channels.newOutputStream(ch);
        try {
            if (!ImageIO.write(img, "jpg", out)) {
                throw new IOException("no ImageIO jpg writer");
            }
        } finally {
            out.close();
        }
    }
}
