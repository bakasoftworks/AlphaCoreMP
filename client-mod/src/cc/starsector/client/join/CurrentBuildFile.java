package cc.starsector.client.join;

import cc.starsector.client.net.CurrentBuildLaw;
import cc.starsector.client.net.LocalVariantIdLaw;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The join window's side of the CURRENT BUILD file ({@link CurrentBuildLaw}): read it
 * to pre-select the ship combo, write it on Launch (a pick there IS the same state the editor's
 * FLY sets). NIO is fine HERE — {@code join/**} runs only outside the game (the B-deny exemption).
 * Written like the launch plan: beside itself, then moved
 * into place, so a half-written file is never read.
 */
final class CurrentBuildFile {
    private CurrentBuildFile() {
    }

    static Path file(Path installRoot) {
        return installRoot.resolve("saves").resolve("common").resolve("cc_variants").resolve(CurrentBuildLaw.FILE_NAME);
    }

    /** The current build's stem or {@code ""} (absent / unreadable / lawless / oversize = none). */
    static String read(Path installRoot) {
        Path f = file(installRoot);
        try {
            if (!Files.isRegularFile(f) || Files.size(f) > CurrentBuildLaw.MAX_TEXT) {
                return "";
            }
            return CurrentBuildLaw.parse(new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException unreadable) {
            return "";
        }
    }

    /**
     * No current build
     * (a server-default pick on the join page had still spawned the staged editor-FLY build). The join window's combo opens ON the
     * current build, so an empty pick can only mean the player actively chose the server-default row —
     * an explicit "not the staged build", which REVOKES the staging. Idempotent; absent = done.
     */
    static void clear(Path installRoot) throws IOException {
        Files.deleteIfExists(file(installRoot));
    }

    /** {@code stem} becomes the current build; a lawless stem is refused before any file access. */
    static void write(Path installRoot, String stem) throws IOException {
        if (!LocalVariantIdLaw.isLawful(stem)) {
            throw new IOException("current build stem refused (id law)");
        }
        Path f = file(installRoot);
        Files.createDirectories(f.getParent());
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.write(tmp, (stem + "\n").getBytes(StandardCharsets.US_ASCII));
        try {
            Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException plain) {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
