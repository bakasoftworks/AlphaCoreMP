package cc.starsector.server.bridge;

import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.match.OfficerRosterCodec;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The engine-born growth seam — a per-round listener on the combat
 * engine's live {@code ObjectRepository} capturing spawn/removal edges the serve loop's
 * own deploy lanes never see (bay fighters, system drones, engine-spawned ships), plus
 * the per-variant wing/drone capacity read behind the stable-roster growth bound.
 *
 * <p>Seam identity: {@code com.fs.util.container.repo
 * .ObjectRepository} is {@code DoNotObfuscate} (update-stable) with a SINGLE
 * {@code setListener(ObjectRepository.o)} slot firing {@code objectAdded}/{@code
 * objectRemoved} at exactly staged→live promotion and live removal — symmetric spawn AND
 * despawn, which the poll fallback cannot give for fighter {@code land()} (it fires no
 * other event). The combat-side slot is vacant (only CampaignEngine claims campaign
 * repos); etiquette here: preserve+delegate any prior listener, observation-only
 * callbacks (they fire inside {@code executeAdds}/{@code executeRemoves} — a throw must
 * NEVER leak into the engine), readback-verified install with rollback, idempotent
 * uninstall. Round teardown uses {@code clear()}, which fires NO callbacks — uninstall
 * owns cleanup, and the deploy mints a FRESH repository per round, so the serve loop
 * installs per round on the CURRENT instance.
 *
 * <p>Capture reads ride the ShipAPI surface reflectively (the {@link RealEngineDemoRoster}
 * idiom — the server never compiles against game jars). Fail-closed edges:
 * blank {@code getId()} (fx_drone-tagged mints) skipped LOUD once per variant per round;
 * add-side outside {0,1} skipped LOUD. Removal identity (side/variant/name) comes from
 * the identity recorded at the matching add — a dead ship's {@code getOwner()} flips to
 * 100, so the live read is unusable there — and {@code removedHealthy} =
 * {@code !isHulk() && hitpoints > 0} at the callback (the despawn-reason seam truth:
 * healthy = DEPARTED/docked, else DESTROYED).
 *
 * <p>Threading: callbacks and {@code drain()} both run on the sim/serve thread today;
 * everything synchronizes on the class anyway (the synchronized-statics house idiom).
 */
public final class EngineBornGrowthSeam {

    // ---- PINNED 0.98a-RC8 (the RealEngineDemoRoster pin block's siblings). ----
    private static final String ENGINE_CLASS = "com.fs.starfarer.combat.CombatEngine";
    private static final String SHIP_CLASS = "com.fs.starfarer.combat.entities.Ship";
    private static final String HULL_VARIANT_SPEC_CLASS =
            "com.fs.starfarer.loading.specs.HullVariantSpec";
    /** DoNotObfuscate listener slot interface. */
    private static final String REPO_LISTENER_INTERFACE =
            "com.fs.util.container.repo.ObjectRepository$o";
    /** ShipSystemSpec type enum constant naming a drone-launcher system. */
    private static final String DRONE_LAUNCHER_TYPE = "DRONE_LAUNCHER";
    /** The api package the captain read's interfaces live in (starfarer.api.jar). */
    private static final String API_CHARACTERS = "com.fs.starfarer.api.characters.";

    /** Fail-closed wrapper so caller code stays off the reflective engine surface. */
    public static final class EngineBornGrowthSeamException extends RuntimeException {
        public EngineBornGrowthSeamException(String message) {
            super(message);
        }

        public EngineBornGrowthSeamException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * One captured lifecycle edge, engine-reads raw (the hook layer clamps/adapts —
     * the {@code WaveDeployResult} precedent). Removal edges carry the identity recorded
     * at their add ({@code crPerMille} 0 there; consumers only read {@code removedHealthy}).
     */
    public record CapturedEdge(
            boolean added,
            String stableKey,
            int side,
            String variantId,
            String shipName,
            int crPerMille,
            boolean removedHealthy,
            /** The carrier's stable key when this add is a
             *  bay-born fighter ({@code ship.getWing().getSourceShip()}), else {@code ""}. */
            String carrierStableKey,
            /** Index of {@code wing.getSource()} in the carrier's {@code getLaunchBays()};
             *  {@link #BAY_NONE} when not a bay-born fighter (or the read failed soft). */
            int bayIndex,
            /** The engine captain ({@code getCaptain()}) as ONE
             *  {@code OfficerRosterCodec} grammar line, read at a carrier-less ADD only;
             *  {@code ""} = no captain / the default captain / a counted fail-closed skip.
             * */
            String officerLine) {
    }

    /** {@link CapturedEdge#bayIndex()} sentinel — not a carrier-born fighter. */
    public static final int BAY_NONE = 0xFF;

