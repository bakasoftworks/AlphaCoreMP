package cc.starsector.server.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The ONE off-frame writer of the hidden-ships file
 * ({@code cc-server.hidden-ships}, {@link HiddenShipSet}). A hide / show runs inside the frame loop,
 * so it never touches the file: it hands {@link HiddenShipSet#render} to
 * {@link #persist} (replaces ONE pending slot — coalescing, the latest render wins — and signals;
 * never blocks on I/O) and this daemon writes {@code <file>.tmp} then {@code ATOMIC_MOVE}s it over
 * the file (the {@link BanPersistSink} idiom). A failed write / move = one
 * {@code hidden_ships_persist_failed} row + {@link #persistFailed}{@code ++}; memory stays
 * authoritative and the next hide / show retries with the then-current render. Before each write
 * the file's mtime is compared with the one this sink last saw (at open, or its own last write): a
 * change = one {@code hidden_ships_external_edit_overwritten} row + {@link #externalEdits}{@code ++}
 * (edit the file only while the server is stopped).
 */
public final class HiddenShipsPersistSink implements AutoCloseable {

    public static final String THREAD_NAME = "cc-hidden-ships-persist";
    static final long CLOSE_DRAIN_MILLIS = 2_000L;

    private final Path file;
    private final Path tmp;
    private final Consumer<String> log;
    private final Object monitor = new Object();
    private final Thread writer;
    /** Guarded by {@link #monitor}: the one pending render (null = none). */
    private List<String> pendingLines;
    private boolean closing;
    private boolean writing;
    /** Writer thread only after start (seeded by the opener before start). */
    private FileTime lastSeenMtime;
    private volatile long persisted;
    private volatile long persistFailed;
    private volatile long coalesced;
    private volatile long externalEdits;
    private volatile long refusedClosed;

    private HiddenShipsPersistSink(Path file, Consumer<String> log) {
        this.file = file;
        this.tmp = file.resolveSibling(file.getFileName() + ".tmp");
        this.log = log;
        this.writer = new Thread(this::drain, THREAD_NAME);
        this.writer.setDaemon(true);
    }

    /** Start the writer for {@code file}; its current mtime is the external-edit baseline (the boot
     *  read just loaded it). Prints {@code hidden_ships_persist_sink}. */
    public static HiddenShipsPersistSink open(Path file, Consumer<String> log) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(log, "log");
        HiddenShipsPersistSink sink = new HiddenShipsPersistSink(file.toAbsolutePath(), log);
        sink.lastSeenMtime = sink.mtimeOrNull();
        sink.writer.start();
        log.accept("hidden_ships_persist_sink path=" + sink.file + " writer=off-thread");
        return sink;
    }

    public Path file() {
        return file;
    }

    /** Caller thread (the serve's frame): copy the lines, replace the pending slot, signal. No file
     *  I/O here; the monitor is held by the writer only for a slot swap. After {@link #close} the
     *  render is refused (counted in the close row). */
    public void persist(List<String> lines) {
        List<String> copy = List.copyOf(lines);
        synchronized (monitor) {
            if (closing) {
                refusedClosed++;
                return;
            }
            if (pendingLines != null) {
                coalesced++;
            }
            pendingLines = copy;
            monitor.notifyAll();
        }
    }

    public long persisted() {
        return persisted;
    }

    public long persistFailed() {
        return persistFailed;
    }

    public long coalesced() {
        return coalesced;
    }

    public long externalEdits() {
        return externalEdits;
    }

    /** 1 while a render waits for the writer, else 0. */
    public int pending() {
        synchronized (monitor) {
            return pendingLines != null ? 1 : 0;
        }
    }

    private void drain() {
        while (true) {
            List<String> lines;
            synchronized (monitor) {
                while (pendingLines == null && !closing) {
                    try {
                        monitor.wait();
                    } catch (InterruptedException stop) {
                        log.accept("hidden_ships_persist_writer_interrupted path=" + file
                                + " pending=" + (pendingLines != null ? 1 : 0));
                        return;
                    }
                }
                if (pendingLines == null) {
                    return; // closing and drained
                }
                lines = pendingLines;
                pendingLines = null;
                writing = true;
            }
            try {
                writeOnce(lines);
            } finally {
                synchronized (monitor) {
                    writing = false;
                    monitor.notifyAll();
                }
            }
        }
    }

    private void writeOnce(List<String> lines) {
        try {
            FileTime now = mtimeOrNull();
            if (!Objects.equals(now, lastSeenMtime)) {
                externalEdits++;
                log.accept("hidden_ships_external_edit_overwritten path=" + file
                        + " (edit the hidden ships file only while the server is stopped)");
            }
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(tmp, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException noAtomic) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            lastSeenMtime = mtimeOrNull();
            persisted++;
            int idLines = 0;
            for (String line : lines) {
                if (line != null && HiddenShipSet.idLawful(line.strip())) {
                    idLines++;
                }
            }
            log.accept("hidden_ships_persisted path=" + file + " ids=" + idLines);
        } catch (IOException | RuntimeException failed) {
            persistFailed++;
            log.accept("hidden_ships_persist_failed path=" + file + " err=" + failed.getClass().getName()
                    + ": " + (failed.getMessage() == null ? "" : failed.getMessage())
                    + " (memory stays authoritative; the next hide or show rewrites)");
        }
    }

    /** Null only on {@link Files#notExists}; an indeterminate path tries the read (its IOException =
     *  null; the write that follows reports the real failure). */
    private FileTime mtimeOrNull() {
        try {
            return Files.notExists(file) ? null : Files.getLastModifiedTime(file);
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** Drain a pending render (bounded {@value #CLOSE_DRAIN_MILLIS} ms), then stop the writer;
     *  prints {@code hidden_ships_persist_sink_closed} with the counters. */
    @Override
    public void close() {
        synchronized (monitor) {
            closing = true;
            monitor.notifyAll();
        }
        try {
            writer.join(CLOSE_DRAIN_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        boolean hung;
        synchronized (monitor) {
            hung = writer.isAlive() && (writing || pendingLines != null);
        }
        log.accept("hidden_ships_persist_sink_closed path=" + file + " persisted=" + persisted
                + " failed=" + persistFailed + " coalesced=" + coalesced
                + " refused_closed=" + refusedClosed + (hung ? " state=writer_hung" : ""));
    }
}
