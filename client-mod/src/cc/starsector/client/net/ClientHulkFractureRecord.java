package cc.starsector.client.net;

/**
 * The decoded payload of one {@code HKFR} server-authoritative hulk-fracture edge (hulk-split
 * sync): the client-side analogue of the server's
 * {@code HulkFractureFrameCodec.FractureEdge}. The server's detector walks its post-step remnant
 * families and fans one edge per fracture event — {@code PIECE_ADDED} (the authoritative engine
 * split a corpse; the client cuts its current largest local piece via {@code splitShip()}) or
 * {@code PIECE_REMOVED} (a piece left the server world; the client removes the local piece at
 * {@code sizeRank} with explosion FX).
 *
 * <p>{@code rootEntityId} is the corpse family's wire id (pieces inherit the parent hull's engine
 * id server-side, pinned at family creation); {@code deathTick} is the family's wire identity
 * (schema v2): the server detector's {@code firstSightTick} — the simTick of the
 * walk that created the family, within ~1-2 ticks of the corpse's EDSP death tick. The client
 * binds a family by same rootId + nearest {@code |mintTick − deathTick|} (≤150 ticks; min
 * same-rootId family separation ≈300 = the respawn cooldown), never by a derived death ordinal
 * (the v1 familySeq desynced on any unobserved death — late join). {@code sizeRank} ranks by
 * bbox area, largest = 0, and is 0 on adds by contract; {@code familyPieceCount} is the post-edge
 * family size (the client's apply assert).
 *
 * <p>Like its {@code SNMR} sibling {@link ClientSeatNameRecord}, this is a small client-owned
 * mirror — the mod deliberately does not bundle the server's record (its codec statics drag
 * {@code NetworkMessageRegistry} into the game JVM). The game-free cross-check
 * ({@code CcClientSeatNameVerifier}'s HKFR legs) round-trips real server
 * {@code HulkFractureFrameCodec} bytes through {@link ClientHulkFractureRecordReader} and asserts
 * this value matches, keeping the two mirrors in sync.
 */
public final class ClientHulkFractureRecord {

    /** A piece joined the corpse family. Mirrors {@code HulkFractureFrameCodec.EDGE_KIND_PIECE_ADDED}. */
    public static final int EDGE_KIND_PIECE_ADDED = 1;
    /** A piece left the world. Mirrors {@code HulkFractureFrameCodec.EDGE_KIND_PIECE_REMOVED}. */
    public static final int EDGE_KIND_PIECE_REMOVED = 2;
    /** A piece left the world because the engine VAPORIZED it — the engine's
     * small-hulk overkill destroy ({@code wasDestroyed=true}; schema v3): the
     * client replays the vanilla destruction presentation instead of the quiet kind-2 prune.
     * Mirrors {@code HulkFractureFrameCodec.EDGE_KIND_PIECE_REMOVED_DESTROYED}. */
    public static final int EDGE_KIND_PIECE_REMOVED_DESTROYED = 3;
    /** The family ROOT was observed module-detached server-side (schema v4):
     * the authoritative detach roll (or a late overkill detach) released a station-module
     * wreck. The client un-slots the bound wreck ({@code setStationSlot(null)}) so its local
     * engine's per-frame re-glue releases and HKPS coast drives the drift. Membership
     * untouched — neither an add nor a removal.
     * Mirrors {@code HulkFractureFrameCodec.EDGE_KIND_ROOT_DETACHED}. */
    public static final int EDGE_KIND_ROOT_DETACHED = 4;

    private final long simTick;
    private final long rootEntityId;
    private final long deathTick;
    private final int edgeKind;
    private final int sizeRank;
    private final int familyPieceCount;