    /** One variant's wing/drone capacity (raw counts; the bound formula lives at the hook types). */
    public record WingDroneCapacity(int fighters, int drones) {
    }

    /** Identity snapshotted at add — removal callbacks read owner=100 on dead ships. */
    private record AddIdentity(int side, String variantId, String shipName) {
    }

    // ---- Install-scoped state (cleared by uninstall; synchronized on the class). ----
    private static Object installedRepo;
    private static Object installedListener;
    private static Object priorListener;
    private static Class<?> shipClass;
    private static Method shipGetId;
    private static Method shipGetOwner;
    private static Method shipGetVariant;
    private static Method shipGetName;
    private static Method shipGetCurrentCR;
    private static Method shipIsHulk;
    private static Method shipGetHitpoints;
    private static Method variantGetHullVariantId;
    // Carrier/bay placement reads (Ship.getWing → M.getSourceShip/getSource;
    // Ship.getLaunchBays for the bay index) — public engine surface.
    //
    private static Method shipGetWing;
    private static Method wingGetSourceShip;
    private static Method wingGetSource;
    private static Method shipGetLaunchBays;
    /** Ship.isFighter — the script-born-wing test (wing, no carrier/bay). */
    private static Method shipIsFighter;
    private static final List<CapturedEdge> captured = new ArrayList<>();
    private static final Map<String, AddIdentity> addIdentityByKey = new HashMap<>();
    private static final Set<String> blankIdSkipLogged = new HashSet<>();
    /** once-per-variant-per-round log guard for a failed carrier/bay placement read. */
    private static final Set<String> placementSkipLogged = new HashSet<>();
    /** once-per-variant-per-round log guard for the script-born-wing CR keep. */
    private static final Set<String> crKeepLogged = new HashSet<>();
    /**
     * The engine-captain read handles — {@code Ship.getCaptain()} plus
     * the api interfaces ({@code com.fs.starfarer.api.characters.*}) invoked on the engine's
     * {@code rpg.Person} impl (interface Methods: the impl's inner classes need no access), resolved
     * with their siblings in {@link #install}; the once-per-variant-per-round skip-log guard; and
     * the per-round meter surfaced on the uninstall line.
     *
     */
    private static Method shipGetCaptain;
    private static Method personGetStats;
    private static Method personGetPersonalityApi;
    private static Method personalityGetId;
    private static Method personGetAiCoreId;
    private static Method personGetName;
    private static Method personGetPortraitSprite;
    private static Method fullNameGetFirst;
    private static Method fullNameGetLast;
    private static Method statsGetLevel;
    private static Method statsGetSkillsCopy;
    private static Method skillLevelGetLevel;
    private static Method skillLevelGetSkill;
    private static Method skillSpecGetId;
    private static Method skillSpecIsAptitudeEffect;
    private static final Set<String> captainSkipLogged = new HashSet<>();
    private static boolean encodeRejectLogged;
    private static int captainLines;
    private static int captainSkipped;

    /** Why a real captain's line fell back to {@code ""} (a bounded log token, never the name). */
    private enum CaptainSkipCause {
        /** A reflective captain read threw (the edge still announces, bare). */
        READ_FAILED,
        /** A non-zero skill entry with a null spec / blank id, or the same skill id twice. */
        SKILL_ENTRY,
        /** {@code OfficerRosterCodec.encodeEngineCaptain} refused a bound (returned {@code ""}). */
        ENCODE_REJECTED
    }

    private EngineBornGrowthSeam() {
    }

    /** True while a listener is installed (diagnostics + the double-install guard's probe). */
    public static synchronized boolean hasInstance() {
        return installedRepo != null;
    }

