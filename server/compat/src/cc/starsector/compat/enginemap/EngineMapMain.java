package cc.starsector.compat.enginemap;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

/**
 * The engine-name remap's build-time proof, run offline over the built client mod jar: the agent's FULL transform (the
 * same scope gate, then {@link EngineMapPrecheck#touches} FIRST, then the same {@link EngineMapRemapper#remap}; one
 * predicate and one remapper, never a copy) over EVERY class of {@code --in}, written to {@code --out}, then the
 * legs that prove the rewritten jar links against a separate build of the engine (the {@code --link} jars) before any
 * player starts it. Usage:
 *
 * <pre>
 * EngineMapMain --map &lt;tsv&gt; --in &lt;jar&gt; --out &lt;jar&gt; --link &lt;jar;jar;...&gt;
 *               [--control-unmapped] [--global-match &lt;tsv&gt;]
 * EngineMapMain --map &lt;tsv&gt; --in &lt;jar&gt; --link &lt;jar;jar;...&gt; --control-only
 * </pre>
 *
 * One machine row per leg ({@code <leg>=<n>}, then one {@code <leg> <item>} row per miss, sorted):
 * <ul>
 * <li>{@code touched=<n> <names,>}: the classes the pre-check selects (the caller compares the set with the map's
 * {@code touched} rows); {@code rewritten=<n>} and {@code transform-errors=<n>} beside it.</li>
 * <li>{@code unresolved=<n>}: the LINK CHECK over the rewritten jar. It walks the references a class REACHES - every
 * instruction's owner / name / descriptor, every descriptor, signature, constant, handle, frame type and attribute
 * (supertypes, exceptions, inner classes, nest, enclosing method, annotations) - and NEVER the constant pool: the
 * remap writes through {@code ClassWriter(reader, 0)}, which keeps the input pool, so the old names stay behind as dead
 * entries nothing resolves and a pool walk would fail every rewritten class. An engine class must exist in the link
 * jars; an engine member must resolve by name + descriptor on its class or a supertype / interface (a member reached
 * through a mod class counts too once its lookup climbs into an engine class).</li>
 * <li>{@code kind-mismatch=<n>}: each member row's static bit against the target member's {@code ACC_STATIC} (the
 * in-game probe picks its lookup by that bit: a wrong kind is a refused start).</li>
 * <li>{@code supertype-unresolved=<n>}: every touched class's superclass + interface chain resolves in the mod jar,
 * the link jars or the JDK (a supertype from an optional mod would make that mod's absence a refused start at
 * the probe's force-define).</li>
 * <li>{@code control-unresolved=<n>} (with {@code --control-unmapped} / {@code --control-only}): the SAME link check
 * over the UNmapped input, rows {@code control-miss <ref>}. Not graded here: against the target build it must equal
 * the known broken set (a checker that does nothing cannot pass); against the source build it must be 0
 * ({@code --control-only} exits 1 on any miss).</li>
 * <li>{@code links-wrong-target=<n>} (with {@code --global-match}): every engine class reference of the output equals
 * the image of the input reference at the same position under the whole-build class match (a name that resolves but
 * now belongs to a different class is caught here, never by the link check).</li>
 * </ul>
 * Refs print as {@code class <name>} / {@code field|method <owner> <name> <desc>}; every character outside printable
 * ASCII (and the backslash) prints as {@code \\uXXXX}, so the rows survive any console code page. Exit 0 iff every
 * graded leg ({@code transform-errors}, {@code unresolved}, {@code kind-mismatch}, {@code supertype-unresolved},
 * {@code links-wrong-target}) is 0, 1 when one is not, 2 on a bad command line or an unreadable input.
 *
 * <p>WHY it runs from the compat unit's COMPILED classes dir plus the vendored ASM core + commons jars and never with
 * {@code -cp} on the kit agent jar: that jar's top level holds only its two loader classes, and every package (this one
 * and ASM) sits under {@code META-INF/cc-compat/classes/} behind its private loader, so a plain classpath of it resolves
 * none of these classes. Depends on nothing outside {@code java.*} and {@code org.objectweb.asm.*}.
 *
 *
 */
public final class EngineMapMain {

    /** A bad command line or an unreadable / malformed input (distinct from a failing leg's 1). */
    public static final int EXIT_USAGE = 2;

