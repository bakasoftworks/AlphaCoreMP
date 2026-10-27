package cc.starsector.server.runtime;

import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.PhaseDiveEvent;
import cc.starsector.server.protocol.ServerPhaseDiveEventMessage;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Fixed-width wire codec for the server-&gt;client {@link PhaseDiveEvent}
 * ({@link ServerPhaseDiveEventMessage}).
 *
 * <p>The {@link EntityDespawnEventCodec} idiom minus the reason field: a lean
 * fixed-width frame bound to its registry entry — every frame stamps the
 * registered wire type code and schema version of {@code server.phase_dive_event},
 * so the bytes are the registered message, not an ad-hoc one. No strings, no
 * wire-derived lengths. Read is fail-closed: a wrong
 * magic, type code, schema, or short read throws {@link IOException} and yields
 * no event.
 */
public final class PhaseDiveEventCodec {

    /** Frame magic ("DIVE"): phase-dive start event. */
    public static final int MAGIC = 0x44495645;

    /** Registered wire type code this frame carries. */
    public static final int MESSAGE_TYPE_CODE =
            NetworkMessageRegistry.authoritativeCombat().serverPhaseDiveEventWireEntry().wireTypeCode();

    /** Registered schema version of the phase-dive-event message. */
    public static final short SCHEMA_VERSION = (short) ServerPhaseDiveEventMessage.SCHEMA_VERSION;

    /** Registered message id (for traceability/diagnostics). */
    public static final String MESSAGE_ID = ServerPhaseDiveEventMessage.MESSAGE_ID;

    /** magic(int) + typeCode(int) + schema(short) + entityId(long) + simTick(long). */
    public static final int FRAME_BYTES =
            Integer.BYTES + Integer.BYTES + Short.BYTES + Long.BYTES + Long.BYTES;

    private PhaseDiveEventCodec() {
    }

    /** Encode one phase-dive event as a single fixed-width frame and flush it. */
    public static void writeEvent(OutputStream out, PhaseDiveEvent event) throws IOException {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(event, "event");
        DataOutputStream data = new DataOutputStream(out);
        data.writeInt(MAGIC);
        data.writeInt(MESSAGE_TYPE_CODE);
        data.writeShort(SCHEMA_VERSION);
        data.writeLong(event.entityId());
        data.writeLong(event.simTick());
        data.flush();
    }

    /** Encode one phase-dive event into a freshly allocated byte array. */
    public static byte[] toBytes(PhaseDiveEvent event) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(FRAME_BYTES);
        try {
            writeEvent(bytes, event);
        } catch (IOException e) {
            throw new IllegalStateException("in-memory phase-dive event encode failed", e);
        }
        return bytes.toByteArray();
    }

    /**
     * Read exactly one phase-dive-event frame. Fail-closed: a truncated frame, a
     * wrong magic / type code / schema version throws {@link IOException}.
     */
    public static PhaseDiveEvent readEvent(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        DataInputStream data = new DataInputStream(in);
        int magic;
        try {
            magic = data.readInt();
        } catch (EOFException eof) {
            throw new IOException("no phase-dive event frame on the wire (EOF before magic)", eof);
        }
        if (magic != MAGIC) {
            throw new IOException("phase-dive event frame magic mismatch: expected "
                    + Integer.toHexString(MAGIC) + ", got " + Integer.toHexString(magic));
        }
        int typeCode = data.readInt();
        if (typeCode != MESSAGE_TYPE_CODE) {
            throw new IOException("phase-dive event wire type code mismatch: expected "
                    + MESSAGE_TYPE_CODE + ", got " + typeCode);
        }
        short schema = data.readShort();
        if (schema != SCHEMA_VERSION) {
            throw new IOException("phase-dive event schema version mismatch: expected "
                    + SCHEMA_VERSION + ", got " + schema);
        }
        long entityId = data.readLong();
        long simTick = data.readLong();
        try {
            return new PhaseDiveEvent(entityId, simTick);
        } catch (IllegalArgumentException badEvent) {
            throw new IOException("phase-dive event fields invalid: " + badEvent.getMessage(), badEvent);
        }
    }
}