    /**
     * Install the capture listener on the engine's CURRENT repository. Fail-fast on
     * double-install (the serve loop's lifecycle is one install per round, uninstalled at
     * the wrap — a second install without an uninstall is a lifecycle bug, never merged
     * silently). Preserves any prior listener as a delegate; readback-verifies the slot
     * and rolls back on mismatch.
     */
    public static synchronized void install() {
        if (installedRepo != null) {
            throw new EngineBornGrowthSeamException(
                    "growth seam already installed — uninstall() must run at the round wrap"
                            + " before the next install (per-round lifecycle)");
        }
        ClassLoader loader = EngineBornGrowthSeam.class.getClassLoader();
        try {
            Class<?> engineClass = Class.forName(ENGINE_CLASS, true, loader);
            Object engine = engineClass.getMethod("getInstance").invoke(null);
            if (engine == null) {
                throw new EngineBornGrowthSeamException(
                        "CombatEngine.getInstance() returned null — the growth seam installs"
                                + " only on a booted engine (create() runs first)");
            }
            Object repo = engineClass.getMethod("getObjects").invoke(engine);
            if (repo == null) {
                throw new EngineBornGrowthSeamException(
                        "CombatEngine.getObjects() returned null at growth-seam install");
            }
            Class<?> listenerInterface = Class.forName(REPO_LISTENER_INTERFACE, false, loader);
            Method getListener = repo.getClass().getMethod("getListener");
            Method setListener = repo.getClass().getMethod("setListener", listenerInterface);

            shipClass = Class.forName(SHIP_CLASS, false, loader);
            shipGetId = shipClass.getMethod("getId");
            shipGetOwner = shipClass.getMethod("getOwner");
            shipGetVariant = shipClass.getMethod("getVariant");
            shipGetName = shipClass.getMethod("getName");
            shipGetCurrentCR = shipClass.getMethod("getCurrentCR");
            shipIsHulk = shipClass.getMethod("isHulk");
            shipGetHitpoints = shipClass.getMethod("getHitpoints");
            shipGetWing = shipClass.getMethod("getWing");
            Class<?> wingClass = shipGetWing.getReturnType();
            wingGetSourceShip = wingClass.getMethod("getSourceShip");
            wingGetSource = wingClass.getMethod("getSource");
            shipGetLaunchBays = shipClass.getMethod("getLaunchBays");
            shipIsFighter = shipClass.getMethod("isFighter");
            // The captain read's handles — pinned
            // public api surface; a miss is loud at install.
            //
            shipGetCaptain = shipClass.getMethod("getCaptain");
            Class<?> personApi = Class.forName(API_CHARACTERS + "PersonAPI", false, loader);
            Class<?> statsApi =
                    Class.forName(API_CHARACTERS + "MutableCharacterStatsAPI", false, loader);
            Class<?> skillLevelApi = Class.forName(
                    API_CHARACTERS + "MutableCharacterStatsAPI$SkillLevelAPI", false, loader);
            Class<?> skillSpecApi = Class.forName(API_CHARACTERS + "SkillSpecAPI", false, loader);
            Class<?> personalityApi =
                    Class.forName(API_CHARACTERS + "PersonalityAPI", false, loader);
            Class<?> fullNameClass = Class.forName(API_CHARACTERS + "FullName", false, loader);
            personGetStats = personApi.getMethod("getStats");
            personGetPersonalityApi = personApi.getMethod("getPersonalityAPI");
            personalityGetId = personalityApi.getMethod("getId");
            personGetAiCoreId = personApi.getMethod("getAICoreId");
            personGetName = personApi.getMethod("getName");
            personGetPortraitSprite = personApi.getMethod("getPortraitSprite");
            fullNameGetFirst = fullNameClass.getMethod("getFirst");
            fullNameGetLast = fullNameClass.getMethod("getLast");
            statsGetLevel = statsApi.getMethod("getLevel");
            statsGetSkillsCopy = statsApi.getMethod("getSkillsCopy");
            skillLevelGetLevel = skillLevelApi.getMethod("getLevel");
            skillLevelGetSkill = skillLevelApi.getMethod("getSkill");
            skillSpecGetId = skillSpecApi.getMethod("getId");
            skillSpecIsAptitudeEffect = skillSpecApi.getMethod("isAptitudeEffect");
            Class<?> hullVariantClass = Class.forName(HULL_VARIANT_SPEC_CLASS, false, loader);
            variantGetHullVariantId = hullVariantClass.getMethod("getHullVariantId");

            Object prior = getListener.invoke(repo);
            Object proxy = Proxy.newProxyInstance(loader, new Class<?>[] {listenerInterface},
                    new GrowthListenerHandler(prior));
            setListener.invoke(repo, proxy);
            Object readback = getListener.invoke(repo);
            if (readback != proxy) {
                setListener.invoke(repo, prior); // rollback — never leave a half-claimed slot
                throw new EngineBornGrowthSeamException(
                        "growth-seam listener readback mismatch after install (slot holds "
                                + (readback == null ? "none" : readback.getClass().getName())
                                + ") — rolled back to the prior listener");
            }
            installedRepo = repo;
            installedListener = proxy;
            priorListener = prior;
            captured.clear();
            addIdentityByKey.clear();
            blankIdSkipLogged.clear();
            placementSkipLogged.clear();
            crKeepLogged.clear();
            resetCaptainState();
            System.out.println("growth-seam installed repo=" + repo.getClass().getName()
                    + " prior_listener="
                    + (prior == null ? "none" : prior.getClass().getName()));
        } catch (EngineBornGrowthSeamException failure) {
            throw failure;
        } catch (Throwable reflectFailure) {
            throw new EngineBornGrowthSeamException(
                    "growth-seam install failed: " + reflectFailure, reflectFailure);
        }
    }

