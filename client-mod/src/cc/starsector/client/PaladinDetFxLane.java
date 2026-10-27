package cc.starsector.client;

import com.fs.starfarer.api.Global;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.Random;

/**
 * The GPEE v10 {@code PALADIN_DET} (kind 8) presenter: the Guardian PD
 * point-of-aim explosion on replica ships (a reported defect; triaged
 * as an effect never presented). Vanilla law (the
 * {@code PaladinEffect} plugin): when 5 sub-beams converge &lt;15 px on a
 * damage target, the effect plugin spawns a {@code DamagingExplosion} at {@code beam.getTo()}.
 * On a client the replica gate never opened in measurement — {@code beam.getDamageTarget()} read
 * null every frame and the local effect plugin stayed structurally dead. CORRECTION:
 * the original "replica hulls ride {@code CollisionClass.NONE}" explanation was
 * FOLKLORE — foe replicas ride {@code CollisionClass.SHIP} ({@code PredictReconcileCombatPlugin}
 * 1v1 bind + registry path; drones NONE, fighters FIGHTER) — so the REAL gate is deeper (open
 * candidates: RAY_FIGHTER shorten interplay, brightness-ramp starvation on force-fired
 * one-frame beams) and is the (a)-glow measure leg's question. The measured null-target fact and
 * this lane's design stand either way; the det crosses the snapshot lane only as a content-LESS PROJECTILE row
 * (un-keyable). So the server detects the det birth ({@code EngineCombatBinding}
 * fingerprint probe) and relays it as a GPEE kind-8 event; this lane presents it.
 *
 * <p><b>Damage-LESS by law</b>: FX + sound only at the wire det pose —
 * {@code spawnExplosion}, never {@code spawnDamagingExplosion}; hull/flux/armor stay
 * wire-authoritative. FX constants = {@code PaladinEffect.createExplosionSpec} VERBATIM
 * (r75/core50, 150 white particles at size 3–6 for 0.5 s, explosion color 255,100,100,75,
 * sound {@code explosion_guardian}); the particle velocity band is engine-internal — the
 * 0..radius/duration outward spray here is the presentation approximation. Own-seat dets
 * present through this same wire lane (the server emits every det once); a local
 * {@code PaladinEffect} double-image would need a collidable local damage target and is a
 * residual graded by eye (the overlap class), not filtered here.
 *
 * <p>Knob {@code cc.predict.paladinDetFx} = {@code on|off}, DEFAULT ON — the FX-lane
 * kill-switch idiom ({@code CanisterEndFxLane}/{@code MineBombEndFxLane} shape), NOT the
 * reconcile-lane off|meter|apply mode knob. Policy:
 * damage-less FX presenters of wire events ride default-on with an emergency off-switch,
 * never the mode knob + six-site delivery mirror — default-on means every launch path
 * (kit joiners included) presents the fix with zero config, and misconfigured silence
 * cannot re-create the original bug. Invalid values THROW at class load. The knob statics
 * live HERE, never in PRCP (the ArmorReconcileLane shape). Overlap law:
 * this lane is the ONE det
 * presenter — the content-0 NSE projectile crossing stays counted+skipped at
 * {@link cc.starsector.client.net.AwssSnapshotReader} (its meter). Everything in
 * try/catch-Throwable (the MineBombEndFxLane law); render-thread only; counters cumulative
 * (the FoeHull totals discipline).
 */
final class PaladinDetFxLane {

    /** The det-FX kill-switch, {@code cc.predict.paladinDetFx} = {@code on|off}, DEFAULT ON
     *  ({@code off} = the pre-(111) silence, byte-for-byte; THROWs on other values). */
    static final boolean PALADIN_DET_FX = parseOnOff("cc.predict.paladinDetFx",
            System.getProperty("cc.predict.paladinDetFx", "on"));

