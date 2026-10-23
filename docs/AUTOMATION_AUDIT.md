# Automation audit

Date: 2026-10-23. Base: main 2576c2d67edc94e67f0c4e8e47e2c9c29fe6606e.

The latest CI hardening and hands-off shortcut/authenticated automation changes are preserved.

Confirmed defects: generated drives were included in source scans; separate pre-routing duplicate checks raced; calendar lookup failures could be treated as no match; periodic UPDATE retained its old enqueue-time anchor; manual routing errors were labelled automatic. Device logs are unavailable, so the precise cause of the reported noon notification is not proven.

Corrections: source filtering; shared final lookup/write mutex; atomic provider batch for events/reminders; updates preserve event IDs and newer calculations; confirmed own duplicates for the same origin, destination and appointment are consolidated; exact identity lookup supports trips longer than 24 hours. Legacy fuzzy matching applies only without a modern marker.

Scheduling uses an explicit next local execution override, reanchored after each scheduled run. Jobs outside a two-hour grace window and stale manual jobs are skipped. Planning dates derive from the original request. Existing jobs migrate at first app startup and package replacement. WorkManager execution can still be delayed by Android.

Execution history stores at most 100 timestamped records with trigger and result, without titles, addresses or credentials. Output errors are separate from routing errors and cancellation propagates.

Tests cover concurrency, provider writes and updates, reminders, stale results, exact and legacy identities, source exclusion, early/late jobs, midnight delays and 23/25-hour DST days. CI results pending.

The mutex covers a single app process. Public and debug installations do not share it. Enable automation in only one installation when they write to the same calendar.

Public release unchanged.
