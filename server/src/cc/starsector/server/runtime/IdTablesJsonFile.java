package cc.starsector.server.runtime;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The server-only file half (the boot dump beside the port file,
 * the synthetic client's {@code -Dcc.client.idTables} read, the {@code IdTableFixture} freeze).
 * Kept OUT of {@link IdTablesJson} (and {@link IdTables}) so the bundled classes carry no
 * {@code java.io.File} / {@code java.nio.file} reference (classloader B name-denies them).
 *
 * <p>Reading is fail-closed: {@link IdTablesJson#parse} recomputes the digest and refuses a file
 * whose {@code vtab} disagrees with its own lists (a hand-edit or a partial write).
 */
public final class IdTablesJsonFile { // server-only file half

    /** Read guard (a vanilla universe renders ~60 KB; a heavy mod set a few hundred KB). */
    public static final int MAX_FILE_BYTES = 8 * 1024 * 1024;

    private IdTablesJsonFile() {
    }

    public static IdTables read(File file) throws IOException {
        if (!file.isFile()) {
            throw new IOException("id-tables file missing: " + file);
        }
        if (file.length() > MAX_FILE_BYTES) {
            throw new IOException("id-tables file exceeds " + MAX_FILE_BYTES + " B: " + file);
        }
        try {
            return IdTablesJson.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException bad) {
            throw new IOException("id-tables file " + file + ": " + bad.getMessage(), bad);
        }
    }

    public static void write(File file, IdTables tables) throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("id-tables dir could not be created: " + parent);
        }
        Files.write(file.toPath(), IdTablesJson.render(tables).getBytes(StandardCharsets.UTF_8));
    }
}
