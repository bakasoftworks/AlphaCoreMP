package cc.starsector.client;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EmpArcEntityAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.impl.combat.NegativeExplosionVisual;
import com.fs.starfarer.api.impl.combat.RiftCascadeMineExplosion;
import com.fs.starfarer.api.impl.combat.dweller.AbyssalGlareEffect;
import com.fs.starfarer.api.impl.combat.dweller.DarkenedGazeSystemScript;
import com.fs.starfarer.api.impl.combat.dweller.RiftLightningEffect;
import com.fs.starfarer.api.util.Misc;
import java.awt.Color;
import org.lwjgl.util.vector.Vector2f;

/**
 * {@code abyssal_glare} — treated-replica beam wrapper over the vanilla
 * {@link AbyssalGlareEffect} (shrouded LARGE beam; the same body also drives the
 * {@code darkened_gaze} system split, so the replica gate keys on the SOURCE ship, independent
 * of the GAZE / NORMAL mode and of which weapon is primary). One sim door: the damaging rift
 * explosion (energy AoE, unseeded cadence) — the server's. A source that is not a treated replica
 * rides {@code super.advance} unchanged.
 *
 * <p>The replica branch was rewritten from the functional
 * spec (spec S13 route (b)). It reuses the parent's protected cadence
 * state (the interval, last frame's damage-target and length-changed flags, the since-rift clock,
 * last frame's origin and end point), so that state stays coherent whichever branch ran last. Each
 * rift is drawn by {@link RiftChainFx} as layered rendering plugins only — no explosion entity,
 * nothing collidable or damaging; the arc and both sounds are visual / audio only. The pure laws
 * live in {@link GlareLaw} (game-free rows: {@code ApiRwD1Rows}).
 */
public class CcWrapAbyssalGlareEffect extends AbyssalGlareEffect {

    static final String TAG = "abyssalglare";

    /**
     * The per-frame beam body. Not a treated replica (or no
     * source): the parent's body, unchanged. A treated replica counts its replica frame, then does
     * nothing more without a weapon or an engine; otherwise, per frame:
     * <ul>
     * <li>the since-rift clock += {@code amount}; end point = whichever of the previous-frame ray end
     *     and the current end lies nearer the origin; {@code L} = origin-to-end-point distance;</li>
     * <li>the length-changed and forced-rift flags per {@link GlareLaw#lengthChanged} /
     *     {@link GlareLaw#forcedRift} (first frame = no stored origin / end point yet), then this
     *     frame's flags and points are stored (fresh copies: a vector held there is never written in
     *     place);</li>
     * <li>trigger = the cadence interval elapsed, or a forced rift. A trigger frame
     *     {@link GlareLaw#triggerCutShort} rejects ends there — no loop sound either. Otherwise a
     *     trigger may draw a rift at the end point ({@link GlareLaw#riftOnTrigger}; size
     *     {@link GlareLaw#riftSize}; {@link #spawnRiftVisualAnalog}; the since-rift clock resets) and
     *     a visual arc ({@link GlareLaw#arcOnTrigger}; {@link #spawnArcVisual});</li>
     * <li>every frame not cut short keeps the mode's loop sound ({@link GlareLaw#loopSound}) alive
     *     at the origin / end-point midpoint, volume = beam brightness, the source's velocity; the
     *     loop is keyed on the SOURCE SHIP, so every glare of one ship shares one loop.</li>
     * </ul>
     * Census: each rift counts {@code doorShut} (the damaging explosion the server owns, never
     * minted here) and, when the chain drew at least one rift, {@code visualMinted}.
     */
    @Override
    public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {
        ShipAPI source = beam.getSource();
        if (!CcReplicaEffectGate.isTreatedReplica(source)) {
            super.advance(amount, engine, beam);
            return;
        }
        CcReplicaEffectGate.replicaFrame(TAG);
        WeaponAPI weapon = beam.getWeapon();
        if (weapon == null || engine == null) {
            return;
        }
        boolean gaze = source.hasTag(DarkenedGazeSystemScript.DARKENED_GAZE_SYSTEM_TAG);
        Object custom = weapon.getCustom();
        boolean primary = !gaze
                || (custom != null && custom.equals(DarkenedGazeSystemScript.DARKENED_GAZE_PRIMARY_WEAPON_TAG));

        sinceRiftSpawn += amount;
        Vector2f from = beam.getFrom();
        Vector2f end = beam.getTo();
        Vector2f prevEnd = beam.getRayEndPrevFrame();
        if (prevEnd != null && Misc.getDistance(from, prevEnd) < Misc.getDistance(from, end)) {
            end = prevEnd;
        }
        float length = Misc.getDistance(from, end);
        boolean firstFrame = prevFrom == null || prevTo == null;
        float prevLength = firstFrame ? 0f : Misc.getDistance(prevFrom, prevTo);
        boolean lengthChanged = GlareLaw.lengthChanged(firstFrame, length, prevLength, amount,
                engine.getTimeMult().getModifiedValue(), source.getMutableStats().getTimeMult().getModifiedValue());
        boolean hasTarget = beam.getDamageTarget() != null;
        boolean forced = GlareLaw.forcedRift(primary, hasTarget, hadDamageTargetPrev, lengthChanged,
                lengthChangedPrev);
        hadDamageTargetPrev = hasTarget;
        lengthChangedPrev = lengthChanged;
        prevFrom = new Vector2f(from);
        prevTo = new Vector2f(end);

        Vector2f mid = Vector2f.add(from, end, new Vector2f());
        mid.scale(0.5f);
        fireInterval.advance(amount);
        boolean trigger = fireInterval.intervalElapsed() || forced;
        if (trigger) {
            if (GlareLaw.triggerCutShort(hasTarget, length, weapon.getRange(), beam.getBrightness())) {
                return;
            }
            boolean rift = GlareLaw.riftOnTrigger((float) Math.random(), forced, primary, gaze, sinceRiftSpawn);
            if (rift) {
                CcReplicaEffectGate.doorShut(TAG);
                if (spawnRiftVisualAnalog(engine, end, GlareLaw.riftSize(gaze, length)) > 0) {
                    CcReplicaEffectGate.visualMinted(TAG);
                }
                sinceRiftSpawn = 0f;
            }
            if (GlareLaw.arcOnTrigger(length, Math.random() < 0.5, gaze, rift)) {
                spawnArcVisual(engine, source, from, end, mid, length);
            }
        }
        String loop = GlareLaw.loopSound(gaze, primary);
        if (loop != null) {
            Global.getSoundPlayer().playLoop(loop, source, 1f, beam.getBrightness(), mid, source.getVelocity());
        }
    }

