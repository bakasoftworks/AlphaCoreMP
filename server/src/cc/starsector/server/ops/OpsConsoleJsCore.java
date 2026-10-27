package cc.starsector.server.ops;

/**
 * The console page's
 * CORE script part - the one top-level {@code CC} object the players and setup parts call, and the
 * page's boot.
 *
 * <p>WHY a separate class: the page's script is three parts on disjoint files
 * (core; players + bar; setup + voice + mods), and {@link OpsConsolePage#js()} joins them in that
 * order into the one {@code /ops.js} the page loads (CSP {@code default-src 'self'}: no inline
 * script), so a {@code const CC} declared here is in scope for both later parts. The member list is
 * the core contract:
 * the console key (address
 * fragment, then {@code sessionStorage}, sent as {@code X-CC-Ops}), {@code CC.api} / {@code CC.send}
 * (the server-minted confirm round trip), the 1 s status poll (one in flight) with the strip, the
 * load rows and the unreachable / wrong-key states, the note line + history, the confirm / error /
 * help / palette dialogs, the section nav and the global keys. The part binds to the ids
 * {@link OpsConsolePageHtml#HTML} pins and writes text only through {@code textContent}
 * ({@code OpsConsolePageSelftest} rows {@code page-csp-clean} + {@code page-text-law} read it).
 * The block carries no backslash, so the Java text block serves it byte-for-byte.
 *
 * <p>{@code _wire} binds {@code #btn-reveal}
 * (Show saved passwords): {@code CC.send('/api/reveal', {descriptor})} through the server's
 * {@code reveal} confirm, then both saved passwords fill {@code #f-password} / {@code #f-admin} shown
 * (type text, each row's Show button pressed - set here directly, the toggle itself lives in the setup
 * part). {@code api}'s abort is {@code isStatus ? 3000 : 35000}: the page's only clock on a stuck
 * POST handler (the server times the request READ only, {@link OpsConsole#REQUEST_CLOCK_S}); it outlasts
 * that ceiling and the Kill's two 8 s polls; {@code send}'s ok branch shows an
 * answer carrying {@code outcome} (the Kill) as its bare {@code detail} sentence. The first
 * status answer opens Setup on a stopped server, Players on a running one
 * ({@code CC.ladder().startEnabled ? 'setup' : 'players'}, picked from the first answer's
 * {@code startEnabled}); {@code _start} arms {@code CC._startTab} after its own
 * Players section and any later {@code section()} call before that answer disarms it, so an operator's
 * own pick wins. {@code _boot}'s post-key steps live in {@code _start()} (runs once,
 * {@code CC._started}); a {@code hashchange} listener in {@code _wire} ignores the same key, adopts a
 * new valid one without a reload (start, or an immediate poll + config re-read) and drops the key on a
 * blank / malformed fragment (the no-key text shows). The block's own comments stay plain words (it is
 * served and exported as source).
 *
 * <p>Comments inside the block carry no repo ids (the block is served and exported).
 *
 * Key adoption re-runs the shown section's part hooks
 * (a part whose load failed under the old key would otherwise keep its HTML defaults).
 *
 * <p>{@code _wire} applies the saved colour scheme before the key
 * check, so the no-key page follows it too: {@code localStorage} {@code cc.theme} = {@code light} sets
 * {@code data-theme="light"} on the root element, anything else (a blocked storage included) leaves the
 * stylesheet's dark default; {@code #theme-toggle} flips it and saves {@code light} or removes the key, every
 * storage access caught into {@code CC._fault('theme', x)} so a SecurityError never ends the wiring.
 * {@code CC.api} takes an optional third {@code opts}: {@code quiet} skips the error dialog on a refusal (the
 * setup part's background draft save notes a refusal itself; the rejection object is unchanged) and
 * {@code keepalive} sets the fetch's own {@code keepalive} (the draft flush on {@code pagehide}). A two-argument
 * call behaves exactly as before.
 *
 * <p>{@code _renderStrip} shows {@code package <stamp>} in
 * {@code #pack-line} when the status answer carries {@code pack} (a packed folder), and hides it otherwise.
 *
 * <p>{@code refreshConfig} runs {@code CC.packageChrome(c)} BEFORE the
 * parts' {@code onConfig}, so the one page serves both
 * modes: the {@link OpsParity#PACKAGE_ONLY_IDS} controls show
 * only when {@code c.packaged === true}, the {@link OpsParity#OWNER_IDS} controls only when
 * {@code c.packaged === false}; each control's {@code label[for]} follows it, and its {@code .frow} follows when
 * every {@code data-cc} in that row is a listed id or a dotted part of one (the Game folder and Recording rows
 * hide whole; the map row keeps half size / station / backdrop). The two literal arrays copy those lists (the
 * served block cannot read Java); the setup part's own toggles give the same verdict and run after.
 */
final class OpsConsoleJsCore {

    private OpsConsoleJsCore() {
    }

