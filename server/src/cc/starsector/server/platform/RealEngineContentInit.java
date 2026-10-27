package cc.starsector.server.platform;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Process-global <b>content initialization</b> — stage (ii) of build-step 2a-&beta;: the
 * settings-publish / mod-register preamble plus the real
 * {@code ResourceLoaderState.init(Map)} texture drive, run <em>once</em> for the whole
 * server process on the substrate thread. This is the product-owned port of the scout
 * MEASURE lane's preamble + loader-drive block.
 *
 * {@link RealEngineGlSubstrate} (stage 1) brought up the GL context,
 * this populates the engine's real texture registry on top of it so a system-bearing ship
 * builds with no L0 stub (proven end-to-end).
 *
 * <p><b>Order / thread affinity (load-bearing).</b> {@link #ensureInitialized()} must run on
 * the <em>same thread</em> that called {@link RealEngineGlSubstrate#ensureContextCurrent()}
 * and that will subsequently drive the engine {@code advance} — a GL context is bound to its
 * thread, and {@code init(Map)} uploads textures into that context. This method fail-closes if
 * the substrate context is not current (it never brings the context up itself; that is stage 1's
 * job, deliberately separated so the factory wires the two in the proven order).
 *
 * <p><b>Option R — literal identities, pinned + centralized.</b>
 * Every engine-internal {@code com.fs.*} identity this resolver feeds into live reflection is rendered
 * <em>literally</em>, byte-identical to the MEASURE lane the GREEN was proved under
 * (resolving by a different method risks selecting a different overload, so GREEN might not
 * transfer). They are confined to the {@code PINNED 0.98a-RC8} constant block below; the adapter
 * BOUNDARY ({@code EngineCombatBinding} + the {@code *View} interfaces) stays identity-clean — this
 * class is exactly the documented "caller" that resolves version-specific handles and hands them in.
 * Descriptor-grade resolution is the documented upgrade for the next game RC (standing
 * portability-drill trigger), not now.
 *
 * <p><b>HOOK-0043 (OPEN, launch-time discovery).</b> Under a Pbuffer-only context it was measured that
 * {@code Display.update()} throws "Display not created"; the scout proved {@code init(Map)} completes
 * because its trace agent patches that call into an in-place no-op (HOOK-0043 transform).
 * The dedicated server attaches <em>no</em> trace agent, so whether {@code init(Map)} reaches a
 * throwing {@code Display.update()} — and therefore whether the server must launch with
 * {@code cc-premain.jar} attached — is confirmed only at the launch ({@code inferred}: a no-agent
 * server likely needs it). This method runs the {@code Display.update()} probe, records the result
 * ({@link #displayUpdateNoThrow()}), and <b>fails closed</b> on the "Display not created" boundary
 * with a message pointing at attaching the premain agent — never a silent swallow.
 *
 * <p><b>Fail-closed, and the one difference from the MEASURE lane.</b> The scout <em>measures</em>
 * boundaries (it records "boundary" as a non-error outcome and tears the Pbuffer down per probe).
 * The product must <em>succeed or throw</em>: {@code init(Map)} not completing means textures are
 * absent and no ship can build, so any failure raises {@link RealEngineContentInitException}, and
 * the one GL context is <b>never destroyed</b> — it is kept across reset rounds
 * (session-lifecycle design, no per-match teardown), so there is deliberately no
 * {@code pbuffer.destroy()} here.
 *
 * <p><b>Staged scope.</b> Stage (iii) — {@code RealEngineCombatBindingFactory.create()} — calls
 * {@link RealEngineGlSubstrate#ensureContextCurrent()} then {@link #ensureInitialized()} (both
 * idempotent) and finally constructs the {@code CombatEngine} + wires the
 * {@code EngineCombatBinding}.
 *
 *
 */
public final class RealEngineContentInit {

    // ---- PINNED 0.98a-RC8 (descriptor-grade identity owed at the next RC — standing
    // portability-drill trigger). Option R: literal here, boundary clean. ----

    /** Settings singleton holder; its static load method seeds {@code Global.getSettings()}. */
    private static final String SETTINGS_CLASS = "com.fs.starfarer.settings.StarfarerSettings";
    /** The first 12 hex chars of SHA-256(field name) of the static {@code org.json.JSONObject} settings field. */
    private static final String SETTINGS_JSON_FIELD_NAME_HASH = "c1a976909838";
    /** Resource registry (singleton + the seed methods named "class"/"new"). */
    private static final String RESOURCE_CLASS = "com.fs.util.C";
    /** Engine-internal method on the resource registry: add-absolute-and-cwd (no-arg) / add-directory(String). */
    private static final String RESOURCE_ADD_DIR_METHOD = "class";
    /** Engine-internal method on the resource registry: add-classpath (no-arg). */
    private static final String RESOURCE_ADD_CLASSPATH_METHOD = "new";
    /**
     * Engine-internal method on the resource registry: front-insert add-mod-directory
     * taking {@code (String path, boolean isMod, Object modSpec)} — adds at index 0 so mod files
     * overlay base content (the {@code StarfarerLauncher} per-enabled-mod registration).
     *
     */
    private static final String RESOURCE_ADD_MOD_DIR_METHOD = "o00000";
    /** The "../starfarer.res/res" content directory the launcher seeds. */
    private static final String RESOURCE_DIRECTORY = "../starfarer.res/res";
    /** Mod manager singleton (drives enabled-mod list + the mods path setting). */
    private static final String MOD_MANAGER_CLASS = "com.fs.starfarer.launcher.ModManager";
    /** ScriptStore — its static {@code List} jar-path field is where launcher registers mod jars. */
    private static final String SCRIPT_STORE_CLASS = "com.fs.starfarer.loading.scripts.ScriptStore";
    /** Texture registry; its declared no-arg getter named "super" returns the texture map. */
    private static final String TEXTURE_REGISTRY_CLASS = "com.fs.graphics.oOoO";
    /** Engine-internal no-arg getter on the texture registry returning the {@code Map} of textures. */
    private static final String TEXTURE_MAP_GETTER = "super";
    /** The texture loader whose {@code init(Map)} populates the registry headless. */
    private static final String RESOURCE_LOADER_STATE_CLASS = "com.fs.starfarer.loading.ResourceLoaderState";

    // ---- Public API with readable names (identity-clean even under descriptor-grade). ----
    private static final String SETTINGS_API_CLASS = "com.fs.starfarer.api.SettingsAPI";
    private static final String GLOBAL_CLASS = "com.fs.starfarer.api.Global";
    private static final String SOUND_PLAYER_API_CLASS = "com.fs.starfarer.api.SoundPlayerAPI";
    private static final String LWJGL_DISPLAY_CLASS = "org.lwjgl.opengl.Display";
    private static final String JSON_OBJECT_CLASS = "org.json.JSONObject";

    private static boolean initialized;
    private static boolean displayUpdateNoThrow;
    private static boolean settingsPublished;
    private static int textureMapSizeBefore = Integer.MIN_VALUE;
    private static int textureMapSizeAfter = Integer.MIN_VALUE;
    private static int modJarsRegistered;
    private static String modRegistrationError = "";
    private static int modDataDirsRegistered;
    private static String modDataDirError = "";

    /** Fail-closed wrapper so caller code stays off the engine-internal, reflective surface. */
    public static final class RealEngineContentInitException extends RuntimeException {
        public RealEngineContentInitException(String message) {
            super(message);
        }

        public RealEngineContentInitException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private RealEngineContentInit() {
    }

    /**
     * Idempotent process-global content init: publish {@code Global} settings, register enabled-mod
     * jars into {@code ScriptStore}, then drive the real {@code ResourceLoaderState.init(Map)} so the
     * engine's texture registry is populated headless. Must be called <em>after</em>
     * {@link RealEngineGlSubstrate#ensureContextCurrent()} on the same thread.
     *
     * @throws RealEngineContentInitException if the substrate context is not current, settings fail to
     *         publish, the HOOK-0043 {@code Display.update()} boundary is hit (no premain agent), or
     *         {@code init(Map)} does not complete — never a silent partial init.
     * @throws ActivationGate.ActivationRefusedException if the game's own activation check does not
     *         report this copy activated (the key gate's FLOOR seat; nothing else runs first)
     */
    public static synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }
        // The key gate's FLOOR seat — every real-engine boot passes
        // here, not only the pilot serve main; the in-JVM memo makes it free after that main's early seat.
        ActivationGate.enforce();
        if (!RealEngineGlSubstrate.contextCurrent()) {
            throw new RealEngineContentInitException(
                    "RealEngineGlSubstrate.ensureContextCurrent() must run first, on this thread: "
                            + "init(Map) uploads textures into the thread-bound GL context");
        }
        ClassLoader loader = RealEngineContentInit.class.getClassLoader();

        // ---- HOOK-0043 probe: is Display.update() a no-op in THIS jvm? (no-agent server => likely not) ----
        String displayUpdateError = "";
        try {
            Class<?> displayClass = Class.forName(LWJGL_DISPLAY_CLASS, true, loader);
            displayClass.getMethod("update").invoke(null);
            displayUpdateNoThrow = true;
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            displayUpdateError = cause.getClass().getName() + ": "
                    + (cause.getMessage() == null ? "" : cause.getMessage());
        }

        try {
            // ---- Seed + publish Global settings (the loader reads Global.getSettings()) ----
            seedSettingsResources(loader);
            Class<?> settingsClass = Class.forName(SETTINGS_CLASS, true, loader);
            Method settingsLoad = uniqueMethod(settingsClass, true, Void.TYPE, new Class<?>[0],
                    new String[] { "java.io.IOException", "org.json.JSONException" });
            invokeStaticNoArg(settingsClass.getName(), settingsLoad);

            Field settingsJsonField = selectStaticJsonObjectFieldByNameHash(
                    settingsClass, SETTINGS_JSON_FIELD_NAME_HASH);
            boolean settingsJsonReady = false;
            if (settingsJsonField != null) {
                Object settingsJson = settingsJsonField.get(null);
                settingsJsonReady = settingsJson != null
                        && JSON_OBJECT_CLASS.equals(settingsJson.getClass().getName())
                        && jsonObjectLength(settingsJson) > 0;
            }
            if (!settingsJsonReady) {
                throw new RealEngineContentInitException(
                        "settings static JSON not ready after load (field hash "
                                + SETTINGS_JSON_FIELD_NAME_HASH + ")");
            }

            Class<?> settingsApiClass = Class.forName(SETTINGS_API_CLASS, false, loader);
            Method settingsApiFactory = uniqueMethod(
                    settingsClass, true, settingsApiClass, new Class<?>[0], null);
            Object settingsApi = settingsApiFactory.invoke(null);
            if (settingsApi == null) {
                throw new RealEngineContentInitException(
                        SETTINGS_CLASS + "." + settingsApiFactory.getName() + "() returned null SettingsAPI");
            }
            Class<?> globalClass = Class.forName(GLOBAL_CLASS, true, loader);
            globalClass.getMethod("setSettings", settingsApiClass).invoke(null, settingsApi);
            Object readback = globalClass.getMethod("getSettings").invoke(null);
            settingsPublished = readback != null && readback == settingsApi;
            if (!settingsPublished) {
                throw new RealEngineContentInitException(
                        "Global.setSettings/getSettings round-trip did not return the published instance");
            }

            // ---- Publish a no-op SoundPlayerAPI so engine call sites that
            // dereference Global.getSoundPlayer() headless hit a real instance, not null.
            // setSoundPlayer is a plain putstatic; the 18-method deref sweep is
            // GREEN on null/zero returns, so the DefaultProxyHandler idiom
            // (HeadlessCombatContext.seedModManagerProxy precedent) is faithful here. ----
            Class<?> soundPlayerApiClass = Class.forName(SOUND_PLAYER_API_CLASS, false, loader);
            Object soundPlayer = Proxy.newProxyInstance(loader,
                    new Class<?>[] { soundPlayerApiClass },
                    new NoOpProxyHandler("HeadlessSoundPlayerProxy"));
            globalClass.getMethod("setSoundPlayer", soundPlayerApiClass).invoke(null, soundPlayer);
            Object soundReadback = globalClass.getMethod("getSoundPlayer").invoke(null);
            if (soundReadback != soundPlayer) {
                throw new RealEngineContentInitException(
                        "Global.setSoundPlayer/getSoundPlayer round-trip did not return the published proxy");
            }

            // ---- Replicate the launcher's mod-jar registration BEFORE init reaches script_store load.
            // Fail-soft (records error) so the true boundary surfaces at init(Map), not masked here;
            // the demo's enabled_mods = ["cc_client"] => this registers the client jar. ----
            registerEnabledModJarsIntoScriptStore(loader);

            // ---- Replicate the launcher's per-enabled-mod resource-DIRECTORY registration so the
            // engine's own content loaders (the spec store's load pass -> ShipHullSpecLoader skins +
            // SpecStore variant scan) discover mod data files (e.g. the cc_client dmod-free skin
            // mudskipper2_cc + its mudskipper2_cc_Hellbore variant) by directory scan, with NO
            // hand-built specs. Must run AFTER seedSettingsResources (base dir registered first) and
            // BEFORE init(Map). Fail-soft so the true boundary surfaces at variant resolution
            // (RealEngineDemoRoster.resolveDemoVariant fail-closed), not masked here. ----
            registerEnabledModDataDirsIntoResourceRegistry(loader);

            // ---- Snapshot the texture registry map before/after the loader runs ----
            Class<?> textureRegistryClass = Class.forName(TEXTURE_REGISTRY_CLASS, true, loader);
            Method textureMapGetter = textureRegistryClass.getDeclaredMethod(TEXTURE_MAP_GETTER);
            textureMapGetter.setAccessible(true);
            textureMapSizeBefore = mapSizeOrMin(textureMapGetter.invoke(null));

            // ---- Construct the real ResourceLoaderState + drive init(Map) under the substrate ----
            Class<?> loaderStateClass = Class.forName(RESOURCE_LOADER_STATE_CLASS, true, loader);
            Constructor<?> ctor = loaderStateClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object state = ctor.newInstance();
            Method initMethod = loaderStateClass.getDeclaredMethod("init", Map.class);
            initMethod.setAccessible(true);
            try {
                initMethod.invoke(state, new HashMap<Object, Object>());
            } catch (InvocationTargetException e) {
                throw initMapBoundary(e.getCause() == null ? e : e.getCause(), displayUpdateError);
            }
            textureMapSizeAfter = mapSizeOrMin(textureMapGetter.invoke(null));

            initialized = true;
        } catch (RealEngineContentInitException e) {
            throw e;
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            throw new RealEngineContentInitException(
                    "headless content init failed before completing ResourceLoaderState.init(Map): "
                            + cause.getClass().getName() + ": "
                            + (cause.getMessage() == null ? "" : cause.getMessage()),
                    cause);
        }
    }

    /** Whether content init has completed for this process. */
    public static synchronized boolean initialized() {
        return initialized;
    }

    /** HOOK-0043 probe result: whether {@code Display.update()} was a no-op in this jvm. */
    public static synchronized boolean displayUpdateNoThrow() {
        return displayUpdateNoThrow;
    }

    /** Whether {@code Global} settings were published (round-trip confirmed). */
    public static synchronized boolean settingsPublished() {
        return settingsPublished;
    }

    /** Texture registry map size observed before {@code init(Map)} ({@code MIN_VALUE} if unread). */
    public static synchronized int textureMapSizeBefore() {
        return textureMapSizeBefore;
    }

    /** Texture registry map size observed after {@code init(Map)} ({@code MIN_VALUE} if unread). */
    public static synchronized int textureMapSizeAfter() {
        return textureMapSizeAfter;
    }

    /** Count of enabled-mod jars registered into {@code ScriptStore} (0 under empty mods). */
    public static synchronized int modJarsRegistered() {
        return modJarsRegistered;
    }

    /** Non-empty if mod-jar registration failed (fail-soft; the boundary then surfaces at init). */
    public static synchronized String modRegistrationError() {
        return modRegistrationError;
    }

    /** Count of enabled-mod DATA directories front-inserted into the resource registry (0 under empty mods). */
    public static synchronized int modDataDirsRegistered() {
        return modDataDirsRegistered;
    }

    /** Non-empty if mod-data-dir registration failed (fail-soft; the boundary then surfaces at variant resolution). */
    public static synchronized String modDataDirError() {
        return modDataDirError;
    }

    /**
     * Map a failed {@code init(Map)} to a fail-closed exception, pointing at the HOOK-0043 premain
     * agent when the cause is the "Display not created" boundary (which the no-agent server will hit
     * unless {@code cc-premain.jar}'s no-op transform is attached).
     */
    private static RealEngineContentInitException initMapBoundary(Throwable cause, String displayUpdateError) {
        String type = cause.getClass().getName();
        String message = cause.getMessage() == null ? "" : cause.getMessage();
        boolean displayBoundary = !displayUpdateNoThrow
                || message.toLowerCase().contains("display not created")
                || message.toLowerCase().contains("display");
        String hint = displayBoundary
                ? " — HOOK-0043 Display.update() boundary (display_update_no_throw="
                        + displayUpdateNoThrow + ", probe='" + displayUpdateError
                        + "'); launch the server with cc-premain.jar attached so Display.update() is a"
                        + " no-op headless"
                : "";
        return new RealEngineContentInitException(
                "ResourceLoaderState.init(Map) hit a measured boundary headless: "
                        + type + ": " + message + hint,
                cause);
    }

    /**
     * Seed the resource registry + mod manager exactly as the launcher does
     * (scout {@code seedSettingsResources}): resource singleton, add
     * absolute+cwd, add the content directory, add classpath, then construct the mod manager
     * (its {@code <init>} reads the mods path setting, needed before mod-jar registration).
     */
    private static void seedSettingsResources(ClassLoader loader) throws Exception {
        Class<?> resourceClass = Class.forName(RESOURCE_CLASS, true, loader);
        Method resourceSingleton = uniqueMethod(resourceClass, true, resourceClass, new Class<?>[0], null);
        Object resourceRegistry = resourceSingleton.invoke(null);

        resourceClass.getDeclaredMethod(RESOURCE_ADD_DIR_METHOD).invoke(resourceRegistry);
        resourceClass.getDeclaredMethod(RESOURCE_ADD_DIR_METHOD, String.class)
                .invoke(resourceRegistry, RESOURCE_DIRECTORY);
        resourceClass.getDeclaredMethod(RESOURCE_ADD_CLASSPATH_METHOD).invoke(resourceRegistry);

        Class<?> modManagerClass = Class.forName(MOD_MANAGER_CLASS, true, loader);
        modManagerClass.getDeclaredMethod("getInstance").invoke(null);
    }

    /**
     * Replicate {@code StarfarerLauncher}'s mod-jar registration into {@code ScriptStore}'s static
     * jar-path list (scout {@code registerEnabledModJarsIntoScriptStore}).
     * Without it a jar-only mod plugin's {@code Class.forName} silently fails and the
     * Janino SOURCE fallback throws, killing init. Descriptor-grade selection: the jar list is the
     * unique public static no-arg {@code ScriptStore} method returning {@code java.util.List} (its
     * same-named sibling returns {@code Set}). Under empty mods this is a no-op. Fail-soft: an error is
     * recorded ({@link #modRegistrationError()}) so the real boundary is measured at {@code init(Map)},
     * not masked by a crash here.
     */
    @SuppressWarnings("unchecked")
    private static void registerEnabledModJarsIntoScriptStore(ClassLoader loader) {
        try {
            Class<?> modManagerClass = Class.forName(MOD_MANAGER_CLASS, true, loader);
            Object modManager = modManagerClass.getMethod("getInstance").invoke(null);
            List<?> enabledMods = (List<?>) modManagerClass.getMethod("getEnabledMods").invoke(modManager);

            Class<?> scriptStoreClass = Class.forName(SCRIPT_STORE_CLASS, true, loader);
            Method jarListGetter = null;
            for (Method m : scriptStoreClass.getMethods()) {
                if (!Modifier.isStatic(m.getModifiers())
                        || m.getParameterTypes().length != 0
                        || m.getReturnType() != List.class) {
                    continue;
                }
                if (jarListGetter != null) {
                    throw new IllegalStateException("ambiguous ScriptStore static no-arg List getter: "
                            + jarListGetter.getName() + " vs " + m.getName());
                }
                jarListGetter = m;
            }
            if (jarListGetter == null) {
                throw new NoSuchMethodException("no public static no-arg java.util.List getter on ScriptStore");
            }
            List<String> jarList = (List<String>) jarListGetter.invoke(null);
            if (jarList == null) {
                throw new IllegalStateException("ScriptStore jar-list getter returned null");
            }
            for (Object spec : enabledMods) {
                String modPath = (String) spec.getClass().getMethod("getPath").invoke(spec);
                List<?> jars = (List<?>) spec.getClass().getMethod("getJars").invoke(spec);
                for (Object jar : jars) {
                    String fullPath = modPath + "/" + jar; // exact launcher composition (forward slash)
                    jarList.add(fullPath);
                    modJarsRegistered++;
                    if (!new File(fullPath).isFile()) {
                        if (modRegistrationError.isEmpty()) {
                            modRegistrationError = "mod jar not found on disk: " + fullPath;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            modRegistrationError = cause.getClass().getName() + ": "
                    + (cause.getMessage() == null ? "" : cause.getMessage());
        }
    }

    /**
     * Replicate {@code StarfarerLauncher}'s per-enabled-mod resource-DIRECTORY registration
     * (the launcher's per-mod loop's
     * front-insert). Without it, an enabled mod's <em>data</em> directory is never on the resource
     * registry {@code com.fs.util.C}, so the engine's own content loaders (the spec store load pass's
     * skin + variant directory scans) never see the mod's {@code data/hulls/skins/*.skin} or
     * {@code data/variants/*.variant} — they are discovered purely by directory scan (no CSV gate), so
     * adding the mod root is the whole fix. This is the faithful counterpart to the mod-JAR step above
     * (that one registers code into {@code ScriptStore}; this registers DATA into {@code C}); the
     * launcher does both. The front-insert {@code (path, true, spec)} overload puts mod files ahead of
     * base content so a mod overlays/overrides base — base ({@code ../starfarer.res/res}) was registered
     * first in {@link #seedSettingsResources}. Idempotent in practice: this runs once per process inside
     * the {@code init} guard. Fail-soft: an error is recorded ({@link #modDataDirError()}) so the real
     * boundary is measured at variant resolution (the demo roster's fail-closed lookup), not masked here.
     */
    private static void registerEnabledModDataDirsIntoResourceRegistry(ClassLoader loader) {
        try {
            Class<?> resourceClass = Class.forName(RESOURCE_CLASS, true, loader);
            Method resourceSingleton = uniqueMethod(resourceClass, true, resourceClass, new Class<?>[0], null);
            Object resourceRegistry = resourceSingleton.invoke(null);
            Method addModDir = resourceClass.getDeclaredMethod(
                    RESOURCE_ADD_MOD_DIR_METHOD, String.class, boolean.class, Object.class);

            Class<?> modManagerClass = Class.forName(MOD_MANAGER_CLASS, true, loader);
            Object modManager = modManagerClass.getMethod("getInstance").invoke(null);
            List<?> enabledMods = (List<?>) modManagerClass.getMethod("getEnabledMods").invoke(modManager);

            for (Object spec : enabledMods) {
                String modPath = (String) spec.getClass().getMethod("getPath").invoke(spec);
                addModDir.invoke(resourceRegistry, modPath, true, spec);
                modDataDirsRegistered++;
                if (!new File(modPath).isDirectory() && modDataDirError.isEmpty()) {
                    modDataDirError = "enabled-mod path not a directory on disk: " + modPath;
                }
            }
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            modDataDirError = cause.getClass().getName() + ": "
                    + (cause.getMessage() == null ? "" : cause.getMessage());
        }
    }

    /**
     * The unique static {@code org.json.JSONObject} field on {@code settingsClass} whose
     * SHA-256 name hash (its first 12 hex chars) equals {@code expectedNameHash} (scout
     * {@code selectStaticJsonObjectFieldByNameHash}). Returns {@code null} (caller
     * fail-closes) unless exactly one matches.
     */
    private static Field selectStaticJsonObjectFieldByNameHash(Class<?> settingsClass, String expectedNameHash) {
        Field selected = null;
        int matchingHashCount = 0;
        for (Field field : settingsClass.getDeclaredFields()) {
            boolean staticJson = Modifier.isStatic(field.getModifiers())
                    && JSON_OBJECT_CLASS.equals(field.getType().getName());
            if (!staticJson) {
                continue;
            }
            if (expectedNameHash.equals(shortSha256Hex(field.getName()))) {
                selected = field;
                matchingHashCount++;
            }
        }
        if (selected == null || matchingHashCount != 1) {
            return null;
        }
        selected.setAccessible(true);
        return selected;
    }

    private static int jsonObjectLength(Object jsonObject) {
        try {
            Object count = jsonObject.getClass().getMethod("length").invoke(jsonObject);
            if (count instanceof Number) {
                return ((Number) count).intValue();
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return Integer.MIN_VALUE;
    }

    private static void invokeStaticNoArg(String className, Method method) {
        try {
            method.invoke(null);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new RealEngineContentInitException(
                    className + "." + method.getName() + "() threw: " + cause.getClass().getName()
                            + ": " + (cause.getMessage() == null ? "" : cause.getMessage()),
                    cause);
        } catch (Throwable t) {
            throw new RealEngineContentInitException(
                    className + "." + method.getName() + "() invocation failed: " + t, t);
        }
    }

    private static int mapSizeOrMin(Object maybeMap) {
        return maybeMap instanceof Map ? ((Map<?, ?>) maybeMap).size() : Integer.MIN_VALUE;
    }

    /**
     * No-op dynamic-proxy handler ({@code HeadlessCombatContext.DefaultProxyHandler} idiom):
     * Object methods answered locally, every interface method returns the type's zero value
     * ({@code null} / {@code false} / numeric 0). Publisher: the SoundPlayerAPI stub above.
     */
    private static final class NoOpProxyHandler implements InvocationHandler {
        private final String label;

        private NoOpProxyHandler(String label) {
            this.label = label;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (Object.class.equals(method.getDeclaringClass())) {
                String methodName = method.getName();
                if ("toString".equals(methodName)) {
                    return label;
                }
                if ("hashCode".equals(methodName)) {
                    return Integer.valueOf(System.identityHashCode(proxy));
                }
                if ("equals".equals(methodName)) {
                    return Boolean.valueOf(args != null && args.length == 1 && proxy == args[0]);
                }
                throw new UnsupportedOperationException("Unexpected Object method: " + methodName);
            }
            Class<?> returnType = method.getReturnType();
            if (!returnType.isPrimitive() || returnType == Void.TYPE) {
                return null;
            }
            if (returnType == Boolean.TYPE) {
                return Boolean.FALSE;
            }
            if (returnType == Byte.TYPE) {
                return Byte.valueOf((byte) 0);
            }
            if (returnType == Short.TYPE) {
                return Short.valueOf((short) 0);
            }
            if (returnType == Integer.TYPE) {
                return Integer.valueOf(0);
            }
            if (returnType == Long.TYPE) {
                return Long.valueOf(0L);
            }
            if (returnType == Float.TYPE) {
                return Float.valueOf(0.0f);
            }
            if (returnType == Double.TYPE) {
                return Double.valueOf(0.0d);
            }
            return Character.valueOf('\0');
        }
    }

    /**
     * The unique {@code owner} method matching the given shape (scout {@code uniqueMethod}).
     * Throws if zero or more than one matches — the descriptor-grade discipline that
     * keeps engine-name drift from silently selecting the wrong overload.
     */
    private static Method uniqueMethod(Class<?> owner, boolean requirePublicStatic, Class<?> returnType,
            Class<?>[] parameterTypes, String[] exceptionTypeNames) {
        List<Method> matches = new ArrayList<>();
        for (Method method : owner.getDeclaredMethods()) {
            int modifiers = method.getModifiers();
            if (requirePublicStatic && (!Modifier.isPublic(modifiers) || !Modifier.isStatic(modifiers))) {
                continue;
            }
            if (!method.getReturnType().equals(returnType)) {
                continue;
            }
            if (!Arrays.equals(method.getParameterTypes(), parameterTypes)) {
                continue;
            }
            if (exceptionTypeNames != null && !sameExceptionTypes(method, exceptionTypeNames)) {
                continue;
            }
            matches.add(method);
        }
        if (matches.size() != 1) {
            throw new RealEngineContentInitException(
                    "expected one matching method on " + owner.getName() + " but found " + matches.size());
        }
        return matches.get(0);
    }

    private static boolean sameExceptionTypes(Method method, String[] expectedNames) {
        Class<?>[] actual = method.getExceptionTypes();
        if (actual.length != expectedNames.length) {
            return false;
        }
        List<String> actualNames = new ArrayList<>();
        for (Class<?> type : actual) {
            actualNames.add(type.getName());
        }
        List<String> expected = new ArrayList<>(Arrays.asList(expectedNames));
        Collections.sort(actualNames);
        Collections.sort(expected);
        return actualNames.equals(expected);
    }

    private static String shortSha256Hex(String value) {
        String hash = sha256Hex(value);
        return hash.length() <= 12 ? hash : hash.substring(0, 12);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte one : hash) {
                int intValue = one & 0xff;
                if (intValue < 16) {
                    sb.append('0');
                }
                sb.append(Integer.toHexString(intValue));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RealEngineContentInitException("unable to compute SHA-256", e);
        }
    }

    private static Throwable unwrap(Throwable t) {
        return (t instanceof InvocationTargetException && t.getCause() != null) ? t.getCause() : t;
    }
}
