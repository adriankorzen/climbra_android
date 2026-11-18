# 014: Importing large ride exports

- **Status:** Draft
- **Created:** 2026-11-18

## Problem

Spec 009 imports at most 50 MB in one go and sends larger selections to climbra.app. A rider's history usually comes
as one big archive: a whole Garmin account export (several GB, archives inside archives) or a Strava export (`.fit.gz`
files). The web reads such archives in the browser and sends only the ride files, in batches within the backend's
limits, one after the other, with pause and resume (cyclingapp spec 040). On a phone, where the export was just
downloaded, the rider cannot import it.

## Scope

- **Reading the chosen files on the device**, as the web's `import-archive`: FIT and TCX files, also gzipped
  (`.fit.gz`, `.tcx.gz`), loose or inside ZIP archives nested up to 3 deep (the backend's `FitArchiveReader.MAX_DEPTH`);
  other entries are ignored and counted, files over 50 MB uncompressed are rejected as too large.
- **Batches** as the web's `import-batches`: at most 100 files, 40 MB packed and 200 MB raw each, packed as a ZIP and
  sent as an ordinary import job (`POST /api/riders/{id}/imports`, spec 009), the next when the previous job ended.
  A batch that packs over 50 MB is split in two.
- **One import for the rider** as the web's `import-session`: the progress over all batches ("Importing rides:
  {processed} of {selected} · batch {n} of {m}"), the outcomes of all files merged (an imported ride stays imported
  when a batch is sent again), Stop (after the current batch), Pause on a failure with Resume / Finish, waiting for
  a free slot (`503 IMPORT_QUEUE_FULL` with `Retry-After`, at most 30 minutes) and retrying network errors (5, 15, 30 s).
- **Running in the background**: a foreground service with a progress notification ("Importing rides: 120 of 830"),
  so the import goes on when the app is left or the screen is off; Android may still stop it (battery saver), then
  the files not sent are told as in the web's "Files not sent".
- The import screen's **Several rides** (spec 009) takes any size: up to 50 MB the files go as today, above they go
  this way. "Large exports are imported on climbra.app" goes away.

### Out of scope

- Choosing single rides inside an archive: all ride files are sent, as on the web; batches of 100 files stay under
  the backend's 200 newest rides per upload, so every ride of the export is imported.
- Resuming after the app's process was killed: the files are lost with the URI permissions; the rider chooses the
  export again and the stored rides come back as duplicates (as on the web after closing the tab).
- Imports from other sources (Strava API, Garmin bulk) — not on the web either.

## Behaviour

- **Reading** shows "Reading the files…" with the count of ride files found so far; an unreadable archive ends the
  import before anything is sent with "Could not read the file {name}. Check that the archive is not damaged.";
  no ride file at all → "There are no FIT or TCX files in the chosen files."
- Entries are read from the content URIs as streams (`ZipInputStream`), nothing is unpacked to storage but the batch
  being sent (in the app's cache, deleted after it was sent).
- **Sending**: batch after batch as above; each job polled as in spec 009; `DataChanges.changed()` after each batch
  that imported rides, so the training log fills while it runs.
- **Stop** (while sending): "Stopping after this batch…"; the result then names the files not sent.
- **Pause** on a failure that retries did not fix (e.g. the server refused the batch twice): the notification and the
  screen say "Import paused: {message}" with **Resume** (sends the batch again) and **Finish** (ends with what was
  imported).
- **Result** as spec 009's, over all batches, plus "Files not sent: {count}. Choose the same export again, and rides
  already stored will be skipped." when some were not sent.
- **Logout** cancels the import; nothing more is sent.
- The notification's tap opens the import screen; it needs the `POST_NOTIFICATIONS` permission on Android 13+, asked
  when the first large import starts (without it the import runs without a visible notification).

## Backend dependencies

Existing only: `POST /api/riders/{id}/imports` and `GET /api/imports/{id}?items=true` (spec 009, cyclingapp specs
037, 040), the limits of `upload.*` (50 MB per request, 200 rides, 500 files, 300 MB raw). No backend change.

## Screens

- The import screen of spec 009, Several rides: the progress with the batch, Stop, Pause with Resume / Finish, the
  "Files not sent" line.
- A foreground service notification (channel "Ride imports") with progress and a Stop action.
- Manifest: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`.

## Data and storage

- `activities.ExportReader` (archive reading, entries as streams), `activities.ImportBatches` (dividing and packing,
  the web's limits), `activities.ImportSession` (the run: batches, retries, pause, stop, merged outcomes), and a
  foreground `ImportService` that runs it; `RideImport` (spec 009) shows its state.
- The packed batch in the app's cache directory, deleted after it was sent or when the import ends.

## Acceptance criteria

- [ ] AC1: Ride files are found loose, gzipped and in archives up to 3 deep; other entries are ignored and counted;
  files over 50 MB are rejected as too large.
- [ ] AC2: Batches stay within 100 files, 40 MB packed and 200 MB raw; a batch that packs over 50 MB is split.
- [ ] AC3: Batches are sent one after the other, each followed to its end; the progress names the batch.
- [ ] AC4: `IMPORT_QUEUE_FULL` waits for `Retry-After` (at most 30 minutes in all), network errors are retried after
  5, 15 and 30 s, then the import pauses with Resume and Finish.
- [ ] AC5: Outcomes of all batches are merged, an imported ride staying imported; the result counts them and names
  the files not sent.
- [ ] AC6: Stop ends after the current batch; logout cancels.
- [ ] AC7: The import runs in a foreground service with a progress notification that opens the screen.
- [ ] AC8: Selections up to 50 MB still go as one job (spec 009); "Large exports are imported on climbra.app" is gone.
- [ ] AC9: New strings exist in English and Polish; counted texts are plurals.

## Open questions

- None.
