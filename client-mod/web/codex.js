







(function () {
  'use strict';
  var base = location.pathname.replace(/[^/]*$/, '');
  var GROUPS = [['tech', 'Tech'], ['size', 'Size'], ['type', 'Type'], ['dmg', 'Damage'], ['apt', 'Aptitude']];
  var SIZE_RANK = { Frigates: 0, Destroyers: 1, Cruisers: 2, Capitals: 3, Stations: 4, Small: 0, Medium: 1, Large: 2, Fighter: 3, Other: 9 };
  var HIST_MAX = 100;

  
  function $(id) { return document.getElementById(id); }
  function el(tag, attrs, children) {
    var e = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (k) {
        if (k === 'class') { e.className = attrs[k]; }
        else if (k === 'text') { e.textContent = attrs[k]; }
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
  function img(src, cls) {
    var im = el('img', { src: src, alt: '', loading: 'lazy' });
    if (cls) { im.className = cls; }
    im.addEventListener('error', function () { im.hidden = true; });   
    return im;
  }
  
  
  function iconBox(lane) {
    var b = el('div', { class: 'ico' });
    if (!lane) { return b; }
    var m = /^sprite\/weapon\/([A-Za-z0-9_.-]+)$/.exec(lane);
    if (m) {
      b.appendChild(img(base + 'sprite/weapon/' + encodeURIComponent(m[1]) + '?kind=turret'));
      b.appendChild(img(base + 'sprite/weapon/' + encodeURIComponent(m[1]) + '?kind=gun', 'gun'));
    } else {
      b.appendChild(img(base + lane));
    }
    return b;
  }
  
  
  
  function fitFigure(fig, box) {
    var imgs = Array.prototype.slice.call(box.querySelectorAll('img'));
    function fit() {
      if (imgs.some(function (i) { return !i.hidden && !(i.complete && i.naturalWidth); })) { return; }
      var live = imgs.filter(function (i) { return !i.hidden; }), dim = 1;
      var avail = (parseInt(getComputedStyle(fig).maxWidth, 10) || 290) - 16;
      live.forEach(function (i) { dim = Math.max(dim, i.naturalWidth, i.naturalHeight); });
      var k = Math.min(4, Math.floor(avail / dim));
      if (k < 2) { return; }
      live.forEach(function (i) { i.style.width = (i.naturalWidth * k) + 'px'; i.style.height = (i.naturalHeight * k) + 'px'; i.classList.add('px'); });
    }
    imgs.forEach(function (i) { i.addEventListener('load', fit); i.addEventListener('error', fit); });
    fit();
  }
  function fmtN(n) { return typeof n === 'number' ? n.toLocaleString('en-US') : String(n == null ? '' : n); }

  
  var C = {
    tree: null, loading: null, unavailable: '', retry: null,
    cats: [], catById: {}, byId: {},
    open: false, cat: null, entry: null, q: '',
    off: {},              
    hist: [], hi: -1,     
    cache: {}, seq: 0
  };

  function load() {
    if (C.tree) { return Promise.resolve(true); }
    if (C.loading) { return C.loading; }
    C.loading = fetch(base + 'codex/tree').then(function (r) {
      return r.json().then(function (j) { return { s: r.status, j: j }; });
    }).then(function (x) {
      C.loading = null;
      if (x.s !== 200 || !x.j || !x.j.cats) { C.unavailable = x.j && x.j.error ? String(x.j.error) : ('HTTP ' + x.s); return false; }
      index(x.j);
      return true;
    }).catch(function (e) { C.loading = null; C.unavailable = e.message; return false; });
    return C.loading;
  }
  function index(t) {
    C.tree = t; C.cats = t.cats || []; C.catById = {}; C.byId = {};
    C.cats.forEach(function (cat) {
      C.catById[cat.id] = cat;
      (cat.entries || []).forEach(function (e) { e.cat = cat.id; C.byId[e.id] = e; });
    });
  }
  function visible(cat) { return (cat.entries || []).filter(function (e) { return C.tree.showLocked || !e.locked; }); }
  function unlocked(e) { return !e.locked; }
  function scope() {   
    if (C.cat && C.catById[C.cat]) { return visible(C.catById[C.cat]); }
    var all = [];
    C.cats.forEach(function (cat) { all = all.concat(visible(cat)); });
    return all;
  }
  function byTitle(a, b) { return String(a.sort || a.title || '').localeCompare(String(b.sort || b.title || '')); }
  function sortRows(cat, rows) {   
    var out = rows.slice();
    if (!cat.retainOrder) { out.sort(function (a, b) { return (a.locked ? 1 : 0) - (b.locked ? 1 : 0) || byTitle(a, b); }); }
    return out;
  }
  function tagLabel(cat, key) { return cat.id === 'fighters' && key === 'type' ? 'Role' : (GROUPS.filter(function (g) { return g[0] === key; })[0] || [key, key])[1]; }
  function tagGroups(cat) {
    var groups = [];
    GROUPS.forEach(function (g) {
      var key = g[0], counts = {}, any = false;
      visible(cat).forEach(function (e) {
        if (e.locked || !e.tags || !e.tags[key]) { return; }
        e.tags[key].forEach(function (v) { if (v) { counts[v] = (counts[v] || 0) + 1; any = true; } });
      });
      if (!any) { return; }
      var values = Object.keys(counts).sort(function (a, b) {
        var ra = SIZE_RANK[a], rb = SIZE_RANK[b];
        if (key === 'size' && ra != null && rb != null) { return ra - rb; }
        return a.localeCompare(b);
      }).map(function (v) { return { v: v, n: counts[v] }; });
      groups.push({ key: key, label: tagLabel(cat, key), values: values });
    });
    return groups;
  }
  function isOff(catId, g, v) { var o = C.off[catId]; return !!(o && o[g] && o[g][v]); }
  function passesTags(e) {   
    var o = C.off[e.cat];
    if (!o || !e.tags) { return true; }
    return Object.keys(e.tags).every(function (g) {
      var vs = e.tags[g];
      if (!vs || !vs.length || !o[g]) { return true; }
      return vs.some(function (v) { return !o[g][v]; });
    });
  }
  function search(q) {   
    var needle = q.toLowerCase(), pre = [], sub = [];
    scope().forEach(function (e) {
      if (e.locked || !passesTags(e)) { return; }
      var t = String(e.title || '').toLowerCase(), i = t.indexOf(needle);
      if (i === 0) { pre.push(e); } else if (i > 0) { sub.push(e); }
    });
    pre.sort(byTitle); sub.sort(byTitle);
    return pre.concat(sub);
  }

  
  function snap() { return { cat: C.cat, entry: C.entry, q: C.q, off: JSON.stringify(C.off), scroll: $('cxList').scrollTop }; }
  function syncSnap() { if (C.hi >= 0) { C.hist[C.hi] = snap(); } }
  function push() {
    C.hist = C.hist.slice(0, C.hi + 1);
    C.hist.push(snap());
    if (C.hist.length > HIST_MAX) { C.hist.shift(); }
    C.hi = C.hist.length - 1;
  }
  function restore(s) {
    C.cat = s.cat; C.entry = s.entry; C.q = s.q;
    try { C.off = JSON.parse(s.off) || {}; } catch (e) { C.off = {}; }
    render({ scroll: s.scroll });
  }
  function hashPairs() {
    return location.hash.replace(/^#/, '').split('&').filter(Boolean).map(function (kv) {
      var i = kv.indexOf('=');
      return [decodeURIComponent(i < 0 ? kv : kv.slice(0, i)), decodeURIComponent(i < 0 ? '' : kv.slice(i + 1))];
    });
  }
  function setHash(codex, q) {
    var pairs = hashPairs().filter(function (p) { return p[0] !== 'codex' && p[0] !== 'q'; });
    if (codex) { pairs.push(['codex', codex]); if (q) { pairs.push(['q', q]); } }
    var h = pairs.map(function (p) { return encodeURIComponent(p[0]) + (p[1] !== '' ? '=' + encodeURIComponent(p[1]) : ''); }).join('&');
    if (location.hash.replace(/^#/, '') !== h) { history.replaceState(null, '', location.pathname + location.search + (h ? '#' + h : '')); }
  }
  function mirror() { if (C.open) { setHash(C.entry ? C.entry : C.cat ? 'cat:' + C.cat : '1', C.q); } }

  
  function go(cat, entry, opts) {
    opts = opts || {};
    syncSnap();
    C.cat = cat; C.entry = entry;
    if (opts.q !== undefined) { C.q = opts.q || ''; } else if (!opts.keepQ) { C.q = ''; }
    push();
    render({});
  }
  function openEntry(id) {
    var e = C.byId[id];
    if (!e || e.locked) { return; }
    go(e.cat, id, { keepQ: e.cat === C.cat });
  }
  function back() { if (C.hi > 0) { syncSnap(); C.hi--; restore(C.hist[C.hi]); } }
  function fwd() { if (C.hi < C.hist.length - 1) { syncSnap(); C.hi++; restore(C.hist[C.hi]); } }
  function up() { if (C.entry) { go(C.cat, null, { keepQ: true }); } else if (C.cat) { go(null, null, {}); } }
  function random() {
    var pool = scope().filter(unlocked);
    if (pool.length) { openEntry(pool[Math.floor(Math.random() * pool.length)].id); }
  }
  function setSearch(q) {
    C.q = q;
    var f = $('cxSearch');
    if (f.value !== q) { f.value = q; }
    syncSnap(); renderList(); renderHead(); mirror();
  }
  function focusSearch() { var f = $('cxSearch'); f.focus(); f.select(); }
  function toggleTag(catId, g, v, solo) {
    var o = C.off[catId] = C.off[catId] || {};
    var og = o[g] = o[g] || {};
    if (solo) {
      var all = tagGroups(C.catById[catId]).filter(function (x) { return x.key === g; })[0];
      Object.keys(og).forEach(function (k) { delete og[k]; });
      if (all) { all.values.forEach(function (x) { if (x.v !== v) { og[x.v] = true; } }); }
    } else if (og[v]) { delete og[v]; } else { og[v] = true; }
    syncSnap(); renderList(); renderTags();
  }

  
  function render(opts) {
    renderHead(); renderList(); renderTags(); renderDetail(); renderNav(); mirror();
    if (opts && typeof opts.scroll === 'number') { $('cxList').scrollTop = opts.scroll; }
    else { var sel = $('cxList').querySelector('.cxRow.sel'); if (sel && sel.scrollIntoView) { sel.scrollIntoView({ block: 'nearest' }); } }
  }
  function renderHead() {
    var cat = C.cat ? C.catById[C.cat] : null, ic = $('cxHeadIcon');
    $('cxHeadTitle').textContent = cat ? cat.title : 'Codex';
    if (cat) { ic.src = base + cat.icon; ic.hidden = false; } else { ic.hidden = true; ic.removeAttribute('src'); }
    var n = cat ? visible(cat).filter(unlocked).length : scope().filter(unlocked).length;
    $('cxHeadN').textContent = C.q ? '' : String(n);
  }
  function rowEl(e, right, cls) {
    var locked = !!e.locked;
    var attrs = { class: 'cxRow' + (cls || '') + (locked ? ' locked' : '') + (C.entry === e.id ? ' sel' : ''), dataset: { id: e.id, kind: e.kind || '' } };
    if (locked) { attrs.title = 'Locked entry — the game has not unlocked it yet'; }
    var row = el('div', attrs, [
      iconBox(locked ? 'sprite/codex/locked_entry' : e.icon),
      el('span', { class: 't', text: locked ? 'Locked entry' : e.title }),
      right ? el('span', { class: 'c', text: right }) : null
    ]);
    if (!locked) { row.addEventListener('click', function () { openEntry(e.id); }); }
    return row;
  }
  function renderList() {
    var box = clear($('cxList'));
    if (C.q) {
      var hits = search(C.q);
      box.appendChild(el('div', { class: 'cxNote', text: hits.length + (hits.length === 1 ? ' match' : ' matches') + (C.cat ? '' : ' in every category') }));
      hits.forEach(function (e) { box.appendChild(rowEl(e, !C.cat && C.catById[e.cat] ? C.catById[e.cat].title : '', ' hit')); });   
      return;
    }
    if (!C.cat) {
      C.cats.forEach(function (cat) {
        var row = el('div', { class: 'cxRow cat', dataset: { id: cat.id } }, [
          iconBox(cat.icon), el('span', { class: 't', text: cat.title }), el('span', { class: 'c', text: String(visible(cat).filter(unlocked).length) })]);
        row.addEventListener('click', function () { go(cat.id, null, {}); });
        box.appendChild(row);
      });
      return;
    }
    var cat = C.catById[C.cat], rows = visible(cat).filter(passesTags);
    var tops = sortRows(cat, rows.filter(function (e) { return !e.parent; }));
    var kids = {};
    rows.forEach(function (e) { if (e.parent) { (kids[e.parent] = kids[e.parent] || []).push(e); } });
    if (!tops.length) { box.appendChild(el('div', { class: 'cxNote', text: 'nothing matches the tags below' })); }
    tops.forEach(function (e) {
      box.appendChild(rowEl(e, ''));
      (kids[e.id] ? sortRows(cat, kids[e.id]) : []).forEach(function (m) { box.appendChild(rowEl(m, '', ' sub')); });   
    });
  }
  function renderTags() {
    var box = clear($('cxTags')), cat = C.cat ? C.catById[C.cat] : null, groups = cat ? tagGroups(cat) : [];
    box.hidden = !groups.length;
    groups.forEach(function (g) {
      var grp = el('div', { class: 'grp', dataset: { group: g.key } }, [el('span', { class: 'g', text: g.label })]);
      g.values.forEach(function (v) {
        var chip = el('button', { type: 'button', class: 'chip' + (isOff(cat.id, g.key, v.v) ? ' off' : ''), title: 'click: toggle · ctrl-click: only this one', dataset: { v: v.v } },
          [v.v, el('span', { class: 'n', text: String(v.n) })]);
        chip.addEventListener('click', function (ev) { toggleTag(cat.id, g.key, v.v, ev.ctrlKey || ev.metaKey); });
        grp.appendChild(chip);
      });
      box.appendChild(grp);
    });
  }
  function renderNav() {
    $('cxBack').disabled = C.hi <= 0;
    $('cxFwd').disabled = C.hi >= C.hist.length - 1;
    $('cxUp').disabled = !C.cat && !C.entry;
    var f = $('cxSearch');
    if (f.value !== C.q) { f.value = C.q; }
    f.setAttribute('value', C.q);   
  }
  function keysGrid() {
    var rows = [['Q or ←', 'back'], ['W or →', 'forward'], ['E or ↑', 'up one level'], ['R', 'a random entry'], ['Ctrl-F or /', 'search'], ['Esc', 'clear the search, else close'], ['F2', 'open the codex from the editor — over a hull, weapon, hullmod or wing row: that entry']];
    var g = el('div', { class: 'keys' });
    rows.forEach(function (r) { g.appendChild(el('b', { text: r[0] })); g.appendChild(el('span', { text: r[1] })); });
    return g;
  }
  function renderDetail() {
    var body = clear($('cxBody')), rel = clear($('cxRelated'));
    rel.hidden = !C.entry; $('cxDetail').classList.toggle('noRel', !C.entry);   
    if (C.entry) { showEntry(C.entry); return; }
    if (C.cat) {
      var cat = C.catById[C.cat], n = visible(cat).filter(unlocked).length;
      body.appendChild(el('div', { class: 'cxWelcome' }, [
        el('h2', { text: cat.title }),
        el('p', { text: n + (n === 1 ? ' entry' : ' entries') + '. Pick one from the list, search with Ctrl-F, or narrow the list with the tags below.' }),
        keysGrid()]));
      return;
    }
    var total = scope().filter(unlocked).length;
    body.appendChild(el('div', { class: 'cxWelcome' }, [
      el('h2', { text: 'Codex' }),
      el('p', { text: 'The game’s codex: every ship, station, fighter wing, weapon, hullmod, ship system and skill it knows, with the numbers its refit screen shows. Pick a category on the left, or search.' }),
      el('p', { text: total + ' entries in ' + C.cats.length + ' categories' + (C.tree.unlocked ? '' : ' (locked entries are the ones the game has not unlocked yet)') + '.' }),
      keysGrid()]));
  }
  function paintUnavailable() {
    var body = clear($('cxBody'));
    body.appendChild(el('div', { class: 'cxWelcome' }, [el('h2', { text: 'Codex' }),
      el('p', { class: 'para err', text: 'The game has not built its codex yet (it does at the title screen): ' + C.unavailable + ' — asking again in 3 s.' })]));
    if (C.retry) { clearTimeout(C.retry); }
    C.retry = setTimeout(function () { C.retry = null; if (C.open) { open('', {}); } }, 3000);
  }

  
  
  
  
  
  
  function entry(id, forBuild) {
    var key = (forBuild ? 'b:' : '') + id;
    if (C.cache[key]) { return Promise.resolve(C.cache[key]); }
    if (!C.pending) { C.pending = {}; }
    if (C.pending[key]) { return C.pending[key]; }
    var p = fetch(base + 'codex/entry/' + encodeURIComponent(id) + (forBuild ? '?b=1' : ''), { cache: 'no-store' }).then(function (r) {
      return r.json().then(function (j) { return { s: r.status, j: j }; }, function () { return { s: r.status, j: null }; });
    }).then(function (x) {
      var doc = x.s === 200 && x.j ? x.j : { id: id, kind: '', title: x.s === 423 ? 'Locked entry' : x.s === 404 ? 'No such entry' : 'Entry unavailable',
        status: x.s, note: x.s === 423 ? 'The game has not unlocked this entry yet.' : x.s === 404 ? 'The game’s codex has no entry named ' + id + '.' : (x.j && x.j.error ? String(x.j.error) : 'HTTP ' + x.s),
        paras: [], sections: [], lists: [], factions: [], related: [] };
      if (x.s === 200 || x.s === 423 || x.s === 404) { C.cache[key] = doc; }
      delete C.pending[key];
      return doc;
    }, function (e) { delete C.pending[key]; throw e; });
    C.pending[key] = p;
    return p;
  }
  function showEntry(id) {
    var seq = ++C.seq, body = $('cxBody');
    if (C.cache[id]) { paintEntry(C.cache[id]); return; }
    body.appendChild(el('div', { class: 'cxNote', text: 'loading…' }));
    entry(id).then(function (doc) {
      if (seq === C.seq && C.entry === id) { paintEntry(doc); }
    }).catch(function (e) {
      if (seq === C.seq && C.entry === id) { clear(body).appendChild(el('p', { class: 'para err', text: 'the codex page could not load: ' + e.message })); }
    });
  }
  
  
  function detailEl(doc) {
    var box = el('div', { class: 'cxDetail' }), ps = el('div', { class: 'cxParas' });
    (doc.paras || []).forEach(function (p) {
      if (!p || !p.t) { return; }
      ps.appendChild(p.style === 'head' ? el('h3', { class: 'para head', text: p.t }) : el('p', { class: 'para' + (p.style ? ' ' + p.style : ''), text: p.t }));
    });
    if (ps.children.length) { box.appendChild(ps); }
    if (doc.sections && doc.sections.length) { box.appendChild(sectionsEl(doc.sections)); }
    if (doc.lists && doc.lists.length) { box.appendChild(listsEl(doc.lists)); }
    return box;
  }
  function linkTo(id, text) {
    if (!id || !C.byId[id] || C.byId[id].locked) { return el('span', { text: text }); }
    var a = el('span', { class: 'lnk', text: text, dataset: { id: id }, title: 'open the codex entry' });
    a.addEventListener('click', function () { openEntry(id); });
    return a;
  }
  function sectionsEl(secs) {
    var box = el('div', { class: 'cxSecs' });
    (secs || []).forEach(function (s) {
      var t = el('table');
      (s.rows || []).forEach(function (r) {
        var flag = !r.k;
        var tr = el('tr', { class: 'kv' + (flag ? ' flag' : '') });
        if (flag) { tr.appendChild(el('td', { class: 'k', colspan: '2', text: r.v })); }
        else { tr.appendChild(el('td', { class: 'k', text: r.k })); tr.appendChild(el('td', r.hint ? { class: 'v', text: r.v, title: r.hint } : { class: 'v', text: r.v })); }
        t.appendChild(tr);
      });
      box.appendChild(el('div', { class: 'sec' }, [el('h3', { text: s.title }), t]));
    });
    return box;
  }
  function listsEl(lists) {
    var box = el('div', { class: 'cxLists' });
    (lists || []).forEach(function (l) {
      var ul = el('ul');
      (l.items || []).forEach(function (it) { ul.appendChild(el('li', {}, [linkTo(it.id, it.t)])); });
      box.appendChild(el('div', { class: 'lst' }, [el('h3', { text: l.title }), ul]));
    });
    return box;
  }
  function parasEl(e) {
    var box = el('div', { class: 'cxParas' });
    (e.paras || []).forEach(function (p) {
      if (!p || !p.t) { return; }
      box.appendChild(p.style === 'head' ? el('h3', { class: 'para head', text: p.t }) : el('p', { class: 'para' + (p.style ? ' ' + p.style : ''), text: p.t }));
    });
    if (e.baseValue != null) { box.appendChild(el('p', { class: 'para base', text: 'Base value: ' + fmtN(e.baseValue) })); }
    if (e.factions && e.factions.length) {
      var f = el('div', { class: 'cxFactions' }, [el('span', { class: 'k', text: 'Used by' })]);
      e.factions.forEach(function (x) { f.appendChild(el('span', x.color ? { class: 'f', text: x.name, style: 'color:' + x.color } : { class: 'f', text: x.name })); });
      box.appendChild(f);
    }
    return box;
  }
  function editButton(hullId) {   
    var ed = window.ccEditor, hulls = ed && ed.catalog ? (ed.catalog().hulls || []) : [];
    var h = hulls.filter(function (x) { return x.id === hullId; })[0], why = '';
    if (!ed || !ed.pickHull) { why = 'the editor is not loaded'; }
    else if (hulls.length && !h) { why = 'not in this tier’s catalog'; }
    else if (h && !h.allowed) { why = 'not allowed: ' + (h.reason || 'tier'); }
    var btn = el('button', { type: 'button', class: 'primary cxEdit', text: 'Edit in the variant maker', dataset: { hull: hullId } });
    if (why) { btn.disabled = true; btn.title = why; }
    else { btn.addEventListener('click', function () { close(); ed.pickHull(hullId); }); }
    return el('div', { class: 'cxEditBox' }, [btn, why ? el('div', { class: 'cxEditWhy', text: why }) : null]);
  }
  function paintEntry(e) {
    var body = clear($('cxBody')), rel = clear($('cxRelated'));
    var big = !!e.sprite || e.kind === 'weapon';   
    body.appendChild(el('div', { class: 'cxTitle' }, [
      big ? null : iconBox(e.icon),
      el('div', { class: 'who' }, [el('h2', { text: e.title }), e.subtitle ? el('div', { class: 'sub', text: e.subtitle }) : null]),
      e.edit && e.edit.hullId ? editButton(e.edit.hullId) : null]));
    if (e.note) { body.appendChild(el('p', { class: 'para' + (e.status === 423 ? '' : ' err'), text: e.note })); }
    if (e.detailError) { body.appendChild(el('p', { class: 'para err', text: 'The game could not build this page: ' + e.detailError })); }
    var main = el('div'), top = el('div', { class: 'cxTop' }, [main]);
    var figBox = big ? (e.sprite ? img(base + e.sprite) : iconBox(e.icon)) : null, fig = big ? el('figure', { class: 'cxFig' }, [figBox]) : null;
    if (fig) { top.appendChild(fig); }
    body.appendChild(top);
    if (fig && !e.sprite) { fitFigure(fig, figBox); }   
    var secs = sectionsEl(e.sections), lists = listsEl(e.lists), paras = parasEl(e);
    
    
    
    var order = e.kind === 'hullmod' ? [paras, secs, lists] : e.kind === 'system' ? [secs, paras, lists] : e.kind === 'skill' ? [paras, lists, secs] : [secs, lists, paras];
    order.forEach(function (x) { if (x.firstChild) { main.appendChild(x); } });
    rel.appendChild(el('h3', { text: 'Related entries' }));
    if (!e.related || !e.related.length) { rel.appendChild(el('div', { class: 'cxNote', text: e.status ? '' : 'none' })); }
    (e.related || []).forEach(function (r) { rel.appendChild(rowEl({ id: r.id, kind: r.kind, title: r.title, icon: r.icon, cat: '' }, r.cat || '')); });
  }

  
  function open(target, opts) {
    opts = opts || {};
    var wasOpen = C.open;
    if (!wasOpen) {
      C.open = true;
      document.body.dataset.view = 'codex';
      $('editor').hidden = true; $('codex').hidden = false;
      $('codexBtn').classList.add('on');
      var tip = $('tip'); if (tip) { tip.hidden = true; }
    }
    load().then(function (ok) {
      if (!C.open) { return; }
      if (!ok) { paintUnavailable(); return; }
      var t = target == null ? '' : String(target);
      if (t === '' || t === '1') {
        if (C.hi < 0 || (t === '1' && (C.cat || C.entry || C.q))) { go(null, null, {}); } else { render({}); }   
      } else if (t.indexOf('cat:') === 0) {
        var cid = t.slice(4);
        if (C.catById[cid]) { go(cid, null, { q: opts.q || '' }); } else { go(null, null, {}); }
      } else if (C.byId[t] && !C.byId[t].locked) {
        go(C.byId[t].cat, t, { q: opts.q || '' });
      } else {
        go(null, null, { q: opts.q || '' });
        if (t) { $('cxList').insertBefore(el('div', { class: 'cxNote', text: (C.byId[t] ? 'that entry is locked: ' : 'no codex entry named ') + t }), $('cxList').firstChild); }
      }
    });
  }
  function close() {
    if (!C.open) { return; }
    C.open = false;
    if (C.retry) { clearTimeout(C.retry); C.retry = null; }
    delete document.body.dataset.view;
    $('codex').hidden = true; $('editor').hidden = false;
    $('codexBtn').classList.remove('on');
    setHash(null, '');
    window.dispatchEvent(new Event('resize'));   
  }
  function isOpen() { return C.open; }

  
  $('codexBtn').addEventListener('click', function () { if (C.open) { close(); } else { open('', {}); } });
  $('cxClose').addEventListener('click', close);
  $('cxBack').addEventListener('click', back);
  $('cxFwd').addEventListener('click', fwd);
  $('cxUp').addEventListener('click', up);
  $('cxRand').addEventListener('click', random);
  $('cxKeys').addEventListener('click', function () { if (window.ccKeys) { window.ccKeys.toggle(); } });   
  $('cxSearch').addEventListener('input', function (ev) { setSearch(ev.target.value); });
  window.addEventListener('hashchange', function () {
    var h = {}; hashPairs().forEach(function (p) { h[p[0]] = p[1]; });
    if (h.codex) { open(h.codex, { q: h.q || '' }); } else if (C.open) { close(); }
  });
  document.addEventListener('keydown', function (ev) {
    if (!C.open) { return; }
    if (window.ccKeys && window.ccKeys.isOpen()) { return; }   
    var k = String(ev.key || ''), t = ev.target, inSearch = t === $('cxSearch');
    if (!inSearch && t && /^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName || '')) { return; }
    if ((ev.ctrlKey || ev.metaKey) && k.toLowerCase() === 'f') { ev.preventDefault(); focusSearch(); return; }
    if (ev.ctrlKey || ev.metaKey || ev.altKey) { return; }
    if (inSearch) {
      if (k === 'Escape') { ev.preventDefault(); if (C.q) { setSearch(''); } else { t.blur(); close(); } }
      else if (k === 'Enter') { ev.preventDefault(); var first = $('cxList').querySelector('.cxRow.hit'); if (first) { first.click(); } }
      return;
    }
    var lk = k.toLowerCase();
    if (k === 'Escape') { ev.preventDefault(); if (C.q) { setSearch(''); } else { close(); } }
    else if (lk === 'q' || k === 'ArrowLeft') { ev.preventDefault(); back(); }
    else if (lk === 'w' || k === 'ArrowRight') { ev.preventDefault(); fwd(); }
    else if (lk === 'e' || k === 'ArrowUp') { ev.preventDefault(); up(); }
    else if (lk === 'r') { ev.preventDefault(); random(); }
    else if (k === '/') { ev.preventDefault(); focusSearch(); }
  });

  window.ccCodex = { open: open, openEntry: openEntry, close: close, isOpen: isOpen,
    back: back, fwd: fwd, canBack: function () { return C.hi > 0; },
    entry: entry, detailEl: detailEl };   
})();
