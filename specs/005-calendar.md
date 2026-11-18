# 005: Calendar

- **Status:** Implemented
- **Created:** 2026-11-12

## Problem

The app has no calendar. Riders want what the web calendar (`../cyclingapp/frontend/src/app/calendar`, cyclingapp
specs 009, 010, 047, 048) offers on the phone: the planned workouts of their saved training plans next to the rides
they did, how each workout went, and the actions on a workout (link a ride, skip, send to Garmin, download for Zwift).
Spec 003 left the calendar link of a ride's planned workout card for this spec, spec 004 the sending to Garmin.

## Scope

- **Calendar tab** in the navigation bar between Activities and Profile.
- **Week view** (the web's phone layout): the days Monday–Sunday of one week as a list, each with its planned
  workouts (tile with title, type, duration, TSS, status and the plan vs. ride level) and its rides (the linked ride
  under its workout, unplanned rides on their own: name, duration, TSS with the hrTSS marker). Previous / next week,
  swipe between weeks, "Today", the period (`3 Nov – 9 Nov`) as the title, which opens a date picker to jump to a week.
- **Week summary:** the plan week (number, recovery badge, focus) and ridden / planned TSS and time.
- **Plans** of the period: name, dates, methodology, goal; remove a plan (after a confirmation).
- **Adherence banners** of a plan: the suggestion to adjust it with its reasons in plain words and "Dismiss"; the
  coach's pending proposal and a running adjustment as status lines.
- **No plan** in the period: the web's note.
- **Workout detail** (a sheet / screen opened from a tile): type, long date, indoor, duration, TSS, IF, target RPE,
  description; the step profile chart (% FTP, colored by zone) and the steps table with repetitions, % FTP, watts
  from the current FTP, cadence; the "set your FTP" note without FTP. For a non-rest workout: the status badge, the
  linked ride (opens the ride) with how it was matched, the plan vs. ride comparison; unlink, skip / undo skip, link
  another ride of ±3 days.
- **Garmin Connect** (when the backend offers it): "Send to Garmin" / "Send again" on a workout with its Garmin status;
  "Import to Garmin" for the plan (the web's toolbar button, shown when there is something to send or out of date)
  with the 2 weeks it sends shown while it runs and in its result; the out-of-date notice.
- **Zwift:** download a workout with steps as `.zwo` to Downloads, with the import hint.
- A ride opens the ride detail (spec 003) on the calendar's stack; the ride detail's planned workout card links to
  the calendar at the workout's week (out of scope in spec 003).
- Pull to refresh.

### Out of scope

- The month grid of the web: spec 015.
- The AI coach parts, with the coach spec: "Ask the coach" of the suggestion and "Adjust plan" (the adjustment
  dialog), showing and accepting / rejecting the coach's proposal, "Create a training block", the plan's conversation
  link. Until then the suggestion and proposal banners carry "Open on climbra.app" (Custom Tab to
  `{webBaseUrl}/calendar?week=<monday>`).
- Connecting Garmin inside the app (spec 004: the backend's OAuth state lives in the browser session); a send while
  not connected offers "Connect on climbra.app" as the profile does.
- Editing workouts or plans, moving workouts between days.

## Behaviour

### Loading

- The rider comes from the rider context (spec 002); an account without a rider sees the profile's "Create your
  profile" hint as the other tabs.
- A week loads `GET /api/riders/{id}/calendar?from=<monday>&to=<sunday>`, and, when Garmin is available,
  `GET /api/riders/{id}/garmin-connection` (once per opening and after a send). The app computes nothing: the week's
  totals and plan week come from `weeks[0]` (cyclingapp spec 054), the step zone from `step.zone`, watts from
  `powerLowWatts` / `powerHighWatts` (`–` without FTP), statuses, levels and comparisons from the workout. It only
  places the workouts and rides on their `date`, the linked ride under its workout (`activityId`), and formats.
- The week shown, the open plan sections and the scroll position survive switching tabs and the ride detail. The
  first opening shows the week of today (device date). The neighbouring weeks are loaded when swiped to, not ahead.
- Loading shows a progress indicator in place of the days; a failure "Cannot load the calendar" with Retry; a
  refresh keeps the old week visible while it loads. After a change to rides elsewhere (`DataChanges`) the calendar
  loads again when shown.

### Days

- Monday first, the short weekday and the date; today highlighted, past days dimmed, a past workout that is not
  completed dimmed further (as the web). An empty day shows `–`.
- Workout tile: title, type icon / label, planned duration and TSS, the status (`PLANNED`, `COMPLETED` with the
  comparison level color, `MISSED`, `SKIPPED`, `REST`) as in the web's `workout-tile`. Tapping opens the workout
  detail.
- Ride rows: name or the sport, duration, TSS (`trainingLoad`) with the hrTSS marker for `HEART_RATE`. Tapping opens
  the ride.

### Plans and banners

- One card per plan in `plans`, above the days. "Remove plan from calendar" asks with the web's
  `calendar.removeConfirm` text, then `DELETE /api/riders/{id}/plans/{planId}` → snackbar "Plan “{name}” removed from
  the calendar", reload (the Garmin connection with it) and `DataChanges.changed()` (rides are unlinked).
- Suggestion: the web's "The plan needs an adjustment – ask the coach?" with one line per reason (the web's
  `adherence.reasons.*` texts, weekday, workout title, planned / actual formatted by metric), "Dismiss" →
  `POST /api/riders/{id}/plans/{planId}/suggestion/dismiss` and reload.
- Pending proposal: "The coach proposes changes to the plan" with "from {date}"; running adjustment: "The coach is
  adjusting the plan…".
- No plan in the response: "No training plan in this period. Create a training block with the coach on
  climbra.app." with "Open on climbra.app".

### Workout actions

- The detail loads `GET /api/riders/{id}/calendar?from=<date−3>&to=<date+3>` (with the Garmin connection): the
  workout itself (by id; "The workout is no longer in the calendar" when it is gone) and the rides around it, whose
  candidates to link are all but the linked one ("weekday · name").
- Skip `PUT /api/riders/{id}/planned-workouts/{wid}/skipped {skipped}`, unlink
  `DELETE .../{wid}/activity`, link `PUT .../{wid}/activity {activityId}`. Each answers the workout, which replaces
  the shown one; the rides around it load again and `DataChanges.changed()` is called, so the week loads again when
  shown. Buttons are disabled while a request runs; a failure shows its message in the detail.
- Garmin: the workout's status from `GET /api/riders/{id}/garmin-connection/workouts?from=<date>&to=<date>` (no
  status → no button). Send `POST /api/riders/{id}/planned-workouts/{wid}/garmin`, plan
  `POST /api/riders/{id}/garmin-connection/send`; the result as the web's `reportGarminSend`: a snackbar with the
  summary ("Sent to Garmin" / "Not sent", or "Sent {sent}, unchanged {unchanged}, removed {removed} ({from}–{to})"),
  then the failed titles and the incomplete reason (`GARMIN_RATE_LIMITED`, `GARMIN_UNAVAILABLE`) as errors; the
  connection reloads. Not connected: a dialog with the status message and "Connect on climbra.app"
  (`{webBaseUrl}/profile`).
- Zwift: only for a non-rest workout with steps; `GET /api/riders/{id}/planned-workouts/{wid}/zwo` through the app's
  HTTP stack, saved to Downloads with MediaStore under the server's file name (as the FIT download of spec 003); a
  snackbar says where, or the error.

## Backend dependencies

- Existing: `GET /api/riders/{id}/calendar`, `DELETE /api/riders/{id}/plans/{planId}`,
  `POST /api/riders/{id}/plans/{planId}/suggestion/dismiss`,
  `PUT/DELETE /api/riders/{id}/planned-workouts/{wid}/activity`, `PUT /api/riders/{id}/planned-workouts/{wid}/skipped`,
  `GET /api/riders/{id}/garmin-connection`, `GET /api/riders/{id}/garmin-connection/workouts`,
  `POST /api/riders/{id}/garmin-connection/send`, `POST /api/riders/{id}/planned-workouts/{wid}/garmin`,
  `GET /api/riders/{id}/planned-workouts/{wid}/zwo`.
- **New, cyclingapp spec 054:** `CalendarResponse.weeks` (week totals and plan week) and `zone` of each workout step.
  The calendar needs a backend with spec 054 deployed.

## Screens

- Calendar tab: icon `calendar_month` (`_fill1` selected), label "Calendar" / "Kalendarz". Top app bar with the
  period as a button (opens `DatePickerDialog`, the chosen day's week is shown), `chevron_left` / `chevron_right`,
  "Today" text button, and an overflow / action `send` "Import to Garmin" when shown.
- Content: `HorizontalPager` of weeks (swipe), each a `LazyColumn` with `PullToRefreshBox`: the Garmin out-of-date
  notice, plan `ElevatedCard`s with banners (`OutlinedCard` / tonal surface), the week summary card, then one row per
  day (label column + items). Workout tiles as `OutlinedCard`s with the status color strip (`extendedColors` levels).
- Workout detail: a full-screen destination on the calendar's stack (`CalendarWorkoutRoute(id, date)`) rather than a
  bottom sheet — the steps table and chart need the height; back returns to the week. The step profile is drawn on
  a Compose `Canvas` (each step as wide as its duration and as high as its upper % FTP, in its `extendedColors`
  power zone color, with the FTP line) — steps of different widths are no Vico chart type; the steps as rows,
  repeats grouped under "{n} ×".
- Dialogs: `AlertDialog` for removing a plan and connecting Garmin; the link candidates in an
  `ExposedDropdownMenuBox`; `SnackbarHost` for results.
- Icons (Material Symbols Rounded): `calendar_month` (and `_fill1`), `chevron_left`, `chevron_right`, `send`,
  `download`, `delete`, `link`, `link_off`, `event_busy` (skip), `open_in_new`, `directions_bike`.
- Strings: the web's `calendar.*`, `block.*` (week, recovery, goal), `enums.methodology.*`, `enums.workoutType.*`,
  `enums.workoutStatus.*`, `enums.stepKind.*`, `workout.*`, `adherence.*` (reasons, match sources, actions),
  `garmin.*` (send, statuses, import, outdated), `zwift.*`, `weekdaysShort.*`, English and Polish; counts as plurals.

## Data and storage

- `data/api/CalendarApi.kt`: the calendar, plan, workout, Garmin and Zwift calls; `CalendarResponse` grows to the
  full backend record (`from`, `to`, `plans`, `plannedWorkouts`, `activities`, `weeks`), replacing the
  planned-workouts-only version of spec 003 (whose link flow keeps using it). DTOs: `TrainingPlan` (with
  `PlanSuggestion`, `SuggestionReason`, `PendingProposal`; the plan weeks come from `weeks`), `CalendarWorkout` (with
  `blocks: List<WorkoutBlock>` — `repetitions`, `steps` with `kind`, `durationSeconds`, `powerLowPctFtp`,
  `powerHighPctFtp`, `powerLowWatts`, `powerHighWatts`, `cadenceLow`, `cadenceHigh`, `note`, `zone` —,
  `comparison: WorkoutComparison`, `matchSource`), `CalendarRide`, `CalendarWeek`, `CalendarPlanWeek`,
  `GarminSendResult`, `GarminWorkoutStatuses`, `SkipRequest`; `WorkoutComparison` shared with the ride detail.
  `GarminConnection` gets `sendable`, `from`, `to`.
- The week shown is kept in the ViewModel (and `SavedStateHandle`); nothing is stored on the device. A week asked for
  by another screen (the ride detail) goes through `calendar.CalendarRequests`, a singleton the calendar takes it
  from.

## Acceptance criteria

- [x] AC1: The Calendar tab shows the week of today, Monday first, with each day's workouts and rides placed on their
      date, the linked ride under its workout and unplanned rides on their own; empty days show `–`.
- [x] AC2: Previous / next, swipe, "Today" and the date picker change the week and load
      `calendar?from=<monday>&to=<sunday>`; the week survives switching tabs and opening a ride.
- [x] AC3: The week summary shows the plan week (number, recovery, focus) and ridden / planned TSS and time from
      `weeks[0]`, with no figure computed in the app.
- [x] AC4: Workout tiles show title, type, duration, TSS and the status with the comparison level; rides show name,
      duration, TSS and the hrTSS marker; tapping opens the workout detail or the ride detail on the calendar's stack.
- [x] AC5: Plan cards show name, dates, methodology and goal; removing a plan asks first, deletes, shows the snackbar
      and reloads; a failure shows its message.
- [x] AC6: The suggestion banner lists its reasons in words and "Dismiss" dismisses it; proposal and running
      adjustment show as status lines with "Open on climbra.app"; no plan shows the note.
- [x] AC7: The workout detail shows the figures, description, the step chart and the steps table (repeats, % FTP,
      watts or `–`, cadence, zone colors from `step.zone`) and the no-FTP note.
- [x] AC8: Skip, undo skip, unlink and link (candidates of ±3 days without the linked ride) send their request,
      replace the shown workout, reload the week and mark data as changed; failures show in the detail.
- [x] AC9: With Garmin available the workout shows its Garmin status and sends itself, the toolbar imports the plan;
      results, failures and incomplete reasons appear as snackbars; not connected offers "Connect on climbra.app";
      without Garmin nothing of it shows.
- [x] AC10: "Download for Zwift" (only for a workout with steps) saves the `.zwo` to Downloads and reports where.
- [x] AC11: Loading, error with Retry, pull to refresh and the reload after `DataChanges` work as described.
- [x] AC12: The ride detail's planned workout card opens the calendar at the workout's week.
- [x] AC13: All new strings exist in English and Polish.

## Decisions

- The workout detail is a full-screen destination; the month grid on wide screens comes later; the coach parts
  (adjust, proposals, creating a block) come with the coach spec, with "Open on climbra.app" meanwhile (approved
  2026-11-12).

## Tests

- `CalendarViewModelTest`: the week of today and its placement and figures (AC1, AC3), previous / next / today /
  picked week and the saved week (AC2), a week asked for by the ride detail (AC12), removing a plan and its failure
  (AC5), dismissing a suggestion (AC6), the Garmin import, without a connection, without Garmin, out of date (AC9),
  error and retry, reload after a change, refresh, no rider (AC11).
- `WorkoutDetailViewModelTest`: the workout and the rides of ±3 days, no FTP, not found (AC7, AC8), unlink, link,
  skip and a failed action (AC8), Garmin status, send, not connected, not available (AC9), the Zwift file and its
  failure (AC10).
- `CalendarScreensTest`: days, tiles, rides, summary and opening (AC1, AC3, AC4), header, swipe and date picker
  (AC2), plan card and removal (AC5), banners and no plan (AC6), Garmin import, notice, dialog and result lines
  (AC9), loading and error (AC11), the workout detail's figures, steps and watts (AC7), its actions (AC8), Garmin and
  Zwift (AC9, AC10), the ride's "Show in calendar" and the calendar tab's stack (AC4, AC12).
- `StringResourcesTest`: the same keys in English and Polish (AC13).
