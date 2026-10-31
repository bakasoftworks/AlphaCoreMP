package cc.starsector.compat.pilotship;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import cc.starsector.compat.magic.MagicSeamRewriter;
import cc.starsector.compat.pilotship.PilotShipClassifier.JarIndex;
import cc.starsector.compat.pilotship.PilotShipClassifier.Twin;
import cc.starsector.compat.pilotship.PilotShipClassifier.Verdict;

/**
 * The call-site redirect: in a class {@link PilotShipClassifier} lets follow the pilot, every
 * {@code INVOKEINTERFACE CombatEngineAPI.getPlayerShip()ShipAPI} becomes
 * {@code INVOKESTATIC PilotShipCall.playerShip(CombatEngineAPI)ShipAPI}. The engine's getter is never touched, so the
 * engine's own readers keep the real answer; only redirected script code consults the per-ship window.
 *
 * <p>WHY the shape: the static call has the interface call's exact stack effect (pop the engine, push a ShipAPI) at
 * the same position, so {@code ClassWriter(reader, 0)} keeps the original max_stack, max_locals and StackMapTable; a
 * method without a site is copied verbatim by ASM. The key is owner + name + descriptor together: a mod's own
 * {@code getPlayerShip(String)String} is a name collision, not a site.
 *
 * <p>Fail-OPEN per class: a non-candidate (or a pool that never names {@code getPlayerShip}) returns {@code null}
 * before any parse; every other class returns a {@link Result} and never throws. An unchanged class carries ONE reason
 * (the classifier's, {@link #REASON_SELF_CHECK}, or {@code error:<SimpleName>}); the caller loads the original bytes.
 *
 * <p>The same pass swaps the LWJGL keyboard / mouse family: each {@code INVOKESTATIC Keyboard|Mouse.<row>} becomes
 * {@code INVOKESTATIC PilotShipCall.<twin>} with the SAME descriptor (a static call for a static call). Every site of
 * either family comes from ONE table, {@link PilotShipClassifier#TWINS}.
 *
 * <p>The self-check re-reads the output independently: replaced calls == the classifier's sites; the method set, the
 * class header and every method without a site byte-identical; each site method's instruction stream (frames, try
 * ranges, lines and maxs included) equal to the input's with exactly the sites swapped to their table twins; the input
 * constant pool an untouched prefix of the output's, the appended entries only the typed target's Class and the
 * Methodref / NameAndType / Utf8s of twin-table rows, each at most once.
 *
 *
 */
public final class PilotShipRedirectRewriter {

    private PilotShipRedirectRewriter() {
    }

    /** The typed target the swapped calls invoke (system loader; answers the window's hull, else the engine's). */
    public static final String TARGET_OWNER = "cc/starsector/server/probes/PilotShipCall";
    public static final String TARGET_NAME = "playerShip";
    public static final String TARGET_DESC =
            "(Lcom/fs/starfarer/api/combat/CombatEngineAPI;)Lcom/fs/starfarer/api/combat/ShipAPI;";
    /** Unchanged: the patched output failed the self-check. */
    public static final String REASON_SELF_CHECK = "self_check";

    /** One class's outcome: patched bytes, or the one reason the class keeps its original bytes. */
    public static final class Result {
        private final byte[] patched;
        private final String reason;
        private final int sites;
        private final int keySites;
        private final String detail;
        final Map<String, Integer> siteMethods;

        Result(byte[] patched, String reason, Verdict v, String detail) {
            this.patched = patched;
            this.reason = reason;
            this.sites = v.sites();
            this.keySites = v.keySites();
            this.detail = PilotShipClassifier.bounded(detail);
            this.siteMethods = v.siteMethods();
        }

        /** Of {@link #sites()}, the LWJGL keyboard / mouse sites (0 <= keySites <= sites). */
        public int keySites() {
            return keySites;
        }

        /** True when {@link #patched()} holds the rewritten class. */
        public boolean applied() {
            return patched != null;
        }

        /** The rewritten class bytes; {@code null} when unchanged (the caller loads the original). */
        public byte[] patched() {
            return patched;
        }

        /** Redirect sites of both families in the class (>= 0); when applied, every one of them was swapped. */
        public int sites() {
            return sites;
        }

        /** {@code null} when applied; else a classifier reason, {@link #REASON_SELF_CHECK} or {@code error:<Name>}. */
        public String reason() {
            return reason;
        }

        /** Where the reason came from or which self-check missed, printable ASCII, <= 64 chars; {@code ""} if none. */
        public String detail() {
            return detail;
        }
    }

    /**
     * The rewrite: {@code null} exactly when {@link PilotShipClassifier#classify} is {@code null}; else a never-throwing
     * {@link Result}. Arguments as {@link PilotShipClassifier#classify}.
     */
    public static Result rewrite(String internalName, byte[] bytes, JarIndex jar, Set<String> hostExclude,
            String codeSourcePath) {
        Verdict v = PilotShipClassifier.classify(internalName, bytes, jar, hostExclude, codeSourcePath);
        if (v == null) {
            return null;
        }
        if (v.reason() != null) {
            return new Result(null, v.reason(), v, v.detail());
        }
        try {
            int[] replaced = {0};
            byte[] out = swap(bytes, v.siteMethods().keySet(), replaced);
            if (replaced[0] != v.sites()) {
                return new Result(null, REASON_SELF_CHECK, v, "replaced=" + replaced[0]);
            }
            String miss = selfCheck(bytes, out, v.siteMethods());
            if (miss != null) {
                return new Result(null, REASON_SELF_CHECK, v, miss);
            }
            return new Result(out, null, v, "");
        } catch (Throwable t) {
            return new Result(null, PilotShipClassifier.REASON_ERROR_PREFIX + MagicSeamRewriter.simpleName(t), v, "");
        }
    }

