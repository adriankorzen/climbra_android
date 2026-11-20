# 019: Navigation rail and two panes on large screens

- **Status:** Implemented
- **Created:** 2026-11-20

## Problem

On a tablet, a foldable opened or a phone in landscape, the app is a phone layout stretched wide. The bottom bar takes
height that a landscape screen lacks. Opening a ride from the training log, or a conversation from the coach's list,
hides the list, so going through several rides means going back and forth. The dashboard, the profile, the ride and
the calendar's month already use the width (≥ 600 dp, specs 002, 004, 003, 015); the navigation and the list screens
do not.

## Scope

- A **navigation rail** in place of the bottom bar on wide windows.
- **List and detail side by side** on expanded windows:
  - the training log: the rides on the left, the chosen ride on the right,
  - the coach: the conversations on the left, the chosen conversation on the right,
  - the calendar: the week or month on the left, the chosen workout or ride on the right.

### Out of scope

- The dashboard and the profile, which already have their own wide layouts.
- Screens before login.
- Three panes, or an extra pane such as a ride's intervals next to its chart.
- Drag and drop between panes, and resizing panes by dragging.

## Behaviour

- **Window classes** (Material 3, by the window's width):
  - compact < 600 dp: the bottom bar and one pane, as today,
  - medium 600–839 dp: the navigation rail and one pane,
  - expanded ≥ 840 dp: the rail, and two panes for the training log and the coach,
  - large ≥ 1200 dp: also two panes for the calendar (below that, its month grid needs the whole width, spec 015).
- **Rail**: the same five destinations, icons and labels as the bottom bar. A tap on the selected destination returns
  to its start, as on the bar (spec 001); with two panes it also empties the detail pane. The snackbar and the
  offline banner (spec 018) stay over the content.
- **Two panes**:
  - The training log's and the coach's list is 380 dp wide and the detail takes the rest. The calendar is the other
    way round: its week or month takes the width and the detail is 440 dp.
  - A tap in the list shows the item in the detail pane, highlights it in the list, and does not cover the list.
  - With nothing chosen, the detail pane shows a placeholder: "Choose a ride", "Choose a conversation" or "Choose a
    workout or ride".
  - The training log and the coach have no automatic choice. The calendar does not choose today's workout by itself
    either.
- **Changing size** (rotating, folding, resizing a window): the chosen item is kept.
  - Two panes → one pane: the detail shows as a screen of its own, and Back returns to the list.
  - One pane → two panes: the open detail joins its list.
- **Back** with two panes: it leaves the tab as Back does from its list today. Back clears the choice first only when
  the detail was opened as a screen of its own.
- **Opening from elsewhere**: a ride or a conversation opened from another tab keeps its
  route and back stack (specs 003, 006). It opens in the detail pane when its tab has two panes.
  - Example: a ride from the dashboard opens over the dashboard as today. A coach conversation from the dashboard
    opens on the Coach tab, in the detail pane next to the list.
- **Deeper screens**: the adjustment form, the training block form, a ride opened from a conversation, the import and
  the feedback still open as screens of their own over both panes.
- **Changes in the detail**: deleting a ride clears the choice and reloads the list (`DataChanges`). A reanalysed or
  linked ride reloads the list next to it, and a deleted conversation clears the choice.

## Backend dependencies

None.

## Screens

- `shell.Shell`: `NavigationSuiteScaffold` (`material3-adaptive-navigation-suite`) in place of `Scaffold` and
  `NavigationBar`. The bar or the rail is chosen from the window's width (`LocalConfiguration.screenWidthDp`), not
  the library's default, which keeps the bar on a phone in landscape.
- `shell.ShellPanes`: the start screens of the training log, the coach and the calendar are a `Row` of the existing
  list screen and the detail pane, with the existing `RideDetailRoute`, `ConversationRoute` and `WorkoutDetailRoute`
  or a placeholder. The tab's navigation decides the panes, so a list-detail scaffold of
  `material3-adaptive-layout` is not needed: one pane is the existing back stack.
- `ShellNavigator`: in a tab showing two panes, `openRide`, `openWorkout`, `openConversation`, `openProposal`,
  `replaceWithConversation` and `rideAdded` show the item in the pane. The pane's own navigator makes Back empty it.
- The detail screens lose their top bar's back arrow when shown next to the list (`ui.common.DetailBackButton`).
- Rows chosen in a list are highlighted (`secondaryContainer`) and marked selected for accessibility
  (`ui.common.LocalPaneItem`).
- No web mapping: the web shows a ride and a conversation on their own pages.

## Data and storage

- No DTO changes, nothing stored on the device. The chosen item (`ui.common.PaneItem`) is kept in the
  `SavedStateHandle` of the tab's start screen, so it is saved with the back stack.
- The detail pane's ViewModels have a store of their own per item (`ui.common.PaneViewModels`), with the item's
  arguments as a route would give them. The store is cleared when another item is shown or the pane is emptied.
- New dependency: `androidx.compose.material3:material3-adaptive-navigation-suite` (Compose BOM).

## Acceptance criteria

Tested with Robolectric in a `w1280dp-h800dp` window, telling the shell narrower widths (`AdaptiveShellTest`), and
on the list screens (`ActivitiesScreensTest`, `CoachScreensTest`, `CalendarScreensTest`).

- [x] AC1: Compact windows show the bottom bar. Medium and wider windows show the rail with the same destinations, and
  a tap on the selected destination returns to its start.
- [x] AC2: In an expanded window, the training log shows the rides and a placeholder. A tap on a ride shows it next
  to the list, highlighted.
- [x] AC3: In an expanded window, the coach shows its conversations and a chosen conversation side by side. A
  conversation opened from another tab opens there.
- [x] AC4: The calendar shows two panes only at ≥ 1200 dp. A workout or ride chosen there opens in the detail pane.
- [x] AC5: Below the two-pane widths, a tap opens the detail as a screen of its own and Back returns to the list, as
  today.
- [x] AC6: Resizing keeps the chosen item, both from two panes to one and back.
- [x] AC7: Deeper screens open over both panes. Deleting the shown ride or conversation clears the choice and reloads
  the list.
- [x] AC8: New strings exist in English and Polish.

## Open questions

- None.