    /** One glare rift, visual only: the engine's
     *  standard rift look in the rift-lightning colour at {@code size} (su), then noise multiple 6,
     *  thickness 25, fade-in 0 s / fade-out 0.5 s, hit glow at 1 (the look's hit-glow flag is set off,
     *  but {@link RiftChainFx} gives the chain's first rift the hit glow regardless), additive blend,
     *  white black-colour, no underglow, no negative particles — drawn by {@link RiftChainFx} about
     *  {@code where} (read, never written). No explosion entity, nothing collidable or damaging.
     *  Returns the rifts drawn (>= 0). */
    private static int spawnRiftVisualAnalog(CombatEngineAPI engine, Vector2f where, float size) {
        NegativeExplosionVisual.NEParams look =
                RiftCascadeMineExplosion.createStandardRiftParams(RiftLightningEffect.RIFT_LIGHTNING_COLOR, size);
        look.additiveBlend = true;
        look.blackColor = Color.white;
        look.fadeIn = 0f;
        look.fadeOut = 0.5f;
        look.noiseMult = 6f;
        look.spawnHitGlowAt = 1f;
        look.thickness = 25f;
        look.underglow = null;
        look.withHitGlow = false;
        look.withNegativeParticles = false;
        return RiftChainFx.spawn(engine, look, where);
    }

    /** The visual-only rift-lightning arc, beam
     *  origin to end point, both ends anchored to the source: thickness 80, core width 40, fringe in the
     *  rift-lightning colour, white core; segment length x8, zig-zag reduction 0.15, fade-out distance
     *  50, minimum fade-out x10, flicker x0.3; bright-spot fractions and travel time per
     *  {@link GlareLaw}; no glow at the start, starts faded out, single flicker. Then the one-shot
     *  {@code abyssal_glare_lightning} sound at {@code mid}, zero velocity. {@code length} = L (su). */
    private static void spawnArcVisual(CombatEngineAPI engine, ShipAPI source, Vector2f from, Vector2f end,
                                       Vector2f mid, float length) {
        EmpArcEntityAPI.EmpArcParams look = new EmpArcEntityAPI.EmpArcParams();
        float spot = GlareLaw.brightSpotFraction(length);
        look.brightSpotFadeFraction = spot;
        look.brightSpotFullFraction = spot;
        look.fadeOutDist = 50f;
        look.flickerRateMult = 0.3f;
        look.minFadeOutMult = 10f;
        look.movementDurOverride = GlareLaw.arcTravelTime(length, RiftLightningEffect.RIFT_LIGHTNING_SPEED);
        look.segmentLengthMult = 8f;
        look.zigZagReductionFactor = 0.15f;
        EmpArcEntityAPI arc = engine.spawnEmpArcVisual(new Vector2f(from), source, new Vector2f(end), source,
                80f, RiftLightningEffect.RIFT_LIGHTNING_COLOR, Color.white, look);
        if (arc != null) {
            arc.setCoreWidthOverride(40f);
            arc.setRenderGlowAtStart(false);
            arc.setFadedOutAtStart(true);
            arc.setSingleFlickerMode(true);
        }
        Global.getSoundPlayer().playSound("abyssal_glare_lightning", 1f, 1f, new Vector2f(mid), new Vector2f());
    }

