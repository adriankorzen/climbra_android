# 027: Home screen widget

- **Status:** Implemented
- **Created:** 2026-11-24

## Problem

To see today's workout or the current form the rider has to open the app and go to the calendar or the dashboard.
A home screen widget shows both at a glance. Everything it shows is already on the backend (the calendar, the
training load), so the widget only requests and formats.

## Scope

- One resizable widget "Climbra – today" (Jetpack Glance) with:
  - the day it is for (the backend's `today`);
  - today's planned workout: title, duration, planned TSS, its status (planned, completed, missed, skipped), a rest day,
    or "No workout planned";
  - the form: CTL and TSB with the form range's name and color, as the dashboard's tiles.
- Two layouts by size: compact (from 2 × 1 cells) and full (from 3 × 2 cells).
- Taps: the workout opens it in the app, the rest of the widget opens the dashboard.
- A refresh button, refreshes in the background, and the states for a logged-out account, an account without rider and
  an error.

### Out of scope

- Tomorrow's workout or the week (a later spec, if wanted).
- Sending the workout to Garmin from the widget, skipping it (actions that send something).
- The latest ride and its TSS.
- The app's light / dark choice of the profile: the widget follows the system's theme (launchers render it outside the
  app).
- A widget configuration screen.

## Behaviour

- **Data.** One refresh makes, in this order: `GET /api/riders` (the account's rider, first entry, as
  `RiderRepository`, without the time zone update), `GET /api/riders/{id}/training-load?days=1&weeks=1` (its `today`
  and `fitness`), `GET /api/riders/{id}/calendar?from={today}&to={today}` with that `today`. Nothing is computed on
  the device: CTL, TSB, the form range, the workout's figures and its status are the backend's.
- **The day.** The header shows `today` as `DateStyle.WEEKDAY`. An answer saved for offline (spec 018) from another
  day shows that day, so old data is visible as such.
- **Workout.**
  - The day's planned workouts in the order of the answer; the first one to ride is shown, more of them add "+ 1 more"
    (plural). A day of only rest entries is a rest day.
  - `REST` → "Rest day"; no workout → "No workout planned"; otherwise the title, "1:15 · TSS 82 · Indoor" (`Fmt.hm`,
    the calendar's "Indoor" text for indoor workouts) and the status with the calendar's texts: `PLANNED` nothing,
    `COMPLETED` "Completed" (success color, and a check icon before the title), `MISSED` "Missed" (error), `SKIPPED`
    "Skipped" (faint text).
- **Form.** "CTL 64" (no decimals) and "TSB -8 · Productive" (TSB with its sign, `Fmt.signed`, and the range's name
  in its color, as the dashboard's TSB tile, spec 002). The labels are CTL / TSB in both languages, as the dashboard's
  short labels: in Polish "forma" is CTL there, so "Forma" for TSB would mislead.
- **Layouts** (`SizeMode.Responsive`, 110 × 40 and 180 × 110 dp):
  - compact (below 180 × 110 dp): the workout's title (or rest / none, the check icon when completed) and the TSB line;
  - full: the header (day, refresh button), the workout (title, details, status, more) and the CTL and TSB line.
- **Taps.** The workout opens the workout (`ShellNavigator.openWorkout`, on the calendar's tab, two panes from
  1200 dp, spec 019). Anything else, also "Rest day" and "No workout planned", opens the dashboard. Without data, a
  tap opens the app (login, terms). Through `MainActivity`'s intent and `PushTargets`, as a tapped notification
  (spec 020); the intents carry the target as their identifier, so the pending intents of the two taps differ.
- **Refresh.**
  - Periodic work (WorkManager) every hour, while a widget is on the home screen (enqueued when the first one is
    placed, cancelled when the last one is removed), with network required.
  - A one-time work shortly after the next local midnight, so the new day's workout appears; it enqueues the next
    one.
  - When the app goes to the background (`AppForeground`), when `DataChanges` changes (rides), and when the session
    state changes (login, logout, terms).
  - The refresh button; while it refreshes the button shows a progress indicator.
  - No refresh while nothing is placed.
  - A widget shown loads once per Glance session, and again when `TodayWidgetUpdates.version` grows; one not shown
    starts a session through `updateAll`.
- **Offline.** The requests go through `Network`'s `OfflineInterceptor`, so the saved answers (spec 018) are shown when
  the backend cannot be reached; `calendar` is already on the saved list, its widget range is a URL of its own and is
  saved as well.
- **States.**
  - Not logged in (no session, or `401`): "Log in to Climbra to see today's workout". The logout deletes the saved
    answers (spec 018) and refreshes the widget, so nothing of the account stays on the home screen.
  - Terms not accepted (`403 TERMS_NOT_ACCEPTED`): "Open Climbra to accept the terms".
  - No rider: "Create your rider profile in Climbra".
  - Error without saved answer: "Cannot load today's training" with the refresh button. A failed calendar request with
    a successful training load shows the form and "–" for the workout.
  - Before the first load: Glance's loading layout; `Loading` shows "Loading…".
  - Logged out (`SessionState.LoggedOut`) the widget makes no request.
- The widget's requests do not end the app's session state on their own: a `401` is shown as logged out in the widget;
  `SessionEventsInterceptor` reacts as for any request.
- **Language and theme.** Strings from the app's resources in the app's language (per-app locale); numbers and dates
  through `Fmt` with that locale. Colors: the app's light and dark schemes and domain colors (`ui.theme`) mapped to
  `GlanceTheme`'s color providers, following the system's dark mode.

## Backend dependencies

- Existing: `GET /api/riders`, `GET /api/riders/{id}/training-load` (cyclingapp spec 051, `days` and `weeks` from 1),
  `GET /api/riders/{id}/calendar` (cyclingapp specs 009, 010, 054). No backend change.

## Screens

- Widget (`widget` package): `TodayWidget : GlanceAppWidget`, `TodayWidgetReceiver : GlanceAppWidgetReceiver`, its
  `appwidget-provider` XML (`res/xml/today_widget_info.xml`: resizable both ways, `minWidth` / `minHeight` for 2 × 1,
  target 3 × 2, a preview layout (`layout/widget_today_preview.xml`, sample content, API 31+) and description,
  `widgetCategory="home_screen"`).
- The web has no counterpart; the content follows the dashboard's TSB tile (spec 002) and the calendar's workout row
  (spec 005).
- Glance components: a `Column` with `GlanceTheme.colors.surface`, `Text`, `Image` with Material Symbols Rounded
  (`ic_check_circle_fill1`, `ic_refresh`), `CircularProgressIndicator` for the refresh, `cornerRadius` from the system
  (`system_app_widget_background_radius`, Android 12+).
- Shell: `PushTarget.Workout(id, date)` and `PushTarget.Dashboard`, opened as the existing targets.

## Data and storage

- No new DTOs (`Rider`, `TrainingLoadResponse`, `CalendarResponse`).
- `widget.TodayRepository`: the three requests → `TodayState` (`Loading`, `LoggedOut`, `NeedsTerms`, `NoRider`,
  `Failed`, `Ready(day, workout, fitness)`, the workout `None`, `Rest`, `Unavailable` or `Planned(workout, more)`).
- `widget.TodayWidgetUpdates` (app-scoped, created at start): the triggers above, `version`, `updateAll`.
- `widget.TodayWidgetSchedule` and `TodayRefreshWorker`: the hourly and the midnight work; `TodayWidgetEntryPoint`
  gives the widget, the receiver and the worker their objects (not created by Hilt).
- Nothing stored by the widget itself: Glance keeps no state, each update loads through the network stack (whose
  offline answers are encrypted, spec 018).
- New dependencies: `androidx.glance:glance-appwidget`, `glance-material3`, `androidx.work:work-runtime-ktx`, for tests
  `glance-appwidget-testing` and `work-testing` (Glance 1.2.0, WorkManager 2.12.0).
- Strings in `values` and `values-pl`.

## Acceptance criteria

- [x] AC1: A refresh requests `riders`, `training-load?days=1&weeks=1` and `calendar` for the answer's `today`, once
  each, and shows the answer's CTL, TSB and form range, computing nothing.
- [x] AC2: The workout row shows the title, duration and planned TSS; `COMPLETED`, `MISSED` and `SKIPPED` show their
  status; `REST` shows "Rest day"; no workout shows "No workout planned"; a second workout adds "+ 1 more".
- [x] AC3: The form shows the sign of TSB and the name and color of each of the five ranges.
- [x] AC4: The compact and the full layout show their content by the widget's size.
- [x] AC5: The header shows the answer's `today`, also when it comes from an answer saved on another day.
- [x] AC6: A tap on the workout opens it on the calendar's tab; any other tap opens the dashboard.
- [x] AC7: Logged out (no session or `401`) shows the login text; terms not accepted and no rider show their texts; an
  error without saved answers shows the error with the refresh button; a failed calendar shows the form and "–".
- [x] AC8: Offline, the saved answers are shown.
- [x] AC9: Placing the first widget enqueues the hourly work and the midnight work; removing the last one cancels them.
- [x] AC10: The widget refreshes when the app goes to the background, when `DataChanges` changes, when the session
  state changes, and on the refresh button.
- [x] AC11: A logout leaves no data of the account in the widget.
- [x] AC12: Texts, numbers and dates follow the app language (e.g. `TSB -8 · Produktywnie` in Polish).

## Open questions

- None (decisions 2026-11-24: the system's theme, today only, no actions; CTL / TSB labels in both languages).

## Tests

- `TodayRepositoryTest` (JVM, `TestBackend`): the three requests once each with their parameters, the backend's
  figures, the day of an old answer, the workout picked (more, rest, none, only today's), a failed calendar, logged out
  without requests and `401`, terms, no rider, errors, the saved answers offline (AC1, AC2, AC5, AC7, AC8, AC11).
- `TodayWidgetUpdatesTest`: the background, `DataChanges`, the session (not at start; login, logout, terms), the
  refresh, nothing while none is placed (AC10, AC11).
- `TodayWidgetScheduleTest` (Robolectric, `work-testing`): the hourly work with a network and the midnight work
  enqueued, kept and replaced, cancelled; the delay to midnight in the device's zone (AC9).
- `TodayWidgetContentTest` (Glance unit test on Robolectric): both layouts, statuses, rest / none / "–" / more, the
  five ranges' names, signs and colors, the taps' intents and their round trip, the states' texts, the progress
  indicator, Polish texts and numbers (AC2–AC4, AC6, AC7, AC12).
- Not covered automatically: the widget on a real launcher (placing, resizing, the taps opening the app) — a manual
  test in `BACKLOG.md`.
