package cc.starsector.client;

import com.fs.starfarer.api.combat.ShipAPI;

/**
 * Wire id -> local {@code ShipAPI}: the ONE shared lookup behind {@code ArcHitFxLane.resolve},
 * {@code CritMalfunctionLane.resolve} and {@code SourcedMissileEndLane.resolveShip}.
 *
 *
 * <p><b>Why.</b> The three lanes each carried a verbatim copy of the PRCP replica resolution idiom;
 * one home means a
 * future fix to the idiom (e.g. the 1v1-shape "any non-own id = the foe" residue) lands once.
 * The only difference between the copies was the missile lane's {@code plugin == null} belt, kept
 * here for every caller (a no-op where the plugin is constructor-final).
 *
 * <p>Law: the own ship via the session id, else {@code remoteEntities} when {@code registryShape},
 * else the 1v1 {@code foeShip}. {@code null} = no local body for that wire id. No state, no
 * reflection; render-thread callers only (reads PRCP fields unsynchronised, as the copies did).
 */
final class WireShipResolver {

    private WireShipResolver() {
    }

    /** The shared lookup. */
    static ShipAPI resolve(PredictReconcileCombatPlugin plugin, long wireId) {
        if (plugin == null) {
            return null;
        }
        if (plugin.session != null && wireId == plugin.session.shipId()) {
            return plugin.ownShip;
        }
        if (plugin.registryShape) {
            PredictReconcileCombatPlugin.RemoteEntity re = plugin.remoteEntities.get(wireId);
            return re != null ? re.ship : null;
        }
        return plugin.foeShip;
    }
}
