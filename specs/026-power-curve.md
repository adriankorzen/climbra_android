# 026: Power curve

- **Status:** Implemented
- **Created:** 2026-11-23

## Problem

The app shows rides one at a time and the training load over time, but not the rider's best powers over durations
and whether they improve. The backend now serves the power curve of a period with the previous one (cyclingapp
spec 061); the app shows it on a screen of its own, as the web's page.

## Scope

- A screen "Power curve": the period's curve and the previous period's, a table of the key durations, links to
  the rides.
- Entries: a card on the dashboard and an action in the training log's top bar.
- Offline: the last answer per period.

### Out of scope

- A ride's own curve on the ride screen, FTP estimates, power profile categories (backend spec 061, out of scope).
- A tab of its own in the navigation bar (five tabs stay).

## Behaviour

- **Period:** a `SingleChoiceSegmentedButtonRow` 28 d / 90 d / 1 y / All, 90 d first; kept on rotation
  (`SavedStateHandle`). Changing it loads `GET /api/riders/{id}/power-curve?days=…`.
- **Chart (a `Canvas`, as the workout chart of spec 023, since Vico's marker would show the raw `log10`):** x is the duration on a logarithmic scale (the app maps `log10(seconds)`, a display transform, not
  a training figure), labels 5 s, 1 min, 5 min, 20 min, 1 h, 5 h; y watts. The current period is a solid line in the
  power color, the previous a dashed line in `onSurfaceVariant`. A tap or drag picks the nearest duration: a line above the chart
  with the duration, watts, W/kg, date and the previous period's watts. `All` has one line.
- **Table:** 5 s, 1 min, 5 min, 20 min, 60 min: watts, W/kg, the change against the previous period (`changeWatts`
  from the backend, shown with a sign, as the web), the ride's date; a tap opens the ride on the
  current tab's stack (`ShellNavigator.openRide`). A duration without a ride shows "–".
- **History note:** `ridesWithoutCurve > 0` → "N rides are still being analysed for the power curve" (plurals).
- **Empty:** no points → "No rides with power in this period."
- Loading, errors with Retry, pull to refresh reloads (no sync), as the other screens.
- **Offline:** the `GET` is in `OfflineCache.isSaved`; a saved answer is shown with the offline banner, none →
  the offline error.
- **Dashboard card:** "Power curve" with the 90 days' 5 s, 1 min, 5 min and 20 min bests (from the same endpoint) and
  a "Show" button that opens the screen; hidden while the rider has no rides with power.
- **Training log:** an action (icon `ic_show_chart`, added from Material Symbols Rounded) in the top bar opens the screen.

## Backend dependencies

- cyclingapp spec 061: `GET /api/riders/{id}/power-curve?days=28|90|365|all` → `PowerCurveResponse`.

## Screens

- `powercurve.PowerCurveRoute` / `PowerCurveScreen` (web: `power-curve/power-curve-page`), routes on the dashboard's
  and the training log's graphs (opened from either tab, back returns there).
- Material 3: `SingleChoiceSegmentedButtonRow`, `Card`, `ListItem`; a `Canvas` with two paths (the curve and the dashed previous one).
- Large screens: one pane, the chart and the table side by side from 840 dp.

## Data and storage

- `data/api/PowerCurveApi.kt`: `PowerCurveResponse(days, current, previous, ridesWithoutCurve)`,
  `PowerCurvePeriod(from, to, points)`, `PowerCurvePoint(durationSeconds, watts, wattsPerKg, activityId, date, changeWatts)`.
- `Fmt.duration` for the labels (5 s, 1 min, 1 h), strings in `values` and `values-pl`.
- Offline: the endpoint added to `OfflineCache.isSaved`.

## Acceptance criteria

- [x] AC1: The screen loads 90 days first and shows both lines; switching the period loads it, `All` shows one line.
- [x] AC2: Picking a point shows its duration, watts, W/kg, date and the previous watts.
- [x] AC3: The table shows the key durations with the change and the date; a tap opens the ride; a missing
  duration shows "–".
- [x] AC4: `ridesWithoutCurve > 0` shows the note with the count; no points shows the empty text.
- [x] AC5: Errors show Retry; offline a saved answer is shown.
- [x] AC6: The dashboard card shows the four bests and opens the screen; it is hidden without rides with power.
- [x] AC7: The training log's action opens the screen, back returns to the log.
- [x] AC8: The period survives a configuration change.

## Open questions

- None (own screen, previous period, decided 2026-11-23).
