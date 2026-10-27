package cc.starsector.client.net;

import java.util.Objects;

/**
 * One demultiplexed message read off the live server stream by
 * {@link MultiplexedServerStreamReader}: a world-snapshot
 * {@link ServerSnapshotFrame} (the {@code AWSS} pose lane), an explicit
 * {@link ClientEntityDespawn} (the durable {@code EDSP} despawn lane), a
 * tick-stamped {@link ClientGameplayEdgeEvent} (the durable {@code GPEE}
 * gameplay-edge-event lane), an explicit
 * {@link ClientMatchReset} next-round boundary (the durable {@code MRST}
 * match-reset lane, L1 session-lifecycle), or a {@link ClientSeatNames}
 * seat-name table (the once-per-connection {@code SNMS} handshake frame), or a
 * delta+keyframe ship-lane {@link AwsdSnapshotReader.AwsdFrame} (the {@code AWSD} lane,
 * ships ride it when the server's {@code cc.net.snapDelta} is ON).
 *
 * <p>The lanes share one socket stream — the server interleaves a per-tick
 * snapshot with the durable events derived from its full authoritative state, plus
 * an inline match-reset between rounds on the persistent socket — so the client reads
 * a single byte stream and routes each frame by its leading magic. This tagged union
 * is what the demultiplexer yields per frame; a consumer switches on {@link #kind()}
 * (or the {@code is*()} helpers). Exactly one of {@link #frame()} / {@link #despawn()}
 * / {@link #gameplayEdgeEvent()} / {@link #matchReset()} / {@link #seatNames()} is
 * non-null for a given message.
 *
 * <p>Internally the union holds one {@code Object} payload typed by {@link #kind()}
 * (typed-factory conversion) — a new lane adds an enum constant, a
 * one-line factory, and a cast-if-kind accessor, never another null-padded ctor copy.
 */
public final class ServerStreamMessage {

