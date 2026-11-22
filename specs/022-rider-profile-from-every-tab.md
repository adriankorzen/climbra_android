# 022: Creating the rider profile from every tab

- **Status:** Implemented
- **Created:** 2026-11-22

## Problem

A new account has no rider profile, and nothing works without one. The profile tab already creates it (spec 004: its
title "Create your profile", the rider form and "Create profile", `POST /api/riders`), but the dashboard, the training
log, the calendar and the coach say "Create it on climbra.app, then come back here." with "Open climbra.app", which
sends the rider to the web. A rider of the app does not find the form that is one tab away.

## Scope

- The "no rider profile" card of the dashboard, the training log, the calendar and the coach: its text says the
  profile is created in the app, and its button opens the profile tab, where the rider form is.
- The import screen's "Create your rider profile first" (spec 009) keeps its text; its button (it opened the profile already) says "Create profile".

### Out of scope

- A separate onboarding screen or wizard after the registration: the profile tab's form is the one place (as on the
  web, whose profile page creates the rider).
- Asking for the thresholds (FTP, LTHR) while creating: the profile asks for them after, as now.

## Behaviour

- Card: title "Create your rider profile" (unchanged), text "Your account has no rider profile yet. Create it with your
  name and weight to see your rides, plans and the coach.", button **Create profile** → the profile tab (its start
  screen, scrolled to the top, where the rider form is).
- After "Create profile" in the profile, the tabs load again (spec 004 already calls `DataChanges.changed()`), so going
  back to the dashboard shows it with the new rider.
- Offline (spec 018): the button still opens the profile (it sends nothing); the profile disables its own Create.

## Backend dependencies

None (`POST /api/riders` exists, spec 004).

## Screens

- `DashboardScreen`, `ActivitiesScreen`, `CalendarScreen`, `CoachListScreen`, `ImportScreen`: one shared
  `ui.common.NoRiderCard(onCreate)` replacing their own cards; the routes get `onOpenProfile` from `ShellNavigator`
  (`openProfile`, which the import screen already uses).

## Data and storage

None.

## Acceptance criteria

- [x] AC1: The no-rider card of the dashboard, the training log, the calendar, the coach and the import screen shows
  the new text and a "Create profile" button, and no longer opens the web.
- [x] AC2: The button opens the profile tab, which shows the rider form with "Create profile".
- [x] AC3: New strings exist in English and Polish.

## Open questions

- None.
