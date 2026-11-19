# Backlog

Checks left to do and what is not planned yet. Features move into a spec in `specs/` when taken up (backend work as a
spec in `../cyclingapp/specs/` first). Updated on 2026-11-19.

## To check

- **Manual test of spec 016** against a backend with cyclingapp spec 055 deployed and `GARMIN_CONNECT_*` set (the
  production one, or a local one with the Developer Portal app): Connect from the profile and from a send in the
  calendar, Garmin's consent in the Custom Tab, the return to the app and its snackbar; refusing in Garmin; closing the
  Custom Tab; a return after the session ended (log in again). The Custom Tab and the redirect are not covered by
  the automated tests.
- **Manual test of specs 008 – 015** on the emulator against the local backend: an adjustment request → proposal →
  accept / reject, "Replace in plan" (008); a single ride upload, several files and a ZIP, a duplicate, and sharing a
  `.fit` from a file manager ("Share", "Open with"), also while logged out (009); sending feedback (010); the Garmin
  sync on an admin account, from the profile and by a pull (011); sorting and the sport filter of the training log (012); registering and resetting the password against the production backend, where Turnstile is on (013); a large export (a Garmin account export) with the app in the background and the screen off, Stop and a paused import (014); the month view on a tablet or in landscape (015). Sharing is not covered by the automated tests.

## Not on the web either

- Editing a ride's name (the backend has no endpoint for it).
- Push notifications when the coach's answer or an import is ready (the backend has no push).
- Opening the emails' links (verify the address, set a new password) in the app: needs App Links (spec 013, out of
  scope).
- Offline cache, tablet two-pane layouts.
