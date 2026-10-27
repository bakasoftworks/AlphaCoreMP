package cc.starsector.client;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.combat.OrionDeviceStats;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.lwjgl.util.vector.Vector2f;

/**
 * The ONE copy of the served Nova/Orion bomb's FX-only
 * impact companion, shared by {@link CcWrapNovaBurstStats} and
 * {@link CcWrapOrionDeviceStats}. {@link Mirror#advance} was rewritten from the functional spec:
 * on the bomb's first unpaused fizzle frame it adds one pusher-plate
 * impulse (script-instance state the engine's own {@code apply} renders), aims the blast
 * ({@link #blastAngle}) and, viewport-gated, emits a nebula-particle burst drawn from the specced
 * offset distribution ({@link #signedOffsetFraction}) and calls the flash hook; from that frame
 * on it jitters the ship ({@link #jitterIntensity}) and runs the impact / braking clock
 * ({@link #brakingDone}) so it self-removes on the device's schedule. It writes NO ship velocity,
 * stat or command — the wire pose already carries the served lunge, so a local shove would double
 * it; the speed half of the braking verdict is a READ. Every number comes from the device's public
 * tuning object except the spec constants below it, which that object does not carry.
 *
 * <p>Bounds: one plugin per served bomb; its own self-removal ({@code done}, or
 * 1 s untriggered); a hard {@link #MAX_AGE_SEC} age cap; and a static live-set swept by
 * {@code PredictReconcileCombatPlugin} at the round reset and the window teardown ({@link #sweep}).
 * A plugin registers itself in {@code init} (the engine's {@code addPlugin} calls it), so every
 * install path is covered. Combat-thread writes; {@link #LIVE} is lock-guarded because the
 * teardown sweep may run on the writer thread (it then only flags — the plugin removes itself on
 * its own thread).
 */
final class CcServedBombMirror {

    /** The hard age cap (unpaused seconds from install) — a trigger that never came or a braking
     *  window that never closed can never leave an immortal plugin behind. */
    static final float MAX_AGE_SEC = 10f;

    /** The vanilla {@code notifySpawnedExplosionParticles} hook, handed in by the wrapper (the
     *  method is protected; Nova's override paints the flash). */
    interface Flash {
        void at(Vector2f bombLoc);
    }

    // Meters (predict_missile_servedbomb_* on the MissilePoseLane round line, {@link #meterTokens}).
    static int spawnSkipped;    // servedbomb_spawn_skipped (wrapper spawnBomb minted nothing)
    static int mirrorFired;     // servedbomb_mirror_fired (the burst trigger ran: particles/plate/jitter)
    static int mirrorAgeCapped; // servedbomb_mirror_age_capped (removed by the hard age cap)
    static int mirrorSwept;     // servedbomb_mirror_swept (removed by the reset/teardown sweep)

    /** The live mirror plugins (install = {@code init}, removal = self-removal or sweep). */
    private static final Set<Mirror> LIVE = new LinkedHashSet<Mirror>();

    private static CcServedBombBinder novaDefault;
    private static CcServedBombBinder orionDefault;

    private CcServedBombMirror() {
    }

    /** The FX-only plugin (not yet installed). {@code pusherState == null} = no plate impulse;
     *  {@code ship == null} = no jitter, no slot math, particles gated and aimed at the bomb. */
    static EveryFrameCombatPlugin plugin(OrionDeviceStats.OrionDeviceParams p,
            OrionDeviceStats.PusherPlateState pusherState, Flash flash, ShipAPI ship,
            WeaponSlotAPI launchSlot, MissileAPI bomb) {
        return new Mirror(p, pusherState, flash, ship, launchSlot, bomb);
    }

    /** The wrappers' {@link CcServedBombBinder#bindServedBomb} body: nearest system slot, then the
     *  mirror into the engine. Returns the slot id ({@code "-"} = none). */
    static String bind(OrionDeviceStats.OrionDeviceParams p, OrionDeviceStats.PusherPlateState pusherState,
            Flash flash, ShipAPI replica, MissileAPI bomb, Vector2f loc) {
        WeaponSlotAPI slot = replica == null || loc == null ? null : nearestSystemSlot(replica, loc);
        Global.getCombatEngine().addPlugin(plugin(p, pusherState, flash, replica, slot, bomb));
        return slot == null ? "-" : slot.getId();
    }

