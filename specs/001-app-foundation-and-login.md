# 001: App foundation and login

- **Status:** Implemented
- **Created:** 2026-11-11

## Problem

Climbra has a web frontend only. Riders want to check their training on the phone in a native app. Before any data
screen can exist, the app needs its architecture, theme, networking against the existing backend, and a way to log
in to an existing account — including accounts created with Google, which have no password.

## Scope

- Project setup: application id and package `app.climbra.android` (renamed from the template's
  `app.climbra.climbraapp`), minSdk 29, dependencies (Hilt, Retrofit + OkHttp + kotlinx.serialization, Navigation Compose, Tink,
  lifecycle-viewmodel-compose, Credential Manager, test libraries), `BuildConfig.API_BASE_URL` per build type,
  Java 17 target, `INTERNET` permission, cleartext allowed only for the debug base URL. Launcher and splash icons
  from the web's brand mark (`frontend/public/icons/icon.svg`). Backup is off: the only data is the session.
- Theme: Material 3 light and dark color schemes from the web tokens (see Screens), follows the system setting,
  dynamic color off. Fonts: Inter for text, JetBrains Mono for numbers (as on the web).
- i18n: English (default) and Polish string resources; the app follows the account language from
  `AccountResponse.language` once logged in, the device language before (per-app locale via
  `AppCompatDelegate.setApplicationLocales`).
- Networking: persistent cookie jar, CSRF handling, problem-details error mapping, `401` handling.
- Login with email and password.
- Login with Google through Credential Manager (needs a backend change, see Backend dependencies).
- Terms gate: accounts with `termsAccepted == false` accept the terms in the app before going on.
- App shell after login: bottom navigation bar with Dashboard, Activities, Profile; each destination shows an empty
  placeholder until its own spec (002+) fills it. Profile placeholder has the logout action.
- Logout.

### Out of scope

- Registration, email verification, password reset — the login screen links to the web pages
  (`https://climbra.app/register`, `/forgot-password`) opened in a Custom Tab.
- Account settings, language switch, account deletion (later spec).
- Dashboard, activities, profile content (specs 002–004), calendar, coach, upload, Garmin.
- Offline cache of data, push notifications, tablet / foldable layouts beyond what Compose adapts by default.

## Behaviour

### Start

1. The app shows the splash screen (Android 12 SplashScreen API, Climbra mark on `--bg`) while it calls
   `GET /api/auth/me` with the stored cookies.
2. `200` → if `termsAccepted` go to the shell, else to the terms screen. `401` → login screen.
3. Network error or `5xx` → a full-screen error with "Retry" (the stored session is kept, nothing is cleared).

### Cookies and CSRF

- A `CookieJar` keeps all cookies in memory and the persistent ones in a file encrypted with Tink (AES-256-GCM, key in
  the Android Keystore) so the session survives app restarts; an unreadable file counts as logged out. Login always
  sends `rememberMe = true` (30 days on the backend).
- Before the first mutating request (`POST/PUT/DELETE`) without an `XSRF-TOKEN` cookie, the app calls
  `GET /api/auth/csrf`. Every mutating request sends the cookie value as `X-XSRF-TOKEN`.
- A `403` with an invalid CSRF token (token rotated after login) refreshes the token once and repeats the request.

### Errors

- Error bodies are problem details; the app reads `code` and `status`. Codes of `AuthErrorCode` relevant here
  (`BAD_CREDENTIALS`, `EMAIL_NOT_VERIFIED`, `LOGIN_LOCKED`, `TOO_MANY_REQUESTS`, `TERMS_NOT_ACCEPTED`,
  `UNAUTHENTICATED`) have translated messages; others show a generic message.
- Any `401` from an authenticated screen clears the cookies and navigates to the login screen (back stack cleared).
- A `403 TERMS_NOT_ACCEPTED` (new legal version while logged in) navigates to the terms screen.

### Login screen

- Climbra logo, email field (keyboard type email, autofill hint), password field (visibility toggle, autofill hint),
  "Log in" button, "Continue with Google" button, links "Create account" and "Forgot password?" (Custom Tab).
- "Log in": local validation (both fields non-empty) → `POST /api/auth/login` → `AccountResponse` → start
  destination as in Start step 2. While the request runs the button shows progress and inputs are disabled.
