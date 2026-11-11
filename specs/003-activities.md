# 003: Activities — training log and ride detail

- **Status:** Implemented
- **Created:** 2026-11-11

## Problem

The Activities tab is a placeholder and the dashboard's recent rides cannot be opened. Riders want their training log
and the analysis of a ride on the phone, with what the web app offers
(`../cyclingapp/frontend/src/app/activities/activities-page.ts`, `activity-detail-page.ts`).

## Scope

### Training log (Activities tab)

- The rides of a period, newest first, loaded page by page while scrolling (backend paging, cyclingapp spec 052).
- Period: presets 7 d / 28 d (default) / 90 d / year, and a custom range (Material date range picker).
- Search by name.
- The totals of the filtered period: rides, time, distance, elevation, kJ, TSS.
- A row: date with weekday, name, duration, distance, NP, IF (colored by its power zone, as the web), TSS with the
  hrTSS marker; tapping it opens the ride.
- Pull to refresh; empty states: no ride at all (import hint with a link to climbra.app), no ride in the period or
  matching the search.

### Ride detail

Everything the web detail page shows and does, except the AI coach:

- Header: name, long date and start time, sport, source, recording device.
- Key figures: duration, distance, elevation, TSS and / or hrTSS (with its explanation), IF, NP, average power with
  max, VI, work (kJ), average heart rate with max, cadence, the thresholds of the analysis (FTP, LTHR, max HR and the
  date they are effective from).
- "No power" note for rides without power.
- Ride chart: power, heart rate, cadence, speed and altitude on one time axis with toggles per series (only series
  the ride has), detected work blocks shaded and unders / recoveries lighter, zoom by pinch and a reset; tapping an
  interval zooms the chart to it.
- Power zones and heart rate zones (time and percent per zone, as the web's `zone-bars`; the note when HR zones come
  from max HR; the hints "set your FTP / LTHR" or "no power / HR data").
- Intervals: summary (structure — blocks, over-under or repeat sets —, counts, target power and its source, threshold,
  consistency of duration and power, VO2max stimulus time, cardiac drift), the sets table and the blocks table
  (blocks, overs, unders, repetitions, recoveries in ride order); "not stored" badge for an ad hoc result.
- Interval source: laps of the file or detection from power, when the file has laps.
- Ad hoc detection: target watts, threshold %, minimum duration, maximum gap, under threshold % → recalculate
  without storing, reset back to the stored analysis.
- Reanalyse (recalculate and store with the current thresholds).
- Download the FIT file (saved to Downloads).
- Delete the ride, after a confirmation naming the ride and its date.
- Planned workout card: the workout the ride was done as, with the plan vs. ride comparison (duration, TSS, IF,
  intervals with their levels); for an unplanned ride, linking it to one of the plan's workouts around its date.

### Out of scope

- AI coach (ask / continue a review, the review summary): with the coach spec.
- The calendar link of the planned workout card: with the calendar spec (the card shows the date instead).
- Import (FIT / TCX upload, intervals.icu sync, Garmin): on the web.
- Sorting by columns and the sport type filter of the web table (the phone list is newest first).
- Editing a ride's name.

## Behaviour

- The app computes no training figures (spec 002): rows, totals, zones, intervals and comparisons come from the
  backend as they are. It only formats them, orders interval rows by start time and converts the form's percents to
  fractions for the request.
- Training log: `GET /api/riders/{id}/rides?from&to&q&page&size=50`, the next page when the list nears its end; a
  new period or search starts from page 0 (the search waits 300 ms after typing). The period, search and scroll
  position survive switching tabs and the detail.
- Detail: `GET /api/activities/{id}` and `GET /api/activities/{id}/analysis` in parallel, the recording
  `GET /api/activities/{id}/recording?resolution=5` after them (a ride file read counts against the backend's file
  limits, spec 045 / 039, so it is loaded once per opening, not on rotation). A recording that cannot be loaded shows
  "No recording" with the rest of the page.
