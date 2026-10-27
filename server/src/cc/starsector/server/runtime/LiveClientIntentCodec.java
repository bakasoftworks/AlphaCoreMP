package cc.starsector.server.runtime;

import cc.starsector.server.protocol.ClientShipFireCommandMessage;
import cc.starsector.server.protocol.NetworkMessageRegistry;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Fixed-width wire codec for the inbound {@link LiveClientIntent} on the lean
 * AWSS live path.
 *
 * <p>The live publish path ({@link LiveWorldSnapshotSocketPublisher} +
 * {@link cc.starsector.server.client.WorldSnapshotSocketClient}) is deliberately
 * lean — raw {@code AWSS} frames, no F5-era admission handshake. This codec is the
 * matching lean framing for the one client→server intent message that closes the
 * round-trip, rather than dragging in the whole
 * {@link cc.starsector.server.client.HeadlessSnapshotSocketClient} control protocol.
 *
 * <p>The frame is bound to the registered authority-input message
 * {@link ClientShipFireCommandMessage}: it stamps that registry entry's wire type
 * code and schema version, so the lean frame is the registered fire-command
 * message, not an ad-hoc one. Read is fail-closed: a wrong magic, type code,
 * schema, or short read throws {@link IOException} and yields no intent.
 */
public final class LiveClientIntentCodec {

    /** Frame magic ("LCIT"): live client intent. */
    public static final int MAGIC = 0x4C434954;

    /** Registered authority-input wire type code this frame carries (fire command). */
    public static final int MESSAGE_TYPE_CODE =
            NetworkMessageRegistry.authoritativeCombat().clientShipFireCommandWireEntry().wireTypeCode();

    /** Registered schema version of the fire-command message. */
    public static final short SCHEMA_VERSION = (short) ClientShipFireCommandMessage.SCHEMA_VERSION;

    /** Registered message id (for traceability/diagnostics). */
    public static final String MESSAGE_ID = ClientShipFireCommandMessage.MESSAGE_ID;

    /** magic(int) + typeCode(int) + schema(short) + shipId(long) + fire(byte) + weaponGroup(int). */
    public static final int FRAME_BYTES =
            Integer.BYTES + Integer.BYTES + Short.BYTES + Long.BYTES + Byte.BYTES + Integer.BYTES;

    private LiveClientIntentCodec() {
    }

    /** Encode one intent as a single fixed-width frame and flush it. */
    public static void writeIntent(OutputStream out, LiveClientIntent intent) throws IOException {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(intent, "intent");
        DataOutputStream data = new DataOutputStream(out);
        data.writeInt(MAGIC);
        data.writeInt(MESSAGE_TYPE_CODE);
        data.writeShort(SCHEMA_VERSION);
        data.writeLong(intent.shipId());
        data.writeByte(intent.fire() ? 1 : 0);
        data.writeInt(intent.weaponGroup());
        data.flush();
    }

    /** Encode one intent into a freshly allocated byte array. */
    public static byte[] toBytes(LiveClientIntent intent) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(FRAME_BYTES);
        try {
            writeIntent(bytes, intent);
        } catch (IOException e) {
            throw new IllegalStateException("in-memory intent encode failed", e);
        }
        return bytes.toByteArray();
    }

    /**
     * Read exactly one intent frame. Fail-closed: a truncated frame, a wrong
     * magic / type code / schema version throws {@link IOException}.
     */
    public static LiveClientIntent readIntent(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        DataInputStream data = new DataInputStream(in);
        int magic;
        try {
            magic = data.readInt();
        } catch (EOFException eof) {
            throw new IOException("no client intent frame on the wire (EOF before magic)", eof);
        }
        if (magic != MAGIC) {
            throw new IOException("client intent frame magic mismatch: expected "
                    + Integer.toHexString(MAGIC) + ", got " + Integer.toHexString(magic));
        }
        int typeCode = data.readInt();
        if (typeCode != MESSAGE_TYPE_CODE) {
            throw new IOException("client intent wire type code mismatch: expected "
                    + MESSAGE_TYPE_CODE + ", got " + typeCode);
        }
        short schema = data.readShort();
        if (schema != SCHEMA_VERSION) {
            throw new IOException("client intent schema version mismatch: expected "
                    + SCHEMA_VERSION + ", got " + schema);
        }
        long shipId = data.readLong();
        int fireByte = data.readUnsignedByte();
        int weaponGroup = data.readInt();
        if (fireByte != 0 && fireByte != 1) {
            throw new IOException("client intent fire flag not boolean: " + fireByte);
        }
        return new LiveClientIntent(shipId, fireByte == 1, weaponGroup);
    }
}
