package cc.starsector.server.bridge;

import java.lang.reflect.Method;

/**
 * The engine-native deploy-CR seam.
 *
 * <p>WHY: {@code Ship.setCRAtDeployment} writes {@code crAtDeployment} AND {@code currentCR}
 * with NO stat application, and {@code Ship.setCurrentCR}
 * crosses {@code RepairTracker.applyCRToStats} only on a value CHANGE.
 * The old "setCRAtDeployment then setCurrentCR" pair therefore never applied the vanilla
 * {@code cr_effect} block (+-10% speed/damage at cr=1.0, AutofireAimAccuracy at EVERY cr) --
 * the authoritative sim fought un-CR-buffed while clients (crossing the seam via wire-CR
 * changes) fought buffed: the observed 174-vs-182 max-speed desync.
 *
 * <p>THE LAW (vanilla deploy verbatim): give a ship its
 * deployment CR by calling the engine's own statics
 * {@code RepairTracker.applyCRToStats(cr, stats, hullSize)} then
 * {@code RepairTracker.applyCRToShip(cr, ship)}. The second stamps BOTH CR fields itself
 * (the api's {@code CRPluginImpl} calls {@code setCRAtDeployment}), so no field writes
 * belong beside these calls. applyCRToStats is idempotent (modify/unmodify keyed by id
 * {@code cr_effect}); applyCRToShip's ammo/damage-sequence branches only fire below cr 0.4/0.2.
 *
 * <p>Client twin (typed, B-deny-legal): {@code cc.starsector.client.CrDeploySeam}. Keep the
 * two semantically identical -- the parity walker (SS213 run shape) is the drift alarm.
 */
public final class CrDeploySeam {

    private static final String REPAIR_TRACKER = "com.fs.starfarer.campaign.fleet.RepairTracker";
    private static final String OBF_SHIP = "com.fs.starfarer.combat.entities.Ship";
    private static final String STATS_API = "com.fs.starfarer.api.combat.MutableShipStatsAPI";
    private static final String HULL_SIZE = "com.fs.starfarer.api.combat.ShipAPI$HullSize";

    private CrDeploySeam() {
    }

    /**
     * Applies deployment CR to a live combat Ship through the engine's own seam.
     * Fail-fast: reflection failures propagate (deploy lanes treat them as fatal;
     * the growth seam wraps this in its own observation guard).
     *
     * @param ship a live engine-internal combat Ship (engine-real; also implements ShipAPI)
     * @param cr   the chosen deployment CR in [0,1]
     */
    public static void applyDeployCr(Object ship, float cr) throws Exception {
        ClassLoader loader = ship.getClass().getClassLoader();
        Class<?> tracker = Class.forName(REPAIR_TRACKER, false, loader);
        Class<?> shipCls = Class.forName(OBF_SHIP, false, loader);
        Class<?> statsApi = Class.forName(STATS_API, false, loader);
        Class<?> hullSize = Class.forName(HULL_SIZE, false, loader);
        if (!shipCls.isInstance(ship)) {
            throw new IllegalArgumentException(
                    "applyDeployCr: not a live engine combat Ship: " + ship.getClass().getName());
        }
        Object stats = ship.getClass().getMethod("getMutableStats").invoke(ship);
        Object size = ship.getClass().getMethod("getHullSize").invoke(ship);
        Method toStats = tracker.getMethod("applyCRToStats", float.class, statsApi, hullSize);
        Method toShip = tracker.getMethod("applyCRToShip", float.class, shipCls);
        toStats.invoke(null, cr, stats, size);
        toShip.invoke(null, cr, ship);
    }
}
