package cc.starsector.server.bridge;

import cc.starsector.server.match.MatchDescriptor;
import cc.starsector.server.match.MatchDescriptorCodec;
import cc.starsector.server.match.MatchDescriptors;
import cc.starsector.server.match.OfficerRosterCodec;
import cc.starsector.server.platform.AllSkillsAttach;
import cc.starsector.server.platform.HeadlessCombatContext;
import cc.starsector.server.platform.OfficerAttach;
import cc.starsector.server.platform.RegistryVariantClone;
import cc.starsector.server.runtime.ServerConfig;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;

/**
 * roster-spawn over the real-engine binding: deploys the fixed two-Mudskipper-Mk-II
 * demo roster (owned ids 1L / 2L) into the live headless {@code CombatEngine} the
 * {@link RealEngineCombatBindingFactory} built, so the served combat has real ships rather than
 * an empty space (the server had bound and served {@code sessions_served=0} over an empty roster — the
 * serve gate never required ships; this is the content step that fills it).
 *
 * <p><b>Faithful lane (R2, not a hack).</b> Ships are deployed through the
 * engine's OWN deploy path — per-side an engine-minted {@code CombatFleetManager} registered via
 * {@code addFleet(sideSpec, FleetGoal.ATTACK)} (W4/W5), per ship a real
 * {@code FleetMember(owner, HullVariantSpec, SHIP)} + {@code setShipName} spawned through
 * {@code CombatFleetManager.spawnFleetMember -> deploy -> instantiateForCombat -> addObject}
 * under pre-deploy gating (W1), the player seat written to the engine's own field (W3). This is
 * the proven {@link HeadlessCombatContext#builder() engineDeployRosterInstall} treatment
 * (battery 20/20, the demo-program R2 done-condition: ships are engine-alive to every
 * {@code isShipAlive} consumer with NO {@code setDoNotEndCombat} crutch). The handle-resolution
 * here is the product-owned port of the scout's proven block
 * of handle resolution; the difference is that the scout
 * sourced its {@code HullVariantSpec} from a pre-built gate ship, while this resolves it by id
 * from the engine's variant registry (the dedicated server has no prior roster).
 *
 * <p><b>Construction-time deploy (L1 Option A).</b> {@link RealEngineCombatBindingFactory#create()}
 * calls {@link #deployMatchRoster()} once at t=0 after it has installed + {@code init()}'d the
 * combat map, so the cached binding wraps a roster'd engine. The session-lifecycle reset loop (2b,
 * {@code MATCH_RESET}) re-deploys a fresh roster per round by calling the same method after a reset
 * ({@link #resetForRedeploy()} clears the idempotency latch). Idempotent within a round: a second
 * call without a reset is a no-op.
 *
 * <p><b>Variant identity — the cc variant by default, stock via sysprop.</b> The default is the
 * cc_client {@code mudskipper2_cc_Hellbore} skin/variant (the SAME dmod-free hull the predict client
 * flies); it resolves headless because {@link RealEngineContentInit} now front-inserts the enabled
 * mod's DATA directory into the resource registry, so the engine's own loaders register the
 * {@code mudskipper2_cc} skin (of base {@code mudskipper}) + the variant by directory scan, with no
 * hand-built specs and no runtime dmod-strip (the audit's explicit "do NOT port the runtime strip").
 * (variant-on-BOTH-sides, [[prefer-mod-content-over-runtime-hacks]]). Both duel ships are built from
 * this one variant, so neither side carries the {@code ill_advised} dmod whose RNG malfunctions would
 * otherwise jam at different times on the authoritative server vs the predicting client and desync the
 * duel. Override back to the stock dmod-bearing {@code mudskipper2_Hellbore} with
 * {@code -Dcc.server.demoVariant=mudskipper2_Hellbore} (e.g. a base-content-only run). Resolution is
 * <b>fail-closed</b>: an unresolved id throws with the registry size + whether stock vs cc is present
 * (distinguishing "no content loaded" from "cc mod dir not on the resource path"), surfacing the
 * content-loading boundary honestly rather than silently substituting a different ship.
 *
 * <p><b>Option R — literal identities, pinned.</b> Like the sibling factory,
 * every engine-internal {@code com.fs.*} identity fed into reflection is rendered literally in the
 * {@code PINNED 0.98a-RC8} block, byte-identical to the proven scout lane; descriptor-grade
 * resolution is the documented next-RC upgrade (standing portability-drill trigger).
 */
public final class RealEngineDemoRoster {

    // ---- PINNED 0.98a-RC8 (descriptor-grade identity owed at the next RC). Option R. ----
    // Several identities are package-private: {@link SeatRebind}
    // resolves through this SAME pinned block — one block, never duplicated.

    /** Combat engine singleton (the same instance {@code create()} constructed). */
    static final String ENGINE_CLASS = "com.fs.starfarer.combat.CombatEngine";
    /** Concrete combat ship type; the {@code getList(Class)} key. */
    static final String SHIP_CLASS = "com.fs.starfarer.combat.entities.Ship";
    /** Hull variant spec; the {@code FleetMember} ctor arg + the registry's value type. */
    private static final String HULL_VARIANT_SPEC_CLASS = "com.fs.starfarer.loading.specs.HullVariantSpec";
    /** Variant registry; the no-arg method = id set, the (String) overload = lookup. */
    private static final String VARIANT_REGISTRY_CLASS = "com.fs.starfarer.loading.B";
    /** Registry method name, engine-internal (overloaded by arity: () -> ids, (String) -> variant). */
    private static final String VARIANT_REGISTRY_METHOD = "o00000";
    /** Real campaign fleet member (the engine-own deploy unit). */
    private static final String FLEET_MEMBER_CLASS = "com.fs.starfarer.campaign.fleet.FleetMember";
    /** Per-side deployer minted by {@code addFleet}; drives {@code spawnFleetMember}. */
    private static final String COMBAT_FLEET_MANAGER_CLASS = "com.fs.starfarer.combat.CombatFleetManager";
    // ADMIRAL: vanilla's fleet commander (a plainly named engine class) + its API plugin face.
    private static final String ADMIRAL_AI_CLASS = "com.fs.starfarer.combat.ai.admiral.AdmiralAI";
    private static final String ADMIRAL_AI_PLUGIN_API = "com.fs.starfarer.api.combat.AdmiralAIPlugin";
    /** The wave (AI) side every fortress chain spawns onto — the admiral's side. */
    private static final int WAVE_SIDE = 1;
    /** The engine-internal player-ship field {@code create()}'s factory binds the seat into (W3). */
    private static final String PLAYER_SHIP_FIELD = "OO0000";

    // ---- Public API + LWJGL (identity-clean even under descriptor-grade). ----
    private static final String FLEET_MEMBER_TYPE_API = "com.fs.starfarer.api.fleet.FleetMemberType";
    private static final String FLEET_MEMBER_API = "com.fs.starfarer.api.fleet.FleetMemberAPI";
    static final String SHIP_API = "com.fs.starfarer.api.combat.ShipAPI";
    /** The cr_effect deploy-CR seed read (stat getters resolve on the PUBLIC interfaces). */
    private static final String MUTABLE_SHIP_STATS_API =
            "com.fs.starfarer.api.combat.MutableShipStatsAPI";
    private static final String MUTABLE_STAT_CLASS = "com.fs.starfarer.api.combat.MutableStat";
    private static final String FLEET_GOAL_API = "com.fs.starfarer.api.fleet.FleetGoal";
    static final String LWJGL_VECTOR2F_CLASS = "org.lwjgl.util.vector.Vector2f";
    /** applyDamage descriptor types (the wave-skip kill lane). */
    static final String COMBAT_ENTITY_API = "com.fs.starfarer.api.combat.CombatEntityAPI";
    static final String DAMAGE_TYPE_API = "com.fs.starfarer.api.combat.DamageType";
    /**
     * PhaseAnchor's emergency-dive one-shot: {@code canDive} iff the engine's customData map LACKS
     * this key (bare key, battle-global, consumed by the first dive of ANY ship).
     * {@code SeatRebind.forceKillHull} plants it around its
     * {@code applyDamage} so kill verbs suppress the dive intercept.
     */
    static final String PHASE_ANCHOR_CAN_DIVE_KEY = "phaseAnchor_canDive";

    /** System property to override the demo variant id (e.g. back to the stock dmod-bearing hull). */
    private static final String DEMO_VARIANT_PROP = "cc.server.demoVariant";
    /** The cc_client dmod-free skin the real predict client flies (variant-on-both-sides target). */
    private static final String CLIENT_DEMO_VARIANT_ID = "mudskipper2_cc_Hellbore";
    /**
     * Default = the cc_client dmod-free variant: the server now spawns the SAME hull the
     * predict client flies, so the two simulations don't diverge on the {@code ill_advised} dmod's RNG
     * malfunctions ([[prefer-mod-content-over-runtime-hacks]] — a mod-defined variant/skin on both
     * sides, NOT a runtime strip). Resolves headless because {@link RealEngineContentInit} now
     * front-inserts the enabled mod data dir into the resource registry, so the engine's own loaders
     * register the cc skin + variant. Override back to stock with {@code -Dcc.server.demoVariant=}.
     */
    private static final String DEFAULT_DEMO_VARIANT_ID = CLIENT_DEMO_VARIANT_ID;
    /** Stock dmod-bearing variant (base content) — the fail-closed "is base content present?" probe. */
    private static final String STOCK_DEMO_VARIANT_ID = "mudskipper2_Hellbore";

    /**
     * Deploy distance from the center line (one engine map-half is the map.half knob). The legacy
     * no-descriptor body keeps its byte-compat X-axis shape (∓this x, facing 0/180); the
     * descriptor formation uses it on the VANILLA axis (∓this y, facing 90/270 —
     * the engine's own home edges are the Y-edges, so the descriptor layout now
     * matches the client mission's vanilla deploy orientation instead of snapping seats sideways).
     */
    // Package-private: {@link DescriptorLayout#formationPosition} (the cut-2 carve)
    // reads this same pinned deploy distance; the two-Mudskipper fallback lane below still uses it.
    static final float SPAWN_X = 1500.0f;

    /**
     * Mid-round wave spawn placement: explicit
     * edge-margin loc — law at the {@code midDeployWaveShip} spawn site. Margin clears the
     * largest hull sprite half (<500) so the hull starts fully outside the border and the
     * fly-in burn carries it in; the X ladder staggers same-wave hulls deterministically
     * (no {@code Math.random} on the sim path).
     */
    private static final float WAVE_SPAWN_EDGE_MARGIN = 600.0f;
    private static final float[] WAVE_SPAWN_X_SLOTS =
            {0.0f, -900.0f, 900.0f, -1800.0f, 1800.0f, -2700.0f, 2700.0f};
    /** Rotating slot index; reset per round for cross-run determinism. */
    private static int waveSpawnXSlot;

    /**
     * The current round's deploy treatment (engine fleet listeners + rollback). Kept for the round so
     * the listeners survive the served session; {@link #resetForRedeploy()} closes it (LIFO rollback)
     * at a MATCH_RESET boundary so the next round redeploys onto a clean engine.
     */
    private static HeadlessCombatContext deployContext;
    /** Idempotency latch within a round (cleared per reset by {@link #resetForRedeploy()});
     * package-private: {@link SeatRebind}'s gate reads ride the shared roster monitor. */
    static boolean deployed;
    /** The variant id the last deploy actually used (descriptor path: the player seat's; diagnostics). */
    private static String deployedVariantId = "";
    /** Authoritative count of ships the last successful deploy spawned (0 if none). */
    private static int deployedShipCount;
    /**
     * The variant id fielded per
     * seat id this round — by the round's deploy lane OR a seat fly-in ({@link #midDeploySeat},
     * which is also the gated reset's seat lane), last write wins; the seat's pick when it
     * resolved, else its descriptor row. Cleared per {@link #resetForRedeploy()}.
     */
    private static Map<Integer, String> deployedVariantBySeatId = new LinkedHashMap<>();
    /** Picks fielded this round (deploy lane + seat fly-ins; cleared per reset). */
    private static int overrideCount;
    /** Picks refused to the descriptor this round (deploy lane + seat fly-ins). */
    private static int overrideFallbackCount;
    /**
     * Wire ids of this round's SEAT ships in seatId order: the descriptor's
     * {@code seat.ship} values, or {@code {1, 2}} on the default no-descriptor path. Empty until a
     * deploy succeeds. AI-row ships are deliberately EXCLUDED — the engine's own AI flies them, so
     * they must never be command/pose/idle targets ({@code EngineCombatBinding.ownedShipIds}).
     */
    private static long[] deployedSeatShipIds = new long[0];
    /**
     * Deploy-recorded stable engine id ({@code ShipAPI.getId()}, trimmed — byte-identical to the
     * {@code shipLifecyclesOrNull()} stable key) per seat wire id, in seatId order.
     * The serve loop pre-seeds each round's {@code ShipNetworkIdRegistry} reserved band from this,
     * making seat entityId == seat wire id by construction. Fresh per deploy; empty until one.
     * Package-private: {@link SeatRebind} re-points it under the roster monitor (the
     * carve — the maps stay roster-owned, the rebind verbs live there).
     */
    static Map<Long, String> seatStableIdByWireId = Map.of();
    /**
     *
     * EVERY stable id a seat hull carried this round: the t=0 seat hulls, each rebind target
     * and each fly-in, kept when {@link #seatStableIdByWireId} re-points away — the wave-skip
     * sweep's spare, so a hull a player left (a transfer source, an earlier life) is never swept
     * as a wave corpse. Grows only; {@link #resetForRedeploy()} clears it with the map.
     * Package-private: {@link SeatRebind} adds each re-point's target under the roster monitor.
     */
    static final Set<String> seatStableIdsThisRound = new LinkedHashSet<>();
    /**
     * The descriptor the current round deployed under (null on the default two-ship path) — the
     * {@link #midDeploySeat} recipe source: variant/name/side for a mid-round
     * fly-in come from the SAME authored truth the round's t=0 deploy used.
     */
    static MatchDescriptor deployedDescriptor;
    /**
     * ALL descriptor seat wire ids in seatId order, occupancy-INDEPENDENT (empty on the default
     * path or pre-deploy) — the D8 {@code EngineCombatBinding.ownedShipIds} source: an intent for
     * a minted-but-unmounted seat must pass the owned set and resolve null (harmless drop), never
     * be rejected as foreign.
     */
    private static long[] descriptorSeatShipIds = new long[0];
    /**
     * Live engine ship per seat wire id for the current round: grown at deploy
     * from the ledger's first deployed-seat slots and by each {@link #midDeploySeat} fly-in;
     * cleared per reset. The {@code EngineCombatBinding.LiveWireShipResolver} authority — when
     * live, a wire id resolves HERE or not at all (an unmounted seat's intent must never fall
     * through to a positional read that could command the wrong ship).
     * Package-private: {@link SeatRebind} mounts/vacates entries under the roster monitor (the
     * carve — the maps stay roster-owned, the rebind verbs live there).
     */
    static final Map<Long, Object> liveShipByWireId = new LinkedHashMap<>();
    /**
     * Stable engine ids of ALL hulls the round's t=0 deploy spawned (deploy-ledger order:
     * seats first, then AI rows; empty until a deploy, fresh per deploy) — the serve loop's
     * growth known-keys seed: deploy-lane hulls must never be
     * growth-announced. Fly-ins/waves deliberately never join it (their own serve-loop
     * lanes seed their keys at mint).
     */
    private static List<String> deployedHullStableIds = List.of();
    /**
     * The round's deployed STATION BASE hull, held
     * from the deploy readback (null when the round fielded no station). The lose seam reads
     * {@code isHulk()} off THIS reference every tick — never a repo re-scan, which would have
     * to re-identify the base among ~24 modules of the same family.
     */
    static Object stationBaseShip;
    /** Stable engine id of {@link #stationBaseShip} (empty when no station deployed). */
    private static String stationBaseStableId = "";
    /**
     * Cached reflective {@code ShipAPI.isHulk()} handle for {@link #stationBaseShip}, resolved
     * ONCE at the deploy readback (the per-tick lose-seam read must not re-resolve).
     */
    private static Method stationBaseIsHulk;

    /** Fail-closed wrapper so caller code stays off the engine-internal/reflective surface. */
    public static final class RealEngineDemoRosterException extends RuntimeException {
        public RealEngineDemoRosterException(String message) {
            super(message);
        }

        public RealEngineDemoRosterException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private RealEngineDemoRoster() {
    }

    /** Whether the demo roster has been deployed into the live engine this round. */
    public static synchronized boolean deployed() {
        return deployed;
    }

    /** The variant id the last successful deploy used (empty if none). */
    public static synchronized String deployedVariantId() {
        return deployedVariantId;
    }

    /**
     * The variant id last FIELDED
     * for {@code seatId} this round (deploy lane or seat fly-in, last write wins) — its pick,
     * else its descriptor row — or null when no hull was fielded for the seat this round.
     */
    public static synchronized String seatDeployedVariantId(int seatId) {
        return deployedVariantBySeatId.get(seatId);
    }

    /** {@code seat:id,…} of the last deploy ({@code -} = none). */
    public static synchronized String seatDeployedVariantsRow() {
        if (deployedVariantBySeatId.isEmpty()) {
            return "-";
        }
        StringBuilder row = new StringBuilder();
        for (Map.Entry<Integer, String> entry : deployedVariantBySeatId.entrySet()) {
            row.append(row.length() == 0 ? "" : ",").append(entry.getKey()).append(':')
                    .append(entry.getValue());
        }
        return row.toString();
    }

    /** Carried picks the last deploy fielded. */
    public static synchronized int overrideCount() {
        return overrideCount;
    }

    /** Carried picks the last deploy refused (descriptor fallback). */
    public static synchronized int overrideFallbackCount() {
        return overrideFallbackCount;
    }

