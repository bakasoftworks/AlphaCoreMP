package cc.starsector.server.client;

import cc.starsector.server.protocol.NetworkCapabilityManifest;
import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.OfflineCombatStepSnapshotMessage;
import cc.starsector.server.protocol.ProtocolRegistryEntry;
import cc.starsector.server.protocol.SnapshotAdmissionRequestMessage;
import cc.starsector.server.protocol.SnapshotStreamIdentity;
import java.util.Objects;
import java.util.Set;

import cc.starsector.server.runtime.DoorRejectReasons;

/**
 * Server-side admission gate for a headless snapshot client handshake.
 */
public final class SnapshotClientAdmissionGate {

    private final int requiredFrames;
    private final SnapshotStreamIdentity requiredStreamIdentity;
    private final NetworkMessageRegistry registry;
    private final Set<String> enforcedSeatTokens;
    private final String requiredContentFingerprint;

    public SnapshotClientAdmissionGate(int requiredFrames) {
        this(requiredFrames, NetworkMessageRegistry.authoritativeCombat().offlineCombatStepSnapshotStreamIdentity());
    }

    public SnapshotClientAdmissionGate(int requiredFrames, SnapshotStreamIdentity requiredStreamIdentity) {
        this(requiredFrames, requiredStreamIdentity, NetworkMessageRegistry.authoritativeCombat());
    }

    public SnapshotClientAdmissionGate(
            int requiredFrames,
            SnapshotStreamIdentity requiredStreamIdentity,
            NetworkMessageRegistry registry) {
        this(requiredFrames, requiredStreamIdentity, registry, Set.of());
    }

    /**
     * demo-program R8 seat-token enforcement. When {@code enforcedSeatTokens} is non-empty, the
     * gate rejects any handshake whose {@code seatToken} is blank ({@code seat_token_required})
     * or is not one of the minted per-seat tokens ({@code seat_token_unknown}). An empty set is
     * the v0 perimeter-trust default — the reserved seat fields stay carry-only
     * and the admission verdict ignores them, exactly as every existing caller (which uses the
     * shorter constructors) behaves. The set is the match descriptor's seat tokens (R4,
     * {@code MatchDescriptor.seats()}). NOTE (R8b seam): this gate is stateless and sees one
     * handshake, so it does NOT enforce the cross-connection duplicate-seat policy, nor does it
     * seed the authority arbiter FROM the token's seat (replacing the client-asserted
     * {@code ownedShipId}) — both are caller-side and tracked.
     */
    public SnapshotClientAdmissionGate(
            int requiredFrames,
            SnapshotStreamIdentity requiredStreamIdentity,
            NetworkMessageRegistry registry,
            Set<String> enforcedSeatTokens) {
        this(requiredFrames, requiredStreamIdentity, registry, enforcedSeatTokens,
                HeadlessSnapshotSocketClient.UNASSERTED_CONTENT_FINGERPRINT);
    }

    /**
     * demo-program R8c content-universe enforcement (the "check the fingerprint at the door" half of
     * the R8 admission-enforcement pair, alongside the R8a seat-token check). When
     * {@code requiredContentFingerprint} is non-blank, the gate rejects any handshake whose
     * {@code contentFingerprint} is blank ({@code content_fingerprint_required}) or does not match
     * the server's universe identity ({@code content_fingerprint_mismatch}) — a client running a
     * different build/mod-set/schema is turned away at the door BEFORE it can desync, rather than
     * relying on the warn-grade drift tripwire mid-stream. A blank (unasserted) required fingerprint
     * is the v0 perimeter-trust default: the field stays carry-only and the verdict
     * ignores it, exactly as every existing caller (which uses the shorter constructors) behaves. The
     * server's fingerprint is the same value {@code MatchRecordMeta} stamps as {@code content.fingerprint}
     * (build + mod set + schema + critical knobs). Wiring this into a live runner is the R8 runner-wiring
     * seam (with R6 token/identity delivery), exactly as the R8a/R8b ledger is — this constructor only
     * supplies the mechanism, default-off. The content check is evaluated BEFORE the seat-token check:
     * a wrong-universe handshake is rejected as such even when its seat token is also unknown.
     */
    public SnapshotClientAdmissionGate(
            int requiredFrames,
            SnapshotStreamIdentity requiredStreamIdentity,
            NetworkMessageRegistry registry,
            Set<String> enforcedSeatTokens,
            String requiredContentFingerprint) {
        if (requiredFrames < 0) {
            throw new IllegalArgumentException("requiredFrames must be >= 0; got " + requiredFrames);
        }
        this.requiredFrames = requiredFrames;
        this.requiredStreamIdentity =
                Objects.requireNonNull(requiredStreamIdentity, "requiredStreamIdentity");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.enforcedSeatTokens =
                Set.copyOf(Objects.requireNonNull(enforcedSeatTokens, "enforcedSeatTokens"));
        Objects.requireNonNull(requiredContentFingerprint, "requiredContentFingerprint");
        this.requiredContentFingerprint =
                requiredContentFingerprint.isBlank() ? "" : requiredContentFingerprint;
    }

