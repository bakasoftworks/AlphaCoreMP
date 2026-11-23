package cc.starsector.client;

import java.io.IOException;

import org.apache.log4j.FileAppender;
import org.apache.log4j.Layout;
import org.apache.log4j.spi.LoggingEvent;

/**
 * The explicit, uncapped sidelog file (a launcher-composed
 * {@code cc.watch.sideLog} path without the debug-watch cap key) — log4j's plain {@code FileAppender}, plus
 * the two duties every appender behind {@link PilotWatchLog}'s async ring owes {@link LogRowGateLaw}.
 *
 * <ul>
 * <li><b>The in-flight slot comes back in a {@code finally}</b>, whatever the write did, so the ring guard's
 * count tracks the ring and the logging thread is never parked on a full ring.</li>
 * <li><b>Nothing escapes {@link #append}.</b> A {@code Throwable} out of an appender kills log4j's async
 * Dispatcher for good (every later row would then sit in the ring); here it is counted
 * ({@link #faults()}) and said ONCE on {@code System.err}.</li>
 * </ul>
 *
 * <p>Classloader-B law: names no {@code java.io} file / writer type and no
 * {@code java.lang.reflect} type, only {@code java.io.IOException} and {@code System.err}'s
 * {@code PrintStream}.
 */
final class CountingFileAppender extends FileAppender {

    /** The gate whose slot each row gives back; null = none (a selftest's own appender). */
    private final LogRowGateLaw ringGate;
    private long faults;
    private boolean faultSaid;

    /** @param path the file (operator-config path, never wire-derived) */
    CountingFileAppender(Layout layout, String path, boolean append, LogRowGateLaw ringGate) throws IOException {
        super(layout, path, append);
        this.ringGate = ringGate;
    }

    /** log4j's write, then the slot back. Runs under {@code doAppend}'s lock (the Dispatcher thread). */
    @Override
    public void append(LoggingEvent e) {
        try {
            super.append(e);
        } catch (Throwable t) {
            faults++;
            if (!faultSaid) {
                faultSaid = true;
                try {
                    System.err.println(PilotWatchLog.TAG + "sidelog FAULT " + t.getClass().getSimpleName()
                            + " (counted, never rethrown; said once per appender)");
                } catch (Throwable ignore) {
                    // System.err itself failed: the count still holds it
                }
            }
        } finally {
            if (ringGate != null) {
                ringGate.releaseRing();
            }
        }
    }

    synchronized long faults() {
        return faults;
    }
}
