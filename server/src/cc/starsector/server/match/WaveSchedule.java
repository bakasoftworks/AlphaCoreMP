package cc.starsector.server.match;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import java.util.function.ToDoubleFunction;

/**
 * The mode-agnostic wave-spawn schedule: WHICH extra AI hulls fly into a live
 * round and WHEN. Shape-keyed at boot ({@link #forShape}); the serve loop drains due waves
 * pre-advance ({@link #drainDue}) and spawns each row through the engine's own reinforcement
 * lane ({@code RealEngineDemoRoster.midDeployWaveShip} — never {@code spawnShipOrWing}, whose
 * specId dedupe re-deploys the same member); the wave-clear round-end trigger consults
 * {@link #drained()} so inter-wave zero-enemy gaps can never latch {@code matchOver}.
 * Designed to transfer to the later fortress legs (owner mode-agnostic
 * guideline): the plan is data, the seam is generic.
 *
 * <p>The plans are STATIC data compiled per shape: wave 1 rides the descriptor's
 * t=0 AI rows (NOT in this schedule). {@code bastion:wave} compiles the CONTINUOUS chain
 * (one continuous fight broken by waves): wave 2 = 2× lumen +
 * 1× glimmer, due on wave-1 clear or at the {@link #WAVE_2_FALLBACK_DUE_SEC} timer, whichever
 * first; waves 3–4 are spawn-on-clear ONLY ({@link #NO_TIMER} — survival pacing never piles a
 * timer wave onto a live field). {@code bastion:wave:rig} keeps the DD1 single-scheduled-wave
 * plan (the headless rig proves the fly-in/clear/latch machinery; chain semantics are
 * contract-pinned game-free). {@code bastion:fortress} compiles the ESCALATING
 * chain (waves 2–8, 37 hulls, DP 0.35×→2.5× of seat DP with AI-core officer twins) and is the
 * one shape that LATCHES round-end on a drained clear ({@link #latchesRoundEndOnWaveClear} —
 * the DEFEND victory). The static total IS the stable-roster bound term — an endless
 * generator needs slot reuse or a cap before it ships. Real dynamic wave
 * descriptors (descriptor v2) stay a later surface.
 *
 * <p>Game-free, sim-thread only (no locking): {@code drainDue} runs in the serve frame loop and
 * {@code observeEnemyLive}/{@code drained} inside the same thread's snapshot capture. A spawn
 * consumes the standing enemy-live observation (the world is about to change), so chained
 * clear-due waves each wait for a fresh post-spawn capture.
 */
public final class WaveSchedule {

    /**
     * Wave 2's timer fallback, seconds of round sim time: the fly-in proof
     * must land even if the humans never clear wave 1, and mixing the waves at 90 s keeps the
     * total field tiny (foe DP 10+13 vs seat DP 48). A future FEEL dial — static v1,
     * deliberately not knob-housed yet. Continuous wave shapes ONLY — the fortress chain
     * fronts {@link #FORTRESS_W2_FALLBACK_DUE_SEC} instead (its t=0 foe rows
     * are gone, so there is no wave 1 for a timer wave to pile onto).
     */
    public static final float WAVE_2_FALLBACK_DUE_SEC = 90.0f;

    /**
     * The FORTRESS chain's w2 timer fallback (paired with the t=0
     * glimmer-row removal): with no descriptor foe rows the wave-1-clear kickstart can never
     * fire ({@code observeEnemyLive} stays ungated-off behind match-over suppression until a
     * second side is SEEN), so this timer alone opens the fight — 25 s per the front-loaded-
     * attention rule (a 90 s empty opening spends the watcher's attention on nothing). The
     * pile-on concern is structurally nil here: the field w2 lands on is empty by construction.
     * Deliberately fortress-scoped — the closed carrier/strike/mirv baselines keep their 90 s
     * single-variable behavior. Same static-v1 FEEL-dial status as
     * {@link #WAVE_2_FALLBACK_DUE_SEC}.
     */
    public static final float FORTRESS_W2_FALLBACK_DUE_SEC = 25.0f;

    /**
     * Due-tick sentinel for a spawn-on-clear-ONLY wave: the timer leg never fires it.
     * Explicitly guarded in {@link #drainDue} — never compared arithmetically.
     */
    private static final long NO_TIMER = Long.MAX_VALUE;

    /**
     * Seconds enemy-live must read ZERO SUSTAINED before a spawn-on-clear
     * wave drains — the omega anti-pile-up hold. A dying Omega parent's ShardSpawner children
     * enter play only {@code ShardSpawnerData.delay} = 2.0+rand ∈ 2–3 s AFTER the parent hulks
     * ({@code SPAWN_TIME}=4.0F is the post-spawn FADE-IN —
     * children are already in play then), so a zero read the tick the parents die is NOT a
     * cleared field: in one recorded session the next wave spawned ≤1 tick after each clear/skip
     * and the children faded in on top (runaway to 313 ships). 4+2 s covers the worst child delay with fade
     * margin — conservative on purpose (never trim toward the 2–3 s true gap).
     * The hold binds EVERY spawn-on-clear wave, category-blind like the clear leg itself.
     */
    public static final float CLEAR_HOLD_SEC = 6.0f;

    /**
     * Seconds between the operator skip verb's kill sweep and its deferred
     * corpse sweep ({@code SeatRebind.removeSideHulks}). Lower bound: the death fan needs the
     * corpse PRESENT a few captures — EDSP absence classification, growth-seam removal truth,
     * and the fracture detector's family sighting (1–2 ticks after death) all read it; sweeping
     * the same tick would strand un-adopted, never-removed local corpses on every client. Upper
     * bound: a dead Omega parent mints its shard children {@code ShardSpawnerData.delay} =
     * 2.0+rand ∈ 2–3 s after hulking — the sweep must land first. 1.0 s sits between with ≥2×
     * margin both ways. Lives here beside {@link #CLEAR_HOLD_SEC}: both are the
     * wave-timing law, game-free and contract-pinned.
     */
    public static final float SKIP_HULK_SWEEP_DELAY_SEC = 1.0f;

    /**
     * The hold in ticks at {@code ticksPerSecond} — the ONE conversion the mint and
     * the contract pins both call (never a hand-inlined 360).
     */
    public static long clearHoldTicks(int ticksPerSecond) {
        return (long) Math.ceil((double) CLEAR_HOLD_SEC * ticksPerSecond);
    }

    /**
     * Fly-in stagger (<i>"heavy waves fly in on top of each
     * other — stagger the fly-in by a random few seconds"</i>): the per-hull FLY-IN stagger
     * WINDOW, seconds, the serve-side default of the {@code serve.waveFlyInStaggerSec} knob.
     * When a wave stages ({@link #drainDue}), row 1 releases AT the due tick — the wave's
     * arrival never moves, so the timer/clear/force laws keep their exact tick — and every later
     * row draws a uniform offset in (0, window] from a seeded generator, sorted ascending so the
     * authored row order (finale mothership FIRST, slot names ascending) survives the spread.
     * The spawn tick IS the fly-in tick (the engine burns each hull in from the edge at spawn),
     * so delaying the spawn on the server delays the fly-in on every client through the
     * ordinary roster-append fan: no wire field, no client change, 1:N-cheap. Deterministic per
     * (category, wave index, round ordinal, stage tick) — a replayed round re-draws the same
     * spread. The CLASS default is 0 = off = the (104)(c) frame-paced drain byte-identical (the
     * pre-stagger contract rows bind it); the serve applies the knob via
     * {@link #setFlyInStaggerSeconds}. A tunable FEEL dial.
     */
    public static final float DEFAULT_FLY_IN_STAGGER_SEC = 5.0f;
    /** Ceiling of {@link #setFlyInStaggerSeconds} — reject-never-clamp (a typo must never hide a wave for a minute). */
    public static final float MAX_FLY_IN_STAGGER_SEC = 30.0f;

    /**
     * Curve multipliers on the boot-computed seat DP, one per scheduled wave in
     * order (w2..w8). AUTHORED data (a tunable curve); the fortress serve boot folds the
     * ENGINE's own per-hull DP over the compiled chain and refuses a boot that drifts outside
     * ±15% of these — the authored side of the chain is ids and counts, never DP numbers.
     * PRIVATE + copy-on-read ({@link #fortressCurveMultipliers}): authored data a caller could
     * mutate is not authored data.
     */
    private static final double[] FORTRESS_CURVE_MULTIPLIERS =
            {0.35d, 0.5d, 0.7d, 0.9d, 1.25d, 1.7d, 2.5d};

    /** Tolerance band the fortress DP curve check holds the authored chain to. */
    public static final double FORTRESS_CURVE_TOLERANCE = 0.15d;

    /**
     * Hard ceiling on GENERATED rows per scaled wave — a defensive
     * fail-closed bound, never a tuning knob. The config parse already caps
     * {@code match.fortressDp} at a total that lands far under this with the current chain;
     * only an engine-data shift (per-hull DP collapsing toward zero) could reach it, and that
     * is exactly the silent-reshape class the fortress boot check exists to refuse loudly.
     */
    private static final int FORTRESS_SCALED_WAVE_ROW_CAP = 400;

    /**
     * Fortress wave scale (<i>"maybe 50% more DP in the
     * earlier waves, and 100% more DP in the later waves"</i>): the per-wave SCALE law for the
     * {@code match.fortressWaveScale} knob — exactly one factor per scheduled wave (w2..w8),
     * each a finite decimal in [{@value #FORTRESS_WAVE_SCALE_MIN}, {@value #FORTRESS_WAVE_SCALE_MAX}],
     * reject-never-clamp. Applied ON TOP of the ratified curve in SCALED mode only (see
     * {@link #fortressWaveTargets}), so {@code match.fortressDp} keeps its "base ordo size"
     * meaning and the row literally encodes the quoted request ({@code 1.5,1.5,1.5,1.75,2,2,2}).
     * Blank/absent = unit = the byte-identical chain (the veto = delete the row).
     */
    public static final double FORTRESS_WAVE_SCALE_MIN = 0.5d;
    /** Upper bound of one {@code match.fortressWaveScale} factor (see {@link #FORTRESS_WAVE_SCALE_MIN}). */
    public static final double FORTRESS_WAVE_SCALE_MAX = 4.0d;

    /**
     * Parse the {@code match.fortressWaveScale} row (the scale law above): {@code null} for
     * blank/absent (= unit); otherwise exactly {@link #FORTRESS_CURVE_MULTIPLIERS}{@code .length}
     * comma-separated factors, each finite and inside the [MIN, MAX] band — anything else
     * THROWS (a mis-typed row must never silently reshape the fight). Game-free so the
     * contract suite pins it.
     */
    public static double[] parseFortressWaveScale(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.trim().split(",", -1);
        int waves = FORTRESS_CURVE_MULTIPLIERS.length;
        if (parts.length != waves) {
            throw new IllegalArgumentException("match.fortressWaveScale must carry exactly "
                    + waves + " comma-separated per-wave factors (w2..w" + (waves + 1)
                    + "); got " + parts.length + " in '" + raw + "'");
        }
        double[] scale = new double[waves];
        for (int i = 0; i < waves; i++) {
            double f;
            try {
                f = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException bad) {
                throw new IllegalArgumentException("match.fortressWaveScale factor " + (i + 1)
                        + " (w" + (i + 2) + ") is not a decimal: '" + parts[i].trim()
                        + "' in '" + raw + "'");
            }
            if (!Double.isFinite(f) || f < FORTRESS_WAVE_SCALE_MIN || f > FORTRESS_WAVE_SCALE_MAX) {
                throw new IllegalArgumentException("match.fortressWaveScale factor " + (i + 1)
                        + " (w" + (i + 2) + ") must be in [" + FORTRESS_WAVE_SCALE_MIN + ", "
                        + FORTRESS_WAVE_SCALE_MAX + "]; got '" + parts[i].trim() + "'");
            }
            scale[i] = f;
        }
        return scale;
    }

    /**
     * The wave
     * CATEGORY — a MODIFIER on the one proven fortress chain selecting WHICH faction's
     * authored wave tables it compiles (a variant of a standing artifact ships as a delta ON
     * it, never a parallel hand-built sibling; the four earlier
     * category descriptors are retired with this). Never a shape, never a descriptor.
     * Non-fortress shapes ignore it (the fortressDp precedent — logged set-but-ignored at
     * boot).
     */
    public static final List<String> WAVE_CATEGORIES =
            List.of("remnant", "derelict", "omega", "threat", "shrouded",
                    // The seven major
                    // factions (tables = FactionWaveTables, hand-authored from vanilla
                    // variant ids — doctrine fleet generation is campaign-only)
                    // and the one-wave Ziggurat boss category (BOSS_ONLY_CATEGORIES).
                    "hegemony", "persean_league", "tritachyon", "luddic_church",
                    "luddic_path", "sindrian_diktat", "pirates", "ziggurat");

    /**
     * Categories whose whole chain is ONE curve-EXEMPT boss wave (the
     * Ziggurat). They never DP-scale (the SCALED mint fields the authored boss row as-is, the
     * boot band check narrates "boss-only" instead of grading 7 waves) and their single wave
     * is timer-armed like any w2 — so a RESET or a fresh round brings the boss on the w2 timer
     * and endless mode brings it back after every clear-and-hold.
     */
    public static final List<String> BOSS_ONLY_CATEGORIES = List.of("ziggurat");

    /** True for a {@link #BOSS_ONLY_CATEGORIES} member (parsed first — unknown THROWS). */
    public static boolean isBossOnlyCategory(String category) {
        return BOSS_ONLY_CATEGORIES.contains(parseWaveCategory(category));
    }

    /**
     * Vanilla-uninflated categories ("make sure that kinda stuff isn't randomized here in
     * our mod in an unvanilla way"): categories whose VANILLA fleets are built WITHOUT a fleet
     * inflater, so the campaign never autofits them either — the wave loadout inflater skips
     * the whole category ({@code skip:vanilla_no_inflater}), on top of the per-hull
     * {@code no_autofit} tag law the engine half copies from {@code DefaultFleetInflater}.
     * In vanilla: omega = {@code CBRemnantPlus}
     * {@code createEmptyFleet("omega")} (every omega hull is {@code no_autofit}-tagged too);
     * threat = {@code DisposableThreatFleetManager} {@code createEmptyFleet("threat")}
     * + {@code setInflater(null)}; shrouded = {@code DwellerCMD}
     * {@code createEmptyFleet("dweller")} + {@code setInflater(null)}. The boss category rides
     * {@link #BOSS_ONLY_CATEGORIES} ({@code TTBlackSite} adds {@code ziggurat_Experimental}
     * to an inflater-less {@code createEmptyFleet}). Remnant, derelict and the seven factions ARE
     * inflated in vanilla ({@code RemnantSeededFleetManager}, {@code SalvageGenFromSeed}
     * → {@code FleetFactoryV3} attaches the inflater).
     */
    public static final List<String> VANILLA_UNINFLATED_CATEGORIES =
            List.of("omega", "threat", "shrouded");

    /** True when vanilla never inflates this category's fleets (parsed first — unknown THROWS). */
    public static boolean isVanillaUninflatedCategory(String category) {
        return VANILLA_UNINFLATED_CATEGORIES.contains(parseWaveCategory(category));
    }

