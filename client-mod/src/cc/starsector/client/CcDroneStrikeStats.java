package cc.starsector.client;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.combat.DroneStrikeStats;

import java.util.List;

/**
 * The mod-shipped {@code drone_strike} stats script (Termination
 * Sequence: tempest, apex), named by {@code data/shipsystems/drone_strike.system}
 * (a one-key file, {@code id} + {@code statsScript}: the engine merges it onto the vanilla file
 * key by key, so every other setting stays vanilla's). Subclassing keeps every vanilla
 * body; the two overrides route the PILOTED carrier's drone roster through
 * {@link OwnCarrierDrones} (its class doc = the why) and skip the LOCAL conversion:
 * <ul>
 * <li>{@link #getDrones}: the enrolled puppet fighters of the piloted carrier — so vanilla
 *     {@code isUsable} (the engine's press gate) and {@code getInfoText}
 *     (READY / NO TARGET / OUT OF RANGE) see the drones the serve has. Every other ship, and the
 *     serve JVM, = vanilla ({@code super}).</li>
 * <li>{@link #convertDrones}: for that carrier, latch {@code fired} exactly as vanilla does
 * and spawn NOTHING — the local window runs natively
 *     (IN → ACTIVE → cooldown, the activation sound, the HUD state), {@code isOn()} rises, PRCP
 *     bit 7 carries the press up, and the serve's own {@code useSystem()} converts a REAL drone;
 *     the conversion missile + arc + the ride-along drone cross on the existing lanes
 *     ({@link DroneStrikeFxLane} pairs the sourceless missile with the nearest puppet drone and
 *     anchors the arc on the drone's carrier). A local spawn would double-mint the missile the
 *     wire already delivers ({@link SpawnSystemGate} law: a local mirror is safe only for scripts
 *     that spawn nothing).</li>
 * </ul>
 * Latency residue: a press landing while the serve's drones are dead and the puppets not yet
 * reaped (or the reverse) activates locally while the serve refuses (a {@code pilot-sysint died}
 * row) — bounded by one RTT, presentation-only. Public + no-arg: the engine's script store
 * instantiates it by name.
 */
public class CcDroneStrikeStats extends DroneStrikeStats {

    @Override
    public List<ShipAPI> getDrones(ShipAPI ship) {
        List<ShipAPI> puppets = OwnCarrierDrones.puppetDronesOf(ship);
        return puppets != null ? puppets : super.getDrones(ship);
    }

    @Override
    public void convertDrones(ShipAPI ship, ShipAPI target) {
        List<ShipAPI> puppets = OwnCarrierDrones.puppetDronesOf(ship);
        if (puppets == null) {
            super.convertDrones(ship, target);
            return;
        }
        fired = true;           // One conversion per activation window
        forceNextTarget = null; // as vanilla
        OwnCarrierDrones.ownPressSkipped(ship, puppets.size(), target);
    }
}
