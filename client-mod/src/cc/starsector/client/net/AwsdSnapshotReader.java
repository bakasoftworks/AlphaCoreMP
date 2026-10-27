package cc.starsector.client.net;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Stateless client-side reader for the {@code AWSD} delta+keyframe world-snapshot family:
 * a field-for-field hand-mirror of the server codec
 * ({@code cc.starsector.server.runtime.AwsdSnapshotCodec}) built on {@link WireBytes} only —
 * the game's script classloader (loader B) denies {@code java.io.Data*Stream}s to mod code,
 * so the server's {@code DataInputStream} walk cannot be shared. The cross-family golden
 * vectors ({@code local\goldens\awsd-vectors\}) pin both sides to the same bytes.
 *
 * <p><b>Strict fail-closed versioning (D11 stale-kit posture):</b> unlike {@link AwssSnapshotReader}
 * (whose appended-tier schema walk makes forward tolerance meaningful), AWSD has no
 * appended-tier structure — a version this reader does not know is undecodable, and ships
 * ride AWSD-only while the delta lane is ON, so skipping frames would silently freeze every
 * ship. A schema other than {@link #SCHEMA_VERSION} therefore throws loudly —
 * SINGLE-version by design; the one legacy exception (v8 accepted totals-less) was
 * DROPPED at v10 (min==max kit lockstep — the fix is
 * a kit re-mint, never a degraded render). The wire-type-code field is read and
 * tolerated (the client bundles no message registry — {@code AwssSnapshotReader}
 * precedent).
 *
 * <p><b>Own section/field constants:</b> the DELTA section mask reuses the server
 * fragment-marker tier bit VOCABULARY plus {@code SECTION_POSE}=64 — but the values are
 * declared here and never routed through {@link ServerFragmentMarker}, whose constructor
 * fails closed on mask bits above 0x3F (POSE would be an illegal tier there by design).
 *
 * <p><b>Quantized-space truth (D4):</b> {@link #applyDelta} accumulates integer grid steps
 * against the anchor keyframe and {@link #resolvePose}/{@link #resolveMounts} mint domain
 * floats with exactly the server's {@code resolve*} arithmetic, so the reconstruction is
 * bit-exactly the server's quantized values — floats never round-trip, grid steps do.
 * Like the AWSS decode path, {@code hardFluxFrac}/{@code fluxFlags} are minted into the
 * pose ({@code hardFlux} reconstructed server-exact as
 * {@code (hardFluxFrac/255)×maxFlux}; the packed flags carried raw) for the foe-flux
 * apply lane; they also still ride the decoded {@link AwsdKeyframe}.
 *
 * <p>The stateful half — anchor adoption, epoch-guarded delta resolve, fold — is owned by
 * {@link SnapshotChunkMerger} (step-4 seam B); this class stays static and game-free so the
 * decode verifier can drive it against the frozen golden bytes.
 */
public final class AwsdSnapshotReader {

    public static final int MAGIC = 0x41575344; // "AWSD"
    /**
     * The current schema — strict single-version by design (see class doc).
     * Mirrors {@code AwsdSnapshotMessage.SCHEMA_VERSION}.
     * v12 = the fighter-replacement percent: ONE u8 after the CR block on
     * the KEYFRAME only (0..100 | 0xFF absent; deltas hold-last) - the served fighter
     * panel's rate line ({@code ServerEntityPose.fighterReplacementPct()});
     * v11 = the armor cell-run tail (D-FOEARMOR; law):
     * EVERY record — both kinds — ends with a cell
     * section {@code u8 runCount} + per run {@code u16 startIdx + u8 len + len×u8
     * values} (absolute quantized cells, ordinal x·h+y; caps runCount ≤ 12, len 1..32,
     * section ≤ 96 B, structurally fail-closed whole-frame; the semantic
     * {@code startIdx+len ≤ cellCount} check is the APPLY layer's per-entity refusal —
     * the reader has no grid);
     * v10 = the armor keyframe tier (u8 {@code armorFrac} appended after
     * {@code hullFrac}, KEYFRAME 62→63 B, + the {@link #POSE_ARMOR_FRAC} delta bit —
     * absolute u8 when dirty; NO min-1 floor: armor is legitimately 0, the min-1
     * clamp is hull-only);
     * v9 = the side-totals mirror block (20 B appended AFTER the records:
     * per side {coordTotal, coordMax, ewTotal, ewMax, cpRate} × 2 sides as u16
     * fixed-point ×100 — the server-authoritative Coordinated-Maneuvers /
     * Electronic-Warfare / CommRelay side aggregates a partial replica roster cannot
     * derive; RAW totals cross, never applied artifacts; memoryless ⇒ join converges
     * in 1 frame);
     * v8 = the sparse system tier (zero new bytes; the sysStateCode u8
     * vocabulary grows the 0xFF {@link #SYS_NONE_WIRE} absent sentinel — armor modules
     * carry no ship system — with the canonical absent shape ctor-enforced, the
     * cloak-tier idiom);
     * v7 = the maxHp u16→i32 widen (KEYFRAME 60→62 B; station3 base
     * carries 160000 HP — u16 refused the whole fortress shape into AWSS fallback);
     * v6 = the ship-CR KEYFRAME tier (5-B CR block appended after the cloak
     * block, KEYFRAME 55→60 B; deltas hold-last; peakHalfSecU16 == 0 = the absent-CR
     * sentinel — resolvePose does not mint); v5 = the leg-6 sim-speed carrier
     * (body-prefix reserved u16 re-meant as {@code simSpeedPermille}, 1000 = 1.0×; zero
     * new bytes); v4 = the shield-facing tier (u8 appended to the flux
     * section, KEYFRAME 54→55 B); v3 = the phase-cloak KEYFRAME tier (5-B
     * cloak block, KEYFRAME 49→54 B; deltas hold-last); v2 = the
     * pose-grid widen.
     */
    public static final int SCHEMA_VERSION = 12;
    public static final int HEADER_BYTES = 16;
    /** Body bytes preceding the records: simTick u64 + recordCount u16 + simSpeedPermille u16. */
    public static final int BODY_PREFIX_BYTES = 12;
    /**
     * Bounded allocation:
     * every AWSD frame decodes from ONE datagram's raw payload (≤1200 B on the wire,
     * ≤{@code SnapDeflateCodec.MAX_RAW_BYTES} after the CAWZ inflate-bomb guard), so a declared
     * body past that bound is forged or corrupt, never legitimate — refused BEFORE the array is
     * sized, so a wire i32 can never pick the client's heap size.
     */
    public static final int MAX_BODY_BYTES =
            cc.starsector.server.runtime.SnapDeflateCodec.MAX_RAW_BYTES;
    /** v9 trailing side-totals block: per side 5 × u16 centi fields, side 0 then side 1. */
    public static final int SIDE_TOTALS_BYTES = 10 * Short.BYTES;

    // v11 cell-section caps — mirror AwsdSnapshotCodec (structural fail-closed at decode).
    public static final int CELL_RUNS_MAX_PER_RECORD = 12;
    public static final int CELL_RUN_LEN_MAX = 32;
    public static final int CELL_BYTES_PER_RECORD_MAX = 96;
    public static final int CELL_SECTION_MIN_BYTES = 1;

    /**
     * v5 sim-speed carrier bounds — mirror {@code AwsdSnapshotCodec.SIM_SPEED_PERMILLE_*}:
     * the announced pacing multiplier in permille, 1000 = 1.0×;
     * out-of-range fails the frame closed like every other decode fault.
     */
    public static final int SIM_SPEED_PERMILLE_MIN = 250;
    public static final int SIM_SPEED_PERMILLE_MAX = 2000;
    public static final int SIM_SPEED_PERMILLE_ONE = 1000;

    /** Record kind: fixed-width compact absolute anchor (establishes the epoch). */
    public static final int KIND_KEYFRAME = 0;
    /** Record kind: mask-driven cumulative grid-step delta vs the named anchor epoch. */
    public static final int KIND_DELTA = 1;

    // DELTA section bits (server fragment-marker tier vocabulary + POSE; declared here —
    // never route through ServerFragmentMarker, whose ctor rejects bits above 0x3F).
    public static final int SECTION_FLUX = 1;
    public static final int SECTION_MOUNT = 1 << 1;
    public static final int SECTION_ALLEGIANCE = 1 << 2;
    public static final int SECTION_ENGINE = 1 << 3;
    public static final int SECTION_MANEUVER = 1 << 4;
    public static final int SECTION_SYSTEM = 1 << 5;
    public static final int SECTION_POSE = 1 << 6;
    private static final int ALL_SECTION_BITS = 0x7F;

    // Per-section field-mask bits (fields serialize in ascending bit order).
    public static final int POSE_X = 1;
    public static final int POSE_Y = 1 << 1;
    public static final int POSE_VX = 1 << 2;
    public static final int POSE_VY = 1 << 3;
    public static final int POSE_FACING = 1 << 4;
    public static final int POSE_ANG_VEL = 1 << 5;
    /** Absolute u8 (the fraction-family law: 1 B always, no anchor dependence). */
    public static final int POSE_HULL_FRAC = 1 << 6;
    /** Absolute u8 (v10 fraction family; NO min-1 floor — armor is legitimately 0). */
    public static final int POSE_ARMOR_FRAC = 1 << 7;
    private static final int POSE_FIELD_BITS = 0xFF;
    public static final int FLUX_LEVEL = 1;
    public static final int FLUX_HARD_FRAC = 1 << 1;
    public static final int FLUX_FLAGS = 1 << 2;
    /** Absolute u8 (v4): quantized shield facing, 360/256° steps; 0 when
     * shieldless — consumers gate on the HAS_SHIELD flux flag, never on this byte. */
    public static final int FLUX_SHIELD_FACING = 1 << 3;
    private static final int FLUX_FIELD_BITS = 0x0F;
    public static final int MOUNT_CD_FRAC = 1;
    public static final int MOUNT_CHARGE = 1 << 1;
    public static final int MOUNT_FLAGS = 1 << 2;
    /** i8 cumulative steps + escape (the one non-fraction dynamic mount field). */
    public static final int MOUNT_AMMO = 1 << 3;
    private static final int MOUNT_FIELD_BITS = 0x0F;
    public static final int ENGINE_DISABLED = 1;
    public static final int ENGINE_PERM_DISABLED = 1 << 1;
    public static final int ENGINE_CONTROLLER = 1 << 2;
    private static final int ENGINE_FIELD_BITS = 0x07;
    public static final int SYS_STATE = 1;
    public static final int SYS_EFFECT = 1 << 1;
    public static final int SYS_CD = 1 << 2;
    public static final int SYS_AMMO = 1 << 3;
    private static final int SYS_FIELD_BITS = 0x0F;

    // Quantization grids (resolve = steps*GRID — the server's exact float constants).
    public static final float POS_GRID_SU = 0.25f;
    public static final float VEL_GRID = 0.5f;
    public static final float FACING_GRID_DEG = 0.5f;
    public static final float ANG_VEL_GRID = 0.5f;
    public static final float SYS_CD_GRID_S = 0.1f;
    public static final float MOUNT_CD_GRID_S = 0.01f;
    /** v6 CR-clock grid: dep/peak seconds @0.5 s, u16 saturated server-side
     * at 0xFFFF (never-lose-CR hulls carry effective peaks ≥ 1e5 s by design). */
    public static final float CR_TIME_GRID_S = 0.5f;
    /** Facing steps per full turn at the 0.5° grid; keyframe facing = u16 in [0,720). */
    public static final int FACING_STEPS_PER_TURN = 720;

    /** Wire {@code ownerWire} value carrying no-owner (domain −1). */
    public static final int OWNER_NONE_WIRE = 0xFFFF;
    /** Wire {@code sysAmmoWire} value carrying the no-ammo sentinel (domain −1). */
    public static final int SYS_AMMO_NONE_WIRE = 0xFF;
    /** Wire {@code sysStateCode} value for a ship with NO ship system (v8: the
     * system tier is SPARSE — vanilla armor modules ship an empty system id column — so
     * absence rides in-band with the canonical absent shape 0xFF/0/0/0xFF, ctor-enforced;
     * the cloak-tier idiom). */
    public static final int SYS_NONE_WIRE = 0xFF;
    /** Wire {@code cloakStateWire} value for a no-cloak ship (v3: the cloak tier
     * is SPARSE; absence rides in-band with the canonical absent shape, ctor-enforced). */
    public static final int CLOAK_NONE_WIRE = 0xFF;
    /** Wire {@code cloakAmmoWire} value carrying the no-charge cloak sentinel (domain −1). */
    public static final int CLOAK_AMMO_NONE_WIRE = 0xFF;
    private static final int NO_OWNER_CONNECTION = -1;
    private static final int NO_SYSTEM_AMMO = -1;

    private AwsdSnapshotReader() {
    }

    // ------------------------------------------------------------------ frame I/O

    /**
     * Decode one AWSD frame from {@code in}, or return {@code null} at a clean
     * end-of-stream (the frame boundary). Throws on a corrupt, truncated, or
     * unknown-schema frame — never a silent skip (see class doc).
     */
    public static AwsdFrame readFrame(InputStream in) throws IOException {
        int first = in.read();
        if (first < 0) {
            return null; // clean EOF at a frame boundary
        }
        int magic = (first << 24)
                | (WireBytes.readUnsignedByte(in) << 16)
                | (WireBytes.readUnsignedByte(in) << 8)
                | WireBytes.readUnsignedByte(in);
        if (magic != MAGIC) {
            throw new IOException("not an AWSD frame: magic 0x" + Integer.toHexString(magic));
        }
        return readFrameAfterMagic(in);
    }

    /**
     * Decode one frame whose 4-byte {@link #MAGIC} has <em>already</em> been read and
     * validated by the caller (the {@link MultiplexedServerStreamReader} demux entry) —
     * the rest of the header, body, and CRC, all fail-closed with {@link IOException}.
     * Decoded keyframes carry {@code anchorTick} = the frame's simTick.
     */
    public static AwsdFrame readFrameAfterMagic(InputStream in) throws IOException {
        int schemaVersion = WireBytes.readUnsignedShort(in);
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IOException("unsupported AWSD schema v" + schemaVersion
                    + " (strict single-version v" + SCHEMA_VERSION
                    + " reader: ships ride AWSD-only while the delta lane is ON,"
                    + " so a skipped frame would silently freeze every ship — this client kit"
                    + " is stale vs the server, re-mint required)");
        }
        int headerBytes = WireBytes.readUnsignedShort(in);
        if (headerBytes != HEADER_BYTES) {
            throw new IOException("AWSD frame header size mismatch: " + headerBytes);
        }
        WireBytes.readInt(in); // wire type code — tolerated (no client-side registry)
        int bodyBytes = WireBytes.readInt(in);
        if (bodyBytes < BODY_PREFIX_BYTES || bodyBytes > MAX_BODY_BYTES) {
            throw new IOException("AWSD body size outside [" + BODY_PREFIX_BYTES + ", "
                    + MAX_BODY_BYTES + "]: " + bodyBytes);
        }
        byte[] body = new byte[bodyBytes];
        WireBytes.readFully(in, body);
        int expectedCrc = WireBytes.readInt(in);
        CRC32 crc = new CRC32();
        crc.update(body, 0, body.length);
        if ((int) crc.getValue() != expectedCrc) {
            throw new IOException("AWSD frame checksum mismatch");
        }
        return decodeBody(body);
    }

    private static AwsdFrame decodeBody(byte[] body) throws IOException {
        ByteArrayInputStream data = new ByteArrayInputStream(body);
        long simTick = WireBytes.readLong(data);
        int recordCount = WireBytes.readUnsignedShort(data);
        int simSpeedPermille = WireBytes.readUnsignedShort(data);
        if (simSpeedPermille < SIM_SPEED_PERMILLE_MIN
                || simSpeedPermille > SIM_SPEED_PERMILLE_MAX) {
            throw new IOException("AWSD simSpeedPermille outside ["
                    + SIM_SPEED_PERMILLE_MIN + ", " + SIM_SPEED_PERMILLE_MAX + "]: "
                    + simSpeedPermille);
        }
        List<AwsdRecord> records = new ArrayList<>(recordCount);
        for (int i = 0; i < recordCount; i++) {
            records.add(readRecord(data, simTick));
        }
        if (data.available() < SIDE_TOTALS_BYTES) {
            throw new IOException("AWSD body truncated before its side-totals block: "
                    + data.available() + " of " + SIDE_TOTALS_BYTES + " bytes");
        }
        SideTotalsBlock sideTotals =
                new SideTotalsBlock(readSideTotals(data), readSideTotals(data));
        if (data.available() != 0) {
            throw new IOException("AWSD body carries " + data.available()
                    + " trailing bytes after its side-totals block");
        }
        return new AwsdFrame(simTick, simSpeedPermille, records, sideTotals);
    }

    private static SideTotals readSideTotals(InputStream data) throws IOException {
        return new SideTotals(
                WireBytes.readUnsignedShort(data),
                WireBytes.readUnsignedShort(data),
                WireBytes.readUnsignedShort(data),
                WireBytes.readUnsignedShort(data),
                WireBytes.readUnsignedShort(data));
    }

    private static AwsdRecord readRecord(InputStream data, long simTick) throws IOException {
        int entityId = WireBytes.readUnsignedShort(data);
        int kind = WireBytes.readUnsignedByte(data);
        int epoch = WireBytes.readUnsignedByte(data);
        if (kind == KIND_KEYFRAME) {
            AwsdKeyframe keyframe = readKeyframeFields(data, entityId, epoch, simTick);
            return new AwsdRecord(entityId, KIND_KEYFRAME, epoch, keyframe, null,
                    readCellSection(data));
        }
        if (kind == KIND_DELTA) {
            AwsdDelta delta = readDeltaFields(data);
            return new AwsdRecord(entityId, KIND_DELTA, epoch, null, delta,
                    readCellSection(data));
        }
        throw new IOException("unknown AWSD record kind " + kind + " for entity " + entityId);
    }

    private static AwsdKeyframe readKeyframeFields(
            InputStream data, int entityId, int epoch, long simTick) throws IOException {
        int contentId = WireBytes.readUnsignedShort(data);
        int ownerWire = WireBytes.readUnsignedShort(data);
        int qx = WireBytes.readInt(data);
        int qy = WireBytes.readInt(data);
        int qvx = WireBytes.readShort(data);
        int qvy = WireBytes.readShort(data);
        int qFacing = WireBytes.readUnsignedShort(data);
        int qAngVel = WireBytes.readShort(data);
        // v7: i32 read — a negative value (a u32-style top-bit pattern the server encoder
        // never writes) fails the keyframe ctor's positive-domain check fail-closed.
        int maxHp = WireBytes.readInt(data);
        int hullFrac = WireBytes.readUnsignedByte(data);
        int armorFrac = WireBytes.readUnsignedByte(data);
        int maxFlux = WireBytes.readUnsignedShort(data);
        int fluxLevel = WireBytes.readUnsignedByte(data);
        int hardFluxFrac = WireBytes.readUnsignedByte(data);
        int fluxFlags = WireBytes.readUnsignedByte(data);
        int shieldFacingU8 = WireBytes.readUnsignedByte(data);
        int fleetSide = WireBytes.readUnsignedByte(data);
        int engineDisabledMask = WireBytes.readInt(data);
        int enginePermDisabledMask = WireBytes.readInt(data);
        int engineControllerState = WireBytes.readUnsignedByte(data);
        int maneuverMask = WireBytes.readUnsignedByte(data);
        int sysStateCode = WireBytes.readUnsignedByte(data);
        int sysEffectLevel = WireBytes.readUnsignedByte(data);
        int qSysCd = WireBytes.readUnsignedShort(data);
        int sysAmmoWire = WireBytes.readUnsignedByte(data);
        int cloakStateWire = WireBytes.readUnsignedByte(data);
        int cloakEffectFrac = WireBytes.readUnsignedByte(data);
        int qCloakCd = WireBytes.readUnsignedShort(data);
        int cloakAmmoWire = WireBytes.readUnsignedByte(data);
        int crU8 = WireBytes.readUnsignedByte(data);
        int depHalfSecU16 = WireBytes.readUnsignedShort(data);
        int peakHalfSecU16 = WireBytes.readUnsignedShort(data);
        int fighterRatePct = WireBytes.readUnsignedByte(data);
        int mountCount = WireBytes.readUnsignedByte(data);
        List<AwsdKeyframe.Mount> mounts = new ArrayList<>(mountCount);
        try {
            for (int i = 0; i < mountCount; i++) {
                mounts.add(new AwsdKeyframe.Mount(
                        WireBytes.readUnsignedByte(data),
                        WireBytes.readUnsignedShort(data),
                        WireBytes.readUnsignedShort(data),
                        WireBytes.readUnsignedShort(data),
                        WireBytes.readUnsignedShort(data),
                        WireBytes.readUnsignedByte(data),
                        WireBytes.readUnsignedByte(data),
                        WireBytes.readUnsignedByte(data)));
            }
            return new AwsdKeyframe(
                    entityId, epoch, simTick, contentId, ownerWire,
                    qx, qy, qvx, qvy, qFacing, qAngVel,
                    maxHp, hullFrac, armorFrac, maxFlux, fluxLevel, hardFluxFrac, fluxFlags,
                    shieldFacingU8, fleetSide,
                    engineDisabledMask, enginePermDisabledMask, engineControllerState,
                    maneuverMask, sysStateCode, sysEffectLevel, qSysCd, sysAmmoWire,
                    cloakStateWire, cloakEffectFrac, qCloakCd, cloakAmmoWire,
                    crU8, depHalfSecU16, peakHalfSecU16, fighterRatePct, mounts);
        } catch (IllegalArgumentException corrupt) {
            throw new IOException(
                    "AWSD keyframe for entity " + entityId + " failed wire validation", corrupt);
        }
    }

    private static AwsdDelta readDeltaFields(InputStream data) throws IOException {
        int sectionMask = WireBytes.readUnsignedByte(data);
        if ((sectionMask & ~ALL_SECTION_BITS) != 0) {
            throw new IOException("AWSD delta sectionMask carries unknown bits: 0x"
                    + Integer.toHexString(sectionMask));
        }
        int fluxFieldMask = 0;
        int fluxLevel = 0;
        int hardFluxFrac = 0;
        int fluxFlags = 0;
        int shieldFacingU8 = 0;
        if ((sectionMask & SECTION_FLUX) != 0) {
            fluxFieldMask = readFieldMask(data, FLUX_FIELD_BITS, "flux");
            if ((fluxFieldMask & FLUX_LEVEL) != 0) {
                fluxLevel = WireBytes.readUnsignedByte(data);
            }
            if ((fluxFieldMask & FLUX_HARD_FRAC) != 0) {
                hardFluxFrac = WireBytes.readUnsignedByte(data);
            }
            if ((fluxFieldMask & FLUX_FLAGS) != 0) {
                fluxFlags = WireBytes.readUnsignedByte(data);
            }
            if ((fluxFieldMask & FLUX_SHIELD_FACING) != 0) {
                shieldFacingU8 = WireBytes.readUnsignedByte(data);
            }
        }
        List<MountDelta> mountDeltas = new ArrayList<>();
        if ((sectionMask & SECTION_MOUNT) != 0) {
            int dirtyCount = WireBytes.readUnsignedByte(data);
            if (dirtyCount == 0) {
                throw new IOException("AWSD mount section with zero dirty mounts"
                        + " (the encoder never mints an empty dirty section)");
            }
            for (int i = 0; i < dirtyCount; i++) {
                int mountIndex = WireBytes.readUnsignedByte(data);
                int fieldMask = readFieldMask(data, MOUNT_FIELD_BITS, "mount");
                if (fieldMask == 0) {
                    throw new IOException("AWSD dirty mount " + mountIndex + " with empty field mask");
                }
                int cdFrac = (fieldMask & MOUNT_CD_FRAC) != 0 ? WireBytes.readUnsignedByte(data) : 0;
                int chargeFrac = (fieldMask & MOUNT_CHARGE) != 0 ? WireBytes.readUnsignedByte(data) : 0;
                int flags = (fieldMask & MOUNT_FLAGS) != 0 ? WireBytes.readUnsignedByte(data) : 0;
                int ammoSteps = (fieldMask & MOUNT_AMMO) != 0 ? readSteps(data) : 0;
                mountDeltas.add(new MountDelta(mountIndex, fieldMask, cdFrac, chargeFrac, flags, ammoSteps));
            }
        }
        int fleetSide = (sectionMask & SECTION_ALLEGIANCE) != 0 ? WireBytes.readUnsignedByte(data) : 0;
        int engineFieldMask = 0;
        int engineDisabledMask = 0;
        int enginePermDisabledMask = 0;
        int engineControllerState = 0;
        if ((sectionMask & SECTION_ENGINE) != 0) {
            engineFieldMask = readFieldMask(data, ENGINE_FIELD_BITS, "engine");
            if ((engineFieldMask & ENGINE_DISABLED) != 0) {
                engineDisabledMask = WireBytes.readInt(data);
            }
            if ((engineFieldMask & ENGINE_PERM_DISABLED) != 0) {
                enginePermDisabledMask = WireBytes.readInt(data);
            }
            if ((engineFieldMask & ENGINE_CONTROLLER) != 0) {
                engineControllerState = WireBytes.readUnsignedByte(data);
            }
        }
        int maneuverMask = (sectionMask & SECTION_MANEUVER) != 0 ? WireBytes.readUnsignedByte(data) : 0;
        int sysFieldMask = 0;
        int sysStateCode = 0;
        int sysEffectLevel = 0;
        int sysCdSteps = 0;
        int sysAmmoWire = 0;
        if ((sectionMask & SECTION_SYSTEM) != 0) {
            sysFieldMask = readFieldMask(data, SYS_FIELD_BITS, "system");
            if ((sysFieldMask & SYS_STATE) != 0) {
                sysStateCode = WireBytes.readUnsignedByte(data);
            }
            if ((sysFieldMask & SYS_EFFECT) != 0) {
                sysEffectLevel = WireBytes.readUnsignedByte(data);
            }
            if ((sysFieldMask & SYS_CD) != 0) {
                sysCdSteps = readSteps(data);
            }
            if ((sysFieldMask & SYS_AMMO) != 0) {
                sysAmmoWire = WireBytes.readUnsignedByte(data);
            }
        }
        int poseFieldMask = 0;
        int dxSteps = 0;
        int dySteps = 0;
        int dvxSteps = 0;
        int dvySteps = 0;
        int dFacingSteps = 0;
        int dAngVelSteps = 0;
        int hullFrac = 0;
        int armorFrac = 0;
        if ((sectionMask & SECTION_POSE) != 0) {
            poseFieldMask = readFieldMask(data, POSE_FIELD_BITS, "pose");
            if ((poseFieldMask & POSE_X) != 0) {
                dxSteps = readSteps(data);
            }
            if ((poseFieldMask & POSE_Y) != 0) {
                dySteps = readSteps(data);
            }
            if ((poseFieldMask & POSE_VX) != 0) {
                dvxSteps = readSteps(data);
            }
            if ((poseFieldMask & POSE_VY) != 0) {
                dvySteps = readSteps(data);
            }
            if ((poseFieldMask & POSE_FACING) != 0) {
                dFacingSteps = readSteps(data);
            }
            if ((poseFieldMask & POSE_ANG_VEL) != 0) {
                dAngVelSteps = readSteps(data);
            }
            if ((poseFieldMask & POSE_HULL_FRAC) != 0) {
                hullFrac = WireBytes.readUnsignedByte(data);
            }
            if ((poseFieldMask & POSE_ARMOR_FRAC) != 0) {
                armorFrac = WireBytes.readUnsignedByte(data);
            }
        }
        return new AwsdDelta(
                sectionMask,
                poseFieldMask, dxSteps, dySteps, dvxSteps, dvySteps, dFacingSteps, dAngVelSteps,
                hullFrac,
                armorFrac,
                fluxFieldMask, fluxLevel, hardFluxFrac, fluxFlags, shieldFacingU8,
                mountDeltas,
                fleetSide,
                engineFieldMask, engineDisabledMask, enginePermDisabledMask, engineControllerState,
                maneuverMask,
                sysFieldMask, sysStateCode, sysEffectLevel, sysCdSteps, sysAmmoWire);
    }

    /**
     * Read one v11 cell section (structural fail-closed: runCount > {@value
     * #CELL_RUNS_MAX_PER_RECORD}, len outside 1..{@value #CELL_RUN_LEN_MAX}, or section
     * bytes > {@value #CELL_BYTES_PER_RECORD_MAX} ⇒ {@link IOException} = whole-frame
     * refusal — the stream position is unknowable past a malformed section). The semantic
     * {@code startIdx+len ≤ cellCount} check is the APPLY layer's per-entity refusal
     * (the reader has no grid). Hand-mirror of {@code AwsdSnapshotCodec.readCellSection}.
     */
    private static List<CellRun> readCellSection(InputStream data) throws IOException {
        int runCount = WireBytes.readUnsignedByte(data);
        if (runCount > CELL_RUNS_MAX_PER_RECORD) {
            throw new IOException("AWSD cell section runCount over the cap "
                    + CELL_RUNS_MAX_PER_RECORD + ": " + runCount);
        }
        List<CellRun> runs = new ArrayList<>(runCount);
        int sectionBytes = CELL_SECTION_MIN_BYTES;
        for (int i = 0; i < runCount; i++) {
            int startIdx = WireBytes.readUnsignedShort(data);
            int len = WireBytes.readUnsignedByte(data);
            if (len < 1 || len > CELL_RUN_LEN_MAX) {
                throw new IOException("AWSD cell run len outside [1," + CELL_RUN_LEN_MAX
                        + "]: " + len);
            }
            sectionBytes += 3 + len;
            if (sectionBytes > CELL_BYTES_PER_RECORD_MAX) {
                throw new IOException("AWSD cell section over the byte cap "
                        + CELL_BYTES_PER_RECORD_MAX + ": " + sectionBytes);
            }
            byte[] values = new byte[len];
            WireBytes.readFully(data, values);
            runs.add(new CellRun(startIdx, values));
        }
        return runs;
    }

    // ------------------------------------------------------------------ apply/resolve

    /**
     * Apply a DELTA record to its anchor, producing the entity's CURRENT quantized state
     * (same epoch/anchorTick — derived state, never a new anchor). Deltas are CUMULATIVE
     * vs the anchor keyframe, so applying an age-k delta directly is identical to having
     * applied every intermediate delta. Throws {@code IllegalArgumentException} on an
     * entity or epoch mismatch — the D5 drop signal the merger turns into
     * hold-last (+ NACK later), and on a dirty mount index absent from the anchor roster
     * (roster changes always ride a keyframe). Mirrors the server codec exactly.
     */
    public static AwsdKeyframe applyDelta(AwsdRecord record, AwsdKeyframe anchor) {
        if (record == null || anchor == null) {
            throw new IllegalArgumentException("record and anchor are required");
        }
        if (record.kind() != KIND_DELTA) {
            throw new IllegalArgumentException("applyDelta requires a DELTA record");
        }
        if (record.entityId() != anchor.entityId()) {
            throw new IllegalArgumentException("delta names entity " + record.entityId()
                    + " but the anchor is entity " + anchor.entityId() + " (D5 drop)");
        }
        if (record.epoch() != anchor.epoch()) {
            throw new IllegalArgumentException("delta names anchor epoch " + record.epoch()
                    + " but the anchor is epoch " + anchor.epoch() + " (D5 drop)");
        }
        AwsdDelta delta = record.delta();
        int sectionMask = delta.sectionMask();
        List<AwsdKeyframe.Mount> mounts = anchor.mounts();
        if ((sectionMask & SECTION_MOUNT) != 0) {
            List<AwsdKeyframe.Mount> updated = new ArrayList<>(mounts);
            for (MountDelta mountDelta : delta.mountDeltas()) {
                int slot = -1;
                for (int i = 0; i < updated.size(); i++) {
                    if (updated.get(i).mountIndex() == mountDelta.mountIndex()) {
                        slot = i;
                        break;
                    }
                }
                if (slot < 0) {
                    throw new IllegalArgumentException("delta names mount index "
                            + mountDelta.mountIndex() + " absent from the anchor roster"
                            + " (roster changes ride keyframes)");
                }
                AwsdKeyframe.Mount a = updated.get(slot);
                updated.set(slot, new AwsdKeyframe.Mount(
                        a.mountIndex(),
                        a.weaponContentId(),
                        a.qCooldownMax(),
                        a.maxAmmo(),
                        (mountDelta.fieldMask() & MOUNT_AMMO) != 0
                                ? a.ammo() + mountDelta.ammoSteps() : a.ammo(),
                        (mountDelta.fieldMask() & MOUNT_CD_FRAC) != 0
                                ? mountDelta.cdFrac() : a.cdFrac(),
                        (mountDelta.fieldMask() & MOUNT_CHARGE) != 0
                                ? mountDelta.chargeFrac() : a.chargeFrac(),
                        (mountDelta.fieldMask() & MOUNT_FLAGS) != 0
                                ? mountDelta.flags() : a.flags()));
            }
            mounts = updated;
        }
        boolean pose = (sectionMask & SECTION_POSE) != 0;
        boolean flux = (sectionMask & SECTION_FLUX) != 0;
        boolean engine = (sectionMask & SECTION_ENGINE) != 0;
        boolean system = (sectionMask & SECTION_SYSTEM) != 0;
        return new AwsdKeyframe(
                anchor.entityId(),
                anchor.epoch(),
                anchor.anchorTick(),
                anchor.contentId(),
                anchor.ownerWire(),
                pose && (delta.poseFieldMask() & POSE_X) != 0
                        ? anchor.qx() + delta.dxSteps() : anchor.qx(),
                pose && (delta.poseFieldMask() & POSE_Y) != 0
                        ? anchor.qy() + delta.dySteps() : anchor.qy(),
                pose && (delta.poseFieldMask() & POSE_VX) != 0
                        ? anchor.qvx() + delta.dvxSteps() : anchor.qvx(),
                pose && (delta.poseFieldMask() & POSE_VY) != 0
                        ? anchor.qvy() + delta.dvySteps() : anchor.qvy(),
                pose && (delta.poseFieldMask() & POSE_FACING) != 0
                        ? Math.floorMod(anchor.qFacing() + delta.dFacingSteps(), FACING_STEPS_PER_TURN)
                        : anchor.qFacing(),
                pose && (delta.poseFieldMask() & POSE_ANG_VEL) != 0
                        ? anchor.qAngVel() + delta.dAngVelSteps() : anchor.qAngVel(),
                anchor.maxHp(),
                pose && (delta.poseFieldMask() & POSE_HULL_FRAC) != 0
                        ? delta.hullFrac() : anchor.hullFrac(),
                pose && (delta.poseFieldMask() & POSE_ARMOR_FRAC) != 0
                        ? delta.armorFrac() : anchor.armorFrac(),
                anchor.maxFlux(),
                flux && (delta.fluxFieldMask() & FLUX_LEVEL) != 0
                        ? delta.fluxLevel() : anchor.fluxLevel(),
                flux && (delta.fluxFieldMask() & FLUX_HARD_FRAC) != 0
                        ? delta.hardFluxFrac() : anchor.hardFluxFrac(),
                flux && (delta.fluxFieldMask() & FLUX_FLAGS) != 0
                        ? delta.fluxFlags() : anchor.fluxFlags(),
                flux && (delta.fluxFieldMask() & FLUX_SHIELD_FACING) != 0
                        ? delta.shieldFacingU8() : anchor.shieldFacingU8(),
                (sectionMask & SECTION_ALLEGIANCE) != 0 ? delta.fleetSide() : anchor.fleetSide(),
                engine && (delta.engineFieldMask() & ENGINE_DISABLED) != 0
                        ? delta.engineDisabledMask() : anchor.engineDisabledMask(),
                engine && (delta.engineFieldMask() & ENGINE_PERM_DISABLED) != 0
                        ? delta.enginePermDisabledMask() : anchor.enginePermDisabledMask(),
                engine && (delta.engineFieldMask() & ENGINE_CONTROLLER) != 0
                        ? delta.engineControllerState() : anchor.engineControllerState(),
                (sectionMask & SECTION_MANEUVER) != 0 ? delta.maneuverMask() : anchor.maneuverMask(),
                system && (delta.sysFieldMask() & SYS_STATE) != 0
                        ? delta.sysStateCode() : anchor.sysStateCode(),
                system && (delta.sysFieldMask() & SYS_EFFECT) != 0
                        ? delta.sysEffectLevel() : anchor.sysEffectLevel(),
                system && (delta.sysFieldMask() & SYS_CD) != 0
                        ? anchor.qSysCd() + delta.sysCdSteps() : anchor.qSysCd(),
                system && (delta.sysFieldMask() & SYS_AMMO) != 0
                        ? delta.sysAmmoWire() : anchor.sysAmmoWire(),
                // v3 cloak block: no delta section exists — the anchor's cloak fields ride
                // through unchanged (hold-last; cloak freshness = keyframe cadence).
                anchor.cloakStateWire(),
                anchor.cloakEffectFrac(),
                anchor.qCloakCd(),
                anchor.cloakAmmoWire(),
                // v6 CR block: same hold-last law — no CR delta section, CR freshness =
                // keyframe cadence.
                anchor.crU8(),
                anchor.depHalfSecU16(),
                anchor.peakHalfSecU16(),
                anchor.fighterRatePct(),
                mounts);
    }

    /**
     * Mint the COMPLETE client pose (the canonical all-tier {@link ServerEntityPose},
     * mounts embedded via {@link #resolveMounts}) from quantized values — bit-exactly the
     * server's {@code resolve*} arithmetic: {@code hitpoints} = hullFrac/255 × maxHp,
     * flux from fractions × maxFlux, cooldowns from grid steps. {@code armorFrac} is
     * ALWAYS minted (v10: every keyframe carries it; 0 is a real value — the pose
     * sentinel is sign-gated). Wire-dropped AWSS pose fields (spawnTick, armor scalars)
     * have no client-side fields to mint — the AWSS decode path discards them by design
     * (the live armor feed is AWSD).
     */
    public static ServerEntityPose resolvePose(AwsdKeyframe kf) {
        if (kf == null) {
            throw new IllegalArgumentException("keyframe is required");
        }
        float hullLevel = kf.hullFrac() / 255f;
        // v3: a keyframe carries the cloak block — CLOAK_NONE leaves the
        // builder's tier-absent sentinels (asserted-EMPTY travels as the sentinel pose,
        // the AWSS whole-frame EMPTY-clear law); any other state mints the real scalars.
        ServerEntityPose.Builder pose = ServerEntityPose.builder()
                .entityId(kf.entityId())
                .contentId(kf.contentId())
                .ownerConnectionId(kf.ownerWire() == OWNER_NONE_WIRE
                        ? NO_OWNER_CONNECTION : kf.ownerWire())
                .locX(kf.qx() * POS_GRID_SU)
                .locY(kf.qy() * POS_GRID_SU)
                .velX(kf.qvx() * VEL_GRID)
                .velY(kf.qvy() * VEL_GRID)
                .facing(kf.qFacing() * FACING_GRID_DEG)
                .angularVelocity(kf.qAngVel() * ANG_VEL_GRID)
                .hitpoints(hullLevel * kf.maxHp())
                // The keyframe's max hull rides through as-is — the
                // server engine's live getMaxHitpoints (script-grown for a construction swarm),
                // not the variant's; the hull-lane max-hull mirror consumes it.
                .maxHp(kf.maxHp())
                .armorFrac(kf.armorFrac() / 255f)
                .fleetSide(kf.fleetSide())
                .currFlux((kf.fluxLevel() / 255f) * kf.maxFlux())
                .maxFlux(kf.maxFlux())
                .fluxLevel(kf.fluxLevel() / 255f)
                .engineDisabledMask(kf.engineDisabledMask())
                .enginePermDisabledMask(kf.enginePermDisabledMask())
                .engineControllerState(kf.engineControllerState())
                .weaponMounts(resolveMounts(kf))
                .maneuverCommandMask(kf.maneuverMask())
                .hardFlux((kf.hardFluxFrac() / 255f) * kf.maxFlux())
                .fluxFlags(kf.fluxFlags())
                // v4: the flux section always carries the quantized shield
                // facing — mint it VERBATIM (u8 domain; dequant happens at the apply site).
                .shieldFacingU8(kf.shieldFacingU8());
        // v8: SYS_NONE leaves the builder's tier-absent sentinels
        // (UNKNOWN_SYSTEM_STATE / 0 / 0 / −1) — NEVER mint IDLE for a systemless hull:
        // seedFoeSystemState gates on hasSystemState() and a minted IDLE would
        // forceState(IDLE) silently wrong. Absence is a first-class counted state.
        if (kf.sysStateCode() != SYS_NONE_WIRE) {
            pose.systemStateCode(kf.sysStateCode())
                    .systemEffectLevel(kf.sysEffectLevel() / 255f)
                    .systemCooldownRemaining(kf.qSysCd() * SYS_CD_GRID_S)
                    .systemAmmo(kf.sysAmmoWire() == SYS_AMMO_NONE_WIRE
                            ? NO_SYSTEM_AMMO : kf.sysAmmoWire());
        }
        if (kf.cloakStateWire() != CLOAK_NONE_WIRE) {
            pose.cloakStateCode(kf.cloakStateWire())
                    .cloakEffectLevel(kf.cloakEffectFrac() / 255f)
                    .cloakCooldownRemaining(kf.qCloakCd() * SYS_CD_GRID_S)
                    .cloakAmmo(kf.cloakAmmoWire() == CLOAK_AMMO_NONE_WIRE
                            ? -1 : kf.cloakAmmoWire());
        }
        // v6: mint the CR scalars ONLY past the peak==0 absent sentinel —
        // absent leaves the builder's UNKNOWN_CR default (the D2 law; a keyframe with
        // the sentinel still ASSERTS the CR tier in the merger = authoritative EMPTY).
        if (kf.peakHalfSecU16() > 0) {
            pose.cr(kf.crU8() / 255f)
                    .crDepSec(kf.depHalfSecU16() * CR_TIME_GRID_S)
                    .crPeakSec(kf.peakHalfSecU16() * CR_TIME_GRID_S);
        }
        // v12: the fighter-replacement percent - the 0xFF absent sentinel
        // leaves the builder's UNKNOWN default; deltas hold-last like the CR block.
        if (kf.fighterRatePct() != AwsdKeyframe.FIGHTER_RATE_NONE_WIRE) {
            pose.fighterReplacementPct(kf.fighterRatePct());
        }
        return pose.build();
    }

    /** Mint the mount tier (anchor order); cooldownRemaining = cdFrac × qCooldownMax grid. */
    public static List<ServerWeaponMountState> resolveMounts(AwsdKeyframe kf) {
        if (kf == null) {
            throw new IllegalArgumentException("keyframe is required");
        }
        List<ServerWeaponMountState> mounts = new ArrayList<>(kf.mounts().size());
        for (AwsdKeyframe.Mount mount : kf.mounts()) {
            float cooldown = mount.qCooldownMax() * MOUNT_CD_GRID_S;
            mounts.add(new ServerWeaponMountState(
                    mount.mountIndex(),
                    mount.weaponContentId(),
                    (mount.cdFrac() / 255f) * cooldown,
                    cooldown,
                    mount.ammo(),
                    mount.maxAmmo(),
                    mount.chargeFrac() / 255f,
                    mount.flags()));
        }
        return List.copyOf(mounts);
    }

    // ------------------------------------------------------------------ helpers

    private static int readSteps(InputStream data) throws IOException {
        byte first = WireBytes.readSignedByte(data);
        if (first == Byte.MIN_VALUE) {
            return WireBytes.readShort(data); // 0x80 escape marker: an i16 follows
        }
        return first;
    }

    private static int readFieldMask(InputStream data, int legalBits, String section)
            throws IOException {
        int fieldMask = WireBytes.readUnsignedByte(data);
        if ((fieldMask & ~legalBits) != 0) {
            throw new IOException("AWSD " + section + " field mask carries unknown bits: 0x"
                    + Integer.toHexString(fieldMask));
        }
        return fieldMask;
    }

    private static void requireU8(String label, int value) {
        if (value < 0 || value > 0xFF) {
            throw new IllegalArgumentException(label + " outside wire u8 [0,255]: " + value);
        }
    }

    private static void requireU16(String label, int value) {
        if (value < 0 || value > 0xFFFF) {
            throw new IllegalArgumentException(label + " outside wire u16 [0,65535]: " + value);
        }
    }

    private static void requireI16(String label, int value) {
        if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
            throw new IllegalArgumentException(
                    label + " outside wire i16 [-32768,32767]: " + value);
        }
    }

    // ------------------------------------------------------------------ decoded shapes

    /**
     * One decoded AWSD frame: the body simTick + the v5 announced sim-speed (permille,
     * 1000 = 1.0×) plus its records in wire order + the v9 trailing
     * side-totals block ({@code null} only via the fixture ctors — a decoded frame always
     * carries it, the v8-accept lane was dropped at v10; totals absent ⇒ the override
     * lane stays inert).
     */
    public record AwsdFrame(long simTick, int simSpeedPermille, List<AwsdRecord> records,
            SideTotalsBlock sideTotals) {
        public AwsdFrame {
            records = List.copyOf(records);
        }

        /** Totals-less convenience (verifier/fixture shorthand): {@code sideTotals} null ⇒ lane inert. */
        public AwsdFrame(long simTick, int simSpeedPermille, List<AwsdRecord> records) {
            this(simTick, simSpeedPermille, records, null);
        }

        /** 1.0×-speed convenience (verifier/fixture shorthand — wire decode always full-arg). */
        public AwsdFrame(long simTick, List<AwsdRecord> records) {
            this(simTick, SIM_SPEED_PERMILLE_ONE, records, null);
        }
    }

    /**
     * One side's authoritative aggregate totals (v9), u16 fixed-point ×100
     * (centi): the Coordinated-Maneuvers total/commander-max, the Electronic-Warfare
     * total/commander-max (vanilla's (int)-truncated values ×100), and the CommRelay
     * CP-rate flat total — the RAW side sums the three vanilla scripts derive from
     * roster + objectives (never applied artifacts; the client's partial replica roster
     * cannot derive them). Mirror of the server codec's {@code SideTotals}; range-enforced
     * to the u16 carriage at construction.
     */
    public record SideTotals(int coordTotalCenti, int coordMaxCenti,
            int ewTotalCenti, int ewMaxCenti, int cpRateCenti) {
        public SideTotals {
            requireU16("coordTotalCenti", coordTotalCenti);
            requireU16("coordMaxCenti", coordMaxCenti);
            requireU16("ewTotalCenti", ewTotalCenti);
            requireU16("ewMaxCenti", ewMaxCenti);
            requireU16("cpRateCenti", cpRateCenti);
        }
    }

    /** The v9 trailing block: both sides' totals, side 0 then side 1 in wire order. */
    public record SideTotalsBlock(SideTotals side0, SideTotals side1) {
        public SideTotalsBlock {
            if (side0 == null || side1 == null) {
                throw new NullPointerException(side0 == null ? "side0" : "side1");
            }
        }
    }

    /**
     * One decoded record: exactly one of {@code keyframe}/{@code delta} is non-null,
     * matching {@code kind}. A keyframe duplicates the header identity (enforced equal).
     * {@code entityId} widens the wire u16 to the client's long id space. {@code cellRuns}
     * (v11) = the record's decoded armor-cell tail, empty when the section carried
     * runCount 0 — never null.
     */
    public record AwsdRecord(
            long entityId, int kind, int epoch, AwsdKeyframe keyframe, AwsdDelta delta,
            List<CellRun> cellRuns) {
        public AwsdRecord {
            if ((keyframe == null) == (delta == null)) {
                throw new IllegalArgumentException(
                        "exactly one of keyframe/delta must be non-null");
            }
            if (cellRuns == null) {
                throw new IllegalArgumentException("cellRuns must be non-null (empty = idle tail)");
            }
            if (cellRuns.size() > CELL_RUNS_MAX_PER_RECORD) {
                throw new IllegalArgumentException("record cellRuns over the per-record cap "
                        + CELL_RUNS_MAX_PER_RECORD + ": " + cellRuns.size());
            }
            cellRuns = List.copyOf(cellRuns);
            if (keyframe != null) {
                if (kind != KIND_KEYFRAME) {
                    throw new IllegalArgumentException("keyframe record must carry KIND_KEYFRAME");
                }
                if (keyframe.entityId() != entityId || keyframe.epoch() != epoch) {
                    throw new IllegalArgumentException(
                            "keyframe identity mismatch vs record header: header "
                                    + entityId + "/" + epoch + " vs keyframe "
                                    + keyframe.entityId() + "/" + keyframe.epoch());
                }
            } else if (kind != KIND_DELTA) {
                throw new IllegalArgumentException("delta record must carry KIND_DELTA");
            }
        }

        /** Cell-less convenience (verifier/fixture shorthand — wire decode always 6-arg). */
        public AwsdRecord(long entityId, int kind, int epoch, AwsdKeyframe keyframe,
                AwsdDelta delta) {
            this(entityId, kind, epoch, keyframe, delta, List.of());
        }
    }

    /** One decoded v11 cell run: absolute quantized u8 values for cell ordinals
     *  {@code [startIdx, startIdx+values.length)} (ordinal = x·h+y, x the OUTER
     *  {@code float[][]} index — the server retainer's walk order). Mirror of the server
     *  codec's {@code CellRun}; the values array is handed over to the apply lane. */
    public record CellRun(int startIdx, byte[] values) {
        public CellRun {
            if (startIdx < 0 || startIdx > 0xFFFF) {
                throw new IllegalArgumentException("cell run startIdx outside u16: " + startIdx);
            }
            if (values == null || values.length < 1 || values.length > CELL_RUN_LEN_MAX) {
                throw new IllegalArgumentException("cell run len outside [1," + CELL_RUN_LEN_MAX
                        + "]: " + (values == null ? "null" : values.length));
            }
        }
    }

    /**
     * One ship's immutable QUANTIZED anchor state — the client mirror of the server's
     * {@code AwsdEntityBaseline} (same component order, same wire-range validation), held
     * by the merger as the rolling per-entity anchor every DELTA resolves against.
     * {@code epoch} is the u8 anchor identity (D5); {@code anchorTick} the simTick of the
     * frame that minted it (the merger's reorder guard).
     */
    public record AwsdKeyframe(
            long entityId,
            int epoch,
            long anchorTick,
            int contentId,
            int ownerWire,
            int qx,
            int qy,
            int qvx,
            int qvy,
            int qFacing,
            int qAngVel,
            int maxHp,
            int hullFrac,
            int armorFrac,
            int maxFlux,
            int fluxLevel,
            int hardFluxFrac,
            int fluxFlags,
            int shieldFacingU8,
            int fleetSide,
            int engineDisabledMask,
            int enginePermDisabledMask,
            int engineControllerState,
            int maneuverMask,
            int sysStateCode,
            int sysEffectLevel,
            int qSysCd,
            int sysAmmoWire,
            int cloakStateWire,
            int cloakEffectFrac,
            int qCloakCd,
            int cloakAmmoWire,
            int crU8,
            int depHalfSecU16,
            int peakHalfSecU16,
            int fighterRatePct,
            List<Mount> mounts) {

        /** v12 {@code fighterRatePct} absent sentinel (mirrors the server's
         * {@code AwsdEntityBaseline.FIGHTER_RATE_NONE_WIRE}); real values are 0..100. */
        public static final int FIGHTER_RATE_NONE_WIRE = 0xFF;

        /**
         * One mount's quantized keyframe fields ({@code qCooldownMax} @0.01 s; ammo pair
         * usesAmmo-normalized u16; fractions 1⁄255; flags = the mount status bits).
         */
        public record Mount(
                int mountIndex,
                int weaponContentId,
                int qCooldownMax,
                int maxAmmo,
                int ammo,
                int cdFrac,
                int chargeFrac,
                int flags) {

            public Mount {
                requireU8("mountIndex", mountIndex);
                requireU16("weaponContentId", weaponContentId);
                requireU16("qCooldownMax", qCooldownMax);
                requireU16("maxAmmo", maxAmmo);
                requireU16("ammo", ammo);
                requireU8("cdFrac", cdFrac);
                requireU8("chargeFrac", chargeFrac);
                requireU8("flags", flags);
            }
        }

        public AwsdKeyframe {
            if (entityId < 1 || entityId > 0xFFFF) {
                throw new IllegalArgumentException("entityId outside wire u16 [1,65535]: " + entityId);
            }
            requireU8("epoch", epoch);
            if (anchorTick < 0L) {
                throw new IllegalArgumentException("anchorTick must be >= 0; got " + anchorTick);
            }
            requireU16("contentId", contentId);
            requireU16("ownerWire", ownerWire);
            // qx/qy are full i32 carriers (the pose-grid widen): any int value is legal.
            requireI16("qvx", qvx);
            requireI16("qvy", qvy);
            if (qFacing < 0 || qFacing >= FACING_STEPS_PER_TURN) {
                throw new IllegalArgumentException("qFacing outside [0,720): " + qFacing);
            }
            requireI16("qAngVel", qAngVel);
            // v7: maxHp rides a full i32 — u16 refused real hulls
            // (station3 base = 160000 HP); any positive int is legal.
            if (maxHp < 1) {
                throw new IllegalArgumentException(
                        "maxHp outside wire i32 positive domain [1,2147483647]: " + maxHp);
            }
            requireU8("hullFrac", hullFrac);
            // NO min-1 floor for armor (hull's Math.max(1,…) is a hull-only encode
            // clamp): armor is legitimately 0 — a stripped grid rides the wire as 0.
            requireU8("armorFrac", armorFrac);
            requireU16("maxFlux", maxFlux);
            requireU8("fluxLevel", fluxLevel);
            requireU8("hardFluxFrac", hardFluxFrac);
            requireU8("fluxFlags", fluxFlags);
            requireU8("shieldFacingU8", shieldFacingU8);
            requireU8("fleetSide", fleetSide);
            // engine masks are full u32 carriers: any int bit pattern is legal.
            requireU8("engineControllerState", engineControllerState);
            requireU8("maneuverMask", maneuverMask);
            requireU8("sysStateCode", sysStateCode);
            requireU8("sysEffectLevel", sysEffectLevel);
            requireU16("qSysCd", qSysCd);
            requireU8("sysAmmoWire", sysAmmoWire);
            requireU8("cloakStateWire", cloakStateWire);
            requireU8("cloakEffectFrac", cloakEffectFrac);
            requireU16("qCloakCd", qCloakCd);
            requireU8("cloakAmmoWire", cloakAmmoWire);
            requireU8("crU8", crU8);
            requireU16("depHalfSecU16", depHalfSecU16);
            requireU16("peakHalfSecU16", peakHalfSecU16);
            if (peakHalfSecU16 == 0 && (crU8 != 0 || depHalfSecU16 != 0)) {
                // peakHalfSecU16 == 0 is the absent-CR sentinel: one bit pattern per
                // meaning — absent rides the canonical 0/0/0 shape (the server
                // baseline ctor enforces the same law).
                throw new IllegalArgumentException(
                        "CR-absent (peakHalfSecU16 0) must ride the canonical shape 0/0/0; got "
                                + crU8 + "/" + depHalfSecU16);
            }
            if (fighterRatePct != FIGHTER_RATE_NONE_WIRE && (fighterRatePct < 0 || fighterRatePct > 100)) {
                throw new IllegalArgumentException(
                        "fighterRatePct outside 0..100 | 0xFF: " + fighterRatePct);
            }
            if (sysStateCode == SYS_NONE_WIRE) {
                // Canonical absent shape (one bit pattern per meaning) — the server
                // baseline ctor enforces the same law (v8 sparse system tier).
                if (sysEffectLevel != 0 || qSysCd != 0
                        || sysAmmoWire != SYS_AMMO_NONE_WIRE) {
                    throw new IllegalArgumentException(
                            "SYS_NONE must ride the canonical absent shape (0/0/0xFF); got "
                                    + sysEffectLevel + "/" + qSysCd + "/" + sysAmmoWire);
                }
            } else if (sysStateCode > 4) {
                throw new IllegalArgumentException(
                        "sysStateCode outside the project-owned codes 0..4 (or 0xFF none): "
                                + sysStateCode);
            }
            if (cloakStateWire == CLOAK_NONE_WIRE) {
                // Canonical absent shape (one bit pattern per meaning) — the server
                // baseline ctor enforces the same law.
                if (cloakEffectFrac != 0 || qCloakCd != 0
                        || cloakAmmoWire != CLOAK_AMMO_NONE_WIRE) {
                    throw new IllegalArgumentException(
                            "CLOAK_NONE must ride the canonical absent shape (0/0/0xFF); got "
                                    + cloakEffectFrac + "/" + qCloakCd + "/" + cloakAmmoWire);
                }
            } else if (cloakStateWire > 4) {
                throw new IllegalArgumentException(
                        "cloakStateWire outside the project-owned codes 0..4 (or 0xFF none): "
                                + cloakStateWire);
            }
            mounts = List.copyOf(mounts);
            if (mounts.size() > 255) {
                throw new IllegalArgumentException("mount count exceeds u8: " + mounts.size());
            }
            int previousIndex = -1;
            for (Mount mount : mounts) {
                if (mount.mountIndex() <= previousIndex) {
                    throw new IllegalArgumentException(
                            "mounts must ride sorted ascending by unique mountIndex; got "
                                    + mount.mountIndex() + " after " + previousIndex);
                }
                previousIndex = mount.mountIndex();
            }
        }
    }

    /**
     * One decoded DELTA payload: section mask + per-section field masks and values.
     * Undirty components are 0 (empty list for mounts). Kinematic {@code *Steps} are
     * cumulative grid steps vs the anchor; fraction/flag/mask fields are absolute.
     */
    public record AwsdDelta(
            int sectionMask,
            int poseFieldMask,
            int dxSteps,
            int dySteps,
            int dvxSteps,
            int dvySteps,
            int dFacingSteps,
            int dAngVelSteps,
            int hullFrac,
            int armorFrac,
            int fluxFieldMask,
            int fluxLevel,
            int hardFluxFrac,
            int fluxFlags,
            int shieldFacingU8,
            List<MountDelta> mountDeltas,
            int fleetSide,
            int engineFieldMask,
            int engineDisabledMask,
            int enginePermDisabledMask,
            int engineControllerState,
            int maneuverMask,
            int sysFieldMask,
            int sysStateCode,
            int sysEffectLevel,
            int sysCdSteps,
            int sysAmmoWire) {
        public AwsdDelta {
            if ((sectionMask & ~ALL_SECTION_BITS) != 0) {
                throw new IllegalArgumentException(
                        "sectionMask carries bits above 0x7f: 0x" + Integer.toHexString(sectionMask));
            }
            if ((poseFieldMask & ~POSE_FIELD_BITS) != 0
                    || (fluxFieldMask & ~FLUX_FIELD_BITS) != 0
                    || (engineFieldMask & ~ENGINE_FIELD_BITS) != 0
                    || (sysFieldMask & ~SYS_FIELD_BITS) != 0) {
                throw new IllegalArgumentException("delta field mask carries unknown bits");
            }
            mountDeltas = List.copyOf(mountDeltas);
        }
    }

    /** One dirty mount inside a DELTA's MOUNT section; undirty components are 0. */
    public record MountDelta(
            int mountIndex, int fieldMask, int cdFrac, int chargeFrac, int flags, int ammoSteps) {
        public MountDelta {
            if ((fieldMask & ~MOUNT_FIELD_BITS) != 0 || fieldMask == 0) {
                throw new IllegalArgumentException(
                        "mount delta field mask must carry 1..4 known bits; got 0x"
                                + Integer.toHexString(fieldMask));
            }
        }
    }
}
