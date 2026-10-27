package cc.starsector.server.runtime;

import java.util.Arrays;
import java.util.Objects;

/**
 * W12 L2 — the sim-thread → relay-thread position hand-off: the
 * ONE on-frame voice cost the frame-loop-residency law allows. {@link VoiceAnchorLane} writes
 * every seat's anchor wire id + x/y + presence into the BACK buffer each tick and flips
 * {@code front}; {@link VoiceRelay} reads the front buffer lock-free (60 Hz writer vs a 10-ms
 * reader — fresh enough for a 2500-su cull radius). Two preallocated buffers per bind, swapped
 * by index: ZERO per-tick garbage.
 *
 * <p><b>Tolerated torn read:</b> the reader snapshots {@code front} once per pump and reads
 * that buffer while the writer fills the OTHER one; a flip mid-pump can only make the reader
 * mix one seat's position from tick T with another's from T+1 — positions only, one tick
 * stale, never identities (seat → slot is fixed for the bind, the wire-id column is the
 * writer's own and re-points at most once per tick). Nothing here guards more than that.
 *
 * <p><b>Binding:</b> seats are fixed per match; {@link #bind} is called at round start (sim
 * thread) and publishes a fresh {@link View} — a relay pump that started on the old view keeps
 * indexing the old arrays to completion (never an out-of-bounds on a size change). Unset seats
 * carry {@code NaN} x/y: never positioned ⇒ never proximity-audible.
 */
public final class VoicePositionSlot {

    /** One bind's buffers — structure immutable, contents double-buffered. */
    public static final class View {
        private final int[] seatIds;
        private final int[] indexBySeatId; // -1 = unknown; sized max seat + 1
        private final long[][] anchorWireId = new long[2][];
        private final float[][] x = new float[2][];
        private final float[][] y = new float[2][];
        private final boolean[][] present = new boolean[2][];
        private volatile int front;

        private View(int[] seatIds) {
            this.seatIds = seatIds.clone();
            int maxSeat = -1;
            for (int seat : seatIds) {
                if (seat < 0) {
                    throw new IllegalArgumentException("seatId must be >= 0: " + seat);
                }
                maxSeat = Math.max(maxSeat, seat);
            }
            this.indexBySeatId = new int[maxSeat + 1];
            Arrays.fill(indexBySeatId, -1);
            for (int i = 0; i < seatIds.length; i++) {
                if (indexBySeatId[seatIds[i]] != -1) {
                    throw new IllegalArgumentException("duplicate seatId " + seatIds[i]);
                }
                indexBySeatId[seatIds[i]] = i;
            }
            for (int b = 0; b < 2; b++) {
                anchorWireId[b] = new long[seatIds.length];
                x[b] = new float[seatIds.length];
                y[b] = new float[seatIds.length];
                present[b] = new boolean[seatIds.length];
                Arrays.fill(x[b], Float.NaN);
                Arrays.fill(y[b], Float.NaN);
            }
        }

        public int seatCount() {
            return seatIds.length;
        }

        public int seatOfSlot(int slot) {
            return seatIds[slot];
        }

        /** The seat's slot, or -1 when the seat is not bound. */
        public int slotOfSeat(int seatId) {
            return seatId < 0 || seatId >= indexBySeatId.length ? -1 : indexBySeatId[seatId];
        }

        // ---- reader API (relay thread) ----

        public int front() {
            return front;
        }

        public long anchorOf(int buffer, int slot) {
            return anchorWireId[buffer][slot];
        }

        public float xOf(int buffer, int slot) {
            return x[buffer][slot];
        }

        public float yOf(int buffer, int slot) {
            return y[buffer][slot];
        }

        public boolean presentOf(int buffer, int slot) {
            return present[buffer][slot];
        }

        // ---- writer API (sim thread, single writer) ----

        /** The back buffer index to fill — always the one the reader is NOT on. */
        public int beginWrite() {
            return 1 - front;
        }

        public void set(int buffer, int slot, long anchor, float px, float py, boolean isPresent) {
            anchorWireId[buffer][slot] = anchor;
            x[buffer][slot] = px;
            y[buffer][slot] = py;
            present[buffer][slot] = isPresent;
        }

        /** Flip the filled back buffer to front (volatile write = the publish fence). */
        public void publish() {
            front = 1 - front;
        }
    }

    private static final View EMPTY = new View(new int[0]);

    private volatile View view = EMPTY;

    /** Allocate fresh double buffers for these seats and publish the new view. */
    public View bind(int[] seatIds) {
        Objects.requireNonNull(seatIds, "seatIds");
        View fresh = new View(seatIds);
        view = fresh;
        return fresh;
    }

    /** The current bind (the empty view before the first {@link #bind}). */
    public View view() {
        return view;
    }
}
