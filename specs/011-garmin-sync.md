# 011: Garmin sync for the admin

- **Status:** Implemented
- **Created:** 2026-11-18

## Problem

The web offers the admin (the app's owner) "Sync from Garmin" in its shell: it fetches the owner's new activities
through the Garmin Personal API (cyclingapp spec 006). In the app the admin has only the intervals.icu sync (spec 007),
so rides recorded on the Garmin reach the app only after a visit to the web.

## Scope

- "Sync from Garmin" for accounts with role `ADMIN`: `POST /api/riders/{id}/garmin/sync`, the result in the shell's
  snackbar.
- Its entry points: a button in the profile, and the pull to refresh on the dashboard and the training log (spec 007),
  which then syncs Garmin as well as intervals.icu.

### Out of scope

- Anything for other accounts: the backend allows the endpoint to admins only, and the app shows nothing of it to
  others.
- Choosing the date to sync from (`since`): like the web, the backend continues from the latest imported activity.
- Garmin Connect (sending workouts, connecting): specs 004, 005 and the backlog.

## Behaviour

One Garmin sync for the whole app (`garmin.GarminSync`, app-scoped like `IntervalsSync`): one at a time, its result
told in the shell's snackbar, forgotten on logout. The request answers when the sync is done (no job to follow).

- **Profile**: for an admin, a "Garmin sync" card (above the account card) with the text "Fetches your new activities
  from Garmin (Personal API of the owner)." and **Sync from Garmin** (icon `sync`), disabled with "Syncing…" while a
  Garmin sync runs. Accounts without the role see no card.
- **Pull to refresh** on the dashboard and the training log: for an admin, also starts a Garmin sync unless one runs
  or the last one ended less than 60 s ago (the throttle of spec 007). The pull's own reload is not held up by it.
- **Result** (the web's texts):
  - `imported > 0` → "New activities from Garmin: {count}", `DataChanges.changed()` (screens that show rides load
    again).
  - `imported == 0` → "No new activities since {since}". After a pull this is quiet, as spec 007's empty sync.
  - Failure → "Garmin sync failed: {error}" (`toUiText`); after a pull a network error is quiet (the screen's own
    reload tells it), as in spec 007.
- While a Garmin sync runs the dashboard and the training log show the sync line of spec 007 with "Syncing from
  Garmin…" (no progress numbers: the backend gives none).
- The request may take long (the backend fetches from Garmin): it uses a read timeout of 120 s.

## Backend dependencies

Existing only: `POST /api/riders/{riderId}/garmin/sync` (role `ADMIN`, optional `since`) → `GarminSyncResult`
(`since`, `fetched`, `imported`); `403` for other accounts (cyclingapp spec 006). The account's `role` comes with
`AccountResponse` (already read). No backend change.

## Screens

- Profile: `GarminSyncCard` (`ProfileCard`, `Button` with icon `sync`).
- Dashboard and training log: the existing sync line (spec 007) with the Garmin text.
- Shell: the snackbar of `GarminSync`'s messages, like `IntervalsSync`'s.

## Data and storage

- `data/api`: `GarminSyncResult(since, fetched, imported)` and the call `syncGarmin(riderId)` (in a new `GarminApi`
  or `ProfileApi`), with the longer timeout for this call only.
- Nothing stored on the device.

## Acceptance criteria

- [x] AC1: Only an admin sees the profile's Garmin sync card; others see nothing of Garmin sync and their pull sends
  no Garmin request.
- [x] AC2: "Sync from Garmin" sends the request once, shows "Syncing…" while it runs and the result in the snackbar:
  new activities (data changed) or nothing new since the date.
- [x] AC3: A failure shows "Garmin sync failed: {error}".
- [x] AC4: An admin's pull to refresh starts a Garmin sync too, at most once per 60 s and not while one runs; an empty
  result and a network error after a pull are quiet.
- [x] AC5: The dashboard and the training log show the Garmin sync line while it runs.
- [x] AC6: Logout forgets the sync.
- [x] AC7: New strings exist in English and Polish; counted texts are plurals.

## Open questions

- None.
