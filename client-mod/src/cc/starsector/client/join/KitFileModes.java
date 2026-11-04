package cc.starsector.client.join;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.Set;

/**
 * Owner-only file modes for the join window's user-dir writers: dirs {@code rwx------} (0700), files
 * {@code rw-------} (0600) — the security number of the kit's Linux user-dir design.
 *
 * <p>WHY at creation, not create-then-chmod: the handoff file, the endpoint file and the settings hold a
 * password, a token and a cid; a window between creation and chmod is a window another local account can
 * read. The attribute rides {@code Files.newByteChannel(path, opts, attr)} so it applies atomically where the
 * {@code posix} view exists; a file that ALREADY existed gets {@link #ownerOnly} afterwards (the attribute
 * applies only at creation). On Windows (no {@code posix} view) every method is the plain call it wraps, so
 * Windows behaviour is unchanged by construction. Each writer KEEPS its own open options (a rewrite stays a
 * rewrite, an append an append); {@code CREATE_NEW} belongs only to the writers that mint a file once.
 */
public final class KitFileModes {
    private static final Set<PosixFilePermission> DIR_0700 = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_0600 = PosixFilePermissions.fromString("rw-------");
    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private KitFileModes() {}

    /** True where the default filesystem has a {@code posix} attribute view (Linux); false on Windows. */
    public static boolean posix() {
        return POSIX;
    }

    /** The 0700 creation attribute on a POSIX filesystem; empty elsewhere. */
    public static FileAttribute<?>[] dirAttrs() {
        return POSIX ? new FileAttribute<?>[] { PosixFilePermissions.asFileAttribute(DIR_0700) } : new FileAttribute<?>[0];
    }

    /** The 0600 creation attribute on a POSIX filesystem; empty elsewhere. */
    public static FileAttribute<?>[] fileAttrs() {
        return POSIX ? new FileAttribute<?>[] { PosixFilePermissions.asFileAttribute(FILE_0600) } : new FileAttribute<?>[0];
    }

    /** {@code Files.createDirectories} with the 0700 attribute (each dir it creates; existing dirs untouched). */
    public static Path createDirs(Path dir) throws IOException {
        return Files.createDirectories(dir, dirAttrs());
    }

    /**
     * Opens {@code file} with exactly the caller's options plus the 0600 creation attribute; a file that existed
     * before the call is set to 0600 after opening (closing the channel if that fails).
     */
    public static SeekableByteChannel open(Path file, OpenOption... opts) throws IOException {
        boolean existed = Files.exists(file, LinkOption.NOFOLLOW_LINKS);
        Set<OpenOption> set = new HashSet<>();
        for (OpenOption o : opts) set.add(o);
        SeekableByteChannel ch = Files.newByteChannel(file, set, fileAttrs());
        if (existed) {
            try {
                ownerOnly(file);
            } catch (IOException e) {
                ch.close();
                throw e;
            }
        }
        return ch;
    }

    /** Sets an existing file to 0600 on a POSIX filesystem; a no-op elsewhere or when the file is absent. */
    public static void ownerOnly(Path file) throws IOException {
        if (POSIX && Files.exists(file, LinkOption.NOFOLLOW_LINKS)) Files.setPosixFilePermissions(file, FILE_0600);
    }
}
