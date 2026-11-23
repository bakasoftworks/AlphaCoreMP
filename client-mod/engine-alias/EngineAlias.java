import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/**
 * The compile-time ALIAS OVERLAY: a spellable alias for every engine name Java source cannot spell, given to the
 * client's main compile only and taken back out of the compiled classes before the jar is packed.
 *
 * <p>WHY: a machine-named engine may name a member {@code for} or {@code Object.for}, or a class {@code new}: legal
 * in a class file, unspellable in a .java file. FORWARD copies the engine jars with every such name renamed by the rule
 * ({@link #part}); the source spells the alias; BACK rewrites each compiled class that holds an alias with the live
 * name, so the jar links against the real engine jars. The renaming is NAME-ONLY: one injective renaming applied to the
 * whole engine world keeps JVM resolution and overriding intact (both match on name + descriptor up the hierarchy), so
 * no hierarchy model is needed - provided no alias equals a live name anywhere on the compile class path (refused) and
 * BACK maps a member back only where its owner is an engine class (anything else is refused, naming the class).
 * Strings are never touched: a reflective literal already spells the live name.
 *
 * <p>THE RULE, two parts. Rule 1 (stateless, {@link #part}): a keyword, a reserved literal or a restricted identifier
 * becomes {@code <name>_}; a dotted name has each '.' made '_'; applied to member names and to every class-name segment
 * and '$' part. Rule 2 (stateful: it reads the jar set, {@link Packages}): an engine class whose internal name equals a
 * PACKAGE - a proper '/' prefix of any class name on the engine jars or on the other compile entries - takes '_' on its
 * outermost simple name after rule 1 ({@code p/A} becomes {@code p/A_}, {@code p/for} becomes {@code p/for__}), and its
 * nested classes follow ({@code p/A$In} becomes {@code p/A_$In}); the package keeps its name. Java source resolves a
 * qualified name to a type before a package, so while the class {@code p/A} exists no spelling reaches {@code p/A/B},
 * an import included. The class takes the alias and not the package because a by-name literal is never rewritten: with
 * the package renamed, compiled refs and literals would spell one package two ways. BACK restores a rule-2 name from
 * the table's per-CLASS rows alone, never by a segment rule, so an unrelated {@code A_} anywhere else never moves.
 *
 * <p>Modes: {@code forward --jars <a;b;c> [--cp <entries>] --cache <dir> --tool <hash>} (the aliased copies, cached by
 * the jars' sha-256 + the rule version + the tool's own hash + rule 2's hiding classes; prints {@code ENGINE-ALIAS-DIR <dir>});
 * {@code back --alias-dir <dir> --classes <dir>} (rewrites in place, then the constant-pool gate);
 * {@code table --jars <a;b;c> [--cp <entries>]} (print-only); {@code selftest --work <dir>} (fixture jars, no game).
 * Exit 0 = done, 1 = REFUSED (the rows above name why) or a self-test FAIL, 2 = a bad command line or unreadable input.
 * Names print escaped: printable ASCII passes, anything else (and '\') becomes {@code \\uXXXX}.
 *
 *
 */
public final class EngineAlias {

    /** Bumped whenever the rule's output for any name changes: it keys the cached aliased jars. */
    static final int ALIAS_RULE_VERSION = 2;

