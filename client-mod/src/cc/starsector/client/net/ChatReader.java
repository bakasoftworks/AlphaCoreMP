package cc.starsector.client.net;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Client-side decoder for the server's {@code CHTD} chat relay wire frame (in-match
 * text chat): the receiving half of the server's {@code ChatFrameCodec}
 * ({@code server.chat.text} v1). The server fans one {@code CHTD} per relayed chat line to
 * EVERY live session on the reliable CTRL lane — sender included (echo = delivery
 * confirmation) — carrying the sender's seat id + the server-stamped display name, so a
 * receiver labels the line with no {@code SNMS}-ordering dependency. The
 * {@link MultiplexedServerStreamReader} routes it here by its leading magic;
 * {@link ChatWriter} is the sending half of the family.
 *
 * <p>Like {@link ClientSeatNamesReader}, the mod deliberately does not bundle the server codec
 * (whose static type-code initializers drag {@code NetworkMessageRegistry} into the game JVM).
 * It reads exactly the fixed-width bytes the server writes — magic / wireTypeCode / schema /
 * seatId / (nameLen + 48 B zero-padded UTF-8 name) / (textLen + 240 B zero-padded UTF-8 text) /
 * CRC32-over-body — over plain byte-array primitives plus the allowlisted {@link CRC32}. The
 * game-free cross-check streams real server
 * {@code ChatFrameCodec} bytes through this reader and asserts the decodes match, keeping the
 * mirrors in sync.
 *
 * <p>Fail-closed, mirroring the server decode exactly (field guards fire before the CRC
 * compare, the codec's documented order): a wire-type-code or schema mismatch, a negative
 * seatId, a {@code nameLen}/{@code textLen} out of range (BOTH are 1-minimum — no empty
 * sentinel in this family), nonzero padding beyond either length, a name/text that is not
 * valid UTF-8 or violates its law, or a CRC32 mismatch all throw {@link IOException}.
 */
public final class ChatReader {

    /** Frame magic: ASCII "CHTD". Mirrors {@code ChatFrameCodec.DOWN_MAGIC}. */
    public static final int MAGIC = 0x43485444;

    /** Registry message id this frame carries. Mirrors {@code ServerChatTextMessage.MESSAGE_ID}. */
    public static final String MESSAGE_ID = "server.chat.text";

    /** Wire type code = CRC32(MESSAGE_ID), mirroring {@code ProtocolRegistryEntry.wireTypeCode}. */
    public static final int MESSAGE_TYPE_CODE = FireDischargeDeclarationWriter.wireTypeCode(MESSAGE_ID);

    /** Schema version this reader understands. Mirrors {@code ServerChatTextMessage.SCHEMA_VERSION}. */
    public static final int SCHEMA_VERSION = 1;

    /** magic(int) + typeCode(int) + schema(short). Mirrors {@code ChatFrameCodec.HEADER_BYTES}. */
    public static final int HEADER_BYTES = Integer.BYTES + Integer.BYTES + Short.BYTES; // 10
    /** Fixed zero-padded UTF-8 buffer for the stamped name. Mirrors {@code SeatNameFrameCodec.NAME_BUFFER_BYTES}. */
    public static final int NAME_BUFFER_BYTES = 48;
    /** Fixed zero-padded UTF-8 buffer for the chat line. Mirrors {@code ChatFrameCodec.TEXT_BUFFER_BYTES}. */
    public static final int TEXT_BUFFER_BYTES = 240;
    /** seatId(int) + nameLen(1B) + name buffer + textLen(u16) + text buffer. */
    public static final int PAYLOAD_BYTES =
            Integer.BYTES + 1 + NAME_BUFFER_BYTES + Short.BYTES + TEXT_BUFFER_BYTES; // 295
    /** Fixed frame width. Mirrors {@code ChatFrameCodec.DOWN_FRAME_BYTES}. */
    public static final int FRAME_BYTES = HEADER_BYTES + PAYLOAD_BYTES + Integer.BYTES; // 309

    private ChatReader() {
    }

    /**
     * Decode one chat relay whose 4-byte {@link #MAGIC} has <em>already</em> been read and
     * validated by the caller — the {@link MultiplexedServerStreamReader} entry point. The CRC32
     * covers the frame INCLUDING the magic (the server codec checksums {@code [0, 305)} of the
     * full frame), so this reconstructs the full frame buffer before validating.
     */
    public static ClientChatMessage readAfterMagic(InputStream in) throws IOException {
        byte[] frame = new byte[FRAME_BYTES];
        WireBytes.putInt(frame, 0, MAGIC);
        byte[] rest = new byte[FRAME_BYTES - 4];
        WireBytes.readFully(in, rest); // mid-frame EOF throws — the magic is already consumed
        System.arraycopy(rest, 0, frame, 4, rest.length);
        return decodeFrame(frame);
    }

    /** Decode + validate one full {@code CHTD} frame — mirrors {@code ChatFrameCodec.decodeDown}. */
    public static ClientChatMessage decodeFrame(byte[] frame) throws IOException {
        if (frame == null || frame.length != FRAME_BYTES) {
            throw new IOException("chat down frame must be " + FRAME_BYTES + " bytes, got "
                    + (frame == null ? "null" : frame.length));
        }
        CRC32 crc = new CRC32();
        crc.update(frame, 0, HEADER_BYTES + PAYLOAD_BYTES);
        int expectedCrc = (int) crc.getValue();
        int off = 0;
        int magic = WireBytes.getInt(frame, off);
        off += 4;
        if (magic != MAGIC) {
            throw new IOException("chat down bad magic: 0x" + Integer.toHexString(magic));
        }
        int typeCode = WireBytes.getInt(frame, off);
        off += 4;
        if (typeCode != MESSAGE_TYPE_CODE) {
            throw new IOException("chat down wire type code mismatch: expected "
                    + MESSAGE_TYPE_CODE + ", got " + typeCode);
        }
        int schema = ((frame[off] & 0xff) << 8) | (frame[off + 1] & 0xff);
        off += 2;
        if (schema != SCHEMA_VERSION) {
            throw new IOException("chat down unsupported schema version: " + schema);
        }
        int seatId = WireBytes.getInt(frame, off);
        off += 4;
        if (seatId < 0) {
            throw new IOException("chat down seatId out of range: " + seatId);
        }
        String name = readStampedName(frame, off);
        off += 1 + NAME_BUFFER_BYTES;
        String text = readTextBuffer(frame, off);
        off += Short.BYTES + TEXT_BUFFER_BYTES;
        int actualCrc = WireBytes.getInt(frame, off);
        if (actualCrc != expectedCrc) {
            throw new IOException("chat down frame CRC mismatch");
        }
        try {
            return new ClientChatMessage(seatId, name, text);
        } catch (IllegalArgumentException badFields) {
            throw new IOException("chat down fields invalid: " + badFields.getMessage(), badFields);
        }
    }

    /**
     * Read + validate the stamped nameLen + zero-padded buffer at {@code off} — mirrors the
     * server codec's {@code readStampedName} exactly: nameLen {@code 1..48} (empty REJECTED —
     * the server always stamps a fallback), fail-closed padding, UTF-8 round-trip guard, the
     * seat-name contract via {@link SeatNameWriter#validateSeatName}.
     */
    private static String readStampedName(byte[] frame, int off) throws IOException {
        int len = frame[off] & 0xff;
        if (len < 1 || len > NAME_BUFFER_BYTES) {
            throw new IOException("chat down nameLen out of range [1," + NAME_BUFFER_BYTES
                    + "]: " + len);
        }
        int nameStart = off + 1;
        for (int i = nameStart + len; i < nameStart + NAME_BUFFER_BYTES; i++) {
            if (frame[i] != 0) {
                throw new IOException("chat down nonzero padding beyond nameLen=" + len
                        + " at buffer index " + (i - nameStart));
            }
        }
        String value = new String(frame, nameStart, len, StandardCharsets.UTF_8);
        // Malformed UTF-8 decodes with replacement chars and would not re-encode byte-identical:
        // fail closed rather than render a mangled name (the server codec's exact guard).
        byte[] reencoded = value.getBytes(StandardCharsets.UTF_8);
        if (reencoded.length != len) {
            throw new IOException("chat down name is not valid UTF-8");
        }
        for (int i = 0; i < len; i++) {
            if (reencoded[i] != frame[nameStart + i]) {
                throw new IOException("chat down name is not valid UTF-8");
            }
        }
        try {
            SeatNameWriter.validateSeatName(value);
        } catch (IllegalArgumentException bad) {
            throw new IOException("chat down name invalid: " + bad.getMessage(), bad);
        }
        return value;
    }

    /**
     * Read + validate one textLen(u16) + zero-padded buffer at {@code off} — mirrors the server
     * codec's {@code readTextBuffer} exactly: textLen {@code 1..240} (NO empty sentinel),
     * fail-closed padding, UTF-8 round-trip guard, the chat-text law via
     * {@link ChatWriter#validateChatText}.
     */
    private static String readTextBuffer(byte[] frame, int off) throws IOException {
        int len = ((frame[off] & 0xff) << 8) | (frame[off + 1] & 0xff);
        if (len < 1 || len > TEXT_BUFFER_BYTES) {
            throw new IOException("chat down textLen out of range [1," + TEXT_BUFFER_BYTES
                    + "]: " + len);
        }
        int textStart = off + Short.BYTES;
        for (int i = textStart + len; i < textStart + TEXT_BUFFER_BYTES; i++) {
            if (frame[i] != 0) {
                throw new IOException("chat down nonzero padding beyond textLen=" + len
                        + " at buffer index " + (i - textStart));
            }
        }
        String value = new String(frame, textStart, len, StandardCharsets.UTF_8);
        byte[] reencoded = value.getBytes(StandardCharsets.UTF_8);
        if (reencoded.length != len) {
            throw new IOException("chat down text is not valid UTF-8");
        }
        for (int i = 0; i < len; i++) {
            if (reencoded[i] != frame[textStart + i]) {
                throw new IOException("chat down text is not valid UTF-8");
            }
        }
        try {
            ChatWriter.validateChatText(value);
        } catch (IllegalArgumentException bad) {
            throw new IOException("chat down text invalid: " + bad.getMessage(), bad);
        }
        return value;
    }
}
