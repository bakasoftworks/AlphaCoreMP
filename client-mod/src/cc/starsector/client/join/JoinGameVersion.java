package cc.starsector.client.join;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The kit's game-version read for the Launch gate
 * ({@link JoinLaunchGates#checkGame}). EXPECTED = the {@code gameVersion} this kit's {@code mod_info.json} names (the
 * same file the game reads for the mod's compatibility; no copy in code, so a version bump edits one place). FOUND =
 * the compile-time constant {@code com.fs.starfarer.Version.versionOnly} as the game's own engine jar carries it:
 * {@link #JAR} in the game root's {@code starsector-core} folder, else directly in the game root (the flat Linux
 * download). The class is never loaded (that would run engine static initialisers inside the join window):
 * its bytes are parsed by hand under fixed bounds, so a damaged or hostile jar yields UNCHECKED — never a throw, an
 * allocation past the 64 KiB entry cap, or a String built from bytes outside the version charset.
 *
 * <p>Three outcomes. OK = equal. MISMATCH = both sides read, both lawful ({@code [0-9A-Za-z._-]{1,32}}), different.
 * UNCHECKED = anything unread or unlawful, named by a fixed reason word (never a path or exception text, so the row
 * is safe in any log). Only MISMATCH refuses: a refusal on a correct install would lock a player out, while an
 * UNCHECKED launch is exactly today's behaviour. Equality is exact, stricter than the game's own mod check (which
 * tolerates a different RC): each server build pins one game version, so the kit and the game must name the same.
 */
final class JoinGameVersion {
    private JoinGameVersion() {
    }

    /** The engine-jar entry cap: the real class is about 2.3 KB, so anything past 64 KiB is not that class. */
    static final int CLASS_MAX_BYTES = 64 * 1024;
    static final String ENTRY = "com/fs/starfarer/Version.class";
    static final String JAR = "starfarer_obf.jar";
    /** The longest lawful version token (the game's own are about 10 characters). */
    static final int TOKEN_MAX = 32;

    /** The kit packer's {@code gameVersion} regex shape, copied (the kit never imports the server's packer). */
    private static final Pattern MOD_INFO_GAME = Pattern.compile("\"gameVersion\"\\s*:\\s*\"([^\"]*)\"");
    private static final byte[] THIS_CLASS = ascii("com/fs/starfarer/Version");
    private static final byte[] FIELD_NAME = ascii("versionOnly");
    private static final byte[] FIELD_DESC = ascii("Ljava/lang/String;");
    private static final byte[] CONSTANT_VALUE = ascii("ConstantValue");
    private static final int ACC_STATIC = 0x0008;
    private static final int TAG_UTF8 = 1;
    private static final int TAG_CLASS = 7;
    private static final int TAG_STRING = 8;

    enum Kind {
        OK, MISMATCH, UNCHECKED
    }

    /** One read's verdict; {@code expected} / {@code found} are lawful tokens or null (unread or unlawful). */
    static final class Result {
        final Kind kind;
        final String expected;
        final String found;
        /** A fixed word: {@code match}, {@code differs}, or the UNCHECKED cause. */
        final String why;

        private Result(Kind kind, String expected, String found, String why) {
            this.kind = kind;
            this.expected = expected;
            this.found = found;
            this.why = why;
        }

        static Result unchecked(String why) {
            return new Result(Kind.UNCHECKED, null, null, why);
        }

        /** The one line the CLI prints and the gate logs: bounded tokens only. */
        String row() {
            return "game_version expected=" + (expected == null ? "-" : expected) + " found="
                    + (found == null ? "-" : found) + " verdict=" + kind + " why=" + why;
        }
    }

    /** An unread or unlawful input, named by a fixed word; no stack trace (it is a verdict, not a fault). */
    static final class Unchecked extends Exception {
        private static final long serialVersionUID = 1L;
        final String why;

        Unchecked(String why) {
            super(why, null, false, false);
            this.why = why;
        }
    }

    /** The kit's own install: the game root = {@code modDir/../..}, the folder the join scripts launch. */
    static Result forKit(Path modDir) {
        Path root;
        try {
            root = JoinVoiceSettings.installRoot(modDir);
        } catch (RuntimeException badPath) {
            return Result.unchecked("bad_path");
        }
        return check(modDir, root);
    }

    /** Reads both sides; never throws. */
    static Result check(Path modDir, Path gameRoot) {
        String expected;
        try {
            expected = readExpected(modDir.resolve("mod_info.json"));
        } catch (Unchecked u) {
            return Result.unchecked(u.why);
        } catch (IOException | RuntimeException unreadable) {
            return Result.unchecked("mod_info_unreadable");
        }
        byte[] cls;
        try {
            Path jar = engineJar(gameRoot);
            if (jar == null) {
                return new Result(Kind.UNCHECKED, expected, null, "jar_missing");
            }
            cls = readEntry(jar);
        } catch (Unchecked u) {
            return new Result(Kind.UNCHECKED, expected, null, u.why);
        } catch (IOException | RuntimeException unreadable) {
            return new Result(Kind.UNCHECKED, expected, null, "jar_unreadable");
        }
        return fromClass(expected, cls);
    }

    /** {@code expected} (a lawful token) against the class bytes' constant; never throws. */
    static Result fromClass(String expected, byte[] cls) {
        String found;
        try {
            found = versionOnly(cls);
        } catch (Unchecked u) {
            return new Result(Kind.UNCHECKED, expected, null, u.why);
        } catch (RuntimeException fault) {
            return new Result(Kind.UNCHECKED, expected, null, "fault");
        }
        if (expected == null || !lawful(expected)) {
            return new Result(Kind.UNCHECKED, null, found, "bad_game_version");
        }
        return expected.equals(found) ? new Result(Kind.OK, expected, found, "match")
                : new Result(Kind.MISMATCH, expected, found, "differs");
    }

    /** {@code mod_info.json}, capped like the mods stage's read; {@code #} comments stripped as the game does. */
    static String readExpected(Path modInfo) throws IOException, Unchecked {
        if (!Files.isRegularFile(modInfo)) {
            throw new Unchecked("no_mod_info");
        }
        byte[] raw;
        try (InputStream in = Files.newInputStream(modInfo)) {
            raw = in.readNBytes((int) JoinModStage.MOD_INFO_MAX_BYTES + 1);
        }
        if (raw.length > JoinModStage.MOD_INFO_MAX_BYTES) {
            throw new Unchecked("mod_info_size");
        }
        return expectedIn(new String(raw, StandardCharsets.UTF_8));
    }

    static String expectedIn(String modInfoText) throws Unchecked {
        Matcher m = MOD_INFO_GAME.matcher(JoinModStage.stripHashComments(modInfoText));
        if (!m.find()) {
            throw new Unchecked("no_game_version");
        }
        String v = m.group(1);
        if (!lawful(v)) {
            throw new Unchecked("bad_game_version");
        }
        return v;
    }

    /** The engine jar: the Windows layout first, then the flat Linux one; null when neither file exists. */
    static Path engineJar(Path gameRoot) {
        Path core = gameRoot.resolve("starsector-core").resolve(JAR);
        if (Files.isRegularFile(core)) {
            return core;
        }
        Path flat = gameRoot.resolve(JAR);
        return Files.isRegularFile(flat) ? flat : null;
    }

    /** The Version class's bytes: the declared size AND the inflated count both held to the cap. */
    static byte[] readEntry(Path jar) throws IOException, Unchecked {
        try (ZipFile zip = new ZipFile(jar.toString())) {
            ZipEntry e = zip.getEntry(ENTRY);
            if (e == null || e.isDirectory()) {
                throw new Unchecked("no_version_class");
            }
            long declared = e.getSize();
            if (declared < 0 || declared > CLASS_MAX_BYTES) {
                throw new Unchecked("class_size");
            }
            byte[] b;
            try (InputStream in = zip.getInputStream(e)) {
                b = in.readNBytes(CLASS_MAX_BYTES + 1);
            }
            if (b.length > CLASS_MAX_BYTES) {
                throw new Unchecked("class_size");
            }
            return b;
        }
    }

    /**
     * The ConstantValue of the static field {@code versionOnly:Ljava/lang/String;}. Every read is bounds-checked
     * against the array; the pool count is held to {@code length / 3} before the tables are allocated; only the
     * pool tags the JVM defines (1, 3-12, 15-20) are walked; a Long / Double takes two slots and its second is
     * unusable; every index is in range and of its expected tag; each field / attribute pass consumes its header,
     * so the walk ends within {@code length / 6} passes; it stops after the fields (methods are never read). A
     * ConstantValue counts only on a static field (the JVM ignores it elsewhere), needs length 2, one per field.
     */
    static String versionOnly(byte[] b) throws Unchecked {
        Cursor r = new Cursor(b);
        if (r.u4() != 0xCAFEBABEL) {
            throw new Unchecked("bad_class");
        }
        r.skip(4);
        int count = r.u2();
        if (count < 1 || count > b.length / 3) {
            throw new Unchecked("bad_class");
        }
        byte[] tags = new byte[count];
        int[] offs = new int[count];
        for (int i = 1; i < count; i++) {
            int tag = r.u1();
            tags[i] = (byte) tag;
            offs[i] = r.pos;
            switch (tag) {
                case TAG_UTF8:
                    r.skip(r.u2());
                    break;
                case 3: case 4:
                case 9: case 10: case 11: case 12: case 17: case 18:
                    r.skip(4);
                    break;
                case 5: case 6:
                    r.skip(8);
                    if (i + 1 >= count) {
                        throw new Unchecked("bad_class");
                    }
                    i++; // the second slot keeps tag 0: no index may name it
                    break;
                case TAG_CLASS: case TAG_STRING: case 16: case 19: case 20:
                    r.skip(2);
                    break;
                case 15:
                    r.skip(3);
                    break;
                default:
                    throw new Unchecked("bad_class");
            }
        }
        r.skip(2);
        if (!utf8Is(b, offs, indirect(b, tags, offs, r.u2(), TAG_CLASS), THIS_CLASS)) {
            throw new Unchecked("other_class");
        }
        r.skip(2);
        r.skip(2L * r.u2());
        int fields = r.u2();
        String found = null;
        boolean seen = false;
        for (int f = 0; f < fields; f++) {
            r.need(8);
            int flags = r.u2();
            int name = utf8(tags, r.u2());
            int desc = utf8(tags, r.u2());
            int attrs = r.u2();
            boolean target = utf8Is(b, offs, name, FIELD_NAME) && utf8Is(b, offs, desc, FIELD_DESC);
            if (target && seen) {
                throw new Unchecked("bad_class");
            }
            seen |= target;
            boolean counts = target && (flags & ACC_STATIC) != 0;
            int constants = 0;
            for (int a = 0; a < attrs; a++) {
                r.need(6);
                int attrName = r.u2();
                long len = r.u4();
                int body = r.pos;
                r.skip(len);
                if (counts && utf8Is(b, offs, utf8(tags, attrName), CONSTANT_VALUE)) {
                    if (len != 2 || ++constants > 1) {
                        throw new Unchecked("bad_constant");
                    }
                    found = token(b, offs, indirect(b, tags, offs, u2At(b, body), TAG_STRING));
                }
            }
        }
        if (found == null) {
            throw new Unchecked("no_constant");
        }
        return found;
    }

    /** The version charset, checked per char before any comparison or String build. */
    static boolean lawful(String s) {
        if (s.isEmpty() || s.length() > TOKEN_MAX) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!tokenChar(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean tokenChar(int c) {
        return c >= '0' && c <= '9' || c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c == '.' || c == '_'
                || c == '-';
    }

    /** A Utf8 entry's bytes as a version token: length and every byte checked before the String exists. */
    private static String token(byte[] b, int[] offs, int i) throws Unchecked {
        int len = u2At(b, offs[i]);
        int at = offs[i] + 2;
        if (len < 1 || len > TOKEN_MAX) {
            throw new Unchecked("bad_found");
        }
        for (int k = 0; k < len; k++) {
            if (!tokenChar(b[at + k])) {
                throw new Unchecked("bad_found");
            }
        }
        return new String(b, at, len, StandardCharsets.US_ASCII);
    }

    /** {@code i} names a Utf8 entry (1 <= i < count, tag 1; a Long / Double second slot has tag 0). */
    private static int utf8(byte[] tags, int i) throws Unchecked {
        if (i < 1 || i >= tags.length || tags[i] != TAG_UTF8) {
            throw new Unchecked("bad_class");
        }
        return i;
    }

    /** {@code i} names a Class / String entry ({@code tag}) whose one u2 names a Utf8; returns that Utf8 index. */
    private static int indirect(byte[] b, byte[] tags, int[] offs, int i, int tag) throws Unchecked {
        if (i < 1 || i >= tags.length || tags[i] != tag) {
            throw new Unchecked("bad_class");
        }
        return utf8(tags, u2At(b, offs[i]));
    }

    /** The Utf8 entry {@code i} (its length + bytes were bounds-checked by the pool walk) equals {@code want}. */
    private static boolean utf8Is(byte[] b, int[] offs, int i, byte[] want) {
        int len = u2At(b, offs[i]);
        if (len != want.length) {
            return false;
        }
        for (int k = 0; k < len; k++) {
            if (b[offs[i] + 2 + k] != want[k]) {
                return false;
            }
        }
        return true;
    }

    private static int u2At(byte[] b, int at) {
        return (b[at] & 0xff) << 8 | (b[at + 1] & 0xff);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /** A bounds-checked big-endian reader: every read asks {@link #need} first, in long arithmetic. */
    private static final class Cursor {
        final byte[] b;
        int pos;

        Cursor(byte[] b) {
            this.b = b;
        }

        void need(long n) throws Unchecked {
            if (n < 0 || pos + n > b.length) {
                throw new Unchecked("truncated");
            }
        }

        int u1() throws Unchecked {
            need(1);
            return b[pos++] & 0xff;
        }

        int u2() throws Unchecked {
            need(2);
            int v = u2At(b, pos);
            pos += 2;
            return v;
        }

        long u4() throws Unchecked {
            need(4);
            long v = (long) u2At(b, pos) << 16 | u2At(b, pos + 2);
            pos += 4;
            return v;
        }

        void skip(long n) throws Unchecked {
            need(n);
            pos += (int) n;
        }
    }
}
