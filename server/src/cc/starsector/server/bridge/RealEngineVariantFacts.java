package cc.starsector.server.bridge;

import cc.starsector.server.runtime.VariantPolicy;
import cc.starsector.server.runtime.VariantPolicy.HullFacts;
import cc.starsector.server.runtime.VariantPolicy.HullModFacts;
import cc.starsector.server.runtime.VariantPolicy.WeaponFacts;
import cc.starsector.server.runtime.VariantPolicy.WingFacts;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

/**
 * W15 v0: the ONE boot-time
 * snapshot of engine spec FACTS the pure {@link VariantPolicy} grades — every hull / weapon /
 * hullmod / wing the running engine knows, read reflectively off {@code Global.getSettings()}
 * (the {@code SettingsAPI} enumerators).
 * Runs on the SIM thread at door
 * wiring, after content init — never per tick.
 *
 * <p>Mod provenance: skins are CLONES of their
 * vanilla base hull and carry no source-mod pointer, so the mod-defined hull set comes from DISK —
 * every enabled mod's {@code data/hulls/skins/*.skin} {@code skinHullId} (the engine's own key,
 * parsed with the engine's lenient org.json) — while spreadsheet content (weapons, wings, base
 * hulls, hullmods) is graded by {@code getSourceMod() != null}. Security: the skin
 * files are owner/mod-shipped local disk, size-guarded per file, never wire.
 */
public final class RealEngineVariantFacts {

    private static final String GLOBAL_CLASS = "com.fs.starfarer.api.Global";
    private static final String MOD_MANAGER_CLASS = "com.fs.starfarer.launcher.ModManager";
    /** Per-skin-file read guard (a .skin is ~1–3 KB). */
    static final int MAX_SKIN_FILE_BYTES = 262144;

    private RealEngineVariantFacts() {
    }

    /** The live {@code SettingsAPI} (content init already ran with the binding); THROWS when absent. */
    public static Object settings() {
        try {
            ClassLoader loader = RealEngineVariantFacts.class.getClassLoader();
            Object settings = Class.forName(GLOBAL_CLASS, true, loader).getMethod("getSettings").invoke(null);
            if (settings == null) {
                throw new IllegalStateException("Global.getSettings() is null — content init has not run");
            }
            return settings;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Global.getSettings() unreachable: " + unwrap(e), unwrap(e));
        }
    }

    /** Snapshot every spec family into a {@link VariantPolicy.Catalog}; THROWS on a reflective fault. */
    public static VariantPolicy.Catalog snapshot() {
        Object settings = settings();
        Set<String> modSkinIds = enabledModSkinHullIds();
        try {
            return new VariantPolicy.Catalog(hulls(settings, modSkinIds), weapons(settings),
                    hullMods(settings), wings(settings));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("variant facts snapshot failed: " + unwrap(e), unwrap(e));
        }
    }

