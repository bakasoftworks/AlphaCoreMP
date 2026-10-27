package cc.starsector.server.bridge;

import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.match.MatchDescriptors;
import cc.starsector.server.platform.HeadlessCombatContext;
import cc.starsector.server.platform.RealEngineContentInit;
import cc.starsector.server.platform.RealEngineGlSubstrate;
import cc.starsector.server.platform.RealEngineRoundHeapDrain;
import cc.starsector.server.runtime.OnFireAttachSweep;
import cc.starsector.server.runtime.ServerConfig;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Stage (iii) of build-step 2a-&beta;: the product-owned factory that constructs a real
 * game {@code CombatEngine} headless and wires it into an {@link EngineCombatBinding}.
 * This is the only step between the proven L3 substrate / content-init and a real-engine
 * {@code EngineCombatBinding} produced from {@code server/src} (until it existed,
 * {@code run-real-dedicated-server.ps1} stayed PENDING exit 2).
 *
 * <p><b>{@code create()} is the {@code DedicatedCombatServerMain --binding-factory} contract</b>
 * ({@code DedicatedCombatServerMain.buildBinding}): a no-arg public static
 * factory returning an {@link EngineCombatBinding}, the exact shape {@code FakeEngineBindingFactory.create()}
 * provides game-free. Where the fake builds a deterministic in-memory engine, this builds the
 * <em>real</em> one — and is therefore reached only with the game jars on the classpath, the
 * LWJGL2 native available, and the L3 GL context up (the dedicated-server launch contract,
 * {@code run-real-dedicated-server.ps1}).
 *
 * <p><b>Construct order (load-bearing, ported byte-identical off the MEASURE lane).</b>
 * {@code create()} drives, in order:
 * <ol>
 *   <li>{@link RealEngineGlSubstrate#ensureContextCurrent()} — the L3 off-screen Pbuffer GL
 *       context (stage 1, idempotent). Must be the same thread that later drives {@code advance}.</li>
 *   <li>{@link RealEngineContentInit#ensureInitialized()} — settings-publish / mod-register
 *       preamble + the real {@code ResourceLoaderState.init(Map)} texture drive (stage 2,
 *       idempotent, fail-closed).</li>
 *   <li><b>Background-sprite seed (Option-C).</b> The {@code CombatEngine} ctor at
 *       offset-965 does an {@code ifnonnull} on a static background-{@code Sprite} field; when
 *       null it calls {@code setDefaultBackground &rarr; replaceBackground &rarr;} an internal super-ctor {@code &rarr;
 *       TextureLoader.glGenTextures} (the pinned in-ctor GL crossing). Pre-seeding that field
 *       non-null with a ctor-less {@code Unsafe.allocateInstance} dummy {@code Sprite} (never
 *       dereferenced on the paused, non-rendering advance path) makes the ctor skip the crossing.
 *       Driven through the product {@link HeadlessCombatContext} seam (the documented adapter)
 *       and <b>kept installed</b> for the process lifetime (never closed — like the GL context,
 *       it survives reset rounds; session-lifecycle design).</li>
 *   <li>{@code getInstance()} — the lazy private-ctor singleton ({@code engineClass.getMethod("getInstance")},
 *       resolved reflectively); confirmed stable by a double-invoke + {@code hasInstance()}.</li>
 *   <li>{@code setCombatMap(new B())} then {@code init()} — install a real combat space + run the
 *       engine's own init.</li>
 *   <li><b>Deploy the match roster (L1 Option A construction-time lane).</b>
 *       {@link RealEngineDemoRoster#deployMatchRoster()} spawns the roster (default: the fixed 1v1,
 *       owned ids 1L/2L; descriptor-configured: the authored N-seat/AI roster) into the
 *       engine through the proven engine-own deploy lane so the served combat
 *       has real ships and the engine's player seat is a real owned ship — making the later live
 *       {@code getPlayerShip()} reads safe (field {@code #671} non-null, no fallback "Command
 *       Shuttle" fabrication). The 2b reset loop re-deploys per round via the same
 *       method after {@link RealEngineDemoRoster#resetForRedeploy()}.</li>
 *   <li>Resolve every descriptor handle the proven builder chain consumes,
 *       then {@code build()} the binding (motion-write seam + {@code ownedShipIds} = the deployed
 *       SEAT wire ids — the pose-driven superset, so the server-authoritative match owns every
 *       seat and may write authoritative poses for predict-and-reconcile).</li>
 * </ol>
 *
 * <p><b>Option R — literal identities, pinned + centralized.</b>
 * Every engine-internal {@code com.fs.*} identity fed into live reflection is rendered <em>literally</em>,
 * byte-identical to the MEASURE lane the GREEN was proved under (resolving by a different
 * method risks a different overload, so GREEN might not transfer). They are
 * confined to the {@code PINNED 0.98a-RC8} constant block; the adapter BOUNDARY
 * ({@link EngineCombatBinding} + the {@code *View} interfaces) gains no literal — this factory is
 * the documented "caller" that resolves version-specific handles and hands them in.
 * Descriptor-grade resolution is the documented upgrade for the next game RC (standing
 * portability-drill trigger), not now.
 *
 * <p><b>Required vs fail-soft handles.</b> The eight handles the {@link EngineCombatBinding}
 * constructor requires non-null (engine, advanceInner, getObjects, repositoryGetList, shipClass,
 * fleetManagers, combatMap, playerShip) are resolved directly — any failure raises
 * {@link RealEngineCombatBindingFactoryException}. Every optional view tier (durability, identity,
 * lifecycle, flux/shield, entity lists, weapon mounts, ship system, profile, maneuver,
 * motion-write) is resolved fail-soft per group exactly as the scout lane does — but disposition
 * is knob-gated since (83): under {@code cc.bind.strict} (default STRICT —
 * pinned RC8 resolves every tier, adjudicated) an unresolved tier fails the boot
 * loudly; {@code bind.strict=false} restores announce-and-degrade (null group => that view
 * unavailable). The pilot-command tier ({@code commands[0]}) is REQUIRED regardless of the knob
 * (SV-1): with it null every {@code applyPilotIntent} frame is rejected, so no faithful degraded
 * serve exists. The allegiance tier and the engine-state v6 tier — both
 * absent from the original clean extraction the scout chain wired — are now
 * bound here via their fail-soft resolvers ({@code .shipAllegiance(...)} / {@code .shipEngineState(...)}),
 * the redo-when-better follow-ons that policy anticipated. The combat-outcome tier stays unwired
 * <em>by design</em>: the raw engine {@code getWinningSideId()} field is fleet-manager-derived and blind
 * to the object-repository roster headless, so the binding derives the outcome
 * from the allegiance roster ({@link EngineCombatBinding#combatOutcomeFromRosterOrNull()}) instead.
 *
 * <p><b>Idempotent.</b> {@code getInstance()} is a process singleton and the substrate / content
 * init are process-global, so {@code create()} constructs once and caches the binding; a second
 * call returns the same instance rather than re-seeding / re-setting the map / re-init'ing.
 *
 *
 *
 *
 */
public final class RealEngineCombatBindingFactory {

    // ---- PINNED 0.98a-RC8 (descriptor-grade identity owed at the next RC — standing
    // portability-drill trigger). Option R: literal here, boundary clean. ----

    /** Combat engine singleton (private lazy ctor reached via {@code getInstance()}). */
    private static final String ENGINE_CLASS = "com.fs.starfarer.combat.CombatEngine";
    /**
     * Campaign engine singleton — the game's ONE {@code SectorAPI} implementation
     * ({@code setInstance} → {@code Global.setSector(instance)}). The real
     * client mints a bare instance at title time, so title-screen missions — the mode this server
     * is faithful to — always see a non-null {@code Global.getSector()}; headless it was never
     * minted, and vanilla combat scripts that touch the sector mid-round NPE'd
     * ({@code getSector().getListenerManager()} on the shard-split spawn chain).
     */
    private static final String CAMPAIGN_ENGINE_CLASS = "com.fs.starfarer.campaign.CampaignEngine";
    /** The api static holder — {@code getSector()} is the null those sector NPEs die on. */
    private static final String GLOBAL_CLASS = "com.fs.starfarer.api.Global";
    /** Combat map / space; default no-arg ctor builds terrain + fog owners {0,1}. */
    private static final String COMBAT_MAP_CLASS = "com.fs.starfarer.combat.A.B";
    /** The input-events "amount" arg type for {@code advanceInner(float, amount)}. */
    private static final String AMOUNT_CLASS = "com.fs.starfarer.util.A.new";
    /** Background sprite; the static field the ctor's offset-965 {@code ifnonnull} guards. */
    private static final String SPRITE_CLASS = "com.fs.graphics.Sprite";
    /** Concrete combat ship type; the {@code getList(Class)} key + the per-ship handle owner. */
    private static final String SHIP_CLASS = "com.fs.starfarer.combat.entities.Ship";
    /**
     * Engine-internal combat-UI interface ({@code CombatEngine.getCombatUI()} return type /
     * {@code setCombatUI(K)} arg; descriptor {@code (Lcom/fs/starfarer/combat/K;)V}). A public
     * interface (it extends an internal interface and {@code CombatUIAPI}) — a valid {@link java.lang.reflect.Proxy}
     * target — whose {@code getEntityToFollow()} the damage path reads.
     */
    private static final String COMBAT_UI_INTERFACE_CLASS = "com.fs.starfarer.combat.K";
    /**
     * Concrete combat viewport ({@code CombatEngine.getViewport()} return; constructed non-null in
     * the engine ctor, independent of any UI). The damage-path debris FX reads
     * {@code viewport.isNearViewport(...)}.
     */
    private static final String COMBAT_VIEWPORT_CLASS = "com.fs.starfarer.combat.CombatViewport";
    /**
     * Debris particle FX system ({@code CombatEngine.getDebrisSystem()} return; constructed non-null
     * in the engine ctor). Its {@code spawnDebris} (reached from {@code Ship.applyDamageInner} on
     * impact) reads a {@code viewport} field the engine's own {@code advance()} lazily populates from
     * {@code getViewport()} — but the headless serve loop drives {@code advanceInner} (never the
     * FX-system {@code advance()}), so the field stays null until impact.
     */
    private static final String DEBRIS_SYSTEM_CLASS = "com.fs.starfarer.renderers.damage.DebrisParticleSystem";

    // ---- Public API + LWJGL, plain names (identity-clean even under descriptor-grade). ----
    private static final String ARMOR_GRID_API = "com.fs.starfarer.api.combat.ArmorGridAPI";
    private static final String SHIP_HULL_SPEC_API = "com.fs.starfarer.api.combat.ShipHullSpecAPI";
    private static final String FLUX_TRACKER_API = "com.fs.starfarer.api.combat.FluxTrackerAPI";
    private static final String SHIELD_API = "com.fs.starfarer.api.combat.ShieldAPI";
    private static final String WEAPON_API = "com.fs.starfarer.api.combat.WeaponAPI";
    private static final String WEAPON_GROUP_API = "com.fs.starfarer.api.combat.WeaponGroupAPI";
    private static final String SHIP_API = "com.fs.starfarer.api.combat.ShipAPI";
    private static final String COMBAT_ENTITY_API = "com.fs.starfarer.api.combat.CombatEntityAPI";
    /** GPEE-v3 fresh-spawn bearing read owner ({@code getElapsed}/{@code getWeapon}). */
    private static final String DAMAGING_PROJECTILE_API = "com.fs.starfarer.api.combat.DamagingProjectileAPI";
    private static final String HULL_SIZE_ENUM = "com.fs.starfarer.api.combat.ShipAPI$HullSize";
    private static final String SHIP_COMMAND_ENUM = "com.fs.starfarer.api.combat.ShipCommand";
    // The vanilla retreat engage statics' declaring class.
    private static final String RETREAT_MANEUVER_CLASS =
            "com.fs.starfarer.combat.ai.movement.maneuvers.RetreatManeuver";
    // The concrete class declaring setCurrentActive(int) — the tube-pointer write
    // is NOT WeaponGroupAPI-level (getType/getActiveWeapon/getWeaponsCopy are).
    private static final String WEAPON_GROUP_CLASS = "com.fs.starfarer.combat.systems.WeaponGroup";
    /** The group-type enum {@code WeaponGroupAPI.getType()} returns. */
    private static final String WEAPON_GROUP_TYPE_ENUM = "com.fs.starfarer.api.loading.WeaponGroupType";
    /** Ship-AI plugin (the arg type {@code Ship.setShipAI} takes) — the (B) AI-suppression handle owner. */
    private static final String SHIP_AI_PLUGIN_API = "com.fs.starfarer.api.combat.ShipAIPlugin";
    private static final String SHIP_SYSTEM_API = "com.fs.starfarer.api.combat.ShipSystemAPI";
    /** Engine controller + its per-engine nested interface (the v6 engine-state tier reads). */
    private static final String SHIP_ENGINE_CONTROLLER_API = "com.fs.starfarer.api.combat.ShipEngineControllerAPI";
    private static final String SHIP_ENGINE_API = "com.fs.starfarer.api.combat.ShipEngineControllerAPI$ShipEngineAPI";
    private static final String MUTABLE_SHIP_STATS_API = "com.fs.starfarer.api.combat.MutableShipStatsAPI";
    private static final String MUTABLE_STAT_API = "com.fs.starfarer.api.combat.MutableStat";
    private static final String STAT_BONUS_API = "com.fs.starfarer.api.combat.StatBonus";
    private static final String LWJGL_VECTOR2F_CLASS = "org.lwjgl.util.vector.Vector2f";

    /** Kept process-global so the background-sprite seed is never restored (survives reset rounds). */
    private static HeadlessCombatContext preConstructContext;
    /** Kept process-global so the damage-path FX seams are never restored (survive reset rounds). */
    private static HeadlessCombatContext postInitFxContext;
    /** Cached single binding (idempotent: {@code getInstance()} is a process singleton). */
    private static EngineCombatBinding binding;
    /** Whether {@link #binding} came from the gated npeer overload (mode-mix guard). */
    private static boolean gatedConstruct;
    /** Set when a gated zero-hull construct deferred the message-widget self-check. */
    private static boolean messageWidgetSelfCheckDeferred;
    /** Cached round-boundary heap drain (resolved once against the singleton engine). */
    private static RealEngineRoundHeapDrain roundHeapDrain;

    /** Fail-closed wrapper so caller code stays off the engine-internal/reflective surface. */
    public static final class RealEngineCombatBindingFactoryException extends RuntimeException {
        public RealEngineCombatBindingFactoryException(String message) {
            super(message);
        }

        public RealEngineCombatBindingFactoryException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private RealEngineCombatBindingFactory() {
    }

    /**
     * Construct the real headless {@code CombatEngine} and wire it into an
     * {@link EngineCombatBinding} (idempotent: constructs once, caches the binding). Must be
     * called on the thread that brought up {@link RealEngineGlSubstrate} (GL thread affinity).
     *
     * @throws RealEngineCombatBindingFactoryException if the substrate/content init fails, the
     *         engine cannot be constructed/registered, the combat map will not install, or any
     *         binding-required handle cannot be resolved — never a half-built binding.
     */
    public static synchronized EngineCombatBinding create() {
        if (binding != null) {
            if (gatedConstruct) {
                throw new RealEngineCombatBindingFactoryException(
                        "create() after a gated create(occupiedSeatIds) construct — the npeer boot"
                                + " owns this process's binding; a legacy full-deploy caller must not"
                                + " share it (mode guard)");
            }
            return binding;
        }
        return createInternal(null);
    }

    /**
     * The npeer/gated construct (the zero-hull boot): identical to
     * {@link #create()} in every stage except the t=0 roster deploy, which is the occupancy-gated
     * {@link RealEngineDemoRoster#deployMatchRoster(SortedSet)} — descriptor-REQUIRED, deploys only
     * the occupied seats (an EMPTY set is legal: the gauntlet host boots pre-first-claim onto a
     * zero-hull battlefield; hulls arrive per claim via {@link RealEngineDemoRoster#midDeploySeat}).
     * With zero hulls deployed the engine player seat stays UNSET, so no serve-path read may invoke
     * {@code getPlayerShip()} while the repository is shipless (the pre-roster fabrication nulls the
     * combat map), and the message-widget self-check is deferred to
     * {@link #runDeferredMessageWidgetShipPostSelfCheck()} (no ship to post yet). Once-per-process
     * and fail-closed against mode mixing: a second create of either shape THROWS rather than
     * handing back a binding whose roster shape the caller did not choose.
     */
    public static synchronized EngineCombatBinding create(SortedSet<Integer> occupiedSeatIds) {
        if (occupiedSeatIds == null) {
            throw new RealEngineCombatBindingFactoryException(
                    "occupiedSeatIds must be non-null (the no-arg create() is the legacy"
                            + " full-deploy construct)");
        }
        if (binding != null) {
            throw new RealEngineCombatBindingFactoryException(
                    "gated create(occupiedSeatIds) with a binding already constructed — the gated"
                            + " construct is once-per-process; post-boot occupancy changes ride"
                            + " midDeploySeat / the per-round reset");
        }
        return createInternal(occupiedSeatIds);
    }

    private static EngineCombatBinding createInternal(SortedSet<Integer> occupiedOrNull) {
        // Stages 1 + 2 (both idempotent, fail-closed): GL context, then content/textures.
        RealEngineGlSubstrate.ensureContextCurrent();
        RealEngineContentInit.ensureInitialized();

        // The confessing-gun boot-order fix: the once-per-JVM onFire emitter
        // sweep runs HERE — post content init (specs live), STRICTLY before ANY weapon build.
        // The former call site (PilotIntentCombatServerMain, post-create) held that law for
        // fly-ins only: deployMatchRoster below constructs every t=0 AI roster row's
        // Ship+weapons synchronously, and the weapon ctor latches the onFire plugin ONCE
        // so round-1 t=0 AI weapons latched null and never confessed. The
        // sweep self-guards (knob-gated, triple fail-soft, dup-safe), so every factory boot
        // shape may sweep; in a JVM whose specs the cc_client S3 sweep already claimed (no
        // cc.jvm.role=server stamp: probe/measure mains), wrap-don't-replace skips every spec
        // and the sink never latches — behavior unchanged there.
        OnFireAttachSweep.runIfEnabled();

        ClassLoader loader = RealEngineCombatBindingFactory.class.getClassLoader();
        try {
            Class<?> engineClass = Class.forName(ENGINE_CLASS, true, loader);

            seedBackgroundSprite(engineClass, loader);

            // Boot the bare CampaignEngine BEFORE the combat
            // engine mint — its setInstance() re-mints the CombatEngine singleton,
            // which after our mint would orphan the binding's instance-bound handles; in this order
            // the getInstance() below simply picks up the instance setInstance just minted.
            bootBareCampaignEngine(loader);

            // ---- Construct via getInstance() singleton (lazy private ctor + Global.setCombatEngine) ----
            Method hasInstance = engineClass.getMethod("hasInstance");
            Method getInstance = engineClass.getMethod("getInstance");
            Object engine = getInstance.invoke(null);
            Object engineAgain = getInstance.invoke(null);
            boolean stable = engine != null && engine == engineAgain
                    && Boolean.TRUE.equals(hasInstance.invoke(null));
            if (!stable) {
                throw new RealEngineCombatBindingFactoryException(
                        "CombatEngine.getInstance() did not register a single stable instance"
                                + " (engine_null=" + (engine == null) + ")");
            }

            // ---- setCombatMap(new B()) then init() (real combat space + engine init) ----
            Class<?> mapClass = Class.forName(COMBAT_MAP_CLASS, true, loader);
            Constructor<?> mapCtor = mapClass.getDeclaredConstructor();
            mapCtor.setAccessible(true);
            Object combatMap = mapCtor.newInstance();
            // Size the map BEFORE setCombatMap, the way
            // every engine mission path does (initMap precedes setCombatMap) —
            // so the two fog trackers setCombatMap
            // mints are born at the descriptor size and the BOOT
            // deploy + every warm-up re-deploy + the round-0 roster all see mapHeight=2*half.
            // Before this the served t=0 roster deployed on the ±1000 default map (side 1 at
            // y=+3000, burned in) and only the round-open re-apply (PersistentPilotIntentCombatServer
            // `npeer-map-bounds`) widened the map AFTERWARDS: the served hive sat 1.8-3k su from
            // the station where vanilla's rests at ~4450.
            // The round-open apply stays as the idempotent per-round re-apply (an ops map-size
            // change lands there); descriptor-less boots (demo/probe mains) keep the default map.
            float createHalf = Float.NaN;
            MatchDescriptor createDescriptor =
                    RealEngineDemoRoster.resolveConfiguredDescriptorOrNull("map-bounds-at-create");
            if (createDescriptor != null) {
                createHalf = MatchDescriptors.serveMapHalf(createDescriptor);
                EngineCombatBinding.findMapBoundsSetter(mapClass)
                        .invoke(combatMap, -createHalf, createHalf, -createHalf, createHalf);
            }
            Method getCombatMap = engineClass.getMethod("getCombatMap");
            engineClass.getMethod("setCombatMap", mapClass).invoke(engine, combatMap);
            if (getCombatMap.invoke(engine) != combatMap) {
                throw new RealEngineCombatBindingFactoryException(
                        "CombatEngine.setCombatMap/getCombatMap round-trip did not return the installed map");
            }
            if (createDescriptor != null) {
                float mapWidth = ((Number) engineClass.getMethod("getMapWidth").invoke(engine)).floatValue();
                float mapHeight = ((Number) engineClass.getMethod("getMapHeight").invoke(engine)).floatValue();
                if (mapWidth != 2.0f * createHalf || mapHeight != 2.0f * createHalf) {
                    throw new RealEngineCombatBindingFactoryException(
                            "map_bounds_at_create half=" + createHalf + " did not take: width="
                                    + mapWidth + " height=" + mapHeight);
                }
                System.out.println("real-engine-binding-factory map_bounds_at_create half=" + createHalf
                        + " width=" + mapWidth + " height=" + mapHeight
                        + " (descriptor-derived, sized BEFORE setCombatMap)");
            }
            logCombatMapIdentity("post-setCombatMap", getCombatMap, engine, combatMap);
            engineClass.getMethod("init").invoke(engine);
            logCombatMapIdentity("post-init", getCombatMap, engine, combatMap);

            // ---- Install the engine's-own damage-path FX seams ----
            // The headless dedicated server never brings up the render/UI layer, so two null
            // presentation handles on the projectile-IMPACT damage path (Ship.applyDamageInner)
            // throw: (a) getCombatUI() is null (a camera-follow COMPARISON), and
            // (b) DebrisParticleSystem.viewport is null (an isNearViewport FX cull). Installing the
            // engine's-own combat-UI proxy + pre-populating the debris viewport (the same value the
            // engine's own DebrisParticleSystem.advance() lazily sets) makes both null-safe WITHOUT
            // faking any combat outcome -- the real damage math still runs. Must come after init()
            // (engine present; getCombatUI() + the debris viewport field still null -- the seams
            // refuse to overwrite an already-bound handle).
            seedDamagePathFxSeams(engineClass, engine, loader);

            // ---- Required handles (binding ctor requires these non-null) ----
            Class<?> amountClass = Class.forName(AMOUNT_CLASS, true, loader);
            Constructor<?> amountCtor = amountClass.getDeclaredConstructor();
            amountCtor.setAccessible(true);
            Object amount = amountCtor.newInstance();
            Method advanceInner = engineClass.getMethod("advanceInner", float.class, amountClass);
            Method advanceOuter = engineClass.getMethod("advance", float.class, amountClass);
            // Engine pause control. Resolved required, not fail-soft: without setPaused
            // the serve path advances on advanceInner's PAUSED branch forever (drains pending-adds
            // → ships present, but skips the ship-advance/AI/physics pass → static served ships).
            // Both are public CombatEngineAPI surface, so a
            // resolution miss is a real version drift worth failing the build loudly, not silently.
            Method engineIsPaused = engineClass.getMethod("isPaused");
            Method engineSetPaused = engineClass.getMethod("setPaused", boolean.class);
            // Public CombatEngineAPI.getCustomData() — the mote-target read.
            // Optional by design (a miss = the payload stays off), unlike the pause pair above.
            Method engineGetCustomData;
            try {
                engineGetCustomData = engineClass.getMethod("getCustomData");
            } catch (NoSuchMethodException e) {
                engineGetCustomData = null;
            }
            Method getObjects = engineClass.getMethod("getObjects");
            Method getFleetManagers = engineClass.getMethod("getFleetManagers");
            Method getPlayerShip = engineClass.getMethod("getPlayerShip");
            Class<?> shipClass = Class.forName(SHIP_CLASS, false, loader);
            Object repo = getObjects.invoke(engine);
            if (repo == null) {
                throw new RealEngineCombatBindingFactoryException(
                        "CombatEngine.getObjects() returned null after init()");
            }
            Method repoGetList = repo.getClass().getMethod("getList", Class.class);

            // Construct-time time multiplier. Do NOT read this via getPlayerShip() here.
            // Pre-roster there is no real player ship, so getPlayerShip() lazily fabricates the
            // engine's no-player fallback "Command Shuttle" (CombatEngine.getPlayerShip ->private
            // Object(): SpecStore "shuttlepod_xfer" + Ship.init()), and that creation path NULLS the
            // combat map (#450) -- measured: liveMap 1004009692 -> 0 across this single call, which is
            // exactly the combatSpaceReady=false the serve gate refused on. The proven
            // scout MEASURE lane never called getPlayerShip() at setup. 1.0 is the faithful pin for
            // the engine-global/step-size lane (shared-time-contract: engine-global timeMult stays 1);
            // the real per-ship multiplier (!=1.0 under a phase cloak / time-dilation system) is read
            // LIVE per ship by the binding's motion view via getMutableStats().getTimeMult()
            // (shipTimeMultRead below) -- a lane that never touches getPlayerShip(),
            // so the hazard cannot recur.
            float realTimeMult = 1.0f;

            // ---- Optional view tiers (fail-soft per group, exactly like the scout lane) ----
            Method[] motion = resolveMotionReadHandles(shipClass);
            Method[] durability = resolveDurabilityHandles(loader, shipClass);
            Method[] identity = resolveIdentityHandles(loader, shipClass);
            Method[] lifecycle = resolveLifecycleHandles(shipClass);
            // Bind the allegiance read too. Without it shipGetOwner stays null, so
            // EngineCombatBinding.shipAllegiancesOrNull() returns null and the perSideRoundEnd one-side-
            // eliminated matchOver trigger NEVER fires on the real engine -- the RUN 2 round boundary then
            // deadlocked (client stops at the lifecycle-driven entityCount shrink waiting for MRST, server
            // never reaches matchOver). getOwner() is the same descriptor-grade public read as isAlive().
            Method allegianceGetOwner = resolveAllegianceGetOwner(shipClass);
            // Bind the engine-state (flameout/disabled) v6 tier read too,
            // mirroring the allegiance retrofit above. Without it the 6 engine-state handles stay
            // null, so EngineCombatBinding.shipEngineStatesOrNull() returns null, EngineBackedLiveWorldSnapshot-
            // Source never folds the v6 tier, and a Hellbore-grade engine hit never crosses the wire as
            // server truth (the desync this tier exists to close). All 6 are public-API reads
            // (ShipAPI.getEngineController + ShipEngineControllerAPI[$ShipEngineAPI]), the same surface the
            // proven scout predict lane binds.
            Method[] engineState = resolveEngineStateHandles(loader, shipClass);
            // Bind the maneuver-command (schema v7) read — the 7 engine-controller
            // maneuver booleans whose per-frame command truth drives plume/glow/contrail (and
            // engine audio) presentation on a predictive client's foe replica. Controller read,
            // not an UP-mask echo, so seat-piloted AND AI ships (the D4 gauntlet) carry alike.
            Method[] maneuverCommand = resolveManeuverCommandHandles(loader, shipClass);
            Method[] flux = resolveFluxAndShieldHandles(loader, shipClass);
            Method[] entityLists = resolveCombatEntityListHandles(engineClass);
            Method[] weaponMounts = resolveWeaponMountHandles(loader, shipClass);
            Method[] weaponSlotIdentity = resolveWeaponSlotIdentityHandles(loader);
            Object[] weaponGroupMirror = resolveWeaponGroupMirrorHandles(loader, shipClass);
            Object[] weaponGroupTubeMirror = resolveWeaponGroupTubeMirrorHandles(loader);
            Method[] edge = resolveGameplayEdgeReadHandles(loader, shipClass);
            Method[] profile = resolveProfileHandles(loader, shipClass);
            Method[] maneuver = resolveShipManeuverHandles(loader, shipClass);
            Method[] shipCrReads = resolveShipCrReadHandles(loader, shipClass);
            Object[] commands = resolveCommandHandles(loader, shipClass);
            Object[] motionWrite = resolveMotionWriteSeam(loader, shipClass);
            Method facingWrite = resolveFacingWriteHandle(shipClass);
            Method travelDriveRead = resolveTravelDriveReadHandle(shipClass);
            Object[] fireLockReads = resolveFireLockHandles(shipClass);
            Method[] mouseTargetSeam = resolveMouseTargetSeam(loader, shipClass);
            Method systemActivationGuard = resolveSystemActivationGuard(loader);
            Method[] aiSuppression = resolveAiSuppressionHandles(loader, shipClass);
            Method[] retreatApply = resolveRetreatApplySeam(loader, shipClass);
            Method[] shipTargetSeam = resolveShipTargetSeam(loader, shipClass);

            // ---- Deploy the match roster over the engine (L1 Option A) ----
            // Done at construction time (t=0) via the proven engine-own deploy lane so the served
            // combat has real ships (earlier builds served an empty roster) and the engine player
            // seat is a real owned ship (makes the binding's live getPlayerShip() reads safe). The
            // deploy installs a fresh repository + addFleet/spawnFleetMember; the binding below reads
            // getObjects() live, so it sees the roster'd repository. Default = the fixed two-ship
            // 1v1; a configured cc.match.descriptor drives the authored N-roster.
            if (occupiedOrNull == null) {
                RealEngineDemoRoster.deployMatchRoster();
            } else {
                RealEngineDemoRoster.deployMatchRoster(occupiedOrNull);
            }
            logCombatMapIdentity("post-roster-deploy", getCombatMap, engine, combatMap);
            if (RealEngineDemoRoster.firstDeployedShipOrNull() != null) {
                selfCheckMessageWidgetShipPost(engineClass, engine, loader);
            } else {
                // Gated zero-hull boot: nothing deployed to post yet — the check
                // runs at the round's first fly-in via runDeferredMessageWidgetShipPostSelfCheck().
                messageWidgetSelfCheckDeferred = true;
                System.out.println("real-engine-binding-factory message-widget ship-post self-check"
                        + " DEFERRED (zero-hull gated boot; first fly-in runs it)");
            }

            logCombatMapIdentity("pre-build", getCombatMap, engine, combatMap);
            Method[] projectileSpawnReads = resolveProjectileSpawnReadHandles(loader);
            Method weaponProjectileSpeed = resolveWeaponProjectileSpeed(loader);
            Object[] weaponSpecClass = resolveWeaponSpecClassHandles(loader);
            Method shipTimeMultRead = resolveShipTimeMultReadHandle(loader);
            Method phaseCloakToggle = resolvePhaseCloakHandle(shipClass);
            Method pullBackFightersRead = resolvePullBackFightersRead(shipClass);
            Method shipIsPhasedRead = resolveShipIsPhasedHandle(shipClass);
            Method engineFastTimeRead = resolveIsInFastTimeAdvance(engineClass);

            // (83) SV-1: the pilot-command tier is REQUIRED, knob or no knob —
            // with giveCommand unresolved every applyPilotIntent frame is rejected, so a
            // "degraded" serve would be a server silently ignoring every pilot. No faithful
            // degraded serve exists for this tier.
            if (commands[0] == null) {
                throw new RealEngineCombatBindingFactoryException(
                        "pilot-command tier unresolved — server cannot apply pilot intent");
            }
            // (83) SV-2 (supersedes the (81) announce-only pass): the optional
            // tiers above resolve fail-soft, but a miss silently serves a LOWER schema/behavior
            // tier to every client for the whole boot (the "quietly landed on an older protocol"
            // class, e.g. post-game-patch). Default = strict (fail the boot loudly);
            // cc.bind.strict=false restores announce+degrade.
            requireResolved("motion-read", motion);
            requireResolved("durability", durability);
            requireResolved("identity", identity);
            requireResolved("lifecycle", lifecycle);
            requireResolved("allegiance", allegianceGetOwner);
            requireResolved("engine-state", engineState);
            requireResolved("maneuver-command", maneuverCommand);
            requireResolved("flux-shield", flux);
            requireResolved("entity-lists", entityLists);
            requireResolved("weapon-mounts", weaponMounts);
            requireResolved("weapon-group-mirror", weaponGroupMirror);
            requireResolved("weapon-group-tube-mirror", weaponGroupTubeMirror);
            requireResolved("gameplay-edge", edge);
            requireResolved("profile", profile);
            requireResolved("ship-maneuver", maneuver);
            requireResolved("ship-cr", shipCrReads);
            requireResolved("commands", commands);
            requireResolved("motion-write", motionWrite);
            requireResolved("facing-write", facingWrite);
            requireResolved("travel-drive", travelDriveRead);
            requireResolved("fire-lock", fireLockReads);
            requireResolved("mouse-target", mouseTargetSeam);
            requireResolved("system-activation-guard", systemActivationGuard);
            requireResolved("ai-suppression", aiSuppression);
            requireResolved("retreat-apply", retreatApply);
            requireResolved("ship-target", shipTargetSeam);
            requireResolved("projectile-spawn", projectileSpawnReads);
            requireResolved("weapon-projectile-speed", weaponProjectileSpeed);
            requireResolved("weapon-spec-class", weaponSpecClass);
            requireResolved("ship-time-mult", shipTimeMultRead);
            requireResolved("phase-cloak-toggle", phaseCloakToggle);
            requireResolved("ship-is-phased", shipIsPhasedRead);
            requireResolved("engine-fast-time", engineFastTimeRead);
            EngineCombatBinding built = EngineCombatBinding.builder()
                    .engine(engine)
                    .advanceInner(advanceInner, amount)
                    .advanceOuter(advanceOuter, amount)
                    .repository(getObjects, repoGetList, shipClass)
                    .fleetManagers(getFleetManagers)
                    .combatMap(getCombatMap)
                    .playerShip(getPlayerShip)
                    .pauseControl(engineIsPaused, engineSetPaused)
                    .playerShipLocation(motion[0])
                    .playerShipVelocity(motion[1])
                    .playerShipFacing(motion[2])
                    .playerShipAngularVelocity(motion[3])
                    .shipDurability(durability[0], durability[1], durability[2], durability[3],
                            durability[4])
                    .shipIdentity(identity[0], identity[1])
                    .shipLifecycle(lifecycle[0], lifecycle[1])
                    .shipAllegiance(allegianceGetOwner, lifecycle[1])
                    .shipEngineState(engineState[0], engineState[1], engineState[2],
                            engineState[3], engineState[4], engineState[5])
                    .shipManeuverCommands(maneuverCommand[0], maneuverCommand[1],
                            maneuverCommand[2], maneuverCommand[3], maneuverCommand[4],
                            maneuverCommand[5], maneuverCommand[6], maneuverCommand[7])
                    .shipFlux(flux[0], flux[1], flux[2], flux[3], flux[4], flux[5], flux[6])
                    .shipShield(flux[7], flux[8], flux[9], flux[10])
                    .combatEntityLists(entityLists[0], entityLists[1], entityLists[2])
                    .engineCustomData(engineGetCustomData)
                    .weaponMounts(
                            weaponMounts[0], weaponMounts[1], weaponMounts[2], weaponMounts[3],
                            weaponMounts[4], weaponMounts[5], weaponMounts[6], weaponMounts[7],
                            weaponMounts[8], weaponMounts[9])
                    .weaponFluxCostToFire(weaponMounts[10])
                    .weaponDistanceFromArc(weaponMounts[11])
                    .weaponAdoptServe(weaponMounts[12], weaponMounts[13])
                    .weaponForceDisabled(weaponMounts.length > 14 ? weaponMounts[14] : null)
                    .weaponGroupMirror(
                            (Method) weaponGroupMirror[0], (Method) weaponGroupMirror[1],
                            (Method) weaponGroupMirror[2], (Method) weaponGroupMirror[3],
                            weaponGroupMirror[4], (Method) weaponGroupMirror[5])
                    .weaponSlotIdentity(weaponSlotIdentity[0], weaponSlotIdentity[1])
                    .weaponGroupTubeMirror(
                            (Method) weaponGroupTubeMirror[0], (Method) weaponGroupTubeMirror[1],
                            (Method) weaponGroupTubeMirror[2], (Method) weaponGroupTubeMirror[3],
                            weaponGroupTubeMirror[4])
                    .weaponCurrAngle(edge[0])
                    .weaponProjectileSpeed(weaponProjectileSpeed)
                    .weaponSpecClass(
                            (Method) weaponSpecClass[0], (Method) weaponSpecClass[1],
                            (Method) weaponSpecClass[2], (Method) weaponSpecClass[3],
                            (Method) weaponSpecClass[4], (Method) weaponSpecClass[5],
                            (Class<?>) weaponSpecClass[6])
                    .projectileSpawnReads(
                            projectileSpawnReads[0], projectileSpawnReads[1], projectileSpawnReads[2],
                            projectileSpawnReads[3], projectileSpawnReads[4])
                    .shipSystem(
                            edge[1], edge[2], edge[3], edge[4], edge[5], edge[6],
                            edge[7], edge[8], edge[9], edge[10], edge[11])
                    .shipProfile(profile[0], profile[1], profile[2])
                    .shipManeuver(
                            maneuver[0], maneuver[1], maneuver[2], maneuver[3],
                            maneuver[4], maneuver[5], maneuver[6])
                    .shipTimeMultRead(shipTimeMultRead)
                    .playerShipGiveCommand((Method) commands[0])
                    .playerShipMovementCommands(
                            commands[2], commands[3], commands[4], commands[5], commands[6], commands[7])
                    .playerShipWeaponCommands(commands[8], commands[9], commands[10])
                    .playerShipReverseThrust(commands[11])
                    .playerShipVentCommand(commands[12])
                    .playerShipShieldToggle(commands[13], phaseCloakToggle)
                    .playerShipPullBackFighters(commands[14], pullBackFightersRead)
                    .shipPhaseCloakRead(shipIsPhasedRead)
                    .shipCrReads(
                            shipCrReads[0], shipCrReads[1], shipCrReads[2],
                            shipCrReads[3], shipCrReads[4], shipCrReads[5])
                    .playerShipUseSystem((Method) commands[1])
                    .systemActivationGuard(systemActivationGuard)
                    .shipAiSuppression(aiSuppression[0], aiSuppression[1])
                    // The DD1 fast-time gate read (public CombatEngineAPI
                    // surface). Fail-soft null ⇒ the shim
                    // never installs (fail-closed at the install site).
                    .engineFastTimeRead(engineFastTimeRead)
                    .timeMult(realTimeMult)
                    .combatSpeedMult(1.0f)
                    .fogBound(true)
                    .shipMotionWrite(
                            (Method) motionWrite[0], (Method) motionWrite[1], (Constructor<?>) motionWrite[2])
                    .shipFacingWrite(facingWrite)
                    .shipTravelDriveRead(travelDriveRead)
                    // The fire-lock reads (vanilla's click gate + the
                    // command filter's refusal set); see resolveFireLockHandles.
                    .shipFireLockReads(
                            (Method) fireLockReads[0], (Method) fireLockReads[1], (Method) fireLockReads[2],
                            (Method) fireLockReads[3], (Method) fireLockReads[4], (Method) fireLockReads[5],
                            (Method) fireLockReads[6], fireLockReads[7])
                    .shipRetreatApply(retreatApply[0], retreatApply[1], retreatApply[2], retreatApply[3])
                    .shipTargetApply(shipTargetSeam[0], shipTargetSeam[1], shipTargetSeam[2])
                    .shipMouseTargetWrite(mouseTargetSeam[0], mouseTargetSeam[1])
                    // Descriptor present => the server owns ALL
                    // descriptor seat ships (occupancy-independent — an intent for a minted-but-
                    // unmounted seat passes the set, resolves null in the live map, drops
                    // harmlessly); no descriptor => the deployed SEAT ships ({1,2} legacy,
                    // unchanged; full-deploy descriptor: the two sets are provably equal). AI-row
                    // ships are deliberately NOT owned — the engine AI flies them, and
                    // idleOwnedShipAi/pose/intent must stay seat-only surfaces.
                    .ownedShipIds(ownedSeatShipIds())
                    // The roster's live wireId→ship map is the seat-addressed
                    // resolve authority (deploy/midDeploy/reset maintained); fakes never wire this.
                    .liveShipResolver(new EngineCombatBinding.LiveWireShipResolver() {
                        @Override
                        public Object shipForWireIdOrNull(long wireId) {
                            return RealEngineDemoRoster.liveShipForWireIdOrNull(wireId);
                        }

                        @Override
                        public boolean live() {
                            return RealEngineDemoRoster.liveShipMapActive();
                        }
                    })
                    .build();
            binding = built;
            gatedConstruct = occupiedOrNull != null;
            logCombatMapIdentity("post-build", getCombatMap, engine, combatMap);

            // ---- VERIFY-FIRST diagnostic + faithful unpause ----
            // A fresh CombatEngine advances on advanceInner's PAUSED branch: executeAdds still
            // drains the pending-add queue (→ the 2 deployed Mudskippers become repo-visible) but
            // the unpaused ship-advance/AI/physics pass is skipped, so the served ships are present
            // yet byte-identical across frames (distinct_entity_states=1). Confirm the
            // engine is in fact paused here (pausedBefore should read true) BEFORE driving the
            // engine's OWN setPaused(false) once -- the faithful unpause the proven scout loop does
            // before stepping, NOT a sim reimplementation.
            // setPaused is sticky, so this single create()-time call holds for the serve loop's
            // every subsequent advanceInner. setPaused(false) on an already-unpaused engine is a
            // harmless no-op, so logging the pre-state first costs nothing if the diagnosis is wrong.
            Boolean pausedBefore = built.isPausedOrNull();
            boolean unpauseApplied = built.unpause();
            Boolean pausedAfter = built.isPausedOrNull();
            System.out.println("real-engine-binding-factory pause-control"
                    + " pausedBefore=" + pausedBefore
                    + " unpauseApplied=" + unpauseApplied
                    + " pausedAfter=" + pausedAfter);

            // round-open plugin BASELINE for the heap drain's corrective 5.
            // Here — the create tail — because the first drain resolve runs at warm-up pass 0's
            // RESET, after a warm-up round may already have leaked script-added plugins into the
            // list (they would be baselined and never swept). Fail-soft inside; logs its own row.
            RealEngineRoundHeapDrain.capturePluginBaseline(engine);
            return binding;
        } catch (RealEngineCombatBindingFactoryException e) {
            throw e;
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            throw new RealEngineCombatBindingFactoryException(
                    "failed to construct the real-engine combat binding: "
                            + cause.getClass().getName() + ": "
                            + (cause.getMessage() == null ? "" : cause.getMessage()),
                    cause);
        }
    }

    /** Whether a binding has been constructed for this process. */
    public static synchronized boolean created() {
        return binding != null;
    }

    /**
     * Reset the singleton {@code CombatEngine}'s combat state for a fresh round — the 2b
     * {@code MATCH_RESET} hook (L1 Option A, session-lifecycle design). Rolls back the prior round's
     * roster deploy ({@link RealEngineDemoRoster#resetForRedeploy()} closes the deploy treatment →
     * engine back to its post-{@code init()} clean state, prior fleet listeners removed) and
     * re-deploys a fresh two-Mudskipper roster through the same proven construction-time deploy lane
     * ({@link RealEngineDemoRoster#deployMatchRoster()}), then re-asserts the engine unpaused.
     *
     * <p><b>The cached {@link #binding} is REUSED unchanged.</b> It wraps {@code getObjects()} /
     * {@code getCombatMap()} / {@code getPlayerShip()} as live method handles on the singleton engine,
     * so it transparently follows the engine's new repository + player seat after the re-deploy — no
     * rebuild needed (and the singleton {@code getInstance()} could not hand back a different instance
     * anyway). The process-global seams (the L3 GL context, the background-sprite seed, the
     * damage-path FX seams) survive the reset by design — they are never restored — so a reset round
     * does not re-pay the substrate bring-up; only the per-round roster is rebuilt. This is the
     * second-real-engine-combat-in-one-JVM question the session-lifecycle MEASURE gates on, exercised
     * here as the production reset loop rather than a standalone probe.
     *
     * <p>Must run on the GL-substrate thread that constructed the engine, between served sessions
     * (no {@code advanceInner} in flight). Fail-closed: if {@code create()} has not run, or the
     * rollback / re-deploy surfaces a seam (engine state not clearing, stale hulks carried over,
     * re-deploy refusal), this throws rather than serving a stale or half-reset roster.
     *
     * @throws RealEngineCombatBindingFactoryException on any reset failure (the named blocker the
     *         session-lifecycle design says to record).
     */
    public static synchronized void resetForRedeploy() {
        if (binding == null) {
            throw new RealEngineCombatBindingFactoryException(
                    "resetForRedeploy() before create(): no real-engine binding to reset");
        }
        if (gatedConstruct) {
            throw new RealEngineCombatBindingFactoryException(
                    "legacy full-deploy resetForRedeploy() on a gated npeer binding — the npeer"
                            + " round reset must pass the live occupancy"
                            + " (resetForRedeploy(occupiedSeatIds); mode guard)");
        }
        resetInternal(null, Map.of(), Map.of());
    }

    /**
     * The npeer/gated round reset: identical to {@link #resetForRedeploy()}
     * in every stage — heap drain, roster rollback, message-widget round-clear, unpause — except
     * the re-deploy: the descriptor-REQUIRED roster
     * deploys with EVERY seat excluded (AI rows + station on the battle-start lane), then each
     * occupied seat flies in through {@link RealEngineDemoRoster#midDeploySeat} — the SAME lane
     * as every join/respawn (edge placement + the fly-in burn); an EMPTY set is legal (a
     * zero-hull battlefield; hulls arrive per claim) and returns no fly-ins. The caller (serveNPeer's round
     * wrap) resolves occupancy AT FIRE TIME from {@code MatchSeatAuthority.occupiedSeatIds()} and
     * follows with {@code resetRoundSeats(mounted)} + the MRST fan + the post-reset baseline burst
     * (seat-machine and wire steps live with the loop, not here). Fail-closed
     * against mode mixing, mirroring {@link #create(SortedSet)}: only a gated-construct binding may
     * be gated-reset, and the legacy no-arg reset THROWS on a gated binding rather than silently
     * full-deploying every seat onto an occupancy-gated match.
     *
     * <p>The pick-free form — every seat redeploys its
     * descriptor row ({@code Map.of()}). Its one production caller is the boot warm-up
     * ({@code ServeWarmupRound}, EMPTY occupancy); the npeer round wrap uses
     * {@link #resetForRedeploy(SortedSet, Map)} with each seat's current pick.
     */
    public static synchronized List<RealEngineDemoRoster.SeatDeployResult> resetForRedeploy(
            SortedSet<Integer> occupiedSeatIds) {
        return resetForRedeploy(occupiedSeatIds, Map.of());
    }

    /**
     * The npeer round-wrap reset that KEEPS every seat's
     * pick — {@code variantOverrideBySeat} maps an occupied seat to its current pick (override ??
     * descriptor: an absent seat, or a pick the engine cannot resolve / a station id, redeploys the
     * descriptor row with one {@code override_unresolvable … fallback= reason=} line). Null map is
     * illegal (a dropped map would silently re-field the descriptor = fail-open). Otherwise
     * identical to {@link #resetForRedeploy(SortedSet)}.
     * returns the seat fly-ins in ascending seat order (unmodifiable; one per occupied seat).
     */
    public static synchronized List<RealEngineDemoRoster.SeatDeployResult> resetForRedeploy(
            SortedSet<Integer> occupiedSeatIds, Map<Integer, String> variantOverrideBySeat) {
        return resetForRedeploy(occupiedSeatIds, variantOverrideBySeat, Map.of());
    }

    /**
     * The npeer round-wrap reset that keeps every seat's pick AND its team side: {@code sideBySeat} maps a
     * seat to the side (0 / 1) its hull flies in on — the engine places the hull at that side's map edge with
     * that side's facing, the {@code /switchteams} fly-in twin — and an absent seat flies on its descriptor
     * side. Null is illegal (a dropped map would silently re-field the descriptor sides). Otherwise identical
     * to {@link #resetForRedeploy(SortedSet, Map)}, which is this with an empty side map.
     *
     */
    public static synchronized List<RealEngineDemoRoster.SeatDeployResult> resetForRedeploy(
            SortedSet<Integer> occupiedSeatIds, Map<Integer, String> variantOverrideBySeat,
            Map<Integer, Integer> sideBySeat) {
        if (sideBySeat == null) {
            throw new RealEngineCombatBindingFactoryException(
                    "sideBySeat must be non-null (Map.of() = every seat on its descriptor side)");
        }
        if (variantOverrideBySeat == null) {
            throw new RealEngineCombatBindingFactoryException(
                    "variantOverrideBySeat must be non-null (Map.of() = every seat on its descriptor"
                            + " row)");
        }
        if (occupiedSeatIds == null) {
            throw new RealEngineCombatBindingFactoryException(
                    "occupiedSeatIds must be non-null (the no-arg resetForRedeploy() is the legacy"
                            + " full-deploy reset)");
        }
        if (binding == null) {
            throw new RealEngineCombatBindingFactoryException(
                    "resetForRedeploy(occupiedSeatIds) before create(): no real-engine binding to reset");
        }
        if (!gatedConstruct) {
            throw new RealEngineCombatBindingFactoryException(
                    "gated resetForRedeploy(occupiedSeatIds) on a legacy full-deploy binding — the"
                            + " occupancy-gated reset exists only under the gated npeer construct"
                            + " (mode guard)");
        }
        return resetInternal(occupiedSeatIds, variantOverrideBySeat, sideBySeat);
    }

    /**
     * Shared reset body ({@code createInternal} mirror): {@code occupiedOrNull == null} is the
     * legacy full re-deploy (log line and behavior byte-identical to the pre-overload reset);
     * non-null is the occupancy-gated npeer re-deploy. Callers hold the class lock and have
     * validated mode/binding state. The gated path threads
     * {@code variantOverrideBySeat} (never null; the legacy path passes {@code Map.of()}) into the
     * roster deploy and its operator row prints the per-seat fielded variants.
     * the gated path deploys the roster with every seat excluded and
     * then {@code midDeploySeat(seat, pick)} per occupied seat (ascending), BEFORE the widget
     * clear / unpause / row; returns those fly-ins (empty on the legacy path). A fly-in throw
     * fails the whole reset closed.
     */
    private static List<RealEngineDemoRoster.SeatDeployResult> resetInternal(SortedSet<Integer> occupiedOrNull,
                                      Map<Integer, String> variantOverrideBySeat,
                                      Map<Integer, Integer> sideBySeat) {
        try {
            // OWED-#1 port (soak-leak correctives -> round-boundary heap drain).
            // Re-run the engine's OWN between-battle cleanup on the OUTGOING round's
            // state BEFORE the roster rollback swaps the repo away: the entity sweep must reach the
            // live repo + renderer to unregister engine-spawned entities through the engine's remove
            // hook, and the roster rollback only restores saved prior state (setObjects/
            // setFleetManagers/playerShip) without iterating the current repo, so sweep-then-rollback
            // is safe and faithful. Default-ON; the single master switch cc.predict.correctivesOff
            // flips all four off for desync-source debugging ([[correctives-toggleable]]).
            // Fail-soft: a drain failure is logged and skipped, never failing the reset
            // (the drain is an optimization; the roster reset keeps its own fail-closed semantics).
            if (!correctivesOff()) {
                try {
                    String drainOutcome = roundHeapDrain().drainRoundHeap();
                    System.out.println("real-engine-binding-factory heap-drain " + drainOutcome);
                } catch (Throwable drainFailure) {
                    System.out.println("real-engine-binding-factory heap-drain SKIPPED "
                            + drainFailure.getClass().getName() + ": " + drainFailure.getMessage());
                }
            }
            RealEngineDemoRoster.resetForRedeploy();
            List<RealEngineDemoRoster.SeatDeployResult> flyIns = List.of();
            if (occupiedOrNull == null) {
                RealEngineDemoRoster.deployMatchRoster();
            } else {
                // The round's roster with EVERY seat
                // excluded (AI rows + station keep the battle-start lane), then each occupied seat
                // flies in through the join/respawn lane — one fly-in shape for the whole match.
                RealEngineDemoRoster.deployMatchRoster(new TreeSet<>(), Map.of());
                List<RealEngineDemoRoster.SeatDeployResult> fielded =
                        new ArrayList<>(occupiedOrNull.size());
                for (Integer seatId : occupiedOrNull) {
                    // A kept team side flies the seat in on that edge
                    // (absent = -1 = the descriptor side, the pre-change deploy bit-for-bit).
                    fielded.add(RealEngineDemoRoster.midDeploySeat(seatId,
                            variantOverrideBySeat.get(seatId), sideBySeat.getOrDefault(seatId, -1)));
                }
                flyIns = Collections.unmodifiableList(fielded);
            }
            // Presentation housekeeping: the seeded message widget's prune runs only in
            // the render advance the server never drives, so clear the dead round's entries.
            // Fail-soft: a failed clear is presentation-only, never worth ending the match over.
            try {
                ClassLoader loader = RealEngineCombatBindingFactory.class.getClassLoader();
                Object engine = Class.forName(ENGINE_CLASS, true, loader).getMethod("getInstance").invoke(null);
                Object combatUi = engine == null ? null : engine.getClass().getMethod("getCombatUI").invoke(engine);
                Object widget = combatUi == null
                        ? null
                        : Class.forName(COMBAT_UI_INTERFACE_CLASS, false, loader)
                                .getMethod("getMessageWidget").invoke(combatUi);
                if (widget != null) {
                    HeadlessCombatContext.resetSeededMessageWidgetEntries(widget);
                }
            } catch (Throwable widgetClearFailure) {
                System.out.println("real-engine-binding-factory message-widget round-clear SKIPPED "
                        + widgetClearFailure.getClass().getName() + ": " + widgetClearFailure.getMessage());
            }
            // setPaused is sticky on the singleton, but re-assert the faithful unpause (a harmless
            // no-op if still unpaused) so a fresh round always steps — never the PAUSED-branch
            // static-served-ships regression.
            boolean unpauseApplied = binding.unpause();
            // The gated row names each seat's FIELDED
            // variant + how many picks rode the wrap / fell back to the descriptor (the legacy
            // row stays byte-identical).
            System.out.println("real-engine-binding-factory match-reset"
                    + (occupiedOrNull == null ? "" : "-gated occupied=" + occupiedOrNull.size())
                    + " redeployed_ships=" + RealEngineDemoRoster.deployedShipCount()
                    + (occupiedOrNull == null
                            ? " variant=" + RealEngineDemoRoster.deployedVariantId()
                            : " seat_variants=" + RealEngineDemoRoster.seatDeployedVariantsRow()
                                    + " overrides=" + RealEngineDemoRoster.overrideCount()
                                    + " override_fallbacks="
                                    + RealEngineDemoRoster.overrideFallbackCount())
                    + " unpauseApplied=" + unpauseApplied);
            return flyIns;
        } catch (RealEngineCombatBindingFactoryException e) {
            throw e;
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            throw new RealEngineCombatBindingFactoryException(
                    "match reset re-deploy failed (engine state not clearing between matches?): "
                            + cause.getClass().getName() + ": "
                            + (cause.getMessage() == null ? "" : cause.getMessage()),
                    cause);
        }
    }

    /**
     * Whether the single master switch {@code cc.predict.correctivesOff} disables ALL round-boundary
     * correctives (default OFF = correctives ON, per the RELAXED verdict).
     * Read server-side via {@link ServerConfig} (sysprop &gt; env &gt; config file &gt; default);
     * shares the property NAME with the client-side predict correctives — each JVM reads its own.
     */
    /**
     * The binding's owned-set source: ALL descriptor seat wire ids when a
     * descriptor deployed this round (occupancy-independent), else the deployed SEAT ships (the
     * legacy {@code {1,2}} path, byte-identical). On a full-deploy descriptor the two arrays are
     * provably equal (the roster's position assert), so pre-N behavior is unchanged.
     */
    private static long[] ownedSeatShipIds() {
        long[] descriptorSeatIds = RealEngineDemoRoster.descriptorSeatShipIds();
        return descriptorSeatIds.length > 0 ? descriptorSeatIds : RealEngineDemoRoster.deployedSeatShipIds();
    }

    private static boolean correctivesOff() {
        return "true".equalsIgnoreCase(ServerConfig.resolve(
                "cc.predict.correctivesOff", "CC_PREDICT_CORRECTIVES_OFF", "predict.correctivesOff", "false"));
    }

    /**
     * Lazily resolve (once per process, cached) the round-boundary heap drain against the singleton
     * engine. Resolved on first reset rather than at {@code create()} so the fog trackers are already
     * minted (a round has run). {@link RealEngineRoundHeapDrain#resolve} is itself fail-soft per
     * corrective; this only throws if the engine singleton cannot be obtained at all (binding!=null
     * is checked by the caller, so that should not happen — surfaced rather than silently swallowed).
     */
    private static RealEngineRoundHeapDrain roundHeapDrain() throws Exception {
        if (roundHeapDrain == null) {
            ClassLoader loader = RealEngineCombatBindingFactory.class.getClassLoader();
            Object engine = Class.forName(ENGINE_CLASS, true, loader).getMethod("getInstance").invoke(null);
            if (engine == null) {
                throw new RealEngineCombatBindingFactoryException(
                        "CombatEngine.getInstance() null at heap-drain resolve");
            }
            roundHeapDrain = RealEngineRoundHeapDrain.resolve(engine);
        }
        return roundHeapDrain;
    }

    /**
     * Boot the BARE {@code CampaignEngine} — the title-screen-faithful sector state (
     * omega F3). In the real client every mission runs with {@code Global.getSector()}
     * returning the bare campaign engine minted at title time (empty hyperspace, faction manager,
     * a LIVE {@code ListenerManager}); headless it was never minted, so vanilla combat scripts
     * that reach the sector mid-round (the omega shard-split spawn chain's
     * {@code getSector().getListenerManager()}) NPE'd and killed the round. Booting the engine's
     * own bare instance — never a hand-rolled {@code SectorAPI} stub — makes that whole CLASS of
     * touches behave exactly as a title-screen mission does (DERIVE-FROM-KNOWN-GOOD; the
     * known-good = the client title boot). The campaign clock is never advanced, so its one boot
     * script ({@code NoFuelDriftScript}) stays dormant; the instance is process-lived and survives
     * round resets by design (a process-global seam, like the GL substrate).
     *
     * <p>Fail-SOFT: a boot refusal logs and continues — every non-sector-touching serve (the ×3
     * GREEN categories) is exactly as viable as before; the omega lane then still names its
     * blocker through the warm-up's full-stack row. Must run BEFORE the combat-engine mint:
     * {@code setInstance} destroys + re-mints the {@code CombatEngine} singleton (null-safe
     * pre-mint, binding-orphaning after). Every caller, the wave-inflate
     * measurement main included, reaches it through {@link #create} — the one order that is safe.
     */
    private static void bootBareCampaignEngine(ClassLoader loader) {
        try {
            Class<?> campaignEngineClass = Class.forName(CAMPAIGN_ENGINE_CLASS, true, loader);
            boolean preExisting = Boolean.TRUE.equals(
                    campaignEngineClass.getMethod("hasInstance").invoke(null));
            if (!preExisting) {
                campaignEngineClass.getMethod("getInstance").invoke(null);
            }
            Object sector = Class.forName(GLOBAL_CLASS, true, loader)
                    .getMethod("getSector").invoke(null);
            System.out.println("real-engine-binding-factory campaign-engine boot"
                    + " preExisting=" + preExisting
                    + " sector_non_null=" + (sector != null));
        } catch (Throwable t) {
            System.out.println("real-engine-binding-factory campaign-engine boot SKIPPED "
                    + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    /**
     * Pre-seed the engine's static background-{@code Sprite} field non-null (Option-C)
     * so the ctor's offset-965 {@code ifnonnull} skips the in-ctor GL crossing. Idempotent: a
     * no-op if the field is already non-null (a prior seed, or content-init populated it) — the
     * ctor's guard then skips the crossing on its own. The seed is driven through the product
     * {@link HeadlessCombatContext} seam and kept installed (never restored) for the process
     * lifetime. The dummy {@code Sprite} is allocated without running its ctor and is never
     * dereferenced on the paused, non-rendering advance path.
     */
    private static void seedBackgroundSprite(Class<?> engineClass, ClassLoader loader) throws Exception {
        Class<?> spriteClass = Class.forName(SPRITE_CLASS, false, loader);
        Field backgroundSpriteField = uniqueStaticFieldOfType(engineClass, spriteClass);
        if (backgroundSpriteField == null) {
            throw new RealEngineCombatBindingFactoryException(
                    "CombatEngine background-sprite static field not uniquely identified");
        }
        backgroundSpriteField.setAccessible(true);
        if (backgroundSpriteField.get(null) != null || preConstructContext != null) {
            return; // already seeded / populated; the ctor ifnonnull will skip the GL crossing
        }
        Object unsafe = unsafeSingleton();
        Object dummySprite = allocateUnsafeInstance(unsafe, spriteClass);
        preConstructContext = HeadlessCombatContext.builder()
                .boundaryLabel("real-engine-combat-binding-factory")
                .backgroundSpriteSeed(backgroundSpriteField, dummySprite)
                .install();
        if (backgroundSpriteField.get(null) != dummySprite) {
            throw new RealEngineCombatBindingFactoryException(
                    "background-sprite seed did not take (field readback != seeded dummy)");
        }
    }

    /**
     * Install the two engine's-own presentation seams the projectile-IMPACT damage path
     * ({@code Ship.applyDamageInner}) dereferences, both null in the headless dedicated server
     * (no render/UI layer) and both reached only once a projectile actually lands:
     *
     * <ol>
     *   <li><b>Combat-UI proxy.</b> {@code getCombatUI()} is null, and the damage path reads
     *       {@code ...getCombatUI().getEntityToFollow()} — only <em>compared</em> against
     *       {@code this} (camera-follow check), never dereferenced. A
     *       {@link java.lang.reflect.Proxy} implementing the engine's own {@code K}/CombatUIAPI
     *       interface with engine-default returns (object methods → null) is installed via the
     *       engine's own {@code setCombatUI(K)}, making {@code this == null} false (null-safe).
     *       Exception: {@code getMessageWidget()} returns a seeded REAL widget —
     *       {@code Ship.advance}'s CR callouts dereference it unguarded (a live-round NPE);
     *       see {@code HeadlessCombatContext.seedMessageWidgetFields}.</li>
     *   <li><b>Debris-viewport seed.</b> {@code DebrisParticleSystem.spawnDebris} reads
     *       {@code this.viewport.isNearViewport(...)} (an FX cull). The engine's own
     *       {@code DebrisParticleSystem.advance()} lazily sets that field from
     *       {@code CombatEngine.getInstance().getViewport()} — but the serve loop drives
     *       {@code advanceInner} (never the FX-system {@code advance()}), so it stays null. The seam
     *       pre-populates it with the engine's own {@code getViewport()} (the <em>same</em> value)
     *       and flips {@code setEverythingNearViewport(true)}, so the cull short-circuits true
     *       and spawnDebris is null-safe. NOT combat-inert: the always-true
     *       viewport also pins {@code Ship.advance}'s off-viewport hulk-fade accumulator at 0
     * — non-fighter hulks never expire
     *       headless while a rendered client fades them off-camera; asymmetry owned at the D2
     *       hulk-row decision.</li>
     * </ol>
     *
     * <p>Neither fakes a combat outcome — the real engine damage math still runs; these are
     * presentation handles, not a reimplementation/stat-hack. Driven through the product
     * {@link HeadlessCombatContext} seam (the engine's own {@code setCombatUI} / {@code getViewport}
     * / {@code setEverythingNearViewport}) and <b>kept installed</b> (never restored) for the
     * process lifetime — like the background-sprite seed and the GL context, they survive reset
     * rounds. Idempotent: a no-op once the process-global context exists.
     *
     * <p>Must be called AFTER {@code getInstance()} + {@code init()}: the engine instance must exist
     * and both handles must still be null (the seams refuse to overwrite an already-bound handle).
     */
    /** Once-per-process latch for {@link #selfCheckMessageWidgetShipPost}. */
    private static boolean messageWidgetShipPostChecked;

    /**
     * one-shot boot-time exercise of the FULL message-post path with a REAL roster ship —
     * the Ship arg makes the entry builder resolve the deployed fleet member and construct the
     * ship-icon fragment ({@code ui.B.new}), the exact allied-branch CR-callout shape that killed
     * a live round. A missing-resource surprise in that unaudited icon ctor fails the BOOT
     * (clear wrap message), never minute ~2.5 of a real match. The self-check entry is cleared
     * afterwards; per-round entry clearing happens in the round-reset path (the widget's own prune
     * runs only in the render advance the server never drives).
     */
    private static void selfCheckMessageWidgetShipPost(Class<?> engineClass, Object engine,
            ClassLoader loader) throws Exception {
        if (messageWidgetShipPostChecked) {
            return;
        }
        Object ship = RealEngineDemoRoster.firstDeployedShipOrNull();
        if (ship == null) {
            throw new RealEngineCombatBindingFactoryException(
                    "message-widget ship-post self-check: no deployed roster ship");
        }
        Class<?> combatUiInterface = Class.forName(COMBAT_UI_INTERFACE_CLASS, false, loader);
        Object combatUi = engineClass.getMethod("getCombatUI").invoke(engine);
        Object widget = combatUiInterface.getMethod("getMessageWidget").invoke(combatUi);
        if (widget == null) {
            throw new RealEngineCombatBindingFactoryException(
                    "message-widget ship-post self-check: getMessageWidget() null post-seams");
        }
        Method post = widget.getClass().getMethod("o00000", Object[].class);
        // Label with the actually-deployed variant (descriptor rosters need not be
        // Mudskippers); the posted ship is spec[0] = the player seat, so its variant id matches.
        Object entry = post.invoke(widget, new Object[] { new Object[] {
                ship, java.awt.Color.WHITE, "self-check (", RealEngineDemoRoster.deployedVariantId() + ")",
                java.awt.Color.WHITE, " message-widget ship-post self-check" } });
        if (entry == null) {
            throw new RealEngineCombatBindingFactoryException(
                    "message-widget ship-post self-check: post returned null entry");
        }
        HeadlessCombatContext.resetSeededMessageWidgetEntries(widget);
        messageWidgetShipPostChecked = true;
        System.out.println("real-engine-binding-factory message-widget ship-post self-check PASS");
    }

    /**
     * Run the message-widget ship-post self-check a gated zero-hull construct deferred
     * (no deployed ship existed at boot). Call once the round's FIRST fly-in has landed a real ship
     * (serveNPeer's first successful {@link RealEngineDemoRoster#midDeploySeat}) — the check then
     * exercises the same CR-callout icon path with that ship, failing the first admit loudly instead
     * of minute ~2.5 of a live round. No-op when nothing was deferred or the check already ran;
     * THROWS if the roster is still shipless (caller sequencing bug) or {@code create()} never ran.
     */
    public static synchronized void runDeferredMessageWidgetShipPostSelfCheck() {
        if (!messageWidgetSelfCheckDeferred || messageWidgetShipPostChecked) {
            return;
        }
        if (binding == null) {
            throw new RealEngineCombatBindingFactoryException(
                    "deferred message-widget self-check before create()");
        }
        try {
            ClassLoader loader = RealEngineCombatBindingFactory.class.getClassLoader();
            Class<?> engineClass = Class.forName(ENGINE_CLASS, true, loader);
            Object engine = engineClass.getMethod("getInstance").invoke(null);
            if (engine == null) {
                throw new RealEngineCombatBindingFactoryException(
                        "CombatEngine.getInstance() null at the deferred message-widget self-check");
            }
            selfCheckMessageWidgetShipPost(engineClass, engine, loader);
            messageWidgetSelfCheckDeferred = false;
        } catch (RealEngineCombatBindingFactoryException e) {
            throw e;
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            throw new RealEngineCombatBindingFactoryException(
                    "deferred message-widget ship-post self-check failed: "
                            + cause.getClass().getName() + ": "
                            + (cause.getMessage() == null ? "" : cause.getMessage()),
                    cause);
        }
    }

    private static void seedDamagePathFxSeams(Class<?> engineClass, Object engine, ClassLoader loader)
            throws Exception {
        if (postInitFxContext != null) {
            return; // already installed for this process
        }
        Class<?> combatUiInterface = Class.forName(COMBAT_UI_INTERFACE_CLASS, false, loader);
        Method getCombatUi = engineClass.getMethod("getCombatUI");
        Method setCombatUi = engineClass.getMethod("setCombatUI", combatUiInterface);

        // The proxy's null getMessageWidget() killed a live round (Ship.advance
        // CR callouts dereference it unguarded). Allocate the REAL widget class (engine-internal
        // com.fs.starfarer.class.C -- keyword package, reflective-only) WITHOUT running its ctor
        // (listener registration + scanline-sprite fetch are render-lane); the context seeds the
        // add-path fields and self-posts at install.
        Class<?> messageWidgetClass = Class.forName("com.fs.starfarer.class.C", true, loader);
        Object rawMessageWidget = allocateUnsafeInstance(unsafeSingleton(), messageWidgetClass);
        Constructor<?> faderConstructor = Class.forName("com.fs.graphics.util.Fader", true, loader)
                .getConstructor(float.class, float.class, float.class);

        Class<?> viewportClass = Class.forName(COMBAT_VIEWPORT_CLASS, false, loader);
        Class<?> debrisSystemClass = Class.forName(DEBRIS_SYSTEM_CLASS, false, loader);
        Method getViewport = engineClass.getMethod("getViewport");
        Method getDebrisSystem = engineClass.getMethod("getDebrisSystem");
        Method setEverythingNearViewport = viewportClass.getMethod("setEverythingNearViewport", boolean.class);
        Method isEverythingNearViewport = viewportClass.getMethod("isEverythingNearViewport");

        postInitFxContext = HeadlessCombatContext.builder()
                .boundaryLabel("real-engine-combat-binding-factory")
                .combatUiProxy(engine, combatUiInterface, getCombatUi, setCombatUi,
                        rawMessageWidget, faderConstructor)
                .debrisViewportSeed(
                        engine,
                        viewportClass,
                        debrisSystemClass,
                        getViewport,
                        getDebrisSystem,
                        setEverythingNearViewport,
                        isEverythingNearViewport)
                .install();
    }

    /**
     * Read-only combat-map survival sentinel. Logs the {@code identityHashCode} of the
     * engine's <em>live</em> combat-map field (a getfield via {@code getCombatMap}) at four named
     * {@code create()} phases (post-setCombatMap / post-init / pre-build / post-build), beside the
     * installed map and engine identities. It was added to localize the serve gate's
     * {@code combatSpaceReady=false}: the map round-trips pre-{@code init()}, survives
     * {@code init()}, then went null at the {@code getPlayerShip()} time-mult read (the engine's
     * no-player fallback "Command Shuttle" creation nulls {@code #450}). With that read removed the
     * map now survives all four phases; the sentinel is <b>retained</b> because it is a cheap,
     * self-explaining regression trip-wire for exactly this subtle null (a future re-introduction of
     * any pre-roster {@code getPlayerShip()} / engine-state-resetting call would show {@code liveNull=true}
     * here, not as an opaque serve-time gate refusal). Pure read, fail-soft (never throws).
     */
    private static void logCombatMapIdentity(String phase, Method getCombatMap, Object engine,
            Object installedMap) {
        try {
            Object liveMap = getCombatMap.invoke(engine);
            System.out.println("real-engine-binding-factory map-identity phase=" + phase
                    + " liveMap=" + System.identityHashCode(liveMap)
                    + " installedMap=" + System.identityHashCode(installedMap)
                    + " engine=" + System.identityHashCode(engine)
                    + " liveNull=" + (liveMap == null));
        } catch (Throwable t) {
            System.out.println("real-engine-binding-factory map-identity phase=" + phase
                    + " READ_FAILED " + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    // ---- Fail-soft per-group handle resolvers (null group element => that view tier unavailable) ----
    // (The old zero-caller resolveRealTimeMult player-ship chain was DELETED —
    // superseded by the binding's per-ship shipTimeMultRead lane, which never touches
    // getPlayerShip() so the fabrication hazard cannot recur.)

    /**
     * (83) SV-2: strict-by-default disposition of an unresolved optional tier.
     * The resolvers below fail SOFT (null / all-null array); a folded tier silently serves older
     * truth for the whole boot, so under {@code cc.bind.strict} (default TRUE — pinned RC8
     * resolves every tier) it fails the boot loudly instead, and
     * {@code bind.strict=false} restores the (81) announce-and-degrade path. Array tiers signal
     * failure as every-element-null (the catch path mints {@code new Method[N]}); a
     * deliberately-null single slot inside an otherwise-resolved group neither throws nor
     * announces.
     */
    private static void requireResolved(String seam, Object resolved) {
        if (!isUnresolved(resolved)) {
            return;
        }
        if (bindStrict()) {
            throw new RealEngineCombatBindingFactoryException("tier " + seam
                    + " unresolved on pinned engine (game patch?);"
                    + " set bind.strict=false to serve degraded");
        }
        cc.starsector.server.runtime.FallbackAnnounce.announceOnce("resolve-" + seam,
                "optional engine tier failed to resolve; serving without it this boot");
    }

    private static boolean isUnresolved(Object resolved) {
        if (resolved == null) {
            return true;
        }
        if (resolved instanceof Object[] arr && arr.length > 0) {
            for (Object o : arr) {
                if (o != null) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static boolean bindStrict() {
        return cc.starsector.server.runtime.ServerConfig.resolveBoolean(
                "cc.bind.strict", "CC_BIND_STRICT", "bind.strict", true);
    }

    /** [getLocation, getVelocity, getFacing, getAngularVelocity]. */
    private static Method[] resolveMotionReadHandles(Class<?> shipClass) {
        try {
            return new Method[] {
                    shipClass.getMethod("getLocation"),
                    shipClass.getMethod("getVelocity"),
                    shipClass.getMethod("getFacing"),
                    shipClass.getMethod("getAngularVelocity")
            };
        } catch (Throwable ignore) {
            return new Method[4];
        }
    }

    /** [getHitpoints, getHullLevel, getArmorGrid, ArmorGridAPI.getGrid,
     *  ArmorGridAPI.getMaxArmorInCell] — the fifth handle is the
     *  armorLevel denominator; all-or-nothing with the group like every tier here. */
    private static Method[] resolveDurabilityHandles(ClassLoader loader, Class<?> shipClass) {
        try {
            Class<?> armorGridApi = Class.forName(ARMOR_GRID_API, false, loader);
            return new Method[] {
                    shipClass.getMethod("getHitpoints"),
                    shipClass.getMethod("getHullLevel"),
                    shipClass.getMethod("getArmorGrid"),
                    armorGridApi.getMethod("getGrid"),
                    armorGridApi.getMethod("getMaxArmorInCell")
            };
        } catch (Throwable ignore) {
            return new Method[5];
        }
    }

    /** [getHullSpec, ShipHullSpecAPI.getHullId]. */
    private static Method[] resolveIdentityHandles(ClassLoader loader, Class<?> shipClass) {
        try {
            Class<?> hullSpecApi = Class.forName(SHIP_HULL_SPEC_API, false, loader);
            return new Method[] {
                    shipClass.getMethod("getHullSpec"),
                    hullSpecApi.getMethod("getHullId")
            };
        } catch (Throwable ignore) {
            return new Method[2];
        }
    }

    /** [getId, isAlive]. */
    private static Method[] resolveLifecycleHandles(Class<?> shipClass) {
        try {
            return new Method[] {
                    shipClass.getMethod("getId"),
                    shipClass.getMethod("isAlive")
            };
        } catch (Throwable ignore) {
            return new Method[2];
        }
    }

    /**
     * {@code CombatEntityAPI.getOwner()} — the owning FleetSide ordinal, paired in the builder with the
     * lifecycle {@code isAlive} handle to bind the allegiance read. Fail-soft: null leaves
     * {@link EngineCombatBinding#shipAllegiancesOrNull()} (and the {@code perSideRoundEnd} round-end
     * trigger) unavailable, exactly like the other optional per-ship reads.
     */
    private static Method resolveAllegianceGetOwner(Class<?> shipClass) {
        try {
            return shipClass.getMethod("getOwner");
        } catch (Throwable ignore) {
            return null;
        }
    }

    /**
     * [getEngineController, ShipEngineControllerAPI: getShipEngines, isFlamingOut, isDisabled,
     * ShipEngineControllerAPI$ShipEngineAPI: isDisabled, isPermanentlyDisabled] — the v6
     * engine-state (flameout/disabled) tier read, all public-API surface, resolved together as the
     * proven scout predict lane does and the
     * exact six handles {@link EngineCombatBinding#shipEngineStatesOrNull()} requires. All-or-nothing
     * by design: that consumer treats the six as one group (any null ⇒ the whole tier unavailable), so
     * a single try matches its semantics — a resolution miss leaves the masks uncarried and the
     * snapshot stays sub-v6, fail-soft exactly like the other optional per-ship reads. (Method
     * names verified on {@code starfarer.api.jar}.)
     */
    private static Method[] resolveEngineStateHandles(ClassLoader loader, Class<?> shipClass) {
        try {
            Class<?> engCtrlApi = Class.forName(SHIP_ENGINE_CONTROLLER_API, false, loader);
            Class<?> shipEngApi = Class.forName(SHIP_ENGINE_API, false, loader);
            return new Method[] {
                    shipClass.getMethod("getEngineController"),
                    engCtrlApi.getMethod("getShipEngines"),
                    engCtrlApi.getMethod("isFlamingOut"),
                    engCtrlApi.getMethod("isDisabled"),
                    shipEngApi.getMethod("isDisabled"),
                    shipEngApi.getMethod("isPermanentlyDisabled")
            };
        } catch (Throwable ignore) {
            return new Method[6];
        }
    }

    /**
     * [getEngineController, ShipEngineControllerAPI: isAccelerating, isAcceleratingBackwards,
     * isDecelerating, isTurningLeft, isTurningRight, isStrafingLeft, isStrafingRight] — the
     * v7 maneuver-command tier read, all public-API surface, in canonical wire-bit
     * order (the same 7 booleans the client mod's own-pilot capture reads). All-or-nothing by
     * design: {@link EngineCombatBinding#shipManeuverCommandsOrNull()} treats the eight as one
     * group (any null ⇒ the whole tier unavailable), so a single try matches its semantics — a
     * resolution miss leaves the masks uncarried and the snapshot stays sub-v7, fail-soft
     * exactly like the other optional per-ship reads.
     */
    private static Method[] resolveManeuverCommandHandles(ClassLoader loader, Class<?> shipClass) {
        try {
            Class<?> engCtrlApi = Class.forName(SHIP_ENGINE_CONTROLLER_API, false, loader);
            return new Method[] {
                    shipClass.getMethod("getEngineController"),
                    engCtrlApi.getMethod("isAccelerating"),
                    engCtrlApi.getMethod("isAcceleratingBackwards"),
                    engCtrlApi.getMethod("isDecelerating"),
                    engCtrlApi.getMethod("isTurningLeft"),
                    engCtrlApi.getMethod("isTurningRight"),
                    engCtrlApi.getMethod("isStrafingLeft"),
                    engCtrlApi.getMethod("isStrafingRight")
            };
        } catch (Throwable ignore) {
            return new Method[8];
        }
    }

    /**
     * [getFluxTracker, getCurrFlux, getMaxFlux, getFluxLevel, getHardFlux, isOverloaded, isVenting,
     * getShield, ShieldAPI.isOn, ShieldAPI.getFacing, ShieldAPI.getActiveArc] — flux + the
     * (optional) shield read, resolved together as the scout lane does (facing = the
     * mint source; activeArc = the unified press law's raise-gate idle read).
     */
    private static Method[] resolveFluxAndShieldHandles(ClassLoader loader, Class<?> shipClass) {
        try {
            Class<?> fluxApi = Class.forName(FLUX_TRACKER_API, false, loader);
            Class<?> shieldApi = Class.forName(SHIELD_API, false, loader);
            return new Method[] {
                    shipClass.getMethod("getFluxTracker"),
                    fluxApi.getMethod("getCurrFlux"),
                    fluxApi.getMethod("getMaxFlux"),
                    fluxApi.getMethod("getFluxLevel"),
                    fluxApi.getMethod("getHardFlux"),
                    fluxApi.getMethod("isOverloaded"),
                    fluxApi.getMethod("isVenting"),
                    shipClass.getMethod("getShield"),
                    shieldApi.getMethod("isOn"),
                    shieldApi.getMethod("getFacing"),
                    shieldApi.getMethod("getActiveArc")
            };
        } catch (Throwable ignore) {
            return new Method[11];
        }
    }

    /** [getProjectiles, getMissiles, getBeams] on the engine class. */
    private static Method[] resolveCombatEntityListHandles(Class<?> engineClass) {
        try {
            return new Method[] {
                    engineClass.getMethod("getProjectiles"),
                    engineClass.getMethod("getMissiles"),
                    engineClass.getMethod("getBeams")
            };
        } catch (Throwable ignore) {
            return new Method[3];
        }
    }

    /**
     * [getAllWeapons, WeaponAPI: getId, getCooldownRemaining, getCooldown, isFiring, usesAmmo,
     * getAmmo, getMaxAmmo, isDisabled, getChargeLevel, getFluxCostToFire (the
     * readiness-probe flux-headroom read), distanceFromArc(Vector2f) (the
     * readiness-probe live-arc read) — same public WeaponAPI family].
     */
    /**
     * Weapon-group mirror seams, resolved beside the {@code getAllWeapons} group they
     * index against: [0]=getWeaponGroupsCopy, [1]=getSelectedGroupAPI, [2]=getWeaponGroupFor(WeaponAPI),
     * [3]=WeaponGroupAPI.isAutofiring, [4]=ShipCommand.TOGGLE_AUTOFIRE (constant),
     * [5]=WeaponGroupAPI.toggleOff (the build-time autofire disarm — same public
     * interface as [3]). All public-API surface; one try (one failure domain — the group mirror is
     * all-or-nothing), null on failure = every mirror lane no-ops, the pre-mirror behavior.
     */
    private static Object[] resolveWeaponGroupMirrorHandles(ClassLoader loader, Class<?> shipClass) {
        try {
            Class<?> weaponApi = Class.forName(WEAPON_API, false, loader);
            Class<?> groupApi = Class.forName(WEAPON_GROUP_API, false, loader);
            Class<?> shipCommand = Class.forName(SHIP_COMMAND_ENUM, true, loader);
            return new Object[] {
                    shipClass.getMethod("getWeaponGroupsCopy"),
                    shipClass.getMethod("getSelectedGroupAPI"),
                    shipClass.getMethod("getWeaponGroupFor", weaponApi),
                    groupApi.getMethod("isAutofiring"),
                    shipCommand.getField("TOGGLE_AUTOFIRE").get(null),
                    groupApi.getMethod("toggleOff")
            };
        } catch (Throwable ignore) {
            return new Object[6];
        }
    }

    /**
     * Active-tube mirror seams (the {@code resolveWeaponGroupMirrorHandles} idiom —
     * one try, one failure domain, all-null on Throwable = the tube-mirror lane no-ops fail-closed):
     * [0]=WeaponGroupAPI.getType, [1]=getActiveWeapon, [2]=getWeaponsCopy (public API), [3]=the
     * concrete {@code WeaponGroup.setCurrentActive(int)} pointer write (the engine AI's own conform
     * verb), [4]=the {@code WeaponGroupType.ALTERNATING}
     * constant (enum class loaded initialize=true like SHIP_COMMAND_ENUM — constants need clinit).
     */
    private static Object[] resolveWeaponGroupTubeMirrorHandles(ClassLoader loader) {
        try {
            Class<?> groupApi = Class.forName(WEAPON_GROUP_API, false, loader);
            Class<?> groupConcrete = Class.forName(WEAPON_GROUP_CLASS, false, loader);
            Class<?> groupType = Class.forName(WEAPON_GROUP_TYPE_ENUM, true, loader);
            return new Object[] {
                    groupApi.getMethod("getType"),
                    groupApi.getMethod("getActiveWeapon"),
                    groupApi.getMethod("getWeaponsCopy"),
                    groupConcrete.getMethod("setCurrentActive", int.class),
                    groupType.getField("ALTERNATING").get(null)
            };
        } catch (Throwable ignore) {
            return new Object[5];
        }
    }

    /**
     * BASE-spec fire-class reads (getter names
     * bytecode-verified against starfarer.api.jar — the spec-side id is
     * {@code getWeaponId} not {@code getId}, the beam test is {@code WeaponSpecAPI.isBeam()}
     * ({@code getType()} is the MOUNT type enum, wrong lever), and
     * {@code ProjectileWeaponSpecAPI} is a SIBLING of the beam spec interface, so instanceof is
     * a valid non-beam gate): [0]=WeaponAPI.getSpec, [1]=WeaponSpecAPI.isBeam,
     * [2]=WeaponSpecAPI.getWeaponId, [3]=ProjectileWeaponSpecAPI.getRefireDelay,
     * [4]=.getBurstSize, [5]=.getBurstDelay, [6]=the ProjectileWeaponSpecAPI {@code Class}.
     * One try / one failure domain (the {@code resolveWeaponMountHandles} idiom): all-null on
     * Throwable = {@code fireClassResolverForShip} answers class H fail-safe.
     */
    private static Object[] resolveWeaponSpecClassHandles(ClassLoader loader) {
        try {
            Class<?> weaponApi = Class.forName(WEAPON_API, false, loader);
            Class<?> weaponSpecApi =
                    Class.forName("com.fs.starfarer.api.loading.WeaponSpecAPI", false, loader);
            Class<?> projSpecApi =
                    Class.forName("com.fs.starfarer.api.loading.ProjectileWeaponSpecAPI", false, loader);
            return new Object[] {
                    weaponApi.getMethod("getSpec"),
                    weaponSpecApi.getMethod("isBeam"),
                    weaponSpecApi.getMethod("getWeaponId"),
                    projSpecApi.getMethod("getRefireDelay"),
                    projSpecApi.getMethod("getBurstSize"),
                    projSpecApi.getMethod("getBurstDelay"),
                    projSpecApi
            };
        } catch (Throwable ignore) {
            return new Object[7]; // all-null = fire-class resolver answers H fail-safe
        }
    }

    /**
     * Group-identity slot reads (one try / one failure domain, the
     * {@code resolveWeaponMountHandles} idiom): [0]={@code WeaponAPI.getSlot()},
     * [1]={@code WeaponSlotAPI.getId()} — both public API, bytecode-verified. All-null on
     * Throwable = the identity map stays identity (the earlier behavior), never a boot failure.
     */
    private static Method[] resolveWeaponSlotIdentityHandles(ClassLoader loader) {
        try {
            Class<?> weaponApi = Class.forName(WEAPON_API, false, loader);
            Class<?> slotApi =
                    Class.forName("com.fs.starfarer.api.loading.WeaponSlotAPI", false, loader);
            return new Method[] {
                    weaponApi.getMethod("getSlot"),
                    slotApi.getMethod("getId")
            };
        } catch (Throwable ignore) {
            return new Method[2];
        }
    }

    private static Method[] resolveWeaponMountHandles(ClassLoader loader, Class<?> shipClass) {
        try {
            Class<?> weaponApi = Class.forName(WEAPON_API, false, loader);
            // distanceFromArc's Vector2f param resolves from the same loader
            // chain as WeaponAPI itself (lwjgl rides the game classpath wherever WeaponAPI does).
            Class<?> vector2f = Class.forName("org.lwjgl.util.vector.Vector2f", false, loader);
            return new Method[] {
                    shipClass.getMethod("getAllWeapons"),
                    weaponApi.getMethod("getId"),
                    weaponApi.getMethod("getCooldownRemaining"),
                    weaponApi.getMethod("getCooldown"),
                    weaponApi.getMethod("isFiring"),
                    weaponApi.getMethod("usesAmmo"),
                    weaponApi.getMethod("getAmmo"),
                    weaponApi.getMethod("getMaxAmmo"),
                    weaponApi.getMethod("isDisabled"),
                    weaponApi.getMethod("getChargeLevel"),
                    weaponApi.getMethod("getFluxCostToFire"),
                    weaponApi.getMethod("distanceFromArc", vector2f),
                    // adopt-serve pair: [12] the muzzle-ε validation read,
                    // [13] the named-mount serve write (public WeaponAPI; the engine consumes
                    // the forced trigger one-shot self-clearing with every resource gate
                    // preserved — the scoped exception).
                    weaponApi.getMethod("getLocation"),
                    weaponApi.getMethod("setForceFireOneFrame", boolean.class),
                    // [14] the script force-disable read that un-folds the wire bit
                    weaponApi.getMethod("isForceDisabled")
            };
        } catch (Throwable ignore) {
            return new Method[14];
        }
    }

    /**
     * [0]=WeaponAPI.getCurrAngle; [1]=getSystem; [2..11]=ShipSystemAPI getId/getState/isActive/
     * isOn/getCooldownRemaining/getCooldown/getEffectLevel/getAmmo/getMaxAmmo/isOutOfAmmo. The
     * curr-angle read (index 0) is independent of the ship-system group, so it is resolved in its
     * own try (scout {@code resolveGameplayEdgeReadHandles}; the scout's
     * dormant instrumentation indices 12-15 are not carried — the builder uses only 0-11).
     */
    /**
     * Optional GPEE-v2 muzzle-speed read {@code WeaponAPI.getProjectileSpeed()}
     * (the flightBearingDeg composition) — own try like the
     * {@code getCurrAngle} handle above it: null just degrades the one scalar to NaN.
     */
    private static Method resolveWeaponProjectileSpeed(ClassLoader loader) {
        try {
            Class<?> weaponApi = Class.forName(WEAPON_API, false, loader);
            return weaponApi.getMethod("getProjectileSpeed");
        } catch (Throwable ignore) {
            return null;
        }
    }

    /**
     * [DamagingProjectileAPI.getElapsed, DamagingProjectileAPI.getWeapon,
     * CombatEntityAPI.getVelocity, CombatEntityAPI.getLocation, CombatEntityAPI.getFacing] —
     * the GPEE-v3 fresh-spawn bearing reads plus the v8 muzzle-position read
     * (all public API; interface Methods,
     * invokable on any live projectile/missile) plus
     * the fresh MISSILE's birth facing for the poll-path {@code LaunchFacingLaw}. Null slots
     * on failure: the binding degrades every {@code freshSpawnBearingDeg} (and the v8 muzzle
     * pair, and the facing) to NaN (the detector's v2-composition fallback), never the mount
     * tier.
     */
    private static Method[] resolveProjectileSpawnReadHandles(ClassLoader loader) {
        try {
            Class<?> projectileApi = Class.forName(DAMAGING_PROJECTILE_API, false, loader);
            Class<?> entityApi = Class.forName(COMBAT_ENTITY_API, false, loader);
            return new Method[] {
                    projectileApi.getMethod("getElapsed"),
                    projectileApi.getMethod("getWeapon"),
                    entityApi.getMethod("getVelocity"),
                    entityApi.getMethod("getLocation"),
                    entityApi.getMethod("getFacing")
            };
        } catch (Throwable ignore) {
            return new Method[5];
        }
    }

    private static Method[] resolveGameplayEdgeReadHandles(ClassLoader loader, Class<?> shipClass) {
        Method[] handles = new Method[12];
        try {
            Class<?> weaponApi = Class.forName(WEAPON_API, false, loader);
            handles[0] = weaponApi.getMethod("getCurrAngle");
        } catch (Throwable ignore) {
            handles[0] = null;
        }
        try {
            Class<?> systemApi = Class.forName(SHIP_SYSTEM_API, false, loader);
            handles[1] = shipClass.getMethod("getSystem");
            handles[2] = systemApi.getMethod("getId");
            handles[3] = systemApi.getMethod("getState");
            handles[4] = systemApi.getMethod("isActive");
            handles[5] = systemApi.getMethod("isOn");
            handles[6] = systemApi.getMethod("getCooldownRemaining");
            handles[7] = systemApi.getMethod("getCooldown");
            handles[8] = systemApi.getMethod("getEffectLevel");
            handles[9] = systemApi.getMethod("getAmmo");
            handles[10] = systemApi.getMethod("getMaxAmmo");
            handles[11] = systemApi.getMethod("isOutOfAmmo");
        } catch (Throwable ignore) {
            for (int i = 1; i < 12; i++) {
                handles[i] = null;
            }
        }
        return handles;
    }

    /** [getHullSize, getCollisionRadius, getMass] (collision/mass are CombatEntityAPI geometry). */
    private static Method[] resolveProfileHandles(ClassLoader loader, Class<?> shipClass) {
        try {
            // Touch the API classes so resolution fails as a group if the surface drifts.
            Class.forName(SHIP_API, false, loader);
            Class.forName(COMBAT_ENTITY_API, false, loader);
            Class.forName(HULL_SIZE_ENUM, false, loader);
            return new Method[] {
                    shipClass.getMethod("getHullSize"),
                    shipClass.getMethod("getCollisionRadius"),
                    shipClass.getMethod("getMass")
            };
        } catch (Throwable ignore) {
            return new Method[3];
        }
    }

    /**
     * [getMutableStats, getMaxSpeed, getAcceleration, getDeceleration, getMaxTurnRate,
     * getTurnAcceleration, MutableStat.getModifiedValue] (scout {@code resolveShipManeuverHandles}).
     *
     */
    private static Method[] resolveShipManeuverHandles(ClassLoader loader, Class<?> shipClass) {
        try {
            Class<?> statsClass = Class.forName(MUTABLE_SHIP_STATS_API, false, loader);
            Class<?> statClass = Class.forName(MUTABLE_STAT_API, false, loader);
            return new Method[] {
                    shipClass.getMethod("getMutableStats"),
                    statsClass.getMethod("getMaxSpeed"),
                    statsClass.getMethod("getAcceleration"),
                    statsClass.getMethod("getDeceleration"),
                    statsClass.getMethod("getMaxTurnRate"),
                    statsClass.getMethod("getTurnAcceleration"),
                    statClass.getMethod("getModifiedValue")
            };
        } catch (Throwable ignore) {
            return new Method[7];
        }
    }

    /**
     * Mixed-type command group: [0]=giveCommand(Method), [1]=useSystem(Method), [2..7]=movement
     * command enum constants (ACCELERATE, DECELERATE, STRAFE_LEFT, STRAFE_RIGHT, TURN_LEFT,
     * TURN_RIGHT), [8..10]=weapon command enum constants (SELECT_GROUP, USE_SELECTED_GROUP,
     * HOLD_FIRE), [11]=ACCELERATE_BACKWARDS (reverse thrust — wire bit 1),
     * [12]=VENT_FLUX (flux-vent command — wire bit 8),
     * [13]=TOGGLE_SHIELD_OR_PHASE_CLOAK (shield hold replay — wire bit 10; the
     * phase-cloak guard Method resolves separately via {@link #resolvePhaseCloakHandle}),
     * [14]=PULL_BACK_FIGHTERS (carrier fighter-mode state mirror - wire bit 12;
     * the isPullBackFighters read resolves via {@link #resolvePullBackFightersRead}). Null on
     * any failure (fail-soft: an unbound command sink / movement / weapon command).
     *
     * <p>ACCELERATE_BACKWARDS + VENT_FLUX are the two {@code ShipCommand} constants the faithful
     * {@link cc.starsector.server.runtime.PilotIntentFrame} apply-path needs but the prior
     * (LiveManeuver) lane never used — the scout's proven predict gate resolves both off this same
     * public enum. They join the same try as
     * the other constants (one failure domain: all are static fields of the same public enum).
     *
     * <p>Slot [9] is {@code USE_SELECTED_GROUP}, not
     * {@code FIRE} (D1). Both run the same engine body
     * ({@code fire(true, aim, dt)}), but only {@code USE_SELECTED_GROUP} is refused by the
     * {@code noFiring} system lock: a blocking system adds the internal {@code USE_SELECTED_GROUP} twin alone
     * and the command filter drops it.
     * Vanilla's own click and the AI
     * issue the same constant.
     */
    private static Object[] resolveCommandHandles(ClassLoader loader, Class<?> shipClass) {
        Object[] out = new Object[15];
        try {
            Class<?> shipCommand = Class.forName(SHIP_COMMAND_ENUM, true, loader);
            out[0] = shipClass.getMethod("giveCommand", shipCommand, Object.class, int.class);
            out[1] = shipClass.getMethod("useSystem");
            out[2] = shipCommand.getField("ACCELERATE").get(null);
            out[3] = shipCommand.getField("DECELERATE").get(null);
            out[4] = shipCommand.getField("STRAFE_LEFT").get(null);
            out[5] = shipCommand.getField("STRAFE_RIGHT").get(null);
            out[6] = shipCommand.getField("TURN_LEFT").get(null);
            out[7] = shipCommand.getField("TURN_RIGHT").get(null);
            out[8] = shipCommand.getField("SELECT_GROUP").get(null);
            out[9] = shipCommand.getField("USE_SELECTED_GROUP").get(null);
            out[10] = shipCommand.getField("HOLD_FIRE").get(null);
            out[11] = shipCommand.getField("ACCELERATE_BACKWARDS").get(null);
            out[12] = shipCommand.getField("VENT_FLUX").get(null);
            out[13] = shipCommand.getField("TOGGLE_SHIELD_OR_PHASE_CLOAK").get(null);
            out[14] = shipCommand.getField("PULL_BACK_FIGHTERS").get(null);
        } catch (Throwable ignore) {
            for (int i = 0; i < out.length; i++) {
                out[i] = null;
            }
        }
        return out;
    }

    /**
     * The {@code Ship.getPhaseCloak()} read backing the shield lane's phase guard (the
     * TOGGLE_SHIELD_OR_PHASE_CLOAK verb phase-toggles on cloak hulls — the binding never issues it
     * where a cloak exists). Fail-soft null, and the binding then FAILS CLOSED (shield lane
     * unexpressed) rather than risking a cloak strobe.
     */
    private static Method resolvePhaseCloakHandle(Class<?> shipClass) {
        try {
            return shipClass.getMethod("getPhaseCloak");
        } catch (Throwable ignore) {
            return null;
        }
    }

    /**
     * The public {@code ShipAPI.isPullBackFighters()} read backing the
     * carrier fighter-mode state mirror (wire bit 12; {@code EngineCombatBinding#applyPullBackBit}
     * compares it against the bit and issues one PULL_BACK_FIGHTERS toggle per mismatched tick).
     * Fail-soft null => the lane FAILS CLOSED (no state read, no issue).
     */
    private static Method resolvePullBackFightersRead(Class<?> shipClass) {
        try {
            return shipClass.getMethod("isPullBackFighters");
        } catch (Throwable ignore) {
            return null;
        }
    }

    /**
     * {@code MutableShipStatsAPI.getTimeMult()} — the live per-ship
     * time-mult read the motion view crosses with the shipManeuver group's
     * {@code MutableStat.getModifiedValue()}. Fail-soft null => the motion view rides the
     * factory constant (exactly the pre-1264 behavior).
     */
    private static Method resolveShipTimeMultReadHandle(ClassLoader loader) {
        try {
            return Class.forName(MUTABLE_SHIP_STATS_API, false, loader).getMethod("getTimeMult");
        } catch (Throwable ignore) {
            return null;
        }
    }

    /**
     * {@code Ship.isPhased()} — the ship-level phased/collision-class
     * truth completing the phase-cloak state tier
     * ({@code EngineCombatBinding.shipPhaseCloakStatesOrNull()}). Fail-soft null => that tier
     * reads unavailable, like every other view tier.
     */
    private static Method resolveShipIsPhasedHandle(Class<?> shipClass) {
        try {
            return shipClass.getMethod("isPhased");
        } catch (Throwable ignore) {
            return null;
        }
    }

    /**
     * [getCurrentCR, getTimeDeployedForCRReduction,
     * MutableShipStatsAPI.getPeakCRDuration, StatBonus.computeEffective(float),
     * ShipHullSpecAPI.getNoCRLossTime] — the CR-clock reads completing
     * {@code EngineCombatBinding.shipCrStatesOrNull()} (the stats/hull-spec roots ride
     * the already-bound shipManeuver/shipIdentity handles). Null-array on any failure
     * (fail-soft: the CR tier then reads unavailable, like every other view tier).
     */
    private static Method[] resolveShipCrReadHandles(ClassLoader loader, Class<?> shipClass) {
        try {
            Class<?> statsClass = Class.forName(MUTABLE_SHIP_STATS_API, false, loader);
            Class<?> statBonusClass = Class.forName(STAT_BONUS_API, false, loader);
            Class<?> hullSpecApi = Class.forName(SHIP_HULL_SPEC_API, false, loader);
            return new Method[] {
                    shipClass.getMethod("getCurrentCR"),
                    shipClass.getMethod("getTimeDeployedForCRReduction"),
                    statsClass.getMethod("getPeakCRDuration"),
                    statBonusClass.getMethod("computeEffective", float.class),
                    hullSpecApi.getMethod("getNoCRLossTime"),
                    // PUBLIC ShipAPI, the fighter panel's rate source.
                    shipClass.getMethod("getSharedFighterReplacementRate")
            };
        } catch (Throwable ignore) {
            return new Method[6]; // Was 5; the success array is 6 (shipCrReads[5] AIOOBE)
        }
    }

    /**
     * (B) Option A AI-suppression handles: [0]={@code setShipAI(ShipAIPlugin)}, [1]={@code getShipAI()}.
     * Null on any failure (fail-soft: the binding then leaves the deploy's {@code BasicShipAI} in
     * place and client commands are fought by it). {@code ShipAIPlugin} is the public-API arg type
     * {@code Ship.setShipAI} takes; {@code setShipAI(null)} routes straight to {@code setAI(null)}.
     * Resolved here (the version-specific caller) and
     * handed to {@link EngineCombatBinding}; the fake binding leaves them null so its path is
     * byte-identical.
     */
    private static Method[] resolveAiSuppressionHandles(ClassLoader loader, Class<?> shipClass) {
        try {
            Class<?> shipAiPlugin = Class.forName(SHIP_AI_PLUGIN_API, false, loader);
            return new Method[] {
                    shipClass.getMethod("setShipAI", shipAiPlugin),
                    shipClass.getMethod("getShipAI")
            };
        } catch (Throwable ignore) {
            return new Method[2];
        }
    }

    /**
     * motion-WRITE seam: [0]=setLoc(Method), [1]=setVel(Method),
     * [2]=Vector2f(float,float) ctor (Constructor). Null on failure (fail-soft: pose-write
     * unsupported, the binding then rejects owned-pose writes {@code REJECTED_UNSUPPORTED}).
     */
    private static Object[] resolveMotionWriteSeam(ClassLoader loader, Class<?> shipClass) {
        try {
            Class<?> vec = Class.forName(LWJGL_VECTOR2F_CLASS, true, loader);
            return new Object[] {
                    shipClass.getMethod("setLoc", vec),
                    shipClass.getMethod("setVel", vec),
                    vec.getConstructor(float.class, float.class)
            };
        } catch (Throwable ignore) {
            return new Object[3];
        }
    }

    /**
     * The facing-WRITE handle: {@code CombatEntityAPI.setFacing(float)} resolved off the
     * ship class (ShipAPI extends CombatEntityAPI). Null on failure (fail-soft: the binding then rejects
     * facing writes {@code REJECTED_UNSUPPORTED} and the pilot apply skips the facing pin).
     */
    private static Method resolveFacingWriteHandle(Class<?> shipClass) {
        try {
            return shipClass.getMethod("setFacing", float.class);
        } catch (Throwable ignore) {
            return null;
        }
    }

    /**
     * The travel-drive read: {@code isTravelDriveOn()} — public on the concrete
     * {@code Ship} (NOT on ShipAPI) — the engine's
     * own "the travel drive is flying this hull" signal (fly-in burn / retreat; vanilla
     * {@code CombatState.advance} blocks player commands on the same window). Null on failure
     * (fail-soft: the binding then pins facing unconditionally, the earlier behavior).
     */
    private static Method resolveTravelDriveReadHandle(Class<?> shipClass) {
        try {
            return shipClass.getMethod("isTravelDriveOn");
        } catch (Throwable ignore) {
            return null;
        }
    }

    /**
     * The fire-lock reads behind the adopt lane's lock wait
     * (D2) and the travel-drive wire bit (D3), all public on the concrete {@code Ship}
     * class: [0]={@code getSystem()},
     * [1]={@code getTravelDrive()}, [2]={@code getPhaseCloak()} — each returns
     * the engine system class {@code combat.systems.F}; [3]={@code F.shouldBlockCommands()},
     * [4]={@code F.getSpec()}, both resolved off the getters' shared RETURN TYPE;
     * [5]={@code isFiringAllowed()} off {@code getSpec}'s return type; [6]=
     * {@code getBlockedCommands()} (an {@code EnumSet} of the ship's internal command enum); [7]=the internal
     * {@code USE_SELECTED_GROUP} twin constant,
     * its class read from [6]'s generic return type argument. [0..5] are vanilla's click gate
     * verbatim; [6]+[7] are the refusal set the command
     * filter tests. All-null on any failure, which
     * {@code requireResolved} turns into a loud boot failure under the default strict bind (a
     * silent fire-parity gap is the worse outcome).
     */
    private static Object[] resolveFireLockHandles(Class<?> shipClass) {
        Object[] out = new Object[8];
        try {
            Method getSystem = shipClass.getMethod("getSystem");
            Method getTravelDrive = shipClass.getMethod("getTravelDrive");
            Method getPhaseCloak = shipClass.getMethod("getPhaseCloak");
            Class<?> systemClass = getSystem.getReturnType();
            if (getTravelDrive.getReturnType() != systemClass
                    || getPhaseCloak.getReturnType() != systemClass) {
                return new Object[8]; // the three getters no longer share the engine system class
            }
            Method shouldBlockCommands = systemClass.getMethod("shouldBlockCommands");
            Method getSpec = systemClass.getMethod("getSpec");
            Method isFiringAllowed = getSpec.getReturnType().getMethod("isFiringAllowed");
            Method getBlockedCommands = shipClass.getMethod("getBlockedCommands");
            java.lang.reflect.Type setType = getBlockedCommands.getGenericReturnType();
            if (!(setType instanceof java.lang.reflect.ParameterizedType parameterized)
                    || !(parameterized.getActualTypeArguments()[0] instanceof Class<?> commandEnum)
                    || !commandEnum.isEnum()) {
                return new Object[8];
            }
            // Read the FIELD: the
            // constant's name() is still the source-level string
            // ("USE_SELECTED_GROUP"), not the field's name, so an Enum.valueOf by field name throws
            // and the strict tier would fail every real-engine boot.
            Object useSelectedGroup = commandEnum.getField("oO0000").get(null);
            out[0] = getSystem;
            out[1] = getTravelDrive;
            out[2] = getPhaseCloak;
            out[3] = shouldBlockCommands;
            out[4] = getSpec;
            out[5] = isFiringAllowed;
            out[6] = getBlockedCommands;
            out[7] = useSelectedGroup;
        } catch (Throwable t) {
            // Name the cause: requireResolved's "game patch?" line alone hid a resolve bug (S1-1).
            System.out.println("real-engine-binding-factory fire-lock resolve_failed cause=" + t);
            return new Object[8];
        }
        return out;
    }

    /**
     * The fast-time read: {@code isInFastTimeAdvance()} — public {@code CombatEngineAPI}
     * surface (TRUE only during the fast-time extra
     * passes) — the DD1 shim's single-issuer gate. Null on
     * failure keeps the shim uninstallable (fail-closed at the install site).
     */
    private static Method resolveIsInFastTimeAdvance(Class<?> engineClass) {
        try {
            return engineClass.getMethod("isInFastTimeAdvance");
        } catch (Throwable ignore) {
            return null;
        }
    }

    /**
     * The retreat-apply seam (ONE failure domain — all four handles or none, the design's
     * "load-bearing drive engage" invariant): [0]={@code setRetreating(boolean,boolean)} (public
     * API, resolved off the concrete ship class), [1]=the vanilla engage static
     * on {@code RetreatManeuver} taking {@code (Ship,boolean)} (the
     * exact call vanilla's retreat keypress makes;
     * 3 static overloads share one internal name, so the
     * (Ship,boolean) parameter list IS the disambiguator; the boolean is a require-reachable gate,
     * vanilla passes {@code false} = engage unconditionally), [2]={@code turnOffTravelDrive()}
     * (public on {@code Ship}; zeroes the drive duration — the tracker flips on the NEXT
     * advance, so the same-frame clear is guard-refused, and the
     * level re-assert retries it), [3]={@code isRetreating()} (public on {@code Ship} — the
     * hull-truth read the level re-assert compares the wire bit against).
     * Null quad on any failure (fail-soft: the binding's retreat lane no-ops).
     */
    private static Method[] resolveRetreatApplySeam(ClassLoader loader, Class<?> shipClass) {
        try {
            Class<?> retreatManeuver = Class.forName(RETREAT_MANEUVER_CLASS, false, loader);
            return new Method[] {
                    shipClass.getMethod("setRetreating", boolean.class, boolean.class),
                    retreatManeuver.getMethod("o00000", shipClass, boolean.class),
                    shipClass.getMethod("turnOffTravelDrive"),
                    shipClass.getMethod("isRetreating")
            };
        } catch (Throwable ignore) {
            return new Method[4];
        }
    }

    /**
     * R-key target-designation seam: [0]={@code setShipTarget(ShipAPI)} (the SETTER —
     * re-derives {@code lastFriendlyTarget}; never a field write), [1]={@code getShipTarget()}
     * (read-compare-issue), [2]={@code isFighter()} (designation reject — vanilla's pick can never
     * produce a fighter). All public {@code ShipAPI} surface resolved off the ship class. Null
     * triple on any failure (fail-soft: the binding's target lane no-ops).
     */
    private static Method[] resolveShipTargetSeam(ClassLoader loader, Class<?> shipClass) {
        try {
            Class<?> shipApi = Class.forName(SHIP_API, false, loader);
            return new Method[] {
                    shipClass.getMethod("setShipTarget", shipApi),
                    shipClass.getMethod("getShipTarget"),
                    shipClass.getMethod("isFighter")
            };
        } catch (Throwable ignore) {
            return new Method[3];
        }
    }

    /**
     * aim-carry seam: the owned ship's LIVE {@code getMouseTarget()} vector (the engine's
     * {@code Ship.advance} weapon-group loop's per-frame manual-group slew target — the field the
     * server never wrote before v3, leaving mounts tracking world origin) + in-place
     * {@code Vector2f.set(float,float)}. In-place is load-bearing: the engine's field is aliased
     * (shield.advance holds the same object), so the binding only ever MUTATES the live vector,
     * never replaces the reference — the vanilla idiom (player {@code CombatState} + every AI).
     * Null pair on failure (fail-soft: the binding skips the aim pin, like the facing pin).
     */
    private static Method[] resolveMouseTargetSeam(ClassLoader loader, Class<?> shipClass) {
        try {
            Class<?> vec = Class.forName(LWJGL_VECTOR2F_CLASS, true, loader);
            return new Method[] {
                    shipClass.getMethod("getMouseTarget"),
                    vec.getMethod("set", float.class, float.class)
            };
        } catch (Throwable ignore) {
            return new Method[2];
        }
    }

    /**
     * The activation guard: {@code ShipSystemAPI.canBeActivated()} — the guard the proven
     * predict gate checks before each rising-edge/retry {@code useSystem()}. Null on
     * failure (fail-soft: the binding falls back to an unguarded {@code useSystem()}, which the engine's own
     * charge tracker still no-ops during cooldown).
     */
    private static Method resolveSystemActivationGuard(ClassLoader loader) {
        try {
            Class<?> systemApi = Class.forName(SHIP_SYSTEM_API, false, loader);
            return systemApi.getMethod("canBeActivated");
        } catch (Throwable ignore) {
            return null;
        }
    }

    /** The single static field of {@code fieldType} on {@code owner}, or null unless exactly one. */
    private static Field uniqueStaticFieldOfType(Class<?> owner, Class<?> fieldType) {
        Field found = null;
        int count = 0;
        for (Field f : owner.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == fieldType) {
                found = f;
                count++;
            }
        }
        return count == 1 ? found : null;
    }

    private static Object unsafeSingleton() throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        return unsafeField.get(null);
    }

    private static Object allocateUnsafeInstance(Object unsafe, Class<?> targetClass) throws Exception {
        Method allocateInstance = unsafe.getClass().getMethod("allocateInstance", Class.class);
        return allocateInstance.invoke(unsafe, targetClass);
    }

    private static Throwable unwrap(Throwable t) {
        return (t instanceof InvocationTargetException && t.getCause() != null) ? t.getCause() : t;
    }
}
