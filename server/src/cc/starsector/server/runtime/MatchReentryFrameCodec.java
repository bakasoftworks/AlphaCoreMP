package cc.starsector.server.runtime;

import cc.starsector.server.protocol.MatchReentryControlMessage;
import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.ProtocolRegistryEntry;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Product-owned binary frame codec for the explicit next-round RE-ENTRY request frame
 * ({@link MatchReentryControlMessage}, L1 session-lifecycle /) — the up-channel
 * complement of {@link MatchResetFrameCodec}.
 *
 * <p>The frame deliberately mirrors {@link MatchResetFrameCodec}'s envelope shape
 * — {@code magic | schema | headerBytes | wireTypeCode | bodyLen | body | CRC32} — so the
 * same registry-checked, fail-closed framing discipline applies. The magic differs
 * ({@code 0x4D524552} = {@code "MRER"} vs the reset codec's {@code "MRST"} and the maneuver
 * intent's {@code "LMVI"}), which is what lets the server distinguish an explicit re-entry
 * request from a per-tick {@link LiveManeuverIntent} on the same up-channel: at a round
 * boundary the {@code pause-until-reentry} server reads this frame directly (no demuxer is
 * needed — the protocol is a phase change, not interleaving: after the boundary the client
 * sends exactly one re-entry frame before resuming intents), and a wrong magic / schema /
 * wire-identity / declared-size mismatch or CRC32 corruption all throw {@link IOException}.
 *
 * <p>This codec is the wire half of the round-boundary handshake the predict client speaks
 * UP: the server emits {@code MRST} DOWN at the boundary and then blocks reading
 * this {@code MRER} UP before deploying the next round, so it produces nothing across the
 * human results-screen gap. Default-OFF on the server ({@code -Dcc.server.awaitReentry}); the
 * existing round-loop paths stay byte-identical..
 */
public final class MatchReentryFrameCodec {

    /** Frame magic: {@code "MRER"}, distinct from {@link MatchResetFrameCodec#MAGIC} ("MRST") and "LMVI". */
    public static final int MAGIC = 0x4D524552; // "MRER"

    private static final ProtocolRegistryEntry REGISTRY_ENTRY =
            NetworkMessageRegistry.authoritativeCombat().matchReentryControlWireEntry();
    public static final short SCHEMA_VERSION = checkedSchemaVersion(REGISTRY_ENTRY);
    public static final int MESSAGE_TYPE_CODE = REGISTRY_ENTRY.wireTypeCode();

    public static final short HEADER_BYTES =
            Integer.BYTES   // magic
                    + Short.BYTES   // schema version
                    + Short.BYTES   // header byte count
                    + Integer.BYTES // wire type code
                    + Integer.BYTES; // body byte count
    /** Body: reentrySeq + completedRoundIndex + nextRoundIndex, three ints. */
    public static final int BODY_BYTES = Integer.BYTES + Integer.BYTES + Integer.BYTES;

    private MatchReentryFrameCodec() {
    }

    public static ProtocolRegistryEntry registryEntry() {
        return REGISTRY_ENTRY;
    }

    public static int frameByteCount() {
        return HEADER_BYTES + BODY_BYTES + Integer.BYTES;
    }

    /** Write one explicit re-entry request frame; returns the bytes written. */
    public static int writeFrame(OutputStream output, MatchReentrySignal signal) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(signal, "signal");
        byte[] body = encodeBody(signal);
        long checksum = checksum(body);
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(MAGIC);
        data.writeShort(SCHEMA_VERSION);
        data.writeShort(HEADER_BYTES);
        data.writeInt(MESSAGE_TYPE_CODE);
        data.writeInt(body.length);
        data.write(body);
        data.writeInt((int) checksum);
        data.flush();
        return HEADER_BYTES + body.length + Integer.BYTES;
    }

    /** Encode one re-entry request into a freshly allocated byte array. */
    public static byte[] toBytes(MatchReentrySignal signal) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(frameByteCount());
        try {
            writeFrame(bytes, signal);
        } catch (IOException e) {
            throw new IllegalStateException("in-memory match-reentry encode failed", e);
        }
        return bytes.toByteArray();
    }

    /**
     * Read one whole re-entry request frame off {@code input}, INCLUDING its 4-byte
     * {@link #MAGIC}. Unlike {@link MatchResetFrameCodec#readFrameAfterMagic} (which is fed by
     * a {@link MatchAwareSnapshotStream} demuxer that already consumed the magic to route the
     * frame), the up-channel re-entry frame is read directly by the server, so this reads and
     * validates the magic itself. Fail-closed: a wrong magic / schema / wire identity /
     * declared size or CRC32 mismatch throws {@link IOException}.
     */
    public static MatchReentrySignal readFrame(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        DataInputStream data = input instanceof DataInputStream di ? di : new DataInputStream(input);
        int magic;
        try {
            magic = data.readInt();
        } catch (EOFException eof) {
            throw new IOException("no match-reentry frame on the wire (EOF before magic)", eof);
        }
        if (magic != MAGIC) {
            throw new IOException("match-reentry frame magic mismatch: expected "
                    + Integer.toHexString(MAGIC) + ", got " + Integer.toHexString(magic));
        }
        return readFrameAfterMagic(data);
    }

    /**
     * Read one re-entry request frame whose 4-byte {@link #MAGIC} has ALREADY been consumed off
     * {@code data} and validated by the caller. Validates schema, header size, wire identity,
     * declared body size, and CRC32; throws {@link IOException} on any mismatch.
     */
    public static MatchReentrySignal readFrameAfterMagic(DataInputStream data) throws IOException {
        Objects.requireNonNull(data, "data");
        short version = data.readShort();
        requireRegisteredSchema(version);
        short headerBytes = data.readShort();
        if (headerBytes != HEADER_BYTES) {
            throw new IOException("match-reentry frame header size mismatch: " + headerBytes);
        }
        int messageTypeCode = data.readInt();
        if (messageTypeCode != MESSAGE_TYPE_CODE) {
            throw new IOException("match-reentry frame wire identity mismatch: "
                    + Integer.toUnsignedString(messageTypeCode));
        }
        int bodyBytes = data.readInt();
        if (bodyBytes != BODY_BYTES) {
            throw new IOException("match-reentry frame body size invalid: " + bodyBytes);
        }
        byte[] body = new byte[bodyBytes];
        data.readFully(body);
        long expectedChecksum = Integer.toUnsignedLong(data.readInt());
        long actualChecksum = checksum(body);
        if (expectedChecksum != actualChecksum) {
            throw new IOException("match-reentry frame checksum mismatch");
        }
        return decodeBody(body);
    }

    private static byte[] encodeBody(MatchReentrySignal signal) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(BODY_BYTES);
        DataOutputStream data = new DataOutputStream(bytes);
        data.writeInt(signal.reentrySeq());
        data.writeInt(signal.completedRoundIndex());
        data.writeInt(signal.nextRoundIndex());
        data.flush();
        return bytes.toByteArray();
    }

    private static MatchReentrySignal decodeBody(byte[] body) throws IOException {
        DataInputStream data = new DataInputStream(new java.io.ByteArrayInputStream(body));
        int reentrySeq = data.readInt();
        int completedRoundIndex = data.readInt();
        int nextRoundIndex = data.readInt();
        try {
            return new MatchReentrySignal(reentrySeq, completedRoundIndex, nextRoundIndex);
        } catch (IllegalArgumentException badRecord) {
            throw new IOException("match-reentry frame body invalid", badRecord);
        }
    }

    private static long checksum(byte[] body) {
        CRC32 crc = new CRC32();
        crc.update(body, 0, body.length);
        return crc.getValue();
    }

    private static void requireRegisteredSchema(short schemaVersion) throws IOException {
        int unsignedSchemaVersion = Short.toUnsignedInt(schemaVersion);
        ProtocolRegistryEntry entry;
        try {
            entry = NetworkMessageRegistry.authoritativeCombat()
                    .require(MatchReentryControlMessage.MESSAGE_ID, unsignedSchemaVersion);
        } catch (IllegalArgumentException badSchema) {
            throw new IOException("unsupported match-reentry frame schema: " + unsignedSchemaVersion, badSchema);
        }
        if (!entry.compatibilityRule().accepts(unsignedSchemaVersion, true)) {
            throw new IOException("match-reentry frame schema rejected by compatibility policy: "
                    + unsignedSchemaVersion);
        }
    }

    private static short checkedSchemaVersion(ProtocolRegistryEntry registryEntry) {
        int schemaVersion = registryEntry.schemaVersion();
        if (schemaVersion > Short.MAX_VALUE) {
            throw new IllegalArgumentException("wire schema version exceeds signed short range: " + schemaVersion);
        }
        return (short) schemaVersion;
    }
}
