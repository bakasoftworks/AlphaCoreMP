package cc.starsector.client;

import java.util.Set;

/**
 * The PUBLIC selftest bridge over the package-private sync-defect laws
 * ({@link SwarmSpriteGate#wantsHide}, {@link DwellerHullLaw#isDwellerHull},
 * {@link WeaponRedisableBackstop}); the selftest tree lives in another package and compiles
 * against the jar. Game-free by construction (no engine type crosses); never called in-game.
 */
public final class SyncDefectLawProbe {

    private final WeaponRedisableBackstop backstop = new WeaponRedisableBackstop();

    public boolean swarmWantsHide(Set<String> hullTags, String baseHullId) {
        return SwarmSpriteGate.wantsHide(hullTags, baseHullId);
    }

    // ---- /switchteams: the team-perspective law ({@link TeamPerspectiveLaw}) ----

    public boolean teamIsCombatSide(int abs) {
        return TeamPerspectiveLaw.isCombatSide(abs);
    }

    public int teamRelSide(int abs, int ownAbs) {
        return TeamPerspectiveLaw.relSide(abs, ownAbs);
    }

    public int teamLocalOwner(int abs, int ownAbs, int sideAuthority) {
        return TeamPerspectiveLaw.localOwner(abs, ownAbs, sideAuthority);
    }

    public int teamOwnChangeOrNeg(int liveOwnAbs, int ownAbs) {
        return TeamPerspectiveLaw.ownTeamChangeOrNeg(liveOwnAbs, ownAbs);
    }

    /** The bounded re-owner retry predicate. */
    public boolean teamReownPending(boolean threw, int owner, int wantOwner) {
        return TeamPerspectiveLaw.reownPending(threw, owner, wantOwner);
    }

    public boolean isDwellerHull(Set<String> hullTags, String baseHullId) {
        return DwellerHullLaw.isDwellerHull(hullTags, baseHullId);
    }

    // ---- the construction-carrier size law ({@link ConstructionCarrierScaleLaw}) ----

    /** Which vanilla {@code launchSwarm} branch a {@code wireMax / localMax} ratio names (0 = unknown, 1..4 = frigate..capital). */
    public int constructionCarrierScaleClass(float hpRatio) {
        return ConstructionCarrierScaleLaw.carrierScaleClass(hpRatio);
    }

    public float constructionCarrierCollisionMult(int scaleClass) {
        return ConstructionCarrierScaleLaw.carrierCollisionMult(scaleClass);
    }

    public float constructionCarrierRadiusMult(int scaleClass) {
        return ConstructionCarrierScaleLaw.carrierRadiusMult(scaleClass);
    }

    public long backstopHoldTicks() {
        return WeaponRedisableBackstop.HOLD_TICKS;
    }

    public boolean backstopAllow(Object hull, int mountIndex, long simTick) {
        return backstop.allowDisable(hull, mountIndex, simTick);
    }

    /** Wire CLEAR edge on the mount, then a disable on the same tick — must apply. */
    public boolean backstopClearThenAllow(Object hull, int mountIndex, long simTick) {
        backstop.onWireClear(hull, mountIndex);
        return backstop.allowDisable(hull, mountIndex, simTick);
    }

    public long backstopped() {
        return backstop.backstopped();
    }

    public boolean backstopConfessOnce() {
        return backstop.confessOnce();
    }

    /** D2: the fragment-carrier replica-branch admission law. */
    public boolean fragmentCarrierPresentable(boolean treatedReplica, boolean swarmPresent, int activeMembers) {
        return FragmentCarrierPresentLaw.presentable(treatedReplica, swarmPresent, activeMembers);
    }

    /** D2: the everyFrame wrapper the client sweep installs for a vanilla effect class (null = none). */
    public String everyFrameWrapperFor(String vanillaClass) {
        return ClientEveryFrameEffectSwap.wrapperFor(vanillaClass);
    }

    /** D2: whether a vanilla effect class is on the audited-DORMANT (left vanilla) list. */
    public boolean everyFrameAuditedDormant(String vanillaClass) {
        return ClientEveryFrameEffectSwap.auditedDormant(vanillaClass);
    }

    /** The onHit wrapper the sweep writes into a projectile spec for a vanilla class (null = none). */
    public String onHitWrapperFor(String vanillaClass) {
        return ClientEveryFrameEffectSwap.onHitWrapperFor(vanillaClass);
    }

