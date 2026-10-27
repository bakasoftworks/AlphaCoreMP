package cc.starsector.client.net;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.CRC32;

import cc.starsector.client.FallbackAnnounce;
import cc.starsector.client.PilotWatchLog;
import cc.starsector.server.runtime.WireTextLaw;

/**
 * Client-side decoder for the server's {@code AWSS} world-snapshot wire frame:
 * the receiving half of the project's
 * {@code AuthoritativeWorldSnapshotCodec}.
 *
 * <p>The mod is a Starsector combat mod; bundling the server's full codec (and
 * its transitive {@code NetworkMessageRegistry} / protocol-record graph) into the
 * mod jar that loads inside the game JVM would be heavy and brittle. Instead this
 * is a deliberately small, self-contained reader of exactly the bytes the server
 * publishes: it validates the frame header (magic / header size) and the CRC32,
 * then decodes the ship-record region into {@link ServerEntityPose} records. The
 * trailing non-ship projectile records the puppet does not need are decoded
 * past (below schema v12 the whole tier is <em>skipped</em>; at v12+ it must be walked
 * record-by-record because beam/missile records are variable-width, and kind=BEAM records
 * are retained as the frame's {@link ServerSnapshotFrame#beams()} with
 * kind=MISSILE records as its {@link ServerSnapshotFrame#missiles()});
 * the schema-v4 per-mount weapon tier is decoded and attached as each pose's
 * {@link ServerEntityPose#weaponMounts()} (the {@code cc.predict.weaponState} reconcile
 * lane); the schema-v3 per-ship flux tier, which the speed-divergence
 * debug DOES read (to show the server-authoritative flux beside the local engine's),
 * is decoded and attached to each pose as its {@link ServerEntityPose#fluxLevel()};
 * the schema-v5 combat-side allegiance tier (friend/foe), which the puppet DOES render, is
 * attached as its {@link ServerEntityPose#fleetSide()}. Because the whole body
 * length is read up front, the trailing tiers (and any future schema bump that only
 * appends sections after allegiance) are tolerated without rejecting the frame.
 *
 * <p>This duplicates the wire <em>layout</em> the server owns, so it is kept in
 * sync by a game-free cross-check that
 * encodes real snapshots with the actual server
 * {@code AuthoritativeWorldSnapshotCodec}, streams the bytes over a real loopback
 * socket, and asserts this reader decodes the same poses. Any drift in the server
 * codec breaks that verifier.
 *
 * <p>Fail-closed: a magic / header-size / CRC / body-length mismatch throws
 * {@link IOException}; a clean end-of-stream at a frame boundary returns
 * {@code null} (or stops {@link #readAll}).
 */
public final class AwssSnapshotReader {

    /** Frame magic: ASCII "AWSS". Mirrors {@code AuthoritativeWorldSnapshotCodec.MAGIC}. */
    public static final int MAGIC = 0x41575353;
    /** Header bytes: magic(4) + schema(2) + headerBytes(2) + wireType(4) + bodyBytes(4). */
    public static final int HEADER_BYTES = 4 + 2 + 2 + 4 + 4;
    /** Body prefix preceding the ship records: simTick(8) + entityCount(4). */
    public static final int BODY_PREFIX_BYTES = 8 + 4;
    /**
     * Bounded allocation:
     * every AWSS frame decodes from ONE datagram's raw payload (≤1200 B on the wire,
     * ≤{@code SnapDeflateCodec.MAX_RAW_BYTES} after the CAWZ inflate-bomb guard; frag-v2 splits
     * bigger worlds into per-datagram frames the merger reassembles AFTER decode), so a declared
     * body past that bound is forged or corrupt — refused BEFORE the array is sized.
     */
    public static final int MAX_BODY_BYTES =
            cc.starsector.server.runtime.SnapDeflateCodec.MAX_RAW_BYTES;
    /**
     * Bytes per ship record. Mirrors {@code AuthoritativeWorldSnapshotCodec.ENTITY_BYTES}:
     * entityId(8) + contentId(4) + spawnTick(8) + ownerConnectionId(4) + locX/locY/velX/velY(16)
     * + facing/angularVelocity(8) + hitpoints/hullLevel(8) + armorCellCount(4) + armorTotal(4)
     * + armorGridHash(8) = 72.
     */
    public static final int ENTITY_BYTES = 8 + 4 + 8 + 4 + 16 + 8 + 8 + 4 + 4 + 8;
    /** Bytes for each trailing-section count field. Mirrors the codec's {@code *_COUNT_BYTES}. */
    public static final int SECTION_COUNT_BYTES = 4;
    /** Bytes per non-ship record: networkId(8) + kind(4) + owner(4) + posX/posY(8) + contentId(4). */
    public static final int NON_SHIP_ENTITY_BYTES = 8 + 4 + 4 + 4 + 4 + 4;
    /**
     * Extra bytes on each kind=BEAM non-ship record at schema v12+:
     * sourceEntityId(8) + sourceMountIndex(4). Width is keyed by KIND, never per-record by
     * value — in a v12 frame EVERY beam record carries the pair (sentinels 0/−1 when
     * unresolved). Mirrors the codec's {@code BEAM_SOURCE_EXTRA_BYTES}.
     */
    public static final int BEAM_SOURCE_EXTRA_BYTES = 8 + 4;
    /**
     * Extra bytes on each kind=MISSILE non-ship record at schema v13+:
     * sourceEntityId(8) + sourceMountIndex(4). Width is keyed by KIND + schema, never
     * per-record by value — in a v13 frame EVERY missile record carries the pair
     * (sentinels 0/−1 when unresolved); a v12 frame's missile records carry no pair.
     * Mirrors the codec's {@code MISSILE_SOURCE_EXTRA_BYTES}.
     */
    public static final int MISSILE_SOURCE_EXTRA_BYTES = 8 + 4;
    /**
     * Extra bytes on each kind=MISSILE non-ship record at schema v16+:
     * facingU16(2) + poseFlags(1), AFTER the v13 source pair. Width is keyed by KIND +
     * schema, never per-record by value — in a v16 frame EVERY missile record carries
     * the pose pair (the (0, 0) sentinel when the server could not read it whole).
     * Mirrors the codec's {@code MISSILE_POSE_EXTRA_BYTES}.
     */
    public static final int MISSILE_POSE_EXTRA_BYTES = 2 + 1;
    /** Wire code of a kind=PROJECTILE non-ship record (the server enum ordinal: PROJECTILE 0,
     * MISSILE 1, BEAM 2). Mirrors {@code NonShipEntitySnapshot.kindWireCode()}. */
    public static final int NON_SHIP_KIND_PROJECTILE = 0;
    /** Wire code of a kind=MISSILE non-ship record (the server enum ordinal: PROJECTILE 0,
     * MISSILE 1, BEAM 2). Mirrors {@code NonShipEntitySnapshot.kindWireCode()}. */
    public static final int NON_SHIP_KIND_MISSILE = 1;
    /** Wire code of a kind=BEAM non-ship record (the server enum ordinal: PROJECTILE 0,
     * MISSILE 1, BEAM 2). Mirrors {@code NonShipEntitySnapshot.kindWireCode()}. */
    public static final int NON_SHIP_KIND_BEAM = 2;
    /** Highest legal non-ship kind wire code (fail-closed mirror of the server's
     * {@code kindFromWireCode} range check). */
    public static final int NON_SHIP_KIND_MAX = 2;

    // The PROJECTILE decoded-past census (single-writer on the net
    // thread; volatile for the render-thread debrief reads, the smoke/debrief idiom).
    private static volatile int projectileRowsDecodedPast;
    private static volatile int projectileContent0RowsDecodedPast;

    /** PROJECTILE NSE rows decoded past (never retained/presented) — the census. */
    public static int projectileRowsDecodedPast() {
        return projectileRowsDecodedPast;
    }

