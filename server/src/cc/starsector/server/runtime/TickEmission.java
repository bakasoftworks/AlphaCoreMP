package cc.starsector.server.runtime;

import cc.starsector.server.protocol.AuthoritativeWorldSnapshot;
import cc.starsector.server.protocol.EntityDespawnEvent;
import cc.starsector.server.protocol.GameplayEdgeEvent;
import cc.starsector.server.protocol.PhaseDiveEvent;
import java.util.List;
import java.util.Objects;

/**
 * Everything one tick's DOWN emission needs, minted by the sim thread at the compose site and
 * handed to {@link DownTickFan#emit} — immutable, so the emission can run on whichever thread
 * holds it without reading sim-owned state. Every list is fresh or immutable when minted and
 * is never mutated afterwards; the snapshot's tiers are immutable copies. No simulation tick
 * field: {@code snap().simTick()} carries it.
 *
 * @param snap            the tick's captured world
 * @param events          the GPEE events to send (empty when the event lane is off)
 * @param despawns        the EDSP despawns to send (empty when the event lane is off)
 * @param dives           the DIVE starts to send (empty when the event lane is off)
 * @param seatFrames      already-encoded SEAT frames, appended to the ctrl lane
 * @param live            the sessions to fan to, in table order (the fan rotates its start)
 * @param snapThinned     an off-tick of the down cadence divisor: ctrl frames only, no SNAP
 * @param downEveryTicks  the cadence divisor (1 = every tick)
 * @param captureEndNanos the engine capture's end ({@code System.nanoTime()})
 * @param dueNanos        the instant the frame was due (pacer deadline, else work start)
 * @param inDebt          the frame did not sleep to a paced deadline (the pacer was behind)
 * @param handoffNanos    when the sim handed the tick over (inline: the detect stage's end)
 *
 *
 */
public record TickEmission(
        AuthoritativeWorldSnapshot snap,
        List<GameplayEdgeEvent> events,
        List<EntityDespawnEvent> despawns,
        List<PhaseDiveEvent> dives,
        List<byte[]> seatFrames,
        List<PilotIntentDatagramServer.PeerSession> live,
        boolean snapThinned,
        int downEveryTicks,
        long captureEndNanos,
        long dueNanos,
        boolean inDebt,
        long handoffNanos) {

    public TickEmission {
        Objects.requireNonNull(snap, "snap");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(despawns, "despawns");
        Objects.requireNonNull(dives, "dives");
        Objects.requireNonNull(seatFrames, "seatFrames");
        Objects.requireNonNull(live, "live");
    }
}
