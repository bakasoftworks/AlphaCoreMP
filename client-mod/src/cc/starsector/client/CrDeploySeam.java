package cc.starsector.client;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.campaign.fleet.RepairTracker;
import com.fs.starfarer.combat.entities.Ship;

/**
 * The engine-native deploy-CR seam, client twin.
 * Server twin: {@code cc.starsector.server.bridge.CrDeploySeam}
 * -- keep the two semantically identical; the parity walker is the drift alarm.
 *
 * <p>WHY: {@code Ship.setCRAtDeployment} writes both CR fields with NO stat application
 * and {@code setCurrentCR} crosses the stats seam only on a value
 * CHANGE, so a "stamp fields to the final value" spawn leaves the vanilla
 * {@code cr_effect} block unapplied AND blocks the later wire-CR apply from ever crossing
 * (equal-value no-op). Measured: remote bodies only carried
 * {@code cr_effect} because they happened to spawn at 0.7 before the 1.0 wire CR hit.
 *
 * <p>THE LAW (vanilla deploy verbatim): the engine's own
 * statics pair. {@code applyCRToShip} stamps BOTH CR fields itself,
 * so no field writes belong beside these calls. Typed engine refs
 * are classloader-B-legal (the adapter law); reflection stays server-side.
 *
 * <p>Terrain tail: every call ends with
 * {@link TerrainLane#apply}, semantically identical to the server twin's call sites, each of which
 * is followed by {@code TerrainStatSeam.applyToShip}.
 */
public final class CrDeploySeam {

    private static volatile boolean fallbackLogged;

    private CrDeploySeam() {
    }

    /** Applies deployment CR to a live local combat ship through the engine's own seam. */
    public static void applyDeployCr(ShipAPI ship, float cr) {
        try {
            Seam.cross(ship, cr);
        } catch (NoClassDefFoundError e) {
            String m = String.valueOf(e.getMessage());
            if (!m.contains("com/fs/starfarer") && !m.contains("com.fs.starfarer")) {
                throw e;
            }
            // Selftest JVMs run without the engine jars (build.ps1 smoke classpaths) — there the
            // field stamps keep mock flows working (no engine, no cr_effect to apply). In-game
            // the engine classes always exist, so this line in a LIVE log is itself a defect.
            if (!fallbackLogged) {
                fallbackLogged = true;
                PilotWatchLog.info("cr-seam: engine classes absent -> field-stamp fallback"
                        + " (selftest JVM shape; a live sighting is a defect)");
            }
            ship.setCRAtDeployment(cr);
            ship.setCurrentCR(cr);
        }
        TerrainLane.apply(ship); // The terrain mults ride every deploy-CR site (L4 (b) sites 1-6); the knob-change sweep covers the rest
    }

    /** Nested holder so the engine-typed linkage resolves only on first live use, never at class load. */
    private static final class Seam {
        static void cross(ShipAPI ship, float cr) {
            RepairTracker.applyCRToStats(cr, ship.getMutableStats(), ship.getHullSize());
            RepairTracker.applyCRToShip(cr, (Ship) ship);
        }
    }
}
