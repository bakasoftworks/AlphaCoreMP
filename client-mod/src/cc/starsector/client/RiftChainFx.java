package cc.starsector.client;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.impl.combat.NegativeExplosionVisual;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import org.lwjgl.util.vector.Vector2f;

/**
 * The rift chain (spec SR), the shared visual-only stand-in
 * for the engine's rift spawn on a damaging explosion: given a LOOK (a
 * {@link NegativeExplosionVisual.NEParams} its caller built) and an ANCHOR point, it draws the look's
 * own rift count as layered {@link NegativeExplosionVisual} rendering plugins — nothing collidable,
 * nothing damaging, no explosion entity, so a treated-replica wrapper can show the rift while the
 * damage stays the server's. Written from the functional spec.
 *
 * <p>Split: {@link #place} is the PURE placement law (no engine type in its signature; the game-free
 * rows in {@code ApiRwD1Rows} drive it with a fixed-seed {@link Random}); {@link #spawn} is the thin
 * engine caller. Callers: {@link CcWrapAbyssalGlareEffect} (route (b) of spec S13); the Rift
 * Lightning bloom is the next caller the spec names.
 *
 * <p>Law per rift {@code i} of {@code n = max(0, numRiftsToSpawn)}: its own copy of the look; radius =
 * look radius x uniform[0.75, 1.25); centre ON the circle of radius 0.4 x that radius about the
 * anchor, at a uniform bearing; the hit glow ALWAYS on {@code i == 0}, whatever the look's own
 * hit-glow flag says, and never on a later rift; every {@code i >= 1} drifts
 * toward rift {@code i - 1} at speed = their distance / (fade-in + fade-out) x 0.7 su/s (0 when the
 * look's fade-in + fade-out is not positive).
 */
final class RiftChainFx {

    /** Radius jitter band, as a multiple of the look's radius: uniform over [MIN, MAX). */
    static final float RADIUS_JITTER_MIN = 0.75f;
    static final float RADIUS_JITTER_MAX = 1.25f;
    /** Ring radius about the anchor, as a fraction of the rift's own (jittered) radius. */
    static final float RING_FRACTION = 0.4f;
    /** Drift speed = distance to the previous rift / (fade-in + fade-out) x this. */
    static final float DRIFT_FRACTION = 0.7f;

    private RiftChainFx() {
    }

    /**
     * One planned rift. Units: {@code x, y} world su; {@code radius} su (>= 0 for a look radius >= 0);
     * {@code vx, vy} su/s (both 0 on the first rift); {@code hitGlow} true on the first rift only.
     */
    static final class Placed {
        final float x;
        final float y;
        final float radius;
        final boolean hitGlow;
        final float vx;
        final float vy;

        Placed(float x, float y, float radius, boolean hitGlow, float vx, float vy) {
            this.x = x;
            this.y = y;
            this.radius = radius;
            this.hitGlow = hitGlow;
            this.vx = vx;
            this.vy = vy;
        }
    }

    /**
     * The pure placement law (see the class doc). {@code count} any int (a negative count draws
     * nothing); {@code baseRadius} su; {@code fadeIn}/{@code fadeOut} seconds; the anchor in world su.
     * Draw order per rift: the radius jitter, then the bearing, both from {@code rng}.
     */
    static Placed[] place(int count, float baseRadius, float fadeIn, float fadeOut,
                          float anchorX, float anchorY, Random rng) {
        int n = Math.max(0, count);
        Placed[] out = new Placed[n];
        for (int i = 0; i < n; i++) {
            float radius = baseRadius
                    * (RADIUS_JITTER_MIN + (RADIUS_JITTER_MAX - RADIUS_JITTER_MIN) * rng.nextFloat());
            double bearing = rng.nextDouble() * 2.0 * Math.PI;
            float ring = RING_FRACTION * radius;
            float x = anchorX + (float) (Math.cos(bearing) * ring);
            float y = anchorY + (float) (Math.sin(bearing) * ring);
            float vx = 0f;
            float vy = 0f;
            if (i > 0) {
                float dx = out[i - 1].x - x;
                float dy = out[i - 1].y - y;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist > 0f) {
                    float speed = driftSpeed(dist, fadeIn, fadeOut);
                    vx = dx / dist * speed;
                    vy = dy / dist * speed;
                }
            }
            out[i] = new Placed(x, y, radius, i == 0, vx, vy);
        }
        return out;
    }

    /** Drift speed (su/s) toward the previous rift: {@code distance / (fadeIn + fadeOut) x 0.7};
     *  0 when the fade sum is not positive (the spec's division has no defined value there). */
    static float driftSpeed(float distance, float fadeIn, float fadeOut) {
        float life = fadeIn + fadeOut;
        return life > 0f ? distance / life * DRIFT_FRACTION : 0f;
    }

    /**
     * The thin engine caller: draws the chain {@link #place} plans for {@code look} about
     * {@code anchor}, each rift from its OWN copy of the look (radius and hit glow set per rift from
     * the plan: the first rift glows, a later one does not — the look's own hit-glow flag is
     * overridden on every copy), added as a layered rendering plugin and positioned / set drifting
     * on the entity the engine returns. {@code look} is read, never written. Returns the number of rifts drawn (0 for a
     * null engine, look or anchor).
     */
    static int spawn(CombatEngineAPI engine, NegativeExplosionVisual.NEParams look, Vector2f anchor) {
        if (engine == null || look == null || anchor == null) {
            return 0;
        }
        Placed[] chain = place(look.numRiftsToSpawn, look.radius, look.fadeIn, look.fadeOut,
                anchor.x, anchor.y, ThreadLocalRandom.current());
        for (Placed r : chain) {
            NegativeExplosionVisual.NEParams p = copyOf(look);
            p.radius = r.radius;
            p.withHitGlow = r.hitGlow;
            CombatEntityAPI e = engine.addLayeredRenderingPlugin(new NegativeExplosionVisual(p));
            e.getLocation().set(r.x, r.y);
            e.getVelocity().set(r.vx, r.vy);
        }
        return chain.length;
    }

    /** An explicit copy of every public field of the look ({@code NEParams.clone()} is protected,
     *  so each rift's own copy is built here). */
    static NegativeExplosionVisual.NEParams copyOf(NegativeExplosionVisual.NEParams s) {
        NegativeExplosionVisual.NEParams p = new NegativeExplosionVisual.NEParams();
        p.numRiftsToSpawn = s.numRiftsToSpawn;
        p.fadeIn = s.fadeIn;
        p.fadeOut = s.fadeOut;
        p.spawnHitGlowAt = s.spawnHitGlowAt;
        p.hitGlowSizeMult = s.hitGlowSizeMult;
        p.radius = s.radius;
        p.thickness = s.thickness;
        p.noiseMag = s.noiseMag;
        p.noiseMult = s.noiseMult;
        p.noisePeriod = s.noisePeriod;
        p.withHitGlow = s.withHitGlow;
        p.withNegativeParticles = s.withNegativeParticles;
        p.color = s.color;
        p.underglow = s.underglow;
        p.blackColor = s.blackColor;
        p.invertForDarkening = s.invertForDarkening;
        p.additiveBlend = s.additiveBlend;
        return p;
    }
}
