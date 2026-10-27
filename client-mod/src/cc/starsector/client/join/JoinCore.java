package cc.starsector.client.join;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cc.starsector.client.net.ClientVariantDefinition;
import cc.starsector.client.net.DoorVariantCheck;
import cc.starsector.client.net.VariantRejectHints;

/**
 * The joiner's Swing-FREE core: every law the join window
 * computes without a frame — the launch nonce, the local-customs scan + the editor's
 * {@code index.data} publish, the plan-file / cancel-marker writers, the launch-face text, the
 * companion's poll laws + {@code api/ping} reader, the kit-credential readers. Carved VERBATIM out
 * of {@link JoinGui} (which static-imports it: its call sites and {@code --selftest} rows read
 * unchanged) so the v2 browser joiner and the retained Swing
 * fallback share ONE copy. Package-private statics only; no Swing/AWT import may ever land here
 * (the v2 responder runs headless). B-deny: rides the mod jar like JoinGui — zero
 * classloader-B refs.
 */
final class JoinCore {
    private JoinCore() {
    }

    /**
     * Row (f) law 2: the launch nonce join-cc.bat minted (8 lowercase hex, charset-gated there
     * too) and handed both to this window and to the game as {@code -Dcc.editor.launch}; anything
     * else ⇒ null ⇒ the window binds to no game in particular (tooling launches, the old shape).
     */
    static String nonceArg(String raw) {
        return raw != null && raw.matches("[0-9a-f]{8}") ? raw : null;
    }
    /** W15 v0: the ids of the player's local custom variants — every
     *  {@code <install>/saves/common/cc_variants/<id>.variant.data} whose stem obeys the local
     *  file-id law (the game's common-file API throws on {@code ..}/{@code :}), is under the
     *  256 KB cap and names ITSELF ({@code "variantId":"<id>"} — the HELLO pick handle the mod's
     *  VSUB echoes; a mismatching file would never resolve the door's held pick). Sorted; a
     *  missing folder is the common case (empty). Never parses JSON here (no json.jar on the
     *  joiner's bare-jar classpath) — the in-game source applies the full law. */
    static List<String> localCustomIds(Path installRoot) {
        return localCustomReport(installRoot).ids;
    }

    /** W15 v1: the offered ids PLUS one plain-language status per file the
     *  player would otherwise lose silently — a bare {@code <id>.variant} (the game's common-file
     *  API appends {@code .data}, so the joiner and the in-game source both skip it: the #1 silent
     *  faceplant), an unlawful stem, an oversize/unreadable
     *  file, a {@code variantId} that does not name the stem, and the {@code displayName} law the
     *  in-game parse enforces (missing/blank/>48/non-ASCII THROWS there → no VSUB) —
     *  a displayName miss still OFFERS the id (the file exists and self-names) so the player fixes
     *  it and clicks Refresh. Notes carry ONLY the file stem (escaped at render) + fixed phrases,
     *  never the file's own text (player-authored = DATA). */
    static final class LocalCustoms {
        final List<String> ids = new java.util.ArrayList<>();
        final List<String> notes = new java.util.ArrayList<>();
        /** id → its {@code .variant.data} file (the dry-run parses the offerable ones). */
        final Map<String, Path> files = new LinkedHashMap<>();
        /** The id table the GAME cached at its last boot ({@code ClientIdTableBoot}) —
         *  the dry-run's u16 dictionary; null/absent = no dry-run ("checked at launch"). */
        Path idTablesFile;
        /** The mod set the GAME cached at its last boot ({@code ClientModSetBoot}) — the
         *  dry-run fold's third input; null/absent = no dry-run ("checked at launch"). */
        Path modSetFile;
    }

    /** The game-written customs dir under an install root (the ONE spelling).
     * */
    static Path customsDir(Path installRoot) {
        return installRoot.resolve("saves").resolve("common").resolve("cc_variants");
    }

    /** The two boot caches the GAME wrote under {@code dir} at its last boot —
     *  the dry-run's u16 id dictionary + mod set. Paths only; {@link #customVerdicts} checks
     *  {@code isRegularFile} and falls to "checked at launch" when either is absent.
     * */
    static void attachBootCaches(LocalCustoms report, Path dir) {
        report.idTablesFile = dir.resolve("id-tables.json.data");
        report.modSetFile = dir.resolve("mod-set.txt.data");
    }

