package cc.starsector.server.protocol;

/**
 * Registered server-&gt;client message announcing one tick-stamped
 * {@link GameplayEdgeEvent} (per the event-replication spec).
 *
 *
 * <p>Mirrors {@link ServerEntityDespawnEventMessage} in kind and lane: a derived
 * gameplay event the server pushes when post-step engine state shows a
 * window/discharge edge (weapon discharge, vent window, ship-system window),
 * carried on the durable gameplay-event lane so a sub-cadence window edge is never
 * dropped like a replaceable snapshot frame. The entry is additive: no existing
 * message, codec byte, or snapshot tier changes, and a client that ignores it gets
 * today's snapshot-floor behavior exactly (the spec's back-compat by absence).
 *
 * <p>Why it exists: the desync class the spec targets — stateful windows replayed at the
 * wrong local time — arises because a client must infer window state from
 * <em>sampled</em> snapshot tiers at snapshot cadence. This message carries the
 * exact completed {@code simTick} of each engine-accepted edge so the client
 * replays windows at the right point on its reconciliation timeline. Its producer
 * is {@code GameplayEdgeEventDetector} (the B1 detector + smoke, proven game-free;
 * NOT yet serve-loop-wired — see that
 * detector's class doc) and its wire framing is {@code GameplayEdgeEventCodec}.
 */
public final class ServerGameplayEdgeEventMessage {

