package cc.starsector.server.ops;

/**
 * The console page's
 * SETUP + VOICE + MODS script part. It holds the Setup form: the {@code /api/config} prefill (a field
 * the operator typed in is never overwritten), the two tri-state passwords, the pre-start lock, bind
 * + advertise, the shape pick and {@code CC.startForm} (the {@code /api/start} payload the bar's
 * Start sends). It also holds the five Set-live dials, the voice group (fill, the bandwidth readout,
 * Apply) and the Mods section.
 *
 * <p>WHY a separate class: see {@link OpsConsoleJsCore}. {@link OpsConsolePage#js()} joins this part
 * last, so the {@code CC} object core declares is in scope, and this part calls ONLY the pinned
 * {@code CC} members. It binds to the ids {@link OpsConsolePageHtml#HTML} pins.
 * The
 * bandwidth readout is {@link OpsVoicePanel#readout}'s formula, the Swing panel's twin.
 * <p>Comments inside the block carry no repo ids (the block is served and exported).
 *
 *
 * <p>The package Setup twins - the Game folder field
 * ({@code #f-game-root}) rides {@code SETUP_PREFILL} ({@code /api/config} {@code gameRoot}) and
 * {@code CC.startForm} sends it as {@code gameRoot} (the console's Start saves it into
 * {@code launcher.properties} right before its spawn). Advertise ({@code #f-advertise}, now an input)
 * follows the port: the listener re-derives it only while it starts with
 * the {@code <public IP>} placeholder ({@link OpsSetupPrefill#PKG_PUBLIC_IP}), and the config's
 * {@code advertise} (the saved row, else the placeholder) lands only on an empty / placeholder field - a
 * typed address survives the phase-change re-read. A good brick saves it (Start sends it for the
 * ProtonVPN mode compare only, below).
 * Copy join brick ({@code #btn-brick}) posts {@code /api/brick} ({@code advertise} + the Start form's
 * password tri-state); on ok the brick fills {@code #f-brick} for {@code 45000} ms
 * (= {@link OpsWindowModel#NOTE_TTL_MS}, the status note's life; row {@code page-setup-twins}), the note
 * line names it (plus the not-saved tail when {@code advertiseSaved} is false) and it is copied.
 *
 * <p>Fill from ProtonVPN ({@code #btn-lease}) posts
 * {@code /api/lease} with the port field; the answer's three addresses land through
 * {@code setupApplyFill} (each field marked typed, so a config re-read keeps the fill) and its address
 * becomes {@code setupFollowAddress}: the port listener re-derives Advertise while its address part
 * ({@code setupAddressPart}, {@link ServerOpsLaunch#addressPart}'s rule) still equals it, and
 * {@code setupOnConfig} re-arms it from {@code /api/config} {@code advertiseFrom} = {@code protonvpn}.
 * {@code CC.startForm} now sends {@code advertise} and the brick sends {@code bindHost} - the server's
 * mode compare ({@link ServerOpsLaunch#protonMode}); a brick answer's {@code fill} lands the same way
 * and its {@code note} joins the brick note. {@code CC.applyFill} exposes {@code setupApplyFill} to the
 * other script parts: the Start answer's handler in {@link OpsConsoleJsPlayers} calls it with an accepted
 * answer's {@code fill} (row {@code page-lease}).
 *
 * <p>The typed passwords clear on an ACCEPTED Start only:
 * {@code CC.startAccepted} = {@code setupClearPasswords}, called by the Start answer's
 * handler in {@link OpsConsoleJsPlayers} on {@code {ok}}; a REFUSED Start keeps the typed text so the retry
 * re-sends it (the former settle-based clear in {@code setupOnStatus} emptied the fields on a refusal too, and
 * the retry then sent {@code keep} = the saved OLD password, silently). Row {@code web-start-ok-clears-passwords}.
 *
 * <p>The Battle map row ({@code #map-row}).
 * {@code setupMapOnConfig} fills the four fields from {@code /api/config} {@code matchSettings} when the
 * page's shape pick is the config's own {@code selectedIndex}, else {@code setupMapAsk} re-asks
 * {@code /api/config?descriptor=<pick>} (also on every shape change; an older answer is dropped by a
 * counter); {@code null} hides the row (a console not on a server package). Each Apply
 * ({@code setupMapBind}) posts {@code /api/match-settings} {@code op} + {@code value} + {@code descriptor}
 * through {@code CC.send} (no confirm: the press applies; its {@code note}, naming the next Start as the
 * step that takes the change, lands on the note line), lands the answer's values (typed flags cleared) and
 * on {@code matchTextChanged} re-reads the config (no focus move: a join window fetches the new match
 * itself, so nothing is handed to players). {@code setupMapGate} keeps the station picker
 * and its Apply off for a stationless shape ({@code '-'}) on top of the pre-start lock, re-run by
 * {@code setupOnStatus} (row {@code page-map-row-ids}).
 *
 * <p>Check from the internet
 * ({@code #btn-reach}) posts {@code /api/reach} with {@code bindHost} + {@code bindPort} + {@code advertise}
 * through {@code CC.send} (busy while in flight; the answer's {@code note} lands on the note line);
 * {@code setupOnStatus} fills {@code #reach-line} with {@link ServerOpsCore#REACHABILITY_PREFIX} + the status
 * answer's {@code reach.note} (hidden while {@code reach} is null). The Recording select ({@code #f-record})
 * rides {@code SETUP_PREFILL} ({@code /api/config} {@code record}) and {@code CC.startForm} sends it as
 * {@code record}. {@code modsOp} shows an answer's {@code note} (a package Remove's
 * {@link ServerOpsCore#modsRemovedLine}). Row {@code page-n-ids}.
 *
 * <p>{@code setupPackaged} =
 * {@code /api/config} {@code packaged}, set in {@code setupOnConfig} BEFORE the shape fill; it shows the
 * package-only parts ({@code #shape-new}, {@code #hint-one-password}, {@code #players-ctl}). Clear-on-switch: the
 * {@code f-shape} listener calls {@code setupApplyShape(true)}, which runs {@code setupClearPasswords()} FIRST
 * off a package only (owner mode keeps per-shape passwords, the Swing re-prefill law); {@code setupFillShapes}
 * calls it with no argument, so a config re-read never clears a typed password, and a package keeps its ONE
 * password across a switch (row {@code web-owner-switch-clears-passwords}). Create posts
 * {@code /api/shape-new} ({@code name base half cr terrain}); ok = the server's created {@code text}, the config re-read, the new
 * stem picked (Based on mirrors the offered list in {@code setupFillShapes}).
 *
 * <p>Roster x ({@code #f-roster-mult}) rides {@code SETUP_PREFILL}
 * ({@code /api/config} {@code waveRosterMultiplier}) and {@code CC.startForm} sends it as
 * {@code waveRosterMultiplier}.
 *
 * <p>Seats per address ({@code #f-seats-per-source}) and All skills
 * ({@code #f-all-skills}) ride {@code SETUP_PREFILL} ({@code /api/config} {@code seatsPerSource} / {@code allSkills})
 * and {@code CC.startForm} sends them as {@code seatsPerSource} / {@code allSkills}.
 *
 * <p>Deterministic replay pins (RNG) ({@code #f-rng-pins}) rides
 * {@code SETUP_PREFILL} ({@code /api/config} {@code rngPins}) and {@code CC.startForm} sends it as {@code rngPins}.
 *
 * <p>MagicLib abilities follow the pilot ({@code #f-magic-seam}) rides
 * {@code SETUP_PREFILL} ({@code /api/config} {@code magicSeam}) and {@code CC.startForm} sends it as
 * {@code magicSeam} - the replay-pins select's twin.
 *
 * <p>Each pilot's ship counts as the player's ({@code #f-pilot-ship}) rides
 * {@code SETUP_PREFILL} ({@code /api/config} {@code pilotShip}) and {@code CC.startForm} sends it as
 * {@code pilotShip} - the MagicLib select's twin.
 *
 * <p>Main's fold on that lane: the New match shape's Players ({@code #f-new-players}) is
 * prefilled from the picked base's {@code seats} ({@code /api/config} {@code options[].seats}; blank without a
 * decode), refilled when Based on changes, and sent as {@code players} in the {@code /api/shape-new} POST
 * (absent / blank = the base's own count on the server).
 *
 * <p>The setup draft, the Players field and the second game folder.
 * {@code SETUP_DRAFTED} = the {@code SETUP_PREFILL} controls + {@code f-shape} + {@code f-advertise} (never a
 * password control). {@code setupDraftOverlay(c)} runs LAST in {@code setupOnConfig} (after the fill, which would
 * overwrite it): each untouched drafted control takes {@code c.draft[<its data-cc id>]} and counts as touched when the
 * value landed; a restored shape runs {@code setupApplyShape()} (no switch) + {@code setupMapAsk()}; the first config
 * answer of the page load alone may note the restore ({@code c.draftUpdated} non-empty). The host's own edits post
 * {@code /api/draft} {@code id} + {@code value} through {@code CC.api}'s quiet option: {@code input} debounced 750 ms
 * per control, {@code change} at once, one promise chain per control, a value equal to the last one sent skipped, a
 * refusal noted once per reason with no retry; {@code pagehide} sends every waiting value at once with
 * {@code keepalive}; {@code CC.startForm} cancels every waiting save first (the press sends the values, and the
 * server drops the draft after an accepted start). Programmatic writes fire no event, so they never post. The
 * package-only Players field ({@code #players-ctl}) shows on {@code setupPackaged}, prefills from the picked option's
 * {@code seats} at the end of {@code setupApplyShape} (a switch drops a typed count) and applies through
 * {@code setupMapBind} with op {@code players}. The second game folder rides {@code SETUP_PREFILL} as
 * {@code gameRootSecond} and {@code CC.startForm} sends it under that name. Players connect to now carries the
 * typed flag too, so the overlay never writes over an address being typed.
 *
 * <p>The four Setup folds ({@code SETUP_FOLDS}: {@code #setup-debug},
 * {@code #setup-advanced}, {@code #setup-live}, {@code #setup-voice-knobs}). {@code setupFoldsRestore} opens a fold
 * at load only when this browser's {@code localStorage} {@code cc.setup.folds} holds a plain object whose flag for
 * that fold's word is exactly {@code true} (absent, unparseable, an array or a blocked storage = all closed; a throw
 * counts into {@code CC._fault('folds')}); every {@code toggle} re-writes the four open flags under that one key (a
 * layout preference like the theme, never match behaviour, never a secret). {@code setupUnfold} opens every
 * {@code <details>} ancestor of a field before the voice refusal marks it {@code aria-invalid} - the one setter of
 * that attribute, so a refusal never marks a field out of sight. {@code setupFoldsHide} runs in {@code setupOnConfig}
 * after the package chrome (the core's config refresh runs it before every part) and the part's show gates, and
 * before {@code setupDraftOverlay}: a fold whose {@code .frow} rows are all hidden hides whole, summary included.
 *
 * <p>PvP mode ({@code #f-pvp}) rides {@code SETUP_PREFILL}
 * ({@code /api/config} {@code pvp}, the server's selected file's mode), so it is drafted and marks touched like its
 * neighbours; {@code setupApplyShape} then prefills it from the PAGE's pick ({@code setupPickedPvp}: the picked
 * option's {@code shape} is {@code SETUP_PVP_SHAPE}) unless the host picked a mode; a switch drops that pick and
 * re-saves the select's draft row (a reload never restores a mode picked for another shape). {@code CC.startForm}
 * sends {@code pvp} and {@code pvpStation} (= the Battle map station pick, the family an OFF switch brings back;
 * {@code '-'} = the stock station). {@code setupMapGate} enables the station picker (never its Apply) on a PvP file
 * while PvP mode is Off, re-run by the select's change listener.
 */