    /**
     * Return and clear the edges captured since the last drain (sim-thread cadence: the
     * serve loop drains once per tick after capture). Fail-fast without an install —
     * a drain on an uninstalled seam is a lifecycle bug, not an empty round.
     */
    public static synchronized List<CapturedEdge> drain() {
        if (installedRepo == null) {
            throw new EngineBornGrowthSeamException(
                    "growth seam drain without install (per-round lifecycle)");
        }
        if (captured.isEmpty()) {
            return List.of();
        }
        List<CapturedEdge> out = new ArrayList<>(captured);
        captured.clear();
        return out;
    }

    /**
     * Remove the listener, restoring the preserved prior one — idempotent (the serve
     * finally backstop re-runs it after the normal pre-wrap call). If some other party
     * displaced our listener mid-round, theirs stays (loud line) — never clobber a live
     * slot we no longer own.
     */
    public static synchronized void uninstall() {
        if (installedRepo == null) {
            return;
        }
        logCaptainMeter();
        try {
            Method getListener = installedRepo.getClass().getMethod("getListener");
            Object current = getListener.invoke(installedRepo);
            if (current == installedListener) {
                Class<?> listenerInterface = Class.forName(REPO_LISTENER_INTERFACE, false,
                        installedRepo.getClass().getClassLoader());
                Method setListener =
                        installedRepo.getClass().getMethod("setListener", listenerInterface);
                setListener.invoke(installedRepo, priorListener);
                System.out.println("growth-seam uninstalled restored_prior="
                        + (priorListener == null ? "none"
                                : priorListener.getClass().getName()));
            } else {
                System.out.println("growth-seam uninstall_listener_displaced current="
                        + (current == null ? "none" : current.getClass().getName())
                        + " (leaving the displacing listener installed)");
            }
        } catch (Throwable reflectFailure) {
            throw new EngineBornGrowthSeamException(
                    "growth-seam uninstall failed: " + reflectFailure, reflectFailure);
        } finally {
            installedRepo = null;
            installedListener = null;
            priorListener = null;
            captured.clear();
            addIdentityByKey.clear();
            blankIdSkipLogged.clear();
            placementSkipLogged.clear();
            crKeepLogged.clear();
            resetCaptainState();
        }
    }

    /**
     * Wing/drone capacity for one variant id through the engine's own loader chain:
     * Σ {@code getWing(i).getNumFighters()} over the
     * variant wing list — built-in wings are already merged into it at
     * {@code setHullSpec}, so Tempest-class built-ins count — plus the hull system's
     * {@code getMaxDrones()} when its type is {@code DRONE_LAUNCHER}. A THROW is fatal
     * at serve entry by design: the bound must never silently under-count.
     */
    public static WingDroneCapacity capacityFor(String variantId) {
        if (variantId == null || variantId.isBlank()) {
            throw new EngineBornGrowthSeamException("capacityFor requires a non-blank variantId");
        }
        ClassLoader loader = EngineBornGrowthSeam.class.getClassLoader();
        try {
            Class<?> hullVariantClass = Class.forName(HULL_VARIANT_SPEC_CLASS, false, loader);
            Object variant =
                    RealEngineDemoRoster.resolveVariantById(loader, hullVariantClass, variantId);
            List<?> wingIds = (List<?>) hullVariantClass.getMethod("getWings").invoke(variant);
            Method getWing = hullVariantClass.getMethod("getWing", int.class);
            Method getNumFighters = null;
            int fighters = 0;
            for (int i = 0; wingIds != null && i < wingIds.size(); i++) {
                Object wingSpec = getWing.invoke(variant, i);
                if (wingSpec == null) {
                    continue; // empty/unresolvable bay slot — no capacity contribution
                }
                if (getNumFighters == null) {
                    getNumFighters = wingSpec.getClass().getMethod("getNumFighters");
                }
                fighters += (Integer) getNumFighters.invoke(wingSpec);
            }
            int drones = 0;
            Object hullSpec = hullVariantClass.getMethod("getHullSpec").invoke(variant);
            if (hullSpec != null) {
                Object systemSpec = hullSpec.getClass().getMethod("getSystemSpec").invoke(hullSpec);
                if (systemSpec != null) {
                    Object type = systemSpec.getClass().getMethod("getType").invoke(systemSpec);
                    String typeName = type instanceof Enum<?>
                            ? ((Enum<?>) type).name() : String.valueOf(type);
                    if (DRONE_LAUNCHER_TYPE.equals(typeName)) {
                        drones = (Integer)
                                systemSpec.getClass().getMethod("getMaxDrones").invoke(systemSpec);
                    }
                }
            }
            return new WingDroneCapacity(Math.max(0, fighters), Math.max(0, drones));
        } catch (EngineBornGrowthSeamException
                | RealEngineDemoRoster.RealEngineDemoRosterException failure) {
            throw failure;
        } catch (Throwable reflectFailure) {
            throw new EngineBornGrowthSeamException(
                    "capacityFor('" + variantId + "') failed: " + reflectFailure, reflectFailure);
        }
    }

