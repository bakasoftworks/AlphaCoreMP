package cc.starsector.server.premain;

import java.lang.instrument.ClassFileTransformer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import cc.starsector.server.transform.TransformLaunchGate;
import cc.starsector.server.transform.TransformRegistry;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.TypePath;

/**
 * The {@link TransformRegistry.Kind#CALL_SITE_REDIRECT}
 * applier: inside ONE fingerprinted game method, swap exactly ONE call instruction for a static
 * call into the bootstrap-appended holder {@code cc.starsector.server.rngpin.CcRngRedirect}. WHY:
 * once the {@code Random} constructor is keyed, the engine still mints random-UUID ship ids (they
 * seed the AI's point-move randomness) and seeds the fighter landing slot from an identity hash —
 * the only non-determinism sources a pinned replay cannot avoid. Redirecting the two calls keeps
 * every other instruction, seed and draw the engine's own.
 *
 * <p><b>It injects a reference to the bootstrap-appended holder, so it arms ONLY after the keyed
 * constructor pin's append</b> ({@code CcTransformPremain} refuses a redirect without HOOK-0044 and
 * registers this transformer after {@code CcRngRedirect.init}); a game class linking the holder
 * before the append would resolve the app-classpath copy, which was never initialised.
 *
 * <p>Shape (the {@link RegistryNoopApplier} precedent, one applier per Kind): entries mapped by
 * target class, two on one class refused at construction; the LOAD-TIME bytes re-verified against
 * the registered fingerprint; the replacement has the original's stack effect, so the writer runs
 * with flags 0 (original maxs and frames kept) and every other method is copied byte-for-byte.
 * When the redirect carries an anchor fingerprint, a matching
 * call is HELD while the next {@code followingCount} counted instructions are tokenized; their
 * SHA-256 equal to the registered one confirms the site (the swap is emitted), anything else
 * refutes it (the original call is emitted, not a site). Fail-closed: a site count other than exactly 1, or an output whose call
 * counts are not exactly "one replacement in the target method, one original fewer there, every
 * other method unchanged", refuses — a transformer cannot refuse by throwing (the JVM swallows it
 * and loads the original bytes), so {@link #transform} prints the named boundary and
 * {@code halt(2)}s.
 */
final class CallSiteRedirectApplier implements ClassFileTransformer, Opcodes {

    /**
     * Zero-operand instructions whose anchor token is their mnemonic; any other zero-operand
     * instruction tokenizes as {@code OP<decimal opcode>}. A row added here re-tokenizes that
     * opcode, so every registered anchor fingerprint is re-measured after an edit.
     */
    private static final Map<Integer, String> ZERO_OPERAND = Map.of(
            IMUL, "IMUL", LMUL, "LMUL", IADD, "IADD", LADD, "LADD", IXOR, "IXOR", LXOR, "LXOR",
            I2L, "I2L", L2I, "L2I", DUP, "DUP", POP, "POP");

    private final Map<String, TransformRegistry.Entry> byInternalName = new HashMap<>();
    /** Sites swapped by the most recent rewrite per entry id (absent = that class has not loaded yet). */
    private final Map<String, Integer> lastSites = new ConcurrentHashMap<>();

    CallSiteRedirectApplier(List<TransformRegistry.Entry> enabled) {
        for (TransformRegistry.Entry entry : enabled) {
            if (entry.kind() != TransformRegistry.Kind.CALL_SITE_REDIRECT || entry.redirect() == null
                    || entry.methods().size() != 1) {
                throw new IllegalStateException("[cc-transform] " + entry.id() + " is not a one-method "
                        + TransformRegistry.Kind.CALL_SITE_REDIRECT + " entry");
            }
            // a malformed fingerprint would silently never match: refuse it here, naming the pair
            int count = entry.redirect().followingCount();
            String sha = String.valueOf(entry.redirect().followingSha256());
            if (count == 0 ? !sha.isEmpty() : count < 0 || !sha.matches("[0-9a-f]{64}")) {
                throw new IllegalStateException("[cc-transform] " + entry.id() + " anchor fingerprint malformed"
                        + " (count " + count + ", sha256 '" + sha + "'): count 0 with an empty sha256, or count > 0"
                        + " with 64 lowercase hex");
            }
            TransformRegistry.Entry prior = byInternalName.put(entry.targetClassInternalName(), entry);
            if (prior != null) {
                throw new IllegalStateException("[cc-transform] two enabled entries target "
                        + entry.targetClassInternalName() + " (" + prior.id() + " + " + entry.id()
                        + ") — one redirect applier entry per class");
            }
        }
    }

