package cc.starsector.server.runtime;

import cc.starsector.server.protocol.AuthoritativeEntitySnapshot;
import cc.starsector.server.protocol.AuthoritativeWorldSnapshot;
import cc.starsector.server.protocol.ContentDictionaryEntry;
import cc.starsector.server.protocol.NonShipEntitySnapshot;
import cc.starsector.server.protocol.ShipAllegianceSnapshot;
import cc.starsector.server.protocol.ShipCrSnapshot;
import cc.starsector.server.protocol.ShipEngineStateSnapshot;
import cc.starsector.server.protocol.ShipFluxSnapshot;
import cc.starsector.server.protocol.ShipFragmentMarkerSnapshot;
import cc.starsector.server.protocol.ShipManeuverCommandSnapshot;
import cc.starsector.server.protocol.ShipPhaseCloakStateSnapshot;
import cc.starsector.server.protocol.ShipProfileSnapshot;
import cc.starsector.server.protocol.ShipSystemStateSnapshot;
import cc.starsector.server.protocol.ShipWeaponMountSnapshot;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Snapshot chunker v1.5: split one tick's {@link AuthoritativeWorldSnapshot}
 * into K sub-snapshots whose encoded {@code AWSS} frames each fit a single datagram payload
 * (product cap = {@link DatagramFrameCodec#MAX_PAYLOAD_BYTES}; no fragmentation v1).
 *
 * <p>Law: selection-not-minting. A chunk carries a SUBSET of the tick's existing records —
 * ships partitioned greedily in stored ascending {@code entityId} order starting at rotation
 * offset {@code simTick % shipCount} (so over consecutive ticks every ship cycles through
 * every chunk position), each ship-scoped tier record riding in whichever chunk holds its
 * ship, and non-ship entities filling after the ships in stored order, each record in exactly
 * ONE chunk. AWSS sections are entityId-keyed and never index-cross-referenced, so a subset
 * frame is pure filtering — no record is invented, edited, or reordered, and the union of a
 * tick's chunks is exactly the original snapshot.
 *
 * <p>Fragmentation v2 amends
 * the atom from ENTITY to RECORD on exactly one path: a SHIP whose record set ALONE exceeds
 * the cap is split across fragment frames — ship anchor record repeated in every fragment
 * (anchor law), scalar tiers all in the anchor fragment, mounts greedily in ascending
 * {@code mountIndex} runs — each fragment declaring its partial carriage in the v9
 * FRAGMENT_MARKER tail tier ({@link ShipFragmentMarkerSnapshot}, ABSENT ≠ EMPTY semantics).
 * Selection-not-minting is preserved: every carried record is byte-identical to the source
 * record; only the ship anchor repeats (the sole disjoint-cover exception). Fragmentation is
 * compiled ON (hardwired — the snapFragmentation knob retired; the v1
 * {@code unitAloneOverCap} trip stays reachable through the test overloads only). Non-fragment
 * chunks carry no marker and stamp ≤ v8 — byte-identical to v1.5 output.
 *
 * <p>Chunk sizing is MEASURED, never budgeted off a formula (the f(N) per-ship formula is
 * a ships-only v7 estimate): every candidate chunk is minted and priced with
 * {@link AuthoritativeWorldSnapshotCodec#frameByteCount}, so the {@code <= cap} bound holds
 * exactly for whatever tier mix the chunk actually carries.
 *
 * <p>K=1 identity: a snapshot whose whole frame fits the cap is returned as-is (the same
 * object), so the encoded bytes are byte-identical to the unchunked path and the AWSS goldens
 * pin it for free.
 *
 * <p>Emission is compiled ON (hardwired — the snapChunking knob retired;
 * ON since the 2026-07-08 flip — client chunk reassembly BUILT
 * and scale-proven at N=31 with fragmentation): a chunk is a
 * self-consistent frame; the client merger rebuilds a tick's K chunks into one frame before
 * APPLY, so the wire death contract
 * ({@code PredictReconcileCombatPlugin.maintainRemoteEntities}) never sees a partial roster.
 * The OFF-diagnostic REFUSE path ({@link #assertFitsOrExplain} on an over-cap tick) left the
 * product path with the knob; it stays reachable through the test-facing overloads. Kits
 * minted BEFORE reassembly (pre {@code cc-joiner-kit-20260708-103126}) must not join this
 * always-ON server.
 */
public final class SnapshotChunker {

    private SnapshotChunker() {
    }

    /**
     * Chunked emission gate — compiled ON (E3 hardwire; the knob-resolved
     * shape is in git). Kept as a method so serve-start call sites and the
     * {@link #chunkForEmission} contract read unchanged.
     */
    public static boolean emissionEnabled() {
        return true;
    }

    /**
     * Fragmentation gate — compiled ON (E3 hardwire;
     * the knob-resolved shape is in git). The v1 {@code unitAloneOverCap} trip is no
     * longer reachable on the product path; contract tests restore it through the
     * {@code fragmentationEnabled=false} overloads.
     */
    public static boolean fragmentationEnabled() {
        return true;
    }

    /**
     * Floor of the {@code cc.net.snapPayloadCap} valid range. A cap below any single framed
     * entity is caught at serve time by the {@code unitAloneOverCap} tripwire (record widths
     * are measured, never static); this floor only rejects nonsense (zero/negative/sub-header
     * values) at resolve time.
     */
    public static final int MIN_PAYLOAD_CAP_BYTES = 64;

    /**
     * Resolve the {@code cc.net.snapPayloadCap} per-serve payload cap — a
     * lowered MTU floor for hostile tunnels (default = the wire constant
     * {@link DatagramFrameCodec#MAX_PAYLOAD_BYTES}, so unset = today's bytes exactly). Resolve
     * ONCE at serve start next to {@link #emissionEnabled()} and pass the int to
     * {@link #chunkForEmission} — not per tick. THROWS outside
     * [{@link #MIN_PAYLOAD_CAP_BYTES}, {@code MAX_PAYLOAD_BYTES}]: raising it past the datagram
     * MTU budget would mint frames the transport's own guard kills.
     */
    public static int payloadCapBytes() {
        int cap = ServerConfig.resolveInt(
                "cc.net.snapPayloadCap", "CC_NET_SNAP_PAYLOAD_CAP", "net.snapPayloadCap",
                DatagramFrameCodec.MAX_PAYLOAD_BYTES);
        if (cap < MIN_PAYLOAD_CAP_BYTES || cap > DatagramFrameCodec.MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("cc.net.snapPayloadCap must be in ["
                    + MIN_PAYLOAD_CAP_BYTES + ", " + DatagramFrameCodec.MAX_PAYLOAD_BYTES
                    + "]: got " + cap);
        }
        return cap;
    }

    /**
     * The serve-loop entry: K=1 identity when the frame fits; over-cap with chunking disabled
     * refuses via {@link #assertFitsOrExplain}; over-cap with chunking enabled splits, and
     * {@code fragmentationEnabled} selects what a single over-cap SHIP does on that path —
     * fragment (v2, spec) or the diagnostic v1 {@code unitAloneOverCap} trip.
     */
    public static List<AuthoritativeWorldSnapshot> chunkForEmission(
            AuthoritativeWorldSnapshot snapshot, int payloadCapBytes, boolean chunkingEnabled,
            boolean fragmentationEnabled) {
        Objects.requireNonNull(snapshot, "snapshot");
        requireCap(payloadCapBytes);
        if (AuthoritativeWorldSnapshotCodec.frameByteCount(snapshot) <= payloadCapBytes) {
            return List.of(snapshot);
        }
        if (!chunkingEnabled) {
            assertFitsOrExplain(snapshot, payloadCapBytes);
            return List.of(snapshot); // unreachable when over cap; total either way
        }
        return split(snapshot, payloadCapBytes, fragmentationEnabled);
    }

    /** v1 arity: fragmentation OFF (the trip). Product callers use the 4-arg overload. */
    public static List<AuthoritativeWorldSnapshot> chunkForEmission(
            AuthoritativeWorldSnapshot snapshot, int payloadCapBytes, boolean chunkingEnabled) {
        return chunkForEmission(snapshot, payloadCapBytes, chunkingEnabled, false);
    }

    /**
     * The named refusal (the S5 tripwire that lands ahead of any consumer): THROWS when the
     * snapshot's whole frame exceeds the payload cap, explaining the measured size, the knob
     * that is holding chunking OFF, and the projected ship count that WOULD fit one datagram
     * at this tick's measured bytes-per-ship (an estimate from real codec output — never the
     * f(N) formula).
     */
    public static void assertFitsOrExplain(
            AuthoritativeWorldSnapshot snapshot, int payloadCapBytes) {
        Objects.requireNonNull(snapshot, "snapshot");
        requireCap(payloadCapBytes);
        int frame = AuthoritativeWorldSnapshotCodec.frameByteCount(snapshot);
        if (frame <= payloadCapBytes) {
            return;
        }
        int ships = snapshot.entities().size();
        int nonShips = snapshot.nonShipEntities().size();
        int mounts = snapshot.weaponMounts().size();
        long projectedShips = ships == 0 ? 0L : (long) ships * payloadCapBytes / frame;
        throw new IllegalStateException(
                "AWSS frame over the datagram payload cap with snapshot chunking disabled"
                        + " (cc.net.snapChunking=false — explicitly set; default is ON since"
                        + " the D8 flip): frame " + frame
                        + " B > cap " + payloadCapBytes + " B at " + ships + " ship(s), "
                        + nonShips + " non-ship entity(ies), " + mounts
                        + " weapon-mount record(s). At this tick's measured bytes/ship ~"
                        + projectedShips + " ship(s) fit one datagram (estimate). Refusing"
                        + " rather than emitting a frame the peer transport kills; clear"
                        + " cc.net.snapChunking (or set it true) to chunk — clients"
                        + " reassemble chunked snapshots.");
    }

    /**
     * Split into the fewest greedy chunks with every chunk's encoded frame {@code <= cap}.
     * K=1 identity fast path returns the same object. With {@code fragmentationEnabled} a
     * single over-cap SHIP fragments; OFF — or a single over-cap NON-SHIP, whose
     * 28 B records never fragment — trips {@link IllegalStateException}.
     */
    public static List<AuthoritativeWorldSnapshot> chunk(
            AuthoritativeWorldSnapshot snapshot, int payloadCapBytes,
            boolean fragmentationEnabled) {
        Objects.requireNonNull(snapshot, "snapshot");
        requireCap(payloadCapBytes);
        if (AuthoritativeWorldSnapshotCodec.frameByteCount(snapshot) <= payloadCapBytes) {
            return List.of(snapshot);
        }
        return split(snapshot, payloadCapBytes, fragmentationEnabled);
    }

    /** v1 arity: fragmentation OFF (single over-cap entity trips — the diagnostic shape). */
    public static List<AuthoritativeWorldSnapshot> chunk(
            AuthoritativeWorldSnapshot snapshot, int payloadCapBytes) {
        return chunk(snapshot, payloadCapBytes, false);
    }

    private static List<AuthoritativeWorldSnapshot> split(
            AuthoritativeWorldSnapshot snapshot, int payloadCapBytes,
            boolean fragmentationEnabled) {
        List<AuthoritativeEntitySnapshot> ships = snapshot.entities();
        List<NonShipEntitySnapshot> nonShips = snapshot.nonShipEntities();
        int shipCount = ships.size();
        int offset = shipCount == 0 ? 0 : Math.floorMod(snapshot.simTick(), shipCount);
        List<AuthoritativeWorldSnapshot> chunks = new ArrayList<>();
        Set<Long> bucketShipIds = new HashSet<>();
        List<NonShipEntitySnapshot> bucketNonShips = new ArrayList<>();
        // When a ship fragments, its LAST fragment stays open as the current bucket and
        // greedy packing continues into its slack (co-riders, unmarked = whole). The open
        // fragment is described entirely by its marker: bucketTail != null means the bucket
        // additionally carries the marked ship's anchor + the marker's masked records.
        ShipFragmentMarkerSnapshot bucketTail = null;
        AuthoritativeWorldSnapshot bucket = null;
        int unitCount = shipCount + nonShips.size();
        for (int u = 0; u < unitCount; u++) {
            boolean isShip = u < shipCount;
            AuthoritativeEntitySnapshot ship =
                    isShip ? ships.get((offset + u) % shipCount) : null;
            NonShipEntitySnapshot nonShip = isShip ? null : nonShips.get(u - shipCount);
            if (isShip) {
                bucketShipIds.add(ship.entityId());
            } else {
                bucketNonShips.add(nonShip);
            }
            AuthoritativeWorldSnapshot candidate =
                    mintChunk(snapshot, bucketShipIds, bucketNonShips, bucketTail,
                            chunks.isEmpty() ? snapshot.contentDictionary() : List.of());
            if (AuthoritativeWorldSnapshotCodec.frameByteCount(candidate) <= payloadCapBytes) {
                bucket = candidate;
                continue;
            }
            if (bucket == null) {
                // bucketTail is null here: an open fragment bucket is always non-null.
                if (fragmentationEnabled && isShip) {
                    bucketShipIds.remove(ship.entityId());
                    bucketTail = fragmentShip(snapshot, ship, payloadCapBytes, chunks);
                    bucket = mintChunk(snapshot, bucketShipIds, bucketNonShips, bucketTail,
                            chunks.isEmpty() ? snapshot.contentDictionary() : List.of());
                    continue;
                }
                throw unitAloneOverCap(
                        candidate, payloadCapBytes, isShip, ship, nonShip, fragmentationEnabled);
            }
            chunks.add(bucket);
            bucketShipIds.clear();
            bucketNonShips.clear();
            bucketTail = null;
            if (isShip) {
                bucketShipIds.add(ship.entityId());
            } else {
                bucketNonShips.add(nonShip);
            }
            candidate = mintChunk(snapshot, bucketShipIds, bucketNonShips, null,
                    chunks.isEmpty() ? snapshot.contentDictionary() : List.of());
            if (AuthoritativeWorldSnapshotCodec.frameByteCount(candidate) > payloadCapBytes) {
                if (fragmentationEnabled && isShip) {
                    bucketShipIds.remove(ship.entityId());
                    bucketTail = fragmentShip(snapshot, ship, payloadCapBytes, chunks);
                    bucket = mintChunk(snapshot, bucketShipIds, bucketNonShips, bucketTail,
                            chunks.isEmpty() ? snapshot.contentDictionary() : List.of());
                    continue;
                }
                throw unitAloneOverCap(
                        candidate, payloadCapBytes, isShip, ship, nonShip, fragmentationEnabled);
            }
            bucket = candidate;
        }
        if (bucket != null) {
            chunks.add(bucket);
        }
        // The bound is asserted EXACTLY on every emitted chunk (belt-and-braces over the
        // greedy accept above): each chunk re-priced by the codec, never by a formula.
        for (AuthoritativeWorldSnapshot chunk : chunks) {
            int frame = AuthoritativeWorldSnapshotCodec.frameByteCount(chunk);
            if (frame > payloadCapBytes) {
                throw new IllegalStateException("AWSS chunker invariant broken: emitted chunk "
                        + frame + " B > cap " + payloadCapBytes + " B");
            }
        }
        verifyFragmentInvariants(chunks);
        return List.copyOf(chunks);
    }

    /**
     * Fragment one over-cap ship: canonical record order — ship anchor, all scalar
     * tier records in the ANCHOR fragment (whose marker asserts ALL scalar tiers, EMPTY
     * assertions included), then mounts in ascending {@code mountIndex} runs, every candidate
     * frame MEASURED via {@code frameByteCount} including its marker record. Closed fragments
     * are appended to {@code chunks}; the LAST fragment is NOT appended — its marker is
     * returned so the caller keeps it open as the current bucket (co-rider slack). Range
     * bounds follow the tiling law: first mount-bearing fragment {@code from=MIN_VALUE}, last
     * {@code to=MAX_VALUE}, interior bound = the next fragment's first carried mountIndex.
     */
    private static ShipFragmentMarkerSnapshot fragmentShip(
            AuthoritativeWorldSnapshot source, AuthoritativeEntitySnapshot ship,
            int payloadCapBytes, List<AuthoritativeWorldSnapshot> chunks) {
        long id = ship.entityId();
        List<ShipWeaponMountSnapshot> eMounts = new ArrayList<>();
        for (ShipWeaponMountSnapshot mount : source.weaponMounts()) {
            if (mount.entityId() == id) {
                eMounts.add(mount); // stored order = ascending mountIndex within the entity
            }
        }
        int total = eMounts.size();
        Set<Long> noCoRiders = Set.of();
        List<NonShipEntitySnapshot> noNonShips = List.of();

        // Anchor fragment. Floor guard first: anchor + scalars + marker is the smallest legal
        // anchor fragment; over cap means no legal fragmentation exists at this cap.
        ShipFragmentMarkerSnapshot anchorMarker = new ShipFragmentMarkerSnapshot(
                id, ShipFragmentMarkerSnapshot.ALL_SCALAR_TIERS, 0, 0);
        AuthoritativeWorldSnapshot anchor =
                mintChunk(source, noCoRiders, noNonShips, anchorMarker,
                        chunks.isEmpty() ? source.contentDictionary() : List.of());
        int anchorBase = AuthoritativeWorldSnapshotCodec.frameByteCount(anchor);
        if (anchorBase > payloadCapBytes) {
            throw fragmentFloorOverCap(id, true, anchorBase, payloadCapBytes);
        }
        int consumed = 0;
        while (consumed < total) {
            int tryCount = consumed + 1;
            int to = tryCount < total ? eMounts.get(tryCount).mountIndex() : Integer.MAX_VALUE;
            ShipFragmentMarkerSnapshot marker = new ShipFragmentMarkerSnapshot(id,
                    ShipFragmentMarkerSnapshot.ALL_SCALAR_TIERS
                            | ShipFragmentMarkerSnapshot.TIER_WEAPON_MOUNT,
                    Integer.MIN_VALUE, to);
            AuthoritativeWorldSnapshot candidate =
                    mintChunk(source, noCoRiders, noNonShips, marker,
                            chunks.isEmpty() ? source.contentDictionary() : List.of());
            if (AuthoritativeWorldSnapshotCodec.frameByteCount(candidate) > payloadCapBytes) {
                break;
            }
            anchor = candidate;
            anchorMarker = marker;
            consumed = tryCount;
        }
        if (consumed == total) {
            // Defensive: everything fit one marker-bearing frame (unreachable when the whole
            // ship was over cap — the marker only ADDS bytes). Keep it open as the bucket.
            return anchorMarker;
        }
        chunks.add(anchor);

        // Continuation fragments: mounts only, ship anchor repeated per the anchor law.
        while (true) {
            int from = consumed == 0 ? Integer.MIN_VALUE : eMounts.get(consumed).mountIndex();
            AuthoritativeWorldSnapshot fragment = null;
            ShipFragmentMarkerSnapshot fragmentMarker = null;
            int floorBytes = -1;
            int c = consumed;
            while (c < total) {
                int tryCount = c + 1;
                int to = tryCount < total ? eMounts.get(tryCount).mountIndex()
                        : Integer.MAX_VALUE;
                ShipFragmentMarkerSnapshot marker = new ShipFragmentMarkerSnapshot(
                        id, ShipFragmentMarkerSnapshot.TIER_WEAPON_MOUNT, from, to);
                AuthoritativeWorldSnapshot candidate =
                        mintChunk(source, noCoRiders, noNonShips, marker,
                                chunks.isEmpty() ? source.contentDictionary() : List.of());
                int bytes = AuthoritativeWorldSnapshotCodec.frameByteCount(candidate);
                if (tryCount == consumed + 1) {
                    floorBytes = bytes;
                }
                if (bytes > payloadCapBytes) {
                    break;
                }
                fragment = candidate;
                fragmentMarker = marker;
                c = tryCount;
            }
            if (fragment == null) {
                // Not even one mount fits beside the anchor + marker: the continuation floor.
                throw fragmentFloorOverCap(id, false, floorBytes, payloadCapBytes);
            }
            if (c == total) {
                return fragmentMarker; // the open LAST fragment (to == MAX_VALUE by the law)
            }
            chunks.add(fragment);
            consumed = c;
        }
    }

    /**
     * Splitter-owned cross-frame invariant (verifier-checked again at Part E): per
     * fragmented entity, every marker-bearing frame carries the entity's anchor ship record;
     * each scalar tier bit is asserted by exactly ONE fragment and the scalar union is ALL
     * tiers; mount ranges tile ℤ in emission order (first {@code from=MIN_VALUE}, last
     * {@code to=MAX_VALUE}, adjacent bounds equal); the entity's fragments occupy contiguous
     * chunk positions (rotation law).
     */
    private static void verifyFragmentInvariants(List<AuthoritativeWorldSnapshot> chunks) {
        Map<Long, List<ShipFragmentMarkerSnapshot>> markersByEntity = new LinkedHashMap<>();
        Map<Long, Integer> lastChunkIndex = new LinkedHashMap<>();
        for (int i = 0; i < chunks.size(); i++) {
            AuthoritativeWorldSnapshot chunk = chunks.get(i);
            for (ShipFragmentMarkerSnapshot marker : chunk.fragmentMarkers()) {
                boolean anchored = false;
                for (AuthoritativeEntitySnapshot entity : chunk.entities()) {
                    if (entity.entityId() == marker.entityId()) {
                        anchored = true;
                        break;
                    }
                }
                if (!anchored) {
                    throw new IllegalStateException("AWSS fragmenter invariant broken: fragment"
                            + " frame lacks its anchor ship record for entityId="
                            + marker.entityId());
                }
                Integer prior = lastChunkIndex.put(marker.entityId(), i);
                if (prior != null && prior != i - 1) {
                    throw new IllegalStateException("AWSS fragmenter invariant broken:"
                            + " fragments of entityId=" + marker.entityId()
                            + " are not contiguous (chunk " + prior + " then " + i + ")");
                }
                markersByEntity.computeIfAbsent(
                        marker.entityId(), k -> new ArrayList<>()).add(marker);
            }
        }
        for (Map.Entry<Long, List<ShipFragmentMarkerSnapshot>> entry
                : markersByEntity.entrySet()) {
            long id = entry.getKey();
            List<ShipFragmentMarkerSnapshot> markers = entry.getValue();
            int scalarUnion = 0;
            List<ShipFragmentMarkerSnapshot> mountMarkers = new ArrayList<>();
            for (ShipFragmentMarkerSnapshot marker : markers) {
                int scalars =
                        marker.tierMask() & ShipFragmentMarkerSnapshot.ALL_SCALAR_TIERS;
                if ((scalarUnion & scalars) != 0) {
                    throw new IllegalStateException("AWSS fragmenter invariant broken: a scalar"
                            + " tier is asserted by two fragments of entityId=" + id);
                }
                scalarUnion |= scalars;
                if ((marker.tierMask() & ShipFragmentMarkerSnapshot.TIER_WEAPON_MOUNT) != 0) {
                    mountMarkers.add(marker);
                }
            }
            if (scalarUnion != ShipFragmentMarkerSnapshot.ALL_SCALAR_TIERS
                    || mountMarkers.isEmpty()) {
                throw new IllegalStateException("AWSS fragmenter invariant broken: tier-mask"
                        + " union incomplete for entityId=" + id + " (scalars=0x"
                        + Integer.toHexString(scalarUnion) + ", mountFragments="
                        + mountMarkers.size() + ")");
            }
            if (mountMarkers.get(0).mountFrom() != Integer.MIN_VALUE
                    || mountMarkers.get(mountMarkers.size() - 1).mountTo()
                            != Integer.MAX_VALUE) {
                throw new IllegalStateException("AWSS fragmenter invariant broken: mount range"
                        + " does not span MIN..MAX for entityId=" + id);
            }
            for (int m = 0; m + 1 < mountMarkers.size(); m++) {
                if (mountMarkers.get(m).mountTo() != mountMarkers.get(m + 1).mountFrom()) {
                    throw new IllegalStateException("AWSS fragmenter invariant broken: mount"
                            + " ranges do not tile for entityId=" + id + " (to="
                            + mountMarkers.get(m).mountTo() + " != next from="
                            + mountMarkers.get(m + 1).mountFrom() + ")");
                }
            }
        }
    }

    /**
     * Mint the chunk holding {@code shipIds} + {@code chunkNonShips}: ships and every
     * ship-scoped tier filtered from the source in stored (ascending) order — the same
     * retained-id idiom as {@link AuthoritativeWorldSnapshot#relevantFor}. The never-encoded
     * Stage-A profile tier rides along so an in-memory consumer sees the same tier rules.
     *
     * <p>A non-null {@code fragment} overlays one PARTIALLY carried ship: its
     * anchor ship record always rides (anchor law), its scalar tier records (profile
     * included, in-memory rule) ride iff the marker asserts any scalar bit — the splitter
     * asserts scalars all-or-nothing — and its mounts ride iff the WEAPON_MOUNT bit is set,
     * bounded to {@code mountIndex ∈ [mountFrom, mountTo)}. Filtering the source lists in
     * stored order keeps every section ascending with the fragment ship merged in place.
     *
     * <p>Content-dictionary tier: frame-global, entity-independent —
     * carried by whichever chunk is minted while {@code chunks} is still empty (chunk #0
     * ONLY, off the per-fragment byte floor), so every mint site passes
     * {@code chunks.isEmpty() ? source.contentDictionary() : List.of()}. Dict bytes are
     * included at MINT time so measured sizing and the Part E union==original invariant
     * both hold by construction.
     */
    private static AuthoritativeWorldSnapshot mintChunk(
            AuthoritativeWorldSnapshot source,
            Set<Long> shipIds,
            List<NonShipEntitySnapshot> chunkNonShips,
            ShipFragmentMarkerSnapshot fragment,
            List<ContentDictionaryEntry> contentDictionary) {
        long fragId = fragment == null ? 0L : fragment.entityId();
        boolean fragScalars = fragment != null
                && (fragment.tierMask() & ShipFragmentMarkerSnapshot.ALL_SCALAR_TIERS) != 0;
        boolean fragMounts = fragment != null
                && (fragment.tierMask() & ShipFragmentMarkerSnapshot.TIER_WEAPON_MOUNT) != 0;
        List<AuthoritativeEntitySnapshot> ships = new ArrayList<>(shipIds.size());
        for (AuthoritativeEntitySnapshot entity : source.entities()) {
            if (shipIds.contains(entity.entityId()) || entity.entityId() == fragId) {
                ships.add(entity);
            }
        }
        List<ShipFluxSnapshot> flux = new ArrayList<>();
        for (ShipFluxSnapshot state : source.fluxStates()) {
            if (shipIds.contains(state.entityId())
                    || (fragScalars && state.entityId() == fragId)) {
                flux.add(state);
            }
        }
        List<ShipWeaponMountSnapshot> mounts = new ArrayList<>();
        for (ShipWeaponMountSnapshot mount : source.weaponMounts()) {
            if (shipIds.contains(mount.entityId())
                    || (fragMounts && mount.entityId() == fragId
                            && mount.mountIndex() >= fragment.mountFrom()
                            && mount.mountIndex() < fragment.mountTo())) {
                mounts.add(mount);
            }
        }
        List<ShipProfileSnapshot> profiles = new ArrayList<>();
        for (ShipProfileSnapshot profile : source.profileStates()) {
            if (shipIds.contains(profile.entityId())
                    || (fragScalars && profile.entityId() == fragId)) {
                profiles.add(profile);
            }
        }
        List<ShipAllegianceSnapshot> allegiances = new ArrayList<>();
        for (ShipAllegianceSnapshot allegiance : source.allegianceStates()) {
            if (shipIds.contains(allegiance.entityId())
                    || (fragScalars && allegiance.entityId() == fragId)) {
                allegiances.add(allegiance);
            }
        }
        List<ShipEngineStateSnapshot> engineStates = new ArrayList<>();
        for (ShipEngineStateSnapshot engineState : source.engineStates()) {
            if (shipIds.contains(engineState.entityId())
                    || (fragScalars && engineState.entityId() == fragId)) {
                engineStates.add(engineState);
            }
        }
        List<ShipManeuverCommandSnapshot> maneuvers = new ArrayList<>();
        for (ShipManeuverCommandSnapshot maneuver : source.maneuverCommands()) {
            if (shipIds.contains(maneuver.entityId())
                    || (fragScalars && maneuver.entityId() == fragId)) {
                maneuvers.add(maneuver);
            }
        }
        List<ShipSystemStateSnapshot> systemStates = new ArrayList<>();
        for (ShipSystemStateSnapshot systemState : source.systemStates()) {
            if (shipIds.contains(systemState.entityId())
                    || (fragScalars && systemState.entityId() == fragId)) {
                systemStates.add(systemState);
            }
        }
        // Phase-cloak tier: NO fragment-marker bit exists for this tier
        // (TIER_MASK_BITS stays 0x3F), so a fragmented ship's cloak record deliberately has
        // no fragScalars leg — no fragment frame can ASSERT the tier, and carrying the
        // record unassertable would only waste the fragment's byte budget. The client
        // HOLDS-LAST for a fragmented cloak ship (tier javadoc); whole co-rider ships
        // carry their cloak record normally via the shipIds filter.
        List<ShipPhaseCloakStateSnapshot> phaseCloaks = new ArrayList<>();
        for (ShipPhaseCloakStateSnapshot phaseCloak : source.phaseCloakStates()) {
            if (shipIds.contains(phaseCloak.entityId())) {
                phaseCloaks.add(phaseCloak);
            }
        }
        // Ship-CR tier: like the cloak tier, NO fragment-marker bit exists
        // for it (TIER_MASK_BITS stays 0x3F), so there is deliberately no fragScalars
        // leg — a fragmented ship's CR record HOLDS-LAST client-side until its next
        // whole-entity frame; whole co-rider ships carry theirs via the shipIds filter.
        List<ShipCrSnapshot> crStates = new ArrayList<>();
        for (ShipCrSnapshot crState : source.crStates()) {
            if (shipIds.contains(crState.entityId())) {
                crStates.add(crState);
            }
        }
        // Component-named builder: every tier is set explicitly. When a
        // new tier is added, this site needs its filter loop + a named set — a missed tier is
        // then ABSENT from every chunk, which the decode-verifier Part E union==original row
        // catches; the old positional full-args call would have silently rebound to a
        // back-compat constructor with the same drop and no compile signal.
        return new AuthoritativeWorldSnapshot.Builder()
                .simTick(source.simTick())
                .entities(ships)
                .nonShipEntities(chunkNonShips)
                .fluxStates(flux)
                .weaponMounts(mounts)
                .profileStates(profiles)
                .allegianceStates(allegiances)
                .engineStates(engineStates)
                .maneuverCommands(maneuvers)
                .systemStates(systemStates)
                .fragmentMarkers(fragment == null ? List.of() : List.of(fragment))
                .phaseCloakStates(phaseCloaks)
                .crStates(crStates)
                .contentDictionary(contentDictionary)
                .build();
    }

    private static IllegalStateException unitAloneOverCap(
            AuthoritativeWorldSnapshot candidate,
            int payloadCapBytes,
            boolean isShip,
            AuthoritativeEntitySnapshot ship,
            NonShipEntitySnapshot nonShip,
            boolean fragmentationEnabled) {
        String label = isShip
                ? "ship entityId=" + ship.entityId()
                : "non-ship networkId=" + nonShip.networkId();
        String why = isShip
                ? " fragmentation v2 is OFF for this split (cc.net.snapFragmentation=false —"
                        + " the diagnostic v1 trip); ON splits this ship's tier records across"
                        + " marker-declared fragment frames."
                : " non-ship records never fragment (fragmentation v2 covers ship-scoped"
                        + " tiers only" + (fragmentationEnabled ? "" : "; the knob is also OFF")
                        + ").";
        return new IllegalStateException("AWSS chunker tripwire: " + label + " alone encodes "
                + AuthoritativeWorldSnapshotCodec.frameByteCount(candidate) + " B > payload cap "
                + payloadCapBytes + " B — a single entity's record set no longer fits one"
                + " datagram;" + why);
    }

    /**
     * The fragmentation floor guard: even the smallest legal fragment —
     * anchor + scalar tiers + marker for the anchor fragment, anchor + marker + one mount
     * record for a continuation — exceeds the cap. A loud post-admission config error
     * (physical floor ≈ 196 B at the current schema), NOT a packing failure;
     * {@code MIN_PAYLOAD_CAP_BYTES} stays 64 by design (a consequence of the datagram-budget
     * decision, corrected: the floor is MEASURED per entity, never a constant).
     */
    private static IllegalStateException fragmentFloorOverCap(
            long entityId, boolean anchorFragment, int measuredBytes, int payloadCapBytes) {
        return new IllegalStateException("AWSS fragmenter fragmentFloorOverCap: ship entityId="
                + entityId + " smallest legal "
                + (anchorFragment
                        ? "anchor fragment (ship record + scalar tiers + marker)"
                        : "continuation fragment (ship record + marker + one mount record)")
                + " measures " + measuredBytes + " B > payload cap " + payloadCapBytes
                + " B — the cap sits below this entity's measured fragment floor. Loud config"
                + " error: raise cc.net.snapPayloadCap (fragmentation cannot shrink a single"
                + " record).");
    }

    private static void requireCap(int payloadCapBytes) {
        if (payloadCapBytes <= 0) {
            throw new IllegalArgumentException(
                    "payloadCapBytes must be > 0; got " + payloadCapBytes);
        }
    }
}
