package cc.starsector.client;

/**
 * The LATCH-AT-BIRTH law of
 * the {@code CcWrap*} replica wrappers, game-free so {@code SyncDefectLawSelftest} can load it.
 *
 * <p><b>Why a latch.</b> Two instance shapes made "is this a replica shot?" unsafe to re-ask:
 * <ul>
 * <li>The projectile {@code onHit} instance is FRESH per projectile (the engine creates one as
 * each projectile is constructed),
 * so any field the everyFrame instance
 * set in {@code onFire} is unset there (the devouring disintegrator door stayed OPEN).</li>
 * <li>The everyFrame instance is ONE per weapon, but a carrier swarm's {@code advance} reads it
 * frames later ({@code reportFragmentHit}, {@code swarmAdvance});
 * a later fire on the same mount flips a per-instance flag under the live swarm. The rift
 * wrapper re-decided per {@code advance} from the ship's treatment ledger; a flip while a bolt sat
 * in {@code fired} took vanilla's range-expiry {@code fireArc} → the client-minted mine.</li>
 * </ul>
 * So the decision is made ONCE, at the shot's birth ({@code onFire}), and written where the shot
 * carries it: the projectile's custom data ({@link #BOLT_KEY}) and, for a fragment carrier, the
 * carrier swarm's {@code custom2} ({@link #SWARM_LATCH}; vanilla never touches {@code custom2} —
 * {@code custom1} is the unstable fuse-AI slot,
 * which our wrappers never write). Every later read (advance / onHit / reportFragmentHit /
 * swarmAdvance) asks the latch, never the ledger or an instance field.
 */
final class ReplicaEffectLatchLaw {

    /** Projectile custom-data key: present with {@link Boolean#TRUE} iff the shot was born a
     *  treated-replica shot (set in the wrapper's {@code onFire}, own-ship shots never carry it). */
    static final String BOLT_KEY = "cc_replica_shot";

    /** The value a replica carrier's {@code RoilingSwarmEffect.custom2} holds. */
    static final Object SWARM_LATCH = "cc_replica_swarm";

    private ReplicaEffectLatchLaw() {
    }

    /** Is a projectile custom-data value ({@code getCustomData().get(BOLT_KEY)}) the replica latch? */
    static boolean boltReplica(Object customValue) {
        return Boolean.TRUE.equals(customValue);
    }

    /** Is a carrier swarm's {@code custom2} the replica latch? */
    static boolean swarmReplica(Object custom2) {
        return SWARM_LATCH.equals(custom2);
    }

    /**
     * The {@code onHit} decision on a possibly-FRESH instance: the birth latch decides; a shot with
     * no latch (a carrier the relay left bare, or a shot born before the wrapper) falls back to the
     * projectile's source treatment. The instance field is deliberately NOT an input (unset on a fresh instance).
     */
    static boolean onHitReplica(boolean latched, boolean sourceTreatedReplica) {
        return latched || sourceTreatedReplica;
    }
}
