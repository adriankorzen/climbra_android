# 012: Sorting and the sport filter of the training log

- **Status:** Implemented
- **Created:** 2026-11-18

## Problem

The web's training log is a table whose columns sort the rides and whose sport type filter narrows them (cyclingapp
spec 052). The app's training log (spec 003) lists the rides newest first only, and has no way to find, say, the
hardest rides of the period or only the indoor ones.

## Scope

- **Sort** the rides of the period by one of the backend's columns, ascending or descending.
- **Filter** them by sport type.
- Both go to `GET /api/riders/{id}/rides` (`sort`, `dir`, `type`), which already sorts and filters the whole period,
  pages included, and computes the totals of what it returns.

### Out of scope

- Several sort keys at once (the backend has one).
- Remembering the choice across app starts: it lasts while the app runs, as the period and the search (spec 003).

## Behaviour

- Under the period and search filters, a row with two controls:
  - **Sort** (icon `sort`): a menu of the backend's columns as the web names them: Date, Name, Type, Duration,
    Distance, Elevation, Avg power, NP, IF, TSS, Avg HR, Intervals (`date`, `name`, `type`, `duration`, `distance`,
    `elevation`, `avgPower`, `np`, `if`, `tss`, `avgHr`, `intervals`). Choosing the current column again flips the
    direction; another column starts with the backend's default direction (names and types ascending, the rest
    descending, as `RideListController`). The chip shows the column and an arrow for the direction.
  - **Sport**: All, or one of the sport types the period has: the backend's `types` of `RideListResponse` (taken
    before the type filter, so the menu keeps the others), named by `sportName` (spec 003). A filter chip with a menu;
    hidden while the period has fewer than two types and no filter is set. A filter whose type the new period no
    longer has stays shown, so it can be cleared.
- Default: Date, descending, all sports (the list as today); the controls show it.
- A change loads the list from page 0 again, like a period change (spec 003): the totals of the backend follow the
  filter, the paging continues in the chosen order.
- Pull to refresh, the search and the period keep the sort and the filter.
- "No rides match" (spec 003) also covers a filter that leaves nothing: "No rides of this type in this period."
- No ride at all (the import hint, spec 009) does not depend on the filter.

## Backend dependencies

Existing only: `GET /api/riders/{riderId}/rides?from&to&q&type&sort&dir&page&size` (cyclingapp spec 052), whose
`types` lists the sport types of the period. No backend change.

## Screens

- Training log: a `Row` of an `AssistChip` / `FilterChip` with a `DropdownMenu` each, under the existing filters.
- Icons: `sort`, `arrow_upward`, `arrow_downward` (new), existing `expand_more`.

## Data and storage

- `ActivityApi.rides` gains `type`, `sort` and `dir`; `RideListResponse` gains `types`; `ActivitiesUiState` gains `sort` (column + direction) and
  `sport`. Nothing stored on the device.

## Acceptance criteria

- [x] AC1: The default is Date descending and all sports, as the list is today.
- [x] AC2: Choosing a column sends `sort` with its default direction; choosing it again flips `dir`.
- [x] AC3: The sport menu lists the period's `types` by name; choosing one sends `type`, All sends none.
- [x] AC4: A change loads from page 0 with the backend's totals; more pages keep the order and the filter.
- [x] AC5: Search, period and pull to refresh keep the sort and the filter.
- [x] AC6: A filter that leaves nothing shows its own empty text.
- [x] AC7: New strings exist in English and Polish.

## Open questions

- None.
