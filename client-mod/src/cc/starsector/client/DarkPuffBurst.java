package cc.starsector.client;

import java.awt.Color;
import java.util.Random;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.impl.combat.RiftCascadeEffect;
import com.fs.starfarer.api.impl.combat.RiftLanceEffect;
import com.fs.starfarer.api.impl.combat.threat.VoltaicDischargeOnFireEffect;
import com.fs.starfarer.api.util.Misc;

/**
 * The DARK PUFF BURST shared by the construction, reclamation and
 * fracture-dissolve presentations, written from the functional spec. One
 * {@link #burst} lays {@link Use#count} negative-nebula puffs around a body (its location, velocity and
 * collision radius R):
 * <ul>
 *   <li>size {@code S = s × R};</li>
 *   <li>place: a point area-uniform in the disc of radius {@code a × R} about the body, then displaced by a
 *       centre-weighted point within {@code S / 2} (both picks are the engine's own {@code Misc} pickers);</li>
 *   <li>drift: the body's velocity plus a uniformly random direction at {@code 0.2 × S × uniform(1, 1.5)};</li>
 *   <li>life: {@code (uniform(2, 4) s + e) × }{@link #lifeFactor} of the TOTAL drift speed (body velocity
 *       included) — fast bodies leave short puffs;</li>
 *   <li>particle: size S, end size ×2, a 0.5 s ramp-up, no full-brightness hold, duration = life.</li>
 * </ul>
 * WHY the split: the engine's {@code Misc} cannot initialise outside a running game, so the per-puff law
 * ({@link #sample}, {@link #lifeFactor}, {@link #extraLifeSec}) carries no engine type and takes its two
 * placement picks through {@link Placer}; a game-free row drives it with its own picker on a fixed-seed
 * {@link Random}. {@link #burst} is the thin engine caller: it reads each row's colour off the engine's
 * public effect classes, rolls on the engine's shared {@code Misc.random} (cosmetic, unseeded, free-running
 * on every client) and hands each puff to {@code addNegativeNebulaParticle}. Render-thread only; no state.
 *
 * <p>VALUE-DOMAIN: R ≥ 0 su (a collision radius); S ≥ 0 su; e ∈ {0, the row's ending extra}; life ∈
 * [1.1, 5] s (base 2..4 s plus e ≤ 1 s, times a factor in [0.55, 1]), so the ramp-up fraction 0.5 / life
 * stays in (0, 0.46].
 */
final class DarkPuffBurst {

    /** The spec's parameter table: one row per presentation that bursts. */
    enum Use {
        /** Construction — served by the helper; no caller rides it in this wave. */
        CONSTRUCTION(11, 0.75f, 0.33f, 1f),
        /** Threat reclamation: per live piece of a dissolving family. */
        RECLAMATION(11, 0.75f, 0.33f, 1f),
        /** The omega fracture dissolve (rift-dark puffs on a fading parent). */
        FRACTURE(3, 0.5f, 0.35f, 0f);

        /** Puffs per burst (n). */
        final int count;
        /** Placement disc radius as a multiple of R (a). */
        final float discRadii;
        /** Puff size as a multiple of R (s). */
        final float sizeRadii;
        /** Extra life (e, seconds) once under {@link #ENDING_WINDOW_SEC} of the effect remains; 0 = never. */
        final float endingExtraLifeSec;

        Use(int count, float discRadii, float sizeRadii, float endingExtraLifeSec) {
            this.count = count;
            this.discRadii = discRadii;
            this.sizeRadii = sizeRadii;
            this.endingExtraLifeSec = endingExtraLifeSec;
        }
    }

    /** The row's ending extra life applies once less than this much of the effect remains (seconds). */
    static final float ENDING_WINDOW_SEC = 1f;
    /** Relative drift speed per unit of puff size, before the uniform(1, 1.5) jitter. */
    static final float DRIFT_SPEED_PER_SIZE = 0.2f;
    /** Upper end of the drift-speed jitter (the lower end is 1). */
    static final float DRIFT_JITTER_MAX = 1.5f;
    static final float BASE_LIFE_MIN_SEC = 2f;
    static final float BASE_LIFE_MAX_SEC = 4f;
    /** Total speed (per unit of size) at or below which a puff keeps its full life. */
    static final float SLOW_SPEED_PER_SIZE = 0.2f;
    /** Total speed (per unit of size) at or above which a puff lives {@link #FAST_LIFE_FACTOR} of it. */
    static final float FAST_SPEED_PER_SIZE = 0.29f;
    static final float FAST_LIFE_FACTOR = 0.55f;
    /** Particle end size as a multiple of its start size. */
    static final float END_SIZE_MULT = 2f;
    /** Particle ramp-up time (seconds); the engine takes it as a fraction of the duration. */
    static final float RAMP_UP_SEC = 0.5f;
    /** Alpha of the EMP-fringe darkening colour (construction + reclamation rows). */
    static final int EMP_DARK_ALPHA = 50;

    private static final float TWO_PI = (float) (Math.PI * 2.0);

    private DarkPuffBurst() {
    }

    /** One sampled puff (world position, world velocity, size, life). Reused across a burst. */
    static final class Puff {
        float x;
        float y;
        float vx;
        float vy;
        float size;
        float life;
    }

