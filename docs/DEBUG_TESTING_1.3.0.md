# Android map target validation notes for 1.3.0

These checks were used to validate the 1.3.0 feature line before publication. The public Android release is built from the same functional source line as the tested 1.3.1 debug build.

## Device checks

1. Share a street address from another app to Drive Time Notifier Debug. The planning dialog should appear above the source app.
2. Share a Google Maps short link, a full Maps link, a `geo:` target and an OpenStreetMap marker. Verify the destination refers to the selected place, rather than the map viewport.
3. Select a saved start, edit the destination, change arrival date/time and select a fixed buffer. Open the planner and check the departure time. Disabling the buffer must also disable the dynamic reserve for this trip. Global planning settings must remain unchanged.
4. Disable the share popup under Planning. Share again. The app must open with the destination already filled, including when its settings were previously open.
5. Tap Current location under either address field. Check precise and approximate permission, denied permission, disabled location services, timeout and cancellation. Location acquisition runs only on request and never in the background.
6. Rotate the share dialog and confirm that edited fields survive. Cancel it and confirm that the source app remains available.
7. Android decides whether HTTPS map links open through its app chooser, browser or an already assigned verified map app. Use Share to select this app explicitly if another app owns the link.

## Dependency and service review, 2026-09-30

Stable Maven versions were checked against publisher metadata. Updated AGP 9.4.1, Kotlin/Compose compiler 2.4.20, Compose BOM 2026.09.00, Core 1.19.1, Activity 1.13.0, Lifecycle 2.11.0, DataStore 1.2.1, WorkManager 2.12.0, Coroutines 1.11.0 and OkHttp 5.5.0. Gradle 9.6.0 and compile SDK 37 satisfy the new build requirements. Runtime minimum and target SDK behavior remain unchanged.

osmdroid 6.1.20 is the final upstream version. Its repository is archived. Replacing the map renderer requires a separate migration; upgrading other dependencies does not restore upstream maintenance for osmdroid.

From the review environment, Photon, OSRM, Valhalla, the main Overpass status endpoint, the Bundesnetzagentur layer metadata and GitHub update metadata answered successfully. The two alternative Overpass services timed out during the probe. They remain documented global instances, and the existing bounded fallback/cooldown logic remains necessary. Authentication-required routing APIs were inspected against publisher references and probed without user API keys. Unauthorized responses do not constitute a complete authenticated routing test. A 404 for an optional health URL does not establish that the routing API is unavailable.

Direct updated runtime coordinates returned no vulnerability IDs from OSV's Maven database during the review. CI exports the actual resolved runtime graph, including transitive dependencies, for a second scan. Absence of a database finding is not a security guarantee.

## Hardening

- Shared content is bounded; map destinations and coordinate ranges are validated.
- Short-link expansion uses only allowlisted HTTPS map hosts, a bounded redirect count and network timeouts.
- Search/routing clients no longer follow automatic redirects, keeping provider-specific API headers at the configured service.
- Stored custom endpoints must parse as HTTPS with a real hostname and no embedded credentials or fragment.
- Diagnostic exports redact URL credentials, query strings and nonstandard endpoint paths which may contain API keys.
- Exported launcher action activities require confirmation before scheduling calendar-writing automation. Token-authenticated broadcast automation remains available for unattended use.
- No overlay, background location or Google Play Services dependency is introduced.
- Dependabot configuration tracks Gradle and GitHub Actions updates.
