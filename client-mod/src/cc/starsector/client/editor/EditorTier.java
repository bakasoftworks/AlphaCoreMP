package cc.starsector.client.editor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints;
import com.fs.starfarer.api.combat.WeaponAPI.AIHints;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.HullModSpecAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.loading.WithSourceMod;

import cc.starsector.client.PilotWatchLog;

/**
 * The editor's HIT-RATE filter: the server's
 * base filter + tier predicates, transcribed VERBATIM from
 * {@code server/src/cc/starsector/server/runtime/VariantPolicy.java} ({@code hullBase} …
 * {@code wingTier}, reason strings identical) and evaluated over the PUBLIC API facts the
 * server's {@code RealEngineVariantFacts} snapshots reflectively. The SERVER stays the arbiter
 * (its override file, its knob, its tier); this class only tells the SPA what the default
 * STANDARD tier will most likely admit, so a player rarely submits a refusal. A mirror, not a
 * shared class: the server codec that would carry the policy in is {@code java.io.Data*}-bound
 * (classloader-B denied) — drift between the two is a parity-test rider.
 *
 * <p>Mod provenance: the server walks every enabled mod's {@code data/hulls/skins/*.skin} from
 * disk (denied here); the build writes this mod's own skin ids into the jar resource
 * {@code mod-skins.txt} (see {@code client-mod/build.ps1}) and spreadsheet content grades by
 * {@code getSourceMod() != null} exactly like the server.
 */
final class EditorTier {
    enum Tier {
        STRICT, STANDARD, PERMISSIVE;

        static Tier parseOrDefault(String raw) {
            if (raw == null) {
                return STANDARD;
            }
            switch (raw.toLowerCase(Locale.ROOT)) {
                case "strict": return STRICT;
                case "permissive": return PERMISSIVE;
                default: return STANDARD;
            }
        }
    }

    // ---- census vocabularies (verbatim from the server's VariantPolicy)
    static final Set<String> MARKET_FACTION_TAGS = Set.of("hegemony", "heg_aux_bp", "XIV_bp", "ind",
            "independent", "sindrian_diktat", "lions_guard", "LG_bp", "luddic_church", "LC_bp",
            "luddic_path", "LP_bp", "merc", "persean", "pirates", "pirate_bp", "tritachyon", "base_bp");
    static final Set<String> NPC_WEAPON_TAGS = Set.of("threat", "dweller");
    static final Set<String> NPC_HULLMOD_TAGS = Set.of("threat", "dweller", "fragment", "shrouded");
    static final Set<String> NPC_WING_TAGS = Set.of("threat", "dweller", "swarm_fighter", "fragment");
    static final Set<String> BASE_DENIED_WEAPON_TYPES = Set.of("DECORATIVE", "SYSTEM", "LAUNCH_BAY",
            "STATION_MODULE", "BUILT_IN");

    /** D-mods allowed (the server knob {@code custom.dMods=allow} default). */
    static final boolean D_MODS_ALLOWED = true;

    private static volatile Set<String> modSkinIds;

    private EditorTier() {
    }

    // ------------------------------------------------------------------ refusals (null = admitted)

    static String hullRefusal(ShipHullSpecAPI h, Tier tier) {
        String base = hullBase(h);
        if (base != null) {
            return base;
        }
        if (modDefined(h, h.getHullId())) {
            return "mod_defined";
        }
        return hullTier(h, tier);
    }

    static String weaponRefusal(WeaponSpecAPI w, Tier tier) {
        String base = weaponBase(w);
        if (base != null) {
            return base;
        }
        if (modDefined(w, null)) {
            return "mod_defined";
        }
        return weaponTier(w, tier);
    }

    static String hullModRefusal(HullModSpecAPI m, Tier tier) {
        String base = hullModBase(m);
        if (base != null) {
            return base;
        }
        if (modDefined(m, null)) {
            return "mod_defined";
        }
        if (isDMod(m) && !D_MODS_ALLOWED) {
            return "dmod_knob";
        }
        return hullModTier(m, tier);
    }

    static String wingRefusal(FighterWingSpecAPI g, ShipHullSpecAPI fighterHullOrNull, Tier tier) {
        String base = wingBase(g);
        if (base != null) {
            return base;
        }
        if (modDefined(g, null)) {
            return "mod_defined";
        }
        return wingTier(g, fighterHullOrNull, tier);
    }

    // ------------------------------------------------------------------ base filters (every tier)

