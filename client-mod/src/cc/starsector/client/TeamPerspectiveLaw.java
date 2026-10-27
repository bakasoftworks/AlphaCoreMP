package cc.starsector.client;

/**
 * The client's team-PERSPECTIVE law ({@code /switchteams}): the ONE mapping between the
 * wire's ABSOLUTE combat sides ({@code fleetSide} 0/1 on every AWSD row, 100 = neutral) and this
 * client's RELATIVE presentation. The own hull is ALWAYS local engine owner {@code sideAuthority()}
 * (= 0): {@code CombatEngine.getPlayerId()} is a hard-coded 0 and friend/foe colour, radar, hull
 * bars and the player fleet manager all key on owner 0,
 * so a team switch can never be "own ship becomes owner 1" — it is a
 * re-siding of everything ELSE relative to the own team's new absolute side.
 *
 * <p>Provenance: before this law the plugin computed {@code abs == ownDescSide ? 0 : 1} at four
 * places and applied a RAW absolute wire side at four more (sourceless missiles/motes, the
 * victory banner, the CP-rate managers). Every site now goes
 * through here, and {@code ownDescSide} is LIVE (re-derived each frame from the own row's wire
 * {@code fleetSide} — the server-settable value — seeded by the descriptor).
 *
 * <p>Pure + static: {@code SyncDefectLawSelftest} pins it through {@link SyncDefectLawProbe}.
 */
final class TeamPerspectiveLaw {

    /** The wire's neutral side ({@code FleetSide.NO_SIDE} ordinal on the wire). */
    static final int NEUTRAL = 100;

    private TeamPerspectiveLaw() {
    }

    /** True for the two fighting sides; false for neutral, {@code UNKNOWN_FLEET_SIDE} and garbage. */
    static boolean isCombatSide(int abs) {
        return abs == 0 || abs == 1;
    }

    /** The registry's RELATIVE side for an absolute wire side: 0 = own team (ally), 1 = the other team. */
    static int relSide(int abs, int ownAbs) {
        return abs == ownAbs ? 0 : 1;
    }

    /**
     * The LOCAL engine owner for an absolute wire side — own team ⇒ {@code sideAuthority}, the
     * other team ⇒ its opposite; a non-combat value (neutral 100, unknown) passes through
     * UNMAPPED so the engine default the site always picked for it is unchanged.
     */
    static int localOwner(int abs, int ownAbs, int sideAuthority) {
        if (!isCombatSide(abs)) {
            return abs;
        }
        return abs == ownAbs ? sideAuthority : 1 - sideAuthority;
    }

    /**
     * The own team's NEW absolute side when the live own row disagrees with the current one,
     * else {@code -1}. An absent / neutral / unknown live value is never a change (the own row
     * is absent while the seat is dead; a pre-v5 frame carries no allegiance).
     */
    static int ownTeamChangeOrNeg(int liveOwnAbs, int ownAbs) {
        return isCombatSide(liveOwnAbs) && liveOwnAbs != ownAbs ? liveOwnAbs : -1;
    }

    /** {@code TeamChangeLane}'s bounded-retry predicate, pure: an attempt is
     *  still PENDING when it threw (the hull pair is written at {@code RemoteTeamFlip} step 2 of
     *  5, so a throw in steps 3-5 leaves {@code getOwner() == wantOwner} with the wings / fleet
     *  member / target sweep never run) or when the owner simply did not take. Lives HERE, not on
     *  the lane, because the game-free selftest cannot load a class that names ShipAPI. */
    static boolean reownPending(boolean threw, int owner, int wantOwner) {
        return threw || owner != wantOwner;
    }
}
