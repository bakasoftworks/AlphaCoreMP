package cc.starsector.server.protocol;

/**
 * One server-authoritative phase-dive START: the PhaseAnchor hull identified by
 * {@code entityId} began its emergency phase dive at completed simulation tick
 * {@code simTick}.
 *
 * <p>Why a dedicated event: the dive's terminal roster-drop already rides the
 * {@link EntityDespawnEvent} lane as {@code DEPARTED}, but the dive PLAYOUT
 * (~1 s of cloak-in, fade and vanish before the hull leaves) starts earlier and
 * has no snapshot-visible trigger a client could latch — the vanilla client-side
 * {@code PhaseAnchorScript} stays fenced against server-decided kills (F3b), so
 * presentation is the client mod's own lane and needs this explicit start signal.
 *
 * <p>The {@code entityId} is the same stable network identity the snapshot
 * stream and {@link EntityDespawnEvent} use, so a client can latch the dive and
 * later match it against the entity's {@code DEPARTED} despawn.
 */
public record PhaseDiveEvent(long entityId, long simTick) {

    public PhaseDiveEvent {
        if (entityId <= 0L) {
            throw new IllegalArgumentException("entityId must be > 0; got " + entityId);
        }
        if (simTick < 0L) {
            throw new IllegalArgumentException("simTick must be >= 0; got " + simTick);
        }
    }
}