    /**
     * Spec S13's pure laws: no engine type in a signature, so
     * the game-free rows ({@code ApiRwD1Rows}) drive them (this nested class loads without its outer
     * class). Units: lengths / ranges su (>= 0), times s (>= 0), time multipliers unitless (> 0 in
     * play), brightness 0..1, draws uniform [0, 1).
     */
    static final class GlareLaw {
        /** Length-change slack = RATE / max(MIN_ENGINE_TIME_MULT, engine time mult) x dt
         *  + SLACK x max(1, source time mult). */
        static final float LENGTH_RATE = 180f;
        static final float LENGTH_SLACK = 10f;
        static final float MIN_ENGINE_TIME_MULT = 0.1f;
        /** A target-less trigger is cut short below this fraction of the weapon's range. */
        static final float IDLE_RANGE_FRACTION = 0.9f;
        static final float RIFT_DRAW_ABOVE = 0.8f;
        static final float RIFT_CLOCK_GAZE = 1f;
        static final float RIFT_CLOCK_NORMAL = 0.5f;
        static final float SIZE_NORMAL = 7.5f;
        static final float SIZE_GAZE_BASE = 15f;
        static final float SIZE_GAZE_SPAN = 5f;
        static final float GAZE_RAMP_FROM = 500f;
        static final float GAZE_RAMP_TO = 2000f;
        static final float ARC_MIN_LENGTH = 100f;
        static final float BRIGHT_SPOT_MAX = 0.33f;
        static final float BRIGHT_SPOT_LENGTH = 300f;
        static final float ARC_MIN_TRAVEL = 0.05f;
        static final String LOOP_NORMAL = "abyssal_glare_loop";
        static final String LOOP_GAZE_PRIMARY = "darkened_gaze_loop";

        private GlareLaw() {
        }

        /** The beam length changed this frame: always on the first frame, else when |L - last L|
         *  exceeds the slack (see {@link #LENGTH_RATE}). */
        static boolean lengthChanged(boolean firstFrame, float length, float prevLength, float dt,
                                     float engineTimeMult, float sourceTimeMult) {
            if (firstFrame) {
                return true;
            }
            float slack = LENGTH_RATE / Math.max(MIN_ENGINE_TIME_MULT, engineTimeMult) * dt
                    + LENGTH_SLACK * Math.max(1f, sourceTimeMult);
            return Math.abs(length - prevLength) > slack;
        }

        /** A forced rift (primary only): a damage target appeared this frame, or the length settled
         *  this frame (changed last frame, not now). A secondary weapon never forces. */
        static boolean forcedRift(boolean primary, boolean hasTarget, boolean hadTargetPrev,
                                  boolean lengthChanged, boolean lengthChangedPrev) {
            return primary && ((hasTarget && !hadTargetPrev) || (lengthChangedPrev && !lengthChanged));
        }

        /** A trigger frame does nothing more: no damage target and L below 0.9 x range, or
         *  brightness below 1. */
        static boolean triggerCutShort(boolean hasTarget, float length, float range, float brightness) {
            return (!hasTarget && length < IDLE_RANGE_FRACTION * range) || brightness < 1f;
        }

        /** A trigger draws a rift: the draw above 0.8, or forced, or (primary and the since-rift clock
         *  past 1 s in GAZE / 0.5 s in NORMAL). */
        static boolean riftOnTrigger(float draw, boolean forced, boolean primary, boolean gaze,
                                     float sinceRift) {
            return draw > RIFT_DRAW_ABOVE || forced
                    || (primary && sinceRift > (gaze ? RIFT_CLOCK_GAZE : RIFT_CLOCK_NORMAL));
        }

        /** Rift size: NORMAL 7.5; GAZE 15 + 5 f, f = 0 up to L = 500, linear to 1 at L = 2000, 1 past it. */
        static float riftSize(boolean gaze, float length) {
            if (!gaze) {
                return SIZE_NORMAL;
            }
            float f = (length - GAZE_RAMP_FROM) / (GAZE_RAMP_TO - GAZE_RAMP_FROM);
            return SIZE_GAZE_BASE + SIZE_GAZE_SPAN * Math.max(0f, Math.min(1f, f));
        }

        /** A trigger draws an arc: L above 100 and (the fair coin, or NORMAL with a rift this trigger). */
        static boolean arcOnTrigger(float length, boolean coin, boolean gaze, boolean rift) {
            return length > ARC_MIN_LENGTH && (coin || (!gaze && rift));
        }

        /** The arc's bright-spot full and fade fraction: min(0.33, 300 / L) (0.33 at L = 0). */
        static float brightSpotFraction(float length) {
            return Math.min(BRIGHT_SPOT_MAX, BRIGHT_SPOT_LENGTH / length);
        }

        /** The arc's travel time (s): max(0.05, L / speed); 0.05 for a non-positive speed. */
        static float arcTravelTime(float length, float speed) {
            return speed > 0f ? Math.max(ARC_MIN_TRAVEL, length / speed) : ARC_MIN_TRAVEL;
        }

        /** The loop sound id: NORMAL the glare loop; GAZE primary the gaze loop; GAZE secondary none. */
        static String loopSound(boolean gaze, boolean primary) {
            if (!gaze) {
                return LOOP_NORMAL;
            }
            return primary ? LOOP_GAZE_PRIMARY : null;
        }
    }
}
