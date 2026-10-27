package cc.starsector.client.join;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import cc.starsector.client.editor.HttpExchange;

/**
 * The join responder's route table (GUI v2 design; join-wire security model).
 * The editor's dispatch skeleton ({@code EditorRoutes.handle}),
 * copied: every request carries the per-process token as its FIRST path segment ({@code /<token>/…}; a miss is a
 * bare 403 that never echoes the path); a {@code Host} header must name this loopback port (DNS-rebinding guard,
 * {@code 127.0.0.1} / {@code localhost} / {@code [::1]}); a POST with an {@code Origin} must originate from it.
 * Static assets come from the jar ({@code /web/join/<name>}) against {@link #ASSETS}, never a request-derived
 * path. Request bodies are {@code application/x-www-form-urlencoded} fields ({@link #field}, per-field
 * length-capped after decoding — over the cap is a 422, never a truncation) — the match password rides a POST
 * body, never a URL.
 *
 * <pre>
 *  GET  /                     the SPA (web/join/index.html; a bare /&lt;token&gt; 302s to /&lt;token&gt;/)
 *  GET  /app.js /app.css      allowlisted static asset from the jar
 *  GET  /api/ping             liveness (the single-instance check's target)
 *  GET  /api/state            form prefill — NEVER the password ({@code hasPassword} only); the launch face after
 *  POST /api/alive            the SPA heartbeat (2 s): "connected" for the page + the first one ends the never-opened
 *                             fallback; NEVER a lifetime signal — the window lives while the LAUNCHER ticks
 *  POST /api/paste            the connect code box: a connect brick fills 3 fields; anything else is refused unparsed
 *                             (an emptied box + the committed pair re-probes when it cleared a staged match)
 *  POST /api/probe            the ship-list PROBE (DoorProbe, off the accept thread; gen-guarded)
 *  POST /api/launch           the press-time gates (JoinLaunchGates, v1 order) + the FILE hand-off; the gate's word
 *  POST /api/cancel           .cancel marker + exit (form state only)
 *  GET  /api/editor           the RUNNING game's editor URL when its endpoint file carries OUR nonce (step 4)
 *  GET  /api/voice/devices    the capture mixers (JoinVoiceCore; the saved pick preselected)
 *  POST /api/voice/test       action=start|stop|gain (+ device index, gain percent) — the Test worker
 *  GET  /api/voice/level      the Test bar: running / level 0..100 / status line (the SPA polls 10 Hz)
 * </pre>
 */
final class JoinRoutes implements JoinHost.Router {
    static final Set<String> ASSETS = new HashSet<>(Arrays.asList("index.html", "app.js", "app.css"));
    static final String RESOURCE_ROOT = "/web/join/";
    static final int ASSET_MAX_BYTES = 1024 * 1024;
    static final int MAX_LOGGED_TOKEN_MISSES = 5;

    /** Per-field caps (chars, after decoding): a hostname, a port, a pilot name (the plan caps at 24 — the field
     *  cap is the line the 422 draws), a password (the law refuses over 64), a ship id (64 + slack), the paste
     *  (a full descriptor is a few KB; {@link HttpExchange} caps the whole body at 64 KB), a boolean word. */
    static final int HOST_MAX = 256;
    static final int PORT_MAX = 8;
    static final int NAME_MAX = 64;
    static final int PASSWORD_MAX = 128;
    static final int SHIP_MAX = 128;
    static final int PASTE_MAX = 32768;
    static final int WORD_MAX = 8;

    /** What the routes need from the window session (implemented by {@code JoinBrowserUi}). */
    interface Session {
        /** {@code form} | {@code launched} | {@code cancelled}. */
        String state();

        /** The {@code api/state} body (a rendered JSON object). */
        String stateJson();

        /** One SPA heartbeat arrived (a connectivity signal, never a lifetime one). */
        void alive();

        /** True while the form is still open (Cancel is possible). */
        boolean inForm();

        /** The Cancel button: the {@code .cancel} marker, then exit. */
        void cancel();

        long uptimeMs();

        /** The paste box's text → a rendered JSON verdict ({@code kind}: brick / empty, or an error).
         *  {@code host} non-null = the page's committed pair — an
         *  emptied box that cleared a staged match then re-probes and the verdict carries {@code probe} (the
         *  {@link #probe} body); null = never a probe (a raw client, an uncommitted password). */
        String paste(String text, String host, String port, String password);

        /** The ship-list probe → a rendered JSON result ({@code status}: not_ready / served / rejected / error / stale). */
        String probe(String host, String port, String password);

