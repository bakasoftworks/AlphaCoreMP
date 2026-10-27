package cc.starsector.server.protocol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Initial product-owned registry for network-facing combat messages.
 *
 * <p>This is the authority list that byte codecs and socket transports
 * consult before accepting a schema. It is intentionally small: one real
 * snapshot message is registered first, and later messages must claim their
 * ownership, lane, relevance, compatibility, failure, and data-class policy
 * before they can be encoded.
 */
public final class NetworkMessageRegistry {

    private static final SnapshotPublicationCadence EVERY_COMPLETED_TICK =
            new SnapshotPublicationCadence(1L);
    private static final SnapshotStreamIdentity GENERIC_OFFLINE_STEP_STREAM =
            new SnapshotStreamIdentity(
                    "server.snapshot_stream.offline_combat_step",
                    1,
                    0,
                    0,
                    false);
    private static final SnapshotStreamIdentity THREE_SHIP_REAL_COMBAT_DAMAGE_STREAM =
            new SnapshotStreamIdentity(
                    "server.snapshot_stream.real_combat_three_ship_engine_damage",
                    1,
                    3,    // requiredShipCount (max): full roster
                    2,    // minShipCount: the stable-roster shrink window publishes a uniform-2 roster
                    180,
                    true);
    private static final SnapshotStreamIdentity THREE_SHIP_REAL_COMBAT_DAMAGE_LONG_STREAM =
            new SnapshotStreamIdentity(
                    "server.snapshot_stream.real_combat_three_ship_engine_damage_720",
                    1,
                    3,
                    720,
                    true);
    private static final SnapshotStreamIdentity FOUR_SHIP_REAL_COMBAT_DAMAGE_LONG_STREAM =
            new SnapshotStreamIdentity(
                    "server.snapshot_stream.real_combat_four_ship_engine_damage_720",
                    1,
                    4,
                    720,
                    true);
    private static final SnapshotStreamIdentity CONCURRENT_TWO_CLIENT_STREAM =
            new SnapshotStreamIdentity(
                    "server.snapshot_stream.concurrent_two_client_authoritative",
                    1,
                    2,
                    6,
                    true);
    private static final SnapshotStreamIdentity PERSISTENT_CONCURRENT_RECONNECT_STREAM =
            new SnapshotStreamIdentity(
                    "server.snapshot_stream.persistent_concurrent_reconnect",
                    1,
                    2,
                    6,
                    true);
    private static final SnapshotStreamIdentity INDEPENDENT_TICK_RECONNECT_STREAM =
            new SnapshotStreamIdentity(
                    "server.snapshot_stream.independent_tick_reconnect",
                    1,
                    2,
                    6,
                    true);
    private static final SnapshotStreamIdentity INDEPENDENT_TICK_CONCURRENT_STREAM =
            new SnapshotStreamIdentity(
                    "server.snapshot_stream.independent_tick_concurrent",
                    1,
                    2,
                    6,
                    true);
    private static final SnapshotStreamIdentity INDEPENDENT_TICK_AUTHORITY_CONFLICT_STREAM =
            new SnapshotStreamIdentity(
                    "server.snapshot_stream.independent_tick_authority_conflict",
                    1,
                    2,
                    6,
                    true);
    private static final SnapshotStreamIdentity INDEPENDENT_TICK_CONFLICT_RECONNECT_STREAM =
            new SnapshotStreamIdentity(
                    "server.snapshot_stream.independent_tick_conflict_reconnect",
                    1,
                    2,
                    6,
                    true);
    private static final ProtocolRelevanceRule COMMAND_AUTHORITY_RELEVANCE_RULE =
            new ProtocolRelevanceRule("command.owner_scoped_authority", true, false);
    private static final NetworkMessageRegistry AUTHORITATIVE_COMBAT =
            new NetworkMessageRegistry(
                    List.of(
                            OfflineCombatStepSnapshotMessage.registryEntry(EVERY_COMPLETED_TICK),
                            WorldSnapshotMessage.registryEntry(EVERY_COMPLETED_TICK),
                            WorldSnapshotMessage.registryEntryV2(EVERY_COMPLETED_TICK),
                            WorldSnapshotMessage.registryEntryV3(EVERY_COMPLETED_TICK),
                            WorldSnapshotMessage.registryEntryV4(EVERY_COMPLETED_TICK),
                            WorldSnapshotMessage.registryEntryV5(EVERY_COMPLETED_TICK),
                            WorldSnapshotMessage.registryEntryV6(EVERY_COMPLETED_TICK),
                            WorldSnapshotMessage.registryEntryV7(EVERY_COMPLETED_TICK),
                            WorldSnapshotMessage.registryEntryV8(EVERY_COMPLETED_TICK),
                            WorldSnapshotMessage.registryEntryV9(EVERY_COMPLETED_TICK),
                            WorldSnapshotMessage.registryEntryV10(EVERY_COMPLETED_TICK),
                            WorldSnapshotMessage.registryEntryV11(EVERY_COMPLETED_TICK),
                            WorldSnapshotMessage.registryEntryV12(EVERY_COMPLETED_TICK),
                            WorldSnapshotMessage.registryEntryV13(EVERY_COMPLETED_TICK),
                            WorldSnapshotMessage.registryEntryV14(EVERY_COMPLETED_TICK),
                            WorldSnapshotMessage.registryEntryV15(EVERY_COMPLETED_TICK),
                            WorldSnapshotMessage.registryEntryV16(EVERY_COMPLETED_TICK),
                            AwsdSnapshotMessage.registryEntry(EVERY_COMPLETED_TICK),
                            SnapshotAdmissionRequestMessage.registryEntry(EVERY_COMPLETED_TICK),
                            ClientShipMovementCommandMessage.registryEntry(EVERY_COMPLETED_TICK),
                            ClientShipWeaponGroupCommandMessage.registryEntry(EVERY_COMPLETED_TICK),
                            ClientShipFireCommandMessage.registryEntry(EVERY_COMPLETED_TICK),
                            ClientShipSystemCommandMessage.registryEntry(EVERY_COMPLETED_TICK),
                            ClientFireDischargeDeclarationMessage.registryEntry(EVERY_COMPLETED_TICK),
                            ClientAwsdNackMessage.registryEntry(EVERY_COMPLETED_TICK),
                            ClientOwnerMarkMessage.registryEntry(EVERY_COMPLETED_TICK),
                            ClientSeatNameAnnounceMessage.registryEntry(EVERY_COMPLETED_TICK),
                            ClientPostureEchoMessage.registryEntry(EVERY_COMPLETED_TICK),
                            ClientChatTextMessage.registryEntry(EVERY_COMPLETED_TICK),
                            ClientSessionResumeClaimMessage.registryEntry(EVERY_COMPLETED_TICK),
                            ServerShipMovementCommandResultMessage.registryEntry(EVERY_COMPLETED_TICK),
                            ServerShipWeaponGroupCommandResultMessage.registryEntry(EVERY_COMPLETED_TICK),
                            ServerShipFireCommandResultMessage.registryEntry(EVERY_COMPLETED_TICK),
                            ServerShipSystemCommandResultMessage.registryEntry(EVERY_COMPLETED_TICK),
                            SnapshotAdmissionAckMessage.registryEntry(EVERY_COMPLETED_TICK),
                            ServerTransportSessionOpenedMessage.registryEntry(EVERY_COMPLETED_TICK),
                            ServerSessionWindowCursorMessage.registryEntry(EVERY_COMPLETED_TICK),
                            ServerInitialSyncMessage.registryEntry(EVERY_COMPLETED_TICK),
                            ServerEntityDespawnEventMessage.registryEntry(EVERY_COMPLETED_TICK),
                            ServerPhaseDiveEventMessage.registryEntry(EVERY_COMPLETED_TICK),
                            ServerCombatOutcomeEventMessage.registryEntry(EVERY_COMPLETED_TICK),
                            ServerGameplayEdgeEventMessage.registryEntry(EVERY_COMPLETED_TICK),
                            MatchResetControlMessage.registryEntry(EVERY_COMPLETED_TICK),
                            MatchReentryControlMessage.registryEntry(EVERY_COMPLETED_TICK),
                            MatchDescriptorControlMessage.registryEntry(EVERY_COMPLETED_TICK),
                            HouseControlMessage.registryEntry(EVERY_COMPLETED_TICK),
                            ServerSeatNamesMessage.registryEntry(EVERY_COMPLETED_TICK),
                            ServerPostureBlockMessage.registryEntry(EVERY_COMPLETED_TICK),
                            ServerSeatNameRecordMessage.registryEntry(EVERY_COMPLETED_TICK),
                            ServerHulkFractureRecordMessage.registryEntry(EVERY_COMPLETED_TICK),
                            ServerHulkPoseRecordMessage.registryEntry(EVERY_COMPLETED_TICK),
                            ServerHulkCutGeometryRecordMessage.registryEntry(EVERY_COMPLETED_TICK),
                            ServerMatchOverRecordMessage.registryEntry(EVERY_COMPLETED_TICK),
                            ServerChatTextMessage.registryEntry(EVERY_COMPLETED_TICK),
                            RosterAppendControlMessage.registryEntry(EVERY_COMPLETED_TICK),
                            ModuleMountControlMessage.registryEntry(EVERY_COMPLETED_TICK),
                            CommandTransferControlMessage.registryEntry(EVERY_COMPLETED_TICK),
                            PilotAimControlMessage.registryEntry(EVERY_COMPLETED_TICK),
                            AsteroidPoseControlMessage.registryEntry(EVERY_COMPLETED_TICK), //
                            VariantCatalogControlMessage.registryEntry(EVERY_COMPLETED_TICK),
                            ClientVariantSubmitMessage.registryEntry(EVERY_COMPLETED_TICK),
                            ServerVariantReceiptMessage.registryEntry(EVERY_COMPLETED_TICK),
                            ServerListingDataMessage.registryEntry(EVERY_COMPLETED_TICK),
                            MatchDocumentMessage.registryEntry(EVERY_COMPLETED_TICK),
                            ServerSeatStateEventMessage.registryEntry(EVERY_COMPLETED_TICK),
                            SnapshotStreamCompletedMessage.registryEntry(EVERY_COMPLETED_TICK)),
                    List.of(
                            GENERIC_OFFLINE_STEP_STREAM,
                            THREE_SHIP_REAL_COMBAT_DAMAGE_STREAM,
                            THREE_SHIP_REAL_COMBAT_DAMAGE_LONG_STREAM,
                            FOUR_SHIP_REAL_COMBAT_DAMAGE_LONG_STREAM,
                            CONCURRENT_TWO_CLIENT_STREAM,
                            PERSISTENT_CONCURRENT_RECONNECT_STREAM,
                            INDEPENDENT_TICK_RECONNECT_STREAM,
                            INDEPENDENT_TICK_CONCURRENT_STREAM,
                            INDEPENDENT_TICK_AUTHORITY_CONFLICT_STREAM,
                            INDEPENDENT_TICK_CONFLICT_RECONNECT_STREAM));

