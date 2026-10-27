package cc.starsector.server.premain;

import java.lang.instrument.ClassFileTransformer;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The {@code Math.random()} caller CENSUS — a
 * DIAGNOSTIC transformer, default OFF, armed only by {@code -Dcc.rngpin.census=<ticks>} together
 * with the keyed pin (HOOK-0044, whose bootstrap append makes the holder resolvable from every
 * loader). It is deliberately NOT a {@code TransformRegistry} entry: it changes no behavior and
 * fingerprints no target — it rewrites EVERY {@code invokestatic java/lang/Math.random()D} in every
 * class loaded outside the JDK (and outside the holder / the premain / the shaded ASM) into
 * {@code sipush <siteId>; invokestatic CcRngRedirect.random(I)D}, whose body draws exactly ONE
 * {@code Math.random()} and counts it against the site while a census window is open. WHY: two
 * re-sims of one reel diverged at tick 8 by exactly three Math.random draws; the
 * census names the drawing site per tick, which a static read of 319 engine sites could not.
 *
 * <p>Stack shape: the swap pushes one extra int before the call, so the writer runs with
 * {@code COMPUTE_MAXS} (never {@code COMPUTE_FRAMES}: the original StackMapTable frames stay valid —
 * no branch target lies between the {@code sipush} and its call); methods with no site are copied
 * verbatim by ASM. Fail-closed like the redirect applier: an output whose call counts are not
 * "zero {@code Math.random()} left, one {@code random(I)D} per original call, per method" names the
 * boundary and {@code halt(2)}s — a transformer that throws is silently ignored by the JVM.
 */
final class MathRandomCensusApplier implements ClassFileTransformer, Opcodes {

    static final String HOLDER = "cc/starsector/server/rngpin/CcRngRedirect";
    private static final byte[] MATH_UTF8 = "java/lang/Math".getBytes(StandardCharsets.UTF_8);
    /** A site also needs the method name and descriptor UTF-8s in the pool. */
    private static final byte[] RANDOM_UTF8 = "random".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DESC_UTF8 = "()D".getBytes(StandardCharsets.UTF_8);
    /** Prefixes never rewritten: the JDK, the holder itself, this premain and its shaded ASM. */
    private static final List<String> SKIP_PREFIXES = List.of("java/", "javax/", "jdk/", "sun/", "com/sun/",
            "cc/starsector/server/rngpin/", "cc/starsector/server/premain/", "org/objectweb/asm/");

    private int classesRewritten;
    private int sitesRewritten;

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || classfileBuffer == null) {
            return null;
        }
        for (String prefix : SKIP_PREFIXES) {
            if (className.startsWith(prefix)) {
                return null;
            }
        }
        // cheap pre-filter: a class without the owner's, the name's and the descriptor's UTF-8s in its
        // constant pool has no site (before, every java/lang/Math user reached the ASM parse)
        if (indexOf(classfileBuffer, MATH_UTF8) < 0 || indexOf(classfileBuffer, RANDOM_UTF8) < 0
                || indexOf(classfileBuffer, DESC_UTF8) < 0) {
            return null;
        }
        try {
            return rewrite(className, classfileBuffer);
        } catch (Throwable t) {
            System.err.println("[cc-transform] REFUSING (rng census rewrite): " + className + " — "
                    + (t.getMessage() != null ? t.getMessage() : t.toString()));
            Runtime.getRuntime().halt(2);
            throw new AssertionError("unreachable");
        }
    }

    /** The rewrite without the halt: null when the class has no site, else the rewritten bytes (self-checked). */
    byte[] rewrite(String className, byte[] classfileBuffer) {
        Map<String, Integer> sitesIn = CallSiteRedirectApplier.callCounts(classfileBuffer, INVOKESTATIC,
                "java/lang/Math", "random", "()D");
        int total = sitesIn.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) {
            return null;
        }
        String dotted = className.replace('/', '.');
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object"; // never resolves target-jar hierarchy (COMPUTE_MAXS never asks)
            }
        };
        // ASM9: an ASM8 visitor throws on a sealed class's PermittedSubclasses
        reader.accept(new ClassVisitor(ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (sitesIn.getOrDefault(name + descriptor, 0) == 0) {
                    return mv; // verbatim copy
                }
                return new MethodVisitor(ASM9, mv) {
                    private int ordinal;

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname, String mdesc,
                            boolean isInterface) {
                        if (opcode == INVOKESTATIC && "java/lang/Math".equals(owner) && "random".equals(mname)
                                && "()D".equals(mdesc)) {
                            int site = cc.starsector.server.rngpin.CcRngRedirect
                                    .censusSite(dotted + "." + name + descriptor + "#" + ordinal++);
                            if (site > Short.MAX_VALUE) {
                                throw new IllegalStateException("more than " + Short.MAX_VALUE + " census sites");
                            }
                            super.visitIntInsn(SIPUSH, site);
                            super.visitMethodInsn(INVOKESTATIC, HOLDER, "random", "(I)D", false);
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, mname, mdesc, isInterface);
                    }
                };
            }
        }, 0);
        byte[] out = writer.toByteArray();
        verifyOutput(className, sitesIn, out);
        classesRewritten++;
        sitesRewritten += total;
        CcTransformPremain.log("rng census: " + total + " Math.random() site(s) redirected in " + dotted
                + " (" + classesRewritten + " classes, " + sitesRewritten + " sites so far)");
        return out;
    }

    /** Re-reads the OUTPUT: no {@code Math.random()} left anywhere; one {@code random(I)D} per original call, per method. */
    private static void verifyOutput(String className, Map<String, Integer> sitesIn, byte[] out) {
        Map<String, Integer> left = CallSiteRedirectApplier.callCounts(out, INVOKESTATIC, "java/lang/Math", "random",
                "()D");
        Map<String, Integer> redirected = CallSiteRedirectApplier.callCounts(out, INVOKESTATIC, HOLDER, "random", "(I)D");
        boolean ok = left.values().stream().allMatch(n -> n == 0) && redirected.keySet().equals(sitesIn.keySet());
        for (Map.Entry<String, Integer> m : sitesIn.entrySet()) {
            ok &= redirected.getOrDefault(m.getKey(), -1).equals(m.getValue());
        }
        if (!ok) {
            throw new IllegalStateException("FAIL-CLOSED — rewritten " + className + " call counts wrong: Math.random"
                    + " left per method " + left + ", redirected per method " + redirected + ", expected " + sitesIn);
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
