package cc.starsector.client.editor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;

import cc.starsector.client.PilotWatchLog;
import cc.starsector.client.net.ClientVariantDefinition;
import cc.starsector.client.net.ClientVariantJson;

/**
 * The dev-stage-only "Mod catalog" lane — the owner
 * promotes an editor build into the mod's shipped {@code data/variants/} (or marks one for removal)
 * WITHOUT a second kit. Armed ONLY by {@code -Dcc.editor.catalogdir} ({@code render-editor.ps1 -Mode
 * launch} on the dev box; every shipped launch line leaves it unset ⇒ {@link #armed()} false ⇒ every
 * {@code catalog*} route answers 404 and the SPA never draws the card). The game writes MARKERS only —
 * {@code saves/common/cc_variants/_catalog/<stem>.variant|.promote|.remove} + the pending index — and
 * the roll's marker-applier script (dev box) validates and applies them into
 * the repo at roll time: the GAME's catalog stays boot-loaded (design law 9, no hot reload); a promotion-armed
 * WATCH serve reads the same pending index live. {@code cancel} (item (9) Undo) withdraws a marker.
 * <p>
 * Laws: every stem passes the id law BEFORE any file access and the path
 * is composed only as {@code cc_variants/_catalog/<stem>.<kind>}; the mod list is the GENERATED
 * {@code <catalogdir>/_index.txt} read over the proven {@code file:} lane (java.io.File is B-denied,
 * so there is no directory listing in-game), cap {@value #MOD_CAP}; ≤ {@value #PENDING_CAP} pending
 * markers; a promote body = the {@code /save} grammar, the oracle's own document re-read under the
 * reader's law before it touches disk; names stay DATA (the log sanitizes, the SPA paints textContent).
 */
final class EditorCatalog {
    static final String PROPERTY = "cc.editor.catalogdir";
    static final String INDEX_FILE = "_index.txt";
    static final String PENDING_NAME = "cc_variants/_catalog/index";
    static final int MOD_CAP = 200;
    static final int PENDING_CAP = 32;
    static final int INDEX_MAX_BYTES = 64 * 1024;

    private EditorCatalog() {
    }

    /** True only on the dev stage (the property set by render-editor.ps1); the routes 404 otherwise. */
    static boolean armed() {
        String d = System.getProperty(PROPERTY);
        return d != null && !d.trim().isEmpty();
    }

