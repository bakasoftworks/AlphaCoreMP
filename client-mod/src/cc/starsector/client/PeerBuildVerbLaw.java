package cc.starsector.client;

import cc.starsector.client.net.ChatWriter;
import cc.starsector.client.net.LocalVariantIdLaw;

/**
 * wq P24 → peer-build sharing v2 (design
 * {@code variant-maker-peer-builds.md}): the pure laws of the CLIENT-LOCAL peer-build
 * save — a {@code /builds} picker pick saves the row's server-accepted custom as a local build
 * ({@code PeerBuilds.saveFromRegistry}); nothing here is ever sent. v1's client-local
 * {@code /builds} chat rows + {@code /savebuild <n> [name]} verb are RETIRED (the listing now
 * comes from the server as a KIND_BUILDS frame and the pick is the picker's). java.lang-only +
 * the two client constants ON PURPOSE: {@code ChatInputLawSelftest} pins every row here
 * game-free. The file stem is OURS ({@code EditorText.saveStem}); a same-stem clash
 * auto-suffixes {@code -2}..{@code -9} ({@link #suffixedStem}) under the per-round budget.
 */
public final class PeerBuildVerbLaw {

    /** Picker saves per round (unchanged by v2). */
    public static final int SAVES_PER_ROUND = 8;
    /** The highest auto-suffix tried on a same-stem clash ({@code <stem>-2} .. {@code <stem>-9}). */
    public static final int MAX_STEM_SUFFIX = 9;

    public static final String LIMIT_REACHED = "save limit reached for this round (" + SAVES_PER_ROUND + ")";

    private PeerBuildVerbLaw() {
    }

    public static boolean saveAllowed(int savesThisRound) {
        return savesThisRound < SAVES_PER_ROUND;
    }

    /**
     * The {@code n}-th candidate stem for a clash ({@code n} = 1 is the stem itself; 2..9 append
     * {@code -n}): the base is cut so the suffixed stem stays inside the file-id cap, a cut that
     * leaves a trailing {@code -}/{@code _} is trimmed first (the {@code saveStem} law), and the
     * result must still be lawful — a stem that cannot be suffixed lawfully throws, which the
     * caller reports as a refusal. Pure.
     */
    public static String suffixedStem(String stem, int n) {
        if (stem == null || stem.isEmpty()) {
            throw new IllegalArgumentException("empty stem");
        }
        if (n < 1 || n > MAX_STEM_SUFFIX) {
            throw new IllegalArgumentException("suffix index out of range 1.." + MAX_STEM_SUFFIX + ": " + n);
        }
        if (n == 1) {
            return stem;
        }
        String suffix = "-" + n;
        String base = stem;
        if (base.length() + suffix.length() > LocalVariantIdLaw.MAX_CHARS) {
            base = base.substring(0, LocalVariantIdLaw.MAX_CHARS - suffix.length());
        }
        while (base.endsWith("-") || base.endsWith("_")) {
            base = base.substring(0, base.length() - 1);
        }
        String out = base + suffix;
        if (!LocalVariantIdLaw.isLawful(out)) {
            throw new IllegalArgumentException("suffixed stem violates the file-id law: " + out);
        }
        return out;
    }

    /**
     * Chat-line clamp: trim, then the printable projection (a non-printable becomes {@code ?}) + the
     * cap with a {@code ~} mark — trimming FIRST keeps the cap exact.
     */
    public static String clamp(String line) {
        String s = line.trim();
        StringBuilder sb = new StringBuilder(Math.min(s.length(), ChatWriter.TEXT_MAX_CHARS));
        for (int i = 0; i < s.length() && sb.length() < ChatWriter.TEXT_MAX_CHARS; i++) {
            char c = s.charAt(i);
            sb.append(c < 0x20 || c > 0x7e ? '?' : c);
        }
        if (s.length() > ChatWriter.TEXT_MAX_CHARS) {
            sb.setCharAt(ChatWriter.TEXT_MAX_CHARS - 1, '~');
        }
        return sb.toString();
    }
}
