package cc.starsector.client;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAPI;
import org.lwjgl.util.vector.Vector2f;

/**
 * Carved from the MissilePoseLane/MineBombEndFxLane split — the
 * CANISTER-flak end-of-life presentation sub-collaborator ({@code MineBombEndFxLane}-owned,
 * the same backref shape that lane holds toward {@code MissilePoseLane}; behavior-identical
 * move): the {@code CANISTER_FX} knob + spec-keyed family gate, the end-burst
 * classifier with its fuse-envelope snap, the prune-exit dispatch, and
 * the canister meter tokens. The owning lane keeps the kind-6
 * {@link MineBombEndFxLane.WireEnd} store (verdicts arrive remove-taken through the owner's
 * dispatch or {@code takeWireEnd}) and the shared geometry helpers
 * ({@code detEligible}/{@code edgeToward}/{@code surfaceToward}, package-static since the
 * carve); the Twin ledger stays {@code MissilePoseLane}-owned throughout (the
 * shared-ledger precedent).
 *
 * <p>Render-thread only, fail-soft throughout; logs through the owning stack's capped budget
 * ({@code mpl.lane} — shared accounting unchanged by the move).
 */
final class CanisterEndFxLane {

    /**
     * The CANISTER-flak FX knob, {@code cc.predict.canisterFx} = {@code on|off},
     * DEFAULT ON: the wire-adopted {@code canister_flak_proj} round (the invictus defense
     * system's airburst flak — a MISSILE body fired by SYSTEM weapon {@code canister_flak}.
     * The arm first assumed it crossed SOURCELESS; a wire census OVERTURNED that — canisters cross fully
     * SOURCED (330/330, src=mount 6..20), so the LIVE doors are the sourced presence-adopt +
     * enroll seams in {@code MissilePoseLane}; {@code adoptSourceless} stays unreachable-but-kept
     * cover) gets (i) the {@code system_canister_flak_fire} sound at every acquisition
     * seam (launch-truth compromise — no muzzle-flash guess) and (ii) its
     * burst at the wire end of track.
     * Vanilla law: {@code ProximityFuseAI.advance} explodes on {@code isFading()}
     * as its FIRST check, and canister {@code fizzleTime−fadeTime = 0}
     * (flameoutTime 0.05 = fadeTime 0.05) makes a range-end fizzle burst the
     * SAME instant — so a canister NEVER fades silently: prox, contact, and range-end all burst
     * (the phase-charge law shape extended to FIZZLING; NOT the mine quiet-shape). Quiet needs
     * a POSITIVE wire {@code DESTROYED} verdict (PD hp-kill — vanilla's only silent-ish end).
     * Damage-less presentation throughout (NOOP puppet AI, no local fuse, no
     * {@code DamagingExplosion}); own-ship felt damage deliberately NOT relayed — ground truth:
     * the burst spec is {@code PROJECTILE_NO_FF} AND the engine hard-excludes the source
     * ship, so canister_flak cannot damage its firer
     * and needs no {@code OwnDetLane} entry. {@code off} = the silence before this arm, byte-for-byte.
     * Compiled ON (E3 hardwire; the {@code cc.predict.canisterFx}
     * knob-resolved shape is in git).
     */
    static final boolean CANISTER_FX = true;

    /** The native burst, ALL layers: the canister_flak.proj
     *  behaviorSpec.explosionSpec exactly as the engine renders it, spec and engine path both
     *  re-read first-hand at this landing.
     *  The earlier present was ONE spawnExplosion r75/0.5 s — it dropped L1/L3/L4 and
     *  undersized+shortened L2, the "smaller/duller" gap seen in play.
     *  Public-API equivalents of the native calls throughout; damage-less (no-local-fuse rule unchanged). */
    // L2 — explosion body: spawnExplosion(explosionColor, detailedExplosionRadius=80,
    //      particleDuration=1.0).
    private static final java.awt.Color CANISTER_END_FX_COLOR = new java.awt.Color(155, 125, 75, 255);
    private static final float CANISTER_END_FX_SIZE = 80f;
    private static final float CANISTER_END_FX_SECONDS = 1.0f;
    // L1 — 50-particle smooth spray (the EmitterFactory 8-arg overload): size
    //      int(rand·4+4) ∈ 4..8, spawn disc r ≤ radius·0.75 = 56.25 (particleSpawnRadius unset
    //      ⇒ −1), vel = offset·3·rand per component, age 1.0.
    private static final java.awt.Color CANISTER_SPRAY_COLOR = new java.awt.Color(255, 225, 125, 255);
    private static final int CANISTER_SPRAY_COUNT = 50;
    private static final float CANISTER_SPRAY_RADIUS_SU = 56.25f;
    private static final float CANISTER_SPRAY_SECONDS = 1.0f;
    // L3/L4 — the detailed-explosion flash pair: fringe hit-particle at
    //      detailedExplosionFlashRadius 250 + core at ×0.25 = 62.5, both flashDuration 0.5,
    //      brightness 1.0 — the r250 fringe is the felt "bigger/glowier" DOMINANT layer.
    private static final java.awt.Color CANISTER_FLASH_FRINGE_COLOR = new java.awt.Color(200, 175, 100, 255);
    private static final java.awt.Color CANISTER_FLASH_CORE_COLOR = new java.awt.Color(155, 155, 155, 255);
    private static final float CANISTER_FLASH_RADIUS_SU = 250f;
    private static final float CANISTER_FLASH_SECONDS = 0.5f;
    /** canister_flak.proj behaviorSpec.range — the prox-fuse envelope (su) the snap
     *  mirrors (+ the target's facing edge, the engine's own fuse shape). */
    private static final float CANISTER_FUSE_NEAR_SU = 40f;

