package cc.starsector.server.runtime;

import cc.starsector.server.protocol.ClientVariantSubmitMessage;
import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.ProtocolRegistryEntry;
import cc.starsector.server.runtime.VariantDefinitionFrameCodec.VariantDefinition;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Product-owned binary frame codec for the {@code VSUB} custom-variant SUBMISSION control frame
 * ({@link ClientVariantSubmitMessage}, W15 v0).
 * Envelope = the {@link VariantDefinitionFrameCodec} envelope byte-for-byte
 * ({@code magic | schema | headerBytes | wireTypeCode | bodyLen | body | CRC32}); body =
 * {@code u16 submitSeq} + the VDEF body VERBATIM ({@link VariantDefinitionFrameCodec#encodeBody}),
 * so the client's hand-mirror writer ({@code WireBytes} + {@code CRC32}, no {@code Data*Stream}
 * under classloader B) targets ONE body grammar and the server re-uses ONE decoder.
 *
 * <p>The {@code variantId} inside the body is the CLIENT-LOCAL id (a correlation handle echoed
 * in the {@code VACK}); the server never registers it — it mints {@code cc-v<serial>-<slug>}.
 * {@code submitSeq} is the client's per-session u16 counter (wraps), echoed in the receipt.
 *
 * <p>Security (UP-fuzz law): decoded FAIL-CLOSED — schema / header / wire
 * identity / declared size / CRC32 / every VDEF field law / trailing bytes all throw
 * {@link IOException} (the dispatch site's ONE caught class ⇒ session kill, endpoint survives);
 * allocation is bounded by the declared body size, itself ≤ {@link #MAX_BODY_BYTES}; no JSON,
 * path or reflection target ever rides the frame. A submission that cannot fit ONE frame is a
 * client-side loud encode refusal (the editor shows the byte meter) — never a multi-frame blob.
 */
public final class VariantSubmitFrameCodec {

    /** Frame magic: {@code "VSUB"}, distinct from every DOWN-family and UP magic. */
    public static final int MAGIC = 0x56535542; // "VSUB"

    private static final ProtocolRegistryEntry REGISTRY_ENTRY =
            NetworkMessageRegistry.authoritativeCombat().clientVariantSubmitWireEntry();
    public static final short SCHEMA_VERSION = checkedSchemaVersion(REGISTRY_ENTRY);
    public static final int MESSAGE_TYPE_CODE = REGISTRY_ENTRY.wireTypeCode();

    public static final short HEADER_BYTES = VariantDefinitionFrameCodec.HEADER_BYTES; // 16

    /** The datagram payload budget the whole frame must fit. */
    public static final int MAX_FRAME_BYTES = VariantDefinitionFrameCodec.MAX_FRAME_BYTES; // 1164
    public static final int MAX_BODY_BYTES = MAX_FRAME_BYTES - HEADER_BYTES - Integer.BYTES; // 1144
    /** Bytes of the body that are NOT the VDEF body: the u16 submitSeq. */
    public static final int SUBMIT_PREFIX_BYTES = Short.BYTES;
    public static final int SUBMIT_SEQ_MAX = 0xFFFF;

    /** One submission: the client's u16 sequence + the variant as the client encoded it. */
    public record VariantSubmit(int submitSeq, VariantDefinition variant) {
        public VariantSubmit {
            if (submitSeq < 0 || submitSeq > SUBMIT_SEQ_MAX) {
                throw new IllegalArgumentException("submitSeq outside [0," + SUBMIT_SEQ_MAX
                        + "]: " + submitSeq);
            }
            Objects.requireNonNull(variant, "variant");
        }
    }

    private VariantSubmitFrameCodec() {
    }

    public static ProtocolRegistryEntry registryEntry() {
        return REGISTRY_ENTRY;
    }

    /** Encode one VSUB frame to its full wire bytes. */
    public static byte[] encode(VariantSubmit submit, IdTables tables) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeFrame(out, submit, tables);
        return out.toByteArray();
    }

    /** Write one VSUB control frame; returns the bytes written. */
    public static int writeFrame(OutputStream output, VariantSubmit submit, IdTables tables) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(submit, "submit");
        byte[] vdefBody = VariantDefinitionFrameCodec.encodeBody(submit.variant(), tables);
        int bodyLength = SUBMIT_PREFIX_BYTES + vdefBody.length;
        if (bodyLength > MAX_BODY_BYTES) {
            throw new IOException("variant-submit frame body " + bodyLength
                    + " B exceeds the wire budget (" + MAX_BODY_BYTES + " B body cap)"
                    + " for local variant '" + submit.variant().variantId() + "'");
        }
        byte[] body = new byte[bodyLength];
        body[0] = (byte) (submit.submitSeq() >>> 8);
        body[1] = (byte) submit.submitSeq();
        System.arraycopy(vdefBody, 0, body, SUBMIT_PREFIX_BYTES, vdefBody.length);
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
     * Read one whole VSUB frame (magic included) off {@code input}; {@code null} on a clean end
     * of stream before any byte, {@link IOException} on any law violation.
     */
    public static VariantSubmit readFrame(InputStream input, IdTables tables) throws IOException {
        Objects.requireNonNull(input, "input");
        DataInputStream data = new DataInputStream(input);
        int first = data.read();
        if (first < 0) {
            return null;
        }
        int magic = (first << 24) | (data.readUnsignedByte() << 16)
                | (data.readUnsignedByte() << 8) | data.readUnsignedByte();
        if (magic != MAGIC) {
            throw new IOException("variant-submit frame magic mismatch: 0x"
                    + Integer.toHexString(magic));
        }
        return readFrameAfterMagic(data, tables);
    }

    /** The demux entry: the whole CTRL payload, magic at offset 0 (already routed by it). One datagram
     * payload IS one frame: bytes after the CRC are refused here — the
     * stream reader {@link #readFrame} stays tail-blind (the TCP demux reads the next frame there). */
    public static VariantSubmit decodePayload(byte[] payload, IdTables tables) throws IOException {
        Objects.requireNonNull(payload, "payload");
        if (payload.length < Integer.BYTES) {
            throw new IOException("variant-submit payload shorter than its magic");
        }
        DataInputStream data = new DataInputStream(new ByteArrayInputStream(
                payload, Integer.BYTES, payload.length - Integer.BYTES));
        VariantSubmit submit = readFrameAfterMagic(data, tables);
        if (data.available() != 0) {
            throw new IOException("variant-submit payload has " + data.available() + " byte(s) after the frame");
        }
        return submit;
    }

    /**
     * Read one VSUB control frame whose 4-byte {@link #MAGIC} has ALREADY been consumed by the
     * caller (the CTRL demux peeks the magic to route). Validates schema, header size, wire
     * identity, declared body size, CRC32, the submitSeq prefix and every VDEF field law
     * (trailing bytes refused); throws {@link IOException} on any mismatch.
     */
    public static VariantSubmit readFrameAfterMagic(DataInputStream data, IdTables tables) throws IOException {
        Objects.requireNonNull(data, "data");
        short version = data.readShort();
        requireRegisteredSchema(version);
        short headerBytes = data.readShort();
        if (headerBytes != HEADER_BYTES) {
            throw new IOException("variant-submit frame header size mismatch: " + headerBytes);
        }
        int messageTypeCode = data.readInt();
        if (messageTypeCode != MESSAGE_TYPE_CODE) {
            throw new IOException("variant-submit frame wire identity mismatch: "
                    + Integer.toUnsignedString(messageTypeCode));
        }
        int bodyBytes = data.readInt();
        if (bodyBytes <= SUBMIT_PREFIX_BYTES || bodyBytes > MAX_BODY_BYTES) {
            throw new IOException("variant-submit frame body size invalid: " + bodyBytes);
        }
        byte[] body = new byte[bodyBytes];
        data.readFully(body);
        long expectedChecksum = Integer.toUnsignedLong(data.readInt());
        if (expectedChecksum != checksum(body)) {
            throw new IOException("variant-submit frame checksum mismatch");
        }
        int submitSeq = ((body[0] & 0xff) << 8) | (body[1] & 0xff);
        VariantDefinition variant = VariantDefinitionFrameCodec.decodeBody(
                Arrays.copyOfRange(body, SUBMIT_PREFIX_BYTES, body.length), tables);
        return new VariantSubmit(submitSeq, variant);
    }

    private static long checksum(byte[] body) {
        CRC32 crc = new CRC32();
        crc.update(body, 0, body.length);
        return crc.getValue();
    }

    private static void requireRegisteredSchema(short schemaVersion) throws IOException {
        int unsignedSchemaVersion = Short.toUnsignedInt(schemaVersion);
        if (unsignedSchemaVersion != SCHEMA_VERSION) {
            // The explicit refusal naming the bump (the VDEF twin).
            throw new IOException("variant-submit frame schema " + unsignedSchemaVersion
                    + " refused: this build speaks schema " + SCHEMA_VERSION
                    + " (dictionary ids) — an older kit's string-id body cannot decode here");
        }
        ProtocolRegistryEntry entry;
        try {
            entry = NetworkMessageRegistry.authoritativeCombat()
                    .require(ClientVariantSubmitMessage.MESSAGE_ID, unsignedSchemaVersion);
        } catch (IllegalArgumentException badSchema) {
            throw new IOException("unsupported variant-submit frame schema: "
                    + unsignedSchemaVersion, badSchema);
        }
        if (!entry.compatibilityRule().accepts(unsignedSchemaVersion, true)) {
            throw new IOException("variant-submit frame schema rejected by compatibility"
                    + " policy: " + unsignedSchemaVersion);
        }
    }

    private static short checkedSchemaVersion(ProtocolRegistryEntry registryEntry) {
        int schemaVersion = registryEntry.schemaVersion();
        if (schemaVersion > Short.MAX_VALUE) {
            throw new IllegalArgumentException("wire schema version exceeds signed short range: "
                    + schemaVersion);
        }
        return (short) schemaVersion;
    }
}
