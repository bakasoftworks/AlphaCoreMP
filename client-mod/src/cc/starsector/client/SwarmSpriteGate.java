package cc.starsector.client;

import java.util.Set;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.impl.combat.threat.SwarmLauncherEffect;

/**
 * The SWARM BASE-SPRITE gate:
 * {@link SpawnSystemGate}/{@link SpawnHullmodGate}'s render-side sibling, same NEW-LANE shape
 * (own file, one treatment site, try-wrapped, fails OPEN).
 *
 * <p><b>Defect.</b> Every {@code attack_swarm} replica showed a static grey fragment clump
 * (the hull's own {@code graphics/ships/threat/fragment_swarm.png}) plus an engine hull bar
 * UNDER the animated {@code RoilingSwarmEffect} cloud. Vanilla never renders that base hull:
 * every vanilla swarm spawner hides the body the instant it mints it —
 * {@code SwarmLauncherEffect}, {@code ConstructionSwarmSystemScript} and {@code ThreatShipReclamationScript}
 * all call {@code setDoNotRender(true)}.
 * Our swarm bodies never pass through those spawners: they arrive as
 * server-replicated roster rows minted directly by {@code spawnShipOrWing}/{@code spawnFleetMember}
 * (the local launcher is held inert by {@code CcWrapSwarmLauncherEffect}), so the vanilla hide
 * step was skipped and the engine drew the clump + bar.
 *
 * <p><b>Fix = vanilla's own lever at the replica's own treatment seam.</b> Once per body, in
 * {@code PredictReconcileCombatPlugin.neutralizeRemoteBody} beside the spawn gates, a body whose
 * hull IS the swarm fighter takes {@code setDoNotRender(true)}. The cloud is untouched — it is a
 * separate layered-render entity built every frame by the {@code fragment_swarm} builtin's
 * {@code advanceInCombat}, which this gate deliberately never suppresses. {@code setDoNotRender}
 * is render-only (collision, hull, targeting, authority unchanged), so it is a pure client
 * derivation from data the client already holds (the minted replica's own hull spec): zero server
 * compute, no wire field.
 *
 * <p><b>Predicate = the swarm FIGHTER hull, never the hullmod alone.</b> {@code fragment_swarm}
 * also rides the big Threat hulls (their fragment cloud is the same hullmod) and is a lootable
 * player hullmod — keying on it would hide a whole Threat cruiser. {@code ship_data.csv} tags the
 * one swarm hull {@code swarm_fighter} (row {@code attack_swarm}; Attack/Construction/Reclamation
 * are variants of that same hull), so the tag is the key and the hull id the fallback.
 */
final class SwarmSpriteGate {

    /** The one vanilla hull tag carried by the swarm fighter hull (its row in {@code ship_data.csv}). */
    static final String SWARM_FIGHTER_TAG = "swarm_fighter";
    /** Hull-id fallback for a spec whose tag set is unreadable. */
    static final String SWARM_HULL_ID = "attack_swarm";

    private static final int GATE_LOG_MAX = 20;

    private int hiddenBodies;
    private int verifyFailures;
    private int gateThrows;
    private int gateLogLines;

    /**
     * The pure law (selftest-visible, game-free): hide when the hull carries the swarm-fighter tag
     * or IS the swarm hull id. A null/empty tag set falls through to the id.
     */
    static boolean wantsHide(Set<String> hullTags, String baseHullId) {
        if (hullTags != null && hullTags.contains(SWARM_FIGHTER_TAG)) {
            return true;
        }
        return baseHullId != null && SWARM_HULL_ID.equals(baseHullId);
    }

    /**
     * Hide ONE replica body's base hull sprite if it is a swarm fighter. Idempotent; returns true
     * when this call hid the body. Fail OPEN: a body this gate cannot read renders exactly as
     * pre-gate (the visible-but-wrong clump, never a missing ship).
     */
    boolean hide(ShipAPI ship) {
        if (ship == null) {
            return false;
        }
        try {
            ShipHullSpecAPI spec = ship.getHullSpec();
            if (spec == null) {
                return false;
            }
            if (!wantsHide(spec.getTags(), spec.getBaseHullId())) {
                return false;
            }
            if (ship.isDoNotRender()) {
                return false; // already hidden (a re-treatment after a band drop)
            }
            ship.setDoNotRender(true);
            // D-FABPOP v2 — every fabricator-made ship ended with a frigate-class death pop:
            // vanilla's spawners hide the body AND
            // zero its death presentation in the same breath:
            // setExplosionScale(0) +
            // setHulkChanceOverride(0) + setImpactVolumeMult(0.33). The engine
            // death routine plays the `explosion_ship` cue (keyed
            // hull style + hull size => the FRIGATE-class bang heard in play) and spawns the
            // explosion visual ONLY while explosionScale > 0 — the 9-arg applyDamage's playSound=false
            // never reaches it. Our replicas skipped the spawner, so every swarm body (the construction
            // carrier at its T2 kill included) died at explosionScale 1.0. Mirror the trio here, once,
            // at the same seam as the hide. Presentation-only; collision/hull/authority unchanged.
            ship.setExplosionScale(0f);
            ship.setHulkChanceOverride(0f);
            ship.setImpactVolumeMult(SwarmLauncherEffect.IMPACT_VOLUME_MULT);
            boolean verified = ship.isDoNotRender();
            if (verified) {
                hiddenBodies++;
            } else {
                verifyFailures++;
            }
            log((verified ? "HIDE " : "HIDE-UNVERIFIED ") + spec.getBaseHullId()
                    + " on " + Integer.toHexString(System.identityHashCode(ship))
                    + " (vanilla SwarmLauncherEffect setDoNotRender + explosionScale=0"
                    + " hulkChance=0 impactVol=" + SwarmLauncherEffect.IMPACT_VOLUME_MULT + "; cloud untouched)");
            return verified;
        } catch (Throwable t) {
            gateThrows++;
            PilotWatchLog.error("predict replica swarm-sprite-gate: hide FAILED on "
                    + Integer.toHexString(System.identityHashCode(ship)) + " (body renders as pre-gate)", t);
            return false;
        }
    }

    private void log(String body) {
        if (gateLogLines >= GATE_LOG_MAX) {
            return;
        }
        gateLogLines++;
        PilotWatchLog.info("predict replica swarm-sprite-gate " + body
                + (gateLogLines == GATE_LOG_MAX ? " [gate log cap; counters continue]" : ""));
    }

    /** Meter fragment (pinned key names; rides the hullmod gate's line). */
    String meterTokens() {
        return " predict_swarm_sprite_hidden_bodies=" + hiddenBodies
                + " predict_swarm_sprite_verify_failures=" + verifyFailures
                + " predict_swarm_sprite_gate_throws=" + gateThrows;
    }
}
