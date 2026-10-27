package cc.starsector.server.premain;

import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import cc.starsector.server.transform.TransformLaunchGate;
import cc.starsector.server.transform.TransformRegistry;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * The {@link TransformRegistry.Kind#NEW_TYPE_SWAP} applier:
 * inside ONE fingerprinted game method, every {@code NEW from} and its
 * {@code INVOKESPECIAL from.<init>ctorDesc} become the same pair on {@code to}, a strict subclass
 * of {@code from}. WHY: a {@code HashMap} keyed by objects without a {@code hashCode} override
 * walks in identity-hash order, a different order every JVM, so an exact tie in a strict-max or
 * nearest pick over its values resolves differently in a recorded match and in its re-simulation;
 * allocating a {@code LinkedHashMap} instead makes the walk the engine's own insertion order and
 * leaves every computation the engine's.
 *
 * <p>Shape (the {@link CallSiteRedirectApplier} precedent, one applier per Kind): entries mapped by
 * target class, two on one class refused at construction, and the replacement type checked at
 * construction too: a bootstrap class (so the rewritten class links it from any game loader), a
 * strict subclass of the original, declaring the sites' constructor publicly. At class load the
 * bytes are re-verified against the registered fingerprint. Flags 0 is safe because a {@code NEW}
 * swap to a same-constructor subclass keeps the original maxs and every frame: both instructions
 * keep their length and stack effect, an uninitialized frame entry names the allocation's offset
 * rather than its type, and methods not wrapped are copied byte-for-byte.
 *
 * <p>Fail-closed: the allocations and the constructor calls are counted APART, and each must equal
 * the entry's {@code expectedSites} (an allocation swapped without its constructor call, or the
 * reverse, would not verify); the OUTPUT is then re-read independently: in the target method
 * exactly {@code expectedSites} allocations and constructor calls moved from {@code from} to
 * {@code to}, every other method's four counts unchanged. A transformer cannot refuse by throwing
 * (the JVM swallows it and loads the original bytes), so {@link #transform} prints the named
 * boundary and {@code halt(2)}s.
 */
final class NewTypeSwapApplier implements ClassFileTransformer, Opcodes {

    private final Map<String, TransformRegistry.Entry> byInternalName = new HashMap<>();
    /** Allocation sites swapped by the most recent rewrite per entry id (absent = that class has not loaded yet). */
    private final Map<String, Integer> lastSites = new ConcurrentHashMap<>();

    NewTypeSwapApplier(List<TransformRegistry.Entry> enabled) {
        for (TransformRegistry.Entry entry : enabled) {
            TransformRegistry.TypeSwap swap = entry.typeSwap();
            if (entry.kind() != TransformRegistry.Kind.NEW_TYPE_SWAP || swap == null
                    || entry.methods().size() != 1 || swap.expectedSites() < 1) {
                throw new IllegalStateException("[cc-transform] " + entry.id() + " is not a one-method "
                        + TransformRegistry.Kind.NEW_TYPE_SWAP + " entry with expectedSites >= 1 (expectedSites "
                        + (swap == null ? "absent" : Integer.toString(swap.expectedSites())) + ")");
            }
            Class<?> from = bootstrapClass(entry, swap.fromInternalName());
            Class<?> to = bootstrapClass(entry, swap.toInternalName());
            if (from == to || !from.isAssignableFrom(to)) {
                throw new IllegalStateException("[cc-transform] " + entry.id() + ": " + swap.toInternalName()
                        + " is not a strict subclass of " + swap.fromInternalName()
                        + " — the swapped allocation would not fit where the code expects the original type");
            }
            boolean declared = false;
            for (Constructor<?> c : to.getDeclaredConstructors()) {
                declared |= Modifier.isPublic(c.getModifiers())
                        && Type.getConstructorDescriptor(c).equals(swap.ctorDesc());
            }
            if (!Modifier.isPublic(to.getModifiers()) || !declared) {
                throw new IllegalStateException("[cc-transform] " + entry.id() + ": " + swap.toInternalName()
                        + " does not publicly declare a constructor " + swap.ctorDesc()
                        + " — the swapped constructor call would not link from the game class");
            }
            TransformRegistry.Entry prior = byInternalName.put(entry.targetClassInternalName(), entry);
            if (prior != null) {
                throw new IllegalStateException("[cc-transform] two enabled entries target "
                        + entry.targetClassInternalName() + " (" + prior.id() + " + " + entry.id()
                        + ") — one type-swap applier entry per class");
            }
        }
    }

    /** The bootstrap loader's class for an internal name; an unknown name refuses, naming the entry and the class. */
    private static Class<?> bootstrapClass(TransformRegistry.Entry entry, String internalName) {
        try {
            return Class.forName(String.valueOf(internalName).replace('/', '.'), false, null);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("[cc-transform] " + entry.id() + ": " + internalName
                    + " is not a bootstrap class (" + e + ")", e);
        }
    }

    /** Allocation sites the most recent rewrite of {@code id}'s class swapped; -1 before that class loaded. */
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
            System.err.println("[cc-transform] REFUSING (load-time type swap): " + entry.id() + " on " + className
                    + " — " + (t.getMessage() != null ? t.getMessage() : t.toString()));
            Runtime.getRuntime().halt(2);
            throw new AssertionError("unreachable");
        }
    }

    /**
     * The rewrite without the halt: returns the retyped class bytes, or throws
     * {@link IllegalStateException} naming the boundary (load-time bytes drifted, an allocation or
     * constructor-call count other than {@code expectedSites}, output counts wrong). Records
     * {@link #sitesOf} before any count refusal.
     */
    byte[] rewrite(TransformRegistry.Entry entry, byte[] classfileBuffer) {
        List<String> refusals = TransformLaunchGate.verify(entry, classfileBuffer);
        if (!refusals.isEmpty()) {
            throw new IllegalStateException("load-time bytes differ from the launch-gate-verified jar"
                    + " (another transformer rewrote them first, or a different jar shadows it): "
                    + refusals.get(0));
        }
        TransformRegistry.TargetMethod target = entry.methods().get(0);
        TransformRegistry.TypeSwap swap = entry.typeSwap();
        ClassReader reader = new ClassReader(classfileBuffer);
        // Flags 0: both swapped instructions keep their length and stack effect, so the original
        // maxs and StackMapTable stay valid; methods not wrapped below are copied verbatim.
        ClassWriter writer = new ClassWriter(reader, 0);
        int[] newSites = new int[] { 0 };
        int[] initSites = new int[] { 0 };
        reader.accept(new ClassVisitor(ASM8, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!target.name().equals(name) || !target.descriptor().equals(descriptor)) {
                    return mv;
                }
                return new MethodVisitor(ASM8, mv) {
                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        if (opcode == NEW && swap.fromInternalName().equals(type)) {
                            newSites[0]++;
                            super.visitTypeInsn(NEW, swap.toInternalName());
                            return;
                        }
                        super.visitTypeInsn(opcode, type);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String insnOwner, String insnName, String insnDesc,
                            boolean isInterface) {
                        if (opcode == INVOKESPECIAL && swap.fromInternalName().equals(insnOwner)
                                && "<init>".equals(insnName) && swap.ctorDesc().equals(insnDesc)) {
                            initSites[0]++;
                            super.visitMethodInsn(INVOKESPECIAL, swap.toInternalName(), insnName, insnDesc, isInterface);
                            return;
                        }
                        super.visitMethodInsn(opcode, insnOwner, insnName, insnDesc, isInterface);
                    }
                };
            }
        }, 0);
        lastSites.put(entry.id(), newSites[0]);
        String where = entry.targetClassName() + "." + target.name() + target.descriptor();
        int expected = swap.expectedSites();
        if (newSites[0] != expected || initSites[0] != expected) {
            throw new IllegalStateException("FAIL-CLOSED — expected exactly " + expected + " NEW "
                    + swap.fromInternalName() + " + <init>" + swap.ctorDesc() + " pairs in " + where + ", found NEW="
                    + newSites[0] + " INIT=" + initSites[0] + " — target shape changed; re-find: "
                    + entry.refindRecipe());
        }
        byte[] out = writer.toByteArray();
        verifyOutput(entry, classfileBuffer, out);
        CcTransformPremain.log("applied " + entry.id() + ": " + expected + " NEW " + swap.fromInternalName() + " -> "
                + swap.toInternalName() + " sites in " + where);
        System.setProperty("cc.transform.applied." + entry.id(), where);
        return out;
    }

    /**
     * Re-reads IN and OUT (independent of the rewrite's own counters), per method: in the target,
     * exactly {@code expectedSites} allocations and constructor calls moved from {@code from} to
     * {@code to}; in every other method the four counts are unchanged; the method sets are equal.
     */
    private static void verifyOutput(TransformRegistry.Entry entry, byte[] in, byte[] out) {
        TransformRegistry.TypeSwap swap = entry.typeSwap();
        String targetKey = entry.methods().get(0).name() + entry.methods().get(0).descriptor();
        int expected = swap.expectedSites();
        Map<String, int[]> before = swapCounts(in, swap);
        Map<String, int[]> after = swapCounts(out, swap);
        if (!before.keySet().equals(after.keySet()) || !before.containsKey(targetKey)) {
            throw new IllegalStateException("FAIL-CLOSED — rewritten " + entry.targetClassName()
                    + " method set differs from the input, or lacks " + targetKey);
        }
        for (Map.Entry<String, int[]> method : before.entrySet()) {
            int[] b = method.getValue();
            int[] a = after.get(method.getKey());
            boolean target = method.getKey().equals(targetKey);
            int moved = target ? expected : 0;
            boolean ok = a[NEW_FROM] == b[NEW_FROM] - moved && a[NEW_TO] == b[NEW_TO] + moved
                    && a[INIT_FROM] == b[INIT_FROM] - moved && a[INIT_TO] == b[INIT_TO] + moved;
            if (!ok) {
                throw new IllegalStateException("FAIL-CLOSED — rewritten " + entry.targetClassName() + "."
                        + method.getKey() + " counts wrong: [NEW from, NEW to, INIT from, INIT to] in "
                        + Arrays.toString(b) + " out " + Arrays.toString(a) + " (expected "
                        + (target ? expected + " moved from " + swap.fromInternalName() + " to " + swap.toInternalName()
                                : "unchanged") + ")");
            }
        }
    }

    private static final int NEW_FROM = 0;
    private static final int NEW_TO = 1;
    private static final int INIT_FROM = 2;
    private static final int INIT_TO = 3;

    /**
     * Per method WITH code ({@code name + descriptor} key): {@code [NEW from, NEW to,
     * INVOKESPECIAL from.<init>ctorDesc, INVOKESPECIAL to.<init>ctorDesc]}.
     */
    private static Map<String, int[]> swapCounts(byte[] classBytes, TransformRegistry.TypeSwap swap) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        new ClassReader(classBytes).accept(new ClassVisitor(ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String methodName, String methodDesc, String signature,
                    String[] exceptions) {
                if ((access & (ACC_ABSTRACT | ACC_NATIVE)) != 0) {
                    return null;
                }
                int[] row = new int[4];
                counts.put(methodName + methodDesc, row);
                return new MethodVisitor(ASM9) {
                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        if (opcode == NEW) {
                            row[NEW_FROM] += swap.fromInternalName().equals(type) ? 1 : 0;
                            row[NEW_TO] += swap.toInternalName().equals(type) ? 1 : 0;
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                            boolean isInterface) {
                        if (opcode == INVOKESPECIAL && "<init>".equals(name) && swap.ctorDesc().equals(descriptor)) {
                            row[INIT_FROM] += swap.fromInternalName().equals(owner) ? 1 : 0;
                            row[INIT_TO] += swap.toInternalName().equals(owner) ? 1 : 0;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return counts;
    }

    /**
     * Per method WITH code ({@code name + descriptor} key, every such method present, zero
     * included): how many {@code NEW internalName} instructions it holds. The contract rows'
     * reflected reader (the {@code callCounts} precedent in {@link CallSiteRedirectApplier}).
     */
    static Map<String, Integer> newCounts(byte[] classBytes, String internalName) {
        Map<String, Integer> counts = new LinkedHashMap<>();
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
                    public void visitTypeInsn(int opcode, String type) {
                        if (opcode == NEW && internalName.equals(type)) {
                            counts.merge(key, 1, Integer::sum);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return counts;
    }
}
