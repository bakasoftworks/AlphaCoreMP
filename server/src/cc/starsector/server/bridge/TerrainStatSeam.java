package cc.starsector.server.bridge;

import cc.starsector.server.runtime.TerrainStatLaw;
import cc.starsector.server.runtime.WireKnob;
import cc.starsector.server.runtime.WireKnobState;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The SERVER's terrain stat
 * seam — installs the {@link TerrainStatLaw} mults on the live authoritative ships. Called right
 * after each {@link CrDeploySeam#applyDeployCr} site (seats, the t=0 roster + module children,
 * waves/adhoc/at-rest, engine-born growth — every fighter/drone the engine mints, so abyss reaches
 * wings) and once over every live ship on a knob change ({@link #reapplyAll} = the
 * {@code AdminTerrainVerb.reapplyHook}; serve thread = sim thread, pre-advance — never per tick;
 * vanilla's per-frame abyss loop is REPLACED because {@code Ship} reads the stats live
 * each frame and a keyed mult persists until {@code unmodify}).
 *
 * <p>Reflective on the PUBLIC API types only ({@code ShipAPI} / {@code MutableShipStatsAPI} /
 * {@code DynamicStatsAPI} / {@code StatBonus} / {@code MutableStat}; the {@link CrDeploySeam} +
 * {@link AdminSeatDebugEnginePort} idiom, handles resolved ONCE on the first ship's classloader).
 * Fail-SOFT: a terrain mult must never kill a deploy or a serve — a throw logs one
 * {@code terrain_stat_apply_failed} row per distinct message and the ship keeps its vanilla stats.
 * Every call writes ALL five stats — {@code modifyMult} when that terrain is active for the ship,
 * {@code unmodify(key)} otherwise — so a corona → abyss → none switch leaves no stale mult
 * ({@code modifyMult(key, 1.0)} would keep a 1.0 entry).
 * Client twin: {@code cc.starsector.client.TerrainLane} (typed) — same law class, same target
 * set ({@link TerrainStatLaw#coronaApplies}: fighters skip corona; hulks skipped here); the parity
 * walker already dumps the four StatBonus values per ship, so a drift shows with no new instrument.
 */
public final class TerrainStatSeam {

    private static final String ENGINE_CLASS = "com.fs.starfarer.combat.CombatEngine";
    private static final String SHIP_API = "com.fs.starfarer.api.combat.ShipAPI";
    private static final String STATS_API = "com.fs.starfarer.api.combat.MutableShipStatsAPI";
    // The class lives in api.util (jar listing; SideTotalsCapture uses the same name) — the
    // api.combat spelling failed every live resolve (applied=0, resolve_failed_skips=305) while
    // applyForTest bypassed Class.forName, so the suite never saw it. A rider adds the real-resolve row.
    private static final String DYNAMIC_API = "com.fs.starfarer.api.util.DynamicStatsAPI";
    private static final String STAT_BONUS = "com.fs.starfarer.api.combat.StatBonus";
    private static final String MUTABLE_STAT = "com.fs.starfarer.api.combat.MutableStat";
    private static final String RESISTANCE = "corona_resistance";

    private static volatile Handles handles;
    /**
     * The cached handle-resolve failure. Once set, every later call returns
     * false fast (no new {@code Class.forName}/{@code getMethod}). Keyed to NO classloader on
     * purpose: the serve has ONE engine loader, so a later ship from a different loader that could
     * resolve is not a case this seam meets.
     *
     */
    private static volatile Throwable handlesFailure;
    private static final Set<String> failuresLogged = new HashSet<String>();
    private static int applied;
    // MEASURE meters (serve thread = sim thread;
    // plain statics like `applied`). Timed = every call that reached the reflective block.
    private static long applyNanos;
    private static long applyMax;
    private static int timedCalls;
    private static int skippedOff;
    private static int resolveFailedSkips;

    private TerrainStatSeam() {
    }

    /** The live knob pair as the law's inputs. */
    static int terrainChoice() {
        return WireKnobState.get(WireKnob.TERRAIN);
    }

    static int levelPercent() {
        return WireKnob.TERRAIN_LEVEL.choicePercent(WireKnobState.get(WireKnob.TERRAIN_LEVEL));
    }

    /**
     * Install (or clear) the terrain mults on one live ship. Hulks are skipped (as vanilla
     * does). Returns true when the five stats were written. This is the BIRTH entry
     * (every birth site): under an inactive law a fresh ship has nothing to unmodify, so it returns
     * false before any reflection.
     *
     */
    public static boolean applyToShip(Object ship) {
        return apply(ship, true);
    }

    /**
     * TEST-ONLY seam (the contract rows live in another package, hence public): the birth /
     * reapply split without a {@code CombatEngine}. Never called by serve code.
     *
     */
    public static boolean applyForTest(Object ship, boolean birth) {
        return apply(ship, birth);
    }

    /**
     * {@code birth} = a fresh ship (early-out when neither
     * terrain is active: nothing to unmodify); {@code !birth} = {@link #reapplyAll}, the full write
     * that clears stale mults on a corona -&gt; none switch. The early-out reads the law with a
     * resistance of 1: the active flags do NOT depend on resistance
     * ({@link TerrainStatLaw#compute}: corona and abyss each set their own flag, level 0 = OFF).
     */
    private static boolean apply(Object ship, boolean birth) {
        if (ship == null) {
            return false;
        }
        if (birth) {
            TerrainStatLaw.Mults m0 = TerrainStatLaw.compute(terrainChoice(), levelPercent(), 1f);
            if (!m0.coronaActive && !m0.abyssActive) {
                skippedOff++;
                return false;
            }
        }
        if (handlesFailure != null) {
            resolveFailedSkips++;
            return false;
        }
        long t0 = System.nanoTime();
        try {
            Handles h = handlesFor(ship);
            if (h == null) {
                return false;
            }
            if ((Boolean) h.isHulk.invoke(ship)) {
                return false;
            }
            Object stats = h.getMutableStats.invoke(ship);
            float res = ((Number) h.dynGetValue.invoke(h.getDynamic.invoke(stats), RESISTANCE))
                    .floatValue();
            TerrainStatLaw.Mults m = TerrainStatLaw.compute(terrainChoice(), levelPercent(), res);
            boolean fighter = (Boolean) h.isFighter.invoke(ship);
            Object peak = h.getPeakCRDuration.invoke(stats);
            Object loss = h.getCRLossPerSecondPercent.invoke(stats);
            if (m.coronaActive && TerrainStatLaw.coronaApplies(fighter)) {
                h.bonusModifyMult.invoke(peak, TerrainStatLaw.PEAK_KEY, m.peak);
                h.bonusModifyMult.invoke(loss, TerrainStatLaw.LOSS_KEY, m.loss);
            } else {
                h.bonusUnmodify.invoke(peak, TerrainStatLaw.PEAK_KEY);
                h.bonusUnmodify.invoke(loss, TerrainStatLaw.LOSS_KEY);
            }
            Object speed = h.getMaxSpeed.invoke(stats);
            Object mRange = h.getMissileWeaponRangeBonus.invoke(stats);
            Object mSpeed = h.getMissileMaxSpeedBonus.invoke(stats);
            if (m.abyssActive) {
                h.statModifyMult.invoke(speed, TerrainStatLaw.ABYSS_KEY, m.speed);
                h.bonusModifyMult.invoke(mRange, TerrainStatLaw.ABYSS_KEY, m.missileRange);
                h.bonusModifyMult.invoke(mSpeed, TerrainStatLaw.ABYSS_KEY, m.missileSpeed);
            } else {
                h.statUnmodify.invoke(speed, TerrainStatLaw.ABYSS_KEY);
                h.bonusUnmodify.invoke(mRange, TerrainStatLaw.ABYSS_KEY);
                h.bonusUnmodify.invoke(mSpeed, TerrainStatLaw.ABYSS_KEY);
            }
            applied++;
            return true;
        } catch (Throwable t) {
            logOnce(ship, t, "");
            return false;
        } finally {
            // Includes the one-time resolve on the first call
            long dt = System.nanoTime() - t0;
            applyNanos += dt;
            if (dt > applyMax) {
                applyMax = dt;
            }
            timedCalls++;
        }
    }

    /**
     * The existing log-once-per-distinct-error idiom (shared by the apply and resolve paths).
     *
     */
    private static void logOnce(Object ship, Throwable t, String note) {
        String key = String.valueOf(t);
        synchronized (failuresLogged) {
            if (failuresLogged.add(key)) {
                System.out.println("npeer-serve terrain_stat_apply_failed ship="
                        + ship.getClass().getName() + " err=" + key + note
                        + " (logged once per distinct error; the ship keeps vanilla stats)");
            }
        }
    }

    /**
     * One pass over every live ship ({@code CombatEngine.getInstance().getShips()}, the
     * {@link SeatTeamEnginePort} idiom) — the knob-change hook. Returns the ships written.
     */
    public static int reapplyAll() {
        try {
            Class<?> engineClass = Class.forName(ENGINE_CLASS, true,
                    TerrainStatSeam.class.getClassLoader());
            Object engine = engineClass.getMethod("getInstance").invoke(null);
            Object ships = engine == null ? null : engineClass.getMethod("getShips").invoke(engine);
            int n = 0;
            if (ships instanceof List<?>) {
                for (Object ship : (List<?>) ships) {
                    if (apply(ship, false)) { // full write, never the birth early-out (r5)
                        n++;
                    }
                }
            }
            return n;
        } catch (Throwable t) {
            System.out.println("npeer-serve terrain_stat_reapply_failed err=" + t);
            return 0;
        }
    }

    /** Ships written so far (log rows / contract reads). */
    public static int appliedCount() {
        return applied;
    }

    /** Total ns over every call that reached reflection. */
    public static long appliedNanosTotal() {
        return applyNanos;
    }

    /** The worst single call (ns). */
    public static long appliedNanosMax() {
        return applyMax;
    }

    /** Calls timed into {@link #appliedNanosTotal} (avg divisor). */
    public static int timedCallCount() {
        return timedCalls;
    }

    /** Births skipped because no terrain was active. */
    public static int skippedOffCount() {
        return skippedOff;
    }

    /** Calls skipped on the cached resolve failure. */
    public static int resolveFailedSkipCount() {
        return resolveFailedSkips;
    }

    /**
     * TEST-ONLY (contract rows; never serve code): clears the resolved handles, the cached
     * failure, the log-once set and every meter so rows can order a fresh resolve attempt.
     *
     */
    public static void resetForTest() {
        handles = null;
        handlesFailure = null;
        synchronized (failuresLogged) {
            failuresLogged.clear();
        }
        applied = 0;
        applyNanos = 0L;
        applyMax = 0L;
        timedCalls = 0;
        skippedOff = 0;
        resolveFailedSkips = 0;
    }

    /**
     * Resolve once; on a throw CACHE the failure, log it once, return null —
     * every later call short-circuits on {@link #handlesFailure} in {@link #apply}.
     *
     */
    private static Handles handlesFor(Object ship) {
        Handles h = handles;
        if (h == null) {
            try {
                h = new Handles(ship.getClass().getClassLoader());
            } catch (Throwable t) {
                handlesFailure = t;
                logOnce(ship, t, " (handle resolve failed; cached, later calls skip)");
                return null;
            }
            handles = h;
        }
        return h;
    }

    /** Resolved once on the API interfaces (public members; no engine-internal names). */
    private static final class Handles {
        final Method isHulk;
        final Method isFighter;
        final Method getMutableStats;
        final Method getDynamic;
        final Method dynGetValue;
        final Method getPeakCRDuration;
        final Method getCRLossPerSecondPercent;
        final Method getMaxSpeed;
        final Method getMissileWeaponRangeBonus;
        final Method getMissileMaxSpeedBonus;
        final Method bonusModifyMult;
        final Method bonusUnmodify;
        final Method statModifyMult;
        final Method statUnmodify;

        Handles(ClassLoader loader) throws Exception {
            Class<?> shipApi = Class.forName(SHIP_API, false, loader);
            Class<?> statsApi = Class.forName(STATS_API, false, loader);
            Class<?> dynamicApi = Class.forName(DYNAMIC_API, false, loader);
            Class<?> statBonus = Class.forName(STAT_BONUS, false, loader);
            Class<?> mutableStat = Class.forName(MUTABLE_STAT, false, loader);
            isHulk = shipApi.getMethod("isHulk");
            isFighter = shipApi.getMethod("isFighter");
            getMutableStats = shipApi.getMethod("getMutableStats");
            getDynamic = statsApi.getMethod("getDynamic");
            // the 1-arg read: returns 1.0 when unset and never INSERTS;
            // getStat(...) would mint a MutableStat(1.0) into the map on every un-shielded ship.
            dynGetValue = dynamicApi.getMethod("getValue", String.class);
            getPeakCRDuration = statsApi.getMethod("getPeakCRDuration");
            getCRLossPerSecondPercent = statsApi.getMethod("getCRLossPerSecondPercent");
            getMaxSpeed = statsApi.getMethod("getMaxSpeed");
            getMissileWeaponRangeBonus = statsApi.getMethod("getMissileWeaponRangeBonus");
            getMissileMaxSpeedBonus = statsApi.getMethod("getMissileMaxSpeedBonus");
            bonusModifyMult = statBonus.getMethod("modifyMult", String.class, float.class);
            bonusUnmodify = statBonus.getMethod("unmodify", String.class);
            statModifyMult = mutableStat.getMethod("modifyMult", String.class, float.class);
            statUnmodify = mutableStat.getMethod("unmodify", String.class);
        }
    }
}
