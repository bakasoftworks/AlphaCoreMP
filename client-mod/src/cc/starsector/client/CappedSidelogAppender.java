package cc.starsector.client;

import java.io.IOException;
import java.util.ArrayList;

import org.apache.log4j.Layout;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.RollingFileAppender;
import org.apache.log4j.helpers.CountingQuietWriter;
import org.apache.log4j.spi.LoggingEvent;

/**
 * The DEFAULT sidelog lane's file appender
 * ({@code cc-client.log}), and the debug-watch explicit lane's when its cap key is set: at the cap
 * ({@link SidelogCapLaw#CAP_CHARS} on the default lane) the live file RESTARTS IN PLACE —
 * truncated and reopened under its own name, the session's pinned head re-emitted at its original
 * {@code %r}, then ONE {@link SidelogCapLaw#markerRow} — and logging carries on. Why a cap, and why
 * not log4j's rename roll: {@link SidelogCapLaw}. {@code PilotWatchLog.attachSidelog} builds it; the
 * explicit tooling lane ({@code -Dcc.watch.sideLog=<path>}) stays a plain uncapped
 * {@code FileAppender} unless {@code CC_WATCH_SIDELOG_MAX_MIB} is set, when it gets this appender
 * at that cap with no load-time rotate ({@link SidelogCapLaw#explicitCapChars}).
 *
 * <p><b>Where it runs.</b> The appender sits behind {@code PilotWatchLog}'s {@code AsyncAppender}, so
 * {@link #append} / {@link #subAppend} run on the async Dispatcher thread inside the synchronized
 * {@code doAppend}: the size check and the restart never touch the render thread. Every
 * field below is touched only under this appender's own monitor; no lock is added (the mutable-state
 * accessors take that same monitor).
 *
 * <p><b>Two hazards designed out</b>:
 * <ul>
 * <li>F1: log4j's {@code setFile} runs {@code reset()} — the writer AND {@code fileName} nulled —
 *     BEFORE it opens, so a failed open would leave a null writer, and {@code WriterAppender.append}
 *     would drop every later row at {@code checkEntryConditions} without ever reaching
 *     {@link #subAppend}. So the file name is copied first, a failed truncate reopens in APPEND mode,
 *     the next truncate waits {@code cap / 16} more chars, 8 failures in a row stop the lane, and a
 *     lane whose append reopen also failed retries from {@link #append} every 1024th row.</li>
 * <li>F2: nothing on log4j's dispatch path catches; a throw out of this class would kill the
 *     Dispatcher, and once the 4096-row ring fills the render thread would park forever. So the
 *     whole body of {@link #subAppend} and {@link #append} sits in {@code catch (Throwable)}, and the
 *     marker / re-emit go out as DIRECT {@code super.subAppend} calls (never through a logger: a
 *     render thread parked on a full ring holds the {@code AsyncAppender} monitor).</li>
 * </ul>
 *
 * <p><b>Bound (honest).</b> The count includes the re-emitted head, so the live file stays ≤ cap + one
 * row and the disk ≤ 2 × (cap + one row) with {@code .1}; after truncate failures ≤ 1.5 × cap + one row,
 * then the lane stops. "One row" is unbounded in principle ({@code PilotWatchLog.render} folds, never
 * truncates). The count is CHARS; the file's bytes follow the native charset.
 *
 * <p><b>Exports.</b> The {@code sidelog RESTART} marker row in the file IS the restart-count export;
 * the {@code System.err} line per truncate failure (and the once-per-JVM fault line) IS the
 * restart-failure export. Reader = whoever opens the log or the debrief bundle; delete trigger = this
 * appender's removal. The accessors exist for the selftest rows only.
 *
 * <p>Classloader-B law: this class names no {@code java.io} file / writer type and no
 * {@code java.lang.reflect} type — only {@code java.io.IOException} and {@code System.err}'s
 * {@code PrintStream}; it overrides neither {@code setQWForFiles(Writer)} nor {@code createWriter}.
 * Package-private and NOT final: the selftest subclasses {@link #reopen} to fail the open.
 */
class CappedSidelogAppender extends RollingFileAppender {

    /** The marker / resumed rows' {@code %c}: {@link PilotWatchLog}'s logger name. */
    private static final String SIDELOG_LOGGER = "cc.pilot.watch";
    private static final String FQCN = CappedSidelogAppender.class.getName();
    /** The fault line is said once per JVM (a benign race may let two instances each say it once). */
    private static volatile boolean faultLineSaid;

    private final long capChars;
    private final int headMaxChars;
    private final String prefix;
    private final Logger markerLogger;
    private final ArrayList<LoggingEvent> head = new ArrayList<>();
    private long headChars;
    private boolean headClosed;
    /** The count (chars) at which the next truncate is attempted: the cap, or a back-off past it. */
    private long nextRestartAt;
    private int restarts;
    /** Truncate failures IN A ROW (a success resets it). */
    private int truncateFailures;
    private long droppedRows;
    private long faults;
    private boolean stopped;
    /** Non-null while BOTH reopens failed: the writer is null and {@link #append} retries this name. */
    private String stalledName;