    /** The content-0 subset of {@link #projectileRowsDecodedPast()} — the script-det class
     *  (script-spawned explosions carry no firing weapon ⇒ empty contentKey). */
    public static int projectileContent0RowsDecodedPast() {
        return projectileContent0RowsDecodedPast;
    }
    /**
     * Bytes per flux record through schema v10: entityId(8) + currFlux/maxFlux/fluxLevel/
     * hardFlux(16) + statusFlags(4). Schema v11 widens the record by one byte —
     * the quantized shield-facing u8 sits BETWEEN hardFlux and statusFlags in-record, so the
     * flux walk is width-aware on {@link #SHIELD_FACING_SCHEMA_VERSION}, not a new tail tier.
     */
    public static final int FLUX_ENTITY_BYTES = 8 + 4 + 4 + 4 + 4 + 4;
    /**
     * Bytes per weapon-mount record: entityId(8) + mountIndex(4) + weaponContentId(4)
     * + cooldownRemaining/cooldown(8) + ammo/maxAmmo(8) + chargeLevel(4) + statusFlags(4).
     */
    public static final int WEAPON_MOUNT_ENTITY_BYTES = 8 + 4 + 4 + 4 + 4 + 4 + 4 + 4 + 4;
    /** Bytes per combat-side allegiance record: entityId(8) + fleetSide(4). Mirrors the codec. */
    public static final int ALLEGIANCE_ENTITY_BYTES = 8 + 4;
    /**
     * Bytes per engine-state record: entityId(8) + disabledMask(4) + permDisabledMask(4)
     * + controllerState(4). Mirrors the codec's {@code ENGINE_STATE_ENTITY_BYTES}.
     */
    public static final int ENGINE_STATE_ENTITY_BYTES = 8 + 4 + 4 + 4;
    /**
     * Bytes per maneuver-command record: entityId(8) + maneuverMask(4). Mirrors the codec's
     * {@code MANEUVER_COMMAND_ENTITY_BYTES}.
     */
    public static final int MANEUVER_COMMAND_ENTITY_BYTES = 8 + 4;
    /**
     * Bytes per ship-system state record: entityId(8) + stateCode(4) + effectLevel(4)
     * + cooldownRemaining(4) + ammo(4). Mirrors the codec's {@code SYSTEM_STATE_ENTITY_BYTES}.
     *
     */
    public static final int SYSTEM_STATE_ENTITY_BYTES = 8 + 4 + 4 + 4 + 4;
    /** First schema version that appends the non-ship (projectile/beam) tier. */
    public static final int NON_SHIP_SCHEMA_VERSION = 2;
    /** First schema version that appends the per-ship flux/shield tier. */
    public static final int FLUX_SCHEMA_VERSION = 3;
    /** First schema version that appends the per-mount weapon-readiness tier. */
    public static final int WEAPON_SCHEMA_VERSION = 4;
    /** First schema version that appends the combat-side allegiance tier. */
    public static final int ALLEGIANCE_SCHEMA_VERSION = 5;
    /** First schema version that appends the per-ship engine-state tier. */
    public static final int ENGINE_STATE_SCHEMA_VERSION = 6;
    /** First schema version that appends the per-ship maneuver-command tier. */
    public static final int MANEUVER_COMMAND_SCHEMA_VERSION = 7;
    /** First schema version that appends the per-ship SYSTEM-state tier. */
    public static final int SYSTEM_STATE_SCHEMA_VERSION = 8;
    /**
     * Bytes per fragment-marker record: entityId(8) + tierMask(4) + mountFrom(4) + mountTo(4).
     * Mirrors the codec's {@code FRAGMENT_MARKER_ENTITY_BYTES}.
     */
    public static final int FRAGMENT_MARKER_ENTITY_BYTES = 8 + 4 + 4 + 4;
    /** First schema version that appends the FRAGMENT_MARKER tail tier. */
    public static final int FRAGMENT_MARKER_SCHEMA_VERSION = 9;
    /**
     * Bytes per phase-cloak state record: entityId(8) + stateCode(4) + effectLevel(4)
     * + cooldownRemaining(4) + ammo(4) — the v8 {@link #SYSTEM_STATE_ENTITY_BYTES} layout
     * verbatim, keyed on the ship's {@code getPhaseCloak()} slot. Mirrors
     * the codec's {@code PHASE_CLOAK_ENTITY_BYTES}.
     */
    public static final int PHASE_CLOAK_ENTITY_BYTES = 8 + 4 + 4 + 4 + 4;
    /**
     * First schema version that appends the per-ship PHASE-cloak state tier.
     * Appended AFTER the fragment-marker section (D-M2-2): a stale v9 reader walks the pose
     * tiers, reads the marker section at its unchanged position, and ignores the trailing
     * cloak bytes via the up-front body length.
     */
    public static final int PHASE_CLOAK_SCHEMA_VERSION = 10;
    /**
     * First schema version whose flux records carry the quantized shield-facing u8:
     * a WIDTH change to the existing v3 flux section (the byte sits between
     * hardFlux and statusFlags in every record), not an appended tail tier. The server's
     * stamp predicate is VALUE-based — a frame is stamped v11 only when some flux record
     * carries facing != 0; an all-facing-0 frame stamps ≤v10 with nothing lost (all true
     * values are 0). The byte is decoded (the walk needs its width) but stays DORMANT until
     * the pose lane surfaces it (the AWSD v4 server-half precedent).
     */
    public static final int SHIELD_FACING_SCHEMA_VERSION = 11;
    /**
     * First schema version whose kind=BEAM non-ship records carry the source pair:
     * a WIDTH change to the existing v2 non-ship section keyed by record KIND
     * (the v11 flux-width precedent), not an appended tail tier. The server's stamp
     * predicate is VALUE-based — a frame is stamped v12 only when some beam record carries
     * a resolved source ({@code sourceEntityId != 0}); a source-less frame stamps ≤v11
     * byte-identical. A v12 frame also FORCES the v11 widened flux record regardless of
     * facing values (higher-tier-forces-lower applied to record width). At v12+ the
     * non-ship section stops being skipped: BEAM records are retained as
     * {@link ServerBeamState} (the beam-presentation lane's aim/source input);
     * projectile/missile records are decoded past.
     */
    public static final int BEAM_SOURCE_SCHEMA_VERSION = 12;
    /**
     * First schema version whose kind=MISSILE non-ship records carry the source pair:
     * a WIDTH change to the existing v2 non-ship section keyed by
     * record KIND + schema (the v12 beam-width precedent), not an appended tail tier.
     * The server's stamp predicate is VALUE-based and rides the MISSILE side ALONE —
     * a frame is stamped v13 only when some missile record carries a resolved source
     * ({@code sourceEntityId != 0}); beam sources may be all-sentinel in a v13 frame
     * (a resolved beam without a resolved missile stamps v12). A v13 body FORCES the
     * v12 beam pair onto every beam record and the v11 widened flux record (the WIDTH
     * LAW — higher-tier-forces-lower applied to record width). Missile networkIds are
     * stable per-lifetime v13a allocator ids (&ge; 1e9) on EVERY v12+ frame — that is
     * a record-side change, not a schema gate. At v12+ MISSILE records are retained as
     * {@link ServerMissileState} (the slice-3 (e) pose lane's input; source pair read
     * at v13+, sentinels at v12); projectile records stay decoded past.
     */
    public static final int MISSILE_SOURCE_SCHEMA_VERSION = 13;
    /**
     * Bytes per ship-CR clock record: entityId(8) + cr(4) + timeDeployedSec(4) + peakSec(4)
     * — floats as raw int bits. Mirrors the codec's {@code CR_ENTITY_BYTES}.
     */
    public static final int SHIP_CR_ENTITY_BYTES = 8 + 4 + 4 + 4;
    /**
     * First schema version that appends the per-ship combat-readiness (CR) clock tier:
     * an appended tail section AFTER the v10 phase-cloak section (the cloak
     * append precedent), SPARSE per-entity — an absent ship means "unchanged", never
     * "CR zero". The server's stamp predicate is VALUE-based (a frame is stamped v14 only
     * when the fold attached ≥1 CR record); a v14 body FORCES the v13 missile pair →
     * v12 beam pair → v11 widened flux record (the WIDTH LAW — sources may be all-sentinel
     * in a v14 frame, so no resolved beam/missile is required). Carried so the predict
     * client can pin its LOCAL CR/PPT readouts, which freeze when the local engine scans a
     * different roster: {@code cr} = the engine's {@code getCurrentCR()};
     * {@code timeDeployedSec} = the RAW deployed-seconds accumulator (the HUD subtracts
     * from its local peak itself — never "PPT remaining"); {@code peakSec} =
     * diagnostic-grade effective peak (never-lose-CR hulls carry ≥ 1e5 s by design).
     */
    public static final int SHIP_CR_SCHEMA_VERSION = 14;
    /**
     * Fixed bytes per content-dictionary record BEFORE its variable-width key:
     * contentId(4) + one-byte UTF-8 key length. Mirrors the codec's
     * {@code CONTENT_DICTIONARY_ENTRY_PREFIX_BYTES}.
     */
    public static final int CONTENT_DICTIONARY_ENTRY_PREFIX_BYTES = 4 + 1;
    /**
     * First schema version that appends the FRAME-GLOBAL content-dictionary tier:
     * a tail section AFTER the v14 ship-CR section — int count, then per
     * entry contentId(4) + a one-byte UTF-8 key length (1..255) + the key bytes
     * (variable width, the v12 beam-record precedent). The dictionary is a catalog
     * announcement, not entity state: a small cycling window mapping interned non-ship
     * contentIds to their true content keys (engine projectile spec ids, e.g.
     * {@code "pilum_second_stage"}) so presentation can spawn by true spec without a
     * request channel; consumers fold it last-write-wins and NEVER trust it for
     * outcomes. The server's stamp predicate is count-based like v14: a frame stamps
     * v15 only when the fold attached ≥1 entry — and the CR section MAY be empty in a
     * v15 frame (the exactly-v15 fail-closed mirror rides the dictionary tier alone).
     */
    public static final int CONTENT_DICTIONARY_SCHEMA_VERSION = 15;
    /**
     * First schema version whose kind=MISSILE non-ship records carry the missile POSE
     * pair: a WIDTH change to the existing v2 non-ship section keyed by
     * record KIND + schema (the v13 missile-source precedent), not an appended tail
     * tier — facingU16 (360°/65536 steps, minted ONCE server-side at the engine read;
     * the v11 never-re-derive law) + a poseFlags u8 (bit0 VALID, bit1 FIZZLING;
     * spare bits MASKED at decode, never rejected), AFTER the source pair. The server's
     * stamp predicate is VALUE-based and rides the missile pose ALONE — a frame stamps
     * v16 only when some missile record carries {@code poseFlags != 0}; a pose-less
     * frame stamps ≤v15 byte-identical. A v16 body FORCES the v15-below record widths
     * (WIDTH LAW), but the dictionary and CR sections MAY both be empty in a v16 frame
     * (each exactly-vN fail-closed mirror stays keyed to its own version). Sentinel
     * pose = (0, 0); a masked-to-zero flags byte defensively forces facing to 0.
     */
    public static final int MISSILE_POSE_SCHEMA_VERSION = 16;
    /**
     * The highest schema version this reader fully decodes: drives the charter R5
     * future-schema flag and the decode verifier's Part C/D pins. A new tier bumps this
     * to its version in the same commit that adds its {@code apply*}/read step.
     */
    public static final int HIGHEST_KNOWN_SCHEMA_VERSION = MISSILE_POSE_SCHEMA_VERSION;

