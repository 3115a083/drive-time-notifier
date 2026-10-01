# Drive Time Notifier with Apple Shortcuts

[Deutsch and detailed action-by-action recipe](README.md)

An Apple Shortcut can calculate a drive using a configured routing provider, add fixed and dynamic arrival buffers, and create a calendar event with an alert. It needs Apple's built-in Shortcuts and Calendar apps, with no dedicated application, website or Apple Developer Program membership.

## Download and import status

This folder currently provides a construction guide and [downloadable example configuration](config.example.json). **It does not contain a signed, device-tested `.shortcut` file.** The JSON file configures a workflow; importing JSON does not install a Shortcut. Build the native actions described below once in Apple's editor.

A completed Shortcut can be shared from an iPhone, iPad or Mac as an iCloud link or a file for anyone. Remove personal locations, calendar selections and API credentials before distributing it. Each recipient supplies their own configuration and grants their own permissions.

Externally generated Shortcut files require Apple signing. A valid workflow file can be signed on macOS:

```sh
shortcuts sign --mode anyone --input Drive-Time-Notifier.unsigned.shortcut --output Drive-Time-Notifier.shortcut
```

This signs an existing workflow; it does not create one from the configuration. Apple's signing tools are unavailable in this project's Linux build environment. No unsigned file is presented as an installable automation. The recipe has not been executed on an Apple device yet.

## Configuration

Save `config.example.json` as `config.json`, for example in `iCloud Drive/Shortcuts/DriveTimeNotifier/`, or locally under On My iPhone. Select that exact file in a File/Get File from Folder action. Read its contents as text, then use Get Dictionary from Input and Get Dictionary Value to load settings.

| Setting | Supported values |
| --- | --- |
| `provider` | `osrm`, `google`, `tomtom`, `here` |
| `osrm_base_url` | HTTPS OSRM server root |
| Provider API keys | A key for the selected commercial provider |
| `fixed_buffer_minutes` | Integer 0–180 |
| `dynamic_buffer_enabled` | Boolean |
| `dynamic_buffer_level` | `low`, `balanced`, `cautious` |
| `reminder_lead_minutes` | Integer 0–180; select a supported alert offset in the Calendar action |
| Default origin coordinates | Both empty, or valid latitude and longitude |
| `show_confirmation` | `true` for interactive planning, `false` for a validated unattended workflow |

Select the actual destination calendar in the Add New Event action. A calendar name stored as text is not a replacement for selecting a calendar object.

Google requires Routes API enablement and billing setup. TomTom and HERE require their own provider accounts. Provider charges and quotas apply. This JSON file is not an encrypted credential store; an iCloud location syncs it through the user's account. Never publish populated configurations or keys. OSRM's public demo endpoint has no guaranteed availability; configure an appropriate HTTPS server for regular use. It does not provide live traffic in this workflow.

## Native workflow

1. Enable Show in Share Sheet, accepting text, locations and URLs. A manual/Siri launch also works.
2. Get Locations from Input. If the input cannot produce a location, ask for a destination address. Do not send an unresolved map shortlink to a routing service as an address.
3. Choose a configured origin, Get Current Location, or ask for an origin address. Ask for the desired arrival date and time.
4. Get Details of Locations to obtain latitude/longitude. Apple's location actions resolve addresses; the selected provider calculates the route. Validate coordinate ranges and format URL numbers with decimal points.
5. Branch on `provider`. Use URL/Get Contents of URL, then Get Dictionary Value/Get Item from List to extract duration and baseline duration in seconds. JSON index 0 means the first list item, which the editor calls item 1.
6. Calculate the buffers below. Use Adjust Date to subtract rounded-up driving minutes and total buffer from arrival. Warn before saving a past departure; skip it during unattended operation.
7. Find Calendar Events in the selected destination calendar and compare the exact notes marker `DTN-IOS|{arrival ISO date}|{origin coordinates}|{destination coordinates}`. Search arrival minus one day through arrival plus one day. Update one matching event; stop on multiple matches. Otherwise Add New Event.
8. Event title: drive to the destination. Start: departure. End: arrival. Location: destination. Notes: marker, provider, origin, destination, drive duration, fixed buffer and dynamic buffer. The event blocks the full drive plus buffer interval.
9. Set a Calendar alert at event start or the chosen supported offset before it. If an iOS version cannot accept a variable alert offset, configure it directly in the action. Show Notification only displays an immediate message and does not schedule the later alert. Enable Calendar notifications in iOS.

All network/permission errors and invalid/empty route responses must stop without creating a fabricated zero-duration event. Do not display full URLs containing credentials in error messages.

## Provider requests

