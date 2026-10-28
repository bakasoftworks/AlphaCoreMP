package cc.starsector.client;

import com.fs.starfarer.api.combat.DroneLauncherShipSystemAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipSystemSpecAPI;

import org.json.JSONObject;

/**
 * The entity-SPAWNING system PRESS GATE: the chosen fix
 * for the chronic
 * {@code autonomous_discharge} axis-6 bar. The foe/replica system lane mirrors the authoritative
 * window by pressing the replica's own {@code useSystem()}; for a system whose script SPAWNS
 * ENTITIES (flare launchers, drone launchers, mine strike, mote attractor, drone strike) that press
 * mints REAL local ordnance on top of what the wire already delivers — one live match
 * measured 60 counted discharges, EVERY one a system-hinted flare launcher (class A
 * CONFIRMED). The wire is already the faithful replica-presentation lane for that ordnance
 * ({@code 743} presented adds, all consumed), so the fix is to NOT press: the local press adds only
 * duplicates.
 *
 * <p>Why a collaborator and not more {@code PredictReconcileCombatPlugin} lines:
 * NEW-LANE LAW — a new concern is born as its own class, so the PRCP relay surface stays thin (1
 * field + the two press sites + the meter classify + the seed arm + the round reset) and the
 * 9500-nb backstop margin is not spent. Every body is {@code try}-wrapped and fails OPEN: a system
 * this gate cannot classify presses EXACTLY as it did pre-gate.
 *
 * <p><b>What it owns</b>:
 * <ul>
 * <li><b>The classifier</b> ({@link #isSpawnFamily}), zero reflection — the loader-B law bans
 *     {@code java.lang.reflect.*} from mod code. PRIMARY = {@code sys.getSpecAPI().getSpecJson()}:
 *     the VERBATIM parsed {@code .system} JSON the engine's own enums were built from, so the strings
 *
 *     cannot drift from engine truth — {@code type ∈ }{@link #SPAWN_TYPES} or
 *     {@code aiType ∈ }{@link #SPAWN_AI_TYPES}. Proven against the full 65-file vanilla
 *     {@code .system} dump: {@code WEAPON} = all 5 flare/canister systems, {@code DRONE_LAUNCHER} =
 *     all 8 drone systems, the aiType trio = {@code mine_strike}/{@code mine_strike_station}/
 *     {@code mote_control}/{@code drone_strike}. FALLBACK (JSON null/empty — a modded or
 *     runtime-minted spec) = the API-surface shape test. Deliberately NOT used: {@code weapon_data.csv}
 *     {@code tags} (only defensive/offensive/restricted live there), the engine-internal {@code getType()} via
 *     MethodHandles (the JSON already hands it over), and {@code AIHints.SYSTEM} — that one is
 *     POST-spawn and stays {@link LocalFireCensus}'s job, the instrument that VERIFIES this gate.</li>
 * <li><b>The counters</b> {@link #gateSkippedPresses} / {@link #gateSkippedSeeds} /
 *     {@link #gatedFrames} and the {@link #meterTokens} fragment the census rides onto the
 *     {@code predict B3 eventReplay-meter} line the axis-6 grader parses.</li>
 * </ul>
 *
 * <p><b>The graded channel must not be poisoned</b>: a gated spawn system never
 * activates locally, so it would DISAGREE on every open-window frame. The meter classifies FIRST —
 * gated-family frames go to {@link #gatedFrames} and agree/disagree grade only the un-gated
 * population.
 *
 * <p><b>Scope</b>: the foe/replica press MIRROR only. The player's OWN ship is client-predicted
 * (the local engine runs the pressed system immediately) and reconciled through the own-discharge /
 * mtrack adopt-retire lanes — untouched here, and structurally free of the double-mint anyway.
 * Threading contract: render-thread only, like every sibling collaborator.
 */
final class SpawnSystemGate {

    /**
     * The gate, compiled ON: the fix IS the decided one, so shipping
     * it off would ship the measured bug. E3 hardwire; the
     * {@code cc.predict.foeSpawnPressGate} on|off knob-resolved shape (legacy press-through
     * as the A/B lever) is in git.
     */
    static final boolean FOE_SPAWN_PRESS_GATE = true;

