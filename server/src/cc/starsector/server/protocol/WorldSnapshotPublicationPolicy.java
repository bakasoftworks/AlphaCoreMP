package cc.starsector.server.protocol;

import java.util.Objects;
import java.util.Optional;

/**
 * Per-connection governance the live world-snapshot transport applies to each
 * frame before it encodes it for one peer, driven entirely by
 * {@link NetworkMessageRegistry} metadata for {@link WorldSnapshotMessage}.
 *
 * <p>Until now the registry's served-schema negotiation
 * ({@link NetworkMessageRegistry#negotiateServedSchema}) and the per-connection
 * relevance rule ({@link ClientRelevanceView}) existed only as in-memory protocol
 * transforms ({@code worldSnapshotSchemaV2NegotiationAndNonShipRelevance}); the
 * live socket publisher ({@code LiveWorldSnapshotSocketPublisher}) streamed the
 * full source snapshot at the server's maximum schema to every client regardless.
 * The real-engine bytes never passed through the registry's lane/relevance
 * metadata. This policy is the object that routes that live AWSS publish path
 * through the registry:
 *
 * <ul>
 *   <li>It resolves, once per peer, the schema version the server may serve that
 *       peer ({@code negotiateServedSchema} of the world-snapshot wire code, capped
 *       at the peer's maximum supported schema) and the registry-granted
 *       {@link ReliabilityLane} for the snapshot. Construction fails closed when the
 *       registry has nothing it may serve the peer.</li>
 *   <li>{@link #govern(AuthoritativeWorldSnapshot)} returns the snapshot the
 *       transport should actually encode for that peer: the authoritative frame
 *       filtered to the peer's relevance set (when a {@link ClientRelevanceView} is
 *       supplied) and then projected down to the served schema (dropping the v2
 *       non-ship critical-state tier for a v1-only peer). The bytes the peer
 *       receives are therefore governed by the registry, not by the raw source
 *       frame.</li>
 * </ul>
 *
 * <p>The policy carries no engine or socket identity; it is a pure, reusable
 * decision object shared by the live transport and any future per-connection
 * fanout.
 */
public final class WorldSnapshotPublicationPolicy {

    private final int servedSchemaVersion;
    private final ReliabilityLane reliabilityLane;
    private final ClientRelevanceView relevanceView;

    private WorldSnapshotPublicationPolicy(
            int servedSchemaVersion,
            ReliabilityLane reliabilityLane,
            ClientRelevanceView relevanceView) {
        this.servedSchemaVersion = servedSchemaVersion;
        this.reliabilityLane = Objects.requireNonNull(reliabilityLane, "reliabilityLane");
        this.relevanceView = relevanceView;
    }

    /**
     * Resolve the policy for a peer that can decode world-snapshot frames up to
     * {@code peerMaxSupportedSchemaVersion}, optionally scoping each frame to
     * {@code relevanceView} (pass {@code null} for an unscoped, full-world peer).
     * Fails closed (throwing) when the registry's world-snapshot wire code has no
     * entry it may serve that peer.
     */
    public static WorldSnapshotPublicationPolicy forPeer(
            NetworkMessageRegistry registry,
            int peerMaxSupportedSchemaVersion,
            ClientRelevanceView relevanceView) {
        Objects.requireNonNull(registry, "registry");
        int wireCode = ProtocolRegistryEntry.wireTypeCode(WorldSnapshotMessage.MESSAGE_ID);
        ProtocolRegistryEntry served = registry
                .negotiateServedSchema(wireCode, peerMaxSupportedSchemaVersion)
                .orElseThrow(() -> new IllegalArgumentException(
                        "registry has no world-snapshot schema to serve a peer with max schema "
                                + peerMaxSupportedSchemaVersion));
        if (isServedDowngrade(registry, served.schemaVersion())) { // Was < SCHEMA_VERSION (=1), never fired
            // (81) the literal "landed on an older protocol" seam: this peer is served a thinner
            // tier than the server can encode, whole session — one row per capability class.
            cc.starsector.server.runtime.FallbackAnnounce.announceOnce(
                    "served-schema-downgrade:v" + served.schemaVersion(),
                    "peer_max=" + peerMaxSupportedSchemaVersion
                            + " served=" + served.schemaVersion()
                            + " server_max=" + serverMaxSchemaVersion(registry));
        }
        return new WorldSnapshotPublicationPolicy(
                served.schemaVersion(), served.reliabilityLane(), relevanceView);
    }

