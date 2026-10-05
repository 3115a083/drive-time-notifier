# Changelog

## 1.3.0

Map sharing, on-demand location and cross-platform automation release.

- Adds Android share targets for street addresses, `geo:` targets, OpenStreetMap markers and supported Google Maps links so destinations can be handed directly to Drive Time Notifier.
- Adds on-demand current-location actions for start and destination. Location is requested only after an explicit user action and is not collected in the background.
- Adds an optional planning popup over other apps for shared destinations. It remains disabled by default and requires explicit Android overlay permission before it can be enabled.
- Adds a refresh action in the appointment picker so newly created or newly synchronized calendar events can be loaded without closing the picker.
- Hardens shared-link resolution with allowlisted HTTPS hosts, bounded redirects, validated coordinate ranges and bounded input sizes.
- Hardens custom network endpoints, redirect handling and diagnostic redaction so credentials and sensitive URL components are not leaked into debug output.
- Requires confirmation for exported launcher automation actions that can schedule calendar-writing work. Token-authenticated broadcast automation remains available for unattended integrations.
- Updates the Android build stack and runtime dependencies while keeping the existing Android 8.0 minimum and public package identity.
- Adds a German and English Apple Shortcuts construction guide with example configuration for OSRM, Google, TomTom and HERE, fixed and dynamic buffers, and calendar alerts.
- Keeps the public Android release functionally aligned with the tested 1.3.1 debug feature line, except for the public version name, package identity and signing.

## 1.2.0

Parking, EV charging and network reliability release.

- Shows the calculated route and map immediately, then enriches the result with parking and charging data in independent background steps.
- Shows loading, success and failure states for supplementary requests, supports individual retries and permits saving a drive when optional POI data is unavailable.
- Removes speed-camera lookup and its request load from the app.
- Adds configurable parking search with a default of 10 results, destination radius, free-only filtering, OpenStreetMap relation support and fee-aware marker colors.
- Adds configurable public charging search with a default of 5 results, destination radius, minimum power, operator and multi-connector filters.
- Adds optional charging-station enrichment and deduplication with the German Federal Network Agency register.
- Supports routing through a selected charger and links the remaining distance to the destination as a walking route.
- Expands parking and charging details with available address, operator, network, connector, power, opening-hours, fee, access, capacity, source and distance data.
- Makes POI detail values selectable and adds a dedicated address copy action.
- Moves Photon and Overpass configuration into one compact dialog with tests placed below their respective endpoint fields.
- Supports multiple ordered Overpass HTTPS endpoints, visible presets, individual enablement and tests, split-server mode or a shared fallback chain.
- Handles HTTP 429, HTTP 5xx, timeouts and short-lived DNS failures with bounded failover, cooldowns and diagnostic reporting.
- Moves parking, charging and fallback-routing options into focused settings dialogs.
- Adds a persistent, closable and copyable network-debug panel unlocked by five taps on `Vibecoded with ❤️`.
- Displays the applied dynamic buffer in the route result and in generated calendar entries.
- Fixes stale duplicate detection after deleting an existing drive entry and keeps an explicit Save anyway action when a conflict remains.
- Adds a GitHub release update check styled consistently with the repository link.
- Extends encrypted backup and restore to the new routing, Photon, Overpass, parking and charging settings.
- Keeps network input HTTPS-only, bounds remote response processing and treats downloaded POI data strictly as data.

## 1.1.0

Feature and reliability release.

- Background next-day processing now runs through WorkManager with foreground-service support and a cancellable progress notification.
- Launcher shortcuts run without opening the main app UI. The next-drive shortcut resolves the next calendar event in the background.
- Automatic processing detects existing Drive Time Notifier entries before making new routing requests to reduce duplicate calendar entries and unnecessary API usage.
- Duplicate handling offers explicit Save anyway and Cancel actions.
- Added stable local identity markers for automatically created drive entries plus compatibility detection for older entries.
- Routing fallbacks now use provider-specific timeouts, so a slow primary provider does not consume the entire fallback window.
- Provider request limits and timeout values are editable in an Advanced section for each routing provider.
- Start and destination geocoding are reused across a fallback chain instead of being repeated for every provider.
- Photon address lookup was optimized to avoid unnecessary global lookup after a local result.
- Encrypted backups now include provider timeout settings.
- Backup import validates the Drive Time Notifier file header before requesting the password.
- Device-specific calendar IDs and calendar start-location assignments are not imported. Source and target calendars must be selected again on the destination device.
- Calendar reselection after import refreshes the current device calendar list before opening the picker.
- Import calendar dialogs can be canceled from the prompt, source picker or target picker without leaving the app stuck in the import flow.
- Imported API keys are only applied after calendar reselection completes successfully, so canceling an import leaves the current installation unchanged.
- Failed provider connectivity states can be long-pressed to re-run the connectivity test without changing the selected provider.
- Debug builds use a separate application ID and app label so they can be installed alongside the official release build.
- Added and extended unit coverage for automatic drive identity and provider defaults.

## 1.0.0

First public release.

- Manual and calendar-based drive planning
- Android source and target calendar selection
- Default and additional saved start locations
- Per-calendar start locations for automatic processing
- Configurable arrival buffers and reminders
- TomTom, Valhalla, openrouteservice, OSRM, GraphHopper, Google Routes and HERE Routing v8
- Ordered fallback routing providers
- Daily, weekly and monthly local request caps
- Individual API and endpoint self-tests with status indicators
- Photon address autocomplete/geocoding
- Optional OpenStreetMap parking and speed-camera enrichment
- Automatic next-day processing with retry and error notifications
- Exclusion rules including regular expressions
- ICS save/share and direct calendar output
- Tasker, MacroDroid and external broadcast integrations
- Per-installation automation token with rotation
- Launcher shortcuts
- Password-protected encrypted settings/API-key backup and import for device migration
- Material You, multiple color palettes and light/dark/system appearance
- German and English UI
- Adaptive, round and legacy launcher icons
- Privacy-oriented routing that does not send calendar titles or descriptions to routing providers