    /**
     * Charter R5 flag-once: the reader's
     * forward tolerance is real but was SILENT — a stale joiner rendered a degraded world with zero
     * indication. On the first frame whose schema exceeds the highest tier this reader knows, log
     * ONE line and keep decoding the known tiers. {@code System.err} because this pure-Java
     * {@code net} package must stay game-free for the decode verifier; Starsector redirects stderr
     * into {@code starsector.log}, so a live joiner's line lands beside the watch log.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean FUTURE_SCHEMA_FLAGGED =
            new java.util.concurrent.atomic.AtomicBoolean();

    private AwssSnapshotReader() {
    }

    /**
     * Decode one frame from {@code in}, or return {@code null} at a clean
     * end-of-stream (the frame boundary). Throws on a corrupt or truncated frame.
     */
    public static ServerSnapshotFrame readFrame(InputStream in) throws IOException {
        int first = in.read();
        if (first < 0) {
            return null; // clean EOF at a frame boundary
        }
        int magic = readIntAfterFirstByte(in, first);
        if (magic != MAGIC) {
            throw new IOException("world snapshot frame magic mismatch: " + Integer.toHexString(magic));
        }
        return readFrameAfterMagic(in);
    }

    /**
     * Decode one frame whose 4-byte {@link #MAGIC} has <em>already</em> been read and
     * validated by the caller — the rest of the AWSS header, body, and CRC. This is
     * the entry point a multiplexed-stream demultiplexer
     * ({@link MultiplexedServerStreamReader}) uses after it peeks the leading magic to
     * decide a frame is a world snapshot rather than a despawn event. Same fail-closed
     * contract as {@link #readFrame(InputStream)} once the magic is consumed.
     */
    public static ServerSnapshotFrame readFrameAfterMagic(InputStream in) throws IOException {
        int schemaVersion = WireBytes.readUnsignedShort(in); // captured: drives the trailing-tier walk
        if (schemaVersion > HIGHEST_KNOWN_SCHEMA_VERSION
                && FUTURE_SCHEMA_FLAGGED.compareAndSet(false, true)) {
            // Log divorce: routed off System.err (which the game tees into
            // starsector.log) through the PilotWatchLog seam; headless hosts without log4j get
            // the same stderr line via its fallback.
            PilotWatchLog.error("AWSS FUTURE SCHEMA v" + schemaVersion
                    + " > known v" + HIGHEST_KNOWN_SCHEMA_VERSION
                    + ": this client is STALE vs the server -> decoding known tiers only, unknown"
                    + " trailing tiers ignored (charter R5 tolerate-and-flag; logged once)", null);
        }
        // (83) CL-1: no PAST-branch announce here — the server stamps each frame with the LOWEST
        // version covering its content, so a below-ceiling frame is HEALTHY (the deleted
        // n1-awss-below-floor row false-fired whole-session). Serve-capability shortfall is
        // confessed ONCE at admission instead (n1-server-schema-below-client, the WELCOME
        // serveSchemaMax compare in PredictReconcileDatagramSession).
        int headerBytes = WireBytes.readUnsignedShort(in);
        if (headerBytes != HEADER_BYTES) {
            throw new IOException("world snapshot frame header size mismatch: " + headerBytes);
        }
        WireBytes.readInt(in); // wire type code — tolerated
        int bodyBytes = WireBytes.readInt(in);
        if (bodyBytes < BODY_PREFIX_BYTES || bodyBytes > MAX_BODY_BYTES) {
            throw new IOException("world snapshot frame body size outside [" + BODY_PREFIX_BYTES
                    + ", " + MAX_BODY_BYTES + "]: " + bodyBytes);
        }
        byte[] body = new byte[bodyBytes];
        WireBytes.readFully(in, body);
        int expectedCrc = WireBytes.readInt(in);
        CRC32 crc = new CRC32();
        crc.update(body, 0, body.length);
        if ((int) crc.getValue() != expectedCrc) {
            throw new IOException("world snapshot frame checksum mismatch");
        }
        return decodeBody(body, schemaVersion);
    }

    /**
     * Decode every frame from {@code in} until end-of-stream, handing each to
     * {@code consumer}. Throws on the first corrupt/truncated frame.
     */
    public static void readAll(InputStream in, Consumer<? super ServerSnapshotFrame> consumer)
            throws IOException {
        while (true) {
            ServerSnapshotFrame frame = readFrame(in);
            if (frame == null) {
                return;
            }
            consumer.accept(frame);
        }
    }

    /** Decode every frame from {@code in} into a list (convenience for tests/verifiers). */
    public static List<ServerSnapshotFrame> readAll(InputStream in) throws IOException {
        List<ServerSnapshotFrame> frames = new ArrayList<>();
        readAll(in, frames::add);
        return frames;
    }

