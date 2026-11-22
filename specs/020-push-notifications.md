# 020: Push notifications

- **Status:** Implemented
- **Created:** 2026-11-21

## Problem

The coach answers in the background and imports run as jobs, but the app learns that one ended only by polling while
it is in the foreground (`coach.CoachActivity`, `activities.RideImport`). A rider who asked the coach and put the
phone away finds the answer only when they open the app again. They should get a notification when it is ready.

## Scope

- Firebase Cloud Messaging in the app: the token sent to the backend after a login and when it changes, removed
  before a logout.
- Notifications for: the coach answered, the coach could not answer, an import finished, an import failed
  (cyclingapp spec 056) — also for requests and imports started on the web.
- Tapping a notification opens the conversation or the training log.
- Asking for the notification permission (Android 13+) when it is first useful, and an entry in the profile.

### Out of scope

- Notifications about rides synced from intervals.icu or Garmin: the backend syncs only when asked and the app waits
  for the answer (BACKLOG, needs a background sync on the backend first).
- Per-type switches on the backend: Android's notification channels turn types off (decided 2026-11-21).
- The content of the answer in the notification: the message carries only the type and ids (decided 2026-11-21).

## Behaviour

### Token

- Firebase is set up in code from `BuildConfig` (`FirebaseOptions`: application id, API key, project id, sender id from
  `local.properties` / the release settings: `climbra.firebase.*`), not from `google-services.json` and its Gradle
  plugin, like the Google login's client id. Empty settings → no Firebase, no token, no notifications (local and CI
  builds work without a Firebase project).
- When the session becomes logged in (`SessionRepository.state`), the app gets the FCM token and sends
  `PUT /api/push-devices`; `FirebaseMessagingService.onNewToken` sends the new one when logged in. Failures are
  ignored (sent again at the next start or login). Offline: not sent (no queue).
- `SessionRepository.logout()` sends `DELETE /api/push-devices` with the token first (at most 3 s, failure ignored),
  then logs out as before, and deletes the token on the device (`FirebaseMessaging.deleteToken`), so a next account
  gets a new one. A session that ended without a logout (`401`) keeps the token; messages for it are dropped (below).

### Messages

`ClimbraMessagingService` receives the data messages:

- `userId` is not the logged-in account (or none is logged in): dropped.
- The app is in the **foreground** (the same `foreground` flow `CoachActivity` uses): no notification. The screens
  reload instead: a coach type calls `CoachActivity.changed()` (and the credits reload), an import type
  `DataChanges.changed()`; the snackbars of a followed request / import tell the end as today.
- In the **background**:

  | `type` | Channel | Title | Text | Tap |
  |---|---|---|---|---|
  | `COACH_ANSWERED` | Coach | "The coach has answered" | "Open the conversation to read it." | the conversation (Coach tab, `ShellNavigator`) |
  | `COACH_FAILED` | Coach | "The coach could not answer" | "Open the conversation to try again." | the conversation |
  | `IMPORT_FINISHED` | Imports | "Rides imported" | "The import has finished. See your rides in the training log." | the training log |
  | `IMPORT_FAILED` | Imports | "The import failed" | "Open the app to see what went wrong." | the training log |

  One notification per conversation / job (its id as the notification id, so a newer one replaces it), auto-cancel,
  the app's small icon. Unknown `type`: dropped (an older app with a newer backend).
- During a large import (`activities.ImportSession`, spec 014) the import types are dropped: its foreground
  notification already reports the end, and every batch is a job of its own.
- The tap's `PendingIntent` opens `MainActivity` (`singleTask`) with the target in its extras; `receive(intent)`
  hands it to the shell, which opens it when the shell is shown (like `SharedRideFiles`); logged out → the login
  first, the target is dropped. Opening a conversation clears its notification.

### Channels and permission

- Two channels, `coach` ("Coach answers") and `imports` ("Imports"), default importance; the import progress channel
  of spec 014 stays as is.
- Android 13+: the app asks for `POST_NOTIFICATIONS` after the first coach request it accepted and before an import
  (spec 014 asks there already), at most once each unless the rider later taps the profile entry; refused → no
  question again.
- Profile → "Notifications": shows on / off (permission and channels) and opens the app's system notification
  settings. Hidden without Firebase.

## Backend dependencies

- cyclingapp spec 056 (new): `PUT /api/push-devices`, `DELETE /api/push-devices` (`{ token, platform }`) → `204`; the
  data messages `COACH_ANSWERED`, `COACH_FAILED` (`conversationId`), `IMPORT_FINISHED`, `IMPORT_FAILED` (`jobId`), each
  with `userId`.

## Screens

- No new screen. Notifications (`NotificationCompat`, icons `ic_notifications` Material Symbols), the profile's row
  "Notifications" (`ListItem`, opens `Settings.ACTION_APP_NOTIFICATION_SETTINGS`).

## Data and storage

- `data/api`: `PushDeviceRequest`, `PushApi`. `push.PushTokens` (app-scoped: register / unregister),
  `push.ClimbraMessagingService`, `push.PushNotifications` (channels, building, the tap's target), `push.PushTarget`.
- DataStore: whether the permission was asked after a coach request. The token is not stored by the app (FCM keeps
  it).
- Dependencies: Firebase BOM + `firebase-messaging` in `libs.versions.toml`. `README.md`: the Firebase project and
  the `climbra.firebase.*` settings; the release settings in CI secrets.

## Acceptance criteria

- [x] AC1: After a login the token is sent with `PUT /api/push-devices`; a new token while logged in is sent; without
  Firebase settings nothing is sent and the profile row is hidden.
- [x] AC2: Logout sends `DELETE /api/push-devices` with the token before logging out, and logs out also when it fails
  or takes more than 3 s.
- [x] AC3: A message for another account, or with nobody logged in, or of an unknown type, shows nothing.
- [x] AC4: In the background the four types show their texts on their channels, one per conversation / job.
- [x] AC5: In the foreground no notification is shown and the coach's lists / the screens with rides reload.
- [x] AC6: During a large import the import types show nothing.
- [x] AC7: Tapping a coach notification opens the conversation on the Coach tab, an import notification the
  training log; logged out it opens the login.
- [x] AC8: The permission is asked after the first accepted coach request on Android 13+, once; the profile row opens
  the system's notification settings.
- [x] AC9: New strings exist in English and Polish.

## Open questions

- None.
