# Backlog

Checks left to do and what is not planned yet. Features move into a spec in `specs/` when taken up (backend work as a
spec in `../cyclingapp/specs/` first). Updated on 2026-11-18.

## To check

- **Manual test of specs 008 – 012** on the emulator against the local backend: an adjustment request → proposal →
  accept / reject, "Replace in plan" (008); a single ride upload, several files and a ZIP, a duplicate, and sharing a
  `.fit` from a file manager ("Share", "Open with"), also while logged out (009); sending feedback (010); the Garmin
  sync on an admin account, from the profile and by a pull (011); sorting and the sport filter of the training log (012). Sharing is not covered by the automated tests.

## Specs written, not implemented yet (status Draft)

- [013](specs/013-native-registration.md) — registration and password reset in the app (Turnstile in a WebView).
- [014](specs/014-large-ride-exports.md) — importing large exports (batches, foreground service).
- [015](specs/015-calendar-month-view.md) — the calendar's month view on wide windows.
- [016](specs/016-garmin-connect-in-the-app.md) — connecting Garmin Connect in the app; needs cyclingapp spec 055
  (`../cyclingapp/specs/055-garmin-connect-from-the-app.md`, Draft) first.

## Not on the web either

- Editing a ride's name (the backend has no endpoint for it).
- Push notifications when the coach's answer or an import is ready (the backend has no push).
- Opening the emails' links (verify the address, set a new password) in the app: needs App Links (spec 013, out of
  scope).
- Offline cache, tablet two-pane layouts.
