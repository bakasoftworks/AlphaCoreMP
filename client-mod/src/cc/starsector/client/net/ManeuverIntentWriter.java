package cc.starsector.client.net;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Client-side encoder for the maneuver round-trip handshake the standing dedicated
 * server reads — the sending half of the server's
 * {@code LiveManeuverIntentCodec}, plus the {@code (peerId, maxSchema)} advertisement
 * the standing-server maneuver path
 * ({@code PersistentDedicatedCombatServer.serveManeuverRoundTripUntilShutdown})
 * reads first.
 *
 * <p>Per connection that server reads, off one stream:
 * <ol>
 *   <li>{@code peerId} (int) + {@code maxSchema} (int) — the 8-byte
 *       advertisement (governs which snapshot schema is served);</li>
 *   <li>one {@code LiveManeuverIntent} frame (23 bytes: magic + wire type code +
 *       schema + shipId + maneuver flag + movement ordinal) — the registered
 *       {@code client.ship_movement.requested} message.</li>
 * </ol>
 * then it streams snapshot frames back. This mirrors that exact layout so the mod
 * speaks the registered message without bundling the server's
 * {@code NetworkMessageRegistry} / protocol graph into the game JVM (same discipline
 * as {@link AwssSnapshotReader} for the inbound {@code AWSS} frame).
 *
 * <p>The wire type code is <em>derived the same way the server derives it</em> — the
 * CRC32 of the message id (see {@code ProtocolRegistryEntry.wireTypeCode}) — rather
 * than a copied magic constant, so it tracks the registry id. The game-free verifier
 * asserts these constants equal
 * {@code LiveManeuverIntentCodec}'s and round-trips a frame through the real
 * {@code readIntent}; any drift breaks it.
 */
public final class ManeuverIntentWriter {

    /** Frame magic ("LMVI"). Mirrors {@code LiveManeuverIntentCodec.MAGIC}. */
    public static final int MAGIC = 0x4C4D5649;

    /** Registry message id this frame carries. Mirrors {@code ClientShipMovementCommandMessage.MESSAGE_ID}. */
    public static final String MESSAGE_ID = "client.ship_movement.requested";

    /** Wire type code = CRC32(MESSAGE_ID), mirroring {@code ProtocolRegistryEntry.wireTypeCode}. */
    public static final int MESSAGE_TYPE_CODE = wireTypeCode(MESSAGE_ID);

    /** Registered schema version. Mirrors {@code ClientShipMovementCommandMessage.SCHEMA_VERSION}. */
    public static final short SCHEMA_VERSION = 1;

    /** magic(int) + typeCode(int) + schema(short) + shipId(long) + maneuver(byte) + movementOrdinal(int). */
    public static final int FRAME_BYTES =
            Integer.BYTES + Integer.BYTES + Short.BYTES + Long.BYTES + Byte.BYTES + Integer.BYTES;

    /** peerId(int) + maxSchema(int). */
    public static final int ADVERTISEMENT_BYTES = Integer.BYTES + Integer.BYTES;

    private ManeuverIntentWriter() {
    }

    /**
     * Stable 32-bit wire identity derived from the registry message id, byte-identical
     * to the server's {@code ProtocolRegistryEntry.wireTypeCode(String)} (CRC32 of the
     * UTF-8 id). Computed in the client with {@code java.util.zip.CRC32} so the mod
     * carries no server dependency.
     */
    public static int wireTypeCode(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId must be non-blank");
        }
        CRC32 crc = new CRC32();
        byte[] bytes = messageId.getBytes(StandardCharsets.UTF_8);
        crc.update(bytes, 0, bytes.length);
        return (int) crc.getValue();
    }

    /** Write the 8-byte peer advertisement and flush. */
    public static void writeAdvertisement(OutputStream out, int peerId, int maxSchema) throws IOException {
        Objects.requireNonNull(out, "out");
        byte[] b = new byte[ADVERTISEMENT_BYTES];
        int off = WireBytes.putInt(b, 0, peerId);
        WireBytes.putInt(b, off, maxSchema);
        out.write(b);
        out.flush();
    }

    /** Write one maneuver intent as a single fixed-width frame and flush. */
    public static void writeManeuver(OutputStream out, ClientManeuverIntent intent) throws IOException {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(intent, "intent");
        out.write(maneuverFrame(intent));
        out.flush();
    }

    /** The single fixed-width maneuver frame ({@link #FRAME_BYTES} bytes), big-endian. */
    private static byte[] maneuverFrame(ClientManeuverIntent intent) {
        byte[] b = new byte[FRAME_BYTES];
        int off = WireBytes.putInt(b, 0, MAGIC);
        off = WireBytes.putInt(b, off, MESSAGE_TYPE_CODE);
        off = WireBytes.putShort(b, off, SCHEMA_VERSION);
        off = WireBytes.putLong(b, off, intent.shipId());
        off = WireBytes.putByte(b, off, intent.maneuver() ? 1 : 0);
        WireBytes.putInt(b, off, intent.movementOrdinal());
        return b;
    }

    /**
     * Write the advertisement and the intent contiguously, exactly as
     * {@code serveManeuverRoundTripUntilShutdown} reads them off one stream.
     */
    public static void writeHandshake(
            OutputStream out, int peerId, int maxSchema, ClientManeuverIntent intent) throws IOException {
        writeAdvertisement(out, peerId, maxSchema);
        writeManeuver(out, intent);
    }

    /** The full handshake (advertisement + intent) as a freshly allocated byte array. */
    public static byte[] handshakeBytes(int peerId, int maxSchema, ClientManeuverIntent intent) {
        Objects.requireNonNull(intent, "intent");
        byte[] b = new byte[ADVERTISEMENT_BYTES + FRAME_BYTES];
        int off = WireBytes.putInt(b, 0, peerId);
        off = WireBytes.putInt(b, off, maxSchema);
        byte[] frame = maneuverFrame(intent);
        System.arraycopy(frame, 0, b, off, frame.length);
        return b;
    }
}
