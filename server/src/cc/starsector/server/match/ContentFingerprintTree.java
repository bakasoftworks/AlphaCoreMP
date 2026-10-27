package cc.starsector.server.match;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Server-side IO half of the {@link ContentFingerprints} recipe: enumerate EXACTLY the
 * whitelisted content surface — {@code mod_info.json} + every regular file under {@code data/**}
 * EXCEPT {@code data/missions/mission_list.csv} — of a mod root on disk. Everything else in the
 * root (kit tooling {@code match.txt} / {@code join-cc.bat} / {@code kit-manifest.txt}, JoinGui
 * persistence, {@code jars/}) is excluded BY CONSTRUCTION, so a pasted token or an edited
 * host:port can never fork the fingerprint.
 *
 * <p><b>mission_list.csv exclusion:</b> the kit packer's
 * shipped-only transform rewrites {@code mission_list.csv} per kit (the shipped registry index
 * lists only {@code cc_predict}; the host's deployed copy keeps the dev-proof rows) — a
 * DELIBERATELY kit-vs-host-skewed file, exactly the class the whitelist principle bars from the
 * digest (else the host's self-computed print bounces at its own armed door). Mission CONTENT
 * files stay digested; every kit generation mints the same two-line shipped index, so no real
 * staleness signal is lost.
 *
 * <p>Deliberately a SEPARATE class from the pure contract: this one references
 * {@code java.nio.file}, which the in-game script classloader denies — it is for the packer CLI
 * (B1b), tests, and any plain-JVM tool; the client-mod's own walker rides its allowlisted reader
 * at B1b and is contract-checked against this one via the minted sidecar value.
 */
public final class ContentFingerprintTree {

    private ContentFingerprintTree() {
    }

    /**
     * Read the whitelisted surface: keys = {@code /}-separated mod-root-relative paths in
     * natural (ordinal) order. Fail-closed on a root that is no mod root ({@code mod_info.json}
     * REQUIRED); a missing {@code data/} tree yields the {@code mod_info.json}-only surface.
     */
    public static TreeMap<String, byte[]> readContentSurface(Path modRoot) throws IOException {
        if (!Files.isDirectory(modRoot)) {
            throw new IOException("mod root is not a directory: " + modRoot);
        }
        TreeMap<String, byte[]> content = new TreeMap<String, byte[]>();
        Path modInfo = modRoot.resolve("mod_info.json");
        if (!Files.isRegularFile(modInfo)) {
            throw new IOException("no mod_info.json under " + modRoot
                    + " — not a mod root (the fingerprint whitelist requires it)");
        }
        content.put("mod_info.json", Files.readAllBytes(modInfo));
        Path data = modRoot.resolve("data");
        if (Files.isDirectory(data)) {
            try (Stream<Path> files = Files.walk(data)) {
                files.filter(Files::isRegularFile).forEach(file -> {
                    String rel = modRoot.relativize(file).toString().replace('\\', '/');
                    if (rel.equals("data/missions/mission_list.csv")) {
                        return; // kit-transform-skewed registry index — see class javadoc
                    }
                    try {
                        content.put(rel, Files.readAllBytes(file));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            } catch (UncheckedIOException unwrap) {
                throw unwrap.getCause();
            }
        }
        return content;
    }

    /** The {@code dataMd5} of a mod root on disk (enumerate + digest in one step). */
    public static String dataMd5(Path modRoot) throws IOException {
        return ContentFingerprints.dataMd5(readContentSurface(modRoot));
    }
}