    static String hullBase(ShipHullSpecAPI h) {
        if (h.getHullSize() == null || "FIGHTER".equals(h.getHullSize().name())) {
            return "fighter";
        }
        Set<ShipTypeHints> hints = h.getHints();
        if (hints.contains(ShipTypeHints.STATION)) {
            return "station";
        }
        if (hasStationModuleSlot(h)) {
            return "station_base";
        }
        if (hints.contains(ShipTypeHints.MODULE) || h.getModuleAnchor() != null
                || h.getHullId().startsWith("module_")) {
            return "station_module";
        }
        if (h.isDefaultDHull() || h.getHullId().endsWith("_default_D")) {
            return "dhull_twin";
        }
        return null;
    }

    static String weaponBase(WeaponSpecAPI w) {
        WeaponType type = w.getType();
        String typeName = type == null ? "null" : type.name();
        if (BASE_DENIED_WEAPON_TYPES.contains(typeName)) {
            return "type_" + typeName.toLowerCase(Locale.ROOT);
        }
        if (w.getAIHints() != null && w.getAIHints().contains(AIHints.SYSTEM)) {
            return "hint_system";
        }
        return null;
    }

    static String hullModBase(HullModSpecAPI m) {
        if (m.isHiddenEverywhere()) {
            return "hidden_everywhere";
        }
        if (m.isHidden() && !isDMod(m)) {
            return "hidden";
        }
        return null;
    }

    static String wingBase(FighterWingSpecAPI g) {
        String variantId = g.getVariantId();
        if (variantId == null || variantId.isBlank()) {
            return "no_variant";
        }
        return null;
    }

    // ------------------------------------------------------------------ tier predicates

    private static String hullTier(ShipHullSpecAPI h, Tier tier) {
        if (tier == Tier.PERMISSIVE) {
            return null;
        }
        Set<ShipTypeHints> hints = h.getHints();
        Set<String> tags = tags(h.getTags());
        if (hints.contains(ShipTypeHints.UNBOARDABLE) && !tags.contains("auto_rec")) {
            return "unboardable";
        }
        if (tags.contains("monster")) {
            return "monster";
        }
        if (tags.contains("unrecoverable")) {
            return "unrecoverable";
        }
        if (tier == Tier.STANDARD) {
            return null;
        }
        if (hints.contains(ShipTypeHints.HIDE_IN_CODEX) || tags.contains("hide_in_codex")) {
            return "hide_in_codex";
        }
        if ("shuttlepod".equals(h.getHullId())) {
            return "codex_skips";
        }
        if (tags.contains("restricted")) {
            return "restricted";
        }
        List<String> builtIns = h.getBuiltInMods();
        if (builtIns != null && builtIns.contains("automated")) {
            return "automated";
        }
        if (!marketFielded(tags)) {
            return "not_market_fielded";
        }
        return null;
    }

    private static String weaponTier(WeaponSpecAPI w, Tier tier) {
        if (tier == Tier.PERMISSIVE) {
            return null;
        }
        Set<String> tags = tags(w.getTags());
        boolean noDrop = tags.contains("no_drop");
        boolean noSell = tags.contains("no_sell");
        if (noDrop && noSell && !anyBpTag(tags)) {
            return "no_drop_no_sell";
        }
        if (noDrop && intersects(tags, NPC_WEAPON_TAGS)) {
            return "npc_only";
        }
        if (tier == Tier.STANDARD) {
            return null;
        }
        if (tags.contains("hide_in_codex")) {
            return "hide_in_codex";
        }
        if (tags.contains("restricted")) {
            return "restricted";
        }
        if (!marketFielded(tags)) {
            return "not_market_fielded";
        }
        if (noSell && noDrop) {
            return "no_sell_no_drop";
        }
        return null;
    }

    private static String hullModTier(HullModSpecAPI m, Tier tier) {
        if (tier == Tier.PERMISSIVE) {
            return null;
        }
        Set<String> tags = tags(m.getTags());
        if (intersects(tags, NPC_HULLMOD_TAGS)) {
            return "npc_faction";
        }
        if (tier == Tier.STANDARD) {
            return null;
        }
        if (isDMod(m)) {
            return "dmod";
        }
        if (tags.contains("hide_in_codex")) {
            return "hide_in_codex";
        }
        if (tags.contains("restricted")) {
            return "restricted";
        }
        if (tags.contains("no_drop") && !m.isAlwaysUnlocked()) {
            return "no_drop_gated";
        }
        return null;
    }

