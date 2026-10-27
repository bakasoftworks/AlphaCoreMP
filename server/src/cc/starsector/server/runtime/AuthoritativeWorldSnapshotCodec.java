package cc.starsector.server.runtime;

import cc.starsector.server.adapter.CombatEntityView;
import cc.starsector.server.protocol.AuthoritativeEntitySnapshot;
import cc.starsector.server.protocol.AuthoritativeWorldSnapshot;
import cc.starsector.server.protocol.ContentDictionaryEntry;
import cc.starsector.server.protocol.NetworkMessageRegistry;
import cc.starsector.server.protocol.NonShipEntitySnapshot;
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
import cc.starsector.server.protocol.WorldSnapshotMessage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.zip.CRC32;

/**
 * Product-owned binary frame codec for {@link AuthoritativeWorldSnapshot}: the
 * per-entity authoritative replication payload, written as the same
 * bytes that travel a socket.
 *
 * <p>Unlike {@code OfflineCombatStepWireCodec} (fixed-width aggregate counts plus
 * one opaque state hash), this frame is variable-length — it carries one decoded
 * per-entity record per ship — so a receiver reconstructs each entity's
 * authoritative motion and durability, never re-simulating combat.
 * Framing is registry-checked and fail-closed: magic/version/wire-identity
 * mismatches and CRC32 corruption throw {@link IOException}, the same discipline
 * the offline-step codec uses.
 */
public final class AuthoritativeWorldSnapshotCodec {

    public static final String MESSAGE_ID = WorldSnapshotMessage.MESSAGE_ID;
    public static final int MAGIC = 0x41575353; // "AWSS"
    private static final ProtocolRegistryEntry REGISTRY_ENTRY =
            NetworkMessageRegistry.authoritativeCombat().worldSnapshotWireEntry();
    private static final ProtocolRegistryEntry REGISTRY_ENTRY_V2 =
            NetworkMessageRegistry.authoritativeCombat().worldSnapshotWireEntryV2();
    private static final ProtocolRegistryEntry REGISTRY_ENTRY_V3 =
            NetworkMessageRegistry.authoritativeCombat().worldSnapshotWireEntryV3();
    private static final ProtocolRegistryEntry REGISTRY_ENTRY_V4 =
            NetworkMessageRegistry.authoritativeCombat().worldSnapshotWireEntryV4();
    private static final ProtocolRegistryEntry REGISTRY_ENTRY_V5 =
            NetworkMessageRegistry.authoritativeCombat().worldSnapshotWireEntryV5();
    private static final ProtocolRegistryEntry REGISTRY_ENTRY_V6 =
            NetworkMessageRegistry.authoritativeCombat().worldSnapshotWireEntryV6();
    private static final ProtocolRegistryEntry REGISTRY_ENTRY_V7 =
            NetworkMessageRegistry.authoritativeCombat().worldSnapshotWireEntryV7();
    private static final ProtocolRegistryEntry REGISTRY_ENTRY_V8 =
            NetworkMessageRegistry.authoritativeCombat().worldSnapshotWireEntryV8();
    private static final ProtocolRegistryEntry REGISTRY_ENTRY_V9 =
            NetworkMessageRegistry.authoritativeCombat().worldSnapshotWireEntryV9();
    private static final ProtocolRegistryEntry REGISTRY_ENTRY_V10 =
            NetworkMessageRegistry.authoritativeCombat().worldSnapshotWireEntryV10();
    private static final ProtocolRegistryEntry REGISTRY_ENTRY_V11 =
            NetworkMessageRegistry.authoritativeCombat().worldSnapshotWireEntryV11();
    private static final ProtocolRegistryEntry REGISTRY_ENTRY_V12 =
            NetworkMessageRegistry.authoritativeCombat().worldSnapshotWireEntryV12();
    private static final ProtocolRegistryEntry REGISTRY_ENTRY_V13 =
            NetworkMessageRegistry.authoritativeCombat().worldSnapshotWireEntryV13();
    private static final ProtocolRegistryEntry REGISTRY_ENTRY_V14 =
            NetworkMessageRegistry.authoritativeCombat().worldSnapshotWireEntryV14();
    private static final ProtocolRegistryEntry REGISTRY_ENTRY_V15 =
            NetworkMessageRegistry.authoritativeCombat().worldSnapshotWireEntryV15();
    private static final ProtocolRegistryEntry REGISTRY_ENTRY_V16 =
            NetworkMessageRegistry.authoritativeCombat().worldSnapshotWireEntryV16();
    public static final int MESSAGE_TYPE_CODE = REGISTRY_ENTRY.wireTypeCode();
    /** Schema stamped on a ship-only frame (back-compat default; also exported for the scout). */
    public static final short SCHEMA_VERSION = checkedSchemaVersion(REGISTRY_ENTRY);
    /** Schema stamped when a frame carries the non-ship critical-state tier. */
    public static final short SCHEMA_VERSION_V2 = checkedSchemaVersion(REGISTRY_ENTRY_V2);
    /** Schema stamped when a frame also carries the per-ship flux/shield tier. */
    public static final short SCHEMA_VERSION_V3 = checkedSchemaVersion(REGISTRY_ENTRY_V3);
    /** Schema stamped when a frame also carries the per-mount weapon-readiness tier. */
    public static final short SCHEMA_VERSION_V4 = checkedSchemaVersion(REGISTRY_ENTRY_V4);
    /** Schema stamped when a frame also carries the per-ship combat-side allegiance tier. */
    public static final short SCHEMA_VERSION_V5 = checkedSchemaVersion(REGISTRY_ENTRY_V5);
    /** Schema stamped when a frame also carries the per-ship engine-state tier. */
    public static final short SCHEMA_VERSION_V6 = checkedSchemaVersion(REGISTRY_ENTRY_V6);
    /** Schema stamped when a frame also carries the per-ship maneuver-command tier. */
    public static final short SCHEMA_VERSION_V7 = checkedSchemaVersion(REGISTRY_ENTRY_V7);
    /** Schema stamped when a frame also carries the per-ship system-state tier. */
    public static final short SCHEMA_VERSION_V8 = checkedSchemaVersion(REGISTRY_ENTRY_V8);
    /** Schema stamped when a frame carries the FRAGMENT_MARKER tail tier. */
    public static final short SCHEMA_VERSION_V9 = checkedSchemaVersion(REGISTRY_ENTRY_V9);
    /** Schema stamped when a frame also carries the per-ship phase-cloak tier. */
    public static final short SCHEMA_VERSION_V10 = checkedSchemaVersion(REGISTRY_ENTRY_V10);
    /** Schema stamped when a frame's flux records carry a nonzero shield-facing u8. */
    public static final short SCHEMA_VERSION_V11 = checkedSchemaVersion(REGISTRY_ENTRY_V11);
    /** Schema stamped when a frame's beam records carry a resolved source binding. */
    public static final short SCHEMA_VERSION_V12 = checkedSchemaVersion(REGISTRY_ENTRY_V12);
    /** Schema stamped when a frame's missile records carry a resolved source binding. */
    public static final short SCHEMA_VERSION_V13 = checkedSchemaVersion(REGISTRY_ENTRY_V13);
    /** Schema stamped when a frame also carries the per-ship combat-readiness (CR) tier. */
    public static final short SCHEMA_VERSION_V14 = checkedSchemaVersion(REGISTRY_ENTRY_V14);
    /** Schema stamped when a frame also carries the frame-global content-dictionary tier. */
    public static final short SCHEMA_VERSION_V15 = checkedSchemaVersion(REGISTRY_ENTRY_V15);
    /** Schema stamped when a frame's missile records carry a minted pose. */
    public static final short SCHEMA_VERSION_V16 = checkedSchemaVersion(REGISTRY_ENTRY_V16);
    /**
     * The TOP schema tier {@code writeFrame} can negotiate: bump this alongside every
     * new {@code SCHEMA_VERSION_Vn} re-export above — adjacency is the anti-drift. Consumed by the
     * {@link MatchRecordMeta} {@code awss.schema} header stamp; each frame's own magic+version
     * stays the authority on what that frame actually carries (negotiation is per-frame, by top
     * non-empty tier).
     *
     * <p><b>A LITERAL on purpose:</b> {@code HandshakeCodec} bakes this into
     * every WELCOME, and that class is folded WHOLE into the client mod jar while this codec is
     * not. A value derived from {@code checkedSchemaVersion(...)} is not a constant expression, so
     * javac emitted a runtime {@code getstatic} on this class — in-game a
     * {@code NoClassDefFoundError} inside the reel door's socket thread (headless the server
     * classes hid it). A literal is inlined at compile time; the static block below keeps the
     * registry adjacency honest, and the client build's jdeps closure gate ({@code -filter:none})
     * proves no reference survives.
     */
    public static final short SCHEMA_VERSION_MAX = 16;

    static {
        if (SCHEMA_VERSION_MAX != SCHEMA_VERSION_V16) {
            throw new IllegalStateException("SCHEMA_VERSION_MAX " + SCHEMA_VERSION_MAX
                    + " != the top registry re-export SCHEMA_VERSION_V16 " + SCHEMA_VERSION_V16
                    + " — bump the literal beside the new Vn re-export");
        }
    }
    public static final short HEADER_BYTES =
            Integer.BYTES   // magic
                    + Short.BYTES   // schema version
                    + Short.BYTES   // header byte count
                    + Integer.BYTES // wire type code
                    + Integer.BYTES; // body byte count
    /** Body bytes preceding the per-entity records: simTick + entity count. */
    public static final int BODY_PREFIX_BYTES = Long.BYTES + Integer.BYTES;
    public static final int ENTITY_BYTES =
            Long.BYTES        // entityId
                    + Integer.BYTES   // contentId
                    + Long.BYTES      // spawnTick
                    + Integer.BYTES   // ownerConnectionId
                    + Integer.BYTES   // locX
                    + Integer.BYTES   // locY
                    + Integer.BYTES   // velX
                    + Integer.BYTES   // velY
                    + Integer.BYTES   // facing
                    + Integer.BYTES   // angularVelocity
                    + Integer.BYTES   // hitpoints
                    + Integer.BYTES   // hullLevel
                    + Integer.BYTES   // armorCellCount
                    + Integer.BYTES   // armorTotal
                    + Long.BYTES;     // armorGridHash
    /**
     * Bytes for the non-ship entity section's count field. In schema v2
     * this count plus its {@link #NON_SHIP_ENTITY_BYTES} records is the declared,
     * mandatory non-ship section. In schema v1 the section is the original
     * self-describing, length-driven affordance kept for back-compat: a body ending
     * exactly after the ship records decodes to zero non-ship entities (every
     * older capture), while a v1 body with remaining bytes carries the
     * transitional non-ship section.
     */
    public static final int NON_SHIP_COUNT_BYTES = Integer.BYTES;
    public static final int NON_SHIP_ENTITY_BYTES =
            Long.BYTES        // networkId
                    + Integer.BYTES   // kind wire code
                    + Integer.BYTES   // owner
                    + Integer.BYTES   // posX
                    + Integer.BYTES   // posY
                    + Integer.BYTES;  // contentId
    /**
     * Extra bytes appended to each kind=BEAM non-ship record in a v12 body:
     * sourceEntityId (long; 0 = unresolved) + sourceMountIndex (int;
     * −1 = unresolved), binding the beam to its EMITTER's wire entityId and the mount
     * ordinal on that emitter — the firing ship + its
     * {@code ServerWeaponMountState}-ordinal mount, or a vanilla DEM
     * payload beam's owning child MISSILE by its v13a stable wire id at or above
     * {@link CombatEntityView#MISSILE_NETWORK_ID_BASE} + the ordinal on that child's
     * fx drone. The codec is pure pass-through over both id spaces (no range or
     * roster assumption on either side). Width is keyed by record KIND
     * within a v12 body — projectile/missile records stay
     * {@link #NON_SHIP_ENTITY_BYTES} wide, and every v1..v11 body keeps the fixed
     * width, which is what keeps the frozen golden vectors byte-identical.
     */
    public static final int BEAM_SOURCE_EXTRA_BYTES = Long.BYTES + Integer.BYTES;
    /**
     * Extra bytes appended to each kind=MISSILE non-ship record in a v13 body:
     * sourceEntityId (long; 0 = unresolved) + sourceMountIndex (int;
     * −1 = unresolved), binding the missile to its launching ship's wire entityId
     * and its {@code ServerWeaponMountState}-ordinal mount. Width is keyed by
     * record KIND within a v13 body — projectile records stay
     * {@link #NON_SHIP_ENTITY_BYTES} wide, beam records carry the
     * {@link #BEAM_SOURCE_EXTRA_BYTES} pair (a v13 body forces the v12 beam width
     * — higher-tier-forces-lower applied to widths), and every v1..v12 body keeps
     * its own widths, which is what keeps the frozen golden vectors byte-identical.
     */
    public static final int MISSILE_SOURCE_EXTRA_BYTES = Long.BYTES + Integer.BYTES;
    /**
     * Extra bytes appended to each kind=MISSILE non-ship record in a v16 body,
     * AFTER its {@link #MISSILE_SOURCE_EXTRA_BYTES} pair:
     * facingU16 (unsigned short; 360/65536° steps, minted ONCE server-side — the
     * v11 never-re-derive law) + poseFlags (u8: bit0 POSE_VALID, bit1
     * FIZZLING; spare bits write-0 / decode-MASKED, never rejected). Sentinel =
     * (0,0); flags==0 forces facing==0. Width is keyed by record KIND within a
     * v16 body — projectile records stay {@link #NON_SHIP_ENTITY_BYTES} wide,
     * beam records carry the {@link #BEAM_SOURCE_EXTRA_BYTES} pair — and every
     * v1..v15 body keeps its own widths, which is what keeps the frozen golden
     * vectors byte-identical.
     */
    public static final int MISSILE_POSE_EXTRA_BYTES = Short.BYTES + Byte.BYTES;
    /**
     * Bytes for the flux section's count field (schema v3). In v3 a frame
     * always writes the non-ship section (even when empty) followed by this declared
     * flux section, so the decoder can locate flux after a possibly-empty non-ship
     * section.
     */
    public static final int FLUX_COUNT_BYTES = Integer.BYTES;
    public static final int FLUX_ENTITY_BYTES =
            Long.BYTES        // entityId
                    + Integer.BYTES   // currFlux (float bits)
                    + Integer.BYTES   // maxFlux (float bits)
                    + Integer.BYTES   // fluxLevel (float bits)
                    + Integer.BYTES   // hardFlux (float bits)
                    + Integer.BYTES;  // statusFlags
    /**
     * v11 flux record width: the v3 record plus the quantized shield-facing
     * u8 written between hardFlux and statusFlags. Only the v11 body path sizes with
     * this — every v3..v10 flux region stays {@link #FLUX_ENTITY_BYTES} wide, which is
     * what keeps the frozen v1..v10 golden vectors byte-identical.
     */
    public static final int FLUX_ENTITY_BYTES_V11 = FLUX_ENTITY_BYTES + Byte.BYTES;
    /**
     * Bytes for the weapon-mount section's count field (schema v4). In v4 a
     * frame always writes the non-ship section and the flux section (each possibly
     * empty) followed by this declared weapon-mount section, so the decoder can locate
     * the weapon-mount records after a possibly-empty non-ship and flux section.
     */
    public static final int WEAPON_MOUNT_COUNT_BYTES = Integer.BYTES;
    public static final int WEAPON_MOUNT_ENTITY_BYTES =
            Long.BYTES        // entityId
                    + Integer.BYTES   // mountIndex
                    + Integer.BYTES   // weaponContentId
                    + Integer.BYTES   // cooldownRemaining (float bits)
                    + Integer.BYTES   // cooldown (float bits)
                    + Integer.BYTES   // ammo
                    + Integer.BYTES   // maxAmmo
                    + Integer.BYTES   // chargeLevel (float bits)
                    + Integer.BYTES;  // statusFlags
    /**
     * Bytes for the combat-side allegiance section's count field (schema v5). In v5
     * a frame always writes the non-ship, flux, and weapon-mount sections (each possibly empty)
     * followed by this declared allegiance section, so the decoder can locate the allegiance
     * records after the possibly-empty lower sections.
     */
    public static final int ALLEGIANCE_COUNT_BYTES = Integer.BYTES;
    public static final int ALLEGIANCE_ENTITY_BYTES =
            Long.BYTES        // entityId
                    + Integer.BYTES;  // fleetSide
    /**
     * Bytes for the engine-state section's count field (schema v6). In v6 a
     * frame always writes the non-ship, flux, weapon-mount, and allegiance sections (each
     * possibly empty) followed by this declared engine-state section, so the decoder can
     * locate the engine-state records after the possibly-empty lower sections.
     */
    public static final int ENGINE_STATE_COUNT_BYTES = Integer.BYTES;
    public static final int ENGINE_STATE_ENTITY_BYTES =
            Long.BYTES        // entityId
                    + Integer.BYTES   // disabledMask
                    + Integer.BYTES   // permDisabledMask
                    + Integer.BYTES;  // controllerState
    /**
     * Bytes for the maneuver-command section's count field (schema v7). In v7 a
     * frame always writes the non-ship, flux, weapon-mount, allegiance, and engine-state
     * sections (each possibly empty) followed by this declared maneuver-command section, so
     * the decoder can locate the maneuver-command records after the possibly-empty lower
     * sections.
     */
    public static final int MANEUVER_COMMAND_COUNT_BYTES = Integer.BYTES;
    public static final int MANEUVER_COMMAND_ENTITY_BYTES =
            Long.BYTES        // entityId
                    + Integer.BYTES;  // maneuverMask
    /**
     * Bytes for the system-state section's count field (schema v8). In v8 a
     * frame always writes the non-ship, flux, weapon-mount, allegiance, engine-state, and
     * maneuver-command sections (each possibly empty) followed by this declared
     * system-state section, so the decoder can locate the system-state records after the
     * possibly-empty lower sections.
     */
    public static final int SYSTEM_STATE_COUNT_BYTES = Integer.BYTES;
    public static final int SYSTEM_STATE_ENTITY_BYTES =
            Long.BYTES        // entityId
                    + Integer.BYTES   // stateCode
                    + Integer.BYTES   // effectLevel (float bits)
                    + Integer.BYTES   // cooldownRemaining (float bits)
                    + Integer.BYTES;  // ammo
    /**
     * Bytes for the fragment-marker section's count field (schema v9). In v9
     * a frame always writes every lower section (each possibly empty) followed by this
     * declared marker section, so the decoder can locate the marker records after the
     * possibly-empty lower sections.
     */
    public static final int FRAGMENT_MARKER_COUNT_BYTES = Integer.BYTES;
    public static final int FRAGMENT_MARKER_ENTITY_BYTES =
            Long.BYTES        // entityId
                    + Integer.BYTES   // tierMask
                    + Integer.BYTES   // mountFrom
                    + Integer.BYTES;  // mountTo
    /**
     * Bytes for the phase-cloak section's count field (schema v10). In v10 a
     * frame always writes every lower section (each possibly empty — including the
     * fragment-marker section, per D-M2-2 the cloak section is appended STRICTLY AFTER
     * it) followed by this declared phase-cloak section, so the decoder can locate the
     * phase-cloak records after the possibly-empty lower sections. The record layout is
     * the v8 SYSTEM_STATE 24-byte layout verbatim (D-M2-1).
     */
    public static final int PHASE_CLOAK_COUNT_BYTES = Integer.BYTES;
    public static final int PHASE_CLOAK_ENTITY_BYTES =
            Long.BYTES        // entityId
                    + Integer.BYTES   // stateCode
                    + Integer.BYTES   // effectLevel (float bits)
                    + Integer.BYTES   // cooldownRemaining (float bits)
                    + Integer.BYTES;  // ammo
    /**
     * Bytes for the ship-CR section's count field (schema v14). In v14 a
     * frame always writes every lower section (each possibly empty) followed by this
     * declared ship-CR section — the new final tail section, appended STRICTLY AFTER
     * the phase-cloak section — so the decoder can locate the CR records after the
     * possibly-empty lower sections.
     */
    public static final int CR_COUNT_BYTES = Integer.BYTES;
    public static final int CR_ENTITY_BYTES =
            Long.BYTES        // entityId
                    + Integer.BYTES   // cr (float bits)
                    + Integer.BYTES   // timeDeployedSec (float bits)
                    + Integer.BYTES;  // peakSec (float bits)
    /**
     * Bytes for the content-dictionary section's count field (schema v15).
     * The dictionary is the new final tail section, appended STRICTLY AFTER the ship-CR
     * section; each record is variable-width (the fixed prefix below plus 1..255 UTF-8
     * key bytes declared by the record's own length byte).
     */
    public static final int CONTENT_DICTIONARY_COUNT_BYTES = Integer.BYTES;
    public static final int CONTENT_DICTIONARY_ENTRY_PREFIX_BYTES =
            Integer.BYTES     // contentId
                    + Byte.BYTES;     // key UTF-8 byte length (1..255)

    private AuthoritativeWorldSnapshotCodec() {
    }

    public static ProtocolRegistryEntry registryEntry() {
        return REGISTRY_ENTRY;
    }

