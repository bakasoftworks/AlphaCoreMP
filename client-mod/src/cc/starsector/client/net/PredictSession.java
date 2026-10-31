package cc.starsector.client.net;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * The client-side transport seam, extracted from
 * {@link PredictReconcileClientSession}'s public surface so the predict-and-reconcile
 * plugin drives EITHER transport ({@code cc.predict.transport=tcp|udp}, resolved once in
 * {@code CcClientModPlugin.dialPredictAtJoin}) through one type. Implementations:
 * {@link PredictReconcileClientSession} (TCP — the retired fallback transport: RE
 * tooling only, never grows features) and {@link PredictReconcileDatagramSession} (UDP).
 *
 *
 * <p>Semantics are the TCP session's (its methods carry the canonical contracts); the datagram
 * sibling documents its deliberate deviations in-class (clock-vs-stream framing, the round
 * barrier, {@code udp_silence}/{@code ctrl_window_stall} death reasons). {@code connect} stays a
 * static factory on each concrete class — construction is transport-specific by nature.
 */
public interface PredictSession extends AutoCloseable {

    /** One AWSS world snapshot DOWN, or {@code null} at a clean end of session; throws on corrupt
     * frames or a dead session. The plain (no-demux) receive — an event/reset/despawn/seat-name
     * frame on the down lane is a contract violation for this caller and fails closed. */
    ServerSnapshotFrame receiveSnapshot() throws IOException;

    /** Demuxed receive: routes {@code GPEE} to {@code onEdgeEvent}; counts+skips
     * {@code MRST}/{@code EDSP}/{@code SNMS}; returns at the next {@code AWSS} (or {@code null}). */
    ServerSnapshotFrame receiveSnapshot(Consumer<? super ClientGameplayEdgeEvent> onEdgeEvent)
            throws IOException;

    /** Reset-aware demuxed receive: adds {@code MRST} routing to {@code onReset} (the in-line
     * next-round boundary, delivered immediately before the fresh round's snapshot). */
    ServerSnapshotFrame receiveSnapshot(
            Consumer<? super ClientGameplayEdgeEvent> onEdgeEvent,
            Consumer<? super ClientMatchReset> onReset) throws IOException;

    /** Adds the {@code EDSP} despawn lane (ignore→consume flip). */
    ServerSnapshotFrame receiveSnapshot(
            Consumer<? super ClientGameplayEdgeEvent> onEdgeEvent,
            Consumer<? super ClientMatchReset> onReset,
            Consumer<? super ClientEntityDespawn> onDespawn) throws IOException;

    /** The fully-demuxed receive: adds the once-per-connection {@code SNMS} seat-name table
     * — the overload the predict combat plugin's net loop runs. */
    ServerSnapshotFrame receiveSnapshot(
            Consumer<? super ClientGameplayEdgeEvent> onEdgeEvent,
            Consumer<? super ClientMatchReset> onReset,
            Consumer<? super ClientEntityDespawn> onDespawn,
            Consumer<? super ClientSeatNames> onSeatNames) throws IOException;

    /** The AWSD-aware fully-demuxed receive: adds the {@code AWSD} delta+keyframe
     * ship lane — each decoded {@link AwsdSnapshotReader.AwsdFrame} routes to {@code onAwsdFrame}
     * (the consumer folds it; the take keeps draining and still returns at the next {@code AWSS}).
     * Default: delegate to the four-consumer overload — a transport without an AWSD lane keeps its
     * fail-closed posture (the TCP twin's demux throws loudly on an AWSD frame; the retired TCP
     * lane never wires the consumer). */
    default ServerSnapshotFrame receiveSnapshot(
            Consumer<? super ClientGameplayEdgeEvent> onEdgeEvent,
            Consumer<? super ClientMatchReset> onReset,
            Consumer<? super ClientEntityDespawn> onDespawn,
            Consumer<? super ClientSeatNames> onSeatNames,
            Consumer<? super AwsdSnapshotReader.AwsdFrame> onAwsdFrame) throws IOException {
        return receiveSnapshot(onEdgeEvent, onReset, onDespawn, onSeatNames);
    }

    /** The chat-aware fully-demuxed receive: adds the {@code CHTD} chat-relay lane —
     * each decoded {@link ClientChatMessage} routes to {@code onChat} on the receive thread (the
     * predict combat plugin's consumer queues it for the render-thread HUD drain). Overloads
     * without {@code onChat} count-ignore chat frames ({@link #chatFramesIgnored} — the
     * {@code SNMS}/{@code EDSP} lesser-overload posture: chat is presentation, dropping it must
     * never kill a session). Default: delegate to the AWSD-aware overload — the retired TCP twin
     * never receives chat (the server relays on the datagram door only). */
    default ServerSnapshotFrame receiveSnapshot(
            Consumer<? super ClientGameplayEdgeEvent> onEdgeEvent,
            Consumer<? super ClientMatchReset> onReset,
            Consumer<? super ClientEntityDespawn> onDespawn,
            Consumer<? super ClientSeatNames> onSeatNames,
            Consumer<? super AwsdSnapshotReader.AwsdFrame> onAwsdFrame,
            Consumer<? super ClientChatMessage> onChat) throws IOException {
        return receiveSnapshot(onEdgeEvent, onReset, onDespawn, onSeatNames, onAwsdFrame);
    }

