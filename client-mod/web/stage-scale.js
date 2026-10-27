





(function () {
  'use strict';
  
  var LAWS = { smooth: 'Smooth', block: 'Crisp', lanczos: 'Lanczos', half: 'Half steps', whole: 'Whole steps', chunky: 'Chunky', box: 'Box', sharp: 'Nearest' }, DEFAULT = 'whole';   
  function saved() { try { var v = localStorage.getItem('cc_editor_render'); return LAWS[v] ? v : DEFAULT; } catch (e) { return DEFAULT; } }
  
  
  
  
  
  
  var preCache = {}, PRE_MAX = 6;
  function preScaled(img, step) {
    var c = preCache[img.src];
    if (!c || c.step !== step) {
      c = { step: step, canvas: document.createElement('canvas') }; c.canvas.width = img.width * step; c.canvas.height = img.height * step;
      var cc = c.canvas.getContext('2d'); cc.imageSmoothingEnabled = false; cc.drawImage(img, 0, 0, c.canvas.width, c.canvas.height);
      preCache[img.src] = c;
    }
    return c.canvas;
  }
  
  
  
  
  
  
  
  function boxScaled(img, w, hh) {
    var c = preCache[img.src], key = 'box@' + w + 'x' + hh;
    if (c && c.key === key) { return c.canvas; }
    var sw = img.width, sh = img.height, src = document.createElement('canvas'); src.width = sw; src.height = sh;
    var sc = src.getContext('2d'); sc.drawImage(img, 0, 0);
    var sd = sc.getImageData(0, 0, sw, sh).data, out = document.createElement('canvas'); out.width = w; out.height = hh;
    var oc = out.getContext('2d'), od = oc.createImageData(w, hh), dd = od.data;
    function spans(n, m) {   
      var r = [], f = m / n;
      for (var i = 0; i < n; i++) { var a = i * f, b = (i + 1) * f, s0 = Math.floor(a), s1 = Math.min(Math.ceil(b), m), ws = [s0]; for (var s = s0; s < s1; s++) { ws.push(Math.min(b, s + 1) - Math.max(a, s)); } r.push(ws); }
      return r;
    }
    var cx = spans(w, sw), cy = spans(hh, sh), area = (sw / w) * (sh / hh), tmp = new Float32Array(w * sh * 4);
    for (var y = 0; y < sh; y++) {   
      for (var i = 0; i < w; i++) {
        var ws = cx[i], r = 0, g = 0, b = 0, a = 0;
        for (var k = 1; k < ws.length; k++) { var si = (y * sw + ws[0] + k - 1) * 4, al = sd[si + 3] * ws[k]; r += sd[si] * al; g += sd[si + 1] * al; b += sd[si + 2] * al; a += al; }
        var ti = (y * w + i) * 4; tmp[ti] = r; tmp[ti + 1] = g; tmp[ti + 2] = b; tmp[ti + 3] = a;
      }
    }
    for (var j = 0; j < hh; j++) {   
      var wy = cy[j];
      for (var i2 = 0; i2 < w; i2++) {
        var r2 = 0, g2 = 0, b2 = 0, a2 = 0;
        for (var k2 = 1; k2 < wy.length; k2++) { var ti2 = ((wy[0] + k2 - 1) * w + i2) * 4, wt = wy[k2]; r2 += tmp[ti2] * wt; g2 += tmp[ti2 + 1] * wt; b2 += tmp[ti2 + 2] * wt; a2 += tmp[ti2 + 3] * wt; }
        if (a2 > 0) { var di = (j * w + i2) * 4; dd[di] = r2 / a2; dd[di + 1] = g2 / a2; dd[di + 2] = b2 / a2; dd[di + 3] = a2 / area; }
      }
    }
    oc.putImageData(od, 0, 0);
    preCache[img.src] = { key: key, canvas: out };
    return out;
  }
  
  
  
  
  
  function lanczosScaled(img, tw, th) {
    var c = preCache[img.src], key = 'lz@' + tw + 'x' + th;
    if (c && c.key === key) { return c.canvas; }
    var sw = img.width, sh = img.height, src = document.createElement('canvas'); src.width = sw; src.height = sh;
    var sc = src.getContext('2d'); sc.drawImage(img, 0, 0);
    var sd = sc.getImageData(0, 0, sw, sh).data, out = document.createElement('canvas'); out.width = tw; out.height = th;
    var oc = out.getContext('2d'), od = oc.createImageData(tw, th), dd = od.data;
    function L(t) { if (t === 0) { return 1; } if (t <= -3 || t >= 3) { return 0; } var p = Math.PI * t; return 3 * Math.sin(p) * Math.sin(p / 3) / (p * p); }
    function taps(n, m) {   
      var f = m / n, r = Math.max(f, 1), rows = [];
      for (var i = 0; i < n; i++) {
        var cc = (i + 0.5) * f - 0.5, lo = Math.max(0, Math.floor(cc - 3 * r + 1)), hi = Math.min(m - 1, Math.floor(cc + 3 * r)), ws = [lo], sum = 0;
        for (var s = lo; s <= hi; s++) { var wt = L((s - cc) / r); ws.push(wt); sum += wt; }
        for (var k = 1; k < ws.length; k++) { ws[k] /= sum; }
        rows.push(ws);
      }
      return rows;
    }
    var tx = taps(tw, sw), ty = taps(th, sh), tmp = new Float32Array(tw * sh * 4);
    for (var y = 0; y < sh; y++) {   
      for (var i = 0; i < tw; i++) {
        var ws = tx[i], r = 0, g = 0, b = 0, a = 0;
        for (var k = 1; k < ws.length; k++) { var si = (y * sw + ws[0] + k - 1) * 4, al = sd[si + 3] * ws[k]; r += sd[si] * al; g += sd[si + 1] * al; b += sd[si + 2] * al; a += al; }
        var ti = (y * tw + i) * 4; tmp[ti] = r; tmp[ti + 1] = g; tmp[ti + 2] = b; tmp[ti + 3] = a;
      }
    }
    for (var j = 0; j < th; j++) {   
      var wy = ty[j];
      for (var i2 = 0; i2 < tw; i2++) {
        var r2 = 0, g2 = 0, b2 = 0, a2 = 0;
        for (var k2 = 1; k2 < wy.length; k2++) { var ti2 = ((wy[0] + k2 - 1) * tw + i2) * 4, wt2 = wy[k2]; r2 += tmp[ti2] * wt2; g2 += tmp[ti2 + 1] * wt2; b2 += tmp[ti2 + 2] * wt2; a2 += tmp[ti2 + 3] * wt2; }
        if (a2 < 1) { continue; }
        
        
        var di = (j * tw + i2) * 4, m = 255 * a2;
        dd[di] = Math.min(Math.max(r2, 0), m) / a2; dd[di + 1] = Math.min(Math.max(g2, 0), m) / a2; dd[di + 2] = Math.min(Math.max(b2, 0), m) / a2; dd[di + 3] = Math.min(a2, 255);
      }
    }
    oc.putImageData(od, 0, 0);
    preCache[img.src] = { key: key, canvas: out };
    return out;
  }
  function clear() { preCache = {}; }
  window.ccStageScale = { LAWS: LAWS, DEFAULT: DEFAULT, saved: saved, PRE_MAX: PRE_MAX, preScaled: preScaled, boxScaled: boxScaled, lanczosScaled: lanczosScaled, clear: clear };
})();
