package cc.starsector.client;

import cc.starsector.client.net.SnapshotChunkMerger;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.FluxTrackerAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.StatBonus;
import com.fs.starfarer.api.combat.WeaponAPI;
import org.lwjgl.util.vector.Vector2f;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The parity-audit walker, CLIENT half.
 *
 * <p><b>HAND-MAINTAINED in lockstep with the server half</b>: the original
 * generator script (seeded from the server's once-per-JVM
 * {@code parity_walk_inventory} event) last matched this file 2026-07-08 and was deleted after
 * five hand-edits had made the .java the truth (deferred-refactors row "STALE FICTION", fired here;
 * git history keeps the script). Rule: every server walk-rule change ({@code captureInterface} type
 * branches, {@code selectPrimaryWeapon}, {@code MODS_ATTRIBUTED}) lands in BOTH halves in the same
 * commit; the field list per interface = every zero-arg public {@code get*}/{@code is*} getter of a
 * walkable return type (scalar, Vector2f, MutableStat, StatBonus), sorted by name.
 *
 * <p>Mirror of {@code cc.starsector.server.runtime.ParityAuditWalker} under the in-game constraints:
 * the combat plugin runs under script classloader-B ({@code java.lang.reflect.*} AND file I/O denied
 * to scripts), so every getter here is a DIRECT compiled public-API call and the emit channel is
 * {@link PilotWatchLog} (log4j to {@code starsector.log} - the parity differ
 * scans each line for the embedded JSON object). Field keys + value rendering are byte-compatible
 * with the server walker; the differ's asymmetric-fields report is the runtime lockstep guard.
 *
 * <p>Called by {@link PredictReconcileCombatPlugin#advance} on a fresh authoritative snapshot edge
 * BEFORE the reconcile calls (predicted state vs authority; the pre-registered tolerances absorb
 * in-window motion + latency skew), at ticks where {@code simTick % walkEvery == 0} - the server
 * walks at {@code frame % walkEvery == 0} with the same N, so walk ticks join in the differ.
 * Config: {@code -Dcc.parity.walkEvery} then {@code CC_PARITY_WALK_EVERY}, 0 = OFF (default - the
 * proven WATCH lanes are untouched when off). Read in static init: this class is force-loaded at
 * application load ({@link CcClientModPlugin#onApplicationLoad}) so config + any loader-B denial
 * (a NAME-based predicate, not a class-loading gate) surface at load, not mid-combat.
 * Fail-soft everywhere; hard per-round +
 * lifetime line budgets (re-armed on round restart, loud on exhaustion).
 */
final class ClientParityWalker {

    /** Walk cadence in sim ticks; 0 = off. Resolved once at app load (sysprop, then env). */
    static final int WALK_EVERY = resolveWalkEvery();
    /**
     * Two-window keying: with two rendered windows sharing ONE starsector.log, tick T
     * ship:2 is emitted by BOTH (A as foe, B as own) and the differ's last-write-wins would silently
     * merge them. Seat 1 keeps the historical {@code "client"} (byte-identical stream); a seat-2+
     * window emits {@code "client<seat>"} so the differ's {@code -SideB} splits the shared
     * log into per-window streams. Seat source: {@link PilotWatchLog#SEAT}.
     */
    private static final String SIDE_JSON =
            PilotWatchLog.SEAT == 1L ? "\"client\"" : "\"client" + PilotWatchLog.SEAT + "\"";
    /**
     * Per-ROUND budget on walk ticks (3 lines each), re-armed when the authoritative simTick
     * DECREASES (round restart - the differ assigns round indices by the same rule), plus a
     * lifetime flood ceiling. Root cause: this was a single never-reset per-JVM budget,
     * so a multi-round run exhausted it silently mid-run (window-1 died at exactly
     * walk tick 240, r2 t3480; rounds 3-5 unwalked) - and the death was silent. Exhaustion of
     * either budget now logs LOUDLY once, so a walker gap can never masquerade as desync again.
     */
    /** 240 capped a full round's coverage at t7170 (240 buckets × walkEvery
     *  30) — one run's seat-2 walker died there, leaving ZERO pose rows at the hit-edge
     *  crossings (t10800+). 720 covers a 21600-tick round end-to-end at the render cadence;
     *  log-only surface, so the cost is 3 log lines per walked bucket (bounded by the round + lifetime budgets). */
    /** 720 exhausted at t21600 — one run's transfer transits
     *  (t28230–32304) ran unwalked (W3-E unmeasured). 1200 covers 36000t at walkEvery 30, and
     *  the budgets are env-promoted so the next arm raises them without a rebuild. The budgets are
     *  hand-maintained here (the generator was deleted — see the class header). */
    private static final int MAX_WALK_TICKS_PER_ROUND =
            resolveBudget("cc.parity.roundBudget", "CC_PARITY_ROUND_BUDGET", 1200);
    /** Lifetime cap across all rounds (flood fail-safe), clamped ≥ the round budget so a raised
     *  round budget alone can never silently starve a round mid-run. */
    private static final int MAX_WALK_TICKS_TOTAL = Math.max(
            resolveBudget("cc.parity.lifetimeBudget", "CC_PARITY_LIFETIME_BUDGET", 7200),
            MAX_WALK_TICKS_PER_ROUND);
    private static int roundWalkTicks;
    private static int totalWalkTicks;
    private static long lastSeenTick = Long.MIN_VALUE;
    /** due-tick BUCKET rule: last {@code tick / WALK_EVERY} bucket walked. The old
     *  {@code tick % WALK_EVERY == 0} gate under-resolved 2× whenever the exact multiple was never
     *  sampled fresh (the caller is fresh-snapshot-gated); walking the FIRST fresh tick of each new
     *  bucket walks every bucket AND still lands exactly on the multiple whenever it IS sampled
     *  fresh — so the differ's exact-(round,tick) joins are unchanged, resolution strictly better. */
    private static long lastWalkBucket = Long.MIN_VALUE;
    private static boolean activeLogged;
    private static boolean roundBudgetLogged;
    private static boolean totalBudgetLogged;
    /** The CURRENT subject ids — re-read on every ON call of
     *  {@link #maybeWalkTick} (the (43)(c) foe re-bind is resolved fresh by the caller each fresh
     *  snapshot); -1 = none (never called, unresolved pose, or the LIFETIME budget spent = walker OFF).
     *  A spent PER-ROUND budget keeps the subjects (no walk rows print, the reserve keeps the per-hit Σ
     *  for them). */
    private static long subjectOwnId = -1L;
    private static long subjectFoeId = -1L;

    private ClientParityWalker() { }

    /** One-line config description for the app-load force-load log line. */
    static String describeConfig() {
        return "walkEvery=" + WALK_EVERY + (WALK_EVERY > 0 ? " (ON)" : " (off)");
    }

    static boolean enabled() {
        return WALK_EVERY > 0;
    }

    /** Whether {@code entityId} (a wire hull id, &gt; 0) is one of
     *  the walker's CURRENT subjects (own / (43)(c) foe) — the read DamageWitness's per-victim
     *  {@code dmg-apply} reserve keys on. Always false while the walker is OFF ({@code WALK_EVERY == 0},
     *  or its lifetime budget spent), so the reserve is inert there. Render thread (the caller's). */
    static boolean isWalkSubject(long entityId) {
        return WALK_EVERY > 0 && entityId > 0L && (entityId == subjectOwnId || entityId == subjectFoeId);
    }

    /**
     * Walk the engine + both keyed ships at one authoritative snapshot tick (the client's LOCAL predicted
     * state, keyed by the server's simTick + AWSS entityIds) and emit one parity_walk line per subject.
     * No-op unless enabled, on-cadence, and within budget. Never throws.
     *
     * <p>Snapshot ages: {@code ownAges}/{@code foeAges} are the chunk merger's stored
     * per-tier ticks for this walk's snapshot tick (tick-checked by the caller; NULLABLE — null means
     * unmeasured: eventReplay OFF, untracked id, or a benign torn read) and become the ship subject's
     * top-level {@code "age"} object, consumed by the parity differ's staleness rule.
     *
     * <p>Under-fire tag: {@code ownUnderFire} = the plugin's hull-hp under-fire latch at this
     * walk tick, appended to the OWN-ship line as a top-level {@code "uf"} property (the additive-
     * tolerant "age" precedent — name-keyed JSON, so the differ needs no parser change; it lets a
     * vel-gap walk row be attributed to being-shot vs coasting).
     *
     * <p>Returns whether this call actually WALKED (paid the getter burst) — even
     * when the walk then threw, the frame paid — so the caller's frame ledger can tag walk frames
     * (the walker-load decision instrument, 1293). Gate/budget refusals return false.
     */
    static boolean maybeWalkTick(long tick, CombatEngineAPI engine, long ownId, ShipAPI own, long foeId,
            ShipAPI foe, SnapshotChunkMerger.TierTicks ownAges, SnapshotChunkMerger.TierTicks foeAges,
            boolean ownUnderFire, float foeHullRaw, float foeHullUnc, float foeHullWire) {
        boolean walked = false;
        try {
            if (WALK_EVERY <= 0) {
                return false;
            }
            if (tick < lastSeenTick) {
                // authoritative simTick reset = round restart: re-arm the per-round budget
                if (roundWalkTicks >= MAX_WALK_TICKS_PER_ROUND) {
                    PilotWatchLog.info("parity walker round budget RE-ARMED on round restart"
                            + " (prior round hit the " + MAX_WALK_TICKS_PER_ROUND + "-walk cap)");
                }
                roundWalkTicks = 0;
                roundBudgetLogged = false;
                lastWalkBucket = Long.MIN_VALUE; // buckets are round-local (ticks restart small)
            }
            lastSeenTick = tick;
            // Record the current subjects BEFORE the cadence
            // gate (most calls return below); a spent lifetime budget reads as no subject.
            boolean live = totalWalkTicks < MAX_WALK_TICKS_TOTAL;
            subjectOwnId = live ? ownId : -1L;
            subjectFoeId = live ? foeId : -1L;
            // BUCKET rule (see lastWalkBucket): walk the first fresh tick of a new
            // WALK_EVERY bucket instead of requiring the exact multiple to be sampled fresh.
            long bucket = tick / WALK_EVERY;
            if (bucket <= lastWalkBucket) {
                return false;
            }
            if (totalWalkTicks >= MAX_WALK_TICKS_TOTAL) {
                if (!totalBudgetLogged) {
                    totalBudgetLogged = true;
                    PilotWatchLog.info("parity walker LIFETIME budget EXHAUSTED at simTick=" + tick
                            + " (" + MAX_WALK_TICKS_TOTAL + " walk ticks); walker OFF for the rest of the run");
                }
                return false;
            }
            if (roundWalkTicks >= MAX_WALK_TICKS_PER_ROUND) {
                if (!roundBudgetLogged) {
                    roundBudgetLogged = true;
                    PilotWatchLog.info("parity walker round budget EXHAUSTED at simTick=" + tick
                            + " (" + MAX_WALK_TICKS_PER_ROUND + " walk ticks this round); suppressed until round restart");
                }
                return false;
            }
            lastWalkBucket = bucket;
            roundWalkTicks++;
            totalWalkTicks++;
            walked = true;
            if (!activeLogged) {
                activeLogged = true;
                PilotWatchLog.info("parity walker ACTIVE: first walk at simTick=" + tick
                        + " walkEvery=" + WALK_EVERY + " roundBudget=" + MAX_WALK_TICKS_PER_ROUND
                        + " lifetimeBudget=" + MAX_WALK_TICKS_TOTAL);
            }
            if (engine != null) {
                emit(tick, "engine", "engine", walkEngine(engine), null, null);
            }
            if (own != null) {
                emit(tick, "ship", Long.toString(ownId), walkShip(own), ownAges, ownUnderFire);
            }
            if (foe != null) {
                Map<String, String> foeFields = walkShip(foe);
                // The foe hull triplet — raw local-applied ledger, its uncapped
                // variant, and the wire baseline (name-keyed additive fields, the "age"/"uf"
                // precedent: absent on old recordings and non-ledgered subjects; parity-diff
                // re-points its hull rule at parse time when hullRaw is present).
                if (!Float.isNaN(foeHullRaw)) {
                    foeFields.put("ship.hullRaw", num(foeHullRaw));
                }
                if (!Float.isNaN(foeHullUnc)) {
                    foeFields.put("ship.hullUnc", num(foeHullUnc));
                }
                if (!Float.isNaN(foeHullWire)) {
                    foeFields.put("ship.hullWire", num(foeHullWire));
                }
                emit(tick, "ship", Long.toString(foeId), foeFields, foeAges, null);
            }
        } catch (Throwable ignore) {
            // the instrument must never break the combat plugin
            // 1290 (81) R4: a throwing walk emits ZERO rows — the gap masquerades as no-desync.
            FallbackAnnounce.announceOnce("r4-parity-walk-threw", "walk threw "
                    + ignore.getClass().getSimpleName() + " -> no parity rows this frame");
        }
        return walked;
    }

    private static Map<String, String> walkEngine(CombatEngineAPI engine) {
        Map<String, String> fields = new TreeMap<>();
        try { fields.put("engine.getBackgroundSpriteName", txt(engine.getBackgroundSpriteName())); } catch (Throwable t) { fields.put("engine.getBackgroundSpriteName", err(t)); }
        try { fields.put("engine.getCombatNotOverFor", num(engine.getCombatNotOverFor())); } catch (Throwable t) { fields.put("engine.getCombatNotOverFor", err(t)); }
        try { fields.put("engine.getCustomExitButtonConfirmString", txt(engine.getCustomExitButtonConfirmString())); } catch (Throwable t) { fields.put("engine.getCustomExitButtonConfirmString", err(t)); }
        try { fields.put("engine.getCustomExitButtonTitle", txt(engine.getCustomExitButtonTitle())); } catch (Throwable t) { fields.put("engine.getCustomExitButtonTitle", err(t)); }
        try { fields.put("engine.getElapsedInContactWithEnemy", num(engine.getElapsedInContactWithEnemy())); } catch (Throwable t) { fields.put("engine.getElapsedInContactWithEnemy", err(t)); }
        try { fields.put("engine.getElapsedInLastFrame", num(engine.getElapsedInLastFrame())); } catch (Throwable t) { fields.put("engine.getElapsedInLastFrame", err(t)); }
        try { fields.put("engine.getMapHeight", num(engine.getMapHeight())); } catch (Throwable t) { fields.put("engine.getMapHeight", err(t)); }
        try { fields.put("engine.getMapWidth", num(engine.getMapWidth())); } catch (Throwable t) { fields.put("engine.getMapWidth", err(t)); }
        try { fields.put("engine.getMissionId", txt(engine.getMissionId())); } catch (Throwable t) { fields.put("engine.getMissionId", err(t)); }
        try { putStat(fields, "engine.getTimeMult", engine.getTimeMult()); } catch (Throwable t) { fields.put("engine.getTimeMult.mod", err(t)); }
        try { fields.put("engine.getWinningSideId", String.valueOf(engine.getWinningSideId())); } catch (Throwable t) { fields.put("engine.getWinningSideId", err(t)); }
        try { fields.put("engine.isBackgroundGlowColorNonAdditive", String.valueOf(engine.isBackgroundGlowColorNonAdditive())); } catch (Throwable t) { fields.put("engine.isBackgroundGlowColorNonAdditive", err(t)); }
        try { fields.put("engine.isCombatOver", String.valueOf(engine.isCombatOver())); } catch (Throwable t) { fields.put("engine.isCombatOver", err(t)); }
        try { fields.put("engine.isEnemyInFullRetreat", String.valueOf(engine.isEnemyInFullRetreat())); } catch (Throwable t) { fields.put("engine.isEnemyInFullRetreat", err(t)); }
        try { fields.put("engine.isFleetsInContact", String.valueOf(engine.isFleetsInContact())); } catch (Throwable t) { fields.put("engine.isFleetsInContact", err(t)); }
        try { fields.put("engine.isInCampaign", String.valueOf(engine.isInCampaign())); } catch (Throwable t) { fields.put("engine.isInCampaign", err(t)); }
        try { fields.put("engine.isInCampaignSim", String.valueOf(engine.isInCampaignSim())); } catch (Throwable t) { fields.put("engine.isInCampaignSim", err(t)); }
        try { fields.put("engine.isInFastTimeAdvance", String.valueOf(engine.isInFastTimeAdvance())); } catch (Throwable t) { fields.put("engine.isInFastTimeAdvance", err(t)); }
        try { fields.put("engine.isInMissionSim", String.valueOf(engine.isInMissionSim())); } catch (Throwable t) { fields.put("engine.isInMissionSim", err(t)); }
        try { fields.put("engine.isMission", String.valueOf(engine.isMission())); } catch (Throwable t) { fields.put("engine.isMission", err(t)); }
        try { fields.put("engine.isPaused", String.valueOf(engine.isPaused())); } catch (Throwable t) { fields.put("engine.isPaused", err(t)); }
        try { fields.put("engine.isRenderStarfield", String.valueOf(engine.isRenderStarfield())); } catch (Throwable t) { fields.put("engine.isRenderStarfield", err(t)); }
        try { fields.put("engine.isSimulation", String.valueOf(engine.isSimulation())); } catch (Throwable t) { fields.put("engine.isSimulation", err(t)); }
        try { fields.put("engine.isUIAutopilotOn", String.valueOf(engine.isUIAutopilotOn())); } catch (Throwable t) { fields.put("engine.isUIAutopilotOn", err(t)); }
        try { fields.put("engine.isUIShowingDialog", String.valueOf(engine.isUIShowingDialog())); } catch (Throwable t) { fields.put("engine.isUIShowingDialog", err(t)); }
        try { fields.put("engine.isUIShowingHUD", String.valueOf(engine.isUIShowingHUD())); } catch (Throwable t) { fields.put("engine.isUIShowingHUD", err(t)); }
        return fields;
    }

    private static Map<String, String> walkShip(ShipAPI ship) {
        Map<String, String> fields = new TreeMap<>();
        walkShipBase(ship, fields);
        MutableShipStatsAPI s = null;
        try { s = ship.getMutableStats(); } catch (Throwable ignore) { }
        if (s == null) { fields.put("ship.stats", "\"null\""); } else { walkStats(s, fields); }
        FluxTrackerAPI fx = null;
        try { fx = ship.getFluxTracker(); } catch (Throwable ignore) { }
        if (fx == null) { fields.put("ship.flux", "\"null\""); } else { walkFlux(fx, fields); }
        ShipSystemAPI sys = null;
        try { sys = ship.getSystem(); } catch (Throwable ignore) { }
        if (sys == null) { fields.put("ship.system", "\"null\""); } else { walkSystem(sys, fields); }
        ShipEngineControllerAPI ec = null;
        try { ec = ship.getEngineController(); } catch (Throwable ignore) { }
        if (ec == null) { fields.put("ship.ctrl", "\"null\""); } else { walkCtrl(ec, fields); }
        ShieldAPI sh = null;
        try { sh = ship.getShield(); } catch (Throwable ignore) { }
        if (sh == null) { fields.put("ship.shield", "\"null\""); } else { walkShield(sh, fields); }
        WeaponAPI w = selectPrimaryWeapon(ship); // Own/foe weapon cooldown phase
        if (w == null) { fields.put("ship.weapon", "\"null\""); } else { walkWeapon(w, fields); }
        return fields;
    }

    /**
     * Row 4 issuer instrument: one {@code order_walk} line per walk tick
     * over every alive side-0 non-fighter non-module hull (wire id, hull, player flag, the
     * task-manager order tuple + retreat flags + AI class). Called by the plugin right after a
     * walk that paid ({@link #maybeWalkTick} returned true). Never throws.
     */
    static void walkOrders(long tick, CombatEngineAPI engine, PredictReconcileCombatPlugin plugin) {
        if (engine == null) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder(512);
            sb.append("{\"event\":\"order_walk\",\"side\":").append(SIDE_JSON)
              .append(",\"tick\":").append(tick).append(",\"members\":[");
            ReplicaOrderSweep.appendOrderWalk(sb, engine, plugin);
            sb.append("]}");
            PilotWatchLog.info(sb.toString());
        } catch (Throwable ignore) {
            // instrument only
        }
    }

    /**
     * The weapon walked under {@code ship.weapon.}: the first non-decorative BALLISTIC/ENERGY weapon in
     * {@code getAllWeapons()}, else the first non-decorative weapon of any type — mirrors
     * {@code ParityAuditWalker.selectPrimaryWeapon} EXACTLY so the differ joins the same weapon on both
     * sides (the old first-non-decorative rule picked a missile on both escort hulls,
     * leaving the Escort Package ballistic/energy range lane unmeasured). A ship carries a LIST, so
     * the subject is selected here.
     */
    private static WeaponAPI selectPrimaryWeapon(ShipAPI ship) {
        try {
            List<WeaponAPI> all = ship.getAllWeapons();
            if (all == null) { return null; }
            WeaponAPI fallback = null;
            for (WeaponAPI w : all) {
                if (w == null || w.isDecorative()) { continue; }
                WeaponAPI.WeaponType type = null;
                try { type = w.getType(); } catch (Throwable ignore) { }
                if (type == WeaponAPI.WeaponType.BALLISTIC || type == WeaponAPI.WeaponType.ENERGY) { return w; }
                if (fallback == null) { fallback = w; }
            }
            return fallback;
        } catch (Throwable ignore) { }
        return null;
    }

    private static void walkShipBase(ShipAPI ship, Map<String, String> fields) {
        try { fields.put("ship.getAcceleration", num(ship.getAcceleration())); } catch (Throwable t) { fields.put("ship.getAcceleration", err(t)); }
        try { fields.put("ship.getAimAccuracy", num(ship.getAimAccuracy())); } catch (Throwable t) { fields.put("ship.getAimAccuracy", err(t)); }
        try { fields.put("ship.getAlphaMult", num(ship.getAlphaMult())); } catch (Throwable t) { fields.put("ship.getAlphaMult", err(t)); }
        try { fields.put("ship.getAngularVelocity", num(ship.getAngularVelocity())); } catch (Throwable t) { fields.put("ship.getAngularVelocity", err(t)); }
        try { fields.put("ship.getAsteroidCollisionSoundOverride", txt(ship.getAsteroidCollisionSoundOverride())); } catch (Throwable t) { fields.put("ship.getAsteroidCollisionSoundOverride", err(t)); }
        try { fields.put("ship.getBaseCriticalMalfunctionDamage", num(ship.getBaseCriticalMalfunctionDamage())); } catch (Throwable t) { fields.put("ship.getBaseCriticalMalfunctionDamage", err(t)); }
        try { fields.put("ship.getCollisionClass", txt(ship.getCollisionClass())); } catch (Throwable t) { fields.put("ship.getCollisionClass", err(t)); }
        try { fields.put("ship.getCollisionRadius", num(ship.getCollisionRadius())); } catch (Throwable t) { fields.put("ship.getCollisionRadius", err(t)); }
        try { fields.put("ship.getCombinedAlphaMult", num(ship.getCombinedAlphaMult())); } catch (Throwable t) { fields.put("ship.getCombinedAlphaMult", err(t)); }
        try { vec(fields, "ship.getCopyLocation", ship.getCopyLocation()); } catch (Throwable t) { vecErr(fields, "ship.getCopyLocation", t); }
        try { fields.put("ship.getCRAtDeployment", num(ship.getCRAtDeployment())); } catch (Throwable t) { fields.put("ship.getCRAtDeployment", err(t)); }
        try { fields.put("ship.getCurrentCR", num(ship.getCurrentCR())); } catch (Throwable t) { fields.put("ship.getCurrentCR", err(t)); }
        try { fields.put("ship.getCurrFlux", num(ship.getCurrFlux())); } catch (Throwable t) { fields.put("ship.getCurrFlux", err(t)); }
        try { fields.put("ship.getDeceleration", num(ship.getDeceleration())); } catch (Throwable t) { fields.put("ship.getDeceleration", err(t)); }
        try { fields.put("ship.getDeployCost", num(ship.getDeployCost())); } catch (Throwable t) { fields.put("ship.getDeployCost", err(t)); }
        try { fields.put("ship.getDHullOverlayAngleOffset", num(ship.getDHullOverlayAngleOffset())); } catch (Throwable t) { fields.put("ship.getDHullOverlayAngleOffset", err(t)); }
        try { fields.put("ship.getEngineFractionPermanentlyDisabled", num(ship.getEngineFractionPermanentlyDisabled())); } catch (Throwable t) { fields.put("ship.getEngineFractionPermanentlyDisabled", err(t)); }
        try { fields.put("ship.getExplosionScale", num(ship.getExplosionScale())); } catch (Throwable t) { fields.put("ship.getExplosionScale", err(t)); }
        try { vec(fields, "ship.getExplosionVelocityOverride", ship.getExplosionVelocityOverride()); } catch (Throwable t) { vecErr(fields, "ship.getExplosionVelocityOverride", t); }
        try { fields.put("ship.getExtraAlphaMult", num(ship.getExtraAlphaMult())); } catch (Throwable t) { fields.put("ship.getExtraAlphaMult", err(t)); }
        try { fields.put("ship.getExtraAlphaMult2", num(ship.getExtraAlphaMult2())); } catch (Throwable t) { fields.put("ship.getExtraAlphaMult2", err(t)); }
        try { fields.put("ship.getExtraOverlayAngleOffset", num(ship.getExtraOverlayAngleOffset())); } catch (Throwable t) { fields.put("ship.getExtraOverlayAngleOffset", err(t)); }
        try { fields.put("ship.getExtraOverlayShadowOpacity", num(ship.getExtraOverlayShadowOpacity())); } catch (Throwable t) { fields.put("ship.getExtraOverlayShadowOpacity", err(t)); }
        try { fields.put("ship.getFacing", num(ship.getFacing())); } catch (Throwable t) { fields.put("ship.getFacing", err(t)); }
        try { fields.put("ship.getFighterTimeBeforeRefit", num(ship.getFighterTimeBeforeRefit())); } catch (Throwable t) { fields.put("ship.getFighterTimeBeforeRefit", err(t)); }
        try { vec(fields, "ship.getFixedLocation", ship.getFixedLocation()); } catch (Throwable t) { vecErr(fields, "ship.getFixedLocation", t); }
        try { fields.put("ship.getFleetMemberId", txt(ship.getFleetMemberId())); } catch (Throwable t) { fields.put("ship.getFleetMemberId", err(t)); }
        try { fields.put("ship.getFluxBasedEnergyWeaponDamageMultiplier", num(ship.getFluxBasedEnergyWeaponDamageMultiplier())); } catch (Throwable t) { fields.put("ship.getFluxBasedEnergyWeaponDamageMultiplier", err(t)); }
        try { fields.put("ship.getFluxLevel", num(ship.getFluxLevel())); } catch (Throwable t) { fields.put("ship.getFluxLevel", err(t)); }
        try { fields.put("ship.getFluxVentTextureSheet", txt(ship.getFluxVentTextureSheet())); } catch (Throwable t) { fields.put("ship.getFluxVentTextureSheet", err(t)); }
        try { fields.put("ship.getForceCarrierPullBackTime", num(ship.getForceCarrierPullBackTime())); } catch (Throwable t) { fields.put("ship.getForceCarrierPullBackTime", err(t)); }
        try { fields.put("ship.getForceCarrierTargetTime", num(ship.getForceCarrierTargetTime())); } catch (Throwable t) { fields.put("ship.getForceCarrierTargetTime", err(t)); }
        try { fields.put("ship.getFullTimeDeployed", num(ship.getFullTimeDeployed())); } catch (Throwable t) { fields.put("ship.getFullTimeDeployed", err(t)); }
        try { fields.put("ship.getHardFluxLevel", num(ship.getHardFluxLevel())); } catch (Throwable t) { fields.put("ship.getHardFluxLevel", err(t)); }
        try { fields.put("ship.getHitpoints", num(ship.getHitpoints())); } catch (Throwable t) { fields.put("ship.getHitpoints", err(t)); }
        try { fields.put("ship.getHulkChanceOverride", num(ship.getHulkChanceOverride())); } catch (Throwable t) { fields.put("ship.getHulkChanceOverride", err(t)); }
        try { fields.put("ship.getHullLevel", num(ship.getHullLevel())); } catch (Throwable t) { fields.put("ship.getHullLevel", err(t)); }
        try { fields.put("ship.getHullLevelAtDeployment", num(ship.getHullLevelAtDeployment())); } catch (Throwable t) { fields.put("ship.getHullLevelAtDeployment", err(t)); }
        try { fields.put("ship.getHullSize", txt(ship.getHullSize())); } catch (Throwable t) { fields.put("ship.getHullSize", err(t)); }
        try { fields.put("ship.getHullStyleId", txt(ship.getHullStyleId())); } catch (Throwable t) { fields.put("ship.getHullStyleId", err(t)); }
        try { fields.put("ship.getId", txt(ship.getId())); } catch (Throwable t) { fields.put("ship.getId", err(t)); }
        try { fields.put("ship.getImpactVolumeMult", num(ship.getImpactVolumeMult())); } catch (Throwable t) { fields.put("ship.getImpactVolumeMult", err(t)); }
        try { fields.put("ship.getLayer", txt(ship.getLayer())); } catch (Throwable t) { fields.put("ship.getLayer", err(t)); }
        try { vec(fields, "ship.getLocation", ship.getLocation()); } catch (Throwable t) { vecErr(fields, "ship.getLocation", t); }
        try { fields.put("ship.getLowestHullLevelReached", num(ship.getLowestHullLevelReached())); } catch (Throwable t) { fields.put("ship.getLowestHullLevelReached", err(t)); }
        try { fields.put("ship.getMass", num(ship.getMass())); } catch (Throwable t) { fields.put("ship.getMass", err(t)); }
        try { fields.put("ship.getMassWithModules", num(ship.getMassWithModules())); } catch (Throwable t) { fields.put("ship.getMassWithModules", err(t)); }
        try { fields.put("ship.getMaxFlux", num(ship.getMaxFlux())); } catch (Throwable t) { fields.put("ship.getMaxFlux", err(t)); }
        try { fields.put("ship.getMaxHitpoints", num(ship.getMaxHitpoints())); } catch (Throwable t) { fields.put("ship.getMaxHitpoints", err(t)); }
        try { fields.put("ship.getMaxSpeed", num(ship.getMaxSpeed())); } catch (Throwable t) { fields.put("ship.getMaxSpeed", err(t)); }
        try { fields.put("ship.getMaxSpeedWithoutBoost", num(ship.getMaxSpeedWithoutBoost())); } catch (Throwable t) { fields.put("ship.getMaxSpeedWithoutBoost", err(t)); }
        try { fields.put("ship.getMaxTurnRate", num(ship.getMaxTurnRate())); } catch (Throwable t) { fields.put("ship.getMaxTurnRate", err(t)); }
        try { fields.put("ship.getMinFlux", num(ship.getMinFlux())); } catch (Throwable t) { fields.put("ship.getMinFlux", err(t)); }
        try { fields.put("ship.getMinFluxLevel", num(ship.getMinFluxLevel())); } catch (Throwable t) { fields.put("ship.getMinFluxLevel", err(t)); }
        try { vec(fields, "ship.getModuleOffset", ship.getModuleOffset()); } catch (Throwable t) { vecErr(fields, "ship.getModuleOffset", t); }
        try { vec(fields, "ship.getMouseTarget", ship.getMouseTarget()); } catch (Throwable t) { vecErr(fields, "ship.getMouseTarget", t); }
        try { fields.put("ship.getName", txt(ship.getName())); } catch (Throwable t) { fields.put("ship.getName", err(t)); }
        try { fields.put("ship.getNumFighterBays", String.valueOf(ship.getNumFighterBays())); } catch (Throwable t) { fields.put("ship.getNumFighterBays", err(t)); }
        try { fields.put("ship.getNumFlameouts", String.valueOf(ship.getNumFlameouts())); } catch (Throwable t) { fields.put("ship.getNumFlameouts", err(t)); }
        try { fields.put("ship.getOriginalOwner", String.valueOf(ship.getOriginalOwner())); } catch (Throwable t) { fields.put("ship.getOriginalOwner", err(t)); }
        try { fields.put("ship.getOwner", String.valueOf(ship.getOwner())); } catch (Throwable t) { fields.put("ship.getOwner", err(t)); }
        try { fields.put("ship.getParentPieceId", txt(ship.getParentPieceId())); } catch (Throwable t) { fields.put("ship.getParentPieceId", err(t)); }
        try { fields.put("ship.getPeakTimeRemaining", num(ship.getPeakTimeRemaining())); } catch (Throwable t) { fields.put("ship.getPeakTimeRemaining", err(t)); }
        try { vec(fields, "ship.getRenderOffset", ship.getRenderOffset()); } catch (Throwable t) { vecErr(fields, "ship.getRenderOffset", t); }
        try { fields.put("ship.getSharedFighterReplacementRate", num(ship.getSharedFighterReplacementRate())); } catch (Throwable t) { fields.put("ship.getSharedFighterReplacementRate", err(t)); }
        try { vec(fields, "ship.getShieldCenterEvenIfNoShield", ship.getShieldCenterEvenIfNoShield()); } catch (Throwable t) { vecErr(fields, "ship.getShieldCenterEvenIfNoShield", t); }
        try { fields.put("ship.getShieldRadiusEvenIfNoShield", num(ship.getShieldRadiusEvenIfNoShield())); } catch (Throwable t) { fields.put("ship.getShieldRadiusEvenIfNoShield", err(t)); }
        try { vec(fields, "ship.getShieldTarget", ship.getShieldTarget()); } catch (Throwable t) { vecErr(fields, "ship.getShieldTarget", t); }
        try { fields.put("ship.getShipCollisionSoundOverride", txt(ship.getShipCollisionSoundOverride())); } catch (Throwable t) { fields.put("ship.getShipCollisionSoundOverride", err(t)); }
        try { fields.put("ship.getShipExplosionRadius", num(ship.getShipExplosionRadius())); } catch (Throwable t) { fields.put("ship.getShipExplosionRadius", err(t)); }
        try { fields.put("ship.getSinceLastDamageTaken", num(ship.getSinceLastDamageTaken())); } catch (Throwable t) { fields.put("ship.getSinceLastDamageTaken", err(t)); }
        try { fields.put("ship.getTimeDeployedForCRReduction", num(ship.getTimeDeployedForCRReduction())); } catch (Throwable t) { fields.put("ship.getTimeDeployedForCRReduction", err(t)); }
        try { fields.put("ship.getTimeDeployedUnderPlayerControl", num(ship.getTimeDeployedUnderPlayerControl())); } catch (Throwable t) { fields.put("ship.getTimeDeployedUnderPlayerControl", err(t)); }
        try { fields.put("ship.getTurnAcceleration", num(ship.getTurnAcceleration())); } catch (Throwable t) { fields.put("ship.getTurnAcceleration", err(t)); }
        try { fields.put("ship.getTurnDeceleration", num(ship.getTurnDeceleration())); } catch (Throwable t) { fields.put("ship.getTurnDeceleration", err(t)); }
        try { vec(fields, "ship.getVelocity", ship.getVelocity()); } catch (Throwable t) { vecErr(fields, "ship.getVelocity", t); }
        // ship.getWingCRAtDeployment dropped: wing.getLaunchCR() NPEs for
        // EVERY non-fighter member ALWAYS — pure census noise; the server walker
        // skips it by name (ParityAuditWalker.SKIPPED_GETTERS) and parity-diff.ps1 excludes the key.
        try { fields.put("ship.isAffectedByNebula", String.valueOf(ship.isAffectedByNebula())); } catch (Throwable t) { fields.put("ship.isAffectedByNebula", err(t)); }
        try { fields.put("ship.isAlive", String.valueOf(ship.isAlive())); } catch (Throwable t) { fields.put("ship.isAlive", err(t)); }
        try { fields.put("ship.isAlly", String.valueOf(ship.isAlly())); } catch (Throwable t) { fields.put("ship.isAlly", err(t)); }
        try { fields.put("ship.isBeingIgnored", String.valueOf(ship.isBeingIgnored())); } catch (Throwable t) { fields.put("ship.isBeingIgnored", err(t)); }
        try { fields.put("ship.isCapital", String.valueOf(ship.isCapital())); } catch (Throwable t) { fields.put("ship.isCapital", err(t)); }
        try { fields.put("ship.isCruiser", String.valueOf(ship.isCruiser())); } catch (Throwable t) { fields.put("ship.isCruiser", err(t)); }
        try { fields.put("ship.isDefenseDisabled", String.valueOf(ship.isDefenseDisabled())); } catch (Throwable t) { fields.put("ship.isDefenseDisabled", err(t)); }
        try { fields.put("ship.isDestroyer", String.valueOf(ship.isDestroyer())); } catch (Throwable t) { fields.put("ship.isDestroyer", err(t)); }
        try { fields.put("ship.isDirectRetreat", String.valueOf(ship.isDirectRetreat())); } catch (Throwable t) { fields.put("ship.isDirectRetreat", err(t)); }
        try { fields.put("ship.isDoNotFlareEnginesWhenStrafingOrDecelerating", String.valueOf(ship.isDoNotFlareEnginesWhenStrafingOrDecelerating())); } catch (Throwable t) { fields.put("ship.isDoNotFlareEnginesWhenStrafingOrDecelerating", err(t)); }
        try { fields.put("ship.isDoNotRender", String.valueOf(ship.isDoNotRender())); } catch (Throwable t) { fields.put("ship.isDoNotRender", err(t)); }
        try { fields.put("ship.isDoNotRenderShield", String.valueOf(ship.isDoNotRenderShield())); } catch (Throwable t) { fields.put("ship.isDoNotRenderShield", err(t)); }
        try { fields.put("ship.isDoNotRenderSprite", String.valueOf(ship.isDoNotRenderSprite())); } catch (Throwable t) { fields.put("ship.isDoNotRenderSprite", err(t)); }
        try { fields.put("ship.isDoNotRenderVentingAnimation", String.valueOf(ship.isDoNotRenderVentingAnimation())); } catch (Throwable t) { fields.put("ship.isDoNotRenderVentingAnimation", err(t)); }
        try { fields.put("ship.isDoNotRenderWeapons", String.valueOf(ship.isDoNotRenderWeapons())); } catch (Throwable t) { fields.put("ship.isDoNotRenderWeapons", err(t)); }
        try { fields.put("ship.isDrone", String.valueOf(ship.isDrone())); } catch (Throwable t) { fields.put("ship.isDrone", err(t)); }
        try { fields.put("ship.isEngineBoostActive", String.valueOf(ship.isEngineBoostActive())); } catch (Throwable t) { fields.put("ship.isEngineBoostActive", err(t)); }
        try { fields.put("ship.isExpired", String.valueOf(ship.isExpired())); } catch (Throwable t) { fields.put("ship.isExpired", err(t)); }
        try { fields.put("ship.isExtraOverlayMatchHullColor", String.valueOf(ship.isExtraOverlayMatchHullColor())); } catch (Throwable t) { fields.put("ship.isExtraOverlayMatchHullColor", err(t)); }
        try { fields.put("ship.isFighter", String.valueOf(ship.isFighter())); } catch (Throwable t) { fields.put("ship.isFighter", err(t)); }
        try { fields.put("ship.isFinishedLanding", String.valueOf(ship.isFinishedLanding())); } catch (Throwable t) { fields.put("ship.isFinishedLanding", err(t)); }
        try { fields.put("ship.isForceHideFFOverlay", String.valueOf(ship.isForceHideFFOverlay())); } catch (Throwable t) { fields.put("ship.isForceHideFFOverlay", err(t)); }
        try { fields.put("ship.isFrigate", String.valueOf(ship.isFrigate())); } catch (Throwable t) { fields.put("ship.isFrigate", err(t)); }
        try { fields.put("ship.isHoldFire", String.valueOf(ship.isHoldFire())); } catch (Throwable t) { fields.put("ship.isHoldFire", err(t)); }
        try { fields.put("ship.isHoldFireOneFrame", String.valueOf(ship.isHoldFireOneFrame())); } catch (Throwable t) { fields.put("ship.isHoldFireOneFrame", err(t)); }
        try { fields.put("ship.isHulk", String.valueOf(ship.isHulk())); } catch (Throwable t) { fields.put("ship.isHulk", err(t)); }
        try { fields.put("ship.isInsideNebula", String.valueOf(ship.isInsideNebula())); } catch (Throwable t) { fields.put("ship.isInsideNebula", err(t)); }
        try { fields.put("ship.isInvalidTransferCommandTarget", String.valueOf(ship.isInvalidTransferCommandTarget())); } catch (Throwable t) { fields.put("ship.isInvalidTransferCommandTarget", err(t)); }
        try { fields.put("ship.isJitterShields", String.valueOf(ship.isJitterShields())); } catch (Throwable t) { fields.put("ship.isJitterShields", err(t)); }
        try { fields.put("ship.isLanding", String.valueOf(ship.isLanding())); } catch (Throwable t) { fields.put("ship.isLanding", err(t)); }
        try { fields.put("ship.isLiftingOff", String.valueOf(ship.isLiftingOff())); } catch (Throwable t) { fields.put("ship.isLiftingOff", err(t)); }
        try { fields.put("ship.isNoDamagedExplosions", String.valueOf(ship.isNoDamagedExplosions())); } catch (Throwable t) { fields.put("ship.isNoDamagedExplosions", err(t)); }
        try { fields.put("ship.isNoMuzzleFlash", String.valueOf(ship.isNoMuzzleFlash())); } catch (Throwable t) { fields.put("ship.isNoMuzzleFlash", err(t)); }
        try { fields.put("ship.isPhased", String.valueOf(ship.isPhased())); } catch (Throwable t) { fields.put("ship.isPhased", err(t)); }
        try { fields.put("ship.isPiece", String.valueOf(ship.isPiece())); } catch (Throwable t) { fields.put("ship.isPiece", err(t)); }
        try { fields.put("ship.isPullBackFighters", String.valueOf(ship.isPullBackFighters())); } catch (Throwable t) { fields.put("ship.isPullBackFighters", err(t)); }
        try { fields.put("ship.isRecentlyShotByPlayer", String.valueOf(ship.isRecentlyShotByPlayer())); } catch (Throwable t) { fields.put("ship.isRecentlyShotByPlayer", err(t)); }
        try { fields.put("ship.isRenderEngines", String.valueOf(ship.isRenderEngines())); } catch (Throwable t) { fields.put("ship.isRenderEngines", err(t)); }
        try { fields.put("ship.isRetreating", String.valueOf(ship.isRetreating())); } catch (Throwable t) { fields.put("ship.isRetreating", err(t)); }
        try { fields.put("ship.isSelectableInWarroom", String.valueOf(ship.isSelectableInWarroom())); } catch (Throwable t) { fields.put("ship.isSelectableInWarroom", err(t)); }
        try { fields.put("ship.isShipSystemDisabled", String.valueOf(ship.isShipSystemDisabled())); } catch (Throwable t) { fields.put("ship.isShipSystemDisabled", err(t)); }
        try { fields.put("ship.isShipWithModules", String.valueOf(ship.isShipWithModules())); } catch (Throwable t) { fields.put("ship.isShipWithModules", err(t)); }
        try { fields.put("ship.isShowModuleJitterUnder", String.valueOf(ship.isShowModuleJitterUnder())); } catch (Throwable t) { fields.put("ship.isShowModuleJitterUnder", err(t)); }
        try { fields.put("ship.isShuttlePod", String.valueOf(ship.isShuttlePod())); } catch (Throwable t) { fields.put("ship.isShuttlePod", err(t)); }
        try { fields.put("ship.isSkipNextDamagedExplosion", String.valueOf(ship.isSkipNextDamagedExplosion())); } catch (Throwable t) { fields.put("ship.isSkipNextDamagedExplosion", err(t)); }
        try { fields.put("ship.isSpawnDebris", String.valueOf(ship.isSpawnDebris())); } catch (Throwable t) { fields.put("ship.isSpawnDebris", err(t)); }
        try { fields.put("ship.isStation", String.valueOf(ship.isStation())); } catch (Throwable t) { fields.put("ship.isStation", err(t)); }
        try { fields.put("ship.isStationModule", String.valueOf(ship.isStationModule())); } catch (Throwable t) { fields.put("ship.isStationModule", err(t)); }
        try { fields.put("ship.isTargetable", String.valueOf(ship.isTargetable())); } catch (Throwable t) { fields.put("ship.isTargetable", err(t)); }
        try { fields.put("ship.isWingLeader", String.valueOf(ship.isWingLeader())); } catch (Throwable t) { fields.put("ship.isWingLeader", err(t)); }
    }

    private static void walkStats(MutableShipStatsAPI s, Map<String, String> fields) {
        try { putStat(fields, "ship.stats.getAcceleration", s.getAcceleration()); } catch (Throwable t) { fields.put("ship.stats.getAcceleration.mod", err(t)); }
        try { putStat(fields, "ship.stats.getAllowZeroFluxAtAnyLevel", s.getAllowZeroFluxAtAnyLevel()); } catch (Throwable t) { fields.put("ship.stats.getAllowZeroFluxAtAnyLevel.mod", err(t)); }
        try { putStat(fields, "ship.stats.getArmorDamageTakenMult", s.getArmorDamageTakenMult()); } catch (Throwable t) { fields.put("ship.stats.getArmorDamageTakenMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getAutofireAimAccuracy", s.getAutofireAimAccuracy()); } catch (Throwable t) { fields.put("ship.stats.getAutofireAimAccuracy.mod", err(t)); }
        try { putStat(fields, "ship.stats.getBallisticAmmoRegenMult", s.getBallisticAmmoRegenMult()); } catch (Throwable t) { fields.put("ship.stats.getBallisticAmmoRegenMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getBallisticProjectileSpeedMult", s.getBallisticProjectileSpeedMult()); } catch (Throwable t) { fields.put("ship.stats.getBallisticProjectileSpeedMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getBallisticRoFMult", s.getBallisticRoFMult()); } catch (Throwable t) { fields.put("ship.stats.getBallisticRoFMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getBallisticWeaponDamageMult", s.getBallisticWeaponDamageMult()); } catch (Throwable t) { fields.put("ship.stats.getBallisticWeaponDamageMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getBaseCRRecoveryRatePercentPerDay", s.getBaseCRRecoveryRatePercentPerDay()); } catch (Throwable t) { fields.put("ship.stats.getBaseCRRecoveryRatePercentPerDay.mod", err(t)); }
        try { putStat(fields, "ship.stats.getBeamDamageTakenMult", s.getBeamDamageTakenMult()); } catch (Throwable t) { fields.put("ship.stats.getBeamDamageTakenMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getBeamShieldDamageTakenMult", s.getBeamShieldDamageTakenMult()); } catch (Throwable t) { fields.put("ship.stats.getBeamShieldDamageTakenMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getBeamWeaponDamageMult", s.getBeamWeaponDamageMult()); } catch (Throwable t) { fields.put("ship.stats.getBeamWeaponDamageMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getBeamWeaponFluxCostMult", s.getBeamWeaponFluxCostMult()); } catch (Throwable t) { fields.put("ship.stats.getBeamWeaponFluxCostMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getBreakProb", s.getBreakProb()); } catch (Throwable t) { fields.put("ship.stats.getBreakProb.mod", err(t)); }
        try { putStat(fields, "ship.stats.getCombatEngineRepairTimeMult", s.getCombatEngineRepairTimeMult()); } catch (Throwable t) { fields.put("ship.stats.getCombatEngineRepairTimeMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getCombatWeaponRepairTimeMult", s.getCombatWeaponRepairTimeMult()); } catch (Throwable t) { fields.put("ship.stats.getCombatWeaponRepairTimeMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getCrewLossMult", s.getCrewLossMult()); } catch (Throwable t) { fields.put("ship.stats.getCrewLossMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getCriticalMalfunctionChance", s.getCriticalMalfunctionChance()); } catch (Throwable t) { fields.put("ship.stats.getCriticalMalfunctionChance.mod", err(t)); }
        try { putStat(fields, "ship.stats.getDamageToCapital", s.getDamageToCapital()); } catch (Throwable t) { fields.put("ship.stats.getDamageToCapital.mod", err(t)); }
        try { putStat(fields, "ship.stats.getDamageToCruisers", s.getDamageToCruisers()); } catch (Throwable t) { fields.put("ship.stats.getDamageToCruisers.mod", err(t)); }
        try { putStat(fields, "ship.stats.getDamageToDestroyers", s.getDamageToDestroyers()); } catch (Throwable t) { fields.put("ship.stats.getDamageToDestroyers.mod", err(t)); }
        try { putStat(fields, "ship.stats.getDamageToFighters", s.getDamageToFighters()); } catch (Throwable t) { fields.put("ship.stats.getDamageToFighters.mod", err(t)); }
        try { putStat(fields, "ship.stats.getDamageToFrigates", s.getDamageToFrigates()); } catch (Throwable t) { fields.put("ship.stats.getDamageToFrigates.mod", err(t)); }
        try { putStat(fields, "ship.stats.getDamageToMissiles", s.getDamageToMissiles()); } catch (Throwable t) { fields.put("ship.stats.getDamageToMissiles.mod", err(t)); }
        try { putStat(fields, "ship.stats.getDamageToTargetEnginesMult", s.getDamageToTargetEnginesMult()); } catch (Throwable t) { fields.put("ship.stats.getDamageToTargetEnginesMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getDamageToTargetHullMult", s.getDamageToTargetHullMult()); } catch (Throwable t) { fields.put("ship.stats.getDamageToTargetHullMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getDamageToTargetShieldsMult", s.getDamageToTargetShieldsMult()); } catch (Throwable t) { fields.put("ship.stats.getDamageToTargetShieldsMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getDamageToTargetWeaponsMult", s.getDamageToTargetWeaponsMult()); } catch (Throwable t) { fields.put("ship.stats.getDamageToTargetWeaponsMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getDeceleration", s.getDeceleration()); } catch (Throwable t) { fields.put("ship.stats.getDeceleration.mod", err(t)); }
        try { putStat(fields, "ship.stats.getEccmChance", s.getEccmChance()); } catch (Throwable t) { fields.put("ship.stats.getEccmChance.mod", err(t)); }
        try { putStat(fields, "ship.stats.getEmpDamageTakenMult", s.getEmpDamageTakenMult()); } catch (Throwable t) { fields.put("ship.stats.getEmpDamageTakenMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getEnergyAmmoRegenMult", s.getEnergyAmmoRegenMult()); } catch (Throwable t) { fields.put("ship.stats.getEnergyAmmoRegenMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getEnergyDamageTakenMult", s.getEnergyDamageTakenMult()); } catch (Throwable t) { fields.put("ship.stats.getEnergyDamageTakenMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getEnergyProjectileSpeedMult", s.getEnergyProjectileSpeedMult()); } catch (Throwable t) { fields.put("ship.stats.getEnergyProjectileSpeedMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getEnergyRoFMult", s.getEnergyRoFMult()); } catch (Throwable t) { fields.put("ship.stats.getEnergyRoFMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getEnergyShieldDamageTakenMult", s.getEnergyShieldDamageTakenMult()); } catch (Throwable t) { fields.put("ship.stats.getEnergyShieldDamageTakenMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getEnergyWeaponDamageMult", s.getEnergyWeaponDamageMult()); } catch (Throwable t) { fields.put("ship.stats.getEnergyWeaponDamageMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getEnergyWeaponFluxBasedBonusDamageMagnitude", s.getEnergyWeaponFluxBasedBonusDamageMagnitude()); } catch (Throwable t) { fields.put("ship.stats.getEnergyWeaponFluxBasedBonusDamageMagnitude.mod", err(t)); }
        try { putStat(fields, "ship.stats.getEnergyWeaponFluxBasedBonusDamageMinLevel", s.getEnergyWeaponFluxBasedBonusDamageMinLevel()); } catch (Throwable t) { fields.put("ship.stats.getEnergyWeaponFluxBasedBonusDamageMinLevel.mod", err(t)); }
        try { putStat(fields, "ship.stats.getEngineDamageTakenMult", s.getEngineDamageTakenMult()); } catch (Throwable t) { fields.put("ship.stats.getEngineDamageTakenMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getEngineMalfunctionChance", s.getEngineMalfunctionChance()); } catch (Throwable t) { fields.put("ship.stats.getEngineMalfunctionChance.mod", err(t)); }
        try { putStat(fields, "ship.stats.getFighterRefitTimeMult", s.getFighterRefitTimeMult()); } catch (Throwable t) { fields.put("ship.stats.getFighterRefitTimeMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getFluxCapacity", s.getFluxCapacity()); } catch (Throwable t) { fields.put("ship.stats.getFluxCapacity.mod", err(t)); }
        try { putStat(fields, "ship.stats.getFluxDamageTakenMult", s.getFluxDamageTakenMult()); } catch (Throwable t) { fields.put("ship.stats.getFluxDamageTakenMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getFluxDissipation", s.getFluxDissipation()); } catch (Throwable t) { fields.put("ship.stats.getFluxDissipation.mod", err(t)); }
        try { putStat(fields, "ship.stats.getFragmentationDamageTakenMult", s.getFragmentationDamageTakenMult()); } catch (Throwable t) { fields.put("ship.stats.getFragmentationDamageTakenMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getFragmentationShieldDamageTakenMult", s.getFragmentationShieldDamageTakenMult()); } catch (Throwable t) { fields.put("ship.stats.getFragmentationShieldDamageTakenMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getHardFluxDissipationFraction", s.getHardFluxDissipationFraction()); } catch (Throwable t) { fields.put("ship.stats.getHardFluxDissipationFraction.mod", err(t)); }
        try { putStat(fields, "ship.stats.getHighExplosiveDamageTakenMult", s.getHighExplosiveDamageTakenMult()); } catch (Throwable t) { fields.put("ship.stats.getHighExplosiveDamageTakenMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getHighExplosiveShieldDamageTakenMult", s.getHighExplosiveShieldDamageTakenMult()); } catch (Throwable t) { fields.put("ship.stats.getHighExplosiveShieldDamageTakenMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getHullCombatRepairRatePercentPerSecond", s.getHullCombatRepairRatePercentPerSecond()); } catch (Throwable t) { fields.put("ship.stats.getHullCombatRepairRatePercentPerSecond.mod", err(t)); }
        try { putStat(fields, "ship.stats.getHullDamageTakenMult", s.getHullDamageTakenMult()); } catch (Throwable t) { fields.put("ship.stats.getHullDamageTakenMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getHullRepairRatePercentPerSecond", s.getHullRepairRatePercentPerSecond()); } catch (Throwable t) { fields.put("ship.stats.getHullRepairRatePercentPerSecond.mod", err(t)); }
        try { putStat(fields, "ship.stats.getKineticArmorDamageTakenMult", s.getKineticArmorDamageTakenMult()); } catch (Throwable t) { fields.put("ship.stats.getKineticArmorDamageTakenMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getKineticDamageTakenMult", s.getKineticDamageTakenMult()); } catch (Throwable t) { fields.put("ship.stats.getKineticDamageTakenMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getKineticShieldDamageTakenMult", s.getKineticShieldDamageTakenMult()); } catch (Throwable t) { fields.put("ship.stats.getKineticShieldDamageTakenMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getMaxArmorDamageReduction", s.getMaxArmorDamageReduction()); } catch (Throwable t) { fields.put("ship.stats.getMaxArmorDamageReduction.mod", err(t)); }
        try { putStat(fields, "ship.stats.getMaxBurnLevel", s.getMaxBurnLevel()); } catch (Throwable t) { fields.put("ship.stats.getMaxBurnLevel.mod", err(t)); }
        try { putStat(fields, "ship.stats.getMaxCombatHullRepairFraction", s.getMaxCombatHullRepairFraction()); } catch (Throwable t) { fields.put("ship.stats.getMaxCombatHullRepairFraction.mod", err(t)); }
        try { putStat(fields, "ship.stats.getMaxCombatReadiness", s.getMaxCombatReadiness()); } catch (Throwable t) { fields.put("ship.stats.getMaxCombatReadiness.mod", err(t)); }
        try { putStat(fields, "ship.stats.getMaxHullRepairFraction", s.getMaxHullRepairFraction()); } catch (Throwable t) { fields.put("ship.stats.getMaxHullRepairFraction.mod", err(t)); }
        try { putStat(fields, "ship.stats.getMaxRecoilMult", s.getMaxRecoilMult()); } catch (Throwable t) { fields.put("ship.stats.getMaxRecoilMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getMaxSpeed", s.getMaxSpeed()); } catch (Throwable t) { fields.put("ship.stats.getMaxSpeed.mod", err(t)); }
        try { putStat(fields, "ship.stats.getMaxTurnRate", s.getMaxTurnRate()); } catch (Throwable t) { fields.put("ship.stats.getMaxTurnRate.mod", err(t)); }
        try { putStat(fields, "ship.stats.getMinArmorFraction", s.getMinArmorFraction()); } catch (Throwable t) { fields.put("ship.stats.getMinArmorFraction.mod", err(t)); }
        try { putStat(fields, "ship.stats.getMissileAmmoRegenMult", s.getMissileAmmoRegenMult()); } catch (Throwable t) { fields.put("ship.stats.getMissileAmmoRegenMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getMissileDamageTakenMult", s.getMissileDamageTakenMult()); } catch (Throwable t) { fields.put("ship.stats.getMissileDamageTakenMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getMissileGuidance", s.getMissileGuidance()); } catch (Throwable t) { fields.put("ship.stats.getMissileGuidance.mod", err(t)); }
        try { putStat(fields, "ship.stats.getMissileRoFMult", s.getMissileRoFMult()); } catch (Throwable t) { fields.put("ship.stats.getMissileRoFMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getMissileShieldDamageTakenMult", s.getMissileShieldDamageTakenMult()); } catch (Throwable t) { fields.put("ship.stats.getMissileShieldDamageTakenMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getMissileWeaponDamageMult", s.getMissileWeaponDamageMult()); } catch (Throwable t) { fields.put("ship.stats.getMissileWeaponDamageMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getNumFighterBays", s.getNumFighterBays()); } catch (Throwable t) { fields.put("ship.stats.getNumFighterBays.mod", err(t)); }
        try { putStat(fields, "ship.stats.getProjectileDamageTakenMult", s.getProjectileDamageTakenMult()); } catch (Throwable t) { fields.put("ship.stats.getProjectileDamageTakenMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getProjectileShieldDamageTakenMult", s.getProjectileShieldDamageTakenMult()); } catch (Throwable t) { fields.put("ship.stats.getProjectileShieldDamageTakenMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getProjectileSpeedMult", s.getProjectileSpeedMult()); } catch (Throwable t) { fields.put("ship.stats.getProjectileSpeedMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getRecoilDecayMult", s.getRecoilDecayMult()); } catch (Throwable t) { fields.put("ship.stats.getRecoilDecayMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getRecoilPerShotMult", s.getRecoilPerShotMult()); } catch (Throwable t) { fields.put("ship.stats.getRecoilPerShotMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getRecoilPerShotMultSmallWeaponsOnly", s.getRecoilPerShotMultSmallWeaponsOnly()); } catch (Throwable t) { fields.put("ship.stats.getRecoilPerShotMultSmallWeaponsOnly.mod", err(t)); }
        try { putStat(fields, "ship.stats.getRepairRatePercentPerDay", s.getRepairRatePercentPerDay()); } catch (Throwable t) { fields.put("ship.stats.getRepairRatePercentPerDay.mod", err(t)); }
        try { putStat(fields, "ship.stats.getSensorProfile", s.getSensorProfile()); } catch (Throwable t) { fields.put("ship.stats.getSensorProfile.mod", err(t)); }
        try { putStat(fields, "ship.stats.getSensorStrength", s.getSensorStrength()); } catch (Throwable t) { fields.put("ship.stats.getSensorStrength.mod", err(t)); }
        try { putStat(fields, "ship.stats.getShieldAbsorptionMult", s.getShieldAbsorptionMult()); } catch (Throwable t) { fields.put("ship.stats.getShieldAbsorptionMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getShieldDamageTakenMult", s.getShieldDamageTakenMult()); } catch (Throwable t) { fields.put("ship.stats.getShieldDamageTakenMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getShieldMalfunctionChance", s.getShieldMalfunctionChance()); } catch (Throwable t) { fields.put("ship.stats.getShieldMalfunctionChance.mod", err(t)); }
        try { putStat(fields, "ship.stats.getShieldMalfunctionFluxLevel", s.getShieldMalfunctionFluxLevel()); } catch (Throwable t) { fields.put("ship.stats.getShieldMalfunctionFluxLevel.mod", err(t)); }
        try { putStat(fields, "ship.stats.getShieldSoftFluxConversion", s.getShieldSoftFluxConversion()); } catch (Throwable t) { fields.put("ship.stats.getShieldSoftFluxConversion.mod", err(t)); }
        try { putStat(fields, "ship.stats.getShieldTurnRateMult", s.getShieldTurnRateMult()); } catch (Throwable t) { fields.put("ship.stats.getShieldTurnRateMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getShieldUnfoldRateMult", s.getShieldUnfoldRateMult()); } catch (Throwable t) { fields.put("ship.stats.getShieldUnfoldRateMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getShieldUpkeepMult", s.getShieldUpkeepMult()); } catch (Throwable t) { fields.put("ship.stats.getShieldUpkeepMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getSuppliesPerMonth", s.getSuppliesPerMonth()); } catch (Throwable t) { fields.put("ship.stats.getSuppliesPerMonth.mod", err(t)); }
        try { putStat(fields, "ship.stats.getSuppliesToRecover", s.getSuppliesToRecover()); } catch (Throwable t) { fields.put("ship.stats.getSuppliesToRecover.mod", err(t)); }
        try { putStat(fields, "ship.stats.getTimeMult", s.getTimeMult()); } catch (Throwable t) { fields.put("ship.stats.getTimeMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getTurnAcceleration", s.getTurnAcceleration()); } catch (Throwable t) { fields.put("ship.stats.getTurnAcceleration.mod", err(t)); }
        try { putStat(fields, "ship.stats.getVentRateMult", s.getVentRateMult()); } catch (Throwable t) { fields.put("ship.stats.getVentRateMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getWeaponDamageTakenMult", s.getWeaponDamageTakenMult()); } catch (Throwable t) { fields.put("ship.stats.getWeaponDamageTakenMult.mod", err(t)); }
        try { putStat(fields, "ship.stats.getWeaponMalfunctionChance", s.getWeaponMalfunctionChance()); } catch (Throwable t) { fields.put("ship.stats.getWeaponMalfunctionChance.mod", err(t)); }
        try { putStat(fields, "ship.stats.getWeaponRangeMultPastThreshold", s.getWeaponRangeMultPastThreshold()); } catch (Throwable t) { fields.put("ship.stats.getWeaponRangeMultPastThreshold.mod", err(t)); }
        try { putStat(fields, "ship.stats.getWeaponRangeThreshold", s.getWeaponRangeThreshold()); } catch (Throwable t) { fields.put("ship.stats.getWeaponRangeThreshold.mod", err(t)); }
        try { putStat(fields, "ship.stats.getZeroFluxMinimumFluxLevel", s.getZeroFluxMinimumFluxLevel()); } catch (Throwable t) { fields.put("ship.stats.getZeroFluxMinimumFluxLevel.mod", err(t)); }
        try { putStat(fields, "ship.stats.getZeroFluxSpeedBoost", s.getZeroFluxSpeedBoost()); } catch (Throwable t) { fields.put("ship.stats.getZeroFluxSpeedBoost.mod", err(t)); }
        // The 48 StatBonus getters of MutableShipStatsAPI (enumerated from the pinned
        // starfarer.api.jar), sparse lanes via putBonus — mirrors the server's STAT_BONUS_CLASS branch.
        try { putBonus(fields, "ship.stats.getArmorBonus", s.getArmorBonus()); } catch (Throwable t) { fields.put("ship.stats.getArmorBonus.err", err(t)); }
        try { putBonus(fields, "ship.stats.getBallisticAmmoBonus", s.getBallisticAmmoBonus()); } catch (Throwable t) { fields.put("ship.stats.getBallisticAmmoBonus.err", err(t)); }
        try { putBonus(fields, "ship.stats.getBallisticWeaponFluxCostMod", s.getBallisticWeaponFluxCostMod()); } catch (Throwable t) { fields.put("ship.stats.getBallisticWeaponFluxCostMod.err", err(t)); }
        try { putBonus(fields, "ship.stats.getBallisticWeaponRangeBonus", s.getBallisticWeaponRangeBonus()); } catch (Throwable t) { fields.put("ship.stats.getBallisticWeaponRangeBonus.err", err(t)); }
        try { putBonus(fields, "ship.stats.getBeamPDWeaponRangeBonus", s.getBeamPDWeaponRangeBonus()); } catch (Throwable t) { fields.put("ship.stats.getBeamPDWeaponRangeBonus.err", err(t)); }
        try { putBonus(fields, "ship.stats.getBeamSpeedMod", s.getBeamSpeedMod()); } catch (Throwable t) { fields.put("ship.stats.getBeamSpeedMod.err", err(t)); }
        try { putBonus(fields, "ship.stats.getBeamWeaponRangeBonus", s.getBeamWeaponRangeBonus()); } catch (Throwable t) { fields.put("ship.stats.getBeamWeaponRangeBonus.err", err(t)); }
        try { putBonus(fields, "ship.stats.getBeamWeaponTurnRateBonus", s.getBeamWeaponTurnRateBonus()); } catch (Throwable t) { fields.put("ship.stats.getBeamWeaponTurnRateBonus.err", err(t)); }
        try { putBonus(fields, "ship.stats.getCRLossPerSecondPercent", s.getCRLossPerSecondPercent()); } catch (Throwable t) { fields.put("ship.stats.getCRLossPerSecondPercent.err", err(t)); }
        try { putBonus(fields, "ship.stats.getCRPerDeploymentPercent", s.getCRPerDeploymentPercent()); } catch (Throwable t) { fields.put("ship.stats.getCRPerDeploymentPercent.err", err(t)); }
        try { putBonus(fields, "ship.stats.getCargoMod", s.getCargoMod()); } catch (Throwable t) { fields.put("ship.stats.getCargoMod.err", err(t)); }
        try { putBonus(fields, "ship.stats.getCriticalMalfunctionDamageMod", s.getCriticalMalfunctionDamageMod()); } catch (Throwable t) { fields.put("ship.stats.getCriticalMalfunctionDamageMod.err", err(t)); }
        try { putBonus(fields, "ship.stats.getEffectiveArmorBonus", s.getEffectiveArmorBonus()); } catch (Throwable t) { fields.put("ship.stats.getEffectiveArmorBonus.err", err(t)); }
        try { putBonus(fields, "ship.stats.getEnergyAmmoBonus", s.getEnergyAmmoBonus()); } catch (Throwable t) { fields.put("ship.stats.getEnergyAmmoBonus.err", err(t)); }
        try { putBonus(fields, "ship.stats.getEnergyWeaponFluxCostMod", s.getEnergyWeaponFluxCostMod()); } catch (Throwable t) { fields.put("ship.stats.getEnergyWeaponFluxCostMod.err", err(t)); }
        try { putBonus(fields, "ship.stats.getEnergyWeaponRangeBonus", s.getEnergyWeaponRangeBonus()); } catch (Throwable t) { fields.put("ship.stats.getEnergyWeaponRangeBonus.err", err(t)); }
        try { putBonus(fields, "ship.stats.getEngineHealthBonus", s.getEngineHealthBonus()); } catch (Throwable t) { fields.put("ship.stats.getEngineHealthBonus.err", err(t)); }
        try { putBonus(fields, "ship.stats.getFighterWingRange", s.getFighterWingRange()); } catch (Throwable t) { fields.put("ship.stats.getFighterWingRange.err", err(t)); }
        try { putBonus(fields, "ship.stats.getFuelMod", s.getFuelMod()); } catch (Throwable t) { fields.put("ship.stats.getFuelMod.err", err(t)); }
        try { putBonus(fields, "ship.stats.getFuelUseMod", s.getFuelUseMod()); } catch (Throwable t) { fields.put("ship.stats.getFuelUseMod.err", err(t)); }
        try { putBonus(fields, "ship.stats.getHangarSpaceMod", s.getHangarSpaceMod()); } catch (Throwable t) { fields.put("ship.stats.getHangarSpaceMod.err", err(t)); }
        try { putBonus(fields, "ship.stats.getHitStrengthBonus", s.getHitStrengthBonus()); } catch (Throwable t) { fields.put("ship.stats.getHitStrengthBonus.err", err(t)); }
        try { putBonus(fields, "ship.stats.getHullBonus", s.getHullBonus()); } catch (Throwable t) { fields.put("ship.stats.getHullBonus.err", err(t)); }
        try { putBonus(fields, "ship.stats.getMaxCrewMod", s.getMaxCrewMod()); } catch (Throwable t) { fields.put("ship.stats.getMaxCrewMod.err", err(t)); }
        try { putBonus(fields, "ship.stats.getMinCrewMod", s.getMinCrewMod()); } catch (Throwable t) { fields.put("ship.stats.getMinCrewMod.err", err(t)); }
        try { putBonus(fields, "ship.stats.getMissileAccelerationBonus", s.getMissileAccelerationBonus()); } catch (Throwable t) { fields.put("ship.stats.getMissileAccelerationBonus.err", err(t)); }
        try { putBonus(fields, "ship.stats.getMissileAmmoBonus", s.getMissileAmmoBonus()); } catch (Throwable t) { fields.put("ship.stats.getMissileAmmoBonus.err", err(t)); }
        try { putBonus(fields, "ship.stats.getMissileHealthBonus", s.getMissileHealthBonus()); } catch (Throwable t) { fields.put("ship.stats.getMissileHealthBonus.err", err(t)); }
        try { putBonus(fields, "ship.stats.getMissileMaxSpeedBonus", s.getMissileMaxSpeedBonus()); } catch (Throwable t) { fields.put("ship.stats.getMissileMaxSpeedBonus.err", err(t)); }
        try { putBonus(fields, "ship.stats.getMissileMaxTurnRateBonus", s.getMissileMaxTurnRateBonus()); } catch (Throwable t) { fields.put("ship.stats.getMissileMaxTurnRateBonus.err", err(t)); }
        try { putBonus(fields, "ship.stats.getMissileTurnAccelerationBonus", s.getMissileTurnAccelerationBonus()); } catch (Throwable t) { fields.put("ship.stats.getMissileTurnAccelerationBonus.err", err(t)); }
        try { putBonus(fields, "ship.stats.getMissileWeaponFluxCostMod", s.getMissileWeaponFluxCostMod()); } catch (Throwable t) { fields.put("ship.stats.getMissileWeaponFluxCostMod.err", err(t)); }
        try { putBonus(fields, "ship.stats.getMissileWeaponRangeBonus", s.getMissileWeaponRangeBonus()); } catch (Throwable t) { fields.put("ship.stats.getMissileWeaponRangeBonus.err", err(t)); }
        try { putBonus(fields, "ship.stats.getNonBeamPDWeaponRangeBonus", s.getNonBeamPDWeaponRangeBonus()); } catch (Throwable t) { fields.put("ship.stats.getNonBeamPDWeaponRangeBonus.err", err(t)); }
        try { putBonus(fields, "ship.stats.getOverloadTimeMod", s.getOverloadTimeMod()); } catch (Throwable t) { fields.put("ship.stats.getOverloadTimeMod.err", err(t)); }
        try { putBonus(fields, "ship.stats.getPeakCRDuration", s.getPeakCRDuration()); } catch (Throwable t) { fields.put("ship.stats.getPeakCRDuration.err", err(t)); }
        try { putBonus(fields, "ship.stats.getPhaseCloakActivationCostBonus", s.getPhaseCloakActivationCostBonus()); } catch (Throwable t) { fields.put("ship.stats.getPhaseCloakActivationCostBonus.err", err(t)); }
        try { putBonus(fields, "ship.stats.getPhaseCloakCooldownBonus", s.getPhaseCloakCooldownBonus()); } catch (Throwable t) { fields.put("ship.stats.getPhaseCloakCooldownBonus.err", err(t)); }
        try { putBonus(fields, "ship.stats.getPhaseCloakUpkeepCostBonus", s.getPhaseCloakUpkeepCostBonus()); } catch (Throwable t) { fields.put("ship.stats.getPhaseCloakUpkeepCostBonus.err", err(t)); }
        try { putBonus(fields, "ship.stats.getShieldArcBonus", s.getShieldArcBonus()); } catch (Throwable t) { fields.put("ship.stats.getShieldArcBonus.err", err(t)); }
        try { putBonus(fields, "ship.stats.getSightRadiusMod", s.getSightRadiusMod()); } catch (Throwable t) { fields.put("ship.stats.getSightRadiusMod.err", err(t)); }
        try { putBonus(fields, "ship.stats.getSystemCooldownBonus", s.getSystemCooldownBonus()); } catch (Throwable t) { fields.put("ship.stats.getSystemCooldownBonus.err", err(t)); }
        try { putBonus(fields, "ship.stats.getSystemFluxCostBonus", s.getSystemFluxCostBonus()); } catch (Throwable t) { fields.put("ship.stats.getSystemFluxCostBonus.err", err(t)); }
        try { putBonus(fields, "ship.stats.getSystemRangeBonus", s.getSystemRangeBonus()); } catch (Throwable t) { fields.put("ship.stats.getSystemRangeBonus.err", err(t)); }
        try { putBonus(fields, "ship.stats.getSystemRegenBonus", s.getSystemRegenBonus()); } catch (Throwable t) { fields.put("ship.stats.getSystemRegenBonus.err", err(t)); }
        try { putBonus(fields, "ship.stats.getSystemUsesBonus", s.getSystemUsesBonus()); } catch (Throwable t) { fields.put("ship.stats.getSystemUsesBonus.err", err(t)); }
        try { putBonus(fields, "ship.stats.getWeaponHealthBonus", s.getWeaponHealthBonus()); } catch (Throwable t) { fields.put("ship.stats.getWeaponHealthBonus.err", err(t)); }
        try { putBonus(fields, "ship.stats.getWeaponTurnRateBonus", s.getWeaponTurnRateBonus()); } catch (Throwable t) { fields.put("ship.stats.getWeaponTurnRateBonus.err", err(t)); }
    }

    private static void walkFlux(FluxTrackerAPI fx, Map<String, String> fields) {
        try { fields.put("ship.flux.getCurrFlux", num(fx.getCurrFlux())); } catch (Throwable t) { fields.put("ship.flux.getCurrFlux", err(t)); }
        try { fields.put("ship.flux.getEngineBoostLevel", num(fx.getEngineBoostLevel())); } catch (Throwable t) { fields.put("ship.flux.getEngineBoostLevel", err(t)); }
        try { fields.put("ship.flux.getFloatySize", num(fx.getFloatySize())); } catch (Throwable t) { fields.put("ship.flux.getFloatySize", err(t)); }
        try { fields.put("ship.flux.getFluxLevel", num(fx.getFluxLevel())); } catch (Throwable t) { fields.put("ship.flux.getFluxLevel", err(t)); }
        try { fields.put("ship.flux.getHardFlux", num(fx.getHardFlux())); } catch (Throwable t) { fields.put("ship.flux.getHardFlux", err(t)); }
        try { fields.put("ship.flux.getMaxFlux", num(fx.getMaxFlux())); } catch (Throwable t) { fields.put("ship.flux.getMaxFlux", err(t)); }
        try { fields.put("ship.flux.getOverloadText", txt(fx.getOverloadText())); } catch (Throwable t) { fields.put("ship.flux.getOverloadText", err(t)); }
        try { fields.put("ship.flux.getOverloadTimeRemaining", num(fx.getOverloadTimeRemaining())); } catch (Throwable t) { fields.put("ship.flux.getOverloadTimeRemaining", err(t)); }
        try { fields.put("ship.flux.getTimeToVent", num(fx.getTimeToVent())); } catch (Throwable t) { fields.put("ship.flux.getTimeToVent", err(t)); }
        try { fields.put("ship.flux.isEngineBoostActive", String.valueOf(fx.isEngineBoostActive())); } catch (Throwable t) { fields.put("ship.flux.isEngineBoostActive", err(t)); }
        try { fields.put("ship.flux.isOverloaded", String.valueOf(fx.isOverloaded())); } catch (Throwable t) { fields.put("ship.flux.isOverloaded", err(t)); }
        try { fields.put("ship.flux.isOverloadedOrVenting", String.valueOf(fx.isOverloadedOrVenting())); } catch (Throwable t) { fields.put("ship.flux.isOverloadedOrVenting", err(t)); }
        try { fields.put("ship.flux.isVenting", String.valueOf(fx.isVenting())); } catch (Throwable t) { fields.put("ship.flux.isVenting", err(t)); }
    }

    private static void walkSystem(ShipSystemAPI sys, Map<String, String> fields) {
        try { fields.put("ship.system.getAmmo", String.valueOf(sys.getAmmo())); } catch (Throwable t) { fields.put("ship.system.getAmmo", err(t)); }
        try { fields.put("ship.system.getAmmoPerSecond", num(sys.getAmmoPerSecond())); } catch (Throwable t) { fields.put("ship.system.getAmmoPerSecond", err(t)); }
        try { fields.put("ship.system.getAmmoReloadProgress", num(sys.getAmmoReloadProgress())); } catch (Throwable t) { fields.put("ship.system.getAmmoReloadProgress", err(t)); }
        try { fields.put("ship.system.getChargeActiveDur", num(sys.getChargeActiveDur())); } catch (Throwable t) { fields.put("ship.system.getChargeActiveDur", err(t)); }
        try { fields.put("ship.system.getChargeDownDur", num(sys.getChargeDownDur())); } catch (Throwable t) { fields.put("ship.system.getChargeDownDur", err(t)); }
        try { fields.put("ship.system.getChargeUpDur", num(sys.getChargeUpDur())); } catch (Throwable t) { fields.put("ship.system.getChargeUpDur", err(t)); }
        try { fields.put("ship.system.getCooldown", num(sys.getCooldown())); } catch (Throwable t) { fields.put("ship.system.getCooldown", err(t)); }
        try { fields.put("ship.system.getCooldownRemaining", num(sys.getCooldownRemaining())); } catch (Throwable t) { fields.put("ship.system.getCooldownRemaining", err(t)); }
        try { fields.put("ship.system.getDisplayName", txt(sys.getDisplayName())); } catch (Throwable t) { fields.put("ship.system.getDisplayName", err(t)); }
        try { fields.put("ship.system.getEffectLevel", num(sys.getEffectLevel())); } catch (Throwable t) { fields.put("ship.system.getEffectLevel", err(t)); }
        try { fields.put("ship.system.getFluxPerSecond", num(sys.getFluxPerSecond())); } catch (Throwable t) { fields.put("ship.system.getFluxPerSecond", err(t)); }
        try { fields.put("ship.system.getFluxPerUse", num(sys.getFluxPerUse())); } catch (Throwable t) { fields.put("ship.system.getFluxPerUse", err(t)); }
        try { fields.put("ship.system.getId", txt(sys.getId())); } catch (Throwable t) { fields.put("ship.system.getId", err(t)); }
        try { fields.put("ship.system.getMaxAmmo", String.valueOf(sys.getMaxAmmo())); } catch (Throwable t) { fields.put("ship.system.getMaxAmmo", err(t)); }
        try { fields.put("ship.system.getState", txt(sys.getState())); } catch (Throwable t) { fields.put("ship.system.getState", err(t)); }
        try { vec(fields, "ship.system.getTargetLoc", sys.getTargetLoc()); } catch (Throwable t) { vecErr(fields, "ship.system.getTargetLoc", t); }
        try { fields.put("ship.system.isActive", String.valueOf(sys.isActive())); } catch (Throwable t) { fields.put("ship.system.isActive", err(t)); }
        try { fields.put("ship.system.isChargedown", String.valueOf(sys.isChargedown())); } catch (Throwable t) { fields.put("ship.system.isChargedown", err(t)); }
        try { fields.put("ship.system.isChargeup", String.valueOf(sys.isChargeup())); } catch (Throwable t) { fields.put("ship.system.isChargeup", err(t)); }
        try { fields.put("ship.system.isCoolingDown", String.valueOf(sys.isCoolingDown())); } catch (Throwable t) { fields.put("ship.system.isCoolingDown", err(t)); }
        try { fields.put("ship.system.isOn", String.valueOf(sys.isOn())); } catch (Throwable t) { fields.put("ship.system.isOn", err(t)); }
        try { fields.put("ship.system.isOutOfAmmo", String.valueOf(sys.isOutOfAmmo())); } catch (Throwable t) { fields.put("ship.system.isOutOfAmmo", err(t)); }
        try { fields.put("ship.system.isStateActive", String.valueOf(sys.isStateActive())); } catch (Throwable t) { fields.put("ship.system.isStateActive", err(t)); }
    }

    private static void walkCtrl(ShipEngineControllerAPI ec, Map<String, String> fields) {
        try { fields.put("ship.ctrl.getFlameoutFraction", num(ec.getFlameoutFraction())); } catch (Throwable t) { fields.put("ship.ctrl.getFlameoutFraction", err(t)); }
        try { fields.put("ship.ctrl.getMaxSpeedWithoutBoost", num(ec.getMaxSpeedWithoutBoost())); } catch (Throwable t) { fields.put("ship.ctrl.getMaxSpeedWithoutBoost", err(t)); }
        try { fields.put("ship.ctrl.getTurnDeceleration", num(ec.getTurnDeceleration())); } catch (Throwable t) { fields.put("ship.ctrl.getTurnDeceleration", err(t)); }
        try { fields.put("ship.ctrl.isAccelerating", String.valueOf(ec.isAccelerating())); } catch (Throwable t) { fields.put("ship.ctrl.isAccelerating", err(t)); }
        try { fields.put("ship.ctrl.isAcceleratingBackwards", String.valueOf(ec.isAcceleratingBackwards())); } catch (Throwable t) { fields.put("ship.ctrl.isAcceleratingBackwards", err(t)); }
        try { fields.put("ship.ctrl.isDecelerating", String.valueOf(ec.isDecelerating())); } catch (Throwable t) { fields.put("ship.ctrl.isDecelerating", err(t)); }
        try { fields.put("ship.ctrl.isDisabled", String.valueOf(ec.isDisabled())); } catch (Throwable t) { fields.put("ship.ctrl.isDisabled", err(t)); }
        try { fields.put("ship.ctrl.isFlamedOut", String.valueOf(ec.isFlamedOut())); } catch (Throwable t) { fields.put("ship.ctrl.isFlamedOut", err(t)); }
        try { fields.put("ship.ctrl.isFlamingOut", String.valueOf(ec.isFlamingOut())); } catch (Throwable t) { fields.put("ship.ctrl.isFlamingOut", err(t)); }
        try { fields.put("ship.ctrl.isIdle", String.valueOf(ec.isIdle())); } catch (Throwable t) { fields.put("ship.ctrl.isIdle", err(t)); }
        try { fields.put("ship.ctrl.isStrafingLeft", String.valueOf(ec.isStrafingLeft())); } catch (Throwable t) { fields.put("ship.ctrl.isStrafingLeft", err(t)); }
        try { fields.put("ship.ctrl.isStrafingRight", String.valueOf(ec.isStrafingRight())); } catch (Throwable t) { fields.put("ship.ctrl.isStrafingRight", err(t)); }
        try { fields.put("ship.ctrl.isTurningLeft", String.valueOf(ec.isTurningLeft())); } catch (Throwable t) { fields.put("ship.ctrl.isTurningLeft", err(t)); }
        try { fields.put("ship.ctrl.isTurningRight", String.valueOf(ec.isTurningRight())); } catch (Throwable t) { fields.put("ship.ctrl.isTurningRight", err(t)); }
    }

    private static void walkShield(ShieldAPI sh, Map<String, String> fields) {
        try { fields.put("ship.shield.getActiveArc", num(sh.getActiveArc())); } catch (Throwable t) { fields.put("ship.shield.getActiveArc", err(t)); }
        try { fields.put("ship.shield.getArc", num(sh.getArc())); } catch (Throwable t) { fields.put("ship.shield.getArc", err(t)); }
        try { fields.put("ship.shield.getFacing", num(sh.getFacing())); } catch (Throwable t) { fields.put("ship.shield.getFacing", err(t)); }
        try { fields.put("ship.shield.getFluxPerPointOfDamage", num(sh.getFluxPerPointOfDamage())); } catch (Throwable t) { fields.put("ship.shield.getFluxPerPointOfDamage", err(t)); }
        try { fields.put("ship.shield.getInnerRotationRate", num(sh.getInnerRotationRate())); } catch (Throwable t) { fields.put("ship.shield.getInnerRotationRate", err(t)); }
        try { vec(fields, "ship.shield.getLocation", sh.getLocation()); } catch (Throwable t) { vecErr(fields, "ship.shield.getLocation", t); }
        try { fields.put("ship.shield.getRadius", num(sh.getRadius())); } catch (Throwable t) { fields.put("ship.shield.getRadius", err(t)); }
        try { fields.put("ship.shield.getRingRotationRate", num(sh.getRingRotationRate())); } catch (Throwable t) { fields.put("ship.shield.getRingRotationRate", err(t)); }
        try { fields.put("ship.shield.getType", txt(sh.getType())); } catch (Throwable t) { fields.put("ship.shield.getType", err(t)); }
        try { fields.put("ship.shield.getUnfoldTime", num(sh.getUnfoldTime())); } catch (Throwable t) { fields.put("ship.shield.getUnfoldTime", err(t)); }
        try { fields.put("ship.shield.getUpkeep", num(sh.getUpkeep())); } catch (Throwable t) { fields.put("ship.shield.getUpkeep", err(t)); }
        try { fields.put("ship.shield.isOff", String.valueOf(sh.isOff())); } catch (Throwable t) { fields.put("ship.shield.isOff", err(t)); }
        try { fields.put("ship.shield.isOn", String.valueOf(sh.isOn())); } catch (Throwable t) { fields.put("ship.shield.isOn", err(t)); }
        try { fields.put("ship.shield.isSkipRendering", String.valueOf(sh.isSkipRendering())); } catch (Throwable t) { fields.put("ship.shield.isSkipRendering", err(t)); }
    }

    private static void walkWeapon(WeaponAPI w, Map<String, String> fields) {
        try { fields.put("ship.weapon.getAmmo", String.valueOf(w.getAmmo())); } catch (Throwable t) { fields.put("ship.weapon.getAmmo", err(t)); }
        try { fields.put("ship.weapon.getAmmoPerSecond", num(w.getAmmoPerSecond())); } catch (Throwable t) { fields.put("ship.weapon.getAmmoPerSecond", err(t)); }
        try { fields.put("ship.weapon.getArc", num(w.getArc())); } catch (Throwable t) { fields.put("ship.weapon.getArc", err(t)); }
        try { fields.put("ship.weapon.getArcFacing", num(w.getArcFacing())); } catch (Throwable t) { fields.put("ship.weapon.getArcFacing", err(t)); }
        try { fields.put("ship.weapon.getBurstFireTimeRemaining", num(w.getBurstFireTimeRemaining())); } catch (Throwable t) { fields.put("ship.weapon.getBurstFireTimeRemaining", err(t)); }
        try { fields.put("ship.weapon.getChargeLevel", num(w.getChargeLevel())); } catch (Throwable t) { fields.put("ship.weapon.getChargeLevel", err(t)); }
        try { fields.put("ship.weapon.getCooldown", num(w.getCooldown())); } catch (Throwable t) { fields.put("ship.weapon.getCooldown", err(t)); }
        try { fields.put("ship.weapon.getCooldownRemaining", num(w.getCooldownRemaining())); } catch (Throwable t) { fields.put("ship.weapon.getCooldownRemaining", err(t)); }
        try { fields.put("ship.weapon.getCurrAngle", num(w.getCurrAngle())); } catch (Throwable t) { fields.put("ship.weapon.getCurrAngle", err(t)); }
        try { fields.put("ship.weapon.getCurrHealth", num(w.getCurrHealth())); } catch (Throwable t) { fields.put("ship.weapon.getCurrHealth", err(t)); }
        try { fields.put("ship.weapon.getCurrSpread", num(w.getCurrSpread())); } catch (Throwable t) { fields.put("ship.weapon.getCurrSpread", err(t)); }
        try { fields.put("ship.weapon.getDamageType", txt(w.getDamageType())); } catch (Throwable t) { fields.put("ship.weapon.getDamageType", err(t)); }
        try { fields.put("ship.weapon.getDisabledDuration", num(w.getDisabledDuration())); } catch (Throwable t) { fields.put("ship.weapon.getDisabledDuration", err(t)); }
        try { fields.put("ship.weapon.getDisplayArcRadius", num(w.getDisplayArcRadius())); } catch (Throwable t) { fields.put("ship.weapon.getDisplayArcRadius", err(t)); }
        try { fields.put("ship.weapon.getDisplayName", txt(w.getDisplayName())); } catch (Throwable t) { fields.put("ship.weapon.getDisplayName", err(t)); }
        try { fields.put("ship.weapon.getFluxCostToFire", num(w.getFluxCostToFire())); } catch (Throwable t) { fields.put("ship.weapon.getFluxCostToFire", err(t)); }
        try { fields.put("ship.weapon.getId", txt(w.getId())); } catch (Throwable t) { fields.put("ship.weapon.getId", err(t)); }
        try { vec(fields, "ship.weapon.getLocation", w.getLocation()); } catch (Throwable t) { vecErr(fields, "ship.weapon.getLocation", t); }
        try { fields.put("ship.weapon.getMaxAmmo", String.valueOf(w.getMaxAmmo())); } catch (Throwable t) { fields.put("ship.weapon.getMaxAmmo", err(t)); }
        try { fields.put("ship.weapon.getMaxHealth", num(w.getMaxHealth())); } catch (Throwable t) { fields.put("ship.weapon.getMaxHealth", err(t)); }
        try { fields.put("ship.weapon.getProjectileCollisionClass", txt(w.getProjectileCollisionClass())); } catch (Throwable t) { fields.put("ship.weapon.getProjectileCollisionClass", err(t)); }
        try { fields.put("ship.weapon.getProjectileFadeRange", num(w.getProjectileFadeRange())); } catch (Throwable t) { fields.put("ship.weapon.getProjectileFadeRange", err(t)); }
        try { fields.put("ship.weapon.getProjectileSpeed", num(w.getProjectileSpeed())); } catch (Throwable t) { fields.put("ship.weapon.getProjectileSpeed", err(t)); }
        try { fields.put("ship.weapon.getRange", num(w.getRange())); } catch (Throwable t) { fields.put("ship.weapon.getRange", err(t)); }
        try { fields.put("ship.weapon.getRefireDelay", num(w.getRefireDelay())); } catch (Throwable t) { fields.put("ship.weapon.getRefireDelay", err(t)); }
        try { vec(fields, "ship.weapon.getRenderOffsetForDecorativeBeamWeaponsOnly", w.getRenderOffsetForDecorativeBeamWeaponsOnly()); } catch (Throwable t) { vecErr(fields, "ship.weapon.getRenderOffsetForDecorativeBeamWeaponsOnly", t); }
        try { fields.put("ship.weapon.getSize", txt(w.getSize())); } catch (Throwable t) { fields.put("ship.weapon.getSize", err(t)); }
        try { fields.put("ship.weapon.getTurnRate", num(w.getTurnRate())); } catch (Throwable t) { fields.put("ship.weapon.getTurnRate", err(t)); }
        try { fields.put("ship.weapon.getType", txt(w.getType())); } catch (Throwable t) { fields.put("ship.weapon.getType", err(t)); }
        try { fields.put("ship.weapon.isAlwaysFire", String.valueOf(w.isAlwaysFire())); } catch (Throwable t) { fields.put("ship.weapon.isAlwaysFire", err(t)); }
        try { fields.put("ship.weapon.isBeam", String.valueOf(w.isBeam())); } catch (Throwable t) { fields.put("ship.weapon.isBeam", err(t)); }
        try { fields.put("ship.weapon.isBurstBeam", String.valueOf(w.isBurstBeam())); } catch (Throwable t) { fields.put("ship.weapon.isBurstBeam", err(t)); }
        try { fields.put("ship.weapon.isDecorative", String.valueOf(w.isDecorative())); } catch (Throwable t) { fields.put("ship.weapon.isDecorative", err(t)); }
        try { fields.put("ship.weapon.isDisabled", String.valueOf(w.isDisabled())); } catch (Throwable t) { fields.put("ship.weapon.isDisabled", err(t)); }
        try { fields.put("ship.weapon.isFiring", String.valueOf(w.isFiring())); } catch (Throwable t) { fields.put("ship.weapon.isFiring", err(t)); }
        try { fields.put("ship.weapon.isForceDisabled", String.valueOf(w.isForceDisabled())); } catch (Throwable t) { fields.put("ship.weapon.isForceDisabled", err(t)); }
        try { fields.put("ship.weapon.isForceNoFireOneFrame", String.valueOf(w.isForceNoFireOneFrame())); } catch (Throwable t) { fields.put("ship.weapon.isForceNoFireOneFrame", err(t)); }
        try { fields.put("ship.weapon.isInBurst", String.valueOf(w.isInBurst())); } catch (Throwable t) { fields.put("ship.weapon.isInBurst", err(t)); }
        try { fields.put("ship.weapon.isKeepBeamTargetWhileChargingDown", String.valueOf(w.isKeepBeamTargetWhileChargingDown())); } catch (Throwable t) { fields.put("ship.weapon.isKeepBeamTargetWhileChargingDown", err(t)); }
        try { fields.put("ship.weapon.isPermanentlyDisabled", String.valueOf(w.isPermanentlyDisabled())); } catch (Throwable t) { fields.put("ship.weapon.isPermanentlyDisabled", err(t)); }
        try { fields.put("ship.weapon.isPulse", String.valueOf(w.isPulse())); } catch (Throwable t) { fields.put("ship.weapon.isPulse", err(t)); }
        try { fields.put("ship.weapon.isRenderBarrelBelow", String.valueOf(w.isRenderBarrelBelow())); } catch (Throwable t) { fields.put("ship.weapon.isRenderBarrelBelow", err(t)); }
    }

    // === value rendering: byte-compatible with ParityAuditWalker.renderScalar/-VectorComponent/-MutableStatModified ===

    /** Float rendered through double exactly as the server's reflective walk boxes it. */
    private static String num(float v) {
        double d = v;
        if (Double.isNaN(d)) { return "\"NaN\""; }
        if (Double.isInfinite(d)) { return d > 0 ? "\"Inf\"" : "\"-Inf\""; }
        return String.valueOf(d);
    }

    /** Strings + enums (server: jsonString(String.valueOf(v)); null renders as quoted "null"). */
    private static String txt(Object v) {
        return v == null ? "\"null\"" : json(String.valueOf(v));
    }

    /**
     * Percent-lane fields — client half of {@code ParityAuditWalker.captureStatLanes}
     * (see its javadoc for the design; a coord-maneuvers pin motivated it). Emits the same
     * {@code .mod} value the old {@code mod()} call produced, then SPARSELY (any mod map non-empty)
     * {@code .pct}/{@code .flat}/{@code .mult} lane aggregates summed in sorted-id order, and for
     * {@link #MODS_ATTRIBUTED} getters the {@code .mods} per-modifier attribution string —
     * key names + value bytes identical to the server half. Direct compiled API calls only
     * (script classloader-B denies reflection). Callers keep their per-site catch fallback.
     */
    private static void putStat(Map<String, String> fields, String base, MutableStat stat) {
        if (stat == null) {
            fields.put(base + ".mod", "\"null\"");
            return;
        }
        fields.put(base + ".mod", num(stat.getModifiedValue()));
        putLanes(fields, base, stat.getPercentMods(), stat.getFlatMods(), stat.getMultMods());
    }

    /**
     * Client half of {@code ParityAuditWalker.captureBonusLanes}: a StatBonus
     * (the hullmod-written range/armor/hull/system bonuses; Escort Package = {@code modifyPercent} on
     * the ballistic + energy range bonuses) has no modified value, so it emits the SPARSE lanes only
     * (same keys/bytes as {@link #putStat}); a null bonus lands as {@code <key>.err="null"}, mirroring
     * the server's null/err: carrier. Callers keep their per-site catch → {@code <key>.err}.
     */
    private static void putBonus(Map<String, String> fields, String base, StatBonus bonus) {
        if (bonus == null) {
            fields.put(base + ".err", "\"null\"");
            return;
        }
        putLanes(fields, base, bonus.getPercentBonuses(), bonus.getFlatBonuses(), bonus.getMultBonuses());
    }

    private static void putLanes(Map<String, String> fields, String base, Map<String, MutableStat.StatMod> pct,
            Map<String, MutableStat.StatMod> flat, Map<String, MutableStat.StatMod> mult) {
        boolean pEmpty = pct == null || pct.isEmpty();
        boolean fEmpty = flat == null || flat.isEmpty();
        boolean mEmpty = mult == null || mult.isEmpty();
        if (pEmpty && fEmpty && mEmpty) {
            return;
        }
        fields.put(base + ".pct", num(sumMods(pct, false)));
        fields.put(base + ".flat", num(sumMods(flat, false)));
        fields.put(base + ".mult", num(sumMods(mult, true)));
        if (MODS_ATTRIBUTED.contains(base.substring(base.lastIndexOf('.') + 1))) {
            fields.put(base + ".mods", json(modsString(pct, flat, mult)));
        }
    }

    /** Getters whose stats emit {@code .mods} — mirrors the server default; override with
     *  {@code -Dcc.parity.walkMods} then {@code CC_PARITY_WALK_MODS} (comma-separated getter names). */
    private static final java.util.Set<String> MODS_ATTRIBUTED = buildModsAttributed();

    private static java.util.Set<String> buildModsAttributed() {
        java.util.Set<String> names = new java.util.HashSet<>();
        String conf = null;
        try { conf = System.getProperty("cc.parity.walkMods"); } catch (Throwable ignore) { }
        if (conf == null || conf.trim().isEmpty()) {
            try { conf = System.getenv("CC_PARITY_WALK_MODS"); } catch (Throwable ignore) { }
        }
        if (conf == null || conf.trim().isEmpty()) {
            names.add("getMaxSpeed");
            names.add("getBallisticWeaponRangeBonus");
            names.add("getEnergyWeaponRangeBonus");
            names.add("getMissileWeaponRangeBonus");
            // Movement extension — MUST mirror the server default set: the
            // SD/DEFECT-1 class hits accel/decel/turn hardest (server 150-vs-100 pct) and
            // the cross-side modset bar grades these four attributed.
            names.add("getAcceleration");
            names.add("getDeceleration");
            names.add("getMaxTurnRate");
            names.add("getTurnAcceleration");
        } else {
            for (String n : conf.split(",")) {
                if (!n.trim().isEmpty()) { names.add(n.trim()); }
            }
        }
        return names;
    }

    /** Σ (or Π when {@code product}) of StatMod.value, accumulated in sorted-id order —
     *  identical float rounding to the server half regardless of HashMap insertion history. */
    private static float sumMods(Map<String, MutableStat.StatMod> mods, boolean product) {
        float acc = product ? 1.0F : 0.0F;
        if (mods != null && !mods.isEmpty()) {
            Map<String, MutableStat.StatMod> sorted = new TreeMap<>(mods);
            for (MutableStat.StatMod mod : sorted.values()) {
                float v = mod == null ? 0.0F : mod.value;
                acc = product ? acc * v : acc + v;
            }
        }
        return acc;
    }

    /** {@code p[id:v,...]f[id:v,...]m[id:v,...]} — ids sorted per lane, values via
     *  {@code Double.toString((double) v)}; byte-identical to the server's renderModsString.
     *  Package-visible: {@link TargetStatSig} renders its attribution
     *  lanes through this same law so the stat-sig dump stays server-comparable. */
    static String modsString(Map<String, MutableStat.StatMod> pct,
            Map<String, MutableStat.StatMod> flat, Map<String, MutableStat.StatMod> mult) {
        StringBuilder sb = new StringBuilder(96);
        appendModsLane(sb, 'p', pct);
        appendModsLane(sb, 'f', flat);
        appendModsLane(sb, 'm', mult);
        return sb.toString();
    }

    private static void appendModsLane(StringBuilder sb, char lane, Map<String, MutableStat.StatMod> mods) {
        sb.append(lane).append('[');
        if (mods != null && !mods.isEmpty()) {
            Map<String, String> sorted = new TreeMap<>();
            for (Map.Entry<String, MutableStat.StatMod> e : mods.entrySet()) {
                float v = e.getValue() == null ? 0.0F : e.getValue().value;
                sorted.put(String.valueOf(e.getKey()), Double.toString((double) v));
            }
            boolean first = true;
            for (Map.Entry<String, String> e : sorted.entrySet()) {
                if (!first) { sb.append(','); }
                first = false;
                sb.append(e.getKey()).append(':').append(e.getValue());
            }
        }
        sb.append(']');
    }

    private static void vec(Map<String, String> fields, String key, Vector2f v) {
        if (v == null) {
            fields.put(key + ".x", "\"null\"");
            fields.put(key + ".y", "\"null\"");
        } else {
            fields.put(key + ".x", num(v.x));
            fields.put(key + ".y", num(v.y));
        }
    }

    private static void vecErr(Map<String, String> fields, String key, Throwable t) {
        String e = err(t);
        fields.put(key + ".x", e);
        fields.put(key + ".y", e);
    }

    private static String err(Throwable t) {
        return json("err:" + t.getClass().getSimpleName());
    }

    /**
     * One subject's walk as a single log line; the differ scans for the embedded JSON object.
     * A non-null {@code age} appends {@code "age":{...}} after {@code "n"} — per-tier
     * ticks-stale ({@code walkTick - storedTick}; mounts = worst = {@code tick - mountTickMin});
     * NEVER_ASSERTED tiers are omitted, a null {@code age} omits the whole object (absent = unmeasured).
     * Old differs ignore the extra property (additive-tolerant JSON).
     * A non-null {@code underFire} appends {@code "uf":true|false} after the
     * age object (own-ship subject only; same additive-tolerant precedent).
     */
    private static void emit(long tick, String subject, String key, Map<String, String> fields,
            SnapshotChunkMerger.TierTicks age, Boolean underFire) {
        StringBuilder sb = new StringBuilder(fields.size() * 40 + 128);
        sb.append("{\"event\":\"parity_walk\",\"side\":").append(SIDE_JSON)
          .append(",\"tick\":").append(tick)
          .append(",\"subject\":").append(json(subject))
          .append(",\"key\":").append(json(key))
          .append(",\"n\":").append(fields.size());
        if (age != null) {
            sb.append(",\"age\":{");
            boolean firstAge = true;
            firstAge = appendAge(sb, "core", tick, age.coreTick(), firstAge);
            firstAge = appendAge(sb, "flux", tick, age.fluxTick(), firstAge);
            firstAge = appendAge(sb, "mounts", tick, age.mountTickMin(), firstAge);
            firstAge = appendAge(sb, "alleg", tick, age.allegianceTick(), firstAge);
            firstAge = appendAge(sb, "eng", tick, age.engineTick(), firstAge);
            firstAge = appendAge(sb, "man", tick, age.maneuverTick(), firstAge);
            firstAge = appendAge(sb, "sys", tick, age.systemTick(), firstAge);
            appendAge(sb, "cloak", tick, age.cloakTick(), firstAge);
            sb.append('}');
        }
        if (underFire != null) {
            sb.append(",\"uf\":").append(underFire.booleanValue());
        }
        sb.append(",\"fields\":{");
        boolean first = true;
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (!first) { sb.append(','); }
            first = false;
            sb.append(json(e.getKey())).append(':').append(e.getValue());
        }
        sb.append("}}");
        PilotWatchLog.info(sb.toString());
    }

    /** One per-tier ticks-stale entry; a NEVER_ASSERTED tier is omitted (absent = unmeasured). */
    private static boolean appendAge(StringBuilder sb, String tier, long tick, long storedTick,
            boolean first) {
        if (storedTick == SnapshotChunkMerger.NEVER_ASSERTED) {
            return first;
        }
        if (!first) {
            sb.append(',');
        }
        sb.append('"').append(tier).append("\":").append(tick - storedTick);
        return false;
    }

    private static String json(String s) {
        if (s == null) { return "\"null\""; }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) { sb.append(String.format("\\u%04x", (int) c)); }
                    else { sb.append(c); }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static int resolveWalkEvery() {
        try {
            String raw = System.getProperty("cc.parity.walkEvery");
            if (raw == null || raw.isBlank()) {
                raw = System.getenv("CC_PARITY_WALK_EVERY");
            }
            if (raw == null || raw.isBlank()) {
                return 0;
            }
            int n = Integer.parseInt(raw.trim());
            return n > 0 ? n : 0;
        } catch (Throwable t) {
            // 1290 (81) R5: an unparseable knob turns the walker OFF masked as deliberate "off".
            FallbackAnnounce.announceOnce("r5-walk-every-unparseable", "cc.parity.walkEvery"
                    + " unparseable (" + t.getClass().getSimpleName() + ") -> walker OFF");
            return 0; // unreadable config = OFF (fail-closed: proven lanes untouched)
        }
    }

    /** Budget-knob resolve (the {@link #resolveWalkEvery} idiom: sysprop, then env).
     *  Absent or non-positive = the compiled default — never OFF: the budgets are flood
     *  ceilings, not gates, so a bad knob must not silence the walker. Unparseable announces
     *  LOUD (the R5 masking law) and falls back. */
    private static int resolveBudget(String prop, String env, int fallback) {
        try {
            String raw = System.getProperty(prop);
            if (raw == null || raw.isBlank()) {
                raw = System.getenv(env);
            }
            if (raw == null || raw.isBlank()) {
                return fallback;
            }
            int n = Integer.parseInt(raw.trim());
            return n > 0 ? n : fallback;
        } catch (Throwable t) {
            FallbackAnnounce.announceOnce("walker-budget-unparseable-" + env, prop
                    + " unparseable (" + t.getClass().getSimpleName() + ") -> default "
                    + fallback);
            return fallback;
        }
    }
}
