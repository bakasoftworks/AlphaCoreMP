package cc.starsector.client;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.EmpArcEntityAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.impl.combat.threat.VoltaicDischargeOnFireEffect;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.util.vector.Vector2f;

/**
 * The vanilla EMP-arc TERMINUS law, ported
 * to public API for the client's damage-free arc visuals.
 *
 * <p><b>Why:</b> a vanilla damaging arc ({@code spawnEmpArc*}) never runs point→point: the engine's
 * {@code EmpArcEntity} ctor's {@code findTarget()} → {@code getPotentialTargets} builds a
 * {@code WeightedRandomPicker} of the struck hull's ENGINE slots and damageable WEAPON modules
 * (weight {@code 1/dist} from the hit point, {@code computeWeight}), replacing a node by its
 * shield-circle intercept when the shield is on and the arc does not pierce shields and
 * falling back to the ship centre only when nothing qualified. Our wrappers minted the
 * damage-free {@code spawnEmpArcVisual} with BOTH anchors on the hit point (zero length, invisible —
 * reported as "no EMP arcs") or with the terminus at {@code target.getLocation()} (reported as "they
 * converge at the true center of my ship").
 * EMP damage itself is server-authoritative and reaches the wire as the per-mount disabled bit;
 * this law is presentation only, 0 wire bytes (prefer-client-side).
 *
 * <p>Fidelity notes: the engine's "damageable module" test is approximated by "not decorative, slot not
 * decorative/hidden" (public API); the intercept uses the same outward ray (node → hit point,
 * length {@code collisionRadius*3}) against the shield circle and the same {@code isWithinArc}
 * acceptance. The pick is a LOCAL re-roll (the server's node never rides the wire) — vanilla's own
 * arcs are random per arc too, so no viewer can tell.
 */
final class EmpArcNodeLaw {

    /** Own-hull residual: one arc + cue per applied wire DISABLE edge (L2 fix sketch 3). */
    static final boolean OWN_DISABLE_ARC = true;
    static final float OWN_DISABLE_ARC_ORIGIN_RADIUS = 40.0F;
    static final float OWN_DISABLE_ARC_THICKNESS = 20.0F;
    static int ownDisableArcs;

    private EmpArcNodeLaw() {
    }

    /**
     * The arc terminus for an arc that struck {@code target} at {@code from}: a random engine /
     * weapon node of the hull (or its shield intercept when shielded and not piercing), weighted
     * {@code 1/dist}; the hull centre when nothing qualifies; {@code target.getLocation()} for a
     * non-ship or a hulk (the engine's not-a-hulk gate — a hulk arc goes centre-anchored, as vanilla).
     * Absolute world coordinates (the caller anchors the arc on {@code target}).
     */
    static Vector2f pick(CombatEntityAPI target, Vector2f from, boolean pierceShields) {
        if (target == null || target.getLocation() == null) {
            return from != null ? new Vector2f(from) : new Vector2f();
        }
        if (!(target instanceof ShipAPI) || ((ShipAPI) target).isHulk() || from == null) {
            return new Vector2f(target.getLocation());
        }
        ShipAPI ship = (ShipAPI) target;
        ShieldAPI shield = ship.getShield();
        boolean shieldOn = shield != null && shield.isOn() && !pierceShields;
        List<Vector2f> nodes = new ArrayList<Vector2f>();
        List<Float> weights = new ArrayList<Float>();
        try {
            ShipEngineControllerAPI ec = ship.getEngineController();
            if (ec != null && ec.getShipEngines() != null) {
                for (ShipEngineControllerAPI.ShipEngineAPI e : ec.getShipEngines()) {
                    if (e != null && e.getLocation() != null) {
                        addNode(nodes, weights, ship, shield, shieldOn, from, e.getLocation());
                    }
                }
            }
            if (ship.getAllWeapons() != null) {
                for (WeaponAPI w : ship.getAllWeapons()) {
                    if (w == null || w.isDecorative() || w.getLocation() == null) {
                        continue;
                    }
                    WeaponSlotAPI slot = w.getSlot();
                    if (slot != null && (slot.isDecorative() || slot.isHidden())) {
                        continue;
                    }
                    addNode(nodes, weights, ship, shield, shieldOn, from, w.getLocation());
                }
            }
        } catch (Throwable ignore) {
            // presentation-only; fall through to the centre
        }
        if (nodes.isEmpty()) {
            return new Vector2f(ship.getLocation());
        }
        float total = 0.0F;
        for (Float wgt : weights) {
            total += wgt.floatValue();
        }
        float roll = (float) Math.random() * total;
        for (int i = 0; i < nodes.size(); i++) {
            roll -= weights.get(i).floatValue();
            if (roll <= 0.0F) {
                return nodes.get(i);
            }
        }
        return nodes.get(nodes.size() - 1);
    }

