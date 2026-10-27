package cc.starsector.server.protocol;

import cc.starsector.server.adapter.ShipCrView;
import java.util.Objects;

/**
 * One ship's combat-readiness (CR) clock as replicated to clients: the
 * CR tier (schema v14), read off the bridge's {@link ShipCrView}.
 *
 * <p>Why this is carried at all: the CR/PPT clock only advances under the
 * engine's in-combat roster scan, and a predict client's LOCAL engine scans a
 * different roster (locked carriers, absent seats) — the local clock freezes at
 * full PPT while the authoritative ship fatigues. Carrying
 * {@code cr} plus the RAW deployed-seconds accumulator lets the client pin its
 * local readouts through the engine's own {@code setCurrentCR} /
 * {@code setTimeDeployed} fields — the exact fields BOTH HUD readouts consume.
 * {@code timeDeployedSec} is deliberately the raw
 * accumulator, never "PPT remaining": the HUD subtracts from its local peak
 * itself. {@code peakSec} is diagnostic-grade context (the HUD derives peak
 * from LOCAL stats); never-lose-CR hulls carry effective peaks ≥ 1e5 s by
 * design, exact here as f32 and SATURATED on the quantized AWSD lane (P0 D1).
 *
 * <p>Wire layout: entityId u64 / cr f32bits / timeDeployedSec f32bits /
 * peakSec f32bits — 20 B per record. The tier is SPARSE per-entity like the
 * phase-cloak tier (an absent entity means "unchanged", no sentinel record).
 * Keyed by {@code entityId} to the same stable network identity as the ship's
 * {@link AuthoritativeEntitySnapshot}; like the other snapshot tiers it carries
 * only scalar, version-independent state and never an engine-internal class.
 */
public record ShipCrSnapshot(long entityId, float cr, float timeDeployedSec, float peakSec,
        int fighterReplacementPct) {

    /**
     * {@link #fighterReplacementPct()} sentinel: no fighter-rate read for this record. Every
     * AWSS-decoded record carries it (the AWSS v14 CR section is 20 B cr/dep/peak only - the
     * percent is AWSD-keyframe-only, so the AWSS wire never grows).
     */
    public static final int NO_FIGHTER_REPLACEMENT_PCT = ShipCrView.NO_FIGHTER_REPLACEMENT_PCT;

    public ShipCrSnapshot {
        if (entityId <= 0L) {
            throw new IllegalArgumentException("entityId must be > 0; got " + entityId);
        }
        if (!Float.isFinite(cr) || cr < 0f) {
            // Lenient upper bound on purpose: engine CR lives in [0,1] but the pin
            // consumer clamps locally; only corrupt (negative/non-finite) values die here.
            throw new IllegalArgumentException("cr must be finite and >= 0; got " + cr);
        }
        if (!Float.isFinite(timeDeployedSec) || timeDeployedSec < 0f) {
            throw new IllegalArgumentException(
                    "timeDeployedSec must be finite and >= 0; got " + timeDeployedSec);
        }
        if (!Float.isFinite(peakSec) || peakSec < 0f) {
            throw new IllegalArgumentException("peakSec must be finite and >= 0; got " + peakSec);
        }
        if (fighterReplacementPct != NO_FIGHTER_REPLACEMENT_PCT
                && (fighterReplacementPct < 0 || fighterReplacementPct > 100)) {
            throw new IllegalArgumentException(
                    "fighterReplacementPct must be 0..100 or NONE; got " + fighterReplacementPct);
        }
    }

    /**
     * Build the per-ship CR snapshot from the project-owned {@link ShipCrView}
     * read model, keyed to the ship's already-resolved network {@code entityId}
     * (distinct from the view's within-tick repository {@code shipId()}).
     */
    public static ShipCrSnapshot fromView(ShipCrView view, long entityId) {
        Objects.requireNonNull(view, "view");
        return new ShipCrSnapshot(entityId, view.currentCr(), view.timeDeployed(), view.peakCr(),
                view.fighterReplacementPct());
    }

    /**
     * Reconstruct from decoded wire fields, fail-closed: corrupt frames (negative
     * scalars, non-finite bits, non-positive entityId) are rejected by the
     * canonical constructor rather than carried. AWSS carries no fighter-rate field, so the
     * record is minted with {@link #NO_FIGHTER_REPLACEMENT_PCT}.
     */
    public static ShipCrSnapshot fromWire(long entityId, float cr, float timeDeployedSec, float peakSec) {
        return new ShipCrSnapshot(entityId, cr, timeDeployedSec, peakSec, NO_FIGHTER_REPLACEMENT_PCT);
    }
}
