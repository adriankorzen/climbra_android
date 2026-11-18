# Backlog

Web features the app does not have yet, and checks left to do. Each item becomes a spec in `specs/` when it is taken
up (backend work as a spec in `../cyclingapp/specs/` first). Noted on 2026-11-18.

## To check

- **Manual test of specs 008 and 009** on the emulator against the local backend: an adjustment request → proposal →
  accept / reject, "Replace in plan"; a single ride upload, several files and a ZIP, a duplicate, and sharing a
  `.fit` from a file manager ("Share", "Open with"), also while logged out. Sharing is not covered by the automated
  tests.

## Web features not in the app

- **Connecting Garmin Connect in the app.** The profile and the calendar open the web profile for it: the backend keeps
  the OAuth state in the browser's session (cyclingapp spec 047), which a Custom Tab does not share with the app. Needs
  a backend spec first (e.g. an app redirect / one-time token for the OAuth start).
- **Large ride exports (more than 50 MB)**, e.g. a whole Garmin account export: the web reads the archives in the
  browser and sends batches with pause and resume (cyclingapp spec 040). Spec 009 leaves them on the web.
- **Registration, password reset and email verification as native screens**: they open the web pages in a Custom Tab
  (spec 001).
- **Smaller ones:** the calendar's month grid (the phone shows weeks; a wider layout could use it), sorting and the
  sport filter of the training log, editing a ride's name.

## Not on the web either

- Push notifications when the coach's answer or an import is ready (the backend has no push).
- Offline cache, tablet / two-pane layouts.
