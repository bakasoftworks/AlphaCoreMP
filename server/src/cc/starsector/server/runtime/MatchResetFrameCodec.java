package cc.starsector.server.runtime;

import cc.starsector.server.protocol.MatchResetControlMessage;
import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.ProtocolRegistryEntry;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Product-owned binary frame codec for the explicit {@code MATCH_RESET} control frame
 * ({@link MatchResetControlMessage}, L1 session-lifecycle /).
 *
 * <p>The frame deliberately mirrors {@link AuthoritativeWorldSnapshotCodec}'s envelope shape
 * — {@code magic | schema | headerBytes | wireTypeCode | bodyLen | body | CRC32} — so a
 * single low-level reader pulls one framed unit at a time and dispatches on its magic. The
 * magic differs ({@code 0x4D525354} = {@code "MRST"} vs the snapshot codec's {@code "AWSS"}),
 * which is what makes the two streams demuxable on one socket: a {@link MatchAwareSnapshotStream}
 * peeks the 4-byte magic and routes a snapshot frame to the snapshot decoder and a
 * match-reset frame here. Framing is registry-checked and fail-closed exactly like the
 * snapshot codec: a magic / schema / wire-identity / declared-size mismatch and CRC32
 * corruption all throw {@link IOException}.
 *
 * <p>This codec is the wire half of the persistent-socket reset the predict client decodes:
 * the read-only persistent-socket reset leaves the round boundary
 * IMPLICIT in the entity-count sequence, which the predict client (its own local engine +
 * reconcile) cannot infer. Default-OFF on the server.
 */
public final class MatchResetFrameCodec {

    /** Frame magic: {@code "MRST"}, distinct from {@link AuthoritativeWorldSnapshotCodec#MAGIC} ("AWSS"). */
    public static final int MAGIC = 0x4D525354; // "MRST"

    private static final ProtocolRegistryEntry REGISTRY_ENTRY =
            NetworkMessageRegistry.authoritativeCombat().matchResetControlWireEntry();
    public static final short SCHEMA_VERSION = checkedSchemaVersion(REGISTRY_ENTRY);
    public static final int MESSAGE_TYPE_CODE = REGISTRY_ENTRY.wireTypeCode();

    public static final short HEADER_BYTES =
            Integer.BYTES   // magic
                    + Short.BYTES   // schema version
                    + Short.BYTES   // header byte count
                    + Integer.BYTES // wire type code
                    + Integer.BYTES; // body byte count
    /** Body: resetSeq + completedRoundIndex + nextRoundIndex, three ints. */
    public static final int BODY_BYTES = Integer.BYTES + Integer.BYTES + Integer.BYTES;

    private MatchResetFrameCodec() {
    }

    public static ProtocolRegistryEntry registryEntry() {
        return REGISTRY_ENTRY;
    }

    public static int frameByteCount() {
        return HEADER_BYTES + BODY_BYTES + Integer.BYTES;
    }

    /** Write one explicit MATCH_RESET control frame; returns the bytes written. */
    public static int writeFrame(OutputStream output, MatchResetSignal signal) throws IOException {
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

    /**
     * Read one MATCH_RESET control frame whose 4-byte {@link #MAGIC} has ALREADY been consumed
     * off {@code data} and validated by the caller (the {@link MatchAwareSnapshotStream} demux,
     * which reads the magic to route the frame). Validates schema, header size, wire identity,
     * declared body size, and CRC32; throws {@link IOException} on any mismatch.
     */
    public static MatchResetSignal readFrameAfterMagic(DataInputStream data) throws IOException {
        Objects.requireNonNull(data, "data");
        short version = data.readShort();
        requireRegisteredSchema(version);
        short headerBytes = data.readShort();
        if (headerBytes != HEADER_BYTES) {
            throw new IOException("match-reset frame header size mismatch: " + headerBytes);
        }
        int messageTypeCode = data.readInt();
        if (messageTypeCode != MESSAGE_TYPE_CODE) {
            throw new IOException("match-reset frame wire identity mismatch: "
                    + Integer.toUnsignedString(messageTypeCode));
        }
        int bodyBytes = data.readInt();
        if (bodyBytes != BODY_BYTES) {
            throw new IOException("match-reset frame body size invalid: " + bodyBytes);
        }
        byte[] body = new byte[bodyBytes];
        data.readFully(body);
        long expectedChecksum = Integer.toUnsignedLong(data.readInt());
        long actualChecksum = checksum(body);
        if (expectedChecksum != actualChecksum) {
            throw new IOException("match-reset frame checksum mismatch");
        }
        return decodeBody(body);
    }

    private static byte[] encodeBody(MatchResetSignal signal) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(BODY_BYTES);
        DataOutputStream data = new DataOutputStream(bytes);
        data.writeInt(signal.resetSeq());
        data.writeInt(signal.completedRoundIndex());
        data.writeInt(signal.nextRoundIndex());
        data.flush();
        return bytes.toByteArray();
    }

    private static MatchResetSignal decodeBody(byte[] body) throws IOException {
        DataInputStream data = new DataInputStream(new java.io.ByteArrayInputStream(body));
        int resetSeq = data.readInt();
        int completedRoundIndex = data.readInt();
        int nextRoundIndex = data.readInt();
        try {
            return new MatchResetSignal(resetSeq, completedRoundIndex, nextRoundIndex);
        } catch (IllegalArgumentException badRecord) {
            throw new IOException("match-reset frame body invalid", badRecord);
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
                    .require(MatchResetControlMessage.MESSAGE_ID, unsignedSchemaVersion);
        } catch (IllegalArgumentException badSchema) {
            throw new IOException("unsupported match-reset frame schema: " + unsignedSchemaVersion, badSchema);
        }
        if (!entry.compatibilityRule().accepts(unsignedSchemaVersion, true)) {
            throw new IOException("match-reset frame schema rejected by compatibility policy: "
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

    /** For symmetry / a self-test that reads a stream of one isolated frame. */
    static int readMagic(DataInputStream data) throws IOException {
        try {
            return data.readInt();
        } catch (EOFException eof) {
            throw new EOFException("truncated match-reset frame magic");
        }
    }
}
