# 023: Step details on the workout chart

- **Status:** Implemented
- **Created:** 2026-11-22

## Problem

The planned workout's step chart (spec 005, also the coach's proposals, spec 006) shows the bars only. On the web a
bar's tooltip shows its time, % FTP and watts; in the app the rider has to find the step in the table below. Tapping a
bar should show what it is.

## Scope

- Tapping (or dragging along) the chart picks the step under the finger: it is highlighted and a label above the chart
  shows the web tooltip's content.
- Everywhere `ui.workout.WorkoutStepChart` is shown: the workout detail and the coach's training block / proposal.

### Out of scope

- Axes with % FTP and watts labels (the FTP line stays the one reference).
- Highlighting the step's row in the table.

## Behaviour

- The chart's steps are the blocks with their repetitions, as drawn. A tap picks the step whose time span holds the
  tap's x; dragging moves the pick; a tap on the picked step, or outside the chart, clears it.
- The picked bar keeps full opacity, the others are dimmed; a label above the chart: `0:10:00–0:15:00 · Work ·
  105–110% FTP · 284–297 W` — the time span (`Fmt.duration`), the step's kind, % FTP (one number when low = high) and
  the watts from the backend (`powerLowWatts` / `powerHighWatts`; left out when it has none, e.g. no FTP). The app
  computes nothing: the watts are the backend's (no figure computed here).
- Nothing picked: no label, the chart as now. The label's space is reserved, so the page does not jump.
- Accessibility: each step is also announced by TalkBack through the chart's content description listing the steps
  (kind, duration, % FTP, watts).

## Backend dependencies

None (`WorkoutStep` has the watts).

## Screens

- `ui.workout.WorkoutStepChart`: the pick state, `pointerInput` for taps and drags, the label (`Text` in `MonoFamily`
  for the figures), test tags `workout-chart`, `workout-chart-label`.

## Data and storage

None.

## Acceptance criteria

- [x] AC1: Tapping a bar shows its time span, kind, % FTP and watts above the chart; repetitions count as their own
  bars.
- [x] AC2: Dragging moves the pick; tapping the picked bar again clears it.
- [x] AC3: Without watts from the backend the label shows % FTP only; one value when low = high.
- [x] AC4: The chart's content description lists the steps.
- [x] AC5: No new strings: the label uses the step kinds and figures the table already has.

## Open questions

- None.
