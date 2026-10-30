package cc.starsector.server.ops;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure text checks over the console page's three blocks
 * ({@link OpsConsolePage#html()}, {@link OpsConsolePage#css()}, {@link OpsConsolePage#js()}).
 *
 * <p>WHY: the page is served to strangers under {@code Content-Security-Policy: default-src 'self'} and
 * must twin every Swing package control.
 * Nothing renders here (no browser in the suite), so each law the page owes is a text row: unique
 * {@code data-cc} ids (every Swing id present = {@code parity-web-twin} in {@link OpsConsoleSelftest}; its page
 * duplicate {@code page-swing-ids-present} was deleted), nothing CSP would block or that
 * runs page text as code,
 * the DOM ids the script parts bind to, and no internal word on a stranger's page. Each failing
 * row prints {@code FAIL <row>: <first offending token / id> (flips if ...)}; the run exits 1 on any
 * FAIL, else prints {@code ops-console-page selftest PASS}. {@link #run} is the seam the console's own
 * {@code --selftest} calls; {@code --print html|css|js} writes one block's SERVED
 * UTF-8 bytes to stdout - for js, the comment-stripped script {@code /ops.js} sends, not the raw parts
 * (a redirect gives the browser-look files; this class touches no file itself).
 *
 * <p>Every composer / Swing-constant
 * sentence the page carries statically appears EXACTLY once (a second copy is the one a later one-shell
 * edit leaves stale), the help's {@code <dd data-hint>} rows name a real hint and carry no text of
 * their own (no help row repeats a hint's text), the bar's read-outs and captions stay inside the bar
 * without joining the verb order, and the text law reads the JS too.
 */
public final class OpsConsolePageSelftest {

    private OpsConsolePageSelftest() {
    }

    /** The DOM ids the script parts bind to; each must exist once. */
    static final List<String> CONTRACT_IDS = List.of(
            "no-key", "strip", "headline", "detail", "copy-detail", "asof", "note", "note-history",
            "note-list", "brief", "brief-title", "brief-body", "nav", "help-open", "theme-toggle", "btn-quit", "sec-players",
            "sec-setup", "sec-mods", "dock", "load", "util", "workmax", "egress", "bar", "simspeed-last",
            "pick-chip", "customs-summary", "footer-note", "dlg-confirm", "confirm-text", "confirm-ok",
            "dlg-error", "error-title", "error-text", "dlg-help", "dlg-palette", "palette-input",
            "palette-list",
            "seats", "seats-body", "tpl-seat-row", "seats-empty", "seats-remembered", "bans",
            "bans-count", "bans-list", "bans-more", "tpl-ban-row",
            "setup-form", "bind", "copy-advertise", "pre-start", "hint-shape", "admin-remove",
            "hint-admission", "hint-admission-inline", "sidecar-name", "live-dials", "voice",
            "voice-anchor", "voice-bandwidth",
            // the setup part reaches these through VOICE_FIELDS / VOICE_NOTES variables, so the
            // page-js-ids-bound scan never sees them; a renamed one throws at load and kills
            // every part's registration
            "v-enabled", "v-bitrate", "v-frame", "v-complexity", "v-fec", "v-max-talkers", "v-prox-full",
            "v-prox-cull", "v-relay-tick", "v-jitter-min", "v-jitter-max", "v-jitter-shrink",
            "voice-note-refused", "voice-note-fail", "voice-note-sent",
            "mods-explainer", "mods-roots", "tpl-root-row", "mods-add-path", "mods-table", "mods-body",
            "tpl-mod-row", "mods-status", "mods-write-fail",
            "load-rates", "load-rates-text", "load-phases", "load-phases-text", "load-totals",
            "load-totals-text", "bans-empty", "customs-detail");

    /** The verb bar's controls in V3's exact cluster membership and order: SERVER, MATCH,
     * SIM-SPEED, SEAT, CUSTOMS; plus the page-only Reject seat beside Allow seat and the Custom id box
     * before Ban custom (page-only, no Swing twin).
     * Join from this PC follows Start in SERVER (the Swing cluster's order).
     * Keep for everyone / Forget kept close the PAGE's CUSTOMS menu
     * after the accept box - this list is the page's own order; Swing's v3 bar carries the two in a sixth KEPT
     * cluster beside SIM-SPEED (a width fit), and the parity rows compare the id SETS, never this order.
     * Launch debug WATCH sits between Start and Join from this PC (the Swing
     * SERVER cluster's own order). */
    static final List<String> BAR_ORDER = List.of(
            "verb.startServer", "verb.watch", "verb.joinerGui", "verb.devEditor",
            "verb.endRound", "verb.endMatch", "verb.kill",
            "verb.simSpeed.field", "verb.simSpeedSet", "verb.simSpeedAuto", "verb.simSpeedAuto.off",
            "verb.kickSelected", "verb.banPlayer", "verb.banSource", "verb.unbanPlayer", "verb.customReject",
            "verb.customAllow",
            "verb.customId", "verb.customBan", "verb.customExport", "verb.customUnban",
            "verb.customSubmissions", "verb.customKeep", "verb.customForget");

    /** The bar's read-outs and captions (no verb among them): each must sit inside the bar, and the order
     * check skips them, so {@link #BAR_ORDER} stays the controls alone. */
    static final List<String> BAR_READOUTS = List.of(
            "shell.enable.bar", "bar.cluster.server", "bar.cluster.match", "bar.cluster.simSpeed",
            "bar.simSpeed.prefix", "bar.cluster.seat", "live.seat.pick.none", "bar.cluster.customs",
            "verb.customs.summary", "verb.customs.detail", "shell.close");

    /** Words a stranger's page never carries, the TEXT rule, matched case-insensitively. */
    static final List<String> TEXT_LAW_TOKENS = List.of(
            "local\\", ".ps1", "owner", "mint flow", "ops-cmd", "token mode", "generated/");

    private static final Pattern DATA_CC = Pattern.compile("data-cc=\"([^\"]*)\"");
    private static final Pattern ID_ATTR = Pattern.compile("\\sid=\"([^\"]*)\"");
    private static final Pattern ID_LAW = Pattern.compile("[a-z][A-Za-z0-9.]*");
    private static final Pattern TEMPLATE = Pattern.compile("(?s)<template\\b.*?</template>");
    private static final Pattern TAG = Pattern.compile("<[a-zA-Z][^>]*>");
    private static final Pattern SCRIPT_TAG = Pattern.compile("(?i)<script\\b[^>]*>");
    private static final Pattern HANDLER_ATTR = Pattern.compile("(?i)\\son[a-z]+\\s*=");
    private static final Pattern STYLE_ATTR = Pattern.compile("(?i)\\sstyle\\s*=");
    private static final Pattern ABS_URL = Pattern.compile("(?i)https?://");
    private static final Pattern HELP_SSH = Pattern.compile("(?s)<p id=\"help-ssh\">.*?</p>");
    private static final Pattern DIALOG = Pattern.compile("(?s)<dialog\\b([^>]*)>(.*?)</dialog>");
    private static final Pattern NAV = Pattern.compile("(?s)<nav id=\"nav\"[^>]*>(.*?)</nav>");
    private static final Pattern DATA_SECTION = Pattern.compile("data-section=\"([^\"]*)\"");
    private static final Pattern HELP_DIALOG = Pattern.compile("(?s)<dialog id=\"dlg-help\"[^>]*>(.*?)</dialog>");
    private static final Pattern DD = Pattern.compile("(?s)<dd\\b([^>]*)>(.*?)</dd>");
    private static final Pattern DATA_HINT = Pattern.compile("data-hint=\"([^\"]*)\"");
    private static final Pattern HINT_P = Pattern.compile("(?s)<p class=\"hint\" id=\"([^\"]*)\"[^>]*>(.*?)</p>");
    private static final Pattern ANY_TAG = Pattern.compile("<[^>]*>");

    /**
     * The served-script laws' patterns. {@link #JS_COMMENT_LINE}
     * = one whole {@code //} line with its newline, written apart from
     * {@link OpsConsolePage#stripCommentLines} so {@code page-js-strip-exact} rebuilds the expectation
     * by a second route. {@link #JS_ID_BINDS} = every literal-id bind idiom the parts use (group 1 = the
     * id): {@code CC.el}, {@code CC.q} / {@code CC.qa} with a leading {@code #}, {@code CC.tpl}, and the
     * DOM's own {@code getElementById} / {@code querySelector}; a bind through a variable ({@code CC.el(id)})
     * is not literal and not read. {@link #JS_MADE_IDS} = ids the script creates itself and may bind:
     * empty, because its one made family ({@code palette-opt-<n>}, the palette list's options, set by
     * {@code li.id =}) is never bound by a literal id.
     */
    private static final Pattern JS_COMMENT_LINE = Pattern.compile("(?m)^[^\\S\\n]*//[^\\n]*(?:\\n|\\z)");
    private static final List<Pattern> JS_ID_BINDS = List.of(
            Pattern.compile("CC\\.el\\('([A-Za-z0-9_-]+)'\\)"),
            Pattern.compile("CC\\.qa?\\('#([A-Za-z0-9_-]+)[^']*'\\s*[),]"),
            Pattern.compile("CC\\.tpl\\('([A-Za-z0-9_-]+)'\\)"),
            Pattern.compile("getElementById\\('([A-Za-z0-9_-]+)'\\)"),
            Pattern.compile("querySelector(?:All)?\\('#([A-Za-z0-9_-]+)[^']*'\\s*[),]"));
    static final Set<String> JS_MADE_IDS = Set.of();
    /** Internal tokens the served script never carries (label, case-sensitive regex). */
    private static final List<String[]> JS_TEXT_LAW = List.of(
            new String[] {"TASK-<n>", "TASK-\\d"}, new String[] {"REVIEW-<n>", "REVIEW-\\d"},
            new String[] {"ADR-<n>", "ADR-\\d"}, new String[] {"Opus-authored", "Opus-authored"},
            new String[] {"s<4 digits>", "\\bs\\d{4}\\b"},
            new String[] {"section sign", Pattern.quote(String.valueOf((char) 0xA7))},
            new String[] {"docs/", "docs/"}, new String[] {"generated/", "generated/"},
            new String[] {"wf_", "wf_"});

    /** Runs every row (one PASS / FAIL line each) and returns the FAIL lines (empty = all green). */
    static List<String> run(String html, String css, String js) {
        List<String> fails = new ArrayList<>();
        row(fails, "page-ids-unique", () -> idsUnique(html));
        row(fails, "page-texts-from-composers", () -> textsFromComposers(html));
        row(fails, "page-join", () -> pageJoin(html, js));
        row(fails, "page-watch", () -> pageWatch(html, js));
        row(fails, "page-csp-clean", () -> cspClean(html, css, js));
        row(fails, "page-dom-contract", () -> domContract(html));
        row(fails, "page-text-law", () -> textLaw(html, css, js));
        row(fails, "page-js-no-comment-lines", () -> jsNoCommentLines(js));
        row(fails, "page-js-strip-exact", () -> jsStripExact(js));
        row(fails, "page-js-text-law", () -> jsTextLaw(js));
        row(fails, "page-js-ids-bound", () -> jsIdsBound(html, js));
        row(fails, "page-kept", () -> pageKept(html, js));
        row(fails, "page-ships", () -> pageShips(html, css, js));
        row(fails, "page-map-row-ids", () -> pageMapRow(html, js));
        row(fails, "page-n-ids", () -> pageNIds(html, js));
        row(fails, "web-owner-switch-clears-passwords", () -> ownerSwitchClearsPasswords(js));
        row(fails, "web-start-ok-clears-passwords", () -> startOkClearsPasswords(js));
        row(fails, "page-token-howto", () -> tokenHowto(html, js));
        row(fails, "page-theme-dark-default", () -> themeDarkDefault(html, css));
        row(fails, "page-theme-toggle", () -> themeToggle(html, js));
        row(fails, "page-quit", () -> quitPage(html, js));
        row(fails, "page-draft-overlay", () -> draftOverlay(js));
        row(fails, "page-draft-post", () -> draftPost(js));
        row(fails, "page-players-field", () -> playersField(html, js));
        row(fails, "page-second-folder", () -> secondFolder(html, js));
        row(fails, "page-setup-folds", () -> setupFolds(html));
        row(fails, "page-setup-fold-opens-on-refusal", () -> setupFoldOpensOnRefusal(js));
        return fails;
    }

    /**
     * {@code page-n-ids} - the Recording, reach and mods-Remove twins on the page.
     * The Recording select {@code setup.record} once, in {@code #bind} after the Game folder, holding EXACTLY the
     * three options {@code off|diag|full} with {@link ServerOpsCore#RECORD_ITEMS} verbatim (each value a word
     * {@link ServeRecord#parse} accepts), described by {@code #hint-record} = {@link ServerOpsCore#RECORD_TIP} once
     * and a help row naming that hint; the {@code setup.reach} button once, in the Advertise row, labelled
     * {@link ServerOpsCore#REACH_LABEL} and described by {@code tip-reach} = {@link ServerOpsCore#REACH_TIP}; the
     * {@code #reach-line} slot ({@code status.reach}) inside the status strip, hidden at load; the Remove help
     * row = {@link ServerOpsCore#MODS_REMOVE_TIP}. The script prefills and sends {@code record}, posts
     * {@code /api/reach} with the three fields and the button as {@code busy}, writes the strip line with the
     * {@link ServerOpsCore#REACHABILITY_PREFIX} literal, and shows a mods answer's note.
     */
    static String pageNIds(String html, String js) {
        List<String> bad = new ArrayList<>();
        String esc = "&amp;";
        StringBuilder opts = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            String value = List.of("off", "diag", "full").get(i);
            try {
                ServeRecord.parse(value);
            } catch (IllegalArgumentException refused) {
                bad.add("the launcher refuses record=" + value + " (flips if the row grammar and the page drift)");
            }
            opts.append("<option value=\"").append(value).append("\">")
                    .append(ServerOpsCore.RECORD_ITEMS.get(i).replace("&", esc).replace("<", "&lt;")
                            .replace(">", "&gt;").replace("\"", "&quot;")).append("</option>");
        }
        String select = "<select id=\"f-record\" data-cc=\"setup.record\" aria-describedby=\"hint-record\">" + opts
                + "</select>";
        int bind = html.indexOf("<fieldset id=\"bind\">");
        int bindEnd = bind < 0 ? -1 : html.indexOf("</fieldset>", bind);
        int gameRoot = html.indexOf("data-cc=\"setup.gameRoot\"");
        int sel = html.indexOf(select);
        if (ServerOpsCore.RECORD_ITEMS.size() != 3 || sel < 0 || sel != html.lastIndexOf(select)
                || html.indexOf("data-cc=\"setup.record\"") != html.lastIndexOf("data-cc=\"setup.record\"")
                || sel < gameRoot || sel > bindEnd) {
            bad.add("no single Recording select with the three RECORD_ITEMS verbatim in #bind after the Game folder"
                    + " (flips if setup.record is dropped / doubled / moved, an item text drifts from RECORD_ITEMS,"
                    + " or an option value leaves off|diag|full)");
        }
        for (String want : List.of("<p class=\"hint\" id=\"hint-record\">" + ServerOpsCore.RECORD_TIP + "</p>",
                "<dt>Recording</dt><dd data-hint=\"hint-record\"></dd>",
                "<dt>" + ServerOpsCore.REACH_LABEL + "</dt><dd id=\"tip-reach\">" + ServerOpsCore.REACH_TIP + "</dd>",
                "<dd id=\"tip-mods-remove\">" + ServerOpsCore.MODS_REMOVE_TIP + "</dd>")) {
            if (html.indexOf(want) < 0 || html.indexOf(want) != html.lastIndexOf(want)) {
                bad.add("page lacks (or doubles) " + clip(want) + " (flips if a tip / hint drifts from its ONE home"
                        + " in ServerOpsCore)");
            }
        }
        String button = "<button type=\"button\" id=\"btn-reach\" data-cc=\"setup.reach\" aria-describedby=\"tip-reach\">"
                + ServerOpsCore.REACH_LABEL + "</button>";
        int btn = html.indexOf(button);
        int adv = html.indexOf("id=\"f-advertise\"");
        int advHint = html.indexOf("id=\"hint-advertise\"");
        if (btn < 0 || btn != html.lastIndexOf(button) || btn < adv || btn > advHint) {
            bad.add("no single setup.reach button labelled '" + ServerOpsCore.REACH_LABEL + "' in the Advertise row"
                    + " (flips if the press loses its twin id, its label or its row)");
        }
        int slot = html.indexOf("<p id=\"reach-line\" data-cc=\"status.reach\" hidden></p>");
        int strip = html.indexOf("<header id=\"strip\"");
        int stripEnd = html.indexOf("</header>");
        if (slot < 0 || slot < strip || slot > stripEnd) {
            bad.add("no hidden #reach-line status.reach slot inside the status strip (flips if the Reachability"
                    + " line leaves the status block or shows before a check)");
        }
        String flatJs = js.replaceAll("\\s+", " ");
        for (String literal : List.of("['f-record', 'record']", "record: CC.el('f-record').value,",
                "CC.send('/api/reach', { bindHost: CC.el('f-bind-host').value, bindPort: CC.el('f-bind-port').value,"
                        + " advertise: CC.el('f-advertise').value}, {busy: CC.el('btn-reach'),",
                "'" + ServerOpsCore.REACHABILITY_PREFIX + "' + reach.note", "CC.show(reachLine, reach !== null);",
                "if (a && typeof a.note === 'string') { CC.note(a.note); }")) {
            if (!flatJs.contains(literal)) {
                bad.add("script lacks '" + clip(literal) + "' (flips if the record prefill / send, the reach post,"
                        + " its busy hold, the strip line or the mods note is unwired)");
            }
        }
        bad.addAll(pageL2Ids(html, flatJs));
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code page-n-ids}' package half.
     * {@code setup.shapeNew} once, in {@code #pre-start}
     * AFTER {@code setup.shape}, inside {@code #shape-new} (hidden at load); {@code #hint-one-password} once, hidden at load. The script
     * posts the New route with its fields and shows the package parts from {@code /api/config} {@code packaged}.
     * The ABSENT leg - neither the page nor the script carries the match
     * credential copy or the per-shape kit control (their ids, help row, text box, label or routes): a join
     * window fetches the match from the server at join, so nothing is handed to players by hand.
     */
    static List<String> pageL2Ids(String html, String flatJs) {
        List<String> bad = new ArrayList<>();
        for (String gone : List.of("btn-match-text", "f-match-text", "match-text-row", "tip-match-text",
                "setup.matchText", "btn-make-kit", "verb.makeKit", "/api/match-text", "'/api/kit'",
                KitPackRules.LABEL_MAKE_KIT, ">Copy match credential<")) {
            if (html.contains(gone) || flatJs.contains(gone)) {
                bad.add("page or script still carries '" + clip(gone) + "' (flips if the match credential copy or"
                        + " the per-shape kit control comes back)");
            }
        }
        int pre = html.indexOf("<fieldset id=\"pre-start\"");
        int preEnd = pre < 0 ? -1 : html.indexOf("</fieldset>", pre);
        int shape = html.indexOf("data-cc=\"setup.shape\"");
        int block = html.indexOf("<details id=\"shape-new\" hidden>");
        int create = html.indexOf("data-cc=\"setup.shapeNew\"");
        if (create < 0 || create != html.lastIndexOf("data-cc=\"setup.shapeNew\"") || create < shape || block < shape
                || create < block || create > preEnd || html.indexOf("</details>", block) < create) {
            bad.add("no single setup.shapeNew Create inside a hidden #shape-new block in #pre-start after the match"
                    + " shape (flips if the New form leaves the pre-start lock, opens shown, or its id is dropped)");
        }
        for (String want : List.of("<p class=\"hint\" id=\"hint-one-password\" hidden>" + OpsConsolePageHtml.ONE_PASSWORD + "</p>",
                "<summary>New match shape...</summary>",
                // main's fold on the D1-b lane: the New dialog's Players input (the Swing twin's field)
                "<label for=\"f-new-players\">Players</label>",
                "<input id=\"f-new-players\" type=\"text\" inputmode=\"numeric\" size=\"4\" maxlength=\"16\"")) {
            if (html.indexOf(want) < 0 || html.indexOf(want) != html.lastIndexOf(want)) {
                bad.add("page lacks (or doubles) " + clip(want) + " (flips if the one-password hint, the New"
                        + " title or the New dialog's Players input drifts)");
            }
        }
        for (String literal : List.of(
                "CC.send('/api/shape-new', { name: CC.el('f-new-name').value, base: CC.el('f-new-base').value,"
                        + " players: CC.el('f-new-players').value,"
                        + " half: CC.el('f-new-half').value, cr: CC.el('f-new-cr').value,"
                        + " terrain: CC.el('f-new-backdrop').value}, {busy: CC.el('btn-shape-new')})",
                // main's pin: the Players prefill = the picked base's seats, refilled on Based on
                "CC.el('f-new-players').value = o && o.seats !== undefined && o.seats !== null ? String(o.seats) : '';",
                "fillPlayers(); base.onchange = fillPlayers;",
                "setupPackaged = c.packaged === true;",
                "CC.show(CC.el('shape-new'), setupPackaged);", "CC.show(CC.el('hint-one-password'), setupPackaged);")) {
            if (!flatJs.contains(literal)) {
                bad.add("script lacks '" + clip(literal) + "' (flips if the New post,"
                        + " the Players prefill or the package show-gate is unwired)");
            }
        }
        return bad;
    }

    /**
     * {@code web-owner-switch-clears-passwords} -
     * the shape-switch password clear, over the SERVED script. {@code setupApplyShape}'s body runs {@code setupClearPasswords()} FIRST
     * (before it reads the pick) and only for a switch off a package ({@code switched === true &&
     * !setupPackaged}); the {@code f-shape} change listener passes the switch; {@code setupFillShapes} (a config
     * re-read) calls it WITHOUT one; {@code setupOnConfig} sets {@code setupPackaged} BEFORE the fill.
     */
    static String ownerSwitchClearsPasswords(String js) {
        String flat = js.replaceAll("\\s+", " ");
        List<String> bad = new ArrayList<>();
        String head = "const setupApplyShape = (switched) => {";
        int from = flat.indexOf(head);
        int to = from < 0 ? -1 : flat.indexOf("};", from);
        String body = to < 0 ? "" : flat.substring(from + head.length(), to);
        String clear = "if (switched === true && !setupPackaged) { setupClearPasswords(); }";
        int clearAt = body.indexOf(clear);
        int pickAt = body.indexOf("const o = setupShapeOptions[");
        if (clearAt < 0 || pickAt < 0 || clearAt > pickAt) {
            bad.add("setupApplyShape does not clear the typed passwords FIRST on a switch off a package (flips if the"
                    + " clear is dropped, moved after the pick read, or loses its package gate)");
        }
        if (!flat.contains("CC.el('f-shape').addEventListener('change', () => setupApplyShape(true));")) {
            bad.add("the f-shape listener does not pass the switch (flips if a host's pick stops clearing)");
        }
        int fill = flat.indexOf("const setupFillShapes = (c) => {");
        int fillEnd = fill < 0 ? -1 : flat.indexOf("};", fill);
        if (fillEnd < 0 || !flat.substring(fill, fillEnd).contains("setupApplyShape();")
                || flat.substring(fill, fillEnd).contains("setupApplyShape(true)")) {
            bad.add("setupFillShapes does not re-apply WITHOUT a switch (flips if a config re-read clears a typed"
                    + " password)");
        }
        int set = flat.indexOf("setupPackaged = c.packaged === true;");
        int filled = flat.indexOf("setupFillShapes(c);");
        if (set < 0 || filled < 0 || set > filled) {
            bad.add("setupOnConfig sets setupPackaged after the shape fill (flips if the first fill reads a stale"
                    + " mode)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code page-token-howto}:
     * the way in is told on the help sheet, the no-key page and the WRONG KEY
     * strip ({@code _renderStrip}'s detail in the served script) - each names {@code run/console.token} (no absolute
     * URL: {@code page-csp-clean}) BEFORE any "printed" address (after a re-key the printed address is dead; the
     * file is the truth); {@code #help-token} names the "# and the key" form and never
     * claims the console "writes the address" (the file holds only the key); the help and the no-key page say the
     * key survives console restarts and how to make a new one (delete the file); the retired per-boot sentence
     * ("changes at every console start") appears nowhere in the served page or script.
     */
    static String tokenHowto(String html, String js) {
        List<String> bad = new ArrayList<>();
        String helpText = between(html, "<p id=\"help-token\">", "</p>");
        String noKey = between(html, "<div id=\"no-key\"", "</div>");
        String strip = between(js, "CC._net === 'wrongkey'", "} else if");
        if (!helpText.contains("# and the key") || helpText.contains("writes the address")) {
            bad.add("#help-token does not tell the # + key way in (flips if the help claims the file holds an"
                    + " address, or loses the no-terminal sentence)");
        }
        for (String[] where : new String[][] {{"#help-token", helpText}, {"#no-key", noKey}, {"WRONG KEY detail", strip}}) {
            int file = where[1].indexOf("run/console.token");
            int printed = where[1].indexOf("printed");
            if (file < 0 || (printed >= 0 && printed < file)) {
                bad.add(where[0] + " does not lead with run/console.token (flips if a remedy text sends the reader to"
                        + " the printed address first, or loses the file)");
            }
        }
        for (String[] where : new String[][] {{"#help-token", helpText}, {"#no-key", noKey}}) {
            if (!where[1].contains("restarts") || !where[1].contains("delete")) {
                bad.add(where[0] + " does not say the key survives console restarts and that deleting the file"
                        + " re-keys (flips if the persistence sentence or the re-key way is lost)");
            }
        }
        if (html.contains("every console start") || js.contains("every console start")) {
            bad.add("the retired per-boot sentence is back (flips if a text says the key changes at every start)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code page-quit} (the page offers a visible way to exit the console):
     * ONE
     * {@code #btn-quit} button in {@code #nav} after {@code #theme-toggle}, its help row {@code tip-quit} once, the
     * script binds it and posts {@code /api/quit}; the no-key page and {@code #help-token} tell the double-click
     * way in (a second {@code ops-web.bat} opens the RUNNING ops GUI's page with its key).
     */
    static String quitPage(String html, String js) {
        List<String> bad = new ArrayList<>();
        Matcher nav = NAV.matcher(html);
        String inNav = nav.find() ? nav.group(1) : "";
        int theme = inNav.indexOf("id=\"theme-toggle\"");
        int at = inNav.indexOf("id=\"btn-quit\"");
        if (theme < 0 || at < theme || count(html, "id=\"btn-quit\"") != 1 || count(html, "id=\"tip-quit\"") != 1) {
            bad.add("no single #btn-quit after #theme-toggle in #nav with one tip-quit (flips if the button leaves"
                    + " the nav, doubles, or loses its help row)");
        }
        if (!js.contains("CC.el('btn-quit')") || !js.contains("'/api/quit'")) {
            bad.add("the script does not bind #btn-quit to /api/quit (flips if the press is unwired or the route"
                    + " is renamed on one side)");
        }
        for (String[] where : new String[][] {{"#help-token", between(html, "<p id=\"help-token\">", "</p>")},
                {"#no-key", between(html, "<div id=\"no-key\"", "</div>")}}) {
            if (!where[1].contains("ops-web.bat")) {
                bad.add(where[0] + " does not tell the double-click way in (flips if the text loses ops-web.bat)");
            }
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /** {@code text} from {@code start} (inclusive) to the next {@code end}; {@code ""} when either is absent. */
    private static String between(String text, String start, String end) {
        int from = text.indexOf(start);
        int to = from < 0 ? -1 : text.indexOf(end, from);
        return to < 0 ? "" : text.substring(from, to);
    }

    /** A const arrow function's body in the whitespace-folded script -
     *  {@code flat} from just after {@code head} to the first {@code "};"} after it (the cut the password-clear row
     *  uses); {@code ""} when either is absent. */
    private static String bodyOf(String flat, String head) {
        int from = flat.indexOf(head);
        int to = from < 0 ? -1 : flat.indexOf("};", from + head.length());
        return to < 0 ? "" : flat.substring(from + head.length(), to);
    }

    /** How many times {@code needle} occurs in {@code text} (non-overlapping; 0 for an empty needle). */
    private static int count(String text, String needle) {
        int n = 0;
        if (needle.isEmpty()) {
            return 0;
        }
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            n++;
        }
        return n;
    }

    /** A custom property's name in a CSS block (group 1). */
    private static final Pattern CSS_VAR = Pattern.compile("(--[a-z][a-z0-9-]*)\\s*:");
    /** One assignment to the root's {@code dataset.theme} in the script (group 1 = the assigned expression). */
    private static final Pattern THEME_SET = Pattern.compile("dataset\\.theme\\s*=(?!=)\\s*([^;]*);");

    /**
     * {@code page-theme-dark-default} - the page is dark before (and without)
     * any script, whatever the operating system's scheme: the stylesheet's FIRST {@code :root} block holds
     * {@code color-scheme: dark} and the dark {@code --bg}; no {@code prefers-color-scheme} and no comment anywhere in
     * the stylesheet; exactly one {@code :root[data-theme="light"]} block, holding {@code color-scheme: light}, whose
     * custom-property names are the first block's minus its three non-colour members (16 names); the colour-scheme
     * meta reads {@code dark} alone, once.
     */
    static String themeDarkDefault(String html, String css) {
        List<String> bad = new ArrayList<>();
        String first = between(css, ":root {", "}");
        if (!first.contains("color-scheme: dark;") || !first.contains("--bg: #15181c;")) {
            bad.add("the first :root block is not the dark scheme (flips if the default turns light, or follows the"
                    + " system scheme, again)");
        }
        for (String banned : List.of("prefers-color-scheme", "/*")) {
            if (css.contains(banned)) {
                bad.add("'" + banned + "' in the stylesheet (flips if the system scheme picks the page's colours again,"
                        + " or a comment lands in the served stylesheet)");
            }
        }
        String lightHead = ":root[data-theme=\"light\"] {";
        String light = between(css, lightHead, "}");
        if (count(css, lightHead) != 1 || !light.contains("color-scheme: light;")) {
            bad.add("no single :root[data-theme=\"light\"] block holding color-scheme: light (flips if the light"
                    + " re-bind is dropped or doubled, or leaves the browser's own controls dark)");
        }
        Set<String> want = new LinkedHashSet<>(all(CSS_VAR, first));
        want.removeAll(List.of("--tap", "--row-h", "--mono"));
        Set<String> got = new LinkedHashSet<>(all(CSS_VAR, light));
        if (want.size() != 16 || !want.equals(got)) {
            bad.add("the light block re-binds " + got.size() + " names vs the dark default's " + want.size()
                    + " colours (flips if a colour drops out of the light set, a non-colour member is re-bound, or a"
                    + " colour lands in one scheme only)");
        }
        String meta = "<meta name=\"color-scheme\" content=\"dark\">";
        if (count(html, meta) != 1 || count(html, "name=\"color-scheme\"") != 1) {
            bad.add("the color-scheme meta is not content=\"dark\" once (flips if it lets a browser paint a light"
                    + " canvas before the stylesheet lands)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code page-theme-toggle} - ONE {@code #theme-toggle} button in
     * {@code #nav} after {@code #help-open}, with no {@code aria-pressed} (the nav's picked-tab paint) and no
     * {@code data-section} (the tab contract), its help row {@code tip-theme} once; the script reads the saved
     * {@code cc.theme} once, inside a {@code try} whose {@code catch} counts {@code CC._fault}; every
     * {@code dataset.theme} assignment writes {@code 'light'} (no other route sets the attribute), applied only for
     * the stored word {@code light}; the click is bound.
     */
    static String themeToggle(String html, String js) {
        List<String> bad = new ArrayList<>();
        Matcher nav = NAV.matcher(html);
        String inNav = nav.find() ? nav.group(1) : "";
        int help = inNav.indexOf("id=\"help-open\"");
        int at = inNav.indexOf("id=\"theme-toggle\"");
        int open = at < 0 ? -1 : inNav.lastIndexOf("<button", at);
        int close = at < 0 ? -1 : inNav.indexOf('>', at);
        String tag = open < 0 || close < 0 ? "" : inNav.substring(open, close + 1);
        if (help < 0 || at < help || count(html, "id=\"theme-toggle\"") != 1 || !tag.contains("type=\"button\"")
                || tag.contains("aria-pressed") || tag.contains("data-section")) {
            bad.add("no single #theme-toggle button after #help-open in #nav free of aria-pressed / data-section"
                    + " (flips if the toggle leaves the nav, or reads or paints as a section tab)");
        }
        if (count(html, "id=\"tip-theme\"") != 1) {
            bad.add("tip-theme is not on the page once (flips if the toggle's help row is dropped or doubled)");
        }
        String read = "localStorage.getItem('cc.theme')";
        int get = js.indexOf(read);
        int tryAt = get < 0 ? -1 : js.lastIndexOf("try {", get);
        int catchAt = get < 0 ? -1 : js.indexOf("} catch (", get);
        int catchEnd = catchAt < 0 ? -1 : js.indexOf('}', catchAt + 1);
        int fault = catchAt < 0 ? -1 : js.indexOf("CC._fault(", catchAt);
        if (count(js, read) != 1 || tryAt < 0 || js.substring(tryAt, get).contains("}") || catchAt < 0 || fault < 0
                || fault > catchEnd) {
            bad.add("the saved scheme is not read once inside a try whose catch counts CC._fault (flips if a blocked"
                    + " storage can throw out of the wiring and lose every listener after it)");
        }
        List<String> sets = all(THEME_SET, js);
        if (sets.isEmpty() || !sets.stream().allMatch("'light'"::equals) || js.contains("data-theme")) {
            bad.add("dataset.theme assignments " + sets + " (flips if a value other than light is applied, or the"
                    + " attribute is set by another route)");
        }
        String flat = js.replaceAll("\\s+", " ");
        for (String literal : List.of("if (themeSaved === 'light') { themeRoot.dataset.theme = 'light'; } else {"
                + " delete themeRoot.dataset.theme; }", "on(CC.el('theme-toggle'), 'click', () => {")) {
            if (!flat.contains(literal)) {
                bad.add("script lacks '" + clip(literal) + "' (flips if a stored word other than light re-binds the"
                        + " colours, or the toggle's click is unbound)");
            }
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code page-draft-overlay} - {@code setupOnConfig} runs
     * {@code setupDraftOverlay(c)} once, AFTER the shape fill and the map fill; the overlay binds each control by its
     * {@code data-cc} id ({@code c.draft[el.dataset.cc]}), skips a non-string value, a password input and a touched
     * field, marks touched only a value that landed, lands a changed shape through {@code setupApplyShape()} with no
     * switch, and names no password control; {@code SETUP_DRAFTED} = {@code SETUP_PREFILL} + {@code f-shape} +
     * {@code f-advertise}, with no password id in the prefill list; the restore note's literal appears once, behind
     * the first-answer flag.
     */
    static String draftOverlay(String js) {
        String flat = js.replaceAll("\\s+", " ");
        List<String> bad = new ArrayList<>();
        String onConfig = bodyOf(flat, "const setupOnConfig = (c) => {");
        int fill = onConfig.indexOf("setupFillShapes(c);");
        int map = onConfig.indexOf("setupMapOnConfig(c);");
        int over = onConfig.indexOf("setupDraftOverlay(c);");
        if (fill < 0 || map < 0 || over < fill || over < map || count(flat, "setupDraftOverlay(c);") != 1) {
            bad.add("setupOnConfig does not run the draft overlay once, after the shape and map fills (flips if the"
                    + " overlay is dropped, or runs before a fill that then overwrites it)");
        }
        String body = bodyOf(flat, "const setupDraftOverlay = (c) => {");
        for (String need : List.of("const v = has ? c.draft[el.dataset.cc] : undefined;",
                "if (typeof v !== 'string' || el.type === 'password' || setupTouched(el)) { continue; }",
                "setupSetValue(el, v); if (el.value === v) { el.dataset.touched = '1'; }",
                "if (shape.value !== shapeWas) { setupApplyShape(); setupMapAsk(); }")) {
            if (!body.contains(need)) {
                bad.add("the overlay lacks '" + clip(need) + "' (flips if it binds by position, can write a password"
                        + " or a touched field, marks a dropped value restored, or lands a shape outside the shape path)");
            }
        }
        for (String secret : List.of("f-password", "f-admin", "setup.password", "setup.adminPassword",
                "setupApplyShape(true)")) {
            if (body.contains(secret)) {
                bad.add("the overlay names " + secret + " (flips if it can fill a password control, or clears typed"
                        + " passwords when it restores a shape)");
            }
        }
        String prefill = between(flat, "const SETUP_PREFILL = [", "];");
        if (!flat.contains("const SETUP_DRAFTED = SETUP_PREFILL.map(([id]) => id).concat(['f-shape', 'f-advertise']);")
                || prefill.isEmpty() || prefill.contains("f-password") || prefill.contains("f-admin")) {
            bad.add("SETUP_DRAFTED is not the password-free prefill list + the shape + the address (flips if the"
                    + " drafted set drifts from the server's ids or takes a password control)");
        }
        String note = "CC.note('restored your unsaved setup from ' + shown + ' - Start saves it');";
        int gate = body.indexOf("if (!setupDraftNoted) { setupDraftNoted = true;");
        int noteAt = body.indexOf(note);
        if (count(flat, "restored your unsaved setup") != 1 || gate < 0 || noteAt < gate
                || !flat.contains("let setupDraftNoted = false;") || count(flat, "setupDraftNoted = true;") != 1) {
            bad.add("the restore note is not once, behind the first-answer flag (flips if it repeats on every config"
                    + " answer or is lost)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code page-draft-post} - the draft save = ONE chained
     * {@code CC.api('/api/draft', {id, value}, {quiet: true})} per control after the take (pending + last-sent
     * dedupe); {@code input} debounced 750 ms per control, {@code change} at once; a failure forgets the sent value
     * and notes once per reason with no retry; {@code pagehide} cancels the timers and sends what waits with
     * {@code keepalive}; {@code CC.startForm} cancels every waiting save before it builds its fields; the core's
     * {@code api} sets the fetch's {@code keepalive} and skips the error dialog on {@code quiet}.
     */
    static String draftPost(String js) {
        String flat = js.replaceAll("\\s+", " ");
        List<String> bad = new ArrayList<>();
        String send = "CC.api('/api/draft', {id: el.dataset.cc, value: el.value}, {quiet: true})";
        String flush = bodyOf(flat, "const setupDraftFlush = (el) => {");
        int take = flush.indexOf("const value = setupDraftTake(id, el);");
        int post = flush.indexOf(send);
        if (count(flat, send) != 1 || take < 0 || post < take || !flush.contains("clearTimeout(setupDraftTimers.get(id));")
                || !flush.contains("const prev = setupDraftChains.get(id) || Promise.resolve();")
                || !flush.contains("setupDraftChains.set(id, prev.then(")) {
            bad.add("the save is not one chained /api/draft post of the control's data-cc id + value after the take"
                    + " (flips if the route or body keys drift from the server's, or two saves of one field overlap)");
        }
        String taken = bodyOf(flat, "const setupDraftTake = (id, el) => {");
        if (!taken.contains("if (!setupDraftPending.has(id)) { return null; }")
                || !taken.contains("if (setupDraftSent.get(id) === value) { return null; }")
                || !taken.contains("setupDraftSent.set(id, value);")) {
            bad.add("no pending + last-sent dedupe (flips if an unchanged value posts again, or a cancelled save still"
                    + " sends)");
        }
        String input = between(flat, "el.addEventListener('input', () => { setupDraftPending.add(id);", "});");
        String change = between(flat, "el.addEventListener('change', () => { setupDraftPending.add(id);", "});");
        if (!input.contains("clearTimeout(setupDraftTimers.get(id));")
                || !input.contains("setupDraftTimers.set(id, setTimeout(() => setupDraftFlush(el), 750));")
                || !change.contains("setupDraftFlush(el);")) {
            bad.add("typing is not debounced 750 ms per control with a change flush (flips if every keystroke posts,"
                    + " or leaving a field waits)");
        }
        String failed = bodyOf(flat, "const setupDraftFailed = (id, value, x) => {");
        if (!failed.contains("setupDraftSent.delete(id);")
                || !failed.contains("if (why !== setupDraftWhy) { setupDraftWhy = why;"
                        + " CC.note('unsaved setup not kept: ' + why); }")
                || failed.contains("CC.api(") || failed.contains("setupDraftFlush(")
                || !flat.contains("const setupDraftOk = () => { setupDraftWhy = null; };")) {
            bad.add("a failed save is not forgotten and noted once per reason with no retry (flips if a refusal"
                    + " retries in a loop, notes on every keystroke, or counts a value the server refused as sent)");
        }
        String hide = between(flat, "window.addEventListener('pagehide', () => {", "});");
        if (!hide.contains("clearTimeout(t);") || !hide.contains("const value = setupDraftTake(id, el);")
                || !hide.contains("CC.api('/api/draft', {id: el.dataset.cc, value: el.value},"
                        + " {quiet: true, keepalive: true})")) {
            bad.add("no pagehide flush with keepalive (flips if a closing tab drops the last typed value)");
        }
        String api = between(flat, "api(path, fields, opts) { const o = opts || {};", "send(path, fields, opts) {");
        if (!api.contains("if (o.keepalive === true) { init.keepalive = true; }")
                || !api.contains("if (!isStatus && o.quiet !== true) { CC.dialogError(title, a.error); }")) {
            bad.add("the core api lacks the keepalive / quiet options (flips if the pagehide flush dies with the page,"
                    + " or a failed background save opens the error dialog)");
        }
        String start = between(flat, "CC.startForm = () => {", "return f;");
        int cancel = start.indexOf("setupDraftCancel();");
        int fields = start.indexOf("const f = {");
        String cancelBody = bodyOf(flat, "const setupDraftCancel = () => {");
        if (cancel < 0 || fields < cancel || !cancelBody.contains("clearTimeout(t);")
                || !cancelBody.contains("setupDraftTimers.clear();") || !cancelBody.contains("setupDraftPending.clear();")) {
            bad.add("CC.startForm does not cancel the waiting saves before it builds its fields (flips if a late timer"
                    + " re-drafts a form Start or the debug watch just sent)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code page-players-field} - the package Players field
     * ({@code setup.shape.players}, at most 3 characters) and its Apply ({@code setup.shape.playersApply}) once each,
     * inside a hidden {@code #players-ctl} between the shape select and its hint, inside {@code #pre-start}, help row
     * {@code tip-players} once; the script binds the Apply through {@code setupMapBind} with op {@code players},
     * prefills the count from the picked option's {@code seats} inside {@code setupApplyShape} (a switch drops a typed
     * count) and shows the field on {@code setupPackaged}.
     */
    static String playersField(String html, String js) {
        List<String> bad = new ArrayList<>();
        String input = "<input id=\"f-players\" data-cc=\"setup.shape.players\" type=\"text\" inputmode=\"numeric\""
                + " size=\"4\" maxlength=\"3\" autocomplete=\"off\" aria-describedby=\"tip-players\">";
        String button = "<button type=\"button\" id=\"btn-players\" data-cc=\"setup.shape.playersApply\""
                + " aria-label=\"Apply the player count\" aria-describedby=\"tip-players\">Apply</button>";
        int pre = html.indexOf("<fieldset id=\"pre-start\"");
        int preEnd = pre < 0 ? -1 : html.indexOf("</fieldset>", pre);
        int shape = html.indexOf("data-cc=\"setup.shape\"");
        int ctl = html.indexOf("<span id=\"players-ctl\" hidden>");
        int in = html.indexOf(input);
        int btn = html.indexOf(button);
        int ctlEnd = ctl < 0 ? -1 : html.indexOf("</span>", ctl);
        int hint = html.indexOf("id=\"hint-shape\"");
        if (count(html, input) != 1 || count(html, button) != 1 || count(html, "id=\"players-ctl\"") != 1 || pre < 0
                || shape < pre || ctl < shape || in < ctl || btn < in || ctlEnd < btn || hint < ctlEnd || hint > preEnd) {
            bad.add("no single hidden #players-ctl with the 3-character Players field + its Apply between the shape"
                    + " select and its hint in #pre-start (flips if the field leaves the pre-start lock, opens shown,"
                    + " loses its ids, or its cap leaves 3)");
        }
        if (count(html, "id=\"tip-players\"") != 1) {
            bad.add("tip-players is not on the page once (flips if the field's help row is dropped or doubled)");
        }
        String flat = js.replaceAll("\\s+", " ");
        String apply = bodyOf(flat, "const setupApplyShape = (switched) => {");
        for (String need : List.of("const players = CC.el('f-players');",
                "if (switched === true) { delete players.dataset.touched; }",
                "if (!setupTouched(players)) { players.value = o && o.seats !== undefined && o.seats !== null"
                        + " ? String(o.seats) : ''; }")) {
            if (!apply.contains(need)) {
                bad.add("setupApplyShape lacks '" + clip(need) + "' (flips if the count stops following the pick, or a"
                        + " typed count survives a switch)");
            }
        }
        for (String need : List.of("setupMapBind(CC.el('f-players'), CC.el('btn-players'), 'players');",
                "CC.show(CC.el('players-ctl'), setupPackaged);")) {
            if (count(flat, need) != 1) {
                bad.add("script lacks one '" + clip(need) + "' (flips if the op word leaves players, or the field shows"
                        + " outside a server package)");
            }
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code page-second-folder} - the Second game folder input
     * ({@code setup.gameRootSecond}) once, in {@code #bind} after the Game folder and before Recording, with its
     * label, its hint and the hint's help row once each; the script prefills it from {@code gameRootSecond} and
     * {@code CC.startForm} sends it under that name.
     */
    static String secondFolder(String html, String js) {
        List<String> bad = new ArrayList<>();
        String input = "<input id=\"f-game-root-second\" data-cc=\"setup.gameRootSecond\" type=\"text\" size=\"40\""
                + " autocomplete=\"off\" spellcheck=\"false\" aria-describedby=\"hint-game-root-second\">";
        int bind = html.indexOf("<fieldset id=\"bind\">");
        int bindEnd = bind < 0 ? -1 : html.indexOf("</fieldset>", bind);
        int first = html.indexOf("data-cc=\"setup.gameRoot\"");
        int at = html.indexOf(input);
        int record = html.indexOf("data-cc=\"setup.record\"");
        if (count(html, input) != 1 || count(html, "data-cc=\"setup.gameRootSecond\"") != 1 || bind < 0 || first < bind
                || at < first || record < at || record > bindEnd) {
            bad.add("no single Second game folder input in #bind between the Game folder and Recording (flips if the"
                    + " field loses its parity id, is doubled, or leaves the Network group)");
        }
        for (String want : List.of("<label for=\"f-game-root-second\">Second game folder</label>",
                "<p class=\"hint\" id=\"hint-game-root-second\">",
                "<dt>Second game folder</dt><dd data-hint=\"hint-game-root-second\"></dd>")) {
            if (count(html, want) != 1) {
                bad.add("page lacks one " + clip(want) + " (flips if the field's label, hint or help row is dropped)");
            }
        }
        String flat = js.replaceAll("\\s+", " ");
        String prefill = between(flat, "const SETUP_PREFILL = [", "];");
        String start = between(flat, "CC.startForm = () => {", "return f;");
        if (!prefill.contains("['f-game-root-second', 'gameRootSecond']")
                || !start.contains("gameRootSecond: CC.el('f-game-root-second').value,")) {
            bad.add("the second folder is not prefilled from gameRootSecond and sent by CC.startForm (flips if it loses"
                    + " its prefill key or its Start send - the second window then never gets its folder)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code page-setup-folds} - the Setup page's four folds. WHY: what a host
     * needs to start a match and hand players their credential stays in view, and every other Setup row sits in
     * exactly one {@code <details class="fold">}, closed at load. The row pins each fold's open tag + summary once (no
     * {@code open} attribute), the EXACT {@code data-cc} set inside each fold (its own summary id included; the
     * Advanced block contains the nested {@code #shape-new}), the in-view set of each Setup fieldset (the fieldset
     * minus its outermost {@code <details>} blocks), and that the section holds no id outside those sets. It is also
     * the pin for the four fold ids the script binds only through a variable ({@code CC.el(id)} in a loop, which
     * {@code page-js-ids-bound} never reads): a missing one would throw at the toggle wiring and stop every part
     * registered after it.
     */
    static String setupFolds(String html) {
        List<String> bad = new ArrayList<>();
        String[][] folds = {{"setup-debug", "debug", "Debug"}, {"setup-advanced", "advanced", "Advanced"},
                {"setup-live", "live", "Match tuning"}, {"setup-voice-knobs", "voice", "Voice tuning"}};
        for (String[] f : folds) {
            String head = "<details id=\"" + f[0] + "\" class=\"fold\">\n<summary data-cc=\"setup.fold." + f[1] + "\">"
                    + f[2] + " &#9662;</summary>";
            if (count(html, head) != 1 || count(html, "id=\"" + f[0] + "\"") != 1) {
                bad.add("fold " + f[0] + " lacks exactly one " + clip(head) + " (flips if the fold is missing, doubled,"
                        + " opens at load, or its summary drifted)");
            }
        }
        Map<String, List<String>> want = new LinkedHashMap<>();
        want.put("fold setup-debug", List.of("setup.fold.debug", "setup.gameRootSecond", "setup.record"));
        want.put("fold setup-advanced", List.of("setup.fold.advanced", "setup.tickRate", "setup.net.seatsPerSource",
                "setup.map.half", "setup.map.apply", "setup.map.station", "setup.map.stationApply", "setup.map.backdrop",
                "setup.map.backdropApply", "setup.map.deployCr", "setup.map.deployCrApply", "setup.shapeNew",
                "setup.fortress.dp", "setup.fortress.hint", "setup.wave.scale", "setup.wave.hint", "setup.wave.rosterDp",
                "setup.wave.rosterMultiplier", "setup.officer.allSkills", "setup.replay.rngPins",
                "setup.mods.magicSeam", "setup.mods.pilotShip"));
        want.put("fold setup-live", List.of("setup.fold.live", "setup.wave.category", "setup.wave.categoryLive",
                "setup.joiner.default", "setup.joiner.defaultLive", "setup.joiner.hint", "setup.joiner.endless",
                "setup.joiner.endlessLive", "setup.fortress.terrain", "setup.fortress.terrainLevel",
                "setup.fortress.terrainLive", "setup.fortress.asteroids", "setup.fortress.asteroidsLive"));
        want.put("fold setup-voice-knobs", List.of("setup.fold.voice", "voice.bitrateKbps", "voice.frameMs",
                "voice.complexity", "voice.maxTalkers", "voice.proxFullSu", "voice.proxCullSu", "voice.relayTickMs",
                "voice.jitterMinFrames", "voice.jitterMaxMs", "voice.jitterShrinkMs"));
        want.put("in view #bind", List.of("setup.bindHost", "setup.bindPort", "setup.lease", "setup.bind.hint",
                "setup.advertise", "setup.reach", "setup.advertise.hint", "setup.gameRoot", "setup.gameRoot.hint",
                "setup.brick.text", "setup.brick"));
        want.put("in view #pre-start", List.of("shell.enable.preStart", "setup.form.title", "setup.shape",
                "setup.shape.players", "setup.shape.playersApply", "setup.shape.hint", "setup.match.pvp", "setup.password",
                "setup.adminPassword", "setup.password.reveal", "setup.admission.hint"));
        want.put("in view #live-dials", List.of("shell.enable.setLive"));
        want.put("in view #voice", List.of("voice.group", "voice.enabled", "voice.fec", "voice.apply",
                "voice.bandwidth", "voice.note.refused", "voice.note.readFail", "voice.note.sent"));
        Set<String> placed = new LinkedHashSet<>(List.of("setup.form.startFields"));
        for (Map.Entry<String, List<String>> e : want.entrySet()) {
            String place = e.getKey();
            String name = place.substring(place.lastIndexOf(' ') + 1);
            String text;
            if (place.startsWith("fold ")) {
                text = detailsBlock(html, name);
            } else {
                String fs = between(html, "<fieldset id=\"" + name.substring(1) + "\"", "</fieldset>");
                text = fs.isEmpty() ? "" : withoutDetails(fs);
            }
            Set<String> have = new LinkedHashSet<>(all(DATA_CC, text));
            Set<String> missing = new LinkedHashSet<>(e.getValue());
            missing.removeAll(have);
            Set<String> extra = new LinkedHashSet<>(have);
            extra.removeAll(e.getValue());
            if (text.isEmpty() || !missing.isEmpty() || !extra.isEmpty()) {
                bad.add(place + (text.isEmpty() ? " not found" : "") + " missing " + missing + " extra " + extra
                        + " (flips if a Setup id moves between a fold and the view, or a new field lands without a"
                        + " chosen fold)");
            }
            placed.addAll(e.getValue());
        }
        String section = between(html, "<section id=\"sec-setup\"", "</section>");
        Set<String> stray = new LinkedHashSet<>(all(DATA_CC, section));
        stray.removeAll(placed);
        if (section.isEmpty() || !stray.isEmpty()) {
            bad.add("#sec-setup " + (section.isEmpty() ? "not found" : "holds ids in no fold and no in-view set " + stray)
                    + " (flips if a Setup control sits in no fieldset: choose its fold or its in-view place)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * The {@code <details id="<id>"} block of {@code html} up to its MATCHING
     * {@code </details>} (a depth count over the {@code <details} / {@code </details>} tokens, so a nested block such
     * as {@code #shape-new} stays inside); {@code ""} when the open tag is absent or never closes.
     */
    private static String detailsBlock(String html, String id) {
        int from = html.indexOf("<details id=\"" + id + "\"");
        int to = from < 0 ? -1 : detailsEnd(html, from);
        return to < 0 ? "" : html.substring(from, to);
    }

    /** Just past the {@code </details>} matching the {@code <details} at
     *  {@code from}; -1 when it never closes. */
    private static int detailsEnd(String text, int from) {
        int depth = 0;
        int at = from;
        while (at >= 0) {
            int open = text.indexOf("<details", at);
            int close = text.indexOf("</details>", at);
            if (close < 0) {
                return -1;
            }
            if (open >= 0 && open < close) {
                depth++;
                at = open + "<details".length();
            } else {
                depth--;
                at = close + "</details>".length();
                if (depth == 0) {
                    return at;
                }
            }
        }
        return -1;
    }

    /** {@code text} with every outermost {@code <details>} block cut out (an
     *  unclosed one cuts to the end), so what remains is what a host sees with every fold closed. */
    private static String withoutDetails(String text) {
        StringBuilder out = new StringBuilder();
        int at = 0;
        for (int open = text.indexOf("<details"); open >= 0; open = text.indexOf("<details", at)) {
            out.append(text, at, open);
            int end = detailsEnd(text, open);
            at = end < 0 ? text.length() : end;
        }
        return out.append(text.substring(at)).toString();
    }

    /**
     * {@code page-setup-fold-opens-on-refusal} - the folds' script. WHY: a
     * refused field marked inside a closed fold is an error the host never sees, so the one {@code aria-invalid}
     * setter (the voice refusal) first opens every {@code <details>} ancestor of the field; and the open / closed
     * memory is one {@code localStorage} key read inside the same {@code try} as its parse, each flag opening a fold
     * only on exactly {@code true}, so a stored null / array / string never throws past the setup wiring. The
     * wiring pins restore at load, remember on every toggle, and the hide in {@code setupOnConfig} after the map
     * fill and before the draft overlay.
     */
    static String setupFoldOpensOnRefusal(String js) {
        List<String> bad = new ArrayList<>();
        String flat = js.replaceAll("\\s+", " ");
        if (count(js, "setAttribute('aria-invalid', 'true')") != 1
                || !flat.contains("setupUnfold(CC.el(id)); CC.el(id).setAttribute('aria-invalid', 'true');")) {
            bad.add("the aria-invalid setter is not exactly one, unfolded first (flips if a refusal can mark a field"
                    + " inside a closed fold, or a second setter lands without the unfold)");
        }
        if (!bodyOf(flat, "const setupUnfold = (el) => {").contains("for (let d = el.closest('details'); d;"
                + " d = d.parentElement && d.parentElement.closest('details')) { d.open = true; }")) {
            bad.add("setupUnfold lacks the every-ancestor loop (flips if the unfold stops at one fold instead of every"
                    + " ancestor)");
        }
        String r = bodyOf(flat, "const setupFoldsRestore = () => {");
        int t = r.indexOf("try {");
        int p = r.indexOf("JSON.parse(localStorage.getItem('cc.setup.folds'))");
        int f = r.indexOf("ok && v[word] === true");
        int c = r.indexOf("} catch (");
        if (!(0 <= t && t < p && p < f && f < c)
                || !r.contains("const ok = v !== null && typeof v === 'object' && !Array.isArray(v);")) {
            bad.add("setupFoldsRestore does not parse and read each flag inside one try (try=" + t + " parse=" + p
                    + " flag=" + f + " catch=" + c + ") (flips if a stored null / array / string can throw past the"
                    + " setup wiring, or a value other than true opens a fold)");
        }
        String m = bodyOf(flat, "const setupFoldsRemember = () => {");
        if (!m.contains("Object.fromEntries(SETUP_FOLDS.map(([id, word]) => [word, CC.el(id).open]))")
                || !m.contains("localStorage.setItem('cc.setup.folds', JSON.stringify(flags));")
                || count(js, "'cc.setup.folds'") != 2) {
            bad.add("setupFoldsRemember does not store the four open flags under the one key ('cc.setup.folds' x"
                    + count(js, "'cc.setup.folds'") + ") (flips if the memory stores anything but the four open flags"
                    + " under the one key)");
        }
        String o = bodyOf(flat, "const setupOnConfig = (c) => {");
        int map = o.indexOf("setupMapOnConfig(c);");
        int hide = o.indexOf("setupFoldsHide();");
        int overlay = o.indexOf("setupDraftOverlay(c);");
        if (!flat.contains("const SETUP_FOLDS = [['setup-debug', 'debug'], ['setup-advanced', 'advanced'],"
                + " ['setup-live', 'live'], ['setup-voice-knobs', 'voice']];")
                || !js.contains("setupFoldsRestore();")
                || !flat.contains("CC.el(id).addEventListener('toggle', setupFoldsRemember);")
                || map < 0 || !(map < hide && hide < overlay)) {
            bad.add("the folds' wiring drifted (map=" + map + " hide=" + hide + " overlay=" + overlay + ") (flips if"
                    + " the folds stop restoring, remembering, or hiding after the chrome and before the draft overlay)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code web-start-ok-clears-passwords} over the SERVED script.
     * The typed passwords clear on an ACCEPTED Start only: the setup part registers
     * {@code CC.startAccepted = setupClearPasswords}, the Start handler ({@link OpsConsoleJsPlayers}) calls it
     * inside its {@code a && a.ok} branch, and {@code setupOnStatus} never clears them. WHY: the former
     * settle-based clear (the Start button un-busy) emptied the fields on a REFUSED Start too, so the host's
     * retry sent {@code keep} = the saved OLD password, silently - a host-visible wrong secret.
     */
    static String startOkClearsPasswords(String js) {
        String flat = js.replaceAll("\\s+", " ");
        List<String> bad = new ArrayList<>();
        if (!flat.contains("CC.startAccepted = setupClearPasswords;")) {
            bad.add("the setup part does not register CC.startAccepted = setupClearPasswords (flips if an accepted"
                    + " Start stops clearing the typed passwords)");
        }
        // The Start body is the route-and-button function both Start and
        // Launch debug WATCH press through (row page-watch pins that both V entries call it)
        int start = flat.indexOf("const startVia = (path, btnId) => {");
        int startEnd = start < 0 ? -1 : flat.indexOf("const noteWatch = (s) => {", start);
        String startBody = startEnd < 0 ? "" : flat.substring(start, startEnd);
        if (!startBody.contains("if (a && a.ok && typeof CC.startAccepted === 'function') { CC.startAccepted(); }")) {
            bad.add("the Start handler does not call CC.startAccepted() inside its a && a.ok branch (flips if the"
                    + " clear leaves the accepted answer or loses its ok gate)");
        }
        int status = flat.indexOf("const setupOnStatus = (s) => {");
        int statusEnd = status < 0 ? -1 : flat.indexOf("const setupSendDial = ", status);
        if (statusEnd < 0 || flat.substring(status, statusEnd).contains("setupClearPasswords()")) {
            bad.add("setupOnStatus clears the typed passwords (flips if a settle-based clear returns: a REFUSED"
                    + " Start would empty the fields and the retry would send keep)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /** The Battle map row's eight Swing twin ids. */
    static final List<String> MAP_ROW_IDS = List.of("setup.map.half", "setup.map.apply", "setup.map.station",
            "setup.map.stationApply", "setup.map.backdrop", "setup.map.backdropApply", "setup.map.deployCr",
            "setup.map.deployCrApply");

    /**
     * {@code page-map-row-ids} - the Battle map row. Each of
     * {@link #MAP_ROW_IDS} once on the page, inside {@code #pre-start} after the match shape (the pre-start
     * lock covers it) in Swing's order; the hint and the four help tips are the Swing map row's texts (typed here, a
     * second copy) with each tip + " Takes effect at the next Start."; each Apply is described by its tip,
     * each typed field is capped at 16 chars. The script binds the four Applies with their op words through
     * {@code CC.send('/api/match-settings'}, re-asks the config on a shape change, re-reads the config on
     * {@code matchTextChanged} (no focus move) and never copies in the map block, and the status hook re-runs the
     * station gate.
     */
    static String pageMapRow(String html, String js) {
        List<String> bad = new ArrayList<>();
        int pre = html.indexOf("<fieldset id=\"pre-start\"");
        int preEnd = pre < 0 ? -1 : html.indexOf("</fieldset>", pre);
        int shape = html.indexOf("data-cc=\"setup.shape\"");
        int last = shape;
        for (String id : MAP_ROW_IDS) {
            String attr = "data-cc=\"" + id + "\"";
            int at = html.indexOf(attr);
            if (at < 0 || at != html.lastIndexOf(attr) || at < last || at > preEnd) {
                bad.add(id + " at " + at + " (flips if a map id is dropped, doubled, leaves #pre-start after the"
                        + " match shape, or the Swing order drifts)");
            }
            last = Math.max(last, at);
        }
        String tail = " Takes effect at the next Start.</dd>";
        for (String want : List.of("<p class=\"hint\" id=\"hint-map\">Apply, then Start: every join window fetches the"
                        + " new match</p>", "<dt>Battle map</dt><dd data-hint=\"hint-map\"></dd>",
                "<dd id=\"tip-map-half\">Half the width of the square battle map, in game units: 2000 to 60000 (9000 ="
                        + " default)." + tail,
                "<dd id=\"tip-map-station\">Station type: lowtech, midline or hightech - only match shapes with a"
                        + " station." + tail,
                "<dd id=\"tip-map-backdrop\">Battle background: none, open, nebula, nebula_blue or hyper." + tail,
                "<dd id=\"tip-map-cr\">Combat readiness every ship starts with, in percent (1 to 100)." + tail,
                "id=\"btn-map-half\" data-cc=\"setup.map.apply\" aria-label=\"Apply the half size\""
                        + " aria-describedby=\"tip-map-half\"",
                "id=\"btn-map-station\" data-cc=\"setup.map.stationApply\" aria-label=\"Apply the station\""
                        + " aria-describedby=\"tip-map-station\"",
                "id=\"btn-map-backdrop\" data-cc=\"setup.map.backdropApply\" aria-label=\"Apply the background\""
                        + " aria-describedby=\"tip-map-backdrop\"",
                "id=\"btn-map-cr\" data-cc=\"setup.map.deployCrApply\" aria-label=\"Apply the deploy CR\""
                        + " aria-describedby=\"tip-map-cr\"")) {
            if (html.indexOf(want) < 0 || html.indexOf(want) != html.lastIndexOf(want)) {
                bad.add("page lacks (or doubles) " + clip(want) + " (flips if the hint / a tip drifts from its pinned"
                        + " text or an Apply loses its description)");
            }
        }
        for (String field : List.of("id=\"f-map-half\"", "id=\"f-map-cr\"")) {
            int at = html.indexOf(field);
            String tag = at < 0 ? "" : html.substring(at, html.indexOf('>', at));
            if (!tag.contains(" maxlength=\"16\"")) {
                bad.add(field + " is not capped at 16 chars (flips if the core's value bound leaves the field)");
            }
        }
        String flatJs = js.replaceAll("\\s+", " ");
        for (String literal : List.of("CC.send('/api/match-settings', {op, value: field.value, descriptor:"
                        + " CC.el('f-shape').value}",
                "setupMapBind(CC.el('f-map-half'), CC.el('btn-map-half'), 'size');",
                "setupMapBind(CC.el('f-map-station'), CC.el('btn-map-station'), 'station');",
                "setupMapBind(CC.el('f-map-backdrop'), CC.el('btn-map-backdrop'), 'backdrop');",
                "setupMapBind(CC.el('f-map-cr'), CC.el('btn-map-cr'), 'cr');",
                "CC.el('f-shape').addEventListener('change', setupMapAsk);",
                "CC.api('/api/config?descriptor=' + encodeURIComponent(CC.el('f-shape').value))",
                "if (a.matchTextChanged === true) { CC.refreshConfig(); }",
                "setupMapGate(lad.startEnabled);", "setupMapOnConfig(c);")) {
            if (!flatJs.contains(literal)) {
                bad.add("script lacks '" + clip(literal) + "' (flips if an Apply is unbound / sends another op word,"
                        + " the shape change stops re-asking, a changed match stops re-reading the config, or a hook"
                        + " is unwired)");
            }
        }
        int from = flatJs.indexOf("const SETUP_MAP_NO_STATION");
        int to = flatJs.indexOf("CC.el('f-map-station').addEventListener('change'");
        if (from < 0 || to < from || flatJs.substring(from, to).contains("_copy(")
                || flatJs.substring(from, to).contains("clipboard")) {
            bad.add("the map block is missing or copies by itself (flips if the map block copies to the clipboard)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /** One row: the check answers null (green) or the first offence; a throw is that row's FAIL. */
    private static void row(List<String> fails, String name, Supplier<String> check) {
        String why;
        try {
            why = check.get();
        } catch (RuntimeException e) {
            why = "threw " + e.getClass().getSimpleName();
        }
        if (why == null) {
            System.out.println("PASS " + name);
        } else {
            String line = "FAIL " + name + ": " + why;
            System.out.println(line);
            fails.add(line);
        }
    }

    static String idsUnique(String html) {
        for (String v : all(DATA_CC, html)) {
            if (!ID_LAW.matcher(v).matches()) {
                return "data-cc=\"" + v + "\" breaks the id law [a-z][A-Za-z0-9.]* (flips if a malformed id is placed)";
            }
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String v : all(DATA_CC, TEMPLATE.matcher(html).replaceAll(""))) {
            counts.merge(v, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() != 1) {
                return "data-cc=\"" + e.getKey() + "\" appears " + e.getValue()
                        + " times outside <template> (flips if a control id is placed twice)";
            }
        }
        return null;
    }

    /**
     * {@code page-join} - Join from this PC's page shape. ONE
     * {@code id="btn-join"} element: a button carrying {@code data-cc="verb.joinerGui"},
     * {@code aria-describedby="tip-join"} and the text {@link OpsJoinSelf#LABEL} (HTML-escaped here by a second
     * route), {@code hidden} in the source, and NO {@code title} (ONE tip text per button - the Swing
     * {@link OpsJoinSelf#TIP} says "on this computer", the help entry "on the server computer");
     * {@code #join-linux} holds {@link OpsJoinSelf#R9}, hidden too; the help
     * names the label beside its {@code tip-join} entry; the script shows the button only for
     * {@code joinHere === true} and the line only for {@code joinHere === false}, and the click posts
     * {@code /api/join}.
     */
    static String pageJoin(String html, String js) {
        List<String> bad = new ArrayList<>();
        int ids = html.split("id=\"btn-join\"", -1).length - 1;
        String button = "<button type=\"button\" id=\"btn-join\" data-cc=\"verb.joinerGui\""
                + " aria-describedby=\"tip-join\" hidden>" + htmlText(OpsJoinSelf.LABEL) + "</button>";
        if (ids != 1 || !html.contains(button)) {
            bad.add(ids + " id=\"btn-join\" element(s), the exact hidden button with the core's label described by"
                    + " tip-join " + (html.contains(button) ? "present" : "absent") + " (flips if the button loses its"
                    + " id, its Swing twin id, the core's label, its help description or its hidden start)");
        }
        int open = html.indexOf("<button type=\"button\" id=\"btn-join\"");
        String tag = open < 0 ? "" : html.substring(open, html.indexOf('>', open) + 1);
        if (tag.contains(" title=")) {
            bad.add("the btn-join button carries a title (flips if a second tip text - the Swing 'this computer'"
                    + " sentence - comes back beside tip-join)");
        }
        if (!html.contains("<p id=\"join-linux\" class=\"hint\" hidden>" + htmlText(OpsJoinSelf.R9) + "</p>")) {
            bad.add("no hidden #join-linux line holding OpsJoinSelf.R9 (flips if the Linux line drifts from the core)");
        }
        if (!html.contains("<dt>" + htmlText(OpsJoinSelf.LABEL) + "</dt><dd id=\"tip-join\">")) {
            bad.add("no help entry naming " + OpsJoinSelf.LABEL + " beside tip-join (flips if the help loses the row)");
        }
        for (String literal : List.of("CC.show(CC.el('btn-join'), c.joinHere === true);",
                "CC.show(CC.el('join-linux'), c.joinHere === false);")) {
            if (!js.contains(literal)) {
                bad.add("script lacks '" + literal + "' (flips if the config's joinHere stops choosing the button"
                        + " or the line)");
            }
        }
        int click = js.indexOf("CC.el('btn-join').addEventListener('click'");
        int end = click < 0 ? -1 : js.indexOf("});", click);
        if (end < 0 || !js.substring(click, end).contains("CC.send('/api/join'")) {
            bad.add("the btn-join click is unbound or stops posting '/api/join'");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * The help entry's exact text: what the debug watch does for every player, and that Start is for play. The
     * watch's own windows sign in as admin with NO password (the owner seat, re-worded) - the
     * earlier "signs in with it" told a host to set a password the watch never reads.
     */
    static final String WATCH_TIP = "Starts a test match and opens your own join window (two when a Second game folder"
            + " is set). Every player gets hull bars and the debug keys. Admin commands need the admin password; your"
            + " windows sign in as admin without it. For normal play, use Start.";

    /**
     * {@code page-watch} - Launch debug WATCH's page shape over the SERVED html +
     * js. ONE {@code id="btn-watch"} element: the button right after {@code btn-start} carrying {@code verb.watch} (the
     * Swing twin id), {@code aria-describedby="tip-watch"}, {@code hidden} in the source and the label
     * {@code Launch debug WATCH}; its help entry {@code tip-watch} holds {@link #WATCH_TIP} (46 words) beside the
     * label; the script binds the click to {@code V.watch}, which (like {@code V.start}) presses through the one
     * {@code startVia} with its own route + button, shows the button only for {@code joinHere === true}, gates it like
     * Start, lists it in the palette and notes the status key {@code watchNote}.
     */
    static String pageWatch(String html, String js) {
        List<String> bad = new ArrayList<>();
        int ids = html.split("id=\"btn-watch\"", -1).length - 1;
        String start = "<button type=\"button\" id=\"btn-start\" data-cc=\"verb.startServer\">Start server</button>\n";
        String button = "<button type=\"button\" id=\"btn-watch\" data-cc=\"verb.watch\" aria-describedby=\"tip-watch\""
                + " hidden>Launch debug WATCH</button>";
        if (ids != 1 || !html.contains(start + button)) {
            bad.add(ids + " id=\"btn-watch\" element(s), the exact hidden button right after Start "
                    + (html.contains(button) ? "present" : "absent") + " (flips if the button loses its id, its Swing"
                    + " twin id, its label, its help description, its hidden start or its place after Start)");
        }
        int words = WATCH_TIP.trim().split("\\s+").length;
        if (words != 46 || !html.contains("<dt>Launch debug WATCH</dt><dd id=\"tip-watch\">" + WATCH_TIP + "</dd>")) {
            bad.add("the help entry tip-watch beside its label does not hold the " + words + "-word tip (flips if the"
                    + " help row or its text drifts)");
        }
        for (String literal : List.of("CC.el('btn-watch').addEventListener('click', V.watch);",
                "CC.show(CC.el('btn-watch'), c.joinHere === true);",
                "start: () => startVia('/api/start', 'btn-start'),",
                "watch: () => startVia('/api/watch', 'btn-watch'),",
                "['btn-watch', gate.start]", "['Launch debug WATCH', V.watch]", "noteWatch(s);")) {
            if (!js.contains(literal)) {
                bad.add("script lacks '" + literal + "' (flips if the click, the joinHere show, the shared Start press,"
                        + " the Start gate, the palette verb or the watchNote poll drifts)");
            }
        }
        int note = js.indexOf("const noteWatch = (s) => {");
        int noteEnd = note < 0 ? -1 : js.indexOf("};", note);
        if (noteEnd < 0 || !js.substring(note, noteEnd).contains("CC.note(n);")
                || !js.substring(note, noteEnd).contains("n !== watchShown")) {
            bad.add("noteWatch does not note a new watchNote sentence once (flips if the page stops showing the debug"
                    + " watch's outcome, or shows it on every poll)");
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code page-kept} - Keep for everyone / Forget kept on the page.
     * Both buttons sit inside {@code #cl-customs} with their Swing twin ids and help descriptions; the
     * {@code #kept} section follows {@code #bans} with its heading, the two lists, the empty line and the two row
     * templates (each id once); the help rows carry {@link ServerOpsCore#CUSTOM_KEEP_TIP_WEB} /
     * {@link ServerOpsCore#CUSTOM_FORGET_TIP} (escaped here by a second route); the script binds both buttons
     * (Keep the Ban custom way, Forget through {@code V.forgetKept}), gates them (Keep like Ban custom, Forget like
     * Unban custom), defines {@code renderKept} and calls it on every status, paints the ids by textContent, sends
     * the per-row verbs by their own keys, and its empty-list text is {@link ServerOpsCore#NO_KEPT_TEXT}.
     */
    static String pageKept(String html, String js) {
        List<String> bad = new ArrayList<>();
        int cl = html.indexOf("<div class=\"cluster\" id=\"cl-customs\"");
        String customs = cl < 0 ? "" : html.substring(cl, html.indexOf("</div>", cl));
        for (String b : List.of(
                "<button type=\"button\" id=\"btn-keep-custom\" data-cc=\"verb.customKeep\""
                        + " aria-describedby=\"pick-why tip-keep-custom\">" + htmlText(ServerOpsCore.CUSTOM_KEEP_LABEL),
                "<button type=\"button\" id=\"btn-forget-custom\" data-cc=\"verb.customForget\""
                        + " aria-describedby=\"tip-forget-custom\">" + htmlText(ServerOpsCore.CUSTOM_FORGET_LABEL))) {
            if (!customs.contains(b)) {
                bad.add("#cl-customs lacks " + clip(b) + " (flips if a kept button leaves the CUSTOMS cluster or loses"
                        + " its id / Swing twin id / label / help description)");
            }
        }
        Map<String, Integer> idCounts = new LinkedHashMap<>();
        for (String id : all(ID_ATTR, html)) {
            idCounts.merge(id, 1, Integer::sum);
        }
        for (String id : List.of("kept", "kept-title", "kept-list", "kept-empty", "inbox-title", "inbox-list",
                "tpl-kept-row", "tpl-inbox-row")) {
            if (idCounts.getOrDefault(id, 0) != 1) {
                bad.add("id=\"" + id + "\" found " + idCounts.getOrDefault(id, 0) + " time(s) (flips if a kept-list id"
                        + " the script binds is renamed or doubled)");
            }
        }
        int bans = html.indexOf("<section id=\"bans\"");
        int kept = html.indexOf("<section id=\"kept\" aria-labelledby=\"kept-title\">");
        if (bans < 0 || kept < bans) {
            bad.add("#kept does not follow #bans (flips if the kept section moves off the Players list idiom)");
        }
        for (String[] tip : new String[][] {
                {ServerOpsCore.CUSTOM_KEEP_LABEL, "tip-keep-custom", ServerOpsCore.CUSTOM_KEEP_TIP_WEB},
                {ServerOpsCore.CUSTOM_FORGET_LABEL, "tip-forget-custom", ServerOpsCore.CUSTOM_FORGET_TIP}}) {
            if (!html.contains("<dt>" + htmlText(tip[0]) + "</dt><dd id=\"" + tip[1] + "\">" + htmlText(tip[2]) + "</dd>")) {
                bad.add("no help row " + tip[1] + " carrying the core's sentence (flips if the help drifts from the"
                        + " Swing tip's one home)");
            }
        }
        String flatJs = js.replaceAll("\\s+", " "); // a wrapped statement reads as one line
        for (String literal : List.of(
                "CC.el('btn-keep-custom').addEventListener('click', () => V.customVerb('custom-keep', 'btn-keep-custom'));",
                "CC.el('btn-forget-custom').addEventListener('click', V.forgetKept);",
                "['btn-keep-custom', gate.custom], ['btn-forget-custom', gate.cmd]",
                "const renderKept = (s) => {", "renderKept(s);",
                "CC.text(CC.q('.kept-label', li), stem);", "CC.text(CC.q('.inbox-label', li), name);",
                "{verb: 'custom-forget', stem}", "{verb: 'custom-keep-export', exportName: name}",
                "'" + ServerOpsCore.NO_KEPT_TEXT + "'")) {
            if (!flatJs.contains(literal)) {
                bad.add("script lacks '" + clip(literal) + "' (flips if a kept button is unbound /"
                        + " ungated, renderKept is dropped or unhooked from the status, a list paints by markup, a"
                        + " per-row verb sends another key, or the empty text leaves the core's)");
            }
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /**
     * {@code page-ships} - the ships that come with the server on the page.
     * Inside {@code #kept} (its tag unchanged, {@code page-kept}'s pin) after {@code #inbox-list}, in order: the heading
     * {@code #ships-title} (starting with {@link ServerOpsCore#SHIPS_TITLE}), {@code #ships-list}, {@code #ships-empty}
     * ({@code class="dim"}, {@link ServerOpsCore#SHIPS_EMPTY_TEXT}) and {@code #ships-last}, each id once with the row
     * template {@code tpl-ship-row}; the template carries the label span and the Hide / Show buttons with their
     * {@code verb.shipHide} / {@code verb.shipShow} twins (Show hidden at first); no style attribute in the section or
     * the template; both ids are package controls (never owner-only or package-only); the stylesheet strikes
     * through a {@code ship-hidden} row's label; the script defines {@code renderShips} and calls it on every status,
     * paints ids by textContent, marks a hidden row, sends each verb with its own key, gates both buttons like Forget,
     * and carries the core's heading, empty and failed-save texts.
     */
    static String pageShips(String html, String css, String js) {
        List<String> bad = new ArrayList<>();
        Map<String, Integer> idCounts = new LinkedHashMap<>();
        for (String id : all(ID_ATTR, html)) {
            idCounts.merge(id, 1, Integer::sum);
        }
        for (String id : List.of("ships-title", "ships-list", "ships-empty", "ships-last", "tpl-ship-row")) {
            if (idCounts.getOrDefault(id, 0) != 1) {
                bad.add("id=\"" + id + "\" found " + idCounts.getOrDefault(id, 0) + " time(s) (flips if a ships-list id"
                        + " the script binds is renamed or doubled)");
            }
        }
        int open = html.indexOf("<section id=\"kept\" aria-labelledby=\"kept-title\">");
        int close = open < 0 ? -1 : html.indexOf("</section>", open);
        String kept = close < 0 ? "" : html.substring(open, close);
        String[] order = {"<ul id=\"inbox-list\"></ul>",
            "<h2 id=\"ships-title\">" + htmlText(ServerOpsCore.SHIPS_TITLE) + " (0)</h2>",
            "<ul id=\"ships-list\"></ul>",
            "<p id=\"ships-empty\" class=\"dim\">" + htmlText(ServerOpsCore.SHIPS_EMPTY_TEXT) + "</p>",
            "<p id=\"ships-last\""};
        int at = -1;
        for (String part : order) {
            int next = kept.indexOf(part, at + 1);
            if (next < 0) {
                bad.add("#kept lacks, or holds out of order, " + clip(part) + " (flips if the ships list leaves the kept"
                        + " section, moves above the exported list, or loses its heading / empty text / verdict line)");
                break;
            }
            at = next;
        }
        Matcher tpl = Pattern.compile("(?s)<template id=\"tpl-ship-row\">(.*?)</template>").matcher(html);
        String row = tpl.find() ? tpl.group(1) : "";
        for (String part : List.of("<span class=\"ship-label\"></span>",
                "<button type=\"button\" class=\"ship-hide\" data-cc=\"verb.shipHide\">Hide</button>",
                "<button type=\"button\" class=\"ship-show\" data-cc=\"verb.shipShow\" hidden>Show</button>")) {
            if (!row.contains(part)) {
                bad.add("tpl-ship-row lacks " + clip(part) + " (flips if a row loses its label, a button its class /"
                        + " parity twin / label, or Show stops starting hidden)");
            }
        }
        for (String[] part : new String[][] {{"#kept", kept}, {"tpl-ship-row", row}}) {
            Matcher tags = TAG.matcher(part[1]);
            while (tags.find()) {
                if (STYLE_ATTR.matcher(tags.group()).find()) {
                    bad.add("style attribute in " + part[0] + ": " + clip(tags.group()) + " (flips if a ships row is"
                            + " styled inline; the stylesheet's ship-hidden rule is the one look)");
                }
            }
        }
        for (String id : List.of("verb.shipHide", "verb.shipShow")) {
            if (!OpsParity.PACKAGE_IDS.contains(id) || OpsParity.OWNER_IDS.contains(id)
                    || OpsParity.PACKAGE_ONLY_IDS.contains(id)) {
                bad.add(id + " is not a both-modes package control (flips if Hide / Show leaves OpsParity.PACKAGE_IDS"
                        + " or becomes owner-only / package-only)");
            }
        }
        String flatCss = css.replaceAll("\\s+", " ");
        if (!flatCss.contains("#ships-list li.ship-hidden .ship-label { text-decoration: line-through;")) {
            bad.add("the stylesheet does not strike through a hidden row's label (flips if the ship-hidden rule is"
                    + " dropped or renamed away from the class the script sets)");
        }
        String flatJs = js.replaceAll("\\s+", " ");
        for (String literal : List.of(
                "const renderShips = (s) => {", "renderShips(s);",
                "idList(s.shipsBaseIds)", "idList(s.shipsHiddenIds)",
                "CC.text(CC.q('.ship-label', li), r.id);", "li.classList.toggle('ship-hidden', r.hidden);",
                "V.shipHide(r.id, e.currentTarget)", "V.shipShow(r.id, e.currentTarget)",
                "{verb: 'ship-hide', id}", "{verb: 'ship-show', id}",
                "for (const b of CC.qa('#ships-list .ship-hide')) { enable(b, gate.cmd(L)); }",
                "for (const b of CC.qa('#ships-list .ship-show')) { enable(b, gate.cmd(L)); }",
                "s.shipsOverCap === true ? OVER_CAP : ''", "s.shipsPersistFailed",
                "'" + ServerOpsCore.SHIPS_TITLE + "'", "'" + ServerOpsCore.SHIPS_EMPTY_TEXT + "'",
                "'" + ServerOpsCore.SHIPS_SAVE_FAILED_TEXT + "'")) {
            if (!flatJs.contains(literal)) {
                bad.add("script lacks '" + clip(literal) + "' (flips if renderShips is dropped or unhooked from the"
                        + " status, a row paints by markup or stops marking a hidden ship, a verb sends another key,"
                        + " a row button is ungated, or a text leaves the core's)");
            }
        }
        return bad.isEmpty() ? null : bad.size() + " wrong: " + String.join(" | ", bad);
    }

    /** The page's text escaping, written apart from {@link OpsConsolePageHtml#esc} (a second route). */
    private static String htmlText(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            sb.append(ch == '&' ? "&amp;" : ch == '<' ? "&lt;" : ch == '>' ? "&gt;" : ch == '"' ? "&quot;"
                    : String.valueOf(ch));
        }
        return sb.toString();
    }

    static String cspClean(String html, String css, String js) {
        Matcher script = SCRIPT_TAG.matcher(html);
        while (script.find()) {
            String tag = script.group();
            boolean empty = html.startsWith("</script>", script.end());
            if (!tag.contains(" src=\"") || !empty) {
                return "inline script at " + tag + " (flips if script text lands in the HTML; CSP blocks it)";
            }
        }
        if (html.toLowerCase(Locale.ROOT).contains("<style")) {
            return "<style> element (flips if inline CSS lands in the HTML; CSP blocks it)";
        }
        Matcher tags = TAG.matcher(html);
        while (tags.find()) {
            String tag = tags.group();
            if (HANDLER_ATTR.matcher(tag).find()) {
                return "inline handler in " + clip(tag) + " (flips if an on*= attribute lands; CSP blocks it)";
            }
            if (STYLE_ATTR.matcher(tag).find()) {
                return "style attribute in " + clip(tag) + " (flips if inline style lands; CSP blocks it)";
            }
        }
        for (String[] part : new String[][] {{"html", html}, {"css", css}, {"js", js}}) {
            if (part[1].toLowerCase(Locale.ROOT).contains("javascript:")) {
                return "javascript: in the " + part[0] + " (flips if a script URL lands)";
            }
        }
        if (ABS_URL.matcher(HELP_SSH.matcher(html).replaceAll("")).find()) {
            return "absolute http(s) URL in the HTML outside the help's ssh sentence (flips if a CDN / remote link lands)";
        }
        for (String bad : List.of("@import", "url(", "expression(")) {
            if (css.toLowerCase(Locale.ROOT).contains(bad)) {
                return bad + " in the CSS (flips if the stylesheet reaches past 'self')";
            }
        }
        if (ABS_URL.matcher(css).find() || ABS_URL.matcher(js).find()) {
            return "absolute http(s) URL in the CSS / JS (flips if a remote fetch or asset lands)";
        }
        for (String sink : List.of("eval(", "new Function", "innerHTML", "outerHTML", "insertAdjacentHTML",
                "document.write")) {
            if (js.contains(sink)) {
                return sink + " in the JS (flips if page text can run as code or markup; textContent only)";
            }
        }
        return null;
    }

    static String domContract(String html) {
        Map<String, Integer> idCounts = new LinkedHashMap<>();
        for (String id : all(ID_ATTR, html)) {
            idCounts.merge(id, 1, Integer::sum);
        }
        for (String id : CONTRACT_IDS) {
            int n = idCounts.getOrDefault(id, 0);
            if (n != 1) {
                return "id=\"" + id + "\" found " + n + " time(s) (flips if a pinned id the script binds to is renamed or doubled)";
            }
        }
        if (!html.contains("<header id=\"strip\" data-state=\"stopped\">")) {
            return "#strip lacks its opening data-state=\"stopped\" (flips if the strip opens with no state colour)";
        }
        for (String hiddenId : List.of("no-key", "sec-setup", "sec-mods")) {
            if (!Pattern.compile("<[a-z]+ id=\"" + hiddenId + "\" hidden[ >]").matcher(html).find()) {
                return "#" + hiddenId + " is not hidden at load (flips if the page opens on the wrong section or the no-key text)";
            }
        }
        if (Pattern.compile("<section id=\"sec-players\" hidden").matcher(html).find()) {
            return "#sec-players is hidden at load (flips if the page opens on no section)";
        }
        Matcher d = DIALOG.matcher(html);
        while (d.find()) {
            String body = d.group(2);
            if (!(body.contains("<h1") || body.contains("<h2") || body.contains("<h3"))) {
                return "dialog " + clip(d.group(1)) + " has no heading (flips if a dialog loses its title)";
            }
        }
        Matcher nav = NAV.matcher(html);
        if (!nav.find()) {
            return "no <nav id=\"nav\"> (flips if the section nav is dropped)";
        }
        List<String> sections = all(DATA_SECTION, nav.group(1));
        if (!sections.equals(List.of("players", "setup", "mods"))) {
            return "#nav data-section buttons = " + sections + " (flips unless exactly players, setup, mods)";
        }
        int barStart = html.indexOf("<div id=\"bar\"");
        int barEnd = html.indexOf("<div id=\"load\"");
        if (barStart < 0 || barEnd < barStart) {
            return "#bar does not precede #load (flips if the bar / load blocks are dropped or swapped)";
        }
        List<String> bar = all(DATA_CC, html.substring(barStart, barEnd));
        for (String readout : BAR_READOUTS) {
            if (!bar.contains(readout)) {
                return "#bar lacks its read-out / caption " + readout
                        + " (flips if a bar caption or read-out leaves the bar or loses its id)";
            }
        }
        bar.removeAll(BAR_READOUTS);
        if (!bar.equals(BAR_ORDER)) {
            int i = 0;
            while (i < bar.size() && i < BAR_ORDER.size() && bar.get(i).equals(BAR_ORDER.get(i))) {
                i++;
            }
            String got = i < bar.size() ? bar.get(i) : "(end)";
            String want = i < BAR_ORDER.size() ? BAR_ORDER.get(i) : "(end)";
            return "#bar control " + i + " is " + got + ", V3 wants " + want
                    + " (flips if a verb leaves its cluster or the order drifts)";
        }
        return helpHintLaw(html);
    }

    /** The help fill law. A help row that would repeat a
     * {@code p.hint} is an EMPTY {@code <dd data-hint="<hint id>">} the core script fills from that hint at
     * load (a typed copy stays stale when the hint changes). So every {@code data-hint} sits on a
     * help {@code <dd>}, names a {@code <p class="hint" id>} that exists, and carries no text; and no
     * typed help {@code <dd>} equals a hint's text (tags stripped, so a span-id vs span-class twin counts). */
    static String helpHintLaw(String html) {
        Map<String, String> hints = new LinkedHashMap<>();
        Matcher h = HINT_P.matcher(html);
        while (h.find()) {
            hints.put(h.group(1), plain(h.group(2)));
        }
        Matcher help = HELP_DIALOG.matcher(html);
        if (!help.find()) {
            return "no <dialog id=\"dlg-help\"> (flips if the help sheet is dropped)";
        }
        int filled = 0;
        Matcher dd = DD.matcher(help.group(1));
        while (dd.find()) {
            Matcher named = DATA_HINT.matcher(dd.group(1));
            if (named.find()) {
                filled++;
                String id = named.group(1);
                if (!hints.containsKey(id)) {
                    return "<dd data-hint=\"" + id + "\"> names no <p class=\"hint\" id> (flips if a hint is renamed"
                            + " or dropped and its help row would fill empty)";
                }
                if (!dd.group(2).isEmpty()) {
                    return "<dd data-hint=\"" + id + "\"> carries text of its own (flips if a help row types a copy"
                            + " instead of naming the hint)";
                }
                continue;
            }
            String typed = plain(dd.group(2));
            for (Map.Entry<String, String> e : hints.entrySet()) {
                if (!typed.isEmpty() && typed.equals(e.getValue())) {
                    return "a help <dd> repeats #" + e.getKey() + "'s text (flips if a help row copies a hint;"
                            + " write <dd data-hint=\"" + e.getKey() + "\"></dd>)";
                }
            }
        }
        int named = all(DATA_HINT, html).size();
        if (named != filled) {
            return named + " data-hint attribute(s) on the page, " + filled + " on help <dd> rows (flips if a"
                    + " data-hint lands where the core script's help fill never reads it)";
        }
        return null;
    }

    /** An HTML fragment's text: tags removed, whitespace runs folded, trimmed. */
    private static String plain(String fragment) {
        return ANY_TAG.matcher(fragment).replaceAll("").replaceAll("\\s+", " ").trim();
    }

    /** The package texts the page carries STATICALLY are the
     * core composers' own sentences ({@link ServerOpsCore}), so the two shells cannot drift apart again
     * (once, three months of Swing-only changes never reached the page). The sidecar
     * name is the page's one dynamic span; the runtime texts (status detail, confirms, refusals) reach the
     * page through the API and are pinned by the console's own rows. Each sentence appears
     * EXACTLY once (a help row names its hint by {@code data-hint} instead of a typed copy), and the want
     * list gains the empty-bans line (the package bind / advertise / Game folder hints are the page's own
     * text since their window constants were carved). */
    static String textsFromComposers(String html) {
        String[][] want = {
            {"ServerOpsCore.admissionHint(packaged)",
                ServerOpsCore.admissionHint("<span id=\"sidecar-name\"></span>", true)},
            {"ServerOpsCore.admissionInlineHint(packaged)",
                ServerOpsCore.admissionInlineHint("<span class=\"sidecar-name\"></span>", true)},
            // The shape hint replaced the core's kit sentence on the page (a new shape needs a
            // new credential, never a new kit); SHAPE_HINT now reads the core composer, which carries that hint
            {"OpsConsolePageHtml.SHAPE_HINT", OpsConsolePageHtml.SHAPE_HINT},
            {"ServerOpsCore.customExportTipWeb()", ServerOpsCore.customExportTipWeb()},
            {"ServerOpsCore.modsRootsTip(packaged)", ServerOpsCore.modsRootsTip("", true)},
            {"ServerOpsCore.NO_BANS_TEXT", ServerOpsCore.NO_BANS_TEXT},
            {"ServerOpsCore.CUSTOM_KEEP_TIP_WEB", ServerOpsCore.CUSTOM_KEEP_TIP_WEB},
            {"ServerOpsCore.CUSTOM_FORGET_TIP", ServerOpsCore.CUSTOM_FORGET_TIP},
            {"ServerOpsCore.NO_KEPT_TEXT", ServerOpsCore.NO_KEPT_TEXT},
            // The shipped-ships heading and empty line
            {"ServerOpsCore.SHIPS_TITLE", ServerOpsCore.SHIPS_TITLE},
            {"ServerOpsCore.SHIPS_EMPTY_TEXT", ServerOpsCore.SHIPS_EMPTY_TEXT},
        };
        for (String[] w : want) {
            int first = html.indexOf(w[1]);
            if (first < 0) {
                return "the page lacks " + w[0] + "'s sentence (flips if one shell's text changes alone): "
                        + clip(w[1]);
            }
            if (first != html.lastIndexOf(w[1])) {
                return w[0] + "'s sentence appears more than once (flips if a second copy lands that a later"
                        + " one-shell edit would leave stale; a help row names its hint by data-hint): " + clip(w[1]);
            }
        }
        return null;
    }

    static String textLaw(String html, String css, String js) {
        for (String[] part : new String[][] {{"html", html}, {"css", css}, {"js", js}}) {
            String lower = part[1].toLowerCase(Locale.ROOT);
            for (String token : TEXT_LAW_TOKENS) {
                int at = lower.indexOf(token);
                if (at >= 0) {
                    return "'" + token + "' in the " + part[0] + " near '" + clip(part[1].substring(Math.max(0, at - 20),
                            Math.min(part[1].length(), at + token.length() + 20))) + "' (flips if an internal word reaches a stranger's page)";
                }
            }
        }
        return null;
    }

    /** No line of the SERVED script trims to a {@code //}
     * prefix (the strip's own predicate, read over its output). */
    static String jsNoCommentLines(String js) {
        int lineNo = 0;
        for (String line : js.split("\n", -1)) {
            lineNo++;
            if (line.strip().startsWith("//")) {
                return "served line " + lineNo + " '" + clip(line.strip())
                        + "' is a // comment (flips if the strip is bypassed or a part is served raw)";
            }
        }
        return null;
    }

    /** The served script equals the three raw parts joined
     * with {@code "\n"} minus exactly their whole {@code //} lines, rebuilt here by {@link #JS_COMMENT_LINE}
     * (not by the strip itself), so a strip that eats or alters a code line reads as a difference. */
    static String jsStripExact(String js) {
        String raw = String.join("\n", OpsConsoleJsCore.JS, OpsConsoleJsPlayers.JS, OpsConsoleJsSetup.JS);
        String want = JS_COMMENT_LINE.matcher(raw).replaceAll("");
        if (want.equals(js)) {
            return null;
        }
        int at = 0;
        int max = Math.min(want.length(), js.length());
        while (at < max && want.charAt(at) == js.charAt(at)) {
            at++;
        }
        return "served script differs from the raw parts minus their // lines at char " + at + " (want '"
                + clip(want.substring(at, Math.min(want.length(), at + 40))) + "', served '"
                + clip(js.substring(at, Math.min(js.length(), at + 40)))
                + "') (flips if the strip eats or alters a code line)";
    }

    /** No internal token ({@link #JS_TEXT_LAW}) in the
     * served script; the {@code textLaw} idiom, case-sensitive, naming the token and its context. */
    static String jsTextLaw(String js) {
        for (String[] token : JS_TEXT_LAW) {
            Matcher m = Pattern.compile(token[1]).matcher(js);
            if (m.find()) {
                return token[0] + " '" + clip(m.group()) + "' in the served js near '"
                        + clip(js.substring(Math.max(0, m.start() - 20), Math.min(js.length(), m.end() + 20)))
                        + "' (flips if an internal token reaches the served script)";
            }
        }
        return null;
    }

    /** Every id the served script binds by a literal
     * ({@link #JS_ID_BINDS}) is an {@code id="..."} of the page (templates included) or a
     * {@link #JS_MADE_IDS} member. ONE-directional (script within page): an id no script binds is fine,
     * so a new page id needs no entry here. WHY: one HTML id renamed away from the script is a
     * TypeError that stops the whole script while every other row stays green. */
    static String jsIdsBound(String html, String js) {
        Set<String> pageIds = new LinkedHashSet<>(all(ID_ATTR, html));
        Set<String> bound = new LinkedHashSet<>();
        for (Pattern bind : JS_ID_BINDS) {
            bound.addAll(all(bind, js));
        }
        if (bound.isEmpty()) {
            return "no literal id bind found in the served js (flips if the bind idioms change shape and the scan goes blind)";
        }
        for (String id : bound) {
            if (!pageIds.contains(id) && !JS_MADE_IDS.contains(id)) {
                return "the script binds id '" + id + "' that no id=\"...\" of the page carries (of " + bound.size()
                        + " bound) (flips if an HTML id is renamed away from the script, or a script binds an id the page never carries)";
            }
        }
        return null;
    }

    private static List<String> all(Pattern p, String text) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** One line, at most 80 chars (page text only - the blocks carry no player text). */
    private static String clip(String s) {
        String one = s.replace('\n', ' ').replace('\r', ' ');
        return one.length() <= 80 ? one : one.substring(0, 80) + "...";
    }

    public static void main(String[] args) {
        if (args.length == 2 && "--print".equals(args[0])) {
            System.exit(print(args[1]));
        }
        if (args.length != 0) {
            System.err.println("usage: OpsConsolePageSelftest [--print html|css|js]");
            System.exit(2);
        }
        List<String> fails = run(OpsConsolePage.html(), OpsConsolePage.css(), OpsConsolePage.js());
        if (!fails.isEmpty()) {
            System.out.println("ops-console-page selftest FAILED (" + fails.size() + " row(s))");
            System.exit(1);
        }
        System.out.println("ops-console-page selftest PASS");
    }

    /** Writes one block's served UTF-8 bytes ({@link OpsConsolePage}'s accessors) to stdout; 2 = an unknown part name. */
    private static int print(String part) {
        String text = switch (part) {
            case "html" -> OpsConsolePage.html();
            case "css" -> OpsConsolePage.css();
            case "js" -> OpsConsolePage.js();
            default -> null;
        };
        if (text == null) {
            System.err.println("usage: OpsConsolePageSelftest --print html|css|js");
            return 2;
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        System.out.write(bytes, 0, bytes.length);
        System.out.flush();
        return 0;
    }
}