    /** The seat-name-record-aware fully-demuxed receive (N-aware seat names): adds the
     * {@code SNMR} per-seat name-record lane — each decoded {@link ClientSeatNameRecord} (fanned
     * on announce, spooled as admission/takeover catch-up) routes to {@code onSeatNameRecord} on
     * the receive thread. Overloads without it count-ignore the record
     * ({@link #seatNameRecordFramesIgnored} — the {@code SNMS}/{@code CHTD} lesser-overload
     * posture: names are presentation, dropping one must never kill a session). Default: delegate
     * to the chat-aware overload — the retired TCP twin never receives a fan (the
     * server fans on the datagram door only; its loopback verifier covers the ignore parity). */
    default ServerSnapshotFrame receiveSnapshot(
            Consumer<? super ClientGameplayEdgeEvent> onEdgeEvent,
            Consumer<? super ClientMatchReset> onReset,
            Consumer<? super ClientEntityDespawn> onDespawn,
            Consumer<? super ClientSeatNames> onSeatNames,
            Consumer<? super AwsdSnapshotReader.AwsdFrame> onAwsdFrame,
            Consumer<? super ClientChatMessage> onChat,
            Consumer<? super ClientSeatNameRecord> onSeatNameRecord) throws IOException {
        return receiveSnapshot(onEdgeEvent, onReset, onDespawn, onSeatNames, onAwsdFrame, onChat);
    }

    /** The hulk-fracture-aware fully-demuxed receive (hulk-split sync): adds the
     * {@code HKFR} fracture-edge lane — each decoded {@link ClientHulkFractureRecord} (fanned on
     * every authoritative corpse split / piece removal; no admission catch-up) routes to
     * {@code onHulkFracture} on the receive thread. Overloads without it count-ignore the record
     * ({@link #hulkFractureFramesIgnored} — the {@code SNMR}/{@code CHTD} lesser-overload
     * posture: fracture is presentation, dropping one must never kill a session). Default:
     * delegate to the seat-name-record-aware overload — the retired TCP twin never
     * receives a fan (the server fans on the datagram door only). */
    default ServerSnapshotFrame receiveSnapshot(
            Consumer<? super ClientGameplayEdgeEvent> onEdgeEvent,
            Consumer<? super ClientMatchReset> onReset,
            Consumer<? super ClientEntityDespawn> onDespawn,
            Consumer<? super ClientSeatNames> onSeatNames,
            Consumer<? super AwsdSnapshotReader.AwsdFrame> onAwsdFrame,
            Consumer<? super ClientChatMessage> onChat,
            Consumer<? super ClientSeatNameRecord> onSeatNameRecord,
            Consumer<? super ClientHulkFractureRecord> onHulkFracture) throws IOException {
        return receiveSnapshot(onEdgeEvent, onReset, onDespawn, onSeatNames, onAwsdFrame, onChat,
                onSeatNameRecord);
    }

    /** The corpse-pose-aware fully-demuxed receive: adds the {@code HKPS}
     * keyframe lane — each decoded {@link ClientHulkPoseRecord} (fanned per corpse-family piece
     * at a low cadence; no admission catch-up) routes to {@code onHulkPose} on the receive
     * thread. Overloads without it count-ignore the keyframe
     * ({@link #hulkPoseFramesIgnored} — the {@code HKFR} lesser-overload posture: pose is
     * presentation, dropping one must never kill a session; the periodic cadence self-heals).
     * Default: delegate to the hulk-fracture-aware overload — the retired TCP twin
     * never receives a fan (the server fans on the datagram door only). */
    default ServerSnapshotFrame receiveSnapshot(
            Consumer<? super ClientGameplayEdgeEvent> onEdgeEvent,
            Consumer<? super ClientMatchReset> onReset,
            Consumer<? super ClientEntityDespawn> onDespawn,
            Consumer<? super ClientSeatNames> onSeatNames,
            Consumer<? super AwsdSnapshotReader.AwsdFrame> onAwsdFrame,
            Consumer<? super ClientChatMessage> onChat,
            Consumer<? super ClientSeatNameRecord> onSeatNameRecord,
            Consumer<? super ClientHulkFractureRecord> onHulkFracture,
            Consumer<? super ClientHulkPoseRecord> onHulkPose) throws IOException {
        return receiveSnapshot(onEdgeEvent, onReset, onDespawn, onSeatNames, onAwsdFrame, onChat,
                onSeatNameRecord, onHulkFracture);
    }

    /** The cut-geometry-aware fully-demuxed receive (s-F(1)): adds the
     * {@code HKCG} hulk-cut-geometry lane — each decoded {@link ClientHulkCutGeometryRecord}
     * (four per authoritative carve, fanned BEFORE the paired {@code HKFR} ADD; no admission
     * catch-up) routes to {@code onHulkCutGeometry} on the receive thread. Overloads without it
     * count-ignore the record ({@link #hulkCutGeometryFramesIgnored} — the {@code HKPS}
     * lesser-overload posture: cut geometry is presentation, dropping one degrades that cut to
     * the local no-arg fallback, never kills a session). Default: delegate to the
     * corpse-pose-aware overload — the retired TCP twin never receives a fan (the
     * server fans on the datagram door only). */
    default ServerSnapshotFrame receiveSnapshot(
            Consumer<? super ClientGameplayEdgeEvent> onEdgeEvent,
            Consumer<? super ClientMatchReset> onReset,
            Consumer<? super ClientEntityDespawn> onDespawn,
            Consumer<? super ClientSeatNames> onSeatNames,
            Consumer<? super AwsdSnapshotReader.AwsdFrame> onAwsdFrame,
            Consumer<? super ClientChatMessage> onChat,
            Consumer<? super ClientSeatNameRecord> onSeatNameRecord,
            Consumer<? super ClientHulkFractureRecord> onHulkFracture,
            Consumer<? super ClientHulkPoseRecord> onHulkPose,
            Consumer<? super ClientHulkCutGeometryRecord> onHulkCutGeometry) throws IOException {
        return receiveSnapshot(onEdgeEvent, onReset, onDespawn, onSeatNames, onAwsdFrame, onChat,
                onSeatNameRecord, onHulkFracture, onHulkPose);
    }

