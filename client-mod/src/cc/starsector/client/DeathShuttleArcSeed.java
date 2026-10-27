package cc.starsector.client;

import com.fs.starfarer.api.combat.CombatEngineAPI;

/**
 * Seeds the engine's lazily-minted death shuttle ("Command Shuttle",
 * {@code CombatEngine.getFakePlayerShip()}, a bare {@code shuttlepod_xfer}) with ONE selected
 * EMPTY LINKED weapon group at the own-death edge, BEFORE the player-ship flip.
 *
 * <p>WHY: the combat state syncs its playerShip from {@code CombatEngine.getPlayerShip()} every
 * frame; at own death the engine's real-player field nulls and the getter falls back to the
 * fake shuttle, so CombatState.setPlayerShip runs and feeds the
 * arc renderer the NEW ship's {@code getSelectedGroup()}. A BARE pod feeds null — a designed
 * no-op (the renderer replaces the held group only on a NON-null feed) — so the renderer
 * keeps drawing the DEAD hull's autofire-arc overlay on the wreck for the whole podded window;
 * vanilla self-heals only at the pod-LANDING edge, unreachable in our match (the
 * death-arc triage). A seeded non-null EMPTY group swaps in through the engine's own
 * vanilla fade idiom (~0.3s) and then draws NOTHING (zero weapons —
 * the draw loop iterates an empty list).
 *
 * <p>LAWS (byte-verified against the engine): the group MUST stay LINKED — an empty
 * ALTERNATING group crashes at WeaponGroup.findNextWeaponFrom ({@code weapons.get(0)});
 * the bare ctor mints LINKED, so never call setType. Ship.addGroup is
 * public and self-sets the private {@code selected} field when it is null, so no
 * SELECT_GROUP command is needed (CombatState.giveCommand drops commands for pods anyway).
 * The seed must run PRE-FLIP (killOwn, before the body goes): the combat state's index-change
 * refresh CANNOT bootstrap post-flip — its gate needs the renderer's held group to be a member
 * of the pod's own group list, and the dead hull's group indexOf is -1 there.
 *
 * <p>SCOPE: the DEATH direction only — /transfer's real B-machine transit pod is a different
 * entity and stays bare, so its hull-to-pod transient stays vanilla-identical (by
 * design). The {@code getFakePlayerShip()} call runs the engine's OWN lazy mint
 * — vanilla runs the same lines at this same edge; re-seed is guarded
 * on {@code getGroups().isEmpty()} (the shuttle persists per engine, later deaths reuse it).
 * Known wire delta, verified benign both sides: while podded the own sample emitted
 * {@code liveSelectedGroup=0} instead of DESELECTED(-2); the server's own 0-group pod turns
 * SELECT_GROUP 0 into {@code selected=null} — the identical end state. Today
 * the capture skips the group read while {@code ownKilled}, so the dead window
 * sends the frozen last-live selection; the engine bounds-checks it — same end state.
 */
final class DeathShuttleArcSeed {

    private DeathShuttleArcSeed() {
    }

    /**
     * Seed the death shuttle if it is still bare. Fail-quiet — a seed failure must never break
     * the death presentation it precedes; one log line marks seed/skip/death for the watch.
     */
    static void seedIfBare(CombatEngineAPI engineApi) {
        try {
            com.fs.starfarer.combat.entities.Ship shuttle =
                    ((com.fs.starfarer.combat.CombatEngine) engineApi).getFakePlayerShip();
            if (shuttle == null) {
                PilotWatchLog.info("cc-death-shuttle-seed SKIP: engine minted no shuttle");
                return;
            }
            if (!shuttle.getGroups().isEmpty()) {
                return; // already seeded this engine (a later death reuses the shuttle)
            }
            shuttle.addGroup(new com.fs.starfarer.combat.systems.WeaponGroup());
            PilotWatchLog.info("cc-death-shuttle-seed: empty LINKED group seeded+selected on"
                    + " the death shuttle (overlay swaps off the wreck at the flip)");
        } catch (Throwable t) {
            PilotWatchLog.info("cc-death-shuttle-seed DEAD: " + t.getClass().getSimpleName()
                    + (t.getMessage() != null ? ": " + t.getMessage() : ""));
        }
    }
}
