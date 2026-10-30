package cc.starsector.compat.pilotship;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The player-ship window's engine half: the engine's {@code Ship.advance(F)V} wrapped IN PLACE so the holder
 * ({@link #HOLDER}) knows, on every frame, which ship's own advance is running. Every hullmod, system and listener
 * script the engine calls from inside that method then runs inside that ship's window, and the call-site redirect
 * in the scripts asks the holder for the answer of the innermost open window. Bytes only (ASM core, no server
 * class), compiled into the compat unit.
 *
 * <p>The edit, as measured verify-clean on Temurin 17: {@code ALOAD 0; INVOKESTATIC enter}
 * at the entry; {@code INVOKESTATIC exit} before EVERY return; ONE catch-all range over the whole original body
 * (its first instruction up to the end of the last), appended AFTER every original exception entry, whose handler
 * is {@code INVOKESTATIC exit; ATHROW} placed after the method's unconditional tail, with ONE hand frame
 * {@code full_frame locals=[] stack=[Throwable]} (the only edge into it is the exception edge, so empty locals are
 * assignable from every point of the range). {@code ClassWriter(reader, 0)}: the constant pool is copied and grown
 * at its end, the original frames, max_stack and max_locals pass through, no local is added. The original entries
 * keep their precedence (the first match wins): a throw inside the body runs the method's own finally first, whose
 * rethrow lands in the new range, so exit runs exactly once. Calls nest (a ship deployed from inside another ship's
 * system advances ten times there): the holder's stack, not this edit, owns depth.
 *
 * <p>The holder's law this edit relies on and does not guard: {@code exit()} NEVER throws. The guard before each
 * return sits inside the new range, so a throwing exit would run the handler's exit too (a double pop). The range
 * starts after the entry call, so a throw from the entry call itself is never paired with an exit; the holder's
 * step guard catches that imbalance.
 *
 * <p>Fail-CLOSED, by the caller: this class only reports. A name other than {@link #TARGET} returns {@code null}
 * unparsed; for the target, a refusal returns a {@link Result} with no bytes and one fixed reason, and the engine
 * applier halts rather than load a half-wrapped engine class. The gate, in order: class version (frames needed),
 * the window not already present ({@link #REASON_WINDOW_REPEATED}), the method present, the edit, then the output
 * SELF-CHECK, an independent re-read: {@code aload_0; invokestatic enter} as the first two instructions (code
 * bytes 0 and 1); one exit immediately before every return (as many as the original has, counted) plus the
 * handler's, and no other holder reference in the class; the original exception entries first and unchanged, the
 * catch-all last over [first original instruction, handler); the hand frame last and exact; with the inserted
 * instructions removed, the instruction stream, its branch targets, the exception entries and every original frame
 * equal to the original's; the class-wide call multiset unchanged; max_stack unchanged and at least 1, max_locals
 * unchanged; the method header and every OTHER method byte-identical; the constant pool a byte-identical prefix of
 * the output's with exactly the holder added as a Class.
 *
 * <p>{@code --golden <jar>} prints the ORIGINAL method's fingerprint in the registry's method form: SHA-256 over
 * the {@code code[]} bytes of its Code attribute, i.e. the {@code code_length} bytes that follow
 * {@code u2 max_stack, u2 max_locals, u4 code_length} (maxs, exception table and sub-attributes excluded), plus
 * the patched class's sha256 and the re-wrap of that output (which must refuse). {@code --verify <jar>} re-proves
 * the edit on an install: the engine's raw classes cannot load under verification at all (the class-file parser
 * rejects member names the classfile format forbids), so it checks PARSE PARITY (the original and the patched
 * class refused with the identical message), then links both through a loader that escapes the illegal names (the
 * type-checking verifier runs), and the edit minus its hand frame must FAIL there and fail the self-check (the
 * negative controls), behind a positive control that proves the JVM verifies at all.
 *
 *
 */
public final class ShipAdvanceWindowRewriter {

    /** The one class this rewrite touches (JVM internal name); every other name returns {@code null} unparsed. */
    public static final String TARGET = "com/fs/starfarer/combat/entities/Ship";
    public static final String METHOD = "advance";
    public static final String METHOD_DESC = "(F)V";
    /** The window holder (system loader, statics); referenced by name only, never loaded here. */
    public static final String HOLDER = "cc/starsector/server/transform/PilotShipWindow";
    public static final String ENTER = "enter";
    public static final String ENTER_DESC = "(Ljava/lang/Object;)V";
    public static final String EXIT = "exit";
    public static final String EXIT_DESC = "()V";

    /** Refusal: no instance {@code advance(F)V} with a body. */
    public static final String REASON_WINDOW_ABSENT = "window_absent";
    /** Refusal: the input already references the holder (a second wrap would double every enter). */
    public static final String REASON_WINDOW_REPEATED = "window_repeated";
    /** Refusal: a class older than major 50 has no StackMapTable for the hand frame. */
    public static final String REASON_CLASS_VERSION = "class_version";
    /** Refusal: the output failed the independent re-read ({@link Result#detail()} names the first miss). */
    public static final String REASON_SELF_CHECK = "self_check";
    /** Refusal prefix: a Throwable during the rewrite, + its simple class name. */
    public static final String REASON_ERROR_PREFIX = "error:";

    private static final String KEY = METHOD + METHOD_DESC;
    private static final String THROWABLE = "java/lang/Throwable";
    private static final String CONTROL = "cc/starsector/compat/pilotship/VerifyControl";

    private ShipAdvanceWindowRewriter() {
    }

    /** One rewrite's outcome: patched bytes (applied) or a reason (refused); never both. */
    public static final class Result {
        private final byte[] patched;
        private final String reason;
        private final String detail;

        private Result(byte[] patched, String reason, String detail) {
            this.patched = patched;
            this.reason = reason;
            this.detail = detail;
        }

        public boolean applied() {
            return patched != null;
        }

        /** The wrapped class; {@code null} on a refusal. */
        public byte[] patched() {
            return patched;
        }

        /** One of the {@code REASON_*} values (or {@code error:<SimpleName>}); {@code null} when applied. */
        public String reason() {
            return reason;
        }

        /**
         * Bounded tokens: on a refusal the first self-check miss ({@code ""} if none); when applied the wrap's
         * shape, {@code returns=<n> code_len=<in>-><out> frames=<in>-><out>}.
         */
        public String detail() {
            return detail;
        }
    }

    /** The rewrite: {@code null} for any name but {@link #TARGET} (before any parse), else a Result; never throws. */
    public static Result rewrite(String internalName, byte[] bytes) {
        if (!TARGET.equals(internalName)) {
            return null;
        }
        try {
            int major = new ClassReader(bytes).readUnsignedShort(6);
            if (major < Opcodes.V1_6) {
                return new Result(null, REASON_CLASS_VERSION, "major=" + major);
            }
            Read in = read(bytes);
            if (in.holderRefs > 0) {
                return new Result(null, REASON_WINDOW_REPEATED, "holder_refs=" + in.holderRefs);
            }
            if (in.body == null) {
                return new Result(null, REASON_WINDOW_ABSENT, "");
            }
            byte[] out = wrap(bytes, true);
            String[] shape = {""};
            String miss = selfCheck(bytes, in, out, shape);
            return miss == null ? new Result(out, null, shape[0]) : new Result(null, REASON_SELF_CHECK, miss);
        } catch (Throwable t) {
            return new Result(null, REASON_ERROR_PREFIX + simpleName(t), "");
        }
    }

    // ---------------------------------------------------------------- the edit

    /** The wrap; {@code handFrame=false} only for the verifier's negative control. */
    private static byte[] wrap(byte[] in, boolean handFrame) {
        ClassReader cr = new ClassReader(in);
        ClassWriter cw = new ClassWriter(cr, 0);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exc) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, exc);
                // every other method keeps the writer's own visitor, so ASM copies its bytes verbatim
                return isTarget(access, name, desc) ? new Wrap(mv, handFrame) : mv;
            }
        }, 0);
        return cw.toByteArray();
    }

    private static boolean isTarget(int access, String name, String desc) {
        return METHOD.equals(name) && METHOD_DESC.equals(desc) && (access & Opcodes.ACC_STATIC) == 0;
    }

    private static boolean isReturn(int op) {
        return op >= Opcodes.IRETURN && op <= Opcodes.RETURN;
    }

    private static final class Wrap extends MethodVisitor {
        private final boolean handFrame;
        private final Label start = new Label();
        private final Label end = new Label();
        private final Label handler = new Label();

        Wrap(MethodVisitor mv, boolean handFrame) {
            super(Opcodes.ASM9, mv);
            this.handFrame = handFrame;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOLDER, ENTER, ENTER_DESC, false);
            super.visitLabel(start);
        }

        @Override
        public void visitInsn(int opcode) {
            if (isReturn(opcode)) {
                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOLDER, EXIT, EXIT_DESC, false);
            }
            super.visitInsn(opcode);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            super.visitLabel(end);
            super.visitTryCatchBlock(start, end, handler, null); // after every original entry: theirs match first
            super.visitLabel(handler);
            if (handFrame) {
                super.visitFrame(Opcodes.F_FULL, 0, new Object[0], 1, new Object[] {THROWABLE});
            }
            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOLDER, EXIT, EXIT_DESC, false);
            super.visitInsn(Opcodes.ATHROW);
            super.visitMaxs(maxStack, maxLocals);
        }
    }

    // ---------------------------------------------------------------- the re-read (instruction-level)

    private record Insn(int op, String text, Label[] labels) {
    }

    private record Frame(int pos, String text, Label[] labels) {
    }

    private record Tc(Label start, Label end, Label handler, String type) {
    }

    /** One class pass: the target's body, the holder references and the call multiset (holder excluded). */
    private static final class Read {
        Body body;
        int holderRefs;
        final Map<String, Integer> calls = new TreeMap<>();
    }

    private static Read read(byte[] bytes) {
        Read r = new Read();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exc) {
                return new Body(r, isTarget(access, name, desc));
            }
        }, ClassReader.SKIP_DEBUG);
        return r;
    }

    /** Records the target's instructions (labels by the instruction index they precede); elsewhere only counts. */
    private static final class Body extends MethodVisitor {
        private final Read read;
        private final boolean record;
        final List<Insn> insns = new ArrayList<>();
        final List<Frame> frames = new ArrayList<>();
        final List<Tc> tcs = new ArrayList<>();
        final IdentityHashMap<Label, Integer> at = new IdentityHashMap<>();
        int returns;

        Body(Read read, boolean record) {
            super(Opcodes.ASM9);
            this.read = read;
            this.record = record;
        }

        private void add(int op, String text, Label... labels) {
            if (record) {
                insns.add(new Insn(op, text, labels));
                if (isReturn(op)) {
                    returns++;
                }
            }
        }

        private void owner(String owner) {
            if (HOLDER.equals(owner)) {
                read.holderRefs++;
            }
        }

        @Override
        public void visitCode() {
            if (record) {
                read.body = this;
            }
        }

        @Override
        public void visitLabel(Label l) {
            if (record) {
                at.put(l, insns.size());
            }
        }

        @Override
        public void visitFrame(int type, int nLocal, Object[] local, int nStack, Object[] stack) {
            if (record) {
                List<Label> ls = new ArrayList<>();
                frames.add(new Frame(insns.size(), frameText(type, nLocal, local, nStack, stack, ls),
                        ls.toArray(new Label[0])));
            }
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            if (record) {
                tcs.add(new Tc(start, end, handler, type));
            }
        }

        @Override
        public void visitInsn(int op) {
            add(op, Integer.toString(op));
        }

        @Override
        public void visitIntInsn(int op, int operand) {
            add(op, op + " " + operand);
        }

        @Override
        public void visitVarInsn(int op, int var) {
            add(op, op + " " + var);
        }

        @Override
        public void visitTypeInsn(int op, String type) {
            owner(type);
            add(op, op + " " + type);
        }

        @Override
        public void visitFieldInsn(int op, String o, String name, String desc) {
            owner(o);
            add(op, op + " " + o + "." + name + " " + desc);
        }

        @Override
        public void visitMethodInsn(int op, String o, String name, String desc, boolean itf) {
            owner(o);
            String t = call(op, o, name, desc, itf);
            if (!HOLDER.equals(o)) {
                read.calls.merge(t, 1, Integer::sum);
            }
            add(op, t);
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... args) {
            add(Opcodes.INVOKEDYNAMIC, Opcodes.INVOKEDYNAMIC + " " + name + " " + desc + " " + bsm + " "
                    + Arrays.toString(args));
        }

        @Override
        public void visitJumpInsn(int op, Label l) {
            add(op, Integer.toString(op), l);
        }

        @Override
        public void visitLdcInsn(Object v) {
            add(Opcodes.LDC, Opcodes.LDC + " " + v.getClass().getSimpleName() + ":" + v);
        }

        @Override
        public void visitIincInsn(int var, int inc) {
            add(Opcodes.IINC, Opcodes.IINC + " " + var + " " + inc);
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            add(Opcodes.TABLESWITCH, Opcodes.TABLESWITCH + " " + min + " " + max, prepend(dflt, labels));
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            add(Opcodes.LOOKUPSWITCH, Opcodes.LOOKUPSWITCH + " " + Arrays.toString(keys), prepend(dflt, labels));
        }

        @Override
        public void visitMultiANewArrayInsn(String desc, int dims) {
            add(Opcodes.MULTIANEWARRAY, Opcodes.MULTIANEWARRAY + " " + desc + " " + dims);
        }
    }

    private static Label[] prepend(Label first, Label[] rest) {
        Label[] all = new Label[rest.length + 1];
        all[0] = first;
        System.arraycopy(rest, 0, all, 1, rest.length);
        return all;
    }

    private static String call(int op, String owner, String name, String desc, boolean itf) {
        return op + " " + owner + "." + name + " " + desc + (itf ? " itf" : "");
    }

    /** A frame as text; a Label (an uninitialized NEW) becomes {@code L} and is appended to {@code ls}. */
    private static String frameText(int type, int nLocal, Object[] local, int nStack, Object[] stack, List<Label> ls) {
        return type + " " + types(nLocal, local, ls) + " | " + types(nStack, stack, ls);
    }

    private static String types(int n, Object[] arr, List<Label> ls) {
        StringBuilder sb = new StringBuilder().append(n).append(':');
        for (int i = 0; arr != null && i < n; i++) {
            Object v = arr[i];
            if (v instanceof Label l) {
                ls.add(l);
                sb.append('L');
            } else {
                sb.append(v instanceof Integer ? "#" + v : String.valueOf(v));
            }
            sb.append(',');
        }
        return sb.toString();
    }

    /** The body as text under a position map (kept-instruction index), skipping stripped instructions. */
    private static List<String> canon(Body b, int[] map, boolean[] strip, int tcCount, int frameCount) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < b.insns.size(); i++) {
            if (strip == null || !strip[i]) {
                out.add(b.insns.get(i).text() + targets(b, map, b.insns.get(i).labels()));
            }
        }
        for (int j = 0; j < tcCount; j++) {
            Tc t = b.tcs.get(j);
            out.add("tc " + pos(b, map, t.start()) + " " + pos(b, map, t.end()) + " " + pos(b, map, t.handler())
                    + " " + t.type());
        }
        for (int j = 0; j < frameCount; j++) {
            Frame f = b.frames.get(j);
            out.add("frame " + map[f.pos()] + " " + f.text() + targets(b, map, f.labels()));
        }
        return out;
    }

    private static int pos(Body b, int[] map, Label l) {
        Integer p = b.at.get(l);
        return p == null ? -1 : map[p];
    }

    private static String targets(Body b, int[] map, Label[] labels) {
        StringBuilder sb = new StringBuilder();
        for (Label l : labels) {
            sb.append(" @").append(pos(b, map, l));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- the self-check

    /** {@code null} = the output holds ({@code shape[0]} = the applied detail); else the first miss. */
    private static String selfCheck(byte[] in, Read before, byte[] out, String[] shape) {
        Read after = read(out);
        Body o = before.body;
        Body p = after.body;
        if (p == null) {
            return "window_lost";
        }
        if (!before.calls.equals(after.calls)) {
            return "call_multiset_changed";
        }
        int n = p.insns.size();
        String exit = call(Opcodes.INVOKESTATIC, HOLDER, EXIT, EXIT_DESC, false);
        if (n < 4 || !p.insns.get(0).text().equals(Opcodes.ALOAD + " 0")
                || !p.insns.get(1).text().equals(call(Opcodes.INVOKESTATIC, HOLDER, ENTER, ENTER_DESC, false))) {
            return "entry";
        }
        if (!p.insns.get(n - 2).text().equals(exit) || p.insns.get(n - 1).op() != Opcodes.ATHROW) {
            return "handler_body";
        }
        boolean[] strip = new boolean[n];
        strip[0] = strip[1] = strip[n - 2] = strip[n - 1] = true;
        int guards = 0;
        for (int i = 2; i < n - 2; i++) {
            Insn x = p.insns.get(i);
            if (x.text().equals(exit)) {
                if (!isReturn(p.insns.get(i + 1).op())) {
                    return "exit_unpaired@" + i;
                }
                strip[i] = true;
                guards++;
            } else if (isReturn(x.op()) && !p.insns.get(i - 1).text().equals(exit)) {
                return "return_unguarded@" + i;
            }
        }
        if (guards != o.returns || p.returns != o.returns) {
            return "guards=" + guards + " returns=" + o.returns;
        }
        if (after.holderRefs != guards + 2) { // the enter, the guards, the handler's exit; nothing else in the class
            return "holder_refs=" + after.holderRefs;
        }
        int k = o.tcs.size();
        if (p.tcs.size() != k + 1) {
            return "try_catch=" + p.tcs.size() + "/" + k;
        }
        Tc all = p.tcs.get(k);
        Integer s = p.at.get(all.start());
        Integer e = p.at.get(all.end());
        Integer h = p.at.get(all.handler());
        if (all.type() != null || s == null || s != 2 || e == null || e != n - 2 || h == null || h != n - 2) {
            return "catch_all";
        }
        int f = o.frames.size();
        if (p.frames.size() != f + 1) {
            return "frames=" + p.frames.size() + "/" + f;
        }
        Frame hand = p.frames.get(f);
        String handText = frameText(Opcodes.F_FULL, 0, new Object[0], 1, new Object[] {THROWABLE}, new ArrayList<>());
        if (hand.pos() != n - 2 || !hand.text().equals(handText)) {
            return "hand_frame";
        }
        int[] map = new int[n + 1];
        int kept = 0;
        for (int i = 0; i < n; i++) {
            map[i] = kept;
            if (!strip[i]) {
                kept++;
            }
        }
        map[n] = kept;
        int no = o.insns.size();
        if (kept != no) {
            return "insn_count=" + kept + "/" + no;
        }
        int[] id = new int[no + 1];
        for (int i = 0; i <= no; i++) {
            id[i] = i;
        }
        List<String> want = canon(o, id, null, k, f);
        List<String> got = canon(p, map, strip, k, f);
        if (!want.equals(got)) {
            int i = 0;
            while (i < want.size() && i < got.size() && want.get(i).equals(got.get(i))) {
                i++;
            }
            return "body_changed@" + i;
        }
        String raw = rawCheck(in, out);
        if (raw != null) {
            return raw;
        }
        int[] ci = raw(in).code.get(KEY);
        int[] co = raw(out).code.get(KEY);
        shape[0] = "returns=" + o.returns + " code_len=" + ci[1] + "->" + co[1] + " frames=" + f + "->" + (f + 1);
        return null;
    }

    // ---------------------------------------------------------------- the re-read (classfile bytes)

    /** Per method (name+descriptor): the whole method_info, its header minus Code, and the Code facts. */
    private static final class Raw {
        final Map<String, byte[]> methods = new TreeMap<>();
        final Map<String, byte[]> heads = new TreeMap<>();
        /** {code offset, code length, max_stack, max_locals}. */
        final Map<String, int[]> code = new TreeMap<>();
        final Map<String, String> codeAttrs = new TreeMap<>();
        final Set<String> classes = new TreeSet<>();
        int poolEnd;
        int headEnd;
    }

    private static Raw raw(byte[] b) {
        ClassReader cr = new ClassReader(b);
        Raw r = new Raw();
        char[] buf = new char[cr.getMaxStringLength() + 1];
        for (int i = 1; i < cr.getItemCount(); i++) {
            int off = cr.getItem(i);
            if (off != 0 && b[off - 1] == 7) { // CONSTANT_Class
                r.classes.add(cr.readUTF8(off, buf));
            }
        }
        r.poolEnd = cr.header;
        int p = cr.header + 6;
        p += 2 + 2 * cr.readUnsignedShort(p);
        r.headEnd = p;
        int fields = cr.readUnsignedShort(p);
        p += 2;
        for (int i = 0; i < fields; i++) {
            int attrs = cr.readUnsignedShort(p + 6);
            p += 8;
            for (int a = 0; a < attrs; a++) {
                p += 6 + cr.readInt(p + 2);
            }
        }
        int methods = cr.readUnsignedShort(p);
        p += 2;
        for (int i = 0; i < methods; i++) {
            int start = p;
            String key = cr.readUTF8(p + 2, buf) + cr.readUTF8(p + 4, buf);
            int attrs = cr.readUnsignedShort(p + 6);
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            head.write(b, p, 8);
            p += 8;
            for (int a = 0; a < attrs; a++) {
                int len = cr.readInt(p + 2);
                if ("Code".equals(cr.readUTF8(p, buf))) {
                    int codeLen = cr.readInt(p + 10);
                    r.code.put(key, new int[] {p + 14, codeLen, cr.readUnsignedShort(p + 6), cr.readUnsignedShort(p + 8)});
                    int q = p + 14 + codeLen;
                    q += 2 + 8 * cr.readUnsignedShort(q);
                    int sub = cr.readUnsignedShort(q);
                    q += 2;
                    StringBuilder names = new StringBuilder();
                    for (int j = 0; j < sub; j++) {
                        names.append(cr.readUTF8(q, buf)).append(',');
                        q += 6 + cr.readInt(q + 2);
                    }
                    r.codeAttrs.put(key, names.toString());
                } else {
                    head.write(b, p, 6 + len);
                }
                p += 6 + len;
            }
            r.methods.put(key, Arrays.copyOfRange(b, start, p));
            r.heads.put(key, head.toByteArray());
        }
        return r;
    }

    private static String rawCheck(byte[] in, byte[] out) {
        Raw ri = raw(in);
        Raw ro = raw(out);
        if (!ri.methods.keySet().equals(ro.methods.keySet())) {
            return "method_set_changed";
        }
        int changed = 0;
        for (Map.Entry<String, byte[]> m : ri.methods.entrySet()) {
            if (!m.getKey().equals(KEY) && !Arrays.equals(m.getValue(), ro.methods.get(m.getKey()))) {
                changed++;
            }
        }
        if (changed > 0) {
            return "other_methods_changed=" + changed;
        }
        if (!Arrays.equals(ri.heads.get(KEY), ro.heads.get(KEY))) {
            return "method_header";
        }
        int[] ci = ri.code.get(KEY);
        int[] co = ro.code.get(KEY);
        if (co[3] != ci[3]) {
            return "max_locals=" + co[3] + "/" + ci[3];
        }
        if (co[2] != ci[2] || co[2] < 1) {
            return "max_stack=" + co[2] + "/" + ci[2];
        }
        if (!ri.codeAttrs.get(KEY).equals(ro.codeAttrs.get(KEY))) {
            return "code_attributes";
        }
        if ((out[co[0]] & 0xFF) != 0x2a || (out[co[0] + 1] & 0xFF) != 0xb8) { // aload_0, invokestatic
            return "entry_bytes";
        }
        if (!Arrays.equals(in, 0, 8, out, 0, 8) || !Arrays.equals(in, 10, ri.poolEnd, out, 10, ri.poolEnd)) {
            return "pool_prefix";
        }
        if (!Arrays.equals(in, ri.poolEnd, ri.headEnd, out, ro.poolEnd, ro.headEnd)) {
            return "class_header";
        }
        Set<String> added = new TreeSet<>(ro.classes);
        added.removeAll(ri.classes);
        if (!ro.classes.containsAll(ri.classes) || !added.equals(Collections.singleton(HOLDER))) {
            return "pool_class_delta=" + added.size();
        }
        return null;
    }

    // ---------------------------------------------------------------- helpers

    /** Lowercase hex sha256 of {@code b[off, off+len)}. */
    public static String sha256(byte[] b, int off, int len) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update(b, off, len);
            StringBuilder sb = new StringBuilder(64);
            for (byte x : d.digest()) {
                sb.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String simpleName(Throwable t) {
        String s = t.getClass().getSimpleName();
        return s.isEmpty() ? t.getClass().getName().substring(t.getClass().getName().lastIndexOf('.') + 1) : s;
    }

    // ---------------------------------------------------------------- the CLI: --golden / --verify

    /**
     * Exit 0 = applied and the output's re-wrap refused (golden) / every leg as expected (verify); 1 = refused, the
     * re-wrap applied, or a leg failed; 2 = usage; 3 = setup.
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 2 || !(args[0].equals("--golden") || args[0].equals("--verify"))) {
            System.err.println("usage: ShipAdvanceWindowRewriter --golden|--verify <engine jar>");
            System.exit(2);
            return;
        }
        Path jar = Path.of(args[1]).toAbsolutePath();
        String label = jar.getFileName().toString();
        byte[] in;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry e = zip.getEntry(TARGET + ".class");
            if (e == null) {
                System.out.println("SETUP no " + TARGET + " in jar=" + label);
                System.exit(3);
                return;
            }
            try (InputStream s = zip.getInputStream(e)) {
                in = s.readAllBytes();
            }
        }
        Result r = rewrite(TARGET, in);
        System.exit(args[0].equals("--golden") ? golden(label, in, r) : verify(jar, label, in, r));
    }

    private static int golden(String label, byte[] in, Result r) {
        int[] c = raw(in).code.get(KEY);
        System.out.println("golden jar=" + label + " class=" + TARGET + " method=" + KEY + " code_sha256="
                + (c == null ? "none code_len=0" : sha256(in, c[0], c[1]) + " code_len=" + c[1])
                + " in_sha256=" + sha256(in, 0, in.length) + " in_len=" + in.length);
        if (!r.applied()) {
            System.out.println("golden verdict=refused:" + r.reason() + " detail=" + r.detail());
            return 1;
        }
        byte[] out = r.patched();
        int[] d = raw(out).code.get(KEY);
        Result again = rewrite(TARGET, out);
        System.out.println("golden verdict=applied " + r.detail() + " out_sha256=" + sha256(out, 0, out.length)
                + " out_len=" + out.length + " out_code_sha256=" + sha256(out, d[0], d[1]) + " rewrap="
                + (again.applied() ? "APPLIED" : again.reason()));
        return again.applied() ? 1 : 0;
    }

    private static int verify(Path jar, String label, byte[] in, Result r) throws IOException {
        if (!r.applied()) {
            System.out.println("verify FAIL rewrite refused:" + r.reason() + " detail=" + r.detail());
            return 1;
        }
        List<URL> urls = new ArrayList<>();
        try (Stream<Path> listing = Files.list(jar.getParent())) {
            for (Path p : listing.sorted().toList()) {
                if (p.getFileName().toString().endsWith(".jar")) {
                    urls.add(p.toUri().toURL());
                }
            }
        }
        URL[] core = urls.toArray(new URL[0]);
        String bin = TARGET.replace('/', '.');
        int fails = 0;
        try (URLClassLoader parent = new URLClassLoader(core, ClassLoader.getPlatformClassLoader())) {
            Throwable control = linkFailure(parent, CONTROL.replace('/', '.'), controlBytes());
            if (!(control instanceof VerifyError)) {
                System.out.println("verify SETUP control=" + brief(control) + " (this JVM does not verify defined"
                        + " classes: run without -noverify / -Xverify:none)");
                return 3;
            }
            System.out.println("verify control=" + brief(control) + " ok");
            Throwable base = linkFailure(parent, bin, in);
            Throwable after = linkFailure(parent, bin, r.patched());
            boolean parity = base == null ? after == null
                    : base instanceof ClassFormatError && after instanceof ClassFormatError
                            && Objects.equals(base.getMessage(), after.getMessage());
            System.out.println("verify parse_parity original=" + brief(base) + " patched=" + brief(after) + " "
                    + (parity ? "ok" : "FAIL"));
            fails += parity ? 0 : 1;
        }
        fails += leg("sanitized_original", core, in, false);
        fails += leg("sanitized_patched", core, r.patched(), false);
        byte[] noFrame = wrap(in, false);
        fails += leg("sanitized_no_hand_frame", core, noFrame, true);
        String miss = selfCheck(in, read(in), noFrame, new String[1]); // the re-read must refuse that output too
        System.out.println("verify self_check_no_hand_frame miss=" + miss + " " + (miss != null ? "ok" : "FAIL"));
        fails += miss != null ? 0 : 1;
        System.out.println("verify jar=" + label + " java=" + System.getProperty("java.version") + " "
                + r.detail() + " " + (fails == 0 ? "PASS" : "FAIL fails=" + fails));
        return fails == 0 ? 0 : 1;
    }

    /** Defines {@code bytes} under a child of {@code parent} and LINKS it (no initializer runs): the error, or null. */
    private static Throwable linkFailure(ClassLoader parent, String binaryName, byte[] bytes) {
        final class Definer extends ClassLoader {
            Definer() {
                super(parent);
            }

            Class<?> define() {
                return defineClass(binaryName, bytes, 0, bytes.length);
            }
        }
        try {
            new Definer().define().getDeclaredMethods(); // HotSpot links (= verifies) here
            return null;
        } catch (LinkageError e) {
            return e;
        }
    }

    private static int leg(String name, URL[] core, byte[] ship, boolean expectVerifyError) throws IOException {
        try (Sanitizing l = new Sanitizing(core, ship)) {
            Throwable t;
            try {
                Class.forName(TARGET.replace('/', '.'), false, l).getDeclaredMethods();
                t = null;
            } catch (LinkageError | ClassNotFoundException e) {
                t = e;
            }
            boolean ok = expectVerifyError ? t instanceof VerifyError : t == null;
            System.out.println("verify " + name + " defined=" + l.defined + " got=" + brief(t) + " "
                    + (ok ? "ok" : "FAIL"));
            return ok ? 0 : 1;
        }
    }

    private static String brief(Throwable t) {
        if (t == null) {
            return "linked";
        }
        String m = String.valueOf(t.getMessage());
        int nl = m.indexOf('\n');
        m = nl < 0 ? m : m.substring(0, nl);
        return simpleName(t) + "[" + (m.length() > 160 ? m.substring(0, 160) : m) + "]";
    }

    /** A version-52 class whose {@code static void m()} is a lone {@code ireturn}: parses, never verifies. */
    private static byte[] controlBytes() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, CONTROL, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "m", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Defines every class it loads from the install's jars re-encoded (fresh pool) with the member names the
     * class-file parser rejects escaped ({@code . ; [ /}, and {@code < >} in method names), consistently at the
     * declarations and the references, so the type-checking verifier gets to read the engine; the target's bytes
     * are the given ones, escaped the same way.
     */
    private static final class Sanitizing extends URLClassLoader {
        private final byte[] ship;
        int defined;

        Sanitizing(URL[] core, byte[] ship) {
            super(core, ClassLoader.getPlatformClassLoader());
            this.ship = ship;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            String internal = name.replace('.', '/');
            byte[] b = ship;
            if (!internal.equals(TARGET)) {
                try (InputStream s = getResourceAsStream(internal + ".class")) {
                    if (s == null) {
                        throw new ClassNotFoundException(name);
                    }
                    b = s.readAllBytes();
                } catch (IOException e) {
                    throw new ClassNotFoundException(name, e);
                }
            }
            byte[] clean = sanitize(b);
            defined++;
            return defineClass(name, clean, 0, clean.length);
        }
    }

    private static byte[] sanitize(byte[] b) {
        ClassReader cr = new ClassReader(b);
        ClassWriter cw = new ClassWriter(0);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public FieldVisitor visitField(int a, String n, String d, String s, Object v) {
                return super.visitField(a, esc(n, false), d, s, v);
            }

            @Override
            public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(a, esc(n, true), d, s, e)) {
                    @Override
                    public void visitFieldInsn(int op, String o, String nm, String ds) {
                        super.visitFieldInsn(op, o, esc(nm, false), ds);
                    }

                    @Override
                    public void visitMethodInsn(int op, String o, String nm, String ds, boolean itf) {
                        super.visitMethodInsn(op, o, esc(nm, true), ds, itf);
                    }
                };
            }
        }, 0);
        return cw.toByteArray();
    }

    private static String esc(String n, boolean method) {
        if (method && (n.equals("<init>") || n.equals("<clinit>"))) {
            return n;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c == '.' || c == ';' || c == '[' || c == '/' || (method && (c == '<' || c == '>'))) {
                sb.append("$x").append(Integer.toHexString(c)).append('$');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