    /**
     * The vanilla FACTION id a wave category inflates with — the categories are
     * named after the {@code .faction} FILES, two of which carry a different {@code id} inside:
     * {@code persean_league.faction} = {@code "persean"}, {@code dweller.faction} (our
     * {@code shrouded}) = {@code "dweller"}; every other category name IS its faction id
     * ({@code remnants.faction} = {@code "remnant"}). The probe found the whole league
     * chain failing on a null faction spec ({@code FactionManager.getFaction} hands back a
     * spec-less placeholder for an unknown id). Parsed first — unknown THROWS.
     */
    public static String vanillaFactionIdFor(String category) {
        String cat = parseWaveCategory(category);
        switch (cat) {
            case "persean_league":
                return "persean";
            case "shrouded":
                return "dweller";
            default:
                return cat;
        }
    }

    /**
     * Parse the {@code match.waveCategory} knob: null/blank = {@code remnant}
     * (the standing chain, byte-identical); anything else must match {@link #WAVE_CATEGORIES}
     * exactly — unknown THROWS (reject-never-clamp, the {@link #parseFortressWaveScale}
     * precedent: a typo'd row must never silently reshape the fight).
     */
    public static String parseWaveCategory(String raw) {
        if (raw == null || raw.isBlank()) {
            return "remnant";
        }
        String category = raw.trim();
        if (!WAVE_CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("match.waveCategory must be one of "
                    + WAVE_CATEGORIES + " (blank = remnant); got '" + raw + "'");
        }
        return category;
    }

    /**
     * ADMIRAL: vanilla's per-enemy-type admiral delta, keyed by the
     * {@code /wave} category. With retreats suppressed everywhere the only
     * remaining vanilla difference is Shrouded's {@code fightToTheLast=true} (permanent full
     * assault + station intercept, {@code DwellerCMD.battleContextCreated},
     * {@code BaseBattleStrategy}); every other category leaves the API default
     * ({@code BattleCreationContext} = false). Ship-level traits (timid hive, aggressive
     * assault) already ride {@code pickShipAI} per hull and need no map here.
     */
    public static boolean fightToTheLast(String category) {
        return "shrouded".equals(parseWaveCategory(category));
    }

    // The category wave-table variant ids — wave-only ids live HERE
    // (MatchDescriptors stays descriptor/officer-scoped). Every id verified on disk at the
    // core data\variants tree (root + derelict\); the trailing DP comment is the
    // engine's own supplies/rec read, probe-confirmed == ship_data.csv.
    // NO officer twins exist for any of these — bare fielding, the "SOME hulls" law.

    /** DERELICT: drone escalation, guardian debut w6. Engine DP in comments. */
    public static final String DERELICT_WARDEN_VARIANT_ID = "warden_Defense"; // DP 3
    public static final String DERELICT_DEFENDER_VARIANT_ID = "defender_PD"; // DP 3
    public static final String DERELICT_PICKET_VARIANT_ID = "picket_Assault"; // DP 3
    public static final String DERELICT_SENTRY_VARIANT_ID = "sentry_FS"; // DP 3
    public static final String DERELICT_BERSERKER_VARIANT_ID = "berserker_Assault"; // DP 6
    public static final String DERELICT_RAMPART_VARIANT_ID = "rampart_Standard"; // DP 15
    public static final String DERELICT_GUARDIAN_VARIANT_ID = "guardian_Standard"; // DP 40
    /**
     * The DERELICT chain's FINALE hull — the Domain-era
     * Survey Mothership (hull hint STATION, six STATION_MODULE children the engine mints at
     * spawn). Fielded as a 9th spawn-on-clear wave AFTER w8 by the NORMAL 3-arg edge wave
     * spawn (engine burn-in, zero teleport); curve-EXEMPT (never in {@link #variantIdsByWave}),
     * and the ONLY row the serve loop's finale-creep author moves toward the fortress
     * ({@link #isFinaleCreepRow}). An adhoc {@code /spawnfoe} of the same id is NOT a finale.
     */
    public static final String DERELICT_MOTHERSHIP_VARIANT_ID =
            "station_derelict_survey_mothership_Standard";
    /**
     * The finale wave's slot-name prefix — continues the authored {@code "CC WAVE w.i"} scheme
     * (the curve is waves 2–8, so {@code 9.} is the finale's alone in BOTH mint modes).
     */
    public static final String DERELICT_FINALE_WAVE_NAME_PREFIX = "CC WAVE 9.";
    /** The category-neutral name of the same finale prefix (every finale is wave 9). */
    public static final String FINALE_WAVE_NAME_PREFIX = DERELICT_FINALE_WAVE_NAME_PREFIX;
    /** The finale wave's mothership slot name (row 1 of the finale wave). */
    public static final String DERELICT_FINALE_SLOT_NAME = DERELICT_FINALE_WAVE_NAME_PREFIX + "1";

    /** The authored slot-id prefix (internal: schedule rows, {@code slot=} log key, contract rows, tooling regexes). */
    public static final String SLOT_NAME_PREFIX = "CC WAVE ";
    /** The PLAYER-VISIBLE form of a wave slot id (only public-facing text drops "CC"). */
    public static final String PUBLIC_WAVE_NAME_PREFIX = "AlphaCoreMP WAVE ";

    /**
     * W11: the public form of a wave slot id — {@code "CC WAVE w.i"} →
     * {@code "AlphaCoreMP WAVE w.i"} — published ONLY where a player can read it (the engine member
     * name + the RAPP row when {@code WaveShipNamer} yields no vanilla display name). The slot id
     * itself never changes: every {@code slot=} log key, schedule row, contract row and tooling regex
     * keeps the authored {@code "CC WAVE"} scheme. A name outside the scheme returns unchanged.
     */
    public static String publicWaveName(String slotName) {
        if (slotName != null && slotName.startsWith(SLOT_NAME_PREFIX)) {
            return PUBLIC_WAVE_NAME_PREFIX + slotName.substring(SLOT_NAME_PREFIX.length());
        }
        return slotName;
    }

    /** The first CURVE wave's index in the authored {@code "CC WAVE w.i"} scheme (wave 1 = the t=0 descriptor rows). */
    public static final int FIRST_CURVE_WAVE = 2;

    /**
     * The d-mod ramp key ("at most the first wave should have a handful" of
     * d-mods): the wave index {@code w} of a slot id in the authored {@code "CC WAVE w.i"} scheme — the
     * inflater's {@code wave.qualityPerWave} ramp keys on it. A name outside the scheme (or a
     * non-numeric {@code w}) returns {@code -1}: no ramp, never a throw — the slot id is the schedule's
     * own mint ({@link #SLOT_NAME_PREFIX}), so an outsider is a tooling/contract row, not a wave.
     */
    public static int waveIndexOf(String slotName) {
        if (slotName == null || !slotName.startsWith(SLOT_NAME_PREFIX)) {
            return -1;
        }
        String rest = slotName.substring(SLOT_NAME_PREFIX.length());
        int dot = rest.indexOf('.');
        String w = dot < 0 ? rest : rest.substring(0, dot);
        if (w.isEmpty() || w.length() > 4) {
            return -1;
        }
        for (int i = 0; i < w.length(); i++) {
            if (w.charAt(i) < '0' || w.charAt(i) > '9') {
                return -1;
            }
        }
        return Integer.parseInt(w);
    }
    /**
     * Finale escorts ("the mothership spawns alone - why? give it some
     * escorts"): the finale wave's ESCORT pack — rows 9.2.. after the mothership, drawn from
     * the derelict pool's Defender/Sentry drone classes (NEVER a Guardian: the reported
     * "too many Guardians"). They spawn at the edge WITH the boss through the same
     * drain and are given a LIGHT_ESCORT task-manager order on the ROOT by the finale author
     * ({@code FinaleCreepAuthor}) once both are in play — without it vanilla drone AI rushes
     * the fortress minutes ahead of a 20 su/s boss. Curve-EXEMPT like the
     * boss; they DO ride {@link #totalHullCount}/{@link #variantIds} (the bound must know
     * them). DP 3 each = 12 DP, well under any curve wave.
     */
    public static final List<String> DERELICT_FINALE_ESCORT_VARIANT_IDS = List.of(
            DERELICT_DEFENDER_VARIANT_ID, DERELICT_SENTRY_VARIANT_ID,
            DERELICT_DEFENDER_VARIANT_ID, DERELICT_SENTRY_VARIANT_ID);
    /**
     * Guardian ceiling ("there should be one or two
     * [Guardians] at most before the final two waves"; "the final two waves" = w7, w8 AND the
     * finale): the per-wave GUARDIAN ceiling the DP-SCALED fill honours, indexed like
     * {@link #derelictWavePattern} (0 = wave 2). A measured run at {@code wave_scale=1.5} fielded
     * 12 Guardians (w6 ×2, w7 ×4, w8 ×6) because the cyclic fill doubled the authored 1/2/3 —
     * w2–w6 ≤ 2 is the quoted ceiling; w7/w8 are pinned to the AUTHORED 2/3 (the
     * ratified numbers — a scale factor grows the drone/rampart screen, never the boss class;
     * an authored pick). The AUTO table already sits inside every cap (contract row).
     */
    public static final int[] DERELICT_GUARDIAN_CAP_BY_WAVE = {2, 2, 2, 2, 2, 2, 3};

    /**
     * OMEGA: variety across the shard/facet/tesseract variant flavors;
     * tesseract debut w5. facet/tesseract death-split via shard_spawner is engine-born
     * (SpawnHullmodGate audit graded at proof+flight). Engine DP: shards 6, facets 12,
     * tesseracts 24.
     */
    public static final String OMEGA_SHARD_LEFT_ATTACK_VARIANT_ID = "shard_left_Attack";
    public static final String OMEGA_SHARD_LEFT_ATTACK2_VARIANT_ID = "shard_left_Attack2";
    public static final String OMEGA_SHARD_LEFT_DEFENSE_VARIANT_ID = "shard_left_Defense";
    public static final String OMEGA_SHARD_LEFT_MISSILE_VARIANT_ID = "shard_left_Missile";
    public static final String OMEGA_SHARD_LEFT_SHIELDBREAKER_VARIANT_ID =
            "shard_left_Shieldbreaker";
    public static final String OMEGA_SHARD_RIGHT_ATTACK_VARIANT_ID = "shard_right_Attack";
    public static final String OMEGA_SHARD_RIGHT_MISSILE_VARIANT_ID = "shard_right_Missile";
    public static final String OMEGA_SHARD_RIGHT_SHIELDBREAKER_VARIANT_ID =
            "shard_right_Shieldbreaker";
    public static final String OMEGA_SHARD_RIGHT_SHOCK_VARIANT_ID = "shard_right_Shock";
    public static final String OMEGA_FACET_ARMORBREAKER_VARIANT_ID = "facet_Armorbreaker";
    public static final String OMEGA_FACET_ATTACK_VARIANT_ID = "facet_Attack";
    public static final String OMEGA_FACET_ATTACK2_VARIANT_ID = "facet_Attack2";
    public static final String OMEGA_FACET_DEFENSE_VARIANT_ID = "facet_Defense";
    public static final String OMEGA_FACET_MISSILE_VARIANT_ID = "facet_Missile";
    public static final String OMEGA_FACET_SHIELDBREAKER_VARIANT_ID = "facet_Shieldbreaker";
    public static final String OMEGA_TESSERACT_ATTACK_VARIANT_ID = "tesseract_Attack";
    public static final String OMEGA_TESSERACT_ATTACK2_VARIANT_ID = "tesseract_Attack2";
    public static final String OMEGA_TESSERACT_DEFENSE_VARIANT_ID = "tesseract_Defense";
    public static final String OMEGA_TESSERACT_DISRUPTOR_VARIANT_ID = "tesseract_Disruptor";
    public static final String OMEGA_TESSERACT_STRIKE_VARIANT_ID = "tesseract_Strike";
    /**
     * The REMNANT finale — three Tesseracts, three distinct loadouts (the
     * vanilla CBRemnantPlus all-Tesseract pool minus Attack2, so each boss reads differently:
     * Attack = rift cascade mains, Strike = the missile/strike fit, Disruptor = the EMP fit).
     * Engine DP 24 each (curve-exempt anyway). Ids verified on disk (recon L5).
     */
    public static final List<String> REMNANT_FINALE_TESSERACT_VARIANT_IDS = List.of(
            OMEGA_TESSERACT_ATTACK_VARIANT_ID, OMEGA_TESSERACT_STRIKE_VARIANT_ID,
            OMEGA_TESSERACT_DISRUPTOR_VARIANT_ID);

    /**
     * THREAT ("we do want to test
     * them" + carriers/fabricators "show up EARLY … pretty integrated with each other"):
     * fabricator_unit INCLUDED — {@code ThreatShipConstructionScript} fabricates full ships
     * mid-combat, engine-born growth the Stage A serve proved headless with ONE fabricator
     * (10 full hulls, zero roster-bound-exceeded); three concurrent fabricators
     * (w5–w7) are unproven until the next Stage A. attack_swarm (wing-only, DP 0) stays
     * excluded. Engine DP: skirmish 4, overseer 8, assault 10, standoff 20, fabricator 20
     * (ship_data.csv supplies/rec, the same column that yields hive 22), hive 22.
     */
    public static final String THREAT_SKIRMISH_TYPE100_VARIANT_ID = "skirmish_unit_Type100";
    public static final String THREAT_SKIRMISH_TYPE101_VARIANT_ID = "skirmish_unit_Type101";
    public static final String THREAT_ASSAULT_TYPE200_VARIANT_ID = "assault_unit_Type200";
    public static final String THREAT_ASSAULT_TYPE201_VARIANT_ID = "assault_unit_Type201";
    public static final String THREAT_OVERSEER_TYPE250_VARIANT_ID = "overseer_unit_Type250";
    public static final String THREAT_STANDOFF_TYPE300_VARIANT_ID = "standoff_unit_Type300";
    public static final String THREAT_STANDOFF_TYPE301_VARIANT_ID = "standoff_unit_Type301";
    public static final String THREAT_STANDOFF_TYPE302_VARIANT_ID = "standoff_unit_Type302";
    public static final String THREAT_HIVE_TYPE350_VARIANT_ID = "hive_unit_Type350";
    /** The fabricator carrier (vanilla {@code data/variants/threat/}). */
    public static final String THREAT_FABRICATOR_TYPE450_VARIANT_ID = "fabricator_unit_Type450";

    /**
     * SHROUDED: one variant per hull; vortex/ejecta (module fighters)
     * excluded; WobblyParts presentation is flight-graded. Engine DP: tendril 10, eye 20,
     * maelstrom 20, maw 40.
     */
    public static final String SHROUDED_TENDRIL_VARIANT_ID = "shrouded_tendril_Roiling";
    public static final String SHROUDED_EYE_VARIANT_ID = "shrouded_eye_Darkened";
    public static final String SHROUDED_MAELSTROM_VARIANT_ID = "shrouded_maelstrom_Menacing";
    public static final String SHROUDED_MAW_VARIANT_ID = "shrouded_maw_Ravenous";