    /** Which lane this message came from. */
    public enum Kind {
        /** A world-snapshot frame (the {@code AWSS} pose lane). */
        SNAPSHOT,
        /** An explicit entity-despawn event (the durable {@code EDSP} lane). */
        DESPAWN,
        /** A tick-stamped gameplay edge event (the durable {@code GPEE} lane). */
        GAMEPLAY_EDGE_EVENT,
        /** An explicit next-round match-reset boundary (the durable {@code MRST} lane). */
        MATCH_RESET,
        /** The once-per-connection seat-name table (the {@code SNMS} handshake frame). */
        SEAT_NAMES,
        /** A per-seat seat-name record (the {@code SNMR} lane of N-aware seat names —
         * fanned on announce, spooled as admission/takeover catch-up). */
        SEAT_NAME_RECORD,
        /** A full-state seat-state event (the durable {@code SEAT} lane of the N-peer serve). */
        SEAT_STATE,
        /** A tolerated reserved control frame ({@code DESC}/{@code HOUS}) — no
         * consumer this client generation; predict sessions count-ignore it. */
        RESERVED_CONTROL,
        /** A delta+keyframe ship-lane frame (the {@code AWSD} lane — ships ride
         * it when the server's {@code cc.net.snapDelta} is ON; non-ships keep the AWSS lane). */
        AWSD_SNAPSHOT,
        /** A server-relayed chat line (the {@code CHTD} lane of in-match text chat —
         * seat-stamped + name-stamped by the server; echoed to the sender as delivery confirm). */
        CHAT,
        /** A server-authoritative hulk-fracture edge (the {@code HKFR} lane of hulk-split
         * sync — fanned on every authoritative corpse split/piece removal; no admission catch-up). */
        HULK_FRACTURE,
        /** A server-authoritative corpse-pose keyframe (the {@code HKPS} lane —
         * fanned per corpse-family piece at a low cadence so clients adopt the server's corpse
         * kinematics; no admission catch-up, the periodic cadence self-heals). */
        HULK_POSE,
        /** A server-authoritative hulk-cut-geometry record (the {@code HKCG} lane —
         * four per authoritative carve, fanned BEFORE the paired {@code HKFR} ADD so the
         * client latch is warm at edge arrival; no admission catch-up). */
        HULK_CUT_GEOMETRY,
        /** A mid-round roster append (the {@code RAPP} lane — one frame per
         * drained wave, replayed per-round to late joiners; the plugin appends the rows to its
         * replica roster so the first-sight adopt can mint the wave hulls). */
        ROSTER_APPEND,
        /** The serve-end match verdict (the {@code MOVR} lane — fanned at most
         * once per serve, before the goodbye BYE; the consumer drives the native end-of-combat
         * presentation from the SERVER's outcome instead of the local auto-end guesser). */
        MATCH_OVER,
        /** A phase-anchor emergency-dive start (the {@code DIVE} lane — fanned
         * once at the server's rising-edge read of {@code emergencyDive}; the client plays the
         * vanilla dive on the local body and takes the paired {@code EDSP DEPARTED} corpse-free). */
        PHASE_DIVE,
        /** A server-delivered ship-variant definition (the {@code VDEF} lane —
         * the armed door bursts the whole boot catalog once per admission, BEFORE any RAPP replay
         * row can name a wire-only variant; the client folds each into the engine's own variant
         * registry so respawn re-points and wave adds resolve with no kit-local file). */
        VARIANT_DEFINITION,
        /** A custom-variant submission receipt (the {@code VACK} lane — the
         * server's unicast verdict on this session's ONE {@code VSUB}: ACCEPTED with the minted
         * canonical id, or REJECTED with a {@code DoorRejectReasons.custom()} token; the combat
         * receipt lane toasts it, the door's held pick resolves server-side). */
        VARIANT_RECEIPT,
        /** A posture wire block (the {@code PSTB} lane of lane posture — the
         * server's once-per-admission CTRL spool stamping the admission's server-set
         * experiment/diagnostic tag; schema 1 is knob-empty by design). */
        POSTURE_BLOCK,
        /** A seat module-mount handover (the {@code MMNT} lane of module
         * piloting — fanned per mount/dismount BEFORE the paired RAPP row, and replayed per
         * live mount at admission BEFORE the RAPP catch-up; the client repoints the seat's
         * fixed wire id between hull and station module). */
        MODULE_MOUNT,
        /** A command-transfer control edge (the {@code XFER} lane —
         * fanned per begin/deny/commit/abort at the serve lane's emission sites, and replayed
         * per in-flight row as BEGIN at admission AFTER the RAPP catch-up; the client folds
         * these count-only until the L4 own-view pod-riding leg). */
        COMMAND_TRANSFER,
        /** A batched per-piloted-seat aim-point frame (the {@code AIMP} lane
         * for remote turret aim — ONE shared change-gated frame per due tick ≈8 Hz +
         * ≈1 s keepalive; the client drops its own row and slews remote piloted ships'
         * manual turret groups via the engine-native mouseTarget seam). */
        PILOT_AIM,
        /** A batched asteroid-pose frame (the {@code ASTP} lane -- the server keeper's
         * ALIVE/DESTROYED/EXPIRED rows; the client's AsteroidLane mints/adopts/kills the local
         * twins). */
        ASTEROID_POSE,
        /** One structured-listing frame (the {@code LSTD} lane —
         * the server's reply to a no-arg {@code /ship}/{@code /module}/{@code /transfer},
         * unicast to the requesting seat as a ≤255-frame drip; the picker lane assembles
         * frames per listingId latest-wins and offers complete listings to the picker law). */
        LISTING,
        /** One raw 22 B voice sheet (the {@code VCFG} lane, schema 2 — sent last in
         * the admit spool and on every {@code /voice} retune; carried RAW so the session route
         * can count-and-drop an out-of-domain sheet instead of dying, see
         * {@link VoiceConfigReader}). */
        VOICE_CONFIG
    }

    private final Kind kind;
    /** The one non-null lane payload; its runtime type is fixed by {@link #kind}. */
    private final Object payload;

    private ServerStreamMessage(Kind kind, Object payload) {
        this.kind = kind;
        this.payload = payload;
    }

