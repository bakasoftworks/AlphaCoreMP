package cc.starsector.server.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Server-owned catalog that interns each ship's <em>stable</em> engine entity id
 * (the version-independent string returned by {@code ShipAPI.getId()})
 * into a stable numeric network {@code entityId} carried in
 * {@link cc.starsector.server.protocol.AuthoritativeEntitySnapshot}.
 *
 * <p>Why this exists: the engine-backed per-ship views
 * ({@link cc.starsector.server.adapter.ShipMotionView},
 * {@link cc.starsector.server.adapter.ShipDurabilityView},
 * {@link cc.starsector.server.adapter.ShipIdentityView}) derive their {@code shipId}
 * from the engine repository's <em>iteration position</em>. That position silently
 * renumbers the survivors whenever the roster's membership or order changes — when a
 * ship is destroyed and leaves combat, every ship after it shifts down one. Using the
 * position id as the client-facing {@code entityId} therefore breaks identity
 * continuity across any roster change (a client tracking entity 3 would see it
 * "teleport" onto entity 2's state). This registry maps the engine's stable id to a
 * network id assigned once, in first-seen order, so an {@code entityId} stays bound
 * to the same ship for that ship's whole combat lifetime regardless of how the roster
 * shrinks (death/removal) or grows (reinforcement).
 *
 * <p>Ids are assigned in first-seen order starting at 1 ({@code entityId} must be
 * {@code > 0}), so the mapping is deterministic for a given appearance order. Not
 * thread-safe: a single publisher owns one registry.
 */
public final class ShipNetworkIdRegistry {

    private final Map<String, Long> idByKey = new LinkedHashMap<>();
    private final long reservedCeiling;
    private long nextId;

    /** First-seen interning from 1 — the original behavior (no reserved band). */
    public ShipNetworkIdRegistry() {
        this(0L);
    }

    /**
     * reserved-band ctor: ids {@code [1, reservedCeiling]} are held for
     * {@link #internReserved} pre-claims (the match descriptor's seat wire ids, recorded
     * at roster deploy), so a seat's replicated {@code entityId} EQUALS its descriptor
     * {@code seat.ship} by construction rather than by first-seen deploy-order
     * coincidence. First-seen interning ({@link #networkIdFor}) starts at
     * {@code reservedCeiling + 1} — non-seat (AI-row) ships intern above the band
     * exactly as before. A ceiling of 0 is the original no-band behavior.
     */
    public ShipNetworkIdRegistry(long reservedCeiling) {
        if (reservedCeiling < 0L) {
            throw new IllegalArgumentException(
                    "reservedCeiling must be >= 0, got: " + reservedCeiling);
        }
        this.reservedCeiling = reservedCeiling;
        this.nextId = reservedCeiling + 1L;
    }

    /**
     * Pre-claim reserved id {@code id} ({@code [1, reservedCeiling]}) for {@code stableKey}.
     * Fail-closed: an id outside the reserved band, an already-interned key, or an
     * already-claimed id THROWS — a mis-seeded identity table must never publish
     * (the serve loop re-seeds a fresh registry every round because a
     * reset re-deploys ships with fresh stable ids).
     */
    public void internReserved(String stableKey, long id) {
        Objects.requireNonNull(stableKey, "stableKey");
        if (stableKey.isBlank()) {
            throw new IllegalArgumentException("stableKey must be non-blank");
        }
        if (id < 1L || id > reservedCeiling) {
            throw new IllegalArgumentException("reserved id " + id
                    + " outside the reserved band [1, " + reservedCeiling + "]");
        }
        if (idByKey.containsKey(stableKey)) {
            throw new IllegalArgumentException(
                    "stableKey already interned: " + stableKey + " -> " + idByKey.get(stableKey));
        }
        if (idByKey.containsValue(id)) {
            throw new IllegalArgumentException("reserved id already claimed: " + id);
        }
        idByKey.put(stableKey, id);
    }

    /**
     * The fly-in MOUNT intern — the ONE reserved-band surface the serve loops'
     * fly-in sites use, covering BOTH the round's first mount (T1: {@code id} unclaimed — a plain
     * reserved intern) and a mid-round remount (T4: {@code id} still claimed by the DEAD hull's
     * stable id from the round pre-seed / first mount — the id RE-POINTS to the fresh hull's
     * stable id, releasing the dead key's mapping). {@link #internReserved} stays fail-closed both
     * ways for the pre-seed path (a mis-seeded table must never publish); this surface relaxes
     * EXACTLY the already-claimed-id refusal, because a remount at the SAME wire id is the
     * seat-identity law working as designed (the seat's replicated
     * {@code entityId} outlives its hulls). Still fail-closed: an id outside the reserved band,
     * a blank key, or a fresh key already interned (engine stable ids are unique per deploy — a
     * duplicate means the fly-in hook returned a stale identity) THROWS.
     */
    public void internReservedMount(String stableKey, long id) {
        Objects.requireNonNull(stableKey, "stableKey");
        if (stableKey.isBlank()) {
            throw new IllegalArgumentException("stableKey must be non-blank");
        }
        if (id < 1L || id > reservedCeiling) {
            throw new IllegalArgumentException("reserved id " + id
                    + " outside the reserved band [1, " + reservedCeiling + "]");
        }
        if (idByKey.containsKey(stableKey)) {
            throw new IllegalArgumentException(
                    "stableKey already interned: " + stableKey + " -> " + idByKey.get(stableKey));
        }
        // T4 remount: release the dead hull's mapping so the seat's wire id re-points; a T1
        // first mount has no prior mapping and this is a no-op.
        idByKey.values().remove(id);
        idByKey.put(stableKey, id);
    }

    /**
     * W2b E1: release {@code stableKey}'s mapping for a REBIND and return the
     * id it held. The module-seat re-key needs this because station modules are pre-interned
     * ABOVE-band at the round pre-seed, and {@link #internReservedMount} deliberately THROWS on
     * an interned key — so a mount is {@code releaseForRebind(moduleStable)} then
     * {@code internReservedMount(moduleStable, seatWireId)} (whose {@code values().remove(id)}
     * frees the seat's dead-hull key), and a dismount is {@code releaseForRebind(moduleStable)}
     * then {@link #networkIdFor} (a FRESH above-band id — {@code nextId} is monotonic, so a
     * released above-band id RETIRES rather than re-pointing a client's stale binding).
     * Fail-closed: an unseen key THROWS — releasing what was never interned is a serve-loop
     * wiring bug, never a no-op.
     */
    public long releaseForRebind(String stableKey) {
        Objects.requireNonNull(stableKey, "stableKey");
        Long released = idByKey.remove(stableKey);
        if (released == null) {
            throw new IllegalArgumentException("releaseForRebind('" + stableKey
                    + "'): key was never interned — a rebind release must name a live mapping");
        }
        return released;
    }

    /**
     * Return the stable network {@code entityId} for {@code stableKey}, assigning the
     * next sequential id the first time a key is seen. The returned id is always
     * {@code > 0} (a valid {@code AuthoritativeEntitySnapshot.entityId}) and never
     * changes for a given key.
     */
    public long networkIdFor(String stableKey) {
        Objects.requireNonNull(stableKey, "stableKey");
        if (stableKey.isBlank()) {
            throw new IllegalArgumentException("stableKey must be non-blank");
        }
        Long existing = idByKey.get(stableKey);
        if (existing != null) {
            return existing;
        }
        long assigned = nextId++;
        idByKey.put(stableKey, assigned);
        return assigned;
    }

    /** The network id previously assigned to {@code stableKey}, or null if unseen. */
    public Long networkIdForOrNull(String stableKey) {
        return idByKey.get(stableKey);
    }

    /** True if {@code stableKey} has already been assigned a network id. */
    public boolean contains(String stableKey) {
        return idByKey.containsKey(stableKey);
    }

    /** Number of distinct ships (stable keys) seen so far. */
    public int size() {
        return idByKey.size();
    }

    /**
     * Immutable snapshot of the current stableKey→entityId table, in assignment order
     * — the identity table a server could replicate to a client at handshake.
     */
    public Map<String, Long> snapshot() {
        return Map.copyOf(idByKey);
    }
}
