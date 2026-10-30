package cc.starsector.compat.magic;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
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
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The MagicLib pilot seam's class-load rewrite: MagicLib's {@code MagicSubsystem} bytes in, patched bytes or ONE
 * fixed disarm reason out. Bytes only: ASM core (reader API ASM9), no server class, no instrumentation, so the
 * serve's premain and a client-side agent can share this one source.
 *
 * <p>WHY a load-time edit: MagicLib decides a subsystem's activation on every JVM inside
 * {@code advanceInternal(F)V}, where the human branch reads the local keyboard and the AI branch calls the mod's
 * {@code shouldActivateAI(F)Z}; both feed ONE boolean into ONE activation gate. A seat hull on the serve (and its
 * replica on a client) is never the local player, so it always took the AI branch and the pilot's key never
 * reached MagicLib. The edit (the guarded branch, "P3") puts ONE static call at the AI label, which every fork
 * jump lands on: {@code decide(this, this.ship)} returns -1 to run the ORIGINAL AI call as its literal original
 * instructions, or 0 / 1 as the key value, stored into the same local the human branch stores and jumping to the
 * same join. The unarmed path is therefore the untouched original, and an armed hull skips the mod's AI exactly as
 * MagicLib's own human branch does on the pilot's machine. One hand frame ({@code same_locals_1_stack_item [int]})
 * sits at the run-the-original label; maxs stay the original's. The documented fallback, never coded here, is P1:
 * a value wrap after the original AI call (no frame), deciding the same but leaving the AI's side effects running.
 *
 * <p>Fail-OPEN: a class whose internal name is not the target returns {@code null} before any parse; for the target,
 * any window miss, hook-probe miss, self-check miss or Throwable returns a disarm {@link Result} (never a throw), so
 * the caller loads the ORIGINAL class and the pilot keeps the contract route. Engine and JDK targets stay
 * fail-closed elsewhere; a third-party mod's class never refuses a launch.
 *
 * <p>The gate, in order: (1) the token pass canonicalises {@code advanceInternal(F)V} (instructions as
 * {@code OP owner.name desc}, labels by identity; no frames, offsets, pool indices or line numbers) and the fork
 * window ({@link #WINDOW}, 23 tokens) must occur EXACTLY once, with the class-wide call counts of
 * {@code getPlayerShip} / {@code isUIAutopilotOn} / {@code isKeyDown()Z} / {@code shouldActivateAI(F)Z} each 1;
 * (2) an optional input allowlist; (3) the {@link HookProbe}; (4) the edit; (5) the output self-check: exactly one
 * {@code decide} call, in {@code advanceInternal} only, every original call count unchanged, every other method's
 * {@code code[]} byte-identical, the constant-pool Class delta exactly the hook class, and the patched
 * {@code advanceInternal}'s max_stack at least {@link #P3_STACK_PEAK} (the edit keeps the original maxs).
 *
 * <p>Provenance: the window, the edit and the self-check are measured verify-clean on Java 17 and 25 for MagicLib
 * 1.4.5 through 1.5.8-dev14; {@code --golden} re-prints the per-input output hashes.
 *
 *
 */
public final class MagicSeamRewriter {

    /** The one class this rewrite touches (JVM internal name); every other name returns {@code null} unparsed. */
    public static final String TARGET = "org/magiclib/subsystems/MagicSubsystem";
    /** The hook class (internal name) the edit calls; it ships in the client mod jar, beside MagicLib's loader. */
    public static final String HOOK = "cc/starsector/client/magic/CcMagicSeam";
    /** The hook's classfile resource path, as the probe looks it up. */
    public static final String HOOK_RESOURCE = HOOK + ".class";
    /** The hook method the edit calls. */
    public static final String DECIDE = "decide";
    /** {@code decide(MagicSubsystem, ShipAPI)}: -1 = run the original AI call, 0 / 1 = the key value. */
    public static final String DECIDE_DESC = "(L" + TARGET + ";Lcom/fs/starfarer/api/combat/ShipAPI;)I";
    /** The hook's {@code public static final int ABI} this rewrite is written against. */
    public static final int HOOK_ABI = 1;

    /** Disarm: no {@code advanceInternal(F)V}, or the fork window is not in it. */
    public static final String REASON_WINDOW_ABSENT = "window_absent";
    /** Disarm: the fork window occurs more than once (the edit point is ambiguous). */
    public static final String REASON_WINDOW_REPEATED = "window_repeated";
    /** Disarm: a class-wide count of one of the four fork calls is not 1. */
    public static final String REASON_CALL_COUNT = "call_count";
    /** Disarm: the defining loader's OWN resources do not hold the hook class. */
    public static final String REASON_HOOK_MISSING = "hook_missing";
    /** Disarm: the parent chain holds a hook copy, which could not see MagicLib (a link error every frame). */
    public static final String REASON_HOOK_SHADOWED = "hook_shadowed";
    /** Disarm: the hook class does not declare the expected public {@code decide} or {@code ABI = 1}. */
    public static final String REASON_HOOK_ABI = "hook_abi";
    /** Disarm: the patched output failed the self-check. */
    public static final String REASON_SELF_CHECK = "self_check";
    /** Disarm: the input class is not in the caller's allowlist of verified MagicLib builds. */
    public static final String REASON_NOT_ALLOWLISTED = "not_allowlisted";
    /** Disarm prefix for any Throwable, followed by its simple class name. */
    public static final String REASON_ERROR_PREFIX = "error:";

    /** The receipt word of an applied rewrite ({@code applied:<16 hex of the window sha256>}). */
    public static final String RECEIPT_APPLIED = "applied";
    /** The receipt word of a disarm ({@code disarmed:<reason>}). */
    public static final String RECEIPT_DISARMED = "disarmed";

    /** A hook class larger than this is not the hook (bounded read). */
    static final int MAX_HOOK_BYTES = 64 * 1024;

    /**
     * The inserted block's operand-stack peak (ALOAD 0, ALOAD 0 / GETFIELD ship, then the {@code decide} result plus
     * its DUP and ICONST_M1 = 3 slots over the empty stack at the AI label). The writer copies the original maxs
     * verbatim (no COMPUTE_MAXS), so a build whose {@code advanceInternal} declares less would overflow its frame:
     * a VerifyError on a verifying JVM, undefined behaviour under {@code -noverify}. The self-check disarms instead.
     *
     *
     */
    public static final int P3_STACK_PEAK = 3;

    private static final String SHIP_DESC = "Lcom/fs/starfarer/api/combat/ShipAPI;";
    private static final String ENGINE = "com/fs/starfarer/api/combat/CombatEngineAPI";
    private static final String GCE = "INVOKESTATIC com/fs/starfarer/api/Global.getCombatEngine ()L" + ENGINE + ";";
    private static final String C_PLAYER = ENGINE + ".getPlayerShip ()" + SHIP_DESC;
    private static final String C_AUTOPILOT = ENGINE + ".isUIAutopilotOn ()Z";
    private static final String C_KEY = TARGET + ".isKeyDown ()Z";
    private static final String C_AI = TARGET + ".shouldActivateAI (F)Z";
    private static final String M_NAME = "advanceInternal";
    private static final String M_DESC = "(F)V";
    private static final String[] COUNTED_CALLS = {C_PLAYER, C_AUTOPILOT, C_KEY, C_AI};

    /**
     * The fork window in canonical tokens: {token text, label variable or null}. A label variable binds by
     * identity on first use and must match the same label after; {@code La} (the AI label) and {@code Lj} (the
     * join) must differ. Token 17 = the AI {@code ALOAD 0} (the P3 insert point), 19 = the AI call, 15 = the
     * human branch's {@code GOTO} to the join.
     */
    static final String[][] WINDOW = {
            {"ICONST_0", null},
            {"ISTORE 4", null},
            {GCE, null},
            {"INVOKEINTERFACE " + C_PLAYER, null},
            {"ALOAD 0", null},
            {"GETFIELD " + TARGET + ".ship " + SHIP_DESC, null},
            {"IF_ACMPNE", "La"},
            {GCE, null},
            {"INVOKEINTERFACE " + C_AUTOPILOT, null},
            {"IFEQ", "La"},
            {"ALOAD 0", null},
            {"INVOKEVIRTUAL " + C_KEY, null},
            {"IFEQ", "Lj"},
            {"ICONST_1", null},
            {"ISTORE 4", null},
            {"GOTO", "Lj"},
            {"LABEL", "La"},
            {"ALOAD 0", null},
            {"FLOAD 1", null},
            {"INVOKEVIRTUAL " + C_AI, null},
            {"ISTORE 4", null},
            {"LABEL", "Lj"},
            {"ILOAD 4", null},
    };
    private static final int W_GOTO = 15;
    private static final int W_AI = 17;
    private static final int W_CALL = 19;

    /** Opcode value to its ASM mnemonic (the canonical token's op word); built once from ASM's own table. */
    private static final Map<Integer, String> OPS = new HashMap<>();

    static {
        for (Field f : Opcodes.class.getFields()) {
            String n = f.getName();
            if (f.getType() != int.class || !Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            if (n.startsWith("ACC_") || n.startsWith("T_") || n.startsWith("H_") || n.startsWith("F_")
                    || n.startsWith("ASM") || n.startsWith("SOURCE_") || n.matches("V\\d.*") || n.startsWith("V_")) {
                continue;
            }
            try {
                OPS.putIfAbsent(f.getInt(null), n);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("ASM opcode table unreadable: " + n, e);
            }
        }
    }

    private MagicSeamRewriter() {
    }

    /**
     * Where the patched class's hook would resolve, answered by RESOURCE lookups only: a {@code loadClass} here
     * would define the hook during MagicSubsystem's own define and re-enter it. Returns {@code null} when the
     * hook is bindable, else {@link #REASON_HOOK_MISSING} / {@link #REASON_HOOK_SHADOWED} /
     * {@link #REASON_HOOK_ABI}. May throw; the rewrite turns a throw into an {@code error:} disarm.
     */
    @FunctionalInterface
    public interface HookProbe {
        String check() throws Exception;
    }

    /**
     * The production probe over the loader defining {@code MagicSubsystem}: its OWN {@code findResource} must
     * hold the hook (only a {@link URLClassLoader} exposes that lookup publicly; any other loader reads
     * missing), its parent chain's {@code getResource} must NOT (a parent copy could not see MagicLib), and the
     * hook's bytes must pass {@link #hookBytesReason}.
     */
    public static HookProbe probeOf(ClassLoader definingLoader) {
        return () -> {
            if (!(definingLoader instanceof URLClassLoader)) {
                return REASON_HOOK_MISSING;
            }
            URL own = ((URLClassLoader) definingLoader).findResource(HOOK_RESOURCE);
            if (own == null) {
                return REASON_HOOK_MISSING;
            }
            ClassLoader parent = definingLoader.getParent();
            URL shadow = parent != null ? parent.getResource(HOOK_RESOURCE)
                    : ClassLoader.getPlatformClassLoader().getResource(HOOK_RESOURCE);
            if (shadow != null) {
                return REASON_HOOK_SHADOWED;
            }
            return hookBytesReason(readBounded(own));
        };
    }

    /**
     * The hook's declared shape from its bytes alone (never loaded): a public class named {@link #HOOK} with a
     * {@code public static int decide(MagicSubsystem, ShipAPI)} and a {@code static final int ABI} whose
     * ConstantValue is {@link #HOOK_ABI}. {@code null} = bindable; else {@link #REASON_HOOK_ABI}
     * ({@link #REASON_HOOK_MISSING} for null bytes).
     */
    public static String hookBytesReason(byte[] hookBytes) {
        if (hookBytes == null) {
            return REASON_HOOK_MISSING;
        }
        if (hookBytes.length > MAX_HOOK_BYTES) {
            return REASON_HOOK_ABI;
        }
        boolean[] seen = new boolean[3]; // public class named HOOK, decide, ABI == 1
        new ClassReader(hookBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName,
                    String[] interfaces) {
                seen[0] = HOOK.equals(name) && (access & Opcodes.ACC_PUBLIC) != 0;
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature,
                    Object value) {
                int need = Opcodes.ACC_STATIC | Opcodes.ACC_FINAL;
                if ("ABI".equals(name) && "I".equals(descriptor) && (access & need) == need
                        && Integer.valueOf(HOOK_ABI).equals(value)) {
                    seen[2] = true;
                }
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                int need = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
                if (DECIDE.equals(name) && DECIDE_DESC.equals(descriptor) && (access & need) == need) {
                    seen[1] = true;
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return seen[0] && seen[1] && seen[2] ? null : REASON_HOOK_ABI;
    }

    /** One rewrite's outcome: patched bytes (applied) or a reason (disarmed); never both. */
    public static final class Result {
        private final byte[] patched;
        private final String reason;
        private final String windowSha;
        private final String detail;

        private Result(byte[] patched, String reason, String windowSha, String detail) {
            this.patched = patched;
            this.reason = reason;
            this.windowSha = windowSha;
            this.detail = detail;
        }

        /** True when {@link #patched} holds the rewritten class. */
        public boolean applied() {
            return patched != null;
        }

        /** The rewritten class bytes; {@code null} on a disarm (the caller loads the original). */
        public byte[] patched() {
            return patched;
        }

        /** One of the {@code REASON_*} values (or {@code error:<SimpleName>}); {@code null} when applied. */
        public String reason() {
            return reason;
        }

        /** The matched window's sha256 (64 lowercase hex); {@code ""} when the window never matched once. */
        public String windowSha() {
            return windowSha;
        }

        /** Why a self-check or window gate missed, in bounded tokens (method names, counts); {@code ""} if none. */
        public String detail() {
            return detail;
        }

        /** The receipt value: {@code applied:<16 hex>} or {@code disarmed:<reason>}. */
        public String receipt() {
            return applied() ? RECEIPT_APPLIED + ":" + windowSha.substring(0, 16) : RECEIPT_DISARMED + ":" + reason;
        }
    }

    /** {@link #rewrite(String, byte[], HookProbe, Set)} without an allowlist (the serve arms every matching build). */
    public static Result rewrite(String internalName, byte[] bytes, HookProbe probe) {
        return rewrite(internalName, bytes, probe, null);
    }

    /**
     * The rewrite. {@code null} for any {@code internalName} other than {@link #TARGET} (compared before any
     * parse); otherwise a {@link Result} that never throws. {@code allowedInputSha256} = lowercase hex sha256s of
     * the input classes a caller has verified (a client agent's set); {@code null} = no allowlist. A {@code null}
     * probe reads {@link #REASON_HOOK_MISSING}.
     */
    public static Result rewrite(String internalName, byte[] bytes, HookProbe probe, Set<String> allowedInputSha256) {
        if (!TARGET.equals(internalName)) {
            return null;
        }
        String windowSha = "";
        try {
            Scan s = scan(bytes);
            String gate = windowGate(s);
            windowSha = s.windowSha;
            if (gate != null) {
                return disarm(gate, windowSha, s.detail);
            }
            if (allowedInputSha256 != null && !allowedInputSha256.contains(sha256(bytes))) {
                return disarm(REASON_NOT_ALLOWLISTED, windowSha, "");
            }
            String hook = probe == null ? REASON_HOOK_MISSING : probe.check();
            if (hook != null) {
                return disarm(hook, windowSha, "");
            }
            int ai = s.toks.get(s.windowStart + W_AI).insnIndex;
            int call = s.toks.get(s.windowStart + W_CALL).insnIndex;
            int jump = s.toks.get(s.windowStart + W_GOTO).insnIndex;
            int[] inserted = {0};
            byte[] out = applyP3(bytes, ai, call, jump, inserted);
            if (inserted[0] != 1) {
                return disarm(REASON_SELF_CHECK, windowSha, "inserted=" + inserted[0]);
            }
            String miss = selfCheck(bytes, s.calls, out);
            if (miss != null) {
                return disarm(REASON_SELF_CHECK, windowSha, miss);
            }
            return new Result(out, null, windowSha, "");
        } catch (Throwable t) {
            return disarm(REASON_ERROR_PREFIX + simpleName(t), windowSha, "");
        }
    }

    /**
     * The window gate alone, for a sweep: {@code window_ok} or the reason the rewrite would disarm with before
     * any probe ({@code window_absent | window_repeated | call_count | error:<SimpleName>}). Ungated by name.
     */
    public static String windowVerdict(byte[] bytes) {
        try {
            String gate = windowGate(scan(bytes));
            return gate == null ? "window_ok" : gate;
        } catch (Throwable t) {
            return REASON_ERROR_PREFIX + simpleName(t);
        }
    }

    private static Result disarm(String reason, String windowSha, String detail) {
        return new Result(null, reason, windowSha, detail);
    }

    // ---------------------------------------------------------------- the read-only token pass

    /** One canonical token: an instruction (with its label operand, if any) or a bound label ({@code insnIndex} -1). */
    private static final class Tok {
        final String text;
        final Label label;
        final int insnIndex;

        Tok(String text, Label label, int insnIndex) {
            this.text = text;
            this.label = label;
            this.insnIndex = insnIndex;
        }
    }

    private static final class Scan {
        final List<Tok> toks = new ArrayList<>();
        /** Class-wide {@code owner.name desc} to its call count, every method. */
        final Map<String, Integer> calls = new TreeMap<>();
        int targetMethods;
        int windowStart = -1;
        String windowSha = "";
        String detail = "";
    }

    private static Scan scan(byte[] bytes) {
        Scan s = new Scan();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                boolean target = M_NAME.equals(name) && M_DESC.equals(descriptor);
                if (target) {
                    s.targetMethods++;
                }
                return new TokenVisitor(target ? s.toks : null, s.calls);
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return s;
    }

    /** {@code null} = the window matched exactly once and the counts hold (sets the window fields); else a reason. */
    private static String windowGate(Scan s) {
        if (s.targetMethods != 1) {
            s.detail = "advanceInternal_count=" + s.targetMethods;
            return REASON_WINDOW_ABSENT;
        }
        List<Integer> hits = findWindow(s.toks);
        if (hits.isEmpty()) {
            return REASON_WINDOW_ABSENT;
        }
        if (hits.size() > 1) {
            s.detail = "window_count=" + hits.size();
            return REASON_WINDOW_REPEATED;
        }
        s.windowStart = hits.get(0);
        s.windowSha = sha256(canon(s.toks, s.windowStart, s.windowStart + WINDOW.length)
                .getBytes(StandardCharsets.UTF_8));
        for (String c : COUNTED_CALLS) {
            int n = s.calls.getOrDefault(c, 0);
            if (n != 1) {
                s.detail = c + "=" + n;
                return REASON_CALL_COUNT;
            }
        }
        // belt for the edit's index arithmetic: the human GOTO sits right before the AI ALOAD 0, the call 2 after it
        int ai = s.toks.get(s.windowStart + W_AI).insnIndex;
        if (s.toks.get(s.windowStart + W_GOTO).insnIndex != ai - 1
                || s.toks.get(s.windowStart + W_CALL).insnIndex != ai + 2) {
            s.detail = "index_shape";
            return REASON_WINDOW_ABSENT;
        }
        return null;
    }

    private static final class TokenVisitor extends MethodVisitor {
        private final List<Tok> out;
        private final Map<String, Integer> calls;
        private int idx;

        TokenVisitor(List<Tok> out, Map<String, Integer> calls) {
            super(Opcodes.ASM9);
            this.out = out;
            this.calls = calls;
        }

        private void t(String s, Label l) {
            if (out != null) {
                out.add(new Tok(s, l, idx));
            }
            idx++;
        }

        @Override
        public void visitLabel(Label l) {
            if (out != null) {
                out.add(new Tok("LABEL", l, -1));
            }
        }

        @Override
        public void visitInsn(int o) {
            t(op(o), null);
        }

        @Override
        public void visitIntInsn(int o, int v) {
            t(op(o) + " " + v, null);
        }

        @Override
        public void visitVarInsn(int o, int v) {
            t(op(o) + " " + v, null);
        }

        @Override
        public void visitTypeInsn(int o, String type) {
            t(op(o) + " " + type, null);
        }

        @Override
        public void visitFieldInsn(int o, String owner, String name, String desc) {
            t(op(o) + " " + owner + "." + name + " " + desc, null);
        }

        @Override
        public void visitMethodInsn(int o, String owner, String name, String desc, boolean itf) {
            String k = owner + "." + name + " " + desc;
            calls.merge(k, 1, Integer::sum);
            t(op(o) + " " + k, null);
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... args) {
            t("INVOKEDYNAMIC " + name + " " + desc + " " + bsm, null);
        }

        @Override
        public void visitJumpInsn(int o, Label l) {
            t(op(o), l);
        }

        @Override
        public void visitLdcInsn(Object v) {
            t("LDC " + v.getClass().getSimpleName() + ":" + v, null);
        }

        @Override
        public void visitIincInsn(int v, int inc) {
            t("IINC " + v + " " + inc, null);
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            t("TABLESWITCH " + min + " " + max, null);
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            t("LOOKUPSWITCH " + Arrays.toString(keys), null);
        }

        @Override
        public void visitMultiANewArrayInsn(String desc, int dims) {
            t("MULTIANEWARRAY " + desc + " " + dims, null);
        }
    }

    private static String op(int o) {
        String s = OPS.get(o);
        return s == null ? "OP" + o : s;
    }

    /** Canonical text: one token per line, label operands by first-appearance ordinal (offset and pool-index free). */
    private static String canon(List<Tok> toks, int from, int to) {
        IdentityHashMap<Label, Integer> ids = new IdentityHashMap<>();
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            Tok k = toks.get(i);
            sb.append(k.text);
            if (k.label != null) {
                ids.putIfAbsent(k.label, ids.size());
                sb.append(" L").append(ids.get(k.label));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** The token positions where {@link #WINDOW} matches, label variables bound by identity. */
    private static List<Integer> findWindow(List<Tok> toks) {
        List<Integer> hits = new ArrayList<>();
        outer:
        for (int p = 0; p + WINDOW.length <= toks.size(); p++) {
            Map<String, Label> bind = new HashMap<>();
            for (int i = 0; i < WINDOW.length; i++) {
                Tok k = toks.get(p + i);
                if (!WINDOW[i][0].equals(k.text)) {
                    continue outer;
                }
                String v = WINDOW[i][1];
                if (v != null) {
                    Label b = bind.get(v);
                    if (b == null) {
                        if (bind.containsValue(k.label)) {
                            continue outer;
                        }
                        bind.put(v, k.label);
                    } else if (b != k.label) {
                        continue outer;
                    }
                }
            }
            hits.add(p);
        }
        return hits;
    }

    // ---------------------------------------------------------------- the P3 edit

    /**
     * The guarded branch at the AI label. {@code ClassWriter(reader, 0)}, never COMPUTE_FRAMES: its common-super
     * lookup would load classes through a loader that cannot see MagicLib; every untouched method is copied
     * verbatim, and the one hand frame keeps the original StackMapTable valid around it.
     */
    private static byte[] applyP3(byte[] in, int aiIdx, int callIdx, int gotoIdx, int[] inserted) {
        ClassReader cr = new ClassReader(in);
        ClassWriter cw = new ClassWriter(cr, 0);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!(M_NAME.equals(name) && M_DESC.equals(descriptor))) {
                    return mv;
                }
                return new P3Visitor(mv, aiIdx, callIdx, gotoIdx, inserted);
            }
        }, 0);
        return cw.toByteArray();
    }

    private static final class P3Visitor extends MethodVisitor {
        private final int aiIdx;
        private final int callIdx;
        private final int gotoIdx;
        private final int[] inserted;
        private int idx;
        private Label join;

        P3Visitor(MethodVisitor mv, int aiIdx, int callIdx, int gotoIdx, int[] inserted) {
            super(Opcodes.ASM9, mv);
            this.aiIdx = aiIdx;
            this.callIdx = callIdx;
            this.gotoIdx = gotoIdx;
            this.inserted = inserted;
        }

        /** Before the AI {@code ALOAD 0}: decide; -1 falls through to the original call, else store + join. */
        private void before(int opcode) {
            if (idx != aiIdx) {
                return;
            }
            if (join == null || opcode != Opcodes.ALOAD) {
                throw new IllegalStateException("p3_anchor");
            }
            Label run = new Label();
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitFieldInsn(Opcodes.GETFIELD, TARGET, "ship", SHIP_DESC);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, DECIDE, DECIDE_DESC, false);
            super.visitInsn(Opcodes.DUP);
            super.visitInsn(Opcodes.ICONST_M1);
            super.visitJumpInsn(Opcodes.IF_ICMPEQ, run);
            super.visitVarInsn(Opcodes.ISTORE, 4);
            super.visitJumpInsn(Opcodes.GOTO, join);
            super.visitLabel(run);
            super.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {Opcodes.INTEGER});
            super.visitInsn(Opcodes.POP);
            inserted[0]++;
        }

        @Override
        public void visitInsn(int o) {
            before(o);
            super.visitInsn(o);
            idx++;
        }

        @Override
        public void visitIntInsn(int o, int v) {
            before(o);
            super.visitIntInsn(o, v);
            idx++;
        }

        @Override
        public void visitVarInsn(int o, int v) {
            before(o);
            super.visitVarInsn(o, v);
            idx++;
        }

        @Override
        public void visitTypeInsn(int o, String type) {
            before(o);
            super.visitTypeInsn(o, type);
            idx++;
        }

        @Override
        public void visitFieldInsn(int o, String owner, String name, String desc) {
            before(o);
            super.visitFieldInsn(o, owner, name, desc);
            idx++;
        }

        @Override
        public void visitMethodInsn(int o, String owner, String name, String desc, boolean itf) {
            if (idx == callIdx && !(o == Opcodes.INVOKEVIRTUAL && TARGET.equals(owner)
                    && "shouldActivateAI".equals(name))) {
                throw new IllegalStateException("p3_call_anchor");
            }
            before(o);
            super.visitMethodInsn(o, owner, name, desc, itf);
            idx++;
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... args) {
            before(Opcodes.INVOKEDYNAMIC);
            super.visitInvokeDynamicInsn(name, desc, bsm, args);
            idx++;
        }

        @Override
        public void visitJumpInsn(int o, Label l) {
            if (idx == gotoIdx) {
                if (o != Opcodes.GOTO) {
                    throw new IllegalStateException("p3_join_anchor");
                }
                join = l;
            }
            before(o);
            super.visitJumpInsn(o, l);
            idx++;
        }

        @Override
        public void visitLdcInsn(Object v) {
            before(Opcodes.LDC);
            super.visitLdcInsn(v);
            idx++;
        }

        @Override
        public void visitIincInsn(int v, int inc) {
            before(Opcodes.IINC);
            super.visitIincInsn(v, inc);
            idx++;
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            before(Opcodes.TABLESWITCH);
            super.visitTableSwitchInsn(min, max, dflt, labels);
            idx++;
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            before(Opcodes.LOOKUPSWITCH);
            super.visitLookupSwitchInsn(dflt, keys, labels);
            idx++;
        }

        @Override
        public void visitMultiANewArrayInsn(String desc, int dims) {
            before(Opcodes.MULTIANEWARRAY);
            super.visitMultiANewArrayInsn(desc, dims);
            idx++;
        }
    }

    // ---------------------------------------------------------------- the output self-check (an independent re-read)

    /** {@code null} = the output holds; else a bounded description of the first miss. */
    private static String selfCheck(byte[] in, Map<String, Integer> inCalls, byte[] out) {
        List<String> hookSites = new ArrayList<>();
        Map<String, Integer> outCalls = new TreeMap<>();
        new ClassReader(out).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                String m = name + descriptor;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int o, String owner, String n, String d, boolean itf) {
                        if (HOOK.equals(owner)) {
                            hookSites.add(m + "->" + o + ":" + n + d + (itf ? ":itf" : ""));
                        } else {
                            outCalls.merge(owner + "." + n + " " + d, 1, Integer::sum);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        String want = M_NAME + M_DESC + "->" + Opcodes.INVOKESTATIC + ":" + DECIDE + DECIDE_DESC;
        if (hookSites.size() != 1 || !hookSites.get(0).equals(want)) {
            return "hook_sites=" + hookSites.size();
        }
        if (!inCalls.equals(outCalls)) {
            return "call_multiset_changed";
        }
        Raw ri = raw(in);
        Raw ro = raw(out);
        if (!ri.code.keySet().equals(ro.code.keySet())) {
            return "method_set_changed";
        }
        for (Map.Entry<String, byte[]> e : ri.code.entrySet()) {
            if (!e.getKey().equals(M_NAME + M_DESC) && !Arrays.equals(e.getValue(), ro.code.get(e.getKey()))) {
                return "code_changed:" + e.getKey();
            }
        }
        Set<String> added = new TreeSet<>(ro.classes);
        added.removeAll(ri.classes);
        Set<String> removed = new TreeSet<>(ri.classes);
        removed.removeAll(ro.classes);
        if (!removed.isEmpty() || !added.equals(Collections.singleton(HOOK))) {
            return "cp_class_delta=+" + added.size() + "-" + removed.size();
        }
        // The frame must hold the inserted block; read from the OUTPUT (the class
        // that loads), which carries the original's max_stack unchanged, so this is the original's declared value.
        Integer maxStack = ro.maxStack.get(M_NAME + M_DESC);
        if (maxStack == null || maxStack < P3_STACK_PEAK) {
            return "max_stack=" + maxStack;
        }
        return null;
    }

    /**
     * A raw classfile walk: each method's {@code code[]} and declared max_stack by name+descriptor, and the constant
     * pool's Class names.
     */
    private static final class Raw {
        final Map<String, byte[]> code = new TreeMap<>();
        final Map<String, Integer> maxStack = new TreeMap<>();
        final Set<String> classes = new TreeSet<>();
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
        int p = cr.header + 6;
        int interfaces = cr.readUnsignedShort(p);
        p += 2 + 2 * interfaces;
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
            int attrs = cr.readUnsignedShort(p + 6);
            p += 8;
            for (int a = 0; a < attrs; a++) {
                String an = cr.readUTF8(p, buf);
                int len = cr.readInt(p + 2);
                if ("Code".equals(an)) {
                    int codeLength = cr.readInt(p + 10);
                    r.maxStack.put(key, cr.readUnsignedShort(p + 6));
                    r.code.put(key, Arrays.copyOfRange(b, p + 14, p + 14 + codeLength));
                }
                p += 6 + len;
            }
        }
        return r;
    }

    // ---------------------------------------------------------------- helpers

    private static byte[] readBounded(URL url) throws IOException {
        URLConnection c = url.openConnection();
        c.setUseCaches(false); // no second cached handle on the mod jar the script loader already holds
        try (InputStream in = c.getInputStream()) {
            byte[] bytes = in.readNBytes(MAX_HOOK_BYTES + 1);
            return bytes.length > MAX_HOOK_BYTES ? new byte[MAX_HOOK_BYTES + 1] : bytes;
        }
    }

    /** Lowercase hex sha256 of {@code b}. */
    public static String sha256(byte[] b) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder sb = new StringBuilder(64);
            for (byte x : d) {
                sb.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** A Throwable's simple class name, never blank (an anonymous class reads its binary name's tail). */
    public static String simpleName(Throwable t) {
        String s = t.getClass().getSimpleName();
        if (s.isEmpty()) {
            String n = t.getClass().getName();
            s = n.substring(n.lastIndexOf('.') + 1);
        }
        return s;
    }

    /** A jar's FILE NAME as a log row may carry it: the last path segment, printable ASCII only, at most 64 chars. */
    public static String jarLabel(String pathOrName) {
        if (pathOrName == null || pathOrName.isEmpty()) {
            return "unknown";
        }
        String n = pathOrName;
        while (n.endsWith("/") || n.endsWith("\\")) {
            n = n.substring(0, n.length() - 1);
        }
        n = n.substring(Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\')) + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length() && sb.length() < 64; i++) {
            char c = n.charAt(i);
            sb.append(c > 0x20 && c < 0x7f ? c : '?');
        }
        return sb.length() == 0 ? "unknown" : sb.toString();
    }

    // ---------------------------------------------------------------- the re-find tool

    /**
     * {@code --print-window <jar>}: every class entry through the name-gated rewrite (the hook assumed present)
     * and the ungated window verdict; one row per class that is not a plain {@code window_absent} miss (with the
     * window's canonical tokens and sha), then a summary row. {@code --golden <jar>}: the patched output's sha256
     * per target class, so each MagicLib build's golden can be checked by hand. Exit 0 / 2 = done / bad arguments.
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 2 || !(args[0].equals("--print-window") || args[0].equals("--golden"))) {
            System.err.println("usage: MagicSeamRewriter --print-window|--golden <jar>");
            System.exit(2);
            return;
        }
        boolean golden = args[0].equals("--golden");
        String label = jarLabel(Path.of(args[1]).getFileName().toString());
        long t0 = System.nanoTime();
        int classes = 0;
        int gatedNonNull = 0;
        int absent = 0;
        int ok = 0;
        int other = 0;
        try (ZipFile zip = new ZipFile(args[1])) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory() || !e.getName().endsWith(".class")) {
                    continue;
                }
                byte[] bytes;
                try (InputStream in = zip.getInputStream(e)) {
                    bytes = in.readAllBytes();
                }
                classes++;
                String internal = e.getName().substring(0, e.getName().length() - ".class".length());
                Result gated = rewrite(internal, bytes, () -> null);
                if (golden) {
                    if (gated != null) {
                        System.out.println("golden jar=" + label + " class=" + internal + " in_sha256=" + sha256(bytes)
                                + " in_len=" + bytes.length + " verdict=" + gated.receipt()
                                + (gated.applied() ? " out_sha256=" + sha256(gated.patched()) + " out_len="
                                        + gated.patched().length : " detail=" + gated.detail())
                                + " window_sha256=" + (gated.windowSha().isEmpty() ? "none" : gated.windowSha()));
                    }
                    continue;
                }
                String verdict = windowVerdict(bytes);
                if (gated != null) {
                    gatedNonNull++;
                }
                if (verdict.equals(REASON_WINDOW_ABSENT)) {
                    absent++;
                } else if (verdict.equals("window_ok")) {
                    ok++;
                } else {
                    other++;
                }
                boolean named = internal.endsWith("/MagicSubsystem") || internal.equals("MagicSubsystem");
                if (gated == null && verdict.equals(REASON_WINDOW_ABSENT) && !named) {
                    continue;
                }
                System.out.println("class=" + internal + " gated=" + (gated == null ? "null" : gated.receipt())
                        + " ungated=" + verdict);
                printWindow(bytes);
            }
        }
        if (!golden) {
            System.out.println("summary jar=" + label + " classes=" + classes + " gated_nonnull=" + gatedNonNull
                    + " ungated window_absent=" + absent + " window_ok=" + ok + " other=" + other
                    + " ms=" + (System.nanoTime() - t0) / 1_000_000);
        }
    }

    private static void printWindow(byte[] bytes) {
        Scan s;
        try {
            s = scan(bytes);
        } catch (RuntimeException e) {
            System.out.println("  window unreadable: " + simpleName(e));
            return;
        }
        List<Integer> hits = findWindow(s.toks);
        System.out.println("  advanceInternal_count=" + s.targetMethods + " window_hits=" + hits.size());
        for (int start : hits) {
            String text = canon(s.toks, start, start + WINDOW.length);
            System.out.println("  window_sha256=" + sha256(text.getBytes(StandardCharsets.UTF_8))
                    + " ai_insn_index=" + s.toks.get(start + W_AI).insnIndex);
            for (String line : text.split("\n")) {
                System.out.println("    " + line);
            }
        }
        for (String c : COUNTED_CALLS) {
            System.out.println("  count " + c + " = " + s.calls.getOrDefault(c, 0));
        }
    }
}
