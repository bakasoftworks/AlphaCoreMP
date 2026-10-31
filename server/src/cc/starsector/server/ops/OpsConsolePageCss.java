package cc.starsector.server.ops;

/**
 * The console page's stylesheet - ONE text block, served by
 * {@link OpsConsolePage#css()} at {@code /ops.css}.
 *
 * <p>WHY this layout: the Swing V3 geometry on a
 * full-viewport grid - the status strip pinned on top, {@code main} the one scroller between, the dock
 * (load above the 2x3 verb bar) pinned at the bottom; the seat table sizes to its rows up to 40vh and then
 * scrolls inside, keeping its header row in view, while the players section grows with it and {@code main}
 * scrolls. A three-seat match had shown two rows plus a scrollbar: the table was the section's only shrinkable
 * child, so the bans / kept / shipped lists squeezed it to a 5em floor. The dock
 * comes before the nav in DOM order (Tab reaches the verbs early), so
 * the grid areas, not the DOM, place it last, and {@code column-reverse} puts the load above the bar.
 * The bar's {@code details.more} groups are see-through at this width (summary hidden, content shown
 * via {@code ::details-content}); a browser without that pseudo-element shows the summaries as
 * toggles instead of hiding the buttons. Headline colours follow {@code #strip[data-state]} (the Swing
 * {@code status.headline.*} rows). No {@code @import}, no {@code url()}, no web fonts (CSP
 * {@code default-src 'self'}); no backslash anywhere in the block.
 *
 * <p>The colour scheme (see the next paragraph) and the narrow layout
 * ({@code max-width: 640px}): one column, the strip capped with the detail on one line
 * until it is focused, the bar's {@code details.more} groups become real toggles holding the SAME
 * buttons, the seat table becomes cards (44 px tap rows), the load group shows only its utilization
 * row until it (focusable) is tapped, form rows stack, and the short admission hint replaces the long
 * one. The same DOM at every width, so the parity walk sees one page.
 *
 * <p>Dark by default. {@code :root} holds the dark values with
 * {@code color-scheme: dark} (the page's meta says the same), so a browser with scripts off, or before the
 * script runs, paints dark and never flashes light; {@code :root[data-theme="light"]} re-binds the same
 * colour properties to the light values (the non-colour members stay on {@code :root}). The core script
 * sets {@code data-theme="light"} only for a browser whose saved choice is light (the nav's Light / Dark
 * button). No {@code prefers-color-scheme} rule: the operating system's scheme no longer picks the page's.
 * The block stays comment-free.
 *
 * <p>The Setup folds ({@code #setup-form details.fold}) get their own three
 * rules: a little vertical room, a bold clickable summary, and no native disclosure marker (the summary text ends
 * in its own down-pointing triangle). They are real toggles at every width, so the narrow layout leaves them alone.
 *
 * <p>The ships list ({@code #ships-list}) takes the bans list's row
 * layout; a row the host hid ({@code li.ship-hidden}) shows its id struck through and dimmed, so Hide / Show reads
 * from the row itself without a style attribute (the CSP law).
 */
final class OpsConsolePageCss {

    private OpsConsolePageCss() {
    }