    /** The fresh-instance onHit decision (birth latch first, source treatment fallback). */
    public boolean onHitReplica(boolean latched, boolean sourceTreatedReplica) {
        return ReplicaEffectLatchLaw.onHitReplica(latched, sourceTreatedReplica);
    }

    /** Is a projectile custom-data value the replica birth latch? */
    public boolean boltReplica(Object customValue) {
        return ReplicaEffectLatchLaw.boltReplica(customValue);
    }

    /** Is a carrier swarm's {@code custom2} the replica latch? */
    public boolean swarmReplica(Object custom2) {
        return ReplicaEffectLatchLaw.swarmReplica(custom2);
    }

    public Object swarmLatch() {
        return ReplicaEffectLatchLaw.SWARM_LATCH;
    }

    /** The own-hull invariant's verdict name ({@code OK|MISMATCH|REFIT_FORK}). */
    public String ownHullVerdict(String live, String announced) {
        return OwnHullForkLaw.verdict(live, announced).name();
    }

    /** Is an id the engine's briefing-Refit save shape {@code mission_<id>_ship_<n>}? */
    public boolean engineRefitSaveId(String id) {
        return OwnHullForkLaw.isEngineRefitSaveId(id);
    }

    /** The unwired-own-fire verdict name ({@code WIRED_AUTOFIRE|WIRED_HELD|UNWIRED_AUTOPILOT|UNWIRED_OTHER}). */
    public String ownFireVerdict(boolean autofireNonSelected, float secondsSinceFireHeld, boolean autopilot) {
        return UnwiredOwnFireLaw.classify(autofireNonSelected, secondsSinceFireHeld, autopilot).name();
    }

    /** The discharge-site verdict — a SYSTEM-slot weapon is {@code WIRED_SYSTEM} before the fire-button law runs. */
    public String ownFireVerdict(boolean systemSlot, boolean autofireNonSelected, float secondsSinceFireHeld, boolean autopilot) {
        return UnwiredOwnFireLaw.classify(systemSlot, autofireNonSelected, secondsSinceFireHeld, autopilot).name();
    }

    /** Is a verdict name one the server's own twin never reproduces? */
    public boolean ownFireUnwired(String verdict) {
        return UnwiredOwnFireLaw.unwired(UnwiredOwnFireLaw.Verdict.valueOf(verdict));
    }

    /** The per-frame {@code secondsSinceFireHeld} step (held = 0; released adds the frame's sim dt). */
    public float ownFireTickSecondsSinceHeld(boolean fireHeld, float prior, float dtSeconds) {
        return UnwiredOwnFireLaw.tickSecondsSinceHeld(fireHeld, prior, dtSeconds);
    }

    /** Is the capped error line due at sim time {@code nowSec} given the last logged time ({@code NaN} = never)? */
    public boolean ownFireErrorDue(float nowSec, float lastErrorSec) {
        return UnwiredOwnFireLaw.errorDue(nowSec, lastErrorSec);
    }

    public float ownFireGraceSeconds() {
        return UnwiredOwnFireLaw.GRACE_SECONDS;
    }

    public float ownFireErrorEverySeconds() {
        return UnwiredOwnFireLaw.ERROR_EVERY_SECONDS;
    }

    public float ownFireNeverHeld() {
        return UnwiredOwnFireLaw.NEVER_HELD;
    }

    public float ownFireNoErrorYet() {
        return UnwiredOwnFireLaw.NO_ERROR_YET;
    }

    /** The wire's held level — mouse-0 OR (AI-driven AND the selected group non-idle). */
    public boolean ownFireDerivedHeld(boolean mouseHeld, boolean aiDriven, boolean selectedGroupFiring) {
        return AutopilotFireServeLaw.deriveHeld(mouseHeld, aiDriven, selectedGroupFiring);
    }

    /** The aim source name ({@code CURSOR|WEAPON_RAY|SHIP_TARGET}). */
    public String ownFireAimSource(boolean aiDriven, boolean rayFinite, boolean targetFinite) {
        return AutopilotFireServeLaw.aimSource(aiDriven, rayFinite, targetFinite).name();
    }

    /** The weapon ray projected to range ({@code [x, y]}), or null when unusable. */
    public float[] ownFireProjectRay(float x, float y, float angleDeg, float range) {
        return AutopilotFireServeLaw.projectRay(x, y, angleDeg, range);
    }

    /** Is the one-per-combat player notice due? */
    public boolean ownFireNoticeDue(boolean shown, int unwiredTotal) {
        return AutopilotFireServeLaw.noticeDue(shown, unwiredTotal);
    }
}
