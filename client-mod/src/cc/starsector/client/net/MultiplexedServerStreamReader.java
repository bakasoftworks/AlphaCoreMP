package cc.starsector.client.net;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;

/**
 * Demultiplexer for the live server stream that interleaves message types on one
 * socket (the relevance-safe despawn lane, the
 * gameplay-edge-event lane):
 *
 * <ul>
 *   <li>the {@code AWSS} world-snapshot pose lane ({@link AwssSnapshotReader}),</li>
 *   <li>the {@code AWSD} delta+keyframe ship lane ({@link AwsdSnapshotReader};
 *       ships ride it when the server's {@code cc.net.snapDelta} is ON), and</li>
 *   <li>the durable {@code EDSP} entity-despawn-event lane
 *       ({@link ClientDespawnEventReader}), and</li>
 *   <li>the durable {@code GPEE} gameplay-edge-event lane
 *       ({@link ClientGameplayEdgeEventReader}), and</li>
 *   <li>the durable {@code MRST} match-reset lane
 *       ({@link ClientMatchResetReader}; session lifecycle — the inline
 *       next-round boundary on the persistent socket), and</li>
 *   <li>the once-per-connection {@code SNMS} seat-name table
 *       ({@link ClientSeatNamesReader}; the seat-name handshake — emitted
 *       right after both seats are known, BEFORE the round's first snapshot), and</li>
 *   <li>the per-seat {@code SNMR} seat-name record
 *       ({@link ClientSeatNameRecordReader}; N-aware seat names — the N door
 *       fans one on every stored announce and spools the full table to a newcomer at
 *       admission/takeover), and</li>
 *   <li>the durable {@code SEAT} seat-state-event lane
 *       ({@link ClientSeatStateReader}; seat-state replication —
 *       emitted by the N-peer serve only: per-admit baseline burst + one frame per
 *       seat-machine transition), and</li>
 *   <li>the RESERVED-DORMANT {@code DESC}/{@code HOUS} control kinds
 *       ({@link ClientReservedControlReader}; no server emits them yet;
 *       tolerated by their frozen length-skippable envelope so a future consumer arc never
 *       forces a re-kit), and</li>
 *   <li>the {@code CHTD} chat-relay lane ({@link ChatReader}; in-match text chat —
 *       one seat- and name-stamped frame per relayed chat line, fanned to every live session
 *       including the sender), and</li>
 *   <li>the {@code HKFR} hulk-fracture lane ({@link ClientHulkFractureRecordReader};
 *       hulk-split sync — the server's remnant-family detector fans one edge per
 *       authoritative corpse split / piece removal; no admission catch-up), and</li>
 *   <li>the {@code HKPS} corpse-pose keyframe lane ({@link ClientHulkPoseRecordReader};
 *       the detector fans one per corpse-family piece at a low cadence so
 *       clients adopt the server's corpse kinematics; no admission catch-up), and</li>
 *   <li>the {@code HKCG} hulk-cut-geometry lane ({@link ClientHulkCutGeometryRecordReader};
 *       four records per authoritative carve, fanned BEFORE the paired
 *       {@code HKFR} ADD so the client's cut latch is warm at edge arrival; no admission
 *       catch-up), and</li>
 *   <li>the {@code RAPP} roster-append lane ({@link ClientRosterAppendReader};
 *       one frame per drained mid-round wave, replayed per-round to
 *       late joiners, so clients append the wave hulls to their replica roster), and</li>
 *   <li>the {@code DIVE} phase-dive-start lane ({@link ClientPhaseDiveReader};
 *       fanned once at the server's rising-edge read of a phase-anchor
 *       hull's {@code emergencyDive}, so the client plays the vanilla dive presentation on
 *       the local body and takes the paired {@code EDSP DEPARTED} corpse-free), and</li>
 *   <li>the {@code VDEF} variant-definition lane ({@link ClientVariantDefinitionReader};
 *       the armed door bursts the server's whole boot variant catalog once
 *       per admission, ONE variant per frame, BEFORE any RAPP replay row can name a wire-only
 *       variant; the client folds each into the engine's own variant registry), and</li>
 *   <li>the {@code VACK} custom-variant receipt lane ({@link ClientVariantReceiptReader};
 *       the server's unicast verdict on this session's {@code VSUB}), and</li>
 *   <li>the {@code MMNT} module-mount handover lane ({@link ClientModuleMountReader};
 *       module piloting — fanned per seat mount/dismount BEFORE the paired
 *       RAPP row, replayed per live mount at admission BEFORE the RAPP catch-up; the client
 *       repoints the seat's fixed wire id between hull and station module), and</li>
 *   <li>the {@code XFER} command-transfer control lane ({@link ClientCommandTransferReader};
 *       fanned per transfer edge (begin/deny/commit/abort), replayed
 *       per in-flight row as BEGIN at admission AFTER the RAPP catch-up; the client folds
 *       these count-only until the L4 own-view pod-riding leg), and</li>
 *   <li>the {@code AIMP} batched pilot-aim lane ({@link ClientPilotAimReader};
 *       remote turret aim — ONE shared change-gated frame per due
 *       tick ≈8 Hz + ≈1 s keepalive carrying every piloted seat's live aim world point; the
 *       client drops its own row and slews remote piloted ships' manual turret groups via
 *       the engine-native mouseTarget seam), and</li>
 *   <li>the {@code PSTB} posture-wire-block lane ({@link ClientPostureBlockReader};
 *       lane posture — the server spools ONE knob-empty block per admission
 *       stamping the deliberate-variance experiment/diagnostic tag; the client adopts the tag for its
 *       posture sidelog + echo, idempotent apply), and</li>
 *   <li>the {@code LSTD} structured-listing lane ({@link ClientListingReader};
 *       the server's reply to a no-arg {@code /ship}/{@code /module}/
 *       {@code /transfer}, unicast to the requesting seat as a per-seat-listingId latest-wins
 *       drip of ≤255 frames; the picker lane assembles and offers complete listings).</li>
 * </ul>
 *
 * <p>Each frame type stamps a distinct 4-byte magic as its first field
 * ({@code AWSS} = {@code 0x41575353}, {@code AWSD} = {@code 0x41575344},
 * {@code EDSP} = {@code 0x45445350},
 * {@code GPEE} = {@code 0x47504545}, {@code MRST} = {@code 0x4D525354},
 * {@code SNMS} = {@code 0x534E4D53}, {@code SNMR} = {@code 0x534E4D52},
 * {@code SEAT} = {@code 0x53454154}, {@code CHTD} = {@code 0x43485444},
 * {@code HKFR} = {@code 0x484B4652}, {@code HKPS} = {@code 0x484B5053},
 * {@code HKCG} = {@code 0x484B4347}, {@code RAPP} = {@code 0x52415050},
 * {@code MOVR} = {@code 0x4D4F5652}, {@code DIVE} = {@code 0x44495645},
 * {@code VDEF} = {@code 0x56444546}, {@code MMNT} = {@code 0x4D4D4E54},
 * {@code AIMP} = {@code 0x41494D50}, {@code LSTD} = {@code 0x4C535444},
 * {@code PSTB} = {@code 0x50535442},
 * reserved {@code DESC} = {@code 0x44455343} / {@code HOUS} = {@code 0x484F5553}), so the client can read a single byte stream and
 * decide each frame's type by peeking that leading magic, then hand the rest of the
 * frame to the matching reader's {@code *AfterMagic} entry point. This lets the server
 * multiplex its durable per-tick events onto the same socket as the per-peer (possibly
 * relevance-scoped) snapshot stream (see {@link RosterLifecycleTracker}).
 *
 *
 * <p>Fail-closed: a magic that is no known lane throws {@link IOException}; each lane's
 * reader fails closed on a corrupt/truncated frame. A clean end-of-stream at a frame
 * boundary yields {@code null} from {@link #readMessage(InputStream)} (or ends
 * {@link #drain}).
 *
 * <p>Pure {@link InputStream} primitives only (the mod-script sandbox blocks
 * {@code DataInputStream} — see {@link WireBytes}).
 */