    /** The core script part: defines {@code CC} and boots the page at {@code DOMContentLoaded}. */
    static final String JS = """
'use strict';
const CC = {
  // the console key (string or null); read once at boot
  token: null,
  // the last /api/status answer, or null
  status: null,
  // the last /api/config answer, or null
  config: null,
  // the shown section: players, setup or mods
  sectionName: null,
  // the picked seat {seatId, clientId, cidKey, custom} or null; the players part assigns it
  pick: null,
  // returns the /api/start fields; the setup part replaces this default (null = nothing to send)
  startForm: () => {
    CC.note('the setup form is not loaded');
    return null;
  },
  // document.getElementById
  el: (id) => document.getElementById(id),
  // querySelector under root (default the document)
  q: (sel, root) => (root || document).querySelector(sel),
  // querySelectorAll under root, as an Array
  qa: (sel, root) => Array.from((root || document).querySelectorAll(sel)),
  // the one text write: textContent (null or undefined = empty)
  text: (el, s) => {
    if (el) {
      el.textContent = s === null || s === undefined ? '' : String(s);
    }
  },
  // hidden = !on
  show: (el, on) => {
    if (el) {
      el.hidden = !on;
    }
  },
  // a deep clone of a template's content (a DocumentFragment)
  tpl: (id) => {
    const t = CC.el(id);
    return t ? t.content.cloneNode(true) : document.createDocumentFragment();
  },

  // GET (fields undefined) or form POST with the key header; resolves the JSON or rejects {kind}.
  // opts (optional): quiet = a refusal opens no error dialog (a background save notes it instead;
  // the wrong-key and unreachable strip states still show); keepalive = the request outlives a
  // closing page
  api(path, fields, opts) {
    const o = opts || {};
    if (!CC.token) {
      return Promise.reject(CC._rej({kind: 'wrongkey'}));
    }
    const isStatus = path.split('?')[0] === '/api/status';
    const ctl = new AbortController();
    // a poll gives up at 3 s; a POST outlasts the server's own request clock and a Kill's two waits
    const timer = setTimeout(() => ctl.abort(), isStatus ? 3000 : 35000);
    const init = {method: 'GET', headers: {'X-CC-Ops': CC.token}, cache: 'no-store', signal: ctl.signal};
    if (o.keepalive === true) {
      init.keepalive = true;
    }
    if (fields !== undefined) {
      init.method = 'POST';
      init.headers['Content-Type'] = 'application/x-www-form-urlencoded';
      init.body = CC._form(fields);
    }
    return fetch(path, init)
      .then((r) => r.text().then((t) => [r.status, t]))
      .then((res) => {
        const code = res[0];
        const body = res[1];
        if (code === 403) {
          CC._setNet('wrongkey');
          throw CC._rej({kind: 'wrongkey'});
        }
        let a = null;
        try {
          a = JSON.parse(body);
        } catch (x) {
          a = null;
        }
        if (a === null || typeof a !== 'object') {
          a = {title: 'Error', error: String(body || '').slice(0, 2000) || 'HTTP ' + code};
        } else if (code !== 200 && !a.error) {
          a = {title: 'Error', error: 'HTTP ' + code};
        }
        if (a.error) {
          const title = a.title ? String(a.title) : 'Error';
          if (!isStatus && o.quiet !== true) {
            CC.dialogError(title, a.error);
          }
          throw CC._rej({kind: 'refused', title: title, error: String(a.error),
            fields: Array.isArray(a.fields) ? a.fields : []});
        }
        return a;
      }, () => {
        CC._setNet('unreachable');
        throw CC._rej({kind: 'unreachable'});
      })
      .finally(() => clearTimeout(timer));
  },

  // POST through the server's confirms; resolves the answer, or null on a Cancel / nothing to send
  send(path, fields, opts) {
    const o = opts || {};
    if (fields === null) {
      return Promise.resolve(null);
    }
    const unbusy = o.busy ? CC._busy(o.busy) : () => {};
    const base = Object.assign({}, fields || {});
    const given = base.confirmed === undefined ? [] : [].concat(base.confirmed).map(String);
    delete base.confirmed;
    let rounds = 0;
    const post = () => {
      const f = Object.assign({}, base);
      if (given.length) {
        f.confirmed = given.slice();
      }
      return CC.api(path, f).then((a) => {
        if (a && Array.isArray(a.needsConfirm) && a.needsConfirm.length) {
          const fresh = a.needsConfirm.filter((c) => c && given.indexOf(String(c.key)) < 0);
          rounds += 1;
          if (!fresh.length || rounds > 8) {
            CC.note('the server asked again for a confirm already given - nothing more sent');
            return null;
          }
          return CC._confirmAll(fresh).then((ok) => {
            if (!ok) {
              CC._hint('cancelled - nothing sent');
              return null;
            }
            fresh.forEach((c) => given.push(String(c.key)));
            return post();
          });
        }
        if (a && a.ok) {
          let text = 'sent';
          if (o.noteText !== undefined) {
            text = o.noteText;
          } else if (typeof a.outcome === 'string' && typeof a.detail === 'string') {
            // an outcome answer (Kill): its sentence shows bare
            text = a.detail;
          } else if (a.detail !== undefined) {
            text = "sent '" + a.detail + "'";
          } else if (typeof a.note === 'string') {
            text = a.note;
          }
          const h = CC.note(text);
          if (typeof o.echo === 'function') {
            CC._echoes.push({fn: o.echo, h: h, left: 5});
          }
        }
        return a;
      });
    };
    return post().finally(unbusy);
  },

  // one confirm on #dlg-confirm; resolves true only on OK (a click or a fresh Enter)
  confirm(spec) {
    const p = CC._confirmChain.then(() => CC._confirmOpen(spec || {}));
    CC._confirmChain = p.then(() => null, () => null);
    return p;
  },

  // the copyable error dialog (queued when one is already open); resolves when it closes
  dialogError(title, text) {
    return new Promise((resolve) => {
      CC._errQ.push({title: title ? String(title) : 'Error', text: text, resolve: resolve});
      const d = CC.el('dlg-error');
      if (CC._errQ.length === 1 && !(d && d.open)) {
        CC._errNext();
      }
    });
  },

  // the note line + its history (newest first, max 20); returns the note's handle
  note(text) {
    CC._noteN += 1;
    const h = {serial: CC._noteN, text: String(text), stamp: CC.fmtTime(new Date()),
      li: document.createElement('li')};
    CC.text(CC.el('note'), h.text);
    CC.text(h.li, h.stamp + ' ' + h.text);
    const list = CC.el('note-list');
    if (list) {
      list.insertBefore(h.li, list.firstChild);
      while (list.children.length > 20) {
        list.removeChild(list.lastChild);
      }
    }
    return h;
  },

  // GET /api/config, then the mode chrome, then every part's onConfig(c); resolves c, or null on a failure
  refreshConfig() {
    return CC.api('/api/config').then((c) => {
      CC.config = c;
      CC.packageChrome(c);
      CC._modules.forEach((m) => CC._call(m, 'onConfig', c));
      return c;
    }, () => null);
  },

  // The mode chrome, the desktop window's own rule: a control only one mode has is hidden in the other.
  // Each listed control is shown or hidden with its label; its form row follows when every control in
  // that row is a listed one (or a part of one), so a row of package-only controls hides whole while a
  // mixed row keeps its other controls. The parts' own onConfig runs after this and decides last.
  packageChrome(c) {
    if (!c) {
      return;
    }
    const apply = (on, ids) => {
      const listed = (v) => ids.some((id) => v === id || v.startsWith(id + '.'));
      ids.forEach((id) => {
        CC.qa('[data-cc="' + id + '"]').forEach((el) => {
          CC.show(el, on);
          if (el.id) {
            CC.qa('label[for="' + el.id + '"]').forEach((lab) => CC.show(lab, on));
          }
          const row = el.closest('.frow');
          if (row && CC.qa('[data-cc]', row).every((x) => listed(x.getAttribute('data-cc')))) {
            CC.show(row, on);
          }
        });
      });
    };
    // shown on a server package only
    apply(c.packaged === true, ['setup.gameRoot', 'setup.map.deployCr', 'setup.map.deployCrApply',
      'setup.record', 'setup.shapeNew']);
    // shown on the repository console only
    apply(c.packaged === false, ['verb.devEditor']);
  },

  // a part: {name, onStatus(s), onConfig(c), onShow(name), onHide(name)}, every hook optional
  register(m) {
    CC._modules.push(m);
    return m;
  },

  // show one section (players, setup, mods), press its nav button, run the parts' hooks
  section(name) {
    const secs = {players: CC.el('sec-players'), setup: CC.el('sec-setup'), mods: CC.el('sec-mods')};
    if (!Object.prototype.hasOwnProperty.call(secs, name)) {
      return;
    }
    // any section shown before the first status answer (an operator's own pick) wins over the first-tick pick
    CC._startTab = false;
    const prev = CC.sectionName;
    CC.sectionName = name;
    Object.keys(secs).forEach((n) => CC.show(secs[n], n === name));
    CC.qa('#nav button[data-section]').forEach((b) => {
      b.setAttribute('aria-pressed', b.dataset.section === name ? 'true' : 'false');
    });
    if (prev !== null && prev !== name) {
      CC._modules.forEach((m) => CC._call(m, 'onHide', prev));
    }
    CC._modules.forEach((m) => CC._call(m, 'onShow', name));
  },

  // the enable ladder (the Swing window's law); all false without a status or while cut off
  ladder() {
    const s = CC.status;
    if (!s || CC._net) {
      return {startEnabled: false, commandable: false, killEnabled: false, bans: 0, pickable: false};
    }
    const commandable = !!s.commandable;
    return {
      startEnabled: !!s.startEnabled && !s.staging && !s.launcherUp,
      commandable: commandable,
      killEnabled: !!s.killEnabled || !!s.launcherUp,
      bans: s.bans | 0,
      // pickable = live seat rows a seat verb may act on (not last match's remembered rows)
      pickable: commandable && !s.seatsRemembered && Array.isArray(s.seats) && s.seats.length > 0
    };
  },

  // disabled = !on (kept while busy); why = the reason shown on the note line when it is reached
  enable(el, on, why) {
    if (!el) {
      return;
    }
    el.dataset.on = on ? '1' : '0';
    el.disabled = !on || el.getAttribute('aria-busy') === 'true';
    if (!on && why) {
      el.dataset.why = String(why);
    } else {
      delete el.dataset.why;
    }
    if (!CC._whyBound.has(el)) {
      CC._whyBound.add(el);
      el.addEventListener('focus', () => {
        if (el.disabled && el.dataset.why) {
          CC._hint(el.dataset.why);
        }
      });
    }
  },

  // 'last sent: <value> <hh:mm>' into an output
  lastSent(outputEl, value) {
    CC.text(outputEl, 'last sent: ' + value + ' ' + CC.fmtTime(new Date()));
  },

  // hh:mm, local time
  fmtTime(date) {
    const d = date || new Date();
    return CC._two(d.getHours()) + ':' + CC._two(d.getMinutes());
  },

  // a byte count in decimal units, the Swing text: 580 B, 6.8 KB, 6.8 MB, 1.25 GB
  fmtBytes(n) {
    const b = Math.trunc(Number(n) || 0);
    if (b < 1000) {
      return b + ' B';
    }
    if (b < 1000000) {
      return CC._fix(b / 1000, 1) + ' KB';
    }
    if (b < 1000000000) {
      return CC._fix(b / 1000000, 1) + ' MB';
    }
    return CC._fix(b / 1000000000, 2) + ' GB';
  },

  // fmtBytes + '/s'
  fmtBps(n) {
    return CC.fmtBytes(n) + '/s';
  },

  // a global key (e.key; opts.ctrl = with Ctrl or Cmd); never in a field or with a dialog open
  key(key, fn, opts) {
    CC._keys.push({key: String(key).toLowerCase(), fn: fn, ctrl: !!(opts && opts.ctrl)});
  },

  // the command palette's entries: add(label, run)
  palette: {
    items: [],
    // one entry: its label (fuzzy-matched) and the function Enter or a click runs
    add(label, run) {
      CC.palette.items.push({label: String(label), run: run});
    }
  },

  // internal state below; the other parts call only the members above
  _modules: [],
  // true once Close ops GUI was confirmed and answered: the strip reads OPS GUI CLOSED, polling stops
  _quit: false,
  _keys: [],
  _echoes: [],
  _errQ: [],
  _faults: {},
  _whyBound: new WeakSet(),
  _confirmChain: Promise.resolve(),
  _cf: null,
  _pal: {shown: [], at: 0},
  _net: null,
  _noteN: 0,
  _inFlight: false,
  _startTab: false,
  _started: false,
  _lastGood: null,
  _t0: null,
  _briefKey: '',
  _failSeen: null,
  _enterDown: false,
  _echoText: ' - seen in status',
  _states: ['run', 'listening', 'starting', 'stale', 'gone', 'failed', 'refused', 'exited', 'stopped',
    'unreachable', 'wrongkey'],

  // tags a rejection as already shown (dialog or strip) so the page swallows it when unhandled
  _rej(o) {
    o.cc = true;
    return o;
  },

  // counts a fault per place; the first one per place goes to the browser console
  _fault(where, x) {
    CC._faults[where] = (CC._faults[where] || 0) + 1;
    if (CC._faults[where] === 1) {
      console.error('cc fault ' + where, x);
    }
  },

  // runs a part's hook, counting a throw
  _call(m, hook, arg) {
    if (m && typeof m[hook] === 'function') {
      try {
        m[hook](arg);
      } catch (x) {
        CC._fault(String(m.name) + '.' + hook, x);
      }
    }
  },

  // a form body; an Array value repeats its key, null / undefined values are left out
  _form(fields) {
    const p = new URLSearchParams();
    Object.keys(fields || {}).forEach((k) => {
      const v = fields[k];
      (Array.isArray(v) ? v : [v]).forEach((x) => {
        if (x !== undefined && x !== null) {
          p.append(k, String(x));
        }
      });
    });
    return p.toString();
  },

  // aria-busy + disabled until the returned function runs (then the ladder's last word)
  _busy(btn) {
    const was = btn.disabled;
    btn.setAttribute('aria-busy', 'true');
    btn.disabled = true;
    return () => {
      btn.removeAttribute('aria-busy');
      btn.disabled = btn.dataset.on === undefined ? was : btn.dataset.on !== '1';
    };
  },

  // the note line only (no history entry)
  _hint(text) {
    CC._noteN += 1;
    CC.text(CC.el('note'), text);
  },

  // two digits
  _two(n) {
    return (n < 10 ? '0' : '') + n;
  },

  // hh:mm:ss, local time
  _clock(d) {
    return CC._two(d.getHours()) + ':' + CC._two(d.getMinutes()) + ':' + CC._two(d.getSeconds());
  },

  // the Swing %.Nf text: half-up on the shortest decimal form of the number
  _fix(x, d) {
    const n = Number(x) || 0;
    const r = Number(Math.round(Number(Math.abs(n) + 'e' + d)) + 'e-' + d);
    return ((n < 0 ? -1 : 1) * (isFinite(r) ? r : Math.abs(n))).toFixed(d);
  },

  // sessionStorage write (null = remove)
  _store(k, v) {
    try {
      if (v === null) {
        sessionStorage.removeItem(k);
      } else {
        sessionStorage.setItem(k, v);
      }
    } catch (x) {
      CC._fault('storage', x);
    }
  },

  // sessionStorage read (null when absent or blocked)
  _recall(k) {
    try {
      return sessionStorage.getItem(k);
    } catch (x) {
      CC._fault('storage', x);
      return null;
    }
  },

  // the console key's shape (the printed address fragment)
  _isKey(h) {
    return /^[A-Za-z0-9_-]{20,200}$/.test(h);
  },

  // the key: the address fragment (then stripped from the address bar), else this tab's stored one
  _readToken() {
    const h = location.hash ? location.hash.slice(1) : '';
    if (location.hash) {
      try {
        history.replaceState(null, '', location.pathname + location.search);
      } catch (x) {
        CC._fault('replaceState', x);
      }
    }
    if (CC._isKey(h)) {
      if (CC._recall('cc.token') !== h) {
        CC._store('cc.failSeen', null);
      }
      CC._store('cc.token', h);
      return h;
    }
    return CC._recall('cc.token');
  },

  // one poll tick: one request in flight; a tick that finds one still out marks the view stale
  _tick() {
    if (CC._quit) {
      return; // the ops GUI was closed from this page: no more polls, the strip says OPS GUI CLOSED
    }
    if (CC._inFlight) {
      CC._stale();
      return;
    }
    CC._inFlight = true;
    const up = CC.el('uplink');
    CC.api('/api/status?uplink=' + encodeURIComponent(up ? up.value : ''))
      .then((s) => CC._onStatus(s), (r) => CC._onPollFail(r))
      .catch((x) => CC._fault('poll', x))
      .finally(() => {
        CC._inFlight = false;
      });
  },

  // a status answer: strip, load, brief, launch dialog, every part's onStatus, the echo watch
  _onStatus(s) {
    if (!s || typeof s !== 'object') {
      CC._stale();
      return;
    }
    CC.status = s;
    CC._net = null;
    CC._lastGood = new Date();
    CC.show(CC.el('asof'), false);
    CC._dim(false);
    /* The first status answer picks the opening section the way the desktop window does: a stopped
       server opens on Setup, a running one on Players. The address only ever carries the console key
       (see _readToken), never a section, so there is no section-from-address case to honour here. */
    if (CC._startTab) {
      CC._startTab = false;
      CC.section(CC.ladder().startEnabled ? 'setup' : 'players');
    }
    CC._renderStrip();
    CC._renderLoad(s);
    CC._renderBrief(s.brief);
    CC._launchDialog(s);
    CC._modules.forEach((m) => CC._call(m, 'onStatus', s));
    CC._runEchoes(s);
  },

  // a failed poll: stale view; a refused status answer shows its text on the detail line
  _onPollFail(r) {
    CC._stale();
    if (r && r.kind === 'refused') {
      CC.text(CC.el('detail'), r.error);
    }
  },

  // 'as of <last good answer>' shown, seat rows and load dimmed
  _stale() {
    if (CC._lastGood) {
      const t = CC.el('asof-time');
      CC.text(t, CC._clock(CC._lastGood));
      if (t) {
        t.setAttribute('datetime', CC._lastGood.toISOString());
      }
      CC.show(CC.el('asof'), true);
    }
    CC._dim(true);
  },

  // the .dim class on the seat rows and the load group
  _dim(on) {
    const rows = CC.el('seats-body');
    const load = CC.el('load');
    if (rows) {
      rows.classList.toggle('dim', on);
    }
    if (load) {
      load.classList.toggle('dim', on);
    }
  },

  // unreachable / wrongkey from a request; the parts re-read the (all-off) ladder at the change
  _setNet(kind) {
    CC._stale();
    if (CC._net === kind) {
      return;
    }
    CC._net = kind;
    CC._renderStrip();
    if (CC.status) {
      CC._modules.forEach((m) => CC._call(m, 'onStatus', CC.status));
    }
  },

  // the strip: data-state, headline (+ elapsed seconds while starting), detail
  _renderStrip() {
    const s = CC.status;
    let state;
    let head;
    let detail;
    if (CC._net === 'wrongkey') {
      state = 'wrongkey';
      head = 'WRONG KEY';
      detail = 'This tab holds a key the console does not accept. Add # and the key from run/console.token (in the server folder) to this address, or open the address the console printed.';
    } else if (CC._net === 'unreachable') {
      state = 'unreachable';
      // after Close ops GUI the dark page is on purpose: say so, and how to come back
      head = CC._quit ? 'OPS GUI CLOSED' : 'CONSOLE UNREACHABLE';
      detail = CC._quit
        ? 'Closed from this page. A server it started keeps running. To open the ops GUI again, double-click ops-web.bat (sh ops-web.sh on Linux): it opens this page with the key.'
        : 'Is the console still running? Over ssh: is the tunnel up?';
    } else if (!s) {
      return;
    } else {
      // a followed launch (STARTING, FAILED, REFUSED, STOPPED) replaces the snapshot's three texts
      const l = s.launch && s.launch.headline ? s.launch : null;
      state = l ? l.stateKey : s.stateKey;
      head = l ? l.headline : s.headline;
      detail = l ? l.text : s.detail;
    }
    if (CC._states.indexOf(state) < 0) {
      state = 'stopped';
    }
    if (state === 'starting') {
      if (CC._t0 === null) {
        CC._t0 = Date.now();
      }
      head = head + ' ' + Math.floor((Date.now() - CC._t0) / 1000) + 's';
    } else if (!CC._net) {
      CC._t0 = null;
    }
    const strip = CC.el('strip');
    if (strip) {
      strip.dataset.state = state;
    }
    CC.text(CC.el('headline'), head);
    CC.text(CC.el('detail'), detail);
    // the folder's package version beside the headline; a folder that is not packed shows none
    const pk = CC.el('pack-line');
    const pack = s && typeof s.pack === 'string' ? s.pack : '';
    if (pk) {
      pk.hidden = !pack;
      CC.text(pk, pack ? 'package ' + pack : '');
    }
  },

  // the load group: the two bars, worst frame, rates, phases, totals
  _renderLoad(s) {
    const m = s.meters && typeof s.meters === 'object' ? s.meters : null;
    const over = !!s.matchOver;
    const um = CC.el('util-meter');
    const em = CC.el('egress-meter');
    if (um) {
      um.value = Number(s.utilValue) || 0;
    }
    if (em) {
      em.value = Number(s.egressValue) || 0;
    }
    if (s.utilTitle) {
      CC.text(CC.q('#util .lcap'), s.utilTitle);
    }
    CC.text(CC.el('util-text'), s.utilText);
    CC.text(CC.el('workmax-text'), s.workMaxText);
    CC.text(CC.el('egress-text'), s.egressText);
    // the texts of the default (V3) load rows:
    // rates "-" at match over, the totals row live and over alike.
    CC._loadRow(CC.el('load-rates'), !m || over ? '-'
      : CC.fmtBps(m.sentBps) + ' out, ' + CC.fmtBps(m.recvBps) + ' in');
    CC._loadRow(CC.el('load-phases'), !m || over ? '-' : CC._phaseText(m));
    CC._loadRow(CC.el('load-totals'), !m ? '-'
      : CC.fmtBytes(m.bytesSentTotal) + ' / ' + CC.fmtBytes(m.bytesRecvTotal)
        + ', send failures ' + Math.trunc(Number(m.sendFailures) || 0));
  },

  // one load row's output (a row or an output that is absent is skipped)
  _loadRow(row, t) {
    CC.text(row ? CC.q('output', row) : null, t);
  },

  // the Swing phase text: step / comp / send mean/p95 ms, every other phase summed as rest
  _phaseText(m) {
    const ph = m.phases && typeof m.phases === 'object' ? m.phases : {};
    const names = Object.keys(ph);
    if (!(Number(m.phaseFrames) > 0) || !names.length) {
      return 'no phase window';
    }
    const v = {step: [0, 0], comp: [0, 0], send: [0, 0], rest: [0, 0]};
    names.forEach((n) => {
      const p = ph[n] || {};
      const mean = Number(p.meanMs) || 0;
      const p95 = Number(p.p95Ms) || 0;
      if (n === 'step' || n === 'comp' || n === 'send') {
        v[n] = [mean, p95];
      } else {
        v.rest = [v.rest[0] + mean, v.rest[1] + p95];
      }
    });
    return ['step', 'comp', 'send', 'rest']
      .map((k) => k + ' ' + CC._fix(v[k][0], 1) + '/' + CC._fix(v[k][1], 1)).join('  ');
  },

  // the brief box: shown with its title and lines when the status carries one
  _renderBrief(b) {
    const key = b ? JSON.stringify(b) : '';
    if (key === CC._briefKey) {
      return;
    }
    CC._briefKey = key;
    const box = CC.el('brief');
    if (!b || typeof b !== 'object') {
      CC.show(box, false);
      return;
    }
    CC.text(CC.el('brief-title'), b.title);
    const ul = CC.el('brief-body');
    if (ul) {
      ul.replaceChildren();
      (Array.isArray(b.body) ? b.body : []).forEach((line) => {
        const li = document.createElement('li');
        CC.text(li, line);
        ul.appendChild(li);
      });
    }
    CC.show(box, true);
  },

  // the launch-failure dialog, once per failId (remembered for this tab and key)
  _launchDialog(s) {
    const id = Math.trunc(Number(s.failId) || 0);
    const l = s.launch;
    if (id <= 0 || String(id) === CC._failSeen || !l || !l.headline) {
      return;
    }
    CC._failSeen = String(id);
    CC._store('cc.failSeen', CC._failSeen);
    CC.dialogError(l.headline, l.text);
  },

  // each sent verb's echo test, re-run on the next 5 answers; a hit extends its note
  _runEchoes(s) {
    CC._echoes = CC._echoes.filter((w) => {
      let hit = false;
      try {
        hit = !!w.fn(s);
      } catch (x) {
        CC._fault('echo', x);
        return false;
      }
      if (hit) {
        w.h.text += CC._echoText;
        CC.text(w.h.li, w.h.stamp + ' ' + w.h.text);
        if (CC._noteN === w.h.serial) {
          CC.text(CC.el('note'), w.h.text);
        }
        return false;
      }
      w.left -= 1;
      return w.left > 0;
    });
  },

  // every spec confirmed in turn; false at the first one not confirmed
  _confirmAll(specs) {
    return specs.reduce((p, c) => p.then((ok) => (ok ? CC.confirm(c) : false)), Promise.resolve(true));
  },

  // opens #dlg-confirm for one spec; an Enter held down at open must be released first
  _confirmOpen(spec) {
    return new Promise((resolve) => {
      const d = CC.el('dlg-confirm');
      CC.text(CC.el('confirm-title'), spec.title ? spec.title : 'Confirm');
      CC.text(CC.el('confirm-text'), spec.message ? spec.message : '');
      CC._cf = {resolve: resolve, opener: document.activeElement, held: CC._enterDown, upSeen: false};
      try {
        d.showModal();
      } catch (x) {
        CC._fault('confirm', x);
        CC._cf = null;
        resolve(false);
        return;
      }
      const ok = CC.el('confirm-ok');
      if (ok) {
        ok.focus();
      }
    });
  },

  // closes #dlg-confirm with the answer and returns focus to its opener
  _confirmEnd(v) {
    const c = CC._cf;
    if (!c) {
      return;
    }
    CC._cf = null;
    const d = CC.el('dlg-confirm');
    if (d && d.open) {
      d.close();
    }
    CC._refocus(c.opener);
    c.resolve(v);
  },

  // shows the first queued error
  _errNext() {
    const it = CC._errQ[0];
    if (!it) {
      return;
    }
    const d = CC.el('dlg-error');
    CC.text(CC.el('error-title'), it.title);
    CC.text(CC.el('error-text'), it.text);
    CC._modal(d);
    if (!d || !d.open) {
      CC._errQ.shift();
      it.resolve();
      CC._errNext();
    }
  },

  // showModal, remembering the opener
  _modal(d) {
    if (!d || d.open) {
      return;
    }
    d.ccOpener = document.activeElement;
    try {
      d.showModal();
    } catch (x) {
      CC._fault('dialog', x);
    }
  },

  // focus back to an element still on the page
  _refocus(el) {
    if (el && el.isConnected && typeof el.focus === 'function') {
      el.focus();
    }
  },

  // copies text; the note says when the browser refuses
  _copy(text) {
    const fail = () => CC.note('copy failed - select the text');
    if (!navigator.clipboard || !navigator.clipboard.writeText) {
      fail();
      return;
    }
    navigator.clipboard.writeText(String(text)).then(() => CC._hint('copied'), fail);
  },

  // every help dd[data-hint] gets the text of the element its data-hint names
  _fillHints() {
    CC.qa('#dlg-help dd[data-hint]').forEach((dd) => {
      const src = CC.el(dd.dataset.hint);
      if (src) {
        CC.text(dd, src.textContent);
      }
    });
  },

  // opens the help sheet
  _helpOpen() {
    CC._fillHints();
    CC._modal(CC.el('dlg-help'));
  },

  // opens the palette with every entry listed
  _palOpen() {
    const d = CC.el('dlg-palette');
    const inp = CC.el('palette-input');
    if (!d || d.open || !inp) {
      return;
    }
    inp.value = '';
    CC._palFilter();
    CC._modal(d);
    inp.focus();
  },

  // fuzzy: every query character in order, case-insensitive
  _palMatch(label, q) {
    const s = label.toLowerCase();
    let i = 0;
    for (const ch of q.toLowerCase()) {
      i = s.indexOf(ch, i);
      if (i < 0) {
        return false;
      }
      i += 1;
    }
    return true;
  },

  // rebuilds the palette list for the typed query; the first match is highlighted
  _palFilter() {
    const inp = CC.el('palette-input');
    const list = CC.el('palette-list');
    const q = inp ? inp.value : '';
    CC._pal.shown = CC.palette.items.filter((it) => CC._palMatch(it.label, q));
    CC._pal.at = 0;
    if (!list) {
      return;
    }
    list.replaceChildren();
    CC._pal.shown.forEach((it, i) => {
      const li = document.createElement('li');
      li.id = 'palette-opt-' + i;
      li.setAttribute('role', 'option');
      CC.text(li, it.label);
      li.addEventListener('click', () => CC._palRun(i));
      list.appendChild(li);
    });
    CC._palMark();
  },

  // aria-selected on the highlighted entry (and the input's active descendant)
  _palMark() {
    const inp = CC.el('palette-input');
    const lis = CC.qa('li', CC.el('palette-list'));
    lis.forEach((li, i) => li.setAttribute('aria-selected', i === CC._pal.at ? 'true' : 'false'));
    const cur = lis[CC._pal.at];
    if (inp && cur) {
      inp.setAttribute('aria-activedescendant', cur.id);
      cur.scrollIntoView({block: 'nearest'});
    } else if (inp) {
      inp.removeAttribute('aria-activedescendant');
    }
  },

  // closes the palette (focus back to its opener first), then runs the entry
  _palRun(i) {
    const it = CC._pal.shown[i];
    const d = CC.el('dlg-palette');
    const opener = d ? d.ccOpener : null;
    if (d) {
      d.ccOpener = null;
      if (d.open) {
        d.close();
      }
    }
    CC._refocus(opener);
    if (!it) {
      return;
    }
    try {
      const r = it.run();
      if (r && typeof r.then === 'function') {
        r.then(null, (x) => {
          if (!(x && x.cc === true)) {
            CC._fault('palette run', x);
          }
        });
      }
    } catch (x) {
      CC._fault('palette run', x);
    }
  },

  // the global key handler behind CC.key
  _onKey(e) {
    if (e.defaultPrevented || e.isComposing || CC.q('dialog[open]')) {
      return;
    }
    const t = e.target;
    if (t && t.closest && t.closest('input, textarea, select, [contenteditable]')) {
      return;
    }
    const k = String(e.key || '').toLowerCase();
    const ctrl = e.ctrlKey || e.metaKey;
    for (const b of CC._keys) {
      if (b.key !== k || b.ctrl !== ctrl || e.altKey) {
        continue;
      }
      if (!CC.token && b.key !== '?') {
        continue;
      }
      e.preventDefault();
      try {
        b.fn(e);
      } catch (x) {
        CC._fault('key', x);
      }
      return;
    }
  },

  // every DOM listener the core owns
  _wire() {
    document.addEventListener('keydown', (e) => CC._onKey(e));
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        CC._enterDown = true;
      }
    }, true);
    document.addEventListener('keyup', (e) => {
      if (e.key === 'Enter') {
        CC._enterDown = false;
        if (CC._cf) {
          CC._cf.upSeen = true;
        }
      }
    }, true);
    window.addEventListener('blur', () => {
      CC._enterDown = false;
    });
    // a changed address while the page is open: the same key does nothing; a new key is adopted
    // without a reload (the page starts, or polls and re-reads its config at once); a blank or
    // malformed fragment drops the key and shows the no-key text
    window.addEventListener('hashchange', () => {
      const h = location.hash ? location.hash.slice(1) : '';
      const key = CC._readToken();
      if (!CC._isKey(h)) {
        CC.token = null;
        CC._store('cc.token', null);
        CC.show(CC.el('no-key'), true);
        return;
      }
      if (key === CC.token) {
        return;
      }
      CC.token = key;
      CC._failSeen = CC._recall('cc.failSeen');
      CC.show(CC.el('no-key'), false);
      if (!CC._started) {
        CC._start();
        return;
      }
      if (!CC._inFlight) {
        CC._tick();
      }
      CC.refreshConfig();
      // the shown section's parts reload under the new key (a load that failed under the old
      // key left HTML defaults behind, and Apply would send them)
      if (CC.sectionName) {
        CC._modules.forEach((m) => CC._call(m, 'onShow', CC.sectionName));
      }
    });
    window.addEventListener('unhandledrejection', (e) => {
      if (e.reason && e.reason.cc === true) {
        e.preventDefault();
      }
    });
    // a disabled control takes no focus; a press on it shows its reason too
    document.addEventListener('pointerdown', (e) => {
      const c = e.target && e.target.closest ? e.target.closest('[data-why]') : null;
      if (c && c.disabled) {
        CC._hint(c.dataset.why);
      }
    }, true);
    const on = (el, type, fn) => {
      if (el) {
        el.addEventListener(type, fn);
      }
    };
    // Light / Dark: the stylesheet is dark by default and only the saved word light re-binds the
    // colours (any other stored value is ignored). A blocked storage throws on access, so each read
    // and write is caught: a throw here would end this wiring and lose every listener after it.
    // The button names the scheme a press switches to.
    const themeRoot = document.documentElement;
    const themeLabel = () => CC.text(CC.el('theme-toggle'), themeRoot.dataset.theme === 'light' ? 'Dark' : 'Light');
    let themeSaved = null;
    try {
      themeSaved = localStorage.getItem('cc.theme');
    } catch (x) {
      CC._fault('theme', x);
    }
    if (themeSaved === 'light') {
      themeRoot.dataset.theme = 'light';
    } else {
      delete themeRoot.dataset.theme;
    }
    themeLabel();
    on(CC.el('theme-toggle'), 'click', () => {
      const light = themeRoot.dataset.theme !== 'light';
      if (light) {
        themeRoot.dataset.theme = 'light';
      } else {
        delete themeRoot.dataset.theme;
      }
      themeLabel();
      try {
        if (light) {
          localStorage.setItem('cc.theme', 'light');
        } else {
          localStorage.removeItem('cc.theme');
        }
      } catch (x) {
        CC._fault('theme', x);
      }
    });
    on(CC.el('copy-detail'), 'click', () => CC._copy(CC.el('detail').textContent));
    on(CC.el('note-history'), 'click', () => {
      const list = CC.el('note-list');
      const open = list.hidden;
      CC.show(list, open);
      CC.el('note-history').setAttribute('aria-expanded', open ? 'true' : 'false');
    });
    on(CC.el('help-open'), 'click', () => CC._helpOpen());
    on(CC.el('btn-quit'), 'click', () => CC._quitGui());
    CC.palette.add('Close the ops GUI', () => CC._quitGui());
    CC.qa('#nav button[data-section]').forEach((b) => on(b, 'click', () => CC.section(b.dataset.section)));
    on(CC.el('error-copy'), 'click', () => CC._copy(CC.el('error-text').textContent));
    // Show saved passwords: after the server's confirm, the picked shape's saved match and admin
    // passwords fill their two fields, shown as text with each row's Show button pressed
    on(CC.el('btn-reveal'), 'click', () => {
      const shape = CC.el('f-shape');
      CC.send('/api/reveal', {descriptor: shape ? shape.value : ''},
        {busy: CC.el('btn-reveal'), noteText: 'saved passwords shown'}).then((a) => {
        if (!a || !a.ok) {
          return;
        }
        [['f-password', a.password], ['f-admin', a.adminPassword]].forEach((p) => {
          const f = CC.el(p[0]);
          if (!f || typeof p[1] !== 'string') {
            return;
          }
          f.value = p[1];
          f.type = 'text';
          CC.qa('#pre-start button.show[aria-controls="' + p[0] + '"]')
            .forEach((b) => b.setAttribute('aria-pressed', 'true'));
        });
      }, () => null);
    });
    // help, palette, error: focus back to the opener on close; the error queue moves on
    [CC.el('dlg-help'), CC.el('dlg-palette'), CC.el('dlg-error')].forEach((d) => on(d, 'close', () => {
      if (d.open) {
        return;
      }
      const opener = d.ccOpener;
      d.ccOpener = null;
      CC._refocus(opener);
      if (d === CC.el('dlg-error')) {
        const it = CC._errQ.shift();
        if (it) {
          it.resolve();
        }
        CC._errNext();
      }
    }));
    const cd = CC.el('dlg-confirm');
    on(cd, 'keydown', (e) => {
      if (!CC._cf || e.key !== 'Enter') {
        return;
      }
      e.preventDefault();
      if (e.repeat || (CC._cf.held && !CC._cf.upSeen)) {
        return;
      }
      CC._confirmEnd(e.target !== CC.el('confirm-cancel'));
    });
    on(CC.el('confirm-ok'), 'click', (e) => {
      e.preventDefault();
      CC._confirmEnd(true);
    });
    on(CC.el('confirm-cancel'), 'click', (e) => {
      e.preventDefault();
      CC._confirmEnd(false);
    });
    on(cd, 'cancel', (e) => {
      e.preventDefault();
      CC._confirmEnd(false);
    });
    // a close event queued by the previous spec's close lands after the next spec reopened: skip it
    on(cd, 'close', () => {
      if (!cd.open) {
        CC._confirmEnd(false);
      }
    });
    const inp = CC.el('palette-input');
    on(inp, 'input', () => CC._palFilter());
    on(inp, 'keydown', (e) => {
      const n = CC._pal.shown.length;
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault();
        if (n) {
          CC._pal.at = (CC._pal.at + (e.key === 'ArrowDown' ? 1 : n - 1)) % n;
          CC._palMark();
        }
      } else if (e.key === 'Enter') {
        e.preventDefault();
        if (!e.repeat) {
          CC._palRun(CC._pal.at);
        }
      }
    });
  },

  // boot: the listeners and the key, then (with one) the page's run
  _boot() {
    CC._wire();
    CC.token = CC._readToken();
    CC._failSeen = CC._recall('cc.failSeen');
    if (!CC.token) {
      CC.show(CC.el('no-key'), true);
      return;
    }
    CC._start();
  },

  // Close ops GUI: the server's one confirm (its dialog), then the ok answer ends the console and
  // this page goes dark on purpose (OPS GUI CLOSED, no more polls); a Cancel sends nothing
  _quitGui() {
    CC.send('/api/quit', {}, {busy: CC.el('btn-quit'), noteText: 'the ops GUI is closing'}).then((a) => {
      if (a && a.ok) {
        CC._quit = true;
        CC._setNet('unreachable');
      }
    }, () => {});
  },

  // the page's run once a key is known (at boot, or later from a changed address): the players
  // section, the poll, the config and the help fill - only ever once
  _start() {
    if (CC._started) {
      return;
    }
    CC._started = true;
    CC.section('players');
    CC._startTab = true;
    CC._tick();
    setInterval(() => CC._tick(), 1000);
    CC.refreshConfig();
    CC._fillHints();
  }
};

CC.key('1', () => CC.section('players'));
CC.key('2', () => CC.section('setup'));
CC.key('3', () => CC.section('mods'));
CC.key('?', () => CC._helpOpen());
CC.key('k', () => CC._palOpen(), {ctrl: true});
CC.key('/', () => CC._palOpen());
CC.palette.add('Players', () => CC.section('players'));
CC.palette.add('Setup', () => CC.section('setup'));
CC.palette.add('Mods', () => CC.section('mods'));
CC.palette.add('Help', () => CC._helpOpen());
if (document.readyState === 'complete') {
  setTimeout(() => CC._boot(), 0);
} else {
  document.addEventListener('DOMContentLoaded', () => CC._boot());
}
""";
}
