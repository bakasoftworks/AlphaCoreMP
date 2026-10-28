package cc.starsector.server.premain;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The keyed {@code java.util.Random.<init>()V} rewrite — a faithful
 * product port of the scout agent's keyed mode (census mode stays
 * scout-only). The ctor's whole body is {@code this(seedUniquifier() ^ System.nanoTime())}:
 *
 * <ul>
 *   <li>replace its single {@code invokestatic System.nanoTime()J} with {@code ldc2_w masterSeed};</li>
 *   <li>insert {@code invokestatic CcRngKeyed.deriveKeyedSeed(J)J} right after its single
 *       {@code lxor} — discarding the chain-derived value for an ORDER-INDEPENDENT seed keyed by
 *       master seed + caller class + per-caller construction index.</li>
 * </ul>
 *
 * <p>Registered one-shot by {@code CcTransformPremain} (add → retransform → remove); the premain
 * verifies {@link #lastRunSites}/{@link #lastRunTaps} == 1 afterward and REFUSES the launch
 * otherwise (a transformer cannot refuse by throwing — the JVM swallows transform errors). The
 * injected holder reference resolves via the bootstrap append the premain performs BEFORE arming.
 */
final class RngKeyedCtorPinApplier implements ClassFileTransformer, Opcodes {

    private final long seed;
    /** nanoTime sites replaced by the most recent transform invocation; -1 = transform threw. */
    volatile int lastRunSites;
    /** lxor sites tapped by the most recent transform invocation; -1 = transform threw. */
    volatile int lastRunTaps;

    RngKeyedCtorPinApplier(long seed) {
        this.seed = seed;
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!"java/util/Random".equals(className)) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            // Flags 0 (no COMPUTE_MAXS/FRAMES): invokestatic ()J -> ldc2_w is stack-neutral (both
            // push 2 slots, pop 0), and the keyed tap's invokestatic (J)J inserted after lxor pops
            // 2 / pushes 2 in straight-line code, so the original maxs and frames stay valid.
            ClassWriter writer = new ClassWriter(reader, 0);
            int[] sites = new int[] { 0 };
            int[] taps = new int[] { 0 };
            ClassVisitor visitor = new ClassVisitor(ASM8, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                        String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!"<init>".equals(name) || !"()V".equals(descriptor)) {
                        return mv;
                    }
                    return new MethodVisitor(ASM8, mv) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String mname, String mdesc,
                                boolean isInterface) {
                            if (opcode == INVOKESTATIC && "java/lang/System".equals(owner)
                                    && "nanoTime".equals(mname) && "()J".equals(mdesc)) {
                                super.visitLdcInsn(Long.valueOf(seed));
                                sites[0]++;
                                return;
                            }
                            super.visitMethodInsn(opcode, owner, mname, mdesc, isInterface);
                        }

                        @Override
                        public void visitInsn(int opcode) {
                            super.visitInsn(opcode);
                            // The ctor body's single lxor leaves the chain-derived seed
                            // (seedUniquifier() ^ masterSeed) on the stack; the tap discards it
                            // and returns the caller-keyed order-independent seed.
                            if (opcode == LXOR) {
                                super.visitMethodInsn(INVOKESTATIC, "cc/starsector/server/rngpin/CcRngKeyed",
                                        "deriveKeyedSeed", "(J)J", false);
                                taps[0]++;
                            }
                        }
                    };
                }
            };
            reader.accept(visitor, 0);
            lastRunSites = sites[0];
            lastRunTaps = taps[0];
            return (sites[0] == 1 && taps[0] == 1) ? writer.toByteArray() : null;
        } catch (Throwable t) {
            lastRunSites = -1; // surfaced by the premain's fail-closed check (the JVM swallows transform errors)
            lastRunTaps = -1;
            return null;
        }
    }
}
