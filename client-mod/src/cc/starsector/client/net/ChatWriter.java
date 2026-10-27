package cc.starsector.client.net;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import cc.starsector.server.runtime.WireTextLaw;

/**
 * Client-side encoder for the in-match text chat's {@code CHTU} send frame — the
 * sending half of the server's {@code ChatFrameCodec} ({@code client.chat.text} v1): the frame a
 * predict client sends on its reliable CTRL lane when its human submits a chat line. The server
 * decodes it fail-closed, stamps the sender's seat + display name, and relays one {@code CHTD}
 * to every live session INCLUDING the sender (echo = delivery confirmation;
 * {@link ChatReader} is the receiving half).
 *
 * <p>This mirrors the server codec's exact layout so the mod speaks the registered message
 * without bundling {@code ChatFrameCodec} — whose static type-code initializers drag
 * {@code NetworkMessageRegistry} + the whole message closure into the game JVM (the
 * {@link SeatNameWriter} discipline). The wire type code is derived the same way the server
 * derives it — CRC32 of the message id via {@link FireDischargeDeclarationWriter#wireTypeCode} —
 * never a copied constant. The game-free verifier
 * asserts these constants equal {@code ChatFrameCodec}'s and byte-compares both encoders; any
 * drift breaks it.
 *
 * <p><b>Text buffer contract:</b> the chat line rides as a 2-byte UTF-8 byte-count prefix + a
 * fixed {@value #TEXT_BUFFER_BYTES}-byte zero-padded buffer. Unlike the seat-name buffers there
 * is NO length-0 sentinel — a chat frame without text is meaningless, so {@code textLen} is
 * {@code 1..240} and blank text fails at {@link #validateChatText} (the seat-name law
 * widened to chat length). The CRC32 trailer covers the whole zero-padded buffer.
 *
 * <p>Allowlist-clean: fixed {@code byte[]} via {@link WireBytes} + the allowlisted
 * {@link CRC32} — no {@code Data*Stream}, so it serializes under the in-game script
 * classloader {@code B}.
 */
public final class ChatWriter {

    /** Frame magic: ASCII "CHTU". Mirrors {@code ChatFrameCodec.UP_MAGIC}. */
    public static final int MAGIC = 0x43485455;

    /** Registry message id this frame carries. Mirrors {@code ClientChatTextMessage.MESSAGE_ID}. */
    public static final String MESSAGE_ID = "client.chat.text";

    /** Wire type code = CRC32(MESSAGE_ID), mirroring {@code ProtocolRegistryEntry.wireTypeCode}. */
    public static final int MESSAGE_TYPE_CODE = FireDischargeDeclarationWriter.wireTypeCode(MESSAGE_ID);

    /** Registered schema version. Mirrors {@code ClientChatTextMessage.SCHEMA_VERSION}. */
    public static final short SCHEMA_VERSION = 1;

    /** magic(int) + typeCode(int) + schema(short). */
    public static final int HEADER_BYTES = Integer.BYTES + Integer.BYTES + Short.BYTES; // 10
    /** Fixed zero-padded UTF-8 buffer (≤120 chars can need up to 240 UTF-8 bytes). Mirrors the codec. */
    public static final int TEXT_BUFFER_BYTES = 240;
    /** The chat-line char cap (the validateChatText contract). Mirrors {@code ChatFrameCodec.TEXT_MAX_CHARS}. */
    public static final int TEXT_MAX_CHARS = 120;
    /** textLen(u16) + text buffer. */
    public static final int PAYLOAD_BYTES = Short.BYTES + TEXT_BUFFER_BYTES; // 242
    /** Fixed frame width: header + payload + CRC32 trailer. Mirrors {@code ChatFrameCodec.UP_FRAME_BYTES}. */
    public static final int FRAME_BYTES = HEADER_BYTES + PAYLOAD_BYTES + Integer.BYTES; // 256

    private ChatWriter() {
    }

    /**
     * The chat text law — byte-identical to {@code ChatFrameCodec.validateChatText} (the
     * seat-name contract widened to chat length): trimmed, non-blank,
     * ≤{@value #TEXT_MAX_CHARS} chars, single-line printable per the ONE shared wire law
     * ({@link WireTextLaw#isPrintableWireChar}: C0/DEL plus the C1/NEL/LS/PS/bidi smuggle set
     * rejected — the client's mirror of the server check; interior spaces and non-ASCII printables OK),
     * ≤{@value #TEXT_BUFFER_BYTES}
     * UTF-8 bytes. Throws {@link IllegalArgumentException}. There is no empty sentinel — blank
     * text is invalid everywhere.
     */
    public static void validateChatText(String text) {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        if (text.isEmpty() || !text.equals(text.trim())) {
            throw new IllegalArgumentException(
                    "chat text must be trimmed and non-blank (" + text.length() + " chars)");
        }
        if (text.length() > TEXT_MAX_CHARS) {
            throw new IllegalArgumentException(
                    "chat text must be <= " + TEXT_MAX_CHARS + " chars, got " + text.length());
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!WireTextLaw.isPrintableWireChar(c)) {
                throw new IllegalArgumentException(
                        "chat text must be single-line printable (control char at index " + i + ")");
            }
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > TEXT_BUFFER_BYTES) {
            throw new IllegalArgumentException(
                    "chat text must encode to <= " + TEXT_BUFFER_BYTES + " UTF-8 bytes");
        }
    }

    /**
     * Encode one chat send as the fixed {@link #FRAME_BYTES}-byte {@code CHTU} frame,
     * byte-identical to {@code ChatFrameCodec.encodeUp}. {@code text} must satisfy
     * {@link #validateChatText} so the client can never emit a frame the server's fail-closed
     * decode would kill the session over (the leg-4 input law enforces the caps pre-send; this
     * validation is the backstop).
     */
    public static byte[] encodeFrame(String text) {
        validateChatText(text);
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        byte[] b = new byte[FRAME_BYTES];
        int off = WireBytes.putInt(b, 0, MAGIC);
        off = WireBytes.putInt(b, off, MESSAGE_TYPE_CODE);
        off = WireBytes.putShort(b, off, SCHEMA_VERSION);
        off = WireBytes.putShort(b, off, (short) textBytes.length);
        System.arraycopy(textBytes, 0, b, off, textBytes.length);
        off += TEXT_BUFFER_BYTES; // bytes beyond textLen stay zero — the fail-closed padding contract
        // off now == HEADER_BYTES + PAYLOAD_BYTES; CRC32 covers exactly that body, like SNAM/PDCL.
        CRC32 crc = new CRC32();
        crc.update(b, 0, off);
        WireBytes.putInt(b, off, (int) crc.getValue());
        return b;
    }
}
