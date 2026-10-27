package cc.starsector.client.editor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.json.JSONException;
import org.json.JSONObject;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;

import cc.starsector.client.CcClientModPlugin;
import cc.starsector.client.PilotWatchLog;
import cc.starsector.client.net.ClientVariantDefinition;
import cc.starsector.client.net.ClientVariantJson;

/**
 * The loopback editor's route table (W15 v1). Every request must carry
 * the per-process token as its first path segment ({@code /<token>/…}, decision (1)) — a miss is
 * a bare 403 that never echoes the path; a {@code Host} header must name this loopback port
 * (DNS-rebinding guard, the {@code ServerOpsWeb} idiom) and a POST with an {@code Origin} must
 * originate from it. Ids in the path/query are charset-gated ({@link EditorText#isId}) and
 * resolved through the API — never a file name. Static assets come from the jar
 * ({@code /web/<name>}, decision (5)) against {@link #ASSETS}, never from a request-derived
 * path. Bodies are the {@code .variant} document parsed by the SAME law as the local file source
 * ({@link ClientVariantJson}).
 *
 * <pre>
 *  GET  /                      the SPA (web/index.html; a bare /&lt;token&gt; 302s to /&lt;token&gt;/)
 *  GET  /web/&lt;asset&gt;           allowlisted static asset from the jar
 *  GET  /api/ping              liveness + game state
 *  GET  /data/hulls|weapons|hullmods|wings[?tier=standard|strict|permissive]
 *  GET  /data/hull/&lt;id&gt;[?tier=…&amp;allSkills=1]
 *  GET  /sprite/&lt;hullId&gt;        PNG bytes
 *  POST /validate[?tier=…&amp;allSkills=1]   body = .variant JSON → the validation verdict
 *  POST /stats[?allSkills=1&amp;cr=0..100&amp;skills=1]  body = .variant JSON → MutableShipStatsAPI numbers at the preview CR; skills=1 = the bastion skill sets applied
 *  POST /save                           body = .variant JSON → saves/common/cc_variants/&lt;stem&gt;
 *  POST /autogroups                     body = .variant JSON → the game's auto-assigned weapon groups (row (d))
 *  POST /submit                         body = .variant JSON → VSUB on the live predict session; the VACK word
 *  GET  /host/ships                     the server's ship list (host only, live)
 *  POST /host/signin                    body = {secret} → the in-editor host sign-in state
 *  POST /host/keep|remove|restore       body = {id} → the server's receipt (host only, live)
 * </pre>
 */
final class EditorRoutes {
    static final Set<String> ASSETS = Set.of("index.html", "editor.js", "editor.css", "codex.js", "codex.css",
            "stage-scale.js");   // codex.* = the in-editor codex; stage-scale.js = the resampler carve

    /** Iteration 8 row (d) (the owner's blind spot — a stock spawn the editor never explained): the
     *  latest VSUB verdict for {@code api/ping.nextVerdict} — null with no predict session (title / headless), else
     *  {@code state} accepted | rejected | pending (+ {@code id}, {@code seq}, {@code token} and a ready-to-paint
     *  {@code line}). The receipt's id is sanitized; the token + hint are the server's / our own vocabulary. */
    static JSONObject nextVerdictJson() throws JSONException {
        if (!CcClientModPlugin.hasPredictSession()) {
            return null;
        }
        JSONObject o = new JSONObject();
        cc.starsector.client.net.ClientVariantReceipt last = CcClientModPlugin.latestVariantReceipt();
        if (last == null) {
            o.put("state", "pending");
            return o;
        }
        String id = EditorText.sanitize(last.variantId(), 64);
        o.put("state", last.accepted() ? "accepted" : "rejected");
        o.put("id", id);
        o.put("seq", last.submitSeq());
        if (last.accepted()) {
            o.put("line", "next respawn: " + id); // Names the build, never "custom"
        } else {
            String hint = cc.starsector.client.net.VariantRejectHints.hintOrNull(last.reasonToken());
            o.put("token", last.reasonToken());
            o.put("line", "rejected: " + (hint == null ? last.reasonToken() : hint + " (" + last.reasonToken() + ")"));
        }
        return o;
    }
    private static final int MAX_LOGGED_TOKEN_MISSES = 5;
    private static int tokenMisses;

