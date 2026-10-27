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
 * The ONE off-frame writer of the P27 bans file
 * ({@code cc-server.bans}). The serve's command lane runs INSIDE the frame loop, so it
 * never touches the file: it hands the rendered lines to {@link #persist} (replaces ONE pending
 * slot — coalescing, the latest render wins — and signals; never blocks, never does I/O) and this
 * daemon writes {@code <file>.tmp} then {@code ATOMIC_MOVE}s it over the file (the
 * {@link CustomVariantPersistSink} / {@code OpsStatusFile} idiom). A failed write/move (an AV
 * lock) = one {@code bans_persist_failed} row + {@code persistFailed++}; memory stays
 * authoritative and the next ban/unban retries with the then-current render. Before each write
 * the file's mtime is compared with the mtime this sink last saw (open or its own last write): a
 * change = the {@code bans_sidecar_external_edit_overwritten} row (the runbook law: edit the file
 * only while the server is stopped).
 */
public final class BanPersistSink implements AutoCloseable {

    public static final String THREAD_NAME = "cc-bans-persist";
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

    private BanPersistSink(Path file, Consumer<String> log) {
        this.file = file;
        this.tmp = file.resolveSibling(file.getFileName() + ".tmp");
        this.log = log;
        this.writer = new Thread(this::drain, THREAD_NAME);
        this.writer.setDaemon(true);
    }

    /** Start the writer for {@code file} (its current mtime = the baseline the external-edit
     *  check compares against — the boot read just loaded it); prints {@code bans_persist_sink}. */
    public static BanPersistSink open(Path file, Consumer<String> log) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(log, "log");
        BanPersistSink sink = new BanPersistSink(file.toAbsolutePath(), log);
        sink.lastSeenMtime = sink.mtimeOrNull();
        sink.writer.start();
        log.accept("bans_persist_sink path=" + sink.file + " writer=off-thread");
        return sink;
    }

    public Path file() {
        return file;
    }

    /** Caller thread (the serve's frame): replace the pending slot and signal. Never blocks on
     *  I/O (the monitor is held by the writer only for a slot swap). */
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

    /** 1 while a render waits for the writer, else 0. */
    public int pending() {
        synchronized (monitor) {
            return pendingLines != null ? 1 : 0;
        }
    }

    public long coalesced() {
        return coalesced;
    }

    public long externalEdits() {
        return externalEdits;
    }

    private void drain() {
        while (true) {
            List<String> lines;
            synchronized (monitor) {
                while (pendingLines == null && !closing) {
                    try {
                        monitor.wait();
                    } catch (InterruptedException stop) {
                        log.accept("bans_persist_writer_interrupted path=" + file
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
                log.accept("bans_sidecar_external_edit_overwritten path=" + file
                        + " (edit the bans file only while the server is stopped)");
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
            int entries = 0;
            for (String line : lines) {
                if (BanSet.parseEntryOrNull(line) != null) {
                    entries++;
                }
            }
            log.accept("bans_persisted path=" + file + " entries=" + entries);
        } catch (IOException | RuntimeException failed) {
            persistFailed++;
            log.accept("bans_persist_failed path=" + file + " err=" + failed.getClass().getName()
                    + ": " + (failed.getMessage() == null ? "" : failed.getMessage())
                    + " (memory stays authoritative; the next ban/unban rewrites)");
        }
    }

    /** null only on {@link Files#notExists}; an
     *  indeterminate path tries the read (its IOException = null, as before). */
    private FileTime mtimeOrNull() {
        try {
            return Files.notExists(file) ? null : Files.getLastModifiedTime(file);
        } catch (IOException unreadable) {
            return null; // the write that follows reports the real failure
        }
    }

    /** Drain a pending render (bounded {@value #CLOSE_DRAIN_MILLIS} ms), then stop the writer. */
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
        log.accept("bans_persist_sink_closed path=" + file + " persisted=" + persisted
                + " failed=" + persistFailed + " coalesced=" + coalesced
                + " refused_closed=" + refusedClosed + (hung ? " state=writer_hung" : ""));
    }
}
