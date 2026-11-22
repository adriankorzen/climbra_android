# 021: Opening the email links in the app

- **Status:** Implemented
- **Created:** 2026-11-21

## Problem

Registration and "Forgot password" run in the app (spec 013), but the links in their emails open `climbra.app` in
the browser: the rider verifies the address or sets a new password on a web page and then comes back to log in in
the app. On a phone with the app installed the links should open the app and finish there.

## Scope

- Android App Links for `https://climbra.app` with the paths of the account emails:
  - `/verify-email?token=…` → the app's "Verify your email" screen;
  - `/reset-password?token=…` → the app's "Set a new password" screen;
  - `/login` and `/forgot-password` (the "account exists" email) → the login / "Forgot password" screens.
- Both new screens as the web's `verify-email-page` and `reset-password-page`.

### Out of scope

- Other paths of `climbra.app` (the landing page, `/terms`, `/activities/…`): the browser keeps them; the intent
  filter lists only the four paths.
- A link without a verified App Link (the app installed from a build whose certificate is not in `assetlinks.json`):
  Android opens the browser, as today.
- Custom schemes for the emails (`climbra://…`): mail apps and webmail do not open them reliably.

## Behaviour

### Links

- `MainActivity` gets an `intent-filter` with `android:autoVerify="true"`, `https`, host `climbra.app`, paths
  `/verify-email`, `/reset-password`, `/login`, `/forgot-password` (exact, `pathPattern` not used). Android verifies
  it against `https://climbra.app/.well-known/assetlinks.json` (cyclingapp spec 057). Release and debug declare the
  same host: a debug build is tested with
  `adb shell pm set-app-links-user-selection --user cur --package app.climbra.android true climbra.app` and the
  local link's path and token on `https://climbra.app` (the local backend's links point at `localhost:4200`).
- `receive(intent)` parses the link into `auth.EmailLink` (`Verify(token)`, `Reset(token)`, `Login`,
  `ForgotPassword`); a missing or empty token gives the screen's "invalid link" state. A link opened while the app
  runs replaces the email link screen shown, if any.
- **Logged out**: the link's screen opens on `auth.LoggedOutNavigation` above the login (back → the login).
- **Logged in**: `Verify` and `Reset` open full screen above the shell (back → the shell), as on the web where these
  pages work logged in or not; `Login` and `ForgotPassword` only bring the app to the front (the web's guest guard
  sends a logged-in visitor to the app).
- **Terms not accepted** state: as logged in, above the terms screen.

### Verify your email

As the web's page: "Enter your password to confirm your address and log in." Password field (show / hide),
**Confirm** → `POST /api/auth/verify-email` `{ token, password }`:

- `200` `AccountResponse` with the session's cookie → `SessionRepository` takes the account as after a login (the
  terms screen when `termsAccepted` is false, else the shell); a previous session of another account is replaced
  (its push token handling of spec 020 runs as at a logout).
- `BAD_CREDENTIALS` → "Wrong password." under the field.
- `TOKEN_INVALID` → the invalid state: "This link is invalid or has expired.", an email field and **Send the link
  again** (`POST /api/auth/resend-verification` with a Turnstile token, `auth.TurnstileController` of spec 013) →
  "If the account is waiting for confirmation, we sent a new link."
- Other errors translated (`toUiText`), the values kept. Offline: Confirm disabled with `OfflineNote`.
- "Forgot password" link → the forgot password screen.

### Set a new password

As the web's page: new password and repeat with the rules of spec 004 / 013, **Set password** →
`POST /api/auth/password-reset/confirm` `{ token, password }`:

- `204` → "Your password has been changed. Log in with the new one." and **Log in**. The backend ends every session
  of the account (cyclingapp `sessions.invalidateAll`): when the app was logged in as it, the next request answers
  `401` and the login opens as after any session end; **Log in** goes to the login (logged out) or closes the screen.
- `TOKEN_INVALID` (or no token) → "This link is invalid or has expired." and **Send a new link** → the forgot
  password screen.
- Password errors (`PASSWORD_TOO_SHORT` …) under the field, others translated; offline as above.

## Backend dependencies

- Existing: `POST /api/auth/verify-email` (`VerifyEmailRequest`: `token`, `password`) → `AccountResponse` + session;
  `POST /api/auth/password-reset/confirm` (`PasswordResetConfirmRequest`: `token`, `password`) → `204`;
  `POST /api/auth/resend-verification` (spec 013).
- cyclingapp spec 057 (new): `/.well-known/assetlinks.json` with the release certificate's fingerprint. Until it is
  deployed with the release key (BACKLOG, spec 017 first release) the links keep opening the browser.

## Screens

- `auth.VerifyEmailRoute` / `VerifyEmailScreen`, `auth.ResetPasswordRoute` / `ResetPasswordScreen`
  (`OutlinedTextField`s, the password field and rules of spec 004, `TurnstileView` of spec 013), on
  `LoggedOutNavigation` and as a full-screen destination above the shell.
- `app.ClimbraContent`: the pending `EmailLink` (`auth.EmailLinks`, app-scoped, like `SharedRideFiles`) decides
  what opens.

## Data and storage

- `data/api`: `VerifyEmailRequest`, `PasswordResetConfirmRequest`, their calls in `AuthApi`;
  `SessionRepository.verifyEmail(token, password)`. Nothing stored; the token lives only in the screen's
  `SavedStateHandle`.

## Acceptance criteria

- [x] AC1: The manifest declares a verified App Link for `https://climbra.app` with exactly the four paths.
- [x] AC2: The four links parse to their `EmailLink`; a link without a token gives the invalid state.
- [x] AC3: Logged out, each link opens its screen above the login; logged in, `Verify` and `Reset` open above the
  shell and `Login` / `ForgotPassword` open nothing.
- [x] AC4: Verify sends token and password; `200` logs in (terms or shell); `BAD_CREDENTIALS` shows the wrong password
  message; `TOKEN_INVALID` shows the invalid state, whose resend sends the email with a Turnstile token.
- [x] AC5: Reset checks the password rules, sends token and password; `204` shows the done state with Log in;
  `TOKEN_INVALID` shows the invalid state with Send a new link to the forgot password screen.
- [x] AC6: Offline, the screens' send buttons are disabled with the offline note.
- [x] AC7: New strings exist in English and Polish.

## Open questions

- None.