    static LocalCustoms localCustomReport(Path installRoot) {
        Path dir = customsDir(installRoot);
        LocalCustoms report = new LocalCustoms();
        if (!Files.isDirectory(dir)) {
            return report;
        }
        attachBootCaches(report, dir);
        final String suffix = ".variant.data";
        final String bare = ".variant";
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            for (Path file : (Iterable<Path>) files.sorted()::iterator) {
                String name = file.getFileName().toString();
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                if (name.endsWith(bare)) {
                    String stem = shownName(name.substring(0, name.length() - bare.length()));
                    report.notes.add(stem + ".variant: wrong suffix - the game reads " + stem
                            + ".variant.data; rename the file");
                    continue;
                }
                if (!name.endsWith(suffix)) {
                    continue;
                }
                String id = name.substring(0, name.length() - suffix.length());
                if (!cc.starsector.client.net.LocalVariantIdLaw.isLawful(id)) {
                    report.notes.add(idLawNote(id));
                    continue;
                }
                String displayNameNote;
                try {
                    if (Files.size(file) > 256L * 1024L) {
                        report.notes.add(id + ".variant.data: over 256 KB - not offered");
                        continue;
                    }
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    if (!java.util.regex.Pattern.compile("\"variantId\"\\s*:\\s*\""
                            + java.util.regex.Pattern.quote(id) + "\"").matcher(text).find()) {
                        report.notes.add(id + ".variant.data: its \"variantId\" must be \"" + id
                                + "\" (the file name stem) - not offered");
                        continue;
                    }
                    displayNameNote = displayNameNote(id, text);
                } catch (IOException | RuntimeException unreadable) {
                    report.notes.add(id + ".variant.data: unreadable - not offered");
                    continue;
                }
                report.ids.add(id);
                report.files.put(id, file);
                if (displayNameNote != null) {
                    report.notes.add(displayNameNote);
                }
            }
        } catch (IOException unreadable) {
            return report;
        }
        return report;
    }

    /**
     * The variant check's CLIENT HALF: after a SERVED probe, the per-custom
     * SERVER verdict — each offerable local file parsed ({@code ClientVariantJson}, the game's own
     * parser) and dry-run against the door's policy over {@link DoorVariantCheck}. Every id gets an
     * entry, in {@code local.ids} order; UNKNOWN ("checked at launch") for a file the parser refuses,
     * for silence, and for the WHOLE list when this JVM has no {@code org.json} on its classpath
     * ({@code join-cc.bat} adds {@code starsector-core\json.jar}; a bare {@code -cp jar} launch or
     * the {@code --selftest} has none — the {@link LinkageError} is the fail-open door). Blocking
     * (the door's paced sends) — worker / request thread only, never the EDT.
     */
    static Map<String, DoorVariantCheck.Verdict> customVerdicts(LocalCustoms local, String host,
            int port, String password, String fingerprint) {
        Map<String, DoorVariantCheck.Verdict> out = new LinkedHashMap<>();
        for (String id : local.ids) {
            out.put(id, DoorVariantCheck.Verdict.UNKNOWN);
        }
        if (out.isEmpty()) {
            return out;
        }
        List<ClientVariantDefinition> parsed = new ArrayList<>();
        for (String id : local.ids) {
            Path file = local.files.get(id);
            if (file == null) {
                continue;
            }
            try {
                parsed.add(cc.starsector.client.net.ClientVariantJson.parse(
                        Files.readString(file, StandardCharsets.UTF_8)));
            } catch (IOException | IllegalArgumentException unparsable) {
                // stays UNKNOWN: the launch-time submit refuses it with the same law, loudly
            } catch (LinkageError noJsonOnClasspath) {
                return out; // no org.json here ⇒ no verdicts at all (fail-open)
            }
        }
        if (parsed.isEmpty()) {
            return out;
        }
        // The u16-index bodies need the id table the game CACHED for
        // this game-free JVM; the fingerprint is presented FOLDED over the cache's digest (the
        // in-game print's shape), so a stale cache is a silent door drop (UNKNOWN), never a
        // garbage verdict. No cache yet (first launch) = no dry-run, "checked at launch".
        cc.starsector.server.runtime.IdTables tables;
        String vchkPrint = fingerprint;
        try {
            if (local.idTablesFile == null || !Files.isRegularFile(local.idTablesFile)
                    || Files.size(local.idTablesFile) > 8L * 1024L * 1024L) {
                return out;
            }
            tables = cc.starsector.server.runtime.IdTablesJson.parse(
                    Files.readString(local.idTablesFile, StandardCharsets.UTF_8));
            // The in-game print folds the boot MOD SET too — the dry-run presents the same
            // three-way fold from the game's cached set (same posture as the id-table cache: absent or
            // unparsable = no dry-run, never a garbage verdict).
            if (local.modSetFile == null || !Files.isRegularFile(local.modSetFile)
                    || Files.size(local.modSetFile) > cc.starsector.server.runtime.ModSet.MAX_CACHE_BYTES) {
                return out;
            }
            cc.starsector.server.runtime.ModSet modSet = cc.starsector.server.runtime.ModSet.parse(
                    Files.readString(local.modSetFile, StandardCharsets.UTF_8));
            if (fingerprint != null && fingerprint.startsWith(
                    cc.starsector.server.match.ContentFingerprints.FORMAT_PREFIX + ";")) {
                vchkPrint = cc.starsector.server.match.ContentFingerprints.foldContent(
                        fingerprint, tables.vtab(), modSet.digest());
            }
        } catch (IOException | IllegalArgumentException | LinkageError unusable) {
            return out; // fail-open: the launch-time submit is the real gate
        }
        // The sealed check - the password never rides the wire (the version-12 fetch is deleted)
        out.putAll(DoorVariantCheck.fetch13(host, port, password, vchkPrint, parsed, tables));
        return out;
    }

    /** The ONE wording home for a verdict row (the SPA and the Swing twin both render this). */
    static String verdictLine(String id, DoorVariantCheck.Verdict verdict) {
        switch (verdict.status()) {
            case OK:
                return id + ": policy OK - final check at submit";
            case REJECT: {
                String hint = VariantRejectHints.hintOrNull(verdict.token());
                return id + ": " + (hint != null ? hint : verdict.token());
            }
            case UNAVAILABLE:
                return id + ": customs off on this server";
            default:
                return id + ": checked at launch";
        }
    }

    /** The verdict rows as the SPA's {@code customsVerdicts} object: {@code {id: {status, token, line}}}. */
    static String verdictsJson(Map<String, DoorVariantCheck.Verdict> verdicts) {
        JoinJson j = new JoinJson();
        for (Map.Entry<String, DoorVariantCheck.Verdict> e : verdicts.entrySet()) {
            j.putRaw(e.getKey(), new JoinJson().put("status", e.getValue().word())
                    .put("token", e.getValue().token())
                    .put("line", verdictLine(e.getKey(), e.getValue())).toString());
        }
        return j.toString();
    }

    /** The verdict rows as plain lines, {@code verdicts} order (the Swing label). */
    static List<String> verdictLines(Map<String, DoorVariantCheck.Verdict> verdicts) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, DoorVariantCheck.Verdict> e : verdicts.entrySet()) {
            lines.add(verdictLine(e.getKey(), e.getValue()));
        }
        return lines;
    }

    /** W15 iteration 6 row (e): the editor's Saved builds list is {@code index ∩ exists}
     *  ({@code EditorOracle.savedList}) and the game has no common-dir listing API, so the
     *  join window — the one place that already scans the folder — publishes
     *  {@code saves/common/cc_variants/index.data} = {@code {"stems":[…]}} (the oracle's own shape) on open,
     *  Refresh and Launch. The file's order (the editor's most-recent-first) is kept for stems whose file still
     *  exists; the files the editor never saved are appended in name order; cap 200 (the oracle's INDEX_CAP).
     *  Written only when the list changed; any failure is silent — a nicety, never a launch blocker.
     *  @return true when the index file was (re)written */
    static boolean publishVariantIndex(Path installRoot, LocalCustoms report) {
        Path dir = customsDir(installRoot);
        if (!Files.isDirectory(dir)) {
            return false;
        }
        Path index = dir.resolve("index.data");
        List<String> had = new java.util.ArrayList<>();
        List<String> merged = new java.util.ArrayList<>();
        try {
            if (Files.isRegularFile(index) && Files.size(index) <= 64L * 1024L) {
                java.util.regex.Matcher arr = java.util.regex.Pattern
                        .compile("\"stems\"\\s*:\\s*\\[([^\\]]*)\\]")
                        .matcher(Files.readString(index, StandardCharsets.UTF_8));
                if (arr.find()) {
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("\"([A-Za-z0-9._-]{1,64})\"").matcher(arr.group(1));
                    while (m.find()) {
                        String s = m.group(1);
                        had.add(s);
                        if (cc.starsector.client.net.LocalVariantIdLaw.isLawful(s) && !merged.contains(s)
                                && Files.isRegularFile(dir.resolve(s + ".variant.data"))) {
                            merged.add(s);
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            had.clear();
            merged.clear(); // an unreadable index is rebuilt from the scan alone
        }
        for (String id : report.ids) {
            if (!merged.contains(id)) {
                merged.add(id);
            }
        }
        while (merged.size() > 200) {
            merged.remove(merged.size() - 1);
        }
        if (merged.equals(had)) {
            return false;
        }
        StringBuilder sb = new StringBuilder("{\"stems\":[");
        for (int i = 0; i < merged.size(); i++) {
            sb.append(i > 0 ? ",\"" : "\"").append(merged.get(i)).append('"');
        }
        sb.append("]}");
        try {
            // The editor process reads + rewrites this file — it lands whole (temp + ATOMIC_MOVE,
            // the plan-file law); the stems are charset-gated ASCII so the plan writer's US_ASCII encoding is exact
            writePlanAtomically(index, sb.toString());
            return true;
        } catch (IOException | RuntimeException unwritable) {
            return false;
        }
    }

    /** The in-game {@code displayName} law (ClientVariantDefinition: 1..48 printable ASCII, one
     *  line) graded on the raw JSON text: {@code null} = lawful. JSON escapes are DECODED first
     *  ({@code é} / {@code \n} are refused in-game exactly like their raw
     *  twins) — an escape the decoder does not know grades as unlawful (the parse would throw). */
    static String displayNameNote(String id, String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"displayName\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(text);
        if (!m.find()) {
            return id + ": add \"displayName\": \"<a name, 1-48 plain ASCII chars>\" or the game"
                    + " will not submit it";
        }
        String decoded = decodeJsonString(m.group(1));
        boolean lawful = decoded != null && !decoded.isBlank() && decoded.length() <= 48;
        for (int i = 0; lawful && i < decoded.length(); i++) {
            char c = decoded.charAt(i);
            lawful = c >= 0x20 && c <= 0x7e;
        }
        return lawful ? null : id + ": \"displayName\" must be 1-48 plain ASCII chars on one"
                + " line or the game will not submit it";
    }

    /** Decode the JSON string escapes ({@code \" \\ \/ \b \f \n \r \t \\uXXXX}); {@code null} on a
     *  malformed escape (no json.jar on the joiner's classpath — this is the RFC 8259 set). */
    static String decodeJsonString(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (++i >= raw.length()) {
                return null;
            }
            char e = raw.charAt(i);
            switch (e) {
                case '"': case '\\': case '/': out.append(e); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u':
                    if (i + 4 >= raw.length()) {
                        return null;
                    }
                    try {
                        out.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
                    } catch (NumberFormatException bad) {
                        return null;
                    }
                    i += 4;
                    break;
                default: return null;
            }
        }
        return out.toString();
    }

    /**
     * Fold: the rename note for an id the id law refused NAMES the
     * rule that refused it — the reserved-device-stem rule when that is the one (the name IS
     * letters, so the character wording would mislead), else the character law.
     */
    static String idLawNote(String id) {
        if (cc.starsector.client.net.LocalVariantIdLaw.isReservedStem(id)) {
            return shownName(id) + ".variant.data: its stem is a Windows reserved device name"
                    + " (CON/PRN/AUX/NUL/COM0-9/LPT0-9) - rename it";
        }
        return shownName(id) + ".variant.data: the file name must be"
                + " letters/digits/._- (no leading -/., no '..', max 64) - rename it";
    }

    /** A file stem for a note: printable ASCII only, capped — the stem is a player-chosen name. */
    static String shownName(String stem) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < stem.length() && out.length() < 48; i++) {
            char c = stem.charAt(i);
            out.append(c >= 0x20 && c <= 0x7e ? c : '?');
        }
        return out.length() < stem.length() ? out.append("...").toString() : out.toString();
    }

    /** The RUNNING game's editor endpoint file (port|token) under the install's saves/common. */
    static Path editorEndpointFile(Path modDir) {
        return JoinVoiceSettings.installRoot(modDir).resolve("saves").resolve("common")
                .resolve("cc_editor_endpoint.data");
    }
    /**
     * The launch face's text (row (f) law 4; the
     * companion wording carried, the separate "helper" window and its justification gone — the
     * owner, flight {@code 134310}: "its all unnecessarily confusing"). The FIRST line says what
     * happens next in one breath; {@code asked} = the player pressed Open editor → Launch now, so
     * the face opens the browser itself. The hint carries the pick/re-submit law a player would otherwise
     * have to discover by hand plus the in-match Submit path (an accepted
     * Submit IS the seat's next respawn). Both render in wrapping text areas.
     */
    /** Iteration 7 row (c) D1: the face also tells the player HOW TO ENTER the match — a player who only
     *  wants to fly the default ship reaches the title otherwise stranded. */
    static final String LAUNCH_FACE_JOIN = " To join the match, select Multiplayer Test on the title screen.";
    static String launchFacePurpose(boolean asked) {
        return "Starsector is starting. " + (asked ? "This window opens" : "Open editor below opens")
                + " your variant editor in your browser once the title screen is up." + LAUNCH_FACE_JOIN;
    }
    /** The purpose line once the game answers (row (g)): "starting" was stale. */
    static final String LAUNCH_FACE_LIVE =
            "Starsector is running. Open editor below opens your variant editor in your browser." + LAUNCH_FACE_JOIN;
    /** A build you save is the ship you
     *  spawn in — the game carries the newest save as its pick on the next Multiplayer Test select
     *  (the ship picked in this window flies until a newer save); Submit stays the in-match path. */
    static final String LAUNCH_FACE_HINT =
            "Save a build in the editor, then select Multiplayer Test on the title screen - your"
            + " newest save flies (the ship you picked in this window flies until you save a newer"
            + " build). In a match, the editor's Submit button sends the build to the server and"
            + " makes it your next respawn.\n\n"
            + "Close this window whenever you like - the game is not affected.";
    /**
     * The live status line, pure (selftested): {@code state} is the game's {@code GameState}
     * name from {@code api/ping} (TITLE / CAMPAIGN / COMBAT, anything else = running), {@code
     * session} is {@code "live"} while a multiplayer session can take a Submit. Both are DATA
     * from the wire — matched against fixed words, never echoed.
     */
    static String companionStatusLine(String state, String session) {
        boolean inMatch = "live".equals(session);
        if ("TITLE".equals(state)) {
            // The title is where a save becomes the next spawn — say so on the status line
            return "Starsector: at the title screen - the editor is ready"
                    + " (Save a build, then select Multiplayer Test - your newest save flies).";
        }
        if ("COMBAT".equals(state)) {
            return inMatch
                    ? "Starsector: in a match - the editor is ready (Submit = your next respawn)."
                    : "Starsector: in combat (no multiplayer session) - the editor is ready.";
        }
        if ("CAMPAIGN".equals(state)) {
            return "Starsector: in the campaign - the editor is ready.";
        }
        return inMatch
                ? "Starsector: in a match - the editor is ready (Submit = your next respawn)."
                : "Starsector: running - the editor is ready.";
    }

    /** The waiting line with a live elapsed clock, so a slow start reads as progress, not a hang. */
    static String companionWaitingLine(long elapsedMs) {
        long s = Math.max(0L, elapsedMs / 1000L);
        return "Starsector: starting - waiting for the title screen (" + (s / 60) + ":"
                + (s % 60 < 10 ? "0" : "") + (s % 60) + ")...";
    }

    /**
     * One string field out of the ping's JSON body, or null. A deliberately narrow reader (no
     * JSON parser in the join package): the value must be a bare word {@code [A-Za-z_]{1,32}};
     * anything else — escapes, quotes, a missing key, an oversized body — yields null and the
     * caller falls back to the neutral wording. Never used to build a path, a URL or a log line.
     */
    static String pingField(String body, String key) {
        if (body == null || key == null || body.length() > 4096) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\"([A-Za-z_]{1,32})\"")
                .matcher(body);
        return m.find() ? m.group(1) : null;
    }

    /** Companion poll cadence: the game is slow to the title, the editor answers fast after. */
    static final long COMPANION_POLL_WAITING_MS = 2000L;
    static final long COMPANION_POLL_LIVE_MS = 3000L;
    /** Consecutive failed pings after a live one = the game closed → the companion leaves. */
    static final int COMPANION_MISSES_TO_EXIT = 3;
    /** No game ever answered for this long → leave (a stale window helps no one). */
    static final long COMPANION_NEVER_LIVE_EXIT_MS = 30L * 60L * 1000L;

    /**
     * The companion's state law, pure: {@code "live"} (editor answering), {@code "waiting"} (not
     * yet / a blip), {@code "exit"} (the game closed after having answered, or never answered
     * within {@link #COMPANION_NEVER_LIVE_EXIT_MS}). Selftested — the Swing shell only renders it.
     */
    static String companionVerdict(boolean live, int seenLive, int misses, long elapsedMs) {
        if (live) {
            return "live";
        }
        if (seenLive > 0) {
            return misses >= COMPANION_MISSES_TO_EXIT ? "exit" : "waiting";
        }
        return elapsedMs >= COMPANION_NEVER_LIVE_EXIT_MS ? "exit" : "waiting";
    }
    /** Row (f) law 3: the plan file lands whole — written beside itself, then moved into place (also the
     *  {@code cc_variants/index.data} writer — any cross-process file the editor reads).
     *  A failed write or move deletes the {@code .tmp}
     *  (best effort) and rethrows — a plan's {@code .tmp} holds the password and the cid. */
    static void writePlanAtomically(Path planFile, String content) throws IOException {
        Path tmp = planTmp(planFile);
        try {
            Files.write(tmp, content.getBytes(StandardCharsets.US_ASCII));
            try {
                Files.move(tmp, planFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException plain) {
                Files.move(tmp, planFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException failed) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException | RuntimeException stuck) {
                failed.addSuppressed(stuck);   // rides the rethrown failure to the caller's refusal
            }
            throw failed;
        }
    }

    static Path planTmp(Path planFile) {
        return planFile.resolveSibling(planFile.getFileName() + ".tmp");
    }

    /** The marker the polling bat reads as "cancelled" (Cancel, or the window closed before Launch). */
    static Path cancelMarker(Path planFile) {
        return planFile.resolveSibling(planFile.getFileName() + ".cancel");
    }

    /** The launcher's tick file ({@code <plan>.alive}): {@code join-cc.bat} rewrites it every second while it polls
     *  for the plan; the browser window lives exactly as long as it keeps changing ({@code JoinBrowserUi.watch}). */
    static Path launcherAliveMarker(Path planFile) {
        return planFile.resolveSibling(planFile.getFileName() + ".alive");
    }

    static void writeCancelMarker(Path planFile) {
        try {
            Files.write(cancelMarker(planFile), new byte[0]);
        } catch (IOException | RuntimeException ignored) {
            // a marker that could not be written: the bat keeps waiting on the player's console (no clock)
        }
    }
    /** One answered {@code api/ping}: the editor's base URL + the (bounded) JSON body it returned. */
    static final class EditorPing {
        final String base;
        final String body;

        EditorPing(String base, String body) {
            this.base = base;
            this.body = body;
        }
    }

    /**
     * Row (f) law 2, pure (selftested): the editor's base URL out of the endpoint file's text
     * ({@code port|token} or {@code port|token|nonce}), or null. {@code nonce} = this window's
     * launch nonce: set ⇒ only a file carrying the SAME nonce is this window's game (a foreign
     * nonce, a two-field file, a stale file from an earlier launch ⇒ keep waiting); null ⇒ the
     * old shape, both file forms accepted (a window started with no nonce: dev tooling).
     */
    static String endpointBase(String text, String nonce) {
        if (text == null) {
            return null;
        }
        String[] fields = text.trim().split("\\|", -1);
        if (fields.length < 2 || fields.length > 3) {
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(fields[0].trim());
        } catch (NumberFormatException bad) {
            return null;
        }
        String token = fields[1].trim();
        if (port < 1 || port > 65535 || !token.matches("[0-9a-fA-F]{32}")) {
            return null;
        }
        String fileNonce = fields.length == 3 ? fields[2].trim() : null;
        if (fileNonce != null && !fileNonce.matches("[0-9a-f]{8}")) {
            return null;
        }
        if (nonce != null && !nonce.equals(fileNonce)) {
            return null;
        }
        return "http://127.0.0.1:" + port + "/" + token + "/";
    }

    /** Cap on the ping body the companion reads — the real body is ~300 B; a fatter one is cut. */
    static final int PING_BODY_MAX = 4096;

    /**
     * The editor's base URL ({@link #endpointBase}, bound by {@code nonce}) plus the {@code api/ping}
     * answer's body (first {@link #PING_BODY_MAX} bytes, UTF-8) when that game answers within 2 s —
     * the companion's live status line and every Open editor read it; null when it is not answering.
     */
    static EditorPing liveEditorPing(Path endpointFile, String nonce) {
        String text;
        try {
            if (!Files.isRegularFile(endpointFile) || Files.size(endpointFile) > 256) {
                return null;
            }
            text = new String(Files.readAllBytes(endpointFile), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return null;
        }
        String base = endpointBase(text, nonce);
        if (base == null) {
            return null;
        }
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(base + "api/ping").openConnection();
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(false); // A planted endpoint must not bounce the ping off loopback
            int code = c.getResponseCode();
            if (code != 200) {
                c.disconnect();
                return null;
            }
            byte[] buf = new byte[PING_BODY_MAX];
            int n = 0;
            try (InputStream in = c.getInputStream()) {
                int r;
                while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) {
                    n += r;
                }
            }
            c.disconnect();
            return new EditorPing(base, new String(buf, 0, n, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException down) {
            return null;
        }
    }
    /**
     * The kit credential beside the bat: {@code match.txt} (or the Notepad save-as trap
     * {@code match.txt.txt}, accepted like the bat does). Absent = this folder is NOT a kit
     * extract and cannot join. Presence-only; content is validated at launch.
     */
    /**
     * Iteration 9 (d) (owner #4): the SERVER BUILDS carrier — after a SERVED probe the join window
     * publishes the server's offerable ids for the editor JVM (which holds no credentials and never dials):
     * {@code saves/common/cc_variants/server-builds.data} = {@code {"ids":[…],"defaultId":"…","stamp":ms}}.
     * Every id is re-gated by the id law here (the door already charset-gates them) and again by the editor
     * on read; NO host / port / password ever lands in it; cap 200; temp + ATOMIC_MOVE (the plan-file law);
     * any failure is silent — a nicety, never a launch blocker. The editor's {@code GET server} reads it.
     * @return true when the file was written
     */
    static boolean publishServerBuilds(Path installRoot, List<String> ids, String defaultId, long stampMs) {
        Path dir = customsDir(installRoot);
        try {
            if (!Files.isDirectory(dir)) {
                Files.createDirectories(dir);
            }
            StringBuilder sb = new StringBuilder("{\"ids\":[");
            int n = 0;
            for (String id : ids == null ? java.util.Collections.<String>emptyList() : ids) {
                if (!cc.starsector.client.net.LocalVariantIdLaw.isLawful(id) || n >= 200) {
                    continue;
                }
                sb.append(n > 0 ? ",\"" : "\"").append(id).append('"');
                n++;
            }
            sb.append("],\"defaultId\":\"")
                    .append(cc.starsector.client.net.LocalVariantIdLaw.isLawful(defaultId) ? defaultId : "")
                    .append("\",\"stamp\":").append(Math.max(0L, stampMs)).append('}');
            writePlanAtomically(dir.resolve("server-builds.data"), sb.toString());
            return true;
        } catch (IOException | RuntimeException unwritable) {
            return false;
        }
    }

    /**
     * Iteration 9 (c) (owner #3): the editor opens BY ITSELF once the launched game answers its first
     * live ping — no button press. Opt-out (default on): {@code -Dcc.join.autoeditor=off}, else the env
     * {@code CC_JOIN_AUTOEDITOR=off}; the property wins when set. Both faces honour it (the browser page's
     * companion loop and the Swing launch face's once-only browse).
     */
    static boolean autoEditorEnabled() {
        return autoEditorEnabled(System.getProperty("cc.join.autoeditor"), System.getenv("CC_JOIN_AUTOEDITOR"));
    }

    static boolean autoEditorEnabled(String prop, String env) {
        String word = prop != null && !prop.trim().isEmpty() ? prop : env;
        return word == null || !"off".equalsIgnoreCase(word.trim());
    }

    static boolean hasKitCredential(Path modDir) {
        return Files.isRegularFile(modDir.resolve("match.txt"))
                || Files.isRegularFile(modDir.resolve("match.txt.txt"));
    }
}