    /** Sites the most recent rewrite of {@code id}'s class swapped; -1 before that class loaded. */
    int sitesOf(String id) {
        Integer sites = lastSites.get(id);
        return sites == null ? -1 : sites;
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        TransformRegistry.Entry entry = className == null ? null : byInternalName.get(className);
        if (entry == null) {
            return null;
        }
        try {
            return rewrite(entry, classfileBuffer);
        } catch (Throwable t) {
            System.err.println("[cc-transform] REFUSING (load-time redirect): " + entry.id() + " on " + className
                    + " — " + (t.getMessage() != null ? t.getMessage() : t.toString()));
            Runtime.getRuntime().halt(2);
            throw new AssertionError("unreachable");
        }
    }

    /**
     * The rewrite without the halt: returns the redirected class bytes, or throws
     * {@link IllegalStateException} naming the boundary (load-time bytes drifted, site count not 1,
     * output call counts wrong). Records {@link #sitesOf} before any count refusal.
     */
    byte[] rewrite(TransformRegistry.Entry entry, byte[] classfileBuffer) {
        List<String> refusals = TransformLaunchGate.verify(entry, classfileBuffer);
        if (!refusals.isEmpty()) {
            throw new IllegalStateException("load-time bytes differ from the launch-gate-verified jar"
                    + " (another transformer rewrote them first, or a different jar shadows it): "
                    + refusals.get(0));
        }
        TransformRegistry.TargetMethod target = entry.methods().get(0);
        TransformRegistry.Redirect redirect = entry.redirect();
        ClassReader reader = new ClassReader(classfileBuffer);
        // Flags 0: the swapped call has the original's stack effect at the same position, so the
        // original maxs and StackMapTable stay valid; methods not wrapped below are copied verbatim.
        ClassWriter writer = new ClassWriter(reader, 0);
        int[] sites = new int[] { 0 };
        reader.accept(new ClassVisitor(ASM8, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!target.name().equals(name) || !target.descriptor().equals(descriptor)) {
                    return mv;
                }
                return new AnchoredRedirect(mv, redirect, sites);
            }
        }, 0);
        lastSites.put(entry.id(), sites[0]);
        String where = entry.targetClassName() + "." + target.name() + target.descriptor();
        if (sites[0] != 1) {
            throw new IllegalStateException("FAIL-CLOSED — expected exactly 1 " + fromLabel(redirect)
                    + " site (with its following anchor) in " + where + ", found " + sites[0]
                    + " — target shape changed; re-find: " + entry.refindRecipe());
        }
        byte[] out = writer.toByteArray();
        verifyOutput(entry, classfileBuffer, out);
        CcTransformPremain.log("applied " + entry.id() + ": 1 call site " + fromLabel(redirect) + " -> "
                + redirect.toOwner().substring(redirect.toOwner().lastIndexOf('/') + 1) + "." + redirect.toName()
                + " in " + where);
        System.setProperty("cc.transform.applied." + entry.id(), where);
        return out;
    }

    /**
     * Re-reads the OUTPUT (independent of the visitor's own counter): exactly one replacement call,
     * in the target method; one original call fewer there; every other method's original-call count
     * unchanged.
     */
    private static void verifyOutput(TransformRegistry.Entry entry, byte[] in, byte[] out) {
        TransformRegistry.Redirect r = entry.redirect();
        String targetKey = entry.methods().get(0).name() + entry.methods().get(0).descriptor();
        Map<String, Integer> toOut = callCounts(out, INVOKESTATIC, r.toOwner(), r.toName(), r.toDesc());
        Map<String, Integer> fromIn = callCounts(in, r.fromOpcode(), r.fromOwner(), r.fromName(), r.fromDesc());
        Map<String, Integer> fromOut = callCounts(out, r.fromOpcode(), r.fromOwner(), r.fromName(), r.fromDesc());
        int toTotal = toOut.values().stream().mapToInt(Integer::intValue).sum();
        boolean ok = toTotal == 1 && toOut.getOrDefault(targetKey, 0) == 1 && fromIn.keySet().equals(fromOut.keySet());
        for (Map.Entry<String, Integer> method : fromIn.entrySet()) {
            int expected = method.getKey().equals(targetKey) ? method.getValue() - 1 : method.getValue();
            ok &= fromOut.getOrDefault(method.getKey(), -1) == expected;
        }
        if (!ok) {
            throw new IllegalStateException("FAIL-CLOSED — rewritten " + entry.targetClassName()
                    + " call counts wrong: replacement per method " + nonZero(toOut) + ", original per method in "
                    + nonZero(fromIn) + " out " + nonZero(fromOut) + " (expected exactly one swap in " + targetKey + ")");
        }
    }

    /**
     * Per method WITH code ({@code name + descriptor} key, every such method present, zero
     * included): how many calls match {@code opcode owner.name desc} ({@code name} / {@code desc}
     * null = any). The output self-check's counter; also how a row enumerates a class's methods.
     */
    static Map<String, Integer> callCounts(byte[] classBytes, int opcode, String owner, String name, String desc) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        // ASM9: the census counts EVERY non-JDK class through this
        // visitor, and an ASM8 one throws on a sealed class's PermittedSubclasses attribute
        new ClassReader(classBytes).accept(new ClassVisitor(ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String methodName, String methodDesc, String signature,
                    String[] exceptions) {
                if ((access & (ACC_ABSTRACT | ACC_NATIVE)) != 0) {
                    return null;
                }
                String key = methodName + methodDesc;
                counts.put(key, 0);
                return new MethodVisitor(ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String o, String n, String d, boolean itf) {
                        if (op == opcode && o.equals(owner) && (name == null || n.equals(name))
                                && (desc == null || d.equals(desc))) {
                            counts.merge(key, 1, Integer::sum);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return counts;
    }

    private static Map<String, Integer> nonZero(Map<String, Integer> counts) {
        Map<String, Integer> kept = new LinkedHashMap<>();
        counts.forEach((k, v) -> {
            if (v != 0) {
                kept.put(k, v);
            }
        });
        return kept;
    }

    private static String fromLabel(TransformRegistry.Redirect r) {
        return r.fromOwner() + "." + r.fromName() + r.fromDesc();
    }

    private static String methodOpName(int opcode) {
        return switch (opcode) {
            case INVOKEVIRTUAL -> "INVOKEVIRTUAL";
            case INVOKESPECIAL -> "INVOKESPECIAL";
            case INVOKESTATIC -> "INVOKESTATIC";
            case INVOKEINTERFACE -> "INVOKEINTERFACE";
            default -> "OP" + opcode;
        };
    }

    private static String ldcToken(Object value) {
        String tag = value instanceof Integer ? "I" : value instanceof Long ? "J" : value instanceof Float ? "F"
                : value instanceof Double ? "D" : value instanceof String ? "S" : "?";
        return "LDC:" + tag + ":" + value;
    }

    /**
     * The target method's visitor: a call matching the
     * redirect is either swapped at once (count 0) or HELD while the following counted instructions
     * are buffered as canonical tokens; once {@code followingCount} are in, the SHA-256 of their
     * {@code "\n"}-joined UTF-8 bytes against {@code followingSha256} decides. Labels, frames and
     * line numbers between them are held in order, not counted. An instruction with no token form
     * (a local load, a jump, a field access, ...) refutes the candidate at once, as does the
     * method's end. A refuted candidate emits the held call as the ORIGINAL (not a site) and then
     * RE-VISITS every held event through this visitor, so a matching call among them may itself
     * start a new candidate: overlapping candidates are never skipped, which keeps the
     * exactly-one-site count honest.
     */
    private static final class AnchoredRedirect extends MethodVisitor {
        private final TransformRegistry.Redirect r;
        private final int[] sites;
        /** Events behind the held call, as emitted after a CONFIRMED swap (straight to the writer). */
        private final List<Runnable> held = new ArrayList<>();
        /** The same events, as re-visited through this visitor after a REFUTED candidate. */
        private final List<Runnable> revisit = new ArrayList<>();
        /** Canonical tokens of the counted instructions behind the held call. */
        private final List<String> pending = new ArrayList<>();
        private boolean holding;
        private boolean heldItf;

        AnchoredRedirect(MethodVisitor mv, TransformRegistry.Redirect r, int[] sites) {
            super(ASM8, mv);
            this.r = r;
            this.sites = sites;
        }

        private void emitReplacement() {
            super.visitMethodInsn(INVOKESTATIC, r.toOwner(), r.toName(), r.toDesc(), false);
            sites[0]++;
        }

        private void release(boolean confirmed) {
            holding = false;
            List<Runnable> events = new ArrayList<>(confirmed ? held : revisit);
            held.clear();
            revisit.clear();
            pending.clear();
            if (confirmed) {
                emitReplacement();
            } else {
                super.visitMethodInsn(r.fromOpcode(), r.fromOwner(), r.fromName(), r.fromDesc(), heldItf);
            }
            // a refuted candidate's events re-enter this visitor and may hold (and settle) a new one
            for (Runnable event : events) {
                event.run();
            }
        }

        /**
         * A counted instruction while a call is held: buffered (true = consumed); the
         * {@code followingCount}-th one settles the candidate by the fingerprint.
         */
        private boolean anchorStep(String token, Runnable emit, Runnable again) {
            if (!holding) {
                return false;
            }
            held.add(emit);
            revisit.add(again);
            pending.add(token);
            if (pending.size() == r.followingCount()) {
                byte[] digest;
                try {
                    digest = MessageDigest.getInstance("SHA-256")
                            .digest(String.join("\n", pending).getBytes(StandardCharsets.UTF_8));
                } catch (NoSuchAlgorithmException e) {
                    throw new IllegalStateException("SHA-256 unavailable", e);
                }
                release(HexFormat.of().formatHex(digest).equals(r.followingSha256()));
            }
            return true;
        }

        /** A non-counted event while a call is held: kept in order behind it. */
        private boolean holdTransparent(Runnable emit, Runnable again) {
            if (!holding) {
                return false;
            }
            held.add(emit);
            revisit.add(again);
            return true;
        }

        /** Refutes the held candidate, and any a re-visited event held in its place. */
        private void releaseIfHolding() {
            while (holding) {
                release(false);
            }
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (anchorStep(methodOpName(opcode) + ":" + owner + "." + name + descriptor,
                    () -> super.visitMethodInsn(opcode, owner, name, descriptor, isInterface),
                    () -> visitMethodInsn(opcode, owner, name, descriptor, isInterface))) {
                return;
            }
            if (opcode == r.fromOpcode() && r.fromOwner().equals(owner) && r.fromName().equals(name)
                    && r.fromDesc().equals(descriptor)) {
                if (r.followingCount() == 0) {
                    emitReplacement();
                    return;
                }
                holding = true;
                heldItf = isInterface;
                return;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public void visitInsn(int opcode) {
            if (!anchorStep(ZERO_OPERAND.getOrDefault(opcode, "OP" + opcode), () -> super.visitInsn(opcode),
                    () -> visitInsn(opcode))) {
                super.visitInsn(opcode);
            }
        }

        @Override
        public void visitLdcInsn(Object value) {
            if (!anchorStep(ldcToken(value), () -> super.visitLdcInsn(value), () -> visitLdcInsn(value))) {
                super.visitLdcInsn(value);
            }
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            releaseIfHolding();
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            releaseIfHolding();
            super.visitVarInsn(opcode, var);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            releaseIfHolding();
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            releaseIfHolding();
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle,
                Object... bootstrapMethodArguments) {
            releaseIfHolding();
            super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            releaseIfHolding();
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitIincInsn(int var, int increment) {
            releaseIfHolding();
            super.visitIincInsn(var, increment);
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            releaseIfHolding();
            super.visitTableSwitchInsn(min, max, dflt, labels);
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            releaseIfHolding();
            super.visitLookupSwitchInsn(dflt, keys, labels);
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            releaseIfHolding();
            super.visitMultiANewArrayInsn(descriptor, numDimensions);
        }

        @Override
        public void visitLabel(Label label) {
            if (!holdTransparent(() -> super.visitLabel(label), () -> visitLabel(label))) {
                super.visitLabel(label);
            }
        }

        @Override
        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
            if (!holding) {
                super.visitFrame(type, numLocal, local, numStack, stack);
                return;
            }
            // the reader reuses its frame arrays for the next frame: a held frame keeps its own copy
            Object[] localCopy = local == null ? null : local.clone();
            Object[] stackCopy = stack == null ? null : stack.clone();
            holdTransparent(() -> super.visitFrame(type, numLocal, localCopy, numStack, stackCopy),
                    () -> visitFrame(type, numLocal, localCopy, numStack, stackCopy));
        }

        @Override
        public AnnotationVisitor visitInsnAnnotation(int typeRef, TypePath typePath, String descriptor,
                boolean visible) {
            releaseIfHolding();
            return super.visitInsnAnnotation(typeRef, typePath, descriptor, visible);
        }

        @Override
        public void visitLineNumber(int line, Label start) {
            if (!holdTransparent(() -> super.visitLineNumber(line, start), () -> visitLineNumber(line, start))) {
                super.visitLineNumber(line, start);
            }
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            releaseIfHolding();
            super.visitTryCatchBlock(start, end, handler, type);
        }

        @Override
        public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end,
                int index) {
            releaseIfHolding();
            super.visitLocalVariable(name, descriptor, signature, start, end, index);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            releaseIfHolding();
            super.visitMaxs(maxStack, maxLocals);
        }

        @Override
        public void visitEnd() {
            releaseIfHolding();
            super.visitEnd();
        }
    }
}
