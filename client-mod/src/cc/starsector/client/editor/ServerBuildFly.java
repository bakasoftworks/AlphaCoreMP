package cc.starsector.client.editor;

import java.util.HashMap;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.fs.starfarer.api.Global;

import cc.starsector.client.CcClientModPlugin;
import cc.starsector.client.PilotWatchLog;

/**
 * A FLY button per server build:
 * {@code POST server/<id>/fly} — a build the server PUBLISHED (the join window's roster
 * file, or a peer's custom accepted this session) becomes the CURRENT BUILD. Its own file, not an
 * {@code EditorOracle} method: that class sits over its WARN tier ({@code deferred-refactors.md}).
 *
 * <p>Two lanes, both already proven on flights:
 * <ul>
 * <li>a ROSTER id is written to {@code current.data} as-is — the join window's combo does exactly
 * this on Launch; the HELLO carries the server's id, {@code submitLocalCustomVariant} finds no local
 * file and sends no VSUB, the serve fields its own variant. Loadable in this game or not is
 * irrelevant (the serve fans the body at join);</li>
 * <li>a PEER custom ({@code cc-v…}) is never a HELLO pick (the ledger's reserved id space): it saves
 * a LOCAL copy first ({@link PeerBuilds#saveFromRegistry} — the picker's fly-next law) and the
 * copy's stem becomes current; the reply names it ({@code stem}). The copy is made ONCE per launch:
 * {@link #PEER_STEMS} remembers id → stem (the picker lane's {@code flyStems} twin), so a second Fly
 * re-flies that copy instead of minting {@code -2..-9}; a copy
 * the player deleted in the editor is minted again.</li>
 * </ul>
 *
 * <p>Fail-closed: the route id-law-gates the id, then it must be IN the published roster or the
 * peers group exactly as {@link EditorOracle#serverBuildsList} renders them — a lawful id the server
 * never offered is {@code null} (404), never written. The caller holds {@link EditorOracle#LOCK}
 * (re-entrant: {@code saveFromRegistry} takes it again); {@link #PEER_STEMS} is guarded by it.
 *
 * <p>this is the LOCKED half only (membership + {@code current.data} =
 * the next cold launch's pick). The route then runs {@link FlyLive#followUp} OUTSIDE the lock: while a
 * session is live a roster id is ALSO sent as {@code /ship <id>} (apply at the next respawn, the
 * server's reply inline on the card) and a peer copy is SUBMITTED (VSUB); {@code kind} tells it which.
 */
final class ServerBuildFly {

    private ServerBuildFly() {}

    /** Peer id → the local stem a Fly already copied it to, this launch (under EditorOracle.LOCK). */
    private static final Map<String, String> PEER_STEMS = new HashMap<String, String>();

    /** The reply for {@code POST server/<id>/fly}, or null when the id is not a published build. */
    static JSONObject fly(String id) throws JSONException {
        JSONObject published = EditorOracle.serverBuildsList();
        boolean roster = holds(published.optJSONArray("server"), id);
        boolean peer = !roster && holds(published.optJSONArray("peers"), id);
        if (!roster && !peer) {
            return null;
        }
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("kind", roster ? "roster" : "peer"); // The route's live half (FlyLive) reads it
        if (roster) {
            boolean set = CcClientModPlugin.setCurrentBuild(id);
            o.put("flown", set);
            if (!set) {
                o.put("error", "could not make " + id + " current");
            }
            PilotWatchLog.info("editor fly (server build): id=" + id + (set ? "" : " (refused)"));
        } else {
            String stem = rememberedStemOrNull(id);
            boolean saved = stem != null;
            String message = null;
            if (!saved) {
                PeerBuilds.Result res = PeerBuilds.saveFromRegistry(id, null);
                saved = res.saved;
                stem = res.stem;
                message = res.message;
                if (saved) {
                    PEER_STEMS.put(id, stem);
                }
            }
            boolean set = saved && CcClientModPlugin.setCurrentBuild(stem);
            o.put("flown", set);
            if (saved) {
                o.put("stem", stem);
            }
            if (!set) {
                o.put("error", saved
                        ? "saved as " + stem + " but it could not be made current"
                        : message);
            }
            PilotWatchLog.info("editor fly (peer build): id=" + EditorText.sanitize(id, 64)
                    + (saved ? (message == null ? " re-flies " : " saved as ") + stem : " not saved")
                    + (set ? "" : " (refused)"));
        }
        o.put("current", CcClientModPlugin.currentBuild());
        o.put("next", CcClientModPlugin.nextConnectPick());
        return o;
    }

    /** {@code GET server}: peer rows a Fly already copied carry {@code stem} = that local build, so the
     *  SPA's NEXT SPAWN test can match the current build against it (the id itself never is current). */
    static void annotatePeers(JSONObject list) throws JSONException {
        JSONArray peers = list.optJSONArray("peers");
        for (int i = 0; peers != null && i < peers.length(); i++) {
            JSONObject row = peers.optJSONObject(i);
            String stem = row == null ? null : rememberedStemOrNull(row.optString("id", ""));
            if (stem != null) {
                row.put("stem", stem);
            }
        }
    }

    /** The remembered copy's stem while its file still exists; a deleted copy forgets (minted again). */
    private static String rememberedStemOrNull(String id) {
        String stem = PEER_STEMS.get(id);
        if (stem == null) {
            return null;
        }
        boolean present;
        try {
            present = Global.getSettings().fileExistsInCommon("cc_variants/" + stem + ".variant");
        } catch (RuntimeException unreadable) {
            present = false;
        }
        if (!present) {
            PEER_STEMS.remove(id);
            return null;
        }
        return stem;
    }

    /** True when one of the rendered rows carries exactly this id. */
    private static boolean holds(JSONArray rows, String id) {
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && id.equals(row.optString("id", ""))) {
                return true;
            }
        }
        return false;
    }
}