    /** The roster-append-aware fully-demuxed receive: adds the {@code RAPP}
     * mid-round roster-append lane — each decoded {@link ClientRosterAppend} (one per drained
     * wave; replayed per-round to late joiners) routes to {@code onRosterAppend} on the receive
     * thread (the predict combat plugin queues it for its render-thread registry append).
     * Overloads without it count-ignore the frame ({@link #rosterAppendFramesIgnored} — the
     * lesser-overload posture; the plugin's net loop always runs THIS widest overload, so a
     * counted ignore marks a caller that must be widened, never a silently-ghosted wave).
     * Default: delegate to the cut-geometry-aware overload — the retired TCP twin
     * never receives a fan (the server fans on the datagram door only). */
    default ServerSnapshotFrame receiveSnapshot(
            Consumer<? super ClientGameplayEdgeEvent> onEdgeEvent,
            Consumer<? super ClientMatchReset> onReset,
            Consumer<? super ClientEntityDespawn> onDespawn,
            Consumer<? super ClientSeatNames> onSeatNames,
            Consumer<? super AwsdSnapshotReader.AwsdFrame> onAwsdFrame,
            Consumer<? super ClientChatMessage> onChat,
            Consumer<? super ClientSeatNameRecord> onSeatNameRecord,
            Consumer<? super ClientHulkFractureRecord> onHulkFracture,
            Consumer<? super ClientHulkPoseRecord> onHulkPose,
            Consumer<? super ClientHulkCutGeometryRecord> onHulkCutGeometry,
            Consumer<? super ClientRosterAppend> onRosterAppend) throws IOException {
        return receiveSnapshot(onEdgeEvent, onReset, onDespawn, onSeatNames, onAwsdFrame, onChat,
                onSeatNameRecord, onHulkFracture, onHulkPose, onHulkCutGeometry);
    }

    /** {@code EDSP} frames seen and count-ignored by the overloads without an {@code onDespawn}. */
    int despawnFramesIgnored();

    /** {@code MRST} frames seen and count-ignored by the single-consumer overload. */
    int matchResetFramesIgnored();

    /** {@code SNMS} frames seen and count-ignored by the overloads without {@code onSeatNames}. */
    int seatNamesFramesIgnored();

    /** Reserved {@code DESC}/{@code HOUS} control frames seen and count-ignored (
     * tolerated by envelope so a future consumer arc never forces a re-kit); default 0 for
     * implementations that pre-date the reservation. */
    default int reservedCtrlIgnored() {
        return 0;
    }

    /** The session's ctrl round epoch (+1 per drained MRST,
     * stamped on RAPP/MRST frames at intake) — the roster-append lane SEEDS its retired count from
     * it at the plugin bind (the session outlives a mission; the lane does not); default 0 for
     * implementations that never stamp (the TCP twin). */
    default int ctrlRoundEpoch() {
        return 0;
    }

    /** The live socket's effective kernel receive buffer (SO_RCVBUF) in bytes — the plugin's
     * OPEN row carries it (the joiner-side overflow witness); default -1 for
     * implementations without a datagram socket (the TCP twin). */
    default int udpReceiveBufferBytes() {
        return -1;
    }

    /** The session-owned voice orchestrator (VCFG sheet, VOICE-channel
     * down decode, thread-safe {@code sendVoice} uplink); default null for implementations
     * without the lane (the plugin's voice/presence lanes stay idle on null). */
    default cc.starsector.client.voice.VoiceClient voice() {
        return null;
    }

    /** The latched serve-end {@code MOVR} match verdict, or {@code null}
     * until the server fans one — the C6 consumer poll surface; default null for
     * implementations without the lane (the verdict simply never arrives there). */
    default ClientMatchOverRecord matchOverRecord() {
        return null;
    }

    /** The latched KICKED-notice reason code (HOUS role 1, first-latch-wins
     * off the CTRL drain), or {@code -1} until the server mails one at a kick; the
     * {@code matchOverRecord} poll idiom. Default -1 for implementations without the lane. */
    default int kickedNoticeReason() {
        return -1;
    }

    /** Drain-take the {@code DIVE} phase-dive-start records queued since the last take
     * (the MATCH_OVER internal-route idiom: latched in-session off the
     * CTRL drain, no per-consumer overload chain to widen); default empty for implementations
     * without the lane (the dive simply never arrives there). */
    default java.util.List<ClientPhaseDive> takePhaseDives() {
        return java.util.Collections.emptyList();
    }

