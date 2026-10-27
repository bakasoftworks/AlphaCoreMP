package cc.starsector.server.runtime;

import cc.starsector.server.protocol.ClientChatTextMessage;
import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.ServerChatTextMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.CRC32;

import static cc.starsector.server.runtime.WireByteOps.getInt;
import static cc.starsector.server.runtime.WireByteOps.getUnsignedShort;
import static cc.starsector.server.runtime.WireByteOps.putInt;
import static cc.starsector.server.runtime.WireByteOps.putShort;

/**
 * Fixed-width wire codec for the in-match text chat — BOTH frames: the client→server
 * {@code CHTU} send ({@link ClientChatTextMessage}) and the server→all-sessions {@code CHTD}
 * relay ({@link ServerChatTextMessage}).
 *
 * <p>The SNAM/SNMS twin ({@link SeatNameFrameCodec}): magic + registered wire type code + schema
 * header, fixed width, CRC32 over everything before the trailer, fail-closed reads. The client
 * mod does NOT load this class (its static type-code initializers drag
 * {@link NetworkMessageRegistry} into the game JVM); it speaks the same bytes through the
 * hand-mirrored {@code ChatWriter} / {@code ChatReader}, and a game-free verifier byte-compares
 * the mirrors against this codec — any drift breaks it.
 *
 * <p><b>Text buffer contract:</b> the chat line rides as a 2-byte UTF-8 byte-count prefix + a
 * fixed {@link #TEXT_BUFFER_BYTES}-byte zero-padded buffer. Unlike the seat-name buffers there
 * is NO length-0 sentinel — a chat frame without text is meaningless, so {@code textLen} is
 * {@code 1..240} and blank text fails closed. The text obeys {@link #validateChatText} (the
 * seat-name law widened to chat length: trimmed, non-blank,
 * ≤{@link #TEXT_MAX_CHARS} chars, ≤{@link #TEXT_BUFFER_BYTES} UTF-8 bytes, single-line
 * printable) and must re-encode byte-identical (malformed UTF-8 fails closed); padding beyond
 * {@code textLen} MUST be zero. The CRC covers the whole zero-padded buffer.
 *
 * <p><b>DOWN attribution:</b> {@code CHTD} additionally carries the sender's seat id plus the
 * display name the SERVER stamped (announce name or {@code "seat-"+seatId} fallback) on the
 * {@link SeatNameFrameCodec#NAME_BUFFER_BYTES} buffer law — REQUIRED non-empty here (the server
 * always has a fallback; an empty stamp is a broken frame). Self-contained: a receiver labels
 * the line with no {@code SNMS}-ordering dependency.
 */
public final class ChatFrameCodec {

    /** CHTU magic = ASCII "CHTU" (CHat Text Up, client→server). */
    public static final int UP_MAGIC = 0x43485455;
    /** CHTD magic = ASCII "CHTD" (CHat Text Down, server→all sessions). */
    public static final int DOWN_MAGIC = 0x43485444;

    /** Registered wire type codes these frames carry. */
    public static final int UP_TYPE_CODE =
            NetworkMessageRegistry.authoritativeCombat()
                    .clientChatTextWireEntry().wireTypeCode();
    public static final int DOWN_TYPE_CODE =
            NetworkMessageRegistry.authoritativeCombat()
                    .serverChatTextWireEntry().wireTypeCode();

    public static final short UP_SCHEMA_VERSION =
            (short) ClientChatTextMessage.SCHEMA_VERSION;
    public static final short DOWN_SCHEMA_VERSION =
            (short) ServerChatTextMessage.SCHEMA_VERSION;

    /** magic(int) + typeCode(int) + schema(short). */
    public static final int HEADER_BYTES = Integer.BYTES + Integer.BYTES + Short.BYTES; // 10
    /** Fixed zero-padded UTF-8 buffer per chat line (≤120 chars can need up to 240 UTF-8 bytes
     * in the printable-two-byte range; anything longer fails the byte cap before the char cap). */
    public static final int TEXT_BUFFER_BYTES = 240;
    /** The chat-line char cap (the validateChatText contract). */
    public static final int TEXT_MAX_CHARS = 120;
    /** textLen(u16) + text buffer. */
    public static final int UP_PAYLOAD_BYTES = Short.BYTES + TEXT_BUFFER_BYTES; // 242
    public static final int UP_FRAME_BYTES =
            HEADER_BYTES + UP_PAYLOAD_BYTES + Integer.BYTES; // 256
    /** seatId(int) + nameLen(1B) + name buffer + textLen(u16) + text buffer. */
    public static final int DOWN_PAYLOAD_BYTES =
            Integer.BYTES + 1 + SeatNameFrameCodec.NAME_BUFFER_BYTES + Short.BYTES
                    + TEXT_BUFFER_BYTES; // 295
    public static final int DOWN_FRAME_BYTES =
            HEADER_BYTES + DOWN_PAYLOAD_BYTES + Integer.BYTES; // 309

