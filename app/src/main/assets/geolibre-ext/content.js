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
 *  2. RIGHT-CLICK — native double tap sends {type:'contextmenu', fx, fy}
 *     (viewport fractions); we dispatch a real DOM contextmenu MouseEvent
 *     at that point so GeoLibre's context menus open exactly as if a
 *     mouse right-clicked there.
 *
 *  3. AI ASSISTANT — triple tap toggles GeoLibre's "GeoAgent" assistant. If the
 *     plugin isn't mounted yet we drive Plugins ▸ GeoAgent ▸ Activate once; after
 *     that the header button just collapses/expands it.
 *
 *  4. DARK MODE — GeoLibre themes from prefers-color-scheme; we also pin the
 *     <html class="dark"> so it stays dark even if the app re-derives the theme.
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
        // Double tap while the AI window is showing closes it instead of
        // opening a right-click menu underneath it.
        if (assistantOpen()) {
          nativeTap(document.querySelector(CLOSE_SEL));
          try { port.postMessage({ type: 'assistant-closed' }); } catch (e) {}
        } else {
          fireContextMenu(msg.fx, msg.fy);
        }
      } else if (msg.type === 'toggle-assistant') {
        toggleAssistant();
      } else if (msg.type === 'ensure-assistant') {
        ensureAssistant();
      } else if (msg.type === 'focus-prompt') {
        focusPrompt();
      } else if (msg.type === 'live-on') {
        liveOn();
      } else if (msg.type === 'live-off') {
        liveOff();
      } else if (msg.type === 'gemini-key') {
        applyGeminiKey(msg.key);
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
    // GeoLibre's service worker rejects the OpenFreeMap style request in
    // GeckoView even though the endpoint is reachable and CORS-enabled. Supply
    // a small raster style synchronously so MapLibre always has a basemap.
    var originalFetch = window.fetch.bind(window);
    // Raster fallback styles for EVERY OpenFreeMap basemap name set_basemap can
    // request (liberty, liberty-3d, bright, positron, dark, fiord) — the vector
    // styles all fail the same way liberty did in GeckoView, so "switch to a
    // dark basemap" would blank the map. Serve a look-alike raster instead:
    // OSM for street styles, Carto light/dark for positron/dark/fiord.
    function x3MakeFallbackStyle(name, tilesUrl, attribution) {
      return {
        version: 8,
        name: name + ' (X3 raster fallback)',
        sources: {
          'x3-basemap': {
            type: 'raster',
            tiles: [tilesUrl],
            tileSize: 256,
            maxzoom: 19,
            attribution: attribution
          },
          // Real elevation, pre-seeded under the name the AI's generated code
          // always uses (map.setTerrain({source:'mapbox-dem'})); stock GeoLibre
          // has no such source and the model's mapbox:// retry is token-gated.
          // Keyless AWS terrarium DEM → "show the map in 3D" just works.
          // Costs nothing until terrain is actually enabled.
          'mapbox-dem': {
            type: 'raster-dem',
            tiles: ['https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png'],
            encoding: 'terrarium',
            tileSize: 256,
            maxzoom: 15,
            attribution: 'Terrain © Mapzen/AWS'
          }
        },
        layers: [
          { id: 'x3-basemap', type: 'raster', source: 'x3-basemap', minzoom: 0, maxzoom: 22 }
        ]
      };
    }
    var X3_OSM_TILES = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';
    var X3_CARTO_LIGHT = 'https://basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png';
    var X3_CARTO_DARK = 'https://basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png';
    function x3StyleFor(styleName) {
      var n = (styleName || '').toLowerCase();
      if (n === 'dark' || n === 'fiord') {
        return x3MakeFallbackStyle(n, X3_CARTO_DARK, '© OpenStreetMap contributors © CARTO');
      }
      if (n === 'positron') {
        return x3MakeFallbackStyle(n, X3_CARTO_LIGHT, '© OpenStreetMap contributors © CARTO');
      }
      return x3MakeFallbackStyle(n || 'liberty', X3_OSM_TILES, '© OpenStreetMap contributors');
    }
    var X3_STYLE_RX = /^https:\/\/tiles\.openfreemap\.org\/styles\/(liberty-3d|liberty|bright|positron|dark|fiord)(?:[/?#]|$)/i;
    // GeoLibre's AI Assistant hardcodes gemini-3.5-flash, which keeps getting
    // rejected under high demand (overload/quota) and can't be changed from
    // settings. Rewrite the model in outgoing Gemini requests to a more liberal
    // GA model on the wire — transparent to GeoLibre, same response shape.
    var X3_AI_FROM = 'gemini-3.5-flash';
    var X3_AI_TO = 'gemini-2.5-flash';
    function x3RewriteModel(url) {
      if (url && url.indexOf('generativelanguage.googleapis.com') >= 0 &&
          url.indexOf(X3_AI_FROM) >= 0) {
        return url.split(X3_AI_FROM).join(X3_AI_TO);
      }
      return url;
    }
    // The assistant keeps "succeeding" at things that render nothing here: it
    // adds single STAC items that don't cover the view (tile 404s happen in a
    // worker, so it never sees them) and writes Mapbox-tutorial code. Since its
    // API calls pass through this fetch hook anyway (that's how the model swap
    // works), append environment notes to its system prompt teaching it the
    // approaches that actually work in this build. Tag guards double-insertion.
    var X3_ENV_NOTES = '\n\n[X3GEO-ENV] Notes about THIS deployment (follow strictly):\n' +
      '1. When the user asks for satellite / aerial / photorealistic / "real" imagery or a ' +
      '"bird\'s-eye view", they mean a global satellite basemap. Do NOT use add_stac_layer for ' +
      'this (single STAC items rarely cover the current view and their tiles 404 silently). ' +
      'Use add_tile_layer with this XYZ template:\n' +
      'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}\n' +
      '(name it "Satellite"). Only use add_stac_layer when the user explicitly wants a specific ' +
      'scene/date/collection (e.g. "latest Sentinel-2", "cloud-free Landsat from June").\n' +
      '2. For 3D / terrain / tilt requests: a raster-dem source named "mapbox-dem" ALREADY EXISTS ' +
      "in the style. Just run map.setTerrain({source:'mapbox-dem',exaggeration:1.5}); " +
      "map.easeTo({pitch:60,duration:1500}); via run_maplibre_js. NEVER call addSource with " +
      'mapbox:// URLs — there is no Mapbox token and they always fail. "Flat again" / "2D" = ' +
      'map.setTerrain(null); map.easeTo({pitch:0}).\n' +
      '3. Temperature / heat-map requests — pick by scale: (a) city/region view → Landsat via ' +
      'add_stac_layer with the lwir11 band + colormap (never the default true-color preset); ' +
      '(b) GLOBAL / whole-world / globe view → Landsat scenes are tiny postage stamps, useless ' +
      'at that scale; instead use add_tile_layer named "Global Temperature" with this XYZ ' +
      'template (NASA GIBS land surface temperature, max zoom 7):\n' +
      'https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/MODIS_Aqua_L3_Land_Surface_Temp_8Day_Day/default/default/GoogleMapsCompatible_Level7/{z}/{y}/{x}.png\n' +
      'For ocean/sea temperature use the same pattern with GHRSST_L4_MUR_Sea_Surface_Temperature. ' +
      'CRITICAL: this layer has NO tiles above zoom 7 — after adding it you MUST also zoom the ' +
      'camera out to world scale (zoom_to bbox [-170,-55,170,70]) or the user sees nothing. ' +
      '(c) a density heatmap of loaded point data → a MapLibre heatmap layer via run_maplibre_js.\n' +
      '4. Tile fetches fail asynchronously and you receive no error, so never claim a layer ' +
      '"is visible". Say you added it and name the layer so the user can verify.\n' +
      '5. The user is often speaking by voice on AR glasses: keep answers to 1-2 short spoken-' +
      'style sentences, no markdown, no code blocks, no URLs in the reply text.\n' +
      '6. UI COMMANDS: this deployment can toggle app panels/controls that have no tools. When ' +
      'the user asks for one of these, include the matching token verbatim in your reply (it is ' +
      'stripped before speech) plus a short confirmation. Each token toggles the feature on/off:\n' +
      'measure distance/area → [X3UI:measure]; save or revisit map views → [X3UI:bookmark]; ' +
      'minimap/overview → [X3UI:minimap]; legend → [X3UI:legend]; raster color scale → [X3UI:colorbar]; ' +
      'place search box → [X3UI:search]; center on my location → [X3UI:geolocate]; ' +
      'globe view → [X3UI:globe]; terrain control → [X3UI:terrain]; scale bar → [X3UI:scale]; ' +
      'grid/graticule → [X3UI:gridlines]; weather effects → [X3UI:weather]; sun/day-night lighting → [X3UI:sun]; ' +
      'atmosphere/sky → [X3UI:atmosphere]; spin the globe → [X3UI:spinning-globe]; ' +
      'directions/routing → [X3UI:directions]; address at a point → [X3UI:reverse-geocode]; ' +
      'camera numbers (center/zoom/pitch) → [X3UI:view-state]; record a video → [X3UI:record-video]; ' +
      'basemap picker → [X3UI:basemaps]; draw/edit features → [X3UI:geoeditor]; notes/annotations → [X3UI:annotations]; ' +
      'historical/old imagery → [X3UI:historical-imagery]; time slider → [X3UI:time-slider]; ' +
      'timelapse animation → [X3UI:timelapse]; Overture buildings/places → [X3UI:overture]; ' +
      'street view → [X3UI:street-view]; street photos → [X3UI:mapillary]; lidar point clouds → [X3UI:lidar]; ' +
      'elevation profile along a line → [X3UI:elevation-profile]; compare layers side-by-side → [X3UI:layer-swipe]; ' +
      'undo the last action → [X3UI:undo]; redo → [X3UI:redo]; close/dismiss a dialog or menu → [X3UI:escape].\n' +
      '7. INTERPRETATION: the request text comes from glasses speech-to-text, so expect misheard ' +
      'or misspelled place and layer names (e.g. "Puerto Vaiorta" means Puerto Vallarta). Infer ' +
      'the intended name from context instead of reporting a failed search; only ask when truly ' +
      'ambiguous. Casual phrasings to honor: "make it look real / like a photo" = satellite ' +
      'imagery; "where am I" = [X3UI:geolocate]; "darker / night mode" = set_basemap dark; ' +
      '"lighter / day mode" = set_basemap positron; "normal map / streets" = set_basemap liberty; ' +
      '"global view / world view / whole earth / globe view" = zoom the CAMERA out to world scale ' +
      '(zoom_to bbox [-170,-55,170,70]) — switching to globe projection alone changes nothing when ' +
      'zoomed in; do both when the user says globe. ' +
      '"closer / further" = run_maplibre_js map.zoomIn() / map.zoomOut(); "what am I looking at" = ' +
      'list_layers then describe briefly; "undo that / go back" = [X3UI:undo]; "never mind / ' +
      'close that" = [X3UI:escape]. For "how far is A from B" between named places, answer in ' +
      'words; for measuring on screen, use [X3UI:measure].';
    function x3InjectEnvNotes(init) {
      try {
        if (!init || typeof init.body !== 'string' || init.body.charAt(0) !== '{') return init;
        var b = JSON.parse(init.body);
        var si = b.systemInstruction || b.system_instruction;
        if (!si) { si = { parts: [] }; b.systemInstruction = si; }
        if (!si.parts) si.parts = [];
        for (var i = 0; i < si.parts.length; i++) {
          if (/X3GEO-ENV/.test(si.parts[i].text || '')) return init; // already tagged
        }
        si.parts.push({ text: X3_ENV_NOTES });
        var out = {};
        for (var k in init) out[k] = init[k];
        out.body = JSON.stringify(b);
        return out;
      } catch (e) { return init; }
    }
    // GeoLibre's assistant "geocodes" ("take me to X", "add X to the map") by
    // web-searching DuckDuckGo's Instant Answer API for "<place> bounding box
    // longitude latitude" — but that API almost never returns coordinates, so
    // navigation silently fails. Intercept those geo lookups and answer with real
    // coordinates from OpenStreetMap Nominatim (keyless, CORS-open), formatted in
    // the same JSON shape the assistant already parses, so it reads the lat/lon
    // and moves the map. Non-geo web searches pass straight through to DuckDuckGo.
    function x3IsGeoSearch(url) {
      if (!/api\.duckduckgo\.com/i.test(url)) return false;
      var d = url; try { d = decodeURIComponent(url); } catch (e) {}
      return /(longitude|latitude|coordinates|bounding\s*box|geocode|\blat\b|\blon\b|\blng\b)/i.test(d);
    }
    function x3PlaceFromDdg(url) {
      var m = /[?&]q=([^&]*)/.exec(url);
      if (!m) return '';
      var q = m[1].replace(/\+/g, ' ');
      try { q = decodeURIComponent(q); } catch (e) {}
      return q.replace(/\b(bounding\s*box|coordinates?|longitude|latitude|lat|long|lon|lng|gps|geocode|location of|where is|the|map)\b/gi, ' ')
              .replace(/\s+/g, ' ').trim();
    }
    function x3GeoLookup(place) {
      return originalFetch(
        'https://nominatim.openstreetmap.org/search?format=json&limit=1&q=' + encodeURIComponent(place),
        { headers: { 'Accept': 'application/json' } }
      ).then(function (r) { return r.json(); }).then(function (a) {
        var t;
        if (a && a[0]) {
          var g = a[0], b = g.boundingbox || [];
          t = g.display_name + '. Latitude: ' + g.lat + ', Longitude: ' + g.lon +
              (b.length === 4 ? '. Bounding box (south, north, west, east): ' +
                b[0] + ', ' + b[1] + ', ' + b[2] + ', ' + b[3] : '') + '.';
        } else {
          t = 'No coordinates found for ' + place + '.';
        }
        return new Response(JSON.stringify({
          Abstract: t, AbstractText: t, AbstractSource: 'OpenStreetMap Nominatim',
          AbstractURL: '', Answer: t, AnswerType: 'location', Heading: place, Type: 'A', RelatedTopics: []
        }), { status: 200, headers: { 'Content-Type': 'application/json' } });
      }).catch(function () {
        return new Response(JSON.stringify({ Abstract: '', AbstractText: '', Answer: '', RelatedTopics: [] }),
          { status: 200, headers: { 'Content-Type': 'application/json' } });
      });
    }
    window.fetch = function (input, init) {
      var url = '';
      try { url = typeof input === 'string' ? input : input.url; } catch (e) {}
      var styleMatch = X3_STYLE_RX.exec(url || '');
      if (styleMatch) {
        return Promise.resolve(new Response(JSON.stringify(x3StyleFor(styleMatch[1])), {
          status: 200,
          headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }
        }));
      }
      if (url && x3IsGeoSearch(url)) {
        var place = x3PlaceFromDdg(url);
        if (place) return x3GeoLookup(place);
      }
      var newUrl = x3RewriteModel(url);
      if (newUrl !== url) {
        if (typeof input === 'string') input = newUrl;
        else { try { input = new Request(newUrl, input); } catch (e) {} }
      }
      if (url && url.indexOf('generativelanguage.googleapis.com') >= 0) {
        init = x3InjectEnvNotes(init);
        try { window.postMessage({ __x3geo: 'aicall', via: 'fetch', url: (newUrl || url).slice(0, 160) }, '*'); } catch (e) {}
      }
      return originalFetch(input, init);
    };
    // Same rewrite for XHR, in case the AI SDK path uses it.
    var x3Open = window.XMLHttpRequest && window.XMLHttpRequest.prototype.open;
    if (x3Open) {
      window.XMLHttpRequest.prototype.open = function (m, u) {
        try {
          if (typeof u === 'string') {
            arguments[1] = x3RewriteModel(u);
            if (u.indexOf('generativelanguage.googleapis.com') >= 0) {
              window.postMessage({ __x3geo: 'aicall', via: 'xhr', url: String(arguments[1]).slice(0, 160) }, '*');
            }
          }
        } catch (e) {}
        return x3Open.apply(this, arguments);
      };
    }

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

  /* ---------------- force dark mode ----------------
     The GeckoRuntime already reports prefers-color-scheme: dark, but pin the
     class too so GeoLibre stays dark even if it re-derives the theme from a
     stored setting. A light-attribute observer re-asserts it if stripped. */
  function forceDark() {
    var el = document.documentElement;
    if (!el) return;
    if (!el.classList.contains('dark')) el.classList.add('dark');
    el.classList.remove('light');
    el.style.colorScheme = 'dark';
  }
  forceDark();
  var darkMo = new MutationObserver(forceDark);
  if (document.documentElement) {
    darkMo.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
  }

  // page asks for a position → relay to native
  window.addEventListener('message', function (ev) {
    var d = ev.data;
    if (!d || !port) return;
    if (d.__x3geo === 'want') {
      try { port.postMessage({ type: 'want-position' }); } catch (e) {}
    } else if (d.__x3geo === 'aicall') {
      // diagnostic: which Gemini model GeoLibre actually calls, and how
      try { port.postMessage({ type: 'probe', ai: d.url, via: d.via }); } catch (e) {}
    }
  });

  /* ---------------- auto-center via GeoLibre's place-search ----------------
     GeoLibre ships NO geolocate control (confirmed on-device), but it has a
     "Search places" geocoder. We center on the user by geocoding their city
     into it — city-level is exactly what IP location gives us anyway. */
  var lastPos = null;             // {lat, lon, label}
  var autoCentered = false;
  var startupPanels = { layers: false, style: false, tries: 0 };

  // GeoLibre remembers/open-defaults both side panels. At the X3's narrow
  // desktop viewport that leaves only a sliver of map. Collapse each initial
  // panel once; the user can reopen either from its collapsed edge tab.
  function compactStartupPanels() {
    if (startupPanels.layers && startupPanels.style) return;
    startupPanels.tries++;
    var layers = document.querySelector('button[aria-label="Collapse Layers" i]');
    var style = document.querySelector('button[aria-label="Collapse style" i]');
    if (!startupPanels.layers && visible(layers)) {
      startupPanels.layers = true;
      try { layers.click(); } catch (e) {}
    }
    if (!startupPanels.style && visible(style)) {
      startupPanels.style = true;
      try { style.click(); } catch (e) {}
    }
    if (startupPanels.tries < 8 && (!startupPanels.layers || !startupPanels.style)) {
      setTimeout(compactStartupPanels, 1000);
    }
  }
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
    if (auto) {
      autoCentered = true;
      // Let the geocoder choose the city before its containing panel folds.
      setTimeout(compactStartupPanels, 7000);
    }
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
    ensureZoomButtons();
  });
  function startObserver() {
    if (!document.documentElement) return;
    mo.observe(document.documentElement, { childList: true, subtree: true });
    // Fallback for a page where the geocoder never mounts.
    setTimeout(function () {
      if (!autoCentered) compactStartupPanels();
    }, 12000);
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

  /* ---------------- shared DOM helpers ---------------- */
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

  /* ---------------- AI Assistant (built-in, Processing ▸ AI Assistant) ----
     Triple tap toggles GeoLibre's built-in assistant panel. GeoLibre's menus
     are Radix components that only open on *trusted* input, so we compute each
     target's centre as a viewport fraction and ask native to land a real
     GeckoView tap there ('tap-native'). If the assistant still needs a
     provider, we auto-seed it: open Settings ▸ AI Providers (defaults to
     Google Gemini), focus the key input, have native TYPE the stored Gemini
     key (real key events), save, then report 'assistant-ready' so native
     starts the voice dictation. */
  var PROMPT_SEL = 'textarea[placeholder*="Ask about" i]';
  var CLOSE_SEL = 'button[title="Close assistant" i]';
  function nativeTap(el) {
    if (!el || !port) return false;
    var r = el.getBoundingClientRect();
    var fx = (r.left + r.width / 2) / (window.innerWidth || 1);
    var fy = (r.top + r.height / 2) / (window.innerHeight || 1);
    try { port.postMessage({ type: 'tap-native', fx: fx, fy: fy }); return true; } catch (e) { return false; }
  }
  function menuItem(text) {
    var items = document.querySelectorAll('[role="menuitem"],[role="menuitemcheckbox"]');
    for (var i = 0; i < items.length; i++) {
      if ((items[i].textContent || '').trim() === text && visible(items[i])) return items[i];
    }
    return null;
  }
  function buttonWithText(rx) {
    var btns = document.querySelectorAll('button');
    for (var i = 0; i < btns.length; i++) {
      if (rx.test((btns[i].textContent || '').trim()) && visible(btns[i])) return btns[i];
    }
    return null;
  }
  // Poll (menus/dialogs mount a tick after the tap) then fire cb(el|null).
  function waitFor(fn, tries, cb) {
    var n = 0;
    var iv = setInterval(function () {
      n++;
      var el = fn();
      if (el) { clearInterval(iv); cb(el); }
      else if (n >= tries) { clearInterval(iv); cb(null); }
    }, 200);
  }
  function assistantOpen() { return !!document.querySelector(CLOSE_SEL); }
  function promptReady() {
    var ta = document.querySelector(PROMPT_SEL);
    return ta && visible(ta) ? ta : null;
  }
  function status(text) {
    toast(text);
    try { port && port.postMessage({ type: 'assistant-status', text: text }); } catch (e) {}
  }
  // The Gemini key is seeded straight into GeoLibre's provider config (see
  // applyGeminiKey), so the assistant just needs to be opened.
  // Open the assistant if it's closed (Live voice needs it open); if it's
  // already open, just report ready. Unlike toggleAssistant this never closes it.
  function ensureAssistant() {
    if (assistantOpen()) {
      waitFor(function () { return promptReady(); }, 15, function (el) {
        if (el) reportReady(); else status('Assistant not ready');
      });
      return;
    }
    toggleAssistant();
  }
  function toggleAssistant() {
    if (assistantOpen()) { nativeTap(document.querySelector(CLOSE_SEL)); return; }
    var processing = document.querySelector('button[aria-label="Processing"]');
    if (!processing) { status('Menu not ready yet'); return; }
    nativeTap(processing);
    waitFor(function () { return menuItem('AI Assistant'); }, 12, function (item) {
      if (!item) { status('Could not open Processing menu'); return; }
      nativeTap(item);
      waitFor(function () { return promptReady() || buttonWithText(/Open Settings/); }, 15, function (el) {
        if (!el) { status('Assistant did not open'); return; }
        if (promptReady()) { reportReady(); return; }
        status('AI needs a valid Gemini key');
      });
    });
  }
  // Native pushes the stored Gemini key on connect; write it into GeoLibre's
  // provider config (localStorage desktopSettings.aiProviderEnv.GEMINI_API_KEY)
  // and reload once so the running store picks it up. No-op if already correct.
  function applyGeminiKey(key) {
    if (!key) return;
    try {
      var raw = localStorage.getItem('geolibre.desktopSettings');
      var s = raw ? JSON.parse(raw) : {};
      s.aiProviderEnv = s.aiProviderEnv || {};
      if (s.aiProviderEnv.GEMINI_API_KEY === key) return;
      s.aiProviderEnv.GEMINI_API_KEY = key;
      localStorage.setItem('geolibre.desktopSettings', JSON.stringify(s));
      location.reload();
    } catch (e) {}
  }
  function reportReady() {
    status('AI ready — speak your question');
    try { port.postMessage({ type: 'assistant-ready' }); } catch (e) {}
  }
  // Native asks us to focus the prompt box before typing the transcript.
  function focusPrompt() {
    var ta = promptReady();
    if (!ta) { status('Prompt box not found'); return; }
    nativeTap(ta);
    setTimeout(function () {
      try { port.postMessage({ type: 'prompt-focused' }); } catch (e) {}
    }, 450);
  }

  /* ---------------- live voice: read assistant replies aloud ----------------
     During a Live voice session the native side reads GeoLibre's answers aloud.
     GeoLibre renders each turn as a child of the chat list
     (div.space-y-2.overflow-auto.leading-relaxed); user turns carry
     .whitespace-pre-wrap.font-medium.text-foreground and a "❯ " prefix, so the
     assistant reply is the last child that isn't a user bubble. We poll and only
     report once the text has stopped growing (streaming finished). */
  var liveMode = false, liveReported = '', livePrev = '', liveStable = 0, livePoll = null;
  function msgListEl() {
    return document.querySelector(
      'div[class*="space-y-2"][class*="overflow-auto"][class*="leading-relaxed"]'
    );
  }
  function isUserBubble(el) {
    if (!el || !el.classList) return false;
    if (el.classList.contains('whitespace-pre-wrap') && el.classList.contains('font-medium')) return true;
    return ((el.textContent || '').trim().charAt(0) === '❯'); // ❯
  }
  function lastAssistantText() {
    var list = msgListEl();
    if (!list) return '';
    for (var i = list.children.length - 1; i >= 0; i--) {
      var el = list.children[i];
      if (isUserBubble(el)) continue;
      var t = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
      if (t) return t;
    }
    return '';
  }
  function liveTick() {
    if (!liveMode) return;
    var cur = lastAssistantText();
    if (cur === livePrev) { liveStable++; } else { livePrev = cur; liveStable = 0; }
    // unchanged across ~1.4 s (two intervals) → generation/tool-run settled
    if (cur && liveStable >= 2 && cur !== liveReported) {
      liveReported = cur;
      // don't read [X3UI:*] command tokens aloud
      var say = cur.replace(/\[X3UI:[a-z0-9-]+\]/gi, '').replace(/\s+/g, ' ').trim();
      if (say.length > 700) say = say.slice(0, 700);
      if (say) {
        try { port && port.postMessage({ type: 'assistant-response', text: say }); } catch (e) {}
      }
    }
  }
  function liveOn() {
    if (liveMode) return;
    liveMode = true;
    liveReported = lastAssistantText(); // baseline: don't re-read existing text
    livePrev = liveReported; liveStable = 0;
    livePoll = setInterval(liveTick, 700);
    status('Live voice on');
  }
  function liveOff() {
    liveMode = false;
    if (livePoll) { clearInterval(livePoll); livePoll = null; }
  }

  /* ---------------- [X3UI:*] command channel ----------------
     GeoLibre's menu-only features (Measure, Bookmarks, Street View, Time
     Slider, …) have no assistant tools. The env notes teach the model to emit
     an [X3UI:name] token in its reply for those requests; this scanner watches
     settled assistant messages for tokens and drives the real Controls/Plugins
     menus via trusted native taps — so "measure this" works by voice. */
  var X3UI = {
    'measure': ['Controls', 'Measure'],
    'bookmark': ['Controls', 'Bookmark'],
    'minimap': ['Controls', 'Minimap'],
    'legend': ['Controls', 'Legend'],
    'colorbar': ['Controls', 'Colorbar'],
    'search': ['Controls', 'Search'],
    'geolocate': ['Controls', 'Geolocate'],
    'globe': ['Controls', 'Globe'],
    'terrain': ['Controls', 'Terrain'],
    'scale': ['Controls', 'Scale'],
    'gridlines': ['Controls', 'Gridlines'],
    'weather': ['Controls', 'Weather'],
    'sun': ['Controls', 'Sun'],
    'atmosphere': ['Controls', 'Atmospheric Effects'],
    'spinning-globe': ['Controls', 'Spinning Globe'],
    'directions': ['Controls', 'Directions'],
    'reverse-geocode': ['Controls', 'Reverse Geocode'],
    'view-state': ['Controls', 'View State'],
    'record-video': ['Controls', 'Record Video...'],
    'basemaps': ['Plugins', 'Basemaps'],
    'geoeditor': ['Plugins', 'GeoEditor'],
    'annotations': ['Plugins', 'Annotations'],
    'historical-imagery': ['Plugins', 'Historical Imagery'],
    'time-slider': ['Plugins', 'Time Slider'],
    'timelapse': ['Plugins', 'Timelapse'],
    'overture': ['Plugins', 'Overture Maps'],
    'street-view': ['Plugins', 'Street View'],
    'mapillary': ['Plugins', 'Mapillary'],
    'lidar': ['Plugins', 'USGS LiDAR'],
    'elevation-profile': ['Plugins', 'Elevation Profile'],
    'layer-swipe': ['Plugins', 'Layer Swipe']
  };
  // Menu items render as "Measure" or "Measure ✓" when active — strip the tick.
  function menuItemLoose(text) {
    var items = document.querySelectorAll('[role="menuitem"],[role="menuitemcheckbox"]');
    for (var i = 0; i < items.length; i++) {
      var t = (items[i].textContent || '').trim().replace(/\s*✓$/, '');
      if (t === text && visible(items[i])) return items[i];
    }
    return null;
  }
  // Not menus — real keystrokes (Ctrl+Z / Ctrl+Shift+Z / Esc), sent natively
  // so Gecko treats them as trusted input. GeoLibre's store makes all
  // assistant actions undoable, so "undo that" is a first-class voice command.
  var X3UI_KEYS = { undo: 1, redo: 1, escape: 1 };
  function x3uiExec(action) {
    if (X3UI_KEYS[action]) {
      try { port && port.postMessage({ type: 'ui-key', key: action }); } catch (e) {}
      return;
    }
    var spec = X3UI[action];
    if (!spec) { status('Unknown UI command: ' + action); return; }
    var btns = document.querySelectorAll('button');
    var menuBtn = null;
    for (var i = 0; i < btns.length; i++) {
      if ((btns[i].textContent || '').trim() === spec[0]) { menuBtn = btns[i]; break; }
    }
    if (!menuBtn) { status(spec[0] + ' menu not found'); return; }
    nativeTap(menuBtn);
    waitFor(function () { return menuItemLoose(spec[1]); }, 12, function (item) {
      if (!item) { status('Could not find ' + spec[1] + ' in ' + spec[0]); return; }
      nativeTap(item);
      status(spec[1] + ' toggled');
    });
  }
  // Scan settled assistant replies for tokens (works in voice AND typed chat).
  var x3uiPrev = '', x3uiStable = 0, x3uiDoneFor = null;
  setInterval(function () {
    if (!assistantOpen()) { x3uiPrev = ''; x3uiStable = 0; return; }
    var cur = lastAssistantText();
    if (!cur) return;
    if (cur === x3uiPrev) { x3uiStable++; } else { x3uiPrev = cur; x3uiStable = 0; }
    if (x3uiStable === 2 && cur !== x3uiDoneFor) {
      x3uiDoneFor = cur;
      var rx = /\[X3UI:([a-z0-9-]+)\]/gi, m, ran = {};
      while ((m = rx.exec(cur))) {
        var a = m[1].toLowerCase();
        if (!ran[a]) { ran[a] = true; x3uiExec(a); }
      }
    }
  }, 700);

  /* ---------------- injected zoom in/out controls ----------------
     GeoLibre ships no zoom control, so add two boxes under the map's top-right
     control stack, styled exactly like the built-in ones (MapLibre's own
     ctrl-group + zoom-in/out classes). They zoom via a native wheel event. */
  function makeZoomBox(id, cls, label, dir) {
    var group = document.createElement('div');
    group.id = id + '-group';
    group.className = 'maplibregl-ctrl maplibregl-ctrl-group';
    var btn = document.createElement('button');
    btn.id = id; btn.className = cls; btn.type = 'button';
    btn.setAttribute('aria-label', label); btn.title = label;
    var icon = document.createElement('span');
    icon.className = 'maplibregl-ctrl-icon'; icon.setAttribute('aria-hidden', 'true');
    btn.appendChild(icon);
    btn.addEventListener('click', function (ev) {
      ev.preventDefault(); ev.stopPropagation();
      if (port) { try { port.postMessage({ type: 'zoom', dir: dir }); } catch (e) {} }
    });
    group.appendChild(btn);
    return group;
  }
  function ensureZoomButtons() {
    var corner = document.querySelector('.maplibregl-ctrl-top-right');
    if (!corner) return;
    // Sit directly under the Layer Control box, so wait until it exists.
    var layerCtrl = corner.querySelector('.maplibregl-ctrl-layer-control');
    if (!layerCtrl) return;
    var zin = document.getElementById('x3-zoom-in-group');
    var zout = document.getElementById('x3-zoom-out-group');
    if (!zin) zin = makeZoomBox('x3-zoom-in', 'maplibregl-ctrl-zoom-in', 'Zoom in', 1);
    if (!zout) zout = makeZoomBox('x3-zoom-out', 'maplibregl-ctrl-zoom-out', 'Zoom out', -1);
    // Keep them ordered right after the layers box even if GeoLibre re-renders.
    if (layerCtrl.nextElementSibling !== zin) layerCtrl.insertAdjacentElement('afterend', zin);
    if (zin.nextElementSibling !== zout) zin.insertAdjacentElement('afterend', zout);
  }

  function ensureGlassesCss() {
    if (document.getElementById('x3geo-glasses-css') || !document.documentElement) return;
    var style = document.createElement('style');
    style.id = 'x3geo-glasses-css';
    // Enlarge MapLibre's on-map controls so they're tappable with the cursor.
    // Also hide the fullscreen control: on the glasses it hides ALL of GeoLibre's
    // chrome (menu bar, panels) with no keyboard Esc to get back — a stray tap on
    // it looks exactly like the app breaking.
    style.textContent =
      '.maplibregl-ctrl-group button{width:42px!important;height:42px!important}' +
      '.maplibregl-ctrl button .maplibregl-ctrl-icon{background-size:25px 25px!important}' +
      '.maplibregl-ctrl-fullscreen{display:none!important}' +
      '.maplibregl-ctrl-group:has(.maplibregl-ctrl-fullscreen){display:none!important}' +
      // Dark red on the waveguide is nearly invisible (the red subpixel is dim and
      // black reads as transparent). Lift every red/destructive text to a bright,
      // legible salmon and bump its weight. Targets Tailwind red/rose/destructive
      // utilities plus the assistant's error output.
      '[class*="text-red"],[class*="text-rose"],[class*="text-destructive"],' +
      '.text-destructive,.destructive,[class*="error"] *,[data-error],' +
      '[class*="text-red"] *,[class*="text-destructive"] *' +
      '{color:#ff9e8a!important;font-weight:600!important;' +
      'text-shadow:0 0 1px rgba(0,0,0,.6)!important}';
    document.documentElement.appendChild(style);
  }
  ensureGlassesCss();

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