    private EditorRoutes() {
    }

    static void handle(HttpExchange req, OutputStream out, String token, int port) throws IOException {
        String host = req.header("host");
        if (host != null && !isOurHost(host, port)) {
            HttpExchange.replyText(out, 403, "forbidden");
            return;
        }
        String prefix = "/" + token;
        String path = req.path;
        if (!(path.equals(prefix) || path.startsWith(prefix + "/"))) {
            noteTokenMiss(path);
            HttpExchange.replyText(out, 403, "forbidden");
            return;
        }
        if (path.equals(prefix) && "GET".equals(req.method)) {
            // /<token> → /<token>/ so the SPA's relative asset + api paths resolve under the token.
            HttpExchange.replyRedirect(out, prefix + "/");
            return;
        }
        String rest = path.length() > prefix.length() ? path.substring(prefix.length() + 1) : "";
        if ("POST".equals(req.method)) {
            String origin = req.header("origin");
            if (origin != null && !isOurOrigin(origin, port)) {
                HttpExchange.replyText(out, 403, "forbidden");
                return;
            }
        }
        try {
            route(req, rest, out);
        } catch (JSONException bad) {
            HttpExchange.replyJson(out, 422, error("bad JSON: " + EditorText.sanitize(bad.getMessage(), 160)));
        } catch (IllegalArgumentException law) {
            HttpExchange.replyJson(out, 422, error(EditorText.sanitize(law.getMessage(), 200)));
        } catch (IOException io) {
            throw io;
        } catch (RuntimeException fault) {
            PilotWatchLog.error("editor route fault on " + EditorText.sanitize(rest, 80), fault);
            HttpExchange.replyJson(out, 500, error(fault.getClass().getSimpleName()));
        }
    }

