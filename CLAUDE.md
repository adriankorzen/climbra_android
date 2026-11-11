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
- Kotlin, Jetpack Compose, Material 3. Package `app.climbra.android`, single `:app` module (split into modules
  only when a spec says so). Dependencies in `gradle/libs.versions.toml`.
- No hard-coded UI strings: `res/values/strings.xml` (English) and `res/values-pl/strings.xml` (Polish) with
  identical keys (a unit test checks this). Format numbers, durations and dates through one formatting helper using
  the current locale, mirroring `shared/fmt.ts` / `shared/format.ts` of the web frontend.

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
  end leaves no back stack. The shell (`shell.Shell`) is a `NavigationBar` with type-safe routes per tab.
- Hilt for DI (`di.AppModule`), Retrofit + OkHttp + kotlinx.serialization, Navigation Compose. Vico for charts
  (from the first spec that needs one).
- Packages by feature (`auth`, `profile`, `shell`, later `dashboard`, `activities`, …) plus `data/` (network, DTOs),
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
