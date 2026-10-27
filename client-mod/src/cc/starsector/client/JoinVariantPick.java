package cc.starsector.client;

/**
 * The spawn follows the GUI's selected ship:
 * the load-time resolved join-window ship pick ("" = none), written ONCE by
 * {@link CcClientModPlugin} at application load (before any mission builds) and read by
 * {@link MatchMissionBuilder} on the mission-build thread so the OWN t=0 hull IS the pick — no
 * descriptor fly-in followed by the L5 alive re-field swap. Volatile is the whole contract.
 * <p>
 * A dependency-free holder: the game-free verify-match harness
 * compiles the builder from a hand-picked source list
 * that must never pull in the plugin (Global/engine-bound), so the seam lives here — the plugin
 * SETS, the builder READS. B-legal (no denied refs).
 */
final class JoinVariantPick {
    private static volatile String pick = "";

    private JoinVariantPick() {
    }

    /** Set once at load from {@code CurrentBuildLane.resolveVariantPick()}; null folds to "". */
    static void set(String resolvedOrEmpty) {
        pick = resolvedOrEmpty == null ? "" : resolvedOrEmpty;
    }

    /** The join-window ship pick as resolved at load; "" = none (descriptor row). */
    static String orEmpty() {
        return pick;
    }

    /**
     * D-FIRSTSHIP (the owner's kit window: pick
     * {@code grendel_Safetyoverride}, "does not resolve in this kit", conquest at t=0, snap):
     * the W7c B3 kit strip ships only descriptor-referenced {@code .variant} files, every other
     * server-published pickable rides the admission {@code VDEF} burst — so a resolvable pick
     * can still fail {@code getVariant} at mission build. The plugin installs this resolver at
     * load (beside {@link #set}); the builder calls it for a pick that does not resolve locally
     * and re-checks. Contract: fold the pick's wire-delivered definition into the engine registry
     * and return true when it now resolves; false = not delivered yet / cannot fold (the builder
     * then fields the neutral pod placeholder). Null = none installed (the game-free harness).
     */
    private static volatile java.util.function.Predicate<String> wireResolver;

    static void setWireResolver(java.util.function.Predicate<String> resolver) {
        wireResolver = resolver;
    }

    /** Ask the installed resolver to fold {@code pick} from the wire; false when none/failed. */
    static boolean resolveFromWire(String pick) {
        java.util.function.Predicate<String> resolver = wireResolver;
        if (resolver == null) {
            return false;
        }
        try {
            return resolver.test(pick);
        } catch (RuntimeException never) {
            return false; // player input never throws the mission build
        }
    }

    /** W15 v1: the plugin-installed {@code VACK} PEEK — the mission build's
     *  pre-combat line naming the custom-variant verdict already on hand (the builder's compile
     *  set is dependency-free by design, so the plugin reaches it through this holder like the
     *  resolver above). Null = none installed (the game-free harness). */
    private static volatile java.util.function.Supplier<String> customVerdictPeek;

    static void setCustomVerdictPeek(java.util.function.Supplier<String> peek) {
        customVerdictPeek = peek;
    }

    /** The custom-variant verdict phrase on hand, or a fixed "no peek installed" phrase. */
    static String customVerdictOnHand() {
        java.util.function.Supplier<String> peek = customVerdictPeek;
        if (peek == null) {
            return "custom-variant receipts: no peek installed";
        }
        try {
            String phrase = peek.get();
            return phrase == null ? "custom-variant receipts: peek returned null" : phrase;
        } catch (RuntimeException never) {
            return "custom-variant receipts: peek failed (" + never.getClass().getSimpleName() + ")";
        }
    }
}