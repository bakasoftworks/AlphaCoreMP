package cc.starsector.server.premain;

import cc.starsector.server.transform.MethodBytecodeFingerprint;
import cc.starsector.server.transform.TransformRegistry;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The transform-registry verifier's checks 10/11: proves
 * {@link RegistryNoopApplier}'s GUARDED kind ({@code RETURN_VOID_UNLESS_LAST_ARG_NULL}) on class
 * majors the registered targets never exercise. Why: {@code lwjgl.jar} is major 49 (no StackMapTable),
 * so the serve's real rows never touch the applier's {@code classMajor >= 50} join-frame branch — a
 * future guarded row on a modern class would meet it untested. Synthetic classes are assembled here
 * with the premain's shaded ASM (the game JRE has no javac), run through the REAL applier (the launch
 * gate's fingerprint check included), defined in a throwaway loader (the JVM verifies every class it
 * defines) and invoked on both guard branches.
 *
 * <p>Modes: {@code pass-cases} — majors 52 and 49, each with a loop whose head is bytecode 0 (a frame
 * at 0 collides with the guard's join frame), a try/catch opening at 0, a straight-line body, a long
 * before the last parameter and an array last parameter; exit 0 only if every witness field reads as
 * expected. {@code refuse-full-frame} / {@code refuse-init} — the two documented limits must REFUSE:
 * the applier halts the JVM with exit 2 after a named {@code REFUSING (load-time error)} line
 * (a non-F_SAME frame at bytecode 0; a guarded constructor). Lives in the premain unit because the
 * applier is package-private and the shaded ASM is on this unit's classpath; a few KB in the agent jar.
 */
public final class GuardPrefixerProofMain implements Opcodes {

    private static final String G = "cc/starsector/server/premain/proof/G";

    public static void main(String[] args) throws Exception {
        String mode = args.length == 1 ? args[0] : "";
        switch (mode) {
            case "pass-cases" -> passCases();
            case "refuse-full-frame" -> refuse(fullFrameAt0(), "H", "f", "(ILjava/lang/Object;)V");
            case "refuse-init" -> refuse(initTarget(), "K", "<init>", "(Ljava/lang/Object;)V");
            default -> {
                System.err.println("usage: GuardPrefixerProofMain pass-cases|refuse-full-frame|refuse-init");
                System.exit(64);
            }
        }
    }

    private static void passCases() throws Exception {
        int fails = loopTryStraight(V1_8, "major 52 (StackMapTable, join F_SAME inserted)")
                + loopTryStraight(V1_5, "major 49 (type-inference verifier, no frame inserted)");
        if (fails != 0) {
            System.err.println("FAIL GuardPrefixerProofMain (pass-cases): " + fails + " witness(es) off");
            System.exit(1);
        }
        System.out.println("GuardPrefixerProofMain OK (pass-cases): guarded rows on synthetic majors 52 + 49 — "
                + "loop-at-0, try/catch-at-0, straight-line, long-before-last, array-last: "
                + "non-null returned at once, null ran the original body");
    }

    private static int loopTryStraight(int version, String label) throws Exception {
        byte[] in = synthetic(version);
        byte[] out = transform(in, G, new String[][] {
                { "m", "(IJLjava/lang/Object;)V" }, { "t", "(JLjava/lang/String;)V" }, { "p", "([I)V" } });
        Class<?> g = define(G.replace('/', '.'), out);
        Object inst = g.getDeclaredConstructor().newInstance();
        Method m = g.getMethod("m", int.class, long.class, Object.class);
        Method t = g.getMethod("t", long.class, String.class);
        Method p = g.getMethod("p", int[].class);
        int fails = 0;
        // non-null last arg => the guard returns before the body touches any witness
        m.invoke(null, 4, 3L, new Object());
        t.invoke(inst, 1L, "x");
        p.invoke(null, new int[1]);
        fails += expect(g, label, "ranM", 0) + expect(g, label, "sumM", 0) + expect(g, label, "ranT", 0)
                + expect(g, label, "caught", 0) + expect(g, label, "ranP", 0);
        // null last arg => the original body runs: the loop (4 x 3), the try+throw+catch, the straight line
        m.invoke(null, 4, 3L, null);
        t.invoke(inst, 1L, null);
        t.invoke(inst, 0L, null);
        p.invoke(null, (Object) null);
        fails += expect(g, label, "ranM", 1) + expect(g, label, "sumM", 12) + expect(g, label, "ranT", 2)
                + expect(g, label, "caught", 1) + expect(g, label, "ranP", 1);
        System.out.println("  " + label + ": " + (fails == 0 ? "PASS" : "FAIL " + fails));
        return fails;
    }

    private static int expect(Class<?> g, String label, String field, long want) throws Exception {
        long got = ((Number) g.getField(field).get(null)).longValue();
        if (got != want) {
            System.err.println("  MISMATCH " + label + " " + field + " want=" + want + " got=" + got);
            return 1;
        }
        return 0;
    }

    private static void refuse(byte[] in, String internalName, String name, String descriptor) {
        // the real applier halts the JVM (exit 2) from its catch-all; reaching the line below = NOT refused
        transform(in, internalName, new String[][] { { name, descriptor } });
        System.err.println("FAIL GuardPrefixerProofMain: " + internalName + "." + name + descriptor
                + " was transformed instead of REFUSED");
        System.exit(1);
    }

    private static byte[] transform(byte[] in, String internalName, String[][] rows) {
        List<TransformRegistry.TargetMethod> methods = new ArrayList<>();
        for (String[] r : rows) {
            methods.add(new TransformRegistry.TargetMethod(r[0], r[1],
                    MethodBytecodeFingerprint.sha256OfMethodCode(in, r[0], r[1]),
                    TransformRegistry.NoopKind.RETURN_VOID_UNLESS_LAST_ARG_NULL));
        }
        TransformRegistry.Entry entry = new TransformRegistry.Entry("PROOF", TransformRegistry.Kind.METHOD_NOOP,
                internalName, "synthetic", methods, "i", "n/a (in-process proof)", "guard-prefixer proof", "n/a");
        byte[] out = new RegistryNoopApplier(List.of(entry)).transform(null, null, internalName, null, null, in);
        if (out == null) {
            throw new IllegalStateException("applier returned null for " + internalName);
        }
        return out;
    }

    private static Class<?> define(String name, byte[] bytes) {
        return new ClassLoader(null) {
            Class<?> go() {
                return defineClass(name, bytes, 0, bytes.length);
            }
        }.go();
    }

    /** G: static int ranM, ranT, ranP, caught; static long sumM; m / t / p as documented on the class. */
    private static byte[] synthetic(int version) {
        // frames only exist from major 50; a major-49 input carries none (COMPUTE_MAXS), like lwjgl.jar
        ClassWriter cw = new ClassWriter(version >= V1_6 ? ClassWriter.COMPUTE_FRAMES : ClassWriter.COMPUTE_MAXS);
        cw.visit(version, ACC_PUBLIC | ACC_SUPER, G, null, "java/lang/Object", null);
        for (String f : new String[] { "ranM", "ranT", "ranP", "caught" }) {
            cw.visitField(ACC_PUBLIC | ACC_STATIC, f, "I", null, null).visitEnd();
        }
        cw.visitField(ACC_PUBLIC | ACC_STATIC, "sumM", "J", null, null).visitEnd();

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        // static void m(int a, long b, Object last) { while (a > 0) { sumM += b; a--; } ranM++; } — head AT 0
        mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "m", "(IJLjava/lang/Object;)V", null, null);
        mv.visitCode();
        Label head = new Label();
        Label end = new Label();
        mv.visitLabel(head);
        mv.visitVarInsn(ILOAD, 0);
        mv.visitJumpInsn(IFLE, end);
        mv.visitFieldInsn(GETSTATIC, G, "sumM", "J");
        mv.visitVarInsn(LLOAD, 1);
        mv.visitInsn(LADD);
        mv.visitFieldInsn(PUTSTATIC, G, "sumM", "J");
        mv.visitIincInsn(0, -1);
        mv.visitJumpInsn(GOTO, head);
        mv.visitLabel(end);
        bump(mv, "ranM");
        mv.visitInsn(RETURN);
        mv.visitMaxs(4, 4);
        mv.visitEnd();

        // void t(long b, String last) { try { ranT++; if (b != 0) throw new RuntimeException(); }
        //                                catch (RuntimeException e) { caught++; } } — try opens AT 0
        mv = cw.visitMethod(ACC_PUBLIC, "t", "(JLjava/lang/String;)V", null, null);
        mv.visitCode();
        Label start = new Label();
        Label stop = new Label();
        Label handler = new Label();
        Label done = new Label();
        Label noThrow = new Label();
        mv.visitTryCatchBlock(start, stop, handler, "java/lang/RuntimeException");
        mv.visitLabel(start);
        bump(mv, "ranT");
        mv.visitVarInsn(LLOAD, 1);
        mv.visitInsn(LCONST_0);
        mv.visitInsn(LCMP);
        mv.visitJumpInsn(IFEQ, noThrow);
        mv.visitTypeInsn(NEW, "java/lang/RuntimeException");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "()V", false);
        mv.visitInsn(ATHROW);
        mv.visitLabel(noThrow);
        mv.visitLabel(stop);
        mv.visitJumpInsn(GOTO, done);
        mv.visitLabel(handler);
        mv.visitInsn(POP);
        bump(mv, "caught");
        mv.visitLabel(done);
        mv.visitInsn(RETURN);
        mv.visitMaxs(4, 4);
        mv.visitEnd();

        // static void p(int[] last) { ranP++; }
        mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "p", "([I)V", null, null);
        mv.visitCode();
        bump(mv, "ranP");
        mv.visitInsn(RETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void bump(MethodVisitor mv, String field) {
        mv.visitFieldInsn(GETSTATIC, G, field, "I");
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IADD);
        mv.visitFieldInsn(PUTSTATIC, G, field, "I");
    }

    /** H (major 52): static void f(int, Object) whose bytecode 0 carries an explicit F_FULL frame (a loop head). */
    private static byte[] fullFrameAt0() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC | ACC_SUPER, "H", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "f", "(ILjava/lang/Object;)V", null, null);
        mv.visitCode();
        Label top = new Label();
        mv.visitLabel(top);
        mv.visitFrame(F_FULL, 2, new Object[] { INTEGER, "java/lang/Object" }, 0, null);
        mv.visitIincInsn(0, -1);
        mv.visitVarInsn(ILOAD, 0);
        mv.visitJumpInsn(IFGT, top);
        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 2);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** K (major 52): a constructor with a reference last parameter — a registry row naming it must be refused. */
    private static byte[] initTarget() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V1_8, ACC_PUBLIC | ACC_SUPER, "K", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 2);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
