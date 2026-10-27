package cc.starsector.client;

import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import org.lwjgl.util.vector.Vector2f;

import java.util.List;

/**
 * Spike 1 of the client-integration arc: prove the real Starsector
 * engine renders externally-driven kinematics.
 *
 * <p>Each combat frame this overwrites a non-flagship allied "puppet" ship's
 * location, velocity, and facing from a <em>scripted</em> circular path that the
 * local simulation did not produce. There is no networking yet — the scripted
 * path is a stand-in for the authoritative server snapshot stream that Spike 2
 * will feed in over the existing off-loopback transport. If the puppet visibly
 * flies the circle and the local sim does not stomp the per-frame override, the
 * load-bearing assumption behind the whole "real Starsector client" arc holds.
 *
 * <p>The plugin writes authoritative state <em>onto</em> the engine; it never
 * reads authority back out, so it is consistent with the snapshot-authoritative
 * model and the no-authority client contract.
 */
public class PuppetCombatPlugin extends BaseEveryFrameCombatPlugin {

    /** Radius of the scripted circle, in world units. */
    private static final float RADIUS = 400f;
    /** Angular speed around the circle, in radians per second. */
    private static final float ANGULAR_SPEED = 0.6f;

    private CombatEngineAPI engine;
    private ShipAPI puppet;
    private Vector2f center;
    private float elapsed;

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine == null || engine.isPaused()) {
            return;
        }
        elapsed += amount;

        if (puppet == null || !puppet.isAlive()) {
            puppet = findPuppet();
            if (puppet == null) {
                return;
            }
            // Anchor the circle at the puppet's spawn position the first frame we see it.
            center = new Vector2f(puppet.getLocation());
        }

        float theta = elapsed * ANGULAR_SPEED;
        float cos = (float) Math.cos(theta);
        float sin = (float) Math.sin(theta);

        // Scripted position on the circle.
        float px = center.x + RADIUS * cos;
        float py = center.y + RADIUS * sin;
        // Scripted velocity = time-derivative of the position (tangent to the circle).
        float vx = -RADIUS * ANGULAR_SPEED * sin;
        float vy = RADIUS * ANGULAR_SPEED * cos;
        // Face the direction of travel. Starsector facing is in degrees.
        float facingDeg = (float) Math.toDegrees(Math.atan2(vy, vx));

        // OVERWRITE the engine's own state for this entity, from outside the local sim.
        Vector2f loc = puppet.getLocation();
        loc.x = px;
        loc.y = py;
        Vector2f vel = puppet.getVelocity();
        vel.x = vx;
        vel.y = vy;
        puppet.setFacing(facingDeg);
        puppet.setAngularVelocity(0f);
    }

    /**
     * The first living player-side ship that is not the player's own flagship.
     * In the Spike 1 mission that is the allied "puppet" ship.
     */
    private ShipAPI findPuppet() {
        ShipAPI player = engine.getPlayerShip();
        int playerOwner = player != null ? player.getOwner() : 0;
        for (ShipAPI ship : engine.getShips()) {
            if (ship == player) {
                continue;
            }
            if (!ship.isAlive()) {
                continue;
            }
            if (ship.getOwner() != playerOwner) {
                continue;
            }
            return ship;
        }
        return null;
    }
}
