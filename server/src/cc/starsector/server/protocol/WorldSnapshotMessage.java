package cc.starsector.server.protocol;

import java.util.Objects;

/**
 * Versioned product-owned envelope for an authoritative per-entity world
 * snapshot ({@link AuthoritativeWorldSnapshot}).
 *
 * <p>This is the registry declaration for the replication
 * payload: a server update carried on the replaceable-snapshot lane, classified
 * as replaceable snapshot data, with a visibility-scoped relevance rule that the
 * server's {@link ClientRelevanceView} interest filter implements. It is distinct
 * from {@code server.offline_combat.step_snapshot}, which carries only aggregate
 * counts and a single opaque state hash; this message carries reconstructable
 * per-entity authoritative state.
 */
public record WorldSnapshotMessage(
        ProtocolRegistryEntry registryEntry,
        AuthoritativeWorldSnapshot snapshot) {

    public static final String MESSAGE_ID = "server.world_snapshot.authoritative_entities";
    public static final int SCHEMA_VERSION = 1;
    /**
     * Schema v2: the same world-snapshot message, now declaring the
     * non-ship critical-state tier (projectiles/missiles/beams) as part of the
     * frame contract. A v2 frame's non-ship section is mandatory and
     * self-announced; v1 stays ship-only (with a length-driven back-compat decode
     * affordance for the transitional captures).
     */
    public static final int SCHEMA_VERSION_V2 = 2;
    /**
     * Schema v3: the same world-snapshot message, now also declaring the
     * per-ship flux + shield combat-effectiveness tier as part of the
     * frame contract. A v3 frame carries the non-ship section (even when empty, so the
     * decoder can locate the flux section that follows it) plus a self-announced flux
     * section. v1/v2 frames are unchanged.
     */
    public static final int SCHEMA_VERSION_V3 = 3;
    /**
     * Schema v4: the same world-snapshot message, now also declaring the
     * per-mount weapon-readiness tier as part of the frame contract. A v4
     * frame carries the non-ship section and the flux section (both possibly empty, so
     * the decoder can locate the weapon-mount section that follows them) plus a
     * self-announced weapon-mount section. v1/v2/v3 frames are unchanged.
     */
    public static final int SCHEMA_VERSION_V4 = 4;
    /**
     * Schema v5: the same world-snapshot message, now also declaring the
     * per-ship combat-SIDE (friend/foe) allegiance tier as part of the frame
     * contract. A v5 frame carries the non-ship, flux, and weapon-mount sections (all possibly
     * empty, so the decoder can locate the allegiance section that follows them) plus a
     * self-announced allegiance section. v1/v2/v3/v4 frames are unchanged. This is the
     * genuinely next WIRED tier above v4; the {@code ShipProfileSnapshot} tier
     * ({@code AuthoritativeWorldSnapshot.profileStates}) is a deferred, never-encoded Stage-A
     * in-memory tier and is intentionally NOT assigned a wire schema here.
     */
    public static final int SCHEMA_VERSION_V5 = 5;
    /**
     * Schema v6: the same world-snapshot message, now also declaring the
     * per-ship engine-disabled/flameout tier as part of the frame contract. A v6 frame
     * carries the non-ship, flux, weapon-mount, and allegiance sections (all possibly
     * empty, so the decoder can locate the engine-state section that follows them) plus a
     * self-announced engine-state section. v1..v5 frames are unchanged. Flameout is
     * DERIVED state on independently-stochastic per-engine health trackers, so a
     * predictive client cannot converge on it implicitly (the WATCH desync);
     * this tier carries the server's per-engine truth.
     */
    public static final int SCHEMA_VERSION_V6 = 6;
    /**
     * Schema v7: the same world-snapshot message, now also declaring the
     * per-ship maneuver-COMMAND tier as part of the frame contract. A v7 frame carries
     * the non-ship, flux, weapon-mount, allegiance, and engine-state sections (all
     * possibly empty, so the decoder can locate the maneuver-command section that
     * follows them) plus a self-announced maneuver-command section. v1..v6 frames are
     * unchanged. The engine's thrust-plume/glow/contrail presentation (and the engine
     * sound loop) is driven ONLY by the maneuver commands drained from a ship's
     * per-frame command queue — a pose-snapped foe replica idles its engines forever
     * (the observed symptom) — so this tier carries the server's per-ship 7-bit
     * maneuver-command mask for the client to re-issue through the engine's own
     * faithful {@code giveCommand} path.
     */
    public static final int SCHEMA_VERSION_V7 = 7;
    /**
     * Schema v8: the same world-snapshot message, now also declaring the
     * per-ship primary ship-SYSTEM readiness tier as part of the frame contract. A v8
     * frame carries the non-ship, flux, weapon-mount, allegiance, engine-state, and
     * maneuver-command sections (all possibly empty, so the decoder can locate the
     * system-state section that follows them) plus a self-announced system-state
     * section. v1..v7 frames are unchanged. The steady-state lane replicates only
     * system activation EDGES, so a client that joins (or first sights an entity)
     * mid-activation has no edge to replay and idles the replica's system — this tier
     * carries the server's phase/progress/ammo truth for a one-shot faithful
     * {@code forceState} seed through the engine's own path.
     */
    public static final int SCHEMA_VERSION_V8 = 8;
    /**
     * Schema v9: the same world-snapshot message, now also
     * declaring the FRAGMENT_MARKER tail tier as part of the frame contract. A v9
     * frame carries every lower section (all possibly empty, so the decoder can locate
     * the marker section that follows them) plus a self-announced fragment-marker
     * section. v1..v8 frames are unchanged — the marker section exists exactly when the
     * frame carries a PARTIAL entity (entity-tier fragmentation v2: one over-cap
     * entity's tier records split across chunk frames, each declaring which tiers and
     * mount range it asserts; ABSENT ≠ EMPTY semantics, spec).
     *
     */
    public static final int SCHEMA_VERSION_V9 = 9;
    /**
     * Schema v10: the same world-snapshot message, now also
     * declaring the per-ship PHASE-CLOAK readiness tier as part of the frame contract.
     * A v10 frame carries every lower section (all possibly empty — including the v9
     * fragment-marker section, so the decoder can locate the phase-cloak section that
     * follows it) plus a self-announced phase-cloak section. v1..v9 frames are
     * unchanged. The cloak lives on the separate {@code getPhaseCloak()} slot (never
     * the v8 primary-system tier), and its activation edges alone cannot seed a
     * mid-join replica — this tier carries the server's phase/progress truth for a
     * one-shot faithful {@code forceState} seed; the per-frame collision truth rides
     * the flux tier's {@code FLAG_PHASED} status bit (both halves of D-M2-1).
     */
    public static final int SCHEMA_VERSION_V10 = 10;
    /**
     * Schema v11: the same world-snapshot message with the FLUX tier record
     * widened from 28 to 29 bytes — one quantized shield-facing u8
     * ({@code ShipFluxSnapshot#shieldFacingU8}, 360/256° steps) inserted between
     * {@code hardFluxCurrent} and {@code statusFlags}. No new section; every section
     * layout is otherwise v10's. The stamp predicate is VALUE-based: a
     * frame stamps v11 only when at least one flux record carries facing != 0, so all
     * v1..v10 canonical content (facing 0 via the back-compat ctors) keeps its stamp and
     * bytes, and schema-capped serve-down of shielded flux stays expressible via
     * {@code AuthoritativeWorldSnapshot#withShieldFacingZeroed()}.
     */
    public static final int SCHEMA_VERSION_V11 = 11;
    /**
     * Schema v12: the same world-snapshot message with each kind=BEAM
     * non-ship record widened by a source binding — {@code sourceEntityId} (long;
     * 0 = unresolved) + {@code sourceMountIndex} (int; −1 = unresolved) — tying a
     * live beam to its EMITTER's wire entityId and the mount ordinal on that
     * emitter, so a client can drive the puppet hull's own mount aim at the beam's
     * impact point. The emitter is normally the firing ship + its
     * {@code ServerWeaponMountState} mount ordinal; a vanilla DEM payload beam
     * instead carries the owning child MISSILE's v13a stable wire id
     * (at or above {@code CombatEntityView#MISSILE_NETWORK_ID_BASE}, which is what
     * tells the two id spaces apart) + the ordinal on that child's fx drone, and the
     * client's DEM presenter — not the ship beam-aim lane — consumes it. Record
     * width is keyed by KIND within a v12 body (projectile/missile records keep the
     * v2 width); a v12 frame also forces the v11 widened flux record regardless of
     * facing values (higher-tier-forces-lower). The stamp predicate is VALUE-based
     * (v11 precedent): a frame stamps v12 only when at least one beam record carries
     * a resolved source, so all source-less content keeps its stamp and bytes.
     */
    public static final int SCHEMA_VERSION_V12 = 12;
    /**
     * Schema v13: the same world-snapshot message with each kind=MISSILE
     * non-ship record widened by the v12 source binding pair — {@code sourceEntityId}
     * (long; 0 = unresolved) + {@code sourceMountIndex} (int; −1 = unresolved) —
     * tying a live missile to its launching ship's wire entityId and the
     * {@code ServerWeaponMountState} mount ordinal. Missile records additionally
     * carry the server-LIFETIME stable networkId partitioned at
     * {@code CombatEntityView#MISSILE_NETWORK_ID_BASE} (v13a; the id is an existing
     * field, no layout change). Record width stays keyed by KIND within a v13 body,
     * with higher-tier-forces-lower applied to widths: in a v13 body EVERY beam
     * record carries the v12 pair and every missile record the v13 pair (projectile
     * records keep the v2 width); a v13 frame also forces the v11 widened flux
     * record and the v10 phase-cloak section. The stamp predicate is VALUE-based
     * (v11/v12 precedent) and rides the MISSILE predicate alone: a frame stamps v13
     * only when at least one missile record carries a resolved source (beam sources
     * may be all-sentinel in a v13 frame), so all missile-source-less content keeps
     * its stamp and bytes.
     */
    public static final int SCHEMA_VERSION_V13 = 13;
    /**
     * Schema v14: the same world-snapshot message with a NEW per-ship
     * COMBAT-READINESS section appended at the end of the body (after the v10
     * phase-cloak section): one 20 B record per enrolled ship — entityId u64 +
     * cr/timeDeployedSec/peakSec f32bits — carrying the authoritative CR clock a
     * predict client's own-CR pin consumes (the LOCAL clock freezes under a
     * different roster scan). v1..v13 frames are unchanged. The
     * stamp predicate is VALUE-based like v10: a frame stamps v14 only when at
     * least one CR record is present, and an exactly-v14 frame with zero records
     * is rejected fail-closed.
     */
    public static final int SCHEMA_VERSION_V14 = 14;
    /**
     * Schema v15: the same world-snapshot message with a NEW FRAME-GLOBAL
     * CONTENT-DICTIONARY section appended at the end of the body (after the v14
     * ship-CR section): a small cycling window of contentId → true content key
     * announcements (variable-width records — contentId u32 + one-byte UTF-8 key
     * length + key bytes) so a client resolves interned non-ship contentIds to real
     * spec ids for presentation without any request channel. v1..v14 frames are
     * unchanged. The stamp predicate is count-based like v14: a frame stamps v15
     * only when at least one dictionary entry is present, and an exactly-v15 frame
     * with zero entries is rejected fail-closed (the CR section MAY be empty in a
     * v15 frame).
     */
    public static final int SCHEMA_VERSION_V15 = 15;
    /**
     * Schema v16: the same world-snapshot message with each
     * kind=MISSILE non-ship record widened by the missile-pose pair —
     * {@code poseFacingU16} (u16, 360°/65536 steps, minted ONCE at the engine read)
     * + {@code poseFlags} (u8: bit0 POSE_VALID, bit1 FIZZLING; spares write-0 /
     * decode-MASKED-not-rejected) — carrying the server missile's true facing and
     * burnout status so a client renders the vanilla death arc instead of
     * reconstructing facing from drift. Record width stays keyed by KIND within a
     * v16 body with higher-tier-forces-lower applied to widths and sections: EVERY
     * missile record carries source pair + pose, beams the v12 pair, flux the v11
     * width, and the CR + content-dictionary tail sections are present (BOTH may be
     * empty in a v16 frame — the exactly-v15 ≥1-entry dictionary check stays
     * exactly-v15). The stamp predicate is VALUE-based (v11/v12/v13 precedent): a
     * frame stamps v16 only when at least one missile record carries nonzero
     * poseFlags, so all pose-less content keeps its stamp and bytes.
     */
    public static final int SCHEMA_VERSION_V16 = 16;
    public static final String CODEC_OWNER = "server.protocol.world_snapshot.codec";
    public static final String VALIDATION_OWNER = "server.protocol.world_snapshot.validation";
    public static final String RELEVANCE_RULE_ID = "world_snapshot.visibility_scoped_relevant_entities";
    /**
     * Relevance rule that scopes the v2 non-ship tier. Transient projectiles/beams
     * are visibility-scoped only (received when near the connection's view center);
     * they are not owner-scoped the way ships are, since the {@code owner} a
     * non-ship carries is a combat side, not a per-connection authority id.
     */
    public static final String NONSHIP_RELEVANCE_RULE_ID = "world_snapshot.nonship_visibility_scoped";
    /**
     * Relevance rule that scopes the v3 flux tier. Flux belongs to a ship, so it is
     * scoped exactly like the ship tier — owner-scoped AND visibility-scoped: a
     * connection that is not told a far ship's motion is not told its flux either.
     */
    public static final String FLUX_RELEVANCE_RULE_ID = "world_snapshot.flux_owner_visibility_scoped";
    /**
     * Relevance rule that scopes the v4 weapon-mount tier. A weapon mount belongs to a
     * ship, so it is scoped exactly like the ship and flux tiers — owner-scoped AND
     * visibility-scoped: a connection that is not told a far ship's motion is not told
     * its per-mount readiness either.
     */
    public static final String WEAPON_MOUNT_RELEVANCE_RULE_ID =
            "world_snapshot.weapon_mount_owner_visibility_scoped";
    /**
     * Relevance rule that scopes the v5 combat-side tier. Allegiance belongs to a ship, so it
     * is scoped exactly like the ship, flux, and weapon-mount tiers — owner-scoped AND
     * visibility-scoped: a connection that is not told a far ship's motion is not told its side
     * either.
     */
    public static final String ALLEGIANCE_RELEVANCE_RULE_ID =
            "world_snapshot.allegiance_owner_visibility_scoped";
    /**
     * Relevance rule that scopes the v6 engine-state tier. Engine state belongs to a ship,
     * so it is scoped exactly like the ship, flux, weapon-mount, and allegiance tiers —
     * owner-scoped AND visibility-scoped: a connection that is not told a far ship's motion
     * is not told its engine state either.
     */
    public static final String ENGINE_STATE_RELEVANCE_RULE_ID =
            "world_snapshot.engine_state_owner_visibility_scoped";
    /**
     * Relevance rule that scopes the v7 maneuver-command tier. Maneuver commands belong to a
     * ship, so they are scoped exactly like the ship, flux, weapon-mount, allegiance, and
     * engine-state tiers — owner-scoped AND visibility-scoped: a connection that is not told
     * a far ship's motion is not told its maneuver commands either.
     */
    public static final String MANEUVER_COMMAND_RELEVANCE_RULE_ID =
            "world_snapshot.maneuver_command_owner_visibility_scoped";
    /**
     * Relevance rule that scopes the v8 system-state tier. A primary ship system belongs
     * to a ship, so it is scoped exactly like the other ship-scoped tiers — owner-scoped
     * AND visibility-scoped. NOTE: like every per-tier relevance row on the AWSS entries,
     * this rule is <b>reserved-dormant</b> under the encode-once law: it
     * stays registered, but no live serve path may consume it to mint per-peer AWSS
     * payload bytes — per-peer variance may SELECT shared chunks, never MINT peer bytes.
     * Sole live consumer today is the game-free relevance-safe-despawn verifier lane.
     */
    public static final String SYSTEM_STATE_RELEVANCE_RULE_ID =
            "world_snapshot.system_state_owner_visibility_scoped";
    /**
     * Relevance rule that scopes the v9 fragment-marker tier. A marker declares a ship's
     * partial carriage, so it is scoped exactly like the other ship-scoped tiers —
     * owner-scoped AND visibility-scoped (a marker travels with its ship's records).
     * Like every per-tier relevance row it is <b>reserved-dormant</b> under the
     * encode-once law: per-peer variance may SELECT shared chunks, never MINT
     * peer bytes.
     */
    public static final String FRAGMENT_MARKER_RELEVANCE_RULE_ID =
            "world_snapshot.fragment_marker_owner_visibility_scoped";
    /**
     * Relevance rule that scopes the v10 phase-cloak tier. A phase cloak belongs to a
     * ship, so it is scoped exactly like the other ship-scoped tiers — owner-scoped AND
     * visibility-scoped. Like every per-tier relevance row it is <b>reserved-dormant</b>
     * under the encode-once law: per-peer variance may SELECT shared
     * chunks, never MINT peer bytes.
     */
    public static final String PHASE_CLOAK_RELEVANCE_RULE_ID =
            "world_snapshot.phase_cloak_owner_visibility_scoped";
    /**
     * Relevance rule that scopes the v11 shield-facing widening of the flux tier. The
     * facing byte rides inside the flux record, so it is scoped exactly like the flux
     * tier — owner-scoped AND visibility-scoped. Like every per-tier relevance row it is
     * <b>reserved-dormant</b> under the encode-once law: per-peer
     * variance may SELECT shared chunks, never MINT peer bytes.
     */
    public static final String SHIELD_FACING_RELEVANCE_RULE_ID =
            "world_snapshot.shield_facing_owner_visibility_scoped";
    /**
     * Relevance rule that scopes the v12 beam-source widening of the non-ship tier.
     * The source pair rides inside the kind=BEAM non-ship record, so it is scoped
     * exactly like the non-ship tier — visibility-scoped only (a beam's owner is a
     * combat side, not a per-connection authority id). Like every per-tier
     * relevance row it is <b>reserved-dormant</b> under the
     * encode-once law: per-peer variance may SELECT shared chunks, never MINT
     * peer bytes.
     */
    public static final String BEAM_SOURCE_RELEVANCE_RULE_ID =
            "world_snapshot.beam_source_visibility_scoped";
    /**
     * Relevance rule that scopes the v13 missile-source widening of the non-ship
     * tier. The source pair rides inside the kind=MISSILE non-ship record, so it is
     * scoped exactly like the non-ship tier — visibility-scoped only (a missile's
     * owner is a combat side, not a per-connection authority id). Like every
     * per-tier relevance row it is <b>reserved-dormant</b> under the
     * encode-once law: per-peer variance may SELECT shared chunks, never MINT
     * peer bytes.
     */
    public static final String MISSILE_SOURCE_RELEVANCE_RULE_ID =
            "world_snapshot.missile_source_visibility_scoped";
    /**
     * Relevance rule that scopes the v14 CR tier. A CR clock belongs to a ship, so it
     * is scoped exactly like the other ship-scoped tiers — owner-scoped AND
     * visibility-scoped. Like every per-tier relevance row it is <b>reserved-dormant</b>
     * under the encode-once law: per-peer variance may SELECT shared
     * chunks, never MINT peer bytes.
     */
    public static final String SHIP_CR_RELEVANCE_RULE_ID =
            "world_snapshot.ship_cr_owner_visibility_scoped";
    /**
     * Relevance rule that scopes the v15 content-dictionary tier. The dictionary is
     * FRAME-GLOBAL — a catalog announcement, not entity state — so it is neither
     * owner-scoped nor visibility-scoped: every connection needs the same mapping to
     * resolve interned contentIds. Like every per-tier relevance row it is
     * <b>reserved-dormant</b> under the encode-once law: per-peer
     * variance may SELECT shared chunks, never MINT peer bytes.
     */
    public static final String CONTENT_DICTIONARY_RELEVANCE_RULE_ID =
            "world_snapshot.content_dictionary_broadcast";
    /**
     * Relevance rule that scopes the v16 missile-pose widening of the non-ship
     * tier. The pose pair rides inside the kind=MISSILE non-ship record, so it is
     * scoped exactly like the non-ship tier — visibility-scoped only (a missile's
     * owner is a combat side, not a per-connection authority id). Like every
     * per-tier relevance row it is <b>reserved-dormant</b> under the
     * encode-once law: per-peer variance may SELECT shared chunks, never MINT
     * peer bytes.
     */
    public static final String MISSILE_POSE_RELEVANCE_RULE_ID =
            "world_snapshot.missile_pose_visibility_scoped";
    public static final String COMPATIBILITY_PREDICATE_ID = "world_snapshot_schema_v1_content_compatible";
    public static final String COMPATIBILITY_PREDICATE_ID_V2 = "world_snapshot_schema_v2_nonship_tier_compatible";
    public static final String COMPATIBILITY_PREDICATE_ID_V3 = "world_snapshot_schema_v3_flux_tier_compatible";
    public static final String COMPATIBILITY_PREDICATE_ID_V4 =
            "world_snapshot_schema_v4_weapon_mount_tier_compatible";
    public static final String COMPATIBILITY_PREDICATE_ID_V5 =
            "world_snapshot_schema_v5_allegiance_tier_compatible";
    public static final String COMPATIBILITY_PREDICATE_ID_V6 =
            "world_snapshot_schema_v6_engine_state_tier_compatible";
    public static final String COMPATIBILITY_PREDICATE_ID_V7 =
            "world_snapshot_schema_v7_maneuver_command_tier_compatible";
    public static final String COMPATIBILITY_PREDICATE_ID_V8 =
            "world_snapshot_schema_v8_system_state_tier_compatible";
    public static final String COMPATIBILITY_PREDICATE_ID_V9 =
            "world_snapshot_schema_v9_fragment_marker_tier_compatible";
    public static final String COMPATIBILITY_PREDICATE_ID_V10 =
            "world_snapshot_schema_v10_phase_cloak_tier_compatible";
    public static final String COMPATIBILITY_PREDICATE_ID_V11 =
            "world_snapshot_schema_v11_shield_facing_tier_compatible";
    public static final String COMPATIBILITY_PREDICATE_ID_V12 =
            "world_snapshot_schema_v12_beam_source_tier_compatible";
    public static final String COMPATIBILITY_PREDICATE_ID_V13 =
            "world_snapshot_schema_v13_missile_source_tier_compatible";
    public static final String COMPATIBILITY_PREDICATE_ID_V14 =
            "world_snapshot_schema_v14_ship_cr_tier_compatible";
    public static final String COMPATIBILITY_PREDICATE_ID_V15 =
            "world_snapshot_schema_v15_content_dictionary_tier_compatible";
    public static final String COMPATIBILITY_PREDICATE_ID_V16 =
            "world_snapshot_schema_v16_missile_pose_tier_compatible";
    public static final String FAILURE_CODE = "WORLD_SNAPSHOT_PROTOCOL_REJECTED";
    public static final ProtocolDataClass DATA_CLASS = ProtocolDataClass.REPLACEABLE_SNAPSHOT;

    private static final ProtocolCodecValidationOwner CODEC_VALIDATION_OWNER =
            new ProtocolCodecValidationOwner(CODEC_OWNER, VALIDATION_OWNER);
    private static final ProtocolRelevanceRule RELEVANCE_RULE =
            new ProtocolRelevanceRule(RELEVANCE_RULE_ID, true, true);
    private static final ProtocolRelevanceRule NONSHIP_RELEVANCE_RULE =
            new ProtocolRelevanceRule(NONSHIP_RELEVANCE_RULE_ID, false, true);
    private static final ProtocolRelevanceRule FLUX_RELEVANCE_RULE =
            new ProtocolRelevanceRule(FLUX_RELEVANCE_RULE_ID, true, true);
    private static final ProtocolRelevanceRule WEAPON_MOUNT_RELEVANCE_RULE =
            new ProtocolRelevanceRule(WEAPON_MOUNT_RELEVANCE_RULE_ID, true, true);
    private static final ProtocolRelevanceRule ALLEGIANCE_RELEVANCE_RULE =
            new ProtocolRelevanceRule(ALLEGIANCE_RELEVANCE_RULE_ID, true, true);
    private static final ProtocolRelevanceRule ENGINE_STATE_RELEVANCE_RULE =
            new ProtocolRelevanceRule(ENGINE_STATE_RELEVANCE_RULE_ID, true, true);
    private static final ProtocolRelevanceRule MANEUVER_COMMAND_RELEVANCE_RULE =
            new ProtocolRelevanceRule(MANEUVER_COMMAND_RELEVANCE_RULE_ID, true, true);
    private static final ProtocolRelevanceRule SYSTEM_STATE_RELEVANCE_RULE =
            new ProtocolRelevanceRule(SYSTEM_STATE_RELEVANCE_RULE_ID, true, true);
    private static final ProtocolRelevanceRule FRAGMENT_MARKER_RELEVANCE_RULE =
            new ProtocolRelevanceRule(FRAGMENT_MARKER_RELEVANCE_RULE_ID, true, true);
    private static final ProtocolRelevanceRule PHASE_CLOAK_RELEVANCE_RULE =
            new ProtocolRelevanceRule(PHASE_CLOAK_RELEVANCE_RULE_ID, true, true);
    private static final ProtocolRelevanceRule SHIELD_FACING_RELEVANCE_RULE =
            new ProtocolRelevanceRule(SHIELD_FACING_RELEVANCE_RULE_ID, true, true);
    private static final ProtocolRelevanceRule BEAM_SOURCE_RELEVANCE_RULE =
            new ProtocolRelevanceRule(BEAM_SOURCE_RELEVANCE_RULE_ID, false, true);
    private static final ProtocolRelevanceRule MISSILE_SOURCE_RELEVANCE_RULE =
            new ProtocolRelevanceRule(MISSILE_SOURCE_RELEVANCE_RULE_ID, false, true);
    private static final ProtocolRelevanceRule SHIP_CR_RELEVANCE_RULE =
            new ProtocolRelevanceRule(SHIP_CR_RELEVANCE_RULE_ID, true, true);
    private static final ProtocolRelevanceRule CONTENT_DICTIONARY_RELEVANCE_RULE =
            new ProtocolRelevanceRule(CONTENT_DICTIONARY_RELEVANCE_RULE_ID, false, false);
    private static final ProtocolRelevanceRule MISSILE_POSE_RELEVANCE_RULE =
            new ProtocolRelevanceRule(MISSILE_POSE_RELEVANCE_RULE_ID, false, true);
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE =
            new ProtocolCompatibilityRule(COMPATIBILITY_PREDICATE_ID, SCHEMA_VERSION, SCHEMA_VERSION, true);
    /**
     * v2 accepts peers across the whole v1..v2 range so a v2 server still admits
     * (and can serve down to) a v1-only client.
     */
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE_V2 =
            new ProtocolCompatibilityRule(
                    COMPATIBILITY_PREDICATE_ID_V2, SCHEMA_VERSION, SCHEMA_VERSION_V2, true);
    /**
     * v3 accepts peers across the whole v1..v3 range so a v3 server still admits (and
     * can serve down to) v1- and v2-only clients.
     */
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE_V3 =
            new ProtocolCompatibilityRule(
                    COMPATIBILITY_PREDICATE_ID_V3, SCHEMA_VERSION, SCHEMA_VERSION_V3, true);
    /**
     * v4 accepts peers across the whole v1..v4 range so a v4 server still admits (and
     * can serve down to) v1-, v2-, and v3-only clients.
     */
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE_V4 =
            new ProtocolCompatibilityRule(
                    COMPATIBILITY_PREDICATE_ID_V4, SCHEMA_VERSION, SCHEMA_VERSION_V4, true);
    /**
     * v5 accepts peers across the whole v1..v5 range so a v5 server still admits (and can
     * serve down to) v1-, v2-, v3-, and v4-only clients.
     */
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE_V5 =
            new ProtocolCompatibilityRule(
                    COMPATIBILITY_PREDICATE_ID_V5, SCHEMA_VERSION, SCHEMA_VERSION_V5, true);
    /**
     * v6 accepts peers across the whole v1..v6 range so a v6 server still admits (and can
     * serve down to) every older client.
     */
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE_V6 =
            new ProtocolCompatibilityRule(
                    COMPATIBILITY_PREDICATE_ID_V6, SCHEMA_VERSION, SCHEMA_VERSION_V6, true);
    /**
     * v7 accepts peers across the whole v1..v7 range so a v7 server still admits (and can
     * serve down to) every older client.
     */
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE_V7 =
            new ProtocolCompatibilityRule(
                    COMPATIBILITY_PREDICATE_ID_V7, SCHEMA_VERSION, SCHEMA_VERSION_V7, true);
    /**
     * v8 accepts peers across the whole v1..v8 range so a v8 server still admits (and can
     * serve down to) every older client.
     */
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE_V8 =
            new ProtocolCompatibilityRule(
                    COMPATIBILITY_PREDICATE_ID_V8, SCHEMA_VERSION, SCHEMA_VERSION_V8, true);
    /**
     * v9 accepts peers across the whole v1..v9 range so a v9 server still admits (and can
     * serve down to) every older client — with the spec's caveat that fragments themselves
     * must never be served to a sub-v9 peer (a marker-blind reader would misread a fragment
     * as whole-entity truth).
     */
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE_V9 =
            new ProtocolCompatibilityRule(
                    COMPATIBILITY_PREDICATE_ID_V9, SCHEMA_VERSION, SCHEMA_VERSION_V9, true);
    /**
     * v10 accepts peers across the whole v1..v10 range so a v10 server still admits (and
     * can serve down to) every older client — with the D-M2-1 caveat that an old client
     * fed only sub-v10 frames renders a phased foe hittable-looking (the silent-lie
     * degrade; kit discipline retires pre-v10 clients).
     */
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE_V10 =
            new ProtocolCompatibilityRule(
                    COMPATIBILITY_PREDICATE_ID_V10, SCHEMA_VERSION, SCHEMA_VERSION_V10, true);
    /**
     * v11 accepts peers across the whole v1..v11 range so a v11 server still admits (and
     * can serve down to) every older client — serve-down to a sub-v11 peer zeroes the
     * in-record facing byte ({@code withShieldFacingZeroed()}) so the frame naturally
     * stamps ≤v10; kit discipline retires pre-v11 clients.
     */
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE_V11 =
            new ProtocolCompatibilityRule(
                    COMPATIBILITY_PREDICATE_ID_V11, SCHEMA_VERSION, SCHEMA_VERSION_V11, true);
    /**
     * v12 accepts peers across the whole v1..v12 range so a v12 server still admits (and
     * can serve down to) every older client — serve-down to a sub-v12 peer clears the
     * per-beam source pair back to the sentinels ({@code withBeamSourcesCleared()})
     * so the frame naturally stamps ≤v11; kit discipline retires pre-v12 clients.
     */
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE_V12 =
            new ProtocolCompatibilityRule(
                    COMPATIBILITY_PREDICATE_ID_V12, SCHEMA_VERSION, SCHEMA_VERSION_V12, true);
    /**
     * v13 accepts peers across the whole v1..v13 range so a v13 server still admits (and
     * can serve down to) every older client — serve-down to a v12 peer clears the
     * per-missile source pair back to the sentinels ({@code withMissileSourcesCleared()})
     * so the frame naturally stamps ≤v12; kit discipline retires pre-v13 clients.
     */
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE_V13 =
            new ProtocolCompatibilityRule(
                    COMPATIBILITY_PREDICATE_ID_V13, SCHEMA_VERSION, SCHEMA_VERSION_V13, true);
    /**
     * v14 accepts peers across the whole v1..v14 range so a v14 server still admits (and
     * can serve down to) every older client — serve-down to a sub-v14 peer drops the CR
     * tier ({@code withoutCrTier()}) so the frame naturally stamps ≤v13; kit discipline
     * retires pre-v14 clients.
     */
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE_V14 =
            new ProtocolCompatibilityRule(
                    COMPATIBILITY_PREDICATE_ID_V14, SCHEMA_VERSION, SCHEMA_VERSION_V14, true);
    /**
     * v15 accepts peers across the whole v1..v15 range so a v15 server still admits (and
     * can serve down to) every older client — serve-down to a sub-v15 peer drops the
     * content-dictionary tier ({@code withoutContentDictionaryTier()}) so the frame
     * naturally stamps ≤v14; kit discipline retires pre-v15 clients.
     */
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE_V15 =
            new ProtocolCompatibilityRule(
                    COMPATIBILITY_PREDICATE_ID_V15, SCHEMA_VERSION, SCHEMA_VERSION_V15, true);
    /**
     * v16 accepts peers across the whole v1..v16 range so a v16 server still admits (and
     * can serve down to) every older client — serve-down to a sub-v16 peer clears the
     * per-missile pose pair back to the sentinels ({@code withMissilePosesCleared()})
     * so the frame naturally stamps ≤v15; kit discipline retires pre-v16 clients.
     */
    private static final ProtocolCompatibilityRule COMPATIBILITY_RULE_V16 =
            new ProtocolCompatibilityRule(
                    COMPATIBILITY_PREDICATE_ID_V16, SCHEMA_VERSION, SCHEMA_VERSION_V16, true);
    private static final ProtocolFailurePolicy FAILURE_POLICY =
            new ProtocolFailurePolicy(FAILURE_CODE, true);

    public WorldSnapshotMessage {
        Objects.requireNonNull(registryEntry, "registryEntry");
        Objects.requireNonNull(snapshot, "snapshot");
        if (!MESSAGE_ID.equals(registryEntry.messageId())) {
            throw new IllegalArgumentException("unexpected messageId: " + registryEntry.messageId());
        }
        if (registryEntry.schemaVersion() != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unexpected schemaVersion: " + registryEntry.schemaVersion());
        }
        if (registryEntry.direction() != MessageDirection.SERVER_UPDATE) {
            throw new IllegalArgumentException("world snapshots must be server updates");
        }
        if (registryEntry.reliabilityLane() != ReliabilityLane.REPLACEABLE_SNAPSHOT) {
            throw new IllegalArgumentException("world snapshots must use replaceable snapshot reliability");
        }
        if (registryEntry.dataClass() != DATA_CLASS) {
            throw new IllegalArgumentException("world snapshots must be replaceable snapshot data");
        }
        if (!CODEC_VALIDATION_OWNER.equals(registryEntry.codecValidationOwner())) {
            throw new IllegalArgumentException("unexpected codec/validation owner");
        }
        if (!RELEVANCE_RULE.equals(registryEntry.relevanceRule())) {
            throw new IllegalArgumentException("unexpected relevance rule");
        }
        if (!COMPATIBILITY_RULE.equals(registryEntry.compatibilityRule())) {
            throw new IllegalArgumentException("unexpected compatibility rule");
        }
        if (!FAILURE_POLICY.equals(registryEntry.failurePolicy())) {
            throw new IllegalArgumentException("unexpected failure policy");
        }
    }

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

    /**
     * Schema v2 registry entry: server-authoritative
     * ({@link MessageDirection#SERVER_UPDATE}) replaceable-snapshot world snapshot
     * that declares the non-ship critical-state tier. Same wire identity as v1
     * (shared {@link #MESSAGE_ID}); it differs in schema version, its non-ship
     * relevance rule, and a v1..v2 compatibility range so a v2 server keeps serving
     * v1-only clients.
     */
    public static ProtocolRegistryEntry registryEntryV2(SnapshotPublicationCadence snapshotCadence) {
        return new ProtocolRegistryEntry(
                MESSAGE_ID,
                SCHEMA_VERSION_V2,
                MessageDirection.SERVER_UPDATE,
                ReliabilityLane.REPLACEABLE_SNAPSHOT,
                DATA_CLASS,
                CODEC_VALIDATION_OWNER,
                NONSHIP_RELEVANCE_RULE,
                COMPATIBILITY_RULE_V2,
                FAILURE_POLICY,
                snapshotCadence);
    }

    /**
     * Schema v3 registry entry: server-authoritative
     * ({@link MessageDirection#SERVER_UPDATE}) replaceable-snapshot world snapshot
     * that also declares the per-ship flux + shield tier. Same wire
     * identity as v1/v2 (shared {@link #MESSAGE_ID}); it differs in schema version,
     * its owner+visibility-scoped flux relevance rule, and a v1..v3 compatibility
     * range so a v3 server keeps serving v1- and v2-only clients.
     */
    public static ProtocolRegistryEntry registryEntryV3(SnapshotPublicationCadence snapshotCadence) {
        return new ProtocolRegistryEntry(
                MESSAGE_ID,
                SCHEMA_VERSION_V3,
                MessageDirection.SERVER_UPDATE,
                ReliabilityLane.REPLACEABLE_SNAPSHOT,
                DATA_CLASS,
                CODEC_VALIDATION_OWNER,
                FLUX_RELEVANCE_RULE,
                COMPATIBILITY_RULE_V3,
                FAILURE_POLICY,
                snapshotCadence);
    }

    /**
     * Schema v4 registry entry: server-authoritative
     * ({@link MessageDirection#SERVER_UPDATE}) replaceable-snapshot world snapshot that
     * also declares the per-mount weapon-readiness tier. Same wire identity
     * as v1/v2/v3 (shared {@link #MESSAGE_ID}); it differs in schema version, its
     * owner+visibility-scoped weapon-mount relevance rule, and a v1..v4 compatibility
     * range so a v4 server keeps serving v1-, v2-, and v3-only clients.
     */
    public static ProtocolRegistryEntry registryEntryV4(SnapshotPublicationCadence snapshotCadence) {
        return new ProtocolRegistryEntry(
                MESSAGE_ID,
                SCHEMA_VERSION_V4,
                MessageDirection.SERVER_UPDATE,
                ReliabilityLane.REPLACEABLE_SNAPSHOT,
                DATA_CLASS,
                CODEC_VALIDATION_OWNER,
                WEAPON_MOUNT_RELEVANCE_RULE,
                COMPATIBILITY_RULE_V4,
                FAILURE_POLICY,
                snapshotCadence);
    }

    /**
     * Schema v5 registry entry: server-authoritative
     * ({@link MessageDirection#SERVER_UPDATE}) replaceable-snapshot world snapshot that also
     * declares the per-ship combat-side allegiance tier. Same wire identity as
     * v1/v2/v3/v4 (shared {@link #MESSAGE_ID}); it differs in schema version, its
     * owner+visibility-scoped allegiance relevance rule, and a v1..v5 compatibility range so a
     * v5 server keeps serving v1-, v2-, v3-, and v4-only clients.
     */
    public static ProtocolRegistryEntry registryEntryV5(SnapshotPublicationCadence snapshotCadence) {
        return new ProtocolRegistryEntry(
                MESSAGE_ID,
                SCHEMA_VERSION_V5,
                MessageDirection.SERVER_UPDATE,
                ReliabilityLane.REPLACEABLE_SNAPSHOT,
                DATA_CLASS,
                CODEC_VALIDATION_OWNER,
                ALLEGIANCE_RELEVANCE_RULE,
                COMPATIBILITY_RULE_V5,
                FAILURE_POLICY,
                snapshotCadence);
    }

    /**
     * Schema v6 registry entry: server-authoritative
     * ({@link MessageDirection#SERVER_UPDATE}) replaceable-snapshot world snapshot that also
     * declares the per-ship engine-disabled/flameout tier. Same wire identity as v1..v5
     * (shared {@link #MESSAGE_ID}); it differs in schema version, its
     * owner+visibility-scoped engine-state relevance rule, and a v1..v6 compatibility range
     * so a v6 server keeps serving every older client.
     */
    public static ProtocolRegistryEntry registryEntryV6(SnapshotPublicationCadence snapshotCadence) {
        return new ProtocolRegistryEntry(
                MESSAGE_ID,
                SCHEMA_VERSION_V6,
                MessageDirection.SERVER_UPDATE,
                ReliabilityLane.REPLACEABLE_SNAPSHOT,
                DATA_CLASS,
                CODEC_VALIDATION_OWNER,
                ENGINE_STATE_RELEVANCE_RULE,
                COMPATIBILITY_RULE_V6,
                FAILURE_POLICY,
                snapshotCadence);
    }

    /**
     * Schema v7 registry entry: server-authoritative
     * ({@link MessageDirection#SERVER_UPDATE}) replaceable-snapshot world snapshot that also
     * declares the per-ship maneuver-command tier. Same wire identity as v1..v6 (shared
     * {@link #MESSAGE_ID}); it differs in schema version, its owner+visibility-scoped
     * maneuver-command relevance rule, and a v1..v7 compatibility range so a v7 server
     * keeps serving every older client.
     */
    public static ProtocolRegistryEntry registryEntryV7(SnapshotPublicationCadence snapshotCadence) {
        return new ProtocolRegistryEntry(
                MESSAGE_ID,
                SCHEMA_VERSION_V7,
                MessageDirection.SERVER_UPDATE,
                ReliabilityLane.REPLACEABLE_SNAPSHOT,
                DATA_CLASS,
                CODEC_VALIDATION_OWNER,
                MANEUVER_COMMAND_RELEVANCE_RULE,
                COMPATIBILITY_RULE_V7,
                FAILURE_POLICY,
                snapshotCadence);
    }

    /**
     * Schema v8 registry entry: server-authoritative
     * ({@link MessageDirection#SERVER_UPDATE}) replaceable-snapshot world snapshot that also
     * declares the per-ship primary ship-system readiness tier. Same wire identity as v1..v7
     * (shared {@link #MESSAGE_ID}); it differs in schema version, its owner+visibility-scoped
     * (reserved-dormant per charter R6) system-state relevance rule, and a v1..v8
     * compatibility range so a v8 server keeps serving every older client.
     */
    public static ProtocolRegistryEntry registryEntryV8(SnapshotPublicationCadence snapshotCadence) {
        return new ProtocolRegistryEntry(
                MESSAGE_ID,
                SCHEMA_VERSION_V8,
                MessageDirection.SERVER_UPDATE,
                ReliabilityLane.REPLACEABLE_SNAPSHOT,
                DATA_CLASS,
                CODEC_VALIDATION_OWNER,
                SYSTEM_STATE_RELEVANCE_RULE,
                COMPATIBILITY_RULE_V8,
                FAILURE_POLICY,
                snapshotCadence);
    }

    /**
     * Schema v9 registry entry: server-authoritative
     * ({@link MessageDirection#SERVER_UPDATE}) replaceable-snapshot world snapshot that also
     * declares the FRAGMENT_MARKER tail tier. Same wire identity as v1..v8 (shared
     * {@link #MESSAGE_ID}); it differs in schema version, its owner+visibility-scoped
     * (reserved-dormant per charter R6) fragment-marker relevance rule, and a v1..v9
     * compatibility range so a v9 server keeps serving every older client.
     */
    public static ProtocolRegistryEntry registryEntryV9(SnapshotPublicationCadence snapshotCadence) {
        return new ProtocolRegistryEntry(
                MESSAGE_ID,
                SCHEMA_VERSION_V9,
                MessageDirection.SERVER_UPDATE,
                ReliabilityLane.REPLACEABLE_SNAPSHOT,
                DATA_CLASS,
                CODEC_VALIDATION_OWNER,
                FRAGMENT_MARKER_RELEVANCE_RULE,
                COMPATIBILITY_RULE_V9,
                FAILURE_POLICY,
                snapshotCadence);
    }

    /**
     * Schema v10 registry entry: server-authoritative
     * ({@link MessageDirection#SERVER_UPDATE}) replaceable-snapshot world snapshot that also
     * declares the per-ship phase-cloak readiness tier. Same wire identity as v1..v9 (shared
     * {@link #MESSAGE_ID}); it differs in schema version, its owner+visibility-scoped
     * (reserved-dormant per charter R6) phase-cloak relevance rule, and a v1..v10
     * compatibility range so a v10 server keeps serving every older client.
     */
    public static ProtocolRegistryEntry registryEntryV10(SnapshotPublicationCadence snapshotCadence) {
        return new ProtocolRegistryEntry(
                MESSAGE_ID,
                SCHEMA_VERSION_V10,
                MessageDirection.SERVER_UPDATE,
                ReliabilityLane.REPLACEABLE_SNAPSHOT,
                DATA_CLASS,
                CODEC_VALIDATION_OWNER,
                PHASE_CLOAK_RELEVANCE_RULE,
                COMPATIBILITY_RULE_V10,
                FAILURE_POLICY,
                snapshotCadence);
    }

    /**
     * Schema v11 registry entry: server-authoritative
     * ({@link MessageDirection#SERVER_UPDATE}) replaceable-snapshot world snapshot whose
     * flux-tier records also carry the quantized shield-facing u8. Same wire identity as
     * v1..v10 (shared {@link #MESSAGE_ID}); it differs in schema version, its
     * owner+visibility-scoped (reserved-dormant per charter R6) shield-facing relevance
     * rule, and a v1..v11 compatibility range so a v11 server keeps serving every older
     * client.
     */
    public static ProtocolRegistryEntry registryEntryV11(SnapshotPublicationCadence snapshotCadence) {
        return new ProtocolRegistryEntry(
                MESSAGE_ID,
                SCHEMA_VERSION_V11,
                MessageDirection.SERVER_UPDATE,
                ReliabilityLane.REPLACEABLE_SNAPSHOT,
                DATA_CLASS,
                CODEC_VALIDATION_OWNER,
                SHIELD_FACING_RELEVANCE_RULE,
                COMPATIBILITY_RULE_V11,
                FAILURE_POLICY,
                snapshotCadence);
    }

    /**
     * Schema v12 registry entry: server-authoritative
     * ({@link MessageDirection#SERVER_UPDATE}) replaceable-snapshot world snapshot whose
     * kind=BEAM non-ship records also carry the source binding pair. Same wire identity
     * as v1..v11 (shared {@link #MESSAGE_ID}); it differs in schema version, its
     * visibility-scoped (reserved-dormant per charter R6) beam-source relevance rule,
     * and a v1..v12 compatibility range so a v12 server keeps serving every older client.
     */
    public static ProtocolRegistryEntry registryEntryV12(SnapshotPublicationCadence snapshotCadence) {
        return new ProtocolRegistryEntry(
                MESSAGE_ID,
                SCHEMA_VERSION_V12,
                MessageDirection.SERVER_UPDATE,
                ReliabilityLane.REPLACEABLE_SNAPSHOT,
                DATA_CLASS,
                CODEC_VALIDATION_OWNER,
                BEAM_SOURCE_RELEVANCE_RULE,
                COMPATIBILITY_RULE_V12,
                FAILURE_POLICY,
                snapshotCadence);
    }

    /**
     * Schema v13 registry entry: server-authoritative
     * ({@link MessageDirection#SERVER_UPDATE}) replaceable-snapshot world snapshot whose
     * kind=MISSILE non-ship records also carry the source binding pair. Same wire
     * identity as v1..v12 (shared {@link #MESSAGE_ID}); it differs in schema version, its
     * visibility-scoped (reserved-dormant per charter R6) missile-source relevance rule,
     * and a v1..v13 compatibility range so a v13 server keeps serving every older client.
     */
    public static ProtocolRegistryEntry registryEntryV13(SnapshotPublicationCadence snapshotCadence) {
        return new ProtocolRegistryEntry(
                MESSAGE_ID,
                SCHEMA_VERSION_V13,
                MessageDirection.SERVER_UPDATE,
                ReliabilityLane.REPLACEABLE_SNAPSHOT,
                DATA_CLASS,
                CODEC_VALIDATION_OWNER,
                MISSILE_SOURCE_RELEVANCE_RULE,
                COMPATIBILITY_RULE_V13,
                FAILURE_POLICY,
                snapshotCadence);
    }

    /**
     * Schema v14 registry entry: server-authoritative
     * ({@link MessageDirection#SERVER_UPDATE}) replaceable-snapshot world snapshot that also
     * declares the per-ship COMBAT-READINESS tier. Same wire identity as v1..v13 (shared
     * {@link #MESSAGE_ID}); it differs in schema version, its owner+visibility-scoped
     * (reserved-dormant per charter R6) ship-CR relevance rule, and a v1..v14
     * compatibility range so a v14 server keeps serving every older client.
     */
    public static ProtocolRegistryEntry registryEntryV14(SnapshotPublicationCadence snapshotCadence) {
        return new ProtocolRegistryEntry(
                MESSAGE_ID,
                SCHEMA_VERSION_V14,
                MessageDirection.SERVER_UPDATE,
                ReliabilityLane.REPLACEABLE_SNAPSHOT,
                DATA_CLASS,
                CODEC_VALIDATION_OWNER,
                SHIP_CR_RELEVANCE_RULE,
                COMPATIBILITY_RULE_V14,
                FAILURE_POLICY,
                snapshotCadence);
    }

    /**
     * Schema v15 registry entry: server-authoritative
     * ({@link MessageDirection#SERVER_UPDATE}) replaceable-snapshot world snapshot that also
     * declares the frame-global CONTENT-DICTIONARY tier. Same wire identity as v1..v14
     * (shared {@link #MESSAGE_ID}); it differs in schema version, its broadcast-scoped
     * (reserved-dormant per charter R6) content-dictionary relevance rule, and a v1..v15
     * compatibility range so a v15 server keeps serving every older client.
     */
    public static ProtocolRegistryEntry registryEntryV15(SnapshotPublicationCadence snapshotCadence) {
        return new ProtocolRegistryEntry(
                MESSAGE_ID,
                SCHEMA_VERSION_V15,
                MessageDirection.SERVER_UPDATE,
                ReliabilityLane.REPLACEABLE_SNAPSHOT,
                DATA_CLASS,
                CODEC_VALIDATION_OWNER,
                CONTENT_DICTIONARY_RELEVANCE_RULE,
                COMPATIBILITY_RULE_V15,
                FAILURE_POLICY,
                snapshotCadence);
    }

    /**
     * Schema v16 registry entry: server-authoritative
     * ({@link MessageDirection#SERVER_UPDATE}) replaceable-snapshot world snapshot whose
     * kind=MISSILE non-ship records also carry the missile-pose pair. Same wire identity
     * as v1..v15 (shared {@link #MESSAGE_ID}); it differs in schema version, its
     * visibility-scoped (reserved-dormant per charter R6) missile-pose relevance rule,
     * and a v1..v16 compatibility range so a v16 server keeps serving every older client.
     */
    public static ProtocolRegistryEntry registryEntryV16(SnapshotPublicationCadence snapshotCadence) {
        return new ProtocolRegistryEntry(
                MESSAGE_ID,
                SCHEMA_VERSION_V16,
                MessageDirection.SERVER_UPDATE,
                ReliabilityLane.REPLACEABLE_SNAPSHOT,
                DATA_CLASS,
                CODEC_VALIDATION_OWNER,
                MISSILE_POSE_RELEVANCE_RULE,
                COMPATIBILITY_RULE_V16,
                FAILURE_POLICY,
                snapshotCadence);
    }

    public static ProtocolCodecValidationOwner codecValidationOwner() {
        return CODEC_VALIDATION_OWNER;
    }

    public static ProtocolRelevanceRule nonShipRelevanceRule() {
        return NONSHIP_RELEVANCE_RULE;
    }

    public static ProtocolRelevanceRule fluxRelevanceRule() {
        return FLUX_RELEVANCE_RULE;
    }

    public static ProtocolRelevanceRule weaponMountRelevanceRule() {
        return WEAPON_MOUNT_RELEVANCE_RULE;
    }

    public static ProtocolRelevanceRule allegianceRelevanceRule() {
        return ALLEGIANCE_RELEVANCE_RULE;
    }

    public static ProtocolRelevanceRule engineStateRelevanceRule() {
        return ENGINE_STATE_RELEVANCE_RULE;
    }

    public static ProtocolRelevanceRule maneuverCommandRelevanceRule() {
        return MANEUVER_COMMAND_RELEVANCE_RULE;
    }

    public static ProtocolRelevanceRule systemStateRelevanceRule() {
        return SYSTEM_STATE_RELEVANCE_RULE;
    }

    public static ProtocolRelevanceRule fragmentMarkerRelevanceRule() {
        return FRAGMENT_MARKER_RELEVANCE_RULE;
    }

    public static ProtocolRelevanceRule phaseCloakRelevanceRule() {
        return PHASE_CLOAK_RELEVANCE_RULE;
    }

    public static ProtocolRelevanceRule shieldFacingRelevanceRule() {
        return SHIELD_FACING_RELEVANCE_RULE;
    }

    public static ProtocolRelevanceRule beamSourceRelevanceRule() {
        return BEAM_SOURCE_RELEVANCE_RULE;
    }

    public static ProtocolRelevanceRule missileSourceRelevanceRule() {
        return MISSILE_SOURCE_RELEVANCE_RULE;
    }

    public static ProtocolRelevanceRule shipCrRelevanceRule() {
        return SHIP_CR_RELEVANCE_RULE;
    }

    public static ProtocolRelevanceRule contentDictionaryRelevanceRule() {
        return CONTENT_DICTIONARY_RELEVANCE_RULE;
    }

    public static ProtocolRelevanceRule missilePoseRelevanceRule() {
        return MISSILE_POSE_RELEVANCE_RULE;
    }

    public static ProtocolCompatibilityRule compatibilityRuleV2() {
        return COMPATIBILITY_RULE_V2;
    }

    public static ProtocolCompatibilityRule compatibilityRuleV3() {
        return COMPATIBILITY_RULE_V3;
    }

    public static ProtocolCompatibilityRule compatibilityRuleV4() {
        return COMPATIBILITY_RULE_V4;
    }

    public static ProtocolCompatibilityRule compatibilityRuleV5() {
        return COMPATIBILITY_RULE_V5;
    }

    public static ProtocolCompatibilityRule compatibilityRuleV6() {
        return COMPATIBILITY_RULE_V6;
    }

    public static ProtocolCompatibilityRule compatibilityRuleV7() {
        return COMPATIBILITY_RULE_V7;
    }

    public static ProtocolCompatibilityRule compatibilityRuleV8() {
        return COMPATIBILITY_RULE_V8;
    }

    public static ProtocolCompatibilityRule compatibilityRuleV9() {
        return COMPATIBILITY_RULE_V9;
    }

    public static ProtocolCompatibilityRule compatibilityRuleV10() {
        return COMPATIBILITY_RULE_V10;
    }

    public static ProtocolCompatibilityRule compatibilityRuleV11() {
        return COMPATIBILITY_RULE_V11;
    }

    public static ProtocolCompatibilityRule compatibilityRuleV12() {
        return COMPATIBILITY_RULE_V12;
    }

    public static ProtocolCompatibilityRule compatibilityRuleV13() {
        return COMPATIBILITY_RULE_V13;
    }

    public static ProtocolCompatibilityRule compatibilityRuleV14() {
        return COMPATIBILITY_RULE_V14;
    }

    public static ProtocolCompatibilityRule compatibilityRuleV16() {
        return COMPATIBILITY_RULE_V16;
    }

    public static ProtocolCompatibilityRule compatibilityRuleV15() {
        return COMPATIBILITY_RULE_V15;
    }

    public static ProtocolRelevanceRule relevanceRule() {
        return RELEVANCE_RULE;
    }

    public static ProtocolCompatibilityRule compatibilityRule() {
        return COMPATIBILITY_RULE;
    }

    public static ProtocolFailurePolicy failurePolicy() {
        return FAILURE_POLICY;
    }

    public static WorldSnapshotMessage wrap(
            AuthoritativeWorldSnapshot snapshot,
            SnapshotPublicationCadence snapshotCadence) {
        return new WorldSnapshotMessage(registryEntry(snapshotCadence), snapshot);
    }
}
