package cc.starsector.client.net;

import java.util.zip.CRC32;

import cc.starsector.server.runtime.IdTables;

/**
 * Hand-mirror writer for the {@code VSUB} custom-variant SUBMISSION control frame (W15 v0;
 * server twin {@code VariantSubmitFrameCodec}).
 * Envelope = the VDEF envelope byte-for-byte:
 * {@code magic u32 | schema u16 | headerBytes u16 (16) | wireTypeCode u32 | bodyLen u32 | body |
 * CRC32(body)} — the CRC covers the BODY ONLY (NOT the SNAM/PINT 10-B whole-frame shape: a
 * {@link SeatNameWriter} copy here is a fail-closed session kill at the door). Body =
 * {@code u16 submitSeq} + the VDEF body VERBATIM ({@link ClientVariantDefinitionWriter} — schema 2:
 * ids ride as u16 indices into the boot {@link IdTables} the caller passes; a
 * missing table or an unknown id is the same LOUD local refusal as an over-budget body).
 *
 * <p>Mirrors {@code VariantSubmitFrameCodec.SCHEMA_VERSION}. The wire type code is the
 * registry's CRC32 of the message id (the {@link SeatNameWriter} idiom). Sent ONCE per connect on
 * the reliable CTRL lane right after the session publish; the
 * {@code variantId} inside is the CLIENT-LOCAL id echoed by the {@code VACK} — the server mints
 * {@code cc-v<serial>-<slug>}. A body that cannot fit ONE frame is a LOUD local refusal
 * ({@link IllegalArgumentException}) — never a multi-frame blob.
 */
public final class VariantSubmitWriter {
    public static final int MAGIC = 0x56535542; // "VSUB"
    public static final String MESSAGE_ID = "client.variant.submit";
    public static final int MESSAGE_TYPE_CODE = FireDischargeDeclarationWriter.wireTypeCode(MESSAGE_ID);
    public static final int SCHEMA_VERSION = 2;
    public static final int HEADER_BYTES = 4 + 2 + 2 + 4 + 4; // 16
    /** The datagram payload budget the whole frame must fit. */
    public static final int MAX_FRAME_BYTES = 1164;
    public static final int MAX_BODY_BYTES = MAX_FRAME_BYTES - HEADER_BYTES - 4; // 1144
    /** Body bytes that are NOT the VDEF body: the u16 submitSeq. */
    public static final int SUBMIT_PREFIX_BYTES = 2;
    public static final int SUBMIT_SEQ_MAX = 0xFFFF;

    private VariantSubmitWriter() {
    }

    /** The body byte count {@code variant} would submit as (the editor's byte meter; no throw on
     *  over-budget — compare against {@link #MAX_BODY_BYTES}). */
    public static int bodyBytes(ClientVariantDefinition variant, IdTables tables) {
        return SUBMIT_PREFIX_BYTES + ClientVariantDefinitionWriter.encodeBody(variant, tables).length;
    }

    /** Encode one VSUB frame to its full wire bytes (magic through CRC) against {@code tables}; a plain
     *  submission (an accept becomes the seat's next-respawn pick). */
    public static byte[] encodeFrame(int submitSeq, ClientVariantDefinition variant, IdTables tables) {
        return encodeFrame(submitSeq, variant, tables, false);
    }

    /**
     * Encode one VSUB frame whose VDEF body carries the
     * {@link ClientVariantDefinitionWriter#FLAG_KEEP_ONLY} bit when {@code keepOnly}: the server accepts
     * and mints the id as for any submission but seeds no respawn pick. The body length is the same
     * either way (one flag byte), so {@link #bodyBytes} stays the budget meter for both.
     */
    public static byte[] encodeFrame(int submitSeq, ClientVariantDefinition variant, IdTables tables,
            boolean keepOnly) {
        if (submitSeq < 0 || submitSeq > SUBMIT_SEQ_MAX) {
            throw new IllegalArgumentException("submitSeq outside [0," + SUBMIT_SEQ_MAX + "]: "
                    + submitSeq);
        }
        byte[] vdefBody = ClientVariantDefinitionWriter.encodeBody(variant, tables, keepOnly);
        int bodyLength = SUBMIT_PREFIX_BYTES + vdefBody.length;
        if (bodyLength > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("variant-submit body " + bodyLength
                    + " B exceeds the datagram payload budget (" + MAX_BODY_BYTES + " B body cap) for"
                    + " local variant '" + variant.variantId() + "'");
        }
        byte[] b = new byte[HEADER_BYTES + bodyLength + 4];
        int off = WireBytes.putInt(b, 0, MAGIC);
        off = WireBytes.putShort(b, off, SCHEMA_VERSION);
        off = WireBytes.putShort(b, off, HEADER_BYTES);
        off = WireBytes.putInt(b, off, MESSAGE_TYPE_CODE);
        off = WireBytes.putInt(b, off, bodyLength);
        int bodyStart = off;
        off = WireBytes.putByte(b, off, submitSeq >>> 8);
        off = WireBytes.putByte(b, off, submitSeq & 0xff);
        System.arraycopy(vdefBody, 0, b, off, vdefBody.length);
        off += vdefBody.length;
        CRC32 crc = new CRC32();
        crc.update(b, bodyStart, bodyLength); // BODY ONLY — the VDEF-family envelope law
        WireBytes.putInt(b, off, (int) crc.getValue());
        return b;
    }
}
