package cc.starsector.server.runtime;

import cc.starsector.server.bridge.EngineCombatBinding;
import cc.starsector.server.protocol.GameplayEdgeEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * The Guardian-PD ((111)) det-EVENT lane's server half:
 * converts the binding's per-tick {@link EngineCombatBinding.PaladinDet} birth side channel
 * (the PROJECTILE-read identity-set diff + spec-fingerprint whitelist)
 * into {@code PALADIN_DET} gameplay edge events (GPEE schema v10) and owns the round
 * meters. Detection lives IN the binding on the already-riding tick read (frame-loop
 * residency law); this lane is mint+count only — the {@link AutofireTargetLane} counter
 * idiom without the poll half.
 *
 * <p>Why the event exists: the Paladin PD
 * script det never crosses the wire classifiably — replica hulls ride
 * {@code CollisionClass.NONE} so the client-local {@code PaladinEffect} gate can never
 * open on a replica, and where the det crosses at all it is a content-LESS PROJECTILE
 * snapshot row (script dets carry no firing weapon), un-keyable on the replaceable lane.
 * So the server names the det explicitly; the client presents FX-only (damage stays
 * wire-authoritative).
 *
 * <p>Rides the compose SEPARATE from effectiveEdges like the split detector: never
 * through the OnFireTruthMerger (not a discharge truth-source) and never through
 * dispatchDischargeEdges (no decl FIFO). Volume is trivial (~408 candidate dets over a
 * 49518-tick reel) — per-emit INFO rows are affordable (missile_split law).
 */
final class PaladinDetLane {

    private int emitted;
    private int skipUnknown;

    /**
     * Per-tick mint off the binding's side channel; the channel follows the
     * replaced-at-start law (empty/zero on a failed read, never stale), so this lane
     * accumulates without double-count risk. Returns this tick's {@code PALADIN_DET}
     * events (empty when no det was born).
     */
    List<GameplayEdgeEvent> observe(
            long simTick,
            List<EngineCombatBinding.PaladinDet> bornDets,
            int skipUnknownThisTick) {
        skipUnknown += skipUnknownThisTick;
        if (bornDets == null || bornDets.isEmpty()) {
            return List.of();
        }
        List<GameplayEdgeEvent> out = new ArrayList<>(bornDets.size());
        for (EngineCombatBinding.PaladinDet det : bornDets) {
            out.add(GameplayEdgeEvent.paladinDet(
                    simTick, det.sourceWireId(), det.mountIndex(), det.x(), det.y()));
            emitted++;
        }
        return out;
    }

    /** Cumulative det emits this round (the {@code paladin_det_emitted=} meter). */
    int emitCount() {
        return emitted;
    }

    /** Cumulative whitelist-law skips this round ({@code paladin_det_skip_unknown=}):
     *  new {@code DamagingExplosion}s the binding counted+skipped — non-Paladin
     *  fingerprint, unresolvable source, non-finite pose, or unreadable probe. */
    int skipUnknownCount() {
        return skipUnknown;
    }
}