    private static boolean parseOnOff(String knob, String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("on".equals(value)) {
            return true;
        }
        if ("off".equals(value)) {
            return false;
        }
        throw new IllegalStateException(knob + " must be on|off; got '" + raw + "'");
    }

    // PaladinEffect.createExplosionSpec VERBATIM: radius 75,
    // core 50, particle size 3+3 range, particle duration 0.5 s, count 150, particles white,
    // explosion color (255,100,100,75), sound set explosion_guardian. Damage keys (100/50
    // FRAGMENTATION) deliberately NOT carried — damage-less presentation is the lane's law.
    private static final float DET_FX_RADIUS = 75f;
    private static final float DET_FX_CORE_RADIUS = 50f;
    private static final float DET_FX_SECONDS = 0.5f;
    private static final int DET_FX_PARTICLE_COUNT = 150;
    private static final float DET_FX_PARTICLE_SIZE_MIN = 3f;
    private static final float DET_FX_PARTICLE_SIZE_RANGE = 3f;
    private static final Color DET_FX_COLOR = new Color(255, 100, 100, 75);

    /** Per-event log cap (counters continue past it) — Paladin dets are dense under fortress
     *  Radiant pressure (~400 candidate tracks/run); the first few lines prove the
     *  lane fed, the meters carry the rest. */
    private static final int LOG_MAX = 20;

    private final PredictReconcileCombatPlugin plugin;
    private int presented;  // predict_event_rebase_paladin_det_presented (FX spawned)
    private int logLines;

    PaladinDetFxLane(PredictReconcileCombatPlugin plugin) {
        this.plugin = plugin;
    }

    /** PRCP kind-8 intake (route-FIRST, the trap law) — present the det at the wire pose.
     *  entityId is the SOURCE ship's wireId and mountIndex may be −1 (the live NO_MOUNT case);
     *  presentation keys off the det pose floats alone, so neither needs resolving. */
    void present(cc.starsector.client.net.ClientGameplayEdgeEvent event) {
        if (!PALADIN_DET_FX) {
            return;
        }
        try {
            Vector2f loc = new Vector2f(event.detX(), event.detY());
            Vector2f still = new Vector2f();
            plugin.engine.spawnExplosion(loc, still, DET_FX_COLOR, DET_FX_RADIUS,
                    DET_FX_SECONDS);
            plugin.engine.addHitParticle(loc, still, DET_FX_CORE_RADIUS, 1f, DET_FX_SECONDS,
                    Color.white);
            // Seeded off the event tick: two clients presenting the same det spray the same
            // way (presentation nicety; no gameplay state reads this).
            Random rng = new Random(event.simTick() * 31L + event.entityId());
            float maxSpeed = DET_FX_RADIUS / DET_FX_SECONDS;
            for (int i = 0; i < DET_FX_PARTICLE_COUNT; i++) {
                float ang = rng.nextFloat() * (float) (Math.PI * 2.0);
                float speed = rng.nextFloat() * maxSpeed;
                Vector2f vel = new Vector2f((float) Math.cos(ang) * speed,
                        (float) Math.sin(ang) * speed);
                plugin.engine.addHitParticle(loc, vel,
                        DET_FX_PARTICLE_SIZE_MIN + rng.nextFloat() * DET_FX_PARTICLE_SIZE_RANGE,
                        1f, DET_FX_SECONDS, Color.white);
            }
            Global.getSoundPlayer().playSound("explosion_guardian", 1f, 1f, loc, still);
            presented++;
            if (logLines < LOG_MAX) {
                logLines++;
                PilotWatchLog.info("predict paladin det-FX: src=" + event.entityId()
                        + " mount=" + event.mountIndex() + " tick=" + event.simTick()
                        + " at=" + (int) loc.x + "," + (int) loc.y);
            }
        } catch (Throwable ignore) {
            // presentation lane; never break the watch
        }
    }

    /** Dets FX-presented — the meter-line liveness token. */
    int presented() {
        return presented;
    }
}
