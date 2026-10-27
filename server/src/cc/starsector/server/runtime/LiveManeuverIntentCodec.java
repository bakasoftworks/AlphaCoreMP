package cc.starsector.server.runtime;

import cc.starsector.server.protocol.ClientShipMovementCommandMessage;
import cc.starsector.server.protocol.NetworkMessageRegistry;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Fixed-width wire codec for the inbound {@link LiveManeuverIntent} on the lean
 * AWSS live path — the maneuver sibling of
 * {@link LiveClientIntentCodec}.
 *
 * <p>Same lean framing rationale as the fire codec: the live publish path is raw
 * {@code AWSS} frames with no F5-era admission handshake, so this is the matching lean
 * framing for the one client→server maneuver message that closes the round-trip,
 * rather than dragging in the whole
 * {@link cc.starsector.server.client.HeadlessSnapshotSocketClient} control protocol.
 *
 * <p>The frame is bound to the registered authority-input message
 * {@link ClientShipMovementCommandMessage}: it stamps that registry entry's wire type
 * code and schema version, so the lean frame is the registered movement-command
 * message, not an ad-hoc one. Read is fail-closed: a wrong magic, type code, schema,
 * out-of-range movement, or short read throws {@link IOException} and yields no intent.
 */
public final class LiveManeuverIntentCodec {

    /** Frame magic ("LMVI"): live maneuver intent. */
    public static final int MAGIC = 0x4C4D5649;

    /** Registered authority-input wire type code this frame carries (movement command). */
    public static final int MESSAGE_TYPE_CODE =
            NetworkMessageRegistry.authoritativeCombat().clientShipMovementCommandWireEntry().wireTypeCode();

    /** Registered schema version of the movement-command message. */
    public static final short SCHEMA_VERSION = (short) ClientShipMovementCommandMessage.SCHEMA_VERSION;

    /** Registered message id (for traceability/diagnostics). */
    public static final String MESSAGE_ID = ClientShipMovementCommandMessage.MESSAGE_ID;

    /** magic(int) + typeCode(int) + schema(short) + shipId(long) + maneuver(byte) + movementOrdinal(int). */
    public static final int FRAME_BYTES =
            Integer.BYTES + Integer.BYTES + Short.BYTES + Long.BYTES + Byte.BYTES + Integer.BYTES;

    private LiveManeuverIntentCodec() {
    }

    /** Encode one intent as a single fixed-width frame and flush it. */
    public static void writeIntent(OutputStream out, LiveManeuverIntent intent) throws IOException {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(intent, "intent");
        DataOutputStream data = new DataOutputStream(out);
        data.writeInt(MAGIC);
        data.writeInt(MESSAGE_TYPE_CODE);
        data.writeShort(SCHEMA_VERSION);
        data.writeLong(intent.shipId());
        data.writeByte(intent.maneuver() ? 1 : 0);
        data.writeInt(intent.movementOrdinal());
        data.flush();
    }

    /** Encode one intent into a freshly allocated byte array. */
    public static byte[] toBytes(LiveManeuverIntent intent) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(FRAME_BYTES);
        try {
            writeIntent(bytes, intent);
        } catch (IOException e) {
            throw new IllegalStateException("in-memory maneuver intent encode failed", e);
        }
        return bytes.toByteArray();
    }

    /**
     * Read exactly one intent frame. Fail-closed: a truncated frame, a wrong magic /
     * type code / schema version / non-boolean flag throws {@link IOException}.
     */
    public static LiveManeuverIntent readIntent(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        DataInputStream data = new DataInputStream(in);
        int magic;
        try {
            magic = data.readInt();
        } catch (EOFException eof) {
            throw new IOException("no client maneuver intent frame on the wire (EOF before magic)", eof);
        }
        if (magic != MAGIC) {
            throw new IOException("client maneuver intent frame magic mismatch: expected "
                    + Integer.toHexString(MAGIC) + ", got " + Integer.toHexString(magic));
        }
        int typeCode = data.readInt();
        if (typeCode != MESSAGE_TYPE_CODE) {
            throw new IOException("client maneuver intent wire type code mismatch: expected "
                    + MESSAGE_TYPE_CODE + ", got " + typeCode);
        }
        short schema = data.readShort();
        if (schema != SCHEMA_VERSION) {
            throw new IOException("client maneuver intent schema version mismatch: expected "
                    + SCHEMA_VERSION + ", got " + schema);
        }
        long shipId = data.readLong();
        int maneuverByte = data.readUnsignedByte();
        int movementOrdinal = data.readInt();
        if (maneuverByte != 0 && maneuverByte != 1) {
            throw new IOException("client maneuver intent flag not boolean: " + maneuverByte);
        }
        return new LiveManeuverIntent(shipId, maneuverByte == 1, movementOrdinal);
    }
}
