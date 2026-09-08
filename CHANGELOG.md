# Changelog

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
