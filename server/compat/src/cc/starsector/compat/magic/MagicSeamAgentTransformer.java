package cc.starsector.compat.magic;

import java.lang.instrument.ClassFileTransformer;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;

/**
 * The client agent's load-time applier: the {@link ClassFileTransformer} around {@link MagicSeamRewriter}, the
 * same shape as the serve premain's applier, plus the input allowlist ({@link MagicSeamAllowlist#SHAS}): a client
 * patches only a MagicLib build whose rewrite was verified ahead of time, never an unseen one.
 *
 * <p>Loaded ONLY through the agent's private child-first loader (never the system path), so the ASM it links is
 * the agent jar's own copy even when another agent or mod puts a different ASM on the system class path. It also
 * rides the serve premain jar with the rest of this package, where nothing instantiates it.
 *
 * <p>FAIL-OPEN: a class that does not match, a non-allowlisted input, a probe / self-check miss, or any Throwable
 * returns {@code null}, so the ORIGINAL class loads and the client keeps the mod's own AI for that subsystem.
 * Nothing escapes {@link #transform}. Each define of the target sets {@link #RECEIPT_SYSPROP} to
 * {@code applied:<16 hex>} or {@code disarmed:<reason>} and writes ONE stdout row (the agent's premain set
 * {@code armed} before any define); the client mod reads the receipt for its HELLO report.
 *
 *
 */
public final class MagicSeamAgentTransformer implements ClassFileTransformer {

    /** The receipt sysprop; the same spelling as the serve's registry constant and the client mod's reader. */
    public static final String RECEIPT_SYSPROP = "cc.magic.seam";
    /** The row prefix: the agent's own tag, so a client log tells the agent's row from the serve premain's. */
    public static final String ROW_PREFIX = "[cc-compat-agent] magicseam ";

    /** Public no-arg: the agent's premain instantiates this class reflectively, behind its private loader. */
    public MagicSeamAgentTransformer() {
    }

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
                    MagicSeamRewriter.probeOf(loader), MagicSeamAllowlist.SHAS);
            if (r == null) {
                return null;
            }
            publish(r.receipt(), r.applied() ? "applied sha=" + r.windowSha().substring(0, 16)
                    : "disarmed reason=" + r.reason(), jar);
            return r.applied() ? r.patched() : null;
        } catch (Throwable t) {
            try {
                // the throw's own simple name, never a call into the rewriter: its class may be what failed to link
                String kind = t.getClass().getSimpleName();
                String reason = MagicSeamRewriter.REASON_ERROR_PREFIX + (kind.isEmpty() ? "Throwable" : kind);
                publish(MagicSeamRewriter.RECEIPT_DISARMED + ":" + reason, "disarmed reason=" + reason, jar);
            } catch (Throwable ignored) {
                // a failure to report must not escape a class define either; the original class loads
            }
            return null;
        }
    }

    private static void publish(String receipt, String row, String jar) {
        System.setProperty(RECEIPT_SYSPROP, receipt);
        System.out.println(ROW_PREFIX + row + " jar=" + jar);
    }

    /** The defining jar's file name only (bounded), from the class's code source; {@code unknown} without one. */
    private static String jarName(ProtectionDomain domain) {
        CodeSource source = domain == null ? null : domain.getCodeSource();
        URL location = source == null ? null : source.getLocation();
        return MagicSeamRewriter.jarLabel(location == null ? null : location.getPath());
    }
}
