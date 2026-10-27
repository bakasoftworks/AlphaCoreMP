package cc.starsector.server.bridge;

import cc.starsector.server.platform.AllSkillsAttach;
import cc.starsector.server.platform.RegistryVariantClone;
import cc.starsector.server.runtime.CustomVariantConfig;
import cc.starsector.server.runtime.CustomVariantLane;
import cc.starsector.server.runtime.CustomVariantLane.Verdict;
import cc.starsector.server.runtime.DoorRejectReasons;
import cc.starsector.server.runtime.VariantDefinitionFrameCodec.ModuleMount;
import cc.starsector.server.runtime.VariantDefinitionFrameCodec.VariantDefinition;
import cc.starsector.server.runtime.VariantDefinitionFrameCodec.WeaponGroup;
import cc.starsector.server.runtime.VariantDefinitionFrameCodec.WeaponMount;
import cc.starsector.server.runtime.VariantPolicy;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 *
 * The variant maker's ENGINE half: the sim-thread registrar for ONE server-minted custom
 * variant. Reflective twin of the client fold ({@code VariantDefinitionLane.foldIntoRegistry}) —
 * wire-order JSON → {@code new HullVariantSpec(JSONObject)} → the engine pass on a throwaway
 * {@code FleetMember} built with the MATCH's spawn person → the registry put {@code (clone, overwrite)}
 * (overwrite=false for a mint: a collision is a fail-loud throw, never a silent replace;
 * overwrite=true ONLY for the catalog lane's live UPDATE of an id this serve registered —
 * a plain map put; a ship already flying keeps
 * the spec instance it was built from, the next {@code createFleetMember} resolves the new one) →
 * post-put resolvability assert. The registry's reflective class + method names
 * are the SAME strings {@link RealEngineDemoRoster} pins; they are named here a
 * second time only because that class keeps them private.
 *
 * <p>Engine pass, in the design's check order (the pure half already ran on the reader thread):
 * #3 the ctor resolves hull/weapons (a throw = {@code custom_engine_rejected}); #5 every fitted
 * slot exists on the hull, is a player slot, and {@code weaponFits}; #4 vents/caps ≤ the refit
 * table 5/10/20/30/50 by size + the spawn person's {@code getMaxVentsBonus/getMaxCapacitorsBonus}
 * (transcribed from the refit screen — the ctor caps NOTHING); #6 built-in +
 * submitted wings ≤ {@code stats.getNumFighterBays()} (hullmod bays included); #8/#9/#10 each
 * declared hullmod's {@code isApplicableToShip} over the throwing proxy (903/903 yield —
 * a detached read = REFUSED "cannot validate detached", fail-closed); #11
 * {@code Misc.getCurrSpecialMods ≤ cap} (cap = engine with the spawn person's stats, or the knob);
 * #14 {@code getUnusedOP(spawnStats) ≥ 0}; #15 register a CLONE (the throwaway member keeps its
 * own spec; the registry singleton is never handed to an engine object).
 *
 * <p>Single-writer law: the {@code B} map is an unsynchronized static HashMap read on the sim
 * thread — {@link #register} MUST run on the serve thread (the caller's drain band); the boot
 * keyset snapshot ({@code RealEngineDemoRoster.variantRegistryIds}) shares that thread.
 * Persistence: the accepted JSON is written to
 * {@code <persistDir>/<serveStamp>/<canonical>.variant} best-effort — evidence, not catalog.
 */
public final class RealEngineVariantRegistrar implements CustomVariantLane.Registrar {

    private static final String HULL_VARIANT_SPEC_CLASS = "com.fs.starfarer.loading.specs.HullVariantSpec";
    private static final String VARIANT_REGISTRY_CLASS = "com.fs.starfarer.loading.B";
    private static final String VARIANT_REGISTRY_PUT = "o00000";
    private static final String FLEET_MEMBER_TYPE = "com.fs.starfarer.api.fleet.FleetMemberType";
    private static final String SHIP_API = "com.fs.starfarer.api.combat.ShipAPI";
    private static final String SHIELD_API = "com.fs.starfarer.api.combat.ShieldAPI";
    private static final String MISC = "com.fs.starfarer.api.util.Misc";
    private static final String FLEET_MEMBER_API = "com.fs.starfarer.api.fleet.FleetMemberAPI";
    private static final String MUTABLE_CHAR_STATS_API = "com.fs.starfarer.api.characters.MutableCharacterStatsAPI";
    private static final String SHIP_VARIANT_API = "com.fs.starfarer.api.combat.ShipVariantAPI";
    private static final String HULLMOD_EFFECT = "com.fs.starfarer.api.combat.HullModEffect";

    /** Check #4: the refit screen's per-size base for BOTH vents and capacitors. */
    public static int baseVentOrCapMax(String hullSizeName) {
        switch (hullSizeName) {
            case "FIGHTER": return 5;
            case "FRIGATE": return 10;
            case "DESTROYER": return 20;
            case "CRUISER": return 30;
            case "CAPITAL_SHIP": return 50;
            default: return 100;
        }
    }

    /** Thrown by the detached proxy for any un-answered read; carries the method name. */
    static final class DetachedRead extends UnsupportedOperationException {
        private static final long serialVersionUID = 1L;
        final String method;

        DetachedRead(String method) {
            super("detached ShipAPI proxy: " + method);
            this.method = method;
        }
    }

    private final CustomVariantConfig config;
    private final File persistDirOrNull;
    private int registered;

    public RealEngineVariantRegistrar(CustomVariantConfig config, String serveStamp) {
        this.config = Objects.requireNonNull(config, "config");
        File dir = null;
        if (config.persistDir() != null && !config.persistDir().isBlank()) {
            dir = new File(config.persistDir(), serveStamp == null ? "serve" : serveStamp);
        }
        this.persistDirOrNull = dir;
    }

    @Override
    public Verdict register(VariantDefinition canonical) {
        return register(canonical, false);
    }

    /** {@code overwrite} = the catalog lane's live UPDATE (the caller proved the id is this serve's
     * own registration, never a boot id); the pass itself is identical. */
    @Override
    public Verdict register(VariantDefinition canonical, boolean overwrite) {
        Objects.requireNonNull(canonical, "canonical");
        if (canonical.variantId().startsWith(VariantPolicy.WENDIGO_PREFIX)) {
            return Verdict.rejected(DoorRejectReasons.CUSTOM_ENGINE_REJECTED, "wendigo prefix (B rewrite law)");
        }
        ClassLoader loader = RealEngineVariantRegistrar.class.getClassLoader();
        Object settings = RealEngineVariantFacts.settings();
        try {
            Class<?> hullVariantClass = Class.forName(HULL_VARIANT_SPEC_CLASS, true, loader);
            Class<?> variantApi = Class.forName(SHIP_VARIANT_API, false, loader);
            Class<?> memberApi = Class.forName(FLEET_MEMBER_API, false, loader);
            Class<?> charStatsApi = Class.forName(MUTABLE_CHAR_STATS_API, false, loader);
            Class<?> memberType = Class.forName(FLEET_MEMBER_TYPE, false, loader);
            Class<?> shipApi = Class.forName(SHIP_API, false, loader);
            Class<?> shieldApi = Class.forName(SHIELD_API, false, loader);
            Class<?> effectApi = Class.forName(HULLMOD_EFFECT, false, loader);
            Class<?> misc = Class.forName(MISC, false, loader);

            // #3: the ctor resolves hull + weapons (throws on an unknown id).
            Object variant;
            try {
                variant = hullVariantClass.getConstructor(JSONObject.class).newInstance(toEngineJson(canonical));
            } catch (InvocationTargetException ctorFailed) {
                Throwable c = RealEngineVariantFacts.unwrap(ctorFailed);
                return Verdict.rejected(DoorRejectReasons.CUSTOM_ENGINE_REJECTED,
                        "HullVariantSpec ctor: " + c.getClass().getSimpleName() + ": " + c.getMessage());
            }
            Object hullSpec = RealEngineVariantFacts.call(variant, "getHullSpec");
            Object hullSize = RealEngineVariantFacts.call(variant, "getHullSize");
            String sizeName = String.valueOf(hullSize);

            // #5: slot identity + weaponFits on the REAL slots.
            Method getSlot = RealEngineVariantFacts.method(hullSpec, "getWeaponSlotAPI", String.class);
            Method getWeaponSpec = RealEngineVariantFacts.method(settings, "getWeaponSpec", String.class);
            for (WeaponGroup group : canonical.weaponGroups()) {
                for (WeaponMount mount : group.weapons()) {
                    Object slot = getSlot.invoke(hullSpec, mount.slotId());
                    if (slot == null) {
                        return Verdict.rejected(DoorRejectReasons.CUSTOM_SHAPE_INVALID,
                                "slot unknown on hull: " + mount.slotId());
                    }
                    // A built-in slot carrying the hull's OWN built-in weapon = vanilla's
                    // .variant shape (the policy half accepted the exact pairing); nothing to fit — the ctor placed it
                    if ((Boolean) RealEngineVariantFacts.call(slot, "isBuiltIn")) {
                        Object ownWeapons = RealEngineVariantFacts.call(hullSpec, "getBuiltInWeapons");
                        Object own = ownWeapons instanceof java.util.Map<?, ?> m ? m.get(mount.slotId()) : null;
                        if (own != null && own.equals(mount.weaponId())) {
                            continue;
                        }
                    }
                    if ((Boolean) RealEngineVariantFacts.call(slot, "isBuiltIn")
                            || (Boolean) RealEngineVariantFacts.call(slot, "isDecorative")
                            || (Boolean) RealEngineVariantFacts.call(slot, "isSystemSlot")
                            || (Boolean) RealEngineVariantFacts.call(slot, "isHidden")
                            || (Boolean) RealEngineVariantFacts.call(slot, "isStationModule")
                            || !(Boolean) RealEngineVariantFacts.call(slot, "isWeaponSlot")) {
                        return Verdict.rejected(DoorRejectReasons.CUSTOM_SHAPE_INVALID,
                                "slot not a player weapon slot: " + mount.slotId());
                    }
                    Object weaponSpec = getWeaponSpec.invoke(settings, mount.weaponId());
                    if (weaponSpec == null) {
                        return Verdict.rejected(DoorRejectReasons.CUSTOM_WEAPON_DENIED,
                                "weapon unknown: " + mount.weaponId());
                    }
                    Method fits = RealEngineVariantFacts.method(slot, "weaponFits",
                            Class.forName("com.fs.starfarer.api.loading.WeaponSpecAPI", false, loader));
                    if (!(Boolean) fits.invoke(slot, weaponSpec)) {
                        return Verdict.rejected(DoorRejectReasons.CUSTOM_SHAPE_INVALID,
                                "weapon does not fit slot: " + mount.weaponId() + "@" + mount.slotId());
                    }
                }
            }

            // #8: the throwaway member with the MATCH's spawn person (knob ON ⇒ all-skills
            // commander stats; OFF ⇒ null stats = base OP/caps, the curated-officer lane's floor).
            Object shipKind = memberType.getField("SHIP").get(null);
            Object member = RealEngineVariantFacts.method(settings, "createFleetMember", memberType, variantApi)
                    .invoke(settings, shipKind, variant);
            Object personStats = null;
            String personNote = "none";
            if (AllSkillsAttach.enabled()) {
                AllSkillsAttach.attachAllSkillsCaptain(member, canonical.variantId());
                Object commander = memberApi.getMethod("getFleetCommanderForStats").invoke(member);
                personStats = commander == null ? null : RealEngineVariantFacts.call(commander, "getStats");
                personNote = personStats == null ? "commander_null" : "all_skills_commander";
            }
            try {
                memberApi.getMethod("updateStats").invoke(member);
            } catch (ReflectiveOperationException ignored) {
                // stats are computed lazily by getStats() on this engine build
            }
            Object stats = memberApi.getMethod("getStats").invoke(member);

            // #4: vents/caps vs size table + the person's bonus.
            int base = baseVentOrCapMax(sizeName);
            int maxVents = base;
            int maxCaps = base;
            if (personStats != null) {
                Object ventBonus = charStatsApi.getMethod("getMaxVentsBonus").invoke(personStats);
                Object capBonus = charStatsApi.getMethod("getMaxCapacitorsBonus").invoke(personStats);
                Method computeEffective = RealEngineVariantFacts.method(ventBonus, "computeEffective", float.class);
                maxVents = (int) ((Float) computeEffective.invoke(ventBonus, (float) base)).floatValue();
                maxCaps = (int) ((Float) computeEffective.invoke(capBonus, (float) base)).floatValue();
            }
            if (canonical.fluxVents() > maxVents) {
                return Verdict.rejected(DoorRejectReasons.CUSTOM_SHAPE_INVALID,
                        "vents " + canonical.fluxVents() + " > max " + maxVents + " for " + sizeName);
            }
            if (canonical.fluxCapacitors() > maxCaps) {
                return Verdict.rejected(DoorRejectReasons.CUSTOM_SHAPE_INVALID,
                        "capacitors " + canonical.fluxCapacitors() + " > max " + maxCaps + " for " + sizeName);
            }

            // #6: wings ≤ bays (built-ins occupy the leading bays; hullmod bays ride the stat).
            Object baysStat = RealEngineVariantFacts.method(stats, "getNumFighterBays").invoke(stats);
            int bays = (Integer) RealEngineVariantFacts.method(baysStat, "getModifiedInt").invoke(baysStat);
            Collection<?> builtInWings = (Collection<?>) RealEngineVariantFacts.call(hullSpec, "getBuiltInWings");
            int builtIn = builtInWings == null ? 0 : builtInWings.size();
            if (builtIn + canonical.wings().size() > bays) {
                return Verdict.rejected(DoorRejectReasons.CUSTOM_SHAPE_INVALID,
                        "wings " + canonical.wings().size() + " + built-in " + builtIn + " > bays " + bays);
            }
            Method getWingSpec = RealEngineVariantFacts.method(settings, "getFighterWingSpec", String.class);
            for (String wing : canonical.wings()) {
                if (getWingSpec.invoke(settings, wing) == null) {
                    return Verdict.rejected(DoorRejectReasons.CUSTOM_WING_DENIED, "wing unknown: " + wing);
                }
            }

            // #9/#10: applicability over the detached proxy (the answer set, verbatim).
            Object shipProxy = detachedShipProxy(loader, shipApi, shieldApi, variant, hullSpec, hullSize,
                    sizeName, stats);
            Method getHullModSpec = RealEngineVariantFacts.method(settings, "getHullModSpec", String.class);
            Method isApplicable = effectApi.getMethod("isApplicableToShip", shipApi);
            Method unapplicableReason = effectApi.getMethod("getUnapplicableReason", shipApi);
            for (String modId : canonical.hullMods()) {
                Object spec = getHullModSpec.invoke(settings, modId);
                if (spec == null) {
                    return Verdict.rejected(DoorRejectReasons.CUSTOM_HULLMOD_DENIED, "hullmod unknown: " + modId);
                }
                Object effect = RealEngineVariantFacts.call(spec, "getEffect");
                if (effect == null) {
                    continue;
                }
                boolean applicable;
                try {
                    applicable = (Boolean) isApplicable.invoke(effect, shipProxy);
                } catch (InvocationTargetException detached) {
                    Throwable c = RealEngineVariantFacts.unwrap(detached);
                    return Verdict.rejected(DoorRejectReasons.CUSTOM_HULLMOD_INAPPLICABLE,
                            modId + " cannot validate detached (" + (c instanceof DetachedRead
                                    ? ((DetachedRead) c).method : c.getClass().getSimpleName()) + ")");
                }
                if (!applicable) {
                    String why;
                    try {
                        why = String.valueOf(unapplicableReason.invoke(effect, shipProxy));
                    } catch (InvocationTargetException detached) {
                        why = "not applicable";
                    }
                    return Verdict.rejected(DoorRejectReasons.CUSTOM_HULLMOD_INAPPLICABLE, modId + ": " + why);
                }
            }

            // #11: s-mod cap (Misc.getCurrSpecialMods excludes built-ins + d-mods by its own law).
            int sMods = (Integer) misc.getMethod("getCurrSpecialMods", variantApi).invoke(null, variant);
            int cap = config.sModCap();
            if (cap == CustomVariantConfig.S_MOD_CAP_ENGINE) {
                cap = (Integer) misc.getMethod("getMaxPermanentMods", memberApi, charStatsApi)
                        .invoke(null, member, personStats);
            }
            if (sMods > cap) {
                return Verdict.rejected(DoorRejectReasons.CUSTOM_SMOD_CAP, "s-mods " + sMods + " > cap " + cap
                        + " (" + personNote + ")");
            }

            // #14: OP budget with the spawn person's baseline.
            int unusedOp = (Integer) variantApi.getMethod("getUnusedOP", charStatsApi).invoke(variant, personStats);
            if (unusedOp < 0) {
                return Verdict.rejected(DoorRejectReasons.CUSTOM_OP_EXCEEDED, "unused OP " + unusedOp
                        + " (" + personNote + ")");
            }

            // #15: register a CLONE (overwrite=false for a mint; true = the catalog lane's live UPDATE of
            // this serve's own registration), then assert resolvable.
            Object registryCopy = RegistryVariantClone.cloneVariant(variant, "custom-register");
            Class<?> registry = Class.forName(VARIANT_REGISTRY_CLASS, true, loader);
            registry.getMethod(VARIANT_REGISTRY_PUT, hullVariantClass, boolean.class)
                    .invoke(null, registryCopy, overwrite);
            if (!RealEngineDemoRoster.variantResolvable(canonical.variantId())) {
                // The registry put did not stick = OUR side, never the submitter's.
                return Verdict.rejected(DoorRejectReasons.CUSTOM_ENGINE_FAULT,
                        "registered id did not resolve back: " + canonical.variantId());
            }
            registered++;
            System.out.println("custom-variant engine_pass id=" + canonical.variantId()
                    + " size=" + sizeName + " vents=" + canonical.fluxVents() + "/" + maxVents
                    + " caps=" + canonical.fluxCapacitors() + "/" + maxCaps
                    + " wings=" + canonical.wings().size() + "/" + (bays - builtIn)
                    + " smods=" + sMods + "/" + cap + " unused_op=" + unusedOp
                    + " person=" + personNote + " registered=" + registered
                    + (overwrite ? " overwrite=true" : ""));
            return Verdict.accepted(unusedOp); // step 3(f): the ops row's seat.N.op reading
        } catch (ReflectiveOperationException | RuntimeException engineFault) {
            // Anything escaping PAST the ctor content check (#3 above
            // answers content with custom_engine_rejected) is the reflection bridge or our own step
            // code breaking = a SERVER fault. Non-hostile token: it never draws the submitter's budget.
            Throwable c = RealEngineVariantFacts.unwrap(engineFault);
            return Verdict.rejected(DoorRejectReasons.CUSTOM_ENGINE_FAULT,
                    c.getClass().getSimpleName() + ": " + c.getMessage());
        }
    }

    /** The check-#9 proxy: answers a fixed getter set, THROWS {@link DetachedRead} for anything else. */
    private static Object detachedShipProxy(ClassLoader loader, Class<?> shipApi, Class<?> shieldApi,
            Object variant, Object hullSpec, Object hullSize, String sizeName, Object stats)
            throws ReflectiveOperationException {
        Object baseShield = RealEngineVariantFacts.call(hullSpec, "getShieldType");
        Method hasHullMod = RealEngineVariantFacts.method(variant, "hasHullMod", String.class);
        boolean frontShield = (Boolean) hasHullMod.invoke(variant, "frontshield");
        boolean shieldPresent = (!"NONE".equals(String.valueOf(baseShield))
                && !(Boolean) hasHullMod.invoke(variant, "shield_shunt")) || frontShield;
        Object shieldKind = frontShield
                ? Enum.valueOf(baseShield.getClass().asSubclass(Enum.class), "FRONT") : baseShield;
        Object shieldProxy = Proxy.newProxyInstance(loader, new Class<?>[]{shieldApi},
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
                case "getVariant": return variant;
                case "getHullSpec": return hullSpec;
                case "getHullSize": return hullSize;
                case "isFrigate": return "FRIGATE".equals(sizeName);
                case "isDestroyer": return "DESTROYER".equals(sizeName);
                case "isCruiser": return "CRUISER".equals(sizeName);
                case "isCapital": return "CAPITAL_SHIP".equals(sizeName);
                case "isFighter": return "FIGHTER".equals(sizeName);
                case "getMutableStats": return stats;
                case "getShield": return shieldPresent ? shieldProxy : null;
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == margs[0];
                case "toString": return "DetachedShipProxy(" + sizeName + ")";
                default: throw new DetachedRead(n);
            }
        };
        return Proxy.newProxyInstance(loader, new Class<?>[]{shipApi}, handler);
    }

    /**
     * The wire-order engine JSON — the server twin of the client's {@code toEngineJson} (same
     * 15 keys, same order, same group/module shapes) so both engines build identical specs.
     */
    public static JSONObject toEngineJson(VariantDefinition def) {
        try {
            JSONObject json = new JSONObject();
            json.put("variantId", def.variantId());
            json.put("hullId", def.hullId());
            json.put("displayName", def.displayName());
            json.put("goalVariant", def.goalVariant());
            json.put("fluxVents", def.fluxVents());
            json.put("fluxCapacitors", def.fluxCapacitors());
            json.put("hullMods", toArray(def.hullMods()));
            json.put("permaMods", toArray(def.permaMods()));
            json.put("sMods", toArray(def.sMods()));
            json.put("sModdedBuiltIns", toArray(def.sModdedBuiltIns()));
            json.put("suppressedMods", toArray(def.suppressedMods()));
            json.put("wings", toArray(def.wings()));
            json.put("tags", toArray(def.tags()));
            JSONArray groups = new JSONArray();
            for (WeaponGroup group : def.weaponGroups()) {
                JSONObject groupJson = new JSONObject();
                groupJson.put("autofire", group.autofire());
                groupJson.put("mode", group.alternating() ? "ALTERNATING" : "LINKED");
                JSONObject weapons = new JSONObject();
                for (WeaponMount mount : group.weapons()) {
                    weapons.put(mount.slotId(), mount.weaponId());
                }
                groupJson.put("weapons", weapons);
                groups.put(groupJson);
            }
            json.put("weaponGroups", groups);
            JSONObject modules = new JSONObject();
            for (ModuleMount mount : def.modules()) {
                modules.put(mount.slotId(), mount.moduleVariantId());
            }
            json.put("modules", modules);
            return json;
        } catch (JSONException impossible) {
            throw new IllegalStateException("engine JSON build failed", impossible);
        }
    }

    private static JSONArray toArray(List<String> values) {
        JSONArray array = new JSONArray();
        for (String value : values) {
            array.put(value);
        }
        return array;
    }

    /**
     * Persistence ({@code <persistDir>/<stamp>/<canonical>.variant}) is NOT written
     * here: this method runs on the sim thread and the residency law forbids
     * frame-loop file I/O — step 3 adds the off-thread evidence writer (the
     * {@code GameplayEdgeEventWatchSink} daemon pattern) fed from the ledger's accepted set.
     * The dir is resolved now so the boot row names where it WILL land.
     */
    public File persistDirOrNull() {
        return persistDirOrNull;
    }

    public int registeredCount() {
        return registered;
    }
}
