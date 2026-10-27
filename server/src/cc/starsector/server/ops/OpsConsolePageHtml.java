package cc.starsector.server.ops;

import java.util.List;

/**
 * The console page's HTML - ONE text block, served by
 * {@link OpsConsolePage#html()} at {@code /}.
 *
 * <p>WHY this shape: the V3 geometry of the Swing window:
 * the status strip on top, one section at a time under the {@code Players | Setup | Mods} nav, the
 * load group above the 2x3 verb bar at the bottom. The {@code #dock} (bar + load) sits BEFORE the nav
 * in DOM order so the Tab order reaches the verbs right after the strip; the CSS grid
 * places it at the bottom and the load above the bar. Every Swing package control carries
 * {@code data-cc="<checklist id>"}; outside a {@code <template>} each value appears once. The ids the
 * script parts bind to are pinned (never renamed). Text discipline: pure
 * ASCII (entities for anything else), no backslash anywhere in the block, no inline script / style /
 * handler (CSP {@code default-src 'self'}), and stranger-facing words only (row {@code page-text-law}).
 *
 * <p>The strip's head row holds the hidden {@code #pack-line}
 * ({@code status.pack}) the core script fills with the folder's package version.
 *
 * <p>The Bans section's typed row - {@code #ban-ip-key} + Ban address
 * ({@code #btn-ban-ip}, {@code verb.banIp}, page-only: the Swing window has no twin) above the list, its help
 * entry {@code tip-ban-ip}; the press sends {@code ban-ip} and the server mints the confirm.
 *
 * <p>The read-outs, captions, hints and dialogs carry their
 * checklist id too (one element each; a runtime text variant such as {@code status.headline.stale} rides
 * the element of its family, never an element of its own). The load group gains the rates / phases /
 * totals rows ({@code #load-rates} ... each a caption + an {@code <output id="...-text">}; {@code #load}
 * is focusable so the narrow layout can show every row on a tap), {@code #bans-empty} and
 * {@code #customs-detail} (the Swing summary tooltip as visible text). The package bind / advertise hints
 * are the page's own text (they began as the carved Setup form's constants). HELP LAW:
 * a help row whose text would repeat a {@code p.hint} is an EMPTY {@code <dd data-hint="<hint id>">}
 * the core script fills from that hint at load, so every sentence lives ONCE on the page (rows
 * {@code page-texts-from-composers} + {@code page-dom-contract}).
 *
 * <p>The {@code #btn-reveal} row under the admin password
 * (Show saved passwords, served by {@code POST /api/reveal}; its help entry {@code tip-reveal}), and the
 * Show help entry no longer claims a saved password never reaches the page.
 *
 * <p>The package Setup twins in {@code #bind} - the Game
 * folder row ({@code #f-game-root}, {@code setup.gameRoot}; its hint is the page's own text; Start saves it
 * into {@code launcher.properties}). The page has no folder picker: a browser file input yields CLIENT
 * files, never a path on the server's disk, so the typed field is the whole control. Advertise ({@code #f-advertise}) is an editable
 * {@code <input>} (was a read-only {@code <output>}), the Swing field's twin: the host types the
 * address joiners dial. The Join brick row ({@code #f-brick} {@code setup.brick.text} + {@code #btn-brick}
 * {@code setup.brick}, help entry {@code tip-brick}) is served by {@code POST /api/brick}.
 *
 * <p>Fill from ProtonVPN in the Listen on host row
 * ({@code #btn-lease} {@code setup.lease}, the Swing package button's twin; help entry {@code tip-lease},
 * the Swing tip's text with the page's field names) - served by {@code POST /api/lease} (row {@code page-lease}).
 *
 * <p>Join from this PC in the SERVER cluster right after Start
 * ({@code #btn-join} {@code verb.joinerGui}, the Swing package button's twin; served by {@code POST /api/join})
 * and the {@code #join-linux} line beside it - both {@code hidden} until the first {@code /api/config} answer's
 * {@code joinHere} shows one of them. The label and the line are {@link OpsJoinSelf#LABEL} /
 * {@link OpsJoinSelf#R9} concatenated into {@link #HTML} at class init through {@link #esc} (each text keeps
 * ONE home, the core's); the button's ONE description is its help entry {@code tip-join} through
 * {@code aria-describedby} (the {@code btn-brick} / {@code btn-lease} idiom).
 * The button no longer carries
 * {@link OpsJoinSelf#TIP} as a {@code title} - that Swing sentence says "on this computer", while the page may
 * sit on another one, and {@code tip-join} says "on the server computer" (row {@code page-join} pins the description and the absent {@code title}).
 * Launch debug WATCH right after Start ({@code #btn-watch} {@code verb.watch},
 * the Swing button's twin and its label; served by {@code POST /api/watch}), {@code hidden} until the config's
 * {@code joinHere} shows it beside Join from this PC (the {@code #join-linux} line already says why elsewhere); its
 * ONE description is the help entry {@code tip-watch} through {@code aria-describedby} (row {@code page-watch}).
 * The Export custom help entry is {@link ServerOpsCore#customExportTipWeb()} through {@link #esc}.
 *
 * <p>Keep for everyone / Forget kept in the CUSTOMS cluster
 * ({@code #btn-keep-custom} {@code verb.customKeep}, {@code #btn-forget-custom} {@code verb.customForget}, the
 * Swing buttons' twins - Swing's v3 bar carries them in its own KEPT cluster beside SIM-SPEED for width, the
 * page's Customs menu has no such bound; both send through the keyed {@code POST /api/verb}, the server mints
 * the confirm) and
 * the {@code #kept} section after {@code #bans} - the kept ships ({@code #kept-list}, a Forget per row) and the
 * exported ships not kept yet ({@code #inbox-list}, a Keep per row), headings counted by the script; the help
 * rows {@code tip-keep-custom} / {@code tip-forget-custom} carry {@link ServerOpsCore#CUSTOM_KEEP_TIP_WEB} /
 * {@link ServerOpsCore#CUSTOM_FORGET_TIP} through {@link #esc} (row {@code page-kept}).
 *
 * <p>The ships that come with the server, inside {@code #kept} after
 * {@code #inbox-list} - the heading {@code #ships-title} ({@link ServerOpsCore#SHIPS_TITLE}, counted by the script),
 * {@code #ships-list} (template {@code tpl-ship-row}: the id, then Hide {@code verb.shipHide} or Show
 * {@code verb.shipShow}; the script marks a hidden row {@code ship-hidden}, which the stylesheet strikes through and
 * dims), {@code #ships-empty} ({@link ServerOpsCore#SHIPS_EMPTY_TEXT}) and {@code #ships-last} (the last hide / show
 * verdict and any failed saves). No bar button: the rows are the controls (row {@code page-ships}).
 *
 * <p>The {@code #map-row} Battle map row in {@code #pre-start}
 * inside {@code #setup-advanced}, after Seats per address (the pre-start lock covers it: an Apply is refused
 * while a server runs) - the
 * Swing package map row's eight twins ({@code setup.map.half} / {@code apply}, {@code station} /
 * {@code stationApply}, {@code backdrop} / {@code backdropApply}, {@code deployCr} / {@code deployCrApply}),
 * served by {@code POST /api/match-settings}; the two typed fields carry {@code maxlength="16"} (the core's
 * value bound), the two pickers the tips' words. The hint {@code #hint-map} and the four help rows
 * {@code tip-map-*} are the Swing map row's texts verbatim (each tip ends " Takes effect at the next Start."); the row
 * label drops the Swing colon (the page's label idiom); {@code #map-problem} shows the prefill's problem
 * sentence (row {@code page-map-row-ids}).
 *
 * <p>Swing twins - Check from the internet
 * ({@code #btn-reach} {@code setup.reach}, label {@link ServerOpsCore#REACH_LABEL}, help entry {@code tip-reach}
 * = {@link ServerOpsCore#REACH_TIP}) in the Advertise row, served by {@code POST /api/reach}; the Recording
 * select ({@code #f-record} {@code setup.record}, {@link #recordOptions}, hint {@code #hint-record} =
 * {@link ServerOpsCore#RECORD_TIP}) after the Game folder, saved by Start as the {@code record} launcher row;
 * the {@code #reach-line} slot ({@code status.reach}) in the status strip, filled by the script from the status
 * answer's {@code reach}; the mods Remove help entry {@code tip-mods-remove} = {@link ServerOpsCore#MODS_REMOVE_TIP}
 * (row {@code page-n-ids}).
 *
 * <p>Swing twins - the shape hint =
 * {@link #SHAPE_HINT} and {@code #hint-one-password} = {@link #ONE_PASSWORD}; the
 * {@code #shape-new} block in {@code #pre-start} after the Battle map row (Name, Based on, Players [the base's seat
 * count prefilled when Based on changes; blank = that count — main's fold on the D1-b lane], Map size, Deploy CR,
 * Backdrop, Create = {@code setup.shapeNew}), served by {@code POST /api/shape-new}. The two package-only parts
 * open {@code hidden}; the script shows them when {@code /api/config} says {@code packaged} (row {@code page-n-ids}).
 * No Setup control hands a player a match file or a per-shape kit: a join window fetches the match from the
 * server at join.
 *
 * <p>Roster x ({@code #f-roster-mult},
 * {@code setup.wave.rosterMultiplier}, hint {@code #hint-roster-mult} + its help row) right after Roster DP
 * in {@code #pre-start} - the Start form's {@code match.waveRosterMultiplier} field.
 *
 * <p>Seats per address ({@code #f-seats-per-source},
 * {@code setup.net.seatsPerSource}, hint {@code #hint-seats-per-source}) right after Tick rate and All skills
 * ({@code #f-all-skills}, {@code setup.officer.allSkills}, on | off, hint {@code #hint-all-skills}) last in
 * {@code #pre-start} (both boot-time rows, so both lock while a server runs), each with its help row.
 *
 * <p>Deterministic replay pins (RNG) ({@code #f-rng-pins},
 * {@code setup.replay.rngPins}, on | off, hint {@code #hint-rng-pins} + its help row) after All skills, last in
 * {@code #pre-start} - the Start form's {@code rngpin.enabled} row (boot-time: it locks while a server runs).
 *
 * <p>The page opens dark ({@code color-scheme} meta = {@code dark}
 * alone, so no light canvas paints before {@code ops.css} lands); {@code #theme-toggle} (Light / Dark, help row
 * {@code tip-theme}) follows {@code #help-open} in {@code #nav} with no {@code data-section} and no
 * {@code aria-pressed}, so it is neither a section tab nor painted as a picked one, and its label names the
 * scheme a press switches to. The shape row's package-only Players field ({@code #players-ctl} opens
 * {@code hidden}: {@code #f-players} {@code setup.shape.players}, at most 3 digits, + its Apply
 * {@code #btn-players} {@code setup.shape.playersApply}, help row {@code tip-players}) sits inside
 * {@code #pre-start}, so the pre-start lock covers it. The Second game folder ({@code #f-game-root-second},
 * {@code setup.gameRootSecond}, hint {@code #hint-game-root-second} + its help row) follows the Game folder in
 * {@code #bind}, before Recording: the folder a debug watch opens a second player window from.
 *
 * <p>Four Setup groups fold under one {@code <details class="fold">} each,
 * closed at load: {@code #setup-debug} (Second game folder, Recording) after the Game folder in {@code #bind};
 * {@code #setup-advanced} (Tick rate, Seats per address, the Battle map row, {@code #shape-new}, the wave and
 * roster rows, All skills, the RNG pins) last in {@code #pre-start}; {@code #setup-live} (every Set live row) under
 * the {@code #live-dials} legend; {@code #setup-voice-knobs} (the ten voice knobs) between the Relay row and Apply
 * voice. Match shape and the password rows move up to just under the lock reason, so they stay in view. Why: a
 * host's first Start sees only what it needs to start a match and tell players the address and password. Each summary
 * carries a {@code setup.fold.<word>} id; a folded control is still a descendant of its fieldset, so the pre-start
 * lock covers it.
 *
 * <p>The PvP mode row right after the Match shape row in {@code #pre-start}
 * (so the pre-start lock disables it while a server runs: toggling is Kill, then Start): {@code #f-pvp}
 * {@code setup.match.pvp}, Off / On (values {@code off} / {@code on}, the Start parse's two words), hint
 * {@code #hint-pvp} = {@link #PVP_HINT} + its help row. No Apply button: Start switches the picked match file's
 * mode when the select differs from it.
 */