    /** {@code .system} {@code "type"} values whose script spawns entities the wire already carries. */
    private static final java.util.Set<String> SPAWN_TYPES = new java.util.HashSet<String>(
            java.util.Arrays.asList("WEAPON", "DRONE_LAUNCHER"));
    /** {@code .system} {@code "aiType"} values whose script spawns entities under a non-spawn type. */
    private static final java.util.Set<String> SPAWN_AI_TYPES = new java.util.HashSet<String>(
            java.util.Arrays.asList("MINE_STRIKE", "MOTE_ATTRACTOR", "DRONE_STRIKE"));
    /**
     * The SCRIPT-SPAWN id list: {@code STAT_MOD} systems whose STATS SCRIPT
     * calls {@code spawnShipOrWing}, invisible to the type/aiType classifier above (the JSON says
     * {@code STAT_MOD}/{@code CUSTOM}|{@code NONE}; the spawn lives in Java). Measured: a threat
     * match pressed {@code construction_swarm} 10x locally and each press minted an
     * unbound twin of the swarm the wire already delivered; module 8's phantom hits
     * were two of those twins. Provenance, one line each:
     * <ul>
     * <li>{@code construction_swarm} — {@code threat/ConstructionSwarmSystemScript.launchSwarm}
     *     ({@code manager.spawnShipOrWing(CONSTRUCTION_SWARM_WING …)}, {@code setDoNotRender})
     *     + the constructed hull via {@code ThreatShipConstructionScript.spawnShip}.</li>
     * <li>{@code tenebrous_expulsion} — {@code dweller/TenebrousExpulsionSystemScript
     *     .fireShroudedEjecta} ({@code spawnShipOrWing(EJECTA_WING …)}, fired per REFIRE_DELAY
     *     while ACTIVE, {@code apply}); shrouded = a Stage C category.</li>
     * <li>{@code chiral_figment} — {@code ChiralFigmentStats} ({@code spawnShipOrWing}
     *     of the figment variant); {@code aiType NONE}.</li>
     * </ul>
     * The full api grep of {@code spawnShipOrWing(} = 8 sites: these three, the two projectile
     * on-hit launchers ({@code SwarmLauncherEffect}, {@code VortexLauncherEffect} — wrapped
     * by {@code ClientEveryFrameEffectSwap}), {@code ThreatShipConstructionScript} (reached
     * only via the swarm press), and {@code ThreatShipReclamationScript} (an engine plugin on
     * hulks, NO press — outside any press gate; the {@code DamageWitness} stranger census is what
     * names it if it mints locally). Checked FIRST in {@link #classify} so the verdict cannot be
     * out-voted by the JSON. Law: a local {@code useSystem()} mirror is safe only for
     * systems whose scripts spawn NOTHING.
     * <li>{@code energy_lash} — {@code threat/EnergyLashSystemScript.apply}
     *     spawns a DAMAGING arc entity per firing ({@code spawnEmpArc} EMP 1500 at an enemy,
     *     {@code spawnEmpArcPierceShields} at an ally) picked by a LOCAL all-ships search
     *     ({@code findTarget} → {@code Misc.findClosestShipTo}), then {@code applyEffectToTarget} (a
     *     local phase overload / the ally's system fired / cooldowns). Measured: one threat test flight
     * pressed the overseer replica 29×, activation at tick 8612 (state OUT @8677)
     *     — the local engine lashed on its own while the wire's {@code ARC_HIT} flavor 3/4 already
     *     carries every arc and the per-mount state wire / the own-flux slave carry the EMP + overload.</li>
     * </ul>
     * the SIBLING AUDIT of every other STAT_MOD/CUSTOM stats script (29 ids,
     * each read in full, one line each; the cosmetic each gate loses is a rider candidate;
     * {@code nova_burst} + {@code orion_device} re-classified later — their row below):
     * <ul>
     * <li>{@code acausaldisruptor} — {@code AcausalDisruptorStats}: a LOCAL nearest-enemy
     *     pick ({@code Misc.findClosestShipEnemyOf}) then {@code beginOverloadWithTotalBaseDuration}
     *     on THAT ship + an every-frame plugin — the player's own hull overloads.</li>
     * <li>{@code entropyamplifier} — {@code EntropyAmplifierStats}: the same local pick,
     *     then hull/armor/shield/EMP damage-taken ×1.5 on the victim every frame + a HUD status
     *     line the server never sent.</li>
     * <li>{@code interdictor} — {@code InterdictorArrayStats}: the local pick, then
     *     {@code Collections.shuffle} + {@code engine.disable()} on the victim's engines.</li>
     * <li>{@code darkened_gaze} — {@code dweller/DarkenedGazeSystemScript} force-fires the eye's
     *     nine decorative {@code abyssal_glare} beams; each beam's {@code AbyssalGlareEffect}
     *     {@code spawnDamagingExplosion} (PROJECTILE_FF).</li>
     * <li>{@code fragment_volley} — {@code threat/FragmentVolleySystemScript} force-fires every
     *     fragment launcher per ACTIVE frame (ammo topped): real local missiles.</li>
     * <li>{@code incursion_mode} / {@code extraction_protocol} — {@code BaseEnergyLashActivatedSystem}
     *     {@code makeAllGroupsAutofireOneFrame} toggles every weapon group ON and never off
     *     (the local autofire AI fires the replica's guns); extraction adds {@code VENT_FLUX} + an
     *     ammo refill per OUT frame.</li>
     * <li>{@code nova_burst} / {@code orion_device} — RE-CLASSIFIED, no
     *     longer listed: the mod's {@code .system} files install {@link CcWrapNovaBurstStats} /
     *     {@link CcWrapOrionDeviceStats}, whose {@code spawnBomb} mints NOTHING on a served non-own
     *     ship (the served bomb rides the wire, adopted sourceless) and whose impact plugin is the
     *     FX-only mirror ({@link CcServedBombMirror}: pusher impulse, particles, flash, jitter — no
     *     ship motion/stat/command write; the wire pose carries the lunge). The press goes through,
     *     so the engine's own system machine runs the window cosmetics (beams, plate, useSound).
     *     </li>
     * <li>{@code recalldevice} — {@code RecallDeviceStats} {@code setPhased(true)} / {@code land()}
     *     on OTHER ships (the wing's fighters); {@code reservewing} — {@code ReserveWingStats}
     *     {@code bay.setExtraDeployments} = an engine-minted extra wing; {@code targetingfeed} —
     *     {@code TargetingFeedStats} weapon-damage mults on OTHER ships (the wing). All three
     *     author nothing on today's replica (wings unbound) — gated by the law, cost nothing.</li>
     * </ul>
     * NOT gated (self-only, matrix rows): {@code cryoflux}, {@code damper}, {@code damper_omega},
     * {@code temporalshell}, {@code ammofeed}, {@code highenergyfocus}; {@code lidararray} (self-stat +
     * decorative dish beams = the weapon-lane presentation idiom, MED row); {@code convulsive_lunge}
     * ({@code dweller/ConvulsiveLungeSystemScript}: a self-velocity spring toward its AI-less default
     * {@code mouseTarget} (0,0), reconcile-fought — a motion row, not a spawn); {@code shrouded_vortex}
     * runs NO script at all — its {@code statsScript} line is commented out in the vanilla
     * {@code data/shipsystems/shrouded_vortex.system:6}.
     *
     * <ul>
     */
    static final java.util.Set<String> SCRIPT_SPAWN_SYSTEM_IDS = new java.util.HashSet<String>(
            java.util.Arrays.asList("construction_swarm", "tenebrous_expulsion", "chiral_figment", "energy_lash",
                    // the sibling audit — see the javadoc above, one line per id
                    "acausaldisruptor", "entropyamplifier", "interdictor", "darkened_gaze", "fragment_volley",
                    // nova_burst + orion_device removed (javadoc row)
                    "incursion_mode", "extraction_protocol", "recalldevice",
                    "reservewing", "targetingfeed"));
    /** W5-D6 — {@code .system} {@code "type"} values the engine binds to one internal
     * system class (TELEPORTER ordinal 6
     * and DISPLACER ordinal 8 BOTH construct it): activation reads a chargeup-computed jump
     * destination. Engine-truth by TYPE (covers {@code energy_conversion} + mods the id list
     * misses); the {@link TeleportJumpLane#TELEPORT_CLASS_SYSTEM_IDS} lockstep set is the
     * JSON-unreadable fallback only. */
    private static final java.util.Set<String> TELEPORT_TYPES = new java.util.HashSet<String>(
            java.util.Arrays.asList("TELEPORTER", "DISPLACER"));
    /** Per-run gate log cap (the sibling-lane discipline; counters continue past it). */
    private static final int GATE_LOG_MAX = 40;
    /** Runaway guard on the verdict cache (a roster fields a handful of distinct system ids). */
    private static final int VERDICT_CACHE_MAX = 64;

