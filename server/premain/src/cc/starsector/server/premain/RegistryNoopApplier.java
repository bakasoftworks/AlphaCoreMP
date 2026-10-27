package cc.starsector.server.premain;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cc.starsector.server.transform.TransformLaunchGate;
import cc.starsector.server.transform.TransformRegistry;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * The registry-driven rewrite — a thin product extraction of the proven
 * {@code DisplayUpdateNoopTransformer} mechanism, with the method list and
 * faithful replacement bodies coming from the {@link TransformRegistry} entry instead of being
 * hardcoded. Replaces each registered method's BODY with its declared
 * {@link TransformRegistry.NoopKind} (bare {@code return} / {@code return false}), or — for the
 * GUARDED kind (HOOK-0048) — keeps the verified body behind a {@code lastArg != null ? return}
 * prefix ({@link GuardPrefixer}); rewrites
 * in-place from the same codesource at load time, so LWJGL2's sealed jar stays sealing-safe; never
 * touches any file on disk; injects no reference back into this package, so it is safe across the
 * isolated game {@code URLClassLoader}s the scouts use.
 *
 * <p>Fail-closed semantics differ deliberately from the trace agent (an RE observer that
 * fails-OPEN to unmodified bytes): this is the PRODUCT's launch gate, and an unverified or failed
 * rewrite must REFUSE, not limp into the exact mid-session failure the transform exists to prevent
 * ({@code Display.update()} throwing {@code IllegalStateException} deep inside resource loading).
 * A {@link ClassFileTransformer} cannot refuse by throwing — the JVM swallows transformer
 * exceptions and loads the original bytes — so on any transform-time boundary this applier prints
 * the named boundary and {@code Runtime.getRuntime().halt(2)}.
 *
 * <p>Transform-time re-verification: the premain's eager gate already verified the target jar's
 * bytes, but the bytes actually handed to the transformer are authoritative — they differ if
 * another agent transformed them first (e.g. the trace agent's {@code displayNoopOnly} stacked on
 * top of this premain) or a different jar shadows the verified one. Both are refusal boundaries.
 */
final class RegistryNoopApplier implements ClassFileTransformer, Opcodes {

    private final Map<String, TransformRegistry.Entry> byInternalName = new HashMap<>();

    RegistryNoopApplier(List<TransformRegistry.Entry> enabled) {
        for (TransformRegistry.Entry entry : enabled) {
            TransformRegistry.Entry prior = byInternalName.put(entry.targetClassInternalName(), entry);
            if (prior != null) {
                // A silent overwrite would leave the earlier entry's rows
                // un-rewritten while its survivor's n/n receipt reads green — refuse at construction (the
                // premain builds this during -javaagent processing, so the launch aborts, fail-closed).
                throw new IllegalStateException("[cc-transform] two enabled entries target "
                        + entry.targetClassInternalName() + " (" + prior.id() + " + " + entry.id()
                        + ") — one applier per class; merge the rows into one entry");
            }
        }
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        TransformRegistry.Entry entry = className == null ? null : byInternalName.get(className);
        if (entry == null) {
            return null;
        }
        try {
            List<String> refusals = TransformLaunchGate.verify(entry, classfileBuffer);
            if (!refusals.isEmpty()) {
                for (String boundary : refusals) {
                    System.err.println("[cc-transform] REFUSING (load-time bytes) : " + boundary);
                }
                System.err.println("[cc-transform] loaded bytes differ from the launch-gate-verified jar — "
                        + "another transformer rewrote them first (do not stack the trace agent's "
                        + "displayNoopOnly on this premain) or a different jar shadows the verified one");
                Runtime.getRuntime().halt(2);
            }

            ClassReader reader = new ClassReader(classfileBuffer);
            // COMPUTE_MAXS only (not COMPUTE_FRAMES): unchanged methods keep their original verified
            // frames; the replaced methods are empty (a single return) so they need no frames; a
            // GUARDED method keeps its original frames and gets one F_SAME at the guard's join point.
            ClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            int[] replaced = new int[] { 0 };
            int[] classMajor = new int[] { 0 };
            ClassVisitor visitor = new ClassVisitor(ASM8, writer) {
                @Override
                public void visit(int version, int access, String name, String signature, String superName,
                        String[] interfaces) {
                    classMajor[0] = version & 0xFFFF;
                    super.visit(version, access, name, signature, superName, interfaces);
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                        String[] exceptions) {
                    TransformRegistry.NoopKind noop = registeredNoop(entry, name, descriptor);
                    if (noop == null) {
                        return super.visitMethod(access, name, descriptor, signature, exceptions);
                    }
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    replaced[0]++;
                    if (noop == TransformRegistry.NoopKind.RETURN_VOID_UNLESS_LAST_ARG_NULL) {
                        // guard first, then the verified original instructions stream through
                        return new GuardPrefixer(mv, access, name, descriptor, classMajor[0]);
                    }
                    emitNoop(mv, noop);
                    return null; // body already written; skip copying the original instructions
                }
            };
            reader.accept(visitor, 0); // flags 0: pass original frames through unchanged for copied methods
            if (replaced[0] != entry.methods().size()) {
                System.err.println("[cc-transform] REFUSING (load-time rewrite): " + entry.id() + " rewrote "
                        + replaced[0] + "/" + entry.methods().size() + " registered methods on " + className);
                Runtime.getRuntime().halt(2);
            }
            boolean guarded = entry.methods().stream().anyMatch(
                    m -> m.noop() == TransformRegistry.NoopKind.RETURN_VOID_UNLESS_LAST_ARG_NULL);
            // the 'applied <id>: n/n' prefix is the receipt verify-transform-registry / measure-serve-working-set grep
            CcTransformPremain.log("applied " + entry.id() + ": " + replaced[0] + "/" + entry.methods().size()
                    + " method bodies -> " + (guarded ? "guarded (null-path body kept)" : "faithful no-op")
                    + " on " + className);
            return writer.toByteArray();
        } catch (Throwable t) {
            System.err.println("[cc-transform] REFUSING (load-time error): " + entry.id() + " on " + className
                    + " — " + t);
            Runtime.getRuntime().halt(2);
            throw new AssertionError("unreachable");
        }
    }

