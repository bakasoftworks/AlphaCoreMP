package cc.starsector.client.net;

import java.io.IOException;
import java.io.InputStream;

/**
 * Client-side decoder for the server's {@code GPEE} gameplay-edge-event wire frame:
 * the receiving half of
 * {@code cc.starsector.server.runtime.GameplayEdgeEventCodec}.
 *
 * <p>Like {@link ClientDespawnEventReader}, the mod does not bundle the server codec
 * (it uses {@code java.io.DataInputStream}, which the Starsector mod-script sandbox
 * classloader blocks — see {@link WireBytes}); this reader reads exactly the
 * fixed-width bytes the server writes — magic / wire-type-code / schema / simTick /
 * entityId / kindCode / phaseCode / mountIndex / aimFacingDeg / flightBearingDeg — over plain
 * {@link InputStream} primitives. The game-free cross-check
 *
 * (archived) streams real server
 * {@code GameplayEdgeEventCodec} bytes (including the committed real-engine B1 G4
 * discharge edges) over a loopback socket and asserts this reader decodes the same
 * events, keeping the two mirrors in sync.
 *
 * <p>Fail-closed: a wrong magic (when read standalone), an unexpected schema version,
 * an unknown kind/phase code, a kind↔phase payload contradiction, or a truncated frame
 * throws {@link IOException}; a clean end-of-stream at a frame boundary returns
 * {@code null} from {@link #readEvent(InputStream)}. The wire type code is tolerated
 * (the client carries no registry), mirroring {@link ClientDespawnEventReader}.
 */
public final class ClientGameplayEdgeEventReader {

    /** Frame magic ("GPEE"). Mirrors {@code GameplayEdgeEventCodec.MAGIC}. */
    public static final int MAGIC = 0x47504545;

    /**
     * Schema version this reader understands. Mirrors {@code GameplayEdgeEventCodec.SCHEMA_VERSION}
     * (v16, told wait: SEMANTIC widening only, width UNCHANGED
     * 66 B — a {@code WEAPON_DISCHARGE} carries its DECLARED WAIT on {@code targetWireId}: the raw server
     * ticks from the consumed declaration's arrival to the discharge, {@code 0..255}, else
     * {@code TARGET_ABSENT}; the kit's lead drain reads it instead of a ping-based guess;
     * v15, phantom-DoT fix: SEMANTIC widening only, width UNCHANGED 66 B — a
     * {@code MISSILE_END} may name the VICTIM ship of the missile's last server-observed impact on
     * {@code targetWireId} ({@code > 0}, else {@code TARGET_ABSENT}) and its cause vocabulary gains
     * {@code END_CAUSE_SHIELD_ABSORBED(3)}; the devour end-hook mints a DoT ONLY on a named HULL contact;
     * v14, crit event lane: kind {@code CRIT_MALFUNCTION(10)}
     * added, width UNCHANGED 66 B — the server-observed critical malfunction (victim wire id,
     * module code on the mount slot, perm/branch/amount on the aim/bearing/birthSpeed slots);
     * v13, arc-hit lane: kind {@code ARC_HIT(9)} added, width UNCHANGED 66 B —
     * the server-named EMP-arc hit (source/victim wire ids, flavor code on the mount slot, node
     * x/y on the aim/bearing slots, the applied damage split on the birthSpeed/muzzle slots);
     * v12, mote-attractor target: SEMANTIC widening only, width UNCHANGED
     * 66 B — a {@code SYSTEM_WINDOW} ON edge may carry the attractor target world x/y on the
     * aim/bearing float slots + the locked ship's wire id (or {@code TARGET_NONE}) on
     * {@code targetWireId}; v11 = the retired D-BARREL number, skipped on purpose;
     * v10, Paladin-det lane: kind {@code PALADIN_DET(8)} added, width
     * UNCHANGED 66 B — the server-side {@code PaladinEffect} det marker, det x/y on the
     * aim/bearing float slots;
     * v9, autofire-target lane: {@code targetWireId} long appended, frame
     * 58→66 B + kind {@code AUTOFIRE_TARGET(7)} — the server's autofire pick on
     * {@code (entityId, mountIndex)}; every other kind carried {@code TARGET_ABSENT} at v9 (later
     * schemas re-use the slot per kind, named above);
     * v8, spawn-truth: {@code muzzleX}/{@code muzzleY}
     * appended, frame 50→58 B — the server-true birth position, both-finite-or-both-NaN;
     * v7: SEMANTIC widening only, bytes = v6's 50 B —
     * kind {@code MISSILE_END(6)} now confesses SOURCED missile ends too, so the pose
     * lane may retire a sourced puppet at the confessed end instead of absence-inferring;
     * v6: {@code birthSpeed} |v0| appended, frame 46→50 B; v5 =
     * kind {@code MISSILE_END(6)} sourceless-only, width unchanged; v4 =
     * kind {@code MISSILE_SPLIT(5)}; v3 = kind
     * {@code SYSTEM_JUMP(4)}; v2 =
     * {@code flightBearingDeg} appended). Exact-match fail-closed — unlike the server
     * codec this reader carries NO legacy tolerance: the live side-frame stream is always
     * current-schema (charter R5 posture) and kit lockstep pins every joiner to this build, so
     * "a v4 client never receives kind 6" holds by construction; only archived {@code .ccwr}
     * recordings need old-schema decode, and those are read server-side.
     */
    public static final int SCHEMA_VERSION = 16; // 15 -> 16, the WEAPON_DISCHARGE declared wait