    private static ServerSnapshotFrame decodeBody(byte[] body, int schemaVersion) throws IOException {
        ByteArrayInputStream data = new ByteArrayInputStream(body);
        long simTick = WireBytes.readLong(data);
        int entityCount = WireBytes.readInt(data);
        long shipRegionEnd = (long) BODY_PREFIX_BYTES + (long) ENTITY_BYTES * entityCount;
        if (entityCount < 0 || shipRegionEnd > body.length) {
            throw new IOException("world snapshot entity count inconsistent with body length: " + entityCount);
        }
        List<ServerEntityPose> entities = new ArrayList<>(entityCount);
        for (int i = 0; i < entityCount; i++) {
            long entityId = WireBytes.readLong(data);
            int contentId = WireBytes.readInt(data);
            WireBytes.readLong(data); // spawnTick — not needed to puppet kinematics
            int ownerConnectionId = WireBytes.readInt(data);
            float locX = WireBytes.readFloat(data);
            float locY = WireBytes.readFloat(data);
            float velX = WireBytes.readFloat(data);
            float velY = WireBytes.readFloat(data);
            float facing = WireBytes.readFloat(data);
            float angularVelocity = WireBytes.readFloat(data);
            float hitpoints = WireBytes.readFloat(data); // hull — lifecycle reason
            WireBytes.readInt(data); // hullLevel
            WireBytes.readInt(data); // armorCellCount
            WireBytes.readInt(data); // armorTotal
            WireBytes.readLong(data); // armorGridHash
            entities.add(new ServerEntityPose(
                    entityId, contentId, ownerConnectionId,
                    locX, locY, velX, velY, facing, angularVelocity, hitpoints));
        }
        // Walk the trailing critical-state tiers that the codec appends per schema version. The
        // non-ship tier is not rendered (skipped); the flux (v3+), weapon-mount (v4+), allegiance
        // (v5+), and engine-state (v6+) tiers are read and attached to each pose. The v9+
        // FRAGMENT_MARKER tier is frame-scoped (a per-frame partial-carriage declaration, not a
        // pose attribute), so it is read here after the pose-tier walk leaves {@code data}
        // positioned at its count. The v10+ PHASE_CLOAK tier is a pose attribute again but is
        // appended AFTER the marker section (the stale-v9-reader tolerance
        // depends on the marker section keeping its position), so it too is read here. The
        // v14+ SHIP_CR clock tier is appended after the cloak section and read
        // last, the same way.
        List<ServerBeamState> beams = new ArrayList<>();
        List<ServerMissileState> missiles = new ArrayList<>();
        List<ServerEntityPose> poses =
                applyTrailingTiers(data, entities, schemaVersion, beams, missiles);
        List<ServerFragmentMarker> markers = schemaVersion < FRAGMENT_MARKER_SCHEMA_VERSION
                ? List.of() : readFragmentMarkerTier(data, schemaVersion);
        if (schemaVersion >= PHASE_CLOAK_SCHEMA_VERSION) {
            poses = applyPhaseCloakTier(data, poses, schemaVersion);
        }
        if (schemaVersion >= SHIP_CR_SCHEMA_VERSION) {
            poses = applyShipCrTier(data, poses, schemaVersion);
        }
        Map<Integer, String> contentDictionary = schemaVersion < CONTENT_DICTIONARY_SCHEMA_VERSION
                ? Map.of() : readContentDictionaryTier(data, schemaVersion);
        return new ServerSnapshotFrame(simTick, poses, markers, beams, missiles, contentDictionary);
    }

    /**
     * Read the frame-scoped FRAGMENT_MARKER section ({@code int count} then {@code count}
     * fixed-width {@link #FRAGMENT_MARKER_ENTITY_BYTES} records: entityId, tierMask,
     * mountFrom, mountTo) — schema v9, the entity-tier fragmentation v2 wire.
     * Mirrors the server codec's {@code decodeBodyV9} rules: a frame stamped exactly v9 with
     * a zero count is rejected (the marker-present ⇔ v9 wire law — fail-closed {@code <= 0}),
     * while a FUTURE frame (stamped above v9) may legally carry an empty forced-to-be-written
     * marker section; a record the fail-closed {@link ServerFragmentMarker} constructor
     * rejects fails the frame. Unlike unknown trailing TIERS (charter R5 tolerate-and-flag),
     * unknown tier BITS inside a marker's mask are fail-closed by design — a stale reader
     * cannot honor ABSENT ≠ EMPTY semantics it does not know (the wire spec's compat hazard;
     * kit discipline guards the skew).
     */
    private static List<ServerFragmentMarker> readFragmentMarkerTier(
            ByteArrayInputStream data, int schemaVersion) throws IOException {
        int markerCount = WireBytes.readInt(data);
        if (markerCount < 0) {
            throw new IOException("world snapshot v9 fragment-marker count negative: " + markerCount);
        }
        if (markerCount == 0 && schemaVersion == FRAGMENT_MARKER_SCHEMA_VERSION) {
            // Fail-closed: a v9 frame is stamped only when it actually carries markers.
            throw new IOException("world snapshot v9 frame declares no fragment-marker tier: "
                    + markerCount);
        }
        if ((long) markerCount * FRAGMENT_MARKER_ENTITY_BYTES > data.available()) {
            throw new IOException(
                    "world snapshot v9 fragment-marker count inconsistent with body length: "
                            + markerCount);
        }
        List<ServerFragmentMarker> markers = new ArrayList<>(markerCount);
        for (int i = 0; i < markerCount; i++) {
            long entityId = WireBytes.readLong(data);
            int tierMask = WireBytes.readInt(data);
            int mountFrom = WireBytes.readInt(data);
            int mountTo = WireBytes.readInt(data);
            try {
                markers.add(new ServerFragmentMarker(entityId, tierMask, mountFrom, mountTo));
            } catch (IllegalArgumentException badRecord) {
                throw new IOException("world snapshot v9 fragment-marker record invalid", badRecord);
            }
        }
        return markers;
    }

    /**
     * Read the per-ship PHASE-cloak state section ({@code int count} then {@code count} ×
     * {@code (long entityId, int stateCode, float effectLevel, float cooldownRemaining,
     * int ammo)} — the v8 SYSTEM_STATE record layout verbatim) and return a
     * copy of {@code poses} with each matched entity's cloak state attached (poses without a
     * matching record keep {@link ServerEntityPose#UNKNOWN_PHASE_CLOAK_STATE} — a cloakless
     * hull contributes no record). The section is appended AFTER the fragment-marker section
     * (D-M2-2), so {@code data} must be positioned just past the marker records. Mirrors the
     * server codec's {@code decodeBodyV10} rules: a frame stamped exactly v10 with a zero
     * count is rejected (the cloak-present ⇔ v10 wire law — fail-closed, the v9 marker
     * precedent), while a FUTURE frame (stamped above v10) may legally carry an empty
     * forced-to-be-written cloak section. Fail-closed on a negative/overrunning count.
     * There is NO fragment-marker bit for this tier (D-M2-3 defers the mask widening); the
     * merge-side consequence — hold-last on a fragmented cloak ship — lives in
     * {@link SnapshotChunkMerger}.
     */
    private static List<ServerEntityPose> applyPhaseCloakTier(
            ByteArrayInputStream data, List<ServerEntityPose> poses, int schemaVersion)
            throws IOException {
        int cloakCount = WireBytes.readInt(data);
        if (cloakCount < 0) {
            throw new IOException("world snapshot v10 phase-cloak count negative: " + cloakCount);
        }
        if (cloakCount == 0 && schemaVersion == PHASE_CLOAK_SCHEMA_VERSION) {
            // Fail-closed: a v10 frame is stamped only when it actually carries cloak records.
            throw new IOException("world snapshot v10 frame declares no phase-cloak tier: "
                    + cloakCount);
        }
        if ((long) cloakCount * PHASE_CLOAK_ENTITY_BYTES > data.available()) {
            throw new IOException(
                    "world snapshot v10 phase-cloak count inconsistent with body length: "
                            + cloakCount);
        }
        Map<Long, int[]> cloakByEntityId = new HashMap<>(cloakCount * 2);
        for (int i = 0; i < cloakCount; i++) {
            long entityId = WireBytes.readLong(data);
            int stateCode = WireBytes.readInt(data);
            int effectLevelBits = WireBytes.readInt(data); // float bits — converted at attach
            int cooldownRemainingBits = WireBytes.readInt(data);
            int ammo = WireBytes.readInt(data);
            cloakByEntityId.put(entityId,
                    new int[] { stateCode, effectLevelBits, cooldownRemainingBits, ammo });
        }
        List<ServerEntityPose> withCloaks = new ArrayList<>(poses.size());
        for (ServerEntityPose pose : poses) {
            int[] c = cloakByEntityId.get(pose.entityId());
            withCloaks.add(c == null ? pose : pose.withPhaseCloakState(
                    c[0], Float.intBitsToFloat(c[1]), Float.intBitsToFloat(c[2]), c[3]));
        }
        return withCloaks;
    }

