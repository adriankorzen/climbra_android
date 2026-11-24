# 028: Sharing a ride or a week

- **Status:** Implemented
- **Created:** 2026-11-24

## Problem

A rider wants to send a ride or a training week to friends or to a human coach without a Climbra account. Today the
only way is a screenshot of a screen made for the rider. The app should make a tidy summary **image** (for chats and
social apps) and a **public link** (a read-only page on `climbra.app`, valid for 15 days, cyclingapp spec 062), and
send either through Android's share sheet.

## Scope

- "Share" on the ride detail and on the calendar's week (week view): a bottom sheet with "Hide power in watts",
  "Hide heart rate" and two actions, "Share image" and "Share link".
- The image: a summary card drawn on the device from the data the screen already has, as a PNG through the share sheet.
- The link: created by the backend, its URL sent through the share sheet as text.
- Profile → "Shared links": the active links with "Revoke".

### Out of scope

- Opening a shared link in the app: `/s/…` is not one of the App Links (spec 021), it opens in the browser for
  everyone, the rider too.
- Sharing other periods (month, plan, power curve), choosing the image's layout or colors, saving it to the gallery
  (the share sheet offers that through other apps).
- An image made by the backend (and link previews in messengers, cyclingapp 062 out of scope).

## Behaviour

### The share sheet (bottom sheet)

- Ride detail: an action "Share" (`ic_share`) in the top app bar, shown once the ride and its analysis are loaded.
- Calendar, week view: a "Share week" icon (`ic_share`) in the calendar's header row, next to "Today" (the calendar
  has no top app bar), once the week is loaded. Not in the month view.
- The sheet: the title ("Share ride" / "Share week"), two switches (off by default; "Hide power in watts" is hidden
  when the ride has no power, "Hide heart rate" when it has no HR), and two buttons. The switches apply to both actions.

### Image

- Drawn off screen with Compose into a 1080 × 1350 px bitmap (portrait 4:5, fits chats and Instagram), always in the
  light theme, in the app's language: the Climbra logo and `climbra.app`, the title (ride name or "Week 12–18 Nov"),
  the date, a grid of figures and a chart.
  - Ride: duration, distance, elevation, TSS, IF, NP, average power, average HR (hidden figures and missing values
    are left out; the grid closes up). Chart: the ride's recording as on the ride detail (power, else speed; HR as a
    second line), from the samples already loaded — drawn only, no figure is computed from them.
  - Week: ridden TSS and time against planned (`CalendarWeek`), the number of rides; a bar per day with each ride of
    that day stacked by its `trainingLoad`, scaled to the week's `riddenTss` from the backend (the rides' own figures,
    the stack is drawn, no total is computed on the device), then the list of rides (name, duration, TSS, at most 7 lines, "+ N more" as a plural).
  - "Hide power" leaves out NP, average power and the power line (the chart falls back to speed); "Hide heart rate"
    the HR figure and line.
- Saved as `cache/shares/<ride-or-week>.png` (overwriting the previous one; the folder is emptied at start), shared
  with `ACTION_SEND` `image/png` through a `FileProvider` with read permission, chooser title "Share image".
- Works offline (the data is on the screen); a failure to draw or save shows a toast. The card is laid out at 360 ×
  450 dp with a density of 3 inside `share.CardCapture`, recorded into a `GraphicsLayer` without being shown and
  read as a bitmap once drawn (at most 30 frames, else the failure).

### Link

- `POST /api/riders/{riderId}/shares` with the kind, the ride / week and the switches; while waiting the button shows
  progress. Success → `ACTION_SEND` `text/plain` with "<title> – <url>" (and `EXTRA_SUBJECT` the title), chooser
  "Share link"; below the buttons the sheet says "The link is valid until 9 Dec 2026". Errors through `toUiText`
  (`SHARE_LIMIT_REACHED` is translated: "You have 50 active links. Revoke one in Profile → Shared links.").