    private static void route(HttpExchange req, String rest, OutputStream out)
            throws IOException, JSONException {
        boolean get = "GET".equals(req.method);
        if (get && (rest.isEmpty() || "index.html".equals(rest))) {
            asset("index.html", out);
        } else if (get && rest.startsWith("web/")) {
            asset(rest.substring(4), out);
        } else if (get && "api/ping".equals(rest)) {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("state", safeState());
            o.put("thread", Thread.currentThread().getName());
            o.put("uptimeMs", EditorHost.uptimeMs());
            String session = liveSession(); // "live" = a predict session the SPA's Submit can hand a variant to
            // While CONNECTED the tier is the SERVER's (the v11 WELCOME tail,
            // CustomConfigFromWire) and the SPA locks its dropdown to it; at the title the
            // player browses any tier (tierSource=default, the STANDARD constant).
            boolean fromWire = "live".equals(session)
                    && cc.starsector.client.CustomConfigFromWire.seeded();
            o.put("defaultTier", fromWire ? cc.starsector.client.CustomConfigFromWire.tierName()
                    : EditorTier.Tier.STANDARD.name());
            o.put("tierSource", fromWire ? "server" : "default");
            if (fromWire) {
                o.put("perSeat", cc.starsector.client.CustomConfigFromWire.perSeat());
                o.put("sModCap", cc.starsector.client.CustomConfigFromWire.sModCap());
                o.put("submissions", cc.starsector.client.CustomConfigFromWire.submissions());
            }
            o.put("gate", EditorGameThread.statsJson()); // game-thread gate counters (session 4)
            o.put("session", session);
            // the NEXT connect's HELLO pick — the CURRENT BUILD (current.data), else the join
            // window's pick, else "" (server default) — where it came from (current | pick | none), the
            // current build itself (the SPA's NEXT SPAWN badge) and the server's verdict on it once known
            // (iteration 8 row (d): null with no session, else accepted / rejected + a plain-words line / pending).
            // All the player's own ids (stem = id-law text, the pick = ≤64 printable); DATA to the SPA.
            o.put("next", CcClientModPlugin.nextConnectPick());
            o.put("nextSource", CcClientModPlugin.nextConnectSource());
            o.put("current", CcClientModPlugin.currentBuild());
            JSONObject verdict = nextVerdictJson();
            o.put("nextVerdict", verdict == null ? JSONObject.NULL : verdict);
            o.put("host", HostShips.hostJson()); // the host card's gate: {live, admin, submissions}, always present
            HttpExchange.replyJson(out, 200, o.toString());
        } else if (get && rest.startsWith("data/")) {
            data(req, rest.substring(5), out);
        } else if (get && rest.startsWith("sprite/")) {
            // sprite/<hullId> | sprite/weapon/<id>?kind=turret|hardpoint|gun|glow|…_under | sprite/hullmod/<id>
            // | sprite/wing/<id> — game PNGs streamed by the same file: lane (session 4: the SPA's icons).
            String what = rest.substring(7);
            String family = "hull";
            String id = what;
            int slash = what.indexOf('/');
            if (slash > 0) {
                family = what.substring(0, slash);
                id = what.substring(slash + 1);
            }
            if (!EditorText.isId(id)) {
                HttpExchange.replyJson(out, 400, error("bad " + family + " id"));
                return;
            }
            byte[] png;
            synchronized (EditorOracle.LOCK) {
                if ("weapon".equals(family)) {
                    String kind = req.queryParam("kind");
                    png = EditorAssets.weaponSpritePng(id, kind == null || kind.isEmpty() ? "turret" : kind);
                } else if ("hullmod".equals(family)) {
                    png = EditorAssets.hullmodSpritePng(id);
                } else if ("wing".equals(family)) {
                    png = EditorAssets.wingSpritePng(id);
                } else if ("system".equals(family)) {
                    png = EditorCodex.systemSpritePng(id);   // The codex's ship-system icons
                } else if ("skill".equals(family)) {
                    png = EditorCodex.skillSpritePng(id);    // The codex's skill icons
                } else if ("codex".equals(family)) {
                    png = EditorCodex.codexIconPng(id);      // The category glyphs (a static key set)
                } else if ("hull".equals(family)) {
                    ShipHullSpecAPI spec = EditorOracle.hullOrNull(id);
                    png = spec == null ? null : EditorAssets.spritePng(spec);
                } else {
                    png = null;
                }
            }
            if (png == null) {
                HttpExchange.replyJson(out, 404, error("no sprite for that " + family));
            } else {
                HttpExchange.replyCacheable(out, "image/png", png); // game data: immutable for the game's lifetime
            }
        } else if (get && "codex/tree".equals(rest)) {
            // the game's OWN codex tree, serialized once per process
            byte[] tree;
            synchronized (EditorOracle.LOCK) {
                tree = EditorCodex.treeJsonBytes();
            }
            if (tree == null) {
                HttpExchange.replyJson(out, 503, error("the game has not built its codex yet"));
            } else {
                HttpExchange.replyCacheable(out, "application/json", tree);
            }
        } else if (get && rest.startsWith("codex/entry/")) {
            String id = rest.substring(12);
            if (!EditorText.isId(id)) {
                HttpExchange.replyJson(out, 400, error("bad codex id"));
                return;
            }
            boolean forBuild = "1".equals(req.queryParam("b"));   // The editor's hover view details a locked spec
            JSONObject entry;
            synchronized (EditorOracle.LOCK) {
                entry = EditorCodex.entry(id, forBuild);
            }
            if (entry == null) {
                HttpExchange.replyJson(out, 404, error("no such codex entry"));
            } else if (entry.optBoolean("locked", false)) {
                HttpExchange.replyJson(out, 423, error("locked entry"));
            } else {
                HttpExchange.replyJson(out, 200, entry.toString());
            }
        } else if (rest.startsWith("codex/")) {
            HttpExchange.replyJson(out, get ? 404 : 405, error(get ? "no such route" : "GET only for that route"));
        } else if (get && rest.startsWith("font/")) {
            // font/<name> — the game's own TrueType faces (allowlisted), streamed from the install for the SPA.
            String name = rest.substring(5);
            byte[] ttf = EditorText.isId(name) ? EditorAssets.fontTtf(name) : null;
            if (ttf == null) {
                HttpExchange.replyJson(out, 404, error("no such font"));
            } else {
                HttpExchange.replyCacheable(out, "font/ttf", ttf);
            }
        } else if (get && rest.startsWith("sound/")) {
            // sound/<id> — the refit screen's cues (iteration 5 row (2)): a static id → file
            // map in the oracle, streamed read-only from the install; id-law-gated, off-map → 404.
            String id = rest.substring(6);
            if (!EditorText.isId(id)) {
                HttpExchange.replyJson(out, 400, error("bad sound id"));
                return;
            }
            byte[] ogg = EditorAssets.soundOgg(id);
            if (ogg == null) {
                HttpExchange.replyJson(out, 404, error("no such sound"));
            } else {
                HttpExchange.replyCacheable(out, "audio/ogg", ogg);
            }
        } else if (get && "saved".equals(rest)) {
            // the saved builds — the editor's own index ∩ the files that exist
            JSONObject o;
            synchronized (EditorOracle.LOCK) {
                o = EditorOracle.savedList();
            }
            HttpExchange.replyJson(out, 200, o.toString());
        } else if (get && "server".equals(rest)) {
            // iteration 9 (owner #4): the server's builds — the join window's published id list, resolved against the
            // game's variant registry (a lawless / unknown id is skipped / greyed; no file is ever named by a request)
            JSONObject o;
            synchronized (EditorOracle.LOCK) {
                o = EditorOracle.serverBuildsList();
                ServerBuildFly.annotatePeers(o);   // A peer row a Fly already copied names its local stem
            }
            HttpExchange.replyJson(out, 200, o.toString());
        } else if (rest.startsWith("server/")) {
            // GET server/<id> = the registered variant as a draft document; the id passes the id law BEFORE the registry
            // is asked; unknown ⇒ 404. POST server/<id>/fly = that PUBLISHED build becomes the
            // CURRENT BUILD (ServerBuildFly re-checks membership in the roster / peers group, fail-closed: a lawful
            // id the server never offered is 404, never written); any other non-GET ⇒ 405.
            String tail = rest.substring(7);
            boolean fly = !get && tail.endsWith("/fly");
            String id = fly ? tail.substring(0, tail.length() - 4) : tail;
            if (!EditorText.isId(id)) {
                HttpExchange.replyJson(out, 400, error("bad build id"));
                return;
            }
            if (!get && !fly) {
                HttpExchange.replyJson(out, 405, error("GET only for that route"));
                return;
            }
            JSONObject o;
            synchronized (EditorOracle.LOCK) {
                o = fly ? ServerBuildFly.fly(id) : EditorOracle.serverBuildLoad(id);
            }
            if (o == null) {
                HttpExchange.replyJson(out, 404, error("no such server build"));
                return;
            }
            if (fly) {
                // The live half — /ship <id> (roster) or a VSUB (peer copy) — lock-free
                FlyLive.followUp(o, o.optString("kind", "roster"));
            }
            HttpExchange.replyJson(out, 200, o.toString());
        } else if ("catalog".equals(rest) || rest.startsWith("catalog/")) {
            // The dev-stage-only Mod catalog lane — armed ONLY by
            // -Dcc.editor.catalogdir (render-editor.ps1 -Mode launch); every shipped launch line leaves
            // it unset, so these answer 404 and the SPA never draws the card. Stems are id-law-gated
            // BEFORE any file access; the host writes only saves/common/cc_variants/_catalog/ markers.
            if (!EditorCatalog.armed()) {
                HttpExchange.replyJson(out, 404, error("no such route"));
                return;
            }
            JSONObject o;
            if (get && "catalog".equals(rest)) {
                synchronized (EditorOracle.LOCK) {
                    o = EditorCatalog.list();
                }
            } else if (!get && "catalog/promote".equals(rest)) {
                ClientVariantDefinition def = body(req);
                synchronized (EditorOracle.LOCK) {
                    o = EditorCatalog.promote(def);
                }
            } else if (!get && rest.startsWith("catalog/") && rest.endsWith("/save")) {
                // POST catalog/<stem>/save = the current build as a promotion marker under that LISTED stem (in-place update)
                String stem = catalogStem(rest, "/save");
                if (stem == null || !EditorText.isId(stem)) {
                    HttpExchange.replyJson(out, 400, error("bad catalog id"));
                    return;
                }
                ClientVariantDefinition def = body(req);
                synchronized (EditorOracle.LOCK) {
                    o = EditorCatalog.promote(def, stem);
                }
            } else if (!get && rest.startsWith("catalog/") && rest.endsWith("/remove")) {
                String stem = catalogStem(rest, "/remove");
                if (stem == null || !EditorText.isId(stem)) {
                    HttpExchange.replyJson(out, 400, error("bad catalog id"));
                    return;
                }
                synchronized (EditorOracle.LOCK) {
                    o = EditorCatalog.remove(stem);
                }
            } else if (!get && rest.startsWith("catalog/") && rest.endsWith("/cancel")) {
                // POST catalog/<stem>/cancel = the card's Undo of a pending promote / demote (index only)
                String stem = catalogStem(rest, "/cancel");
                if (stem == null || !EditorText.isId(stem)) {
                    HttpExchange.replyJson(out, 400, error("bad catalog id"));
                    return;
                }
                synchronized (EditorOracle.LOCK) {
                    o = EditorCatalog.cancel(stem);
                }
            } else if (get && rest.startsWith("catalog/")) {
                // GET catalog/<stem> = a listed mod variant as a draft (the server/<id> shape); unlisted ⇒ 404
                String stem = rest.substring(8);
                if (!EditorText.isId(stem)) {
                    HttpExchange.replyJson(out, 400, error("bad catalog id"));
                    return;
                }
                synchronized (EditorOracle.LOCK) {
                    o = EditorCatalog.load(stem);
                }
                if (o == null) {
                    HttpExchange.replyJson(out, 404, error("no such catalog variant"));
                    return;
                }
            } else {
                HttpExchange.replyJson(out, get ? 404 : 405, error(get ? "no such route" : "GET only for that route"));
                return;
            }
            HttpExchange.replyJson(out, o.optBoolean("ok", true) ? 200 : 422, o.toString());
        } else if (rest.startsWith("saved/")) {
            // GET saved/<stem> = the stored document; POST saved/<stem>/delete = remove it; POST saved/<stem>/fly =
            // make it the CURRENT BUILD. The stem passes the id law BEFORE any file access; the path
            // is composed by the oracle, never from the request.
            String tail = rest.substring(6);
            boolean del = !get && tail.endsWith("/delete");
            boolean fly = !get && tail.endsWith("/fly");
            String stem = del ? tail.substring(0, tail.length() - 7) : fly ? tail.substring(0, tail.length() - 4) : tail;
            if (!EditorText.isId(stem)) {
                HttpExchange.replyJson(out, 400, error("bad build id"));
                return;
            }
            if (!get && !del && !fly) {
                HttpExchange.replyJson(out, 405, error("GET only for that route"));
                return;
            }
            // the Fly's keep-only ask (flyKeepOnly), law-checked BEFORE the locked half writes current.data:
            // a body that is not a JSON object, or a keepOnly that is not a JSON boolean, is a 400 and nothing is flown
            Boolean keepOnly = fly ? flyKeepOnly(req.body) : Boolean.FALSE;
            if (keepOnly == null) {
                HttpExchange.replyJson(out, 400, error("bad fly body"));
                return;
            }
            JSONObject o;
            synchronized (EditorOracle.LOCK) {
                o = del ? EditorOracle.savedDelete(stem) : fly ? EditorOracle.savedFly(stem, keepOnly) : EditorOracle.savedLoad(stem);
            }
            if (o == null) {
                HttpExchange.replyJson(out, 404, error("no such saved build"));
                return;
            }
            if (fly) {
                // Fly = Save + SUBMIT while live (the one verb; the Submit button is gone) — lock-free
                FlyLive.followUp(o, "local", keepOnly);
            }
            HttpExchange.replyJson(out, 200, o.toString());
        } else if (!get && "validate".equals(rest)) {
            ClientVariantDefinition def = body(req);
            EditorTier.Tier tier = EditorTier.Tier.parseOrDefault(req.queryParam("tier"));
            boolean allSkills = flag(req, "allSkills");
            // takes the oracle LOCK itself for the API half only; the game-thread wait runs lock-free
            JSONObject o = EditorOracle.validate(def, tier, allSkills);
            HttpExchange.replyJson(out, 200, o.toString());
        } else if (!get && "submit".equals(rest)) {
            // in-match only; lock-free — the VACK wait must not stall the other routes
            JSONObject o = EditorOracle.submit(body(req));
            HttpExchange.replyJson(out, 200, o.toString());
        } else if (!get && "stats".equals(rest)) {
            ClientVariantDefinition def = body(req);
            boolean allSkills = flag(req, "allSkills");
            // the CR preview bar — an int 0..100, absent / lawless = 100
            int crPct = intParam(req, "cr", 100, 0, 100);
            // skills=1 applies the bastion seat's skill sets to the detached member (EditorStats);
            // absent / lawless = off — the toggle drives /stats ONLY (/validate keeps the union person's OP + S-mod law)
            boolean skills = flag(req, "skills");
            JSONObject o;
            synchronized (EditorOracle.LOCK) {
                o = EditorStats.stats(def, allSkills, skills, crPct);
            }
            HttpExchange.replyJson(out, 200, o.toString());
        } else if (!get && "save".equals(rest)) {
            ClientVariantDefinition def = body(req);
            // ?keep=1 = the host card's Promote: the save never auto-selects the build as the CURRENT BUILD;
            //a plain Save keeps the first-time auto-select
            boolean keep = flag(req, "keep");
            JSONObject o;
            synchronized (EditorOracle.LOCK) {
                o = EditorOracle.save(def, null, !keep);
            }
            HttpExchange.replyJson(out, 200, o.toString());
        } else if (!get && "autogroups".equals(rest)) {
            // iteration 6 row (d): the game's own auto-assign on a detached variant (same body law as /stats)
            ClientVariantDefinition def = body(req);
            JSONObject o;
            synchronized (EditorOracle.LOCK) {
                o = EditorOracle.autoGroups(def);
            }
            HttpExchange.replyJson(out, 200, o.toString());
        } else if (rest.startsWith("host/")) {
            hostRoute(req, rest.substring(5), get, out);
        } else if (get) {
            HttpExchange.replyJson(out, 404, error("no such route"));
        } else {
            HttpExchange.replyJson(out, 405, error("GET only for that route"));
        }
    }