    private static Map<String, HullFacts> hulls(Object settings, Set<String> modSkinIds)
            throws ReflectiveOperationException {
        Map<String, HullFacts> out = new LinkedHashMap<>();
        Collection<?> specs = (Collection<?>) call(settings, "getAllShipHullSpecs");
        for (Object spec : specs) {
            String id = (String) call(spec, "getHullId");
            if (id == null || id.isEmpty()) {
                continue;
            }
            Object size = call(spec, "getHullSize");
            Set<String> hints = enumNames((Collection<?>) call(spec, "getHints"));
            Set<String> tags = strings((Collection<?>) call(spec, "getTags"));
            boolean defaultD = (Boolean) call(spec, "isDefaultDHull");
            boolean anchor = call(spec, "getModuleAnchor") != null;
            boolean stationSlot = false;
            Set<String> fittable = new LinkedHashSet<>();
            // EVERY slot in definition order = the per-hull u16
            // slot index domain both trees share (the client mirror walks the same copy).
            List<String> slotOrder = new java.util.ArrayList<>();
            Collection<?> slots = (Collection<?>) call(spec, "getAllWeaponSlotsCopy");
            for (Object slot : slots) {
                String slotId = (String) call(slot, "getId");
                if (slotId != null && !slotId.isEmpty()) {
                    slotOrder.add(slotId);
                }
                if ((Boolean) call(slot, "isStationModule")) {
                    stationSlot = true;
                    continue;
                }
                Object slotType = call(slot, "getWeaponType");
                boolean fittableSlot = !(Boolean) call(slot, "isBuiltIn")
                        && !(Boolean) call(slot, "isDecorative")
                        && !(Boolean) call(slot, "isSystemSlot")
                        && !(Boolean) call(slot, "isHidden")
                        && (Boolean) call(slot, "isWeaponSlot")
                        && !"LAUNCH_BAY".equals(String.valueOf(slotType));
                if (fittableSlot) {
                    fittable.add((String) call(slot, "getId"));
                }
            }
            Set<String> builtInMods = strings((Collection<?>) call(spec, "getBuiltInMods"));
            // The hull's built-in weapons (slot id → weapon id) — the policy's exact-pairing law
            Map<String, String> builtInWeapons = new LinkedHashMap<>();
            Object biw = call(spec, "getBuiltInWeapons");
            if (biw instanceof Map<?, ?> biwMap) {
                for (Map.Entry<?, ?> e : biwMap.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        builtInWeapons.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                    }
                }
            }
            boolean modDefined = modSkinIds.contains(id) || sourceModPresent(spec);
            out.put(id, new HullFacts(id, String.valueOf(size), hints, tags, defaultD, anchor,
                    stationSlot, builtInMods, fittable, modDefined, builtInWeapons, slotOrder));
        }
        return out;
    }

    private static Map<String, WeaponFacts> weapons(Object settings) throws ReflectiveOperationException {
        Map<String, WeaponFacts> out = new LinkedHashMap<>();
        Collection<?> specs = (Collection<?>) call(settings, "getAllWeaponSpecs");
        for (Object spec : specs) {
            String id = (String) call(spec, "getWeaponId");
            if (id == null || id.isEmpty()) {
                continue;
            }
            Set<String> hints = enumNames((Collection<?>) call(spec, "getAIHints"));
            out.put(id, new WeaponFacts(id, String.valueOf(call(spec, "getType")),
                    strings((Collection<?>) call(spec, "getTags")), hints.contains("SYSTEM"),
                    sourceModPresent(spec)));
        }
        return out;
    }

    private static Map<String, HullModFacts> hullMods(Object settings) throws ReflectiveOperationException {
        Map<String, HullModFacts> out = new LinkedHashMap<>();
        Collection<?> specs = (Collection<?>) call(settings, "getAllHullModSpecs");
        for (Object spec : specs) {
            String id = (String) call(spec, "getId");
            if (id == null || id.isEmpty()) {
                continue;
            }
            out.put(id, new HullModFacts(id, (Boolean) call(spec, "isHidden"),
                    (Boolean) call(spec, "isHiddenEverywhere"),
                    strings((Collection<?>) call(spec, "getTags")),
                    (Boolean) call(spec, "isAlwaysUnlocked"), sourceModPresent(spec)));
        }
        return out;
    }

    private static Map<String, WingFacts> wings(Object settings) throws ReflectiveOperationException {
        Map<String, WingFacts> out = new LinkedHashMap<>();
        Collection<?> specs = (Collection<?>) call(settings, "getAllFighterWingSpecs");
        for (Object spec : specs) {
            String id = (String) call(spec, "getId");
            if (id == null || id.isEmpty()) {
                continue;
            }
            String variantId = (String) call(spec, "getVariantId");
            boolean hasVariant = variantId != null && !variantId.isBlank();
            Set<String> fighterHints = Set.of();
            if (hasVariant) {
                try {
                    Object variant = method(settings, "getVariant", String.class)
                            .invoke(settings, variantId);
                    Object hullSpec = variant == null ? null : call(variant, "getHullSpec");
                    if (hullSpec != null) {
                        fighterHints = enumNames((Collection<?>) call(hullSpec, "getHints"));
                    }
                } catch (Throwable unresolved) {
                    // A wing whose fighter variant does not resolve keeps empty hints (fail-soft: the
                    // registrar's engine pass refuses it later if it is ever fitted).
                    fighterHints = Set.of();
                }
            }
            out.put(id, new WingFacts(id, strings((Collection<?>) call(spec, "getTags")), hasVariant,
                    fighterHints, sourceModPresent(spec)));
        }
        return out;
    }

    /**
     * Every enabled mod's {@code data/hulls/skins/*.skin} {@code skinHullId} — the disk-walk
     * twin of {@code RealEngineDemoRoster.enabledModVariantRoots()}. Fail-soft to EMPTY with a
     * loud line (then every skin grades as vanilla-tagged, i.e. by its base hull's tier — the
     * override file's {@code deny} remains the owner's belt).
     */
    public static Set<String> enabledModSkinHullIds() {
        Set<String> ids = new LinkedHashSet<>();
        List<File> roots;
        try {
            roots = enabledModDataRoots("data/hulls/skins");
        } catch (Throwable t) {
            System.out.println("custom-variant mod_skin_roots_failed " + unwrap(t).getClass().getName()
                    + ": " + unwrap(t).getMessage());
            return Collections.emptySet();
        }
        for (File root : roots) {
            File[] files = root.listFiles();
            if (files == null) {
                continue;
            }
            for (File file : files) {
                if (!file.isFile() || !file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".skin")) {
                    continue;
                }
                try {
                    if (file.length() > MAX_SKIN_FILE_BYTES) {
                        throw new IllegalArgumentException("skin exceeds " + MAX_SKIN_FILE_BYTES + " B");
                    }
                    String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                    // The engine's JSON loader accepts `#` line comments (the
                    // mod's mudskipper2_cc.skin opens with four) — this org.json does not.
                    String skinHullId = new JSONObject(stripHashComments(text)).optString("skinHullId", "").trim();
                    if (!skinHullId.isEmpty()) {
                        ids.add(skinHullId);
                    }
                } catch (Exception bad) {
                    System.out.println("custom-variant mod_skin_skip file=" + file.getName()
                            + " error=" + bad.getClass().getSimpleName() + ": " + bad.getMessage());
                }
            }
        }
        System.out.println("custom-variant mod_defined_hulls roots=" + roots.size() + " skins=" + ids.size());
        return ids;
    }

    /** Enabled mods' {@code <modPath>/<relative>} dirs that exist (launcher declaration order). */
    static List<File> enabledModDataRoots(String relative) throws ReflectiveOperationException {
        List<File> roots = new java.util.ArrayList<>();
        ClassLoader loader = RealEngineVariantFacts.class.getClassLoader();
        Class<?> modManagerClass = Class.forName(MOD_MANAGER_CLASS, true, loader);
        Object modManager = modManagerClass.getMethod("getInstance").invoke(null);
        List<?> enabledMods = (List<?>) modManagerClass.getMethod("getEnabledMods").invoke(modManager);
        for (Object spec : enabledMods) {
            String modPath = (String) method(spec, "getPath").invoke(spec);
            File root = new File(modPath, relative);
            if (root.isDirectory()) {
                roots.add(root);
            }
        }
        return roots;
    }

    private static boolean sourceModPresent(Object spec) {
        try {
            Method m = method(spec, "getSourceMod");
            return m.invoke(spec) != null;
        } catch (Throwable absent) {
            return false;
        }
    }

    static Object call(Object target, String method) throws ReflectiveOperationException {
        return method(target, method).invoke(target);
    }

    /**
     * Resolve a public method on {@code target}'s runtime class and return it ON A PUBLIC DECLARING
     * TYPE so {@link Method#invoke} passes the access check. The engine's {@code Global.getSettings()}
     * is an anonymous {@code StarfarerSettings$1} (package-private class, public methods): a
     * {@code Method} taken off THAT class throws {@link IllegalAccessException} at invoke — the
     * first-live-run boot fault — while the same method taken off
     * the public {@code SettingsAPI} interface invokes fine (the {@code AllSkillsFleetData} /
     * {@code DetachedHullmodYieldProbeMain} idiom). Walks superclasses + interfaces breadth-first
     * for the first PUBLIC type whose own {@code getMethod} lands on a public declaring class;
     * falls back to {@code setAccessible(true)} (classpath = the unnamed module, no module wall).
     */
    static Method method(Object target, String name, Class<?>... params) throws NoSuchMethodException {
        Class<?> runtime = target.getClass();
        Method m = runtime.getMethod(name, params);
        if (Modifier.isPublic(m.getDeclaringClass().getModifiers())) {
            return m;
        }
        java.util.ArrayDeque<Class<?>> queue = new java.util.ArrayDeque<>();
        Set<Class<?>> seen = new java.util.HashSet<>();
        queue.add(runtime);
        while (!queue.isEmpty()) {
            Class<?> type = queue.poll();
            if (!seen.add(type)) {
                continue;
            }
            if (type != runtime && Modifier.isPublic(type.getModifiers())) {
                try {
                    Method onType = type.getMethod(name, params);
                    if (Modifier.isPublic(onType.getDeclaringClass().getModifiers())) {
                        return onType;
                    }
                } catch (NoSuchMethodException notDeclaredHere) {
                    // keep walking
                }
            }
            if (type.getSuperclass() != null) {
                queue.add(type.getSuperclass());
            }
            queue.addAll(java.util.Arrays.asList(type.getInterfaces()));
        }
        m.setAccessible(true);
        return m;
    }

    /** Drop {@code #} line comments outside JSON strings (the engine loader's grammar; org.json refuses them). */
    static String stripHashComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
                continue;
            }
            if (c == '#') {
                while (i + 1 < text.length() && text.charAt(i + 1) != '\n') {
                    i++;
                }
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static Set<String> enumNames(Collection<?> values) {
        Set<String> out = new LinkedHashSet<>();
        if (values != null) {
            for (Object v : values) {
                out.add(v instanceof Enum ? ((Enum<?>) v).name() : String.valueOf(v));
            }
        }
        return out;
    }

    private static Set<String> strings(Collection<?> values) {
        Set<String> out = new LinkedHashSet<>();
        if (values != null) {
            for (Object v : values) {
                if (v != null) {
                    out.add(String.valueOf(v));
                }
            }
        }
        return out;
    }

    static Throwable unwrap(Throwable t) {
        Throwable c = t;
        while (c instanceof InvocationTargetException && c.getCause() != null) {
            c = c.getCause();
        }
        return c;
    }
}
