package cc.starsector.client.editor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;

import cc.starsector.client.BootVariantRegistry;
import cc.starsector.client.PeerBuildVerbLaw;
import cc.starsector.client.PilotWatchLog;
import cc.starsector.client.net.ClientVariantDefinition;
import cc.starsector.client.net.ClientVariantJson;

/**
 * wq P24 (design {@code variant-maker-peer-builds.md}): saving a PEER's
 * server-accepted custom variant — zero wire, zero new file input. Every accepted custom is
 * already folded into the engine's variant registry on every peer ({@code
 * VariantDefinitionLane.foldIntoRegistry}, tracked by {@link BootVariantRegistry}); this class
 * only LISTS that folded set (the editor panel's {@code peers} group on {@code GET server}; the
 * in-game {@code /builds} picker rows' labels) and turns one entry into a local build through
 * the editor's EXISTING draft + save path ({@link EditorOracle#variantDocumentFromApi} →
 * {@link EditorOracle#save}).
 *
 * <p>v2: the SHARE bit is server truth — the last
 * {@code /builds} listing (a KIND_BUILDS frame, ids only) fills {@link #noteSharedListing}, and
 * the editor {@code peers} group lists ONLY those ids (empty before the seat's first
 * {@code /builds}); a picker pick on a {@code /builds} row is {@link #saveFromRegistry} with a
 * {@code -2}..{@code -9} stem auto-suffix on a clash ({@link PeerBuildVerbLaw#suffixedStem}).
 *
 * <p>Laws: an id is id-law-gated BEFORE the registry is asked
 * and must be IN the folded set (a peer custom, never an arbitrary registered id); the file stem
 * is OURS ({@code EditorText.saveStem}); the author's {@code displayName} is DATA (sanitized by
 * the document composer and by {@link #rowLabel}, re-read under the reader's law before any
 * disk write). Classloader-B posture unchanged: {@code Global.getSettings()} + the registry's
 * own tracker, no new engine touch.
 */
public final class PeerBuilds {

    /** Listing cap — the editor panel's own ({@link EditorOracle#SERVER_BUILDS_CAP}). */
    public static final int CAP = EditorOracle.SERVER_BUILDS_CAP;

    /** v2: the ids the server's LAST {@code /builds} listing named (insertion order; render-thread
     *  writer, any-thread readers through the synchronized view). Empty until the first listing. */
    private static final Set<String> shared = Collections.synchronizedSet(new LinkedHashSet<String>());

    private PeerBuilds() {
    }

    /** v2: the last KIND_BUILDS listing REPLACES the shared set (the server's truth at that moment). */
    public static void noteSharedListing(Collection<String> ids) {
        synchronized (shared) {
            shared.clear();
            for (String id : ids) {
                if (EditorText.isId(id)) {
                    shared.add(id);
                }
            }
        }
    }

    /** v2: whether the last {@code /builds} listing named {@code id}. */
    public static boolean isShared(String id) {
        return id != null && shared.contains(id);
    }

    /** v2: the shared ids known to this client (a copy). */
    public static List<String> sharedIds() {
        synchronized (shared) {
            return new ArrayList<String>(shared);
        }
    }

    /** The outcome of one picker save — {@code message} is a chat-safe line (≤ 120 printable). */
    public static final class Result {
        public final boolean saved;
        public final String stem;
        public final String message;

        Result(boolean saved, String stem, String message) {
            this.saved = saved;
            this.stem = stem;
            this.message = PeerBuildVerbLaw.clamp(message);
        }
    }

    /** One folded peer custom resolved ONCE against the registry (the shape both listings share). */
    static final class Resolved {
        final String id;
        final ShipVariantAPI variant;

        Resolved(String id, ShipVariantAPI variant) {
            this.id = id;
            this.variant = variant;
        }
    }

    /**
     * The folded ids that resolve to a registered, hull-bearing variant (unknown ⇒ skipped), in fold
     * order, capped — each id resolved exactly once (the listings used
     * to re-resolve every id, ~2 × cap registry reads per call).
     */
    static List<Resolved> foldedResolved(int cap) {
        List<Resolved> out = new ArrayList<Resolved>();
        for (String id : BootVariantRegistry.foldedIds()) {
            if (out.size() >= cap) {
                break;
            }
            ShipVariantAPI v = EditorText.isId(id) ? EditorOracle.registeredVariant(id) : null;
            if (v != null) {
                out.add(new Resolved(id, v));
            }
        }
        return out;
    }

