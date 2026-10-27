package cc.starsector.client;

import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;

/**
 * opt-in camera lock for a PILOTED rig window — {@code -Dcc.watch.cameraLockPlayer=true}.
 *
 * <p>WHY: an unattended piloted window (run-mote-census-rig, the vanilla twin) captured a BLANK world after the
 * burn-in: the engine's mouse-edge camera pan runs in an unfocused window whose cursor sits at an
 * edge, so the view walks off the stationary player ship to the pan limit (camera x converged to ≈-1585 su in
 * BOTH the served and the serverless reels while the ship stayed at its deploy column) and every world layer
 * leaves the viewport. The observer window never suffered it because {@link ObserverCameraLane} owns the
 * viewport every frame ({@code setExternalControl(true)} + {@code set(llx,lly,w,h)}); this is the same
 * mechanism pinned to {@code engine.getPlayerShip()} instead. Presentation only, per-window, default OFF —
 * a normal play window keeps the stock camera. Zoom is left as the engine has it (visible width/height re-read).
 *
 * <p><b>{@code engagement} mode:</b> {@code -Dcc.watch.cameraLockPlayer=engagement}
 * pins the viewport to the ENEMY hull nearest any own-side hull instead of the player ship, STICKY while that
 * hull's body is still in play (alive or hulk — the observer's S6 hold), re-picked the frame it leaves. WHY:
 * the threat twin (`fidelity-threat/0904-072309`) and the shrouded twins all put the
 * seat alone on camera for the whole reel — a standoff foe (a {@code threat_timid} hive unit) or a foe that
 * fights the station never comes within a screen of the seat, so a seat-locked twin reel grades NOTHING; the
 * served observer already has {@code -Dcc.observer.follow=engagement}
 * ({@link ObserverCameraLane#nearestEnemyToAnyAlly}); this is the SAME law on the local engine's ship list so
 * a matched pair can follow the foe on BOTH reels. Fighters and station modules are never picked or counted
 * as anchors (the observer's remote-entity list carries neither).
 */
final class PlayerCameraLock extends BaseCombatLayeredRenderingPlugin {
    static final String PROP = "cc.watch.cameraLockPlayer";
    static final String MODE_ENGAGEMENT = "engagement";
    private static final String MODE = System.getProperty(PROP, "");
    static final boolean ENGAGEMENT = MODE_ENGAGEMENT.equalsIgnoreCase(MODE);
    static final boolean ON = ENGAGEMENT || "true".equalsIgnoreCase(MODE) || "seat".equalsIgnoreCase(MODE);

    private static volatile boolean dead;
    private static volatile boolean touched;

    /** engagement mode: the followed enemy hull (sticky while its body is in play). */
    private ShipAPI followed;

    /** Application-load first-touch (never under the combat SecurityManager); a no-op unless armed. */
    static void startAtApplicationLoad() {
        if (!ON) {
            return;
        }
        new PlayerCameraLock();
        touched = true;
        PilotWatchLog.info("player camera lock ARMED (" + PROP + "=" + MODE + "): viewport pinned to "
                + (ENGAGEMENT ? "the enemy hull nearest any ally (sticky while in play)" : "the player ship") + " every frame");
    }

    /** Per-combat registration from {@code PredictReconcileCombatPlugin.init}. */
    static void registerIfArmed(CombatEngineAPI engine) {
        if (!ON || dead || !touched || engine == null) {
            return;
        }
        try {
            engine.addLayeredRenderingPlugin(new PlayerCameraLock());
        } catch (Throwable t) {
            dead = true;
            PilotWatchLog.error("player camera lock: register FAILED (lane dead, match unaffected)", t);
        }
    }

    @Override
    public float getRenderRadius() {
        return 100000.0F; // never culled; we never draw
    }

    @Override
    public boolean isExpired() {
        return dead;
    }

    @Override
    public void advance(float amount) {
        if (dead) {
            return;
        }
        try {
            CombatEngineAPI engine = com.fs.starfarer.api.Global.getCombatEngine();
            if (engine == null) {
                return;
            }
            ShipAPI ship = ENGAGEMENT ? engagementAnchor(engine) : engine.getPlayerShip();
            ViewportAPI vp = engine.getViewport();
            if (ship == null || vp == null || ship.getLocation() == null) {
                return;
            }
            float visW = vp.getVisibleWidth();
            float visH = vp.getVisibleHeight();
            if (!(visW > 0.0F) || !(visH > 0.0F)) {
                return;
            }
            vp.setExternalControl(true);
            vp.set(ship.getLocation().x - visW / 2.0F, ship.getLocation().y - visH / 2.0F, visW, visH);
        } catch (Throwable t) {
            dead = true;
            PilotWatchLog.error("player camera lock: lane dead (fail-soft)", t);
        }
    }

    /**
     * The observer's engagement law on the local engine: keep the followed enemy while its body is in play
     * (S6 hulk hold), else the alive enemy hull nearest any alive own-side hull, else the player ship
     * (pre-contact / no enemy left). A pick change logs once.
     */
    private ShipAPI engagementAnchor(CombatEngineAPI engine) {
        if (followed != null && engine.isEntityInPlay(followed)) {
            return followed;
        }
        if (followed != null) {
            PilotWatchLog.info("player camera lock: engagement target gone (" + hullOf(followed) + ") -> re-pick");
            followed = null;
        }
        ShipAPI best = null;
        float bestD2 = Float.MAX_VALUE;
        for (ShipAPI foe : engine.getShips()) {
            if (!hull(foe) || foe.getOwner() != 1 || !foe.isAlive() || foe.getLocation() == null) {
                continue;
            }
            for (ShipAPI ally : engine.getShips()) {
                if (!hull(ally) || ally.getOwner() != 0 || !ally.isAlive() || ally.getLocation() == null) {
                    continue;
                }
                float dx = foe.getLocation().x - ally.getLocation().x;
                float dy = foe.getLocation().y - ally.getLocation().y;
                float d2 = dx * dx + dy * dy;
                if (d2 < bestD2) { // NaN never wins
                    bestD2 = d2;
                    best = foe;
                }
            }
        }
        if (best != null) {
            followed = best;
            PilotWatchLog.info("player camera lock: engagement follow -> enemy " + hullOf(best)
                    + " at (" + Math.round(best.getLocation().x) + "," + Math.round(best.getLocation().y) + ")"
                    + " nearest-ally dist=" + Math.round(Math.sqrt(bestD2)));
            return best;
        }
        return engine.getPlayerShip();
    }

    /** A HULL for the follow law: never a fighter, never a station module (the observer's list carries neither). */
    private static boolean hull(ShipAPI s) {
        return s != null && !s.isFighter() && !s.isStationModule();
    }

    private static String hullOf(ShipAPI s) {
        return s.getHullSpec() != null ? s.getHullSpec().getHullId() : "?";
    }
}
