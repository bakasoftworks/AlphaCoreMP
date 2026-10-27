package cc.starsector.client;

import java.awt.Color;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EmpArcEntityAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import com.fs.starfarer.api.impl.combat.NegativeExplosionVisual;
import com.fs.starfarer.api.impl.combat.RiftCascadeMineExplosion;
import com.fs.starfarer.api.impl.combat.dweller.DwellerShroud;
import com.fs.starfarer.api.impl.combat.dweller.RiftLightningEffect;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.util.vector.Vector2f;

/**
 * treated-replica wrapper over the Shrouded
 * {@link RiftLightningEffect} ({@code rift_lightning.wpn} everyFrameEffect AND, since the
 * ON_HIT spec swap, {@code rift_lightning_shot.proj} onHitEffect — the projectile
 * dispatches {@code onHit} from ITS spec, on a FRESH instance).
 * Non-replica bolts ride the parent's own code unchanged. On a replica bolt the vanilla shape is
 * presented with its ONE sim door removed: vanilla's static {@code fireArc} draws a damage-free
 * visual arc (thickness 80, core 40, red fringe, single-flicker, plus the shroud-anchored crackle)
 * AND {@code spawnMine("rift_lightning_minelayer")} — the mine is the area EMP damage
 * (server-authoritative, arrives as the wire's disabled bits) and would be an unseeded local damage
 * author, so it is the door shut here; the arc and the mine's rift bloom are drawn by this class's
 * own code.
 * {@code advance}, {@code onHit}, {@link #fireArcVisual}
 * and {@link #spawnRiftBloom} were rewritten from the functional spec;
 * their pure laws live in {@link RiftArcLaw} (game-free rows:
 * {@code ApiRwD3Rows}). Trigger = the wire-presented bolt: {@code onFire} via
 * {@link ReplicaPresentedFireRelay} (the presenter's spawn never runs it), then {@code onHit} on
 * a local hit or the range-expiry sweep in {@code advance} — vanilla's own two doors.
 *
 * <p><b>Per-BOLT latch:</b> replica-ness is decided
 * once in {@code onFire} and written onto the bolt ({@link ReplicaEffectLatchLaw#BOLT_KEY});
 * {@code advance} and {@code onHit} read the bolt's latch, never the ship's treatment ledger — a
 * treatment flip (reap / command-transfer adopt / neutralize) while a relayed bolt sits in
 * {@code fired} can no longer route it through vanilla's mine-minting {@code fireArc}.
 */