    private final List<ProtocolRegistryEntry> entries;
    private final List<SnapshotStreamIdentity> snapshotStreams;
    private final Map<Key, ProtocolRegistryEntry> entriesByKey;
    private final Map<WireKey, ProtocolRegistryEntry> entriesByWireKey;
    private final Map<StreamKey, SnapshotStreamIdentity> streamsByKey;
    private final Map<StreamWireKey, SnapshotStreamIdentity> streamsByWireKey;
    private final NetworkCapabilityManifest capabilityManifest;

    public NetworkMessageRegistry(List<ProtocolRegistryEntry> entries) {
        this(entries, List.of(GENERIC_OFFLINE_STEP_STREAM));
    }

    public NetworkMessageRegistry(
            List<ProtocolRegistryEntry> entries,
            List<SnapshotStreamIdentity> snapshotStreams) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(snapshotStreams, "snapshotStreams");
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("network message registry must contain at least one entry");
        }
        if (snapshotStreams.isEmpty()) {
            throw new IllegalArgumentException("network message registry must contain at least one stream identity");
        }
        LinkedHashMap<Key, ProtocolRegistryEntry> byKey = new LinkedHashMap<>();
        LinkedHashMap<WireKey, ProtocolRegistryEntry> byWireKey = new LinkedHashMap<>();
        for (ProtocolRegistryEntry entry : entries) {
            Objects.requireNonNull(entry, "entry");
            Key key = new Key(entry.messageId(), entry.schemaVersion());
            ProtocolRegistryEntry previous = byKey.putIfAbsent(key, entry);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate network message registry entry: " + entry.messageId()
                                + " v" + entry.schemaVersion());
            }
            WireKey wireKey = new WireKey(entry.wireTypeCode(), entry.schemaVersion());
            ProtocolRegistryEntry previousWire = byWireKey.putIfAbsent(wireKey, entry);
            if (previousWire != null) {
                throw new IllegalArgumentException(
                        "duplicate network wire identity: " + Integer.toUnsignedString(entry.wireTypeCode())
                                + " v" + entry.schemaVersion());
            }
        }
        LinkedHashMap<StreamKey, SnapshotStreamIdentity> streamsByKey = new LinkedHashMap<>();
        LinkedHashMap<StreamWireKey, SnapshotStreamIdentity> streamsByWireKey = new LinkedHashMap<>();
        for (SnapshotStreamIdentity stream : snapshotStreams) {
            Objects.requireNonNull(stream, "stream");
            StreamKey key = new StreamKey(stream.streamId(), stream.streamVersion());
            SnapshotStreamIdentity previous = streamsByKey.putIfAbsent(key, stream);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "duplicate snapshot stream identity: " + stream.streamId()
                                + " v" + stream.streamVersion());
            }
            StreamWireKey wireKey = new StreamWireKey(stream.streamTypeCode(), stream.streamVersion());
            SnapshotStreamIdentity previousWire = streamsByWireKey.putIfAbsent(wireKey, stream);
            if (previousWire != null) {
                throw new IllegalArgumentException(
                        "duplicate snapshot stream wire identity: "
                                + Integer.toUnsignedString(stream.streamTypeCode())
                                + " v" + stream.streamVersion());
            }
        }
        this.entries = List.copyOf(entries);
        this.snapshotStreams = List.copyOf(snapshotStreams);
        this.entriesByKey = Map.copyOf(byKey);
        this.entriesByWireKey = Map.copyOf(byWireKey);
        this.streamsByKey = Map.copyOf(streamsByKey);
        this.streamsByWireKey = Map.copyOf(streamsByWireKey);
        this.capabilityManifest = NetworkCapabilityManifest.fromEntries(this.entries, this.snapshotStreams);
    }

    public static NetworkMessageRegistry authoritativeCombat() {
        return AUTHORITATIVE_COMBAT;
    }

    public List<ProtocolRegistryEntry> entries() {
        return entries;
    }

    public List<SnapshotStreamIdentity> snapshotStreams() {
        return snapshotStreams;
    }

    public NetworkCapabilityManifest capabilityManifest() {
        return capabilityManifest;
    }

    public Optional<ProtocolRegistryEntry> lookup(String messageId, int schemaVersion) {
        return Optional.ofNullable(entriesByKey.get(new Key(messageId, schemaVersion)));
    }

    public ProtocolRegistryEntry require(String messageId, int schemaVersion) {
        return lookup(messageId, schemaVersion)
                .orElseThrow(() -> new IllegalArgumentException(
                        "unregistered network message schema: " + messageId + " v" + schemaVersion));
    }

    public Optional<ProtocolRegistryEntry> lookupWireType(int wireTypeCode, int schemaVersion) {
        return Optional.ofNullable(entriesByWireKey.get(new WireKey(wireTypeCode, schemaVersion)));
    }

    public ProtocolRegistryEntry requireWireType(int wireTypeCode, int schemaVersion) {
        return lookupWireType(wireTypeCode, schemaVersion)
                .orElseThrow(() -> new IllegalArgumentException(
                        "unregistered network wire identity: "
                                + Integer.toUnsignedString(wireTypeCode)
                                + " v" + schemaVersion));
    }

    /** True if any registered entry carries this wire type code at any schema version. */
    public boolean hasWireTypeCode(int wireTypeCode) {
        for (ProtocolRegistryEntry entry : entries) {
            if (entry.wireTypeCode() == wireTypeCode) {
                return true;
            }
        }
        return false;
    }

    /**
     * Negotiate the server-canonical registry entry to serve a peer that advertises
     * {@code wireTypeCode} at {@code peerSchemaVersion}, consulting each candidate
     * entry's {@link ProtocolCompatibilityRule} min/max (and content-compatibility)
     * rather than requiring an exact {@code (wireTypeCode, schemaVersion)} match.
     *
     * <p>This is how cross-version protocol negotiation is wired into the admission
     * path: an exact registered version still negotiates (its own compatibility rule
     * must accept it), and a peer version inside a registered entry's
     * {@code [minimumSchemaVersion, maximumSchemaVersion]} range is admitted against
     * the server-canonical entry. When several registered versions share the wire
     * code and accept the peer, the highest server schema version wins. Returns empty
     * when no registered entry for the wire code accepts the peer version (or the wire
     * code is unknown); callers distinguish those with {@link #hasWireTypeCode(int)}.
     */
    public Optional<ProtocolRegistryEntry> negotiateWireType(
            int wireTypeCode, int peerSchemaVersion, boolean peerContentCompatible) {
        if (peerSchemaVersion <= 0) {
            throw new IllegalArgumentException("peerSchemaVersion must be > 0; got " + peerSchemaVersion);
        }
        ProtocolRegistryEntry negotiated = null;
        for (ProtocolRegistryEntry entry : entries) {
            if (entry.wireTypeCode() != wireTypeCode) {
                continue;
            }
            if (!entry.compatibilityRule().accepts(peerSchemaVersion, peerContentCompatible)) {
                continue;
            }
            if (negotiated == null || entry.schemaVersion() > negotiated.schemaVersion()) {
                negotiated = entry;
            }
        }
        return Optional.ofNullable(negotiated);
    }

    public Optional<SnapshotStreamIdentity> lookupSnapshotStream(String streamId, int streamVersion) {
        return Optional.ofNullable(streamsByKey.get(new StreamKey(streamId, streamVersion)));
    }

    public SnapshotStreamIdentity requireSnapshotStream(String streamId, int streamVersion) {
        return lookupSnapshotStream(streamId, streamVersion)
                .orElseThrow(() -> new IllegalArgumentException(
                        "unregistered snapshot stream identity: " + streamId + " v" + streamVersion));
    }

    public Optional<SnapshotStreamIdentity> lookupSnapshotStreamType(int streamTypeCode, int streamVersion) {
        return Optional.ofNullable(streamsByWireKey.get(new StreamWireKey(streamTypeCode, streamVersion)));
    }

    public SnapshotStreamIdentity requireSnapshotStreamType(int streamTypeCode, int streamVersion) {
        return lookupSnapshotStreamType(streamTypeCode, streamVersion)
                .orElseThrow(() -> new IllegalArgumentException(
                        "unregistered snapshot stream wire identity: "
                                + Integer.toUnsignedString(streamTypeCode)
                                + " v" + streamVersion));
    }

    public ProtocolRegistryEntry offlineCombatStepSnapshotWireEntry() {
        return require(
                OfflineCombatStepSnapshotMessage.MESSAGE_ID,
                OfflineCombatStepSnapshotMessage.SCHEMA_VERSION);
    }

    public ProtocolRegistryEntry worldSnapshotWireEntry() {
        return require(
                WorldSnapshotMessage.MESSAGE_ID,
                WorldSnapshotMessage.SCHEMA_VERSION);
    }

    public ProtocolRegistryEntry worldSnapshotWireEntryV2() {
        return require(
                WorldSnapshotMessage.MESSAGE_ID,
                WorldSnapshotMessage.SCHEMA_VERSION_V2);
    }

    public ProtocolRegistryEntry worldSnapshotWireEntryV3() {
        return require(
                WorldSnapshotMessage.MESSAGE_ID,
                WorldSnapshotMessage.SCHEMA_VERSION_V3);
    }

    public ProtocolRegistryEntry worldSnapshotWireEntryV4() {
        return require(
                WorldSnapshotMessage.MESSAGE_ID,
                WorldSnapshotMessage.SCHEMA_VERSION_V4);
    }

    public ProtocolRegistryEntry worldSnapshotWireEntryV5() {
        return require(
                WorldSnapshotMessage.MESSAGE_ID,
                WorldSnapshotMessage.SCHEMA_VERSION_V5);
    }

    public ProtocolRegistryEntry worldSnapshotWireEntryV6() {
        return require(
                WorldSnapshotMessage.MESSAGE_ID,
                WorldSnapshotMessage.SCHEMA_VERSION_V6);
    }

    public ProtocolRegistryEntry worldSnapshotWireEntryV7() {
        return require(
                WorldSnapshotMessage.MESSAGE_ID,
                WorldSnapshotMessage.SCHEMA_VERSION_V7);
    }

    public ProtocolRegistryEntry worldSnapshotWireEntryV8() {
        return require(
                WorldSnapshotMessage.MESSAGE_ID,
                WorldSnapshotMessage.SCHEMA_VERSION_V8);
    }

    public ProtocolRegistryEntry worldSnapshotWireEntryV9() {
        return require(
                WorldSnapshotMessage.MESSAGE_ID,
                WorldSnapshotMessage.SCHEMA_VERSION_V9);
    }

    public ProtocolRegistryEntry worldSnapshotWireEntryV10() {
        return require(
                WorldSnapshotMessage.MESSAGE_ID,
                WorldSnapshotMessage.SCHEMA_VERSION_V10);
    }

    public ProtocolRegistryEntry worldSnapshotWireEntryV11() {
        return require(
                WorldSnapshotMessage.MESSAGE_ID,
                WorldSnapshotMessage.SCHEMA_VERSION_V11);
    }

    public ProtocolRegistryEntry worldSnapshotWireEntryV12() {
        return require(
                WorldSnapshotMessage.MESSAGE_ID,
                WorldSnapshotMessage.SCHEMA_VERSION_V12);
    }

    public ProtocolRegistryEntry worldSnapshotWireEntryV13() {
        return require(
                WorldSnapshotMessage.MESSAGE_ID,
                WorldSnapshotMessage.SCHEMA_VERSION_V13);
    }

    public ProtocolRegistryEntry worldSnapshotWireEntryV14() {
        return require(
                WorldSnapshotMessage.MESSAGE_ID,
                WorldSnapshotMessage.SCHEMA_VERSION_V14);
    }

    public ProtocolRegistryEntry worldSnapshotWireEntryV15() {
        return require(
                WorldSnapshotMessage.MESSAGE_ID,
                WorldSnapshotMessage.SCHEMA_VERSION_V15);
    }

    public ProtocolRegistryEntry worldSnapshotWireEntryV16() {
        return require(
                WorldSnapshotMessage.MESSAGE_ID,
                WorldSnapshotMessage.SCHEMA_VERSION_V16);
    }

    /** The AWSD delta+keyframe family's own wire identity (single-version). */
    public ProtocolRegistryEntry awsdSnapshotWireEntry() {
        return require(
                AwsdSnapshotMessage.MESSAGE_ID,
                AwsdSnapshotMessage.SCHEMA_VERSION);
    }

    /**
     * Select which schema version the server should <em>send</em> to a peer that
     * can decode up to {@code peerMaxSupportedSchemaVersion}.
     *
     * <p>This is the outbound counterpart of {@link #negotiateWireType}. Where
     * {@code negotiateWireType} asks "will I accept a peer that advertises this
     * version" (it picks the highest server entry whose compatibility range
     * <em>contains</em> the peer version), this asks "what may I serve this peer":
     * the highest registered entry for the wire code whose own schema version is
     * {@code <=} the peer's maximum. A v2-capable peer is therefore served v2
     * (ships + the non-ship tier) while a v1-only peer is served v1 (ships only),
     * never a v2 frame it cannot decode. Returns empty when the wire code is
     * unknown or every registered version is newer than the peer supports.
     */
    public Optional<ProtocolRegistryEntry> negotiateServedSchema(
            int wireTypeCode, int peerMaxSupportedSchemaVersion) {
        if (peerMaxSupportedSchemaVersion <= 0) {
            throw new IllegalArgumentException(
                    "peerMaxSupportedSchemaVersion must be > 0; got " + peerMaxSupportedSchemaVersion);
        }
        ProtocolRegistryEntry served = null;
        for (ProtocolRegistryEntry entry : entries) {
            if (entry.wireTypeCode() != wireTypeCode) {
                continue;
            }
            if (entry.schemaVersion() > peerMaxSupportedSchemaVersion) {
                continue;
            }
            if (served == null || entry.schemaVersion() > served.schemaVersion()) {
                served = entry;
            }
        }
        return Optional.ofNullable(served);
    }

    public ProtocolRegistryEntry snapshotAdmissionAckWireEntry() {
        return require(
                SnapshotAdmissionAckMessage.MESSAGE_ID,
                SnapshotAdmissionAckMessage.SCHEMA_VERSION);
    }

    public ProtocolRegistryEntry snapshotAdmissionRequestWireEntry() {
        return require(
                SnapshotAdmissionRequestMessage.MESSAGE_ID,
                SnapshotAdmissionRequestMessage.SCHEMA_VERSION);
    }

    public ProtocolRegistryEntry clientShipMovementCommandWireEntry() {
        return require(
                ClientShipMovementCommandMessage.MESSAGE_ID,
                ClientShipMovementCommandMessage.SCHEMA_VERSION);
    }

    public ProtocolRegistryEntry clientShipFireCommandWireEntry() {
        return require(
                ClientShipFireCommandMessage.MESSAGE_ID,
                ClientShipFireCommandMessage.SCHEMA_VERSION);
    }

    public ProtocolRegistryEntry clientShipWeaponGroupCommandWireEntry() {
        return require(
                ClientShipWeaponGroupCommandMessage.MESSAGE_ID,
                ClientShipWeaponGroupCommandMessage.SCHEMA_VERSION);
    }

    public ProtocolRegistryEntry clientShipSystemCommandWireEntry() {
        return require(
                ClientShipSystemCommandMessage.MESSAGE_ID,
                ClientShipSystemCommandMessage.SCHEMA_VERSION);
    }

    public ProtocolRegistryEntry clientFireDischargeDeclarationWireEntry() {
        return require(
                ClientFireDischargeDeclarationMessage.MESSAGE_ID,
                ClientFireDischargeDeclarationMessage.SCHEMA_VERSION);
    }

    /** {@code SNNK} AWSD delta-reject NACK (codec {@code AwsdNackFrameCodec}). */
    public ProtocolRegistryEntry clientAwsdNackWireEntry() {
        return require(
                ClientAwsdNackMessage.MESSAGE_ID,
                ClientAwsdNackMessage.SCHEMA_VERSION);
    }

    /** {@code MARK} F9 owner-mark capture (codec {@code MarkFrameCodec}). */
    public ProtocolRegistryEntry clientOwnerMarkWireEntry() {
        return require(
                ClientOwnerMarkMessage.MESSAGE_ID,
                ClientOwnerMarkMessage.SCHEMA_VERSION);
    }

    public ProtocolRegistryEntry clientSessionResumeClaimWireEntry() {
        return require(
                ClientSessionResumeClaimMessage.MESSAGE_ID,
                ClientSessionResumeClaimMessage.SCHEMA_VERSION);
    }

    public ProtocolRegistryEntry serverShipMovementCommandResultWireEntry() {
        return require(
                ServerShipMovementCommandResultMessage.MESSAGE_ID,
                ServerShipMovementCommandResultMessage.SCHEMA_VERSION);
    }

    public ProtocolRegistryEntry serverShipFireCommandResultWireEntry() {
        return require(
                ServerShipFireCommandResultMessage.MESSAGE_ID,
                ServerShipFireCommandResultMessage.SCHEMA_VERSION);
    }

    public ProtocolRegistryEntry serverShipWeaponGroupCommandResultWireEntry() {
        return require(
                ServerShipWeaponGroupCommandResultMessage.MESSAGE_ID,
                ServerShipWeaponGroupCommandResultMessage.SCHEMA_VERSION);
    }

    public ProtocolRegistryEntry serverShipSystemCommandResultWireEntry() {
        return require(
                ServerShipSystemCommandResultMessage.MESSAGE_ID,
                ServerShipSystemCommandResultMessage.SCHEMA_VERSION);
    }

    public ProtocolRegistryEntry snapshotStreamCompletedWireEntry() {
        return require(
                SnapshotStreamCompletedMessage.MESSAGE_ID,
                SnapshotStreamCompletedMessage.SCHEMA_VERSION);
    }

    public ProtocolRegistryEntry serverInitialSyncWireEntry() {
        return require(
                ServerInitialSyncMessage.MESSAGE_ID,
                ServerInitialSyncMessage.SCHEMA_VERSION);
    }

    public ProtocolRegistryEntry serverEntityDespawnEventWireEntry() {
        return require(
                ServerEntityDespawnEventMessage.MESSAGE_ID,
                ServerEntityDespawnEventMessage.SCHEMA_VERSION);
    }

    public ProtocolRegistryEntry serverPhaseDiveEventWireEntry() {
        return require(
                ServerPhaseDiveEventMessage.MESSAGE_ID,
                ServerPhaseDiveEventMessage.SCHEMA_VERSION);
    }

    public ProtocolRegistryEntry serverCombatOutcomeEventWireEntry() {
        return require(
                ServerCombatOutcomeEventMessage.MESSAGE_ID,
                ServerCombatOutcomeEventMessage.SCHEMA_VERSION);
    }

    public ProtocolRegistryEntry serverGameplayEdgeEventWireEntry() {
        return require(
                ServerGameplayEdgeEventMessage.MESSAGE_ID,
                ServerGameplayEdgeEventMessage.SCHEMA_VERSION);
    }

    public ProtocolRegistryEntry matchResetControlWireEntry() {
        return require(
                MatchResetControlMessage.MESSAGE_ID,
                MatchResetControlMessage.SCHEMA_VERSION);
    }

    public ProtocolRegistryEntry matchReentryControlWireEntry() {
        return require(
                MatchReentryControlMessage.MESSAGE_ID,
                MatchReentryControlMessage.SCHEMA_VERSION);
    }

    /** Reserved-dormant {@code DESC} descriptor-on-CTRL kind. */
    public ProtocolRegistryEntry matchDescriptorControlWireEntry() {
        return require(
                MatchDescriptorControlMessage.MESSAGE_ID,
                MatchDescriptorControlMessage.SCHEMA_VERSION);
    }

    /** Reserved-dormant {@code HOUS} house/operator CTRL kind. */
    public ProtocolRegistryEntry houseControlWireEntry() {
        return require(
                HouseControlMessage.MESSAGE_ID,
                HouseControlMessage.SCHEMA_VERSION);
    }

    public ProtocolRegistryEntry clientSeatNameAnnounceWireEntry() {
        return require(
                ClientSeatNameAnnounceMessage.MESSAGE_ID,
                ClientSeatNameAnnounceMessage.SCHEMA_VERSION);
    }

    public ProtocolRegistryEntry serverSeatNamesWireEntry() {
        return require(
                ServerSeatNamesMessage.MESSAGE_ID,
                ServerSeatNamesMessage.SCHEMA_VERSION);
    }

    /** Lane-posture {@code PSTB} wire block (codec {@code PostureFrameCodec}). */
    public ProtocolRegistryEntry serverPostureBlockWireEntry() {
        return require(
                ServerPostureBlockMessage.MESSAGE_ID,
                ServerPostureBlockMessage.SCHEMA_VERSION);
    }

    /** Lane-posture {@code PSTE} echo (codec {@code PostureFrameCodec}). */
    public ProtocolRegistryEntry clientPostureEchoWireEntry() {
        return require(
                ClientPostureEchoMessage.MESSAGE_ID,
                ClientPostureEchoMessage.SCHEMA_VERSION);
    }

    /** N-aware per-seat {@code SNMR} name record (codec {@code SeatNameFrameCodec}). */
    public ProtocolRegistryEntry serverSeatNameRecordWireEntry() {
        return require(
                ServerSeatNameRecordMessage.MESSAGE_ID,
                ServerSeatNameRecordMessage.SCHEMA_VERSION);
    }

    /** {@code HKFR} hulk-fracture edge record (codec {@code HulkFractureFrameCodec}). */
    public ProtocolRegistryEntry serverHulkFractureRecordWireEntry() {
        return require(
                ServerHulkFractureRecordMessage.MESSAGE_ID,
                ServerHulkFractureRecordMessage.SCHEMA_VERSION);
    }

    /** {@code HKPS} corpse-pose keyframe record (codec {@code HulkPoseFrameCodec}). */
    public ProtocolRegistryEntry serverHulkPoseRecordWireEntry() {
        return require(
                ServerHulkPoseRecordMessage.MESSAGE_ID,
                ServerHulkPoseRecordMessage.SCHEMA_VERSION);
    }

    /** {@code HKCG} hulk-cut-geometry record (codec {@code HulkCutGeometryFrameCodec}). */
    public ProtocolRegistryEntry serverHulkCutGeometryRecordWireEntry() {
        return require(
                ServerHulkCutGeometryRecordMessage.MESSAGE_ID,
                ServerHulkCutGeometryRecordMessage.SCHEMA_VERSION);
    }

    /** {@code MOVR} serve-end match verdict record (codec {@code MatchOverFrameCodec}). */
    public ProtocolRegistryEntry serverMatchOverRecordWireEntry() {
        return require(
                ServerMatchOverRecordMessage.MESSAGE_ID,
                ServerMatchOverRecordMessage.SCHEMA_VERSION);
    }

    public ProtocolRegistryEntry clientChatTextWireEntry() {
        return require(
                ClientChatTextMessage.MESSAGE_ID,
                ClientChatTextMessage.SCHEMA_VERSION);
    }

    public ProtocolRegistryEntry serverChatTextWireEntry() {
        return require(
                ServerChatTextMessage.MESSAGE_ID,
                ServerChatTextMessage.SCHEMA_VERSION);
    }

    /** {@code RAPP} mid-round roster append (codec {@code RosterAppendFrameCodec}). */
    public ProtocolRegistryEntry serverRosterAppendWireEntry() {
        return require(
                RosterAppendControlMessage.MESSAGE_ID,
                RosterAppendControlMessage.SCHEMA_VERSION);
    }

    /** {@code MMNT} module-seat handover (codec {@code ModuleMountFrameCodec}). */
    public ProtocolRegistryEntry serverModuleMountWireEntry() {
        return require(
                ModuleMountControlMessage.MESSAGE_ID,
                ModuleMountControlMessage.SCHEMA_VERSION);
    }

    /** {@code XFER} command-transfer control (codec {@code CommandTransferFrameCodec}). */
    public ProtocolRegistryEntry serverCommandTransferWireEntry() {
        return require(
                CommandTransferControlMessage.MESSAGE_ID,
                CommandTransferControlMessage.SCHEMA_VERSION);
    }

    /** {@code AIMP} batched piloted-seat aim points (codec {@code PilotAimFrameCodec}). */
    public ProtocolRegistryEntry serverPilotAimWireEntry() {
        return require(
                PilotAimControlMessage.MESSAGE_ID,
                PilotAimControlMessage.SCHEMA_VERSION);
    }

    /** {@code ASTP} batched asteroid-field pose rows (codec {@code AsteroidPoseFrameCodec}).
     * */
    public ProtocolRegistryEntry serverAsteroidPoseWireEntry() {
        return require(
                AsteroidPoseControlMessage.MESSAGE_ID,
                AsteroidPoseControlMessage.SCHEMA_VERSION);
    }

    /** W7c B2 {@code VDEF} variant-catalog delivery (codec {@code VariantDefinitionFrameCodec}). */
    public ProtocolRegistryEntry serverVariantCatalogWireEntry() {
        return require(
                VariantCatalogControlMessage.MESSAGE_ID,
                VariantCatalogControlMessage.SCHEMA_VERSION);
    }

    /** W15 v0 {@code VSUB} custom-variant submission, UP (codec {@code VariantSubmitFrameCodec}). */
    public ProtocolRegistryEntry clientVariantSubmitWireEntry() {
        return require(
                ClientVariantSubmitMessage.MESSAGE_ID,
                ClientVariantSubmitMessage.SCHEMA_VERSION);
    }

    /** W15 v0 {@code VACK} submission receipt, DOWN unicast (codec {@code VariantReceiptFrameCodec}). */
    public ProtocolRegistryEntry serverVariantReceiptWireEntry() {
        return require(
                ServerVariantReceiptMessage.MESSAGE_ID,
                ServerVariantReceiptMessage.SCHEMA_VERSION);
    }

    /** SS382 B {@code LSTD} structured listing frames (codec {@code ListingFrameCodec}). */
    public ProtocolRegistryEntry serverListingDataWireEntry() {
        return require(
                ServerListingDataMessage.MESSAGE_ID,
                ServerListingDataMessage.SCHEMA_VERSION);
    }

    /** {@code DESC} schema 2: the match-document probe reply (codec {@code MatchDocFrameCodec}). */
    public ProtocolRegistryEntry matchDocumentWireEntry() {
        return require(
                MatchDocumentMessage.MESSAGE_ID,
                MatchDocumentMessage.SCHEMA_VERSION);
    }

    public ProtocolRegistryEntry serverSeatStateEventWireEntry() {
        return require(
                ServerSeatStateEventMessage.MESSAGE_ID,
                ServerSeatStateEventMessage.SCHEMA_VERSION);
    }

    public ProtocolRegistryEntry serverTransportSessionOpenedWireEntry() {
        return require(
                ServerTransportSessionOpenedMessage.MESSAGE_ID,
                ServerTransportSessionOpenedMessage.SCHEMA_VERSION);
    }

    public ProtocolRegistryEntry serverSessionWindowCursorWireEntry() {
        return require(
                ServerSessionWindowCursorMessage.MESSAGE_ID,
                ServerSessionWindowCursorMessage.SCHEMA_VERSION);
    }

    public SnapshotStreamIdentity offlineCombatStepSnapshotStreamIdentity() {
        return requireSnapshotStream(
                GENERIC_OFFLINE_STEP_STREAM.streamId(),
                GENERIC_OFFLINE_STEP_STREAM.streamVersion());
    }

    public SnapshotStreamIdentity threeShipRealCombatDamageStreamIdentity() {
        return requireSnapshotStream(
                THREE_SHIP_REAL_COMBAT_DAMAGE_STREAM.streamId(),
                THREE_SHIP_REAL_COMBAT_DAMAGE_STREAM.streamVersion());
    }

    public SnapshotStreamIdentity threeShipRealCombatDamageLongStreamIdentity() {
        return requireSnapshotStream(
                THREE_SHIP_REAL_COMBAT_DAMAGE_LONG_STREAM.streamId(),
                THREE_SHIP_REAL_COMBAT_DAMAGE_LONG_STREAM.streamVersion());
    }

    public SnapshotStreamIdentity fourShipRealCombatDamageLongStreamIdentity() {
        return requireSnapshotStream(
                FOUR_SHIP_REAL_COMBAT_DAMAGE_LONG_STREAM.streamId(),
                FOUR_SHIP_REAL_COMBAT_DAMAGE_LONG_STREAM.streamVersion());
    }

    public SnapshotStreamIdentity concurrentTwoClientStreamIdentity() {
        return requireSnapshotStream(
                CONCURRENT_TWO_CLIENT_STREAM.streamId(),
                CONCURRENT_TWO_CLIENT_STREAM.streamVersion());
    }

    public SnapshotStreamIdentity persistentConcurrentReconnectStreamIdentity() {
        return requireSnapshotStream(
                PERSISTENT_CONCURRENT_RECONNECT_STREAM.streamId(),
                PERSISTENT_CONCURRENT_RECONNECT_STREAM.streamVersion());
    }

    public SnapshotStreamIdentity independentTickReconnectStreamIdentity() {
        return requireSnapshotStream(
                INDEPENDENT_TICK_RECONNECT_STREAM.streamId(),
                INDEPENDENT_TICK_RECONNECT_STREAM.streamVersion());
    }

    public SnapshotStreamIdentity independentTickConcurrentStreamIdentity() {
        return requireSnapshotStream(
                INDEPENDENT_TICK_CONCURRENT_STREAM.streamId(),
                INDEPENDENT_TICK_CONCURRENT_STREAM.streamVersion());
    }

    public SnapshotStreamIdentity independentTickAuthorityConflictStreamIdentity() {
        return requireSnapshotStream(
                INDEPENDENT_TICK_AUTHORITY_CONFLICT_STREAM.streamId(),
                INDEPENDENT_TICK_AUTHORITY_CONFLICT_STREAM.streamVersion());
    }

    public SnapshotStreamIdentity independentTickConflictReconnectStreamIdentity() {
        return requireSnapshotStream(
                INDEPENDENT_TICK_CONFLICT_RECONNECT_STREAM.streamId(),
                INDEPENDENT_TICK_CONFLICT_RECONNECT_STREAM.streamVersion());
    }

    public ProtocolRelevanceRule commandAuthorityRelevanceRule() {
        return COMMAND_AUTHORITY_RELEVANCE_RULE;
    }

    public ReliabilityLane grantedCommandReliabilityLane() {
        return ReliabilityLane.DURABLE_GAMEPLAY_EVENT;
    }

    private record Key(String messageId, int schemaVersion) {
        private Key {
            if (messageId == null || messageId.isBlank()) {
                throw new IllegalArgumentException("messageId must be non-blank");
            }
            if (schemaVersion <= 0) {
                throw new IllegalArgumentException("schemaVersion must be > 0; got " + schemaVersion);
            }
        }
    }

    private record WireKey(int wireTypeCode, int schemaVersion) {
        private WireKey {
            if (schemaVersion <= 0) {
                throw new IllegalArgumentException("schemaVersion must be > 0; got " + schemaVersion);
            }
        }
    }

    private record StreamKey(String streamId, int streamVersion) {
        private StreamKey {
            if (streamId == null || streamId.isBlank()) {
                throw new IllegalArgumentException("streamId must be non-blank");
            }
            if (streamVersion <= 0) {
                throw new IllegalArgumentException("streamVersion must be > 0; got " + streamVersion);
            }
        }
    }

    private record StreamWireKey(int streamTypeCode, int streamVersion) {
        private StreamWireKey {
            if (streamVersion <= 0) {
                throw new IllegalArgumentException("streamVersion must be > 0; got " + streamVersion);
            }
        }
    }
}
