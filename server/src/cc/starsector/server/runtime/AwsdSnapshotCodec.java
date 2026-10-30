package cc.starsector.server.runtime;

import cc.starsector.server.protocol.AuthoritativeEntitySnapshot;
import cc.starsector.server.protocol.AwsdSnapshotMessage;
import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.ProtocolRegistryEntry;
import cc.starsector.server.protocol.ShipAllegianceSnapshot;
import cc.starsector.server.protocol.ShipCrSnapshot;
import cc.starsector.server.protocol.ShipEngineStateSnapshot;
import cc.starsector.server.protocol.ShipFluxSnapshot;
import cc.starsector.server.protocol.ShipFragmentMarkerSnapshot;
import cc.starsector.server.protocol.ShipManeuverCommandSnapshot;
import cc.starsector.server.protocol.ShipPhaseCloakStateSnapshot;
import cc.starsector.server.protocol.ShipSystemStateSnapshot;
import cc.starsector.server.protocol.ShipWeaponMountSnapshot;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Product-owned binary frame codec for the {@code AWSD} delta+keyframe world-snapshot
 * family (wire shapes frozen): compact absolute
 * KEYFRAME records (the L2 fold — dead-28-B dropped, quantized, statics interned) every
 * E ticks round-robin per ship, plus cumulative quantized DELTA records computed against
 * that shared {@link AwsdEntityBaseline} anchor on all other ticks. AWSS v1..v9 stays
 * frozen beside this family (charter R1's own escape route: a dirty-masked variable-width
 * record set is a NEW magic, never an AWSS tier) as the L3-OFF path, the golden-pinned
 * legacy, and the recorder artifact.
 *
 * <p><b>Quantized-space truth (D4):</b> {@link #quantize} rounds domain floats onto the
 * grids ONCE; the baseline stores the grid steps; deltas are integer step
 * differences vs the anchor; {@code resolve*} mints domain records bit-exactly from grid
 * values on both sides. There is no float drift channel because floats never round-trip.
 * Deltas are CUMULATIVE vs the anchor keyframe (never vs the previous delta): a lost
 * delta costs one tick and the next delta fully repairs it.
 *
 * <p><b>Epoch guard (D5):</b> every record header carries the u8 epoch — a KEYFRAME
 * establishes it, a DELTA names the anchor epoch it accumulates against;
 * {@link #applyDelta} rejects a mismatch (the client's drop-and-hold-last signal).
 *
 * <p><b>Encode refusal:</b> any domain value outside its frozen wire
 * carriage throws the checked {@link EncodeRefusedException} — ONE verdict per ship —
 * so the composer falls that tick's ships back to absolute AWSS with a loud counter,
 * never a half-converted tick. {@code IllegalArgumentException} out of this codec is
 * always a caller bug, never a data verdict.
 *
 * <p>Framing mirrors {@code AuthoritativeWorldSnapshotCodec}: 16-B header (magic u32 +
 * version u16 + headerBytes u16 + wireTypeCode u32 + bodyLen u32), body prefix simTick
 * u64 + recordCount u16 + simSpeedPermille u16 (v5: the announced-dilation
 * carrier, 1000 = 1.0×, range-enforced on read; =0 reserved through v4), the v9 trailing
 * side-totals block after the records ({@link #SIDE_TOTALS_BYTES}), CRC32 over the body
 * appended; magic/version/identity/CRC failures throw {@link IOException} fail-closed.
 * {@code Data*Streams} are legal server-side (AWSC precedent); the step-4 client reader
 * hand-mirrors on {@code WireBytes} (loader-B forbids {@code Data*Stream}s in mod code).
 */
public final class AwsdSnapshotCodec {

    public static final String MESSAGE_ID = AwsdSnapshotMessage.MESSAGE_ID;
    public static final int MAGIC = 0x41575344; // "AWSD"
    private static final ProtocolRegistryEntry REGISTRY_ENTRY =
            NetworkMessageRegistry.authoritativeCombat().awsdSnapshotWireEntry();
    public static final int MESSAGE_TYPE_CODE = REGISTRY_ENTRY.wireTypeCode();
    public static final short SCHEMA_VERSION = (short) AwsdSnapshotMessage.SCHEMA_VERSION;
    public static final short HEADER_BYTES =
            Integer.BYTES   // magic
                    + Short.BYTES   // schema version
                    + Short.BYTES   // header byte count
                    + Integer.BYTES // wire type code
                    + Integer.BYTES; // body byte count
    /** Body bytes preceding the records: simTick u64 + recordCount u16 + simSpeedPermille u16. */
    public static final int BODY_PREFIX_BYTES = Long.BYTES + Short.BYTES + Short.BYTES;
    /**
     * v9: side-totals mirror block bytes, appended AFTER the records —
     * per side {coordTotal, coordMax, ewTotal, ewMax, cpRate} × 2 sides as u16
     * fixed-point ×100 (centi). RAW totals cross, never applied artifacts (EW's
     * cross-side dilution + per-ship ECCM scaling make post-apply read-back
     * unrecoverable); wire law enforces ONLY the u16 range — cap consistency is
     * engine truth, not codec law. Memoryless ⇒ join converges in 1 frame.
     */
    public static final int SIDE_TOTALS_BYTES = 10 * Short.BYTES;

    /**
     * v5 sim-speed carrier bounds: the announced pacing multiplier in
     * permille, 1000 = 1.0×. Mirrors the {@code set-sim-speed} verb contract [0.25, 2.0];
     * out-of-range REJECTS fail-closed at both encode (caller bug) and decode (wire fault).
     */
    public static final int SIM_SPEED_PERMILLE_MIN = 250;
    public static final int SIM_SPEED_PERMILLE_MAX = 2000;
    public static final int SIM_SPEED_PERMILLE_ONE = 1000;

    /** Record kind: fixed-width compact absolute anchor (establishes the epoch). */
    public static final int KIND_KEYFRAME = 0;
    /** Record kind: mask-driven cumulative grid-step delta vs the named anchor epoch. */
    public static final int KIND_DELTA = 1;

    /** Record header bytes: entityId u16 + kind u8 + epoch u8 (both kinds). */
    public static final int RECORD_HEADER_BYTES = 4;
    /** KEYFRAME fixed bytes including the record header (the locXY widen + the v3
     * 5-B cloak block + the v4 shield-facing u8 + the v6
     * 5-B CR block (crU8 + depHalfSecU16 + peakHalfSecU16) + the v7 maxHp
     * u16→i32 widen (station3 base carries 160000 HP) + the v10 armorFrac
     * u8 + the v11 always-present cell-section runCount u8 + the v12
     * fighterRatePct u8 after the CR block + the v14 pilotSeat u8 +
     * pilotMask u8 + pilotFlags u8 after fighterRatePct: 68 B
     * + 12/mount + cell-run bytes). */
    public static final int KEYFRAME_BASE_BYTES = 68;
    /** Per-mount KEYFRAME bytes. */
    public static final int KEYFRAME_MOUNT_BYTES = 12;

    // DELTA section bits — the ServerFragmentMarker tier vocabulary + POSE + the v14
    // EXT section; sections serialize in ASCENDING bit order.
    public static final int SECTION_FLUX = ShipFragmentMarkerSnapshot.TIER_FLUX;
    public static final int SECTION_MOUNT = ShipFragmentMarkerSnapshot.TIER_WEAPON_MOUNT;
    public static final int SECTION_ALLEGIANCE = ShipFragmentMarkerSnapshot.TIER_ALLEGIANCE;
    public static final int SECTION_ENGINE = ShipFragmentMarkerSnapshot.TIER_ENGINE_STATE;
    public static final int SECTION_MANEUVER = ShipFragmentMarkerSnapshot.TIER_MANEUVER;
    public static final int SECTION_SYSTEM = ShipFragmentMarkerSnapshot.TIER_SYSTEM_STATE;
    public static final int SECTION_POSE = 64;
    /**
     * v14: the LAST free u8 section bit becomes an
     * EXTENSION section — when set, ONE u8 {@code extMask} follows the POSE section's bytes
     * (ascending bit order), and each set ext bit carries its own fields. Future fields grow
     * through new ext bits instead of a wider section mask; an unknown ext bit is refused
     * fail-closed on encode and decode (the stream position past it is unknowable).
     */
    public static final int SECTION_EXT = 0x80;
    private static final int ALL_SECTION_BITS = 0xFF;

    /**
     * Ext bit: the hull's pilot triple ({@code pilotSeat} u8, {@code pilotMask} u8,
     * {@code pilotFlags} u8), ABSOLUTE, present while the triple differs from the anchor's —
     * cumulative against the anchor, so it repeats every delta until the next keyframe
     * re-anchors (self-healing under loss, no added latency); hold-last when absent. The third
     * byte is the mark's flags (bit 0 = the pilot's MagicLib decision is followed on this hull),
     * so a remote copy of the hull gates its subsystem the way the serve does. Canonical: seat 0
     * (no mark) carries mask 0 AND flags 0.
     */
    public static final int EXT_PILOT = 0x01;
    /** Every ext bit this schema defines; any other is refused. */
    public static final int ALL_EXT_BITS = 0x01;

    // Per-section field-mask bits (fields serialize in ascending bit order).
    public static final int POSE_X = 1;
    public static final int POSE_Y = 1 << 1;
    public static final int POSE_VX = 1 << 2;
    public static final int POSE_VY = 1 << 3;
    public static final int POSE_FACING = 1 << 4;
    public static final int POSE_ANG_VEL = 1 << 5;
    /** Absolute u8 (the step-0 fraction-family law: 1 B always, no anchor dependence). */
    public static final int POSE_HULL_FRAC = 1 << 6;
    /** Absolute u8 (v10): armor-pool fraction, no min-1 clamp (armor
     * legitimately 0) — corrections ride change cadence, not just the round-robin. */
    public static final int POSE_ARMOR_FRAC = 1 << 7;
    private static final int POSE_FIELD_BITS = 0xFF;
    public static final int FLUX_LEVEL = 1;
    public static final int FLUX_HARD_FRAC = 1 << 1;
    public static final int FLUX_FLAGS = 1 << 2;
    /** Absolute u8 (v4): quantized shield facing, 360/256° steps; 0 when
     * shieldless — consumers gate on FLAG_HAS_SHIELD, never on this byte. */
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

    // The wire grids (quantize = Math.round(v/GRID); resolve = steps*GRID).
    public static final float POS_GRID_SU = 0.25f;
    public static final float VEL_GRID = 0.5f;
    public static final float FACING_GRID_DEG = 0.5f;
    public static final float ANG_VEL_GRID = 0.5f;
    public static final float SYS_CD_GRID_S = 0.1f;
    public static final float MOUNT_CD_GRID_S = 0.01f;
    /** v6 CR-clock grid: dep/peak seconds @0.5 s, u16 SATURATED at 0xFFFF
     * (pins P0 D1 — never-lose-CR hulls carry effective peaks ≥ 1e5 s by design; wire
     * peak is diagnostic-grade, AWSS keeps the exact f32). */
    public static final float CR_TIME_GRID_S = 0.5f;
    /** Facing steps per full turn at the 0.5° grid; keyframe facing = u16 in [0,720). */
    public static final int FACING_STEPS_PER_TURN = 720;

    /**
     * i8-steps escape marker: a step field in [−127,127] rides one signed byte; the
     * byte 0x80 (−128, which therefore never rides as an i8 value) announces an i16
     * follows — a per-field width signal with zero mask overhead.
     */
    public static final int STEP_ESCAPE_MARKER = 0x80;

    private AwsdSnapshotCodec() {
    }

    /**
     * The refusal family: a domain value this frame family cannot carry losslessly
     * on its frozen wire ranges (id/owner/max overflow, hull/cooldown out of range, a
     * missing DENSE tier record, a cross-tier entityId mismatch). CHECKED by design: the
     * composer must catch it per ship and fall that tick's ships back to absolute AWSS
     * (loud counter) — never a half-converted tick.
     */
    public static final class EncodeRefusedException extends Exception {
        public EncodeRefusedException(String message) {
            super(message);
        }
    }

    /**
     * One side's authoritative aggregate totals (v9), u16 fixed-point
     * ×100 (centi): the Coordinated-Maneuvers total/commander-max, the
     * Electronic-Warfare total/commander-max, and the CommRelay CP-rate flat total —
     * exactly the values the three vanilla side scripts SUM from roster + objectives
     * (a client's partial replica roster cannot derive them). Range-enforced to the
     * u16 carriage at construction; semantics stay engine truth.
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

        private static void requireU16(String field, int value) {
            if (value < 0 || value > 0xFFFF) {
                throw new IllegalArgumentException(
                        "side-totals " + field + " outside wire u16: " + value);
            }
        }
    }

    /** The v9 trailing block: both sides' totals, side 0 then side 1 in wire order. */
    public record SideTotalsBlock(SideTotals side0, SideTotals side1) {
        public SideTotalsBlock {
            Objects.requireNonNull(side0, "side0");
            Objects.requireNonNull(side1, "side1");
        }

        /** All-zero totals: the pre-first-capture value; net-identical to vanilla's no-bonus state. */
        public static final SideTotalsBlock ZERO =
                new SideTotalsBlock(new SideTotals(0, 0, 0, 0, 0), new SideTotals(0, 0, 0, 0, 0));
    }

    /**
     * One decoded AWSD frame: the body simTick + the v5 announced sim-speed (permille,
     * 1000 = 1.0×) plus its records in wire order + the v9 trailing
     * side-totals block (always present — this codec is single-version at the registry).
     */
    public record AwsdFrame(long simTick, int simSpeedPermille, List<AwsdRecord> records,
            SideTotalsBlock sideTotals) {
        public AwsdFrame {
            records = List.copyOf(records);
            Objects.requireNonNull(sideTotals, "sideTotals");
        }
    }

    /**
     * One decoded record: exactly one of {@code keyframe}/{@code delta} is non-null,
     * matching {@code kind}. A keyframe's baseline duplicates the header identity
     * (enforced equal here). {@code cellRuns} (v11) = the record's decoded armor-cell
     * tail, empty when the section carried runCount 0 — never null.
     */
    public record AwsdRecord(
            int entityId, int kind, int epoch, AwsdEntityBaseline keyframe, AwsdDelta delta,
            List<CellRun> cellRuns) {
        public AwsdRecord {
            if ((keyframe == null) == (delta == null)) {
                throw new IllegalArgumentException(
                        "exactly one of keyframe/delta must be non-null");
            }
            Objects.requireNonNull(cellRuns, "cellRuns");
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
                            "keyframe baseline identity mismatch vs record header: header "
                                    + entityId + "/" + epoch + " vs baseline "
                                    + keyframe.entityId() + "/" + keyframe.epoch());
                }
            } else if (kind != KIND_DELTA) {
                throw new IllegalArgumentException("delta record must carry KIND_DELTA");
            }
        }
    }

    // ------------------------------------------------------ v11 cell section (D-FOEARMOR)
    // Landed ahead of the wire bump:
    // the vocabulary + cost law the ArmorCellBook
    // scheduler plans against. The stream halves (writeCellSection/readCellSection +
    // AwsdRecord.cellRuns) land WITH the v10→v11 bump in the one both-trees commit —
    // until then nothing below touches encode/decode.

    /** v11 cell section: max runs one record's tail may carry. */
    public static final int CELL_RUNS_MAX_PER_RECORD = 12;
    /** v11 cell section: max cells one run may carry (len wire range 1..32). */
    public static final int CELL_RUN_LEN_MAX = 32;
    /**
     * v11 cell section: hard cap on one record's WHOLE section bytes
     * ({@link #cellSectionBytes}), keeping any record far under the composer's
     * single-record payload tripwire (Onslaught-class 63 + 22×12 + 96 = 423 B).
     */
    public static final int CELL_BYTES_PER_RECORD_MAX = 96;
    /** v11 cell section: the always-present {@code u8 runCount} presence byte. */
    public static final int CELL_SECTION_MIN_BYTES = 1;

    /**
     * One v11 cell run: absolute quantized armor-cell values for the contiguous cell
     * ordinals {@code [startIdx, startIdx + values.length)} (ordinal = x·h + y, x the
     * OUTER {@code float[][]} index — the {@code ArmorCellRetainer} walk order; value law
     * {@code round(clamp01(cell/maxInCell)·255)}). Absolute, never delta-vs-anchor: a
     * lost run only delays those cells to their next send (charter item 4). The client
     * checks {@code startIdx + len ≤ cellCount} against ITS OWN grid — an APPLY-layer
     * per-entity refusal, not a codec concern (the codec has no grid).
     */
    public record CellRun(int startIdx, byte[] values) {
        public CellRun {
            if (startIdx < 0 || startIdx > 0xFFFF) {
                throw new IllegalArgumentException(
                        "cell run startIdx outside wire u16: " + startIdx);
            }
            Objects.requireNonNull(values, "values");
            if (values.length < 1 || values.length > CELL_RUN_LEN_MAX) {
                throw new IllegalArgumentException("cell run len outside [1,"
                        + CELL_RUN_LEN_MAX + "]: " + values.length);
            }
        }
    }

    /**
     * Wire bytes of one record's whole cell section: the presence byte + per run
     * {@code u16 startIdx + u8 len + len values} — the size formula the scheduler
     * budgets with (there is no post-encode estimator; the composer packs by actual
     * {@code byte[].length}, so staged sections must be pre-costed).
     */
    public static int cellSectionBytes(List<CellRun> runs) {
        int bytes = CELL_SECTION_MIN_BYTES;
        for (CellRun run : runs) {
            bytes += 3 + run.values().length;
        }
        return bytes;
    }

    /**
     * Write one v11 cell section: {@code u8 runCount} + per run {@code u16 startIdx +
     * u8 len + values}. Over-cap input throws {@code IllegalArgumentException} — the
     * {@code ArmorCellBook} plans within the caps, so a breach is a scheduler bug, never
     * a data verdict.
     */
    private static void writeCellSection(DataOutputStream data, List<CellRun> runs)
            throws IOException {
        Objects.requireNonNull(runs, "runs");
        if (runs.size() > CELL_RUNS_MAX_PER_RECORD) {
            throw new IllegalArgumentException("cell section over the run cap "
                    + CELL_RUNS_MAX_PER_RECORD + ": " + runs.size());
        }
        if (cellSectionBytes(runs) > CELL_BYTES_PER_RECORD_MAX) {
            throw new IllegalArgumentException("cell section over the byte cap "
                    + CELL_BYTES_PER_RECORD_MAX + ": " + cellSectionBytes(runs));
        }
        data.writeByte(runs.size());
        for (CellRun run : runs) {
            data.writeShort(run.startIdx());
            data.writeByte(run.values().length);
            data.write(run.values());
        }
    }

    /**
     * Read one v11 cell section (structural fail-closed: runCount > {@value
     * #CELL_RUNS_MAX_PER_RECORD}, len outside 1..{@value #CELL_RUN_LEN_MAX}, or section
     * bytes > {@value #CELL_BYTES_PER_RECORD_MAX} ⇒ {@code IOException} = whole-frame
     * refusal — the stream position is unknowable past a malformed section). The
     * semantic {@code startIdx+len ≤ cellCount} check is the APPLY layer's per-entity
     * refusal (the codec has no grid).
     */
    private static List<CellRun> readCellSection(DataInputStream data) throws IOException {
        int runCount = data.readUnsignedByte();
        if (runCount > CELL_RUNS_MAX_PER_RECORD) {
            throw new IOException("AWSD cell section runCount over the cap "
                    + CELL_RUNS_MAX_PER_RECORD + ": " + runCount);
        }
        List<CellRun> runs = new ArrayList<>(runCount);
        int sectionBytes = CELL_SECTION_MIN_BYTES;
        for (int i = 0; i < runCount; i++) {
            int startIdx = data.readUnsignedShort();
            int len = data.readUnsignedByte();
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
            data.readFully(values);
            runs.add(new CellRun(startIdx, values));
        }
        return runs;
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
            int sysAmmoWire,
            int extMask,
            int pilotSeat,
            int pilotMask,
            int pilotFlags) {
        public AwsdDelta {
            if ((sectionMask & ~ALL_SECTION_BITS) != 0) {
                throw new IllegalArgumentException(
                        "sectionMask carries bits above 0xff: 0x" + Integer.toHexString(sectionMask));
            }
            if ((extMask & ~ALL_EXT_BITS) != 0
                    || ((sectionMask & SECTION_EXT) == 0 && extMask != 0)) {
                throw new IllegalArgumentException(
                        "extMask carries unknown bits (or rides without SECTION_EXT): 0x"
                                + Integer.toHexString(extMask));
            }
            if ((extMask & EXT_PILOT) != 0) {
                if (pilotSeat < 0 || pilotSeat > 0xFF || pilotMask < 0 || pilotMask > 0xFF
                        || pilotFlags < 0 || pilotFlags > 0xFF
                        || (pilotSeat == AwsdEntityBaseline.PILOT_SEAT_NONE_WIRE
                                && (pilotMask != 0 || pilotFlags != 0))) {
                    throw new IllegalArgumentException("pilot triple outside u8 or non-canonical: "
                            + pilotSeat + "/0x" + Integer.toHexString(pilotMask)
                            + "/0x" + Integer.toHexString(pilotFlags));
                }
            } else if (pilotSeat != 0 || pilotMask != 0 || pilotFlags != 0) {
                throw new IllegalArgumentException("pilot triple set without EXT_PILOT");
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

    // ------------------------------------------------------------------ quantize

    /**
     * Quantize one ship's domain state onto the AWSD grids as an immutable
     * baseline — the value set a KEYFRAME carries and every DELTA is computed
     * against. Fail-closed: a missing DENSE tier record, a cross-tier entityId mismatch, or
     * any value outside its frozen wire carriage refuses the ship (the composer's AWSS
     * fallback verdict), never a lossy silent clamp — the ONLY named lossy
     * normalizations are the grids themselves, the hullFrac min-1 clamp
     * (a sliver-hull ship must not wire-read dead), the usesAmmo 0/0 pair, and
     * the v3 cloak grids: cloak effectLevel quantized to the 1⁄255 u8
     * fraction and cooldownRemaining to the 0.1 s u16 system grid — NEW named lossy
     * normalizations vs the AWSS v10 tier, which carries both as raw f32.
     */
    public static AwsdEntityBaseline quantize(
            AuthoritativeEntitySnapshot pose,
            ShipFluxSnapshot flux,
            List<ShipWeaponMountSnapshot> mounts,
            ShipAllegianceSnapshot allegiance,
            ShipEngineStateSnapshot engineState,
            ShipManeuverCommandSnapshot maneuver,
            // NULLABLE BY DESIGN (v8): the system tier is SPARSE — vanilla
            // armor modules ship an EMPTY system id column in ship_data.csv, so the
            // binding's system walk has no record for them. An idiom-following
            // requireTier here refused EVERY module into 100% AWSS fallback (the third
            // fortress delta-lane blocker) — null means no-system hull and wires
            // SYS_NONE, exactly mirroring an AWSS frame with no record for the entity.
            ShipSystemStateSnapshot systemState,
            // NULLABLE BY DESIGN — SPARSE like the system tier (most hulls have no
            // getPhaseCloak() slot): the 6 tiers above are whole-roster dense and
            // refuse null (requireTier); null here means no-cloak ship and wires
            // CLOAK_NONE.
            ShipPhaseCloakStateSnapshot phaseCloak,
            // NULLABLE BY DESIGN like the cloak tier above (v6): the v14 CR
            // tier is sparse per-entity — null means no CR record this tick and wires
            // the canonical absent shape 0/0/0 (peakHalfSecU16 == 0 = the P0 D2
            // sentinel; resolveCrOrNull does not mint from it).
            ShipCrSnapshot crState,
            int epoch,
            long anchorTick) throws EncodeRefusedException {
        requireTier(pose, "pose");
        requireTier(flux, "flux");
        requireTier(mounts, "weapon-mount");
        requireTier(allegiance, "allegiance");
        requireTier(engineState, "engine-state");
        requireTier(maneuver, "maneuver-command");
        long entityId = pose.entityId();
        requireSameEntity(entityId, flux.entityId(), "flux");
        requireSameEntity(entityId, allegiance.entityId(), "allegiance");
        requireSameEntity(entityId, engineState.entityId(), "engine-state");
        requireSameEntity(entityId, maneuver.entityId(), "maneuver-command");
        if (entityId > 0xFFFF) {
            throw new EncodeRefusedException("entityId outside wire u16: " + entityId);
        }
        if (pose.contentId() > 0xFFFF) {
            throw new EncodeRefusedException("contentId outside wire u16: " + pose.contentId());
        }
        int ownerWire;
        if (pose.ownerConnectionId() == AuthoritativeEntitySnapshot.NO_OWNER_CONNECTION) {
            ownerWire = AwsdEntityBaseline.OWNER_NONE_WIRE;
        } else if (pose.ownerConnectionId() > 0xFFFE) {
            throw new EncodeRefusedException(
                    "ownerConnectionId outside wire u16 owner domain: " + pose.ownerConnectionId());
        } else {
            ownerWire = pose.ownerConnectionId();
        }
        int qx = quantizeI32("locX", pose.locX(), POS_GRID_SU);
        int qy = quantizeI32("locY", pose.locY(), POS_GRID_SU);
        int qvx = quantizeI16("velX", pose.velX(), VEL_GRID);
        int qvy = quantizeI16("velY", pose.velY(), VEL_GRID);
        // floorMod maps BOTH NaN (round → 0) and ±Inf (round saturates)
        // into [0,720) — facing needs its own non-finite refusal (no i16/i32 check to catch it).
        if (!Float.isFinite(pose.facing())) {
            throw new EncodeRefusedException("facing non-finite: " + pose.facing());
        }
        int qFacing = Math.floorMod(
                Math.round(pose.facing() / FACING_GRID_DEG), FACING_STEPS_PER_TURN);
        int qAngVel = quantizeI16("angularVelocity", pose.angularVelocity(), ANG_VEL_GRID);
        if (pose.hullLevel() <= 0f) {
            throw new EncodeRefusedException(
                    "hullLevel <= 0 cannot derive maxHp (dead ships never encode): "
                            + pose.hullLevel());
        }
        // v7: maxHp rides a full i32 — u16 refused real hulls (station3
        // base = 160000 HP ⇒ permanent whole-tick AWSS fallback on the fortress shape).
        // Math.round saturates at Integer.MAX_VALUE for huge/±Inf inputs, so the MAX
        // check is the fail-closed absurdity refusal (quantizeI32 idiom); NaN rounds to
        // 0 and refuses on the < 1 floor.
        int maxHp = Math.round(pose.hitpoints() / pose.hullLevel());
        if (maxHp < 1 || maxHp == Integer.MAX_VALUE) {
            throw new EncodeRefusedException(
                    "derived maxHp outside wire i32 positive domain [1,2147483646]: " + maxHp);
        }
        // Min-1 clamp: hullLevel > 0 (guarded above) must never wire-read dead.
        int hullFrac = Math.max(1, fracU8(pose.hullLevel()));
        // v10: NO min-1 clamp — armor legitimately ablates to exactly 0.
        int armorFrac = fracU8(pose.armorLevel());
        int maxFlux = Math.round(flux.maxFlux());
        if (maxFlux < 0 || maxFlux > 0xFFFF) {
            throw new EncodeRefusedException("maxFlux outside wire u16: " + maxFlux);
        }
        // fluxLevel quantizes from the record FIELD, not currFlux/maxFlux. The two flux bytes use
        // fluxU8, never fracU8 (255 MEANS "at max"); the hard byte never exceeds the flux byte.
        int fluxLevel = fluxU8(flux.fluxLevel());
        int hardFluxFrac = flux.maxFlux() > 0f
                ? Math.min(fluxLevel, fluxU8(flux.hardFlux() / flux.maxFlux())) : 0;
        int fleetSide = allegiance.fleetSide();
        if (fleetSide < 0 || fleetSide > 0xFF) {
            throw new EncodeRefusedException("fleetSide outside wire u8: " + fleetSide);
        }
        int sysStateWire;
        int sysEffectFrac;
        int qSysCd;
        int sysAmmoWire;
        if (systemState == null) {
            // No-system hull: the canonical absent shape (baseline ctor enforces it).
            sysStateWire = AwsdEntityBaseline.SYS_NONE_WIRE;
            sysEffectFrac = 0;
            qSysCd = 0;
            sysAmmoWire = AwsdEntityBaseline.SYS_AMMO_NONE_WIRE;
        } else {
            requireSameEntity(entityId, systemState.entityId(), "system-state");
            sysStateWire = systemState.stateCode();
            sysEffectFrac = fracU8(systemState.effectLevel());
            qSysCd = Math.round(systemState.cooldownRemaining() / SYS_CD_GRID_S);
            if (qSysCd < 0 || qSysCd > 0xFFFF) {
                throw new EncodeRefusedException(
                        "system cooldownRemaining outside u16 @0.1 s: "
                                + systemState.cooldownRemaining());
            }
            if (systemState.ammo() == ShipSystemStateSnapshot.NO_AMMO) {
                sysAmmoWire = AwsdEntityBaseline.SYS_AMMO_NONE_WIRE;
            } else if (systemState.ammo() > 0xFE) {
                throw new EncodeRefusedException(
                        "system ammo cannot ride u8 beside the 0xFF no-ammo sentinel: "
                                + systemState.ammo());
            } else {
                sysAmmoWire = systemState.ammo();
            }
        }
        int cloakStateWire;
        int cloakEffectFrac;
        int qCloakCd;
        int cloakAmmoWire;
        if (phaseCloak == null) {
            // No-cloak ship: the canonical absent shape (baseline ctor enforces it).
            cloakStateWire = AwsdEntityBaseline.CLOAK_NONE_WIRE;
            cloakEffectFrac = 0;
            qCloakCd = 0;
            cloakAmmoWire = AwsdEntityBaseline.SYS_AMMO_NONE_WIRE;
        } else {
            requireSameEntity(entityId, phaseCloak.entityId(), "phase-cloak");
            cloakStateWire = phaseCloak.stateCode();
            cloakEffectFrac = fracU8(phaseCloak.effectLevel());
            qCloakCd = Math.round(phaseCloak.cooldownRemaining() / SYS_CD_GRID_S);
            if (qCloakCd < 0 || qCloakCd > 0xFFFF) {
                throw new EncodeRefusedException(
                        "cloak cooldownRemaining outside u16 @0.1 s: "
                                + phaseCloak.cooldownRemaining());
            }
            if (phaseCloak.ammo() == ShipPhaseCloakStateSnapshot.NO_AMMO) {
                cloakAmmoWire = AwsdEntityBaseline.SYS_AMMO_NONE_WIRE;
            } else if (phaseCloak.ammo() > 0xFE) {
                throw new EncodeRefusedException(
                        "cloak ammo cannot ride u8 beside the 0xFF no-ammo sentinel: "
                                + phaseCloak.ammo());
            } else {
                cloakAmmoWire = phaseCloak.ammo();
            }
        }
        int crU8;
        int depHalfSecU16;
        int peakHalfSecU16;
        if (crState == null) {
            // No CR record this tick: the canonical absent shape (P0 D2 sentinel).
            crU8 = 0;
            depHalfSecU16 = 0;
            peakHalfSecU16 = 0;
        } else {
            requireSameEntity(entityId, crState.entityId(), "ship-cr");
            peakHalfSecU16 = crTimeU16Saturated("peakSec", crState.peakSec());
            if (peakHalfSecU16 == 0) {
                // Peak quantizes to 0 (< 0.25 s — the P0 D2 accepted corner: such a ship
                // reads as CR-absent): canonicalize to the absent shape so one bit
                // pattern has one meaning and quantize∘resolve stays the identity.
                crU8 = 0;
                depHalfSecU16 = 0;
            } else {
                crU8 = fracU8(crState.cr());
                depHalfSecU16 = crTimeU16Saturated(
                        "timeDeployedSec", crState.timeDeployedSec());
            }
        }
        // v12: the fighter-replacement percent rides the CR record (the
        // per-ship scalar carriage) as ONE keyframe-only u8, 0..100 | 0xFF absent; deltas
        // hold-last like the CR block. Independent of the CR-absent canonical 0/0/0 shape.
        int fighterRatePct = (crState == null
                || crState.fighterReplacementPct() == ShipCrSnapshot.NO_FIGHTER_REPLACEMENT_PCT)
                ? AwsdEntityBaseline.FIGHTER_RATE_NONE_WIRE
                : crState.fighterReplacementPct();
        if (mounts.size() > 0xFF) {
            throw new EncodeRefusedException("mount count outside wire u8: " + mounts.size());
        }
        List<AwsdEntityBaseline.Mount> qMounts = new ArrayList<>(mounts.size());
        int previousIndex = -1;
        for (ShipWeaponMountSnapshot mount : mounts) {
            requireTier(mount, "weapon-mount");
            requireSameEntity(entityId, mount.entityId(), "weapon-mount");
            if (mount.mountIndex() <= previousIndex) {
                throw new EncodeRefusedException(
                        "mount tier must ride sorted ascending by unique mountIndex; got "
                                + mount.mountIndex() + " after " + previousIndex);
            }
            previousIndex = mount.mountIndex();
            if (mount.mountIndex() > 0xFF) {
                throw new EncodeRefusedException("mountIndex outside wire u8: " + mount.mountIndex());
            }
            if (mount.weaponContentId() > 0xFFFF) {
                throw new EncodeRefusedException(
                        "weaponContentId outside wire u16: " + mount.weaponContentId());
            }
            int qCooldownMax = Math.round(mount.cooldown() / MOUNT_CD_GRID_S);
            if (qCooldownMax < 0 || qCooldownMax > 0xFFFF) {
                throw new EncodeRefusedException(
                        "mount cooldown-max outside u16 @0.01 s: " + mount.cooldown());
            }
            int cdFrac = mount.cooldown() > 0f
                    ? fracU8(mount.cooldownRemaining() / mount.cooldown())
                    : 0;
            int chargeFrac = fracU8(mount.chargeLevel());
            int ammoWire;
            int maxAmmoWire;
            if (!mount.usesAmmo()) {
                // The !usesAmmo ammo domain is deliberately UNCONSTRAINED (engine
                // sentinel, often large/negative) and cannot round-trip u16 — the USES_AMMO
                // flag is the truth carrier, the wire pair is normalized 0/0. NAMED lossy
                // normalization under the lossy-lever law; AWSS keeps the raw sentinel.
                ammoWire = 0;
                maxAmmoWire = 0;
            } else {
                if (mount.ammo() < 0 || mount.ammo() > 0xFFFF) {
                    throw new EncodeRefusedException(
                            "mount ammo outside wire u16 under usesAmmo: " + mount.ammo());
                }
                if (mount.maxAmmo() < 0 || mount.maxAmmo() > 0xFFFF) {
                    throw new EncodeRefusedException(
                            "mount maxAmmo outside wire u16 under usesAmmo: " + mount.maxAmmo());
                }
                ammoWire = mount.ammo();
                maxAmmoWire = mount.maxAmmo();
            }
            qMounts.add(new AwsdEntityBaseline.Mount(
                    mount.mountIndex(),
                    mount.weaponContentId(),
                    qCooldownMax,
                    maxAmmoWire,
                    ammoWire,
                    cdFrac,
                    chargeFrac,
                    mount.statusFlags()));
        }
        return new AwsdEntityBaseline(
                (int) entityId,
                epoch,
                anchorTick,
                pose.contentId(),
                ownerWire,
                qx,
                qy,
                qvx,
                qvy,
                qFacing,
                qAngVel,
                maxHp,
                hullFrac,
                armorFrac,
                maxFlux,
                fluxLevel,
                hardFluxFrac,
                flux.statusFlags(),
                // v4 shield facing: already quantized ONCE at the ECB mint —
                // carried VERBATIM, never re-derived from floats.
                flux.shieldFacingU8(),
                fleetSide,
                engineState.disabledMask(),
                engineState.permDisabledMask(),
                engineState.controllerState(),
                maneuver.maneuverMask(),
                sysStateWire,
                sysEffectFrac,
                qSysCd,
                sysAmmoWire,
                cloakStateWire,
                cloakEffectFrac,
                qCloakCd,
                cloakAmmoWire,
                crU8,
                depHalfSecU16,
                peakHalfSecU16,
                fighterRatePct,
                maneuver.pilotSeat(),
                maneuver.pilotMask(),
                maneuver.pilotFlags(),
                qMounts);
    }

    /**
     * True when {@code cur} cannot ride a DELTA against {@code anchor} and must force an
     * out-of-turn KEYFRAME (D5 hatch family): any static changed (contentId, ownerWire,
     * maxHp, maxFlux, or the mount roster identity — count/index/weaponContentId/
     * qCooldownMax/maxAmmo) or any cumulative step field outside i16 (teleport hatch;
     * facing is mod-720 shortest-path bounded and never hatches).
     */
    public static boolean needsKeyframe(AwsdEntityBaseline cur, AwsdEntityBaseline anchor) {
        requireSameBaselineEntity(cur, anchor);
        if (cur.contentId() != anchor.contentId()
                || cur.ownerWire() != anchor.ownerWire()
                || cur.maxHp() != anchor.maxHp()
                || cur.maxFlux() != anchor.maxFlux()) {
            return true;
        }
        if (cur.mounts().size() != anchor.mounts().size()) {
            return true;
        }
        for (int i = 0; i < cur.mounts().size(); i++) {
            AwsdEntityBaseline.Mount c = cur.mounts().get(i);
            AwsdEntityBaseline.Mount a = anchor.mounts().get(i);
            if (c.mountIndex() != a.mountIndex()
                    || c.weaponContentId() != a.weaponContentId()
                    || c.qCooldownMax() != a.qCooldownMax()
                    || c.maxAmmo() != a.maxAmmo()) {
                return true;
            }
            if (outsideI16(c.ammo() - a.ammo())) {
                return true;
            }
        }
        return outsideI16((long) cur.qx() - anchor.qx())
                || outsideI16((long) cur.qy() - anchor.qy())
                || outsideI16(cur.qvx() - anchor.qvx())
                || outsideI16(cur.qvy() - anchor.qvy())
                || outsideI16(cur.qAngVel() - anchor.qAngVel())
                || outsideI16(cur.qSysCd() - anchor.qSysCd());
    }

    // ------------------------------------------------------------------ encode

    /** Encode one KEYFRAME record ({@link #KEYFRAME_BASE_BYTES} + 12/mount): the wire field
     * order with the v3 5-B cloak block (state u8 + effectFrac u8 + qCd u16 + ammo u8)
     * between sysAmmoWire and mountCount, then the v6 CR block, the v12 fighterRatePct u8
     * and the v14 pilot triple (pilotSeat u8 + pilotMask u8 + pilotFlags u8) right before mountCount. This
     * no-runs signature writes a v11 cell section with runCount 0. */
    public static byte[] encodeKeyframe(AwsdEntityBaseline baseline) {
        return encodeKeyframe(baseline, List.of());
    }

    /** v11: {@link #encodeKeyframe(AwsdEntityBaseline)} + a cell-run tail (foe-armor
     * per-cell section; law). */
    public static byte[] encodeKeyframe(AwsdEntityBaseline baseline, List<CellRun> cellRuns) {
        Objects.requireNonNull(baseline, "baseline");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(
                KEYFRAME_BASE_BYTES + KEYFRAME_MOUNT_BYTES * baseline.mounts().size());
        DataOutputStream data = new DataOutputStream(bytes);
        try {
            data.writeShort(baseline.entityId());
            data.writeByte(KIND_KEYFRAME);
            data.writeByte(baseline.epoch());
            data.writeShort(baseline.contentId());
            data.writeShort(baseline.ownerWire());
            data.writeInt(baseline.qx());
            data.writeInt(baseline.qy());
            data.writeShort(baseline.qvx());
            data.writeShort(baseline.qvy());
            data.writeShort(baseline.qFacing());
            data.writeShort(baseline.qAngVel());
            data.writeInt(baseline.maxHp());
            data.writeByte(baseline.hullFrac());
            data.writeByte(baseline.armorFrac());
            data.writeShort(baseline.maxFlux());
            data.writeByte(baseline.fluxLevel());
            data.writeByte(baseline.hardFluxFrac());
            data.writeByte(baseline.fluxFlags());
            data.writeByte(baseline.shieldFacingU8());
            data.writeByte(baseline.fleetSide());
            data.writeInt(baseline.engineDisabledMask());
            data.writeInt(baseline.enginePermDisabledMask());
            data.writeByte(baseline.engineControllerState());
            data.writeByte(baseline.maneuverMask());
            data.writeByte(baseline.sysStateCode());
            data.writeByte(baseline.sysEffectLevel());
            data.writeShort(baseline.qSysCd());
            data.writeByte(baseline.sysAmmoWire());
            data.writeByte(baseline.cloakStateWire());
            data.writeByte(baseline.cloakEffectFrac());
            data.writeShort(baseline.qCloakCd());
            data.writeByte(baseline.cloakAmmoWire());
            data.writeByte(baseline.crU8());
            data.writeShort(baseline.depHalfSecU16());
            data.writeShort(baseline.peakHalfSecU16());
            data.writeByte(baseline.fighterRatePct());
            data.writeByte(baseline.pilotSeat());
            data.writeByte(baseline.pilotMask());
            data.writeByte(baseline.pilotFlags());
            data.writeByte(baseline.mounts().size());
            for (AwsdEntityBaseline.Mount mount : baseline.mounts()) {
                data.writeByte(mount.mountIndex());
                data.writeShort(mount.weaponContentId());
                data.writeShort(mount.qCooldownMax());
                data.writeShort(mount.maxAmmo());
                data.writeShort(mount.ammo());
                data.writeByte(mount.cdFrac());
                data.writeByte(mount.chargeFrac());
                data.writeByte(mount.flags());
            }
            writeCellSection(data, cellRuns);
            data.flush();
        } catch (IOException impossible) {
            throw new AssertionError("in-memory keyframe encode cannot fail", impossible);
        }
        return bytes.toByteArray();
    }

    /**
     * Encode one DELTA record for {@code cur} against its anchor: header (id, kind,
     * ANCHOR epoch) + section mask + dirty sections in ascending bit order. Dirty =
     * differs-from-anchor in quantized space; an all-clean ship still emits the 5-B
     * mask-0 record (the client's confirmed-unchanged fold). Throws
     * {@code IllegalArgumentException} when {@link #needsKeyframe} — calling this
     * without that check is a composer bug, not a data verdict. This no-runs signature
     * writes a v11 cell section with runCount 0.
     */
    public static byte[] encodeDelta(AwsdEntityBaseline cur, AwsdEntityBaseline anchor) {
        return encodeDelta(cur, anchor, List.of());
    }

    /** v11: {@link #encodeDelta(AwsdEntityBaseline, AwsdEntityBaseline)} + a cell-run
     * tail after the POSE block (foe-armor per-cell section). */
    public static byte[] encodeDelta(
            AwsdEntityBaseline cur, AwsdEntityBaseline anchor, List<CellRun> cellRuns) {
        requireSameBaselineEntity(cur, anchor);
        if (needsKeyframe(cur, anchor)) {
            throw new IllegalArgumentException(
                    "entity " + cur.entityId() + " needs a keyframe vs this anchor"
                            + " (static change or i16 hatch) — encodeDelta is illegal here");
        }
        int fluxFieldMask = maskBit(cur.fluxLevel() != anchor.fluxLevel(), FLUX_LEVEL)
                | maskBit(cur.hardFluxFrac() != anchor.hardFluxFrac(), FLUX_HARD_FRAC)
                | maskBit(cur.fluxFlags() != anchor.fluxFlags(), FLUX_FLAGS)
                | maskBit(cur.shieldFacingU8() != anchor.shieldFacingU8(), FLUX_SHIELD_FACING);
        List<MountDelta> mountDeltas = new ArrayList<>();
        for (int i = 0; i < cur.mounts().size(); i++) {
            AwsdEntityBaseline.Mount c = cur.mounts().get(i);
            AwsdEntityBaseline.Mount a = anchor.mounts().get(i);
            int fieldMask = maskBit(c.cdFrac() != a.cdFrac(), MOUNT_CD_FRAC)
                    | maskBit(c.chargeFrac() != a.chargeFrac(), MOUNT_CHARGE)
                    | maskBit(c.flags() != a.flags(), MOUNT_FLAGS)
                    | maskBit(c.ammo() != a.ammo(), MOUNT_AMMO);
            if (fieldMask != 0) {
                mountDeltas.add(new MountDelta(
                        c.mountIndex(),
                        fieldMask,
                        (fieldMask & MOUNT_CD_FRAC) != 0 ? c.cdFrac() : 0,
                        (fieldMask & MOUNT_CHARGE) != 0 ? c.chargeFrac() : 0,
                        (fieldMask & MOUNT_FLAGS) != 0 ? c.flags() : 0,
                        (fieldMask & MOUNT_AMMO) != 0 ? c.ammo() - a.ammo() : 0));
            }
        }
        int engineFieldMask =
                maskBit(cur.engineDisabledMask() != anchor.engineDisabledMask(), ENGINE_DISABLED)
                        | maskBit(cur.enginePermDisabledMask() != anchor.enginePermDisabledMask(),
                                ENGINE_PERM_DISABLED)
                        | maskBit(cur.engineControllerState() != anchor.engineControllerState(),
                                ENGINE_CONTROLLER);
        int sysFieldMask = maskBit(cur.sysStateCode() != anchor.sysStateCode(), SYS_STATE)
                | maskBit(cur.sysEffectLevel() != anchor.sysEffectLevel(), SYS_EFFECT)
                | maskBit(cur.qSysCd() != anchor.qSysCd(), SYS_CD)
                | maskBit(cur.sysAmmoWire() != anchor.sysAmmoWire(), SYS_AMMO);
        int dFacingSteps = facingDeltaSteps(cur.qFacing(), anchor.qFacing());
        int poseFieldMask = maskBit(cur.qx() != anchor.qx(), POSE_X)
                | maskBit(cur.qy() != anchor.qy(), POSE_Y)
                | maskBit(cur.qvx() != anchor.qvx(), POSE_VX)
                | maskBit(cur.qvy() != anchor.qvy(), POSE_VY)
                | maskBit(dFacingSteps != 0, POSE_FACING)
                | maskBit(cur.qAngVel() != anchor.qAngVel(), POSE_ANG_VEL)
                | maskBit(cur.hullFrac() != anchor.hullFrac(), POSE_HULL_FRAC)
                | maskBit(cur.armorFrac() != anchor.armorFrac(), POSE_ARMOR_FRAC);
        int sectionMask = maskBit(fluxFieldMask != 0, SECTION_FLUX)
                | maskBit(!mountDeltas.isEmpty(), SECTION_MOUNT)
                | maskBit(cur.fleetSide() != anchor.fleetSide(), SECTION_ALLEGIANCE)
                | maskBit(engineFieldMask != 0, SECTION_ENGINE)
                | maskBit(cur.maneuverMask() != anchor.maneuverMask(), SECTION_MANEUVER)
                | maskBit(sysFieldMask != 0, SECTION_SYSTEM)
                | maskBit(poseFieldMask != 0, SECTION_POSE);
        // v14 ext section: the pilot triple rides ABSOLUTE while it differs from the anchor —
        // so it repeats on every delta until a keyframe re-anchors (a lost datagram never
        // loses the mark), and the edge rides the very tick it happens.
        int extMask = maskBit(cur.pilotSeat() != anchor.pilotSeat()
                || cur.pilotMask() != anchor.pilotMask()
                || cur.pilotFlags() != anchor.pilotFlags(), EXT_PILOT);
        if ((extMask & ~ALL_EXT_BITS) != 0) {
            throw new IllegalArgumentException("extMask carries unknown bits: 0x" + Integer.toHexString(extMask));
        }
        sectionMask |= maskBit(extMask != 0, SECTION_EXT);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream(32);
        DataOutputStream data = new DataOutputStream(bytes);
        try {
            data.writeShort(cur.entityId());
            data.writeByte(KIND_DELTA);
            data.writeByte(anchor.epoch());
            data.writeByte(sectionMask);
            if ((sectionMask & SECTION_FLUX) != 0) {
                data.writeByte(fluxFieldMask);
                if ((fluxFieldMask & FLUX_LEVEL) != 0) {
                    data.writeByte(cur.fluxLevel());
                }
                if ((fluxFieldMask & FLUX_HARD_FRAC) != 0) {
                    data.writeByte(cur.hardFluxFrac());
                }
                if ((fluxFieldMask & FLUX_FLAGS) != 0) {
                    data.writeByte(cur.fluxFlags());
                }
                if ((fluxFieldMask & FLUX_SHIELD_FACING) != 0) {
                    data.writeByte(cur.shieldFacingU8());
                }
            }
            if ((sectionMask & SECTION_MOUNT) != 0) {
                data.writeByte(mountDeltas.size());
                for (MountDelta mount : mountDeltas) {
                    data.writeByte(mount.mountIndex());
                    data.writeByte(mount.fieldMask());
                    if ((mount.fieldMask() & MOUNT_CD_FRAC) != 0) {
                        data.writeByte(mount.cdFrac());
                    }
                    if ((mount.fieldMask() & MOUNT_CHARGE) != 0) {
                        data.writeByte(mount.chargeFrac());
                    }
                    if ((mount.fieldMask() & MOUNT_FLAGS) != 0) {
                        data.writeByte(mount.flags());
                    }
                    if ((mount.fieldMask() & MOUNT_AMMO) != 0) {
                        writeSteps(data, mount.ammoSteps());
                    }
                }
            }
            if ((sectionMask & SECTION_ALLEGIANCE) != 0) {
                data.writeByte(cur.fleetSide());
            }
            if ((sectionMask & SECTION_ENGINE) != 0) {
                data.writeByte(engineFieldMask);
                if ((engineFieldMask & ENGINE_DISABLED) != 0) {
                    data.writeInt(cur.engineDisabledMask());
                }
                if ((engineFieldMask & ENGINE_PERM_DISABLED) != 0) {
                    data.writeInt(cur.enginePermDisabledMask());
                }
                if ((engineFieldMask & ENGINE_CONTROLLER) != 0) {
                    data.writeByte(cur.engineControllerState());
                }
            }
            if ((sectionMask & SECTION_MANEUVER) != 0) {
                data.writeByte(cur.maneuverMask());
            }
            if ((sectionMask & SECTION_SYSTEM) != 0) {
                data.writeByte(sysFieldMask);
                if ((sysFieldMask & SYS_STATE) != 0) {
                    data.writeByte(cur.sysStateCode());
                }
                if ((sysFieldMask & SYS_EFFECT) != 0) {
                    data.writeByte(cur.sysEffectLevel());
                }
                if ((sysFieldMask & SYS_CD) != 0) {
                    writeSteps(data, cur.qSysCd() - anchor.qSysCd());
                }
                if ((sysFieldMask & SYS_AMMO) != 0) {
                    data.writeByte(cur.sysAmmoWire());
                }
            }
            if ((sectionMask & SECTION_POSE) != 0) {
                data.writeByte(poseFieldMask);
                if ((poseFieldMask & POSE_X) != 0) {
                    writeSteps(data, cur.qx() - anchor.qx());
                }
                if ((poseFieldMask & POSE_Y) != 0) {
                    writeSteps(data, cur.qy() - anchor.qy());
                }
                if ((poseFieldMask & POSE_VX) != 0) {
                    writeSteps(data, cur.qvx() - anchor.qvx());
                }
                if ((poseFieldMask & POSE_VY) != 0) {
                    writeSteps(data, cur.qvy() - anchor.qvy());
                }
                if ((poseFieldMask & POSE_FACING) != 0) {
                    writeSteps(data, dFacingSteps);
                }
                if ((poseFieldMask & POSE_ANG_VEL) != 0) {
                    writeSteps(data, cur.qAngVel() - anchor.qAngVel());
                }
                if ((poseFieldMask & POSE_HULL_FRAC) != 0) {
                    data.writeByte(cur.hullFrac());
                }
                if ((poseFieldMask & POSE_ARMOR_FRAC) != 0) {
                    data.writeByte(cur.armorFrac());
                }
            }
            if ((sectionMask & SECTION_EXT) != 0) {
                data.writeByte(extMask);
                if ((extMask & EXT_PILOT) != 0) {
                    data.writeByte(cur.pilotSeat());
                    data.writeByte(cur.pilotMask());
                    data.writeByte(cur.pilotFlags());
                }
            }
            writeCellSection(data, cellRuns);
            data.flush();
        } catch (IOException impossible) {
            throw new AssertionError("in-memory delta encode cannot fail", impossible);
        }
        return bytes.toByteArray();
    }

    // ------------------------------------------------------------------ frame I/O

    /**
     * Write one AWSD frame: 16-B header + body (simTick u64 + recordCount u16 +
     * simSpeedPermille u16 + records verbatim + the v9 side-totals block) + CRC32 over
     * the body. Returns bytes written. {@code simSpeedPermille} (v5) = the
     * announced pacing multiplier,
     * 1000 = 1.0×; out-of-[{@value #SIM_SPEED_PERMILLE_MIN},{@value #SIM_SPEED_PERMILLE_MAX}]
     * throws {@link IllegalArgumentException} — a caller bug, never a data verdict.
     * {@code sideTotals} (v9) is REQUIRED: the encoder always writes
     * v9 — a caller with nothing to announce passes {@link SideTotalsBlock#ZERO}.
     */
    public static int writeFrame(OutputStream output, long simTick, int simSpeedPermille,
            List<byte[]> records, SideTotalsBlock sideTotals) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(records, "records");
        Objects.requireNonNull(sideTotals, "sideTotals");
        if (records.size() > 0xFFFF) {
            throw new IllegalArgumentException(
                    "record count outside wire u16: " + records.size());
        }
        if (simSpeedPermille < SIM_SPEED_PERMILLE_MIN
                || simSpeedPermille > SIM_SPEED_PERMILLE_MAX) {
            throw new IllegalArgumentException("simSpeedPermille outside ["
                    + SIM_SPEED_PERMILLE_MIN + ", " + SIM_SPEED_PERMILLE_MAX + "]: "
                    + simSpeedPermille);
        }
        int recordBytes = 0;
        for (byte[] record : records) {
            Objects.requireNonNull(record, "records element");
            recordBytes += record.length;
        }
        ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream(
                BODY_PREFIX_BYTES + recordBytes + SIDE_TOTALS_BYTES);
        DataOutputStream bodyData = new DataOutputStream(bodyBytes);
        bodyData.writeLong(simTick);
        bodyData.writeShort(records.size());
        bodyData.writeShort(simSpeedPermille);
        for (byte[] record : records) {
            bodyData.write(record);
        }
        writeSideTotals(bodyData, sideTotals.side0());
        writeSideTotals(bodyData, sideTotals.side1());
        bodyData.flush();
        byte[] body = bodyBytes.toByteArray();
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(MAGIC);
        data.writeShort(SCHEMA_VERSION);
        data.writeShort(HEADER_BYTES);
        data.writeInt(REGISTRY_ENTRY.wireTypeCode());
        data.writeInt(body.length);
        data.write(body);
        data.writeInt((int) checksum(body));
        data.flush();
        return HEADER_BYTES + body.length + Integer.BYTES;
    }

    /**
     * Read and validate exactly one AWSD frame — magic, registered schema/wire identity,
     * declared header size, CRC32, simSpeedPermille range, record walk, and exact body
     * consumption all fail closed with {@link IOException}. Decoded keyframes carry
     * {@code anchorTick} = the frame's simTick (the tick that minted the anchor).
     */
    public static AwsdFrame readFrame(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        DataInputStream data = new DataInputStream(input);
        int magic = data.readInt();
        if (magic != MAGIC) {
            throw new IOException("not an AWSD frame: magic 0x" + Integer.toHexString(magic));
        }
        short schemaVersion = data.readShort();
        requireRegisteredWireIdentity(data.readShort(), schemaVersion, data.readInt());
        int bodyLength = data.readInt();
        if (bodyLength < BODY_PREFIX_BYTES) {
            throw new IOException("AWSD body shorter than its prefix: " + bodyLength);
        }
        byte[] body = new byte[bodyLength];
        data.readFully(body);
        long declared = Integer.toUnsignedLong(data.readInt());
        long actual = checksum(body);
        if (declared != actual) {
            throw new IOException("AWSD frame CRC mismatch: declared " + declared
                    + " actual " + actual);
        }
        ByteArrayInputStream bodyBytes = new ByteArrayInputStream(body);
        DataInputStream bodyData = new DataInputStream(bodyBytes);
        long simTick = bodyData.readLong();
        int recordCount = bodyData.readUnsignedShort();
        int simSpeedPermille = bodyData.readUnsignedShort();
        if (simSpeedPermille < SIM_SPEED_PERMILLE_MIN
                || simSpeedPermille > SIM_SPEED_PERMILLE_MAX) {
            throw new IOException("AWSD simSpeedPermille outside ["
                    + SIM_SPEED_PERMILLE_MIN + ", " + SIM_SPEED_PERMILLE_MAX + "]: "
                    + simSpeedPermille);
        }
        List<AwsdRecord> records = new ArrayList<>(recordCount);
        for (int i = 0; i < recordCount; i++) {
            records.add(readRecord(bodyData, simTick));
        }
        if (bodyBytes.available() < SIDE_TOTALS_BYTES) {
            throw new IOException("AWSD v9 body truncated before its side-totals block: "
                    + bodyBytes.available() + " of " + SIDE_TOTALS_BYTES + " bytes");
        }
        SideTotalsBlock sideTotals =
                new SideTotalsBlock(readSideTotals(bodyData), readSideTotals(bodyData));
        if (bodyBytes.available() != 0) {
            throw new IOException("AWSD body carries " + bodyBytes.available()
                    + " trailing bytes after its side-totals block");
        }
        return new AwsdFrame(simTick, simSpeedPermille, records, sideTotals);
    }

    private static void writeSideTotals(DataOutputStream data, SideTotals side)
            throws IOException {
        data.writeShort(side.coordTotalCenti());
        data.writeShort(side.coordMaxCenti());
        data.writeShort(side.ewTotalCenti());
        data.writeShort(side.ewMaxCenti());
        data.writeShort(side.cpRateCenti());
    }

    private static SideTotals readSideTotals(DataInputStream data) throws IOException {
        return new SideTotals(
                data.readUnsignedShort(),
                data.readUnsignedShort(),
                data.readUnsignedShort(),
                data.readUnsignedShort(),
                data.readUnsignedShort());
    }

    private static AwsdRecord readRecord(DataInputStream data, long simTick) throws IOException {
        int entityId = data.readUnsignedShort();
        int kind = data.readUnsignedByte();
        int epoch = data.readUnsignedByte();
        if (kind == KIND_KEYFRAME) {
            AwsdEntityBaseline keyframe = readKeyframeFields(data, entityId, epoch, simTick);
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

    private static AwsdEntityBaseline readKeyframeFields(
            DataInputStream data, int entityId, int epoch, long simTick) throws IOException {
        int contentId = data.readUnsignedShort();
        int ownerWire = data.readUnsignedShort();
        int qx = data.readInt();
        int qy = data.readInt();
        int qvx = data.readShort();
        int qvy = data.readShort();
        int qFacing = data.readUnsignedShort();
        int qAngVel = data.readShort();
        // v7: i32 read — a negative value (a u32-style top-bit pattern this encoder never
        // writes) fails the baseline ctor's positive-domain check into the IOException below.
        int maxHp = data.readInt();
        int hullFrac = data.readUnsignedByte();
        int armorFrac = data.readUnsignedByte();
        int maxFlux = data.readUnsignedShort();
        int fluxLevel = data.readUnsignedByte();
        int hardFluxFrac = data.readUnsignedByte();
        int fluxFlags = data.readUnsignedByte();
        int shieldFacingU8 = data.readUnsignedByte();
        int fleetSide = data.readUnsignedByte();
        int engineDisabledMask = data.readInt();
        int enginePermDisabledMask = data.readInt();
        int engineControllerState = data.readUnsignedByte();
        int maneuverMask = data.readUnsignedByte();
        int sysStateCode = data.readUnsignedByte();
        int sysEffectLevel = data.readUnsignedByte();
        int qSysCd = data.readUnsignedShort();
        int sysAmmoWire = data.readUnsignedByte();
        int cloakStateWire = data.readUnsignedByte();
        int cloakEffectFrac = data.readUnsignedByte();
        int qCloakCd = data.readUnsignedShort();
        int cloakAmmoWire = data.readUnsignedByte();
        int crU8 = data.readUnsignedByte();
        int depHalfSecU16 = data.readUnsignedShort();
        int peakHalfSecU16 = data.readUnsignedShort();
        int fighterRatePct = data.readUnsignedByte();
        int pilotSeat = data.readUnsignedByte();
        int pilotMask = data.readUnsignedByte();
        int pilotFlags = data.readUnsignedByte();
        int mountCount = data.readUnsignedByte();
        List<AwsdEntityBaseline.Mount> mounts = new ArrayList<>(mountCount);
        try {
            for (int i = 0; i < mountCount; i++) {
                mounts.add(new AwsdEntityBaseline.Mount(
                        data.readUnsignedByte(),
                        data.readUnsignedShort(),
                        data.readUnsignedShort(),
                        data.readUnsignedShort(),
                        data.readUnsignedShort(),
                        data.readUnsignedByte(),
                        data.readUnsignedByte(),
                        data.readUnsignedByte()));
            }
            return new AwsdEntityBaseline(
                    entityId, epoch, simTick, contentId, ownerWire,
                    qx, qy, qvx, qvy, qFacing, qAngVel,
                    maxHp, hullFrac, armorFrac, maxFlux, fluxLevel, hardFluxFrac, fluxFlags,
                    shieldFacingU8, fleetSide,
                    engineDisabledMask, enginePermDisabledMask, engineControllerState,
                    maneuverMask, sysStateCode, sysEffectLevel, qSysCd, sysAmmoWire,
                    cloakStateWire, cloakEffectFrac, qCloakCd, cloakAmmoWire,
                    crU8, depHalfSecU16, peakHalfSecU16, fighterRatePct, pilotSeat, pilotMask, pilotFlags,
                    mounts);
        } catch (IllegalArgumentException corrupt) {
            throw new IOException(
                    "AWSD keyframe for entity " + entityId + " failed wire validation", corrupt);
        }
    }

    private static AwsdDelta readDeltaFields(DataInputStream data) throws IOException {
        int sectionMask = data.readUnsignedByte();
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
                fluxLevel = data.readUnsignedByte();
            }
            if ((fluxFieldMask & FLUX_HARD_FRAC) != 0) {
                hardFluxFrac = data.readUnsignedByte();
            }
            if ((fluxFieldMask & FLUX_FLAGS) != 0) {
                fluxFlags = data.readUnsignedByte();
            }
            if ((fluxFieldMask & FLUX_SHIELD_FACING) != 0) {
                shieldFacingU8 = data.readUnsignedByte();
            }
        }
        List<MountDelta> mountDeltas = new ArrayList<>();
        if ((sectionMask & SECTION_MOUNT) != 0) {
            int dirtyCount = data.readUnsignedByte();
            if (dirtyCount == 0) {
                throw new IOException("AWSD mount section with zero dirty mounts"
                        + " (the encoder never mints an empty dirty section)");
            }
            for (int i = 0; i < dirtyCount; i++) {
                int mountIndex = data.readUnsignedByte();
                int fieldMask = readFieldMask(data, MOUNT_FIELD_BITS, "mount");
                if (fieldMask == 0) {
                    throw new IOException("AWSD dirty mount " + mountIndex + " with empty field mask");
                }
                int cdFrac = (fieldMask & MOUNT_CD_FRAC) != 0 ? data.readUnsignedByte() : 0;
                int chargeFrac = (fieldMask & MOUNT_CHARGE) != 0 ? data.readUnsignedByte() : 0;
                int flags = (fieldMask & MOUNT_FLAGS) != 0 ? data.readUnsignedByte() : 0;
                int ammoSteps = (fieldMask & MOUNT_AMMO) != 0 ? readSteps(data) : 0;
                mountDeltas.add(new MountDelta(mountIndex, fieldMask, cdFrac, chargeFrac, flags, ammoSteps));
            }
        }
        int fleetSide = (sectionMask & SECTION_ALLEGIANCE) != 0 ? data.readUnsignedByte() : 0;
        int engineFieldMask = 0;
        int engineDisabledMask = 0;
        int enginePermDisabledMask = 0;
        int engineControllerState = 0;
        if ((sectionMask & SECTION_ENGINE) != 0) {
            engineFieldMask = readFieldMask(data, ENGINE_FIELD_BITS, "engine");
            if ((engineFieldMask & ENGINE_DISABLED) != 0) {
                engineDisabledMask = data.readInt();
            }
            if ((engineFieldMask & ENGINE_PERM_DISABLED) != 0) {
                enginePermDisabledMask = data.readInt();
            }
            if ((engineFieldMask & ENGINE_CONTROLLER) != 0) {
                engineControllerState = data.readUnsignedByte();
            }
        }
        int maneuverMask = (sectionMask & SECTION_MANEUVER) != 0 ? data.readUnsignedByte() : 0;
        int sysFieldMask = 0;
        int sysStateCode = 0;
        int sysEffectLevel = 0;
        int sysCdSteps = 0;
        int sysAmmoWire = 0;
        if ((sectionMask & SECTION_SYSTEM) != 0) {
            sysFieldMask = readFieldMask(data, SYS_FIELD_BITS, "system");
            if ((sysFieldMask & SYS_STATE) != 0) {
                sysStateCode = data.readUnsignedByte();
            }
            if ((sysFieldMask & SYS_EFFECT) != 0) {
                sysEffectLevel = data.readUnsignedByte();
            }
            if ((sysFieldMask & SYS_CD) != 0) {
                sysCdSteps = readSteps(data);
            }
            if ((sysFieldMask & SYS_AMMO) != 0) {
                sysAmmoWire = data.readUnsignedByte();
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
                hullFrac = data.readUnsignedByte();
            }
            if ((poseFieldMask & POSE_ARMOR_FRAC) != 0) {
                armorFrac = data.readUnsignedByte();
            }
        }
        int extMask = 0;
        int pilotSeat = 0;
        int pilotMask = 0;
        int pilotFlags = 0;
        if ((sectionMask & SECTION_EXT) != 0) {
            extMask = data.readUnsignedByte();
            if ((extMask & ~ALL_EXT_BITS) != 0) {
                // An unknown ext bit's field width is unknowable: the stream position past it
                // is lost, so the whole frame is refused (fail-closed, never skipped).
                throw new IOException("AWSD delta extMask carries unknown bits: 0x"
                        + Integer.toHexString(extMask));
            }
            if ((extMask & EXT_PILOT) != 0) {
                pilotSeat = data.readUnsignedByte();
                pilotMask = data.readUnsignedByte();
                pilotFlags = data.readUnsignedByte();
                if (pilotSeat == AwsdEntityBaseline.PILOT_SEAT_NONE_WIRE && (pilotMask != 0 || pilotFlags != 0)) {
                    throw new IOException("AWSD delta pilot triple non-canonical: seat 0 with mask 0x"
                            + Integer.toHexString(pilotMask) + " flags 0x" + Integer.toHexString(pilotFlags));
                }
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
                sysFieldMask, sysStateCode, sysEffectLevel, sysCdSteps, sysAmmoWire,
                extMask, pilotSeat, pilotMask, pilotFlags);
    }

    // ------------------------------------------------------------------ apply/resolve

    /**
     * Apply a DELTA record to its anchor, producing the entity's CURRENT quantized
     * state (same epoch/anchorTick — the result is derived state, never a new anchor;
     * the anchor itself only moves on the next KEYFRAME). Throws
     * {@code IllegalArgumentException} on an entity or epoch mismatch — the D5 drop
     * signal a client turns into hold-last + NACK, and on a dirty mount index absent
     * from the anchor roster (roster changes always ride a keyframe).
     */
    public static AwsdEntityBaseline applyDelta(AwsdRecord record, AwsdEntityBaseline anchor) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(anchor, "anchor");
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
        List<AwsdEntityBaseline.Mount> mounts = anchor.mounts();
        if ((sectionMask & SECTION_MOUNT) != 0) {
            List<AwsdEntityBaseline.Mount> updated = new ArrayList<>(mounts);
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
                AwsdEntityBaseline.Mount a = updated.get(slot);
                updated.set(slot, new AwsdEntityBaseline.Mount(
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
        boolean pilot = (sectionMask & SECTION_EXT) != 0 && (delta.extMask() & EXT_PILOT) != 0;
        return new AwsdEntityBaseline(
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
                // v3 cloak block: NO delta section exists (no section bit carries it) — a
                // delta always carries the anchor's cloak fields through unchanged; cloak
                // freshness = keyframe cadence (the hold-last law).
                anchor.cloakStateWire(),
                anchor.cloakEffectFrac(),
                anchor.qCloakCd(),
                anchor.cloakAmmoWire(),
                // v6 CR block: same hold-last law — no CR delta section,
                // CR freshness = keyframe cadence.
                anchor.crU8(),
                anchor.depHalfSecU16(),
                anchor.peakHalfSecU16(),
                anchor.fighterRatePct(),
                // v14 pilot triple: ABSOLUTE when the ext section carries EXT_PILOT, else the
                // anchor's (hold-last).
                pilot ? delta.pilotSeat() : anchor.pilotSeat(),
                pilot ? delta.pilotMask() : anchor.pilotMask(),
                pilot ? delta.pilotFlags() : anchor.pilotFlags(),
                mounts);
    }

    /**
     * Mint the pose tier from grid values (the resolve law). Wire-dropped fields are
     * minted at their documented constants: spawnTick 0, armor cellCount/total/hash
     * 0/0f/0L (client-discarded pose bytes, D1; the recorder artifact keeps the real
     * ones, D2). {@code armorLevel} is wire-carried since v10 =
     * armorFrac/255. {@code hitpoints} = hullFrac/255 × maxHp and {@code hullLevel}
     * = hullFrac/255 — bit-exactly the server's quantized truth (D4).
     */
    public static AuthoritativeEntitySnapshot resolvePose(AwsdEntityBaseline baseline) {
        Objects.requireNonNull(baseline, "baseline");
        float hullLevel = baseline.hullFrac() / 255f;
        return new AuthoritativeEntitySnapshot(
                baseline.entityId(),
                baseline.contentId(),
                0L,
                baseline.ownerWire() == AwsdEntityBaseline.OWNER_NONE_WIRE
                        ? AuthoritativeEntitySnapshot.NO_OWNER_CONNECTION
                        : baseline.ownerWire(),
                baseline.qx() * POS_GRID_SU,
                baseline.qy() * POS_GRID_SU,
                baseline.qvx() * VEL_GRID,
                baseline.qvy() * VEL_GRID,
                baseline.qFacing() * FACING_GRID_DEG,
                baseline.qAngVel() * ANG_VEL_GRID,
                hullLevel * baseline.maxHp(),
                hullLevel,
                baseline.armorFrac() / 255f,
                0,
                0f,
                0L);
    }

    /** Mint the flux tier: currFlux/hardFlux reconstruct from their fractions × maxFlux. */
    public static ShipFluxSnapshot resolveFlux(AwsdEntityBaseline baseline) {
        Objects.requireNonNull(baseline, "baseline");
        return ShipFluxSnapshot.fromWire(
                baseline.entityId(),
                (baseline.fluxLevel() / 255f) * baseline.maxFlux(),
                baseline.maxFlux(),
                baseline.fluxLevel() / 255f,
                (baseline.hardFluxFrac() / 255f) * baseline.maxFlux(),
                baseline.fluxFlags(),
                baseline.shieldFacingU8());
    }

    /** Mint the mount tier (anchor order); cooldownRemaining = cdFrac × qCooldownMax grid. */
    public static List<ShipWeaponMountSnapshot> resolveMounts(AwsdEntityBaseline baseline) {
        Objects.requireNonNull(baseline, "baseline");
        List<ShipWeaponMountSnapshot> mounts = new ArrayList<>(baseline.mounts().size());
        for (AwsdEntityBaseline.Mount mount : baseline.mounts()) {
            float cooldown = mount.qCooldownMax() * MOUNT_CD_GRID_S;
            mounts.add(ShipWeaponMountSnapshot.fromWire(
                    baseline.entityId(),
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

    /** Mint the allegiance tier. */
    public static ShipAllegianceSnapshot resolveAllegiance(AwsdEntityBaseline baseline) {
        Objects.requireNonNull(baseline, "baseline");
        return new ShipAllegianceSnapshot(baseline.entityId(), baseline.fleetSide());
    }

    /** Mint the engine-state tier. */
    public static ShipEngineStateSnapshot resolveEngineState(AwsdEntityBaseline baseline) {
        Objects.requireNonNull(baseline, "baseline");
        return ShipEngineStateSnapshot.fromWire(
                baseline.entityId(),
                baseline.engineDisabledMask(),
                baseline.enginePermDisabledMask(),
                baseline.engineControllerState());
    }

    /** Mint the maneuver-command tier, with the v14 pilot triple. */
    public static ShipManeuverCommandSnapshot resolveManeuver(AwsdEntityBaseline baseline) {
        Objects.requireNonNull(baseline, "baseline");
        return ShipManeuverCommandSnapshot.fromWire(baseline.entityId(), baseline.maneuverMask(),
                baseline.pilotSeat(), baseline.pilotMask(), baseline.pilotFlags());
    }

    /**
     * Mint the system-state tier, or {@code null} for a {@code SYS_NONE} ship (v8:
     * the tier is SPARSE — null is the domain's absent shape, matching
     * an AWSS frame with no record for the entity). 0xFF ammo resolves back to the
     * {@code NO_AMMO} sentinel; fail-closed via {@code fromWire}'s project-owned 0..4
     * state-code check.
     */
    public static ShipSystemStateSnapshot resolveSystemStateOrNull(AwsdEntityBaseline baseline) {
        Objects.requireNonNull(baseline, "baseline");
        if (baseline.sysStateCode() == AwsdEntityBaseline.SYS_NONE_WIRE) {
            return null;
        }
        return ShipSystemStateSnapshot.fromWire(
                baseline.entityId(),
                baseline.sysStateCode(),
                baseline.sysEffectLevel() / 255f,
                baseline.qSysCd() * SYS_CD_GRID_S,
                baseline.sysAmmoWire() == AwsdEntityBaseline.SYS_AMMO_NONE_WIRE
                        ? ShipSystemStateSnapshot.NO_AMMO
                        : baseline.sysAmmoWire());
    }

    /**
     * Mint the v14 ship-CR tier from the v6 CR block, or {@code null} for a CR-absent
     * ship ({@code peakHalfSecU16 == 0} — the P0 D2 sentinel; the tier is SPARSE, null
     * is the domain's absent shape, matching a v14 AWSS frame with no record for the
     * entity). Dequant: cr = crU8/255, dep/peak = u16 × {@link #CR_TIME_GRID_S}.
     */
    public static ShipCrSnapshot resolveCrOrNull(AwsdEntityBaseline baseline) {
        Objects.requireNonNull(baseline, "baseline");
        if (baseline.peakHalfSecU16() == 0) {
            return null;
        }
        return new ShipCrSnapshot(
                baseline.entityId(),
                baseline.crU8() / 255f,
                baseline.depHalfSecU16() * CR_TIME_GRID_S,
                baseline.peakHalfSecU16() * CR_TIME_GRID_S,
                baseline.fighterRatePct() == AwsdEntityBaseline.FIGHTER_RATE_NONE_WIRE
                        ? ShipCrSnapshot.NO_FIGHTER_REPLACEMENT_PCT
                        : baseline.fighterRatePct());
    }

    /**
     * Mint the v10 phase-cloak tier from the v3 cloak block, or {@code null} for a
     * {@code CLOAK_NONE} ship (the tier is SPARSE — null is the domain's absent shape,
     * matching a v10 AWSS frame with no record for the entity). Fail-closed via
     * {@code fromWire}'s project-owned 0..4 state-code check.
     */
    public static ShipPhaseCloakStateSnapshot resolvePhaseCloakOrNull(AwsdEntityBaseline baseline) {
        Objects.requireNonNull(baseline, "baseline");
        if (baseline.cloakStateWire() == AwsdEntityBaseline.CLOAK_NONE_WIRE) {
            return null;
        }
        return ShipPhaseCloakStateSnapshot.fromWire(
                baseline.entityId(),
                baseline.cloakStateWire(),
                baseline.cloakEffectFrac() / 255f,
                baseline.qCloakCd() * SYS_CD_GRID_S,
                baseline.cloakAmmoWire() == AwsdEntityBaseline.SYS_AMMO_NONE_WIRE
                        ? ShipPhaseCloakStateSnapshot.NO_AMMO
                        : baseline.cloakAmmoWire());
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Facing delta = mod-720 SHORTEST-PATH signed steps (the angular law): bounded to
     * [−360, 359], so a facing delta always fits i8 or a single escape — never the hatch.
     */
    public static int facingDeltaSteps(int curFacing, int anchorFacing) {
        return Math.floorMod(curFacing - anchorFacing + 360, FACING_STEPS_PER_TURN) - 360;
    }

    private static int maskBit(boolean dirty, int bit) {
        return dirty ? bit : 0;
    }

    private static int fracU8(float fraction) {
        return Math.max(0, Math.min(255, Math.round(fraction * 255f)));
    }

    /**
     * The FLUX quantizer (rider {@code u8-flux-ceiling}): 255 is sent only AT
     * max; a level just under max rounds DOWN to 254. WHY: the client pins a copy's flux to
     * byte / 255 x max, and exactly max is the one value at which the engine refuses shield upkeep
     * (the refuse-if-over arm) and {@code okToHaveShieldOn} goes false
     * — {@link #fracU8}'s round-up made a copy at 2799.3 / 2800 drop a
     * shield the server kept (e113: 443.70 hull the server never dealt). Every other
     * fraction keeps {@link #fracU8}. Public for the codec contract's rows.
     */
    public static int fluxU8(float level) {
        if (level >= 1f) {
            return 255;
        }
        return Math.max(0, Math.min(254, Math.round(level * 255f)));
    }

    /**
     * v6 CR-clock seconds → u16 @0.5 s, SATURATED at 0xFFFF (P0 D1: never-lose-CR hulls
     * carry effective peaks ≥ 1e5 s by design — a refusal would fire every keyframe and
     * collapse AWSD to whole-tick AWSS fallback for the entire match). Negative /
     * non-finite still refuse (the existing quantize idiom; {@code ShipCrSnapshot}'s
     * ctor already rejects both, so this guard is the codec's own fail-closed mirror).
     */
    private static int crTimeU16Saturated(String label, float seconds)
            throws EncodeRefusedException {
        if (!Float.isFinite(seconds) || seconds < 0f) {
            throw new EncodeRefusedException(label + " negative or non-finite: " + seconds);
        }
        return (int) Math.min(Math.round(seconds / CR_TIME_GRID_S), 0xFFFF);
    }

    private static int quantizeI16(String label, float value, float grid)
            throws EncodeRefusedException {
        // Math.round(NaN) == 0 would silently encode grid step 0 (the
        // no-silent-clamp law) — refuse non-finite before quantizing.
        if (!Float.isFinite(value)) {
            throw new EncodeRefusedException(label + " non-finite: " + value);
        }
        int steps = Math.round(value / grid);
        if (steps < Short.MIN_VALUE || steps > Short.MAX_VALUE) {
            throw new EncodeRefusedException(
                    label + " outside i16 grid steps @" + grid + ": " + value);
        }
        return steps;
    }

    private static boolean outsideI16(long steps) {
        return steps < Short.MIN_VALUE || steps > Short.MAX_VALUE;
    }

    /**
     * Position-family quantize (the locXY widen): locX/locY ride full i32 grid steps.
     * {@code Math.round(float)} saturates at the int extremes, so ±Inf/absurd coordinates
     * fail closed on the MIN/MAX check — but {@code Math.round(NaN) == 0} would slip as
     * grid step 0 (map origin), so non-finite refuses explicitly first (
     * the no-silent-clamp law).
     */
    private static int quantizeI32(String label, float value, float grid)
            throws EncodeRefusedException {
        if (!Float.isFinite(value)) {
            throw new EncodeRefusedException(label + " non-finite: " + value);
        }
        int steps = Math.round(value / grid);
        if (steps == Integer.MIN_VALUE || steps == Integer.MAX_VALUE) {
            throw new EncodeRefusedException(
                    label + " outside i32 grid steps @" + grid + ": " + value);
        }
        return steps;
    }

    private static void writeSteps(DataOutputStream data, int steps) throws IOException {
        if (steps < Short.MIN_VALUE || steps > Short.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "cumulative steps outside i16 — the needsKeyframe hatch was skipped: " + steps);
        }
        if (steps >= -127 && steps <= 127) {
            data.writeByte(steps);
        } else {
            data.writeByte(STEP_ESCAPE_MARKER);
            data.writeShort(steps);
        }
    }

    private static int readSteps(DataInputStream data) throws IOException {
        byte first = data.readByte();
        if (first == Byte.MIN_VALUE) {
            return data.readShort();
        }
        return first;
    }

    private static int readFieldMask(DataInputStream data, int legalBits, String section)
            throws IOException {
        int fieldMask = data.readUnsignedByte();
        if ((fieldMask & ~legalBits) != 0) {
            throw new IOException("AWSD " + section + " field mask carries unknown bits: 0x"
                    + Integer.toHexString(fieldMask));
        }
        return fieldMask;
    }

    private static void requireTier(Object tier, String name) throws EncodeRefusedException {
        if (tier == null) {
            throw new EncodeRefusedException(
                    "missing " + name + " tier record (a keyframe carries every section)");
        }
    }

    private static void requireSameEntity(long entityId, long tierEntityId, String name)
            throws EncodeRefusedException {
        if (tierEntityId != entityId) {
            throw new EncodeRefusedException("cross-tier entityId mismatch at quantize: "
                    + name + " names " + tierEntityId + " but pose names " + entityId);
        }
    }

    private static void requireSameBaselineEntity(
            AwsdEntityBaseline cur, AwsdEntityBaseline anchor) {
        Objects.requireNonNull(cur, "cur");
        Objects.requireNonNull(anchor, "anchor");
        if (cur.entityId() != anchor.entityId()) {
            throw new IllegalArgumentException("baseline entity mismatch: cur "
                    + cur.entityId() + " vs anchor " + anchor.entityId());
        }
    }

    private static long checksum(byte[] body) {
        CRC32 crc = new CRC32();
        crc.update(body, 0, body.length);
        return crc.getValue();
    }

    private static void requireRegisteredWireIdentity(
            short declaredHeaderBytes, short schemaVersion, int messageTypeCode)
            throws IOException {
        if (declaredHeaderBytes != HEADER_BYTES) {
            throw new IOException(
                    "unsupported AWSD header size: " + declaredHeaderBytes);
        }
        int unsignedSchemaVersion = Short.toUnsignedInt(schemaVersion);
        ProtocolRegistryEntry entry;
        try {
            entry = NetworkMessageRegistry.authoritativeCombat().require(
                    MESSAGE_ID, unsignedSchemaVersion);
        } catch (IllegalArgumentException badSchema) {
            throw new IOException(
                    "unsupported AWSD frame schema: " + unsignedSchemaVersion, badSchema);
        }
        if (!entry.compatibilityRule().accepts(unsignedSchemaVersion, true)) {
            throw new IOException("AWSD frame schema rejected by compatibility policy: "
                    + unsignedSchemaVersion);
        }
        if (messageTypeCode != entry.wireTypeCode()) {
            throw new IOException("AWSD frame wire identity mismatch: declared "
                    + Integer.toUnsignedString(messageTypeCode)
                    + " registered " + Integer.toUnsignedString(entry.wireTypeCode()));
        }
    }
}
