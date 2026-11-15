# 007: Pull to refresh syncs the connected services

- **Status:** Implemented
- **Created:** 2026-11-15

## Problem

Pull to refresh on the dashboard (spec 002) and the training log (spec 003) only loads the backend's data again. A
ride recorded a moment ago reaches Climbra only after the rider opens the profile and taps "Sync now" in the
intervals.icu section (spec 004). Riders expect the pull gesture on the screens that show their rides to fetch new
rides from the services they connected. For now that is intervals.icu only.

## Scope

- Pull to refresh on the dashboard and on the training log also starts an intervals.icu sync when the rider has a
  connection.
- One app-scoped sync shared by the dashboard, the training log and the profile's "Sync now": one sync at a time, its
  progress visible on all three, its result shown once.
- A sync progress line on the dashboard and the training log while a sync runs.

### Out of scope

- Garmin sync (`POST /api/riders/{id}/garmin/sync`, owner only on the web): a later spec.
- Automatic / background / scheduled sync (WorkManager, sync on app start): the rider starts each sync, as in
  cyclingapp spec 005.
- Pull to refresh on the calendar, the coach or the ride detail: they keep their plain reload.
- Following a sync started elsewhere (the web): the backend has no "current sync" endpoint; a `409` for a running
  sync is reported as such (see Behaviour).

## Behaviour

### Pull to refresh

1. The pull reloads the screen's data at once, as today (the spinner of `PullToRefreshBox` is this reload only, it
   does not wait for the sync).
2. At the same time the app asks `intervals.IntervalsSync` to start a sync for the rider:
   - The connection status (`GET /api/riders/{id}/intervals`) is read once per rider and kept; it is read again after
     the profile connects or disconnects intervals.icu, and on each pull while it is "not connected" or unknown
     (a failed read), so a connection made on the web is picked up.
   - Not connected → no sync, no message: the pull is a plain reload.
   - A sync already running in the app → nothing new is started (its progress is shown already).
   - Otherwise `POST /api/riders/{id}/intervals/sync`, then the job is polled every 2 s
     (`GET /api/imports/{jobId}`) while `QUEUED` / `RUNNING`.
3. **Throttle:** a pull within 60 s after the last sync of the app *ended* starts no new sync (pulling twice should not
   queue two syncs on the backend); it is a plain reload. The profile's "Sync now" is not throttled.

### While syncing

- The dashboard and the training log show a thin `LinearProgressIndicator` with "Syncing with intervals.icu…" at the
  top of their content (tag `sync-progress`); indeterminate while `QUEUED` or while the job does not know its rides
  yet, else determinate `processedFiles / selectedRides` with "… {processed} of {total}".
- The profile's "Sync now" shows the same running state as today (disabled, "Syncing…"), whichever screen started it.
- Polling runs in the app scope: it continues while the rider switches tabs and while the app is in the background
  for as long as the process lives; it stops on logout (session end), like `coach.CoachActivity`.

### Result