    private static TransformRegistry.NoopKind registeredNoop(TransformRegistry.Entry entry, String name,
            String descriptor) {
        for (TransformRegistry.TargetMethod method : entry.methods()) {
            if (method.name().equals(name) && method.descriptor().equals(descriptor)) {
                return method.noop();
            }
        }
        return null;
    }

    /** Emits the registered faithful replacement body. */
    private static void emitNoop(MethodVisitor mv, TransformRegistry.NoopKind noop) {
        mv.visitCode();
        if (noop == TransformRegistry.NoopKind.RETURN_FALSE) {
            mv.visitInsn(ICONST_0);
            mv.visitInsn(IRETURN);
        } else {
            mv.visitInsn(RETURN);
        }
        mv.visitMaxs(0, 0); // recomputed by COMPUTE_MAXS
        mv.visitEnd();
    }

    /**
     * {@link TransformRegistry.NoopKind#RETURN_VOID_UNLESS_LAST_ARG_NULL} (HOOK-0048):
     * prepends {@code if (lastArg != null) return;} to the verified original body, which then streams
     * through unchanged. Local-slot arithmetic: statics start at slot 0, instance methods at 1
     * ({@code this}); {@code long}/{@code double} parameters take two slots. Fail-closed shape
     * checks (a registry-authoring error, never a runtime one): the method must return {@code void}
     * and its last parameter must be a reference type — a miss throws into the caller's
     * REFUSING-halt. Frames: the guard's join label is a branch target, so classes of major
     * {@code >= 50} (Java 6+, StackMapTable-verified) get one {@code F_SAME} there (same locals as
     * method entry, empty stack — exactly the frame every original compressed frame that follows
     * was already relative to); older classes ({@code lwjgl.jar} is major 49) verify by type
     * inference and must not carry one (ASM refuses a compressed frame below major 50). Proven both
     * ways by {@code GuardPrefixerProofMain} (verify-transform-registry checks 10/11).
     *
     * <p>Known limits: (1) a major {@code >= 50} original whose bytecode 0 carries
     * a frame OTHER than {@code F_SAME} (a hand-assembled loop/handler at method start — javac never
     * emits one) collides with the join frame; ASM's {@code MethodWriter.visitFrame} skips a duplicate
     * {@code F_SAME} and throws on any other type, which {@link #visitFrame} re-throws NAMED into the
     * REFUSING-halt (fail-closed, never a corrupt StackMapTable). (2) Constructors/static initialisers
     * are refused here: a guard before {@code super(...)} would be a link-time VerifyError, not a
     * named refusal. (3) The launch gate's sha256 covers {@code code[]} only; a guarded row streams the
     * original exception table + StackMapTable through unhashed (GL11's uploads carry neither).
     */
    private static final class GuardPrefixer extends MethodVisitor {
        private final int lastArgSlot;
        private final boolean needsFrame;

        GuardPrefixer(MethodVisitor mv, int access, String name, String descriptor, int classMajor) {
            super(ASM8, mv);
            Type[] args = Type.getArgumentTypes(descriptor);
            if (name.startsWith("<")) {
                throw new IllegalStateException("RETURN_VOID_UNLESS_LAST_ARG_NULL cannot guard " + name
                        + descriptor + " (a guard before super()/static init is a link-time VerifyError)");
            }
            if (Type.getReturnType(descriptor).getSort() != Type.VOID) {
                throw new IllegalStateException("RETURN_VOID_UNLESS_LAST_ARG_NULL needs a void method, got "
                        + descriptor);
            }
            if (args.length == 0 || (args[args.length - 1].getSort() != Type.OBJECT
                    && args[args.length - 1].getSort() != Type.ARRAY)) {
                throw new IllegalStateException("RETURN_VOID_UNLESS_LAST_ARG_NULL needs a reference-typed "
                        + "last parameter, got " + descriptor);
            }
            int slot = (access & ACC_STATIC) != 0 ? 0 : 1;
            for (int i = 0; i < args.length - 1; i++) {
                slot += args[i].getSize();
            }
            this.lastArgSlot = slot;
            this.needsFrame = classMajor >= 50;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            Label original = new Label();
            super.visitVarInsn(ALOAD, lastArgSlot);
            super.visitJumpInsn(IFNULL, original);
            super.visitInsn(RETURN);
            super.visitLabel(original);
            if (needsFrame) {
                super.visitFrame(F_SAME, 0, null, 0, null);
            }
        }

        /** Names the one frame collision ASM reports message-less (javadoc limit (1)); everything else streams. */
        @Override
        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
            try {
                super.visitFrame(type, numLocal, local, numStack, stack);
            } catch (IllegalStateException e) {
                throw new IllegalStateException("RETURN_VOID_UNLESS_LAST_ARG_NULL: the original method carries a "
                        + "non-F_SAME frame (type " + type + ") at bytecode 0 that collides with the guard's join "
                        + "frame — a loop/handler at method start is unsupported for guarded rows; re-author", e);
            }
        }
    }

    /** Collapse merges to java/lang/Object so frame writing never resolves target-jar hierarchy. */
    private static final class SafeClassWriter extends ClassWriter {
        SafeClassWriter(ClassReader reader, int flags) {
            super(reader, flags);
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            return "java/lang/Object";
        }
    }
}
