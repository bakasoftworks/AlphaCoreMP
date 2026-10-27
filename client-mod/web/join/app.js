







(function () {
  'use strict';
  var base = location.pathname.replace(/[^/]*$/, '');
  var $ = function (id) { return document.getElementById(id); };
  var st = null;            
  var remembered = false;   
  var editorUrl = null;     

  function getJson(path) {
    return fetch(base + path, { cache: 'no-store' }).then(function (r) {
      return r.json().then(function (j) { if (!r.ok) { throw new Error((j && j.error) || ('http ' + r.status)); } return j; });
    });
  }
  function postForm(path, fields) {
    return fetch(base + path, { method: 'POST', cache: 'no-store',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams(fields || {}).toString() }).then(function (r) {
      return r.text().then(function (t) {
        var j; try { j = JSON.parse(t); } catch (e) { j = null; }
        if (!r.ok) { throw new Error((j && j.error) || ('http ' + r.status)); }
        return j;
      });
    });
  }
  function setState(s) { document.body.dataset.state = s; }
  function status(text) { $('status').textContent = text; }
  function setText(id, text, bad) { var el = $(id); el.textContent = text || ''; el.classList.toggle('bad', !!bad); }
  function inForm() { return document.body.dataset.state === 'form'; }
  function clean(v) { return (v || '').replace(/"/g, '').trim(); }

  
  
  
  function hostProblem(h) { if (!h) { return 'enter the server address the host gave you'; } if (/\s/.test(h)) { return 'the server address must not contain spaces'; } return null; }
  function portProblem(p) { if (!p) { return 'enter the server port'; } if (!/^\d+$/.test(p)) { return 'the port must be a number (1-65535)'; } var n = +p; if (n < 1 || n > 65535) { return 'the port must be between 1 and 65535'; } return null; }
  function passwordProblem(pw) { if (!pw) { return null; } if (!/^[A-Za-z0-9][A-Za-z0-9-]{7,63}$/.test(pw)) { return 'the match password is 8 to 64 characters: letters, digits and dashes, not starting with a dash - type it exactly as the host announced it'; } return null; }
  function shipProblem(s) { if (!s || /^\(server default/.test(s)) { return null; } if (!/^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$/.test(s)) { return 'a ship id is letters, digits, dot, underscore, colon and dash (up to 64) - pick one from the list or leave it empty'; } return null; }
  
  var FACE = { ship: 'shipBtn' };
  function mark(id, problem) { $(FACE[id] || id).classList.toggle('bad', !!problem); }
  function readiness() {
    var ph = hostProblem($('host').value.trim()), pp = portProblem($('port').value.trim());
    var pw = passwordProblem(clean($('password').value)), ps = shipProblem(clean($('ship').value));
    mark('host', ph); mark('port', pp); mark('password', pw); mark('ship', ps);
    var problem = ph || pp || pw || ps;
    var launchable = !problem && inForm();
    $('launch').disabled = !launchable;
    if (problem) { setText('ready', problem, true); } else { setText('ready', inForm() ? 'ready - Enter launches' : '', false); }
    return launchable;
  }

  
  
  
  
  function defaultLabel(id) { return id ? '(server default: ' + shipLabel(id) + ')' : ((st && st.serverDefaultLabel) || '(server default)'); }
  var ships = { server: [], local: [], defaultId: '', current: '', names: {}, hulls: {} };
  
  
  
  
  function shipLabel(id) {
    var h = ships.hulls[id];
    return h ? h + '  —  ' + id : id;
  }
  function labelMap(m) { return (m && typeof m === 'object') ? m : {}; }
  var shipUi = { open: false, active: -1, rows: [] };
  function uniq(list) { var seen = {}, out = []; (list || []).forEach(function (id) { if (id && !seen[id]) { seen[id] = 1; out.push(id); } }); return out; }
  function fillShips(ids, localIds, names, hulls) {
    ships.server = uniq(ids);
    ships.local = uniq(localIds);
    ships.names = labelMap(names);
    ships.hulls = labelMap(hulls);
    if ($('ship').value) { pickShip($('ship').value, { quiet: true }); } 
    if (shipUi.open) { renderShipList($('shipSearch').value); }
  }
  function setShipDefault(id) {
    ships.defaultId = id || '';
    if (!$('ship').value) { pickShip('', { quiet: true }); }
  }
  function pickShip(id, opts) {
    id = clean(id || '');
    $('ship').value = id;
    var val = $('shipVal');
    val.textContent = id ? shipLabel(id) : defaultLabel(ships.defaultId);
    val.classList.toggle('ph', !id);
    if (!(opts && opts.quiet)) { closeShipPop(); $('shipBtn').focus(); }
    readiness();
  }
  function renderShipList(q) {
    q = (q || '').trim().toLowerCase();
    var list = $('shipList');
    while (list.firstChild) { list.removeChild(list.firstChild); }
    shipUi.rows = []; shipUi.active = -1;
    var cur = $('ship').value;
    function addOpt(id, label, tag, tagCls, cls) {
      var d = document.createElement('div');
      d.className = 'opt' + (cls ? ' ' + cls : '') + (id === cur ? ' sel' : '');
      d.setAttribute('role', 'option');
      d.dataset.id = id;
      var s = document.createElement('span'); s.className = 'id'; s.textContent = label; d.appendChild(s);
      if (tag) { var t = document.createElement('span'); t.className = 'tag' + (tagCls ? ' ' + tagCls : ''); t.textContent = tag; d.appendChild(t); }
      d.addEventListener('pointerdown', function (e) { e.preventDefault(); }); 
      d.addEventListener('click', function () { pickShip(id); });
      list.appendChild(d); shipUi.rows.push(d);
      if (id === cur) { shipUi.active = shipUi.rows.length - 1; }
    }
    function addHead(text, n) {
      var h = document.createElement('div'); h.className = 'grpHead'; h.textContent = text;
      var c = document.createElement('span'); c.className = 'n'; c.textContent = String(n); h.appendChild(c);
      list.appendChild(h);
    }
    var match = function (id) { return !q || [id, ships.names[id], ships.hulls[id]].some(function (s) { return (s || '').toLowerCase().indexOf(q) >= 0; }); };
    var srv = ships.server.filter(match), loc = ships.local.filter(match);
    var known = {};
    ships.server.concat(ships.local).forEach(function (id) { known[id] = 1; });
    if (!q) { addOpt('', defaultLabel(ships.defaultId), 'default', 'dflt', 'dflt'); }
    if (ships.current && !known[ships.current] && match(ships.current)) { addHead('Current build', 1); addOpt(ships.current, ships.current, 'current', null, null); }
    if (cur && !known[cur] && cur !== ships.current && match(cur)) { addHead('Picked', 1); addOpt(cur, cur, null, null, 'typed'); }
    if (srv.length) { addHead('Server ships', srv.length); srv.forEach(function (id) { addOpt(id, shipLabel(id), id === ships.current ? 'current' : (id === ships.defaultId ? 'default' : null), id === ships.current ? null : 'dflt', null); }); }
    if (loc.length) { addHead('Local customs', loc.length); loc.forEach(function (id) { addOpt(id, id, id === ships.current ? 'current' : null, null, null); }); }
    if (q && !known[q] && !known[$('shipSearch').value.trim()]) {
      var typed = $('shipSearch').value.trim(), problem = shipProblem(typed);
      if (!problem) { addHead('Typed id', 1); addOpt(typed, typed, 'use as typed', 'dflt', 'typed'); }
      else if (!srv.length && !loc.length) { var e = document.createElement('div'); e.className = 'empty'; e.textContent = problem; list.appendChild(e); }
    }
    if (!shipUi.rows.length && !list.firstChild) { var none = document.createElement('div'); none.className = 'empty'; none.textContent = 'no ship matches'; list.appendChild(none); }
    if (shipUi.active < 0 && shipUi.rows.length) { shipUi.active = 0; }
    setActiveRow(shipUi.active);
  }
  function setActiveRow(i) {
    shipUi.rows.forEach(function (r, k) { r.classList.toggle('active', k === i); });
    shipUi.active = i;
    var r = shipUi.rows[i];
    if (r && r.scrollIntoView) { r.scrollIntoView({ block: 'nearest' }); }
  }
  function openShipPop() {
    if (shipUi.open || !inForm()) { return; }
    shipUi.open = true;
    $('shipPop').hidden = false;
    $('shipBtn').setAttribute('aria-expanded', 'true');
    $('shipSearch').value = '';
    renderShipList('');
    $('shipSearch').focus();
  }
  function closeShipPop() {
    if (!shipUi.open) { return; }
    shipUi.open = false;
    $('shipPop').hidden = true;
    $('shipBtn').setAttribute('aria-expanded', 'false');
  }
  $('shipBtn').addEventListener('click', function () { if (shipUi.open) { closeShipPop(); } else { openShipPop(); } });
  $('shipSearch').addEventListener('input', function () { renderShipList($('shipSearch').value); });
  $('shipSearch').addEventListener('keydown', function (e) {
    
    if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); closeShipPop(); $('shipBtn').focus(); return; }
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      if (!shipUi.rows.length) { return; }
      var n = shipUi.rows.length, i = shipUi.active < 0 ? 0 : (shipUi.active + (e.key === 'ArrowDown' ? 1 : n - 1)) % n;
      setActiveRow(i); return;
    }
    if (e.key === 'Enter') {
      e.preventDefault(); e.stopPropagation();
      var r = shipUi.rows[shipUi.active] || shipUi.rows[0];
      if (r) { pickShip(r.dataset.id); } else { closeShipPop(); $('shipBtn').focus(); }
    }
  });
  document.addEventListener('pointerdown', function (e) { if (shipUi.open && !$('shipCombo').contains(e.target)) { closeShipPop(); } });

  
  
  
  
  var ROW_CAP = 4;
  function renderRows(boxId, headText, items, render) {
    var box = $(boxId);
    while (box.firstChild) { box.removeChild(box.firstChild); }
    if (!items || !items.length) { box.hidden = true; return; }
    box.hidden = false;
    var head = document.createElement('div'); head.className = 'head'; head.textContent = headText; box.appendChild(head);
    items.forEach(function (it, i) { var row = render(it); if (i >= ROW_CAP) { row.hidden = true; } box.appendChild(row); });
    if (items.length > ROW_CAP) {
      var more = document.createElement('button'); more.type = 'button'; more.className = 'link';
      more.textContent = '+' + (items.length - ROW_CAP) + ' more - show all';
      more.addEventListener('click', function () {
        Array.prototype.forEach.call(box.querySelectorAll('.vrow'), function (r) { r.hidden = false; });
        more.hidden = true;
      });
      box.appendChild(more);
    }
  }
  function textRow(text, cls) {
    var row = document.createElement('div'); row.className = 'vrow' + (cls ? ' ' + cls : '');
    var t = document.createElement('span'); t.className = 'txt'; t.textContent = text; t.title = text; row.appendChild(t);
    return row;
  }
  function showCustoms(notes) {
    renderRows('customs', 'Local custom files', notes, function (n) { return textRow(n, 'plain'); });
  }
  var VERDICT_CLASS = { ok: 'ok', reject: 'bad', unavailable: 'warn', unknown: 'wait' };
  var VERDICT_WORD = { ok: 'ok', reject: 'reject', unavailable: 'off', unknown: 'check' };
  function showCustomVerdicts(ids, verdicts) {
    var rows = [];
    (ids || []).forEach(function (id) {
      if (!verdicts || !Object.prototype.hasOwnProperty.call(verdicts, id)) { return; }
      var v = verdicts[id];
      if (v && typeof v.line === 'string') { rows.push(v); }
    });
    renderRows('verdicts', 'Server check', rows, function (v) {
      var known = Object.prototype.hasOwnProperty.call(VERDICT_CLASS, v.status);
      var row = textRow(v.line, null);
      var pill = document.createElement('span');
      pill.className = 'verdict ' + (known ? VERDICT_CLASS[v.status] : 'wait');
      pill.textContent = known ? VERDICT_WORD[v.status] : 'check';
      row.insertBefore(pill, row.firstChild);
      return row;
    });
  }

  
  
  var MOD_CLASS = { ok: 'ok', missing: 'bad', version: 'bad', content: 'bad', unreadable: 'warn' };
  var MOD_WORD = { ok: 'ok', missing: 'missing', version: 'version', content: 'files', unreadable: 'check' };
  function showMods(rows) {
    renderRows('mods', 'Server mods', rows || [], function (m) {
      var known = Object.prototype.hasOwnProperty.call(MOD_CLASS, m.status);
      var row = textRow(m.line || m.id, null);
      var pill = document.createElement('span');
      
      pill.className = 'verdict ' + (m.optional ? (m.status === 'ok' ? 'ok' : 'wait') : (known ? MOD_CLASS[m.status] : 'wait'));
      pill.textContent = m.optional ? 'optional' : (known ? MOD_WORD[m.status] : 'check');
      row.insertBefore(pill, row.firstChild);
      return row;
    });
  }

  
  
  
  
  
  
  
  
  var BLOCKED_LINE = 'Ship list: press Enter to check the password';   
  var pwCommitted = false, pwEditedSinceCommit = false;
  function pwCommit() { pwCommitted = true; pwEditedSinceCommit = false; }
  function pwEdited() {
    readiness();
    
    
    
    
    
    if (!clean($('password').value) && rememberedHere()) { pwCommit(); fieldsChanged(); return; }
    pwCommitted = false; pwEditedSinceCommit = true;
    if (!$('refresh').disabled) { showBlocked(); }   
  }
  function showBlocked() { setText('shipStatus', probeable() ? BLOCKED_LINE : ((st && st.notReadyLine) || ''), false); }
  function commitAndProbe() { pwCommit(); probe(); }

  var probeTimer = null, probeGen = 0;
  function fieldsChanged() {   
    readiness();
    passwordHint();   
    if (probeTimer) { clearTimeout(probeTimer); }
    probeTimer = setTimeout(debounceFired, 700);
  }
  function debounceFired() {
    probeTimer = null;
    if (pwCommitted) { probe(); } else { showBlocked(); }
  }
  
  
  
  
  
  function foldAz(s) { return s.replace(/[A-Z]/g, function (c) { return c.toLowerCase(); }); }
  function rememberedHere() {
    return remembered && !!st && foldAz($('host').value.trim()) === foldAz(String(st.host || '').trim())
      && $('port').value.trim() === String(st.port || '').trim();
  }
  function passwordHint() {
    var here = rememberedHere();
    $('password').placeholder = here ? '(remembered)' : '';
    setText('passwordHint', here ? 'remembered from the last launch for this address and port - type to replace'
      : 'ask the host - one password admits everyone', false);
  }
  function probeable() {
    var pw = $('password').value;
    var havePassword = clean(pw) ? !passwordProblem(clean(pw)) : rememberedHere();
    return !hostProblem($('host').value.trim()) && !portProblem($('port').value.trim()) && havePassword;
  }
  function probe() {
    if (probeTimer) { clearTimeout(probeTimer); probeTimer = null; }
    if (!inForm()) { return; }
    var h = $('host').value.trim(), p = $('port').value.trim(), pw = $('password').value;
    if (!probeable()) { setText('shipStatus', (st && st.notReadyLine) || '', false); return; }   
    var gen = ++probeGen;
    setText('shipStatus', 'Ship list: asking ' + h + ':' + p + ' ...', false);
    $('refresh').disabled = true;
    postForm('api/probe', { host: h, port: p, password: pw }).then(function (j) {
      if (gen !== probeGen) { return; }
      $('refresh').disabled = false;
      if (!j || !j.ok) { setText('shipStatus', (j && j.error) || 'Ship list: the probe failed - you can still type a ship id.', true); return; }
      if (j.status === 'stale') { return; }
      showProbe(j);
    }).catch(function (err) {
      if (gen !== probeGen) { return; }
      $('refresh').disabled = false;
      setText('shipStatus', 'Ship list: ' + err.message + ' - you can still type a ship id.', true);
    });
  }
  
  
  
  
  
  function showTeams(two) {
    $('teamRow').hidden = !two;
    if (!two) { var r = document.querySelector('input[name="team"][value="0"]'); if (r) { r.checked = true; } }
  }
  function teamPick() {
    if ($('teamRow').hidden) { return '0'; }
    var r = document.querySelector('input[name="team"]:checked');
    return r && /^[012]$/.test(r.value) ? r.value : '0';
  }
  function showProbe(j) {
    showTeams(j.status === 'served' && j.sides === 2);
    setText('shipStatus', j.line || '', j.status === 'rejected' || j.status === 'error');
    setText('docLine', j.docLine || '', j.status === 'served' && j.docOk === false && !!j.docLine);
    showCustomVerdicts([], null);
    setText('modsLine', '', false); showMods([]);
    if (j.status === 'served') {
      fillShips(j.ids, j.localCustoms, j.names, j.hulls);
      showCustoms(j.customsNotes);
      showCustomVerdicts(j.localCustoms, j.customsVerdicts);
      setShipDefault(j.defaultId);
      setText('modsLine', j.modsLine || '', j.modsOk === false);
      showMods(j.mods);
    }
  }

  
  var pasteTimer = null, pasteWasPaste = false;
  function pasteChanged(e) {
    
    pasteWasPaste = !!(e && (e.inputType === 'insertFromPaste' || e.inputType === 'insertFromDrop'));
    var ta = $('paste');
    ta.rows = Math.min(6, Math.max(1, ta.value.split('\n').length));
    if (pasteTimer) { clearTimeout(pasteTimer); }
    pasteTimer = setTimeout(sendPaste, 250);
  }
  function stagedLine(s) { return 'match fetched from the server: match ' + s.matchId + ' (' + s.seats + ' seats) - saved as match.txt on Launch'; }
  function sendPaste() {
    pasteTimer = null;
    
    
    
    
    var fields = { text: $('paste').value };
    if (pwCommitted && probeable()) {
      fields.host = $('host').value.trim(); fields.port = $('port').value.trim(); fields.password = $('password').value;
    }
    var gen = probeGen;
    postForm('api/paste', fields).then(function (j) {
      if (!j) { return; }
      if (!j.ok) { setText('pasteStatus', j.error || 'paste not understood', true); readiness(); return; }
      if (j.kind === 'brick') {
        $('host').value = j.host || ''; $('port').value = j.port || ''; $('password').value = j.password || '';
        setText('pasteStatus', 'connect code: filled address, port and password', false);
        
        if (pasteWasPaste) { pwCommit(); $('paste').value = ''; } else { pwCommitted = false; pwEditedSinceCommit = true; }
        fieldsChanged();   
      } else if (j.kind === 'empty') {
        
        
        setText('pasteStatus', '', false);
        if (j.probe && j.probe.ok && j.probe.status !== 'stale' && gen === probeGen && inForm()) {
          ++probeGen;   
          $('refresh').disabled = false;
          showProbe(j.probe);
        }
        readiness();
      } else {
        setText('pasteStatus', '', false);
        readiness();
      }
    }).catch(function (err) { setText('pasteStatus', 'paste failed: ' + err.message, true); });
  }

  
  var GATE_FIELD = { address: 'host', password: 'password', ship: 'ship' };
  function launch() {
    pwCommit();   
    if (!readiness()) { return; }
    $('launch').disabled = true;
    stopTest(false);   
    setText('note', 'launching...', false);
    
    
    var fields = { host: $('host').value.trim(), port: $('port').value.trim(), name: $('name').value,
      password: $('password').value, ship: $('ship').value, logs: $('logs').checked ? 'on' : 'off', team: teamPick() };
    var v = voiceFields();
    for (var k in v) { if (Object.prototype.hasOwnProperty.call(v, k)) { fields[k] = v[k]; } }
    postForm('api/launch', fields).then(function (j) {
      if (!j) { throw new Error('no reply'); }
      if (!j.ok) {
        setText('note', (j.title ? j.title + ': ' : '') + (j.error || 'refused'), true);
        var f = GATE_FIELD[j.gate];
        if (f) { $(FACE[f] || f).classList.add('bad'); $(FACE[f] || f).focus(); }
        $('launch').disabled = !inForm();
        return;
      }
      setText('note', j.warning || '', !!j.warning);   
      launched({ host: $('host').value.trim(), port: $('port').value.trim(), name: clean($('name').value), ship: j.ship || '' });
      pollFace();
    }).catch(function (err) { setText('note', 'launch failed: ' + err.message, true); readiness(); });
  }
  function launched(pick) {
    stopTest(false);
    closeShipPop();
    setState('launched');
    $('password').value = ''; $('paste').value = '';   
    $('form').hidden = true; $('face').hidden = false; $('launch').hidden = true; $('cancel').hidden = true;
    status('launched - Starsector is starting');
    setText('facePick', 'Joining ' + (pick.host || '') + ':' + (pick.port || '') + (pick.name ? ' as ' + pick.name : '')
      + ' - ship: ' + (pick.ship || 'server default'), false);
    if (st && st.face) { setText('facePurpose', st.face.purpose); setText('faceHint', st.face.hint); }
    setText('faceLine', (st && st.companion && st.companion.line) || 'Starsector: starting...', false);
  }
  var faceTimer = null, autoOpenNoted = false;
  function pollFace() {
    getJson('api/state').then(function (s) {
      st = s;
      var c = s.companion;
      if (c) {
        setText('faceLine', c.line || '', false);
        editorUrl = c.editor || null;
        $('openEditor').disabled = !editorUrl;
        if (c.seenLive && s.face) { setText('facePurpose', s.face.live); }
        if (c.autoOpened && !autoOpenNoted) { autoOpenNoted = true; setText('faceHint', 'the editor opened in a new tab - press Open editor if it did not', false); }
        if (c.verdict === 'exit') { status('Starsector closed - you can close this tab'); setState('exit'); return; }
      }
      faceTimer = setTimeout(pollFace, 2000);
    }).catch(function () { status('the join window has closed - you can close this tab'); setState('closed'); });
  }
  $('openEditor').addEventListener('click', function () { if (editorUrl) { window.open(editorUrl, '_blank', 'noopener'); } });

  
  
  
  function openEditorFromForm() {
    $('edit').disabled = true;
    getJson('api/editor').then(function (j) {
      $('edit').disabled = false;
      if (!j || !j.ok || !j.editor) { setText('editHint', (j && j.error) || 'launch first - the editor opens from the running game', false); return; }
      editorUrl = j.editor;
      var tab = window.open(j.editor, '_blank', 'noopener');
      setText('editHint', tab === null ? 'your browser blocked the new tab - press Open editor again' : (j.line || 'editor opened in a new tab'), tab === null);
    }).catch(function (err) { $('edit').disabled = false; setText('editHint', 'editor: ' + err.message, true); });
  }
  $('edit').addEventListener('click', openEditorFromForm);

  
  
  
  var levelTimer = null, testing = false;
  function fillDevices(j) {
    var sel = $('mic');
    while (sel.firstChild) { sel.removeChild(sel.firstChild); }
    var d0 = document.createElement('option'); d0.value = '0'; d0.textContent = j.defaultLabel || 'System default'; sel.appendChild(d0);
    (j.devices || []).forEach(function (d) {
      var o = document.createElement('option');
      o.value = String(d.index);
      o.textContent = (d.name || '') + (d.lost ? ' (name not saveable - the default is used)' : '');
      sel.appendChild(o);
    });
    sel.value = String(j.selected || 0);
    if (sel.value !== String(j.selected || 0)) { sel.value = '0'; }
    setText('voiceStatus', j.line || '', false);
  }
  function fillQuality() {
    var q = $('quality');
    for (var c = 0; c <= 10; c++) { var o = document.createElement('option'); o.value = String(c); o.textContent = String(c); q.appendChild(o); }
  }
  function voiceFields() {
    return { voiceDevice: $('mic').value, voiceOn: $('micOn').checked ? 'on' : 'off', voiceGain: $('gain').value, voiceQuality: $('quality').value };
  }
  function showTesting(on) {
    testing = on;
    $('voiceTest').textContent = on ? 'Stop' : 'Test';
    $('voiceTest').classList.toggle('on', on);
    if (!on) { $('level').value = 0; }
  }
  function pollLevel() {
    levelTimer = null;
    getJson('api/voice/level').then(function (j) {
      if (!testing) { return; }
      $('level').value = j.level || 0;
      if (j.line) { setText('voiceStatus', j.line, !j.running); }
      if (!j.running) { showTesting(false); return; }   
      levelTimer = setTimeout(pollLevel, 100);
    }).catch(function () { showTesting(false); });
  }
  function startTest() {
    if (!inForm()) { return; }
    showTesting(true);
    postForm('api/voice/test', { action: 'start', device: $('mic').value, gain: $('gain').value }).then(function (j) {
      if (!testing) { return; }
      if (!j || !j.ok) { setText('voiceStatus', (j && j.error) || 'the test could not start', true); showTesting(false); return; }
      setText('voiceStatus', j.line || '', false);
      levelTimer = setTimeout(pollLevel, 100);
    }).catch(function (err) { setText('voiceStatus', 'test failed: ' + err.message, true); showTesting(false); });
  }
  function stopTest(tellWindow) {
    if (levelTimer) { clearTimeout(levelTimer); levelTimer = null; }
    var was = testing;
    showTesting(false);
    if (was && tellWindow) { postForm('api/voice/test', { action: 'stop' }).catch(function () {}); }
  }
  $('voiceTest').addEventListener('click', function () { if (testing) { stopTest(true); setText('voiceStatus', '', false); } else { startTest(); } });
  $('mic').addEventListener('change', function () { if (testing) { startTest(); } });
  $('gain').addEventListener('input', function () {
    setText('gainLabel', $('gain').value + '%', false);
    if (testing) { postForm('api/voice/test', { action: 'gain', gain: $('gain').value }).catch(function () {}); }
  });
  function fillVoice(v) {
    v = v || {};
    $('micOn').checked = !!v.on;
    $('gain').value = typeof v.gain === 'number' ? v.gain : 100;
    setText('gainLabel', $('gain').value + '%', false);
    $('quality').value = (typeof v.quality === 'number' && v.quality >= 0) ? String(v.quality) : '';
  }

  
  function fill(s) {
    $('host').value = s.host || '';
    $('port').value = s.port || '';
    $('name').value = s.name || '';
    ships.current = s.currentBuild || '';
    setShipDefault(s.fetchedDefault);
    pickShip(s.initialShip || '', { quiet: true });
    $('password').value = '';
    remembered = !!s.hasPassword;
    pwCommitted = remembered; pwEditedSinceCommit = false;   
    passwordHint();
    setText('kit', s.kitCredential ? 'match: a saved copy is beside join-cc.bat - the server is asked whether it is current'
      : 'match: none saved yet - the server sends it once the password checks out', false);
    
    setText('build', s.currentBuild ? 'Current build: ' + s.currentBuild + ' (flies unless you pick the server default above - that clears it)'
      : 'Current build: none - the ship above (or the server default) flies', false);
    fillShips([], s.localCustoms);
    showCustoms(s.customsNotes);
    setText('shipStatus', s.notReadyLine || '', false);
    setText('pasteStatus', s.staged ? stagedLine(s.staged) : '', false);
    $('logs').checked = s.logs !== false;
    fillVoice(s.voice);
    setState(s.state || 'form');
    status(s.state === 'form' ? 'join window connected' : ('join window: ' + s.state));
  }

  
  var alivePeriod = 2000, misses = 0, aliveTimer = null;
  function beat() {
    postForm('api/alive').then(function (j) {
      if (misses >= 3) { status(inForm() ? 'join window connected' : ('join window: ' + document.body.dataset.state)); }
      misses = 0;
      if (j && j.state && j.state !== document.body.dataset.state && inForm()) { setState(j.state); }
    }).catch(function () {
      misses++;
      
      
      
      
      if (misses >= 3) {
        status(misses < 30 ? 'join window not answering - reconnecting (' + misses + ')'
          : 'join window not answering - if the launcher console was closed, run join-cc.bat again');
      }
    });
  }
  function closed() {
    if (aliveTimer) { clearInterval(aliveTimer); aliveTimer = null; }
    if (faceTimer) { clearTimeout(faceTimer); faceTimer = null; }
    setState('closed');
    status('the join window has closed - you can close this tab');
    $('cancel').disabled = true;
    $('launch').disabled = true;
  }

  
  function cancel() {
    if (!inForm()) { return; }
    if (!window.confirm('Cancel the join? The launcher will stop without starting the game.')) { return; }
    $('cancel').disabled = true;
    postForm('api/cancel').then(function () { closed(); status('cancelled - you can close this tab'); })
      .catch(function () { closed(); });
  }

  
  $('cancel').addEventListener('click', cancel);
  $('launch').addEventListener('click', launch);
  $('refresh').addEventListener('click', commitAndProbe);
  ['host', 'port'].forEach(function (id) { $(id).addEventListener('input', fieldsChanged); });
  
  $('password').addEventListener('input', pwEdited);
  
  
  
  
  $('password').addEventListener('blur', function () {
    if (pwEditedSinceCommit && document.hasFocus()) { commitAndProbe(); }
  });
  $('password').addEventListener('keydown', function (e) {
    if (e.key !== 'Enter' || !pwEditedSinceCommit) { return; }   
    e.preventDefault(); e.stopPropagation(); commitAndProbe();
  });
  $('name').addEventListener('input', readiness);
  $('paste').addEventListener('input', pasteChanged);
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') { e.preventDefault(); cancel(); return; }
    if (e.key === 'Enter') {
      var t = e.target;
      if (t && t.tagName === 'TEXTAREA' && !e.ctrlKey) { return; }   
      if (t && t.tagName === 'BUTTON') { return; }                    
      e.preventDefault();
      if (inForm() && !$('launch').disabled) { launch(); }
    }
  });

  
  document.addEventListener('visibilitychange', function () { if (!document.hidden && aliveTimer) { beat(); } });

  
  fillQuality();
  getJson('api/state').then(function (s) {
    st = s;
    fill(s);
    if (s.alivePeriodMs) { alivePeriod = s.alivePeriodMs; }
    beat();
    aliveTimer = setInterval(beat, alivePeriod);
    if (s.state === 'launched') { launched(s.launched || {}); pollFace(); }
    else {
      readiness(); probe();   
      getJson('api/voice/devices').then(fillDevices).catch(function (err) { setText('voiceStatus', 'microphones: ' + err.message, true); });
    }
  }).catch(function (err) {
    setState('error');
    status('could not reach the join window: ' + err.message);
  });
})();
