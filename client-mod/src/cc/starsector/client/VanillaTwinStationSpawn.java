package cc.starsector.client;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.mission.FleetSide;
import java.util.List;
import org.lwjgl.util.vector.Vector2f;

/**
 * The VANILLA TWIN's own-side station, spawned the way the served side
 * gets it instead of mission-fielded.
 *
 * <p>WHY: the matched fixed-roster pair puts the fortress station on side 0 of BOTH
 * descriptors. In a served/registry shape the client never mission-fields an own-side station — it
 * arrives through the wire-sight spawn lane (a station in the local PLAYER reserves sets the engine's
 * isDeployedStation flag, which kills the seat's reserves==1 auto-deploy and pops the modal deploy
 * dialog; {@link MatchMissionBuilder} "own-side station is NEVER mission-fielded"). A serverless
 * {@code vanilla:} twin has no wire, so twin run 0904-053938 hit the builder's fail-closed refusal,
 * "Error loading mission preview", and wrote zero frames. This plugin is the twin's substitute for the
 * wire-sight lane: on the first frame where the seat's flagship exists (the reserves==1 auto-deploy has
 * run, so the PLAYER side is anchored) it calls {@code spawnShipOrWing(variant, (0,0), 0)} on the PLAYER
 * fleet manager — the SAME engine deploy body vanilla's own deployStationAndRelatedIfNeeded uses, module
 * cluster included — at the server's pin (RealEngineDemoRoster: "the station pins at (0,0) — MAP CENTER
 * … facing 0"), never touching reserves. Public API only (the classloader-B deny).
 *
 * <p>Registered per station row by {@code MatchMissionBuilder.build} for {@code vanilla:} shapes only;
 * every served/registry lane is byte-identical.
 */
final class VanillaTwinStationSpawn implements EveryFrameCombatPlugin {
    private final String variantId;
    private CombatEngineAPI engine;
    private boolean done;

    VanillaTwinStationSpawn(String variantId) {
        this.variantId = variantId;
    }

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (done) {
            return;
        }
        CombatEngineAPI e = engine != null ? engine : Global.getCombatEngine();
        if (e == null || e.getPlayerShip() == null) {
            return; // the seat auto-deploys first (reserves==1); anchor the PLAYER side on it
        }
        done = true;
        try {
            ShipAPI base = e.getFleetManager(FleetSide.PLAYER)
                    .spawnShipOrWing(variantId, new Vector2f(0f, 0f), 0f);
            PilotWatchLog.info("predict vanilla-twin station: spawned "
                    + FallbackAnnounce.sanitize(variantId, 64) + " at (0,0) facing 0 -> "
                    + (base == null ? "NULL" : base.getHullSpec().getHullId()
                    + " modules=" + base.getChildModulesCopy().size()
                    + " owner=" + base.getOwner()));
        } catch (RuntimeException ex) {
            PilotWatchLog.error("predict vanilla-twin station: spawn FAILED for "
                    + FallbackAnnounce.sanitize(variantId, 64), ex);
        }
    }

    @Override
    public void processInputPreCoreControls(float amount, List<InputEventAPI> events) {
    }

    @Override
    public void renderInWorldCoords(ViewportAPI viewport) {
    }

    @Override
    public void renderInUICoords(ViewportAPI viewport) {
    }
}
