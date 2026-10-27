package cc.starsector.client.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.fs.starfarer.api.combat.ShipVariantAPI;

import cc.starsector.client.CcClientModPlugin;
import cc.starsector.client.ChatInputLaw;
import cc.starsector.client.ChatLane;
import cc.starsector.client.CustomConfigFromWire;
import cc.starsector.client.MatchConfigFromWire;
import cc.starsector.client.PilotWatchLog;
import cc.starsector.client.net.ClientVariantDefinition;
import cc.starsector.client.net.HostShipsLatch;
import cc.starsector.client.net.PredictSession;

/**
 * The ship editor's host card, its live half: a host manages the server's ship list from the editor through the
 * server's own chat admin verbs, the same path a typed verb takes (no new wire surface). Run by the
 * {@code host/*} routes OUTSIDE {@code EditorOracle.LOCK} (every call blocks on a bounded wait for the server's
 * answer).
 * <ul>
 * <li>{@link #hostJson}: {@code live} = a live session AND an active combat plugin's chat drain (a kept-open session
 * at the title drains nothing, so no answer could land); {@code admin} = live and the seat's admin flag (presentation
 * only: the server re-checks its own session flag on every verb); {@code submissions} = live and the server's
 * customs switch.</li>
 * <li>{@link #signIn}: the in-editor {@code /auth}, deposited through {@link ChatLane#editorAuth} (the typed
 * {@code /auth}'s own drain: proven, never sent readable, held for the re-admit replay); the answer is read by the
 * latch's AUTH slot. The secret is trimmed, checked against the admin-password law locally (a miss never leaves
 * the process), never logged, never echoed, never kept here past the call.</li>
 * <li>{@link #ships} and {@link #verb}: {@code /shiplist}, {@code /shipkeep <id>} (or {@code /shipkeep <id> replaces
 * <X id>}), {@code /shipremove <id>}, {@code /shiprestore <id>} on the session's raw chat send (the Fly route's
 * {@code /ship} idiom), ONE verb at a
 * time (the server's per-key chat bucket drops a fifth line inside a second), each answered through its own
 * {@link HostShipsLatch} slot.</li>
 * </ul>
 * Fail-soft: nothing here throws past the route; server text is sanitized and capped before it reaches the SPA; the
 * log rows carry outcome tokens only, never an id or a secret.
 *
 */
final class HostShips {

    private HostShips() {
    }

    /** A verb's first wait (the Fly route's {@code /ship} reply window). */
    static final long VERDICT_WAIT_MS = FlyLive.SHIP_REPLY_WAIT_MS;
    /** The total window once the server said it is working (it mails a final, or "no result", within 15 s). */
    static final long QUEUED_WAIT_MS = 16000L;
    /** The list's window (its end line). */
    static final long LIST_WAIT_MS = 3000L;
    /** The sign-in's window. */
    static final long AUTH_WAIT_MS = 3000L;
    /** How long a confirmed sign-in waits for the seat flag the same reply flips on the chat path. */
    static final long ADMIN_FLAG_SETTLE_MS = 500L;
    static final String NO_ANSWER = "no answer from the server - is a match running?";
    static final String QUEUED_SILENT = "no result yet - check the list";
    static final String BUSY = "busy";
    /** Per-array cap on the parsed list (the server caps its hidden list at 128; this only bounds a misuse). */
    static final int MAX_IDS_PER_GROUP = 512;

    private static final Object GATE = new Object();
    private static boolean verbInFlight;
    private static boolean signInInFlight;
    private static boolean sessionFaultLogged;

    /** The live predict session, or null (none, defunct, or the read threw: logged once). */
    static PredictSession sessionOrNull() {
        try {
            PredictSession s = CcClientModPlugin.predictSession();
            return s != null && !s.isDefunct() ? s : null;
        } catch (RuntimeException fault) {
            synchronized (GATE) {
                if (!sessionFaultLogged) {
                    sessionFaultLogged = true;
                    PilotWatchLog.info("editor host: session read failed (" + fault.getClass().getSimpleName()
                            + "); the host card reads as not live");
                }
            }
            return null;
        }
    }

