/*
 * x3geolibre bridge content script. Three jobs:
 *
 *  1. GEOLOCATION — the X3 Pro has no GNSS and no Google location services,
 *     so the native side resolves a position (IP-based, city-level) and
 *     streams it over a connectNative port. A page-world script (injected
 *     below — content-script sandbox overrides don't reach page code)
 *     replaces navigator.geolocation with an implementation fed from that
 *     stream, which makes GeoLibre's own locate control work unmodified.
 *     On load we click the MapLibre geolocate control once a position
 *     exists → the map centers on the user.
 *
 *  2. RIGHT-CLICK — native long-press sends {type:'contextmenu', fx, fy}
 *     (viewport fractions); we dispatch a real DOM contextmenu MouseEvent
 *     at that point so GeoLibre's context menus open exactly as if a
 *     mouse right-clicked there.
 *
 *  3. ICON RAIL — a left-edge column of large glasses-friendly buttons
 *     mapping GeoLibre's core functions (search / locate / zoom / compass /
 *     layers / menu / right-click-here) to single cursor taps. Targets the
 *     stable maplibregl-ctrl-* classes first and falls back to aria/title
 *     heuristics for app chrome; anything unfindable toasts instead of
 *     silently failing.
 */
(function () {
  'use strict';

  /* ---------------- native port ---------------- */
  let port = null;
  function connectPort() {
    try {
      port = browser.runtime.connectNative('x3geolibre');
    } catch (e) {
      setTimeout(connectPort, 2000);
      return;
    }
    port.onMessage.addListener(function (msg) {
      if (!msg || !msg.type) return;
      if (msg.type === 'position') {
        lastPos = { lat: msg.lat, lon: msg.lon, label: msg.label || '' };
        window.postMessage({ __x3geo: 'pos', lat: msg.lat, lon: msg.lon, acc: msg.acc || 20000 }, '*');
        if (!autoCentered) centerOnUser(true);
      } else if (msg.type === 'contextmenu') {
        fireContextMenu(msg.fx, msg.fy);
      } else if (msg.type === 'toast') {
        toast(msg.text || '');
      }
    });
    port.onDisconnect.addListener(function () { port = null; });
    try { port.postMessage({ type: 'ready', url: location.href }); } catch (e) {}
  }
  connectPort();

  /* ---------------- page-world geolocation polyfill ---------------- */
  const pageJs = `(${function () {
    var last = null;
    var subs = {};
    var nextId = 1;
    window.addEventListener('message', function (ev) {
      var d = ev.data;
      if (!d || d.__x3geo !== 'pos') return;
      last = {
        coords: {
          latitude: d.lat, longitude: d.lon, accuracy: d.acc,
          altitude: null, altitudeAccuracy: null, heading: null, speed: null
        },
        timestamp: Date.now()
      };
      for (var k in subs) { try { subs[k](last); } catch (e) {} }
    });
    var impl = {
      getCurrentPosition: function (ok, err, opts) {
        if (last) { ok(last); return; }
        window.postMessage({ __x3geo: 'want' }, '*');
        var waited = 0;
        var t = setInterval(function () {
          waited += 250;
          if (last) { clearInterval(t); ok(last); }
          else if (waited > 15000) {
            clearInterval(t);
            if (err) err({ code: 3, message: 'x3geolibre: no position yet' });
          }
        }, 250);
      },
      watchPosition: function (ok, err, opts) {
        var id = nextId++;
        subs[id] = ok;
        if (last) { try { ok(last); } catch (e) {} }
        else window.postMessage({ __x3geo: 'want' }, '*');
        return id;
      },
      clearWatch: function (id) { delete subs[id]; }
    };
    try {
      Object.defineProperty(navigator, 'geolocation', { value: impl, configurable: true });
    } catch (e) { try { navigator.geolocation = impl; } catch (_) {} }
  }})();`;
  function injectPageScript() {
    try {
      const s = document.createElement('script');
      s.textContent = pageJs;
      (document.head || document.documentElement).appendChild(s);
      s.remove();
    } catch (e) {}
  }
  if (document.documentElement) injectPageScript();
  else document.addEventListener('DOMContentLoaded', injectPageScript, { once: true });

  // page asks for a position → relay to native
  window.addEventListener('message', function (ev) {
    var d = ev.data;
    if (d && d.__x3geo === 'want' && port) {
      try { port.postMessage({ type: 'want-position' }); } catch (e) {}
    }
  });

  /* ---------------- auto-center via GeoLibre's place-search ----------------
     GeoLibre ships NO geolocate control (confirmed on-device), but it has a
     "Search places" geocoder. We center on the user by geocoding their city
     into it — city-level is exactly what IP location gives us anyway. */
  var lastPos = null;             // {lat, lon, label}
  var autoCentered = false;
  function findSearchInput() {
    return q(['input[placeholder*="search places" i]', 'input[placeholder*="search" i]',
              'input[type="search"]', 'input[aria-label*="search" i]']);
  }
  function setNativeValue(el, val) {
    var proto = Object.getPrototypeOf(el);
    var desc = proto && Object.getOwnPropertyDescriptor(proto, 'value');
    if (desc && desc.set) desc.set.call(el, val); else el.value = val;
  }
  // Click the first geocoder suggestion whose row starts with the city name.
  // GeoLibre renders results as compact clickable rows (not a standard
  // listbox), so match by text + row shape rather than a fixed selector.
  function clickFirstSuggestion(city) {
    var best = null, bestTop = 1e9;
    var nodes = document.querySelectorAll('[role="option"], li, div, button, a');
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (!visible(el) || el.querySelector('input')) continue;
      var txt = (el.textContent || '').trim();
      if (txt.length < 3 || txt.length > 90) continue;
      if (txt.toLowerCase().indexOf(city) !== 0) continue;      // row starts with the city
      if (el.querySelectorAll('div,li,button,a').length > 5) continue; // a row, not a panel
      var r = el.getBoundingClientRect();
      if (r.height < 8 || r.height > 64 || r.width < 40) continue;
      if (r.top < bestTop) { bestTop = r.top; best = el; }       // topmost = first result
    }
    if (best) { try { best.click(); return true; } catch (e) {} }
    return false;
  }
  function doSearch(input, query, auto) {
    try { input.focus(); } catch (e) {}
    setNativeValue(input, query);
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('change', { bubbles: true }));
    if (auto) autoCentered = true;
    toast('Centering on ' + query);
    var city = query.split(',')[0].trim().toLowerCase();
    var tries = 0;
    var iv = setInterval(function () {
      tries++;
      // some geocoders accept Enter; try it, then click the first suggestion
      ['keydown', 'keyup'].forEach(function (t) {
        input.dispatchEvent(new KeyboardEvent(t, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
      });
      if (clickFirstSuggestion(city) || tries >= 7) clearInterval(iv);
    }, 500);
  }
  function centerOnUser(auto) {
    if (!lastPos) {
      toast('Locating…');
      if (port) { try { port.postMessage({ type: 'want-position' }); } catch (e) {} }
      return;
    }
    var query = lastPos.label || (lastPos.lat.toFixed(5) + ', ' + lastPos.lon.toFixed(5));
    var input = findSearchInput();
    if (input) { doSearch(input, query, auto); return; }
    // search box not mounted yet — open any search toggle, then retry
    clickOr(['button[aria-label*="search" i]', '[data-testid*="search" i]'], null);
    setTimeout(function () {
      var i2 = findSearchInput();
      if (i2) doSearch(i2, query, auto);
      else if (!auto) toast('Search box not found');
    }, 450);
  }
  // the search box mounts late in a React app — retry auto-center when it appears
  var mo = new MutationObserver(function () {
    if (lastPos && !autoCentered && findSearchInput()) centerOnUser(true);
    ensureRail();
  });
  function startObserver() {
    if (!document.documentElement) return;
    mo.observe(document.documentElement, { childList: true, subtree: true });
  }
  startObserver();

  /* ---------------- right-click dispatch ---------------- */
  function fireContextMenu(fx, fy) {
    var x = Math.round((fx || 0.5) * window.innerWidth);
    var y = Math.round((fy || 0.5) * window.innerHeight);
    var el = document.elementFromPoint(x, y) || document.body;
    var base = {
      bubbles: true, cancelable: true, view: window,
      clientX: x, clientY: y, screenX: x, screenY: y, button: 2, buttons: 2
    };
    try {
      if (typeof PointerEvent !== 'undefined') {
        el.dispatchEvent(new PointerEvent('pointerdown', Object.assign({ pointerId: 7, pointerType: 'mouse', isPrimary: true }, base)));
      }
      el.dispatchEvent(new MouseEvent('mousedown', base));
      if (typeof PointerEvent !== 'undefined') {
        el.dispatchEvent(new PointerEvent('pointerup', Object.assign({ pointerId: 7, pointerType: 'mouse', isPrimary: true }, base, { buttons: 0 })));
      }
      el.dispatchEvent(new MouseEvent('mouseup', Object.assign({}, base, { buttons: 0 })));
      el.dispatchEvent(new MouseEvent('contextmenu', base));
    } catch (e) {}
  }

  /* ---------------- icon rail ---------------- */
  function visible(el) {
    if (!el || !el.getClientRects || !el.getClientRects().length) return false;
    var s = getComputedStyle(el);
    return s.visibility !== 'hidden' && s.display !== 'none' && el.offsetWidth > 0;
  }
  function q(list) {
    for (var i = 0; i < list.length; i++) {
      var els = document.querySelectorAll(list[i]);
      for (var j = 0; j < els.length; j++) if (visible(els[j])) return els[j];
    }
    return null;
  }
  function clickOr(list, failMsg) {
    var el = q(list);
    if (el) { try { el.click(); return true; } catch (e) {} }
    if (failMsg) toast(failMsg);
    return false;
  }
  function focusSearch() {
    // a visible search input, or a button that opens search then the input
    var input = q(['input[type="search"]', 'input[placeholder*="search" i]', 'input[aria-label*="search" i]']);
    if (!input) {
      clickOr(['button[aria-label*="search" i]', 'button[title*="search" i]', '[data-testid*="search" i]'], null);
      setTimeout(function () {
        var inp = q(['input[type="search"]', 'input[placeholder*="search" i]', 'input[aria-label*="search" i]']);
        if (inp) { inp.focus(); inp.click(); } else toast('Search box not found');
      }, 350);
      return;
    }
    input.focus();
    input.click();
  }
  var RAIL = [
    { g: '\u{1F50D}', t: 'Search (keyboard + Mic to dictate)', f: focusSearch },
    { g: '◎', t: 'Center on me', f: function () { autoCentered = false; centerOnUser(false); } },
    { g: '＋', t: 'Zoom in', f: function () { clickOr(['.maplibregl-ctrl-zoom-in'], 'Zoom control not found'); } },
    { g: '－', t: 'Zoom out', f: function () { clickOr(['.maplibregl-ctrl-zoom-out'], 'Zoom control not found'); } },
    { g: '⦾', t: 'Reset north', f: function () { clickOr(['.maplibregl-ctrl-compass'], 'Compass not found'); } },
    { g: '▤', t: 'Layers', f: function () {
        clickOr(['button[aria-label*="layer" i]', 'button[title*="layer" i]', '[data-testid*="layer" i]',
                 'button[aria-label*="legend" i]'], 'Layers control not found');
      } },
    { g: '☰', t: 'Menu', f: function () {
        clickOr(['button[aria-label*="menu" i]', 'button[title*="menu" i]', '[data-testid*="menu" i]',
                 'button[aria-label*="navigation" i]'], 'Menu button not found');
      } },
    { g: '⋮', t: 'Right-click here (screen center)', f: function () { fireContextMenu(0.5, 0.5); } }
  ];
  function ensureRail() {
    if (document.getElementById('x3rail') || !document.body) return;
    var rail = document.createElement('div');
    rail.id = 'x3rail';
    rail.style.cssText =
      'position:fixed;left:6px;top:50%;transform:translateY(-50%);z-index:2147483000;' +
      'display:flex;flex-direction:column;gap:6px;';
    RAIL.forEach(function (item) {
      var b = document.createElement('button');
      b.textContent = item.g;
      b.title = item.t;
      b.style.cssText =
        'width:38px;height:38px;border-radius:9px;border:1px solid rgba(127,227,212,.5);' +
        'background:rgba(8,16,20,.86);color:#7fe3d4;font-size:18px;line-height:1;' +
        'cursor:pointer;display:flex;align-items:center;justify-content:center;padding:0;';
      b.addEventListener('mouseenter', function () { b.style.background = 'rgba(18,51,45,.95)'; });
      b.addEventListener('mouseleave', function () { b.style.background = 'rgba(8,16,20,.86)'; });
      b.addEventListener('click', function (ev) { ev.stopPropagation(); try { item.f(); } catch (e) {} });
      rail.appendChild(b);
    });
    document.body.appendChild(rail);
  }
  if (document.body) ensureRail();

  // (diagnostic DOM probe removed after on-device verification)

  /* ---------------- toast ---------------- */
  var toastTimer = null;
  function toast(text) {
    if (!document.body || !text) return;
    var el = document.getElementById('x3toast');
    if (!el) {
      el = document.createElement('div');
      el.id = 'x3toast';
      el.style.cssText =
        'position:fixed;left:50%;bottom:14px;transform:translateX(-50%);z-index:2147483001;' +
        'background:rgba(8,16,20,.92);color:#7fe3d4;border:1px solid rgba(127,227,212,.5);' +
        'border-radius:8px;padding:6px 14px;font:13px/1.4 sans-serif;pointer-events:none;';
      document.body.appendChild(el);
    }
    el.textContent = text;
    el.style.display = 'block';
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.style.display = 'none'; }, 2600);
  }
})();
