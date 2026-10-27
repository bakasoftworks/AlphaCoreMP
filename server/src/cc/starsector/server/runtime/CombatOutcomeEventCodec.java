package cc.starsector.server.runtime;

import cc.starsector.server.protocol.CombatOutcomeEvent;
import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.ServerCombatOutcomeEventMessage;
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
 * Fixed-width wire codec for the server-&gt;client {@link CombatOutcomeEvent}
 * ({@link ServerCombatOutcomeEventMessage}).
 *
 * <p>Like {@link EntityDespawnEventCodec}, this is a lean fixed-width frame bound to
 * its registry entry: every frame stamps the registered wire type code and schema
 * version of {@code server.combat_outcome_event}, so the bytes are the registered
 * message, not an ad-hoc one. Read is fail-closed: a wrong magic, type code, schema,
 * a negative winning side, or a short read throws {@link IOException} and yields no
 * event.
 */
public final class CombatOutcomeEventCodec {

    /** Frame magic ("CBTO"): combat outcome. */
    public static final int MAGIC = 0x4342544F;

    /** Registered wire type code this frame carries. */
    public static final int MESSAGE_TYPE_CODE =
            NetworkMessageRegistry.authoritativeCombat().serverCombatOutcomeEventWireEntry().wireTypeCode();

    /** Registered schema version of the combat-outcome-event message. */
    public static final short SCHEMA_VERSION = (short) ServerCombatOutcomeEventMessage.SCHEMA_VERSION;

    /** Registered message id (for traceability/diagnostics). */
    public static final String MESSAGE_ID = ServerCombatOutcomeEventMessage.MESSAGE_ID;

    /** magic(int) + typeCode(int) + schema(short) + winningSideId(int) + combatOver(byte) + simTick(long). */
    public static final int FRAME_BYTES =
            Integer.BYTES + Integer.BYTES + Short.BYTES + Integer.BYTES + Byte.BYTES + Long.BYTES;

    private CombatOutcomeEventCodec() {
    }

    /** Encode one combat-outcome event as a single fixed-width frame and flush it. */
    public static void writeEvent(OutputStream out, CombatOutcomeEvent event) throws IOException {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(event, "event");
        DataOutputStream data = new DataOutputStream(out);
        data.writeInt(MAGIC);
        data.writeInt(MESSAGE_TYPE_CODE);
        data.writeShort(SCHEMA_VERSION);
        data.writeInt(event.winningSideId());
        data.writeBoolean(event.combatOver());
        data.writeLong(event.simTick());
        data.flush();
    }

    /** Encode one combat-outcome event into a freshly allocated byte array. */
    public static byte[] toBytes(CombatOutcomeEvent event) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(FRAME_BYTES);
        try {
            writeEvent(bytes, event);
        } catch (IOException e) {
            throw new IllegalStateException("in-memory combat outcome event encode failed", e);
        }
        return bytes.toByteArray();
    }

    /**
     * Read exactly one combat-outcome-event frame. Fail-closed: a truncated frame, a
     * wrong magic / type code / schema version / negative winning side throws
     * {@link IOException}.
     */
    public static CombatOutcomeEvent readEvent(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        DataInputStream data = new DataInputStream(in);
        int magic;
        try {
            magic = data.readInt();
        } catch (EOFException eof) {
            throw new IOException("no combat outcome event frame on the wire (EOF before magic)", eof);
        }
        return readBody(data, magic);
    }

    /** Drain every combat-outcome-event frame from {@code in} until end of stream. */
    public static List<CombatOutcomeEvent> readEvents(InputStream in) throws IOException {
        List<CombatOutcomeEvent> events = new ArrayList<>();
        readEvents(in, events::add);
        return List.copyOf(events);
    }

    /** Drain every combat-outcome-event frame from {@code in}, handing each to {@code consumer}. */
    public static void readEvents(InputStream in, Consumer<? super CombatOutcomeEvent> consumer)
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
            consumer.accept(readBody(data, magic));
        }
    }

    /** Read the post-magic body of one frame, validating the registry-bound identity. */
    private static CombatOutcomeEvent readBody(DataInputStream data, int magic) throws IOException {
        if (magic != MAGIC) {
            throw new IOException("combat outcome event frame magic mismatch: expected "
                    + Integer.toHexString(MAGIC) + ", got " + Integer.toHexString(magic));
        }
        int typeCode = data.readInt();
        if (typeCode != MESSAGE_TYPE_CODE) {
            throw new IOException("combat outcome event wire type code mismatch: expected "
                    + MESSAGE_TYPE_CODE + ", got " + typeCode);
        }
        short schema = data.readShort();
        if (schema != SCHEMA_VERSION) {
            throw new IOException("combat outcome event schema version mismatch: expected "
                    + SCHEMA_VERSION + ", got " + schema);
        }
        int winningSideId = data.readInt();
        boolean combatOver = data.readBoolean();
        long simTick = data.readLong();
        try {
            return new CombatOutcomeEvent(winningSideId, combatOver, simTick);
        } catch (IllegalArgumentException badEvent) {
            throw new IOException("combat outcome event fields invalid: " + badEvent.getMessage(), badEvent);
        }
    }

    private static int readByteOrThrow(DataInputStream data) throws IOException {
        int b = data.read();
        if (b < 0) {
            throw new EOFException("truncated combat outcome event frame header");
        }
        return b & 0xff;
    }
}
