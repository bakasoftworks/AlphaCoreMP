package cc.starsector.server.runtime;

import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.ServerShipFireCommandResultMessage;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Fixed-width wire codec for the server&rarr;client {@link LiveCommandAck} on the lean
 * AWSS live path.
 *
 * <p>The outbound sibling of {@link LiveClientIntentCodec}: where that codec frames the
 * one inbound client FIRE command, this frames the one outbound server acknowledgement
 * of it. The frame is bound to the registered server result message
 * {@link ServerShipFireCommandResultMessage} — it stamps that registry entry's wire
 * type code and schema version, so the lean frame <em>is</em> the registered
 * {@code server.ship_fire.result} message (a {@code SERVER_OWNER_ONLY_UPDATE} on the
 * {@code DURABLE_GAMEPLAY_EVENT} lane), promoted from registry-only metadata to an
 * actual wire message with a live consumer, rather than an ad-hoc reply.
 *
 * <p>Read is fail-closed: a wrong magic, wire type code, schema version, or a short
 * read throws {@link IOException} and yields no ack.
 */
public final class LiveCommandAckCodec {

    /** Frame magic ("SFAK"): server fire ack. */
    public static final int MAGIC = 0x5346414B;

    /** Registered server-result wire type code this frame carries (fire result). */
    public static final int MESSAGE_TYPE_CODE =
            NetworkMessageRegistry.authoritativeCombat().serverShipFireCommandResultWireEntry().wireTypeCode();

    /** Registered schema version of the fire-result message. */
    public static final short SCHEMA_VERSION = (short) ServerShipFireCommandResultMessage.SCHEMA_VERSION;

    /** Registered message id (for traceability/diagnostics). */
    public static final String MESSAGE_ID = ServerShipFireCommandResultMessage.MESSAGE_ID;

    /**
     * magic(int) + typeCode(int) + schema(short) + shipId(long) + fireRequested(byte)
     * + accepted(byte) + weaponGroup(int).
     */
    public static final int FRAME_BYTES =
            Integer.BYTES + Integer.BYTES + Short.BYTES + Long.BYTES + Byte.BYTES + Byte.BYTES + Integer.BYTES;

    private LiveCommandAckCodec() {
    }

    /** Encode one ack as a single fixed-width frame and flush it. */
    public static void writeAck(OutputStream out, LiveCommandAck ack) throws IOException {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(ack, "ack");
        DataOutputStream data = new DataOutputStream(out);
        data.writeInt(MAGIC);
        data.writeInt(MESSAGE_TYPE_CODE);
        data.writeShort(SCHEMA_VERSION);
        data.writeLong(ack.shipId());
        data.writeByte(ack.fireRequested() ? 1 : 0);
        data.writeByte(ack.accepted() ? 1 : 0);
        data.writeInt(ack.weaponGroup());
        data.flush();
    }

    /** Encode one ack into a freshly allocated byte array. */
    public static byte[] toBytes(LiveCommandAck ack) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(FRAME_BYTES);
        try {
            writeAck(bytes, ack);
        } catch (IOException e) {
            throw new IllegalStateException("in-memory ack encode failed", e);
        }
        return bytes.toByteArray();
    }

    /**
     * Read exactly one ack frame. Fail-closed: a truncated frame, a wrong magic /
     * wire type code / schema version throws {@link IOException}.
     */
    public static LiveCommandAck readAck(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        DataInputStream data = new DataInputStream(in);
        int magic;
        try {
            magic = data.readInt();
        } catch (EOFException eof) {
            throw new IOException("no command ack frame on the wire (EOF before magic)", eof);
        }
        if (magic != MAGIC) {
            throw new IOException("command ack frame magic mismatch: expected "
                    + Integer.toHexString(MAGIC) + ", got " + Integer.toHexString(magic));
        }
        int typeCode = data.readInt();
        if (typeCode != MESSAGE_TYPE_CODE) {
            throw new IOException("command ack wire type code mismatch: expected "
                    + MESSAGE_TYPE_CODE + ", got " + typeCode);
        }
        short schema = data.readShort();
        if (schema != SCHEMA_VERSION) {
            throw new IOException("command ack schema version mismatch: expected "
                    + SCHEMA_VERSION + ", got " + schema);
        }
        long shipId = data.readLong();
        int fireByte = data.readUnsignedByte();
        int acceptedByte = data.readUnsignedByte();
        int weaponGroup = data.readInt();
        if (fireByte != 0 && fireByte != 1) {
            throw new IOException("command ack fireRequested flag not boolean: " + fireByte);
        }
        if (acceptedByte != 0 && acceptedByte != 1) {
            throw new IOException("command ack accepted flag not boolean: " + acceptedByte);
        }
        return new LiveCommandAck(shipId, weaponGroup, fireByte == 1, acceptedByte == 1);
    }
}
