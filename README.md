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
| [004](specs/004-profile.md) | Profile: rider data (and creating the rider), threshold history with add / edit / delete, W/kg and the re-analysed rides from the backend (cyclingapp spec 053), FTP trend, power and HR zones, intervals.icu (connect, sync, its thresholds as a proposal), Garmin Connect status and disconnect (connecting on the web), language and theme (system / light / dark), account: AI credits, password change, legal documents, account deletion |
| [005](specs/005-calendar.md) | Calendar tab: one week at a time (arrows, swipe, Today, date picker) with the planned workouts of the saved plans and the rides, week totals and plan week from the backend (cyclingapp spec 054), plan cards with removal, the adjustment suggestion with dismiss; workout detail with steps and step profile, plan vs. ride, link / unlink / skip, send to Garmin Connect (single workout or the plan's 2 weeks), Zwift `.zwo` download; the ride detail opens its workout's week; "Create a training block" and a plan's conversation open on the Coach tab. Plan adjustments: spec 008 |
| [006](specs/006-coach.md) | AI coach tab: conversations with filters and deletion, AI credits, the chat with Markdown answers, follow-ups with a FIT / ZIP file, requests answered in the background (followed while the app is open, a snackbar when they end), failed and refused requests with retry; the review and training block panels, workout detail with steps in watts, adding a block to the calendar (with the overlap question); a ride's "Ask the coach" with RPE and note, the new training block form and the FIT file review; the dashboard's coach card |
| [007](specs/007-pull-to-sync.md) | Pull to refresh on the dashboard and the training log also fetches new rides from intervals.icu when connected: one sync for the whole app (shared with the profile's "Sync now"), its progress above the content, a snackbar when rides came, the screens reload by themselves |
| [008](specs/008-plan-adjustments.md) | Plan adjustments with the coach: "Adjust plan" on a plan card, "Ask the coach" on the adjustment suggestion and "Adjust plan with the coach" on a review open the adjustment form (reasons, fatigue, note, first day, new availability); the proposal tab of a plan adjustment conversation shows the changed days before / after and the weeks' planned TSS, with Accept / Reject; "Show proposal" on the calendar opens it; "Replace in plan" puts a review's workout into the plan |
| [009](specs/009-ride-import.md) | Importing rides from files: "Import rides" in the training log and on the empty dashboard and training log. A single FIT / TCX / GZ / ZIP ride is uploaded, analysed and opened (a duplicate offers its stored ride); several files of up to 50 MB in all go as one import job with progress, the backend's counters and the files not imported, going on when the screen is left (a snackbar tells its end). Ride files shared or opened from other apps ("Share", "Open with") open the import with them chosen. Larger exports stay on climbra.app |
| [010](specs/010-feedback.md) | Feedback: "Send feedback" in the profile's account card opens the web's form (name, type, message) and sends it to the author of the app, with the app's version |
| [011](specs/011-garmin-sync.md) | Garmin sync for the admin: "Sync from Garmin" in the profile and an admin's pull to refresh on the dashboard and the training log fetch the owner's new activities (Garmin Personal API); others see nothing of it |
| [012](specs/012-training-log-sort-and-filter.md) | Training log: sorting by the backend's columns (date, name, type, duration, distance, elevation, power, NP, IF, TSS, HR, intervals; again flips the direction) and the sport type filter of the period's types, done by the backend with its totals |
| [013](specs/013-native-registration.md) | Registration (email, password and its repeat, the terms and health data consents) and "Forgot password" as the app's screens, from the login and the profile, with the backend's Turnstile check in a WebView; "Check your inbox" with the link again |

Registration and the password reset request are the app's screens (spec 013, Turnstile in a WebView); the links in their emails (confirming the address, setting a new password) open the web pages.

## Requirements

- Android 10 (API 29) or newer.
- Building: Android Studio or the Android SDK with JDK 17+; Gradle is the wrapper.

## Running against a local backend

1. Start the backend in `../cyclingapp/backend` with `./mvnw spring-boot:run` (Docker must be running).
2. Forward the ports to the emulator or a phone on USB:
   `adb reverse tcp:8080 tcp:8080` (API) and `adb reverse tcp:4200 tcp:4200` (web pages, if the frontend runs).
3. `./gradlew installDebug` and open Climbra.

Debug builds use `http://localhost:8080`. Release builds use `https://climbra.app`.

Optional settings in `local.properties` (not committed):

| Property | Meaning |
|----------|---------|
| `climbra.apiBaseUrl` | Backend of debug builds, default `http://localhost:8080` |
| `climbra.webBaseUrl` | Web pages of debug builds (register, password reset, terms), default `http://localhost:4200` |
| `climbra.googleServerClientId` | The backend's Google OAuth **web** client id; without it the Google button is hidden. Google login also needs an OAuth client of type Android (package `app.climbra.android` + the signing key's SHA-1 from `./gradlew signingReport`) in the same Google Cloud project, and the backend with cyclingapp spec 050 |

## Tests

```bash
./gradlew testDebugUnitTest   # all tests, including the Compose screen tests (Robolectric), no emulator needed
./gradlew lintDebug
```

## Licenses

Fonts: Inter and JetBrains Mono, SIL Open Font License ([`licenses/`](licenses)). Icons: Material Symbols
(Apache License 2.0).
