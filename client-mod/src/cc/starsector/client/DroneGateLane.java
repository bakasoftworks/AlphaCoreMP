package cc.starsector.client;

import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.ShipAPI;

/**
 * The two-way drone collision gate, extracted verbatim from
 * {@code PredictReconcileCombatPlugin} at the 9500-nb backstop fire (the
 * {@code deferred-refactors.md} cold-extraction rule — this lane was the named first candidate).
 * Behavior is UNCHANGED from the in-class landing: a drone replica presents
 * {@code CollisionClass.NONE} while inside any station complex's collision reach (launch transit,
 * any return) and {@code SHIP} in the open, so foe fire lands locally as vanilla; two-way (not a
 * one-shot flip) so a server-flown return transit can never re-mint the grind. The mint-side
 * arm (intangible birth + {@code setAnimatedLaunch}) stays at the spawn site in the plugin —
 * this lane owns the per-frame flips only.
 */
final class DroneGateLane {

    /** Proximity margin (su) on the reach test; generous because the flip is
     *  presentation-only and the steady orbit sits 650 su clear. */
    private static final float DRONE_GATE_MARGIN = 30f;

    /** The owning plugin — the sibling-collaborator backref ({@link SpawnHullmodGate} idiom);
     *  read for {@code remoteEntities} (the station scan population). */
    private final PredictReconcileCombatPlugin plugin;

    DroneGateLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    /** A DRONE-class replica by spec: hull designation "Drone" (merlon/gargoyle/
     *  ravelin + the system-drone hulls). {@code ShipAPI.isDrone()} is useless here — plain
     *  mutable state only the engine's drone launcher sets, FALSE on client-minted replicas. */
    static boolean isDroneReplica(ShipAPI ship) {
        try {
            return !ship.isFighter() && !ship.isStation() && ship.getHullSpec() != null
                    && "Drone".equalsIgnoreCase(ship.getHullSpec().getDesignation());
        } catch (Throwable t) {
            return false;
        }
    }

    /** The per-frame two-way drone collision gate: NONE while inside any station
     *  complex's collision reach (launch transit, any return), SHIP in the open (foe fire lands
     *  locally as vanilla), SHIP once killed (a hulk presents collidable like any other hulk).
     *  Fail-soft: a throw leaves the current class; next frame retries. */
    void apply(PredictReconcileCombatPlugin.RemoteEntity re) {
        ShipAPI drone = re.ship;
        if (drone == null) {
            return;
        }
        try {
            boolean wantIntangible = !re.killed && nearAnyStationComplex(drone);
            if (wantIntangible == re.droneIntangible) {
                return;
            }
            drone.setCollisionClass(wantIntangible ? CollisionClass.NONE : CollisionClass.SHIP);
            re.droneIntangible = wantIntangible;
            if (re.droneGateLogs < 6) {
                re.droneGateLogs++;
                PilotWatchLog.info("predict drone-gate: entity " + re.entityId + " -> "
                        + (wantIntangible ? "NONE (station-near)" : "SHIP (clear)")
                        + (re.killed ? " [killed]" : ""));
            }
        } catch (Throwable t) {
            // presentation-side only — never let the gate break the maintain loop
        }
    }

    /** Inside collision reach of any station replica or its child modules? */
    private boolean nearAnyStationComplex(ShipAPI drone) {
        for (PredictReconcileCombatPlugin.RemoteEntity other : plugin.remoteEntities.values()) {
            ShipAPI station = other.ship;
            if (station == null || station == drone || !station.isStation()) {
                continue;
            }
            if (withinCollisionReach(drone, station)) {
                return true;
            }
            for (ShipAPI module : station.getChildModulesCopy()) {
                if (module != null && withinCollisionReach(drone, module)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Broadphase-style reach test (collision radii + margin) — conservative vs exact bounds. */
    private static boolean withinCollisionReach(ShipAPI a, ShipAPI b) {
        float dx = a.getLocation().x - b.getLocation().x;
        float dy = a.getLocation().y - b.getLocation().y;
        float reach = a.getCollisionRadius() + b.getCollisionRadius() + DRONE_GATE_MARGIN;
        return dx * dx + dy * dy < reach * reach;
    }
}
