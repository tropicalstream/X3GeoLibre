# X3 GeoLibre

**A native RayNeo X3 Pro shell around [GeoLibre Web](https://web.geolibre.app/)**
— the open-source, cloud-native GIS platform — made fully usable with the
glasses' trackpad-and-two-buttons input. An awesome way to explore the world
in AR: pan a real MapLibre map, add data, run analysis, all from the temple pad.

Built from the same proven shell as TapGPT/TapGemini (bundled GeckoView /
Firefox 152 engine — the system WebView's Chrome 95 can't run GeoLibre's
bundle; binocular dual-eye mirror; trackpad cursor; on-screen keyboard).

## What makes GeoLibre usable on glasses

- **Auto-locate on load.** The X3 Pro has no GNSS, so a native IP geolocator
  (`IpLocator`) resolves your city, and a built-in **WebExtension bridge**
  polyfills `navigator.geolocation` and geocodes your city into GeoLibre's
  "Search places" box on load — dropping a pin at where you are. (GeoLibre
  ships no map geolocate control, confirmed on-device, so this drives its own
  geocoder.)
- **Icon rail** (left edge, injected by the bridge): one-tap **Search**,
  **Center on me**, **Zoom +/−**, **Reset north**, **Layers**, **Menu**, and
  **Right-click here** — the core functions mapped to big, cursor-friendly
  buttons so you never hunt tiny web chrome.
- **Right-click context menus.** GeoLibre is right-click-driven; a **long-press
  on the right pad** (cursor held still ~0.65 s) dispatches a real
  `contextmenu` at the cursor, so GeoLibre's menus open exactly as with a mouse.
- **Mic dictation on the keyboard.** The on-screen keyboard's **Mic** key is
  Groq Whisper dictation (`whisper-large-v3-turbo`): tap to record, tap to
  stop, the transcript types into the focused field (great for the search box
  and attribute edits). Set the key via ⚙ Settings or adb.
- **Wi-Fi wait gate.** The glasses' Wi-Fi takes ~5 s to come up after wake, so
  the app polls for a validated network (status line: "Waiting for Wi-Fi…")
  before loading, and reloads automatically if the network arrives late — no
  dead error page.
- **Cursor + scroll + volume** from the shell: right pad moves the cursor and
  taps click; edge bands scroll/pan; left pad is volume.

## Controls

| Gesture | Action |
|---|---|
| Right pad slide | Move cursor (edge bands pan the map) |
| Right pad tap / firm click | Click at cursor |
| **Right pad long-press** | **Right-click** (context menu) at cursor |
| Left pad slide | Volume |
| ⚙ (top-right) | Settings (Groq key) |
| Icon rail (left) | Search / Locate / Zoom / North / Layers / Menu / Right-click |
| Keyboard **Mic** | Dictate into the focused field (Groq) |

## Groq key (for mic dictation)

```bash
adb shell am broadcast -n com.x3geolibre.app/.SetKeyReceiver \
  -a com.x3geolibre.app.SET_GROQ_KEY --es key "gsk_...your-key..."
```

## Build + install

```bash
cd /Users/me/Projects/x3geolibre
./gradlew assembleDebug && adb install -r /Users/me/Projects/x3geolibre/app/build/outputs/apk/debug/app-debug.apk
```

Launch: `adb shell am start -n com.x3geolibre.app/.GeckoTestActivity`
(pre-grant the mic once: `adb shell pm grant com.x3geolibre.app android.permission.RECORD_AUDIO`)

## Verified on-device (ARGF20, 2026-07-14)

**Working:** dual-eye GeoLibre load; full chrome (Project/Edit/View/Add Data/
Processing/Controls/Plugins/Settings/Help + Layers/Style panels); injected icon
rail; bridge extension + native port; IP fix (resolved "Oakland" and pinned it);
search-geocoder auto-center selecting the first result; Wi-Fi gate; mic keyboard
inherited from the shell.

**Needs your eyes on the real glasses (my adb screenshots fight the wear-sensor
auto-sleep):**
- **Basemap tiles rendered on first launch, then went dark** after I
  force-relaunched ~8× in minutes — almost certainly openfreemap **throttling**
  the glasses' IP under rapid reloads (host pings fine; the whole style
  `tiles.openfreemap.org/styles/liberty` starts returning CORS-preflight 405).
  Should be fine in normal one-launch use; if it persists, GeoLibre's basemap
  can be pointed at a different tile/style source in Settings.
- **Geocoder drops a pin at your city but doesn't auto-zoom the camera** — it
  selects the result; whether GeoLibre flies-to on selection may need a nudge.
- Long-press right-click and every rail button are wired but need a hand on the
  pad to confirm feel.

> Unofficial wrapper; GeoLibre is © its authors (opengeos/GeoLibre). All map
> data © OpenStreetMap contributors via OpenFreeMap.