    public Admission admit(HeadlessSnapshotSocketClient.ClientHandshake handshake) {
        Objects.requireNonNull(handshake, "handshake");
        NetworkMessageRegistry registry = this.registry;
        NetworkCapabilityManifest requiredCapabilityManifest = registry.capabilityManifest();
        ProtocolRegistryEntry admissionRequestEntry;
        try {
            admissionRequestEntry = registry.requireWireType(
                    handshake.admissionRequestMessageTypeCode(),
                    handshake.admissionRequestSchemaVersion());
        } catch (IllegalArgumentException badRequestIdentity) {
            return Admission.rejected(
                    "unregistered_admission_request_identity",
                    handshake.messageId(),
                    handshake.wireTypeCode(),
                    handshake.schemaVersion(),
                    handshake.streamId(),
                    handshake.streamTypeCode(),
                    handshake.streamVersion(),
                    handshake.expectedFrames(),
                    requiredFrames,
                    handshake.capabilityManifest(),
                    requiredCapabilityManifest);
        }
        if (!SnapshotAdmissionRequestMessage.MESSAGE_ID.equals(admissionRequestEntry.messageId())
                || !admissionRequestEntry.messageId().equals(handshake.admissionRequestMessageId())) {
            return Admission.rejected(
                    "unexpected_admission_request_message_id",
                    handshake.messageId(),
                    handshake.wireTypeCode(),
                    handshake.schemaVersion(),
                    handshake.streamId(),
                    handshake.streamTypeCode(),
                    handshake.streamVersion(),
                    handshake.expectedFrames(),
                    requiredFrames,
                    handshake.capabilityManifest(),
                    requiredCapabilityManifest);
        }
        ProtocolRegistryEntry entry;
        java.util.Optional<ProtocolRegistryEntry> negotiated =
                registry.negotiateWireType(handshake.wireTypeCode(), handshake.schemaVersion(), true);
        if (negotiated.isEmpty()) {
            String reason = registry.hasWireTypeCode(handshake.wireTypeCode())
                    ? "schema_version_out_of_compatibility_range"
                    : "unregistered_wire_identity";
            return Admission.rejected(
                    reason,
                    handshake.messageId(),
                    handshake.wireTypeCode(),
                    handshake.schemaVersion(),
                    handshake.streamId(),
                    handshake.streamTypeCode(),
                    handshake.streamVersion(),
                    handshake.expectedFrames(),
                    requiredFrames,
                    handshake.capabilityManifest(),
                    requiredCapabilityManifest);
        }
        entry = negotiated.get();
        if (!OfflineCombatStepSnapshotMessage.MESSAGE_ID.equals(entry.messageId())
                || !entry.messageId().equals(handshake.messageId())) {
            return Admission.rejected(
                    "unexpected_message_id",
                    handshake.messageId(),
                    handshake.wireTypeCode(),
                    handshake.schemaVersion(),
                    handshake.streamId(),
                    handshake.streamTypeCode(),
                    handshake.streamVersion(),
                    handshake.expectedFrames(),
                    requiredFrames,
                    handshake.capabilityManifest(),
                    requiredCapabilityManifest);
        }
        SnapshotStreamIdentity streamIdentity;
        try {
            streamIdentity = registry.requireSnapshotStreamType(
                    handshake.streamTypeCode(),
                    handshake.streamVersion());
        } catch (IllegalArgumentException badStreamIdentity) {
            return Admission.rejected(
                    "unregistered_stream_identity",
                    handshake.messageId(),
                    handshake.wireTypeCode(),
                    handshake.schemaVersion(),
                    handshake.streamId(),
                    handshake.streamTypeCode(),
                    handshake.streamVersion(),
                    handshake.expectedFrames(),
                    requiredFrames,
                    handshake.capabilityManifest(),
                    requiredCapabilityManifest);
        }
        if (!streamIdentity.streamId().equals(handshake.streamId())) {
            return Admission.rejected(
                    "unexpected_stream_id",
                    handshake.messageId(),
                    handshake.wireTypeCode(),
                    handshake.schemaVersion(),
                    handshake.streamId(),
                    handshake.streamTypeCode(),
                    handshake.streamVersion(),
                    handshake.expectedFrames(),
                    requiredFrames,
                    handshake.capabilityManifest(),
                    requiredCapabilityManifest);
        }
        if (!requiredStreamIdentity.streamId().equals(streamIdentity.streamId())
                || requiredStreamIdentity.streamTypeCode() != streamIdentity.streamTypeCode()
                || requiredStreamIdentity.streamVersion() != streamIdentity.streamVersion()) {
            return Admission.rejected(
                    "server_stream_identity_mismatch",
                    handshake.messageId(),
                    handshake.wireTypeCode(),
                    handshake.schemaVersion(),
                    handshake.streamId(),
                    handshake.streamTypeCode(),
                    handshake.streamVersion(),
                    handshake.expectedFrames(),
                    requiredFrames,
                    handshake.capabilityManifest(),
                    requiredCapabilityManifest);
        }
        if (!requiredCapabilityManifest.equals(handshake.capabilityManifest())) {
            return Admission.rejected(
                    "registry_capability_mismatch",
                    handshake.messageId(),
                    handshake.wireTypeCode(),
                    handshake.schemaVersion(),
                    handshake.streamId(),
                    handshake.streamTypeCode(),
                    handshake.streamVersion(),
                    handshake.expectedFrames(),
                    requiredFrames,
                    handshake.capabilityManifest(),
                    requiredCapabilityManifest);
        }
        if (streamIdentity.hasFrameRequirement() && streamIdentity.requiredFrames() != requiredFrames) {
            return Admission.rejected(
                    "stream_required_frame_mismatch",
                    handshake.messageId(),
                    handshake.wireTypeCode(),
                    handshake.schemaVersion(),
                    handshake.streamId(),
                    handshake.streamTypeCode(),
                    handshake.streamVersion(),
                    handshake.expectedFrames(),
                    requiredFrames,
                    handshake.capabilityManifest(),
                    requiredCapabilityManifest);
        }
        if (handshake.expectedFrames() != requiredFrames) {
            return Admission.rejected(
                    "frame_bound_mismatch",
                    handshake.messageId(),
                    handshake.wireTypeCode(),
                    handshake.schemaVersion(),
                    handshake.streamId(),
                    handshake.streamTypeCode(),
                    handshake.streamVersion(),
                    handshake.expectedFrames(),
                    requiredFrames,
                    handshake.capabilityManifest(),
                    requiredCapabilityManifest);
        }
        if (!requiredContentFingerprint.isEmpty()) {
            String contentFingerprint = handshake.contentFingerprint();
            if (contentFingerprint == null || contentFingerprint.isBlank()) {
                return Admission.rejected(
                        DoorRejectReasons.CONTENT_FINGERPRINT_REQUIRED,
                        entry.messageId(),
                        entry.wireTypeCode(),
                        entry.schemaVersion(),
                        streamIdentity.streamId(),
                        streamIdentity.streamTypeCode(),
                        streamIdentity.streamVersion(),
                        handshake.expectedFrames(),
                        requiredFrames,
                        handshake.capabilityManifest(),
                        requiredCapabilityManifest);
            }
            if (!requiredContentFingerprint.equals(contentFingerprint)) {
                return Admission.rejected(
                        DoorRejectReasons.CONTENT_FINGERPRINT_MISMATCH,
                        entry.messageId(),
                        entry.wireTypeCode(),
                        entry.schemaVersion(),
                        streamIdentity.streamId(),
                        streamIdentity.streamTypeCode(),
                        streamIdentity.streamVersion(),
                        handshake.expectedFrames(),
                        requiredFrames,
                        handshake.capabilityManifest(),
                        requiredCapabilityManifest);
            }
        }
        if (!enforcedSeatTokens.isEmpty()) {
            String seatToken = handshake.seatToken();
            if (seatToken == null || seatToken.isBlank()) {
                return Admission.rejected(
                        DoorRejectReasons.SEAT_TOKEN_REQUIRED,
                        entry.messageId(),
                        entry.wireTypeCode(),
                        entry.schemaVersion(),
                        streamIdentity.streamId(),
                        streamIdentity.streamTypeCode(),
                        streamIdentity.streamVersion(),
                        handshake.expectedFrames(),
                        requiredFrames,
                        handshake.capabilityManifest(),
                        requiredCapabilityManifest);
            }
            if (!enforcedSeatTokens.contains(seatToken)) {
                return Admission.rejected(
                        DoorRejectReasons.SEAT_TOKEN_UNKNOWN,
                        entry.messageId(),
                        entry.wireTypeCode(),
                        entry.schemaVersion(),
                        streamIdentity.streamId(),
                        streamIdentity.streamTypeCode(),
                        streamIdentity.streamVersion(),
                        handshake.expectedFrames(),
                        requiredFrames,
                        handshake.capabilityManifest(),
                        requiredCapabilityManifest);
            }
        }
        return new Admission(
                true,
                "accepted",
                entry.messageId(),
                entry.wireTypeCode(),
                entry.schemaVersion(),
                streamIdentity.streamId(),
                streamIdentity.streamTypeCode(),
                streamIdentity.streamVersion(),
                handshake.expectedFrames(),
                requiredFrames,
                handshake.capabilityManifest(),
                requiredCapabilityManifest);
    }