    /**
     * @param path         the live file (operator-config path, never wire-derived)
     * @param capChars     the restart threshold in chars; ≤ 0 = never restart
     * @param headMaxChars the pinned head bound in chars; ≤ 0 = an empty head
     * @param prefix       the {@link PilotWatchLog} row label at attach time (bounded token)
     */
    CappedSidelogAppender(Layout layout, String path, boolean append, long capChars, int headMaxChars,
            String prefix) throws IOException {
        super(layout, path, append);
        // The native rename roll must never fire mid-session (it deletes .1); the caller sets it too.
        this.maxFileSize = Long.MAX_VALUE;
        this.capChars = capChars;
        this.headMaxChars = headMaxChars;
        this.prefix = prefix == null ? "" : prefix;
        this.markerLogger = Logger.getLogger(SIDELOG_LOGGER);
        this.nextRestartAt = capChars;
    }

    /** The ONE open seam: every reopen (truncate, append fallback, stall retry) goes through here. */
    void reopen(String name, boolean append) throws IOException {
        setFile(name, append, bufferedIO, bufferSize);
    }

    /** Write the row, pin it while the head is open, then restart in place once the count reaches
     *  the threshold. Never throws (F2). */
    @Override
    protected void subAppend(LoggingEvent e) {
        try {
            super.subAppend(e);
            if (!headClosed) {
                int len = layout.format(e).length();
                if (headChars + len > headMaxChars) {
                    headClosed = true; // the event that would cross the bound closes the head, unpinned
                } else {
                    head.add(e);
                    headChars += len;
                }
            }
            if (qw instanceof CountingQuietWriter) {
                long count = ((CountingQuietWriter) qw).getCount();
                if (SidelogCapLaw.shouldRestart(count, nextRestartAt)) {
                    restartInPlace(count);
                }
            }
        } catch (Throwable t) {
            fault(t);
        }
    }

    /** The only entry log4j still calls once the writer is null: drops (counted) while stopped or
     *  stalled, retrying a stalled reopen every 1024th row. Never throws (F2). */
    @Override
    public void append(LoggingEvent e) {
        try {
            if (stopped) {
                droppedRows++;
                return;
            }
            if (qw == null && stalledName != null && !closed) {
                if (((droppedRows + 1L) & 1023L) != 0L || !revive()) {
                    droppedRows++;
                    return;
                }
            }
            super.append(e);
        } catch (Throwable t) {
            fault(t);
        }
    }

    private void restartInPlace(long count) {
        String name = fileName; // setFile nulls fileName before it opens (F1): copy it first
        if (name == null) {
            return;
        }
        headClosed = true; // the head = the first rows up to the bound or the first restart attempt
        try {
            reopen(name, false);
        } catch (Throwable t) {
            truncateFailed(name, count, t);
            return;
        }
        restarts++;
        truncateFailures = 0;
        nextRestartAt = capChars;
        if (qw == null) {
            stalledName = name; // an open that returned without a writer: treat as a stall
            return;
        }
        for (LoggingEvent pinned : head) {
            super.subAppend(pinned); // the native write: the original %r and thread name
        }
        super.subAppend(new LoggingEvent(FQCN, markerLogger, Level.INFO, SidelogCapLaw.markerRow(prefix,
                restarts, Math.max(0L, count - headChars), head.size(), capChars), null));
    }

    private void truncateFailed(String name, long count, Throwable cause) {
        truncateFailures++;
        say("sidelog truncate FAILED " + cause.getClass().getSimpleName() + " in_a_row=" + truncateFailures
                + " (the file keeps growing; next try in " + (capChars / SidelogCapLaw.RETRY_STEP_DIVISOR)
                + " chars)");
        if (qw == null) {
            try {
                reopen(name, true); // keep the rows landing in the un-truncated file
            } catch (Throwable again) {
                // counted below: the stall drops (and counts) every row until a retry revives it
            }
            if (qw == null) {
                stalledName = name;
            }
        }
        nextRestartAt = count + capChars / SidelogCapLaw.RETRY_STEP_DIVISOR;
        if (truncateFailures >= SidelogCapLaw.MAX_TRUNCATE_FAILURES) {
            stopped = true;
            say("sidelog STOPPED after " + truncateFailures + " truncate failures in a row (disk bound"
                    + " outranks the tail; rows are dropped until the next game start)");
        }
    }

    /** A stalled lane's retry: reopen in append mode; on success write ONE resumed row. */
    private boolean revive() {
        try {
            reopen(stalledName, true);
        } catch (Throwable t) {
            return false; // still stalled: the caller counts this row as dropped
        }
        if (qw == null) {
            return false;
        }
        stalledName = null;
        super.subAppend(new LoggingEvent(FQCN, markerLogger, Level.INFO,
                SidelogCapLaw.resumedRow(prefix, droppedRows), null));
        return true;
    }

    private void fault(Throwable t) {
        faults++;
        if (!faultLineSaid) {
            faultLineSaid = true;
            say("sidelog FAULT " + t.getClass().getSimpleName() + " (counted, never rethrown; said once per JVM)");
        }
    }

    private void say(String line) {
        try {
            System.err.println(prefix + line);
        } catch (Throwable ignore) {
            // System.err itself failed: there is nowhere left to say it
        }
    }

    long capChars() {
        return capChars;
    }

    synchronized int restarts() {
        return restarts;
    }

    synchronized int truncateFailures() {
        return truncateFailures;
    }

    synchronized long droppedRows() {
        return droppedRows;
    }

    synchronized long faults() {
        return faults;
    }
}