    // The canister-flak end arm (wire-cause accounting mirrors the mine arm).
    private int canisterEndFx;            // predict_missile_canister_endfx (burst presented)
    private int canisterEndQuiet;         // predict_missile_canister_end_quiet (wire DESTROYED)
    private int canisterEndWireDestroyed; // predict_missile_canister_end_wire_destroyed
    private int canisterEndWireIntact;    // predict_missile_canister_end_wire_intact
    private int canisterEndWireFizzling;  // predict_missile_canister_end_wire_fizzling
    private int canisterEndWireNone;      // predict_missile_canister_end_wire_none
    private int canisterSnap;             // predict_missile_canister_snap (burst snapped to a body)
    private int canisterPruneExits;       // predict_missile_canister_prune_exit (each
                                          //   local-death exit dispatched the classifier)

    private final MissilePoseLane mpl;
    private final MineBombEndFxLane end;

    CanisterEndFxLane(MissilePoseLane mpl, MineBombEndFxLane end) {
        this.mpl = mpl;
        this.end = end;
    }

    /** The canister-flak family gate (spec-keyed like the mine/bomb arms): the
     *  invictus defense system's airburst round, the sourceless class's third member. */
    static boolean isCanisterSpec(String specId) {
        return "canister_flak_proj".equals(specId);
    }

    /** Classifier-on-every-exit: the {@code pruneLocalGone} (local-death)
     *  dispatch. With the twins intangible the only local deaths left are engine removals —
     *  the wire-FIZZLING flameOut relay's 0.05 s fade racing the wire-absence grace — and
     *  vanilla law bursts THERE (knob javadoc: a canister never fades silently). Pose = last
     *  wire sample (the honest truth this side holds); the kind-6 verdict, if one already
     *  landed, is remove-taken so entries never outlive their twin. A never-wire-sampled twin
     *  (pre-link local death) presents nothing — no honest pose exists. Round reset stays
     *  quiet (twins clear without dispatch). */
    void onPruneExit(MissilePoseLane.Twin t, long tick) {
        if (!CANISTER_FX || !MissilePoseLane.MISSILE_POSE_APPLY
                || t.lastWireTick == Long.MIN_VALUE || t.canisterEndClassified) {
            return;
        }
        canisterPruneExits++;
        presentEndFx(t, tick, end.takeWireEnd(t.wireId));
    }

