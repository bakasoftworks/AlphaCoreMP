package cc.starsector.server.bridge;

import cc.starsector.server.runtime.ModDataWalk;
import cc.starsector.server.runtime.ModSet;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * The SERVER's enumerator for {@link ModSet} — the launcher {@code ModManager}'s enabled
 * list read reflectively (the {@link RealEngineVariantFacts#enabledModDataRoots} /
 * {@code RealEngineContentInit.registerEnabledModJarsIntoScriptStore} idiom: {@code getInstance()},
 * {@code getEnabledMods()}, then {@code getId}/{@code getVersion}/{@code getPath}/{@code getJars}
 * on each spec), jar bytes via nio (server-only class — the client twin is
 * {@code ClientModSetBoot} on the {@code ModManagerAPI} + the FileReader lane; both feed
 * {@link ModSet#of}, the ONE recipe). The engine's {@code ModSpec.getVersion()} is
 * {@code versionInfo.getString()} on both sides, so a declared version compares equal by
 * construction. THROWS on any fault (missing jar, unlawful id, reflection miss) — the caller
 * ({@code PilotIntentCombatServerMain}) prints the posture.
 */
public final class RealEngineModSetFacts {

    private static final String MOD_MANAGER_CLASS = "com.fs.starfarer.launcher.ModManager";

    private RealEngineModSetFacts() {
    }

    /** Mint the enabled set (every mod but {@code cc_client}) from the running launcher state. */
    public static ModSet snapshot() {
        try {
            ClassLoader loader = RealEngineModSetFacts.class.getClassLoader();
            Class<?> modManagerClass = Class.forName(MOD_MANAGER_CLASS, true, loader);
            Object modManager = modManagerClass.getMethod("getInstance").invoke(null);
            List<?> enabledMods = (List<?>) modManagerClass.getMethod("getEnabledMods").invoke(modManager);
            List<ModSet.Record> records = new ArrayList<ModSet.Record>();
            for (Object spec : enabledMods) {
                String id = (String) method(spec, "getId").invoke(spec);
                if (id == null || ModSet.EXCLUDED_ID.equals(id)) {
                    continue;
                }
                records.add(record(spec, id));
            }
            return ModSet.of(records);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("mod-set: launcher ModManager unreachable: " + unwrap(e), unwrap(e));
        } catch (IOException e) {
            throw new IllegalStateException("mod-set: a declared mod jar is unreadable: " + e.getMessage(), e);
        }
    }

    /**
     * Mint the OPTIONAL set — the {@code mods.optional} ids as records of the launcher's
     * AVAILABLE (scanned, NOT enabled) list: the serve wrapper mirrors each optional mod's folder beside
     * the enabled ones and leaves it out of {@code enabled_mods.json}, so the engine parses its
     * {@code mod_info.json} ({@code ModManager.updateList}, the same {@code VersionInfo} recipe) without
     * loading a byte of it. Same record recipe as {@link #snapshot()}. THROWS when an id is not
     * available (the wrapper did not mirror it) or is ENABLED too (required-or-optional law).
     */
    public static ModSet optionalSnapshot(List<String> optionalIds) {
        try {
            ClassLoader loader = RealEngineModSetFacts.class.getClassLoader();
            Class<?> modManagerClass = Class.forName(MOD_MANAGER_CLASS, true, loader);
            Object modManager = modManagerClass.getMethod("getInstance").invoke(null);
            List<?> available = (List<?>) modManagerClass.getMethod("getAvailableMods").invoke(modManager);
            List<?> enabled = (List<?>) modManagerClass.getMethod("getEnabledMods").invoke(modManager);
            List<ModSet.Record> records = new ArrayList<ModSet.Record>();
            for (String wanted : optionalIds) {
                Object found = null;
                for (Object spec : available) {
                    if (wanted.equals(method(spec, "getId").invoke(spec))) {
                        found = spec;
                        break;
                    }
                }
                if (found == null) {
                    throw new IllegalStateException("mod-set: optional mod '" + wanted + "' is not under the serve's"
                            + " mods root — the wrapper mirrors every mods.optional id beside the enabled ones"
                            + " (serve-mods-lib.ps1); fetch it under a mods.roots root first");
                }
                for (Object spec : enabled) {
                    if (wanted.equals(method(spec, "getId").invoke(spec))) {
                        throw new IllegalStateException("mod-set: optional mod '" + wanted + "' is also ENABLED —"
                                + " a mod is required (mods.enabled) or optional (mods.optional), never both");
                    }
                }
                records.add(record(found, wanted));
            }
            return ModSet.of(records);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("mod-set: launcher ModManager unreachable: " + unwrap(e), unwrap(e));
        } catch (IOException e) {
            throw new IllegalStateException("mod-set: an optional mod's declared jar is unreadable: " + e.getMessage(), e);
        }
    }

    /** One launcher spec → its record (declared version, md5 over the declared jars in order, md5 over
     * {@code data/**} — the same {@code ModDataWalk} class the in-game client runs). */
    private static ModSet.Record record(Object spec, String id) throws ReflectiveOperationException, IOException {
        String version = (String) method(spec, "getVersion").invoke(spec);
        String modPath = (String) method(spec, "getPath").invoke(spec);
        List<?> jars = (List<?>) method(spec, "getJars").invoke(spec);
        List<byte[]> jarBytes = new ArrayList<byte[]>();
        if (jars != null) {
            for (Object jar : jars) {
                if (jar == null) {
                    continue;
                }
                String rel = jar.toString();
                // exact launcher composition (forward slash), absolute jar paths honoured
                String full = Paths.get(rel).isAbsolute() ? rel : modPath + "/" + rel;
                jarBytes.add(Files.readAllBytes(Paths.get(full)));
            }
        }
        return ModSet.Record.of(id, version, ModSet.jarsMd5(jarBytes), ModDataWalk.dataMd5(modPath));
    }

    private static Method method(Object target, String name) throws NoSuchMethodException {
        return target.getClass().getMethod(name);
    }

    private static Throwable unwrap(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c
                && (c instanceof java.lang.reflect.InvocationTargetException || c instanceof IllegalStateException)) {
            c = c.getCause();
        }
        return c;
    }
}