    private static final String USAGE = "usage: EngineMapMain --map <tsv> --in <jar> (--out <jar> | --control-only)"
            + " --link <jar;jar;...> [--control-unmapped] [--global-match <tsv>]";

    private EngineMapMain() {
    }

    public static void main(String[] args) {
        int code;
        try {
            code = run(args, System.out);
        } catch (IOException | RuntimeException e) {
            // A bad command line, an unreadable jar or a malformed class file: one row, never a leg verdict.
            System.err.println("EngineMapMain: " + e.getClass().getSimpleName() + ": " + esc(String.valueOf(
                    e.getMessage())));
            code = EXIT_USAGE;
        }
        System.out.flush();
        System.exit(code);
    }

    /** The whole run against {@code out}; returns the exit code (see the class doc). */
    public static int run(String[] argv, PrintStream out) throws IOException {
        Args a = Args.parse(argv);
        EngineMap map = EngineMap.parse(Files.readAllBytes(a.map));
        List<Entry> input = readJar(a.in);
        Map<String, byte[]> inClasses = new LinkedHashMap<>();
        for (Entry e : input) {
            String cls = className(e.name);
            if (cls != null) {
                inClasses.put(cls, e.bytes);
            }
        }
        try (EngineIndex index = new EngineIndex(a.link)) {
            out.println("engine-map-main map=" + esc(map.id()) + " classes=" + inClasses.size() + " link-classes="
                    + index.size());
            if (a.controlOnly) {
                int control = leg(out, "control-unresolved", "control-miss", linkCheck(inClasses, index));
                return control == 0 ? 0 : 1;
            }
            Transformed t = transform(input, map);
            writeJar(a.out, t.entries);
            out.println("touched=" + t.touched.size() + (t.touched.isEmpty() ? "" : " " + esc(String.join(",",
                    t.touched))));
            out.println("rewritten=" + t.rewritten.size());
            int bad = leg(out, "transform-errors", "transform-error", t.errors);
            Resolver outLinks = new Resolver(index, t.classes);
            bad += leg(out, "unresolved", "unresolved", linkCheck(t.classes, index));
            bad += leg(out, "kind-mismatch", "kind-mismatch", kindCheck(map, outLinks));
            bad += leg(out, "supertype-unresolved", "supertype-unresolved", supertypeCheck(t.touched, t.classes,
                    outLinks));
            if (a.control) {
                leg(out, "control-unresolved", "control-miss", linkCheck(inClasses, index));
            }
            if (a.globalMatch != null) {
                bad += leg(out, "links-wrong-target", "links-wrong-target", identityCheck(inClasses, t.classes,
                        GlobalMatch.read(a.globalMatch), index));
            }
            return bad == 0 ? 0 : 1;
        }
    }

    private static int leg(PrintStream out, String count, String item, Collection<String> rows) {
        out.println(count + "=" + rows.size());
        for (String r : rows) {
            out.println(item + " " + r);
        }
        return rows.size();
    }

    // ------------------------------------------------------------------ the transform

    private record Entry(String name, byte[] bytes, boolean directory) {
    }

    private record Transformed(List<Entry> entries, Map<String, byte[]> classes, TreeSet<String> touched,
            TreeSet<String> rewritten, TreeSet<String> errors) {
    }

    /** The agent's per-class decision, verbatim in order: scope, the shared pre-check, the shared remapper. */
    private static Transformed transform(List<Entry> input, EngineMap map) {
        EngineMapPrecheck precheck = new EngineMapPrecheck(map);
        List<Entry> entries = new ArrayList<>(input.size());
        Map<String, byte[]> classes = new LinkedHashMap<>();
        TreeSet<String> touched = new TreeSet<>();
        TreeSet<String> rewritten = new TreeSet<>();
        TreeSet<String> errors = new TreeSet<>();
        for (Entry e : input) {
            byte[] bytes = e.bytes;
            String cls = className(e.name);
            if (cls != null) {
                if (cls.startsWith(EngineMap.SCOPE)) {
                    try {
                        if (precheck.touches(bytes)) {
                            touched.add(cls);
                            EngineMapRemapper.Remapped r = EngineMapRemapper.remap(bytes, map);
                            if (r != null) {
                                bytes = r.bytes();
                                rewritten.add(cls);
                            }
                        }
                    } catch (RuntimeException ex) {
                        // The agent fails open here (the original bytes load); offline it is a counted, failing row.
                        errors.add(esc(cls) + " " + ex.getClass().getSimpleName());
                    }
                }
                classes.put(cls, bytes);
            }
            entries.add(new Entry(e.name, bytes, e.directory));
        }
        return new Transformed(entries, classes, touched, rewritten, errors);
    }

