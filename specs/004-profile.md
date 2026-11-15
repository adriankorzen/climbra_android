# 004: Profile

- **Status:** Implemented
- **Created:** 2026-11-12

## Problem

The Profile tab is a placeholder with the email and logout (spec 001). Riders want what the web profile
(`../cyclingapp/frontend/src/app/profile`) offers: their rider data, the threshold history with its trend and zones,
the intervals.icu and Garmin Connect connections, language and theme, and the account (password, AI credits, legal
documents, deletion). An account without a rider cannot create one in the app yet (specs 002, 003 send it to the web).

## Scope

- **Header:** the rider's name and the current thresholds (FTP W and W/kg, LTHR, max HR), or "Create your profile"
  for an account without a rider.
- **Rider:** name, weight, resting HR; save, or create the rider (with the device's time zone).
- **Threshold history:** the entries newest first with W/kg and the "Current" badge; add, edit, delete; the number of
  re-analysed rides after a change. **FTP trend** chart. **Power and heart rate zones**.
- **intervals.icu:** connect with athlete id and API key, connection status, sync now (followed to its end),
  disconnect, the intervals.icu thresholds as a proposal for the history.
- **Garmin Connect** (only when the backend offers it): status, workouts on Garmin, disconnect; connecting on
  the web.
- **Preferences:** language (English / Polski, the account's), theme (system / light / dark, on the device).
- **Account:** email with the Google badge, AI credits, password change, terms and privacy, account deletion, logout.
- Pull to refresh.

### Out of scope

- Connecting Garmin inside the app: the backend keeps the OAuth state in the browser's session (spec 047), which a
  Custom Tab does not share with the app; the button opens the web profile instead. A native flow would need a
  backend spec.
- Sending workouts to Garmin (with the calendar spec), buying credits / subscriptions (on the web).
- Several riders per account: the app uses the account's rider, as the web.

## Behaviour

### Loading

- The rider comes from the rider context (spec 002). With a rider, in parallel: `GET /api/riders/{id}/thresholds`,
  `GET /api/riders/{id}/zones`, `GET /api/riders/{id}/intervals`, `GET /api/riders/{id}/garmin-connection` and
  `GET /api/account/coach-quota`; after `intervals` reports a connection also `GET /api/riders/{id}/intervals/thresholds`.
  Without a rider only the coach quota (account section).
- Each section has its own loading and error state ("Cannot load …" with Retry); one failing section does not hide
  the others. Pull to refresh loads everything again, the rider context included.
- The app computes nothing: W/kg of each entry (`ftpWattsPerKg`), the resolved FTP of the trend (`resolved.ftpWatts`)
  and the re-analysed count come from the backend (cyclingapp spec 053), zones from `/zones`.

### Rider

- Fields and rules as the web and the backend: name required, ≤ 100 chars; weight 20–250 kg, up to 2 decimals;
  resting HR 25–120 bpm, optional. Invalid input shows "Check the fields" and sends nothing.
- With a rider: `PUT /api/riders/{id}` → snackbar "Profile saved", the rider context reloads (header, dashboard).
- Without a rider: `POST /api/riders` with `timeZone` = the device's → snackbar "Profile created", the rider context
  reloads and the other tabs show their content; the profile then shows every section.

### Threshold history

- Rows: the valid-from date (with the "Current" badge for `rider.currentThresholds.id`), FTP W, W/kg, LTHR bpm,
  max HR bpm, "–" for missing. Empty: "No thresholds yet".
- "Add" and tapping a row open a form (modal bottom sheet): valid from (date picker, at most today, default today),
  FTP 30–2500 W, LTHR 60–230 bpm (with the web's "LTHR is optional" hint), max HR 80–240 bpm, the hint that empty
  fields keep their previous value. A row's menu has Edit and Delete; Delete asks "Delete the thresholds valid from
  {date}?".
- Create `POST`, edit `PUT`, delete `DELETE /api/riders/{id}/thresholds/{thresholdId}`. Success: the sheet closes,
  snackbar "Thresholds saved – re-analysed rides: {n}" / "Entry deleted – re-analysed rides: {n}" (`reanalysed`,
  plurals), then thresholds, zones and the rider reload and `DataChanges.changed()` is called (rides were
  re-analysed). A backend error (e.g. `409`, an entry on that date exists) stays in the sheet.

### FTP trend

- A step line of `resolved.ftpWatts` per entry, oldest first, extended to today with the last value; tooltip with
  date, W and the entry's W/kg. Hidden ("No thresholds yet") without an entry with FTP.

### Zones

- Power zones (`Zn`, translated name, `min–max %`, `min–max W`, `≥ min W` for the last) with the zone color, title
  with the FTP; heart rate zones likewise with the basis (`LTHR` / max HR) in the title and the percent "of LTHR" /
  "of max HR". Empty lists: "Add an FTP to see power zones" / "Add LTHR or max HR to see heart rate zones".

### intervals.icu

- Not connected: intro, how-to hint, athlete id (optional, e.g. `i12345`), API key (password field, required),
  Connect → `PUT /api/riders/{id}/intervals`. `422` → "intervals.icu rejected the key", `503` → "Not available on this
  server"; other errors as usual. Success: snackbar "Connected to intervals.icu ({name})", fields cleared.
- Connected: athlete name and id, `••••{keyHint}`, last sync (date-time or "Never"); the note which sources are
  synced. "Sync now" → `POST /api/riders/{id}/intervals/sync` returns an import job; the app polls
  `GET /api/imports/{jobId}` every 2 s while it is `QUEUED` / `RUNNING` (the button shows progress and is disabled),
  then shows "Sync finished – imported {n}" or the failure, reloads the status and calls `DataChanges.changed()`.
  Since spec 007 this is the app's one sync (`intervals.IntervalsSync`), shared with the pull to refresh of the
  dashboard and the training log: polled in the app scope, stopped on logout, its messages in the shell's snackbar. "Disconnect" asks first, then `DELETE`.
- Thresholds proposal: a table intervals.icu vs current (FTP with indoor FTP in brackets, LTHR, max HR); when
  `differsFromCurrent` an "Add to history" button opens the threshold form prefilled with these values and today,
  saved by the rider; otherwise "Same as your current thresholds". A failed request shows "Cannot load the thresholds
  from intervals.icu".

### Garmin Connect

- Hidden when `available` is false. `CONNECTED`: connected at, workouts on Garmin (`sent`), the out-of-date hint when
  `stale > 0`, "Disconnect" (asks first, `DELETE /api/riders/{id}/garmin-connection`). Other statuses: the status
  message (as the web's `garmin.status.*`) and "Connect on climbra.app", opening `{webBaseUrl}/profile` in a Custom
  Tab. The status reloads when the app returns to the foreground on the profile.

### Preferences

- Language: English / Polski. A change → `PUT /api/account/language`, then the session's account and the app locale
  (`AppLocales`) switch to it; a failure keeps the old language and shows the error.
- Theme: system (default) / light / dark, stored in DataStore on the device (as the web stores it in the browser) and
  applied by `ClimbraTheme` at once, the login screen included. The system choice is labelled "System" /
  "Systemowy" (the web's "Like the system" does not fit a phone's segmented button).
- The dashboard loads again when shown after a change in the profile (`DataChanges`: a new rider, new thresholds, a
  sync), as the training log does.

### Account

- Email and the "Google linked" badge (`AccountResponse`).
- AI credits (coach quota, details mode of the web): plan (and subscription status with its date), monthly credits
  remaining of limit and the reset date, each bonus grant with its expiry, the balance — or "Unlimited".
- Change password (when `hasPassword`): current, new, repeat; rules as the web and the backend (≥ 10 code points,
  ≤ 72 UTF-8 bytes, repeat equal, not the email). `PUT /api/account/password` → snackbar "Password changed", fields
  cleared (other sessions end on the backend). Without a password: the web's note and "Forgot password?" (Custom Tab).
- Terms and privacy open in a Custom Tab in the app language (as spec 001).
- Delete account: admins (`role` `ADMIN`) see the web's note instead. Others get a dialog with the warning and the
  password (or, without a password, the email typed again); `DELETE /api/account` → the session ends locally and the
  login screen shows "Account deleted". A wrong password shows the backend's message in the dialog.
- Logout as now (spec 001).

## Backend dependencies

- Existing: `GET/POST/PUT /api/riders[/{id}]`, `GET /api/riders/{id}/zones`, `GET/PUT/DELETE /api/riders/{id}/intervals`,
  `POST /api/riders/{id}/intervals/sync`, `GET /api/riders/{id}/intervals/thresholds`, `GET /api/imports/{id}`,
  `GET/DELETE /api/riders/{id}/garmin-connection`, `GET /api/account/coach-quota`,
  `PUT /api/account/password`, `PUT /api/account/language`, `DELETE /api/account`.
- **New, cyclingapp spec 053:** `ThresholdResponse.ftpWattsPerKg` and `resolved`, `ThresholdChangeResponse` with
  `reanalysed` from create / update / delete of `/api/riders/{id}/thresholds`. The threshold section needs a backend
  with spec 053 deployed.

## Screens

- Profile tab: top app bar "Profile" (or "Create your profile"); a scrolling column of `ElevatedCard` sections in the
  web's order: rider, preferences, threshold history, FTP trend, zones, intervals.icu, Garmin Connect, account, logout.
  Wider screens (≥ 600 dp) put pairs side by side (rider | preferences, history | trend, power | HR zones).
- Forms: `OutlinedTextField` (number keyboards, password fields with show/hide), `DatePickerDialog`,
  `ModalBottomSheet` for the threshold form, `AlertDialog` for confirmations and deletion, `SnackbarHost` for results,
  `SingleChoiceSegmentedButtonRow` for theme and language, `AssistChip`-like badges ("Current", "Google linked").
- FTP trend with Vico (as spec 002), color `extendedColors.power`; zone dots from `extendedColors` power / HR zones.
- Icons (Material Symbols Rounded): `add`, `edit`, `delete`, `more_vert`, `sync`, `link_off`, `open_in_new`,
  `visibility` / `visibility_off`, `logout`.
- Strings: the web's `profile.*`, `intervals.*`, `garmin.section.*`, `garmin.status.*`, `coachQuota.*` (details),
  `auth.account.*`, `auth.delete.*`, `languages.*`, `themes.*`, `zones.*` keys, English and Polish; counts as plurals.

## Data and storage

- DTOs (`data/api/`): `Rider` (full), `RiderRequest`, `ThresholdEntry` (+ `ftpWattsPerKg`, `resolved`),
  `ThresholdRequest`, `ThresholdChangeResponse`, `Zones`, `TrainingZone`, `IntervalsConnection`,
  `IntervalsConnectRequest`, `IntervalsThresholds`, `ImportJob` (status and counts), `GarminConnection`, `CoachQuota`
  (+ monthly, bonus, subscription), `PasswordChangeRequest`, `LanguageRequest`, `DeleteAccountRequest`.
- DataStore: the theme mode. The API key is sent once and never stored; nothing else is stored.

## Acceptance criteria

- [x] AC1: With a rider the profile loads thresholds, zones, intervals, Garmin and the coach quota once each and
      shows the header with the current thresholds; a failing section shows its error with Retry while the others
      show their data; pull to refresh loads everything again.
- [x] AC2: Saving the rider sends `PUT` with the fields and reloads the rider; invalid fields send nothing.
- [x] AC3: An account without a rider sees "Create your profile"; creating sends `POST` with the device time zone and
      the profile then shows every section.
- [x] AC4: The history lists entries newest first with the backend's W/kg, "–" for missing values and the "Current"
      badge; empty shows "No thresholds yet".
- [x] AC5: Adding, editing and deleting an entry send `POST` / `PUT` / `DELETE` with the form's values, show the
      `reanalysed` count, reload thresholds, zones and rider and call `DataChanges.changed()`; out-of-range values send
      nothing; a backend error stays in the form; deleting asks first.
- [x] AC6: The FTP trend plots `resolved.ftpWatts` of the entries; zones show the returned ranges with their names,
      the HR basis and the empty hints.
- [x] AC7: intervals.icu connects with the entered id and key (`422` and `503` with their messages), shows the
      status, syncs and polls the job until it finishes (then reloads and calls `DataChanges.changed()`), and
      disconnects after a confirmation.
- [x] AC8: The intervals.icu proposal shows both columns; "Add to history" opens the threshold form prefilled with
      today and the proposed values; without differences it shows "Same as your current thresholds".
- [x] AC9: Garmin is hidden when not available; connected shows the status and disconnects after a confirmation;
      not connected opens the web profile.
- [x] AC10: Changing the language sends `PUT /api/account/language` and switches the app locale; a failure keeps the
      old language.
- [x] AC11: The theme choice is stored on the device and applied at once and after a restart.
- [x] AC12: The account section shows the email, the Google badge and the AI credits (plan, monthly, bonus, balance or
      unlimited).
- [x] AC13: Password change validates like the backend, sends `PUT /api/account/password` and clears the fields;
      without a password the note and the reset link are shown.
- [x] AC14: Deleting the account asks for the password (or the email without one), sends `DELETE /api/account` and
      ends the session; admins see the note instead.
- [x] AC15: All texts exist in English and Polish; numbers and dates follow the app language.

## Open questions

- None (decisions 2026-11-12: every web profile section in scope; the figures the web computes move to the backend,
  cyclingapp spec 053; Garmin connects on the web).

## Tests

- `ProfileViewModelTest` (real HTTP stack on `TestBackend`): one request per section, a failing section with Retry,
  pull to refresh (AC1); rider save and its rules (AC2); creating the rider with the time zone (AC3); add, edit,
  delete with confirmation, ranges and a future day, `409` in the sheet, the `reanalysed` count and the reloads (AC5);
  intervals.icu connect with `422` / `503`, sync polled every 2 s until the job ends, a failed sync, disconnect (AC7);
  the proposal prefill (AC8); Garmin disconnect and the reload on resume (AC9); language (AC10), theme (AC11),
  password rules and errors (AC13), account deletion with the password or the email (AC14).
- `ProfileScreenTest` (Compose on Robolectric): header, section errors, rider form, the no-rider form, history rows
  with the backend's W/kg and the current badge, sheet, confirmation, zones, intervals.icu states, the proposal,
  Garmin states, language and theme, the account with credits, password errors, delete dialogs, the admin note, logout
  (AC1–AC14). `ProfileFormsTest`: form rules, password rules counted like the backend, the FTP trend steps (AC2, AC5,
  AC6, AC13). `SessionRepositoryTest`: language, password, account deletion (AC10, AC13, AC14). `ThemeSettingsTest`:
  the stored choice (AC11). `FmtTest`: instants in the device's zone; `StringResourcesTest`: the same keys in both
  languages (AC15). `DashboardViewModelTest`: the reload after a change elsewhere.
- Checked by hand on the API 37 emulator against the local backend with spec 053: the Polish profile, zones, adding an
  FTP entry (W/kg and the count from the backend, the step chart) and deleting it after the confirmation. Garmin was
  not offered by the local server; intervals.icu was not connected.
