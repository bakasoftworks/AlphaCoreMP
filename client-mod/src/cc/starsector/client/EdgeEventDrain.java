package cc.starsector.client;

import java.util.Deque;
import java.util.function.Consumer;

/**
 * The bounded poll loop behind {@link EdgeEventLane#drainEdgeEvents}:
 * one drain hands on at most the queue size it read at entry. WHY: the net thread refills the intake queue
 * while the render thread drains it, so a drain that ran until the queue was EMPTY let a host that sends faster
 * than the joiner presents keep one frame's drain running without end (a frame stall the sender controls).
 * Bounding by the entry size keeps a drain finite (at most the intake's occupancy cap,
 * {@link RosterAppendLane#MAX_ROUND_ENTITIES}); whatever arrived during the drain waits, in order, for the next
 * frame's drain (the queue is FIFO: nothing is dropped, nothing reordered). Lock discipline (unchanged from
 * the loop this replaces): every queue touch holds {@code lock}; the consumer runs OUTSIDE it, so the per-event
 * body never blocks the net thread's intake. Plugin-free and static so a selftest can drive it directly.
 */
final class EdgeEventDrain {

    private EdgeEventDrain() {
    }

    /**
     * Polls {@code queue} under {@code lock} and hands each event to {@code consumer}, at most as many as the
     * queue held when this call began; stops early when the queue runs empty (a reset that cleared it). Every
     * event handed on counts against the budget whatever the consumer does with it (present, hold or refuse).
     *
     * @return the number of events handed to {@code consumer}, {@code 0..}(the size read at entry)
     */
    static <E> int drainEntrySize(Deque<E> queue, Object lock, Consumer<? super E> consumer) {
        int budget;
        synchronized (lock) {
            budget = queue.size();
        }
        int handed = 0;
        while (handed < budget) {
            E event;
            synchronized (lock) {
                event = queue.pollFirst();
            }
            if (event == null) {
                break;
            }
            handed++;
            consumer.accept(event);
        }
        return handed;
    }
}