    /** Drain-take the {@code VDEF} variant definitions queued since the last take (
     * the same internal-route idiom: queued in-session off the CTRL drain, no
     * per-consumer overload chain to widen; the plugin's fold lane polls this just ahead of its
     * RAPP fold); default empty for implementations without the lane (an armed door never admits
     * a session type that lacks it — a legacy serve simply never fans {@code VDEF}). */
    default java.util.List<ClientVariantDefinition> takeVariantDefinitions() {
        return java.util.Collections.emptyList();
    }

    /** {@code VDEF} frames the transport dropped at its queue cap since connect (0 for lane-less
     * transports); the fold lane logs the first drop. */
    default int variantDefinitionFramesDropped() {
        return 0;
    }

    /** D-FIRSTSHIP: read-only PEEK of every {@code VDEF} definition on hand
     * BEFORE combat — the admission catalog burst (W7c B2) parks in the CTRL queue until the
     * first combat drain, but the mission build (menu, game thread) needs a join pick's
     * definition NOW when the W7c B3 kit strip shipped no local {@code .variant} for it.
     * Consumes nothing (the drain re-reads the same bytes later); default empty. */
    default java.util.List<ClientVariantDefinition> peekVariantDefinitions() {
        return java.util.Collections.emptyList();
    }
    /** W15 v0: ONE {@code VSUB} custom-variant submission UP on
     * the reliable CTRL lane — the client-local definition ({@code saves/common/cc_variants/}),
     * answered by a unicast {@code VACK} ({@link #takeVariantReceipts}). Sent once per connect
     * right after the session publish; no client retry (the lane is reliable, the server keys
     * dups by body CRC). Default: THROW — submissions ride the datagram CTRL lane only (the
     * retired TCP twin never grows the feature); loud beats silent if that ever breaks. */
    default void sendVariantSubmit(ClientVariantDefinition variant) throws IOException {
        throw new IOException("transport has no custom-variant lane (VSUB is datagram-only)");
    }
    /**
     * A {@code VSUB} whose body carries the keep-only ask
     * when {@code keepOnly} ({@code VariantSubmitWriter.encodeFrame(seq, def, tables, true)}): the server
     * mints the id but seeds NO next-respawn pick — the host's Promote adds a ship without changing what
     * the host flies next. {@code keepOnly=false} IS the plain submission ({@link #sendVariantSubmit(ClientVariantDefinition)}).
     * Default: a transport that does not override this cannot set the bit, so a keep-only ask THROWS
     * rather than going out as a plain submission that would change the pick behind the host's back.
     * The one-arg default is never routed here (no mutual default delegation, so a transport that
     * overrides neither cannot recurse).
     */
    default void sendVariantSubmit(ClientVariantDefinition variant, boolean keepOnly) throws IOException {
        if (!keepOnly) {
            sendVariantSubmit(variant);
            return;
        }
        throw new IOException("transport cannot send a keep-only variant submission");
    }
    /** Was the {@code VSUB} carrying {@code submitSeq} sent keep-only (the host's Promote)? Answered ONCE per seq
     *  (the mark clears on the read), so the combat receipt lane can keep a keep-only accept's "next respawn" toast
     *  out of the chat: a Promote never changes what the host flies next, and its accept carries a minted id that a
     *  chat line must not show. Default false: a transport that cannot send
     *  keep-only never marks one. */
    default boolean takeKeepOnlyMark(int submitSeq) {
        return false;
    }
    /** Drain-take the {@code VACK} receipts queued since the last take (the same
     * internal-route idiom as {@link #takeVariantDefinitions}; the combat receipt lane polls
     * this each frame and toasts the verdict). Default empty for lane-less transports. */
    default java.util.List<ClientVariantReceipt> takeVariantReceipts() {
        return java.util.Collections.emptyList();
    }
    /** Read-only PEEK of every {@code VACK} receipt on hand (queued-undrained + drained, the
     * {@link #peekVariantDefinitions} shape) — a menu-thread read BEFORE combat drains CTRL.
     * Consumes nothing; default empty. */
    default java.util.List<ClientVariantReceipt> peekVariantReceipts() {
        return java.util.Collections.emptyList();
    }

    /** Drain-take the latest {@code PSTB} posture wire block, or {@code null} when none arrived
     * since the last take (the same internal-route idiom: latched
     * latest-wins in-session off the CTRL drain; the combat plugin's take triggers the one-shot
     * sidelog posture dump, so the take-clears contract IS the idempotence). Default null for
     * implementations without the lane (a posture block simply never arrives there). */
    default ClientPostureBlock takePostureBlockOrNull() {
        return null;
    }

    /** Drain-take the {@code MMNT} module-mount handovers queued since the last take
     * (the same internal-route idiom: queued in-session off the CTRL
     * drain, no per-consumer overload chain to widen; the plugin's mount lane drains this
     * BEFORE its RAPP drain so wire order MMNT≺RAPP is preserved render-side); default empty
     * for implementations without the lane (a mount handover simply never arrives there). */
    default java.util.List<ClientModuleMount> takeModuleMounts() {
        return java.util.Collections.emptyList();
    }

    /** A PEEK (never a take) of the seat wire ids of
     * the {@code MMNT} handovers queued and not yet taken by {@link #takeModuleMounts()} (any kind)
     * — the registry rebuild's wrap fold skips those seats' RAPP rows so MMNT≺RAPP holds there too;
     * default empty for implementations without the lane (a mount handover never queues there). */
    default java.util.Set<Long> peekQueuedModuleMountSeats() {
        return java.util.Collections.emptySet();
    }

