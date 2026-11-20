# 018: Reading saved data offline

- **Status:** Approved
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
- **Back online**: the first request that reaches the backend ends offline mode. A network returning (Android's
  network callback) also retries. Either way the banner disappears and `DataChanges.changed()` makes the shown
  screens load again.
- **Disabled while offline**, with a short "Not available offline" text where the action is:
  - pull to refresh starts no sync (it only retries the loads),
  - the ride: reanalyse, delete, link to a workout, coach review, save the file,
  - the calendar and its workouts: send a workout or the plan to Garmin, skip, link and unlink a ride, delete a plan,
    dismiss a suggestion, plan adjustment, save the `.zwo`,
  - the training log: import.
- **Size**: at most 50 MB of saved answers. Past that, the answers read least recently are deleted first. Recordings
  are the largest, so they are the first to go in practice.
- **Deleting**: everything saved is deleted when the session ends (logout, `401`, account deleted) and when another
  account logs in. A file that cannot be decrypted (a new Keystore key, a damaged file) is deleted and counts as not
  saved.

## Backend dependencies

None. Only the existing `GET` endpoints above. They need no cache headers, because the app keeps its own copy.

## Screens

- `shell.Shell`: the offline banner (`ic_cloud_off`, Material Symbols Rounded), with the `testTag` `offline-banner`.
- The action buttons listed above: `enabled = !offline` and the "Not available offline" supporting text or a
  snackbar.
- No new screens. The web app has no offline mode.

## Data and storage

- `data.net.OfflineCache`: an OkHttp application interceptor in `Network`, after the session interceptors. It saves
  and reads the listed `GET`s. Each answer goes in its own file under `noBackupFilesDir/offline/` (the name is a hash
  of the request), encrypted with the `ByteCipher` (Tink AEAD, the Keystore key in its own keyset, as
  `EncryptedFileCookieStore`). The file holds the body, its content type and the time it was received.
- `data.net.Connectivity`, app-scoped: `offline: StateFlow<OfflineState?>` (`null` online, otherwise the oldest time
  of the shown data). The interceptor and Android's `ConnectivityManager` callback set it.
- `SessionRepository`: clears `OfflineCache` wherever it clears the cookies.
- No DTO changes.

## Acceptance criteria

- [ ] AC1: A successful `GET` from the list is saved encrypted. A `GET` outside the list, a write and an HTTP error
  are not saved.
- [ ] AC2: Without a connection, a saved request returns the saved answer and the app is offline. An HTTP error from
  the backend is not replaced, and `401` still ends the session.
- [ ] AC3: Starting without a connection with a saved account opens the shell. Without one it shows "backend
  unavailable".
- [ ] AC4: The dashboard, the training log, a ride opened before and the calendar show their saved data offline. A
  request never saved shows the usual error.
- [ ] AC5: The banner shows offline with the oldest time of the served data. It disappears when a request reaches
  the backend or the network returns, and the screens load again.
- [ ] AC6: The listed actions are disabled while offline, and pull to refresh starts no sync.
- [ ] AC7: Past 50 MB the least recently read answers are deleted.
- [ ] AC8: Logout, `401`, account deletion and the login of another account delete everything saved. A file that
  cannot be decrypted counts as not saved.
- [ ] AC9: New strings exist in English and Polish.

## Open questions

- None.
