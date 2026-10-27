package cc.starsector.server.runtime;

import cc.starsector.server.protocol.EntityDespawnEvent;
import cc.starsector.server.protocol.EntityDespawnReason;
import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.ServerEntityDespawnEventMessage;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Fixed-width wire codec for the server-&gt;client {@link EntityDespawnEvent}
 * ({@link ServerEntityDespawnEventMessage}).
 *
 * <p>Like {@link LiveClientIntentCodec}, this is a lean fixed-width frame bound to
 * its registry entry: every frame stamps the registered wire type code and schema
 * version of {@code server.entity_despawn_event}, so the bytes are the registered
 * message, not an ad-hoc one. Read is fail-closed: a wrong magic, type code,
 * schema, unknown reason code, or short read throws {@link IOException} and yields
 * no event.
 */
public final class EntityDespawnEventCodec {

    /** Frame magic ("EDSP"): entity despawn event. */
    public static final int MAGIC = 0x45445350;

    /** Registered wire type code this frame carries. */
    public static final int MESSAGE_TYPE_CODE =
            NetworkMessageRegistry.authoritativeCombat().serverEntityDespawnEventWireEntry().wireTypeCode();

    /** Registered schema version of the despawn-event message. */
    public static final short SCHEMA_VERSION = (short) ServerEntityDespawnEventMessage.SCHEMA_VERSION;

    /** Registered message id (for traceability/diagnostics). */
    public static final String MESSAGE_ID = ServerEntityDespawnEventMessage.MESSAGE_ID;

    /** magic(int) + typeCode(int) + schema(short) + entityId(long) + simTick(long) + reasonCode(int). */
    public static final int FRAME_BYTES =
            Integer.BYTES + Integer.BYTES + Short.BYTES + Long.BYTES + Long.BYTES + Integer.BYTES;

    private EntityDespawnEventCodec() {
    }

    /** Encode one despawn event as a single fixed-width frame and flush it. */
    public static void writeEvent(OutputStream out, EntityDespawnEvent event) throws IOException {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(event, "event");
        DataOutputStream data = new DataOutputStream(out);
        data.writeInt(MAGIC);
        data.writeInt(MESSAGE_TYPE_CODE);
        data.writeShort(SCHEMA_VERSION);
        data.writeLong(event.entityId());
        data.writeLong(event.simTick());
        data.writeInt(event.reason().wireCode());
        data.flush();
    }

    /** Encode one despawn event into a freshly allocated byte array. */
    public static byte[] toBytes(EntityDespawnEvent event) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(FRAME_BYTES);
        try {
            writeEvent(bytes, event);
        } catch (IOException e) {
            throw new IllegalStateException("in-memory despawn event encode failed", e);
        }
        return bytes.toByteArray();
    }

    /**
     * Read exactly one despawn-event frame. Fail-closed: a truncated frame, a wrong
     * magic / type code / schema version / unknown reason code throws
     * {@link IOException}.
     */
    public static EntityDespawnEvent readEvent(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        DataInputStream data = new DataInputStream(in);
        int magic;
        try {
            magic = data.readInt();
        } catch (EOFException eof) {
            throw new IOException("no despawn event frame on the wire (EOF before magic)", eof);
        }
        if (magic != MAGIC) {
            throw new IOException("despawn event frame magic mismatch: expected "
                    + Integer.toHexString(MAGIC) + ", got " + Integer.toHexString(magic));
        }
        int typeCode = data.readInt();
        if (typeCode != MESSAGE_TYPE_CODE) {
            throw new IOException("despawn event wire type code mismatch: expected "
                    + MESSAGE_TYPE_CODE + ", got " + typeCode);
        }
        short schema = data.readShort();
        if (schema != SCHEMA_VERSION) {
            throw new IOException("despawn event schema version mismatch: expected "
                    + SCHEMA_VERSION + ", got " + schema);
        }
        long entityId = data.readLong();
        long simTick = data.readLong();
        int reasonCode = data.readInt();
        EntityDespawnReason reason;
        try {
            reason = EntityDespawnReason.fromWireCode(reasonCode);
        } catch (IllegalArgumentException badReason) {
            throw new IOException("despawn event reason code invalid: " + reasonCode, badReason);
        }
        try {
            return new EntityDespawnEvent(entityId, simTick, reason);
        } catch (IllegalArgumentException badEvent) {
            throw new IOException("despawn event fields invalid: " + badEvent.getMessage(), badEvent);
        }
    }

    /** Drain every despawn-event frame from {@code in} until end of stream. */
    public static List<EntityDespawnEvent> readEvents(InputStream in) throws IOException {
        List<EntityDespawnEvent> events = new ArrayList<>();
        readEvents(in, events::add);
        return List.copyOf(events);
    }

    /** Drain every despawn-event frame from {@code in}, handing each to {@code consumer}. */
    public static void readEvents(InputStream in, Consumer<? super EntityDespawnEvent> consumer)
            throws IOException {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(consumer, "consumer");
        DataInputStream data = new DataInputStream(in);
        while (true) {
            int first = data.read();
            if (first < 0) {
                return;
            }
            int magic = ((first & 0xff) << 24)
                    | (readByteOrThrow(data) << 16)
                    | (readByteOrThrow(data) << 8)
                    | readByteOrThrow(data);
            if (magic != MAGIC) {
                throw new IOException("despawn event frame magic mismatch: " + Integer.toHexString(magic));
            }
            int typeCode = data.readInt();
            if (typeCode != MESSAGE_TYPE_CODE) {
                throw new IOException("despawn event wire type code mismatch: " + typeCode);
            }
            short schema = data.readShort();
            if (schema != SCHEMA_VERSION) {
                throw new IOException("despawn event schema version mismatch: " + schema);
            }
            long entityId = data.readLong();
            long simTick = data.readLong();
            int reasonCode = data.readInt();
            EntityDespawnReason reason;
            try {
                reason = EntityDespawnReason.fromWireCode(reasonCode);
            } catch (IllegalArgumentException badReason) {
                throw new IOException("despawn event reason code invalid: " + reasonCode, badReason);
            }
            try {
                consumer.accept(new EntityDespawnEvent(entityId, simTick, reason));
            } catch (IllegalArgumentException badEvent) {
                throw new IOException("despawn event fields invalid: " + badEvent.getMessage(), badEvent);
            }
        }
    }

    private static int readByteOrThrow(DataInputStream data) throws IOException {
        int b = data.read();
        if (b < 0) {
            throw new EOFException("truncated despawn event frame header");
        }
        return b & 0xff;
    }
}