    /**
     * Authoritative count of ships the demo roster deployed this round, sourced from the engine-own
     * deploy ledger ({@code engineDeployedShips()}) — NOT from a pre-drain
     * {@code getObjects().getList(Ship)} read. Zero if no roster has deployed.
     *
     * <p>This is the faithful ship-count the serve loop bounds its snapshot source by. The 2
     * Mudskippers are deployed onto the engine's PENDING-ADD queue at construction (L1 Option A) and
     * only drain into the repository ship list on the first {@code advanceInner} — confirmed statically
     * ({@code advanceInner -> executeAdds()} drains the pending-add list) and runtime-confirmed
     * (a green run measured {@code drain_frame=0}) — so the repo reads empty (0) at startup. The
     * deploy ledger already recorded that 2 real ships were spawned: that is the count, not the
     * temporally-too-early repo read that made {@code serveUntilShutdown} fail closed
     * {@code shipCountBound=0} (the earlier serve blocker). This is NOT a count-hack — it is the
     * engine's own recorded deployment ([[no-hacky-shortcuts]]).
     */
    public static synchronized int deployedShipCount() {
        return deployedShipCount;
    }

    /**
     * Wire ids of this round's SEAT ships in seatId order (empty if no deploy yet) — the
     * descriptor's {@code seat.ship} values, or {@code {1, 2}} on the default path. This is the
     * {@code EngineCombatBinding.ownedShipIds} set: seat ships only, never AI-row
     * ships (the engine AI flies those; {@code idleOwnedShipAi} would wrongly strip them).
     */
    public static synchronized long[] deployedSeatShipIds() {
        return deployedSeatShipIds.clone();
    }

    /**
     * Deploy-recorded stable engine id per seat wire id, seatId order (immutable; empty if no
     * deploy yet) — the {@code ShipNetworkIdRegistry} reserved-band pre-seed source.
     */
    public static synchronized Map<Long, String> seatStableIdByWireId() {
        return seatStableIdByWireId;
    }

    /**
     *
     * Whether a seat hull carried the trimmed {@code stableId} this round — the wave-skip
     * sweep's read, under the roster monitor (a query, so the growing set never leaves it).
     */
    static synchronized boolean seatCarriedThisRound(String stableId) {
        return seatStableIdsThisRound.contains(stableId);
    }

    /**
     * Stable engine ids of ALL hulls this round's t=0 deploy spawned (seats + AI rows,
     * deploy-ledger order; immutable; empty pre-deploy) — the growth known-keys seed source,
     * hook-carried like every roster identity
     * ({@code PilotIntentCombatServerMain.buildGrowthSeamHook}).
     */
    /**
     * ADMIRAL: the {@code /wave} switch's posture write — the engine context's
     * {@code fightToTheLast} for {@code category} ({@link WaveSchedule#fightToTheLast}). No-op
     * when no roster is deployed or the knob is OFF; returns whether a write happened.
     */
    public static synchronized boolean applyWaveAdmiralPosture(String category) {
        if (deployContext == null) {
            return false;
        }
        boolean fightToTheLast = cc.starsector.server.match.WaveSchedule.fightToTheLast(category);
        boolean written = deployContext.applyWaveAdmiralPosture(fightToTheLast);
        if (written) {
            System.out.println("engine-deploy wave_admiral posture category=" + category
                    + " fight_to_the_last=" + fightToTheLast);
        }
        return written;
    }

    public static synchronized List<String> deployedHullStableIds() {
        return deployedHullStableIds;
    }

    /** True when this round's deploy fielded a STAR FORTRESS base hull. */
    public static synchronized boolean stationDeployed() {
        return stationBaseShip != null;
    }

    /**
     * The descriptor this boot deployed from, or null when none was configured
     * (the two-Mudskipper legacy body) or the deploy has not run yet. The serve-boot fortress
     * checks read the shape + roster off THIS, never a second decode of the descriptor file —
     * one decode, one truth.
     */
    public static synchronized MatchDescriptor deployedDescriptorOrNull() {
        return deployedDescriptor;
    }

    /** Stable engine id of this round's station BASE hull (empty when none deployed). */
    public static synchronized String stationBaseStableId() {
        return stationBaseStableId;
    }

    /**
     * The engine-native LOSE seam — has this round's station BASE hull
     * turned hulk? Reads the held base reference through the cached {@code ShipAPI.isHulk()}
     * handle resolved at the deploy readback. Called once per stable-roster tick from
     * {@code EngineBackedLiveWorldSnapshotSource}'s station-lost trigger.
     *
     * <p>A read failure — or a call with no station deployed — <b>THROWS</b>. That is the whole
     * point: a lose seam that degrades to "base still alive" turns a lost fortress round into
     * an endless one, and the serve loop is the only place that can end it.
     */
    public static synchronized boolean stationBaseHulk() {
        if (stationBaseShip == null || stationBaseIsHulk == null) {
            throw new RealEngineDemoRosterException(
                    "stationBaseHulk() called with no station base recorded for this round —"
                            + " the lose seam must never be consulted on a station-free deploy"
                            + " (a silent false would keep the round alive forever)");
        }
        try {
            Object hulk = stationBaseIsHulk.invoke(stationBaseShip);
            if (!(hulk instanceof Boolean)) {
                throw new RealEngineDemoRosterException(
                        "ShipAPI.isHulk() on the station base '" + stationBaseStableId
                                + "' returned "
                                + (hulk == null ? "null" : hulk.getClass().getName())
                                + " — refusing to keep the round alive on an unreadable base");
            }
            return (Boolean) hulk;
        } catch (RealEngineDemoRosterException failure) {
            throw failure;
        } catch (Throwable readFailure) {
            Throwable cause = unwrap(readFailure);
            throw new RealEngineDemoRosterException(
                    "station base-hulk read failed for '" + stationBaseStableId + "': "
                            + cause.getClass().getName() + ": " + cause.getMessage(), cause);
        }
    }

    /**
     * ALL descriptor seat wire ids in seatId order, occupancy-independent (empty when the round
     * deployed without a descriptor, or pre-deploy) — the D8 ownership source.
     */
    public static synchronized long[] descriptorSeatShipIds() {
        return descriptorSeatShipIds.clone();
    }

    /**
     * Bench seam (game-free contracts ONLY — touches no engine state): record a
     * synthetic deploy's seat stable ids so {@code newSeatReservedRegistry()} mints the same
     * reserved-band registry the real deploy lanes get. Game-free these statics are empty, which
     * yields a ceiling-0 band whose respawn fly-in intern THROWS — so the
     * legacy-lane respawn bench ({@code PilotRespawnServeContract}) seeds them here exactly as a
     * real {@code deployMatchRoster()} would have. {@link #resetForRedeploy()} clears it; the
     * seam also adds the ids to {@link #seatStableIdsThisRound}, as a deploy or a re-point
     * does (so a second call models a re-point), and sets nothing else ({@code deployed} stays
     * false — no real-path gate can misread a bench run as a live roster).
     */
    public static synchronized void recordBenchSeatStableIds(Map<Long, String> stableByWire) {
        seatStableIdByWireId = Collections.unmodifiableMap(new LinkedHashMap<>(stableByWire));
        seatStableIdsThisRound.addAll(stableByWire.values());
    }

    /** The live engine ship mounted at seat wire id {@code wireId} this round, or null. */
    public static synchronized Object liveShipForWireIdOrNull(long wireId) {
        return liveShipByWireId.get(wireId);
    }

