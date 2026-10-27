package cc.starsector.client.net;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The client's {@code .variant} JSON → {@link ClientVariantDefinition} parse law (W15 v0)
 * — a field-for-field mirror of the server's
 * {@code VariantCatalogStore.parseVariantChecked}: {@code weapons} /
 * {@code modules} objects walked in {@code JSONObject.getNames()} order (the D3 order law that
 * makes both sides' bytes identical), group {@code mode} LINKED|ALTERNATING fail-closed,
 * {@code goalVariant}/{@code fluxVents}/{@code fluxCapacitors} defaulted, every list optional.
 * Game-free (org.json ships in the game's {@code json.jar} and is loader-B-clean by standing
 * evidence — {@code VariantDefinitionLane} already imports it), so the parity verifier compiles
 * it without the engine. Every violation surfaces as {@link IllegalArgumentException} (the
 * record's own laws + the JSON shape) — the caller decides loud-vs-soft.
 */
public final class ClientVariantJson {
    /** Mirrors {@code VariantCatalogStore.MAX_FILE_BYTES} (the 256 KB text guard). */
    public static final int MAX_TEXT_CHARS = 256 * 1024;

    private ClientVariantJson() {
    }

    /** Parse one {@code .variant} document; throws {@link IllegalArgumentException} on any law. */
    public static ClientVariantDefinition parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("variant text must not be null");
        }
        if (text.length() > MAX_TEXT_CHARS) {
            throw new IllegalArgumentException("variant text exceeds " + MAX_TEXT_CHARS
                    + " chars: " + text.length());
        }
        try {
            return parseChecked(new JSONObject(text));
        } catch (JSONException badJson) {
            throw new IllegalArgumentException("variant JSON invalid: " + badJson.getMessage(),
                    badJson);
        }
    }

    private static ClientVariantDefinition parseChecked(JSONObject o) throws JSONException {
        JSONArray groupsJson = o.optJSONArray("weaponGroups");
        List<ClientVariantDefinition.WeaponGroup> groups = new ArrayList<>();
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
                List<ClientVariantDefinition.WeaponMount> weapons = new ArrayList<>();
                String[] slots = JSONObject.getNames(weaponsJson);
                if (slots != null) {
                    for (String slot : slots) {
                        weapons.add(new ClientVariantDefinition.WeaponMount(slot,
                                weaponsJson.getString(slot)));
                    }
                }
                groups.add(new ClientVariantDefinition.WeaponGroup(
                        groupJson.optBoolean("autofire", false), alternating, weapons));
            }
        }
        List<ClientVariantDefinition.ModuleMount> modules = new ArrayList<>();
        JSONObject modulesJson = o.optJSONObject("modules");
        if (modulesJson != null) {
            String[] slots = JSONObject.getNames(modulesJson);
            if (slots != null) {
                for (String slot : slots) {
                    modules.add(new ClientVariantDefinition.ModuleMount(slot,
                            modulesJson.getString(slot)));
                }
            }
        }
        return new ClientVariantDefinition(
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
        List<String> values = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                values.add(array.getString(i));
            }
        }
        return values;
    }
}
