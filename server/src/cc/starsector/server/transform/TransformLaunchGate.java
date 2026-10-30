package cc.starsector.server.transform;

import java.util.ArrayList;
import java.util.List;

/**
 * The fail-closed half of the transform registry — verify every registered
 * fingerprint of an entry against actual class bytes BEFORE any transform applies. An empty result
 * is a pass; each returned line is a named refusal boundary (entry, method, expected vs actual)
 * suitable for printing verbatim, so version drift surfaces as a refused launch naming its
 * boundary instead of a weird mid-session failure. This verifier rides the
 * registry.
 */
public final class TransformLaunchGate {

    private TransformLaunchGate() {
    }

    /**
     * Verifies {@code entry}'s fingerprints against {@code classBytes} (the target classfile) under
     * the entry's {@link TransformRegistry.Kind} semantics: {@code METHOD_NOOP}, {@code CALL_SITE_REDIRECT}
     * and {@code NEW_TYPE_SWAP} = every registered method must match (ALL-match);
     * {@code RNG_KEYED_CTOR_PIN} = the one target method's measured fingerprint must be in the
     * accepted set (ANY-OF — one row per legitimate runtime). Returns an empty list on pass, else
     * one named boundary line per failure.
     */
    public static List<String> verify(TransformRegistry.Entry entry, byte[] classBytes) {
        if (entry.kind() == TransformRegistry.Kind.FOREIGN_LOAD_TIME_SEAM) {
            // A third-party seam has no code[] fingerprint to verify and no
            // jar on java.class.path; an eager caller is a wiring error, refused loud rather than passed or failed
            throw new IllegalStateException(entry.id() + " is a " + TransformRegistry.Kind.FOREIGN_LOAD_TIME_SEAM
                    + " entry: load-time only and fail-open, never verified by the eager launch gate");
        }
        if (entry.kind() == TransformRegistry.Kind.RNG_KEYED_CTOR_PIN) {
            return verifyAnyOf(entry, classBytes);
        }
        List<String> refusals = new ArrayList<>();
        for (TransformRegistry.TargetMethod method : entry.methods()) {
            String where = entry.id() + " target " + entry.targetClassName()
                    + "." + method.name() + method.descriptor();
            try {
                String actual = MethodBytecodeFingerprint.sha256OfMethodCode(
                        classBytes, method.name(), method.descriptor());
                if (!actual.equalsIgnoreCase(method.sha256())) {
                    refusals.add(where + ": bytecode fingerprint mismatch — expected sha256="
                            + method.sha256() + " actual=" + actual
                            + "; target drifted (game update?); re-find before re-enabling: "
                            + entry.refindRecipe());
                }
            } catch (RuntimeException e) {
                refusals.add(where + ": cannot fingerprint target — " + e.getMessage()
                        + "; re-find before re-enabling: " + entry.refindRecipe());
            }
        }
        return refusals;
    }

    /**
     * ANY-OF semantics for JDK-site kinds: all rows share one name+descriptor and form the accepted
     * set (the two bundled JVMs ship different {@code Random.<init>} bytes); the running class's
     * measured fingerprint must equal one of them.
     */
    private static List<String> verifyAnyOf(TransformRegistry.Entry entry, byte[] classBytes) {
        TransformRegistry.TargetMethod first = entry.methods().get(0);
        String where = entry.id() + " target " + entry.targetClassName()
                + "." + first.name() + first.descriptor();
        String actual;
        try {
            actual = MethodBytecodeFingerprint.sha256OfMethodCode(
                    classBytes, first.name(), first.descriptor());
        } catch (RuntimeException e) {
            return List.of(where + ": cannot fingerprint target — " + e.getMessage()
                    + "; re-find before re-enabling: " + entry.refindRecipe());
        }
        List<String> accepted = new ArrayList<>();
        for (TransformRegistry.TargetMethod method : entry.methods()) {
            if (actual.equalsIgnoreCase(method.sha256())) {
                return List.of();
            }
            accepted.add(method.sha256());
        }
        return List.of(where + ": bytecode fingerprint mismatch — measured sha256=" + actual
                + " is in NEITHER accepted runtime fingerprint " + accepted
                + "; target drifted (game/JDK update?); re-find before re-enabling: "
                + entry.refindRecipe());
    }
}
