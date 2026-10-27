package cc.starsector.server.platform;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Process-global <b>L3 graphics substrate</b>: one real off-screen LWJGL2
 * {@code Pbuffer} GL context, made current on the calling thread, brought up <em>once</em>
 * for the whole server process. This is the first product-owned port of the substrate
 * context bring-up that a feasibility study identified as "the real work of build-step 2a"
 * and "scout-only — absent from {@code server/src}": every {@code Pbuffer} create +
 * {@code makeCurrent()} previously lived only in
 * the headless bootstrap scout script.
 * {@code HeadlessCombatContext} installs per-engine statics against an <em>already-current</em>
 * context; it never created one. This class creates it.
 *
 * <p><b>Why a context at all.</b> The engine's texture loader
 * ({@code ResourceLoaderState.init(Map)}) is gated behind a live GL context, not behind a
 * window: give the engine one off-screen context and it populates real
 * {@code com.fs.graphics.Object} textures headless, after which a system-bearing ship builds
 * with no stub (proven end-to-end). The {@code 16x16} Pbuffer +
 * {@code PixelFormat(8,8,0)} arguments are byte-identical to the lane the MEASURE
 * proved a {@code CombatEngine} builds + advances under — keep them so GREEN transfers
 * ("GREEN transfers iff 2a is a clean extraction").
 *
 * <p><b>Driver: llvmpipe.</b> This exact bring-up was A/B-tested on Mesa3D
 * <b>llvmpipe</b> (software GL, dropped in as {@code opengl32.dll} + {@code libgallium_wgl.dll}
 * with {@code GALLIUM_DRIVER=llvmpipe}) against hardware GL: both reached {@code L3_CONTEXT_PROVEN}
 * and loaded the identical 2488 textures (llvmpipe ~1.66x slower on upload), so the headless
 * server needs <b>no GPU</b> and the L2 null-GL shim is "not needed." The bring-up code here is
 * therefore <em>driver-agnostic</em> — selecting llvmpipe vs hardware GL is a launch-environment
 * decision (which {@code opengl32.dll} is on the JVM's DLL search path + {@code GALLIUM_DRIVER}),
 * supplied by the server's launch script, not by this class. {@link #rendererString()}
 * lets the caller confirm llvmpipe is actually in use ("a pass without that string is hardware GL
 * leaking through").
 *
 * <p><b>Thread affinity (load-bearing).</b> A GL context is bound to the thread that calls
 * {@code makeCurrent()}. {@link #ensureContextCurrent()} must be called on the same thread that
 * will subsequently drive {@code init(Map)} and the per-tick engine {@code advance} — i.e. the
 * server's combat-loop thread. The context stays current on that thread for the process lifetime
 * (the session-lifecycle design keeps the ONE context across reset rounds — no per-match teardown).
 *
 * <p><b>Fail-closed.</b> Any failure throws {@link RealEngineGlSubstrateException}; the substrate
 * is never left half-current silently. The held {@code Pbuffer} reference keeps the context from
 * being GC-reclaimed. We deliberately do <b>not</b> {@code System.loadLibrary} the native — LWJGL2
 * auto-loads its own native given {@code -Djava.library.path=<core/native/windows>} and
 * {@code cwd=starsector-core}; loading it here would dup-load it in the wrong classloader.
 *
 * <p><b>Staged scope.</b> This is stage 1 of the build-step 2a-&beta; process-global init.
 * Remaining stages (still owed) — all driving the engine's own paths, ported from the scout's
 * substrate gate:
 * <ul>
 *   <li>the settings-publish / mod-register preamble ({@code seedSettingsResources},
 *       {@code createSettingsApi}, {@code invokeGlobalSetSettings},
 *       {@code registerEnabledModJarsIntoScriptStore});
 *       </li>
 *   <li>the real {@code ResourceLoaderState.init(Map)} drive that populates textures.
 *       <b>Open question for the no-agent server:</b> the scout
 *       confirms the HOOK-0043 {@code Display.update()} no-op is live because its trace agent
 *       patches it; the dedicated server attaches no trace agent, so whether {@code init(Map)}
 *       reaches a {@code Display.update()} that throws "Display not created" — and therefore
 *       whether the server must attach {@code cc-premain.jar} (HOOK-0043 transform) — is a
 *       launch-time discovery;</li>
 *   <li>the per-call {@code CombatEngine} construct + {@code EngineCombatBinding.builder()}
 *       wiring (the construct lane the MEASURE proved).</li>
 * </ul>
 *
 *
 */
public final class RealEngineGlSubstrate {

    /** GL_RENDERER, read reflectively so no LWJGL type is imported at compile time. */
    private static final int GL_RENDERER = 0x1F01;

    /** Off-screen Pbuffer dimensions + pixel format — byte-identical to the scout MEASURE lane. */
    private static final int PBUFFER_WIDTH = 16;
    private static final int PBUFFER_HEIGHT = 16;
    private static final int PIXEL_FORMAT_BPP = 8;
    private static final int PIXEL_FORMAT_ALPHA = 8;
    private static final int PIXEL_FORMAT_DEPTH = 0;

    /** Held so the GC cannot reclaim the live context (and its native handle). */
    private static Object pbuffer;
    private static boolean contextCurrent;

    /** Fail-closed wrapper so caller code can stay off the reflective, engine-internal surface. */
    public static final class RealEngineGlSubstrateException extends RuntimeException {
        public RealEngineGlSubstrateException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private RealEngineGlSubstrate() {
    }

    /**
     * Bring the process-global off-screen Pbuffer GL context up and make it current on the
     * <em>calling thread</em> (idempotent: a no-op once current). Must be called on the thread
     * that will drive {@code init(Map)} and the engine {@code advance} (see thread-affinity note).
     *
     * @throws RealEngineGlSubstrateException if the context cannot be created or made current
     *         (e.g. LWJGL native missing from {@code java.library.path}, no {@code opengl32.dll}
     *         on the DLL search path, or the driver lacks Pbuffer support).
     */
    public static synchronized void ensureContextCurrent() {
        if (contextCurrent) {
            return;
        }
        ClassLoader loader = RealEngineGlSubstrate.class.getClassLoader();
        try {
            Class<?> pbufferClass = Class.forName("org.lwjgl.opengl.Pbuffer", true, loader);
            Class<?> pixelFormatClass = Class.forName("org.lwjgl.opengl.PixelFormat", true, loader);
            Class<?> drawableClass = Class.forName("org.lwjgl.opengl.Drawable", false, loader);
            Object pixelFormat = pixelFormatClass
                    .getConstructor(int.class, int.class, int.class)
                    .newInstance(PIXEL_FORMAT_BPP, PIXEL_FORMAT_ALPHA, PIXEL_FORMAT_DEPTH);
            Constructor<?> pbufferCtor = pbufferClass.getConstructor(
                    int.class, int.class, pixelFormatClass, drawableClass);
            Object created = pbufferCtor.newInstance(
                    PBUFFER_WIDTH, PBUFFER_HEIGHT, pixelFormat, null);
            pbufferClass.getMethod("makeCurrent").invoke(created);
            pbuffer = created;
            contextCurrent = true;
        } catch (Throwable t) {
            Throwable cause = (t instanceof InvocationTargetException && t.getCause() != null)
                    ? t.getCause() : t;
            throw new RealEngineGlSubstrateException(
                    "failed to bring up the L3 off-screen Pbuffer GL context: "
                            + cause.getClass().getName() + ": "
                            + (cause.getMessage() == null ? "" : cause.getMessage()),
                    cause);
        }
    }

    /** Whether the process-global GL context is up and current. */
    public static synchronized boolean contextCurrent() {
        return contextCurrent;
    }

    /**
     * Best-effort {@code glGetString(GL_RENDERER)} on the current context, for confirming the
     * active driver (e.g. that it contains {@code "llvmpipe"} on the GPU-independent path).
     * Returns {@code ""} if no context is current or the read fails; never throws.
     */
    public static String rendererString() {
        try {
            ClassLoader loader = RealEngineGlSubstrate.class.getClassLoader();
            Class<?> gl11 = Class.forName("org.lwjgl.opengl.GL11", true, loader);
            Method glGetString = gl11.getMethod("glGetString", int.class);
            Object renderer = glGetString.invoke(null, GL_RENDERER);
            return renderer == null ? "" : renderer.toString();
        } catch (Throwable t) {
            return "";
        }
    }
}
