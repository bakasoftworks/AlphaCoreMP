package cc.starsector.client.join;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import cc.starsector.server.match.ContentFingerprintClientSurface;
import cc.starsector.server.match.ContentFingerprints;
import cc.starsector.server.match.MatchDocumentLaws;
import cc.starsector.server.runtime.HandshakeCodec;

/**
 * The joiner GUI's content-fingerprint composite for the door PROBE — the RAW value the game
 * self-computes ({@code PredictDialPlanner.selfComputeFingerprint}: {@code cc1;jar=<md5>;data=<md5>;
 * hs=<HANDSHAKE_VERSION>;match=<match.id>}) BEFORE its fold over the boot id table + mod set
 * (what the session HELLO presents); an armed door accepts this raw print on a PROBE / VCHK only.
 * Inputs: the mod root the bat hands over ({@code %~dp0.}); the packer-minted {@value #KIT_FILES} there
 * for the file LIST (its {@code file=} rows only — it carries no match term, so one list serves every
 * match); the jar at {@code jars/cc-client-mod.jar}; the bundled codec's {@code HANDSHAKE_VERSION}; and
 * for {@code match=} the staged document's id, else the cached {@code match.txt} (or the Notepad
 * {@code match.txt.txt} trap — the bat's own pair) when it parses, else
 * {@link ContentFingerprints#MATCH_NONE}. A kit that holds no match yet therefore still presents a full
 * print — the password-proven probe is how a fresh install draws the server's match document. No
 * {@value #KIT_FILES}, or any failure computing the print ⇒ blank (fail-open here, fail-closed at the
 * door — an armed door bounces {@code content_fingerprint_required}, a readable reason the status line
 * shows), with the WHY kept as a note for the status line / the {@code --probe} CLI.
 */
final class JoinFingerprint {

    /** The kit's file LIST next to the bat: the packer's {@code file=} rows, the only sidecar a print reads. */
    static final String KIT_FILES = "kit.files";

    static final class Computed {
        final String print;
        final String note;

        Computed(String print, String note) {
            this.print = print == null ? "" : print;
            this.note = note;
        }
    }

    private JoinFingerprint() {
    }

    /**
     * The print of what this folder holds: {@code match=} is the cached {@code match.txt}'s id (or the
     * Notepad twin's) when that file reads within the match cap and parses, else {@code none} with the
     * reason in the note — a missing or unusable cached match never blanks the print, it presents
     * {@code match=none}. Only a folder without {@value #KIT_FILES} presents blank. Blocking file reads —
     * call OFF the EDT. Never throws.
     *
     */
    static Computed selfCompute(Path modDir) {
        Path root = modDir.toAbsolutePath().normalize();
        Path descriptor = null;
        for (String name : new String[] {"match.txt", "match.txt.txt"}) {
            Path candidate = root.resolve(name);
            if (Files.isRegularFile(candidate)) {
                descriptor = candidate;
                break;
            }
        }
        if (descriptor == null) {
            if (!Files.isRegularFile(root.resolve(KIT_FILES))) {
                return new Computed("", "no match.txt and no " + KIT_FILES + " in this folder (not a kit"
                        + " extract?) - no content fingerprint presented");
            }
            return compute(root, ContentFingerprints.MATCH_NONE, "no match fetched yet");
        }
        String name = descriptor.getFileName().toString();
        String matchId = ContentFingerprints.MATCH_NONE;
        String noneWhy;
        try {
            String text = readCappedKitText(descriptor);
            if (text == null) {
                noneWhy = name + " is over the " + MatchDocumentLaws.TEXT_MAX + "-byte match cap - not used";
            } else {
                matchId = JoinLaunchPlan.parseDescriptor(text).matchId();
                noneWhy = null;
            }
        } catch (Exception undecodable) {
            noneWhy = name + " did not decode (" + undecodable.getClass().getSimpleName() + ")";
        }
        return compute(root, matchId, noneWhy);
    }

    /**
     * The print a STAGED match (held by the window, not yet
     * saved) will present once Launch saves it: after a refused probe on the
     * cached id, the window must probe with the staged id, or the first Launch boots without the server's mods.
     * Launch writes the staged document as {@code match.txt} ({@code JoinLaunchGates.commit}); the file LIST is
     * the kit's one {@value #KIT_FILES} either way, and {@code match=} is the staged id — the same
     * {@link ContentFingerprintClientSurface#selfCompute} call, no hashing of our own.
     * {@code matchId} null = nothing staged = {@link #selfCompute(Path)} (the cached file's id, else
     * {@code none}). Blocking file reads — call OFF the EDT. Never throws.
     *
     */
    static Computed selfCompute(Path modDir, String matchId) {
        if (matchId == null) {
            return selfCompute(modDir);
        }
        return compute(modDir.toAbsolutePath().normalize(), matchId, null);
    }

    /**
     * The half both entries share: {@value #KIT_FILES} in {@code root} + the jar, under {@code matchId}.
     * {@code noneWhy} non-null = {@code matchId} is {@code none} because the cached match was absent or
     * unusable; it rides the note so the status line says why.
     *
     */
    private static Computed compute(Path root, String matchId, String noneWhy) {
        Path sidecar = root.resolve(KIT_FILES);
        if (!Files.isRegularFile(sidecar)) {
            return new Computed("", "no fingerprint sidecar (" + KIT_FILES + ") next to this bat (not a kit"
                    + " extract?) - no content fingerprint presented");
        }
        Path jar = root.resolve("jars").resolve("cc-client-mod.jar");
        try {
            String print = ContentFingerprintClientSurface.selfCompute(root.toString(),
                    sidecar.toString(), jar.toString(), HandshakeCodec.HANDSHAKE_VERSION,
                    matchId);
            return new Computed(print, noneWhy == null ? "content fingerprint self-computed"
                    : "content fingerprint self-computed with match=" + ContentFingerprints.MATCH_NONE
                            + " (" + noneWhy + ")");
        } catch (Throwable failed) {
            return new Computed("", "content fingerprint self-compute FAILED (a listed file is"
                    + " missing or unreadable - stale/broken kit?): " + failed);
        }
    }

    /**
     * THE bounded read of a kit text file ({@code match.txt} and its Notepad twin): a file larger than
     * {@link MatchDocumentLaws#FILE_READ_MAX} bytes is refused by its size and never read; the read itself
     * stops at that many bytes (a file that grew after the size check stays bounded), and text past
     * {@link MatchDocumentLaws#TEXT_MAX} is refused — no lawful match document is that long. Returns null
     * when refused; throws on an I/O failure. Blocking — call OFF the EDT.
     *
     */
    static String readCappedKitText(Path file) throws IOException {
        if (Files.size(file) > MatchDocumentLaws.FILE_READ_MAX) {
            return null;
        }
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file)) {
            bytes = in.readNBytes(MatchDocumentLaws.FILE_READ_MAX);
        }
        if (bytes.length > MatchDocumentLaws.TEXT_MAX) {
            return null;
        }
        return new String(bytes, StandardCharsets.UTF_8);   // the writer's codec
    }
}
