package cc.starsector.server.protocol;

/**
 * Registry declaration for the AWSD delta+keyframe world-snapshot frame family:
 * a NEW wire identity beside the frozen AWSS family — the
 * snapshot-schema charter's own R1 escape route (a variable-width, dirty-masked
 * record set is never a legal AWSS tier; it is a new magic / new message id).
 * AWSS v1..v9 stays the L3-OFF path, the golden-pinned legacy, and the recorder
 * artifact; AWSD carries the same authoritative ship state as compact KEYFRAME
 * records (the L2 fold: dead-28-B dropped, quantized, statics interned) every E
 * ticks round-robin plus cumulative quantized DELTA records vs that shared
 * anchor on all other ticks.
 *
 * <p>Like every AWSS per-tier relevance row, the relevance rule here is
 * <b>reserved-dormant</b> per charter R6 (the encode-once law): per-peer
 * variance may SELECT shared keyframe/delta records, never MINT peer bytes.
 *
 * <p>This class carries only the registry identity constants + entry builder
 * (the {@code WorldSnapshotMessage} pattern); the codec is
 * {@code cc.starsector.server.runtime.AwsdSnapshotCodec}.
 */
public final class AwsdSnapshotMessage {

    public static final String MESSAGE_ID = "server.world_snapshot.awsd_delta_keyframe";
    /**
     * v14 = the pilot triple:
     *the serve's per-hull pilot mark (u8 pilotSeat, 0 = no
     * mark, seats 1..255; u8 pilotMask = the applied ability bits, slot k = bit k; u8 pilotFlags,
     * bit 0 = the pilot's MagicLib decision is followed on this hull) on the maneuver record.
     * KEYFRAME +3 B after fighterRatePct (65 to 68 B); DELTA: the last free section bit becomes
     * SECTION_EXT = 0x80, followed by ONE u8 extMask whose EXT_PILOT = 0x01 carries all three
     * ABSOLUTE bytes while the triple differs from the anchor (+4 B on such deltas only;
     * hold-last otherwise; any other ext bit refused fail-closed; seat 0 with a non-zero mask
     * or flags refused). Why: a
     * remote client's copy of a piloted hull must carry the same mark the serve's mod reads, the
     * very tick it changes, so the mod's per-hull ability behaviour shows on every window;
     * v13 = the 2-byte pilot pair (seat, mask; EXT_PILOT 2 B), never shipped — the third byte
     * rides a NEW version number so a pair-reading kit refuses a triple frame by version (the
     * reader's strict single-version throw) instead of misparsing its records;
     * v12 = the fighter-replacement percent: ONE u8 after the CR block on
     * the KEYFRAME only (0..100 panel-arithmetic percent | 0xFF absent; deltas hold-last;
     * KEYFRAME 64 to 65 B) - the served fighter panel's rate line;
     * v11 = the armor cell-run tail (D-FOEARMOR; law):
     * EVERY record — both kinds — ends with a
     * cell section {@code u8 runCount} + per run {@code u16 startIdx + u8 len +
     * len×u8 values} (absolute quantized cells, ordinal x·h+y; caps runCount ≤ 12,
     * len 1..32, section ≤ 96 B, structurally fail-closed whole-frame; semantic
     * startIdx+len ≤ cellCount = APPLY-layer per-entity refusal). KEYFRAME 63→64 B
     * (the always-present runCount). Down-negotiation contract row (charter item 3,
     * encode-once kit-lockstep family): there is no sub-v11 peer lane — a v10 client
     * never receives a v11 frame; the registry min==max==11 refuses cleanly.
     * v10 = the armor keyframe tier ({@code armorFrac} u8 appended
     * after {@code hullFrac}, KEYFRAME 62→63 B, + a {@code POSE_ARMOR_FRAC} delta
     * bit carrying the absolute u8 when dirty — corrections ride change cadence,
     * not just the round-robin. Fraction family with NO min-1 clamp: armor
     * legitimately ablates to 0; the min-1 floor is hull-only. Denominator is
     * engine-exact both sides — cellCount × getMaxArmorInCell()).
     * v9 = the side-totals mirror block (20 B appended AFTER the
     * records: per side {coordTotal, coordMax, ewTotal, ewMax, cpRate} × 2 sides as
     * u16 fixed-point ×100 — the server-authoritative Coordinated-Maneuvers /
     * Electronic-Warfare / CommRelay side aggregates the client's partial replica
     * roster cannot derive (derive-vs-carry: genuinely dynamic side state). RAW
     * totals cross, never applied artifacts — EW's cross-side dilution + per-ship
     * ECCM scaling make post-apply read-back unrecoverable. Memoryless ⇒ join
     * converges in 1 frame (the simSpeedPermille precedent).
     * v8 = the sparse system tier (NO wire-layout change — the
     * sysStateCode u8 vocabulary grows the 0xFF SYS_NONE absent sentinel + canonical
     * absent shape 0/0/0xFF; a v7 client would throw at the tier record's 0..4
     * resolve check instead of refusing cleanly, so the vocabulary rides a bump.
     * Vanilla armor modules carry NO ship system: dense requireTier refused every
     * module tick — the third fortress delta-lane blocker).
     * v7 = the maxHp widen (KEYFRAME maxHp u16→i32, 60→62 B; deltas
     * unchanged — maxHp is a static, a change forces a keyframe. u16 refused real
     * hulls: the station3 fortress base carries 160000 HP, collapsing the whole
     * shape into per-tick AWSS fallback).
     * v6 = the ship-CR KEYFRAME tier (crU8 + depHalfSecU16 + peakHalfSecU16
     * appended after the cloak block, KEYFRAME 55→60 B; deltas hold-last — no CR
     * delta section; dep/peak SATURATE at 0xFFFF @0.5 s, pins P0 D1).
     * v5 = the leg-6 sim-speed carrier (the body-prefix reserved u16 —
     * =0 enforced through v4 — re-meant as {@code simSpeedPermille}, 1000 = 1.0×,
     * announced dilation on EVERY frame; zero new bytes).
     * v4 = the shield-facing tier (1 u8 ABSOLUTE field appended to the
     * flux section, KEYFRAME 54→55 B; FLUX_FIELD_BITS 0x07→0x0F).
     * v3 = the phase-cloak KEYFRAME tier (4 wire fields appended after
     * sysAmmoWire, KEYFRAME 49→54 B; deltas hold-last — no cloak delta section).
     * v2 = the pose-grid widen (KEYFRAME locX/locY i16→i32,
     * 45→49 B). Single-version by design; v1 died unshipped (live=false, both ends
     * ride one kit) — a prior-version frame is refused at the registry, never
     * migrated (v2/v3 DID ship; their retirement is the same one-kit law, recorded
     * at the golden contract).
     */
    public static final int SCHEMA_VERSION = 14;
    public static final String CODEC_OWNER = "server.protocol.awsd_snapshot.codec";
    public static final String VALIDATION_OWNER = "server.protocol.awsd_snapshot.validation";
    /**
     * Owner+visibility-scoped like every ship-scoped AWSS tier — and equally
     * reserved-dormant (charter R6): no live serve path may consume it to mint
     * per-peer payload bytes.
     */
    public static final String RELEVANCE_RULE_ID = "awsd_snapshot.owner_visibility_scoped";
    public static final String COMPATIBILITY_PREDICATE_ID =
            "awsd_snapshot_schema_v14_delta_keyframe_compatible";
    public static final String FAILURE_CODE = "AWSD_SNAPSHOT_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.REPLACEABLE_SNAPSHOT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, true, true);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(
                    COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private AwsdSnapshotMessage() {
    }

    /**
     * AWSD registry entry (version = {@link #SCHEMA_VERSION}): server-authoritative
     * replaceable-snapshot frames on
     * the SNAP lane, own wire identity (wireTypeCode = CRC32 of {@link #MESSAGE_ID}),
     * single-version compatibility (capability rides the joiner-kit re-mint, D11 —
     * there is no in-band downgrade negotiation; an AWSD-blind client is never sent
     * AWSD).
     */
    public static ProtocolRegistryEntry registryEntry(SnapshotPublicationCadence snapshotCadence) {
        return new ProtocolRegistryEntry(
                MESSAGE_ID,
                SCHEMA_VERSION,
                MessageDirection.SERVER_UPDATE,
                ReliabilityLane.REPLACEABLE_SNAPSHOT,
                DATA_CLASS,
                CODEC_VALIDATION_OWNER,
                RELEVANCE_RULE,
                COMPATIBILITY_RULE,
                FAILURE_POLICY,
                snapshotCadence);
    }
}
