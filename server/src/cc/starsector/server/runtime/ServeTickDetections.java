package cc.starsector.server.runtime;

import cc.starsector.server.protocol.EntityDespawnEvent;
import cc.starsector.server.protocol.GameplayEdgeEvent;

import java.util.List;
import java.util.Map;

/**
 * This tick's detection lists, carried from the {@code serveFrameDet*} phase method that builds each one to the
 * later phases that read it ({@link PilotServeSession}). Each field is named exactly as the local it carries, so
 * the phase bodies read and write the same names they always did; the one exception is {@link #dispatchedEdges}.
 *
 * <p>WHY: the frame is split into phases so each one compiles (one method stayed past the JIT's huge-method limit);
 * these are the references that cross a phase boundary. One instance per serve session, so the frame allocates
 * nothing for it. {@link #clear()} runs right after the compose and in the serve's teardown, so no engine-keyed
 * reference ({@code tickPositionShipIds} is keyed by engine ship objects) outlives its frame, even one that threw.
 *
 */
final class ServeTickDetections {
    // serveFrameDetReaders
    List<GameplayEdgeEvent> critEdges;
    // serveFrameDetEdges
    List<GameplayEdgeEvent> tickEdges;
    Map<Object, Long> tickPositionShipIds;
    List<FireBirthSink.FireBirth> tickBirths;
    /** The truth-merged edge list as the edge detector left it, before the declaration dispatch. */
    List<GameplayEdgeEvent> effectiveEdges;
    // serveFrameDetDespawn
    List<GameplayEdgeEvent> arcHitEdges;
    List<GameplayEdgeEvent> splitEdges;
    List<GameplayEdgeEvent> missileEndEdges;
    List<GameplayEdgeEvent> afTargetEdges;
    List<GameplayEdgeEvent> paladinDetEdges;
    List<EntityDespawnEvent> tickDespawns;
    // serveFrameDetSeat
    List<EntityDespawnEvent> composedDespawns;
    /** The edge list the declaration dispatch returned (each consumed declaration's wait stamped on its edge):
     *  the list the compose puts on the wire. A field apart from {@link #effectiveEdges} so each has one writer. */
    List<GameplayEdgeEvent> dispatchedEdges;
    // serveFrameDetFans
    List<byte[]> seatFrames;

    /** Drops every reference (each field is rewritten before its next read, so nothing else is reset). */
    void clear() {
        critEdges = null;
        tickEdges = null;
        tickPositionShipIds = null;
        tickBirths = null;
        effectiveEdges = null;
        arcHitEdges = null;
        splitEdges = null;
        missileEndEdges = null;
        afTargetEdges = null;
        paladinDetEdges = null;
        tickDespawns = null;
        composedDespawns = null;
        dispatchedEdges = null;
        seatFrames = null;
    }
}
