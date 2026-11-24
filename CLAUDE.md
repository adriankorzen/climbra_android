# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

The Android app of Climbra (`climbra.app`), a personal cycling training application. It is a client of the existing
backend in the sibling repository `../cyclingapp` (Java 25, Spring Boot 4, PostgreSQL, running on Oracle Cloud). The
Angular web frontend in `../cyclingapp/frontend` is the reference for screens, content and behaviour; the Android app
looks like it, but uses Material 3 components, Material Symbols icons and Android navigation patterns instead of
copying web layouts.

The backend owns all logic (analysis, metrics, plans, coach). The app only displays and requests — never recompute
analysis results on the device.

## Conventions

- Chat with the user in Polish; everything in the repo (code, comments, docs, specs, commits) is in English.
- **The app computes no training figures, joins or totals**: TSS, CTL / ATL / TSB, weekly and period totals, the
  training log's rows and totals come from the backend
  (`GET /api/riders/{id}/training-load`, `GET /api/riders/{id}/rides`, cyclingapp specs 051, 052). A missing figure is a
  backend change (spec there),
  never a computation here.
- Kotlin, Jetpack Compose, Material 3. Package `app.climbra.android`, single `:app` module (split into modules
  only when a spec says so). Dependencies in `gradle/libs.versions.toml`.
- No hard-coded UI strings: `res/values/strings.xml` (English) and `res/values-pl/strings.xml` (Polish) with
  identical keys (a unit test checks this). Format numbers, durations and dates through one formatting helper using
  the current locale: `ui.format.Fmt` (`rememberFmt()` in composables), mirroring `shared/fmt.ts` / `shared/format.ts` of
  the web frontend. Counted texts are `plurals`.

## Spec-driven development

All new features and non-trivial changes follow spec-driven development. Specs live in `specs/` as
`NNN-short-name.md` (numbered sequentially), based on `specs/TEMPLATE.md`.

1. **Spec first.** Before writing code, write (or update) the spec: problem, scope / out of scope, behaviour,
   backend dependencies, screens, acceptance criteria. Ask the user about anything ambiguous.
2. **Approval.** Show the spec to the user and wait for approval before implementing. Status `Draft` → `Approved`.
3. **Implement against the spec.** Every acceptance criterion maps to at least one test (unit, Compose UI or
   instrumented). If implementation reveals the spec is wrong or incomplete, update the spec first and tell the
   user — never let code and spec diverge silently.
4. **Done.** When all criteria are covered and tests pass, set status to `Implemented`, and update `README.md`
   if user-visible behaviour changed. Commit the spec together with the implementation.

Small bug fixes and refactors without behaviour change don't need a spec.

**Backend changes** needed by the app are never made silently from here: they get their own spec in
`../cyclingapp/specs/` following that repository's `CLAUDE.md` (its numbering, its process). The app spec lists
them under "Backend dependencies" by number.

## Backend contract

- Base URL per build type (`BuildConfig.API_BASE_URL`): debug → `http://localhost:8080`, the backend on this PC
  reached through `adb reverse tcp:8080 tcp:8080` (never `10.0.2.2`: Android 17 blocks private addresses without the
  local network permission); release → `https://climbra.app`. Overrides in `local.properties`: `climbra.apiBaseUrl`,
  `climbra.webBaseUrl`, `climbra.googleServerClientId` (the backend's Google web client id; empty hides Google login).
- DTOs in `data/api/` mirror the backend records (`../cyclingapp/backend/.../*Response.java`) and the web types in
  `../cyclingapp/frontend/src/app/api/models.ts` — keep them in sync. Unknown JSON fields are ignored (`Network.json`).
- Auth is the backend's session: cookie `CYCLINGAPP_SESSION` in `data.net.PersistentCookieJar` (persistent cookies
  in a Tink-encrypted file), CSRF in `CsrfInterceptor`, session-wide reactions (`401` → login, `403
  TERMS_NOT_ACCEPTED` → terms) in `SessionEventsInterceptor` → `session.SessionRepository`.
- Every call goes through `apiCall`, which maps failures to `ApiError`; `ui.common.toUiText` translates backend codes
  (`TRANSLATED_ERROR_CODES`, like the web app).
