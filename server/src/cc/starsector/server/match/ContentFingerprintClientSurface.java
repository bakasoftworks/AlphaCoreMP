package cc.starsector.server.match;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

/**
 * Client-side (in-game) IO half of the {@link ContentFingerprints} recipe — the walker-less
 * surface: the Starsector script
 * classloader B denies every directory-ENUMERATION lane ({@code java.io.File} functional DENY,
 * no listing API anywhere in the published game API) but allows byte-exact READS — the
 * {@code FileReader(path, ISO_8859_1)} char≡byte lane plus {@code MessageDigest}, both measured
 * PASS under a real B-parented loader (20/20). So the client
 * takes the {@code data/**} file LIST from the packer-minted sidecar's {@code file=} rows and
 * digests the mod's own REAL local bytes.
 *
 * <p><b>Sandbox law:</b> nothing in this class (or {@link ContentFingerprints}) may reference
 * {@code java.io.File}, {@code java.io.FileInputStream}, or {@code java.nio.file.File*} — DENIED to
 * B-parented mod code; a stray reference kills the class at link time in-game. The client-mod
 * build's jar-content gate pins which halves ride the jar. PRECISION (measured):
 * B's predicate is the {@code java.nio.file.File} PREFIX, not the package — {@code Path}, {@code Paths},
 * {@code DirectoryStream}, {@code java.nio.file.attribute.*} and {@code java.nio.file.spi.FileSystemProvider}
 * pass by name, which is how {@code cc.starsector.server.runtime.ModDataWalk} enumerates a foreign
 * mod's {@code data/**} in-game (the "walker-less" premise above holds only for {@code Files}/{@code File}).
 *
 * <p>Known gap: an ADDED unlisted {@code data/**} file escapes this
 * digest — the mint-side walker ({@link ContentFingerprintTree}) still enumerates for real, so
 * stale/modified/missing listed files all still bounce at the door.
 */
public final class ContentFingerprintClientSurface {

    private ContentFingerprintClientSurface() {
    }

    /** Read a small text file's lines via the measured-ALLOW reader lane (sidecars are ASCII). */
    public static List<String> readLines(String path) throws IOException {
        BufferedReader reader = new BufferedReader(
                new FileReader(path, StandardCharsets.ISO_8859_1));
        try {
            List<String> lines = new ArrayList<String>();
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            return lines;
        } finally {
            reader.close();
        }
    }