    /**
     * The swap itself, only inside {@code siteMethods} (methods with a site of either family); each site becomes its
     * table twin; {@code replaced[0]} counts the swapped calls.
     */
    static byte[] swap(byte[] bytes, Set<String> siteMethods, int[] replaced) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!siteMethods.contains(name + descriptor)) {
                    return mv; // the writer's own visitor: ASM copies this method's bytes verbatim
                }
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                        Twin t = PilotShipClassifier.siteTwin(op, owner, n, d);
                        if (t != null) {
                            replaced[0]++;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, TARGET_OWNER, t.twin(), t.twinDesc(), false);
                        } else {
                            super.visitMethodInsn(op, owner, n, d, itf);
                        }
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    // ---------------------------------------------------------------- the self-check (an independent re-read)

    /** {@code null} = the output holds; else a bounded description of the first miss. */
    static String selfCheck(byte[] in, byte[] out, Map<String, Integer> siteMethods) {
        String pool = poolDelta(in, out);
        if (pool != null) {
            return pool;
        }
        Raw ri = raw(in);
        Raw ro = raw(out);
        if (!Arrays.equals(ri.header, ro.header)) {
            return "header_changed";
        }
        if (!ri.keys.equals(ro.keys)) {
            return "method_set_changed";
        }
        for (String k : ri.keys) {
            if (siteMethods.containsKey(k)) {
                continue;
            }
            if (!Arrays.equals(ri.code.get(k), ro.code.get(k)) || !Arrays.equals(ri.maxs.get(k), ro.maxs.get(k))) {
                return "code_changed:" + k;
            }
        }
        int[] swapped = {0};
        int[] targets = {0};
        Map<String, List<String>> ti = tokens(in, siteMethods.keySet(), true, swapped);
        Map<String, List<String>> to = tokens(out, siteMethods.keySet(), false, targets);
        for (String k : siteMethods.keySet()) {
            if (!Arrays.equals(ri.maxs.get(k), ro.maxs.get(k))) {
                return "maxs_changed:" + k;
            }
            List<String> a = ti.get(k);
            if (a == null || !a.equals(to.get(k))) {
                return "tokens_changed:" + k;
            }
        }
        int sites = 0;
        for (int s : siteMethods.values()) {
            sites += s;
        }
        if (swapped[0] != sites || targets[0] != sites) {
            return "target_calls=" + targets[0] + "/" + sites;
        }
        return null;
    }

    /** The input pool must be an untouched prefix of the output pool; appended entries only the twin table's own. */
    private static String poolDelta(byte[] in, byte[] out) {
        int[] pi = PilotShipClassifier.poolOffsets(in);
        int[] po = PilotShipClassifier.poolOffsets(out);
        if (pi == null || po == null) {
            return "pool_unreadable";
        }
        int ni = pi.length - 1;
        int no = po.length - 1;
        if (no < ni) {
            return "pool_shrank";
        }
        for (int k = 1; k < ni; k++) {
            if (pi[k] < 0) {
                continue;
            }
            int len = end(pi, k) - pi[k];
            if (po[k] < 0 || end(po, k) - po[k] != len
                    || !Arrays.equals(in, pi[k], pi[k] + len, out, po[k], po[k] + len)) {
                return "pool_changed:" + k;
            }
        }
        Set<String> added = new HashSet<>();
        for (int k = ni; k < no; k++) {
            String what = entry(out, po, k, ni);
            if (what == null || !added.add(what)) {
                int p = po[k];
                int tag = p < 0 ? -1 : out[p] & 0xff;
                String text = tag == 1 ? new String(out, p + 3, Math.min(24, PilotShipClassifier.u2(out, p + 1)),
                        StandardCharsets.ISO_8859_1) : "";
                return "pool_added:" + k + " tag=" + tag + " " + text;
            }
        }
        return null;
    }

    private static int end(int[] off, int k) {
        int j = k + 1;
        while (off[j] < 0) {
            j++;
        }
        return off[j];
    }

    /**
     * The allowed appended entry at {@code k} as a short name (the row it resolves to), or {@code null} for anything
     * else: the typed target's Class and Utf8 name; a Utf8 equal to a twin-table row's twin name or twin descriptor; a
     * NameAndType or a Methodref (on the typed target) of a twin-table row. The caller refuses a repeated short name,
     * so each resolves at most once. Besides those, an InvokeDynamic / Dynamic entry over a NameAndType the input
     * already held is allowed: when a bootstrap method's arguments repeat a constant (some compilers emit duplicate
     * MethodType entries), ASM re-keys the bootstrap method on re-emit and appends a value-identical call-site entry;
     * the token pass proves the invokedynamic's name, descriptor, bootstrap handle and arguments unchanged by value.
     * A loosened check (the Class delta alone) would pass a wrong twin descriptor, which throws NoSuchMethodError
     * inside a hull's update.
     *
     *
     */
    private static String entry(byte[] b, int[] off, int k, int inputCount) {
        int p = off[k];
        if (p < 0) {
            return null;
        }
        int tag = b[p] & 0xff;
        switch (tag) {
            case 17:
            case 18: {
                int nat = PilotShipClassifier.u2(b, p + 3);
                return nat > 0 && nat < inputCount ? "callsite:" + k : null;
            }
            case 1: {
                String s = utf8(b, p);
                if (TARGET_OWNER.equals(s)) {
                    return "u_owner";
                }
                for (Twin t : PilotShipClassifier.TWINS) {
                    if (t.twin().equals(s)) {
                        return "u_name:" + s;
                    }
                    if (t.twinDesc().equals(s)) {
                        return "u_desc:" + s;
                    }
                }
                return null;
            }
            case 7:
                return TARGET_OWNER.equals(utf8(b, at(b, off, p + 1))) ? "class" : null;
            case 12: {
                Twin t = twinByTarget(utf8(b, at(b, off, p + 1)), utf8(b, at(b, off, p + 3)));
                return t == null ? null : "nat:" + t.twin() + t.twinDesc();
            }
            case 10: {
                int cls = at(b, off, p + 1);
                int nat = at(b, off, p + 3);
                boolean owner = cls >= 0 && (b[cls] & 0xff) == 7 && TARGET_OWNER.equals(utf8(b, at(b, off, cls + 1)));
                Twin t = owner && nat >= 0 && (b[nat] & 0xff) == 12
                        ? twinByTarget(utf8(b, at(b, off, nat + 1)), utf8(b, at(b, off, nat + 3))) : null;
                return t == null ? null : "methodref:" + t.twin() + t.twinDesc();
            }
            default:
                return null;
        }
    }

    /** The twin-table row whose typed-target twin is {@code name + desc}; {@code null} for none (or a null part). */
    static Twin twinByTarget(String name, String desc) {
        if (name == null || desc == null) {
            return null;
        }
        for (Twin t : PilotShipClassifier.TWINS) {
            if (t.twin().equals(name) && t.twinDesc().equals(desc)) {
                return t;
            }
        }
        return null;
    }

    private static int at(byte[] b, int[] off, int p) {
        int idx = PilotShipClassifier.u2(b, p);
        return idx > 0 && idx < off.length - 1 ? off[idx] : -1;
    }

    /** The Utf8 entry at offset {@code p} as Latin-1 text (an ASCII name compares exactly); null when not a Utf8. */
    private static String utf8(byte[] b, int p) {
        if (p < 0 || (b[p] & 0xff) != 1) {
            return null;
        }
        return new String(b, p + 3, PilotShipClassifier.u2(b, p + 1), StandardCharsets.ISO_8859_1);
    }

    /** A raw classfile walk: the header after the pool, and each method's code[] and maxs by name + descriptor. */
    private static final class Raw {
        byte[] header;
        final List<String> keys = new ArrayList<>();
        final Map<String, byte[]> code = new HashMap<>();
        final Map<String, int[]> maxs = new HashMap<>();
    }

    private static Raw raw(byte[] b) {
        ClassReader cr = new ClassReader(b);
        Raw r = new Raw();
        char[] buf = new char[cr.getMaxStringLength() + 1];
        int p = cr.header + 6;
        int interfaces = cr.readUnsignedShort(p);
        p += 2 + 2 * interfaces;
        r.header = Arrays.copyOfRange(b, cr.header, p);
        int fields = cr.readUnsignedShort(p);
        p += 2;
        for (int i = 0; i < fields; i++) {
            p += 6;
            int attrs = cr.readUnsignedShort(p);
            p += 2;
            for (int a = 0; a < attrs; a++) {
                p += 6 + cr.readInt(p + 2);
            }
        }
        int methods = cr.readUnsignedShort(p);
        p += 2;
        for (int i = 0; i < methods; i++) {
            String key = cr.readUTF8(p + 2, buf) + cr.readUTF8(p + 4, buf);
            r.keys.add(key);
            int attrs = cr.readUnsignedShort(p + 6);
            p += 8;
            for (int a = 0; a < attrs; a++) {
                String an = cr.readUTF8(p, buf);
                int len = cr.readInt(p + 2);
                if ("Code".equals(an)) {
                    r.maxs.put(key, new int[] {cr.readUnsignedShort(p + 6), cr.readUnsignedShort(p + 8)});
                    int codeLength = cr.readInt(p + 10);
                    r.code.put(key, Arrays.copyOfRange(b, p + 14, p + 14 + codeLength));
                }
                p += 6 + len;
            }
        }
        return r;
    }

    /**
     * Canonical instruction streams of the named methods: opcodes and symbolic operands, labels by first use, frames,
     * try ranges, lines, locals and maxs; no offsets or pool indices. {@code substitute}: render each site as the swap.
     */
    private static Map<String, List<String>> tokens(byte[] b, Set<String> keys, boolean substitute, int[] count) {
        Map<String, List<String>> out = new TreeMap<>();
        new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                String key = name + descriptor;
                if (!keys.contains(key)) {
                    return null;
                }
                List<String> t = new ArrayList<>();
                out.put(key, t);
                return new TokenVisitor(t, substitute, count);
            }
        }, 0);
        return out;
    }

    private static final class TokenVisitor extends MethodVisitor {
        private final List<String> t;
        private final boolean substitute;
        private final int[] count;
        private final Map<Label, Integer> ids = new IdentityHashMap<>();

        TokenVisitor(List<String> t, boolean substitute, int[] count) {
            super(Opcodes.ASM9);
            this.t = t;
            this.substitute = substitute;
            this.count = count;
        }

        private String id(Object o) {
            if (o instanceof Label l) {
                return "L" + ids.computeIfAbsent(l, k -> ids.size());
            }
            if (o instanceof Object[] a) {
                StringBuilder sb = new StringBuilder("[");
                for (Object x : a) {
                    sb.append(id(x)).append(',');
                }
                return sb.append(']').toString();
            }
            return o == null ? "null" : o.getClass().getSimpleName() + ":" + o;
        }

        @Override
        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
            t.add("F" + type + " " + numLocal + id(local == null ? null : Arrays.copyOf(local, numLocal)) + " "
                    + numStack + id(stack == null ? null : Arrays.copyOf(stack, numStack)));
        }

        @Override
        public void visitInsn(int opcode) {
            t.add(Integer.toString(opcode));
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            t.add(opcode + " " + operand);
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            t.add(opcode + " " + var);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            t.add(opcode + " " + type);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            t.add(opcode + " " + owner + "." + name + " " + descriptor);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
            Twin site = substitute ? PilotShipClassifier.siteTwin(opcode, owner, name, descriptor) : null;
            if (site != null) {
                count[0]++;
                opcode = Opcodes.INVOKESTATIC;
                owner = TARGET_OWNER;
                name = site.twin();
                descriptor = site.twinDesc();
                itf = false;
            } else if (!substitute && opcode == Opcodes.INVOKESTATIC && TARGET_OWNER.equals(owner)
                    && twinByTarget(name, descriptor) != null) {
                count[0]++;
            }
            t.add(opcode + " " + owner + "." + name + " " + descriptor + " " + itf);
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bsm, Object... args) {
            t.add("indy " + name + " " + descriptor + " " + bsm + " " + id(args));
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            t.add(opcode + " " + id(label));
        }

        @Override
        public void visitLabel(Label label) {
            t.add(id(label) + ":");
        }

        @Override
        public void visitLdcInsn(Object value) {
            t.add("ldc " + id(value));
        }

        @Override
        public void visitIincInsn(int var, int increment) {
            t.add("iinc " + var + " " + increment);
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            t.add("tswitch " + min + " " + max + " " + id(dflt) + id(labels));
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            t.add("lswitch " + id(dflt) + Arrays.toString(keys) + id(labels));
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            t.add("multi " + descriptor + " " + numDimensions);
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            t.add("try " + id(start) + id(end) + id(handler) + " " + type);
        }

        @Override
        public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end,
                int index) {
            t.add("local " + name + " " + descriptor + " " + signature + id(start) + id(end) + " " + index);
        }

        @Override
        public void visitLineNumber(int line, Label start) {
            t.add("line " + line + id(start));
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            t.add("maxs " + maxStack + " " + maxLocals);
        }
    }

    // ---------------------------------------------------------------- the census, golden and selftest mains

    /**
     * {@code --census <jar...>}: one csv row per redirect site in every class of the given jars (jar, class, method,
     * kind, twin = the typed target's method the site becomes: {@code playerShip} for a ship site, any other for a key
     * site, verdict = kept | unchanged, reason, detail), then summary rows: kept vs unchanged classes, sites and key
     * sites per reason, the sites in non-candidate classes, the elapsed time. Pass the API jar too: it resolves the kinds.
     * {@code --golden <jar>}: per class the input and output sha256 and the verdict. {@code --selftest}: the
     * hand-built fixtures, one per reason and per hard shape. Exit 0 / 1 / 2 = done / a selftest failed / bad arguments.
     */
    public static void main(String[] args) throws IOException {
        if (args.length >= 2 && args[0].equals("--census")) {
            census(Arrays.asList(args).subList(1, args.length));
        } else if (args.length == 2 && args[0].equals("--golden")) {
            golden(args[1]);
        } else if (args.length == 1 && args[0].equals("--selftest")) {
            System.exit(SelfTest.run() == 0 ? 0 : 1);
        } else {
            System.err.println("usage: PilotShipRedirectRewriter --census <jar...> | --golden <jar> | --selftest");
            System.exit(2);
        }
    }

    private interface ClassSink {
        void accept(String internalName, byte[] bytes) throws IOException;
    }

    private static int eachClass(String jarPath, ClassSink sink) throws IOException {
        int n = 0;
        try (ZipFile zip = new ZipFile(jarPath)) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String fn = e.getName();
                if (e.isDirectory() || !fn.endsWith(".class") || fn.startsWith("META-INF/")
                        || fn.endsWith("module-info.class") || fn.endsWith("package-info.class")) {
                    continue;
                }
                byte[] bytes;
                try (InputStream in = zip.getInputStream(e)) {
                    bytes = in.readAllBytes();
                }
                n++;
                sink.accept(fn.substring(0, fn.length() - ".class".length()), bytes);
            }
        }
        return n;
    }

    private static void census(List<String> jars) throws IOException {
        long t0 = System.nanoTime();
        List<JarIndex> all = new ArrayList<>();
        for (String j : jars) {
            all.add(JarIndex.forLocation(j));
        }
        Map<String, int[]> unchanged = new TreeMap<>();
        int[] kept = new int[3];
        int[] outside = new int[2];
        int scanned = 0;
        System.out.println("# verdict kept = the class is rewritten (its sites follow the pilot); unchanged = the class"
                + " keeps its bytes for <reason>; twin playerShip = a ship site, any other twin = a key site");
        System.out.println("jar,class,method,kind,twin,verdict,reason,detail");
        for (String j : jars) {
            String label = MagicSeamRewriter.jarLabel(Path.of(j).getFileName().toString());
            JarIndex idx = JarIndex.forLocation(j);
            int[] jk = new int[3];
            int[] ju = new int[3];
            scanned += eachClass(j, (name, bytes) -> {
                Result r = rewrite(name, bytes, idx, Set.of(), j);
                if (r == null) {
                    if (!PilotShipClassifier.isCandidate(name) && PilotShipClassifier.siteNameMask(bytes) != 0) {
                        int s = countSites(bytes);
                        outside[0] += s > 0 ? 1 : 0;
                        outside[1] += s;
                    }
                    return;
                }
                if (r.sites() == 0 && PilotShipClassifier.REASON_NO_SITES.equals(r.reason())) {
                    unchanged.computeIfAbsent(r.reason(), k -> new int[3])[0]++;
                    return;
                }
                String kind = kind(name, all);
                String verdict = r.applied() ? "kept" : "unchanged";
                for (String[] site : siteTwins(bytes)) {
                    System.out.println(label + "," + name + "," + site[0] + "," + kind + "," + site[1] + "," + verdict
                            + "," + (r.applied() ? "-" : r.reason()) + "," + r.detail().replace(',', ';'));
                }
                for (int[] b : new int[][] {r.applied() ? kept : unchanged.computeIfAbsent(r.reason(),
                        k -> new int[3]), r.applied() ? jk : ju}) {
                    b[0]++;
                    b[1] += r.sites();
                    b[2] += r.keySites();
                }
            });
            System.out.println("# jar " + label + " kept_classes=" + jk[0] + " kept_sites=" + jk[1]
                    + " kept_key_sites=" + jk[2] + " unchanged_classes=" + ju[0] + " unchanged_sites=" + ju[1]
                    + " unchanged_key_sites=" + ju[2]);
        }
        System.out.println("summary kept classes=" + kept[0] + " sites=" + kept[1] + " key_sites=" + kept[2]);
        for (Map.Entry<String, int[]> e : unchanged.entrySet()) {
            System.out.println("summary unchanged reason=" + e.getKey() + " classes=" + e.getValue()[0] + " sites="
                    + e.getValue()[1] + " key_sites=" + e.getValue()[2]);
        }
        System.out.println("summary outside_candidates classes=" + outside[0] + " sites=" + outside[1]);
        System.out.println("summary jars=" + jars.size() + " classes_scanned=" + scanned + " index_errors="
                + JarIndex.errorCount() + " ms=" + (System.nanoTime() - t0) / 1_000_000);
        JarIndex.closeAll();
    }

    private static void golden(String jar) throws IOException {
        String label = MagicSeamRewriter.jarLabel(Path.of(jar).getFileName().toString());
        JarIndex idx = JarIndex.forLocation(jar);
        eachClass(jar, (name, bytes) -> {
            Result r = rewrite(name, bytes, idx, Set.of(), jar);
            if (r != null) {
                System.out.println("golden jar=" + label + " class=" + name + " in_sha256="
                        + MagicSeamRewriter.sha256(bytes) + " sites=" + r.sites() + " verdict="
                        + (r.applied() ? "applied out_sha256=" + MagicSeamRewriter.sha256(r.patched())
                                : "unchanged:" + r.reason()));
            }
        });
        JarIndex.closeAll();
    }

    /**
     * Census only: every redirect site in class order as {@code {method name+desc, twin name}}; empty for a class ASM
     * cannot read.
     */
    static List<String[]> siteTwins(byte[] bytes) {
        List<String[]> out = new ArrayList<>();
        try {
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int a, String nm, String d, String s, String[] e) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                            String twin = PilotShipClassifier.twinFor(op, owner, name, desc);
                            if (twin != null) {
                                out.add(new String[] {nm + d, twin});
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (RuntimeException e) {
            out.clear(); // census only: the verdict row above already names the class's error
        }
        return out;
    }

    static int countSites(byte[] bytes) {
        int[] n = {0};
        try {
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int a, String nm, String d, String s, String[] e) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                            n[0] += PilotShipClassifier.isSite(op, owner, name, desc) ? 1 : 0;
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (RuntimeException e) {
            return -1; // census only: a class ASM cannot read counts as -1 in the outside total
        }
        return n[0];
    }

    /** The callback kind from the census role table, by walking supertypes across every census jar; helper = none. */
    private static String kind(String name, List<JarIndex> all) {
        Set<String> kinds = new TreeSet<>();
        Set<String> seen = new HashSet<>();
        List<String> q = new ArrayList<>(List.of(name));
        for (int i = 0; i < q.size() && i < 256; i++) {
            String s = q.get(i);
            String k = ROLES.get(s);
            if (k != null) {
                kinds.add(k);
            } else if (s.startsWith("com/fs/starfarer/api/ui/")) {
                kinds.add("ui");
            }
            for (JarIndex j : all) {
                PilotShipClassifier.ClassModel m = j == null ? null : j.model(s);
                if (m != null) {
                    if (m.superName != null && seen.add(m.superName)) {
                        q.add(m.superName);
                    }
                    for (String itf : m.interfaces) {
                        if (seen.add(itf)) {
                            q.add(itf);
                        }
                    }
                    break;
                }
            }
        }
        return kinds.isEmpty() ? "helper" : String.join("+", kinds);
    }

    private static final String C = "com/fs/starfarer/api/combat/";
    private static final Map<String, String> ROLES = Map.ofEntries(
            Map.entry(C + "HullModEffect", "hullmod"),
            Map.entry(C + "listeners/AdvanceableListener", "listener"),
            Map.entry("com/fs/starfarer/api/plugins/ShipSystemStatsScript", "system"),
            Map.entry(C + "EveryFrameWeaponEffectPlugin", "weapon_fx"),
            Map.entry(C + "OnFireEffectPlugin", "weapon_fx"),
            Map.entry(C + "BeamEffectPlugin", "weapon_fx"),
            Map.entry(C + "OnHitEffectPlugin", "onhit"),
            Map.entry(C + "listeners/DamageDealtModifier", "onhit"),
            Map.entry(C + "listeners/DamageTakenModifier", "onhit"),
            Map.entry(C + "listeners/DamageListener", "onhit"),
            Map.entry(C + "ShipAIPlugin", "ai"),
            Map.entry(C + "AutofireAIPlugin", "ai"),
            Map.entry(C + "ShipSystemAIScript", "ai"),
            Map.entry(C + "MissileAIPlugin", "ai"),
            Map.entry(C + "EveryFrameCombatPlugin", "plugin"),
            Map.entry(C + "CombatLayeredRenderingPlugin", "render"),
            Map.entry("com/fs/starfarer/api/EveryFrameScript", "campaign"),
            Map.entry("com/fs/starfarer/api/ModPlugin", "campaign"),
            Map.entry("com/fs/starfarer/api/mission/MissionDefinitionPlugin", "campaign"),
            Map.entry(PilotShipClassifier.MAGIC_SUBSYSTEM, "magic"));

    /**
     * Hand-built ASM fixtures (never loaded, only classified and rewritten): each reason once, and the shapes the
     * census measured as hard (a clock writer behind FDIV / FNEG / string building / a local store, a safe-call
     * reader, a dangling GL Class entry, a method-precise hop, an interface default method, IS-A vs a reference, the
     * same-name collision, a re-rewrite, a hand-broken output). Prints one PASS / FAIL row per case.
     *
     *
     */
    static final class SelfTest {
        private static final String CE = PilotShipClassifier.ENGINE_API;
        private static final String STAT = "com/fs/starfarer/api/combat/MutableStat";
        private static final String ADV = "(L" + CE + ";)V";
        private static final String GL11 = "org/lwjgl/opengl/GL11";
        private static final String SB = "java/lang/StringBuilder";
        private static final Map<String, byte[]> JAR = new HashMap<>();
        private static int fails;

        interface Body {
            void emit(MethodVisitor m);
        }

        static byte[] cls(int access, String name, String sup, String[] itfs, String method, String desc,
                int methodAccess, Body body) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V1_8, access, name, null, sup == null ? "java/lang/Object" : sup, itfs);
            if (method != null) {
                MethodVisitor m = cw.visitMethod(methodAccess, method, desc, null, null);
                m.visitCode();
                body.emit(m);
                m.visitMaxs(0, 0);
                m.visitEnd();
            }
            cw.visitEnd();
            byte[] b = cw.toByteArray();
            JAR.put(name, b);
            return b;
        }

        /** A class whose {@code advance(CombatEngineAPI)V} holds one site, then {@code extra}, then RETURN. */
        static byte[] site(String name, String sup, Body extra) {
            return cls(Opcodes.ACC_PUBLIC, name, sup, null, "advance", ADV, Opcodes.ACC_PUBLIC, m -> {
                m.visitVarInsn(Opcodes.ALOAD, 1);
                m.visitMethodInsn(Opcodes.INVOKEINTERFACE, CE, PilotShipClassifier.GET_PLAYER_SHIP,
                        PilotShipClassifier.GET_PLAYER_SHIP_DESC, true);
                m.visitInsn(Opcodes.POP);
                extra.emit(m);
                m.visitInsn(Opcodes.RETURN);
            });
        }

        static void clock(MethodVisitor m) {
            m.visitVarInsn(Opcodes.ALOAD, 1);
            m.visitMethodInsn(Opcodes.INVOKEINTERFACE, CE, "getTimeMult", "()L" + STAT + ";", true);
        }

        static void call(MethodVisitor m, int op, String owner, String name, String desc) {
            m.visitMethodInsn(op, owner, name, desc, op == Opcodes.INVOKEINTERFACE);
        }

        static void check(String label, String name, byte[] b, Set<String> exclude, String loc, String want) {
            Result r = rewrite(name, b, JarIndex.ofClasses(JAR), exclude, loc);
            String got = r == null ? "null" : r.applied() ? "applied" : r.reason();
            boolean ok = want.endsWith("*") ? got.startsWith(want.substring(0, want.length() - 1)) : got.equals(want);
            fails += ok ? 0 : 1;
            System.out.println("selftest " + (ok ? "PASS " : "FAIL ") + label + " want=" + want + " got=" + got
                    + (r == null || r.detail().isEmpty() ? "" : " detail=" + r.detail()));
        }

        static int run() {
            // the hop's helpers: a pure method beside a drawing one; an interface default that draws; a MagicLib chain
            ClassWriter hw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            hw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "fx/Helper", null, "java/lang/Object", null);
            MethodVisitor cm = hw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "calc", "()F", null, null);
            cm.visitCode();
            cm.visitInsn(Opcodes.FCONST_0);
            cm.visitInsn(Opcodes.FRETURN);
            cm.visitMaxs(0, 0);
            cm.visitEnd();
            MethodVisitor dm = hw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "draw", "()V", null, null);
            dm.visitCode();
            dm.visitInsn(Opcodes.ICONST_0);
            call(dm, Opcodes.INVOKESTATIC, GL11, "glBegin", "(I)V");
            dm.visitInsn(Opcodes.RETURN);
            dm.visitMaxs(0, 0);
            dm.visitEnd();
            hw.visitEnd();
            JAR.put("fx/Helper", hw.toByteArray());
            cls(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE, "fx/Warn", null, null, "warn", "()V",
                    Opcodes.ACC_PUBLIC, m -> {
                        call(m, Opcodes.INVOKESTATIC, GL11, "glEnd", "()V");
                        m.visitInsn(Opcodes.RETURN);
                    });
            cls(Opcodes.ACC_PUBLIC, "fx/Impl", null, new String[] {"fx/Warn"}, null, null, 0, null);
            cls(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "fx/MidSub", PilotShipClassifier.MAGIC_SUBSYSTEM, null,
                    null, null, 0, null);

            byte[] plain = site("fx/Plain", null, m -> {
                m.visitVarInsn(Opcodes.ALOAD, 1);
                call(m, Opcodes.INVOKEINTERFACE, CE, "getPlayerShip", PilotShipClassifier.GET_PLAYER_SHIP_DESC);
                m.visitInsn(Opcodes.POP);
            });
            Result pr = rewrite("fx/Plain", plain, JarIndex.ofClasses(JAR), null, null);
            boolean two = pr != null && pr.applied() && pr.sites() == 2;
            fails += two ? 0 : 1;
            System.out.println("selftest " + (two ? "PASS" : "FAIL") + " plain_two_sites applied=" + two);
            check("rewrite_of_rewritten", "fx/Plain", two ? pr.patched() : plain, null, null, "no_sites");
            String broken = selfCheck(plain, plain, Map.of("advance" + ADV, 2));
            fails += broken != null ? 0 : 1;
            System.out.println("selftest " + (broken != null ? "PASS" : "FAIL") + " self_check_refuses miss=" + broken);
            check("not_candidate_kotlin", "kotlin/Foo", site("kotlin/Foo", null, m -> { }), null, null, "null");
            check("not_candidate_api_util", "com/fs/starfarer/api/util/Misc",
                    site("com/fs/starfarer/api/util/Misc", null, m -> { }), null, null, "null");
            check("candidate_api_impl", "com/fs/starfarer/api/impl/combat/Y",
                    site("com/fs/starfarer/api/impl/combat/Y", null, m -> { }), null, null, "applied");
            check("name_collision", "fx/Collision", cls(Opcodes.ACC_PUBLIC, "fx/Collision", null, null, "advance", ADV,
                    Opcodes.ACC_PUBLIC, m -> {
                        m.visitInsn(Opcodes.ACONST_NULL);
                        m.visitLdcInsn("x");
                        call(m, Opcodes.INVOKEVIRTUAL, "fx/Mission", "getPlayerShip",
                                "(Ljava/lang/String;)Ljava/lang/String;");
                        m.visitInsn(Opcodes.POP);
                        m.visitInsn(Opcodes.RETURN);
                    }), null, null, "no_sites");
            check("deny_list", "com/fs/starfarer/api/impl/hullmods/OperationsCenter",
                    site("com/fs/starfarer/api/impl/hullmods/OperationsCenter", null, m -> { }), null, null,
                    "deny_list");
            String loc = "file:/C:/Game/mods/My%20Mod/jars/my.jar";
            check("host_exclude_folder", "fx/Plain", plain, Set.of("my mod"), loc, "host_exclude");
            check("host_exclude_jar_stem", "fx/Plain", plain, Set.of("MY"), loc, "host_exclude");
            check("host_exclude_other", "fx/Plain", plain, Set.of("other"), loc, "applied");
            check("adopter", "fx/Adopter", site("fx/Adopter", null, m -> {
                m.visitLdcInsn(PilotShipClassifier.PILOT_MARK);
                m.visitInsn(Opcodes.POP);
            }), null, null, "adopter");
            check("magic_is_a", "fx/Sub", site("fx/Sub", "fx/MidSub", m -> { }), null, null, "magic_subsystem");
            check("magic_reference_only", "fx/PassSub", site("fx/PassSub", null, m -> {
                m.visitInsn(Opcodes.ACONST_NULL);
                call(m, Opcodes.INVOKESTATIC, "fx/Other", "use", "(L" + PilotShipClassifier.MAGIC_SUBSYSTEM + ";)V");
            }), null, null, "applied");
            check("control_transfer", "fx/Control", site("fx/Control", null, m -> {
                m.visitVarInsn(Opcodes.ALOAD, 1);
                m.visitInsn(Opcodes.ACONST_NULL);
                call(m, Opcodes.INVOKEINTERFACE, CE, "setPlayerShipExternal", "(Lcom/fs/starfarer/api/combat/ShipAPI;)V");
            }), null, null, "control_transfer");
            // the key family: a held-key poll is a twin site, the pointer and the event feed are not
            check("keys_twin_site", "fx/Keys", site("fx/Keys", null, m -> {
                m.visitInsn(Opcodes.ICONST_0);
                call(m, Opcodes.INVOKESTATIC, "org/lwjgl/input/Keyboard", "isKeyDown", "(I)Z");
                m.visitInsn(Opcodes.POP);
            }), null, null, "applied");
            check("local_cursor", "fx/Cursor", site("fx/Cursor", null, m -> {
                call(m, Opcodes.INVOKESTATIC, "org/lwjgl/input/Mouse", "getX", "()I");
                m.visitInsn(Opcodes.POP);
            }), null, null, "local_cursor");
            check("input_events", "fx/Events", site("fx/Events", null, m -> {
                m.visitInsn(Opcodes.ACONST_NULL);
                call(m, Opcodes.INVOKEINTERFACE, "com/fs/starfarer/api/input/InputEventAPI", "isConsumed", "()Z");
                m.visitInsn(Opcodes.POP);
            }), null, null, "input_events");
            ClassWriter dw = new ClassWriter(new ClassReader(site("fx/Dangling", null, m -> { })), 0);
            new ClassReader(JAR.get("fx/Dangling")).accept(dw, 0);
            dw.newClass(GL11);
            check("dangling_gl_class_entry", "fx/Dangling", dw.toByteArray(), null, null, "applied");
            check("drawing_magicui_helper", "fx/Ui", site("fx/Ui", null, m -> {
                for (int k = 0; k < 4; k++) {
                    m.visitInsn(Opcodes.ACONST_NULL);
                }
                m.visitInsn(Opcodes.ICONST_0);
                call(m, Opcodes.INVOKESTATIC, "org/magiclib/util/MagicUI", "addText",
                        "(Lcom/fs/starfarer/api/combat/ShipAPI;Ljava/lang/String;Ljava/awt/Color;"
                                + "Lorg/lwjgl/util/vector/Vector2f;Z)V");
            }), null, null, "drawing");
            check("hop_pure_method", "fx/CallsCalc", site("fx/CallsCalc", null, m -> {
                call(m, Opcodes.INVOKESTATIC, "fx/Helper", "calc", "()F");
                m.visitInsn(Opcodes.POP);
            }), null, null, "applied");
            check("hop_drawing_method", "fx/CallsDraw", site("fx/CallsDraw", null,
                    m -> call(m, Opcodes.INVOKESTATIC, "fx/Helper", "draw", "()V")), null, null, "drawing");
            check("hop_interface_default", "fx/CallsWarn", site("fx/CallsWarn", null, m -> {
                m.visitInsn(Opcodes.ACONST_NULL);
                call(m, Opcodes.INVOKEVIRTUAL, "fx/Impl", "warn", "()V");
            }), null, null, "drawing");
            check("clock_writer_fdiv", "fx/ClockFdiv", site("fx/ClockFdiv", null, m -> {
                clock(m);
                m.visitLdcInsn("id");
                m.visitInsn(Opcodes.FCONST_1);
                m.visitInsn(Opcodes.FCONST_2);
                m.visitInsn(Opcodes.FDIV);
                call(m, Opcodes.INVOKEVIRTUAL, STAT, "modifyMult", "(Ljava/lang/String;F)V");
            }), null, null, "battle_speed");
            check("clock_writer_string_building", "fx/ClockKt", site("fx/ClockKt", null, m -> {
                clock(m);
                m.visitInsn(Opcodes.DUP);
                m.visitLdcInsn("timeMult");
                call(m, Opcodes.INVOKESTATIC, PilotShipClassifier.KOTLIN_INTRINSICS, "checkNotNullExpressionValue",
                        "(Ljava/lang/Object;Ljava/lang/String;)V");
                m.visitTypeInsn(Opcodes.NEW, SB);
                m.visitInsn(Opcodes.DUP);
                call(m, Opcodes.INVOKESPECIAL, SB, "<init>", "()V");
                m.visitLdcInsn("a");
                call(m, Opcodes.INVOKEVIRTUAL, SB, "append", "(Ljava/lang/String;)L" + SB + ";");
                m.visitVarInsn(Opcodes.ALOAD, 0);
                call(m, Opcodes.INVOKEVIRTUAL, SB, "append", "(Ljava/lang/Object;)L" + SB + ";");
                call(m, Opcodes.INVOKEVIRTUAL, SB, "toString", "()Ljava/lang/String;");
                m.visitInsn(Opcodes.FCONST_2);
                m.visitInsn(Opcodes.FNEG);
                call(m, Opcodes.INVOKEVIRTUAL, STAT, "modifyMult", "(Ljava/lang/String;F)V");
            }), null, null, "battle_speed");
            check("clock_reader", "fx/ClockRead", site("fx/ClockRead", null, m -> {
                clock(m);
                call(m, Opcodes.INVOKEVIRTUAL, STAT, "getModifiedValue", "()F");
                m.visitVarInsn(Opcodes.FSTORE, 3);
            }), null, null, "applied");
            check("clock_stored_then_written", "fx/ClockStored", site("fx/ClockStored", null, m -> {
                clock(m);
                m.visitVarInsn(Opcodes.ASTORE, 3);
                m.visitVarInsn(Opcodes.ALOAD, 3);
                call(m, Opcodes.INVOKEVIRTUAL, STAT, "getModifiedValue", "()F");
                m.visitInsn(Opcodes.POP);
                m.visitVarInsn(Opcodes.ALOAD, 3);
                m.visitLdcInsn("id");
                call(m, Opcodes.INVOKEVIRTUAL, STAT, "unmodify", "(Ljava/lang/String;)V");
            }), null, null, "battle_speed");
            check("clock_safe_call_reader", "fx/ClockSafe", site("fx/ClockSafe", null, m -> {
                Label isNull = new Label();
                Label join = new Label();
                clock(m);
                m.visitInsn(Opcodes.DUP);
                m.visitJumpInsn(Opcodes.IFNULL, isNull);
                call(m, Opcodes.INVOKEVIRTUAL, STAT, "getModifiedValue", "()F");
                m.visitJumpInsn(Opcodes.GOTO, join);
                m.visitLabel(isNull);
                m.visitInsn(Opcodes.POP);
                m.visitInsn(Opcodes.FCONST_1);
                m.visitLabel(join);
                m.visitVarInsn(Opcodes.FSTORE, 3);
            }), null, null, "applied");
            check("clock_escapes_to_field", "fx/ClockEsc", site("fx/ClockEsc", null, m -> {
                clock(m);
                m.visitFieldInsn(Opcodes.PUTSTATIC, "fx/ClockEsc", "s", "L" + STAT + ";");
            }), null, null, "battle_speed");
            byte[] junk = Arrays.copyOf(new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 52, 0,
                    3, 1, 0, 13}, 26);
            System.arraycopy(PilotShipClassifier.GET_PLAYER_SHIP.getBytes(StandardCharsets.US_ASCII), 0, junk, 13, 13);
            check("malformed_class", "fx/Junk", junk, null, null, "error:*");
            System.out.println("selftest summary fails=" + fails);
            return fails;
        }
    }
}