    public static int frameByteCount(AuthoritativeWorldSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return HEADER_BYTES + bodyByteCount(snapshot) + Integer.BYTES;
    }

    /**
     * True when this snapshot is stamped schema v2 — i.e. it carries the non-ship
     * critical-state tier. A ship-only snapshot stays schema v1 and its body omits
     * the non-ship section entirely (byte-identical in shape to every pre-0907
     * ship-only frame).
     */
    private static boolean carriesNonShipTier(AuthoritativeWorldSnapshot snapshot) {
        return snapshot.nonShipEntityCount() > 0;
    }

    /**
     * True when this snapshot is stamped schema v3 — i.e. it carries the per-ship
     * flux/shield tier. A v3 frame always writes the non-ship section
     * (even when empty) so the decoder can locate the flux section that follows it.
     */
    private static boolean carriesFluxTier(AuthoritativeWorldSnapshot snapshot) {
        return snapshot.fluxStateCount() > 0;
    }

    /**
     * True when this snapshot is stamped schema v4 — i.e. it carries the per-mount
     * weapon-readiness tier. A v4 frame always writes the non-ship section
     * and the flux section (each even when empty) so the decoder can locate the
     * weapon-mount section that follows them.
     */
    private static boolean carriesWeaponMountTier(AuthoritativeWorldSnapshot snapshot) {
        return snapshot.weaponMountCount() > 0;
    }

    /**
     * True when this snapshot is stamped schema v5 — i.e. it carries the per-ship combat-side
     * allegiance tier. A v5 frame always writes the non-ship, flux, and weapon-mount
     * sections (each even when empty) so the decoder can locate the allegiance section that
     * follows them.
     */
    private static boolean carriesAllegianceTier(AuthoritativeWorldSnapshot snapshot) {
        return snapshot.allegianceStateCount() > 0;
    }

    /**
     * True when this snapshot is stamped schema v6 — i.e. it carries the per-ship
     * engine-disabled/flameout tier. A v6 frame always writes the non-ship,
     * flux, weapon-mount, and allegiance sections (each even when empty) so the decoder
     * can locate the engine-state section that follows them.
     */
    private static boolean carriesEngineStateTier(AuthoritativeWorldSnapshot snapshot) {
        return snapshot.engineStateCount() > 0;
    }

    /**
     * True when this snapshot is stamped schema v7 — i.e. it carries the per-ship
     * maneuver-command tier. A v7 frame always writes the non-ship, flux,
     * weapon-mount, allegiance, and engine-state sections (each even when empty) so the
     * decoder can locate the maneuver-command section that follows them.
     */
    private static boolean carriesManeuverCommandTier(AuthoritativeWorldSnapshot snapshot) {
        return snapshot.maneuverCommandCount() > 0;
    }

    /**
     * True when this snapshot is stamped schema v8 — i.e. it carries the per-ship
     * primary ship-system tier. A v8 frame always writes the non-ship, flux,
     * weapon-mount, allegiance, engine-state, and maneuver-command sections (each even
     * when empty) so the decoder can locate the system-state section that follows them.
     */
    private static boolean carriesSystemStateTier(AuthoritativeWorldSnapshot snapshot) {
        return snapshot.systemStateCount() > 0;
    }

    /**
     * True when this snapshot is stamped schema v9 — i.e. it carries the FRAGMENT_MARKER
     * tail tier: the frame carries at least one PARTIAL entity and declares,
     * per marked entity, which ship-scoped tiers (and mount range) it asserts. A v9 frame
     * always writes every lower section (each even when empty) so the decoder can locate
     * the marker section that follows them.
     */
    private static boolean carriesFragmentMarkerTier(AuthoritativeWorldSnapshot snapshot) {
        return snapshot.fragmentMarkerCount() > 0;
    }

    /**
     * True when this snapshot is stamped schema v10 — i.e. it carries the per-ship
     * phase-cloak tier. A v10 frame always writes every lower section (each
     * even when empty — the fragment-marker section included, D-M2-2) so the decoder can
     * locate the phase-cloak section that follows them.
     */
    private static boolean carriesPhaseCloakTier(AuthoritativeWorldSnapshot snapshot) {
        return snapshot.phaseCloakStateCount() > 0;
    }

