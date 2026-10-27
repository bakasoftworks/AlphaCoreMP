package cc.starsector.client.net;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;

import cc.starsector.server.runtime.DoorRejectReasons;

/**
 * Hand-mirror reader for the {@code VACK} custom-variant RECEIPT control frame (W15 v0;
 * server twin {@code VariantReceiptFrameCodec}). Envelope = the VDEF envelope
 * ({@link ClientVariantDefinitionReader}): {@code magic | schema u16 | headerBytes u16 (16) |
 * wireTypeCode u32 | bodyLen u32 | body | CRC32(body)}; body = {@code u16 submitSeq | u8 verdict
 * (1 ACCEPTED / 2 REJECTED) | u8 reasonCode | u8 idLen | id bytes} (≤ 69 B).
 *
 * <p>Mirrors {@code VariantReceiptFrameCodec.SCHEMA_VERSION}. Decoded FAIL-CLOSED (the UP/
 * DOWN-fuzz law): schema, header width, wire identity, declared size, CRC32, verdict domain,
 * reason-code domain ({@code DoorRejectReasons.custom()}, bundled in the mod jar since rider
 * {@code 1346-9-3}), id law and length agreement all throw {@link IOException} — the demux's ONE
 * caught class. The server unicasts a VACK ONLY to a session that submitted (first-use gating,
 * per {@code VariantReceiptFrameCodec}), so a stale kit never sees this magic.
 */
public final class ClientVariantReceiptReader {
    public static final int MAGIC = 0x5641434B; // "VACK"
    public static final String MESSAGE_ID = "server.variant.receipt";
    public static final int MESSAGE_TYPE_CODE = FireDischargeDeclarationWriter.wireTypeCode(MESSAGE_ID);
    public static final int SCHEMA_VERSION = 1;
    public static final int HEADER_BYTES = 4 + 2 + 2 + 4 + 4; // 16
    public static final int VERDICT_ACCEPTED = 1;
    public static final int VERDICT_REJECTED = 2;
    public static final int MAX_BODY_BYTES = 2 + 1 + 1 + 1 + ClientVariantDefinition.ID_MAX_CHARS; // 69
    public static final int MAX_FRAME_BYTES = HEADER_BYTES + MAX_BODY_BYTES + 4; // 89
    private static final int MIN_BODY_BYTES = 2 + 1 + 1 + 1 + 1; // a 1-char id

    private ClientVariantReceiptReader() {
    }

    /** Read one whole VACK frame (magic included); {@code null} on a clean EOF before any byte. */
    public static ClientVariantReceipt readFrame(InputStream in) throws IOException {
        int first = in.read();
        if (first < 0) {
            return null;
        }
        int magic = ((first & 0xff) << 24)
                | (WireBytes.readUnsignedByte(in) << 16)
                | (WireBytes.readUnsignedByte(in) << 8)
                | WireBytes.readUnsignedByte(in);
        if (magic != MAGIC) {
            throw new IOException("variant-receipt frame magic mismatch: "
                    + Integer.toHexString(magic));
        }
        return readAfterMagic(in);
    }

    /** The demux entry: the 4-byte magic has ALREADY been consumed by the caller. */
    public static ClientVariantReceipt readAfterMagic(InputStream in) throws IOException {
        int schema = WireBytes.readUnsignedShort(in);
        if (schema != SCHEMA_VERSION) {
            throw new IOException("variant-receipt frame schema version mismatch: expected "
                    + SCHEMA_VERSION + ", got " + schema);
        }
        int headerBytes = WireBytes.readUnsignedShort(in);
        if (headerBytes != HEADER_BYTES) {
            throw new IOException("variant-receipt frame header size mismatch: " + headerBytes);
        }
        int typeCode = WireBytes.readInt(in);
        if (typeCode != MESSAGE_TYPE_CODE) {
            throw new IOException("variant-receipt frame wire identity mismatch: "
                    + Integer.toUnsignedString(typeCode));
        }
        int bodyBytes = WireBytes.readInt(in);
        if (bodyBytes < MIN_BODY_BYTES || bodyBytes > MAX_BODY_BYTES) {
            throw new IOException("variant-receipt frame body size invalid: " + bodyBytes);
        }
        byte[] body = new byte[bodyBytes];
        WireBytes.readFully(in, body);
        int expectedCrc = WireBytes.readInt(in);
        CRC32 crc = new CRC32();
        crc.update(body, 0, body.length);
        if ((int) crc.getValue() != expectedCrc) {
            throw new IOException("variant-receipt frame checksum mismatch");
        }
        return decodeBody(body);
    }

    private static ClientVariantReceipt decodeBody(byte[] body) throws IOException {
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
        boolean accepted;
        String reasonToken;
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
            return new ClientVariantReceipt(submitSeq, accepted, reasonToken, id.toString());
        } catch (IllegalArgumentException badReceipt) {
            throw new IOException("variant-receipt frame body invalid: "
                    + badReceipt.getMessage(), badReceipt);
        }
    }
}
