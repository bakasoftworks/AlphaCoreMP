package cc.starsector.server.premain;

import java.lang.instrument.ClassFileTransformer;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;

import cc.starsector.compat.magic.MagicSeamRewriter;
import cc.starsector.server.transform.TransformRegistry;

/**
 * The MagicLib pilot seam's load-time applier: the {@link ClassFileTransformer} around {@link MagicSeamRewriter}.
 * Registered by {@link CcTransformPremain} when the host row {@code magicseam.enabled} resolves true, WITHOUT the
 * eager jar gate: MagicLib is a mod jar the game's script loader opens, never on {@code java.class.path}.
 *
 * <p>FAIL-OPEN, unlike the engine-class appliers (whose load-time drift halts the JVM): a third-party mod's class
 * that does not match, or a probe / self-check miss, or any Throwable, returns {@code null} so the ORIGINAL class
 * loads and the pilot keeps the contract route. Nothing escapes {@link #transform}. Each define of the target
 * writes ONE stdout row and sets the receipt sysprop {@code cc.magic.seam} to {@code applied:<16 hex>} or
 * {@code disarmed:<reason>} (the premain set {@code armed} before any define).
 *
 * <p>The hook probe is built from the DEFINING loader passed in here (resource lookups only), so the verdict is
 * the topology the patched class will actually link in.
 *
 *
 */
final class MagicSeamApplier implements ClassFileTransformer {

    /** The receipt before any define: the row is on and the applier is registered (the registry's one home). */
    static final String RECEIPT_ARMED = TransformRegistry.MAGIC_SEAM_RECEIPT_ARMED;
    /** The receipt when the host row resolved false: nothing registered. */
    static final String RECEIPT_OFF = TransformRegistry.MAGIC_SEAM_RECEIPT_OFF;

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        // the name compare first: every other class costs one String.equals on a compile-time constant
        if (!MagicSeamRewriter.TARGET.equals(className) || classBeingRedefined != null) {
            return null;
        }
        String jar = "unknown";
        try {
            jar = jarName(protectionDomain);
            MagicSeamRewriter.Result r = MagicSeamRewriter.rewrite(className, classfileBuffer,
                    MagicSeamRewriter.probeOf(loader));
            if (r == null) {
                return null;
            }
            publish(r.receipt(), r.applied() ? "applied sha=" + r.windowSha().substring(0, 16)
                    : "disarmed reason=" + r.reason(), jar);
            return r.applied() ? r.patched() : null;
        } catch (Throwable t) {
            try {
                String reason = MagicSeamRewriter.REASON_ERROR_PREFIX + MagicSeamRewriter.simpleName(t);
                publish(MagicSeamRewriter.RECEIPT_DISARMED + ":" + reason, "disarmed reason=" + reason, jar);
            } catch (Throwable ignored) {
                // a failure to report must not escape a class define either; the original class loads
            }
            return null;
        }
    }

    private static void publish(String receipt, String row, String jar) {
        System.setProperty(TransformRegistry.MAGIC_SEAM_RECEIPT_SYSPROP, receipt);
        System.out.println("[cc-premain] magicseam " + row + " jar=" + jar);
    }

    /** The defining jar's file name only (bounded), from the class's code source; {@code unknown} without one. */
    private static String jarName(ProtectionDomain domain) {
        CodeSource source = domain == null ? null : domain.getCodeSource();
        URL location = source == null ? null : source.getLocation();
        return MagicSeamRewriter.jarLabel(location == null ? null : location.getPath());
    }
}