final class OpsConsolePageHtml {

    private OpsConsolePageHtml() {
    }

    /** HTML text / double-quoted attribute escaping for a Java constant concatenated into {@link #HTML}. */
    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** The package match-shape hint ({@code #hint-shape}) - a new shape needs nothing new from the players: every
     *  join window fetches it, and the kit stays the same; the page reads the core composer, which carries this
     *  hint. */
    // The hint comes from the ONE composer (ServerOpsCore.shapeHint(true)); Swing reads the same.
    static final String SHAPE_HINT = ServerOpsCore.shapeHint(true);
    /** The one package password law, shown under the shape selector in a package only. */
    static final String ONE_PASSWORD = "One match password for every match shape: switching keeps it.";
    /** The PvP mode hint ({@code #hint-pvp}): what the mode is, what it needs, and that a change re-makes the match
     *  (a new match id, so every join window fetches it). */
    static final String PVP_HINT = "Players fight each other in two teams, South and North. No station, and nobody"
            + " wins. Waves start paused. Fortress match, 2 or more players. Changing it makes a new match.";

    /** The {@code record=} row values, in {@link ServerOpsCore#RECORD_ITEMS} order (the launcher's grammar). */
    static final List<String> RECORD_VALUES = List.of("off", "diag", "full");

    /**
     * The Recording select's three options - value = the
     * {@link #RECORD_VALUES} row word, text = {@link ServerOpsCore#RECORD_ITEMS} verbatim (escaped); "off" first,
     * so a page with no prefill posts the launcher's default.
     */
    static String recordOptions() {
        StringBuilder sb = new StringBuilder(160);
        for (int i = 0; i < RECORD_VALUES.size(); i++) {
            sb.append("<option value=\"").append(RECORD_VALUES.get(i)).append("\">")
                    .append(esc(ServerOpsCore.RECORD_ITEMS.get(i))).append("</option>");
        }
        return sb.toString();
    }

    /** The page; {@code ops.css} and {@code ops.js} resolve relative to it. */
    static final String HTML = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer">
<meta name="color-scheme" content="dark">
<title data-cc="status.windowTitle">AlphaCoreMP - Server Ops</title>
<link rel="stylesheet" href="ops.css">
<script src="ops.js" defer></script>
</head>
<body>
<div id="no-key" hidden>
<p>This page needs the console's key. The quickest way in: double-click ops-web.bat in the server folder again (sh ops-web.sh on Linux) - while the ops GUI is running, that opens this page with its key. Or add # and the key from run/console.token (in the server folder) to the end of this address, or open the address the console printed when it started from a terminal. The key stays the same across console restarts; to make a new one, stop the console, delete that file and start the console again.</p>
</div>

<header id="strip" data-state="stopped">
<div class="strip-row strip-head">
<h1 id="headline" data-cc="status.headline">CONNECTING</h1>
<p id="asof" class="dim" hidden>as of <time id="asof-time"></time></p>
<p id="pack-line" class="dim" data-cc="status.pack" hidden></p>
</div>
<div class="strip-row">
<p id="detail" tabindex="0" data-cc="status.detail">waiting for the first status answer</p>
<button type="button" id="copy-detail" aria-label="Copy the status line">Copy</button>
</div>
<div class="strip-row">
<div id="note" role="status" aria-live="polite" data-cc="status.detail.note"></div>
<button type="button" id="note-history" aria-expanded="false" aria-controls="note-list">History</button>
</div>
<ol id="note-list" data-cc="verb.send" hidden></ol>
<p id="reach-line" data-cc="status.reach" hidden></p>
<section id="brief" hidden data-cc="status.brief" aria-labelledby="brief-title">
<h2 id="brief-title"></h2>
<ul id="brief-body" data-cc="status.brief.body"></ul>
</section>
</header>

<footer id="dock">
<div id="bar" role="group" aria-label="Server verbs" data-cc="shell.enable.bar">
<div class="cluster" id="cl-server" role="group" aria-labelledby="cap-server">
<span class="cap" id="cap-server" data-cc="bar.cluster.server">SERVER</span>
<button type="button" id="btn-start" data-cc="verb.startServer">Start server</button>
<button type="button" id="btn-watch" data-cc="verb.watch" aria-describedby="tip-watch" hidden>Launch debug WATCH</button>
"""
            + "<button type=\"button\" id=\"btn-join\" data-cc=\"verb.joinerGui\" aria-describedby=\"tip-join\" hidden>"
            + esc(OpsJoinSelf.LABEL) + "</button>\n"
            // The repository console's Dev editor, the Swing owner button's twin: hidden in
            // the source, shown by CC.packageChrome for packaged:false only; served by POST /api/dev-editor
            + "<button type=\"button\" id=\"btn-dev-editor\" data-cc=\"verb.devEditor\" hidden>Dev editor</button>\n"
            + "<p id=\"join-linux\" class=\"hint\" hidden>" + esc(OpsJoinSelf.R9) + "</p>\n"
            + """
</div>
<div class="cluster" id="cl-match" role="group" aria-labelledby="cap-match">
<span class="cap" id="cap-match" data-cc="bar.cluster.match">MATCH</span>
<button type="button" id="btn-end-round" data-cc="verb.endRound">End round</button>
<button type="button" id="btn-end-match" data-cc="verb.endMatch">End match</button>
<details class="more">
<summary>More &#9662;</summary>
<button type="button" id="btn-kill" data-cc="verb.kill" aria-describedby="tip-kill">Kill (fallback)</button>
</details>
</div>
<div class="cluster" id="cl-simspeed" role="group" aria-labelledby="cap-simspeed">
<span class="cap" id="cap-simspeed" data-cc="bar.cluster.simSpeed">SIM-SPEED</span>
<details class="more">
<summary>Sim-speed &#9662;</summary>
<span class="prefix" data-cc="bar.simSpeed.prefix">x</span>
<input id="simspeed" data-cc="verb.simSpeed.field" type="text" inputmode="decimal" value="1.0" size="4" autocomplete="off" spellcheck="false" aria-label="Sim-speed multiplier" aria-describedby="tip-simspeed">
<button type="button" id="btn-simspeed-set" data-cc="verb.simSpeedSet" aria-describedby="tip-simspeed">Set</button>
<button type="button" id="btn-auto-on" data-cc="verb.simSpeedAuto" aria-describedby="tip-auto">auto on</button>
<button type="button" id="btn-auto-off" data-cc="verb.simSpeedAuto.off" aria-describedby="tip-auto">auto off</button>
<output id="simspeed-last" class="last-sent" for="simspeed"></output>
</details>
</div>
<div class="cluster" id="cl-seat" role="group" aria-labelledby="cap-seat">
<span class="cap" id="cap-seat" data-cc="bar.cluster.seat">SEAT</span>
<span id="pick-chip" class="chip">no seat picked</span>
<span id="pick-why" class="dim" data-cc="live.seat.pick.none">Select a seat row first.</span>
<details class="more">
<summary>Seat actions &#9662;</summary>
<button type="button" id="btn-kick" data-cc="verb.kickSelected" aria-describedby="pick-why">Kick selected</button>
<button type="button" id="btn-ban-player" data-cc="verb.banPlayer" aria-describedby="pick-why tip-ban-player">Ban player</button>
<button type="button" id="btn-ban-source" data-cc="verb.banSource" aria-describedby="pick-why tip-ban-source">Ban source</button>
<button type="button" id="btn-unban-player" data-cc="verb.unbanPlayer" aria-describedby="tip-unban-player">Unban player</button>
<button type="button" id="btn-reject-seat" data-cc="verb.customReject" aria-describedby="pick-why tip-reject-seat">Reject seat</button>
<button type="button" id="btn-allow-seat" data-cc="verb.customAllow" aria-describedby="pick-why tip-allow-seat">Allow seat</button>
<input id="customId" data-cc="verb.customId" type="text" placeholder="cc-v... id" size="18" autocomplete="off" spellcheck="false" aria-label="Custom id" aria-describedby="tip-custom-id">
<button type="button" id="btn-ban-custom" data-cc="verb.customBan" aria-describedby="pick-why tip-ban-custom">Ban custom</button>
<button type="button" id="btn-export-custom" data-cc="verb.customExport" aria-describedby="pick-why tip-export-custom">Export custom</button>
<button type="button" id="btn-unban-custom" data-cc="verb.customUnban" aria-describedby="tip-unban-custom">Unban custom</button>
</details>
</div>
<div class="cluster" id="cl-customs" role="group" aria-labelledby="cap-customs">
<span class="cap" id="cap-customs" data-cc="bar.cluster.customs">CUSTOMS</span>
<details class="more">
<summary>Customs &#9662;</summary>
<label class="check"><input type="checkbox" id="customs-accept" data-cc="verb.customSubmissions" aria-describedby="tip-customs-accept"> accept customs</label>
<button type="button" id="btn-keep-custom" data-cc="verb.customKeep" aria-describedby="pick-why tip-keep-custom">Keep for everyone</button>
<button type="button" id="btn-forget-custom" data-cc="verb.customForget" aria-describedby="tip-forget-custom">Forget kept</button>
</details>
<span id="customs-summary" data-cc="verb.customs.summary">customs: -</span>
<p id="customs-detail" data-cc="verb.customs.detail"></p>
</div>
<p id="footer-note" data-cc="shell.close">Closing this tab stops nothing; the server keeps running.</p>
</div>
<div id="load" tabindex="0" role="group" aria-label="Load (capacity headroom)" data-cc="load.group">
<div class="lrow" id="util"><span class="lcap" data-cc="load.row.util.caption">Sim utilization (% of frame budget):</span><meter id="util-meter" data-cc="load.row.util.bar" min="0" max="100" value="0"></meter><output id="util-text">-</output></div>
<div class="lrow" id="workmax" data-cc="load.row.worstFrame"><span class="lcap">Worst frame:</span><output id="workmax-text">-</output></div>
<div class="lrow" id="egress"><span class="lcap"><label for="uplink">Egress vs uplink of</label> <input id="uplink" data-cc="load.uplink" type="number" min="1" step="any" value="50" aria-describedby="tip-uplink"> Mbps:</span><meter id="egress-meter" data-cc="load.row.egress.bar" min="0" max="100" value="0"></meter><output id="egress-text">-</output></div>
<div class="lrow" id="load-rates" data-cc="load.row.rates"><span class="lcap">Rates:</span><output id="load-rates-text">-</output></div>
<div class="lrow" id="load-phases" data-cc="load.row.phases"><span class="lcap">Frame ms mean/p95:</span><output id="load-phases-text">-</output></div>
<div class="lrow" id="load-totals" data-cc="load.row.totals"><span class="lcap">Match totals:</span><output id="load-totals-text">-</output></div>
</div>
</footer>

<nav id="nav" aria-label="Sections">
<button type="button" data-section="players" data-cc="tab.live" aria-pressed="true" aria-controls="sec-players">Players</button>
<button type="button" data-section="setup" data-cc="tab.setup" aria-pressed="false" aria-controls="sec-setup">Setup</button>
<button type="button" data-section="mods" data-cc="tab.mods" aria-pressed="false" aria-controls="sec-mods">Mods</button>
<button type="button" id="help-open" aria-haspopup="dialog" aria-label="Help">?</button>
<button type="button" id="theme-toggle" aria-describedby="tip-theme">Light</button>
<button type="button" id="btn-quit" aria-describedby="tip-quit">Close ops GUI</button>
</nav>

<main id="main">
<section id="sec-players" aria-label="Players">
<p id="seats-remembered" class="dim" data-cc="live.seat.rows.remembered" hidden>last match's players</p>
<div class="table-wrap">
<table id="seats" role="grid" aria-readonly="true" tabindex="0" aria-label="Seats" data-cc="live.seat.pick">
<thead data-cc="live.seat.header">
<tr>
<th scope="col" class="num" data-cc="live.seat.col.seat">seat</th>
<th scope="col" data-cc="live.seat.col.name">name</th>
<th scope="col" data-cc="live.seat.col.client">client</th>
<th scope="col" data-cc="live.seat.col.ship">ship</th>
<th scope="col" data-cc="live.seat.col.endpoint">endpoint</th>
<th scope="col" data-cc="live.seat.col.live">live</th>
<th scope="col" class="num" data-cc="live.seat.col.txRate">tx/s</th>
<th scope="col" class="num" data-cc="live.seat.col.rxRate">rx/s</th>
<th scope="col" class="num" data-cc="live.seat.col.txTotal">tx total</th>
<th scope="col" class="num" data-cc="live.seat.col.rxTotal">rx total</th>
<th scope="col" class="num" data-cc="live.seat.col.ctrl">ctrl</th>
<th scope="col" data-cc="live.seat.col.custom">custom</th>
<th scope="col" data-cc="live.seat.col.customs">customs</th>
</tr>
</thead>
<tbody id="seats-body" data-cc="live.seats.paint"></tbody>
</table>
</div>
<template id="tpl-seat-row"><tr role="row" aria-selected="false"><td role="gridcell" class="num" data-col="seat"></td><td role="gridcell" data-col="name"></td><td role="gridcell" data-col="client"></td><td role="gridcell" data-col="ship"></td><td role="gridcell" data-col="endpoint"></td><td role="gridcell" data-col="live"></td><td role="gridcell" class="num" data-col="tx/s"></td><td role="gridcell" class="num" data-col="rx/s"></td><td role="gridcell" class="num" data-col="tx total"></td><td role="gridcell" class="num" data-col="rx total"></td><td role="gridcell" class="num" data-col="ctrl"></td><td role="gridcell" data-col="custom"></td><td role="gridcell" data-col="customs"></td></tr></template>
<p id="seats-empty" class="empty" data-cc="live.seat.empty">no seats - Start server begins a new match</p>
<section id="bans" aria-labelledby="bans-title">
<h2 id="bans-title">Bans (<span id="bans-count">0</span>)</h2>
<div class="ctl">
<label for="ban-ip-key">Ban address or range</label>
<input id="ban-ip-key" type="text" maxlength="60" placeholder="1.2.3.4 or 1.2.3.0/24" autocomplete="off" spellcheck="false">
<button type="button" id="btn-ban-ip" data-cc="verb.banIp" aria-describedby="tip-ban-ip">Ban address</button>
</div>
<ul id="bans-list" data-cc="dialog.unban.pick"></ul>
<p id="bans-empty" class="dim" data-cc="dialog.unban.empty" hidden>no bans in the last snapshot</p>
<p id="bans-more" class="dim" hidden></p>
</section>
<section id="kept" aria-labelledby="kept-title">
<h2 id="kept-title">Kept for everyone (0)</h2>
<ul id="kept-list"></ul>
<p id="kept-empty" class="dim">No kept ships yet.</p>
<h2 id="inbox-title">Exported, not kept yet (0)</h2>
<ul id="inbox-list"></ul>
<h2 id="ships-title">Ships that come with the server (0)</h2>
<ul id="ships-list"></ul>
<p id="ships-empty" class="dim">Start the server to see its ships.</p>
<p id="ships-last" hidden></p>
</section>
<template id="tpl-kept-row"><li><span class="kept-label"></span> <button type="button" class="forget">Forget</button></li></template>
<template id="tpl-inbox-row"><li><span class="inbox-label"></span> <button type="button" class="keep">Keep</button></li></template>
<template id="tpl-ship-row"><li><span class="ship-label"></span> <button type="button" class="ship-hide" data-cc="verb.shipHide">Hide</button><button type="button" class="ship-show" data-cc="verb.shipShow" hidden>Show</button></li></template>
<template id="tpl-ban-row"><li><span class="ban-label"></span> <button type="button" class="unban">Unban</button></li></template>
</section>

<section id="sec-setup" hidden aria-label="Setup">
<form id="setup-form" data-cc="setup.form.startFields" novalidate>
<fieldset id="bind">
<legend>Network</legend>
<div class="frow">
<label for="f-bind-host">Listen on host</label>
<div class="ctl"><input id="f-bind-host" data-cc="setup.bindHost" type="text" size="15" autocomplete="off" spellcheck="false" aria-describedby="hint-bind"><label for="f-bind-port" class="inner">port</label><input id="f-bind-port" data-cc="setup.bindPort" type="text" inputmode="numeric" size="6" autocomplete="off" aria-describedby="hint-bind"><button type="button" id="btn-lease" data-cc="setup.lease" aria-describedby="tip-lease">Fill from ProtonVPN</button></div>
<p class="hint" id="hint-bind" data-cc="setup.bind.hint">where the server listens: this host's LAN / forwarded address (127.0.0.1 = this PC)</p>
</div>
<div class="frow">
<label for="f-advertise">Players connect to</label>
"""
            + "<div class=\"ctl\"><input id=\"f-advertise\" data-cc=\"setup.advertise\" type=\"text\" size=\"24\""
            + " autocomplete=\"off\" spellcheck=\"false\" aria-describedby=\"hint-advertise\"><button type=\"button\""
            + " id=\"copy-advertise\">Copy</button><button type=\"button\" id=\"btn-reach\" data-cc=\"setup.reach\""
            + " aria-describedby=\"tip-reach\">" + esc(ServerOpsCore.REACH_LABEL) + "</button></div>\n"
            + """
<p class="hint" id="hint-advertise" data-cc="setup.advertise.hint">what joiners dial: type your public address + forwarded port</p>
</div>
<div class="frow">
<label for="f-game-root">Game folder</label>
<div class="ctl"><input id="f-game-root" data-cc="setup.gameRoot" type="text" size="40" autocomplete="off" spellcheck="false" aria-describedby="hint-game-root"></div>
<p class="hint" id="hint-game-root" data-cc="setup.gameRoot.hint">your Starsector install folder - saved at Start</p>
</div>
<details id="setup-debug" class="fold">
<summary data-cc="setup.fold.debug">Debug &#9662;</summary>
<div class="frow">
<label for="f-game-root-second">Second game folder</label>
<div class="ctl"><input id="f-game-root-second" data-cc="setup.gameRootSecond" type="text" size="40" autocomplete="off" spellcheck="false" aria-describedby="hint-game-root-second"></div>
<p class="hint" id="hint-game-root-second">a second Starsector install folder: Launch debug WATCH opens a second player window from it (blank = one window) - saved at Start</p>
</div>
<div class="frow">
<label for="f-record">Recording</label>
"""
            + "<div class=\"ctl\"><select id=\"f-record\" data-cc=\"setup.record\" aria-describedby=\"hint-record\">"
            + recordOptions() + "</select></div>\n"
            + "<p class=\"hint\" id=\"hint-record\">" + esc(ServerOpsCore.RECORD_TIP) + "</p>\n"
            + """
</div>
</details>
<div class="frow">
<label for="f-brick">Join brick</label>
<div class="ctl"><output id="f-brick" data-cc="setup.brick.text">-</output><button type="button" id="btn-brick" data-cc="setup.brick" aria-describedby="tip-brick">Copy join brick</button></div>
</div>
</fieldset>

<fieldset id="pre-start" data-cc="shell.enable.preStart">
<legend data-cc="setup.form.title">Before start</legend>
<p class="lock-reason" hidden>Locked while a server runs.</p>
<div class="frow">
<label for="f-shape">Match shape</label>
<div class="ctl"><select id="f-shape" data-cc="setup.shape" aria-describedby="hint-shape"></select><span id="players-ctl" hidden><label for="f-players" class="inner">players</label><input id="f-players" data-cc="setup.shape.players" type="text" inputmode="numeric" size="4" maxlength="3" autocomplete="off" aria-describedby="tip-players"><button type="button" id="btn-players" data-cc="setup.shape.playersApply" aria-label="Apply the player count" aria-describedby="tip-players">Apply</button></span></div>
"""
            + "<p class=\"hint\" id=\"hint-shape\" data-cc=\"setup.shape.hint\">" + esc(SHAPE_HINT) + "</p>\n"
            + "<p class=\"hint\" id=\"hint-one-password\" hidden>" + esc(ONE_PASSWORD) + "</p>\n"
            + """
</div>
<div class="frow">
<label for="f-pvp">PvP mode</label>
<div class="ctl"><select id="f-pvp" data-cc="setup.match.pvp" aria-describedby="hint-pvp"><option value="off" selected>Off</option><option value="on">On</option></select></div>
"""
            + "<p class=\"hint\" id=\"hint-pvp\">" + esc(PVP_HINT) + "</p>\n"
            + """
</div>
<div class="frow">
<label for="f-password">Match password</label>
<div class="ctl"><input id="f-password" data-cc="setup.password" type="password" placeholder="saved - type to replace" autocomplete="new-password" spellcheck="false" size="18" aria-describedby="hint-admission"><button type="button" class="show" aria-controls="f-password" aria-pressed="false">Show</button></div>
</div>
<div class="frow">
<label for="f-admin">Admin password</label>
<div class="ctl"><input id="f-admin" data-cc="setup.adminPassword" type="password" placeholder="saved - type to replace" autocomplete="new-password" spellcheck="false" size="18" aria-describedby="hint-admission"><button type="button" class="show" aria-controls="f-admin" aria-pressed="false">Show</button><label class="check"><input type="checkbox" id="admin-remove"> Remove the admin password</label></div>
</div>
<div class="frow">
<span class="lab"></span>
<div class="ctl"><button type="button" id="btn-reveal" data-cc="setup.password.reveal" aria-describedby="tip-reveal">Show saved passwords</button></div>
</div>
<div class="frow frow-hint">
<p class="hint" id="hint-admission" data-cc="setup.admission.hint">writes <span id="sidecar-name"></span> (+ .adminpassword); match 8 to 64, admin 12 to 64, never a common word; match password = what players type to join, admin password = waves + /kick (must differ); a match password is required; blank admin = none</p>
<p class="hint" id="hint-admission-inline">writes <span class="sidecar-name"></span>; a match password is required; blank admin = none</p>
</div>
<details id="setup-advanced" class="fold">
<summary data-cc="setup.fold.advanced">Advanced &#9662;</summary>
<div class="frow">
<label for="f-tick">Tick rate (Hz)</label>
<div class="ctl"><input id="f-tick" data-cc="setup.tickRate" type="text" inputmode="numeric" size="6" autocomplete="off" aria-describedby="hint-tick"></div>
<p class="hint" id="hint-tick">writes sim.tickRate; 60 = default (blank = 60, range 10 to 600); joiners adopt another rate when they join (no new kit needed)</p>
</div>
<div class="frow">
<label for="f-seats-per-source">Seats per address</label>
<div class="ctl"><input id="f-seats-per-source" data-cc="setup.net.seatsPerSource" type="text" inputmode="numeric" size="4" maxlength="16" autocomplete="off" aria-describedby="hint-seats-per-source"></div>
<p class="hint" id="hint-seats-per-source">writes moderation.maxSeatsPerSource; 0 = no limit; N = at most N seats per internet address (0 to 255) - a household behind one address counts as one; this computer is never limited by its own address (a tunnel gateway's address counts unless listed in moderation.sourceCapExempt)</p>
</div>
<div class="frow" id="map-row">
<span class="lab">Battle map</span>
<div class="ctl"><label for="f-map-half" class="inner">half size</label><input id="f-map-half" data-cc="setup.map.half" type="text" inputmode="numeric" size="6" maxlength="16" autocomplete="off" aria-describedby="hint-map tip-map-half"><button type="button" id="btn-map-half" data-cc="setup.map.apply" aria-label="Apply the half size" aria-describedby="tip-map-half">Apply</button><label for="f-map-station" class="inner">station</label><select id="f-map-station" data-cc="setup.map.station" aria-describedby="hint-map tip-map-station"><option value="-">-</option><option>lowtech</option><option>midline</option><option>hightech</option></select><button type="button" id="btn-map-station" data-cc="setup.map.stationApply" aria-label="Apply the station" aria-describedby="tip-map-station">Apply</button><label for="f-map-backdrop" class="inner">background</label><select id="f-map-backdrop" data-cc="setup.map.backdrop" aria-describedby="hint-map tip-map-backdrop"><option>none</option><option>open</option><option>nebula</option><option>nebula_blue</option><option>hyper</option></select><button type="button" id="btn-map-backdrop" data-cc="setup.map.backdropApply" aria-label="Apply the background" aria-describedby="tip-map-backdrop">Apply</button><label for="f-map-cr" class="inner">deploy CR %</label><input id="f-map-cr" data-cc="setup.map.deployCr" type="text" inputmode="numeric" size="4" maxlength="16" autocomplete="off" aria-describedby="hint-map tip-map-cr"><button type="button" id="btn-map-cr" data-cc="setup.map.deployCrApply" aria-label="Apply the deploy CR" aria-describedby="tip-map-cr">Apply</button><output id="map-problem" class="dim" hidden></output></div>
<p class="hint" id="hint-map">Apply, then Start: every join window fetches the new match</p>
</div>
<details id="shape-new" hidden>
<summary>New match shape...</summary>
<div class="frow">
<label for="f-new-name">Name</label>
<div class="ctl"><input id="f-new-name" type="text" size="20" maxlength="32" autocomplete="off" spellcheck="false" aria-describedby="hint-shape-new"></div>
</div>
<div class="frow">
<label for="f-new-base">Based on</label>
<div class="ctl"><select id="f-new-base"></select></div>
</div>
<div class="frow">
<label for="f-new-players">Players</label>
<div class="ctl"><input id="f-new-players" type="text" inputmode="numeric" size="4" maxlength="16" autocomplete="off" aria-describedby="hint-shape-new"></div>
</div>
<div class="frow">
<label for="f-new-half">Map size</label>
<div class="ctl"><input id="f-new-half" type="text" inputmode="numeric" size="6" maxlength="16" autocomplete="off" value="9000"></div>
</div>
<div class="frow">
<label for="f-new-cr">Deploy CR</label>
<div class="ctl"><input id="f-new-cr" type="text" inputmode="numeric" size="4" maxlength="16" autocomplete="off" value="70"></div>
</div>
<div class="frow">
<label for="f-new-backdrop">Backdrop</label>
<div class="ctl"><select id="f-new-backdrop"><option>none</option><option>open</option><option>nebula</option><option>nebula_blue</option><option>hyper</option></select></div>
</div>
<div class="frow">
<span class="lab"></span>
<div class="ctl"><button type="button" id="btn-shape-new" data-cc="setup.shapeNew" aria-describedby="hint-shape-new">Create</button></div>
<p class="hint" id="hint-shape-new">a new match shape file beside the others: names use a-z, 0-9 and - (1 to 32); map size = the half width, 2000 to 60000; deploy CR 1 to 100 %; players 1 to 255 (blank = the base's own count)</p>
</div>
</details>
<div class="frow">
<label for="f-fortress-dp">Total wave DP</label>
<div class="ctl"><input id="f-fortress-dp" data-cc="setup.fortress.dp" type="text" inputmode="numeric" size="6" autocomplete="off" aria-describedby="hint-fortress"></div>
<p class="hint" id="hint-fortress" data-cc="setup.fortress.hint">writes match.fortressDp; 0 = the authored wave chain; above 0 = the total wave DP, 170 to 5000 (fortress shape only; the authored total is about 385; below about 170 the server refuses)</p>
</div>
<div class="frow">
<label for="f-wave-scale">Per-wave scale</label>
<div class="ctl"><input id="f-wave-scale" data-cc="setup.wave.scale" type="text" size="24" autocomplete="off" spellcheck="false" aria-describedby="hint-wave-scale"></div>
<p class="hint" id="hint-wave-scale" data-cc="setup.wave.hint">writes match.fortressWaveScale; 7 comma-separated factors 0.5..4.0 scaling each wave's share of the total wave DP (blank = x1; needs Total wave DP above 0)</p>
</div>
<div class="frow">
<label for="f-roster-dp">Roster DP</label>
<div class="ctl"><input id="f-roster-dp" data-cc="setup.wave.rosterDp" type="text" inputmode="numeric" size="6" autocomplete="off" aria-describedby="hint-roster-dp"></div>
<p class="hint" id="hint-roster-dp">writes match.waveRosterFullDp; 0 = static waves; N = the connected players' total ship DP at which waves reach full size (waves shrink below it, floor 25%); needs Total wave DP above 0</p>
</div>
<div class="frow">
<label for="f-roster-mult">Roster x</label>
<div class="ctl"><input id="f-roster-mult" data-cc="setup.wave.rosterMultiplier" type="text" inputmode="decimal" size="4" autocomplete="off" aria-describedby="hint-roster-mult"></div>
<p class="hint" id="hint-roster-mult">writes match.waveRosterMultiplier; multiplies the players' share of each wave: 1 = as computed, 2 = twice the share; 0.1 to 10; needs Roster DP above 0</p>
</div>
<div class="frow">
<label for="f-all-skills">All skills</label>
<div class="ctl"><select id="f-all-skills" data-cc="setup.officer.allSkills" aria-describedby="hint-all-skills"><option selected>on</option><option>off</option></select></div>
<p class="hint" id="hint-all-skills">writes server.officerSkillsAll; on = every player's ship gets a captain with every combat skill</p>
</div>
<div class="frow">
<label for="f-rng-pins">Deterministic replay pins (RNG)</label>
<div class="ctl"><select id="f-rng-pins" data-cc="setup.replay.rngPins" aria-describedby="hint-rng-pins"><option selected>on</option><option>off</option></select></div>
<p class="hint" id="hint-rng-pins">writes rngpin.enabled; on = random numbers are pinned to a fresh seed each serve, so a recorded match can be replayed and re-simulated; off = no pins (that serve's recording cannot be re-simulated)</p>
</div>
</details>
</fieldset>

<fieldset id="live-dials" data-cc="shell.enable.setLive">
<legend>Also changeable while running</legend>
<details id="setup-live" class="fold">
<summary data-cc="setup.fold.live">Match tuning &#9662;</summary>
<div class="frow">
<label for="f-wave-category">Wave faction</label>
<div class="ctl"><select id="f-wave-category" data-cc="setup.wave.category" aria-describedby="hint-wave-category"><option selected>remnant</option><option>derelict</option><option>omega</option><option>threat</option><option>shrouded</option><option>hegemony</option><option>persean_league</option><option>tritachyon</option><option>luddic_church</option><option>luddic_path</option><option>sindrian_diktat</option><option>pirates</option><option>ziggurat</option></select><button type="button" id="btn-category-live" data-cc="setup.wave.categoryLive">Set live</button><output id="last-category" class="last-sent" for="f-wave-category"></output></div>
<p class="hint" id="hint-wave-category">writes match.waveCategory; remnant = the standing chain; Set live swaps a running server at its next round boundary (fortress shape only)</p>
</div>
<div class="frow">
<label for="f-joiner-default">Default ship for joiners</label>
<div class="ctl"><input id="f-joiner-default" data-cc="setup.joiner.default" type="text" size="20" autocomplete="off" spellcheck="false" aria-describedby="hint-joiner"><button type="button" id="btn-joiner-live" data-cc="setup.joiner.defaultLive">Set live</button><output id="last-joiner" class="last-sent" for="f-joiner-default"></output></div>
<p class="hint" id="hint-joiner" data-cc="setup.joiner.hint">Start writes match.joinerDefaultVariant, Set live sets it on the running server; blank = the match's roster default; an id players cannot pick is refused in the server log (not here)</p>
</div>
<div class="frow">
<label for="f-endless">Endless</label>
<div class="ctl"><select id="f-endless" data-cc="setup.joiner.endless" aria-describedby="hint-endless"><option selected>on</option><option>off</option></select><button type="button" id="btn-endless-live" data-cc="setup.joiner.endlessLive">Set live</button><output id="last-endless" class="last-sent" for="f-endless"></output></div>
<p class="hint" id="hint-endless">on = the wave chain wraps after its last wave clears (Start writes match.endless=true|false; absent = on, the server's default); a running server may refuse Set live while its per-clear reset is on</p>
</div>
<div class="frow">
<label for="f-terrain">Terrain</label>
<div class="ctl"><select id="f-terrain" data-cc="setup.fortress.terrain" aria-describedby="hint-terrain"><option selected>none</option><option>corona</option><option>pulsar</option><option>abyss</option></select><label for="f-terrain-level" class="inner">strength %</label><select id="f-terrain-level" data-cc="setup.fortress.terrainLevel" aria-describedby="hint-terrain"><option>0</option><option>25</option><option>50</option><option>75</option><option selected>100</option></select><button type="button" id="btn-terrain-live" data-cc="setup.fortress.terrainLive">Set live</button><output id="last-terrain" class="last-sent" for="f-terrain f-terrain-level"></output></div>
<p class="hint" id="hint-terrain">battle terrain (corona / pulsar = peak-CR + CR-loss multipliers, abyss = speed + missile multipliers) at a strength in percent (100 = the full vanilla effect); Start writes match.terrain + match.terrainLevel (none = rows removed); Set live re-applies every live ship's stats once</p>
</div>
<div class="frow">
<label for="f-asteroids">Asteroids</label>
<div class="ctl"><select id="f-asteroids" data-cc="setup.fortress.asteroids" aria-describedby="hint-asteroids"><option selected>none</option><option>20</option><option>100</option><option>400</option></select><button type="button" id="btn-asteroids-live" data-cc="setup.fortress.asteroidsLive">Set live</button><output id="last-asteroids" class="last-sent" for="f-asteroids"></output></div>
<p class="hint" id="hint-asteroids">asteroid field size (none / 20 / 100 / 400); Start writes match.asteroids (none = row removed); Set live changes the field on a running server</p>
</div>
</details>
</fieldset>

<fieldset id="voice">
<legend id="voice-anchor" data-cc="voice.group">Voice chat relay</legend>
<p class="hint">Apply voice saves these settings for the next start and retunes a running server.</p>
<div class="frow">
<span class="lab">Relay</span>
<div class="ctl"><label class="check"><input type="checkbox" id="v-enabled" data-cc="voice.enabled" checked> Enabled</label><label class="check"><input type="checkbox" id="v-fec" data-cc="voice.fec" checked aria-describedby="hint-v-flags"> FEC</label></div>
<p class="hint" id="hint-v-flags">FEC = forward error correction: more bytes, fewer dropouts</p>
</div>
<details id="setup-voice-knobs" class="fold">
<summary data-cc="setup.fold.voice">Voice tuning &#9662;</summary>
<div class="frow">
<label for="v-bitrate">Bitrate (kbps)</label>
<div class="ctl"><input id="v-bitrate" data-cc="voice.bitrateKbps" type="number" min="6" max="510" step="1" value="48" aria-describedby="hint-v-bitrate"></div>
<p class="hint" id="hint-v-bitrate">Opus kbps per talker - higher = clearer, more bandwidth</p>
</div>
<div class="frow">
<label for="v-frame">Frame (ms)</label>
<div class="ctl"><select id="v-frame" data-cc="voice.frameMs" aria-describedby="hint-v-frame"><option selected>10</option><option>20</option><option>40</option><option>60</option></select></div>
<p class="hint" id="hint-v-frame">audio per packet - shorter = less delay, more packets</p>
</div>
<div class="frow">
<label for="v-complexity">Complexity</label>
<div class="ctl"><input id="v-complexity" data-cc="voice.complexity" type="number" min="0" max="10" step="1" value="5" aria-describedby="hint-v-complexity"></div>
<p class="hint" id="hint-v-complexity">Opus encoder effort 0..10 - higher = better at the same kbps</p>
</div>
<div class="frow">
<label for="v-max-talkers">Max talkers</label>
<div class="ctl"><input id="v-max-talkers" data-cc="voice.maxTalkers" type="number" min="1" max="8" step="1" value="6" aria-describedby="hint-v-max-talkers"></div>
<p class="hint" id="hint-v-max-talkers">talkers mixed per listener - each one adds bandwidth</p>
</div>
<div class="frow">
<label for="v-prox-full">Proximity full (su)</label>
<div class="ctl"><input id="v-prox-full" data-cc="voice.proxFullSu" type="number" min="1" max="65535" step="1" value="600" aria-describedby="hint-v-prox-full"></div>
<p class="hint" id="hint-v-prox-full">full volume within this range (must be below the cull range)</p>
</div>
<div class="frow">
<label for="v-prox-cull">Proximity cull (su)</label>
<div class="ctl"><input id="v-prox-cull" data-cc="voice.proxCullSu" type="number" min="1" max="65535" step="1" value="2500" aria-describedby="hint-v-prox-cull"></div>
<p class="hint" id="hint-v-prox-cull">silent beyond this range</p>
</div>
<div class="frow">
<label for="v-relay-tick">Relay tick (ms)</label>
<div class="ctl"><input id="v-relay-tick" data-cc="voice.relayTickMs" type="number" min="0" max="60" step="1" value="2" aria-describedby="hint-v-relay-tick"></div>
<p class="hint" id="hint-v-relay-tick">relay bundler wake period - 0 = half a frame</p>
</div>
<div class="frow">
<label for="v-jitter-min">Jitter floor (frames)</label>
<div class="ctl"><input id="v-jitter-min" data-cc="voice.jitterMinFrames" type="number" min="1" max="4" step="1" value="2" aria-describedby="hint-v-jitter-min"></div>
<p class="hint" id="hint-v-jitter-min">frames held before playout - 1 = most aggressive (lowest 1, highest 4, default 2)</p>
</div>
<div class="frow">
<label for="v-jitter-max">Jitter ceiling (ms)</label>
<div class="ctl"><input id="v-jitter-max" data-cc="voice.jitterMaxMs" type="number" min="20" max="160" step="10" value="80" aria-describedby="hint-v-jitter-max"></div>
<p class="hint" id="hint-v-jitter-max">buffer depth cap - at least floor x frame ms (lowest 20, highest 160, default 80)</p>
</div>
<div class="frow">
<label for="v-jitter-shrink">Jitter relax (ms clean)</label>
<div class="ctl"><input id="v-jitter-shrink" data-cc="voice.jitterShrinkMs" type="number" min="200" max="20000" step="100" value="2000" aria-describedby="hint-v-jitter-shrink"></div>
<p class="hint" id="hint-v-jitter-shrink">clean audio before the buffer shrinks one frame (lowest 200, highest 20000, default 2000)</p>
</div>
</details>
<div class="frow">
<span class="lab"></span>
<div class="ctl"><button type="button" id="v-apply" data-cc="voice.apply">Apply voice</button><output id="voice-bandwidth" data-cc="voice.bandwidth" aria-describedby="hint-v-bandwidth">-</output></div>
<p class="hint" id="hint-v-bandwidth">what the settings above cost: per listener at the max talkers, and the server's egress at 32 players</p>
</div>
<p id="voice-note-refused" class="note-err" data-cc="voice.note.refused" hidden></p>
<p id="voice-note-fail" class="note-err" data-cc="voice.note.readFail" hidden></p>
<p id="voice-note-sent" class="voice-note" data-cc="voice.note.sent" hidden></p>
</fieldset>
</form>
</section>

<section id="sec-mods" hidden aria-labelledby="mods-title">
<h2 id="mods-title">Mods for the next start</h2>
<p id="mods-explainer" data-cc="mods.explainer">required = the serve loads it and every joiner must run it.  optional = joiners may run it (presentation only; the serve never loads it).  off = not used.  cc_client is always on and never listed.</p>
<h3 id="mods-roots-title">Folders searched</h3>
<ul id="mods-roots" data-cc="mods.roots.list" aria-labelledby="mods-roots-title" aria-describedby="hint-mods-roots"></ul>
<p class="hint" id="hint-mods-roots">the folders searched for mods (the mods.roots row); none named = the serve-mods folder of this package: extract a mod there, set it to required, press Start</p>
<template id="tpl-root-row"><li><span class="root-path"></span></li></template>
<div class="ctl">
<label for="mods-add-path" class="vh">Mod folder to add</label>
<input id="mods-add-path" type="text" placeholder="absolute folder path" size="48" autocomplete="off" spellcheck="false" aria-describedby="hint-mods-add">
<button type="button" id="mods-add" data-cc="mods.roots.add">Add folder</button>
<button type="button" id="mods-rescan" data-cc="mods.rescan">Rescan</button>
</div>
<p class="hint" id="hint-mods-add">a mod's own folder (it holds mod_info.json) or a folder that contains mods; Add folder or Enter adds it to the searched list (mods.roots)</p>
<div class="table-wrap">
<table id="mods-table" data-cc="mods.table" aria-labelledby="mods-title">
<thead>
<tr>
<th scope="col" data-cc="mods.col.mod">Mod</th>
<th scope="col" data-cc="mods.col.id">Id</th>
<th scope="col" data-cc="mods.col.version">Version</th>
<th scope="col" data-cc="mods.col.use">Use</th>
<th scope="col" data-cc="mods.col.folder">Folder</th>
<th scope="col"><span class="vh">Remove</span></th>
</tr>
</thead>
<tbody id="mods-body"></tbody>
</table>
</div>
<template id="tpl-mod-row"><tr><td data-col="Mod"></td><td data-col="Id"></td><td data-col="Version"></td><td data-col="Use"><select class="use" aria-label="Use"><option>off</option><option>required</option><option>optional</option></select></td><td data-col="Folder"></td><td><button type="button" class="remove" data-cc="mods.remove">Remove</button></td></tr></template>
<p id="mods-status" data-cc="mods.status"></p>
<p id="mods-write-fail" class="note-err" data-cc="mods.note.writeFail" hidden></p>
</section>
</main>

<dialog id="dlg-confirm" aria-labelledby="confirm-title" data-cc="shell.ui.adapter">
<form method="dialog">
<h2 id="confirm-title">Confirm</h2>
<p id="confirm-text"></p>
<div class="actions"><button value="cancel" id="confirm-cancel">Cancel</button><button value="ok" id="confirm-ok">OK</button></div>
</form>
</dialog>

<dialog id="dlg-error" aria-labelledby="error-title" data-cc="dialog.copyableError">
<form method="dialog">
<h2 id="error-title">Error</h2>
<pre id="error-text" tabindex="0"></pre>
<div class="actions"><button type="button" id="error-copy">Copy</button><button value="ok" id="error-ok">OK</button></div>
</form>
</dialog>

<dialog id="dlg-unban-custom" aria-labelledby="unban-custom-title" data-cc="dialog.customUnban.input">
<form method="dialog">
<h2 id="unban-custom-title">Unban custom</h2>
<p><label for="unban-custom-id">Custom id to un-ban (cc-v...):</label></p>
<input id="unban-custom-id" type="text" size="32" autocomplete="off" spellcheck="false">
<div class="actions"><button type="button" id="unban-custom-cancel">Cancel</button><button value="ok" id="unban-custom-ok">Unban</button></div>
</form>
</dialog>

<dialog id="dlg-palette" aria-labelledby="palette-title">
<h2 id="palette-title" class="vh">Commands</h2>
<input id="palette-input" type="text" role="combobox" aria-controls="palette-list" aria-expanded="true" aria-autocomplete="list" placeholder="type a verb or a section" autocomplete="off" spellcheck="false">
<ul id="palette-list" role="listbox" aria-label="Matching commands"></ul>
</dialog>

<dialog id="dlg-help" aria-labelledby="help-title">
<form method="dialog">
<h2 id="help-title">Help</h2>
<h3>Keyboard</h3>
<dl class="keys">
<dt><kbd>Ctrl+K</kbd> or <kbd>/</kbd></dt><dd>the command palette: every verb and section by name (a verb still asks its confirm)</dd>
<dt><kbd>1</kbd> <kbd>2</kbd> <kbd>3</kbd></dt><dd>Players, Setup, Mods</dd>
<dt><kbd>?</kbd></dt><dd>this help</dd>
<dt><kbd>Up</kbd> <kbd>Down</kbd> <kbd>Home</kbd> <kbd>End</kbd></dt><dd>in the seat table: move the picked seat</dd>
<dt><kbd>Esc</kbd></dt><dd>clear the picked seat; in a dialog: close it without sending anything</dd>
<dt><kbd>Enter</kbd></dt><dd>inside a field: that field's own action (Set, Set live, Add folder)</dd>
</dl>
<p>Keys do nothing while you type in a field, and no single key runs a destructive verb.</p>
<h3>Opening this page</h3>
<p id="help-token">This page needs the console's key. The quickest way in: double-click ops-web.bat again (sh ops-web.sh on Linux) - while the ops GUI is running, that opens this page with its key. The key is in run/console.token in the server folder, and the address the console prints when started from a terminal ends in it (on Windows that start also opens the address in your browser). The key stays the same across console restarts, so a saved address keeps working; to make a new key, stop the console, delete that file and start the console again - every open tab then needs the new address. The key stays with this tab only: a reload keeps it, a new tab needs the address with # and the key again. A console started without a terminal prints no address: open the console port on this computer (7717 unless changed), then add # and the key from run/console.token to the end of the address.</p>
<p id="help-ssh">On a remote server, open a tunnel from your own computer first: <code>ssh -L 7717:127.0.0.1:7717 &lt;host&gt;</code> (7717 is the default console port; use the port the console printed), then open the printed address in your own browser.</p>
<p id="help-zoom" data-cc="status.zoomHint">Zoom is your browser's own: Ctrl + and Ctrl - scale the page, Ctrl 0 resets it.</p>
<h3>Every control</h3>
<dl class="tips">
<dt>Close ops GUI</dt><dd id="tip-quit">ends the ops GUI program itself, after a confirm (this page then reads OPS GUI CLOSED); a server it started keeps running. To open the ops GUI again, double-click ops-web.bat (sh ops-web.sh on Linux): it opens this page with the key</dd>
<dt>Kill (fallback)</dt><dd id="tip-kill">Stop the server: writes the launcher's stop file first, then ends the server process (the launcher last, only without a stop receipt) - prefer End match (clean goodbye fan)</dd>
<dt>Sim-speed and Set</dt><dd id="tip-simspeed">Sim-speed multiplier in [0.25, 2.0]; Set sends it, and a manual Set turns the auto governor off</dd>
<dt>auto on / auto off</dt><dd id="tip-auto">send the sim-speed auto governor on or off; the server reports no governor state, so the bar shows what was last sent</dd>
<dt>accept customs</dt><dd id="tip-customs-accept">turns custom submissions on or off; the box follows the server's status, and the summary beside it is the server's own count</dd>
<dt>Kick selected</dt><dd>disconnects the picked seat's player</dd>
<dt>Ban player</dt><dd id="tip-ban-player">bans the picked seat's player: kicked now, and both their client id and the address they connected from are refused at the door on every later join (the address part follows moderation.chatSourceHold in cc-server.properties, on as shipped; everyone behind a shared address is refused with them); the ban list beside the server config (cc-server.bans) keeps it across server starts</dd>
<dt>Ban source</dt><dd id="tip-ban-source">bans the picked seat's source address - every client behind it (CGNAT / shared LAN) is kicked and refused too</dd>
<dt>Ban address</dt><dd id="tip-ban-ip">bans a typed address or a whole range: 1.2.3.4, 1.2.3.0/24 (/8 to /31), or for IPv6 a key copied from the Bans list or a provider range like 2a0b:f4c0::/32 - everyone connecting from it is kicked now and refused at the door; the ban list beside the server config (cc-server.bans) keeps it across server starts; lift it with that row's Unban</dd>
<dt>Unban player</dt><dd id="tip-unban-player">lifts one ban picked from the server's ban list (the Bans list under the seat table has one Unban per row)</dd>
<dt>Reject seat</dt><dd id="tip-reject-seat">blocks the picked seat's player from submitting custom ships for the rest of this serve and bans every custom they hold (their pending ones are dropped); Allow seat is its inverse</dd>
<dt>Allow seat</dt><dd id="tip-allow-seat">the inverse of a custom reject on the picked seat: its player may submit customs again and their banned customs come back</dd>
<dt>Custom id</dt><dd id="tip-custom-id">the id Ban custom, Export custom and Keep for everyone act on; picking a seat row fills it with that seat's flying custom, and the customs column lists every accepted id a seat holds - type one to ban, export or keep a held custom nobody is flying (blank = the picked seat's flying custom)</dd>
<dt>Ban custom</dt><dd id="tip-ban-custom">bans the custom id in the box (no seat may pick it again; a seat flying it keeps it until its next respawn)</dd>
"""
            + "<dt>Export custom</dt><dd id=\"tip-export-custom\">" + esc(ServerOpsCore.customExportTipWeb())
            + "</dd>\n"
            + """
<dt>Unban custom</dt><dd id="tip-unban-custom">the inverse of Ban custom for one minted cc-v... id (typed; the server log answers an unknown id)</dd>
"""
            + "<dt>" + esc(ServerOpsCore.CUSTOM_KEEP_LABEL) + "</dt><dd id=\"tip-keep-custom\">"
            + esc(ServerOpsCore.CUSTOM_KEEP_TIP_WEB) + "</dd>\n"
            + "<dt>" + esc(ServerOpsCore.CUSTOM_FORGET_LABEL) + "</dt><dd id=\"tip-forget-custom\">"
            + esc(ServerOpsCore.CUSTOM_FORGET_TIP) + "</dd>\n"
            + """
<dt>Egress vs uplink</dt><dd id="tip-uplink">your upload capacity in Mbps; the egress bar shows the server's send rate as a share of it</dd>
<dt>Worst frame</dt><dd>the slowest frame this window + the number of frames over budget</dd>
<dt>Light / Dark</dt><dd id="tip-theme">switches this page between dark and light colours; this browser remembers the choice</dd>
<dt>Listen on host / port</dt><dd data-hint="hint-bind"></dd>
<dt>Players connect to</dt><dd data-hint="hint-advertise"></dd>
<dt>Fill from ProtonVPN</dt><dd id="tip-lease">Asks ProtonVPN for your public address and, on Windows, the port it forwards; fills Listen on host, port and Players connect to. Needs ProtonVPN connected with port forwarding on. While the addresses stay as filled, Start and Copy join brick ask again.</dd>
<dt>Game folder</dt><dd data-hint="hint-game-root"></dd>
<dt>Second game folder</dt><dd data-hint="hint-game-root-second"></dd>
<dt>Recording</dt><dd data-hint="hint-record"></dd>
<dt>Copy join brick</dt><dd id="tip-brick">builds the one-line join code from Players connect to and the match password against the running server, copies it, and saves that address for the next window; joiners paste it into the join window; the code shows here for 45 s</dd>
"""
            + "<dt>New match shape</dt><dd data-hint=\"hint-shape-new\"></dd>\n"
            + "<dt>" + esc(ServerOpsCore.REACH_LABEL) + "</dt><dd id=\"tip-reach\">" + esc(ServerOpsCore.REACH_TIP)
            + "</dd>\n"
            + "<dt>" + esc(OpsJoinSelf.LABEL) + "</dt>"
            + """
<dd id="tip-join">opens the player join window on the server computer (Windows only), filled in with the running server's address, port and saved match password - press Launch in it; needs the player kit (CC_Client) in the Game folder's mods folder. On another computer, join with the code from Copy join brick</dd>
<dt>Launch debug WATCH</dt><dd id="tip-watch">Starts a test match and opens your own join window (two when a Second game folder is set). Every player gets hull bars and the debug keys. Admin commands need the admin password; your windows sign in as admin without it. For normal play, use Start.</dd>
<dt>Tick rate (Hz)</dt><dd data-hint="hint-tick"></dd>
<dt>Seats per address</dt><dd data-hint="hint-seats-per-source"></dd>
<dt>Match shape</dt><dd data-hint="hint-shape"></dd>
<dt>PvP mode</dt><dd data-hint="hint-pvp"></dd>
<dt>Match shape: players</dt><dd id="tip-players">How many player seats the picked match shape has, 1 to 255 (the server refuses a count the shape cannot take). Apply re-makes the shape with that many seats - nothing is sent: every join window fetches the new match. Takes effect at the next Start.</dd>
<dt>Battle map</dt><dd data-hint="hint-map"></dd>
<dt>Battle map: half size</dt><dd id="tip-map-half">Half the width of the square battle map, in game units: 2000 to 60000 (9000 = default). Takes effect at the next Start.</dd>
<dt>Battle map: station</dt><dd id="tip-map-station">Station type: lowtech, midline or hightech - only match shapes with a station. Takes effect at the next Start.</dd>
<dt>Battle map: background</dt><dd id="tip-map-backdrop">Battle background: none, open, nebula, nebula_blue or hyper. Takes effect at the next Start.</dd>
<dt>Battle map: deploy CR %</dt><dd id="tip-map-cr">Combat readiness every ship starts with, in percent (1 to 100). Takes effect at the next Start.</dd>
<dt>Match / Admin password</dt><dd data-hint="hint-admission"></dd>
<dt>Show</dt><dd>shows what is in the field</dd>
<dt>Show saved passwords</dt><dd id="tip-reveal">after a confirm, fills the match and admin password fields with the saved passwords for the picked match shape and shows them; otherwise a saved password never reaches this page</dd>
<dt>Remove the admin password</dt><dd>the next Start deletes the saved admin password (no admin seat)</dd>
<dt>Total wave DP</dt><dd data-hint="hint-fortress"></dd>
<dt>Per-wave scale</dt><dd data-hint="hint-wave-scale"></dd>
<dt>Roster DP</dt><dd data-hint="hint-roster-dp"></dd>
<dt>Roster x</dt><dd data-hint="hint-roster-mult"></dd>
<dt>All skills</dt><dd data-hint="hint-all-skills"></dd>
<dt>Deterministic replay pins (RNG)</dt><dd data-hint="hint-rng-pins"></dd>
<dt>Wave faction</dt><dd data-hint="hint-wave-category"></dd>
<dt>Default ship for joiners</dt><dd data-hint="hint-joiner"></dd>
<dt>Endless</dt><dd data-hint="hint-endless"></dd>
<dt>Terrain and strength</dt><dd data-hint="hint-terrain"></dd>
<dt>Asteroids</dt><dd data-hint="hint-asteroids"></dd>
<dt>Set live</dt><dd>sends the field beside it to a running server now (send-only: the page shows what was last sent); Start also writes it for the next start</dd>
<dt>Voice: Bitrate (kbps)</dt><dd data-hint="hint-v-bitrate"></dd>
<dt>Voice: Frame (ms)</dt><dd data-hint="hint-v-frame"></dd>
<dt>Voice: Complexity</dt><dd data-hint="hint-v-complexity"></dd>
<dt>Voice: Max talkers</dt><dd data-hint="hint-v-max-talkers"></dd>
<dt>Voice: Proximity full (su)</dt><dd data-hint="hint-v-prox-full"></dd>
<dt>Voice: Proximity cull (su)</dt><dd data-hint="hint-v-prox-cull"></dd>
<dt>Voice: Relay tick (ms)</dt><dd data-hint="hint-v-relay-tick"></dd>
<dt>Voice: Jitter floor (frames)</dt><dd>Frames the receiver holds before it starts playing a talker (one frame = the Frame ms). Lower = less delay but more 10 ms holds when a frame runs late. 1 = most aggressive. Lowest 1, highest 4, default 2 frames.</dd>
<dt>Voice: Jitter ceiling (ms)</dt><dd>How deep the buffer may grow: every late frame adds one frame, up to this many ms. Must be at least floor x Frame ms (Apply refuses a ceiling below the floor). Lowest 20, highest 160, default 80 ms.</dd>
<dt>Voice: Jitter relax (ms clean)</dt><dd>Clean audio (no late frames) this long shrinks the buffer one frame back toward the floor. Lower = a more aggressive return to the floor after a late burst; higher = steadier. Lowest 200, highest 20000, default 2000 ms.</dd>
<dt>Voice: bandwidth</dt><dd data-hint="hint-v-bandwidth"></dd>
<dt>Add folder</dt><dd data-hint="hint-mods-add"></dd>
"""
            + "<dt>Remove (a mod row)</dt><dd id=\"tip-mods-remove\">" + esc(ServerOpsCore.MODS_REMOVE_TIP) + "</dd>\n"
            + """
<dt>Rescan</dt><dd>re-read the searched folders and the properties rows</dd>
<dt>Folders searched</dt><dd data-hint="hint-mods-roots"></dd>
</dl>
<div class="actions"><button value="close" id="help-close">Close</button></div>
</form>
</dialog>
</body>
</html>
""";
}