    public static final String MESSAGE_ID = "server.gameplay_edge_event";
    /**
     * v10 (Guardian-PD det): adds kind
     * {@code PALADIN_DET(8)} (kind pin {@link #PALADIN_DET_MIN_SCHEMA}) with frame bytes
     * UNCHANGED (66 B, the v9 width — det x/y ride the two reused float slots, the
     * v3/v4/v5 add-a-kind form): the server-side birth of a Paladin-PD script
     * {@code DamagingExplosion} (spec fingerprint r75/dmg100/FRAGMENTATION, whitelist
     * law), which measurement proved never crosses classifiably otherwise (measured:
     * guardian contentId is beam-only; script dets cross content-LESS on the replaceable
     * snapshot lane) while the replica-side local gate is unconditionally closed
     * (because {@code CollisionClass.NONE} ⇒ {@code getDamageTarget()==null}).
     * Client consumption = damage-LESS FX only ({@code PaladinDetFxLane}).
     * v9 (autofire-TARGET sync lane): appends {@code targetWireId} — the
     * stable network id of the ship the server's autofire plugin picked for
     * {@code (entityId, mountIndex)} (raw long; {@code GameplayEdgeEvent.TARGET_NONE} =
     * pick cleared, canonical {@code GameplayEdgeEvent.TARGET_ABSENT} on every other
     * kind) — at the frame tail, widening the frame 58→66 B (the v6/v8 widen-frame
     * template verbatim; width pin {@link #TARGET_WIRE_ID_MIN_SCHEMA}). Adds kind
     * {@code AUTOFIRE_TARGET(7)} (kind pin {@link #AUTOFIRE_TARGET_MIN_SCHEMA}): the
     * server→owning-client mirror of the ECB {@code applyShipTarget} designation lane,
     * per-WEAPON — emitted on pick-change (incl. →null) + a ~60-tick keepalive while a
     * non-null pick is held, seat ships only ({@code AutofireTargetLane}). Evidence: the
     * client's local autofire pick diverges from the server's on the SAME engaged cluster
     * (strict pairs >45° = 15/15 different ship) — the pick, not the aim
     * math, is the desync axis.
     * v8 (spawn-truth): appends
     * {@code muzzleX,muzzleY} — the server-true world position a {@code WEAPON_DISCHARGE}'s
     * projectile was born at (raw float32 pair; both-finite-or-both-NaN, {@code NaN} =
     * honest absence, canonical {@code NaN} pair on every other kind) — at the frame tail,
     * widening the frame 50→58 B (the v6 widen-frame template verbatim). Completes
     * spawn-truth for the victim's composed ray: bearing, {@code birthSpeed} and the fire
     * tick already ride the frame; the muzzle was the one axis still re-derived from a
     * lagged shooter replica. NOT class-gated — every
     * projectile benefits. No handshake bump; GPEE rides its own durable CTRL frame (zero
     * pressure on the 1164 B datagram payload cap).
     * v7: SEMANTIC widening only — kind
     * {@code MISSILE_END(6)} now confesses SOURCED missile ends too (every vanished
     * missile with a readable post-mortem triage), not just the v5 sourceless
     * mine/system-spawn family. Frame bytes UNCHANGED (50 B, the v6 width): id/cause/
     * endX/endY were already in the frame; {@link #MISSILE_END_SOURCED_MIN_SCHEMA} pins
     * the semantics so a consumer may rely on sourced-end confession ONLY at v7+
     * (a v5/v6 archive's MISSILE_END silence about a sourced missile means
     * "absence-inferred", never "survived"). Volume law: ends ≤ births, each end riding
     * its OWN CTRL datagram — zero bytes added to any snapshot datagram (the
     * per-datagram cap unaffected).
     * v6: appends {@code birthSpeed} — the {@code |v0|}
     * birth-velocity magnitude of a {@code WEAPON_DISCHARGE} (engine units/sec; {@code NaN}
     * = honest absence, canonical {@code NaN} on every other kind) — at the frame tail,
     * widening the frame 46→50 B (the v2 widen-frame template). v5
     * added kind {@code MISSILE_END(6)} — the sourceless-missile
     * end-cause marker whose {@code mountIndex} slot carries the post-mortem triage code
     * ({@code GameplayEdgeEvent.END_CAUSE_*}) and whose two float slots carry the last
     * authoritative {@code endX}/{@code endY} — with frame width UNCHANGED. v4
     * added kind {@code MISSILE_SPLIT(5)} the same way, v3
     * kind {@code SYSTEM_JUMP(4)}. v2
     * appended {@code flightBearingDeg} after {@code aimFacingDeg}. The writer always
     * stamps the current schema; the server-side codec READS v1
     * ({@code flightBearingDeg=NaN}), v2, v3, v4 and v5 frames ({@code birthSpeed=NaN}
     * below {@link #BIRTH_SPEED_MIN_SCHEMA}) so every archived {@code .ccwr}
     * stays decodable (schema-charter R4 spirit), but rejects kind 4 below
     * {@link #SYSTEM_JUMP_MIN_SCHEMA}, kind 5 below {@link #MISSILE_SPLIT_MIN_SCHEMA}
     * and kind 6 below {@link #MISSILE_END_MIN_SCHEMA}
     * (an archive schema can never smuggle a kind its version predates).
     *
     * <p><b>Down-negotiation contract row (charter Bump item (3), side-frame form):
     * </b> GPEE frames encode ONCE and fan byte-identical to every peer
     * (encode-once; there is NO govern()/negotiation lane for side frames).
     * The governing pair is therefore: the client reader is fail-closed EXACT-MATCH on
     * schema (charter R5), and kit lockstep (jar-fingerprint admission) pins every joiner
     * to this build — so "a v4 client never receives kind 6" holds BY CONSTRUCTION.
     * An AWSS-style per-peer down-strip was considered and adjudicated NOT owed
     * (it would break encode-once). Re-pack the joiner kit after deploying this bump.
     */
    /** v13: width UNCHANGED (66 B) — kind {@code ARC_HIT(9)} appended
     *  (the add-a-kind form, the v10 PALADIN_DET template): the server names every EMP-arc hit
     *  (source/victim wire ids, node point, flavor code, applied armor/hull/shield split in the
     *  existing slots) off its {@code DamageListener} seam; DOWN-only. Pin =
     *  {@link #ARC_HIT_MIN_SCHEMA} (the kind-pin idiom). Re-pack the joiner kit. */
    /** v12: width UNCHANGED (66 B) — the SYSTEM_WINDOW ON edge
     *  of a {@code mote_control} press MAY carry a finite target X/Y in the two float slots and
     *  the lock's wire id (or TARGET_NONE) in the long slot; the bump exists so an unrebuilt
     *  client (which fail-closes on that payload) is caught by the session-start parity check
     *  and kit lockstep, never mid-flight. v11 is the RETIRED D-BARREL number (never reused). */
    /** v14: width UNCHANGED (66 B) —
     *  kind {@code CRIT_MALFUNCTION(10)} appended (the add-a-kind form, the v13 ARC_HIT template):
     *  the server names every engine crit (victim wire id, module code, permanent flag, branch,
     *  hull amount in the existing slots) off the {@code CombatReadinessPlugin} seam; DOWN-only.
     *  Pin = {@link #CRIT_MALFUNCTION_MIN_SCHEMA}. Re-pack the joiner kit. */
    /** v15: width UNCHANGED (66 B) — SEMANTIC widening of {@code MISSILE_END(6)}
     *  (the v12 template): the long slot names the VICTIM ship of the missile's LAST engine impact
     *  ({@code > 0}, else {@code TARGET_ABSENT} as before) and the end-cause vocabulary gains
     *  {@code END_CAUSE_SHIELD_ABSORBED(3)} — the server's own {@code DamageListener} memo joined at
     *  delist, so the client's devour end-hook mints a DoT ONLY on a server-named HULL contact (the
     *  phantom-DoT class). Pin = {@link #MISSILE_END_VICTIM_MIN_SCHEMA}; DOWN-only.
     *  Re-pack the joiner kit (no handshake bump: the v13/v14 precedent — the door's kit-fingerprint
     *  gate refuses pre-bump kits, and the client reader exact-matches the schema). */
    /** v16: width UNCHANGED (66 B) — SEMANTIC widening of {@code WEAPON_DISCHARGE(1)}
     *  (the v15 template): the long slot carries the DECLARED WAIT — the raw ticks from the consumed
     *  declaration's {@code applyTick} to this discharge edge's {@code simTick}, clamped to
     *  {@code GameplayEdgeEvent.DECLARED_WAIT_MAX_TICKS} (255), or {@code TARGET_ABSENT} (-1) when the
     *  discharge consumed no stamped declaration (a ready gun reads 0, never absent). Stamped server-side
     *  after the step ({@code PilotIntentStep.dispatchDischargeEdges}); bookkeeping only — no shot is served
     *  earlier or later. Pin = {@link #DECLARED_WAIT_MIN_SCHEMA}; DOWN-only. Re-pack the joiner kit (the
     *  v15 precedent: no handshake bump; the client reader exact-matches the schema).
     * */
    public static final int SCHEMA_VERSION = 16;
    /** v15 = the 66 B MISSILE_END victim schema every pre-v16 archive carries — READ-only tolerance:
     *  its {@code WEAPON_DISCHARGE} long slot is always {@code TARGET_ABSENT}. */
    public static final int SCHEMA_VERSION_V15 = 15;
    public static final int SCHEMA_VERSION_V14 = 14;
    public static final int SCHEMA_VERSION_V13 = 13;
    public static final int SCHEMA_VERSION_V12 = 12;
    public static final int SCHEMA_VERSION_V10 = 10;
    public static final int SCHEMA_VERSION_V9 = 9;
    public static final int SCHEMA_VERSION_V8 = 8;
    public static final int SCHEMA_VERSION_V7 = 7;
    public static final int SCHEMA_VERSION_V6 = 6;
    public static final int SCHEMA_VERSION_V5 = 5;
    public static final int SCHEMA_VERSION_V4 = 4;
    public static final int SCHEMA_VERSION_V3 = 3;
    public static final int SCHEMA_VERSION_V2 = 2;
    public static final int SCHEMA_VERSION_V1 = 1;

