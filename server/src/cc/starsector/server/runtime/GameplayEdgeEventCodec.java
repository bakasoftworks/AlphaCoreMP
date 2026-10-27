package cc.starsector.server.runtime;

import cc.starsector.server.protocol.GameplayEdgeEvent;
import cc.starsector.server.protocol.GameplayEdgeEventKind;
import cc.starsector.server.protocol.GameplayEdgeEventPhase;
import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.ServerGameplayEdgeEventMessage;
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
 * Fixed-width wire codec for the server-&gt;client {@link GameplayEdgeEvent}
 * ({@link ServerGameplayEdgeEventMessage}).
 *
 *
 * <p>Mirrors {@link EntityDespawnEventCodec} byte-for-byte in style: a lean
 * fixed-width frame bound to its registry entry — every frame stamps the
 * registered wire type code and schema version of
 * {@code server.gameplay_edge_event}, so the bytes are the registered message,
 * not an ad-hoc one. Read is fail-closed: a wrong magic, type code, schema
 * (v16 or legacy v15/v14/v13/v12/v10/v9/v8/v7/v6/v5/v4/v3/v2/v1 only — v1 archives decode with
 * {@code flightBearingDeg=NaN}, pre-v6 archives with {@code birthSpeed=NaN}, pre-v8
 * archives with the {@code muzzleX,muzzleY} pair {@code NaN}, pre-v9 archives with
 * {@code targetWireId=TARGET_ABSENT}),
 * a {@code SYSTEM_JUMP} kind in a pre-v3 frame ({@code SYSTEM_JUMP_MIN_SCHEMA}),
 * a {@code MISSILE_SPLIT} kind in a pre-v4 frame ({@code MISSILE_SPLIT_MIN_SCHEMA}),
 * a {@code MISSILE_END} kind in a pre-v5 frame ({@code MISSILE_END_MIN_SCHEMA}),
 * an {@code AUTOFIRE_TARGET} kind in a pre-v9 frame
 * ({@code AUTOFIRE_TARGET_MIN_SCHEMA}),
 * a {@code PALADIN_DET} kind in a pre-v10 frame ({@code PALADIN_DET_MIN_SCHEMA}),
 * an {@code ARC_HIT} kind in a pre-v13 frame ({@code ARC_HIT_MIN_SCHEMA}),
 * a {@code CRIT_MALFUNCTION} kind in a pre-v14 frame ({@code CRIT_MALFUNCTION_MIN_SCHEMA}; v13 legacy read accepted),
 * a {@code MISSILE_END} naming a victim or reading {@code END_CAUSE_SHIELD_ABSORBED} in a pre-v15
 * frame ({@code MISSILE_END_VICTIM_MIN_SCHEMA}; v14 legacy read accepted),
 * a {@code WEAPON_DISCHARGE} carrying a declared wait (slot not {@code TARGET_ABSENT}) in a pre-v16
 * frame ({@code DECLARED_WAIT_MIN_SCHEMA}; v15 legacy read accepted),
 * unknown kind/phase code, kind/phase payload contradiction, or short read
 * throws {@link IOException} and yields no event.
 *
 * <p>Encoding is deterministic for equal events: kind/phase ride their stable
 * {@code wireCode()}s (never ordinals) and {@code aimFacingDeg} rides
 * {@link DataOutputStream#writeFloat} — the event record only admits finite
 * values or canonical {@link Float#NaN} (the fail-soft "aim read unbound"
 * marker, spec), both of which round-trip bit-exactly.
 */
public final class GameplayEdgeEventCodec {

    /** Frame magic ("GPEE"): gameplay edge event. */
    public static final int MAGIC = 0x47504545;

    /** Registered wire type code this frame carries. */
    public static final int MESSAGE_TYPE_CODE =
            NetworkMessageRegistry.authoritativeCombat().serverGameplayEdgeEventWireEntry().wireTypeCode();

    /** Registered schema version of the gameplay-edge-event message (the live schema — the writer always stamps this). */
    public static final short SCHEMA_VERSION = (short) ServerGameplayEdgeEventMessage.SCHEMA_VERSION;

    /** Legacy v9 schema — READ-only tolerance (same 66 B width as v10; no
     *  {@code PALADIN_DET} in its taxonomy — the v10 delta is a kind, not a width). */
    /** v12 = the 66 B mote-target schema every pre-v13 archive carries (v13 = same width, the
     *  ARC_HIT kind — READ-only tolerance, no {@code ARC_HIT} in its taxonomy). */
    /** v14 = the 66 B CRIT_MALFUNCTION schema every pre-v15 archive carries (v15 = same width, the
     *  MISSILE_END victim form — READ-only tolerance: no named victim and no
     *  {@code END_CAUSE_SHIELD_ABSORBED} in its taxonomy). */
    public static final short SCHEMA_VERSION_V14 = (short) ServerGameplayEdgeEventMessage.SCHEMA_VERSION_V14;
    /** v15 = the 66 B MISSILE_END victim schema every pre-v16 archive carries (v16 = same width, the
     *  declared wait on the {@code WEAPON_DISCHARGE} long slot — READ-only tolerance: no
     *  declared wait in its taxonomy). */
    public static final short SCHEMA_VERSION_V15 = (short) ServerGameplayEdgeEventMessage.SCHEMA_VERSION_V15;
    /** v13 = the 66 B ARC_HIT schema every pre-v14 archive carries (v14 = same width, the
     *  CRIT_MALFUNCTION kind — READ-only tolerance, no {@code CRIT_MALFUNCTION} in
     *  its taxonomy). */
    public static final short SCHEMA_VERSION_V13 = (short) ServerGameplayEdgeEventMessage.SCHEMA_VERSION_V13;
    public static final short SCHEMA_VERSION_V12 = (short) ServerGameplayEdgeEventMessage.SCHEMA_VERSION_V12;
    /** v10 = the 66 B PALADIN_DET schema every pre-v12 archive carries (v12 = same width, the
     *  mote-target payload on SYSTEM_WINDOW ON). */
    public static final short SCHEMA_VERSION_V10 = (short) ServerGameplayEdgeEventMessage.SCHEMA_VERSION_V10;
    public static final short SCHEMA_VERSION_V9 = (short) ServerGameplayEdgeEventMessage.SCHEMA_VERSION_V9;

    /** Legacy v8 schema — READ-only tolerance (58 B: no {@code targetWireId} tail long,
     *  no {@code AUTOFIRE_TARGET} in its taxonomy). */
    public static final short SCHEMA_VERSION_V8 = (short) ServerGameplayEdgeEventMessage.SCHEMA_VERSION_V8;

    /** Legacy v7 schema — READ-only tolerance (50 B: no {@code muzzleX,muzzleY} tail pair). */
    public static final short SCHEMA_VERSION_V7 = (short) ServerGameplayEdgeEventMessage.SCHEMA_VERSION_V7;

    /** Legacy v6 schema — READ-only tolerance (same 50 B width as v7; its {@code MISSILE_END}
     *  taxonomy is sourceless-only — the v7 delta is semantic, see
     *  {@code ServerGameplayEdgeEventMessage.MISSILE_END_SOURCED_MIN_SCHEMA}). */
    public static final short SCHEMA_VERSION_V6 = (short) ServerGameplayEdgeEventMessage.SCHEMA_VERSION_V6;

    /** Legacy v5 schema — READ-only tolerance (46 B: no {@code birthSpeed} tail float). */
    public static final short SCHEMA_VERSION_V5 = (short) ServerGameplayEdgeEventMessage.SCHEMA_VERSION_V5;

    /** Legacy v4 schema — READ-only tolerance (same width as v5; no {@code MISSILE_END} in its taxonomy). */
    public static final short SCHEMA_VERSION_V4 = (short) ServerGameplayEdgeEventMessage.SCHEMA_VERSION_V4;

    /** Legacy v3 schema — READ-only tolerance (same width as v4; no {@code MISSILE_SPLIT} in its taxonomy). */
    public static final short SCHEMA_VERSION_V3 = (short) ServerGameplayEdgeEventMessage.SCHEMA_VERSION_V3;

    /** Legacy v2 schema — READ-only tolerance (same width as v3; no {@code SYSTEM_JUMP} in its taxonomy). */
    public static final short SCHEMA_VERSION_V2 = (short) ServerGameplayEdgeEventMessage.SCHEMA_VERSION_V2;

    /** Legacy v1 schema — READ-only tolerance so archived {@code .ccwr} frames stay decodable. */
    public static final short SCHEMA_VERSION_V1 = (short) ServerGameplayEdgeEventMessage.SCHEMA_VERSION_V1;

    /** Registered message id (for traceability/diagnostics). */
    public static final String MESSAGE_ID = ServerGameplayEdgeEventMessage.MESSAGE_ID;

    /**
     * magic(int) + typeCode(int) + schema(short) + simTick(long) + entityId(long)
     * + kindCode(int) + phaseCode(int) + mountIndex(int) + aimFacingDeg(float)
     * + flightBearingDeg(float, v2) + birthSpeed(float, v6) + muzzleX(float, v8)
     * + muzzleY(float, v8) + targetWireId(long, v9).
     */
    public static final int FRAME_BYTES =
            Integer.BYTES + Integer.BYTES + Short.BYTES + Long.BYTES + Long.BYTES
                    + Integer.BYTES + Integer.BYTES + Integer.BYTES + Float.BYTES + Float.BYTES
                    + Float.BYTES + Float.BYTES + Float.BYTES + Long.BYTES;

    /** The v8 frame width ({@link #FRAME_BYTES} without the {@code targetWireId} tail long). */
    public static final int V8_FRAME_BYTES = FRAME_BYTES - Long.BYTES;

    /** The v6..v7 frame width ({@link #V8_FRAME_BYTES} additionally without the
     *  {@code muzzleX,muzzleY} pair). */
    public static final int V7_FRAME_BYTES = V8_FRAME_BYTES - Float.BYTES - Float.BYTES;

    /** The v2..v5 frame width ({@link #V7_FRAME_BYTES} additionally without {@code birthSpeed}). */
    public static final int V5_FRAME_BYTES = V7_FRAME_BYTES - Float.BYTES;

    /** The v1 frame width ({@link #V5_FRAME_BYTES} additionally without {@code flightBearingDeg}). */
    public static final int V1_FRAME_BYTES = V5_FRAME_BYTES - Float.BYTES;

    private GameplayEdgeEventCodec() {
    }

    /** Encode one gameplay edge event as a single fixed-width frame and flush it. */
    public static void writeEvent(OutputStream out, GameplayEdgeEvent event) throws IOException {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(event, "event");
        DataOutputStream data = new DataOutputStream(out);
        data.writeInt(MAGIC);
        data.writeInt(MESSAGE_TYPE_CODE);
        data.writeShort(SCHEMA_VERSION);
        data.writeLong(event.simTick());
        data.writeLong(event.entityId());
        data.writeInt(event.kind().wireCode());
        data.writeInt(event.phase().wireCode());
        data.writeInt(event.mountIndex());
        data.writeFloat(event.aimFacingDeg());
        data.writeFloat(event.flightBearingDeg());
        data.writeFloat(event.birthSpeed());
        data.writeFloat(event.muzzleX());
        data.writeFloat(event.muzzleY());
        data.writeLong(event.targetWireId());
        data.flush();
    }

    /** Encode one gameplay edge event into a freshly allocated byte array. */
    public static byte[] toBytes(GameplayEdgeEvent event) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(FRAME_BYTES);
        try {
            writeEvent(bytes, event);
        } catch (IOException e) {
            throw new IllegalStateException("in-memory gameplay edge event encode failed", e);
        }
        return bytes.toByteArray();
    }

    /**
     * Read exactly one gameplay-edge-event frame. Fail-closed: a truncated frame, a
     * wrong magic / type code / schema version, an unknown kind/phase code, or a
     * payload the event family rejects throws {@link IOException}.
     */
    public static GameplayEdgeEvent readEvent(InputStream in) throws IOException {
        Objects.requireNonNull(in, "in");
        DataInputStream data = new DataInputStream(in);
        int magic;
        try {
            magic = data.readInt();
        } catch (EOFException eof) {
            throw new IOException("no gameplay edge event frame on the wire (EOF before magic)", eof);
        }
        if (magic != MAGIC) {
            throw new IOException("gameplay edge event frame magic mismatch: expected "
                    + Integer.toHexString(MAGIC) + ", got " + Integer.toHexString(magic));
        }
        return readAfterMagic(data);
    }

    /** Drain every gameplay-edge-event frame from {@code in} until end of stream. */
    public static List<GameplayEdgeEvent> readEvents(InputStream in) throws IOException {
        List<GameplayEdgeEvent> events = new ArrayList<>();
        readEvents(in, events::add);
        return List.copyOf(events);
    }

    /** Drain every gameplay-edge-event frame from {@code in}, handing each to {@code consumer}. */
    public static void readEvents(InputStream in, Consumer<? super GameplayEdgeEvent> consumer)
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
                throw new IOException("gameplay edge event frame magic mismatch: " + Integer.toHexString(magic));
            }
            consumer.accept(readAfterMagic(data));
        }
    }

    private static GameplayEdgeEvent readAfterMagic(DataInputStream data) throws IOException {
        int typeCode = data.readInt();
        if (typeCode != MESSAGE_TYPE_CODE) {
            throw new IOException("gameplay edge event wire type code mismatch: expected "
                    + MESSAGE_TYPE_CODE + ", got " + typeCode);
        }
        short schema = data.readShort();
        if (schema != SCHEMA_VERSION && schema != SCHEMA_VERSION_V15 && schema != SCHEMA_VERSION_V14
                && schema != SCHEMA_VERSION_V13 && schema != SCHEMA_VERSION_V12
                && schema != SCHEMA_VERSION_V10 && schema != SCHEMA_VERSION_V9
                && schema != SCHEMA_VERSION_V8
                && schema != SCHEMA_VERSION_V7
                && schema != SCHEMA_VERSION_V6
                && schema != SCHEMA_VERSION_V5
                && schema != SCHEMA_VERSION_V4 && schema != SCHEMA_VERSION_V3
                && schema != SCHEMA_VERSION_V2 && schema != SCHEMA_VERSION_V1) {
            throw new IOException("gameplay edge event schema version mismatch: expected "
                    + SCHEMA_VERSION + " (or legacy " + SCHEMA_VERSION_V15 + "/" + SCHEMA_VERSION_V14 + "/"
                    + SCHEMA_VERSION_V13 + "/" + SCHEMA_VERSION_V12 + "/"
                    + SCHEMA_VERSION_V10 + "/" + SCHEMA_VERSION_V9 + "/"
                    + SCHEMA_VERSION_V8 + "/" + SCHEMA_VERSION_V7 + "/"
                    + SCHEMA_VERSION_V6 + "/" + SCHEMA_VERSION_V5 + "/"
                    + SCHEMA_VERSION_V4 + "/" + SCHEMA_VERSION_V3 + "/"
                    + SCHEMA_VERSION_V2 + "/" + SCHEMA_VERSION_V1 + "), got " + schema);
        }
        long simTick = data.readLong();
        long entityId = data.readLong();
        int kindCode = data.readInt();
        int phaseCode = data.readInt();
        int mountIndex = data.readInt();
        float aimFacingDeg = data.readFloat();
        // v2+ appends flightBearingDeg at tail; ONLY a legacy v1 frame carries no bearing
        // (NaN — the honest absence marker, so archived .ccwr recordings stay decodable).
        // NOT `schema == SCHEMA_VERSION`: that naive form silently NaN'd every v2 archive
        // at the v3 bump.
        float flightBearingDeg = schema != SCHEMA_VERSION_V1 ? data.readFloat() : Float.NaN;
        // v6+ appends birthSpeed after the bearing; a >= MIN_SCHEMA pin, not == SCHEMA_VERSION
        // (the same trap the bearing read above documents).
        float birthSpeed = schema >= ServerGameplayEdgeEventMessage.BIRTH_SPEED_MIN_SCHEMA
                ? data.readFloat() : Float.NaN;
        // v8+ appends the muzzleX,muzzleY pair after birthSpeed; same >= MIN_SCHEMA pin law.
        float muzzleX = Float.NaN;
        float muzzleY = Float.NaN;
        if (schema >= ServerGameplayEdgeEventMessage.MUZZLE_MIN_SCHEMA) {
            muzzleX = data.readFloat();
            muzzleY = data.readFloat();
        }
        // v9+ appends the targetWireId long after the muzzle pair; same >= MIN_SCHEMA pin
        // law — pre-v9 frames decode the canonical TARGET_ABSENT sentinel, never 0.
        long targetWireId = schema >= ServerGameplayEdgeEventMessage.TARGET_WIRE_ID_MIN_SCHEMA
                ? data.readLong() : GameplayEdgeEvent.TARGET_ABSENT;
        GameplayEdgeEventKind kind;
        try {
            kind = GameplayEdgeEventKind.fromWireCode(kindCode);
        } catch (IllegalArgumentException badKind) {
            throw new IOException("gameplay edge event kind code invalid: " + kindCode, badKind);
        }
        if (kind == GameplayEdgeEventKind.SYSTEM_JUMP
                && schema < ServerGameplayEdgeEventMessage.SYSTEM_JUMP_MIN_SCHEMA) {
            throw new IOException("gameplay edge event kind SYSTEM_JUMP in a schema " + schema
                    + " frame (kind requires schema >= "
                    + ServerGameplayEdgeEventMessage.SYSTEM_JUMP_MIN_SCHEMA + ")");
        }
        if (kind == GameplayEdgeEventKind.MISSILE_SPLIT
                && schema < ServerGameplayEdgeEventMessage.MISSILE_SPLIT_MIN_SCHEMA) {
            throw new IOException("gameplay edge event kind MISSILE_SPLIT in a schema " + schema
                    + " frame (kind requires schema >= "
                    + ServerGameplayEdgeEventMessage.MISSILE_SPLIT_MIN_SCHEMA + ")");
        }
        if (kind == GameplayEdgeEventKind.MISSILE_END
                && schema < ServerGameplayEdgeEventMessage.MISSILE_END_MIN_SCHEMA) {
            throw new IOException("gameplay edge event kind MISSILE_END in a schema " + schema
                    + " frame (kind requires schema >= "
                    + ServerGameplayEdgeEventMessage.MISSILE_END_MIN_SCHEMA + ")");
        }
        if (kind == GameplayEdgeEventKind.AUTOFIRE_TARGET
                && schema < ServerGameplayEdgeEventMessage.AUTOFIRE_TARGET_MIN_SCHEMA) {
            throw new IOException("gameplay edge event kind AUTOFIRE_TARGET in a schema " + schema
                    + " frame (kind requires schema >= "
                    + ServerGameplayEdgeEventMessage.AUTOFIRE_TARGET_MIN_SCHEMA + ")");
        }
        if (kind == GameplayEdgeEventKind.PALADIN_DET
                && schema < ServerGameplayEdgeEventMessage.PALADIN_DET_MIN_SCHEMA) {
            throw new IOException("gameplay edge event kind PALADIN_DET in a schema " + schema
                    + " frame (kind requires schema >= "
                    + ServerGameplayEdgeEventMessage.PALADIN_DET_MIN_SCHEMA + ")");
        }
        if (kind == GameplayEdgeEventKind.ARC_HIT
                && schema < ServerGameplayEdgeEventMessage.ARC_HIT_MIN_SCHEMA) {
            throw new IOException("gameplay edge event kind ARC_HIT in a schema " + schema
                    + " frame (kind requires schema >= "
                    + ServerGameplayEdgeEventMessage.ARC_HIT_MIN_SCHEMA + ")");
        }
        // The ARC_HIT gate cloned for kind 10.
        if (kind == GameplayEdgeEventKind.CRIT_MALFUNCTION
                && schema < ServerGameplayEdgeEventMessage.CRIT_MALFUNCTION_MIN_SCHEMA) {
            throw new IOException("gameplay edge event kind CRIT_MALFUNCTION in a schema " + schema
                    + " frame (kind requires schema >= "
                    + ServerGameplayEdgeEventMessage.CRIT_MALFUNCTION_MIN_SCHEMA + ")");
        }
        // The SEMANTIC pin — a pre-v15 MISSILE_END may neither name a victim
        // nor read SHIELD_ABSORBED (the MISSILE_END_SOURCED_MIN_SCHEMA idiom, enforced on read).
        if (kind == GameplayEdgeEventKind.MISSILE_END
                && schema < ServerGameplayEdgeEventMessage.MISSILE_END_VICTIM_MIN_SCHEMA
                && (mountIndex == GameplayEdgeEvent.END_CAUSE_SHIELD_ABSORBED
                        || targetWireId != GameplayEdgeEvent.TARGET_ABSENT)) {
            throw new IOException("gameplay edge event MISSILE_END victim form in a schema " + schema
                    + " frame (a named victim / END_CAUSE_SHIELD_ABSORBED requires schema >= "
                    + ServerGameplayEdgeEventMessage.MISSILE_END_VICTIM_MIN_SCHEMA + ")");
        }
        // The v16 SEMANTIC pin, cloned from the v15 one above —
        // a pre-v16 WEAPON_DISCHARGE may not carry a declared wait (its constructor forced the slot to
        // TARGET_ABSENT; pre-v9 frames decode TARGET_ABSENT above, so they always pass).
        if (kind == GameplayEdgeEventKind.WEAPON_DISCHARGE
                && schema < ServerGameplayEdgeEventMessage.DECLARED_WAIT_MIN_SCHEMA
                && targetWireId != GameplayEdgeEvent.TARGET_ABSENT) {
            throw new IOException("gameplay edge event WEAPON_DISCHARGE declared wait in a schema " + schema
                    + " frame (a declared wait requires schema >= "
                    + ServerGameplayEdgeEventMessage.DECLARED_WAIT_MIN_SCHEMA + ")");
        }
        GameplayEdgeEventPhase phase;
        try {
            phase = GameplayEdgeEventPhase.fromWireCode(phaseCode);
        } catch (IllegalArgumentException badPhase) {
            throw new IOException("gameplay edge event phase code invalid: " + phaseCode, badPhase);
        }
        try {
            return new GameplayEdgeEvent(
                    simTick, entityId, kind, phase, mountIndex, aimFacingDeg, flightBearingDeg,
                    birthSpeed, muzzleX, muzzleY, targetWireId);
        } catch (IllegalArgumentException badEvent) {
            throw new IOException("gameplay edge event fields invalid: " + badEvent.getMessage(), badEvent);
        }
    }

    private static int readByteOrThrow(DataInputStream data) throws IOException {
        int b = data.read();
        if (b < 0) {
            throw new EOFException("truncated gameplay edge event frame header");
        }
        return b & 0xff;
    }
}
