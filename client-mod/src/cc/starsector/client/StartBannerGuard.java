package cc.starsector.client;

import com.fs.starfarer.api.combat.CombatEngineAPI;

/**
 * Start-banner suppression — the seam map.
 *
 * <p>The engine shows a one-shot native message — "The enemy fleet is defeated. Press ESCAPE
 * to end the battle at any time." — from the top of the {@code CombatState} main loop,
 * gated ONLY by {@code isEnemyInFullRetreat()},
 * which a wave-empty enemy side satisfies at frame 0: deployed empty vacuously passes the
 * orders loop, and empty enemy reserves complete the check. {@code setDoNotEndCombat}
 * does NOT cover it — that gates the separate end-checker — which is why
 * the flash survived the end-of-combat drive in a live run.
 *
 * <p>The fix is the engine's own public lever: {@code combatNotOverFor > 0} short-circuits
 * {@code isEnemyInFullRetreat()}. The field self-decrements by dt each engine
 * advance and clamps at 0, so the plugin seeds it at init ({@link #seed} — the
 * CombatState guard runs BEFORE any plugin advance, so only an init-time seed covers frame 0)
 * and tops it up per frame ({@link #topUp}) until the MOVR verdict drives the real end; the
 * residual (≤ {@link #TOPUP_SEC}) drains during MatchOverLane's 1f whiteout delay, leaving the
 * driven end flow untouched. Suppression must span the WHOLE registry match, not just roster
 * init: the wave-fed fortress re-empties side 1 between waves, and the one-shot would burn
 * wrongly on any such stretch.
 *
 * <p>Rider effect, honest for a live served match: while active, the ESC exit-confirm picks
 * its "battle not decided" dialog variant instead of "enemy defeated"
 * (the only other readers of the check). No other engine consumer exists.
 */
final class StartBannerGuard {

    /** Generous init→first-advance cover; any excess is consumed by the per-frame top-up law. */
    private static final float SEED_SEC = 5f;

    /** Per-frame refresh floor — small, so the residual after the MOVR drive drains inside the
     *  1f whiteout delay. */
    private static final float TOPUP_SEC = 1f;

    private StartBannerGuard() {
    }

    /** Frame-0 cover, called where init engages the hold (registry engage + connect-fail hold-open). */
    static void seed(CombatEngineAPI engine) {
        engine.setCombatNotOverForAtLeast(SEED_SEC);
    }

    /** Per-frame refresh while the suppression window is live (caller owns the gate condition). */
    static void topUp(CombatEngineAPI engine) {
        engine.setCombatNotOverForAtLeast(TOPUP_SEC);
    }
}