    /** The replica's system slot nearest {@code loc} (vanilla {@code apply} spawns one bomb per
     *  {@code isSystemSlot()} slot); {@code null} = the hull has none. */
    static WeaponSlotAPI nearestSystemSlot(ShipAPI ship, Vector2f loc) {
        WeaponSlotAPI best = null;
        float bestD2 = Float.MAX_VALUE;
        for (WeaponSlotAPI slot : ship.getHullSpec().getAllWeaponSlotsCopy()) {
            if (!slot.isSystemSlot()) {
                continue;
            }
            Vector2f at = slot.computePosition(ship);
            float dx = at.x - loc.x;
            float dy = at.y - loc.y;
            float d2 = dx * dx + dy * dy;
            if (d2 < bestD2) {
                bestD2 = d2;
                best = slot;
            }
        }
        return best;
    }

    /** The per-spec DEFAULT binder (a detached vanilla-ctor wrapper: FX + flash only, no plate,
     *  no jitter target) for a source whose system script is not a binder; {@code null} for any
     *  other spec. Lazy, combat-thread. */
    static CcServedBombBinder defaultBinder(String specId) {
        if (MineBombEndFxLane.NOVA_BOMB_SPEC.equals(specId)) {
            if (novaDefault == null) {
                novaDefault = new CcWrapNovaBurstStats(true);
            }
            return novaDefault;
        }
        if (MineBombEndFxLane.ORION_BOMB_SPEC.equals(specId)) {
            if (orionDefault == null) {
                orionDefault = new CcWrapOrionDeviceStats(true);
            }
            return orionDefault;
        }
        return null;
    }

    /** The spawn fade for a served bomb: the binder's vanilla {@code bombFadeInTime}, else the
     *  vanilla ctor constants:
     *  Nova 1.0, Orion 0.15. */
    static float fadeInFor(CcServedBombBinder binder, String specId) {
        if (binder != null) {
            return binder.servedBombFadeIn();
        }
        return MineBombEndFxLane.NOVA_BOMB_SPEC.equals(specId) ? 1.0f : 0.15f;
    }

    /** A wrapper's served-branch {@code spawnBomb}: count it (the memo is the caller's). */
    static void noteSpawnSkipped() {
        spawnSkipped++;
    }

    /** The adopt row's {@code press=} token, pure: {@code "none"} when no skip was remembered
     *  ({@code slotId == null} or a non-finite time), else {@code "<age>s@<slotId>"} with the age
     *  (engine seconds, now minus the skip) to 0.01 s. */
    static String skipToken(String slotId, float skipAtSec, float nowSec) {
        if (slotId == null || Float.isNaN(skipAtSec) || Float.isNaN(nowSec)) {
            return "none";
        }
        return String.format(java.util.Locale.ROOT, "%.2fs@%s", nowSec - skipAtSec, slotId);
    }

    /** The engine clock for the skip memo (NaN = no engine). */
    static float engineSeconds() {
        CombatEngineAPI e = Global.getCombatEngine();
        return e == null ? Float.NaN : e.getTotalElapsedTime(false);
    }

    /** The age cap's pure verdict (selftest row): remove once past {@link #MAX_AGE_SEC}. */
    static boolean ageCapped(float elapsed) {
        return elapsed > MAX_AGE_SEC;
    }

    /** Remove every live mirror: on its own thread directly, else flagged so its next
     *  {@code advance} removes it. Called at the round reset and the window teardown. */
    static void sweep() {
        List<Mirror> all;
        synchronized (LIVE) {
            if (LIVE.isEmpty()) {
                return;
            }
            all = new ArrayList<Mirror>(LIVE);
            LIVE.clear();
            mirrorSwept += all.size();
        }
        for (Mirror m : all) {
            m.retired = true;
            if (Thread.currentThread() == m.installThread) {
                m.remove();
            }
        }
    }

    static int liveCount() {
        synchronized (LIVE) {
            return LIVE.size();
        }
    }

    /** Spliced into the MissilePoseLane round line beside the scriptbomb adopt tokens. */
    static String meterTokens() {
        return " predict_missile_servedbomb_spawn_skipped=" + spawnSkipped
                + " predict_missile_servedbomb_mirror_fired=" + mirrorFired
                + " predict_missile_servedbomb_mirror_age_capped=" + mirrorAgeCapped
                + " predict_missile_servedbomb_mirror_swept=" + mirrorSwept
                + " predict_missile_servedbomb_mirror_live=" + liveCount();
    }