    /**
     * The lowest schema whose taxonomy contains {@code SYSTEM_JUMP(4)}. The codec's
     * read tolerance spans v1..current; this pin keeps a FUTURE bump from silently
     * widening what old-schema archives may claim to carry.
     */
    public static final int SYSTEM_JUMP_MIN_SCHEMA = 3;

    /**
     * The lowest schema whose taxonomy contains {@code MISSILE_SPLIT(5)} (the
     * {@link #SYSTEM_JUMP_MIN_SCHEMA} pin idiom: read tolerance spans
     * v1..current, but an old-schema archive may never claim a kind its version predates).
     */
    public static final int MISSILE_SPLIT_MIN_SCHEMA = 4;

    /**
     * The lowest schema whose taxonomy contains {@code MISSILE_END(6)} (the
     * {@link #SYSTEM_JUMP_MIN_SCHEMA} pin idiom: read tolerance spans
     * v1..current, but an old-schema archive may never claim a kind its version predates).
     */
    public static final int MISSILE_END_MIN_SCHEMA = 5;

    /**
     * The lowest schema whose frame carries the {@code birthSpeed} tail float (v6 —
     * a WIDTH pin, not a kind pin: the versioned tail-read idiom, mirror of the codec's
     * v1 bearing gate). Reads below it decode {@code birthSpeed=NaN}; expressed as a
     * {@code >=} pin so a FUTURE bump can never silently NaN v6 archives
     * (the silent-default trap).
     */
    public static final int BIRTH_SPEED_MIN_SCHEMA = 6;

    /**
     * The lowest schema whose {@code MISSILE_END(6)} taxonomy covers SOURCED missiles
     * (v7) — a SEMANTIC pin, not a width or kind pin: the
     * frame bytes are v6's exactly, but below this version the kind was emitted for
     * sourceless missiles only, so a consumer replaying an older archive must fall back
     * to absence-inferred ends for sourced ids (silence ≠ survived there).
     */
    public static final int MISSILE_END_SOURCED_MIN_SCHEMA = 7;