    /**
     * The owning plugin — the backref every sibling collaborator takes as its sole constructor
     * argument ({@link PressShadowProbe}/{@link LocalFireCensus}). Held for symmetry and for the
     * cross-lane reads a later stroke will want; the constructor stores it and does nothing else
     * (the PRCP field initializer leaks {@code this} mid-construction exactly as the siblings do).
     */
    @SuppressWarnings("unused")
    private final PredictReconcileCombatPlugin plugin;

    /** Verdict per system id — content truth, so it outlives the round (never re-derived). */
    private final java.util.HashMap<String, Boolean> verdictBySysId =
            new java.util.HashMap<String, Boolean>();
    /** Per-ROUND log-throttle keys ({@code sysId#arm}) so a 540 fps press storm cannot flood. */
    private final java.util.HashSet<String> loggedThisRound = new java.util.HashSet<String>();

    private int gateSkippedPresses;  // presses the gate ate (both arms)
    private int gateSkippedSeeds;    // forceState-into-IN/ACTIVE seeds the gate ate
    private int gateSkippedTeleportSeeds; // W5-D6: teleport-class IN/ACTIVE seeds ate
    private int gatedFrames;         // meter frames removed from the agree/disagree population
    private int gateLogLines;        // run-lifetime log budget

    SpawnSystemGate(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * The PRESS, routed through the gate at both {@code maintainFoeSystemState}
     * arms (the APPLY press and the CANCEL twin, gated together on one family key
     * by design). Returns TRUE when the press actually happened, so the caller's
     * counters/stamps/log fire exactly as before; FALSE when the gate ate it.
     *
     * <p>The classification is {@code try}-wrapped and fails OPEN — a system this gate cannot read
     * must press exactly as it did pre-gate. {@code foe.useSystem()} deliberately sits OUTSIDE that
     * guard so its behavior (including a throw, which the caller's own {@code try} has always
     * caught) is byte-identical to the pre-gate lane.
     */
    boolean press(ShipAPI foe, ShipSystemAPI sys, long simTick, String arm) {
        try {
            if (gates(sys)) {
                gateSkippedPresses++;
                logGate("SKIP-PRESS", sys, arm, " tick=" + simTick
                        + " ship=" + Integer.toHexString(System.identityHashCode(foe))
                        + " (entity-spawning family — the wire already delivers the ordnance)");
                return false;
            }
        } catch (Throwable ignore) {
            // fail OPEN: an unclassifiable system presses exactly as it did pre-gate
            // 1290 (81) S1: fail-open = possible double-mint press-through; ONE shared seam —
            // an unclassifiable spec throws identically in all three arms.
            FallbackAnnounce.announceOnce("s1-spawn-gate-classify-threw", "classify threw "
                    + ignore.getClass().getSimpleName() + " -> gate fails OPEN");
        }
        foe.useSystem();
        return true;
    }

    /**
     * The meter classify, called FIRST on every {@code meterCompare} frame.
     * Returns TRUE (and counts the frame) when this hull's system is a gated spawn family, whose
     * local state can never track the authoritative window once the press is gone: grading those
     * frames would mint a standing disagree storm and hide the real lane's health. Un-gated frames
     * return FALSE and grade normally.
     */
    boolean noteGatedFrame(ShipSystemAPI sys) {
        try {
            if (gates(sys)) {
                gatedFrames++;
                return true;
            }
        } catch (Throwable ignore) {
            // fail OPEN: an unclassifiable system grades exactly as it did pre-gate
            // 1290 (81) S1: shared seam — see press().
            FallbackAnnounce.announceOnce("s1-spawn-gate-classify-threw", "classify threw "
                    + ignore.getClass().getSimpleName() + " -> gate fails OPEN");
        }
        return false;
    }

    /**
     * The SEED arm of the same family: {@code seedFoeSystemState} can
     * {@code forceState} a mid-window joiner's spawn system straight INTO {@code IN}/{@code ACTIVE},
     * which runs the script and mints the same duplicate ordnance the press does. Returns TRUE (and
     * counts) only for that arm — ammo seeding and the {@code IDLE}/{@code OUT}/{@code COOLDOWN}
     * {@code forceState} still run, since those phases spawn nothing and keep the replica's cooldown
     * honest.
     *
     * <p>W5-D6 — the same arm also refuses {@code IN}/{@code ACTIVE} seeds of
     * TELEPORT-class systems ({@link TeleportJumpLane#TELEPORT_CLASS_SYSTEM_IDS}): their
     * activation reads a chargeup-computed jump destination that {@code forceState} never
     * computes — a null-destination engine NPE, fatal, measured live (flight 3's crash).
     */
    boolean gatesSeed(ShipSystemAPI sys, ShipSystemAPI.SystemState auth) {
        try {
            if (auth == ShipSystemAPI.SystemState.IN || auth == ShipSystemAPI.SystemState.ACTIVE) {
                if (gates(sys)) {
                    gateSkippedSeeds++;
                    logGate("SKIP-SEED", sys, "SEED", " auth=" + auth
                            + " (forceState into an open window would run the spawn script)");
                    return true;
                }
                // W5-D6: a teleport-class system's activation dereferences the
                // destination its CHARGEUP computes — forceState
                // into IN/ACTIVE skips that chargeup, and the null jump destination is a fatal
                // engine NPE at the IN→ACTIVE edge (~100 ms after adopt, flight 3's crash).
                // Ammo + IDLE/OUT/COOLDOWN seeding still run; the press lane (engine-native
                // chargeup) carries the NEXT window — one bounded first-window disagree instead
                // of a dead client.
                if (sys != null && isTeleportFamily(sys)) {
                    gateSkippedTeleportSeeds++;
                    logGate("SKIP-SEED-TELEPORT", sys, "SEED", " auth=" + auth
                            + " (forceState skips the chargeup that computes the jump destination)");
                    return true;
                }
            }
        } catch (Throwable ignore) {
            // fail OPEN: an unclassifiable system seeds exactly as it did pre-gate
            // 1290 (81) S1: shared seam — see press().
            FallbackAnnounce.announceOnce("s1-spawn-gate-classify-threw", "classify threw "
                    + ignore.getClass().getSimpleName() + " -> gate fails OPEN");
        }
        return false;
    }

    /** Knob AND family: the single predicate every arm above consults. */
    private boolean gates(ShipSystemAPI sys) {
        return FOE_SPAWN_PRESS_GATE && sys != null && isSpawnFamily(sys);
    }

    /** W5-D6 — the SEED-arm teleport classifier: spec-JSON {@code type} PRIMARY
     * (the {@link #TELEPORT_TYPES} engine binding), lockstep id set FALLBACK when the JSON is
     * unreadable. Uncached — seeds are a one-shot per entity per join/round, a handful per
     * adopt. Throws propagate to {@code gatesSeed}'s shared fail-OPEN seam. */
    private boolean isTeleportFamily(ShipSystemAPI sys) {
        ShipSystemSpecAPI spec = sys.getSpecAPI();
        JSONObject json = spec != null ? spec.getSpecJson() : null;
        if (json != null) {
            String type = json.optString("type", "");
            if (!type.isEmpty()) {
                return TELEPORT_TYPES.contains(type);
            }
        }
        return sys.getId() != null
                && TeleportJumpLane.TELEPORT_CLASS_SYSTEM_IDS.contains(sys.getId());
    }

    /**
     * The cached classifier. The verdict is a property of the SPEC, not of the ship or the round,
     * so it is derived once per system id and kept (the cache is bounded purely as a runaway guard
     * — a real roster fields a handful of distinct ids).
     */
    private boolean isSpawnFamily(ShipSystemAPI sys) {
        String id = sys.getId();
        String key = id == null ? "(null)" : id;
        Boolean cached = verdictBySysId.get(key);
        if (cached != null) {
            return cached.booleanValue();
        }
        boolean scriptListed = SCRIPT_SPAWN_SYSTEM_IDS.contains(key);
        boolean verdict = scriptListed || classify(sys);
        if (verdictBySysId.size() < VERDICT_CACHE_MAX) {
            verdictBySysId.put(key, Boolean.valueOf(verdict));
        }
        if (gateLogLines < GATE_LOG_MAX) {
            gateLogLines++;
            PilotWatchLog.info("predict replica spawn-gate classify sysId=" + key
                    + " spawnFamily=" + verdict + " knob=" + (FOE_SPAWN_PRESS_GATE ? "on" : "off")
                    + " via=" + (scriptListed ? "script-spawn-id-list" : "spec-json")
                    + (gateLogLines == GATE_LOG_MAX ? " [gate log cap; counters continue]" : ""));
        }
        return verdict;
    }

    /**
     * PRIMARY = the engine's own parsed {@code .system} JSON; FALLBACK (null/empty JSON) = the
     * API-surface shape test: a system that carries a weapon id, is a
     * {@link DroneLauncherShipSystemAPI}, or declares drones IS a spawner whatever its type string
     * says. A spec that reads back neither way classifies un-gated (fail OPEN).
     */
    private boolean classify(ShipSystemAPI sys) {
        ShipSystemSpecAPI spec = sys.getSpecAPI();
        if (spec == null) {
            // 1290 (81) S2: no spec readable -> API-shape classify fallback (per system id).
            FallbackAnnounce.announceOnce("s2-spawn-gate-spec-unreadable#" + sys.getId(),
                    "null spec -> API-shape classify fallback");
            return sys instanceof DroneLauncherShipSystemAPI;
        }
        JSONObject json = spec.getSpecJson();
        if (json != null) {
            String type = json.optString("type", "");
            String aiType = json.optString("aiType", "");
            if (!type.isEmpty() || !aiType.isEmpty()) {
                return SPAWN_TYPES.contains(type) || SPAWN_AI_TYPES.contains(aiType);
            }
        }
        // 1290 (81) S2: null/empty spec JSON -> the same API-shape fallback (per system id).
        FallbackAnnounce.announceOnce("s2-spawn-gate-spec-unreadable#" + sys.getId(),
                "null/empty spec JSON -> API-shape classify fallback");
        return spec.getWeaponId() != null
                || sys instanceof DroneLauncherShipSystemAPI
                || spec.getMaxDrones() > 0
                || spec.getDroneVariant() != null;
    }

    /** Capped + per-round-deduped gate line (one per system id per arm per round). */
    private void logGate(String what, ShipSystemAPI sys, String arm, String tail) {
        try {
            String id = sys.getId() == null ? "(null)" : sys.getId();
            if (gateLogLines >= GATE_LOG_MAX || !loggedThisRound.add(id + "#" + arm)) {
                return;
            }
            gateLogLines++;
            PilotWatchLog.info("predict replica spawn-gate " + what + " sysId=" + id + " arm=" + arm
                    + tail
                    + (gateLogLines == GATE_LOG_MAX ? " [gate log cap; counters continue]" : ""));
        } catch (Throwable ignore) {
            // diagnostic only — never break the lane
        }
    }

    /**
     * The gate's fragment of the {@code predict B3 eventReplay-meter} line, appended by
     * {@link LocalFireCensus#meterTokens} (the census is the instrument that VERIFIES this gate, so
     * the two ride one line the axis-6 grader already parses; {@code Get-WvKv} is a name-agnostic
     * whitespace tokenizer, so new keys ride for free). Key names are PINNED — the grader parses
     * them by name.
     */
    String meterTokens() {
        return " predict_event_rebase_foe_system_spawn_press_gate="
                + (FOE_SPAWN_PRESS_GATE ? "on" : "off")
                + " predict_event_rebase_gate_skipped_presses=" + gateSkippedPresses
                + " predict_event_rebase_gate_skipped_seeds=" + gateSkippedSeeds
                + " predict_event_rebase_foe_system_gated_frames=" + gatedFrames
                + " predict_event_rebase_gate_skipped_teleport_seeds=" + gateSkippedTeleportSeeds;
    }

    /**
     * The round boundary (PRCP {@code finishMatchReset}, beside {@link LocalFireCensus#resetRound}):
     * the per-round log dedup clears so each round's first gated press per system is visible again,
     * while the run-lifetime {@link #GATE_LOG_MAX} budget and the cumulative counters survive (the
     * PressShadowProbe per-round-sub-cap discipline). The verdict cache is content truth and is NOT
     * cleared — a spec cannot change mid-session.
     */
    void resetRound() {
        loggedThisRound.clear();
    }
}
