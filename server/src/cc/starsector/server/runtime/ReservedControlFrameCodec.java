package cc.starsector.server.runtime;

import cc.starsector.server.protocol.HouseControlMessage;
import cc.starsector.server.protocol.MatchDescriptorControlMessage;
import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.ProtocolRegistryEntry;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Product-owned binary frame codec for the two RESERVED-DORMANT control kinds frozen in the
 * wire design:
 * {@code DESC} descriptor-on-CTRL carriage ({@link MatchDescriptorControlMessage}) and
 * {@code HOUS} house/operator signals ({@link HouseControlMessage}, body byte 0 = role).
 *
 * <p>Both share the project's standard demuxable envelope byte-exactly
 * ({@link MatchResetFrameCodec} / {@link AuthoritativeWorldSnapshotCodec} shape):
 * {@code magic | schema u16 | headerBytes u16 (=16, frozen — charter R1 header evolution
 * illegal) | wireTypeCode u32 | bodyBytes u32 | body | CRC32(body)}. What B6 freezes is the
 * ENVELOPE, deliberately not the body: {@code bodyBytes} makes the frame length-skippable, so
 * the D4.8 client jar tolerates these kinds (count-ignored) years before any body semantics
 * exist — a future emission never forces a &times;N re-kit. Body content is opaque at v1
 * (defined by each kind's consumer arc; HOUS body byte 0 = role discriminator, position
 * frozen).
 *
 * <p><b>The server emits nothing on either kind until its consumer arc</b> — this codec's
 * only live callers are the golden-vector contract ({@code ReservedCtrlGoldenContract},
 * charter G2 first-of-next-batch) and future consumer arcs. Server-side read is
 * registry-checked fail-closed like every sibling codec (the server is schema-canonical);
 * the CLIENT half ({@code ClientReservedControlReader}) deliberately tolerates ANY schema —
 * that asymmetry is the reservation's point (a future {@code v2} body must still skip
 * cleanly through a v1-era kit). {@code DESC} schema 2 is the match-document PROBE reply, codec
 * {@link MatchDocFrameCodec}; schema 1 stays the dormant reservation, and this
 * in-session reader still rejects any {@code DESC} schema but 1.
 */
public final class ReservedControlFrameCodec {

    /** Frame magic: {@code "DESC"} — descriptor-on-CTRL carriage (reserved-dormant). */
    public static final int MAGIC_DESCRIPTOR = 0x44455343; // "DESC"
    /** Frame magic: {@code "HOUS"} — house/operator control (reserved-dormant). */
    public static final int MAGIC_HOUSE = 0x484F5553; // "HOUS"

    /** Envelope header, frozen at 16 like every sibling family (charter R1). */
    public static final short HEADER_BYTES =
            Integer.BYTES   // magic
                    + Short.BYTES   // schema version
                    + Short.BYTES   // header byte count
                    + Integer.BYTES // wire type code
                    + Integer.BYTES; // body byte count

    /**
     * Sanity bound on a declared body length (matches the client reader's bound): a reserved
     * frame is skippable only while its declared length is sane — anything larger is stream
     * corruption, failed loud, never allocated.
     */
    public static final int MAX_BODY_BYTES = 1 << 20;

    private static final ProtocolRegistryEntry DESCRIPTOR_ENTRY =
            NetworkMessageRegistry.authoritativeCombat().matchDescriptorControlWireEntry();
    private static final ProtocolRegistryEntry HOUSE_ENTRY =
            NetworkMessageRegistry.authoritativeCombat().houseControlWireEntry();

    /** One decoded reserved-control envelope; {@code body} is opaque by design at v1. */
    public record ReservedControlFrame(int magic, int schemaVersion, int wireTypeCode, byte[] body) {
        public ReservedControlFrame {
            Objects.requireNonNull(body, "body");
        }
    }

    private ReservedControlFrameCodec() {
    }

    public static ProtocolRegistryEntry descriptorRegistryEntry() {
        return DESCRIPTOR_ENTRY;
    }

    public static ProtocolRegistryEntry houseRegistryEntry() {
        return HOUSE_ENTRY;
    }

    /** Write one reserved {@code DESC} frame carrying an opaque body; returns bytes written. */
    public static int writeDescriptorFrame(OutputStream output, byte[] body) throws IOException {
        return writeFrame(output, MAGIC_DESCRIPTOR, DESCRIPTOR_ENTRY, body);
    }

