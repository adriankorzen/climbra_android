# 009: Importing rides from files

- **Status:** Implemented
- **Created:** 2026-11-18

## Problem

On the web a rider adds rides from FIT and TCX files: a single ride, which is analysed right away (cyclingapp spec
004), or a history of rides as an import job (cyclingapp specs 037, 040). The app has no import at all: rides reach it
only through the intervals.icu sync (spec 007), and the empty dashboard and training log point to climbra.app. A
rider who records on a device without intervals.icu, or who gets a `.fit` file from Garmin Connect, Wahoo or Zwift on
the phone, cannot add it in the app.

## Scope

- An **import screen** with two parts, like the web's `import-dialog`:
  - **Single ride**: one FIT / TCX / GZ / ZIP file and an optional name, sent with
    `POST /api/riders/{id}/activities/upload`; the ride opens when it is stored.
  - **Several rides**: FIT / TCX / GZ / ZIP files of together at most 50 MB, sent as one import job with
    `POST /api/riders/{id}/imports` and followed until it ends: progress, then the counters and the files that were
    not imported.
- Entry points: "Import rides" in the training log's top bar, and on the empty dashboard and training log in place of
  "Open climbra.app".
- **Sharing into the app**: Climbra appears in the system's share sheet ("Share", "Open with") for ride files; shared
  files open the import screen with them chosen.

### Out of scope

- Larger selections (more than 50 MB, e.g. a whole Garmin account export): the web reads such archives in the
  browser and sends them in batches with pause and resume (cyclingapp spec 040). The app says so and opens
  climbra.app.
- Reading archives on the device (choosing the ride files inside a ZIP): the backend reads the ZIP.
- Uploads that go on after the app is closed (a foreground service / WorkManager): the request runs while the app's
  process lives; once the job is on the server, it finishes there without the app.
- Garmin sync and connecting Garmin Connect (spec 004, 007).
- The list of earlier import jobs (`GET /api/riders/{id}/imports`): the web does not show it either.

## Behaviour

### Import screen

Full screen on the training log's stack, title "Import rides", back arrow. A segmented button switches between
**Single ride** (default; on a phone the usual case is the ride just recorded) and **Several rides**. Switching is
disabled while something is being sent. An account without a rider sees "Create your rider profile first" with a
button to the profile (spec 004) instead of the form.