    /** Drain-take the {@code XFER} command-transfer control edges queued since the last take
     * (the same internal-route idiom: queued in-session off the CTRL
     * drain, no per-consumer overload chain to widen; the plugin drains this count-only until
     * the L4 own-view pod-riding leg applies them); default empty for implementations without
     * the lane (a transfer edge simply never arrives there). */
    default java.util.List<ClientCommandTransfer> takeCommandTransfers() {
        return java.util.Collections.emptyList();
    }

    /** Drain-take the {@code AIMP} batched pilot-aim frames queued since the last take
     * (the same internal-route idiom: queued in-session off the
     * CTRL drain, no per-consumer overload chain to widen; the plugin's remote-aim lane polls
     * this per frame and folds latest-wins per ship); default empty for implementations
     * without the lane (the aim fan simply never arrives there). */
    default java.util.List<ClientPilotAimFrame> takePilotAims() {
        return java.util.Collections.emptyList();
    }

    /** Drain-take the {@code ASTP} batched asteroid-pose frames queued since the last take
     * (the same internal-route idiom; each frame carries the session's
     * net-thread MRST round epoch); default empty for implementations without the lane.
     * */
    default java.util.List<ClientAsteroidFrame> takeAsteroidPoses() {
        return java.util.Collections.emptyList();
    }

    /** Drain-take the {@code LSTD} structured-listing frames queued since the last take
     * (the same internal-route idiom: queued in-session off the
     * CTRL drain, no per-consumer overload chain to widen; the picker lane polls this per
     * frame and assembles per-listingId latest-wins); default empty for implementations
     * without the lane (a listing frame simply never arrives there). */
    default java.util.List<ClientListingRecord> takeListings() {
        return java.util.Collections.emptyList();
    }

    /** {@code CHTD} chat frames seen and count-ignored by the overloads without {@code onChat};
     * default 0 for implementations without a chat lane. */
    default int chatFramesIgnored() {
        return 0;
    }

    /** {@code SNMR} seat-name records seen and count-ignored by the overloads without
     * {@code onSeatNameRecord}; default 0 for implementations without the lane. */
    default int seatNameRecordFramesIgnored() {
        return 0;
    }

    /** {@code HKFR} hulk-fracture edges seen and count-ignored by the overloads without
     * {@code onHulkFracture}; default 0 for implementations without the lane. */
    default int hulkFractureFramesIgnored() {
        return 0;
    }

    /** {@code HKPS} corpse-pose keyframes seen and count-ignored by the overloads without
     * {@code onHulkPose}; default 0 for implementations without the lane. */
    default int hulkPoseFramesIgnored() {
        return 0;
    }

    /** {@code HKCG} hulk-cut-geometry records seen and count-ignored by the overloads without
     * {@code onHulkCutGeometry}; default 0 for implementations without the lane. */
    default int hulkCutGeometryFramesIgnored() {
        return 0;
    }

    /** {@code RAPP} roster-append frames seen and count-ignored by the overloads without
     * {@code onRosterAppend}; default 0 for implementations without the
     * lane. Nonzero on the live plugin loop = a caller that must be widened (a dropped append
     * would re-field the ghost match). */
    default int rosterAppendFramesIgnored() {
        return 0;
    }

    /** This tick's faithful pilot intent UP, echoing {@link #lastSnapshot()}'s simTick as frameSeq
     * (the welded overload — single-threaded ping-pong callers only). {@code selectedWeaponGroup} =
     * the live-sampled selected-group ordinal ({@code < 0} falls back to the connect-time group);
     * {@code autofireGroupsMask} = the per-group {@code isAutofiring()} bitmask ({@code -1} =
     * absent, server skips the autofire diff). PINT v5:
     * {@code targetWireId} = the live-sampled ship-target designation as a wire id
     * ({@code TARGET_ABSENT} -1 = never sampled, server skips designation; {@code TARGET_NONE}
     * -2 = sampled, no target — server clears iff its hull's target differs); {@code fireEdgeSeq}
     * = the 0..255 wrapping rising-edge counter of the sampled fire button ({@code 0} from a
     * producer that never advances it is legacy-inert — the server latch never arms).
     * PINT v6: {@code activeTubeOrdinal} = the selected ALTERNATING group's
     * active-tube pointer as a {@code getWeaponsCopy()} ordinal ({@code TUBE_ORDINAL_ABSENT} -1 =
     * never sampled / selection not ALTERNATING — the server's tube mirror skips entirely;
     * wire-valid as-is, no fallback substitution — unlike {@code selectedWeaponGroup} there is no
     * connect-time default to fall back to).
     * PINT v7: the pilot's raw input LEVELS, sampled on the render thread and passed through verbatim —
     * {@code keyCodes} = up to 8 held key codes packed ascending then zero pad
     * ({@code PilotIntentFrame.KEYS_EMPTY} 0 = nothing held; a non-canonical pack throws
     * {@link IllegalArgumentException} from the frame constructor), {@code mouseButtons} = u8 (bit b = button
     * b held), {@code controlKeys} = u16 (bit i = the pilot's own key for
     * {@code PilotIntentFrame.CONTROL_KEY_NAMES[i]} held). A producer with no keyboard passes 0L, 0, 0.
     * Levels only, never text; the frame's contract ({@code PilotIntentFrame}) says what the server does with them.
     * */
    void sendIntent(float cursorFacingDeg, float ownShipFacingDeg, int heldControls, boolean fireHeld,
            float aimWorldX, float aimWorldY, int selectedWeaponGroup, int autofireGroupsMask,
            long targetWireId, int fireEdgeSeq, int activeTubeOrdinal,
            long keyCodes, int mouseButtons, int controlKeys)
            throws IOException;