    /**
     * The module variant ids a STATION variant will
     * mint at deploy — {@code HullVariantSpec.getStationModules()} (slot id → module variant
     * id) read through the SAME loader-resolve idiom as
     * {@link #capacityFor} ({@code RealEngineDemoRoster.resolveVariantById}, one registry, one
     * fail-closed diagnostic). Returned SORTED BY SLOT ID so the list is deterministic across
     * runs (the map's own iteration order is not a contract).
     *
     * <p>Non-station variants return EMPTY — the serve loop folds every roster variant through
     * here and a hull with no station modules contributes nothing. A station variant whose
     * module map is null/empty, or that carries a blank module variant id, THROWS: that is a
     * content boundary (the mod-dir data did not load), and the caller's bound term must never
     * silently under-count (the {@link #capacityFor} posture — fatal at serve entry, never a
     * mid-round roster-bound-exceeded kill).
     */
    public static List<String> stationModuleVariantIds(String variantId) {
        if (variantId == null || variantId.isBlank()) {
            throw new EngineBornGrowthSeamException(
                    "stationModuleVariantIds requires a non-blank variantId");
        }
        ClassLoader loader = EngineBornGrowthSeam.class.getClassLoader();
        try {
            Class<?> hullVariantClass = Class.forName(HULL_VARIANT_SPEC_CLASS, false, loader);
            Object variant =
                    RealEngineDemoRoster.resolveVariantById(loader, hullVariantClass, variantId);
            Object isStation = hullVariantClass.getMethod("isStation").invoke(variant);
            if (!Boolean.TRUE.equals(isStation)) {
                return List.of();
            }
            Object rawModules =
                    hullVariantClass.getMethod("getStationModules").invoke(variant);
            if (!(rawModules instanceof Map)) {
                throw new EngineBornGrowthSeamException(
                        "station variant '" + variantId + "' reported isStation()=true but"
                                + " getStationModules() returned "
                                + (rawModules == null ? "null" : rawModules.getClass().getName())
                                + " — the station module map is a CONTENT boundary, not an"
                                + " empty cluster (refusing an under-counted module bound)");
            }
            Map<?, ?> modules = (Map<?, ?>) rawModules;
            List<String> slotIds = new ArrayList<>(modules.size());
            for (Object slotId : modules.keySet()) {
                slotIds.add(slotId == null ? "" : slotId.toString());
            }
            java.util.Collections.sort(slotIds);
            List<String> moduleVariantIds = new ArrayList<>(slotIds.size());
            for (String slotId : slotIds) {
                Object rawModuleVariantId = modules.get(slotId);
                String moduleVariantId =
                        rawModuleVariantId == null ? "" : rawModuleVariantId.toString().trim();
                if (moduleVariantId.isEmpty()) {
                    throw new EngineBornGrowthSeamException(
                            "station variant '" + variantId + "' slot '" + slotId
                                    + "' carries a blank module variant id — refusing an"
                                    + " under-counted module bound");
                }
                moduleVariantIds.add(moduleVariantId);
            }
            if (moduleVariantIds.isEmpty()) {
                throw new EngineBornGrowthSeamException(
                        "station variant '" + variantId + "' resolved ZERO station modules —"
                                + " a station with no cluster means the variant data did not"
                                + " load (refusing an under-counted module bound)");
            }
            return List.copyOf(moduleVariantIds);
        } catch (EngineBornGrowthSeamException
                | RealEngineDemoRoster.RealEngineDemoRosterException failure) {
            throw failure;
        } catch (Throwable reflectFailure) {
            throw new EngineBornGrowthSeamException(
                    "stationModuleVariantIds('" + variantId + "') failed: " + reflectFailure,
                    reflectFailure);
        }
    }

    /**
     * THE single blank-id law — a repo {@code Ship} whose raw
     * {@code getId()} is null/blank is a non-roster fx/presentation mint (the engine's
     * {@code Ship} ctor writes {@code id=""} exactly when the variant carries the
     * {@code fx_drone} tag — {@code createFXDrone} — else {@code Misc.genUID()}; the field has
     * no setter). Callers:
     * this seam's {@code blank_id_skip} branch and
     * {@link EngineCombatBinding#rosterShipList()} — NEVER fork a second copy.
     */
    public static boolean isNonRosterMintId(Object rawId) {
        return rawId == null || rawId.toString().trim().isEmpty();
    }

