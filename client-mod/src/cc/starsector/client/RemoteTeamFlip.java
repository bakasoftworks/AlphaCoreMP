package cc.starsector.client;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.FighterWingAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import java.util.ArrayList;
import java.util.List;

/**
 * Team switch ({@code /switchteams}) — re-owner a BOUND remote replica whose relative side flipped
 * mid-life: the client twin of the server's {@code SeatTeamEnginePort.flipOwner} checklist
 * (proven on the real engine), written against the PUBLIC API only
 * (B-legal: no reflection, no engine-typed ref — the mod-jar classloader-B deny).
 *
 * <p>WHY a checklist and never a bare {@code setOwner}: {@code Ship.setOwner} writes
 * {@code originalOwner} only while it is −1, and the
 * fleet-member cache, the friend/foe colour and the fleet manager's own death listeners key on
 * {@code originalOwner}; modules, launched fighters (wing members AND the ones flying home) and
 * deployed drones are independent {@code Ship}s with their own cached pair; a live
 * {@code setOwner} never re-buckets the OLD side's deployed set, whose listeners then never hear
 * the hull die — so the parts leave it through the
 * engine's own {@code removeDeployed(ship, false)} (the {@link DeployedSetEvictLane} verb).
 * Replacement fighters read the WING's owner at launch, so every wing is re-owned too. Stale ship
 * targets are dropped (the engine re-validates a cached target only on hulk/expired) — on this
 * client that is the own ship's R-target and the autofire coordinator's saved pick. In-flight
 * ordnance keeps its fire-time side (transient, self-clearing).
 *
 * <p>Fail-soft per flip: a throw logs once and the registry side still flips; {@link TeamChangeLane}
 * re-attempts the flip on a BOUNDED cadence (~every 60 ticks) while a pass left it PENDING — a
 * THROW counts even when the hull pair already took, because steps 3-5 below did not run;
 * a body that becomes mis-owned with no wire side move is not revisited —
 * a mis-owned body is a standing desync, never abandoned, but the retry is never per frame.
 * Render thread only, like every registry write. Cost: one walk per FLIP (an
 * admin verb, seconds apart at most) — never per frame.
 */
final class RemoteTeamFlip {

    private RemoteTeamFlip() {
    }

    /** {@code hull} + its child modules (recursive) + every wing's members and homing fighters +
     *  deployed drones — the same walk the server's {@code SeatHullParts.collect} makes. */
    static void collectParts(ShipAPI hull, List<ShipAPI> out) {
        if (hull == null || out.contains(hull)) {
            return;
        }
        out.add(hull);
        List<ShipAPI> modules = hull.getChildModulesCopy();
        if (modules != null) {
            for (ShipAPI module : modules) {
                collectParts(module, out);
            }
        }
        List<FighterWingAPI> wings = hull.getAllWings();
        if (wings != null) {
            for (FighterWingAPI wing : wings) {
                if (wing == null) {
                    continue;
                }
                List<ShipAPI> members = wing.getWingMembers();
                if (members != null) {
                    for (ShipAPI fighter : members) {
                        collectParts(fighter, out);
                    }
                }
                List<FighterWingAPI.ReturningFighter> homing = wing.getReturning();
                if (homing != null) {
                    for (FighterWingAPI.ReturningFighter rf : homing) {
                        if (rf != null) {
                            collectParts(rf.fighter, out);
                        }
                    }
                }
            }
        }
        List<ShipAPI> drones = hull.getDeployedDrones();
        if (drones != null) {
            for (ShipAPI drone : drones) {
                collectParts(drone, out);
            }
        }
    }

    /**
     * Flip {@code hull} (and its parts) to local engine owner {@code newOwner}. Returns the receipt
     * tail ({@code parts= wings= undeployed= targets_dropped= verified=}) for the log row;
     * {@code verified} counts the parts whose owner AND originalOwner read back {@code newOwner}
     * (== parts when the engine took the flip — the self-proving row shape).
     */
    static String flip(CombatEngineAPI engine, ShipAPI hull, int newOwner, ShipAPI ownShip) {
        List<ShipAPI> parts = new ArrayList<ShipAPI>();
        collectParts(hull, parts);
        int oldOwner = hull.getOwner();
        // 1. leave the OLD side's deployed set FIRST (the evict verb keys on the live owner pair)
        int undeployed = 0;
        for (ShipAPI part : parts) {
            if (DeployedSetEvictLane.evictFrom(engine, oldOwner, part)) {
                undeployed++;
            }
        }
        // 2. the owner pair on every part
        for (ShipAPI part : parts) {
            part.setOwner(newOwner);
            part.setOriginalOwner(newOwner);
        }
        // 3. every wing (replacement fighters launch with the wing's owner)
        int wings = 0;
        List<FighterWingAPI> allWings = hull.getAllWings();
        if (allWings != null) {
            for (FighterWingAPI wing : allWings) {
                if (wing != null) {
                    wing.setWingOwner(newOwner);
                    wings++;
                }
            }
        }
        // 4. the fleet member (Ship.getFleetMember re-stamps from it)
        FleetMemberAPI member = hull.getFleetMember();
        if (member != null) {
            member.setOwner(newOwner);
        }
        // 5. stale targets: the parts' own, and every ship still targeting a part
        int dropped = 0;
        for (ShipAPI part : parts) {
            if (part.getShipTarget() != null) {
                part.setShipTarget(null);
                dropped++;
            }
        }
        List<ShipAPI> ships = engine.getShips();
        if (ships != null) {
            for (ShipAPI ship : ships) {
                if (ship == null || parts.contains(ship)) {
                    continue;
                }
                ShipAPI target = ship.getShipTarget();
                if (target != null && parts.contains(target)) {
                    ship.setShipTarget(null);
                    dropped++;
                }
            }
        }
        if (ownShip != null && !parts.contains(ownShip) && ownShip.getShipTarget() != null
                && parts.contains(ownShip.getShipTarget())) {
            ownShip.setShipTarget(null); // the player ship may sit outside engine.getShips() mid-respawn
            dropped++;
        }
        int verified = 0;
        for (ShipAPI part : parts) {
            if (part.getOwner() == newOwner && part.getOriginalOwner() == newOwner) {
                verified++;
            }
        }
        return "parts=" + parts.size() + " wings=" + wings + " undeployed=" + undeployed
                + " targets_dropped=" + dropped + " verified=" + verified;
    }
}
