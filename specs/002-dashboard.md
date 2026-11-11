# 002: Dashboard

- **Status:** Implemented
- **Created:** 2026-11-11

## Problem

After login the Dashboard tab is a placeholder. Riders want the same overview as on the web dashboard
(`../cyclingapp/frontend/src/app/dashboard`): training load of the last days, fitness / fatigue / form, the performance
management chart, weekly load and the latest rides.

## Scope

- Rider context: the account's rider from `GET /api/riders` (first entry, as `rider/rider-context.ts`), kept in
  memory per account; the rider's time zone follows the device's (`PUT /api/riders/{id}/time-zone` when it differs,
  failures ignored — cyclingapp spec 049, like the web).
- KPI tiles: TSS 7 d, time 7 d, distance 7 d, rides 7 d (each with the change against the 7 days before), FTP (W and
  W/kg), CTL fitness, ATL fatigue (with the change against a week ago and a 42-day sparkline), TSB form with its range
  (transition / fresh / neutral / productive / high risk) and color.
- Performance management chart: CTL (area), ATL, TSB (dashed) lines and daily TSS bars on a second axis; ranges
  42 / 90 / 180 / 365 days, default 90.
- Weekly load chart: TSS bars and hours line for the last 12 ISO weeks.
- Recent rides: the 7 latest analysed rides (date with weekday, name, duration, NP, IF, TSS with a heart-rate marker
  when the TSS is hrTSS).
- Pull to refresh; loading, error and empty states.

### Out of scope

- The coach section of the web dashboard (with the coach spec).
- Opening a ride (spec 003 adds the navigation from the recent rides).
- Ride import / Garmin sync: the empty state points to the web app.
- Creating a rider profile (profile spec): an account without rider sees a hint to create it on the web.

## Behaviour

- The backend computes everything (cyclingapp spec 051); the app only formats and draws. Two requests, in parallel:
  `GET /api/riders/{id}/training-load?days=365&weeks=12` (tiles and both charts) and
  `GET /api/riders/{id}/analyses?limit=7` (recent rides).
- "Today" (header date, chart ends) is the response's `today`, the rider's day on the backend.
- The PMC ranges are tails of the 365 returned days: switching makes no request. The sparklines of CTL / ATL are the
  last 42 days. The FTP tile reads the rider (`currentThresholds.ftpWatts`, `ftpWattsPerKg`).
- The TSB tile shows a `+` for positive values and the form range's name and color (`TRANSITION` z4, `FRESH`
  success, `NEUTRAL` faint text, `OPTIMAL` info, `OVERLOAD` error — as the web).
- No recent rides: `GET /api/riders/{id}/activities` tells "no rides at all" (empty state with a hint to import on
  climbra.app, link in a Custom Tab) from "no analysed rides in the window" (the list shows "No analysed rides yet").
- Errors: a failed training-load request shows "Cannot load the analyses." with Retry; the tiles show "–".
- The data reloads on pull to refresh and when the tab is opened again after more than 5 minutes.

## Backend dependencies

- Existing: `GET /api/riders`, `PUT /api/riders/{id}/time-zone`, `GET /api/riders/{id}/activities`.
- **New, cyclingapp spec 051:** `GET /api/riders/{id}/training-load` and the `limit` parameter of
  `GET /api/riders/{id}/analyses`. The dashboard needs a backend with spec 051 deployed.

## Screens

- Dashboard tab: top app bar "Dashboard" with the long date; a scrolling column: KPI tiles in a 2-column grid
  (`ElevatedCard`, value in `MonoFamily`, change as ▲/▼ percent colored success / error, sparkline), PMC card with a
  `SingleChoiceSegmentedButtonRow` for the range, weekly card, recent rides as a list (`ListItem`) instead of the web
  table. Wider screens (≥ 600 dp) use 4 tile columns and the charts side by side.
- Charts with Vico (latest stable `compose-m3`), colors from `MaterialTheme.extendedColors` (CTL `speed`, ATL
  `heartRate`, TSB `powerZones[3]`, TSS bars `altitude` 55 %, weekly bars `primary`), a marker with the values on touch.
- Number / date formatting through a new `ui.format.Fmt` mirroring `shared/fmt.ts` + `shared/format.ts`
  (`num`, `km`, `duration` h:mm:ss, `hm` h:mm, percent change, dates `long` / `weekday` / `dayMonth`, "–" for
  missing) in the app language.
- Strings: the web's `dashboard.*`, `columns.*`, `import.empty*`, `hrTss.tooltip` keys, in English and Polish.

## Data and storage

- DTOs: `Rider` (+ `ThresholdEntry.ftpWatts`), `TrainingLoadResponse` and its parts, `AnalysisSummary`, `Activity`
  (only what the empty state needs), `TimeZoneRequest`. Nothing is stored on the device.

## Acceptance criteria

- [x] AC1: The dashboard requests `training-load?days=365&weeks=12` and `analyses?limit=7` once each and computes no
      load figure itself: tiles show the response's periods, changes and fitness.
- [x] AC2: The TSB tile shows the sign, the form range's name and its color for each of the five ranges.
- [x] AC3: The PMC range buttons switch between the last 42 / 90 / 180 / 365 returned days without a new request.
- [x] AC4: The weekly chart shows the returned weeks (TSS and hours); the FTP tile shows watts and W/kg, "–" without
      thresholds.
- [x] AC5: Recent rides show the returned rides newest first, with the heart-rate marker for hrTSS.
- [x] AC6: A rider without any ride sees the import hint; one with rides but none recent sees "No analysed rides".
- [x] AC7: A failed request shows the error with Retry; Retry and pull to refresh load again.
- [x] AC8: The rider's time zone is sent when it differs from the device's, not otherwise.
- [x] AC9: An account without rider sees the hint to create the profile on the web.
- [x] AC10: Number, percent and date formatting follows the app language (e.g. `1 234,5` in Polish).

## Open questions

- None (decisions 2026-11-11: the backend computes the load — cyclingapp spec 051, no coach section, rides not
  clickable yet, Vico).

## Tests

- `DashboardViewModelTest`: the requests (one each, `days=365`, `weeks=12`, `limit=7`, nothing else), ranges as tails
  without requests, recent rides newest first, the empty states, errors and Retry, the 5-minute reload, the time zone
  (sent only when it differs, before the figures; a failure is ignored), an account without rider (AC1–AC9).
- `DashboardScreenTest` (Compose on Robolectric): tiles, form range names, range buttons, charts, rides with the hrTSS
  marker, hints, error with Retry, Polish formatting (AC1–AC7, AC9, AC10). `FmtTest`: numbers, percent, durations and
  dates in both languages (AC10). Numbers round halves away from zero, as `Intl.NumberFormat` on the web.
- Checked by hand on the API 37 emulator against the local backend with spec 051 and 35 uploaded TCX rides: Polish
  tiles and charts, 90 and 365 days, the light and the dark theme.