    /** The seat's descriptor side (0/1) for the podtest target spawn, -1 when
     * the layout has no such seat (the driver grades that as a spawn FAIL, never a throw). */
    public static synchronized int seatSideOrNeg(int seatId) {
        try {
            return DescriptorLayout.seatSlot(seatId).side();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * Bench seam (game-free contracts ONLY — touches no engine state): inject
     * synthetic live-map entries so {@link #vacateSeatHull}'s fail-closed semantics are testable
     * at the REAL surface without an engine (a past serve crash lived exactly in this map; the
     * serve-grade bench's hook mirror proves the serve-loop wiring, this seam proves the roster's
     * own vacate). Sets nothing else ({@code deployed} stays false — no real-path gate can misread
     * a bench run as a live roster); {@link #resetForRedeploy()} clears it.
     *
     * <p>A deliberate extension beyond game-free bench use: the offline
     * anchor-dive probe ({@code RealEngineAnchorDiveProbeMain --arm w|k}) also calls this on a
     * GATED real-engine boot whose live map is empty (seat occupancy-0) to front the AI-row
     * anchor hull behind seat wire id 1 — {@code deployed} is already true there (this method
     * still touches ONLY the map), a deliberate measurement-lane mismatch, never product serve.
     */
    public static synchronized void recordBenchLiveHulls(Map<Long, Object> hullsByWireId) {
        liveShipByWireId.clear();
        liveShipByWireId.putAll(hullsByWireId);
    }

    /**
     * Vacate a despawned seat hull from the live wireId→ship map. The serve loop's
     * T3 despawn detection observed the ENGINE drop this hull (DESTROYED or DEPARTED — ground
     * truth), so the entry is stale: until a fly-in re-points the seat's wire id to a fresh hull,
     * the {@code LiveWireShipResolver} must resolve it null (an unmounted seat's intent drops
     * harmlessly) and {@link #midDeploySeat}'s fail-closed "already live-mapped" guard must not
     * mistake the dead hull for a mounted seat — a past serve died exactly there: the cooldown-expiry
     * fly-in THREW on the dead Mudskipper's never-vacated entry and killed the serve.
     *
     * <p>Fail-closed: the wire id must be live-mapped (T3 fires once per hull death by
     * construction — the despawn detector diffs consecutive frames — so a second vacate, or one
     * for a never-mounted seat, is a serve-loop wiring bug). {@code seatStableIdByWireId} is
     * deliberately NOT touched: the dead hull's stable id stays the round's identity record until
     * {@link #midDeploySeat} re-points it (the Main fly-in hook's remount diff-assert keys on
     * exactly that re-point).
     */
    public static synchronized void vacateSeatHull(int seatId, long wireId) {
        Object removed = liveShipByWireId.remove(wireId);
        if (removed == null) {
            throw new RealEngineDemoRosterException(
                    "vacateSeatHull(seat=" + seatId + ", wireId=" + wireId + "): wire id not"
                            + " live-mapped — T3 fires once per hull death, so a second vacate (or"
                            + " one for a never-mounted seat) is a serve-loop wiring bug");
        }
        System.out.println("real-engine-demo-roster vacateSeatHull seat=" + seatId
                + " wireId=" + wireId
                + " (hull despawned — live map vacated; a fly-in re-points this id on remount)");
    }

    /**
     * Whether the live wireId→ship map is authoritative for this round: a real
     * roster deploy makes the map THE seat-addressed resolve authority until
     * {@link #resetForRedeploy()} — deliberately population-INDEPENDENT: a
     * zero-hull gated boot (mounts all arrive by fly-in) and an all-seats-vacated window (every
     * hull dead inside the respawn cooldown overlap) must both keep resolving wire ids HERE
     * (null ⇒ harmless intent drop), never fall through to the positional read that could command
     * an AI-row ship. False between rounds (reset) and on fake-binding paths (no deploy) — the
     * binding falls back to the legacy positional read only there.
     */
    public static synchronized boolean liveShipMapActive() {
        return deployed;
    }

    /**
     * Roll back the prior round's engine-deploy treatment and clear the per-round idempotency latch
     * so the next {@link #deployMatchRoster()} re-deploys a fresh roster (the 2b {@code MATCH_RESET}
     * hook, L1 Option A). {@link HeadlessCombatContext#close()} runs the install's LIFO rollback —
     * {@code removeListener} each match-N {@code CombatFleetManager} + its transfer-manager {@code B},
     * and restore the prior {@code CombatEngine} objects/fleetManagers/playerShip/preDeploy — so the
     * singleton engine returns to the same post-{@code init()} clean state the FIRST deploy started
     * from, rather than the next round layering a fresh roster on top of the dead round's stale fleet
     * listeners (which would {@code advance()} over destroyed-ship state). The process-global seams
     * (GL context, background-sprite, damage-path FX) are NOT this context and survive untouched.
     *
     * <p>Fail-closed: a rollback-assertion mismatch (engine state not clearing between matches) is a
     * real reset seam and is allowed to propagate — the reset loop ends the standing server with a
     * named reason rather than serving a half-reset roster.
     */
    /** First engine-deployed roster ship this round (null pre-deploy) — for the boot self-check. */
    public static synchronized Object firstDeployedShipOrNull() {
        if (deployContext == null) {
            return null;
        }
        java.util.List<Object> ships = deployContext.engineDeployedShips();
        if (!ships.isEmpty()) {
            return ships.get(0);
        }
        // Zero-hull gated boot: the t=0 install ledger stays empty and fly-ins
        // deliberately never join it (reset restores the prior repository wholesale), so the live
        // seat map IS this round's deployed roster — without this fallback the deferred boot
        // self-check would THROW "no deployed roster ship" at the round's first fly-in.
        for (Object live : liveShipByWireId.values()) {
            return live;
        }
        return null;
    }

    public static synchronized void resetForRedeploy() {
        if (deployContext != null) {
            deployContext.close();
        }
        deployed = false;
        deployContext = null;
        deployedVariantId = "";
        deployedShipCount = 0;
        // The per-seat fielded record dies with the round.
        deployedVariantBySeatId = new LinkedHashMap<>();
        overrideCount = 0;
        overrideFallbackCount = 0;
        deployedSeatShipIds = new long[0];
        seatStableIdByWireId = Map.of();
        seatStableIdsThisRound.clear();
        deployedDescriptor = null;
        descriptorSeatShipIds = new long[0];
        waveSpawnXSlot = 0;
        liveShipByWireId.clear();
        deployedHullStableIds = List.of();
        stationBaseShip = null;
        stationBaseStableId = "";
        stationBaseIsHulk = null;
    }

    /**
     * Resolve the configured match descriptor, or null when the knob is unset.
     * Resolution is the standard {@link ServerConfig#resolvePath} triple cascade — sysprop &gt; env
     * &gt; the {@code match.descriptor} config row — the SAME cascade
     * {@code PersistentPilotIntentCombatServer.mintUdpSeatAuthority} uses, closing the
     * divergence where a config-row-only serve admitted descriptor seats but deployed the fallback
     * 1v1 (this read previously saw sysprop &gt; env only). With the knob unset — the default — NO
     * file I/O happens and the caller deploys the byte-identical two-Mudskipper body.
     *
     * <p><b>Fail-CLOSED</b> once a descriptor is named (supersedes the fail-soft deployCr
     * read): an unreadable or undecodable file THROWS — a serve whose admission ledger was minted
     * from the descriptor must never silently deploy a different roster (knob discipline: invalid
     * values THROW, never degrade).
     */
    static MatchDescriptor resolveConfiguredDescriptorOrNull(String purpose) {
        // Package-private: RealEngineCombatBindingFactory.create() resolves the
        // same descriptor to size the map BEFORE setCombatMap; {@code purpose} tags the row so a
        // log reader can tell the two reads apart.
        String raw = ServerConfig.resolvePath(
                "cc.match.descriptor", "CC_MATCH_DESCRIPTOR", "match.descriptor", "");
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String path = raw.trim();
        List<String> lines;
        try {
            lines = Files.readAllLines(Path.of(path));
        } catch (IOException e) {
            throw new RealEngineDemoRosterException(
                    "cc.match.descriptor=" + path + " UNREADABLE for the roster deploy: " + e, e);
        }
        try {
            MatchDescriptor descriptor = MatchDescriptorCodec.decode(lines);
            System.out.println("real-engine-demo-roster cc.match.descriptor=" + path
                    + " -> descriptor-driven roster shape=" + descriptor.shape()
                    + " seats=" + descriptor.seats().size()
                    + " rosterRows=" + descriptor.roster().size()
                    + " deployCr=" + descriptor.deployCr()
                    + " purpose=" + purpose);
            return descriptor;
        } catch (RuntimeException e) {
            throw new RealEngineDemoRosterException(
                    "cc.match.descriptor=" + path + " DECODE FAILED for the roster deploy: " + e, e);
        }
    }

    /**
     * Deploy this match's roster into the live {@code CombatEngine} via the proven engine-own deploy
     * lane. With no descriptor configured ({@code cc.match.descriptor} /
     * {@code CC_MATCH_DESCRIPTOR} / config row {@code match.descriptor} all unset), this is the
     * EXACT long-standing two-Mudskipper body — same variants, names, positions ({@code ∓SPAWN_X},
     * facing 0/180) and deployCr, byte-identical for every existing gate. With a descriptor
     * configured (fail-closed — see {@link #resolveConfiguredDescriptorOrNull()}), the roster is
     * descriptor-driven: seats sorted by seatId deploy first (seat 1 = the engine player seat, W3),
     * each consuming its side's SEAT-row capacity in row order, then AI rows in declaration order,
     * positioned by the {@link DescriptorLayout#formationPosition} column grid.
     *
     * <p><b>All SEAT rows deploy at t=0</b> (an unoccupied seat's ship simply coasts);
     * occupancy-gated deploy + the fly-in lane land at D4.4, not here. Resolves the engine itself
     * ({@code getInstance()} singleton — decoupled from {@code create()}); requires the combat map
     * already installed (the serve-gate predicate). Must run on the GL-substrate thread that
     * constructed the engine. Idempotent within a round.
     *
     * @throws RealEngineDemoRosterException if the engine/map are not ready, a variant cannot be
     *         resolved, the descriptor is named-but-broken, or the deploy lane fails — never a
     *         half-installed roster.
     */
    public static synchronized void deployMatchRoster() {
        deployRoster(null, Map.of());
    }

    /**
     * Occupancy-gated deploy: deploy only the OCCUPIED seats (seatId order,
     * spec'd first) plus ALL AI rows. Descriptor REQUIRED — occupancy gating exists only under the
     * npeer mode, which is descriptor-mandatory (D1); THROWS if {@code cc.match.descriptor} is
     * unset. Descriptor-shape validation stays on the FULL seat list (the position assert +
     * seatsPerSide==capacity validate the MINT, independent of occupancy), and a seat's variant
     * slot, formation position and name are computed from its FULL-layout ordinal — ship identity
     * and placement never depend on who else joined (deterministic across join orders; round-reset
     * redeploys land on the same slots). Occupancy arrives as a VALUE (the roster stays
     * authority-decoupled — the serve loop passes {@code MatchSeatAuthority.occupiedSeatIds()}).
     * An EMPTY occupied set is legal (the gauntlet host boots pre-first-claim onto a zero-hull
     * battlefield; both descriptor sides are addFleet-registered so {@link #midDeploySeat} fly-ins
     * find real managers). Unknown occupied seat ids fail closed. Idempotent within a round, same
     * latch as the no-arg deploy. Every seat on its
     * descriptor row ({@code Map.of()}). (the npeer round wrap deploys
     * with EVERY seat excluded and fields the seats through {@link #midDeploySeat}.)
     */
    public static synchronized void deployMatchRoster(SortedSet<Integer> occupiedSeatIds) {
        deployMatchRoster(occupiedSeatIds, Map.of());
    }

    /**
     * The occupancy-gated deploy with each occupied seat's
     * carried pick ({@code variantOverrideBySeat}: seatId → allowlist-canonical variant id; an
     * absent seat fields its descriptor row). Precedence = override ?? descriptor through the ONE
     * pick law {@link #resolveSeatPick} that {@link #midDeploySeat} also uses; a
     * pick that does not resolve (or names a station) falls back to the descriptor with one
     * {@code override_unresolvable … fallback= reason=} line.
     * Placement, name and slot stay FULL-layout-ordinal (variant-independent). Null map THROWS
     * like the null set.
     */
    public static synchronized void deployMatchRoster(SortedSet<Integer> occupiedSeatIds,
                                                      Map<Integer, String> variantOverrideBySeat) {
        if (variantOverrideBySeat == null) {
            throw new RealEngineDemoRosterException(
                    "variantOverrideBySeat must be non-null (Map.of() = every seat on its"
                            + " descriptor row)");
        }
        if (occupiedSeatIds == null) {
            throw new RealEngineDemoRosterException(
                    "occupiedSeatIds must be non-null (the no-arg deployMatchRoster() is the full deploy)");
        }
        // Descriptor-required, checked BEFORE any engine touch so the boundary is game-free
        // testable and a misconfigured N-serve fails on the real cause, not an engine read.
        String raw = ServerConfig.resolvePath(
                "cc.match.descriptor", "CC_MATCH_DESCRIPTOR", "match.descriptor", "");
        if (raw == null || raw.trim().isEmpty()) {
            throw new RealEngineDemoRosterException(
                    "occupancy-gated deploy requires a configured match descriptor"
                            + " (cc.match.descriptor / CC_MATCH_DESCRIPTOR / match.descriptor):"
                            + " occupancy gating exists only under npeer, which is descriptor-mandatory");
        }
        deployRoster(occupiedSeatIds, variantOverrideBySeat);
    }

    /** {@code overrideBySeat} rides to the seat specs. */
    private static void deployRoster(SortedSet<Integer> occupiedOrNull,
                                     Map<Integer, String> overrideBySeat) {
        if (deployed) {
            return;
        }
        ClassLoader loader = RealEngineDemoRoster.class.getClassLoader();
        try {
            Class<?> engineClass = Class.forName(ENGINE_CLASS, true, loader);
            Object engine = engineClass.getMethod("getInstance").invoke(null);
            if (engine == null) {
                throw new RealEngineDemoRosterException(
                        "CombatEngine.getInstance() returned null — RealEngineCombatBindingFactory.create()"
                                + " must construct the engine before the roster is deployed");
            }
            Object combatMap = engineClass.getMethod("getCombatMap").invoke(engine);
            if (combatMap == null) {
                throw new RealEngineDemoRosterException(
                        "combat map null at roster deploy — create() must setCombatMap + init() first"
                                + " (the serve gate's combatSpaceReady predicate)");
            }

            Class<?> shipClass = Class.forName(SHIP_CLASS, false, loader);
            Class<?> hullVariantClass = Class.forName(HULL_VARIANT_SPEC_CLASS, false, loader);
            MatchDescriptor descriptor = resolveConfiguredDescriptorOrNull("roster-deploy");

            // ---- Engine-own deploy-lane handles ----
            Method getObjects = engineClass.getMethod("getObjects");
            Object engineRepo = getObjects.invoke(engine);
            if (engineRepo == null) {
                throw new RealEngineDemoRosterException("CombatEngine.getObjects() null before roster deploy");
            }
            Class<?> repoClass = engineRepo.getClass();
            Method repoGetList = repoClass.getMethod("getList", Class.class);

            Class<?> fleetMemberClass = Class.forName(FLEET_MEMBER_CLASS, false, loader);
            Class<?> fleetMemberTypeClass = Class.forName(FLEET_MEMBER_TYPE_API, false, loader);
            Class<?> fleetMemberApiClass = Class.forName(FLEET_MEMBER_API, false, loader);
            Class<?> shipApiClass = Class.forName(SHIP_API, false, loader);
            Class<?> cfmClass = Class.forName(COMBAT_FLEET_MANAGER_CLASS, false, loader);
            Class<?> vec2Class = Class.forName(LWJGL_VECTOR2F_CLASS, false, loader);
            Constructor<?> vec2Ctor = vec2Class.getConstructor(float.class, float.class);
            Constructor<?> fleetMemberCtor =
                    fleetMemberClass.getConstructor(int.class, hullVariantClass, fleetMemberTypeClass);
            Object memberTypeShip = fleetMemberTypeClass.getField("SHIP").get(null);
            Method memberSetShipName = fleetMemberClass.getMethod("setShipName", String.class);
            Method spawnFleetMember =
                    cfmClass.getMethod("spawnFleetMember", fleetMemberApiClass, vec2Class, float.class, float.class);
            Method getDeployedCopy = cfmClass.getMethod("getDeployedCopy");
            Method getFleetManager = engineClass.getMethod("getFleetManager", int.class);
            Method getFleetManagers = engineClass.getMethod("getFleetManagers");
            Method setFleetManagers = engineClass.getMethod("setFleetManagers", List.class);

            // W5: getFleetManager's mint-on-miss never populates the transfer manager; the engine's
            // OWN per-side registration is addFleet(sideSpec, FleetGoal). The side-spec class + its
            // (int) ctor are derived descriptor-grade from addFleet's own signature.
            Class<?> fleetGoalClass = Class.forName(FLEET_GOAL_API, false, loader);
            Object fleetGoalAttack = fleetGoalClass.getField("ATTACK").get(null);
            Method addFleet = findMethod(engineClass, "addFleet",
                    m -> m.getParameterCount() == 2 && m.getParameterTypes()[1] == fleetGoalClass,
                    "addFleet(sideSpec, FleetGoal)");
            Constructor<?> fleetSpecCtor = addFleet.getParameterTypes()[0].getDeclaredConstructor(int.class);
            Method getTransferManager = cfmClass.getMethod("getTransferManager");
            Method removeListener = findMethod(engineClass, "removeListener",
                    m -> m.getParameterCount() == 1, "removeListener(listener)");
            Method setObjects = findMethod(engineClass, "setObjects",
                    m -> m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(repoClass),
                    "setObjects(repository)");
            Method isShipAlive = engineClass.getMethod("isShipAlive", shipApiClass);
            Method setPreDeploy = engineClass.getMethod("setPreDeploy", boolean.class);
            Method isPreDeploy = engineClass.getMethod("isPreDeploy");
            Method getPlayerShip = engineClass.getMethod("getPlayerShip");
            Field playerShipField = engineClass.getDeclaredField(PLAYER_SHIP_FIELD);
            playerShipField.setAccessible(true);

            Constructor<?> repoCtor = repoClass.getDeclaredConstructor();
            repoCtor.setAccessible(true);
            Object freshRepo = repoCtor.newInstance();

            if (occupiedOrNull != null && descriptor == null) {
                // Defensive: the overload pre-checks the knob; a race on the sysprop between the
                // two reads must still fail closed, never deploy the fallback 1v1 under gating.
                throw new RealEngineDemoRosterException(
                        "occupancy-gated deploy lost its descriptor between the knob pre-check and"
                                + " resolution — refusing to deploy the fallback roster");
            }

            List<HeadlessCombatContext.EngineDeployShipSpec> specs;
            long[] seatWireIds;
            long[] allSeatShipIds = new long[0];
            float deployCr;
            String demoVariantId = null;
            if (descriptor == null) {
                // Default no-descriptor path: the EXACT long-standing two-Mudskipper body (byte-compat
                // for every existing gate + the certified lane).
                Object variant = resolveDemoVariant(loader, hullVariantClass);
                // fallback-lane fleet-context input: the side-0 fleet IS this one hull.
                demoVariantId = (String) hullVariantClass.getMethod("getHullVariantId")
                        .invoke(variant);
                specs = new ArrayList<>();
                // Literal System.getProperty at the read sites — the knob-scan idiom.
                specs.add(new HeadlessCombatContext.EngineDeployShipSpec(
                        0, variant,
                        DescriptorLayout.resolveSeatShipName(1, System.getProperty("cc.match.seat1Name"), DescriptorLayout.seatDefaultName(1)),
                        vec2Ctor.newInstance(-SPAWN_X, 0.0f), 0.0f, 0.0f));
                specs.add(new HeadlessCombatContext.EngineDeployShipSpec(
                        1, variant,
                        DescriptorLayout.resolveSeatShipName(2, System.getProperty("cc.match.seat2Name"), DescriptorLayout.seatDefaultName(2)),
                        vec2Ctor.newInstance(SPAWN_X, 0.0f), 180.0f, 0.0f));
                seatWireIds = new long[] {1L, 2L};
                deployCr = HeadlessCombatContext.DEFAULT_DEPLOY_CR;
            } else {
                // Descriptor-driven roster: seats (seatId order) first, then AI rows.
                List<MatchDescriptor.Seat> seats = new ArrayList<>(descriptor.seats());
                seats.sort(Comparator.comparingInt(MatchDescriptor.Seat::seatId));
                // Shape validation stays on the FULL seat list: the position
                // assert + the buildDescriptorSpecs capacity check validate the MINT itself,
                // independent of who happens to be occupying a seat this round.
                allSeatShipIds = new long[seats.size()];
                for (int i = 0; i < seats.size(); i++) {
                    MatchDescriptor.Seat seat = seats.get(i);
                    // FULL-layout order = seatId order is LOAD-BEARING twice: the lowest deployed
                    // seat is the engine player seat (W3), and the intent/pose lanes resolve ships
                    // by wire id — so in v1 a seat's wire id must equal its FULL-layout position
                    // (every minted shape satisfies this; a divergent hand-authored file must not
                    // serve).
                    if (seat.shipId() != i + 1) {
                        throw new RealEngineDemoRosterException(
                                "descriptor seat.id=" + seat.seatId() + " has seat.ship="
                                        + seat.shipId() + " but deploys at position " + (i + 1)
                                        + " — v1 requires seat shipIds to equal their seatId-order"
                                        + " deploy positions (repository-position command lanes)");
                    }
                    allSeatShipIds[i] = seat.shipId();
                }
                List<MatchDescriptor.Seat> deploySeats;
                if (occupiedOrNull == null) {
                    deploySeats = seats;
                } else {
                    // Unknown occupied ids fail closed — occupancy is a caller-supplied VALUE and
                    // a seat id outside the mint must never silently deploy nothing.
                    deploySeats = new ArrayList<>();
                    java.util.Set<Integer> minted = new java.util.HashSet<>();
                    for (MatchDescriptor.Seat seat : seats) {
                        minted.add(seat.seatId());
                    }
                    for (Integer occupiedId : occupiedOrNull) {
                        if (!minted.contains(occupiedId)) {
                            throw new RealEngineDemoRosterException(
                                    "occupied seat id " + occupiedId + " is not a minted descriptor"
                                            + " seat (minted=" + minted + ")");
                        }
                    }
                    for (MatchDescriptor.Seat seat : seats) {
                        if (occupiedOrNull.contains(seat.seatId())) {
                            deploySeats.add(seat);
                        }
                    }
                }
                seatWireIds = new long[deploySeats.size()];
                for (int i = 0; i < deploySeats.size(); i++) {
                    seatWireIds[i] = deploySeats.get(i).shipId();
                }
                specs = buildDescriptorSpecs(descriptor, seats, occupiedOrNull, overrideBySeat,
                        loader, hullVariantClass, vec2Ctor);
                deployCr = descriptor.deployCr();
            }
            // Mint the side-0 fleet context BEFORE any member mints (installFleetContext
            // fail-closes on ordering) — knob-gated inside; cached once per boot.
            if (descriptor != null) {
                AllSkillsAttach.maybeMintSideZeroFleetContext(descriptor.roster());
            } else {
                AllSkillsAttach.maybeMintSideZeroFleetContextFromVariantIds(
                        List.of(demoVariantId));
            }
            HeadlessCombatContext.Builder deployBuilder = HeadlessCombatContext.builder()
                    .boundaryLabel("real-engine-demo-roster")
                    .engineDeployRosterDeployCr(deployCr);
            if (deployFlyInEnabled(descriptor == null ? null : descriptor.shape())) {
                // Flying in, not teleporting: resolve the
                // engine's battle-start driver handles for the engine-native batched
                // fly-in. The combat map's height accessor resolves fine via
                // getMethod; the standoff setter lives on the API BattleCreationContext
                // (getContext's return type — headless-safe: a never-set context is the engine's
                // own default instance, standoff=6000 burn=1.0).
                Method addToReserves = cfmClass.getMethod("addToReserves", fleetMemberApiClass);
                Method setFirstDeployment = cfmClass.getMethod("setFirstDeployment", boolean.class);
                Method deployAll = cfmClass.getMethod("deployAll", float.class);
                Method getShipFor = cfmClass.getMethod("getShipFor", fleetMemberApiClass);
                Method getReserves = cfmClass.getMethod("getReserves");
                Method getCombatMap = engineClass.getMethod("getCombatMap");
                Method mapHeight = getCombatMap.getReturnType().getMethod("super");
                Method getStandoffRange = engineClass.getMethod("getStandoffRange");
                Method getContext = engineClass.getMethod("getContext");
                Method contextSetStandoffRange =
                        getContext.getReturnType().getMethod("setStandoffRange", float.class);
                deployBuilder.engineDeployRosterFlyIn(
                        addToReserves, setFirstDeployment, deployAll, getShipFor, getReserves,
                        getCombatMap, mapHeight, getStandoffRange, getContext,
                        contextSetStandoffRange);
                System.out.println("real-engine-demo-roster deploy_fly_in=true"
                        + " (cc.server.deployFlyIn — engine-native deployAll battle-start lane)");
            }
            if (waveAdmiralEnabled()) {
                // ADMIRAL: the wave side's engine-native
                // fleet commander — vanilla's own AdmiralAI(manager, allyMode=false) installed
                // at the addFleet seam, retreat suppressed on the engine's BattleCreationContext,
                // the per-category fightToTheLast seeded from the boot category (a /wave switch
                // re-writes it through the wave hook). Public API fields + one stable-named engine
                // class; the client jar never sees any of it (B-deny stays clear).
                Class<?> admiralClass = Class.forName(ADMIRAL_AI_CLASS, false, loader);
                Class<?> admiralPluginClass = Class.forName(ADMIRAL_AI_PLUGIN_API, false, loader);
                Constructor<?> admiralCtor = admiralClass.getConstructor(cfmClass, boolean.class);
                Method setAdmiralAI = cfmClass.getMethod("setAdmiralAI", admiralPluginClass);
                Method getAdmiralAI = cfmClass.getMethod("getAdmiralAI");
                Method preCombat = admiralPluginClass.getMethod("preCombat");
                Method getContext = engineClass.getMethod("getContext");
                Class<?> contextClass = getContext.getReturnType();
                Field aiRetreatAllowed = contextClass.getField("aiRetreatAllowed");
                Field fightToTheLast = contextClass.getField("fightToTheLast");
                String bootCategory = cc.starsector.server.runtime.PersistentPilotIntentCombatServer
                        .resolveWaveCategory();
                boolean fightToTheLastInitial =
                        cc.starsector.server.match.WaveSchedule.fightToTheLast(bootCategory);
                deployBuilder.engineDeployRosterWaveAdmiral(WAVE_SIDE, admiralCtor, setAdmiralAI,
                        getAdmiralAI, preCombat, getContext, aiRetreatAllowed, fightToTheLast,
                        fightToTheLastInitial);
                System.out.println("real-engine-demo-roster wave_admiral=true side=" + WAVE_SIDE
                        + " boot_category=" + bootCategory
                        + " fight_to_the_last=" + fightToTheLastInitial
                        + " (cc.server.waveAdmiral — engine-native AdmiralAI, retreats off)");
            }
            if (occupiedOrNull != null) {
                // The gated deploy registers the descriptor's sides even when a
                // side has zero occupied hulls at t=0, so midDeploySeat fly-ins always find a
                // REAL addFleet-registered manager + transfer manager (W4/W5).
                deployBuilder.engineDeployRosterRegisterSides(descriptorSides(descriptor));
            }
            deployContext = deployBuilder
                    .engineDeployRosterInstall(
                            engine, freshRepo, specs, shipClass,
                            repoGetList, getObjects, setObjects,
                            getFleetManagers, setFleetManagers, getFleetManager,
                            addFleet, fleetSpecCtor, fleetGoalAttack, getTransferManager, removeListener,
                            fleetMemberCtor, memberTypeShip, memberSetShipName,
                            spawnFleetMember, getDeployedCopy, isShipAlive,
                            setPreDeploy, isPreDeploy, getPlayerShip, playerShipField)
                    .install();

            // Roster + player-seat readback (the deploy treatment already failed closed internally:
            // per-ship isShipAlive + deployed-ledger membership + player-seat readback). The count is
            // the lane's authoritative spawned-ship list, NOT repoGetList(getObjects()): spawnFleetMember
            // -> deploy -> instantiateForCombat -> addObject puts each ship on the engine's PENDING-ADD
            // queue, which only drains into the repository ship list on the first advance(), so the repo
            // list reads empty here pre-drain (the serve loop's first step populates it; the binding
            // reads getObjects() live).
            int deployedCount = deployContext.engineDeployedShips().size();
            if (deployedCount != specs.size()) {
                throw new RealEngineDemoRosterException(
                        "deployed ship count " + deployedCount + " != expected " + specs.size());
            }
            // Player-seat readback conditional on >=1 deployed ship: only the
            // occupancy-gated path can deploy zero hulls (N-boot pre-first-claim); every legacy
            // deploy has >=1 spec, so this readback is behavior-identical there.
            Object playerSeat = null;
            if (!specs.isEmpty()) {
                playerSeat = getPlayerShip.invoke(engine);
                if (playerSeat == null || !shipClass.isInstance(playerSeat)) {
                    throw new RealEngineDemoRosterException(
                            "player seat not a real ship after deploy (got "
                                    + (playerSeat == null ? "null" : playerSeat.getClass().getName()) + ")");
                }
            }
            // Record each SEAT ship's stable engine id (ShipAPI.getId(), trimmed —
            // byte-identical to the shipLifecyclesOrNull() stable key) keyed by its wire id. Seats
            // occupy the first seatWireIds.length deploy-ledger slots because they were spec'd first
            // (on the gated path seatWireIds is the OCCUPIED list — same invariant). The same loop
            // grows the live wireId→ship map, the LiveWireShipResolver authority.
            List<Object> deployedShips = deployContext.engineDeployedShips();
            Method shipGetId = shipClass.getMethod("getId");
            Map<Long, String> stableByWire = new LinkedHashMap<>();
            liveShipByWireId.clear();
            for (int i = 0; i < seatWireIds.length; i++) {
                Object idValue = shipGetId.invoke(deployedShips.get(i));
                String stable = (idValue instanceof String) ? ((String) idValue).trim() : "";
                if (stable.isEmpty()) {
                    throw new RealEngineDemoRosterException(
                            "seat stable-id read failed at deploy position " + (i + 1)
                                    + " (ShipAPI.getId() returned "
                                    + (idValue == null ? "null" : idValue.getClass().getName())
                                    + ") — the D7 reserved-band pre-seed must never publish a"
                                    + " half-recorded identity table");
                }
                stableByWire.put(seatWireIds[i], stable);
                liveShipByWireId.put(seatWireIds[i], deployedShips.get(i));
            }
            // Record ALL deployed hull stable ids (seats + AI rows) —
            // the growth known-keys seed. Blank ids (the fx_drone class) are skipped
            // silently: the seam never announces a blank key, so an unseeded blank cannot
            // leak; seats already failed closed on blank above.
            List<String> allHullStableIds = new ArrayList<>(deployedShips.size());
            for (Object deployedShip : deployedShips) {
                Object rawId = shipGetId.invoke(deployedShip);
                String stable = (rawId instanceof String) ? ((String) rawId).trim() : "";
                if (!stable.isEmpty()) {
                    allHullStableIds.add(stable);
                }
            }
            // ---- the STATION deploy readback ----
            // Runs only when a station spec deployed; a station-free deploy is byte-identical
            // (no extra reads, no extra asserts — the repo is still empty pre-drain there, so
            // the repo-order assert has nothing to read and correctly skips).
            int stationSpecIndex = -1;
            for (int i = 0; i < specs.size(); i++) {
                if (!specs.get(i).isStation()) {
                    continue;
                }
                if (stationSpecIndex >= 0) {
                    throw new RealEngineDemoRosterException(
                            "deploy produced " + (i + 1) + " station specs — buildDescriptorSpecs"
                                    + " admits exactly one");
                }
                stationSpecIndex = i;
            }
            if (stationSpecIndex >= 0) {
                if (stationSpecIndex != specs.size() - 1) {
                    throw new RealEngineDemoRosterException(
                            "station spec deployed at ledger position " + stationSpecIndex
                                    + " of " + specs.size() + " — it must be LAST so the"
                                    + " flattened wire-id contract equals deploy-ledger order");
                }
                if (descriptor == null) {
                    throw new RealEngineDemoRosterException(
                            "station spec deployed on the NO-descriptor path — stations arrive"
                                    + " only from a descriptor's last roster row");
                }
                String stationVariantId = descriptor.roster()
                        .get(descriptor.roster().size() - 1).variantId();
                if (!MatchDescriptors.isStationVariantId(stationVariantId)) {
                    throw new RealEngineDemoRosterException(
                            "station spec deployed but the descriptor's LAST roster row is '"
                                    + stationVariantId + "' — the spec order and the roster"
                                    + " declaration order disagree about which row is the station");
                }
                // The bound's own source of truth (serve entry folds the SAME list into
                // rosterHullBound): if the engine minted a different cluster size than the
                // bound assumed, fail HERE, at deploy, not as a mid-round roster-bound kill.
                int expectedModules =
                        EngineBornGrowthSeam.stationModuleVariantIds(stationVariantId).size();
                Object baseShip = deployedShips.get(stationSpecIndex);
                Method shipGetChildModulesCopy = shipClass.getMethod("getChildModulesCopy");
                Method shipGetParentStation = shipClass.getMethod("getParentStation");
                Method shipGetStationSlot = shipClass.getMethod("getStationSlot");
                Object rawChildren = shipGetChildModulesCopy.invoke(baseShip);
                if (!(rawChildren instanceof List)) {
                    throw new RealEngineDemoRosterException(
                            "station base getChildModulesCopy() returned "
                                    + (rawChildren == null ? "null"
                                            : rawChildren.getClass().getName())
                                    + " — the module cluster is the objective's whole body");
                }
                List<?> children = (List<?>) rawChildren;
                if (children.size() != expectedModules) {
                    throw new RealEngineDemoRosterException(
                            "station '" + stationVariantId + "' minted " + children.size()
                                    + " modules but its variant declares " + expectedModules
                                    + " (getStationModules) — the serve entry bounded the"
                                    + " stable roster on the DECLARED count, so a mismatch is a"
                                    + " guaranteed mid-round roster-bound kill");
                }
                List<String> moduleStableIds = new ArrayList<>(children.size());
                for (int m = 0; m < children.size(); m++) {
                    Object child = children.get(m);
                    if (child == null || !shipClass.isInstance(child)) {
                        throw new RealEngineDemoRosterException(
                                "station module " + m + " is "
                                        + (child == null ? "null" : child.getClass().getName())
                                        + ", not a Ship");
                    }
                    if (shipGetParentStation.invoke(child) != baseShip) {
                        throw new RealEngineDemoRosterException(
                                "station module " + m + " is not parented to the deployed base"
                                        + " (getParentStation mismatch) — a detached module"
                                        + " would replicate as a free-floating hull");
                    }
                    if (shipGetStationSlot.invoke(child) == null) {
                        throw new RealEngineDemoRosterException(
                                "station module " + m + " carries a null station slot — the"
                                        + " client's spec+slot-position module bind has no key");
                    }
                    if (!Boolean.TRUE.equals(isShipAlive.invoke(engine, child))) {
                        throw new RealEngineDemoRosterException(
                                "station module " + m + " is not engine-alive at deploy");
                    }
                    Object rawModuleId = shipGetId.invoke(child);
                    String moduleStable =
                            (rawModuleId instanceof String) ? ((String) rawModuleId).trim() : "";
                    if (moduleStable.isEmpty()) {
                        throw new RealEngineDemoRosterException(
                                "station module " + m + " read a blank stable id"
                                        + " (ShipAPI.getId()) — the growth known-keys seed would"
                                        + " miss it and the first drain would RAPP-announce a"
                                        + " hull the client already has");
                    }
                    moduleStableIds.add(moduleStable);
                }
                // Growth known-keys seed: the modules are DEPLOY-lane hulls (the engine
                // mints them inside the station's own spawn), so they must be seeded — else the
                // seam's first drain announces the whole cluster as engine-born growth.
                allHullStableIds.addAll(moduleStableIds);
                // F3 id-readback assert (MANDATORY): every hull
                // this deploy put on the field carries a distinct, non-blank stable id. A
                // duplicate is the server-side id-steal shape — the wire id a client bound to
                // one hull silently re-pointing at another — and it is UNDETECTABLE client-side
                // (ServerEntityPose carries no variant field).
                Set<String> distinctStableIds = new LinkedHashSet<>();
                for (String stable : allHullStableIds) {
                    if (!distinctStableIds.add(stable)) {
                        throw new RealEngineDemoRosterException(
                                "duplicate stable engine id '" + stable + "' across this"
                                        + " deploy's hulls (ledger=" + deployedShips.size()
                                        + " modules=" + moduleStableIds.size() + ") — refusing"
                                        + " to publish a roster where one wire id could carry"
                                        + " two hulls (F3 id-steal law)");
                    }
                }
                if (distinctStableIds.size() != deployedShips.size() + moduleStableIds.size()) {
                    throw new RealEngineDemoRosterException(
                            "station deploy recorded " + distinctStableIds.size()
                                    + " stable ids for " + deployedShips.size() + " ledger hulls"
                                    + " + " + moduleStableIds.size() + " modules — a blank id"
                                    + " was silently dropped (F3 readback is MANDATORY on a"
                                    + " station deploy)");
                }
                // Repo MEMBERSHIP assert — deliberately NOT an order assert. The station's own
                // spawn runs executeAdds, which drains the ENTIRE pending queue FIFO
                // (tail-append order) — so unlike every station-free
                // deploy the repository IS enumerable here. But its ORDER is the engine's, not
                // ours: fly-in deploy() Collections.sorts its batch by modified max speed before
                // spawning, so near-equal hulls (the 2026-07-29 fortress
                // twin-glimmer swap) land in a per-run order. Identity does not ride that order:
                // wire ids are pinned to the dense descriptor contract by the serve loop's
                // deploy-ledger registry pre-seed (PersistentPilotIntentCombatServer),
                // and the client resolves variant BY numeric wire id (PRCP variantById).
                // A ledger hull ABSENT from the repository is still a failed spawn — refuse.
                Object repoNow = getObjects.invoke(engine);
                Object rawRepoShips = repoGetList.invoke(repoNow, shipClass);
                if (!(rawRepoShips instanceof List)) {
                    throw new RealEngineDemoRosterException(
                            "repository getList(Ship) returned "
                                    + (rawRepoShips == null ? "null"
                                            : rawRepoShips.getClass().getName())
                                    + " at the station deploy readback");
                }
                List<?> repoShips = (List<?>) rawRepoShips;
                Set<Object> repoIdentity = Collections.newSetFromMap(new IdentityHashMap<>());
                repoIdentity.addAll(repoShips);
                for (int i = 0; i < deployedShips.size(); i++) {
                    if (!repoIdentity.contains(deployedShips.get(i))) {
                        throw new RealEngineDemoRosterException(
                                "deploy-ledger hull " + i + " ('" + specs.get(i).shipName()
                                        + "') is absent from the " + repoShips.size()
                                        + "-ship repository after deploy — the hull failed to"
                                        + " spawn (membership law; repo order is engine"
                                        + " speed-sorted and carries no identity)");
                    }
                }
                stationBaseShip = baseShip;
                stationBaseStableId = allHullStableIds.get(stationSpecIndex);
                stationBaseIsHulk = shipClass.getMethod("isHulk");
                System.out.println("real-engine-demo-roster station_deploy variant="
                        + stationVariantId
                        + " base_stable_id=" + stationBaseStableId
                        + " modules=" + moduleStableIds.size()
                        + " repo_ships=" + repoShips.size()
                        + " ledger_hulls=" + deployedShips.size()
                        + " (census + F3 id readback + repo-membership asserts GREEN)");
            }
            deployedHullStableIds = Collections.unmodifiableList(allHullStableIds);
            deployedSeatShipIds = seatWireIds;
            seatStableIdByWireId = Collections.unmodifiableMap(stableByWire);
            seatStableIdsThisRound.addAll(stableByWire.values());
            deployedShipCount = deployedCount;
            deployedDescriptor = descriptor;
            descriptorSeatShipIds = allSeatShipIds;
            deployed = true;
            if (occupiedOrNull != null) {
                System.out.println("real-engine-demo-roster occupancy-gated deploy occupied="
                        + seatWireIds.length + "/" + allSeatShipIds.length
                        + " seatIds=" + occupiedOrNull);
            }
            System.out.println("real-engine-demo-roster deployed ships=" + deployedCount
                    + " seats=" + seatWireIds.length
                    + " variant=" + deployedVariantId + " (pending-add until first advance)"
                    + " deployCr=" + deployCr
                    + " playerSeatAlive=" + (playerSeat != null)
                    + " mapNonNull=" + (engineClass.getMethod("getCombatMap").invoke(engine) != null));
        } catch (RealEngineDemoRosterException e) {
            throw e;
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            throw new RealEngineDemoRosterException(
                    "failed to deploy the match roster: "
                            + cause.getClass().getName() + ": "
                            + (cause.getMessage() == null ? "" : cause.getMessage()),
                    cause);
        }
    }

    /**
     * Knob: the owner-directed fly-in initial deploy (engine-native batched
     * {@code deployAll} battle-start lane) — STRICT bool, fail-closed, default OFF (v1; the
     * grade adjudicates promote-to-default). Literal resolve strings = the
     * knob-scan idiom.
     */
    public static boolean deployFlyInEnabled() {
        return deployFlyInEnabled(null);
    }

    /**
     * Shape-aware resolve: an EXPLICIT knob value wins in
     * both directions (strict-bool throw preserved — the A/B lever at the watch stays
     * {@code cc.server.deployFlyIn=false}); UNSET defaults ON for {@code bastion:fortress}
     * only (the owner's t=0 "glimmers insta-die on the station, they should be flying in"
     * debrief — every other shape keeps the teleport default until its own grade).
     */
    public static boolean deployFlyInEnabled(String shapeOrNull) {
        String raw = ServerConfig.resolve(
                "cc.server.deployFlyIn", "CC_SERVER_DEPLOY_FLYIN", "server.deployFlyIn", "");
        if (raw.isEmpty()) {
            return cc.starsector.server.match.MatchDescriptors.isFortressShape(shapeOrNull);
        }
        return parseDeployFlyIn(raw);
    }

    /**
     * Branch selector, game-free-testable (ServerContractTests): ""/"false" = the teleport
     * lane (default OFF), "true" = fly-in; anything else THROWS (the waveClearRoundEnd
     * strict-bool house rule).
     */
    public static boolean parseDeployFlyIn(String raw) {
        switch (raw) {
            case "":
            case "false":
                return false;
            case "true":
                return true;
            default:
                throw new IllegalArgumentException(
                        "cc.server.deployFlyIn must be 'true' or 'false'; got '" + raw + "'");
        }
    }

    /**
     * ADMIRAL knob:
     * the wave side's engine-native {@code AdmiralAI} —
     * STRICT bool, default ON ({@code ""}/"true"), {@code "false"} = no commander (the
     * pre-admiral serve, byte-identical). Literal resolve strings = the
     * knob-scan idiom.
     */
    public static boolean waveAdmiralEnabled() {
        return parseWaveAdmiral(ServerConfig.resolve(
                "cc.server.waveAdmiral", "CC_SERVER_WAVE_ADMIRAL", "server.waveAdmiral", ""));
    }

    /**
     * Branch selector, game-free-testable (ServerContractTests): ""/"true" = admiral ON
     * (default), "false" = OFF; anything else THROWS (the deployFlyIn strict-bool house rule).
     */
    public static boolean parseWaveAdmiral(String raw) {
        switch (raw) {
            case "":
            case "true":
                return true;
            case "false":
                return false;
            default:
                throw new IllegalArgumentException(
                        "cc.server.waveAdmiral must be 'true' or 'false'; got '" + raw + "'");
        }
    }

    /**
     * Engine's own reinforcement fly-in duration, seconds (default): the
     * {@code spawnFleetMember} burn-drive window the mid-deploy probe measured deterministic
     * ({@code RealEngineMidDeployProbeMain} — ship enters at the side edge
     * under its own travel drive and settles in ~4s of sim time). The probe's determinism came from
     * the burn being EXPLICIT (a non-negative 4th arg zeroes the engine's {@code Math.random()}
     * jitter), not from the literal 4.0 — so the value is a tunable FEEL dial, knob-housed:
     * fail-soft
     * {@code cc.match.flyinBurnSec} / {@code CC_MATCH_FLYIN_BURN_SEC} / config row
     * {@code match.flyinBurnSec}, unset or unparsable = 4.0 (byte-identical to the prior constant),
     * clamped ≥ 1 (the engine {@code deploy()} travel-drive branch only fires at burn ≥ 1). The
     * client's presentation twin ({@code OWN_SPAWN_FLYIN_BURN_SEC}) reads the SAME knob — one dial,
     * both JVMs, no hand-sync.
     */
    static final float FLY_IN_BURN_DUR = Math.max(1.0f, ServerConfig.resolveFloat(
            "cc.match.flyinBurnSec", "CC_MATCH_FLYIN_BURN_SEC", "match.flyinBurnSec", 4.0f));

    /**
     * Mid-round seat fly-in (the D4.4 join-in-progress lane): spawn the descriptor
     * seat's ship into the LIVE round through the engine's OWN reinforcement path —
     * {@code getFleetManager(side).spawnFleetMember(member, null, facing, FLY_IN_BURN_DUR)} with a
     * null location (the engine's own reinforcement edge placement) — the probe-proven recipe
     * verbatim, plus the deploy treatment's crew fix (a crew-less member deploys
     * controls-locked → retreat order) and CR lift (a CR-0 fly-in auto-withdraws;
     * the probe only measured drift — a product joiner must fight).
     *
     * <p>Must run on the engine/sim thread at a frame boundary (the serve loop's reconcile step,
     * step 5) — the spawned ship rides the engine's pending-add queue and drains on the next
     * advance. Reflective handles are re-resolved per call (joins are rare). The fly-in is
     * deliberately NOT added to the deploy treatment's rollback ledger — reset restores the whole
     * prior repository wholesale, so rollback stays sound; the ship lives only in this roster's
     * live/stable maps until then.
     *
     * <p>this is ALSO the gated round reset's seat lane —
     * the wrap deploys the roster with every seat excluded, then flies each occupied seat in here,
     * so joins, respawns and round starts share one fly-in shape (edge placement + burn).
     *
     * <p>Fail-closed preconditions (all THROW): a roster deployed this round, the descriptor path
     * active, {@code seatId} minted, and the seat's wire id not already live-mapped (double
     * fly-in / still-mounted hull).
     *
     * @return the live engine {@code Ship} (already recorded in the live + stable maps)
     */
    public static synchronized Object midDeploySeat(int seatId) {
        return midDeploySeat(seatId, null).ship();
    }

    /**
     * The override-consulting twin — {@code overrideOrNull}
     * (the door's stored canonical {@code /ship} pick, or null = descriptor) selects the deployed
     * hull; an override that fails to resolve FALLS BACK to the descriptor variant with a loud
     * line, never an aborted fly-in (a stale pick must not kill a mount). Returns the full
     * deploy-recorded identity the serve loop's variant-repoint fan needs.
     */
    public static synchronized SeatDeployResult midDeploySeat(int seatId, String overrideOrNull) {
        return midDeploySeat(seatId, overrideOrNull, -1);
    }

    /**
     * The {@code /switchteams} side-consulting twin — {@code sideOrNeg} = 0/1 fields
     * the seat's hull on THAT side's fleet manager (negative = the descriptor's baked side, the
     * pre-switch behavior bit-for-bit). Nothing else changes: the spawn passes a null location, so
     * the ENGINE places the hull at that owner's home edge with the owner-default facing
     * (side 0 = south / 90, side 1 = north / 270) — a team switch needs no pose math. The
     * all-skills fleet-context pair FOLLOWS THE SEAT onto either side (a switched player stays a
     * player — default 1). The returned
     * {@link SeatDeployResult#side} is the side actually fielded (the RAPP row's side byte).
     */
    public static synchronized SeatDeployResult midDeploySeat(int seatId, String overrideOrNull,
            int sideOrNeg) {
        if (sideOrNeg > 1) {
            throw new RealEngineDemoRosterException(
                    "midDeploySeat(" + seatId + "): side " + sideOrNeg + " is not a team (0/1,"
                            + " negative = descriptor)");
        }
        if (!deployed) {
            throw new RealEngineDemoRosterException(
                    "midDeploySeat(" + seatId + ") before this round's roster deploy — the serve"
                            + " loop must deploy (occupancy-gated) before reconciling fly-ins");
        }
        if (deployedDescriptor == null) {
            throw new RealEngineDemoRosterException(
                    "midDeploySeat(" + seatId + ") without an active descriptor — fly-ins exist"
                            + " only on the descriptor-driven npeer path");
        }
        DescriptorLayout.SeatSlot slot = DescriptorLayout.seatSlot(seatId);
        MatchDescriptor.Seat seat = slot.seat();
        if (liveShipByWireId.containsKey(seat.shipId())) {
            throw new RealEngineDemoRosterException(
                    "midDeploySeat(" + seatId + "): wire id " + seat.shipId()
                            + " is already live-mapped — a mounted seat must vacate its hull"
                            + " (mount-loss / reset) before a fresh fly-in");
        }
        ClassLoader loader = RealEngineDemoRoster.class.getClassLoader();
        try {
            Class<?> engineClass = Class.forName(ENGINE_CLASS, true, loader);
            Object engine = engineClass.getMethod("getInstance").invoke(null);
            if (engine == null) {
                throw new RealEngineDemoRosterException(
                        "CombatEngine.getInstance() returned null at midDeploySeat(" + seatId + ")");
            }
            Class<?> shipClass = Class.forName(SHIP_CLASS, false, loader);
            Class<?> hullVariantClass = Class.forName(HULL_VARIANT_SPEC_CLASS, false, loader);

            // Variant by FULL-layout ordinal (occupancy-independent — the same slot the t=0 deploy
            // would have used, so a joiner's hull identity is join-order-invariant), or the seat's
            // stored /ship override when it resolves (W7 B1b — allowlist-canonical id, never wire).
            int side = sideOrNeg >= 0 ? sideOrNeg : slot.side();
            // The ONE pick law (now refuses stations too).
            Method variantIsStation = hullVariantClass.getMethod("isStation");
            PickResolution pick = resolveSeatPick(overrideOrNull, slot.descriptorVariantId(),
                    id -> resolveVariantById(loader, hullVariantClass, id),
                    (v, id) -> Boolean.TRUE.equals(variantIsStation.invoke(v))
                            || MatchDescriptors.isStationVariantId(id));
            if (pick.fallbackReasonOrNull() != null) {
                System.out.println("real-engine-demo-roster midDeploySeat seat=" + seatId
                        + " override_unresolvable id=" + overrideOrNull
                        + " fallback=" + pick.variantId()
                        + " reason=" + pick.fallbackReasonOrNull());
            }
            String variantId = pick.variantId();
            Object variant = pick.pickVariantOrNull() != null
                    ? pick.pickVariantOrNull()
                    : resolveVariantById(loader, hullVariantClass, variantId);
            // The engine build lane must never see the shared REGISTRY
            // singleton (FleetMember stores a passed variant by reference and engine
            // normalization can rewrite it). The seat autofire STRIP was reaped once the
            // defect attribution to it was overturned; the runtime disarm at the
            // fly-in site is the FIRE-only bar — groups_off there counts real disarms again.
            variant = RegistryVariantClone.cloneVariant(variant, "midDeploySeat seat=" + seatId);
            if (deployedVariantId.isEmpty()) {
                deployedVariantId = variantId; // diagnostics: zero-hull boot — first hull this round
            }

            // The probe-verbatim member build (member + name + the crew fix with
            // headroom: combat-time minCrew reads ~25% above the bare member's, so bare-minCrew
            // crewing left getCrewFraction()=0.8 and CRPluginImpl's understrength malus dragged
            // maxCR to 0.6 — crew max(maxCrew,minCrew,1), the engine's own full-crew idiom
            // (FleetMember.readResolve addCrew(100000))).
            Class<?> fleetMemberClass = Class.forName(FLEET_MEMBER_CLASS, false, loader);
            Class<?> fleetMemberTypeClass = Class.forName(FLEET_MEMBER_TYPE_API, false, loader);
            Object member = fleetMemberClass
                    .getConstructor(int.class, hullVariantClass, fleetMemberTypeClass)
                    .newInstance(side, variant, fleetMemberTypeClass.getField("SHIP").get(null));
            fleetMemberClass.getMethod("setShipName", String.class).invoke(member, DescriptorLayout.seatShipName(seatId));
            Object crewComp = fleetMemberClass.getMethod("getCrewComposition").invoke(member);
            float minCrew =
                    ((Number) fleetMemberClass.getMethod("getMinCrew").invoke(member)).floatValue();
            float maxCrew =
                    ((Number) fleetMemberClass.getMethod("getMaxCrew").invoke(member)).floatValue();
            crewComp.getClass().getMethod("setCrew", float.class)
                    .invoke(crewComp, Math.max(Math.max(maxCrew, minCrew), 1.0f));
            // Officer attach BEFORE spawn — a fly-in seat hull must carry the
            // same json-assigned officer the t=0 roster deploy gives it (client parity attach
            // is unconditional per variant, so an officer-less fly-in would desync stats).
            // A fly-in is seat-by-construction — same guard (e) exclusion as the
            // t=0 seat specs (all-skills captain REPLACES the curated row, never stacks).
            if (AllSkillsAttach.enabled()) {
                AllSkillsAttach.attachAllSkillsCaptain(member, DescriptorLayout.seatShipName(seatId));
            } else {
                // Officer TRANSFER: an out-of-table /ship pick falls back to the seat's
                // descriptor-variant row — the officer follows the seat, never a bare hull.
                OfficerAttach.maybeAttachSeat(member, variantId, slot.descriptorVariantId());
            }
            // A side-0 fly-in carries the fleet-context pair like every t=0 side-0
            // member (knob-gated no-op inside; the round's deploy minted the context).
            // The pair is MEMBER-local (setFleetCommanderForStats) and follows a
            // /switchteams seat onto side 1 — without it the derive short-circuits to FULL
            // bonus (the defect), so a switched player would fly a stronger ship.
            if (side == 0 || slot.side() == 0) {
                AllSkillsAttach.installFleetContext(member);
            }

            // The engine's own reinforcement spawn through the round's REAL addFleet-registered
            // manager (the gated deploy registered every descriptor side — W4/W5).
            Object manager = engineClass.getMethod("getFleetManager", int.class).invoke(engine, side);
            if (manager == null) {
                throw new RealEngineDemoRosterException(
                        "midDeploySeat(" + seatId + "): getFleetManager(" + side + ") null — the"
                                + " occupancy-gated deploy must addFleet-register every descriptor"
                                + " side at install");
            }
            Class<?> fleetMemberApiClass = Class.forName(FLEET_MEMBER_API, false, loader);
            Class<?> vec2Class = Class.forName(LWJGL_VECTOR2F_CLASS, false, loader);
            Method spawnFleetMember = manager.getClass().getMethod(
                    "spawnFleetMember", fleetMemberApiClass, vec2Class, float.class, float.class);
            // The facing arg is DEAD with a null loc — the engine applies an explicit facing only
            // when loc != null; the hull spawns at the
            // side's Y-edge with the FleetMember.instantiateForCombat owner default (owner 0 => 90,
            // owner 1 => 270 — the vanilla away-from-entry-edge facing). Kept
            // probe-verbatim; do NOT "fix" it to aim the ship. (the strand
            // pose deploy that once rode the loc != null path is retired — pods strand in place.)
            Object ship = spawnFleetMember.invoke(manager, member, null,
                    side == 0 ? 0.0f : 180.0f, FLY_IN_BURN_DUR);
            if (ship == null || !shipClass.isInstance(ship)) {
                throw new RealEngineDemoRosterException(
                        "midDeploySeat(" + seatId + "): spawnFleetMember returned "
                                + (ship == null ? "null" : ship.getClass().getName()));
            }
            List<?> deployedLedger =
                    (List<?>) manager.getClass().getMethod("getDeployedCopy").invoke(manager);
            if (!deployedLedger.contains(member)) {
                throw new RealEngineDemoRosterException(
                        "midDeploySeat(" + seatId + "): deployed ledger does not contain the member");
            }
            Class<?> shipApiClass = Class.forName(SHIP_API, false, loader);
            Object alive = engineClass.getMethod("isShipAlive", shipApiClass).invoke(engine, ship);
            if (!Boolean.TRUE.equals(alive)) {
                throw new RealEngineDemoRosterException(
                        "midDeploySeat(" + seatId + "): spawned ship not engine-alive at spawn");
            }
            // The respawn's toString() key never shares a hulk's
            // (the previous life's hulk otherwise flames the successor's motes at birth).
            SameKeyHulkRename.sweepEngine(engine, ship, "midDeploySeat");
            // CR lift (build refinement over probe-verbatim): a CR-0 fly-in
            // auto-withdraws; the seed is the engine-computed maxCR
            // (post-officer-attach, {@link #chooseDeployCr}) floored at the descriptor deployCr.
            // The write crosses the engine-native seam ({@link CrDeploySeam}):
            // a bare field pair never ran applyCRToStats, so the sim fought without cr_effect.
            float deployCr = deployedDescriptor.deployCr();
            Object crStat = Class.forName(MUTABLE_SHIP_STATS_API, false, loader)
                    .getMethod("getMaxCombatReadiness")
                    .invoke(shipClass.getMethod("getMutableStats").invoke(ship));
            float computedCr = ((Number) Class.forName(MUTABLE_STAT_CLASS, false, loader)
                    .getMethod("getModifiedValue").invoke(crStat)).floatValue();
            float chosenCr = chooseDeployCr(computedCr, deployCr, "midDeploySeat seat=" + seatId);
            CrDeploySeam.applyDeployCr(ship, chosenCr);
            TerrainStatSeam.applyToShip(ship); //

            // Same stable-id trim/THROW idiom as the t=0 capture.
            Object idValue = shipClass.getMethod("getId").invoke(ship);
            String stable = (idValue instanceof String) ? ((String) idValue).trim() : "";
            if (stable.isEmpty()) {
                throw new RealEngineDemoRosterException(
                        "midDeploySeat(" + seatId + "): stable-id read failed (ShipAPI.getId()"
                                + " returned "
                                + (idValue == null ? "null" : idValue.getClass().getName()) + ")");
            }
            Map<Long, String> stableByWire = new LinkedHashMap<>(seatStableIdByWireId);
            stableByWire.put(seat.shipId(), stable);
            seatStableIdByWireId = Collections.unmodifiableMap(stableByWire);
            seatStableIdsThisRound.add(stable);
            liveShipByWireId.put(seat.shipId(), ship);
            deployedShipCount++;
            System.out.println("real-engine-demo-roster midDeploySeat seat=" + seatId
                    + " wireId=" + seat.shipId() + " side=" + side
                    + " variant=" + variantId + " stableId=" + stable
                    + " burnDur=" + FLY_IN_BURN_DUR
                    + hullPhys(shipClass, ship)
                    + " (pending-add until next advance)");
            // The fly-in's fielded id + pick counters
            // (the reset's row reads them; last write wins per seat, cleared per reset).
            deployedVariantBySeatId.put(seatId, variantId);
            if (pick.override()) {
                overrideCount++;
            } else if (pick.fallbackReasonOrNull() != null) {
                overrideFallbackCount++;
            }
            return new SeatDeployResult(ship, variantId, slot.descriptorVariantId(), side,
                    DescriptorLayout.seatShipName(seatId), DescriptorLayout.crPerMille(chosenCr),
                    seat.shipId(), stable);
        } catch (RealEngineDemoRosterException e) {
            throw e;
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            throw new RealEngineDemoRosterException(
                    "midDeploySeat(" + seatId + ") failed: "
                            + cause.getClass().getName() + ": "
                            + (cause.getMessage() == null ? "" : cause.getMessage()),
                    cause);
        }
    }

    /** One seat fly-in's deploy-recorded identity (W7 B1b): the live engine ship + the DEPLOYED
     * variant (override or descriptor), the seat's descriptor truth, and the RAPP row fields
     * the serve loop's variant-repoint fan sends.
     * {@code wireId} (the seat's descriptor ship id) + {@code stableId} (the spawned hull's
     * engine id) — the round-reset lane builds its fly-in results from these directly. */
    public record SeatDeployResult(Object ship, String variantId, String descriptorVariantId,
            int side, String shipName, int crPerMille, long wireId, String stableId) {
    }

    /** The seat/wave ship's
     *  runtime hull geometry appended to the spawn row so the next served match answers the
     *  TT-Brawler soft-collision row (custom vs stock hull mass/radius). Reads through the
     *  proven ShipAPI reflection pattern, in its OWN try/catch —
     *  a getMass/getCollisionRadius drift reads "unread" and can NEVER abort a fly-in. Per-spawn
     *  only, engine floats only (no wire-derived text). */
    private static String hullPhys(Class<?> shipClass, Object ship) {
        try {
            Object mass = shipClass.getMethod("getMass").invoke(ship);
            Object collRadius = shipClass.getMethod("getCollisionRadius").invoke(ship);
            return " mass=" + mass + " collRadius=" + collRadius;
        } catch (Throwable readMiss) {
            return " mass=unread collRadius=unread";
        }
    }

    /** The spawned hull's engine collision radius for the
     *  adhoc cluster spacing ({@code AdhocFoeSpawner.clusterSpacing}) — the {@link #hullPhys}
     *  reflection idiom in its OWN try/catch: a getter drift reads 0 (the spacing floor) with one
     *  bounded line and can NEVER abort a fly-in. Per-spawn only, engine float only. */
    private static float collisionRadiusOrZero(Class<?> shipClass, Object ship, String site) {
        try {
            Object radius = shipClass.getMethod("getCollisionRadius").invoke(ship);
            return radius instanceof Number ? ((Number) radius).floatValue() : 0f;
        } catch (Throwable readMiss) {
            System.out.println("real-engine-demo-roster " + site
                    + " collision_radius_unread err=" + readMiss.getClass().getSimpleName());
            return 0f;
        }
    }

    /** Resolves a pick id to an engine variant (throws = unresolvable). */
    @FunctionalInterface
    public interface PickResolver {
        Object resolve(String variantId) throws Exception;
    }

    /** The station refusal predicate (engine {@code isStation} OR the mint predicate). */
    @FunctionalInterface
    public interface StationCheck {
        boolean isStation(Object variant, String variantId) throws Exception;
    }

    /**
     * One seat pick's resolution. {@code pickVariantOrNull}
     * = the resolved pick variant to field, or null = field the DESCRIPTOR row (the caller's own
     * descriptor object); {@code variantId} = the id fielded; {@code override} = a pick was
     * fielded as picked; {@code fallbackReasonOrNull} = why a pick was refused (non-null ⇒ the
     * caller counts a fallback and prints its {@code override_unresolvable} line).
     */
    public record PickResolution(Object pickVariantOrNull, String variantId, boolean override,
            String fallbackReasonOrNull) {
    }

    /**
     * THE seat pick-resolution law, shared by the round's
     * deploy lane ({@code buildDescriptorSpecs}) and every seat fly-in ({@link #midDeploySeat}):
     * null → descriptor (neither override nor fallback); pick == descriptor → descriptor object,
     * an override; resolver throws → descriptor, reason = the Throwable's simple name; a station
     * → descriptor, reason {@code station}; else the pick. Public only so the game-free contract
     * rows can inject both seams; counters and log lines stay at the call sites.
     */
    public static PickResolution resolveSeatPick(String pickOrNull, String descriptorVariantId,
            PickResolver resolver, StationCheck stationCheck) {
        if (pickOrNull == null) {
            return new PickResolution(null, descriptorVariantId, false, null);
        }
        if (pickOrNull.equals(descriptorVariantId)) {
            return new PickResolution(null, descriptorVariantId, true, null);
        }
        try {
            Object pickVariant = resolver.resolve(pickOrNull);
            if (stationCheck.isStation(pickVariant, pickOrNull)) {
                return new PickResolution(null, descriptorVariantId, false, "station");
            }
            return new PickResolution(pickVariant, pickOrNull, true, null);
        } catch (Throwable overrideMiss) {
            return new PickResolution(null, descriptorVariantId, false,
                    overrideMiss.getClass().getSimpleName());
        }
    }

    /**
     * cr_effect deploy-CR seed law: seed {@code max(floor, computed)}
     * where {@code computed} = the engine's own {@code getMutableStats().getMaxCombatReadiness()
     * .getModifiedValue()} read AFTER officer attach. The client's vanilla {@code addToFleet}
     * deploy gets engine-computed maxCR (0.7 "Basic maintenance" + the officer's max-CR
     * contribution) — a flat descriptor 0.7 sits exactly ON
     * the engine's cr_effect zero edge and DROPS that officer delta (the
     * whole measured 174.45229-vs-174.39999 maxSpeed gap). The floor keeps the
     * anti-withdraw guarantee if a headless member's crew-modifier path computes low
     * (MEASURE-first guard); a non-finite read falls to the floor rather than seeding NaN.
     */
    private static float chooseDeployCr(float computed, float floor, String site) {
        float chosen = Float.isFinite(computed) ? Math.max(floor, computed) : floor;
        System.out.println("real-engine-demo-roster " + site + " deploy CR seed computed="
                + computed + " floor=" + floor + " chosen=" + chosen);
        return chosen;
    }

    /** One wave deploy's identity: the engine ship's stable id ({@code ShipAPI.getId().trim()},
     * the SAME derivation capture uses — stable-key parity) + the CR it deployed at. */
    public record WaveDeployResult(String stableId, float deployCr, List<String> moduleStableIds,
            OfficerRosterCodec.OfficerSpec officerOrNull, float collisionRadius, boolean atRest) {

        /** {@code moduleStableIds} = the engine-minted
         * STATION_MODULE children's stable ids (same {@code getId().trim()} derivation), read
         * right after {@code spawnFleetMember} returns for a {@code variant.isStation()} hull;
         * EMPTY for every other hull. The serve loop interns them as the contiguous wire band
         * after the root and seeds the growth known-keys so they are never RAPP-announced.
         * {@code officerOrNull} = the captain {@code OfficerAttach} ACTUALLY
         * attached at the mint (row spec or table row; null = bare) — the RAPP v3 officer tail. */
        public WaveDeployResult {
            moduleStableIds = moduleStableIds == null ? List.of() : List.copyOf(moduleStableIds);
            // collisionRadius = the spawned hull's engine
            // getCollisionRadius() (the adhoc cluster spacing source); finite >= 0, else 0.
            collisionRadius = Float.isFinite(collisionRadius) && collisionRadius > 0f
                    ? collisionRadius : 0f;
        }

        /**
         * The original fly-in shape — {@code atRest=false} (the hull flew in).
         * {@code atRest} = the mint's own {@code burnDur < 1.0f} — placed
         * AT the pose with no travel drive (the engine turns the drive on only at burn ≥ 1.0
         * in its fleet manager); the edge slot and
         * {@code midDeploySeat} burn {@link RealEngineDemoRoster#FLY_IN_BURN_DUR} (≥ 1 by its clamp). It rides
         * the RAPP v5 flags bit so the client twin skips its parity burn.
         */
        public WaveDeployResult(String stableId, float deployCr, List<String> moduleStableIds,
                OfficerRosterCodec.OfficerSpec officerOrNull, float collisionRadius) {
            this(stableId, deployCr, moduleStableIds, officerOrNull, collisionRadius, false);
        }

        /** The older shape — no collision radius reported (0 = unread). */
        public WaveDeployResult(String stableId, float deployCr, List<String> moduleStableIds,
                OfficerRosterCodec.OfficerSpec officerOrNull) {
            this(stableId, deployCr, moduleStableIds, officerOrNull, 0f);
        }

        /** The older shape — no captain reported. */
        public WaveDeployResult(String stableId, float deployCr, List<String> moduleStableIds) {
            this(stableId, deployCr, moduleStableIds, null);
        }

        /** The older shape — a hull with no module cluster. */
        public WaveDeployResult(String stableId, float deployCr) {
            this(stableId, deployCr, List.of());
        }
    }

    /**
     * Wave-mint reflective handles, resolved ONCE per JVM (the
     * {@code OfficerAttach.Recipe} idiom — this class had no handle caching at all). The
     * first wave mint paid the full uncached {@code Class.forName}/{@code getMethod} set ×5
     * hulls inside ONE served frame, and every later mint repaid the
     * linear {@code getMethod} scans; a steady-state win for EVERY mint. The three methods
     * below the holder resolve off instance RUNTIME classes (crew composition, fleet
     * manager — stable within a JVM) at the first mint that holds one. All resolution runs
     * under the class lock: every caller is a {@code static synchronized} method.
     */
    private static final class WaveMintHandles {
        final Class<?> engineClass;
        final Class<?> shipClass;
        final Class<?> hullVariantClass;
        final Class<?> fleetMemberApiClass;
        final Class<?> vec2Class;
        final Object fleetMemberTypeShip;
        final Constructor<?> fleetMemberCtor;
        final Constructor<?> vec2Ctor;
        final Method engineGetInstance;
        final Method setShipName;
        final Method getCrewComposition;
        final Method getMinCrew;
        final Method getMaxCrew;
        final Method getFleetManager;
        final Method getMapHeight;
        final Method isShipAlive;
        final Method getId;
        /** The cr_effect seed read chain (stat getters on the PUBLIC interfaces). */
        final Method getMutableStats;
        final Method getMaxCombatReadiness;
        final Method statGetModifiedValue;

        WaveMintHandles(ClassLoader loader) throws ReflectiveOperationException {
            engineClass = Class.forName(ENGINE_CLASS, true, loader);
            shipClass = Class.forName(SHIP_CLASS, false, loader);
            hullVariantClass = Class.forName(HULL_VARIANT_SPEC_CLASS, false, loader);
            Class<?> fleetMemberClass = Class.forName(FLEET_MEMBER_CLASS, false, loader);
            Class<?> fleetMemberTypeClass = Class.forName(FLEET_MEMBER_TYPE_API, false, loader);
            fleetMemberApiClass = Class.forName(FLEET_MEMBER_API, false, loader);
            vec2Class = Class.forName(LWJGL_VECTOR2F_CLASS, false, loader);
            Class<?> shipApiClass = Class.forName(SHIP_API, false, loader);
            fleetMemberTypeShip = fleetMemberTypeClass.getField("SHIP").get(null);
            fleetMemberCtor = fleetMemberClass.getConstructor(
                    int.class, hullVariantClass, fleetMemberTypeClass);
            vec2Ctor = vec2Class.getConstructor(float.class, float.class);
            engineGetInstance = engineClass.getMethod("getInstance");
            setShipName = fleetMemberClass.getMethod("setShipName", String.class);
            getCrewComposition = fleetMemberClass.getMethod("getCrewComposition");
            getMinCrew = fleetMemberClass.getMethod("getMinCrew");
            getMaxCrew = fleetMemberClass.getMethod("getMaxCrew");
            getFleetManager = engineClass.getMethod("getFleetManager", int.class);
            getMapHeight = engineClass.getMethod("getMapHeight");
            isShipAlive = engineClass.getMethod("isShipAlive", shipApiClass);
            getId = shipClass.getMethod("getId");
            getMutableStats = shipClass.getMethod("getMutableStats");
            getMaxCombatReadiness = Class.forName(MUTABLE_SHIP_STATS_API, false, loader)
                    .getMethod("getMaxCombatReadiness");
            statGetModifiedValue = Class.forName(MUTABLE_STAT_CLASS, false, loader)
                    .getMethod("getModifiedValue");
        }
    }

    private static WaveMintHandles waveMintHandles;
    /** Resolved off the first crew composition's RUNTIME class (stable within a JVM). */
    private static Method waveSetCrew;
    /** Resolved off the first fleet manager's RUNTIME class (stable within a JVM). */
    private static Method waveSpawnFleetMember;
    private static Method waveGetDeployedCopy;

    private static WaveMintHandles waveMintHandlesOrResolve(ClassLoader loader)
            throws ReflectiveOperationException {
        if (waveMintHandles == null) {
            waveMintHandles = new WaveMintHandles(loader);
        }
        return waveMintHandles;
    }

    /**
     * Mid-round scheduled-wave AI fly-in: the {@link #midDeploySeat} recipe minus
     * the seat bookkeeping — build a fresh {@code FleetMember} for {@code variantId} and spawn it
     * through the engine's OWN reinforcement path ({@code spawnFleetMember} with a null location =
     * the engine's reinforcement-edge placement + explicit burn), through the round's REAL
     * addFleet-registered manager. NEVER {@code spawnShipOrWing}: it DEDUPES by specId against the
     * reserves (a second same-variant call re-deploys the
     * SAME member), which is exactly wrong for duplicate-hull waves; {@code spawnFleetMember}
     * takes a fresh member that need not be in reserves.
     *
     * <p>NO wire-id/live-map writes: wave hulls are not seats — {@code liveShipByWireId} /
     * {@code seatStableIdByWireId} stay seat-only (their vacate/remount discipline never applies
     * here — a dead wave hull just stays a hulk). The returned {@link WaveDeployResult} carries
     * the deploy-recorded stable id + deploy CR so the serve loop can intern the wire id AT MINT
     * and fan the {@code RAPP} roster-append row (capture's later
     * first-sight intern finds the key already present, same id). {@code deployedShipCount} is
     * deliberately NOT incremented (both its readers are boot-time ship-count overrides,
     * pre-first-wave by construction). Same crew fix (a no-op at the Remnants'
     * 0-crew), same CR lift, same pending-add semantics (ledger membership +
     * engine-alive checks at spawn; the hull enters the repository on the next advance).
     * Fail-closed THROWS, never a half-spawned wave row.
     */
    public static synchronized WaveDeployResult midDeployWaveShip(
            int side, String variantId, String shipName) {
        return midDeployCore(side, variantId, shipName, null, FLY_IN_BURN_DUR, "midDeployWaveShip",
                null);
    }

    /**
     * The wave verb with an OPTIONAL explicit pose — {@code poseOrNull} =
     * {@code {x, y, facing}} places the hull AT that point with {@code burnDur 0} (no travel
     * drive, the {@link #midDeployAtRest} law);
     * null = the 3-arg edge slot + fly-in burn, byte-identical to
     * {@link #midDeployWaveShip(int, String, String)}. The {@code /spawnfoe <v> [n] [@x,y]}
     * lane's one entry.
     */
    public static synchronized WaveDeployResult midDeployWaveShip(
            int side, String variantId, String shipName, float[] poseOrNull) {
        return midDeployWaveShip(side, variantId, shipName, poseOrNull, null);
    }

    /**
     * The 4-arg verb plus an OPTIONAL row-carried officer
     * ({@code WaveSchedule.WaveRow.officer()} — the faction tables' per-wave officer rule and
     * the Ziggurat boss captain); null = the variant-keyed table lookup, byte-identical to
     * the 4-arg verb. The scheduled-wave drain's one entry.
     */
    public static synchronized WaveDeployResult midDeployWaveShip(
            int side, String variantId, String shipName, float[] poseOrNull,
            OfficerRosterCodec.OfficerSpec officerOrNull) {
        if (poseOrNull == null) {
            return midDeployCore(side, variantId, shipName, null, FLY_IN_BURN_DUR,
                    "midDeployWaveShip", officerOrNull);
        }
        if (poseOrNull.length != 3) {
            throw new RealEngineDemoRosterException(
                    "midDeployWaveShip(" + shipName + ") pose must be {x, y, facing}, got "
                            + poseOrNull.length + " values");
        }
        return midDeployCore(side, variantId, shipName, poseOrNull.clone(), 0f,
                "midDeployWaveShip", officerOrNull);
    }

    /** Per-JVM handles for the post-spawn module read
     * ({@code HullVariantSpec.isStation} / {@code Ship.getChildModulesCopy}) — the
     * {@code waveSpawnFleetMember} lazy-cache idiom. */
    private static Method waveIsStation;
    private static Method waveGetChildModulesCopy;

    /**
     * Read the engine-minted STATION_MODULE children of a freshly
     * spawned {@code variant.isStation()} hull — {@code getChildModulesCopy()} right after
     * {@code spawnFleetMember} returns (the CFM auto-creates the cluster inside the spawn,
     * as measured; the fortress t=0 pre-seed reads the same seam). Count MUST
     * equal {@code EngineBornGrowthSeam.stationModuleVariantIds(variantId).size()} — the
     * serve entry bounded the stable roster on the DECLARED count, so a mismatch (or a
     * lazily-minted, still-empty cluster) is refused HERE, loud, never as a mid-round
     * roster-bound kill. Non-station hulls return EMPTY without touching the engine.
     */
    private static List<String> readStationModuleStableIds(WaveMintHandles h, Object variant,
            Object ship, String variantId, String site, String shipName) throws Exception {
        if (waveIsStation == null) {
            waveIsStation = h.hullVariantClass.getMethod("isStation");
        }
        if (!Boolean.TRUE.equals(waveIsStation.invoke(variant))) {
            return List.of();
        }
        int expectedModules = EngineBornGrowthSeam.stationModuleVariantIds(variantId).size();
        if (waveGetChildModulesCopy == null) {
            waveGetChildModulesCopy = h.shipClass.getMethod("getChildModulesCopy");
        }
        Object rawChildren = waveGetChildModulesCopy.invoke(ship);
        if (!(rawChildren instanceof List)) {
            throw new RealEngineDemoRosterException(
                    site + "(" + shipName + "): station getChildModulesCopy() returned "
                            + (rawChildren == null ? "null" : rawChildren.getClass().getName()));
        }
        List<?> children = (List<?>) rawChildren;
        if (children.size() != expectedModules) {
            throw new RealEngineDemoRosterException(
                    site + "(" + shipName + "): station '" + variantId + "' has "
                            + children.size() + " child modules right after spawnFleetMember"
                            + " but its variant declares " + expectedModules
                            + " (getStationModules) — " + (children.isEmpty()
                                    ? "the engine minted the cluster lazily (read it at the"
                                            + " deploy-ledger confirm point instead)"
                                    : "a cluster-size mismatch")
                            + "; refusing an unbudgeted module band");
        }
        List<String> moduleStableIds = new ArrayList<>(children.size());
        for (int m = 0; m < children.size(); m++) {
            Object child = children.get(m);
            if (child == null || !h.shipClass.isInstance(child)) {
                throw new RealEngineDemoRosterException(
                        site + "(" + shipName + "): station module " + m + " is "
                                + (child == null ? "null" : child.getClass().getName())
                                + ", not a Ship");
            }
            Object childId = h.getId.invoke(child);
            String childStable = (childId instanceof String) ? ((String) childId).trim() : "";
            if (childStable.isEmpty()) {
                throw new RealEngineDemoRosterException(
                        site + "(" + shipName + "): station module " + m
                                + " stable-id read failed");
            }
            moduleStableIds.add(childStable);
        }
        return moduleStableIds;
    }

    /**
     * The {@code /podtest} driver's friendly TARGET — the
     * {@link #midDeployWaveShip} body with an EXPLICIT pose and {@code burnDur 0}
     * — {@code spawnFleetMember(member, loc,
     * facing, 0f)} = no travel drive, placed AT {@code loc} (every other spawn path clamps the
     * burn ≥ 1 s and flies in from the edge). Same pending-add semantics: the caller polls the
     * target alive, THEN pins it ({@code TransferPodSpawner.holdStill}) and owns the wire-id
     * intern + RAPP row exactly as the wave/adhoc drains do. Fail-closed THROWS like the wave verb.
     */
    public static synchronized WaveDeployResult midDeployAtRest(int side, String variantId,
            String shipName, float x, float y, float facing) {
        return midDeployCore(side, variantId, shipName, new float[] {x, y, facing}, 0f,
                "midDeployAtRest", null);
    }

    /** The shared mid-round mint body of {@link #midDeployWaveShip} / {@link #midDeployAtRest}
     * (an extraction, byte-identical semantics for the wave verb):
     * {@code poseOrNull} = {@code {x, y, facing}} or null = the wave lane's edge slot + stagger
     * + side-facing; {@code site} labels every throw and the stdout row. Called under the class
     * lock by both public wrappers. */
    private static WaveDeployResult midDeployCore(int side, String variantId, String shipName,
            float[] poseOrNull, float burnDur, String site,
            OfficerRosterCodec.OfficerSpec officerOrNull) {
        if (side != 0 && side != 1) {
            throw new RealEngineDemoRosterException(
                    site + " side must be 0 or 1, got " + side);
        }
        if (!deployed) {
            throw new RealEngineDemoRosterException(
                    site + "(" + shipName + ") before this round's roster deploy — the"
                            + " serve loop must deploy before draining wave spawns");
        }
        if (deployedDescriptor == null) {
            throw new RealEngineDemoRosterException(
                    site + "(" + shipName + ") without an active descriptor — wave"
                            + " spawns exist only on the descriptor-driven npeer path");
        }
        ClassLoader loader = RealEngineDemoRoster.class.getClassLoader();
        try {
            // All loader/member/ship handles come from the cached
            // holder — resolution order and semantics identical to the pre-cache body, paid
            // once per JVM instead of ~75 resolutions per mint.
            WaveMintHandles h = waveMintHandlesOrResolve(loader);
            long mintStartNanos = System.nanoTime(); // pre_wave sub-split (3 stamps)
            Object engine = h.engineGetInstance.invoke(null);
            if (engine == null) {
                throw new RealEngineDemoRosterException(
                        "CombatEngine.getInstance() returned null at " + site + "(" + shipName + ")");
            }
            Object variant = resolveVariantById(loader, h.hullVariantClass, variantId);
            // Wave lane: clone ONLY — a wave hull is a server-AI
            // combatant, vanilla default-ON autofire must survive. The clone protects the
            // registry singleton from engine build-normalization write-back.
            variant = RegistryVariantClone.cloneVariant(variant, site + " " + shipName);

            // The probe-verbatim member build (member + name + the crew fix with
            // headroom max(maxCrew,minCrew,1) — still a no-op on the 0-crew automated Remnant
            // hulls, kept for recipe uniformity).
            Object member = h.fleetMemberCtor.newInstance(side, variant, h.fleetMemberTypeShip);
            h.setShipName.invoke(member, shipName);
            Object crewComp = h.getCrewComposition.invoke(member);
            float minCrew = ((Number) h.getMinCrew.invoke(member)).floatValue();
            float maxCrew = ((Number) h.getMaxCrew.invoke(member)).floatValue();
            if (waveSetCrew == null) {
                waveSetCrew = crewComp.getClass().getMethod("setCrew", float.class);
            }
            waveSetCrew.invoke(crewComp, Math.max(Math.max(maxCrew, minCrew), 1.0f));
            // Officer attach BEFORE spawn (variant-row lookup).
            // the fortress chain's *_cc_* AI-core TWINS get their captain HERE — a bare vanilla
            // wave id has no row and a miss stays a no-op ("SOME hulls" law).
            // a ROW-carried spec (faction officer rule / Ziggurat boss) wins over the table.
            // What was ACTUALLY attached rides the deploy result → the RAPP v3
            // officer tail (the client twin fields the same captain).
            OfficerRosterCodec.OfficerSpec attachedOfficer =
                    OfficerAttach.attachOrNull(member, variantId, officerOrNull);
            long mintEndNanos = System.nanoTime();

            Object manager = h.getFleetManager.invoke(engine, side);
            if (manager == null) {
                throw new RealEngineDemoRosterException(
                        site + "(" + shipName + "): getFleetManager(" + side + ") null —"
                                + " the gated deploy must addFleet-register every descriptor side"
                                + " at install");
            }
            if (waveSpawnFleetMember == null) {
                waveSpawnFleetMember = manager.getClass().getMethod(
                        "spawnFleetMember", h.fleetMemberApiClass, h.vec2Class,
                        float.class, float.class);
                waveGetDeployedCopy = manager.getClass().getMethod("getDeployedCopy");
            }
            // Burn-in-outside-map fix: a null loc rides
            // the engine's reinforcement Y-law — edge + margin + a PERSISTENT stagger accumulator
            // (cap 10000) — and this one-ship-per-call pattern
            // pumps that accumulator ~5x vanilla's batched rate until late waves park far outside
            // the border, beyond fly-in-burn recovery. An explicit loc suspends the accumulator
            // and pins the spawn to the LIVE border read at spawn
            // time (tracks any npeer-map-bounds resize). Facing goes LIVE with a non-null loc:
            // 90/270 toward the foe (the descriptor deploy-grid law).
            float mapHalfY = ((Number) h.getMapHeight.invoke(engine)).floatValue() * 0.5f;
            float spawnX;
            float spawnY;
            float facing;
            if (poseOrNull == null) {
                spawnX = WAVE_SPAWN_X_SLOTS[waveSpawnXSlot];
                waveSpawnXSlot = (waveSpawnXSlot + 1) % WAVE_SPAWN_X_SLOTS.length;
                spawnY = (side == 0 ? -1.0f : 1.0f) * (mapHalfY + WAVE_SPAWN_EDGE_MARGIN);
                facing = side == 0 ? 90.0f : 270.0f;
            } else {
                // An explicit pose (podtest target) — no edge slot, no
                // stagger; burnDur 0 = placed AT loc with no travel drive.
                spawnX = poseOrNull[0];
                spawnY = poseOrNull[1];
                facing = poseOrNull[2];
            }
            Object spawnLoc = h.vec2Ctor.newInstance(spawnX, spawnY);
            Object ship = waveSpawnFleetMember.invoke(
                    manager, member, spawnLoc, facing, burnDur);
            long engineEndNanos = System.nanoTime();
            if (ship == null || !h.shipClass.isInstance(ship)) {
                throw new RealEngineDemoRosterException(
                        site + "(" + shipName + "): spawnFleetMember returned "
                                + (ship == null ? "null" : ship.getClass().getName()));
            }
            List<?> deployedLedger = (List<?>) waveGetDeployedCopy.invoke(manager);
            if (!deployedLedger.contains(member)) {
                throw new RealEngineDemoRosterException(
                        site + "(" + shipName + "): deployed ledger does not contain the member");
            }
            Object alive = h.isShipAlive.invoke(engine, ship);
            if (!Boolean.TRUE.equals(alive)) {
                throw new RealEngineDemoRosterException(
                        site + "(" + shipName + "): spawned ship not engine-alive at spawn");
            }
            // Same law as midDeploySeat — a schedule that re-uses a
            // wave ship name never keys a live hull onto an earlier wave's hulk.
            SameKeyHulkRename.sweepEngine(engine, ship, site);
            // CR lift: a CR-0 fly-in auto-withdraws; wave foes must fight.
            // deployCr 0.7 also clears the CR<0.4 malfunction band. The
            // seed is the engine-computed maxCR (post-officer-attach — the fortress *_cc_*
            // AI-core twins carry captains) floored at deployCr ({@link #chooseDeployCr}).
            float deployCr = deployedDescriptor.deployCr();
            float computedCr = ((Number) h.statGetModifiedValue.invoke(
                    h.getMaxCombatReadiness.invoke(h.getMutableStats.invoke(ship)))).floatValue();
            float chosenCr = chooseDeployCr(computedCr, deployCr,
                    site + " " + shipName);
            CrDeploySeam.applyDeployCr(ship, chosenCr);
            TerrainStatSeam.applyToShip(ship); //
            // WHICH ship AI the engine picked for this hull + the personality
            // it resolves to (+ the ModManager plugin list once) — the vanilla-personality seam
            // evidence ({@link AiPickEvidence} javadoc = the why). Spawn-time only, read-only.
            System.out.println("ai-pick " + site + " hull=" + shipName + " variant=" + variantId
                    + " " + AiPickEvidence.describe(ship)
                    + AiPickEvidence.pluginListOnce(ship.getClass().getClassLoader()));

            // Stable-id read for the log + the typed return (still no map writes — see the
            // javadoc note above; the serve loop owns the intern).
            Object idValue = h.getId.invoke(ship);
            String stable = (idValue instanceof String) ? ((String) idValue).trim() : "";
            if (stable.isEmpty()) {
                throw new RealEngineDemoRosterException(
                        site + "(" + shipName + "): stable-id read failed (ShipAPI.getId()"
                                + " returned "
                                + (idValue == null ? "null" : idValue.getClass().getName()) + ")");
            }
            // The module band of a station-shaped hull (empty
            // otherwise) — read NOW, before the id is returned, so the serve loop interns
            // root+modules contiguously in the same call.
            List<String> moduleStableIds = readStationModuleStableIds(
                    h, variant, ship, variantId, site, shipName);
            System.out.println("real-engine-demo-roster " + site + " side=" + side
                    + " variant=" + variantId + " name=" + shipName + " stableId=" + stable
                    + " burnDur=" + burnDur + " spawn=" + spawnX + "," + spawnY
                    + " mapHalfY=" + mapHalfY + " modules=" + moduleStableIds.size()
                    + hullPhys(h.shipClass, ship)
                    + " (pending-add until next advance)");
            long postEndNanos = System.nanoTime();
            lastWaveMintSplit = String.format(java.util.Locale.ROOT,
                    "clone_member_officer=%.2f engine_spawn=%.2f ledger_cr_id=%.2f",
                    (mintEndNanos - mintStartNanos) / 1_000_000.0d,
                    (engineEndNanos - mintEndNanos) / 1_000_000.0d,
                    (postEndNanos - engineEndNanos) / 1_000_000.0d);
            return new WaveDeployResult(stable, chosenCr, moduleStableIds, attachedOfficer,
                    collisionRadiusOrZero(h.shipClass, ship, site), burnDur < 1.0f); //
        } catch (RealEngineDemoRosterException e) {
            throw e;
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            throw new RealEngineDemoRosterException(
                    site + "(" + shipName + ") failed: "
                            + cause.getClass().getName() + ": "
                            + (cause.getMessage() == null ? "" : cause.getMessage()),
                    cause);
        }
    }

    /** The LAST {@link #midDeployWaveShip} call's phase split (ms) — which of
     *  our clone/member/officer mint, the engine's own {@code spawnFleetMember}, or the
     *  post-spawn ledger/CR/id reads burned; the serve loop prints it on the wave_spawn row so
     *  a pre_wave stall is attributed, never guessed. Empty until the first mint. */
    private static volatile String lastWaveMintSplit = "";

    public static String lastWaveMintSplit() {
        return lastWaveMintSplit;
    }

    /**
     * One raw engine-truth sample line for a wave-probe hull, keyed by
     * stable id — the gate discriminator (raw owner sentinel / travel-drive stuck /
     * controls locked / bay-and-wing census / fleet-manager attachment). Read-only, NEVER
     * throws: probe instrumentation must not be able to kill the serve it is diagnosing
     * (a genuine wave-lane failure still dies loudly on the (c2b) spawn path, not here).
     *
     * <p>{@code mgr_attached}: {@code CombatEngine.getFleetManager(owner)} mints a FRESH
     * detached manager per call for an owner outside the registered list
     * (it never returns null), so two consecutive calls
     * returning DIFFERENT identities pin "raw owner has no registered manager" directly.
     */
    public static String waveProbeSampleLine(String stableId) {
        ClassLoader loader = RealEngineDemoRoster.class.getClassLoader();
        try {
            Class<?> engineClass = Class.forName(ENGINE_CLASS, true, loader);
            Object engine = engineClass.getMethod("getInstance").invoke(null);
            if (engine == null) {
                return "sample_error=no_engine_instance";
            }
            Class<?> shipClass = Class.forName(SHIP_CLASS, false, loader);
            List<?> ships = (List<?>) engineClass.getMethod("getShips").invoke(engine);
            Object ship = null;
            for (Object candidate : ships) {
                if (shipClass.isInstance(candidate)
                        && stableId.equals(shipClass.getMethod("getId").invoke(candidate))) {
                    ship = candidate;
                    break;
                }
            }
            if (ship == null) {
                return "in_ships=false ships_total=" + ships.size();
            }
            Class<?> shipApiClass = Class.forName(SHIP_API, false, loader);
            Object alive = engineClass.getMethod("isShipAlive", shipApiClass).invoke(engine, ship);
            int owner = ((Number) shipClass.getMethod("getOwner").invoke(ship)).intValue();
            int origOwner =
                    ((Number) shipClass.getMethod("getOriginalOwner").invoke(ship)).intValue();
            boolean travel =
                    Boolean.TRUE.equals(shipClass.getMethod("isTravelDriveOn").invoke(ship));
            boolean locked =
                    Boolean.TRUE.equals(shipClass.getMethod("controlsLocked").invoke(ship));
            float deployedS =
                    ((Number) shipClass.getMethod("getFullTimeDeployed").invoke(ship)).floatValue();
            Object vel = shipClass.getMethod("getVelocity").invoke(ship);
            float velLen = ((Number) vel.getClass().getMethod("length").invoke(vel)).floatValue();
            // Raw location/facing + the live child-module census, so
            // a station-shaped probe hull's translate-vs-hold and cluster survival read
            // straight off the sample row (position, not just |vel|).
            Object loc = shipClass.getMethod("getLocation").invoke(ship);
            float locX = ((Number) loc.getClass().getField("x").get(loc)).floatValue();
            float locY = ((Number) loc.getClass().getField("y").get(loc)).floatValue();
            float facing = ((Number) shipClass.getMethod("getFacing").invoke(ship)).floatValue();
            Object rawChildren = shipClass.getMethod("getChildModulesCopy").invoke(ship);
            int modules = rawChildren instanceof List ? ((List<?>) rawChildren).size() : -1;
            int modulesAlive = 0;
            if (rawChildren instanceof List) {
                for (Object child : (List<?>) rawChildren) {
                    if (child != null && Boolean.TRUE.equals(
                            engineClass.getMethod("isShipAlive", shipApiClass)
                                    .invoke(engine, child))) {
                        modulesAlive++;
                    }
                }
            }
            float crCur = ((Number) shipClass.getMethod("getCurrentCR").invoke(ship)).floatValue();
            float crDep =
                    ((Number) shipClass.getMethod("getCRAtDeployment").invoke(ship)).floatValue();
            List<?> bays = (List<?>) shipClass.getMethod("getLaunchBays").invoke(ship);
            Class<?> bayApiClass =
                    Class.forName("com.fs.starfarer.api.combat.FighterLaunchBayAPI", false, loader);
            Method getWing = bayApiClass.getMethod("getWing");
            int baysWithWing = 0;
            for (Object bay : bays) {
                if (getWing.invoke(bay) != null) {
                    baysWithWing++;
                }
            }
            Object variant = shipClass.getMethod("getVariant").invoke(ship);
            Object variantWings = variant.getClass().getMethod("getWings").invoke(variant);
            // Two-call identity probe on the RAW owner's manager (javadoc above).
            Method getFleetManager = engineClass.getMethod("getFleetManager", int.class);
            boolean mgrAttached = getFleetManager.invoke(engine, owner)
                    == getFleetManager.invoke(engine, owner);
            Object member = shipClass.getMethod("getFleetMember").invoke(ship);
            Object side1Manager = getFleetManager.invoke(engine, 1);
            List<?> side1Deployed = (List<?>)
                    side1Manager.getClass().getMethod("getDeployedCopy").invoke(side1Manager);
            return "in_ships=true alive=" + alive
                    + " owner=" + owner
                    + " orig_owner=" + origOwner
                    + " travel=" + travel
                    + " locked=" + locked
                    + " deployed_s=" + deployedS
                    + " vel=" + velLen
                    + " loc=" + locX + "," + locY
                    + " facing=" + facing
                    + " modules=" + modules + " modules_alive=" + modulesAlive
                    + " cr_cur=" + crCur
                    + " cr_dep=" + crDep
                    + " bays=" + bays.size()
                    + " bay_wings=" + baysWithWing + "/" + bays.size()
                    + " variant_wings=" + variantWings
                    + " mgr_attached=" + mgrAttached
                    + " in_side1_ledger=" + (member != null && side1Deployed.contains(member));
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            return "sample_error=" + cause.getClass().getName()
                    + ":" + (cause.getMessage() == null ? "" : cause.getMessage());
        }
    }

    /**
     * Build the descriptor-driven deploy specs: {@code seats} (already seatId-sorted,
     * wire ids position-asserted by the caller, ALWAYS the full list) first — each consuming its
     * side's SEAT-row capacity in roster declaration order — then AI rows in declaration order. Per
     * side, FULL-layout deploy ordinal j takes the {@link DescriptorLayout#formationPosition} slot; facing is 90
     * (side 0) / 270 (side 1) — the vanilla away-from-entry-edge values on the engine's own
     * home-edge (Y) axis, still face-each-other (the old 0/180 X-axis
     * layout was what every seat's t=0 world adoption snapped SIDEWAYS to). With
     * {@code occupiedOrNull} set, unoccupied seats still CONSUME their FULL-layout
     * ordinal but emit no spec — a seat's variant/slot/name and every AI row's slot are
     * occupancy-independent by construction. Fail-closed: seats-per-side must equal that side's
     * SEAT-row capacity, and every row variant must resolve (occupied or not).
     *
     * <p>A deployed seat whose id is in
     * {@code overrideBySeat} fields that pick (override ?? descriptor — a pick that does not
     * resolve, or names a station, falls back to the descriptor row with one line and counts
     * {@link #overrideFallbackCount()}); each deployed seat's fielded id is recorded for
     * {@link #seatDeployedVariantId(int)}, and the seat spec carries fielded + descriptor ids so
     * the officer attach takes the seat lookup law. Placement/name stay ordinal-driven.
     */
    private static List<HeadlessCombatContext.EngineDeployShipSpec> buildDescriptorSpecs(
            MatchDescriptor descriptor, List<MatchDescriptor.Seat> seats, SortedSet<Integer> occupiedOrNull,
            Map<Integer, String> overrideBySeat,
            ClassLoader loader, Class<?> hullVariantClass, Constructor<?> vec2Ctor) throws Exception {
        // Resolve each distinct variant once, fail-closed. Hoisted ABOVE the capacity fold
        // because the STATION validation below needs the ENGINE's own
        // detection seam — ShipVariantAPI.isStation() (reads the
        // hull-spec STATION hint). There is no
        // FleetMemberType.STATION: isStation reads the VARIANT, and the member still mints as
        // FleetMemberType.SHIP.
        Map<String, Object> variantById = new LinkedHashMap<>();
        for (MatchDescriptor.RosterRow row : descriptor.roster()) {
            if (!variantById.containsKey(row.variantId())) {
                variantById.put(row.variantId(),
                        resolveVariantById(loader, hullVariantClass, row.variantId()));
            }
        }
        Method variantIsStation = hullVariantClass.getMethod("isStation");
        Set<String> stationVariantIds = new LinkedHashSet<>();
        for (Map.Entry<String, Object> entry : variantById.entrySet()) {
            Object engineSaysStation = variantIsStation.invoke(entry.getValue());
            boolean mintSaysStation = MatchDescriptors.isStationVariantId(entry.getKey());
            if (Boolean.TRUE.equals(engineSaysStation) != mintSaysStation) {
                // The mint-side (game-free) predicate the serve loop keys its station lane on
                // and the engine's own variant seam MUST agree, or the serve entry's module
                // bound term and this deploy lane are describing different rosters.
                throw new RealEngineDemoRosterException(
                        "station detection disagreement for variant '" + entry.getKey()
                                + "': engine ShipVariantAPI.isStation()=" + engineSaysStation
                                + " but MatchDescriptors.isStationVariantId()=" + mintSaysStation
                                + " — the serve entry's module bound term is keyed on the mint"
                                + " predicate, so a divergence would deploy an unbounded roster");
            }
            if (mintSaysStation) {
                stationVariantIds.add(entry.getKey());
            }
        }
        // A STATION row is LAST + AI +
        // count 1 + SIDE 0, or the roster does not deploy. LAST is the client id-contract law
        // (wire ids 1..N in roster-row-flattened DECLARATION order — a station anywhere else
        // pushes every later row's declared id off its hull, and the engine-minted modules must
        // intern ABOVE the descriptor band; the flattened order is side-blind, so LAST and side
        // 0 are independent constraints). AI because no seat can pilot a station. count 1
        // because the engine's own station lane (the fleet manager's deployStationAndRelatedIfNeeded)
        // fields ONE per side. SIDE 0 because the mode is DEFEND: the
        // fortress is allied with the seats and the waves attack it — a side-1 station is the
        // inverted SIEGE this check exists to refuse.
        List<MatchDescriptor.RosterRow> rosterRows = descriptor.roster();
        for (int i = 0; i < rosterRows.size(); i++) {
            MatchDescriptor.RosterRow row = rosterRows.get(i);
            if (!stationVariantIds.contains(row.variantId())) {
                continue;
            }
            if (i != rosterRows.size() - 1) {
                throw new RealEngineDemoRosterException(
                        "STATION roster row '" + row.variantId() + "' is at declaration index "
                                + i + " of " + rosterRows.size() + " — a station row must be"
                                + " LAST (the client id-contract law interns wire ids 1..N in"
                                + " roster-row-flattened declaration order)");
            }
            if (row.pilot() != MatchDescriptor.PilotKind.AI) {
                throw new RealEngineDemoRosterException(
                        "STATION roster row '" + row.variantId() + "' has pilot " + row.pilot()
                                + " — a station is AI-only (no seat pilots a station)");
            }
            if (row.count() != 1) {
                throw new RealEngineDemoRosterException(
                        "STATION roster row '" + row.variantId() + "' has count " + row.count()
                                + " — exactly one station per descriptor (the engine's own"
                                + " station lane fields one per side)");
            }
            if (row.side() != 0) {
                throw new RealEngineDemoRosterException(
                        "STATION roster row '" + row.variantId() + "' is on side " + row.side()
                                + " — DEFEND fields the fortress ALLIED on side 0;"
                                + " a wave-side station is the inverted SIEGE"
                                + " (waves can never attack it and it fires on the seats)");
            }
        }
        int[] seatCapacity = new int[2];
        int[] sideTotal = new int[2];
        for (MatchDescriptor.RosterRow row : descriptor.roster()) {
            if (stationVariantIds.contains(row.variantId())) {
                // The station takes NO formation slot — it pins at (0,0) (map
                // center, the vanilla station position) and must not widen the AI rows' row
                // spacing, which is derived from sideTotal.
                continue;
            }
            sideTotal[row.side()] += row.count();
            if (row.pilot() == MatchDescriptor.PilotKind.SEAT) {
                seatCapacity[row.side()] += row.count();
            }
        }
        int[] seatsPerSide = new int[2];
        for (MatchDescriptor.Seat seat : seats) {
            seatsPerSide[seat.side()]++;
        }
        for (int side = 0; side < 2; side++) {
            if (seatsPerSide[side] != seatCapacity[side]) {
                throw new RealEngineDemoRosterException(
                        "descriptor side " + side + " has " + seatsPerSide[side]
                                + " seats but SEAT-row capacity " + seatCapacity[side]
                                + " — every SEAT-row slot must be held by exactly one seat");
            }
        }
        // Per-side SEAT variant slots in roster declaration order (what each seat ordinal consumes).
        List<List<String>> seatVariantBySide = DescriptorLayout.seatVariantSlotsBySide(descriptor.roster());
        List<HeadlessCombatContext.EngineDeployShipSpec> specs = new ArrayList<>();
        int[] fullOrdinals = DescriptorLayout.fullLayoutSideOrdinals(seats);
        int[] sideOrdinal = new int[2];
        for (int i = 0; i < seats.size(); i++) {
            MatchDescriptor.Seat seat = seats.get(i);
            int side = seat.side();
            int j = fullOrdinals[i];
            // Count EVERY seat (occupied or not) so the AI rows below start after the FULL seat
            // layout — AI slots must not shift when occupancy changes.
            sideOrdinal[side]++;
            if (occupiedOrNull != null && !occupiedOrNull.contains(seat.seatId())) {
                continue;
            }
            String descriptorVariantId = seatVariantBySide.get(side).get(j);
            String variantId = descriptorVariantId;
            Object variant = variantById.get(descriptorVariantId);
            // The seat's carried pick (override ??
            // descriptor), resolved through the engine registry exactly as midDeploySeat does.
            // Through the ONE pick law.
            String overrideOrNull = overrideBySeat.get(seat.seatId());
            PickResolution pick = resolveSeatPick(overrideOrNull, descriptorVariantId,
                    id -> resolveVariantById(loader, hullVariantClass, id),
                    (v, id) -> Boolean.TRUE.equals(variantIsStation.invoke(v))
                            || MatchDescriptors.isStationVariantId(id));
            if (pick.pickVariantOrNull() != null) {
                variant = pick.pickVariantOrNull();
                variantId = pick.variantId();
            }
            if (pick.override()) {
                overrideCount++;
            } else if (pick.fallbackReasonOrNull() != null) {
                overrideFallbackCount++;
                System.out.println("real-engine-demo-roster deployMatchRoster seat="
                        + seat.seatId() + " override_unresolvable id=" + overrideOrNull
                        + " fallback=" + descriptorVariantId + " reason="
                        + pick.fallbackReasonOrNull());
            }
            deployedVariantBySeatId.put(seat.seatId(), variantId);
            if (specs.isEmpty()) {
                deployedVariantId = variantId; // diagnostics: the first deployed seat's variant
            }
            float[] pos = DescriptorLayout.formationPosition(side, j, sideTotal[side]);
            // SEAT rows carry the seat marker — the deploy lane keys the
            // all-skills-vs-curated captain exclusion on it (guard (e)).
            specs.add(new HeadlessCombatContext.EngineDeployShipSpec(
                    side, variant, DescriptorLayout.seatShipName(seat.seatId()),
                    vec2Ctor.newInstance(pos[0], pos[1]), side == 0 ? 90.0f : 270.0f, 0.0f,
                    /*station*/ false, /*seat*/ true, variantId, descriptorVariantId));
        }
        int[] aiOrdinal = new int[2];
        for (MatchDescriptor.RosterRow row : descriptor.roster()) {
            if (row.pilot() != MatchDescriptor.PilotKind.AI) {
                continue;
            }
            int side = row.side();
            if (stationVariantIds.contains(row.variantId())) {
                // The station pins at (0,0) — MAP CENTER, exactly where
                // vanilla's own deployStationAndRelatedIfNeeded puts it — with
                // facing 0 and burn 0 (burn is dead input for a station: the fleet manager's isStation
                // short-circuit and its travel-drive gate both read false). It consumes
                // NO formation ordinal, so the side's other rows keep their grid slots.
                // Validated LAST/AI/count-1 above, so this is the final spec in deploy order —
                // which is what keeps the flattened id contract == deploy-ledger order.
                specs.add(new HeadlessCombatContext.EngineDeployShipSpec(
                        side, variantById.get(row.variantId()),
                        "AlphaCoreMP Station " + side + "." + (++aiOrdinal[side]), // W11 public name
                        vec2Ctor.newInstance(0.0f, 0.0f), 0.0f, 0.0f, /*station*/ true));
                continue;
            }
            for (int k = 0; k < row.count(); k++) {
                int j = sideOrdinal[side]++;
                float[] pos = DescriptorLayout.formationPosition(side, j, sideTotal[side]);
                specs.add(new HeadlessCombatContext.EngineDeployShipSpec(
                        side, variantById.get(row.variantId()),
                        "AlphaCoreMP AI " + side + "." + (++aiOrdinal[side]), // W11 public name
                        vec2Ctor.newInstance(pos[0], pos[1]), side == 0 ? 90.0f : 270.0f, 0.0f));
            }
        }
        return specs;
    }

    /**
     * Every side the descriptor DECLARES (exactly two by construction), side-index order — NOT
     * the sides observed in roster rows: the fortress
     * shape's side 1 is wave-only (zero t=0 roster rows), and a side missing from the addFleet
     * register list has no engine-attached manager — {@code getFleetManager(side)} mints a fresh
     * DETACHED one per call (it never returns null), so wave hulls spawn and fight but their
     * bays never launch a wing. Registering a declared zero-hull side is exactly the S3 N-boot
     * mechanism; on every pre-fortress shape both sides carry rows, so this is byte-identical
     * there.
     */
    private static int[] descriptorSides(MatchDescriptor descriptor) {
        int[] out = new int[descriptor.sides().size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = i;
        }
        return out;
    }

    /**
     * Resolve the demo {@code HullVariantSpec} by id from the engine's variant registry. Fail-closed
     * (no silent substitution): if the configured id does not resolve, throw with the registry size +
     * which candidate ids are present so the caller's launch records the content-loading boundary
     * (e.g. an empty variant registry means ship/variant DATA was not loaded headless, distinct from
     * the cc_client variant not being registered).
     */
    private static Object resolveDemoVariant(ClassLoader loader, Class<?> hullVariantClass) throws Exception {
        // M3-A: the ServerConfig cascade (sysprop > env > file > compiled),
        // so the registered cc.server.demoVariant row resolves the same tiers the posture dump
        // prints. Deliberate flip vs the old getProperty(prop, default) read: an explicit-EMPTY
        // -Dcc.server.demoVariant= used to reach resolveVariantById("") and crash; blank now
        // counts as absent and falls to the default, like every other knob.
        String variantId = ServerConfig.resolve(DEMO_VARIANT_PROP, "CC_SERVER_DEMO_VARIANT",
                "server.demoVariant", DEFAULT_DEMO_VARIANT_ID);
        Object variant = resolveVariantById(loader, hullVariantClass, variantId);
        deployedVariantId = variantId;
        return variant;
    }

    /**
     * Resolve any {@code HullVariantSpec} by id from the engine's variant registry — the
     * {@link #resolveDemoVariant} lane generalized to the descriptor's per-row variant ids
     * (e.g. the gauntlet's {@code onslaught_xiv_Elite} owner row). Same fail-closed
     * diagnostics; {@code -Dcc.server.demoVariant} deliberately does NOT override descriptor rows —
     * the descriptor is the authored truth on that path. Package-private for
     * {@link EngineBornGrowthSeam#capacityFor} (same resolve, same registry).
     */
    /**
     * Loader-defaulted {@link #resolveVariantById(ClassLoader, Class, String)} — the same
     * fail-closed registry lookup for callers that hold no engine class handles of their own
     * (the fortress boot checks). The engine-internal registry identity stays named ONCE,
     * here.
     */
    static Object resolveVariantById(String variantId) throws Exception {
        ClassLoader loader = RealEngineDemoRoster.class.getClassLoader();
        return resolveVariantById(
                loader, Class.forName(HULL_VARIANT_SPEC_CLASS, false, loader), variantId);
    }

    /**
     * W7 B1b: does {@code variantId} resolve in the engine's variant registry? The
     * {@code PickableVariants}-injected predicate (runtime-side) — the ONE registry lookup
     * ({@link #resolveVariantById(String)}) folded to a boolean; any throw (registry missing,
     * id unknown) is simply "not pickable", never a scan abort.
     */
    public static boolean variantResolvable(String variantId) {
        try {
            resolveVariantById(variantId);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The FULL engine variant-registry keyset (every registered
     * {@code HullVariantSpec} id — hulls, npc, modules, stations, fighters), the
     * {@code /debugships} debug-catalogue source. The no-arg registry overload returns a FRESH
     * snapshot copy of an UNSYNCHRONIZED static map's keys
     * — snapshot ONCE at boot when load-time registry writes are
     * quiescent, never per-tick. Fail-soft to EMPTY with a loud line: the debug catalogue is
     * an admin nicety and must never fail a serve boot (the door replies unavailable off it).
     */
    public static List<String> variantRegistryIds() {
        try {
            ClassLoader loader = RealEngineDemoRoster.class.getClassLoader();
            Class<?> registryClass = Class.forName(VARIANT_REGISTRY_CLASS, true, loader);
            Object rawIds = registryClass.getMethod(VARIANT_REGISTRY_METHOD).invoke(null);
            List<String> ids = new ArrayList<>();
            if (rawIds instanceof Collection) {
                for (Object id : (Collection<?>) rawIds) {
                    if (id instanceof String) {
                        ids.add((String) id);
                    }
                }
            }
            return ids;
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            System.out.println("real-engine-demo-roster variant_registry_ids_failed "
                    + cause.getClass().getName() + ": "
                    + (cause.getMessage() == null ? "" : cause.getMessage()));
            return Collections.emptyList();
        }
    }

    /**
     * W7 B1b: every enabled mod's existing {@code data/variants} dir, launcher declaration
     * order — the reflective {@code ModManager} walk ({@code RealEngineContentInit}
     * {@code registerEnabledModDataDirsIntoResourceRegistry} idiom). Fail-soft to EMPTY with a
     * loud line: the {@code /ship} allowlist is a player nicety and must never fail a serve
     * boot (the caller replies "picking unavailable" off an empty scan).
     */
    public static List<java.io.File> enabledModVariantRoots() {
        List<java.io.File> roots = new ArrayList<>();
        try {
            ClassLoader loader = RealEngineDemoRoster.class.getClassLoader();
            Class<?> modManagerClass =
                    Class.forName("com.fs.starfarer.launcher.ModManager", true, loader);
            Object modManager = modManagerClass.getMethod("getInstance").invoke(null);
            List<?> enabledMods =
                    (List<?>) modManagerClass.getMethod("getEnabledMods").invoke(modManager);
            for (Object spec : enabledMods) {
                String modPath = (String) spec.getClass().getMethod("getPath").invoke(spec);
                java.io.File root = new java.io.File(modPath, "data/variants");
                if (root.isDirectory()) {
                    roots.add(root);
                }
            }
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            System.out.println("real-engine-demo-roster enabled_mod_variant_roots_failed "
                    + cause.getClass().getName() + ": "
                    + (cause.getMessage() == null ? "" : cause.getMessage()));
            return Collections.emptyList();
        }
        return roots;
    }

    static Object resolveVariantById(ClassLoader loader, Class<?> hullVariantClass,
            String variantId) throws Exception {
        Class<?> registryClass = Class.forName(VARIANT_REGISTRY_CLASS, true, loader);
        Method lookup = registryClass.getMethod(VARIANT_REGISTRY_METHOD, String.class);
        Object variant = lookup.invoke(null, variantId);
        if (variant != null && hullVariantClass.isInstance(variant)) {
            return variant;
        }
        // Fail closed with the diagnostics that point at the actual boundary.
        int registrySize = -1;
        try {
            Object rawIds = registryClass.getMethod(VARIANT_REGISTRY_METHOD).invoke(null);
            if (rawIds instanceof Collection) {
                registrySize = ((Collection<?>) rawIds).size();
            }
        } catch (Throwable ignore) {
            // registrySize stays -1
        }
        boolean stockPresent = isResolvable(lookup, hullVariantClass, STOCK_DEMO_VARIANT_ID);
        boolean clientPresent = isResolvable(lookup, hullVariantClass, CLIENT_DEMO_VARIANT_ID);
        throw new RealEngineDemoRosterException(
                "demo variant '" + variantId + "' did not resolve to a HullVariantSpec from the variant"
                        + " registry (size=" + registrySize + ", stock '" + STOCK_DEMO_VARIANT_ID
                        + "' present=" + stockPresent + ", client '" + CLIENT_DEMO_VARIANT_ID
                        + "' present=" + clientPresent + "). stock present + client absent => the cc_client"
                        + " mod DATA dir was not on the resource path (RealEngineContentInit mod-dir"
                        + " registration); a size<=0 registry means ship/variant DATA was not loaded"
                        + " headless at all (a content-init boundary beyond the texture loader). Override"
                        + " the id with -D" + DEMO_VARIANT_PROP + "=" + STOCK_DEMO_VARIANT_ID + " for stock.");
    }

    private static boolean isResolvable(Method lookup, Class<?> hullVariantClass, String id) {
        try {
            Object v = lookup.invoke(null, id);
            return v != null && hullVariantClass.isInstance(v);
        } catch (Throwable ignore) {
            return false;
        }
    }

    /** First public method on {@code owner} named {@code name} matching {@code test}, or throw. */
    private static Method findMethod(Class<?> owner, String name,
            java.util.function.Predicate<Method> test, String description) throws NoSuchMethodException {
        for (Method m : owner.getMethods()) {
            if (m.getName().equals(name) && test.test(m)) {
                return m;
            }
        }
        throw new NoSuchMethodException("no public " + description + " on " + owner.getName());
    }

    static Throwable unwrap(Throwable t) {
        return (t instanceof InvocationTargetException && t.getCause() != null) ? t.getCause() : t;
    }
}