    private static String wingTier(FighterWingSpecAPI g, ShipHullSpecAPI fighterHullOrNull, Tier tier) {
        if (tier == Tier.PERMISSIVE) {
            return null;
        }
        Set<String> tags = tags(g.getTags());
        if (tags.contains("no_drop") && tags.contains("no_sell") && !anyBpTag(tags)) {
            return "no_drop_no_sell";
        }
        if (intersects(tags, NPC_WING_TAGS)) {
            return "npc_swarm";
        }
        if (tier == Tier.STANDARD) {
            return null;
        }
        boolean fighterHidden = fighterHullOrNull != null
                && fighterHullOrNull.getHints().contains(ShipTypeHints.HIDE_IN_CODEX);
        if (tags.contains("hide_in_codex") || fighterHidden) {
            return "hide_in_codex";
        }
        if (tags.contains("restricted")) {
            return "restricted";
        }
        if (!marketFielded(tags)) {
            return "not_market_fielded";
        }
        return null;
    }

    // ------------------------------------------------------------------ facts helpers

    static boolean isDMod(HullModSpecAPI m) {
        return m.hasTag("dmod");
    }

    static boolean hasStationModuleSlot(ShipHullSpecAPI h) {
        for (WeaponSlotAPI slot : h.getAllWeaponSlotsCopy()) {
            if (slot.isStationModule()) {
                return true;
            }
        }
        return false;
    }

    /** The server's {@code RealEngineVariantFacts} fittable-slot rule verbatim. */
    static boolean isFittableSlot(WeaponSlotAPI slot) {
        return !slot.isStationModule() && !slot.isBuiltIn() && !slot.isDecorative()
                && !slot.isSystemSlot() && !slot.isHidden() && slot.isWeaponSlot()
                && slot.getWeaponType() != WeaponType.LAUNCH_BAY;
    }

    static boolean modDefined(WithSourceMod spec, String hullIdOrNull) {
        if (hullIdOrNull != null && modSkinIds().contains(hullIdOrNull)) {
            return true;
        }
        try {
            return spec.getSourceMod() != null;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    static boolean marketFielded(Set<String> tags) {
        return intersects(tags, MARKET_FACTION_TAGS) || anyBpTag(tags);
    }

    static boolean anyBpTag(Set<String> tags) {
        for (String t : tags) {
            if (t.endsWith("_bp")) {
                return true;
            }
        }
        return false;
    }

    private static boolean intersects(Set<String> a, Set<String> b) {
        for (String t : a) {
            if (b.contains(t)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> tags(Collection<String> raw) {
        return raw == null ? Set.of() : new HashSet<>(raw);
    }

    /** The build-written {@code mod-skins.txt} (one skinHullId per line); empty + one log row when absent. */
    static Set<String> modSkinIds() {
        Set<String> ids = modSkinIds;
        if (ids != null) {
            return ids;
        }
        Set<String> loaded = new HashSet<>();
        try {
            InputStream in = EditorTier.class.getResourceAsStream("mod-skins.txt");
            if (in == null) {
                PilotWatchLog.info("editor tier: mod-skins.txt resource ABSENT — mod skins grade by their base hull");
            } else {
                try {
                    String text = new String(readAll(in, 65536), StandardCharsets.UTF_8);
                    for (String line : text.split("\n")) {
                        String id = line.trim();
                        if (!id.isEmpty() && !id.startsWith("#")) {
                            loaded.add(id);
                        }
                    }
                } finally {
                    in.close();
                }
            }
        } catch (IOException | RuntimeException bad) {
            PilotWatchLog.info("editor tier: mod-skins.txt unreadable (" + bad.getClass().getSimpleName()
                    + ") — mod skins grade by their base hull");
        }
        modSkinIds = Set.copyOf(loaded);
        return modSkinIds;
    }

    /** Hand-grown byte[] read (no ByteArrayOutputStream — classloader-B denied); {@code cap} bytes at most. */
    static byte[] readAll(InputStream in, int cap) throws IOException {
        byte[] acc = new byte[8192];
        int len = 0;
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (len + n > cap) {
                throw new IOException("stream exceeds " + cap + " bytes");
            }
            if (len + n > acc.length) {
                byte[] bigger = new byte[Math.max(acc.length * 2, len + n)];
                System.arraycopy(acc, 0, bigger, 0, len);
                acc = bigger;
            }
            System.arraycopy(buf, 0, acc, len, n);
            len += n;
        }
        byte[] out = new byte[len];
        System.arraycopy(acc, 0, out, 0, len);
        return out;
    }
}
