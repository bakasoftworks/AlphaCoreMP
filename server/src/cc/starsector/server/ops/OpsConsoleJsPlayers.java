package cc.starsector.server.ops;

/**
 * The console page's PLAYERS
 * section + the WHOLE verb bar, as the second part of the one page script. WHY a separate class: see
 * {@link OpsConsoleJsCore}; {@link OpsConsolePage#js()} joins this part second, so the core's
 * {@code CC} object is in scope. It renders the seat table (cells index-for-index with
 * {@link ServerOpsCore#SEAT_COLUMNS}, the cid key read from the last cell and never shown), keeps the
 * pick ({@code CC.pick}, re-found by seat id on every status answer), renders the bans list, applies
 * the Swing enable ladder to every bar control, and sends each verb through {@code CC.send} (the
 * server mints every confirm; the page never decides one). The ids it binds are the ones
 * {@link OpsConsolePageHtml#HTML} pins. The whole part sits in one block so none of its names leak
 * into the sibling parts.
 * <p>Comments inside the block carry no repo ids (the block is served and exported).
 *
 * <p>Join from this PC ({@code #btn-join}) posts {@code /api/join} with
 * an empty form ({@code aria-busy} while out, a refusal shown the way every send refusal is) and notes the
 * answer's {@code detail}; the part's {@code onConfig} shows the button for {@code joinHere === true} and the
 * {@code #join-linux} line for {@code joinHere === false} (row {@code page-join}).
 * <p>Launch debug WATCH ({@code #btn-watch}) = {@code V.watch}, the
 * Start press through ONE route-and-button function shared with {@code V.start} ({@code /api/watch} vs
 * {@code /api/start}; the same gate, accept and clear steps), shown by {@code onConfig} for
 * {@code joinHere === true} like Join from this PC, and in the palette; {@code onStatus} notes the status key
 * {@code watchNote} once per new non-empty sentence (the last one noted is forgotten when the key reads empty or
 * a Start is accepted) (row {@code page-watch}).
 * <p>Keep for everyone ({@code #btn-keep-custom}) sends
 * {@code custom-keep} the Ban custom way ({@code V.customVerb}: the typed Custom id, else the pick; the
 * typed-id-first law lives in the server arm) under the same enable gate; Forget kept ({@code #btn-forget-custom},
 * gated like Unban custom: it needs no pick) sends {@code custom-forget} with the ONE kept id when exactly one is
 * kept, else notes where to pick; {@code renderKept} paints {@code #kept-list} (a Forget per row,
 * {@code stem=}) and {@code #inbox-list} (a Keep per row, {@code custom-keep-export} + {@code exportName=})
 * from the status keys {@code customKeptIds} / {@code customInboxIds}, textContent only, the counts and
 * over-cap words in the two headings (row {@code page-kept}). Every send goes through the one {@code send}
 * path, so the server-minted confirm shows the way every verb confirm does.
 * <p>Ban address ({@code #btn-ban-ip}, {@code V.banIp}) sends {@code ban-ip}
 * + {@code key=} with the trimmed {@code #ban-ip-key} text (an empty box only notes), gated like the Bans list's
 * Unban ({@code gate.cmd}); its echo settles when the status ban count rises by one, and the box empties once the
 * server takes the line unless it was retyped meanwhile.
 * <p>{@code renderShips} paints {@code #ships-list} from the status keys
 * {@code shipsBaseIds} / {@code shipsHiddenIds} (a row per shipped id, then any hidden id the shipped list lacks;
 * ids compared ASCII-folded), a hidden row marked {@code ship-hidden} with Show in place of Hide; the heading counts
 * the shipped ids and carries the over-cap words on {@code shipsOverCap}; {@code #ships-last} shows
 * {@code shipsLast} and, on a non-zero {@code shipsPersistFailed}, the failed-save sentence with its count. Hide
 * ({@code V.shipHide}) sends {@code ship-hide} + {@code id=} (the server asks first), Show ({@code V.shipShow})
 * {@code ship-show} + {@code id=}, both gated like Forget (row {@code page-ships}).
 * <p>On {@code packaged:false} (owner mode) {@code onConfig} relabels
 * {@code #btn-join} to {@code Joiner GUI}, the Swing owner button's name (its HTML tag stays byte-identical; the
 * server's owner branch of {@code /api/join} spawns the joiner GUI script).
 * <p>In owner mode ({@code repoConsole}, set by {@code onConfig} from
 * {@code packaged:false}) {@code #btn-watch} is the Swing owner button: shown whatever the host, labelled
 * {@code Arm WATCH (observer)}, its help entry {@code tip-watch} = {@code REPO_WATCH_TIP} (what one press opens);
 * {@code startVia} posts it with an EMPTY form (the server arms from its own brief file, so no typed text rides
 * and the typed passwords stay) and notes the answer's {@code detail}. Every package literal stays: a package
 * page never sets {@code repoConsole}. The WATCH lane's Kill question is minted by the server like every
 * confirm.
 * <p>The seat table's {@code MagicLib} cell carries, as its {@code title},
 * the seat's {@code MagicLib why} data cell (the server's host words for why a reporting seat is not followed;
 * no td shows that cell, the way none shows {@code cidkey}); {@code renderMagicSeam} writes the status key
 * {@code magicSeamLine} into {@code #magic-seam} in the MATCH cluster, hidden while the key reads empty or is
 * absent (an older server).
 * <p>{@code renderPilotShip} is its twin for the status key
 * {@code pilotShipLine} and {@code #pilot-ship} (whether each pilot's ship counts as the player's), with the same
 * hide rule; no seat cell, the server marks every seat's hull alike.
 */
