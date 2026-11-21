# Backlog

Checks left to do and what is not planned yet. Features move into a spec in `specs/` when taken up (backend work as a
spec in `../cyclingapp/specs/` first). Updated on 2026-11-21.

## To check

- **Spec 017, first release:** create the release keystore (`keytool`), put it and its passwords in the repository's
  secrets (README, "Release builds"), add the release key's SHA-1 to an Android OAuth client for Google login, check the
  first CI run on GitHub, then tag `v0.1.0` and go through the README's smoke test with the signed APK against the
  production backend (the release build was only seen starting to the login screen on the emulator).
- **Specs 020 and 021, setup and manual test:** create the Firebase project and its service account (cyclingapp
  `deploy/README.md`), set `FCM_*` on the server and `climbra.firebase.*` / the `CLIMBRA_FIREBASE_*` variables here;
  then on a device: the permission after the first coach request, a notification for a coach answer and an import with
  the app in the background (none in the foreground), the tap opening the conversation / training log, the profile's
  row, logout removing the token. After the release key exists: its SHA-256 in `ANDROID_CERT_FINGERPRINTS`, Google's
  assetlinks checker, and the four email links opening the app (also logged in), else `adb` as in the README. The
  backend's next deploy moves the legal documents to 2026-11-21 (everyone accepts them again).
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
