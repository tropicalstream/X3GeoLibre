# X3 GeoLibre

X3 GeoLibre is a native shell that makes GeoLibre Web, an open-source cloud GIS platform, fully usable on the RayNeo X3 Pro AR glasses. It runs the platform's own MapLibre-based map inside a bundled browser engine, adapting a mouse-and-keyboard web application to a temple trackpad and two buttons: a triple-tap opens a command palette for search, recentering, zoom, layers, and menu access, and a double-tap dispatches a real right-click for GeoLibre's context menus. The app auto-locates the wearer through IP-based geolocation (the glasses have no GPS of their own), substitutes a reliable basemap when the platform's default style fails to load, and offers voice dictation into any text field on the map.

## Controls

| Gesture | Action |
|---|---|
| Right pad slide | Move cursor (edge bands pan the map) |
| Right pad tap | Click at cursor |
| Right pad double-tap | Right-click (context menu) at cursor |
| Right pad triple-tap | Open or close the command palette |
| Left pad slide | Volume |
| Keyboard mic key | Voice dictation into the focused field |

## Demo

[![GeoLibre for RayNeo X3 Pro](https://i.ytimg.com/vi/Y2PereXhzpU/hqdefault.jpg)](https://youtu.be/Y2PereXhzpU)

## Download

The debug APK is published as a GitHub release asset (too large for the repo itself): [X3GeoLibre.apk](https://github.com/tropicalstream/X3GeoLibre/releases/download/v1.0/X3GeoLibre.apk)

## Credits

An unofficial wrapper around [GeoLibre](https://web.geolibre.app/), © its authors. Map data © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors.