    /** Wrap a decoded world-snapshot frame. */
    public static ServerStreamMessage snapshot(ServerSnapshotFrame frame) {
        return new ServerStreamMessage(Kind.SNAPSHOT, Objects.requireNonNull(frame, "frame"));
    }

    /** Wrap a decoded explicit despawn event. */
    public static ServerStreamMessage despawn(ClientEntityDespawn despawn) {
        return new ServerStreamMessage(Kind.DESPAWN, Objects.requireNonNull(despawn, "despawn"));
    }

    /** Wrap a decoded gameplay edge event. */
    public static ServerStreamMessage gameplayEdgeEvent(ClientGameplayEdgeEvent event) {
        return new ServerStreamMessage(Kind.GAMEPLAY_EDGE_EVENT,
                Objects.requireNonNull(event, "event"));
    }

    /** Wrap a decoded explicit match-reset boundary. */
    public static ServerStreamMessage matchReset(ClientMatchReset matchReset) {
        return new ServerStreamMessage(Kind.MATCH_RESET,
                Objects.requireNonNull(matchReset, "matchReset"));
    }

    /** Wrap a decoded seat-name table (the {@code SNMS} handshake frame). */
    public static ServerStreamMessage seatNames(ClientSeatNames seatNames) {
        return new ServerStreamMessage(Kind.SEAT_NAMES,
                Objects.requireNonNull(seatNames, "seatNames"));
    }

    /** Wrap a decoded per-seat name record (the {@code SNMR} lane). */
    public static ServerStreamMessage seatNameRecord(ClientSeatNameRecord seatNameRecord) {
        return new ServerStreamMessage(Kind.SEAT_NAME_RECORD,
                Objects.requireNonNull(seatNameRecord, "seatNameRecord"));
    }

    /** Wrap a decoded seat-state event (the durable {@code SEAT} lane). */
    public static ServerStreamMessage seatState(ClientSeatStateEvent seatState) {
        return new ServerStreamMessage(Kind.SEAT_STATE,
                Objects.requireNonNull(seatState, "seatState"));
    }

    /** Wrap a tolerated reserved control frame ({@code DESC}/{@code HOUS}). */
    public static ServerStreamMessage reservedControl(ClientReservedControl reservedControl) {
        return new ServerStreamMessage(Kind.RESERVED_CONTROL,
                Objects.requireNonNull(reservedControl, "reservedControl"));
    }

    /** Wrap a decoded delta+keyframe ship-lane frame (the {@code AWSD} lane). */
    public static ServerStreamMessage awsdSnapshot(AwsdSnapshotReader.AwsdFrame awsdFrame) {
        return new ServerStreamMessage(Kind.AWSD_SNAPSHOT,
                Objects.requireNonNull(awsdFrame, "awsdFrame"));
    }

    /** Wrap a decoded server-relayed chat line (the {@code CHTD} lane). */
    public static ServerStreamMessage chat(ClientChatMessage chatMessage) {
        return new ServerStreamMessage(Kind.CHAT,
                Objects.requireNonNull(chatMessage, "chatMessage"));
    }

    /** Wrap a decoded server-authoritative hulk-fracture edge (the {@code HKFR} lane). */
    public static ServerStreamMessage hulkFracture(ClientHulkFractureRecord hulkFracture) {
        return new ServerStreamMessage(Kind.HULK_FRACTURE,
                Objects.requireNonNull(hulkFracture, "hulkFracture"));
    }

    /** Wrap a decoded server-authoritative corpse-pose keyframe (the {@code HKPS} lane). */
    public static ServerStreamMessage hulkPose(ClientHulkPoseRecord hulkPose) {
        return new ServerStreamMessage(Kind.HULK_POSE,
                Objects.requireNonNull(hulkPose, "hulkPose"));
    }

    /** Wrap a decoded server-authoritative hulk-cut-geometry record (the {@code HKCG} lane). */
    public static ServerStreamMessage hulkCutGeometry(ClientHulkCutGeometryRecord hulkCutGeometry) {
        return new ServerStreamMessage(Kind.HULK_CUT_GEOMETRY,
                Objects.requireNonNull(hulkCutGeometry, "hulkCutGeometry"));
    }

