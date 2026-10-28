package cc.starsector.client.net;

/**
 * The CURRENT BUILD file law: ONE persisted stem,
 * {@code saves/common/cc_variants/current.data}, shared by the variant editor (the game, via
 * SettingsAPI — {@link #COMMON_NAME}) and the join window (a bare-jar JVM, via NIO —
 * {@link #FILE_NAME}). Content = a single {@link LocalVariantIdLaw} stem line; the read takes the
 * first trimmed line and gates it, so a torn, lawless, oversize or absent file is NONE (the server
 * default) — never a path component beyond the fixed {@code cc_variants/<stem>.variant} load.
 * java.lang-only ON PURPOSE (the join window shares it; the catch-type lesson).
 */
public final class CurrentBuildLaw {
    /** SettingsAPI name (the game appends {@code .data}). */
    public static final String COMMON_NAME = "cc_variants/current";
    /** The file under {@code saves/common/cc_variants/} as the join window sees it. */
    public static final String FILE_NAME = "current.data";
    /** A stem is ≤ 64 chars; anything bigger than this is not a current-build file. */
    public static final int MAX_TEXT = 4096;

    private CurrentBuildLaw() {
    }

    /** The stem the file text names, or {@code ""}: first line, trimmed, id-law gated. */
    public static String parse(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_TEXT) {
            return "";
        }
        int nl = text.indexOf('\n');
        String line = (nl >= 0 ? text.substring(0, nl) : text).trim();
        return LocalVariantIdLaw.isLawful(line) ? line : "";
    }

    /**
     * The HELLO pick for a launch:
     * the current build is consumed ONLY by a launch that HOSTS the editor
     * ({@code -Dcc.editor} not {@code off}) — the editor is its sole producer, and a rig's local/observer
     * seat (editor off) sharing the install must never fly the joiner's build. Precedence: current build
     * (editor hosted) → the launch pick → {@code ""} = the server default.
     */
    public static String pick(String current, String launchPick, boolean editorHosted) {
        String cur = editorHosted && current != null ? current : "";
        return cur.isEmpty() ? (launchPick == null ? "" : launchPick) : cur;
    }
}