    /** The impact companion's spec constants (game facts the
     *  public tuning object does not carry), then its pure laws (no engine type in any signature,
     *  so {@code ApiRwFRows} drives them game-free); {@link Mirror} is their thin engine caller. */
    private static final float VIEW_GATE_SU = 800f;         // burst only when the gate entity is this near the viewport
    private static final float REVERSE_OFF_DEG = 90f;       // launch bearing reversed when MORE than this off the ship bearing
    private static final float BLAST_TURN_FRACTION = 0.2f;  // ship bearing turned this share toward the launch bearing
    private static final float BRAKING_FADE_SEC = 0.5f;     // jitter phase level falls 1 -> 0 over this much braking
    private static final float BRAKING_MAX_SEC = 3f;        // braking ends after this long at the latest
    private static final float PARTICLE_RAMP_UP = 0.1f;     // nebula particle ramp-up fraction
    private static final float PARTICLE_FULL_BRIGHT = 0.5f; // nebula particle full-brightness fraction
    private static final int JITTER_COPIES = 3;
    private static final float SQRT_0_2 = (float) Math.sqrt(0.2);

    /**
     * The blast-direction law, degrees in [0, 360).
     * With a ship: start from the bomb-to-ship-centre bearing; take the bomb-to-launch-point
     * bearing (the caller passes the launch slot's world position, or the ship centre when no
     * slot is known), reversed when it is MORE than 90 degrees off the ship bearing; the result is
     * the ship bearing turned 20 % of the way toward it along the shorter rotation. Without a
     * ship ({@code hasShip == false}, the ship / launch coordinates ignored): opposite the bomb's
     * facing.
     */
    static float blastAngle(float bombX, float bombY, float bombFacing, boolean hasShip,
            float shipX, float shipY, float launchX, float launchY) {
        if (!hasShip) {
            return wrap360(bombFacing + 180f);
        }
        float toShip = bearing(bombX, bombY, shipX, shipY);
        float toLaunch = bearing(bombX, bombY, launchX, launchY);
        float turn = shortestTurn(toShip, toLaunch);
        if (Math.abs(turn) > REVERSE_OFF_DEG) {
            turn = shortestTurn(toShip, toLaunch + 180f);
        }
        return wrap360(toShip + BLAST_TURN_FRACTION * turn);
    }

    /** The bearing from (x0, y0) to (x1, y1), degrees in [0, 360) (0 = +x, counter-clockwise). */
    static float bearing(float x0, float y0, float x1, float y1) {
        return wrap360((float) Math.toDegrees(Math.atan2(y1 - y0, x1 - x0)));
    }

    /** The signed shorter rotation from {@code from} to {@code to}, degrees in (-180, 180]. */
    static float shortestTurn(float from, float to) {
        float d = wrap360(to - from);
        return d > 180f ? d - 360f : d;
    }

    /** {@code deg} wrapped into [0, 360) (a float that rounds up to 360 reads 0). */
    static float wrap360(float deg) {
        float r = deg % 360f;
        if (r < 0f) {
            r += 360f;
        }
        return r >= 360f ? 0f : r;
    }

    /**
     * The jitter-intensity curve: min(phase level,
     * 1 - sinceTrigger / decay), floored at 0. The phase level is 1 before braking and falls
     * linearly to 0 over the first {@link #BRAKING_FADE_SEC} of braking. The caller passes the
     * clocks as they stood at frame start; a non-positive decay yields 0.
     */
    static float jitterIntensity(boolean braking, float brakingSec, float sinceTriggerSec, float decaySec) {
        float phase = braking ? 1f - brakingSec / BRAKING_FADE_SEC : 1f;
        float decay = decaySec > 0f ? 1f - sinceTriggerSec / decaySec : 0f;
        return Math.max(0f, Math.min(phase, decay));
    }

    /**
     * The removal schedule's braking verdict: done at once
     * with no ship; else once braking has run {@link #BRAKING_MAX_SEC}, or as soon as the ship's
     * speed is at or under its current max-speed value (both READ by the caller).
     */
    static boolean brakingDone(boolean hasShip, float brakingSec, float speed, float maxSpeed) {
        return !hasShip || brakingSec >= BRAKING_MAX_SEC || speed <= maxSpeed;
    }

    /**
     * The burst's offset sampler: a magnitude {@code a} in
     * [0, 1] drawn through {@link #offsetFraction} from one uniform, with an equiprobable sign.
     * The caller scales it by half the cone and slows the particle by {@link #speedFactor}.
     */
    static float signedOffsetFraction(Random rnd) {
        float a = offsetFraction(rnd.nextFloat());
        return rnd.nextBoolean() ? a : -a;
    }

