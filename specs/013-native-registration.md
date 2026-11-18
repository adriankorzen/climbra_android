# 013: Registration and password reset in the app

- **Status:** Implemented
- **Created:** 2026-11-18

## Problem

The login screen opens the web's registration and "Forgot password" pages in a Custom Tab (spec 001). The rider
leaves the app, registers on a web page that does not look like the app, and comes back to log in. On a phone the
first contact with the app is a web form.

## Scope

- **Registration** screen like the web's `register-page`: email, password, language, the terms and health data
  consents, `POST /api/auth/register`, then "Check your email" with "Send the link again"
  (`POST /api/auth/resend-verification`).
- **Forgot password** screen: email, `POST /api/auth/password-reset`, then "Check your email".
- Both reached from the login screen in place of the Custom Tabs; "Forgot password" also from the profile's
  password change (spec 004).
- The **captcha** these requests need in production (cyclingapp spec 032, Cloudflare Turnstile): the Turnstile
  widget in a WebView (decided 2026-11-18; Cloudflare has no Android SDK).

### Out of scope

- The links in the emails (verify the address, set a new password): they keep opening `climbra.app/verify-email` and
  `/reset-password` in the browser, which finishes them; the rider then logs in in the app. Opening them in the app
  needs Android App Links (`assetlinks.json` on climbra.app) and is a later spec.
- Google sign-up: the login screen's Google button already creates the account (cyclingapp spec 050).

## Behaviour

### Registration

Full screen above the login screen (no shell yet), title "Create an account", back to the login.

- **Email**, required, an email address; **password** with the backend's rules (cyclingapp spec 045 / the web's
  `password-rules`: the same checks and texts as the profile's password change, spec 004) and its show / hide;
  **Repeat password** (the same, not the email), as the web's form.
- **Language**: not asked, as on the web: the app's language goes along (`pl` / `en`).
- **Consents**, both required to send: "I accept the terms and the privacy policy" (links open them, spec 001) and
  "I agree to the processing of my health data (heart rate, power, training load)" (cyclingapp spec 014), the web's
  texts.
- **Captcha**, when the backend asks for one (`GET /api/auth/providers` gives its site key; none → no widget and
  no token): see "Captcha" below.
- **Create account** → `202` → the web's "Check your inbox" text with the address (a taken address gets the same:
  its owner gets a login link) with **Send the link again** (`resend-verification` with a new token, at most once per 60 s in the app; the backend
  throttles too) and **Back to login** (email prefilled).
- Errors under the button, the backend's codes translated as the web does (`EMAIL_DOMAIN_NOT_ALLOWED`,
  `PASSWORD_TOO_SHORT` / `PASSWORD_TOO_LONG` / `PASSWORD_EQUALS_EMAIL`, `HEALTH_CONSENT_REQUIRED`, `CAPTCHA_FAILED`,
  `EMAIL_LIMIT_REACHED`, `TOO_MANY_REQUESTS`). An address that is taken gets the same `202` (the backend does not
  tell; its owner gets an email).
  The form keeps its values but the captcha.

### Forgot password

Full screen above the login (or the profile), title "Reset your password".

- **Email** (prefilled from the login or the account), captcha as above, **Send link** → `202` → "If an account
  exists for {email}, we sent a link to set a new password." (the backend does not tell whether it exists).
- Errors as above.

### Captcha

Cloudflare has no Android SDK, so the app shows the web's Turnstile widget in a `WebView` (decided 2026-11-18):

- A small HTML page bundled in the app (the widget's script `challenges.cloudflare.com/turnstile/v0/api.js`, the
  site key from `/api/auth/providers`, the app's language and theme), loaded with `loadDataWithBaseURL` and the web
  app's address (`AppConfig.webBaseUrl`, `https://climbra.app` in release) as its base, so the hostname matches the
  site key's allowed hostnames. JavaScript on, nothing else of the page reaches the app but the token.
- As the web's widget (`execution: 'execute'`, `appearance: 'interaction-only'`): nothing is visible and no token is
  asked for until the rider sends; then a fresh token is asked for (`turnstile.execute`, `turnstile.reset` for every
  later request: a token works once), and the widget shows above the send button only while Cloudflare needs an
  interaction. Its callback hands the token to the request through a `JavascriptInterface`.
- The script cannot load within 15 s, or the check fails or expires: nothing is sent, "We could not check that you
  are not a bot. Check the connection and try again, or use Google login."; sending again is the retry (the page
  loads again); the form keeps its values.
- No site key (the local backend, `auth.turnstile.required: false`): no widget, no token.
- If Cloudflare stops accepting the WebView, the fallback is a backend spec for Google Play Integrity tokens (not
  part of this spec).

## Backend dependencies

Existing: `GET /api/auth/providers` (`turnstileSiteKey`, `null` when off), `POST /api/auth/register` (`RegisterRequest`: `email`,
`password`, `language`, `acceptTerms`, `acceptHealthData`, `captchaToken`) → `202`,
`POST /api/auth/resend-verification` and `POST /api/auth/password-reset` (`EmailRequest`: `email`, `captchaToken`)
→ `202`. The Turnstile widget comes from Cloudflare (`challenges.cloudflare.com`). No backend change.

## Screens

- `auth.RegisterRoute` / `RegisterScreen`, `auth.ForgotPasswordRoute` / `ForgotPasswordScreen`: `OutlinedTextField`s,
  the password field and rules of spec 004, `Checkbox` rows with links, `auth.TurnstileView` (an `AndroidView` with the
  `WebView`) and the page `assets/turnstile.html`.
- `auth.LoggedOutNavigation`: the logged-out state's small back stack (login → registration / forgot password); the
  profile's "Forgot password" is `ProfileForgotPasswordRoute` on the profile's stack.

## Data and storage

- `data/api`: `RegisterRequest`, `EmailRequest` and their calls in `AuthApi` (`AuthProviders` with the site key exists). Nothing stored on the device
  (the email moves to the login screen in memory).

## Acceptance criteria

- [x] AC1: "Create an account" and "Forgot password" on the login screen open the app's screens, not Custom Tabs.
- [x] AC2: Registration checks the email, the password rules and both consents before sending, and sends the
  web's body with the language and the captcha token.
- [x] AC3: `202` shows "Check your email" with the address; "Send the link again" sends `resend-verification`, at
  most once per 60 s.
- [x] AC4: The backend's errors show the web's messages and keep the values (the captcha is asked again).
- [x] AC5: Forgot password sends the email and the captcha and shows the same answer whether the account exists.
- [x] AC6: "Back to login" returns with the email filled in.
- [x] AC7: With a site key a fresh Turnstile token is asked for when sending (the WebView on the web's address), it goes with the
  request; no token, no send and the check's message;
  without a site key there is no widget.
- [x] AC8: New strings exist in English and Polish.

## Open questions

- None.
