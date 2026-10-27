package cc.starsector.client.net;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import cc.starsector.server.runtime.WireTextLaw;

/**
 * Client-side encoder for the seat-name handshake's {@code SNAM} announce frame —
 * the sending half of the server's {@code SeatNameFrameCodec} ({@code client.seat_name.announce}
 * v1): the ONE frame a predict client sends immediately at connect, BEFORE its first receive,
 * announcing the name its human picked locally ({@code cc.match.seat<N>Name}; the joiner's
 * {@code join-cc.bat} prompt / the host's {@code -Seat1Name} both write that knob). The server's
 * bounded pre-loop {@code awaitSeatName} read consumes it right after accepting the seat and
 * distributes both seats' names back down in one {@code SNMS} table
 * ({@link ClientSeatNamesReader}).
 *
 * <p>This mirrors the server codec's exact layout so the mod speaks the registered message
 * without bundling {@code SeatNameFrameCodec} — whose static type-code initializers drag
 * {@code NetworkMessageRegistry} + the whole message closure into the game JVM (the
 * {@link FireDischargeDeclarationWriter} discipline). The wire type code is derived the same way
 * the server derives it — CRC32 of the message id ({@code ProtocolRegistryEntry.wireTypeCode})
 * via {@link FireDischargeDeclarationWriter#wireTypeCode} — never a copied constant. The
 * game-free verifier asserts these constants
 * equal {@code SeatNameFrameCodec}'s and byte-compares both encoders; any drift breaks it.
 *
 * <p><b>Name buffer contract:</b> the name rides as a 1-byte UTF-8 byte-count prefix + a fixed
 * {@value #NAME_BUFFER_BYTES}-byte zero-padded buffer. {@code nameLen} 0 = the "no local pick"
 * sentinel (a valid frame — a name-less client must never stall the server's handshake read);
 * {@code nameLen > 0} carries a name under the {@code parseSeatName} contract
 * ({@link #validateSeatName}). The CRC32 trailer covers the whole zero-padded buffer.
 *
 * <p>Allowlist-clean: fixed {@code byte[]} via {@link WireBytes} + the allowlisted
 * {@link CRC32} — no {@code Data*Stream}, so it serializes under the in-game script
 * classloader {@code B}.
 */
public final class SeatNameWriter {

    /** Frame magic: ASCII "SNAM". Mirrors {@code SeatNameFrameCodec.ANNOUNCE_MAGIC}. */
    public static final int MAGIC = 0x534E414D;

    /** Registry message id this frame carries. Mirrors {@code ClientSeatNameAnnounceMessage.MESSAGE_ID}. */
    public static final String MESSAGE_ID = "client.seat_name.announce";

    /** Wire type code = CRC32(MESSAGE_ID), mirroring {@code ProtocolRegistryEntry.wireTypeCode}. */
    public static final int MESSAGE_TYPE_CODE = FireDischargeDeclarationWriter.wireTypeCode(MESSAGE_ID);

    /** Registered schema version. Mirrors {@code ClientSeatNameAnnounceMessage.SCHEMA_VERSION}. */
    public static final short SCHEMA_VERSION = 1;

    /** magic(int) + typeCode(int) + schema(short). */
    public static final int HEADER_BYTES = Integer.BYTES + Integer.BYTES + Short.BYTES; // 10
    /** Fixed zero-padded UTF-8 buffer (≤24 chars can need up to 48 UTF-8 bytes). Mirrors the codec. */
    public static final int NAME_BUFFER_BYTES = 48;
    /** The parseSeatName contract cap, in chars. Mirrors {@code SeatNameFrameCodec.NAME_MAX_CHARS}. */
    public static final int NAME_MAX_CHARS = 24;
    /** shipId(long) + nameLen(1B) + name buffer. */
    public static final int PAYLOAD_BYTES = Long.BYTES + 1 + NAME_BUFFER_BYTES; // 57
    /** Fixed frame width: header + payload + CRC32 trailer. Mirrors {@code SeatNameFrameCodec.ANNOUNCE_FRAME_BYTES}. */
    public static final int FRAME_BYTES = HEADER_BYTES + PAYLOAD_BYTES + Integer.BYTES; // 71

    private SeatNameWriter() {
    }

    /**
     * The {@code parseSeatName} contract, wire-hardened — byte-identical to
     * {@code SeatNameFrameCodec.validateSeatName}: trimmed, non-blank, ≤{@value #NAME_MAX_CHARS}
     * chars, single-line printable per the ONE shared wire law ({@link WireTextLaw#isPrintableWireChar}
     * — C0/DEL plus the C1/NEL/LS/PS/bidi smuggle set; the
     * bundled server class IS the law, no client copy), ≤{@value #NAME_BUFFER_BYTES} UTF-8 bytes. Throws
     * {@link IllegalArgumentException}; the no-pick sentinel is the null/empty name and is handled
     * by {@link #encodeFrame}, not here.
     */
    public static void validateSeatName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("name must not be null");
        }
        if (name.isEmpty() || !name.equals(name.trim())) {
            throw new IllegalArgumentException(
                    "seat name must be trimmed and non-blank (" + name.length() + " chars)");
        }
        if (name.length() > NAME_MAX_CHARS) {
            throw new IllegalArgumentException(
                    "seat name must be <= " + NAME_MAX_CHARS + " chars, got " + name.length());
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!WireTextLaw.isPrintableWireChar(c)) {
                throw new IllegalArgumentException(
                        "seat name must be single-line printable (control char at index " + i + ")");
            }
        }
        if (name.getBytes(StandardCharsets.UTF_8).length > NAME_BUFFER_BYTES) {
            throw new IllegalArgumentException(
                    "seat name must encode to <= " + NAME_BUFFER_BYTES + " UTF-8 bytes");
        }
    }

    /**
     * Encode one announce as the fixed {@link #FRAME_BYTES}-byte {@code SNAM} frame, byte-identical
     * to {@code SeatNameFrameCodec.encodeAnnounce}. {@code name} null/empty = the no-pick sentinel
     * (nameLen 0); otherwise it must satisfy {@link #validateSeatName} so the client can never emit
     * a frame the server's fail-closed decode would kill the connection over.
     */
    public static byte[] encodeFrame(long shipId, String name) {
        if (shipId < 0L) {
            throw new IllegalArgumentException("shipId must be >= 0; got " + shipId);
        }
        byte[] nameBytes;
        if (name == null || name.isEmpty()) {
            nameBytes = new byte[0]; // the no-pick sentinel: nameLen 0, all-zero buffer
        } else {
            validateSeatName(name);
            nameBytes = name.getBytes(StandardCharsets.UTF_8);
        }
        byte[] b = new byte[FRAME_BYTES];
        int off = WireBytes.putInt(b, 0, MAGIC);
        off = WireBytes.putInt(b, off, MESSAGE_TYPE_CODE);
        off = WireBytes.putShort(b, off, SCHEMA_VERSION);
        off = WireBytes.putLong(b, off, shipId);
        off = WireBytes.putByte(b, off, nameBytes.length);
        System.arraycopy(nameBytes, 0, b, off, nameBytes.length);
        off += NAME_BUFFER_BYTES; // bytes beyond nameLen stay zero — the fail-closed padding contract
        // off now == HEADER_BYTES + PAYLOAD_BYTES; CRC32 covers exactly that body, like PINT/PDCL.
        CRC32 crc = new CRC32();
        crc.update(b, 0, off);
        WireBytes.putInt(b, off, (int) crc.getValue());
        return b;
    }
}