    /**
     * The two placement picks, each writing the picked point into {@code out.x / out.y}. The engine's
     * pickers ({@link #ENGINE_PLACER}) are the production pair; a game-free row supplies its own.
     */
    interface Placer {
        /** A point area-uniform in the disc of {@code radius} about ({@code cx}, {@code cy}). */
        void uniformInDisc(float cx, float cy, float radius, Random rng, Puff out);

        /** A centre-weighted point within {@code radius} of ({@code cx}, {@code cy}). */
        void centreWeightedInDisc(float cx, float cy, float radius, Random rng, Puff out);
    }

    /** Production picks: the engine's {@code Misc.getPointWithinRadiusUniform} / {@code getPointWithinRadius}. */
    static final Placer ENGINE_PLACER = new Placer() {
        @Override
        public void uniformInDisc(float cx, float cy, float radius, Random rng, Puff out) {
            Vector2f p = Misc.getPointWithinRadiusUniform(new Vector2f(cx, cy), radius, rng);
            out.x = p.x;
            out.y = p.y;
        }

        @Override
        public void centreWeightedInDisc(float cx, float cy, float radius, Random rng, Puff out) {
            Vector2f p = Misc.getPointWithinRadius(new Vector2f(cx, cy), radius, rng);
            out.x = p.x;
            out.y = p.y;
        }
    };

    /** The row's extra life (seconds): its ending extra once under {@link #ENDING_WINDOW_SEC} remains, else 0. */
    static float extraLifeSec(Use use, float remainingSec) {
        return remainingSec < ENDING_WINDOW_SEC ? use.endingExtraLifeSec : 0f;
    }

    /**
     * The life factor of a puff whose TOTAL speed is {@code speed} (su/s ≥ 0) at size {@code size} (≥ 0):
     * 1 at or below {@code 0.2 S}, falling linearly to {@link #FAST_LIFE_FACTOR} at {@code 0.29 S}, flat
     * beyond. A zero size makes any positive speed "fast".
     */
    static float lifeFactor(float speed, float size) {
        float slow = SLOW_SPEED_PER_SIZE * size;
        float fast = FAST_SPEED_PER_SIZE * size;
        if (speed <= slow) {
            return 1f;
        }
        if (speed >= fast) {
            return FAST_LIFE_FACTOR;
        }
        return 1f - (1f - FAST_LIFE_FACTOR) * (speed - slow) / (fast - slow);
    }

    /**
     * Samples ONE puff of {@code use} about a body at ({@code bodyX}, {@code bodyY}) moving at
     * ({@code bodyVx}, {@code bodyVy}) with collision radius {@code radius} (≥ 0), into {@code out}.
     * Draws from {@code rng} only (and through {@code placer}); no engine type, no state.
     */
    static void sample(Use use, float bodyX, float bodyY, float bodyVx, float bodyVy, float radius,
            float extraLifeSec, Random rng, Placer placer, Puff out) {
        float size = use.sizeRadii * radius;
        placer.uniformInDisc(bodyX, bodyY, use.discRadii * radius, rng, out);
        placer.centreWeightedInDisc(out.x, out.y, size * 0.5f, rng, out);
        float angle = rng.nextFloat() * TWO_PI;
        float speed = DRIFT_SPEED_PER_SIZE * size * (1f + (DRIFT_JITTER_MAX - 1f) * rng.nextFloat());
        out.vx = bodyVx + (float) Math.cos(angle) * speed;
        out.vy = bodyVy + (float) Math.sin(angle) * speed;
        float baseLife = BASE_LIFE_MIN_SEC + (BASE_LIFE_MAX_SEC - BASE_LIFE_MIN_SEC) * rng.nextFloat();
        out.size = size;
        out.life = (baseLife + extraLifeSec) * lifeFactor((float) Math.hypot(out.vx, out.vy), size);
    }

    /** The row's puff colour, read off the engine's public effect classes (never re-typed). */
    static Color colorFor(Use use) {
        if (use == Use.FRACTURE) {
            return RiftLanceEffect.getColorForDarkening(RiftCascadeEffect.STANDARD_RIFT_COLOR);
        }
        return Misc.setAlpha(RiftLanceEffect.getColorForDarkening(VoltaicDischargeOnFireEffect.EMP_FRINGE_COLOR),
                EMP_DARK_ALPHA);
    }

    /**
     * One burst of {@code use} around {@code body} (render thread; a live engine). {@code extraLifeSec} is the
     * caller's {@link #extraLifeSec} for its own remaining time (0 for a row without an ending extra).
     */
    static void burst(Use use, CombatEngineAPI engine, CombatEntityAPI body, float extraLifeSec) {
        Vector2f loc = body.getLocation();
        Vector2f vel = body.getVelocity();
        float bodyVx = vel != null ? vel.x : 0f;
        float bodyVy = vel != null ? vel.y : 0f;
        float radius = body.getCollisionRadius();
        Color color = colorFor(use);
        Puff puff = new Puff();
        for (int i = 0; i < use.count; i++) {
            sample(use, loc.x, loc.y, bodyVx, bodyVy, radius, extraLifeSec, Misc.random, ENGINE_PLACER, puff);
            engine.addNegativeNebulaParticle(new Vector2f(puff.x, puff.y), new Vector2f(puff.vx, puff.vy),
                    puff.size, END_SIZE_MULT, RAMP_UP_SEC / puff.life, 0f, puff.life, color);
        }
    }
}
