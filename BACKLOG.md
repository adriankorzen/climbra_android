# Backlog

Checks left to do and what is not planned yet. Features move into a spec in `specs/` when taken up (backend work as a
spec in `../cyclingapp/specs/` first). Updated on 2026-11-21.

## To check

- **Spec 017, first release:** done on 2026-11-22: the release key (SHA-256 `C7:7E:7B:…:BE:76`), the repository's
  secrets and variables, the Android OAuth client with the release SHA-1, and `v0.1.0` built, signed and published by
  the release workflow (its APK checked: the signature, versionName 0.1.0, Firebase set up). Left: the README's smoke
  test of that APK against the production backend (email and Google login, the screens, a coach answer's
  notification).
- **Specs 020 and 021, rest:** done on 2026-11-21: the Firebase project `climbraapp-dc05a` and its service account,
  `climbra.firebase.*` and the `CLIMBRA_FIREBASE_*` variables, and on the emulator against the local backend with FCM:
  the token stored at login and removed at logout, an import's notification in the background (none in the
  foreground), its tap opening the training log; `FCM_*` on the server and the backend deployed (legal documents
  2026-11-21) on 2026-11-22. Left: the permission
  asked after the first coach request, a coach answer's notification opening the conversation, the profile's row.
  The App Links are verified (`climbra.app: verified` on the emulator with the release APK, Google's checker lists the
  key, `/verify-email` opens the app); left: the four links logged in.
- **Manual test of spec 027** on the emulator against the local backend: place the widget "Climbra – today" (the
  picker's preview), both sizes (2 × 1 and 3 × 2 and larger), a day with a workout, a completed one, a rest day and
  none, the taps (the workout opens it on the calendar, elsewhere the dashboard), the refresh button, a change in the
  app reaching the widget after going home, logout (the login text), the dark theme, Polish. The launcher is not
  covered by the automated tests.
- **Manual test of spec 016** against a backend with cyclingapp spec 055 deployed and `GARMIN_CONNECT_*` set (the
  production one, or a local one with the Developer Portal app): Connect from the profile and from a send in the
  calendar, Garmin's consent in the Custom Tab, the return to the app and its snackbar; refusing in Garmin; closing the
  Custom Tab; a return after the session ended (log in again). The Custom Tab and the redirect are not covered by
  the automated tests.
- **Manual test of specs 008 – 015** on the emulator against the local backend: an adjustment request → proposal →
  accept / reject, "Replace in plan" (008); a single ride upload, several files and a ZIP, a duplicate, and sharing a
  `.fit` from a file manager ("Share", "Open with"), also while logged out (009); sending feedback (010); the Garmin
  sync on an admin account, from the profile and by a pull (011); sorting and the sport filter of the training log (012); registering and resetting the password against the production backend, where Turnstile is on (013); a large export (a Garmin account export) with the app in the background and the screen off, Stop and a paused import (014); the month view on a tablet or in landscape (015). Sharing is not covered by the automated tests.
- **Manual test of spec 019, rest**: checked on the phone emulator rotated (914 dp: rail, the training log's two
  panes, the chosen ride kept from two panes to one), on 2026-11-20. Left: the coach's two panes and the calendar's
  (≥ 1200 dp, a tablet emulator), deleting the ride shown.
- **Manual test of spec 018, rest**: checked on the emulator on 2026-11-20 (start offline after a login, the banner,
  saved dashboard, log and ride, the disabled actions, Retry back online). Left: the network callback (airplane mode
  on a device without `adb reverse`), the calendar offline.

## Not on the web either

- Editing a ride's name (the backend has no endpoint for it).
- Notifications about rides synced from intervals.icu or Garmin: the backend syncs only when asked; needs a
  background sync there first (spec 020, out of scope).