    static boolean live() {
        return sessionOrNull() != null && ChatLane.editorLive();
    }

    static boolean admin() {
        return live() && MatchConfigFromWire.seatAdmin();
    }

    /** {@code api/ping.host}: always present. */
    static JSONObject hostJson() throws JSONException {
        boolean live = live();
        JSONObject o = new JSONObject();
        o.put("live", live);
        o.put("admin", live && MatchConfigFromWire.seatAdmin());
        o.put("submissions", live && CustomConfigFromWire.submissions());
        return o;
    }

    /** One string field of a small JSON body, trimmed; null when the body is not a JSON object or the field is
     *  absent / not a string. Never throws, and never puts body text into any message. */
    static String bodyField(byte[] body, String name) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            Object v = new JSONObject(new String(body, StandardCharsets.UTF_8)).opt(name);
            return v instanceof String ? ((String) v).trim() : null;
        } catch (JSONException | RuntimeException malformed) {
            return null; // the route answers 400 with a fixed text
        }
    }

    /**
     * The admin-password law, checked locally before anything is queued: the server's charset
     * {@code [A-Za-z0-9][A-Za-z0-9-]*}, 12 to 64 characters, and the line the drain would build must be a proof-class
     * {@code /auth} line. A pasted trailing space was already trimmed by {@link #bodyField}.
     */
    static boolean lawfulSecret(String secret) {
        if (secret == null || secret.length() < cc.starsector.server.match.MatchPasswords.ADMIN_PASSWORD_MIN_CHARS
                || secret.length() > cc.starsector.server.match.MatchPasswords.PASSWORD_MAX_CHARS) {
            return false;
        }
        for (int i = 0; i < secret.length(); i++) {
            char c = secret.charAt(i);
            boolean alnum = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (!alnum && !(i > 0 && c == '-')) {
                return false;
            }
        }
        return ChatInputLaw.auth13Class("/auth " + secret) == ChatInputLaw.Auth13Line.PROVE;
    }

    /**
     * The sign-in: {@code not-live} | {@code refused} (the local law, or the door's miss) | {@code busy} (one in
     * flight) | {@code signed-in} | {@code held} (the door asked for a retry) | {@code no-answer} (3 s of silence).
     */
    static String signIn(String secret) {
        if (!live()) {
            return "not-live";
        }
        if (!lawfulSecret(secret)) {
            return "refused";
        }
        synchronized (GATE) {
            if (signInInFlight) {
                return "busy";
            }
            signInInFlight = true;
        }
        String state;
        try {
            state = signInOnce(secret);
        } finally {
            synchronized (GATE) {
                signInInFlight = false;
            }
        }
        PilotWatchLog.info("editor host sign-in: " + state);
        return state;
    }

    private static String signInOnce(String secret) {
        PredictSession s = sessionOrNull();
        if (s == null) {
            return "not-live";
        }
        long gen = HostShipsLatch.armAuth(s.ownSeatIdOrMinusOne());
        if (!ChatLane.editorAuth(secret)) {
            HostShipsLatch.disarmAuth(gen);
            return "not-live";
        }
        HostShipsLatch.AuthAnswer a = HostShipsLatch.awaitAuth(gen, AUTH_WAIT_MS);
        if (a == null) {
            return "no-answer";
        }
        if (a == HostShipsLatch.AuthAnswer.HELD) {
            return "held";
        }
        if (a == HostShipsLatch.AuthAnswer.REFUSED) {
            return "refused";
        }
        // The latch reads the reply BEFORE the chat path flips the seat flag on the same net-thread call; let that
        // land so the card's next ping already reads admin.
        long deadline = System.nanoTime() + ADMIN_FLAG_SETTLE_MS * 1_000_000L;
        while (!MatchConfigFromWire.seatAdmin() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return "signed-in";
    }

    /** {@code GET host/ships}: the server's {@code /shiplist}, parsed into its four groups. */
    static JSONObject ships() throws JSONException {
        PredictSession s = sessionOrNull();
        if (s == null) {
            return fail(NO_ANSWER);
        }
        synchronized (GATE) {
            if (verbInFlight) {
                return fail(BUSY);
            }
            verbInFlight = true;
        }
        try {
            long gen = HostShipsLatch.armList(s.ownSeatIdOrMinusOne());
            try {
                s.sendChat("/shiplist");
            } catch (IOException | RuntimeException notSent) {
                HostShipsLatch.disarmList(gen);
                PilotWatchLog.info("editor host verb: shiplist NOT sent (" + notSent.getClass().getSimpleName() + ")");
                return fail("not sent - is a match running?");
            }
            HostShipsLatch.ListResult r = HostShipsLatch.awaitList(gen, LIST_WAIT_MS);
            String outcome = r.terminal != null ? "terminal" : r.ended ? "ok" : r.lines.isEmpty() ? "no-answer" : "cut";
            PilotWatchLog.info("editor host verb: shiplist -> " + outcome + " (" + r.lines.size() + " lines)");
            if (r.terminal != null) {
                return fail(EditorText.sanitize(r.terminal, 120));
            }
            if (!r.ended) {
                return fail(r.lines.isEmpty() ? NO_ANSWER : "the list did not finish - try again");
            }
            return withNames(parseList(r.lines)); //
        } finally {
            synchronized (GATE) {
                verbInFlight = false;
            }
        }
    }

    /**
     * The list lines into the route's object: the count line ({@code shiplist <n> listed, ...}) is {@code line};
     * each {@code shiplist <group>: a, b} line appends to its group (a group may span lines); ids failing the id
     * law are skipped (never painted).
     */
    static JSONObject parseList(java.util.List<String> lines) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("ok", true);
        String[] groups = {"listed", "hidden", "kept", "removed"};
        JSONArray[] arrays = new JSONArray[groups.length];
        for (int g = 0; g < groups.length; g++) {
            arrays[g] = new JSONArray();
            o.put(groups[g], arrays[g]);
        }
        String count = "";
        for (String text : lines) {
            if (!text.startsWith(HostShipsLatch.LIST_HEAD)) {
                continue;
            }
            String body = text.substring(HostShipsLatch.LIST_HEAD.length());
            if (count.isEmpty() && !body.isEmpty() && body.charAt(0) >= '0' && body.charAt(0) <= '9') {
                count = EditorText.sanitize(text, 120);
                continue;
            }
            for (int g = 0; g < groups.length; g++) {
                String head = groups[g] + ":";
                if (!body.startsWith(head)) {
                    continue;
                }
                for (String raw : body.substring(head.length()).split(",")) {
                    String id = raw.trim();
                    if (!id.isEmpty() && EditorText.isId(id) && arrays[g].length() < MAX_IDS_PER_GROUP) {
                        arrays[g].put(id);
                    }
                }
                break;
            }
        }
        o.put("line", count);
        return o;
    }

    /**
     * Adds {@code names} = {@code {id: displayName}} for every id across the four
     * groups whose name this JVM knows — the game registry first, then the wire-delivered customs' disk cache, the
     * resolution the S.server rows use — each sanitized to 48 printable ASCII; an unknown id is simply absent. WHY: a
     * kept copy's id says nothing to a host, its name does. DISPLAY ONLY: the rows stay keyed by id and every verb
     * still sends the id. A registry-less run leaves {@code names} empty, {@code ok} untouched, and never throws.
     * Beside it {@code hulls} = {@code {id: hull name}} (the row's meta line) and
     * {@code hullIds} = {@code {id: hull id}} (the editor's check that a kept row is the hull it just promoted), from the
     * SAME variant lookup; a hull whose spec this JVM cannot read shows its id as its name.
     */
    static JSONObject withNames(JSONObject o) throws JSONException {
        return withNames(o, REGISTRY);
    }

    /** The three lookups behind {@link #withNames}; the selftest swaps in a fixture (no game registry there). */
    interface ShipLookup {
        /** The variant's display name, or null (unknown, blank, or no registry). */
        String displayNameOrNull(String id);

        /** The variant's hull id, or null (unknown or no registry). */
        String hullIdOrNull(String id);

        /** The hull's display name, or null when no spec answers for that hull id. */
        String hullNameOrNull(String hullId);
    }

    /** The editor JVM's lookups: the game registry, then the wire-delivered customs' disk cache. */
    static final ShipLookup REGISTRY = new ShipLookup() {
        @Override
        public String displayNameOrNull(String id) {
            return HostShips.displayNameOrNull(id);
        }

        @Override
        public String hullIdOrNull(String id) {
            try {
                ShipVariantAPI v = EditorOracle.registeredVariant(id);
                if (v != null) {
                    return v.getHullSpec().getHullId();
                }
                ClientVariantDefinition d = ServerBuildCache.cached(id);
                return d == null ? null : d.hullId();
            } catch (RuntimeException | LinkageError registryless) {
                return null;
            }
        }

        @Override
        public String hullNameOrNull(String hullId) {
            try {
                com.fs.starfarer.api.combat.ShipHullSpecAPI spec = EditorOracle.hullOrNull(hullId);
                return spec == null ? null : spec.getHullName();
            } catch (RuntimeException | LinkageError registryless) {
                return null;
            }
        }
    };

    static JSONObject withNames(JSONObject o, ShipLookup lookup) throws JSONException {
        JSONObject names = new JSONObject();
        JSONObject hulls = new JSONObject();
        JSONObject hullIds = new JSONObject();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String group : new String[] {"listed", "hidden", "kept", "removed"}) {
            JSONArray ids = o.optJSONArray(group);
            for (int i = 0; ids != null && i < ids.length(); i++) {
                String id = ids.optString(i, "");
                if (!EditorText.isId(id) || !seen.add(id)) {
                    continue;
                }
                String name = safeOrNull(lookup.displayNameOrNull(id));
                if (name != null) {
                    names.put(id, name);
                }
                String hullId = lookup.hullIdOrNull(id);
                if (hullId != null && EditorText.isId(hullId)) {
                    hullIds.put(id, hullId);
                    String hullName = safeOrNull(lookup.hullNameOrNull(hullId));
                    hulls.put(id, hullName != null ? hullName : hullId);
                }
            }
        }
        o.put("names", names);
        o.put("hulls", hulls);
        o.put("hullIds", hullIds);
        return o;
    }

    /** A looked-up label for the SPA: trimmed and sanitized to 48 chars, or null when absent or blank. */
    private static String safeOrNull(String label) {
        return label == null || label.isBlank() ? null : EditorText.sanitize(label.trim(), 48);
    }

    /** One id's display name, sanitized to 48 chars, else null: unknown, blank, or no game registry in this JVM. */
    static String displayNameOrNull(String id) {
        try {
            ShipVariantAPI v = EditorOracle.registeredVariant(id);
            String name = v != null ? v.getDisplayName() : null;
            if (name == null) {
                ClientVariantDefinition d = ServerBuildCache.cached(id);
                name = d == null ? null : d.displayName();
            }
            if (name == null || name.isBlank()) {
                return null;
            }
            return EditorText.sanitize(name.trim(), 48);
        } catch (RuntimeException | LinkageError registryless) {
            return null;
        }
    }

    /**
     * {@code POST host/keep|remove|restore}: {@code verb} = {@code shipkeep} / {@code shipremove} /
     * {@code shiprestore}; {@code id} already passed the id law. The verdict is the receipt for
     * {@code "<verb> <id>: "}: a success tail (it opens with the ship's quoted display name, {@link #successTail})
     * answers {@code ok}; any other tail (a refusal, the server's "no result" line) answers its words as
     * {@code error}; a head-less terminal line answers itself.
     */
    static JSONObject verb(String verb, String id) throws JSONException {
        return verb(verb, id, null);
    }

    /**
     * {@link #verb(String, String)} with the keep's optional supersede target:
     * {@code replaces} = the id of the ship the host's draft was loaded from (null or empty = a new ship). It must pass
     * the id law or nothing is sent. The receipt head stays {@code "shipkeep <id>: "} - the server answers under the id
     * as typed - so the latch slot is the same one a plain keep arms.
     */
    static JSONObject verb(String verb, String id, String replaces) throws JSONException {
        String line = verbLine(verb, id, replaces);
        if (line == null) {
            return fail("bad ship id");
        }
        PredictSession s = sessionOrNull();
        if (s == null) {
            return fail(NO_ANSWER);
        }
        synchronized (GATE) {
            if (verbInFlight) {
                return fail(BUSY);
            }
            verbInFlight = true;
        }
        try {
            String prefix = verb + " " + id + ": ";
            long gen = HostShipsLatch.armVerb(prefix, s.ownSeatIdOrMinusOne());
            try {
                s.sendChat(line);
            } catch (IOException | RuntimeException notSent) {
                HostShipsLatch.disarmVerb(prefix, gen);
                PilotWatchLog.info("editor host verb: " + verb + " NOT sent (" + notSent.getClass().getSimpleName() + ")");
                return fail("not sent - is a match running?");
            }
            HostShipsLatch.Verdict v = HostShipsLatch.awaitVerdict(prefix, gen, VERDICT_WAIT_MS, QUEUED_WAIT_MS);
            JSONObject o = verdictJson(prefix, v);
            String what = line.contains(" replaces ") ? verb + "+replaces" : verb; // a bounded token, never the ids
            PilotWatchLog.info("editor host verb: " + what + " -> " + (o.optBoolean("ok") ? "ok"
                    : v.line == null ? (v.queued ? "queued-silent" : "no-answer") : v.terminal ? "terminal" : "refused"));
            return o;
        } finally {
            synchronized (GATE) {
                verbInFlight = false;
            }
        }
    }

    /**
     * The chat line a host verb sends: {@code /<verb> <id>}, and for
     * {@code shipkeep} with a supersede target {@code /shipkeep <id> replaces <X id>} (four tokens, the third literally
     * {@code replaces}). Null = a lawless id or target (nothing may be sent); a target on any other verb is ignored.
     */
    static String verbLine(String verb, String id, String replaces) {
        if (id == null || !EditorText.isId(id)) {
            return null;
        }
        boolean supersede = "shipkeep".equals(verb) && replaces != null && !replaces.isEmpty();
        if (supersede && !EditorText.isId(replaces)) {
            return null;
        }
        return "/" + verb + " " + id + (supersede ? " replaces " + replaces : "");
    }

    /** The receipt text the SPA gets: the head, a display name and its tail fit (a refusal's words too). */
    static final int VERDICT_MAX_CHARS = 200;

    static JSONObject verdictJson(String prefix, HostShipsLatch.Verdict v) throws JSONException {
        if (v.line == null) {
            JSONObject o = fail(v.queued ? QUEUED_SILENT : NO_ANSWER);
            o.put("queued", v.queued);
            return o;
        }
        String line = EditorText.sanitize(v.line, VERDICT_MAX_CHARS);
        if (v.terminal) {
            return fail(line);
        }
        String tail = v.line.substring(prefix.length());
        if (successTail(tail)) {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("verdict", line);
            return o;
        }
        JSONObject o = fail(EditorText.sanitize(tail, VERDICT_MAX_CHARS));
        o.put("verdict", line);
        return o;
    }

    /**
     * A receipt body is a SUCCESS when it opens with the ship's quoted display
     * name ({@code 'Wolf Raider' added - ...}, {@code '...' removed - ...}, {@code '...' put back - ...}, {@code '...'
     * already kept - ...}); a refusal never opens with a quote. WHY one test for every verb: the receipts name the ship,
     * never its kept id, so the editor learns a new id from the list instead of parsing it here. The queued words
     * ({@code saving} / {@code removing} / {@code restoring}) never reach this test: the latch keeps waiting past them.
     */
    static boolean successTail(String tail) {
        return tail != null && !tail.isEmpty() && tail.charAt(0) == '\'';
    }

    private static JSONObject fail(String error) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("ok", false);
        o.put("error", error);
        return o;
    }
}
