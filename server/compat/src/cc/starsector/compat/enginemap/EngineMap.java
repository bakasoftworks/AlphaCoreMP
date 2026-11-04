package cc.starsector.compat.enginemap;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One engine-name map: the client mod's references to the game engine's internal classes, as compiled against the
 * Windows build, and their spelling in a separate build of the engine (the Linux download of the same game version).
 * The kit agent renames those references at class load (one mod jar, one md5, one fingerprint on every OS).
 *
 * <p>The text (UTF-8, TAB-separated, LF or CRLF; blank lines ignored):
 * <ul>
 * <li>{@value #MAGIC} as the FIRST line; then the header rows {@code # id=}, {@code # game=}, {@code # source=} (the
 * sha-256 of the Windows engine jar) and {@code # target=} (the sha-256 of the engine jar this map renames INTO, the
 * arming key); any other {@code #} line is a comment.</li>
 * <li>{@code class<TAB><windows internal name><TAB><target internal name>}.</li>
 * <li>{@code <kind><TAB><windows owner><TAB><name><TAB><windows descriptor><TAB><target name>}, kind one of
 * {@code static-method | method | static-field | field} ({@link Kind}). A target descriptor is never written: it is
 * the Windows descriptor mapped through the class rows.</li>
 * <li>{@code touched<TAB><mod class internal name>}: the client classes the transform rewrites (each under
 * {@value #SCOPE}).</li>
 * </ul>
 * Keys are EXACT and case-sensitive (two engine classes may differ only in case). A bad row FAILS the whole parse with
 * an {@link IllegalArgumentException} naming the line number and the rule, never the row's text: a half-read map
 * would link half the mod.
 *
 *
 */
public final class EngineMap {

    /** The first line of every map (the format version). */
    public static final String MAGIC = "# cc-engine-map v1";
    /** The package every {@code touched} row (and every transformed class) sits under. */
    public static final String SCOPE = "cc/starsector/client/";
    /** A map file's byte bound: a map is a few KB; anything larger is not a map. */
    public static final int MAX_BYTES = 1 << 20;

    private static final Pattern INTERNAL = Pattern.compile("([^./;\\[\\t]+/)*[^./;\\[\\t]+");
    private static final Pattern MEMBER = Pattern.compile("[^./;\\[\\t]+");
    private static final String FIELD_TYPE = "\\[*(?:[BCDFIJSZ]|L[^.;\\[\\t]+;)";
    private static final Pattern FIELD_DESC = Pattern.compile(FIELD_TYPE);
    private static final Pattern METHOD_DESC = Pattern.compile("\\((?:" + FIELD_TYPE + ")*\\)(?:V|" + FIELD_TYPE + ")");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /** A member row's kind: whether it names a method or a field, and whether that member is static. */
    public enum Kind {
        STATIC_METHOD("static-method", true, true),
        METHOD("method", true, false),
        STATIC_FIELD("static-field", false, true),
        FIELD("field", false, false);

        private final String token;
        private final boolean method;
        private final boolean isStatic;

        Kind(String token, boolean method, boolean isStatic) {
            this.token = token;
            this.method = method;
            this.isStatic = isStatic;
        }

        /** The row's first column. */
        public String token() {
            return token;
        }

        public boolean isMethod() {
            return method;
        }

        public boolean isStatic() {
            return isStatic;
        }

        static Kind ofToken(String token) {
            for (Kind k : values()) {
                if (k.token.equals(token)) {
                    return k;
                }
            }
            return null;
        }
    }

    /** One member row: the Windows key ({@code owner}, {@code name}, {@code desc}) and the target build's name. */
    public record Member(Kind kind, String owner, String name, String desc, String targetName) {
    }

    /** The exact lookup key of a member reference (owner + name + descriptor, as the Windows build spells them). */
    record Key(String owner, String name, String desc) {
    }

    private final String id;
    private final String game;
    private final String sourceSha;
    private final String targetSha;
    private final Map<String, String> classes;
    private final List<Member> members;
    private final Map<Key, Member> methods;
    private final Map<Key, Member> fields;
    private final List<String> touched;

    private EngineMap(Map<String, String> header, Map<String, String> classes, List<Member> members,
            Map<Key, Member> methods, Map<Key, Member> fields, List<String> touched) {
        this.id = header.get("id");
        this.game = header.get("game");
        this.sourceSha = header.get("source");
        this.targetSha = header.get("target");
        this.classes = Collections.unmodifiableMap(classes);
        this.members = List.copyOf(members);
        this.methods = Map.copyOf(methods);
        this.fields = Map.copyOf(fields);
        this.touched = List.copyOf(touched);
    }

    /** Parses a map from its file bytes (strict UTF-8: a malformed byte fails the parse). */
    public static EngineMap parse(byte[] utf8) {
        if (utf8.length > MAX_BYTES) {
            throw new IllegalArgumentException("engine map over " + MAX_BYTES + " bytes");
        }
        try {
            return parse(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(utf8)).toString());
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("engine map is not UTF-8");
        }
    }

    /** Parses a map from its text; any bad row throws {@link IllegalArgumentException} (line number + rule). */
    public static EngineMap parse(String text) {
        String[] lines = text.split("\n", -1);
        if (lines.length == 0 || !strip(lines[0]).equals(MAGIC)) {
            throw new IllegalArgumentException("line 1: not '" + MAGIC + "'");
        }
        Map<String, String> header = new HashMap<>();
        Map<String, String> classes = new LinkedHashMap<>();
        Set<String> classTargets = new HashSet<>();
        List<Member> members = new ArrayList<>();
        Map<Key, Member> methods = new HashMap<>();
        Map<Key, Member> fields = new HashMap<>();
        List<String> touched = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = strip(lines[i]);
            int n = i + 1;
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                headerRow(line, n, header);
                continue;
            }
            String[] f = line.split("\t", -1);
            if (f[0].equals("class")) {
                need(f.length == 3, n, "a class row has 3 columns");
                need(INTERNAL.matcher(f[1]).matches() && INTERNAL.matcher(f[2]).matches(), n,
                        "a class row names two internal class names");
                need(classes.put(f[1], f[2]) == null, n, "duplicate class key");
                need(classTargets.add(f[2]), n, "two class keys map to one target class");
            } else if (f[0].equals("touched")) {
                need(f.length == 2, n, "a touched row has 2 columns");
                need(INTERNAL.matcher(f[1]).matches() && f[1].startsWith(SCOPE), n,
                        "a touched row names a client class under " + SCOPE);
                need(!touched.contains(f[1]), n, "duplicate touched row");
                touched.add(f[1]);
            } else {
                Kind kind = Kind.ofToken(f[0]);
                need(kind != null, n, "unknown row kind (want class | touched | static-method | method | static-field"
                        + " | field)");
                need(f.length == 5, n, "a member row has 5 columns");
                need(INTERNAL.matcher(f[1]).matches(), n, "a member row's owner is an internal class name");
                need(MEMBER.matcher(f[2]).matches() && MEMBER.matcher(f[4]).matches(), n,
                        "a member row's two names are plain member names");
                need((kind.isMethod() ? METHOD_DESC : FIELD_DESC).matcher(f[3]).matches(), n,
                        "the descriptor does not fit the row's kind");
                Member m = new Member(kind, f[1], f[2], f[3], f[4]);
                need((kind.isMethod() ? methods : fields).put(new Key(f[1], f[2], f[3]), m) == null, n,
                        "duplicate member key");
                members.add(m);
            }
        }
        for (String key : List.of("id", "game", "source", "target")) {
            need(header.containsKey(key), 0, "the header row '# " + key + "=' is missing");
        }
        need(ID.matcher(header.get("id")).matches(), 0, "the id is 1-64 of [A-Za-z0-9._-]");
        need(SHA256.matcher(header.get("source")).matches() && SHA256.matcher(header.get("target")).matches(), 0,
                "source and target are lowercase sha-256 hex");
        return new EngineMap(header, classes, members, methods, fields, touched);
    }

    private static void headerRow(String line, int n, Map<String, String> header) {
        for (String key : List.of("id", "game", "source", "target")) {
            String prefix = "# " + key + "=";
            if (line.startsWith(prefix)) {
                need(header.put(key, line.substring(prefix.length()).trim()) == null, n, "duplicate header '" + key
                        + "'");
                return;
            }
        }
        need(!line.startsWith("# cc-engine-map"), n, "a second format line");
    }

    private static String strip(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private static void need(boolean ok, int line, String rule) {
        if (!ok) {
            throw new IllegalArgumentException((line > 0 ? "line " + line : "header") + ": " + rule);
        }
    }

    /** The map's id (its file name without {@code .tsv}); the agent's receipt when it arms. */
    public String id() {
        return id;
    }

    public String game() {
        return game;
    }

    /** The sha-256 (lowercase hex) of the Windows engine jar the mod is compiled against. */
    public String sourceSha() {
        return sourceSha;
    }

    /** The sha-256 (lowercase hex) of the engine jar this map renames into: the agent arms on an exact match. */
    public String targetSha() {
        return targetSha;
    }

    /** Windows internal name to target internal name, in file order (read-only). */
    public Map<String, String> classes() {
        return classes;
    }

    /** The member rows in file order. */
    public List<Member> members() {
        return members;
    }

    /** The client classes the transform rewrites, in file order. */
    public List<String> touched() {
        return touched;
    }

    /** The target internal name of a Windows class key, or {@code null} when the class is not mapped. */
    public String targetClass(String windowsInternal) {
        return classes.get(windowsInternal);
    }

    /** The method row keyed EXACTLY by the Windows owner + name + descriptor, or {@code null}. */
    public Member method(String owner, String name, String desc) {
        return methods.get(new Key(owner, name, desc));
    }

    /** The field row keyed EXACTLY by the Windows owner + name + descriptor, or {@code null}. */
    public Member field(String owner, String name, String desc) {
        return fields.get(new Key(owner, name, desc));
    }
}
