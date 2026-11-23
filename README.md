# Climbra for Android

The Android app of [Climbra](https://climbra.app), a cycling training application. It is a client of the backend in
the `cyclingapp` repository and looks like its web app, with Material 3 components and Android navigation.

Built spec first: see [`specs/`](specs) and [`CLAUDE.md`](CLAUDE.md).

## Features

| Spec | Feature |
|------|---------|
| [001](specs/001-app-foundation-and-login.md) | Login with email and password or Google, terms acceptance, logout, session kept between app starts, English / Polish (the account's language), bottom navigation with Dashboard, Activities, Calendar, Coach and Profile |
| [002](specs/002-dashboard.md) | Dashboard: the last 7 days against the 7 before, FTP, fitness / fatigue / form (CTL, ATL, TSB), performance management chart (42–365 days), weekly load, the 7 latest rides. All figures come from the backend (cyclingapp spec 051) |
| [003](specs/003-activities.md) | Training log: rides of a period (7 / 28 / 90 days, this year, custom) with search, the period's totals and paging (cyclingapp spec 052). Ride detail as on the web: figures, thresholds, ride chart, power and HR zones, intervals, interval source, ad hoc detection, reanalyse, FIT download, delete, the planned workout and linking an unplanned ride |
| [004](specs/004-profile.md) | Profile: rider data (and creating the rider), threshold history with add / edit / delete, W/kg and the re-analysed rides from the backend (cyclingapp spec 053), FTP trend, power and HR zones, intervals.icu (connect, sync, its thresholds as a proposal), Garmin Connect status, connecting (spec 016) and disconnect, language and theme (system / light / dark), account: AI credits, password change, legal documents, account deletion |
| [005](specs/005-calendar.md) | Calendar tab: one week at a time (arrows, swipe, Today, date picker) with the planned workouts of the saved plans and the rides, week totals and plan week from the backend (cyclingapp spec 054), plan cards with removal, the adjustment suggestion with dismiss; workout detail with steps and step profile, plan vs. ride, link / unlink / skip, send to Garmin Connect (single workout or the plan's 2 weeks), Zwift `.zwo` download; the ride detail opens its workout's week; "Create a training block" and a plan's conversation open on the Coach tab. Plan adjustments: spec 008 |
| [006](specs/006-coach.md) | AI coach tab: conversations with filters and deletion, AI credits, the chat with Markdown answers, follow-ups with a FIT / ZIP file, requests answered in the background (followed while the app is open, a snackbar when they end), failed and refused requests with retry; the review and training block panels, workout detail with steps in watts, adding a block to the calendar (with the overlap question); a ride's "Ask the coach" with RPE and note, the new training block form and the FIT file review; the dashboard's coach card |
| [007](specs/007-pull-to-sync.md) | Pull to refresh on the dashboard and the training log also fetches new rides from intervals.icu when connected: one sync for the whole app (shared with the profile's "Sync now"), its progress above the content, a snackbar when rides came, the screens reload by themselves |
| [008](specs/008-plan-adjustments.md) | Plan adjustments with the coach: "Adjust plan" on a plan card, "Ask the coach" on the adjustment suggestion and "Adjust plan with the coach" on a review open the adjustment form (reasons, fatigue, note, first day, new availability); the proposal tab of a plan adjustment conversation shows the changed days before / after and the weeks' planned TSS, with Accept / Reject; "Show proposal" on the calendar opens it; "Replace in plan" puts a review's workout into the plan |
| [009](specs/009-ride-import.md) | Importing rides from files: "Import rides" in the training log and on the empty dashboard and training log. A single FIT / TCX / GZ / ZIP ride is uploaded, analysed and opened (a duplicate offers its stored ride); several files of up to 50 MB in all go as one import job with progress, the backend's counters and the files not imported, going on when the screen is left (a snackbar tells its end). Ride files shared or opened from other apps ("Share", "Open with") open the import with them chosen. Larger exports go in batches (spec 014) |
| [010](specs/010-feedback.md) | Feedback: "Send feedback" in the profile's account card opens the web's form (name, type, message) and sends it to the author of the app, with the app's version |
| [011](specs/011-garmin-sync.md) | Garmin sync for the admin: "Sync from Garmin" in the profile and an admin's pull to refresh on the dashboard and the training log fetch the owner's new activities (Garmin Personal API); others see nothing of it |
| [012](specs/012-training-log-sort-and-filter.md) | Training log: sorting by the backend's columns (date, name, type, duration, distance, elevation, power, NP, IF, TSS, HR, intervals; again flips the direction) and the sport type filter of the period's types, done by the backend with its totals |
| [013](specs/013-native-registration.md) | Registration (email, password and its repeat, the terms and health data consents) and "Forgot password" as the app's screens, from the login and the profile, with the backend's Turnstile check in a WebView; "Check your inbox" with the link again |
| [014](specs/014-large-ride-exports.md) | Large ride exports (more than 50 MB, e.g. a whole Garmin or Strava export): the app reads the archives on the phone (FIT / TCX, gzipped, nested up to 3 deep) and sends the rides in batches within the backend's limits, one after the other, with waiting for a busy server, retries, Stop, Pause with Resume / Finish and a progress notification; the result over all batches names the files not sent |
| [015](specs/015-calendar-month-view.md) | The calendar's month view: Week / Month switch (Month by default on wide windows), the whole weeks covering the month with each day's workouts and rides as compact tiles ("+n more" over 3), days outside the month faded, the backend's week totals and plan week per row; a tile opens its workout or ride, a day its week |
| [016](specs/016-garmin-connect-in-the-app.md) | Connecting Garmin Connect in the app: Connect in the profile and in the calendar's "Connect first" dialogs opens the consent (the web's texts and checkbox), Garmin's page in a Custom Tab, the return through `climbra://garmin-connect` completed in the app's session (also after the next login), the outcome in a snackbar; needs cyclingapp spec 055 |
| [018](specs/018-offline-cache.md) | Reading saved data offline: the dashboard, the training log, rides opened before and the calendar show the backend's last answers when it cannot be reached (also at start with a stored session), with an "Offline · data from …" banner and Retry; actions that send something are disabled meanwhile. The answers are encrypted on the device (at most 50 MB) and deleted when the session ends |
| [019](specs/019-adaptive-layouts.md) | Large screens: a navigation rail in place of the bottom bar from 600 dp (also a phone in landscape); from 840 dp the training log and the coach show the chosen ride or conversation next to their list, from 1200 dp the calendar its workout or ride next to the week or month; the chosen item is kept when the window changes |
| [020](specs/020-push-notifications.md) | Push notifications (Firebase Cloud Messaging): when the coach has answered or could not answer, and when an import has finished or failed, also for requests and imports started on the web, while the app is in the background; the tap opens the conversation or the training log. Two notification channels (coach, imports; spec 025 adds "New rides"), the permission asked after the first coach request, the profile's "Notifications" row opens the system settings. The message carries only the type and ids. Needs cyclingapp spec 056 and the `climbra.firebase.*` settings |
| [021](specs/021-email-links-in-the-app.md) | The account emails' links open the app (Android App Links for `climbra.app`): confirming the address with the registration's password logs in, setting a new password, the "account exists" email's login and forgot password; an invalid link asks for a new one. Logged in, a confirm or reset link opens above the shell. Needs cyclingapp spec 057 with the release key's fingerprint |
| [022](specs/022-rider-profile-from-every-tab.md) | An account without a rider profile: the dashboard, the training log, the calendar, the coach and the import screen show "Create profile", which opens the profile tab with the rider form (no longer the web) |
| [023](specs/023-workout-chart-step-details.md) | The workout chart (calendar and coach proposals): tapping or dragging over a bar shows its time span, kind, % FTP and the backend's watts above the chart; tapping it again clears it |
| [024](specs/024-rider-form-edit-mode.md) | The profile's rider data is shown as text with "Edit"; editing opens the fields with "Cancel" (also the system back) and "Save", a reload keeps the typed values, offline editing is disabled (web: cyclingapp spec 058) |
| [025](specs/025-automatic-sync-notifications.md) | The backend syncs intervals.icu every 30 minutes (cyclingapp spec 060): a switch in the profile's intervals.icu section (on by default, the text when intervals.icu rejected the key), and a notification in the channel "New rides" when it brought rides: one opens the ride on the training log's tab, several the training log; in the foreground the screens reload instead |
| [026](specs/026-power-curve.md) | Power curve (cyclingapp spec 061): the best power over durations (1 s – 5 h, logarithmic axis) of 28 days, 90 days, 1 year or all rides against the period before, a tap on the chart shows a duration's figures; the key durations (5 s, 1, 5, 20, 60 min) with W/kg, the backend's change and the ride. Opened from a dashboard card (the 90 days' bests) and the training log's top bar; read offline |

Registration and the password reset request are the app's screens (spec 013, Turnstile in a WebView); the links in their emails open the app (spec 021) once `climbra.app` lists the app's signing certificate, else the web pages.

## Requirements

- Android 10 (API 29) or newer.
- Building: Android Studio or the Android SDK with JDK 17+; Gradle is the wrapper.

## Running against a local backend

1. Start the backend in `../cyclingapp/backend` with `./mvnw spring-boot:run` (Docker must be running).
2. Forward the ports to the emulator or a phone on USB:
   `adb reverse tcp:8080 tcp:8080` (API) and `adb reverse tcp:4200 tcp:4200` (web pages, if the frontend runs).
3. `./gradlew installDebug` and open Climbra.

Debug builds use `http://localhost:8080`. Release builds use `https://climbra.app`.

The email links (spec 021) are App Links of `https://climbra.app` in every build. Android verifies them only for a
build whose certificate `climbra.app/.well-known/assetlinks.json` lists (the release key, cyclingapp spec 057). To try
them on a debug build, allow them by hand and open the local link's path and token on `https://climbra.app`:

```bash
adb shell pm set-app-links-user-selection --user cur --package app.climbra.android true climbra.app
adb shell am start -a android.intent.action.VIEW -d "https://climbra.app/verify-email?token=<token from Mailpit>"
```

Optional settings in `local.properties` (not committed):

| Property | Meaning |
|----------|---------|
| `climbra.apiBaseUrl` | Backend of debug builds, default `http://localhost:8080` |
| `climbra.webBaseUrl` | Web pages of debug builds (register, password reset, terms), default `http://localhost:4200` |
| `climbra.googleServerClientId` | The backend's Google OAuth **web** client id; without it the Google button is hidden. Google login also needs an OAuth client of type Android (package `app.climbra.android` + the signing key's SHA-1 from `./gradlew signingReport`) in the same Google Cloud project, and the backend with cyclingapp spec 050 |
| `climbra.firebase.appId`, `climbra.firebase.apiKey`, `climbra.firebase.projectId`, `climbra.firebase.senderId` | The Firebase project of the push notifications (spec 020): from the Android app `app.climbra.android` in the Firebase console (*Project settings*, or its `google-services.json`: `mobilesdk_app_id`, `current_key`, `project_id`, `project_number`). Without all four there is no Firebase and no notification; the backend needs the same project's service account (cyclingapp `deploy/README.md`) |

## Tests

```bash
./gradlew testDebugUnitTest   # all tests, including the Compose screen tests (Robolectric), no emulator needed
./gradlew lintDebug
./gradlew :buildSrc:test      # the version from git tags
```

CI (`.github/workflows/ci.yml`) runs these and an unsigned release build on every push and pull request.

## Release builds

Release builds (spec 017) talk to `https://climbra.app` and go through R8 over the whole app. The version comes from
the latest `vX.Y.Z` git tag: `versionName` `X.Y.Z`, `versionCode` `X * 10000 + Y * 100 + Z` (no tag: `0.0.0`).

Signing settings, in `local.properties` or the environment (never committed); without them the APK is unsigned:

| `local.properties` | Environment |
|--------------------|-------------|
| `climbra.release.storeFile` (path from the repository root, or absolute) | `CLIMBRA_RELEASE_STORE_FILE` |
| `climbra.release.storePassword` | `CLIMBRA_RELEASE_STORE_PASSWORD` |
| `climbra.release.keyAlias` | `CLIMBRA_RELEASE_KEY_ALIAS` |
| `climbra.release.keyPassword` | `CLIMBRA_RELEASE_KEY_PASSWORD` |

```bash
./gradlew assembleRelease     # app/build/outputs/apk/release/, R8's mapping in app/build/outputs/mapping/release/
```

Publishing: push a tag (`git tag v0.1.0 && git push origin v0.1.0`). `.github/workflows/release.yml` builds the
signed APK and AAB and attaches them, with `mapping.txt`, to a GitHub Release. It needs the repository's secrets
`CLIMBRA_RELEASE_KEYSTORE_BASE64` (the keystore file in base64), `CLIMBRA_RELEASE_STORE_PASSWORD`,
`CLIMBRA_RELEASE_KEY_ALIAS` and `CLIMBRA_RELEASE_KEY_PASSWORD`, and optionally the variables
`CLIMBRA_GOOGLE_SERVER_CLIENT_ID` (Google login), `CLIMBRA_FIREBASE_APP_ID`, `CLIMBRA_FIREBASE_API_KEY`,
`CLIMBRA_FIREBASE_PROJECT_ID` and `CLIMBRA_FIREBASE_SENDER_ID` (push notifications) and `CLIMBRA_RELEASE_CERT_SHA256` (the certificate the APK must be
signed with). Google login in a release build also needs an Android OAuth client with the release key's SHA-1.

Keep the keystore and its passwords safe outside the repository: an app signed with a lost key cannot be updated.

### Smoke test of a release build

R8 can remove what is only reached by reflection or serialization, which no unit test sees. Before publishing, install
the release APK (`adb install app-release.apk`; uninstall a debug build first, its key differs) and go through, against
the production backend: log in (email and Google), the dashboard with its charts, the training log and a ride (map,
charts, intervals), the calendar's week and month, the coach (a question and its answer), the profile (thresholds, FTP
trend, sync), an import of a `.fit` file, log out. Read a crash's stack trace with `retrace` and the build's
`mapping.txt`.

## Licenses

Fonts: Inter and JetBrains Mono, SIL Open Font License ([`licenses/`](licenses)). Icons: Material Symbols
(Apache License 2.0).