    /**
     * The host card's routes ({@link HostShips}; lock-free: each blocks on the server's answer). Order: the method
     * (405), the body / id law (400, before anything is sent), then the gate (403 unless live AND the seat is
     * admin; the server re-checks its own session flag on every verb). {@code host/signin} has no 403: it answers
     * {@code not-live} / {@code refused} itself, and its body is never logged, echoed or put in an error.
     *
     */
    private static void hostRoute(HttpExchange req, String what, boolean get, OutputStream out)
            throws IOException, JSONException {
        if ("ships".equals(what)) {
            if (!get) {
                HttpExchange.replyJson(out, 405, error("GET only for that route"));
                return;
            }
            if (!HostShips.admin()) {
                HttpExchange.replyJson(out, 403, error("sign in as the host first"));
                return;
            }
            HttpExchange.replyJson(out, 200, HostShips.ships().toString());
        } else if ("signin".equals(what)) {
            if (get) {
                HttpExchange.replyJson(out, 405, error("POST only for that route"));
                return;
            }
            String secret = HostShips.bodyField(req.body, "secret");
            if (secret == null) {
                HttpExchange.replyJson(out, 400, error("bad sign-in request"));
                return;
            }
            JSONObject o = new JSONObject();
            o.put("state", HostShips.signIn(secret));
            HttpExchange.replyJson(out, 200, o.toString());
        } else if ("keep".equals(what) || "remove".equals(what) || "restore".equals(what)) {
            if (get) {
                HttpExchange.replyJson(out, 405, error("POST only for that route"));
                return;
            }
            String id = HostShips.bodyField(req.body, "id");
            if (id == null || !EditorText.isId(id)) {
                HttpExchange.replyJson(out, 400, error("bad ship id"));
                return;
            }
            // The card's Promote on a CHANGED build loaded from X posts {id, replaces: X} and the verb becomes
            // "/shipkeep <id> replaces <X>"; without this hop a Promote ADDED a second ship
            // beside X. Only keep carries it; a replaces off the id law is a 400 BEFORE the admin gate (a malformed
            // ask is never answered "sign in first").
            String replaces = "keep".equals(what) ? HostShips.bodyField(req.body, "replaces") : null;
            if (replaces != null && !EditorText.isId(replaces)) {
                HttpExchange.replyJson(out, 400, error("bad replaced ship id"));
                return;
            }
            if (!HostShips.admin()) {
                HttpExchange.replyJson(out, 403, error("sign in as the host first"));
                return;
            }
            HttpExchange.replyJson(out, 200, HostShips.verb("ship" + what, id, replaces).toString());
        } else {
            HttpExchange.replyJson(out, get ? 404 : 405, error("no such route"));
        }
    }

