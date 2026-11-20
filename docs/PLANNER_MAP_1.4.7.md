# Planner map: public 1.3.1 versus debug 1.4.6

Comparison: public tag `v1.3.1`, debug `85b3163154abd1cc3edd0247b3b9cb3ecb34225c`.

Both use osmdroid 6.1.20 and OpenStreetMap tiles. Neither map requires a Google Maps key or signing-certificate registration. INTERNET and network-state permissions are present in both. The debug package stays separate and does not share the public app's cache.

## Confirmed code defects

- The debug viewport update marks a route as fitted and posts a single bounding-box calculation. Posting is not a guarantee that AndroidView has finished layout. osmdroid calculates the zoom using width/height minus twice the border. A zero-sized view produces a logarithm of a negative value, hence NaN. The route identity guard suppresses a later retry. The old public path did not have that one-shot guard.
- The debug replacement `XYTileSource` uses the same Mapnik name and endpoint but loses the public source's policy: two concurrent requests, no preventive/bulk downloads and normalized user agent. This is an unnecessary behavioral difference; it does not prove that a tile server blocked the user's device.

## Fix

Wait for actual layout with `doOnLayout`, discard obsolete pending route fits and bound the border to the available dimensions. Keep manual zoom on ordinary recomposition. Use the original `TileSourceFactory.MAPNIK` policy. Preserve the app-private tile cache, direct delivery of decoded tiles, all route/POI features and the separate debug identity. Debug version: 1.4.7 / code 22.

## Regression coverage

Android tests request two route fits before measuring the map, then verify finite zoom, the latest route's visible bounds and preservation of manual zoom. The existing real-Compose pixel test now also performs route fitting before layout. Policy and SQLite cache tests cover the production source. CI also runs unit tests, lint and debug/release assembly.

A direct HTTPS probe of a single world tile returned HTTP 200 for both the public and debug user agents on 2026-11-20. This checks this environment only. No device log from the reported failing installation was supplied, so device-specific network or cache failures cannot be conclusively excluded. No claim is made that the exact reported device symptom has been reproduced.