public final class MultiplexedServerStreamReader {

    private MultiplexedServerStreamReader() {
    }

    /**
     * Read one message off the multiplexed stream, demultiplexed by its leading magic,
     * or {@code null} at a clean end-of-stream (a frame boundary).
     */
    public static ServerStreamMessage readMessage(InputStream in) throws IOException {
        int first = in.read();
        if (first < 0) {
            return null; // clean EOF at a frame boundary
        }
        int magic = ((first & 0xff) << 24)
                | (WireBytes.readUnsignedByte(in) << 16)
                | (WireBytes.readUnsignedByte(in) << 8)
                | WireBytes.readUnsignedByte(in);
        if (magic == AwssSnapshotReader.MAGIC) {
            return ServerStreamMessage.snapshot(AwssSnapshotReader.readFrameAfterMagic(in));
        }
        if (magic == AwsdSnapshotReader.MAGIC) {
            return ServerStreamMessage.awsdSnapshot(AwsdSnapshotReader.readFrameAfterMagic(in));
        }
        if (magic == ClientDespawnEventReader.MAGIC) {
            return ServerStreamMessage.despawn(ClientDespawnEventReader.readEventAfterMagic(in));
        }
        if (magic == ClientGameplayEdgeEventReader.MAGIC) {
            return ServerStreamMessage.gameplayEdgeEvent(
                    ClientGameplayEdgeEventReader.readEventAfterMagic(in));
        }
        if (magic == ClientMatchResetReader.MAGIC) {
            return ServerStreamMessage.matchReset(ClientMatchResetReader.readResetAfterMagic(in));
        }
        if (magic == ClientSeatNamesReader.MAGIC) {
            return ServerStreamMessage.seatNames(ClientSeatNamesReader.readAfterMagic(in));
        }
        if (magic == ClientSeatNameRecordReader.MAGIC) {
            return ServerStreamMessage.seatNameRecord(ClientSeatNameRecordReader.readAfterMagic(in));
        }
        if (magic == ClientSeatStateReader.MAGIC) {
            return ServerStreamMessage.seatState(ClientSeatStateReader.readAfterMagic(in));
        }
        if (magic == ChatReader.MAGIC) {
            return ServerStreamMessage.chat(ChatReader.readAfterMagic(in));
        }
        if (magic == ClientHulkFractureRecordReader.MAGIC) {
            return ServerStreamMessage.hulkFracture(
                    ClientHulkFractureRecordReader.readAfterMagic(in));
        }
        if (magic == ClientHulkPoseRecordReader.MAGIC) {
            return ServerStreamMessage.hulkPose(
                    ClientHulkPoseRecordReader.readAfterMagic(in));
        }
        if (magic == ClientHulkCutGeometryRecordReader.MAGIC) {
            return ServerStreamMessage.hulkCutGeometry(
                    ClientHulkCutGeometryRecordReader.readAfterMagic(in));
        }
        if (magic == ClientRosterAppendReader.MAGIC) {
            return ServerStreamMessage.rosterAppend(
                    ClientRosterAppendReader.readAppendAfterMagic(in));
        }
        if (magic == ClientMatchOverRecordReader.MAGIC) {
            return ServerStreamMessage.matchOver(
                    ClientMatchOverRecordReader.readAfterMagic(in));
        }
        if (magic == ClientPhaseDiveReader.MAGIC) {
            return ServerStreamMessage.phaseDive(
                    ClientPhaseDiveReader.readEventAfterMagic(in));
        }
        if (magic == ClientVariantDefinitionReader.MAGIC) {
            // The boot id table (ClientIdTableBoot at onApplicationLoad); null = the
            // reader refuses the frame (IOException, the DROP-FRAME tier) — never a guess.
            return ServerStreamMessage.variantDefinition(
                    ClientVariantDefinitionReader.readDefinitionAfterMagic(in,
                            cc.starsector.server.runtime.IdTables.installedOrNull()));
        }
        if (magic == ClientModuleMountReader.MAGIC) {
            return ServerStreamMessage.moduleMount(
                    ClientModuleMountReader.readMountAfterMagic(in));
        }
        if (magic == ClientCommandTransferReader.MAGIC) {
            return ServerStreamMessage.commandTransfer(
                    ClientCommandTransferReader.readTransferAfterMagic(in));
        }
        if (magic == ClientPilotAimReader.MAGIC) {
            return ServerStreamMessage.pilotAims(
                    ClientPilotAimReader.readAimsAfterMagic(in));
        }
        if (magic == ClientAsteroidFrameReader.MAGIC) { // ASTP asteroid pose
            return ServerStreamMessage.asteroidPose(ClientAsteroidFrameReader.readAfterMagic(in));
        }
        if (magic == ClientListingReader.MAGIC) {
            return ServerStreamMessage.listing(ClientListingReader.readAfterMagic(in));
        }
        if (magic == ClientPostureBlockReader.MAGIC) {
            return ServerStreamMessage.postureBlock(
                    ClientPostureBlockReader.readAfterMagic(in));
        }
        if (magic == VoiceConfigReader.MAGIC) {
            // The VCFG voice sheet (22 B) — raw here, validated by the
            // session route under its count-and-drop law (a bad sheet is a server bug, not a
            // stream break).
            return ServerStreamMessage.voiceConfig(VoiceConfigReader.readFrameAfterMagic(in));
        }
        if (magic == ClientVariantReceiptReader.MAGIC) {
            // W15 v0: the VACK custom-variant receipt — BEFORE the reserved-magic
            // check on purpose: an unknown DOWN magic below is TERMINAL reconcile death, and the
            // server unicasts VACK only to the session that submitted (stale kits never see it).
            return ServerStreamMessage.variantReceipt(
                    ClientVariantReceiptReader.readAfterMagic(in));
        }
        if (ClientReservedControlReader.isReservedMagic(magic)) {
            // The two reserved kinds (DESC/HOUS) are TOLERATED — decoded by their
            // frozen length-skippable envelope and surfaced as RESERVED_CONTROL for the predict
            // sessions to count-ignore. Any other unknown magic stays fail-closed below.
            return ServerStreamMessage.reservedControl(
                    ClientReservedControlReader.readAfterMagic(magic, in));
        }
        throw new IOException("unknown server stream frame magic: " + Integer.toHexString(magic));
    }

