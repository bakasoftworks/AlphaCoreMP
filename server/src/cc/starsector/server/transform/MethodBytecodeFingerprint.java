package cc.starsector.server.transform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * descriptor-grade method fingerprinting for the
 * product-owned transform registry. The fingerprint of a method is the SHA-256 over the raw
 * {@code code[]} bytes of its {@code Code} attribute — the executable bytecode only, excluding
 * max_stack/max_locals, the exception table, and debug attributes (LineNumberTable et al.), so a
 * debug-info-only rebuild of the same code still matches while any instruction change refuses.
 * The bytes include constant-pool indices, which are stable for a given classfile build; a
 * recompiled target jar (game update) is EXPECTED to drift and must re-run the re-find recipe
 * recorded on its registry entry (an invariant borrowed from the Nitrox project).
 *
 * Hand-rolled classfile parse (JVMS ch. 4) rather than ASM so this class — and with it the whole
 * launch-gate half of the registry — compiles under {@code server/build.ps1}'s plain
 * {@code --release 17} with zero dependencies and is usable game-free by contract tests and the
 * {@link TransformRegistryVerifierMain} CLI. Only the premain's REWRITE half needs ASM.
 */
public final class MethodBytecodeFingerprint {

    private MethodBytecodeFingerprint() {
    }

    /**
     * SHA-256 (lowercase hex) over the {@code code[]} bytes of the named method's {@code Code}
     * attribute. Throws {@link IllegalArgumentException} naming the problem when the method or its
     * {@code Code} attribute is absent or the classfile is malformed — callers surface that as a
     * fail-closed refusal boundary.
     */
    public static String sha256OfMethodCode(byte[] classBytes, String methodName, String methodDescriptor) {
        int[] range = locateMethodCode(classBytes, methodName, methodDescriptor);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        digest.update(classBytes, range[0], range[1]);
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest()) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    /**
     * Locates the {@code code[]} byte range of the named method's {@code Code} attribute and
     * returns {@code {offset, length}} into {@code classBytes}. Public so tests can tamper exactly
     * the fingerprinted range and assert the gate refuses; this range definition IS the fingerprint
     * contract documented on {@link #sha256OfMethodCode}.
     */
    public static int[] locateMethodCode(byte[] classBytes, String methodName, String methodDescriptor) {
        Parser p = new Parser(classBytes);
        if (p.u4() != 0xCAFEBABEL) {
            throw new IllegalArgumentException("not a classfile (bad magic)");
        }
        p.skip(4); // minor + major version
        String[] utf8 = p.constantPool();
        p.skip(6); // access_flags, this_class, super_class
        p.skip(2 * p.u2()); // interfaces
        skipMembers(p, utf8, null, null); // fields
        int[] code = skipMembers(p, utf8, methodName, methodDescriptor); // methods
        if (code == null) {
            throw new IllegalArgumentException(
                    "method not found or has no Code attribute: " + methodName + methodDescriptor);
        }
        return code;
    }

    /**
     * Walks a field_info/method_info table. When {@code wantName} is non-null, returns the
     * {@code {offset, length}} of the matching method's {@code Code} attribute {@code code[]}
     * bytes (or null if never matched); for fields pass null and the table is just skipped.
     */
    private static int[] skipMembers(Parser p, String[] utf8, String wantName, String wantDescriptor) {
        int[] found = null;
        int count = p.u2();
        for (int i = 0; i < count; i++) {
            p.skip(2); // access_flags
            String name = utf8[p.u2()];
            String descriptor = utf8[p.u2()];
            boolean match = wantName != null && wantName.equals(name) && wantDescriptor.equals(descriptor);
            int attributes = p.u2();
            for (int a = 0; a < attributes; a++) {
                String attributeName = utf8[p.u2()];
                int length = (int) p.u4();
                if (match && "Code".equals(attributeName)) {
                    // Code attribute body: u2 max_stack, u2 max_locals, u4 code_length, code[]
                    int body = p.offset;
                    int codeLength = ((p.bytes[body + 4] & 0xFF) << 24) | ((p.bytes[body + 5] & 0xFF) << 16)
                            | ((p.bytes[body + 6] & 0xFF) << 8) | (p.bytes[body + 7] & 0xFF);
                    found = new int[] { body + 8, codeLength };
                }
                p.skip(length);
            }
        }
        return found;
    }

    /** Minimal big-endian cursor over the classfile bytes. */
    private static final class Parser {
        final byte[] bytes;
        int offset;

        Parser(byte[] bytes) {
            this.bytes = bytes;
        }

        int u1() {
            return bytes[offset++] & 0xFF;
        }

        int u2() {
            return (u1() << 8) | u1();
        }

        long u4() {
            return ((long) u2() << 16) | u2();
        }

        void skip(int n) {
            offset += n;
        }

        /** Parses the constant pool, returning Utf8 values by index (others null). */
        String[] constantPool() {
            int count = u2();
            String[] utf8 = new String[count];
            for (int i = 1; i < count; i++) {
                int tag = u1();
                switch (tag) {
                    case 1: { // CONSTANT_Utf8
                        int length = u2();
                        utf8[i] = new String(bytes, offset, length, StandardCharsets.UTF_8);
                        skip(length);
                        break;
                    }
                    case 7: case 8: case 16: case 19: case 20: // Class, String, MethodType, Module, Package
                        skip(2);
                        break;
                    case 15: // MethodHandle
                        skip(3);
                        break;
                    case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18:
                        // Integer, Float, refs, NameAndType, Dynamic, InvokeDynamic
                        skip(4);
                        break;
                    case 5: case 6: // Long, Double — take two pool slots
                        skip(8);
                        i++;
                        break;
                    default:
                        throw new IllegalArgumentException("unknown constant pool tag " + tag + " at index " + i);
                }
            }
            return utf8;
        }
    }
}
