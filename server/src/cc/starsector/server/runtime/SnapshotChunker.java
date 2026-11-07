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
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToLongFunction;

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
 * <p>Chunk sizing is EXACT, never budgeted off a per-ship
 * estimate: a candidate is priced by the codec's own size ladder over the candidate's census
 * ({@code AuthoritativeWorldSnapshotCodec.FrameCensus}) — counts add and value flags OR over the
 * units' disjoint record sets, and bytes are always evaluated from the WHOLE census, never
 * summed per unit (one flag, or the first record of a tier, widens records and forces sections
 * already in the bucket). Each chunk is minted ONCE, when it closes, from a per-ship run index
 * built once per split; fragment candidates are still minted and measured one at a time. Every
 * emitted chunk is re-measured against the cap, which guards the cap, not byte identity: that
 * the chunk lists equal the measure-every-candidate packer's, byte for byte, is proven by the
 * frozen-packer oracle contract, which prices every candidate with its own frozen size walk.
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
        return split(snapshot, payloadCapBytes, fragmentationEnabled, false);
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
     * 28 B records never fragment — trips {@link IllegalStateException}. This test-facing
     * overload additionally ASSERTS record coverage over the emitted chunks (every unit and
     * every ship-scoped record emitted exactly once, minus the documented fragment
     * exclusions); the serve path ({@link #chunkForEmission}) skips that check.
     */
    public static List<AuthoritativeWorldSnapshot> chunk(
            AuthoritativeWorldSnapshot snapshot, int payloadCapBytes,
            boolean fragmentationEnabled) {
        Objects.requireNonNull(snapshot, "snapshot");
        requireCap(payloadCapBytes);
        if (AuthoritativeWorldSnapshotCodec.frameByteCount(snapshot) <= payloadCapBytes) {
            return List.of(snapshot);
        }
        return split(snapshot, payloadCapBytes, fragmentationEnabled, true);
    }

    /** v1 arity: fragmentation OFF (single over-cap entity trips — the diagnostic shape). */
    public static List<AuthoritativeWorldSnapshot> chunk(
            AuthoritativeWorldSnapshot snapshot, int payloadCapBytes) {
        return chunk(snapshot, payloadCapBytes, false);
    }

    /**
     * Greedy split over units — ships in rotation from
     * {@code simTick % shipCount}, then non-ships in stored order. The open bucket is always a
     * CONTIGUOUS unit range {@code [wholeFrom, u)}, optionally preceded by one fragment tail (the
     * ship unit just before it, partially carried per its marker): units are consumed in order
     * and every bucket re-opens at the current unit. A unit joins iff the candidate's census
     * (open census + the unit's records) prices within the cap; otherwise the bucket closes —
     * minted once, here — and the unit re-opens a bucket alone. A unit over the cap ALONE (plus
     * the dictionary while no chunk has closed) fragments when it is a ship and fragmentation is
     * on, else trips {@link #unitAloneOverCap}. The dictionary rides the bucket open while no
     * chunk has closed, so it is constant for a bucket's whole life.
     */
    private static List<AuthoritativeWorldSnapshot> split(
            AuthoritativeWorldSnapshot snapshot, int payloadCapBytes,
            boolean fragmentationEnabled, boolean assertCoverage) {
        ShipRuns runs = ShipRuns.of(snapshot);
        int shipCount = runs.shipCount;
        int unitCount = shipCount + snapshot.nonShipEntities().size();
        List<AuthoritativeWorldSnapshot> chunks = new ArrayList<>();
        AuthoritativeWorldSnapshotCodec.FrameCensus open =
                new AuthoritativeWorldSnapshotCodec.FrameCensus();
        AuthoritativeWorldSnapshotCodec.FrameCensus candidate =
                new AuthoritativeWorldSnapshotCodec.FrameCensus();
        open.setDictionary(snapshot.contentDictionary()); // no chunk has closed yet
        boolean bucketOpen = false;
        // When a ship fragments, its LAST fragment stays open as the current bucket and
        // greedy packing continues into its slack (co-riders, unmarked = whole). The open
        // fragment is described entirely by its marker: tail != null means the bucket
        // additionally carries unit tailUnit's anchor + the marker's masked records.
        ShipFragmentMarkerSnapshot tail = null;
        int tailUnit = -1;
        int wholeFrom = 0;
        for (int u = 0; u < unitCount; u++) {
            candidate.copyFrom(open);
            runs.addUnit(candidate, snapshot, u);
            int bytes = candidate.frameBytes();
            if (bytes <= payloadCapBytes) {
                AuthoritativeWorldSnapshotCodec.FrameCensus accepted = candidate;
                candidate = open;
                open = accepted;
                bucketOpen = true;
                continue;
            }
            if (bucketOpen) {
                chunks.add(mintChunk(
                        snapshot, runs, tailUnit, tail, wholeFrom, u, chunks.isEmpty()));
                tail = null;
                tailUnit = -1;
                wholeFrom = u;
                open.clear(); // a chunk has closed: no dictionary from here on
                candidate.copyFrom(open);
                runs.addUnit(candidate, snapshot, u);
                bytes = candidate.frameBytes();
                if (bytes <= payloadCapBytes) {
                    AuthoritativeWorldSnapshotCodec.FrameCensus accepted = candidate;
                    candidate = open;
                    open = accepted;
                    continue;
                }
            }
            boolean isShip = u < shipCount;
            if (fragmentationEnabled && isShip) {
                tail = fragmentShip(snapshot, runs, u, payloadCapBytes, chunks);
                tailUnit = u;
                wholeFrom = u + 1;
                // Rare path: the open census is measured off the tail-only mint itself.
                open.copyFrom(AuthoritativeWorldSnapshotCodec.FrameCensus.of(mintChunk(
                        snapshot, runs, tailUnit, tail, wholeFrom, wholeFrom, chunks.isEmpty())));
                bucketOpen = true;
                continue;
            }
            throw unitAloneOverCap(bytes, payloadCapBytes, isShip,
                    isShip ? snapshot.entities().get(runs.position(u)) : null,
                    isShip ? null : snapshot.nonShipEntities().get(u - shipCount),
                    fragmentationEnabled);
        }
        if (bucketOpen) {
            chunks.add(mintChunk(
                    snapshot, runs, tailUnit, tail, wholeFrom, unitCount, chunks.isEmpty()));
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
        if (assertCoverage) {
            requireCoverage(snapshot, runs, chunks);
        }
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
     * Every candidate is a tail-only mint (no co-riders), so each mint costs one ship's runs.
     */
    private static ShipFragmentMarkerSnapshot fragmentShip(
            AuthoritativeWorldSnapshot source, ShipRuns runs, int unit,
            int payloadCapBytes, List<AuthoritativeWorldSnapshot> chunks) {
        int position = runs.position(unit);
        long id = source.entities().get(position).entityId();
        // The ship's own mount run: stored order = ascending mountIndex within the entity.
        List<ShipWeaponMountSnapshot> eMounts = source.weaponMounts().subList(
                runs.lo[ShipRuns.MOUNT][position], runs.hi[ShipRuns.MOUNT][position]);
        int total = eMounts.size();

        // Anchor fragment. Floor guard first: anchor + scalars + marker is the smallest legal
        // anchor fragment; over cap means no legal fragmentation exists at this cap.
        ShipFragmentMarkerSnapshot anchorMarker = new ShipFragmentMarkerSnapshot(
                id, ShipFragmentMarkerSnapshot.ALL_SCALAR_TIERS, 0, 0);
        AuthoritativeWorldSnapshot anchor = mintChunk(
                source, runs, unit, anchorMarker, unit + 1, unit + 1, chunks.isEmpty());
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
            AuthoritativeWorldSnapshot candidate = mintChunk(
                    source, runs, unit, marker, unit + 1, unit + 1, chunks.isEmpty());
            if (AuthoritativeWorldSnapshotCodec.frameByteCount(candidate) > payloadCapBytes) {
                break;
            }
            anchor = candidate;
            anchorMarker = marker;
            consumed = tryCount;
        }
        if (consumed == total) {
            // Everything fit one marker-bearing frame: reachable when the ship's cloak + CR
            // records (never carried for a fragmented ship) outweigh the marker. Keep it open
            // as the bucket. A ZERO-mount ship lands here with a scalar-only marker, which
            // verifyFragmentInvariants then refuses (no mount fragment).
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
                AuthoritativeWorldSnapshot candidate = mintChunk(
                        source, runs, unit, marker, unit + 1, unit + 1, chunks.isEmpty());
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
     * Mint the chunk holding whole units
     * {@code [wholeFrom, wholeTo)} plus, when {@code fragment} is non-null, the partially
     * carried ship at unit {@code tailUnit} (always {@code wholeFrom - 1}). The ship units
     * {@code [first, min(wholeTo, shipCount))} map to one stored-position range, or two at the
     * rotation wrap; walking those positions in ASCENDING order and appending each ship's
     * per-tier record runs yields exactly the source lists filtered by ship membership in stored
     * order (every list is ascending by entityId, so a ship's records are one contiguous run and
     * orphan records belong to no run) — the same selection the retained-id idiom of
     * {@link AuthoritativeWorldSnapshot#relevantFor} makes. The never-encoded Stage-A profile
     * tier rides along so an in-memory consumer sees the same tier rules. Non-ships are the
     * stored-order slice of the non-ship units.
     *
     * <p>A non-null {@code fragment} overlays one PARTIALLY carried ship: its
     * anchor ship record always rides (anchor law), its scalar tier records (profile
     * included, in-memory rule) ride iff the marker asserts any scalar bit — the splitter
     * asserts scalars all-or-nothing — and its mounts ride iff the WEAPON_MOUNT bit is set,
     * bounded to {@code mountIndex ∈ [mountFrom, mountTo)}. Phase-cloak and CR records never
     * ride for the fragmented ship (below).
     *
     * <p>Content-dictionary tier: frame-global, entity-independent —
     * carried by whichever chunk is minted while {@code chunks} is still empty (chunk #0
     * ONLY, off the per-fragment byte floor), so every mint site passes
     * {@code chunks.isEmpty()}. Dict bytes are included at MINT time so measured sizing and the
     * Part E union==original invariant both hold by construction.
     */
    private static AuthoritativeWorldSnapshot mintChunk(
            AuthoritativeWorldSnapshot source,
            ShipRuns runs,
            int tailUnit,
            ShipFragmentMarkerSnapshot fragment,
            int wholeFrom,
            int wholeTo,
            boolean withDictionary) {
        int shipCount = runs.shipCount;
        int firstShipUnit = fragment == null ? wholeFrom : tailUnit;
        int[] positions = runs.positions(
                firstShipUnit, Math.max(0, Math.min(wholeTo, shipCount) - firstShipUnit));
        int fragPos = fragment == null ? -1 : runs.position(tailUnit);
        boolean fragScalars = fragment != null
                && (fragment.tierMask() & ShipFragmentMarkerSnapshot.ALL_SCALAR_TIERS) != 0;
        boolean fragMounts = fragment != null
                && (fragment.tierMask() & ShipFragmentMarkerSnapshot.TIER_WEAPON_MOUNT) != 0;
        List<AuthoritativeEntitySnapshot> ships = new ArrayList<>(positions.length);
        for (int p : positions) {
            ships.add(source.entities().get(p)); // the fragment ship's anchor always rides
        }
        List<ShipWeaponMountSnapshot> sourceMounts = source.weaponMounts();
        List<ShipWeaponMountSnapshot> mounts = new ArrayList<>();
        for (int p : positions) {
            int lo = runs.lo[ShipRuns.MOUNT][p];
            int hi = runs.hi[ShipRuns.MOUNT][p];
            for (int r = lo; r < hi; r++) {
                ShipWeaponMountSnapshot mount = sourceMounts.get(r);
                if (p != fragPos || (fragMounts
                        && mount.mountIndex() >= fragment.mountFrom()
                        && mount.mountIndex() < fragment.mountTo())) {
                    mounts.add(mount);
                }
            }
        }
        // Phase-cloak tier: NO fragment-marker bit exists for this tier
        // (TIER_MASK_BITS stays 0x3F), so a fragmented ship's cloak record deliberately has
        // no fragScalars leg — no fragment frame can ASSERT the tier, and carrying the
        // record unassertable would only waste the fragment's byte budget. The client
        // HOLDS-LAST for a fragmented cloak ship (tier javadoc); whole co-rider ships
        // carry their cloak record normally.
        // Ship-CR tier: like the cloak tier, NO fragment-marker bit exists
        // for it (TIER_MASK_BITS stays 0x3F), so there is deliberately no fragScalars
        // leg — a fragmented ship's CR record HOLDS-LAST client-side until its next
        // whole-entity frame; whole co-rider ships carry theirs.
        // Component-named builder: every tier is set explicitly.
        // A NEW ship-scoped tier needs four legs: its ShipRuns index, a gather here, its
        // FrameCensus count + size-ladder rung (whole-ship pricing in ShipRuns.addUnit), and
        // the same membership filter in the frozen oracle packer. A missed gather leaves the
        // tier ABSENT from every chunk, which the oracle's component-coverage and union rows and
        // the decode-verifier Part E union==original row catch.
        int nonShipFrom = Math.max(wholeFrom, shipCount) - shipCount;
        int nonShipTo = Math.max(wholeTo, shipCount) - shipCount;
        return new AuthoritativeWorldSnapshot.Builder()
                .simTick(source.simTick())
                .entities(ships)
                .fluxStates(gather(source.fluxStates(), runs, ShipRuns.FLUX, positions,
                        fragPos, fragScalars))
                .weaponMounts(mounts)
                .profileStates(gather(source.profileStates(), runs, ShipRuns.PROFILE, positions,
                        fragPos, fragScalars))
                .allegianceStates(gather(source.allegianceStates(), runs, ShipRuns.ALLEGIANCE,
                        positions, fragPos, fragScalars))
                .engineStates(gather(source.engineStates(), runs, ShipRuns.ENGINE, positions,
                        fragPos, fragScalars))
                .maneuverCommands(gather(source.maneuverCommands(), runs, ShipRuns.MANEUVER,
                        positions, fragPos, fragScalars))
                .systemStates(gather(source.systemStates(), runs, ShipRuns.SYSTEM, positions,
                        fragPos, fragScalars))
                .phaseCloakStates(gather(source.phaseCloakStates(), runs, ShipRuns.CLOAK,
                        positions, fragPos, false))
                .crStates(gather(source.crStates(), runs, ShipRuns.CR, positions, fragPos, false))
                .contentDictionary(withDictionary ? source.contentDictionary() : List.of())
                .nonShipEntities(source.nonShipEntities().subList(nonShipFrom, nonShipTo))
                .fragmentMarkers(fragment == null ? List.of() : List.of(fragment))
                .build();
    }

    /**
     * One ship-scoped tier's records for the chunk: each
     * position's run in ascending position order; the fragmented ship's run rides only when
     * {@code fragCarries}.
     */
    private static <T> List<T> gather(List<T> records, ShipRuns runs, int tier, int[] positions,
            int fragPos, boolean fragCarries) {
        int[] lo = runs.lo[tier];
        int[] hi = runs.hi[tier];
        int size = 0;
        for (int p : positions) {
            if (p != fragPos || fragCarries) {
                size += hi[p] - lo[p];
            }
        }
        if (size == 0) {
            return List.of();
        }
        List<T> out = new ArrayList<>(size);
        for (int p : positions) {
            if (p != fragPos || fragCarries) {
                for (int r = lo[p]; r < hi[p]; r++) {
                    out.add(records.get(r));
                }
            }
        }
        return out;
    }

    /**
     * Per-ship record runs, built once per split in
     * O(ships + records): for each ship-scoped tier, {@code [lo[tier][p], hi[tier][p])} is the
     * slice of that tier's source list whose entityId equals the ship at stored position
     * {@code p}. Every source list is strictly ascending by entityId (mounts by
     * (entityId, mountIndex)), enforced by the snapshot's canonical constructor, so one merge
     * walk per tier finds each run; records whose entityId names no ship (orphans) fall
     * between runs and ride no chunk. Unit {@code u < shipCount} is the ship at position
     * {@code (offset + u) % shipCount}.
     */
    private static final class ShipRuns {
        static final int FLUX = 0;
        static final int MOUNT = 1;
        static final int PROFILE = 2;
        static final int ALLEGIANCE = 3;
        static final int ENGINE = 4;
        static final int MANEUVER = 5;
        static final int SYSTEM = 6;
        static final int CLOAK = 7;
        static final int CR = 8;
        static final int TIERS = 9;

        final int shipCount;
        final int offset;
        final int[][] lo = new int[TIERS][];
        final int[][] hi = new int[TIERS][];
        /** Per tier, the records that belong to some ship (the source size minus orphans). */
        final int[] runTotals = new int[TIERS];

        private ShipRuns(int shipCount, int offset) {
            this.shipCount = shipCount;
            this.offset = offset;
        }

        static ShipRuns of(AuthoritativeWorldSnapshot source) {
            List<AuthoritativeEntitySnapshot> ships = source.entities();
            int shipCount = ships.size();
            ShipRuns runs = new ShipRuns(shipCount,
                    shipCount == 0 ? 0 : Math.floorMod(source.simTick(), shipCount));
            runs.index(FLUX, ships, source.fluxStates(), ShipFluxSnapshot::entityId);
            runs.index(MOUNT, ships, source.weaponMounts(), ShipWeaponMountSnapshot::entityId);
            runs.index(PROFILE, ships, source.profileStates(), ShipProfileSnapshot::entityId);
            runs.index(ALLEGIANCE, ships, source.allegianceStates(),
                    ShipAllegianceSnapshot::entityId);
            runs.index(ENGINE, ships, source.engineStates(), ShipEngineStateSnapshot::entityId);
            runs.index(MANEUVER, ships, source.maneuverCommands(),
                    ShipManeuverCommandSnapshot::entityId);
            runs.index(SYSTEM, ships, source.systemStates(), ShipSystemStateSnapshot::entityId);
            runs.index(CLOAK, ships, source.phaseCloakStates(),
                    ShipPhaseCloakStateSnapshot::entityId);
            runs.index(CR, ships, source.crStates(), ShipCrSnapshot::entityId);
            return runs;
        }

        private <T> void index(int tier, List<AuthoritativeEntitySnapshot> ships, List<T> records,
                ToLongFunction<T> entityId) {
            int[] tierLo = new int[shipCount];
            int[] tierHi = new int[shipCount];
            int n = records.size();
            int r = 0;
            for (int p = 0; p < shipCount; p++) {
                long id = ships.get(p).entityId();
                while (r < n && entityId.applyAsLong(records.get(r)) < id) {
                    r++; // orphan records below this ship
                }
                tierLo[p] = r;
                while (r < n && entityId.applyAsLong(records.get(r)) == id) {
                    r++;
                }
                tierHi[p] = r;
                runTotals[tier] += r - tierLo[p];
            }
            lo[tier] = tierLo;
            hi[tier] = tierHi;
        }

        int position(int unit) {
            return (offset + unit) % shipCount;
        }

        /** The stored positions of ship units {@code [firstUnit, firstUnit + n)}, ascending. */
        int[] positions(int firstUnit, int n) {
            int[] out = new int[n];
            if (n == 0) {
                return out;
            }
            int start = position(firstUnit);
            if (start + n <= shipCount) {
                for (int i = 0; i < n; i++) {
                    out[i] = start + i;
                }
            } else {
                int wrapped = start + n - shipCount; // positions [0, wrapped) come first
                for (int i = 0; i < wrapped; i++) {
                    out[i] = i;
                }
                for (int i = wrapped; i < n; i++) {
                    out[i] = start + (i - wrapped);
                }
            }
            return out;
        }

        /**
         * Adds unit {@code u}'s WHOLE record set to {@code census}: a ship's anchor plus every
         * encoded tier run (the profile tier costs no wire bytes), or one non-ship record.
         */
        void addUnit(AuthoritativeWorldSnapshotCodec.FrameCensus census,
                AuthoritativeWorldSnapshot source, int u) {
            if (u >= shipCount) {
                census.addNonShip(source.nonShipEntities().get(u - shipCount));
                return;
            }
            int p = position(u);
            census.entities++;
            List<ShipFluxSnapshot> flux = source.fluxStates();
            for (int r = lo[FLUX][p]; r < hi[FLUX][p]; r++) {
                census.addFlux(flux.get(r));
            }
            census.mounts += hi[MOUNT][p] - lo[MOUNT][p];
            census.allegiance += hi[ALLEGIANCE][p] - lo[ALLEGIANCE][p];
            census.engine += hi[ENGINE][p] - lo[ENGINE][p];
            census.maneuver += hi[MANEUVER][p] - lo[MANEUVER][p];
            census.system += hi[SYSTEM][p] - lo[SYSTEM][p];
            census.cloak += hi[CLOAK][p] - lo[CLOAK][p];
            census.cr += hi[CR][p] - lo[CR][p];
        }

        /** Stored position of the ship with {@code entityId} (binary search; -1 if none). */
        int positionOf(List<AuthoritativeEntitySnapshot> ships, long entityId) {
            int low = 0;
            int high = shipCount - 1;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                long id = ships.get(mid).entityId();
                if (id < entityId) {
                    low = mid + 1;
                } else if (id > entityId) {
                    high = mid - 1;
                } else {
                    return mid;
                }
            }
            return -1;
        }
    }

    /**
     * O(K) record-coverage assertion over the EMITTED
     * chunks, run by the test-facing {@link #chunk} overload only: Σ ship records minus the
     * repeated fragment anchors == ships; Σ non-ships == non-ships; per ship-scoped tier,
     * Σ records == the run totals minus the fragment exclusions (a fragmented ship's cloak and
     * CR records, and its mount at index {@code Integer.MAX_VALUE}, which the half-open last
     * range never carries). A unit dropped or duplicated by the unit-to-position mapping turns
     * every chunking test red here; the census arithmetic cannot see such a bug.
     */
    private static void requireCoverage(AuthoritativeWorldSnapshot source, ShipRuns runs,
            List<AuthoritativeWorldSnapshot> chunks) {
        long entities = 0;
        long nonShips = 0;
        long markers = 0;
        long[] tiers = new long[ShipRuns.TIERS];
        Set<Long> fragmented = new HashSet<>();
        for (AuthoritativeWorldSnapshot chunk : chunks) {
            entities += chunk.entityCount();
            nonShips += chunk.nonShipEntityCount();
            markers += chunk.fragmentMarkerCount();
            for (ShipFragmentMarkerSnapshot marker : chunk.fragmentMarkers()) {
                fragmented.add(marker.entityId());
            }
            tiers[ShipRuns.FLUX] += chunk.fluxStateCount();
            tiers[ShipRuns.MOUNT] += chunk.weaponMountCount();
            tiers[ShipRuns.PROFILE] += chunk.profileStates().size();
            tiers[ShipRuns.ALLEGIANCE] += chunk.allegianceStateCount();
            tiers[ShipRuns.ENGINE] += chunk.engineStateCount();
            tiers[ShipRuns.MANEUVER] += chunk.maneuverCommandCount();
            tiers[ShipRuns.SYSTEM] += chunk.systemStateCount();
            tiers[ShipRuns.CLOAK] += chunk.phaseCloakStateCount();
            tiers[ShipRuns.CR] += chunk.crStateCount();
        }
        long[] expected = new long[ShipRuns.TIERS];
        for (int t = 0; t < ShipRuns.TIERS; t++) {
            expected[t] = runs.runTotals[t];
        }
        for (long id : fragmented) {
            int p = runs.positionOf(source.entities(), id);
            if (p < 0) {
                throw new IllegalStateException("AWSS chunker coverage broken: a fragment marker"
                        + " names entityId=" + id + ", which is not a ship of this tick");
            }
            expected[ShipRuns.CLOAK] -= runs.hi[ShipRuns.CLOAK][p] - runs.lo[ShipRuns.CLOAK][p];
            expected[ShipRuns.CR] -= runs.hi[ShipRuns.CR][p] - runs.lo[ShipRuns.CR][p];
            int mountHi = runs.hi[ShipRuns.MOUNT][p];
            if (mountHi > runs.lo[ShipRuns.MOUNT][p]
                    && source.weaponMounts().get(mountHi - 1).mountIndex() == Integer.MAX_VALUE) {
                expected[ShipRuns.MOUNT]--;
            }
        }
        boolean unitsCovered = entities - markers + fragmented.size() == runs.shipCount
                && nonShips == source.nonShipEntityCount();
        boolean tiersCovered = Arrays.equals(tiers, expected);
        if (chunks.isEmpty() ? runs.shipCount + source.nonShipEntityCount() != 0
                : !(unitsCovered && tiersCovered)) {
            throw new IllegalStateException("AWSS chunker coverage broken: ships "
                    + (entities - markers + fragmented.size()) + "/" + runs.shipCount
                    + ", non-ships " + nonShips + "/" + source.nonShipEntityCount()
                    + ", tier records " + Arrays.toString(tiers) + " vs expected "
                    + Arrays.toString(expected) + " over " + chunks.size() + " chunk(s)");
        }
    }

    private static IllegalStateException unitAloneOverCap(
            int candidateBytes,
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
                + candidateBytes + " B > payload cap "
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