The [German guide](README.md#routing-anfragen) includes complete payloads and field-by-field native action instructions.

| Provider | Request and duration extraction |
| --- | --- |
| OSRM | GET `{base}/route/v1/driving/{origin_lon},{origin_lat};{destination_lon},{destination_lat}?overview=false&steps=false&alternatives=false`. Require `code=Ok`; first `routes` item, `duration` in seconds. Baseline equals duration. |
| Google | POST `https://routes.googleapis.com/directions/v2:computeRoutes`. Headers `X-Goog-Api-Key`, `Content-Type: application/json`, `X-Goog-FieldMask: routes.duration,routes.staticDuration`. JSON origin/destination `location.latLng`, `travelMode=DRIVE`, `routingPreference=TRAFFIC_AWARE`. First route's `duration` and `staticDuration` strings have a trailing `s`, which must be removed before numeric conversion. |
| TomTom | GET `https://api.tomtom.com/routing/1/calculateRoute/{origin_lat},{origin_lon}:{destination_lat},{destination_lon}/json?key={key}&travelMode=car&traffic=true&departAt=now&routeType=fastest&computeTravelTimeFor=all&maxAlternatives=0`. First route's `summary.travelTimeInSeconds` and `noTrafficTravelTimeInSeconds`. |
| HERE | GET `https://router.hereapi.com/v8/routes?transportMode=car&routingMode=fast&origin={lat},{lon}&destination={lat},{lon}&return=summary&departureTime=now&apikey={key}`. Sum `summary.duration` and `summary.baseDuration` over **every section** of the first route. |

If a baseline duration is absent, use that route/section's duration instead. Google, TomTom and HERE are traffic-aware in these requests. The simple requests estimate traffic at execution time; they are not a guarantee of future traffic at an arbitrary arrival time. Recalculation should update the same calendar event.

## Buffer calculation

Use raw duration seconds for choosing a tier. These tiers match Android's `DynamicArrivalBuffer.kt`.

| Level | Exclusive upper limits in minutes, then remaining durations | Base buffer minutes |
| --- | --- | --- |
| low | 30, 60, 120, 180, remaining | 0, 2, 4, 7, 10 |
| balanced | 20, 45, 90, 150, 240, remaining | 0, 3, 6, 10, 15, 20 |
| cautious | 20, 45, 90, 150, 240, remaining | 2, 5, 10, 15, 22, 30 |

When dynamic buffer is disabled, `extra=0`. Otherwise use If/Otherwise branches to select the tier and Round Number, always round up, zero decimal places:

```text
minutes = duration_seconds / 60
traffic_delay = max(0, duration_seconds - baseline_seconds)
OSRM reserve = min(10, ceil(minutes * 0.03))
Traffic-aware reserve = min(5, ceil(traffic_delay / 60 * 0.10))
extra = min(45, base + reserve)
total_buffer = min(180, fixed_buffer_minutes + extra)
departure = arrival - ceil(minutes) minutes - total_buffer minutes
```

Already included traffic delay is not added a second time. Example: a 60-minute OSRM drive, 10-minute fixed buffer and balanced tier yield 6+2 dynamic minutes, hence departure at 08:42 for arrival at 10:00.

## Optional daily automation

Create a second Shortcut that finds tomorrow's non-all-day events with a location in selected source calendars, excluding the drive calendar. Derive tomorrow midnight and the following midnight as calendar dates, not by adding 24 hours. Process in start-time order.

Pass a dictionary to the route Shortcut using Run Shortcut: `destination`, `arrival`, `origin_latitude`, `origin_longitude`, `title`. At the route Shortcut's start, detect this dictionary and bypass interactive inputs. Use a fixed configured origin for unattended operation. Do not require fresh location access, confirmation or Ask Each Time file/calendar selection. Count and report skipped invalid events.

Each user adds a personal Time of Day automation that runs this Shortcut daily, choosing Run Immediately where available. Importing a shared Shortcut does not install a time trigger or grant permissions.

## Before distributing an importable Shortcut

On a real Apple device, test all providers using valid credentials, multi-section HERE responses, locale formatting, buffer boundaries, disabled dynamic buffer, calendar alerts, deduplication, overnight and DST dates, offline operation and missing keys. Remove credentials and personal configuration, export through Apple, and test the signed file/link import on a second device.

Official references: [Apple sharing](https://support.apple.com/guide/shortcuts/apdf01f8c054/ios), [Apple automations](https://support.apple.com/guide/shortcuts/apdfbdbd7123/ios), [OSRM](https://project-osrm.org/docs/v5.24.0/api/#route-service), [Google](https://developers.google.com/maps/documentation/routes/reference/rest/v2/TopLevel/computeRoutes), [TomTom](https://docs.tomtom.com/routing-api/documentation/tomtom-maps/v1/calculate-route), [HERE](https://docs.here.com/routing/docs/routing-v8-route-summary).
