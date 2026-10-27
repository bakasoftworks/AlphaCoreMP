package cc.starsector.client;

/**
 * Omega SHARD FADE-IN law: the pure thresholds of vanilla's {@code ShardSpawner.ShardFadeInPlugin}
 * that
 * {@link FractureFamilyCoordinator} replays on each shard child replica.
 *
 *
 * <p>WHY: the client mirrored only the parent's fade-OUT, so a freshly split shard replica was
 * collidable and hull-damageable at once while the server's body sat in vanilla's intangible +
 * {@code ShardSpawnerInvuln} ×0 window (one measured shard, entity 56: 8 local rows, 1878.9 hp, landed
 * while the server was intangible). The law: for the first 75 % of the 4 s fade the shard takes
 * {@code CollisionClass.NONE} + hull-damage-taken ×0 (vanilla re-asserts both every frame,
 * and lifts them only past 75 %); alpha = progress; the blue jitter
 * rides the fade; everything restored past 4 s. Wire truth outranks the timer: a wire hull
 * or armor drop means the server body is already tangible, so the local window ends at once.
 *
 * <p>Client-side by the client-derivation rule (the lift time is a vanilla constant, zero wire); NEVER CONCEAL (the
 * local body takes the state the server body has, nothing capped); no latency (the ramp is
 * vanilla's own presentation). Engine-free so {@code ShardFadeInLawSmoke} runs without game jars.
 */
final class ShardFadeInLaw {

    /** {@code ShardSpawner.SPAWN_TIME} (the fade-in {@code fadeInTime}). */
    static final float FADE_SEC = 4.0f;
    /** Progress past which collision + hull damage are restored (a strict greater-than). */
    static final float LIFT_PROGRESS = 0.75f;
    /** Vanilla jitter copies and max range bonus. */
    static final int JITTER_COPIES = 25;
    static final float JITTER_MAX_RANGE_BONUS = 50f;
    /** A wire hull move smaller than this is not a drop (the AWSD hull rides a u8 fraction of max
     *  hull; one cell is maxHp/255 ≥ 0.5 hp for every hull ≥ 128 hp). */
    static final float HULL_DROP_EPS_HP = 0.5f;
    /** A local armor-grid move smaller than one cell of one slot is not a drop (the
     *  {@code ArmorCellLane.CELL_EPS_FRAC} bite epsilon, {@code ArmorReconcileLane} bite detector). */
    static final float ARMOR_DROP_EPS_CELL_FRAC = 1f / 255f;

    /** No fade: the child sits near no omega hulk (counted as {@code omega_unattributed}). */
    static final int SKIP_UNATTRIBUTED = 0;
    /** No fade: a bay-born fighter (a carrier launched it; vanilla's shard wings have none). */
    static final int SKIP_BAY_BORN = 1;
    /** Fade: the first child of this split (the parent's fade-out begins on the same frame). */
    static final int BEGIN = 2;
    /** Fade: a later sibling of a split whose parent is already dissolving. */
    static final int BEGIN_SIBLING = 3;

    private ShardFadeInLaw() {
    }

    /**
     * The begin verdict for one appended child. Every
     * attributed shard fades — vanilla adds one fade-in plugin per shard on the split frame,
     * so the parent's already-running dissolve never exempts a sibling. A carrier id
     * of any sign but zero is a bay launch (wire ids are signed longs).
     */
    static int beginVerdict(boolean attributed, long carrierWireId, boolean parentAlreadyDissolving) {
        if (!attributed) {
            return SKIP_UNATTRIBUTED;
        }
        if (carrierWireId != 0L) {
            return SKIP_BAY_BORN;
        }
        return parentAlreadyDissolving ? BEGIN_SIBLING : BEGIN;
    }

    /** Fade progress in [0, 1] (elapsed over the fade-in time, delay 0, capped at 1). */
    static float progress(float elapsedSec) {
        if (!(elapsedSec > 0f)) {
            return 0f;
        }
        float p = elapsedSec / FADE_SEC;
        return p > 1f ? 1f : p;
    }

    /** True = intangible + hull ×0 this frame (vanilla sets both every frame, then restores only
     *  when {@code progress > 0.75F}; exactly 0.75 is still shielded). */
    static boolean shielded(float progress) {
        return !(progress > LIFT_PROGRESS);
    }

    /** The shard's alpha: the fade progress itself, clamped to [0, 1]. */
    static float alpha(float progress) {
        return progress < 0f ? 0f : (progress > 1f ? 1f : progress);
    }

    /** Jitter intensity: up to 1 at half-fade, back to 0 at the end. */
    static float jitterLevel(float progress) {
        return progress < 0.5f ? progress * 2f : (1f - progress) * 2f;
    }

    /** Jitter range bonus {@code (1 - progress) × 50}. */
    static float jitterRangeBonus(float progress) {
        return (1f - progress) * JITTER_MAX_RANGE_BONUS;
    }

    /** The fade ends once {@code elapsed > fadeInTime}. */
    static boolean done(float elapsedSec) {
        return elapsedSec > FADE_SEC;
    }

    /** NaN-aware running max of a baseline ({@code NaN} = nothing seen yet). */
    static float raiseBaseline(float baseline, float value) {
        if (value != value) {
            return baseline;
        }
        return (baseline != baseline || value > baseline) ? value : baseline;
    }

    /**
     * Wire truth outranks the timer. true when the wire hull
     * fell below its window baseline, or the child's armor grid (which the armor reconcile lanes
     * pull to the wire, while NONE keeps local shots off it) fell below its baseline by more than
     * one cell of one slot. A {@code NaN} baseline or reading = no data = no verdict.
     */
    static boolean wireDropEnds(float hullBaseline, float wireHull, float armorBaseline,
            float armorNow, float maxArmorInCell) {
        if (hullBaseline == hullBaseline && wireHull == wireHull
                && wireHull < hullBaseline - HULL_DROP_EPS_HP) {
            return true;
        }
        if (armorBaseline == armorBaseline && armorNow == armorNow && maxArmorInCell == maxArmorInCell) {
            float eps = Math.max(0f, maxArmorInCell) * ARMOR_DROP_EPS_CELL_FRAC;
            return armorNow < armorBaseline - eps;
        }
        return false;
    }
}
