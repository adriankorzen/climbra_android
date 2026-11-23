# 025: Automatic sync and new ride notifications

- **Status:** Implemented
- **Created:** 2026-11-23

## Problem

Rides from intervals.icu reach the app only after a pull to refresh or "Sync now" (spec 007). The backend now syncs
every 30 minutes on its own (cyclingapp spec 060) and pushes when it imported rides; the app has to show those
notifications, open the new ride, and let the rider turn the automatic sync off.

## Scope

- A switch "Sync automatically (every 30 minutes)" in the profile's intervals.icu section, and the text when
  intervals.icu rejected the key.
- Notifications for the pushes `RIDE_SYNCED` (one ride) and `RIDES_SYNCED` (several), in a channel of their own.
- A tap opens the ride (one) or the training log (several).
- In the foreground: no notification, the screens that show rides reload.

### Out of scope

- The ride's name or figures in the notification: the push carries only the type and ids (spec 020's decision).
- A sync on the device in the background (WorkManager): the backend syncs.
- Garmin (cyclingapp spec 060 keeps it manual).

## Behaviour

- Profile, intervals.icu connected: a `Switch` row under the connection (as the web's section), its state from
  `IntervalsConnectionResponse.autoSync`. Toggling sends `PUT /api/riders/{id}/intervals/auto-sync`; while it is
  sent the switch is disabled; a failure puts it back and shows the error in the snackbar. Offline: disabled
  (`LocalOffline`).
- `autoSyncStopped = KEY_REJECTED`: the row reads "intervals.icu rejected the key. Connect again to sync
  automatically." and the switch is off.
- `PushMessages` parses `RIDE_SYNCED` (`activityId`) and `RIDES_SYNCED` (`jobId`); other accounts' pushes are dropped
  as today.
- Foreground (`AppForeground`): `DataChanges.changed()`, no notification.
- Background: `PushNotifier` shows, in the new channel `rides` ("New rides"):
  - one ride: "New ride synced" / "Tap to see its analysis", tag per ride; tap → `PushTarget.Ride(activityId)`, the
    ride on the training log's tab (two panes from 840 dp, spec 019);
  - several: "New rides synced" / "Tap to see them in the training log", tag per job; tap → the training log.
- The notification permission is asked as today (after the first coach request, spec 020); the profile's
  notification row lists the new channel through the system's settings.
- "Sync now" / pull to refresh while an automatic sync runs: the backend answers `409`; the
  snackbar reads "A sync is already running" (existing translation, checked).

## Backend dependencies

- cyclingapp spec 060: `IntervalsConnectionResponse.autoSync`, `autoSyncStopped`;
  `PUT /api/riders/{id}/intervals/auto-sync` `{ enabled }` → `IntervalsConnectionResponse`; push types
  `RIDE_SYNCED`, `RIDES_SYNCED`.

## Screens

- Profile → intervals.icu section: Material 3 `ListItem` with a `Switch` (web: `profile/intervals-section.ts`).
- Notifications: channel `rides`, icon `ic_directions_bike` (Material Symbols Rounded).

## Data and storage

- `data/api`: `IntervalsConnectionResponse.autoSync` (default `false` when missing), `autoSyncStopped`;
  `AutoSyncRequest(enabled)`; `ProfileApi.setIntervalsAutoSync`.
- `push.PushTarget.Ride(activityId)`.
- Strings in `values` and `values-pl`.

## Acceptance criteria

- [x] AC1: The profile shows the switch with the connection's state; toggling sends the request, disables the switch
  while sending, and puts it back with an error on failure.
- [x] AC2: `autoSyncStopped = KEY_REJECTED` shows the rejected key's text and the switch off.
- [x] AC3: The switch is disabled offline.
- [x] AC4: `RIDE_SYNCED` in the background shows the one-ride notification in the `rides` channel; its tap opens the
  ride on the training log's tab.
- [x] AC5: `RIDES_SYNCED` shows the several-rides notification; its tap opens the training log.
- [x] AC6: Either push in the foreground shows nothing and calls `DataChanges.changed()`.
- [x] AC7: Another account's push is dropped.

## Open questions

- None.