    /**
     * Read the per-ship combat-readiness (CR) clock section ({@code int count} then
     * {@code count} × {@code (long entityId, float cr, float timeDeployedSec, float
     * peakSec)} — floats as raw int bits, schema v14) and return a copy of
     * {@code poses} with each matched entity's CR clock attached (poses without a matching
     * record keep {@link ServerEntityPose#UNKNOWN_CR} — the tier is SPARSE per-entity like
     * the cloak tier: an absent ship means "unchanged", never "CR zero"). The section is
     * appended AFTER the phase-cloak section, so {@code data} must be positioned just past
     * the cloak records. Mirrors the server codec's {@code decodeBodyV14} rules: a frame
     * stamped exactly v14 with a zero count is rejected (the CR-present ⇔ v14 wire law —
     * fail-closed, the v9/v10 precedent), while a FUTURE frame (stamped above v14) may
     * legally carry an empty forced-to-be-written CR section. Fail-closed on a
     * negative/overrunning count.
     */
    private static List<ServerEntityPose> applyShipCrTier(
            ByteArrayInputStream data, List<ServerEntityPose> poses, int schemaVersion)
            throws IOException {
        int crCount = WireBytes.readInt(data);
        if (crCount < 0) {
            throw new IOException("world snapshot v14 ship-cr count negative: " + crCount);
        }
        if (crCount == 0 && schemaVersion == SHIP_CR_SCHEMA_VERSION) {
            // Fail-closed: a v14 frame is stamped only when it actually carries CR records.
            throw new IOException("world snapshot v14 frame declares no ship-cr tier: "
                    + crCount);
        }
        if ((long) crCount * SHIP_CR_ENTITY_BYTES > data.available()) {
            throw new IOException(
                    "world snapshot v14 ship-cr count inconsistent with body length: "
                            + crCount);
        }
        Map<Long, int[]> crByEntityId = new HashMap<>(crCount * 2);
        for (int i = 0; i < crCount; i++) {
            long entityId = WireBytes.readLong(data);
            int crBits = WireBytes.readInt(data); // float bits — converted at attach
            int depBits = WireBytes.readInt(data);
            int peakBits = WireBytes.readInt(data);
            crByEntityId.put(entityId, new int[] { crBits, depBits, peakBits });
        }
        List<ServerEntityPose> withCr = new ArrayList<>(poses.size());
        for (ServerEntityPose pose : poses) {
            int[] c = crByEntityId.get(pose.entityId());
            withCr.add(c == null ? pose : pose.withShipCr(
                    Float.intBitsToFloat(c[0]), Float.intBitsToFloat(c[1]),
                    Float.intBitsToFloat(c[2])));
        }
        return withCr;
    }

    /**
     * Read the frame-global CONTENT-DICTIONARY section ({@code int count} then
     * {@code count} variable-width records: contentId, a one-byte UTF-8 key length,
     * the key bytes — schema v15) into a contentId → key map. The section
     * is appended AFTER the ship-CR section, so {@code data} must be positioned just
     * past the CR records. Mirrors the server codec's {@code decodeBodyV15} rules: a
     * frame stamped exactly v15 with a zero count is rejected (the
     * dictionary-present ⇔ v15 wire law — fail-closed, the v9/v10/v14 precedent),
     * while a FUTURE frame (stamped above v15) may legally carry an empty
     * forced-to-be-written dictionary section. Fail-closed on a negative/overrunning
     * count, a zero key length, a sub-1 contentId, an overrunning key, and a key carrying a char
     * {@code WireTextLaw.isPrintableWireChar} rejects (mirrored at the server's intern,
     * {@code ContentDictionaryEntry.carriable}).
     */
    private static Map<Integer, String> readContentDictionaryTier(
            ByteArrayInputStream data, int schemaVersion) throws IOException {
        int entryCount = WireBytes.readInt(data);
        if (entryCount < 0) {
            throw new IOException("world snapshot v15 content-dict count negative: " + entryCount);
        }
        if (entryCount == 0 && schemaVersion == CONTENT_DICTIONARY_SCHEMA_VERSION) {
            // Fail-closed: a v15 frame is stamped only when it actually carries entries.
            throw new IOException("world snapshot v15 frame declares no content-dict tier: "
                    + entryCount);
        }
        if ((long) entryCount * CONTENT_DICTIONARY_ENTRY_PREFIX_BYTES > data.available()) {
            throw new IOException(
                    "world snapshot v15 content-dict count inconsistent with body length: "
                            + entryCount);
        }
        Map<Integer, String> entries = new HashMap<>(entryCount * 2);
        for (int i = 0; i < entryCount; i++) {
            int contentId = WireBytes.readInt(data);
            if (contentId < 1) {
                throw new IOException("world snapshot v15 content-dict id invalid: " + contentId);
            }
            int keyLength = data.read();
            if (keyLength <= 0) {
                // 0 is never written (keys are 1..255 bytes); -1 is a truncated section.
                throw new IOException("world snapshot v15 content-dict key length invalid: "
                        + keyLength);
            }
            if (keyLength > data.available()) {
                throw new IOException(
                        "world snapshot v15 content-dict key overruns body: " + keyLength);
            }
            byte[] keyUtf8 = new byte[keyLength];
            WireBytes.readFully(data, keyUtf8);
            String key = new String(keyUtf8, StandardCharsets.UTF_8);
            // The single-line law
            // — a key is printed raw on spec= rows; the reject names an index, never the key.
            int bad = WireTextLaw.firstUnprintableIndex(key);
            if (bad >= 0) {
                throw new IOException("world snapshot v15 content-dict key carries a non-printable"
                        + " char at index " + bad);
            }
            entries.put(contentId, key);
        }
        return entries;
    }