final class OpsConsoleJsPlayers {

    private OpsConsoleJsPlayers() {
    }

    /** The players + bar script part (one block; registers module {@code players} at load). */
    static final String JS = """
            // Players section + the verb bar: the seat table and its pick, the bans list, and every bar
            // verb sent through CC.send (the server mints each confirm). One block, so no name leaks.
            {
              const NO_LIVE = 'no live server to command';
              const NO_PICK = 'pick a seat row first';
              const STOCK = 'the picked seat flies a stock hull';
              const NO_ID = 'type a custom id (cc-v...) or pick a seat flying one';
              const NO_ANSWER = 'the console is not answering';
              const NO_BANS = 'no bans in the last snapshot';
              const EMPTY_OVER = 'no seats - the match is over; Start server begins a new one';
              const EMPTY_STOPPED = 'no seats - Start server begins a new match';
              const EMPTY_WAITING = 'no seats yet - waiting for joiners';
              // the seat cell order when an answer lacks seatColumns (the statusJson column set)
              const COLS = ['seat', 'name', 'client', 'ship', 'endpoint', 'live', 'tx/s', 'rx/s',
                'tx total', 'rx total', 'ctrl', 'MagicLib', 'MagicLib why', 'custom', 'customs', 'cidkey'];
              const body = CC.el('seats-body');
              const table = CC.el('seats');
              const rowOf = new Map(); // seat id -> its tr, reused so a poll keeps focus and text selection
              let shown = [];          // rendered seats in table order: {tr, seatId, name, clientId, cidKey, custom}
              let banSig = null;       // the banRows last rendered (unchanged rows are not rebuilt)
              let keptSig = null;      // the kept / exported lists last rendered (the same rebuild law)
              const NO_KEPT = 'No kept ships yet.';
              const PICK_KEPT = 'pick a kept ship in the list below';
              const OVER_CAP = ' - over cap';
              // the ships that come with the server: heading, empty line and failed-save sentence (the core's texts)
              let shipsSig = null;
              const SHIPS_TITLE = 'Ships that come with the server';
              const NO_SHIPS = 'Start the server to see its ships.';
              const SHIPS_SAVE_FAILED = 'The hidden list could not be saved, so a change may not last past a restart. Failed saves: ';
              // a status list of strings (anything else reads as empty)
              const idList = (v) => Array.isArray(v) ? v.filter((x) => typeof x === 'string') : [];
              // a refusal already showed the core's dialog; unreachable / wrong key show on the strip
              const ignore = () => {};

              CC.pick = null;

              // writes a text only when it differs (a same-value write would drop a text selection)
              const put = (node, v) => {
                if (node && node.textContent !== String(v)) {
                  CC.text(node, v);
                }
              };
              const noAnswer = () => {
                const st = CC.el('strip').dataset.state;
                return !CC.status || st === 'unreachable' || st === 'wrongkey';
              };
              const starting = () => {
                const s = CC.status;
                return !!s && ((!!s.launch && !s.launch.terminal) || s.staging === true || s.launcherUp === true);
              };

              // seats: every seat is an array matching s.seatColumns index for index (14 shown, plus two data
              // cells no td shows: the MagicLib why, read into the MagicLib cell's title, and cidkey last);
              // seatId = the seat cell (an int, > 0 for a seated player); cidKey = hex or '' (an old writer)
              const seatRows = (s) => {
                const names = Array.isArray(s.seatColumns) ? s.seatColumns : COLS;
                const at = {};
                names.forEach((n, i) => { at[n] = i; });
                const cell = (r, name) => {
                  const v = Array.isArray(r) && at[name] !== undefined ? r[at[name]] : undefined;
                  return v === undefined || v === null ? '' : String(v);
                };
                return (Array.isArray(s.seats) ? s.seats : []).map((r) => ({
                  cell: (name) => cell(r, name),
                  seatId: Number(cell(r, 'seat')),
                  name: cell(r, 'name'),
                  clientId: cell(r, 'client'),
                  cidKey: cell(r, 'cidkey'),
                  custom: cell(r, 'custom')
                }));
              };
              const seatIds = (s) => seatRows(s).map((r) => r.seatId);
              const pickOf = (r) => ({seatId: r.seatId, clientId: r.clientId, cidKey: r.cidKey, custom: r.custom});
              // the Custom id box (trimmed; '' = act on the picked seat's flying custom)
              const typedId = () => CC.el('customId').value.trim();

              // aria-selected on the picked row + the chip; focus = the picked row (else the table)
              const paintPick = (focus) => {
                const p = CC.pick;
                let picked = null;
                for (const r of shown) {
                  const on = p !== null && r.seatId === p.seatId;
                  r.tr.setAttribute('aria-selected', on ? 'true' : 'false');
                  if (on) {
                    picked = r;
                  }
                }
                put(CC.el('pick-chip'), picked
                  ? 'seat ' + picked.seatId + ' ' + (picked.name || picked.clientId) : 'no seat picked');
                if (focus) {
                  const target = picked ? picked.tr : table;
                  if (document.activeElement !== target) {
                    target.focus({preventScroll: true});
                  }
                }
              };

              const renderSeats = (s) => {
                const rows = seatRows(s);
                const focusIn = body.contains(document.activeElement);
                const dim = s.seatsRemembered === true;
                const used = new Set();
                rows.forEach((r, i) => {
                  let tr = rowOf.get(r.seatId);
                  if (!tr || used.has(tr)) {
                    tr = CC.tpl('tpl-seat-row').firstElementChild;
                    tr.tabIndex = -1;
                    if (!rowOf.has(r.seatId)) {
                      rowOf.set(r.seatId, tr);
                    }
                  }
                  used.add(tr);
                  for (const td of tr.children) {
                    put(td, r.cell(td.dataset.col));
                    // the MagicLib cell's hover says why a reporting seat is not followed ('' = no title)
                    if (td.dataset.col === 'MagicLib') {
                      const why = r.cell('MagicLib why');
                      if (td.title !== why) {
                        td.title = why;
                      }
                    }
                  }
                  tr.classList.toggle('dim', dim);
                  if (body.children[i] !== tr) {
                    body.insertBefore(tr, body.children[i] || null);
                  }
                  r.tr = tr;
                });
                while (body.children.length > rows.length) {
                  body.lastElementChild.remove();
                }
                for (const [id, tr] of rowOf) {
                  if (!tr.isConnected) {
                    rowOf.delete(id);
                  }
                }
                shown = rows;
                const p = CC.pick;
                if (p) {
                  const r = shown.find((x) => x.seatId === p.seatId);
                  if (!r || r.clientId !== p.clientId || r.cidKey !== p.cidKey) {
                    CC.pick = null;
                    CC.note('seat ' + p.seatId + ' left - pick cleared');
                  } else {
                    CC.pick = pickOf(r);
                  }
                }
                paintPick(focusIn);
                const empty = CC.el('seats-empty');
                CC.show(empty, shown.length === 0);
                if (shown.length === 0) {
                  put(empty, s.matchOver === true ? EMPTY_OVER
                    : (CC.ladder().startEnabled ? EMPTY_STOPPED : EMPTY_WAITING));
                }
                CC.show(CC.el('seats-remembered'), dim && shown.length > 0);
              };

              const pickAt = (i) => {
                const r = shown[i];
                if (!r) {
                  return;
                }
                CC.pick = pickOf(r);
                CC.el('customId').value = r.custom; // a pick fills the box with its flying custom ('' = stock)
                paintPick(false);
                r.tr.focus();
                applyBar();
              };
              body.addEventListener('click', (e) => {
                const tr = e.target.closest('tr');
                const i = shown.findIndex((r) => r.tr === tr);
                if (i >= 0) {
                  pickAt(i);
                }
              });
              // the table's own keys (help: Up / Down / Home / End move the pick, Esc clears it)
              table.addEventListener('keydown', (e) => {
                if (e.ctrlKey || e.altKey || e.metaKey) {
                  return;
                }
                const n = shown.length;
                const at = CC.pick ? shown.findIndex((r) => r.seatId === CC.pick.seatId) : -1;
                let to;
                if (e.key === 'ArrowDown') {
                  to = at < 0 ? 0 : Math.min(n - 1, at + 1);
                } else if (e.key === 'ArrowUp') {
                  to = at < 0 ? 0 : Math.max(0, at - 1);
                } else if (e.key === 'Home') {
                  to = 0;
                } else if (e.key === 'End') {
                  to = n - 1;
                } else if (e.key === 'Escape') {
                  to = -1;
                } else {
                  return;
                }
                e.preventDefault();
                if (to === -1) {
                  CC.pick = null;
                  paintPick(false);
                  table.focus();
                  applyBar();
                } else if (n > 0) {
                  pickAt(to);
                }
              });

              // the bar's enable ladder: each gate answers null (on) or why it is off
              const gate = {
                start: (L) => L.startEnabled ? null
                  : (noAnswer() ? NO_ANSWER : (starting() ? 'starting' : 'a server is running')),
                cmd: (L) => L.commandable ? null : (noAnswer() ? NO_ANSWER : NO_LIVE),
                kill: (L) => L.killEnabled ? null : (noAnswer() ? NO_ANSWER : 'nothing to stop'),
                unbanPlayer: (L) => gate.cmd(L) || (L.bans > 0 ? null : NO_BANS),
                seat: (L) => gate.cmd(L) || (CC.pick ? null : NO_PICK),
                // a typed Custom id, else the pick's flying custom
                custom: (L) => gate.cmd(L) || (typedId() || (CC.pick && CC.pick.custom) ? null
                  : (CC.pick ? STOCK : NO_ID))
              };
              const BAR = [
                ['btn-start', gate.start], ['btn-watch', gate.start], ['btn-end-round', gate.cmd], ['btn-end-match', gate.cmd],
                ['btn-kill', gate.kill], ['simspeed', gate.cmd], ['btn-simspeed-set', gate.cmd],
                ['btn-auto-on', gate.cmd], ['btn-auto-off', gate.cmd], ['btn-kick', gate.seat],
                ['btn-ban-player', gate.seat], ['btn-ban-source', gate.seat],
                ['btn-unban-player', gate.unbanPlayer], ['btn-ban-ip', gate.cmd],
                ['btn-reject-seat', gate.seat], ['btn-allow-seat', gate.seat],
                ['btn-ban-custom', gate.custom], ['btn-export-custom', gate.custom],
                ['btn-unban-custom', gate.cmd], ['customs-accept', gate.cmd],
                ['btn-keep-custom', gate.custom], ['btn-forget-custom', gate.cmd]
              ];
              // a control mid-send (aria-busy, set by CC.send) stays off until its answer lands
              const enable = (node, why) => {
                const busy = node.getAttribute('aria-busy') === 'true';
                CC.enable(node, !why && !busy, why || (busy ? 'sending' : ''));
              };
              const applyBar = () => {
                const L = CC.ladder();
                for (const [id, g] of BAR) {
                  enable(CC.el(id), g(L));
                }
                const why = gate.seat(L);
                const pickWhy = CC.el('pick-why');
                put(pickWhy, why || '');
                CC.show(pickWhy, !!why);
                for (const b of CC.qa('#bans-list .unban')) {
                  enable(b, gate.cmd(L));
                }
                for (const b of CC.qa('#kept-list .forget')) {
                  enable(b, gate.cmd(L));
                }
                for (const b of CC.qa('#inbox-list .keep')) {
                  enable(b, gate.cmd(L));
                }
                for (const b of CC.qa('#ships-list .ship-hide')) {
                  enable(b, gate.cmd(L));
                }
                for (const b of CC.qa('#ships-list .ship-show')) {
                  enable(b, gate.cmd(L));
                }
              };
              // the strip turning unreachable / wrong key brings no status answer: re-apply the ladder on it
              new MutationObserver(applyBar).observe(CC.el('strip'),
                {attributes: true, attributeFilter: ['data-state']});

              // a press re-checks its gate (the palette path; a poll may have moved the ladder) and notes why
              const blocked = (g) => {
                const why = g(CC.ladder());
                if (why) {
                  CC.note(why);
                }
                return !!why;
              };
              // one send: dropped while its control is busy; settles to the answer, null (a Cancel) or undefined
              const send = (path, fields, btn, echo) => {
                if (btn && btn.getAttribute('aria-busy') === 'true') {
                  return Promise.resolve(null);
                }
                const opts = echo ? {busy: btn, echo} : {busy: btn};
                return CC.send(path, fields, opts).catch(ignore).finally(applyBar);
              };
              // a seat verb's fields: the picked seat (int > 0) + its cid key, so the server refuses a new occupant
              const seatFields = (verb) => ({verb, seat: String(CC.pick.seatId), key: CC.pick.cidKey});
              let unbanOpener = null; // the control that opened the Unban custom dialog (focus returns there)
              let watchShown = ''; // the debug watch's last sentence already noted ('' = none)
              let repoConsole = false; // this console runs in the repository (the config said packaged: false)
              // the repository console's WATCH help text (what one press opens)
              const REPO_WATCH_TIP = 'Arms the standing test match from this repository: a VPN lease and the kit'
                + ' checks, then the server, your host window, the kit join window and an observer window open.'
                + " This page then follows the watch's own status.";

              // one Start press by route and button: Start and Launch debug WATCH share every accept / clear step
              const startVia = (path, btnId) => {
                if (blocked(gate.start)) {
                  return;
                }
                // the repository console's WATCH arms from the server's own files: no form text rides it, and
                // the typed passwords stay for the next Start
                if (path === '/api/watch' && repoConsole) {
                  const btn = CC.el(btnId);
                  if (btn.getAttribute('aria-busy') === 'true') {
                    return;
                  }
                  CC.send(path, {}, {busy: btn, noteText: 'asked for the observer WATCH'}).then((a) => {
                    if (a && a.ok && typeof a.detail === 'string') {
                      CC.note(a.detail);
                    }
                  }, ignore).finally(applyBar);
                  return;
                }
                const fields = typeof CC.startForm === 'function' ? CC.startForm() : null;
                if (!fields) {
                  CC.note('the setup form is not loaded');
                  return;
                }
                send(path, fields, CC.el(btnId),
                  (st) => st.stateKey === 'listening' || st.stateKey === 'run')
                  .then((a) => {
                    // an accepted Start took the typed passwords: the setup part clears them (a refusal
                    // keeps them, so the retry re-sends the typed text and never 'keep')
                    if (a && a.ok && typeof CC.startAccepted === 'function') {
                      CC.startAccepted();
                    }
                    // an accepted Start cleared the server's watch sentence: the same one may be noted again
                    if (a && a.ok) {
                      watchShown = '';
                    }
                    // a Start that asked ProtonVPN again answers the fresh addresses as fill
                    if (a && a.ok && a.fill && typeof CC.applyFill === 'function') {
                      CC.applyFill(a.fill);
                    }
                    return a ? CC.refreshConfig() : null;
                  }).catch(ignore);
              };
              // the debug watch's outcome (the join window it opened, or why not) is noted once per sentence
              const noteWatch = (s) => {
                const n = typeof s.watchNote === 'string' ? s.watchNote : '';
                if (n === '') {
                  watchShown = '';
                } else if (n !== watchShown) {
                  watchShown = n;
                  CC.note(n);
                }
              };

              const V = {
                start: () => startVia('/api/start', 'btn-start'),
                watch: () => startVia('/api/watch', 'btn-watch'),
                endRound: () => {
                  if (!blocked(gate.cmd)) {
                    send('/api/verb', {verb: 'end-round'}, CC.el('btn-end-round'));
                  }
                },
                endMatch: () => {
                  if (!blocked(gate.cmd)) {
                    send('/api/verb', {verb: 'end-match'}, CC.el('btn-end-match'), (st) => st.matchOver === true);
                  }
                },
                kill: () => {
                  if (!blocked(gate.kill)) {
                    send('/api/kill', {}, CC.el('btn-kill'));
                  }
                },
                simSet: () => {
                  if (blocked(gate.cmd)) {
                    return;
                  }
                  const v = CC.el('simspeed').value.trim();
                  send('/api/verb', {verb: 'set-sim-speed', value: v}, CC.el('btn-simspeed-set'))
                    .then((a) => {
                      if (a) {
                        CC.lastSent(CC.el('simspeed-last'), 'x' + v);
                      }
                    });
                },
                auto: (on) => {
                  if (blocked(gate.cmd)) {
                    return;
                  }
                  const word = on ? 'on' : 'off';
                  send('/api/verb', {verb: 'sim-speed-auto', auto: word}, CC.el(on ? 'btn-auto-on' : 'btn-auto-off'))
                    .then((a) => {
                      if (a) {
                        CC.lastSent(CC.el('simspeed-last'), 'auto ' + word);
                      }
                    });
                },
                kick: () => {
                  if (blocked(gate.seat)) {
                    return;
                  }
                  const id = CC.pick.seatId;
                  send('/api/verb', seatFields('kick'), CC.el('btn-kick'), (st) => !seatIds(st).includes(id));
                },
                ban: (verb, btnId) => {
                  if (blocked(gate.seat)) {
                    return;
                  }
                  const before = CC.status.bans | 0;
                  send('/api/verb', seatFields(verb), CC.el(btnId), (st) => (st.bans | 0) === before + 1);
                },
                seatVerb: (verb, btnId, g) => {
                  if (!blocked(g)) {
                    send('/api/verb', seatFields(verb), CC.el(btnId));
                  }
                },
                // Ban / Export custom: the typed id when the box has one, else the picked seat's flying custom
                customVerb: (verb, btnId) => {
                  if (!blocked(gate.custom)) {
                    const id = typedId();
                    send('/api/verb', id ? {verb, id} : seatFields(verb), CC.el(btnId));
                  }
                },
                // Forget kept: one kept ship = that one (the server asks first); more = the list's own Forget
                forgetKept: () => {
                  if (blocked(gate.cmd)) {
                    return;
                  }
                  const ids = idList(CC.status && CC.status.customKeptIds);
                  if (ids.length === 1) {
                    send('/api/verb', {verb: 'custom-forget', stem: ids[0]}, CC.el('btn-forget-custom'));
                  } else {
                    CC.note(ids.length === 0 ? NO_KEPT : PICK_KEPT);
                  }
                },
                forgetRow: (stem, btn) => {
                  if (!blocked(gate.cmd)) {
                    send('/api/verb', {verb: 'custom-forget', stem}, btn);
                  }
                },
                keepRow: (name, btn) => {
                  if (!blocked(gate.cmd)) {
                    send('/api/verb', {verb: 'custom-keep-export', exportName: name}, btn);
                  }
                },
                // a shipped ship's Hide (the server asks first) and Show (asks nothing), keyed by its id
                shipHide: (id, btn) => {
                  if (!blocked(gate.cmd)) {
                    send('/api/verb', {verb: 'ship-hide', id}, btn);
                  }
                },
                shipShow: (id, btn) => {
                  if (!blocked(gate.cmd)) {
                    send('/api/verb', {verb: 'ship-show', id}, btn);
                  }
                },
                unbanRow: (row, btn) => {
                  if (blocked(gate.cmd)) {
                    return;
                  }
                  const before = CC.status.bans | 0;
                  send('/api/verb', {verb: 'unban', kind: row.kind, key: row.key}, btn,
                    (st) => (st.bans | 0) === before - 1);
                },
                // Ban address: the typed address or range; the server judges it and asks first. The box empties
                // once the server takes the line, unless the host typed something new meanwhile.
                banIp: () => {
                  if (blocked(gate.cmd)) {
                    return;
                  }
                  const box = CC.el('ban-ip-key');
                  const key = box.value.trim();
                  if (!key) {
                    CC.note('type an address or range first');
                    return;
                  }
                  const before = CC.status.bans | 0;
                  send('/api/verb', {verb: 'ban-ip', key}, CC.el('btn-ban-ip'),
                    (st) => (st.bans | 0) === before + 1).then((a) => {
                    if (a && a.ok && box.value.trim() === key) {
                      box.value = '';
                    }
                  });
                },
                // the bar's Unban player = the Bans list (one Unban per row): bring it up and focus its first row
                unbanPlayer: () => {
                  if (blocked(gate.unbanPlayer)) {
                    return;
                  }
                  if (CC.sectionName !== 'players') {
                    CC.section('players');
                  }
                  const b = CC.q('#bans-list .unban:not(:disabled)');
                  if (b) {
                    b.focus();
                    CC.note('pick a ban in the Bans list and press its Unban');
                  } else {
                    CC.note(NO_BANS);
                  }
                },
                unbanCustom: () => {
                  const dlg = CC.el('dlg-unban-custom');
                  if (dlg.open || blocked(gate.cmd)) {
                    return;
                  }
                  unbanOpener = document.activeElement;
                  CC.el('unban-custom-id').value = '';
                  dlg.returnValue = '';
                  dlg.showModal();
                  CC.el('unban-custom-id').focus();
                },
                customs: (on) => {
                  if (blocked(gate.cmd)) {
                    return;
                  }
                  const box = CC.el('customs-accept');
                  box.checked = on;
                  send('/api/verb', {verb: 'custom-submissions', auto: on ? 'on' : 'off'}, box);
                }
              };

              CC.el('btn-start').addEventListener('click', V.start);
              CC.el('btn-watch').addEventListener('click', V.watch);
              // Join from this PC: the server opens the player join window on ITS computer, filled in with
              // the running server's address, port and saved match password. Not in the enable ladder: it
              // posts at once, and the server answers why when nothing runs.
              CC.el('btn-join').addEventListener('click', () => {
                const btn = CC.el('btn-join');
                if (btn.getAttribute('aria-busy') === 'true') {
                  return;
                }
                CC.send('/api/join', {}, {busy: btn, noteText: 'asked for the join window'}).then((a) => {
                  if (a && a.ok && typeof a.detail === 'string') {
                    CC.note(a.detail);
                  }
                }, () => {});
              });
              // Dev editor (the repository console only): opens the maintainer's editor window on the server's
              // computer. Like Join: posts at once, and the server answers why when it refuses.
              CC.el('btn-dev-editor').addEventListener('click', () => {
                const btn = CC.el('btn-dev-editor');
                if (btn.getAttribute('aria-busy') === 'true') {
                  return;
                }
                CC.send('/api/dev-editor', {}, {busy: btn, noteText: 'asked for the dev editor'}).then((a) => {
                  if (a && a.ok && typeof a.detail === 'string') {
                    CC.note(a.detail);
                  }
                }, () => {});
              });
              CC.el('btn-end-round').addEventListener('click', V.endRound);
              CC.el('btn-end-match').addEventListener('click', V.endMatch);
              CC.el('btn-kill').addEventListener('click', V.kill);
              CC.el('btn-simspeed-set').addEventListener('click', V.simSet);
              CC.el('simspeed').addEventListener('keydown', (e) => {
                if (e.key === 'Enter' && !e.repeat) {
                  e.preventDefault();
                  V.simSet();
                }
              });
              CC.el('btn-auto-on').addEventListener('click', () => V.auto(true));
              CC.el('btn-auto-off').addEventListener('click', () => V.auto(false));
              CC.el('btn-kick').addEventListener('click', V.kick);
              CC.el('btn-ban-player').addEventListener('click', () => V.ban('ban', 'btn-ban-player'));
              CC.el('btn-ban-source').addEventListener('click', () => V.ban('ban-src', 'btn-ban-source'));
              CC.el('btn-unban-player').addEventListener('click', V.unbanPlayer);
              CC.el('btn-ban-ip').addEventListener('click', V.banIp);
              CC.el('btn-reject-seat').addEventListener('click',
                () => V.seatVerb('custom-reject', 'btn-reject-seat', gate.seat));
              CC.el('btn-allow-seat').addEventListener('click',
                () => V.seatVerb('custom-allow', 'btn-allow-seat', gate.seat));
              CC.el('btn-ban-custom').addEventListener('click', () => V.customVerb('custom-ban', 'btn-ban-custom'));
              CC.el('btn-export-custom').addEventListener('click',
                () => V.customVerb('custom-export', 'btn-export-custom'));
              CC.el('btn-keep-custom').addEventListener('click',
                () => V.customVerb('custom-keep', 'btn-keep-custom'));
              CC.el('btn-forget-custom').addEventListener('click', V.forgetKept);
              CC.el('customId').addEventListener('input', applyBar);
              CC.el('btn-unban-custom').addEventListener('click', V.unbanCustom);
              // the box shows the snapshot's flag; a change sends the flip and the next poll confirms it
              CC.el('customs-accept').addEventListener('change', (e) => {
                const on = e.target.checked;
                e.target.checked = !on;
                V.customs(on);
              });
              CC.el('unban-custom-cancel').addEventListener('click', () => CC.el('dlg-unban-custom').close('cancel'));
              // a held Enter (e.g. from the palette) never submits the freshly opened id field
              CC.el('unban-custom-id').addEventListener('keydown', (e) => {
                if (e.key === 'Enter' && e.repeat) {
                  e.preventDefault();
                }
              });
              CC.el('dlg-unban-custom').addEventListener('close', () => {
                const dlg = CC.el('dlg-unban-custom');
                const id = CC.el('unban-custom-id').value.trim();
                if (unbanOpener && unbanOpener.isConnected) {
                  unbanOpener.focus();
                }
                unbanOpener = null;
                if (dlg.returnValue !== 'ok') {
                  return;
                }
                if (!id) {
                  CC.note('no custom id typed - nothing sent');
                } else if (!blocked(gate.cmd)) {
                  send('/api/verb', {verb: 'custom-unban', id}, CC.el('btn-unban-custom'));
                }
              });

              // bans: s.bans = the count, s.banRows = the carried rows (label = the Swing pick label)
              const renderBans = (s) => {
                put(CC.el('bans-count'), s.bans | 0);
                const rows = Array.isArray(s.banRows) ? s.banRows : [];
                const sig = JSON.stringify(rows);
                if (sig !== banSig) {
                  banSig = sig;
                  CC.el('bans-list').replaceChildren(...rows.map((b) => {
                    const li = CC.tpl('tpl-ban-row').firstElementChild;
                    CC.text(CC.q('.ban-label', li),
                      b.label ? b.label : '#' + b.index + ' ' + b.kind + ' ' + b.keyAbbrev);
                    CC.q('.unban', li).addEventListener('click', (e) => V.unbanRow(b, e.currentTarget));
                    return li;
                  }));
                }
                const more = s.bansMore | 0;
                const moreEl = CC.el('bans-more');
                put(moreEl, more > 0 ? '(' + more + ' more not listed)' : '');
                CC.show(moreEl, more > 0);
                const empty = CC.el('bans-empty');
                if (empty) {
                  put(empty, NO_BANS);
                  CC.show(empty, rows.length === 0);
                }
              };
              // kept for everyone + exported not kept yet: textContent only, counts and over-cap words in the headings
              const renderKept = (s) => {
                const kept = idList(s.customKeptIds);
                const inbox = idList(s.customInboxIds);
                put(CC.el('kept-title'), 'Kept for everyone (' + (s.customKept | 0) + ')'
                  + (s.customKeptOverCap === true ? OVER_CAP : ''));
                put(CC.el('inbox-title'), 'Exported, not kept yet (' + inbox.length + ')'
                  + (s.customInboxOverCap === true ? OVER_CAP : ''));
                const sig = JSON.stringify([kept, inbox]);
                if (sig !== keptSig) {
                  keptSig = sig;
                  CC.el('kept-list').replaceChildren(...kept.map((stem) => {
                    const li = CC.tpl('tpl-kept-row').firstElementChild;
                    CC.text(CC.q('.kept-label', li), stem);
                    CC.q('.forget', li).addEventListener('click', (e) => V.forgetRow(stem, e.currentTarget));
                    return li;
                  }));
                  CC.el('inbox-list').replaceChildren(...inbox.map((name) => {
                    const li = CC.tpl('tpl-inbox-row').firstElementChild;
                    CC.text(CC.q('.inbox-label', li), name);
                    CC.q('.keep', li).addEventListener('click', (e) => V.keepRow(name, e.currentTarget));
                    return li;
                  }));
                }
                const empty = CC.el('kept-empty');
                put(empty, NO_KEPT);
                CC.show(empty, kept.length === 0);
              };
              // the ships that come with the server: one row per shipped id, a hidden one struck through with Show
              // in place of Hide (a hidden id the shipped list lacks still gets its row, so it can be shown again);
              // textContent only, the count and the over-cap words in the heading, the last verdict under the list
              const renderShips = (s) => {
                const base = idList(s.shipsBaseIds);
                const hidden = idList(s.shipsHiddenIds);
                const hiddenFolds = new Set(hidden.map((id) => id.toLowerCase()));
                const baseFolds = new Set(base.map((id) => id.toLowerCase()));
                const rows = base.map((id) => ({id, hidden: hiddenFolds.has(id.toLowerCase())}))
                  .concat(hidden.filter((id) => !baseFolds.has(id.toLowerCase())).map((id) => ({id, hidden: true})));
                put(CC.el('ships-title'), SHIPS_TITLE + ' (' + base.length + ')'
                  + (s.shipsOverCap === true ? OVER_CAP : ''));
                const sig = JSON.stringify(rows);
                if (sig !== shipsSig) {
                  shipsSig = sig;
                  CC.el('ships-list').replaceChildren(...rows.map((r) => {
                    const li = CC.tpl('tpl-ship-row').firstElementChild;
                    CC.text(CC.q('.ship-label', li), r.id);
                    li.classList.toggle('ship-hidden', r.hidden);
                    const hide = CC.q('.ship-hide', li);
                    const show = CC.q('.ship-show', li);
                    CC.show(hide, !r.hidden);
                    CC.show(show, r.hidden);
                    hide.addEventListener('click', (e) => V.shipHide(r.id, e.currentTarget));
                    show.addEventListener('click', (e) => V.shipShow(r.id, e.currentTarget));
                    return li;
                  }));
                }
                const empty = CC.el('ships-empty');
                put(empty, NO_SHIPS);
                CC.show(empty, rows.length === 0);
                const last = typeof s.shipsLast === 'string' && s.shipsLast !== '-' ? s.shipsLast : '';
                const failed = typeof s.shipsPersistFailed === 'number' ? s.shipsPersistFailed : 0;
                const line = [last, failed > 0 ? SHIPS_SAVE_FAILED + failed : ''].filter((t) => t).join(' ');
                const lastEl = CC.el('ships-last');
                put(lastEl, line);
                CC.show(lastEl, line !== '');
              };
              const renderCustoms = (s) => {
                put(CC.el('customs-summary'), s.customs || 'customs: -');
                put(CC.el('customs-detail'), s.customsDetail || '-');
                const box = CC.el('customs-accept');
                if (box.getAttribute('aria-busy') !== 'true') {
                  box.checked = s.customSubmissions === true;
                }
              };
              // the MATCH line's MagicLib sentence (the server words it; '' or absent = no line)
              const renderMagicSeam = (s) => {
                const line = typeof s.magicSeamLine === 'string' ? s.magicSeamLine : '';
                const el = CC.el('magic-seam');
                put(el, line);
                CC.show(el, line !== '');
              };
              // the MATCH line's player-ship sentence (the server words it; '' or absent = no line)
              const renderPilotShip = (s) => {
                const line = typeof s.pilotShipLine === 'string' ? s.pilotShipLine : '';
                const el = CC.el('pilot-ship');
                put(el, line);
                CC.show(el, line !== '');
              };

              CC.register({
                name: 'players',
                onStatus: (s) => {
                  renderSeats(s);
                  renderBans(s);
                  renderKept(s);
                  renderShips(s);
                  renderCustoms(s);
                  renderMagicSeam(s);
                  renderPilotShip(s);
                  noteWatch(s);
                  applyBar();
                },
                // Join from this PC and Launch debug WATCH show on a Windows server only; elsewhere one line says why
                onConfig: (c) => {
                  if (c) {
                    CC.show(CC.el('btn-join'), c.joinHere === true);
                    CC.show(CC.el('btn-watch'), c.joinHere === true);
                    CC.show(CC.el('join-linux'), c.joinHere === false);
                    // the repository console's join button opens the kit joiner window instead
                    if (c.packaged === false) {
                      CC.text(CC.el('btn-join'), 'Joiner GUI');
                    }
                    // the repository console's WATCH is the observer arm, shown whatever the host
                    repoConsole = c.packaged === false;
                    if (repoConsole) {
                      CC.show(CC.el('btn-watch'), true);
                      CC.text(CC.el('btn-watch'), 'Arm WATCH (observer)');
                      CC.text(CC.el('tip-watch'), REPO_WATCH_TIP);
                    }
                  }
                }
              });

              // the palette's bar verbs: each run is the press itself (gate re-checked, the same confirms)
              if (CC.palette && typeof CC.palette.add === 'function') {
                for (const [label, run] of [
                  ['Start server', V.start], ['Launch debug WATCH', V.watch],
                  ['End round', V.endRound], ['End match', V.endMatch],
                  ['Kill (fallback)', V.kill], ['Sim-speed: Set', V.simSet],
                  ['Sim-speed: auto on', () => V.auto(true)], ['Sim-speed: auto off', () => V.auto(false)],
                  ['Kick selected', V.kick], ['Ban player', () => V.ban('ban', 'btn-ban-player')],
                  ['Ban source', () => V.ban('ban-src', 'btn-ban-source')], ['Unban player', V.unbanPlayer],
                  ['Reject seat', () => V.seatVerb('custom-reject', 'btn-reject-seat', gate.seat)],
                  ['Allow seat', () => V.seatVerb('custom-allow', 'btn-allow-seat', gate.seat)],
                  ['Ban custom', () => V.customVerb('custom-ban', 'btn-ban-custom')],
                  ['Export custom', () => V.customVerb('custom-export', 'btn-export-custom')],
                  ['Unban custom', V.unbanCustom],
                  ['Keep for everyone', () => V.customVerb('custom-keep', 'btn-keep-custom')],
                  ['Forget kept', V.forgetKept],
                  ['Accept customs: on', () => V.customs(true)], ['Accept customs: off', () => V.customs(false)]
                ]) {
                  CC.palette.add(label, run);
                }
              }
            }
            """;
}
