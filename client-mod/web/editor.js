




(function () {
  'use strict';
  var base = location.pathname.replace(/[^/]*$/, '');

  
  function getJson(path) {
    return fetch(base + path, { cache: 'no-store' }).then(function (r) {
      return r.json().then(function (j) { if (!r.ok && j && j.error) { throw new Error(j.error); } return j; });
    });
  }
  function postJson(path, body) {
    return fetch(base + path, { method: 'POST', cache: 'no-store', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body) }).then(function (r) {
      return r.text().then(function (t) {
        var j; try { j = JSON.parse(t); } catch (e) { j = null; }
        if (!r.ok) { throw new Error(j && j.error ? j.error : ('HTTP ' + r.status + (t ? ' ' + t.slice(0, 40) : ''))); }
        return j;
      });
    });
  }

  
  function $(id) { return document.getElementById(id); }
  function el(tag, attrs, children) {
    var e = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (k) {
        if (k === 'class') { e.className = attrs[k]; }
        else if (k === 'text') { e.textContent = attrs[k]; }
        else if (k === 'on') { Object.keys(attrs.on).forEach(function (ev) { e.addEventListener(ev, attrs.on[ev]); }); }
        else if (k === 'dataset') { Object.keys(attrs.dataset).forEach(function (d) { e.dataset[d] = attrs.dataset[d]; }); }
        else if (k === 'hidden') { e.hidden = !!attrs[k]; }
        else if (k === 'style') { e.style.cssText = attrs[k]; }   
        else { e.setAttribute(k, attrs[k]); }
      });
    }
    if (children) { children.forEach(function (c) { if (c != null) { e.appendChild(typeof c === 'string' ? document.createTextNode(c) : c); } }); }
    return e;
  }
  function clear(node) { while (node.firstChild) { node.removeChild(node.firstChild); } return node; }
  function fmt(n) { return (typeof n === 'number' && isFinite(n)) ? (Math.round(n * 10) / 10).toString() : String(n == null ? '' : n); }
  function sizeName(s) { return ({ FRIGATE: 'Frigate', DESTROYER: 'Destroyer', CRUISER: 'Cruiser', CAPITAL_SHIP: 'Capital', FIGHTER: 'Fighter' })[s] || s; }
  function sizeRank(s) { var r = ({ FRIGATE: 0, DESTROYER: 1, CRUISER: 2, CAPITAL_SHIP: 3 })[s]; return r == null ? 9 : r; }
  function byName(a, b) { return a.name.localeCompare(b.name); }
  function slug(s) { return s.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40) || 'custom'; }
  function typeLabel(t) { return ({ BALLISTIC: 'Ballistic', ENERGY: 'Energy', MISSILE: 'Missile', UNIVERSAL: 'Universal', HYBRID: 'Hybrid', SYNERGY: 'Synergy', COMPOSITE: 'Composite' })[t] || t; }
  function sizeShort(s) { return ({ SMALL: 'Small', MEDIUM: 'Medium', LARGE: 'Large' })[s] || s; }

  
  
  
  var RENDER_LAWS = window.ccStageScale.LAWS, RENDER_DEFAULT = window.ccStageScale.DEFAULT;   
  function savedRender() { return window.ccStageScale.saved(); }
  function setRender(id, persist) {
    if (!RENDER_LAWS[id]) { return; }
    S.render = id; document.body.dataset.render = id; window.ccStageScale.clear();
    if (persist) { try { localStorage.setItem('cc_editor_render', id); } catch (e) {   } }
    if (window.ccRender) { window.ccRender.sync(); }
    drawStage();
  }

  
  var S = {
    tier: 'standard',
    catalog: { hulls: [], weapons: [], hullmods: [], wings: [] },
    weaponById: {}, modById: {}, wingById: {},
    hull: null, sprite: null,
    fit: {}, groupOf: {}, groups: [],
    mods: [], smods: [], vents: 0, caps: 0, wings: [], name: '',
    selectedSlot: null, hoverSlot: null, selectedBay: null, wingSearch: '',
    hoverGroup: null, shiftGroup: false,   
    verdict: null, stats: null, gate: {}, gatePolls: 0, gateTimer: null,
    cr: 100,                   
    skills: false,             

    showDenied: false, hullSearch: '', weaponSearch: '', modSearch: '',
    seq: 0, timer: null, tab: 'arm', mrows: {}, msecs: [],
    shipClosed: {},            
    session: 'none',           
    pingTimer: null,
    lastWeapon: null,          
    flash: null,               
    mountCounts: { fitted: 0, total: 0 },
    mute: false, audio: {},    
    undo: [], redo: [], lastDoc: null,   
    
    
    drafts: {}, clean: {},
    fitSig: null,              
    returnGroups: true,        
    armOrigin: null,           
    server: [], peers: [], serverConnected: false, serverStamp: 0   
  };
  for (var g = 0; g < 7; g++) { S.groups.push({ mode: 'LINKED', autofire: false }); }
  S.returnGroups = savedReturnGroups();
  S.skills = savedSkills();
  document.body.dataset.skills = S.skills ? '1' : '0';   
  S.favs = savedFavs();            
  S.showD = savedShowD();          
  S.showDmods = savedShowDmods();  
  S.modCatalog = null;             
  
  
  
  
  S.host = { live: false, admin: false, submissions: false };
  S.catalogKnown = false; S.hostKey = ''; S.hostShips = null; S.hostLoading = false; S.hostSeq = 0;
  S.hostLine = null; S.hostBusy = false; S.hostSigning = false; S.hostSignMsg = null; S.loadedFrom = null; S.loadedSnap = null;
  S.hostAdopt = null;              
  S.groupsCursor = null;           
  S.biGroupOf = {};                
  S.hullFavs = savedHullFavs();    
  S.railBusy = false;              
  S.zoom = 1;                      
  S.pan = { x: 0, y: 0 };          
  S.spriteWaiters = [];            
  S.render = savedRender();        
  S.loadoutCursor = null;          
  S.cursorSlot = null;             
                                   
  S.hoverArm = null;               
                                   
  document.body.dataset.render = S.render; document.body.dataset.renderpanel = '0';
  S.fitK = 0; S.fitRatio = 0; S.fitInfo = null; S.zoomLive = false;   
  
  S.lastWing = null;               
  S.pinHeld = {};                  
  S.pinHoldArm = false;            
  S.cursorHoverBorn = false;       
  S.sfxVol = 100;                  
  S.gestureN = 0; S.sfxGesture = -1;   

  function variantDocument() {
    var h = S.hull, groups = [];
    for (var gi = 1; gi <= 7; gi++) {
      var weapons = {}, any = false;
      Object.keys(S.fit).forEach(function (slotId) {
        if (S.fit[slotId] && (S.groupOf[slotId] || 1) === gi) { weapons[slotId] = S.fit[slotId]; any = true; }
      });
      
      Object.keys(h ? (h.builtInWeapons || {}) : {}).forEach(function (slotId) {
        var x = slotById(slotId);
        if (x && !x.fittable && !x.hidden && !x.decorative && !x.system && !x.launchBay && !x.stationModule && groupOfSlot(slotId) === gi) { weapons[slotId] = h.builtInWeapons[slotId]; any = true; }
      });
      if (any) { groups.push({ mode: S.groups[gi - 1].mode, autofire: S.groups[gi - 1].autofire, weapons: weapons }); }
    }
    var smods = S.smods.filter(function (m) { return S.mods.indexOf(m) >= 0; });
    var name = S.name.trim() || ((h ? h.name : 'Ship') + ' Custom');
    return {
      variantId: (h ? h.id : 'none') + '_' + slug(name), hullId: h ? h.id : '', displayName: name,
      goalVariant: false, quality: 1, fluxVents: S.vents, fluxCapacitors: S.caps,
      hullMods: S.mods.slice(), permaMods: smods.slice(), sMods: smods.slice(), sModdedBuiltIns: [],
      wings: S.wings.filter(function (w) { return !!w; }), weaponGroups: groups
    };
  }

  
  function boot() {
    document.body.dataset.ready = '0';
    var hash = parseHash();
    if (hash.tier && /^(strict|standard|permissive)$/.test(hash.tier)) { S.tier = hash.tier; }
    if (hash.cr && /^[0-9]{1,3}$/.test(hash.cr)) { S.cr = Math.max(0, Math.min(100, parseInt(hash.cr, 10))); }   
    $('tier').value = S.tier;
    
    
    function ping(left) {
      return getJson('api/ping').catch(function (e) {
        if (left <= 0) { throw e; }
        return new Promise(function (ok) { setTimeout(ok, 400); }).then(function () { return ping(left - 1); });
      });
    }
    
    
    function start() {
      ping(2).then(function (p) {
        showPing(p);
        
        
        S.pingTimer = setInterval(function () { getJson('api/ping').then(showPing).catch(function () {}); }, 5000);
        return loadCatalog().then(function () {
          document.body.dataset.ready = '1';
          renderHullRail();
          buildModList();
          renderAll();
          loadSavedList();   
          loadServerList();  
          loadModCatalog();  
          sfxPreload();      
          var picked = hash.hull ? pickHull(hash.hull).then(function () { applyHashPrefill(hash); }) : Promise.resolve();
          
          return picked.then(function () { if (hash.codex && window.ccCodex) { window.ccCodex.open(hash.codex, { q: hash.q || '' }); } });
        }).catch(function (e) {
          $('ping').textContent = 'the editor could not load: ' + e.message;
          $('ping').className = 'err';
          document.body.dataset.ready = 'error';
        });
      }, function (e) {
        $('ping').textContent = 'no answer from the game: ' + e.message + ' — asking again in 5 s';
        $('ping').className = 'err';
        document.body.dataset.ready = 'error';
        S.pingTimer = setTimeout(start, 5000);
      });
    }
    start();
  }
  
  
  function showPing(p) {
    var st = String(p.state || '').toLowerCase();
    S.session = p.session === 'live' ? 'live' : 'none';
    var where = st === 'combat' ? 'game in a match' : st === 'title' ? 'game at the title screen' : st === 'campaign' ? 'game in the campaign' : 'game: ' + (st || 'unknown');
    
    
    
    S.next = p.next ? String(p.next) : ''; S.nextSource = p.nextSource || 'none'; S.nextVerdict = p.nextVerdict || null;
    var nextLine = S.next ? (S.session === 'live' ? ' · next connect: ' : ' · next spawn: ') + nextSpawnLabel(S.next) + (S.nextSource === 'pick' ? ' (join-window pick)' : '') : '';
    $('ping').textContent = where + ' · up ' + Math.round(p.uptimeMs / 1000) + ' s' + (S.session === 'live' ? ' · connected to a server' : '') + nextLine;
    var vst = S.nextVerdict ? String(S.nextVerdict.state || '') : '', cv = $('customVerdict');
    cv.textContent = (vst === 'accepted' || vst === 'rejected') ? String(S.nextVerdict.line || '') : '';
    cv.className = 'sub' + (vst === 'accepted' ? ' ok' : vst === 'rejected' ? ' err' : '');
    var cur = p.current === undefined ? (S.currentBuild || '') : String(p.current || '');
    if (cur !== (S.currentBuild || '')) { S.currentBuild = cur; if (S.saved) { renderSaved(); } }   
    
    
    
    var sel = $('tier'), note = $('tierNote');
    var locked = p.tierSource === 'server' && /^(strict|standard|permissive)$/i.test(String(p.defaultTier || ''));
    S.noSubs = locked && p.submissions === false;
    if (locked) {
      var serverTier = String(p.defaultTier).toLowerCase();
      var switched = S.tier !== serverTier ? ' · switched from ' + S.tier : '';
      note.textContent = 'server: ' + serverTier + ' · ' + p.perSeat + ' per seat · s-mods ' + (p.sModCap < 0 ? 'engine' : p.sModCap) + (S.noSubs ? ' · not accepting submissions' : '') + switched;
      note.title = 'The connected server decides the tier; the dropdown is locked while connected.';
      sel.disabled = true;
      if (S.tier !== serverTier) {
        S.tier = serverTier; sel.value = serverTier;
        reloadCatalog();
      }
    } else {
      sel.disabled = false;
      note.textContent = '';
      note.title = '';
    }
    
    
    $('fly').title = S.session === 'live'
      ? (S.noSubs ? 'save this build (this server is not accepting custom variants — it flies at your next connect)' : 'save this build and send it to the server — it flies at your next respawn')
      : 'save this build and make it your next spawn (the current build — the join window picks it too)';
    var hf = p.host || {};   
    S.host = { live: hf.live === true, admin: hf.live === true && hf.admin === true, submissions: hf.live === true && hf.submissions === true }; hostPingChanged();
  }
  function parseHash() {
    var out = {};
    location.hash.replace(/^#/, '').split('&').forEach(function (kv) {
      if (!kv) { return; }
      var i = kv.indexOf('=');
      out[decodeURIComponent(i < 0 ? kv : kv.slice(0, i))] = decodeURIComponent(i < 0 ? '' : kv.slice(i + 1));
    });
    return out;
  }
  function applyHashPrefill(h) {
    if (!S.hull) { return; }
    if (RENDER_LAWS[h.render]) { setRender(h.render, false); }   
    
    
    if (h.favs !== undefined) { setFavs(h.favs.split(',').filter(Boolean), true);}   
    
    
    if (h.hullfavs !== undefined) { setHullFavs(h.hullfavs.split(',').filter(Boolean), true); renderHullRail(); }
    if (h.showd === '0' || h.showd === '1') { setShowD(h.showd === '1', true); }      
    if (h.dmods === '0' || h.dmods === '1') { setShowDmods(h.dmods === '1', true); }  
    if (h.fit) { h.fit.split(',').forEach(function (pair) { var i = pair.indexOf(':'); if (i > 0) { S.fit[pair.slice(0, i)] = pair.slice(i + 1); } }); }
    if (h.mods) { S.mods = h.mods.split(',').filter(Boolean); }
    if (h.smods) { S.smods = h.smods.split(',').filter(Boolean); }
    if (h.vents) { S.vents = parseInt(h.vents, 10) || 0; }
    if (h.caps) { S.caps = parseInt(h.caps, 10) || 0; }
    if (h.wings) { S.wings = h.wings.split(','); }
    if (h.name) { S.name = h.name.slice(0, 48); $('name').value = S.name; syncPromoteRow(); }
    
    
    if (h.groups) { h.groups.split(',').forEach(function (pair) { var i = pair.indexOf(':'); var gi = parseInt(pair.slice(i + 1), 10); if (i > 0 && gi >= 1 && gi <= 7) { S.groupOf[pair.slice(0, i)] = gi; } }); }
    if (h.alt) { h.alt.split(',').forEach(function (g) { var gi = parseInt(g, 10); if (gi >= 1 && gi <= 7) { S.groups[gi - 1].mode = 'ALTERNATING'; } }); }
    if (h.auto) { h.auto.split(',').forEach(function (g) { var gi = parseInt(g, 10); if (gi >= 1 && gi <= 7) { S.groups[gi - 1].autofire = true; } }); }
    if (h.last && S.weaponById[h.last]) { S.lastWeapon = h.last; }
    if (h.lastwing && S.wingById[h.lastwing]) { S.lastWing = h.lastwing; }   
    if (h.sfxvol !== undefined) { setSfxVol(parseInt(h.sfxvol, 10), true); }   
    S.fitSig = fitSig();   
    undoFloor();   
    renderAll();
    
    
    
    if (h.strip) { stripAll(); }
    if (h.shift) { h.shift.split(',').forEach(function (id) { if (id) { shiftApply(id); } }); }
    if (h.undo) { for (var u = parseInt(h.undo, 10) || 0; u > 0; u--) { undo(); } }
    
    
    if (h.slot && slotById(h.slot)) { selectSlot(h.slot); setTab('arm'); }
    if (h.bay && parseInt(h.bay, 10) >= 1) { if (S.hull.bays > 0) { selectBay(parseInt(h.bay, 10) - 1); setTab('wings'); } else { S.bayWish = parseInt(h.bay, 10) - 1; } }   
    if (h.tab && /^(arm|groups|mods|ship|wings)$/.test(h.tab)) {
      
      
      if (h.tab === 'wings' && !(bayCount() > 0)) { S.wingsWish = true; } else { setTab(h.tab); }
    }
    changed();
    if (h.tip) { openTipFor(h.tip); }
    if (h.saved) { $('savedBox').open = true; }   
    if (h.server) { $('serverBox').open = true; }   
    if (h.catalog) { $('catalogBox').open = true; }   
    
    
    if (h.gequip) { var ge = h.gequip.split(':'); if (ge.length === 2 && slotById(ge[0]) && S.weaponById[ge[1]]) { S.armOrigin = ge[0]; setTab('arm'); selectSlot(ge[0]); S.fit[ge[0]] = ge[1]; if (!S.groupOf[ge[0]]) { S.groupOf[ge[0]] = 1; } drawStage(); renderArm(); changed(); returnToGroupsAfterEquip(ge[0]); } }
    
    if (h.esc) { document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })); }   
    
    
    if (h.keys) { window.ccKeys.toggle(); }
    if (h.renderpanel) { window.ccRender.open(); }   
    if (h.vwheel) { var vk = parseInt(h.vwheel, 10) || 0, vrow = $('ventsRange').parentElement; for (var vc = Math.abs(vk); vc > 0; vc--) { vrow.dispatchEvent(new WheelEvent('wheel', { deltaY: vk > 0 ? -100 : 100, bubbles: true, cancelable: true })); } }
    
    
    if (h.wheel) { h.wheel.split(',').forEach(function (n) { var k = parseInt(n, 10) || 0; for (var c = Math.abs(k); c > 0; c--) { $('stage').dispatchEvent(new WheelEvent('wheel', { deltaY: k > 0 ? 100 : -100, bubbles: true, cancelable: true })); } }); }
    if (h.gclear && slotById(h.gclear)) { var gtr = document.querySelector('.gMatrix tr[data-slot="' + h.gclear + '"]'); if (gtr) { document.body.dataset.gclear = gtr.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true })) ? 'menu' : 'prevented'; } }
    
    
    
    
    if (h.skills === '1' || h.skills === '0') { setSkills(h.skills === '1'); }
    if (h.segclick && S.opSegs && S.opSegs[h.segclick]) { S.opSegs[h.segclick].dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true })); }
    
    
    
    
    
    
    
    if (h.segrc && S.opSegs && S.opSegs[h.segrc]) { S.opSegs[h.segrc].dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true })); }
    if (h.segshift && S.opSegs && S.opSegs[h.segshift]) { S.opSegs[h.segshift].dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, shiftKey: true })); }
    if (h.modrc) { var mrc = h.modrc.split(':'), mrow = S.mrows[mrc[0]]; if (mrow) { mrow.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true, shiftKey: mrc[1] === 'shift' })); } }
    if (h.tabs) { for (var tc = parseInt(h.tabs, 10) || 0; tc > 0; tc--) { document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true })); } }
    if (h.railkey) {
      var rk = parseInt(h.railkey, 10) || 0, rdir = rk > 0 ? 1 : -1;
      if (rk) {
        $('rail').focus(); $('rail').dispatchEvent(new KeyboardEvent('keydown', { key: rdir > 0 ? 'ArrowDown' : 'ArrowUp', bubbles: true, cancelable: true }));
        var rchain = S.railPromise || Promise.resolve();
        for (var rc = Math.abs(rk) - 1; rc > 0; rc--) { rchain = rchain.then(function () { return railStep(rdir); }); }
      }
    }
    if (h.ctx) { var crow = $('weapons').querySelector('.wrow[data-weapon="' + h.ctx.replace(/"/g, '') + '"]'); if (crow) { crow.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true, clientX: 700, clientY: 300 })); } }
    if (h.ctxpick) { var cact = $('ctxMenu').querySelector('.r[data-act="' + h.ctxpick.replace(/"/g, '') + '"]'); if (cact) { cact.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true })); } }
    
    
    if (h.ctxkey) { document.dispatchEvent(new KeyboardEvent('keydown', { key: h.ctxkey.charAt(0), bubbles: true, cancelable: true })); }
    
    
    if (h.rearm) { var rrw = $('weapons').querySelector('.wrow[data-weapon="' + h.rearm.replace(/"/g, '') + '"]'); if (rrw) { rrw.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true })); } }
    
    
    
    
    
    var afterSprite = new Promise(function (ok) { if (S.sprite && S.fitInfo) { ok(); } else { S.spriteWaiters.push(ok); } });
    afterSprite.then(function () {
      if (h.zoom) { h.zoom.split(',').forEach(function (zs) { var zk = parseInt(zs, 10) || 0; for (var zc = Math.abs(zk); zc > 0; zc--) { $('stage').dispatchEvent(new WheelEvent('wheel', { deltaY: zk > 0 ? -100 : 100, shiftKey: true, bubbles: true, cancelable: true })); } }); }   
      if (h.pan) {
        var pk = h.pan.split(',').map(function (v) { return parseInt(v, 10) || 0; }), pr = canvas.getBoundingClientRect(), px0 = pr.left + 8, py0 = pr.top + 8;
        canvas.dispatchEvent(new MouseEvent('mousedown', { clientX: px0, clientY: py0, button: 0, buttons: 1, bubbles: true, cancelable: true }));
        document.dispatchEvent(new MouseEvent('mousemove', { clientX: px0 + pk[0], clientY: py0 + (pk[1] || 0), buttons: 1, bubbles: true, cancelable: true }));
        document.dispatchEvent(new MouseEvent('mouseup', { clientX: px0 + pk[0], clientY: py0 + (pk[1] || 0), button: 0, bubbles: true, cancelable: true }));
      }
      
      
      
      
      if (h.hover) { var hvm = markers.filter(function (x) { return x.id === h.hover; })[0], hvr = canvas.getBoundingClientRect(); if (hvm) { canvas.dispatchEvent(new MouseEvent('mousemove', { clientX: hvr.left + hvm.x, clientY: hvr.top + hvm.y, bubbles: true, cancelable: true })); } }
      if (h.mwheel) {
        var mk = h.mwheel.split(','), mn = parseInt(mk[1], 10) || 0, mm = markers.filter(function (x) { return x.id === mk[0]; })[0], mr = canvas.getBoundingClientRect();
        if (mm) { for (var mi = Math.abs(mn); mi > 0; mi--) { canvas.dispatchEvent(new WheelEvent('wheel', { deltaY: mn > 0 ? 100 : -100, clientX: mr.left + mm.x, clientY: mr.top + mm.y, bubbles: true, cancelable: true })); } }
      }
      if (h.stageclick) { var sr = canvas.getBoundingClientRect(); canvas.dispatchEvent(new MouseEvent('click', { clientX: sr.left + 8, clientY: sr.top + 8, button: 0, bubbles: true, cancelable: true })); }
      
      
      if (h.stagemove) { var smr = canvas.getBoundingClientRect(); canvas.dispatchEvent(new MouseEvent('mousemove', { clientX: smr.left + 8, clientY: smr.top + 8, bubbles: true, cancelable: true })); }
      
      
      if (h.paint) {
        var pids = h.paint.split(',').filter(Boolean), pr0 = canvas.getBoundingClientRect();
        pids.forEach(function (pid, pi) {
          var pmk = markers.filter(function (x) { return x.id === pid; })[0]; if (!pmk) { return; }
          canvas.dispatchEvent(new MouseEvent(pi === 0 ? 'mousedown' : 'mousemove', { clientX: pr0.left + pmk.x, clientY: pr0.top + pmk.y, button: 0, buttons: 1, shiftKey: true, bubbles: true, cancelable: true }));
        });
        document.dispatchEvent(new MouseEvent('mouseup', { button: 0, bubbles: true, cancelable: true }));
      }
      var navChain = h.switch ? h.switch.split(',').filter(Boolean).reduce(function (p, id) { return p.then(function () { return pickHull(id); }); }, Promise.resolve()) : Promise.resolve();
      
      if (h.nav) { navChain.then(function () { return h.nav.split('').reduce(function (p, c) { return p.then(function () { return c === 'b' ? navBack() : c === 'f' ? navFwd() : null; }); }, Promise.resolve()); }); }
    });
    if (h.ctxshift) { var csr = $('weapons').querySelector('.wrow[data-weapon="' + h.ctxshift.replace(/"/g, '') + '"]'); if (csr) { csr.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true, shiftKey: true, clientX: 700, clientY: 300 })); } }
    if (h.dockwheel) { var dk = parseInt(h.dockwheel, 10) || 0, dl = $('weapons'); for (var dc = Math.abs(dk); dc > 0; dc--) { document.body.dataset.dockwheel = dl.dispatchEvent(new WheelEvent('wheel', { deltaY: dk > 0 ? 100 : -100, bubbles: true, cancelable: true })) ? 'native' : 'prevented'; } }
    
    
    
    if (h.settings) { window.ccRender.open(); }
    if (h.qe || h.wasd) { ((h.qe || '') + (h.wasd || '')).split('').forEach(function (c) { if (WASD[c] || c === 'q' || c === 'e') { document.dispatchEvent(new KeyboardEvent('keydown', { key: c, bubbles: true, cancelable: true })); } }); }
    if (h.gwheel) { var gk = parseInt(h.gwheel, 10) || 0; for (var gc = Math.abs(gk); gc > 0; gc--) { $('groups').dispatchEvent(new WheelEvent('wheel', { deltaY: gk > 0 ? 100 : -100, bubbles: true, cancelable: true })); } }
    
    
    if (h.gwheelr) { var grk = parseInt(h.gwheelr, 10) || 0, grb = $('groups').getBoundingClientRect(); for (var grc = Math.abs(grk); grc > 0; grc--) { document.body.dataset.gwheelr = $('groups').dispatchEvent(new WheelEvent('wheel', { deltaY: grk > 0 ? 100 : -100, clientX: grb.left + grb.width * 0.75, clientY: grb.top + 40, bubbles: true, cancelable: true })) ? 'native' : 'prevented'; } }
    
    
    
    if (h.ghover && slotById(h.ghover)) { hoverMount(h.ghover, true); }
    if (h.gkey) { h.gkey.split('').forEach(function (c) { if (/^[1-7]$/.test(c)) { document.dispatchEvent(new KeyboardEvent('keydown', { key: c, bubbles: true, cancelable: true })); } }); }
    if (h.gctrl) { h.gctrl.split('').forEach(function (c) { if (/^[1-7]$/.test(c)) { document.dispatchEvent(new KeyboardEvent('keydown', { key: c, ctrlKey: true, bubbles: true, cancelable: true })); } }); }
    
    
    if (h.gpaint) {
      h.gpaint.split(',').filter(Boolean).forEach(function (pid, pi) {
        var prow = $('groups').querySelector('tr[data-slot="' + pid.replace(/"/g, '') + '"]'); if (!prow) { return; }
        prow.dispatchEvent(new MouseEvent(pi === 0 ? 'mousedown' : 'mouseenter', { button: 0, buttons: 1, shiftKey: true, bubbles: pi === 0, cancelable: true }));
      });
      document.dispatchEvent(new MouseEvent('mouseup', { button: 0, bubbles: true, cancelable: true }));
    }
    
    if (h.wwheel) { var wk = parseInt(h.wwheel, 10) || 0; for (var wc = Math.abs(wk); wc > 0; wc--) { $('wingsBody').dispatchEvent(new WheelEvent('wheel', { deltaY: wk > 0 ? 100 : -100, bubbles: true, cancelable: true })); } }
    
    
    
    
    
    if (h.lwheel) { var lk = parseInt(h.lwheel, 10) || 0; for (var lkc = Math.abs(lk); lkc > 0; lkc--) { $('tab-arm').dispatchEvent(new WheelEvent('wheel', { deltaY: lk > 0 ? 100 : -100, bubbles: true, cancelable: true })); } }
    if (h.baywheel) {   
      var bwk = h.baywheel.split(','), bwn = parseInt(bwk[1], 10) || 0, bwSel = '.bay[data-bay="' + (parseInt(bwk[0], 10) || 0) + '"]';
      for (var bwi = Math.abs(bwn); bwi > 0; bwi--) { var bwc = $('bays').querySelector(bwSel); if (!bwc) { break; } bwc.dispatchEvent(new WheelEvent('wheel', { deltaY: bwn > 0 ? 100 : -100, bubbles: true, cancelable: true })); }
    }
    if (h.dockclick) { $('tab-arm').dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true })); }
    if (h.tabclick) { var tbb = $('tabs').querySelector('button[data-tab="' + h.tabclick.replace(/"/g, '') + '"]'); if (tbb) { tbb.focus(); tbb.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true })); document.body.dataset.tabfocus = document.activeElement ? document.activeElement.tagName : ''; } }
    
    
    
    var cueEl = function (v) { var m = /^tab:([a-z]+)$/.exec(v); return m ? $('tabs').querySelector('button[data-tab="' + m[1] + '"]') : /^[A-Za-z][A-Za-z0-9]*$/.test(v) ? $(v) : null; };
    if (h.cuehover || h.cueclick) { S.mute = false; S.hoverTicks = true; }   
    if (h.cuehover) { var che = cueEl(h.cuehover); if (che) { che.dispatchEvent(new PointerEvent('pointerover', { bubbles: true })); } }
    if (h.cueclick) { var cce = cueEl(h.cueclick); if (cce) { cce.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true })); cce.dispatchEvent(new MouseEvent('click', { detail: 1, bubbles: true, cancelable: true })); } }
    if (h.space) { document.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true, cancelable: true })); }
    
    
    
    
    
    
    
    if (h.modadd && S.mrows[h.modadd]) { S.mrows[h.modadd].dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true })); }
    if (h.modscroll) { var msl = $('mods'); msl.scrollTop = parseInt(h.modscroll, 10) || 0; msl.dispatchEvent(new Event('scroll', { bubbles: false })); }
    ['bpaint', 'cpaint'].forEach(function (pk) {
      if (!h[pk]) { return; }
      var sel = pk === 'bpaint' ? '#wingsBody .wrow[data-bay="' : '#bays .bay[data-bay="';
      h[pk].split(',').filter(Boolean).forEach(function (bs, pi) {
        var node = document.querySelector(sel + (parseInt(bs, 10) || 0) + '"]'); if (!node) { return; }   
        node.dispatchEvent(new MouseEvent(pi === 0 ? 'mousedown' : 'mouseenter', { button: 0, buttons: 1, shiftKey: true, bubbles: pi === 0, cancelable: true }));
      });
      document.dispatchEvent(new MouseEvent('mouseup', { button: 0, bubbles: true, cancelable: true }));
    });
    if (h.wingrearm) { var wrr = $('wingsBody').querySelector('.wrow[data-wing="' + h.wingrearm.replace(/"/g, '') + '"]'); if (wrr) { wrr.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true })); } }
    if (h.bayrc) { var brn = $('wingsBody').querySelector('.wrow[data-bay="' + (parseInt(h.bayrc, 10) || 0) + '"]'); if (brn) { document.body.dataset.bayrc = brn.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true })) ? 'menu' : 'prevented'; } }
    if (h.chiprc) { var crn = $('bays').querySelector('.bay[data-bay="' + (parseInt(h.chiprc, 10) || 0) + '"]'); if (crn) { document.body.dataset.bayrc = crn.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true })) ? 'menu' : 'prevented'; } }
    if (h.wingctx) { var wcr = $('wingsBody').querySelector('.wrow[data-wing="' + h.wingctx.replace(/"/g, '') + '"]'); if (wcr) { wcr.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true, clientX: 700, clientY: 300 })); } }
    if (h.wingctx && h.ctxpick) { var wca = $('ctxMenu').querySelector('.r[data-act="' + h.ctxpick.replace(/"/g, '') + '"]'); if (wca) { wca.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true })); } }
    if (h.lwheelr) { var lrk = parseInt(h.lwheelr, 10) || 0, lrb = $('weapons').getBoundingClientRect(); for (var lrc = Math.abs(lrk); lrc > 0; lrc--) { document.body.dataset.lwheelr = $('weapons').dispatchEvent(new WheelEvent('wheel', { deltaY: lrk > 0 ? 100 : -100, clientX: lrb.left + lrb.width * 0.75, clientY: lrb.top + 40, bubbles: true, cancelable: true })) ? 'native' : 'prevented'; } }
  }
  function loadCatalog() {
    var q = '?tier=' + S.tier;
    
    
    var gen = S.catalogGen = (S.catalogGen || 0) + 1;
    return Promise.all([getJson('data/hulls' + q), getJson('data/weapons' + q), getJson('data/hullmods' + q), getJson('data/wings' + q)])
      .then(function (all) {
        if (gen !== S.catalogGen) { return; }
        S.catalog.hulls = all[0].sort(function (a, b) { return (sizeRank(a.size) - sizeRank(b.size)) || byName(a, b); });
        S.catalog.weapons = all[1].sort(byName);
        S.catalog.hullmods = all[2].sort(byName);
        S.catalog.wings = all[3].sort(byName);
        S.weaponById = {}; S.catalog.weapons.forEach(function (w) { S.weaponById[w.id] = w; });
        S.modById = {}; S.catalog.hullmods.forEach(function (m) { S.modById[m.id] = m; });
        S.wingById = {}; S.catalog.wings.forEach(function (w) { S.wingById[w.id] = w; });
        var allowed = function (list) { return list.filter(function (x) { return x.allowed; }).length; };
        $('counts').textContent = allowed(all[0]) + ' hulls · ' + allowed(all[1]) + ' weapons · ' + allowed(all[2]) + ' hullmods · ' + allowed(all[3]) + ' wings';
      });
  }
  
  
  
  function reloadCatalog() {
    S.drafts = {}; S.clean = {};   
    return loadCatalog().then(function () { renderHullRail(); buildModList(); updateModList(); renderWings(); loadServerList(); if (S.hull) { changed(); } })   
      .catch(function (e) { $('ping').textContent = 'could not load the ' + S.tier + ' tier: ' + e.message; $('ping').className = 'err'; });
  }
  function renderAll() { drawStage(); renderArm(); updateModList(); renderShip(); renderWings(); renderBudget(); if (S.modCatalog) { renderModCatalog(); } else { syncHostBuildRow(); } }

  
  function renderHullRail() {
    var box = clear($('hulls'));
    var q = S.hullSearch.toLowerCase();
    var sizes = ['FRIGATE', 'DESTROYER', 'CRUISER', 'CAPITAL_SHIP'];
    var curSize = S.hull ? S.hull.size : null;
    var anyOpen = false;
    function listed(h) {
      if (!h.allowed && !S.showDenied) { return false; }
      if (!S.showD && (h.dHull || /\(D\)\s*$/.test(h.name || ''))) { return false; }   
      return !q || (h.name + ' ' + h.id).toLowerCase().indexOf(q) >= 0;
    }
    
    
    function rowFor(h) {
      var draft = !!(S.drafts[h.id] && S.drafts[h.id].dirty);   
      var fav = isHullFav(h.id);
      var star = el('span', { class: 'star' + (fav ? ' on' : ''), text: '★', title: fav ? 'a favourite — click: unfavourite' : 'favourite this hull (pins it to the top of its size + the Favourites section)' });
      star.addEventListener('click', function (ev) { ev.stopPropagation(); toggleHullFav(h.id); });
      var row = el('div', { class: 'hullRow' + (S.hull && S.hull.id === h.id ? ' sel' : '') + (h.allowed ? '' : ' denied') + (fav ? ' fav' : ''), dataset: { id: h.id, draft: draft ? '1' : '0' },
        title: (h.allowed ? h.designation || '' : ('not allowed: ' + (h.reason || 'tier'))) + (h.bays ? ' · ' + h.bays + ' bay' + (h.bays > 1 ? 's' : '') : '') + (draft ? ' · unsaved draft kept this session' : '') }, [
        el('img', { src: base + 'sprite/' + encodeURIComponent(h.id), alt: '', loading: 'lazy' }),
        el('span', { class: 'name' }, [h.name, draft ? draftDot() : null]),   
        el('span', { class: 'op' }, [h.op + ' OP', star])
      ]);
      codexDoor(row, 'codex_hull_' + h.id);
      if (h.allowed) { row.addEventListener('click', function () { pickHull(h.id); }); }
      return row;
    }
    var favRows = S.catalog.hulls.filter(function (h) { return isHullFav(h.id) && listed(h); });
    if (favRows.length) {
      var fdet = el('details', { open: '', dataset: { size: 'FAV' } }, [el('summary', {}, ['Favourites', el('span', { class: 'n', text: String(favRows.length) })])]);
      favRows.forEach(function (h) { fdet.appendChild(rowFor(h)); });
      box.appendChild(fdet);
    }
    sizes.forEach(function (size) {
      var rows = S.catalog.hulls.filter(function (h) { return h.size === size && listed(h); })
        .map(function (h, i) { return { h: h, i: i }; })
        .sort(function (a, b) { return ((isHullFav(b.h.id) ? 1 : 0) - (isHullFav(a.h.id) ? 1 : 0)) || (a.i - b.i); })   
        .map(function (x) { return x.h; });
      if (!rows.length) { return; }
      
      
      var open = !!q || size === curSize;
      anyOpen = anyOpen || open;
      
      
      var det = el('details', open ? { open: '', dataset: { size: size } } : { dataset: { size: size } }, [
        el('summary', {}, [sizeName(size) + 's', el('span', { class: 'n', text: String(rows.length) })])
      ]);
      rows.forEach(function (h) { det.appendChild(rowFor(h)); });
      box.appendChild(det);
    });
    renderDraftHint();
  }
  
  
  
  
  
  function railStep(dir) {
    if (S.railBusy) { return Promise.resolve(); }
    var rows = Array.prototype.slice.call($('hulls').querySelectorAll('details:not([data-size="FAV"]) .hullRow:not(.denied)'));   
    if (!rows.length) { return Promise.resolve(); }
    var i = -1; rows.forEach(function (r, k) { if (S.hull && r.dataset.id === S.hull.id) { i = k; } });
    var j = i < 0 ? (dir > 0 ? 0 : rows.length - 1) : Math.max(0, Math.min(rows.length - 1, i + dir));
    if (j === i) { return Promise.resolve(); }
    var id = rows[j].dataset.id;
    S.railBusy = true;
    return pickHull(id).then(function () {
      S.railBusy = false;
      document.body.dataset.railkey = id;
      var row = $('hulls').querySelector('.hullRow.sel');
      if (row && row.scrollIntoView) { row.scrollIntoView({ block: 'nearest' }); }
    }, function () { S.railBusy = false; });
  }
  
  function savedShowD() { try { return localStorage.getItem('cc_editor_showd') !== '0'; } catch (e) { return true; } }
  function setShowD(on, view) {   
    S.showD = !!on;
    if (!view) { try { localStorage.setItem('cc_editor_showd', S.showD ? '1' : '0'); } catch (e) {   } }
    $('showD').checked = S.showD; document.body.dataset.showd = S.showD ? '1' : '0';
    renderHullRail();
  }
  
  
  
  
  function savedShowDmods() { try { return localStorage.getItem('cc_editor_showdmods') === '1'; } catch (e) { return false; } }
  function setShowDmods(on, view) {
    S.showDmods = !!on;
    if (!view) { try { localStorage.setItem('cc_editor_showdmods', S.showDmods ? '1' : '0'); } catch (e) {   } }
    $('showDmods').checked = S.showDmods; document.body.dataset.showdmods = S.showDmods ? '1' : '0';
    updateModList();
  }
  function draftCount() { return Object.keys(S.drafts).filter(function (id) { return S.drafts[id].dirty; }).length; }
  
  function draftDot() { return el('span', { class: 'draftDot', title: 'unsaved draft kept this session (lost on refresh)' }); }
  function refreshDraftDots() {
    Array.prototype.forEach.call($('hulls').querySelectorAll('.hullRow'), function (row) {
      var id = row.dataset.id, dirty = !!(S.drafts[id] && S.drafts[id].dirty);
      if ((row.dataset.draft === '1') === dirty) { return; }
      row.dataset.draft = dirty ? '1' : '0';
      var dot = row.querySelector('.draftDot');
      if (dirty && !dot) { row.querySelector('.name').appendChild(draftDot()); }   
      else if (!dirty && dot) { dot.parentNode.removeChild(dot); }
    });
    renderDraftHint();
  }
  function renderDraftHint() {
    var n = draftCount();
    document.body.dataset.drafts = String(n);
    $('draftHint').textContent = n ? (n + ' draft' + (n === 1 ? '' : 's') + ' kept this session — lost on refresh') : '';
  }
  
  
  function pickHull(id, opts) {
    return getJson('data/hull/' + encodeURIComponent(id) + '?tier=' + S.tier + '&allSkills=1').then(function (h) {
      S.hull = h;
      S.fit = {}; S.groupOf = {}; S.biGroupOf = {}; S.mods = []; S.smods = []; S.wings = []; S.selectedSlot = null; S.selectedBay = null;
      S.vents = 0; S.caps = 0; S.verdict = null; S.stats = null; S.gate = {}; S.flash = null; S.opSegGap = 0; S.bayWish = null;
      S.undo = []; S.redo = []; S.lastDoc = null;   
      S.cursorHoverBorn = false; S.pinHeld = {}; S.pinHoldArm = false;   
      S.armOrigin = null; S.groupsCursor = null; S.bayCursor = null; S.loadoutCursor = null; S.cursorSlot = null; S.hoverArm = null; S.cursorByTab = false;   
      navCommit();   
      var fresh = stateSnapshot();
      var d = S.drafts[h.id];
      if (d && !(opts && opts.noDraft)) { restoreState(d.snap); }
      if (!S.clean[h.id]) { S.clean[h.id] = fresh; }
      S.fitSig = null;   
      hideTip();
      document.body.dataset.hull = h.id;
      $('hullTitle').textContent = h.name;
      
      $('hullSub').textContent = (h.designation ? h.designation + ' · ' : '') + sizeName(h.size) + ' · ' + h.op + ' OP' + (h.dp > 0 ? ' · ' + fmt(h.dp) + ' DP' : '') + (h.bays ? ' · ' + h.bays + ' fighter bay' + (h.bays > 1 ? 's' : '') : '');
      $('saveResult').textContent = '';
      $('ventsRange').max = h.maxVents; $('capsRange').max = h.maxCaps;
      S.wingsWish = false;   
      if (S.tab === 'wings' && !(h.bays > 0)) { setTab('arm'); }   
      renderHullRail();
      loadSprite(h);
      renderAll();
      changed();
    }).catch(function (e) { $('ping').textContent = 'hull load failed: ' + e.message; $('ping').className = 'err'; });
  }

  
  var canvas, ctx, scale = 1, markers = [], imgCache = {};
  function img(url) {
    var c = imgCache[url];
    if (c) { return c.ok ? c.img : null; }
    var i = new Image();
    c = imgCache[url] = { img: i, ok: false, tries: 0 };
    i.onload = function () { c.ok = true; drawStage(); };
    
    
    i.onerror = function () { c.ok = false; if (c.tries++ < 1) { setTimeout(function () { i.src = url + (url.indexOf('?') < 0 ? '?' : '&') + 'r=' + c.tries; }, 600); } };
    i.src = url;
    return null;
  }
  function loadSprite(h) {
    S.sprite = null;
    S.zoom = 1; S.zoomN = 0; document.body.dataset.zoom = '1.00'; window.ccStageScale.clear();   
    S.pan = { x: 0, y: 0 }; S.drag = null; S.spriteWaiters = []; S.fitInfo = null;   
    S.zoomLive = false; if (zoomSettle) { clearTimeout(zoomSettle); zoomSettle = 0; }   
    var i = new Image();
    var flush = function () { var ws = S.spriteWaiters; S.spriteWaiters = []; ws.forEach(function (f) { f(); }); };   
    i.onload = function () { if (S.hull === h) { S.sprite = i; drawStage(); flush(); } };
    i.onerror = function () { if (S.hull === h) { drawStage(); flush(); } };
    i.src = base + 'sprite/' + encodeURIComponent(h.id);
  }
  
  
  function slotToPx(h, im, s) {
    var c = h.center && typeof h.center.x === 'number' ? h.center : { x: im.width / 2, y: im.height / 2 };
    return { x: c.x - s.y, y: (im.height - c.y) - s.x };
  }
  
  var MOUNT_COLOR = { BALLISTIC: '#ffd700', ENERGY: '#46c8ff', MISSILE: '#9bff00', UNIVERSAL: '#ffffff', HYBRID: '#ffa500', SYNERGY: '#00ffc8', COMPOSITE: '#d7ff00' };
  function slotColor(s) {
    if (!s.fittable) { return '#7d7d7d'; }
    return MOUNT_COLOR[s.type] || '#d7ff00';
  }
  function slotRadius(s) { return s.size === 'LARGE' ? 14 : s.size === 'MEDIUM' ? 10 : 7; }
  
  
  function drawEmptyMount(cx, cy, r, color, size, hardpoint) {
    var rings = size === 'LARGE' ? [1, 0.75, 0.5] : size === 'MEDIUM' ? [0.75, 0.5] : [0.5];
    ctx.save();
    ctx.translate(cx, cy);
    ctx.strokeStyle = color; ctx.fillStyle = color;
    ctx.globalAlpha = 0.35; ctx.beginPath(); ctx.arc(0, 0, r, 0, Math.PI * 2); ctx.fillStyle = 'rgba(0,0,0,0.55)'; ctx.fill();
    ctx.globalAlpha = 1; ctx.fillStyle = color;
    rings.forEach(function (k) { ctx.beginPath(); ctx.lineWidth = Math.max(1.5, r * 0.16); ctx.arc(0, 0, r * k, 0, Math.PI * 2); ctx.stroke(); });
    var b = r * 1.15, l = r * 0.45;
    ctx.lineWidth = Math.max(1.5, r * 0.14);
    [[1, 1], [-1, 1], [-1, -1], [1, -1]].forEach(function (q) {
      ctx.beginPath(); ctx.moveTo(q[0] * b, q[1] * (b - l)); ctx.lineTo(q[0] * b, q[1] * b); ctx.lineTo(q[0] * (b - l), q[1] * b); ctx.stroke();
    });
    if (hardpoint) { ctx.beginPath(); ctx.rect(-r * 0.22, -r * 0.22, r * 0.44, r * 0.44); ctx.fill(); }
    else { ctx.beginPath(); ctx.arc(0, 0, r * 0.2, 0, Math.PI * 2); ctx.fill(); }
    ctx.restore();
  }
  var PRE_MAX = window.ccStageScale.PRE_MAX, preScaled = window.ccStageScale.preScaled, boxScaled = window.ccStageScale.boxScaled, lanczosScaled = window.ccStageScale.lanczosScaled;   
  
  
  
  
  
  
  
  function drawSprite(img, x, y, w, hh) {
    if (!(img.width > 0)) { return ''; }
    var law = S.render, q = window.devicePixelRatio || 1, blocky = S.fitK >= 1 && (law === 'block' || law === 'chunky');
    
    
    if (S.fitPixel || (law === 'sharp' && S.fitStep >= 1) || (S.fitStep > PRE_MAX && (!blocky || S.zoomLive))) { ctx.imageSmoothingEnabled = false; ctx.drawImage(img, x, y, w, hh); return 'nn'; }
    var heavy = law === 'lanczos' || law === 'box' || law === 'block' || law === 'chunky';
    if (heavy && !S.zoomLive) {
      if (blocky) {
        var k = S.fitK, rw = Math.max(1, Math.round(img.width * S.fitRatio)), rh = Math.max(1, Math.round(img.height * S.fitRatio)), dw = rw * k / q, dh = rh * k / q;
        ctx.imageSmoothingEnabled = false;
        ctx.drawImage(lanczosScaled(img, rw, rh), x + (w - dw) / 2, y + (hh - dh) / 2, dw, dh);
        return 'block';
      }
      var tw = Math.max(1, Math.round(w * q)), th = Math.max(1, Math.round(hh * q));
      ctx.imageSmoothingEnabled = true; ctx.imageSmoothingQuality = 'high';
      ctx.drawImage(law === 'box' ? boxScaled(img, tw, th) : lanczosScaled(img, tw, th), x, y, w, hh);
      return law === 'box' ? 'box' : 'lanczos';
    }
    ctx.imageSmoothingEnabled = true; ctx.imageSmoothingQuality = 'high';
    ctx.drawImage(S.fitStep >= 2 ? preScaled(img, S.fitStep) : img, x, y, w, hh);
    return heavy ? 'live' : 'smooth';
  }
  
  
  
  var zoomSettle = 0;
  function setZoom(z, exact) {
    
    
    S.zoom = exact ? Math.max(0.25, Math.min(6, z)) : Math.round(Math.max(0.25, Math.min(6, z)) * 1000) / 1000;
    if (S.zoom <= 1) { S.pan = { x: 0, y: 0 }; }   
    if (!S.drag && S.hoverSlot === null) { canvas.style.cursor = S.zoom > 1 && S.sprite ? 'grab' : 'default'; }   
    document.body.dataset.zoom = S.zoom.toFixed(2);
    S.zoomLive = true;
    if (zoomSettle) { clearTimeout(zoomSettle); }
    zoomSettle = setTimeout(function () { zoomSettle = 0; S.zoomLive = false; drawStage(); }, 150);
    drawStage();
    var drawn = S.fitInfo ? S.fitInfo.scale * (window.devicePixelRatio || 1) : 0;
    flashHint((drawn > 0 ? 'ship ×' + drawn.toFixed(2) + ' · ' : '') + 'zoom ×' + S.zoom.toFixed(2) + ' · shift+wheel · resets when you pick another hull');
  }
  
  
  
  
  function zoomNotch(dir) {
    var dpr = window.devicePixelRatio || 1, q = S.render === 'whole' ? 1 : S.render === 'half' ? 0.5 : 0, f = S.fitInfo;
    if (q && f && f.d0 > 0) {
      var cur = f.scale * dpr, target;
      if (dir > 0) { target = cur >= q - 1e-6 ? (Math.floor(cur / q + 1e-6) + 1) * q : Math.min(cur * 1.15, q); }
      else { target = cur > q + 1e-6 ? (Math.ceil(cur / q - 1e-6) - 1) * q : cur / 1.15; }
      
      
      
      var home = dir < 0 ? (cur > f.s0 + 1e-6 && target < f.s0 + 1e-6) : (cur < f.s0 - 1e-6 && target > f.s0 - 1e-6);
      setZoom(home ? 1 : target / f.d0, true);
      S.zoomN = Math.round(Math.log(S.zoom) / Math.log(1.15));   
      return;
    }
    S.zoomN = Math.max(-9, Math.min(12, (S.zoomN || 0) + dir));   
    setZoom(Math.pow(1.15, S.zoomN));
  }
  function drawStage() {
    var h = S.hull, im = S.sprite, wrap = $('stageWrap');
    markers = [];
    var dpr = window.devicePixelRatio || 1;
    $('stageEmpty').hidden = !!h;
    if (!h || !im) {
      wrap.classList.remove('hasStats');
      canvas.width = 320 * dpr; canvas.height = 200 * dpr; canvas.style.width = '320px'; canvas.style.height = '200px';
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0); ctx.clearRect(0, 0, 320, 200);
      if (h) { ctx.fillStyle = '#a9b4b8'; ctx.font = '15px Bahnschrift, Segoe UI, sans-serif'; ctx.textAlign = 'center'; ctx.fillText('loading sprite…', 160, 104); }
      $('stageHint').textContent = '';
      return;
    }
    
    var gutter = bayCount() > 0 ? 80 : 0;
    wrap.classList.toggle('hasBays', gutter > 0);
    
    
    
    
    wrap.classList.add('hasStats');
    var maxW = Math.max(240, wrap.clientWidth - 24 - gutter), maxH = Math.max(200, wrap.clientHeight - 24);
    
    
    
    
    
    
    
    
    
    var cw = Math.floor(maxW), ch = Math.floor(maxH);
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    function snapScale(raw, cap) {   
      var d = cap > 0 ? Math.min(raw * dpr, cap) : raw * dpr, step = Math.floor(d + 1e-6), law = S.render;   
      if (step >= 1 && d / step < 1.1) { return { scale: step / dpr, pixel: true, step: step, d: d, snap: true }; }
      if (step < 1) { return { scale: d / dpr, pixel: false, step: 0, d: d }; }
      if (law === 'whole') { return { scale: step / dpr, pixel: true, step: step, d: d }; }
      if (law === 'half') { var dq = Math.floor(d * 2 + 1e-6) / 2; return { scale: dq / dpr, pixel: true, step: Math.ceil(dq), d: d }; }
      if (law === 'block' || law === 'chunky') {
        var k = Math.max(1, law === 'block' ? Math.round(d) : Math.ceil(d)), rw = Math.max(1, Math.round(im.width * d / k)), sd = rw * k / im.width;
        return { scale: sd / dpr, pixel: false, step: k, d: d, k: k, ratio: sd / k };
      }
      return { scale: d / dpr, pixel: false, step: Math.ceil(d), d: d };
    }
    function rawFit(w, hh) { return Math.min(w / im.width, hh / im.height) * 0.9; }   
    function place(f, x0, y0, w, hh) {   
      var sw = f.pixel ? im.width * f.scale : Math.round(im.width * f.scale), sh = f.pixel ? im.height * f.scale : Math.round(im.height * f.scale);
      return { pixel: f.pixel, step: f.step, scale: f.scale, d: f.d, snap: !!f.snap, k: f.k || 0, ratio: f.ratio || 0, sw: sw, sh: sh, ox: Math.round((x0 + (w - sw) / 2) * dpr) / dpr, oy: Math.round((y0 + (hh - sh) / 2) * dpr) / dpr };
    }
    
    
    var crBox = $('crBox'), stats = $('statsCol'), cx0 = gutter + 12;
    var crW = crBox.hidden ? 0 : crBox.offsetLeft + crBox.offsetWidth + 10 - cx0, crH = crBox.hidden ? 0 : crBox.offsetTop + crBox.offsetHeight + 10 - 12;
    var stW = stats.hidden ? 0 : cw - (stats.offsetLeft - 10 - cx0), stH = stats.hidden ? 0 : stats.offsetTop + stats.offsetHeight + 10 - 12;
    function under(r) { return (crH > 0 && r.oy < crH && r.ox < crW) || (stH > 0 && r.oy < stH && r.ox + r.sw > cw - stW); }
    var FIT_CAP = 5;   
    var raw = rawFit(cw, ch), r = place(snapScale(raw, FIT_CAP), 0, 0, cw, ch), fitRaw = raw, fit = 'centred';
    if (under(r)) {
      
      
      
      
      
      
      
      
      
      var clearOf = function (bw, bh) { return Math.max((cw - 2 * bw) / im.width, (ch - 2 * bh) / im.height); };
      var centred = Math.min(raw, crH > 0 ? clearOf(crW, crH) : Infinity, stH > 0 ? clearOf(stW, stH) : Infinity);
      var cand = centred > 0.05 ? { raw: centred, x: 0, y: 0, w: cw, h: ch, fit: 'shrunk' } : null, dodge = null;
      var topH = Math.max(crH, stH);
      [[0, topH, cw, ch - topH], [crW, 0, cw - crW - stW, ch], [0, crH, cw - stW, ch - crH], [crW, stH, cw - crW, ch - stH]].forEach(function (g) {
        if (g[2] < 8 || g[3] < 8) { return; }
        var f = rawFit(g[2], g[3]);
        if (!dodge || f > dodge.raw + 1e-6) { dodge = { raw: f, x: g[0], y: g[1], w: g[2], h: g[3], fit: 'dodged' }; }
      });
      if (dodge && (!cand || dodge.raw * 2 / 3 > cand.raw)) { cand = dodge; }
      if (cand) { fitRaw = cand.raw; fit = cand.fit; r = place(snapScale(cand.raw, FIT_CAP), cand.x, cand.y, cand.w, cand.h); } else { fit = 'dodged'; }
    }
    var d0 = r.d, s0 = r.scale * dpr;   
    if (S.zoom !== 1) {
      
      
      
      
      
      
      var zcx = r.ox + r.sw / 2, zcy = r.oy + r.sh / 2, zf = snapScale(Math.min(fitRaw, FIT_CAP / dpr) * S.zoom, 0);
      var r0 = place(zf, zcx - cw / 2, zcy - ch / 2, cw, ch), KEEP = 64;
      if (S.zoom > 1) {
        S.pan.x = Math.max(KEEP - r0.ox - r0.sw, Math.min(cw - KEEP - r0.ox, S.pan.x));
        S.pan.y = Math.max(KEEP - r0.oy - r0.sh, Math.min(ch - KEEP - r0.oy, S.pan.y));
      }
      r = S.pan.x || S.pan.y ? place(zf, zcx - cw / 2 + S.pan.x, zcy - ch / 2 + S.pan.y, cw, ch) : r0; fit = 'zoom';
    }
    canvas.dataset.pan = Math.round(S.pan.x) + ',' + Math.round(S.pan.y);   
    canvas.dataset.fit = fit;
    var pixel = r.pixel, sw = r.sw, sh = r.sh, ox = r.ox, oy = r.oy;
    scale = r.scale; S.fitPixel = r.pixel; S.fitStep = r.step; S.fitK = r.k; S.fitRatio = r.ratio;   
    canvas.dataset.zoom = S.zoom.toFixed(2);
    S.crBoxH = crBox.hidden ? 0 : crBox.offsetHeight + 10;   
    S.statsColH = stats.hidden ? 0 : stats.offsetHeight;      
    canvas.width = Math.round(cw * dpr); canvas.height = Math.round(ch * dpr);
    canvas.style.width = cw + 'px'; canvas.style.height = ch + 'px';
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, cw, ch);
    canvas.dataset.pixel = pixel ? '1' : '0';
    canvas.dataset.fitk = String(S.fitK);   
    canvas.dataset.origin = ox.toFixed(2) + ',' + oy.toFixed(2);
    var how = drawSprite(im, ox, oy, sw, sh);
    canvas.dataset.how = how;   
    S.fitInfo = { d: r.d, d0: d0, s0: s0, scale: scale, sw: sw, sh: sh, fit: fit, snap: r.snap, step: r.step, k: r.k, ratio: r.ratio, how: how };   
    var fittedCount = 0, total = 0, builtIns = 0;
    var arcPass = [];   
    h.slots.forEach(function (s) {
      if (s.hidden || s.system || s.stationModule || s.launchBay || s.decorative) { return; }
      var p = slotToPx(h, im, s);
      var cx = ox + p.x * scale, cy = oy + p.y * scale;
      
      
      var fitId = S.fit[s.id], biId = !fitId && h.builtInWeapons && h.builtInWeapons[s.id] ? h.builtInWeapons[s.id] : null;
      if (biId) { builtIns++; }
      var wId = fitId || biId, w = weaponOf(wId);   
      var color = slotColor(s);
      var r = slotRadius(s) * Math.max(0.8, Math.min(scale, 2));
      if (s.fittable) { total++; }
      if (s.fittable && s.arc > 0) {
        var a0 = (-(90 + s.angle) - s.arc / 2) * Math.PI / 180, a1 = (-(90 + s.angle) + s.arc / 2) * Math.PI / 180;
        
        
        if (S.selectedSlot === s.id || S.hoverSlot === s.id || (S.cursorSlot === s.id && !S.cursorHoverBorn)) {   
          
          
          
          var reach = Math.max(r * 2.4, Math.min((w && w.range > 0 ? w.range : 1e9) * scale, 0.4 * Math.max(sw, sh)));
          arcPass.push({ id: s.id, cx: cx, cy: cy, a0: a0, a1: a1, color: color, sel: S.selectedSlot === s.id, R: reach, arc: s.arc, fitted: !!w });
        } else if (!wId && s.arc < 360) {
          ctx.beginPath(); ctx.moveTo(cx, cy); ctx.arc(cx, cy, r * 2.4, a0, a1); ctx.closePath();
          ctx.fillStyle = color; ctx.globalAlpha = 0.12; ctx.fill(); ctx.globalAlpha = 1;
        }
      }
      var drawn = false;
      if (wId) {
        if (fitId) { fittedCount++; }
        
        
        
        var kind = s.hardpoint ? 'hardpoint' : 'turret';
        var wi = img(base + 'sprite/weapon/' + encodeURIComponent(wId) + '?kind=' + kind);
        var gi = img(base + 'sprite/weapon/' + encodeURIComponent(wId) + '?kind=' + (s.hardpoint ? 'hardpoint_gun' : 'gun'));
        if (wi) {
          ctx.save();
          ctx.translate(cx, cy);
          ctx.rotate(-s.angle * Math.PI / 180);
          [wi, gi].forEach(function (layer) {
            if (!layer) { return; }
            var ww = layer.width * scale, wh = layer.height * scale;
            drawSprite(layer, -ww / 2, s.hardpoint ? -wh * 0.75 : -wh / 2, ww, wh);   
          });
          ctx.restore();
          drawn = true;
        }
      }
      if (!drawn) {
        if (wId) {
          ctx.beginPath(); ctx.arc(cx, cy, r, 0, Math.PI * 2); ctx.lineWidth = 2; ctx.strokeStyle = color; ctx.fillStyle = color; ctx.fill(); ctx.stroke();
        } else {
          drawEmptyMount(cx, cy, r, color, s.size, s.hardpoint);
        }
      }
      if (S.selectedSlot === s.id || S.hoverSlot === s.id || S.cursorSlot === s.id) {   
        ctx.beginPath(); ctx.arc(cx, cy, r + 5, 0, Math.PI * 2); ctx.lineWidth = 2;
        ctx.strokeStyle = S.selectedSlot === s.id ? '#cbf5ff' : 'rgba(203,245,255,0.6)'; ctx.stroke();
      }
      
      if (fitId && S.hoverGroup && (S.groupOf[s.id] || 1) === S.hoverGroup) {
        ctx.beginPath(); ctx.arc(cx, cy, r + 5, 0, Math.PI * 2); ctx.lineWidth = 2; ctx.strokeStyle = '#ffd200'; ctx.stroke();
        ctx.fillStyle = '#ffd200'; ctx.font = 'bold 11px Bahnschrift, Segoe UI, sans-serif'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
        ctx.fillText(String(S.hoverGroup), cx + r + 9, cy - r - 6);
      }
      if (s.fittable) { markers.push({ id: s.id, x: cx, y: cy, r: r + 5 }); }
      
      else if (biId) { markers.push({ id: s.id, x: cx, y: cy, r: r + 5, builtin: true }); }
    });
    
    
    arcPass.forEach(function (a) {
      var full = a.arc >= 360;
      ctx.beginPath();
      if (full) { ctx.arc(a.cx, a.cy, a.R, 0, Math.PI * 2); } else { ctx.moveTo(a.cx, a.cy); ctx.arc(a.cx, a.cy, a.R, a.a0, a.a1); ctx.closePath(); }
      ctx.fillStyle = a.color; ctx.globalAlpha = a.sel ? 0.24 : 0.16; ctx.fill();
      ctx.lineWidth = 1.5; ctx.strokeStyle = a.color; ctx.globalAlpha = a.sel ? 0.9 : 0.7; ctx.setLineDash(a.fitted ? [] : [4, 4]); ctx.stroke();
      ctx.setLineDash([]); ctx.globalAlpha = 1;
    });
    S.mountCounts = { fitted: fittedCount, total: total };
    
    
    canvas.dataset.scale = scale.toFixed(4);
    canvas.dataset.builtins = String(builtIns);   
    canvas.dataset.cursor = S.cursorSlot || '';   
    canvas.dataset.cone = arcPass.map(function (a) { return a.id; }).join(';');   
    canvas.dataset.markers = markers.map(function (m) { return m.id + ':' + m.x.toFixed(1) + ',' + m.y.toFixed(1); }).join(';');
    renderStageHint();
    renderInfo();
  }
  
  
  function renderInfo() {
    var card = $('renderCard'), p = $('renderInfo');
    if (!card || card.hidden || !p) { return; }
    var h = S.hull, im = S.sprite, f = S.fitInfo;
    if (!h || !im || !f) { p.textContent = h ? 'loading the sprite…' : 'pick a hull'; return; }
    var dpr = window.devicePixelRatio || 1, s = f.scale * dpr, d = f.d, st = f.step, law = RENDER_LAWS[S.render] || S.render, x2 = function (v) { return '×' + v.toFixed(2); }, how;
    if (f.how === 'nn') {
      if (f.snap) { how = 'within 10 % of ' + x2(st) + ': pure nearest-neighbour ' + x2(s) + ' (every law)'; }
      else if (S.render === 'whole') { how = law + ': nearest-neighbour ' + x2(s) + ' — the fit ' + x2(d) + ' floored to a whole step'; }
      else if (S.render === 'half') { how = law + ': nearest-neighbour ' + x2(s) + ' — the fit ' + x2(d) + ' snapped down to a half step'; }
      else if (S.render === 'sharp') { how = law + ': nearest-neighbour at the exact fit — source pixels ' + Math.floor(s) + ' or ' + Math.ceil(s) + ' device px wide'; }
      else { how = law + ': past ×' + PRE_MAX + ' — nearest-neighbour straight at ' + x2(s); }
    } else if (f.how === 'block') { how = law + ': Lanczos ×' + f.ratio.toFixed(3) + ' (' + Math.round(im.width * f.ratio) + '×' + Math.round(im.height * f.ratio) + ' px), then ' + (f.k > 1 ? f.k + '×' + f.k + ' pixel blocks' : 'drawn 1:1 — no whole step nearer than ×1'); }
    else if (f.how === 'lanczos') { how = law + ': Lanczos-3 ' + x2(s) + ' straight from the sprite' + (st === 0 ? ' (a downscale)' : ''); }
    else if (f.how === 'box') { how = law + ': the area average at ' + x2(s) + (st === 0 ? ' (a downscale)' : ''); }
    else if (f.how === 'live') { how = law + ': zooming — resampled once the wheel settles'; }
    else { how = st === 0 ? law + ': bilinear downscale ' + x2(s) : st >= 2 ? law + ': nearest-neighbour ×' + st + ', then smoothed down to ' + x2(s) : law + ': bilinear ' + x2(s); }
    p.textContent = h.name + ' · sprite ' + im.width + '×' + im.height + ' · fit ' + x2(d) + ' · drawn ' + x2(s) + ' (' + Math.round(f.sw) + '×' + Math.round(f.sh) + ' px, ' + f.fit
      + (S.zoom !== 1 ? ', zoom ' + x2(S.zoom) : '') + (dpr !== 1 ? ', device pixel ratio ' + dpr : '') + ')\n' + how;
  }
  
  
  
  function renderStageHint() {
    var h = S.hull, box = clear($('stageHint'));
    if (!h) { return; }
    var c = S.mountCounts, last = S.lastWeapon ? S.weaponById[S.lastWeapon] : null;
    
    
    var nb = bayCount();
    box.appendChild(el('span', { class: 'tally', text: (c.total ? (c.fitted + ' / ' + c.total + ' mounts armed') : 'no weapon mounts')
      + (nb > 0 ? ' · ' + nb + ' fighter bay' + (nb > 1 ? 's' : '') : ''),
      title: (c.total ? 'click a mount to arm it · right-click clears it' : 'this hull mounts no weapons') + (nb > 0 ? ' · the fighter bays sit on the left of the ship' : '') }));
    if (c.total) {
      box.appendChild(el('span', { class: 'last', dataset: { weapon: last ? last.id : '' },
        text: 'shift+click: ' + (last ? last.name : 'the last weapon'),   
        title: last ? ('shift+click any mount ' + last.name + ' fits to put it there (the OP allowing) — spam one weapon across the ship') : 'arm a mount from the weapon list first; shift+click then repeats that weapon on any mount it fits' }));
    }
    if (S.flash) { box.appendChild(el('span', { class: 'flash', text: S.flash })); }
    var undoBtn = el('button', { id: 'undo', class: 'sm', text: 'Undo', title: 'undo the last change (Ctrl+Z)', on: { click: undo } });
    var redoBtn = el('button', { id: 'redo', class: 'sm', text: 'Redo', title: 'redo the change you undid (Ctrl+Y)', on: { click: redo } });
    undoBtn.disabled = !S.undo.length; redoBtn.disabled = !S.redo.length;
    
    box.appendChild(el('span', { class: 'tools' }, [undoBtn, redoBtn,
      el('button', { class: 'sm', text: 'Strip', title: 'take every weapon and fighter off, vents and capacitors to 0, weapon groups cleared — built-in and S-mods stay (the refit\'s Strip: no confirm; Undo brings it back)', on: { click: stripAll } })]));
    
    var tallyEl = box.querySelector('.tally'), lastEl = box.querySelector('.last');
    box.dataset.tallyclip = String(tallyEl ? Math.max(0, tallyEl.scrollWidth - tallyEl.clientWidth) : 0);
    box.dataset.lastclip = String(lastEl ? Math.max(0, lastEl.scrollWidth - lastEl.clientWidth) : 0);
  }
  function flashHint(msg) {
    S.flash = msg; renderStageHint();
    setTimeout(function () { if (S.flash === msg) { S.flash = null; renderStageHint(); } }, 2000);
  }
  
  
  
  
  
  
  function stateSnapshot() {
    return JSON.stringify({ fit: S.fit, groupOf: S.groupOf, biGroupOf: S.biGroupOf, groups: S.groups, mods: S.mods, smods: S.smods, vents: S.vents, caps: S.caps, wings: S.wings, name: S.name });
  }
  function restoreState(snap) {
    var o = JSON.parse(snap);
    S.fit = o.fit || {}; S.groupOf = o.groupOf || {}; S.biGroupOf = o.biGroupOf || {}; S.groups = o.groups; S.mods = o.mods || []; S.smods = o.smods || [];
    S.vents = o.vents || 0; S.caps = o.caps || 0; S.wings = o.wings || []; S.name = o.name || ''; $('name').value = S.name;
  }
  function undoFloor() { S.lastDoc = S.hull ? stateSnapshot() : null; }
  function undoTrack() {
    if (!S.hull) { return; }
    var now = stateSnapshot();
    if (S.lastDoc != null && now !== S.lastDoc) {
      S.undo.push(S.lastDoc); if (S.undo.length > 60) { S.undo.shift(); }
      S.redo = [];
    }
    S.lastDoc = now;
    renderStageHint();   
  }
  function undo(hold) { undoStep(S.undo, S.redo, 'undone', hold); }
  function redo(hold) { undoStep(S.redo, S.undo, 'redone', hold); }
  function undoStep(from, to, word, hold) {   
    if (!S.hull || !from.length) { sfx('ui_button_disabled_pressed'); return; }
    var snap = from.pop();
    to.push(stateSnapshot());
    restoreState(snap);
    S.lastDoc = stateSnapshot();   
    hideTip();
    renderAll(); changed(hold === true);
    sfx('ui_button_pressed');
    flashHint(word);
  }
  function slotFits(s, wid) { return !!s && !!s.fittable && (s.fits || []).indexOf(wid) >= 0; }
  
  
  
  
  
  function shiftApply(slotId) {
    var s = slotById(slotId), w = S.lastWeapon ? S.weaponById[S.lastWeapon] : null;
    if (!s || !s.fittable) { return; }
    if (!w) { sfxCleared(s); flashHint('nothing to repeat yet — arm a mount from the weapon list first'); return; }
    if (!slotFits(s, w.id)) { sfxCleared(s); flashHint(w.name + ' does not fit ' + s.id + ' (' + sizeShort(s.size) + ' ' + typeLabel(s.type) + ')'); return; }
    if (S.fit[slotId] === w.id) { flashHint(s.id + ' already carries ' + w.name); return; }
    var cur = S.fit[slotId] ? S.weaponById[S.fit[slotId]] : null, need = effOp(w) - (cur ? effOp(cur) : 0), free = opFree();   
    if (need > free) { sfxCleared(s); flashHint('not enough OP for ' + w.name + ': needs ' + need + ', ' + free + ' free'); return; }
    S.fit[slotId] = w.id; bumpOp(need);
    if (!S.groupOf[slotId]) { S.groupOf[slotId] = 1; }
    sfxFill(w, s);
    drawStage(); renderArm(); changed();
  }
  
  
  
  function clearMount(slotId) {
    var s = slotById(slotId), w = S.fit[slotId] ? S.weaponById[S.fit[slotId]] : null;
    if (!s) { return; }
    sfxCleared(s);
    if (!w) { return; }
    bumpOp(-effOp(w)); delete S.fit[slotId]; delete S.groupOf[slotId];
    drawStage(); renderArm(); changed();
  }
  
  
  
  
  function stripAll() {
    var h = S.hull;
    if (!h) { return; }
    var freed = 0, touched = false;
    Object.keys(S.fit).forEach(function (id) { var w = S.fit[id] ? S.weaponById[S.fit[id]] : null; if (w) { freed += effOp(w); touched = true; } });
    S.wings.forEach(function (id) { if (id) { touched = true; var wg = S.wingById[id]; if (wg && typeof wg.op === 'number') { freed += wg.op; } } });
    var keep = S.mods.filter(function (id) { var g = modGate(id); return S.smods.indexOf(id) >= 0 || !!(g && g.now === false); });
    S.mods.forEach(function (id) { if (keep.indexOf(id) < 0) { freed += modOpCost(S.modById[id]); touched = true; } });
    if (S.vents || S.caps) { freed += S.vents + S.caps; touched = true; }
    if (!touched) { sfx('ui_button_disabled_pressed'); return; }
    S.fit = {}; S.groupOf = {}; S.biGroupOf = {}; S.wings = []; S.mods = keep; S.smods = S.smods.filter(function (id) { return keep.indexOf(id) >= 0; });   
    S.groups = []; for (var g = 0; g < 7; g++) { S.groups.push({ mode: 'LINKED', autofire: false }); }
    S.vents = 0; S.caps = 0; S.flash = null;
    bumpOp(-freed);
    sfx('ui_button_pressed');
    drawStage(); renderArm(); updateModList(); renderWings(); changed();
  }
  
  
  
  
  
  
  function bayCount() {
    var h = S.hull, st = S.stats && !S.stats.error ? S.stats : null;
    if (!h) { return 0; }
    return st && S.statsAt && S.statsAt.hull === h.id && typeof st.bays === 'number' ? st.bays : (h.bays || 0);
  }
  
  
  
  function bayOrigin(k) {
    var h = S.hull, own = h ? (h.bays || 0) : 0, st = buildStats(), src = st && st.baySources ? st.baySources : [];
    if (k < own) { return 'hull bay'; }
    for (var i = 0, j = k - own; i < src.length; i++) { if (j < (src[i].n || 0)) { return src[i].name || src[i].id || 'hullmod bay'; } j -= src[i].n || 0; }
    return 'hullmod bay';
  }
  function renderBays() {
    var box = clear($('bays')), h = S.hull, bays = bayCount();
    if (!h || !(bays > 0)) { box.hidden = true; return; }
    box.hidden = false;
    var builtIn = h.builtInWings || [], free = Math.max(0, bays - builtIn.length), ids = bayIds(h);
    function wingImg(w) { return pixelFit(el('img', { src: base + 'sprite/wing/' + encodeURIComponent(w.id), alt: '', loading: 'lazy' }), 44); }
    box.appendChild(el('div', { class: 'h', text: 'Bays' }));
    builtIn.forEach(function (id, i) {
      var w = S.wingById[id];
      box.appendChild(el('div', { class: 'bay builtin', title: (w ? w.name + ' · ' + w.numFighters + ' craft' : id) + ' — built into this hull' }, [
        el('span', { class: 'n', text: String(i + 1) }), w ? wingImg(w) : el('span', { class: 'mark' })]));
    });
    for (var i = 0; i < free; i++) {
      (function (bi) {
        var w = S.wings[bi] ? S.wingById[S.wings[bi]] : null, slotId = ids[builtIn.length + bi] || ('bay ' + (bi + 1));
        
        
        var chip = el('div', { class: 'bay' + (w ? '' : ' bare') + (S.tab === 'wings' && S.selectedBay === bi ? ' sel' : '') + (S.tab === 'wings' && S.selectedBay == null && S.bayCursor === bi ? ' cur' : ''),   
          dataset: { bay: String(bi) },   
          on: { click: function (ev) { if (S.dragClick) { S.dragClick = false; return; } if (ev.shiftKey) { shiftApplyWing(bi); return; } setTab('wings'); selectBay(bi); },
                contextmenu: function (ev) { ev.preventDefault(); hideTip(); clearBay(bi); },
                mousedown: function (ev) { if (ev.button === 0 && ev.shiftKey) { ev.preventDefault(); hideTip(); wingPaintStart('chips'); wingPaintOver(bi, true); } },
                mouseenter: function (ev) { if (S.wingPaint && S.wingPaint.via === 'chips') { if (!(ev.buttons & 1) || !ev.shiftKey) { wingPaintEnd(); } else { wingPaintOver(bi); } } } } }, [
          el('span', { class: 'n', text: String(builtIn.length + bi + 1) }), w ? wingImg(w) : el('span', { class: 'mark' })]);
        attachTip(chip, function () { return w ? wingTip(w, slotId + ' · click: pick a wing · right-click: clear') : [el('div', { class: 'h', text: 'Empty bay' }), el('div', { class: 'p hint', text: slotId + ' · click: pick a wing' + (S.lastWing && S.wingById[S.lastWing] ? ' · shift+click: ' + S.wingById[S.lastWing].name : '') })]; });
        box.appendChild(chip);
      })(i);
    }
  }
  function markerAt(ev) {
    var rect = canvas.getBoundingClientRect();
    var x = (ev.clientX - rect.left), y = (ev.clientY - rect.top);
    var best = null, bestD = 1e9;
    markers.forEach(function (m) { var d = Math.hypot(m.x - x, m.y - y); if (d <= m.r + 4 && d < bestD) { best = m; bestD = d; } });
    return best;
  }
  function stageClick(ev) {
    if (S.dragClick) { S.dragClick = false; return; }   
    var m = markerAt(ev);
    
    
    if (!m) {   
      if (S.tab === 'wings' && S.selectedBay != null) { selectBay(null); document.body.dataset.deselect = 'stage'; }
      else { clearCursorRing(); if (S.selectedSlot) { selectSlot(null); } document.body.dataset.deselect = 'stage'; }   
      return;
    }
    
    if (m.builtin) { var bw = weaponOf(biw()[m.id]); sfx('ui_button_disabled_pressed'); flashHint((bw ? bw.name : biw()[m.id]) + ' is built into the hull'); return; }
    if (ev.shiftKey) { shiftApply(m.id); return; }   
    selectSlot(m.id); setTab('arm');
  }
  
  
  
  function clearCursorRing() { if (S.tab === 'groups') { markGroupsCursor(null); } else { markLoadoutCursor(null); } }
  
  
  
  
  
  
  
  
  function paintStart(via) { S.paint = { via: via, done: {}, n: 0 }; }
  function paintOver(id, first) {
    var p = S.paint; if (!p || p.done[id]) { return; }
    p.done[id] = true;
    var s = slotById(id), w = S.lastWeapon ? S.weaponById[S.lastWeapon] : null, before = S.fit[id];
    if (first) { shiftApply(id); }
    else {
      if (!s || !s.fittable || !w || !slotFits(s, w.id) || S.fit[id] === w.id) { return; }
      var cur = S.fit[id] ? S.weaponById[S.fit[id]] : null;
      if (effOp(w) - (cur ? effOp(cur) : 0) > opFree()) { return; }   
      shiftApply(id);
    }
    if (S.fit[id] !== before) { p.n++; if (p.via === 'rows') { renderGroups(); } }
  }
  function paintEnd() {
    var p = S.paint; if (!p) { return; }
    S.paint = null; document.body.dataset.painted = String(p.n);   
    S.dragClick = true; setTimeout(function () { S.dragClick = false; }, 0);   
  }
  
  
  
  
  
  function shiftApplyWing(bi, silent) {
    var h = S.hull, free = h ? Math.max(0, bayCount() - (h.builtInWings || []).length) : 0, w = S.lastWing ? S.wingById[S.lastWing] : null;
    if (!(bi >= 0 && bi < free)) { return false; }
    var refuse = function (m) { if (!silent) { sfx('ui_button_disabled_pressed'); flashHint(m); } return false; };
    if (!w) { return refuse('nothing to repeat yet — put a wing in a bay from the Fighters tab first'); }
    if (!w.allowed) { return refuse(w.name + ' is not allowed at this tier'); }
    if (S.wings[bi] === w.id) { if (!silent) { flashHint('bay ' + (bi + 1) + ' already carries ' + w.name); } return false; }
    var cur = S.wings[bi] ? S.wingById[S.wings[bi]] : null, need = (w.op || 0) - (cur ? cur.op || 0 : 0), fr = opFree();
    if (need > fr) { return refuse('not enough OP for ' + w.name + ': needs ' + need + ', ' + fr + ' free'); }
    armWing(bi, w); drawStage();
    return true;
  }
  function wingPaintStart(via) { S.wingPaint = { via: via, done: {}, n: 0 }; }
  function wingPaintOver(bi, first) {
    var p = S.wingPaint; if (!p || p.done[bi]) { return; }
    p.done[bi] = true;
    if (shiftApplyWing(bi, !first)) { p.n++; }
  }
  function wingPaintEnd() {
    var p = S.wingPaint; if (!p) { return; }
    S.wingPaint = null; document.body.dataset.wpainted = String(p.n);
    S.dragClick = true; setTimeout(function () { S.dragClick = false; }, 0);   
  }
  function stageMove(ev) {
    if (S.drag) { return; }
    if (S.paint && S.paint.via === 'ship') { if (!(ev.buttons & 1) || !ev.shiftKey) { paintEnd(); } else { var pm = markerAt(ev); if (pm && !pm.builtin) { paintOver(pm.id); } return; } }
    stageHover(markerAt(ev));   
  }
  
  
  
  
  function stageDown(ev) {
    if (ev.button === 0 && ev.shiftKey && S.hull) {   
      var ppm = markerAt(ev);
      if (ppm && !ppm.builtin) { ev.preventDefault(); $('stageTip').hidden = true; paintStart('ship'); paintOver(ppm.id, true); return; }
    }
    if (ev.button !== 0 || !(S.zoom > 1) || !S.sprite || markerAt(ev)) { return; }
    S.drag = { x: ev.clientX, y: ev.clientY, px: S.pan.x, py: S.pan.y, moved: false };
    canvas.style.cursor = 'grabbing'; $('stageTip').hidden = true;
    ev.preventDefault();
  }
  function dragMove(ev) {
    var d = S.drag; if (!d) { return; }
    if (!(ev.buttons & 1)) { dragEnd(); return; }   
    var dx = ev.clientX - d.x, dy = ev.clientY - d.y;
    if (!d.moved && Math.abs(dx) + Math.abs(dy) < 3) { return; }
    d.moved = true; S.pan = { x: d.px + dx, y: d.py + dy }; drawStage();
  }
  function dragEnd() {
    var d = S.drag; if (!d) { return; }
    S.drag = null; canvas.style.cursor = S.zoom > 1 ? 'grab' : 'default';
    if (d.moved) { S.dragClick = true; setTimeout(function () { S.dragClick = false; }, 0); }   
  }
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  function hoverArmLeave(overId, via) {
    var a = S.hoverArm;
    if (!a || a.via !== via || S.selectedSlot !== a.id || overId === a.id) { return; }
    S.hoverArm = null;
    var hoverBorn = Object.prototype.hasOwnProperty.call(a, 'prev');
    selectSlot(hoverBorn ? (a.prev || null) : null, hoverBorn); document.body.dataset.hoverdone = a.id;
    if (hoverBorn && !S.selectedSlot && S.cursorSlot) { S.cursorHoverBorn = true; drawStage(); }   
    
    
    
    
    if (a.from && a.from !== 'arm' && S.tab === 'arm' && !S.selectedSlot) { setTab(a.from); if (a.from === 'groups') { markGroupsCursor(a.id, true); } }
  }
  function stageHover(m) {
    hoverArmLeave(m ? m.id : null, 'ship');   
    var tip = $('stageTip');
    var id = m ? m.id : null, entered = id !== S.hoverSlot;
    if (entered) { S.hoverSlot = id; drawStage(); }
    
    
    var cueEntered = !!m && S.cueMount !== m.id; S.cueMount = m ? m.id : null;
    if (cueEntered && !S.drag) { cueHover(); }   
    if (entered && m && !m.builtin && S.tab === 'arm' && S.selectedSlot !== id) {   
      var hsl = slotById(id);
      if (hsl && hsl.fittable) { var hprev = S.selectedSlot; selectSlot(id, true); S.hoverArm = { id: id, via: 'ship', prev: hprev }; document.body.dataset.hoversel = id; }
    }
    if (!m) { tip.hidden = true; canvas.style.cursor = S.zoom > 1 && S.sprite ? 'grab' : 'default'; return; }   
    canvas.style.cursor = 'pointer';
    
    var s = slotById(m.id), bi = !!m.builtin, w = bi ? weaponOf(biw()[m.id]) : (S.fit[m.id] ? weaponOf(S.fit[m.id]) : null), last = S.lastWeapon ? S.weaponById[S.lastWeapon] : null;
    clear(tip);
    tip.appendChild(el('div', { class: 'h', text: (w ? w.name : bi ? biw()[m.id] : 'Empty') + ' · ' + sizeShort(bi && w ? w.size : s.size) + ' ' + typeLabel(bi && w ? (w.mountType || w.type) : s.type) + (s.hardpoint ? ' hardpoint' : ' turret') + ' · ' + s.id }));
    
    var arcWord = s.arc >= 360 ? '360° turret' : Math.round(s.arc) + '° arc';   
    var lockWords = 'built into the hull: never removed or replaced, no OP';
    tip.appendChild(el('div', { class: 's', text: w
      ? [fmt(w.range) + ' range', arcWord, (w.dps > 0 ? fmt(w.dps) + ' dps' : null), (w.flux > 0 ? fmt(w.flux) + ' flux/s' : null), (w.maxAmmo > 0 ? fmt(effAmmo(w)) + ' ammo' : null), bi ? lockWords : effOp(w) + ' OP', bi ? null : 'right-click: clear'].filter(Boolean).join(' · ')   
      : bi ? lockWords
      : arcWord + ' · ' + (s.fits || []).length + ' weapons fit · click: pick one' + (last && slotFits(s, last.id) ? ' · shift+click: ' + last.name : '') }));
    tip.dataset.slot = m.id; tip.dataset.builtin = bi ? '1' : '0';   
    tip.hidden = false;
    
    
    var rect = canvas.getBoundingClientRect(), wr = $('stageWrap').getBoundingClientRect();
    var x = rect.left - wr.left + m.x + 14, y = rect.top - wr.top + m.y - tip.offsetHeight - 8;
    if (x + tip.offsetWidth > wr.width - 6) { x = Math.max(6, rect.left - wr.left + m.x - 14 - tip.offsetWidth); }
    if (y < 6) { y = rect.top - wr.top + m.y + 18; }
    tip.style.left = x + 'px'; tip.style.top = y + 'px';
  }
  function selectSlot(id, quiet) {   
    if (id) { S.loadoutCursor = id; S.cursorByTab = false; S.cursorHoverBorn = false; }   
    S.hoverArm = null;   
    S.cursorSlot = cursorFor();   
    S.selectedSlot = id; document.body.dataset.slot = id || ''; drawStage(); renderArm();   
    
    
    if (id === null && S.armOrigin && S.returnGroups) { var o = S.armOrigin; S.armOrigin = null; setTab('groups'); focusGroupRow(o); }
    else if (id === null) { S.armOrigin = null; }
    if (!quiet) { navCommit(); }   
  }
  
  function returnToGroupsAfterEquip(slotId) {
    if (!(S.returnGroups && S.armOrigin === slotId)) { return; }
    S.armOrigin = null; S.selectedSlot = null; document.body.dataset.slot = '';
    drawStage(); renderArm(); setTab('groups'); focusGroupRow(slotId);
  }
  function slotById(id) { return S.hull ? S.hull.slots.filter(function (s) { return s.id === id; })[0] : null; }
  
  
  function weaponOf(id) { return (id && (S.weaponById[id] || (S.hull && S.hull.builtInWeaponSpecs && S.hull.builtInWeaponSpecs[id]))) || null; }
  function biw() { return (S.hull && S.hull.builtInWeapons) || {}; }
  function isBuiltInSlot(id) { return !!biw()[id]; }

  
  
  
  
  
  
  var tipTimer = null;
  function tipRow(k, v) { return el('div', { class: 'tr' }, [el('span', { class: 'k', text: k }), el('span', { class: 'v', text: v })]); }
  function fmt2(n) { return (typeof n === 'number' && isFinite(n)) ? (Math.round(n * 100) / 100).toString() : ''; }
  
  
  
  function codexDoor(row, entryId) {
    if (!entryId) { return row; }
    row.dataset.codex = entryId;
    var g = el('span', { class: 'cdx', text: 'i', title: 'Codex entry (F2)' });
    g.addEventListener('click', function (ev) { ev.stopPropagation(); ev.preventDefault(); hideTip(); if (window.ccCodex) { window.ccCodex.open(entryId); } });
    g.addEventListener('mousedown', function (ev) { ev.stopPropagation(); });
    g.addEventListener('contextmenu', function (ev) { ev.stopPropagation(); ev.preventDefault(); });
    row.appendChild(g);
    return row;
  }
  
  
  
  
  function attachTip(node, build, delayMs, place) {
    var wait = typeof delayMs === 'number' ? delayMs : 150;
    node.addEventListener('mouseenter', function () {
      if (tipTimer) { clearTimeout(tipTimer); tipTimer = null; }
      if (wait <= 0) { showTip(node, build(), place); return; }
      tipTimer = setTimeout(function () { tipTimer = null; showTip(node, build(), place); }, wait);
    });
    node.addEventListener('mouseleave', hideTip);
    node.addEventListener('mousedown', hideTip);
  }
  function hideTip() { if (tipTimer) { clearTimeout(tipTimer); tipTimer = null; } var t = $('tip'); if (t) { t.hidden = true; } S.tipAt = null; }
  function showTip(anchor, parts, place) {
    var tip = clear($('tip'));
    if (!parts || !parts.length) { tip.hidden = true; S.tipAt = null; return; }
    parts.forEach(function (p) { if (p) { tip.appendChild(p); } });
    if (anchor && anchor.closest && anchor.closest('[data-codex]')) { tip.appendChild(el('div', { class: 'p hint', text: 'F2: codex entry' })); }   
    tip.hidden = false;
    S.tipAt = { anchor: anchor, place: place };   
    placeTip();
  }
  function placeTip() {
    var at = S.tipAt, tip = $('tip'); if (!at || !tip || tip.hidden) { return; }
    var anchor = at.anchor, place = at.place;
    var r = anchor.getBoundingClientRect(), vw = window.innerWidth, vh = window.innerHeight, w = tip.offsetWidth, hgt = tip.offsetHeight;
    if (place === 'above') {   
      var above = r.top - hgt - 8;
      if (above < 8) { above = Math.min(r.bottom + 8, vh - hgt - 8); }
      tip.style.left = Math.max(8, Math.min(r.left + r.width / 2 - w / 2, vw - w - 8)) + 'px';
      tip.style.top = Math.max(8, above) + 'px';
      tip.dataset.place = 'above';
      return;
    }
    tip.dataset.place = 'side';
    var left = r.left - w - 10;   
    if (left < 8) { left = Math.min(r.right + 10, vw - w - 8); }
    tip.style.left = Math.max(8, left) + 'px';
    tip.style.top = Math.max(8, Math.min(r.top, vh - hgt - 8)) + 'px';
  }
  
  
  function effAmmo(w) {
    var base = +w.maxAmmo || 0; if (!(base > 0)) { return 0; }
    var st = S.stats && !S.stats.error && S.statsAt && S.hull && S.statsAt.hull === S.hull.id ? S.stats : null, m = st && st.ammoMul ? st.ammoMul[w.type] : null;
    return m && typeof m.a === 'number' ? Math.max(0, Math.round(base * m.a + (m.b || 0))) : base;
  }
  function ammoText(w) { var e = effAmmo(w), b = +w.maxAmmo || 0; return b > 0 ? fmt(e) + (e !== b ? ' (base ' + fmt(b) + ')' : '') + (w.ammoRegen > 0 ? ' · regenerates' : '') : ''; }
  
  
  
  
  
  function buildStats() { return S.stats && !S.stats.error && S.statsAt && S.hull && S.statsAt.hull === S.hull.id ? S.stats : null; }
  function effOp(w) {
    var st = buildStats(), e = st && st.weaponOp ? st.weaponOp[w.id] : null;
    return typeof e === 'number' ? e : (+w.op || 0);
  }
  function opWhy(w) {
    var why = (buildStats() || {}).weaponOpWhy, sz = String(w.size || '').toLowerCase(), t = String(w.type || '').toLowerCase();
    if (!why) { return ''; }
    return [sz + '_' + t + '_mod', w.beam ? sz + '_beam_mod' : '', w.pd ? sz + '_pd_mod' : ''].map(function (k) { return k && why[k] ? why[k] : ''; }).filter(Boolean).join(' · ');
  }
  function opText(w) { var e = effOp(w), b = +w.op || 0; return e === b ? String(b) : e + ' (base ' + b + ' · ' + (opWhy(w) || 'this build') + ')'; }
  
  function refreshAmmoCells() {
    Array.prototype.forEach.call($('weapons').querySelectorAll('.wrow[data-weapon] .col.ammo'), function (c) {
      var w = weaponOf(c.parentNode.dataset.weapon); if (!w || !(w.maxAmmo > 0)) { return; }
      var e = effAmmo(w); c.textContent = fmt(e); c.dataset.ammo = String(e);
    });
    Array.prototype.forEach.call($('weapons').querySelectorAll('.wrow[data-weapon] .col.op'), function (c) {   
      var w = weaponOf(c.parentNode.dataset.weapon); if (!w) { return; }
      var e = effOp(w); c.textContent = String(e); c.dataset.op = String(e);
    });
  }
  
  
  
  function codexTipBox(entryId) {
    var box = el('div', { class: 'cx' });
    if (!window.ccCodex || !window.ccCodex.entry) { return box; }
    
    
    window.ccCodex.entry(entryId, true).then(function (doc) {
      if (!box.isConnected || !doc || doc.status) { return; }   
      box.appendChild(window.ccCodex.detailEl(doc)); box.dataset.entry = entryId; placeTip();
    }).catch(function () {   });
    return box;
  }
  
  
  
  
  function burstText(burst, sustained) { return burst > 0 && Math.abs(burst - sustained) > 0.05 ? fmt(burst) + ' (' + fmt(sustained) + ')' : fmt(sustained); }
  function weaponTip(w, why) {
    var dl = damageLabel(w), codex = !!(S.weaponById[w.id] && window.ccCodex && window.ccCodex.entry);
    var parts = [el('div', { class: 'h', text: w.name }),
      el('div', { class: 's', text: sizeShort(w.size) + ' ' + typeLabel(w.mountType || w.type) + (w.beam ? ' beam' : '') + (w.pd ? ' · point defence' : '') + (w.role ? ' · ' + w.role : '') })];
    if (!codex) {
      if (dl) { parts.push(tipRow('Damage type', dl === 'HE' ? 'High explosive' : dl === 'Frag' ? 'Fragmentation' : dl)); }
      parts.push(tipRow('Range', fmt(w.range)));
      if (w.dps > 0) { parts.push(tipRow(w.dpsBurst > 0 && Math.abs(w.dpsBurst - w.dps) > 0.05 ? 'Damage / sec (sustained)' : 'Damage / sec', burstText(w.dpsBurst, w.dps))); }
      if (w.flux > 0) { parts.push(tipRow(w.fluxBurst > 0 && Math.abs(w.fluxBurst - w.flux) > 0.05 ? 'Flux / sec (sustained)' : 'Flux / sec', burstText(w.fluxBurst, w.flux))); }
      if (w.fluxPerDamage > 0) { parts.push(tipRow('Flux / damage', fmt2(w.fluxPerDamage))); }
    }
    if (w.maxAmmo > 0) { parts.push(tipRow('Ammo', ammoText(w))); }   
    parts.push(tipRow('Ordnance points', opText(w)));   
    if (why) { parts.push(el('div', { class: 'p why', text: why })); }
    if (codex) { parts.push(codexTipBox('codex_weapon_' + w.id)); }   
    return parts;
  }
  
  function wingTip(w, hint) {
    var parts = [el('div', { class: 'h', text: w.name }),
      el('div', { class: 's', text: roleLabel(w.role) + ' wing · ' + w.numFighters + ' craft · ' + w.op + ' OP' })];
    if (!w.allowed) { parts.push(el('div', { class: 'p why', text: 'not allowed: ' + (w.reason || 'tier') })); }
    parts.push(codexTipBox('codex_fighter_' + w.id));
    if (hint) { parts.push(el('div', { class: 'p hint', text: hint })); }
    return parts;
  }
  function modTip(m) {
    var h = S.hull, g = modGate(m.id), d = h && h.modDesc ? h.modDesc[m.id] : null;
    var on = S.mods.indexOf(m.id) >= 0, isS = S.smods.indexOf(m.id) >= 0, bi = !!h && (h.builtInMods || []).indexOf(m.id) >= 0;
    var refused = !!(g && g.ok === false), locked = !!(g && g.now === false), cost = modOpCost(m);
    var costText = !h ? '' : bi ? 'built into this hull' : (on && isS) ? 'built in (S-mod): 0 OP' : cost + ' OP on a ' + sizeName(h.size).toLowerCase();
    var parts = [el('div', { class: 'h', text: m.name }),
      el('div', { class: 's', text: [m.manufacturer || '', costText].filter(Boolean).join(' · ') })];
    if (d && d.d) {   
      d.d.split('\n').forEach(function (p) { if (p.trim()) { parts.push(el('div', { class: 'p', text: p.trim() })); } });
    }
    
    
    var notes = (on || bi) && (buildStats() || {}).hullModNotes ? buildStats().hullModNotes[m.id] : null;
    if (notes && notes.length) { notes.forEach(function (n) { parts.push(el('div', { class: 'p build', text: n })); }); }
    
    
    if (d && d.s && !m.noBuildIn) { parts.push(el('div', { class: 'p smod' + (d.sNeg ? ' neg' : ''), text: (d.sNeg ? 'S-mod penalty (built in): ' : 'S-mod bonus (built in): ') + d.s })); }
    if (!bi && refused) { parts.push(el('div', { class: 'p why', text: g.reason || 'the game refuses it on this ship' })); }
    else if (!bi && !on && locked) { parts.push(el('div', { class: 'p why', text: g.nowReason || 'cannot be changed now' })); }
    else if (!bi && !on && cost > opFree()) { parts.push(el('div', { class: 'p why', text: 'not enough OP: needs ' + cost + ', ' + opFree() + ' free' })); }
    if (!bi) {   
      parts.push(el('div', { class: 'p hint', text: !on ? ('click: add' + (m.noBuildIn ? '' : ' · shift+click: add built in'))
        : isS ? 'click: remove · right-click: remove · shift+right-click: un-build (keep it as a normal mod)'
        : sModBlock(m) ? ('click: remove (' + sModBlock(m) + ') · right-click: remove') : 'click: build in (S-mod, 0 OP) · right-click: remove · shift+right-click: build in' }));
    }
    return parts;
  }
  
  
  function wireTip() {
    var v = S.verdict, b = v && v.bytes ? v.bytes : null;
    return [el('div', { class: 'h', text: 'Wire size' }),
      el('div', { class: 'p', text: 'How big this build is when it is sent to the server' + (b ? ' (' + b.body + ' bytes)' : '') + ', against the server’s cap' + (b ? ' (' + b.cap + ' bytes)' : '')
        + '. Informational — nothing to do here. A build over the cap is refused at Save with a message saying so.' })];
  }
  
  function openTipFor(id) {
    if (id === 'wire') { showTip($('bytesInfo'), wireTip(), 'above'); return; }
    if (id.indexOf('slot:') === 0) {   
      var want = id.slice(5), tries = 0;
      (function again() {   
        var mk = markers.filter(function (m) { return m.id === want; })[0];
        if (mk) { stageHover(mk); } else if (tries++ < 40) { setTimeout(again, 100); }
      })();
      return;
    }
    if (id.indexOf('seg:') === 0) {   
      
      
      setTimeout(function () {
        var seg = S.opSegs && S.opSegs[id.slice(4)];
        if (seg) { seg.dispatchEvent(new MouseEvent('mouseenter', { bubbles: false, cancelable: true })); }
      }, 400);
      return;
    }
    var m = S.modById[id];
    if (m && S.mrows[id]) { showTip(S.mrows[id], modTip(m)); return; }
    var w = S.weaponById[id], row = w ? $('weapons').querySelector('.wrow[data-weapon="' + id.replace(/"/g, '') + '"]') : null;
    if (row) { showTip(row, weaponTip(w, null)); return; }
    var wg = S.wingById[id], wrow = wg ? $('wingsBody').querySelector('.wrow[data-wing="' + id.replace(/"/g, '') + '"]') : null;   
    if (wrow) { showTip(wrow, wingTip(wg, null)); }
  }

  
  function setTab(name) {
    S.tab = name;
    document.body.dataset.tab = name;   
    Array.prototype.forEach.call($('tabs').querySelectorAll('button'), function (b) { b.classList.toggle('on', b.dataset.tab === name); });
    if (name === 'groups') { var gb = $('tabs').querySelector('button[data-tab="groups"]'); if (gb) { gb.classList.remove('cue', 'pulse'); } }   
    Array.prototype.forEach.call(document.querySelectorAll('.tab'), function (t) { t.classList.toggle('on', t.id === 'tab-' + name); });
    if (S.hull) { renderBays(); }   
    if (S.hoverSlot) { S.hoverSlot = null; drawStage(); }   
    S.hoverArm = null;   
    setCursorSlot(cursorFor());   
    navCommit();   
  }
  
  
  
  
  
  function tabQE(dir) {
    var btns = Array.prototype.filter.call($('tabs').querySelectorAll('button'), function (b) { return !b.hidden; });
    if (!btns.length) { return false; }
    var rail = $('rail'), onRail = rail.contains(document.activeElement), n = btns.length + 1, i = 0;
    if (!onRail) { btns.forEach(function (b, k) { if (b.classList.contains('on')) { i = k + 1; } }); }
    var j = ((i + dir) % n + n) % n;
    hideTip(); closeCtx(); S.armOrigin = null;
    if (j === 0) { rail.focus({ preventScroll: true }); document.body.dataset.qe = 'ships'; return true; }
    if (onRail && document.activeElement && document.activeElement.blur) { document.activeElement.blur(); }
    setTab(btns[j - 1].dataset.tab); document.body.dataset.qe = btns[j - 1].dataset.tab;
    return true;
  }
  
  
  
  
  
  var NAV = { hist: [], hi: -1, lock: false, MAX: 60 };
  function navPlace() { return { hull: S.hull ? S.hull.id : null, tab: S.tab, slot: S.selectedSlot || null, bay: S.selectedBay == null ? null : S.selectedBay }; }
  function navSame(a, b) { return !!a && !!b && a.hull === b.hull && a.tab === b.tab && a.slot === b.slot && a.bay === b.bay; }
  function navPublish() { document.body.dataset.nav = NAV.hi + '/' + NAV.hist.length; }
  function navCommit() {
    if (NAV.lock || !S.hull) { return; }
    var p = navPlace();
    if (navSame(p, NAV.hist[NAV.hi])) { return; }
    NAV.hist = NAV.hist.slice(0, NAV.hi + 1); NAV.hist.push(p);
    if (NAV.hist.length > NAV.MAX) { NAV.hist.shift(); }
    NAV.hi = NAV.hist.length - 1;
    navPublish();
  }
  function navApply(p) {
    NAV.lock = true;
    var step = (p.hull && (!S.hull || S.hull.id !== p.hull)) ? pickHull(p.hull) : Promise.resolve();
    return step.then(function () {
      hideTip(); closeCtx(); S.armOrigin = null;
      setTab(p.tab);
      if (S.selectedBay != null && p.bay == null) { selectBay(null); }
      if (S.selectedSlot && !p.slot) { selectSlot(null); }
      if (p.slot && S.selectedSlot !== p.slot && slotById(p.slot)) { selectSlot(p.slot); }
      if (p.bay != null && S.selectedBay !== p.bay) { selectBay(p.bay); }
    }).catch(function () { }).then(function () { NAV.lock = false; navPublish(); });
  }
  function navBack() {
    if (window.ccCodex && window.ccCodex.isOpen()) { if (window.ccCodex.canBack()) { window.ccCodex.back(); } else { window.ccCodex.close(); } return Promise.resolve(true); }
    if (NAV.lock || NAV.hi <= 0) { return Promise.resolve(false); }
    NAV.hi--; return navApply(NAV.hist[NAV.hi]);
  }
  function navFwd() {
    if (window.ccCodex && window.ccCodex.isOpen()) { window.ccCodex.fwd(); return Promise.resolve(true); }
    if (NAV.lock || NAV.hi >= NAV.hist.length - 1) { return Promise.resolve(false); }
    NAV.hi++; return navApply(NAV.hist[NAV.hi]);
  }
  
  
  
  
  
  
  
  function armWing(bi, w, opts) {
    var cur = S.wings[bi] ? S.wingById[S.wings[bi]] : null, wheel = !!(opts && opts.wheel);
    if (S.wings[bi] !== w.id) {
      if (wheel) { clearTimeout(S.wheelSfx); S.wheelSfx = setTimeout(function () { S.wheelSfx = null; sfx('ui_refit_slot_filled_fighter'); document.body.dataset.wheelsfx = String(S.wheelSfxN = (S.wheelSfxN || 0) + 1); }, 100); }
      else { sfx('ui_refit_slot_filled_fighter'); }
      bumpOp((w.op || 0) - (cur ? cur.op || 0 : 0));
    }
    S.wings[bi] = w.id; S.lastWing = w.id; renderWings(); changed(wheel);   
    if (wheel) { var row = $('wingsBody').querySelector('.wrow.sel'); if (row && row.scrollIntoView) { row.scrollIntoView({ block: 'center' }); } }
  }
  function wingStep(dir, bayOpt) {   
    var bi = bayOpt == null ? S.selectedBay : bayOpt, fits = bayOpt == null ? (S.wingFits || []) : wingOrder('');
    if (bi == null || !S.hull || !fits.length) { return false; }
    var cur = S.wings[bi] ? S.wingById[S.wings[bi]] : null, free = opFree();
    var armable = fits.filter(function (w) { return w.allowed && (S.wings[bi] === w.id || (w.op || 0) - (cur ? cur.op || 0 : 0) <= free); });
    if (armable.length) {
      var i = -1; armable.forEach(function (w, k) { if (w.id === S.wings[bi]) { i = k; } });
      var j = i < 0 ? (dir > 0 ? 0 : armable.length - 1) : i + dir;
      if (j >= 0 && j < armable.length) { armWing(bi, armable[j], { wheel: true }); }
    }
    document.body.dataset.wheelwing = S.wings[bi] || '';
    return true;
  }
  function bayCursorStep(dir) {
    var rows = Array.prototype.slice.call($('wingsBody').querySelectorAll('.wrow[data-bay]'));
    if (!rows.length) { return false; }
    var i = -1; rows.forEach(function (r, k) { if (parseInt(r.dataset.bay, 10) === S.bayCursor) { i = k; } });
    var j = i < 0 ? (dir > 0 ? 0 : rows.length - 1) : Math.max(0, Math.min(rows.length - 1, i + dir));
    if (j === i) { return false; }   
    markBayCursor(parseInt(rows[j].dataset.bay, 10), true);
    return true;
  }
  function markBayCursor(bi, scroll) {
    S.bayCursor = bi == null ? null : bi;
    var list = $('wingsBody'), cur = null;
    Array.prototype.forEach.call(list.querySelectorAll('.wrow[data-bay]'), function (r) { var on = parseInt(r.dataset.bay, 10) === S.bayCursor; r.classList.toggle('cur', on); if (on) { cur = r; } });
    if (cur && scroll && cur.scrollIntoView) { cur.scrollIntoView({ block: 'nearest' }); }
    list.dataset.bcursor = cur ? String(S.bayCursor) : '';
    renderBays();
  }
  
  
  
  
  function bayWheel(bi, dir) {
    var h = S.hull, free = h ? Math.max(0, bayCount() - (h.builtInWings || []).length) : 0;
    if (!(bi >= 0 && bi < free)) { return false; }
    markBayCursor(bi, false);   
    wingStep(dir, bi);
    document.body.dataset.baywheel = bi + ':' + (S.wings[bi] || '');
    return true;
  }
  
  
  
  
  
  function loadoutCursorStep(dir, wrap, byTab) {   
    var rows = Array.prototype.slice.call($('weapons').querySelectorAll('.wrow[data-slot]:not(.locked)'));
    if (!rows.length) { return false; }
    var i = -1; rows.forEach(function (r, k) { if (r.dataset.slot === S.loadoutCursor) { i = k; } });
    var j = i < 0 ? (dir > 0 ? 0 : rows.length - 1) : wrap ? ((i + dir) % rows.length + rows.length) % rows.length : Math.max(0, Math.min(rows.length - 1, i + dir));
    if (j === i) { return false; }
    markLoadoutCursor(rows[j].dataset.slot, true);
    S.cursorByTab = !!byTab;
    return true;
  }
  function markLoadoutCursor(id, scroll) {
    S.loadoutCursor = id || null; S.cursorHoverBorn = false;   
    var list = $('weapons'), cur = null;
    Array.prototype.forEach.call(list.querySelectorAll('.wrow[data-slot]'), function (r) { var on = r.dataset.slot === S.loadoutCursor; r.classList.toggle('cur', on); if (on) { cur = r; } });
    if (cur && scroll && cur.scrollIntoView) { cur.scrollIntoView({ block: 'nearest' }); }
    setCursorSlot(cur ? S.loadoutCursor : null);   
    list.dataset.lcursor = cur ? S.loadoutCursor : '';
  }
  
  

  
  
  
  function weaponIcon(w, box) {
    var gun = el('img', { class: 'gun', src: base + 'sprite/weapon/' + encodeURIComponent(w.id) + '?kind=gun', alt: '', loading: 'lazy' });
    gun.addEventListener('error', function () { gun.hidden = true; });
    var b = box || 40;   
    var ref = ({ SMALL: 24, MEDIUM: 40, LARGE: 64 })[String(w.size || '').toUpperCase()] || 0;   
    var turret = el('img', { src: base + 'sprite/weapon/' + encodeURIComponent(w.id) + '?kind=turret', alt: '', loading: 'lazy' });
    if (b < 40) { [turret, gun].forEach(function (im) { im.addEventListener('load', pubGroupIcons); }); }   
    return el('div', { class: 'ico' + (b < 40 ? ' sm' : '') }, [pixelFit(turret, b, 1, ref), pixelFit(gun, b, 1, ref)]);
  }
  
  
  
  
  
  function pubGroupIcons() {
    var box = $('groups'), over = 0, loaded = 0;
    if (!box) { return; }
    Array.prototype.forEach.call(box.querySelectorAll('.ico.sm'), function (d) {
      var g = d.querySelector('img.gun');
      if (g && !g.hidden && g.naturalWidth) { loaded++; }
      if (d.scrollHeight > d.clientHeight || d.scrollWidth > d.clientWidth) { over++; }
    });
    box.dataset.gicoOver = String(over); box.dataset.gicoLoaded = String(loaded);
  }
  
  
  
  
  
  function pixelFit(im, box, min, ref) {
    function fit() {
      var nw = im.naturalWidth, nh = im.naturalHeight, dpr = window.devicePixelRatio || 1;
      if (!nw || !nh) { return; }
      
      
      
      var k = Math.floor(box * dpr / Math.max(nw, nh, ref || 0));
      if (k >= (min || 1)) { im.style.width = (nw * k / dpr) + 'px'; im.style.height = (nh * k / dpr) + 'px'; im.classList.add('px'); }
    }
    if (im.complete && im.naturalWidth) { fit(); } else { im.addEventListener('load', fit); }
    return im;
  }
  
  function damageLabel(w) { return ({ HIGH_EXPLOSIVE: 'HE', KINETIC: 'Kinetic', ENERGY: 'Energy', FRAGMENTATION: 'Frag', OTHER: '' })[w.damageType] || ''; }
  function weaponRow(w, s, fitted, onClick) {
    var fav = isFav(w.id);   
    var row = el('div', { class: 'wrow' + (fitted ? ' sel' : '') + (w.allowed ? '' : ' off') + (fav ? ' fav' : ''), dataset: { weapon: w.id } }, [
      weaponIcon(w),
      el('div', { class: 'who' }, [
        el('div', { class: 'name' }, [fav ? el('span', { class: 'fav', text: '★', title: 'a favourite — right-click: unfavourite' }) : null, w.name]),
        el('div', { class: 'meta' }, [el('span', { class: 'dot ' + (w.mountType || w.type) }), el('span', { class: 't', text: sizeShort(w.size) + ' ' + typeLabel(w.mountType || w.type) + (w.beam ? ' beam' : '') })])
      ]),
      el('div', { class: 'col dmg ' + (w.damageType || ''), text: damageLabel(w) }),
      el('div', { class: 'col rng', text: String(w.range) }),
      el('div', { class: 'col op', text: String(effOp(w)), dataset: { op: String(effOp(w)) } }),   
      el('div', w.maxAmmo > 0 ? { class: 'col ammo', text: fmt(effAmmo(w)), dataset: { ammo: String(effAmmo(w)) } } : { class: 'col ammo' })   
    ]);
    if (w.allowed && onClick) { row.addEventListener('click', onClick); }
    if (!w.allowed) { row.dataset.why = 'not allowed: ' + (w.reason || 'tier'); }
    
    
    
    row.addEventListener('contextmenu', function (ev) {
      if (ev.shiftKey) {
        ev.preventDefault(); hideTip(); closeCtx();
        if (s && S.fit[s.id] === w.id) { clearMount(s.id); document.body.dataset.ctxshift = 'removed'; }
        else { sfx('ui_button_disabled_pressed'); flashHint(!s ? 'select a mount first' : S.fit[s.id] ? (s.id + ' carries ' + (S.weaponById[S.fit[s.id]] || { name: S.fit[s.id] }).name + ', not ' + w.name) : (s.id + ' is empty')); document.body.dataset.ctxshift = 'nomatch'; }
        return;
      }
      openCtx(ev, w, s);
    });
    codexDoor(row, 'codex_weapon_' + w.id);
    attachTip(row, function () { return weaponTip(w, row.dataset.why || null); });   
    return row;
  }
  function colHead(labels) {
    var head = el('div', { class: 'colHead' });
    labels.forEach(function (l) { head.appendChild(el('span', { text: l })); });
    return head;
  }
  
  
  
  function armPick(s, w, opts) {
    var fittedW = S.fit[s.id] ? S.weaponById[S.fit[s.id]] : null, need = effOp(w) - (fittedW ? effOp(fittedW) : 0), wheel = !!(opts && opts.wheel);   
    if (S.fit[s.id] !== w.id && need > opFree()) { sfxCleared(s); return false; }   
    S.lastWeapon = w.id;   
    if (S.fit[s.id] === w.id) { renderStageHint(); if (!wheel) { returnToGroupsAfterEquip(s.id); } return true; }
    S.fit[s.id] = w.id; bumpOp(need); if (!S.groupOf[s.id]) { S.groupOf[s.id] = 1; }
    
    
    
    
    
    if (wheel) { clearTimeout(S.wheelSfx); S.wheelSfx = setTimeout(function () { S.wheelSfx = null; sfxFill(w, s); document.body.dataset.wheelsfx = String(S.wheelSfxN = (S.wheelSfxN || 0) + 1); }, 160); }
    else { sfxFill(w, s); }
    drawStage(); renderArm(); changed(wheel);   
    if (wheel) { var row = $('weapons').querySelector('.wrow.sel'); if (row && row.scrollIntoView) { row.scrollIntoView({ block: 'center' }); } }
    else { returnToGroupsAfterEquip(s.id); }   
    return true;
  }
  
  
  
  
  function wheelStep(dir) {
    var s = slotById(S.selectedSlot), fits = S.pickerFits || [];
    if (!s || !fits.length) { return false; }
    var fittedW = S.fit[s.id] ? S.weaponById[S.fit[s.id]] : null, free = opFree();
    var armable = fits.filter(function (w) { return w.allowed && (S.fit[s.id] === w.id || effOp(w) - (fittedW ? effOp(fittedW) : 0) <= free); });   
    if (armable.length) {
      var i = -1; armable.forEach(function (w, k) { if (w.id === S.fit[s.id]) { i = k; } });
      var j = i < 0 ? (dir > 0 ? 0 : armable.length - 1) : i + dir;
      if (j >= 0 && j < armable.length) { armPick(s, armable[j], { wheel: true }); }
    }
    document.body.dataset.wheelArmed = S.fit[s.id] || '';   
    return true;
  }
  
  
  
  
  
  
  
  
  
  
  
  
  var RING_TYPE = { BALLISTIC: 0, ENERGY: 1, MISSILE: 2, HYBRID: 3, SYNERGY: 4, COMPOSITE: 5, UNIVERSAL: 6 };
  function ringOrder() {
    if (!S.hull) { return []; }
    return S.hull.slots.filter(function (s) { return s.fittable; }).sort(function (a, b) {
      return ((SZ_ORDER[b.size] || 0) - (SZ_ORDER[a.size] || 0))
        || ((RING_TYPE[a.type] == null ? 9 : RING_TYPE[a.type]) - (RING_TYPE[b.type] == null ? 9 : RING_TYPE[b.type]))
        || ((b.x || 0) - (a.x || 0)) || ((b.y || 0) - (a.y || 0)) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0);
    }).map(function (s) { return s.id; });
  }
  
  function nextOnRing(ids, cur, dir) {
    var n = ids.length;
    if (!n) { return null; }
    var i = ids.indexOf(cur), start = i < 0 ? (dir > 0 ? 0 : n - 1) : ((i + dir) % n + n) % n;
    for (var k = 0; k < n; k++) { var id = ids[((start + k * dir) % n + n) % n]; if (!S.fit[id]) { return id; } }
    return ids[start];
  }
  
  
  function tabRing() { return matrixRows().filter(function (r) { return !r.builtin; }).map(function (r) { return r.id; }); }
  function tabStep(dir) {
    if (!S.hull) { return false; }
    var next;
    if (S.tab === 'groups') {
      var act = document.activeElement, cur = S.groupsCursor;
      if (act && act.dataset && act.dataset.slot && $('groups').contains(act)) { cur = act.dataset.slot; }
      next = nextOnRing(tabRing(), cur, dir);
      if (!next) { return false; }
      S.groupsCursor = next; S.armOrigin = next; sfx('ui_button_pressed'); setTab('arm'); selectSlot(next);
    } else if (S.tab === 'arm') {
      
      
      
      
      S.armOrigin = null;
      if (S.selectedSlot) { selectSlot(null); }
      if (!loadoutCursorStep(dir, true, true)) { return false; }
      document.body.dataset.tabring = S.loadoutCursor || '';
      return true;
    } else if (S.tab === 'wings' && S.selectedBay != null && S.hull) {
      
      
      var free = Math.max(0, bayCount() - (S.hull.builtInWings || []).length), n = free, k;
      if (n < 1) { return false; }
      var start = ((S.selectedBay + dir) % n + n) % n, pick = start;
      for (k = 0; k < n; k++) { var cand = ((start + k * dir) % n + n) % n; if (!S.wings[cand]) { pick = cand; break; } }
      selectBay(pick);
      document.body.dataset.tabbay = String(pick + 1);
      return true;
    } else { return false; }
    document.body.dataset.tabring = S.selectedSlot || '';
    var row = $('weapons').querySelector('.wrow.sel');   
    if (row && row.scrollIntoView) { row.scrollIntoView({ block: 'center' }); }
    return true;
  }
  
  
  
  
  
  var WASD = { w: [0, -1], s: [0, 1], a: [-1, 0], d: [1, 0] };
  function wasdStep(k) {
    var h = S.hull, dir = WASD[k];
    if (!h || !dir) { return false; }
    var from = slotById(S.selectedSlot), fx = from ? from.x : 0, fy = from ? from.y : 0, best = null, bestD = Infinity;
    h.slots.forEach(function (s) {
      if (!s.fittable || s === from) { return; }
      var vx = -(s.y - fy), vy = -(s.x - fx), d = Math.sqrt(vx * vx + vy * vy);
      if (d < 1e-6 || dir[0] * vx + dir[1] * vy <= d * Math.SQRT1_2) { return; }   
      if (d < bestD) { bestD = d; best = s; }
    });
    if (!best) { return false; }
    S.armOrigin = null; setTab('arm'); selectSlot(best.id);
    document.body.dataset.wasd = best.id;
    var row = $('weapons').querySelector('.wrow.sel');
    if (row && row.scrollIntoView) { row.scrollIntoView({ block: 'center' }); }
    return true;
  }
  
  
  
  
  
  
  function groupsCursorStep(dir) {
    var rows = Array.prototype.filter.call($('groups').querySelectorAll('tr.gw:not(.locked), tr.ge'), function (tr) { return !!tr.dataset.slot; });
    if (!rows.length) { return false; }
    var i = -1; rows.forEach(function (tr, k) { if (tr.dataset.slot === S.groupsCursor) { i = k; } });
    var j = i < 0 ? (dir > 0 ? 0 : rows.length - 1) : Math.max(0, Math.min(rows.length - 1, i + dir));
    if (j === i) { return false; }   
    var act = document.activeElement;
    if (act && act !== document.body && $('groups').contains(act) && act.blur) { act.blur(); }
    markGroupsCursor(rows[j].dataset.slot, true);
    return true;
  }
  function markGroupsCursor(id, scroll) {
    var box = $('groups');
    S.groupsCursor = id || null; S.cursorHoverBorn = false;   
    Array.prototype.forEach.call(box.querySelectorAll('tr.cur'), function (tr) { tr.classList.remove('cur'); });
    var q = id ? id.replace(/"/g, '') : '', tr = id ? box.querySelector('tr.gw[data-slot="' + q + '"]:not(.locked), tr.ge[data-slot="' + q + '"]') : null;
    if (tr) { tr.classList.add('cur'); if (scroll && tr.scrollIntoView) { tr.scrollIntoView({ block: 'nearest' }); } }
    setCursorSlot(tr ? id : null);   
    box.dataset.gcursor = tr ? id : '';
  }
  function spaceAct() {
    if (!S.hull) { return false; }
    if (S.tab === 'groups' && S.groupsCursor && $('groups').querySelector('tr.cur')) {
      var id = S.groupsCursor;
      sfx('ui_button_pressed'); S.armOrigin = id; setTab('arm'); selectSlot(id);
      document.body.dataset.space = 'groups:' + id;
      return true;
    }
    
    
    if (S.tab === 'wings' && S.selectedBay == null && S.bayCursor != null && $('wingsBody').querySelector('.wrow.cur')) {
      var bc = S.bayCursor; sfx('ui_button_pressed'); selectBay(bc); document.body.dataset.space = 'bays:' + bc; return true;
    }
    
    if (S.tab === 'arm' && !S.selectedSlot && S.loadoutCursor && $('weapons').querySelector('.wrow.cur')) {
      var lc = S.loadoutCursor; sfx('ui_button_pressed'); selectSlot(lc); document.body.dataset.space = 'loadout:' + lc; return true;
    }
    if (S.selectedBay != null) { selectBay(null); document.body.dataset.space = 'bay'; return true; }
    if (S.selectedSlot) { selectSlot(null); document.body.dataset.space = 'slot'; return true; }
    return false;
  }
  
  
  
  
  
  
  
  
  function savedFavs() { try { return JSON.parse(localStorage.getItem('cc_editor_favs') || '[]').filter(function (x) { return typeof x === 'string'; }); } catch (e) { return []; } }
  function setFavs(list, view) { S.favs = list; if (!view) { try { localStorage.setItem('cc_editor_favs', JSON.stringify(list)); } catch (e) {   } } document.body.dataset.favs = list.join(','); }   
  function isFav(id) { return S.favs.indexOf(id) >= 0; }
  
  
  function savedHullFavs() { try { return JSON.parse(localStorage.getItem('cc_editor_hullfavs') || '[]').filter(function (x) { return typeof x === 'string'; }); } catch (e) { return []; } }
  function setHullFavs(list, view) { S.hullFavs = list; if (!view) { try { localStorage.setItem('cc_editor_hullfavs', JSON.stringify(list)); } catch (e) {   } } document.body.dataset.hullfavs = list.join(','); }   
  function isHullFav(id) { return S.hullFavs.indexOf(id) >= 0; }
  function toggleHullFav(id) {
    var on = !isHullFav(id);
    setHullFavs(on ? S.hullFavs.concat([id]) : S.hullFavs.filter(function (x) { return x !== id; }));
    sfx('ui_button_pressed');
    renderHullRail();
  }
  function toggleFav(id) { setFavs(isFav(id) ? S.favs.filter(function (x) { return x !== id; }) : S.favs.concat([id])); sfx('ui_button_pressed'); renderArm(); }
  function closeCtx() { var m = $('ctxMenu'); if (!m.hidden) { m.hidden = true; clear(m); } document.body.dataset.ctx = 'closed'; }
  
  function ctxKey(digit) {
    var r = $('ctxMenu').querySelector('.r[data-key="' + digit + '"]');
    if (!r) { return false; }
    if (r.classList.contains('off')) { sfx('ui_button_disabled_pressed'); return true; }
    r.click();
    return true;
  }
  function fillMounts(w, targets) {
    var armed = 0, unpayable = 0, free = opFree();
    targets.forEach(function (id) {
      var cur = S.fit[id] ? S.weaponById[S.fit[id]] : null, need = effOp(w) - (cur ? effOp(cur) : 0);   
      if (S.fit[id] === w.id) { return; }
      if (need > free) { unpayable++; return; }
      free -= need; bumpOp(need); S.fit[id] = w.id; if (!S.groupOf[id]) { S.groupOf[id] = 1; } armed++;
    });
    document.body.dataset.ctxfill = armed + '/' + targets.length + '/' + unpayable;
    if (!armed) { sfx('ui_button_disabled_pressed'); flashHint(!targets.length ? 'no mount to arm' : unpayable ? ('no mount armed — ' + unpayable + ' unpayable') : 'they carry ' + w.name + ' already'); return; }
    S.lastWeapon = w.id; sfxFill(w, slotById(targets[0]));
    drawStage(); renderArm(); changed();
    flashHint('armed ' + armed + ' of ' + targets.length + ' mount' + (targets.length === 1 ? '' : 's') + (unpayable ? ' · ' + unpayable + ' unpayable' : ''));
  }
  function removeEverywhere(w) {
    var ids = Object.keys(S.fit).filter(function (id) { return S.fit[id] === w.id; });
    document.body.dataset.ctxfill = '0/' + ids.length + '/0';
    if (!ids.length) { sfx('ui_button_disabled_pressed'); flashHint(w.name + ' is on no mount'); return; }
    ids.forEach(function (id) { delete S.fit[id]; delete S.groupOf[id]; });
    bumpOp(-effOp(w) * ids.length); sfxCleared(slotById(ids[0]));   
    drawStage(); renderArm(); changed();
    flashHint(w.name + ' off ' + ids.length + ' mount' + (ids.length === 1 ? '' : 's'));
  }
  function openCtx(ev, w, s) {
    ev.preventDefault(); hideTip(); closeCtx();
    var m = $('ctxMenu'), size = s ? s.size : w.size;
    var compat = ringOrder().filter(function (id) { return slotFits(slotById(id), w.id); }), compatEmpty = compat.filter(function (id) { return !S.fit[id]; });
    var sizeEmpty = compatEmpty.filter(function (id) { var x = slotById(id); return x && x.size === size; });
    var carrying = Object.keys(S.fit).filter(function (id) { return S.fit[id] === w.id; }).length;
    
    
    var nth = 0;
    function act(key, label, count, fn, off) {
      var digit = nth < 10 ? String((nth + 1) % 10) : ''; nth++;
      var r = el('div', { class: 'r' + (off ? ' off' : ''), dataset: digit ? { act: key, key: digit } : { act: key } },
        [el('span', { class: 'k', text: digit }), el('span', { class: 'l', text: label }), count != null ? el('span', { class: 'n', text: String(count) }) : null]);
      if (!off) { r.addEventListener('click', function (e) { e.stopPropagation(); closeCtx(); document.body.dataset.ctxact = key; fn(); }); }
      m.appendChild(r);
    }
    m.appendChild(el('div', { class: 'h', text: w.name }));
    if (s) { act('arm', 'Arm ' + s.id, null, function () { armPick(s, w, null); }, !w.allowed); }
    
    
    
    var sizesWithEmpties = ['LARGE', 'MEDIUM', 'SMALL'].filter(function (sz) { return compatEmpty.some(function (id) { var x = slotById(id); return x && x.size === sz; }); });
    if (!sizesWithEmpties.length) { act('fillsize', 'Fill the empty ' + sizeShort(size).toLowerCase() + ' mounts', 0, function () { }, true); }
    sizesWithEmpties.forEach(function (sz) {
      var ids = compatEmpty.filter(function (id) { var x = slotById(id); return x && x.size === sz; });
      act(sz === size ? 'fillsize' : 'fill' + sz.toLowerCase(), 'Fill the empty ' + sizeShort(sz).toLowerCase() + ' mounts', ids.length, function () { fillMounts(w, ids); }, !w.allowed);
    });
    act('fillall', 'Fill every compatible empty mount', compatEmpty.length, function () { fillMounts(w, compatEmpty); }, !compatEmpty.length || !w.allowed);
    act('replace', 'Put it on every compatible mount', compat.length, function () { fillMounts(w, compat); }, !compat.length || !w.allowed);
    act('remove', 'Remove it from every mount', carrying, function () { removeEverywhere(w); }, !carrying);
    m.appendChild(el('div', { class: 'sep' }));
    act('fav', isFav(w.id) ? '★ Unfavourite' : '★ Favourite (pin it to the top)', null, function () { toggleFav(w.id); });
    act('codex', 'Codex entry', null, function () { if (window.ccCodex) { window.ccCodex.open('codex_weapon_' + w.id); } });
    m.hidden = false;
    var vw = window.innerWidth, vh = window.innerHeight, x = ev.clientX || 0, y = ev.clientY || 0;
    if (x + m.offsetWidth > vw - 8) { x = Math.max(8, vw - 8 - m.offsetWidth); }
    if (y + m.offsetHeight > vh - 8) { y = Math.max(8, vh - 8 - m.offsetHeight); }
    m.style.left = x + 'px'; m.style.top = y + 'px';
    document.body.dataset.ctx = 'open'; document.body.dataset.ctxweapon = w.id;
  }
  
  
  
  function openWingCtx(ev, w, bi) {
    ev.preventDefault(); hideTip(); closeCtx();
    var m = $('ctxMenu'), h = S.hull, free = h ? Math.max(0, bayCount() - (h.builtInWings || []).length) : 0, bays = [];
    for (var i = 0; i < free; i++) { bays.push(i); }
    var empty = bays.filter(function (b) { return !S.wings[b]; }), carrying = bays.filter(function (b) { return S.wings[b] === w.id; });
    var nth = 0;
    function act(key, label, count, fn, off) {
      var digit = nth < 10 ? String((nth + 1) % 10) : ''; nth++;
      var r = el('div', { class: 'r' + (off ? ' off' : ''), dataset: digit ? { act: key, key: digit } : { act: key } },
        [el('span', { class: 'k', text: digit }), el('span', { class: 'l', text: label }), count != null ? el('span', { class: 'n', text: String(count) }) : null]);
      if (!off) { r.addEventListener('click', function (e) { e.stopPropagation(); closeCtx(); document.body.dataset.ctxact = key; fn(); }); }
      m.appendChild(r);
    }
    function put(targets) {   
      var n = 0;
      targets.forEach(function (b) {
        if (S.wings[b] === w.id) { return; }
        var cur = S.wings[b] ? S.wingById[S.wings[b]] : null, need = (w.op || 0) - (cur ? cur.op || 0 : 0);
        if (need > opFree()) { return; }
        bumpOp(need); S.wings[b] = w.id; n++;
      });
      if (n) { S.lastWing = w.id; sfx('ui_refit_slot_filled_fighter'); renderWings(); drawStage(); changed(); flashHint(w.name + ' into ' + n + ' bay' + (n === 1 ? '' : 's')); }
      else { sfx('ui_button_disabled_pressed'); flashHint('no bay took ' + w.name + (targets.length ? ' (not enough OP)' : '')); }
    }
    m.appendChild(el('div', { class: 'h', text: w.name }));
    if (bi != null && bi < free) { act('arm', 'Arm bay ' + (bi + 1), null, function () { armWing(bi, w); }, !w.allowed); }
    act('fillbays', 'Fill every empty bay', empty.length, function () { put(empty); }, !empty.length || !w.allowed);
    act('allbays', 'Put it in every bay', bays.length, function () { put(bays); }, !bays.length || !w.allowed);
    act('removewings', 'Remove it everywhere', carrying.length, function () {
      carrying.forEach(function (b) { var cur = S.wingById[S.wings[b]]; bumpOp(-(cur ? cur.op || 0 : 0)); S.wings[b] = ''; });
      sfx('ui_refit_slot_cleared_fighter'); renderWings(); drawStage(); changed(); flashHint(w.name + ' off ' + carrying.length + ' bay' + (carrying.length === 1 ? '' : 's'));
    }, !carrying.length);
    m.appendChild(el('div', { class: 'sep' }));
    act('codex', 'Codex entry', null, function () { if (window.ccCodex) { window.ccCodex.open('codex_fighter_' + w.id); } });
    m.hidden = false;
    var vw = window.innerWidth, vh = window.innerHeight, x = ev.clientX || 0, y = ev.clientY || 0;
    if (x + m.offsetWidth > vw - 8) { x = Math.max(8, vw - 8 - m.offsetWidth); }
    if (y + m.offsetHeight > vh - 8) { y = Math.max(8, vh - 8 - m.offsetHeight); }
    m.style.left = x + 'px'; m.style.top = y + 'px';
    document.body.dataset.ctx = 'open'; document.body.dataset.ctxwing = w.id;
  }
  function renderArm() {
    S.armFree = opFree();   
    var head = clear($('slotHead')), list = clear($('weapons'));
    var search = $('weaponSearch');
    if (!S.hull) { head.appendChild(el('h3', { text: 'Weapons' })); search.hidden = true; list.appendChild(el('div', { class: 'empty', text: 'Pick a hull first.' })); return; }
    var s = slotById(S.selectedSlot);
    if (!s) {
      
      var last = S.lastWeapon ? S.weaponById[S.lastWeapon] : null;
      head.appendChild(el('h3', { text: 'Loadout' }));
      search.hidden = true;
      list.className = 'scroll list slotList';
      var fittable = S.hull.slots.filter(function (x) { return x.fittable; }).sort(slotFacingCompare);   
      if (!fittable.length) { list.appendChild(el('div', { class: 'empty', text: 'This hull has no weapon mounts.' })); }
      list.appendChild(colHead(['', 'Mount', 'Type', 'Range', 'OP', 'Ammo']));   
      fittable.forEach(function (x) {
        var w = S.fit[x.id] ? S.weaponById[S.fit[x.id]] : null;
        var row = el('div', { class: 'wrow' + (w ? '' : ' bare') + (S.loadoutCursor === x.id ? ' cur' : ''), dataset: w ? { slot: x.id, weapon: w.id } : { slot: x.id },   
          on: { click: function (ev) { if (ev.shiftKey) { shiftApply(x.id); return; } selectSlot(x.id); },
                contextmenu: function (ev) { ev.preventDefault(); clearMount(x.id); },
                
                mouseenter: function () { if (S.hoverSlot !== x.id) { S.hoverSlot = x.id; drawStage(); } },
                mouseleave: function () { if (S.hoverSlot === x.id) { S.hoverSlot = null; drawStage(); } } } }, [
          w ? weaponIcon(w) : el('div', { class: 'ico' }, [el('span', { class: 'mount ' + (x.hardpoint ? 'hp' : 'tt'), style: 'border-color:' + slotColor(x) })]),
          el('div', { class: 'who' }, [
            el('div', { class: 'name' }, [w ? w.name : 'Empty', w ? el('span', { class: 'gchip', text: 'G' + (S.groupOf[x.id] || 1), title: 'weapon group ' + (S.groupOf[x.id] || 1) + ' — click: the Groups tab, on this mount\'s row',
              on: { click: function (ev) { ev.stopPropagation(); sfx('ui_button_pressed'); setTab('groups'); focusGroupRow(x.id); } } }) : null]),
            el('div', { class: 'meta' }, [el('span', { class: 'dot ' + x.type }), el('span', { class: 't', text: sizeShort(x.size) + ' ' + typeLabel(x.type) + (x.hardpoint ? ' hardpoint' : ' turret') })])
          ]),
          el('div', { class: 'col dmg ' + (w ? w.damageType || '' : ''), text: w ? damageLabel(w) : '' }),
          el('div', { class: 'col rng', text: w ? String(w.range) : '' }),
          el('div', w ? { class: 'col op', text: String(effOp(w)), dataset: { op: String(effOp(w)) } } : { class: 'col op' }),   
          el('div', w && w.maxAmmo > 0 ? { class: 'col ammo', text: fmt(effAmmo(w)), dataset: { ammo: String(effAmmo(w)) } } : { class: 'col ammo' })   
        ]);
        if (w) { codexDoor(row, 'codex_weapon_' + w.id); }
        var hint = x.id + ' — click: arm it' + (last ? ' · shift+click: ' + last.name : '') + (w ? ' · right-click: clear it' : '');
        attachTip(row, function () { return w ? weaponTip(w, null).concat([el('div', { class: 'p hint', text: hint })]) : [el('div', { class: 'h', text: 'Empty mount' }), el('div', { class: 'p hint', text: hint })]; });
        list.appendChild(row);
      });
      
      
      var biw = S.hull.builtInWeapons || {};
      S.hull.slots.filter(function (x) { return !x.fittable && !x.hidden && !x.decorative && !x.system && !x.launchBay && !x.stationModule && biw[x.id]; }).forEach(function (x) {
        var bid = biw[x.id], bw = weaponOf(bid);   
        var row = el('div', { class: 'wrow locked', dataset: { slot: x.id, builtin: bid },
          on: { click: function () { sfx('ui_button_disabled_pressed'); flashHint((bw ? bw.name : bid) + ' is built into the hull'); },
                contextmenu: function (ev) { ev.preventDefault(); sfx('ui_button_disabled_pressed'); flashHint((bw ? bw.name : bid) + ' is built into the hull'); },
                mouseenter: function () { if (S.hoverSlot !== x.id) { S.hoverSlot = x.id; drawStage(); } },
                mouseleave: function () { if (S.hoverSlot === x.id) { S.hoverSlot = null; drawStage(); } } } }, [
          bw ? weaponIcon(bw) : el('div', { class: 'ico' }, [el('span', { class: 'mount ' + (x.hardpoint ? 'hp' : 'tt'), style: 'border-color:' + slotColor(x) })]),
          el('div', { class: 'who' }, [
            el('div', { class: 'name' }, [bw ? bw.name : bid, el('span', { class: 'gchip lock', text: 'built in', title: 'built into the hull — never removed or replaced' })]),
            
            el('div', { class: 'meta' }, [el('span', { class: 'dot ' + (bw ? (bw.mountType || bw.type) : x.type) }), el('span', { class: 't', text: sizeShort(bw ? bw.size : x.size) + ' ' + typeLabel(bw ? (bw.mountType || bw.type) : x.type) + (x.hardpoint ? ' hardpoint' : ' turret') })])
          ]),
          el('div', { class: 'col dmg ' + (bw ? bw.damageType || '' : ''), text: bw ? damageLabel(bw) : '' }),
          el('div', { class: 'col rng', text: bw ? String(bw.range) : '' }),
          el('div', { class: 'col op', text: '' })
        ]);
        if (bw) { codexDoor(row, 'codex_weapon_' + bw.id); }
        attachTip(row, function () { return (bw ? weaponTip(bw, null) : [el('div', { class: 'h', text: bid })]).concat([el('div', { class: 'p hint', text: x.id + ' — built into the hull: never removed or replaced, no OP' })]); });
        list.appendChild(row);
      });
      list.dataset.lcursor = S.loadoutCursor && list.querySelector('.wrow.cur') ? S.loadoutCursor : '';   
      return;
    }
    list.className = 'scroll list'; list.dataset.lcursor = '';
    search.hidden = false;
    var fittedW = S.fit[s.id] ? S.weaponById[S.fit[s.id]] : null;
    
    head.appendChild(el('div', { class: 'row' }, [
      el('h3', { text: sizeShort(s.size) + ' ' + typeLabel(s.type) + (s.hardpoint ? ' hardpoint' : ' turret') }),
      el('span', { class: 'badge', style: 'color:' + slotColor(s) + ';border-color:' + slotColor(s), text: s.id })
    ]));
    head.appendChild(el('div', { class: 'row' }, [
      el('span', { class: 'sub fitted', text: fittedW ? ('armed: ' + fittedW.name + ' · ' + effOp(fittedW) + ' OP') : 'empty — pick a weapon below' }),   
      fittedW ? el('button', { text: 'Unarm', on: { click: function () { clearMount(s.id); } } }) : null,
      el('button', { text: 'Done', on: { click: function () { selectSlot(null); } } })
    ]));
    var q = S.weaponSearch.toLowerCase();
    var fits = (s.fits || []).map(function (id) { return S.weaponById[id]; }).filter(Boolean)
      .filter(function (w) { return !q || (w.name + ' ' + w.id).toLowerCase().indexOf(q) >= 0; })
      
      
      
      .sort(function (a, b) { return (a.allowed === b.allowed ? 0 : a.allowed ? -1 : 1) || ((isFav(b.id) ? 1 : 0) - (isFav(a.id) ? 1 : 0)) || (wsizeRank(b.size) - wsizeRank(a.size)) || (b.op - a.op) || byName(a, b); });
    function wsizeRank(sz) { return ({ SMALL: 0, MEDIUM: 1, LARGE: 2 })[sz] || 0; }
    if (!fits.length) { list.appendChild(el('div', { class: 'empty', text: 'No weapon fits this mount' + (q ? ' with that filter.' : '.') })); }
    else { list.appendChild(colHead(['', fits.length + ' fit this mount', 'Type', 'Range', 'OP', 'Ammo'])); }   
    var free = opFree();
    if (fits.length) { list.appendChild(el('div', { class: 'hint', text: 'click: arm this mount · shift+click other mounts on the ship: repeat the weapon · shift+right-click: take it off' })); }
    S.pickerFits = fits;   
    fits.forEach(function (w) {
      var need = effOp(w) - (fittedW ? effOp(fittedW) : 0);   
      var over = S.fit[s.id] !== w.id && need > free;   
      
      var row = weaponRow(w, s, S.fit[s.id] === w.id, function () { if (S.fit[s.id] === w.id) { clearMount(s.id); document.body.dataset.rearm = 'unarmed'; } else { armPick(s, w, null); } });
      if (over) { row.classList.add('nofit'); row.dataset.why = 'not enough OP: needs ' + need + ', ' + free + ' free'; }
      list.appendChild(row);
    });
  }

  
  
  
  
  
  
  
  function modGate(id) {
    var v = S.verdict;
    var mods = v && v.applicability === 'game' ? v.mods : S.gate;   
    return mods && mods[id] ? mods[id] : null;
  }
  
  function modRow(m, pinned) {
    var row = el('div', { class: 'mrow', dataset: pinned ? { id: m.id, pin: '1' } : { id: m.id } }, [
      el('div', { class: 'ico' }, [pixelFit(el('img', { src: base + 'sprite/hullmod/' + encodeURIComponent(m.id), alt: '', loading: 'lazy' }), 48, 2)]),
      el('div', { class: 'who' }, [el('div', { class: 'nm', text: m.name }), el('div', { class: 'meta', text: m.manufacturer || '', style: m.manufacturerColor ? 'color:' + m.manufacturerColor : '' })]),
      el('span', { class: 'cost' }), el('span', { class: 'st' })
    ]);
    codexDoor(row, 'codex_hullmod_' + m.id);
    row.addEventListener('click', function (ev) { toggleMod(m.id, (ev.shiftKey || ev.altKey) ? 'smod' : 'cycle'); });
    row.addEventListener('contextmenu', function (ev) { ev.preventDefault(); toggleMod(m.id, ev.shiftKey ? 'smod' : 'remove'); });   
    attachTip(row, function () { return modTip(m); });   
    return row;
  }
  function buildModList() {
    var box = clear($('mods'));
    S.mrows = {}; S.msecs = []; S.mrowsPinned = {};
    box.appendChild(el('div', { class: 'hint', text: 'click a hullmod: add · click it again: build it in (S-mod, 0 OP) · again: remove · right-click: remove · shift+right-click: build in / un-build' }));
    
    
    S.mpinned = el('div', { class: 'mlist' });
    S.mpinSec = el('section', { dataset: { cat: 'Installed' }, hidden: true }, [el('h4', { text: 'Installed' }), S.mpinned]);
    box.appendChild(S.mpinSec);
    var cats = {}, order = [];
    S.catalog.hullmods.forEach(function (m) {
      
      
      
      if (!m.hidden && !m.allowed) { return; }
      var cat = (m.uiTags && m.uiTags[0]) || 'Other';
      if (!cats[cat]) { cats[cat] = []; order.push(cat); }
      cats[cat].push(m);
    });
    
    
    
    order.sort(function (a, b) { return modCatRank(a) - modCatRank(b) || a.localeCompare(b); });
    order.forEach(function (cat) {
      var sec = el('section', { dataset: { cat: cat } }, [el('h4', { text: cat })]);
      var list = el('div', { class: 'mlist' }), rows = [];
      cats[cat].forEach(function (m) {
        var row = modRow(m, false);
        S.mrows[m.id] = row;
        rows.push(row); list.appendChild(row);
      });
      sec.appendChild(list);
      box.appendChild(sec);
      S.msecs.push({ sec: sec, list: list, rows: rows });   
    });
    orderModSections();
  }
  
  
  
  
  var MOD_CAT_RANK = { Weapons: 1, Defenses: 2, Shields: 3, Engines: 4, Special: 5, Phase: 6, Fighters: 7, Support: 8, Logistics: 9, 'Requires Dock': 10, Other: 99 };
  function modCatRank(cat) {
    if (cat === 'Phase' && hullIsPhase()) { return 0; }
    if (cat === 'Fighters' && S.hull && bayCount() === 0) { return 98; }
    return MOD_CAT_RANK[cat] || 50;
  }
  function hullIsPhase() {
    var h = S.hull; if (!h) { return false; }
    if (S.stats && !S.stats.error && S.statsAt && S.statsAt.hull === h.id) { return !!S.stats.cloak; }   
    var c = (S.catalog && S.catalog.hulls || []).filter(function (x) { return x.id === h.id; })[0];   
    return !!(c && c.phase);
  }
  function orderModSections() {
    var secs = S.msecs.slice().sort(function (a, b) { return modCatRank(a.sec.dataset.cat) - modCatRank(b.sec.dataset.cat) || a.sec.dataset.cat.localeCompare(b.sec.dataset.cat); });
    var key = secs.map(function (x) { return x.sec.dataset.cat; }).join(',');
    if (key !== S.modCatOrder) { S.modCatOrder = key; var box = $('mods'); secs.forEach(function (x) { box.appendChild(x.sec); }); }
    document.body.dataset.modcats = key;
  }
  
  
  
  function sModCap() { var v = S.verdict; return v && v.sMods && typeof v.sMods.cap === 'number' ? v.sMods.cap : 99; }
  function sModCount() { return S.smods.filter(function (x) { return S.mods.indexOf(x) >= 0; }).length; }
  
  
  
  
  function opFree() { var v = S.verdict; return v && typeof v.opMax === 'number' && typeof v.opCost === 'number' ? v.opMax - v.opCost - (S.opLocal || 0) : Infinity; }
  function bumpOp(delta) { S.opLocal = (S.opLocal || 0) + (delta || 0); }
  function modOpCost(m) { return S.hull && m && m.cost ? (m.cost[S.hull.size] || 0) : 0; }
  function sModBlock(m) {
    if (!m || m.noBuildIn) { return 'cannot be built in'; }
    if (sModCount() >= sModCap()) { return 'S-mod cap reached (' + sModCap() + ')'; }
    return null;
  }
  
  
  function toggleMod(id, mode) {
    var row = S.mrows[id];
    if (!row || row.classList.contains('builtin')) { return; }
    var m = S.modById[id];
    var on = S.mods.indexOf(id) >= 0, isS = S.smods.indexOf(id) >= 0;
    
    
    var buildIn0 = mode === 'smod' && !on && !sModBlock(m);
    if (row.classList.contains('off') && !(buildIn0 && row.classList.contains('pricey') && !row.classList.contains('na') && !row.classList.contains('locked'))) { sfx('ui_button_disabled_pressed'); return; }   
    var cost = modOpCost(m), was = (on && !isS) ? cost : 0;
    if (!on && cost > opFree() && !buildIn0) { sfx('ui_button_disabled_pressed'); return; }   
    var unS = function () { S.smods = S.smods.filter(function (x) { return x !== id; }); };
    if (mode === 'remove') { if (on) { S.mods = S.mods.filter(function (x) { return x !== id; }); unS(); } }   
    else if (mode === 'smod') {
      if (isS) { unS(); }                                                   
      else if (!sModBlock(m)) { if (!on) { S.mods.push(id); } S.smods.push(id); }   
      else if (!on) { S.mods.push(id); }                                    
    } else if (!on) { S.mods.push(id); }                                    
    else if (!isS && !sModBlock(m)) { S.smods.push(id); }                   
    else { S.mods = S.mods.filter(function (x) { return x !== id; }); unS(); }   
    var nowOn = S.mods.indexOf(id) >= 0, nowS = S.smods.indexOf(id) >= 0;
    if (nowOn === on && nowS === isS) { sfx('ui_button_disabled_pressed'); return; }   
    bumpOp(((nowOn && !nowS) ? cost : 0) - was);
    sfx(nowS && !isS ? 'ui_char_spent_story_point_technology' : 'ui_button_pressed');   
    S.pinHoldArm = nowOn && !on && S.tab === 'mods';   
    updateModList(); changed();
  }
  function updateModList() {
    var h = S.hull, q = S.modSearch.toLowerCase();
    var builtIn = h ? (h.builtInMods || []) : [];
    var note = $('modsNote'), v = S.verdict;
    note.textContent = !v ? '' : v.applicability === 'game' ? '' : v.applicability === 'pending' ? 'the game is checking…' : 'game not answering — the server decides at join';
    var gn = $('gateNote');
    
    
    gn.textContent = !v ? '' : v.applicability === 'game' ? 'hullmod rules: checked by the game'
      : v.applicability === 'pending' ? 'hullmod rules: asking the game…' + (v.applicabilityNote ? ' — ' + v.applicabilityNote : '')
      : 'hullmod rules: the game did not answer — the server decides at join' + (v.applicabilityNote ? ' (' + v.applicabilityNote + ')' : '');
    gn.title = gn.textContent;   
    gn.classList.toggle('paused', !!(v && v.applicability === 'pending' && /advancing/.test(v.applicabilityNote || '')));
    var dmodRows = 0;
    Object.keys(S.mrows).forEach(function (id) {
      var row = S.mrows[id], m = S.modById[id];
      var g = modGate(id);
      var on = S.mods.indexOf(id) >= 0, isS = S.smods.indexOf(id) >= 0, bi = builtIn.indexOf(id) >= 0;
      
      var dmodShown = !!(m.dmod && m.allowed && S.showDmods && !(g && g.ok === false));
      
      var hidden = !h || (!bi && ((g && g.show === false) || (m.hidden && !dmodShown))) || (q && (m.name + ' ' + id + ' ' + (m.manufacturer || '') + ' ' + (m.uiTags || []).join(' ')).toLowerCase().indexOf(q) < 0);
      var refused = !!(g && g.ok === false), locked = !!(g && g.now === false);
      if (m.dmod && !bi && !hidden) { dmodRows++; }
      styleModRow(row, m, h, g, hidden, on, isS, bi, refused, locked);
      
      var pin = S.mrowsPinned[id];
      if (on || bi) {
        if (!pin) { pin = S.mrowsPinned[id] = modRow(m, true); }
        styleModRow(pin, m, h, g, hidden, on, isS, bi, refused, locked);
      } else if (pin) { delete S.mrowsPinned[id]; if (pin.parentNode) { pin.parentNode.removeChild(pin); } }
    });
    document.body.dataset.dmods = String(dmodRows);   
    if (S.mpinned) {   
      
      
      
      var pinList = S.mpinned, pi = 0, held = S.pinHeld || (S.pinHeld = {}), atTop = $('mods').scrollTop <= 0;
      S.msecs.forEach(function (x) { x.rows.forEach(function (r) {
        var id = r.dataset.id, pin = S.mrowsPinned[id]; if (!pin) { delete held[id]; return; }
        if (pin.parentNode !== pinList && atTop && (held[id] || S.pinHoldArm)) { held[id] = true; return; }
        delete held[id];
        if (pinList.children[pi] !== pin) { pinList.insertBefore(pin, pinList.children[pi] || null); }
        pi++;
      }); });
      S.pinHoldArm = false;
      S.mpinSec.hidden = !pinList.querySelector('.mrow:not(.hide)');
      document.body.dataset.pinned = String(Object.keys(S.mrowsPinned).length);   
      document.body.dataset.pindefer = Object.keys(held).length ? '1' : '0';   
    }
    S.msecs.forEach(function (x) {
      var sunk = function (r) { return r.classList.contains('sunk'); };
      x.rows.filter(function (r) { return !sunk(r); }).concat(x.rows.filter(sunk)).forEach(function (r, i) {
        if (x.list.children[i] !== r) { x.list.insertBefore(r, x.list.children[i] || null); }   
      });
      x.sec.hidden = !x.sec.querySelector('.mrow:not(.hide)');
    });
    orderModSections();   
  }
  
  function styleModRow(row, m, h, g, hidden, on, isS, bi, refused, locked) {
      row.classList.toggle('hide', !!hidden);
      row.classList.toggle('on', on || bi);
      row.classList.toggle('smod', on && isS);
      row.classList.toggle('builtin', bi);
      var pricey = !bi && !on && !refused && !locked && modOpCost(m) > opFree();   
      row.classList.toggle('off', !bi && !on && (refused || locked || pricey));
      
      
      row.classList.toggle('pricey', pricey);
      row.classList.toggle('na', !bi && !on && refused);
      row.classList.toggle('locked', !bi && !on && !refused && locked);
      row.classList.toggle('bad', on && refused);
      row.classList.toggle('sunk', !bi && !on && (refused || locked));   
      row.querySelector('.cost').textContent = bi ? '' : (!on && refused ? 'n / a' : (h && m.cost ? (isS ? '0' : m.cost[h.size]) + ' OP' : ''));
      
      
      var st = row.querySelector('.st'), meta = row.querySelector('.meta');
      st.textContent = bi ? 'built in' : on && isS ? 'S' : on ? '✓' : (locked && !refused) ? 'dock' : '';
      meta.textContent = !on && refused ? (g.reason || 'the game refuses it on this ship') : !on && locked ? (g.nowReason || 'cannot be changed now') : (m.manufacturer || '');
      meta.classList.toggle('why', !on && (refused || locked));
  }

  
  function renderShip() {
    var box = clear($('statsBody'));
    var st = S.stats, h = S.hull;
    if (!h) { box.appendChild(el('div', { class: 'empty', text: 'Pick a hull first.' })); return; }
    if (!st || st.error) { box.appendChild(el('div', { class: 'empty', text: st && st.error ? 'stats unavailable: ' + st.error : 'computing…' })); return; }
    var t = null;
    
    
    function sec(title, count, wide) {
      var det = el('details', S.shipClosed[title] ? { class: wide ? 'wide' : '' } : { class: wide ? 'wide' : '', open: '' }, [
        el('summary', {}, [title, count != null ? el('span', { class: 'n', text: String(count) }) : null])
      ]);
      det.addEventListener('toggle', function () { S.shipClosed[title] = !det.open; });
      t = el('table');
      det.appendChild(t);
      box.appendChild(det);
    }
    function row(k, v, base) {   
      var cell = el('td', { text: v });
      if (typeof base === 'number' && typeof v === 'number' && base !== v) { cell.textContent = fmt(v); cell.appendChild(el('span', { class: 'd' + (v < base ? ' down' : ''), text: (v > base ? '+' : '') + fmt(v - base) })); }
      else if (typeof v === 'number') { cell.textContent = fmt(v); }
      t.appendChild(el('tr', {}, [el('td', { text: k }), cell]));
    }
    
    
    if (h.system) {
      var sysLine = codexDoor(el('div', { class: 'sysLine', title: 'the codex entry for this ship system (F2)' }, [el('span', { class: 'k', text: 'Ship system' }), el('span', { class: 'v', text: h.systemName || h.system })]), 'codex_system_' + h.system);
      sysLine.addEventListener('click', function () { hideTip(); if (window.ccCodex) { window.ccCodex.open('codex_system_' + h.system); } });
      box.appendChild(sysLine);
    }
    sec('Hull');
    row('Hull integrity', st.hp, h.hp); row('Armor rating', st.armor, h.armor); row('Max speed', st.maxSpeed); row('Acceleration', st.acceleration); row('Turn rate', st.turnRate);
    sec('Flux');
    row('Flux capacity', st.fluxCap, h.fluxCap); row('Flux dissipation', st.fluxDiss, h.fluxDiss);
    var sh = st.shield && typeof st.shield === 'object' ? st.shield : null;
    if (sh) {
      sec('Shield');
      if (sh.type) { row('Shield type', String(sh.type).charAt(0) + String(sh.type).slice(1).toLowerCase()); }
      if (typeof sh.arc === 'number') { row('Shield arc', sh.arc); }
      if (typeof sh.fluxPerDamage === 'number') { row('Shield efficiency (flux / damage)', sh.fluxPerDamage); }
      if (typeof sh.upkeepMult === 'number') { row('Shield upkeep (× dissipation)', sh.upkeepMult); }
      if (typeof sh.damageTakenMult === 'number' && sh.damageTakenMult !== 1) { row('Shield damage taken (×)', sh.damageTakenMult); }
    }
    sec('Ordnance');
    row('Weapons', fmt(st.opWeapons) + ' OP'); row('Hullmods', fmt(st.opHullMods) + ' OP'); row('Unused', fmt(st.opUnused) + ' OP');
    if (st.bays > 0) { row('Fighter bays', st.bays); }
    
    
    
    
    if (st.peakTime > 0 || st.deployPoints > 0) {
      sec('Combat readiness');
      
      if (st.peakTime > 0) { var pk = Math.round(st.peakTime); row('Peak performance', pk >= 60 ? Math.floor(pk / 60) + ':' + ('0' + (pk % 60)).slice(-2) + ' min' : pk + ' s'); }
      if (st.deployPoints > 0) { row('Deployment points', fmt(st.deployPoints)); }
    }
    if (st.weapons && st.weapons.length) {
      
      
      sec('Weapons (range · ammo)', st.weapons.length, true);
      st.weapons.forEach(function (w) {
        var ammo = typeof w.ammo === 'number' ? ' · ' + fmt(w.ammo) + ' ammo' + (typeof w.baseAmmo === 'number' && w.baseAmmo !== w.ammo ? ' (base ' + fmt(w.baseAmmo) + ')' : '') : '';
        row(w.name + ' · ' + w.slot, fmt(w.range) + (w.baseRange && w.baseRange !== w.range ? ' (base ' + fmt(w.baseRange) + ')' : '') + ammo);
      });
    }
    
    box.appendChild(el('div', { class: 'sub', style: 'padding:8px 4px', text: st.skills === 'applied'
      ? 'The game’s numbers for this build with all captain skills and commander bonuses applied (the Skills toggle on the stats column; the OP budget never moves).'
      : 'The game’s numbers for this build, no skills applied (the Skills toggle on the stats column applies all captain skills and commander bonuses).' }));
  }

  
  function roleLabel(r) { return ({ FIGHTER: 'Fighter', INTERCEPTOR: 'Interceptor', BOMBER: 'Bomber', SUPPORT: 'Support', ASSAULT: 'Assault' })[r] || (r ? r.charAt(0) + r.slice(1).toLowerCase() : ''); }
  function wingIcon(w) { return el('div', { class: 'ico' }, [pixelFit(el('img', { class: 'wing', src: base + 'sprite/wing/' + encodeURIComponent(w.id), alt: '', loading: 'lazy' }), 38)]); }
  
  
  function wingRow(w, sel, onClick, bi) {
    var row = el('div', { class: 'wrow' + (sel ? ' sel' : '') + (w.allowed ? '' : ' off'), dataset: { wing: w.id } }, [
      wingIcon(w),
      el('div', { class: 'who' }, [
        el('div', { class: 'name', text: w.name }),
        el('div', { class: 'meta' }, [el('span', { class: 't', text: w.numFighters + ' craft' })])
      ]),
      el('div', { class: 'col role ' + (w.role || ''), text: roleLabel(w.role) }),
      el('div', { class: 'col op', text: String(w.op) })
    ]);
    codexDoor(row, 'codex_fighter_' + w.id);
    if (w.allowed && onClick) { row.addEventListener('click', onClick); }
    row.addEventListener('contextmenu', function (ev) {
      if (ev.shiftKey && bi != null) {
        ev.preventDefault(); hideTip(); closeCtx();
        if (S.wings[bi] === w.id) { clearBay(bi); document.body.dataset.wingctxshift = 'removed'; }
        else { sfx('ui_button_disabled_pressed'); flashHint('bay ' + (bi + 1) + (S.wings[bi] ? ' carries ' + (S.wingById[S.wings[bi]] || { name: S.wings[bi] }).name + ', not ' + w.name : ' is empty')); document.body.dataset.wingctxshift = 'nomatch'; }
        return;
      }
      openWingCtx(ev, w, bi);
    });
    attachTip(row, function () { return wingTip(w, w.allowed ? (sel ? 'click: take it off the bay · right-click: menu' : 'click: put it in the bay · right-click: menu') : null); });
    return row;
  }
  function bayIds(h) { return h.slots.filter(function (s) { return s.launchBay; }).map(function (s) { return s.id; }); }
  function selectBay(bi) {
    if (bi != null) { S.bayCursor = bi; }   
    S.selectedBay = bi; renderWings(); navCommit();
  }
  
  function wingOrder(q) {
    var roleRank = { FIGHTER: 0, INTERCEPTOR: 1, BOMBER: 2, SUPPORT: 3 };
    return S.catalog.wings.filter(function (w) { return w.allowed || S.showDenied; })
      .filter(function (w) { return !q || (w.name + ' ' + w.id + ' ' + roleLabel(w.role)).toLowerCase().indexOf(q) >= 0; })
      .sort(function (a, b) { return (a.allowed === b.allowed ? 0 : a.allowed ? -1 : 1) || ((roleRank[a.role] == null ? 9 : roleRank[a.role]) - (roleRank[b.role] == null ? 9 : roleRank[b.role])) || (a.op - b.op) || byName(a, b); });
  }
  function renderWings() {
    var head = clear($('bayHead')), list = clear($('wingsBody')), search = $('wingSearch'), h = S.hull, bays = bayCount();
    renderBays();
    
    S.baysShown = bays;
    $('tabs').querySelector('[data-tab=wings]').hidden = !(bays > 0);
    $('tabs').classList.toggle('four', bays > 0);
    if (S.wingsWish && bays > 0) { S.wingsWish = false; setTab('wings'); }   
    if (S.bayWish != null && bays > 0) { var bw = S.bayWish; S.bayWish = null; if (bw < bays - (h.builtInWings || []).length) { S.selectedBay = bw; S.bayCursor = bw; setTab('wings'); } }   
    if (S.tab === 'wings' && !(bays > 0)) { setTab('arm'); }
    if (!h || !(bays > 0)) { search.hidden = true; return; }
    var builtIn = h.builtInWings || [], free = Math.max(0, bays - builtIn.length), ids = bayIds(h);
    var bi = S.selectedBay;
    if (bi == null || bi >= free) {
      
      head.appendChild(el('div', { class: 'row' }, [el('h3', { text: 'Fighter bays' }), el('span', { class: 'sub', text: bays + ' bay' + (bays > 1 ? 's' : '') + (bays > (h.bays || 0) ? ' · ' + (bays - (h.bays || 0)) + ' from a hullmod' : '') })]));
      search.hidden = true;
      list.appendChild(colHead(['', 'Bay', 'Role', 'OP']));
      builtIn.forEach(function (id, i) {
        var w = S.wingById[id];
        list.appendChild(codexDoor(el('div', { class: 'wrow builtin', title: 'built into this hull' }, [
          w ? wingIcon(w) : el('div', { class: 'ico' }),
          el('div', { class: 'who' }, [el('div', { class: 'name', text: w ? w.name : id }), el('div', { class: 'meta' }, [el('span', { class: 't', text: 'built in' + (w ? ' · ' + w.numFighters + ' craft' : '') })])]),
          el('div', { class: 'col role ' + (w ? w.role || '' : ''), text: w ? roleLabel(w.role) : '' }),
          el('div', { class: 'col op', text: '' })
        ]), w ? 'codex_fighter_' + w.id : ''));
      });
      for (var i = 0; i < free; i++) {
        (function (bi) {
          var w = S.wings[bi] ? S.wingById[S.wings[bi]] : null, bayName = 'bay ' + (bi + 1) + ' · ' + bayOrigin(builtIn.length + bi);   
          
          
          var brow = el('div', { class: 'wrow' + (w ? '' : ' bare') + (S.bayCursor === bi ? ' cur' : ''), dataset: { bay: String(bi) },
            on: { click: function (ev) { if (S.dragClick) { S.dragClick = false; return; } if (ev.shiftKey) { shiftApplyWing(bi); return; } selectBay(bi); },
                  contextmenu: function (ev) { ev.preventDefault(); hideTip(); clearBay(bi); },
                  mousedown: function (ev) { if (ev.button === 0 && ev.shiftKey) { ev.preventDefault(); hideTip(); wingPaintStart('rows'); wingPaintOver(bi, true); } },
                  mouseenter: function (ev) { if (S.wingPaint && S.wingPaint.via === 'rows') { if (!(ev.buttons & 1) || !ev.shiftKey) { wingPaintEnd(); } else { wingPaintOver(bi); } } } } }, [   
            w ? wingIcon(w) : el('div', { class: 'ico' }, [el('span', { class: 'mount bay' })]),
            el('div', { class: 'who' }, [
              el('div', { class: 'name', text: w ? w.name : 'Empty bay' }),
              el('div', { class: 'meta' }, [el('span', { class: 't', text: w ? w.numFighters + ' craft' : 'Bay ' + (bi + 1) })])
            ]),
            el('div', { class: 'col role ' + (w ? w.role || '' : ''), text: w ? roleLabel(w.role) : '' }),
            el('div', { class: 'col op', text: w ? String(w.op) : '' })
          ]);
          codexDoor(brow, w ? 'codex_fighter_' + w.id : '');
          var lastW = S.lastWing ? S.wingById[S.lastWing] : null;
          attachTip(brow, function () { return w ? wingTip(w, bayName + ' · click: pick a wing · right-click: clear' + (lastW ? ' · shift+click: ' + lastW.name : '')) : [el('div', { class: 'h', text: 'Empty bay' }), el('div', { class: 'p hint', text: bayName + ' · click: pick a wing' + (lastW ? ' · shift+click: ' + lastW.name : '') })]; });
          list.appendChild(brow);
        })(i);
      }
      list.dataset.bcursor = S.bayCursor != null && S.bayCursor < free ? String(S.bayCursor) : '';   
      return;
    }
    search.hidden = false;
    var cur = S.wings[bi] ? S.wingById[S.wings[bi]] : null;
    head.appendChild(el('div', { class: 'row' }, [
      el('h3', { text: 'Fighter bay ' + (bi + 1) }),
      el('span', { class: 'badge bay', text: bayOrigin(builtIn.length + bi), title: 'where this bay comes from', dataset: { bayid: ids[builtIn.length + bi] || '' } })   
    ]));
    head.appendChild(el('div', { class: 'row' }, [
      el('span', { class: 'sub fitted', text: cur ? ('assigned: ' + cur.name + ' · ' + cur.op + ' OP') : 'empty — pick a wing below' }),
      cur ? el('button', { text: 'Clear', on: { click: function () { clearBay(bi); } } }) : null,   
      el('button', { text: 'Done', on: { click: function () { selectBay(null); } } })
    ]));
    var q = S.wingSearch.toLowerCase();
    var wings = wingOrder(q);
    if (!wings.length) { list.appendChild(el('div', { class: 'empty', text: 'No wing' + (q ? ' matches that filter.' : ' is allowed at this tier.') })); }
    else { list.appendChild(colHead(['', wings.length + ' wings', 'Role', 'OP'])); }
    S.wingFits = wings;   
    wings.forEach(function (w) {
      
      list.appendChild(wingRow(w, cur && cur.id === w.id, function () {
        if (cur && cur.id === w.id) { clearBay(bi); document.body.dataset.wingrearm = 'cleared'; } else { armWing(bi, w); }
      }, bi));
    });
  }

  
  
  
  
  
  function fmt2(n) { return (typeof n === 'number' && isFinite(n)) ? (Math.round(n * 100) / 100).toString() : String(n == null ? '' : n); }
  function renderStatsCol(capOpt, dissOpt) {
    var col = $('statsCol'), h = S.hull, st = S.stats && !S.stats.error ? S.stats : null;
    col.hidden = !h;
    if (!h) { renderCrBox(); return; }
    var live = !!(st && S.statsAt && S.statsAt.hull === h.id), b = live && st.base && typeof st.base === 'object' ? st.base : {};
    clear(col);
    col.classList.toggle('dim', !live);
    
    
    
    
    
    var skb = el('button', { class: 'link' + (S.skills ? ' on' : ''), type: 'button', id: 'skillsBtn', text: S.skills ? 'on' : 'off',
      title: S.skills ? 'All captain skills and commander bonuses applied (deprecated ones excluded) · click: off'
        : 'Apply all captain skills and commander bonuses (deprecated ones excluded) · click: on' });
    skb.addEventListener('click', function () { setSkills(!S.skills); });
    col.appendChild(el('div', { class: 'srow skills', title: 'Skills: these numbers with all captain skills and commander bonuses applied (the OP budget never moves)' }, [el('span', { class: 'k', text: 'Skills' }), skb]));
    if (live && S.skills && st.skills !== 'applied') { col.appendChild(el('div', { class: 'srow err', text: 'skills not applied' + (st.skillsError ? ': ' + st.skillsError : '') })); }
    if (S.stats && S.stats.error) { col.appendChild(el('div', { class: 'srow err', text: 'stats unavailable: ' + S.stats.error })); }
    
    
    function row(k, v, base, tip, two, lower) {
      var val = el('span', { class: 'v' });
      if (typeof v === 'number') {
        
        val.textContent = two ? fmt2(v) : fmt(Math.round(v));
        if (typeof base === 'number' && (two ? Math.round(v * 100) !== Math.round(base * 100) : Math.round(v) !== Math.round(base))) {
          var good = lower ? v < base : v > base;
          val.classList.add(good ? 'up' : 'down');
          val.appendChild(el('span', { class: 'd' + (good ? '' : ' down'), text: (v > base ? '+' : '') + (two ? fmt2(v - base) : fmt(Math.round(v - base))) }));
        }
      } else { val.textContent = v == null ? '…' : String(v); }
      col.appendChild(el('div', tip ? { class: 'srow', title: tip } : { class: 'srow' }, [el('span', { class: 'k', text: k }), val]));
    }
    var bare = function (n) { return typeof n === 'number' ? ' (the bare hull: ' + fmt(n) + ')' : ''; };
    row('Hull', live ? st.hp : h.hp, typeof b.hp === 'number' ? b.hp : h.hp, 'hull integrity' + bare(h.hp));
    row('Armor', live ? st.armor : h.armor, typeof b.armor === 'number' ? b.armor : h.armor, 'armor rating' + bare(h.armor));
    row('Top speed', live ? st.maxSpeed : null, b.maxSpeed, 'max speed — follows the combat-readiness bar' + bare(b.maxSpeed));
    if (h.bays > 0 || (live && st.bays > 0)) { row('Flight decks', live ? st.bays : h.bays, h.bays, 'fighter bays' + (live && st.bays > (h.bays || 0) ? ' — a hullmod adds ' + (st.bays - (h.bays || 0)) : '')); }   
    row('Flux capacity', live ? capOpt : h.fluxCap, h.fluxCap, 'flux capacity: the hull + capacitors × ' + fmt(h.perCap) + ' (hullmods multiply it)');
    row('Flux dissipation', live ? dissOpt : h.fluxDiss, h.fluxDiss, 'flux dissipation / sec: the hull + vents × ' + fmt(h.perVent) + ' (hullmods multiply it)');
    row('Weapon flux/sec', live ? st.weaponFlux : null, null, 'every fitted weapon firing sustained, summed (the refit row)');
    var sh = live && st.shield && typeof st.shield === 'object' ? st.shield : null, ck = live && st.cloak && typeof st.cloak === 'object' ? st.cloak : null;
    if (ck) {
      row('Cloak flux/sec', ck.fluxPerSec, b.cloakFluxPerSec, 'phase cloak upkeep: flux / sec while phased (lower is better)', false, true);
      row('Cloak activation', ck.activation, b.cloakActivation, 'flux to activate the phase cloak (lower is better)', false, true);
    } else if (sh) {
      row('Shield flux/sec', sh.upkeep, b.shieldUpkeep, 'shield upkeep: flux / sec while raised (lower is better)', false, true);
      row('Shield arc', sh.arc, b.shieldArc, 'shield arc in degrees');
      row('Shield flux/dam', typeof sh.fluxPerDamageEff === 'number' ? sh.fluxPerDamageEff : sh.fluxPerDamage, b.fluxPerDamage, 'flux taken per point of damage on the shield — follows the combat-readiness bar (lower is better)', true, true);
    } else {
      var dash = live ? '—' : null;
      row('Shield flux/sec', dash); row('Shield arc', dash); row('Shield flux/dam', dash);
    }
    
    if (S.sprite && S.statsColH !== (col.hidden ? 0 : col.offsetHeight)) { drawStage(); }
    renderCrBox();
  }
  function renderCrBox() {
    var box = $('crBox'), h = S.hull, st = S.stats && !S.stats.error ? S.stats : null;
    box.hidden = !h;
    if (!h) { $('bays').style.top = ''; return; }
    var cp = st && st.crPreview && typeof st.crPreview === 'object' ? st.crPreview : null;
    $('crRange').value = S.cr;
    $('crRange').style.setProperty('--crPct', S.cr + '%');
    $('crText').textContent = S.cr + ' %';
    $('crReset').hidden = S.cr === 100;
    var ticks = clear($('crTicks'));
    if (cp) {
      ['critical', 'malfunction', 'degrade', 'improve'].forEach(function (k) {
        if (typeof cp[k] === 'number' && cp[k] > 0 && cp[k] < 1) { var t = el('span', { class: 'tick', title: k + ' threshold: ' + Math.round(cp[k] * 100) + ' %' }); t.style.left = (cp[k] * 100) + '%'; ticks.appendChild(t); }
      });
    }
    var fx = clear($('crFx'));
    if (!cp || !cp.effects) { fx.appendChild(el('div', { class: 'one', text: st ? (cp && cp.error ? 'no readiness preview: ' + cp.error : 'no readiness preview from the game') : 'computing…' })); }
    else {
      var rows = cp.effects.filter(function (e) { return e && e.type !== 'NEUTRAL'; });
      if (!rows.length) { fx.appendChild(el('div', { class: 'one', text: 'standard performance' })); }
      rows.forEach(function (e) { fx.appendChild(el('div', { class: 'fx ' + (/^(BONUS|PENALTY)$/.test(e.type) ? e.type : 'NEUTRAL') }, [el('span', { class: 'n', text: e.desc }), el('span', { class: 'x', text: e.value })])); });
    }
    box.classList.toggle('stale', !!(cp && cp.cr !== S.cr));   
    
    $('bays').style.top = bayCount() > 0 ? (box.offsetHeight + 20) + 'px' : '';
    
    if (S.sprite && S.crBoxH !== box.offsetHeight + 10) { drawStage(); }
  }
  function crChanged() {
    renderCrBox();
    if (!S.hull) { return; }
    if (S.timer) { clearTimeout(S.timer); }
    S.timer = setTimeout(refresh, 120);
  }

  function renderBudget() {
    var h = S.hull, v = S.verdict, st = S.stats && !S.stats.error ? S.stats : null;
    var opMeter = document.querySelector('.meter.op');
    var op = v && typeof v.opCost === 'number' ? v.opCost : (st ? st.opCost : 0), opMax = v && typeof v.opMax === 'number' ? v.opMax : (h ? h.op : 0);
    renderOpSegs(op, opMax);
    opMeter.classList.toggle('over', op > opMax);
    $('opText').textContent = h ? (op + ' / ' + opMax + (opMax - op >= 0 ? '  (' + (opMax - op) + ' free)' : '  (' + (op - opMax) + ' over)')) : '—';
    function withDelta(node, val, baseVal) {
      clear(node);
      if (!h) { node.textContent = '—'; return; }
      node.appendChild(document.createTextNode(fmt(val)));
      if (typeof baseVal === 'number' && typeof val === 'number' && val !== baseVal) { node.appendChild(el('span', { class: 'd', text: (val > baseVal ? '+' : '') + fmt(val - baseVal) })); }
    }
    
    
    
    
    var live = st && h && S.statsAt && S.statsAt.hull === h.id;
    var capOpt = live ? Math.round(st.fluxCap + (S.caps - S.statsAt.caps) * (typeof st.capSlope === 'number' ? st.capSlope : h.perCap)) : (h ? h.fluxCap : null);
    var dissOpt = live ? Math.round(st.fluxDiss + (S.vents - S.statsAt.vents) * (typeof st.ventSlope === 'number' ? st.ventSlope : h.perVent)) : (h ? h.fluxDiss : null);
    
    
    function withUnit(node, val, baseVal, unit) { withDelta(node, val, baseVal); if (h) { node.insertBefore(el('span', { class: 'u', text: unit }), node.firstChild.nextSibling); } }
    withUnit($('ventsEff'), dissOpt, h ? h.fluxDiss : null, '/s');
    withUnit($('capsEff'), capOpt, h ? h.fluxCap : null, 'flux');
    renderStatsCol(capOpt, dissOpt);
    $('smodText').textContent = v && v.sMods ? v.sMods.n + ' / ' + v.sMods.cap : (h ? '0' : '—');
    $('ventsRange').value = S.vents; $('capsRange').value = S.caps;
    $('ventsText').textContent = S.vents + (h ? ' / ' + h.maxVents : ''); $('capsText').textContent = S.caps + (h ? ' / ' + h.maxCaps : '');
    
    var free = opFree();
    $('ventsDown').disabled = !h || S.vents <= 0; $('ventsUp').disabled = !h || S.vents >= h.maxVents || free <= 0;
    $('capsDown').disabled = !h || S.caps <= 0; $('capsUp').disabled = !h || S.caps >= h.maxCaps || free <= 0;
    if (h) {
      
      
      
      var vNote = 'each vent: +' + fmt(h.perVent) + ' flux dissipation / sec for 1 OP (hullmods multiply it — the number on the row is the total)';
      var cNote = 'each capacitor: +' + fmt(h.perCap) + ' flux capacity for 1 OP (hullmods multiply it — the number on the row is the total)';
      ['ventsRange', 'ventsText', 'ventsLabel'].forEach(function (id) { $(id).title = vNote; });
      ['capsRange', 'capsText', 'capsLabel'].forEach(function (id) { $(id).title = cNote; });
      $('ventsUp').title = 'one vent on (hold: repeat · shift: ten) — ' + vNote; $('capsUp').title = 'one capacitor on (hold: repeat · shift: ten) — ' + cNote;
    }
    renderGroups();
  }
  
  
  
  
  
  
  
  var SEG_RANK = { w: 0, wing: 1, mod: 2, vents: 3, caps: 4, other: 5 };
  function renderOpSegs(op, opMax) {
    var box = $('opSegs'), h = S.hull, items = [];
    if (h) {
      Object.keys(S.fit).forEach(function (slotId) {
        var w = S.fit[slotId] ? S.weaponById[S.fit[slotId]] : null, t = w ? (w.mountType || w.type) : '';
        if (w && effOp(w) > 0) { items.push({ key: 'w:' + slotId, kind: 'w', cls: t, op: effOp(w), sub: WT_ORDER[t] || 0, name: w.name, note: slotId + ' · ' + sizeShort(w.size) + ' ' + typeLabel(t).toLowerCase() + ' weapon' }); }
      });
      S.wings.forEach(function (id, bi) {
        var wg = id ? S.wingById[id] : null;
        if (wg && wg.op > 0) { items.push({ key: 'g:' + bi, kind: 'wing', op: wg.op, sub: 0, name: wg.name, note: 'bay ' + (bi + 1) + ' · ' + roleLabel(wg.role).toLowerCase() + ' wing · ' + wg.numFighters + ' craft' }); }
      });
      var builtIn = h.builtInMods || [];
      S.mods.forEach(function (id) {
        if (S.smods.indexOf(id) >= 0 || builtIn.indexOf(id) >= 0) { return; }
        var m = S.modById[id], c = modOpCost(m);
        if (m && c > 0) { items.push({ key: 'm:' + id, kind: 'mod', op: c, sub: 0, name: m.name, note: 'hullmod · ' + c + ' OP on a ' + sizeName(h.size).toLowerCase() }); }
      });
      if (S.vents > 0) { items.push({ key: 'vents', kind: 'vents', op: S.vents, units: S.vents, sub: 0, name: 'Vents', note: S.vents + ' × 1 OP · flux dissipation' }); }
      if (S.caps > 0) { items.push({ key: 'caps', kind: 'caps', op: S.caps, units: S.caps, sub: 0, name: 'Capacitors', note: S.caps + ' × 1 OP · flux capacity' }); }
    }
    
    
    var sum = items.reduce(function (a, it) { return a + it.op; }, 0), scale = 1;
    if (!S.opStale && S.verdict && typeof S.verdict.opCost === 'number') {
      if (op - sum !== S.opSegGap && op < sum) { console.warn('OP bar: the items add up to ' + sum + ' OP, the game counts ' + op + ' — widths scaled'); }
      S.opSegGap = op - sum;
    }
    var gap = h ? (S.opSegGap || 0) : 0;
    if (gap > 0) { items.push({ key: 'other', kind: 'other', op: gap, sub: 0, name: 'Other', note: 'the game counts ' + gap + ' OP more than these items add up to — a hullmod or skill that changes an item’s cost' }); }
    else if (gap < 0 && sum > 0) { scale = (sum + gap) / sum; }
    op = sum + gap;
    items.sort(function (a, b) { return (b.op - a.op) || (SEG_RANK[a.kind] - SEG_RANK[b.kind]) || (a.sub - b.sub) || a.name.localeCompare(b.name) || a.key.localeCompare(b.key); });
    var span = Math.max(opMax || 0, op, 1), pxPerOp = box.clientWidth > 0 ? box.clientWidth / span : 0;
    var live = {}, nodes = S.opSegs || (S.opSegs = {});
    items.forEach(function (it) { live[it.key] = it; });
    Object.keys(nodes).forEach(function (k) {
      if (live[k]) { return; }
      var gone = nodes[k]; delete nodes[k];
      gone.classList.add('gone'); gone.dataset.op = '0'; gone.style.flexBasis = '0%';
      setTimeout(function () { if (gone.parentNode) { gone.parentNode.removeChild(gone); } }, 300);
    });
    var prev = null;
    items.forEach(function (it) {
      var n = nodes[it.key], fresh = !n;
      if (fresh) {
        n = el('div', { class: 'seg', dataset: { key: it.key, op: '0' } });
        n.style.flexBasis = '0%';
        
        
        
        attachTip(n, function () { var cur = S.opSegItems && S.opSegItems[it.key]; return cur ? [el('div', { class: 'h', text: cur.name }), el('div', { class: 'p', text: cur.note }), tipRow('Ordnance points', String(cur.op)), el('div', { class: 'p hint', text: segHint(it.key) })] : []; }, 0, 'above');
        n.addEventListener('click', function (ev) { if (ev.shiftKey && it.key.indexOf('m:') === 0) { segBuildIn(it.key); } else { segClick(it.key); } });
        n.addEventListener('contextmenu', function (ev) { ev.preventDefault(); segRemove(it.key); });
        nodes[it.key] = n;
      }
      n.className = 'seg ' + it.kind + (it.cls ? ' ' + it.cls : '') + (it.units && pxPerOp >= 4 ? ' units' : '');
      n.dataset.op = String(it.op);
      if (it.units) { n.style.setProperty('--cell', 'calc(100% / ' + it.units + ')'); }
      var want = prev ? prev.nextSibling : box.firstChild;
      if (n !== want) { box.insertBefore(n, want); }
      if (fresh) { void n.offsetWidth; }   
      n.style.flexBasis = (it.op * scale / span * 100).toFixed(3) + '%';
      prev = n;
    });
    S.opSegItems = live;
  }
  
  
  
  
  
  function segHint(key) {
    var kind = key.indexOf(':') > 0 ? key.slice(0, key.indexOf(':')) : key;
    return kind === 'w' ? 'click: this mount · right-click: unarm it' : kind === 'm' ? 'click: this hullmod on its tab · right-click: remove it · shift+click: build it in'
      : kind === 'g' ? 'click: this bay · right-click: clear it' : (kind === 'vents' || kind === 'caps') ? 'click: the slider · right-click: down to 0' : '';
  }
  
  
  
  
  function segRemove(key) {
    hideTip();
    var i = key.indexOf(':'), kind = i > 0 ? key.slice(0, i) : key, id = i > 0 ? key.slice(i + 1) : '';
    if (kind === 'w' && slotById(id)) { clearMount(id); }
    else if (kind === 'm' && S.mrows[id]) { toggleMod(id, 'remove'); }
    else if (kind === 'g' && S.hull) { clearBay(parseInt(id, 10)); }
    else if ((kind === 'vents' || kind === 'caps') && S.hull && S[kind] > 0) { bumpOp(-S[kind]); S[kind] = 0; $(kind + 'Range').value = 0; sfx('ui_button_pressed'); changed(); }
    document.body.dataset.segremove = key;
  }
  function segBuildIn(key) { hideTip(); var id = key.slice(2); if (S.mrows[id]) { toggleMod(id, 'smod'); } document.body.dataset.segsmod = key; }
  
  function clearBay(bi) {
    var cur = bi >= 0 && S.wings[bi] ? S.wingById[S.wings[bi]] : null;
    if (!cur) { return; }
    bumpOp(-(cur.op || 0)); S.wings[bi] = ''; sfx('ui_refit_slot_cleared_fighter'); renderWings(); changed();
  }
  function segClick(key) {
    hideTip();
    var i = key.indexOf(':'), kind = i > 0 ? key.slice(0, i) : key, id = i > 0 ? key.slice(i + 1) : '';
    if (kind === 'w' && slotById(id)) { selectSlot(id); setTab('arm'); }
    else if (kind === 'm' && S.mrows[id]) {
      if (S.modSearch) { S.modSearch = ''; $('modSearch').value = ''; updateModList(); }
      setTab('mods'); flashRow(S.mrows[id], true);
      document.body.dataset.segscroll = String(Math.round($('mods').scrollTop));   
    }
    else if (kind === 'g' && bayCount() > 0) { var bi = parseInt(id, 10); if (bi >= 0 && bi < bayCount()) { setTab('wings'); selectBay(bi); } }
    else if (kind === 'vents' || kind === 'caps') { flashRow($(kind + 'Range').parentElement, false); }
    document.body.dataset.segclick = key;
  }
  function flashRow(row, center) {
    if (!row) { return; }
    if (center && row.scrollIntoView) { row.scrollIntoView({ block: 'center', behavior: 'auto' }); }   
    row.classList.remove('flash'); void row.offsetWidth; row.classList.add('flash');
    setTimeout(function () { row.classList.remove('flash'); }, 1200);
  }
  function flashGroups(msg) {
    S.groupsFlash = msg; renderGroups();
    setTimeout(function () { if (S.groupsFlash === msg) { S.groupsFlash = null; renderGroups(); } }, 2200);
  }
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  var WT_ORDER = { BALLISTIC: 0, ENERGY: 1, MISSILE: 2, LAUNCH_BAY: 3, UNIVERSAL: 4, HYBRID: 5, SYNERGY: 6, COMPOSITE: 7, BUILT_IN: 8, DECORATIVE: 9, SYSTEM: 10, STATION_MODULE: 11 };
  var SZ_ORDER = { SMALL: 0, MEDIUM: 1, LARGE: 2 };
  function matrixRows() {
    if (!S.hull) { return []; }
    
    
    var bw = biw();
    var rows = S.hull.slots.filter(function (s) { return isRowSlot(s, bw); }).map(function (s) {
      var bi = !s.fittable;
      return { id: s.id, s: s, fitted: bi || !!S.fit[s.id], w: bi ? weaponOf(bw[s.id]) : (S.fit[s.id] ? weaponOf(S.fit[s.id]) : null), builtin: bi };
    });
    
    
    
    rows.sort(function (a, b) { return slotFacingCompare(a.s, b.s); });
    return rows;
  }
  function isRowSlot(s, bw) { return s.fittable || !!(bw[s.id] && !s.hidden && !s.decorative && !s.system && !s.launchBay && !s.stationModule); }
  
  function facingAngle(s) { return ((((s.angle || 0) % 360) + 540) % 360) - 180; }   
  function facingWord(s) { var a = Math.abs(facingAngle(s)); return a <= 45 ? 'forward' : a >= 135 ? 'the rear' : facingAngle(s) > 0 ? 'left' : 'right'; }
  
  
  
  
  
  
  
  
  var ROW_SPAN = 14;
  function idCmp(a, b) { return a.id < b.id ? -1 : a.id > b.id ? 1 : 0; }
  function rowRank(hull) {
    if (hull._rowRank) { return hull._rowRank; }
    var bw = biw(), s = hull.slots.filter(function (m) { return isRowSlot(m, bw); }).sort(function (a, b) { return ((b.x || 0) - (a.x || 0)) || ((b.y || 0) - (a.y || 0)) || idCmp(a, b); });
    function split(items) {
      if (items.length < 2 || (items[0].x || 0) - (items[items.length - 1].x || 0) <= ROW_SPAN) { return [items]; }
      var at = 1, best = -1;
      for (var i = 1; i < items.length; i++) { var g = (items[i - 1].x || 0) - (items[i].x || 0); if (g > best) { best = g; at = i; } }
      return split(items.slice(0, at)).concat(split(items.slice(at)));
    }
    var rank = {}, n = 0;
    split(s).forEach(function (row) {
      row.sort(function (a, b) { return ((b.y || 0) - (a.y || 0)) || ((b.x || 0) - (a.x || 0)) || idCmp(a, b); });
      row.forEach(function (m) { rank[m.id] = n++; });
    });
    hull._rowRank = rank;
    return rank;
  }
  function slotFacingCompare(a, b) {
    var r = S.hull ? rowRank(S.hull) : {}, ra = r[a.id] === undefined ? Infinity : r[a.id], rb = r[b.id] === undefined ? Infinity : r[b.id];
    return (ra === rb ? 0 : ra < rb ? -1 : 1) || idCmp(a, b);
  }
  function groupRows() { return matrixRows().filter(function (r) { return r.fitted; }); }
  
  
  function groupOfSlot(id) { return isBuiltInSlot(id) ? (S.biGroupOf[id] === 0 ? 0 : S.biGroupOf[id] || 1) : (S.groupOf[id] || 1); }
  function groupMembers(gi) {
    var ids = Object.keys(S.fit).filter(function (id) { return S.fit[id] && (S.groupOf[id] || 1) === gi; });
    Object.keys(biw()).forEach(function (id) { if (groupOfSlot(id) === gi && slotById(id)) { ids.push(id); } });
    return ids;
  }
  function fluxOf(ids) {
    var f = 0;
    ids.forEach(function (id) { var w = weaponOf(S.fit[id] || biw()[id]); if (w && typeof w.flux === 'number') { f += w.flux; } });   
    return Math.floor(f);
  }
  function groupFlux(gi) { return fluxOf(groupMembers(gi)); }
  function weaponsFlux() { return fluxOf(Object.keys(S.fit).filter(function (id) { return !!S.fit[id]; })); }
  
  
  function arcGlyph(s, color) {
    var NS = 'http://www.w3.org/2000/svg', svg = document.createElementNS(NS, 'svg');
    svg.setAttribute('viewBox', '0 0 20 20'); svg.setAttribute('class', 'arc'); svg.setAttribute('aria-hidden', 'true');
    var ring = document.createElementNS(NS, 'circle');
    ring.setAttribute('cx', '10'); ring.setAttribute('cy', '10'); ring.setAttribute('r', '7.5'); ring.setAttribute('fill', 'none');
    ring.setAttribute('stroke', color); ring.setAttribute('stroke-opacity', '0.45');
    svg.appendChild(ring);
    var arc = Math.max(s.arc || 0, 30), r = 9, p = document.createElementNS(NS, 'path');
    if (arc >= 360) { p.setAttribute('d', 'M10,10 m-9,0 a9,9 0 1,0 18,0 a9,9 0 1,0 -18,0'); }
    else {
      var a0 = (-(90 + (s.angle || 0)) - arc / 2) * Math.PI / 180, a1 = (-(90 + (s.angle || 0)) + arc / 2) * Math.PI / 180;
      p.setAttribute('d', 'M10,10 L' + (10 + r * Math.cos(a0)).toFixed(2) + ',' + (10 + r * Math.sin(a0)).toFixed(2)
        + ' A' + r + ',' + r + ' 0 ' + (arc > 180 ? 1 : 0) + ',1 ' + (10 + r * Math.cos(a1)).toFixed(2) + ',' + (10 + r * Math.sin(a1)).toFixed(2) + ' Z');
    }
    p.setAttribute('fill', color); p.setAttribute('fill-opacity', '0.85');
    svg.appendChild(p);
    return svg;
  }
  function setGroup(slotId, gi, sameType) {
    var bi = isBuiltInSlot(slotId), w = weaponOf(bi ? biw()[slotId] : S.fit[slotId]), s = slotById(slotId), moved = 0;
    if (!s || (!bi && !w)) { return; }
    if (bi) {   
      Object.keys(biw()).forEach(function (id) {
        if (id === slotId || (sameType && biw()[id] === biw()[slotId])) { if (groupOfSlot(id) !== gi) { S.biGroupOf[id] = gi; moved++; } }
      });
    }
    else Object.keys(S.fit).forEach(function (id) {
      var o = slotById(id);
      if (id === slotId || (sameType && S.fit[id] === w.id && o && o.type === s.type)) {
        if ((S.groupOf[id] || 1) !== gi) { S.groupOf[id] = gi; moved++; }
      }
    });
    if (!moved) { renderGroups(); return; }   
    sfx('ui_button_pressed');
    renderGroups(); renderArm(); changed();
  }
  function autoAssign() {
    if (!S.hull || !groupRows().length) { sfx('ui_button_disabled_pressed'); return; }
    var btn = $('autoAssign');
    if (btn) { btn.disabled = true; }
    postJson('autogroups', variantDocument()).then(function (r) {
      var groups = (r.groups || []).slice(0, 7), used = {};
      for (var k = 0; k < 7; k++) { S.groups[k] = { mode: 'LINKED', autofire: false }; }
      groups.forEach(function (g, i) {
        S.groups[i] = { mode: g.mode === 'ALTERNATING' ? 'ALTERNATING' : 'LINKED', autofire: !!g.autofire };
        (g.slots || []).forEach(function (id) { if (S.fit[id]) { S.groupOf[id] = i + 1; used[id] = true; } else if (isBuiltInSlot(id)) { S.biGroupOf[id] = i + 1; used[id] = true; } });   
      });
      Object.keys(S.fit).forEach(function (id) { if (S.fit[id] && !used[id]) { S.groupOf[id] = 1; } });   
      Object.keys(biw()).forEach(function (id) { if (!used[id]) { S.biGroupOf[id] = 1; } });
      sfx('ui_button_pressed');
      renderGroups(); renderArm(); changed();
      flashGroups('auto-assigned into ' + groups.length + ' group' + (groups.length === 1 ? '' : 's'));
    }).catch(function (e) { if (btn) { btn.disabled = false; } flashGroups('auto-assign failed: ' + e.message); });
  }
  
  function lightGroup(gi, on) {
    S.hoverGroup = on ? gi : null;
    var members = on ? groupMembers(gi) : [];
    Array.prototype.forEach.call(document.querySelectorAll('#groups tr.gw'), function (tr) { tr.classList.toggle('hi', on && tr.dataset.group === String(gi)); });
    Array.prototype.forEach.call(document.querySelectorAll('#weapons .wrow[data-slot]'), function (row) { row.classList.toggle('ghi', members.indexOf(row.dataset.slot) >= 0); });
    drawStage();
  }
  function hoverMount(id, on) {
    if (on) { if (S.hoverSlot !== id) { S.hoverSlot = id; drawStage(); } }
    else if (S.hoverSlot === id) { S.hoverSlot = null; drawStage(); }
  }
  
  
  
  
  function cursorFor() { return S.tab === 'arm' ? S.loadoutCursor : S.tab === 'groups' ? S.groupsCursor : null; }
  function setCursorSlot(id) { id = id || null; if (S.cursorSlot !== id) { S.cursorSlot = id; drawStage(); } }
  
  function focusGroupRow(id) {
    S.groupsCursor = id;   
    setCursorSlot(cursorFor());   
    var rb = $('groups').querySelector('input[data-slot="' + id.replace(/"/g, '') + '"][data-group="' + (S.groupOf[id] || 1) + '"]');
    if (rb) { rb.focus({ preventScroll: true }); rb.scrollIntoView({ block: 'nearest' }); return; }
    var ge = $('groups').querySelector('tr.ge[data-slot="' + id.replace(/"/g, '') + '"]');   
    if (ge && ge.scrollIntoView) { ge.scrollIntoView({ block: 'nearest' }); }
  }
  
  function savedReturnGroups() { try { return localStorage.getItem('cc_editor_return_groups') !== '0'; } catch (e) { return true; } }
  function setReturnGroups(on) { S.returnGroups = !!on; try { localStorage.setItem('cc_editor_return_groups', S.returnGroups ? '1' : '0'); } catch (e) {   } document.body.dataset.retgroups = S.returnGroups ? '1' : '0'; }
  
  
  function fitSig() { return Object.keys(S.fit).sort().map(function (id) { return id + '=' + S.fit[id]; }).join(','); }
  function cueGroups() {
    var b = $('tabs').querySelector('button[data-tab="groups"]');
    if (!b || b.hidden || b.classList.contains('on')) { return; }   
    b.classList.add('cue');
    b.classList.remove('pulse'); void b.offsetWidth; b.classList.add('pulse');
  }
  function renderGroups() {
    var box = $('groups'), h = S.hull, gi;
    
    var act = document.activeElement, keep = act && act !== document.body && box.contains(act) ? { slot: act.dataset.slot, group: act.dataset.group, auto: act.dataset.auto, gr: act.classList.contains('gr') } : null;   
    clear(box);
    if (!h) { return; }
    var all = matrixRows(), fitted = all.filter(function (r) { return r.fitted; }), empties = all.filter(function (r) { return !r.fitted; });
    var rows = all;   
    var autoBtn = el('button', { id: 'autoAssign', text: 'Auto-assign', title: 'the game sorts every weapon into groups the way the refit\'s Auto-assign does — the groups you made are replaced (Undo brings them back)', on: { click: function () { autoBtn.blur(); autoAssign(); } } });   
    autoBtn.disabled = !fitted.length;
    var head = el('div', { class: 'gHead' }, [
      el('h3', { text: 'Weapon groups' }), autoBtn,
      el('span', { class: 'hint', text: fitted.length ? 'radio: move · shift: every same weapon · right-click: unarm · group row: Linked / Alternating'
        : (all.length ? 'no weapons installed — arm a mount, then group it here' : 'this hull has no weapon mounts'),
        title: 'click a number to move a weapon into that group · shift+click: every mount carrying that weapon on the same mount type follows · click a group row (or Enter on it): Linked ⇄ Alternating · the checkbox row: autofire on when combat starts' })
    ]);
    
    var retBox = el('input', { type: 'checkbox', id: 'returnGroups', on: { change: function (ev) { setReturnGroups(ev.target.checked); } } });
    if (S.returnGroups) { retBox.setAttribute('checked', ''); retBox.checked = true; }
    head.appendChild(el('label', { class: 'ret', title: 'after you equip a mount from here, come back to Groups to keep filling slots (Done / Esc come back too)' }, [retBox, el('span', { text: 'return here after equipping' })]));
    if (S.groupsFlash) { head.appendChild(el('span', { class: 'flash', text: S.groupsFlash })); }
    box.appendChild(head);
    
    var mt = el('table', { class: 'gMatrix' }), thead = el('thead'), tbody = el('tbody');
    var hr = el('tr', {}, [el('th', { class: 'wname', text: 'Weapon', title: 'Weapon name, mount and arc. An empty mount is listed dim — click it to arm it.' })]);
    for (gi = 1; gi <= 7; gi++) { hr.appendChild(el('th', { class: 'g', text: String(gi), title: 'Whether this weapon is assigned to group ' + gi + '. Shift-click puts every mount of this weapon kind into the group at once.' })); }
    thead.appendChild(hr);
    var ar = el('tr', { class: 'auto' }, [el('th', { class: 'wname', text: 'Autofire starts enabled', title: 'Whether each group has autofire turned on when combat starts. A weapon group will not autofire while it is selected.' })]);
    for (gi = 1; gi <= 7; gi++) {
      (function (gi) {
        var grp = S.groups[gi - 1];
        var cb = el('input', { type: 'checkbox', dataset: { auto: String(gi) }, title: 'group ' + gi + ': autofire on when combat starts',
          on: { change: function (ev) { sfx('ui_button_pressed'); grp.autofire = !!ev.target.checked; changed(); } } });
        if (grp.autofire) { cb.setAttribute('checked', ''); }   
        ar.appendChild(el('th', { class: 'g' }, [cb]));
      })(gi);
    }
    thead.appendChild(ar);
    mt.appendChild(thead);
    if (!all.length) { tbody.appendChild(el('tr', { class: 'none' }, [el('td', { class: 'wname', text: 'This hull has no weapon mounts' }), el('td', { class: 'g', colspan: '7' })])); }
    rows.forEach(function (r) {
      var color = slotColor(r.s), mount = sizeShort(r.s.size) + ' ' + typeLabel(r.s.type) + (r.s.hardpoint ? ' hardpoint' : ' turret');
      if (!r.fitted) {
        tbody.appendChild(el('tr', { class: 'ge', dataset: { slot: r.id }, style: 'border-left-color:' + color, title: r.id + ' · ' + mount + ' · arc ' + (r.s.arc || 0) + '° · faces ' + facingWord(r.s) + ' — empty: click to arm it',
          on: { click: function (ev) { if (ev.shiftKey) { return; } sfx('ui_button_pressed'); S.armOrigin = r.id; setTab('arm'); selectSlot(r.id); },   
                contextmenu: function (ev) { ev.preventDefault(); },   
                mousedown: function (ev) { if (ev.button === 0 && ev.shiftKey) { ev.preventDefault(); paintStart('rows'); paintOver(r.id, true); } },
                mouseenter: function (ev) { hoverMount(r.id, true); if (S.paint && S.paint.via === 'rows') { if (!(ev.buttons & 1) || !ev.shiftKey) { paintEnd(); } else { paintOver(r.id); } } }, mouseleave: function () { hoverMount(r.id, false); } } }, [
          el('td', { class: 'wname', colspan: '8' }, [el('div', { class: 'wc' }, [   
            el('div', { class: 'ico sm' }, [el('span', { class: 'mount ' + (r.s.hardpoint ? 'hp' : 'tt'), style: 'border-color:' + color })]),
            el('span', { class: 'nm', text: 'empty · ' + mount.toLowerCase() }),
            arcGlyph(r.s, color)])])]));
        return;
      }
      
      
      var bi = !!r.builtin, cur = groupOfSlot(r.id), name = r.w ? r.w.name : (bi ? biw()[r.id] : S.fit[r.id]);
      if (bi) { mount = sizeShort(r.w ? r.w.size : r.s.size) + ' ' + typeLabel(r.w ? (r.w.mountType || r.w.type) : r.s.type) + (r.s.hardpoint ? ' hardpoint' : ' turret') + ' · built in'; }
      function refuse() { sfx('ui_button_disabled_pressed'); flashGroups(name + ' is built into the hull'); }
      var tr = el('tr', { class: 'gw' + (bi ? ' locked' : ''), dataset: bi ? { slot: r.id, group: String(cur), builtin: biw()[r.id] } : { slot: r.id, group: String(cur) }, style: 'border-left-color:' + color,
        
        on: { mouseenter: function (ev) { hoverMount(r.id, true); if (S.paint && S.paint.via === 'rows' && !bi) { if (!(ev.buttons & 1) || !ev.shiftKey) { paintEnd(); } else { paintOver(r.id); } } }, mouseleave: function () { hoverMount(r.id, false); },
              mousedown: function (ev) { if (ev.button === 0 && ev.shiftKey) { ev.preventDefault(); if (bi) { refuse(); return; } paintStart('rows'); paintOver(r.id, true); } },   
              
              contextmenu: function (ev) { ev.preventDefault(); if (bi) { refuse(); return; } hoverMount(r.id, false); clearMount(r.id); renderGroups(); },
              focusin: function () { hoverMount(r.id, true); }, focusout: function () { hoverMount(r.id, false); } } }, [
        el('td', { class: 'wname', title: r.id + ' · ' + mount + ' · arc ' + (r.s.arc || 0) + '° · faces ' + facingWord(r.s)
          + (r.w && typeof r.w.flux === 'number' ? ' · ' + fmt(r.w.flux) + ' flux/s sustained' : '') + (r.w && r.w.pd ? ' · point defence' : '') + (bi ? ' — built into the hull: never removed or replaced; its group is yours' : ' — click the name: this mount\'s weapon list') }, [el('div', { class: 'wc' }, [
          r.w ? weaponIcon(r.w, 24) : null,
          el('span', { class: 'nm', text: name, style: 'color:' + color, on: { click: function (ev) { if (ev.shiftKey) { return; } if (bi) { refuse(); return; } sfx('ui_button_pressed'); S.armOrigin = r.id; setTab('arm'); selectSlot(r.id); } } }),
          bi ? el('span', { class: 'gchip lock', text: 'built in', title: 'built into the hull — never removed or replaced, no OP' })
            : r.w && typeof r.w.op === 'number' ? el('span', { class: 'op', text: effOp(r.w) + ' OP', title: 'ordnance points this weapon costs on this build: ' + opText(r.w) }) : null,   
          arcGlyph(r.s, color)
        ])])
      ]);
      for (var g = 1; g <= 7; g++) {
        (function (g) {
          var rb = el('input', { type: 'radio', name: 'grp-' + r.id, dataset: { slot: r.id, group: String(g) }, title: name + ' → group ' + g + ' (shift: every ' + name + ' on a ' + typeLabel(r.s.type).toLowerCase() + ' mount)',
            on: { click: function (ev) { S.shiftGroup = !!ev.shiftKey; setTimeout(function () { S.shiftGroup = false; }, 0); },   
                  change: function () { var sh = !!S.shiftGroup; S.shiftGroup = false; setGroup(r.id, g, sh); } } });
          if (cur === g) { rb.setAttribute('checked', ''); }
          tr.appendChild(el('td', { class: 'g' }, [rb]));
        })(g);
      }
      tbody.appendChild(tr);
    });
    mt.appendChild(tbody);
    
    if (S.groupsCursor) { var ctr = tbody.querySelector('tr[data-slot="' + S.groupsCursor.replace(/"/g, '') + '"]'); if (ctr && !ctr.classList.contains('locked')) { ctr.classList.add('cur'); } }   
    box.dataset.gcursor = tbody.querySelector('tr.cur') ? S.groupsCursor : '';
    
    var gt = el('table', { class: 'gTable' });
    gt.appendChild(el('thead', {}, [el('tr', {}, [
      el('th', { text: 'Group', title: 'There are a maximum of 7 possible weapon groups. If a weapon group is left empty, it can still be selected, allowing all of the ship\'s weapons to autofire.' }),
      el('th', { text: 'Fire mode', title: 'Click the row to switch: Linked = every weapon fires together · Alternating = the weapons fire one after another' }),
      el('th', { class: 'num', text: 'Flux', title: 'Flux per second generated by the sustained firing of this group. Burst-fire weapons generate more at first.' })])]));
    var gb = el('tbody');
    for (gi = 1; gi <= 7; gi++) {
      (function (gi) {
        var members = groupMembers(gi), grp = S.groups[gi - 1], empty = !members.length;
        function flip() {
          if (empty) { sfx('ui_button_disabled_pressed'); return; }
          sfx('ui_button_pressed'); grp.mode = grp.mode === 'ALTERNATING' ? 'LINKED' : 'ALTERNATING'; renderGroups(); changed();
        }
        var tr = el('tr', { class: 'gr' + (empty ? ' empty' : ''), dataset: { group: String(gi) }, tabindex: '0',
          title: empty ? 'weapon group ' + gi + ' — empty' : 'weapon group ' + gi + ': ' + members.length + ' weapon' + (members.length > 1 ? 's' : '') + ' — click: ' + (grp.mode === 'ALTERNATING' ? 'Linked' : 'Alternating'),
          on: { click: flip, keydown: function (ev) { if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); flip(); } },
                mouseenter: function () { lightGroup(gi, true); }, mouseleave: function () { lightGroup(gi, false); },
                focus: function () { lightGroup(gi, true); }, blur: function () { lightGroup(gi, false); } } }, [
          el('td', { text: 'Group ' + gi }),
          el('td', { class: 'mode', text: empty ? '---' : (grp.mode === 'ALTERNATING' ? 'Alternating' : 'Linked') }),
          el('td', { class: 'num', text: empty ? '---' : String(groupFlux(gi)) })
        ]);
        gb.appendChild(tr);
      })(gi);
    }
    gt.appendChild(gb);
    
    
    var st = S.stats && !S.stats.error ? S.stats : null;
    var diss = Math.floor(st ? st.fluxDiss : h.fluxDiss), cap = Math.floor(st ? st.fluxCap : h.fluxCap);
    var hasShield = !!(st && st.shield), upkeep = hasShield ? Math.floor(st.shield.upkeep || 0) : 0;
    var wf = weaponsFlux(), net = diss - wf - upkeep, netDown = diss - wf;
    var netTitle = 'flux dissipation − weapons flux' + (hasShield ? ' − shield upkeep' : '') + ' — above zero the ship never overloads firing everything' + (hasShield ? ' with the shield up' : '')
      + (net < 0 && cap > 0 ? '; below zero it overloads in about ' + Math.round(cap / -net) + ' s of sustained fire' : '');
    function statLine(k, v, cls, title, sub) {
      var vs = el('span', { class: 'v' + (cls ? ' ' + cls : ''), text: v });
      if (sub) { vs.appendChild(el('span', { class: 'sub', text: sub })); }
      var ks = el('span', { class: 'k', text: k });
      if (title) { ks.title = title; vs.title = title; }
      return [ks, vs];
    }
    
    
    
    
    var lines = el('div', { class: 'gStats' }, [el('div', { class: 'h', text: 'Flux budget', title: 'the ship\'s flux budget with every fitted weapon firing — the numbers the oracle computes for this loadout' })].concat(
      statLine('Weapons flux / sec', String(wf), '', 'every fitted weapon firing sustained, all groups together (the Flux column summed)'),
      statLine('Dissipation / sec', String(diss), '', 'flux dissipation per second (vents and hullmods included)'),
      statLine('Shield flux / sec', !st ? '…' : hasShield ? String(upkeep) : 'no shield', '', 'the shield\'s upkeep per second while it is up'),
      statLine(hasShield ? 'Net, shields up' : 'Net at full fire', !st ? '…' : (net >= 0 ? '+' : '') + String(net), !st ? '' : (net >= 0 ? 'ok' : 'bad'), netTitle,
        st && net < 0 && cap > 0 ? 'overloads in ~' + Math.round(cap / -net) + ' s' : null),
      
      hasShield ? statLine('Net, shields down', !st ? '…' : (netDown >= 0 ? '+' : '') + String(netDown), !st ? '' : (netDown >= 0 ? 'ok' : 'bad'),
        'flux dissipation − weapons flux, the shield down — above zero the ship never overloads firing everything unshielded' + (netDown < 0 && cap > 0 ? '; below zero it overloads in about ' + Math.round(cap / -netDown) + ' s of sustained fire' : ''),
        st && netDown < 0 && cap > 0 ? 'overloads in ~' + Math.round(cap / -netDown) + ' s' : null) : [],
      
      
      
      statLine('Venting / sec', !st || typeof st.ventRate !== 'number' ? '…' : st.ventRate > 0 ? String(Math.floor(st.ventRate)) : 'cannot vent',
        st && typeof st.ventRate === 'number' && st.ventRate <= 0 ? 'bad' : '',
        'flux shed per second while venting (V): dissipation × 2 × the vent-rate stat (Resistant Flux Conduits +25 %, built in +35 %) — a Safety Overrides ship cannot vent'),
      statLine('Flux capacity', String(cap))));
    var wrap = el('div', { class: 'gMatrixWrap' }, [mt]);
    box.appendChild(el('div', { class: 'gBody' }, [wrap, el('div', { class: 'gRight' }, [gt, lines])]));
    if (keep) {
      var back = keep.gr ? box.querySelector('tr.gr[data-group="' + keep.group + '"]')
        : keep.auto ? box.querySelector('input[data-auto="' + keep.auto + '"]') : (keep.slot && keep.group ? box.querySelector('input[data-slot="' + keep.slot + '"][data-group="' + keep.group + '"]') : null);
      if (back) { back.focus({ preventScroll: true }); }
    }
    if (S.hoverGroup) { lightGroup(S.hoverGroup, true); }   
    
    
    
    function publish() {
      var stage = $('stage');
      box.dataset.rows = String(rows.length); box.dataset.fitted = String(fitted.length); box.dataset.mounts = String(all.length);
      box.dataset.gscroll = String(Math.max(0, box.scrollHeight - box.clientHeight));
      box.dataset.gxscroll = String(Math.max(0, box.scrollWidth - box.clientWidth));   
      box.dataset.retgroups = S.returnGroups ? '1' : '0';
      box.dataset.gh = String(box.scrollHeight) + '/' + String(box.clientHeight) + '@' + String(window.innerWidth) + 'x' + String(window.innerHeight);   
      if (stage) { box.dataset.sscroll = String(Math.max(0, stage.scrollHeight - stage.clientHeight)); }
      
      
      var wrapped = 0;
      Array.prototype.forEach.call(box.querySelectorAll('.gStats .k'), function (k) {
        var lh = parseFloat(getComputedStyle(k).lineHeight) || parseFloat(getComputedStyle(k).fontSize) * 1.3 || 18, r = document.createRange();
        r.selectNodeContents(k);   
        if (r.getBoundingClientRect().height > 1.6 * lh) { wrapped++; }
      });
      box.dataset.gstatsWrapped = String(wrapped);
      
      var clipped = 0;
      Array.prototype.forEach.call(box.querySelectorAll('.gStats .k'), function (k) { if (k.scrollWidth > k.clientWidth) { clipped++; } });
      box.dataset.gstatsClipped = String(clipped);
    }
    publish(); setTimeout(publish, 250);
  }

  
  
  
  
  
  function changed(hold) {
    
    
    var sig = fitSig();
    if (S.fitSig != null && sig !== S.fitSig && S.tab !== 'groups') { cueGroups(); }
    S.fitSig = sig;
    
    if (S.hull) {
      var snap = stateSnapshot();
      S.drafts[S.hull.id] = { snap: snap, dirty: snap !== (S.clean[S.hull.id] || '') };
      refreshDraftDots();
    }
    undoTrack();   
    if (S.timer) { clearTimeout(S.timer); }
    S.timer = setTimeout(refresh, hold === true ? 120 : 0);
    S.opStale = true;   
    renderBudget();
    
    
    
    var vd = $('verdict');
    if (!S.hull) { vd.textContent = ''; vd.className = 'verdict'; }
    else if (!S.verdict) { vd.textContent = 'checking…'; vd.className = 'verdict wait'; }
    else { vd.classList.add('rechecking'); }
    S.barHs = [$('bar').offsetHeight]; document.body.dataset.barh = String(S.barHs[0]);
    $('save').disabled = true; $('fly').disabled = true; $('svprm').disabled = true;   
  }
  function refresh() {
    S.timer = null;
    if (!S.hull) { return; }
    var seq = ++S.seq;
    var doc = variantDocument();
    document.body.dataset.status = 'pending';
    var t0 = performance.now();   
    var at = { hull: S.hull.id, vents: S.vents, caps: S.caps };   
    Promise.all([
      postJson('validate?tier=' + S.tier + '&allSkills=1', doc),
      postJson('stats?allSkills=1&cr=' + S.cr + '&skills=' + (S.skills ? 1 : 0), doc).catch(function (e) { return { error: e.message }; })   
    ]).then(function (r) {
      if (seq !== S.seq) { return; }
      var t1 = performance.now(); document.body.dataset.rt = (t1 - t0).toFixed(1);
      requestAnimationFrame(function () { document.body.dataset.paint = (performance.now() - t1).toFixed(1); });
      S.verdict = r[0]; S.stats = r[1]; S.statsAt = at; S.opLocal = 0; S.opStale = false;   
      
      
      
      if (S.verdict.applicability === 'game') { S.gate = S.verdict.mods || {}; S.gatePolls = 0; }
      else if (S.verdict.applicability === 'pending') {
        S.gatePolls = (S.gatePolls || 0) + 1;
        if (S.gateTimer) { clearTimeout(S.gateTimer); }
        S.gateTimer = setTimeout(function () { S.gateTimer = null; if (seq === S.seq) { refresh(); } }, S.gatePolls <= 40 ? 300 : 2000);
      }
      renderVerdict(); renderBudget(); renderShip(); updateModList();
      if (S.armFree !== opFree()) { renderArm(); } else { refreshAmmoCells(); }   
      
      
      
      
      if (S.stats && !S.stats.error && typeof S.stats.bays === 'number' && S.statsAt.hull === S.hull.id) {
        var freeNow = Math.max(0, bayCount() - (S.hull.builtInWings || []).length), shed = 0;
        for (var sbi = freeNow; sbi < S.wings.length; sbi++) { var swg = S.wings[sbi] ? S.wingById[S.wings[sbi]] : null; if (swg) { bumpOp(-(swg.op || 0)); shed++; } }
        if (S.wings.length > freeNow) { S.wings.length = freeNow; }
        if (shed > 0) {
          document.body.dataset.shedwings = String(S.shedWings = (S.shedWings || 0) + shed);
          flashHint(shed + ' fighter wing' + (shed > 1 ? 's' : '') + ' removed — ' + (shed > 1 ? 'their bays are' : 'its bay is') + ' gone'); sfx('ui_refit_slot_cleared_fighter');
          renderWings(); drawStage(); changed();
        }
      }
      if (bayCount() !== S.baysShown) { renderWings(); drawStage(); }   
      document.body.dataset.status = !S.verdict.ok ? 'bad' : S.verdict.applicability === 'pending' ? 'pending' : 'ok';   
    }).catch(function (e) {
      if (seq !== S.seq) { return; }
      $('verdict').textContent = 'validate failed: ' + e.message; $('verdict').className = 'verdict bad';
      document.body.dataset.status = 'error';
    });
  }
  function renderVerdict() {
    var v = S.verdict, vd = $('verdict'), banner = $('banner'), list = clear($('problems'));
    if (!v) { vd.textContent = ''; vd.className = 'verdict'; banner.hidden = true; $('save').disabled = true; $('svprm').disabled = true; return; }
    var problems = v.problems || [];
    $('bytes').textContent = v.bytes ? 'wire ' + v.bytes.body + ' / ' + v.bytes.cap + ' B' : '';
    $('bytesInfo').hidden = !v.bytes;   
    if (v.ok && v.applicability === 'pending') {
      
      
      vd.textContent = 'valid so far — asking the game…'; vd.className = 'verdict wait';   
      banner.hidden = true;
    } else if (v.ok) {
      vd.textContent = 'valid — the game accepts this variant'; vd.className = 'verdict ok';
      banner.hidden = true;
    } else {
      vd.textContent = problems.length + ' problem' + (problems.length === 1 ? '' : 's'); vd.className = 'verdict bad';
      var first = problems[0];
      
      $('bannerText').textContent = first ? ((first.subject ? first.subject + ': ' : '') + first.detail) : 'the game refuses this variant';
      if (S.bannerProblems !== problems.length) { list.hidden = true; S.bannerProblems = problems.length; }
      $('bannerMore').hidden = problems.length < 2;
      $('bannerMore').textContent = list.hidden ? ('+' + (problems.length - 1) + ' more') : 'hide';
      problems.forEach(function (p) { list.appendChild(el('li', {}, [p.subject ? el('span', { class: 'who', text: p.subject }) : null, p.detail])); });
      banner.hidden = false;
    }
    $('save').disabled = !v.ok;
    $('fly').disabled = $('save').disabled;   
    $('svprm').disabled = $('save').disabled;   
    if (S.barHs) { S.barHs.push($('bar').offsetHeight); document.body.dataset.barh = S.barHs.join(','); }   
  }

  
  function save() {
    if (!S.hull || !S.verdict || !S.verdict.ok) { return; }
    var btn = $('save'), out = $('saveResult');
    btn.disabled = true; out.textContent = 'saving…';
    postJson('save', variantDocument()).then(function (r) {
      
      
      var isCur = r.saved && r.current === r.id;
      out.textContent = r.saved
        ? ('saved as ' + r.id + (r.overwritten ? ' (replaced)' : '') + ' — ' + (r.autoSelected ? 'your next spawn (nothing was current)' : isCur ? 'your next spawn' : 'Fly makes it your next spawn')
            + (S.session === 'live' ? '; Submit is the live respawn' : '') + stockNote())
        : ('not saved: ' + (r.error || 'refused'));
      btn.disabled = false;
      if (r.saved) {
        if (S.hull) { S.clean[S.hull.id] = stateSnapshot(); S.drafts[S.hull.id] = { snap: S.clean[S.hull.id], dirty: false }; refreshDraftDots(); }   
        loadSavedList(); repingSoon(); syncPromoteRow();   
      }
    }).catch(function (e) { out.textContent = 'save failed: ' + e.message; btn.disabled = false; });
  }
  
  function stockNote() {
    if (S.noSubs) { return ' — note: this server takes no custom variants (custom submissions off)'; }
    if (S.nextVerdict && S.nextVerdict.state === 'rejected') { return ' — note: ' + String(S.nextVerdict.line || ''); }
    return '';
  }
  
  
  
  
  function flyLine(r, id) {
    if (!r.flown) { return { text: 'not flown: ' + (r.error || 'refused'), cls: 'err' }; }
    var label = nextSpawnLabel(String(r.current || id)), copy = r.stem ? ' (saved as local build ' + r.stem + ')' : '';
    if (r.replyState === 'answered' && r.reply) { return { text: String(r.reply) + copy, cls: /^next respawn/.test(String(r.reply)) ? 'ok' : 'err' }; }
    if (r.replyState === 'sent') { return { text: 'sent /ship ' + id + ' — no reply yet; the game’s chat shows it when it lands' + copy, cls: '' }; }
    if (r.replyState === 'not-sent') { return { text: (r.error || 'not sent') + ' — ' + label + ' flies at your next connect' + copy, cls: 'err' }; }
    if (r.replyState === 'submitted' && r.submit) {
      var s = r.submit;
      if (!s.submitted) { return { text: 'not submitted: ' + (s.message || s.error || 'refused') + ' — ' + label + ' flies at your next connect' + copy, cls: 'err' }; }
      if (!s.answered) { return { text: 'sent — the server has not answered yet; the game’s chat shows the verdict when it lands' + copy, cls: '' }; }
      if (s.accepted) { return { text: 'next respawn: ' + s.id + copy, cls: 'ok' }; }
      return { text: 'rejected (' + s.reasonToken + ')' + (s.hint ? ': ' + s.hint : '') + ' — ' + label + ' flies at your next connect' + copy, cls: 'err' };
    }
    return { text: 'next spawn: ' + label + (S.session === 'live' ? ' (flies at your next connect)' : '') + copy + (r.stem || r.kind !== 'roster' ? stockNote() : ''), cls: '' };
  }
  
  
  function noteFlyLine(card, id, line) { S.flyLine = card ? { card: card, id: id, text: line.text, cls: line.cls } : null; }
  function flyReplyEl(card, id) { var f = S.flyLine; return f && f.card === card && f.id === id ? el('div', { class: 'reply' + (f.cls ? ' ' + f.cls : ''), text: f.text, title: f.text }) : null; }
  
  
  
  function flyBuild(id, out, card, keepOnly) {
    return postJson('saved/' + encodeURIComponent(id) + '/fly', keepOnly ? { keepOnly: true } : {}).then(function (r) {
      var line = flyLine(r, id);
      noteFlyLine(card, id, line);
      if (out) { out.textContent = line.text; }
      loadSavedList(); repingSoon();
      return r;   
    }).catch(function (e) { noteFlyLine(card, id, { text: 'fly failed: ' + e.message, cls: 'err' }); if (out) { out.textContent = 'fly failed: ' + e.message; } if (card) { renderSaved(); } });
  }
  
  
  
  function flyServerBuild(id) {
    return postJson('server/' + encodeURIComponent(id) + '/fly', {}).then(function (r) {
      noteFlyLine('server', id, flyLine(r, id));
      return loadSavedList().then(function () { return loadServerList(); }).then(function () { repingSoon(); });
    }).catch(function (e) { noteFlyLine('server', id, { text: 'fly failed: ' + e.message, cls: 'err' }); renderServer(); });
  }
  function saveAndFly() {
    if (!S.hull || !S.verdict || !S.verdict.ok) { return; }
    var btn = $('fly'), out = $('saveResult');
    btn.disabled = true; out.textContent = 'saving…';
    postJson('save', variantDocument()).then(function (r) {
      btn.disabled = false;
      if (!r.saved) { out.textContent = 'not saved: ' + (r.error || 'refused'); return null; }
      return flyBuild(r.id, out);
    }).catch(function (e) { out.textContent = 'save failed: ' + e.message; btn.disabled = false; });
  }
  
  
  
  
  
  
  function saveAndPromote() {
    if (!S.hull || !S.verdict || !S.verdict.ok || !S.modCatalog) { return; }
    var btn = $('svprm'), out = $('saveResult'), doc = variantDocument();
    btn.disabled = true; out.textContent = 'saving…';
    postJson('save', doc).then(function (r) {
      if (!r.saved) { btn.disabled = false; out.textContent = 'not saved: ' + (r.error || 'refused'); return null; }
      if (S.hull) { S.clean[S.hull.id] = stateSnapshot(); S.drafts[S.hull.id] = { snap: S.clean[S.hull.id], dirty: false }; refreshDraftDots(); }
      loadSavedList(); repingSoon(); syncPromoteRow();
      out.textContent = 'saved as ' + r.id + ' — promoting…';
      return postJson('catalog/promote', doc).then(function (res) {
        btn.disabled = false;
        out.textContent = res && res.ok ? promotedLine(res.id) : 'saved as ' + r.id + '; promotion refused: ' + ((res && res.error) || '?');
        return loadModCatalog();
      }, function (e) { btn.disabled = false; out.textContent = 'saved as ' + r.id + '; promote failed: ' + e.message; });
    }).catch(function (e) { out.textContent = 'save failed: ' + e.message; btn.disabled = false; });
  }
  
  
  
  function promotedLine(id) {
    var shipped = !!(S.modCatalog && (S.modCatalog.mods || []).some(function (m) { return m.id === id; }));
    return shipped ? 'promoted: ' + id + ' — a shipped variant: the serve keeps its boot body, the update ships at the next roll'
      : 'promoted: ' + id + ' — live on /ship of an armed serve now (a changed body flies at your next respawn; the serve chats its verdict); in the mod at the next roll';
  }
  
  function repingSoon() { getJson('api/ping').then(showPing).catch(function () {}); }
  function nextSpawnLabel(next, nameOnly) {   
    if (!next) { return ''; }
    var rows = (S.saved || []).concat(S.server || [], S.peers || []);   
    for (var i = 0; i < rows.length; i++) { if (rows[i].id === next && rows[i].displayName) { return nameOnly ? rows[i].displayName : rows[i].displayName + ' (' + next + ')'; } }
    return next;
  }
  
  
  
  
  
  
  
  
  
  
  var SFX_VOL = { ui_refit_slot_cleared_fighter: 0.2, ui_refit_slot_filled: 0.2, ui_refit_slot_cleared: 0.25,   
    ui_button_pressed: 0.6, ui_button_disabled_pressed: 0.6, ui_button_mouseover: 0.25, ui_char_spent_story_point_technology: 0.4 };
  var SFX_IDS = ['ui_button_pressed', 'ui_button_disabled_pressed', 'ui_button_mouseover',
    'ui_char_spent_story_point_technology', 'ui_refit_slot_filled_fighter', 'ui_refit_slot_cleared_fighter'];
  ['small', 'medium', 'large'].forEach(function (z) {
    SFX_IDS.push('ui_refit_slot_cleared_' + z);
    ['ballistic', 'energy', 'missile'].forEach(function (t) { SFX_IDS.push('ui_refit_slot_filled_' + t + '_' + z); });
  });
  function sfxVolume(id) {
    var k = Object.keys(SFX_VOL).filter(function (p) { return id.indexOf(p) === 0; })[0];
    return (k ? SFX_VOL[k] : 0.3) * ((typeof S.sfxVol === 'number' ? S.sfxVol : 100) / 100);   
  }
  
  
  
  function savedSfxVol() { try { var v = parseInt(localStorage.getItem('cc_editor_sfxvol'), 10); return isNaN(v) ? 100 : Math.max(0, Math.min(100, v)); } catch (e) { return 100; } }
  function setSfxVol(pct, view) {
    var p = parseInt(pct, 10); S.sfxVol = isNaN(p) ? 100 : Math.max(0, Math.min(100, p));
    if (!view) { try { localStorage.setItem('cc_editor_sfxvol', String(S.sfxVol)); } catch (e) {   } }
    document.body.dataset.sfxvol = String(S.sfxVol);
    var hv = $('sfxVolHdr'); if (hv) { hv.value = String(S.sfxVol); hv.title = 'refit cue volume: ' + S.sfxVol + ' % — remembered on this machine (Settings has it too)'; }   
    var cv = $('sfxVol'), cn = $('sfxVolN'); if (cv) { cv.value = String(S.sfxVol); } if (cn) { cn.textContent = S.sfxVol + ' %'; }   
    Object.keys(S.audio).forEach(function (id) { S.audio[id].volume = sfxVolume(id); });
    if (window.ccRender) { window.ccRender.sync(); }
  }
  function sfxAudio(id) {
    var a = S.audio[id];
    if (!a) { a = new Audio(base + 'sound/' + encodeURIComponent(id)); a.preload = 'auto'; a.volume = sfxVolume(id); S.audio[id] = a; }
    return a;
  }
  function sfx(id, hover) {
    if (S.mute || !id) { return; }
    
    
    document.body.dataset.sfxn = String(S.sfxN = (S.sfxN || 0) + 1);
    if (!hover) { S.sfxPressAt = performance.now(); S.sfxGesture = S.gestureN; }
    try {
      var a = sfxAudio(id);
      a.currentTime = 0;
      var p = a.play();
      if (p && p.catch) { p.catch(function () {}); }   
    } catch (e) {   }
  }
  function sfxPreload() { if (!S.mute) { SFX_IDS.forEach(sfxAudio); } }
  
  
  
  
  
  
  function cueHover() {
    if (S.mute || !S.hoverTicks) { return; }
    if (performance.now() - (S.cueHoverAt || -1e9) < 50) { return; }
    S.cueHoverAt = performance.now(); sfx('ui_button_mouseover', true);
    document.body.dataset.sfxhover = String(S.sfxHoverN = (S.sfxHoverN || 0) + 1);
  }
  function savedHoverTicks() { try { return localStorage.getItem('cc_editor_hovertick') === '1'; } catch (e) { return false; } }   
  function setHoverTicks(on, view) {
    S.hoverTicks = !!on;
    if (!view) { try { localStorage.setItem('cc_editor_hovertick', S.hoverTicks ? '1' : '0'); } catch (e) {   } }
    document.body.dataset.hovertick = S.hoverTicks ? '1' : '0';
    var cb = $('hoverTick'); if (cb) { cb.checked = S.hoverTicks; }
  }
  function sfxFill(w, s) {   
    var t = ({ BALLISTIC: 'ballistic', ENERGY: 'energy', MISSILE: 'missile' })[(w && w.type) || (s && s.type)] || 'ballistic';
    var z = String((w && w.size) || (s && s.size) || 'small').toLowerCase();
    sfx('ui_refit_slot_filled_' + t + '_' + (/^(small|medium|large)$/.test(z) ? z : 'small'));
  }
  function sfxCleared(s) { var z = String(s && s.size || 'small').toLowerCase(); sfx('ui_refit_slot_cleared_' + (/^(small|medium|large)$/.test(z) ? z : 'small')); }
  function setMute(m) {
    S.mute = !!m;
    try { localStorage.setItem('cc_editor_mute', S.mute ? '1' : '0'); } catch (e) {   }
    var b = $('sfx');
    if (b) { b.textContent = S.mute ? 'Sound: off' : 'Sound: on'; b.title = S.mute ? 'refit sounds are muted — click to unmute' : 'refit sounds play on clicks and equips (hover ticks: Settings) — click to mute'; }
    var cb = $('sfxOn'); if (cb) { cb.checked = !S.mute; }   
    if (!S.mute) { sfxPreload(); }   
  }
  
  
  
  function savedMute() { try { return localStorage.getItem('cc_editor_mute') === '1'; } catch (e) { return false; } }
  
  
  
  
  function savedSkills() { try { return localStorage.getItem('cc_editor_skills') === '1'; } catch (e) { return false; } }
  function setSkills(on) {
    S.skills = !!on;
    try { localStorage.setItem('cc_editor_skills', S.skills ? '1' : '0'); } catch (e) {   }
    document.body.dataset.skills = S.skills ? '1' : '0';
    renderBudget();
    if (!S.hull) { return; }
    if (S.timer) { clearTimeout(S.timer); }
    S.timer = setTimeout(refresh, 0);
  }

  
  
  
  
  
  function loadSavedList() {
    return getJson('saved').then(function (r) { S.saved = r.saved || []; if (r.current !== undefined) { S.currentBuild = String(r.current || ''); } renderSaved(); if (S.serverConnected) { renderServer(false); } })   
      .catch(function () { S.saved = []; renderSaved(); });
  }
  
  
  
  
  
  function loadServerList() {
    return getJson('server').then(function (r) {
      S.server = r.server || []; S.peers = r.peers || []; S.serverConnected = !!r.connected; S.serverStamp = r.stamp || 0; S.serverDefault = String(r.defaultId || '');
      renderServer(false);
    }).catch(function () { S.server = []; S.peers = []; S.serverConnected = false; renderServer(true); });
  }
  
  
  
  
  
  
  
  function serverRow(box, r, peer) {
    var hull = r.hullId ? (S.catalog.hulls.filter(function (h) { return h.id === r.hullId; })[0] || null) : null;
    var ok = !!r.loadable && !!hull && !!hull.allowed, why = r.why || (hull && !hull.allowed ? 'hull not allowed at this tier' : 'not loadable here');
    var cur = !!r.id && (r.id === S.currentBuild || (!!r.stem && r.stem === S.currentBuild));   
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    var hostKind = peer ? '' : hostGroupOf(r.id), onCard = hostKind === 'listed' || hostKind === 'kept' || hostKind === 'hidden';
    var hostTip = onCard ? '; change it, then Promote replaces it for every player' + (hostKind === 'hidden' ? '; unchanged, Promote puts it back' : '')
      : hostKind === 'removed' ? '; it was removed from /ship: change it, then Promote replaces the ship that took its place (or adds it if none did); unchanged, Promote puts it back' : '';
    var btn = el('button', { text: 'Load', title: ok ? 'load this server build as a draft — Save keeps it as a local build' + hostTip : why, on: { click: function () {
      sfx('ui_button_pressed');
      var g = peer ? '' : hostGroupOf(r.id), on = !peer && g !== 'gone';
      loadServerBuild(r.id, on ? 'host' : undefined, on ? g : undefined);
    } } });
    btn.disabled = !ok;
    box.appendChild(el('div', { class: 'srow' + (ok ? '' : ' off') + (cur ? ' cur' : ''), dataset: { id: r.id, loadable: ok ? '1' : '0', current: cur ? '1' : '0' }, title: r.id + (ok ? '' : ' — ' + why) + (cur ? ' — your next spawn' : '') }, [
      el('div', { class: 'who' }, [
        el('span', { class: 'name', text: r.displayName || r.id }),
        el('span', { class: 'meta', text: (hull ? hull.name : (r.hullName || r.hullId || '')) + (typeof r.op === 'number' ? ' · ' + r.op + ' OP' : '') }),
        cur ? el('span', { class: 'badge cur', text: 'NEXT SPAWN', title: S.session === 'live' ? 'your next spawn (Fly told the server; a build picked at the title rode your connect)' : 'this build flies when you next select Multiplayer Test' }) : null
      ]),
      
      el('div', { class: 'acts' }, [
        cur ? null : el('button', { class: 'fly', text: 'Fly', title: peer ? 'save a local copy of this player\'s build and fly it at your next respawn (submitted while connected)' : 'fly this server build at your next respawn (while connected: /ship, the server\'s reply lands here)', on: { click: function () { var b = this; b.disabled = true; sfx('ui_button_pressed'); flyServerBuild(r.id).then(function () { b.disabled = false; }); } } }),   
        btn,
        r.id === S.serverDefault ? el('span', { class: 'badge dflt', text: 'DEFAULT', title: 'the server\'s default spawn' }) : null]),
      flyReplyEl('server', r.id)   
    ]));
  }
  function renderServer(unavailable) {
    var box = clear($('server')), rows = S.server || [], peers = S.peers || [], total = rows.length + peers.length;
    $('serverBox').hidden = !!unavailable;
    if (unavailable) { return; }
    $('serverN').textContent = total ? String(total) : '';
    $('serverNote').textContent = !S.serverConnected ? 'connect through the join window to list them' : (S.serverStamp ? 'from your last connection' : '');
    if (!total) { box.appendChild(el('div', { class: 'empty', text: S.serverConnected ? 'The server offered no builds.' : 'Connect through the join window — its ship list lands here.' })); return; }
    rows.forEach(function (r) { serverRow(box, r); });
    if (peers.length) {
      box.appendChild(el('h4', { class: 'ghead', text: 'From players this session (' + peers.length + ')', title: 'custom ships other players got accepted this match — Load, then Save keeps a copy as your own local build (names are theirs)' }));
      peers.forEach(function (r) { serverRow(box, r, true); });
    }
  }
  
  
  
  
  function loadServerBuild(id, from, kind) {
    var out = $('saveResult'), what = from === 'catalog' ? 'mod variant' : 'server build';
    out.textContent = 'loading…';
    getJson((from === 'catalog' ? 'catalog/' : 'server/') + encodeURIComponent(id)).then(function (doc) {
      return pickHull(doc.hullId, { noDraft: true }).then(function () {
        S.loadedFrom = from === 'host' && S.hull && S.hull.id === doc.hullId ? { id: id, kind: String(kind || ''), hullId: doc.hullId } : null;
        S.loadedSnap = null; S.hostAdopt = null;   
        if (S.hull && S.hull.id === doc.hullId) { applyDocument(doc); S.loadedSnap = S.loadedFrom ? stateSnapshot() : null; out.textContent = 'loaded ' + what + ' ' + id + ' as a draft — Save keeps it as a local build'; }
        else { out.textContent = 'cannot load ' + id + ': hull ' + doc.hullId + ' is not available here'; }
      });
    }).catch(function (e) { out.textContent = 'load failed: ' + e.message; });
  }
  function renderSaved() {
    var box = clear($('saved')), rows = S.saved || [], curId = S.currentBuild || '';
    $('savedBox').hidden = false;
    $('savedN').textContent = rows.length ? String(rows.length) : '';
    
    
    var curEl = $('savedCurrent'); curEl.textContent = curId ? 'next spawn: ' + nextSpawnLabel(curId, true) : ''; curEl.title = curId ? nextSpawnLabel(curId) : '';
    if (!rows.length) { box.appendChild(el('div', { class: 'empty', text: 'Nothing saved yet — Save keeps a build here.' })); return; }
    rows.forEach(function (r) {
      var hull = r.hullId ? (S.catalog.hulls.filter(function (h) { return h.id === r.hullId; })[0] || null) : null, cur = r.id === curId;
      
      
      box.appendChild(el('div', { class: 'srow' + (r.error ? ' bad' : '') + (cur ? ' cur' : ''), dataset: { id: r.id, current: cur ? '1' : '0' }, title: r.id + (cur ? ' — your next spawn' : '') }, [
        el('div', { class: 'who' }, [
          el('span', { class: 'name', text: r.error ? r.id : (r.displayName || r.id) }),
          el('span', { class: 'meta', text: r.error ? 'unreadable file' : (hull ? hull.name : (r.hullId || '')) }),
          
          
          cur ? el('span', { class: 'badge cur', text: 'NEXT SPAWN', title: S.session === 'live' ? 'your next spawn (Fly submitted it; a build picked at the title rode your connect)' : 'this build flies when you next select Multiplayer Test' }) : null
        ]),
        el('div', { class: 'acts' }, [
          cur || r.error ? null : el('button', { class: 'fly', text: 'Fly', title: S.session === 'live' ? 'send this build to the server — it flies at your next respawn' : 'make this build your next spawn', on: { click: function () { var b = this; b.disabled = true; sfx('ui_button_pressed'); flyBuild(r.id, null, 'saved').then(function () { b.disabled = false; }); } } }),
          r.error ? null : el('button', { text: 'Load', title: 'load this build into the editor', on: { click: function () { sfx('ui_button_pressed'); loadBuild(r.id); } } }),
          el('button', { class: 'danger', text: 'Delete', title: 'delete the saved file', on: { click: function () { sfx('ui_button_pressed'); deleteBuild(r); } } })
        ]),
        flyReplyEl('saved', r.id)   
      ]));
    });
  }
  
  
  
  
  
  
  function syncPromoteRow() {
    var row = $('catalog').querySelector('.srow.promote[data-promote="current"]'); if (!row) { return; }
    row.dataset.stem = S.hull ? variantDocument().variantId : '';
    var name = row.querySelector('.name'), meta = row.querySelector('.meta');
    if (name) { name.textContent = S.hull ? (S.name || 'this build') : 'pick a hull first'; }
    if (meta) { meta.textContent = S.hull ? S.hull.name : ''; }
  }
  function loadModCatalog() {
    return getJson('catalog').then(function (r) { S.modCatalog = r && r.armed ? r : null; S.catalogKnown = true; renderModCatalog(); })
      .catch(function () { S.modCatalog = null; S.catalogKnown = true; renderModCatalog(); });   
  }
  function renderModCatalog() {
    var r = S.modCatalog, box = clear($('catalog')), out = $('saveResult');
    document.body.dataset.catalog = r ? String((r.mods || []).length) : 'off';
    $('svprm').hidden = !r;   
    var hostFace = renderHostCard();   
    if (!r) { if (!hostFace) { $('catalogBox').hidden = true; } return; }
    
    
    var firstReveal = $('catalogBox').hidden, mods = r.mods || [], pend = r.pending || [], listed = {};
    $('catalogBox').hidden = false;
    if (pend.length && (firstReveal || !(S.catalogPend > 0))) { $('catalogBox').open = true; }
    S.catalogPend = pend.length;
    mods.forEach(function (m) { listed[m.id] = true; });
    $('catalogN').textContent = mods.length ? String(mods.length) : '';
    $('catalogNote').textContent = pend.length ? pend.length + ' pending — applied at the next roll' : '';
    document.body.dataset.catalogPending = String(pend.length);
    
    var rejectOf = {};
    pend.forEach(function (p) { if (p.reject) { rejectOf[p.id] = p.reject; } });
    function rejectLine(id) { return rejectOf[id] ? el('div', { class: 'why', text: 'rejected at the last roll: ' + rejectOf[id] }) : null; }
    
    function refresh(res, verb, done) {
      out.textContent = res && res.ok ? (done || (verb === 'promotion' ? promotedLine(res.id) : verb === 'demotion' ? 'demoted: ' + res.id + ' — off /ship of an armed serve now; out of the mod at the next roll' : verb + ' marked: ' + res.id + ' (in the mod at the next roll)')) : 'refused: ' + ((res && res.error) || '?');
      return loadModCatalog();
    }
    function undoBtn(id, what) {
      return el('button', { text: 'Undo', title: 'withdraw this pending ' + what + ' — the next roll leaves the mod as it is', on: { click: function () {
        sfx('ui_button_pressed'); out.textContent = 'undoing…';
        postJson('catalog/' + encodeURIComponent(id) + '/cancel', {}).then(function (res) { return refresh(res, 'undo', 'undone: ' + id + ' (no longer pending)'); }).catch(function (e) { out.textContent = 'undo failed: ' + e.message; });
      } } });
    }
    
    
    box.appendChild(el('div', { class: 'srow promote', dataset: { promote: 'current', stem: S.hull ? variantDocument().variantId : '' } }, [
      el('div', { class: 'who' }, [el('span', { class: 'name', text: S.hull ? (S.name || 'this build') : 'pick a hull first' }), el('span', { class: 'meta', text: S.hull ? S.hull.name : '' })]),
      el('div', { class: 'acts' }, [el('button', { class: 'primary', text: 'Promote this build', title: 'write this build as a promotion marker — the next roll validates it and ships it in the mod catalog', on: { click: function () {
        if (!S.hull) { sfx('ui_button_disabled_pressed'); return; }
        sfx('ui_button_pressed'); out.textContent = 'promoting…';
        postJson('catalog/promote', variantDocument()).then(function (res) { return refresh(res, 'promotion'); }).catch(function (e) { out.textContent = 'promote failed: ' + e.message; });
      } } })])
    ]));
    
    pend.filter(function (p) { return p.kind === 'promote' && !listed[p.id]; }).forEach(function (p) {
      box.appendChild(el('div', { class: 'srow promote', dataset: { id: p.id, pending: 'promote' }, title: p.id }, [
        el('div', { class: 'who' }, [el('span', { class: 'name', text: p.id }), el('span', { class: 'meta', text: 'pending promotion — on /ship of a running WATCH server now; ships at the next roll' })]),
        rejectLine(p.id), el('div', { class: 'acts' }, [undoBtn(p.id, 'promotion')])]));
    });
    if (!mods.length) { box.appendChild(el('div', { class: 'empty', text: 'No mod variants listed.' })); return; }
    mods.forEach(function (m) {
      var pk = m.pending || '';
      box.appendChild(el('div', { class: 'srow' + (pk === 'remove' ? ' pending' : ''), dataset: { id: m.id, pending: pk }, title: m.id }, [
        el('div', { class: 'who' }, [el('span', { class: 'name', text: m.id }), el('span', { class: 'meta', text: (m.npc ? 'npc' : 'pickable') + (pk ? ' · pending ' + (pk === 'remove' ? 'demote' : 'update (live at the next roll)') : '') })]),
        rejectLine(m.id),
        el('div', { class: 'acts' }, [el('button', { text: 'Load', title: 'load this mod variant into the editor as a draft', on: { click: function () { sfx('ui_button_pressed'); loadServerBuild(m.id, 'catalog'); } } }), el('button', { text: 'Save', title: 'write the CURRENT build over this mod variant (same hull) — a promotion marker under this stem; the next roll updates it in place', on: { click: function () {
          if (!S.hull) { sfx('ui_button_disabled_pressed'); out.textContent = 'pick a hull first'; return; }
          sfx('ui_button_pressed'); out.textContent = 'saving over ' + m.id + '…';
          postJson('catalog/' + encodeURIComponent(m.id) + '/save', variantDocument()).then(function (res) { return refresh(res, 'update of ' + m.id); }).catch(function (e) { out.textContent = 'save failed: ' + e.message; });
        } } }), pk === 'remove' ? undoBtn(m.id, 'demotion') : el('button', { class: 'danger', text: 'Demote', title: m.npc ? 'mark this npc variant for removal from the mod at the next roll' : 'take this build out of /ship — hidden on a running WATCH server now; the next roll deletes it from the mod', on: { click: function () {
          
          if (!window.confirm('Demote "' + m.id + '"?\n' + (m.npc ? 'The next roll deletes this npc variant from the mod.' : 'It leaves /ship on a running WATCH server; the next roll deletes it from the mod.') + ' Undo stays on the row until then.')) { return; }
          sfx('ui_button_pressed'); out.textContent = 'marking…';
          postJson('catalog/' + encodeURIComponent(m.id) + '/remove', {}).then(function (res) { return refresh(res, 'demotion'); }).catch(function (e) { out.textContent = 'demote failed: ' + e.message; });
        } } }), pk === 'promote' ? undoBtn(m.id, 'update') : null])
      ]));
    });
  }
  
  
  
  
  
  
  
  
  
  
  var HOST_DIM = 'Join your server to manage its ships here; the web console can too';
  
  
  var HOST_PROMOTE_HELP = 'Promote adds your current build, or replaces the ship you loaded it from once you change it';
  var HOST_HELP = 'Every player picks from this list with /ship. Remove takes a ship off it for everyone; Put back restores it; ' + HOST_PROMOTE_HELP + '; an unchanged ship you took off goes back as it was.';
  var HOST_NO_KEEP = 'Keeping ships is off on this server (custom.submissions)';
  var HOST_NO_ANSWER = 'no answer from the server - is a match running?';
  
  var HOST_GROUPS = [
    { key: 'listed', head: 'Listed', title: 'ships that come with the mod — on /ship for every player' },
    { key: 'kept', head: 'Kept builds', title: 'builds kept on this server — on /ship for every player' },
    { key: 'hidden', head: 'Taken off', title: 'ships that come with the mod, taken off /ship on this server — Put back restores them' },
    { key: 'removed', head: 'Removed builds', title: 'kept builds removed from this server — Put back restores them' }];
  
  function hostCardMode() {
    if (S.modCatalog || !S.catalogKnown) { return 'off'; }
    return !S.host.live ? 'dim' : S.host.admin ? 'list' : 'signin';
  }
  
  
  function hostKeyNow() { var m = hostCardMode(); return m + (m === 'list' && S.host.submissions ? '+' : ''); }
  function hostPingChanged() { if (S.catalogKnown && !S.modCatalog && hostKeyNow() !== S.hostKey) { renderModCatalog(); } }
  
  function renderHostCard() {
    var mode = hostCardMode(), box = $('catalog'), dim = $('hostDim');
    S.hostKey = hostKeyNow();
    document.body.dataset.hostCard = mode;
    if (mode !== 'list') { S.hostShips = null; } else { S.hostSignMsg = null; }
    syncPromoteTop();
    
    
    if (mode === 'dim' && $('catalogBox').open) { $('catalogBox').open = false; }
    dim.hidden = mode !== 'dim';
    dim.textContent = mode === 'dim' ? HOST_DIM : '';
    $('catalogTitle').textContent = mode === 'signin' || mode === 'list' ? 'Ships on this server' : 'Mod catalog';
    if (mode === 'off' || mode === 'dim') { return false; }
    $('catalogBox').hidden = false;
    if (mode === 'signin') { $('catalogN').textContent = ''; $('catalogNote').textContent = 'for the server\'s host'; hostSignRow(box); return true; }
    var sh = S.hostShips, g = {};
    HOST_GROUPS.forEach(function (gr) { g[gr.key] = sh && sh.ok && Array.isArray(sh[gr.key]) ? sh[gr.key].map(String) : []; });
    $('catalogN').textContent = sh && sh.ok ? '(' + (g.listed.length + g.kept.length) + ')' : '';
    $('catalogNote').textContent = sh && sh.ok ? String(sh.line || '').replace(/^shiplist /, '') : '';
    box.appendChild(el('div', { class: 'hostHelp', text: HOST_HELP }));
    box.appendChild(hostBuildRow());
    if (!sh) {
      box.appendChild(el('div', { class: 'empty', text: 'reading the list…' }));
      if (!S.hostLoading) { loadHostShips(); }
      return true;
    }
    if (!sh.ok) {
      box.appendChild(el('div', { class: 'empty', text: 'the list did not load: ' + String(sh.error || 'no answer') }));
      box.appendChild(el('div', { class: 'acts hostRetry' }, [el('button', { text: 'Read it again', on: { click: function () { sfx('ui_button_pressed'); loadHostShips(); } } })]));
      return true;
    }
    HOST_GROUPS.forEach(function (gr) {
      if (!g[gr.key].length) { return; }
      box.appendChild(el('h4', { class: 'ghead', text: gr.head + ' (' + g[gr.key].length + ')', title: gr.title }));
      g[gr.key].forEach(function (id) { box.appendChild(hostShipRow(id, gr.key)); });
    });
    if (!g.listed.length && !g.kept.length && !g.hidden.length && !g.removed.length) { box.appendChild(el('div', { class: 'empty', text: 'The server listed no ships.' })); }
    return true;
  }
  
  function loadHostShips() {
    var seq = ++S.hostSeq;
    S.hostLoading = true;
    return getJson('host/ships').then(function (r) { return r && typeof r === 'object' ? r : { ok: false, error: 'no list' }; },
      function (e) { return { ok: false, error: hostFail(e) }; }).then(function (r) {
      if (seq !== S.hostSeq) { return; }
      S.hostLoading = false;
      if (hostCardMode() !== 'list') { return; }
      S.hostShips = r;
      if (S.hostAdopt && r.ok) { hostAdoptKept(r); }   
      var top = $('catalog').scrollTop;
      renderModCatalog();
      $('catalog').scrollTop = top;
      if (r.ok && S.serverConnected) { renderServer(false); }   
    });
  }
  
  
  
  function syncPromoteTop() {
    var b = $('promoteTop'), sh = S.hostShips;
    var on = document.body.dataset.hostCard === 'list' && !!(sh && sh.ok);
    b.hidden = !on;
    b.disabled = S.hostBusy;
    b.title = S.host.submissions ? HOST_PROMOTE_HELP : HOST_NO_KEEP;
  }
  function hostFail(e) { return e instanceof TypeError ? 'no answer from the game' : String((e && e.message) || 'failed'); }
  
  
  
  
  
  function hostVerdict(r) {
    if (!r || !r.ok) { return { text: String((r && r.error) || 'refused'), cls: 'err', kind: 'no' }; }
    var t = String(r.verdict || '').replace(/^[a-z]+ \S+: /, '');
    if (t.charAt(0) === '\'') { return { text: t, cls: 'ok', kind: 'ok' }; }
    if (!t || /^(no result\b|saving$|removing$|restoring$)/.test(t)) { return { text: 'unknown - check the list', cls: '', kind: 'wait' }; }
    return { text: t, cls: 'err', kind: 'no' };
  }
  
  function hostLineEl(target) {
    var f = S.hostLine, mine = f && (target == null ? f.build : !f.build && f.id === target);
    return mine ? el('div', { class: 'reply' + (f.cls ? ' ' + f.cls : ''), text: f.text, title: f.text }) : null;
  }
  function hostPaint(target, text, cls, news) {
    S.hostLine = { build: target == null, id: target == null ? '' : String(target), text: text, cls: cls || '' };
    var rows = $('catalog').querySelectorAll('.srow'), row = null;
    for (var i = 0; i < rows.length && !row; i++) {
      var d = rows[i].dataset;
      if (target == null ? d.promote === 'current' : d.hid === String(target)) { row = rows[i]; }
    }
    if (row) { var old = row.querySelector('.reply'), nu = hostLineEl(target); if (old) { row.replaceChild(nu, old); } else { row.appendChild(nu); } }
    if (news && !$('catalogBox').hidden && !$('catalogBox').open) { $('catalogBox').open = true; }
  }
  
  function hostBusy(on) {
    S.hostBusy = !!on;
    var bs = $('catalog').querySelectorAll('button.hv');
    for (var i = 0; i < bs.length; i++) { bs[i].disabled = S.hostBusy || (bs[i].dataset.keep === '1' && !S.host.submissions); }
    syncPromoteTop();
  }
  
  function hostBuildRow() {
    var noKeep = !S.host.submissions;
    var prm = el('button', { class: 'primary hv', text: 'Promote', dataset: { keep: '1' }, title: noKeep ? HOST_NO_KEEP : 'adds this build to /ship for every player (your own respawn pick is unchanged)', on: { click: function () { hostPromote(); } } });
    prm.disabled = noKeep || S.hostBusy;
    return el('div', { class: 'srow promote', dataset: { promote: 'current', stem: S.hull ? variantDocument().variantId : '' } }, [
      el('div', { class: 'who' }, [el('span', { class: 'name', text: S.hull ? (S.name || 'this build') : 'pick a hull first' }), el('span', { class: 'meta', text: S.hull ? S.hull.name : '' })]),
      el('div', { class: 'acts' }, [prm]),
      noKeep ? el('div', { class: 'hostOff', text: HOST_NO_KEEP }) : null,
      hostLineEl(null)
    ]);
  }
  
  function syncHostBuildRow() {
    if (document.body.dataset.hostCard !== 'list') { return; }
    var old = $('catalog').querySelector('.srow.promote[data-promote="current"]');
    if (old) { old.parentNode.replaceChild(hostBuildRow(), old); }
  }
  function hostMeta(id) {
    var row = (S.server || []).filter(function (r) { return r.id === id; })[0] || null;
    var hull = row && row.hullId ? (S.catalog.hulls.filter(function (h) { return h.id === row.hullId; })[0] || null) : null;
    return hull ? hull.name : row ? String(row.hullName || row.hullId || '') : '';
  }
  
  
  function hostMapped(map, id) {
    var m = (S.hostShips && S.hostShips[map]) || {};
    return Object.prototype.hasOwnProperty.call(m, id) && m[id] ? String(m[id]) : '';
  }
  
  function hostName(id, fallback) { return hostMapped('names', id) || fallback || id; }
  function hostShipRow(id, kind) {
    var gone = kind === 'removed', onShip = kind === 'listed' || kind === 'kept';
    var load = el('button', { text: 'Load', title: gone ? 'not on this server any more' : 'load this ship into the editor as a draft' + (onShip ? ' — change it, then Promote replaces it for every player' : ''), on: { click: function () { sfx('ui_button_pressed'); loadServerBuild(id, 'host', kind); } } });
    load.disabled = gone;
    var shown = hostMapped('names', id), named = shown || id;
    var verb = onShip
      ? el('button', { class: 'danger hv', text: 'Remove', title: 'take this ship off /ship for every player', on: { click: function () {
        var ask = kind === 'listed'
          ? 'Remove \'' + named + '\' for every player? It leaves /ship now and after restarts until you put it back. A player already flying it, or whose seat\'s default ship it is, keeps it.'
          : 'Remove \'' + named + '\' for every player? You can put it back from this card.';
        if (!window.confirm(ask)) { return; }
        sfx('ui_button_pressed'); hostVerb('remove', id, kind);
      } } })
      : el('button', { class: 'hv', text: 'Put back', title: 'put this ship back on /ship for every player', on: { click: function () { sfx('ui_button_pressed'); hostVerb('restore', id, kind); } } });
    verb.disabled = S.hostBusy;
    
    
    
    return el('div', { class: 'srow hostRow' + (gone ? ' gone' : ''), dataset: { hid: id, kind: kind }, title: id + (gone ? ' — not on this server any more' : '') }, [
      el('div', { class: 'who' }, [el('span', { class: 'name', text: named }), el('span', { class: 'meta', text: hostMapped('hulls', id) || hostMeta(id) })]),
      el('span', { class: 'hid', text: id, title: id }),
      el('div', { class: 'acts' }, [load, verb, id === S.serverDefault ? el('span', { class: 'badge dflt', text: 'DEFAULT', title: 'the server\'s default spawn' }) : null]),
      hostLineEl(id)
    ]);
  }
  
  
  function hostVerb(verb, id, kind) {
    if (S.hostBusy) { return; }
    hostBusy(true);
    hostPaint(id, verb === 'remove' ? 'removing…' : 'putting back…', '');
    var to = null, copyGone = false;
    postJson('host/' + verb, { id: id }).then(function (r) {
      var v = hostVerdict(r);
      to = hostSyncGroup(verb, kind, v);
      copyGone = verb === 'restore' && v.kind === 'ok' && / the changed copy is gone/.test(v.text);
      hostPaint(id, v.text, v.cls, true);
    }, function (e) { hostPaint(id, hostFail(e), 'err', true); })
      .then(function () { hostBusy(false); repingSoon(); return to && hostMoveRow(id, kind, to) ? null : loadHostShips(); })
      .then(function () { if (copyGone) { hostRepointPutBack(id, kind); } });
  }
  
  
  
  
  
  
  function hostSyncGroup(verb, kind, v) {
    if (!v || v.kind !== 'ok') { return null; }
    if (verb === 'remove' && kind === 'listed' && /^'.*' removed\b/.test(v.text)) { return 'hidden'; }
    if (verb === 'restore' && kind === 'hidden' && /^'.*' put back\b/.test(v.text) && !/ the changed copy is gone/.test(v.text)) { return 'listed'; }
    return null;
  }
  
  
  
  
  function hostMoveRow(id, from, to) {
    var sh = S.hostShips;
    if (S.hostLoading || hostCardMode() !== 'list' || !sh || !sh.ok || !Array.isArray(sh[from]) || !Array.isArray(sh[to])) { return false; }
    var at = sh[from].map(String).indexOf(id);
    if (at < 0) { return false; }
    sh[from].splice(at, 1);
    var i = 0;
    while (i < sh[to].length && hostFoldCmp(String(sh[to][i]), id) < 0) { i++; }
    sh[to].splice(i, 0, id);
    sh.line = hostCountShift(sh.line, from, to);
    var top = $('catalog').scrollTop;
    renderModCatalog();
    $('catalog').scrollTop = top;
    return true;
  }
  
  function hostFoldCmp(a, b) {
    var lo = function (s) { return s.replace(/[A-Z]/g, function (c) { return c.toLowerCase(); }); }, fa = lo(a), fb = lo(b);
    return fa < fb ? -1 : fa > fb ? 1 : a < b ? -1 : a > b ? 1 : 0;
  }
  
  
  function hostCountShift(line, from, to) {
    var m = /^shiplist (\d+) listed, (\d+) hidden, (\d+) kept, (\d+) removed$/.exec(String(line || ''));
    if (!m) { return ''; }
    var n = { listed: +m[1], hidden: +m[2], kept: +m[3], removed: +m[4] };
    n[from] = Math.max(0, n[from] - 1); n[to] += 1;
    return 'shiplist ' + n.listed + ' listed, ' + n.hidden + ' hidden, ' + n.kept + ' kept, ' + n.removed + ' removed';
  }
  
  
  
  
  
  
  
  
  
  
  
  
  function hostPromote() {
    if (S.hostBusy) { return; }
    if (!S.host.submissions) { sfx('ui_button_disabled_pressed'); hostPaint(null, HOST_NO_KEEP, 'err'); return; }
    if (!S.hull) { sfx('ui_button_disabled_pressed'); hostPaint(null, 'pick a hull first', 'err'); return; }
    var lf = S.loadedFrom && S.loadedFrom.hullId === S.hull.id ? S.loadedFrom : null;
    var group = lf ? hostGroupOf(lf.id) || lf.kind : '';
    if (lf && group === 'gone') { lf = null; }   
    var nm = lf ? hostName(lf.id, S.name) : '';
    if (lf && S.loadedSnap != null && stateSnapshot() === S.loadedSnap) {
      if (group === 'hidden' || group === 'removed') {
        sfx('ui_button_pressed');
        hostPaint(null, 'putting \'' + nm + '\' back…', '');
        hostVerb('restore', lf.id, group);
        return;
      }
      if (group === 'listed' || group === 'kept') {
        sfx('ui_button_disabled_pressed');
        hostPaint(null, group === 'listed' ? '\'' + nm + '\' is already on /ship for every player' : '\'' + nm + '\' is already kept', '');
        return;
      }
    }
    if (!S.verdict || !S.verdict.ok) { sfx('ui_button_disabled_pressed'); hostPaint(null, 'this build has problems — fix them first', 'err'); return; }
    var replaces = lf ? lf.id : null;
    var sh = S.hostShips, adopt = { name: String(S.name || '').trim(), hullId: S.hull.id, snap: stateSnapshot(), replaced: replaces,
      before: sh && sh.ok && Array.isArray(sh.kept) ? sh.kept.map(String) : [] };
    var label = adopt.name || 'this build';
    sfx('ui_button_pressed');
    hostBusy(true);
    hostPaint(null, 'submitting…', '');
    var hullId = S.hull.id, sink = el('span');
    
    
    postJson('save?keep=1', variantDocument()).then(function (r) {
      if (!r || !r.saved) { return { text: 'not saved: ' + String((r && r.error) || 'refused'), cls: 'err' }; }
      if (S.hull && S.hull.id === hullId) { S.clean[hullId] = stateSnapshot(); S.drafts[hullId] = { snap: S.clean[hullId], dirty: false }; refreshDraftDots(); }
      return flyBuild(r.id, sink, null, true).then(function (f) {
        var sub = f && f.flown && f.replyState === 'submitted' ? f.submit : null;
        if (!sub || sub.accepted !== true || !sub.id) { return hostSubmitFail(label, sub, sink); }
        hostPaint(null, 'saving…', '');
        var body = { id: String(sub.id) };
        if (replaces) { body.replaces = replaces; }
        return postJson('host/keep', body).then(function (k) {
          var v = hostVerdict(k);
          if (v.kind === 'ok') { S.hostAdopt = adopt; }
          return v;
        });
      });
    }).then(function (v) { hostPaint(null, v.text, v.cls, true); }, function (e) { hostPaint(null, hostFail(e), 'err', true); })
      .then(function () { hostBusy(false); syncHostBuildRow(); return loadHostShips(); });
  }
  
  
  function hostGroupOf(id) {
    var sh = S.hostShips;
    if (!sh || !sh.ok) { return ''; }
    for (var i = 0; i < HOST_GROUPS.length; i++) {
      var g = sh[HOST_GROUPS[i].key];
      if (Array.isArray(g) && g.map(String).indexOf(id) >= 0) { return HOST_GROUPS[i].key; }
    }
    return 'gone';
  }
  
  function hostSubmitFail(label, sub, sink) {
    if (sub && sub.submitted === false) { return { text: '\'' + label + '\' not sent: ' + String(sub.message || sub.error || 'refused'), cls: 'err' }; }
    if (sub && !sub.answered) { return { text: '\'' + label + '\' sent - the server has not answered yet; check the list', cls: '' }; }
    if (sub && sub.accepted === false) { return { text: '\'' + label + '\' refused (' + String(sub.reasonToken || '?') + ')' + (sub.hint ? ': ' + sub.hint : ''), cls: 'err' }; }
    return { text: sink.textContent || 'not submitted', cls: 'err' };
  }
  
  
  
  
  
  function hostAdoptKept(sh) {
    var a = S.hostAdopt;
    S.hostAdopt = null;
    if (!a || !sh || !sh.ok || !Array.isArray(sh.kept)) { return; }
    var kept = sh.kept.map(String), exact = [], fresh = [];
    kept.forEach(function (id) {
      var n = hostMapped('names', id), h = hostMapped('hullIds', id), isNew = a.before.indexOf(id) < 0;
      if (n === a.name && h === a.hullId) { exact.push(id); }
      if (isNew && (!n || n === a.name) && (!h || h === a.hullId)) { fresh.push(id); }
    });
    var exactNew = exact.filter(function (id) { return fresh.indexOf(id) >= 0; });
    var pick = exactNew.length ? exactNew[exactNew.length - 1] : exact.length ? exact[exact.length - 1] : fresh.length === 1 ? fresh[0] : null;
    
    S.loadedFrom = pick ? { id: pick, kind: 'kept', hullId: a.hullId, replaced: a.replaced || null } : null;
    S.loadedSnap = pick ? a.snap : null;
  }
  
  
  
  
  
  function hostRepointPutBack(id, kind) {
    var lf = S.loadedFrom;
    if (!lf || lf.id === id) { return; }
    var hull = hostMapped('hullIds', id) || lf.hullId;
    var fromCopy = lf.replaced === id || (lf.kind === 'kept' && lf.hullId === hull && hostGroupOf(lf.id) !== 'kept');
    if (!fromCopy) { return; }
    S.loadedFrom = { id: id, kind: kind === 'removed' ? 'kept' : 'listed', hullId: hull, replaced: null };
    S.loadedSnap = null;
  }
  
  
  
  var HOST_SIGN_LINES = {
    'refused': { text: 'not the admin password', cls: 'err' },
    'held': { text: 'the server is still checking - try again', cls: 'err' },
    'no-answer': { text: HOST_NO_ANSWER, cls: 'err' },
    'busy': { text: 'one sign-in at a time', cls: 'err' },
    'not-live': { text: HOST_DIM, cls: '' },
    'signed-in': { text: 'signed in — reading the list…', cls: 'ok' } };
  function hostSignRow(box) {
    var input = el('input', { type: 'password', autocomplete: 'new-password', placeholder: 'admin password', maxlength: '128', spellcheck: 'false', 'aria-label': 'the server\'s admin password' });
    var btn = el('button', { class: 'primary', text: 'Sign in as host', title: 'sign in as this server\'s host — the password is checked by the server, never stored here' });
    btn.disabled = S.hostSigning;
    btn.addEventListener('click', function () { sfx('ui_button_pressed'); hostSignIn(input, btn); });
    input.addEventListener('keydown', function (ev) { if (ev.key === 'Enter') { ev.preventDefault(); hostSignIn(input, btn); } });
    var m = S.hostSignMsg;
    box.appendChild(el('div', { class: 'hostHelp', text: 'Sign in with the server\'s admin password to manage its ships here (again after every server restart). On the server\'s PC, Join from its console signs you in without one - if no admin password is set, that is the only way.' }));
    box.appendChild(el('div', { class: 'srow hostSign' }, [el('div', { class: 'acts' }, [input, btn]),
      m ? el('div', { class: 'reply' + (m.cls ? ' ' + m.cls : ''), text: m.text }) : null]));
  }
  function hostSignPaint(text, cls) {
    S.hostSignMsg = { text: text, cls: cls || '' };
    var row = $('catalog').querySelector('.srow.hostSign'); if (!row) { return; }
    var old = row.querySelector('.reply'), nu = el('div', { class: 'reply' + (cls ? ' ' + cls : ''), text: text });
    if (old) { row.replaceChild(nu, old); } else { row.appendChild(nu); }
  }
  function hostSignIn(input, btn) {
    var secret = String(input.value || '');
    input.value = '';
    if (S.hostSigning) { hostSignPaint('one sign-in at a time', 'err'); return; }
    if (!secret.trim()) { hostSignPaint('type the admin password first', 'err'); return; }
    S.hostSigning = true; btn.disabled = true;
    hostSignPaint('signing in…', '');
    var retried = false;
    function ask() {
      return postJson('host/signin', { secret: secret }).then(function (r) {
        var st = String((r && r.state) || '');
        if (st === 'held' && !retried) { retried = true; return new Promise(function (ok) { setTimeout(ok, 1000); }).then(ask); }
        return st;
      });
    }
    ask().then(function (st) {
      var line = HOST_SIGN_LINES[st] || { text: HOST_NO_ANSWER, cls: 'err' };
      hostSignPaint(line.text, line.cls);
      if (st === 'signed-in' || st === 'not-live') { repingSoon(); }
    }, function (e) { hostSignPaint('sign-in failed: ' + hostFail(e), 'err'); })
      .then(function () {
        secret = ''; S.hostSigning = false;
        var cur = $('catalog').querySelector('.srow.hostSign button');   
        if (cur) { cur.disabled = false; }
      });
  }
  function loadBuild(id) {
    var out = $('saveResult');
    out.textContent = 'loading…';
    getJson('saved/' + encodeURIComponent(id)).then(function (doc) {
      return pickHull(doc.hullId, { noDraft: true }).then(function () {   
        S.loadedFrom = null; S.loadedSnap = null; S.hostAdopt = null;   
        if (S.hull && S.hull.id === doc.hullId) { applyDocument(doc); }
        else { out.textContent = 'cannot load ' + id + ': hull ' + doc.hullId + ' is not available here'; }
      });
    }).catch(function (e) { out.textContent = 'load failed: ' + e.message; });
  }
  function applyDocument(doc) {
    var dropped = setDocument(doc);
    if (S.hull) { S.clean[S.hull.id] = stateSnapshot(); }   
    S.fitSig = null;
    undoFloor();   
    renderAll();
    changed();
    $('saveResult').textContent = 'loaded ' + (doc.id || S.name)
      + (dropped.length ? ' — dropped (unknown here): ' + dropped.slice(0, 6).join(', ') + (dropped.length > 6 ? ' +' + (dropped.length - 6) + ' more' : '') : '');
  }
  
  
  function setDocument(doc) {
    var dropped = [], h = S.hull;
    S.fit = {}; S.groupOf = {}; S.biGroupOf = {};
    for (var k = 0; k < 7; k++) { S.groups[k] = { mode: 'LINKED', autofire: false }; }
    (doc.weaponGroups || []).forEach(function (g, gi) {
      var n = Math.min(gi + 1, 7);
      S.groups[n - 1] = { mode: g.mode === 'ALTERNATING' ? 'ALTERNATING' : 'LINKED', autofire: !!g.autofire };
      Object.keys(g.weapons || {}).forEach(function (slotId) {
        var wid = g.weapons[slotId];
        if (isBuiltInSlot(slotId)) { if (biw()[slotId] === wid) { S.biGroupOf[slotId] = n; } else { dropped.push(wid + ' @ ' + slotId); } return; }   
        if (!S.weaponById[wid] || !slotFits(slotById(slotId), wid)) { dropped.push(wid + ' @ ' + slotId); return; }
        S.fit[slotId] = wid; S.groupOf[slotId] = n;
      });
    });
    
    
    Object.keys(biw()).forEach(function (id) { var x = slotById(id); if (x && !x.fittable && !x.hidden && !x.decorative && !x.system && !x.launchBay && !x.stationModule && !S.biGroupOf[id]) { S.biGroupOf[id] = 0; } });
    S.mods = (doc.hullMods || []).filter(function (m) { if (S.modById[m]) { return true; } dropped.push(m); return false; });
    
    
    S.smods = [];
    (doc.sMods || []).forEach(function (m) {
      if (!S.modById[m]) { if (dropped.indexOf(m) < 0) { dropped.push(m); } return; }
      if (S.mods.indexOf(m) < 0 && (h.builtInMods || []).indexOf(m) < 0) { S.mods.push(m); }
      if (S.mods.indexOf(m) >= 0 && S.smods.indexOf(m) < 0) { S.smods.push(m); }
    });
    S.vents = Math.max(0, Math.min(h.maxVents, parseInt(doc.fluxVents, 10) || 0));
    S.caps = Math.max(0, Math.min(h.maxCaps, parseInt(doc.fluxCapacitors, 10) || 0));
    S.wings = (doc.wings || []).filter(function (w) { if (S.wingById[w]) { return true; } dropped.push(w); return false; });
    S.name = String(doc.displayName || '').slice(0, 48); $('name').value = S.name;
    return dropped;
  }
  function deleteBuild(r) {
    if (!window.confirm('Delete the saved build "' + (r.displayName || r.id) + '"?\n(' + r.id + ' — this removes the file)')) { return; }
    postJson('saved/' + encodeURIComponent(r.id) + '/delete', {}).then(function (res) {
      $('saveResult').textContent = res.deleted ? 'deleted ' + r.id : 'nothing to delete (' + r.id + ')';
      repingSoon();   
      return loadSavedList();
    }).catch(function (e) { $('saveResult').textContent = 'delete failed: ' + e.message; });
  }
  
  
  

  
  document.addEventListener('DOMContentLoaded', function () {
    canvas = $('canvas'); ctx = canvas.getContext('2d');
    canvas.addEventListener('click', stageClick);
    
    
    canvas.addEventListener('contextmenu', function (ev) {
      var m = markerAt(ev);
      if (m) { ev.preventDefault(); clearMount(m.id); }
    });
    canvas.addEventListener('mousemove', stageMove);
    canvas.addEventListener('mousedown', stageDown);   
    document.addEventListener('mouseup', paintEnd);   
    document.addEventListener('mouseup', wingPaintEnd);   
    
    $('mods').addEventListener('scroll', function () {
      var ml = $('mods'); if (!S.pinHeld || !Object.keys(S.pinHeld).length || ml.scrollTop <= 0) { return; }
      var before = ml.scrollHeight; updateModList(); ml.scrollTop += ml.scrollHeight - before;
    });
    canvas.addEventListener('mouseleave', function () { if (S.paint && S.paint.via === 'ship') { paintEnd(); } });
    document.addEventListener('mousemove', dragMove);
    document.addEventListener('mouseup', dragEnd);
    window.addEventListener('blur', dragEnd);   
    canvas.addEventListener('mouseleave', function () { if (S.drag) { return; } hoverArmLeave(null, 'ship'); S.hoverSlot = null; $('stageTip').hidden = true; drawStage(); });   
    
    
    
    $('tab-arm').addEventListener('mouseleave', function () { hoverArmLeave(null, 'row'); });
    $('tab-arm').addEventListener('click', function () { S.hoverArm = null; });
    window.addEventListener('resize', function () { drawStage(); });
    
    
    function armDpr() {
      if (!window.matchMedia) { return; }
      var mq = matchMedia('(resolution: ' + (window.devicePixelRatio || 1) + 'dppx)');
      if (!mq.addEventListener) { return; }
      mq.addEventListener('change', function () { drawStage(); armDpr(); }, { once: true });
    }
    armDpr();
    
    
    if (window.ResizeObserver) {
      var stageFrame = 0;
      new ResizeObserver(function () { if (stageFrame) { return; } stageFrame = requestAnimationFrame(function () { stageFrame = 0; drawStage(); }); }).observe($('stageWrap'));
    }
    $('hullSearch').addEventListener('input', function (ev) { S.hullSearch = ev.target.value; renderHullRail(); });
    $('showDenied').addEventListener('change', function (ev) { S.showDenied = ev.target.checked; renderHullRail(); });
    $('compactRail').addEventListener('change', function (ev) { $('hulls').classList.toggle('compact', ev.target.checked); });
    
    document.body.dataset.savedOpen = '0';
    
    
    
    $('savedBox').addEventListener('toggle', function () { document.body.dataset.savedOpen = $('savedBox').open ? '1' : '0'; if ($('savedBox').open) { if ($('serverBox').open) { $('serverBox').open = false; } if ($('catalogBox').open) { $('catalogBox').open = false; } } });
    $('serverBox').addEventListener('toggle', function () { document.body.dataset.serverOpen = $('serverBox').open ? '1' : '0'; if ($('serverBox').open) { if ($('savedBox').open) { $('savedBox').open = false; } if ($('catalogBox').open) { $('catalogBox').open = false; } loadServerList(); } });   
    document.body.dataset.catalogOpen = $('catalogBox').open ? '1' : '0';
    $('catalogBox').addEventListener('toggle', function () { document.body.dataset.catalogOpen = $('catalogBox').open ? '1' : '0'; if ($('catalogBox').open) { if ($('savedBox').open) { $('savedBox').open = false; } if ($('serverBox').open) { $('serverBox').open = false; } } });
    $('catalogBox').addEventListener('toggle', function () { if ($('catalogBox').open && document.body.dataset.hostCard === 'list' && !S.hostLoading) { loadHostShips(); } });   
    document.body.dataset.retgroups = S.returnGroups ? '1' : '0';
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    function wheelNotch(ev, now) {   
      S.wheelAcc = (now - (S.wheelAt || 0) > 300 ? 0 : (S.wheelAcc || 0)) + ev.deltaY; S.wheelAt = now;
      if (Math.abs(S.wheelAcc) < 50) { return 0; }
      var dir = S.wheelAcc > 0 ? 1 : -1; S.wheelAcc = 0; return dir;
    }
    document.addEventListener('wheel', function (ev) {
      if (ev.ctrlKey) { return; }
      var t = ev.target && ev.target.closest ? ev.target : null, now = Date.now(), dir;
      if (!t) { return; }
      
      
      
      if (ev.shiftKey && t.closest('#stage') && S.hull) {
        
        
        S.zoomAcc = (now - (S.zoomAt || 0) > 300 ? 0 : (S.zoomAcc || 0)) + (ev.deltaY || ev.deltaX); S.zoomAt = now;
        if (Math.abs(S.zoomAcc) >= 50) { zoomNotch(S.zoomAcc < 0 ? 1 : -1); S.zoomAcc = 0; }   
        ev.preventDefault(); return;
      }
      if (!S.hull || t.closest('#rail, #budget, #codex, #keysCard, #renderCard, #ctxMenu, #tip')) { return; }
      var onArm = S.tab === 'arm', onWings = S.tab === 'wings';
      
      
      var wRight = onArm && !!t.closest('#weapons') && ev.clientX >= $('weapons').getBoundingClientRect().left + $('weapons').clientWidth * 2 / 3;
      if (S.selectedSlot && onArm && !wRight) {   
        if ((dir = wheelNotch(ev, now))) { wheelStep(dir); if (t.closest('#weapons')) { document.body.dataset.lwheel = String(S.lwheelN = (S.lwheelN || 0) + 1); } }
        ev.preventDefault(); return;
      }
      if (S.selectedBay != null && onWings) {   
        if ((dir = wheelNotch(ev, now))) { wingStep(dir); }
        ev.preventDefault(); return;
      }
      
      var m = t === canvas ? markerAt(ev) : null, lrow = t.closest('#weapons .wrow[data-slot]:not(.locked)');
      var hid = m ? (m.builtin ? null : m.id) : lrow ? lrow.dataset.slot : null, hs = hid ? slotById(hid) : null;
      if (hs && hs.fittable && !wRight) {
        if ((dir = wheelNotch(ev, now))) {
          var fromTab = S.tab;   
          if (!onArm) { setTab('arm'); }
          selectSlot(hid); wheelStep(dir); document.body.dataset.mwheel = hid + ':' + (S.fit[hid] || '');
          S.hoverArm = { id: hid, via: m ? 'ship' : 'row', from: fromTab };   
        }
        ev.preventDefault(); return;
      }
      
      var bnode = t.closest('#bays .bay[data-bay], #wingsBody .wrow[data-bay]');
      if (bnode) {
        if ((dir = wheelNotch(ev, now))) { bayWheel(parseInt(bnode.dataset.bay, 10), dir); }
        ev.preventDefault(); return;
      }
      
      var over = t.closest('.scroll'), own = onArm ? $('weapons') : S.tab === 'groups' ? $('groups') : onWings ? $('wingsBody') : null;
      
      
      
      var gRight = S.tab === 'groups' && over === own && ev.clientX >= own.getBoundingClientRect().left + own.clientWidth / 2;
      if (own && !gRight && !wRight && (!over || over === own)) {
        if (!(dir = wheelNotch(ev, now))) { ev.preventDefault(); return; }
        
        
        if (onArm && S.cursorByTab && S.loadoutCursor && $('weapons').querySelector('.wrow.cur[data-slot]')) {
          var tid = S.loadoutCursor; selectSlot(tid); wheelStep(dir); document.body.dataset.mwheel = tid + ':' + (S.fit[tid] || ''); ev.preventDefault(); return;
        }
        if (onArm ? loadoutCursorStep(dir) : onWings ? bayCursorStep(dir) : groupsCursorStep(dir)) { ev.preventDefault(); return; }
      }
      
      if (over && over.scrollHeight > over.clientHeight) { return; }
      var tab = document.querySelector('.tab.on'), list = tab ? tab.querySelector('.scroll') : null;
      if (!list || list.scrollHeight <= list.clientHeight) { return; }
      list.scrollTop += ev.deltaY; ev.preventDefault();
    }, { passive: false });
    
    
    
    ['mousedown', 'mouseup', 'auxclick'].forEach(function (type) {
      document.addEventListener(type, function (ev) {
        if (ev.button !== 3 && ev.button !== 4) { return; }
        ev.preventDefault();
        if (type === 'mouseup') { if (ev.button === 3) { navBack(); } else { navFwd(); } }
      }, { passive: false });
    });
    $('weaponSearch').addEventListener('input', function (ev) { S.weaponSearch = ev.target.value; renderArm(); });
    $('modSearch').addEventListener('input', function (ev) { S.modSearch = ev.target.value; updateModList(); });
    $('showDmods').checked = S.showDmods; document.body.dataset.showdmods = S.showDmods ? '1' : '0';   
    $('showDmods').addEventListener('change', function (ev) { sfx('ui_button_pressed'); setShowDmods(ev.target.checked); });
    $('wingSearch').addEventListener('input', function (ev) { S.wingSearch = ev.target.value; renderWings(); });
    
    function clampToBudget(ev, current) {
      var want = parseInt(ev.target.value, 10) || 0, free = opFree();
      if (want > current && want - current > free) { want = current + Math.max(0, free); ev.target.value = want; }
      bumpOp(want - current);
      return want;
    }
    $('ventsRange').addEventListener('input', function (ev) { var v = clampToBudget(ev, S.vents); if (v !== S.vents) { sfx('ui_button_mouseover'); } S.vents = v; changed(true); });   
    
    
    $('crRange').addEventListener('input', function (ev) { var v = Math.max(0, Math.min(100, parseInt(ev.target.value, 10) || 0)); if (v === S.cr) { return; } S.cr = v; crChanged(); });
    $('crReset').addEventListener('click', function () { S.cr = 100; crChanged(); });
    $('capsRange').addEventListener('input', function (ev) { var v = clampToBudget(ev, S.caps); if (v !== S.caps) { sfx('ui_button_mouseover'); } S.caps = v; changed(true); });   
    
    
    
    
    var shiftHeld = false;
    document.addEventListener('keydown', function (ev) {
      shiftHeld = ev.shiftKey;
      var k = String(ev.key || '').toLowerCase(), t = ev.target, tag = t && t.tagName ? t.tagName.toUpperCase() : '';
      var typing = tag === 'TEXTAREA' || tag === 'SELECT' || (tag === 'INPUT' && /^(text|search|number|password|email|url)$/i.test(t.type || 'text'));
      
      
      
      if (k === '?' && !typing) { ev.preventDefault(); window.ccKeys.toggle(); return; }
      if (window.ccKeys.isOpen()) { if (k === 'escape') { ev.preventDefault(); window.ccKeys.close(); } return; }
      
      
      
      
      
      
      if (S.tab === 'groups' && S.hull && !typing && !ev.altKey && !ev.metaKey && /^[1-7]$/.test(k)) {
        var gk = parseInt(k, 10);
        ev.preventDefault();
        if (ev.ctrlKey) { var ga = S.groups[gk - 1]; ga.autofire = !ga.autofire; sfx('ui_button_pressed'); changed(); renderGroups(); document.body.dataset.gkey = 'auto:' + gk + '=' + (ga.autofire ? 1 : 0); return; }
        var gid = S.hoverSlot || S.groupsCursor, grow = gid ? $('groups').querySelector('tr.gw[data-slot="' + gid.replace(/"/g, '') + '"]') : null;
        if (grow) { sfx('ui_button_pressed'); setGroup(gid, gk, ev.shiftKey); document.body.dataset.gkey = gid + ':' + gk; return; }
        sfx('ui_button_disabled_pressed'); document.body.dataset.gkey = 'none:' + gk; return;
      }
      if (k === 'f2') {
        ev.preventDefault();
        if (!window.ccCodex) { return; }
        if (window.ccCodex.isOpen()) { window.ccCodex.close(); return; }
        var hov = document.querySelector('[data-codex]:hover');
        hideTip();
        window.ccCodex.open(hov ? hov.dataset.codex : '');
        return;
      }
      if (window.ccCodex && window.ccCodex.isOpen()) { return; }
      
      
      if (k === 'escape' && !typing) {
        ev.preventDefault();
        if (!$('ctxMenu').hidden) { closeCtx(); } else if (window.ccRender.isOpen()) { window.ccRender.close(); } else if (S.selectedBay != null) { selectBay(null); } else if (S.selectedSlot) { selectSlot(null); } else { hideTip(); }   
        return;
      }
      
      if (!$('ctxMenu').hidden && /^[0-9]$/.test(k) && !ev.ctrlKey && !ev.altKey && !ev.metaKey) { if (ctxKey(k)) { ev.preventDefault(); } return; }
      
      
      
      if (k === ' ' && !typing && !ev.defaultPrevented && !/^(BUTTON|INPUT|A|SUMMARY|SELECT|TEXTAREA|LABEL)$/.test(tag) && !ev.ctrlKey && !ev.altKey && !ev.metaKey) {
        if (spaceAct()) { ev.preventDefault(); return; }
      }
      
      if (k === 'tab' && !typing && !ev.ctrlKey && !ev.altKey && !ev.metaKey) { if (tabStep(ev.shiftKey ? -1 : 1)) { ev.preventDefault(); } return; }
      
      
      if ((k === 'q' || k === 'e') && !typing && !ev.ctrlKey && !ev.altKey && !ev.metaKey && !window.ccRender.isOpen()) { if (tabQE(k === 'e' ? 1 : -1)) { ev.preventDefault(); } return; }   
      
      if ($('rail').contains(t) && !ev.ctrlKey && !ev.altKey && !ev.metaKey && (k === 'arrowdown' || k === 'arrowup' || (!typing && (k === 's' || k === 'w')))) {
        ev.preventDefault(); S.railPromise = railStep((k === 'arrowdown' || k === 's') ? 1 : -1); return;
      }
      
      
      if (WASD[k] && !typing && !ev.ctrlKey && !ev.altKey && !ev.metaKey && S.hull && $('ctxMenu').hidden && !window.ccRender.isOpen()) {
        if (wasdStep(k)) { ev.preventDefault(); }
        return;
      }
      
      
      if (!(ev.ctrlKey || ev.metaKey) || ev.altKey) { return; }
      if (k === 's') { ev.preventDefault(); if (!$('save').disabled) { sfx('ui_button_pressed'); save(); } return; }
      if (typing) { return; }
      
      
      if (k === 'z' && !ev.shiftKey) { ev.preventDefault(); undo(ev.repeat); }
      else if (k === 'y' || (k === 'z' && ev.shiftKey)) { ev.preventDefault(); redo(ev.repeat); }
    });
    document.addEventListener('keyup', function (ev) { shiftHeld = ev.shiftKey; });
    ['scroll', 'wheel'].forEach(function (t) { document.addEventListener(t, function () { hideTip(); closeCtx(); }, true); });   
    document.addEventListener('mousedown', function (ev) { var m = $('ctxMenu'); if (!m.hidden && !m.contains(ev.target)) { closeCtx(); S.ctxClosedAt = Date.now(); } }, true);   
    
    
    
    
    $('dock').addEventListener('click', function (ev) {
      var t = ev.target;
      if (!t || !t.closest || t.closest('button, input, a, label, select, textarea, summary, .wrow, .cdx, #tabs, table')) { return; }
      if (S.ctxClosedAt && Date.now() - S.ctxClosedAt < 400) { return; }
      if (S.tab === 'arm' && t.closest('#tab-arm')) { clearCursorRing(); if (S.selectedSlot) { selectSlot(null); } document.body.dataset.deselect = 'dock'; }   
      else if (S.tab === 'wings' && S.selectedBay != null && t.closest('#tab-wings')) { selectBay(null); document.body.dataset.deselect = 'dock'; }
    });
    attachTip($('bytesInfo'), wireTip, 150, 'above');   
    $('showD').checked = S.showD; document.body.dataset.showd = S.showD ? '1' : '0';   
    $('showD').addEventListener('change', function (ev) { setShowD(ev.target.checked); });
    document.body.dataset.favs = S.favs.join(',');
    document.body.dataset.hullfavs = S.hullFavs.join(',');   
    
    
    function stepSlider(key, rangeId, dir, hold, ten) {
      var h = S.hull;
      if (!h) { return false; }
      var max = key === 'vents' ? h.maxVents : h.maxCaps, cur = S[key], want = cur + dir * (ten ? 10 : 1);
      want = dir > 0 ? Math.min(want, max, cur + Math.max(0, opFree())) : Math.max(want, 0);
      if (want === cur) { return false; }
      bumpOp(want - cur); S[key] = want; $(rangeId).value = want;
      sfx('ui_button_mouseover'); changed(hold === true);
      return true;
    }
    
    
    
    
    
    
    var WHEEL_ROWS = [{ key: 'vents', rangeId: 'ventsRange' }, { key: 'caps', rangeId: 'capsRange' }];
    $('budget').addEventListener('wheel', function (ev) {
      var d = ev.deltaY || ev.deltaX;
      if (!d || ev.ctrlKey || !S.hull) { return; }
      var hit = ev.target && ev.target.closest ? ev.target.closest('.slider') : null, best = null, bestD = Infinity;
      WHEEL_ROWS.forEach(function (r) {
        var row = $(r.rangeId).parentElement;
        if (hit) { if (row === hit) { best = r; } return; }
        var b = row.getBoundingClientRect();
        var dx = Math.max(b.left - ev.clientX, 0, ev.clientX - b.right), dy = Math.max(b.top - ev.clientY, 0, ev.clientY - b.bottom), dist = dx * dx + dy * dy;
        if (dist < bestD) { bestD = dist; best = r; }
      });
      if (!best || $(best.rangeId).disabled) { return; }
      ev.preventDefault(); ev.stopPropagation();   
      stepSlider(best.key, best.rangeId, d < 0 ? 1 : -1, true, ev.shiftKey);
    }, { passive: false });
    function stepper(btnId, key, rangeId, dir) {
      var btn = $(btnId), delay = null, repeat = null;
      function stepOnce(hold) { return stepSlider(key, rangeId, dir, hold, shiftHeld); }
      function stop() { if (delay) { clearTimeout(delay); delay = null; } if (repeat) { clearInterval(repeat); repeat = null; } }
      btn.addEventListener('pointerdown', function (ev) {
        if (ev.button !== 0 || btn.disabled) { return; }
        ev.preventDefault(); shiftHeld = ev.shiftKey;
        stop();
        if (!stepOnce()) { return; }
        delay = setTimeout(function () { repeat = setInterval(function () { if (!stepOnce(true)) { stop(); } }, 125); }, 125);
      });
      ['pointerup', 'pointerleave', 'pointercancel'].forEach(function (t) { btn.addEventListener(t, stop); });
      document.addEventListener('pointerup', stop); window.addEventListener('blur', stop);
      btn.addEventListener('click', function (ev) { if (ev.detail === 0 && !btn.disabled) { stepOnce(); } });   
    }
    stepper('ventsDown', 'vents', 'ventsRange', -1); stepper('ventsUp', 'vents', 'ventsRange', 1);
    stepper('capsDown', 'caps', 'capsRange', -1); stepper('capsUp', 'caps', 'capsRange', 1);
    $('name').addEventListener('input', function (ev) { S.name = ev.target.value; changed(); syncPromoteRow(); });   
    $('save').addEventListener('click', function () { sfx('ui_button_pressed'); save(); });
    $('fly').addEventListener('click', function () { sfx('ui_button_pressed'); saveAndFly(); });   
    $('promoteTop').addEventListener('click', function () { $('catalogBox').open = true; hostPromote(); });   
    $('svprm').addEventListener('click', function () { sfx('ui_button_pressed'); saveAndPromote(); });   
    
    new MutationObserver(function () { var o = $('saveResult'); o.title = o.textContent; }).observe($('saveResult'), { childList: true, characterData: true, subtree: true });
    setMute(savedMute());
    setHoverTicks(savedHoverTicks(), true);   
    $('hoverTick').addEventListener('change', function (ev) { setHoverTicks(ev.target.checked, false); });
    setSfxVol(savedSfxVol(), true);   
    $('sfx').addEventListener('click', function () { setMute(!S.mute); if (!S.mute) { sfx('ui_button_pressed'); } });
    $('sfxVolHdr').addEventListener('input', function (ev) { setSfxVol(ev.target.value, false); });   
    $('sfxVolHdr').addEventListener('change', function () { sfx('ui_refit_slot_filled_ballistic_small'); });
    $('sfxOn').addEventListener('change', function (ev) { setMute(!ev.target.checked); if (!S.mute) { sfx('ui_button_pressed'); } });   
    
    
    
    
    
    var CUE_OUTER = '.hullRow, .wrow, .mrow, tr.gw, tr.ge, .bay, .cxRow, label', CUE_CTRL = 'button, a, select, summary, [role="button"], input[type=checkbox], input[type=radio], input[type=range]';
    
    
    function cueTarget(t, hover) { return t && t.closest && !t.closest('canvas') && !(hover && t.closest('#rail summary')) ? t.closest(CUE_OUTER) || t.closest(CUE_CTRL) : null; }   
    document.addEventListener('pointerover', function (ev) {
      var c = cueTarget(ev.target, true);
      if (c === S.cueHover) { return; }
      S.cueHover = c;
      if (!c || ev.buttons || c.disabled) { return; }
      cueHover();
    });
    document.addEventListener('pointerdown', function () { S.gestureN++; }, true);
    document.addEventListener('click', function () { S.clickT0 = performance.now(); }, true);   
    document.addEventListener('click', function (ev) {
      var c = cueTarget(ev.target), t0 = S.clickT0, g = S.gestureN, det = ev.detail;
      if (!c || c.disabled || c.matches('input[type=range]')) { return; }
      
      
      setTimeout(function () {
        if (S.mute || (S.sfxPressAt || -1e9) >= t0 || (det > 0 && S.sfxGesture === g)) { return; }   
        sfx('ui_button_pressed');
        document.body.dataset.sfxclick = String(S.sfxClickN = (S.sfxClickN || 0) + 1);
      }, 0);
    });
    $('bannerMore').addEventListener('click', function () { var l = $('problems'); l.hidden = !l.hidden; $('bannerMore').textContent = l.hidden ? ('+' + ((S.verdict ? S.verdict.problems.length : 1) - 1) + ' more') : 'hide'; });
    $('tier').addEventListener('change', function (ev) { S.tier = ev.target.value; reloadCatalog(); });
    Array.prototype.forEach.call($('tabs').querySelectorAll('button'), function (b) { b.addEventListener('click', function () { S.armOrigin = null; setTab(b.dataset.tab); b.blur(); }); });   
    
    
    window.ccEditor = { pickHull: function (id) { return pickHull(id); }, catalog: function () { return S.catalog; }, weaponIcon: weaponIcon, hideTip: hideTip };
    
    (function () {
      var card = $('keysCard');
      function open() { hideTip(); closeCtx(); card.hidden = false; $('keysClose').focus(); }   
      function close() { card.hidden = true; }
      window.ccKeys = { toggle: function () { if (card.hidden) { open(); } else { close(); } }, close: close, isOpen: function () { return !card.hidden; } };
      $('keysBtn').addEventListener('click', function () { sfx('ui_button_pressed'); window.ccKeys.toggle(); });
      $('keysClose').addEventListener('click', close);
      card.addEventListener('click', function (ev) { if (ev.target === card) { close(); } });
    })();
    
    
    
    (function () {
      var card = $('renderCard');
      function sync() {
        Array.prototype.forEach.call(card.querySelectorAll('input[name=renderLaw]'), function (r) { r.checked = r.value === S.render; r.parentElement.classList.toggle('def', r.value === RENDER_DEFAULT); });
        var more = $('renderMore'); if (more && !more.open && more.querySelector('input[name=renderLaw]:checked')) { more.open = true; }
        var sv = $('sfxVol'), svn = $('sfxVolN'); if (sv) { sv.value = String(S.sfxVol); } if (svn) { svn.textContent = S.sfxVol + ' %'; }   
      }
      function open() { hideTip(); closeCtx(); card.hidden = false; document.body.dataset.renderpanel = '1'; sync(); renderInfo(); $('renderClose').focus(); }
      function close() { card.hidden = true; document.body.dataset.renderpanel = '0'; }
      window.ccRender = { toggle: function () { if (card.hidden) { open(); } else { close(); } }, open: open, close: close, isOpen: function () { return !card.hidden; }, sync: sync };
      $('renderBtn').addEventListener('click', function () { sfx('ui_button_pressed'); window.ccRender.toggle(); });
      $('renderClose').addEventListener('click', function () { sfx('ui_button_pressed'); close(); });
      card.addEventListener('change', function (ev) { var t = ev.target; if (t && t.name === 'renderLaw' && RENDER_LAWS[t.value]) { sfx('ui_button_pressed'); setRender(t.value, true); } });
      
      card.addEventListener('input', function (ev) { var t = ev.target; if (t && t.id === 'sfxVol') { setSfxVol(t.value, false); } });
      card.addEventListener('change', function (ev) { var t = ev.target; if (t && t.id === 'sfxVol') { sfx('ui_refit_slot_filled_ballistic_small'); } });
    })();
    boot();
  });
})();