    /**
     * The ONE seam for the SCALED-mode per-wave DP targets (the generator, the
     * serve-boot band check and the nearest-fit scan all read THIS, never their own basis
     * math): {@code target_w = multiplier_w × scale_w × (dpTotal / Σ multipliers)}. The basis
     * divides by the UNSCALED multiplier sum on purpose — {@code dpTotal} stays the base ordo
     * size the host GUI hands in, and the scale row grows waves relative to it. {@code waveScale}
     * null = unit (the pre-scale math byte-identical); a wrong-length array THROWS.
     */
    public static double[] fortressWaveTargets(double dpTotal, double[] waveScale) {
        double[] multipliers = FORTRESS_CURVE_MULTIPLIERS;
        if (waveScale != null && waveScale.length != multipliers.length) {
            throw new IllegalArgumentException("fortress wave scale carries " + waveScale.length
                    + " factors but the authored curve carries " + multipliers.length
                    + " multipliers — parse them with parseFortressWaveScale");
        }
        double multiplierSum = 0.0d;
        for (double m : multipliers) {
            multiplierSum += m;
        }
        double basis = dpTotal / multiplierSum;
        double[] targets = new double[multipliers.length];
        for (int w = 0; w < multipliers.length; w++) {
            targets[w] = multipliers[w] * (waveScale == null ? 1.0d : waveScale[w]) * basis;
        }
        return targets;
    }

    /** Defensive copy of the authored fortress curve (see {@link #FORTRESS_CURVE_MULTIPLIERS}). */
    public static double[] fortressCurveMultipliers() {
        return FORTRESS_CURVE_MULTIPLIERS.clone();
    }

    /**
     * One scheduled hull: engine side, variant id, the ship name the spawn lane applies, and
     * an OPTIONAL row-carried officer — null = the variant-keyed
     * {@code officers.txt} lookup as before (the AI-core twins, the "SOME hulls" law). A row
     * spec is the faction tables' per-(category, wave) officer RULE ({@code FactionWaveTables})
     * and the Ziggurat boss captain: server-side only (the client never officers a wave foe —
     * {@code MatchMissionBuilder} attaches t=0 rows and the own seat only), no wire field, no
     * mod data, no {@code _cc_} twin per faction hull.
     */
    public record WaveRow(int side, String variantId, String shipName,
            OfficerRosterCodec.OfficerSpec officer, String inflatedVariantId) {
        public WaveRow {
            Objects.requireNonNull(variantId, "variantId");
            Objects.requireNonNull(shipName, "shipName");
        }

        /** The pre-inflation shape: an authored row (the goal id is the fielded id). */
        public WaveRow(int side, String variantId, String shipName,
                OfficerRosterCodec.OfficerSpec officer) {
            this(side, variantId, shipName, officer, null);
        }

        /** The pre-officer shape: an unofficered row (table lookup by variant id). */
        public WaveRow(int side, String variantId, String shipName) {
            this(side, variantId, shipName, null, null);
        }

        /**
         * The id the spawn lane fields and the RAPP row names — the inflated
         * (server-generated, VDEF-carried) variant when the stage hook minted one, else the
         * authored goal. {@link #variantId()} stays the GOAL for every boot-closure consumer
         * (officer table, station budget, {@code FortressServeBootChecks}).
         */
        public String spawnVariantId() {
            return inflatedVariantId != null ? inflatedVariantId : variantId;
        }

        public WaveRow withInflated(String id) {
            return new WaveRow(side, variantId, shipName, officer, id);
        }
    }

    /**
     * The stage-time loadout hook — given an authored row and this chain's
     * category, the row to STAGE (the same row, or {@link WaveRow#withInflated}). Installed by
     * the serve ({@code WaveLoadoutInflater}); null = rows stage as authored (contract fakes,
     * game-free serves). Runs inside {@link #drainDue} on the sim thread, once per staged row;
     * the installer pre-walks the chain so this is a cache read in the frame loop.
     */
    @FunctionalInterface
    public interface RowInflater {
        WaveRow inflate(WaveRow authored, String category);
    }

    private RowInflater rowInflaterOrNull;

    public void setRowInflater(RowInflater inflaterOrNull) {
        rowInflaterOrNull = inflaterOrNull;
    }

    /** The compiled chain's rows per wave (authored, never inflated) — the pre-walk's input. */
    public List<List<WaveRow>> rowsByWave() {
        List<List<WaveRow>> out = new ArrayList<>(waves.size());
        for (Wave wave : waves) {
            out.add(wave.rows());
        }
        return out;
    }

    /** One wave: its rows plus when it is due (timer tick, and/or on-clear of prior waves). */
    private record Wave(List<WaveRow> rows, long dueTick, boolean spawnOnClear, boolean finale) {
        /** The pre-finale shape: a curve-graded wave (never a finale). */
        Wave(List<WaveRow> rows, long dueTick, boolean spawnOnClear) {
            this(rows, dueTick, spawnOnClear, false);
        }
    }

    private final String shape;
    // non-final ONLY for retarget(WaveSchedule) — the compiled chain swaps IN
    // PLACE at a round boundary (this object is lambda-captured by the serve loop's clear
    // trigger, so its identity must survive a category switch). waves stays an immutable
    // List.copyOf; only the reference moves.
    private String category;
    private List<Wave> waves;
    private int waveSide;
    private int totalHullCount;

    // The compiled clear hold, ticks (clearHoldTicks(ticksPerSecond) at
    // mint). Final — a retarget graft keeps it (same shape, same tick rate by construction).
    private final long clearHoldTicks;

    // Per-round state (newRound() resets): first unspawned wave + the last enemy-live
    // observation. "Unknown before first observation" never satisfies the clear leg.
    private int nextWave;
    private boolean enemyLiveKnown;
    private long lastEnemyLive;
    // Hold countdown anchor: the first drainDue tick that read a standing ZERO
    // enemy-live observation; -1 = not counting. Reset by a non-zero observation (late-fading
    // shard children — the catch), by a spawn (the world is about to change), and by newRound().
    private long clearZeroSinceTick = -1L;
    // timer-arm latch: the timer leg measures from the round's FIRST live-seat
    // mount, never round-open. Unarmed = the timer NEVER fires (no-seat round).
    private boolean timerArmed;
    private long timerArmBaseTick;
    // Operator wave-skip: one-shot force of the next unspawned wave,
    // consumed by drainDue exactly like a timer firing (ONE wave, never a chain).
    private boolean forceNextDueOnce;
    // Operator wave-pause (F12): while paused drainDue returns nothing
    // and the timer clock FREEZES — on resume the arm base shifts forward by the paused span,
    // so no wave ever fires "late" as a catch-up burst. Volatile: toggled from the operator
    // poll (sim thread today, but the flag is the one cross-thread-safe seam by design).
    private volatile boolean paused;
    private long pauseStartTick = -1L;
    // Stagger stage: rows of the wave(s) already judged due, not yet returned
    // to the caller (drainDue pops at most maxRows per call). newRound() clears. Each
    // staged row carries its RELEASE tick (stage tick + fly-in offset); the deque is kept
    // release-sorted so the pop only ever inspects the head.
    private record PendingRow(WaveRow row, long releaseTick) { }
    private final java.util.ArrayDeque<PendingRow> pendingRows = new java.util.ArrayDeque<>();
    // The compiled tick rate (the stagger window's seconds→ticks conversion) and the
    // fly-in stagger window, seconds (0 = off — the class default; the serve sets the knob).
    private final int ticksPerSecond;
    private float flyInStaggerSec;
    // Seed term: the round ordinal (newRound() increments) — same wave, later round,
    // different spread.
    private int roundOrdinal;
    // ENDLESS MODE ("make it so it
    // starts the waves over? an endless mode toggle? it could be the default if it works"):
    // once the LAST wave is spent (chain index past the end, no staged tail) and the field
    // reads zero SUSTAINED through the same hold every clear-spawn uses, the chain
    // index WRAPS to 0 and wave 2 rides the clear leg again — same authored plan, same scale,
    // cycle counter +1. `drained()` is then NEVER true, which is exactly the match-over
    // latch's schedule gate (EngineBackedLiveWorldSnapshotSource.setWaveClearRoundEnd reads
    // it), so the DEFEND victory never latches mid-loop; the round still ends by the base-hulk
    // LOSE seam, the operator verbs or the frame budget. Config (the serve sets it once at
    // mint, persists across rounds); `cycle` is round state (newRound() resets to 1).
    private boolean endless;
    private int cycle = 1;
    private long lastWrapTick = -1L;
    // The SCALED mint's per-curve-wave static targets
    // (the fortressWaveTargets output, index = chain index, finale excluded) and each compiled
    // row's DP (the mint's own dpOf reads, so the stage-time prefix scan never re-resolves on
    // the sim thread). Both null on AUTO/boss-only/non-fortress mints; a retarget graft copies
    // them from the fresh compile. scaledMint = fortressDp > 0 at mint (the knob's legal mode).
    private double[] liveTargetsOrNull;
    private double[][] rowDpOrNull;
    private boolean scaledMint;
    // The installed live-roster ratio (null = static = the drain byte-identical), its fault
    // meter, and the last stage's receipt (newRound clears it).
    private DoubleSupplier liveRatioOrNull;
    private long liveRatioFaults;
    private StageReceipt lastStageReceipt;

    private WaveSchedule(String shape, String category, List<Wave> waves, int ticksPerSecond) {
        this.shape = shape;
        this.category = category;
        this.ticksPerSecond = ticksPerSecond;
        this.clearHoldTicks = clearHoldTicks(ticksPerSecond);
        this.waves = List.copyOf(waves);
        int side = -1;
        int hulls = 0;
        for (Wave wave : this.waves) {
            for (WaveRow row : wave.rows()) {
                if (side == -1) {
                    side = row.side();
                } else if (side != row.side()) {
                    throw new IllegalArgumentException("wave schedule for " + shape
                            + " mixes sides " + side + " and " + row.side()
                            + " — the clear trigger needs the ONE wave side");
                }
                hulls++;
            }
        }
        if (side == -1) {
            throw new IllegalArgumentException("wave schedule for " + shape + " has no rows");
        }
        this.waveSide = side;
        this.totalHullCount = hulls;
        newRound();
    }

    /**
     * Mid-round retarget ("/wave omega ... next wave
     * will become that kind"; supersedes the round-boundary {@code retarget}):
     * swap this schedule's compiled chain for {@code fresh}'s IN PLACE, mid-round, WITHOUT a
     * per-round reset — the UNSPAWNED TAIL becomes the new faction at its existing trigger.
     * Why the whole-list graft is exactly that: the wave count is category-invariant (every
     * fortress table authors waves 2–8), only index 0 is timer-armed (later waves ride
     * {@code NO_TIMER + spawnOnClear}), and {@link #drainDue} reads {@code waves[nextWave..]}
     * only. Preserved: {@code nextWave} (the spawned prefix), {@code pendingRows} (a wave
     * mid-stage keeps its already-popped rows — already-fielded hulls are never re-shaped),
     * {@code timerArmed/timerArmBaseTick/enemyLiveKnown/lastEnemyLive}, the operator
     * {@link #paused} intent and its bookmark. Recomputed from {@code fresh}:
     * {@code category/waveSide/totalHullCount}. Object identity survives so every capture
     * (clear-trigger method refs, skip/pause handlers) stays valid; {@code fresh} is a normal
     * {@link #forShape} compile of the SAME shape, so every mint-time law already held.
     * Between rounds the graft simply means the next round's whole chain is the new faction
     * (the boundary's own {@code newRound()} restarts at index 0). Returns the number of
     * waves still unspawned after the graft (the caller's receipt/log row; 0 = the chain was
     * already drained, the switch shows at the next round).
     */
    public int retargetNextWave(WaveSchedule fresh) {
        Objects.requireNonNull(fresh, "fresh");
        if (!this.shape.equals(fresh.shape)) {
            throw new IllegalArgumentException("retarget must keep the shape: " + this.shape
                    + " != " + fresh.shape);
        }
        this.category = fresh.category;
        this.waves = fresh.waves;
        this.waveSide = fresh.waveSide;
        this.totalHullCount = fresh.totalHullCount;
        // The live-roster targets ride the graft (the
        // installed ratio supplier is config on THIS object and stays).
        this.liveTargetsOrNull = fresh.liveTargetsOrNull;
        this.rowDpOrNull = fresh.rowDpOrNull;
        this.scaledMint = fresh.scaledMint;
        return Math.max(0, waves.size() - nextWave);
    }

    /**
     * RESET ({@code /wave <faction> reset} "would reset the
     * waves to the beginning" — vs STAY = {@link #retargetNextWave} alone, "keeps the current
     * waves progress but changes the faction"): re-open the chain at index 0 mid-round. Narrower
     * than {@link #newRound()} on purpose — {@code cycle}/{@code roundOrdinal}/the timer arm/the
     * pause intent are round state and stay; only the spawned prefix, the staged tail and the
     * clear-hold anchor reset. Index 0 is timer-armed, so on an ARMED round the first wave is
     * due at the very next drain (the w2 timer elapsed long ago) — RESET fields the new
     * faction's opener NOW, on top of whatever is alive; hulls already fielded are never
     * un-spawned ({@code /wave skip} clears them). Returns the wave count now ahead.
     */
    public int restartChain() {
        nextWave = 0;
        pendingRows.clear();
        clearZeroSinceTick = -1L;
        forceNextDueOnce = false;
        return waves.size();
    }

    /**
     * SET ({@code /wave set N}: "set us on wave 1, wave 4, whatever"): make
     * operator-numbered wave {@code n} the NEXT wave to spawn. Operator numbering = slot names: the
     * chain's index 0 is wave 2 (wave 1 = the descriptor's t=0 field, never scheduled), a
     * finale is wave {@code waveCount()+1}. {@code n <= 2} restarts at index 0 (wave 1 has no
     * chain row of its own, so "wave 1" = the opener next); {@code n} above the last wave THROWS
     * (the grammar bounds it 1..9, the drain refuses with a receipt — never clamps silently).
     * The staged tail and the clear-hold anchor reset like {@link #restartChain}; index 0 fires
     * on its (long elapsed) timer at the next drain, any later wave on the clear leg (the field
     * must clear and hold first — the honest "it comes when this lot is dead"). Returns the
     * operator-numbered wave now next.
     */
    public int setNextWave(int n) {
        int index = Math.max(0, n - 2);
        if (index >= waves.size()) {
            throw new IllegalArgumentException("wave " + n + " is past this chain's last wave "
                    + (waves.size() + 1) + " (category " + category + ")");
        }
        nextWave = index;
        pendingRows.clear();
        clearZeroSinceTick = -1L;
        forceNextDueOnce = false;
        return index + 2;
    }

    /** The operator-numbered wave that spawns NEXT (2 = the chain opener; {@code waveCount()+2}
     *  once the plan is spent — a receipt reads it as "none left" unless endless). */
    public int nextWaveNumber() {
        return nextWave + 2;
    }

    /**
     * The compiled schedule for a wave shape, or null for every other shape (their serves are
     * byte-identical — no schedule, no trigger, no bound change). Exact-match keying, the
     * fail-closed mint discipline: a NEW wave shape adds its own branch here.
     */
    public static WaveSchedule forShape(String shape, int ticksPerSecond) {
        return forShape(shape, ticksPerSecond, 0.0d, null);
    }

    /**
     * The DP-scaled mint. {@code fortressDpTotal}
     * &gt; 0 scales the FORTRESS chain to that TOTAL scheduled-wave DP ("vanilla ordo size"
     * semantics — the host GUI hands in one DP number and every wave scales proportionally);
     * 0/negative = AUTO = the authored chain BYTE-IDENTICAL (the suite pins ride on that).
     * {@code dpOf} is the INJECTED per-variant DP truth (the engine
     * {@code getSuppliesToRecover} fold at a serve; a static table in the suite) — required
     * exactly when scaling, so this class stays game-free and DP numbers are never authored
     * (the DP LAW). Non-fortress shapes ignore both extras (the knob is
     * fortress-scoped by name; the serve logs a set-but-ignored knob at boot).
     */
    public static WaveSchedule forShape(String shape, int ticksPerSecond,
            double fortressDpTotal, ToDoubleFunction<String> dpOf) {
        return forShape(shape, ticksPerSecond, fortressDpTotal, null, dpOf);
    }

