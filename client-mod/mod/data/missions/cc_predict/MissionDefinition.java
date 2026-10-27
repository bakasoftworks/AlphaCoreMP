package data.missions.cc_predict;

import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

import cc.starsector.client.CcClientModPlugin;
import cc.starsector.client.CloakWallClockCombatPlugin;
import cc.starsector.client.MatchMissionBuilder;
import cc.starsector.client.PredictFailBannerCombatPlugin;
import cc.starsector.client.PredictReconcileCombatPlugin;
import cc.starsector.client.ReconnectBannerCombatPlugin;
import cc.starsector.client.SideTotalsOverrideLane;

/**
 * Predict-and-reconcile 1v1 mission — the in-game client half of a served match. Runtime Janino
 * script; keep it conservative Java.
 *
 * <p>Drives {@link PredictReconcileCombatPlugin}: the local engine SIMULATES for presentation and is
 * RECONCILED against the server's authoritative snapshots (velocity included). The own ship is flown
 * by the engine's native player control (real collision, real held-trigger Hellbore at engine
 * cadence); the foe is dead-reckoned then reconciled; the server stays the sole damage/death
 * authority. The dev-proof missions (cc_pilot_intent et al.) were removed for the public test
 * — git still restores them if a puppet regression lane is ever revived.
 *
 * <p>The 1v1: the player's own Mudskipper Mk II + Hellbore vs a sparring Mudskipper Mk II +
 * Hellbore. Endpoint per window via {@code cc.predict.portFile} / {@code cc.predict.port}; pilot
 * ship via {@code cc.predict.shipId}.
 */
public class MissionDefinition implements MissionDefinitionPlugin {

    public void defineMission(MissionDefinitionAPI api) {
        // Connect-at-join (W13): never at launch — a window that only launched holds no seat.
        // Registry shapes (the served match): the click runs a NON-claiming door probe for the
        // briefing rows below and the build fields ONE placeholder flagship (reserves==1, so no
        // modal deploy dialog); the dial AND the seat claim
        // happen at Play (combat init), where the WELCOME's seat hull is fielded at its first wire
        // pose. Legacy shapes keep the click dial (the builder's bounded first-attempt wait fields
        // the assigned seat). mission_list.csv keeps this mission off row 1, so the title screen's
        // auto-select never reaches here. No-op on an ordinary launch.
        CcClientModPlugin.dialPredictAtJoin();
        // Server-minted match descriptor (cc.match.descriptor) — when configured and decoded at app
        // load, the GENERIC builder authors this mission from it (1v1 descriptor = the exact call
        // sequence below). Knob unset / decode failure -> false with zero api calls and the default
        // body below runs untouched.
        if (MatchMissionBuilder.buildFromConfiguredDescriptor(api)) {
            PredictReconcileCombatPlugin predict = new PredictReconcileCombatPlugin();
            api.addPlugin(predict);
            // MIRROR: server-auth side-totals overwrite-after lane — reads the
            // predict plugin's AWSD v9 stash; inert until a v9 frame lands.
            api.addPlugin(new SideTotalsOverrideLane(predict));
            // loud-fail: if the app-load predict connect gave up, this window is
            // LOCAL VANILLA — the banner plugin says so on-screen instead of letting the round
            // masquerade as a served match (the earlier two-empty-rounds incident).
            api.addPlugin(new PredictFailBannerCombatPlugin());
            // mid-match session death -> "reconnecting (N s)" banner (no wire; before this the
            // only tell was the small HUD net line, easy to miss on a dead lane).
            api.addPlugin(new ReconnectBannerCombatPlugin());
            // F2b: own-hull cloak charge ticks wall time (coherent twin of the
            // server's PilotedCloakWallClock; knob cc.predict.cloakWall).
            api.addPlugin(new CloakWallClockCombatPlugin());
            addJoinBriefing(api);
            return;
        }

        // Player side: the human's own Mudskipper Mk II + Hellbore, flown locally and reconciled to
        // truth. The mod-defined variant (mudskipper2_cc_Hellbore, skin mudskipper2_cc) omits the RNG
        // malfunction d-mod so the local prediction engine and the authoritative server never jam at
        // different times.
        api.initFleet(FleetSide.PLAYER, "ISS", FleetGoal.ATTACK, false);
        api.setFleetTagline(FleetSide.PLAYER, "Multiplayer test - local fallback (no server)");
        api.addToFleet(FleetSide.PLAYER, "mudskipper2_cc_Hellbore", FleetMemberType.SHIP, true);

        // Enemy side: the sparring dmod-free Mudskipper Mk II + Hellbore the client dead-reckons + reconciles.
        api.initFleet(FleetSide.ENEMY, "HSS", FleetGoal.ATTACK, false);
        api.setFleetTagline(FleetSide.ENEMY, "Sparring partner");
        api.addToFleet(FleetSide.ENEMY, "mudskipper2_cc_Hellbore", FleetMemberType.SHIP, false);

        float halfWidth = 9000f;
        float halfHeight = 9000f;
        api.initMap(-halfWidth, halfWidth, -halfHeight, halfHeight);

        PredictReconcileCombatPlugin predict = new PredictReconcileCombatPlugin();
        api.addPlugin(predict);
        api.addPlugin(new SideTotalsOverrideLane(predict)); // MIRROR (see above)
        api.addPlugin(new PredictFailBannerCombatPlugin()); // loud-fail (see above)
        api.addPlugin(new ReconnectBannerCombatPlugin()); // reconnecting banner (see above)
        api.addPlugin(new CloakWallClockCombatPlugin()); // F2b wall-clock cloak (see above)
        addJoinBriefing(api);
    }

    /**
     * W13: the connection state as mission-briefing rows (the vanilla preview-text lane, e.g.
     * turningthetables) — composed AFTER the build so a LAN click already reads CONNECTED;
     * re-rendered on every list click. Conservative Java for the Janino runtime compile.
     */
    private static void addJoinBriefing(MissionDefinitionAPI api) {
        String[] rows = CcClientModPlugin.joinBriefingRows();
        for (int i = 0; i < rows.length; i++) {
            api.addBriefingItem(rows[i]);
        }
    }
}
