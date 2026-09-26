# X3GeoLibre Voice Guide

What to say to the AI Assistant (triple-tap → speak). Compiled from the official
GeoLibre docs (geolibre.app/user-guide/ai-assistant), the assistant's actual
17-tool schema captured off the wire, and on-device/browser testing.

Legend: ✅ verified working &nbsp; 🔧 works via this app's fixes &nbsp; ⚠️ caveat &nbsp; 🧪 untested

---

## Tier 1 — Everyday map control

| Say something like… | What happens | Tool | Status |
|---|---|---|---|
| "Take me to Sacramento" / "Fly to Tokyo" | Camera moves there | `web_search` (geocode) + `zoom_to` | 🔧✅ geocoding rerouted to OpenStreetMap (DuckDuckGo returns nothing) — verified on-device ("Zooming to Mexico City") |
| "Zoom to the parcels layer" | Fits camera to a layer | `zoom_to` | ✅ |
| "Zoom in / zoom out" | Camera zoom | `run_maplibre_js` | 🧪 (native zoom boxes also on-screen) |
| "Show me satellite / photorealistic / aerial imagery" | Adds a global Esri World Imagery layer named "Satellite" | `add_tile_layer` | 🔧✅ env-note reroute; verified rendering in browser. Without the fix it picks postage-stamp STAC items that 404 |
| "Show the map in 3D" / "make the mountains pop" / "tilt the map" | Real terrain + camera pitch | `run_maplibre_js` → `setTerrain` | 🔧✅ works because this app pre-seeds a `mapbox-dem` elevation source; verified on-device (pitch 75°, exaggeration 2.5) |
| "Make it flat again" / "back to 2D" | Terrain off, pitch 0 | `run_maplibre_js` | 🔧 in env notes |
| "What layers are loaded?" | Lists layers + fields | `list_layers` | ✅ verified |
| "Hide the buildings layer" / "show it again" | Layer visibility | `set_layer_visibility` | 🧪 |
| "Set the satellite layer opacity to 50%" | Layer transparency | `set_layer_opacity` | 🧪 |
| "Remove the temperature layer" | Deletes a layer | `remove_layer` | 🧪 |
| "Switch to a dark basemap" / "light basemap" | Swaps basemap style | `set_basemap` (liberty, bright, positron, dark, fiord) | 🔧🧪 vector styles blank in GeckoView — this app now serves raster look-alikes (OSM / Carto light / Carto dark). Needs an on-device look |

## Tier 2 — Imagery & earth observation

| Say… | What happens | Tool | Status |
|---|---|---|---|
| "Load the latest Sentinel-2 scene over this view" | Recent satellite scene as a layer | `search_stac` + `add_stac_layer` | 🧪 official example; scene may cover only part of the view (diagonal swath edges are normal) |
| "Add the most recent cloud-free Landsat image here" | Same, filtered by cloud | `search_stac` + `add_stac_layer` | 🧪 official example |
| "Show a temperature map of this area" | Landsat thermal band with a colormap | `add_stac_layer` | 🔧✅ env note forces the lwir11 thermal band — confirmed on-device |
| "Show a heat map of the whole world / in globe view" | NASA GIBS global land-surface temperature layer (ocean: sea-surface temp) | `add_tile_layer` | 🔧🧪 Landsat can't do global (185 km scenes); env note routes world-scale asks to GIBS — retest |
| "Search the Planetary Computer for NAIP imagery here" | High-res US aerial photos | `search_stac` | ⚠️ single NAIP quads are tiny; if it looks empty, zoom in, or just say "satellite imagery" instead |
| "Add an OpenTopoMap basemap" | Topo tile layer | `add_tile_layer` (knows osm, opentopomap, carto-dark) | 🧪 |
| "Add this GeoJSON: https://…" | Loads remote vector data | `add_layer_from_url` | 🧪 needs a spoken-friendly URL — better typed than voiced |

## Tier 3 — Data questions (Spatial SQL)

The assistant generates read-only DuckDB Spatial SQL against loaded layers.
Needs vector layers loaded first (Add Data menu, drag-and-drop, or a GeoJSON URL).

| Say… | Tool | Status |
|---|---|---|
| "How many parcels are larger than 1 hectare?" | `run_sql` | 🧪 official example |
| "List the 10 most populous counties" | `run_sql` | 🧪 official example |
| "Show parcels within 500 m of a river and add them as a layer" | `run_sql` (adds result layer) | 🧪 official example |
| "Count points in each polygon of the districts layer" | `run_sql` | 🧪 official example |

## Tier 4 — Geoprocessing & styling