    /**
     * The 4-arg mint plus the parsed {@code match.fortressWaveScale}
     * factors ({@link #parseFortressWaveScale}; null = unit = the 4-arg mint byte-identical).
     * The scale rides SCALED mode only — a non-null scale with {@code fortressDpTotal <= 0}
     * (AUTO) THROWS on a fortress shape: a set knob is never silently inert, and the AUTO
     * chain is authored counts, not a generator. Non-fortress shapes ignore it like the total.
     */
    public static WaveSchedule forShape(String shape, int ticksPerSecond,
            double fortressDpTotal, double[] waveScale, ToDoubleFunction<String> dpOf) {
        return forShape(shape, ticksPerSecond, fortressDpTotal, waveScale, dpOf, "remnant");
    }

    /**
     * The 5-arg mint plus the parsed {@code match.waveCategory}
     * ({@link #parseWaveCategory}; the 5-arg mint delegates with {@code remnant}, so every
     * pre-D1 caller and pin compiles the standing chain byte-identical). The category picks
     * the fortress wave TABLES only — timer/clear/latch/scale/fill law are all category-blind,
     * and both fortress modes honour it (AUTO fields the authored table, SCALED cyclic-fills
     * over it). Non-fortress shapes ignore it (the fortressDp precedent).
     */
    public static WaveSchedule forShape(String shape, int ticksPerSecond,
            double fortressDpTotal, double[] waveScale, ToDoubleFunction<String> dpOf,
            String category) {
        // Bastion:hyperion IS the standing wave watch with seat 2's hull
        // swapped, so it compiles the SAME continuous
        // chain — single-variable vs the bastion:wave baselines. Its :rig twin stays
        // schedule-less (beam-rig precedent: fixture + AI-teleport evidence only).
        // Bastion:carrier = the standing wave watch PLUS one t=0 condor foe
        // row — same full chain, same single-variable rationale; its :rig twin is likewise
        // schedule-less (the rig proves the growth seam, not the wave fight).
        // Bastion:strike = bastion:carrier PLUS one t=0 drover foe row —
        // same full chain, same append-don't-swap rationale; its :rig twin is likewise
        // schedule-less (the rig measures the slice-3 ordnance baselines, not the wave fight).
        // Bastion:mirv = bastion:strike PLUS the two t=0 MIRV-vehicle foe
        // rows — same full chain, same append-don't-swap rationale; no rig twin (the shape
        // exists for an eyeball acceptance only).
        // Bastion:fortress compiles the ESCALATING chain below instead — a
        // different plan, not a different seam. Its :rig twin stays schedule-less (the rig
        // proves the station deploy/lose machinery, not the wave fight).
        boolean waveShape = "bastion:wave".equals(shape) || "bastion:hyperion".equals(shape)
                || "bastion:carrier".equals(shape) || "bastion:strike".equals(shape)
                || "bastion:mirv".equals(shape);
        boolean rigShape = "bastion:wave:rig".equals(shape);
        boolean fortressShape = MatchDescriptors.isFortressShape(shape);
        if (!waveShape && !rigShape && !fortressShape) {
            return null;
        }
        if (ticksPerSecond < 1) {
            throw new IllegalArgumentException(
                    "wave schedule needs ticksPerSecond >= 1, got " + ticksPerSecond);
        }
        // normalize-and-validate even for non-fortress mints (a typo'd knob
        // must throw at boot on every shape, never ride silently ignored).
        String waveCategory = parseWaveCategory(category);
        if (fortressShape) {
            long armDueTick =
                    Math.round((double) FORTRESS_W2_FALLBACK_DUE_SEC * ticksPerSecond);
            if (fortressDpTotal > 0.0d && !isBossOnlyCategory(waveCategory)) {
                if (!Double.isFinite(fortressDpTotal)) {
                    throw new IllegalArgumentException(
                            "match.fortressDp must be finite, got " + fortressDpTotal);
                }
                if (dpOf == null) {
                    throw new IllegalArgumentException("match.fortressDp="
                            + fortressDpTotal + " needs an injected DP resolver (the serve's"
                            + " NPeerHooks fortressDp / the boot check's engine fold) — DP"
                            + " numbers are never authored");
                }
                return scaledFortressChain(shape, waveCategory, armDueTick,
                        fortressWaveTargets(fortressDpTotal, waveScale), dpOf, ticksPerSecond);
            }
            if (fortressDpTotal > 0.0d) {
                // A boss-only category under SCALED is
                // still the knob's legal mode (the boss is curve-exempt: no targets, static).
                WaveSchedule boss = fortressChain(shape, waveCategory, armDueTick, ticksPerSecond);
                boss.scaledMint = true;
                return boss;
            }
            if (waveScale != null && !(fortressDpTotal > 0.0d)) {
                throw new IllegalArgumentException("match.fortressWaveScale is set but"
                        + " match.fortressDp is 0/absent (AUTO): the per-wave scale rides the"
                        + " DP-scaled chain only — set a base total, or delete the scale row");
            }
            // A boss-only category fields its authored chain under BOTH modes (the
            // boss is curve-exempt — a DP total or scale never grows or shrinks it).
            return fortressChain(shape, waveCategory, armDueTick, ticksPerSecond);
        }
        long dueTick = Math.round((double) WAVE_2_FALLBACK_DUE_SEC * ticksPerSecond);
        List<WaveRow> wave2 = List.of(
                new WaveRow(1, MatchDescriptors.REMNANT_LUMEN_VARIANT_ID, "CC WAVE 2.1"),
                new WaveRow(1, MatchDescriptors.REMNANT_LUMEN_VARIANT_ID, "CC WAVE 2.2"),
                new WaveRow(1, MatchDescriptors.REMNANT_GLIMMER_VARIANT_ID, "CC WAVE 2.3"));
        if (rigShape) {
            return new WaveSchedule(shape, waveCategory, List.of(new Wave(wave2, dueTick, true)),
                    ticksPerSecond);
        }
        // The continuous chain. DP ramp vs seat DP 48, gentle by design (a FEEL
        // dial — sizes/mixes are vetoable data): w1 10 (descriptor) → w2 13 → w3 18 → w4 22.
        List<WaveRow> wave3 = List.of(
                new WaveRow(1, MatchDescriptors.REMNANT_GLIMMER_VARIANT_ID, "CC WAVE 3.1"),
                new WaveRow(1, MatchDescriptors.REMNANT_GLIMMER_VARIANT_ID, "CC WAVE 3.2"),
                new WaveRow(1, MatchDescriptors.REMNANT_LUMEN_VARIANT_ID, "CC WAVE 3.3"),
                new WaveRow(1, MatchDescriptors.REMNANT_LUMEN_VARIANT_ID, "CC WAVE 3.4"));
        List<WaveRow> wave4 = List.of(
                new WaveRow(1, MatchDescriptors.REMNANT_LUMEN_VARIANT_ID, "CC WAVE 4.1"),
                new WaveRow(1, MatchDescriptors.REMNANT_LUMEN_VARIANT_ID, "CC WAVE 4.2"),
                new WaveRow(1, MatchDescriptors.REMNANT_LUMEN_VARIANT_ID, "CC WAVE 4.3"),
                new WaveRow(1, MatchDescriptors.REMNANT_GLIMMER_VARIANT_ID, "CC WAVE 4.4"),
                new WaveRow(1, MatchDescriptors.REMNANT_GLIMMER_VARIANT_ID, "CC WAVE 4.5"));
        return new WaveSchedule(shape, waveCategory, List.of(
                new Wave(wave2, dueTick, true),
                new Wave(wave3, NO_TIMER, true),
                new Wave(wave4, NO_TIMER, true)), ticksPerSecond);
    }

    /**
     * The STAR FORTRESS escalating chain (a hand-authored curve, vetoable DATA — the continuous-chain
     * precedent; the ids and counts here are the only authored numbers, DP truth always comes
     * from the engine at boot via the fortress DP-curve check). Wave 1 no longer exists: the
     * descriptor's t=0 glimmer mix was REMOVED, so
     * w2 OPENS the fight on the 25 s {@link #FORTRESS_W2_FALLBACK_DUE_SEC} timer (the clear
     * leg stays structurally dead until w2 itself is seen); w3–w8 are spawn-on-clear ONLY
     * ({@link #NO_TIMER} — the survival law: never pile a timer wave onto a live field).
     *
     * <p>C4 re-author (<i>"a trickle of
     * non-officer small force, escalating in size, then in officer frequency"</i>, real Remnant
     * Ordo mixes as reference): w2–w4 field NO officer twins at all (pure trickle — frigates,
     * then destroyers); the ratified carrier debuts at w5 with the FIRST gamma;
     * officer tier ladders gamma(w5) → beta(w6) → alpha(w7); the finale fields two capitals.
     * DP still tracks the multipliers (0.35× → 2.5× of seat DP, boot-checked ±15%). Bare
     * vanilla ids field UNOFFICERED by design (the "SOME hulls" law); lumen is never officered
     * at all. The twin ladder matches vanilla's own core convention (alpha→capital/cruiser,
     * beta→destroyer, gamma→frigate/destroyer — {@code RemnantOfficerGeneratorPlugin}).
     *
     * <p>FINAL C4 re-author (real Ordo compositions,
     * <b>NO beam avoidance</b> — beam lane B1–B4 proven): every pick is a same-hull same-DP
     * loadout re-pick, so the DP receipts are byte-identical to the earlier C4 chain. The
     * previously-avoided beam surfaces now field: {@code glimmer_Support} (all-beam) mixes
     * into the w2–w5 screen beside Assault; {@code fulgent_Support} (pdlaser PD beams) mixes
     * beside Assault from w4; {@code scintilla_Support} (2× spark_wing — pdburst BEAM drones)
     * debuts w6 and repeats w7 while w5 keeps the Strike loadout (flash bombers = the B2 bomb
     * surface); w7.2 apex flies {@code apex_Standard} (graviton mains) and w8.2 the finale
     * capital flies {@code nova_Standard} (phasebeam mains) — the beam-light Overdriven/Attack
     * picks retired. Composition sizes, officer frequency 1→2→3→3, the debut ladder and the
     * two-capital finale are untouched; all picks stay authored, fixed in code.
     *
     * <p>B4 apex fielding (vetoable like the rest of the chain): w7.2
     * swaps the brilliant BETA twin for the bare apex — the ONE row where apex's DP 30 stays
     * inside the ±15% band (w7 = 87 vs 81.6 target, +6.6%; every other wave goes out of band,
     * and ADDING a row anywhere does too). Apex's 2× built-in terminator_wing + drone_strike
     * conversion are arc-watch fighter-ordnance rows.
     */
    private static WaveSchedule fortressChain(String shape, String category, long armDueTick,
            int ticksPerSecond) {
        List<List<WaveRow>> pattern = fortressWavePattern(category);
        List<Wave> waves = new ArrayList<>(pattern.size());
        for (int w = 0; w < pattern.size(); w++) {
            waves.add(new Wave(pattern.get(w), w == 0 ? armDueTick : NO_TIMER, true));
        }
        Wave finale = finaleWaveOrNull(category);
        if (finale != null) {
            waves.add(finale);
        }
        return new WaveSchedule(shape, category, waves, ticksPerSecond);
    }

    /**
     * The category's FINALE wave, or null (every category but derelict). ONE
     * mint seam for BOTH fortress modes ({@link #fortressChain} AUTO and
     * {@link #scaledFortressChain} SCALED append it AFTER the 7 curve waves), so the boss can
     * never drift between modes. Shape: one {@link #DERELICT_MOTHERSHIP_VARIANT_ID} row named
     * {@link #DERELICT_FINALE_SLOT_NAME} FIRST, then the {@link #DERELICT_FINALE_ESCORT_VARIANT_IDS}
     * escort rows (never DP-filled), {@link #NO_TIMER} + spawn-on-clear (it drains only
     * once w8 is cleared and held, exactly like w3–w8 chain), {@code finale=true}.
     * Consequences by construction: {@link #waveCount} = 8 for derelict (the round's DEFEND
     * win latches on {@link #drained} + a cleared field, so the boss must die like w8 did);
     * {@link #totalHullCount}/{@link #variantIds} INCLUDE it (the stable-roster bound term +
     * the engine-keyed station-module fold read those); {@link #variantIdsByWave} EXCLUDES it
     * (curve-exempt — the boot band check pins exactly 7 graded waves). The mid-serve
     * {@link #retargetNextWave} graft stays correct: it swaps the WHOLE list and keeps
     * {@code nextWave}, so a switch INTO derelict gains the finale at the tail and a switch
     * OUT drops it. The finale row's side is the derelict table's ONE wave side (1).
     */
    private static Wave finaleWaveOrNull(String category) {
        List<WaveRow> rows = finaleRows(category);
        return rows.isEmpty() ? null : new Wave(rows, NO_TIMER, true, true);
    }

    /**
     * The finale wave's rows in drain order (mothership FIRST, then the escort slots), or an
     * empty list for a category with no finale. Public for the {@code CC_RIG_WAVE_PROBE}
     * {@code finale@tick} token (the headless rehearsal drains the REAL
     * finale rows — same variants, same slot names, so the escort enlist keys match) and the
     * contract rows.
     */
    public static List<WaveRow> finaleRows(String category) {
        String cat = parseWaveCategory(category);
        if ("remnant".equals(cat)) {
            // Remnant finale (the remnant bonus wave 9 "only has three
            // Tesseracts (the omega enemy)"): three distinct Tesseract loadouts from the
            // vanilla all-Tesseract pool (CBRemnantPlus picks Attack/Attack2/Strike/
            // Disruptor). Curve-exempt like the derelict finale, spawn-on-clear after w8; the
            // hull's built-in shard_spawner splits them engine-side. No creep root, no escorts
            // (isFinaleEscortRow keys on the derelict escort ids, never the 9. prefix alone).
            List<WaveRow> rows = new ArrayList<>(REMNANT_FINALE_TESSERACT_VARIANT_IDS.size());
            for (int i = 0; i < REMNANT_FINALE_TESSERACT_VARIANT_IDS.size(); i++) {
                rows.add(new WaveRow(1, REMNANT_FINALE_TESSERACT_VARIANT_IDS.get(i),
                        FINALE_WAVE_NAME_PREFIX + (i + 1)));
            }
            return List.copyOf(rows);
        }
        if (!"derelict".equals(cat)) {
            return List.of();
        }
        List<WaveRow> rows = new ArrayList<>(1 + DERELICT_FINALE_ESCORT_VARIANT_IDS.size());
        rows.add(new WaveRow(1, DERELICT_MOTHERSHIP_VARIANT_ID, DERELICT_FINALE_SLOT_NAME));
        // The escort pack rides the SAME wave, mothership FIRST (the drain is
        // in-order, so the root's arm precedes every escort enlist — FinaleCreepAuthor still
        // tolerates either order by resolving lazily).
        for (int i = 0; i < DERELICT_FINALE_ESCORT_VARIANT_IDS.size(); i++) {
            rows.add(new WaveRow(1, DERELICT_FINALE_ESCORT_VARIANT_IDS.get(i),
                    DERELICT_FINALE_WAVE_NAME_PREFIX + (i + 2)));
        }
        return List.copyOf(rows);
    }