    /** Wrap a decoded mid-round roster append (the {@code RAPP} lane). */
    public static ServerStreamMessage rosterAppend(ClientRosterAppend rosterAppend) {
        return new ServerStreamMessage(Kind.ROSTER_APPEND,
                Objects.requireNonNull(rosterAppend, "rosterAppend"));
    }

    /** Wrap a decoded serve-end match verdict (the {@code MOVR} lane). */
    public static ServerStreamMessage matchOver(ClientMatchOverRecord matchOver) {
        return new ServerStreamMessage(Kind.MATCH_OVER,
                Objects.requireNonNull(matchOver, "matchOver"));
    }

    /** Wrap a decoded phase-dive start (the {@code DIVE} lane). */
    public static ServerStreamMessage phaseDive(ClientPhaseDive phaseDive) {
        return new ServerStreamMessage(Kind.PHASE_DIVE,
                Objects.requireNonNull(phaseDive, "phaseDive"));
    }

    /** Wrap a decoded ship-variant definition (the {@code VDEF} lane). */
    public static ServerStreamMessage variantDefinition(ClientVariantDefinition variantDefinition) {
        return new ServerStreamMessage(Kind.VARIANT_DEFINITION,
                Objects.requireNonNull(variantDefinition, "variantDefinition"));
    }

    /** W15 v0: one decoded {@code VACK} custom-variant receipt. */
    public static ServerStreamMessage variantReceipt(ClientVariantReceipt variantReceipt) {
        return new ServerStreamMessage(Kind.VARIANT_RECEIPT,
                Objects.requireNonNull(variantReceipt, "variantReceipt"));
    }

    /** Wrap a decoded posture wire block (the {@code PSTB} lane). */
    public static ServerStreamMessage postureBlock(ClientPostureBlock postureBlock) {
        return new ServerStreamMessage(Kind.POSTURE_BLOCK,
                Objects.requireNonNull(postureBlock, "postureBlock"));
    }

    /** Wrap a decoded seat module-mount handover (the {@code MMNT} lane). */
    public static ServerStreamMessage moduleMount(ClientModuleMount moduleMount) {
        return new ServerStreamMessage(Kind.MODULE_MOUNT,
                Objects.requireNonNull(moduleMount, "moduleMount"));
    }

    /** Wrap a decoded command-transfer control edge (the {@code XFER} lane). */
    public static ServerStreamMessage commandTransfer(ClientCommandTransfer commandTransfer) {
        return new ServerStreamMessage(Kind.COMMAND_TRANSFER,
                Objects.requireNonNull(commandTransfer, "commandTransfer"));
    }

    /** Wrap a decoded batched pilot-aim frame (the {@code AIMP} lane). */
    public static ServerStreamMessage pilotAims(ClientPilotAimFrame pilotAims) {
        return new ServerStreamMessage(Kind.PILOT_AIM,
                Objects.requireNonNull(pilotAims, "pilotAims"));
    }

    /** Wrap a decoded batched asteroid-pose frame (the {@code ASTP} lane). */
    public static ServerStreamMessage asteroidPose(ClientAsteroidFrame asteroidPose) { //
        return new ServerStreamMessage(Kind.ASTEROID_POSE,
                Objects.requireNonNull(asteroidPose, "asteroidPose"));
    }

    /** Wrap a decoded structured-listing frame (the {@code LSTD} lane). */
    public static ServerStreamMessage listing(ClientListingRecord listing) {
        return new ServerStreamMessage(Kind.LISTING,
                Objects.requireNonNull(listing, "listing"));
    }

    /** Wrap one raw {@code VCFG} frame (magic included). */
    public static ServerStreamMessage voiceConfig(byte[] frame) {
        return new ServerStreamMessage(Kind.VOICE_CONFIG,
                Objects.requireNonNull(frame, "frame"));
    }

    public Kind kind() {
        return kind;
    }

    public boolean isSnapshot() {
        return kind == Kind.SNAPSHOT;
    }

