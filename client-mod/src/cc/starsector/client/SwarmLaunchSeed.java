package cc.starsector.client;

import java.util.List;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.impl.combat.threat.FragmentSwarmHullmod;
import com.fs.starfarer.api.impl.combat.threat.RoilingSwarmEffect;
import com.fs.starfarer.api.impl.combat.threat.SwarmLauncherEffect;
import com.fs.starfarer.api.util.Misc;

/**
 * A Hive-launched swarm-fighter replica is born INSIDE its Hive's fragment cloud,
 * the vanilla way. Vanilla {@code SwarmLauncherEffect.onFire} creates the leader's swarm and
 * TRANSFERS {@code FRAGMENT_NUM} (50) members of the launching ship's cloud to a point 0.5 s ahead of
 * the leader (bright, already-flying members re-attached — no fade-in). The replica lane mints the
 * fighter wing-less through {@code spawnFleetMember}; {@link CcWrapSwarmLauncherEffect} holds the
 * replica Hive's launcher inert, so nothing transferred: the fighter's {@code fragment_swarm} hullmod
 * fresh-minted 50 members AT the body, each fading in over 0.5–1 s, chasing a body already at its
 * 250 su/s takeoff kick (probe rows: {@code bright} 0.04 → 1.00 over ~0.9 s, {@code cloudOff}
 * 3 → 126 su). This lane runs ONCE per swarm-fighter replica right after the first-sight snap (loc +
 * wire velocity adopted): find the launching Hive = the nearest same-owner non-fighter hull with a
 * live cloud within reach of the fighter, and replay the vanilla transfer. No donor in reach (a
 * deploy-line wing) = the vanilla fresh-creation law, untouched — that IS what a vanilla client shows
 * for a fleet-deployed swarm wing. Cosmetic, engine-side only; a throw disables nothing but this seed.
 */
final class SwarmLaunchSeed {

    /** Launch reach past the donor's collision radius (the launcher muzzle sits on the hull; the wire
     *  pose at bind is at most a few ticks of 250 su/s past it). */
    static final float DONOR_REACH = 150f;
    /** Vanilla {@code SwarmLauncherEffect.onFire}: the transfer point leads by half a second. */
    static final float LEAD_SEC = 0.5f;
    /** Vanilla's transfer picker prefers members within this range of the lead point. */
    static final float PREFER_RANGE = 100f;
    /** {@code FRAGMENT_NUM} fallback when the wing table has no row. */
    static final int FRAGMENTS_DEFAULT = 50;

    private SwarmLaunchSeed() {
    }

    /**
     * @return members moved from the donor's cloud, {@code -1} when not applicable (not a swarm fighter,
     *         a construction swarm, an already-populated cloud, or no donor in reach).
     */
    static int seed(ShipAPI fighter, long entityId, CombatEngineAPI engine) {
        if (fighter == null || engine == null) {
            return -1;
        }
        try {
            ShipHullSpecAPI spec = fighter.getHullSpec();
            if (spec == null || !SwarmSpriteGate.wantsHide(spec.getTags(), spec.getBaseHullId())) {
                return -1;
            }
            if (fighter.getVariant() != null && SwarmLauncherEffect.CONSTRUCTION_SWARM_VARIANT
                    .equals(fighter.getVariant().getHullVariantId())) {
                return -1; // ConstructionPresentLane owns that cloud
            }
            RoilingSwarmEffect swarm = RoilingSwarmEffect.getSwarmFor(fighter);
            if (swarm != null && swarm.getMembers() != null && !swarm.getMembers().isEmpty()) {
                return -1; // already populated (a second bind of the same body)
            }
            Vector2f loc = fighter.getLocation();
            ShipAPI donor = null;
            RoilingSwarmEffect donorSwarm = null;
            float best = Float.MAX_VALUE;
            List<ShipAPI> ships = engine.getShips();
            for (int i = 0; i < ships.size(); i++) {
                ShipAPI s = ships.get(i);
                if (s == null || s == fighter || s.getOwner() != fighter.getOwner()
                        || s.isFighter() || s.isHulk() || !s.isAlive()) {
                    continue;
                }
                RoilingSwarmEffect ds = RoilingSwarmEffect.getSwarmFor(s);
                if (ds == null) {
                    continue; // no cloud at all = not a swarm hull; an EMPTY cloud still marks the launcher (top-up below)
                }
                float d = Misc.getDistance(loc, s.getLocation()) - s.getCollisionRadius();
                if (d <= DONOR_REACH && d < best) {
                    best = d;
                    donor = s;
                    donorSwarm = ds;
                }
            }
            if (donor == null) {
                return -1;
            }
            if (swarm == null) {
                swarm = FragmentSwarmHullmod.createSwarmFor(fighter); // as vanilla
            }
            Vector2f lead = new Vector2f(fighter.getVelocity());
            if (lead.length() < 1f) {
                // no wire velocity yet: vanilla's takeoff kick = maxSpeed along the facing
                lead = Misc.getUnitVectorAtDegreeAngle(fighter.getFacing());
                lead.scale(fighter.getMaxSpeed());
            }
            lead.scale(LEAD_SEC);
            Vector2f.add(lead, loc, lead);
            Integer tableNum = SwarmLauncherEffect.FRAGMENT_NUM.get(SwarmLauncherEffect.ATTACK_SWARM_WING);
            int fragments = tableNum == null ? FRAGMENTS_DEFAULT : tableNum.intValue();
            int before = swarm.getMembers() == null ? 0 : swarm.getMembers().size();
            donorSwarm.transferMembersTo(swarm, fragments, lead, PREFER_RANGE);
            int moved = (swarm.getMembers() == null ? 0 : swarm.getMembers().size()) - before;
            // Measured: the FIRST launch pair of a fresh Hive replica found its donor cloud still
            // EMPTY (members spawn on the cloud's first advance, one frame after the Hive's bind) and
            // fell to the dim fresh law (ids 42/43/49/50). Vanilla's launcher only fires once the Hive's
            // cloud holds >= FRAGMENT_NUM members, so the server's
            // Hive HAD them — top the shortfall up bright at the lead point, the transferred look.
            int topped = 0;
            int missing = fragments - moved;
            if (missing > 0) {
                int at = swarm.getMembers() == null ? 0 : swarm.getMembers().size();
                swarm.addMembers(missing);
                List<RoilingSwarmEffect.SwarmMember> ms = swarm.getMembers();
                for (int i = at; ms != null && i < ms.size(); i++) {
                    RoilingSwarmEffect.SwarmMember m = ms.get(i);
                    if (m == null) {
                        continue;
                    }
                    m.loc.set(Misc.getPointWithinRadius(lead, PREFER_RANGE));
                    if (m.fader != null) {
                        m.fader.forceIn();
                    }
                    topped++;
                }
            }
            PilotWatchLog.info("predict swarm-launch seed: entity " + entityId + " <- donor "
                    + donor.getHullSpec().getHullId() + "@" + Integer.toHexString(System.identityHashCode(donor))
                    + " gap=" + String.format(java.util.Locale.ROOT, "%.1f", best)
                    + " moved=" + moved + "/" + fragments + " topped=" + topped
                    + " donorLeft=" + donorSwarm.getNumActiveMembers()
                    + " lead=" + String.format(java.util.Locale.ROOT, "%.1f", fighter.getVelocity().length() * LEAD_SEC));
            return moved + topped;
        } catch (Throwable t) {
            PilotWatchLog.error("predict swarm-launch seed: entity " + entityId + " FAILED (vanilla fresh cloud stands)", t);
            return -1;
        }
    }
}
