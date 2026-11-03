package cc.starsector.compat.magic;

import java.util.Set;

/**
 * The ONE home of the MagicLib builds a client's load-time agent may patch: the lowercase sha256 of each input
 * {@code MagicSubsystem.class} whose rewrite output was verified clean on Java 17 and 25 before it was listed. A
 * client meets every other build with a loud {@code disarmed:not_allowlisted} and the original class (the serve's
 * premain arms without a list), so a new MagicLib release stays unpatched on clients until a kit ships its hash.
 *
 * <p>Readers: the agent's transformer ({@link MagicSeamAgentTransformer}) passes {@link #SHAS} to
 * {@link MagicSeamRewriter#rewrite(String, byte[], MagicSeamRewriter.HookProbe, Set)}; the patch rows key their
 * per-build output goldens off these constants and fail when the two sets differ, so an entry can never be listed
 * without a verified golden.
 *
 *
 */
public final class MagicSeamAllowlist {

    /** MagicLib 1.4.5. */
    public static final String MAGICLIB_1_4_5 = "9d7162aebd9954a2ba2179f471684a83987d256c3ffeecbf16eca230f720845e";
    /** MagicLib 1.5.1. */
    public static final String MAGICLIB_1_5_1 = "a64fb1ecd615179fae84dd2b25c3268327f3a24ee8fa22c3aa0a55a6535cf417";
    /** MagicLib 1.5.4 through 1.5.6 (one class, byte-identical across the three). */
    public static final String MAGICLIB_1_5_4_TO_1_5_6 =
            "e2815e57069f8146fe915c9cae74d4fd97e958b041f4ad8bafe89bc43d395c0a";
    /** MagicLib 1.5.8-dev14. */
    public static final String MAGICLIB_1_5_8_DEV14 =
            "0b9aba6f68566ab0d0a5724068a971c03788921382ae525644e06b41da4c9b6c";

    /** Every allowed input sha256 (64 lowercase hex, the rewriter's own {@code sha256} spelling); unmodifiable. */
    public static final Set<String> SHAS = Set.of(MAGICLIB_1_4_5, MAGICLIB_1_5_1, MAGICLIB_1_5_4_TO_1_5_6,
            MAGICLIB_1_5_8_DEV14);

    private MagicSeamAllowlist() {
    }
}