    /** The explicit-echo-tick send the duplex WRITER thread uses — the writer
     * must not read {@link #lastSnapshot()} (reader-thread state); negative = no-snapshot-yet. The three
     * input-level arguments are the welded overload's, verbatim. */
    void sendIntent(float cursorFacingDeg, float ownShipFacingDeg, int heldControls, boolean fireHeld,
            float aimWorldX, float aimWorldY, int selectedWeaponGroup, int autofireGroupsMask,
            long targetWireId, int fireEdgeSeq, int activeTubeOrdinal,
            long keyCodes, int mouseButtons, int controlKeys, long echoTick) throws IOException;

    /** One {@code PDCL} declared-discharge frame UP (rides directly after this tick's
     * {@link #sendIntent} on TCP; the datagram sibling carries it on its reliable CTRL lane).
     * {@code mountIndex} = the discharging weapon's {@code getAllWeapons()} ordinal ({@code -1} =
     * unknown ⇒ legacy serve). {@code muzzleX/muzzleY/bearingDeg/speed}
     * (PDCL v4) = the committed projectile's own ray at the discharge instant,
     * all-NaN when uncaptured (ray law: all-finite requires a real mount, or all-NaN). */
    void sendFireDeclaration(int declSeq, long clientTick, float aimFacingDeg,
            float aimWorldX, float aimWorldY, int mountIndex,
            float muzzleX, float muzzleY, float bearingDeg, float speed) throws IOException;

    /** The ONE {@code SNAM} seat-name announce UP, immediately after connect. */
    void sendSeatName(String name) throws IOException;

    /** One {@code CHTU} chat line UP on the reliable CTRL lane — the server stamps
     * seat + display name and relays a {@code CHTD} to every live session (echo included; the
     * sender renders its own line only on that echo). {@code text} must satisfy
     * {@link ChatWriter#validateChatText} — the leg-4 input law enforces the caps pre-send, so
     * a conforming client can never emit a frame the server's fail-closed decode kills the
     * session over. Default: THROW — chat rides the datagram CTRL lane only (the retired TCP
     * twin never grows the feature); loud beats silent if that ever breaks. */
    default void sendChat(String text) throws IOException {
        throw new IOException("transport has no chat lane (CHTU is datagram-only)");
    }

    /**
     * Whether this session was admitted by the version-13 handshake: there the chat drain
     * never sends a typed {@code /auth} secret readable — it calls {@link #sendAuth13} instead. Fixed for the
     * session's life (never re-negotiated). Default {@code false}: every other transport keeps today's readable path.
     *
     */
    default boolean handshake13() {
        return false;
    }

    /** What {@link #sendAuth13} did with one typed secret. */
    enum Auth13Send {
        /** Not a version-13 session: nothing was proven or sent (the caller's readable path is unchanged). */
        NOT_VERSION_13,
        /** The proof line was handed to the reliable CTRL lane (or died with a dead session, like any chat line). */
        SENT,
        /** No proof can be made on this connection (no context, a refused door share, a blank secret, the counter
         *  spent): nothing was sent. */
        UNAVAILABLE,
        /** The proof was minted against a context whose reliability core an adopt retired before it could enter:
         *  the frame was dropped (never sent under another session); the caller may re-prove once. */
        RETIRED
    }

    /**
     * Version 13: prove {@code secret} — the typed {@code /auth} argument — against the
     * session's current auth context and send the {@code /auth <proof>} line on the reliable CTRL lane; the secret
     * itself never leaves the process. Runs the map + 2 X25519 on the CALLING thread (the net/writer chat drain, never
     * the frame thread). Default: {@link Auth13Send#NOT_VERSION_13}, nothing sent.
     *
     */
    default Auth13Send sendAuth13(String secret) throws IOException {
        return Auth13Send.NOT_VERSION_13;
    }

    /** One {@code SNNK} AWSD delta-reject NACK UP on the reliable CTRL lane —
     * the merger rejected a DELTA for {@code entityId} hold-last (lost keyframe / D5 signal);
     * the server answers with an out-of-turn keyframe. {@code epoch} = the REJECTED delta's
     * epoch, advisory only. Default: THROW — only the datagram session routes AWSD frames, so
     * a transport without an AWSD lane can never lawfully NACK (the retired TCP twin never
     * wires the AWSD consumer); loud beats silent if that invariant ever breaks. */
    default void sendAwsdNack(long entityId, int epoch) throws IOException {
        throw new IOException("transport has no AWSD NACK lane (SNNK is datagram-only)");
    }

    /** One {@code MARK} F9 owner-mark capture UP on the reliable CTRL lane —
     * {@code count} = the client's running mark number (u16 ≥1), {@code clientTick} = its
     * last snapshot sim tick ({@code -1} = none yet); the server prints one
     * {@code owner-marker} row on the serve log. Default: THROW — marks ride the datagram
     * CTRL lane only (the retired TCP twin never grows the feature); the client lane is
     * fail-soft around this, so loud here costs nothing and beats silent. */
    default void sendOwnerMark(int count, long clientTick) throws IOException {
        throw new IOException("transport has no owner-mark lane (MARK is datagram-only)");
    }