    /** The canister-flak end burst ({@code CANISTER_FX} knob; law + provenance
     *  in the knob javadoc): burst by DEFAULT — quiet needs a positive wire {@code DESTROYED}
     *  (PD hp-kill); INTACT (prox/contact det), FIZZLING (range-end — vanilla bursts THERE,
     *  on a 0-width fade window), and no-record all present the
     *  behaviorSpec explosionSpec as damage-less FX. Deliberately NO {@code t.fizzled} gate:
     *  a canister latches the wire-FIZZLING relay at range end and the latch must not starve
     *  the burst (the conversion-class lesson). The fuse-envelope snap
     *  vs hostile missiles AND ships/fighters (range 40 + the target's facing edge) — the
     *  canister's own side is excluded by the owner filter, which also excludes the ending
     *  round itself (same owner, still in play until {@code retire}). No {@code OwnDetLane}
     *  relay and no impact sweep: the burst cannot damage its firer (NO_FF + source-excluded,
     *  knob javadoc) and hostile damage stays wire-mirrored. Since (62c) the burst presents
     *  the FULL native layer stack (constants javadoc above), not the single r75 body. */
    void presentEndFx(MissilePoseLane.Twin t, long tick, MineBombEndFxLane.WireEnd wireEnd) {
        if (!CANISTER_FX || !MissilePoseLane.MISSILE_POSE_APPLY || !isCanisterSpec(t.specId)
                || t.canisterEndClassified) {
            return;
        }
        t.canisterEndClassified = true; // once-per-twin latch (quiet latches too)
        try {
            if (wireEnd == null) {
                canisterEndWireNone++;
            } else if (wireEnd.cause
                    == cc.starsector.client.net.ClientGameplayEdgeEvent.END_CAUSE_DESTROYED) {
                canisterEndWireDestroyed++;
                canisterEndQuiet++; // wire-verified hp-death — vanilla's only silent-ish end
                return;
            } else if (wireEnd.cause
                    == cc.starsector.client.net.ClientGameplayEdgeEvent.END_CAUSE_FIZZLING) {
                canisterEndWireFizzling++;
            } else {
                canisterEndWireIntact++;
            }
            Vector2f loc = wireEnd != null ? new Vector2f(wireEnd.x, wireEnd.y)
                    : new Vector2f(t.lastWireX, t.lastWireY);
            Vector2f still = new Vector2f();
            int owner = t.missile != null ? t.missile.getOwner() : 1;
            // Nearest fuse-envelope body wins, missiles and ships in one contest — the engine's
            // own fuse shape, presentation-only.
            float bestD2 = Float.MAX_VALUE;
            Vector2f snapTo = null;
            for (com.fs.starfarer.api.combat.MissileAPI m : mpl.plugin.engine.getMissiles()) {
                if (m == null || m.getLocation() == null || m.getOwner() == owner) {
                    continue;
                }
                float reach = CANISTER_FUSE_NEAR_SU + m.getCollisionRadius();
                float dx = m.getLocation().x - loc.x;
                float dy = m.getLocation().y - loc.y;
                float d2 = dx * dx + dy * dy;
                if (d2 <= reach * reach && d2 < bestD2) {
                    bestD2 = d2;
                    snapTo = new Vector2f(m.getLocation());
                }
            }
            for (ShipAPI ship : mpl.plugin.engine.getShips()) {
                if (!MineBombEndFxLane.detEligible(ship, owner)) {
                    continue;
                }
                float reach = CANISTER_FUSE_NEAR_SU + MineBombEndFxLane.edgeToward(ship, loc);
                float dx = ship.getLocation().x - loc.x;
                float dy = ship.getLocation().y - loc.y;
                float d2 = dx * dx + dy * dy;
                if (d2 <= reach * reach && d2 < bestD2) {
                    bestD2 = d2;
                    snapTo = MineBombEndFxLane.surfaceToward(ship, loc);
                }
            }
            if (snapTo != null) {
                loc = snapTo;
                mpl.plugin.engine.addHitParticle(loc, still, 40f, 1f, 0.3f,
                        CANISTER_END_FX_COLOR);
                canisterSnap++;
            }
            // The full native layer stack in the engine's own order —
            // L1 spray → L2 body → L3 fringe → L4 core. Math.random matches the native
            // emitter's own per-particle randomness (render-side FX only, no sim state).
            for (int i = 0; i < CANISTER_SPRAY_COUNT; i++) {
                float size = (int) (Math.random() * 4f + 4f);
                double theta = Math.random() * 2.0 * Math.PI;
                float r = (float) (Math.random() * CANISTER_SPRAY_RADIUS_SU);
                float ox = (float) (Math.cos(theta) * r);
                float oy = (float) (Math.sin(theta) * r);
                mpl.plugin.engine.addSmoothParticle(new Vector2f(loc.x + ox, loc.y + oy),
                        new Vector2f(ox * 3f * (float) Math.random(),
                                oy * 3f * (float) Math.random()),
                        size, 1f, CANISTER_SPRAY_SECONDS, CANISTER_SPRAY_COLOR);
            }
            mpl.plugin.engine.spawnExplosion(loc, still, CANISTER_END_FX_COLOR,
                    CANISTER_END_FX_SIZE, CANISTER_END_FX_SECONDS);
            mpl.plugin.engine.addHitParticle(loc, still, CANISTER_FLASH_RADIUS_SU, 1f,
                    CANISTER_FLASH_SECONDS, CANISTER_FLASH_FRINGE_COLOR);
            mpl.plugin.engine.addHitParticle(loc, still, CANISTER_FLASH_RADIUS_SU * 0.25f, 1f,
                    CANISTER_FLASH_SECONDS, CANISTER_FLASH_CORE_COLOR);
            Global.getSoundPlayer().playSound("system_canister_flak_explosion", 1f, 1f,
                    loc, still);
            canisterEndFx++;
            mpl.lane.log("predict v16 canister end-FX: id=" + t.wireId + " tick=" + tick
                    + " at=" + (int) loc.x + "," + (int) loc.y
                    + " wireCause=" + (wireEnd == null ? "none" : String.valueOf(wireEnd.cause))
                    + (snapTo == null ? "" : " snap"));
        } catch (Throwable ignore) {
            // presentation lane; never break the watch
        }
    }

    /** Spliced into the owner's {@code meterTokens()} at the pre-carve token position (the
     *  split/own meterTokens discipline — the MPL meter line stays byte-identical). */
    String meterTokens() {
        return " predict_missile_canister_endfx=" + canisterEndFx
                + " predict_missile_canister_end_quiet=" + canisterEndQuiet
                + " predict_missile_canister_end_wire_destroyed=" + canisterEndWireDestroyed
                + " predict_missile_canister_end_wire_intact=" + canisterEndWireIntact
                + " predict_missile_canister_end_wire_fizzling=" + canisterEndWireFizzling
                + " predict_missile_canister_end_wire_none=" + canisterEndWireNone
                + " predict_missile_canister_snap=" + canisterSnap
                + " predict_missile_canister_prune_exit=" + canisterPruneExits;
    }
}
