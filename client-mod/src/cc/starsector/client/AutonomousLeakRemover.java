package cc.starsector.client;

import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;

/**
 * ENFORCEMENT rider on the {@link LocalFireCensus} class-B census: a
 * weapon-backed, non-presented projectile from a replay-driven source is a local damager the server
 * never authorized, and this lane removes it at first sight. Own file because the census class's
 * contract is instrumentation-only (the new-lane law) and removal changes what the sim
 * shows.
 *
 * <p><b>Why removal instead of better suppression:</b> the per-frame hold
 * ({@code reassertReplayDrivenHold}) cannot reach the engine's internal sub-steps — the W-FASTTIME
 * hazard: a time-dilating hull's {@code Ship.advance} consumes the one-frame no-fire flags on its
 * first sub-step, so a committed burst can mint one real projectile before the next frame's
 * truncation. One run minted 246 this way, every one from the phase-hull
 * {@code grendel_Hypervelocity} peer replica (attribution [T] via the {@code autodis} src
 * hashes; the dilation trigger is [I] — best fit for why only that hull leaks). There is no public
 * per-sub-step seam to assert the hold on. [STALE → there is one: the replica's
 * own {@code ShipAIPlugin.advance}, which the engine runs per fast-time sub-step BEFORE that pass's
 * {@code Ship.advance} ({@link SubstepHoldAI} re-asserts the hold there). This lane stays as the
 * enforcement backstop + census invariant; the proving bar is removed → 0 with the gate on.]
 *
 * <p>Riders: (a) a removed leak is set {@code CollisionClass.NONE}
 * BEFORE {@code removeEntity} — the removal only queues ({@code executeRemoves}) AFTER
 * this frame's collision pass, and a NONE mover finds no pair rule,
 * so the leak gets NO collision pass; (b) {@code removed_did_damage} counts {@code didDamage()} at
 * drain time — the drain runs BEFORE this frame's only collision pass, so it reads damage done before
 * sight (expected 0; &gt; 0 = a late-seen class).
 *
 * <p><b>Why this is not a doctrine backstop:</b> the sync-gap doctrine bans periodic state that
 * masks a missed SERVER event; this deletes a CLIENT-local invention — opposite polarity. The
 * census counts every leak BEFORE this lane removes it, so the {@code nonsystem==0} tripwire keeps
 * its meaning; the removed count is emitted beside it and the grader can check
 * {@code removed == nonsystem + system_hinted + spec_unknown} while the knob is ON (a shortfall
 * means a leak SURVIVED). The meter also carries {@code leak_remove_gate=on|off} (the
 * spawn-press-gate idiom) so graders can tell a count-only A/B run
 * (gate=off, removed stays 0) from a broken removal — without it the invariant is ungradeable. Divergence self-heals: replica HP is snapshot-driven, so a ≤1-frame local
 * hit by the leaked shell is overwritten by the next authoritative state.
 *
 * <p>Knob {@code cc.predict.leakRemove} = {@code true|false}, default ON ({@code false} restores
 * the earlier count-only behavior for A/B). Removal is deferred to {@link #drain} so the caller's
 * live {@code getProjectiles()} iteration is never mutated mid-walk.
 */
final class AutonomousLeakRemover {

    private static final boolean LEAK_REMOVE_ENABLED =
            !"false".equals(System.getProperty("cc.predict.leakRemove"));

    private final java.util.ArrayList<DamagingProjectileAPI> pending =
            new java.util.ArrayList<DamagingProjectileAPI>(4);
    private int removed; // predict_event_rebase_replay_ship_discharge_removed
    private int removedDidDamage; // ..._discharge_removed_did_damage

    /** The counted leak object, called at the census site (inside the projectile walk). */
    void onLeak(DamagingProjectileAPI p) {
        if (LEAK_REMOVE_ENABLED && p != null) {
            pending.add(p);
        }
    }

    /** After the projectile walk: remove everything queued this frame. */
    void drain(CombatEngineAPI engine) {
        if (pending.isEmpty()) {
            return;
        }
        for (DamagingProjectileAPI p : pending) {
            try {
                if (p.didDamage()) {
                    removedDidDamage++; // Damage done BEFORE sight (expected 0)
                }
                try {
                    p.setCollisionClass(CollisionClass.NONE); // No collision pass
                } catch (Throwable noneFailed) {
                    FallbackAnnounce.announceOnce("leak-remove-none-failed", "setCollisionClass(NONE) threw "
                            + noneFailed.getClass().getSimpleName() + " -> the removed leak keeps one collision pass");
                }
                engine.removeEntity(p);
                removed++;
            } catch (Throwable ignore) {
                // fail-soft: an unremovable entity degrades to the earlier count-only behavior
                // the unsynced local damager SURVIVES — systemic once it starts.
                FallbackAnnounce.announceOnce("s3-leak-remove-failed", "removeEntity threw "
                        + ignore.getClass().getSimpleName() + " -> local damager survives");
            }
        }
        pending.clear();
    }

    /** Rides the {@code eventReplay-meter} line beside the census tokens it enforces. */
    String meterToken() {
        return " predict_event_rebase_replay_ship_discharge_removed=" + removed
                + " predict_event_rebase_replay_ship_discharge_removed_did_damage=" + removedDidDamage
                + " predict_event_rebase_leak_remove_gate=" + (LEAK_REMOVE_ENABLED ? "on" : "off");
    }
}