    /** The ONE {@code PSTE} posture echo UP at attach (lane-posture): the
     * client's {@code ClientPostureRegistry} resolved-posture digests, verdicted server-side
     * against the compiled baseline (behavior hash decides; full hash is informational drift).
     * UNCONDITIONAL on the datagram lane — detection must not be knob-suppressible, and every
     * hs-bumped kit speaks the frame. Default: THROW — posture rides the datagram CTRL lane
     * only (the retired TCP twin never grows the feature); loud beats silent if that ever
     * breaks. */
    default void sendPostureEcho() throws IOException {
        throw new IOException("transport has no posture lane (PSTE is datagram-only)");
    }

    /**
     * The transport's continuous round-trip estimate in milliseconds, or {@code NaN}
     * when this transport cannot measure one. The datagram session overrides with its reliability
     * core's snap-echo RTT (every inbound datagram echoes the server's high-water mark of our UP
     * intent seqs — no wire change); the TCP session keeps this default: a bare AWSS stream
     * carries no echo of the client's UP, so TCP (the archived fallback transport)
     * reads {@code NaN} honestly rather than faking a number.
     */
    default double netPingMillis() {
        return Double.NaN;
    }

    /**
     * true while the transport's
     * standing re-admit loop is armed after a session death: receives block until re-admission
     * (or a match-end BYE), sends drop counted, and the plugin presents a "reconnecting" state.
     * The TCP session keeps the default: the archived fallback transport has no
     * re-admit loop and dies loud.
     */
    default boolean reconnecting() {
        return false;
    }

    /**
     * wall-clock milliseconds since the CURRENT re-HELLO episode armed,
     * or {@code -1} when not {@link #reconnecting()}. Presentation-only feed for the
     * "reconnecting (N s)" banner — the session's own monotonic clock, so a paused render frame
     * ({@code advance(0)}) never freezes the count the way a frame-time accumulator would.
     * Transports without a re-admit loop keep the default (never reconnecting).
     */
    default long reconnectingElapsedMillis() {
        return -1L;
    }

    /**
     * Inbound silence that PRECEDED the current episode's arm (the
     * {@code udp_silence} deadline, ~60 s), so a banner can present the outage the player felt
     * ({@code silentBefore + elapsed}) instead of the time since the arm alone (a 56 s kill leg
     * read "RECONNECTED after 1 s"). {@code 0} when not reconnecting or on transports without a
     * re-admit loop. Presentation-only; the sidelog arm/welcome stamps are unchanged.
     */
    default long reconnectingSilentBeforeArmMillis() {
        return 0L;
    }

    /**
     * {@code true} while the current re-HELLO episode is the DELIBERATE
     * re-baseline (a mission re-entry / ESC-then-Play re-pick on the kept-open takeover lane —
     * {@code armReAdmission}, no fallback reason), so presentation can hold the "connection lost"
     * pair for a short quiet window instead of announcing an outage the player asked for. A
     * fallback arm (silence / stall / receive-fail) is never deliberate. Default {@code false}.
     */
    default boolean reconnectingDeliberate() {
        return false;
    }

    /** Completed same-object re-admissions (0 on transports without a re-admit
     * loop). The net loop clears per-session drained state (chunk merger) when this advances. */
    default long reAdmits() {
        return 0L;
    }

    /** Attach announces (SNAM/PSTE/VSUB) re-sent after admitted
     *  re-HELLOs — a takeover's fresh server session starts with none of them. 0 on lanes
     *  without a re-admit path. */
    default long attachFramesReplayed() {
        return 0L;
    }

    /** L-R1: the server's per-BOOT life nonce from the latest WELCOME (hs v6
     * {@code u64} tail) — the plugin's fresh-server-life detector at re-admit (differs from the
     * bound life ⇒ full net-world epoch rebaseline). 0 = no WELCOME adopted yet; the archived
     * TCP fallback keeps the default so the detector can never fire there. */
    default long serverLife() {
        return 0L;
    }

    /** W5-D5 teardown-dump counters: zero-default on transports without the B4
     * re-admit loop; the datagram session overrides. The net loop appends them to its
     * {@code ended} line so a deaf-admission flight's artifacts decide door-drop vs
     * never-arrived vs nonce-gate-refused without a debugger. */
    default long reHelloEpisodes() {
        return 0L;
    }

    /** W5-D5 teardown dump — re-HELLO datagrams sent across all episodes (see above). */
    default long reHellosSent() {
        return 0L;
    }

    /** The initial dial's wall-clock HELLO retransmits (0 when the
     *  session has no datagram dial). */
    default int connectHelloRetransmits() {
        return 0;
    }

    /** The initial dial's pre-admission silent drops (non-WELCOME /
     *  foreign-source / undecodable / wrong-nonce datagrams). */
    default int connectPreAdmitDrops() {
        return 0;
    }

    /** Door COOKIE challenges the initial dial answered (0 without a dial). */
    default int connectCookieChallenges() {
        return 0;
    }

    /** Door COOKIE challenges the B4 re-HELLO pump answered. */
    default long reHelloCookieChallenges() {
        return 0L;
    }

