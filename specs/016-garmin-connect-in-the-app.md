# 016: Connecting Garmin Connect in the app

- **Status:** Draft
- **Created:** 2026-11-18

## Problem

To send planned workouts to Garmin Connect (spec 005) the rider must connect it, and the app sends them to the web
profile for that (spec 004): the backend kept the OAuth state in the browser's session, which the app's Custom Tab
does not share. cyclingapp spec 055 adds a flow the app can finish in its own session.

## Scope

- **Connect** in the profile's Garmin Connect card and in the calendar's "Connect Garmin Connect first" dialogs
  (calendar and workout detail, spec 005), in place of "Connect on climbra.app".
- The web's consent dialog (spec 047) before the flow.
- The Garmin authorization page in a Custom Tab, the return to the app through `climbra://garmin-connect`, and the
  completion with `POST /api/garmin-connection/authorization/complete`.
- The outcome in a snackbar, with the web's texts; the Garmin status loads again.
- "Connect again" for `NO_PERMISSION` and `NEEDS_RECONNECT` (spec 004 shows them) uses the same flow.

### Out of scope

- Disconnecting (spec 004 has it), sending workouts (spec 005).
- An App Link instead of the custom scheme (cyclingapp spec 055, open question).

## Behaviour

1. **Connect** opens the consent dialog with the web's texts (`garmin.dialog`: what is sent, Garmin's own use, the
   restriction, "workouts are sent only when you click", the link to Garmin's privacy notice) and the checkbox "I
   agree to my planned workouts being transferred to Garmin"; **Continue to Garmin Connect** is enabled only when it
   is checked.
2. Continue → `POST /api/riders/{id}/garmin-connection/authorization` `{"consent": true, "returnTo": "APP"}`; the
   answer's `authorizationUrl` opens in a Custom Tab. `503` → "Garmin Connect is not available right now."; `409`
   (already connected) → the status loads again. While the request runs the button shows progress.
3. Garmin's consent ends in the backend's callback, which redirects to `climbra://garmin-connect?state=…`;
   `MainActivity` takes it (an intent filter for the scheme and host), and `garmin.GarminConnectFlow` (app-scoped)
   completes it: `POST /api/garmin-connection/authorization/complete` `{"state": …}`.
4. **Outcome** (the web's `garmin.outcome` texts) in the shell's snackbar: `connected` "Connected to Garmin Connect",
   `no-permission`, `denied`, `in-use`, `failed`; then `DataChanges.changed()` so the profile's card and the calendar's
   Garmin status load again.
5. A redirect that arrives while logged out (the session ended meanwhile) waits until the shell shows, as the shared
   files of spec 009, then completes; a state the backend does not know any more gives `failed`.
6. The rider closes the Custom Tab without finishing: nothing arrives; the card stays as it was (no error).

## Backend dependencies

- **New, cyclingapp spec 055:** `returnTo: APP`, the callback's redirect to `climbra://garmin-connect?state=…`, and
  `POST /api/garmin-connection/authorization/complete` → `{"outcome": …}`.
- Existing: `GET /api/riders/{id}/garmin-connection` (status, spec 004).

## Screens

- `garmin.GarminConsentDialog` (`AlertDialog` with the texts, a `Checkbox` row, the privacy link), used by the profile
  card and the calendar's dialogs.
- Manifest: an intent filter on `MainActivity` for `climbra://garmin-connect` (`VIEW`, `BROWSABLE`).

## Data and storage

- `data/api`: `GarminAuthorizationRequest(consent, returnTo)`, `GarminAuthorization(authorizationUrl)`,
  `GarminCompleteRequest(state)`, `GarminOutcome(outcome)`, in `ProfileApi` next to the connection calls.
- The state waits in memory until it is completed; nothing stored on the device.

## Acceptance criteria

- [ ] AC1: Connect in the profile and in the calendar's dialogs opens the consent dialog; Continue needs the checkbox.
- [ ] AC2: Continue sends `consent: true, returnTo: APP` and opens the returned URL in a Custom Tab; `503` and `409`
  are handled.
- [ ] AC3: `climbra://garmin-connect?state=…` completes with that state, shows the outcome's text and reloads the
  Garmin status.
- [ ] AC4: A redirect while logged out completes after the next login.
- [ ] AC5: "Connect on climbra.app" is gone from the profile and the calendar.
- [ ] AC6: New strings exist in English and Polish.

## Open questions

- Depends on cyclingapp spec 055 being approved and deployed first.