    /** One decoded {@code CHTU} send ({@code text} always non-blank and law-conforming). */
    public record ChatUp(String text) {
        public ChatUp {
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * One decoded {@code CHTD} relay. {@code name} is the server-stamped display name (never
     * empty); {@code seatId} is the sender's seat.
     */
    public record ChatDown(int seatId, String name, String text) {
        public ChatDown {
            if (seatId < 0) {
                throw new IllegalArgumentException("seatId must be >= 0; got " + seatId);
            }
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(text, "text");
        }
    }

    private ChatFrameCodec() {}

    /**
     * The chat text law (the seat-name contract widened to chat length): trimmed,
     * non-blank, ≤{@link #TEXT_MAX_CHARS} chars, single-line printable (the shared
     * {@link WireTextLaw#isPrintableWireChar} law — no C0/DEL, no C1/NEL/LS/PS/bidi control;
     * interior spaces and non-ASCII printables OK), and
     * ≤{@link #TEXT_BUFFER_BYTES} UTF-8 bytes. Throws {@link IllegalArgumentException}. There
     * is no empty sentinel — blank text is invalid everywhere.
     */
    public static void validateChatText(String text) {
        Objects.requireNonNull(text, "text");
        if (text.isEmpty() || !text.equals(text.trim())) {
            throw new IllegalArgumentException(
                    "chat text must be trimmed and non-blank; got " + text.length() + " chars");
        }
        if (text.length() > TEXT_MAX_CHARS) {
            throw new IllegalArgumentException(
                    "chat text must be <= " + TEXT_MAX_CHARS + " chars, got " + text.length());
        }
        int bad = WireTextLaw.firstUnprintableIndex(text);
        if (bad >= 0) {
            throw new IllegalArgumentException(
                    "chat text must be single-line printable (control char at index " + bad + ")");
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > TEXT_BUFFER_BYTES) {
            throw new IllegalArgumentException(
                    "chat text must encode to <= " + TEXT_BUFFER_BYTES + " UTF-8 bytes");
        }
    }

    /** Encode one {@code CHTU} send ({@code text} must pass {@link #validateChatText}). */
    public static byte[] encodeUp(String text) {
        byte[] textBytes = validatedTextBytes(text);
        byte[] buf = new byte[UP_FRAME_BYTES];
        int off = 0;
        off = putInt(buf, off, UP_MAGIC);
        off = putInt(buf, off, UP_TYPE_CODE);
        off = putShort(buf, off, UP_SCHEMA_VERSION);
        off = putTextBuffer(buf, off, textBytes);
        // off now == HEADER_BYTES + UP_PAYLOAD_BYTES; CRC32 covers exactly that body.
        CRC32 crc = new CRC32();
        crc.update(buf, 0, off);
        putInt(buf, off, (int) crc.getValue());
        return buf;
    }

    /** Serialize one {@code CHTU} to {@code out} as exactly {@link #UP_FRAME_BYTES} bytes. */
    public static void writeUp(OutputStream out, String text) throws IOException {
        Objects.requireNonNull(out, "out");
        byte[] bytes = encodeUp(text);
        out.write(bytes, 0, bytes.length);
    }

    /**
     * Read exactly one {@code CHTU} from {@code in}, blocking until {@link #UP_FRAME_BYTES}
     * arrive (the CTRL demux hands the full reassembled payload as a stream). Returns
     * {@code null} at a clean end of stream (zero bytes); throws fail-closed on truncation,
     * bad magic/type/schema, nonzero padding, law-breaking text, or a CRC mismatch.
     */
    public static ChatUp readUp(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        byte[] frame = in.readNBytes(UP_FRAME_BYTES);
        if (frame.length == 0) {
            return null; // clean end of stream
        }
        if (frame.length != UP_FRAME_BYTES) {
            throw new IOException("chat up frame truncated: expected "
                    + UP_FRAME_BYTES + " bytes, got " + frame.length);
        }
        return decodeUp(frame);
    }

    /** Decode + validate one full {@code CHTU} frame (fail-closed; verifier + read path). */
    public static ChatUp decodeUp(byte[] frame) throws IOException {
        Objects.requireNonNull(frame, "frame");
        if (frame.length != UP_FRAME_BYTES) {
            throw new IOException("chat up frame must be " + UP_FRAME_BYTES
                    + " bytes, got " + frame.length);
        }
        CRC32 crc = new CRC32();
        crc.update(frame, 0, HEADER_BYTES + UP_PAYLOAD_BYTES);
        int expectedCrc = (int) crc.getValue();
        int off = 0;
        int magic = getInt(frame, off); off += 4;
        if (magic != UP_MAGIC) {
            throw new IOException("chat up bad magic: 0x" + Integer.toHexString(magic));
        }
        int typeCode = getInt(frame, off); off += 4;
        if (typeCode != UP_TYPE_CODE) {
            throw new IOException("chat up wire type code mismatch: expected "
                    + UP_TYPE_CODE + ", got " + typeCode);
        }
        int schema = getUnsignedShort(frame, off); off += 2;
        if (schema != UP_SCHEMA_VERSION) {
            throw new IOException("chat up unsupported schema version: " + schema);
        }
        String text = readTextBuffer(frame, off, "chat up");
        off += Short.BYTES + TEXT_BUFFER_BYTES;
        int actualCrc = getInt(frame, off);
        if (actualCrc != expectedCrc) {
            throw new IOException("chat up frame CRC mismatch");
        }
        return new ChatUp(text);
    }

    /**
     * Encode one {@code CHTD} relay. {@code name} is REQUIRED non-empty under the
     * {@link SeatNameFrameCodec#validateSeatName} law (the server always stamps a fallback);
     * {@code text} must pass {@link #validateChatText}.
     */
    public static byte[] encodeDown(int seatId, String name, String text) {
        if (seatId < 0) {
            throw new IllegalArgumentException("seatId must be >= 0; got " + seatId);
        }
        byte[] nameBytes = validatedStampedNameBytes(name);
        byte[] textBytes = validatedTextBytes(text);
        byte[] buf = new byte[DOWN_FRAME_BYTES];
        int off = 0;
        off = putInt(buf, off, DOWN_MAGIC);
        off = putInt(buf, off, DOWN_TYPE_CODE);
        off = putShort(buf, off, DOWN_SCHEMA_VERSION);
        off = putInt(buf, off, seatId);
        buf[off] = (byte) nameBytes.length;
        System.arraycopy(nameBytes, 0, buf, off + 1, nameBytes.length);
        // bytes beyond nameLen stay zero (fresh array) — the fail-closed padding contract.
        off += 1 + SeatNameFrameCodec.NAME_BUFFER_BYTES;
        off = putTextBuffer(buf, off, textBytes);
        // off now == HEADER_BYTES + DOWN_PAYLOAD_BYTES; CRC32 covers exactly that body.
        CRC32 crc = new CRC32();
        crc.update(buf, 0, off);
        putInt(buf, off, (int) crc.getValue());
        return buf;
    }

    /** Serialize one {@code CHTD} to {@code out} as exactly {@link #DOWN_FRAME_BYTES} bytes. */
    public static void writeDown(OutputStream out, int seatId, String name, String text)
            throws IOException {
        Objects.requireNonNull(out, "out");
        byte[] bytes = encodeDown(seatId, name, text);
        out.write(bytes, 0, bytes.length);
    }

    /**
     * Read exactly one {@code CHTD} from {@code in} (game-free tests / round-trip verifier; the
     * live client reads it via the mirrored {@code ChatReader} off its demux). Returns
     * {@code null} at a clean end of stream; fail-closed otherwise.
     */
    public static ChatDown readDown(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        byte[] frame = in.readNBytes(DOWN_FRAME_BYTES);
        if (frame.length == 0) {
            return null; // clean end of stream
        }
        if (frame.length != DOWN_FRAME_BYTES) {
            throw new IOException("chat down frame truncated: expected "
                    + DOWN_FRAME_BYTES + " bytes, got " + frame.length);
        }
        return decodeDown(frame);
    }

    /** Decode + validate one full {@code CHTD} frame (fail-closed; verifier + read path). */
    public static ChatDown decodeDown(byte[] frame) throws IOException {
        Objects.requireNonNull(frame, "frame");
        if (frame.length != DOWN_FRAME_BYTES) {
            throw new IOException("chat down frame must be " + DOWN_FRAME_BYTES
                    + " bytes, got " + frame.length);
        }
        CRC32 crc = new CRC32();
        crc.update(frame, 0, HEADER_BYTES + DOWN_PAYLOAD_BYTES);
        int expectedCrc = (int) crc.getValue();
        int off = 0;
        int magic = getInt(frame, off); off += 4;
        if (magic != DOWN_MAGIC) {
            throw new IOException("chat down bad magic: 0x" + Integer.toHexString(magic));
        }
        int typeCode = getInt(frame, off); off += 4;
        if (typeCode != DOWN_TYPE_CODE) {
            throw new IOException("chat down wire type code mismatch: expected "
                    + DOWN_TYPE_CODE + ", got " + typeCode);
        }
        int schema = getUnsignedShort(frame, off); off += 2;
        if (schema != DOWN_SCHEMA_VERSION) {
            throw new IOException("chat down unsupported schema version: " + schema);
        }
        int seatId = getInt(frame, off); off += 4;
        if (seatId < 0) {
            throw new IOException("chat down seatId out of range: " + seatId);
        }
        String name = readStampedName(frame, off);
        off += 1 + SeatNameFrameCodec.NAME_BUFFER_BYTES;
        String text = readTextBuffer(frame, off, "chat down");
        off += Short.BYTES + TEXT_BUFFER_BYTES;
        int actualCrc = getInt(frame, off);
        if (actualCrc != expectedCrc) {
            throw new IOException("chat down frame CRC mismatch");
        }
        return new ChatDown(seatId, name, text);
    }

    // ---- buffer halves ----

    /** The validated UTF-8 bytes of a law-conforming chat line (never empty). */
    private static byte[] validatedTextBytes(String text) {
        validateChatText(text);
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** Write textLen(u16) + the zero-padded buffer; returns the offset past the buffer. */
    private static int putTextBuffer(byte[] buf, int off, byte[] textBytes) {
        off = putShort(buf, off, (short) textBytes.length);
        System.arraycopy(textBytes, 0, buf, off, textBytes.length);
        // bytes beyond textLen stay zero (fresh array) — the fail-closed padding contract.
        return off + TEXT_BUFFER_BYTES;
    }

    /** Read + validate one textLen(u16) + buffer at {@code off}; no empty sentinel. */
    private static String readTextBuffer(byte[] frame, int off, String what) throws IOException {
        int len = getUnsignedShort(frame, off);
        if (len < 1 || len > TEXT_BUFFER_BYTES) {
            throw new IOException(what + " textLen out of range [1," + TEXT_BUFFER_BYTES
                    + "]: " + len);
        }
        int textStart = off + Short.BYTES;
        for (int i = textStart + len; i < textStart + TEXT_BUFFER_BYTES; i++) {
            if (frame[i] != 0) {
                throw new IOException(what + " nonzero padding beyond textLen=" + len
                        + " at buffer index " + (i - textStart));
            }
        }
        String value = new String(frame, textStart, len, StandardCharsets.UTF_8);
        // Malformed UTF-8 decodes with replacement chars and would not re-encode byte-identical:
        // fail closed rather than relay a mangled line.
        byte[] reencoded = value.getBytes(StandardCharsets.UTF_8);
        if (reencoded.length != len
                || !Arrays.equals(frame, textStart, textStart + len, reencoded, 0, len)) {
            throw new IOException(what + " text is not valid UTF-8");
        }
        try {
            validateChatText(value);
        } catch (IllegalArgumentException bad) {
            throw new IOException(what + " text invalid: " + bad.getMessage(), bad);
        }
        return value;
    }

    /** The server-stamped display name: seat-name law, empty REJECTED (no sentinel here). */
    private static byte[] validatedStampedNameBytes(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException(
                    "chat down name must be non-empty (the server always stamps a fallback)");
        }
        SeatNameFrameCodec.validateSeatName(name);
        return name.getBytes(StandardCharsets.UTF_8);
    }

    /** Read + validate the stamped name buffer at {@code off} (nameLen 0 fails closed). */
    private static String readStampedName(byte[] frame, int off) throws IOException {
        int len = frame[off] & 0xFF;
        if (len < 1 || len > SeatNameFrameCodec.NAME_BUFFER_BYTES) {
            throw new IOException("chat down nameLen out of range [1,"
                    + SeatNameFrameCodec.NAME_BUFFER_BYTES + "]: " + len);
        }
        int nameStart = off + 1;
        for (int i = nameStart + len; i < nameStart + SeatNameFrameCodec.NAME_BUFFER_BYTES; i++) {
            if (frame[i] != 0) {
                throw new IOException("chat down nonzero padding beyond nameLen=" + len
                        + " at buffer index " + (i - nameStart));
            }
        }
        String value = new String(frame, nameStart, len, StandardCharsets.UTF_8);
        byte[] reencoded = value.getBytes(StandardCharsets.UTF_8);
        if (reencoded.length != len
                || !Arrays.equals(frame, nameStart, nameStart + len, reencoded, 0, len)) {
            throw new IOException("chat down name is not valid UTF-8");
        }
        try {
            SeatNameFrameCodec.validateSeatName(value);
        } catch (IllegalArgumentException bad) {
            throw new IOException("chat down name invalid: " + bad.getMessage(), bad);
        }
        return value;
    }

}