    /** {@code GET catalog}: the mod list (stem, npc flag, its pending kind) + every pending marker. */
    static JSONObject list() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("armed", true);
        JSONObject pending = pendingRead(Global.getSettings());
        JSONArray rows = new JSONArray();
        for (String[] m : modIndex()) {
            JSONObject r = new JSONObject();
            r.put("id", m[0]);
            r.put("npc", "npc".equals(m[1]));
            String kind = has(pending, "remove", m[0]) ? "remove" : has(pending, "promote", m[0]) ? "promote" : null;
            if (kind != null) {
                r.put("pending", kind);
            }
            rows.put(r);
        }
        o.put("mods", rows);
        JSONArray pend = new JSONArray();
        for (String kind : new String[] { "promote", "remove" }) {
            JSONArray arr = pending.optJSONArray(kind);
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject p = new JSONObject();
                String stem = arr.optString(i, "");
                p.put("id", stem);
                p.put("kind", kind);
                String why = rejectRead(Global.getSettings(), stem);
                if (why != null) {
                    p.put("reject", why);
                }
                pend.put(p);
            }
        }
        o.put("pending", pend);
        o.put("pendingCap", PENDING_CAP);
        return o;
    }

    /** {@code POST catalog/promote}: the current build (the /save grammar) → {@code _catalog/<stem>.variant} + {@code .promote}. */
    static JSONObject promote(ClientVariantDefinition def) throws JSONException, IOException {
        return promote(def, null);
    }

    /**
     * {@code POST catalog/<stem>/save} — the SAVE button on a
     * mod catalog variant, so it updates without a load / update / delete-old / save-new round trip: the current build written
     * as a promotion marker UNDER AN EXISTING listed stem — the roll's applier updates that mod variant in place. The
     * stem must be listed and keep its hull (a hull change = a new build: promote it instead).
     */
    static JSONObject promote(ClientVariantDefinition def, String targetStem) throws JSONException, IOException {
        ShipHullSpecAPI hull = EditorOracle.hullOrNull(def.hullId());
        if (hull == null) {
            throw new IllegalArgumentException("unknown hull");
        }
        String stem = EditorText.saveStem(hull.getHullId(), def.displayName());
        if (targetStem != null) {
            if (!EditorText.isId(targetStem)) {
                throw new IllegalArgumentException("bad catalog id");
            }
            boolean listed = false;
            for (String[] m : modIndex()) {
                if (m[0].equals(targetStem)) {
                    listed = true;
                    break;
                }
            }
            if (!listed) {
                return refuse("not a mod catalog variant");
            }
            com.fs.starfarer.api.combat.ShipVariantAPI current = EditorOracle.registeredVariant(targetStem);
            if (current != null && !hull.getHullId().equals(current.getHullSpec().getHullId())) {
                return refuse("hull changed (" + EditorText.sanitize(current.getHullSpec().getHullId(), 64) + " -> "
                        + EditorText.sanitize(hull.getHullId(), 64) + ") - promote it as a new build instead");
            }
            stem = targetStem;
        }
        ClientVariantDefinition canonical = new ClientVariantDefinition(stem, def.hullId(),
                def.displayName(), false, def.fluxVents(), def.fluxCapacitors(), def.hullMods(),
                def.permaMods(), def.sMods(), def.sModdedBuiltIns(), def.suppressedMods(),
                def.wings(), def.tags(), def.weaponGroups(), def.modules());
        String text = EditorOracle.variantDocument(canonical).toString(2);
        ClientVariantDefinition reparsed = ClientVariantJson.parse(text); // our own output under the reader's law
        if (!stem.equals(reparsed.variantId())) {
            throw new IllegalStateException("promote document stem drift");
        }
        SettingsAPI settings = Global.getSettings();
        JSONObject pending = pendingRead(settings);
        if (!has(pending, "promote", stem) && count(pending) >= PENDING_CAP) {
            return refuse("pending markers at the cap (" + PENDING_CAP + ") - roll first");
        }
        settings.writeTextFileToCommon("cc_variants/_catalog/" + stem + ".variant", text);
        settings.writeTextFileToCommon("cc_variants/_catalog/" + stem + ".promote",
                marker(stem, "promote", hull.getHullId(), def.displayName()));
        pendingSet(settings, pending, stem, "promote");
        PilotWatchLog.info("editor catalog promote: id=" + stem + " hull="
                + EditorText.sanitize(def.hullId(), 64) + " bytes=" + text.length());
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("id", stem);
        o.put("kind", "promote");
        o.put("bytes", text.length());
        o.put("file", "saves/common/cc_variants/_catalog/" + stem + ".variant.data");
        return o;
    }

    /**
     * {@code GET catalog/<stem>}: a listed mod
     * variant as a DRAFT document — the {@code GET server/<id>} shape ({@link EditorOracle#serverBuildLoad}:
     * the game's own registry, read-only, no file access); null when the index does not list the stem or the
     * game holds no such variant. The stem passes the id law BEFORE the index is read.
     */
    static JSONObject load(String stem) throws JSONException {
        if (!EditorText.isId(stem)) {
            throw new IllegalArgumentException("bad catalog id");
        }
        for (String[] m : modIndex()) {
            if (m[0].equals(stem)) {
                return EditorOracle.serverBuildLoad(stem);
            }
        }
        return null;
    }

    /** {@code POST catalog/<stem>/remove}: a stem the mod index lists → {@code _catalog/<stem>.remove}. */
    static JSONObject remove(String stem) throws JSONException, IOException {
        if (!EditorText.isId(stem)) {
            throw new IllegalArgumentException("bad catalog id");
        }
        boolean listed = false;
        for (String[] m : modIndex()) {
            if (m[0].equals(stem)) {
                listed = true;
                break;
            }
        }
        if (!listed) {
            return refuse("not a mod catalog variant");
        }
        SettingsAPI settings = Global.getSettings();
        JSONObject pending = pendingRead(settings);
        if (!has(pending, "remove", stem) && count(pending) >= PENDING_CAP) {
            return refuse("pending markers at the cap (" + PENDING_CAP + ") - roll first");
        }
        settings.writeTextFileToCommon("cc_variants/_catalog/" + stem + ".remove", marker(stem, "remove", "", ""));
        pendingSet(settings, pending, stem, "remove");
        PilotWatchLog.info("editor catalog remove: id=" + stem);
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("id", stem);
        o.put("kind", "remove");
        return o;
    }

    /**
     * {@code POST catalog/<stem>/cancel} = the card's UNDO: the stem leaves both pending lists (the index
     * rewritten without it). No file is deleted — the roll's applier and a promotion-armed serve read only the index
     * lists, so a withdrawn marker's files are inert. Nothing pending under the stem ⇒ refused (422).
     */
    static JSONObject cancel(String stem) throws JSONException, IOException {
        if (!EditorText.isId(stem)) {
            throw new IllegalArgumentException("bad catalog id");
        }
        SettingsAPI settings = Global.getSettings();
        JSONObject pending = pendingRead(settings);
        String was = has(pending, "remove", stem) ? "remove" : has(pending, "promote", stem) ? "promote" : null;
        if (was == null) {
            return refuse("nothing pending for that stem");
        }
        pendingSet(settings, pending, stem, null);
        PilotWatchLog.info("editor catalog cancel: id=" + stem + " was=" + was);
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("id", stem);
        o.put("kind", "cancel");
        o.put("was", was);
        return o;
    }

    // ---- the mod index: <catalogdir>/_index.txt, one stem per line, "npc/" prefix = data/variants/npc/ ----

    /** Each row = {stem, "root" | "npc"}; unreadable / unarmed = empty (logged, never a throw). */
    static List<String[]> modIndex() {
        List<String[]> rows = new ArrayList<>();
        String dir = System.getProperty(PROPERTY);
        if (dir == null || dir.trim().isEmpty()) {
            return rows;
        }
        InputStream in = null;
        try {
            // the webdir idiom (EditorRoutes.asset): the `file:` lane, the name never request-derived
            in = new java.net.URL("file:/" + dir.trim().replace('\\', '/') + "/" + INDEX_FILE).openStream();
            String text = new String(EditorTier.readAll(in, INDEX_MAX_BYTES), StandardCharsets.UTF_8);
            for (String raw : text.split("\n")) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String where = "root";
                String stem = line;
                if (line.startsWith("npc/")) {
                    where = "npc";
                    stem = line.substring(4);
                }
                if (!EditorText.isId(stem)) {
                    continue;
                }
                boolean dup = false;
                for (String[] r : rows) {
                    if (r[0].equals(stem)) {
                        dup = true;
                        break;
                    }
                }
                if (dup) {
                    continue;
                }
                rows.add(new String[] { stem, where });
                if (rows.size() >= MOD_CAP) {
                    break;
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            PilotWatchLog.info("editor catalog index unreadable (" + unreadable.getClass().getSimpleName()
                    + "): empty list");
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
        return rows;
    }

    /**
     * The LAST roll's refusal of a marker — apply-catalog-markers.ps1 writes
     * {@code _catalog/<stem>.reject.data} ("&lt;iso date&gt; &lt;why&gt;") and leaves the marker; the card shows the why
     * (a rejection used to be silent in the editor). DATA: the timestamp dropped, control characters folded, capped.
     * Null when there is none or it cannot be read.
     */
    private static String rejectRead(SettingsAPI settings, String stem) {
        if (!EditorText.isId(stem)) {
            return null;
        }
        String name = "cc_variants/_catalog/" + stem + ".reject";
        try {
            if (!settings.fileExistsInCommon(name)) {
                return null;
            }
            String text = settings.readTextFileFromCommon(name);
            if (text == null) {
                return null;
            }
            String why = text.trim();
            int sp = why.indexOf(' ');
            if (sp > 0 && why.length() > sp + 1) {
                why = why.substring(sp + 1).trim();
            }
            why = why.replaceAll("[\\p{Cntrl}]+", " ").trim();
            if (why.isEmpty()) {
                return null;
            }
            return why.length() > 240 ? why.substring(0, 240) + "…" : why;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    // ---- the pending index: cc_variants/_catalog/index = {"promote":[stems],"remove":[stems]} ----

    private static JSONObject pendingRead(SettingsAPI settings) {
        JSONObject o = new JSONObject();
        try {
            if (settings.fileExistsInCommon(PENDING_NAME)) {
                String text = settings.readTextFileFromCommon(PENDING_NAME);
                if (text != null && text.length() <= INDEX_MAX_BYTES) {
                    JSONObject read = new JSONObject(text);
                    for (String kind : new String[] { "promote", "remove" }) {
                        JSONArray arr = read.optJSONArray(kind);
                        JSONArray kept = new JSONArray();
                        for (int i = 0; arr != null && i < arr.length() && kept.length() < PENDING_CAP; i++) {
                            String s = arr.optString(i, "");
                            if (EditorText.isId(s)) {
                                kept.put(s);
                            }
                        }
                        o.put(kind, kept);
                    }
                }
            }
        } catch (IOException | JSONException | RuntimeException unreadable) {
            PilotWatchLog.info("editor catalog pending index unreadable (" + unreadable.getClass().getSimpleName()
                    + "): rebuilt empty");
        }
        return o;
    }

    private static boolean has(JSONObject pending, String kind, String stem) {
        JSONArray arr = pending.optJSONArray(kind);
        for (int i = 0; arr != null && i < arr.length(); i++) {
            if (stem.equals(arr.optString(i, ""))) {
                return true;
            }
        }
        return false;
    }

    private static int count(JSONObject pending) {
        int n = 0;
        for (String kind : new String[] { "promote", "remove" }) {
            JSONArray arr = pending.optJSONArray(kind);
            n += arr == null ? 0 : arr.length();
        }
        return n;
    }

    /** Adds {@code stem} under {@code kind} and drops it from the other list (a later promote cancels a remove and vice versa); a null kind drops it from both. */
    private static void pendingSet(SettingsAPI settings, JSONObject pending, String stem, String kind) throws IOException {
        try {
            JSONObject out = new JSONObject();
            for (String k : new String[] { "promote", "remove" }) {
                JSONArray arr = pending.optJSONArray(k);
                JSONArray kept = new JSONArray();
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    String s = arr.optString(i, "");
                    if (!s.equals(stem)) {
                        kept.put(s);
                    }
                }
                if (k.equals(kind)) {
                    kept.put(stem);
                }
                out.put(k, kept);
            }
            settings.writeTextFileToCommon(PENDING_NAME, out.toString());
        } catch (JSONException impossible) {
            throw new IllegalStateException("pending index json", impossible);
        }
    }

    private static String marker(String stem, String kind, String hullId, String displayName) {
        try {
            JSONObject m = new JSONObject();
            m.put("stem", stem);
            m.put("kind", kind);
            m.put("hull", hullId);
            m.put("name", EditorText.sanitize(displayName, 48));
            m.put("at", System.currentTimeMillis());
            return m.toString();
        } catch (JSONException impossible) {
            throw new IllegalStateException("marker json", impossible);
        }
    }

    private static JSONObject refuse(String why) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("ok", false);
        o.put("error", why);
        return o;
    }
}