    /**
     * true for the ONE row the serve loop's finale-creep author may arm — the
     * derelict finale mothership. Keyed on the variant id (a {@code WaveRow} carries no lane),
     * so the CALLER owns the lane law: the scheduled drain and the probe drain arm, the adhoc
     * {@code /spawnfoe} drain never calls this.
     */
    public static boolean isFinaleCreepRow(WaveRow row) {
        return row != null && DERELICT_MOTHERSHIP_VARIANT_ID.equals(row.variantId());
    }

    /**
     * true for a finale ESCORT row — any finale-wave slot
     * ({@link #DERELICT_FINALE_WAVE_NAME_PREFIX}) that is not the mothership. Keyed on the slot
     * name because the escort variants ARE curve-pool drones (defender/sentry ride w2–w8 too);
     * the curve names never carry the {@code 9.} prefix in either mint mode. Same caller-owned
     * lane law as {@link #isFinaleCreepRow}.
     */
    public static boolean isFinaleEscortRow(WaveRow row) {
        // The remnant finale's Tesseracts share the "9." prefix but are NOT escorts
        // (no creep root to follow) — the escort law needs the derelict escort ids too.
        return row != null && row.shipName() != null
                && row.shipName().startsWith(DERELICT_FINALE_WAVE_NAME_PREFIX)
                && !isFinaleCreepRow(row)
                && DERELICT_FINALE_ESCORT_VARIANT_IDS.contains(row.variantId());
    }

    /**
     * The SCALED fill's per-wave ceiling for one variant —
     * {@link Integer#MAX_VALUE} (uncapped) for everything but the derelict Guardian, whose
     * ceiling is {@link #DERELICT_GUARDIAN_CAP_BY_WAVE}. Public for the contract rows only.
     */
    public static int scaledVariantCap(String category, int waveIndex, String variantId) {
        if ("derelict".equals(category) && DERELICT_GUARDIAN_VARIANT_ID.equals(variantId)
                && waveIndex >= 0 && waveIndex < DERELICT_GUARDIAN_CAP_BY_WAVE.length) {
            return DERELICT_GUARDIAN_CAP_BY_WAVE[waveIndex];
        }
        return Integer.MAX_VALUE;
    }

    /**
     * The AUTO chain scaled to {@code dpTotal} scheduled-wave DP. Per-wave
     * basis = {@code dpTotal / Σ multipliers} (so the authored curve SHAPE is preserved and
     * AUTO ≈ the same math with seat-DP basis); each wave's authored row list is treated as a
     * CYCLIC pattern — repeat rows to grow, drop-from-end to shrink — filled greedily while
     * the wave stays ≤ its target, minimum ONE row even when the first pattern hull alone
     * overshoots (the serve-boot ±15% band check then refuses fail-closed: a total too small
     * for the authored late-wave patterns must never silently reshape the fight). Names
     * continue the authored {@code "CC WAVE w.i"} scheme, so the authored prefix is
     * name-identical. DP truth comes only from {@code dpOf} (injected — see
     * {@link #forShape(String, int, double, ToDoubleFunction)}). The per-wave
     * {@code targets} come from {@link #fortressWaveTargets} (curve × scale × basis) — this
     * method owns the FILL law only.
     */
    private static WaveSchedule scaledFortressChain(String shape, String category,
            long armDueTick, double[] targets, ToDoubleFunction<String> dpOf,
            int ticksPerSecond) {
        List<List<WaveRow>> pattern = fortressWavePattern(category);
        if (pattern.size() != targets.length) {
            throw new IllegalStateException("fortress pattern carries " + pattern.size()
                    + " waves but the authored curve carries " + targets.length
                    + " multipliers — re-ratify them together");
        }
        String cat = parseWaveCategory(category);
        List<Wave> waves = new ArrayList<>(pattern.size());
        double[][] rowDpByWave = new double[pattern.size()][]; //
        for (int w = 0; w < pattern.size(); w++) {
            List<WaveRow> authored = pattern.get(w);
            double target = targets[w];
            List<WaveRow> rows = new ArrayList<>();
            List<Double> rowDps = new ArrayList<>(); //
            double waveDp = 0.0d;
            // per-variant counts vs scaledVariantCap — a capped proto is
            // SKIPPED (i advances, the fill continues with the next pattern row); a whole
            // pattern cycle that adds nothing means every remaining proto is capped → stop.
            Map<String, Integer> fielded = new HashMap<>();
            int skippedSinceAdd = 0;
            for (int i = 0; ; i++) {
                WaveRow proto = authored.get(i % authored.size());
                if (fielded.getOrDefault(proto.variantId(), 0)
                        >= scaledVariantCap(cat, w, proto.variantId())) {
                    if (++skippedSinceAdd >= authored.size()) {
                        break;
                    }
                    continue;
                }
                double rowDp = dpOf.applyAsDouble(proto.variantId());
                if (!Double.isFinite(rowDp) || rowDp <= 0.0d) {
                    throw new IllegalArgumentException("fortress DP resolver read " + rowDp
                            + " for variant '" + proto.variantId() + "' — a non-positive DP"
                            + " would spin the greedy fill or blind the curve check");
                }
                if (!rows.isEmpty() && waveDp + rowDp > target) {
                    break;
                }
                if (rows.size() >= FORTRESS_SCALED_WAVE_ROW_CAP) {
                    throw new IllegalArgumentException("scaled fortress wave " + (w + 2)
                            + " hit the " + FORTRESS_SCALED_WAVE_ROW_CAP + "-row cap at dp="
                            + waveDp + " target=" + target + " — per-hull DP data has shifted"
                            + " far below the authored assumptions; refuse loudly instead of"
                            + " minting a runaway schedule");
                }
                // Slot index = rows fielded so far (== i while nothing is capped, so the
                // uncapped naming is byte-identical; a skip never leaves a name gap).
                // The proto's row-carried officer rides every cyclic copy
                // (the fill grows officer COUNT with the wave's DP, never the tier).
                rows.add(new WaveRow(proto.side(), proto.variantId(),
                        "CC WAVE " + (w + 2) + "." + (rows.size() + 1), proto.officer()));
                fielded.merge(proto.variantId(), 1, Integer::sum);
                skippedSinceAdd = 0;
                waveDp += rowDp;
                rowDps.add(rowDp);
            }
            if (rows.isEmpty()) {
                throw new IllegalStateException("scaled fortress wave " + (w + 2)
                        + " fielded nothing — every authored proto is capped at 0"
                        + " (caps are authored ≥ 1)");
            }
            waves.add(new Wave(rows, w == 0 ? armDueTick : NO_TIMER, true));
            double[] dps = new double[rowDps.size()];
            for (int i = 0; i < dps.length; i++) {
                dps[i] = rowDps.get(i);
            }
            rowDpByWave[w] = dps;
        }
        Wave finale = finaleWaveOrNull(category); // Never DP-filled
        if (finale != null) {
            waves.add(finale);
        }
        WaveSchedule scaled = new WaveSchedule(shape, category, waves, ticksPerSecond);
        // Keep the static targets (= the CAP) and the
        // per-row DP for the live-roster stage-time prefix truncation.
        scaled.liveTargetsOrNull = targets.clone();
        scaled.rowDpOrNull = rowDpByWave;
        scaled.scaledMint = true;
        return scaled;
    }

    /**
     * The authored fortress wave compositions for one {@link #WAVE_CATEGORIES category},
     * waves 2–8 in order — ids and counts are the ONLY authored numbers (every
     * table authored at the TRUE seat basis 58, engine-DP-summed against the ratified curve;
     * the boot-time band is now NARRATION, not a refusal).
     * Shared verbatim by the AUTO mint ({@link #fortressChain}) and as the cyclic fill
     * pattern of the DP-scaled mint ({@link #scaledFortressChain}), so scaling can never
     * drift the composition character away from the ratified tables.
     */
    public static List<List<WaveRow>> fortressWavePattern(String category) {
        return switch (parseWaveCategory(category)) {
            case "remnant" -> remnantWavePattern();
            case "derelict" -> derelictWavePattern();
            case "omega" -> omegaWavePattern();
            case "threat" -> threatWavePattern();
            case "shrouded" -> shroudedWavePattern();
            // The seven faction tables (+ their officer rule) and the
            // one-wave Ziggurat boss live in FactionWaveTables (this file is past its size tier).
            case "hegemony", "persean_league", "tritachyon", "luddic_church", "luddic_path",
                    "sindrian_diktat", "pirates", "ziggurat" ->
                    FactionWaveTables.pattern(parseWaveCategory(category));
            default -> throw new IllegalStateException("parseWaveCategory admitted a category"
                    + " with no authored table: '" + category + "' — extend the switch with"
                    + " the new table");
        };
    }

    /**
     * REMNANT — the standing chain (provenance in the class javadoc above),
     * re-authored for the TRUE seat basis 58 (the aurora→conquest
     * seat swap moved the fold 48→58): ONE frigate appended to each of w2–w6 (glimmer /
     * glimmer / lumen / glimmer / lumen), w7/w8 byte-identical. Engine DP 22/29/38/47/65/
     * 91/129 vs targets 20.3/29/40.6/52.2/72.5/98.6/145. 40 hulls; officer ladder untouched
     * (9 cores, lumen never officered).
     */
    private static List<List<WaveRow>> remnantWavePattern() {
        List<WaveRow> wave2 = List.of(
                new WaveRow(1, MatchDescriptors.REMNANT_GLIMMER_SUPPORT_VARIANT_ID, "CC WAVE 2.1"),
                new WaveRow(1, MatchDescriptors.REMNANT_LUMEN_VARIANT_ID, "CC WAVE 2.2"),
                new WaveRow(1, MatchDescriptors.REMNANT_LUMEN_VARIANT_ID, "CC WAVE 2.3"),
                new WaveRow(1, MatchDescriptors.REMNANT_LUMEN_VARIANT_ID, "CC WAVE 2.4"),
                new WaveRow(1, MatchDescriptors.REMNANT_GLIMMER_VARIANT_ID, "CC WAVE 2.5"));
        List<WaveRow> wave3 = List.of(
                new WaveRow(1, MatchDescriptors.REMNANT_FULGENT_VARIANT_ID, "CC WAVE 3.1"),
                new WaveRow(1, MatchDescriptors.REMNANT_GLIMMER_VARIANT_ID, "CC WAVE 3.2"),
                new WaveRow(1, MatchDescriptors.REMNANT_GLIMMER_SUPPORT_VARIANT_ID, "CC WAVE 3.3"),
                new WaveRow(1, MatchDescriptors.REMNANT_LUMEN_VARIANT_ID, "CC WAVE 3.4"),
                new WaveRow(1, MatchDescriptors.REMNANT_GLIMMER_VARIANT_ID, "CC WAVE 3.5"));
        List<WaveRow> wave4 = List.of(
                new WaveRow(1, MatchDescriptors.REMNANT_FULGENT_VARIANT_ID, "CC WAVE 4.1"),
                new WaveRow(1, MatchDescriptors.REMNANT_FULGENT_SUPPORT_VARIANT_ID, "CC WAVE 4.2"),
                new WaveRow(1, MatchDescriptors.REMNANT_GLIMMER_VARIANT_ID, "CC WAVE 4.3"),
                new WaveRow(1, MatchDescriptors.REMNANT_GLIMMER_SUPPORT_VARIANT_ID, "CC WAVE 4.4"),
                new WaveRow(1, MatchDescriptors.REMNANT_LUMEN_VARIANT_ID, "CC WAVE 4.5"),
                new WaveRow(1, MatchDescriptors.REMNANT_LUMEN_VARIANT_ID, "CC WAVE 4.6"));
        List<WaveRow> wave5 = List.of(
                new WaveRow(1, MatchDescriptors.REMNANT_SCINTILLA_VARIANT_ID, "CC WAVE 5.1"),
                new WaveRow(1, MatchDescriptors.REMNANT_FULGENT_GAMMA_VARIANT_ID, "CC WAVE 5.2"),
                new WaveRow(1, MatchDescriptors.REMNANT_FULGENT_VARIANT_ID, "CC WAVE 5.3"),
                new WaveRow(1, MatchDescriptors.REMNANT_GLIMMER_VARIANT_ID, "CC WAVE 5.4"),
                new WaveRow(1, MatchDescriptors.REMNANT_GLIMMER_SUPPORT_VARIANT_ID, "CC WAVE 5.5"),
                new WaveRow(1, MatchDescriptors.REMNANT_GLIMMER_VARIANT_ID, "CC WAVE 5.6"));
        List<WaveRow> wave6 = List.of(
                new WaveRow(1, MatchDescriptors.REMNANT_BRILLIANT_BETA_VARIANT_ID, "CC WAVE 6.1"),
                new WaveRow(1, MatchDescriptors.REMNANT_SCINTILLA_SUPPORT_VARIANT_ID, "CC WAVE 6.2"),
                new WaveRow(1, MatchDescriptors.REMNANT_FULGENT_GAMMA_VARIANT_ID, "CC WAVE 6.3"),
                new WaveRow(1, MatchDescriptors.REMNANT_FULGENT_SUPPORT_VARIANT_ID, "CC WAVE 6.4"),
                new WaveRow(1, MatchDescriptors.REMNANT_LUMEN_VARIANT_ID, "CC WAVE 6.5"),
                new WaveRow(1, MatchDescriptors.REMNANT_LUMEN_VARIANT_ID, "CC WAVE 6.6"));
        // Late-wave mix ("especially in the last waves, the
        // remnants only come out with capitals for the most part. it should have some smaller
        // ships mixed in too"): w7/w8 re-authored SMALL-FIRST with a frigate screen — the
        // cyclic fill's partial last cycle now takes smalls before the capital, and rows spawn
        // screen-then-heavies. AUTO band on seat DP 48 holds (engine DP: w7 91 vs 81.6 target
        // +11.5%; w8 129 vs 120 +7.5%); officer ladder byte-identical (w7 gamma+beta+alpha,
        // w8 gamma+beta+alpha — 9 cores, lumen never officered). Vetoable like the rest.
        List<WaveRow> wave7 = List.of(
                new WaveRow(1, MatchDescriptors.REMNANT_LUMEN_VARIANT_ID, "CC WAVE 7.1"),
                new WaveRow(1, MatchDescriptors.REMNANT_FULGENT_GAMMA_VARIANT_ID, "CC WAVE 7.2"),
                new WaveRow(1, MatchDescriptors.REMNANT_APEX_VARIANT_ID, "CC WAVE 7.3"),
                new WaveRow(1, MatchDescriptors.REMNANT_SCINTILLA_SUPPORT_VARIANT_ID, "CC WAVE 7.4"),
                new WaveRow(1, MatchDescriptors.REMNANT_FULGENT_BETA_VARIANT_ID, "CC WAVE 7.5"),
                new WaveRow(1, MatchDescriptors.REMNANT_BRILLIANT_ALPHA_VARIANT_ID, "CC WAVE 7.6"));
        List<WaveRow> wave8 = List.of(
                new WaveRow(1, MatchDescriptors.REMNANT_LUMEN_VARIANT_ID, "CC WAVE 8.1"),
                new WaveRow(1, MatchDescriptors.REMNANT_FULGENT_GAMMA_VARIANT_ID, "CC WAVE 8.2"),
                new WaveRow(1, MatchDescriptors.REMNANT_NOVA_VARIANT_ID, "CC WAVE 8.3"),
                new WaveRow(1, MatchDescriptors.REMNANT_GLIMMER_VARIANT_ID, "CC WAVE 8.4"),
                new WaveRow(1, MatchDescriptors.REMNANT_FULGENT_BETA_VARIANT_ID, "CC WAVE 8.5"),
                new WaveRow(1, MatchDescriptors.REMNANT_RADIANT_ALPHA_VARIANT_ID, "CC WAVE 8.6"));
        return List.of(wave2, wave3, wave4, wave5, wave6, wave7, wave8);
    }