    /** The Java keywords, the reserved literals and the restricted identifiers: each becomes {@code <name>_}. */
    static final Set<String> RESERVED = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
            "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if",
            "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while",
            "true", "false", "null",
            "_", "var", "yield", "record", "sealed", "permits");

    static final String TABLE = "alias-table.tsv";
    static final String COMPLETE = "COMPLETE";
    static final int ROW_CAP = 40;
    static final String USAGE = "usage: EngineAlias forward --jars <a;b;c> [--cp <entries>] --cache <dir> --tool <hash>"
            + " | back --alias-dir <dir> --classes <dir> | table --jars <a;b;c> [--cp <entries>] | selftest --work <dir>";

    private EngineAlias() {
    }

    /** A reason to stop the build; the rows printed before it name every cause. */
    static final class Refusal extends RuntimeException {
        Refusal(String message) {
            super(message);
        }
    }

    public static void main(String[] args) {
        int code;
        try {
            code = run(args, System.out);
        } catch (Refusal r) {
            System.out.println("ENGINE-ALIAS: REFUSED " + r.getMessage());
            code = 1;
        } catch (IOException | RuntimeException e) {
            System.err.println("EngineAlias: " + e.getClass().getSimpleName() + ": " + esc(String.valueOf(e.getMessage())));
            code = 2;
        }
        System.out.flush();
        System.exit(code);
    }

    static int run(String[] argv, PrintStream out) throws IOException {
        if (argv.length == 0) {
            throw new IllegalArgumentException(USAGE);
        }
        Map<String, String> a = new HashMap<>();
        for (int i = 1; i < argv.length; i++) {
            if (!argv[i].startsWith("--") || i + 1 >= argv.length) {
                throw new IllegalArgumentException("bad argument " + esc(argv[i]) + "; " + USAGE);
            }
            a.put(argv[i], argv[++i]);
        }
        switch (argv[0]) {
            case "forward" -> {
                Forwarded f = forward(paths(need(a, "--jars")), paths(a.getOrDefault("--cp", "")),
                        Path.of(need(a, "--cache")), need(a, "--tool"), out);
                out.println("ENGINE-ALIAS-DIR " + f.dir.toAbsolutePath());
                return 0;
            }
            case "back" -> {
                return backCli(Path.of(need(a, "--alias-dir")), Path.of(need(a, "--classes")), out);
            }
            case "table" -> {
                return table(paths(need(a, "--jars")), paths(a.getOrDefault("--cp", "")), out);
            }
            case "selftest" -> {
                return selfTest(Path.of(need(a, "--work")), out);
            }
            default -> throw new IllegalArgumentException(USAGE);
        }
    }

    private static String need(Map<String, String> a, String key) {
        String v = a.get(key);
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException("missing " + key + "; " + USAGE);
        }
        return v;
    }

    private static List<Path> paths(String joined) {
        List<Path> out = new ArrayList<>();
        for (String p : joined.split(java.io.File.pathSeparator)) {
            if (!p.isEmpty()) {
                out.add(Path.of(p));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ the rule

    /**
     * The rule on one simple name. {@code nested} = a '$' part after the first of a class-name segment: one that
     * starts with a digit is javac's own anonymous / local class spelling (source never spells it) and stays, as an
     * empty part ('$$', a leading '$') does. Returns the name itself when Java source can spell it.
     */
    static String part(String s, boolean nested) {
        if (s.isEmpty() || (nested && s.charAt(0) >= '0' && s.charAt(0) <= '9')) {
            return s;
        }
        String t = s.indexOf('.') >= 0 ? s.replace('.', '_') : RESERVED.contains(s) ? s + "_" : s;
        if (RESERVED.contains(t) || !isIdentifier(t)) {
            throw new Refusal("a name Java source cannot spell: " + esc(s));
        }
        return t;
    }

    /** The rule on a member name (a constructor / initializer name is the JVM's own and stays). */
    static String member(String name) {
        if (name.equals("<init>") || name.equals("<clinit>")) {
            return name;
        }
        if (name.isEmpty()) {
            throw new Refusal("an empty member name");
        }
        return part(name, false);
    }

    /** The rule on each '/' segment and each '$' part of an internal class name. */
    static String className(String internal) {
        String[] segs = internal.split("/", -1);
        for (int i = 0; i < segs.length; i++) {
            String[] parts = segs[i].split("\\$", -1);
            for (int j = 0; j < parts.length; j++) {
                parts[j] = part(parts[j], j > 0);
            }
            segs[i] = String.join("$", parts);
        }
        return String.join("/", segs);
    }

    /**
     * The outermost class of an internal class name: the name cut at the first '$' of its last segment (a leading '$'
     * belongs to the name). Rule 2 is decided on it, so a nested class follows its outer.
     */
    static String outer(String internal) {
        int s = internal.lastIndexOf('/') + 1;
        int d = internal.indexOf('$', s + 1);
        return d < 0 ? internal : internal.substring(0, d);
    }

    /** Rule 2 on a name rule 1 already ran on: '_' after the outermost simple name (rule 1 adds no '/' and no '$'). */
    static String clash(String alias) {
        int s = alias.lastIndexOf('/') + 1;
        int d = alias.indexOf('$', s + 1);
        return d < 0 ? alias + "_" : alias.substring(0, d) + "_" + alias.substring(d);
    }

    static void addPackages(String internal, Set<String> into) {
        for (int i = internal.indexOf('/'); i > 0; i = internal.indexOf('/', i + 1)) {
            into.add(internal.substring(0, i));
        }
    }

    /**
     * Rule 2's input, read from the jar set: every package of the compile world (a proper '/' prefix of a class name on
     * the engine jars or on the other compile entries; javac sees a package wherever a class file sits, so entry names
     * suffice) and the engine classes that hide one. Only an outermost class hides: a nested class's source spelling is
     * a member of its outer, never a package's name. The hiding set keys the cached copies, so a class-path entry that
     * adds a package re-mints them.
     *
     *
     */
    static final class Packages {
        final Set<String> all = new HashSet<>();
        final TreeSet<String> hiding = new TreeSet<>();

        static Packages of(List<Path> jars, List<Path> cp) throws IOException {
            Packages pk = new Packages();
            List<String> engine = new ArrayList<>();
            for (Path j : jars) {
                classNames(j, engine);
            }
            List<String> other = new ArrayList<>();
            for (Path e : cp) {
                classNames(e, other);
            }
            engine.forEach(n -> addPackages(n, pk.all));
            other.forEach(n -> addPackages(n, pk.all));
            for (String n : engine) {
                if (outer(n).equals(n) && pk.all.contains(n)) {
                    pk.hiding.add(n);
                }
            }
            return pk;
        }

        /** The class names of a jar or a class folder, by entry path (an absent entry adds none). */
        static void classNames(Path entry, List<String> into) throws IOException {
            if (Files.isDirectory(entry)) {
                try (Stream<Path> w = Files.walk(entry)) {
                    w.filter(Files::isRegularFile).map(p -> entry.relativize(p).toString().replace('\\', '/'))
                            .filter(EngineAlias::isClassEntry)
                            .forEach(n -> into.add(n.substring(0, n.length() - ".class".length())));
                }
            } else if (Files.isRegularFile(entry)) {
                try (ZipFile zip = new ZipFile(entry.toFile())) {
                    for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements();) {
                        ZipEntry e = en.nextElement();
                        if (!e.isDirectory() && isClassEntry(e.getName())) {
                            into.add(e.getName().substring(0, e.getName().length() - ".class".length()));
                        }
                    }
                }
            }
        }
    }

    static boolean isIdentifier(String s) {
        if (s.isEmpty()) {
            return false;
        }
        int cp = s.codePointAt(0);
        if (!Character.isJavaIdentifierStart(cp) || Character.isIdentifierIgnorable(cp)) {
            return false;
        }
        for (int i = Character.charCount(cp); i < s.length(); i += Character.charCount(cp)) {
            cp = s.codePointAt(i);
            if (!Character.isJavaIdentifierPart(cp) || Character.isIdentifierIgnorable(cp)) {
                return false;
            }
        }
        return true;
    }

    /** A JVM unqualified name the class-format check accepts (a verifying JVM refuses a class referencing one that is not). */
    static boolean jvmLegal(String name, boolean method) {
        if (method && (name.equals("<init>") || name.equals("<clinit>"))) {
            return true;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '.' || c == ';' || c == '[' || c == '/' || (method && (c == '<' || c == '>'))) {
                return false;
            }
        }
        return !name.isEmpty();
    }

    // ------------------------------------------------------------------------------------------------ the table

    /**
     * The alias table (live -> alias, sorted ordinal) and the engine's classes in ALIAS spelling. {@code classes} holds
     * every renamed class by either rule and is all BACK reads for a class; {@code clashes} names the rule-2 hiding
     * classes themselves (their rows are in {@code classes} too).
     */
    static final class Table {
        final TreeMap<String, String> segments = new TreeMap<>();
        final TreeMap<String, String> members = new TreeMap<>();
        final TreeMap<String, String> classes = new TreeMap<>();
        final TreeMap<String, String> clashes = new TreeMap<>();
        final TreeSet<String> engine = new TreeSet<>();
        final Map<String, String> segmentBack = new HashMap<>();
        final Map<String, String> memberBack = new HashMap<>();
        final Map<String, String> classBack = new HashMap<>();

        int rows() {
            return segments.size() + members.size() + clashes.size();
        }

        void seal() {
            segments.forEach((live, alias) -> segmentBack.put(alias, live));
            members.forEach((live, alias) -> memberBack.put(alias, live));
            classes.forEach((live, alias) -> classBack.put(alias, live));
        }

        void write(Path p) throws IOException {
            StringBuilder b = new StringBuilder("# engine alias table, rule " + ALIAS_RULE_VERSION + "\n");
            segments.forEach((l, a) -> b.append("segment\t").append(esc(l)).append('\t').append(esc(a)).append('\n'));
            members.forEach((l, a) -> b.append("member\t").append(esc(l)).append('\t').append(esc(a)).append('\n'));
            classes.forEach((l, a) -> b.append("class\t").append(esc(l)).append('\t').append(esc(a)).append('\n'));
            clashes.forEach((l, a) -> b.append("clash\t").append(esc(l)).append('\t').append(esc(a)).append('\n'));
            for (String e : engine) {
                b.append("engine\t").append(esc(e)).append('\n');
            }
            Files.write(p, b.toString().getBytes(UTF_8));
        }

        static Table read(Path p) throws IOException {
            Table t = new Table();
            for (String line : Files.readAllLines(p, UTF_8)) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\t", -1);
                switch (f[0]) {
                    case "segment" -> t.segments.put(unesc(f[1]), unesc(f[2]));
                    case "member" -> t.members.put(unesc(f[1]), unesc(f[2]));
                    case "class" -> t.classes.put(unesc(f[1]), unesc(f[2]));
                    case "clash" -> t.clashes.put(unesc(f[1]), unesc(f[2]));
                    case "engine" -> t.engine.add(unesc(f[1]));
                    default -> throw new IOException("alias table " + p.getFileName() + ": an unknown row " + esc(line));
                }
            }
            t.seal();
            return t;
        }
    }

    // ------------------------------------------------------------------------------------------------ FORWARD

    /** FORWARD's remapper: the rule on every name it is shown, recording what changed and every live name seen. */
    static final class Aliaser extends Remapper {
        final Table table = new Table();
        final Set<String> declared = new TreeSet<>();
        final Set<String> liveClasses = new HashSet<>();
        final Set<String> liveMembers = new HashSet<>();
        final TreeSet<String> unspeakable = new TreeSet<>();
        final TreeSet<String> shared = new TreeSet<>();
        private final Map<String, String> classCache = new HashMap<>();
        private final Map<String, String> memberCache = new HashMap<>();
        private final Map<String, String> segmentByAlias = new HashMap<>();
        private final Map<String, String> memberByAlias = new HashMap<>();
        private final Map<String, String> classByAlias = new HashMap<>();
        private final Set<String> hiding;

        Aliaser(Set<String> hiding) {
            super(Opcodes.ASM9);
            this.hiding = hiding;
        }

        @Override
        public String map(String internalName) {
            String hit = classCache.get(internalName);
            if (hit != null) {
                return hit;
            }
            liveClasses.add(internalName);
            String[] segs = internalName.split("/", -1);
            for (int i = 0; i < segs.length; i++) {
                String[] parts = segs[i].split("\\$", -1);
                for (int j = 0; j < parts.length; j++) {
                    parts[j] = segment(parts[j], j > 0);
                }
                segs[i] = String.join("$", parts);
            }
            String alias = String.join("/", segs);
            String outer = outer(internalName);
            if (hiding.contains(outer)) {
                alias = clash(alias);
                if (outer.equals(internalName)) {
                    table.clashes.put(internalName, alias);
                }
            }
            if (!alias.equals(internalName)) {
                record(table.classes, classByAlias, "class", internalName, alias);
            }
            classCache.put(internalName, alias);
            return alias;
        }

        String segment(String live, boolean nested) {
            String alias;
            try {
                alias = part(live, nested);
            } catch (Refusal r) {
                unspeakable.add("class part " + esc(live));
                return live;
            }
            if (!alias.equals(live)) {
                record(table.segments, segmentByAlias, "class part", live, alias);
            }
            return alias;
        }

        String member(String live) {
            String hit = memberCache.get(live);
            if (hit != null) {
                return hit;
            }
            liveMembers.add(live);
            String alias;
            try {
                alias = EngineAlias.member(live);
            } catch (Refusal r) {
                unspeakable.add("member " + esc(live));
                alias = live;
            }
            if (!alias.equals(live)) {
                record(table.members, memberByAlias, "member", live, alias);
            }
            memberCache.put(live, alias);
            return alias;
        }

        private void record(Map<String, String> rows, Map<String, String> byAlias, String kind, String live, String alias) {
            rows.put(live, alias);
            String other = byAlias.putIfAbsent(alias, live);
            if (other != null && !other.equals(live)) {
                shared.add(kind + " names " + esc(other) + " and " + esc(live) + " both alias to " + esc(alias));
            }
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            return member(name);
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            return member(name);
        }

        @Override
        public String mapRecordComponentName(String owner, String name, String descriptor) {
            return member(name);
        }

        @Override
        public String mapAnnotationAttributeName(String descriptor, String name) {
            return name == null ? null : member(name);
        }

        /** ASM's default here is the identity: a call site named for a SAM method would otherwise keep its live name. */
        @Override
        public String mapInvokeDynamicMethodName(String name, String descriptor) {
            return member(name);
        }

        @Override
        public String mapInvokeDynamicMethodName(String name, String descriptor, Handle bsm, Object... bsmArgs) {
            return member(name);
        }

        @Override
        public String mapInnerClassName(String name, String ownerName, String innerName) {
            return innerName == null ? null : segment(innerName, true);
        }

        /**
         * An alias that equals a live name of the engine jars (declared or referenced there), or a class alias that
         * equals a package of the aliased engine jars (it would hide that package: the clash rule 2 removes, made anew).
         * The other compile entries' packages are checked on every run by the class-path scan.
         */
        List<String> aliasEqualsLive() {
            List<String> rows = new ArrayList<>();
            table.members.forEach((live, alias) -> {
                if (liveMembers.contains(alias)) {
                    rows.add("member alias " + esc(alias) + " (of " + esc(live) + ") is a live member name in the engine jars");
                }
            });
            Set<String> packages = new HashSet<>();
            table.engine.forEach(e -> addPackages(e, packages));
            table.classes.forEach((live, alias) -> {
                if (liveClasses.contains(alias)) {
                    rows.add("class alias " + esc(alias) + " (of " + esc(live) + ") is a live class name in the engine jars");
                }
                if (packages.contains(alias)) {
                    rows.add("class alias " + esc(alias) + " (of " + esc(live) + ") is a live package name in the engine jars");
                }
            });
            return rows;
        }

        /** Every reason the engine jars alone refuse this table. */
        List<String> refusals() {
            List<String> rows = new ArrayList<>();
            unspeakable.forEach(u -> rows.add("unspeakable " + u));
            shared.forEach(s -> rows.add("shared alias: " + s));
            rows.addAll(aliasEqualsLive());
            return rows;
        }

        /** Renamed class names the engine jars reference but do not declare (counted, never refused: javac cannot name them). */
        int renamedUndeclared() {
            int n = 0;
            for (String live : table.classes.keySet()) {
                if (!declared.contains(live)) {
                    n++;
                }
            }
            return n;
        }
    }

    static boolean isClassEntry(String name) {
        return name.endsWith(".class") && !name.startsWith("META-INF/") && !name.endsWith("module-info.class");
    }

    /** Runs every engine class through the rule; with {@code writeDir}, writes the aliased copies there (same file names). */
    static Aliaser aliasJars(List<Path> jars, Set<String> hiding, Path writeDir) throws IOException {
        Aliaser a = new Aliaser(hiding);
        for (Path jar : jars) {
            Set<String> written = new HashSet<>();
            try (ZipFile zip = new ZipFile(jar.toFile());
                 ZipOutputStream zo = writeDir == null ? null
                         : new ZipOutputStream(Files.newOutputStream(writeDir.resolve(jar.getFileName().toString())))) {
                for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements();) {
                    ZipEntry e = en.nextElement();
                    if (e.isDirectory() || !isClassEntry(e.getName())) {
                        continue;
                    }
                    byte[] in;
                    try (InputStream s = zip.getInputStream(e)) {
                        in = s.readAllBytes();
                    }
                    ClassReader cr = new ClassReader(in);
                    a.declared.add(cr.getClassName());
                    ClassWriter cw = new ClassWriter(0);
                    cr.accept(new ClassRemapper(cw, a), 0);
                    String name = a.map(cr.getClassName()) + ".class";
                    if (zo != null && written.add(name)) {
                        ZipEntry o = new ZipEntry(name);
                        o.setTime(0L);
                        zo.putNextEntry(o);
                        zo.write(cw.toByteArray());
                        zo.closeEntry();
                    }
                }
            }
        }
        for (String d : a.declared) {
            a.table.engine.add(a.map(d));
        }
        a.table.seal();
        return a;
    }

    static final class Forwarded {
        Path dir;
        boolean hit;
        Table table;
        Scan cp;
        long ms;
    }

    static Forwarded forward(List<Path> jars, List<Path> cp, Path cache, String tool, PrintStream out) throws IOException {
        long t0 = System.nanoTime();
        Packages pk = Packages.of(jars, cp);
        String key = key(jars, tool, pk.hiding);
        Forwarded f = new Forwarded();
        f.dir = cache.resolve("fwd-" + key);
        f.hit = Files.isRegularFile(f.dir.resolve(COMPLETE));
        if (f.hit) {
            f.table = Table.read(f.dir.resolve(TABLE));
        } else {
            Files.createDirectories(cache);
            Path tmp = Files.createTempDirectory(cache, "fwd-" + key + ".tmp");
            try {
                Aliaser a = aliasJars(jars, pk.hiding, tmp);
                List<String> refused = a.refusals();
                if (!refused.isEmpty()) {
                    printRows(out, "refused", refused);
                    throw new Refusal(refused.size() + " engine-jar row(s): the 'refused' rows above");
                }
                a.table.write(tmp.resolve(TABLE));
                Files.write(tmp.resolve(COMPLETE), (key + "\n").getBytes(UTF_8));
                if (Files.exists(f.dir)) {
                    deleteTree(f.dir); // a copy that never completed (its marker is written last)
                }
                Files.move(tmp, f.dir, StandardCopyOption.ATOMIC_MOVE);
                f.table = a.table;
            } finally {
                if (Files.exists(tmp)) {
                    deleteTree(tmp);
                }
            }
        }
        f.cp = scanClasspath(cp, f.table);
        f.ms = (System.nanoTime() - t0) / 1_000_000;
        Table t = f.table;
        out.println("ENGINE-ALIAS-FORWARD: cache=" + (f.hit ? "hit" : "miss") + " key=" + key + " table=" + t.rows()
                + " (class-segments=" + t.segments.size() + " members=" + t.members.size() + " clashes=" + t.clashes.size()
                + ") renamed-classes=" + t.classes.size() + " engine-classes=" + t.engine.size() + " classpath="
                + f.cp.entries + " entries/"
                + f.cp.classes + " classes candidates=" + f.cp.candidates + " alias-equals-live=" + f.cp.collisions.size()
                + " names-a-renamed-class=" + f.cp.renamedRefs.size() + " ms=" + f.ms);
        if (!f.cp.absent.isEmpty()) {
            out.println("classpath entries absent (skipped): " + String.join(", ", f.cp.absent));
        }
        if (!f.cp.collisions.isEmpty() || !f.cp.renamedRefs.isEmpty()) {
            printRows(out, "refused", f.cp.collisions);
            printRows(out, "refused names-a-renamed-class", f.cp.renamedRefs);
            throw new Refusal("the compile class path collides with the alias table: the 'refused' rows above");
        }
        return f;
    }

    static String key(List<Path> jars, String tool, Collection<String> hiding) throws IOException {
        StringBuilder b = new StringBuilder("rule=").append(ALIAS_RULE_VERSION).append("\ntool=").append(tool).append('\n');
        for (Path j : jars) {
            b.append(j.getFileName()).append('=').append(sha256(Files.readAllBytes(j))).append('\n');
        }
        b.append("hiding=").append(String.join(",", hiding)).append('\n');
        return sha256(b.toString().getBytes(UTF_8)).substring(0, 20);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("no SHA-256 in this JVM", e);
        }
    }

    // ------------------------------------------------------------------------------------------------ the class path

    /** What the other compile-classpath entries say about the table. */
    static final class Scan {
        int entries;
        int classes;
        int candidates;
        final TreeSet<String> collisions = new TreeSet<>();
        final TreeSet<String> renamedRefs = new TreeSet<>();
        final TreeSet<String> absent = new TreeSet<>();
    }

    /** Records the names a class declares and the classes its declarations name; renames nothing. */
    static final class Recorder extends Remapper {
        final Set<String> classes = new HashSet<>();
        final Set<String> members = new HashSet<>();

        Recorder() {
            super(Opcodes.ASM9);
        }

        @Override
        public String map(String internalName) {
            classes.add(internalName);
            return internalName;
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            members.add(name);
            return name;
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            members.add(name);
            return name;
        }

        @Override
        public String mapRecordComponentName(String owner, String name, String descriptor) {
            members.add(name);
            return name;
        }
    }

    /**
     * Every class of every non-engine compile-classpath entry: a declared class or member name that equals an alias, a
     * package that equals a class alias, or a declaration (supertype, member descriptor, signature, inner class) naming
     * a class the table renames, is a row. Only a class whose constant pool could hold a name gets the structural look.
     */
    static Scan scanClasspath(List<Path> cp, Table t) throws IOException {
        Scan s = new Scan();
        Prefilter pf = new Prefilter(t, true);
        for (Path entry : cp) {
            String label = entry.getFileName() == null ? entry.toString() : entry.getFileName().toString();
            if (Files.isDirectory(entry)) {
                s.entries++;
                List<Path> files;
                try (Stream<Path> w = Files.walk(entry)) {
                    files = w.filter(p -> p.toString().endsWith(".class") && Files.isRegularFile(p)).sorted()
                            .collect(Collectors.toList());
                }
                for (Path f : files) {
                    scanOne(Files.readAllBytes(f), label, t, pf, s);
                }
            } else if (Files.isRegularFile(entry)) {
                s.entries++;
                try (ZipFile zip = new ZipFile(entry.toFile())) {
                    for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements();) {
                        ZipEntry e = en.nextElement();
                        if (e.isDirectory() || !isClassEntry(e.getName())) {
                            continue;
                        }
                        try (InputStream in = zip.getInputStream(e)) {
                            scanOne(in.readAllBytes(), label, t, pf, s);
                        }
                    }
                }
            } else {
                s.absent.add(esc(label));
            }
        }
        return s;
    }

    private static void scanOne(byte[] bytes, String label, Table t, Prefilter pf, Scan s) {
        s.classes++;
        String own = new ClassReader(bytes).getClassName();
        for (int i = own.indexOf('/'); i > 0; i = own.indexOf('/', i + 1)) {
            String of = t.classBack.get(own.substring(0, i)); // a package spelled like a class alias: that class hides it
            if (of != null) {
                s.collisions.add("package " + esc(own.substring(0, i)) + " (" + esc(label) + ") is spelled like the alias of "
                        + esc(of));
            }
        }
        if (!pf.hit(bytes)) {
            return;
        }
        s.candidates++;
        ClassReader cr = new ClassReader(bytes);
        Recorder r = new Recorder();
        cr.accept(new ClassRemapper(null, r), ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        String self = cr.getClassName();
        String where = esc(self) + " (" + esc(label) + ")";
        if (t.classBack.containsKey(self)) {
            s.collisions.add("class " + where + " is spelled like the alias of " + esc(t.classBack.get(self)));
        }
        for (String m : r.members) {
            if (t.memberBack.containsKey(m)) {
                s.collisions.add("member " + esc(m) + " of " + where + " is spelled like the alias of " + esc(t.memberBack.get(m)));
            }
        }
        for (String c : r.classes) {
            if (t.classes.containsKey(c)) {
                s.renamedRefs.add(where + " names " + esc(c));
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ the pool

    /** A class file's constant pool: its CONSTANT_Utf8 strings by index, and which of them only a CONSTANT_String uses. */
    static final class Pool {
        final String[] utf8;
        final boolean[] stringOnly;
        final boolean[] byString;

        private Pool(String[] utf8, boolean[] stringOnly, boolean[] byString) {
            this.utf8 = utf8;
            this.stringOnly = stringOnly;
            this.byString = byString;
        }

        static Pool read(byte[] classFile) {
            ClassReader cr = new ClassReader(classFile);
            int n = cr.getItemCount();
            String[] utf8 = new String[n];
            boolean[] byString = new boolean[n];
            boolean[] byOther = new boolean[n];
            for (int i = 1; i < n; i++) {
                int off = cr.getItem(i);
                if (off == 0) {
                    continue; // the second slot of a long / double
                }
                switch (cr.readByte(off - 1)) {
                    case 1 -> utf8[i] = readUtf8(cr, off);
                    case 8 -> byString[cr.readUnsignedShort(off)] = true;
                    case 7, 16, 19, 20 -> byOther[cr.readUnsignedShort(off)] = true;
                    case 12 -> {
                        byOther[cr.readUnsignedShort(off)] = true;
                        byOther[cr.readUnsignedShort(off + 2)] = true;
                    }
                    default -> {
                    }
                }
            }
            boolean[] stringOnly = new boolean[n];
            for (int i = 1; i < n; i++) {
                stringOnly[i] = byString[i] && !byOther[i];
            }
            return new Pool(utf8, stringOnly, byString);
        }

        private static String readUtf8(ClassReader cr, int off) {
            int len = cr.readUnsignedShort(off);
            int p = off + 2;
            int end = p + len;
            char[] buf = new char[len];
            int n = 0;
            while (p < end) {
                int c = cr.readByte(p++);
                if ((c & 0x80) == 0) {
                    buf[n++] = (char) c;
                } else if ((c & 0xE0) == 0xC0) {
                    buf[n++] = (char) (((c & 0x1F) << 6) | (cr.readByte(p++) & 0x3F));
                } else {
                    buf[n++] = (char) (((c & 0x0F) << 12) | ((cr.readByte(p++) & 0x3F) << 6) | (cr.readByte(p++) & 0x3F));
                }
            }
            return new String(buf, 0, n);
        }
    }

    /** Could a class file hold an alias spelling (or, for a class-path entry, a renamed live class name)? A superset. */
    static final class Prefilter {
        final Set<String> exact = new HashSet<>();
        final Set<String> classNames = new HashSet<>();
        final Set<String> innerNames = new HashSet<>();

        Prefilter(Table t, boolean liveRenamed) {
            exact.addAll(t.members.values());
            exact.addAll(t.segments.values());
            exact.addAll(t.classes.values());
            classNames.addAll(t.classes.values());
            innerNames.addAll(t.segments.values());
            if (liveRenamed) {
                exact.addAll(t.classes.keySet());
                classNames.addAll(t.classes.keySet());
            }
        }

        boolean hit(byte[] classFile) {
            if (exact.isEmpty()) {
                return false;
            }
            for (String s : Pool.read(classFile).utf8) {
                if (s != null && matches(s)) {
                    return true;
                }
            }
            return false;
        }

        boolean matches(String s) {
            return exact.contains(s) || holdsName(s, classNames, innerNames);
        }
    }

    /**
     * A descriptor or signature naming one of {@code classNames} after an 'L', or (an inner class of a generic outer,
     * {@code Lp/A<..>.B;}) one of {@code innerNames} after a '.'.
     */
    static boolean holdsName(String s, Set<String> classNames, Set<String> innerNames) {
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c != 'L' && c != '.') {
                continue;
            }
            int j = i + 1;
            while (j < n) {
                char d = s.charAt(j);
                if (d == ';' || d == '<' || (c == '.' && d == '.')) {
                    break;
                }
                j++;
            }
            if (j < n && j > i + 1 && (c == 'L' ? classNames : innerNames).contains(s.substring(i + 1, j))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------ BACK

    /** BACK's remapper: alias -> live, a member only where its owner is an engine class; anything else is refused. */
    static final class Unaliaser extends Remapper {
        final Table t;
        final String self;
        boolean changed;
        boolean illegal;
        final TreeSet<String> refusals = new TreeSet<>();

        Unaliaser(Table t, String self) {
            super(Opcodes.ASM9);
            this.t = t;
            this.self = self;
        }

        @Override
        public String map(String internalName) {
            String live = t.classBack.get(internalName);
            if (live == null) {
                return internalName;
            }
            changed = true;
            return live;
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            return member(owner, name, true);
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            return member(owner, name, false);
        }

        @Override
        public String mapRecordComponentName(String owner, String name, String descriptor) {
            return member(owner, name, false);
        }

        @Override
        public String mapAnnotationAttributeName(String descriptor, String name) {
            String owner = descriptor != null && descriptor.startsWith("L") ? Type.getType(descriptor).getInternalName() : null;
            return name == null ? null : member(owner, name, true);
        }

        @Override
        public String mapInvokeDynamicMethodName(String name, String descriptor) {
            return indy(name, descriptor);
        }

        @Override
        public String mapInvokeDynamicMethodName(String name, String descriptor, Handle bsm, Object... bsmArgs) {
            return indy(name, descriptor);
        }

        @Override
        public String mapInnerClassName(String name, String ownerName, String innerName) {
            if (innerName == null || !t.engine.contains(name)) {
                return innerName;
            }
            String live = t.segmentBack.get(innerName);
            if (live == null) {
                return innerName;
            }
            changed = true;
            return live;
        }

        /** A call site's name maps back only when the type it makes (a lambda's interface) is an engine class. */
        private String indy(String name, String descriptor) {
            String live = t.memberBack.get(name);
            if (live == null) {
                return name;
            }
            Type type = descriptor.startsWith("(") ? Type.getReturnType(descriptor) : Type.getType(descriptor);
            if (type.getSort() == Type.OBJECT && t.engine.contains(type.getInternalName())) {
                return found(live, true);
            }
            refusals.add("call site " + esc(name) + " " + esc(descriptor) + " in " + esc(self)
                    + ": an alias whose call site type is not an engine class");
            return name;
        }

        private String member(String owner, String name, boolean method) {
            String live = t.memberBack.get(name);
            if (live == null) {
                return name;
            }
            if (owner != null && t.engine.contains(owner)) {
                return found(live, method);
            }
            refusals.add("member " + esc(name) + " on " + esc(String.valueOf(owner)) + " in " + esc(self)
                    + ": an alias on a class that is not an engine class");
            return name;
        }

        private String found(String live, boolean method) {
            changed = true;
            if (!jvmLegal(live, method)) {
                illegal = true;
            }
            return live;
        }
    }

    /**
     * The live-name rewrite done IN PLACE: every constant-pool Utf8 holding an alias spelling (a class name, a member
     * name, a descriptor or signature naming an aliased class) gets its live text at the SAME index, so javac's own pool
     * order survives - a class that reached an engine class only through a descriptor comes out byte-identical to a
     * compile against the real jars. Null when nothing changes or a string constant shares an entry whose text would
     * change (a literal is never rewritten). The caller writes it only when it re-serializes equal to the fresh-pool
     * rewrite, so a wrong guess about an entry's role can never ship.
     */
    static byte[] patchPool(byte[] in, Table t) {
        Pool pool = Pool.read(in);
        ByteArrayOutputStream o = new ByteArrayOutputStream(in.length + 64);
        o.write(in, 0, 10);
        int count = ((in[8] & 0xFF) << 8) | (in[9] & 0xFF);
        int off = 10;
        boolean any = false;
        for (int i = 1; i < count; i++) {
            int tag = in[off] & 0xFF;
            int size = switch (tag) {
                case 1 -> 3 + (((in[off + 1] & 0xFF) << 8) | (in[off + 2] & 0xFF));
                case 3, 4, 9, 10, 11, 12, 17, 18 -> 5;
                case 5, 6 -> 9;
                case 7, 8, 16, 19, 20 -> 3;
                case 15 -> 4;
                default -> throw new IllegalArgumentException("constant pool tag " + tag);
            };
            String live = tag == 1 && !pool.stringOnly[i] ? liveText(pool.utf8[i], t) : null;
            if (live != null && !live.equals(pool.utf8[i])) {
                if (pool.byString[i]) {
                    return null;
                }
                writeUtf8(o, live);
                any = true;
            } else {
                o.write(in, off, size);
            }
            off += size;
            if (tag == 5 || tag == 6) {
                i++;
            }
        }
        o.write(in, off, in.length - off);
        return any ? o.toByteArray() : null;
    }

    /** An alias text's live text: a whole class / member / class-part alias, else every aliased class a descriptor names. */
    static String liveText(String s, Table t) {
        String whole = t.classBack.get(s);
        if (whole == null) {
            whole = t.memberBack.get(s);
        }
        if (whole == null) {
            whole = t.segmentBack.get(s);
        }
        if (whole != null) {
            return whole;
        }
        StringBuilder b = new StringBuilder(s.length());
        boolean changed = false;
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == 'L' || (c == '.' && i > 0 && s.charAt(i - 1) == '>')) {
                int j = i + 1;
                while (j < n) {
                    char d = s.charAt(j);
                    if (d == ';' || d == '<' || (c == '.' && d == '.')) {
                        break;
                    }
                    j++;
                }
                String live = j < n && j > i + 1 ? (c == 'L' ? t.classBack : t.segmentBack).get(s.substring(i + 1, j)) : null;
                if (live != null) {
                    b.append(c).append(live);
                    i = j;
                    changed = true;
                    continue;
                }
            }
            b.append(c);
            i++;
        }
        return changed ? b.toString() : s;
    }

    private static void writeUtf8(ByteArrayOutputStream o, String s) {
        ByteArrayOutputStream body = new ByteArrayOutputStream(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x0001 && c <= 0x007F) {
                body.write(c);
            } else if (c <= 0x07FF) {
                body.write(0xC0 | (c >> 6));
                body.write(0x80 | (c & 0x3F));
            } else {
                body.write(0xE0 | (c >> 12));
                body.write(0x80 | ((c >> 6) & 0x3F));
                body.write(0x80 | (c & 0x3F));
            }
        }
        if (body.size() > 0xFFFF) {
            throw new IllegalStateException("a constant-pool string over 65535 bytes");
        }
        o.write(1);
        o.write(body.size() >> 8);
        o.write(body.size() & 0xFF);
        o.write(body.toByteArray(), 0, body.size());
    }

    /** The class re-serialized through a fresh pool in ASM's visit order: equal outputs = equal classes. */
    static byte[] canon(byte[] classFile) {
        ClassWriter w = new ClassWriter(0);
        new ClassReader(classFile).accept(w, 0);
        return w.toByteArray();
    }

    static final class BackResult {
        int classes;
        int candidates;
        int rewritten;
        int inPlace;
        final TreeSet<String> refusals = new TreeSet<>();
        final TreeSet<String> gate = new TreeSet<>();
        final TreeSet<String> holders = new TreeSet<>();
    }

    /**
     * Rewrites every class under {@code classesDir} that holds an alias with the live names; a class holding none is
     * never written. The rewrite is the in-place pool patch ({@link #patchPool}: javac's own layout, so a class whose
     * source spelled no alias is byte-identical to a compile against the real jars) when it re-serializes equal to the
     * FRESH-pool rewrite, else the fresh-pool rewrite itself; either way no alias survives, not even as an unreferenced
     * entry (gate one checks). Nothing is written when a class is refused or misses gate one.
     */
    static BackResult back(Table t, Path classesDir) throws IOException {
        BackResult r = new BackResult();
        Prefilter pf = new Prefilter(t, false);
        Map<Path, byte[]> writes = new LinkedHashMap<>();
        List<Path> files;
        try (Stream<Path> w = Files.walk(classesDir)) {
            files = w.filter(p -> p.toString().endsWith(".class") && Files.isRegularFile(p)).sorted()
                    .collect(Collectors.toList());
        }
        for (Path f : files) {
            r.classes++;
            byte[] in = Files.readAllBytes(f);
            if (!pf.hit(in)) {
                continue; // no alias spelling anywhere in its pool: untouched, and gate one holds for it
            }
            r.candidates++;
            ClassReader cr = new ClassReader(in);
            String self = cr.getClassName();
            Unaliaser u = new Unaliaser(t, self);
            if (t.classBack.containsKey(self)) {
                u.refusals.add("class " + esc(self) + " of ours is spelled like the alias of " + esc(t.classBack.get(self)));
            }
            ClassWriter cw = new ClassWriter(0);
            cr.accept(new ClassRemapper(cw, u), 0);
            r.refusals.addAll(u.refusals);
            byte[] out = in;
            if (u.changed) {
                out = cw.toByteArray();
                byte[] patched = u.refusals.isEmpty() ? patchPool(in, t) : null;
                if (patched != null && Arrays.equals(canon(patched), canon(out))) {
                    out = patched;
                    r.inPlace++;
                }
            }
            r.gate.addAll(gate(out, t));
            if (u.changed) {
                writes.put(f, out);
                if (u.illegal) {
                    r.holders.add(esc(self));
                }
            }
        }
        r.rewritten = writes.size();
        if (r.refusals.isEmpty() && r.gate.isEmpty()) {
            for (Map.Entry<Path, byte[]> e : writes.entrySet()) {
                Files.write(e.getKey(), e.getValue());
            }
        }
        return r;
    }

    /**
     * Gate one on one class file: no alias spelling as a class, member, descriptor, signature, inner or call-site name
     * - structurally (BACK's remapper finds nothing to map or refuse) and in the raw pool (no dead entry either; a
     * string constant's text is not a name and is skipped).
     */
    static List<String> gate(byte[] classFile, Table t) {
        List<String> rows = new ArrayList<>();
        ClassReader cr = new ClassReader(classFile);
        String self = esc(cr.getClassName());
        Unaliaser probe = new Unaliaser(t, cr.getClassName());
        cr.accept(new ClassRemapper(new ClassWriter(0), probe), 0);
        if (probe.changed || !probe.refusals.isEmpty()) {
            rows.add(self + " still references an alias");
        }
        Pool p = Pool.read(classFile);
        Set<String> classAliases = t.classBack.keySet();
        for (int i = 1; i < p.utf8.length; i++) {
            String s = p.utf8[i];
            if (s == null || p.stringOnly[i]) {
                continue;
            }
            if (t.memberBack.containsKey(s) || classAliases.contains(s) || holdsName(s, classAliases, Set.of())) {
                rows.add(self + " pool #" + i + " holds the alias spelling " + esc(s));
            }
        }
        return rows;
    }

    static int backCli(Path aliasDir, Path classesDir, PrintStream out) throws IOException {
        long t0 = System.nanoTime();
        Table t = Table.read(aliasDir.resolve(TABLE));
        BackResult r = back(t, classesDir);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        out.println("ENGINE-ALIAS: table=" + t.rows() + " (class-segments=" + t.segments.size() + " members="
                + t.members.size() + " clashes=" + t.clashes.size() + ") rewritten=" + r.rewritten + " of " + r.classes
                + " classes illegal-jvm-name-holders="
                + (r.holders.isEmpty() ? "none" : String.join(",", r.holders)) + " ms=" + ms);
        boolean pass = r.refusals.isEmpty() && r.gate.isEmpty();
        out.println("ENGINE-ALIAS-GATE: " + (pass ? "PASS" : "FAIL") + " pool-scan classes=" + r.classes + " candidates="
                + r.candidates + " alias-spellings=" + r.gate.size() + " refused=" + r.refusals.size() + " rewritten-in-place="
                + r.inPlace + " rewritten-fresh-pool=" + (r.rewritten - r.inPlace));
        if (!pass) {
            printRows(out, "refused", r.refusals);
            printRows(out, "gate", r.gate);
            throw new Refusal("BACK left the classes unwritten: the 'refused' / 'gate' rows above");
        }
        return 0;
    }

    // ------------------------------------------------------------------------------------------------ table (print-only)

    static int table(List<Path> jars, List<Path> cp, PrintStream out) throws IOException {
        long t0 = System.nanoTime();
        Aliaser a = aliasJars(jars, Packages.of(jars, cp).hiding, null);
        Table t = a.table;
        List<String> names = new ArrayList<>();
        jars.forEach(j -> names.add(esc(j.getFileName().toString())));
        out.println("ENGINE-ALIAS-TABLE rule=" + ALIAS_RULE_VERSION + " jars=" + String.join(",", names) + " engine-classes="
                + a.declared.size() + " table=" + t.rows() + " (class-segments=" + t.segments.size() + " members="
                + t.members.size() + " clashes=" + t.clashes.size() + ") renamed-classes=" + t.classes.size());
        t.segments.forEach((l, al) -> out.println("segment " + esc(l) + " -> " + esc(al)));
        t.members.forEach((l, al) -> out.println("member " + esc(l) + " -> " + esc(al)));
        t.clashes.forEach((l, al) -> out.println("clash " + esc(l) + " -> " + esc(al)));
        t.classes.forEach((l, al) -> out.println("class " + esc(l) + " -> " + esc(al)));
        List<String> equalsLive = a.aliasEqualsLive();
        boolean jarsOk = a.unspeakable.isEmpty() && a.shared.isEmpty() && equalsLive.isEmpty();
        out.println("verdict engine-jars: unspeakable=" + a.unspeakable.size() + " shared-alias=" + a.shared.size()
                + " alias-equals-live=" + equalsLive.size() + " renamed-but-undeclared=" + a.renamedUndeclared() + " "
                + (jarsOk ? "PASS" : "REFUSE"));
        printRows(out, "refused", a.refusals());
        Scan s = scanClasspath(cp, t);
        boolean cpOk = s.collisions.isEmpty() && s.renamedRefs.isEmpty();
        out.println("verdict classpath: entries=" + s.entries + " classes=" + s.classes + " candidates=" + s.candidates
                + " alias-equals-live=" + s.collisions.size() + " names-a-renamed-class=" + s.renamedRefs.size()
                + (s.absent.isEmpty() ? "" : " absent=" + String.join(",", s.absent)) + " " + (cpOk ? "PASS" : "REFUSE"));
        printRows(out, "refused", s.collisions);
        printRows(out, "refused names-a-renamed-class", s.renamedRefs);
        out.println("ENGINE-ALIAS-TABLE: " + (jarsOk && cpOk ? "PASS" : "REFUSED") + " ms=" + (System.nanoTime() - t0) / 1_000_000);
        return jarsOk && cpOk ? 0 : 1;
    }

    static void printRows(PrintStream out, String label, Collection<String> rows) {
        int i = 0;
        for (String r : rows) {
            if (i++ == ROW_CAP) {
                out.println(label + " ... and " + (rows.size() - ROW_CAP) + " more");
                return;
            }
            out.println(label + " " + r);
        }
    }

    // ------------------------------------------------------------------------------------------------ self-test

    @FunctionalInterface
    interface Check {
        boolean run() throws Exception;
    }

    private static int fails;

    private static void row(PrintStream out, String name, String flipsIf, Check check) {
        String verdict;
        try {
            verdict = check.run() ? "PASS " + name : "FAIL " + name;
        } catch (Exception | AssertionError e) {
            verdict = "FAIL " + name + ": " + e.getClass().getSimpleName() + " " + esc(String.valueOf(e.getMessage()));
        }
        if (verdict.startsWith("FAIL")) {
            fails++;
        }
        out.println(verdict + " (flips if " + flipsIf + ")");
    }

    private static boolean refused(Check c) throws Exception {
        try {
            c.run();
            return false;
        } catch (Refusal r) {
            return true;
        }
    }

    /** No game needed: fixture engine jars minted with ASM, FORWARD -> javac -> BACK, and the refusals. */
    static int selfTest(Path work, PrintStream out) throws IOException {
        fails = 0;
        deleteTree(work);
        Files.createDirectories(work);
        PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, UTF_8);

        row(out, "rule", "the rule passes a name Java cannot spell, renames one it can, or misses a keyword / literal / "
                + "restricted / dotted name or a class part", () ->
                member("for").equals("for_") && member("Object.for").equals("Object_for") && member("null").equals("null_")
                        && member("_").equals("__") && member("var").equals("var_") && member("permits").equals("permits_")
                        && member("forward").equals("forward") && member("lambda$run$0").equals("lambda$run$0")
                        && member("<init>").equals("<init>") && className("p/new").equals("p/new_")
                        && className("p/A$class").equals("p/A$class_") && className("p/A$1").equals("p/A$1")
                        && className("p/A$1Local").equals("p/A$1Local") && className("java/lang/Object").equals("java/lang/Object")
                        && refused(() -> member("9x").isEmpty()) && refused(() -> member("a-b").isEmpty())
                        && refused(() -> className("p/9x").isEmpty()) && refused(() -> member("").isEmpty()));

        Path engine = mint(work.resolve("engine").resolve("engine.jar"), false);
        Path cache = work.resolve("cache");
        Forwarded f1 = forward(List.of(engine), List.of(), cache, "selftest", sink);
        row(out, "forward-table", "a name kind (a member, a dotted member, a SAM method, a class part, a nested class "
                + "part) goes un-aliased or a name Java can spell is renamed", () ->
                !f1.hit && f1.table.members.equals(Map.of("Object.for", "Object_for", "do", "do_", "for", "for_"))
                        && f1.table.segments.equals(Map.of("class", "class_", "new", "new_"))
                        && f1.table.classes.equals(Map.of("p/A$class", "p/A$class_", "p/new", "p/new_"))
                        && f1.table.engine.equals(Set.of("p/X", "p/new_", "p/A", "p/A$class_", "p/Fn")));

        Path aliasedJar = f1.dir.resolve("engine.jar");
        long stamp = Files.getLastModifiedTime(aliasedJar).toMillis();
        row(out, "forward-cache-hit", "the cache key is unstable or a completed copy is rebuilt", () -> {
            Forwarded f2 = forward(List.of(engine), List.of(), cache, "selftest", sink);
            return f2.hit && f2.dir.equals(f1.dir) && Files.getLastModifiedTime(aliasedJar).toMillis() == stamp;
        });

        Path collide = mint(work.resolve("engine-collide").resolve("engine.jar"), true);
        row(out, "forward-refuse-live-collision", "an alias equal to a live member name of the engine jars passes", () -> {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try {
                forward(List.of(collide), List.of(), cache, "selftest", new PrintStream(b, true, UTF_8));
                return false;
            } catch (Refusal r) {
                return b.toString(UTF_8).contains("member alias for_ (of for)");
            }
        });

        Path good = work.resolve("good");
        Path bad = work.resolve("bad");
        Path src = work.resolve("src");
        Files.createDirectories(good);
        Files.createDirectories(bad);
        Files.createDirectories(src);
        List<Path> goodSrc = List.of(
                write(src.resolve("Use.java"), "public class Use {\n    public p.new_ held;\n    public p.A.class_ inner;\n"
                        + "    public int run(p.X x) {\n        x.for_(true);\n        p.Fn f = () -> { };\n        f.do_();\n"
                        + "        return x.Object_for();\n    }\n}\n"),
                write(src.resolve("Plain.java"), "public class Plain {\n    public int twice(int v) {\n        return v + v;\n    }\n}\n"));
        List<Path> badSrc = List.of(
                write(src.resolve("Other.java"), "public class Other {\n    public void for_() {\n    }\n}\n"),
                write(src.resolve("Bad.java"), "public class Bad {\n    public void call(Other o) {\n        o.for_();\n    }\n}\n"));
        String[] javacErr = {""};
        row(out, "javac-against-aliases", "the aliased jar loses a nested class's inner name or a SAM method's alias "
                + "(javac then rejects p.A.class_ or the lambda)", () -> {
            ByteArrayOutputStream e = new ByteArrayOutputStream();
            int a = javac(aliasedJar, good, goodSrc, e);
            int b = javac(aliasedJar, bad, badSrc, e);
            javacErr[0] = e.toString(UTF_8);
            return a == 0 && b == 0;
        });
        if (!javacErr[0].isBlank()) {
            out.println("javac said: " + esc(javacErr[0].strip()));
        }

        byte[] useJavac = readOrEmpty(good.resolve("Use.class"));
        byte[] plainJavac = readOrEmpty(good.resolve("Plain.class"));
        row(out, "pool-scan-detects", "the constant-pool gate stops seeing an alias spelling (it would pass a class BACK "
                + "missed) or flags a class holding none", () ->
                !gate(useJavac, f1.table).isEmpty() && gate(plainJavac, f1.table).isEmpty());

        BackResult br = back(f1.table, good);
        byte[] useOut = readOrEmpty(good.resolve("Use.class"));
        Refs refs = Refs.of(useOut);
        Map<String, Set<String>> declared = declaredMembers(engine);
        row(out, "back-live-names", "BACK misses a reference kind (a method, a dotted method, an interface call, the SAM "
                + "name of a lambda call site, a field type, an inner-class name) or a reference stops resolving in the "
                + "real engine jar", () ->
                refs.all.containsAll(List.of("call p/X.for(Z)V", "call p/X.Object.for()I", "call p/Fn.do()V",
                        "indy do()Lp/Fn;", "field held Lp/new;", "field inner Lp/A$class;", "inner p/A$class class"))
                        && refs.resolveIn(declared) && br.refusals.isEmpty() && br.gate.isEmpty() && br.rewritten == 1);

        row(out, "back-pool-scan", "an alias survives BACK as a pool entry (dead or live) or gate one stops passing a "
                + "rewritten class", () -> {
            Set<String> aliases = Set.of("for_", "Object_for", "do_", "p/new_", "p/A$class_", "class_", "new_");
            for (String s : Pool.read(useOut).utf8) {
                if (s != null && (aliases.contains(s) || s.contains("Lp/new_;") || s.contains("Lp/A$class_;"))) {
                    return false;
                }
            }
            return gate(useOut, f1.table).isEmpty();
        });

        row(out, "back-untouched-identical", "BACK rewrites a class that holds no alias", () ->
                plainJavac.length > 0 && Arrays.equals(plainJavac, readOrEmpty(good.resolve("Plain.class"))));

        row(out, "back-illegal-holders", "a class holding a member name the class-format check rejects ('Object.for') "
                + "stops being reported", () -> br.holders.equals(Set.of("Use")));

        byte[] badBefore = readOrEmpty(bad.resolve("Bad.class"));
        row(out, "back-refuse-foreign-owner", "an alias on a class that is not an engine class is mapped back or let "
                + "through, or a refused run still writes", () -> {
            BackResult bb = back(f1.table, bad);
            return bb.refusals.stream().anyMatch(s -> s.startsWith("member for_ on Bad in Bad")
                    || s.startsWith("member for_ on Other in Bad"))
                    && badBefore.length > 0 && Arrays.equals(badBefore, readOrEmpty(bad.resolve("Bad.class")));
        });

        inPlaceRows(work, src, engine, aliasedJar, f1.table, out);
        clashRows(work, out);

        out.println("ENGINE-ALIAS-SELFTEST: " + (fails == 0 ? "PASS" : "FAIL") + " failed=" + fails);
        return fails == 0 ? 0 : 1;
    }

    /**
     * BACK's in-place path, per class: the live text goes back at the same pool index only when that patch,
     * re-serialized, equals the fresh-pool rewrite (the equality is the proof), else the fresh-pool rewrite is written.
     * Three classes: one reaching a renamed class through a return descriptor alone (in place, byte-equal to the same
     * source compiled against the real jar); one holding a string constant equal to an alias next to that alias as a
     * member name (javac pools both on one text entry, so the patch is refused: the constant is kept, the fresh pool
     * taken, and gate one passes it, a string constant's text being no name); one holding that text as an annotation's
     * string value (pooled as a bare text entry shared with the member's name: the patch would rename the value too,
     * the equality refuses it, and gate one then refuses the alias-spelled value the fresh pool keeps, so BACK writes
     * nothing - a source must not spell an alias as annotation text).
     *
     *
     */
    private static void inPlaceRows(Path work, Path src, Path engine, Path aliasedJar, Table t, PrintStream out)
            throws IOException {
        Path reach = Files.createDirectories(work.resolve("reach"));
        Path reachLive = Files.createDirectories(work.resolve("reach-live"));
        Path lit = Files.createDirectories(work.resolve("lit"));
        Path ann = Files.createDirectories(work.resolve("ann"));

        List<Path> reachSrc = List.of(write(src.resolve("Reach.java"),
                "public class Reach {\n    public int run(p.X x) {\n        return x.make().hashCode();\n    }\n}\n"));
        row(out, "back-descriptor-only-byte-equal", "a class whose source spells no alias but reaches a renamed class "
                + "through a return descriptor stops taking the in-place path, or stops coming out byte-equal to the same "
                + "source compiled against the real jar", () -> {
            ByteArrayOutputStream e = new ByteArrayOutputStream();
            if (javac(aliasedJar, reach, reachSrc, e) != 0 || javac(engine, reachLive, reachSrc, e) != 0) {
                throw new IllegalStateException("SETUP-back-descriptor-only-byte-equal: javac " + esc(e.toString(UTF_8).strip()));
            }
            byte[] aliased = readOrEmpty(reach.resolve("Reach.class"));
            byte[] live = readOrEmpty(reachLive.resolve("Reach.class"));
            BackResult r = back(t, reach);
            return live.length > 0 && !Arrays.equals(aliased, live) && r.rewritten == 1 && r.inPlace == 1
                    && r.refusals.isEmpty() && r.gate.isEmpty() && Arrays.equals(live, readOrEmpty(reach.resolve("Reach.class")));
        });

        List<Path> litSrc = List.of(write(src.resolve("Lit.java"), "public class Lit {\n    public String run(p.X x) {\n"
                + "        x.for_(true);\n        return \"for_\";\n    }\n}\n"));
        row(out, "back-string-literal-kept", "a string constant equal to an alias spelling is renamed along with the "
                + "member name it shares a pool entry with, the patch is written in place over it, or gate one starts "
                + "refusing a string constant's text", () -> {
            ByteArrayOutputStream e = new ByteArrayOutputStream();
            if (javac(aliasedJar, lit, litSrc, e) != 0) {
                throw new IllegalStateException("SETUP-back-string-literal-kept: javac " + esc(e.toString(UTF_8).strip()));
            }
            Pool in = Pool.read(readOrEmpty(lit.resolve("Lit.class")));
            boolean shared = false;
            for (int i = 1; i < in.utf8.length; i++) {
                shared |= "for_".equals(in.utf8[i]) && in.byString[i] && !in.stringOnly[i];
            }
            if (!shared) {
                throw new IllegalStateException("SETUP-back-string-literal-kept: javac no longer pools the literal and the "
                        + "member name on one entry (the row would not reach the refused patch)");
            }
            BackResult r = back(t, lit);
            byte[] after = readOrEmpty(lit.resolve("Lit.class"));
            Pool p = Pool.read(after);
            boolean literal = false;
            boolean name = false;
            for (int i = 1; i < p.utf8.length; i++) {
                if ("for_".equals(p.utf8[i])) {
                    literal |= p.stringOnly[i];
                    name |= !p.stringOnly[i];
                }
            }
            List<String> g = gate(after, t);
            out.println("note back-string-literal-kept: gate one on the kept literal \"for_\" = "
                    + (g.isEmpty() ? "PASS (a string constant's text is no name)" : "REFUSED " + g));
            return r.rewritten == 1 && r.inPlace == 0 && r.refusals.isEmpty() && r.gate.isEmpty() && g.isEmpty()
                    && literal && !name && Refs.of(after).all.contains("call p/X.for(Z)V");
        });

        List<Path> annSrc = List.of(write(src.resolve("Ann.java"), "public class Ann {\n    @Deprecated(since = \"for_\")\n"
                + "    public void run(p.X x) {\n        x.for_(true);\n    }\n}\n"));
        row(out, "back-annotation-text-refused", "the in-place patch is written although it renamed an annotation's "
                + "string value too (javac pools that value as a bare text entry shared with the member's name), gate one "
                + "stops refusing the alias-spelled value the fresh-pool rewrite keeps, or a refused run still writes", () -> {
            ByteArrayOutputStream e = new ByteArrayOutputStream();
            if (javac(aliasedJar, ann, annSrc, e) != 0) {
                throw new IllegalStateException("SETUP-back-annotation-text-refused: javac " + esc(e.toString(UTF_8).strip()));
            }
            byte[] before = readOrEmpty(ann.resolve("Ann.class"));
            BackResult r = back(t, ann);
            boolean refused = r.gate.stream().anyMatch(s -> s.startsWith("Ann pool #") && s.endsWith(" holds the alias spelling for_"));
            out.println("note back-annotation-text-refused: gate one on the annotation value \"for_\" = "
                    + (refused ? "REFUSED (BACK writes nothing)" : "no refusal"));
            return refused && r.inPlace == 0 && r.refusals.isEmpty() && before.length > 0
                    && Arrays.equals(before, readOrEmpty(ann.resolve("Ann.class")));
        });
    }

    /**
     * Rule 2, FORWARD -> javac -> BACK on the clash fixture ({@link #mintClash}): the hidden package is reachable, the
     * hiding class and its nested class are spelled with the alias and come back live, an unrelated {@code A_} never
     * moves, a source spelling no alias is byte-identical, and every collision of the alias is refused by name.
     */
    private static void clashRows(Path work, PrintStream out) throws IOException {
        PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, UTF_8);
        Path jar = mintClash(work.resolve("clash-engine").resolve("engine.jar"), "");
        Path cache = work.resolve("clash-cache");
        Forwarded f = forward(List.of(jar), List.of(), cache, "selftest", sink);
        Path aliased = f.dir.resolve("engine.jar");
        Path src = Files.createDirectories(work.resolve("clash-src"));
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        List<Path> fwdSrc = List.of(write(src.resolve("Fwd.java"),
                "public class Fwd {\n    public void run() {\n        p.A.B.F = true;\n    }\n}\n"));
        row(out, "clash-forward-javac", "rule 2 is dropped (the class p/A keeps hiding the package p/A, so p.A.B does not "
                + "resolve) or the fixture stops reproducing the clash against the real jar", () ->
                javac(jar, Files.createDirectories(work.resolve("clash-fwd-real")), fwdSrc, new ByteArrayOutputStream()) != 0
                        && javac(aliased, Files.createDirectories(work.resolve("clash-fwd")), fwdSrc, err) == 0);

        Path good = Files.createDirectories(work.resolve("clash-good"));
        List<Path> useSrc = List.of(write(src.resolve("ClashUse.java"), "public class ClashUse {\n    public p.A_ held;\n"
                + "    public p.A_.In nested;\n    public q.A_ other;\n    public r.A_.Z deep;\n    public p.for__ kw;\n"
                + "    public void run() {\n        p.A.B.F = true;\n        p.for_.C.G = 1;\n    }\n}\n"));
        int useCode = javac(aliased, good, useSrc, err);
        BackResult br = back(f.table, good);
        byte[] useOut = readOrEmpty(good.resolve("ClashUse.class"));
        Refs refs = Refs.of(useOut);
        row(out, "clash-back-real-names", "BACK leaves A_ in a class (a field type, a class constant) or a reference "
                + "stops resolving in the real jar", () -> {
            for (String s : Pool.read(useOut).utf8) {
                if (s != null && (s.startsWith("p/A_") || s.contains("Lp/A_"))) {
                    return false;
                }
            }
            return useCode == 0 && refs.all.containsAll(List.of("get p/A/B.F Z", "field held Lp/A;"))
                    && refs.resolveIn(declaredMembers(jar)) && br.refusals.isEmpty() && br.gate.isEmpty();
        });

        row(out, "clash-nested-follows", "a nested class keeps the old outer (p.A_.In then does not resolve) or BACK "
                + "leaves its alias", () ->
                "p/A_$In".equals(f.table.classes.get("p/A$In")) && entries(aliased).contains("p/A_$In.class")
                        && refs.all.containsAll(List.of("field nested Lp/A$In;", "inner p/A$In In")));

        row(out, "clash-alias-collides", "a rule-2 alias equal to a live class, an engine package or a class-path "
                + "package passes, or its refusal does not name the alias", () -> {
            Path cpCollide = work.resolve("clash-cp-collide");
            Files.createDirectories(cpCollide.resolve("p").resolve("A_"));
            Files.write(cpCollide.resolve("p").resolve("A_").resolve("K.class"), close(open("p/A_/K")));
            return refusedWith(List.of(mintClash(work.resolve("clash-live-class").resolve("engine.jar"), "class")),
                    List.of(), cache, "class alias p/A_ (of p/A) is a live class name in the engine jars")
                    && refusedWith(List.of(mintClash(work.resolve("clash-live-package").resolve("engine.jar"), "package")),
                            List.of(), cache, "class alias p/A_ (of p/A) is a live package name in the engine jars")
                    && refusedWith(List.of(jar), List.of(cpCollide), cache,
                            "package p/A_ (clash-cp-collide) is spelled like the alias of p/A");
        });

        row(out, "clash-unrelated-untouched", "FORWARD or BACK moves an A_ that hides nothing (a segment rule instead "
                + "of a per-class one)", () ->
                f.table.classes.keySet().stream().noneMatch(c -> c.startsWith("q/") || c.startsWith("r/"))
                        && entries(aliased).containsAll(List.of("q/A_.class", "r/A_/Z.class"))
                        && refs.all.containsAll(List.of("field other Lq/A_;", "field deep Lr/A_/Z;")));

        row(out, "clash-no-alias-identical", "a class whose source spells no alias, compiled with a clash present, is "
                + "not byte-identical to the same source compiled against the real jar", () -> {
            List<Path> reach = List.of(write(src.resolve("Reach2.java"), "public class Reach2 {\n    public int run(p.R r, "
                    + "q.A_ q) {\n        return r.make().hashCode() + q.hashCode();\n    }\n}\n"));
            Path real = Files.createDirectories(work.resolve("clash-reach-real"));
            Path viaAlias = Files.createDirectories(work.resolve("clash-reach"));
            if (javac(jar, real, reach, err) != 0 || javac(aliased, viaAlias, reach, err) != 0) {
                return false;
            }
            BackResult rb = back(f.table, viaAlias);
            byte[] want = readOrEmpty(real.resolve("Reach2.class"));
            return rb.rewritten == 1 && want.length > 0 && Arrays.equals(want, readOrEmpty(viaAlias.resolve("Reach2.class")));
        });

        row(out, "clash-keyword-composes", "rule 2 runs before rule 1 (p/for would alias onto the package p/for_) or "
                + "BACK misses the composed name", () ->
                f.table.clashes.equals(Map.of("p/A", "p/A_", "p/for", "p/for__"))
                        && "p/for_/C".equals(f.table.classes.get("p/for/C"))
                        && refs.all.containsAll(List.of("field kw Lp/for;", "get p/for/C.G I")));

        row(out, "clash-classpath-package", "a package that only the other compile entries hold is not read, or the cache "
                + "key ignores it (a copy minted without the alias is reused)", () -> {
            Path cp = work.resolve("clash-cp");
            Files.createDirectories(cp.resolve("p").resolve("K"));
            Files.write(cp.resolve("p").resolve("K").resolve("Q.class"), close(open("p/K/Q")));
            Forwarded withCp = forward(List.of(jar), List.of(cp), cache, "selftest", sink);
            return !f.table.clashes.containsKey("p/K") && "p/K_".equals(withCp.table.clashes.get("p/K"))
                    && !withCp.dir.equals(f.dir);
        });
        if (!err.toString(UTF_8).isBlank()) {
            out.println("javac said: " + esc(err.toString(UTF_8).strip()));
        }
    }

    private static boolean refusedWith(List<Path> jars, List<Path> cp, Path cache, String row) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try {
            forward(jars, cp, cache, "selftest", new PrintStream(b, true, UTF_8));
            return false;
        } catch (Refusal r) {
            return b.toString(UTF_8).contains(row);
        }
    }

    private static Set<String> entries(Path jar) throws IOException {
        Set<String> names = new HashSet<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            zip.stream().forEach(e -> names.add(e.getName()));
        }
        return names;
    }

    /**
     * The clash fixture: p/A (nested p/A$In) beside the package p/A (p/A/B, a static boolean F); p/for beside the
     * package p/for (p/for/C, a static int G); p/R {make()Lp/A;}; p/K (hides only a class-path package); the unrelated
     * q/A_ and r/A_/Z. {@code variant} "class" adds a live p/A_, "package" a live p/A_/Z.
     */
    private static Path mintClash(Path jar, String variant) throws IOException {
        Map<String, byte[]> cls = new LinkedHashMap<>();
        ClassWriter w = open("p/A");
        w.visitInnerClass("p/A$In", "p/A", "In", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
        cls.put("p/A", close(w));
        w = open("p/A$In");
        w.visitInnerClass("p/A$In", "p/A", "In", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
        cls.put("p/A$In", close(w));
        w = open("p/A/B");
        w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "F", "Z", null, null).visitEnd();
        cls.put("p/A/B", close(w));
        cls.put("p/for", close(open("p/for")));
        w = open("p/for/C");
        w.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "G", "I", null, null).visitEnd();
        cls.put("p/for/C", close(w));
        w = open("p/R");
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "make", "()Lp/A;", null, null);
        m.visitCode();
        m.visitInsn(Opcodes.ACONST_NULL);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cls.put("p/R", close(w));
        for (String n : List.of("p/K", "q/A_", "r/A_/Z")) {
            cls.put(n, close(open(n)));
        }
        if (variant.equals("class")) {
            cls.put("p/A_", close(open("p/A_")));
        } else if (variant.equals("package")) {
            cls.put("p/A_/Z", close(open("p/A_/Z")));
        }
        return writeJar(jar, cls);
    }

    private static Path writeJar(Path jar, Map<String, byte[]> cls) throws IOException {
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> e : cls.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey() + ".class"));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return jar;
    }

    private static Path write(Path p, String text) throws IOException {
        Files.writeString(p, text, UTF_8);
        return p;
    }

    private static byte[] readOrEmpty(Path p) throws IOException {
        return Files.isRegularFile(p) ? Files.readAllBytes(p) : new byte[0];
    }

    private static int javac(Path cp, Path outDir, List<Path> sources, ByteArrayOutputStream err) {
        JavaCompiler c = ToolProvider.getSystemJavaCompiler();
        if (c == null) {
            throw new IllegalStateException("no system Java compiler (run the tool on a JDK)");
        }
        List<String> args = new ArrayList<>(List.of("-encoding", "UTF-8", "--release", "17", "-proc:none", "-cp",
                cp.toString(), "-d", outDir.toString()));
        sources.forEach(s -> args.add(s.toString()));
        return c.run(null, err, err, args.toArray(new String[0]));
    }

    /**
     * The fixture engine: p/X {for(Z)V, Object.for()I, make()Lp/new;}, p/new, p/A + p/A$class (inner name 'class'),
     * p/Fn {do()V}. {@code make} is spellable: a source reaches the renamed p/new through its return type alone.
     */
    private static Path mint(Path jar, boolean collide) throws IOException {
        Files.createDirectories(jar.getParent());
        Map<String, byte[]> cls = new LinkedHashMap<>();
        ClassWriter w = open("p/X");
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "for", "(Z)V", null, null);
        m.visitCode();
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        m = w.visitMethod(Opcodes.ACC_PUBLIC, "make", "()Lp/new;", null, null);
        m.visitCode();
        m.visitInsn(Opcodes.ACONST_NULL);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        m = w.visitMethod(Opcodes.ACC_PUBLIC, "Object.for", "()I", null, null);
        m.visitCode();
        m.visitInsn(Opcodes.ICONST_0);
        m.visitInsn(Opcodes.IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cls.put("p/X", close(w));
        cls.put("p/new", close(open("p/new")));
        w = open("p/A");
        w.visitInnerClass("p/A$class", "p/A", "class", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
        cls.put("p/A", close(w));
        w = open("p/A$class");
        w.visitInnerClass("p/A$class", "p/A", "class", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
        cls.put("p/A$class", close(w));
        ClassWriter fn = new ClassWriter(0);
        fn.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, "p/Fn", null,
                "java/lang/Object", null);
        fn.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "do", "()V", null, null).visitEnd();
        fn.visitEnd();
        cls.put("p/Fn", fn.toByteArray());
        if (collide) {
            w = open("p/Y");
            m = w.visitMethod(Opcodes.ACC_PUBLIC, "for_", "()V", null, null);
            m.visitCode();
            m.visitInsn(Opcodes.RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
            cls.put("p/Y", close(w));
        }
        return writeJar(jar, cls);
    }

    private static ClassWriter open(String name) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        return w;
    }

    private static byte[] close(ClassWriter w) {
        w.visitEnd();
        return w.toByteArray();
    }

    /** Owner -> its declared {@code name + descriptor} set, for every class of a jar (the classes themselves as keys). */
    private static Map<String, Set<String>> declaredMembers(Path jar) throws IOException {
        Map<String, Set<String>> out = new HashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements();) {
                ZipEntry e = en.nextElement();
                if (!isClassEntry(e.getName())) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(e)) {
                    ClassReader cr = new ClassReader(in.readAllBytes());
                    Set<String> names = new HashSet<>();
                    cr.accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                            names.add(name + desc);
                            return null;
                        }

                        @Override
                        public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                            names.add(name + desc);
                            return null;
                        }
                    }, ClassReader.SKIP_CODE);
                    out.put(cr.getClassName(), names);
                }
            }
        }
        return out;
    }

    /** Every reference a compiled fixture class makes, as text rows, plus its member and class references. */
    static final class Refs extends ClassVisitor {
        final Set<String> all = new HashSet<>();
        final List<String[]> members = new ArrayList<>();
        final Set<String> classes = new HashSet<>();

        private Refs() {
            super(Opcodes.ASM9);
        }

        static Refs of(byte[] classFile) {
            Refs r = new Refs();
            if (classFile.length > 0) {
                new ClassReader(classFile).accept(r, 0);
            }
            return r;
        }

        void type(Type t) {
            if (t.getSort() == Type.METHOD) {
                type(t.getReturnType());
                for (Type a : t.getArgumentTypes()) {
                    type(a);
                }
                return;
            }
            Type e = t.getSort() == Type.ARRAY ? t.getElementType() : t;
            if (e.getSort() == Type.OBJECT) {
                classes.add(e.getInternalName());
            }
        }

        @Override
        public void visitInnerClass(String name, String outer, String inner, int access) {
            all.add("inner " + name + " " + inner);
            classes.add(name);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
            all.add("field " + name + " " + desc);
            type(Type.getType(desc));
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
            type(Type.getMethodType(desc));
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                    all.add("call " + owner + "." + n + d);
                    members.add(new String[] {owner, n, d});
                    classes.add(owner);
                    type(Type.getMethodType(d));
                }

                @Override
                public void visitFieldInsn(int op, String owner, String n, String d) {
                    all.add("get " + owner + "." + n + " " + d);
                    members.add(new String[] {owner, n, d});
                    classes.add(owner);
                    type(Type.getType(d));
                }

                @Override
                public void visitTypeInsn(int op, String t) {
                    type(Type.getObjectType(t));
                }

                @Override
                public void visitInvokeDynamicInsn(String n, String d, Handle bsm, Object... args) {
                    all.add("indy " + n + d);
                    Type ret = Type.getReturnType(d);
                    if (ret.getSort() == Type.OBJECT && args.length > 0 && args[0] instanceof Type sam) {
                        members.add(new String[] {ret.getInternalName(), n, sam.getDescriptor()});
                    }
                    type(Type.getMethodType(d));
                }
            };
        }

        /** Every reference into the fixture package resolves, by its exact name + descriptor, in the real jar. */
        boolean resolveIn(Map<String, Set<String>> declared) {
            for (String c : classes) {
                if (c.startsWith("p/") && !declared.containsKey(c)) {
                    return false;
                }
            }
            for (String[] m : members) {
                if (m[0].startsWith("p/") && !declared.getOrDefault(m[0], Set.of()).contains(m[1] + m[2])) {
                    return false;
                }
            }
            return !members.isEmpty();
        }
    }

    // ------------------------------------------------------------------------------------------------ text + files

    static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 0x20 && c < 0x7f && c != '\\') {
                b.append(c);
            } else {
                b.append(String.format("\\u%04x", (int) c));
            }
        }
        return b.toString();
    }

    static String unesc(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 6 <= s.length() && s.charAt(i + 1) == 'u') {
                b.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                i += 5;
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        List<Path> all;
        try (Stream<Path> w = Files.walk(root)) {
            all = w.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
        }
        for (Path p : all) {
            Files.delete(p);
        }
    }
}