| Say… | Tool | Status |
|---|---|---|
| "Buffer the roads by 100 meters" | `run_algorithm` (buffer) | 🧪 official example |
| "Clip the buffer to the county boundary" | `run_algorithm` (clip) | 🧪 |
| "Dissolve the parcels by zoning type" | `run_algorithm` (dissolve) | 🧪 |
| "Find where the floodplain overlaps the buildings" | `run_algorithm` (intersection) | 🧪 |
| "Create an H3 hex grid over the points and count per cell" | `run_algorithm` (H3) | 🧪 |
| "Color the counties by population with a red ramp" | `apply_symbology` (graduated) | 🧪 official example |
| "Style the parcels categorized by land use" | `apply_symbology` (categorized) | 🧪 |
| "Shade tracts by income, viridis, 7 classes" | `apply_symbology` | 🧪 |

## Tier 5 — Edge cases & power tools

| Say… | Tool | Status |
|---|---|---|
| "Switch to a 3D globe projection" | `run_maplibre_js` | 🧪 official example (globe control also on-map) |
| "Load a CSV from a URL with pandas and summarize it" | `run_python` (Pyodide) | 🧪 official example |
| "What's the population of this county?" (fact questions) | `web_search` | ✅ works (answers from the web) |
| Anything custom on the map (markers, sky color, animations) | `run_maplibre_js` | ⚠️ model-written JS; quality varies. Approve via the "Run assistant code?" dialog — single-tap Run, tick "allow for session" for hands-free |

## Tier 6 — App controls & plugins (via this app's [X3UI] command channel) 🔧🧪

GeoLibre gives these no assistant tools, so this app adds a command channel:
the assistant emits an `[X3UI:…]` token (stripped before speech) and the
bridge clicks the real Controls/Plugins menus with trusted taps. Each request
TOGGLES the feature (ask again to turn it off).

| Say… | Toggles |
|---|---|
| "Let me measure a distance/area" | Measure tool |
| "Bookmark this view" / "open my bookmarks" | Bookmarks panel |
| "Show a minimap" | Minimap |
| "Show the legend" / "color scale" | Legend / Colorbar |
| "Open the place search" | Search box |
| "Center on my location" | Geolocate control |
| "Globe view" / "spin the globe" | Globe / Spinning globe |
| "Show the scale bar" / "grid lines" | Scale / Graticule |
| "Show weather effects" / "sun lighting" / "atmosphere" | Weather / Sun / Sky |
| "Get directions" / "what address is this" | Directions / Reverse geocode |
| "Open the basemap picker" | Basemaps panel |
| "Let me draw on the map" / "add a note" | GeoEditor / Annotations |
| "Show historical imagery" / "time slider" / "timelapse" | Temporal tools |
| "Show Overture buildings" / "street view" / "street photos" | Overture / Street View / Mapillary |
| "Show lidar" / "elevation profile" / "compare layers side by side" | LiDAR / Profile / Swipe |
| "Record a video of the map" | Record Video |

| "Undo that" / "go back" | Ctrl+Z into GeoLibre's undo history (native keystroke) |
| "Redo" | Ctrl+Shift+Z |
| "Never mind" / "close that" | Escape (dismisses open dialog/menu) |

Still menu-only (no voice path): AI Segmentation (SAM), Story Maps, attribute
table editing, project save/share, Field Collection, Map Tour recording.

## Varied language & speech quirks

The assistant is an LLM, so most phrasing variation is understood natively.
This app adds interpretation rules on top (env notes §7):

- **STT tolerance**: transcripts come from speech recognition — the assistant
  is told to infer misheard place/layer names ("Puerto Vaiorta" → Puerto
  Vallarta) instead of failing the search.
- **Colloquialisms**: "make it look real / like a photo" → satellite; "where
  am I" → geolocate; "darker / night mode" → dark basemap; "lighter / day
  mode" → light; "closer / further" → zoom; "what am I looking at" →
  layer summary; "how far is A from B" → answered in words (vs. on-screen
  measuring → Measure tool).
- Guidance is probabilistic, not a grammar: the model can still occasionally
  misroute a phrase — rephrasing more explicitly always works.

## Glasses-specific behaviors (this app's layer)

- Voice loop: triple-tap = start conversation; single tap = normal click
  (approve dialogs etc.); double-tap = close assistant + end conversation.
- Voice avatar top-centre: grey connecting…, green listening, bright-green
  "hearing you", amber thinking…, cyan speaking. No avatar = session ended.
- The basemap is a raster fallback (vector styles fail in GeckoView), so 3D
  buildings aren't available; 3D terrain is (via the pre-seeded DEM).
- Geocoding, satellite, thermal, and 3D behaviors above rely on this app's
  request-level fixes in `app/src/main/assets/geolibre-ext/content.js`
  (`X3_ENV_NOTES`, DuckDuckGo→Nominatim reroute, basemap fallbacks, DEM seed).