    /**
     * Observation-only capture off the repository callback. Every repo object flows
     * through here (missiles/projectiles included) — the {@code isInstance} check is the
     * fast path out. Any read failure is swallowed LOUD: a throw here would surface
     * inside the engine's {@code executeAdds}/{@code executeRemoves}.
     */
    private static synchronized void observe(Object candidate, boolean added) {
        if (shipClass == null || !shipClass.isInstance(candidate)) {
            return;
        }
        try {
            Object rawId = shipGetId.invoke(candidate);
            String stableKey = rawId == null ? "" : rawId.toString().trim();
            Object variantObj = shipGetVariant.invoke(candidate);
            String liveVariantId = "";
            if (variantObj != null) {
                Object rawVariantId = variantGetHullVariantId.invoke(variantObj);
                liveVariantId = rawVariantId == null ? "" : rawVariantId.toString();
            }
            if (isNonRosterMintId(rawId)) {
                // Landmine: fx_drone-tagged variants mint with id "" — never announce
                // (a blank key THROWS at the registry; the positional walks exclude these
                // entries via the same law, rosterShipList()).
                if (added && blankIdSkipLogged.add(liveVariantId)) {
                    System.out.println("growth-seam blank_id_skip variant="
                            + (liveVariantId.isEmpty() ? "unknown" : liveVariantId)
                            + " (fx_drone-class mint; logged once per variant per round)");
                }
                return;
            }
            if (added) {
                int side = (Integer) shipGetOwner.invoke(candidate);
                if (side != 0 && side != 1) {
                    System.out.println("growth-seam side_skip stable_id=" + stableKey
                            + " side=" + side + " variant=" + liveVariantId);
                    return;
                }
                if (liveVariantId.isEmpty()) {
                    System.out.println("growth-seam variant_skip stable_id=" + stableKey
                            + " (null variant at add)");
                    return;
                }
                Object rawName = shipGetName.invoke(candidate);
                String shipName = rawName == null || rawName.toString().isBlank()
                        ? liveVariantId : rawName.toString(); // fighter getName() is nullable
                float cr = (Float) shipGetCurrentCR.invoke(candidate);
                addIdentityByKey.put(stableKey, new AddIdentity(side, liveVariantId, shipName));
                String carrierKey = "";
                int bayIndex = BAY_NONE;
                // A fighter that carries a wing but NO carrier+bay is a
                // SCRIPT-born wing (spawnShipOrWing(wingId, loc, facing, 0, null) — Threat
                // attack/construction/reclamation swarms, hive respawns, swarm-launcher spawns).
                // Those keep vanilla's tracker CR (RepairTracker default 0.5): the reclamation
                // homing weight is getShipWeight(fab) × (1 − swarmCR), so our
                // deploy-CR floor of 1.0 zeroed it and the swarms drifted off ("retreat").
                boolean scriptBornWing = false;
                // A bay-born fighter carries its wing at add-time (the bay's mint
                // sets the wing BEFORE addObject) — read carrier + bay
                // index here, fail-SOFT to the absent shape (the row still announces the hull;
                // only its HUD placement is lost, logged once per variant).
                try {
                    Object wing = shipGetWing.invoke(candidate);
                    Object carrier = wing == null ? null : wingGetSourceShip.invoke(wing);
                    Object bay = wing == null ? null : wingGetSource.invoke(wing);
                    if (carrier != null && bay != null) {
                        Object carrierRawId = shipGetId.invoke(carrier);
                        List<?> bays = (List<?>) shipGetLaunchBays.invoke(carrier);
                        int idx = bays == null ? -1 : bays.indexOf(bay);
                        if (carrierRawId != null && !isNonRosterMintId(carrierRawId)
                                && idx >= 0 && idx < BAY_NONE) {
                            carrierKey = carrierRawId.toString().trim();
                            bayIndex = idx;
                        }
                    } else if (wing != null && (Boolean) shipIsFighter.invoke(candidate)) {
                        scriptBornWing = true;
                    }
                } catch (Throwable placementFailure) {
                    if (placementSkipLogged.add(liveVariantId)) {
                        System.out.println("growth-seam carrier_read_failed variant="
                                + liveVariantId + " reason=" + placementFailure);
                    }
                }
                // Engine births ride the engine's own CR (station growth
                // default 0.5) — the descriptor deployCr floor never ran on this lane. Floor
                // through the engine-native seam BEFORE the crPerMille read so the wire carries
                // the floored value; a seam failure lands in this guard's observe_failed.
                // Script-born wings are EXCLUDED (comment above).
                MatchDescriptor deployed = RealEngineDemoRoster.deployedDescriptorOrNull();
                // Terrain mults on EVERY engine birth, wings included (abyss
                // reaches fighters in vanilla; the law itself skips corona on them).
                TerrainStatSeam.applyToShip(candidate);
                if (scriptBornWing) {
                    if (crKeepLogged.add(liveVariantId)) {
                        System.out.println("growth-seam cr_keep stable_id=" + stableKey
                                + " variant=" + liveVariantId + " cr=" + cr
                                + " " + AiPickEvidence.describe(candidate)
                                + " (script-born wing keeps vanilla tracker CR;"
                                + " logged once per variant per round)");
                    }
                } else if (deployed != null && cr < deployed.deployCr()) {
                    CrDeploySeam.applyDeployCr(candidate, deployed.deployCr());
                    float floored = (Float) shipGetCurrentCR.invoke(candidate);
                    System.out.println("growth-seam cr_floor stable_id=" + stableKey
                            + " birth=" + cr + " floor=" + deployed.deployCr()
                            + " after=" + floored);
                    cr = floored;
                }
                int crPerMille = Math.round((Float.isFinite(cr) ? cr : 0f) * 1000f);
                // A carrier-less ADD carries its engine captain (a
                // bay-born fighter's captain is its carrier's — the GrowthEvent/AppendRow law).
                String officerLine =
                        carrierKey.isEmpty() ? captainLineOrEmpty(candidate, liveVariantId) : "";
                captured.add(new CapturedEdge(
                        true, stableKey, side, liveVariantId, shipName, crPerMille, false,
                        carrierKey, bayIndex, officerLine));
            } else {
                AddIdentity identity = addIdentityByKey.remove(stableKey);
                boolean removedHealthy = !((Boolean) shipIsHulk.invoke(candidate))
                        && (Float) shipGetHitpoints.invoke(candidate) > 0f;
                String removalVariantId = identity != null ? identity.variantId()
                        : (liveVariantId.isEmpty() ? "unknown" : liveVariantId);
                captured.add(new CapturedEdge(
                        false, stableKey,
                        identity != null ? identity.side() : 0,
                        removalVariantId,
                        identity != null ? identity.shipName() : removalVariantId,
                        0, removedHealthy, "", BAY_NONE, ""));
            }
        } catch (Throwable readFailure) {
            System.out.println("growth-seam observe_failed added=" + added
                    + " reason=" + readFailure);
        }
    }

