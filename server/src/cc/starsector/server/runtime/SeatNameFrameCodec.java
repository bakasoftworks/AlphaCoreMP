package cc.starsector.server.runtime;

import cc.starsector.server.protocol.ClientSeatNameAnnounceMessage;
import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.ServerSeatNameRecordMessage;
import cc.starsector.server.protocol.ServerSeatNamesMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.CRC32;

import static cc.starsector.server.runtime.WireByteOps.getInt;
import static cc.starsector.server.runtime.WireByteOps.getLong;
import static cc.starsector.server.runtime.WireByteOps.getUnsignedShort;
import static cc.starsector.server.runtime.WireByteOps.putInt;
import static cc.starsector.server.runtime.WireByteOps.putLong;
import static cc.starsector.server.runtime.WireByteOps.putShort;

/**
 * Fixed-width wire codec for the seat-name family — the handshake frames
 * (client→server {@code SNAM} announce, {@link ClientSeatNameAnnounceMessage}; server→both
 * {@code SNMS} two-seat name table, {@link ServerSeatNamesMessage}) plus the N-aware
 * per-seat {@code SNMR} record ({@link ServerSeatNameRecordMessage}) the N door fans on
 * announce/admission.
 *
 * <p>The PDCL twin ({@link FireDischargeDeclarationCodec}): magic + registered wire type code +
 * schema header, fixed width, CRC32 over everything before the trailer, fail-closed reads. The
 * client mod does NOT load this class (its static type-code initializers drag
 * {@link NetworkMessageRegistry} into the game JVM); it speaks the same bytes through the
 * hand-mirrored {@code SeatNameWriter} / {@code ClientSeatNamesReader}, and the game-free
 * verifier byte-compares the mirrors against this
 * codec — any drift breaks it.
 *
 * <p><b>Name buffer contract (the first strings on the live wire):</b> each name rides as a
 * 1-byte UTF-8 byte-count prefix + a fixed {@link #NAME_BUFFER_BYTES}-byte zero-padded buffer.
 * {@code nameLen} is {@code 0..48} where {@code 0} = the "no local pick / no override" sentinel
 * (valid frame; the receiver falls back — a name-less client never stalls the handshake); padding
 * beyond {@code nameLen} MUST be zero (fail-closed); {@code nameLen > 0} carries a name under the
 * {@code parseSeatName} contract ({@link #validateSeatName}: trimmed, non-blank,
 * ≤{@link #NAME_MAX_CHARS} chars, single-line printable) that also re-encodes byte-identical
 * (malformed UTF-8 fails closed). The CRC covers the whole zero-padded buffer.
 */
public final class SeatNameFrameCodec {

    /** SNAM magic = ASCII "SNAM" (Seat NAMe announce, up). */
    public static final int ANNOUNCE_MAGIC = 0x534E414D;
    /** SNMS magic = ASCII "SNMS" (Seat NaMeS table, down). */
    public static final int SEAT_NAMES_MAGIC = 0x534E4D53;
    /** SNMR magic = ASCII "SNMR" (Seat NaMe Record, down — the N-aware per-seat fan). */
    public static final int SEAT_NAME_RECORD_MAGIC = 0x534E4D52;

    /** Registered wire type codes these frames carry. */
    public static final int ANNOUNCE_TYPE_CODE =
            NetworkMessageRegistry.authoritativeCombat()
                    .clientSeatNameAnnounceWireEntry().wireTypeCode();
    public static final int SEAT_NAMES_TYPE_CODE =
            NetworkMessageRegistry.authoritativeCombat()
                    .serverSeatNamesWireEntry().wireTypeCode();
    public static final int SEAT_NAME_RECORD_TYPE_CODE =
            NetworkMessageRegistry.authoritativeCombat()
                    .serverSeatNameRecordWireEntry().wireTypeCode();

    public static final short ANNOUNCE_SCHEMA_VERSION =
            (short) ClientSeatNameAnnounceMessage.SCHEMA_VERSION;
    public static final short SEAT_NAMES_SCHEMA_VERSION =
            (short) ServerSeatNamesMessage.SCHEMA_VERSION;
    public static final short SEAT_NAME_RECORD_SCHEMA_VERSION =
            (short) ServerSeatNameRecordMessage.SCHEMA_VERSION;