    /**
     * The lowest schema whose frame carries the {@code muzzleX,muzzleY} tail pair (v8 —
     * a WIDTH pin like {@link #BIRTH_SPEED_MIN_SCHEMA}, the versioned tail-read idiom).
     * Reads below it decode the pair {@code NaN}/{@code NaN}; a {@code >=} pin so a
     * FUTURE bump can never silently NaN v8 archives (the silent-default trap).
     */
    public static final int MUZZLE_MIN_SCHEMA = 8;

    /**
     * The lowest schema whose taxonomy contains {@code AUTOFIRE_TARGET(7)} (the
     * {@link #SYSTEM_JUMP_MIN_SCHEMA} pin idiom: read tolerance spans
     * v1..current, but an old-schema archive may never claim a kind its version predates).
     */
    public static final int AUTOFIRE_TARGET_MIN_SCHEMA = 9;

    /**
     * The lowest schema whose frame carries the {@code targetWireId} tail long (v9 —
     * a WIDTH pin like {@link #BIRTH_SPEED_MIN_SCHEMA}, the versioned tail-read idiom).
     * Reads below it decode {@code GameplayEdgeEvent.TARGET_ABSENT} — never 0; a
     * {@code >=} pin so a FUTURE bump can never silently sentinel v9 archives
     * (the silent-default trap).
     */
    public static final int TARGET_WIRE_ID_MIN_SCHEMA = 9;

    /**
     * The lowest schema whose taxonomy contains {@code PALADIN_DET(8)} (the
     * {@link #SYSTEM_JUMP_MIN_SCHEMA} pin idiom: read tolerance spans
     * v1..current, but an old-schema archive may never claim a kind its version predates).
     */
    public static final int PALADIN_DET_MIN_SCHEMA = 10;

    /**
     * The lowest schema whose taxonomy contains {@code ARC_HIT(9)} (the same
     * kind-pin idiom as {@link #PALADIN_DET_MIN_SCHEMA}).
     */
    public static final int ARC_HIT_MIN_SCHEMA = 13;

    /**
     * The lowest schema whose taxonomy contains {@code CRIT_MALFUNCTION(10)} (the
     * same kind-pin idiom as {@link #ARC_HIT_MIN_SCHEMA}: v14 = add-a-kind, width unchanged
     * 66 B).
     */
    public static final int CRIT_MALFUNCTION_MIN_SCHEMA = 14;

    /**
     * The lowest schema whose {@code MISSILE_END(6)} may NAME a victim ({@code targetWireId > 0})
     * or read {@code END_CAUSE_SHIELD_ABSORBED} (v15) — a SEMANTIC pin like
     * {@link #MISSILE_END_SOURCED_MIN_SCHEMA}: the bytes are v14's exactly, but an older archive
     * may never claim either (the pin idiom; the codec read rejects a pre-v15 frame carrying them).
     */
    public static final int MISSILE_END_VICTIM_MIN_SCHEMA = 15;

    /**
     * The lowest schema whose {@code WEAPON_DISCHARGE(1)} may carry a DECLARED WAIT
     * ({@code targetWireId} in {@code 0..DECLARED_WAIT_MAX_TICKS}; v16) — a SEMANTIC pin
     * like {@link #MISSILE_END_VICTIM_MIN_SCHEMA}: the bytes are v15's exactly, but an older archive may
     * never claim one (the codec read rejects a pre-v16 discharge whose slot is not {@code TARGET_ABSENT}).
     *
     */
    public static final int DECLARED_WAIT_MIN_SCHEMA = 16;
    public static final String CODEC_OWNER = "server.protocol.gameplay_edge_event.codec";
    public static final String VALIDATION_OWNER = "server.protocol.gameplay_edge_event.validation";
    public static final String RELEVANCE_RULE_ID = "gameplay_edge_event.tick_stamped_window_discharge_edge";
    public static final String COMPATIBILITY_PREDICATE_ID = "gameplay_edge_event_schema_v1_v10";
    public static final String FAILURE_CODE = "GAMEPLAY_EDGE_EVENT_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.DURABLE_EVENT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, false, false);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION_V1, SCHEMA_VERSION, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    private ServerGameplayEdgeEventMessage() {
    }

    public static ProtocolRegistryEntry registryEntry(SnapshotPublicationCadence snapshotCadence) {
        return new ProtocolRegistryEntry(
                MESSAGE_ID,
                SCHEMA_VERSION,
                MessageDirection.SERVER_BROADCAST,
                ReliabilityLane.DURABLE_GAMEPLAY_EVENT,
                DATA_CLASS,
                CODEC_VALIDATION_OWNER,
                RELEVANCE_RULE,
                COMPATIBILITY_RULE,
                FAILURE_POLICY,
                snapshotCadence);
    }
}