    /**
     * The spawned ship's ENGINE captain
     * as ONE officer line, or {@code ""}. Runs once per carrier-less growth ADD on the SIM/serve
     * thread inside the engine's executeAdds (a spawn-time cost, default-captain swarm fighters
     * included — the read + encode + decode round-trip + their allocations all sit on that thread;
     * never per frame; the first-execution reflection cost of the 15 Methods is UNMEASURED — a
     * test-run row reads the sim-tick max around the first shard spawn,
     * per the frame-loop residency law), under observe()'s class lock. Without it
     * the ShardSpawner WING captain (PD/GI/IM elite; server {@code adm=0.75 capElite=3}) never
     * reached the replica ({@code adm=1.0 capElite=0}).
     *
     * <p>GATE (binding, lane G dev 1(b)): NEVER {@code PersonAPI.isDefault()} — it tests only the
     * portrait and the wing captain's portrait IS
     * {@code misc.default_portrait}, so it would drop exactly the captain this read exists for.
     * A captain is real iff it carries ≥ 1 non-aptitude skill entry at a non-zero level, or a
     * non-empty {@code aiCoreId}; the engine's default captain (ship 43's {@code ''/L1/4}: four
     * aptitude entries at 0) yields {@code ""} uncounted. Level-0 entries are skipped BEFORE their
     * spec is read (the default captain = 8 reflective calls; a real one = 12 + 1 per entry + 3
     * per learned entry — the wing captain's 7 entries / 3 skills = 27,
     * once per spawn). A
     * non-zero level outside {1, 2} still counts as real, so the factory refuses it LOUD instead
     * of the gate hiding it.
     *
     * <p>FAIL-CLOSED: every read sits in THIS catch — a throw reaching observe()'s catch would drop
     * the whole edge (the hull never announces). Any failure ⇒ {@code ""} + ONE counted line per
     * variant per round ({@link #noteCaptainSkip}); the grammar is built only by
     * {@link OfficerRosterCodec#encodeEngineCaptain} (the seam never formats it).
     *
     */
    private static String captainLineOrEmpty(Object ship, String variantId) {
        try {
            Object person = shipGetCaptain.invoke(ship);
            if (person == null) {
                return "";
            }
            Object stats = personGetStats.invoke(person);
            List<?> entries = (List<?>) statsGetSkillsCopy.invoke(stats);
            Map<String, Float> skills = new LinkedHashMap<>();
            boolean badEntry = false;
            for (int i = 0; entries != null && i < entries.size(); i++) {
                Object entry = entries.get(i);
                float skillLevel = ((Number) skillLevelGetLevel.invoke(entry)).floatValue();
                if (skillLevel == 0f) {
                    continue; // unlearned row (the default captain's aptitudes sit at 0)
                }
                Object spec = skillLevelGetSkill.invoke(entry);
                if (spec == null) {
                    badEntry = true;
                    continue;
                }
                if ((Boolean) skillSpecIsAptitudeEffect.invoke(spec)) {
                    continue;
                }
                Object rawSkillId = skillSpecGetId.invoke(spec);
                String skillId = rawSkillId == null ? "" : rawSkillId.toString();
                if (skillId.isBlank() || skills.put(skillId, skillLevel) != null) {
                    badEntry = true;
                }
            }
            Object rawCore = personGetAiCoreId.invoke(person);
            String core = rawCore == null ? "" : rawCore.toString();
            if (!badEntry && skills.isEmpty() && core.isEmpty()) {
                return ""; // the engine's default captain — no officer to mirror
            }
            if (badEntry) {
                noteCaptainSkip(variantId, CaptainSkipCause.SKILL_ENTRY,
                        " skills=" + skills.size());
                return "";
            }
            int level = ((Number) statsGetLevel.invoke(stats)).intValue();
            Object personality = personGetPersonalityApi.invoke(person);
            Object personalityId = personality == null ? null : personalityGetId.invoke(personality);
            Object name = personGetName.invoke(person);
            Object first = name == null ? null : fullNameGetFirst.invoke(name);
            Object last = name == null ? null : fullNameGetLast.invoke(name);
            Object portrait = personGetPortraitSprite.invoke(person);
            String line = OfficerRosterCodec.encodeEngineCaptain(variantId,
                    first == null ? "" : first.toString(), last == null ? "" : last.toString(),
                    personalityId == null ? "" : personalityId.toString(),
                    portrait == null ? null : portrait.toString(), level, skills, core);
            if (line.isEmpty()) {
                noteCaptainSkip(variantId, CaptainSkipCause.ENCODE_REJECTED, " level=" + level
                        + " skills=" + skills.size() + " core=" + (core.isEmpty() ? 0 : 1));
                return "";
            }
            captainLines++;
            return line;
        } catch (Throwable readFailure) {
            Throwable root = readFailure instanceof InvocationTargetException
                    && readFailure.getCause() != null ? readFailure.getCause() : readFailure;
            noteCaptainSkip(variantId, CaptainSkipCause.READ_FAILED,
                    " reason=" + root.getClass().getSimpleName());
            return "";
        }
    }

