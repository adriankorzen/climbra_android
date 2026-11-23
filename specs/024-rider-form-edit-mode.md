# 024: Editing the rider data on demand

- **Status:** Implemented
- **Created:** 2026-11-23

## Problem

The profile's Rider card (spec 004) is always an open form: name, weight and resting HR are text fields and "Save" is
always enabled, whether anything changed or not. A rider cannot tell at a glance whether the values are saved, and a
"Save" next to untouched values reads as something left to do. An explicit edit mode ("Edit" → fields with "Cancel"
and "Save") makes the card read as data, and editing as a deliberate step.

## Scope

- The Rider card of the profile tab of a rider who has a profile: a read-only view with an "Edit" action, and an
  edit mode with "Cancel" and "Save".
- The same change on the web: `../cyclingapp/specs/058-rider-form-edit-mode.md`, so both look and behave alike.

### Out of scope

- Creating the profile (no rider yet): the card stays the open form with "Create profile" (spec 004, 022), since
  there is nothing to view or cancel.
- The threshold history (FTP, LTHR, max HR): its entries are already edited in a dialog with its own buttons.
- Preferences (language, theme), which apply on selection, and the other sections.
- Disabling "Save" until a field changes.

## Behaviour

- **View mode** (default with a rider): the card shows name, weight (kg) and resting HR (bpm, "–" when missing) as
  label + value, formatted through `Fmt`, and an "Edit" button (icon `ic_edit`) in the card's header.
- **Edit:** the card shows the current fields (as today) filled with the rider's values, and "Cancel" and "Save"
  below them. "Edit" is hidden while editing.
- **Cancel:** drops the changes and the "Check the fields" error, back to view mode with the rider's values. The
  system back gesture while editing does the same (instead of leaving the screen).
- **Save:** the rules of spec 004 (invalid input shows "Check the fields", sends nothing, stays in edit mode).
  `PUT /api/riders/{id}` → snackbar "Profile saved", the rider context reloads, back to view mode with the saved values.
  A failure shows the error in the snackbar and stays in edit mode with the typed values. While saving both buttons
  are disabled.
- **Reload while editing** (pull to refresh, data changes from another screen): the rider's new values update the view
  but not the fields being edited; the edit mode stays.
- **Edit mode survives** switching tabs and configuration changes (it lives in the ViewModel's state).
- **Offline** (spec 018): "Edit" is disabled with `LocalOffline` (saving sends a request); if the connection drops
  while editing, "Save" is disabled and the card shows `OfflineNote`, "Cancel" stays.
- Without a rider: unchanged (open form, "Create profile"); after a successful create the card shows the view mode.

## Backend dependencies

None (`PUT /api/riders/{id}`, `POST /api/riders` as in spec 004).

## Screens

- `profile.RiderCard` (`ProfileSections.kt`): view / edit by `RiderForm.editing`; header `TextButton`
  "Edit" (`ic_edit`), `OutlinedButton` "Cancel" + `Button` "Save" in a row; `BackHandler(enabled = editing)`.
- Test tags: `rider-edit`, `rider-cancel`, `rider-save` (as today), view values `rider-view-name`,
  `rider-view-weight`, `rider-view-resting-hr`.
- Strings: the existing `edit`, `cancel`, `save`.

## Data and storage

`RiderForm.editing: Boolean` in the ViewModel's state; `ProfileViewModel.editRider()`, `cancelRider()`. Nothing stored.

## Acceptance criteria

- [x] AC1: With a rider the card shows name, weight and resting HR as text with "Edit", and no text fields or "Save".
- [x] AC2: "Edit" shows the fields filled with the rider's values and "Cancel" and "Save"; "Edit" is hidden.
- [x] AC3: "Cancel" (and system back while editing) drops typed changes and the field error and shows the view with
  the rider's values; nothing is sent.
- [x] AC4: A successful "Save" sends `PUT /api/riders/{id}`, shows "Profile saved" and returns to the view with the
  saved values.
- [x] AC5: Invalid input or a failed save stays in edit mode with the typed values.
- [x] AC6: A reload while editing keeps the typed values and the edit mode.
- [x] AC7: Offline "Edit" is disabled; while editing offline "Save" is disabled and "Cancel" works.
- [x] AC8: Without a rider the card is the open form with "Create profile" and no "Cancel"; after creating, the view.

## Open questions

- None.