    /**
     * DERELICT (authored at basis 58): drone trickle → rampart weight →
     * guardian debut w6, two more guardians w7 and a triple-guardian finale. Engine DP
     * 21/27/42/57/70/101/147 vs targets 20.3/29/40.6/52.2/72.5/98.6/145; smalls first per
     * wave; bare vanilla ids, no officer twins.
     */
    private static List<List<WaveRow>> derelictWavePattern() {
        return List.of(
                List.of(new WaveRow(1, DERELICT_WARDEN_VARIANT_ID, "CC WAVE 2.1"),
                        new WaveRow(1, DERELICT_DEFENDER_VARIANT_ID, "CC WAVE 2.2"),
                        new WaveRow(1, DERELICT_PICKET_VARIANT_ID, "CC WAVE 2.3"),
                        new WaveRow(1, DERELICT_BERSERKER_VARIANT_ID, "CC WAVE 2.4"),
                        new WaveRow(1, DERELICT_BERSERKER_VARIANT_ID, "CC WAVE 2.5")),
                List.of(new WaveRow(1, DERELICT_WARDEN_VARIANT_ID, "CC WAVE 3.1"),
                        new WaveRow(1, DERELICT_DEFENDER_VARIANT_ID, "CC WAVE 3.2"),
                        new WaveRow(1, DERELICT_BERSERKER_VARIANT_ID, "CC WAVE 3.3"),
                        new WaveRow(1, DERELICT_RAMPART_VARIANT_ID, "CC WAVE 3.4")),
                List.of(new WaveRow(1, DERELICT_WARDEN_VARIANT_ID, "CC WAVE 4.1"),
                        new WaveRow(1, DERELICT_SENTRY_VARIANT_ID, "CC WAVE 4.2"),
                        new WaveRow(1, DERELICT_BERSERKER_VARIANT_ID, "CC WAVE 4.3"),
                        new WaveRow(1, DERELICT_RAMPART_VARIANT_ID, "CC WAVE 4.4"),
                        new WaveRow(1, DERELICT_RAMPART_VARIANT_ID, "CC WAVE 4.5")),
                List.of(new WaveRow(1, DERELICT_WARDEN_VARIANT_ID, "CC WAVE 5.1"),
                        new WaveRow(1, DERELICT_DEFENDER_VARIANT_ID, "CC WAVE 5.2"),
                        new WaveRow(1, DERELICT_BERSERKER_VARIANT_ID, "CC WAVE 5.3"),
                        new WaveRow(1, DERELICT_RAMPART_VARIANT_ID, "CC WAVE 5.4"),
                        new WaveRow(1, DERELICT_RAMPART_VARIANT_ID, "CC WAVE 5.5"),
                        new WaveRow(1, DERELICT_RAMPART_VARIANT_ID, "CC WAVE 5.6")),
                List.of(new WaveRow(1, DERELICT_WARDEN_VARIANT_ID, "CC WAVE 6.1"),
                        new WaveRow(1, DERELICT_BERSERKER_VARIANT_ID, "CC WAVE 6.2"),
                        new WaveRow(1, DERELICT_BERSERKER_VARIANT_ID, "CC WAVE 6.3"),
                        new WaveRow(1, DERELICT_RAMPART_VARIANT_ID, "CC WAVE 6.4"),
                        new WaveRow(1, DERELICT_GUARDIAN_VARIANT_ID, "CC WAVE 6.5")),
                List.of(new WaveRow(1, DERELICT_DEFENDER_VARIANT_ID, "CC WAVE 7.1"),
                        new WaveRow(1, DERELICT_SENTRY_VARIANT_ID, "CC WAVE 7.2"),
                        new WaveRow(1, DERELICT_RAMPART_VARIANT_ID, "CC WAVE 7.3"),
                        new WaveRow(1, DERELICT_GUARDIAN_VARIANT_ID, "CC WAVE 7.4"),
                        new WaveRow(1, DERELICT_GUARDIAN_VARIANT_ID, "CC WAVE 7.5")),
                List.of(new WaveRow(1, DERELICT_WARDEN_VARIANT_ID, "CC WAVE 8.1"),
                        new WaveRow(1, DERELICT_PICKET_VARIANT_ID, "CC WAVE 8.2"),
                        new WaveRow(1, DERELICT_BERSERKER_VARIANT_ID, "CC WAVE 8.3"),
                        new WaveRow(1, DERELICT_RAMPART_VARIANT_ID, "CC WAVE 8.4"),
                        new WaveRow(1, DERELICT_GUARDIAN_VARIANT_ID, "CC WAVE 8.5"),
                        new WaveRow(1, DERELICT_GUARDIAN_VARIANT_ID, "CC WAVE 8.6"),
                        new WaveRow(1, DERELICT_GUARDIAN_VARIANT_ID, "CC WAVE 8.7")));
    }

    /**
     * OMEGA (authored at basis 58): shard screen → facet weight →
     * tesseract debut w5, four-tesseract finale; variety across the 16 variant flavors.
     * Engine DP 18/30/42/48/72/96/144 vs targets 20.3/29/40.6/52.2/72.5/98.6/145; the
     * facet/tesseract death-split (shard_spawner hullmod) is engine-born growth, graded at
     * proof+flight.
     */
    private static List<List<WaveRow>> omegaWavePattern() {
        return List.of(
                List.of(new WaveRow(1, OMEGA_SHARD_LEFT_ATTACK_VARIANT_ID, "CC WAVE 2.1"),
                        new WaveRow(1, OMEGA_SHARD_RIGHT_ATTACK_VARIANT_ID, "CC WAVE 2.2"),
                        new WaveRow(1, OMEGA_SHARD_LEFT_DEFENSE_VARIANT_ID, "CC WAVE 2.3")),
                List.of(new WaveRow(1, OMEGA_SHARD_RIGHT_SHOCK_VARIANT_ID, "CC WAVE 3.1"),
                        new WaveRow(1, OMEGA_FACET_ATTACK_VARIANT_ID, "CC WAVE 3.2"),
                        new WaveRow(1, OMEGA_FACET_DEFENSE_VARIANT_ID, "CC WAVE 3.3")),
                List.of(new WaveRow(1, OMEGA_SHARD_LEFT_MISSILE_VARIANT_ID, "CC WAVE 4.1"),
                        new WaveRow(1, OMEGA_FACET_ATTACK2_VARIANT_ID, "CC WAVE 4.2"),
                        new WaveRow(1, OMEGA_FACET_SHIELDBREAKER_VARIANT_ID, "CC WAVE 4.3"),
                        new WaveRow(1, OMEGA_FACET_ATTACK_VARIANT_ID, "CC WAVE 4.4")),
                List.of(new WaveRow(1, OMEGA_SHARD_LEFT_ATTACK_VARIANT_ID, "CC WAVE 5.1"),
                        new WaveRow(1, OMEGA_SHARD_RIGHT_MISSILE_VARIANT_ID, "CC WAVE 5.2"),
                        new WaveRow(1, OMEGA_FACET_ATTACK_VARIANT_ID, "CC WAVE 5.3"),
                        new WaveRow(1, OMEGA_TESSERACT_ATTACK_VARIANT_ID, "CC WAVE 5.4")),
                List.of(new WaveRow(1, OMEGA_FACET_DEFENSE_VARIANT_ID, "CC WAVE 6.1"),
                        new WaveRow(1, OMEGA_FACET_ATTACK_VARIANT_ID, "CC WAVE 6.2"),
                        new WaveRow(1, OMEGA_TESSERACT_ATTACK_VARIANT_ID, "CC WAVE 6.3"),
                        new WaveRow(1, OMEGA_TESSERACT_DEFENSE_VARIANT_ID, "CC WAVE 6.4")),
                List.of(new WaveRow(1, OMEGA_SHARD_LEFT_ATTACK2_VARIANT_ID, "CC WAVE 7.1"),
                        new WaveRow(1, OMEGA_SHARD_RIGHT_SHIELDBREAKER_VARIANT_ID, "CC WAVE 7.2"),
                        new WaveRow(1, OMEGA_FACET_MISSILE_VARIANT_ID, "CC WAVE 7.3"),
                        new WaveRow(1, OMEGA_TESSERACT_ATTACK_VARIANT_ID, "CC WAVE 7.4"),
                        new WaveRow(1, OMEGA_TESSERACT_DISRUPTOR_VARIANT_ID, "CC WAVE 7.5"),
                        new WaveRow(1, OMEGA_TESSERACT_ATTACK2_VARIANT_ID, "CC WAVE 7.6")),
                List.of(new WaveRow(1, OMEGA_SHARD_LEFT_SHIELDBREAKER_VARIANT_ID, "CC WAVE 8.1"),
                        new WaveRow(1, OMEGA_SHARD_RIGHT_ATTACK_VARIANT_ID, "CC WAVE 8.2"),
                        new WaveRow(1, OMEGA_FACET_ARMORBREAKER_VARIANT_ID, "CC WAVE 8.3"),
                        new WaveRow(1, OMEGA_TESSERACT_ATTACK_VARIANT_ID, "CC WAVE 8.4"),
                        new WaveRow(1, OMEGA_TESSERACT_DEFENSE_VARIANT_ID, "CC WAVE 8.5"),
                        new WaveRow(1, OMEGA_TESSERACT_STRIKE_VARIANT_ID, "CC WAVE 8.6"),
                        new WaveRow(1, OMEGA_TESSERACT_DISRUPTOR_VARIANT_ID, "CC WAVE 8.7"),
                        new WaveRow(1, OMEGA_TESSERACT_ATTACK2_VARIANT_ID, "CC WAVE 8.8")));
    }

    /**
     * THREAT (authored at basis 58; re-authored for the hive/fabricator rules
     * D1/D3): integrated
     * carrier fleets, not a late graduation — a hive in EVERY wave from w2 (D1), the
     * fabricator debuts w5 and recurs w6/w7 (D3), never a finale row; triple-hive finale kept;
     * smalls first. Engine DP 26/30/40/54/70/100/144 vs targets 20.3/29/40.6/52.2/72.5/
     * 98.6/145 — w2 runs +5.7 hot by construction (a hive alone is 22 &gt; 20.3; the band is
     * narration; a w3 hive debut is the open alternative).
     */
    private static List<List<WaveRow>> threatWavePattern() {
        return List.of(
                List.of(new WaveRow(1, THREAT_SKIRMISH_TYPE100_VARIANT_ID, "CC WAVE 2.1"),
                        new WaveRow(1, THREAT_HIVE_TYPE350_VARIANT_ID, "CC WAVE 2.2")),
                List.of(new WaveRow(1, THREAT_SKIRMISH_TYPE100_VARIANT_ID, "CC WAVE 3.1"),
                        new WaveRow(1, THREAT_SKIRMISH_TYPE101_VARIANT_ID, "CC WAVE 3.2"),
                        new WaveRow(1, THREAT_HIVE_TYPE350_VARIANT_ID, "CC WAVE 3.3")),
                List.of(new WaveRow(1, THREAT_SKIRMISH_TYPE100_VARIANT_ID, "CC WAVE 4.1"),
                        new WaveRow(1, THREAT_SKIRMISH_TYPE101_VARIANT_ID, "CC WAVE 4.2"),
                        new WaveRow(1, THREAT_ASSAULT_TYPE200_VARIANT_ID, "CC WAVE 4.3"),
                        new WaveRow(1, THREAT_HIVE_TYPE350_VARIANT_ID, "CC WAVE 4.4")),
                List.of(new WaveRow(1, THREAT_SKIRMISH_TYPE100_VARIANT_ID, "CC WAVE 5.1"),
                        new WaveRow(1, THREAT_OVERSEER_TYPE250_VARIANT_ID, "CC WAVE 5.2"),
                        new WaveRow(1, THREAT_FABRICATOR_TYPE450_VARIANT_ID, "CC WAVE 5.3"),
                        new WaveRow(1, THREAT_HIVE_TYPE350_VARIANT_ID, "CC WAVE 5.4")),
                List.of(new WaveRow(1, THREAT_SKIRMISH_TYPE100_VARIANT_ID, "CC WAVE 6.1"),
                        new WaveRow(1, THREAT_SKIRMISH_TYPE101_VARIANT_ID, "CC WAVE 6.2"),
                        new WaveRow(1, THREAT_STANDOFF_TYPE300_VARIANT_ID, "CC WAVE 6.3"),
                        new WaveRow(1, THREAT_FABRICATOR_TYPE450_VARIANT_ID, "CC WAVE 6.4"),
                        new WaveRow(1, THREAT_HIVE_TYPE350_VARIANT_ID, "CC WAVE 6.5")),
                List.of(new WaveRow(1, THREAT_SKIRMISH_TYPE100_VARIANT_ID, "CC WAVE 7.1"),
                        new WaveRow(1, THREAT_SKIRMISH_TYPE101_VARIANT_ID, "CC WAVE 7.2"),
                        new WaveRow(1, THREAT_ASSAULT_TYPE201_VARIANT_ID, "CC WAVE 7.3"),
                        new WaveRow(1, THREAT_STANDOFF_TYPE300_VARIANT_ID, "CC WAVE 7.4"),
                        new WaveRow(1, THREAT_STANDOFF_TYPE301_VARIANT_ID, "CC WAVE 7.5"),
                        new WaveRow(1, THREAT_FABRICATOR_TYPE450_VARIANT_ID, "CC WAVE 7.6"),
                        new WaveRow(1, THREAT_HIVE_TYPE350_VARIANT_ID, "CC WAVE 7.7")),
                List.of(new WaveRow(1, THREAT_SKIRMISH_TYPE100_VARIANT_ID, "CC WAVE 8.1"),
                        new WaveRow(1, THREAT_SKIRMISH_TYPE101_VARIANT_ID, "CC WAVE 8.2"),
                        new WaveRow(1, THREAT_ASSAULT_TYPE200_VARIANT_ID, "CC WAVE 8.3"),
                        new WaveRow(1, THREAT_STANDOFF_TYPE300_VARIANT_ID, "CC WAVE 8.4"),
                        new WaveRow(1, THREAT_STANDOFF_TYPE301_VARIANT_ID, "CC WAVE 8.5"),
                        new WaveRow(1, THREAT_STANDOFF_TYPE302_VARIANT_ID, "CC WAVE 8.6"),
                        new WaveRow(1, THREAT_HIVE_TYPE350_VARIANT_ID, "CC WAVE 8.7"),
                        new WaveRow(1, THREAT_HIVE_TYPE350_VARIANT_ID, "CC WAVE 8.8"),
                        new WaveRow(1, THREAT_HIVE_TYPE350_VARIANT_ID, "CC WAVE 8.9")));
    }