- Offline: the button is disabled with `OfflineNote` (`LocalOffline`).
- The URL is not stored on the device (the backend answers it only once).

### Shared links (profile)

- A row "Shared links" in the profile opens a list screen: each active link with what it shows (ride name and date /
  "Week 12–18 Nov"), the hidden data ("Power hidden", "HR hidden"), "Expires 9 Dec"; "Revoke" with a confirmation
  dialog → `DELETE`, the row disappears. Empty: "No active links".
- Read offline from the saved answer (its `GET` added to `OfflineCache.isSaved`), "Revoke" disabled offline.
- The list loads again when shown after a link was created (`DataChanges` is not used: links are not rides; the
  screen loads on each show).

## Backend dependencies

- cyclingapp **062** (public share links): `POST`, `GET`, `DELETE /api/riders/{riderId}/shares`.
- Image data: existing `GET /api/activities/{id}`, `/analysis`, `/recording` and `GET /api/riders/{id}/calendar`
  (`weeks`, spec 054 there).

## Screens

- `activities.RideDetailScreen`: top bar action; `calendar.CalendarScreen` (week view): header icon. Both get the
  sheet from `share.ShareViewModel` (a second ViewModel of the route, like the coach's `AskCoachViewModel`).
- `share.ShareSheet` (`ModalBottomSheet`, `Switch`, `Button` / `OutlinedButton`), `share.ShareCard` (the image's
  composable, ride and week variants: `RideShareCard` / `WeekShareCard` from `RideCard` / `WeekCard`), `share.CardCapture`
  (draws it into a bitmap), `share.ShareFiles` (the PNG, the `FileProvider` URI and both intents).
- `share.SharedLinksScreen` (`…Route` / `…Screen`, `ListItem`, `AlertDialog`) on the Profile tab's graph.
- Icons: `ic_share`, `ic_link`, `ic_image` (Material Symbols Rounded). The profile's account card gets a "Shared
  links" button (`ic_link`) above "Feedback".
- Mirrors the web's share dialog and the profile's "Shared links" (cyclingapp 062); the web has no image.

## Data and storage

- DTOs in `data/api/ShareApi.kt`: `ShareRequest`, `ShareResponse`, `ShareCreatedResponse` (mirroring 062).
- PNGs in `cache/shares/` only; `res/xml/file_paths.xml` for the `FileProvider` (`${applicationId}.files`).
- Strings in `values` and `values-pl`, "+ N more" and the link limit as `plurals` where counted.

## Acceptance criteria

- [x] AC1: The ride detail shows "Share" once loaded; the calendar's week view offers "Share week"; the month view does
      not.
- [x] AC2: The sheet hides the power switch for a ride without power and the HR switch for one without HR.
- [x] AC3: The ride card shows the backend's figures formatted with `Fmt`; with "Hide power" it has no NP, average
      power or power line; with "Hide heart rate" no HR figure or line; missing values are left out.
- [x] AC4: The week card shows `CalendarWeek`'s ridden / planned TSS and time, one bar per day with its rides and at
      most 7 ride lines plus "+ N more".
- [x] AC5: "Share image" writes a 1080 × 1350 PNG to `cache/shares/` and sends `ACTION_SEND` `image/png` with a
      `content://` URI and read permission; it works offline.
- [x] AC6: "Share link" sends the request with the kind, id / week and switches, then `ACTION_SEND` `text/plain` with
      the URL and shows the expiry; `SHARE_LIMIT_REACHED` shows its translated message; offline the button is disabled.
- [x] AC7: "Shared links" lists the active links with their options and expiry, revokes one after confirmation, shows
      the empty state, and reads offline with "Revoke" disabled.
- [x] AC8: `/s/` links are not App Links (the manifest's paths are unchanged).

## Open questions

- None after the decisions of 2026-11-24 (image and link, ride and week, the two switches, 15 days).