    /**
     * Walk the trailing critical-state sections that follow the ship records, attaching the tiers
     * the mod consumes. {@code data} is positioned just past the ship records and {@code schemaVersion}
     * says which sections are present (each higher tier forces every lower section to be written, even
     * when empty, so they can be located): v1 ⇒ none; v2 ⇒ non-ship; v3 ⇒ +flux; v4 ⇒ +weapon-mount;
     * v5 ⇒ +allegiance; v6 ⇒ +engine-state; v7 ⇒ +maneuver-command;
     * v8 ⇒ +ship-system state. The v9 FRAGMENT_MARKER tier is
     * frame-scoped, not a pose attribute, so it is NOT walked here — the caller reads it via
     * {@code readFragmentMarkerTier} after this walk returns; the v10 PHASE_CLOAK tier
     * sits after the marker section (D-M2-2), so the caller attaches it via
     * {@code applyPhaseCloakTier} after the marker read. The
     * non-ship section is skipped below v12 and read via {@code readNonShipTier} at v12+
     * (beam records are variable-width there, so a fixed-width skip would
     * misparse; BEAM records are retained into {@code beams} and MISSILE records into
     * {@code missiles}); the flux
     * section is read and attached via {@link ServerEntityPose#withFlux}; the weapon-mount section
     * via {@link ServerEntityPose#withWeaponMounts}; the allegiance section via
     * {@link ServerEntityPose#withFleetSide}; the engine-state section via
     * {@link ServerEntityPose#withEngineState}; the maneuver-command section via
     * {@link ServerEntityPose#withManeuverCommandMask}; the system-state section via
     * {@link ServerEntityPose#withSystemState}. Fail-closed: a negative count or a
     * section that overruns the remaining body throws.
     */
    private static List<ServerEntityPose> applyTrailingTiers(
            ByteArrayInputStream data, List<ServerEntityPose> poses, int schemaVersion,
            List<ServerBeamState> beams, List<ServerMissileState> missiles) throws IOException {
        if (schemaVersion < NON_SHIP_SCHEMA_VERSION) {
            return poses; // v1: ship records only
        }
        if (schemaVersion >= BEAM_SOURCE_SCHEMA_VERSION) {
            readNonShipTier(data, schemaVersion, beams, missiles);
        } else {
            skipSection(data, NON_SHIP_ENTITY_BYTES);
        }
        if (schemaVersion < FLUX_SCHEMA_VERSION) {
            return poses; // v2: the non-ship section was the trailing section
        }
        poses = applyFluxTier(data, poses, schemaVersion);
        if (schemaVersion < WEAPON_SCHEMA_VERSION) {
            return poses; // v3: the flux section was the trailing section
        }
        poses = applyWeaponMountTier(data, poses);
        if (schemaVersion < ALLEGIANCE_SCHEMA_VERSION) {
            return poses; // v4: the weapon-mount section was the trailing section
        }
        poses = applyAllegianceTier(data, poses);
        if (schemaVersion < ENGINE_STATE_SCHEMA_VERSION) {
            return poses; // v5: the allegiance section was the trailing section
        }
        poses = applyEngineStateTier(data, poses);
        if (schemaVersion < MANEUVER_COMMAND_SCHEMA_VERSION) {
            return poses; // v6: the engine-state section was the trailing section
        }
        poses = applyManeuverCommandTier(data, poses);
        if (schemaVersion < SYSTEM_STATE_SCHEMA_VERSION) {
            return poses; // v7: the maneuver-command section was the trailing section
        }
        return applySystemStateTier(data, poses);
    }

    /**
     * Read the non-ship section at schema v12+ ({@code int count} then {@code count} records:
     * the fixed-width {@link #NON_SHIP_ENTITY_BYTES} base — networkId, kind, owner, posX/posY,
     * contentId — plus, on each kind=BEAM record, the {@link #BEAM_SOURCE_EXTRA_BYTES} source
     * pair and, at v13+ on each kind=MISSILE record, the {@link #MISSILE_SOURCE_EXTRA_BYTES}
     * pair plus, at v16+, the {@link #MISSILE_POSE_EXTRA_BYTES} pose pair),
     * retaining BEAM records into {@code beams}, MISSILE records into {@code missiles}
     * (source pair at the v12 sentinels below v13, pose at the (0, 0) sentinels below v16 —
     * both schema-keyed widths), and decoding past projectile records.
     * Mirrors the server codec's {@code readNonShipSectionWithBeamSource} +
     * {@code decodeBodyV12}/{@code decodeBodyV13}/{@code decodeBodyV16} rules: an
     * out-of-range kind code fails the frame; unknown pose-flag spare bits are MASKED off
     * (never rejected — so a future bit needs no crash window) and a masked-to-zero flags
     * byte defensively forces facing to 0; a frame stamped exactly v12 with no resolved
     * beam source is rejected, one stamped exactly v13 with no resolved missile source is
     * rejected, and one stamped exactly v16 with no missile pose is rejected (each
     * value-based stamp predicate would have stamped lower — the v13/v16 predicates ride
     * the missile side ALONE, so such frames may carry all-sentinel beam sources), while a
     * FUTURE frame may legally carry none. Fail-closed on a negative/overrunning count and on
     * a non-finite base pos pair (the shared beam/missile engine input).
     */
    private static void readNonShipTier(
            ByteArrayInputStream data, int schemaVersion, List<ServerBeamState> beams,
            List<ServerMissileState> missiles) throws IOException {
        int count = WireBytes.readInt(data);
        if (count < 0) {
            throw new IOException("world snapshot v12 non-ship count negative: " + count);
        }
        if ((long) count * NON_SHIP_ENTITY_BYTES > data.available()) {
            throw new IOException(
                    "world snapshot v12 non-ship count inconsistent with body length: " + count);
        }
        boolean missileSourceWidened = schemaVersion >= MISSILE_SOURCE_SCHEMA_VERSION;
        boolean missilePoseWidened = schemaVersion >= MISSILE_POSE_SCHEMA_VERSION;
        boolean anyBeamSource = false;
        boolean anyMissileSource = false;
        boolean anyMissilePose = false;
        for (int i = 0; i < count; i++) {
            long networkId = WireBytes.readLong(data);
            int kind = WireBytes.readInt(data);
            if (kind < 0 || kind > NON_SHIP_KIND_MAX) {
                throw new IOException("world snapshot non-ship kind wire code out of range: " + kind);
            }
            int owner = WireBytes.readInt(data);
            float posX = WireBytes.readFloat(data);
            float posY = WireBytes.readFloat(data);
            // Fail-closed decode law: this shared pos pair reaches the engine
            // on BOTH retained kinds — beam toX/toY drives the converge length cap + local damage
            // sweep (WireTermAutofireShim), missile posX/posY drives spawn/loc.set/velocity
            // (MissilePoseLane) — so a non-finite value NaN-propagates into engine state. Guard
            // at the one decode site all consumers share (the FoeStateLaneCoordinator ship-pose
            // isFinite idiom, promoted to the non-ship tier).
            if (!Float.isFinite(posX) || !Float.isFinite(posY)) {
                throw new IOException(
                        "world snapshot non-ship pos not finite at record " + i);
            }
            int contentId = WireBytes.readInt(data);
            if (kind == NON_SHIP_KIND_BEAM) {
                if (data.available() < BEAM_SOURCE_EXTRA_BYTES) {
                    throw new IOException(
                            "world snapshot v12 non-ship beam-source pair truncated at record " + i);
                }
                long sourceEntityId = WireBytes.readLong(data);
                int sourceMountIndex = WireBytes.readInt(data);
                anyBeamSource |= sourceEntityId != ServerBeamState.NO_SOURCE;
                beams.add(new ServerBeamState(
                        networkId, owner, posX, posY, contentId, sourceEntityId, sourceMountIndex));
            } else if (kind == NON_SHIP_KIND_MISSILE) {
                long sourceEntityId = ServerMissileState.NO_SOURCE;
                int sourceMountIndex = ServerMissileState.NO_SOURCE_MOUNT;
                if (missileSourceWidened) {
                    if (data.available() < MISSILE_SOURCE_EXTRA_BYTES) {
                        throw new IOException(
                                "world snapshot v13 non-ship missile-source pair truncated at record "
                                        + i);
                    }
                    sourceEntityId = WireBytes.readLong(data);
                    sourceMountIndex = WireBytes.readInt(data);
                    anyMissileSource |= sourceEntityId != ServerMissileState.NO_SOURCE;
                }
                int facingU16 = 0;
                int poseFlags = 0;
                if (missilePoseWidened) {
                    if (data.available() < MISSILE_POSE_EXTRA_BYTES) {
                        throw new IOException(
                                "world snapshot v16 non-ship missile-pose pair truncated at record "
                                        + i);
                    }
                    facingU16 = WireBytes.readUnsignedShort(data);
                    poseFlags = WireBytes.readUnsignedByte(data) & ServerMissileState.POSE_FLAGS_MASK;
                    if (poseFlags == 0) {
                        facingU16 = 0; // the sentinel invariant (spares-masked law)
                    }
                    anyMissilePose |= poseFlags != 0;
                }
                missiles.add(new ServerMissileState(
                        networkId, owner, posX, posY, contentId, sourceEntityId, sourceMountIndex,
                        facingU16, poseFlags));
            } else if (kind == NON_SHIP_KIND_PROJECTILE) {
                // Overlap law: PROJECTILE rows are decoded PAST (the base
                // record was fully consumed above), never retained or presented — counted here
                // so the debrief line proves the GPEE kind-8 lane is the ONE det presenter.
                // The content-0 subset is the script-det class (script-spawned explosions carry
                // no firing weapon ⇒ empty contentKey, the census shape).
                projectileRowsDecodedPast++;
                if (contentId == 0) {
                    projectileContent0RowsDecodedPast++;
                }
            }
        }
        if (!anyBeamSource && schemaVersion == BEAM_SOURCE_SCHEMA_VERSION) {
            // Fail-closed: a v12 frame is stamped only when some beam record actually
            // carries a resolved source (the value-based stamp predicate).
            throw new IOException("world snapshot v12 frame declares no resolved beam source");
        }
        if (!anyMissileSource && schemaVersion == MISSILE_SOURCE_SCHEMA_VERSION) {
            // Fail-closed: a v13 frame is stamped only when some missile record actually
            // carries a resolved source (the missile-alone stamp predicate).
            throw new IOException("world snapshot v13 frame declares no resolved missile source");
        }
        if (!anyMissilePose && schemaVersion == MISSILE_POSE_SCHEMA_VERSION) {
            // Fail-closed: a v16 frame is stamped only when some missile record actually
            // carries a pose (the missile-alone stamp predicate).
            throw new IOException("world snapshot v16 frame declares no missile pose");
        }
    }