    /**
     * SHROUDED (authored at basis 58): tendril trickle → eye debut w4 →
     * maelstrom w5 → maw debut w7, double-maw finale; one variant per hull by design.
     * Engine DP 20/30/40/50/70/90/150 vs targets 20.3/29/40.6/52.2/72.5/98.6/145.
     */
    private static List<List<WaveRow>> shroudedWavePattern() {
        return List.of(
                List.of(new WaveRow(1, SHROUDED_TENDRIL_VARIANT_ID, "CC WAVE 2.1"),
                        new WaveRow(1, SHROUDED_TENDRIL_VARIANT_ID, "CC WAVE 2.2")),
                List.of(new WaveRow(1, SHROUDED_TENDRIL_VARIANT_ID, "CC WAVE 3.1"),
                        new WaveRow(1, SHROUDED_TENDRIL_VARIANT_ID, "CC WAVE 3.2"),
                        new WaveRow(1, SHROUDED_TENDRIL_VARIANT_ID, "CC WAVE 3.3")),
                List.of(new WaveRow(1, SHROUDED_TENDRIL_VARIANT_ID, "CC WAVE 4.1"),
                        new WaveRow(1, SHROUDED_TENDRIL_VARIANT_ID, "CC WAVE 4.2"),
                        new WaveRow(1, SHROUDED_EYE_VARIANT_ID, "CC WAVE 4.3")),
                List.of(new WaveRow(1, SHROUDED_TENDRIL_VARIANT_ID, "CC WAVE 5.1"),
                        new WaveRow(1, SHROUDED_EYE_VARIANT_ID, "CC WAVE 5.2"),
                        new WaveRow(1, SHROUDED_MAELSTROM_VARIANT_ID, "CC WAVE 5.3")),
                List.of(new WaveRow(1, SHROUDED_TENDRIL_VARIANT_ID, "CC WAVE 6.1"),
                        new WaveRow(1, SHROUDED_EYE_VARIANT_ID, "CC WAVE 6.2"),
                        new WaveRow(1, SHROUDED_EYE_VARIANT_ID, "CC WAVE 6.3"),
                        new WaveRow(1, SHROUDED_MAELSTROM_VARIANT_ID, "CC WAVE 6.4")),
                List.of(new WaveRow(1, SHROUDED_TENDRIL_VARIANT_ID, "CC WAVE 7.1"),
                        new WaveRow(1, SHROUDED_EYE_VARIANT_ID, "CC WAVE 7.2"),
                        new WaveRow(1, SHROUDED_MAELSTROM_VARIANT_ID, "CC WAVE 7.3"),
                        new WaveRow(1, SHROUDED_MAW_VARIANT_ID, "CC WAVE 7.4")),
                List.of(new WaveRow(1, SHROUDED_TENDRIL_VARIANT_ID, "CC WAVE 8.1"),
                        new WaveRow(1, SHROUDED_EYE_VARIANT_ID, "CC WAVE 8.2"),
                        new WaveRow(1, SHROUDED_EYE_VARIANT_ID, "CC WAVE 8.3"),
                        new WaveRow(1, SHROUDED_MAELSTROM_VARIANT_ID, "CC WAVE 8.4"),
                        new WaveRow(1, SHROUDED_MAW_VARIANT_ID, "CC WAVE 8.5"),
                        new WaveRow(1, SHROUDED_MAW_VARIANT_ID, "CC WAVE 8.6")));
    }

    /**
     * The DEFEND victory: does {@code shape} end its round in VICTORY when
     * the schedule drains and the wave side is cleared? TRUE only for the fortress — the one
     * shape with a commissioned beatable END — and FALSE for its PvP shape, which has no win
     * (the waves fight for the North team there, so a cleared wave side is no victory). Every
     * other wave shape keeps the
     * continuous-fight default (the knob {@code cc.server.waveClearRoundEnd} still opts a run
     * into the per-clear reset lane; this predicate ORs with it at the wiring site, never
     * replaces it, and the serve refuses that knob on PvP). Exact-match keying, the
     * {@link #forShape} fail-closed mint discipline.
     */
    public static boolean latchesRoundEndOnWaveClear(String shape) {
        return MatchDescriptors.isFortressShape(shape) && !MatchDescriptors.isPvpShape(shape);
    }

    /**
     * Does a serve of {@code shape} boot with its wave feed PAUSED (true = PvP only)? PvP players
     * fight each other first; an admin's first pause toggle (F12 or the wave pause verb) starts
     * the waves. The serve applies it once at boot through {@link #setPaused}; {@link #newRound}
     * leaves {@code paused} alone (the operator's intent), so the pause persists across rounds
     * until toggled. Every other shape starts unpaused, byte-identical.
     *
     */
    public static boolean startsPaused(String shape) {
        return MatchDescriptors.isPvpShape(shape);
    }

    /** The shape this schedule was compiled for (diagnostics). */
    public String shape() {
        return shape;
    }

    /**
     * The parsed wave category this schedule compiled with ({@code remnant}
     * for every pre-D1 mint and every non-fortress shape) — the D2 admin-verb reply's value
     * and the boot narration's {@code category=} field.
     */
    public String category() {
        return category;
    }

    /** Scheduled waves (the t=0 descriptor rows are wave 1 and never live here). */
    public int waveCount() {
        return waves.size();
    }

    /**
     * Total scheduled hulls across all waves — the stable-roster bound term (
     * scheduled hulls are NOT descriptor rows, so the bound must learn the schedule total).
     */
    public int totalHullCount() {
        return totalHullCount;
    }

    /** The one engine side every scheduled row spawns on (= the clear trigger's enemy side). */
    public int waveSide() {
        return waveSide;
    }

    /**
     * Every scheduled hull's variant id across all waves, one entry per
     * hull (duplicates preserved — {@code size() == totalHullCount()}). The engine-born
     * growth bound folds per-hull wing/drone capacity over these plus the descriptor
     * roster rows, so a carrier arriving as a WAVE hull grows the bound too.
     */
    public List<String> variantIds() {
        List<String> ids = new ArrayList<>(totalHullCount);
        for (Wave wave : waves) {
            for (WaveRow row : wave.rows()) {
                ids.add(row.variantId());
            }
        }
        return ids;
    }

    /**
     * {@link #variantIds()} grouped per wave — outer index 0 is the FIRST
     * scheduled wave (wave 2 in operator numbering; wave 1 is the descriptor's t=0 rows), inner
     * lists are row order with duplicates preserved. The fortress serve boot folds engine DP
     * per wave over this to hold the authored chain to the authored DP curve.
     *
     * <p>A FINALE wave is EXCLUDED here (the boss is curve-EXEMPT — the boot
     * check {@code FortressServeBootChecks.runIfFortress} refuses unless it sees exactly the
     * 7 curve multipliers' waves, and a station's DP would blow any band anyway). It still
     * rides {@link #waveCount}, {@link #totalHullCount} and {@link #variantIds} — the bound
     * and the module fold must know it.
     */
    public List<List<String>> variantIdsByWave() {
        List<List<String>> byWave = new ArrayList<>(waves.size());
        for (Wave wave : waves) {
            if (wave.finale()) {
                continue;
            }
            List<String> ids = new ArrayList<>(wave.rows().size());
            for (WaveRow row : wave.rows()) {
                ids.add(row.variantId());
            }
            byWave.add(List.copyOf(ids));
        }
        return List.copyOf(byWave);
    }

    /** Reset per-round state — call where the serve loop mints its fresh per-round source. */
    public void newRound() {
        nextWave = 0;
        enemyLiveKnown = false;
        lastEnemyLive = 0L;
        clearZeroSinceTick = -1L;
        timerArmed = false;
        timerArmBaseTick = 0L;
        forceNextDueOnce = false;
        // The pause BOOKMARK is round-scoped — the wrapped
        // round's clock restarts at 0, so a bookmark from the old clock made the resume shift
        // clamp to zero and the whole paused span counted toward the timer (a catch-up burst).
        // `paused` itself persists: it is the operator's intent, not round state.
        pauseStartTick = -1L;
        pendingRows.clear();
        roundOrdinal++;
        cycle = 1; // F1: the wrapped round restarts its cycle count (endless itself persists)
        lastWrapTick = -1L;
        lastStageReceipt = null; // The receipt is round state
    }

    /**
     * One live-roster wave stage's receipt — the
     * serve's {@code wave_dp_basis} row reads it right after {@link #drainDue}. {@code wave} =
     * chain index (owner number = wave + 2); {@code ratio} = the clamped ratio actually used;
     * {@code liveTarget = ratio × staticTarget}; rows = staged of compiled; {@code readNanos} =
     * the supplier read's wall cost (the residency measurement).
     */
    public record StageReceipt(int wave, int cycle, double ratio, double liveTarget,
            double staticTarget, int rowsStaged, int rowsCompiled, long readNanos) {
    }

    /**
     * Install (or with null, remove) the LIVE-ROSTER
     * ratio. The CAP LAW: the compiled SCALED chain stays exactly today's (the static cap —
     * rosterHullBound, the inflater pre-walk and the boot band narration all grade it); at each
     * curve wave's STAGE the supplier is read ONCE and the wave fields the longest PREFIX of its
     * compiled rows with Σdp ≤ {@code clamp(r,0,1) × staticTarget}, minimum ONE row (the fill's
     * own stop law) — a live wave is always a subset of the cap, never larger. Finale waves and
     * AUTO/boss-only chains (no targets) stage whole. Null = static = the drain byte-identical.
     * A non-null supplier on a non-SCALED mint THROWS (the AUTO + knob law, the
     * {@code fortressWaveScale} precedent). Config, not state: survives {@link #newRound},
     * {@link #retargetNextWave}, {@link #restartChain}, {@link #setNextWave}.
     */
    public void setLiveRosterRatio(DoubleSupplier ratioOrNull) {
        if (ratioOrNull != null && !scaledMint) {
            throw new IllegalStateException(liveRosterAutoRefusal());
        }
        this.liveRatioOrNull = ratioOrNull;
    }

    /** The last live stage's receipt, or null. */
    public StageReceipt lastStageReceiptOrNull() {
        return lastStageReceipt;
    }

    /** Supplier faults (the stage fell back to r=1). */
    public long liveRatioFaults() {
        return liveRatioFaults;
    }

    /**
     * The knob's boot mode law, beside the
     * {@code fortressWaveScale} AUTO throw in {@link #forShape}. Returns true when
     * {@code rosterFullDp > 0} APPLIES (fortress shape, SCALED); false when it is off or the
     * shape is not a fortress (the caller logs set-but-IGNORED); THROWS on a fortress shape
     * with {@code fortressDpTotal <= 0} (AUTO) — a set knob is never silently inert.
     */
    public static boolean requireRosterFullDpMode(String shape, double fortressDpTotal,
            int rosterFullDp) {
        if (rosterFullDp <= 0 || shape == null || !MatchDescriptors.isFortressShape(shape)) {
            return false;
        }
        if (!(fortressDpTotal > 0.0d)) {
            throw new IllegalArgumentException(liveRosterAutoRefusal());
        }
        return true;
    }

    /**
     * The {@code match.waveRosterMultiplier} boot mode
     * law, the sibling of {@link #requireRosterFullDpMode}. Returns true when a non-unit
     * multiplier APPLIES (fortress shape + {@code rosterFullDp > 0}); false when it is the unit
     * value or the shape is not a fortress (the caller logs set-but-IGNORED); THROWS on a
     * fortress shape with the roster sizing OFF ({@code rosterFullDp <= 0}) — the multiplier
     * scales a share that does not exist there, and a set knob is never silently inert.
     */
    public static boolean requireRosterMultiplierMode(String shape, int rosterFullDp,
            double multiplier) {
        if (multiplier == LiveRosterDp.MULTIPLIER_UNIT || shape == null
                || !MatchDescriptors.isFortressShape(shape)) {
            return false;
        }
        if (rosterFullDp <= 0) {
            throw new IllegalArgumentException("match.waveRosterMultiplier is set but"
                    + " match.waveRosterFullDp is 0/absent (off): the multiplier scales the"
                    + " live-roster share only — set match.waveRosterFullDp above 0, or delete"
                    + " the multiplier row");
        }
        return true;
    }

    private static String liveRosterAutoRefusal() {
        return "match.waveRosterFullDp is set but match.fortressDp is 0/absent (AUTO): the"
                + " live-roster ratio truncates the DP-scaled chain only — set a base total, or"
                + " delete the roster row";
    }

    /**
     * The rows to stage for chain wave {@code index} —
     * the whole compiled list on the static path; under a live supplier the longest prefix
     * with Σdp ≤ r × target (min 1), recording {@link #lastStageReceipt}.
     */
    private List<WaveRow> liveStageRows(Wave wave, int index) {
        List<WaveRow> rows = wave.rows();
        if (liveRatioOrNull == null || wave.finale() || liveTargetsOrNull == null
                || rowDpOrNull == null || index < 0 || index >= liveTargetsOrNull.length
                || index >= rowDpOrNull.length || rowDpOrNull[index] == null
                || rowDpOrNull[index].length != rows.size()) {
            return rows;
        }
        long t0 = System.nanoTime();
        double r;
        try {
            r = liveRatioOrNull.getAsDouble();
        } catch (RuntimeException fault) {
            liveRatioFaults++;
            if (liveRatioFaults == 1L) {
                System.out.println("npeer-serve wave_dp_basis_fault err="
                        + fault.getClass().getSimpleName() + " (the live-roster supplier"
                        + " threw; the wave stages at the static cap — logged once)");
            }
            r = 1.0d;
        }
        long readNanos = System.nanoTime() - t0;
        if (Double.isNaN(r)) {
            liveRatioFaults++;
            r = 1.0d;
        }
        r = r < 0.0d ? 0.0d : (r > 1.0d ? 1.0d : r);
        double staticTarget = liveTargetsOrNull[index];
        double liveTarget = r * staticTarget;
        double[] dps = rowDpOrNull[index];
        int k = rows.isEmpty() ? 0 : 1;
        double sum = rows.isEmpty() ? 0.0d : dps[0];
        while (k < rows.size() && sum + dps[k] <= liveTarget) {
            sum += dps[k];
            k++;
        }
        lastStageReceipt = new StageReceipt(index, cycle, r, liveTarget, staticTarget, k,
                rows.size(), readNanos);
        return k == rows.size() ? rows : List.copyOf(rows.subList(0, k));
    }

    /**
     * Arm/disarm ENDLESS MODE — see the field comment. Set once at serve mint
     * (the {@code cc.match.endless} knob); a schedule with no waves left then wraps instead of
     * draining. Survives {@link #newRound()} and {@link #retargetNextWave} (config, not state).
     */
    public void setEndless(boolean endless) {
        this.endless = endless;
    }

    /** Whether the chain wraps after its last wave (F1; the serve's boot row + receipts). */
    public boolean endless() {
        return endless;
    }

    /** The current plan cycle, 1-based within the round (2 = the chain wrapped once). */
    public int cycle() {
        return cycle;
    }

    /** Tick of the latest F1 wrap this round, or -1 (the serve's {@code wave_cycle_wrap} row). */
    public long lastWrapTick() {
        return lastWrapTick;
    }

    /**
     * Set the fly-in stagger window (seconds; 0 = off). Finite, in
     * [0, {@link #MAX_FLY_IN_STAGGER_SEC}] — anything else THROWS (the
     * {@link #parseFortressWaveScale} reject-never-clamp precedent). Takes effect at the next
     * wave STAGE; rows already staged keep their release ticks. Survives
     * {@link #retargetNextWave} (the graft swaps the chain, not this object's dial).
     */
    public void setFlyInStaggerSeconds(float seconds) {
        if (!Float.isFinite(seconds) || seconds < 0.0f || seconds > MAX_FLY_IN_STAGGER_SEC) {
            throw new IllegalArgumentException("serve.waveFlyInStaggerSec must be in [0, "
                    + MAX_FLY_IN_STAGGER_SEC + "] (0 = off); got " + seconds);
        }
        this.flyInStaggerSec = seconds;
    }