    private static void data(HttpExchange req, String what, OutputStream out)
            throws IOException, JSONException {
        EditorTier.Tier tier = EditorTier.Tier.parseOrDefault(req.queryParam("tier"));
        String json;
        synchronized (EditorOracle.LOCK) {
            if ("hulls".equals(what)) {
                json = EditorOracle.hulls(tier).toString();
            } else if ("weapons".equals(what)) {
                json = EditorOracle.weapons(tier).toString();
            } else if ("hullmods".equals(what)) {
                json = EditorOracle.hullMods(tier).toString();
            } else if ("wings".equals(what)) {
                json = EditorOracle.wings(tier).toString();
            } else if (what.startsWith("hull/")) {
                String id = what.substring(5);
                if (!EditorText.isId(id)) {
                    HttpExchange.replyJson(out, 400, error("bad hull id"));
                    return;
                }
                JSONObject o = EditorOracle.hull(id, tier, flag(req, "allSkills"));
                if (o == null) {
                    HttpExchange.replyJson(out, 404, error("no such hull"));
                    return;
                }
                json = o.toString();
            } else {
                HttpExchange.replyJson(out, 404, error("no such data set"));
                return;
            }
        }
        HttpExchange.replyJson(out, 200, json);
    }

    private static ClientVariantDefinition body(HttpExchange req) {
        if (req.body.length == 0) {
            throw new IllegalArgumentException("a .variant JSON body is required");
        }
        return ClientVariantJson.parse(new String(req.body, StandardCharsets.UTF_8));
    }