    /**
     * Count one fail-closed captain skip; log ONCE per variant per round with
     * bounded tokens only (the cause enum, the engine variant id, counts, a Throwable SimpleName)
     * — never the person's name, personality or portrait. ENCODE_REJECTED logs
     * ONCE per round - it repeats on every hull whose captain passes the codec's level bound (an all-skills serve: every
     * seat hull), and the round's {@code captain_meter} counts the rest.
     */
    private static void noteCaptainSkip(String variantId, CaptainSkipCause cause, String detail) {
        captainSkipped++;
        boolean perRound = cause == CaptainSkipCause.ENCODE_REJECTED;
        if (perRound ? !encodeRejectLogged : captainSkipLogged.add(variantId)) {
            encodeRejectLogged |= perRound;
            System.out.println("growth captain SKIPPED variant=" + variantId + " cause=" + cause
                    + detail + " skipped_total=" + captainSkipped + " (officerLine=\"\"; logged once per "
                    + (perRound ? "round, captain_meter counts the rest)" : "variant per round)"));
        }
    }

    /** The per-round captain meter, one line at the round's uninstall. */
    private static void logCaptainMeter() {
        System.out.println("growth-seam captain_meter lines=" + captainLines
                + " skipped=" + captainSkipped
                + " (engine captains read onto growth officerLine this round)");
    }

    /** The captain read's per-round state (install + uninstall both clear). */
    private static void resetCaptainState() {
        captainSkipLogged.clear();
        encodeRejectLogged = false;
        captainLines = 0;
        captainSkipped = 0;
    }

    /** Proxy handler: observe, then preserve+delegate to the prior listener (unwrapped). */
    private static final class GrowthListenerHandler implements InvocationHandler {
        private final Object prior;

        GrowthListenerHandler(Object prior) {
            this.prior = prior;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            boolean addedCall = "objectAdded".equals(name);
            if (addedCall || "objectRemoved".equals(name)) {
                try {
                    observe(args[0], addedCall);
                } catch (Throwable observeFailure) {
                    // observe() already swallows; this is the belt for the buckle.
                    System.out.println("growth-seam observe_threw reason=" + observeFailure);
                }
                if (prior != null) {
                    try {
                        method.invoke(prior, args);
                    } catch (InvocationTargetException delegateFailure) {
                        // The prior listener's own throw, exactly as the engine would see it.
                        throw delegateFailure.getCause() == null
                                ? delegateFailure : delegateFailure.getCause();
                    }
                }
                return null;
            }
            if ("equals".equals(name)) {
                return proxy == args[0];
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("toString".equals(name)) {
                return "cc-engine-born-growth-seam";
            }
            return null;
        }
    }
}
