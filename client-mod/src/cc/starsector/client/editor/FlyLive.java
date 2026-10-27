package cc.starsector.client.editor;

import java.io.IOException;

import org.json.JSONException;
import org.json.JSONObject;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;

import cc.starsector.client.CcClientModPlugin;
import cc.starsector.client.PilotWatchLog;
import cc.starsector.client.net.ClientVariantDefinition;
import cc.starsector.client.net.ClientVariantJson;
import cc.starsector.client.net.PredictSession;
import cc.starsector.client.net.ShipReplyLatch;

/**
 * ONE verb, Fly, applies at the NEXT RESPAWN.
 * The route's locked
 * half ({@link ServerBuildFly#fly} / {@code EditorOracle.savedFly}) has already written
 * {@code current.data} (the next cold launch's HELLO pick); this is the LIVE half, run by the route
 * OUTSIDE {@code EditorOracle.LOCK} (both lanes block: a bounded CTRL send, a 3 s VACK wait) while a
 * predict session exists and is not defunct:
 * <ul>
 * <li>a ROSTER build ({@code kind=roster}): {@code /ship <id>} on the CTRL chat lane — the SAME CHTU
 * frame a typed line rides, zero new wire surface; the id passed the id law AND the published-roster
 * membership check under the lock, the server re-validates against its allowlist exactly as for a
 * typed verb. The server's reply (its own words, "next respawn: <id>" or a refusal) is taken by
 * {@link ShipReplyLatch} and returned as {@code reply} for the card row; no line inside
 * {@link #SHIP_REPLY_WAIT_MS} ⇒ {@code replyState=sent} (the chat bucket drops silently);</li>
 * <li>a LOCAL build ({@code kind=local}, the header's Fly and a saved row) or a PEER copy
 * ({@code kind=peer}, {@code stem} = the Fly-made local copy): the stored file is SUBMITTED over the
 * existing VSUB lane ({@code EditorOracle.submit}, the server dup-keys the body so a re-Fly answers
 * the same canonical id) — the accept seeds the seat's next-respawn override server-side; the
 * verdict object rides as {@code submit}.</li>
 * </ul>
 * Not live ⇒ {@code replyState=not-live} and the SPA says "flies at your next connect". Fail-soft:
 * a send that throws is {@code not-sent} with a sanitized reason; nothing here throws past the route.
 * Player-authored text (ids, the server echo) is DATA: sanitized + capped before the log / the SPA.
 */
final class FlyLive {

    private FlyLive() {}

    /** The bounded wait for the server's {@code /ship} reply line. */
    static final long SHIP_REPLY_WAIT_MS = 2000L;

    /** The live half of a Fly; {@code o} is the locked half's reply (mutated: live/replyState/reply/submit). */
    static void followUp(JSONObject o, String kind) throws JSONException {
        followUp(o, kind, false);
    }

    /** {@link #followUp(JSONObject, String)} carrying the saved-build
     *  Fly's keep-only ask (the host card's Promote): the submission goes out keep-only, so the server's accept
     *  mints the id without making it this player's next respawn. A roster build ignores it (a /ship verb). */
    static void followUp(JSONObject o, String kind, boolean keepOnly) throws JSONException {
        PredictSession session;
        try {
            session = CcClientModPlugin.predictSession();
        } catch (RuntimeException e) {
            session = null;
        }
        boolean live = session != null && !session.isDefunct();
        o.put("live", live);
        if (!live) {
            o.put("replyState", "not-live");
            return;
        }
        if (!o.optBoolean("flown", false)) {
            o.put("replyState", "not-flown");
            return;
        }
        if ("roster".equals(kind)) {
            shipVerb(o, session, o.optString("id", ""));
        } else {
            String stem = "peer".equals(kind) ? o.optString("stem", "") : o.optString("id", "");
            submitStem(o, stem, keepOnly);
        }
    }

    private static void shipVerb(JSONObject o, PredictSession session, String id) throws JSONException {
        if (!EditorText.isId(id)) {
            o.put("replyState", "not-sent");
            return;
        }
        long gen = ShipReplyLatch.arm(session.ownSeatIdOrMinusOne()); // The reply's seat
        try {
            session.sendChat("/ship " + id);
        } catch (IOException | RuntimeException notSent) {
            o.put("replyState", "not-sent");
            o.put("error", "not sent: " + EditorText.sanitize(notSent.getMessage() == null
                    ? notSent.getClass().getSimpleName() : notSent.getMessage(), 120));
            PilotWatchLog.info("editor fly (server build): /ship " + id + " NOT sent ("
                    + notSent.getClass().getSimpleName() + ")");
            return;
        }
        String reply = ShipReplyLatch.await(gen, SHIP_REPLY_WAIT_MS);
        if (reply == null) {
            o.put("replyState", "sent");
            PilotWatchLog.info("editor fly (server build): /ship " + id + " sent, no reply in "
                    + SHIP_REPLY_WAIT_MS + " ms (the chat shows it when it lands)");
            return;
        }
        String clean = EditorText.sanitize(reply, 120);
        o.put("replyState", "answered");
        o.put("reply", clean);
        PilotWatchLog.info("editor fly (server build): /ship " + id + " -> '" + clean + "'");
    }

    private static void submitStem(JSONObject o, String stem, boolean keepOnly) throws JSONException {
        if (!EditorText.isId(stem)) {
            o.put("replyState", "not-sent");
            return;
        }
        ClientVariantDefinition def;
        try {
            SettingsAPI settings = Global.getSettings();
            String name = "cc_variants/" + stem + ".variant";
            if (!settings.fileExistsInCommon(name)) {
                o.put("replyState", "not-sent");
                o.put("error", "not submitted: the saved file is gone");
                return;
            }
            def = ClientVariantJson.parse(settings.readTextFileFromCommon(name));
        } catch (IOException | RuntimeException unreadable) {
            o.put("replyState", "not-sent");
            o.put("error", "not submitted: " + EditorText.sanitize(unreadable.getMessage() == null
                    ? unreadable.getClass().getSimpleName() : unreadable.getMessage(), 120));
            return;
        }
        JSONObject submit = EditorOracle.submit(def, keepOnly); // NEVER under LOCK (its own 3 s VACK wait)
        o.put("replyState", "submitted");
        o.put("submit", submit);
    }
}
