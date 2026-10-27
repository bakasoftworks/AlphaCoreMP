package cc.starsector.server.runtime;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The ONE down-family inner-frame demux.
 *
 * A DOWN record payload (or CTRL datagram body) is 1..N concatenated inner frames, each led by a
 * 4-char magic and self-delimited only by its own codec read — no per-frame length prefix, so an
 * unknown magic is unrecoverable and must throw. Consumers loop the payload here and override
 * only the magics they extract; every other family member advances via its codec's own read.
 * The family list is single-sourced here: ReservedCtrlGoldenContract derives its uniqueness pin
 * from {@link #magics()}, so a new down lane added to this map is pinned automatically.
 * Live-lane consumers keep their own per-lane failure policy and are NOT routed
 * through this walk; they may share only the family list.
 */
public final class DownFrameFamily {

    /** Per-frame handler; the stream is positioned AT the 4-byte magic (not consumed). */
    @FunctionalInterface
    public interface FrameHandler {
        void onFrame(int magic, InputStream in) throws IOException;
    }

    /** Ordered family: magic -> default advance-past, delegating to the codec's own read. */
    private static final Map<Integer, FrameHandler> FAMILY;

    static {
        LinkedHashMap<Integer, FrameHandler> family = new LinkedHashMap<>();
        family.put(GameplayEdgeEventCodec.MAGIC, (magic, in) -> GameplayEdgeEventCodec.readEvent(in));
        family.put(AuthoritativeWorldSnapshotCodec.MAGIC,
                (magic, in) -> AuthoritativeWorldSnapshotCodec.readFrameBytes(in));
        family.put(AwsdSnapshotCodec.MAGIC, (magic, in) -> AwsdSnapshotCodec.readFrame(in));
        family.put(PilotWorldViewWireCodec.MAGIC, (magic, in) -> PilotWorldViewWireCodec.readFrame(in));
        family.put(EntityDespawnEventCodec.MAGIC, (magic, in) -> EntityDespawnEventCodec.readEvent(in));
        family.put(MatchResetFrameCodec.MAGIC, (magic, in) -> {
            DataInputStream data = new DataInputStream(in);
            data.readInt();                                  // consume the peeked MRST magic
            MatchResetFrameCodec.readFrameAfterMagic(data);
        });
        family.put(SeatNameFrameCodec.SEAT_NAMES_MAGIC,
                (magic, in) -> SeatNameFrameCodec.readSeatNames(in));
        family.put(SeatNameFrameCodec.SEAT_NAME_RECORD_MAGIC,
                (magic, in) -> SeatNameFrameCodec.readSeatNameRecord(in));
        family.put(SeatStateFrameCodec.SEAT_STATE_MAGIC,
                (magic, in) -> SeatStateFrameCodec.readSeatState(in));
        family.put(HulkPoseFrameCodec.POSE_MAGIC, (magic, in) -> HulkPoseFrameCodec.read(in));
        family.put(HulkFractureFrameCodec.FRACTURE_MAGIC, (magic, in) -> HulkFractureFrameCodec.read(in));
        family.put(HulkCutGeometryFrameCodec.CUT_GEOMETRY_MAGIC,
                (magic, in) -> HulkCutGeometryFrameCodec.read(in));
        family.put(ChatFrameCodec.DOWN_MAGIC, (magic, in) -> ChatFrameCodec.readDown(in));
        family.put(MatchReentryFrameCodec.MAGIC, (magic, in) -> MatchReentryFrameCodec.readFrame(in));
        family.put(RosterAppendFrameCodec.MAGIC, (magic, in) -> {
            DataInputStream data = new DataInputStream(in);
            data.readInt();                                  // consume the peeked RAPP magic
            RosterAppendFrameCodec.readArchivedFrameAfterMagic(data); // v4 + v5 reels
        });
        family.put(ModuleMountFrameCodec.MAGIC, (magic, in) -> {
            DataInputStream data = new DataInputStream(in);
            data.readInt();                                  // consume the peeked MMNT magic
            ModuleMountFrameCodec.readFrameAfterMagic(data);
        });
        family.put(VariantDefinitionFrameCodec.MAGIC, (magic, in) -> {
            DataInputStream data = new DataInputStream(in);
            data.readInt();                                  // consume the peeked VDEF magic
            VariantDefinitionFrameCodec.readRawBodyAfterMagic(data); // envelope + CRC only: no id table here
        });
        family.put(VariantReceiptFrameCodec.MAGIC, (magic, in) -> {
            DataInputStream data = new DataInputStream(in);
            data.readInt();                                  // consume the peeked VACK magic
            VariantReceiptFrameCodec.readFrameAfterMagic(data);
        });
        family.put(PilotAimFrameCodec.MAGIC, (magic, in) -> {
            DataInputStream data = new DataInputStream(in);
            data.readInt();                                  // consume the peeked AIMP magic
            PilotAimFrameCodec.readFrameAfterMagic(data);
        });
        family.put(AsteroidPoseFrameCodec.MAGIC, (magic, in) -> { //
            DataInputStream data = new DataInputStream(in);
            data.readInt();                                  // consume the peeked ASTP magic
            AsteroidPoseFrameCodec.readFrameAfterMagic(data);
        });
        family.put(CommandTransferFrameCodec.MAGIC, (magic, in) -> {
            DataInputStream data = new DataInputStream(in);
            data.readInt();                                  // consume the peeked XFER magic
            CommandTransferFrameCodec.readFrameAfterMagic(data);
        });
        family.put(MatchOverFrameCodec.MATCH_OVER_MAGIC,
                (magic, in) -> MatchOverFrameCodec.read(in));
        family.put(CombatOutcomeEventCodec.MAGIC, (magic, in) -> CombatOutcomeEventCodec.readEvent(in));
        family.put(PostureFrameCodec.BLOCK_MAGIC, (magic, in) -> PostureFrameCodec.readBlock(in));
        // A mount dump once died "unknown inner magic 0x56434647" on a voice-era .ccwr:
        // every DOWN-direction magic the serve can put on the wire is a family member — an
        // unknown magic is the one throw site, so a lane added to the serve without a row here
        // blinds EVERY offline reader on the next recording. Readers re-validate like the client.
        family.put(VoiceConfigFrameCodec.MAGIC, (magic, in) -> {
            // W12 L2 VCFG voice sheet — fixed 17 B on the CTRL lane.
            byte[] frame = new byte[VoiceConfigFrameCodec.FRAME_BYTES];
            new DataInputStream(in).readFully(frame);
            VoiceConfigFrameCodec.decode(frame);
        });
        family.put(ListingFrameCodec.DOWN_MAGIC, (magic, in) -> {
            // LSTD listing page: entryCount = last header byte, then the entries + CRC.
            DataInputStream data = new DataInputStream(in);
            int head = ListingFrameCodec.HEADER_BYTES + ListingFrameCodec.LISTING_HEADER_BYTES;
            byte[] header = new byte[head];
            data.readFully(header);
            int entryCount = header[head - 1] & 0xFF;
            byte[] frame = java.util.Arrays.copyOf(header,
                    ListingFrameCodec.frameBytes(entryCount));
            data.readFully(frame, head, frame.length - head);
            ListingFrameCodec.decodeDown(frame);
        });
        family.put(VoiceFrameCodec.DOWN_MAGIC, (magic, in) -> {
            // VCDN voice part: 9 B header (talkerCount = byte 8), per talker 11 B tag whose last
            // u16 is the opus length, then opus[len]. Walk the tags to find the part's extent.
            DataInputStream data = new DataInputStream(in);
            java.io.ByteArrayOutputStream part = new java.io.ByteArrayOutputStream();
            byte[] header = new byte[VoiceFrameCodec.DOWN_HEADER_BYTES];
            data.readFully(header);
            part.write(header);
            int talkers = header[VoiceFrameCodec.DOWN_HEADER_BYTES - 1] & 0xFF;
            for (int t = 0; t < talkers; t++) {
                byte[] tag = new byte[VoiceFrameCodec.DOWN_TAG_BYTES];
                data.readFully(tag);
                part.write(tag);
                int len = ((tag[tag.length - 2] & 0xFF) << 8) | (tag[tag.length - 1] & 0xFF);
                byte[] opus = new byte[len];
                data.readFully(opus);
                part.write(opus);
            }
            VoiceFrameCodec.decodeDown(part.toByteArray());
        });
        family.put(PhaseDiveEventCodec.MAGIC, (magic, in) -> PhaseDiveEventCodec.readEvent(in));
        FrameHandler reserved = (magic, in) -> {
            DataInputStream data = new DataInputStream(in);
            ReservedControlFrameCodec.readFrameAfterMagic(data.readInt(), data);
        };
        family.put(ReservedControlFrameCodec.MAGIC_DESCRIPTOR, reserved);
        family.put(ReservedControlFrameCodec.MAGIC_HOUSE, reserved);
        FAMILY = Collections.unmodifiableMap(family);
    }

    private DownFrameFamily() {
    }

    /** Membership test for a consumer that tolerates the WHOLE family (the
     * synthetic seat's CTRL demux) — a kind the serve can put on the wire is a member by law. */
    public static boolean contains(int magic) {
        return FAMILY.containsKey(magic);
    }

    /** The full ordered family, for the ReservedCtrlGoldenContract uniqueness pin. */
    public static int[] magics() {
        int[] out = new int[FAMILY.size()];
        int i = 0;
        for (Integer magic : FAMILY.keySet()) {
            out[i++] = magic;
        }
        return out;
    }

    /** Per-frame byte observer (the lane byte census): the magic and the
     * bytes its handler consumed, magic included. Byte accounting lives HERE because the family
     * has no per-frame length prefix — only the codec read knows a frame's extent. */
    @FunctionalInterface
    public interface FrameObserver {
        void onFrameBytes(int magic, int bytes);
    }

    /**
     * Demux one DOWN payload: peek each inner magic, dispatch the consumer's override if it
     * extracts that lane, else the family default advance. Unknown magic -> the one throw site.
     */
    public static void demux(byte[] payload, Map<Integer, FrameHandler> overrides)
            throws IOException {
        demux(payload, overrides, null);
    }

    /** {@link #demux(byte[], Map)} with a byte observer called after every frame ({@code null} = none). */
    public static void demux(byte[] payload, Map<Integer, FrameHandler> overrides,
            FrameObserver observer) throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(payload);
        DataInputStream peek = new DataInputStream(in);
        while (in.available() > 0) {
            in.mark(4);
            int magic = peek.readInt();
            in.reset();
            FrameHandler handler = overrides.get(magic);
            if (handler == null) {
                handler = FAMILY.get(magic);
            }
            if (handler == null) {
                throw new IOException("unknown inner magic 0x" + Integer.toHexString(magic));
            }
            int before = in.available();
            handler.onFrame(magic, in);
            if (observer != null) {
                observer.onFrameBytes(magic, before - in.available());
            }
        }
    }
}