Accepted files: names ending in `.fit`, `.tcx`, `.gz` or `.zip` (case ignored), as the backend's
`RideFile.isSupported`. Files are chosen with the system picker (`OpenDocument` / `OpenMultipleDocuments`, MIME types
`*/*`, so files with an unknown type can be chosen) and read from their content URI only when sent, streamed into the
multipart request (as the coach's files, spec 006). The rider's time zone goes along as `timeZone` (the device's
zone, as the web's `browserTimeZone`).

### Single ride

- A "Choose file" field showing the chosen file's name; another file name → "Skipped unsupported file: {name}" and
  nothing chosen.
- **Name**, optional, at most 255 characters; blank → not sent.
- Hints: "Add one ride from a FIT or TCX file: it is stored and analysed right away." and the sources hint of the web
  ("Get the files from Garmin Connect…").
- **Add ride** (disabled without a file or while sending) → `POST …/activities/upload` (part `file`, `name`,
  `timeZone`):
  - `201` → `DataChanges.changed()`, a toast "Ride added" (it outlives the screen), and the import screen gives way to the ride's detail
    (spec 003) on the same stack.
  - `409` with `activityId` → "This ride is already stored." with **Open ride** (opens that ride).
  - `413` → "The file is larger than 50 MB."; `422` → "The file can't be read as a ride."; `503
    FILE_PROCESSING_BUSY` → "The server is busy, try again in a few seconds."; other errors as usual (`toUiText`).
  - The chosen file and name stay after an error.

### Several rides

- **Choose files** adds files to the selection (a file with a name already chosen is not added twice); each chosen
  file is a row with its name, size and a remove button. Unsupported files → "Skipped unsupported files: {names}".
- Under the list: "{count} files · {size} MB".
- Together more than 50 MB → "Large exports (more than 50 MB) are imported on climbra.app" with "Open climbra.app"
  (Custom Tab to `{webBaseUrl}/activities`); **Import** is disabled.
- Hints: "Choose FIT, TCX or ZIP files of up to 200 rides and 50 MB in all; the newest 200 rides are imported." and
  the web's thresholds hint ("TSS of older rides uses the thresholds in your profile history…").
- **Import** (disabled without files, over 50 MB or while sending) → `POST …/imports` (parts `files`, `timeZone`):
  - `202` with the job → the screen shows its progress (below).
  - `409 IMPORT_RUNNING` → "An import is already running. Wait until it ends."; `413` → "The files are larger than
    50 MB."; `503 IMPORT_QUEUE_FULL` → "The server is busy with other imports, try again in a minute."; other errors
    as usual. The selection stays.
- **Progress** while the job is `QUEUED` / `RUNNING`, polled with `GET /api/imports/{id}?items=true` every 2 s:
  `QUEUED` → "The import is queued…"; `RUNNING` without `selectedRides` → "Reading the files…"; with it → "Importing
  rides: {processed} of {selected}" and a determinate progress bar. "You can leave this screen: the import goes on."
- **Result** when the job ends:
  - `COMPLETED` → "Import finished"; `FAILED` → "The import failed: {message}".
  - Counters from the backend: Imported, Already stored, Not cycling, Invalid (`failed`), and Over the limit
    (`overLimit`) when not 0.
  - "Rides from {first} – {last}" when the job has `firstRideDate` and `lastRideDate`.
  - "Files not imported ({count})", expandable: each item whose status is not `IMPORTED` with its file name, the
    status as the web names it (Already stored, Not cycling, Over the limit, Invalid file, Not saved, try again, Too
    large) and the backend's message. A `DUPLICATE` item with `activityId` opens that ride on tap.
  - **Import more** clears the result and the selection.
- `DataChanges.changed()` when the job ends with imported rides, so the dashboard, the training log and the calendar
  load again (spec 001).

### The import outside the screen

The import of several rides is app-scoped (`activities.RideImport`, like `IntervalsSync`, spec 007): one at a time;
leaving the screen keeps it going, coming back shows its progress or its result. Polling runs in the app scope while the
process lives, as `IntervalsSync`'s. When the job ends while the import screen is not shown, the shell's
snackbar says "Imported rides: {count}" (or "The import failed: {message}") with "Show" opening the import screen.
A failed poll is retried at the next interval (the server may be restarting). Logout forgets the import. The single
ride's upload is the screen's own.

The intervals.icu sync is independent: the backend locks uploads and syncs separately, so both can run.

### Sharing into the app

- `MainActivity` takes `ACTION_SEND`, `ACTION_SEND_MULTIPLE` and `ACTION_VIEW` (content URIs) for the MIME types ride
  files come with: `application/octet-stream`, `application/vnd.ant.fit`, `application/fit`, `application/zip`,
  `application/x-zip-compressed`, `application/gzip`, `application/x-gzip`, `application/vnd.garmin.tcx+xml`,
  `application/xml`, `text/xml`. The file name (`OpenableColumns.DISPLAY_NAME`) decides, as for chosen files.
- One shared ride file → the import screen on the training log's tab, **Single ride** with the file chosen. Several →
  **Several rides** with them chosen (unsupported ones reported as when chosen). Nothing is sent before the rider
  taps Add ride / Import.
- No ride file among them → snackbar "Climbra imports FIT, TCX, GZ and ZIP files." and the app opens as usual.
- Logged out, or terms not accepted: the shared files wait (in memory) until the shell shows, then the import screen
  opens with them. They are lost if the app's process ends before that (the read permission of the URIs ends with
  it).
- An import of several rides is running: the screen shows it, and shared files for Several rides are told "An import
  is already running. Wait until it ends."; a single ride can still be added.
- A share while the app is open (`onNewIntent`) behaves the same.

## Backend dependencies

Existing only:

- `POST /api/riders/{riderId}/activities/upload` (multipart `file`, `name`, `timeZone`) → `201` `ActivityResponse`;
  `409` with `activityId` (duplicate), `400`, `413`, `422`, `503 FILE_PROCESSING_BUSY` (cyclingapp spec 004).
- `POST /api/riders/{riderId}/imports` (multipart `files`, `timeZone`) → `202` `ActivityImportJobResponse`;
  `409 IMPORT_RUNNING`, `400`, `413`, `503 IMPORT_QUEUE_FULL` (cyclingapp specs 037, 040). Limits from the backend's
  `upload.*`: 50 MB per request, 200 rides, 500 files.
- `GET /api/imports/{id}?items=true` → `ActivityImportJobResponse` with `items`.

No backend change.

## Screens

- `activities.ImportRoute` / `ImportScreen`: `SingleChoiceSegmentedButtonRow` (Single ride / Several rides),
  `OutlinedButton` "Choose file(s)", `OutlinedTextField` for the name, `ListItem` rows of the chosen files with an
  `IconButton` to remove, `LinearProgressIndicator`, counters as small tiles (the web's `counters`), an expandable
  list of the files not imported, `Button` for Add ride / Import.
- Training log: a top bar `IconButton` "Import rides" (icon `upload_file`, new). Dashboard and training log empty
  states: "Import rides" button in place of "Open climbra.app" (the no-rider hint stays as it is).
- Navigation: a new route `ActivitiesImportRoute(shared…)` in the training log's graph; `ShellNavigator.openImport()`
  switches to the training log's tab; the ride detail replaces the import route after a single ride.
- Icons: `upload_file` (new), existing `close`, `expand_more`.
- Web mapping: `import-dialog` (tabs History / Single ride), `import-progress`, `import-tracker`; the batching of
  `import-session` / `import-archive` / `import-batches` is not ported (out of scope).

## Data and storage

- `data/api`: `ImportJob` (spec 007) gains `kind`, `duplicates`, `skipped`, `overLimit`, `failed`, `firstRideDate`,
  `lastRideDate` and `items` (`ImportItem`: `fileName`, `status`, `activityId`, `message`); `ImportItemStatus` with an
  unknown value read as `FAILED`. A new `ImportApi` (or calls in `ActivityApi`) for the upload and the import; the
  poll reuses `importJob`.
- `ApiError.Http` gains `activityId` from the problem's properties (the duplicate's ride).
- Multipart bodies stream from content URIs (`RideFileSource`, spec 006), with the file's display name as file name;
  nothing is copied to the device's storage.
- Shared files are held in memory (`activities.SharedRideFiles`, app-scoped) until the import screen takes them.

## Acceptance criteria

- [x] AC1: "Import rides" in the training log's top bar and on the empty dashboard and training log opens the import
  screen on the training log's tab; no "Open climbra.app" is left on those empty states.
- [x] AC2: Only `.fit`, `.tcx`, `.gz` and `.zip` files are accepted (case ignored); others are named as skipped.
- [x] AC3: Single ride sends the file, the trimmed name or none, and the time zone; `201` marks data changed, shows
  "Ride added" and replaces the screen with the ride's detail.
- [x] AC4: A duplicate offers "Open ride" for the stored ride; `413`, `422` and `FILE_PROCESSING_BUSY` show their
  messages and keep the file and name.
- [x] AC5: Several rides lists the chosen files without doubles, with their count and size; over 50 MB disables
  Import and offers climbra.app.
- [x] AC6: Import sends all files and the time zone; `IMPORT_RUNNING`, `413` and `IMPORT_QUEUE_FULL` show their
  messages and keep the selection.
- [x] AC7: While the job runs the screen shows queued / reading / "{processed} of {selected}" from the polled job;
  a failed poll is retried.
- [x] AC8: The result shows the backend's counters, the rides' date range and the files not imported with their
  status and message; a duplicate item opens its ride; "Import more" starts over.
- [x] AC9: The import goes on when the screen is left; it ends with data changed (when rides were imported) and a
  shell snackbar when the screen is not shown; logout forgets it.
- [x] AC10: Shared ride files open the import screen with them chosen (one → Single ride, several → Several rides);
  non-ride files only show the snackbar; shares before login wait until the shell shows.
- [x] AC11: An account without a rider sees the hint to create it in the profile instead of the form.
- [x] AC12: New strings exist in English and Polish; counted texts are plurals.

## Open questions

- None.