    /**
     * The inverse of the offset CDF F at a uniform
     * {@code u} in [0, 1], where F(x) = x on [0, 0.04], x + sqrt(x) - 0.2 on (0.04, 0.2] and
     * sqrt(x) on (0.2, 1] — a mixture that concentrates particles on the blast axis. The middle
     * branch solves s^2 + s - (0.2 + u) = 0 for s = sqrt(x).
     */
    static float offsetFraction(float u) {
        if (u <= 0.04f) {
            return Math.max(0f, u);
        }
        if (u <= SQRT_0_2) {
            double s = (Math.sqrt(1.0 + 4.0 * (0.2 + u)) - 1.0) * 0.5;
            return (float) (s * s);
        }
        return Math.min(1f, u * u);
    }

    /** The particle-speed multiplier of an offset magnitude {@code a} in [0, 1]: 1 - a/2 (axis
     *  particles fastest, cone-edge particles at half speed). */
    static float speedFactor(float a) {
        return 1f - 0.5f * a;
    }

    /** A uniform draw in [lo, hi). */
    private static float between(Random rnd, float lo, float hi) {
        return lo + (hi - lo) * rnd.nextFloat();
    }

    /** The mirror plugin: the six bound inputs, the companion's clocks and flags, then the bounds
     *  fields. The clocks and flags are declared in field-name
     *  order. */
    private static final class Mirror extends BaseEveryFrameCombatPlugin {
        private final OrionDeviceStats.OrionDeviceParams p;
        private final OrionDeviceStats.PusherPlateState pusherState;
        private final Flash flash;
        private final ShipAPI ship;
        private final WeaponSlotAPI launchSlot;
        private final MissileAPI bomb;
        boolean braking = false;
        float brakingTime = 0.0F;
        boolean done = false;
        float elapsed = 0.0F;
        float forceAngle;
        float impactTime = 0.0F;
        float jitterTime = 0.0F;
        boolean triggered = false;
        // Bounds: the install engine/thread + the sweep flag + the one-shot removal latch.
        private CombatEngineAPI engine;
        private Thread installThread;
        volatile boolean retired;
        private boolean removed;

        Mirror(OrionDeviceStats.OrionDeviceParams p, OrionDeviceStats.PusherPlateState pusherState,
                Flash flash, ShipAPI ship, WeaponSlotAPI launchSlot, MissileAPI bomb) {
            this.p = p;
            this.pusherState = pusherState;
            this.flash = flash;
            this.ship = ship;
            this.launchSlot = launchSlot;
            this.bomb = bomb;
        }

        @Override
        public void init(CombatEngineAPI engine) {
            this.engine = engine;
            this.installThread = Thread.currentThread();
            synchronized (LIVE) {
                LIVE.add(this);
            }
        }

        /** The engine's {@code removePlugin(this)} + the live-set exit; idempotent. */
        void remove() {
            if (removed) {
                return;
            }
            removed = true;
            CombatEngineAPI e = engine != null ? engine : Global.getCombatEngine();
            if (e != null) {
                e.removePlugin(this);
            }
            synchronized (LIVE) {
                LIVE.remove(this);
            }
        }

        /**
         * One frame of the impact companion, rewritten from
         * the functional spec. A swept mirror removes itself before any
         * engine read; a paused frame advances nothing. Otherwise: the install age ticks; the
         * removal check (never triggered within 1 s, braking done, or the
         * {@link CcServedBombMirror#MAX_AGE_SEC} cap — counted as age-capped only when it was the
         * sole reason); the one-shot trigger on the bomb's first fizzle frame ({@link #trigger});
         * then, on that frame and every later one, the ship jitter read at the clocks this frame
         * started with, and the impact / braking clock (impact time advances at the tuning's rate
         * until it reaches 1, then braking runs until {@link CcServedBombMirror#brakingDone}).
         * Every ship access is a READ.
         */
        @Override
        public void advance(float amount, List<InputEventAPI> events) {
            if (retired) {
                remove();
                return;
            }
            CombatEngineAPI e = engine != null ? engine : Global.getCombatEngine();
            if (e == null || e.isPaused()) {
                return;
            }
            this.elapsed += amount;
            boolean aged = ageCapped(this.elapsed);
            if (!this.triggered && this.elapsed > 1.0F || this.done || aged) {
                if (aged && this.triggered && !this.done) {
                    mirrorAgeCapped++;
                }
                remove();
                return;
            }
            if (!this.triggered) {
                if (bomb == null || !bomb.isFizzling()) {
                    return;
                }
                trigger(e);
            }
            boolean hasShip = ship != null;
            if (hasShip) {
                ship.setJitter(this, p.jitterColor,
                        jitterIntensity(this.braking, this.brakingTime, this.jitterTime, p.maxJitterDur),
                        JITTER_COPIES, 0f);
            }
            this.jitterTime += amount;
            if (this.braking) {
                this.brakingTime += amount;
            } else {
                this.impactTime += amount * p.impactRateMult;
                this.braking = this.impactTime >= 1.0F;
            }
            if (this.braking) {
                this.done = brakingDone(hasShip, this.brakingTime,
                        hasShip ? ship.getVelocity().length() : 0f,
                        hasShip ? ship.getMutableStats().getMaxSpeed().getModifiedValue() : 0f);
            }
        }