    /** The stylesheet. */
    static final String CSS = """
:root {
  color-scheme: dark;
  --bg: #15181c;
  --panel: #1d2127;
  --btn: #2a3038;
  --btn-hover: #353c45;
  --text: #dfe4ea;
  --muted: #9aa3ad;
  --line: #353b43;
  --stripe: #1b1f24;
  --pick-bg: #1f3a5f;
  --accent: #5ea2ff;
  --err: #ff6b6b;
  --c-run: #3fbf3f;
  --c-listening: #5ea2ff;
  --c-starting: #e0952e;
  --c-red: #ff5c5c;
  --c-grey: #a3abb4;
  --tap: 26px;
  --row-h: 20px;
  --mono: ui-monospace, "Cascadia Mono", Consolas, Menlo, "DejaVu Sans Mono", monospace;
  font-family: system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
  font-size: 13px;
}
:root[data-theme="light"] {
  color-scheme: light;
  --bg: #ffffff;
  --panel: #f3f4f6;
  --btn: #e8eaed;
  --btn-hover: #dcdfe3;
  --text: #1b1f24;
  --muted: #5f6670;
  --line: #d0d4d9;
  --stripe: #f2f2f2;
  --pick-bg: #cfe3ff;
  --accent: #005ac8;
  --err: #b40000;
  --c-run: #008000;
  --c-listening: #005ac8;
  --c-starting: #b06000;
  --c-red: #b40000;
  --c-grey: #505050;
}
*, *::before, *::after { box-sizing: border-box; }
[hidden] { display: none !important; }
html { height: 100%; }
body {
  margin: 0;
  height: 100vh;
  height: 100dvh;
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr) auto;
  grid-template-areas: "strip" "nav" "main" "dock";
  background: var(--bg);
  color: var(--text);
  line-height: 1.35;
}
#no-key { grid-area: main; padding: 24px; font-size: 15px; }
#no-key:not([hidden]) ~ *:not(#dlg-help) { display: none !important; }

button, input, select, output, summary, textarea { font: inherit; color: inherit; }
button {
  min-height: var(--tap);
  padding: 1px 10px;
  border: 1px solid var(--line);
  border-radius: 3px;
  background: var(--btn);
  cursor: pointer;
  white-space: nowrap;
}
button:hover:not(:disabled) { background: var(--btn-hover); }
button:disabled { opacity: .45; cursor: not-allowed; }
button[aria-busy="true"] { opacity: .55; cursor: wait; }
input[type="text"], input[type="password"], input[type="number"], select {
  min-height: var(--tap);
  padding: 1px 4px;
  border: 1px solid var(--line);
  border-radius: 3px;
  background: var(--bg);
}
input[type="number"] { width: 7em; }
input:disabled, select:disabled { opacity: .55; }
:focus-visible { outline: 2px solid var(--accent); outline-offset: 1px; }
[aria-invalid="true"] { outline: 2px solid var(--err); outline-offset: 0; border-color: var(--err); }
.dim { opacity: .6; }
.hint { margin: 0; color: var(--muted); font-size: 12px; }
.note-err { margin: 2px 0; color: var(--err); }
.voice-note { margin: 2px 0; }
.empty { margin: 4px 0; color: var(--muted); text-align: center; }
output.last-sent { color: var(--muted); font-size: 12px; white-space: nowrap; }
.vh {
  position: absolute; width: 1px; height: 1px; overflow: hidden;
  clip: rect(0 0 0 0); clip-path: inset(50%); white-space: nowrap;
}
kbd {
  font-family: var(--mono); font-size: 12px; padding: 0 4px;
  border: 1px solid var(--line); border-bottom-width: 2px; border-radius: 3px; background: var(--panel);
}
code { font-family: var(--mono); font-size: 12px; }

#strip {
  grid-area: strip;
  position: sticky; top: 0; z-index: 2;
  padding: 4px 10px;
  background: var(--panel);
  border-bottom: 1px solid var(--line);
  border-left: 4px solid var(--state, var(--c-grey));
}
#strip[data-state="run"] { --state: var(--c-run); }
#strip[data-state="listening"] { --state: var(--c-listening); }
#strip[data-state="starting"] { --state: var(--c-starting); }
#strip[data-state="stale"], #strip[data-state="gone"], #strip[data-state="failed"],
#strip[data-state="refused"], #strip[data-state="unreachable"], #strip[data-state="wrongkey"] { --state: var(--c-red); }
#strip[data-state="exited"], #strip[data-state="stopped"] { --state: var(--c-grey); }
.strip-row { display: flex; align-items: flex-start; gap: 8px; min-width: 0; }
.strip-head { align-items: baseline; }
#headline { margin: 0; font-size: 18px; font-weight: 700; color: var(--state, var(--c-grey)); }
#asof { margin: 0; font-size: 12px; }
#detail {
  flex: 1 1 auto; min-width: 0; margin: 0;
  font-family: var(--mono); font-size: 12px;
  white-space: pre-wrap; overflow-wrap: anywhere;
  max-height: 4.2em; overflow-y: auto;
}
#note { flex: 1 1 auto; min-width: 0; min-height: 1.35em; font-size: 12px; overflow-wrap: anywhere; }
#note-list { margin: 2px 0 2px 18px; padding: 0; max-height: 9em; overflow-y: auto; font-size: 12px; color: var(--muted); }
#brief { margin: 4px 0 2px; padding: 4px 8px; border: 1px solid var(--c-starting); border-radius: 3px; }
#brief-title { margin: 0 0 2px; font-size: 13px; }
#brief-body { margin: 0; padding-left: 18px; max-height: 13.5em; overflow-y: auto; }

#nav {
  grid-area: nav;
  display: flex; gap: 2px; align-items: flex-end;
  padding: 4px 10px 0;
  border-bottom: 1px solid var(--line);
}
#nav button { min-width: 76px; border-radius: 3px 3px 0 0; border-bottom: 0; }
#nav button[aria-pressed="true"] { background: var(--bg); font-weight: 700; position: relative; top: 1px; }
#help-open { margin-left: auto; min-width: var(--tap); }

main {
  grid-area: main;
  min-height: 0;
  overflow: auto;
  padding: 6px 10px;
  display: flex; flex-direction: column;
}
main > section { flex: 1 0 auto; }
#sec-players { display: flex; flex-direction: column; gap: 6px; flex: 1 0 auto; min-height: 16em; }
.table-wrap { overflow: auto; border: 1px solid var(--line); }
#sec-players .table-wrap { flex: 0 0 auto; max-height: 40vh; }
table { border-collapse: collapse; }
#seats { width: 100%; font-variant-numeric: tabular-nums; }
#seats th, #seats td {
  height: var(--row-h); padding: 1px 6px;
  text-align: left; white-space: nowrap;
  border-bottom: 1px solid var(--line);
}
#seats td { max-width: 24em; overflow: hidden; text-overflow: ellipsis; }
#seats th { position: sticky; top: 0; z-index: 1; background: var(--panel); font-weight: 700; }
#seats .num { text-align: right; }
#seats tbody tr { cursor: pointer; }
#seats tbody tr:nth-child(even) { background: var(--stripe); }
tr[aria-selected="true"], #seats tbody tr[aria-selected="true"] { background: var(--pick-bg); }
#seats:focus-visible { outline-offset: -2px; }
#bans { flex: none; }
#bans h2, #sec-mods h2, #sec-mods h3 { margin: 4px 0 2px; font-size: 13px; }
#bans-list { list-style: none; margin: 0; padding: 0; max-height: 10em; overflow-y: auto; }
#bans-list li { display: flex; align-items: center; gap: 8px; padding: 1px 0; font-family: var(--mono); font-size: 12px; }
#bans-empty, #bans-more { margin: 2px 0; font-size: 12px; }
#ships-list { list-style: none; margin: 0; padding: 0; max-height: 14em; overflow-y: auto; }
#ships-list li { display: flex; align-items: center; gap: 8px; padding: 1px 0; font-family: var(--mono); font-size: 12px; }
#ships-list li.ship-hidden .ship-label { text-decoration: line-through; opacity: .6; }
#ships-empty, #ships-last { margin: 2px 0; font-size: 12px; }

fieldset { min-width: 0; margin: 0 0 8px; padding: 4px 10px 6px; border: 1px solid var(--line); border-radius: 4px; }
legend { padding: 0 4px; font-weight: 700; }
.frow {
  display: grid;
  grid-template-columns: 12rem minmax(0, max-content) minmax(12rem, 1fr);
  gap: 2px 10px; align-items: center;
  padding: 2px 0;
}
.frow > label, .frow > .lab { text-align: right; }
.frow-hint { grid-template-columns: 12rem minmax(0, 1fr); }
.frow-hint > .hint { grid-column: 2; }
#hint-admission-inline { display: none; }
.ctl { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 6px; min-width: 0; }
label.inner { margin-left: 6px; }
label.check { display: inline-flex; align-items: center; gap: 4px; margin-right: 8px; }
.lock-reason { margin: 0 0 4px; color: var(--c-starting); font-weight: 700; }
#setup-form details.fold { margin: 2px 0 6px; }
#setup-form details.fold > summary { padding: 2px 0; font-weight: 700; cursor: pointer; list-style: none; }
#setup-form details.fold > summary::-webkit-details-marker { display: none; }
#f-advertise { font-family: var(--mono); }
#voice-bandwidth { font-family: var(--mono); font-size: 12px; }

#sec-mods > .ctl { margin: 4px 0; }
#mods-roots { list-style: none; margin: 2px 0; padding: 0; }
#mods-roots li { display: flex; align-items: center; gap: 8px; font-family: var(--mono); font-size: 12px; overflow-wrap: anywhere; }
#mods-explainer { margin: 2px 0 6px; }
#mods-table { width: 100%; }
#mods-table th, #mods-table td { padding: 1px 6px; text-align: left; white-space: nowrap; border-bottom: 1px solid var(--line); }
#mods-table th { background: var(--panel); }
#mods-table td[data-col="Folder"] { white-space: normal; overflow-wrap: anywhere; font-family: var(--mono); font-size: 12px; }

#dock {
  grid-area: dock;
  position: sticky; bottom: 0; z-index: 2;
  display: flex; flex-direction: column-reverse; gap: 4px;
  padding: 4px 10px;
  background: var(--panel);
  border-top: 1px solid var(--line);
}
#bar {
  display: grid;
  grid-template-columns: max-content max-content minmax(0, 1fr);
  gap: 4px 14px; align-items: center;
}
.cluster { display: flex; flex-wrap: nowrap; align-items: center; gap: 4px; min-width: 0; }
#cl-seat { grid-column: 1 / span 2; }
.cap { margin-right: 2px; font-size: 11px; font-weight: 700; letter-spacing: .04em; color: var(--muted); }
.prefix { color: var(--muted); }
.chip {
  display: inline-block; max-width: 16em; padding: 0 8px;
  border: 1px solid var(--line); border-radius: 10px;
  font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
#pick-why { font-size: 11px; white-space: nowrap; }
#customs-summary { min-width: 0; font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
#cl-customs { flex-wrap: wrap; }
#customs-detail { flex: 1 1 100%; min-width: 0; margin: 0; font-size: 11px; color: var(--muted); overflow-wrap: anywhere; }
#customs-detail:empty { display: none; }
#footer-note { grid-column: 1 / -1; margin: 0; font-size: 11px; color: var(--muted); }
#bar details.more { display: contents; }
#bar details.more > summary { display: none; }
#bar details.more::details-content { display: contents; content-visibility: visible; }
@supports not selector(::details-content) {
  #bar details.more { display: inline-flex; align-items: center; gap: 4px; }
  #bar details.more > summary {
    display: inline-flex; align-items: center; min-height: var(--tap); padding: 0 10px;
    border: 1px solid var(--line); border-radius: 3px; cursor: pointer;
  }
}

#load {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 2px 16px;
}
.lrow { display: flex; align-items: center; gap: 6px; min-width: 0; }
.lcap { flex: none; color: var(--muted); white-space: nowrap; }
.lrow meter { flex: 1 1 60px; min-width: 40px; height: 12px; }
.lrow output { min-width: 0; font-family: var(--mono); font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
#uplink { width: 5.5em; }
#load-rates-text, #load-phases-text, #load-totals-text { white-space: normal; overflow-wrap: anywhere; }

dialog {
  max-width: min(92vw, 760px);
  padding: 12px 16px;
  border: 1px solid var(--line); border-radius: 6px;
  background: var(--bg); color: var(--text);
}
dialog::backdrop { background: rgba(0, 0, 0, .45); }
dialog h2 { margin: 0 0 8px; font-size: 15px; }
dialog h3 { margin: 10px 0 4px; font-size: 13px; }
#confirm-text { white-space: pre-wrap; }
#error-text {
  max-height: 50vh; overflow: auto; margin: 0 0 8px; padding: 6px;
  white-space: pre-wrap; overflow-wrap: anywhere;
  font-family: var(--mono); font-size: 12px;
  background: var(--panel); border: 1px solid var(--line);
}
.actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 10px; }
#dlg-help { width: min(92vw, 880px); }
#dlg-help dl { display: grid; grid-template-columns: max-content minmax(0, 1fr); gap: 3px 14px; margin: 4px 0 10px; }
#dlg-help dt { font-weight: 700; }
#dlg-help dd { margin: 0; }
#dlg-palette { width: min(92vw, 520px); margin-top: 12vh; padding: 8px; }
#palette-input { width: 100%; }
#palette-list { list-style: none; margin: 6px 0 0; padding: 0; max-height: 50vh; overflow-y: auto; }
#palette-list li { padding: 3px 6px; cursor: pointer; }
#palette-list [aria-selected="true"] { background: var(--pick-bg); }

@media (max-width: 640px) {
  :root { --tap: 44px; }
  #strip { max-height: 40vh; overflow-y: auto; }
  #detail { max-height: none; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
  #detail:focus { white-space: pre-wrap; text-overflow: clip; overflow-y: auto; max-height: 9em; }
  #bar { grid-template-columns: minmax(0, 1fr); }
  #cl-seat, #footer-note { grid-column: auto; }
  .cluster { flex-wrap: wrap; }
  #bar .cap { display: none; }
  #bar details.more { display: block; }
  #bar details.more > summary {
    display: inline-flex; align-items: center; min-height: var(--tap); padding: 0 10px;
    border: 1px solid var(--line); border-radius: 3px; background: var(--btn); cursor: pointer;
  }
  #bar details.more:not([open])::details-content { content-visibility: hidden; display: none; }
  #bar details.more[open]::details-content {
    content-visibility: visible; display: flex; flex-wrap: wrap; align-items: center; gap: 4px; padding-top: 4px;
  }
  #seats, #seats tbody, #seats tr, #seats td { display: block; }
  #seats thead { display: none; }
  #seats tbody tr { min-height: 44px; padding: 4px 6px; border-bottom: 1px solid var(--line); }
  #seats td {
    height: auto; max-width: none; padding: 0; border: 0;
    overflow: visible; white-space: normal; overflow-wrap: anywhere; text-align: left;
  }
  #seats .num { text-align: left; }
  #seats td::before { content: attr(data-col); display: inline-block; min-width: 6.5em; color: var(--muted); font-size: 12px; }
  #load { grid-template-columns: minmax(0, 1fr); cursor: pointer; }
  #load > .lrow:not(#util) { display: none; }
  #load:focus-within > .lrow:not(#util) { display: flex; }
  .frow, .frow-hint { grid-template-columns: minmax(0, 1fr); }
  .frow > label, .frow > .lab { text-align: left; }
  .frow-hint > .hint { grid-column: auto; }
  input, select { max-width: 100%; }
  #hint-admission { display: none; }
  #hint-admission-inline { display: block; }
  #dlg-help dl { grid-template-columns: minmax(0, 1fr); }
  #dlg-help dd { margin-bottom: 4px; }
}
""";
}
