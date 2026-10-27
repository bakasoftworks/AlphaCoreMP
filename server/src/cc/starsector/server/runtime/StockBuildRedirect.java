package cc.starsector.server.runtime;

import cc.starsector.server.runtime.VariantDefinitionFrameCodec.VariantDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A {@code /share} of an owned custom whose LOADOUT is
 * a {@code /ship}-pickable server build redirects the sharer to that build — a private reply, no
 * share, no broadcast (every peer can already pick it). Loadout equality = exact equality of the
 * NORMALISED record ({@link #loadoutKey}): the id, display name, goalVariant flag and tags are
 * blanked (an editor export differs from a kit file there by construction); hull, vents,
 * capacitors, every mod list, wings, weapon groups and modules must match. List order counts, so an
 * equivalent build listed in another order is a false NEGATIVE (a normal share), never a wrong
 * redirect. Pure law: {@link VariantCatalogStore} indexes at its boot scan, the door asks once per
 * {@code /share}. The reply names only server kit DATA (the pickable canonical id + the catalog's
 * display name), never the sharer's text.
 */
public final class StockBuildRedirect {

    /** Lawful placeholders for the blanked fields (the record's id + display-name laws). */
    private static final String KEY_ID = SubmittedVariantLedger.ID_PREFIX;
    private static final String KEY_NAME = "loadout";

    private StockBuildRedirect() {}

    /** {@code v} with the id, display name, goalVariant and tags blanked — the loadout identity. */
    public static VariantDefinition loadoutKey(VariantDefinition v) {
        return new VariantDefinition(KEY_ID, v.hullId(), KEY_NAME, false, v.fluxVents(), v.fluxCapacitors(),
                v.hullMods(), v.permaMods(), v.sMods(), v.sModdedBuiltIns(), v.suppressedMods(), v.wings(),
                List.of(), v.weaponGroups(), v.modules());
    }

    /** Index one catalog definition under its loadout key, scan order kept (root before {@code npc/}). */
    static void index(Map<VariantDefinition, List<VariantDefinition>> byLoadout, VariantDefinition stock) {
        byLoadout.computeIfAbsent(loadoutKey(stock), k -> new ArrayList<VariantDefinition>()).add(stock);
    }

    /**
     * The private redirect reply for a {@code /share} of {@code owned}, or null = share as usual:
     * {@code owned} null (not the sender's live accepted custom), no catalog / pickable set, no
     * loadout match, or no match {@code /ship} can pick (an {@code npc/}-only build) all fall
     * through. The FIRST pickable match in scan order names the reply; the pickable id must EQUAL the
     * catalog id (the pick lookup case-folds — a fold or stem/id mismatch never names another build).
     *
     */
    public static String replyOrNull(VariantDefinition owned, VariantCatalogStore catalog,
            PickableVariants pickable) {
        if (owned == null || catalog == null || pickable == null) {
            return null;
        }
        for (VariantDefinition stock : catalog.loadoutMatches(owned)) {
            String pickId = pickable.canonicalIdOrNull(stock.variantId());
            if (stock.variantId().equals(pickId)) {
                return ChatCommands.stockRedirectLine(stock.displayName(), pickId);
            }
        }
        return null;
    }
}