    /** magic(int) + typeCode(int) + schema(short). */
    public static final int HEADER_BYTES = Integer.BYTES + Integer.BYTES + Short.BYTES; // 10
    /** Fixed zero-padded UTF-8 buffer per name (≤24 chars can need up to 48 UTF-8 bytes). */
    public static final int NAME_BUFFER_BYTES = 48;
    /** The parseSeatName contract cap, in chars. */
    public static final int NAME_MAX_CHARS = 24;
    /** shipId(long) + nameLen(1B) + name buffer. */
    public static final int ANNOUNCE_PAYLOAD_BYTES = Long.BYTES + 1 + NAME_BUFFER_BYTES; // 57
    public static final int ANNOUNCE_FRAME_BYTES =
            HEADER_BYTES + ANNOUNCE_PAYLOAD_BYTES + Integer.BYTES; // 71
    /** 2 × (nameLen(1B) + name buffer), seat 1 (shipId 1) first. */
    public static final int SEAT_NAMES_PAYLOAD_BYTES = 2 * (1 + NAME_BUFFER_BYTES); // 98
    public static final int SEAT_NAMES_FRAME_BYTES =
            HEADER_BYTES + SEAT_NAMES_PAYLOAD_BYTES + Integer.BYTES; // 112
    /** seatId(u16) + shipId(long) + nameLen(1B) + name buffer. */
    public static final int SEAT_NAME_RECORD_PAYLOAD_BYTES =
            Short.BYTES + Long.BYTES + 1 + NAME_BUFFER_BYTES; // 59
    public static final int SEAT_NAME_RECORD_FRAME_BYTES =
            HEADER_BYTES + SEAT_NAME_RECORD_PAYLOAD_BYTES + Integer.BYTES; // 73

    private static final byte[] NO_NAME = new byte[0];

    /**
     * One decoded {@code SNAM} announce. {@code name} is {@code ""} for the no-pick sentinel
     * (nameLen 0); {@code shipId} is a cross-check only — the transport IS the seat.
     */
    public record SeatNameAnnounce(long shipId, String name) {
        public SeatNameAnnounce {
            if (shipId < 0L) {
                throw new IllegalArgumentException("shipId must be >= 0; got " + shipId);
            }
            Objects.requireNonNull(name, "name");
        }

        /** True when the client actually picked a name (nameLen &gt; 0). */
        public boolean hasName() {
            return !name.isEmpty();
        }
    }

    /** One decoded {@code SNMS} name table (either name may be the {@code ""} no-override sentinel). */
    public record SeatNames(String seat1Name, String seat2Name) {
        public SeatNames {
            Objects.requireNonNull(seat1Name, "seat1Name");
            Objects.requireNonNull(seat2Name, "seat2Name");
        }
    }

    /**
     * One decoded {@code SNMR} per-seat name record: the seat's resolved display
     * name as the N door knows it. {@code name} may be the {@code ""} no-override sentinel
     * (the fan carries clears; a client treats {@code ""} as a no-op).
     */
    public record SeatNameRecord(int seatId, long shipId, String name) {
        public SeatNameRecord {
            if (seatId < 1 || seatId > 0xFFFF) {
                throw new IllegalArgumentException("seatId must be 1..65535; got " + seatId);
            }
            if (shipId < 0L) {
                throw new IllegalArgumentException("shipId must be >= 0; got " + shipId);
            }
            Objects.requireNonNull(name, "name");
        }

        /** True when the record carries a name (nameLen &gt; 0, not the {@code ""} sentinel). */
        public boolean hasName() {
            return !name.isEmpty();
        }
    }

    private SeatNameFrameCodec() {}

