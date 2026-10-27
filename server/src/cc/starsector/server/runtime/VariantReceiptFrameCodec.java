package cc.starsector.server.runtime;

import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.ProtocolRegistryEntry;
import cc.starsector.server.protocol.ServerVariantReceiptMessage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Product-owned binary frame codec for the {@code VACK} custom-variant RECEIPT control frame
 * ({@link ServerVariantReceiptMessage}, W15 v0).
 * Envelope = the {@link VariantDefinitionFrameCodec} envelope byte-for-byte; body =
 * {@code u16 submitSeq | u8 verdict | u8 code | u8-len ASCII id} (≤ {@link #MAX_BODY_BYTES}).
 *
 * <p>Verdict {@link #VERDICT_ACCEPTED} (1): code MUST be 0, id = the server-MINTED canonical id
 * the author may now {@code /ship} (the client re-derives the canonical hash from the REGISTERED
 * spec it folded — the desync guard). Verdict {@link #VERDICT_REJECTED} (2): code = the 1-based
 * index of a {@link DoorRejectReasons#custom()} token (the ONE reject vocabulary, bundled into
 * the mod jar so both ends switch on the same list), id = the client-LOCAL id echoed for
 * correlation. NEVER free text — the client renders the token to a sentence.
 *
 * <p>Fail-closed both ways: an unknown verdict, a code outside its verdict's domain, an
 * unlawful id or trailing bytes throw {@link IOException}; encode refuses a token that is not a
 * custom reason. Allocation is bounded by the u8 id length (≤ 64) — no wire-derived sizes
 * beyond it. UNICAST only (the submitter), never fanned, no sim-thread work at decode.
 */
public final class VariantReceiptFrameCodec {

    /** Frame magic: {@code "VACK"}, distinct from every DOWN-family and UP magic. */
    public static final int MAGIC = 0x5641434B; // "VACK"

    private static final ProtocolRegistryEntry REGISTRY_ENTRY =
            NetworkMessageRegistry.authoritativeCombat().serverVariantReceiptWireEntry();
    public static final short SCHEMA_VERSION = checkedSchemaVersion(REGISTRY_ENTRY);
    public static final int MESSAGE_TYPE_CODE = REGISTRY_ENTRY.wireTypeCode();

    public static final short HEADER_BYTES = VariantDefinitionFrameCodec.HEADER_BYTES; // 16

    public static final int VERDICT_ACCEPTED = 1;
    public static final int VERDICT_REJECTED = 2;
    public static final int SUBMIT_SEQ_MAX = VariantSubmitFrameCodec.SUBMIT_SEQ_MAX;

    /** {@code u16 seq + u8 verdict + u8 code + u8 len + id ≤ 64}. */
    public static final int MAX_BODY_BYTES = Short.BYTES + 1 + 1 + 1
            + VariantDefinitionFrameCodec.ID_MAX_CHARS; // 69
    public static final int MAX_FRAME_BYTES = HEADER_BYTES + MAX_BODY_BYTES + Integer.BYTES; // 89
    private static final int MIN_BODY_BYTES = Short.BYTES + 1 + 1 + 1 + 1; // a 1-char id

    /**
     * One receipt. {@code reasonToken} is {@code ""} when accepted, else a
     * {@link DoorRejectReasons#custom()} token; {@code variantId} is the canonical id when
     * accepted, the echoed client-local id when rejected.
     */
    public record VariantReceipt(int submitSeq, boolean accepted, String reasonToken,
            String variantId) {
        public VariantReceipt {
            if (submitSeq < 0 || submitSeq > SUBMIT_SEQ_MAX) {
                throw new IllegalArgumentException("submitSeq outside [0," + SUBMIT_SEQ_MAX
                        + "]: " + submitSeq);
            }
            Objects.requireNonNull(reasonToken, "reasonToken");
            Objects.requireNonNull(variantId, "variantId");
            if (accepted) {
                if (!reasonToken.isEmpty()) {
                    throw new IllegalArgumentException("an ACCEPTED receipt carries no reason,"
                            + " got '" + reasonToken + "'");
                }
            } else if (DoorRejectReasons.customCode(reasonToken) == 0) {
                throw new IllegalArgumentException("a REJECTED receipt needs a"
                        + " DoorRejectReasons.custom() token, got '" + reasonToken + "'");
            }
            if (!VariantDefinitionFrameCodec.isIdLawful(variantId)) {
                throw new IllegalArgumentException("variantId violates the id law (1.."
                        + VariantDefinitionFrameCodec.ID_MAX_CHARS
                        + " printable ASCII, no spaces): '" + variantId + "'");
            }
        }

        /** The u8 wire code: 0 when accepted, else the token's 1-based custom index. */
        public int code() {
            return accepted ? 0 : DoorRejectReasons.customCode(reasonToken);
        }
    }

    private VariantReceiptFrameCodec() {
    }

    public static ProtocolRegistryEntry registryEntry() {
        return REGISTRY_ENTRY;
    }

    public static VariantReceipt accepted(int submitSeq, String canonicalId) {
        return new VariantReceipt(submitSeq, true, "", canonicalId);
    }

    public static VariantReceipt rejected(int submitSeq, String reasonToken, String localId) {
        return new VariantReceipt(submitSeq, false, reasonToken, localId);
    }

    /** Encode one VACK frame to its full wire bytes. */
    public static byte[] encode(VariantReceipt receipt) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeFrame(out, receipt);
        return out.toByteArray();
    }

    /** Write one VACK control frame; returns the bytes written. */
    public static int writeFrame(OutputStream output, VariantReceipt receipt) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(receipt, "receipt");
        String id = receipt.variantId();
        byte[] body = new byte[Short.BYTES + 1 + 1 + 1 + id.length()];
        int off = 0;
        body[off++] = (byte) (receipt.submitSeq() >>> 8);
        body[off++] = (byte) receipt.submitSeq();
        body[off++] = (byte) (receipt.accepted() ? VERDICT_ACCEPTED : VERDICT_REJECTED);
        body[off++] = (byte) receipt.code();
        body[off++] = (byte) id.length();
        for (int i = 0; i < id.length(); i++) {
            body[off++] = (byte) id.charAt(i); // id law guarantees ASCII: bytes == chars
        }
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
     * Read one whole VACK frame (magic included) off {@code input}; {@code null} on a clean end
     * of stream before any byte, {@link IOException} on any law violation.
     */
    public static VariantReceipt readFrame(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        DataInputStream data = new DataInputStream(input);
        int first = data.read();
        if (first < 0) {
            return null;
        }
        int magic = (first << 24) | (data.readUnsignedByte() << 16)
                | (data.readUnsignedByte() << 8) | data.readUnsignedByte();
        if (magic != MAGIC) {
            throw new IOException("variant-receipt frame magic mismatch: 0x"
                    + Integer.toHexString(magic));
        }
        return readFrameAfterMagic(data);
    }

    /** The demux entry: the whole payload, magic at offset 0 (already routed by it). */
    public static VariantReceipt decodePayload(byte[] payload) throws IOException {
        Objects.requireNonNull(payload, "payload");
        if (payload.length < Integer.BYTES) {
            throw new IOException("variant-receipt payload shorter than its magic");
        }
        return readFrameAfterMagic(new DataInputStream(new ByteArrayInputStream(
                payload, Integer.BYTES, payload.length - Integer.BYTES)));
    }

    /**
     * Read one VACK control frame whose 4-byte {@link #MAGIC} has ALREADY been consumed by the
     * caller. Validates schema, header size, wire identity, declared body size, CRC32, the
     * verdict/code domain and the id law; throws {@link IOException} on any mismatch.
     */
    public static VariantReceipt readFrameAfterMagic(DataInputStream data) throws IOException {
        Objects.requireNonNull(data, "data");
        short version = data.readShort();
        requireRegisteredSchema(version);
        short headerBytes = data.readShort();
        if (headerBytes != HEADER_BYTES) {
            throw new IOException("variant-receipt frame header size mismatch: " + headerBytes);
        }
        int messageTypeCode = data.readInt();
        if (messageTypeCode != MESSAGE_TYPE_CODE) {
            throw new IOException("variant-receipt frame wire identity mismatch: "
                    + Integer.toUnsignedString(messageTypeCode));
        }
        int bodyBytes = data.readInt();
        if (bodyBytes < MIN_BODY_BYTES || bodyBytes > MAX_BODY_BYTES) {
            throw new IOException("variant-receipt frame body size invalid: " + bodyBytes);
        }
        byte[] body = new byte[bodyBytes];
        data.readFully(body);
        long expectedChecksum = Integer.toUnsignedLong(data.readInt());
        if (expectedChecksum != checksum(body)) {
            throw new IOException("variant-receipt frame checksum mismatch");
        }
        int submitSeq = ((body[0] & 0xff) << 8) | (body[1] & 0xff);
        int verdict = body[2] & 0xff;
        int code = body[3] & 0xff;
        int idLength = body[4] & 0xff;
        if (idLength < 1 || 5 + idLength != body.length) {
            throw new IOException("variant-receipt frame id length " + idLength
                    + " disagrees with the body (" + body.length + " B)");
        }
        StringBuilder id = new StringBuilder(idLength);
        for (int i = 0; i < idLength; i++) {
            int c = body[5 + i] & 0xff;
            if (c < 0x21 || c > 0x7e) {
                throw new IOException("variant-receipt id carries a non-printable byte: 0x"
                        + Integer.toHexString(c));
            }
            id.append((char) c);
        }
        String reasonToken;
        boolean accepted;
        if (verdict == VERDICT_ACCEPTED) {
            if (code != 0) {
                throw new IOException("variant-receipt ACCEPTED carries reason code " + code);
            }
            accepted = true;
            reasonToken = "";
        } else if (verdict == VERDICT_REJECTED) {
            reasonToken = DoorRejectReasons.customTokenOrNull(code);
            if (reasonToken == null) {
                throw new IOException("variant-receipt REJECTED reason code out of domain: "
                        + code);
            }
            accepted = false;
        } else {
            throw new IOException("variant-receipt verdict unknown: " + verdict);
        }
        try {
            return new VariantReceipt(submitSeq, accepted, reasonToken, id.toString());
        } catch (IllegalArgumentException badReceipt) {
            throw new IOException("variant-receipt frame body invalid: "
                    + badReceipt.getMessage(), badReceipt);
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
                    .require(ServerVariantReceiptMessage.MESSAGE_ID, unsignedSchemaVersion);
        } catch (IllegalArgumentException badSchema) {
            throw new IOException("unsupported variant-receipt frame schema: "
                    + unsignedSchemaVersion, badSchema);
        }
        if (!entry.compatibilityRule().accepts(unsignedSchemaVersion, true)) {
            throw new IOException("variant-receipt frame schema rejected by compatibility"
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