    private static boolean flag(HttpExchange req, String name) {
        String v = req.queryParam(name);
        return v != null && ("1".equals(v) || "true".equalsIgnoreCase(v));
    }

    /**
     * The keep-only ask of a {@code POST saved/<stem>/fly} body:
     * the host card's Promote posts {@code {"keepOnly": true}} (the build is submitted so the server mints and
     * keeps it, but it must NOT become the host's next respawn); every other Fly posts {@code {}} or nothing.
     * Returns TRUE / FALSE, or {@code null} = refused: a non-empty body that is not a JSON object, or a
     * {@code keepOnly} that is present but not a JSON boolean (a string "true" is refused, never coerced). Only
     * the saved-build fly reads it — {@code server/<id>/fly} flies a PUBLISHED build and never reads its body.
     */
    static Boolean flyKeepOnly(byte[] body) {
        if (body == null || body.length == 0) {
            return Boolean.FALSE;
        }
        Object v;
        try {
            v = new JSONObject(new String(body, StandardCharsets.UTF_8)).opt("keepOnly");
        } catch (JSONException notAnObject) {
            flyBodyRefusals++;
            PilotWatchLog.info("editor fly: body refused (" + notAnObject.getClass().getSimpleName()
                    + ", refusals=" + flyBodyRefusals + ")");
            return null;
        }
        if (v == null) {
            return Boolean.FALSE;
        }
        return v instanceof Boolean ? (Boolean) v : null;
    }