    /** Fresh re-HELLO COOKIEs past the nonce generation's ONE give-back
     * (answered with the cookied re-send; slot + doubling kept — the per-packet-rebinding-NAT law). */
    default long reHelloCookieGiveBacksSuppressed() {
        return 0L;
    }

    /** W5-D5 teardown dump — WELCOMEs the nonce gate refused (stale/foreign; see above). */
    default long welcomeDuplicates() {
        return 0L;
    }

    /** W5-D5 teardown dump — fresh-nonce takeover escalations (see above). */
    default long reconnectNonceEscalations() {
        return 0L;
    }

    /** W5-D5 teardown dump — own mission-exit BYE echoes swallowed mid-reconnect (an
     * unswallowed echo took the match-over exit and killed the episode). */
    default long reconnectByeEchoesSwallowed() {
        return 0L;
    }

    /** Sends the transport DROPPED at its gate (reconnecting / authority held /
     * kicked) instead of putting on the wire. The writer loop counts a send only when this
     * does not advance across {@link #sendIntent} — the wedge's "sent=2365" was every
     * one of them a phantom. 0 on transports with no gate. */
    default long sendsDropped() {
        return 0L;
    }

    /** Teardown dump — re-admissions adopted off the previous nonce generation. */
    default long prevGenerationAdopts() {
        return 0L;
    }

    /** Teardown dump — server re-mints adopted while live (late-generation). */
    default long lateGenerationAdopts() {
        return 0L;
    }

    /** Teardown dump — socket recreates (fresh source port) across episodes. */
    default long socketRecreates() {
        return 0L;
    }

    /** true once this session can never again carry a serve: closed, the peer
     * said BYE, or terminally failed (the datagram session's set is exactly its B4 arm-refusal
     * guard minus the healable {@code reconnecting}; TCP reads its socket). Mission init keys on
     * this to tell a re-admittable kept-open app-scoped session (feed re-binds via same-token
     * takeover) from a dead one (refuse + banner instead of a net thread that dies on its first
     * receive → silent LOCAL VANILLA). Default {@code false}: a
     * transport without liveness state stays optimistically consumable. */
    default boolean isDefunct() {
        return false;
    }

    /** True once the server KICKED this session — not defunct by
     * {@link #isDefunct} (the socket may still be open) but never re-HELLOs on its own, so the
     * next Multiplayer Test click re-dials a fresh session instead of re-picking this one.
     * Default {@code false} (no kick lane). */
    default boolean isKickedTerminal() {
        return false;
    }

    /** mission-exit hook: close IFF this session cannot survive the mission menu;
     * returns whether it closed. Default close+{@code true} = the pre-(41) close-at-exit
     * behavior byte-identical (the TCP twin keeps it — the retired twin never grows the feature). The
     * datagram session overrides: a live app-scoped session stays OPEN (reader/timer/B4 armed)
     * but BIDS GOODBYE (supersedes the pre-grace "seat coasts AFK on a live
     * session" model): the BYE TRUE-vacates the seat so server-side grace enters (the cautious
     * AI flies the hull) and the next re-pick lands as a reclaim, never a displacement. A
     * defunct one closes. */
    default boolean closeAtMissionExit() {
        close();
        return true;
    }

    /** Deliberate re-baseline arm: at mission re-acquisition of a LIVE
     * kept-open session, fire a fresh-nonce re-HELLO (the server resolves a same-token TAKEOVER
     * whose fresh sessionId re-fires the newly-live catch-up — seat baseline + per-round RAPP
     * replay + seat-name spool) and hold UP intents until {@link #resumeOwnAuthority}. Returns
     * whether THIS call armed it — true exactly once per kept-open mission exit (the caller's
     * one INFO log; a first-ever mission init never arms). Default {@code false}: a transport
     * without the keep-open lane (the retired TCP twin) never re-baselines. */
    default boolean armReAdmission() {
        return false;
    }

    /** Release the {@link #armReAdmission} UP-intent hold — the predict plugin
     * calls this at its one-shot fresh-world adopt ({@code adoptInitialWorld}), after which own
     * intents are client-authoritative again. Default no-op (no hold to release). */
    default void resumeOwnAuthority() {
    }

    /** The pilot's 16-byte control keymap as resolved at THIS combat init on the game thread ({@code null} = none):
     *  the map every later re-HELLO seals, handed over BEFORE {@link #armReAdmission} so the kept-open takeover
     *  re-HELLO carries the bindings the sampler polls, never the dial-time ones. Default no-op: a
     *  transport without the version-13 re-HELLO pump (the retired TCP twin) seals no keymap.
     *  Main-written */
    default void setPilotKeymap(byte[] keymapOrNull) {
    }

    /** The most recent snapshot received, or {@code null} before the first read. */
    ServerSnapshotFrame lastSnapshot();

    long shipId();

    /** this session's own seat id (the seat whose newest applied
     * {@code SEAT} event carried {@link #shipId}), or -1 when unknown; default -1 for implementations without a
     * seat table (a -1 latch takes no {@code /ship} reply — the editor Fly then honestly reports "sent"). */
    default int ownSeatIdOrMinusOne() {
        return -1;
    }

    /** The weapon group the human's held trigger fires in-game. */
    int selectedWeaponGroup();

    int framesSent();

    /** Best-effort, idempotent, never throws (the TCP session's contract). */
    @Override
    void close();
}
