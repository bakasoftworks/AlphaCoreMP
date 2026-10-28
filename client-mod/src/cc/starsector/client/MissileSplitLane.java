package cc.starsector.client;

import cc.starsector.client.net.ServerMissileState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.loading.MissileSpecAPI;
import com.fs.starfarer.combat.CombatEngine;
import com.fs.starfarer.combat.ai.ProximityFuseAI;
import com.fs.starfarer.combat.entities.BallisticProjectile;
import com.fs.starfarer.combat.entities.Missile;
import com.fs.starfarer.combat.entities.MovingRay;
import com.fs.starfarer.combat.entities.PlasmaShot;
import com.fs.starfarer.combat.entities.Ship;
import com.fs.starfarer.combat.systems.o00O;
import com.fs.starfarer.loading.Q;
import com.fs.starfarer.loading.specs.F;
import com.fs.starfarer.loading.specs.oO0o;
import com.fs.starfarer.renderers.damage.DebrisParticleSystem;
import org.json.JSONObject;
import org.lwjgl.util.vector.Vector2f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The MISSILE-SPLIT / BALLISTIC-CHILDREN PRESENTATION lane, extracted COLD from
 * {@link MissilePoseLane}.
 * The carve
 * has the same shape as the PAID
 * {@link OwnMissileHandoffLane} split: backref coupling to the owning pose lane,
 * which keeps the shared {@link MissilePoseLane.Twin} ledger, the iteration passes and thin
 * relays.
 *
 * <p>CONCERN: everything downstream of a {@code MISSILE_SPLIT} edge event — the vanilla split
 * MOMENT (sound / smoke / debris, MirvAI.checkSplit), the ballistic-children COMPLETION
 * (spec classification through the engine's own store, vanilla's spread math, the vanilla spawn
 * dispatcher and its 7-arg approx fallback), the guided-child recipe STASH and its replay onto
 * wire-adopted children, and the whole cause partition that attributes every
 * declined child mint. The reflective seam these need ({@link #SMOKE_BURST}, {@link #SPEC_STORE},
 * {@link #SPLIT_FACTORY}, the debris size enum triple) is owned here too — nothing outside this
 * concern touched it.
 *
 * <p>WHAT STAYED IN THE POSE LANE: the Twin ledger and its identity maps, enroll/link/adopt,
 * presence-adopt, absence, the puppet AI installs, the dictionary-defer lane
 * ({@code dictDefer*} — a catalog-arrival concern that only neighboured these counters on the
 * meter line), and {@code ballisticPairs} (an enroll/presence-adopt classification set, read at
 * four sites that all stay). The row's stale "3 collections" anchor was measured at pay time:
 * exactly ONE collection ({@link #childOverrides}) belongs to this concern.
 *
 * <p>COLD means byte-identical behavior: the meter token block is appended at the SAME position
 * in {@code logMeter}'s line, every log line rides the pose lane's SAME capped
 * {@code PredictLane} budget (never a second logger — that would silently change the log
 * economy), and no counter/token text moved.
 */
final class MissileSplitLane {

    /** Caps guard on locally spawned split children (the presented-real pattern):
     *  vanilla families run 5–7; anything past this is pathological data, capped LOUD. */
    private static final int MAX_SPLIT_CHILDREN = 32;
    /** Split child-recipe stash lifetime in server sim ticks (~5 s):
     *  children adopt within a couple of seconds of their split; a stale stash must never
     *  touch a later unrelated same-pair missile. */
    private static final int CHILD_OVERRIDE_TICKS = 300;

    /** The vanilla split-moment engine seams, resolved once, each
     *  fail-soft null (a resolve failure degrades that piece only — sound+debris without
     *  smoke, or children treated guided — never the lane). {@link #SMOKE_BURST} = the FX
     *  holder's smoke-burst overload {@code (smokeSpec, loc, facingDeg, vel)void} — the holder
     *  class is literally named {@code class} ⇒
     *  {@code Class.forName}; the spec itself builds via the PUBLIC static
     *  factory from its JSON object. {@link #SPEC_STORE} = the engine's projectile-spec
     *  store get {@code (String)→Object} — the SAME resolver the
     *  7-arg {@code spawnProjectile} uses, here consulted only to
     *  CLASSIFY the split child spec (missile ⇒ guided ⇒ wire-presented, never local).
     *  {@link #DEBRIS_SMALL}/MEDIUM/LARGE = the debris size enum constants.
     *  <b>The C7 root cause:</b> a name-mangled enum's
     *  FIELD identifier and its {@code Enum(String,int)} NAME string are DIFFERENT strings,
     *  and {@code valueOf} matches the NAME. The constant pool of
     *  the debris size enum carries mangled field identifiers
     *  but ctor names {@code "SMALL"}/{@code "MEDIUM"}/{@code "LARGE"} — the shipped build
     *  renamed the identifiers and left the name strings ORIGINAL. All three prior
     *  {@code valueOf} args were the field identifiers ⇒ all three threw. Use the plain names.
     *  {@link #SPLIT_FACTORY} = <b>vanilla's own spawn
     *  dispatcher</b> {@code (Ship, weapon, Object spec, loc, angleDeg, vel)→Object}
     * — the EXACT method {@code MirvAI.checkSplit} calls, where
     *  {@code projSpec} is declared {@code Object}. It was rebound off the narrower
     *  {@code (…, F, …)→BallisticProjectile} overload, which was wrong two ways:
     *  it force-builds a {@code BallisticProjectile} for ANY {@code F} — so a BEAM /
     *  BALLISTIC_AS_BEAM / PLASMA spawn-type child got the WRONG entity class — and it is
     *  unreachable for a non-{@code F} spec that vanilla handles.
     *  <b>That overload's bytecode carries ONE {@code areturn} (offset 134) and
     *  ZERO {@code aconst_null}: it CANNOT return null.</b> So the reading of
     *  {@code skip_nochild} as "the factory minted null" rested on an impossible premise — the
     *  factory was never successfully INVOKED (gate false, clone threw, or it threw). The
     *  dispatcher, by contrast, CAN legitimately return null — for an {@code F}
     *  whose {@code getSpawnType()} is MISSILE/OTHER, or a spec class outside
     *  {@code F}, {@code DamagingExplosionSpec} and one renamed spec class — and that null now has its own token.
     *  A reserved-word METHOD name (a real renamed identifier — method/field
     *  reflection DOES take the renamed string; only enum {@code valueOf} does not),
     *  reachable only via {@code findStatic} by string. <b>Resolved FIRST and in its OWN
     *  try</b> — the pixel-minting piece never sits downstream of an FX-only resolve
     * (the old "resolved LAST so its failure nulls itself alone" reasoning was
     *  inverted — being last protects the OTHERS from IT, not IT from THEM, and that
     *  inversion is what nulled the factory for a whole watch).
     *  {@link #DAMAGE_CTOR} + {@link #SPEC_SET_DAMAGE} (<b>the C7 FIX</b>) =
     *  vanilla {@code MirvAI}'s {@code setDamage(new com.fs.starfarer.combat.E.F(1f, false,
     *  ENERGY))}, which an earlier read took as cosmetic neutralization and declined; it was proved from
     *  the live throwable that it is a <b>required precondition of the factory call</b>: the
     *  {@code BallisticProjectile} ctor unconditionally does {@code spec.getDamage().clone()},
     *  and a MIRV child store spec is damage-less by design ⇒ a clone without
     *  the placeholder NPEs on EVERY child (35/35, both windows).
     *  <b>Handles, not {@code new}:</b> {@code com.fs.starfarer.combat.E} is a class AND a
     *  package, Java's name reclassification picks the TYPE, so vanilla's own line does not
     *  compile verbatim (proved against the real build javac); the setter's parameter
     *  type is unavoidably that engine class (no {@code DamageAPI} overload exists in the class's
     *  signatures). Resolved in their OWN try placed AFTER the factory's, so the ordering
     *  law above is untouched. Both null ⇒ the {@code damage_handles_unresolved} gate arm: a
     *  NAMED decline to the approx lane, never a silent repeat of the 35/35 NPE.
     *  {@code java.lang.invoke} = the loader-B legal lane (java.lang.reflect is B-denied). */
    private static final java.lang.invoke.MethodHandle SMOKE_BURST;
    private static final java.lang.invoke.MethodHandle SPEC_STORE;
    private static final java.lang.invoke.MethodHandle SPLIT_FACTORY;
    private static final java.lang.invoke.MethodHandle DAMAGE_CTOR;
    private static final java.lang.invoke.MethodHandle SPEC_SET_DAMAGE;
    private static final DebrisParticleSystem.o DEBRIS_SMALL;
    private static final DebrisParticleSystem.o DEBRIS_MEDIUM;
    private static final DebrisParticleSystem.o DEBRIS_LARGE;
    /** Per-piece resolve failures, tagged and joined; null iff every piece resolved.
     * It was once a single {@code Throwable} for the whole try, which reported only the
     *  FIRST failure and hid that the pieces after it never even ran. */
    private static final String SPLIT_HANDLES_FAILURE;

    static {
        java.lang.invoke.MethodHandle smoke = null;
        java.lang.invoke.MethodHandle store = null;
        java.lang.invoke.MethodHandle factory = null;
        java.lang.invoke.MethodHandle dmgCtor = null;
        java.lang.invoke.MethodHandle setDamage = null;
        DebrisParticleSystem.o small = null;
        DebrisParticleSystem.o medium = null;
        DebrisParticleSystem.o large = null;
        StringBuilder failures = new StringBuilder();
        java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.lookup();
        // ORDER IS LOAD-BEARING: the factory is the only piece that mints pixels, so
        // it resolves FIRST and ALONE — no FX-only lookup may ever precede and abort it.
        try {
            // Vanilla's OWN dispatcher, the exact descriptor MirvAI.checkSplit calls —
            // NOT the F/BALLISTIC-only overload, which cannot return null and so
            // cannot have been the source of skip_nochild. See the field doc.
            factory = lookup.findStatic(com.fs.starfarer.loading.specs.a.class, "super",
                    java.lang.invoke.MethodType.methodType(Object.class,
                            Ship.class, o00O.class, Object.class,
                            Vector2f.class, float.class, Vector2f.class));
        } catch (Throwable t) {
            failures.append("[SPLIT_FACTORY] ").append(t).append(' ');
        }
        try {
            // The C7 FIX: vanilla MirvAI's damage placeholder — the factory ctor
            // clones spec.getDamage() unconditionally, so this is a PRECONDITION of the spawn,
            // not cosmetics. Placed AFTER the factory try: the ordering law above still holds
            // (the pixel-minting piece resolves FIRST and ALONE). The type is reachable only by
            // string — `com.fs.starfarer.combat.E` is a class AND a package.
            Class<?> dmg = Class.forName("com.fs.starfarer.combat.E.F");
            dmgCtor = lookup.findConstructor(dmg, java.lang.invoke.MethodType.methodType(
                    void.class, float.class, boolean.class, DamageType.class));
            setDamage = lookup.findVirtual(F.class, "setDamage",
                    java.lang.invoke.MethodType.methodType(void.class, dmg));
        } catch (Throwable t) {
            // All-or-nothing: the call site needs BOTH, and half a pair is a null-deref waiting.
            dmgCtor = null;
            setDamage = null;
            failures.append("[SPEC_SET_DAMAGE] ").append(t).append(' ');
        }
        try {
            store = lookup.findStatic(Q.class, "Õ" + "00000", // raw UTF-8 U+00D5, JEP 400
                    java.lang.invoke.MethodType.methodType(Object.class, String.class));
        } catch (Throwable t) {
            failures.append("[SPEC_STORE] ").append(t).append(' ');
        }
        try {
            Class<?> fxHolder = Class.forName("com.fs.starfarer.combat.entities.ship.A.class");
            smoke = lookup.findStatic(fxHolder, "o00000", java.lang.invoke.MethodType.methodType(
                    void.class, oO0o.class, Vector2f.class, float.class, Vector2f.class));
        } catch (Throwable t) {
            failures.append("[SMOKE_BURST] ").append(t).append(' ');
        }
        try {
            // Enum NAME strings, not the renamed field identifiers — see the field doc.
            small = DebrisParticleSystem.o.valueOf("SMALL");
            medium = DebrisParticleSystem.o.valueOf("MEDIUM");
            large = DebrisParticleSystem.o.valueOf("LARGE");
        } catch (Throwable t) {
            // All-or-nothing: playSplitMoment gates the debris triple on SMALL alone.
            small = null;
            medium = null;
            large = null;
            failures.append("[DEBRIS_*] ").append(t).append(' ');
        }
        SMOKE_BURST = smoke;
        SPEC_STORE = store;
        SPLIT_FACTORY = factory;
        DAMAGE_CTOR = dmgCtor;
        SPEC_SET_DAMAGE = setDamage;
        DEBRIS_SMALL = small;
        DEBRIS_MEDIUM = medium;
        DEBRIS_LARGE = large;
        SPLIT_HANDLES_FAILURE = failures.length() == 0 ? null : failures.toString().trim();
    }

    /** One-shot flag for the split-handle resolve-failure log. */
    private static boolean splitHandlesFailureLogged;

    /** The engine's projectile-spec store read ({@link #SPEC_STORE}) for the boot-time
     *  onHit sweep's MIRV-child leg ({@code ClientEveryFrameEffectSwap.swapOnHit}): the spec object
     *  every child mint path clones or resolves by id, so a slot rewritten on it reaches them all.
     *  Fail-soft: an unresolved handle or a throwing read returns {@code null} (the caller counts). */
    static Object storeSpec(String specId) {
        if (SPEC_STORE == null || specId == null || specId.isEmpty()) {
            return null;
        }
        try {
            return SPEC_STORE.invoke(specId);
        } catch (Throwable t) {
            return null;
        }
    }

    /** One split's child-recipe stash (per source pair): vanilla MIRV child
     *  specs carry NO damage — MirvAI injects the STAGE-1 behaviorSpec recipe post-spawn
     *  (damage/emp/type/impact, hitpoints × source MissileHealthBonus); a
     *  wire-adopted child spawned by true spec (7-arg factory) misses that injection ⇒ the
     *  wimpy floaties. The split moment stashes the recipe; presence-adopt
     *  re-applies it to bodies wearing the stashed child spec. */
    private static final class ChildOverride {
        final String specId;
        final float damage;
        final float emp;
        final float impact;
        final float hitpoints;
        final DamageType damageType;
        final long expiryTick;

        ChildOverride(String specId, float damage, float emp, float impact, float hitpoints,
                DamageType damageType, long expiryTick) {
            this.specId = specId;
            this.damage = damage;
            this.emp = emp;
            this.impact = impact;
            this.hitpoints = hitpoints;
            this.damageType = damageType;
            this.expiryTick = expiryTick;
        }
    }

    /** The owning pose lane — the shared Twin ledger, its identity maps, the capped
     *  {@code PredictLane} logger, and the plugin backref all live there (the backref
     *  coupling idiom). */
    private final MissilePoseLane pose;

    /** Split child-recipe stashes by source pair (lazy expiry at lookup). */
    private final Map<Long, ChildOverride> childOverrides = new HashMap<Long, ChildOverride>();

    private int splitEvents;         // predict_missile_split_events (MISSILE_SPLIT received)
    private int splitRetired;        // predict_missile_split_retired (linked twin ended at split)
    private int splitOrphan;         // predict_missile_split_orphan (event with no linked twin)
    private int splitFx;             // predict_missile_split_fx (split moments presented)
    private int splitChildren;       // predict_missile_split_children (ballistic children spawned)
    private int splitChildSkips;     // predict_missile_split_child_skips (completion fail-soft skips)
    // The aggregate above could not tell "fixed" from "failed differently", and the
    // per-reason detail lived ONLY in lane.log, which is THROTTLED (45 skips, ZERO
    // `child SKIP:` lines). These four partition splitChildSkips and ride the METER, which is
    // not throttled. Meter counters are the trustworthy surface on this lane.
    private int splitSkipNoSpec;     // :spec store unresolved for the child spec id
    private int splitSkipNoMount;    // :source ship/mount not resolvable to a live local weapon
    private int splitSkipNoChild;    // :no child entity after BOTH lanes (kept as the aggregate;
                                     //  the CAUSE is partitioned across the six counters below)
    private int splitSkipGuidedMint; // :store said non-missile, factory minted a missile (removed)
    private int splitPresentThrew;   // presentMissileSplit's outer catch actually fired
    private int splitSpawnThrew;     // spawnBallisticChildren's own outer catch fired
    private boolean splitPresentThrewLogged; // one-shot log flags for the two swallows above
    private boolean splitSpawnThrewLogged;
    // The SAME defect class as the skip aggregate above, one level up: splitSkipNoChild was an aggregate over
    // SIX distinct silent sites (a four-way eligibility gate with no token, a `catch (Throwable
    // ignore)` on the clone, the factory's own silent catch, the fallback's silent catch, and the
    // two genuine nulls). An earlier read took the aggregate as "the factory minted null" — which the BYTES
    // rule out (the narrow overload has zero aconst_null). These partition it so the cause is attributable.
    private int splitGuided;         // guided (MissileSpecAPI) child -> wire-presented, NOT a skip.
                                     //  THREAD 2: this early return had NO token, which is the
                                     //  whole "62 events, 20 attempts" gap ABOVE the attempt site.
    private int splitAttempts;       // shots that entered the spawn loop (SHOTS, never events —
                                     //  an earlier "42 unreached" count conflated the two units)
    private int splitGateIneligible;  // per split: the factory lane was not eligible at all
    private int splitCloneThrew;      // per split: F.clone()/setMaxRange/setDamage threw
    private int splitFactoryNull;     // per shot: dispatcher returned null (its own fall-through)
    private int splitFactoryThrew;    // per shot: dispatcher THREW (was silently swallowed)
    private int splitFallbackNull;    // per shot: the 7-arg engine.spawnProjectile returned null
    private int splitFallbackThrew;   // per shot: the 7-arg approx THREW (was silently swallowed)
    /** One-shot forensic payload for the FIRST child-mint failure, space-free so it rides the
     *  METER token line. It must NOT ride {@code lane.log}: that is hard-capped per combat
     *  ({@code PredictLane.logMax}) and 62 split lines exhaust it — the lesson (a line that
     *  is emitted is not a line that is captured) applied before it costs another round. */
    private String splitForensic;
    private int childOverrideHit;    // predict_missile_child_override_hit (stashed recipe applied)
    private int childOverrideMiss;   // predict_missile_child_override_miss (stash up, adopt got no
                                     //   wire spec truth — the un-fixed recipe-fallback child lane)

    MissileSplitLane(MissilePoseLane pose) {
        this.pose = pose;
    }

    /**
     * MISSILE_SPLIT presentation (lane-internal —
     * {@code event.entityId()} is the PARENT missile's wire id, resolvable through
     * {@link MissilePoseLane#twinByWireId} only). The server detected the vanilla MIRV split
     * (stage-1 consumed, children born); the client mirrors the vanilla split moment
     * (MirvAI.checkSplit) for the linked twin:
     * <ol>
     * <li>IMMEDIATE retire, skipping the absence grace — vanilla CONSUMES stage-1 at the
     * split ({@code removeObject} first), so any grace-window linger here is exactly
     * the all-ok-but-missile-linger residue this stroke removes;</li>
     * <li>vanilla FX from the twin's own {@code getBehaviorSpecParams()} (the MIRV behavior
     * params ride every stage-1 spec): splitSound unconditional, then
     * viewport-gated smoke burst + debris ×3 at the parent's authoritative split
     * pose + 5 su along raw facing, fail-soft per piece ({@link #SMOKE_BURST}
     * et al.);</li>
     * <li>ballistic-children completion: the child spec ({@code projectileSpec}) classified
     * through the engine's own spec store ({@link #SPEC_STORE}) — instanceof
     * {@link MissileSpecAPI} ⇒ GUIDED: NEVER locally spawned (the children arrive as wire
     * records via {@code presenceAdopt} — the double-present guard); a non-missile child
     * spec (wire-invisible today: shot records carry no stable id/pair) spawns
     * locally by true spec via the SAME 7-arg factory vanilla uses, vanilla spread
     * math + damage overrides, real local damage per the standing presented-real ballistic
     * law, {@link #MAX_SPLIT_CHILDREN} caps guard. Store miss / unreadable params fail-soft
     * = treated guided, skip local spawn, counted.</li>
     * </ol>
     * The husk ({@code emptySpec}) is a real server-side Missile — it arrives on
     * the wire and presence-adopts; never locally spawned. {@code explodeOnSplit} families
     * keep sound+smoke but skip debris (vanilla replaces debris with an EXPLOSION = damage,
     * a server-authority concern; no covered family uses it — accepted visual gap).
     * An unlinked parent (orphan) counts + logs — no local body, no params source.
     */
    void onSplit(cc.starsector.client.net.ClientGameplayEdgeEvent event) {
        if (!MissilePoseLane.MISSILE_POSE_METER && !OwnMissileHandoffLane.OWN_MISSILE_METER) {
            return;
        }
        splitEvents++;
        try {
            long id = event.entityId();
            MissilePoseLane.Twin ownTwin = pose.twinByWireId.get(id);
            if (ownTwin != null && ownTwin.own) {
                pose.own.onSplit(ownTwin, id, event.simTick()); // Release/remove law
                return;
            }
            if (!MissilePoseLane.MISSILE_POSE_METER) {
                return; // own-nudge alone: driven split presentation is the pose lane's
            }
            MissilePoseLane.Twin t = pose.twinByWireId.get(id);
            if (t == null) {
                splitOrphan++;
                pose.lane.log("predict v13 missile-split ORPHAN: id=" + id + " tick="
                        + event.simTick() + " (no linked twin — nothing to present)");
                return;
            }
            if (!MissilePoseLane.MISSILE_POSE_APPLY) {
                pose.lane.log("predict v13 missile-split: id=" + id + " spec=" + t.specId
                        + " tick=" + event.simTick() + " (meter)");
                return;
            }
            if (SPLIT_HANDLES_FAILURE != null && !splitHandlesFailureLogged) {
                splitHandlesFailureLogged = true;
                pose.lane.log("predict v13 missile-split handle resolve FAILED (fail-soft, "
                        + "reduced FX / children treated guided): " + SPLIT_HANDLES_FAILURE);
                // F9 fallback announce: ride beside the capped lane.log/latch.
                FallbackAnnounce.announceOnce("f9-split-handles-failed",
                        "split handle resolve failed -> reduced FX, children treated guided");
            }
            // Read the recipe + pose BEFORE the retire — the body is the params source.
            JSONObject params = null;
            try {
                params = t.missile.getBehaviorSpecParams();
            } catch (Throwable ignore) {
            }
            float facing = t.missile.getFacing();
            Vector2f vel = new Vector2f();
            Vector2f bodyVel = t.missile.getVelocity();
            if (bodyVel != null) {
                vel.set(bodyVel);
            }
            float radius = t.missile.getCollisionRadius();
            // (1) The immediate authoritative end — vanilla consumes stage-1 first.
            pose.retire(t);
            pose.twins.remove(t.missile);
            pose.twinByWireId.remove(id);
            pose.sightings.remove(id);
            splitRetired++;
            pose.lane.log("predict v13 missile-split: id=" + id + " spec=" + t.specId
                    + " tick=" + event.simTick() + " x=" + event.splitX() + " y="
                    + event.splitY() + " -> retired"
                    + (params == null ? " (no behavior params — moment skipped)" : ""));
            if (params == null) {
                return;
            }
            playSplitMoment(params, event, facing, vel, radius);
            spawnBallisticChildren(t, params, event, facing, vel);
        } catch (Throwable th) {
            // Presentation lane — still never breaks the watch, but it is no longer
            // SILENT: a swallowed throw here was one of the two live C7 hypotheses and the
            // old `catch (Throwable ignore)` made it unfalsifiable.
            splitPresentThrew++;
            if (!splitPresentThrewLogged) {
                splitPresentThrewLogged = true;
                pose.lane.log("predict v13 missile-split PRESENT THREW (fail-soft, one-shot): " + th);
            }
        }
    }

    /** The vanilla split FX (MirvAI.checkSplit): splitSound unconditional, smoke
     *  burst + debris ×3 viewport-gated, at the authoritative split pose; the 5-su spawn
     *  offset uses the RAW facing while smoke/debris facing takes
     *  {@code arcOffset}. Every piece fail-soft on its own. */
    private void playSplitMoment(JSONObject params, cc.starsector.client.net.ClientGameplayEdgeEvent event,
            float facing, Vector2f vel, float radius) {
        try {
            splitFx++;
            String splitSound = params.optString("splitSound", "");
            if (splitSound != null && !splitSound.isEmpty()) {
                try {
                    Global.getSoundPlayer().playSound(splitSound, 1f, 1f,
                            new Vector2f(event.splitX(), event.splitY()), vel);
                } catch (Throwable ignore) {
                }
            }
            if (!pose.plugin.engine.getViewport().isNearViewport(
                    new Vector2f(event.splitX(), event.splitY()), radius * 1.5f + 100f)) {
                return; // the vanilla viewport gate
            }
            Vector2f fxLoc = splitOffsetLoc(event, facing);
            float fxFacing = facing + (float) params.optDouble("arcOffset", 0.0);
            boolean explode = params.optBoolean("explodeOnSplit", false)
                    || params.optBoolean("explodeAsIfFromDamageOnSplit", false);
            Vector2f smokeVel = explode ? new Vector2f() : vel; //
            if (SMOKE_BURST != null) {
                try {
                    oO0o smokeSpec = oO0o.o00000(params.getJSONObject("smokeSpec"));
                    SMOKE_BURST.invoke(smokeSpec, fxLoc, fxFacing, smokeVel);
                } catch (Throwable ignore) {
                    // fail-soft: skip smoke, keep sound+debris
                }
            }
            if (!explode && DEBRIS_SMALL != null) {
                DebrisParticleSystem debris = CombatEngine.getInstance().getDebrisSystem();
                debris.spawnDebris(fxLoc, vel, DEBRIS_SMALL,
                        params.optInt("debrisSmall", 11), fxFacing, 360f, 0f, 25f, 720f);
                debris.spawnDebris(fxLoc, vel, DEBRIS_MEDIUM,
                        params.optInt("debrisMedium", 5), fxFacing, 360f, 0f, 25f, 720f);
                debris.spawnDebris(fxLoc, vel, DEBRIS_LARGE,
                        params.optInt("debrisLarge", 3), fxFacing, 360f, 0f, 25f, 720f);
            }
        } catch (Throwable ignore) {
            // presentation lane; never break the watch
        }
    }

    /** Meter tokens are {@code key=value} on ONE space-separated line, so a value that can
     *  carry whitespace (a spec id, an exception message) must be flattened or it silently
     *  becomes several bogus tokens. Null/blank collapses to a literal so the field never
     *  disappears from the token line. */
    private static String noSpaces(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "-";
        }
        return raw.replaceAll("\\s+", "_");
    }

    /** The vanilla 5-su spawn/FX offset along the parent's RAW facing. */
    private static Vector2f splitOffsetLoc(cc.starsector.client.net.ClientGameplayEdgeEvent event,
            float rawFacingDeg) {
        double rad = Math.toRadians(rawFacingDeg);
        return new Vector2f(event.splitX() + (float) Math.cos(rad) * 5f,
                event.splitY() + (float) Math.sin(rad) * 5f);
    }

    /** Ballistic-children completion, the F lane exact:
     *  classify the child spec through the engine's own store — a
     *  MISSILE child spec is GUIDED and arrives as wire records (presence-adopt), NEVER
     *  locally spawned; a non-missile child spec is wire-invisible (shot records carry no
     *  stable id/pair) so the split spawns its {@code numShots} children
     *  locally, vanilla spread math + damage overrides, real local
     *  damage per the standing presented-real ballistic law. An {@code F} (projectile)
     *  child spec replicates vanilla EXACTLY: OUR private {@code F.clone()} per split
     *  (vanilla's own mechanism — the SHARED store spec is never mutated,
     *  engine-faithful), per-shot {@code setMoveSpeed(spreadSpeed+rand·range)} +
     *  {@code setMaxRange(projectileRange)}, spawned through the SAME factory
     *  vanilla calls ({@link #SPLIT_FACTORY}) with the RAW parent velocity
     *  — child velocity = parentVel + moveSpeed·dir(ang), added inside the factory.
     *  Vanilla's clone damage placeholder <b>IS mirrored</b> — it is
     *  a REQUIRED PRECONDITION of that factory call, not the cosmetic delta an earlier read declined:
     *  child store specs are damage-less and the ctor clones {@code spec.getDamage()}, so
     *  skipping it NPEd 35/35 children in one test watch. Presented damage
     *  is unaffected — real child damage rides {@link #applyChildOverrides} either way. Fail-soft per
     *  piece to the 7-arg approx lane (spec-native speed, no range override). Any resolve
     *  failure = treated guided, counted — never a guessed spawn. */
    private void spawnBallisticChildren(MissilePoseLane.Twin t, JSONObject params,
            cc.starsector.client.net.ClientGameplayEdgeEvent event, float facing, Vector2f vel) {
        try {
            String childSpecId = params.optString("projectileSpec", "");
            Object childSpec = null;
            if (SPEC_STORE != null && childSpecId != null && !childSpecId.isEmpty()) {
                childSpec = SPEC_STORE.invoke(childSpecId);
            }
            if (childSpec == null) {
                splitChildSkips++;
                splitSkipNoSpec++;
                pose.lane.log("predict v13 missile-split child SKIP: spec store unresolved for '"
                        + childSpecId + "' -> treated guided (wire-presented)");
                return;
            }
            if (childSpec instanceof MissileSpecAPI) {
                // Guided children present via wire records (the double-present guard) — but a
                // true-spec adopt misses vanilla's post-spawn damage injection (child specs
                // are damage-less): stash this split's stage-1 recipe for the
                // pair so presenceAdopt applies it.
                // This return is CORRECT but was UNTOKENED — it is the entire
                // "62 events but only 20 attempts" gap, and it sat ABOVE the partition.
                splitGuided++;
                stashChildOverride(t, params, childSpecId, event.simTick());
                return;
            }
            ShipAPI ship = pose.plugin.registryShape
                    ? pose.registryShip(t.sourceEntityId) : pose.plugin.foeShip;
            List<WeaponAPI> weapons = ship != null && ship.isAlive() ? ship.getAllWeapons() : null;
            int idx = t.sourceMountIndex;
            WeaponAPI weapon = weapons != null && idx >= 0 && idx < weapons.size()
                    ? weapons.get(idx) : null;
            if (weapon == null) {
                splitChildSkips++;
                splitSkipNoMount++;
                pose.lane.log("predict v13 missile-split child SKIP: src=" + t.sourceEntityId
                        + " mount=" + idx + " unresolvable (no live local mount)");
                return;
            }
            int numShots = params.getInt("numShots");
            float arc = (float) params.getDouble("arc");
            float fxFacing = facing + (float) params.optDouble("arcOffset", 0.0);
            float spreadSpeed = (float) params.getDouble("spreadSpeed");
            float spreadSpeedRange = (float) params.optDouble("spreadSpeedRange", 0.0);
            float spreadInaccuracy = (float) params.optDouble("spreadInaccuracy", 0.0);
            float projectileRange = (float) params.optDouble("projectileRange", 300.0);
            boolean evenSpread = params.optBoolean("evenSpread", false);
            float damage = (float) params.getDouble("damage");
            float emp = (float) params.optDouble("emp", 0.0);
            float hitpoints = (float) params.getDouble("hitpoints");
            DamageType damageType = parseDamageType(params);
            Vector2f spawnLoc = splitOffsetLoc(event, facing);
            int shots = Math.min(numShots, MAX_SPLIT_CHILDREN);
            if (shots < numShots) {
                pose.lane.log("predict v13 missile-split children CAPPED: numShots=" + numShots
                        + " > " + MAX_SPLIT_CHILDREN);
            }
            // The vanilla F lane's per-split private clone — MirvAI's own
            // mechanism (never the shared store spec, engine-faithful). maxRange and the
            // damage placeholder set once (vanilla re-sets range per shot with the same value;
            // it sets damage once, before the shot loop); moveSpeed per shot below.
            // Null clone / unresolved factory / non-concrete ship-weapon = the approx lane.
            // Every arm of this gate now NAMES itself. Before, four distinct declines AND a
            // swallowed clone throw all arrived at the same untokened `child == null`, which is
            // how an earlier read came to attribute the failure to a method that cannot return null.
            F vanillaChildSpec = null;
            String gate = null;
            boolean cloneThrew = false;
            if (!(childSpec instanceof F)) {
                gate = "spec_not_F:" + childSpec.getClass().getName();
            } else if (SPLIT_FACTORY == null) {
                gate = "factory_unresolved";
            } else if (SPEC_SET_DAMAGE == null || DAMAGE_CTOR == null) {
                // Without the damage-placeholder mirror the factory ctor NPEs on every child, so an
                // unresolved pair declines NAMED to the approx lane rather than re-minting the
                // silent 35/35. Same doctrine as every other arm of this gate.
                gate = "damage_handles_unresolved";
            } else if (!(ship instanceof Ship)) {
                gate = "ship_not_engine:" + ship.getClass().getName();
            } else if (!(weapon instanceof o00O)) {
                gate = "weapon_not_engine:" + weapon.getClass().getName();
            } else {
                // Which prep step threw — the try now covers THREE mutations, and one
                // unnamed NPE once cost a whole watch to attribute. The meter KEY
                // (predict_missile_split_clone_threw) is unchanged; graders join on it.
                String prep = "clone";
                try {
                    vanillaChildSpec = ((F) childSpec).clone();
                    prep = "range";
                    vanillaChildSpec.setMaxRange(projectileRange);
                    prep = "damage";
                    // THE C7 FIX — vanilla's placeholder, mirrored at last. The child store
                    // spec is damage-less and the factory ctor does spec.getDamage().clone(),
                    // so this placeholder is what lets the spawn happen AT ALL.
                    // Presented damage is untouched: real child damage rides
                    // applyChildOverrides either way, exactly as the earlier read assumed — its error was
                    // reading a precondition as an aesthetic delta, not the damage claim.
                    SPEC_SET_DAMAGE.invoke(vanillaChildSpec,
                            DAMAGE_CTOR.invoke(1.0F, false, DamageType.ENERGY));
                } catch (Throwable clo) {
                    vanillaChildSpec = null;
                    cloneThrew = true;
                    splitCloneThrew++;
                    gate = "clone_threw:" + prep + ':' + clo.getClass().getSimpleName();
                }
            }
            if (gate != null && !cloneThrew) {
                splitGateIneligible++;
            }
            float evenBase = fxFacing - arc / 2f;
            int spawnedHere = 0;
            int spawnedVanilla = 0;
            for (int i = 0; i < shots; i++) {
                float ang = (float) Math.random() * arc - arc / 2f + fxFacing;
                if (evenSpread) {
                    ang = numShots <= 1 ? fxFacing : evenBase + arc / (numShots - 1f) * i;
                }
                ang += ((float) Math.random() - 0.5f) * spreadInaccuracy;
                Vector2f childVel;
                CombatEntityAPI child = null;
                String factoryFail = null;
                String fallbackFail = null;
                splitAttempts++; // SHOTS, not events (an earlier count conflated the two units)
                if (childSpec instanceof F) {
                    // Vanilla passes the RAW parent velocity — the direction
                    // spread rides the factory's moveSpeed·dir(ang) add.
                    childVel = new Vector2f(vel);
                    if (vanillaChildSpec != null) {
                        try {
                            // Vanilla's per-shot clone mutation, then the EXACT
                            // vanilla dispatcher call.
                            vanillaChildSpec.setMoveSpeed(
                                    spreadSpeed + spreadSpeedRange * (float) Math.random());
                            Object minted = SPLIT_FACTORY.invoke(
                                    (Ship) ship, (o00O) weapon, (Object) vanillaChildSpec,
                                    spawnLoc, ang, childVel);
                            if (minted instanceof CombatEntityAPI) {
                                child = (CombatEntityAPI) minted;
                                spawnedVanilla++;
                            } else {
                                // The dispatcher's OWN fall-through (an F whose
                                // spawn type is MISSILE/OTHER). Vanilla nulls here too, so this
                                // would be the SPEC's shape, never our call.
                                splitFactoryNull++;
                                factoryFail = minted == null ? "dispatcher_null"
                                        : "not_entity:" + minted.getClass().getName();
                            }
                        } catch (Throwable fx) {
                            child = null; // this shot rides the 7-arg approx below
                            splitFactoryThrew++;
                            factoryFail = "threw:" + fx.getClass().getSimpleName()
                                    + ':' + noSpaces(fx.getMessage());
                        }
                    } else {
                        factoryFail = "not_attempted";
                    }
                } else {
                    double rad = Math.toRadians(ang);
                    float speed = spreadSpeed + spreadSpeedRange * (float) Math.random();
                    childVel = new Vector2f(vel.x + (float) Math.cos(rad) * speed,
                            vel.y + (float) Math.sin(rad) * speed);
                }
                if (child == null) {
                    try {
                        child = pose.plugin.engine.spawnProjectile(ship, weapon, weapon.getId(),
                                childSpecId, spawnLoc, ang, childVel);
                        if (child == null) {
                            splitFallbackNull++;
                            fallbackFail = "returned_null";
                        }
                    } catch (Throwable fb) {
                        splitFallbackThrew++;
                        fallbackFail = "threw:" + fb.getClass().getSimpleName()
                                + ':' + noSpaces(fb.getMessage());
                    }
                }
                if (child == null) {
                    splitChildSkips++;
                    splitSkipNoChild++;
                    // The whole context of the FIRST failure, on the METER (never lane.log —
                    // that is capped and 62 split lines exhaust it). This is what makes the next
                    // run attribute the cause instead of narrowing it by another round.
                    if (splitForensic == null) {
                        splitForensic = "spec:" + noSpaces(childSpecId)
                                + "|class:" + childSpec.getClass().getName()
                                + "|spawnType:" + (childSpec
                                        instanceof com.fs.starfarer.api.loading.ProjectileSpecAPI
                                        ? String.valueOf(((com.fs.starfarer.api.loading
                                                .ProjectileSpecAPI) childSpec).getSpawnType())
                                        : "n/a")
                                + "|gate:" + (gate == null ? "ok" : gate)
                                + "|factory:" + (factoryFail == null ? "unreached" : factoryFail)
                                + "|fallback:" + (fallbackFail == null ? "unreached" : fallbackFail)
                                + "|ship:" + ship.getClass().getName()
                                + "|weapon:" + weapon.getClass().getName()
                                + "|weaponId:" + noSpaces(weapon.getId())
                                + "|mount:" + idx + "|numShots:" + numShots;
                    }
                    continue;
                }
                // Exclusion FIRST, the presenceAdopt discipline.
                pose.plugin.presentedFoeProj.add(child);
                pose.plugin.localFire.onPresentedAdd();
                if (child instanceof MissileAPI) {
                    // The store said non-missile but the factory minted one — a locally
                    // spawned guided body is never kept (double-present guard).
                    pose.plugin.engine.removeEntity(child);
                    pose.plugin.localFire.onPresentedEphemeral(); // entry can never be consumed — roll (I)(b)
                    splitChildSkips++;
                    splitSkipGuidedMint++;
                    continue;
                }
                applyChildOverrides(child, ship, damage, emp, damageType, hitpoints);
                // birth-latch the child as a replica shot (ReplicaEffectLatchLaw) so its
                // onHit wrapper (CcWrapSabotOnHitEffect) keeps the replica branch even if the
                // source stops being a treated replica mid-flight (the foe dies); the source
                // treatment read is the same one the fire relay latches on.
                if (child instanceof DamagingProjectileAPI) {
                    ReplicaPresentedFireRelay.latchAtFire((DamagingProjectileAPI) child);
                }
                splitChildren++;
                spawnedHere++;
            }
            if (spawnedHere > 0) {
                pose.lane.log("predict v13 missile-split children: spec=" + childSpecId
                        + " spawned=" + spawnedHere + " vanilla=" + spawnedVanilla
                        + " src=" + t.sourceEntityId + " mount=" + idx);
            }
        } catch (Throwable th) {
            // The spawn loop's OWN swallow — the one that would hide a throwing
            // 7-arg fallback. Counted + logged once; the lane still never breaks.
            splitSpawnThrew++;
            if (!splitSpawnThrewLogged) {
                splitSpawnThrewLogged = true;
                pose.lane.log("predict v13 missile-split SPAWN THREW (fail-soft, one-shot): " + th);
            }
        }
    }

    /** Stash fill (split moment, guided-children branch): the parent twin's own
     *  {@code getBehaviorSpecParams()} recipe — the SAME params source vanilla reads
     *  (MirvAI ctor) — keyed by the launcher attribution pair its children ride
     *  (the launcher-pair law). Unreadable recipe fields fail-soft to no stash = today's behavior. */
    private void stashChildOverride(MissilePoseLane.Twin t, JSONObject params, String childSpecId,
            long tick) {
        try {
            childOverrides.put(MissilePoseLane.pairKey(t.sourceEntityId, t.sourceMountIndex),
                    new ChildOverride(
                    childSpecId,
                    (float) params.getDouble("damage"),
                    (float) params.optDouble("emp", 0.0),
                    (float) params.optDouble("impact", 1.0),
                    (float) params.getDouble("hitpoints"),
                    parseDamageType(params),
                    tick + CHILD_OVERRIDE_TICKS));
        } catch (Throwable ignore) {
            // presentation lane; children adopt un-overridden (today's behavior)
        }
    }

    /** Vanilla's post-spawn child damage injection (MirvAI semantics)
     *  for a wire-adopted MIRV child: applies ONLY when the adopted body wears the stashed
     *  child spec. A recipe-fallback mint (no wire spec truth ⇒ {@code trueSpec} null)
     *  counts as the MISS — the un-fixed child lane (B) exists to drain; a body whose wire
     *  truth names a DIFFERENT spec (the husk, an unrelated same-pair missile) is expected
     *  and stays silent. Damage rides the standard presented lane afterwards — the
     *  presented-real caps and the RAW rows are untouched. */
    void applyChildStash(MissileAPI missile, ShipAPI ship, ServerMissileState rec,
            String trueSpec, long tick, long id) {
        try {
            long pair = MissilePoseLane.pairKey(rec.sourceEntityId(), rec.sourceMountIndex());
            ChildOverride ov = childOverrides.get(pair);
            if (ov == null) {
                return; // no split stash on this pair — a normal non-child adopt
            }
            if (tick > ov.expiryTick) {
                childOverrides.remove(pair);
                return; // stale stash pruned — no live split context on this pair
            }
            String specId = MissilePoseLane.specIdOf(missile);
            if (specId == null || !specId.equals(ov.specId)) {
                if (trueSpec == null) {
                    childOverrideMiss++;
                    pose.lane.log("predict v13 missile-child override MISS: id=" + id + " spec="
                            + specId + " != stash " + ov.specId
                            + " (recipe fallback — untouched)");
                }
                return;
            }
            DamageAPI d = missile.getDamage();
            d.setDamage(ov.damage);
            d.setFluxComponent(ov.emp);
            d.setType(ov.damageType);
            if (missile instanceof Missile) {
                Missile m = (Missile) missile;
                m.setImpact(ov.impact);
                float hp = ov.hitpoints;
                try {
                    if (ship != null) {
                        hp = ship.getMutableStats().getMissileHealthBonus()
                                .computeEffective(ov.hitpoints);
                    }
                } catch (Throwable ignore) {
                }
                m.setHitpoints(hp);
                m.setMaxHitpoints(hp);
                if (m.getAI() instanceof ProximityFuseAI) {
                    ((ProximityFuseAI) m.getAI()).updateDamage();
                }
            }
            childOverrideHit++;
            pose.lane.log("predict v13 missile-child override: id=" + id + " spec=" + specId
                    + " damage=" + ov.damage + " emp=" + ov.emp + " hp=" + ov.hitpoints);
        } catch (Throwable ignore) {
            // presentation lane; never break the watch
        }
    }

    /** {@code damageType} enum read, vanilla-default OTHER (MirvAI helper semantics). */
    private static DamageType parseDamageType(JSONObject params) {
        try {
            return DamageType.valueOf(
                    params.optString("damageType", "OTHER").trim().toUpperCase());
        } catch (Throwable ignore) {
            return DamageType.OTHER;
        }
    }

    /** The vanilla per-child overrides for non-missile children (as MirvAI does): real
     *  damage/EMP/type via the public {@link DamagingProjectileAPI} setters, then the
     *  concrete-class touches vanilla makes — {@code setFromMissile(true)} (PD priority),
     *  ballistic hitpoints (source missile-health bonus applied), and the
     *  proximity-fuse damage re-sync. {@code PlasmaShot.setFacing(toward target)} is
     *  skipped — the client holds no target; the factory bearing stands. */
    private static void applyChildOverrides(CombatEntityAPI child, ShipAPI ship, float damage,
            float emp, DamageType damageType, float hitpoints) {
        try {
            if (child instanceof DamagingProjectileAPI) {
                DamageAPI d = ((DamagingProjectileAPI) child).getDamage();
                d.setDamage(damage);
                d.setFluxComponent(emp);
                d.setType(damageType);
            }
            if (child instanceof BallisticProjectile) {
                BallisticProjectile bp = (BallisticProjectile) child;
                bp.setFromMissile(true);
                float hp = hitpoints;
                try {
                    if (ship != null) {
                        hp = ship.getMutableStats().getMissileHealthBonus()
                                .computeEffective(hitpoints);
                    }
                } catch (Throwable ignore) {
                }
                bp.setHitpoints(hp);
                bp.setMaxHitpoints(hp);
                if (bp.getAI() instanceof ProximityFuseAI) {
                    ((ProximityFuseAI) bp.getAI()).updateDamage();
                }
            } else if (child instanceof MovingRay) {
                MovingRay ray = (MovingRay) child;
                ray.setFromMissile(true);
                if (ray.getAI() instanceof ProximityFuseAI) {
                    ((ProximityFuseAI) ray.getAI()).updateDamage();
                }
            } else if (child instanceof PlasmaShot) {
                PlasmaShot shot = (PlasmaShot) child;
                shot.setFromMissile(true);
                if (shot.getAI() instanceof ProximityFuseAI) {
                    ((ProximityFuseAI) shot.getAI()).updateDamage();
                }
            }
        } catch (Throwable ignore) {
            // presentation lane; never break the watch
        }
    }

    /** The lane's meter-line contribution (byte-identical token block, appended by the pose
     *  lane's {@code logMeter} at the SAME position the inline tokens held — the graders join
     *  on these names). */
    String meterTokens() {
        return " predict_missile_split_events=" + splitEvents
                + " predict_missile_split_retired=" + splitRetired
                + " predict_missile_split_orphan=" + splitOrphan
                + " predict_missile_split_fx=" + splitFx
                + " predict_missile_split_children=" + splitChildren
                + " predict_missile_split_child_skips=" + splitChildSkips
                + " predict_missile_split_skip_nospec=" + splitSkipNoSpec
                + " predict_missile_split_skip_nomount=" + splitSkipNoMount
                + " predict_missile_split_skip_nochild=" + splitSkipNoChild
                + " predict_missile_split_skip_guidedmint=" + splitSkipGuidedMint
                + " predict_missile_split_present_threw=" + splitPresentThrew
                + " predict_missile_split_spawn_threw=" + splitSpawnThrew
                // The skip_nochild cause partition + the two unit-disambiguating counters.
                + " predict_missile_split_guided=" + splitGuided
                + " predict_missile_split_attempts=" + splitAttempts
                + " predict_missile_split_gate_ineligible=" + splitGateIneligible
                + " predict_missile_split_clone_threw=" + splitCloneThrew
                + " predict_missile_split_factory_null=" + splitFactoryNull
                + " predict_missile_split_factory_threw=" + splitFactoryThrew
                + " predict_missile_split_fallback_null=" + splitFallbackNull
                + " predict_missile_split_fallback_threw=" + splitFallbackThrew
                + " predict_missile_split_forensic=" + (splitForensic == null ? "none" : splitForensic)
                + " predict_missile_child_override_hit=" + childOverrideHit
                + " predict_missile_child_override_miss=" + childOverrideMiss;
    }

    /** Round reset (called from the pose lane's {@code resetForMatchReset}): per-round
     *  identity state clears — counters stay cumulative across rounds. */
    void resetForMatchReset() {
        childOverrides.clear();
    }
}
