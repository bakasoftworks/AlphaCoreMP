package cc.starsector.client;

/**
 * For the "zero fragment-weapon shots from any Threat ship" defect — the
 * game-free admission law of the four {@code CcWrap*FragmentMissileEffect} wrappers (the
 * engine-facing helpers live in {@link ReplicaPresentedFireRelay}; this class stays free of
 * engine types so the {@code SyncDefectLawSelftest} JVM — no game jars — can load it).
 *
 * <p><b>Root cause as observed.</b> The headless server FIRES fragment weapons (census
 * probe: {@code fragswarm-fires tick=11700 fragmentFiresTotal=819}, every non-hive fragment
 * mount {@code forced=0/m} with a full swarm). The loss is client-side and total: every fragment
 * carrier missile rides {@code "sprite":"graphics/fx/empty.png"} (vanilla
 * {@code data/weapons/proj/{kinetic_fragments,seeker_fragment,unstable_fragment,devouring_swarm}_missile.proj})
 * — the carrier is INVISIBLE by design; the shot's whole visual is the {@code RoilingSwarmEffect}
 * that {@code BaseFragmentMissileEffect.onFire} builds around it.
 * The wire-minted carrier is spawned by
 * {@code MissilePoseLane.presenceAdopt} through {@code CombatEngine.spawnProjectile}, which
 * never runs {@code onFire} — so the
 * client showed a bare empty sprite flying the server's track. Fix = relay the presented
 * carrier to OUR wrapper's {@code onFire} ({@link ReplicaPresentedFireRelay}, treated replica
 * only), whose replica branch runs vanilla's swarm build with the sim doors shut.
 *
 * <p><b>Doors shut on the replica branch</b> (each wrapper cites its own): fighter HP loss per
 * transferred member (a local hull author + kill), the kinetic
 * {@code reportFragmentHit} {@code applyDamage},
 * the devouring {@code onHit} disintegrator (
 * hull ticks + heal-carry {@code addMember}), the unstable proximity-fuse AI
 * (a local {@code explode}). Vanilla's own
 * {@code removeEntity(projectile)} on a null pick is made unreachable: the
 * branch runs only when the replica swarm has an active member, and the pick falls back to any
 * member.
 */
final class FragmentCarrierPresentLaw {

    /** Census tag — {@code wsg census} row {@code fragmentcarrier} frames/doorShut/visual. */
    static final String TAG = "fragmentcarrier";

    private FragmentCarrierPresentLaw() {
    }

    /**
     * The replica branch presents a carrier only when the shooter is a treated replica whose
     * local swarm holds at least one active member (a pick source for vanilla's
     * {@code pickPrimaryFragment}); otherwise the bare carrier stands untouched (never removed).
     */
    static boolean presentable(boolean treatedReplica, boolean swarmPresent, int activeMembers) {
        return treatedReplica && swarmPresent && activeMembers > 0;
    }
}
