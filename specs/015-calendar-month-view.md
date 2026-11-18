# 015: The calendar's month view

- **Status:** Draft
- **Created:** 2026-11-18

## Problem

The web calendar shows a month as a grid of weeks (desktop and tablet) and a week on phones (`calendar-grid`). The
app shows one week at a time only (spec 005). On a tablet, a foldable or a phone in landscape a week list leaves most
of the screen empty, and the rider cannot see the shape of a block (its build and recovery weeks) at a glance.

## Scope

- A **Week / Month** switch in the calendar's top bar.
- **Month**: the web's grid of whole weeks (Monday to Sunday) covering the month, days outside the month faded, each
  day with its planned workouts and rides as compact tiles; each week row ends with the backend's week totals and
  plan week (cyclingapp spec 054), as the week view's summary.
- Default: Month on wide windows (width ≥ 600 dp, the app's wide layout as the dashboard's and the profile's), Week on
  phones; the rider's choice holds while the app runs.

### Out of scope

- Moving workouts between days (spec 005 leaves editing out).
- A year view.

## Behaviour

- **Navigation** in Month: arrows and a swipe change the month, "Today" returns to the current month, the date
  picker chooses a month; the title is "November 2026".
- **Loading**: `GET /api/riders/{id}/calendar?from&to` for the month's whole weeks (up to 6 weeks), the web's
  `monthRange`; the backend returns the weeks' totals with it. Nothing is computed on the device.
- **Day cell**: the day number (today marked), up to 3 tiles (workout type color and short title, or a ride with its
  TSS), "+{n} more" when there are more. A tap on a tile opens the workout or the ride as in the week view (spec 005,
  spec 003); a tap on the day opens the week view at that week.
- **Week summary** at the end of each row (wide windows) or under it: planned / ridden TSS and time, plan week and
  focus, from the backend.
- The plan cards, the suggestion and proposal banners (specs 005, 008) stay above the grid.
- Switching Week ↔ Month keeps the shown date: the week of the month's first shown day, or the month of the week.
- Pull to refresh and the reload after data changes work as in the week view.

## Backend dependencies

Existing only: `GET /api/riders/{riderId}/calendar?from&to` with `weeks` (cyclingapp specs 009, 054). No backend
change.

## Screens

- `calendar.CalendarScreen`: `SegmentedButton` Week / Month in the top bar, a `MonthGrid` (7 columns, `LazyColumn` of
  week rows), the existing tiles in a compact form.
- Web mapping: `calendar-grid` (month mode), `monthRange`, `shiftMonth`.

## Data and storage

- `CalendarUiState` gains `mode` and `month`; `CalendarViewModel` loads the month's range. Nothing stored on the
  device.

## Acceptance criteria

- [ ] AC1: Wide windows open in Month, phones in Week; the switch changes the view and the choice holds.
- [ ] AC2: Month loads the whole weeks covering it and shows each day's workouts and rides, days outside faded,
  today marked, "+{n} more" over 3 tiles.
- [ ] AC3: Each week row shows the backend's totals and plan week.
- [ ] AC4: Arrows, swipe, Today and the picker change the month; switching views keeps the date.
- [ ] AC5: A tile opens its workout or ride; a day opens the week view at its week.
- [ ] AC6: New strings exist in English and Polish.

## Open questions

- None.