    /** The live fly-in stagger window, seconds (0 = off) — the serve's boot row + receipts. */
    public float flyInStaggerSeconds() {
        return flyInStaggerSec;
    }

    /**
     * The operator skip verb's stagger half — a wave mid-spread is skipped WHOLE: the
     * rows still waiting on their release tick are DROPPED (never spawned into the field the
     * kill sweep just emptied — a tail trickling in after a skip reads as "the skip didn't
     * take"). Returns the dropped count for the receipt line. {@link #drained} then reads the
     * chain honestly (a dropped tail is not a pending one), so the clear leg / force can
     * advance exactly as before the stagger existed.
     */
    public int dropPendingRows() {
        int dropped = pendingRows.size();
        pendingRows.clear();
        return dropped;
    }

    /**
     * Stage one due wave — row 1 at {@code tick}, later rows at {@code tick + offset}
     * with sorted uniform offsets in (0, window] (window = {@link #flyInStaggerSec} × tick rate;
     * 0 ⇒ every offset 0 = the pre-stagger shape). The deque stays release-sorted: the legacy
     * unlimited drain may stage two due waves in ONE call, so a non-empty deque is merged with a
     * STABLE sort (equal release ticks keep authored order).
     */
    private void stageWave(List<WaveRow> authored, int waveIndex, long tick) {
        // The loadout hook runs at STAGE, before release offsets — the staged
        // row carries its inflated id; the authored chain row is never rewritten.
        List<WaveRow> rows = authored;
        if (rowInflaterOrNull != null) {
            rows = new ArrayList<>(authored.size());
            for (WaveRow row : authored) {
                WaveRow staged = rowInflaterOrNull.inflate(row, category);
                rows.add(staged == null ? row : staged);
            }
        }
        int n = rows.size();
        long window = Math.round((double) flyInStaggerSec * ticksPerSecond);
        long[] offsets = new long[n];
        if (window > 0L && n > 1) {
            java.util.Random rng = new java.util.Random(flyInSeed(waveIndex, tick));
            for (int i = 1; i < n; i++) {
                offsets[i] = 1L + (long) (rng.nextDouble() * window);
            }
            java.util.Arrays.sort(offsets, 1, n);
        }
        List<PendingRow> staged = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            staged.add(new PendingRow(rows.get(i), tick + offsets[i]));
        }
        if (pendingRows.isEmpty()) {
            pendingRows.addAll(staged);
            return;
        }
        List<PendingRow> merged = new ArrayList<>(pendingRows.size() + n);
        merged.addAll(pendingRows);
        merged.addAll(staged);
        merged.sort(java.util.Comparator.comparingLong(PendingRow::releaseTick));
        pendingRows.clear();
        pendingRows.addAll(merged);
    }

    /** Seed: (category, wave index, round ordinal, stage tick) — replay-stable. */
    private long flyInSeed(int waveIndex, long tick) {
        long h = 1125899906842597L;
        h = 31L * h + category.hashCode();
        h = 31L * h + waveIndex;
        h = 31L * h + roundOrdinal;
        h = 31L * h + tick;
        return h;
    }

    /**
     * The operator wave-skip's schedule half — arm a one-shot force of the
     * NEXT unspawned wave, for the window where the clear leg is structurally dead (pre-w2 on
     * the fortress: no wave-side hull has ever been SEEN, so {@code enemyLiveKnown} cannot arm).
     * The skip verb's kill half covers every live-field case through the normal
     * clear machinery; this covers only the empty-field-and-undrained gap. Requires the timer
     * armed (a seatless round must never skip-spawn — the law) and an unspawned wave
     * left; returns whether the force armed, for the caller's receipt line. Consumed by the
     * next {@link #drainDue} exactly like a timer firing: ONE wave, never a chain.
     *
     * <p>Also refuses while the {@link #CLEAR_HOLD_SEC clear hold} is
     * counting — a hulk-only field reads killed=0 (dead Omega parents sit INVULNERABLE through
     * their 2–3 s ShardSpawner child delay), and, before the hold existed, that fell through to this force and
     * spawned the next wave STRAIGHT THROUGH the hold (the F11-hammer runaway). While
     * the hold counts, the clear leg owns the window; the force exists only for the
     * never-observed pre-w2 dead window, where the hold cannot be armed.
     *
     * <p>The pending-zero guard: ALSO refuses while a
     * standing enemy-live-0 observation is waiting for its anchoring drain. The hold's anchor
     * is set inside {@link #drainDue}, but the serve runs the operator-command drain (the skip
     * press) BEFORE the frame's {@code drainDue} — so on the ONE frame after any capture that
     * read zero (a skip's own kill frame K → K+1, the last omega parent's natural death) the
     * guard saw {@code clearHoldArmed()==false}, the force armed, and the same frame's drain
     * anchored the hold AND force-spawned = a wave straight through the hold, the exact
     * F11-hammer path the hold law claimed closed (one open tick per zero run). A zero observation
     * that will anchor a hold belongs to the clear leg from the CAPTURE, not from the drain.
     */
    public boolean forceNextDue() {
        if (!timerArmed || drained() || clearHoldArmed()
                || (enemyLiveKnown && lastEnemyLive == 0L)) {
            return false;
        }
        forceNextDueOnce = true;
        return true;
    }

    /**
     * The operator {@code pause-wave} verb (F12 in-game via the launcher's
     * log-tail bridge) — pause the wave feed during a voice/HUD test so the operator can inspect
     * without the next wave landing. Paused: {@link #drainDue} returns nothing and the timer
     * clock freezes (the arm base shifts forward by the paused span on resume — never a
     * resume-chain / catch-up burst; a clear-due or forced wave simply waits for resume).
     */
    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    /** Flips {@link #setPaused}; returns the NEW state (the operator receipt line's value). */
    public boolean togglePaused() {
        paused = !paused;
        return paused;
    }

    public boolean paused() {
        return paused;
    }

    /**
     * Arm the timer leg at the round's first live-seat MOUNT (idempotent
     * latch — later mounts/remounts never re-base the clock). The desync root: dueTick
     * compiled ABSOLUTE on the round clock, so a round whose first human booted minutes after
     * round-open had already burned the 90 s fallback — wave_spawn fired into a seatless
     * field. The timer now measures from the first seat's arrival ({@code armTimer(tickNow)}
     * at the fly-in exec; {@code armTimer(0)} at the wrapped-round pre-deploy, whose ready
     * seats are mounted at round-open). An unarmed round never times out — correct: no
     * humans to fight the wave, and match-over cannot trigger unarmed either. The clear leg
     * is untouched (already arm-gated upstream behind matchOver suppression).
     */
    public void armTimer(long baseTick) {
        if (!timerArmed) {
            timerArmed = true;
            timerArmBaseTick = Math.max(baseTick, 0L);
        }
    }

    /**
     * The snapshot source's per-tick enemy-live feed (the wave side's live hull count off the
     * engine allegiance read). Arms the spawn-on-clear leg; never called while the round's
     * match-over triggers are still suppressed, so waves cannot clear-spawn before the match
     * has even armed.
     */
    public void observeEnemyLive(long liveCount) {
        enemyLiveKnown = true;
        lastEnemyLive = liveCount;
        if (liveCount != 0L) {
            // Enemies (re-)entered — late-fading ShardSpawner children land here,
            // so an armed clear hold restarts from scratch at the next zero.
            clearZeroSinceTick = -1L;
        }
    }

    /**
     * Tick of the drain read that started the current sustained-zero
     * window, or -1 when the hold is not counting — the serve's {@code wave_clear_hold}
     * transition rows and the skip receipt's hold branch read it.
     */
    public long clearHoldSinceTick() {
        return clearZeroSinceTick;
    }

    /** True while the clear hold is counting down a sustained-zero window. */
    public boolean clearHoldArmed() {
        return clearZeroSinceTick >= 0L;
    }

    /**
     * The skip receipt's hold-branch read — true
     * while the hold counts OR a standing zero observation is about to anchor it at this
     * frame's drain (the one-tick window, where {@link #forceNextDue} now refuses).
     * Reading {@link #clearHoldArmed()} alone there reports "nothing to skip" on that frame
     * — a dead-button reply, the F11-hammer trigger. Never true once no wave is left.
     */
    public boolean clearHoldArmedOrPending() {
        // F1: in endless mode a wave is ALWAYS left (the chain wraps), so the post-clear zero
        // of the last wave anchors a real hold — the wrap's own countdown.
        return (nextWave < waves.size() || endless)
                && (clearZeroSinceTick >= 0L || (enemyLiveKnown && lastEnemyLive == 0L));
    }

    /** The compiled clear-hold length in ticks (the serve's armed log row). */
    public long clearHoldTicks() {
        return clearHoldTicks;
    }

    /**
     * True once every scheduled wave has been drained — the clear trigger's schedule gate.
     * Staged-but-unreturned rows ((104)(c) stagger) count as undrained: a match must not
     * end while a wave's tail is still deploying.
     */
    public boolean drained() {
        // F1: an endless chain is never drained — the match-over latch's schedule gate stays
        // shut for the whole round (the wrap in drainDue re-arms the plan instead).
        return !endless && nextWave >= waves.size() && pendingRows.isEmpty();
    }

    /**
     * Rows due to spawn BEFORE the advance of {@code tick} (empty most ticks). A wave is due
     * when its ARM-based timer tick arrived ({@code armed && tick - armBase >= dueTick},
     * the arm rule — never fires unarmed), or (spawn-on-clear) when every prior wave has spawned and
     * the enemy-live observation has read zero SUSTAINED for the {@link #CLEAR_HOLD_SEC} hold
     * (a momentary zero the tick the parents die is not a cleared field).
     * Draining marks the wave spawned and consumes the enemy-live observation.
     */
    public List<WaveRow> drainDue(long tick) {
        return drainDue(tick, 0);
    }

    /**
     * wave-spawn stagger: the batch drain spread over
     * frames. {@code maxRows <= 0} = the legacy batch shape (every due wave's rows in one call —
     * the pre-stagger contract rows bind it verbatim). {@code maxRows >= 1}: a wave's rows move
     * to a pending stage when it comes due (due-ness evaluated ONCE, at stage time — the
     * timer law and clear/force semantics are strictly upstream and untouched) and at most
     * {@code maxRows} rows return per call, so the caller's per-hull engine deploys — the
     * measured t1500 {@code pre}-bracket batch cost, 4.5-6.3 ms/hull warm — spread across
     * consecutive frames. While rows pend, the NEXT wave's due-ness is not evaluated (waves
     * stage one at a time; a timer-due successor waits the few staging ticks — cadence is
     * minutes, the delay is frames). {@link #drained()} counts pending rows, so the clear
     * trigger and match-over gate can never fire mid-stage.
     */
    public List<WaveRow> drainDue(long tick, int maxRows) {
        // Pause: freeze the clock (the ONLY time base is timerArmBaseTick — dueTicks
        // are arm-relative, clear/force are event-latched and simply wait), never catch up.
        if (paused) {
            if (pauseStartTick < 0L) {
                pauseStartTick = tick;
            }
            return List.of();
        } else if (pauseStartTick >= 0L) {
            if (timerArmed) {
                // Only the span AFTER the arm is frozen time on the timer's clock: a pause that
                // began before armTimer() must not push the base past "now".
                timerArmBaseTick += Math.max(0L, tick - Math.max(pauseStartTick, timerArmBaseTick));
            }
            // A staged spread is frozen time too — every pending release shifts by
            // the paused span, so resume never lands the tail as a catch-up burst.
            long pausedSpan = Math.max(0L, tick - pauseStartTick);
            if (pausedSpan > 0L && !pendingRows.isEmpty()) {
                List<PendingRow> shifted = new ArrayList<>(pendingRows.size());
                for (PendingRow pending : pendingRows) {
                    shifted.add(new PendingRow(pending.row(), pending.releaseTick() + pausedSpan));
                }
                pendingRows.clear();
                pendingRows.addAll(shifted);
            }
            pauseStartTick = -1L;
        }
        // The clear-hold countdown keys on the drain read (observeEnemyLive
        // carries no tick) — the first drain that sees a standing zero
        // observation latches this tick; only a field STILL zero clearHoldTicks later is
        // cleared. Late-fading ShardSpawner children reset the anchor through the non-zero
        // observation, so an omega clear can never chain-spawn onto its own shards.
        // Receipt nit: anchor only while a wave is
        // LEFT to hold for — a drained plan's post-clear zero used to latch too, so the skip
        // receipt / wave_clear_hold rows read "field clearing" with nothing left to spawn.
        // An endless chain's spent plan (no index left, no staged tail) is
        // ALSO a wave "left to hold for" — its post-clear zero anchors the wrap's hold.
        boolean planSpent = nextWave >= waves.size() && pendingRows.isEmpty();
        if (enemyLiveKnown && lastEnemyLive == 0L && clearZeroSinceTick < 0L
                && (nextWave < waves.size() || (endless && planSpent))) {
            clearZeroSinceTick = tick;
        }
        // F1 WRAP: the plan is spent and the field stayed zero through the hold (or the
        // operator forced the next wave — the skip verb on an empty spent field): index back
        // to 0, cycle +1. Wave 2 then stages in the SAME drain below through its normal clear
        // leg (spawnOnClear + the very hold that just elapsed; its timer is long past too),
        // so the wrap is never an extra, silent spawn path — it only re-opens the plan.
        if (endless && planSpent
                && (forceNextDueOnce
                        || (enemyLiveKnown && lastEnemyLive == 0L && clearZeroSinceTick >= 0L
                                && tick - clearZeroSinceTick >= clearHoldTicks))) {
            nextWave = 0;
            cycle++;
            lastWrapTick = tick;
        }
        boolean unlimited = maxRows <= 0;
        while ((unlimited || pendingRows.isEmpty()) && nextWave < waves.size()) {
            Wave wave = waves.get(nextWave);
            boolean tickDue = wave.dueTick() != NO_TIMER && timerArmed
                    && (tick - timerArmBaseTick) >= wave.dueTick();
            boolean clearDue = wave.spawnOnClear() && enemyLiveKnown && lastEnemyLive == 0L
                    && clearZeroSinceTick >= 0L && tick - clearZeroSinceTick >= clearHoldTicks;
            boolean forceDue = forceNextDueOnce;
            if (!tickDue && !clearDue && !forceDue) {
                break;
            }
            forceNextDueOnce = false;
            // Release ticks assigned here. A live-roster
            // supplier truncates a curve wave to its prefix under r × static target.
            stageWave(liveStageRows(wave, nextWave), nextWave, tick);
            nextWave++;
            enemyLiveKnown = false;
            clearZeroSinceTick = -1L; // The spawn changes the world — re-hold fresh
        }
        if (pendingRows.isEmpty()) {
            return List.of();
        }
        // Pop only rows whose release tick has arrived (head-only read — the deque is
        // release-sorted), still at most maxRows per call (the (104)(c) frame pacing rides on
        // top of the spread, never instead of it).
        int cap = unlimited ? pendingRows.size() : Math.min(maxRows, pendingRows.size());
        List<WaveRow> due = new ArrayList<>(cap);
        while (due.size() < cap && !pendingRows.isEmpty()
                && pendingRows.peek().releaseTick() <= tick) {
            due.add(pendingRows.poll().row());
        }
        return due;
    }
}
