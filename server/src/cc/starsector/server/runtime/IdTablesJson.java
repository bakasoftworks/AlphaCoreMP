package cc.starsector.server.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The pure JSON grammar half — BUNDLED into the client mod jar
 * (classloader B: no {@code java.io.File} / {@code java.nio.file} here, ever) so the in-game plugin
 * can render its boot table into {@code saves/common/cc_variants/id-tables.json} for the game-free
 * join page's VCHK dictionary; the file half is {@link IdTablesJsonFile}.
 *
 * <p>Shape: {@code {"v":1,"vtab":"<md5>","hulls":[..],"weapons":[..],"hullMods":[..],"wings":[..],
 * "slots":{"<hull>":["WS 001",..]},"builtIns":{"<hull>":{"<slot>":"<weapon>"}}}}. Reading is
 * fail-closed: the file's {@code vtab} must equal the digest recomputed from its lists (a hand-edit
 * or a partial write never yields a table that disagrees with its own advertised digest).
 */
public final class IdTablesJson { // bundled: no java.io.File / java.nio.file reference

    public static final int JSON_VERSION = 1;

    private IdTablesJson() {
    }

    public static String render(IdTables tables) {
        JSONObject o = new JSONObject();
        try {
            o.put("v", JSON_VERSION);
            o.put("vtab", tables.vtab());
            o.put("hulls", new JSONArray(tables.ids(IdTables.Kind.HULL)));
            o.put("weapons", new JSONArray(tables.ids(IdTables.Kind.WEAPON)));
            o.put("hullMods", new JSONArray(tables.ids(IdTables.Kind.HULLMOD)));
            o.put("wings", new JSONArray(tables.ids(IdTables.Kind.WING)));
            JSONObject slots = new JSONObject();
            for (Map.Entry<String, List<String>> e : tables.slotOrderByHull().entrySet()) {
                slots.put(e.getKey(), new JSONArray(e.getValue()));
            }
            o.put("slots", slots);
            JSONObject builtIns = new JSONObject();
            for (Map.Entry<String, Map<String, String>> e : tables.builtInWeaponsByHull().entrySet()) {
                JSONObject pairs = new JSONObject();
                for (Map.Entry<String, String> p : e.getValue().entrySet()) {
                    pairs.put(p.getKey(), p.getValue());
                }
                builtIns.put(e.getKey(), pairs);
            }
            o.put("builtIns", builtIns);
            return o.toString(1);
        } catch (JSONException impossible) {
            throw new IllegalStateException("id-tables JSON render failed", impossible);
        }
    }

    /** Parse + rebuild; THROWS {@link IllegalArgumentException} on shape, version or digest disagreement. */
    public static IdTables parse(String text) {
        try {
            JSONObject o = new JSONObject(text);
            int v = o.getInt("v");
            if (v != JSON_VERSION) {
                throw new IllegalArgumentException("id-tables JSON version " + v + " (this build reads "
                        + JSON_VERSION + ")");
            }
            String vtab = o.getString("vtab");
            Map<String, List<String>> slots = new LinkedHashMap<>();
            JSONObject slotsObj = o.optJSONObject("slots");
            if (slotsObj != null) {
                for (String hull : JSONObject.getNames(slotsObj) == null ? new String[0] : JSONObject.getNames(slotsObj)) {
                    slots.put(hull, strings(slotsObj.getJSONArray(hull)));
                }
            }
            Map<String, Map<String, String>> builtIns = new LinkedHashMap<>();
            JSONObject builtInsObj = o.optJSONObject("builtIns");
            if (builtInsObj != null) {
                for (String hull : JSONObject.getNames(builtInsObj) == null ? new String[0] : JSONObject.getNames(builtInsObj)) {
                    JSONObject pairs = builtInsObj.getJSONObject(hull);
                    Map<String, String> m = new LinkedHashMap<>();
                    for (String slot : JSONObject.getNames(pairs) == null ? new String[0] : JSONObject.getNames(pairs)) {
                        m.put(slot, pairs.getString(slot));
                    }
                    builtIns.put(hull, m);
                }
            }
            IdTables tables = IdTables.of(strings(o.getJSONArray("hulls")), strings(o.getJSONArray("weapons")),
                    strings(o.getJSONArray("hullMods")), strings(o.getJSONArray("wings")), slots, builtIns);
            if (!tables.vtab().equals(vtab)) {
                throw new IllegalArgumentException("id-tables JSON advertises vtab=" + vtab
                        + " but its lists digest to " + tables.vtab() + " (edited or partial file)");
            }
            return tables;
        } catch (JSONException bad) {
            throw new IllegalArgumentException("id-tables JSON malformed: " + bad.getMessage(), bad);
        }
    }

    private static List<String> strings(JSONArray array) throws JSONException {
        List<String> out = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            out.add(array.getString(i));
        }
        return out;
    }
}
