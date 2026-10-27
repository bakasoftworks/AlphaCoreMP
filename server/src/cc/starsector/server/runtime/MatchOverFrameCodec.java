package cc.starsector.server.runtime;

import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.ServerMatchOverRecordMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.zip.CRC32;

import static cc.starsector.server.runtime.WireByteOps.getInt;
import static cc.starsector.server.runtime.WireByteOps.getLong;
import static cc.starsector.server.runtime.WireByteOps.getUnsignedShort;
import static cc.starsector.server.runtime.WireByteOps.putInt;
import static cc.starsector.server.runtime.WireByteOps.putLong;
import static cc.starsector.server.runtime.WireByteOps.putShort;

/**
 * Fixed-width wire codec for the serve-end match verdict — the server→both
 * {@code MOVR} record ({@link ServerMatchOverRecordMessage}) fanned at most once per serve,
 * before the goodbye BYE, carrying the ELWSS first-latch-wins outcome so clients drive the
 * native end-of-combat presentation from the SERVER's verdict (their local auto-end checker is
 * held out of victory state by the consumer's {@code setDoNotEndCombat} guard).
 *
 * <p>The SNMR/HKFR twin ({@link SeatNameFrameCodec}/{@link HulkFractureFrameCodec}): magic +
 * registered wire type code + schema header, fixed width, CRC32 over everything before the
 * trailer, fail-closed reads. The client mod does NOT load this class (its static type-code
 * initializer drags {@link NetworkMessageRegistry} into the game JVM); it speaks the same
 * bytes through a hand-mirrored reader.
 *
 * <p>Frame layout (24 bytes): header 10 (magic u32, typeCode u32, schema u16) + payload 10
 * ({@code simTick} i64, {@code outcome} u8, {@code winnerSide} u8 — FleetSide ordinal 0/1,
 * 0xFF = no victor) + CRC32 u32 over {@code [0, 20)}. The ELWSS latch tag STRING stays off
 * the wire; {@link #outcomeCodeForTag(String)} is the one server-side map.
 */
public final class MatchOverFrameCodec {

    /** MOVR magic = ASCII "MOVR" (Match OVeR verdict, down). */
    public static final int MATCH_OVER_MAGIC = 0x4D4F5652;

    /** Registered wire type code this frame carries. */
    public static final int MATCH_OVER_TYPE_CODE =
            NetworkMessageRegistry.authoritativeCombat()
                    .serverMatchOverRecordWireEntry().wireTypeCode();

    public static final short MATCH_OVER_SCHEMA_VERSION =
            (short) ServerMatchOverRecordMessage.SCHEMA_VERSION;

    /** Fortress serve: the attacker wave schedule + live hulls hit zero (DEFEND win). */
    public static final int OUTCOME_FORTRESS_CLEARED = 1;
    /** Fortress serve: the defended base hull went hulk (attacker win). */
    public static final int OUTCOME_BASE_HULK = 2;
    /** Stable-roster serve: exactly one fighting side still holds live ships. */
    public static final int OUTCOME_PER_SIDE = 3;
    /** Stable-roster serve: zero live ships remain anywhere (no victor). */
    public static final int OUTCOME_ROSTER_0 = 4;
    /** Non-fortress wave serve: the wave side's schedule + live hulls hit zero. */
    public static final int OUTCOME_WAVE_CLEAR = 5;
    /** Operator end verbs (end-match, or an end-round closing the final round): a stop,
     * not a victory — {@code winnerSide} stays -1 so no client mints a victory banner. */
    public static final int OUTCOME_OPERATOR_END = 6;

    /** magic(int) + typeCode(int) + schema(short). */
    public static final int HEADER_BYTES = Integer.BYTES + Integer.BYTES + Short.BYTES; // 10
    /** simTick(long) + outcome(u8) + winnerSide(u8). */
    public static final int MATCH_OVER_PAYLOAD_BYTES = Long.BYTES + 2; // 10
    public static final int MATCH_OVER_FRAME_BYTES =
            HEADER_BYTES + MATCH_OVER_PAYLOAD_BYTES + Integer.BYTES; // 24

    /** No-victor wire byte for {@code winnerSide} -1 (u8 lane; 0/1 ride verbatim). */
    private static final int WINNER_NONE_WIRE = 0xFF;

    /**
     * The ONE server-side map from the ELWSS {@code logRosterDump} latch tag to the wire
     * outcome code (the tag string never crosses the wire). Unknown tags
     * throw — a new latch site must extend the vocabulary deliberately, never fan garbage.
     */
    public static int outcomeCodeForTag(String tag) {
        switch (Objects.requireNonNull(tag, "tag")) {
            case "match-over-fortress-cleared": return OUTCOME_FORTRESS_CLEARED;
            case "match-over-base-hulk": return OUTCOME_BASE_HULK;
            case "match-over-per-side": return OUTCOME_PER_SIDE;
            case "match-over-roster-0": return OUTCOME_ROSTER_0;
            case "match-over-wave-clear": return OUTCOME_WAVE_CLEAR;
            default:
                throw new IllegalArgumentException("unknown match-over latch tag: " + tag);
        }
    }