    public boolean isDespawn() {
        return kind == Kind.DESPAWN;
    }

    public boolean isGameplayEdgeEvent() {
        return kind == Kind.GAMEPLAY_EDGE_EVENT;
    }

    public boolean isMatchReset() {
        return kind == Kind.MATCH_RESET;
    }

    /** The snapshot frame, or {@code null} when this is not a snapshot message. */
    public ServerSnapshotFrame frame() {
        return kind == Kind.SNAPSHOT ? (ServerSnapshotFrame) payload : null;
    }

    /** The despawn event, or {@code null} when this is not a despawn message. */
    public ClientEntityDespawn despawn() {
        return kind == Kind.DESPAWN ? (ClientEntityDespawn) payload : null;
    }

    /** The gameplay edge event, or {@code null} when this is not an edge-event message. */
    public ClientGameplayEdgeEvent gameplayEdgeEvent() {
        return kind == Kind.GAMEPLAY_EDGE_EVENT ? (ClientGameplayEdgeEvent) payload : null;
    }

    /** The match-reset boundary, or {@code null} when this is not a match-reset message. */
    public ClientMatchReset matchReset() {
        return kind == Kind.MATCH_RESET ? (ClientMatchReset) payload : null;
    }

    public boolean isSeatNames() {
        return kind == Kind.SEAT_NAMES;
    }

    /** The seat-name table, or {@code null} when this is not a seat-names message. */
    public ClientSeatNames seatNames() {
        return kind == Kind.SEAT_NAMES ? (ClientSeatNames) payload : null;
    }

    public boolean isSeatNameRecord() {
        return kind == Kind.SEAT_NAME_RECORD;
    }

    /** The per-seat name record, or {@code null} when this is not a seat-name-record message. */
    public ClientSeatNameRecord seatNameRecord() {
        return kind == Kind.SEAT_NAME_RECORD ? (ClientSeatNameRecord) payload : null;
    }

    public boolean isSeatState() {
        return kind == Kind.SEAT_STATE;
    }

    /** The seat-state event, or {@code null} when this is not a seat-state message. */
    public ClientSeatStateEvent seatState() {
        return kind == Kind.SEAT_STATE ? (ClientSeatStateEvent) payload : null;
    }

    public boolean isReservedControl() {
        return kind == Kind.RESERVED_CONTROL;
    }

    /** The tolerated reserved control frame, or {@code null} when this is not one. */
    public ClientReservedControl reservedControl() {
        return kind == Kind.RESERVED_CONTROL ? (ClientReservedControl) payload : null;
    }

    public boolean isAwsdSnapshot() {
        return kind == Kind.AWSD_SNAPSHOT;
    }

    /** The delta+keyframe ship-lane frame, or {@code null} when this is not one. */
    public AwsdSnapshotReader.AwsdFrame awsdFrame() {
        return kind == Kind.AWSD_SNAPSHOT ? (AwsdSnapshotReader.AwsdFrame) payload : null;
    }

    public boolean isChat() {
        return kind == Kind.CHAT;
    }

    /** The server-relayed chat line, or {@code null} when this is not a chat message. */
    public ClientChatMessage chatMessage() {
        return kind == Kind.CHAT ? (ClientChatMessage) payload : null;
    }

    public boolean isHulkFracture() {
        return kind == Kind.HULK_FRACTURE;
    }

    /** The hulk-fracture edge, or {@code null} when this is not a hulk-fracture message. */
    public ClientHulkFractureRecord hulkFracture() {
        return kind == Kind.HULK_FRACTURE ? (ClientHulkFractureRecord) payload : null;
    }

    public boolean isHulkPose() {
        return kind == Kind.HULK_POSE;
    }

    /** The corpse-pose keyframe, or {@code null} when this is not a hulk-pose message. */
    public ClientHulkPoseRecord hulkPose() {
        return kind == Kind.HULK_POSE ? (ClientHulkPoseRecord) payload : null;
    }

    public boolean isHulkCutGeometry() {
        return kind == Kind.HULK_CUT_GEOMETRY;
    }