    /**
     * @throws IllegalArgumentException on any field outside the server record's exact guards
     *     (negative simTick, rootEntityId &lt; 1, a deathTick outside {@code 0..simTick} — a
     *     family's edges never precede its death — u8 fields outside 0..255, an unknown edgeKind,
     *     or a nonzero sizeRank on a PIECE_ADDED or ROOT_DETACHED edge). The reader translates this to an
     *     {@link java.io.IOException} so a bad frame fails closed like every other wire decode.
     */
    public ClientHulkFractureRecord(long simTick, long rootEntityId, long deathTick, int edgeKind,
            int sizeRank, int familyPieceCount) {
        if (simTick < 0L) {
            throw new IllegalArgumentException("simTick must be >= 0; got " + simTick);
        }
        if (rootEntityId < 1L) {
            throw new IllegalArgumentException(
                    "rootEntityId must be >= 1 (a wire entity id); got " + rootEntityId);
        }
        if (deathTick < 0L) {
            throw new IllegalArgumentException("deathTick must be >= 0; got " + deathTick);
        }
        if (deathTick > simTick) {
            throw new IllegalArgumentException("deathTick must be <= simTick (edges never precede"
                    + " the death); got deathTick=" + deathTick + " simTick=" + simTick);
        }
        if (edgeKind != EDGE_KIND_PIECE_ADDED && edgeKind != EDGE_KIND_PIECE_REMOVED
                && edgeKind != EDGE_KIND_PIECE_REMOVED_DESTROYED
                && edgeKind != EDGE_KIND_ROOT_DETACHED) {
            throw new IllegalArgumentException(
                    "edgeKind must be 1 (ADD), 2 (REMOVE), 3 (REMOVE_DESTROYED) or"
                            + " 4 (ROOT_DETACHED); got " + edgeKind);
        }
        if (sizeRank < 0 || sizeRank > 0xFF) {
            throw new IllegalArgumentException("sizeRank must be 0..255; got " + sizeRank);
        }
        if ((edgeKind == EDGE_KIND_PIECE_ADDED || edgeKind == EDGE_KIND_ROOT_DETACHED)
                && sizeRank != 0) {
            throw new IllegalArgumentException("sizeRank must be 0 on PIECE_ADDED and"
                    + " ROOT_DETACHED edges; got " + sizeRank);
        }
        if (familyPieceCount < 0 || familyPieceCount > 0xFF) {
            throw new IllegalArgumentException(
                    "familyPieceCount must be 0..255; got " + familyPieceCount);
        }
        this.simTick = simTick;
        this.rootEntityId = rootEntityId;
        this.deathTick = deathTick;
        this.edgeKind = edgeKind;
        this.sizeRank = sizeRank;
        this.familyPieceCount = familyPieceCount;
    }

    /** The server sim tick the edge was observed on. */
    public long simTick() {
        return simTick;
    }

    /** The corpse family's wire id (the originally-alive ship's entity id). */
    public long rootEntityId() {
        return rootEntityId;
    }

    /** The family's wire identity: the server detector's firstSightTick (~the EDSP death tick;
     * schema v2). Clients bind by same rootId + nearest {@code |mintTick − deathTick|} ≤ 150. */
    public long deathTick() {
        return deathTick;
    }

    /** {@link #EDGE_KIND_PIECE_ADDED}, {@link #EDGE_KIND_PIECE_REMOVED} or
     * {@link #EDGE_KIND_PIECE_REMOVED_DESTROYED}. */
    public int edgeKind() {
        return edgeKind;
    }

    /** The removed piece's pre-edge size rank (bbox area, largest = 0); 0 on adds by contract. */
    public int sizeRank() {
        return sizeRank;
    }

    /** The post-edge family piece count (the client's apply assert). */
    public int familyPieceCount() {
        return familyPieceCount;
    }

    public boolean isPieceAdded() {
        return edgeKind == EDGE_KIND_PIECE_ADDED;
    }

    /** Either removal kind — the piece leaves the local world on both. */
    public boolean isPieceRemoved() {
        return edgeKind == EDGE_KIND_PIECE_REMOVED
                || edgeKind == EDGE_KIND_PIECE_REMOVED_DESTROYED;
    }

    /** The engine vaporized the piece (kind 3): replay the vanilla destruction presentation. */
    public boolean isRemoveDestroyed() {
        return edgeKind == EDGE_KIND_PIECE_REMOVED_DESTROYED;
    }

    /** The family root was observed module-detached server-side (kind 4):
     * un-slot the bound wreck; piece membership untouched. */
    public boolean isRootDetached() {
        return edgeKind == EDGE_KIND_ROOT_DETACHED;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ClientHulkFractureRecord other)) {
            return false;
        }
        return simTick == other.simTick && rootEntityId == other.rootEntityId
                && deathTick == other.deathTick && edgeKind == other.edgeKind
                && sizeRank == other.sizeRank && familyPieceCount == other.familyPieceCount;
    }

    @Override
    public int hashCode() {
        int h = Long.hashCode(simTick);
        h = 31 * h + Long.hashCode(rootEntityId);
        h = 31 * h + Long.hashCode(deathTick);
        h = 31 * h + edgeKind;
        h = 31 * h + sizeRank;
        return 31 * h + familyPieceCount;
    }

    @Override
    public String toString() {
        return "ClientHulkFractureRecord{tick=" + simTick + ",rootId=" + rootEntityId
                + ",deathTick=" + deathTick + ",edge="
                + (isPieceAdded() ? "ADD"
                        : isRootDetached() ? "ROOT_DETACHED"
                        : isRemoveDestroyed() ? "REMOVE_DESTROYED" : "REMOVE")
                + ",rank=" + sizeRank + ",pieces=" + familyPieceCount + "}";
    }
}
