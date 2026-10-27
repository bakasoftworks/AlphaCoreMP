package cc.starsector.client.net;

/**
 * The client-LOCAL custom-variant file-id law (W15 v0): the ids that may name a
 * {@code saves/common/cc_variants/<id>.variant.data} file. STRICTER than the wire id law and
 * the joiner's {@code :vguard} charset: the game's common-file API
 * ({@code StarfarerSettings}) THROWS on {@code ..}, {@code :}, {@code \} and a
 * leading {@code /}, so those never reach it; the file stem must also be a lawful wire id
 * (1..{@value #MAX_CHARS} chars) because the HELLO carries it verbatim. Charset =
 * {@code [0-9A-Za-z][0-9A-Za-z._-]*} with no {@code ..} run. java.lang-only ON PURPOSE — the
 * joiner window ({@code JoinGui}, a bare-jar JVM without json.jar) shares it, so it must never
 * drag {@code org.json} into class verification (the catch-type lesson). A Windows reserved
 * device stem ({@code CON PRN AUX NUL COM0-9 LPT0-9}, any case, the part before the first
 * {@code .}) is refused too: the server-build cache
 * names a file after a server-sent id, and under the Win32 rule a reserved stem plus an extension
 * IS the device — refusing it here keeps every caller (the HELLO pick, the editor stems, the join
 * window, the cache) on one verdict.
 */
public final class LocalVariantIdLaw {
    public static final int MAX_CHARS = 64;

    /** The Win32 reserved device names, upper-case; matched ASCII case-folded by {@link #isReservedStem}. */
    private static final String[] RESERVED = {
            "CON", "PRN", "AUX", "NUL",
            "COM0", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT0", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"};

    private LocalVariantIdLaw() {
    }

    public static boolean isLawful(String id) {
        if (id == null || id.isEmpty() || id.length() > MAX_CHARS) {
            return false;
        }
        if (!alnum(id.charAt(0))) {
            return false;
        }
        for (int i = 1; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!(alnum(c) || c == '.' || c == '_' || c == '-')) {
                return false;
            }
        }
        return !id.contains("..") && !isReservedStem(id);
    }

    /**
     * Whether the part of {@code id} before
     * its first {@code .} (the whole id when it has none) is a {@link #RESERVED} device name, ASCII
     * case-folded. A STEM match, never a substring one: {@code CONSOLE}, {@code con1}, {@code COM10},
     * {@code aux_wolf}, {@code wolf.con} stay lawful; {@code con}, {@code Com1}, {@code nul.variant},
     * {@code LPT9.x.y}, {@code prn.} are refused. Called after the charset loop, so the
     * {@code regionMatches} fold only ever sees ASCII. Public since the fold so a
     * refusal note can NAME this rule ({@code JoinCore.idLawNote}); a non-ASCII stem simply never
     * matches a reserved name.
     */
    public static boolean isReservedStem(String id) {
        int dot = id.indexOf('.');
        int stemLen = dot < 0 ? id.length() : dot;
        for (String name : RESERVED) {
            if (stemLen == name.length() && id.regionMatches(true, 0, name, 0, stemLen)) {
                return true;
            }
        }
        return false;
    }

    private static boolean alnum(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }
}