    /**
     * Read the per-ship flux section ({@code int count} then {@code count} fixed-width
     * {@link #FLUX_ENTITY_BYTES} records) and return a copy of {@code poses} with each matched
     * entity's {@code currFlux}/{@code maxFlux}/{@code fluxLevel}/{@code hardFlux}/{@code fluxFlags}
     * attached (poses without a matching flux record keep {@link ServerEntityPose#UNKNOWN_FLUX} /
     * {@link ServerEntityPose#UNKNOWN_FLUX_FLAGS}). {@code data} must be positioned at the flux
     * count. {@code hardFlux} + the packed {@code statusFlags} were decoded-past here
     * until the foe-flux apply lane needed them — the wire walk is unchanged (same bytes), they
     * are now minted instead of skipped. Schema v11 widens each record by the
     * quantized shield-facing u8 between hardFlux and statusFlags: the byte is minted onto the
     * pose VERBATIM via the 6-arg {@code withFlux} (a sub-v11 record leaves
     * {@link ServerEntityPose#UNKNOWN_SHIELD_FACING});
     * mirroring the server codec's {@code decodeBodyV11}, a frame stamped exactly v11 whose flux
     * records ALL carry facing 0 is rejected (the value-based stamp predicate would have stamped
     * it ≤v10 — the charter R3 stamp-lowest analog; a FUTURE frame may legally carry all-0
     * facings). Fail-closed on a negative/overrunning count.
     */
    private static List<ServerEntityPose> applyFluxTier(
            ByteArrayInputStream data, List<ServerEntityPose> poses, int schemaVersion)
            throws IOException {
        int fluxCount = WireBytes.readInt(data);
        if (fluxCount < 0) {
            throw new IOException("world snapshot flux count negative: " + fluxCount);
        }
        boolean shieldFacingPresent = schemaVersion >= SHIELD_FACING_SCHEMA_VERSION;
        int fluxRecordBytes = shieldFacingPresent ? FLUX_ENTITY_BYTES + 1 : FLUX_ENTITY_BYTES;
        if ((long) fluxCount * fluxRecordBytes > data.available()) {
            throw new IOException("world snapshot flux count inconsistent with body length: " + fluxCount);
        }
        Map<Long, float[]> fluxByEntityId = new HashMap<>(fluxCount * 2);
        boolean anyShieldFacing = false;
        for (int i = 0; i < fluxCount; i++) {
            long entityId = WireBytes.readLong(data);
            float currFlux = WireBytes.readFloat(data);
            float maxFlux = WireBytes.readFloat(data);
            float fluxLevel = WireBytes.readFloat(data);
            float hardFlux = WireBytes.readFloat(data);
            int shieldFacingU8 = ServerEntityPose.UNKNOWN_SHIELD_FACING;
            if (shieldFacingPresent) {
                shieldFacingU8 = WireBytes.readUnsignedByte(data);
                anyShieldFacing |= shieldFacingU8 != 0;
            }
            int statusFlags = WireBytes.readInt(data); // overloaded/venting/shield packing
            fluxByEntityId.put(entityId,
                    new float[] { currFlux, maxFlux, fluxLevel, hardFlux, statusFlags,
                            shieldFacingU8 });
        }
        if (!anyShieldFacing && schemaVersion == SHIELD_FACING_SCHEMA_VERSION) {
            // Fail-closed: a v11 frame is stamped only when some flux record actually
            // carries a nonzero shield facing (the value-based stamp predicate).
            throw new IOException("world snapshot v11 frame declares no nonzero shield facing");
        }
        List<ServerEntityPose> withFlux = new ArrayList<>(poses.size());
        for (ServerEntityPose pose : poses) {
            float[] flux = fluxByEntityId.get(pose.entityId());
            withFlux.add(flux == null ? pose
                    : pose.withFlux(flux[0], flux[1], flux[2], flux[3], (int) flux[4],
                            (int) flux[5]));
        }
        return withFlux;
    }

    /**
     * Read the per-mount weapon-readiness section ({@code int count} then {@code count} fixed-width
     * {@link #WEAPON_MOUNT_ENTITY_BYTES} records: entityId, mountIndex, weaponContentId, two
     * float-bit cooldown scalars, ammo/maxAmmo, float-bit chargeLevel, packed statusFlags) and
     * return a copy of {@code poses} with each matched entity's mount states attached in wire
     * order (poses without a matching record keep an empty list). The server writes mounts
     * ordered ascending by (entityId, mountIndex), so each pose's list arrives mountIndex-sorted.
     * {@code data} must be positioned at the weapon-mount count. Fail-closed on a
     * negative/overrunning count. The tier was skipped before; the wire has
     * carried it since schema v4.
     */
    private static List<ServerEntityPose> applyWeaponMountTier(
            ByteArrayInputStream data, List<ServerEntityPose> poses) throws IOException {
        int mountCount = WireBytes.readInt(data);
        if (mountCount < 0) {
            throw new IOException("world snapshot v4 weapon-mount count negative: " + mountCount);
        }
        if ((long) mountCount * WEAPON_MOUNT_ENTITY_BYTES > data.available()) {
            throw new IOException(
                    "world snapshot v4 weapon-mount count inconsistent with body length: " + mountCount);
        }
        Map<Long, List<ServerWeaponMountState>> mountsByEntityId = new HashMap<>();
        for (int i = 0; i < mountCount; i++) {
            long entityId = WireBytes.readLong(data);
            int mountIndex = WireBytes.readInt(data);
            int weaponContentId = WireBytes.readInt(data);
            float cooldownRemaining = WireBytes.readFloat(data);
            float cooldown = WireBytes.readFloat(data);
            int ammo = WireBytes.readInt(data);
            int maxAmmo = WireBytes.readInt(data);
            float chargeLevel = WireBytes.readFloat(data);
            int statusFlags = WireBytes.readInt(data);
            mountsByEntityId.computeIfAbsent(entityId, k -> new ArrayList<>())
                    .add(new ServerWeaponMountState(mountIndex, weaponContentId,
                            cooldownRemaining, cooldown, ammo, maxAmmo, chargeLevel, statusFlags));
        }
        List<ServerEntityPose> withMounts = new ArrayList<>(poses.size());
        for (ServerEntityPose pose : poses) {
            List<ServerWeaponMountState> mounts = mountsByEntityId.get(pose.entityId());
            withMounts.add(mounts == null ? pose : pose.withWeaponMounts(mounts));
        }
        return withMounts;
    }