    public record Admission(
            boolean accepted,
            String reason,
            String messageId,
            int wireTypeCode,
            int schemaVersion,
            String streamId,
            int streamTypeCode,
            int streamVersion,
            int expectedFrames,
            int requiredFrames,
            NetworkCapabilityManifest offeredCapabilityManifest,
            NetworkCapabilityManifest requiredCapabilityManifest) {

        private static Admission rejected(
                String reason,
                String messageId,
                int wireTypeCode,
                int schemaVersion,
                String streamId,
                int streamTypeCode,
                int streamVersion,
                int expectedFrames,
                int requiredFrames,
                NetworkCapabilityManifest offeredCapabilityManifest,
                NetworkCapabilityManifest requiredCapabilityManifest) {
            return new Admission(
                    false,
                    reason,
                        messageId,
                        wireTypeCode,
                        schemaVersion,
                        streamId,
                        streamTypeCode,
                        streamVersion,
                        expectedFrames,
                        requiredFrames,
                    offeredCapabilityManifest,
                    requiredCapabilityManifest);
        }

        public Admission {
            reason = Objects.requireNonNull(reason, "reason");
            messageId = Objects.requireNonNull(messageId, "messageId");
            streamId = Objects.requireNonNull(streamId, "streamId");
            offeredCapabilityManifest =
                    Objects.requireNonNull(offeredCapabilityManifest, "offeredCapabilityManifest");
            requiredCapabilityManifest =
                    Objects.requireNonNull(requiredCapabilityManifest, "requiredCapabilityManifest");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
            if (messageId.isBlank()) {
                throw new IllegalArgumentException("messageId must not be blank");
            }
            if (schemaVersion <= 0) {
                throw new IllegalArgumentException("schemaVersion must be > 0; got " + schemaVersion);
            }
            if (streamId.isBlank()) {
                throw new IllegalArgumentException("streamId must be non-blank");
            }
            if (streamVersion <= 0) {
                throw new IllegalArgumentException("streamVersion must be > 0; got " + streamVersion);
            }
            if (expectedFrames < 0) {
                throw new IllegalArgumentException("expectedFrames must be >= 0; got " + expectedFrames);
            }
            if (requiredFrames < 0) {
                throw new IllegalArgumentException("requiredFrames must be >= 0; got " + requiredFrames);
            }
            if (accepted && !"accepted".equals(reason)) {
                throw new IllegalArgumentException("accepted admission must use reason=accepted");
            }
            if (!accepted && "accepted".equals(reason)) {
                throw new IllegalArgumentException("rejected admission must not use reason=accepted");
            }
            if (accepted && !offeredCapabilityManifest.equals(requiredCapabilityManifest)) {
                throw new IllegalArgumentException("accepted admission requires matching capability manifests");
            }
        }
    }
}
