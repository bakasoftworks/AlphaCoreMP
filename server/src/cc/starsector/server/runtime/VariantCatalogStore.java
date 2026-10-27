package cc.starsector.server.runtime;

import cc.starsector.server.runtime.VariantDefinitionFrameCodec.ModuleMount;
import cc.starsector.server.runtime.VariantDefinitionFrameCodec.VariantDefinition;
import cc.starsector.server.runtime.VariantDefinitionFrameCodec.WeaponGroup;
import cc.starsector.server.runtime.VariantDefinitionFrameCodec.WeaponMount;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * W7c B2: the boot-time variant catalog — every deployed
 * {@code data/variants} definition (root ∪ the {@code npc/} subfolder, the W7d split) parsed with
 * TYPED org.json (the SAME jar the engine parses with, so the lenient parse and the
 * HashMap-backed key order are identical by construction — the D3 order law rides
 * {@code JSONObject.getNames()}) and PRE-ENCODED once into {@code VDEF} frames
 * ({@link VariantDefinitionFrameCodec}). The catalog is immutable post-boot — the pre-encoded
 * frames are the admission burst verbatim AND the natural golden-vector surface (goldens come
 * from this live producer).
 *
 * <p>Fail-closed per FILE, loud, never a boot failure (the {@link PickableVariants} posture — the
 * catalog is a delivery nicety in B2; every skipped definition stays kit-local): unreadable /
 * over-sized / unparseable / law-violating / over-frame-budget files SKIP with a line naming the
 * file and the reason. Duplicate variant ids keep the FIRST (root before {@code npc/}), loud.
 * The catalog stops LOUD at {@link VariantDefinitionFrameCodec#MAX_VARIANTS_TOTAL} — dropped
 * remainder counted, never silent (D5).
 *
 * <p>Security: inputs are LOCAL server disk files (owner-authored kit content),
 * never the wire; every field law is enforced by the codec's records at construction, and the
 * per-file byte guard bounds allocation. Output is DOWN-only wire surface.
 */
public final class VariantCatalogStore {

    /** Per-file read guard (a variant JSON is ~1–4 KB; anything near this is a config error). */
    public static final int MAX_FILE_BYTES = 262144;

    /** Pre-encoded VDEF frames, one per variant, scan order (root then {@code npc/}, each
     * case-insensitive filename order — deterministic across boots for the golden surface). */
    private final List<byte[]> frames;
    private final List<String> variantIds;
    /**
     * Per variant, the FILE-declared weapon groups as slot-id SETS in
     * declared order — the group-identity map's client-order ground truth (the client engine
     * builds its live groups in this order; reading the SERVER
     * engine's registry here instead would be circular — an in-process registry mutation is
     * exactly the branch-(a) suspect). Codec law already guarantees non-empty groups,
     * so declared order == both engines' live build order for fully-fitted variants.
     */
    private final java.util.Map<String, List<Set<String>>> declaredGroupSlotSets;
    /** Loadout key ({@link StockBuildRedirect#loadoutKey}) → every definition of that
     * loadout, scan order — the {@code /share} stock-redirect index. */
    private final java.util.Map<VariantDefinition, List<VariantDefinition>> byLoadout;

    private VariantCatalogStore(List<byte[]> frames, List<String> variantIds,
            java.util.Map<String, List<Set<String>>> declaredGroupSlotSets,
            java.util.Map<VariantDefinition, List<VariantDefinition>> byLoadout) {
        this.frames = frames;
        this.variantIds = variantIds;
        this.declaredGroupSlotSets = declaredGroupSlotSets;
        this.byLoadout = byLoadout;
    }

    /** The no-catalog instance (no variants root / legacy door): the burst emits nothing. */
    public static VariantCatalogStore empty() {
        return new VariantCatalogStore(Collections.emptyList(), Collections.emptyList(),
                Collections.emptyMap(), Collections.emptyMap());
    }

    /**
     * Scan ONE {@code data/variants} root: direct {@code *.variant} children plus the
     * {@code npc/} subfolder's (the engine loads exactly root + one subfolder level, W7d — but
     * only {@code npc/} is OURS to deliver; other subfolders are not part of the split).
     * A missing/unlistable root is LOUD-empty, never a throw.
     */
    public static VariantCatalogStore scan(File root, IdTables tables) {
        Objects.requireNonNull(root, "root");
        List<File> files = new ArrayList<File>();
        if (!listVariantFiles(root, files)) {
            System.out.println("variant-catalog scan_root_unlistable root=" + root);
            return empty();
        }
        listVariantFiles(new File(root, "npc"), files); // absent npc/ is a legal shape
        List<byte[]> frames = new ArrayList<byte[]>();
        List<String> ids = new ArrayList<String>();
        java.util.Map<String, List<Set<String>>> slotSets =
                new java.util.HashMap<String, List<Set<String>>>();
        java.util.Map<VariantDefinition, List<VariantDefinition>> byLoadout =
                new java.util.HashMap<VariantDefinition, List<VariantDefinition>>();
        Set<String> seen = new LinkedHashSet<String>();
        int dropped = 0;
        for (File file : files) {
            if (ids.size() >= VariantDefinitionFrameCodec.MAX_VARIANTS_TOTAL) {
                dropped++;
                continue;
            }
            try {
                if (file.length() > MAX_FILE_BYTES) {
                    throw new IllegalArgumentException("file exceeds " + MAX_FILE_BYTES
                            + " B (config error — a variant JSON is a few KB)");
                }
                String text = new String(Files.readAllBytes(file.toPath()),
                        StandardCharsets.UTF_8);
                VariantDefinition variant = parseVariant(new JSONObject(text));
                if (PickableVariants.isReservedCustomId(variant.variantId())) {
                    // W15 v0 step 3(d): the boot catalog never carries the server-minted id
                    // space — a kit file wearing it would shadow a live custom in every window.
                    System.out.println("variant-catalog skip_reserved_prefix file=" + file.getName()
                            + " id=" + variant.variantId());
                    continue;
                }
                if (!seen.add(variant.variantId())) {
                    System.out.println("variant-catalog skip_duplicate_id file=" + file.getName()
                            + " id=" + variant.variantId());
                    continue;
                }
                frames.add(VariantDefinitionFrameCodec.encode(variant, tables));
                ids.add(variant.variantId());
                List<Set<String>> groupSets = new ArrayList<Set<String>>();
                for (WeaponGroup group : variant.weaponGroups()) {
                    Set<String> slots = new LinkedHashSet<String>();
                    for (WeaponMount weapon : group.weapons()) {
                        slots.add(weapon.slotId());
                    }
                    groupSets.add(Collections.unmodifiableSet(slots));
                }
                slotSets.put(variant.variantId(), Collections.unmodifiableList(groupSets));
                StockBuildRedirect.index(byLoadout, variant);
            } catch (Exception bad) {
                System.out.println("variant-catalog skip file=" + file.getName()
                        + " error=" + bad.getClass().getSimpleName() + ": " + bad.getMessage());
            }
        }
        if (dropped > 0) {
            System.out.println("variant-catalog over_cap dropped=" + dropped + " cap="
                    + VariantDefinitionFrameCodec.MAX_VARIANTS_TOTAL);
        }
        System.out.println("variant-catalog scan root=" + root + " variants=" + ids.size());
        return new VariantCatalogStore(Collections.unmodifiableList(frames),
                Collections.unmodifiableList(ids),
                Collections.unmodifiableMap(slotSets), Collections.unmodifiableMap(byLoadout));
    }

    /**
     * The FILE-declared weapon groups for one variant as slot-id sets in
     * declared order, or null when the variant is not catalog-resident (vanilla-only id / legacy
     * door) — the caller's group-identity map then stays identity, today's behavior.
     */
    public List<Set<String>> declaredGroupSlotSetsOrNull(String variantId) {
        return variantId == null ? null : declaredGroupSlotSets.get(variantId);
    }

    /** Every catalog definition whose loadout equals {@code v}'s
     * ({@link StockBuildRedirect#loadoutKey}), scan order; empty when none or {@code v} is null. */
    public List<VariantDefinition> loadoutMatches(VariantDefinition v) {
        List<VariantDefinition> matches = v == null ? null : byLoadout.get(StockBuildRedirect.loadoutKey(v));
        return matches == null ? Collections.<VariantDefinition>emptyList() : Collections.unmodifiableList(matches);
    }

    /** False = the folder is unlistable (root only — a missing {@code npc/} is simply skipped). */
    private static boolean listVariantFiles(File folder, List<File> into) {
        File[] children = folder.listFiles();
        if (children == null) {
            return false;
        }
        List<File> picked = new ArrayList<File>();
        for (File child : children) {
            if (child.isFile() && child.getName().endsWith(".variant")) {
                picked.add(child);
            }
        }
        picked.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.getName(), b.getName()));
        into.addAll(picked);
        return true;
    }

    /**
     * One parsed file → the codec's validated definition. Key set = the W6 exporter's 12 keys +
     * the D6 additions ({@code sModdedBuiltIns}/{@code suppressedMods}/{@code tags}); absent
     * lists are empty, absent scalars default like the engine ctor. Weapons/modules walk
     * {@code getNames()} order (the D3 order law); an unknown weapon-group {@code mode} string
     * is fail-closed (skip the file loud), never guessed.
     */
    public static VariantDefinition parseVariant(JSONObject o) throws JSONException {
        return parseVariantChecked(o);
    }

    /**
     * W15 v0: one {@code .variant} TEXT → definition for callers OUTSIDE this
     * package that must not name org.json types themselves (the synthetic seat's {@code --vsub-file};
     * a catch type is resolved at class verification, so a bare-cp JVM would die before main).
     * A JSON fault surfaces as {@link IllegalArgumentException}.
     */
    public static VariantDefinition parseVariantText(String text) {
        try {
            return parseVariantChecked(new JSONObject(text));
        } catch (JSONException notVariantJson) {
            throw new IllegalArgumentException("not a lawful .variant: " + notVariantJson.getMessage(),
                    notVariantJson);
        }
    }

    /** Package seam: {@code WaveLoadoutInflater.splitBodies} parses the parent + its module bodies. */
    static VariantDefinition parseVariantChecked(JSONObject o) throws JSONException {
        JSONArray groupsJson = o.optJSONArray("weaponGroups");
        List<WeaponGroup> groups = new ArrayList<WeaponGroup>();
        if (groupsJson != null) {
            for (int g = 0; g < groupsJson.length(); g++) {
                JSONObject groupJson = groupsJson.getJSONObject(g);
                String mode = groupJson.optString("mode", "LINKED");
                boolean alternating = "ALTERNATING".equals(mode);
                if (!alternating && !"LINKED".equals(mode)) {
                    throw new IllegalArgumentException("weaponGroups[" + g
                            + "] mode unknown: '" + mode + "'");
                }
                JSONObject weaponsJson = groupJson.getJSONObject("weapons");
                List<WeaponMount> weapons = new ArrayList<WeaponMount>();
                String[] slots = JSONObject.getNames(weaponsJson);
                if (slots != null) {
                    for (String slot : slots) {
                        weapons.add(new WeaponMount(slot, weaponsJson.getString(slot)));
                    }
                }
                groups.add(new WeaponGroup(groupJson.optBoolean("autofire", false),
                        alternating, weapons));
            }
        }
        List<ModuleMount> modules = new ArrayList<ModuleMount>();
        JSONObject modulesJson = o.optJSONObject("modules");
        if (modulesJson != null) {
            String[] slots = JSONObject.getNames(modulesJson);
            if (slots != null) {
                for (String slot : slots) {
                    modules.add(new ModuleMount(slot, modulesJson.getString(slot)));
                }
            }
        }
        return new VariantDefinition(
                o.getString("variantId"),
                o.getString("hullId"),
                o.getString("displayName"),
                o.optBoolean("goalVariant", false),
                o.optInt("fluxVents", 0),
                o.optInt("fluxCapacitors", 0),
                stringList(o, "hullMods"),
                stringList(o, "permaMods"),
                stringList(o, "sMods"),
                stringList(o, "sModdedBuiltIns"),
                stringList(o, "suppressedMods"),
                stringList(o, "wings"),
                stringList(o, "tags"),
                groups,
                modules);
    }

    private static List<String> stringList(JSONObject o, String key) throws JSONException {
        JSONArray array = o.optJSONArray(key);
        List<String> values = new ArrayList<String>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                values.add(array.getString(i));
            }
        }
        return values;
    }

    /** Pre-encoded VDEF frames in scan order — the admission burst spools these verbatim. */
    public List<byte[]> frames() {
        return frames;
    }

    /** Variant ids in frame order (logging + the golden surface's identity row). */
    public List<String> variantIds() {
        return variantIds;
    }

    public int size() {
        return variantIds.size();
    }

    public boolean isEmpty() {
        return variantIds.isEmpty();
    }
}