    /**
     * {@code GET server} → {@code peers}: the same row shape as the roster rows (all loadable by
     * construction), v2-gated to the ids the last {@code /builds} listing named (the
     * SHARE bit is the owner's axis — an accepted-but-unshared body stays resident yet unlisted).
     * A registry fault yields the rows resolved so far (an unguarded
     * throw here blanked the whole {@code GET server} reply, roster included).
     */
    static JSONArray peerRows(int cap) { // Guard below now catches JSONException, so nothing checked escapes
        JSONArray rows = new JSONArray();
        try {
            for (Resolved r : foldedResolved(cap)) {
                if (!isShared(r.id)) {
                    continue;
                }
                JSONObject row = new JSONObject();
                row.put("id", r.id);
                row.put("loadable", true);
                row.put("hullId", r.variant.getHullSpec().getHullId());
                row.put("hullName", String.valueOf(r.variant.getHullSpec().getHullName()));
                row.put("displayName", EditorText.sanitize(String.valueOf(r.variant.getDisplayName()), 48));
                row.put("op", r.variant.getHullSpec().getOrdnancePoints(null));
                rows.put(row);
            }
        } catch (JSONException | RuntimeException fault) { // row.put(...) throws checked JSONException
            PilotWatchLog.info("peer builds: peers group truncated: " + fault);
        }
        return rows;
    }

    /** How many folded ids currently resolve to a registered body (the drills' eviction check). */
    public static int residentCount(int cap) {
        try {
            return foldedResolved(cap).size();
        } catch (RuntimeException fault) {
            PilotWatchLog.info("peer builds: resident count failed: " + fault);
            return -1;
        }
    }

    /**
     * v2: a picker row's label for a folded custom — {@code "<name>" (<Hull>)},
     * the author's name sanitized DATA (≤ 48) — or {@code null} when the id is not a folded,
     * registered, hull-bearing body (the lane then SKIPS the row fail-soft). Render thread;
     * SettingsAPI reads only. Never throws.
     */
    public static String rowLabel(String id) {
        try {
            if (!EditorText.isId(id) || !BootVariantRegistry.foldedIds().contains(id)) {
                return null;
            }
            ShipVariantAPI v = EditorOracle.registeredVariant(id);
            if (v == null) {
                return null;
            }
            return "\"" + EditorText.sanitize(String.valueOf(v.getDisplayName()), 48) + "\" ("
                    + EditorText.sanitize(String.valueOf(v.getHullSpec().getHullName()), 32) + ")";
        } catch (RuntimeException fault) {
            PilotWatchLog.info("peer builds: row label failed: " + fault);
            return null;
        }
    }

    /**
     * Save the folded peer custom {@code id} as a NEW local build: the author's display name
     * (or {@code name} when given) → the stem law → the editor's own save path. v2: an existing
     * stem auto-suffixes {@code -2}..{@code -9}; all nine taken = refused. Never
     * throws.
     */
    public static Result saveFromRegistry(String id, String name) {
        try {
            if (!EditorText.isId(id) || !BootVariantRegistry.foldedIds().contains(id)) {
                return new Result(false, null, "not saved: that build is no longer offered this match");
            }
            ShipVariantAPI v;
            JSONObject doc;
            synchronized (EditorOracle.LOCK) {
                v = EditorOracle.registeredVariant(id);
                if (v == null) {
                    return new Result(false, null, "not saved: that build is no longer in this game's variant list");
                }
                doc = EditorOracle.variantDocumentFromApi(v);
            }
            if (name != null) {
                doc.put("displayName", name);
            }
            String baseStem = EditorText.saveStem(doc.getString("hullId"), doc.getString("displayName"));
            SettingsAPI settings = Global.getSettings();
            JSONObject o;
            String stem;
            synchronized (EditorOracle.LOCK) {
                // the refusal and the write share the lock: a same-stem editor save cannot slip between
                // them (EditorOracle.save overwrites unconditionally)
                stem = null;
                for (int n = 1; n <= PeerBuildVerbLaw.MAX_STEM_SUFFIX && stem == null; n++) {
                    String candidate = PeerBuildVerbLaw.suffixedStem(baseStem, n);
                    if (!settings.fileExistsInCommon("cc_variants/" + candidate + ".variant")) {
                        stem = candidate;
                    }
                }
                if (stem == null) {
                    return new Result(false, baseStem, "not saved: local builds " + baseStem + " .. -"
                            + PeerBuildVerbLaw.MAX_STEM_SUFFIX + " all exist - delete one in the editor");
                }
                doc.put("variantId", stem);
                ClientVariantDefinition def = ClientVariantJson.parse(doc.toString()); // the reader's law
                o = EditorOracle.save(def, stem); // the suffixed stem lands as-is (save(def) re-derived the plain stem)
            }
            boolean current = o.optBoolean("autoSelected", false);
            PilotWatchLog.info("peer builds: saved peer custom " + EditorText.sanitize(id, 64) + " as " + stem
                    + (current ? " (auto-selected current)" : ""));
            return new Result(true, stem, "saved as local build " + stem
                    + (current ? " (now your current build)" : " - fly it from the join window or the editor"));
        } catch (IllegalArgumentException | IllegalStateException | JSONException | IOException fault) {
            PilotWatchLog.info("peer builds: save of " + EditorText.sanitize(id, 64) + " refused: " + fault);
            return new Result(false, null, "not saved: " + EditorText.sanitize(String.valueOf(fault.getMessage()), 70));
        } catch (RuntimeException fault) {
            PilotWatchLog.error("peer builds: save of " + EditorText.sanitize(id, 64) + " FAILED", fault);
            return new Result(false, null, "not saved: " + fault.getClass().getSimpleName());
        }
    }