    /** Fly bodies refused by {@link #flyKeepOnly} (not a JSON object) since load; the log row's count. */
    private static int flyBodyRefusals;

    /** A bounded int query param: ≤ 4 chars of digits (an optional sign), clamped to [lo, hi]; anything else = the
     *  fallback (a query string is DATA — never a float, never a throw). */
    private static int intParam(HttpExchange req, String name, int fallback, int lo, int hi) {
        String v = req.queryParam(name);
        if (v == null || v.isEmpty() || v.length() > 4 || !v.matches("-?[0-9]{1,3}")) {
            return fallback;
        }
        return Math.max(lo, Math.min(hi, Integer.parseInt(v)));
    }

    /** Allowlisted jar resource {@code /web/<name>} (decision (5)); a name off the list is a 404. */
    private static void asset(String name, OutputStream out) throws IOException {
        if (!ASSETS.contains(name)) {
            HttpExchange.replyText(out, 404, "no such asset");
            return;
        }
        // Dev override (presentation iteration loop): -Dcc.editor.webdir=<dir> serves the
        // SAME allowlisted names from that directory (edit → reload, no jar rebuild) over the proven
        // `file:` lane (java.io.File is B-denied). The name is never request-derived (it
        // passed the ASSETS set above); the property is unset in every shipped launch line.
        String devDir = System.getProperty("cc.editor.webdir");
        InputStream in = null;
        if (devDir != null && !devDir.isEmpty()) {
            try {
                in = new java.net.URL("file:/" + devDir.replace('\\', '/') + "/" + name).openStream();
            } catch (IOException missing) {
                in = null; // fall through to the packed asset
            }
        }
        if (in == null) {
            in = EditorRoutes.class.getResourceAsStream("/web/" + name);
        }
        if (in == null) {
            HttpExchange.replyText(out, 404, "asset not packed in this build");
            return;
        }
        byte[] bytes;
        try {
            bytes = EditorTier.readAll(in, 4 * 1024 * 1024);
        } finally {
            in.close();
        }
        String type = name.endsWith(".js") ? "text/javascript; charset=utf-8"
                : name.endsWith(".css") ? "text/css; charset=utf-8" : "text/html; charset=utf-8";
        HttpExchange.reply(out, 200, type, bytes);
    }