- Data is addressed through `/api/riders/{riderId}/...` and `/api/activities/{id}/...` (backend ownership checks).

## Architecture (spec 001)

- MVVM: Compose screens → `@HiltViewModel` exposing `StateFlow<UiState>` → repositories → Retrofit services.
  Screens are split into a `…Route` (gets the ViewModel) and a stateless `…Screen` (state + callbacks) that tests render.
- `SessionRepository.state` decides the top-level screen in `app.ClimbraContent` (login, terms, shell), so a session
  end leaves no back stack. The shell (`shell.Shell`) has a navigation bar or rail; each tab is a nested graph with type-safe
  routes (a ride opens on the stack of its tab, spec 003); screens navigate through `shell.ShellNavigator` (a coach
  conversation always opens on the Coach tab, above its list, spec 006). After a change to rides (delete, reanalyse, link) call
  `rider.DataChanges.changed()`: screens that show rides load again when shown.
- Large screens (spec 019): the shell is a `NavigationSuiteScaffold` (bar below 600 dp, rail from it). From 840 dp
  (training log, coach) and 1200 dp (calendar) the tab's start screen shows the chosen item next to its list
  (`shell.ShellPanes`); `ShellNavigator` puts rides, workouts and conversations there instead of on the stack. The
  item (`ui.common.PaneItem`) lives in the start entry's `SavedStateHandle`, the pane's ViewModels in a store of their
  own with the item's arguments (`ui.common.PaneViewModels`), so detail ViewModels keep reading `SavedStateHandle`.
- Hilt for DI (`di.AppModule`), Retrofit + OkHttp + kotlinx.serialization, Navigation Compose. Vico for charts
  (from the first spec that needs one).
- AI coach (spec 006): `coach.CoachQuotaRepository` (the AI credits, one for the app) and `coach.CoachActivity`
  (requests answered in the background, polled while the app is in the foreground; call `track` after an accepted
  request, `changed()` after a deletion) are app-scoped. Chosen files go through `coach.RideFileSource`.
- intervals.icu sync (spec 007): `intervals.IntervalsSync` is the app's one sync (pull to refresh on the dashboard and
  the training log, the profile's "Sync now"), app-scoped; its messages go to the shell's snackbar.
- Ride import (spec 009): `activities.RideImport` follows the import job of several files, app-scoped; files shared
  from other apps reach `MainActivity` (`singleTask`, intent filters for ride MIME types) and wait in
  `activities.SharedRideFiles` until the shell opens the import screen. More than 50 MB (spec 014) goes through
  `activities.ImportSession` (`ExportReader` walks the archives twice, `ImportBatches` packs a batch in the cache) while
  `ImportService` keeps the process in the foreground with a notification.
- Before login (spec 013): `auth.LoggedOutNavigation` (login → registration / forgot password); requests that send an
  email take a Turnstile token from `auth.TurnstileController` (`assets/turnstile.html` in a WebView on the web's
  address), asked for when sending, as the web's widget.