    /**
     * The highest world-snapshot schema {@code registry} can serve (its top registered entry for
     * the wire code; {@code 0} when none) — the served-schema-downgrade announce's reference, read
     * from the registry so a future tier needs no second edit here.
     *
     *
     */
    public static int serverMaxSchemaVersion(NetworkMessageRegistry registry) {
        int wireCode = ProtocolRegistryEntry.wireTypeCode(WorldSnapshotMessage.MESSAGE_ID);
        return registry.negotiateServedSchema(wireCode, Integer.MAX_VALUE)
                .map(ProtocolRegistryEntry::schemaVersion).orElse(0);
    }

    /**
     * True when {@code servedSchemaVersion} is below what {@code registry} can serve — the peer
     * lands on a thinner tier (the {@code served-schema-downgrade} FALLBACK-ANNOUNCE condition).
     *
     *
     */
    public static boolean isServedDowngrade(NetworkMessageRegistry registry, int servedSchemaVersion) {
        return servedSchemaVersion < serverMaxSchemaVersion(registry);
    }

    /** The world-snapshot schema version the registry negotiated for this peer. */
    public int servedSchemaVersion() {
        return servedSchemaVersion;
    }

    /** The registry-granted reliability lane the snapshot is published on. */
    public ReliabilityLane reliabilityLane() {
        return reliabilityLane;
    }

    /** The relevance view scoping each frame, if any. */
    public Optional<ClientRelevanceView> relevanceView() {
        return Optional.ofNullable(relevanceView);
    }

    /** True when the served schema is high enough to carry the v2 non-ship tier. */
    public boolean servesNonShipTier() {
        return servedSchemaVersion >= WorldSnapshotMessage.SCHEMA_VERSION_V2;
    }

    /** True when the served schema is high enough to carry the v3 flux/shield tier. */
    public boolean servesFluxTier() {
        return servedSchemaVersion >= WorldSnapshotMessage.SCHEMA_VERSION_V3;
    }

    /** True when the served schema is high enough to carry the v4 weapon-mount tier. */
    public boolean servesWeaponMountTier() {
        return servedSchemaVersion >= WorldSnapshotMessage.SCHEMA_VERSION_V4;
    }

    /** True when the served schema is high enough to carry the v5 combat-side allegiance tier. */
    public boolean servesAllegianceTier() {
        return servedSchemaVersion >= WorldSnapshotMessage.SCHEMA_VERSION_V5;
    }

    /** True when the served schema is high enough to carry the v6 engine-state tier. */
    public boolean servesEngineStateTier() {
        return servedSchemaVersion >= WorldSnapshotMessage.SCHEMA_VERSION_V6;
    }

    /** True when the served schema is high enough to carry the v7 maneuver-command tier. */
    public boolean servesManeuverCommandTier() {
        return servedSchemaVersion >= WorldSnapshotMessage.SCHEMA_VERSION_V7;
    }

    /** True when the served schema is high enough to carry the v8 ship-system state tier. */
    public boolean servesSystemStateTier() {
        return servedSchemaVersion >= WorldSnapshotMessage.SCHEMA_VERSION_V8;
    }

    /** True when the served schema is high enough to carry the v9 fragment-marker tier. */
    public boolean servesFragmentMarkerTier() {
        return servedSchemaVersion >= WorldSnapshotMessage.SCHEMA_VERSION_V9;
    }

    /** True when the served schema is high enough to carry the v10 phase-cloak tier. */
    public boolean servesPhaseCloakTier() {
        return servedSchemaVersion >= WorldSnapshotMessage.SCHEMA_VERSION_V10;
    }

