package cc.starsector.server.match;

import cc.starsector.server.runtime.HandshakeCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * D4.7 B1b packer mint CLI: digest a STAGED kit mod
 * root and write the descriptor-adjacent sidecar that carries BOTH halves of the fingerprint
 * contract — the {@code fingerprint=} row (the armed door's expected composite, re-read per
 * serve) and the {@code file=} rows (the client's enumeration source: in-game mod code cannot
 * list {@code data/**}, so the kit ships the list and the client digests its own real bytes).
 *
 * <p>Invoked by the kit packer (a maintainer script) AFTER the shipped-tree transforms
 * (match.txt bake + mission trim) so the digest covers EXACTLY the bytes a joiner extracts.
 * Args: {@code modRoot=<staged CC_Client>} {@code out=<sidecar path>}; the file lands through
 * {@link #writeSidecar} (the one sidecar writer). Fail-closed THROWS: no
 * jar, no/undecodable match.txt, composite over the 128 HELLO cap (the freeze's mint assert), or a
 * non-ASCII listed path (the client reads the sidecar latin-1 — mojibake must fail at mint, not
 * mysteriously at the door).
 */
public final class ContentFingerprintMint {

    private ContentFingerprintMint() {
    }

    /** Compose the sidecar lines for a staged kit mod root (testable core; main() writes them). */
    public static List<String> mintSidecarLines(Path modRoot) throws IOException {
        TreeMap<String, byte[]> surface = ContentFingerprintTree.readContentSurface(modRoot);
        Path jar = modRoot.resolve("jars/cc-client-mod.jar");
        if (!Files.isRegularFile(jar)) {
            throw new IOException("no client jar at " + jar + " — not a packed kit mod root");
        }
        Path descriptor = modRoot.resolve("match.txt");
        if (!Files.isRegularFile(descriptor)) {
            throw new IOException("no match.txt at " + descriptor + " — the composite needs the"
                    + " baked descriptor's match.id (classic TCP kits skip the mint)");
        }
        MatchDescriptor decoded = MatchDescriptorCodec.decode(
                Files.readAllLines(descriptor, StandardCharsets.UTF_8));
        String composite = ContentFingerprints.composite(
                ContentFingerprints.md5Hex(Files.readAllBytes(jar)),
                ContentFingerprints.dataMd5(surface),
                HandshakeCodec.HANDSHAKE_VERSION,
                decoded.matchId());
        List<String> lines = new ArrayList<String>();
        lines.add("# content-fingerprint sidecar (ContentFingerprintMint)");
        lines.add("# fingerprint= is the armed door's expected composite (re-read per serve);");
        lines.add("# file= rows are the client's digest surface (mod code cannot list data/** in-game).");
        lines.add("fingerprint=" + composite);
        for (String rel : surface.keySet()) {
            for (int i = 0; i < rel.length(); i++) {
                if (rel.charAt(i) > 0x7F) {
                    throw new IOException("non-ASCII path in the digest surface: '" + rel
                            + "' — the client reads the sidecar latin-1; mint fails loud");
                }
            }
            lines.add("file=" + rel);
        }
        return lines;
    }

    /**
     * Re-key a minted sidecar to {@code matchId}: each {@code fingerprint=} row's {@code ;match=}
     * component is swapped, every other component and line kept as-is - equal to a fresh
     * {@link #mintSidecarLines} over the same kit with the new {@code match.txt}, since the data
     * surface never reads match.txt (the ops package Apply re-keys a shipped pair without its kit
     * tree). THROWS when a row lacks the component or the result exceeds the HELLO cap.
     *
     *
     * <p>Strengthened: the pack and New re-key the KIT's
     * sidecar for every shipped shape, so a half-formed input must refuse here, never ship N broken
     * copies. Also THROWS ({@code IllegalArgumentException} naming the KEY, never a value) when the
     * lines carry no {@code fingerprint=} row, a row lacks {@code data=} / {@code hs=}, its
     * {@code hs=} is not {@link HandshakeCodec#HANDSHAKE_VERSION}, or {@code matchId} is blank or
     * carries a composite separator ({@code ;} {@code =} whitespace - it would forge a component).
     * A missing {@code jar=} is NOT refused: the package Apply re-keys a stripped kit's jar-less
     * sidecar (OpsMatchApplySelftest's stripped-sidecar row pins that).
     *
     */
    public static List<String> rekeySidecarLines(List<String> lines, String matchId) {
        if (matchId == null || matchId.isBlank() || matchId.indexOf(';') >= 0 || matchId.indexOf('=') >= 0
                || matchId.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("the re-key match id is blank or carries a composite"
                    + " separator (key match)");
        }
        List<String> out = new ArrayList<String>();
        int rows = 0;
        for (String line : lines) {
            String t = line.trim();
            int eq = t.indexOf('=');
            String old = t.startsWith("#") || eq <= 0 || !"fingerprint".equals(t.substring(0, eq).trim())
                    ? null : t.substring(eq + 1).trim();
            if (old != null) {
                rows++;
                requireComposite(old);
            }
            int at = old == null ? -1 : old.indexOf(";match=");
            int end = at < 0 ? -1 : old.indexOf(';', at + 1);
            String keyed = at < 0 ? "" : old.substring(0, at) + ";match=" + matchId + (end < 0 ? "" : old.substring(end));
            out.add(old == null ? line : "fingerprint=" + ContentFingerprints.parseSidecarFingerprint(
                    List.of("fingerprint=" + keyed), "the re-keyed sidecar"));
        }
        if (rows == 0) {
            throw new IllegalArgumentException("the sidecar carries no fingerprint= row (key fingerprint)");
        }
        return out;
    }

    /** {@link #rekeySidecarLines}'s per-row key check: names the key, never echoes a value. */
    private static void requireComposite(String composite) {
        for (String key : new String[] {"data", "hs", "match"}) {
            String value = ContentFingerprints.componentOrNull(composite, key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("the sidecar's fingerprint= row carries no " + key
                        + "= component (key " + key + ")");
            }
        }
        if (!String.valueOf(HandshakeCodec.HANDSHAKE_VERSION).equals(
                ContentFingerprints.componentOrNull(composite, "hs"))) {
            throw new IllegalArgumentException("the sidecar's fingerprint= row is for another handshake"
                    + " version (key hs)");
        }
    }

    /**
     * THE one writer of a {@code .fingerprint} sidecar's bytes:
     * every package / New / pack / mint-main sidecar lands here, so the bytes cannot fork
     * between writers. Lines are joined with a PINNED {@code "\r\n"} plus a trailing CRLF in ISO-8859-1
     * (= today's shipped sidecar bytes, which Windows {@code Files.write(lines, US_ASCII)} produced; a
     * latin-1-read sidecar round-trips byte for byte) - never {@code System.lineSeparator()}, so a
     * re-key on a Linux host reproduces the kit's bytes ({@code sidecar-rekey-equals-mint} compares
     * BYTES). Written to the temp sibling {@code <file>.sidecar-tmp}, then ATOMIC_MOVEd onto
     * {@code file} (replacing it): a reader sees the old sidecar or the new one, never a torn one. A
     * failure deletes the temp and rethrows (a failed delete rides as a suppressed exception). Creates
     * no parent dir.
     */
    public static void writeSidecar(Path file, List<String> lines) throws IOException {
        byte[] bytes = sidecarBytes(lines);
        Path sidecarTemp = Paths.get(file + SIDECAR_TEMP_SUFFIX);
        try {
            Files.write(sidecarTemp, bytes);
            Files.move(sidecarTemp, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException failed) {
            try {
                Files.deleteIfExists(sidecarTemp);
            } catch (IOException | RuntimeException leak) {
                failed.addSuppressed(leak);
            }
            throw failed;
        }
    }

    /**
     * The bytes {@link #writeSidecar} lands (each line followed by {@link #SIDECAR_EOL}, ISO-8859-1): the ONE
     * rendering, for a caller that routes a sidecar as bytes through another file writer (the server-folder
     * writer re-keying a package's shape library to its kit) - so those bytes equal the file this writer would
     * have written.
     */
    public static byte[] sidecarBytes(List<String> lines) {
        StringBuilder joined = new StringBuilder();
        for (String line : lines) {
            joined.append(line).append(SIDECAR_EOL);
        }
        return joined.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /** The sidecar line terminator, PINNED (see {@link #writeSidecar}). */
    public static final String SIDECAR_EOL = "\r\n";
    /** {@link #writeSidecar}'s temp sibling suffix (not {@code *.txt}, so never offered as a shape). */
    public static final String SIDECAR_TEMP_SUFFIX = ".sidecar-tmp";

    public static void main(String[] args) throws Exception {
        String modRoot = null;
        String out = null;
        for (String arg : args) {
            if (arg.startsWith("modRoot=")) {
                modRoot = arg.substring("modRoot=".length());
            } else if (arg.startsWith("out=")) {
                out = arg.substring("out=".length());
            } else {
                throw new IllegalArgumentException("unknown arg '" + arg
                        + "' (usage: modRoot=<staged CC_Client> out=<sidecar>)");
            }
        }
        if (modRoot == null || modRoot.isBlank() || out == null || out.isBlank()) {
            throw new IllegalArgumentException("usage: modRoot=<staged CC_Client> out=<sidecar>");
        }
        List<String> lines = mintSidecarLines(Paths.get(modRoot));
        writeSidecar(Paths.get(out), lines);
        String composite = null;
        int files = 0;
        for (String line : lines) {
            if (line.startsWith("fingerprint=")) {
                composite = line.substring("fingerprint=".length());
            } else if (line.startsWith("file=")) {
                files++;
            }
        }
        System.out.println("content-fingerprint minted: " + composite
                + " files=" + files + " out=" + out);
    }
}