- When the job ends, a snackbar on the screen that is shown (dashboard, training log or profile; through one
  app-scoped message flow that the shell's `SnackbarHost` shows): the texts of spec 004 —
  "intervals.icu sync finished – imported rides: {n}" (plus "not available: {m}" when `unavailable > 0`) or "The
  intervals.icu sync failed: {message}".
- A sync that imported nothing and found nothing unavailable shows **no** snackbar when it was started by a pull (the
  common case: the rider pulled to check, nothing new); started by "Sync now" it shows the "imported rides: 0" text as
  today, and "intervals.icu sync started" when the job is accepted, as today.
- `imported > 0` → `DataChanges.changed()`: the shown screen loads again (a dashboard / log that is shown reloads at
  once, not only when shown again; a load still running is started again, as it may predate the import), so the new
  rides appear without another pull. The profile reloads its intervals status (last sync) whenever a sync ends.

### Errors

- Starting the sync fails: `409` (a sync already queued or running on the backend, e.g. started on the web, or the
  stored key cannot be decrypted; the backend sends no code to tell them apart) shows "The intervals.icu sync cannot
  start: one is already running, or intervals.icu needs to be connected again in the profile"; `503` shows "The
  intervals.icu integration is not configured on the server" (spec 004's text); after either the connection status is
  read again on the next pull. Network errors of the start show the usual error snackbar only when started by
  "Sync now"; after a pull the screen's own reload already shows the network error.
- A failed poll ends the tracking with the error snackbar (as spec 004 today); the job may still run on the backend.
- Offline pull: the reload fails as today; no sync is attempted after the status read fails.

## Backend dependencies

Existing only (cyclingapp spec 005): `GET /api/riders/{id}/intervals` (`IntervalsConnection`),
`POST /api/riders/{id}/intervals/sync` (`202` `ImportJob`, `409`, `503`), `GET /api/imports/{jobId}`. No backend
change.

## Screens

- Dashboard (`dashboard.DashboardScreen`) and training log (`activities.ActivitiesScreen`): the sync progress line
  above the content inside the existing `PullToRefreshBox`; the empty states' text keeps its link to the web import,
  and also mentions pulling down to sync when intervals.icu is connected.
- Profile: no visual change; "Sync now" goes through `IntervalsSync`.
- Material 3: `LinearProgressIndicator`, `Snackbar`. No new icons.

## Data and storage

- `ImportJob` (in `data/api/ProfileApi.kt`) gains `processedFiles: Int = 0` and `selectedRides: Int? = null`
  (mirrors `upload.ActivityImportJobResponse`).
- New app-scoped `intervals.IntervalsSync` (Hilt `@Singleton`): `state: StateFlow<SyncState>` (running, processed /
  total, connected), `messages: Flow<UiText>` (merged with the coach's notices into the shell's snackbar),
  `finished` / `imported` counters, `sync(riderId, origin = PULL | BUTTON)`, `connectionChanged(connected, riderId)`
  (the profile tells every status it reads, connects and disconnects); cleared on session end. The profile's sync
  code moved there. Nothing is stored on the device.

## Acceptance criteria

- [x] AC1: A pull on the dashboard with intervals.icu connected reloads the data and starts a sync
  (`POST …/intervals/sync`); without a connection it only reloads and sends no sync request.
  (`DashboardViewModelTest`, `IntervalsSyncTest.aPullWithoutAConnectionDoesNotSync`)
- [x] AC2: The same for a pull on the training log; a search or period change does not sync. (`ActivitiesViewModelTest`)
- [x] AC3: While the job is `QUEUED` / `RUNNING` the dashboard and the training log show `sync-progress`, determinate
  with "{processed} of {total}" once `selectedRides` is known; the profile's "Sync now" is disabled meanwhile.
  (`IntervalsSyncTest.aRunningSyncShowsItsProgress`, `DashboardScreenTest`, `ActivitiesScreensTest`,
  `ProfileViewModelTest.aSyncStartedElsewhereDisablesSyncNow`)
- [x] AC4: Only one sync runs: a pull or "Sync now" during a running sync sends no second `POST`.
- [x] AC5: A pull within 60 s after a sync ended sends no `POST`; "Sync now" does.
- [x] AC6: A finished sync with `imported > 0` shows the "imported rides" snackbar and calls `DataChanges.changed()`;
  the shown dashboard / log loads again.
- [x] AC7: A pull-started sync with nothing imported and nothing unavailable shows no snackbar; "Sync now" shows it.
- [x] AC8: A failed job, a `409` / `503` at the start and a failed poll show their messages; tracking stops.
- [x] AC9: The sync is followed in the app scope (independent of the shown screen) and stops on logout.
- [x] AC10: The connection status is read again after the profile connects or disconnects intervals.icu, and on a
  pull while it is not connected. (`ProfileViewModelTest.connectingTellsTheSync`, `IntervalsSyncTest`)
- [x] AC11: New strings exist in English and Polish. (`StringResourcesTest`)

## Open questions

- None.