    /** magic(4) + typeCode(4) + schema(2) + simTick(8) + entityId(8) + kind(4) + phase(4) + mount(4) + aim(4) + bearing(4) + birthSpeed(4) + muzzleX(4) + muzzleY(4) + targetWireId(8). */
    public static final int FRAME_BYTES = 4 + 4 + 2 + 8 + 8 + 4 + 4 + 4 + 4 + 4 + 4 + 4 + 4 + 8;

    private ClientGameplayEdgeEventReader() {
    }

    /**
     * Decode one gameplay-edge-event frame from {@code in}, or return {@code null} at a
     * clean end-of-stream (a frame boundary). Throws on a corrupt/truncated frame.
     */
    public static ClientGameplayEdgeEvent readEvent(InputStream in) throws IOException {
        int first = in.read();
        if (first < 0) {
            return null; // clean EOF at a frame boundary
        }
        int magic = ((first & 0xff) << 24)
                | (WireBytes.readUnsignedByte(in) << 16)
                | (WireBytes.readUnsignedByte(in) << 8)
                | WireBytes.readUnsignedByte(in);
        if (magic != MAGIC) {
            throw new IOException("gameplay edge event frame magic mismatch: " + Integer.toHexString(magic));
        }
        return readEventAfterMagic(in);
    }

    /**
     * Decode one gameplay-edge-event frame whose 4-byte {@link #MAGIC} has already been
     * read and validated by the caller — the entry point
     * {@link MultiplexedServerStreamReader} uses after peeking the leading magic.
     */
    public static ClientGameplayEdgeEvent readEventAfterMagic(InputStream in) throws IOException {
        WireBytes.readInt(in); // wire type code — tolerated (the client carries no registry)
        int schema = WireBytes.readUnsignedShort(in);
        if (schema != SCHEMA_VERSION) {
            throw new IOException("gameplay edge event schema version mismatch: expected "
                    + SCHEMA_VERSION + ", got " + schema);
        }
        long simTick = WireBytes.readLong(in);
        long entityId = WireBytes.readLong(in);
        int kindCode = WireBytes.readInt(in);
        int phaseCode = WireBytes.readInt(in);
        int mountIndex = WireBytes.readInt(in);
        float aimFacingDeg = Float.intBitsToFloat(WireBytes.readInt(in));
        float flightBearingDeg = Float.intBitsToFloat(WireBytes.readInt(in));
        float birthSpeed = Float.intBitsToFloat(WireBytes.readInt(in));
        float muzzleX = Float.intBitsToFloat(WireBytes.readInt(in));
        float muzzleY = Float.intBitsToFloat(WireBytes.readInt(in));
        long targetWireId = WireBytes.readLong(in);
        ClientGameplayEdgeEvent.Kind kind;
        ClientGameplayEdgeEvent.Phase phase;
        try {
            kind = ClientGameplayEdgeEvent.Kind.fromWireCode(kindCode);
            phase = ClientGameplayEdgeEvent.Phase.fromWireCode(phaseCode);
        } catch (IllegalArgumentException badCode) {
            throw new IOException("gameplay edge event kind/phase code invalid: kind=" + kindCode
                    + " phase=" + phaseCode, badCode);
        }
        try {
            return new ClientGameplayEdgeEvent(simTick, entityId, kind, phase, mountIndex,
                    aimFacingDeg, flightBearingDeg, birthSpeed, muzzleX, muzzleY, targetWireId);
        } catch (IllegalArgumentException badEvent) {
            throw new IOException("gameplay edge event fields invalid: " + badEvent.getMessage(), badEvent);
        }
    }
}