- Ad hoc detection: `GET /api/activities/{id}/analysis?targetWatts&relativeThreshold&minDuration&maxGap&underThreshold`
  (only the filled fields); the result replaces the shown analysis with the "not stored" badge until reset.
- Interval source: `PUT /api/activities/{id}/interval-source {useLaps}`; reanalyse: `POST /api/activities/{id}/analysis`;
  both replace the shown analysis and mark the dashboard and the log as changed (they load again when shown).
- FIT download: the file is fetched through the app's HTTP stack (session cookie) and stored in Downloads with
  MediaStore (no storage permission on Android 10+); a snackbar says where, or the error.
- Delete: `DELETE /api/activities/{id}` → back to the log, which loads again, and a snackbar. `409 ACTIVITY_BUSY` (a coach
  request about the ride is running) shows its message and stays.
- Linking: the plan's workouts from `GET /api/riders/{id}/calendar?from=<day−3>&to=<day+3>` that are neither rest days
  nor completed (as the web's `MAX_LINK_DAYS` and filter), `PUT /api/riders/{id}/planned-workouts/{workoutId}/activity`,
  then the activity loads again.
- Dashboard: its recent rides open the detail.
- Errors are problem details translated like the web (`ACTIVITY_BUSY`, `FILE_STORAGE_UNAVAILABLE`,
  `FILE_PROCESSING_BUSY`, `TOO_MANY_REQUESTS`, `WORKOUT_COMPLETED`, …); a missing or another account's ride → "Ride not
  found" with the way back.

## Backend dependencies

- **New, cyclingapp spec 052:** `GET /api/riders/{id}/rides` and `thresholds.effectiveFrom` of the analysis.
- Existing: `GET/DELETE /api/activities/{id}`, `GET/POST /api/activities/{id}/analysis`,
  `PUT /api/activities/{id}/interval-source`, `GET /api/activities/{id}/recording`, `GET /api/activities/{id}/file`,
  `GET /api/riders/{id}/calendar`, `PUT /api/riders/{id}/planned-workouts/{workoutId}/activity`.

## Screens

- Activities tab: a search field, a row of `FilterChip`s for the presets plus "Custom" (`DateRangePicker` dialog), a
  totals card (3 × 2: six figures do not fit one row of a phone), a `LazyColumn` of rows with an IF chip; a progress
  row while the next page loads.
- Ride detail: a destination on the back stack of the tab it is opened from. Each tab is a nested navigation graph
  (`DashboardGraph`, `ActivitiesGraph`, `ProfileGraph`) with its own ride route (`DashboardRideRoute(id)`,
  `ActivitiesRideRoute(id)`), so the tab stays selected and keeps the ride when switching tabs. Top app bar with back,
  an overflow menu (Reanalyse, Download FIT, Delete); a scrolling column: header, key figure tiles (`KpiTile` of
  spec 002, 2 / 4 columns, hints on up to two lines), planned workout card, ride chart card with series `FilterChip`s,
  zone cards (horizontal bars in the zone colors of `extendedColors`), the intervals card with the interval source
  `SegmentedButton` (only there: the web shows it in the chart card too, twice is noise on a phone), the detection form
  in an expandable section, and the sets / blocks as lists of rows.
- Zone names are the app's translations by zone number and kind (the web's `zones.power.*`, `zones.hr.*`,
  `zones.hrMax.*`), the backend's English name only for an unknown number.
- Ride chart drawn on a Compose `Canvas` (not Vico: Vico has no vertical shaded ranges for the blocks and one shared
  scale per axis side): each series on its own scale (power and cadence from 0, altitude from its minimum), blocks
  shaded and unders / recoveries lighter, pinch to zoom, drag to pan, tap for the values at a time, "Whole ride" to
  reset.
- Deleting closes the detail with a toast "Ride deleted".
- Material Symbols: `search`, `close`, `calendar_month`, `download`, `delete`, `refresh`, `more_vert`, `arrow_back`,
  `zoom_out_map`.
- Strings: the web's `activities.*`, `activity.*`, `rideChart.*`, `adherence.*`, `source.*`, `sport.*`, `zones.*`,
  `ranges.*`, `enums.workoutType.*`, `enums.complianceLevel.*` keys used here, in English and Polish.