- `EMAIL_NOT_VERIFIED` shows the message with a hint to check the mailbox (resending is on the web).
- The Google button is shown only when `GET /api/auth/providers` says `google: true`.

### Google login

- Credential Manager's "Sign in with Google" button flow (`GetSignInWithGoogleOption`; server client id = the
  backend's Google web client id, from `BuildConfig.GOOGLE_SERVER_CLIENT_ID`), nonce fetched per attempt from
  `GET /api/auth/google/nonce`. Shows the account picker; on success the app sends the ID token to
  `POST /api/auth/google/id-token`, which starts the same session as the web Google login and returns
  `AccountResponse`. `401 GOOGLE_FAILED` and errors Credential Manager reports as errors (e.g. a wrong client id)
  show the translated Google message.
- Cancelling the picker returns to the login screen without an error message. Google Play services also report some
  failures as a cancellation (status 16, e.g. no Android OAuth client for the package and signing key, which logs
  `UNREGISTERED_ON_API_CONSOLE`); those cannot be told apart and show no message either. Every outcome is logged with
  the tag `ClimbraGoogle`.
- No Google account on the device → the "add account" flow of Google Play services. That flow can close without
  Credential Manager ever answering; so a request still open 3 seconds after the login screen is back in front
  (paused under the system UI, then resumed) is given up as cancelled, and the buttons work again.
- A first Google login creates the account on the backend (without accepted terms) → terms screen.

### Terms screen

- Text explaining that the terms and privacy policy must be accepted, links to `https://climbra.app/terms` and
  `/privacy` (Custom Tab, in the app language), two checkboxes mirroring the web `accept-terms` page (terms, health
  data consent), "Accept" enabled when both are checked → `POST /api/account/accept-terms`
  `{acceptTerms: true, acceptHealthData: true}` → shell. "Log out" is available here as well.

### Logout

- `POST /api/auth/logout` (`204`), then cookies are cleared and the login screen is shown. If the request fails the
  cookies are cleared anyway (the session expires on the server).

## Backend dependencies

- Existing: `GET /api/auth/me`, `GET /api/auth/csrf`, `GET /api/auth/providers`, `POST /api/auth/login`,
  `POST /api/auth/logout`, `POST /api/account/accept-terms`.