    /**
     * Drain every message from {@code in} until end-of-stream, routing each frame to its
     * lane's consumer. Throws on the first corrupt/truncated/unknown frame.
     */
    public static void drain(
            InputStream in,
            Consumer<? super ServerSnapshotFrame> onFrame,
            Consumer<? super ClientEntityDespawn> onDespawn,
            Consumer<? super ClientGameplayEdgeEvent> onGameplayEdgeEvent)
            throws IOException {
        while (true) {
            ServerStreamMessage message = readMessage(in);
            if (message == null) {
                return;
            }
            switch (message.kind()) {
                case SNAPSHOT:
                    onFrame.accept(message.frame());
                    break;
                case DESPAWN:
                    onDespawn.accept(message.despawn());
                    break;
                case GAMEPLAY_EDGE_EVENT:
                    onGameplayEdgeEvent.accept(message.gameplayEdgeEvent());
                    break;
                default:
                    // A MATCH_RESET / SEAT_NAMES / SEAT_STATE frame is the predict lane's business
                    // (the predict sessions' demultiplexed receive); any is unexpected
                    // on this snapshot/despawn/edge drain, so fail closed rather than silently
                    // swallow a round boundary, a handshake frame, or a seat transition.
                    throw new IllegalStateException(
                            "unexpected " + message.kind() + " frame on a snapshot/despawn/edge drain: "
                                    + message);
            }
        }
    }

    /**
     * Two-lane drain (the pre-B3 contract): routes snapshots and despawns; a gameplay
     * edge event on the stream is unexpected for these callers and fails closed.
     */
    public static void drain(
            InputStream in,
            Consumer<? super ServerSnapshotFrame> onFrame,
            Consumer<? super ClientEntityDespawn> onDespawn)
            throws IOException {
        drain(in, onFrame, onDespawn, event -> {
            throw new IllegalStateException(
                    "unexpected gameplay edge event on a two-lane drain: " + event);
        });
    }
}
