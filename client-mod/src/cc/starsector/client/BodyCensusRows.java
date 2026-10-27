package cc.starsector.client;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.DeployedFleetMemberAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;

import org.lwjgl.util.vector.Vector2f;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Born of a stray tab-map arrow left from the spawn — the
 * BODY CENSUS: one sidelog row per ship body this client is drawing, each labelled by its wire
 * identity ({@code own} / {@code wire=N} / {@code LOCAL-ONLY}) with exactly the fields vanilla's
 * tactical map colours by (owner, originalOwner, isAlly, alive/hulk, in-play) plus pose, velocity
 * and its fleet-manager DEPLOYED membership. Two consumers:
 * <ul>
 *   <li>{@link #markRows} — the operator's F9 press: every non-fighter engine ship, then every
 *       deployed-set RESIDUAL (a deployed member whose ship is dead or OUT OF PLAY — the class a
 *       bare {@code removeEntity} leaves behind, {@link DeployedSetEvictLane} /
 *       {@link PredictReconcileCombatPlugin#removeStaleBody}). The mark frame (JPG) plus this list
 *       NAMES the on-map thing; no guessing from a screenshot.</li>
 *   <li>{@link #residualSummary} — the periodic census line's compact residual list.</li>
 * </ul>
 * Read-only walk over public API getters (B-legal); never throws (a fault yields one row saying
 * so); bounded per mark and per run. Variant ids + numbers only — no player text enters a row.
 */
final class BodyCensusRows {
    /** F9 marks that emit a census per run; the next mark logs one skip row, later ones nothing. */
    static final int MARK_CENSUS_MAX = 6;
    /** Rows per mark census (engine ships + residuals; fighters are counted, not listed). */
    static final int MARK_ROWS_MAX = 64;
    /** Residual entries on one periodic census line. */
    static final int RESIDUAL_SUMMARY_MAX = 6;
    /** MISSILE rows per mark (the census the mines read lacked). */
    static final int MISSILE_ROWS_MAX = 24;

    private static int marksEmitted;

    private BodyCensusRows() {
    }

    /** The F9 census (plugin thread, right after the mark is sent). */
    static void markRows(PredictReconcileCombatPlugin plugin, int markNo, long simTick) {
        CombatEngineAPI engine = plugin.engine;
        if (engine == null) {
            return;
        }
        if (marksEmitted >= MARK_CENSUS_MAX) {
            if (marksEmitted == MARK_CENSUS_MAX) {
                marksEmitted++;
                PilotWatchLog.info("predict body_census mark=#" + markNo + " SKIPPED (cap "
                        + MARK_CENSUS_MAX + " marks per run reached)");
            }
            return;
        }
        marksEmitted++;
        String head = "predict body_census mark=#" + markNo + " tick=" + simTick + " ";
        try {
            Map<ShipAPI, String> keys = new IdentityHashMap<ShipAPI, String>();
            if (plugin.ownShip != null) {
                keys.put(plugin.ownShip, "own");
            }
            for (PredictReconcileCombatPlugin.RemoteEntity re : plugin.remoteEntities.values()) {
                if (re.ship != null && !keys.containsKey(re.ship)) {
                    keys.put(re.ship, "wire=" + re.entityId + (re.killed ? "(killed)" : ""));
                }
            }
            List<ShipAPI> ships = engine.getShips();
            int rows = 0;
            int fighters = 0;
            int total = ships == null ? 0 : ships.size();
            if (ships != null) {
                for (int i = 0; i < ships.size(); i++) {
                    ShipAPI s = ships.get(i);
                    if (s == null) {
                        continue;
                    }
                    if (s.isFighter()) {
                        fighters++;
                        continue;
                    }
                    if (rows >= MARK_ROWS_MAX) {
                        break;
                    }
                    rows++;
                    String key = keys.get(s);
                    PilotWatchLog.info(head + (key != null ? key : "LOCAL-ONLY") + " " + describe(engine, s));
                }
            }
            // Every local MISSILE body with its twin label (own-twin / wire=N /
            // LOCAL-ONLY): a live run's "double mines" were 14 LOCAL sprites for 7 wire
            // records, invisible to a ships-only census. Mines are first-class here.
            int missileRows = 0;
            List<MissileAPI> missiles = engine.getMissiles();
            int missileTotal = missiles == null ? 0 : missiles.size();
            if (missiles != null) {
                for (int i = 0; i < missiles.size() && missileRows < MISSILE_ROWS_MAX; i++) {
                    MissileAPI m = missiles.get(i);
                    if (m == null) {
                        continue;
                    }
                    missileRows++;
                    String key = plugin.missileLane != null ? plugin.missileLane.censusKeyOf(m) : null;
                    Vector2f loc = m.getLocation();
                    Object src = m.getSource();
                    PilotWatchLog.info(head + "MISSILE " + (key != null ? key : "LOCAL-ONLY")
                            + " spec=" + MissilePoseLane.specIdOf(m) + " mine=" + m.isMine()
                            + " owner=" + m.getOwner()
                            + " src=" + (src == null ? "none" : (src == plugin.ownShip ? "own" : "ship"))
                            + " loc=(" + (loc != null ? loc.x : Float.NaN) + ","
                            + (loc != null ? loc.y : Float.NaN) + ")");
                }
            }
            int residuals = 0;
            for (int side = 0; side < 2; side++) {
                CombatFleetManagerAPI mgr = engine.getFleetManager(side);
                List<DeployedFleetMemberAPI> members = mgr == null ? null : mgr.getDeployedCopyDFM();
                if (members == null) {
                    continue;
                }
                for (int i = 0; i < members.size(); i++) {
                    DeployedFleetMemberAPI m = members.get(i);
                    if (m == null || m.isFighterWing()) {
                        continue;
                    }
                    ShipAPI s = m.getShip();
                    if (s == null || (s.isAlive() && engine.isEntityInPlay(s))) {
                        continue;
                    }
                    residuals++;
                    if (rows >= MARK_ROWS_MAX) {
                        continue;
                    }
                    rows++;
                    String key = keys.get(s);
                    PilotWatchLog.info(head + "RESIDUAL side=" + side + " "
                            + (key != null ? key : "LOCAL-ONLY") + " " + describe(engine, s));
                }
            }
            PilotWatchLog.info(head + "END rows=" + rows + " engine_ships=" + total
                    + " fighters_unlisted=" + fighters + " residuals=" + residuals
                    + " missiles=" + missileRows + "/" + missileTotal
                    + (rows >= MARK_ROWS_MAX ? " TRUNCATED(cap " + MARK_ROWS_MAX + ")" : ""));
        } catch (Throwable t) {
            PilotWatchLog.info(head + "FAULT " + t);
        }
    }

    /** One body, the map-colour fields first. */
    static String describe(CombatEngineAPI engine, ShipAPI s) {
        StringBuilder sb = new StringBuilder(200);
        sb.append("id=").append(Integer.toHexString(System.identityHashCode(s)));
        sb.append(" variant=").append(s.getVariant() != null ? s.getVariant().getHullVariantId() : "?");
        sb.append(" owner=").append(s.getOwner()).append(" orig=").append(s.getOriginalOwner());
        sb.append(" ally=").append(s.isAlly()).append(" alive=").append(s.isAlive());
        sb.append(" hulk=").append(s.isHulk()).append(" inplay=").append(engine.isEntityInPlay(s));
        sb.append(" pod=").append(s.isShuttlePod()).append(" coll=").append(s.getCollisionClass());
        Vector2f loc = s.getLocation();
        Vector2f vel = s.getVelocity();
        sb.append(" loc=(").append(loc == null ? "?" : fmt(loc.x) + "," + fmt(loc.y)).append(')');
        sb.append(" vel=(").append(vel == null ? "?" : fmt(vel.x) + "," + fmt(vel.y)).append(')');
        sb.append(" facing=").append(fmt(s.getFacing()));
        sb.append(" ai=").append(s.getShipAI() == null ? "null" : s.getShipAI().getClass().getSimpleName());
        sb.append(" deployed=").append(deployedIn(engine, s));
        return sb.toString();
    }

    /** Which side's fleet manager still lists the body as DEPLOYED ({@code 0}, {@code 1}, {@code 0+1}, {@code none}). */
    private static String deployedIn(CombatEngineAPI engine, ShipAPI s) {
        String out = "";
        for (int side = 0; side < 2; side++) {
            CombatFleetManagerAPI mgr = engine.getFleetManager(side);
            if (mgr != null && mgr.getDeployedFleetMember(s) != null) {
                out = out.isEmpty() ? String.valueOf(side) : out + "+" + side;
            }
        }
        return out.isEmpty() ? "none" : out;
    }

    /** The periodic census tail: {@code residual=[side:variant@x,y hulk|OUT; ...]} or empty. */
    static String residualSummary(CombatEngineAPI engine) {
        try {
            StringBuilder sb = new StringBuilder(96);
            int n = 0;
            int extra = 0;
            for (int side = 0; side < 2; side++) {
                CombatFleetManagerAPI mgr = engine.getFleetManager(side);
                List<DeployedFleetMemberAPI> members = mgr == null ? null : mgr.getDeployedCopyDFM();
                if (members == null) {
                    continue;
                }
                for (int i = 0; i < members.size(); i++) {
                    DeployedFleetMemberAPI m = members.get(i);
                    if (m == null || m.isFighterWing()) {
                        continue;
                    }
                    ShipAPI s = m.getShip();
                    if (s == null || (s.isAlive() && engine.isEntityInPlay(s))) {
                        continue;
                    }
                    if (n >= RESIDUAL_SUMMARY_MAX) {
                        extra++;
                        continue;
                    }
                    n++;
                    Vector2f loc = s.getLocation();
                    sb.append(n == 1 ? " residual=[" : "; ").append(side).append(':')
                            .append(s.getVariant() != null ? s.getVariant().getHullVariantId() : "?")
                            .append('@').append(loc == null ? "?" : fmt(loc.x) + "," + fmt(loc.y))
                            .append(engine.isEntityInPlay(s) ? " hulk" : " OUT");
                }
            }
            if (n > 0) {
                sb.append(extra > 0 ? "; +" + extra + " more]" : "]");
            }
            return sb.toString();
        } catch (Throwable t) {
            return " residual=FAULT";
        }
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.ROOT, "%.0f", v);
    }
}