    /** The hulk-cut-geometry record, or {@code null} when this is not one. */
    public ClientHulkCutGeometryRecord hulkCutGeometry() {
        return kind == Kind.HULK_CUT_GEOMETRY ? (ClientHulkCutGeometryRecord) payload : null;
    }

    public boolean isRosterAppend() {
        return kind == Kind.ROSTER_APPEND;
    }

    /** The mid-round roster append, or {@code null} when this is not one. */
    public ClientRosterAppend rosterAppend() {
        return kind == Kind.ROSTER_APPEND ? (ClientRosterAppend) payload : null;
    }

    public boolean isMatchOver() {
        return kind == Kind.MATCH_OVER;
    }

    /** The serve-end match verdict, or {@code null} when this is not one. */
    public ClientMatchOverRecord matchOver() {
        return kind == Kind.MATCH_OVER ? (ClientMatchOverRecord) payload : null;
    }

    public boolean isPhaseDive() {
        return kind == Kind.PHASE_DIVE;
    }

    /** The phase-dive start, or {@code null} when this is not one. */
    public ClientPhaseDive phaseDive() {
        return kind == Kind.PHASE_DIVE ? (ClientPhaseDive) payload : null;
    }

    public boolean isVariantDefinition() {
        return kind == Kind.VARIANT_DEFINITION;
    }

    /** The ship-variant definition, or {@code null} when this is not one. */
    public ClientVariantDefinition variantDefinition() {
        return kind == Kind.VARIANT_DEFINITION ? (ClientVariantDefinition) payload : null;
    }

    public boolean isVariantReceipt() {
        return kind == Kind.VARIANT_RECEIPT;
    }

    /** The custom-variant receipt, or {@code null} when this is not one. */
    public ClientVariantReceipt variantReceipt() {
        return kind == Kind.VARIANT_RECEIPT ? (ClientVariantReceipt) payload : null;
    }

    public boolean isPostureBlock() {
        return kind == Kind.POSTURE_BLOCK;
    }

    /** The posture wire block, or {@code null} when this is not one. */
    public ClientPostureBlock postureBlock() {
        return kind == Kind.POSTURE_BLOCK ? (ClientPostureBlock) payload : null;
    }

    public boolean isModuleMount() {
        return kind == Kind.MODULE_MOUNT;
    }

    /** The seat module-mount handover, or {@code null} when this is not one. */
    public ClientModuleMount moduleMount() {
        return kind == Kind.MODULE_MOUNT ? (ClientModuleMount) payload : null;
    }

    public boolean isCommandTransfer() {
        return kind == Kind.COMMAND_TRANSFER;
    }

    /** The command-transfer control edge, or {@code null} when this is not one. */
    public ClientCommandTransfer commandTransfer() {
        return kind == Kind.COMMAND_TRANSFER ? (ClientCommandTransfer) payload : null;
    }

    public boolean isPilotAims() {
        return kind == Kind.PILOT_AIM;
    }

    /** The batched pilot-aim frame, or {@code null} when this is not one. */
    public ClientPilotAimFrame pilotAims() {
        return kind == Kind.PILOT_AIM ? (ClientPilotAimFrame) payload : null;
    }

    //
    public boolean isAsteroidPose() {
        return kind == Kind.ASTEROID_POSE;
    }

    /** The batched asteroid-pose frame, or {@code null} when this is not one. */
    public ClientAsteroidFrame asteroidPose() {
        return kind == Kind.ASTEROID_POSE ? (ClientAsteroidFrame) payload : null;
    }

    public boolean isListing() {
        return kind == Kind.LISTING;
    }

    /** The structured-listing frame, or {@code null} when this is not one. */
    public ClientListingRecord listing() {
        return kind == Kind.LISTING ? (ClientListingRecord) payload : null;
    }

    /** The raw {@code VCFG} frame, or {@code null} when this is not one. */
    public byte[] voiceConfigFrame() {
        return kind == Kind.VOICE_CONFIG ? (byte[]) payload : null;
    }

    @Override
    public String toString() {
        return "ServerStreamMessage{" + kind + "," + payload + "}";
    }
}