- **New, cyclingapp spec 050: Google login for native apps.** `GET /api/auth/google/nonce` → `{nonce}` stored in
  the session; `POST /api/auth/google/id-token` `{idToken}` → verifies the token (signature, issuer, audience = the
  web client id, verified email, the session's nonce), finds or creates the account like `GoogleAccountService`,
  starts a remember-me session, returns `AccountResponse`; errors `401 GOOGLE_FAILED`, `429`. Until it is
  deployed, the app hides the Google button (feature flag in `BuildConfig`).

## Screens

- Splash, Login, Terms, Error (retry), Shell with `NavigationBar` (Material 3) — icons from Material Symbols
  (Rounded): `dashboard`, `directions_bike`, `person`. The web uses a sidebar on desktop and a bottom tab bar on phones;
  the app uses the bottom bar.
- Color scheme mapping from `../cyclingapp/frontend/src/styles.scss`:

  | Material 3 role        | Light (web token)          | Dark (web token)           |
  |------------------------|----------------------------|----------------------------|
  | primary                | `--accent` #e2531f         | `--accent` #f26a35         |
  | onPrimary              | `--on-accent` #ffffff      | `--on-accent` #ffffff      |
  | primaryContainer       | `--accent-soft` #fde7dd    | `--accent-soft` #3a2218    |
  | onPrimaryContainer     | `--accent-strong` #a93a0b  | `--accent-strong` #ff8957  |
  | background             | `--bg` #f4f5f7             | `--bg` #0f1318             |
  | surface                | `--surface` #ffffff        | `--surface` #171c23        |
  | surfaceContainer       | `--surface-2` #f0f2f5      | `--surface-2` #1e252e      |
  | surfaceContainerHigh   | `--surface-3` #e6e9ee      | `--surface-3` #27303b      |
  | onSurface / onBackground | `--text` #161b22         | `--text` #e7ebf0           |
  | onSurfaceVariant       | `--text-muted` #5b6573     | `--text-muted` #9aa5b2     |
  | outlineVariant         | `--border` #e1e4e9         | `--border` #29313c         |
  | outline                | `--border-strong` #c9cfd7  | `--border-strong` #3a4552  |
  | error                  | `--danger` #c62828         | `--danger` #ef5350         |

  Domain colors that Material 3 has no role for (power, heart rate, cadence, speed, altitude, power zones z1–z7, HR
  zones, success / warning / info, chart grid / axis) are an `ExtendedColors` object provided by a
  `CompositionLocal`, with the same light / dark values as the web.
- Shapes: small 6 dp, medium 8 dp (web `--radius-sm` / `--radius`).

## Data and storage

- DTOs: `AccountResponse`, `LoginRequest`, `AuthProviders`, `AcceptTermsRequest`, `GoogleIdTokenRequest`,
  `ProblemDetail`.
- Encrypted cookie store. No account data is stored apart from the cookies; the current account is held in memory
  (`SessionRepository` exposing `StateFlow<SessionState>`), reloaded from `/api/auth/me` at start.

DTOs ignore unknown JSON fields, so new backend fields do not break the app.

## Acceptance criteria

- [x] AC1: Starting with a valid stored session opens the shell; with no / expired session opens the login screen;
      with a network error shows the retry screen and keeps the cookies.
- [x] AC2: Cookies set by the backend are persisted and sent after the app process is restarted.
- [x] AC3: Mutating requests carry `X-XSRF-TOKEN` equal to the `XSRF-TOKEN` cookie; without the cookie the app
      calls `GET /api/auth/csrf` first; a CSRF `403` is retried once with a fresh token.
- [x] AC4: Email/password login sends `rememberMe = true` and on success navigates according to `termsAccepted`.
- [x] AC5: `BAD_CREDENTIALS`, `EMAIL_NOT_VERIFIED`, `LOGIN_LOCKED`, `TOO_MANY_REQUESTS` show their translated
      messages; an unknown code shows the generic message.
- [x] AC6: The Google button is visible only when providers report Google and the feature flag is on; a successful
      Google credential is sent to `POST /api/auth/google/id-token` and the session starts; cancelling shows no error.
- [x] AC7: An account without accepted terms sees the terms screen; "Accept" is enabled only with both boxes
      checked and posts both flags; afterwards the shell opens.
- [x] AC8: A `401` on any authenticated request clears cookies and shows the login screen with an empty back stack;
      a `403 TERMS_NOT_ACCEPTED` shows the terms screen.
- [x] AC9: Logout calls `POST /api/auth/logout` and clears cookies even when the call fails.
- [x] AC10: `values` and `values-pl` contain identical string keys.
- [x] AC11: The shell shows the bottom navigation with Dashboard, Activities, Profile and switching keeps each
      tab's state.
- [x] AC12: The UI language follows `AccountResponse.language` after login.

## Decisions

- Application id and package: `app.climbra.android`.
- minSdk 29 (Android 10).
- The terms screen requires both consents (terms and health data), as on the web.
- An Android OAuth client (package name + SHA-1 of the debug and release signing keys) is added to the backend's
  Google Cloud project. The ID token audience stays the backend's web client id.
- Debug builds talk to the backend on the developer's PC as `http://localhost:8080` (web pages `:4200`) through
  `adb reverse`. Android 17 apps targeting API 37 may not connect to private addresses such as the emulator's
  `10.0.2.2` without the local network permission; `localhost` is not local network and works for the emulator and
  for a phone on USB alike. Overridable with `climbra.apiBaseUrl` / `climbra.webBaseUrl` in `local.properties`.
- The Google server client id comes from `climbra.googleServerClientId` (`local.properties` or a Gradle property);
  without it the Google button stays hidden (the feature flag of AC6).

## Tests

- Network and session (AC1–AC9, AC12): JVM tests of the real OkHttp / Retrofit stack (`data.net.Network`) against
  `MockWebServer` (`testing/TestBackend`); the Keystore cipher is replaced by a reversible one.
- Screens (AC6, AC7, AC8, AC11): Compose UI tests on Robolectric (SDK 35; SDK 36 fails in Robolectric 4.17).
- AC10: `StringResourcesTest`. All run with `./gradlew testDebugUnitTest`, no emulator needed.
- Checked by hand on the API 37 emulator: email login against the local backend (Polish UI after login, session kept
  after a process restart, logout back to the device language) and Google login against production (spec 050).
