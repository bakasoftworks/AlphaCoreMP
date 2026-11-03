package cc.starsector.client;

/**
 * The realized-loadout variant id, client half. A mod hullmod can roll a hull's slot weapons inside
 * fleet-member creation, each engine on its own Random, so two engines building the same stock
 * variant can field different weapons. The server ships the loadout it realized as a variant
 * definition named {@code cc_real_<goal>_<hex8>}: {@code <goal>} = the stock (goal) variant id
 * verbatim, which may itself hold underscores; {@code <hex8>} = exactly 8 lowercase hex chars. The
 * minted id names what the engine BUILDS (spawn, re-field, the registry row); every consumer keyed
 * on boot-catalog variant ids (the curated officer table, the all-skills captain mint) takes
 * {@link #goalOf}, as the server's wave inflater keys the authored goal id for those reads.
 * Fail closed: an id that does not parse is NOT realized and is its own goal. java.lang-only so the
 * selftest drives it headless.
 *
 *
 */
public final class RealizedVariantId {

    /** The minted id's prefix; the two trees spell it identically. */
    public static final String PREFIX = "cc_real_";
    /** The minted id's suffix length: exactly 8 lowercase hex chars after the final underscore. */
    public static final int HEX_LEN = 8;

    private RealizedVariantId() {
    }

    /**
     * True when {@code variantId} has the minted shape: the prefix, a non-empty goal, an underscore,
     * then exactly {@link #HEX_LEN} chars of {@code [0-9a-f]}. Null, a bare prefix, an empty goal,
     * uppercase hex or a short / long suffix all read false.
     */
    public static boolean isRealized(String variantId) {
        if (variantId == null || !variantId.startsWith(PREFIX)) {
            return false;
        }
        int n = variantId.length();
        if (n < PREFIX.length() + 1 + 1 + HEX_LEN || variantId.charAt(n - HEX_LEN - 1) != '_') {
            return false;
        }
        for (int i = n - HEX_LEN; i < n; i++) {
            char c = variantId.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    /** The goal (stock) variant id of a minted id; any other id (null included) is returned as-is. */
    public static String goalOf(String variantId) {
        return isRealized(variantId)
                ? variantId.substring(PREFIX.length(), variantId.length() - HEX_LEN - 1)
                : variantId;
    }
}