    /**
     * Write one reserved {@code HOUS} frame: body byte 0 = {@code role}
     * ({@link HouseControlMessage#ROLE_BYTE_OFFSET}, frozen position), rest = opaque payload.
     */
    public static int writeHouseFrame(OutputStream output, int role, byte[] opaquePayload)
            throws IOException {
        Objects.requireNonNull(opaquePayload, "opaquePayload");
        if (role < 0 || role > 0xFF) {
            throw new IllegalArgumentException("house role byte out of range: " + role);
        }
        byte[] body = new byte[1 + opaquePayload.length];
        body[HouseControlMessage.ROLE_BYTE_OFFSET] = (byte) role;
        System.arraycopy(opaquePayload, 0, body, 1, opaquePayload.length);
        return writeFrame(output, MAGIC_HOUSE, HOUSE_ENTRY, body);
    }

    /**
     * Encode one complete {@code HOUS} KICKED-notice frame (the HOUS
     * reservation's first consumer; body v1 layout canonical at
     * {@link HouseControlMessage#ROLE_KICKED_NOTICE}): role byte 1 + {@code u8 reasonCode} +
     * {@code u16 reserved0}. Returned as ready-to-send frame bytes because the kick sites
     * hand it straight to the reliability core's CTRL send (never the spool — a spooled
     * frame would sit behind the drain's pendingDeath skip and die with the session).
     */
    public static byte[] encodeKickedNoticeFrame(int reasonCode) {
        if (reasonCode < 0 || reasonCode > 0xFF) {
            throw new IllegalArgumentException("kicked reasonCode out of u8 range: " + reasonCode);
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] payload = {(byte) reasonCode, 0, 0}; // u8 reason + u16 reserved0
        try {
            writeHouseFrame(out, HouseControlMessage.ROLE_KICKED_NOTICE, payload);
        } catch (IOException impossible) {
            // ByteArrayOutputStream never throws; the signature is the shared writeFrame's.
            throw new IllegalStateException("in-memory HOUS encode failed", impossible);
        }
        return out.toByteArray();
    }

    private static int writeFrame(OutputStream output, int magic, ProtocolRegistryEntry entry,
            byte[] body) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(body, "body");
        if (body.length > MAX_BODY_BYTES) {
            throw new IOException("reserved control body exceeds sanity bound: " + body.length);
        }
        CRC32 crc = new CRC32();
        crc.update(body, 0, body.length);
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(magic);
        data.writeShort(checkedSchemaVersion(entry));
        data.writeShort(HEADER_BYTES);
        data.writeInt(entry.wireTypeCode());
        data.writeInt(body.length);
        data.write(body);
        data.writeInt((int) crc.getValue());
        data.flush();
        return HEADER_BYTES + body.length + Integer.BYTES;
    }

    /**
     * Read one reserved control frame whose 4-byte magic has ALREADY been consumed and matched
     * to {@code magic} by the demuxing caller. Registry-checked fail-closed (server side is
     * schema-canonical): schema must be the kind's registered version, header must be 16,
     * wire identity must match, declared body length bounded, CRC32 verified.
     */
    public static ReservedControlFrame readFrameAfterMagic(int magic, DataInputStream data)
            throws IOException {
        Objects.requireNonNull(data, "data");
        ProtocolRegistryEntry entry = entryFor(magic);
        int schema = Short.toUnsignedInt(data.readShort());
        if (schema != entry.schemaVersion()) {
            throw new IOException("reserved control frame schema mismatch for "
                    + entry.messageId() + ": " + schema);
        }
        short headerBytes = data.readShort();
        if (headerBytes != HEADER_BYTES) {
            throw new IOException("reserved control frame header size mismatch: " + headerBytes);
        }
        int wireTypeCode = data.readInt();
        if (wireTypeCode != entry.wireTypeCode()) {
            throw new IOException("reserved control frame wire identity mismatch: "
                    + Integer.toUnsignedString(wireTypeCode));
        }
        int bodyBytes = data.readInt();
        if (bodyBytes < 0 || bodyBytes > MAX_BODY_BYTES) {
            throw new IOException("reserved control frame body size invalid: " + bodyBytes);
        }
        byte[] body = new byte[bodyBytes];
        data.readFully(body);
        long expectedChecksum = Integer.toUnsignedLong(data.readInt());
        CRC32 crc = new CRC32();
        crc.update(body, 0, body.length);
        if (expectedChecksum != crc.getValue()) {
            throw new IOException("reserved control frame checksum mismatch");
        }
        return new ReservedControlFrame(magic, schema, wireTypeCode, body);
    }

    private static ProtocolRegistryEntry entryFor(int magic) throws IOException {
        if (magic == MAGIC_DESCRIPTOR) {
            return DESCRIPTOR_ENTRY;
        }
        if (magic == MAGIC_HOUSE) {
            return HOUSE_ENTRY;
        }
        throw new IOException("not a reserved control magic: " + Integer.toHexString(magic));
    }

    private static short checkedSchemaVersion(ProtocolRegistryEntry entry) {
        int schemaVersion = entry.schemaVersion();
        if (schemaVersion > Short.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "wire schema version exceeds signed short range: " + schemaVersion);
        }
        return (short) schemaVersion;
    }
}
