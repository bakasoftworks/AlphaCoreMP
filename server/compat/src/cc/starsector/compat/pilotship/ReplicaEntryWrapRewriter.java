package cc.starsector.compat.pilotship;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The replica window's api-entry wrap: in a mod class the shared verdict already rewrites, each per-ship API entry
 * method the class declares ({@link #ENTRIES}) is wrapped IN PLACE so a client's holder knows, for the length of
 * that call, which ship's own script is running. A client cannot wrap the engine's ship update (patched engine
 * bytes fail the newer runtime's forced class-file format check), so the window opens at the mod method instead:
 * every armed row (hullmod advanceInCombat; system apply; weapon advance / advanceAfter / onFire; beam advance) runs
 * inside the engine's per-ship update on the serve too, so the client window answers in a subset of the places the
 * serve's window does. The damage-listener family ({@link #CENSUS_ONLY}) is counted, never wrapped: the engine
 * dispatches damage listeners wherever applyDamage runs (collision passes included) and the serve has no
 * damage-listener window yet, so arming it here would open client windows the serve never opens.
 *
 * <p>The edit (the serve's engine-wrap shape, on a mod method): {@code ALOAD <key>; INVOKESTATIC enter(Object)V}
 * before the first original instruction (so a loop back to that instruction never re-enters); {@code INVOKESTATIC
 * exit()V} immediately before EVERY return of any kind; ONE catch-all range over the whole original body, appended
 * AFTER every original exception entry (theirs keep precedence, so a mod handler that catches Throwable runs first
 * and exit still runs exactly once), whose handler {@code exit; athrow} sits after the original code with ONE hand
 * frame {@code full_frame locals=[] stack=[Throwable]}. No local is added; max_locals stays, max_stack becomes
 * {@code max(original, 1)} (the key load needs one slot; a method that declared 0 is legal input). The access flags
 * (synchronized included) pass through. The key argument is the entry's ship-bearing argument as declared; the
 * holder derives the ship from it (a ship, a stats object's entity, a weapon's ship, a beam's source) and pushes
 * null for anything else, the own ship included, so the twins then make the real calls.
 *
 * <p>The holder's law this edit relies on: {@code exit()} NEVER throws (an exit before a return sits inside the
 * new range, so a throwing exit would run the handler's exit too). The holder's names are string constants
 * ({@link #HOLDER}); this unit never links the holder.
 *
 * <p>FAIL-OPEN per class: every refusal returns a {@link Result} with no bytes and one reason, and the caller loads
 * the ORIGINAL bytes: {@link #REASON_CLASS_VERSION} (major below 50: no StackMapTable for the hand frame),
 * {@link #REASON_WINDOW_REPEATED} (the input already names the holder), {@link #REASON_ILLEGAL_NAMES} (a name the
 * class-file format forbids: returned bytes get the strict format check, so a class that loads today could turn
 * into a fatal load), {@link #REASON_SELF_CHECK} (the independent re-read missed), {@code error:<SimpleName>}.
 * The SELF-CHECK re-reads the output: the input pool an untouched prefix of the output's, the appended entries only
 * the holder's and the hand frame's; the class header, the method set, every method's access flags and every
 * unwrapped method's code byte-identical; in each wrapped method the key load and the enter as the first two
 * instructions, one exit immediately before each return (as many as the input has), the handler last
 * ({@code exit; athrow}, the hand frame exact), the catch-all the LAST exception entry over [first original
 * instruction, handler), and, with the inserted instructions removed, the instruction stream, branch targets,
 * frames, exception entries, lines and locals equal to the input's; max_stack {@code max(in, 1)}, max_locals equal.
 *
 * <p>The census-only damage family:
 */
public final class ReplicaEntryWrapRewriter {

    /** The client holder the wrap calls (internal name; resolved by name at run time, never linked here). */
    public static final String HOLDER = "cc/starsector/client/replica/ReplicaPilotCall";
    public static final String ENTER = "enter";
    public static final String ENTER_DESC = "(Ljava/lang/Object;)V";
    public static final String EXIT = "exit";
    public static final String EXIT_DESC = "()V";

    public static final String REASON_CLASS_VERSION = "class_version";
    public static final String REASON_WINDOW_REPEATED = "window_repeated";
    public static final String REASON_ILLEGAL_NAMES = "illegal_names";
    public static final String REASON_SELF_CHECK = "wrap_self_check";
    public static final String REASON_ERROR_PREFIX = "error:";

    private static final String C = "com/fs/starfarer/api/combat/";
    private static final String L = C + "listeners/";
    private static final String THROWABLE = "java/lang/Throwable";
    private static final String DAMAGE_MOD_DESC = "(Ljava/lang/Object;L" + C + "CombatEntityAPI;L" + C
            + "DamageAPI;Lorg/lwjgl/util/vector/Vector2f;Z)Ljava/lang/String;";
    private static final String WEAPON_ADVANCE_DESC = "(FL" + C + "CombatEngineAPI;L" + C + "WeaponAPI;)V";

    /**
     * One entry row: the API interface the method implements (census label), its name + descriptor (the match key:
     * an instance method with code), the local slot of its ship-bearing argument, and its family.
     */
    public record Entry(String family, String itf, String name, String desc, int keySlot) {
        public String key() {
            return name + desc;
        }
    }

    /**
     * The armed entry table (the rows {@link #wrap} wraps): the per-ship rows the engine calls from inside a ship's
     * own update (hullmod per-frame, system apply, weapon per-frame and on-fire, beam per-frame). The system's
     * {@code unapply} and every on-hit callback are NOT rows: the engine also calls them outside a ship's update.
     */
    public static final List<Entry> ENTRIES = List.of(
            new Entry("hullmod", C + "HullModEffect", "advanceInCombat", "(L" + C + "ShipAPI;F)V", 1),
            new Entry("system", "com/fs/starfarer/api/plugins/ShipSystemStatsScript", "apply", "(L" + C
                    + "MutableShipStatsAPI;Ljava/lang/String;Lcom/fs/starfarer/api/plugins/ShipSystemStatsScript$State;F)V",
                    1),
            new Entry("weapon", C + "EveryFrameWeaponEffectPlugin", "advance", WEAPON_ADVANCE_DESC, 3),
            new Entry("weapon", C + "EveryFrameWeaponEffectPluginWithAdvanceAfter", "advanceAfter",
                    WEAPON_ADVANCE_DESC, 3),
            new Entry("beam", C + "BeamEffectPlugin", "advance", "(FL" + C + "CombatEngineAPI;L" + C + "BeamAPI;)V", 3),
            new Entry("weapon", C + "OnFireEffectPlugin", "onFire", "(L" + C + "DamagingProjectileAPI;L" + C
                    + "WeaponAPI;L" + C + "CombatEngineAPI;)V", 2));

    /**
     * The damage-listener rows: counted by the census ({@link #declaredOf}), NEVER wrapped ({@link #entriesOf} and
     * {@link #wrap} read {@link #ENTRIES} only). A listener runs on the caller's stack wherever applyDamage runs,
     * collision passes included, and the serve opens no damage-listener window yet, so a client window here would
     * answer where the serve's never does (the client's windows must stay a subset of the serve's). The key slots
     * record each row's ship-bearing argument as declared (the target for taken, applied and hull damage, the dealing
     * object for dealt); nothing reads them until the family arms.
     *
     *
     */
    public static final List<Entry> CENSUS_ONLY = List.of(
            new Entry("damage", L + "DamageTakenModifier", "modifyDamageTaken", DAMAGE_MOD_DESC, 2),
            new Entry("damage", L + "DamageDealtModifier", "modifyDamageDealt", DAMAGE_MOD_DESC, 1),
            new Entry("damage", L + "DamageListener", "reportDamageApplied", "(Ljava/lang/Object;L" + C
                    + "CombatEntityAPI;L" + L + "ApplyDamageResultAPI;)V", 2),
            new Entry("damage", L + "HullDamageAboutToBeTakenListener", "notifyAboutToTakeHullDamage",
                    "(Ljava/lang/Object;L" + C + "ShipAPI;Lorg/lwjgl/util/vector/Vector2f;F)Z", 2));

    /** The system's unapply (counted by the census, never wrapped). */
    public static final String UNAPPLY_KEY = "unapply(L" + C + "MutableShipStatsAPI;Ljava/lang/String;)V";

    /** The armed rows by key (the wrap's match). */
    private static final Map<String, Entry> BY_KEY = new HashMap<>();
    /** The armed and the census-only rows by key (the census's match). */
    private static final Map<String, Entry> DECLARED_BY_KEY = new HashMap<>();

    static {
        for (Entry e : ENTRIES) {
            BY_KEY.putIfAbsent(e.key(), e);
            DECLARED_BY_KEY.putIfAbsent(e.key(), e);
        }
        for (Entry e : CENSUS_ONLY) {
            DECLARED_BY_KEY.putIfAbsent(e.key(), e);
        }
    }

    private ReplicaEntryWrapRewriter() {
    }

    /** One class's outcome: the wrapped bytes (applied) or the one reason it keeps its input bytes; never both. */
    public static final class Result {
        private final byte[] patched;
        private final String reason;
        private final String detail;
        private final List<Entry> wrapped;

        Result(byte[] patched, String reason, String detail, List<Entry> wrapped) {
            this.patched = patched;
            this.reason = reason;
            this.detail = PilotShipClassifier.bounded(detail);
            this.wrapped = List.copyOf(wrapped);
        }

        public boolean applied() {
            return patched != null;
        }

        /** The wrapped class; {@code null} on a refusal. */
        public byte[] patched() {
            return patched;
        }

        /** {@code null} when applied; else a {@code REASON_*} value or {@code error:<SimpleName>}. */
        public String reason() {
            return reason;
        }

        /** The first self-check miss or the offending name kind, printable ASCII, at most 64 chars; else empty. */
        public String detail() {
            return detail;
        }

        /** The armed entry rows the class declares (wrapped when applied), in class order. */
        public List<Entry> rows() {
            return wrapped;
        }
    }

    /**
     * The wrap: {@code null} when the class declares no armed entry row (nothing to do, no parse beyond the method
     * table; a class whose only rows are {@link #CENSUS_ONLY} included); else a never-throwing {@link Result}.
     */
    public static Result wrap(byte[] bytes) {
        List<Entry> rows;
        try {
            rows = entriesOf(bytes);
        } catch (Throwable t) {
            return new Result(null, REASON_ERROR_PREFIX + simpleName(t), "", List.of());
        }
        if (rows.isEmpty()) {
            return null;
        }
        try {
            int major = new ClassReader(bytes).readUnsignedShort(6);
            if (major < Opcodes.V1_6) {
                return new Result(null, REASON_CLASS_VERSION, "major=" + major, rows);
            }
            if (namesHolder(bytes)) {
                return new Result(null, REASON_WINDOW_REPEATED, "", rows);
            }
            String illegal = illegalName(bytes);
            if (illegal != null) {
                return new Result(null, REASON_ILLEGAL_NAMES, illegal, rows);
            }
            Map<String, Entry> keys = new LinkedHashMap<>();
            for (Entry e : rows) {
                keys.put(e.key(), e);
            }
            byte[] out = edit(bytes, keys);
            String miss = selfCheck(bytes, out, keys);
            return miss == null ? new Result(out, null, "", rows) : new Result(null, REASON_SELF_CHECK, miss, rows);
        } catch (Throwable t) {
            return new Result(null, REASON_ERROR_PREFIX + simpleName(t), "", rows);
        }
    }

    /**
     * The armed entry rows a class declares (the rows {@link #wrap} wraps): instance methods with code whose name +
     * descriptor is an {@link #ENTRIES} row, class order.
     */
    public static List<Entry> entriesOf(byte[] bytes) {
        return rowsOf(bytes, BY_KEY);
    }

    /**
     * Every entry row a class declares, armed ({@link #ENTRIES}) and census-only ({@link #CENSUS_ONLY}), class
     * order: the census's view; the wrap never reads it.
     *
     *
     */
    public static List<Entry> declaredOf(byte[] bytes) {
        return rowsOf(bytes, DECLARED_BY_KEY);
    }

    private static List<Entry> rowsOf(byte[] bytes, Map<String, Entry> table) {
        List<Entry> out = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exc) {
                Entry e = table.get(name + desc);
                if (e != null && (access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
                    out.add(e);
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return out;
    }

    /** True when an instance method with code named {@link #UNAPPLY_KEY} is declared (census only). */
    public static boolean declaresUnapply(byte[] bytes) {
        boolean[] hit = {false};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exc) {
                hit[0] |= UNAPPLY_KEY.equals(name + desc)
                        && (access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return hit[0];
    }

    // ---------------------------------------------------------------- the legal-names law

    /**
     * The first name the class-file format forbids, as {@code <kind>:<index>} (never the name itself: it is mod
     * text), or {@code null}: a declared field or method name, every NameAndType name (a method name when its
     * descriptor starts with {@code (}), and every Class entry's internal name. Unqualified names hold none of
     * {@code . ; [ /}; a method name holds no {@code < >} unless it is {@code <init>} / {@code <clinit>}; a class
     * name is non-empty segments split by {@code /} holding none of {@code . ; [}, or an array descriptor.
     */
    public static String illegalName(byte[] b) {
        int[] off = PilotShipClassifier.poolOffsets(b);
        if (off == null) {
            return "pool_unreadable";
        }
        for (int k = 1; k < off.length - 1; k++) {
            int p = off[k];
            if (p < 0) {
                continue;
            }
            int tag = b[p] & 0xff;
            if (tag == 12) {
                String name = utf8At(b, off, PilotShipClassifier.u2(b, p + 1));
                String desc = utf8At(b, off, PilotShipClassifier.u2(b, p + 3));
                if (name == null || desc == null) {
                    return "nat_unreadable:" + k;
                }
                if (!(desc.startsWith("(") ? legalMethodName(name) : legalUnqualified(name))) {
                    return "nat_name:" + k;
                }
            } else if (tag == 7) {
                String cls = utf8At(b, off, PilotShipClassifier.u2(b, p + 1));
                if (cls == null || !legalClassName(cls)) {
                    return "class_name:" + k;
                }
            }
        }
        String[] miss = {null};
        new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM9) {
            private int fields;
            private int methods;

            @Override
            public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                if (miss[0] == null && !legalUnqualified(name)) {
                    miss[0] = "field_name:" + fields;
                }
                fields++;
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exc) {
                if (miss[0] == null && !legalMethodName(name)) {
                    miss[0] = "method_name:" + methods;
                }
                methods++;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return miss[0];
    }

    static boolean legalUnqualified(String n) {
        if (n.isEmpty()) {
            return false;
        }
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c == '.' || c == ';' || c == '[' || c == '/') {
                return false;
            }
        }
        return true;
    }

    static boolean legalMethodName(String n) {
        if (n.equals("<init>") || n.equals("<clinit>")) {
            return true;
        }
        return legalUnqualified(n) && n.indexOf('<') < 0 && n.indexOf('>') < 0;
    }

    static boolean legalClassName(String n) {
        if (n.startsWith("[")) {
            int i = 0;
            while (i < n.length() && n.charAt(i) == '[') {
                i++;
            }
            if (i >= n.length() || i > 255) {
                return false;
            }
            char t = n.charAt(i);
            if (t == 'L') {
                return n.endsWith(";") && i + 1 < n.length() - 1 && legalClassName(n.substring(i + 1, n.length() - 1));
            }
            return i == n.length() - 1 && "BCDFIJSZ".indexOf(t) >= 0;
        }
        if (n.isEmpty()) {
            return false;
        }
        for (String seg : n.split("/", -1)) {
            if (seg.isEmpty() || seg.indexOf('.') >= 0 || seg.indexOf(';') >= 0 || seg.indexOf('[') >= 0) {
                return false;
            }
        }
        return true;
    }

    private static String utf8At(byte[] b, int[] off, int idx) {
        if (idx <= 0 || idx >= off.length - 1 || off[idx] < 0 || (b[off[idx]] & 0xff) != 1) {
            return null;
        }
        int p = off[idx];
        return new String(b, p + 3, PilotShipClassifier.u2(b, p + 1), StandardCharsets.UTF_8);
    }

    /** True when a Utf8 in the pool equals the holder's internal name. */
    static boolean namesHolder(byte[] b) {
        int[] off = PilotShipClassifier.poolOffsets(b);
        if (off == null) {
            return false;
        }
        byte[] want = HOLDER.getBytes(StandardCharsets.US_ASCII);
        for (int k = 1; k < off.length - 1; k++) {
            if (off[k] > 0 && (b[off[k]] & 0xff) == 1 && PilotShipClassifier.utf8Equals(b, off[k], want)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- the edit

    /** The wrap of every method in {@code keys}; package-private for the contract's hand-broken outputs. */
    static byte[] edit(byte[] in, Map<String, Entry> keys) {
        ClassReader cr = new ClassReader(in);
        ClassWriter cw = new ClassWriter(cr, 0);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exc) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, exc);
                Entry e = keys.get(name + desc);
                // every other method keeps the writer's own visitor, so ASM copies its bytes verbatim
                return e != null && (access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0
                        ? new Wrap(mv, e.keySlot()) : mv;
            }
        }, 0);
        return cw.toByteArray();
    }

    private static boolean isReturn(int op) {
        return op >= Opcodes.IRETURN && op <= Opcodes.RETURN;
    }

    private static final class Wrap extends MethodVisitor {
        private final int keySlot;
        private final Label start = new Label();
        private final Label end = new Label();
        private final Label handler = new Label();

        Wrap(MethodVisitor mv, int keySlot) {
            super(Opcodes.ASM9, mv);
            this.keySlot = keySlot;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            super.visitVarInsn(Opcodes.ALOAD, keySlot);
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
            super.visitFrame(Opcodes.F_FULL, 0, new Object[0], 1, new Object[] {THROWABLE});
            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOLDER, EXIT, EXIT_DESC, false);
            super.visitInsn(Opcodes.ATHROW);
            super.visitMaxs(Math.max(maxStack, 1), maxLocals);
        }
    }

    // ---------------------------------------------------------------- the self-check (an independent re-read)

    /** {@code null} = the output holds; else a bounded token naming the first miss. */
    static String selfCheck(byte[] in, byte[] out, Map<String, Entry> keys) {
        String pool = poolDelta(in, out);
        if (pool != null) {
            return pool;
        }
        Shape si = shape(in);
        Shape so = shape(out);
        if (!Arrays.equals(si.header, so.header)) {
            return "header_changed";
        }
        if (!si.access.equals(so.access)) {
            return "methods_changed";
        }
        for (Map.Entry<String, byte[]> m : si.code.entrySet()) {
            if (keys.containsKey(m.getKey())) {
                continue;
            }
            if (!Arrays.equals(m.getValue(), so.code.get(m.getKey()))) {
                return "code_changed:" + m.getKey();
            }
        }
        Map<String, Body> bi = bodies(in, keys.keySet());
        Map<String, Body> bo = bodies(out, keys.keySet());
        for (Map.Entry<String, Entry> k : keys.entrySet()) {
            Body a = bi.get(k.getKey());
            Body b = bo.get(k.getKey());
            if (a == null || b == null) {
                return "body_missing:" + k.getKey();
            }
            String miss = compare(a, b, k.getValue().keySlot());
            if (miss != null) {
                return miss + ":" + k.getValue().name();
            }
        }
        return null;
    }

    /** One wrapped method compared with its input: the inserted instructions stripped, the rest position-mapped. */
    private static String compare(Body in, Body out, int keySlot) {
        int n = out.insns.size();
        if (n < 4 || !out.insns.get(0).text.equals("var " + Opcodes.ALOAD + " " + keySlot)
                || !out.insns.get(1).text.equals(call(HOLDER, ENTER, ENTER_DESC))) {
            return "enter_not_first";
        }
        String exit = call(HOLDER, EXIT, EXIT_DESC);
        if (!out.insns.get(n - 2).text.equals(exit) || !out.insns.get(n - 1).text.equals("insn " + Opcodes.ATHROW)) {
            return "handler_not_last";
        }
        boolean[] strip = new boolean[n];
        strip[0] = strip[1] = strip[n - 2] = strip[n - 1] = true;
        int exits = 0;
        for (int i = 2; i < n - 2; i++) {
            Insn x = out.insns.get(i);
            if (x.text.equals(exit) && i + 1 < n - 2 && isReturn(out.insns.get(i + 1).op)) {
                strip[i] = true;
                exits++;
            } else if (x.text.contains(HOLDER)) {
                return "holder_ref_extra";
            }
        }
        int returns = 0;
        for (Insn x : in.insns) {
            returns += isReturn(x.op) ? 1 : 0;
            if (x.text.contains(HOLDER)) {
                return "holder_ref_input";
            }
        }
        if (exits != returns) {
            return "exits=" + exits + "/" + returns;
        }
        int[] map = new int[n + 1];
        int kept = 0;
        for (int i = 0; i < n; i++) {
            map[i] = kept;
            kept += strip[i] ? 0 : 1;
        }
        map[n] = kept;
        if (kept != in.insns.size()) {
            return "insn_count";
        }
        int handlerPos = n - 2;
        // the stream with targets mapped
        List<String> a = new ArrayList<>();
        List<String> b = new ArrayList<>();
        for (Insn x : in.insns) {
            a.add(x.text + targets(in, null, x.labels));
        }
        for (int i = 0; i < n; i++) {
            if (!strip[i]) {
                b.add(out.insns.get(i).text + targets(out, map, out.insns.get(i).labels));
            }
        }
        if (!a.equals(b)) {
            return "stream_changed";
        }
        // exception entries: the input's first and unchanged, the catch-all last
        if (out.tcs.size() != in.tcs.size() + 1) {
            return "try_count";
        }
        for (int i = 0; i < in.tcs.size(); i++) {
            Tc x = in.tcs.get(i);
            Tc y = out.tcs.get(i);
            if (!(pos(in, null, x.start) + "," + pos(in, null, x.end) + "," + pos(in, null, x.handler) + x.type)
                    .equals(pos(out, map, y.start) + "," + pos(out, map, y.end) + "," + pos(out, map, y.handler)
                            + y.type)) {
                return "try_changed:" + i;
            }
        }
        Tc last = out.tcs.get(out.tcs.size() - 1);
        if (last.type != null || out.labelPos.get(last.start) == null || out.labelPos.get(last.start) != 2
                || out.labelPos.get(last.end) == null || out.labelPos.get(last.end) != handlerPos
                || out.labelPos.get(last.handler) == null || out.labelPos.get(last.handler) != handlerPos) {
            return "catch_all_shape";
        }
        // frames: the input's, position-mapped, then the hand frame at the handler
        if (out.frames.size() != in.frames.size() + 1) {
            return "frame_count";
        }
        for (int i = 0; i < in.frames.size(); i++) {
            Frame x = in.frames.get(i);
            Frame y = out.frames.get(i);
            if (x.pos != map[y.pos] || !frameText(in, null, x).equals(frameText(out, map, y))) {
                return "frame_changed:" + i;
            }
        }
        Frame hand = out.frames.get(out.frames.size() - 1);
        if (hand.pos != handlerPos || !hand.text.equals(Opcodes.F_FULL + " 0[] 1[" + THROWABLE + "]")) {
            return "hand_frame";
        }
        // lines and locals, position-mapped
        if (!debug(in, null).equals(debug(out, map))) {
            return "debug_changed";
        }
        if (out.maxStack != Math.max(in.maxStack, 1) || out.maxLocals != in.maxLocals) {
            return "maxs=" + out.maxStack + "/" + out.maxLocals;
        }
        return null;
    }

    private static String targets(Body b, int[] map, Label[] labels) {
        if (labels.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" ->");
        for (Label l : labels) {
            sb.append(' ').append(pos(b, map, l));
        }
        return sb.toString();
    }

    private static int pos(Body b, int[] map, Label l) {
        Integer p = l == null ? null : b.labelPos.get(l);
        if (p == null) {
            return -1;
        }
        return map == null ? p : map[p];
    }

    private static String frameText(Body b, int[] map, Frame f) {
        StringBuilder sb = new StringBuilder(f.text);
        for (Label l : f.labels) {
            sb.append(" U").append(pos(b, map, l));
        }
        return sb.toString();
    }

    private static List<String> debug(Body b, int[] map) {
        List<String> out = new ArrayList<>();
        for (Object[] line : b.lines) {
            out.add("line " + line[0] + "@" + pos(b, map, (Label) line[1]));
        }
        for (Object[] lv : b.locals) {
            out.add("local " + lv[0] + "@" + pos(b, map, (Label) lv[1]) + "-" + pos(b, map, (Label) lv[2]));
        }
        return out;
    }

    private static String call(String owner, String name, String desc) {
        return "call " + Opcodes.INVOKESTATIC + " " + owner + "." + name + desc + " false";
    }

    private record Insn(int op, String text, Label[] labels) {
    }

    private record Frame(int pos, String text, Label[] labels) {
    }

    private record Tc(Label start, Label end, Label handler, String type) {
    }

    /** One method as read: instructions (symbolic), label positions, frames, exception entries, lines, locals. */
    private static final class Body extends MethodVisitor {
        final List<Insn> insns = new ArrayList<>();
        final Map<Label, Integer> labelPos = new IdentityHashMap<>();
        final List<Frame> frames = new ArrayList<>();
        final List<Tc> tcs = new ArrayList<>();
        final List<Object[]> lines = new ArrayList<>();
        final List<Object[]> locals = new ArrayList<>();
        int maxStack = -1;
        int maxLocals = -1;

        Body() {
            super(Opcodes.ASM9);
        }

        private void add(int op, String text, Label... labels) {
            insns.add(new Insn(op, text, labels));
        }

        @Override
        public void visitLabel(Label label) {
            labelPos.put(label, insns.size());
        }

        @Override
        public void visitFrame(int type, int nLocal, Object[] local, int nStack, Object[] stack) {
            List<Label> ls = new ArrayList<>();
            String text = type + " " + nLocal + types(nLocal, local, ls) + " " + nStack + types(nStack, stack, ls);
            frames.add(new Frame(insns.size(), text, ls.toArray(new Label[0])));
        }

        private static String types(int n, Object[] arr, List<Label> ls) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < n && arr != null && i < arr.length; i++) {
                Object o = arr[i];
                if (i > 0) {
                    sb.append(',');
                }
                if (o instanceof Label l) {
                    ls.add(l);
                    sb.append("U");
                } else {
                    sb.append(o);
                }
            }
            return sb.append(']').toString();
        }

        @Override
        public void visitInsn(int opcode) {
            add(opcode, "insn " + opcode);
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            add(opcode, "int " + opcode + " " + operand);
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            add(opcode, "var " + opcode + " " + var);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            add(opcode, "type " + opcode + " " + type);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
            add(opcode, "field " + opcode + " " + owner + "." + name + " " + desc);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            add(opcode, "call " + opcode + " " + owner + "." + name + desc + " " + itf);
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... args) {
            add(Opcodes.INVOKEDYNAMIC, "indy " + name + desc + " " + bsm + " " + Arrays.deepToString(args));
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            add(opcode, "jump " + opcode, label);
        }

        @Override
        public void visitLdcInsn(Object value) {
            add(Opcodes.LDC, "ldc " + (value == null ? "null" : value.getClass().getSimpleName() + ":" + value));
        }

        @Override
        public void visitIincInsn(int var, int increment) {
            add(Opcodes.IINC, "iinc " + var + " " + increment);
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            Label[] all = new Label[labels.length + 1];
            all[0] = dflt;
            System.arraycopy(labels, 0, all, 1, labels.length);
            add(Opcodes.TABLESWITCH, "tswitch " + min + " " + max, all);
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            Label[] all = new Label[labels.length + 1];
            all[0] = dflt;
            System.arraycopy(labels, 0, all, 1, labels.length);
            add(Opcodes.LOOKUPSWITCH, "lswitch " + Arrays.toString(keys), all);
        }

        @Override
        public void visitMultiANewArrayInsn(String desc, int dims) {
            add(Opcodes.MULTIANEWARRAY, "multi " + desc + " " + dims);
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            tcs.add(new Tc(start, end, handler, type));
        }

        @Override
        public void visitLineNumber(int line, Label start) {
            lines.add(new Object[] {line, start});
        }

        @Override
        public void visitLocalVariable(String name, String desc, String sig, Label start, Label end, int index) {
            locals.add(new Object[] {name + " " + desc + " " + sig + " " + index, start, end});
        }

        @Override
        public void visitMaxs(int ms, int ml) {
            maxStack = ms;
            maxLocals = ml;
        }
    }

    private static Map<String, Body> bodies(byte[] b, Set<String> keys) {
        Map<String, Body> out = new HashMap<>();
        new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exc) {
                if (!keys.contains(name + desc)) {
                    return null;
                }
                Body body = new Body();
                out.put(name + desc, body);
                return body;
            }
        }, 0);
        return out;
    }

    /** The class header (access, names, interfaces), each method's access, each method's raw code and maxs. */
    private static final class Shape {
        byte[] header;
        final Map<String, Integer> access = new LinkedHashMap<>();
        final Map<String, byte[]> code = new HashMap<>();
    }

    private static Shape shape(byte[] b) {
        ClassReader cr = new ClassReader(b);
        Shape s = new Shape();
        char[] buf = new char[cr.getMaxStringLength() + 1];
        int p = cr.header + 6;
        p += 2 + 2 * cr.readUnsignedShort(p);
        s.header = Arrays.copyOfRange(b, cr.header, p);
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
            String key = cr.readUTF8(p + 2, buf) + cr.readUTF8(p + 4, buf);
            s.access.put(key, cr.readUnsignedShort(p));
            int attrs = cr.readUnsignedShort(p + 6);
            p += 8;
            for (int a = 0; a < attrs; a++) {
                int len = cr.readInt(p + 2);
                if ("Code".equals(cr.readUTF8(p, buf))) {
                    s.code.put(key, Arrays.copyOfRange(b, p + 6, p + 6 + len));
                }
                p += 6 + len;
            }
        }
        return s;
    }

    /** The input pool an untouched prefix of the output's; the appended entries only the wrap's own, once each. */
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
        if (!Arrays.equals(in, pi[1], pi[ni], out, po[1], po[1] + (pi[ni] - pi[1]))) {
            return "pool_changed";
        }
        Set<String> utf8s = Set.of(HOLDER, ENTER, ENTER_DESC, EXIT, EXIT_DESC, THROWABLE, "StackMapTable");
        Set<String> seen = new HashSet<>();
        for (int k = ni; k < no; k++) {
            int p = po[k];
            if (p < 0) {
                return "pool_added:" + k;
            }
            int tag = out[p] & 0xff;
            String what = switch (tag) {
                case 1 -> {
                    String s = utf8At(out, po, k);
                    yield s != null && utf8s.contains(s) ? "u:" + s : null;
                }
                case 7 -> {
                    String s = utf8At(out, po, PilotShipClassifier.u2(out, p + 1));
                    yield HOLDER.equals(s) || THROWABLE.equals(s) ? "c:" + s : null;
                }
                case 12 -> {
                    String nm = utf8At(out, po, PilotShipClassifier.u2(out, p + 1));
                    String d = utf8At(out, po, PilotShipClassifier.u2(out, p + 3));
                    yield (ENTER.equals(nm) && ENTER_DESC.equals(d)) || (EXIT.equals(nm) && EXIT_DESC.equals(d))
                            ? "nat:" + nm : null;
                }
                case 10 -> {
                    int cls = PilotShipClassifier.u2(out, p + 1);
                    int nat = PilotShipClassifier.u2(out, p + 3);
                    String owner = cls > 0 && cls < no && po[cls] > 0 && (out[po[cls]] & 0xff) == 7
                            ? utf8At(out, po, PilotShipClassifier.u2(out, po[cls] + 1)) : null;
                    String nm = nat > 0 && nat < no && po[nat] > 0 && (out[po[nat]] & 0xff) == 12
                            ? utf8At(out, po, PilotShipClassifier.u2(out, po[nat] + 1)) : null;
                    yield HOLDER.equals(owner) && (ENTER.equals(nm) || EXIT.equals(nm)) ? "m:" + nm : null;
                }
                default -> null;
            };
            if (what == null || !seen.add(what)) {
                return "pool_added:" + k + " tag=" + tag;
            }
        }
        return null;
    }

    static String simpleName(Throwable t) {
        String n = t.getClass().getSimpleName();
        return n.isEmpty() ? "Throwable" : n;
    }
}