    /** True when the served schema is high enough to carry the v11 in-record shield facing. */
    public boolean servesShieldFacingTier() {
        return servedSchemaVersion >= WorldSnapshotMessage.SCHEMA_VERSION_V11;
    }

    /** True when the served schema is high enough to carry the v12 in-record beam source pair. */
    public boolean servesBeamSourceTier() {
        return servedSchemaVersion >= WorldSnapshotMessage.SCHEMA_VERSION_V12;
    }

    /** True when the served schema is high enough to carry the v13 in-record missile source pair. */
    public boolean servesMissileSourceTier() {
        return servedSchemaVersion >= WorldSnapshotMessage.SCHEMA_VERSION_V13;
    }

    /** True when the served schema is high enough to carry the v14 ship-CR tier. */
    public boolean servesCrTier() {
        return servedSchemaVersion >= WorldSnapshotMessage.SCHEMA_VERSION_V14;
    }

    /** True when the served schema is high enough to carry the v15 content-dictionary tier. */
    public boolean servesContentDictionaryTier() {
        return servedSchemaVersion >= WorldSnapshotMessage.SCHEMA_VERSION_V15;
    }

    /** True when the served schema is high enough to carry the v16 missile-pose widening. */
    public boolean servesMissilePoseTier() {
        return servedSchemaVersion >= WorldSnapshotMessage.SCHEMA_VERSION_V16;
    }

    /**
     * The snapshot the live transport should encode for this peer: relevance-scoped
     * (when a view is set) then projected down to the served schema — each section
     * tier above the negotiated version dropped (v2 non-ship … v10 phase-cloak plus
     * the v14 ship-CR and v15 content-dictionary tails; a lower drop subsumes the higher ones), and the in-record value widenings
     * cleared (v16 missile poses, then v11 shield facing zeroed, v13 missile then
     * v12 beam source pairs cleared) so the
     * value-based writeFrame stamp lands at or below the negotiated version. The
     * chain was v7-capped from v8 through v12 while the
     * v8..v10 projections sat uncalled — a sub-v8 peer on a negotiating surface
     * received frames stamped above its advertised max whenever the source folded
     * higher tiers (the live engine-backed source folds through v12); closed.
     * Returns the same instance when no step changes the frame, so an
     * unscoped v12 peer receiving a v12 frame pays no copy.
     */
    public AuthoritativeWorldSnapshot govern(AuthoritativeWorldSnapshot full) {
        Objects.requireNonNull(full, "full");
        AuthoritativeWorldSnapshot governed = full;
        if (relevanceView != null) {
            governed = governed.relevantFor(relevanceView);
        }
        if (!servesNonShipTier()) {
            governed = governed.withoutNonShipTier();
        }
        if (!servesFluxTier()) {
            governed = governed.withoutFluxTier();
        }
        if (!servesWeaponMountTier()) {
            governed = governed.withoutWeaponMountTier();
        }
        if (!servesAllegianceTier()) {
            governed = governed.withoutAllegianceTier();
        }
        if (!servesEngineStateTier()) {
            governed = governed.withoutEngineStateTier();
        }
        if (!servesManeuverCommandTier()) {
            governed = governed.withoutManeuverCommandTier();
        }
        if (!servesSystemStateTier()) {
            governed = governed.withoutSystemStateTier();
        }
        if (!servesFragmentMarkerTier()) {
            governed = governed.withoutFragmentMarkerTier();
        }
        if (!servesPhaseCloakTier()) {
            governed = governed.withoutPhaseCloakTier();
        }
        if (!servesContentDictionaryTier()) {
            governed = governed.withoutContentDictionaryTier();
        }
        if (!servesCrTier()) {
            governed = governed.withoutCrTier();
        }
        if (!servesMissilePoseTier()) {
            governed = governed.withMissilePosesCleared();
        }
        if (!servesShieldFacingTier()) {
            governed = governed.withShieldFacingZeroed();
        }
        if (!servesMissileSourceTier()) {
            governed = governed.withMissileSourcesCleared();
        }
        if (!servesBeamSourceTier()) {
            governed = governed.withBeamSourcesCleared();
        }
        return governed;
    }
}
