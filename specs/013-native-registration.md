# 013: Registration and password reset in the app

- **Status:** Draft
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
  `password-rules`: the same checks and texts as the profile's password change, spec 004) and its show / hide.
- **Language**: Polish / English, the app's language preselected (the account's language, as the web's).
- **Consents**, both required to send: "I accept the terms and the privacy policy" (links open them, spec 001) and
  "I agree to the processing of my health data (heart rate, power, training load)" (cyclingapp spec 014), the web's
  texts.
- **Captcha**, when the backend asks for one (`GET /api/auth/providers` gives its site key; none → no widget and
  no token): see "Captcha" below.
- **Create account** → `202` → "Check your email: we sent a link to {email}. Open it to activate the account, then
  log in here." with **Send the link again** (`resend-verification`, at most once per 60 s in the app; the backend
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
- The widget sits above the send button as on the web (managed mode: usually a checkmark the rider does not have to
  tap). Its callback hands the token to the form through a `JavascriptInterface`; expired or failed → no token, and
  the button waits for a new one ("Confirm that you are not a robot").
- A token is used once: after every answer of the backend the widget is reset (`turnstile.reset`).
- The widget cannot load (offline, Cloudflare down): "The check could not load. Check the connection and try
  again." with Retry; the form keeps its values.
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
  the password field and rules of spec 004, `Checkbox` rows with links, `SegmentedButton` for the language, `auth.TurnstileView` (an `AndroidView` with the
  `WebView`) and the page `assets/turnstile.html`.
- `app.ClimbraContent`: the logged-out state gets a small back stack (login → registration / forgot password).

## Data and storage

- `data/api`: `RegisterRequest`, `EmailRequest` and their calls in `AuthApi` (`AuthProviders` with the site key exists). Nothing stored on the device
  (the email moves to the login screen in memory).

## Acceptance criteria

- [ ] AC1: "Create an account" and "Forgot password" on the login screen open the app's screens, not Custom Tabs.
- [ ] AC2: Registration checks the email, the password rules and both consents before sending, and sends the
  web's body with the language and the captcha token.
- [ ] AC3: `202` shows "Check your email" with the address; "Send the link again" sends `resend-verification`, at
  most once per 60 s.
- [ ] AC4: The backend's errors show the web's messages and keep the values (the captcha is asked again).
- [ ] AC5: Forgot password sends the email and the captcha and shows the same answer whether the account exists.
- [ ] AC6: "Back to login" returns with the email filled in.
- [ ] AC7: With a site key the Turnstile widget shows in a WebView on the web's address, its token goes with the
  request and the widget is reset after every answer; no token, no send; a widget that cannot load offers Retry;
  without a site key there is no widget.
- [ ] AC8: New strings exist in English and Polish.

## Open questions

- None.
