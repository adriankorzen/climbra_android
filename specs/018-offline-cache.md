# 018: Reading saved data offline

- **Status:** Implemented
- **Created:** 2026-11-20

## Problem

Every screen loads from the backend each time. Without a connection (a ride in the mountains, a plane, a gym in a
basement) the app shows only errors: at start it stops on "backend unavailable" (`SessionState.Unavailable`), and the
calendar with today's workout, the latest rides and the training load cannot be read. The rider most often needs the
plan for today exactly where the signal is bad.

## Scope

- **Read only**: the dashboard, the training log, the details of rides opened before, the calendar (weeks and months
  loaded before) and its planned workouts show the backend's last answers when the backend cannot be reached.
- Starting the app offline with a stored session opens the shell with the saved account.
- A banner says the app is offline and how old the shown data is.
- Actions that send something are disabled while offline.
- The saved answers are encrypted and deleted when the session ends.

### Out of scope

- Changes made offline and sent later (a queue of writes).
- The coach (conversations, reviews) and the profile: not saved, they show their usual "no connection" error.
- Loading data ahead (weeks or rides not opened before); only answers the app has already received are saved.
- Ride files (`/file`, `.zwo`), the import, the syncs (intervals.icu, Garmin).

## Behaviour

- **Saving**: every successful (`2xx`) answer to a `GET` from the list below is saved, the whole body as received,
  with the time it was received. A later answer to the same request (method, path and query) replaces it. The app
  computes nothing from the saved data: it is the same backend answer the screen would have read.
  - `GET /api/auth/me`, `GET /api/riders`
  - `GET /api/riders/{id}/training-load`, `GET /api/riders/{id}/analyses`, `GET /api/riders/{id}/activities`
  - `GET /api/riders/{id}/rides` (each sort and filter is its own request)
  - `GET /api/activities/{id}`, `GET /api/activities/{id}/analysis`, `GET /api/activities/{id}/recording`
  - `GET /api/riders/{id}/calendar` (the calendar's weeks and months, and the ranges around a day that the ride and
    workout screens load for linking)
- **Reading**: when such a request fails without an answer from the backend (no network, timeout, DNS, connection
  refused), the saved answer is returned instead, and the app is **offline**. An HTTP error from the backend (`4xx`,
  `5xx`) is never replaced by a saved answer: `401` still ends the session (spec 001).
- **No saved answer**: the screen shows its present error ("no connection") with Retry, as today.
- **Start offline**: `GET /api/auth/me` returns the saved account, so the shell opens (`LoggedIn`). Without a saved
  account (first start after this update, or after a logout) the app stays on the present "backend unavailable"
  screen.
- **Banner**: while offline, a banner under the top of the shell says "Offline · data from {date time}". The time is
  the oldest save among the answers served from the cache since the app went offline. The banner has a Retry.
- The app is offline only once a saved answer was served. A request with nothing saved fails as before and shows no
  banner.
- **Back online**: the first request that reaches the backend, with any answer, ends offline mode. The banner then
  disappears and the shown screens load again: the dashboard, the training log and the calendar through
  `DataChanges.changed()`, the ride and the workout through `Connectivity.reloads`.
- **Retry**: the banner's Retry, and a network that comes back (Android's default network callback), make the
  shown screens load again while offline. Whether they reach the backend decides whether offline mode ends.
- **Disabled while offline**:
  - pull to refresh starts no sync (it only retries the loads); the profile's "Sync now" still tries,
  - the ride: reanalyse, delete, save the file (the menu starts with "Not available offline"), the interval source,
    the ad hoc detection, linking to a workout, the coach review,
  - the workout: skip and undo, link and unlink a ride, send to Garmin, save the `.zwo`,
  - the calendar's plan cards: adjust, ask the coach about the suggestion, dismiss it, remove the plan; sending the
    plan to Garmin,
  - the training log's import button. The empty dashboard's and training log's "Import rides" still open the import
    screen, which shows its own error.
- "Not available offline" (`ui.common.OfflineNote`) shows where the actions are: the ride's menu, its link and
  coach cards, the workout's actions and each plan card.
- **Size**: at most 50 MB of saved answers. Past that, the answers read least recently are deleted first. Recordings
  are the largest, so they are the first to go in practice.
- **Deleting**: everything saved is deleted when the session ends (logout, `401`, account deleted) and when another
  account logs in. A file that cannot be decrypted (a new Keystore key, a damaged file) is deleted and counts as not
  saved.

## Backend dependencies

None. Only the existing `GET` endpoints above. They need no cache headers, because the app keeps its own copy.

## Screens

- `shell.Shell`: the offline banner (`shell.OfflineBanner`, `ic_cloud_off`, Material Symbols Rounded) above the
  tabs' content, with the `testTag` `offline-banner` and Retry. The shell provides `ui.common.LocalOffline` to the
  screens.
- The action buttons listed above: `enabled = … && !LocalOffline.current`, and `OfflineNote`.
- No new screens. The web app has no offline mode.

## Data and storage

- `data.net.OfflineInterceptor`: an OkHttp application interceptor in `Network`, innermost, after the session and
  CSRF interceptors. It saves and serves the listed `GET`s. A cancelled call is not offline.
- `data.net.OfflineCache`: each answer goes in its own file under `noBackupFilesDir/offline/`. The file name is a
  hash of the request's path and query. The file is encrypted with a `KeystoreCipher` of its own
  (`KeystoreCipher.Key.OFFLINE`: Tink AES-256-GCM, its keyset wrapped by its own Keystore key). It holds the time
  the answer was received, the content type and the body. The least recently read files go past 50 MB: reading
  touches the file's time.
- `data.net.Connectivity`, app-scoped: `offline: StateFlow<OfflineState?>` (`null` online, otherwise the oldest time
  of the shown data) and `reloads`. The interceptor sets it, and `di.AppModule` registers the network callback.
- The manifest gains `ACCESS_NETWORK_STATE` for the network callback.
- `SessionRepository`: clears `OfflineCache` and `Connectivity` wherever it clears the cookies, and after a login
  (email or Google).
- No DTO changes.

## Acceptance criteria

Tested by `OfflineCacheTest` (the HTTP stack and the session against `TestBackend`, which can go offline), the
ViewModel tests of the dashboard, the training log, the ride and the calendar, `IntervalsSyncTest`, `OfflineShellTest`
and the screen tests (`LocalOffline`).

- [x] AC1: A successful `GET` from the list is saved encrypted. A `GET` outside the list, a write and an HTTP error
  are not saved.
- [x] AC2: Without a connection, a saved request returns the saved answer and the app is offline. An HTTP error from
  the backend is not replaced, and `401` still ends the session.
- [x] AC3: Starting without a connection with a saved account opens the shell. Without one it shows "backend
  unavailable".
- [x] AC4: The dashboard, the training log, a ride opened before and the calendar show their saved data offline. A
  request never saved shows the usual error.
- [x] AC5: The banner shows offline with the oldest time of the served data. It disappears when a request reaches
  the backend (also after a retry when the network returns), and the screens load again.
- [x] AC6: The listed actions are disabled while offline, and pull to refresh starts no sync.
- [x] AC7: Past 50 MB the least recently read answers are deleted.
- [x] AC8: Logout, `401`, account deletion and the login of another account delete everything saved. A file that
  cannot be decrypted counts as not saved.
- [x] AC9: New strings exist in English and Polish.

## Open questions

- None.
