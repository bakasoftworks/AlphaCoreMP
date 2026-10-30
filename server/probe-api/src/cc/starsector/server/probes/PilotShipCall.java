package cc.starsector.server.probes;

import cc.starsector.server.transform.PilotShipWindow;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;

/**
 * The typed target of the redirected {@code CombatEngineAPI.getPlayerShip()} call sites (HOOK-0055): the same
 * stack shape as the interface call it replaces (one reference in, one {@link ShipAPI} out), so a rewritten
 * class keeps its frames and maxs. Inside an open {@link PilotShipWindow} on the sim thread the answer is the
 * human-flown hull being advanced; everywhere else the engine's own getter answers, shuttle fabrication
 * included. Lives in the probe-api unit because it must be compiled against the game's API types, which the
 * product unit never links (reflection only); it lands in the server classes beside the holder, on the
 * system class path every mod loader delegates to. Main-written.
 */
public final class PilotShipCall {

    private PilotShipCall() {
    }

    public static ShipAPI playerShip(CombatEngineAPI engine) {
        Object answer = PilotShipWindow.answerOrNull();
        return answer instanceof ShipAPI ? (ShipAPI) answer : engine.getPlayerShip();
    }
}