    /** A class entry's internal name, or {@code null} for a resource, a directory or a module / versioned entry. */
    private static String className(String entry) {
        if (!entry.endsWith(".class") || entry.startsWith("META-INF/") || entry.equals("module-info.class")) {
            return null;
        }
        return entry.substring(0, entry.length() - ".class".length());
    }

    private static List<Entry> readJar(Path jar) throws IOException {
        List<Entry> out = new ArrayList<>();
        try (ZipFile z = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                byte[] bytes;
                try (InputStream in = z.getInputStream(ze)) {
                    bytes = in.readAllBytes();
                }
                out.add(new Entry(ze.getName(), bytes, ze.isDirectory()));
            }
        }
        return out;
    }

    private static void writeJar(Path jar, List<Entry> entries) throws IOException {
        try (OutputStream os = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(os)) {
            for (Entry e : entries) {
                zip.putNextEntry(new ZipEntry(e.name));
                if (!e.directory) {
                    zip.write(e.bytes);
                }
                zip.closeEntry();
            }
        }
    }

    // ------------------------------------------------------------------ the legs

    /** The link check over {@code classes} (the mod jar, rewritten or not) against the engine index. */
    private static TreeSet<String> linkCheck(Map<String, byte[]> classes, EngineIndex index) {
        Resolver resolver = new Resolver(index, classes);
        TreeSet<String> misses = new TreeSet<>();
        Set<String> seenClasses = new HashSet<>();
        Set<MemberRef> seenMembers = new HashSet<>();
        for (byte[] bytes : classes.values()) {
            RefWalk w = RefWalk.of(bytes);
            for (String c : w.classes) {
                if (seenClasses.add(c) && index.inNamespace(c) && !index.has(c)) {
                    misses.add("class " + esc(c));
                }
            }
            for (MemberRef m : w.members) {
                if (!seenMembers.add(m) || m.owner.startsWith("[")) {
                    continue; // an array owner (clone) has no member to resolve
                }
                boolean engineOwner = index.inNamespace(m.owner);
                if (!engineOwner && !classes.containsKey(m.owner)) {
                    continue; // the JDK or a library jar: not the engine's concern
                }
                Walk walk = new Walk(index);
                if (resolver.resolve(m.owner, m.name, m.desc, m.field, walk) == null
                        && (engineOwner || walk.touchedEngine)) {
                    misses.add(m.token());
                }
            }
        }
        return misses;
    }

    /** Each member row's static bit against the target member (its owner and descriptor mapped through the map). */
    private static TreeSet<String> kindCheck(EngineMap map, Resolver resolver) {
        EngineMapRemapper names = new EngineMapRemapper(map);
        TreeSet<String> bad = new TreeSet<>();
        for (EngineMap.Member row : map.members()) {
            String owner = names.map(row.owner());
            boolean method = row.kind().isMethod();
            String desc = method ? names.mapMethodDesc(row.desc()) : names.mapDesc(row.desc());
            Found f = resolver.resolve(owner, row.targetName(), desc, !method, new Walk(resolver.index));
            String token = row.kind().token() + " " + esc(owner) + " " + esc(row.targetName()) + " " + esc(desc);
            if (f == null) {
                bad.add(token + " absent");
            } else if (((f.access & Opcodes.ACC_STATIC) != 0) != row.kind().isStatic()) {
                bad.add(token + " static=" + ((f.access & Opcodes.ACC_STATIC) != 0) + " on " + esc(f.declarer));
            }
        }
        return bad;
    }

    /** Every touched class's superclass + interface chain, resolved in the mod jar, the link jars or the JDK. */
    private static TreeSet<String> supertypeCheck(Set<String> touched, Map<String, byte[]> classes,
            Resolver resolver) {
        TreeSet<String> misses = new TreeSet<>();
        for (String t : touched) {
            Deque<String> todo = new ArrayDeque<>();
            Set<String> seen = new HashSet<>();
            Shape self = Shape.of(classes.get(t));
            push(todo, self.superName, self.interfaces);
            while (!todo.isEmpty()) {
                String n = todo.pop();
                if (!seen.add(n)) {
                    continue;
                }
                Shape s = resolver.shape(n);
                if (s == null) {
                    misses.add(esc(t) + " " + esc(n));
                } else {
                    push(todo, s.superName, s.interfaces);
                }
            }
        }
        return misses;
    }

    private static void push(Deque<String> todo, String superName, String[] interfaces) {
        if (superName != null) {
            todo.push(superName);
        }
        for (String i : interfaces) {
            todo.push(i);
        }
    }

    /** Every engine class reference of the output against the image of the input reference at the same position. */
    private static TreeSet<String> identityCheck(Map<String, byte[]> in, Map<String, byte[]> out, GlobalMatch gm,
            EngineIndex index) {
        TreeSet<String> wrong = new TreeSet<>();
        for (Map.Entry<String, byte[]> e : out.entrySet()) {
            byte[] before = in.get(e.getKey());
            List<String> ins = RefWalk.of(before).classes;
            List<String> outs = before == e.getValue() ? ins : RefWalk.of(e.getValue()).classes;
            if (ins.size() != outs.size()) {
                wrong.add(esc(e.getKey()) + " shape in=" + ins.size() + " out=" + outs.size());
                continue;
            }
            for (int i = 0; i < ins.size(); i++) {
                String a = ins.get(i);
                String b = outs.get(i);
                if ((index.inNamespace(a) || index.inNamespace(b)) && !gm.accepts(a, b)) {
                    wrong.add(esc(a) + " -> " + esc(b) + " want " + esc(gm.image(a)));
                }
            }
        }
        return wrong;
    }

    // ------------------------------------------------------------------ references reached

    private record MemberRef(boolean field, String owner, String name, String desc) {
        String token() {
            return (field ? "field " : "method ") + esc(owner) + " " + esc(name) + " " + esc(desc);
        }
    }

    /**
     * Every class and member a class file's linkage reaches, in visit order: the header (super, interfaces,
     * signature), nest host / members, a sealed class's subclass list, the enclosing method, inner class entries, record
     * components, annotations, fields, and per method its descriptor, signature, exceptions and every instruction,
     * frame, handler, local variable and constant. Visit order is the reader's fixed order, so the input and the
     * rewritten output of one class yield position-aligned lists.
     */
    private static final class RefWalk extends ClassVisitor {
        final List<String> classes = new ArrayList<>();
        final Set<MemberRef> members = new LinkedHashSet<>();

        private RefWalk() {
            super(Opcodes.ASM9);
        }

        static RefWalk of(byte[] classfile) {
            RefWalk w = new RefWalk();
            new ClassReader(classfile).accept(w, 0);
            return w;
        }

        void cls(String internalName) {
            classes.add(internalName);
        }

        void internalOrArray(String n) {
            if (n == null) {
                return;
            }
            if (n.startsWith("[")) {
                desc(n);
            } else {
                cls(n);
            }
        }

        void desc(String d) {
            if (d != null) {
                type(Type.getType(d));
            }
        }

        void type(Type t) {
            switch (t.getSort()) {
                case Type.ARRAY -> type(t.getElementType());
                case Type.OBJECT -> cls(t.getInternalName());
                case Type.METHOD -> {
                    for (Type arg : t.getArgumentTypes()) {
                        type(arg);
                    }
                    type(t.getReturnType());
                }
                default -> {
                    // a primitive or void names no class
                }
            }
        }

        void classSig(String s) {
            if (s != null) {
                new SignatureReader(s).accept(new Sig());
            }
        }

        void typeSig(String s) {
            if (s != null) {
                new SignatureReader(s).acceptType(new Sig());
            }
        }

        void member(boolean field, String owner, String name, String d) {
            internalOrArray(owner);
            desc(d);
            members.add(new MemberRef(field, owner, name, d));
        }

        void handle(Handle h) {
            member(h.getTag() <= Opcodes.H_PUTSTATIC, h.getOwner(), h.getName(), h.getDesc());
        }

        void constant(Object v) {
            if (v instanceof Type t) {
                type(t);
            } else if (v instanceof Handle h) {
                handle(h);
            } else if (v instanceof ConstantDynamic c) {
                desc(c.getDescriptor());
                handle(c.getBootstrapMethod());
                for (int i = 0; i < c.getBootstrapMethodArgumentCount(); i++) {
                    constant(c.getBootstrapMethodArgument(i));
                }
            }
        }

        AnnotationVisitor annotation(String d) {
            desc(d);
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visit(String name, Object value) {
                    if (value instanceof Type t) {
                        type(t);
                    }
                }

                @Override
                public void visitEnum(String name, String descriptor, String value) {
                    desc(descriptor);
                }

                @Override
                public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                    return annotation(descriptor);
                }

                @Override
                public AnnotationVisitor visitArray(String name) {
                    return this;
                }
            };
        }

        /** A signature's class types; each nested type parses into its own visitor so the inner-class chain holds. */
        final class Sig extends SignatureVisitor {
            private String current;

            Sig() {
                super(Opcodes.ASM9);
            }

            @Override
            public void visitClassType(String name) {
                current = name;
                cls(name);
            }

            @Override
            public void visitInnerClassType(String name) {
                current = current + "$" + name;
                cls(current);
            }

            @Override
            public SignatureVisitor visitClassBound() {
                return new Sig();
            }

            @Override
            public SignatureVisitor visitInterfaceBound() {
                return new Sig();
            }

            @Override
            public SignatureVisitor visitSuperclass() {
                return new Sig();
            }

            @Override
            public SignatureVisitor visitInterface() {
                return new Sig();
            }

            @Override
            public SignatureVisitor visitParameterType() {
                return new Sig();
            }

            @Override
            public SignatureVisitor visitReturnType() {
                return new Sig();
            }

            @Override
            public SignatureVisitor visitExceptionType() {
                return new Sig();
            }

            @Override
            public SignatureVisitor visitArrayType() {
                return new Sig();
            }

            @Override
            public SignatureVisitor visitTypeArgument(char wildcard) {
                return new Sig();
            }
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName,
                String[] interfaces) {
            if (superName != null) {
                cls(superName);
            }
            if (interfaces != null) {
                for (String i : interfaces) {
                    cls(i);
                }
            }
            classSig(signature);
        }

        @Override
        public void visitNestHost(String nestHost) {
            cls(nestHost);
        }

        @Override
        public void visitOuterClass(String owner, String name, String descriptor) {
            cls(owner);
            desc(descriptor);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return annotation(descriptor);
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String descriptor,
                boolean visible) {
            return annotation(descriptor);
        }

        @Override
        public void visitNestMember(String nestMember) {
            cls(nestMember);
        }

        @Override
        public void visitPermittedSubclass(String permittedSubclass) {
            cls(permittedSubclass);
        }

        @Override
        public void visitInnerClass(String name, String outerName, String innerName, int access) {
            cls(name);
            if (outerName != null) {
                cls(outerName);
            }
        }

        @Override
        public RecordComponentVisitor visitRecordComponent(String name, String descriptor, String signature) {
            desc(descriptor);
            typeSig(signature);
            return new RecordComponentVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String d, boolean visible) {
                    return annotation(d);
                }

                @Override
                public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String d,
                        boolean visible) {
                    return annotation(d);
                }
            };
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            desc(descriptor);
            typeSig(signature);
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String d, boolean visible) {
                    return annotation(d);
                }

                @Override
                public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String d,
                        boolean visible) {
                    return annotation(d);
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                String[] exceptions) {
            desc(descriptor);
            classSig(signature);
            if (exceptions != null) {
                for (String x : exceptions) {
                    cls(x);
                }
            }
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotationDefault() {
                    return annotation(null);
                }

                @Override
                public AnnotationVisitor visitAnnotation(String d, boolean visible) {
                    return annotation(d);
                }

                @Override
                public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String d,
                        boolean visible) {
                    return annotation(d);
                }

                @Override
                public AnnotationVisitor visitParameterAnnotation(int parameter, String d, boolean visible) {
                    return annotation(d);
                }

                @Override
                public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
                    frame(numLocal, local);
                    frame(numStack, stack);
                }

                private void frame(int n, Object[] items) {
                    for (int i = 0; i < n && items != null; i++) {
                        if (items[i] instanceof String s) {
                            internalOrArray(s);
                        }
                    }
                }

                @Override
                public void visitTypeInsn(int opcode, String type) {
                    internalOrArray(type);
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String name, String d) {
                    member(true, owner, name, d);
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String name, String d, boolean isInterface) {
                    member(false, owner, name, d);
                }

                @Override
                public void visitInvokeDynamicInsn(String name, String d, Handle bsm, Object... args) {
                    desc(d);
                    handle(bsm);
                    for (Object arg : args) {
                        constant(arg);
                    }
                }

                @Override
                public void visitLdcInsn(Object value) {
                    constant(value);
                }

                @Override
                public void visitMultiANewArrayInsn(String d, int numDimensions) {
                    desc(d);
                }

                @Override
                public AnnotationVisitor visitInsnAnnotation(int typeRef, TypePath typePath, String d,
                        boolean visible) {
                    return annotation(d);
                }

                @Override
                public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                    if (type != null) {
                        cls(type);
                    }
                }

                @Override
                public AnnotationVisitor visitTryCatchAnnotation(int typeRef, TypePath typePath, String d,
                        boolean visible) {
                    return annotation(d);
                }

                @Override
                public void visitLocalVariable(String name, String d, String signature, Label start, Label end,
                        int index) {
                    desc(d);
                    typeSig(signature);
                }

                @Override
                public AnnotationVisitor visitLocalVariableAnnotation(int typeRef, TypePath typePath, Label[] start,
                        Label[] end, int[] index, String d, boolean visible) {
                    return annotation(d);
                }
            };
        }
    }

    // ------------------------------------------------------------------ resolution

    private record Sig(String name, String desc) {
    }

    /** A class's linkage shape: access, supertypes, declared members (name + descriptor to access flags). */
    private record Shape(String name, int access, String superName, String[] interfaces, Map<Sig, Integer> fields,
            Map<Sig, Integer> methods) {
        static Shape of(byte[] classfile) {
            ClassReader cr = new ClassReader(classfile);
            Map<Sig, Integer> fields = new HashMap<>();
            Map<Sig, Integer> methods = new HashMap<>();
            cr.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public FieldVisitor visitField(int access, String name, String descriptor, String signature,
                        Object value) {
                    fields.put(new Sig(name, descriptor), access);
                    return null;
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                        String[] exceptions) {
                    methods.put(new Sig(name, descriptor), access);
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return new Shape(cr.getClassName(), cr.getAccess(), cr.getSuperName(), cr.getInterfaces(), fields,
                    methods);
        }
    }

    private record Found(int access, String declarer) {
    }

    /** One member lookup's trail: whether it climbed into an engine class (found or not). */
    private static final class Walk {
        final EngineIndex index;
        final Set<String> seen = new HashSet<>();
        boolean touchedEngine;

        Walk(EngineIndex index) {
            this.index = index;
        }

        boolean enter(String name) {
            touchedEngine |= index.inNamespace(name);
            return seen.add(name);
        }
    }

    /**
     * Shapes by name: an engine-namespace class ONLY from the link jars (the mod cannot stand in for the engine), any
     * other class from the mod jar given, then the running JDK's own runtime image.
     */
    private static final class Resolver {
        final EngineIndex index;
        private final Map<String, byte[]> mod;
        private final Map<String, Shape> modShapes = new HashMap<>();

        Resolver(EngineIndex index, Map<String, byte[]> mod) {
            this.index = index;
            this.mod = mod;
        }

        Shape shape(String name) {
            if (index.inNamespace(name)) {
                return index.shape(name);
            }
            byte[] bytes = mod.get(name);
            if (bytes != null) {
                return modShapes.computeIfAbsent(name, n -> Shape.of(bytes));
            }
            return index.jdkShape(name);
        }

        /** JVM-style resolution by name + descriptor (fields: class, interfaces, superclass; methods: classes first). */
        Found resolve(String owner, String name, String desc, boolean field, Walk walk) {
            Sig sig = new Sig(name, desc);
            return field ? field(owner, sig, walk) : method(owner, sig, walk);
        }

        private Found field(String c, Sig sig, Walk walk) {
            if (c == null || !walk.enter(c)) {
                return null;
            }
            Shape s = shape(c);
            if (s == null) {
                return null;
            }
            Integer acc = s.fields.get(sig);
            if (acc != null) {
                return new Found(acc, c);
            }
            for (String i : s.interfaces) {
                Found f = field(i, sig, walk);
                if (f != null) {
                    return f;
                }
            }
            return field(s.superName, sig, walk);
        }

        private Found method(String owner, Sig sig, Walk walk) {
            List<Shape> chain = new ArrayList<>();
            for (String c = owner; c != null && walk.enter(c);) {
                Shape s = shape(c);
                if (s == null) {
                    break;
                }
                Integer acc = s.methods.get(sig);
                if (acc != null) {
                    return new Found(acc, c);
                }
                chain.add(s);
                c = s.superName;
            }
            Deque<String> todo = new ArrayDeque<>();
            for (Shape s : chain) {
                todo.addAll(Arrays.asList(s.interfaces));
            }
            Set<String> seen = new HashSet<>();
            while (!todo.isEmpty()) {
                String i = todo.poll();
                if (!seen.add(i)) {
                    continue;
                }
                walk.enter(i);
                Shape s = shape(i);
                if (s == null) {
                    continue;
                }
                Integer acc = s.methods.get(sig);
                if (acc != null) {
                    return new Found(acc, i);
                }
                todo.addAll(Arrays.asList(s.interfaces));
            }
            return null;
        }
    }

    /**
     * The link jars' classes (first jar wins on a duplicate, as on a class path), their package set, and the shapes
     * parsed on demand. The engine NAMESPACE = {@code com/fs/} plus every package the link jars hold (the sound
     * library's top-level package is an engine package too).
     */
    private static final class EngineIndex implements Closeable {
        private final List<ZipFile> jars = new ArrayList<>();
        private final Map<String, ZipEntryRef> classes = new HashMap<>();
        private final Set<String> packages = new HashSet<>();
        private final Map<String, Shape> shapes = new HashMap<>();
        private final Map<String, Shape> jdk = new HashMap<>();
        private final Set<String> jdkMissing = new HashSet<>();

        private record ZipEntryRef(ZipFile zip, ZipEntry entry) {
        }

        EngineIndex(List<Path> link) throws IOException {
            try {
                for (Path p : link) {
                    ZipFile z = new ZipFile(p.toFile());
                    jars.add(z);
                    Enumeration<? extends ZipEntry> en = z.entries();
                    while (en.hasMoreElements()) {
                        ZipEntry ze = en.nextElement();
                        String cls = className(ze.getName());
                        if (cls != null && !ze.isDirectory()) {
                            classes.putIfAbsent(cls, new ZipEntryRef(z, ze));
                            packages.add(packageOf(cls));
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                close();
                throw e;
            }
        }

        int size() {
            return classes.size();
        }

        boolean inNamespace(String name) {
            return name.startsWith("com/fs/") || packages.contains(packageOf(name));
        }

        boolean has(String name) {
            return classes.containsKey(name);
        }

        Shape shape(String name) {
            if (shapes.containsKey(name)) {
                return shapes.get(name);
            }
            ZipEntryRef ref = classes.get(name);
            Shape s = null;
            if (ref != null) {
                try (InputStream in = ref.zip.getInputStream(ref.entry)) {
                    s = Shape.of(in.readAllBytes());
                } catch (IOException | RuntimeException e) {
                    throw new IllegalArgumentException("unreadable link class " + esc(name) + ": " + e, e);
                }
            }
            shapes.put(name, s);
            return s;
        }

        /** A class of the running JDK's runtime image ({@code jrt:} only - never this tool's own class path). */
        Shape jdkShape(String name) {
            Shape cached = jdk.get(name);
            if (cached != null || jdkMissing.contains(name)) {
                return cached;
            }
            URL u = ClassLoader.getSystemResource(name + ".class");
            if (u != null && "jrt".equals(u.getProtocol())) {
                try (InputStream in = u.openStream()) {
                    cached = Shape.of(in.readAllBytes());
                } catch (IOException e) {
                    throw new IllegalArgumentException("unreadable JDK class " + esc(name) + ": " + e, e);
                }
                jdk.put(name, cached);
                return cached;
            }
            jdkMissing.add(name);
            return null;
        }

        @Override
        public void close() throws IOException {
            IOException first = null;
            for (ZipFile z : jars) {
                try {
                    z.close();
                } catch (IOException e) {
                    first = first == null ? e : first;
                }
            }
            if (first != null) {
                throw first;
            }
        }
    }

    private static String packageOf(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? "" : internalName.substring(0, slash);
    }

    /**
     * The whole-build class match: per source class its status, its identity verdict and up to four target
     * candidates. A class absent from the table (the shared API, an identical class) maps to itself.
     */
    private static final class GlobalMatch {
        private record Row(String status, String identity, List<String> candidates) {
        }

        private final Map<String, Row> rows;

        private GlobalMatch(Map<String, Row> rows) {
            this.rows = rows;
        }

        static GlobalMatch read(Path p) throws IOException {
            Map<String, Row> rows = new HashMap<>();
            int n = 0;
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                n++;
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("windows_class\t")) {
                    continue;
                }
                String[] f = line.split("\t", -1);
                if (f.length != 4) {
                    throw new IllegalArgumentException("global match line " + n + ": want 4 tab columns");
                }
                List<String> cands = f[3].isEmpty() ? List.of() : List.of(f[3].trim().split(" +"));
                if (rows.put(f[0], new Row(f[1], f[2], cands)) != null) {
                    throw new IllegalArgumentException("global match line " + n + ": duplicate class");
                }
            }
            return new GlobalMatch(rows);
        }

        /**
         * True when {@code out} is an image of {@code in}: a listed candidate, or the same name where the class is
         * still present with the same code (an ambiguous same-code row lists at most four candidates).
         */
        boolean accepts(String in, String out) {
            Row r = rows.get(in);
            if (r == null) {
                return in.equals(out);
            }
            if (r.candidates.contains(out)) {
                return true;
            }
            return in.equals(out) && r.status.startsWith("PRESENT") && r.identity.startsWith("SAME-");
        }

        String image(String in) {
            Row r = rows.get(in);
            return r == null ? in : String.join("|", r.candidates);
        }
    }

    // ------------------------------------------------------------------ the command line

    private record Args(Path map, Path in, Path out, List<Path> link, boolean control, boolean controlOnly,
            Path globalMatch) {
        static Args parse(String[] argv) {
            Map<String, String> v = new TreeMap<>();
            boolean control = false;
            boolean controlOnly = false;
            for (int i = 0; i < argv.length; i++) {
                String k = argv[i];
                switch (k) {
                    case "--control-unmapped" -> control = true;
                    case "--control-only" -> controlOnly = true;
                    case "--map", "--in", "--out", "--link", "--global-match" -> {
                        if (i + 1 >= argv.length || v.put(k, argv[++i]) != null) {
                            throw new IllegalArgumentException(k + " needs one value, once; " + USAGE);
                        }
                    }
                    default -> throw new IllegalArgumentException("unknown argument " + esc(k) + "; " + USAGE);
                }
            }
            for (String need : controlOnly ? List.of("--map", "--in", "--link")
                    : List.of("--map", "--in", "--out", "--link")) {
                if (!v.containsKey(need)) {
                    throw new IllegalArgumentException("missing " + need + "; " + USAGE);
                }
            }
            if (controlOnly && (v.containsKey("--out") || v.containsKey("--global-match") || control)) {
                throw new IllegalArgumentException("--control-only takes --map --in --link alone; " + USAGE);
            }
            List<Path> link = new ArrayList<>();
            for (String p : v.get("--link").split(";")) {
                if (!p.isBlank()) {
                    link.add(Path.of(p.trim()));
                }
            }
            if (link.isEmpty()) {
                throw new IllegalArgumentException("--link names no jar; " + USAGE);
            }
            return new Args(Path.of(v.get("--map")), Path.of(v.get("--in")), v.containsKey("--out")
                    ? Path.of(v.get("--out")) : null, link, control, controlOnly, v.containsKey("--global-match")
                            ? Path.of(v.get("--global-match")) : null);
        }
    }

    /** Printable ASCII passes; everything else (and the backslash) becomes {@code \\uXXXX}. */
    static String esc(String s) {
        StringBuilder b = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean plain = c > 0x20 && c < 0x7f && c != '\\';
            if (plain) {
                if (b != null) {
                    b.append(c);
                }
                continue;
            }
            if (b == null) {
                b = new StringBuilder(s.length() + 16).append(s, 0, i);
            }
            b.append(String.format("\\u%04x", (int) c));
        }
        return b == null ? s : b.toString();
    }
}