    private static void addNode(List<Vector2f> nodes, List<Float> weights, ShipAPI ship,
            ShieldAPI shield, boolean shieldOn, Vector2f from, Vector2f node) {
        Vector2f use = new Vector2f(node);
        if (shieldOn) {
            // the engine's intercept: ray from the node toward the hit point, length collisionRadius*3,
            // intersected with the shield circle; accepted only inside the shield arc.
            Vector2f dir = Vector2f.sub(from, node, new Vector2f());
            if (dir.lengthSquared() > 0.0001F) {
                dir.normalise();
                dir.scale(Math.max(1.0F, ship.getCollisionRadius() * 3.0F));
                Vector2f far = Vector2f.add(dir, node, new Vector2f());
                Vector2f ip = segmentCircleExit(node, far, shield.getLocation(), shield.getRadius());
                if (ip != null && shield.isWithinArc(ip)) {
                    use = ip;
                }
            }
        }
        float dist = Math.max(1.0F, Misc.getDistance(from, use));
        nodes.add(use);
        weights.add(Float.valueOf(1.0F / dist));
    }

    /** First crossing of segment a→b with the circle (c, r), or null when it never crosses. */
    static Vector2f segmentCircleExit(Vector2f a, Vector2f b, Vector2f c, float r) {
        if (a == null || b == null || c == null || r <= 0.0F) {
            return null;
        }
        float dx = b.x - a.x;
        float dy = b.y - a.y;
        float fx = a.x - c.x;
        float fy = a.y - c.y;
        float qa = dx * dx + dy * dy;
        if (qa <= 0.000001F) {
            return null;
        }
        float qb = 2.0F * (fx * dx + fy * dy);
        float qc = fx * fx + fy * fy - r * r;
        float disc = qb * qb - 4.0F * qa * qc;
        if (disc < 0.0F) {
            return null;
        }
        float sq = (float) Math.sqrt(disc);
        float t1 = (-qb - sq) / (2.0F * qa);
        float t2 = (-qb + sq) / (2.0F * qa);
        float t = -1.0F;
        if (t1 >= 0.0F && t1 <= 1.0F) {
            t = t1;
        } else if (t2 >= 0.0F && t2 <= 1.0F) {
            t = t2;
        }
        if (t < 0.0F) {
            return null;
        }
        return new Vector2f(a.x + dx * t, a.y + dy * t);
    }

    /**
     * L2 fix sketch 3 — the own hull's residual EMP cue: a server-authored disable whose local
     * projectile twin missed (pose lag) or whose arc was never presented reaches the client ONLY as
     * the wire DISABLE edge ({@code FoeStateLaneCoordinator.maintainWeaponState}). Mint one short
     * arc onto the mount + the discharge impact cue, derived from a field the client already
     * receives. Presentation only; never touches the mount state the lane just applied.
     */
    static void ownDisableArc(ShipAPI ship, WeaponAPI weapon) {
        if (!OWN_DISABLE_ARC || ship == null || weapon == null) {
            return;
        }
        try {
            CombatEngineAPI engine = Global.getCombatEngine();
            Vector2f to = weapon.getLocation();
            if (engine == null || to == null) {
                return;
            }
            Vector2f from = Misc.getPointWithinRadius(to, OWN_DISABLE_ARC_ORIGIN_RADIUS);
            EmpArcEntityAPI arc = engine.spawnEmpArcVisual(from, ship, new Vector2f(to), ship,
                    OWN_DISABLE_ARC_THICKNESS, VoltaicDischargeOnFireEffect.EMP_FRINGE_COLOR, Color.white);
            if (arc != null) {
                arc.setSingleFlickerMode();
            }
            Global.getSoundPlayer().playSound("voltaic_discharge_emp_impact", 1.0F, 0.6F, to, new Vector2f());
            ownDisableArcs++;
        } catch (Throwable ignore) {
            // presentation-only
        }
    }
}