    /**
     * The {@code parseSeatName} contract, wire-hardened: trimmed, non-blank,
     * ≤{@link #NAME_MAX_CHARS} chars, single-line printable (the shared
     * {@link WireTextLaw#isPrintableWireChar} law — no C0/DEL, no C1/NEL/LS/PS/bidi control),
     * and ≤{@link #NAME_BUFFER_BYTES} UTF-8 bytes. Throws
     * {@link IllegalArgumentException}; the no-pick sentinel is the EMPTY string and is validated
     * by the callers, not here.
     */
    public static void validateSeatName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty() || !name.equals(name.trim())) {
            throw new IllegalArgumentException(
                    "seat name must be trimmed and non-blank; got " + name.length() + " chars");
        }
        if (name.length() > NAME_MAX_CHARS) {
            throw new IllegalArgumentException(
                    "seat name must be <= " + NAME_MAX_CHARS + " chars, got " + name.length());
        }
        int bad = WireTextLaw.firstUnprintableIndex(name);
        if (bad >= 0) {
            throw new IllegalArgumentException(
                    "seat name must be single-line printable (control char at index " + bad + ")");
        }
        if (name.getBytes(StandardCharsets.UTF_8).length > NAME_BUFFER_BYTES) {
            throw new IllegalArgumentException(
                    "seat name must encode to <= " + NAME_BUFFER_BYTES + " UTF-8 bytes");
        }
    }

    /**
     * The two SERVER-AUTHORED display-label shapes a joiner may not
     * announce — the literal {@code server} stamp every system chat line / join toast / admin
     * reply carries ({@code fanServerChat}) and the {@code seat-N} fallback minted for a nameless
     * seat — because a {@code CHTD} line is {@code seatId + stamped name + text} and a player so
     * named fans chat indistinguishable from the server's own (presentation only: no verb trusts
     * the label). Folded before the compare ({@link #foldForReservedCompare};
     * an earlier ASCII-only fold let a Cyrillic-dze {@code server}, a fullwidth one, a
     * zero-width-space-split one and a trailing-NBSP one fan as the authority): NFKC, then every
     * {@link Character#FORMAT} char dropped, then a Cyrillic/Greek confusable map, then ASCII lower,
     * then Unicode whitespace trimmed at both ends. Over-inclusion is harmless — a name is reserved
     * only when the WHOLE fold equals a server-authored shape. Pure predicate, no wire shape.
     * Enforced ONLY on the joiner-inbound {@link #decodeAnnounce} path — never in
     * {@link #validateSeatName}, which the server's own {@code "server"} stamp is validated through
     * ({@code ChatFrameCodec.validatedStampedNameBytes}).
     */
    public static boolean isReservedSeatName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String folded = foldForReservedCompare(name);
        if (folded.equals("server")) {
            return true;
        }
        if (folded.length() > 5 && folded.startsWith("seat-")) {
            for (int i = 5; i < folded.length(); i++) {
                char c = folded.charAt(i);
                if (c < '0' || c > '9') {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /** ASCII-only case fold (a Unicode {@code toLowerCase} could widen a 24-char name past the cap). */
    private static String asciiLower(String s) {
        char[] cs = s.toCharArray();
        for (int i = 0; i < cs.length; i++) {
            char c = cs[i];
            if (c >= 'A' && c <= 'Z') {
                cs[i] = (char) (c + ('a' - 'A'));
            }
        }
        return new String(cs);
    }

    /**
     * The reserved-compare fold. NFKC (fullwidth / math
     * alphanumerics / ligatures / the Unicode spaces U+00A0, U+2000–U+200A, U+3000 → their ASCII
     * shapes) → every {@link Character#FORMAT} char dropped (ZWSP/ZWNJ/ZWJ, WORD JOINER, BOM, SOFT
     * HYPHEN — all admitted by {@link WireTextLaw}) → {@link #confusableToAscii} → ASCII lower →
     * Unicode whitespace trimmed at both ends ({@code String.trim} stops at U+0020). Compare-only:
     * the result is never stored or sent, so the widening {@link #asciiLower} guards against is moot.
     */
    static String foldForReservedCompare(String name) {
        String nfkc = Normalizer.normalize(name, Normalizer.Form.NFKC);
        StringBuilder sb = new StringBuilder(nfkc.length());
        // By CODE POINT, not UTF-16 unit: the astral variation selectors (U+E0100..) are marks
        // that a per-char getType reads as SURROGATE (a verification pass found combining marks were
        // the residual hole -- CGJ U+034F / VS-16 U+FE0F / the Hangul fillers render nothing).
        int i = 0;
        while (i < nfkc.length()) {
            int cp = nfkc.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (type == Character.FORMAT
                    || type == Character.NON_SPACING_MARK
                    || type == Character.ENCLOSING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || cp == 0x115F || cp == 0x1160) {   // Hangul fillers (Lo, blank glyph; U+3164 / U+FFA0 NFKC to U+1160)
                continue;
            }
            if (cp <= 0xFFFF) {
                sb.append(confusableToAscii((char) cp));
            } else {
                sb.appendCodePoint(cp);
            }
        }
        int start = 0;
        int end = sb.length();
        while (start < end && isUnicodeSpace(sb.charAt(start))) {
            start++;
        }
        while (end > start && isUnicodeSpace(sb.charAt(end - 1))) {
            end--;
        }
        return asciiLower(sb.substring(start, end));
    }

    private static boolean isUnicodeSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    /**
     * Cyrillic / Greek homoglyphs of the letters in {@code server} / {@code seat} (both cases) and
     * the dash shapes a {@code seat-N} spoof can carry; every other char maps to itself. NFKC has
     * already folded the fullwidth / math forms, so this is the residual set — plus the Latin
     * letter small capitals S / E / R / V (U+A731, U+1D07, U+0280, U+1D20), which carry NO NFKC
     * decomposition, and A / T (U+1D00, U+1D1B)
     * so an all-small-caps {@code seat-N} folds too.
     */
    private static char confusableToAscii(char c) {
        switch (c) {
            case 0x0455: case 0x0405:                               // Cyrillic dze
            case 0xA731:                                            // small capital S //
                return 's';
            case 0x0435: case 0x0415: case 0x0395:                  // Cyrillic ie, Greek capital Epsilon
            case 0x1D07:                                            // small capital E //
                return 'e';
            case 0x0433:                                            // Cyrillic ghe (r-shaped in sans fonts)
            case 0x0280:                                            // small capital R //
                return 'r';
            case 0x03BD: case 0x0475: case 0x0474:                  // Greek nu, Cyrillic izhitsa
            case 0x1D20:                                            // small capital V //
                return 'v';
            case 0x0430: case 0x0410: case 0x03B1: case 0x0391:     // Cyrillic a, Greek alpha
            case 0x1D00:                                            // LATIN LETTER SMALL CAPITAL A //
                return 'a';
            case 0x0442: case 0x0422: case 0x03C4: case 0x03A4:     // Cyrillic te, Greek tau
            case 0x1D1B:                                            // LATIN LETTER SMALL CAPITAL T (same rider hunk)
                return 't';
            case 0x2010: case 0x2011: case 0x2012: case 0x2013:     // hyphen, nb-hyphen, figure dash, en dash
            case 0x2014: case 0x2015: case 0x2212: case 0x02D7:     // em dash, horizontal bar, minus, modifier minus
                return '-';
            default:
                return c;
        }
    }

    /** Encode one {@code SNAM} announce ({@code name} null/empty = the no-pick sentinel, nameLen 0). */
    public static byte[] encodeAnnounce(long shipId, String name) {
        if (shipId < 0L) {
            throw new IllegalArgumentException("shipId must be >= 0; got " + shipId);
        }
        byte[] nameBytes = validatedNameBytes(name);
        byte[] buf = new byte[ANNOUNCE_FRAME_BYTES];
        int off = 0;
        off = putInt(buf, off, ANNOUNCE_MAGIC);
        off = putInt(buf, off, ANNOUNCE_TYPE_CODE);
        off = putShort(buf, off, ANNOUNCE_SCHEMA_VERSION);
        off = putLong(buf, off, shipId);
        off = putNameBuffer(buf, off, nameBytes);
        // off now == HEADER_BYTES + ANNOUNCE_PAYLOAD_BYTES; CRC32 covers exactly that body.
        CRC32 crc = new CRC32();
        crc.update(buf, 0, off);
        putInt(buf, off, (int) crc.getValue());
        return buf;
    }

    /** Serialize one {@code SNAM} to {@code out} as exactly {@link #ANNOUNCE_FRAME_BYTES} bytes. */
    public static void writeAnnounce(OutputStream out, long shipId, String name) throws IOException {
        Objects.requireNonNull(out, "out");
        byte[] bytes = encodeAnnounce(shipId, name);
        out.write(bytes, 0, bytes.length);
    }

    /**
     * Read exactly one {@code SNAM} from {@code in}, blocking until {@link #ANNOUNCE_FRAME_BYTES}
     * arrive — the server's bounded pre-loop handshake read (the caller bounds it via SO_TIMEOUT,
     * {@code PilotIntentSocketServer.awaitSeatName}). Returns {@code null} at a clean end of
     * stream (zero bytes); throws fail-closed on truncation, bad magic/type/schema, nonzero
     * padding, an invalid name, or a CRC mismatch.
     */
    public static SeatNameAnnounce readAnnounce(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        byte[] frame = in.readNBytes(ANNOUNCE_FRAME_BYTES);
        if (frame.length == 0) {
            return null; // clean end of stream
        }
        if (frame.length != ANNOUNCE_FRAME_BYTES) {
            throw new IOException("seat name announce frame truncated: expected "
                    + ANNOUNCE_FRAME_BYTES + " bytes, got " + frame.length);
        }
        return decodeAnnounce(frame);
    }

    /** Decode + validate one full {@code SNAM} frame (fail-closed; verifier + read path). */
    public static SeatNameAnnounce decodeAnnounce(byte[] frame) throws IOException {
        Objects.requireNonNull(frame, "frame");
        if (frame.length != ANNOUNCE_FRAME_BYTES) {
            throw new IOException("seat name announce frame must be " + ANNOUNCE_FRAME_BYTES
                    + " bytes, got " + frame.length);
        }
        CRC32 crc = new CRC32();
        crc.update(frame, 0, HEADER_BYTES + ANNOUNCE_PAYLOAD_BYTES);
        int expectedCrc = (int) crc.getValue();
        int off = 0;
        int magic = getInt(frame, off); off += 4;
        if (magic != ANNOUNCE_MAGIC) {
            throw new IOException("seat name announce bad magic: 0x" + Integer.toHexString(magic));
        }
        int typeCode = getInt(frame, off); off += 4;
        if (typeCode != ANNOUNCE_TYPE_CODE) {
            throw new IOException("seat name announce wire type code mismatch: expected "
                    + ANNOUNCE_TYPE_CODE + ", got " + typeCode);
        }
        int schema = getUnsignedShort(frame, off); off += 2;
        if (schema != ANNOUNCE_SCHEMA_VERSION) {
            throw new IOException("seat name announce unsupported schema version: " + schema);
        }
        long shipId = getLong(frame, off); off += 8;
        String name = readNameBuffer(frame, off, "seat name announce");
        off += 1 + NAME_BUFFER_BYTES;
        int actualCrc = getInt(frame, off);
        if (actualCrc != expectedCrc) {
            throw new IOException("seat name announce frame CRC mismatch");
        }
        if (isReservedSeatName(name)) {
            // Joiner-inbound only (the server never encodes SNAM): a reserved label is a codec
            // protocol violation like any malformed announce — the datagram door's readAnnounce
            // IOException kills the session, the socket door's handshake fails. No new reject
            // token (post-admission, the session-END family; the existing token parity untouched).
            throw new IOException("seat name announce name reserved: '" + name + "'");
        }
        try {
            return new SeatNameAnnounce(shipId, name);
        } catch (IllegalArgumentException badFields) {
            throw new IOException(
                    "seat name announce fields invalid: " + badFields.getMessage(), badFields);
        }
    }

    /** Encode one {@code SNMS} name table (either name null/empty = the no-override sentinel). */
    public static byte[] encodeSeatNames(String seat1Name, String seat2Name) {
        byte[] n1 = validatedNameBytes(seat1Name);
        byte[] n2 = validatedNameBytes(seat2Name);
        byte[] buf = new byte[SEAT_NAMES_FRAME_BYTES];
        int off = 0;
        off = putInt(buf, off, SEAT_NAMES_MAGIC);
        off = putInt(buf, off, SEAT_NAMES_TYPE_CODE);
        off = putShort(buf, off, SEAT_NAMES_SCHEMA_VERSION);
        off = putNameBuffer(buf, off, n1);
        off = putNameBuffer(buf, off, n2);
        // off now == HEADER_BYTES + SEAT_NAMES_PAYLOAD_BYTES; CRC32 covers exactly that body.
        CRC32 crc = new CRC32();
        crc.update(buf, 0, off);
        putInt(buf, off, (int) crc.getValue());
        return buf;
    }

    /** Serialize one {@code SNMS} to {@code out} as exactly {@link #SEAT_NAMES_FRAME_BYTES} bytes. */
    public static void writeSeatNames(OutputStream out, String seat1Name, String seat2Name)
            throws IOException {
        Objects.requireNonNull(out, "out");
        byte[] bytes = encodeSeatNames(seat1Name, seat2Name);
        out.write(bytes, 0, bytes.length);
    }

    /**
     * Read exactly one {@code SNMS} from {@code in} (game-free tests / round-trip verifier; the
     * live client reads it via the mirrored {@code ClientSeatNamesReader} off its demux). Returns
     * {@code null} at a clean end of stream; fail-closed otherwise.
     */
    public static SeatNames readSeatNames(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        byte[] frame = in.readNBytes(SEAT_NAMES_FRAME_BYTES);
        if (frame.length == 0) {
            return null; // clean end of stream
        }
        if (frame.length != SEAT_NAMES_FRAME_BYTES) {
            throw new IOException("seat names frame truncated: expected "
                    + SEAT_NAMES_FRAME_BYTES + " bytes, got " + frame.length);
        }
        return decodeSeatNames(frame);
    }

    /** Decode + validate one full {@code SNMS} frame (fail-closed; verifier + read path). */
    public static SeatNames decodeSeatNames(byte[] frame) throws IOException {
        Objects.requireNonNull(frame, "frame");
        if (frame.length != SEAT_NAMES_FRAME_BYTES) {
            throw new IOException("seat names frame must be " + SEAT_NAMES_FRAME_BYTES
                    + " bytes, got " + frame.length);
        }
        CRC32 crc = new CRC32();
        crc.update(frame, 0, HEADER_BYTES + SEAT_NAMES_PAYLOAD_BYTES);
        int expectedCrc = (int) crc.getValue();
        int off = 0;
        int magic = getInt(frame, off); off += 4;
        if (magic != SEAT_NAMES_MAGIC) {
            throw new IOException("seat names bad magic: 0x" + Integer.toHexString(magic));
        }
        int typeCode = getInt(frame, off); off += 4;
        if (typeCode != SEAT_NAMES_TYPE_CODE) {
            throw new IOException("seat names wire type code mismatch: expected "
                    + SEAT_NAMES_TYPE_CODE + ", got " + typeCode);
        }
        int schema = getUnsignedShort(frame, off); off += 2;
        if (schema != SEAT_NAMES_SCHEMA_VERSION) {
            throw new IOException("seat names unsupported schema version: " + schema);
        }
        String seat1 = readNameBuffer(frame, off, "seat names [seat1]");
        off += 1 + NAME_BUFFER_BYTES;
        String seat2 = readNameBuffer(frame, off, "seat names [seat2]");
        off += 1 + NAME_BUFFER_BYTES;
        int actualCrc = getInt(frame, off);
        if (actualCrc != expectedCrc) {
            throw new IOException("seat names frame CRC mismatch");
        }
        return new SeatNames(seat1, seat2);
    }

    /** Encode one {@code SNMR} record ({@code name} null/empty = the no-override sentinel). */
    public static byte[] encodeSeatNameRecord(int seatId, long shipId, String name) {
        if (seatId < 1 || seatId > 0xFFFF) {
            throw new IllegalArgumentException("seatId must be 1..65535; got " + seatId);
        }
        if (shipId < 0L) {
            throw new IllegalArgumentException("shipId must be >= 0; got " + shipId);
        }
        byte[] nameBytes = validatedNameBytes(name);
        byte[] buf = new byte[SEAT_NAME_RECORD_FRAME_BYTES];
        int off = 0;
        off = putInt(buf, off, SEAT_NAME_RECORD_MAGIC);
        off = putInt(buf, off, SEAT_NAME_RECORD_TYPE_CODE);
        off = putShort(buf, off, SEAT_NAME_RECORD_SCHEMA_VERSION);
        off = putShort(buf, off, (short) seatId);
        off = putLong(buf, off, shipId);
        off = putNameBuffer(buf, off, nameBytes);
        // off now == HEADER_BYTES + SEAT_NAME_RECORD_PAYLOAD_BYTES; CRC32 covers exactly that body.
        CRC32 crc = new CRC32();
        crc.update(buf, 0, off);
        putInt(buf, off, (int) crc.getValue());
        return buf;
    }

    /**
     * Read exactly one {@code SNMR} from {@code in} (game-free tests / round-trip verifier; the
     * live client reads it via the mirrored {@code ClientSeatNameRecordReader} off its demux).
     * Returns {@code null} at a clean end of stream; fail-closed otherwise.
     */
    public static SeatNameRecord readSeatNameRecord(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        byte[] frame = in.readNBytes(SEAT_NAME_RECORD_FRAME_BYTES);
        if (frame.length == 0) {
            return null; // clean end of stream
        }
        if (frame.length != SEAT_NAME_RECORD_FRAME_BYTES) {
            throw new IOException("seat name record frame truncated: expected "
                    + SEAT_NAME_RECORD_FRAME_BYTES + " bytes, got " + frame.length);
        }
        return decodeSeatNameRecord(frame);
    }

    /** Decode + validate one full {@code SNMR} frame (fail-closed; verifier + read path). */
    public static SeatNameRecord decodeSeatNameRecord(byte[] frame) throws IOException {
        Objects.requireNonNull(frame, "frame");
        if (frame.length != SEAT_NAME_RECORD_FRAME_BYTES) {
            throw new IOException("seat name record frame must be " + SEAT_NAME_RECORD_FRAME_BYTES
                    + " bytes, got " + frame.length);
        }
        CRC32 crc = new CRC32();
        crc.update(frame, 0, HEADER_BYTES + SEAT_NAME_RECORD_PAYLOAD_BYTES);
        int expectedCrc = (int) crc.getValue();
        int off = 0;
        int magic = getInt(frame, off); off += 4;
        if (magic != SEAT_NAME_RECORD_MAGIC) {
            throw new IOException("seat name record bad magic: 0x" + Integer.toHexString(magic));
        }
        int typeCode = getInt(frame, off); off += 4;
        if (typeCode != SEAT_NAME_RECORD_TYPE_CODE) {
            throw new IOException("seat name record wire type code mismatch: expected "
                    + SEAT_NAME_RECORD_TYPE_CODE + ", got " + typeCode);
        }
        int schema = getUnsignedShort(frame, off); off += 2;
        if (schema != SEAT_NAME_RECORD_SCHEMA_VERSION) {
            throw new IOException("seat name record unsupported schema version: " + schema);
        }
        int seatId = getUnsignedShort(frame, off); off += 2;
        long shipId = getLong(frame, off); off += 8;
        String name = readNameBuffer(frame, off, "seat name record");
        off += 1 + NAME_BUFFER_BYTES;
        int actualCrc = getInt(frame, off);
        if (actualCrc != expectedCrc) {
            throw new IOException("seat name record frame CRC mismatch");
        }
        try {
            return new SeatNameRecord(seatId, shipId, name);
        } catch (IllegalArgumentException badFields) {
            throw new IOException(
                    "seat name record fields invalid: " + badFields.getMessage(), badFields);
        }
    }

    // ---- name buffer halves (shared by all three frames) ----

    /** Null/empty = the sentinel (nameLen 0); else the validated UTF-8 bytes (≤48). */
    private static byte[] validatedNameBytes(String name) {
        if (name == null || name.isEmpty()) {
            return NO_NAME;
        }
        validateSeatName(name);
        return name.getBytes(StandardCharsets.UTF_8);
    }

    /** Write nameLen + the zero-padded buffer; returns the offset past the buffer. */
    private static int putNameBuffer(byte[] buf, int off, byte[] nameBytes) {
        buf[off] = (byte) nameBytes.length;
        System.arraycopy(nameBytes, 0, buf, off + 1, nameBytes.length);
        // bytes beyond nameLen stay zero (fresh array) — the fail-closed padding contract.
        return off + 1 + NAME_BUFFER_BYTES;
    }

    /** Read + validate one nameLen + buffer at {@code off}; returns "" for the len-0 sentinel. */
    private static String readNameBuffer(byte[] frame, int off, String what) throws IOException {
        int len = frame[off] & 0xFF;
        if (len > NAME_BUFFER_BYTES) {
            throw new IOException(what + " nameLen out of range: " + len);
        }
        int nameStart = off + 1;
        for (int i = nameStart + len; i < nameStart + NAME_BUFFER_BYTES; i++) {
            if (frame[i] != 0) {
                throw new IOException(what + " nonzero padding beyond nameLen=" + len
                        + " at buffer index " + (i - nameStart));
            }
        }
        if (len == 0) {
            return ""; // the no-pick / no-override sentinel
        }
        String value = new String(frame, nameStart, len, StandardCharsets.UTF_8);
        // Malformed UTF-8 decodes with replacement chars and would not re-encode byte-identical:
        // fail closed rather than serve a mangled name.
        byte[] reencoded = value.getBytes(StandardCharsets.UTF_8);
        if (reencoded.length != len
                || !Arrays.equals(frame, nameStart, nameStart + len, reencoded, 0, len)) {
            throw new IOException(what + " name is not valid UTF-8");
        }
        try {
            validateSeatName(value);
        } catch (IllegalArgumentException bad) {
            throw new IOException(what + " name invalid: " + bad.getMessage(), bad);
        }
        return value;
    }

}