    /**
     * Byte-exact read via the measured char≡byte lane: ISO_8859_1 maps byte {@code n} to char
     * {@code n} for all 256 values, so each char IS the file byte (probe-verified against
     * ground-truth md5s: all-256-values fixture + non-buffer-aligned tail). A char above 0xFF is
     * impossible under latin-1 — kept as a THROW invariant, never a silent mangle.
     */
    public static byte[] readBytes(String path) throws IOException {
        Reader reader = new BufferedReader(new FileReader(path, StandardCharsets.ISO_8859_1));
        try {
            byte[] out = new byte[8192];
            int size = 0;
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) != -1) {
                if (size + n > out.length) {
                    out = Arrays.copyOf(out, Math.max(out.length * 2, size + n));
                }
                for (int i = 0; i < n; i++) {
                    char c = buf[i];
                    if (c > 0xFF) {
                        throw new IOException("non-latin-1 char 0x" + Integer.toHexString(c)
                                + " reading " + path + " — the char≡byte invariant broke");
                    }
                    out[size++] = (byte) c;
                }
            }
            return Arrays.copyOf(out, size);
        } finally {
            reader.close();
        }
    }

    /**
     * The sidecar's {@code file=} rows: the packer-minted enumeration of the digest surface
     * ({@code /}-separated mod-root-relative paths). Same row grammar as
     * {@link ContentFingerprints#parseSidecarFingerprint} — comments and unknown rows tolerated.
     * THROWS on an empty result (a sidecar without {@code file=} rows predates B1b or is broken;
     * the client cannot enumerate {@code data/**} itself, so silence would digest nothing) and on
     * a blank/backslash path (never minted), a leading {@code /} or a {@code ..} segment (see
     * {@link #climbsOutOfModRoot}).
     */
    public static List<String> parseSidecarFileList(List<String> lines, String source) {
        List<String> files = new ArrayList<String>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0 || !"file".equals(trimmed.substring(0, eq).trim())) {
                continue;
            }
            String rel = trimmed.substring(eq + 1).trim();
            if (rel.isEmpty() || rel.indexOf('\\') >= 0 || climbsOutOfModRoot(rel)) {
                throw new IllegalArgumentException(source + " carries a malformed file= row: "
                        + quoteRow(trimmed));
            }
            files.add(rel);
        }
        if (files.isEmpty()) {
            throw new IllegalArgumentException(source + " carries no file= rows — a pre-B1b or"
                    + " broken sidecar (the client cannot self-enumerate data/** in-game)");
        }
        return files;
    }

    /**
     * True when a {@code file=} path would read outside the mod root once {@link #selfCompute} prefixes it: a
     * leading {@code /} (an absolute path) or any segment that is exactly {@code ..}. The packer mints neither (its
     * rows are mod-root-relative walks of {@code data/**}), so such a row means a hand-edited or forged list; it is
     * refused through the malformed-row throw above, never read. A name merely CONTAINING two dots
     * ({@code data/a..b.csv}) is lawful. String ops only: no {@code java.nio.file} type under the game's mod
     * classloader.
     *
     *
     */
    static boolean climbsOutOfModRoot(String rel) {
        if (rel.startsWith("/")) {
            return true;
        }
        int from = 0;
        while (from <= rel.length()) {
            int slash = rel.indexOf('/', from);
            int end = slash < 0 ? rel.length() : slash;
            if (end - from == 2 && rel.charAt(from) == '.' && rel.charAt(from + 1) == '.') {
                return true;
            }
            if (slash < 0) {
                return false;
            }
            from = slash + 1;
        }
        return false;
    }

    /** The most chars of one sidecar row an exception message quotes ({@link #quoteRow}). */
    static final int ROW_QUOTE_MAX = 64;

    /**
     * One sidecar row as an exception message may quote it: inside single quotes, at most {@link #ROW_QUOTE_MAX}
     * chars of the row, each char outside printable ASCII ({@code 0x20..0x7e}) shown as {@code ?}, a {@code '} or
     * {@code \} inside the row backslash-escaped (the row can never close the quote and plant text after it); a
     * longer row gets a {@code ...(+N chars)} marker after the closing quote, so a reader sees the quote was cut.
     * WHY: the sidecar's rows arrive from the host (the match file rides the wire), and every caller logs the
     * exception whole — a forged row must never put unbounded or control text into a player's log (the kit's
     * {@code FallbackAnnounce.sanitize} fold, re-made here). java.lang only: this class rides the kit jar under the
     * game's mod classloader, which denies by class name.
     *
     *
     */
    static String quoteRow(String row) {
        String text = row == null ? "" : row;
        int keep = Math.min(text.length(), ROW_QUOTE_MAX);
        StringBuilder b = new StringBuilder(keep + 24);
        b.append('\'');
        for (int i = 0; i < keep; i++) {
            char c = text.charAt(i);
            if (c == '\'' || c == '\\') {
                b.append('\\').append(c);
            } else {
                b.append(c >= 0x20 && c < 0x7f ? c : '?');
            }
        }
        b.append('\'');
        if (text.length() > keep) {
            b.append("...(+").append(text.length() - keep).append(" chars)");
        }
        return b.toString();
    }

    /**
     * The whole client self-compute: sidecar {@code file=} list + the
     * mod's own real local bytes → {@code dataMd5}; jar bytes → {@code jarMd5}; compose. THROWS
     * to the caller on ANY failure (missing sidecar, missing/unreadable listed file, malformed
     * rows) — the plugin's posture is LOG + send blank (fail-open client / fail-closed door).
     */
    public static String selfCompute(String modRoot, String sidecarPath, String jarPath,
            int handshakeVersion, String matchId) throws IOException {
        return selfCompute(modRoot, readLines(sidecarPath), sidecarPath, jarPath,
                handshakeVersion, matchId);
    }

    /**
     * The digest half over ALREADY-READ sidecar lines — lets a caller separate "no sidecar"
     * (its own read, the EXPECTED pre-D4.8 state) from "a LISTED file is missing" (a stale or
     * broken install — the diagnosis lane the door reasons exist for).
     */
    public static String selfCompute(String modRoot, List<String> sidecarLines,
            String sidecarLabel, String jarPath, int handshakeVersion, String matchId)
            throws IOException {
        List<String> listed = parseSidecarFileList(sidecarLines, sidecarLabel);
        String rootPrefix = modRoot.endsWith("/") || modRoot.endsWith("\\")
                ? modRoot : modRoot + "/";
        TreeMap<String, byte[]> content = new TreeMap<String, byte[]>();
        for (String rel : listed) {
            content.put(rel, readListed(rootPrefix, rel));
        }
        return ContentFingerprints.composite(
                ContentFingerprints.md5Hex(readBytes(jarPath)),
                ContentFingerprints.dataMd5(content),
                handshakeVersion, matchId);
    }

    /**
     * {@link #readBytes} of one LISTED file, its failure re-thrown as a plain {@code IOException} that quotes the row
     * through {@link #quoteRow} plus the failure's class name. The JDK's own open failure (and the char-to-byte
     * invariant throw in {@link #readBytes}) carries the whole path, which ends in the row; the cause is therefore
     * NOT chained — a logged stack trace would print it. Still a throw to the caller, so the fail-closed posture
     * (log, present a blank print, the armed door refuses) is unchanged. The catch type stays {@code IOException}:
     * the game's mod classloader denies {@code java.io.FileNotFoundException} by name, a catch type included.
     *
     *
     */
    private static byte[] readListed(String rootPrefix, String rel) throws IOException {
        try {
            return readBytes(rootPrefix + rel);
        } catch (IOException unreadable) {
            throw new IOException("listed file= row unreadable: " + quoteRow(rel) + " ("
                    + unreadable.getClass().getSimpleName() + ")");
        }
    }
}
