package cc.starsector.server.match;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The match-password sidecar contract — the {@code <descriptor>.password} sibling of the
 * {@code <descriptor>.fingerprint} sidecar ({@link ContentFingerprints#parseSidecarFingerprint}):
 * ONE shared secret per match, host-set, re-read per serve (D4 persistence law). The sidecar's
 * PRESENCE arms password mode; ABSENT = token mode, bit-identical. Present-but-malformed THROWS at
 * serve open (fail-closed — a half-configured door never serves silently); the mode split itself
 * (absent vs unreadable) is the caller's, since only the caller can see the filesystem.
 *
 * <p>Charset {@code [A-Za-z0-9][A-Za-z0-9-]*}, two length floors and one blocklist:
 * the MATCH password is 8 to 64 chars ({@link #PASSWORD_MIN_CHARS}), the
 * ADMIN password 12 to 64 ({@link #ADMIN_PASSWORD_MIN_CHARS}, read by
 * {@link #parseAdminSidecarPassword}), and BOTH refuse a common password ({@link #isBlocklisted}:
 * a compiled case-insensitive list of common human passwords and game/project words, plus any
 * single character repeated). Why a blocklist: the door's guess law admits about 8 guesses/s per
 * /48 source and at most about 74/s across every key, so a dictionary word a host TYPES falls in
 * hours (a 10^6 list: ≈ 17 h at 8/s, ≈ 2 h at 74/s) and the top few hundred in seconds — the
 * blocklist removes exactly the attacker's first guesses. No composition rule (length + blocklist
 * is the NIST SP 800-63B posture; composition breeds {@code Password1}). No floor makes a typed
 * dictionary word safe; the 24-char per-arm mint (~143 bits) is. The charset is
 * bat/JTO-ferry-safe on every hop (no
 * quoting/escaping hazards), within the HELLO codec's 64-char credential cap, case-sensitive
 * exact compare at the door. The FIRST character is alphanumeric (flight-#14
 * arm #1): the kit's {@code join-cc.bat} {@code :vguard} refuses a leading hyphen, so a password
 * starting with one could never be presented — the server, the ops GUI, the arm-watch mint and
 * the bat all state this one law. The value NEVER ships in a kit (packer leak-assert, build leg 7).
 *
 * <p>Pure (lines in, string out — no file IO) like its fingerprint sibling; goldens pin the parse
 * table in {@code ServerContractTests}.
 */
public final class MatchPasswords {

    /** The HELLO codec credential-field cap — the parse-time assert bound. */
    public static final int PASSWORD_MAX_CHARS = 64;

    /**
     * The match-password entropy floor: 8 chars × log2 63 ≈ 47.8 bits nominal,
     * plus the {@link #isBlocklisted} rule (the online rate law alone does NOT
     * bound a host-typed dictionary word). Enforced in {@link #parseSidecarPassword}, the ONE
     * server validator.
     */
    public static final int PASSWORD_MIN_CHARS = 8;

    /**
     * The ADMIN-password floor: 12 chars × log2 63 ≈ 71.7 bits
     * nominal, plus the same {@link #isBlocklisted} rule. The admin secret is the prize, and every
     * match-password holder can guess it through the door (or {@code /auth}, which
     * spends the DoorGuessLedger KEY path before its compare) at the stranger rates
     * — about 8 guesses/s per /48 source, at most about 74/s across every key — so it carries a
     * higher floor than the match password. Enforced in {@link #parseAdminSidecarPassword}.
     *
     */
    public static final int ADMIN_PASSWORD_MIN_CHARS = 12;

    /**
     * The compiled common-password blocklist: lower-case,
     * at most 256 entries, built once, no file IO. Content = common human passwords of 8+ chars
     * and game/project words; the "any single character repeated" rule is a CHECK in
     * {@link #isBlocklisted}, never entries. Only charset-legal ({@code [a-z0-9-]}) words are
     * listed — anything else is refused by the charset rule first.
     *
     */
    private static final Set<String> BLOCKLIST = buildBlocklist(
            // common human passwords (8+ chars)
            "password", "password1", "password12", "password123", "password1234", "passw0rd",
            "p4ssw0rd", "p4ssword", "mypassword", "passpass", "passwort", "motdepasse",
            "contrasena", "senha123", "12345678", "123456789", "1234567890", "12345678910",
            "0123456789", "01234567", "87654321", "987654321", "9876543210", "11223344",
            "112233445566", "12341234", "12344321", "123123123", "123123123123", "123321123",
            "147258369", "159753456", "123456abc", "123qweasd", "12qwaszx", "12121212",
            "13131313", "69696969", "1q2w3e4r", "1q2w3e4r5t", "1q2w3e4r5t6y", "1qaz2wsx",
            "1qaz2wsx3edc", "zaq12wsx", "zaq1zaq1", "qwertyui", "qwertyuiop", "qwerty12",
            "qwerty123", "qwerty1234", "qwer1234", "qwerasdf", "asdfghjk", "asdfghjkl",
            "asdf1234", "asdfasdf", "zxcvbnm1", "zxcvbnm123", "qazwsxedc", "qweasdzxc",
            "abcd1234", "abc12345", "abc123456", "abcdefgh", "abcdefg1", "a1b2c3d4", "a1b2c3d4e5",
            "iloveyou", "iloveyou1", "iloveyou2", "sunshine", "sunshine1", "princess", "princess1",
            "football", "football1", "baseball", "baseball1", "basketball", "superman",
            "batman123", "starwars", "trustno1", "welcome1", "welcome123", "letmein1",
            "letmein123", "whatever", "computer", "internet", "michelle", "jennifer", "jordan23",
            "charlie1", "dragon12", "dragon123", "monkey12", "monkey123", "master12", "master123",
            "shadow12", "shadow123", "liverpool", "chelsea1", "arsenal1", "mercedes", "corvette",
            "ferrari1", "mustang1", "butterfly", "sunflower", "hello123", "helloworld",
            "hello-world", "freedom1", "security", "changeme", "changeme1", "changeme123",
            "default1", "administrator", "admin123", "admin1234", "admin12345", "adminadmin",
            "rootroot", "testtest", "test1234", "test12345", "testing1", "testing123",
            "secret12", "secret123", "mypass123", "minecraft", "pokemon1", "pokemon123",
            "fortnite", "gaming123", "superstar", "blink182", "q1w2e3r4", "q1w2e3r4t5",
            "passwordpassword", "letmeinnow", "loveyou1", "football123", "11111111a",
            // game / project words
            "starsector", "starsector1", "starsector12", "starsector123", "starsector2026",
            "starsector-mp", "starsectormp", "star-sector", "starfarer", "starfarer1",
            "fractalsoftworks", "fractal-softworks", "fractalsoft", "fractal1", "multiplayer",
            "multiplayer1", "multiplayer123", "multiplayer-mod", "alphacore", "alphacore1",
            "alphacoremp", "alpha-core", "alpha-core-mp", "ccstarsector", "cc-starsector",
            "perseansector", "persean-sector", "perseanleague", "persean-league", "hegemony",
            "hegemony1", "tritachyon", "tri-tachyon", "luddicpath", "luddic-path",
            "luddicchurch", "luddic-church", "sindrian", "sindriandiktat", "sindrian-diktat",
            "domainofman", "domain-of-man", "remnant1", "remnants", "onslaught", "onslaught1",
            "paragon1", "conquest", "astralflash", "astral-flash", "gameserver", "server123",
            "dedicatedserver", "dedicated-server", "matchpassword", "match-password",
            "adminpassword", "admin-password", "joinpassword", "join-password");

    /**
     * {@code true} when {@code candidate} is a common password the door refuses:
     * case-insensitive membership in the compiled blocklist, or any single
     * character repeated (e.g. {@code aaaaaaaa}, or eight zero digits). {@code null}/empty ⇒
     * {@code false} (the blank-row law refuses those with its own message). Pure; never logs.
     *
     */
    public static boolean isBlocklisted(String candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return false;
        }
        String lower = candidate.toLowerCase(Locale.ROOT);
        char first = lower.charAt(0);
        boolean repeated = true;
        for (int i = 1; i < lower.length(); i++) {
            if (lower.charAt(i) != first) {
                repeated = false;
                break;
            }
        }
        return repeated || BLOCKLIST.contains(lower);
    }

    /**
     * The compiled blocklist's entry count (the selftest pins it
     * ≤ 256).
     */
    public static int blocklistSize() {
        return BLOCKLIST.size();
    }

    /**
     * Build the blocklist once: lower-cased, duplicates collapsed (a {@code Set.of} over a
     * duplicate would throw at class init and take the door down), unmodifiable.
     *
     */
    private static Set<String> buildBlocklist(String... words) {
        Set<String> set = new HashSet<>();
        for (String word : words) {
            set.add(word.toLowerCase(Locale.ROOT));
        }
        return Collections.unmodifiableSet(set);
    }

    /**
     * The admin-credential sidecar's file suffix — {@code <descriptor>.adminpassword}, the
     * {@code .password} sibling. Same row grammar ({@link #parseSidecarPassword}
     * reads its {@code password=} row — the FILE NAME is the distinction), same charset/cap/
     * fail-closed law, re-read per serve, NEVER in a kit. A joiner presenting THIS value in the
     * HELLO credential slot is admitted as ADMIN (per-seat WELCOME bit3); the serve-open laws
     * (present without a match password ⇒ throw; equal to the match password ⇒ throw) live at
     * the caller, which alone sees both files.
     */
    public static final String ADMIN_SIDECAR_SUFFIX = ".adminpassword";

    private MatchPasswords() {
    }

    /**
     * Constant-time credential compare: {@code MessageDigest.isEqual} over the
     * SHA-256 of each side — equal-length digests, so neither length nor first-mismatch position
     * leaks through timing (the door's match AND admin compares both ride this; a plain
     * {@code String.equals} early-outs on the first differing char). {@code null} on either side
     * = {@code false} (a HELLO with no credential never matches anything).
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(sha256(a), sha256(b));
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a mandatory JCA algorithm", e);
        }
    }

    /**
     * Parse the match password out of a password sidecar's lines: k=v rows, {@code #} comments and
     * unknown rows tolerated (future rows are allowed), the {@code password=} row REQUIRED,
     * last-row-wins (the fingerprint-sidecar idiom). Fail-closed: missing/blank/over-cap/
     * under-floor /charset-violating/blocklisted ({@link #isBlocklisted}, AMENDED)
     * THROWS — a present sidecar is an armed door and never serves half-configured.
     * {@code source} labels the error; no message ever echoes the value.
     */
    public static String parseSidecarPassword(List<String> lines, String source) {
        return parse(lines, source, PASSWORD_MIN_CHARS, "match");
    }

    /**
     * Parse the ADMIN password out of a {@code .adminpassword} sidecar's lines:
     * {@link #parseSidecarPassword}'s exact grammar and fail-closed law, with the
     * {@link #ADMIN_PASSWORD_MIN_CHARS} floor ("12 to 64") and the same blocklist. The serve-open
     * mint and the ops GUI's admin field both call THIS, so the two can never drift.
     *
     */
    public static String parseAdminSidecarPassword(List<String> lines, String source) {
        return parse(lines, source, ADMIN_PASSWORD_MIN_CHARS, "admin");
    }

    /**
     * The serve-open law for a REDACTED descriptor. The stranger
     * kit's {@code match.txt} carries {@code REDACTED-seat<N>} placeholders; served by mistake in
     * TOKEN mode (no {@code .password} sidecar) {@link MatchSeatAuthority} would key its seats by
     * those placeholders and admit anyone presenting {@code REDACTED-seat1}. Fail-closed at the
     * open: a placeholder descriptor serves ONLY under password admission (tokens are inert
     * there); a live-token descriptor passes in either mode. {@code matchPassword} null = token
     * mode (the {@code mintMatchPassword} contract). No message ever echoes a token or password.
     */
    public static void requireLiveTokensInTokenMode(MatchDescriptor descriptor, String matchPassword) {
        if (matchPassword == null && carriesRedactedTokens(descriptor)) {
            throw new IllegalArgumentException("match descriptor carries "
                    + MatchDescriptor.REDACTED_TOKEN_PREFIX + "* placeholder seat tokens (a redacted"
                    + " kit copy) and no .password sidecar is armed: a token-mode serve would mint the"
                    + " placeholders as live credentials; serve the SOURCE descriptor or arm"
                    + " <descriptor>.password");
        }
    }

    /**
     * Whether ANY seat carries a redaction placeholder — the mark of a kit COPY (the stranger
     * kit's {@code match.txt}) rather than the minted source file. Lives HERE, not on
     * {@link MatchDescriptor}: that class is kit-folded ({@code client-mod/build.ps1}
     * {@code $pilotClasses}) and a server-only law must not move the kit jar.
     */
    public static boolean carriesRedactedTokens(MatchDescriptor descriptor) {
        for (MatchDescriptor.Seat seat : descriptor.seats()) {
            if (MatchDescriptor.isRedactedToken(seat.seatToken())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The ONE parse body behind both public parsers: {@code minChars} = the floor, {@code kind} =
     * "match"/"admin" (message wording only). Order: row → blank → cap → charset → leading
     * hyphen → floor → blocklist.
     */
    private static String parse(List<String> lines, String source, int minChars, String kind) {
        String found = null;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0 || !"password".equals(trimmed.substring(0, eq).trim())) {
                continue;
            }
            found = trimmed.substring(eq + 1).trim();
        }
        if (found == null || found.isBlank()) {
            throw new IllegalArgumentException(source + " carries no non-blank password= row"
                    + " (a present password sidecar arms password mode and must carry the match"
                    + " password; token mode = DELETE the sidecar)");
        }
        if (found.length() > PASSWORD_MAX_CHARS) {
            throw new IllegalArgumentException(source + " password= row is " + found.length()
                    + " chars — over the " + PASSWORD_MAX_CHARS + " HELLO codec credential cap"
                    + " (a client could never present it)");
        }
        for (int i = 0; i < found.length(); i++) {
            char c = found.charAt(i);
            boolean legal = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-';
            if (!legal) {
                // The offending character is rendered as a code point, never
                // raw — this message reaches the serve log via the open-time stack trace.
                throw new IllegalArgumentException(source + " password= row carries U+"
                        + String.format("%04X", (int) c)
                        + " — the " + kind + " password charset is [A-Za-z0-9-] (bat/JTO"
                        + " ferry-safe on every hop)");
            }
        }
        if (found.charAt(0) == '-') {
            throw new IllegalArgumentException(source + " password= row starts with '-' — the "
                    + kind + " password must start with a letter or digit (the kit's join-cc.bat"
                    + " vguard refuses a leading hyphen)");
        }
        if (found.length() < minChars) {
            throw new IllegalArgumentException(source + " password= row is " + found.length()
                    + " chars — the " + kind + " password must be " + minChars + " to "
                    + PASSWORD_MAX_CHARS + " characters (entropy floor)");
        }
        if (isBlocklisted(found)) {
            throw new IllegalArgumentException(source + " password= row is a common password"
                    + " — pick " + minChars + "+ characters that are not a dictionary word");
        }
        return found;
    }
}