## Data and storage

- DTOs: `RideListResponse`, `RideRow`, `RideTotals`, `ActivityResponse` (+ planned workout and comparison),
  `ActivityAnalysisResponse` (power, heart rate, zones, intervals with blocks, unders, recoveries, sets),
  `ActivityRecording`, `CalendarResponse` (only the planned workouts), `IntervalSourceRequest`.
- Nothing is cached on the device; the FIT file goes to the user's Downloads.

## Acceptance criteria

- [x] AC1: The log requests `rides` with the period, search and page size, loads the next page at the end of the
      list, and starts again from page 0 when the period or search changes (after 300 ms of typing).
- [x] AC2: The presets and a custom range set `from` / `to`; the totals card shows the response's totals.
- [x] AC3: Rows show the backend's values formatted, IF colored by zone, the hrTSS marker; tapping opens the detail;
      the dashboard's recent rides open it too.
- [x] AC4: Empty states: no ride at all (`hasRides` false) with the import hint; no ride matching the period or search.
- [x] AC5: The detail shows header, key figures, the thresholds with their date, zones with their hints, intervals with
      summary, sets and blocks in ride order, and the "no power" note for rides without power.
- [x] AC6: The ride chart shows only the series the recording has, toggles them, shades the blocks, and zooms to an
      interval tapped in the lists; without a recording it says so.
- [x] AC7: Ad hoc detection sends only the filled fields (percents as fractions), shows the result with "not stored",
      and reset returns to the stored analysis without a request.
- [x] AC8: Interval source and reanalyse send their requests and show the returned analysis; the dashboard and the
      log load again when shown next.
- [x] AC9: Download FIT stores the file in Downloads and reports it; a failure shows the translated error.
- [x] AC10: Delete asks first, deletes, returns to the reloaded log; `409 ACTIVITY_BUSY` shows its message and stays.
- [x] AC11: The planned workout card shows the workout and the comparison; an unplanned ride offers the workouts of
      ±3 days that are neither rest nor completed, and linking reloads the ride.
- [x] AC12: A missing ride shows "Ride not found"; translated error codes show their messages.
- [x] AC13: Strings exist in English and Polish; numbers and dates follow the app language.

## Open questions

- None (decisions 2026-11-11: list from the backend — cyclingapp spec 052 with the web; the detail as on the web
  except the coach; period and search, newest first).

## Tests

- `ActivitiesViewModelTest`: the first page (28 days, page 0, size 50, nothing of the whole history), the next page,
  presets and a custom range from page 0, the search after its pause, no ride at all, no rider, a failed request, a
  reload after rides changed elsewhere (AC1, AC2, AC4, AC8).
- `RideDetailViewModelTest`: ride and analysis before the recording at 5 s, a missing ride, a failed recording, ad hoc
  detection with only the filled fields and reset without a request, invalid fields, interval source and reanalyse,
  FIT download (name from `Content-Disposition`, backend error codes also for the raw response), delete and
  `409 ACTIVITY_BUSY`, the link candidates of ±3 days and linking (AC5–AC12).
- `RidePresentationTest`: the detection query, interval rows in ride order, the chart window, and the dashboard axis
  labels never being blank (a crash of spec 002 found here: Vico throws for a blank label while a range switch
  animates).
- `ActivitiesScreensTest` (Compose on Robolectric): log rows, totals, presets, search, empty states; detail figures,
  thresholds with their date, translated zone names, no-power page, series toggles, the "not stored" badge, zooming
  to a tapped interval, no recording, interval source, the delete confirmation, the planned workout card and linking,
  a missing ride; a ride opening on the stack of its tab (AC3–AC13).
- Checked by hand on the API 37 emulator against the local backend with spec 052 and 35 uploaded TCX rides: the log
  with totals, the detail with tiles, chart and translated zones, ad hoc detection at 100 % FTP (blocks found, "not
  stored"), zooming the chart to block 1. The FIT download was not checked on the device.
