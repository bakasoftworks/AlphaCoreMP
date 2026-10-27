package cc.starsector.server.transform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The game-free registry verifier CLI — proves every {@link TransformRegistry}
 * entry's fingerprints against the REAL install's jars without launching anything. This is both the
 * standing verifier (exit 0 = every registered transform still matches the installed bytes) and the
 * re-find tool a game update runs ({@code --print} emits freshly measured fingerprints to paste into
 * the registry after a bytecode re-check). Driven by a wrapper script.
 *
 * <pre>
 *   java ... TransformRegistryVerifierMain &lt;starsector-core-dir&gt;                  # verify: 0=pass, 2=refused
 *   java ... TransformRegistryVerifierMain &lt;starsector-core-dir&gt; --print          # measure + print fingerprints
 *   java ... TransformRegistryVerifierMain &lt;starsector-core-dir&gt; --tamper-self-test
 *       # flips one byte inside the first registered method's fingerprinted code range IN MEMORY and
 *       # asserts the gate refuses (0=gate refused as required, 3=gate FAILED to refuse). Proves the
 *       # fail-closed path against real bytes without touching any file.
 * </pre>
 */
public final class TransformRegistryVerifierMain {

    private TransformRegistryVerifierMain() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args.length > 2) {
            System.err.println("usage: TransformRegistryVerifierMain <starsector-core-dir> [--print|--tamper-self-test]");
            System.exit(64);
        }
        Path jarDir = Path.of(args[0]);
        String mode = args.length == 2 ? args[1] : "";

        boolean refused = false;
        for (TransformRegistry.Entry entry : TransformRegistry.entries()) {
            // JDK-site entries (targetJarFileName null) verify against the
            // RUNNING JVM's own class bytes — so running this on the game jre gates that runtime,
            // and the build-JDK contract children gate the other (any-of accepted set).
            String where;
            byte[] classBytes;
            if (entry.targetJarFileName() == null) {
                where = "running JVM (java " + System.getProperty("java.runtime.version") + ")";
                classBytes = readRunningJvmClassBytes(entry);
                if (classBytes == null) {
                    System.err.println("REFUSE " + entry.id() + ": running JVM does not expose "
                            + entry.targetClassResource());
                    refused = true;
                    continue;
                }
            } else {
                Path jar = jarDir.resolve(entry.targetJarFileName());
                if (!Files.isRegularFile(jar)) {
                    System.err.println("REFUSE " + entry.id() + ": target jar not found: " + jar);
                    refused = true;
                    continue;
                }
                where = jar.toString();
                classBytes = readClassBytes(jar, entry);
            }
            switch (mode) {
                case "--print" -> printMeasured(entry, where, classBytes);
                case "--tamper-self-test" -> refused |= !tamperSelfTest(entry, classBytes);
                case "" -> refused |= !verify(entry, where, classBytes);
                default -> {
                    System.err.println("unknown mode: " + mode);
                    System.exit(64);
                }
            }
        }
        if (refused) {
            System.exit(mode.equals("--tamper-self-test") ? 3 : 2);
        }
    }

    private static boolean verify(TransformRegistry.Entry entry, String where, byte[] classBytes) {
        List<String> refusals = TransformLaunchGate.verify(entry, classBytes);
        if (refusals.isEmpty()) {
            System.out.println("PASS " + entry.id() + ": "
                    + (entry.kind() == TransformRegistry.Kind.RNG_KEYED_CTOR_PIN
                            ? "fingerprint in the accepted set (" + entry.methods().size() + " runtimes) for "
                            : entry.methods().size() + " fingerprints match ")
                    + entry.targetClassName() + " in " + where);
            return true;
        }
        refusals.forEach(r -> System.err.println("REFUSE " + r));
        return false;
    }

    /** The re-find half: measure and print current fingerprints in registry-source form. */
    private static void printMeasured(TransformRegistry.Entry entry, String where, byte[] classBytes) {
        System.out.println(entry.id() + " " + entry.targetClassName() + " in " + where + ":");
        // Any-of entries repeat one name+descriptor per accepted runtime — measure it once.
        List<TransformRegistry.TargetMethod> toMeasure =
                entry.kind() == TransformRegistry.Kind.RNG_KEYED_CTOR_PIN
                        ? entry.methods().subList(0, 1)
                        : entry.methods();
        for (TransformRegistry.TargetMethod method : toMeasure) {
            String measured;
            try {
                measured = MethodBytecodeFingerprint.sha256OfMethodCode(
                        classBytes, method.name(), method.descriptor());
            } catch (RuntimeException e) {
                measured = "UNMEASURABLE: " + e.getMessage();
            }
            System.out.println("  " + method.name() + method.descriptor() + " sha256=" + measured);
        }
    }

    private static byte[] readRunningJvmClassBytes(TransformRegistry.Entry entry) throws IOException {
        try (InputStream in = ClassLoader.getSystemResourceAsStream(entry.targetClassResource())) {
            return in == null ? null : in.readAllBytes();
        }
    }

    /** Flips one byte inside the first method's fingerprinted range; the gate MUST refuse. */
    private static boolean tamperSelfTest(TransformRegistry.Entry entry, byte[] classBytes) {
        TransformRegistry.TargetMethod first = entry.methods().get(0);
        int[] range = MethodBytecodeFingerprint.locateMethodCode(classBytes, first.name(), first.descriptor());
        byte[] tampered = classBytes.clone();
        tampered[range[0]] ^= 0x01;
        List<String> refusals = TransformLaunchGate.verify(entry, tampered);
        boolean refusedTamperedMethod = refusals.stream()
                .anyMatch(r -> r.contains(first.name() + first.descriptor()) && r.contains("mismatch"));
        if (refusedTamperedMethod) {
            System.out.println("PASS " + entry.id() + " tamper self-test: gate refused the tampered "
                    + first.name() + first.descriptor() + " — " + refusals.get(0));
            return true;
        }
        System.err.println("FAIL " + entry.id() + " tamper self-test: gate did NOT refuse tampered bytes");
        return false;
    }

    private static byte[] readClassBytes(Path jar, TransformRegistry.Entry entry) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry classEntry = zip.getEntry(entry.targetClassResource());
            if (classEntry == null) {
                throw new IOException(entry.targetClassResource() + " not found in " + jar);
            }
            try (InputStream in = zip.getInputStream(classEntry)) {
                return in.readAllBytes();
            }
        }
    }
}