        /**
         * The one-shot fizzle trigger: count it, add one
         * impulse (the tuning's force and duration) to the plate state when one is bound, aim the
         * blast into {@link #forceAngle}, and — only when the ship (no ship: the bomb) is within
         * the viewport gate — emit the burst, then call the flash hook at the bomb's location.
         */
        private void trigger(CombatEngineAPI e) {
            this.triggered = true;
            mirrorFired++;
            if (pusherState != null) {
                pusherState.addImpulse(p.pusherPlateImpulseForce, p.pusherPlateImpulseDuration);
            }
            Vector2f at = bomb.getLocation();
            if (ship == null) {
                this.forceAngle = blastAngle(at.x, at.y, bomb.getFacing(), false, 0f, 0f, 0f, 0f);
            } else {
                Vector2f c = ship.getLocation();
                Vector2f launch = launchSlot == null ? c : launchSlot.computePosition(ship);
                this.forceAngle = blastAngle(at.x, at.y, bomb.getFacing(), true, c.x, c.y, launch.x, launch.y);
            }
            if (!e.getViewport().isNearViewport(ship == null ? at : ship.getLocation(), VIEW_GATE_SU)) {
                return;
            }
            emitBurst(e, at);
            if (flash != null) {
                flash.at(new Vector2f(at));
            }
        }

        /**
         * The burst, a distribution (cosmetic, unseeded):
         * centre = the bomb moved the tuning's spawn offset along the blast; each particle
         * travels at blast + 180 degrees + its sampled offset times half the cone, at a uniform
         * speed times {@link CcServedBombMirror#speedFactor}, and starts scatter x U x U along its
         * own travel from the centre; size, end-size multiple and life are uniform in the tuning's
         * ranges.
         */
        private void emitBurst(CombatEngineAPI e, Vector2f at) {
            Random rnd = ThreadLocalRandom.current();
            double blast = Math.toRadians(this.forceAngle);
            float cx = at.x + (float) Math.cos(blast) * p.shapedExplosionOffset;
            float cy = at.y + (float) Math.sin(blast) * p.shapedExplosionOffset;
            float halfCone = p.shapedExplosionArc * 0.5f;
            for (int i = 0; i < p.shapedExplosionNumParticles; i++) {
                float s = signedOffsetFraction(rnd);
                double travel = Math.toRadians(this.forceAngle + 180f + s * halfCone);
                float ux = (float) Math.cos(travel);
                float uy = (float) Math.sin(travel);
                float speed = between(rnd, p.shapedExplosionMinParticleVel, p.shapedExplosionMaxParticleVel)
                        * speedFactor(Math.abs(s));
                float r = p.shapedExplosionScatter * rnd.nextFloat() * rnd.nextFloat();
                e.addNebulaParticle(new Vector2f(cx + ux * r, cy + uy * r), new Vector2f(ux * speed, uy * speed),
                        between(rnd, p.shapedExplosionMinParticleSize, p.shapedExplosionMaxParticleSize),
                        between(rnd, p.shapedExplosionEndSizeMin, p.shapedExplosionEndSizeMax),
                        PARTICLE_RAMP_UP, PARTICLE_FULL_BRIGHT,
                        between(rnd, p.shapedExplosionMinParticleDur, p.shapedExplosionMaxParticleDur),
                        p.shapedExplosionColor);
            }
        }
    }
}
