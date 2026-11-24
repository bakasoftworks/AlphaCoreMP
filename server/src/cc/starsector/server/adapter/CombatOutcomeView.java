package cc.starsector.server.adapter;

/**
 * Project-owned read model for the combat-wide OUTCOME / end-condition of a
 * single combat: whether the match has been decided and, if so,
 * which side won. This is the match-lifecycle tier a dedicated <em>combat</em>
 * server needs to end a session and declare a winner — categorically distinct
 * from every per-ship read tier (motion/durability/flux/weapon/system/profile/
 * maneuver), which describe ships, not the match.
 *
 * <p>The engine objects and any engine-internal classes stay inside the bridge. This is
 * a plain {@code (winningSideId, combatOver)} read model; two producers populate it
 * behind the same contract (see the headless caveat): the engine-field read
 * {@code EngineCombatBinding.combatOutcomeOrNull()} and the roster-derived
 * {@code EngineCombatBinding.combatOutcomeFromRosterOrNull()}.
 *
 * <p><b>Headless caveat
 * (the load-bearing RE finding).</b> The engine's own end judge is not the headless
 * server's outcome source, so a headless server uses neither engine signal as its
 * authoritative producer:
 * <ul>
 *   <li>{@code isCombatOver()} returns {@code true} only once the engine's own end check
 *       has latched the combat-over flag <em>and</em> the end-of-combat {@code Fader} has
 *       reached full brightness; that fader advances inside the engine's own step, so it
 *       does reach full brightness headless.
 * The real-engine serve holds the engine's
 *       {@code setDoNotEndCombat(true)}, as every client does, so that end check never runs
 *       and {@link #combatOver()} reads {@code false} there — end on
 *       {@link #decided()}, not {@link #combatOver()}.</li>
 *   <li>{@code getWinningSideId()} is computed from {@code CombatFleetManager} deployment
 *       bookkeeping that the headless roster install deliberately empties, so
 *       headless it latches a <em>spurious early winner</em> blind to the ships actually
 *       fighting. It is retained only for diagnostic/parity.</li>
 * </ul>
 * The authoritative headless verdict is therefore <em>derived server-side</em>
 * from the server's own roster — which {@code FleetSide} still has at least one live ship
 * ({@code RosterCombatOutcome}) — and surfaced through this same {@link #winningSideId()} /
 * {@link #decided()} contract. The values mean the same thing either way: {@code -1} while
 * undecided, a concrete {@code FleetSide} ordinal once one side is eliminated.
 */
public interface CombatOutcomeView {

    /**
     * The engine's own combat-over flag, as reported by the public
     * {@code CombatEngineAPI.isCombatOver()}: the engine's own end judge, not the
     * headless outcome source (see the type Javadoc), and {@code false} on the real-engine
     * serve, which holds {@code setDoNotEndCombat(true)} so that judge never runs.
     * Exposed for diagnostics / parity with the engine; prefer
     * {@link #decided()} for the authoritative match-end signal.
     */
    boolean combatOver();

    /**
     * The winning side id. {@code -1} (more generally {@code < 0}) while the combat is
     * undecided; a concrete {@code FleetSide} ordinal (e.g. {@code 0} or {@code 1}) once
     * one side is eliminated. On the headless path this is the roster-derived verdict
     * — the side that still has live ships — not the engine's
     * {@code getWinningSideId()} field (which is unreliable headless; see the type doc).
     */
    int winningSideId();

    /**
     * True once the combat has been decided (a side id has been assigned):
     * {@code winningSideId() >= 0}. This is the render-free match-end signal a
     * headless server should use to end the session and declare the winner,
     * <em>not</em> {@link #combatOver()}.
     */
    default boolean decided() {
        return winningSideId() >= 0;
    }
}