final class OpsConsoleJsSetup {

    private OpsConsoleJsSetup() {
    }

    /** The setup + voice + mods script part, joined after the core and players parts. */
    static final String JS = """
            // Setup form (prefill, tri-state passwords, pre-start lock, bind + advertise, shape pick,
            // CC.startForm), the five Set-live dials, the voice group and the Mods section. Every
            // top-level name carries a setup / voice / mods prefix and is a const, so a clash with a
            // sibling part fails the parse loudly instead of silently replacing a function.

            // ---- setup form ----

            // Start-form fields /api/config prefills: [element id, config key].
            const SETUP_PREFILL = [
              ['f-bind-host', 'bindHost'], ['f-bind-port', 'bindPort'], ['f-tick', 'tickRate'],
              ['f-fortress-dp', 'fortressDp'], ['f-wave-scale', 'fortressWaveScale'],
              ['f-roster-dp', 'waveRosterFullDp'], ['f-roster-mult', 'waveRosterMultiplier'],
              ['f-wave-category', 'waveCategory'],
              ['f-joiner-default', 'joinerDefault'], ['f-endless', 'endless'],
              ['f-terrain', 'terrain'], ['f-terrain-level', 'terrainLevel'],
              ['f-asteroids', 'asteroids'], ['f-game-root', 'gameRoot'],
              ['f-game-root-second', 'gameRootSecond'], ['f-record', 'record'],
              ['f-seats-per-source', 'seatsPerSource'], ['f-all-skills', 'allSkills'],
              ['f-rng-pins', 'rngPins'], ['f-magic-seam', 'magicSeam'], ['f-pilot-ship', 'pilotShip'],
              ['f-pvp', 'pvp']];
            // The PvP match shape: a picked file of this shape prefills PvP mode On.
            const SETUP_PVP_SHAPE = 'bastion:fortress:pvp';
            const SETUP_LOCK_WHY = 'Locked while a server runs.';
            const SETUP_DIAL_WHY = 'no live server to command';
            const SETUP_SAVED_HINT = 'saved - type to replace';
            // The Swing form's admission target when the shape pick names no password file.
            const SETUP_NO_SIDECAR = '<descriptor>.password - match.descriptor row MISSING';
            // The Start-form fields that carry the typed-text flag (the voice group keeps its own).
            const SETUP_TOUCHED_SCOPE =
              '#bind [data-touched], #pre-start [data-touched], #live-dials [data-touched]';
            const SETUP_DIAL_BUTTONS = ['btn-category-live', 'btn-joiner-live', 'btn-endless-live',
              'btn-terrain-live', 'btn-asteroids-live'];

            const setupAdvertisePrefix = '<public IP>'; // the Advertise placeholder; it follows the port
            // The address a ProtonVPN fill put in Advertise (null = none): Advertise follows the port
            // while its address part still equals it.
            let setupFollowAddress = null;
            let setupShapeOptions = []; // /api/config options[], index-aligned with #f-shape
            let setupPhase = null; // 'idle' | 'live' as of the last status answer, null = none yet
            // A server package (from /api/config): its one match password survives a shape switch, and the
            // new match shape controls show.
            let setupPackaged = false;

            const setupTouched = (el) => el.dataset.touched === '1';

            // True when the picked match file is a PvP one (its options[] shape).
            const setupPickedPvp = () => {
              const o = setupShapeOptions[CC.el('f-shape').selectedIndex] || null;
              return o !== null && o.shape === SETUP_PVP_SHAPE;
            };

            const setupMarkTouched = (el) => {
              const mark = () => {
                el.dataset.touched = '1';
              };
              el.addEventListener('input', mark);
              el.addEventListener('change', mark);
            };

            // A config value into a field; a select keeps its pick when no option matches (the Swing
            // combo's setSelectedItem law).
            const setupSetValue = (el, v) => {
              if (v === undefined || v === null) {
                return;
              }
              const s = String(v);
              if (el.tagName !== 'SELECT' || Array.from(el.options).some((o) => o.value === s)) {
                el.value = s;
              }
            };

            // The file name of a password-file path (the Swing hint's getFileName), either separator.
            const setupBaseName = (p) => p.slice(Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\\\')) + 1);

            // The address of host:port text: the text before the LAST colon (all of it when none), trimmed
            // (the server's own mode compare reads the address the same way).
            const setupAddressPart = (v) => {
              const t = String(v === undefined || v === null ? '' : v).trim();
              const colon = t.lastIndexOf(':');
              return (colon < 0 ? t : t.slice(0, colon)).trim();
            };

            // The port listener: Advertise re-derives while it still holds the placeholder, or the
            // address a ProtonVPN fill put there; an address the host typed is the host's and is never
            // overwritten.
            const setupAdvertise = () => {
              const el = CC.el('f-advertise');
              const port = CC.el('f-bind-port').value.trim();
              if (el.value.startsWith(setupAdvertisePrefix)) {
                el.value = setupAdvertisePrefix + ':' + port;
              } else if (setupFollowAddress !== null && setupAddressPart(el.value) === setupFollowAddress) {
                el.value = setupFollowAddress + ':' + port;
              }
            };

            // A ProtonVPN fill (the Fill answer, or a Start / Copy join brick that asked again) into the
            // three address fields; each counts as typed, so a later config re-read keeps it.
            const setupApplyFill = (fill) => {
              if (!fill) {
                return;
              }
              const fields = [['f-bind-host', fill.bindHost], ['f-bind-port', fill.bindPort],
                ['f-advertise', fill.advertise]];
              for (const [id, v] of fields) {
                const el = CC.el(id);
                setupSetValue(el, v);
                el.dataset.touched = '1';
              }
              setupFollowAddress = setupAddressPart(fill.advertise);
            };
            CC.applyFill = setupApplyFill;

            // The picked shape's password-file name into the hints + the saved-password placeholders.
            // switched = the host picked another shape: off a server package each shape keeps its own
            // passwords, so the typed ones go first; a package keeps its one password (a config re-read
            // never clears).
            const setupApplyShape = (switched) => {
              if (switched === true && !setupPackaged) {
                setupClearPasswords();
              }
              const o = setupShapeOptions[CC.el('f-shape').selectedIndex] || null;
              const side = o && typeof o.sidecar === 'string' && o.sidecar !== '' ? o.sidecar : null;
              const target = side === null ? SETUP_NO_SIDECAR : setupBaseName(side);
              CC.text(CC.el('sidecar-name'), target);
              for (const span of CC.qa('.sidecar-name')) {
                CC.text(span, target);
              }
              CC.el('f-password').placeholder = o && o.hasPassword === true ? SETUP_SAVED_HINT : '';
              CC.el('f-admin').placeholder = o && o.hasAdmin === true ? SETUP_SAVED_HINT : '';
              // Players (a server package): the picked shape's own seat count, unless the host typed
              // one; picking another shape drops the typed count.
              const players = CC.el('f-players');
              if (switched === true) {
                delete players.dataset.touched;
              }
              if (!setupTouched(players)) {
                players.value = o && o.seats !== undefined && o.seats !== null ? String(o.seats) : '';
              }
              // PvP mode: the picked file's own mode, unless the host picked one; picking another shape
              // drops that pick (Start switches the file only when the select differs from it). The saved
              // draft follows the switch, so a reload never restores a mode picked for another shape.
              const pvp = CC.el('f-pvp');
              if (switched === true) {
                delete pvp.dataset.touched;
              }
              if (!setupTouched(pvp)) {
                pvp.value = setupPickedPvp() ? 'on' : 'off';
              }
              if (switched === true) {
                setupDraftPending.add('f-pvp');
                setupDraftFlush(pvp);
              }
            };

            // The shape select from options[] (label = text, path = value, '' for the as-is row); a
            // pick the operator made survives when its path is still offered.
            const setupFillShapes = (c) => {
              const sel = CC.el('f-shape');
              const opts = Array.isArray(c.options) ? c.options : [];
              const valueOf = (o) => (o.path === undefined || o.path === null ? '' : String(o.path));
              const kept = setupTouched(sel) ? sel.value : null;
              sel.replaceChildren();
              for (const o of opts) {
                sel.add(new Option(o.label === undefined || o.label === null ? '' : String(o.label),
                  valueOf(o)));
              }
              setupShapeOptions = opts;
              let idx = kept === null ? -1 : opts.findIndex((o) => valueOf(o) === kept);
              if (idx < 0) {
                delete sel.dataset.touched;
                idx = Number.isInteger(c.selectedIndex) && c.selectedIndex < opts.length
                  ? c.selectedIndex : -1;
              }
              sel.selectedIndex = idx;
              setupApplyShape();
              // New match shape's Based on: the same offered list, its pick kept while still offered.
              const base = CC.el('f-new-base');
              const baseKept = base.value;
              base.replaceChildren();
              for (const o of opts) {
                base.add(new Option(o.label === undefined || o.label === null ? '' : String(o.label), valueOf(o)));
              }
              const baseIdx = opts.findIndex((o) => valueOf(o) === baseKept);
              base.selectedIndex = baseIdx >= 0 ? baseIdx : idx;
              // Players: the picked base's own seat count (the config's seats; blank without a decode), refilled
              // when Based on changes - the same prefill the desktop window's New dialog shows.
              const fillPlayers = () => {
                const o = opts[base.selectedIndex];
                CC.el('f-new-players').value = o && o.seats !== undefined && o.seats !== null ? String(o.seats) : '';
              };
              fillPlayers();
              base.onchange = fillPlayers;
            };

            const setupOnConfig = (c) => {
              if (!c) {
                return;
              }
              for (const [id, key] of SETUP_PREFILL) {
                const el = CC.el(id);
                if (!setupTouched(el)) {
                  setupSetValue(el, c[key]);
                }
              }
              // The served Advertise (the saved address, else the placeholder) lands only on a field
              // still empty or holding the placeholder: a phase change clears the typed flags, and a
              // typed address not yet saved by a good Copy join brick must survive that re-read.
              const adv = CC.el('f-advertise');
              if (adv.value === '' || adv.value.startsWith(setupAdvertisePrefix)) {
                setupSetValue(adv, c.advertise);
              }
              // A saved ProtonVPN fill: its address follows the port the way a fresh fill does.
              setupFollowAddress = c.advertiseFrom === 'protonvpn' ? setupAddressPart(String(c.advertise)) : null;
              setupAdvertise();
              setupPackaged = c.packaged === true;
              CC.show(CC.el('shape-new'), setupPackaged);
              CC.show(CC.el('hint-one-password'), setupPackaged);
              CC.show(CC.el('players-ctl'), setupPackaged);
              setupFillShapes(c);
              setupMapOnConfig(c);
              setupFoldsHide();
              // The served uplink default replaces the
              // HTML's 50 until the operator types one (typed text never clobbered).
              const uplink = CC.el('uplink');
              const uplinkDefault = Number(c.uplinkDefault);
              if (uplink && uplinkDefault > 0 && !setupTouched(uplink)) {
                uplink.value = String(uplinkDefault);
              }
              // Last: the saved draft over the fields the fill just wrote.
              setupDraftOverlay(c);
            };

            // Typed passwords never outlive an ACCEPTED Start: emptied, hidden again, the remove box
            // cleared. A refused Start keeps them: the retry re-sends the typed text (a cleared field
            // would send 'keep' = the saved OLD password, silently).
            const setupClearPasswords = () => {
              for (const f of [CC.el('f-password'), CC.el('f-admin')]) {
                f.value = '';
                f.type = 'password';
              }
              for (const b of CC.qa('#pre-start button.show')) {
                b.setAttribute('aria-pressed', 'false');
              }
              CC.el('admin-remove').checked = false;
            };
            CC.startAccepted = setupClearPasswords;

            // 'live' = a server takes commands (listening / run / stale); 'idle' = Start is the next
            // step (stopped / exited / gone); any other answer leaves the phase as it was.
            const setupPhaseOf = (s) => {
              const k = s ? s.stateKey : null;
              if (k === 'listening' || k === 'run' || k === 'stale') {
                return 'live';
              }
              return k === 'stopped' || k === 'exited' || k === 'gone' ? 'idle' : null;
            };

            // CC.enable, except on a button whose send is still in flight (CC.send holds it disabled).
            const setupEnable = (el, on, why) => {
              if (el.getAttribute('aria-busy') !== 'true') {
                CC.enable(el, on, why);
              }
            };

            const setupOnStatus = (s) => {
              const lad = CC.ladder();
              // The pre-start lock keys on Start's own rule (STARTING locks too); Show stays usable.
              for (const el of CC.qa('#pre-start input, #pre-start select, #pre-start textarea, #pre-start button')) {
                if (!el.classList.contains('show')) {
                  setupEnable(el, lad.startEnabled, SETUP_LOCK_WHY);
                }
              }
              CC.show(CC.q('#pre-start .lock-reason'), !lad.startEnabled);
              setupMapGate(lad.startEnabled);
              // The last reachability check (any press, or the check after a ProtonVPN Start): its
              // sentence in the status strip; none yet = the line stays hidden.
              const reach = s && s.reach && typeof s.reach.note === 'string' ? s.reach : null;
              const reachLine = CC.el('reach-line');
              CC.text(reachLine, reach === null ? '' : 'Reachability: ' + reach.note);
              CC.show(reachLine, reach !== null);
              for (const id of SETUP_DIAL_BUTTONS) {
                setupEnable(CC.el(id), lad.commandable, SETUP_DIAL_WHY);
              }
              // A Start that took (idle to live) and the return to stopped (live to idle) both
              // re-read the config into every field, typed or not.
              const ph = setupPhaseOf(s);
              if (ph !== null) {
                if (setupPhase !== null && ph !== setupPhase) {
                  for (const el of CC.qa(SETUP_TOUCHED_SCOPE)) {
                    delete el.dataset.touched;
                  }
                  CC.refreshConfig();
                }
                setupPhase = ph;
              }
            };

            // One Set-live press: the verb goes out once; `last sent` only once the console took it.
            const setupSendDial = (btn, out, fields, shown) => {
              CC.send('/api/verb', fields, {busy: btn}).then((a) => {
                if (a) {
                  CC.lastSent(out, shown);
                }
              }, () => {});
            };

            // Enter inside a dial field = its Set live; a held key never re-sends.
            const setupEnterRuns = (field, btn) => {
              field.addEventListener('keydown', (e) => {
                if (e.key !== 'Enter' || e.repeat) {
                  return;
                }
                e.preventDefault();
                if (btn.getAttribute('aria-busy') === 'true') {
                  return;
                }
                if (btn.disabled) {
                  CC.note(btn.dataset.why || SETUP_DIAL_WHY);
                } else {
                  btn.click();
                }
              });
            };

            for (const [id] of SETUP_PREFILL) {
              setupMarkTouched(CC.el(id));
            }
            setupMarkTouched(CC.el('f-shape'));
            // Players connect to: typed text marks the field too, so the draft overlay never writes
            // over an address being typed (the config fill guards it by its own placeholder rule).
            setupMarkTouched(CC.el('f-advertise'));
            setupMarkTouched(CC.el('uplink'));
            CC.el('f-shape').addEventListener('change', () => setupApplyShape(true));
            CC.el('f-bind-port').addEventListener('input', setupAdvertise);
            CC.el('setup-form').addEventListener('submit', (e) => e.preventDefault());
            for (const b of CC.qa('#pre-start button.show')) {
              b.addEventListener('click', () => {
                const f = CC.el(b.getAttribute('aria-controls'));
                const reveal = f.type === 'password';
                f.type = reveal ? 'text' : 'password';
                b.setAttribute('aria-pressed', reveal ? 'true' : 'false');
              });
            }
            CC.el('copy-advertise').addEventListener('click', () => {
              const text = CC.el('f-advertise').value;
              if (!navigator.clipboard) {
                CC.note('copy failed - select the text');
                return;
              }
              navigator.clipboard.writeText(text).then(() => CC.note('copied ' + text),
                () => CC.note('copy failed - select the text'));
            });

            // Copy join brick: the server builds the one-line join code from Advertise and the match
            // password (the Start form's keep / replace rule) against the running server and saves
            // Advertise for the next window. The box shows the code for 45 s (the desktop note's life);
            // a second brick restarts the clock. A refusal shows the way every send refusal does.
            // While the addresses stay as a ProtonVPN fill left them, the server asks ProtonVPN again
            // first; a fresh answer comes back as fill, a silent one as note.
            let setupBrickTimer = null;
            CC.el('btn-brick').addEventListener('click', () => {
              // under the pre-start lock the typed text never served this match, so ask for the saved one
              const pw = CC.ladder().startEnabled ? CC.el('f-password').value : '';
              CC.send('/api/brick', {
                bindHost: CC.el('f-bind-host').value,
                advertise: CC.el('f-advertise').value,
                passwordAction: pw !== '' ? 'replace' : 'keep',
                password: pw}, {busy: CC.el('btn-brick'), noteText: 'join brick built'}).then((a) => {
                if (!a || !a.ok || typeof a.brick !== 'string') {
                  return;
                }
                if (a.fill) {
                  setupApplyFill(a.fill);
                }
                const box = CC.el('f-brick');
                CC.text(box, a.brick);
                clearTimeout(setupBrickTimer);
                setupBrickTimer = setTimeout(() => {
                  CC.text(box, '-');
                  setupBrickTimer = null;
                }, 45000);
                CC.note('join brick copied - it is in the Join brick box for 45 s'
                  + ' (send it to joiners - they paste it into the join window)'
                  + (a.advertiseSaved ? '' : ' - Advertise not saved for the next window')
                  + (typeof a.note === 'string' ? ' - ' + a.note : ''));
                CC._copy(a.brick);
              }, () => {});
            });

            // New match shape: the server writes a new shape file from the picked base with these values
            // (before Start only); on success the shape list is read again and the new shape picked.
            CC.el('btn-shape-new').addEventListener('click', () => {
              CC.send('/api/shape-new', {
                name: CC.el('f-new-name').value,
                base: CC.el('f-new-base').value,
                players: CC.el('f-new-players').value,
                half: CC.el('f-new-half').value,
                cr: CC.el('f-new-cr').value,
                terrain: CC.el('f-new-backdrop').value}, {busy: CC.el('btn-shape-new')}).then((a) => {
                if (!a || !a.ok || typeof a.shape !== 'string') {
                  return;
                }
                CC.note(a.text);
                const want = a.shape + '.txt';
                CC.refreshConfig().then(() => {
                  const sel = CC.el('f-shape');
                  const i = Array.from(sel.options).findIndex((o) => o.value === want || o.value.endsWith('/' + want));
                  if (i >= 0) {
                    sel.selectedIndex = i;
                    sel.dataset.touched = '1';
                    setupApplyShape(true);
                    setupMapAsk();
                  }
                });
              }, () => {});
            });

            // Fill from ProtonVPN: the server asks the ProtonVPN gateway (one ask at a time, shared by
            // every press) and answers the three addresses; a refusal shows the way every send refusal does.
            CC.el('btn-lease').addEventListener('click', () => {
              CC.send('/api/lease', {bindPort: CC.el('f-bind-port').value},
                {busy: CC.el('btn-lease'), noteText: 'asked ProtonVPN'}).then((a) => {
                if (!a || !a.ok) {
                  return;
                }
                setupApplyFill(a);
                CC.note(a.note + (a.saved ? '' : ' - not saved for the next window'));
              }, () => {});
            });

            // Check from the internet: the server sends a few small test packets to the address in
            // Players connect to (a bare address is tried at the port field) and waits up to about 8 s
            // for the running server to answer from outside; the button stays off while it waits and
            // the answer's sentence lands on the note line (the status strip keeps the last one).
            CC.el('btn-reach').addEventListener('click', () => {
              CC.send('/api/reach', {
                bindHost: CC.el('f-bind-host').value,
                bindPort: CC.el('f-bind-port').value,
                advertise: CC.el('f-advertise').value},
                {busy: CC.el('btn-reach'), noteText: 'reachability check sent'}).then((a) => {
                if (!a || !a.ok) {
                  return;
                }
                CC.note(typeof a.note === 'string' ? a.note : 'reachability: ' + a.verdict);
              }, () => {});
            });

            // ---- battle map ----

            // The Battle map row: four values of the picked match shape, each with its own Apply. An
            // Apply re-makes that shape with the one changed value on the press (no confirm); a changed
            // match re-reads the config, and players' join windows fetch the new match themselves.
            const SETUP_MAP_NO_STATION = 'this match shape has no station';
            let setupMapStationless = false;
            let setupMapAsked = 0; // the latest shape ask; an older answer is dropped

            // The station picker and its Apply follow the pre-start lock, and stay off for a shape
            // without a station ('-'). A PvP file with PvP mode set Off is the one exception for the
            // picker alone: Start then takes its pick as the station that comes back ('-' = the stock one).
            const setupMapGate = (on) => {
              const sel = CC.el('f-map-station');
              const why = on ? SETUP_MAP_NO_STATION : SETUP_LOCK_WHY;
              const pvpBack = setupPickedPvp() && CC.el('f-pvp').value === 'off';
              setupEnable(sel, on && (!setupMapStationless || pvpBack), why);
              setupEnable(CC.el('btn-map-station'), on && !setupMapStationless && sel.value !== '-', why);
            };

            // A shape's four values into the row (null = not a server package: the row hides). fresh =
            // the values belong to a new pick or an Apply answer, so a typed value gives way.
            const setupMapFill = (m, fresh) => {
              CC.show(CC.el('map-row'), !!m);
              if (!m) {
                return;
              }
              for (const [el, key] of [[CC.el('f-map-half'), 'half'], [CC.el('f-map-station'), 'station'],
                [CC.el('f-map-backdrop'), 'backdrop'], [CC.el('f-map-cr'), 'deployCr']]) {
                if (fresh) {
                  delete el.dataset.touched;
                }
                if (!setupTouched(el)) {
                  setupSetValue(el, m[key]);
                }
              }
              setupMapStationless = m.station === '-';
              const problem = typeof m.problem === 'string' ? m.problem : '';
              CC.text(CC.el('map-problem'), problem);
              CC.show(CC.el('map-problem'), problem !== '');
              setupMapGate(CC.ladder().startEnabled);
            };

            // The picked shape's values, asked again with the pick: a config answer carries them only
            // for the shape it picks itself.
            const setupMapAsk = () => {
              setupMapAsked += 1;
              const n = setupMapAsked;
              CC.api('/api/config?descriptor=' + encodeURIComponent(CC.el('f-shape').value)).then((c) => {
                if (n === setupMapAsked && c) {
                  setupMapFill(c.matchSettings, true);
                }
              }, () => {});
            };

            const setupMapOnConfig = (c) => {
              if (CC.el('f-shape').selectedIndex === c.selectedIndex) {
                setupMapAsked += 1;
                setupMapFill(c.matchSettings, false);
              } else {
                setupMapAsk();
              }
            };

            // One Apply: applied on the press, no confirm; its note (the change lands at the next Start)
            // shows on the note line, its values land in the row, and a changed match re-reads the config.
            const setupMapBind = (field, btn, op) => {
              setupMarkTouched(field);
              btn.addEventListener('click', () => {
                CC.send('/api/match-settings', {op, value: field.value, descriptor: CC.el('f-shape').value},
                  {busy: btn}).then((a) => {
                  if (!a || !a.ok) {
                    return;
                  }
                  setupMapAsked += 1;
                  setupMapFill({half: a.half, station: a.station, backdrop: a.backdrop,
                    deployCr: a.deployCr, problem: ''}, true);
                  if (a.matchTextChanged === true) {
                    CC.refreshConfig();
                  }
                }, () => {});
              });
            };
            setupMapBind(CC.el('f-map-half'), CC.el('btn-map-half'), 'size');
            setupMapBind(CC.el('f-map-station'), CC.el('btn-map-station'), 'station');
            setupMapBind(CC.el('f-map-backdrop'), CC.el('btn-map-backdrop'), 'backdrop');
            setupMapBind(CC.el('f-map-cr'), CC.el('btn-map-cr'), 'cr');
            // Players (a server package): the same Apply, op players; the field shows only in a package.
            setupMapBind(CC.el('f-players'), CC.el('btn-players'), 'players');
            CC.el('f-shape').addEventListener('change', setupMapAsk);
            CC.el('f-map-station').addEventListener('change', () => setupMapGate(CC.ladder().startEnabled));
            CC.el('f-pvp').addEventListener('change', () => setupMapGate(CC.ladder().startEnabled));

            // ---- setup draft ----

            // What the host typed into the setup form but has not started yet is saved on the server
            // while it is typed, and the next page load puts it back. Drafted = the prefilled Start
            // fields + the match shape + Players connect to, each saved under its own data-cc id; the
            // two password fields are never in this list (and a password input is skipped anyway), and
            // the Players, battle map and voice fields are not drafted (each has its own Apply).
            const SETUP_DRAFTED = SETUP_PREFILL.map(([id]) => id).concat(['f-shape', 'f-advertise']);
            const setupDraftTimers = new Map(); // control id to its waiting save timer
            const setupDraftPending = new Set(); // control ids typed into since their last save
            const setupDraftSent = new Map(); // control id to the last value sent
            const setupDraftChains = new Map(); // control id to the tail of its save chain
            let setupDraftWhy = null; // the failure reason last noted (one note per reason)
            let setupDraftNoted = false; // the restore note is decided on the first config answer only

            // The draft over every drafted field the host has not touched (a touched field holds text
            // newer than the draft), bound by the control's data-cc id, never by position. A field is
            // marked touched only when the value really landed (a select keeps its pick for a shape no
            // longer offered). A restored shape goes through the shape path (hints, Players, the map
            // row) without a switch, so no typed password is cleared.
            const setupDraftOverlay = (c) => {
              const has = c.draft !== null && typeof c.draft === 'object';
              const shape = CC.el('f-shape');
              const shapeWas = shape.value;
              for (const id of SETUP_DRAFTED) {
                const el = CC.el(id);
                const v = has ? c.draft[el.dataset.cc] : undefined;
                if (typeof v !== 'string' || el.type === 'password' || setupTouched(el)) {
                  continue;
                }
                setupSetValue(el, v);
                if (el.value === v) {
                  el.dataset.touched = '1';
                }
              }
              setupAdvertise();
              if (shape.value !== shapeWas) {
                setupApplyShape();
                setupMapAsk();
              }
              if (!setupDraftNoted) {
                setupDraftNoted = true;
                const at = typeof c.draftUpdated === 'string' ? c.draftUpdated : '';
                if (at !== '') {
                  // the server's instant may carry up to 9 fraction digits; a browser's Date wants 3 at most
                  const when = new Date(at.replace(/[.][0-9]+Z$/, 'Z'));
                  const shown = isNaN(when.getTime()) ? at : CC.fmtTime(when);
                  CC.note('restored your unsaved setup from ' + shown + ' - Start saves it');
                }
              }
            };

            // A save the server took: the next failure notes again.
            const setupDraftOk = () => {
              setupDraftWhy = null;
            };

            // A save that failed: the value counts as unsent (the next edit sends it again) and the
            // reason is noted once until a save succeeds. There is never a retry.
            const setupDraftFailed = (id, value, x) => {
              if (setupDraftSent.get(id) === value) {
                setupDraftSent.delete(id);
              }
              const why = x && typeof x.error === 'string' && x.error !== '' ? x.error
                : (x && typeof x.kind === 'string' ? x.kind : 'failed');
              if (why !== setupDraftWhy) {
                setupDraftWhy = why;
                CC.note('unsaved setup not kept: ' + why);
              }
            };

            // The value to save for a control, or null: nothing typed since its last save, or the same
            // value as the last one sent. Taking it clears the pending mark and records the send.
            const setupDraftTake = (id, el) => {
              if (!setupDraftPending.has(id)) {
                return null;
              }
              setupDraftPending.delete(id);
              const value = el.value;
              if (setupDraftSent.get(id) === value) {
                return null;
              }
              setupDraftSent.set(id, value);
              return value;
            };

            // Save one control now: its waiting timer is cancelled and the save joins that control's
            // chain, so two saves of one field never overlap and an older value never lands last.
            const setupDraftFlush = (el) => {
              const id = el.id;
              clearTimeout(setupDraftTimers.get(id));
              setupDraftTimers.delete(id);
              const prev = setupDraftChains.get(id) || Promise.resolve();
              setupDraftChains.set(id, prev.then(() => {
                const value = setupDraftTake(id, el);
                if (value === null) {
                  return null;
                }
                return CC.api('/api/draft', {id: el.dataset.cc, value: el.value}, {quiet: true})
                  .then(setupDraftOk, (x) => setupDraftFailed(id, value, x));
              }));
            };

            // Start and Launch debug WATCH send every field themselves, and the server drops the draft
            // once a start is accepted: a save still waiting must not draft the form again after them.
            const setupDraftCancel = () => {
              for (const t of setupDraftTimers.values()) {
                clearTimeout(t);
              }
              setupDraftTimers.clear();
              setupDraftPending.clear();
            };

            // Only the host's own edits save: typing saves a field 750 ms after its last keystroke (one
            // timer per field, reset by every keystroke), and a change (leaving the field, a pick)
            // saves it at once. The page's own writes (the config fill, the draft overlay, a ProtonVPN
            // fill, the new shape's pick) fire no event, so they are never sent back.
            for (const id of SETUP_DRAFTED) {
              const el = CC.el(id);
              if (el.type === 'password') {
                continue;
              }
              el.addEventListener('input', () => {
                setupDraftPending.add(id);
                clearTimeout(setupDraftTimers.get(id));
                setupDraftTimers.set(id, setTimeout(() => setupDraftFlush(el), 750));
              });
              el.addEventListener('change', () => {
                setupDraftPending.add(id);
                setupDraftFlush(el);
              });
            }

            // The page is leaving: every value still waiting goes now, outside the chains (the page
            // will not wait for them), on requests that outlive the page.
            window.addEventListener('pagehide', () => {
              for (const t of setupDraftTimers.values()) {
                clearTimeout(t);
              }
              setupDraftTimers.clear();
              for (const id of Array.from(setupDraftPending)) {
                const el = CC.el(id);
                const value = setupDraftTake(id, el);
                if (value !== null) {
                  CC.api('/api/draft', {id: el.dataset.cc, value: el.value}, {quiet: true, keepalive: true})
                    .then(setupDraftOk, (x) => setupDraftFailed(id, value, x));
                }
              }
            });

            CC.el('btn-category-live').addEventListener('click', () => {
              const value = CC.el('f-wave-category').value;
              setupSendDial(CC.el('btn-category-live'), CC.el('last-category'),
                {verb: 'wave-category', value}, value);
            });
            CC.el('btn-joiner-live').addEventListener('click', () => {
              const value = CC.el('f-joiner-default').value;
              // blank = the roster default, which the server's line names `none` (joinerDefaultLine)
              setupSendDial(CC.el('btn-joiner-live'), CC.el('last-joiner'),
                {verb: 'set-joiner-default', value}, value.trim() === '' ? 'none' : value.trim());
            });
            CC.el('btn-endless-live').addEventListener('click', () => {
              const value = CC.el('f-endless').value;
              setupSendDial(CC.el('btn-endless-live'), CC.el('last-endless'), {verb: 'endless', value}, value);
            });
            CC.el('btn-terrain-live').addEventListener('click', () => {
              const value = CC.el('f-terrain').value;
              const level = CC.el('f-terrain-level').value;
              setupSendDial(CC.el('btn-terrain-live'), CC.el('last-terrain'),
                {verb: 'terrain', value, level}, value + ' ' + level);
            });
            CC.el('btn-asteroids-live').addEventListener('click', () => {
              const value = CC.el('f-asteroids').value;
              setupSendDial(CC.el('btn-asteroids-live'), CC.el('last-asteroids'),
                {verb: 'knob-asteroids', value}, value);
            });
            setupEnterRuns(CC.el('f-wave-category'), CC.el('btn-category-live'));
            setupEnterRuns(CC.el('f-joiner-default'), CC.el('btn-joiner-live'));
            setupEnterRuns(CC.el('f-endless'), CC.el('btn-endless-live'));
            setupEnterRuns(CC.el('f-terrain'), CC.el('btn-terrain-live'));
            setupEnterRuns(CC.el('f-terrain-level'), CC.el('btn-terrain-live'));
            setupEnterRuns(CC.el('f-asteroids'), CC.el('btn-asteroids-live'));

            // ---- voice group ----

            // [OpsVoicePanel.PANEL_KNOBS name, element id], in the panel's row order.
            const VOICE_FIELDS = [
              ['enabled', 'v-enabled'], ['bitrateKbps', 'v-bitrate'], ['frameMs', 'v-frame'],
              ['complexity', 'v-complexity'], ['fec', 'v-fec'], ['maxTalkers', 'v-max-talkers'],
              ['proxFullSu', 'v-prox-full'], ['proxCullSu', 'v-prox-cull'],
              ['relayTickMs', 'v-relay-tick'], ['jitterMinFrames', 'v-jitter-min'],
              ['jitterMaxMs', 'v-jitter-max'], ['jitterShrinkMs', 'v-jitter-shrink']];
            const VOICE_ID_OF = new Map(VOICE_FIELDS);
            const VOICE_NOTES = ['voice-note-refused', 'voice-note-fail', 'voice-note-sent'];
            // The readout's constants: OpsVoicePanel.LISTENER_FRAME_OVERHEAD_BYTES,
            // VoiceFrameCodec.DOWN_TAG_BYTES, OpsVoicePanel.EGRESS_PLAYERS.
            const VOICE_LISTENER_OVERHEAD_BYTES = 64;
            const VOICE_DOWN_TAG_BYTES = 11;
            const VOICE_EGRESS_PLAYERS = 32;

            // OpsVoicePanel.readout: bytes per listener at the max talkers + the egress at 32 players.
            const voiceBandwidth = () => {
              const kbps = parseInt(CC.el('v-bitrate').value, 10);
              const frameMs = parseInt(CC.el('v-frame').value, 10);
              const talkers = parseInt(CC.el('v-max-talkers').value, 10);
              const out = CC.el('voice-bandwidth');
              if (!(kbps >= 0 && frameMs > 0 && talkers >= 0)) {
                CC.text(out, '-');
                return;
              }
              const talkerFrame = kbps * frameMs / 8 + VOICE_DOWN_TAG_BYTES;
              const perListener = (VOICE_LISTENER_OVERHEAD_BYTES + talkers * talkerFrame) * (1000 / frameMs);
              const egress = Math.max(0, VOICE_EGRESS_PLAYERS - 1) * perListener;
              CC.text(out, 'per listener ≈ ' + (perListener / 1000).toFixed(1) + ' kB/s at ' + talkers
                + ' talkers; egress at ' + VOICE_EGRESS_PLAYERS + ' players ≈ '
                + (egress * 8 / 1000000).toFixed(1) + ' Mbit/s');
            };

            const voiceFlag = (v) => v === true || v === 'on' || v === 'true';

            // Shows ONE of the three voice notes (null = none) with its text.
            const voiceNote = (id, text) => {
              for (const n of VOICE_NOTES) {
                CC.show(CC.el(n), n === id);
              }
              if (id !== null) {
                CC.text(CC.el(id), text);
              }
            };

            // GET /api/voice into the group; a field the operator edited keeps its text.
            const voiceFill = (a) => {
              if (!a) {
                return;
              }
              for (const [knob, id] of VOICE_FIELDS) {
                const el = CC.el(id);
                if (setupTouched(el) || a[knob] === undefined || a[knob] === null) {
                  continue;
                }
                if (el.type === 'checkbox') {
                  el.checked = voiceFlag(a[knob]);
                } else {
                  setupSetValue(el, a[knob]);
                }
              }
              voiceBandwidth();
            };

            const voiceLoad = () => {
              CC.api('/api/voice').then(voiceFill, () => {});
            };

            // Apply voice (always enabled): the 12 rows go out whole; the server validates them.
            const voiceApply = () => {
              const fields = {};
              for (const [knob, id] of VOICE_FIELDS) {
                const el = CC.el(id);
                el.removeAttribute('aria-invalid');
                fields[knob] = el.type === 'checkbox' ? (el.checked ? 'on' : 'off') : el.value;
              }
              voiceNote(null, '');
              CC.send('/api/voice', fields, {busy: CC.el('v-apply'), noteText: 'voice: settings sent'})
                .then((a) => {
                  if (!a) {
                    return;
                  }
                  for (const [, id] of VOICE_FIELDS) {
                    delete CC.el(id).dataset.touched;
                  }
                  voiceNote('voice-note-sent',
                    a.note === undefined || a.note === null ? 'voice: settings sent' : a.note);
                }, (err) => {
                  const kind = err ? err.kind : null;
                  const named = kind === 'refused' && Array.isArray(err.fields) ? err.fields : [];
                  if (kind === 'refused' && (err.title === 'Voice refused' || named.length > 0)) {
                    for (const knob of named) {
                      const id = VOICE_ID_OF.get(knob);
                      if (id) {
                        setupUnfold(CC.el(id));
                        CC.el(id).setAttribute('aria-invalid', 'true');
                      }
                    }
                    voiceNote('voice-note-refused', 'voice NOT applied: ' + err.error);
                  } else if (kind === 'refused') {
                    voiceNote('voice-note-fail', err.error);
                  } else {
                    voiceNote('voice-note-fail', kind === 'wrongkey'
                      ? 'voice: not sent - the console key was refused'
                      : 'voice: not sent - the console did not answer');
                  }
                });
            };

            for (const [, id] of VOICE_FIELDS) {
              const el = CC.el(id);
              const edited = () => {
                el.dataset.touched = '1';
                el.removeAttribute('aria-invalid');
                voiceBandwidth();
              };
              el.addEventListener('input', edited);
              el.addEventListener('change', edited);
            }
            CC.el('v-apply').addEventListener('click', voiceApply);

            // ---- mods section ----

            let modsRows = null; // the last GET /api/mods rows [{mod, id, version, use, folder}]; null = not loaded
            const modsStr = (v) => (v === undefined || v === null ? '' : String(v));

            // The Start payload's two mod lists (comma-joined ids, row order): the section's rows once
            // loaded, else the config's own rows (written back unchanged), else null = rows untouched.
            const modsChoiceLists = () => {
              if (modsRows !== null) {
                const ids = (use) => modsRows.filter((r) => r.use === use).map((r) => r.id).join(',');
                return {required: ids('required'), optional: ids('optional')};
              }
              const c = CC.config;
              if (c && typeof c.modsEnabled === 'string' && typeof c.modsOptional === 'string') {
                return {required: c.modsEnabled, optional: c.modsOptional};
              }
              return null;
            };

            // One mods write, then the section re-read whatever the answer; a `Mods` refusal = the
            // write-fail note; an answer's own note (a Remove on a server package) lands on the note
            // line. Resolves true when the console took it.
            const modsOp = (fields, busy, noteText) => {
              CC.show(CC.el('mods-write-fail'), false);
              return CC.send('/api/mods', fields, {busy, noteText}).then((a) => {
                if (a && typeof a.note === 'string') {
                  CC.note(a.note);
                }
                return a !== null;
              }, (err) => {
                if (err && err.kind === 'refused' && err.title === 'Mods') {
                  CC.text(CC.el('mods-write-fail'), err.error);
                  CC.show(CC.el('mods-write-fail'), true);
                }
                return false;
              }).then((ok) => {
                modsLoad();
                return ok;
              });
            };

            const modsRootRow = (list, text) => {
              const frag = CC.tpl('tpl-root-row');
              CC.text(CC.q('.root-path', frag), text);
              list.appendChild(frag);
            };

            const modsModRow = (body, r) => {
              const frag = CC.tpl('tpl-mod-row');
              CC.text(CC.q('[data-col="Mod"]', frag), r.mod);
              CC.text(CC.q('[data-col="Id"]', frag), r.id);
              CC.text(CC.q('[data-col="Version"]', frag), r.version);
              CC.text(CC.q('[data-col="Folder"]', frag), r.folder);
              const sel = CC.q('select.use', frag);
              setupSetValue(sel, r.use);
              sel.addEventListener('change', () => {
                r.use = sel.value;
                modsOp({op: 'use', id: r.id, use: sel.value}, sel, 'mods: use sent');
              });
              const rm = CC.q('button.remove', frag);
              // Swing's row Remove, by id for every row (a folder cell may be prose, never a path):
              // the console drops an installed mod's folder, or a not-installed row's choice.
              rm.addEventListener('click', () => modsOp({op: 'remove', id: r.id}, rm, 'mods: remove sent'));
              body.appendChild(frag);
            };

            // GET /api/mods into the roots list, the table (every cell textContent) and the status.
            const modsRender = (a) => {
              if (!a) {
                return;
              }
              const roots = Array.isArray(a.roots) ? a.roots.map(modsStr) : [];
              const list = CC.el('mods-roots');
              list.replaceChildren();
              for (const root of roots) {
                modsRootRow(list, root);
              }
              if (roots.length === 0) {
                modsRootRow(list, '(none - add one)');
              }
              modsRows = (Array.isArray(a.rows) ? a.rows : []).map((r) => ({
                mod: modsStr(r.mod), id: modsStr(r.id), version: modsStr(r.version),
                use: modsStr(r.use), folder: modsStr(r.folder)}));
              const body = CC.el('mods-body');
              body.replaceChildren();
              for (const r of modsRows) {
                modsModRow(body, r);
              }
              CC.text(CC.el('mods-status'), modsStr(a.status));
            };

            const modsLoad = () => {
              CC.api('/api/mods').then(modsRender, () => {});
            };

            // Add folder / Enter in the path field: the typed absolute path goes to the server as typed
            // (its path rules decide); a blank field sends nothing.
            const modsAdd = () => {
              const input = CC.el('mods-add-path');
              const btn = CC.el('mods-add');
              if (btn.getAttribute('aria-busy') === 'true') {
                return;
              }
              if (input.value === '') {
                CC.note('type a mod folder path first, then Add folder');
                return;
              }
              modsOp({op: 'add', path: input.value}, btn, 'mods: add folder sent').then((ok) => {
                if (ok) {
                  input.value = '';
                }
              });
            };

            CC.el('mods-add').addEventListener('click', modsAdd);
            CC.el('mods-add-path').addEventListener('keydown', (e) => {
              if (e.key === 'Enter' && !e.repeat) {
                e.preventDefault();
                modsAdd();
              }
            });
            CC.el('mods-rescan').addEventListener('click',
              () => modsOp({op: 'rescan'}, CC.el('mods-rescan'), 'mods: rescan sent'));

            // ---- the Start payload + the module ----

            // /api/start (the tri-state password rule): the five non-nullable keys always; a password only
            // when typed (`keep` = the server keeps the saved one); the admin remove box. Advertise rides
            // along only so the server can tell whether the addresses are still a ProtonVPN fill.
            CC.startForm = () => {
              // this press sends the values itself: no draft save still waiting may follow it
              setupDraftCancel();
              const pw = CC.el('f-password').value;
              const admin = CC.el('f-admin').value;
              const remove = CC.el('admin-remove').checked;
              const adminAction = remove ? 'remove' : (admin !== '' ? 'replace' : 'keep');
              const f = {
                bindHost: CC.el('f-bind-host').value,
                bindPort: CC.el('f-bind-port').value,
                advertise: CC.el('f-advertise').value,
                tickRate: CC.el('f-tick').value,
                fortressDp: CC.el('f-fortress-dp').value,
                descriptor: CC.el('f-shape').value,
                joinerDefault: CC.el('f-joiner-default').value,
                fortressWaveScale: CC.el('f-wave-scale').value,
                waveCategory: CC.el('f-wave-category').value,
                endless: CC.el('f-endless').value,
                terrain: CC.el('f-terrain').value,
                terrainLevel: CC.el('f-terrain-level').value,
                asteroids: CC.el('f-asteroids').value,
                waveRosterFullDp: CC.el('f-roster-dp').value,
                waveRosterMultiplier: CC.el('f-roster-mult').value,
                seatsPerSource: CC.el('f-seats-per-source').value,
                allSkills: CC.el('f-all-skills').value,
                rngPins: CC.el('f-rng-pins').value,
                magicSeam: CC.el('f-magic-seam').value,
                pilotShip: CC.el('f-pilot-ship').value,
                pvp: CC.el('f-pvp').value,
                pvpStation: CC.el('f-map-station').value,
                gameRoot: CC.el('f-game-root').value,
                gameRootSecond: CC.el('f-game-root-second').value,
                record: CC.el('f-record').value,
                passwordAction: pw !== '' ? 'replace' : 'keep',
                password: pw,
                adminAction,
                adminPassword: adminAction === 'replace' ? admin : '',
                adminRemove: remove ? 'on' : 'off'};
              const mods = modsChoiceLists();
              if (mods !== null) {
                f.modsEnabled = mods.required;
                f.modsOptional = mods.optional;
              }
              return f;
            };

            // The Setup folds: four <details> groups, closed at load unless this browser opened them
            // before. One localStorage key holds the four open flags; any other stored value is ignored,
            // and a blocked storage throws on access, so each read and write is caught.
            const SETUP_FOLDS = [['setup-debug', 'debug'], ['setup-advanced', 'advanced'],
              ['setup-live', 'live'], ['setup-voice-knobs', 'voice']];
            const setupFoldsRestore = () => {
              try {
                const v = JSON.parse(localStorage.getItem('cc.setup.folds'));
                const ok = v !== null && typeof v === 'object' && !Array.isArray(v);
                for (const [id, word] of SETUP_FOLDS) {
                  CC.el(id).open = ok && v[word] === true;
                }
              } catch (x) {
                CC._fault('folds', x);
              }
            };
            const setupFoldsRemember = () => {
              const flags = Object.fromEntries(SETUP_FOLDS.map(([id, word]) => [word, CC.el(id).open]));
              try {
                localStorage.setItem('cc.setup.folds', JSON.stringify(flags));
              } catch (x) {
                CC._fault('folds', x);
              }
            };
            // A field about to be marked invalid is first brought into view: every <details> above it
            // opens (a hidden error is no error). Any later setter of aria-invalid calls this first.
            const setupUnfold = (el) => {
              for (let d = el.closest('details'); d; d = d.parentElement && d.parentElement.closest('details')) {
                d.open = true;
              }
            };
            // A fold whose rows are all hidden (the mode chrome, the show gates) hides with them.
            const setupFoldsHide = () => {
              for (const [id] of SETUP_FOLDS) {
                const fold = CC.el(id);
                CC.show(fold, CC.qa('.frow', fold).some((row) => !row.hidden));
              }
            };
            setupFoldsRestore();
            for (const [id] of SETUP_FOLDS) {
              CC.el(id).addEventListener('toggle', setupFoldsRemember);
            }

            // Section changes: Setup re-reads the voice rows, Mods re-reads the mods list.
            const setupOnShow = () => {
              if (CC.sectionName === 'setup') {
                voiceLoad();
              } else if (CC.sectionName === 'mods') {
                modsLoad();
              }
            };

            CC.register({name: 'setup', onStatus: setupOnStatus, onConfig: setupOnConfig, onShow: setupOnShow});
            """;
}