    /**
     * True when this snapshot is stamped schema v11 — i.e. at least one flux record
     * carries a nonzero quantized shield-facing u8. The predicate is
     * VALUE-based, not shield-flag-based: all facing-0 content (every pre-v11 fixture
     * and capture, shielded or not) keeps its v10-or-lower stamp and bytes, and a
     * schema-capped serve to a sub-v11 peer stays expressible via
     * {@code AuthoritativeWorldSnapshot#withShieldFacingZeroed()}. Documented edge: a
     * live frame where EVERY shielded ship's facing quantizes to 0 stamps ≤v10 — the
     * byte is absent and all true values are 0, so nothing is lost; the client pose
     * lane treats absent facing as UNKNOWN (keep-last-forced sticky).
     */
    private static boolean carriesShieldFacingTier(AuthoritativeWorldSnapshot snapshot) {
        for (ShipFluxSnapshot flux : snapshot.fluxStates()) {
            if (flux.shieldFacingU8() != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when this snapshot is stamped schema v12 — i.e. at least one kind=BEAM
     * non-ship record carries a resolved source binding. VALUE-based like
     * the v11 predicate: all source-less content (every pre-v12 fixture and capture,
     * beams included) keeps its v11-or-lower stamp and bytes, and a schema-capped
     * serve to a sub-v12 peer stays expressible via
     * {@code AuthoritativeWorldSnapshot#withBeamSourcesCleared()}. In a v12 body EVERY
     * beam record carries the {@link #BEAM_SOURCE_EXTRA_BYTES} pair (sentinels 0/−1
     * when unresolved) — record width is keyed by kind, never per-record by value —
     * and the flux records take the v11 widened width regardless of facing values
     * (higher-tier-forces-lower).
     */
    private static boolean carriesBeamSourceTier(AuthoritativeWorldSnapshot snapshot) {
        for (NonShipEntitySnapshot nonShip : snapshot.nonShipEntities()) {
            if (nonShip.kind() == CombatEntityView.Kind.BEAM
                    && nonShip.sourceEntityId() != NonShipEntitySnapshot.NO_SOURCE) {
                return true;
            }
        }
        return false;
    }

    /** Count of kind=BEAM records in the non-ship tier (the v12 variable-width extras). */
    private static int beamRecordCount(AuthoritativeWorldSnapshot snapshot) {
        int beams = 0;
        for (NonShipEntitySnapshot nonShip : snapshot.nonShipEntities()) {
            if (nonShip.kind() == CombatEntityView.Kind.BEAM) {
                beams++;
            }
        }
        return beams;
    }

    /**
     * True when this snapshot is stamped schema v13 — i.e. at least one kind=MISSILE
     * non-ship record carries a resolved source binding. VALUE-based like
     * the v11/v12 predicates, and the v13 stamp rides this predicate ALONE: beam
     * sources may be all-sentinel in a v13 frame (a resolved beam without a resolved
     * missile stamps v12). All missile-source-less content keeps its v12-or-lower
     * stamp and bytes, and a schema-capped serve to a v12 peer stays expressible via
     * {@code AuthoritativeWorldSnapshot#withMissileSourcesCleared()}. In a v13 body
     * EVERY missile record carries the {@link #MISSILE_SOURCE_EXTRA_BYTES} pair and
     * EVERY beam record the {@link #BEAM_SOURCE_EXTRA_BYTES} pair (sentinels 0/−1
     * when unresolved) — record width is keyed by kind, never per-record by value —
     * and the flux records take the v11 widened width plus the phase-cloak section
     * regardless of values (higher-tier-forces-lower).
     */
    private static boolean carriesMissileSourceTier(AuthoritativeWorldSnapshot snapshot) {
        for (NonShipEntitySnapshot nonShip : snapshot.nonShipEntities()) {
            if (nonShip.kind() == CombatEntityView.Kind.MISSILE
                    && nonShip.sourceEntityId() != NonShipEntitySnapshot.NO_SOURCE) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when this snapshot is stamped schema v16 — i.e. at least one kind=MISSILE
     * non-ship record carries a minted pose. VALUE-based like the
     * v11/v12/v13 predicates and rides poseFlags alone (every server-minted pose sets
     * POSE_FLAG_VALID; the sentinel is flags==0). In a v16 body EVERY missile record
     * carries the {@link #MISSILE_POSE_EXTRA_BYTES} pair after its source pair —
     * record width is keyed by kind, never per-record by value — and the
     * content-dictionary + ship-CR tail sections are forced present though each MAY
     * be empty (the ≥1-entry dictionary mirror stays exactly-v15's own). A
     * schema-capped serve to a sub-v16 peer stays expressible via
     * {@code AuthoritativeWorldSnapshot#withMissilePosesCleared()}.
     */
    private static boolean carriesMissilePoseTier(AuthoritativeWorldSnapshot snapshot) {
        for (NonShipEntitySnapshot nonShip : snapshot.nonShipEntities()) {
            if (nonShip.kind() == CombatEntityView.Kind.MISSILE && nonShip.poseFlags() != 0) {
                return true;
            }
        }
        return false;
    }

    /** Count of kind=MISSILE records in the non-ship tier (the v13 variable-width extras). */
    private static int missileRecordCount(AuthoritativeWorldSnapshot snapshot) {
        int missiles = 0;
        for (NonShipEntitySnapshot nonShip : snapshot.nonShipEntities()) {
            if (nonShip.kind() == CombatEntityView.Kind.MISSILE) {
                missiles++;
            }
        }
        return missiles;
    }

    /**
     * True when this snapshot is stamped schema v14 — i.e. it carries the per-ship
     * combat-readiness (CR) tier. A v14 frame always writes every lower
     * section (each even when empty) so the decoder can locate the ship-CR tail
     * section that follows them, and forces the v13 missile / v12 beam source-pair
     * record widths plus the v11 widened flux record regardless of values
     * (higher-tier-forces-lower, applied to sections AND widths — the decoder reads
     * the fully-widened v13 layout before the CR section).
     */
    private static boolean carriesCrTier(AuthoritativeWorldSnapshot snapshot) {
        return snapshot.crStateCount() > 0;
    }

    /**
     * Count-based stamp predicate for the frame-global content-dictionary tier
     * (schema v15): present iff the snapshot carries at least one
     * dictionary entry. A v15 body forces the CR section to be present (possibly
     * empty) so the decoder can locate the dictionary tail after it
     * (higher-tier-forces-lower; CR itself MAY be empty in a v15 frame — the
     * fail-closed exactly-v15 mirror rides the dictionary section alone).
     */
    private static boolean carriesContentDictionaryTier(AuthoritativeWorldSnapshot snapshot) {
        return snapshot.contentDictionaryCount() > 0;
    }

    private static int bodyByteCount(AuthoritativeWorldSnapshot snapshot) {
        // A higher tier forces every lower section to be present (even if empty) so the
        // decoder can locate it: v11 => all nine sections with the widened flux record;
        // v10 => all nine sections; v9 => non-ship through fragment-marker; v8 =>
        // non-ship through system-state; v7 => non-ship + flux + weapon-mount +
        // allegiance + engine-state + maneuver-command; v6 => non-ship + flux +
        // weapon-mount + allegiance + engine-state; v5 => non-ship + flux + weapon-mount
        // + allegiance; v4 => non-ship + flux + weapon-mount; v3 => non-ship + flux;
        // v2 => non-ship; v1 => none. v14 => all ten sections at the full v13 widths.
        // v15 => all eleven sections (the content-dictionary tail forces the CR
        // section to be present even when empty). v16 => the missile-pose widening on
        // every missile record; still eleven sections, dictionary AND CR may be empty.
        boolean posePresent = carriesMissilePoseTier(snapshot);
        boolean dictPresent = carriesContentDictionaryTier(snapshot) || posePresent;
        boolean crPresent = carriesCrTier(snapshot) || dictPresent;
        // v14 WIDTH LAW: a v14 body forces the v13 missile pair (and through
        // it the v12 beam pair + v11 flux width below) onto every record even when all
        // source values are sentinel — decodeBodyV14 reads the fully-widened v13 layout.
        boolean missileSourcePresent = carriesMissileSourceTier(snapshot) || crPresent;
        // v13 WIDTH LAW: a v13 body forces the v12 beam pair onto EVERY
        // beam record even when all beam sources are sentinel.
        boolean beamPairPresent = carriesBeamSourceTier(snapshot) || missileSourcePresent;
        boolean shieldFacingPresent = carriesShieldFacingTier(snapshot);
        // v12/v13 force the v11 widened flux record even when every facing is 0 (the
        // higher-tier-forces-lower law applied to a record WIDTH rather than a section).
        boolean fluxRecordWidened = shieldFacingPresent || beamPairPresent;
        boolean phaseCloakPresent =
                carriesPhaseCloakTier(snapshot) || shieldFacingPresent || beamPairPresent;
        boolean markerPresent = carriesFragmentMarkerTier(snapshot) || phaseCloakPresent;
        boolean systemStatePresent = carriesSystemStateTier(snapshot) || markerPresent;
        boolean maneuverPresent = carriesManeuverCommandTier(snapshot) || systemStatePresent;
        boolean engineStatePresent = carriesEngineStateTier(snapshot) || maneuverPresent;
        boolean allegiancePresent = carriesAllegianceTier(snapshot) || engineStatePresent;
        boolean weaponPresent = carriesWeaponMountTier(snapshot) || allegiancePresent;
        boolean fluxPresent = carriesFluxTier(snapshot) || weaponPresent;
        boolean nonShipPresent = carriesNonShipTier(snapshot) || fluxPresent;
        int count = BODY_PREFIX_BYTES + ENTITY_BYTES * snapshot.entityCount();
        if (nonShipPresent) {
            count += NON_SHIP_COUNT_BYTES + NON_SHIP_ENTITY_BYTES * snapshot.nonShipEntityCount();
            if (beamPairPresent) {
                count += BEAM_SOURCE_EXTRA_BYTES * beamRecordCount(snapshot);
            }
            if (missileSourcePresent) {
                count += MISSILE_SOURCE_EXTRA_BYTES * missileRecordCount(snapshot);
            }
            if (posePresent) {
                count += MISSILE_POSE_EXTRA_BYTES * missileRecordCount(snapshot);
            }
        }
        if (fluxPresent) {
            count += FLUX_COUNT_BYTES
                    + (fluxRecordWidened ? FLUX_ENTITY_BYTES_V11 : FLUX_ENTITY_BYTES)
                            * snapshot.fluxStateCount();
        }
        if (weaponPresent) {
            count += WEAPON_MOUNT_COUNT_BYTES + WEAPON_MOUNT_ENTITY_BYTES * snapshot.weaponMountCount();
        }
        if (allegiancePresent) {
            count += ALLEGIANCE_COUNT_BYTES + ALLEGIANCE_ENTITY_BYTES * snapshot.allegianceStateCount();
        }
        if (engineStatePresent) {
            count += ENGINE_STATE_COUNT_BYTES + ENGINE_STATE_ENTITY_BYTES * snapshot.engineStateCount();
        }
        if (maneuverPresent) {
            count += MANEUVER_COMMAND_COUNT_BYTES
                    + MANEUVER_COMMAND_ENTITY_BYTES * snapshot.maneuverCommandCount();
        }
        if (systemStatePresent) {
            count += SYSTEM_STATE_COUNT_BYTES
                    + SYSTEM_STATE_ENTITY_BYTES * snapshot.systemStateCount();
        }
        if (markerPresent) {
            count += FRAGMENT_MARKER_COUNT_BYTES
                    + FRAGMENT_MARKER_ENTITY_BYTES * snapshot.fragmentMarkerCount();
        }
        if (phaseCloakPresent) {
            count += PHASE_CLOAK_COUNT_BYTES
                    + PHASE_CLOAK_ENTITY_BYTES * snapshot.phaseCloakStateCount();
        }
        if (crPresent) {
            count += CR_COUNT_BYTES + CR_ENTITY_BYTES * snapshot.crStateCount();
        }
        if (dictPresent) {
            count += CONTENT_DICTIONARY_COUNT_BYTES;
            for (ContentDictionaryEntry entry : snapshot.contentDictionary()) {
                count += CONTENT_DICTIONARY_ENTRY_PREFIX_BYTES
                        + entry.key().getBytes(StandardCharsets.UTF_8).length;
            }
        }
        return count;
    }

    public static int writeFrame(OutputStream output, AuthoritativeWorldSnapshot snapshot)
            throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(snapshot, "snapshot");

        boolean carriesNonShipTier = carriesNonShipTier(snapshot);
        boolean carriesFluxTier = carriesFluxTier(snapshot);
        boolean carriesWeaponMountTier = carriesWeaponMountTier(snapshot);
        boolean carriesAllegianceTier = carriesAllegianceTier(snapshot);
        boolean carriesEngineStateTier = carriesEngineStateTier(snapshot);
        boolean carriesManeuverCommandTier = carriesManeuverCommandTier(snapshot);
        boolean carriesSystemStateTier = carriesSystemStateTier(snapshot);
        boolean carriesFragmentMarkerTier = carriesFragmentMarkerTier(snapshot);
        boolean carriesPhaseCloakTier = carriesPhaseCloakTier(snapshot);
        boolean carriesShieldFacingTier = carriesShieldFacingTier(snapshot);
        boolean carriesBeamSourceTier = carriesBeamSourceTier(snapshot);
        boolean carriesMissileSourceTier = carriesMissileSourceTier(snapshot);
        boolean carriesCrTier = carriesCrTier(snapshot);
        boolean carriesContentDictionaryTier = carriesContentDictionaryTier(snapshot);
        boolean carriesMissilePoseTier = carriesMissilePoseTier(snapshot);
        short schemaVersion = carriesMissilePoseTier
                ? SCHEMA_VERSION_V16
                : (carriesContentDictionaryTier
                ? SCHEMA_VERSION_V15
                : (carriesCrTier
                ? SCHEMA_VERSION_V14
                : (carriesMissileSourceTier
                ? SCHEMA_VERSION_V13
                : (carriesBeamSourceTier
                ? SCHEMA_VERSION_V12
                : (carriesShieldFacingTier
                ? SCHEMA_VERSION_V11
                : (carriesPhaseCloakTier
                        ? SCHEMA_VERSION_V10
                        : (carriesFragmentMarkerTier
                                ? SCHEMA_VERSION_V9
                                : (carriesSystemStateTier
                                        ? SCHEMA_VERSION_V8
                                        : (carriesManeuverCommandTier
                                                ? SCHEMA_VERSION_V7
                                                : (carriesEngineStateTier
                                                        ? SCHEMA_VERSION_V6
                                                        : (carriesAllegianceTier
                                                                ? SCHEMA_VERSION_V5
                                                                : (carriesWeaponMountTier
                                                                        ? SCHEMA_VERSION_V4
                                                                        : (carriesFluxTier
                                                                                ? SCHEMA_VERSION_V3
                                                                                : (carriesNonShipTier ? SCHEMA_VERSION_V2 : SCHEMA_VERSION))))))))))))));
        byte[] body = encodeBody(
                snapshot, carriesNonShipTier, carriesFluxTier, carriesWeaponMountTier,
                carriesAllegianceTier, carriesEngineStateTier, carriesManeuverCommandTier,
                carriesSystemStateTier, carriesFragmentMarkerTier, carriesPhaseCloakTier,
                carriesShieldFacingTier, carriesBeamSourceTier, carriesMissileSourceTier,
                carriesCrTier, carriesContentDictionaryTier, carriesMissilePoseTier);
        long checksum = checksum(body);
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(MAGIC);
        data.writeShort(schemaVersion);
        data.writeShort(HEADER_BYTES);
        data.writeInt(REGISTRY_ENTRY.wireTypeCode());
        data.writeInt(body.length);
        data.write(body);
        data.writeInt((int) checksum);
        data.flush();
        return HEADER_BYTES + body.length + Integer.BYTES;
    }

    public static List<AuthoritativeWorldSnapshot> readSnapshots(InputStream input) throws IOException {
        List<AuthoritativeWorldSnapshot> snapshots = new ArrayList<>();
        readSnapshots(input, snapshots::add);
        return List.copyOf(snapshots);
    }

    /**
     * Read exactly <em>one</em> frame's raw wire bytes off {@code input} (header + body +
     * checksum), or {@code null} at a clean end-of-stream before any frame byte. The
     * returned bytes are the same bytes {@link #writeFrame} produced, so a caller can both
     * forward them verbatim (preserving byte-identity with the sender) and decode them with
     * {@link #readSnapshots}. This is the single-frame counterpart of the multi-frame
     * {@link #readSnapshots} drain: a continuous client that interleaves <em>sending</em> a
     * per-frame intent with <em>receiving</em> the resulting frame (the live per-frame intent
     * channel) cannot use {@code readAllBytes}/{@code readSnapshots} —
     * those block to EOF — so it reads one frame at a time with this. Framing is validated
     * (magic, declared header size, body lower bound); the schema-registry and CRC checks run
     * when the bytes are later decoded by {@link #readSnapshots}.
     */
    public static byte[] readFrameBytes(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        DataInputStream data = new DataInputStream(input);
        int first = data.read();
        if (first < 0) {
            return null;
        }
        int magic = readIntAfterFirstByte(data, first);
        if (magic != MAGIC) {
            throw new IOException("world snapshot frame magic mismatch: " + Integer.toHexString(magic));
        }
        short version = data.readShort();
        short headerBytes = data.readShort();
        if (headerBytes != HEADER_BYTES) {
            throw new IOException("world snapshot frame header size mismatch: " + headerBytes);
        }
        int messageTypeCode = data.readInt();
        int bodyBytes = data.readInt();
        if (bodyBytes < BODY_PREFIX_BYTES) {
            throw new IOException("world snapshot frame body size invalid: " + bodyBytes);
        }
        byte[] body = new byte[bodyBytes];
        data.readFully(body);
        int checksum = data.readInt();
        // Re-serialize the exact frame so the caller holds the verbatim wire bytes (the same
        // bytes writeFrame emitted); decode-time schema/CRC validation happens in readSnapshots.
        ByteArrayOutputStream frame = new ByteArrayOutputStream(HEADER_BYTES + bodyBytes + Integer.BYTES);
        DataOutputStream out = new DataOutputStream(frame);
        out.writeInt(MAGIC);
        out.writeShort(version);
        out.writeShort(headerBytes);
        out.writeInt(messageTypeCode);
        out.writeInt(bodyBytes);
        out.write(body);
        out.writeInt(checksum);
        out.flush();
        return frame.toByteArray();
    }

    /**
     * Read one snapshot frame whose 4-byte {@link #MAGIC} has ALREADY been consumed off
     * {@code data} and validated {@code == MAGIC} by the caller. This is the snapshot-side
     * counterpart of {@link MatchResetFrameCodec#readFrameAfterMagic}: a
     * {@link MatchAwareSnapshotStream} demuxer reads the magic to route a frame, then calls
     * this to finish decoding an {@code "AWSS"} snapshot frame (the same schema/identity/
     * body/CRC validation {@link #readSnapshots} performs per frame, minus the magic read).
     * {@link #readSnapshots} is left byte-identical (it inlines the same steps), so every
     * existing pure-snapshot decode path is unchanged.
     */
    public static AuthoritativeWorldSnapshot readFrameAfterMagic(DataInputStream data) throws IOException {
        Objects.requireNonNull(data, "data");
        short version = data.readShort();
        requireRegisteredSchema(version);
        short headerBytes = data.readShort();
        if (headerBytes != HEADER_BYTES) {
            throw new IOException("world snapshot frame header size mismatch: " + headerBytes);
        }
        int messageTypeCode = data.readInt();
        requireRegisteredWireIdentity(messageTypeCode, version);
        int bodyBytes = data.readInt();
        if (bodyBytes < BODY_PREFIX_BYTES) {
            throw new IOException("world snapshot frame body size invalid: " + bodyBytes);
        }
        byte[] body = new byte[bodyBytes];
        data.readFully(body);
        long expectedChecksum = Integer.toUnsignedLong(data.readInt());
        long actualChecksum = checksum(body);
        if (expectedChecksum != actualChecksum) {
            throw new IOException("world snapshot frame checksum mismatch");
        }
        return decodeBody(body, version);
    }

    public static void readSnapshots(
            InputStream input,
            Consumer<? super AuthoritativeWorldSnapshot> decodedConsumer) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(decodedConsumer, "decodedConsumer");
        DataInputStream data = new DataInputStream(input);
        while (true) {
            int first = data.read();
            if (first < 0) {
                return;
            }
            int magic = readIntAfterFirstByte(data, first);
            if (magic != MAGIC) {
                throw new IOException("world snapshot frame magic mismatch: " + Integer.toHexString(magic));
            }
            short version = data.readShort();
            requireRegisteredSchema(version);
            short headerBytes = data.readShort();
            if (headerBytes != HEADER_BYTES) {
                throw new IOException("world snapshot frame header size mismatch: " + headerBytes);
            }
            int messageTypeCode = data.readInt();
            requireRegisteredWireIdentity(messageTypeCode, version);
            int bodyBytes = data.readInt();
            if (bodyBytes < BODY_PREFIX_BYTES) {
                throw new IOException("world snapshot frame body size invalid: " + bodyBytes);
            }
            byte[] body = new byte[bodyBytes];
            data.readFully(body);
            long expectedChecksum = Integer.toUnsignedLong(data.readInt());
            long actualChecksum = checksum(body);
            if (expectedChecksum != actualChecksum) {
                throw new IOException("world snapshot frame checksum mismatch");
            }
            decodedConsumer.accept(decodeBody(body, version));
        }
    }

    private static byte[] encodeBody(
            AuthoritativeWorldSnapshot snapshot,
            boolean carriesNonShipTier,
            boolean carriesFluxTier,
            boolean carriesWeaponMountTier,
            boolean carriesAllegianceTier,
            boolean carriesEngineStateTier,
            boolean carriesManeuverCommandTier,
            boolean carriesSystemStateTier,
            boolean carriesFragmentMarkerTier,
            boolean carriesPhaseCloakTier,
            boolean carriesShieldFacingTier,
            boolean carriesBeamSourceTier,
            boolean carriesMissileSourceTier,
            boolean carriesCrTier,
            boolean carriesContentDictionaryTier,
            boolean carriesMissilePoseTier)
            throws IOException {
        // A higher tier forces every lower section to be written (even if empty).
        boolean posePresent = carriesMissilePoseTier;
        boolean dictPresent = carriesContentDictionaryTier || posePresent;
        boolean crPresent = carriesCrTier || dictPresent;
        // v14 WIDTH LAW: a v14 body forces the v13 missile pair (and through
        // it the v12 beam pair + v11 flux width) onto every record.
        boolean missileSourcePresent = carriesMissileSourceTier || crPresent;
        // v13 WIDTH LAW: a v13 body forces the v12 pair onto EVERY beam record.
        boolean beamPairPresent = carriesBeamSourceTier || missileSourcePresent;
        boolean shieldFacingPresent = carriesShieldFacingTier;
        // v12/v13 force the v11 widened flux record even when every facing is 0.
        boolean fluxRecordWidened = shieldFacingPresent || beamPairPresent;
        boolean phaseCloakPresent = carriesPhaseCloakTier || shieldFacingPresent || beamPairPresent;
        boolean markerPresent = carriesFragmentMarkerTier || phaseCloakPresent;
        boolean systemStatePresent = carriesSystemStateTier || markerPresent;
        boolean maneuverPresent = carriesManeuverCommandTier || systemStatePresent;
        boolean engineStatePresent = carriesEngineStateTier || maneuverPresent;
        boolean allegiancePresent = carriesAllegianceTier || engineStatePresent;
        boolean weaponPresent = carriesWeaponMountTier || allegiancePresent;
        boolean fluxPresent = carriesFluxTier || weaponPresent;
        boolean nonShipPresent = carriesNonShipTier || fluxPresent;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(bodyByteCount(snapshot));
        DataOutputStream data = new DataOutputStream(bytes);
        data.writeLong(snapshot.simTick());
        data.writeInt(snapshot.entityCount());
        for (AuthoritativeEntitySnapshot entity : snapshot.entities()) {
            data.writeLong(entity.entityId());
            data.writeInt(entity.contentId());
            data.writeLong(entity.spawnTick());
            data.writeInt(entity.ownerConnectionId());
            data.writeInt(Float.floatToIntBits(entity.locX()));
            data.writeInt(Float.floatToIntBits(entity.locY()));
            data.writeInt(Float.floatToIntBits(entity.velX()));
            data.writeInt(Float.floatToIntBits(entity.velY()));
            data.writeInt(Float.floatToIntBits(entity.facing()));
            data.writeInt(Float.floatToIntBits(entity.angularVelocity()));
            data.writeInt(Float.floatToIntBits(entity.hitpoints()));
            data.writeInt(Float.floatToIntBits(entity.hullLevel()));
            data.writeInt(entity.armorCellCount());
            data.writeInt(Float.floatToIntBits(entity.armorTotal()));
            data.writeLong(entity.armorGridHash());
        }
        // Non-ship entity section (schema v2): written when the frame carries
        // the non-ship tier (v2) OR carries flux (v3 — the section is always present so
        // the decoder can locate the flux section that follows it, even when empty). A
        // ship-only (schema v1) frame omits it entirely, staying byte-identical in shape
        // to every pre-0907 ship-only capture; a v2/v3 frame declares it (count, then
        // records) as part of its announced contract.
        if (nonShipPresent) {
            data.writeInt(snapshot.nonShipEntityCount());
            for (NonShipEntitySnapshot nonShip : snapshot.nonShipEntities()) {
                data.writeLong(nonShip.networkId());
                data.writeInt(nonShip.kindWireCode());
                data.writeInt(nonShip.owner());
                data.writeInt(Float.floatToIntBits(nonShip.posX()));
                data.writeInt(Float.floatToIntBits(nonShip.posY()));
                data.writeInt(nonShip.contentId());
                // v12: EVERY beam record in a beam-source frame carries the
                // source pair — width keyed by kind, sentinels (0, -1) when unresolved.
                // A v13 body forces the beam pair too (WIDTH LAW).
                if (beamPairPresent && nonShip.kind() == CombatEntityView.Kind.BEAM) {
                    data.writeLong(nonShip.sourceEntityId());
                    data.writeInt(nonShip.sourceMountIndex());
                }
                // v13: EVERY missile record in a missile-source frame
                // carries the source pair — same kind-keyed width discipline.
                if (missileSourcePresent && nonShip.kind() == CombatEntityView.Kind.MISSILE) {
                    data.writeLong(nonShip.sourceEntityId());
                    data.writeInt(nonShip.sourceMountIndex());
                }
                // v16: EVERY missile record in a missile-pose frame
                // carries facingU16 + poseFlags AFTER the source pair — the values are
                // minted once at EngineCombatBinding and copied VERBATIM here, never
                // re-derived, sentinels (0, 0) when unminted.
                if (posePresent && nonShip.kind() == CombatEntityView.Kind.MISSILE) {
                    data.writeShort(nonShip.poseFacingU16());
                    data.writeByte(nonShip.poseFlags());
                }
            }
        }
        // Flux section (schema v3): written after the non-ship section. Count,
        // then one fixed-width record per ship flux state (entityId, four float-bit
        // scalars, the packed statusFlags int). In v4 it is written even when empty (so
        // the weapon-mount section that follows can be located). In v11 each
        // record additionally carries the quantized shield-facing u8 between hardFlux
        // and statusFlags — the byte is minted once at ShipFluxSnapshot.fromView and
        // copied VERBATIM here, never re-derived from floats.
        if (fluxPresent) {
            data.writeInt(snapshot.fluxStateCount());
            for (ShipFluxSnapshot flux : snapshot.fluxStates()) {
                data.writeLong(flux.entityId());
                data.writeInt(Float.floatToIntBits(flux.currFlux()));
                data.writeInt(Float.floatToIntBits(flux.maxFlux()));
                data.writeInt(Float.floatToIntBits(flux.fluxLevel()));
                data.writeInt(Float.floatToIntBits(flux.hardFlux()));
                if (fluxRecordWidened) {
                    data.writeByte(flux.shieldFacingU8());
                }
                data.writeInt(flux.statusFlags());
            }
        }
        // Weapon-mount section (schema v4): written after the flux section.
        // Count, then one fixed-width record per ship weapon mount (entityId, mountIndex,
        // interned weaponContentId, two float-bit cooldown scalars, ammo/maxAmmo,
        // float-bit chargeLevel, the packed statusFlags int).
        if (weaponPresent) {
            data.writeInt(snapshot.weaponMountCount());
            for (ShipWeaponMountSnapshot mount : snapshot.weaponMounts()) {
                data.writeLong(mount.entityId());
                data.writeInt(mount.mountIndex());
                data.writeInt(mount.weaponContentId());
                data.writeInt(Float.floatToIntBits(mount.cooldownRemaining()));
                data.writeInt(Float.floatToIntBits(mount.cooldown()));
                data.writeInt(mount.ammo());
                data.writeInt(mount.maxAmmo());
                data.writeInt(Float.floatToIntBits(mount.chargeLevel()));
                data.writeInt(mount.statusFlags());
            }
        }
        // Combat-side allegiance section (schema v5): written after the weapon-mount
        // section. Count, then one fixed-width record per ship allegiance (entityId, the
        // FleetSide ordinal) so a client can render friend vs foe.
        if (allegiancePresent) {
            data.writeInt(snapshot.allegianceStateCount());
            for (ShipAllegianceSnapshot allegiance : snapshot.allegianceStates()) {
                data.writeLong(allegiance.entityId());
                data.writeInt(allegiance.fleetSide());
            }
        }
        // Engine-state section (schema v6): written after the allegiance
        // section. Count, then one fixed-width record per ship engine state (entityId,
        // disabledMask, permDisabledMask, controllerState).
        if (engineStatePresent) {
            data.writeInt(snapshot.engineStateCount());
            for (ShipEngineStateSnapshot engineState : snapshot.engineStates()) {
                data.writeLong(engineState.entityId());
                data.writeInt(engineState.disabledMask());
                data.writeInt(engineState.permDisabledMask());
                data.writeInt(engineState.controllerState());
            }
        }
        // Maneuver-command section (schema v7): written after the engine-state
        // section. Count, then one fixed-width record per ship maneuver-command state
        // (entityId, the 7-bit maneuverMask).
        if (maneuverPresent) {
            data.writeInt(snapshot.maneuverCommandCount());
            for (ShipManeuverCommandSnapshot maneuverCommand : snapshot.maneuverCommands()) {
                data.writeLong(maneuverCommand.entityId());
                data.writeInt(maneuverCommand.maneuverMask());
            }
        }
        // System-state section (schema v8): written after the maneuver-command
        // section. Count, then one fixed-width record per ship system state (entityId,
        // project-owned stateCode, float-bit effectLevel and cooldownRemaining, ammo).
        if (systemStatePresent) {
            data.writeInt(snapshot.systemStateCount());
            for (ShipSystemStateSnapshot systemState : snapshot.systemStates()) {
                data.writeLong(systemState.entityId());
                data.writeInt(systemState.stateCode());
                data.writeInt(Float.floatToIntBits(systemState.effectLevel()));
                data.writeInt(Float.floatToIntBits(systemState.cooldownRemaining()));
                data.writeInt(systemState.ammo());
            }
        }
        // Fragment-marker section (schema v9): written after system-state.
        // Count, then one fixed-width record per PARTIALLY-carried ship (entityId,
        // tierMask, mountFrom, mountTo). In v10 it is written even when empty (D-M2-2:
        // the phase-cloak section that follows must be locatable, and a stale-v9 reader
        // walking pose tiers then the marker must never read the cloak count as a
        // marker count).
        if (markerPresent) {
            data.writeInt(snapshot.fragmentMarkerCount());
            for (ShipFragmentMarkerSnapshot marker : snapshot.fragmentMarkers()) {
                data.writeLong(marker.entityId());
                data.writeInt(marker.tierMask());
                data.writeInt(marker.mountFrom());
                data.writeInt(marker.mountTo());
            }
        }
        // Phase-cloak section (schema v10): the final tail section, appended
        // STRICTLY AFTER the fragment-marker section (D-M2-2). Count, then one
        // fixed-width record per cloak-bearing ship — the v8 SYSTEM_STATE 24-byte
        // layout verbatim (D-M2-1: entityId, project-owned stateCode, float-bit
        // effectLevel and cooldownRemaining, ammo).
        if (phaseCloakPresent) {
            data.writeInt(snapshot.phaseCloakStateCount());
            for (ShipPhaseCloakStateSnapshot phaseCloak : snapshot.phaseCloakStates()) {
                data.writeLong(phaseCloak.entityId());
                data.writeInt(phaseCloak.stateCode());
                data.writeInt(Float.floatToIntBits(phaseCloak.effectLevel()));
                data.writeInt(Float.floatToIntBits(phaseCloak.cooldownRemaining()));
                data.writeInt(phaseCloak.ammo());
            }
        }
        // Ship-CR section (schema v14): the new final tail section, appended
        // STRICTLY AFTER the phase-cloak section. Count, then one fixed-width record
        // per enrolled ship (entityId, float-bit cr, float-bit RAW deployed-seconds,
        // float-bit effective peak).
        if (crPresent) {
            data.writeInt(snapshot.crStateCount());
            for (ShipCrSnapshot crState : snapshot.crStates()) {
                data.writeLong(crState.entityId());
                data.writeInt(Float.floatToIntBits(crState.cr()));
                data.writeInt(Float.floatToIntBits(crState.timeDeployedSec()));
                data.writeInt(Float.floatToIntBits(crState.peakSec()));
            }
        }
        // Content-dictionary section (schema v15): the new final tail section,
        // appended STRICTLY AFTER the ship-CR section. Count, then one variable-width
        // record per announced entry (contentId, one-byte UTF-8 key length, key bytes —
        // the model guarantees 1..255 bytes so the length always fits its byte).
        if (dictPresent) {
            data.writeInt(snapshot.contentDictionaryCount());
            for (ContentDictionaryEntry entry : snapshot.contentDictionary()) {
                byte[] keyUtf8 = entry.key().getBytes(StandardCharsets.UTF_8);
                data.writeInt(entry.contentId());
                data.writeByte(keyUtf8.length);
                data.write(keyUtf8);
            }
        }
        data.flush();
        byte[] body = bytes.toByteArray();
        if (body.length != bodyByteCount(snapshot)) {
            throw new IOException("world snapshot body size bug: " + body.length);
        }
        return body;
    }

    private static AuthoritativeWorldSnapshot decodeBody(byte[] body, short schemaVersion) throws IOException {
        DataInputStream data = new DataInputStream(new ByteArrayInputStream(body));
        long simTick = data.readLong();
        int entityCount = data.readInt();
        int shipRegionEnd = BODY_PREFIX_BYTES + ENTITY_BYTES * entityCount;
        if (entityCount < 0 || shipRegionEnd > body.length) {
            throw new IOException("world snapshot entity count inconsistent with body length: " + entityCount);
        }
        List<AuthoritativeEntitySnapshot> entities = new ArrayList<>(entityCount);
        for (int i = 0; i < entityCount; i++) {
            entities.add(new AuthoritativeEntitySnapshot(
                    data.readLong(),
                    data.readInt(),
                    data.readLong(),
                    data.readInt(),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    // armorLevel is NOT an AWSS wire field (layout frozen at 72 B) —
                    // minted 0f like resolvePose's wire-dropped fields.
                    0f,
                    data.readInt(),
                    Float.intBitsToFloat(data.readInt()),
                    data.readLong()));
        }
        int unsignedSchema = Short.toUnsignedInt(schemaVersion);
        // Dispatch order hazard: the >=V10 branch MUST sit ABOVE >=V9 — the
        // cascade takes the FIRST match, so a v10 body reaching decodeBodyV9 would
        // misparse its phase-cloak tail as trailing bytes. Same for >=V11 above >=V10:
        // a v11 body's widened flux records would corrupt every offset after them —
        // and >=V12 above >=V11 (variable-width beam records misalign everything after),
        // and >=V13 above >=V12 (missile-record extras misalign the same way), and
        // >=V14 above >=V13 (the ship-CR tail would misparse as trailing bytes), and
        // >=V15 above >=V14 (the content-dictionary tail would misparse the same way),
        // and >=V16 above >=V15 (the missile-pose extras misalign every later offset).
        if (unsignedSchema >= SCHEMA_VERSION_V16) {
            return decodeBodyV16(data, body.length, shipRegionEnd, simTick, entities);
        }
        if (unsignedSchema >= SCHEMA_VERSION_V15) {
            return decodeBodyV15(data, body.length, shipRegionEnd, simTick, entities);
        }
        if (unsignedSchema >= SCHEMA_VERSION_V14) {
            return decodeBodyV14(data, body.length, shipRegionEnd, simTick, entities);
        }
        if (unsignedSchema >= SCHEMA_VERSION_V13) {
            return decodeBodyV13(data, body.length, shipRegionEnd, simTick, entities);
        }
        if (unsignedSchema >= SCHEMA_VERSION_V12) {
            return decodeBodyV12(data, body.length, shipRegionEnd, simTick, entities);
        }
        if (unsignedSchema >= SCHEMA_VERSION_V11) {
            return decodeBodyV11(data, body.length, shipRegionEnd, simTick, entities);
        }
        if (unsignedSchema >= SCHEMA_VERSION_V10) {
            return decodeBodyV10(data, body.length, shipRegionEnd, simTick, entities);
        }
        if (unsignedSchema >= SCHEMA_VERSION_V9) {
            return decodeBodyV9(data, body.length, shipRegionEnd, simTick, entities);
        }
        if (unsignedSchema >= SCHEMA_VERSION_V8) {
            return decodeBodyV8(data, body.length, shipRegionEnd, simTick, entities);
        }
        if (unsignedSchema >= SCHEMA_VERSION_V7) {
            return decodeBodyV7(data, body.length, shipRegionEnd, simTick, entities);
        }
        if (unsignedSchema >= SCHEMA_VERSION_V6) {
            return decodeBodyV6(data, body.length, shipRegionEnd, simTick, entities);
        }
        if (unsignedSchema >= SCHEMA_VERSION_V5) {
            return decodeBodyV5(data, body.length, shipRegionEnd, simTick, entities);
        }
        if (unsignedSchema >= SCHEMA_VERSION_V4) {
            return decodeBodyV4(data, body.length, shipRegionEnd, simTick, entities);
        }
        if (unsignedSchema >= SCHEMA_VERSION_V3) {
            return decodeBodyV3(data, body.length, shipRegionEnd, simTick, entities);
        }
        boolean schemaDeclaresNonShipTier = unsignedSchema >= SCHEMA_VERSION_V2;
        List<NonShipEntitySnapshot> nonShipEntities =
                decodeNonShipSection(data, body.length, shipRegionEnd, schemaDeclaresNonShipTier);
        if (data.read() != -1) {
            throw new IOException("world snapshot body has trailing bytes");
        }
        return new AuthoritativeWorldSnapshot(simTick, entities, nonShipEntities);
    }

    /**
     * Decode a schema-v3 body: ships (already read), then a <em>declared</em> non-ship
     * section (count + records, possibly empty) followed by a declared flux section.
     *
     * <p>Unlike the v1/v2 path, the non-ship section here is NOT the trailing section —
     * the flux section follows it — so it is read by its declared count advancing an
     * explicit offset rather than the "remaining bytes == expected" affordance. Every
     * length is validated against the body so a corrupt count fails closed; a v3 frame
     * must carry at least one flux record (a flux-empty frame would have been stamped
     * v2/v1 by {@link #writeFrame}).
     */
    private static AuthoritativeWorldSnapshot decodeBodyV3(
            DataInputStream data,
            int bodyLength,
            int shipRegionEnd,
            long simTick,
            List<AuthoritativeEntitySnapshot> entities) throws IOException {
        int remaining = bodyLength - shipRegionEnd;
        if (remaining < NON_SHIP_COUNT_BYTES + FLUX_COUNT_BYTES) {
            throw new IOException("world snapshot v3 frame truncated: "
                    + remaining + " trailing bytes after ship records");
        }
        int nonShipCount = data.readInt();
        if (nonShipCount < 0) {
            throw new IOException("world snapshot v3 non-ship count negative: " + nonShipCount);
        }
        int nonShipRegion = NON_SHIP_COUNT_BYTES + NON_SHIP_ENTITY_BYTES * nonShipCount;
        if (nonShipRegion + FLUX_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v3 non-ship count inconsistent with body length: " + nonShipCount);
        }
        List<NonShipEntitySnapshot> nonShipEntities = new ArrayList<>(nonShipCount);
        for (int i = 0; i < nonShipCount; i++) {
            nonShipEntities.add(new NonShipEntitySnapshot(
                    data.readLong(),
                    NonShipEntitySnapshot.kindFromWireCode(data.readInt()),
                    data.readInt(),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt()));
        }
        int fluxCount = data.readInt();
        if (fluxCount <= 0) {
            // Fail-closed: a v3 frame is stamped only when it actually carries flux.
            throw new IOException("world snapshot v3 frame declares no flux tier: " + fluxCount);
        }
        int fluxRegion = FLUX_COUNT_BYTES + FLUX_ENTITY_BYTES * fluxCount;
        if (nonShipRegion + fluxRegion != remaining) {
            throw new IOException(
                    "world snapshot v3 flux count inconsistent with body length: " + fluxCount);
        }
        List<ShipFluxSnapshot> fluxStates = new ArrayList<>(fluxCount);
        for (int i = 0; i < fluxCount; i++) {
            fluxStates.add(ShipFluxSnapshot.fromWire(
                    data.readLong(),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt()));
        }
        if (data.read() != -1) {
            throw new IOException("world snapshot body has trailing bytes");
        }
        return new AuthoritativeWorldSnapshot(simTick, entities, nonShipEntities, fluxStates);
    }

    /**
     * Decode a schema-v4 body: ships (already read), then a <em>declared</em> non-ship
     * section (count + records, possibly empty), a declared flux section (count +
     * records, possibly empty), and finally a declared weapon-mount section.
     *
     * <p>Like the v3 path the lower sections are read by their declared counts advancing
     * an explicit offset (they are no longer the trailing section — the weapon-mount
     * records follow). Unlike v3, the flux section here may be empty (a v4 frame is
     * stamped when it carries weapon mounts, independent of flux); the weapon-mount
     * section must carry at least one record (a weapon-empty frame would have been
     * stamped v3/v2/v1 by {@link #writeFrame}). Every length is validated against the
     * body so a corrupt count fails closed.
     */
    private static AuthoritativeWorldSnapshot decodeBodyV4(
            DataInputStream data,
            int bodyLength,
            int shipRegionEnd,
            long simTick,
            List<AuthoritativeEntitySnapshot> entities) throws IOException {
        int remaining = bodyLength - shipRegionEnd;
        if (remaining < NON_SHIP_COUNT_BYTES + FLUX_COUNT_BYTES + WEAPON_MOUNT_COUNT_BYTES) {
            throw new IOException("world snapshot v4 frame truncated: "
                    + remaining + " trailing bytes after ship records");
        }
        int nonShipCount = data.readInt();
        if (nonShipCount < 0) {
            throw new IOException("world snapshot v4 non-ship count negative: " + nonShipCount);
        }
        int nonShipRegion = NON_SHIP_COUNT_BYTES + NON_SHIP_ENTITY_BYTES * nonShipCount;
        if (nonShipRegion + FLUX_COUNT_BYTES + WEAPON_MOUNT_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v4 non-ship count inconsistent with body length: " + nonShipCount);
        }
        List<NonShipEntitySnapshot> nonShipEntities = new ArrayList<>(nonShipCount);
        for (int i = 0; i < nonShipCount; i++) {
            nonShipEntities.add(new NonShipEntitySnapshot(
                    data.readLong(),
                    NonShipEntitySnapshot.kindFromWireCode(data.readInt()),
                    data.readInt(),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt()));
        }
        int fluxCount = data.readInt();
        if (fluxCount < 0) {
            throw new IOException("world snapshot v4 flux count negative: " + fluxCount);
        }
        int fluxRegion = FLUX_COUNT_BYTES + FLUX_ENTITY_BYTES * fluxCount;
        if (nonShipRegion + fluxRegion + WEAPON_MOUNT_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v4 flux count inconsistent with body length: " + fluxCount);
        }
        List<ShipFluxSnapshot> fluxStates = new ArrayList<>(fluxCount);
        for (int i = 0; i < fluxCount; i++) {
            fluxStates.add(ShipFluxSnapshot.fromWire(
                    data.readLong(),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt()));
        }
        int weaponCount = data.readInt();
        if (weaponCount <= 0) {
            // Fail-closed: a v4 frame is stamped only when it actually carries mounts.
            throw new IOException("world snapshot v4 frame declares no weapon-mount tier: " + weaponCount);
        }
        int weaponRegion = WEAPON_MOUNT_COUNT_BYTES + WEAPON_MOUNT_ENTITY_BYTES * weaponCount;
        if (nonShipRegion + fluxRegion + weaponRegion != remaining) {
            throw new IOException(
                    "world snapshot v4 weapon-mount count inconsistent with body length: " + weaponCount);
        }
        List<ShipWeaponMountSnapshot> weaponMounts = new ArrayList<>(weaponCount);
        for (int i = 0; i < weaponCount; i++) {
            weaponMounts.add(ShipWeaponMountSnapshot.fromWire(
                    data.readLong(),
                    data.readInt(),
                    data.readInt(),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt(),
                    data.readInt(),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt()));
        }
        if (data.read() != -1) {
            throw new IOException("world snapshot body has trailing bytes");
        }
        return new AuthoritativeWorldSnapshot(simTick, entities, nonShipEntities, fluxStates, weaponMounts);
    }

    /**
     * Decode a schema-v5 body: ships (already read), then a <em>declared</em> non-ship section
     * (count + records, possibly empty), a declared flux section (count + records, possibly
     * empty), a declared weapon-mount section (count + records, possibly empty), and finally a
     * declared combat-side allegiance section.
     *
     * <p>Like the v4 path the lower sections are read by their declared counts advancing an
     * explicit offset. Unlike v4, the weapon-mount section here may be empty (a v5 frame is
     * stamped when it carries allegiance, independent of weapon mounts); the allegiance section
     * must carry at least one record (an allegiance-empty frame would have been stamped
     * v4/v3/v2/v1 by {@link #writeFrame}). Every length is validated against the body so a
     * corrupt count fails closed. Profile states are an unwired tier and never appear on the
     * wire, so the reconstructed snapshot carries none.
     */
    private static AuthoritativeWorldSnapshot decodeBodyV5(
            DataInputStream data,
            int bodyLength,
            int shipRegionEnd,
            long simTick,
            List<AuthoritativeEntitySnapshot> entities) throws IOException {
        int remaining = bodyLength - shipRegionEnd;
        if (remaining < NON_SHIP_COUNT_BYTES + FLUX_COUNT_BYTES + WEAPON_MOUNT_COUNT_BYTES
                + ALLEGIANCE_COUNT_BYTES) {
            throw new IOException("world snapshot v5 frame truncated: "
                    + remaining + " trailing bytes after ship records");
        }
        int nonShipCount = data.readInt();
        if (nonShipCount < 0) {
            throw new IOException("world snapshot v5 non-ship count negative: " + nonShipCount);
        }
        int nonShipRegion = NON_SHIP_COUNT_BYTES + NON_SHIP_ENTITY_BYTES * nonShipCount;
        if (nonShipRegion + FLUX_COUNT_BYTES + WEAPON_MOUNT_COUNT_BYTES + ALLEGIANCE_COUNT_BYTES
                > remaining) {
            throw new IOException(
                    "world snapshot v5 non-ship count inconsistent with body length: " + nonShipCount);
        }
        List<NonShipEntitySnapshot> nonShipEntities = new ArrayList<>(nonShipCount);
        for (int i = 0; i < nonShipCount; i++) {
            nonShipEntities.add(new NonShipEntitySnapshot(
                    data.readLong(),
                    NonShipEntitySnapshot.kindFromWireCode(data.readInt()),
                    data.readInt(),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt()));
        }
        int fluxCount = data.readInt();
        if (fluxCount < 0) {
            throw new IOException("world snapshot v5 flux count negative: " + fluxCount);
        }
        int fluxRegion = FLUX_COUNT_BYTES + FLUX_ENTITY_BYTES * fluxCount;
        if (nonShipRegion + fluxRegion + WEAPON_MOUNT_COUNT_BYTES + ALLEGIANCE_COUNT_BYTES
                > remaining) {
            throw new IOException(
                    "world snapshot v5 flux count inconsistent with body length: " + fluxCount);
        }
        List<ShipFluxSnapshot> fluxStates = new ArrayList<>(fluxCount);
        for (int i = 0; i < fluxCount; i++) {
            fluxStates.add(ShipFluxSnapshot.fromWire(
                    data.readLong(),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt()));
        }
        int weaponCount = data.readInt();
        if (weaponCount < 0) {
            throw new IOException("world snapshot v5 weapon-mount count negative: " + weaponCount);
        }
        int weaponRegion = WEAPON_MOUNT_COUNT_BYTES + WEAPON_MOUNT_ENTITY_BYTES * weaponCount;
        if (nonShipRegion + fluxRegion + weaponRegion + ALLEGIANCE_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v5 weapon-mount count inconsistent with body length: " + weaponCount);
        }
        List<ShipWeaponMountSnapshot> weaponMounts = new ArrayList<>(weaponCount);
        for (int i = 0; i < weaponCount; i++) {
            weaponMounts.add(ShipWeaponMountSnapshot.fromWire(
                    data.readLong(),
                    data.readInt(),
                    data.readInt(),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt(),
                    data.readInt(),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt()));
        }
        int allegianceCount = data.readInt();
        if (allegianceCount <= 0) {
            // Fail-closed: a v5 frame is stamped only when it actually carries allegiance.
            throw new IOException("world snapshot v5 frame declares no allegiance tier: " + allegianceCount);
        }
        int allegianceRegion = ALLEGIANCE_COUNT_BYTES + ALLEGIANCE_ENTITY_BYTES * allegianceCount;
        if (nonShipRegion + fluxRegion + weaponRegion + allegianceRegion != remaining) {
            throw new IOException(
                    "world snapshot v5 allegiance count inconsistent with body length: " + allegianceCount);
        }
        List<ShipAllegianceSnapshot> allegianceStates = new ArrayList<>(allegianceCount);
        for (int i = 0; i < allegianceCount; i++) {
            allegianceStates.add(new ShipAllegianceSnapshot(data.readLong(), data.readInt()));
        }
        if (data.read() != -1) {
            throw new IOException("world snapshot body has trailing bytes");
        }
        return new AuthoritativeWorldSnapshot(
                simTick, entities, nonShipEntities, fluxStates, weaponMounts, List.of(), allegianceStates);
    }

    /**
     * Decode a schema-v6 body: ships (already read), then <em>declared</em> non-ship,
     * flux, weapon-mount, and allegiance sections (each count + records, each possibly
     * empty), and finally a declared engine-state section.
     *
     * <p>Like the v5 path the lower sections are read by their declared counts advancing
     * an explicit offset. Unlike v5, the allegiance section here may be empty (a v6 frame
     * is stamped when it carries engine states, independent of allegiance); the
     * engine-state section must carry at least one record (an engine-state-empty frame
     * would have been stamped v5-or-lower by {@link #writeFrame}). Every length is
     * validated against the body so a corrupt count fails closed, and each engine-state
     * record passes the {@link ShipEngineStateSnapshot#fromWire} controller-state range
     * check.
     */
    private static AuthoritativeWorldSnapshot decodeBodyV6(
            DataInputStream data,
            int bodyLength,
            int shipRegionEnd,
            long simTick,
            List<AuthoritativeEntitySnapshot> entities) throws IOException {
        int remaining = bodyLength - shipRegionEnd;
        if (remaining < NON_SHIP_COUNT_BYTES + FLUX_COUNT_BYTES + WEAPON_MOUNT_COUNT_BYTES
                + ALLEGIANCE_COUNT_BYTES + ENGINE_STATE_COUNT_BYTES) {
            throw new IOException("world snapshot v6 frame truncated: "
                    + remaining + " trailing bytes after ship records");
        }
        int nonShipCount = data.readInt();
        if (nonShipCount < 0) {
            throw new IOException("world snapshot v6 non-ship count negative: " + nonShipCount);
        }
        int nonShipRegion = NON_SHIP_COUNT_BYTES + NON_SHIP_ENTITY_BYTES * nonShipCount;
        if (nonShipRegion + FLUX_COUNT_BYTES + WEAPON_MOUNT_COUNT_BYTES + ALLEGIANCE_COUNT_BYTES
                + ENGINE_STATE_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v6 non-ship count inconsistent with body length: " + nonShipCount);
        }
        List<NonShipEntitySnapshot> nonShipEntities = new ArrayList<>(nonShipCount);
        for (int i = 0; i < nonShipCount; i++) {
            nonShipEntities.add(new NonShipEntitySnapshot(
                    data.readLong(),
                    NonShipEntitySnapshot.kindFromWireCode(data.readInt()),
                    data.readInt(),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt()));
        }
        int fluxCount = data.readInt();
        if (fluxCount < 0) {
            throw new IOException("world snapshot v6 flux count negative: " + fluxCount);
        }
        int fluxRegion = FLUX_COUNT_BYTES + FLUX_ENTITY_BYTES * fluxCount;
        if (nonShipRegion + fluxRegion + WEAPON_MOUNT_COUNT_BYTES + ALLEGIANCE_COUNT_BYTES
                + ENGINE_STATE_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v6 flux count inconsistent with body length: " + fluxCount);
        }
        List<ShipFluxSnapshot> fluxStates = new ArrayList<>(fluxCount);
        for (int i = 0; i < fluxCount; i++) {
            fluxStates.add(ShipFluxSnapshot.fromWire(
                    data.readLong(),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt()));
        }
        int weaponCount = data.readInt();
        if (weaponCount < 0) {
            throw new IOException("world snapshot v6 weapon-mount count negative: " + weaponCount);
        }
        int weaponRegion = WEAPON_MOUNT_COUNT_BYTES + WEAPON_MOUNT_ENTITY_BYTES * weaponCount;
        if (nonShipRegion + fluxRegion + weaponRegion + ALLEGIANCE_COUNT_BYTES
                + ENGINE_STATE_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v6 weapon-mount count inconsistent with body length: " + weaponCount);
        }
        List<ShipWeaponMountSnapshot> weaponMounts = new ArrayList<>(weaponCount);
        for (int i = 0; i < weaponCount; i++) {
            weaponMounts.add(ShipWeaponMountSnapshot.fromWire(
                    data.readLong(),
                    data.readInt(),
                    data.readInt(),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt(),
                    data.readInt(),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt()));
        }
        int allegianceCount = data.readInt();
        if (allegianceCount < 0) {
            throw new IOException("world snapshot v6 allegiance count negative: " + allegianceCount);
        }
        int allegianceRegion = ALLEGIANCE_COUNT_BYTES + ALLEGIANCE_ENTITY_BYTES * allegianceCount;
        if (nonShipRegion + fluxRegion + weaponRegion + allegianceRegion
                + ENGINE_STATE_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v6 allegiance count inconsistent with body length: " + allegianceCount);
        }
        List<ShipAllegianceSnapshot> allegianceStates = new ArrayList<>(allegianceCount);
        for (int i = 0; i < allegianceCount; i++) {
            allegianceStates.add(new ShipAllegianceSnapshot(data.readLong(), data.readInt()));
        }
        int engineStateCount = data.readInt();
        if (engineStateCount <= 0) {
            // Fail-closed: a v6 frame is stamped only when it actually carries engine states.
            throw new IOException(
                    "world snapshot v6 frame declares no engine-state tier: " + engineStateCount);
        }
        int engineStateRegion = ENGINE_STATE_COUNT_BYTES + ENGINE_STATE_ENTITY_BYTES * engineStateCount;
        if (nonShipRegion + fluxRegion + weaponRegion + allegianceRegion + engineStateRegion
                != remaining) {
            throw new IOException(
                    "world snapshot v6 engine-state count inconsistent with body length: "
                            + engineStateCount);
        }
        List<ShipEngineStateSnapshot> engineStates = new ArrayList<>(engineStateCount);
        for (int i = 0; i < engineStateCount; i++) {
            try {
                engineStates.add(ShipEngineStateSnapshot.fromWire(
                        data.readLong(), data.readInt(), data.readInt(), data.readInt()));
            } catch (IllegalArgumentException badRecord) {
                throw new IOException("world snapshot v6 engine-state record invalid", badRecord);
            }
        }
        if (data.read() != -1) {
            throw new IOException("world snapshot body has trailing bytes");
        }
        return new AuthoritativeWorldSnapshot(
                simTick, entities, nonShipEntities, fluxStates, weaponMounts, List.of(),
                allegianceStates, engineStates);
    }

    /**
     * Decode a schema-v7 body: ships (already read), then <em>declared</em> non-ship,
     * flux, weapon-mount, allegiance, and engine-state sections (each count + records,
     * each possibly empty), and finally a declared maneuver-command section.
     *
     * <p>Like the v6 path the lower sections are read by their declared counts advancing
     * an explicit offset. Unlike v6, the engine-state section here may be empty (a v7
     * frame is stamped when it carries maneuver commands, independent of engine state);
     * the maneuver-command section must carry at least one record (a maneuver-empty frame
     * would have been stamped v6-or-lower by {@link #writeFrame}). Every length is
     * validated against the body so a corrupt count fails closed, and each
     * maneuver-command record passes the {@link ShipManeuverCommandSnapshot#fromWire}
     * 7-bit mask range check.
     */
    private static AuthoritativeWorldSnapshot decodeBodyV7(
            DataInputStream data,
            int bodyLength,
            int shipRegionEnd,
            long simTick,
            List<AuthoritativeEntitySnapshot> entities) throws IOException {
        int remaining = bodyLength - shipRegionEnd;
        if (remaining < NON_SHIP_COUNT_BYTES + FLUX_COUNT_BYTES + WEAPON_MOUNT_COUNT_BYTES
                + ALLEGIANCE_COUNT_BYTES + ENGINE_STATE_COUNT_BYTES + MANEUVER_COMMAND_COUNT_BYTES) {
            throw new IOException("world snapshot v7 frame truncated: "
                    + remaining + " trailing bytes after ship records");
        }
        int nonShipCount = data.readInt();
        if (nonShipCount < 0) {
            throw new IOException("world snapshot v7 non-ship count negative: " + nonShipCount);
        }
        int nonShipRegion = NON_SHIP_COUNT_BYTES + NON_SHIP_ENTITY_BYTES * nonShipCount;
        if (nonShipRegion + FLUX_COUNT_BYTES + WEAPON_MOUNT_COUNT_BYTES + ALLEGIANCE_COUNT_BYTES
                + ENGINE_STATE_COUNT_BYTES + MANEUVER_COMMAND_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v7 non-ship count inconsistent with body length: " + nonShipCount);
        }
        List<NonShipEntitySnapshot> nonShipEntities = new ArrayList<>(nonShipCount);
        for (int i = 0; i < nonShipCount; i++) {
            nonShipEntities.add(new NonShipEntitySnapshot(
                    data.readLong(),
                    NonShipEntitySnapshot.kindFromWireCode(data.readInt()),
                    data.readInt(),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt()));
        }
        int fluxCount = data.readInt();
        if (fluxCount < 0) {
            throw new IOException("world snapshot v7 flux count negative: " + fluxCount);
        }
        int fluxRegion = FLUX_COUNT_BYTES + FLUX_ENTITY_BYTES * fluxCount;
        if (nonShipRegion + fluxRegion + WEAPON_MOUNT_COUNT_BYTES + ALLEGIANCE_COUNT_BYTES
                + ENGINE_STATE_COUNT_BYTES + MANEUVER_COMMAND_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v7 flux count inconsistent with body length: " + fluxCount);
        }
        List<ShipFluxSnapshot> fluxStates = new ArrayList<>(fluxCount);
        for (int i = 0; i < fluxCount; i++) {
            fluxStates.add(ShipFluxSnapshot.fromWire(
                    data.readLong(),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt()));
        }
        int weaponCount = data.readInt();
        if (weaponCount < 0) {
            throw new IOException("world snapshot v7 weapon-mount count negative: " + weaponCount);
        }
        int weaponRegion = WEAPON_MOUNT_COUNT_BYTES + WEAPON_MOUNT_ENTITY_BYTES * weaponCount;
        if (nonShipRegion + fluxRegion + weaponRegion + ALLEGIANCE_COUNT_BYTES
                + ENGINE_STATE_COUNT_BYTES + MANEUVER_COMMAND_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v7 weapon-mount count inconsistent with body length: " + weaponCount);
        }
        List<ShipWeaponMountSnapshot> weaponMounts = new ArrayList<>(weaponCount);
        for (int i = 0; i < weaponCount; i++) {
            weaponMounts.add(ShipWeaponMountSnapshot.fromWire(
                    data.readLong(),
                    data.readInt(),
                    data.readInt(),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt(),
                    data.readInt(),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt()));
        }
        int allegianceCount = data.readInt();
        if (allegianceCount < 0) {
            throw new IOException("world snapshot v7 allegiance count negative: " + allegianceCount);
        }
        int allegianceRegion = ALLEGIANCE_COUNT_BYTES + ALLEGIANCE_ENTITY_BYTES * allegianceCount;
        if (nonShipRegion + fluxRegion + weaponRegion + allegianceRegion
                + ENGINE_STATE_COUNT_BYTES + MANEUVER_COMMAND_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v7 allegiance count inconsistent with body length: " + allegianceCount);
        }
        List<ShipAllegianceSnapshot> allegianceStates = new ArrayList<>(allegianceCount);
        for (int i = 0; i < allegianceCount; i++) {
            allegianceStates.add(new ShipAllegianceSnapshot(data.readLong(), data.readInt()));
        }
        int engineStateCount = data.readInt();
        if (engineStateCount < 0) {
            throw new IOException(
                    "world snapshot v7 engine-state count negative: " + engineStateCount);
        }
        int engineStateRegion = ENGINE_STATE_COUNT_BYTES + ENGINE_STATE_ENTITY_BYTES * engineStateCount;
        if (nonShipRegion + fluxRegion + weaponRegion + allegianceRegion + engineStateRegion
                + MANEUVER_COMMAND_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v7 engine-state count inconsistent with body length: "
                            + engineStateCount);
        }
        List<ShipEngineStateSnapshot> engineStates = new ArrayList<>(engineStateCount);
        for (int i = 0; i < engineStateCount; i++) {
            try {
                engineStates.add(ShipEngineStateSnapshot.fromWire(
                        data.readLong(), data.readInt(), data.readInt(), data.readInt()));
            } catch (IllegalArgumentException badRecord) {
                throw new IOException("world snapshot v7 engine-state record invalid", badRecord);
            }
        }
        int maneuverCount = data.readInt();
        if (maneuverCount <= 0) {
            // Fail-closed: a v7 frame is stamped only when it actually carries maneuver commands.
            throw new IOException(
                    "world snapshot v7 frame declares no maneuver-command tier: " + maneuverCount);
        }
        int maneuverRegion = MANEUVER_COMMAND_COUNT_BYTES + MANEUVER_COMMAND_ENTITY_BYTES * maneuverCount;
        if (nonShipRegion + fluxRegion + weaponRegion + allegianceRegion + engineStateRegion
                + maneuverRegion != remaining) {
            throw new IOException(
                    "world snapshot v7 maneuver-command count inconsistent with body length: "
                            + maneuverCount);
        }
        List<ShipManeuverCommandSnapshot> maneuverCommands = new ArrayList<>(maneuverCount);
        for (int i = 0; i < maneuverCount; i++) {
            try {
                maneuverCommands.add(ShipManeuverCommandSnapshot.fromWire(
                        data.readLong(), data.readInt()));
            } catch (IllegalArgumentException badRecord) {
                throw new IOException("world snapshot v7 maneuver-command record invalid", badRecord);
            }
        }
        if (data.read() != -1) {
            throw new IOException("world snapshot body has trailing bytes");
        }
        return new AuthoritativeWorldSnapshot(
                simTick, entities, nonShipEntities, fluxStates, weaponMounts, List.of(),
                allegianceStates, engineStates, maneuverCommands);
    }

    /**
     * Decode a schema-v8 body: ships (already read), then <em>declared</em> non-ship,
     * flux, weapon-mount, allegiance, engine-state, and maneuver-command sections (each
     * count + records, each possibly empty), and finally a declared system-state section.
     *
     * <p>Like the v7 path the lower sections are read by their declared counts advancing
     * an explicit offset. Unlike v7, the maneuver-command section here may be empty (a v8
     * frame is stamped when it carries system states, independent of maneuver commands);
     * the system-state section must carry at least one record (a system-state-empty frame
     * would have been stamped v7-or-lower by {@link #writeFrame}). Every length is
     * validated against the body so a corrupt count fails closed, and each system-state
     * record passes the {@link ShipSystemStateSnapshot#fromWire} project-owned
     * state-code/ammo range checks.
     */
    private static AuthoritativeWorldSnapshot decodeBodyV8(
            DataInputStream data,
            int bodyLength,
            int shipRegionEnd,
            long simTick,
            List<AuthoritativeEntitySnapshot> entities) throws IOException {
        int remaining = bodyLength - shipRegionEnd;
        if (remaining < NON_SHIP_COUNT_BYTES + FLUX_COUNT_BYTES + WEAPON_MOUNT_COUNT_BYTES
                + ALLEGIANCE_COUNT_BYTES + ENGINE_STATE_COUNT_BYTES + MANEUVER_COMMAND_COUNT_BYTES
                + SYSTEM_STATE_COUNT_BYTES) {
            throw new IOException("world snapshot v8 frame truncated: "
                    + remaining + " trailing bytes after ship records");
        }
        int nonShipCount = data.readInt();
        if (nonShipCount < 0) {
            throw new IOException("world snapshot v8 non-ship count negative: " + nonShipCount);
        }
        int nonShipRegion = NON_SHIP_COUNT_BYTES + NON_SHIP_ENTITY_BYTES * nonShipCount;
        if (nonShipRegion + FLUX_COUNT_BYTES + WEAPON_MOUNT_COUNT_BYTES + ALLEGIANCE_COUNT_BYTES
                + ENGINE_STATE_COUNT_BYTES + MANEUVER_COMMAND_COUNT_BYTES
                + SYSTEM_STATE_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v8 non-ship count inconsistent with body length: " + nonShipCount);
        }
        List<NonShipEntitySnapshot> nonShipEntities = new ArrayList<>(nonShipCount);
        for (int i = 0; i < nonShipCount; i++) {
            nonShipEntities.add(new NonShipEntitySnapshot(
                    data.readLong(),
                    NonShipEntitySnapshot.kindFromWireCode(data.readInt()),
                    data.readInt(),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt()));
        }
        int fluxCount = data.readInt();
        if (fluxCount < 0) {
            throw new IOException("world snapshot v8 flux count negative: " + fluxCount);
        }
        int fluxRegion = FLUX_COUNT_BYTES + FLUX_ENTITY_BYTES * fluxCount;
        if (nonShipRegion + fluxRegion + WEAPON_MOUNT_COUNT_BYTES + ALLEGIANCE_COUNT_BYTES
                + ENGINE_STATE_COUNT_BYTES + MANEUVER_COMMAND_COUNT_BYTES
                + SYSTEM_STATE_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v8 flux count inconsistent with body length: " + fluxCount);
        }
        List<ShipFluxSnapshot> fluxStates = new ArrayList<>(fluxCount);
        for (int i = 0; i < fluxCount; i++) {
            fluxStates.add(ShipFluxSnapshot.fromWire(
                    data.readLong(),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt()));
        }
        int weaponCount = data.readInt();
        if (weaponCount < 0) {
            throw new IOException("world snapshot v8 weapon-mount count negative: " + weaponCount);
        }
        int weaponRegion = WEAPON_MOUNT_COUNT_BYTES + WEAPON_MOUNT_ENTITY_BYTES * weaponCount;
        if (nonShipRegion + fluxRegion + weaponRegion + ALLEGIANCE_COUNT_BYTES
                + ENGINE_STATE_COUNT_BYTES + MANEUVER_COMMAND_COUNT_BYTES
                + SYSTEM_STATE_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v8 weapon-mount count inconsistent with body length: " + weaponCount);
        }
        List<ShipWeaponMountSnapshot> weaponMounts = new ArrayList<>(weaponCount);
        for (int i = 0; i < weaponCount; i++) {
            weaponMounts.add(ShipWeaponMountSnapshot.fromWire(
                    data.readLong(),
                    data.readInt(),
                    data.readInt(),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt(),
                    data.readInt(),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt()));
        }
        int allegianceCount = data.readInt();
        if (allegianceCount < 0) {
            throw new IOException("world snapshot v8 allegiance count negative: " + allegianceCount);
        }
        int allegianceRegion = ALLEGIANCE_COUNT_BYTES + ALLEGIANCE_ENTITY_BYTES * allegianceCount;
        if (nonShipRegion + fluxRegion + weaponRegion + allegianceRegion
                + ENGINE_STATE_COUNT_BYTES + MANEUVER_COMMAND_COUNT_BYTES
                + SYSTEM_STATE_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v8 allegiance count inconsistent with body length: " + allegianceCount);
        }
        List<ShipAllegianceSnapshot> allegianceStates = new ArrayList<>(allegianceCount);
        for (int i = 0; i < allegianceCount; i++) {
            allegianceStates.add(new ShipAllegianceSnapshot(data.readLong(), data.readInt()));
        }
        int engineStateCount = data.readInt();
        if (engineStateCount < 0) {
            throw new IOException(
                    "world snapshot v8 engine-state count negative: " + engineStateCount);
        }
        int engineStateRegion = ENGINE_STATE_COUNT_BYTES + ENGINE_STATE_ENTITY_BYTES * engineStateCount;
        if (nonShipRegion + fluxRegion + weaponRegion + allegianceRegion + engineStateRegion
                + MANEUVER_COMMAND_COUNT_BYTES + SYSTEM_STATE_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v8 engine-state count inconsistent with body length: "
                            + engineStateCount);
        }
        List<ShipEngineStateSnapshot> engineStates = new ArrayList<>(engineStateCount);
        for (int i = 0; i < engineStateCount; i++) {
            try {
                engineStates.add(ShipEngineStateSnapshot.fromWire(
                        data.readLong(), data.readInt(), data.readInt(), data.readInt()));
            } catch (IllegalArgumentException badRecord) {
                throw new IOException("world snapshot v8 engine-state record invalid", badRecord);
            }
        }
        int maneuverCount = data.readInt();
        if (maneuverCount < 0) {
            throw new IOException(
                    "world snapshot v8 maneuver-command count negative: " + maneuverCount);
        }
        int maneuverRegion = MANEUVER_COMMAND_COUNT_BYTES + MANEUVER_COMMAND_ENTITY_BYTES * maneuverCount;
        if (nonShipRegion + fluxRegion + weaponRegion + allegianceRegion + engineStateRegion
                + maneuverRegion + SYSTEM_STATE_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v8 maneuver-command count inconsistent with body length: "
                            + maneuverCount);
        }
        List<ShipManeuverCommandSnapshot> maneuverCommands = new ArrayList<>(maneuverCount);
        for (int i = 0; i < maneuverCount; i++) {
            try {
                maneuverCommands.add(ShipManeuverCommandSnapshot.fromWire(
                        data.readLong(), data.readInt()));
            } catch (IllegalArgumentException badRecord) {
                throw new IOException("world snapshot v8 maneuver-command record invalid", badRecord);
            }
        }
        int systemStateCount = data.readInt();
        if (systemStateCount <= 0) {
            // Fail-closed: a v8 frame is stamped only when it actually carries system states.
            throw new IOException(
                    "world snapshot v8 frame declares no system-state tier: " + systemStateCount);
        }
        int systemStateRegion = SYSTEM_STATE_COUNT_BYTES + SYSTEM_STATE_ENTITY_BYTES * systemStateCount;
        if (nonShipRegion + fluxRegion + weaponRegion + allegianceRegion + engineStateRegion
                + maneuverRegion + systemStateRegion != remaining) {
            throw new IOException(
                    "world snapshot v8 system-state count inconsistent with body length: "
                            + systemStateCount);
        }
        List<ShipSystemStateSnapshot> systemStates = new ArrayList<>(systemStateCount);
        for (int i = 0; i < systemStateCount; i++) {
            try {
                systemStates.add(ShipSystemStateSnapshot.fromWire(
                        data.readLong(),
                        data.readInt(),
                        Float.intBitsToFloat(data.readInt()),
                        Float.intBitsToFloat(data.readInt()),
                        data.readInt()));
            } catch (IllegalArgumentException badRecord) {
                throw new IOException("world snapshot v8 system-state record invalid", badRecord);
            }
        }
        if (data.read() != -1) {
            throw new IOException("world snapshot body has trailing bytes");
        }
        return new AuthoritativeWorldSnapshot(
                simTick, entities, nonShipEntities, fluxStates, weaponMounts, List.of(),
                allegianceStates, engineStates, maneuverCommands, systemStates);
    }

    /**
     * Decode a schema-v9 body: ships (already read), then <em>declared</em> non-ship,
     * flux, weapon-mount, allegiance, engine-state, maneuver-command, and system-state
     * sections (each count + records, each possibly empty), and finally a declared
     * fragment-marker section.
     *
     * <p>Like the v8 path the lower sections are read by their declared counts advancing
     * an explicit offset. Unlike v8, the system-state section here may be empty (a v9
     * frame is stamped when it carries fragment markers, independent of system states);
     * the marker section must carry at least one record (a marker-empty frame would have
     * been stamped v8-or-lower by {@link #writeFrame}). Every length is validated against
     * the body so a corrupt count fails closed, and each marker record passes the
     * {@link ShipFragmentMarkerSnapshot#fromWire} tier-mask/mount-range checks.
     */
    private static AuthoritativeWorldSnapshot decodeBodyV9(
            DataInputStream data,
            int bodyLength,
            int shipRegionEnd,
            long simTick,
            List<AuthoritativeEntitySnapshot> entities) throws IOException {
        int remaining = bodyLength - shipRegionEnd;
        if (remaining < NON_SHIP_COUNT_BYTES + FLUX_COUNT_BYTES + WEAPON_MOUNT_COUNT_BYTES
                + ALLEGIANCE_COUNT_BYTES + ENGINE_STATE_COUNT_BYTES + MANEUVER_COMMAND_COUNT_BYTES
                + SYSTEM_STATE_COUNT_BYTES + FRAGMENT_MARKER_COUNT_BYTES) {
            throw new IOException("world snapshot v9 frame truncated: "
                    + remaining + " trailing bytes after ship records");
        }
        int nonShipCount = data.readInt();
        if (nonShipCount < 0) {
            throw new IOException("world snapshot v9 non-ship count negative: " + nonShipCount);
        }
        int nonShipRegion = NON_SHIP_COUNT_BYTES + NON_SHIP_ENTITY_BYTES * nonShipCount;
        if (nonShipRegion + FLUX_COUNT_BYTES + WEAPON_MOUNT_COUNT_BYTES + ALLEGIANCE_COUNT_BYTES
                + ENGINE_STATE_COUNT_BYTES + MANEUVER_COMMAND_COUNT_BYTES
                + SYSTEM_STATE_COUNT_BYTES + FRAGMENT_MARKER_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v9 non-ship count inconsistent with body length: " + nonShipCount);
        }
        List<NonShipEntitySnapshot> nonShipEntities = new ArrayList<>(nonShipCount);
        for (int i = 0; i < nonShipCount; i++) {
            nonShipEntities.add(new NonShipEntitySnapshot(
                    data.readLong(),
                    NonShipEntitySnapshot.kindFromWireCode(data.readInt()),
                    data.readInt(),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt()));
        }
        int fluxCount = data.readInt();
        if (fluxCount < 0) {
            throw new IOException("world snapshot v9 flux count negative: " + fluxCount);
        }
        int fluxRegion = FLUX_COUNT_BYTES + FLUX_ENTITY_BYTES * fluxCount;
        if (nonShipRegion + fluxRegion + WEAPON_MOUNT_COUNT_BYTES + ALLEGIANCE_COUNT_BYTES
                + ENGINE_STATE_COUNT_BYTES + MANEUVER_COMMAND_COUNT_BYTES
                + SYSTEM_STATE_COUNT_BYTES + FRAGMENT_MARKER_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v9 flux count inconsistent with body length: " + fluxCount);
        }
        List<ShipFluxSnapshot> fluxStates = new ArrayList<>(fluxCount);
        for (int i = 0; i < fluxCount; i++) {
            fluxStates.add(ShipFluxSnapshot.fromWire(
                    data.readLong(),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt()));
        }
        int weaponCount = data.readInt();
        if (weaponCount < 0) {
            throw new IOException("world snapshot v9 weapon-mount count negative: " + weaponCount);
        }
        int weaponRegion = WEAPON_MOUNT_COUNT_BYTES + WEAPON_MOUNT_ENTITY_BYTES * weaponCount;
        if (nonShipRegion + fluxRegion + weaponRegion + ALLEGIANCE_COUNT_BYTES
                + ENGINE_STATE_COUNT_BYTES + MANEUVER_COMMAND_COUNT_BYTES
                + SYSTEM_STATE_COUNT_BYTES + FRAGMENT_MARKER_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v9 weapon-mount count inconsistent with body length: " + weaponCount);
        }
        List<ShipWeaponMountSnapshot> weaponMounts = new ArrayList<>(weaponCount);
        for (int i = 0; i < weaponCount; i++) {
            weaponMounts.add(ShipWeaponMountSnapshot.fromWire(
                    data.readLong(),
                    data.readInt(),
                    data.readInt(),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt(),
                    data.readInt(),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt()));
        }
        int allegianceCount = data.readInt();
        if (allegianceCount < 0) {
            throw new IOException("world snapshot v9 allegiance count negative: " + allegianceCount);
        }
        int allegianceRegion = ALLEGIANCE_COUNT_BYTES + ALLEGIANCE_ENTITY_BYTES * allegianceCount;
        if (nonShipRegion + fluxRegion + weaponRegion + allegianceRegion
                + ENGINE_STATE_COUNT_BYTES + MANEUVER_COMMAND_COUNT_BYTES
                + SYSTEM_STATE_COUNT_BYTES + FRAGMENT_MARKER_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v9 allegiance count inconsistent with body length: " + allegianceCount);
        }
        List<ShipAllegianceSnapshot> allegianceStates = new ArrayList<>(allegianceCount);
        for (int i = 0; i < allegianceCount; i++) {
            allegianceStates.add(new ShipAllegianceSnapshot(data.readLong(), data.readInt()));
        }
        int engineStateCount = data.readInt();
        if (engineStateCount < 0) {
            throw new IOException(
                    "world snapshot v9 engine-state count negative: " + engineStateCount);
        }
        int engineStateRegion = ENGINE_STATE_COUNT_BYTES + ENGINE_STATE_ENTITY_BYTES * engineStateCount;
        if (nonShipRegion + fluxRegion + weaponRegion + allegianceRegion + engineStateRegion
                + MANEUVER_COMMAND_COUNT_BYTES + SYSTEM_STATE_COUNT_BYTES
                + FRAGMENT_MARKER_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v9 engine-state count inconsistent with body length: "
                            + engineStateCount);
        }
        List<ShipEngineStateSnapshot> engineStates = new ArrayList<>(engineStateCount);
        for (int i = 0; i < engineStateCount; i++) {
            try {
                engineStates.add(ShipEngineStateSnapshot.fromWire(
                        data.readLong(), data.readInt(), data.readInt(), data.readInt()));
            } catch (IllegalArgumentException badRecord) {
                throw new IOException("world snapshot v9 engine-state record invalid", badRecord);
            }
        }
        int maneuverCount = data.readInt();
        if (maneuverCount < 0) {
            throw new IOException(
                    "world snapshot v9 maneuver-command count negative: " + maneuverCount);
        }
        int maneuverRegion = MANEUVER_COMMAND_COUNT_BYTES + MANEUVER_COMMAND_ENTITY_BYTES * maneuverCount;
        if (nonShipRegion + fluxRegion + weaponRegion + allegianceRegion + engineStateRegion
                + maneuverRegion + SYSTEM_STATE_COUNT_BYTES
                + FRAGMENT_MARKER_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v9 maneuver-command count inconsistent with body length: "
                            + maneuverCount);
        }
        List<ShipManeuverCommandSnapshot> maneuverCommands = new ArrayList<>(maneuverCount);
        for (int i = 0; i < maneuverCount; i++) {
            try {
                maneuverCommands.add(ShipManeuverCommandSnapshot.fromWire(
                        data.readLong(), data.readInt()));
            } catch (IllegalArgumentException badRecord) {
                throw new IOException("world snapshot v9 maneuver-command record invalid", badRecord);
            }
        }
        int systemStateCount = data.readInt();
        if (systemStateCount < 0) {
            throw new IOException(
                    "world snapshot v9 system-state count negative: " + systemStateCount);
        }
        int systemStateRegion = SYSTEM_STATE_COUNT_BYTES + SYSTEM_STATE_ENTITY_BYTES * systemStateCount;
        if (nonShipRegion + fluxRegion + weaponRegion + allegianceRegion + engineStateRegion
                + maneuverRegion + systemStateRegion + FRAGMENT_MARKER_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v9 system-state count inconsistent with body length: "
                            + systemStateCount);
        }
        List<ShipSystemStateSnapshot> systemStates = new ArrayList<>(systemStateCount);
        for (int i = 0; i < systemStateCount; i++) {
            try {
                systemStates.add(ShipSystemStateSnapshot.fromWire(
                        data.readLong(),
                        data.readInt(),
                        Float.intBitsToFloat(data.readInt()),
                        Float.intBitsToFloat(data.readInt()),
                        data.readInt()));
            } catch (IllegalArgumentException badRecord) {
                throw new IOException("world snapshot v9 system-state record invalid", badRecord);
            }
        }
        int markerCount = data.readInt();
        if (markerCount <= 0) {
            // Fail-closed: a v9 frame is stamped only when it actually carries markers.
            throw new IOException(
                    "world snapshot v9 frame declares no fragment-marker tier: " + markerCount);
        }
        int markerRegion = FRAGMENT_MARKER_COUNT_BYTES + FRAGMENT_MARKER_ENTITY_BYTES * markerCount;
        if (nonShipRegion + fluxRegion + weaponRegion + allegianceRegion + engineStateRegion
                + maneuverRegion + systemStateRegion + markerRegion != remaining) {
            throw new IOException(
                    "world snapshot v9 fragment-marker count inconsistent with body length: "
                            + markerCount);
        }
        List<ShipFragmentMarkerSnapshot> fragmentMarkers = new ArrayList<>(markerCount);
        for (int i = 0; i < markerCount; i++) {
            try {
                fragmentMarkers.add(ShipFragmentMarkerSnapshot.fromWire(
                        data.readLong(), data.readInt(), data.readInt(), data.readInt()));
            } catch (IllegalArgumentException badRecord) {
                throw new IOException("world snapshot v9 fragment-marker record invalid", badRecord);
            }
        }
        if (data.read() != -1) {
            throw new IOException("world snapshot body has trailing bytes");
        }
        // Full-arity construction routes through the Builder (the rule: the
        // one raw all-components call site is Builder.build()).
        return new AuthoritativeWorldSnapshot.Builder()
                .simTick(simTick)
                .entities(entities)
                .nonShipEntities(nonShipEntities)
                .fluxStates(fluxStates)
                .weaponMounts(weaponMounts)
                .allegianceStates(allegianceStates)
                .engineStates(engineStates)
                .maneuverCommands(maneuverCommands)
                .systemStates(systemStates)
                .fragmentMarkers(fragmentMarkers)
                .build();
    }

    /**
     * Decode a schema-v10 body: ships (already read), then <em>declared</em> non-ship,
     * flux, weapon-mount, allegiance, engine-state, maneuver-command, system-state, and
     * fragment-marker sections (each count + records, each possibly empty), and finally
     * a declared phase-cloak section (appended STRICTLY AFTER the marker
     * section per D-M2-2).
     *
     * <p>Like the v9 path the lower sections are read by their declared counts advancing
     * an explicit offset. Unlike v9, the fragment-marker section here may be empty (a
     * v10 frame is stamped when it carries phase-cloak states, independent of markers);
     * the phase-cloak section must carry at least one record (a cloak-empty frame would
     * have been stamped v9-or-lower by {@link #writeFrame}). Every length is validated
     * against the body so a corrupt count fails closed, and each phase-cloak record
     * passes the {@link ShipPhaseCloakStateSnapshot#fromWire} project-owned
     * state-code/ammo range checks.
     */
    private static AuthoritativeWorldSnapshot decodeBodyV10(
            DataInputStream data,
            int bodyLength,
            int shipRegionEnd,
            long simTick,
            List<AuthoritativeEntitySnapshot> entities) throws IOException {
        int remaining = bodyLength - shipRegionEnd;
        if (remaining < NON_SHIP_COUNT_BYTES + FLUX_COUNT_BYTES + WEAPON_MOUNT_COUNT_BYTES
                + ALLEGIANCE_COUNT_BYTES + ENGINE_STATE_COUNT_BYTES + MANEUVER_COMMAND_COUNT_BYTES
                + SYSTEM_STATE_COUNT_BYTES + FRAGMENT_MARKER_COUNT_BYTES
                + PHASE_CLOAK_COUNT_BYTES) {
            throw new IOException("world snapshot v10 frame truncated: "
                    + remaining + " trailing bytes after ship records");
        }
        int nonShipCount = data.readInt();
        if (nonShipCount < 0) {
            throw new IOException("world snapshot v10 non-ship count negative: " + nonShipCount);
        }
        int nonShipRegion = NON_SHIP_COUNT_BYTES + NON_SHIP_ENTITY_BYTES * nonShipCount;
        if (nonShipRegion + FLUX_COUNT_BYTES + WEAPON_MOUNT_COUNT_BYTES + ALLEGIANCE_COUNT_BYTES
                + ENGINE_STATE_COUNT_BYTES + MANEUVER_COMMAND_COUNT_BYTES
                + SYSTEM_STATE_COUNT_BYTES + FRAGMENT_MARKER_COUNT_BYTES
                + PHASE_CLOAK_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v10 non-ship count inconsistent with body length: " + nonShipCount);
        }
        List<NonShipEntitySnapshot> nonShipEntities = new ArrayList<>(nonShipCount);
        for (int i = 0; i < nonShipCount; i++) {
            nonShipEntities.add(new NonShipEntitySnapshot(
                    data.readLong(),
                    NonShipEntitySnapshot.kindFromWireCode(data.readInt()),
                    data.readInt(),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt()));
        }
        int fluxCount = data.readInt();
        if (fluxCount < 0) {
            throw new IOException("world snapshot v10 flux count negative: " + fluxCount);
        }
        int fluxRegion = FLUX_COUNT_BYTES + FLUX_ENTITY_BYTES * fluxCount;
        if (nonShipRegion + fluxRegion + WEAPON_MOUNT_COUNT_BYTES + ALLEGIANCE_COUNT_BYTES
                + ENGINE_STATE_COUNT_BYTES + MANEUVER_COMMAND_COUNT_BYTES
                + SYSTEM_STATE_COUNT_BYTES + FRAGMENT_MARKER_COUNT_BYTES
                + PHASE_CLOAK_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v10 flux count inconsistent with body length: " + fluxCount);
        }
        List<ShipFluxSnapshot> fluxStates = new ArrayList<>(fluxCount);
        for (int i = 0; i < fluxCount; i++) {
            fluxStates.add(ShipFluxSnapshot.fromWire(
                    data.readLong(),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt()));
        }
        int weaponCount = data.readInt();
        if (weaponCount < 0) {
            throw new IOException("world snapshot v10 weapon-mount count negative: " + weaponCount);
        }
        int weaponRegion = WEAPON_MOUNT_COUNT_BYTES + WEAPON_MOUNT_ENTITY_BYTES * weaponCount;
        if (nonShipRegion + fluxRegion + weaponRegion + ALLEGIANCE_COUNT_BYTES
                + ENGINE_STATE_COUNT_BYTES + MANEUVER_COMMAND_COUNT_BYTES
                + SYSTEM_STATE_COUNT_BYTES + FRAGMENT_MARKER_COUNT_BYTES
                + PHASE_CLOAK_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v10 weapon-mount count inconsistent with body length: " + weaponCount);
        }
        List<ShipWeaponMountSnapshot> weaponMounts = new ArrayList<>(weaponCount);
        for (int i = 0; i < weaponCount; i++) {
            weaponMounts.add(ShipWeaponMountSnapshot.fromWire(
                    data.readLong(),
                    data.readInt(),
                    data.readInt(),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt(),
                    data.readInt(),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt()));
        }
        int allegianceCount = data.readInt();
        if (allegianceCount < 0) {
            throw new IOException("world snapshot v10 allegiance count negative: " + allegianceCount);
        }
        int allegianceRegion = ALLEGIANCE_COUNT_BYTES + ALLEGIANCE_ENTITY_BYTES * allegianceCount;
        if (nonShipRegion + fluxRegion + weaponRegion + allegianceRegion
                + ENGINE_STATE_COUNT_BYTES + MANEUVER_COMMAND_COUNT_BYTES
                + SYSTEM_STATE_COUNT_BYTES + FRAGMENT_MARKER_COUNT_BYTES
                + PHASE_CLOAK_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v10 allegiance count inconsistent with body length: " + allegianceCount);
        }
        List<ShipAllegianceSnapshot> allegianceStates = new ArrayList<>(allegianceCount);
        for (int i = 0; i < allegianceCount; i++) {
            allegianceStates.add(new ShipAllegianceSnapshot(data.readLong(), data.readInt()));
        }
        int engineStateCount = data.readInt();
        if (engineStateCount < 0) {
            throw new IOException(
                    "world snapshot v10 engine-state count negative: " + engineStateCount);
        }
        int engineStateRegion = ENGINE_STATE_COUNT_BYTES + ENGINE_STATE_ENTITY_BYTES * engineStateCount;
        if (nonShipRegion + fluxRegion + weaponRegion + allegianceRegion + engineStateRegion
                + MANEUVER_COMMAND_COUNT_BYTES + SYSTEM_STATE_COUNT_BYTES
                + FRAGMENT_MARKER_COUNT_BYTES + PHASE_CLOAK_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v10 engine-state count inconsistent with body length: "
                            + engineStateCount);
        }
        List<ShipEngineStateSnapshot> engineStates = new ArrayList<>(engineStateCount);
        for (int i = 0; i < engineStateCount; i++) {
            try {
                engineStates.add(ShipEngineStateSnapshot.fromWire(
                        data.readLong(), data.readInt(), data.readInt(), data.readInt()));
            } catch (IllegalArgumentException badRecord) {
                throw new IOException("world snapshot v10 engine-state record invalid", badRecord);
            }
        }
        int maneuverCount = data.readInt();
        if (maneuverCount < 0) {
            throw new IOException(
                    "world snapshot v10 maneuver-command count negative: " + maneuverCount);
        }
        int maneuverRegion = MANEUVER_COMMAND_COUNT_BYTES + MANEUVER_COMMAND_ENTITY_BYTES * maneuverCount;
        if (nonShipRegion + fluxRegion + weaponRegion + allegianceRegion + engineStateRegion
                + maneuverRegion + SYSTEM_STATE_COUNT_BYTES + FRAGMENT_MARKER_COUNT_BYTES
                + PHASE_CLOAK_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v10 maneuver-command count inconsistent with body length: "
                            + maneuverCount);
        }
        List<ShipManeuverCommandSnapshot> maneuverCommands = new ArrayList<>(maneuverCount);
        for (int i = 0; i < maneuverCount; i++) {
            try {
                maneuverCommands.add(ShipManeuverCommandSnapshot.fromWire(
                        data.readLong(), data.readInt()));
            } catch (IllegalArgumentException badRecord) {
                throw new IOException("world snapshot v10 maneuver-command record invalid", badRecord);
            }
        }
        int systemStateCount = data.readInt();
        if (systemStateCount < 0) {
            throw new IOException(
                    "world snapshot v10 system-state count negative: " + systemStateCount);
        }
        int systemStateRegion = SYSTEM_STATE_COUNT_BYTES + SYSTEM_STATE_ENTITY_BYTES * systemStateCount;
        if (nonShipRegion + fluxRegion + weaponRegion + allegianceRegion + engineStateRegion
                + maneuverRegion + systemStateRegion + FRAGMENT_MARKER_COUNT_BYTES
                + PHASE_CLOAK_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v10 system-state count inconsistent with body length: "
                            + systemStateCount);
        }
        List<ShipSystemStateSnapshot> systemStates = new ArrayList<>(systemStateCount);
        for (int i = 0; i < systemStateCount; i++) {
            try {
                systemStates.add(ShipSystemStateSnapshot.fromWire(
                        data.readLong(),
                        data.readInt(),
                        Float.intBitsToFloat(data.readInt()),
                        Float.intBitsToFloat(data.readInt()),
                        data.readInt()));
            } catch (IllegalArgumentException badRecord) {
                throw new IOException("world snapshot v10 system-state record invalid", badRecord);
            }
        }
        int markerCount = data.readInt();
        if (markerCount < 0) {
            throw new IOException(
                    "world snapshot v10 fragment-marker count negative: " + markerCount);
        }
        int markerRegion = FRAGMENT_MARKER_COUNT_BYTES + FRAGMENT_MARKER_ENTITY_BYTES * markerCount;
        if (nonShipRegion + fluxRegion + weaponRegion + allegianceRegion + engineStateRegion
                + maneuverRegion + systemStateRegion + markerRegion
                + PHASE_CLOAK_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v10 fragment-marker count inconsistent with body length: "
                            + markerCount);
        }
        List<ShipFragmentMarkerSnapshot> fragmentMarkers = new ArrayList<>(markerCount);
        for (int i = 0; i < markerCount; i++) {
            try {
                fragmentMarkers.add(ShipFragmentMarkerSnapshot.fromWire(
                        data.readLong(), data.readInt(), data.readInt(), data.readInt()));
            } catch (IllegalArgumentException badRecord) {
                throw new IOException("world snapshot v10 fragment-marker record invalid", badRecord);
            }
        }
        int phaseCloakCount = data.readInt();
        if (phaseCloakCount <= 0) {
            // Fail-closed: a v10 frame is stamped only when it actually carries cloak states.
            throw new IOException(
                    "world snapshot v10 frame declares no phase-cloak tier: " + phaseCloakCount);
        }
        int phaseCloakRegion = PHASE_CLOAK_COUNT_BYTES + PHASE_CLOAK_ENTITY_BYTES * phaseCloakCount;
        if (nonShipRegion + fluxRegion + weaponRegion + allegianceRegion + engineStateRegion
                + maneuverRegion + systemStateRegion + markerRegion + phaseCloakRegion != remaining) {
            throw new IOException(
                    "world snapshot v10 phase-cloak count inconsistent with body length: "
                            + phaseCloakCount);
        }
        List<ShipPhaseCloakStateSnapshot> phaseCloakStates = new ArrayList<>(phaseCloakCount);
        for (int i = 0; i < phaseCloakCount; i++) {
            try {
                phaseCloakStates.add(ShipPhaseCloakStateSnapshot.fromWire(
                        data.readLong(),
                        data.readInt(),
                        Float.intBitsToFloat(data.readInt()),
                        Float.intBitsToFloat(data.readInt()),
                        data.readInt()));
            } catch (IllegalArgumentException badRecord) {
                throw new IOException("world snapshot v10 phase-cloak record invalid", badRecord);
            }
        }
        if (data.read() != -1) {
            throw new IOException("world snapshot body has trailing bytes");
        }
        // Full-arity construction routes through the Builder (the rule: the
        // one raw all-components call site is Builder.build()).
        return new AuthoritativeWorldSnapshot.Builder()
                .simTick(simTick)
                .entities(entities)
                .nonShipEntities(nonShipEntities)
                .fluxStates(fluxStates)
                .weaponMounts(weaponMounts)
                .allegianceStates(allegianceStates)
                .engineStates(engineStates)
                .maneuverCommands(maneuverCommands)
                .systemStates(systemStates)
                .fragmentMarkers(fragmentMarkers)
                .phaseCloakStates(phaseCloakStates)
                .build();
    }

    /**
     * Decode a schema-v11 body: every v10 section in the same order, with
     * the flux records widened to {@link #FLUX_ENTITY_BYTES_V11} — the quantized
     * shield-facing u8 sits between hardFlux and statusFlags ON THE WIRE, while the
     * 7-arg {@link ShipFluxSnapshot#fromWire} takes it AFTER statusFlags (read into
     * locals; the argument order is deliberately not the wire order). Unlike v10, the
     * phase-cloak section here may be empty (a v11 frame is stamped when some flux
     * record carries facing != 0, independent of cloaks); at least one flux record
     * must carry a nonzero facing — a facing-0 frame would have been stamped
     * v10-or-lower by {@link #writeFrame} (the charter R3 stamp-lowest analog). Every
     * length is validated against the body so a corrupt count fails closed.
     */
    private static AuthoritativeWorldSnapshot decodeBodyV11(
            DataInputStream data,
            int bodyLength,
            int shipRegionEnd,
            long simTick,
            List<AuthoritativeEntitySnapshot> entities) throws IOException {
        int remaining = bodyLength - shipRegionEnd;
        if (remaining < NON_SHIP_COUNT_BYTES + FLUX_COUNT_BYTES + WEAPON_MOUNT_COUNT_BYTES
                + ALLEGIANCE_COUNT_BYTES + ENGINE_STATE_COUNT_BYTES + MANEUVER_COMMAND_COUNT_BYTES
                + SYSTEM_STATE_COUNT_BYTES + FRAGMENT_MARKER_COUNT_BYTES
                + PHASE_CLOAK_COUNT_BYTES) {
            throw new IOException("world snapshot v11 frame truncated: "
                    + remaining + " trailing bytes after ship records");
        }
        int nonShipCount = data.readInt();
        if (nonShipCount < 0) {
            throw new IOException("world snapshot v11 non-ship count negative: " + nonShipCount);
        }
        int nonShipRegion = NON_SHIP_COUNT_BYTES + NON_SHIP_ENTITY_BYTES * nonShipCount;
        if (nonShipRegion + FLUX_COUNT_BYTES + WEAPON_MOUNT_COUNT_BYTES + ALLEGIANCE_COUNT_BYTES
                + ENGINE_STATE_COUNT_BYTES + MANEUVER_COMMAND_COUNT_BYTES
                + SYSTEM_STATE_COUNT_BYTES + FRAGMENT_MARKER_COUNT_BYTES
                + PHASE_CLOAK_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v11 non-ship count inconsistent with body length: " + nonShipCount);
        }
        List<NonShipEntitySnapshot> nonShipEntities = new ArrayList<>(nonShipCount);
        for (int i = 0; i < nonShipCount; i++) {
            nonShipEntities.add(new NonShipEntitySnapshot(
                    data.readLong(),
                    NonShipEntitySnapshot.kindFromWireCode(data.readInt()),
                    data.readInt(),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt()));
        }
        int fluxCount = data.readInt();
        if (fluxCount < 0) {
            throw new IOException("world snapshot v11 flux count negative: " + fluxCount);
        }
        int fluxRegion = FLUX_COUNT_BYTES + FLUX_ENTITY_BYTES_V11 * fluxCount;
        if (nonShipRegion + fluxRegion + WEAPON_MOUNT_COUNT_BYTES + ALLEGIANCE_COUNT_BYTES
                + ENGINE_STATE_COUNT_BYTES + MANEUVER_COMMAND_COUNT_BYTES
                + SYSTEM_STATE_COUNT_BYTES + FRAGMENT_MARKER_COUNT_BYTES
                + PHASE_CLOAK_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v11 flux count inconsistent with body length: " + fluxCount);
        }
        List<ShipFluxSnapshot> fluxStates = new ArrayList<>(fluxCount);
        boolean anyShieldFacing = false;
        for (int i = 0; i < fluxCount; i++) {
            long entityId = data.readLong();
            float currFlux = Float.intBitsToFloat(data.readInt());
            float maxFlux = Float.intBitsToFloat(data.readInt());
            float fluxLevel = Float.intBitsToFloat(data.readInt());
            float hardFlux = Float.intBitsToFloat(data.readInt());
            int shieldFacingU8 = data.readUnsignedByte();
            int statusFlags = data.readInt();
            anyShieldFacing |= shieldFacingU8 != 0;
            fluxStates.add(ShipFluxSnapshot.fromWire(
                    entityId, currFlux, maxFlux, fluxLevel, hardFlux, statusFlags, shieldFacingU8));
        }
        if (!anyShieldFacing) {
            // Fail-closed: a v11 frame is stamped only when some flux record actually
            // carries a nonzero shield facing (the value-based stamp predicate).
            throw new IOException("world snapshot v11 frame declares no nonzero shield facing");
        }
        int weaponCount = data.readInt();
        if (weaponCount < 0) {
            throw new IOException("world snapshot v11 weapon-mount count negative: " + weaponCount);
        }
        int weaponRegion = WEAPON_MOUNT_COUNT_BYTES + WEAPON_MOUNT_ENTITY_BYTES * weaponCount;
        if (nonShipRegion + fluxRegion + weaponRegion + ALLEGIANCE_COUNT_BYTES
                + ENGINE_STATE_COUNT_BYTES + MANEUVER_COMMAND_COUNT_BYTES
                + SYSTEM_STATE_COUNT_BYTES + FRAGMENT_MARKER_COUNT_BYTES
                + PHASE_CLOAK_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v11 weapon-mount count inconsistent with body length: " + weaponCount);
        }
        List<ShipWeaponMountSnapshot> weaponMounts = new ArrayList<>(weaponCount);
        for (int i = 0; i < weaponCount; i++) {
            weaponMounts.add(ShipWeaponMountSnapshot.fromWire(
                    data.readLong(),
                    data.readInt(),
                    data.readInt(),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt(),
                    data.readInt(),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt()));
        }
        int allegianceCount = data.readInt();
        if (allegianceCount < 0) {
            throw new IOException("world snapshot v11 allegiance count negative: " + allegianceCount);
        }
        int allegianceRegion = ALLEGIANCE_COUNT_BYTES + ALLEGIANCE_ENTITY_BYTES * allegianceCount;
        if (nonShipRegion + fluxRegion + weaponRegion + allegianceRegion
                + ENGINE_STATE_COUNT_BYTES + MANEUVER_COMMAND_COUNT_BYTES
                + SYSTEM_STATE_COUNT_BYTES + FRAGMENT_MARKER_COUNT_BYTES
                + PHASE_CLOAK_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v11 allegiance count inconsistent with body length: " + allegianceCount);
        }
        List<ShipAllegianceSnapshot> allegianceStates = new ArrayList<>(allegianceCount);
        for (int i = 0; i < allegianceCount; i++) {
            allegianceStates.add(new ShipAllegianceSnapshot(data.readLong(), data.readInt()));
        }
        int engineStateCount = data.readInt();
        if (engineStateCount < 0) {
            throw new IOException(
                    "world snapshot v11 engine-state count negative: " + engineStateCount);
        }
        int engineStateRegion = ENGINE_STATE_COUNT_BYTES + ENGINE_STATE_ENTITY_BYTES * engineStateCount;
        if (nonShipRegion + fluxRegion + weaponRegion + allegianceRegion + engineStateRegion
                + MANEUVER_COMMAND_COUNT_BYTES + SYSTEM_STATE_COUNT_BYTES
                + FRAGMENT_MARKER_COUNT_BYTES + PHASE_CLOAK_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v11 engine-state count inconsistent with body length: "
                            + engineStateCount);
        }
        List<ShipEngineStateSnapshot> engineStates = new ArrayList<>(engineStateCount);
        for (int i = 0; i < engineStateCount; i++) {
            try {
                engineStates.add(ShipEngineStateSnapshot.fromWire(
                        data.readLong(), data.readInt(), data.readInt(), data.readInt()));
            } catch (IllegalArgumentException badRecord) {
                throw new IOException("world snapshot v11 engine-state record invalid", badRecord);
            }
        }
        int maneuverCount = data.readInt();
        if (maneuverCount < 0) {
            throw new IOException(
                    "world snapshot v11 maneuver-command count negative: " + maneuverCount);
        }
        int maneuverRegion = MANEUVER_COMMAND_COUNT_BYTES + MANEUVER_COMMAND_ENTITY_BYTES * maneuverCount;
        if (nonShipRegion + fluxRegion + weaponRegion + allegianceRegion + engineStateRegion
                + maneuverRegion + SYSTEM_STATE_COUNT_BYTES + FRAGMENT_MARKER_COUNT_BYTES
                + PHASE_CLOAK_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v11 maneuver-command count inconsistent with body length: "
                            + maneuverCount);
        }
        List<ShipManeuverCommandSnapshot> maneuverCommands = new ArrayList<>(maneuverCount);
        for (int i = 0; i < maneuverCount; i++) {
            try {
                maneuverCommands.add(ShipManeuverCommandSnapshot.fromWire(
                        data.readLong(), data.readInt()));
            } catch (IllegalArgumentException badRecord) {
                throw new IOException("world snapshot v11 maneuver-command record invalid", badRecord);
            }
        }
        int systemStateCount = data.readInt();
        if (systemStateCount < 0) {
            throw new IOException(
                    "world snapshot v11 system-state count negative: " + systemStateCount);
        }
        int systemStateRegion = SYSTEM_STATE_COUNT_BYTES + SYSTEM_STATE_ENTITY_BYTES * systemStateCount;
        if (nonShipRegion + fluxRegion + weaponRegion + allegianceRegion + engineStateRegion
                + maneuverRegion + systemStateRegion + FRAGMENT_MARKER_COUNT_BYTES
                + PHASE_CLOAK_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v11 system-state count inconsistent with body length: "
                            + systemStateCount);
        }
        List<ShipSystemStateSnapshot> systemStates = new ArrayList<>(systemStateCount);
        for (int i = 0; i < systemStateCount; i++) {
            try {
                systemStates.add(ShipSystemStateSnapshot.fromWire(
                        data.readLong(),
                        data.readInt(),
                        Float.intBitsToFloat(data.readInt()),
                        Float.intBitsToFloat(data.readInt()),
                        data.readInt()));
            } catch (IllegalArgumentException badRecord) {
                throw new IOException("world snapshot v11 system-state record invalid", badRecord);
            }
        }
        int markerCount = data.readInt();
        if (markerCount < 0) {
            throw new IOException(
                    "world snapshot v11 fragment-marker count negative: " + markerCount);
        }
        int markerRegion = FRAGMENT_MARKER_COUNT_BYTES + FRAGMENT_MARKER_ENTITY_BYTES * markerCount;
        if (nonShipRegion + fluxRegion + weaponRegion + allegianceRegion + engineStateRegion
                + maneuverRegion + systemStateRegion + markerRegion
                + PHASE_CLOAK_COUNT_BYTES > remaining) {
            throw new IOException(
                    "world snapshot v11 fragment-marker count inconsistent with body length: "
                            + markerCount);
        }
        List<ShipFragmentMarkerSnapshot> fragmentMarkers = new ArrayList<>(markerCount);
        for (int i = 0; i < markerCount; i++) {
            try {
                fragmentMarkers.add(ShipFragmentMarkerSnapshot.fromWire(
                        data.readLong(), data.readInt(), data.readInt(), data.readInt()));
            } catch (IllegalArgumentException badRecord) {
                throw new IOException("world snapshot v11 fragment-marker record invalid", badRecord);
            }
        }
        int phaseCloakCount = data.readInt();
        if (phaseCloakCount < 0) {
            // Unlike v10, the cloak section may be EMPTY here (the v11 stamp rides the
            // flux facing, not the cloak tier) — only a negative count is corrupt.
            throw new IOException(
                    "world snapshot v11 phase-cloak count negative: " + phaseCloakCount);
        }
        int phaseCloakRegion = PHASE_CLOAK_COUNT_BYTES + PHASE_CLOAK_ENTITY_BYTES * phaseCloakCount;
        if (nonShipRegion + fluxRegion + weaponRegion + allegianceRegion + engineStateRegion
                + maneuverRegion + systemStateRegion + markerRegion + phaseCloakRegion != remaining) {
            throw new IOException(
                    "world snapshot v11 phase-cloak count inconsistent with body length: "
                            + phaseCloakCount);
        }
        List<ShipPhaseCloakStateSnapshot> phaseCloakStates = new ArrayList<>(phaseCloakCount);
        for (int i = 0; i < phaseCloakCount; i++) {
            try {
                phaseCloakStates.add(ShipPhaseCloakStateSnapshot.fromWire(
                        data.readLong(),
                        data.readInt(),
                        Float.intBitsToFloat(data.readInt()),
                        Float.intBitsToFloat(data.readInt()),
                        data.readInt()));
            } catch (IllegalArgumentException badRecord) {
                throw new IOException("world snapshot v11 phase-cloak record invalid", badRecord);
            }
        }
        if (data.read() != -1) {
            throw new IOException("world snapshot body has trailing bytes");
        }
        // Full-arity construction routes through the Builder (the rule: the
        // one raw all-components call site is Builder.build()).
        return new AuthoritativeWorldSnapshot.Builder()
                .simTick(simTick)
                .entities(entities)
                .nonShipEntities(nonShipEntities)
                .fluxStates(fluxStates)
                .weaponMounts(weaponMounts)
                .allegianceStates(allegianceStates)
                .engineStates(engineStates)
                .maneuverCommands(maneuverCommands)
                .systemStates(systemStates)
                .fragmentMarkers(fragmentMarkers)
                .phaseCloakStates(phaseCloakStates)
                .build();
    }

    /**
     * Decode a schema-v12 body: every v11 section in the same order, with
     * each kind=BEAM non-ship record widened by the source pair
     * ({@link #BEAM_SOURCE_EXTRA_BYTES}) and the flux records at the v11 widened
     * width — a v12 frame FORCES the v11 flux width regardless of facing values, so
     * unlike v11 there is no nonzero-facing requirement here. The fail-closed mirror
     * of the value-based stamp predicate is instead: at least one beam record must
     * carry a resolved source (a source-less frame would have been stamped
     * v11-or-lower by {@link #writeFrame}). Built from the shared parameterized
     * section readers below (v12+ decode paths stop being per-version
     * copies; the frozen v1..v11 paths above are untouched).
     */
    private static AuthoritativeWorldSnapshot decodeBodyV12(
            DataInputStream data,
            int bodyLength,
            int shipRegionEnd,
            long simTick,
            List<AuthoritativeEntitySnapshot> entities) throws IOException {
        SectionCursor cursor = new SectionCursor("v12", bodyLength, shipRegionEnd, 9);
        List<NonShipEntitySnapshot> nonShipEntities =
                readNonShipSectionWithBeamSource(data, cursor, false);
        boolean anyBeamSource = false;
        for (NonShipEntitySnapshot nonShip : nonShipEntities) {
            anyBeamSource |= nonShip.kind() == CombatEntityView.Kind.BEAM
                    && nonShip.sourceEntityId() != NonShipEntitySnapshot.NO_SOURCE;
        }
        if (!anyBeamSource) {
            throw new IOException("world snapshot v12 frame declares no resolved beam source");
        }
        List<ShipFluxSnapshot> fluxStates = readFluxSection(data, cursor, true);
        List<ShipWeaponMountSnapshot> weaponMounts = readWeaponMountSection(data, cursor);
        List<ShipAllegianceSnapshot> allegianceStates = readAllegianceSection(data, cursor);
        List<ShipEngineStateSnapshot> engineStates = readEngineStateSection(data, cursor);
        List<ShipManeuverCommandSnapshot> maneuverCommands = readManeuverCommandSection(data, cursor);
        List<ShipSystemStateSnapshot> systemStates = readSystemStateSection(data, cursor);
        List<ShipFragmentMarkerSnapshot> fragmentMarkers = readFragmentMarkerSection(data, cursor);
        List<ShipPhaseCloakStateSnapshot> phaseCloakStates = readPhaseCloakSection(data, cursor);
        cursor.requireFullyConsumed();
        if (data.read() != -1) {
            throw new IOException("world snapshot body has trailing bytes");
        }
        // Full-arity construction routes through the Builder (the rule: the
        // one raw all-components call site is Builder.build()).
        return new AuthoritativeWorldSnapshot.Builder()
                .simTick(simTick)
                .entities(entities)
                .nonShipEntities(nonShipEntities)
                .fluxStates(fluxStates)
                .weaponMounts(weaponMounts)
                .allegianceStates(allegianceStates)
                .engineStates(engineStates)
                .maneuverCommands(maneuverCommands)
                .systemStates(systemStates)
                .fragmentMarkers(fragmentMarkers)
                .phaseCloakStates(phaseCloakStates)
                .build();
    }

    /**
     * Decode a schema-v13 body: every v12 section in the same order, with
     * each kind=MISSILE non-ship record widened by the source pair
     * ({@link #MISSILE_SOURCE_EXTRA_BYTES}) beside the v12 beam pair (a v13 body
     * forces the beam width — WIDTH LAW) and the flux records at the v11 widened
     * width. The fail-closed mirror of the value-based stamp predicate rides the
     * MISSILE predicate alone: at least one missile record must carry a resolved
     * source (beam sources MAY be all-sentinel in a v13 frame — a frame with only
     * resolved beams would have been stamped v12 by {@link #writeFrame}).
     */
    private static AuthoritativeWorldSnapshot decodeBodyV13(
            DataInputStream data,
            int bodyLength,
            int shipRegionEnd,
            long simTick,
            List<AuthoritativeEntitySnapshot> entities) throws IOException {
        SectionCursor cursor = new SectionCursor("v13", bodyLength, shipRegionEnd, 9);
        List<NonShipEntitySnapshot> nonShipEntities =
                readNonShipSectionWithBeamSource(data, cursor, true);
        boolean anyMissileSource = false;
        for (NonShipEntitySnapshot nonShip : nonShipEntities) {
            anyMissileSource |= nonShip.kind() == CombatEntityView.Kind.MISSILE
                    && nonShip.sourceEntityId() != NonShipEntitySnapshot.NO_SOURCE;
        }
        if (!anyMissileSource) {
            throw new IOException("world snapshot v13 frame declares no resolved missile source");
        }
        List<ShipFluxSnapshot> fluxStates = readFluxSection(data, cursor, true);
        List<ShipWeaponMountSnapshot> weaponMounts = readWeaponMountSection(data, cursor);
        List<ShipAllegianceSnapshot> allegianceStates = readAllegianceSection(data, cursor);
        List<ShipEngineStateSnapshot> engineStates = readEngineStateSection(data, cursor);
        List<ShipManeuverCommandSnapshot> maneuverCommands = readManeuverCommandSection(data, cursor);
        List<ShipSystemStateSnapshot> systemStates = readSystemStateSection(data, cursor);
        List<ShipFragmentMarkerSnapshot> fragmentMarkers = readFragmentMarkerSection(data, cursor);
        List<ShipPhaseCloakStateSnapshot> phaseCloakStates = readPhaseCloakSection(data, cursor);
        cursor.requireFullyConsumed();
        if (data.read() != -1) {
            throw new IOException("world snapshot body has trailing bytes");
        }
        // Full-arity construction routes through the Builder (the rule: the
        // one raw all-components call site is Builder.build()).
        return new AuthoritativeWorldSnapshot.Builder()
                .simTick(simTick)
                .entities(entities)
                .nonShipEntities(nonShipEntities)
                .fluxStates(fluxStates)
                .weaponMounts(weaponMounts)
                .allegianceStates(allegianceStates)
                .engineStates(engineStates)
                .maneuverCommands(maneuverCommands)
                .systemStates(systemStates)
                .fragmentMarkers(fragmentMarkers)
                .phaseCloakStates(phaseCloakStates)
                .build();
    }

    /**
     * Decode a schema-v14 body: every v13 section in the same order and at
     * the same widths (missile+beam source pairs, widened flux — a v14 body forces them
     * all, WIDTH LAW), followed by the declared ship-CR tail section appended STRICTLY
     * AFTER the phase-cloak section. The fail-closed mirror of the count-based stamp
     * predicate rides the CR section alone: an exactly-v14 frame must carry at least
     * one CR record (missile/beam sources MAY be all-sentinel in a v14 frame — a frame
     * with resolved sources but no CR records would have been stamped ≤v13 by
     * {@link #writeFrame}).
     */
    private static AuthoritativeWorldSnapshot decodeBodyV14(
            DataInputStream data,
            int bodyLength,
            int shipRegionEnd,
            long simTick,
            List<AuthoritativeEntitySnapshot> entities) throws IOException {
        SectionCursor cursor = new SectionCursor("v14", bodyLength, shipRegionEnd, 10);
        List<NonShipEntitySnapshot> nonShipEntities =
                readNonShipSectionWithBeamSource(data, cursor, true);
        List<ShipFluxSnapshot> fluxStates = readFluxSection(data, cursor, true);
        List<ShipWeaponMountSnapshot> weaponMounts = readWeaponMountSection(data, cursor);
        List<ShipAllegianceSnapshot> allegianceStates = readAllegianceSection(data, cursor);
        List<ShipEngineStateSnapshot> engineStates = readEngineStateSection(data, cursor);
        List<ShipManeuverCommandSnapshot> maneuverCommands = readManeuverCommandSection(data, cursor);
        List<ShipSystemStateSnapshot> systemStates = readSystemStateSection(data, cursor);
        List<ShipFragmentMarkerSnapshot> fragmentMarkers = readFragmentMarkerSection(data, cursor);
        List<ShipPhaseCloakStateSnapshot> phaseCloakStates = readPhaseCloakSection(data, cursor);
        List<ShipCrSnapshot> crStates = readCrSection(data, cursor);
        if (crStates.isEmpty()) {
            throw new IOException("world snapshot v14 frame declares no ship-CR record");
        }
        cursor.requireFullyConsumed();
        if (data.read() != -1) {
            throw new IOException("world snapshot body has trailing bytes");
        }
        // Full-arity construction routes through the Builder (the rule: the
        // one raw all-components call site is Builder.build()).
        return new AuthoritativeWorldSnapshot.Builder()
                .simTick(simTick)
                .entities(entities)
                .nonShipEntities(nonShipEntities)
                .fluxStates(fluxStates)
                .weaponMounts(weaponMounts)
                .allegianceStates(allegianceStates)
                .engineStates(engineStates)
                .maneuverCommands(maneuverCommands)
                .systemStates(systemStates)
                .fragmentMarkers(fragmentMarkers)
                .phaseCloakStates(phaseCloakStates)
                .crStates(crStates)
                .build();
    }

    /**
     * Decode a schema-v15 body: every v14 section in the same order and at
     * the same widths, followed by the declared content-dictionary tail section appended
     * STRICTLY AFTER the ship-CR section. The fail-closed mirror of the count-based
     * stamp predicate rides the dictionary section alone: an exactly-v15 frame must
     * carry at least one dictionary entry, while the CR section MAY be empty (a frame
     * with CR records but no dictionary entries would have been stamped v14 by
     * {@link #writeFrame}).
     */
    private static AuthoritativeWorldSnapshot decodeBodyV15(
            DataInputStream data,
            int bodyLength,
            int shipRegionEnd,
            long simTick,
            List<AuthoritativeEntitySnapshot> entities) throws IOException {
        SectionCursor cursor = new SectionCursor("v15", bodyLength, shipRegionEnd, 11);
        List<NonShipEntitySnapshot> nonShipEntities =
                readNonShipSectionWithBeamSource(data, cursor, true);
        List<ShipFluxSnapshot> fluxStates = readFluxSection(data, cursor, true);
        List<ShipWeaponMountSnapshot> weaponMounts = readWeaponMountSection(data, cursor);
        List<ShipAllegianceSnapshot> allegianceStates = readAllegianceSection(data, cursor);
        List<ShipEngineStateSnapshot> engineStates = readEngineStateSection(data, cursor);
        List<ShipManeuverCommandSnapshot> maneuverCommands = readManeuverCommandSection(data, cursor);
        List<ShipSystemStateSnapshot> systemStates = readSystemStateSection(data, cursor);
        List<ShipFragmentMarkerSnapshot> fragmentMarkers = readFragmentMarkerSection(data, cursor);
        List<ShipPhaseCloakStateSnapshot> phaseCloakStates = readPhaseCloakSection(data, cursor);
        List<ShipCrSnapshot> crStates = readCrSection(data, cursor);
        List<ContentDictionaryEntry> contentDictionary =
                readContentDictionarySection(data, cursor);
        if (contentDictionary.isEmpty()) {
            throw new IOException(
                    "world snapshot v15 frame declares no content-dictionary entry");
        }
        cursor.requireFullyConsumed();
        if (data.read() != -1) {
            throw new IOException("world snapshot body has trailing bytes");
        }
        // Full-arity construction routes through the Builder (the rule: the
        // one raw all-components call site is Builder.build()).
        return new AuthoritativeWorldSnapshot.Builder()
                .simTick(simTick)
                .entities(entities)
                .nonShipEntities(nonShipEntities)
                .fluxStates(fluxStates)
                .weaponMounts(weaponMounts)
                .allegianceStates(allegianceStates)
                .engineStates(engineStates)
                .maneuverCommands(maneuverCommands)
                .systemStates(systemStates)
                .fragmentMarkers(fragmentMarkers)
                .phaseCloakStates(phaseCloakStates)
                .crStates(crStates)
                .contentDictionary(contentDictionary)
                .build();
    }

    /**
     * Decode a schema-v16 body: every v15 section in the same order,
     * with the missile-pose widening on EVERY kind=MISSILE non-ship record (facingU16
     * + poseFlags after the source pair). The fail-closed mirror of the value-based
     * stamp predicate rides the pose values alone: an exactly-v16 frame must carry at
     * least one missile record with nonzero poseFlags, while the content-dictionary
     * AND ship-CR sections both MAY be empty (a frame with dictionary entries but no
     * minted pose would have been stamped v15 by {@link #writeFrame} — the ≥1-entry
     * dictionary check stays exactly-v15's own).
     */
    private static AuthoritativeWorldSnapshot decodeBodyV16(
            DataInputStream data,
            int bodyLength,
            int shipRegionEnd,
            long simTick,
            List<AuthoritativeEntitySnapshot> entities) throws IOException {
        SectionCursor cursor = new SectionCursor("v16", bodyLength, shipRegionEnd, 11);
        List<NonShipEntitySnapshot> nonShipEntities =
                readNonShipSectionWithBeamSource(data, cursor, true, true);
        boolean anyMissilePose = false;
        for (NonShipEntitySnapshot nonShip : nonShipEntities) {
            anyMissilePose |= nonShip.kind() == CombatEntityView.Kind.MISSILE
                    && nonShip.poseFlags() != 0;
        }
        if (!anyMissilePose) {
            throw new IOException("world snapshot v16 frame declares no missile pose");
        }
        List<ShipFluxSnapshot> fluxStates = readFluxSection(data, cursor, true);
        List<ShipWeaponMountSnapshot> weaponMounts = readWeaponMountSection(data, cursor);
        List<ShipAllegianceSnapshot> allegianceStates = readAllegianceSection(data, cursor);
        List<ShipEngineStateSnapshot> engineStates = readEngineStateSection(data, cursor);
        List<ShipManeuverCommandSnapshot> maneuverCommands = readManeuverCommandSection(data, cursor);
        List<ShipSystemStateSnapshot> systemStates = readSystemStateSection(data, cursor);
        List<ShipFragmentMarkerSnapshot> fragmentMarkers = readFragmentMarkerSection(data, cursor);
        List<ShipPhaseCloakStateSnapshot> phaseCloakStates = readPhaseCloakSection(data, cursor);
        List<ShipCrSnapshot> crStates = readCrSection(data, cursor);
        List<ContentDictionaryEntry> contentDictionary =
                readContentDictionarySection(data, cursor);
        cursor.requireFullyConsumed();
        if (data.read() != -1) {
            throw new IOException("world snapshot body has trailing bytes");
        }
        // Full-arity construction routes through the Builder (the rule: the
        // one raw all-components call site is Builder.build()).
        return new AuthoritativeWorldSnapshot.Builder()
                .simTick(simTick)
                .entities(entities)
                .nonShipEntities(nonShipEntities)
                .fluxStates(fluxStates)
                .weaponMounts(weaponMounts)
                .allegianceStates(allegianceStates)
                .engineStates(engineStates)
                .maneuverCommands(maneuverCommands)
                .systemStates(systemStates)
                .fragmentMarkers(fragmentMarkers)
                .phaseCloakStates(phaseCloakStates)
                .crStates(crStates)
                .contentDictionary(contentDictionary)
                .build();
    }

    /**
     * Byte-accounting cursor shared by the v12+ section readers.
     * Reproduces the frozen decoders' cumulative-region validation generically:
     * each section's declared count is checked against the bytes left after the
     * ship records minus one reserved count field per still-unread section, so a
     * corrupt count fails closed before its records are read — including
     * variable-width extras accounted via {@link #consume} mid-record.
     */
    private static final class SectionCursor {
        private final String frameLabel;
        private final int remaining;
        private int used;
        private int reservedTailCountBytes;

        SectionCursor(String frameLabel, int bodyLength, int shipRegionEnd, int sectionCount)
                throws IOException {
            this.frameLabel = frameLabel;
            this.remaining = bodyLength - shipRegionEnd;
            this.reservedTailCountBytes = sectionCount * Integer.BYTES;
            if (remaining < reservedTailCountBytes) {
                throw new IOException("world snapshot " + frameLabel + " frame truncated: "
                        + remaining + " trailing bytes after ship records");
            }
        }

        /** Read one section's count and reserve {@code recordBytes} per record. */
        int readCount(DataInputStream data, String section, int recordBytes) throws IOException {
            int count = data.readInt();
            used += Integer.BYTES;
            reservedTailCountBytes -= Integer.BYTES;
            if (count < 0) {
                throw new IOException("world snapshot " + frameLabel + " " + section
                        + " count negative: " + count);
            }
            requireRoom(recordBytes * count, section, count);
            return count;
        }

        /** Account variable-width extra bytes within the current section's records. */
        void requireRoom(int bytes, String section, int countForMessage) throws IOException {
            used += bytes;
            if (used + reservedTailCountBytes > remaining) {
                throw new IOException("world snapshot " + frameLabel + " " + section
                        + " count inconsistent with body length: " + countForMessage);
            }
        }

        /** Exact-fit check after the final section (the frozen decoders' {@code != remaining}). */
        void requireFullyConsumed() throws IOException {
            if (used != remaining) {
                throw new IOException("world snapshot " + frameLabel
                        + " sections inconsistent with body length: consumed " + used
                        + " of " + remaining + " bytes after ship records");
            }
        }
    }

    /**
     * Shared v12+ non-ship section reader: the v2 fixed-width record, plus — for
     * kind=BEAM records — the {@link #BEAM_SOURCE_EXTRA_BYTES} source pair, and —
     * when {@code missileSourceWidened} (a v13 body) — the
     * {@link #MISSILE_SOURCE_EXTRA_BYTES} pair on kind=MISSILE records too (width
     * keyed by kind; sentinels 0/−1 when unresolved; the {@code widened}-flag
     * parameterization follows the {@code readFluxSection} precedent so the v12
     * caller's bytes stay frozen).
     */
    private static List<NonShipEntitySnapshot> readNonShipSectionWithBeamSource(
            DataInputStream data, SectionCursor cursor, boolean missileSourceWidened)
            throws IOException {
        return readNonShipSectionWithBeamSource(data, cursor, missileSourceWidened, false);
    }

    /**
     * The v16 overload: when {@code missilePoseWidened} each kind=MISSILE record
     * additionally carries the {@link #MISSILE_POSE_EXTRA_BYTES} facingU16+poseFlags
     * pair after its source pair. Unknown spare flag bits are MASKED
     * off, never rejected (so future bits need no crash window), and a masked-to-zero
     * flags byte defensively forces facing to 0 (the sentinel invariant the record
     * validation enforces).
     */
    private static List<NonShipEntitySnapshot> readNonShipSectionWithBeamSource(
            DataInputStream data, SectionCursor cursor, boolean missileSourceWidened,
            boolean missilePoseWidened)
            throws IOException {
        int count = cursor.readCount(data, "non-ship", NON_SHIP_ENTITY_BYTES);
        List<NonShipEntitySnapshot> nonShipEntities = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long networkId = data.readLong();
            CombatEntityView.Kind kind = NonShipEntitySnapshot.kindFromWireCode(data.readInt());
            int owner = data.readInt();
            float posX = Float.intBitsToFloat(data.readInt());
            float posY = Float.intBitsToFloat(data.readInt());
            int contentId = data.readInt();
            long sourceEntityId = NonShipEntitySnapshot.NO_SOURCE;
            int sourceMountIndex = NonShipEntitySnapshot.NO_SOURCE_MOUNT;
            int poseFacingU16 = 0;
            int poseFlags = 0;
            if (kind == CombatEntityView.Kind.BEAM) {
                cursor.requireRoom(BEAM_SOURCE_EXTRA_BYTES, "non-ship beam-source", count);
                sourceEntityId = data.readLong();
                sourceMountIndex = data.readInt();
            } else if (missileSourceWidened && kind == CombatEntityView.Kind.MISSILE) {
                cursor.requireRoom(MISSILE_SOURCE_EXTRA_BYTES, "non-ship missile-source", count);
                sourceEntityId = data.readLong();
                sourceMountIndex = data.readInt();
                if (missilePoseWidened) {
                    cursor.requireRoom(MISSILE_POSE_EXTRA_BYTES, "non-ship missile-pose", count);
                    poseFacingU16 = data.readUnsignedShort();
                    poseFlags = data.readUnsignedByte() & NonShipEntitySnapshot.POSE_FLAGS_MASK;
                    if (poseFlags == 0) {
                        poseFacingU16 = 0;
                    }
                }
            }
            try {
                nonShipEntities.add(new NonShipEntitySnapshot(
                        networkId, kind, owner, posX, posY, contentId,
                        sourceEntityId, sourceMountIndex, poseFacingU16, poseFlags));
            } catch (IllegalArgumentException badRecord) {
                throw new IOException("world snapshot non-ship record invalid", badRecord);
            }
        }
        return nonShipEntities;
    }

    /** Shared v12+ flux section reader; {@code widened} selects the v11 29-byte record. */
    private static List<ShipFluxSnapshot> readFluxSection(
            DataInputStream data, SectionCursor cursor, boolean widened) throws IOException {
        int count = cursor.readCount(
                data, "flux", widened ? FLUX_ENTITY_BYTES_V11 : FLUX_ENTITY_BYTES);
        List<ShipFluxSnapshot> fluxStates = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long entityId = data.readLong();
            float currFlux = Float.intBitsToFloat(data.readInt());
            float maxFlux = Float.intBitsToFloat(data.readInt());
            float fluxLevel = Float.intBitsToFloat(data.readInt());
            float hardFlux = Float.intBitsToFloat(data.readInt());
            int shieldFacingU8 = widened ? data.readUnsignedByte() : 0;
            int statusFlags = data.readInt();
            fluxStates.add(ShipFluxSnapshot.fromWire(
                    entityId, currFlux, maxFlux, fluxLevel, hardFlux, statusFlags, shieldFacingU8));
        }
        return fluxStates;
    }

    /** Shared v12+ weapon-mount section reader (the v4 record layout). */
    private static List<ShipWeaponMountSnapshot> readWeaponMountSection(
            DataInputStream data, SectionCursor cursor) throws IOException {
        int count = cursor.readCount(data, "weapon-mount", WEAPON_MOUNT_ENTITY_BYTES);
        List<ShipWeaponMountSnapshot> weaponMounts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            weaponMounts.add(ShipWeaponMountSnapshot.fromWire(
                    data.readLong(),
                    data.readInt(),
                    data.readInt(),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt(),
                    data.readInt(),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt()));
        }
        return weaponMounts;
    }

    /** Shared v12+ allegiance section reader (the v5 record layout). */
    private static List<ShipAllegianceSnapshot> readAllegianceSection(
            DataInputStream data, SectionCursor cursor) throws IOException {
        int count = cursor.readCount(data, "allegiance", ALLEGIANCE_ENTITY_BYTES);
        List<ShipAllegianceSnapshot> allegianceStates = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            allegianceStates.add(new ShipAllegianceSnapshot(data.readLong(), data.readInt()));
        }
        return allegianceStates;
    }

    /** Shared v12+ engine-state section reader (the v6 record layout). */
    private static List<ShipEngineStateSnapshot> readEngineStateSection(
            DataInputStream data, SectionCursor cursor) throws IOException {
        int count = cursor.readCount(data, "engine-state", ENGINE_STATE_ENTITY_BYTES);
        List<ShipEngineStateSnapshot> engineStates = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            try {
                engineStates.add(ShipEngineStateSnapshot.fromWire(
                        data.readLong(), data.readInt(), data.readInt(), data.readInt()));
            } catch (IllegalArgumentException badRecord) {
                throw new IOException("world snapshot engine-state record invalid", badRecord);
            }
        }
        return engineStates;
    }

    /** Shared v12+ maneuver-command section reader (the v7 record layout). */
    private static List<ShipManeuverCommandSnapshot> readManeuverCommandSection(
            DataInputStream data, SectionCursor cursor) throws IOException {
        int count = cursor.readCount(data, "maneuver-command", MANEUVER_COMMAND_ENTITY_BYTES);
        List<ShipManeuverCommandSnapshot> maneuverCommands = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            try {
                maneuverCommands.add(ShipManeuverCommandSnapshot.fromWire(
                        data.readLong(), data.readInt()));
            } catch (IllegalArgumentException badRecord) {
                throw new IOException("world snapshot maneuver-command record invalid", badRecord);
            }
        }
        return maneuverCommands;
    }

    /** Shared v12+ system-state section reader (the v8 record layout). */
    private static List<ShipSystemStateSnapshot> readSystemStateSection(
            DataInputStream data, SectionCursor cursor) throws IOException {
        int count = cursor.readCount(data, "system-state", SYSTEM_STATE_ENTITY_BYTES);
        List<ShipSystemStateSnapshot> systemStates = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            try {
                systemStates.add(ShipSystemStateSnapshot.fromWire(
                        data.readLong(),
                        data.readInt(),
                        Float.intBitsToFloat(data.readInt()),
                        Float.intBitsToFloat(data.readInt()),
                        data.readInt()));
            } catch (IllegalArgumentException badRecord) {
                throw new IOException("world snapshot system-state record invalid", badRecord);
            }
        }
        return systemStates;
    }

    /** Shared v12+ fragment-marker section reader (the v9 record layout). */
    private static List<ShipFragmentMarkerSnapshot> readFragmentMarkerSection(
            DataInputStream data, SectionCursor cursor) throws IOException {
        int count = cursor.readCount(data, "fragment-marker", FRAGMENT_MARKER_ENTITY_BYTES);
        List<ShipFragmentMarkerSnapshot> fragmentMarkers = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            try {
                fragmentMarkers.add(ShipFragmentMarkerSnapshot.fromWire(
                        data.readLong(), data.readInt(), data.readInt(), data.readInt()));
            } catch (IllegalArgumentException badRecord) {
                throw new IOException("world snapshot fragment-marker record invalid", badRecord);
            }
        }
        return fragmentMarkers;
    }

    /** Shared v12+ phase-cloak section reader (the v10 record layout; may be empty). */
    private static List<ShipPhaseCloakStateSnapshot> readPhaseCloakSection(
            DataInputStream data, SectionCursor cursor) throws IOException {
        int count = cursor.readCount(data, "phase-cloak", PHASE_CLOAK_ENTITY_BYTES);
        List<ShipPhaseCloakStateSnapshot> phaseCloakStates = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            try {
                phaseCloakStates.add(ShipPhaseCloakStateSnapshot.fromWire(
                        data.readLong(),
                        data.readInt(),
                        Float.intBitsToFloat(data.readInt()),
                        Float.intBitsToFloat(data.readInt()),
                        data.readInt()));
            } catch (IllegalArgumentException badRecord) {
                throw new IOException("world snapshot phase-cloak record invalid", badRecord);
            }
        }
        return phaseCloakStates;
    }

    /** Shared v14+ ship-CR section reader (the 20-byte record layout). */
    private static List<ShipCrSnapshot> readCrSection(
            DataInputStream data, SectionCursor cursor) throws IOException {
        int count = cursor.readCount(data, "ship-cr", CR_ENTITY_BYTES);
        List<ShipCrSnapshot> crStates = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            try {
                crStates.add(ShipCrSnapshot.fromWire(
                        data.readLong(),
                        Float.intBitsToFloat(data.readInt()),
                        Float.intBitsToFloat(data.readInt()),
                        Float.intBitsToFloat(data.readInt())));
            } catch (IllegalArgumentException badRecord) {
                throw new IOException("world snapshot ship-cr record invalid", badRecord);
            }
        }
        return crStates;
    }

    /**
     * Shared v15+ content-dictionary section reader: count, then one
     * variable-width record per entry — contentId, a one-byte UTF-8 key length
     * (1..255, zero rejected), then the key bytes accounted via
     * {@link SectionCursor#requireRoom} so a corrupt length fails closed before
     * the bytes are read (the {@code readNonShipSectionWithBeamSource}
     * variable-width precedent).
     */
    private static List<ContentDictionaryEntry> readContentDictionarySection(
            DataInputStream data, SectionCursor cursor) throws IOException {
        int count = cursor.readCount(
                data, "content-dict", CONTENT_DICTIONARY_ENTRY_PREFIX_BYTES);
        List<ContentDictionaryEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int contentId = data.readInt();
            int keyLength = data.readUnsignedByte();
            if (keyLength == 0) {
                throw new IOException("world snapshot content-dict key length zero");
            }
            cursor.requireRoom(keyLength, "content-dict key", count);
            byte[] keyUtf8 = new byte[keyLength];
            data.readFully(keyUtf8);
            try {
                entries.add(new ContentDictionaryEntry(
                        contentId, new String(keyUtf8, StandardCharsets.UTF_8)));
            } catch (IllegalArgumentException badRecord) {
                throw new IOException("world snapshot content-dict record invalid", badRecord);
            }
        }
        return entries;
    }

    /**
     * Decode the non-ship entity section that follows the ship records.
     *
     * <p>Schema v2 ({@code schemaDeclaresNonShipTier}) declares the section as part
     * of the frame contract: the remaining bytes must be a count followed by exactly
     * that many fixed-width records, with no slack and none missing. Schema v1 keeps
     * the original self-describing, length-driven affordance: a body ending exactly
     * at the ship records is a legacy ship-only frame (zero non-ship entities), and a
     * body with remaining bytes carries the transitional non-ship section — so every
     * committed pre-v2 capture (ship-only <em>and</em> the v1-stamped beam frames)
     * still decodes unchanged.
     */
    private static List<NonShipEntitySnapshot> decodeNonShipSection(
            DataInputStream data, int bodyLength, int shipRegionEnd, boolean schemaDeclaresNonShipTier)
            throws IOException {
        int remaining = bodyLength - shipRegionEnd;
        if (remaining == 0) {
            if (schemaDeclaresNonShipTier) {
                throw new IOException("world snapshot v2 frame is missing its declared non-ship section");
            }
            return List.of();
        }
        if (remaining < NON_SHIP_COUNT_BYTES) {
            throw new IOException("world snapshot non-ship section truncated: " + remaining + " trailing bytes");
        }
        int nonShipCount = data.readInt();
        int expectedNonShipRegion = NON_SHIP_COUNT_BYTES + NON_SHIP_ENTITY_BYTES * nonShipCount;
        if (nonShipCount < 0 || expectedNonShipRegion != remaining) {
            throw new IOException(
                    "world snapshot non-ship count inconsistent with body length: " + nonShipCount);
        }
        List<NonShipEntitySnapshot> nonShipEntities = new ArrayList<>(nonShipCount);
        for (int i = 0; i < nonShipCount; i++) {
            nonShipEntities.add(new NonShipEntitySnapshot(
                    data.readLong(),
                    NonShipEntitySnapshot.kindFromWireCode(data.readInt()),
                    data.readInt(),
                    Float.intBitsToFloat(data.readInt()),
                    Float.intBitsToFloat(data.readInt()),
                    data.readInt()));
        }
        return nonShipEntities;
    }

    private static long checksum(byte[] body) {
        CRC32 crc = new CRC32();
        crc.update(body, 0, body.length);
        return crc.getValue();
    }

    private static void requireRegisteredSchema(short schemaVersion) throws IOException {
        int unsignedSchemaVersion = Short.toUnsignedInt(schemaVersion);
        ProtocolRegistryEntry entry;
        try {
            entry = NetworkMessageRegistry.authoritativeCombat().require(MESSAGE_ID, unsignedSchemaVersion);
        } catch (IllegalArgumentException badSchema) {
            throw new IOException("unsupported world snapshot frame schema: " + unsignedSchemaVersion, badSchema);
        }
        if (!entry.compatibilityRule().accepts(unsignedSchemaVersion, true)) {
            throw new IOException("world snapshot frame schema rejected by compatibility policy: "
                    + unsignedSchemaVersion);
        }
    }

    private static void requireRegisteredWireIdentity(int messageTypeCode, short schemaVersion)
            throws IOException {
        int unsignedSchemaVersion = Short.toUnsignedInt(schemaVersion);
        ProtocolRegistryEntry entry;
        try {
            entry = NetworkMessageRegistry.authoritativeCombat().requireWireType(
                    messageTypeCode,
                    unsignedSchemaVersion);
        } catch (IllegalArgumentException badIdentity) {
            throw new IOException(
                    "unsupported world snapshot frame message identity: "
                            + Integer.toUnsignedString(messageTypeCode)
                            + " v" + unsignedSchemaVersion,
                    badIdentity);
        }
        if (!MESSAGE_ID.equals(entry.messageId())) {
            throw new IOException("world snapshot frame message id mismatch: " + entry.messageId());
        }
    }

    private static short checkedSchemaVersion(ProtocolRegistryEntry registryEntry) {
        int schemaVersion = registryEntry.schemaVersion();
        if (schemaVersion > Short.MAX_VALUE) {
            throw new IllegalArgumentException("wire schema version exceeds signed short range: " + schemaVersion);
        }
        return (short) schemaVersion;
    }

    private static int readIntAfterFirstByte(DataInputStream input, int first) throws IOException {
        try {
            return ((first & 0xff) << 24)
                    | (input.readUnsignedByte() << 16)
                    | (input.readUnsignedByte() << 8)
                    | input.readUnsignedByte();
        } catch (EOFException eof) {
            throw new EOFException("truncated world snapshot frame header");
        }
    }
}