        /** The Launch button → a rendered JSON verdict ({@code launched:true}, or the refusing gate's word + text);
         *  {@code voice} = the Options row's pick (null = the page sent none: the voice file is left alone). */
        String launch(String host, String port, String name, String password, String ship, boolean logs,
                JoinVoiceCore.Pick voice, int team);

        /** GET api/editor: the running game's editor URL bound by nonce, or {@code ok:false} + the reason line. */
        String editor();

        /** GET api/voice/devices: {@link JoinVoiceCore#devicesJson}. */
        String voiceDevices();

        /** POST api/voice/test: {@code action} = start (device index + gain percent) / stop / gain. */
        String voiceTest(String action, String device, String gain);

        /** GET api/voice/level: the Test bar's state. */
        String voiceLevel();

        /** GET font/{@code name}: an allowlisted game TrueType face's bytes from the install (the editor's
         *  own font route, mirrored so the join page wears the editor's display face); null = no such font. */
        byte[] font(String name);
    }

    private final Session session;
    private int tokenMisses;

    JoinRoutes(Session session) {
        this.session = session;
    }

    @Override
    public void handle(HttpExchange req, OutputStream out, String token, int port) throws IOException {
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
        } catch (IllegalArgumentException law) {
            HttpExchange.replyJson(out, 422, JoinJson.error(bounded(law.getMessage(), 200)));
        } catch (IOException io) {
            throw io;
        } catch (RuntimeException fault) {
            // the fault's class only — never a message that could carry request text
            System.err.println("join-routes: fault " + fault.getClass().getName() + " on route length " + rest.length());
            HttpExchange.replyJson(out, 500, JoinJson.error(fault.getClass().getSimpleName()));
        }
    }

    private void route(HttpExchange req, String rest, OutputStream out) throws IOException {
        boolean get = "GET".equals(req.method);
        if (rest.isEmpty() || "index.html".equals(rest) || ASSETS.contains(rest)) {
            if (!get) {
                HttpExchange.replyText(out, 405, "method not allowed");
                return;
            }
            asset(rest.isEmpty() ? "index.html" : rest, out);
        } else if (get && rest.startsWith("font/")) {
            // font/<name> — the game's own TrueType faces (allowlisted in the session), streamed from the
            // install for the SPA's @font-face; the name is gated before any file is named (iteration 9)
            String name = rest.substring("font/".length());
            byte[] ttf = name.length() <= 32 && name.matches("[a-z0-9-]+") ? session.font(name) : null;
            if (ttf == null) {
                HttpExchange.replyText(out, 404, "no such font");
                return;
            }
            HttpExchange.replyCacheable(out, "font/ttf", ttf);
        } else if ("api/ping".equals(rest)) {
            if (!get) {
                HttpExchange.replyText(out, 405, "method not allowed");
                return;
            }
            HttpExchange.replyJson(out, 200, new JoinJson().put("ok", true).put("app", "join")
                    .put("state", session.state()).put("uptimeMs", session.uptimeMs()).toString());
        } else if ("api/state".equals(rest)) {
            if (!get) {
                HttpExchange.replyText(out, 405, "method not allowed");
                return;
            }
            HttpExchange.replyJson(out, 200, session.stateJson());
        } else if ("api/alive".equals(rest)) {
            if (get) {
                HttpExchange.replyText(out, 405, "method not allowed");
                return;
            }
            session.alive();
            HttpExchange.replyJson(out, 200, new JoinJson().put("ok", true).put("state", session.state()).toString());
        } else if ("api/cancel".equals(rest)) {
            if (get) {
                HttpExchange.replyText(out, 405, "method not allowed");
                return;
            }
            if (!session.inForm()) {
                // after Launch the plan is the launcher's: the face has no Cancel (v1: Close only)
                HttpExchange.replyJson(out, 200, JoinJson.error("already launched - the game is not affected by"
                        + " this page; close the tab whenever you like"));
                return;
            }
            // reply first: the session's exit may tear the socket down under us
            HttpExchange.replyJson(out, 200, JoinJson.ok());
            session.cancel();
        } else if ("api/paste".equals(rest)) {
            if (get) {
                HttpExchange.replyText(out, 405, "method not allowed");
                return;
            }
            // The pair rides only when the page sends it (the
            // api/probe field caps); its absence = never a probe with blank fields
            boolean reprobe = hasField(req.body, "host");
            HttpExchange.replyJson(out, 200, session.paste(field(req.body, "text", PASTE_MAX),
                    reprobe ? field(req.body, "host", HOST_MAX) : null,
                    reprobe ? field(req.body, "port", PORT_MAX) : null,
                    reprobe ? field(req.body, "password", PASSWORD_MAX) : null));
        } else if ("api/probe".equals(rest)) {
            if (get) {
                HttpExchange.replyText(out, 405, "method not allowed");
                return;
            }
            HttpExchange.replyJson(out, 200, session.probe(field(req.body, "host", HOST_MAX),
                    field(req.body, "port", PORT_MAX), field(req.body, "password", PASSWORD_MAX)));
        } else if ("api/launch".equals(rest)) {
            if (get) {
                HttpExchange.replyText(out, 405, "method not allowed");
                return;
            }
            boolean logs = !"off".equalsIgnoreCase(field(req.body, "logs", WORD_MAX));
            // the Options row rides four voice fields; a body WITHOUT them (a raw client) leaves the voice file alone
            JoinVoiceCore.Pick voice = hasField(req.body, "voiceOn")
                    ? JoinVoiceCore.parsePick(field(req.body, "voiceDevice", WORD_MAX), field(req.body, "voiceOn", WORD_MAX),
                            field(req.body, "voiceGain", WORD_MAX), field(req.body, "voiceQuality", WORD_MAX))
                    : null;
            HttpExchange.replyJson(out, 200, session.launch(field(req.body, "host", HOST_MAX),
                    field(req.body, "port", PORT_MAX), field(req.body, "name", NAME_MAX),
                    field(req.body, "password", PASSWORD_MAX), field(req.body, "ship", SHIP_MAX), logs, voice,
                    teamField(req.body)));
        } else if ("api/editor".equals(rest)) {
            if (!get) {
                HttpExchange.replyText(out, 405, "method not allowed");
                return;
            }
            HttpExchange.replyJson(out, 200, session.editor());
        } else if ("api/voice/devices".equals(rest)) {
            if (!get) {
                HttpExchange.replyText(out, 405, "method not allowed");
                return;
            }
            HttpExchange.replyJson(out, 200, session.voiceDevices());
        } else if ("api/voice/test".equals(rest)) {
            if (get) {
                HttpExchange.replyText(out, 405, "method not allowed");
                return;
            }
            HttpExchange.replyJson(out, 200, session.voiceTest(field(req.body, "action", WORD_MAX),
                    field(req.body, "device", WORD_MAX), field(req.body, "gain", WORD_MAX)));
        } else if ("api/voice/level".equals(rest)) {
            if (!get) {
                HttpExchange.replyText(out, 405, "method not allowed");
                return;
            }
            HttpExchange.replyJson(out, 200, session.voiceLevel());
        } else {
            HttpExchange.replyText(out, 404, "not found");
        }
    }

    /** Allowlisted jar resource {@code /web/join/<name>}; a name off the list is a 404. */
    private static void asset(String name, OutputStream out) throws IOException {
        if (!ASSETS.contains(name)) {
            HttpExchange.replyText(out, 404, "no such asset");
            return;
        }
        // Dev override (the editor's -Dcc.editor.webdir idiom): -Dcc.join.webdir=<dir> serves the SAME allowlisted
        // names from that directory (edit → reload, no jar rebuild). The name is never request-derived (it passed
        // the ASSETS set above); the property is unset in every shipped launch line.
        String devDir = System.getProperty("cc.join.webdir");
        byte[] bytes = null;
        if (devDir != null && !devDir.isEmpty()) {
            try {
                Path p = Paths.get(devDir).resolve(name);
                if (Files.isRegularFile(p) && Files.size(p) <= ASSET_MAX_BYTES) {
                    bytes = Files.readAllBytes(p);
                }
            } catch (IOException | RuntimeException missing) {
                bytes = null; // fall through to the packed asset
            }
        }
        if (bytes == null) {
            InputStream in = JoinRoutes.class.getResourceAsStream(RESOURCE_ROOT + name);
            if (in == null) {
                HttpExchange.replyText(out, 404, "asset not packed in this build");
                return;
            }
            try {
                bytes = readAll(in, ASSET_MAX_BYTES);
            } finally {
                in.close();
            }
        }
        String type = name.endsWith(".js") ? "text/javascript; charset=utf-8"
                : name.endsWith(".css") ? "text/css; charset=utf-8" : "text/html; charset=utf-8";
        HttpExchange.reply(out, 200, type, bytes);
    }

    static byte[] readAll(InputStream in, int max) throws IOException {
        byte[] buf = new byte[8192];
        byte[] acc = new byte[0];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (acc.length + n > max) {
                throw new IOException("asset over " + max + " bytes");
            }
            byte[] grown = Arrays.copyOf(acc, acc.length + n);
            System.arraycopy(buf, 0, grown, acc.length, n);
            acc = grown;
        }
        return acc;
    }

    /** {@code Host} allow-list: this loopback port under its three spellings. */
    static boolean isOurHost(String host, int port) {
        String h = host.trim().toLowerCase(Locale.ROOT);
        return h.equals("127.0.0.1:" + port) || h.equals("localhost:" + port) || h.equals("[::1]:" + port);
    }

    static boolean isOurOrigin(String origin, int port) {
        String o = origin.trim().toLowerCase(Locale.ROOT);
        return o.equals("http://127.0.0.1:" + port) || o.equals("http://localhost:" + port)
                || o.equals("http://[::1]:" + port);
    }

    private void noteTokenMiss(String path) {
        tokenMisses++;
        if (tokenMisses <= MAX_LOGGED_TOKEN_MISSES || tokenMisses % 100 == 0) {
            System.err.println("join-routes: token mismatch #" + tokenMisses + " (path length " + path.length() + ")");
        }
    }

    /**
     * One {@code application/x-www-form-urlencoded} field out of a POST body, percent-decoded ({@code +} = space);
     * null when absent or undecodable. Bodies are already bounded by {@link HttpExchange}; a field is capped at
     * {@code max} chars AFTER decoding (an over-long field is null, never truncated — the caller refuses).
     */
    static String formField(byte[] body, String name, int max) {
        String text = new String(body, StandardCharsets.UTF_8);
        for (String pair : text.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            if (!k.equals(name)) {
                continue;
            }
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                String decoded = URLDecoder.decode(v, StandardCharsets.UTF_8);
                return decoded.length() > max ? null : decoded;
            } catch (RuntimeException bad) {
                return null;
            }
        }
        return null;
    }

    /** The launch's {@code team} field when it names no team: absent / empty (a raw client, an old page). */
    static final int TEAM_ABSENT = 0;
    /** The launch's {@code team} field when it is not one digit 0..2: the launch gate's {@code team} refusal. */
    static final int TEAM_UNLAWFUL = -1;

    /**
     * The launch's Team choice: absent / empty = 0 (the server picks); exactly {@code 0}, {@code 1} or {@code 2} =
     * that team; anything else (a {@code 3}, a sign, a space, two digits) = {@link #TEAM_UNLAWFUL}, which the launch
     * gate refuses with its word {@code team} — never clamped to a team the player did not pick. Over
     * {@link #WORD_MAX} chars = the field reader's 422 like every other field.
     *
     */
    static int teamField(byte[] body) {
        String raw = field(body, "team", WORD_MAX);
        if (raw.isEmpty()) {
            return TEAM_ABSENT;
        }
        return raw.length() == 1 && raw.charAt(0) >= '0' && raw.charAt(0) <= '2' ? raw.charAt(0) - '0'
                : TEAM_UNLAWFUL;
    }

    /**
     * The form routes' field reader: an ABSENT field is {@code ""} (the gates then say what is missing, in the
     * player's words); a field over {@code max} chars or with a malformed escape is an {@link IllegalArgumentException}
     * — the 422 path — naming the FIELD (a fixed word of ours), never its content.
     */
    static String field(byte[] body, String name, int max) {
        String text = new String(body, StandardCharsets.UTF_8);
        for (String pair : text.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            if (!k.equals(name)) {
                continue;
            }
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            String decoded;
            try {
                decoded = URLDecoder.decode(v, StandardCharsets.UTF_8);
            } catch (RuntimeException bad) {
                throw new IllegalArgumentException("field " + name + " is malformed");
            }
            if (decoded.length() > max) {
                throw new IllegalArgumentException("field " + name + " is over " + max + " characters");
            }
            return decoded;
        }
        return "";
    }

    /** True when the body names {@code name} at all (any length, any value) — presence, not validity. */
    static boolean hasField(byte[] body, String name) {
        String text = new String(body, StandardCharsets.UTF_8);
        for (String pair : text.split("&")) {
            int eq = pair.indexOf('=');
            if ((eq < 0 ? pair : pair.substring(0, eq)).equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** A message projected for a JSON error body: printable ASCII only, cut at {@code max}. */
    static String bounded(String s, int max) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(s.length(), max));
        for (int i = 0; i < s.length() && out.length() < max; i++) {
            char c = s.charAt(i);
            out.append(c >= 0x20 && c <= 0x7e ? c : '?');
        }
        return out.toString();
    }
}