public class CcWrapRiftLightningEffect extends RiftLightningEffect
        implements ReplicaPresentedFireRelay.PresentedFireAware {

    static final String TAG = "riftlightning";

    @Override
    public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        super.onFire(projectile, weapon, engine); // vanilla's damage remover + SOURCE_WEAPON + fired list
        if (ReplicaPresentedFireRelay.latchAtFire(projectile)) {
            CcReplicaEffectGate.replicaFrame(TAG);
        }
    }

    /**
     * The per-frame range sweep, rewritten from the functional
     * spec (spec S14 call shape (a)): a PRE-PASS, then the parent. Unless
     * the engine is in fast-time advance, every bolt in the inherited {@code fired} list that was born
     * a replica ({@link ReplicaPresentedFireRelay#latched}), has travelled STRICTLY farther than the
     * weapon's range from its spawn point and lacks {@code RIFT_LIGHTNING_FIRED_TAG}
     * ({@link RiftArcLaw#presentOnSweep}) gets {@link #fireArcVisual}, which tags it first. The
     * parent's {@code advance} then runs in the same call: a bolt the pre-pass tagged is one its sweep
     * passes over, and own-ship (unlatched) bolts get the parent's own path. A null engine, a paused
     * engine or a dead ship never skips the pre-pass; a null weapon does (there is no range to compare).
     */
    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        boolean fastTime = engine != null && engine.isInFastTimeAdvance();
        if (!fastTime && weapon != null && fired != null) {
            float range = weapon.getRange();
            for (FiredLightningProjectile entry : fired) {
                DamagingProjectileAPI bolt = entry == null ? null : entry.projectile;
                if (bolt == null) {
                    continue;
                }
                float distance = Misc.getDistance(bolt.getSpawnLocation(), bolt.getLocation());
                if (RiftArcLaw.presentOnSweep(distance, range, isFiredTagged(bolt),
                        ReplicaPresentedFireRelay.latched(bolt))) {
                    fireArcVisual(bolt, weapon, null, null);
                }
            }
        }
        super.advance(amount, engine, weapon);
    }

    /**
     * The hit door, rewritten from the functional spec.
     * A bolt that is not a replica
     * ({@link ReplicaPresentedFireRelay#onHitReplica} false) is handed to the parent's {@code onHit}
     * unchanged. A replica bolt: its weapon is the bolt's {@code RIFT_LIGHTNING_SOURCE_WEAPON} datum
     * (written by the parent's {@code onFire}, which {@link #onFire} runs); no weapon there ⇒ nothing;
     * else {@link #fireArcVisual} at the hit point and target.
     */
    @Override
    public void onHit(DamagingProjectileAPI projectile, CombatEntityAPI target, Vector2f point, boolean shieldHit,
                      ApplyDamageResultAPI damageResult, CombatEngineAPI engine) {
        if (!ReplicaPresentedFireRelay.onHitReplica(projectile)) {
            super.onHit(projectile, target, point, shieldHit, damageResult, engine);
            return;
        }
        Map<String, Object> data = projectile == null ? null : projectile.getCustomData();
        Object source = data == null ? null : data.get(RIFT_LIGHTNING_SOURCE_WEAPON);
        if (source instanceof WeaponAPI) {
            fireArcVisual(projectile, (WeaponAPI) source, point, target);
        }
    }

    /**
     * One presented Rift Lightning arc, visual only, rewritten
     * from the functional spec; vanilla's static
     * {@code fireArc} minus its damage door, the mine. Once per bolt: a bolt already carrying
     * {@code RIFT_LIGHTNING_FIRED_TAG} (the key shared with the parent, so no path arcs it twice) gets
     * nothing; otherwise the tag is set FIRST — before every other check, outside any {@code try} —
     * and then no firing ship, no weapon or no combat engine ⇒ nothing more. Else, with the end point,
     * origin and travel time {@code T} from {@link RiftArcLaw}:
     * <ul>
     * <li>the main arc: the engine's VISUAL arc (no damage arguments), origin end anchored to the ship,
     *     far end free (no anchor); thickness 80, core width 40, fringe {@code RIFT_LIGHTNING_COLOR},
     *     white core, segment length x8, zig-zag reduction 0.15, fade-out distance 50, minimum fade-out
     *     x10, flicker x0.3, travel time {@code T}; no glow at the start, starts faded out, single
     *     flicker;</li>
     * <li>the bloom, the mine's visual half: the mine itself is never spawned (counted
     *     {@code doorShut}); {@link #spawnRiftBloomLater} at the end point after {@code 0.8 T};</li>
     * <li>with a dweller shroud only, the crackle: a second visual arc from the origin, both ends
     *     anchored to the ship, to {@link RiftArcLaw#crackleEnd}; the shroud's overload-arc thickness
     *     and core thickness, fringe {@code RIFT_LIGHTNING_COLOR}, white core, segment length x4, glow
     *     size x4, flicker {@link RiftArcLaw#crackleFlicker}; not single flicker;</li>
     * </ul>
     * then {@code visualMinted}. {@code point} = the hit point, null on a range-expiry miss;
     * {@code target} keeps the parent's call shape and is not read (the far end is free).
     */
    static void fireArcVisual(DamagingProjectileAPI projectile, WeaponAPI weapon, Vector2f point, CombatEntityAPI target) {
        if (projectile == null || isFiredTagged(projectile)) {
            return;
        }
        projectile.setCustomData(RIFT_LIGHTNING_FIRED_TAG, Boolean.TRUE);
        ShipAPI ship = projectile.getSource();
        CombatEngineAPI engine = Global.getCombatEngine();
        if (ship == null || weapon == null || engine == null) {
            return;
        }
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Vector2f spawn = projectile.getSpawnLocation();
        float[] e = RiftArcLaw.endPoint(spawn.x, spawn.y, projectile.getFacing(), point != null,
                point == null ? 0f : point.x, point == null ? 0f : point.y, weapon.getRange(),
                projectile.didDamage(), rng.nextFloat());
        Vector2f end = new Vector2f(e[0], e[1]);
        float travel = RiftArcLaw.travelTime(e[2], RIFT_LIGHTNING_SPEED);

        Vector2f centre = ship.getLocation();
        DwellerShroud shroud = DwellerShroud.getShroudFor(ship);
        DwellerShroud.DwellerShroudParams shroudParams = shroud == null ? null : shroud.getShroudParams();
        Vector2f origin = new Vector2f(spawn);
        if (shroudParams != null) {
            float[] o = RiftArcLaw.shroudOrigin(centre.x, centre.y, end.x, end.y, shroudParams.maxOffset,
                    rng.nextFloat(), rng.nextFloat());
            origin.set(o[0], o[1]);
        }

        EmpArcEntityAPI.EmpArcParams look = new EmpArcEntityAPI.EmpArcParams();
        look.fadeOutDist = 50f;
        look.flickerRateMult = 0.3f;
        look.minFadeOutMult = 10f;
        look.movementDurOverride = travel;
        look.segmentLengthMult = 8f;
        look.zigZagReductionFactor = 0.15f;
        EmpArcEntityAPI arc = engine.spawnEmpArcVisual(new Vector2f(origin), ship, new Vector2f(end), null,
                80f, RIFT_LIGHTNING_COLOR, Color.white, look);
        if (arc != null) {
            arc.setCoreWidthOverride(40f);
            arc.setRenderGlowAtStart(false);
            arc.setFadedOutAtStart(true);
            arc.setSingleFlickerMode(true);
        }

        CcReplicaEffectGate.doorShut(TAG);
        spawnRiftBloomLater(engine, end, RiftArcLaw.bloomDelay(travel));

        if (shroudParams != null) {
            float[] c = RiftArcLaw.crackleEnd(origin.x, origin.y, centre.x, centre.y, shroudParams.maxOffset,
                    rng.nextFloat(), rng.nextFloat());
            EmpArcEntityAPI.EmpArcParams crackleLook = new EmpArcEntityAPI.EmpArcParams();
            crackleLook.flickerRateMult = RiftArcLaw.crackleFlicker(rng.nextFloat());
            crackleLook.glowSizeMult = 4f;
            crackleLook.segmentLengthMult = 4f;
            EmpArcEntityAPI crackle = engine.spawnEmpArcVisual(new Vector2f(origin), ship, new Vector2f(c[0], c[1]),
                    ship, shroudParams.overloadArcThickness, RIFT_LIGHTNING_COLOR, Color.white, crackleLook);
            if (crackle != null) {
                crackle.setCoreWidthOverride(shroudParams.overloadArcCoreThickness);
            }
        }
        CcReplicaEffectGate.visualMinted(TAG);
    }

    /** Does the bolt carry {@code RIFT_LIGHTNING_FIRED_TAG}? A
     *  non-null value under that key counts; an absent key or a null value reads UNtagged — the wider
     *  reading, so the pre-pass in {@link #advance} is never narrower than the sweep it guards. */
    private static boolean isFiredTagged(DamagingProjectileAPI bolt) {
        Map<String, Object> data = bolt.getCustomData();
        return data != null && data.get(RIFT_LIGHTNING_FIRED_TAG) != null;
    }

    /**
     * Why the Maelstrom seemed not to fire its rift lightning:
     * the mine the door above shuts
     * is ALSO the weapon's recognizable half — its explosion visual
     * ({@code RiftLightningMineExplosion.onExplosion} → {@code RiftCascadeMineExplosion
     * .spawnStandardRift}, a pure {@code NegativeExplosionVisual} layered plugin with no
     * damage author) is the dark "rift" that opens at the bolt's terminus; without it the arc alone
     * is one flicker lost in the red shroud. Minted here damage-free at the same point and delay
     * vanilla gives the mine (80 % of the arc's travel time; the mine explodes on arming) via a
     * one-shot layered plugin that renders nothing and expires after the wait. The look is
     * {@link #riftBloomParams}; {@link #spawnRiftBloom} draws it at the point with no projectile
     * anchor (both written from the functional spec).
     */
    static void spawnRiftBloomLater(final CombatEngineAPI engine, Vector2f point, float delay) {
        final Vector2f at = new Vector2f(point);
        final float wait = Math.max(0f, delay);
        engine.addLayeredRenderingPlugin(new BaseCombatLayeredRenderingPlugin() {
            float left = wait;
            boolean done;

            @Override
            public void advance(float amount) {
                if (done) {
                    return;
                }
                left -= amount;
                if (left <= 0f) {
                    done = true;
                    try {
                        spawnRiftBloom(engine, at);
                    } catch (Throwable ignore) {
                        // presentation only — the arc already landed
                    }
                }
            }

            @Override
            public boolean isExpired() {
                return done;
            }
        });
    }

    /** The rift-lightning mine's bloom look (spec S14 (b) game facts): the engine's standard rift params
     *  with border colour (255, 75, 75, 255) at radius 20, then fade-out 1, hit-glow size x0.6,
     *  thickness 50 (a fresh, deterministic object each call). */
    private static NegativeExplosionVisual.NEParams riftBloomParams() {
        NegativeExplosionVisual.NEParams p =
                RiftCascadeMineExplosion.createStandardRiftParams(new Color(255, 75, 75, 255), 20.0F);
        p.fadeOut = 1.0F;
        p.hitGlowSizeMult = 0.6F;
        p.thickness = 50.0F;
        return p;
    }

    /** The mine's rift bloom at {@code at}, visual only, rewritten
     *  from the functional spec (spec S14 (b) + SR): {@link RiftChainFx}
     *  draws the {@link #riftBloomParams} look about the point as layered rendering plugins — no
     *  explosion entity, no mine, no projectile anchor. A null engine or point draws nothing. */
    static void spawnRiftBloom(CombatEngineAPI engine, Vector2f at) {
        RiftChainFx.spawn(engine, riftBloomParams(), at);
    }

    /**
     * Spec S14's pure laws: no engine type in a signature, so
     * the game-free rows ({@code ApiRwD3Rows}) drive them (this nested class loads without its outer
     * effect class). Units: positions world su (either sign); distances, ranges and offsets su
     * (>= 0 in play); facing degrees (0 = +x, counter-clockwise, any value); times s; speeds su/s
     * (> 0 in play); every {@code draw} uniform [0, 1). Points come back as fresh arrays.
     */
    static final class RiftArcLaw {
        /** A miss ends at range x uniform [MISS_MIN_FRACTION, 1). */
        static final float MISS_MIN_FRACTION = 0.5f;
        /** The arc's travel-time floor, s. */
        static final float MIN_TRAVEL = 0.05f;
        /** The bloom waits this fraction of the arc's travel time. */
        static final float BLOOM_DELAY_FRACTION = 0.8f;
        /** Shroud origin: ship centre + uniform [MIN, MAX) x the shroud's max offset, turned up to
         *  +-ORIGIN_TURN_DEGREES off the centre-to-end-point bearing. */
        static final float ORIGIN_MIN_FRACTION = 0.5f;
        static final float ORIGIN_MAX_FRACTION = 0.75f;
        static final float ORIGIN_TURN_DEGREES = 90f;
        /** Crackle: length = CRACKLE_LENGTH_FRACTION x uniform [CRACKLE_MIN_FRACTION, 1) x the max
         *  offset, turned up to +-CRACKLE_TURN_DEGREES off the origin-to-centre bearing. */
        static final float CRACKLE_LENGTH_FRACTION = 0.5f;
        static final float CRACKLE_MIN_FRACTION = 0.5f;
        static final float CRACKLE_TURN_DEGREES = 45f;
        static final float CRACKLE_FLICKER_MIN = 0.75f;
        static final float CRACKLE_FLICKER_MAX = 1.5f;

        private RiftArcLaw() {
        }

        /** The pre-pass predicate: a replica-latched, untagged bolt STRICTLY past the weapon's range
         *  from its spawn point is presented (at exactly the range it is not). */
        static boolean presentOnSweep(float distance, float range, boolean tagged, boolean latched) {
            return latched && !tagged && distance > range;
        }

        /**
         * The end-point law: a hit point within the range of the spawn point (distance <= range) is
         * the end, D = that distance; otherwise the point along the facing from the spawn point at
         * D = the range when the bolt already did damage, else range x uniform [0.5, 1).
         * Returns {x, y, D}.
         */
        static float[] endPoint(float spawnX, float spawnY, float facingDegrees, boolean hasHit, float hitX,
                                float hitY, float range, boolean didDamage, float draw) {
            if (hasHit) {
                float hitDistance = (float) Math.hypot(hitX - spawnX, hitY - spawnY);
                if (hitDistance <= range) {
                    return new float[] {hitX, hitY, hitDistance};
                }
            }
            float d = didDamage ? range : range * (MISS_MIN_FRACTION + (1f - MISS_MIN_FRACTION) * draw);
            double facing = Math.toRadians(facingDegrees);
            return new float[] {spawnX + (float) (Math.cos(facing) * d), spawnY + (float) (Math.sin(facing) * d), d};
        }

        /** The travel-time law, s: max(0.05, D / speed); 0.05 for a non-positive speed. */
        static float travelTime(float distance, float speed) {
            return speed > 0f ? Math.max(MIN_TRAVEL, distance / speed) : MIN_TRAVEL;
        }

        /** The bloom's wait, s: 0.8 x the arc's travel time. */
        static float bloomDelay(float travel) {
            return BLOOM_DELAY_FRACTION * travel;
        }

        /** The shroud-origin band (see the constants). Returns {x, y}. */
        static float[] shroudOrigin(float centreX, float centreY, float endX, float endY, float maxOffset,
                                    float radiusDraw, float turnDraw) {
            float distance = maxOffset
                    * (ORIGIN_MIN_FRACTION + (ORIGIN_MAX_FRACTION - ORIGIN_MIN_FRACTION) * radiusDraw);
            double bearing = Math.atan2(endY - centreY, endX - centreX)
                    + Math.toRadians(ORIGIN_TURN_DEGREES * (2f * turnDraw - 1f));
            return new float[] {centreX + (float) (Math.cos(bearing) * distance),
                    centreY + (float) (Math.sin(bearing) * distance)};
        }

        /** The crackle's far end point, from the origin (see the constants). Returns {x, y}. */
        static float[] crackleEnd(float originX, float originY, float centreX, float centreY, float maxOffset,
                                  float lengthDraw, float turnDraw) {
            float length = CRACKLE_LENGTH_FRACTION * maxOffset
                    * (CRACKLE_MIN_FRACTION + (1f - CRACKLE_MIN_FRACTION) * lengthDraw);
            double bearing = Math.atan2(centreY - originY, centreX - originX)
                    + Math.toRadians(CRACKLE_TURN_DEGREES * (2f * turnDraw - 1f));
            return new float[] {originX + (float) (Math.cos(bearing) * length),
                    originY + (float) (Math.sin(bearing) * length)};
        }

        /** The crackle's flicker multiple: 0.75 + 0.75 x the draw (uniform over 0.75 .. 1.5). */
        static float crackleFlicker(float draw) {
            return CRACKLE_FLICKER_MIN + (CRACKLE_FLICKER_MAX - CRACKLE_FLICKER_MIN) * draw;
        }
    }
}