    /**
     * Read the combat-side allegiance section ({@code int count} then {@code count} ×
     * {@code (long entityId, int fleetSide)}) and return a copy of {@code poses} with each entity's
     * {@code fleetSide} attached (poses without a matching allegiance record keep
     * {@link ServerEntityPose#UNKNOWN_FLEET_SIDE}). {@code data} must be positioned at the allegiance
     * count (the caller has already walked the lower sections). Fail-closed on a negative/overrunning count.
     */
    private static List<ServerEntityPose> applyAllegianceTier(
            ByteArrayInputStream data, List<ServerEntityPose> poses) throws IOException {
        int allegianceCount = WireBytes.readInt(data);
        if (allegianceCount < 0) {
            throw new IOException("world snapshot v5 allegiance count negative: " + allegianceCount);
        }
        if ((long) allegianceCount * ALLEGIANCE_ENTITY_BYTES > data.available()) {
            throw new IOException(
                    "world snapshot v5 allegiance count inconsistent with body length: " + allegianceCount);
        }
        Map<Long, Integer> fleetSideByEntityId = new HashMap<>(allegianceCount * 2);
        for (int i = 0; i < allegianceCount; i++) {
            long entityId = WireBytes.readLong(data);
            int fleetSide = WireBytes.readInt(data);
            fleetSideByEntityId.put(entityId, fleetSide);
        }
        List<ServerEntityPose> withSides = new ArrayList<>(poses.size());
        for (ServerEntityPose pose : poses) {
            Integer side = fleetSideByEntityId.get(pose.entityId());
            withSides.add(side == null ? pose : pose.withFleetSide(side));
        }
        return withSides;
    }

    /**
     * Read the per-ship engine-state section ({@code int count} then {@code count} ×
     * {@code (long entityId, int disabledMask, int permDisabledMask, int controllerState)}) and
     * return a copy of {@code poses} with each matched entity's engine state attached (poses
     * without a matching record keep {@link ServerEntityPose#UNKNOWN_ENGINE_STATE}). {@code data}
     * must be positioned at the engine-state count (the caller has already walked the lower
     * sections). Fail-closed on a negative/overrunning count. Schema v6.
     */
    private static List<ServerEntityPose> applyEngineStateTier(
            ByteArrayInputStream data, List<ServerEntityPose> poses) throws IOException {
        int engineStateCount = WireBytes.readInt(data);
        if (engineStateCount < 0) {
            throw new IOException("world snapshot v6 engine-state count negative: " + engineStateCount);
        }
        if ((long) engineStateCount * ENGINE_STATE_ENTITY_BYTES > data.available()) {
            throw new IOException(
                    "world snapshot v6 engine-state count inconsistent with body length: " + engineStateCount);
        }
        Map<Long, int[]> engineStateByEntityId = new HashMap<>(engineStateCount * 2);
        for (int i = 0; i < engineStateCount; i++) {
            long entityId = WireBytes.readLong(data);
            int disabledMask = WireBytes.readInt(data);
            int permDisabledMask = WireBytes.readInt(data);
            int controllerState = WireBytes.readInt(data);
            engineStateByEntityId.put(entityId, new int[] { disabledMask, permDisabledMask, controllerState });
        }
        List<ServerEntityPose> withEngines = new ArrayList<>(poses.size());
        for (ServerEntityPose pose : poses) {
            int[] state = engineStateByEntityId.get(pose.entityId());
            withEngines.add(state == null ? pose : pose.withEngineState(state[0], state[1], state[2]));
        }
        return withEngines;
    }

    /**
     * Read the per-ship maneuver-command section ({@code int count} then {@code count} ×
     * {@code (long entityId, int maneuverMask)}) and return a copy of {@code poses} with each
     * matched entity's mask attached (poses without a matching record keep
     * {@link ServerEntityPose#UNKNOWN_MANEUVER_MASK}). {@code data} must be positioned at the
     * maneuver-command count (the caller has already walked the lower sections). Fail-closed on a
     * negative/overrunning count. Schema v7 — the foe engine-plume lane.
     */
    private static List<ServerEntityPose> applyManeuverCommandTier(
            ByteArrayInputStream data, List<ServerEntityPose> poses) throws IOException {
        int maneuverCount = WireBytes.readInt(data);
        if (maneuverCount < 0) {
            throw new IOException("world snapshot v7 maneuver-command count negative: " + maneuverCount);
        }
        if ((long) maneuverCount * MANEUVER_COMMAND_ENTITY_BYTES > data.available()) {
            throw new IOException(
                    "world snapshot v7 maneuver-command count inconsistent with body length: " + maneuverCount);
        }
        Map<Long, Integer> maskByEntityId = new HashMap<>(maneuverCount * 2);
        for (int i = 0; i < maneuverCount; i++) {
            long entityId = WireBytes.readLong(data);
            int maneuverMask = WireBytes.readInt(data);
            maskByEntityId.put(entityId, maneuverMask);
        }
        List<ServerEntityPose> withMasks = new ArrayList<>(poses.size());
        for (ServerEntityPose pose : poses) {
            Integer mask = maskByEntityId.get(pose.entityId());
            withMasks.add(mask == null ? pose : pose.withManeuverCommandMask(mask));
        }
        return withMasks;
    }

    /**
     * Read the per-ship SYSTEM-state section ({@code int count} then {@code count} ×
     * {@code (long entityId, int stateCode, float effectLevel, float cooldownRemaining, int ammo)})
     * and return a copy of {@code poses} with each matched entity's system state attached (poses
     * without a matching record keep {@link ServerEntityPose#UNKNOWN_SYSTEM_STATE}). The state
     * code is the server's PROJECT-OWNED mapping (0 IDLE, 1 CHARGEUP, 2 ACTIVE, 3 CHARGEDOWN,
     * 4 COOLDOWN — never an engine ordinal) and ammo {@code -1} means a no-charge system.
     * {@code data} must be positioned at the system-state count (the caller has already walked
     * the lower sections). Fail-closed on a negative/overrunning count. Schema v8 —
     * the mid-join system-seed lane ({@code forceState}/{@code setAmmo}, D4).
     */
    private static List<ServerEntityPose> applySystemStateTier(
            ByteArrayInputStream data, List<ServerEntityPose> poses) throws IOException {
        int systemStateCount = WireBytes.readInt(data);
        if (systemStateCount < 0) {
            throw new IOException("world snapshot v8 system-state count negative: " + systemStateCount);
        }
        if ((long) systemStateCount * SYSTEM_STATE_ENTITY_BYTES > data.available()) {
            throw new IOException(
                    "world snapshot v8 system-state count inconsistent with body length: " + systemStateCount);
        }
        Map<Long, int[]> systemByEntityId = new HashMap<>(systemStateCount * 2);
        for (int i = 0; i < systemStateCount; i++) {
            long entityId = WireBytes.readLong(data);
            int stateCode = WireBytes.readInt(data);
            int effectLevelBits = WireBytes.readInt(data); // float bits — converted at attach
            int cooldownRemainingBits = WireBytes.readInt(data);
            int ammo = WireBytes.readInt(data);
            systemByEntityId.put(entityId,
                    new int[] { stateCode, effectLevelBits, cooldownRemainingBits, ammo });
        }
        List<ServerEntityPose> withSystems = new ArrayList<>(poses.size());
        for (ServerEntityPose pose : poses) {
            int[] s = systemByEntityId.get(pose.entityId());
            withSystems.add(s == null ? pose : pose.withSystemState(
                    s[0], Float.intBitsToFloat(s[1]), Float.intBitsToFloat(s[2]), s[3]));
        }
        return withSystems;
    }

    /**
     * Read a trailing-section {@code int count} and skip its {@code count} fixed-width records of
     * {@code recordBytes} each, advancing {@code data} to the next section. Fail-closed on a
     * negative count or a section that overruns the remaining body.
     */
    private static void skipSection(ByteArrayInputStream data, int recordBytes) throws IOException {
        int count = WireBytes.readInt(data);
        if (count < 0) {
            throw new IOException("world snapshot v5 section count negative: " + count);
        }
        long sectionBytes = (long) count * recordBytes;
        if (sectionBytes > data.available()) {
            throw new IOException("world snapshot v5 section length exceeds frame body: " + count);
        }
        long skipped = data.skip(sectionBytes);
        if (skipped != sectionBytes) {
            throw new IOException("world snapshot v5 section truncated; could not skip " + sectionBytes);
        }
    }

    private static int readIntAfterFirstByte(InputStream in, int first) throws IOException {
        return ((first & 0xff) << 24)
                | (WireBytes.readUnsignedByte(in) << 16)
                | (WireBytes.readUnsignedByte(in) << 8)
                | WireBytes.readUnsignedByte(in);
    }
}
