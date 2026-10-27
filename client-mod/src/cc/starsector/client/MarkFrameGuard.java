package cc.starsector.client;

/**
 * The pure tick maths behind the two frame instruments
 * ({@link MarkFrameRing}, the F9 mark ring, and {@link ObserverFrameDump}, the per-tick reel), pulled
 * out so a headless smoke ({@code MarkFrameGuardSmoke}) proves it with no GL / Display / ImageIO /
 * api class on the classpath. After the {@code /wave wrap} the server tick space
 * restarted at 0 while the capture guard still waited for tick ≈16564, so neither instrument captured
 * a single round-1 frame; the fix is the round-reset hooks ({@code onRoundReset}) re-arming the guard
 * to {@link Long#MIN_VALUE}, plus a pre-window bounded ABOVE as well as below. Stateless, never throws.
 */
final class MarkFrameGuard {

    private MarkFrameGuard() {
    }

    /**
     * The capture cadence guard. {@code nextTick} =
     * {@link Long#MIN_VALUE} means re-armed (combat init / round reset): the first stamped tick
     * captures. Returns {@link Long#MIN_VALUE} = SKIP this tick, else the new {@code nextTick} (the next
     * multiple of {@code every} above {@code tick}; {@code every} below 1 reads as 1). A tick below a
     * stale {@code nextTick} is skipped forever — which is why a tick-space restart MUST re-arm.
     */
    static long nextCaptureTick(long tick, long nextTick, int every) {
        if (nextTick != Long.MIN_VALUE && tick < nextTick) {
            return Long.MIN_VALUE;
        }
        long e = Math.max(1, every);
        return tick - Math.floorMod(tick, e) + e;
    }

    /**
     * A ring frame belongs to a mark's pre-snapshot iff
     * {@code markTick - preTicks <= fTick <= markTick + postTicks}. The upper bound keeps frames
     * encoded between the F9 press and the encoder draining the mark (their tick is a little past the
     * mark), and rejects a previous round's frames whose ticks sit far above a restarted tick space.
     */
    static boolean inPreWindow(long fTick, long markTick, int preTicks, int postTicks) {
        return fTick >= markTick - preTicks && fTick <= markTick + postTicks;
    }
}