- Garmin sync (spec 011): `garmin.GarminSync`, app-scoped, admins only (`AccountResponse.role`); a pull to refresh
  starts it next to `IntervalsSync` (the screens' `pullSync`), its `GarminApi` has a 120 s read timeout.
- Offline (spec 018): `data.net.OfflineInterceptor` (innermost in `Network`) saves the successful answers of the
  `GET`s in `OfflineCache.isSaved` encrypted to `noBackupFilesDir/offline` and serves them when the backend cannot be
  reached; `data.net.Connectivity` says offline (the shell's banner, `ui.common.LocalOffline` for disabling actions
  that send something). A new screen that reads data offline adds its `GET` to that list; one with such actions
  disables them with `LocalOffline` and shows `OfflineNote`. `SessionRepository` deletes the saved answers with the
  cookies. `TestBackend.goOffline()` makes the backend unreachable in tests.
- Push (spec 020): Firebase is set up in `ClimbraApplication` from `BuildConfig` (`climbra.firebase.*`, no
  `google-services.json`; `AppConfig.pushEnabled`). `push.PushTokens` (app-scoped) sends the FCM token after a login
  and removes it in `SessionRepository.logout`'s `beforeLogout`; `push.PushMessages` drops other accounts' pushes,
  reloads in the foreground (`app.AppForeground`), else `PushNotifier` shows one; a tap goes through `push.PushTargets`
  to the shell. Email links (spec 021): App Links of `climbra.app` → `auth.EmailLinks` → the logged-out screens or
  `auth.SignedInEmailLinks` above the shell.
- Home screen widget (spec 027): `widget.TodayWidget` (Glance) loads through `widget.TodayRepository` in each session
  and again when `widget.TodayWidgetUpdates.version` grows (app-scoped: background, `DataChanges`, session, refresh
  button, `TodayRefreshWorker` from `TodayWidgetSchedule`: hourly and after midnight while one is placed). Glance
  stores nothing; the widget, its receiver and worker get their objects through `TodayWidgetEntryPoint`. Its taps are
  `PushTarget`s.
- Sharing (spec 028): `share.ShareViewModel` (a second ViewModel of the ride detail and calendar routes) holds the
  sheet and creates the public link (`ShareApi`, cyclingapp spec 062); the image is the screen's own data drawn by
  `RideShareCard` / `WeekShareCard` inside `share.CardCapture` (1080 × 1350 px, light theme) and written by
  `share.ShareFiles` to `cache/shares` (the `FileProvider` `${applicationId}.files`, emptied at start). The cards only
  draw the backend's figures. Profile → `share.SharedLinksScreen` revokes links.
- Packages by feature (`auth`, `profile`, `shell`, `dashboard`, `activities`, `calendar`, `coach`, …) plus `data/` (network, DTOs),
  `session/` and `ui/` (theme, shared components).
- Theme (`ui.theme`): Material 3 colors from the web tokens in `../cyclingapp/frontend/src/styles.scss` (light + dark),
  domain colors (power, HR, zones) in `MaterialTheme.extendedColors`, Inter / JetBrains Mono (`MonoFamily` for numbers),
  dynamic color off. Icons: Material Symbols Rounded as vector drawables in `res/drawable/ic_*.xml`
  (from `google/material-design-icons`, `symbols/android/<name>/materialsymbolsrounded/`; `_fill1` for selected).

## Commands

Run from the repository root (Windows; use `./gradlew` in bash, `.\gradlew` in PowerShell; adb is in
`%LOCALAPPDATA%\Android\Sdk\platform-tools`):

```bash
./gradlew testDebugUnitTest        # all tests (JVM + Robolectric Compose tests), no emulator needed
./gradlew testDebugUnitTest --tests '*SessionRepositoryTest'   # one class
./gradlew lintDebug                # Android lint
./gradlew :buildSrc:test           # the version from git tags (spec 017)
./gradlew assembleRelease          # R8 over the whole app; signed only with the climbra.release.* settings (README)
./gradlew assembleDebug            # debug APK; installDebug to put it on the emulator
adb reverse tcp:8080 tcp:8080      # before running against the local backend (../cyclingapp/backend: ./mvnw spring-boot:run)
```

A local test account: register through the API and confirm the link from Mailpit (`http://localhost:8025`), which the
backend's `compose.yaml` starts.

## Tests

- Network and session: JVM tests of the real HTTP stack (`Network`) against `MockWebServer` through
  `testing/TestBackend` (routes per `"METHOD /path"`, CSRF cookie by default, `XorCipher` instead of the Keystore).
- ViewModels: `Dispatchers.setMain(UnconfinedTestDispatcher())` with a real `SessionRepository` on `TestBackend`.
- Screens: Compose UI tests on Robolectric (`@RunWith(AndroidJUnit4::class)`, `createComposeRule`), SDK 35 in
  `src/test/resources/robolectric.properties` (SDK 36 fails in Robolectric 4.17). Find nodes by `testTag`.
  Robolectric runs offline on the Android image Gradle resolves (`robolectricAndroidAll` in `libs.versions.toml`,
  the `robolectricJars` task): a Robolectric update or another SDK needs that image's version changed too.
