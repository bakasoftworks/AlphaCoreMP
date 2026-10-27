package cc.starsector.server.runtime;

import cc.starsector.server.bridge.RealEngineCombatBindingFactory;
import cc.starsector.server.platform.AllSkillsAttach;
import cc.starsector.server.platform.RealEngineContentInit;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Custom variant maker — the detached-hullmod applicability-yield CENSUS +
 * the headless {@code getUnusedOP} probe. By design,
 * the server validates a submitted variant with NO
 * ship by handing every hullmod's {@code HullModEffect.isApplicableToShip} a fail-closed
 * {@code java.lang.reflect.Proxy} of {@code ShipAPI} that answers ONLY getVariant / getHullSpec /
 * getHullSize / isFrigate…isCapital / getMutableStats / getShield and THROWS on anything else
 * (that hullmod is then rejected "cannot validate detached"). The yield of that
 * shape was unmeasured — a low yield means most STANDARD-tier customs reject and the proxy must
 * widen (or the check must move to a real ShipAPI in a dry combat build). This main MEASURES it on
 * the real engine, headless: for every hull in {@code --hulls} it mints an empty variant + a
 * detached {@code FleetMember} ({@code SettingsAPI.createFleetMember(SHIP, variant)}, the
 * AllSkillsFleetData idiom) and calls every hullmod spec's effect through the design's exact proxy,
 * recording per (hull, mod): OK (applicable true/false), THROWS_DETACHED (+ the method the effect
 * wanted — the widening list for free), ERROR, NO_EFFECT. The same member answers the unused-OP question:
 * {@code getUnusedOP(null)} vs {@code getUnusedOP(spawnPersonStats)} and
 * {@code Misc.getMaxPermanentMods(member, stats)} (the s-mod cap).
 *
 * <p>Fully reflective (server/src compiles without the API jar; probe-main precedent
 * {@code RealEngineFighterWingProbeMain}). A MEASUREMENT main, never wired into the serve.
 * Exit: 0 = census completed (the yield is the answer, whatever it is); 2 = boot failed.
 *
 */
public final class DetachedHullmodYieldProbeMain {

    private static final String P = "hullmod-yield-probe";
    private static final String GLOBAL_CLASS = "com.fs.starfarer.api.Global";
    private static final String SETTINGS_API = "com.fs.starfarer.api.SettingsAPI";
    private static final String SHIP_API = "com.fs.starfarer.api.combat.ShipAPI";
    private static final String SHIELD_API = "com.fs.starfarer.api.combat.ShieldAPI";
    private static final String SHIELD_TYPE = "com.fs.starfarer.api.combat.ShieldAPI$ShieldType";
    private static final String HULL_SPEC_API = "com.fs.starfarer.api.combat.ShipHullSpecAPI";
    private static final String SHIP_VARIANT_API = "com.fs.starfarer.api.combat.ShipVariantAPI";
    private static final String FLEET_MEMBER_API = "com.fs.starfarer.api.fleet.FleetMemberAPI";
    private static final String FLEET_MEMBER_TYPE = "com.fs.starfarer.api.fleet.FleetMemberType";
    private static final String HULLMOD_SPEC_API = "com.fs.starfarer.api.loading.HullModSpecAPI";
    private static final String HULLMOD_EFFECT = "com.fs.starfarer.api.combat.HullModEffect";
    private static final String MUTABLE_CHAR_STATS_API = "com.fs.starfarer.api.characters.MutableCharacterStatsAPI";
    private static final String MISC = "com.fs.starfarer.api.util.Misc";

    /** The design's answer set, verbatim (isFighter added: same family, same cost). */
    private static final Set<String> ANSWERED = new LinkedHashSet<>(List.of(
            "getVariant", "getHullSpec", "getHullSize", "isFrigate", "isDestroyer", "isCruiser",
            "isCapital", "isFighter", "getMutableStats", "getShield"));

    /** Thrown by the proxy for any un-answered read; carries the method name. */
    static final class DetachedRead extends UnsupportedOperationException {
        private static final long serialVersionUID = 1L;
        final String method;
        DetachedRead(String method) {
            super("detached ShipAPI proxy: " + method);
            this.method = method;
        }
    }