    static boolean isOurHost(String host, int port) {
        String h = host.trim().toLowerCase(java.util.Locale.ROOT);
        return h.equals("127.0.0.1:" + port) || h.equals("localhost:" + port);
    }

    static boolean isOurOrigin(String origin, int port) {
        String o = origin.trim().toLowerCase(java.util.Locale.ROOT);
        return o.equals("http://127.0.0.1:" + port) || o.equals("http://localhost:" + port);
    }

    private static synchronized void noteTokenMiss(String path) {
        tokenMisses++;
        if (tokenMisses <= MAX_LOGGED_TOKEN_MISSES || tokenMisses % 100 == 0) {
            PilotWatchLog.info("editor: token mismatch #" + tokenMisses + " (path length " + path.length() + ")");
        }
    }

    /** The {@code <stem>} of a {@code catalog/<stem>/<verb>} path, or null when the path carries
     *  none — a stemless POST ({@code catalog/save}, {@code catalog/remove}, {@code catalog/cancel}) used to reach
     *  {@code substring(8, 7)} → StringIndexOutOfBounds → a 500; a parser fails CLEANLY (400 "bad catalog id"). */
    private static String catalogStem(String rest, String verbSuffix) {
        int end = rest.length() - verbSuffix.length();
        return end > "catalog/".length() ? rest.substring("catalog/".length(), end) : null;
    }

    private static String error(String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("error", message);
        } catch (JSONException impossible) {
            return "{\"ok\":false}";
        }
        return o.toString();
    }

    private static String safeState() {
        try {
            return String.valueOf(Global.getSettings().getCurrentState());
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    /** {@code live} while the app-scoped predict session exists and is not defunct (a kept-open session
     *  after a match still counts — the server still answers it), else {@code none}. */
    private static String liveSession() {
        try {
            cc.starsector.client.net.PredictSession s = CcClientModPlugin.predictSession();
            return s != null && !s.isDefunct() ? "live" : "none";
        } catch (RuntimeException e) {
            return "none";
        }
    }
}
