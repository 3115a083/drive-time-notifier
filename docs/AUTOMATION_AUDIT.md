# Automation audit and regression verification

Date: 2026-10-14. Base: public v1.3.1 / main 4c772db717399bd49621524d40d1cd8f89982852.
Status: fixes proposed on a test branch; public release unchanged.

## Confirmed code defects

1. Source scans included the application's generated drive events when the destination calendar was also selected as a source. Planning could therefore create drives for earlier drives.
2. Duplicate detection ran before potentially long route/enrichment requests, independently in manual and scheduled workers. Concurrent runs could both observe no entry, then both insert.
3. The daily PeriodicWorkRequest used an initial delay with UPDATE. UPDATE preserves the original enqueue time; changing initial delay did not establish a new wall-clock anchor. The 24-hour interval also did not retain the local hour across daylight-saving changes or OS delays.
4. Calendar-read errors during duplicate checks were treated as no match. Some output errors and cancellations were presented as routing failures.
5. Routing failures from a manual next-day scan were labelled automatic, obscuring their origin.
6. The legacy time heuristic could match a different modern marker at the same destination.

No device logs were supplied. These defects explain plausible duplicate and noon-notification paths, but do not prove which path occurred on the reported device.

## Corrected behavior

- Calendar source queries exclude generated marker-bearing and recognised legacy descriptions.
- A shared coroutine mutex protects the final calendar lookup and insert across every calendar-writing app entry point. Calendar-provider checks fail closed. Different source appointments retain different identities; an explicit Save anyway remains available.
- Matching exact identities is not limited to a 24-hour departure window. Legacy time matching applies only to entries without an identity marker.
- The next periodic execution uses setNextScheduleTimeOverride and UPDATE, calculated from the next local configured time. Each automatic run reanchors its successor using current settings. Existing schedules migrate on the first app start and package replacement.
- Scheduled jobs must carry a valid planned time and match the current hour/minute. Execution up to two hours late is permitted for Android/network delays. Later jobs are skipped without routing and reanchored to the next configured time. This intentionally prevents noon catch-up for a missed 21:00 run. Exact execution at 21:00 is still subject to Android's background scheduling.
- The target planning date derives from the original schedule or manual request, not the eventual execution date. Duplicate manual next-day requests use KEEP rather than cancelling a running scan.
- At most 100 persistent diagnostic lines record timestamp/zone, trigger, requested date, hashed identity and result. They contain no event titles, addresses or API credentials and are included in the existing diagnostic copy/export surface.
- Cancellation is propagated. Calendar/output problems produce calendar/output notifications rather than a false routing-retry message.

## Verification

- Unit tests: bounded execution window, pre-start rejection, old-job rejection, settings changes, next local run, 23/25-hour DST days and midnight-delayed planning date.
- Unit tests: 40 concurrent writers create one record; failed checks prevent inserts and release the mutex.
- Android instrumented test: actual CalendarProvider, 20 concurrent writes, exclusion of generated events, distinct nearby appointments, long-trip marker matching and explicit duplicate override. Test data is created in a temporary local calendar and removed afterward.
- CI: unit tests, Android lint, debug/release compilation and Android 15 device tests. Results are reported in the pull request.

Existing device calendar duplicates are not deleted by this change. The separate debug application has its own package/settings; exercise same-calendar source and output selection, manual next-day processing and the scheduled run before rollout.

References:
- https://developer.android.com/reference/androidx/work/PeriodicWorkRequest.Builder#setNextScheduleTimeOverride(long)
- https://developer.android.com/reference/androidx/work/ExistingPeriodicWorkPolicy#UPDATE