    /**
     * The editor half of the {@code peerbuilds} drill row (driven by
     * {@code BootVariantRegistry.drillReport}, which folds {@code idA}/{@code idB} first and
     * evicts after): both listed under {@code peers}; the API resolves a folded custom (the
     * one point the design left UNVERIFIED — {@code SettingsAPI.getVariant} → {@code
     * SpecStore} → the same {@code B} map the fold puts into, static RE); a draft loads;
     * a save lands a stem file that self-reparses with the author's name; the same save again
     * auto-suffixes {@code -2} (v2); a renamed save lands a third stem; all three deleted (kit
     * env left clean). v2 rows: the picker label for a folded id (name + hull, DATA) vs null
     * for an unknown one; the {@code peers} group gated by the shared set (1 with idA noted,
     * 0 after the note is cleared).
     */
    public static String drillReport(String idA, String idB) {
        StringBuilder sb = new StringBuilder();
        try {
            int listed = 0;
            for (Resolved r : foldedResolved(CAP)) {
                if (r.id.equals(idA) || r.id.equals(idB)) {
                    listed++;
                }
            }
            sb.append("listed=").append(listed);
            sb.append(" label=").append(String.valueOf(rowLabel(idA)))
                    .append(" unknown-label=").append(String.valueOf(rowLabel("cc-v999-never-folded")));
            noteSharedListing(java.util.Arrays.asList(idA, "cc-v999-never-folded"));
            JSONArray gated = peerRows(CAP);
            sb.append(" peers-gated=").append(gated.length())
                    .append(gated.length() == 1 && idA.equals(gated.getJSONObject(0).optString("id", "")) ? "(idA)" : "(?)");
            noteSharedListing(Collections.<String>emptyList());
            sb.append(" peers-cleared=").append(peerRows(CAP).length());
            ShipVariantAPI viaApi = null;
            try {
                viaApi = Global.getSettings().getVariant(idA);
            } catch (RuntimeException miss) {
                viaApi = null;
            }
            sb.append(" api=").append(viaApi != null && viaApi.getHullSpec() != null);
            JSONObject draft;
            synchronized (EditorOracle.LOCK) {
                draft = EditorOracle.serverBuildLoad(idA);
            }
            sb.append(" draft=").append(draft != null && draft.optBoolean("draft", false)
                    && idA.equals(draft.optString("id", "")) ? "ok" : "MISSING");
            Result first = saveFromRegistry(idA, null);
            sb.append(" saved=").append(first.saved ? first.stem : "NO(" + first.message + ")");
            boolean reparsed = false;
            String keptName = "";
            if (first.saved) {
                SettingsAPI settings = Global.getSettings();
                String text = settings.readTextFileFromCommon("cc_variants/" + first.stem + ".variant");
                ClientVariantDefinition back = ClientVariantJson.parse(text);
                reparsed = first.stem.equals(back.variantId());
                keptName = back.displayName();
            }
            sb.append(" reparsed=").append(reparsed).append(" name=\"").append(keptName).append('"');
            Result dup = saveFromRegistry(idA, null);
            sb.append(" dup=").append(dup.saved ? dup.stem : "NO(" + dup.message + ")");
            Result renamed = saveFromRegistry(idA, "Drill Wolf Two");
            sb.append(" renamed=").append(renamed.saved ? renamed.stem : "NO(" + renamed.message + ")");
            int deleted = 0;
            synchronized (EditorOracle.LOCK) {
                for (String stem : new String[] { first.stem, dup.stem, renamed.stem }) {
                    if (stem != null && EditorOracle.savedDelete(stem).optBoolean("deleted", false)) {
                        deleted++;
                    }
                }
            }
            sb.append(" deleted=").append(deleted);
        } catch (Throwable t) {
            sb.append(" error=").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
        }
        return sb.toString();
    }
}