    private DetachedHullmodYieldProbeMain() {
    }

    public static void main(String[] args) throws Exception {
        List<String> hulls = new ArrayList<>(List.of("wolf", "hound", "hammerhead", "drover", "eagle",
                "afflictor", "onslaught"));
        String standardFile = null;
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--hulls": hulls = new ArrayList<>(List.of(args[++i].split(","))); break;
                case "--standard-file": standardFile = args[++i]; break;
                default: break;
            }
        }
        Set<String> standard = new LinkedHashSet<>();
        if (standardFile != null) {
            for (String tok : new String(Files.readAllBytes(Paths.get(standardFile)), StandardCharsets.UTF_8)
                    .split("\\s+")) {
                if (!tok.isEmpty()) {
                    standard.add(tok);
                }
            }
        }
        System.out.println(P + "-begin hulls=" + String.join(",", hulls) + " standard_ids=" + standard.size());

        ClassLoader loader = DetachedHullmodYieldProbeMain.class.getClassLoader();
        Object settings;
        try {
            settings = boot(loader);
        } catch (Throwable t) {
            Throwable c = t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
            System.out.println(P + "-end verdict=BOOT_FAILED error=" + c.getClass().getName()
                    + " message=" + q(String.valueOf(c.getMessage())));
            c.printStackTrace(System.out);
            System.exit(2);
            return;
        }

        Class<?> settingsApi = Class.forName(SETTINGS_API, false, loader);
        Class<?> hullSpecApi = Class.forName(HULL_SPEC_API, false, loader);
        Class<?> variantApi = Class.forName(SHIP_VARIANT_API, false, loader);
        Class<?> memberApi = Class.forName(FLEET_MEMBER_API, false, loader);
        Class<?> memberType = Class.forName(FLEET_MEMBER_TYPE, false, loader);
        Class<?> hullModSpecApi = Class.forName(HULLMOD_SPEC_API, false, loader);
        Class<?> effectApi = Class.forName(HULLMOD_EFFECT, false, loader);
        Class<?> shipApi = Class.forName(SHIP_API, false, loader);
        Class<?> shieldApi = Class.forName(SHIELD_API, false, loader);
        Class<?> shieldType = Class.forName(SHIELD_TYPE, false, loader);
        Class<?> charStatsApi = Class.forName(MUTABLE_CHAR_STATS_API, false, loader);
        Class<?> misc = Class.forName(MISC, false, loader);

        Method getHullSpec = settingsApi.getMethod("getHullSpec", String.class);
        Method createEmptyVariant = settingsApi.getMethod("createEmptyVariant", String.class, hullSpecApi);
        Method createFleetMember = settingsApi.getMethod("createFleetMember", memberType, variantApi);
        Method getAllHullModSpecs = settingsApi.getMethod("getAllHullModSpecs");
        Object shipKind = memberType.getField("SHIP").get(null);
        Method getStats = memberApi.getMethod("getStats");
        Method getCaptain = memberApi.getMethod("getCaptain");
        Method getVariantHullSize = variantApi.getMethod("getHullSize");
        Method getVariantHullSpec = variantApi.getMethod("getHullSpec");
        Method hasHullMod = variantApi.getMethod("hasHullMod", String.class);
        Method getUnusedOP = variantApi.getMethod("getUnusedOP", charStatsApi);
        Method computeOPCost = variantApi.getMethod("computeOPCost", charStatsApi);
        Method getShieldType = hullSpecApi.getMethod("getShieldType");
        Method getOrdnancePoints = hullSpecApi.getMethod("getOrdnancePoints", charStatsApi);
        Method getFighterBays = hullSpecApi.getMethod("getFighterBays");
        Method specId = hullModSpecApi.getMethod("getId");
        Method specEffect = hullModSpecApi.getMethod("getEffect");
        Method specHidden = hullModSpecApi.getMethod("isHidden");
        Method specHiddenEverywhere = hullModSpecApi.getMethod("isHiddenEverywhere");
        Method isApplicable = effectApi.getMethod("isApplicableToShip", shipApi);
        Method unapplicableReason = effectApi.getMethod("getUnapplicableReason", shipApi);
        Method maxPermanentMods = misc.getMethod("getMaxPermanentMods", memberApi, charStatsApi);
        Object shieldNone = Enum.valueOf(shieldType.asSubclass(Enum.class), "NONE");
        Object shieldFront = Enum.valueOf(shieldType.asSubclass(Enum.class), "FRONT");

        @SuppressWarnings("unchecked")
        List<Object> specs = (List<Object>) getAllHullModSpecs.invoke(settings);
        System.out.println(P + " specs count=" + specs.size());

        int pairs = 0, ok = 0, throwsDetached = 0, errors = 0, noEffect = 0;
        int stdPairs = 0, stdOk = 0;
        Map<String, Set<String>> badMethodsByMod = new LinkedHashMap<>();
        Map<String, String> worstByMod = new LinkedHashMap<>();
        Map<String, Integer> badHullsByMod = new LinkedHashMap<>();
        int hullsDone = 0;

        for (String hullId : hulls) {
            Object hullSpec;
            try {
                hullSpec = getHullSpec.invoke(settings, hullId);
            } catch (Throwable t) {
                System.out.println(P + " hull id=" + hullId + " outcome=HULL_MISSING error=" + q(rootMsg(t)));
                continue;
            }
            if (hullSpec == null) {
                System.out.println(P + " hull id=" + hullId + " outcome=HULL_MISSING");
                continue;
            }
            Object variant = createEmptyVariant.invoke(settings, hullId + "_cc_census", hullSpec);
            Object member = createFleetMember.invoke(settings, shipKind, variant);
            Object stats = getStats.invoke(member);
            Object hullSize = getVariantHullSize.invoke(variant);
            Object hullSpecOfVariant = getVariantHullSpec.invoke(variant);
            Object baseShield = getShieldType.invoke(hullSpecOfVariant);
            String sizeName = String.valueOf(hullSize);

            // OP + s-mod cap, null stats vs the spawn person (all-skills attach when the knob is on).
            Object personStats = null;
            String personNote = "na";
            try {
                if (AllSkillsAttach.enabled()) {
                    AllSkillsAttach.attachAllSkillsCaptain(member, hullId + "_cc_census");
                    Object captain = getCaptain.invoke(member);
                    personStats = captain == null ? null
                            : captain.getClass().getMethod("getStats").invoke(captain);
                    personNote = personStats == null ? "captain_null" : "all_skills";
                } else {
                    personNote = "knob_off";
                }
            } catch (Throwable t) {
                personNote = "attach_failed:" + rootMsg(t).replace(' ', '_');
            }
            String opRow = P + " hull id=" + hullId + " size=" + sizeName + " shield=" + baseShield
                    + " bays=" + getFighterBays.invoke(hullSpecOfVariant)
                    + " op_base_null=" + getOrdnancePoints.invoke(hullSpecOfVariant, (Object) null)
                    + " op_cost_null=" + computeOPCost.invoke(variant, (Object) null)
                    + " unused_op_null=" + getUnusedOP.invoke(variant, (Object) null)
                    + " smod_cap_null=" + callOr(maxPermanentMods, null, member, null)
                    + " person=" + personNote;
            if (personStats != null) {
                opRow += " op_base_person=" + getOrdnancePoints.invoke(hullSpecOfVariant, personStats)
                        + " unused_op_person=" + getUnusedOP.invoke(variant, personStats)
                        + " smod_cap_person=" + callOr(maxPermanentMods, null, member, personStats);
            }
            // Best of the Best is a FLEETWIDE (commander) skill — the +1 rides the
            // fleet commander's stats, which attachAllSkillsCaptain also installs on the member.
            // Misc.getMaxPermanentMods swaps in a fake commander carrying the
            // stats it is handed, so the CAPTAIN's stats (combat-officer set) can never show it.
            try {
                Object commander = memberApi.getMethod("getFleetCommanderForStats").invoke(member);
                Object commanderStats = commander == null ? null
                        : commander.getClass().getMethod("getStats").invoke(commander);
                opRow += commanderStats == null ? " commander=none"
                        : " commander=set smod_cap_commander=" + callOr(maxPermanentMods, null, member, commanderStats)
                        + " op_base_commander=" + getOrdnancePoints.invoke(hullSpecOfVariant, commanderStats)
                        + " unused_op_commander=" + getUnusedOP.invoke(variant, commanderStats);
            } catch (Throwable t) {
                opRow += " commander=ERR:" + rootMsg(t).replace(' ', '_');
            }
            System.out.println(opRow);
            hullsDone++;

            // The design's proxy, verbatim shape.
            final Object fVariant = variant, fHullSpec = hullSpecOfVariant, fHullSize = hullSize, fStats = stats;
            final boolean shieldPresent = (!baseShield.equals(shieldNone)
                    && !(Boolean) hasHullMod.invoke(variant, "shield_shunt"))
                    || (Boolean) hasHullMod.invoke(variant, "frontshield");
            final Object shieldKind = (Boolean) hasHullMod.invoke(variant, "frontshield") ? shieldFront : baseShield;
            final Object shieldProxy = Proxy.newProxyInstance(loader, new Class<?>[]{shieldApi},
                    (proxy, method, margs) -> {
                        switch (method.getName()) {
                            case "getType": return shieldKind;
                            case "hashCode": return System.identityHashCode(proxy);
                            case "equals": return proxy == margs[0];
                            case "toString": return "DetachedShieldProxy";
                            default: throw new DetachedRead("ShieldAPI." + method.getName());
                        }
                    });
            InvocationHandler handler = (proxy, method, margs) -> {
                String n = method.getName();
                switch (n) {
                    case "getVariant": return fVariant;
                    case "getHullSpec": return fHullSpec;
                    case "getHullSize": return fHullSize;
                    case "isFrigate": return "FRIGATE".equals(sizeName);
                    case "isDestroyer": return "DESTROYER".equals(sizeName);
                    case "isCruiser": return "CRUISER".equals(sizeName);
                    case "isCapital": return "CAPITAL_SHIP".equals(sizeName);
                    case "isFighter": return "FIGHTER".equals(sizeName);
                    case "getMutableStats": return fStats;
                    case "getShield": return shieldPresent ? shieldProxy : null;
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == margs[0];
                    case "toString": return "DetachedShipProxy(" + hullId + ")";
                    default: throw new DetachedRead(n);
                }
            };
            Object shipProxy = Proxy.newProxyInstance(loader, new Class<?>[]{shipApi}, handler);

            for (Object spec : specs) {
                String id = String.valueOf(specId.invoke(spec));
                boolean std = standard.contains(id);
                String tier = std ? "standard" : "other";
                String hidden = ((Boolean) specHiddenEverywhere.invoke(spec)) ? "everywhere"
                        : ((Boolean) specHidden.invoke(spec)) ? "hidden" : "visible";
                Object effect = specEffect.invoke(spec);
                pairs++;
                if (std) {
                    stdPairs++;
                }
                String outcome, applicable = "na", methodName = "-", reasonOutcome = "na", reasonMethod = "-", reason = "";
                if (effect == null) {
                    outcome = "NO_EFFECT";
                    noEffect++;
                } else {
                    try {
                        boolean a = (Boolean) isApplicable.invoke(effect, shipProxy);
                        outcome = "OK";
                        applicable = String.valueOf(a);
                        ok++;
                        if (std) {
                            stdOk++;
                        }
                        if (!a) {
                            try {
                                reason = String.valueOf(unapplicableReason.invoke(effect, shipProxy));
                                reasonOutcome = "OK";
                            } catch (Throwable t) {
                                Throwable c = root(t);
                                reasonOutcome = c instanceof DetachedRead ? "THROWS_DETACHED" : "ERROR";
                                reasonMethod = c instanceof DetachedRead ? ((DetachedRead) c).method
                                        : c.getClass().getName();
                            }
                        }
                    } catch (Throwable t) {
                        Throwable c = root(t);
                        if (c instanceof DetachedRead) {
                            outcome = "THROWS_DETACHED";
                            methodName = ((DetachedRead) c).method;
                            throwsDetached++;
                        } else {
                            outcome = "ERROR";
                            methodName = c.getClass().getName() + ":" + String.valueOf(c.getMessage()).replace(' ', '_');
                            errors++;
                        }
                    }
                }
                if (!"OK".equals(outcome) || !"na".equals(reasonOutcome) && !"OK".equals(reasonOutcome)) {
                    String key = id;
                    String bad = "OK".equals(outcome) ? "REASON_" + reasonOutcome : outcome;
                    worstByMod.merge(key, bad, (a, b) -> a.compareTo(b) <= 0 ? a : b);
                    badHullsByMod.merge(key, 1, Integer::sum);
                    String m = "OK".equals(outcome) ? reasonMethod : methodName;
                    badMethodsByMod.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(m);
                }
                System.out.println(P + " row hull=" + hullId + " mod=" + id + " tier=" + tier + " hidden=" + hidden
                        + " outcome=" + outcome + " applicable=" + applicable + " method=" + methodName
                        + " reason_outcome=" + reasonOutcome + " reason_method=" + reasonMethod
                        + " reason=" + q(reason));
            }
        }

        int badMods = 0, badStdMods = 0;
        for (Map.Entry<String, String> e : worstByMod.entrySet()) {
            badMods++;
            boolean std = standard.contains(e.getKey());
            if (std) {
                badStdMods++;
            }
            System.out.println(P + " mod id=" + e.getKey() + " tier=" + (std ? "standard" : "other")
                    + " worst=" + e.getValue() + " hulls_bad=" + badHullsByMod.get(e.getKey()) + "/" + hullsDone
                    + " methods=" + String.join("|", badMethodsByMod.get(e.getKey())));
        }
        String yield = stdPairs == 0 ? "na" : String.format(Locale.ROOT, "%.1f", 100.0 * stdOk / stdPairs);
        System.out.println(P + "-end verdict=CENSUS_DONE hulls=" + hullsDone + " mods=" + specs.size()
                + " standard_mods=" + standard.size() + " pairs=" + pairs + " ok=" + ok
                + " throws=" + throwsDetached + " error=" + errors + " no_effect=" + noEffect
                + " standard_pairs=" + stdPairs + " standard_ok=" + stdOk + " standard_yield_pct=" + yield
                + " bad_mods=" + badMods + " bad_standard_mods=" + badStdMods);
        System.out.flush();
        System.exit(0);
    }

    /**
     * The proven product boot (GL substrate → content init → engine singleton), exactly the wing
     * probe's: {@code RealEngineContentInit.ensureInitialized()} alone refuses without a current GL
     * context (texture upload is thread-bound), so the factory owns the ordering.
     */
    private static Object boot(ClassLoader loader) throws Exception {
        Method getSettings = Class.forName(GLOBAL_CLASS, true, loader).getMethod("getSettings");
        RealEngineCombatBindingFactory.create();
        Object settings = getSettings.invoke(null);
        System.out.println(P + " boot stage=combat_binding settings=" + (settings != null)
                + " content_init=" + RealEngineContentInit.initialized());
        if (settings == null) {
            throw new IllegalStateException("Global.getSettings() null after boot");
        }
        return settings;
    }

    private static String callOr(Method m, Object target, Object... a) {
        try {
            return String.valueOf(m.invoke(target, a));
        } catch (Throwable t) {
            return "ERR:" + rootMsg(t).replace(' ', '_');
        }
    }

    private static Throwable root(Throwable t) {
        Throwable c = t;
        while (c instanceof InvocationTargetException && c.getCause() != null) {
            c = c.getCause();
        }
        return c;
    }

    private static String rootMsg(Throwable t) {
        Throwable c = root(t);
        return c.getClass().getName() + ":" + c.getMessage();
    }

    /** Single-line quoted field: printable ASCII only, quotes folded — never parsed, only read. */
    private static String q(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(c == '"' ? '\'' : (c < 0x20 || c > 0x7e) ? '?' : c);
        }
        return b.append('"').toString();
    }
}
