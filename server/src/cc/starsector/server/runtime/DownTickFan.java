package cc.starsector.server.runtime;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * One tick's DOWN emission, SEND-FIRST: compose what the peers receive, fan it, and only then
 * finish the record-only work. The order is the latency law: the raw absolute
 * frames the {@code .ccwr} DOWN record needs — and nothing a peer receives — are encoded after
 * the last send, so a delta tick reaches the wire earlier than when the whole composition was
 * built first, with byte-identical wire and record bytes.
 *
 * <ol>
 *   <li>{@link DownEmissionComposer#composeWire}: the ctrl frames and the wire SNAP lane; the
 *       chunker runs here, so its invariant throws still land before any send;</li>
 *   <li>the ctrl lane = {@code [GPEE*][EDSP*][DIVE*][SEAT*]};</li>
 *   <li>the fan: the SAME bytes to every live session, the start rotating with the tick so
 *       tail-of-fan loss under egress pressure is shared jitter rather than a standing
 *       join-order penalty; an off-tick of the down cadence withholds the SNAP lane only;</li>
 *   <li>the round's fan maxima;</li>
 *   <li>{@link DownEmissionComposer#completeRecord}: the raw absolute frames;</li>
 *   <li>ONE {@code KIND_DOWN} record — every tick, zero-session ticks included; SEAT and DIVE
 *       frames ride the ctrl fan but not the record contract;</li>
 *   <li>the send-lag receipt ({@link EmitLagMeter}).</li>
 * </ol>
 *
 * <p>Whoever calls {@link #emit} owns the composer, its armor-cell book, the {@link FanMeters},
 * the lag meter and every session's send-gap meter for the duration of the call. Today that is
 * always the sim thread, inline in the serve loop.
 *
 *
 */
public final class DownTickFan {

    private DownTickFan() {
    }

    /** Where a tick's frames go: the door, or a counting fake in a game-free contract. */
    public interface Sink {
        void sendTickToSession(PilotIntentDatagramServer.PeerSession session, long tick,
                List<byte[]> ctrlFrames, List<byte[]> snapFrames) throws IOException;

        void recordTickDown(long tick, byte[] composedDownRecord) throws IOException;
    }

    /**
     * The fan's round maxima plus the last tick's compose/send boundary — the stamp the serve's
     * frame witness splits its compose and send stages at, with that instant's stdout I/O
     * total. One owner at a time (the emitting thread); the serve reads and resets it between
     * ticks.
     */
    public static final class FanMeters {
        /** Slowest whole per-tick send fan this round (fanned ticks only). */
        long fanPassMaxNanos;
        /** Slowest single session send this round. */
        long sendSlowestNanos;
        /** The last emitted tick's wire-stage end ({@code System.nanoTime()}). */
        long wireStageEndNanos;
        /** The emitting thread's stdout I/O total at that instant. */
        long ioAtWireStageEnd;
        /** The emitting thread's stdout I/O meter; null reads 0 (an install that failed). */
        ServeStdoutIoMeter ioMeter;

        public long fanPassMaxNanos() {
            return fanPassMaxNanos;
        }

        public long sendSlowestNanos() {
            return sendSlowestNanos;
        }

        /** A new round: both maxima back to 0. */
        public void resetRound() {
            fanPassMaxNanos = 0L;
            sendSlowestNanos = 0L;
        }
    }

    /** The serve's form: the door is the sink. */
    public static void emit(PilotIntentDatagramServer door, DownEmissionComposer composer,
            TickEmission t, long pickupNanos, FanMeters meters, EmitLagMeter lag)
            throws IOException {
        emit(new DoorSink(door), composer, t, pickupNanos, meters, lag);
    }

    /**
     * Emit one tick into {@code sink} in the SEND-FIRST order of the class javadoc. A throw
     * from stage one leaves nothing sent and nothing recorded.
     */
    public static void emit(Sink sink, DownEmissionComposer composer, TickEmission t,
            long pickupNanos, FanMeters meters, EmitLagMeter lag) throws IOException {
        long tick = t.snap().simTick();
        DownTickComposition wire = composer.composeWire(t.snap(), t.events(), t.despawns(),
                t.dives());
        List<byte[]> ctrlFrames = new ArrayList<>(
                wire.gpeeFrames().size() + wire.edspFrames().size()
                        + wire.diveFrames().size() + t.seatFrames().size());
        ctrlFrames.addAll(wire.gpeeFrames());
        ctrlFrames.addAll(wire.edspFrames());
        ctrlFrames.addAll(wire.diveFrames());
        ctrlFrames.addAll(t.seatFrames());
        List<byte[]> snapFramesToFan = t.snapThinned() ? List.<byte[]>of() : wire.snapFrames();
        long wireStageEndNanos = System.nanoTime();
        meters.wireStageEndNanos = wireStageEndNanos;
        meters.ioAtWireStageEnd = ServeStdoutIoMeter.nanosOf(meters.ioMeter);
        List<PilotIntentDatagramServer.PeerSession> live = t.live();
        int fanCount = live.size();
        long fanPassStartNanos = System.nanoTime();
        int fanStart = fanCount == 0 ? 0 : Math.floorMod(tick, fanCount);
        for (int fi = 0; fi < fanCount; fi++) {
            PilotIntentDatagramServer.PeerSession session = live.get((fanStart + fi) % fanCount);
            long sendStartNanos = System.nanoTime();
            sink.sendTickToSession(session, tick, ctrlFrames, snapFramesToFan);
            long sendNanos = System.nanoTime() - sendStartNanos;
            if (sendNanos > meters.sendSlowestNanos) {
                meters.sendSlowestNanos = sendNanos;
            }
            // Send-gap meter: send end = the two stamps above, NO new clock read.
            session.snapSendGap.record(sendStartNanos + sendNanos, tick,
                    !snapFramesToFan.isEmpty(), t.downEveryTicks());
        }
        long fanPassNanos = System.nanoTime() - fanPassStartNanos;
        if (fanCount > 0 && fanPassNanos > meters.fanPassMaxNanos) {
            meters.fanPassMaxNanos = fanPassNanos;
        }
        DownTickComposition full = composer.completeRecord(wire);
        sink.recordTickDown(tick, full.composedDownRecord());
        lag.record(fanCount, t.inDebt(), t.captureEndNanos(), t.dueNanos(), t.handoffNanos(),
                pickupNanos, wireStageEndNanos, fanPassStartNanos + fanPassNanos);
    }

    /** The door as a {@link Sink}. */
    private record DoorSink(PilotIntentDatagramServer door) implements Sink {
        @Override
        public void sendTickToSession(PilotIntentDatagramServer.PeerSession session, long tick,
                List<byte[]> ctrlFrames, List<byte[]> snapFrames) {
            door.sendTickToSession(session, tick, ctrlFrames, snapFrames);
        }

        @Override
        public void recordTickDown(long tick, byte[] composedDownRecord) {
            door.recordTickDown(tick, composedDownRecord);
        }
    }
}
