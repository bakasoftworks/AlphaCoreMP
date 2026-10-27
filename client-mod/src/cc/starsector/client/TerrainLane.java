package cc.starsector.client;

import cc.starsector.server.runtime.TerrainStatLaw;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI;
import java.awt.Color;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Battle TERRAIN on the serve -- the CLIENT terrain lane.
 *
 *
 * <p>Two halves. (1) {@link #apply} = the typed twin of the server's
 * {@code cc.starsector.server.bridge.TerrainStatSeam.applyToShip}: the SAME {@link TerrainStatLaw}
 * over the SAME knob pair ({@link ClientWireKnobs#terrainValue} / {@link
 * ClientWireKnobs#terrainLevelPercent}) and the ship's own {@code corona_resistance}, the SAME
 * write order and target set (hulks skipped; fighters skip the corona pair) -- so own ship and
 * replicas carry the server's numbers with no per-tick wire. Every call writes ALL
 * five stats: {@code modifyMult} when active, {@code unmodify(key)} otherwise (a 1.0 entry is
 * never installed for "off"). It rides every client deploy-CR site ({@link CrDeploySeam}) and the
 * knob-change sweep below. (2) {@link #advance} = the per-frame hook right after PRCP's pause
 * gate: ONE volatile epoch read in steady state; a change (or a fresh engine) re-sweeps the ships
 * and re-presents the one-shot background glow / colour / starfield; while a terrain is active the
 * vanilla MAINTAIN-semantics calls (status rows; abyss flame fade/shrink) are re-issued per frame
 * -- zero work at {@code none}. Source: vanilla's
 * {@code BattleCreationPluginImpl}
 * shapes, strings verbatim incl. the "missle" typo.
 *
 * <p>B-legal: api-jar types, {@link java.awt.Color}, the bundled {@link TerrainStatLaw} /
 * {@code WireKnob} and our own client classes only -- no engine-internal type.
 */
final class TerrainLane {
    /** Vanilla's registered icon paths (stock settings.json) -- the fallback when
     *  {@code Global} is unset (game-free JVM); in game {@code getSpriteName} returns the same. */
    static final String ICON_CR_FALLBACK = "graphics/icons/tactical/cr_tactical3.png";
    static final String ICON_ENGINE_FALLBACK = "graphics/icons/tactical/engine_damage.png";

    private static final String RESISTANCE = "corona_resistance";
    private static final Set<String> failuresLogged = new HashSet<String>();
    /** Ships written so far (smoke + log reads). */
    static int applied;

    /** The lane-field idiom (unused today; kept for plugin-state reads). */
    private final PredictReconcileCombatPlugin plugin;
    private int appliedEpoch = -1;
    private CombatEngineAPI baselineEngine;
    private Color baselineBg;
    private Color baselineGlow;
    private boolean baselineGlowNonAdditive;
    private boolean baselineStarfield;

    // per-change presentation cache
    private boolean coronaActive;
    private boolean abyssActive;
    private float depth;
    private String icon;
    private String title;
    private final Object rowKey1 = new Object();
    private final Object rowKey2 = new Object();

    TerrainLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Install (or clear) the terrain mults on one local ship; idempotent and re-callable. Returns
     * true when the five stats were written. Fail-SOFT: a throw logs one row per distinct message.
     */
    static boolean apply(ShipAPI ship) {
        if (ship == null) {
            return false;
        }
        try {
            MutableShipStatsAPI stats = ship.getMutableStats();
            if (stats == null) {
                return false; // selftest proxy shape -- silent
            }
            if (ship.isHulk()) {
                return false;
            }
            // the 1-arg read: 1.0 when unset and never INSERTS (getStat would mint an entry)
            float res = stats.getDynamic().getValue(RESISTANCE);
            TerrainStatLaw.Mults m = TerrainStatLaw.compute(ClientWireKnobs.terrainValue(),
                    ClientWireKnobs.terrainLevelPercent(), res);
            if (m.coronaActive && TerrainStatLaw.coronaApplies(ship.isFighter())) {
                stats.getPeakCRDuration().modifyMult(TerrainStatLaw.PEAK_KEY, m.peak);
                stats.getCRLossPerSecondPercent().modifyMult(TerrainStatLaw.LOSS_KEY, m.loss);
            } else {
                stats.getPeakCRDuration().unmodify(TerrainStatLaw.PEAK_KEY);
                stats.getCRLossPerSecondPercent().unmodify(TerrainStatLaw.LOSS_KEY);
            }
            if (m.abyssActive) {
                stats.getMaxSpeed().modifyMult(TerrainStatLaw.ABYSS_KEY, m.speed);
                stats.getMissileWeaponRangeBonus().modifyMult(TerrainStatLaw.ABYSS_KEY, m.missileRange);
                stats.getMissileMaxSpeedBonus().modifyMult(TerrainStatLaw.ABYSS_KEY, m.missileSpeed);
            } else {
                stats.getMaxSpeed().unmodify(TerrainStatLaw.ABYSS_KEY);
                stats.getMissileWeaponRangeBonus().unmodify(TerrainStatLaw.ABYSS_KEY);
                stats.getMissileMaxSpeedBonus().unmodify(TerrainStatLaw.ABYSS_KEY);
            }
            applied++;
            return true;
        } catch (Throwable t) {
            String key = String.valueOf(t);
            boolean first;
            synchronized (failuresLogged) {
                first = failuresLogged.add(key);
            }
            if (first) {
                PilotWatchLog.info("terrain_stat_apply_failed err=" + key
                        + " (logged once per distinct error; the ship keeps vanilla stats)");
            }
            return false;
        }
    }

    /** Once per frame, after the pause gate ({@code engine} non-null, not paused). */
    void advance(CombatEngineAPI engine) {
        if (engine != baselineEngine) {
            baselineBg = engine.getBackgroundColor();
            baselineGlow = engine.getBackgroundGlowColor();
            baselineGlowNonAdditive = engine.isBackgroundGlowColorNonAdditive();
            baselineStarfield = engine.isRenderStarfield();
            baselineEngine = engine;
            appliedEpoch = -1; // a fresh engine re-applies + re-presents (the sweep is idempotent)
        }
        int e = ClientWireKnobs.terrainEpoch();
        if (e != appliedEpoch) {
            onChange(engine, e);
            appliedEpoch = e;
        }
        if (!coronaActive && !abyssActive) {
            return; // zero per-frame work at none
        }
        try {
            if (coronaActive) {
                engine.maintainStatusForPlayerShip(rowKey1, icon, title, "reduced peak time", true);
                engine.maintainStatusForPlayerShip(rowKey2, icon, title, "faster CR degradation", true);
                return;
            }
            engine.maintainStatusForPlayerShip(rowKey1, icon, title, "-20% top speed", true);
            engine.maintainStatusForPlayerShip(rowKey2, icon, title, "-20% missle speed / range", true);
            float d = depth;
            if (d <= 0f) {
                return;
            }
            List<ShipAPI> ships = engine.getShips();
            if (ships != null) {
                for (ShipAPI s : ships) {
                    if (s == null || s.isHulk()) {
                        continue;
                    }
                    ShipEngineControllerAPI ec = s.getEngineController();
                    if (ec != null) {
                        ec.fadeToOtherColor(this, Color.black, null, 1f, d * 0.4f);
                        ec.extendFlame(this, -0.33f * d, 0f, -0.5f * d);
                    }
                }
            }
            List<MissileAPI> missiles = engine.getMissiles();
            if (missiles != null) {
                for (MissileAPI mi : missiles) {
                    ShipEngineControllerAPI ec = mi == null ? null : mi.getEngineController();
                    if (ec != null) {
                        ec.fadeToOtherColor(this, Color.black, null, 1f, d * 0.4f);
                        ec.extendFlame(this, -0.33f * d, 0f, 0f);
                    }
                }
            }
        } catch (Throwable t) {
            logOnce("terrain_present_failed err=" + t);
        }
    }

    private void onChange(CombatEngineAPI engine, int e) {
        int choice = ClientWireKnobs.terrainValue();
        int pct = ClientWireKnobs.terrainLevelPercent();
        TerrainStatLaw.Mults m = TerrainStatLaw.compute(choice, pct, 1f);
        coronaActive = m.coronaActive;
        abyssActive = m.abyssActive;
        depth = TerrainStatLaw.fraction(pct);
        title = TerrainStatLaw.statusTitle(choice);
        int n = 0;
        List<ShipAPI> ships = engine.getShips();
        if (ships != null) {
            for (ShipAPI s : ships) {
                if (apply(s)) {
                    n++;
                }
            }
        }
        icon = null;
        if (coronaActive || abyssActive) {
            icon = resolveIcon(coronaActive ? "icon_tactical_cr_penalty" : "icon_tactical_engine_damage",
                    coronaActive ? ICON_CR_FALLBACK : ICON_ENGINE_FALLBACK);
        }
        try {
            if (coronaActive) {
                int[] rgb = TerrainStatLaw.glowRgb(choice);
                engine.setBackgroundGlowColor(setAlpha(new Color(rgb[0], rgb[1], rgb[2]),
                        TerrainStatLaw.glowAlpha(pct)));
                engine.setBackgroundGlowColorNonAdditive(false);
                engine.setBackgroundColor(baselineBg);
                engine.setRenderStarfield(baselineStarfield);
            } else if (abyssActive) {
                float d = depth;
                engine.setBackgroundColor(scaleColor(Color.white, 1f - d));
                engine.setBackgroundGlowColor(scaleAlpha(Color.black,
                        d * TerrainStatLaw.ABYSS_OVERLAY_ALPHA));
                engine.setBackgroundGlowColorNonAdditive(true);
                engine.setRenderStarfield(d > TerrainStatLaw.ABYSS_STARFIELD_OFF_DEPTH ? false
                        : baselineStarfield);
            } else {
                engine.setBackgroundColor(baselineBg);
                engine.setBackgroundGlowColor(baselineGlow);
                engine.setBackgroundGlowColorNonAdditive(baselineGlowNonAdditive);
                engine.setRenderStarfield(baselineStarfield);
            }
        } catch (Throwable t) {
            logOnce("terrain_present_failed err=" + t);
        }
        PilotWatchLog.info("terrain lane epoch=" + e + " terrain=" + ClientWireKnobs.terrainWord()
                + " level=" + pct + " ships=" + n + " present="
                + (coronaActive ? (choice == TerrainStatLaw.PULSAR ? "pulsar" : "corona")
                        : abyssActive ? "abyss" : "none"));
    }

    /** The icon ONCE per change: the registered sprite name, or the literal registered path
     *  when {@code Global} is unset (game-free JVM). Package-private for the smoke. */
    static String resolveIcon(String id, String fallback) {
        try {
            String s = Global.getSettings().getSpriteName("ui", id);
            if (s != null && !s.isEmpty()) {
                return s;
            }
        } catch (Throwable t) {
            // game-free JVM: Global unset -> the literal registered path below
        }
        return fallback;
    }

    /**
     * -- the lane's own colour helpers, rewritten from the
     * functional spec: ONE implementation in game and in the game-free smoke
     * JVM (no engine helper class is touched, so no engine static init runs). Each returns a NEW
     * {@link Color}; every channel is clamped to 0..255 and a scaled channel truncates toward 0.
     * This one keeps RGB and replaces alpha with {@code alpha}.
     */
    static Color setAlpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), clampChannel(alpha));
    }

    /** All FOUR channels times {@code factor}: alpha scales with RGB ({@code TerrainLaneSmoke} R6
     *  pins {@code scaleColor(white, 0)} = transparent black, the abyss d=1 background). */
    static Color scaleColor(Color c, float factor) {
        return new Color(scaleChannel(c.getRed(), factor), scaleChannel(c.getGreen(), factor),
                scaleChannel(c.getBlue(), factor), scaleChannel(c.getAlpha(), factor));
    }

    /** RGB kept, alpha times {@code factor}. */
    static Color scaleAlpha(Color c, float factor) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), scaleChannel(c.getAlpha(), factor));
    }

    /** One channel times {@code factor}, truncated toward 0, then clamped (NaN reads 0). */
    private static int scaleChannel(int channel, float factor) {
        return clampChannel((int) (channel * factor));
    }

    private static int clampChannel(int v) {
        return v < 0 ? 0 : v > 255 ? 255 : v;
    }

    private static void logOnce(String msg) {
        boolean first;
        synchronized (failuresLogged) {
            first = failuresLogged.add(msg);
        }
        if (first) {
            PilotWatchLog.info(msg + " (logged once per distinct error)");
        }
    }

    // test hooks (smoke reads)
    boolean presentingForSmoke() {
        return coronaActive || abyssActive;
    }

    String iconForSmoke() {
        return icon;
    }
}