    /**
     * One decoded {@code MOVR} verdict. {@code winnerSide} is the engine {@code FleetSide}
     * ordinal (0/1) or -1 = no victor (roster-0, operator end, fail-soft latch) — a client
     * maps "winner != own side" to the defeat presentation, so -1 never shows a victory.
     */
    public record MatchOverRecord(long simTick, int outcome, int winnerSide) {
        public MatchOverRecord {
            if (simTick < 0L) {
                throw new IllegalArgumentException("simTick must be >= 0; got " + simTick);
            }
            if (outcome < OUTCOME_FORTRESS_CLEARED || outcome > OUTCOME_OPERATOR_END) {
                throw new IllegalArgumentException(
                        "outcome must be 1..6 (the match-over outcome vocabulary); got " + outcome);
            }
            if (winnerSide != -1 && winnerSide != 0 && winnerSide != 1) {
                throw new IllegalArgumentException(
                        "winnerSide must be -1 (none), 0 or 1; got " + winnerSide);
            }
        }
    }

    private MatchOverFrameCodec() {}

    /** Encode one {@code MOVR} verdict as exactly {@link #MATCH_OVER_FRAME_BYTES} bytes. */
    public static byte[] encode(MatchOverRecord record) {
        Objects.requireNonNull(record, "record");
        byte[] buf = new byte[MATCH_OVER_FRAME_BYTES];
        int off = 0;
        off = putInt(buf, off, MATCH_OVER_MAGIC);
        off = putInt(buf, off, MATCH_OVER_TYPE_CODE);
        off = putShort(buf, off, MATCH_OVER_SCHEMA_VERSION);
        off = putLong(buf, off, record.simTick());
        buf[off++] = (byte) record.outcome();
        buf[off++] = (byte) (record.winnerSide() == -1 ? WINNER_NONE_WIRE : record.winnerSide());
        // off now == HEADER_BYTES + MATCH_OVER_PAYLOAD_BYTES; CRC32 covers exactly that body.
        CRC32 crc = new CRC32();
        crc.update(buf, 0, off);
        putInt(buf, off, (int) crc.getValue());
        return buf;
    }

    /** Serialize one {@code MOVR} to {@code out} as exactly {@link #MATCH_OVER_FRAME_BYTES} bytes. */
    public static void write(OutputStream out, MatchOverRecord record) throws IOException {
        Objects.requireNonNull(out, "out");
        byte[] bytes = encode(record);
        out.write(bytes, 0, bytes.length);
    }

    /**
     * Read exactly one {@code MOVR} from {@code in} (game-free tests / family walk; the live
     * client reads it via a mirrored reader off its demux). Returns {@code null} at a clean
     * end of stream; fail-closed otherwise.
     */
    public static MatchOverRecord read(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        byte[] frame = in.readNBytes(MATCH_OVER_FRAME_BYTES);
        if (frame.length == 0) {
            return null; // clean end of stream
        }
        if (frame.length != MATCH_OVER_FRAME_BYTES) {
            throw new IOException("match-over frame truncated: expected "
                    + MATCH_OVER_FRAME_BYTES + " bytes, got " + frame.length);
        }
        return decode(frame);
    }

    /** Decode + validate one full {@code MOVR} frame (fail-closed; verifier + read path). */
    public static MatchOverRecord decode(byte[] frame) throws IOException {
        Objects.requireNonNull(frame, "frame");
        if (frame.length != MATCH_OVER_FRAME_BYTES) {
            throw new IOException("match-over frame must be " + MATCH_OVER_FRAME_BYTES
                    + " bytes, got " + frame.length);
        }
        CRC32 crc = new CRC32();
        crc.update(frame, 0, HEADER_BYTES + MATCH_OVER_PAYLOAD_BYTES);
        int expectedCrc = (int) crc.getValue();
        int off = 0;
        int magic = getInt(frame, off); off += 4;
        if (magic != MATCH_OVER_MAGIC) {
            throw new IOException("match-over bad magic: 0x" + Integer.toHexString(magic));
        }
        int typeCode = getInt(frame, off); off += 4;
        if (typeCode != MATCH_OVER_TYPE_CODE) {
            throw new IOException("match-over wire type code mismatch: expected "
                    + MATCH_OVER_TYPE_CODE + ", got " + typeCode);
        }
        int schema = getUnsignedShort(frame, off); off += 2;
        if (schema != MATCH_OVER_SCHEMA_VERSION) {
            throw new IOException("match-over unsupported schema version: " + schema);
        }
        long simTick = getLong(frame, off); off += 8;
        int outcome = frame[off++] & 0xFF;
        int rawWinner = frame[off++] & 0xFF;
        int winnerSide = rawWinner == WINNER_NONE_WIRE ? -1 : rawWinner;
        int actualCrc = getInt(frame, off);
        if (actualCrc != expectedCrc) {
            throw new IOException("match-over frame CRC mismatch");
        }
        try {
            return new MatchOverRecord(simTick, outcome, winnerSide);
        } catch (IllegalArgumentException badFields) {
            throw new IOException(
                    "match-over fields invalid: " + badFields.getMessage(), badFields);
        }
    }
}
