# 010: Feedback

- **Status:** Implemented
- **Created:** 2026-11-18

## Problem

The web has a "Feedback" button in its shell that opens a form whose message goes to the author of the app
(cyclingapp spec 030). The app has no way to report a bug or suggest something, though the app itself is what a
rider would want to report on.

## Scope

- A feedback form like the web's `feedback-dialog`: name, type, message, sent with `POST /api/feedback`.
- Its entry point: "Send feedback" in the profile's account card.

### Out of scope

- Attachments, screenshots, logs: the backend takes text only.
- The list of sent feedback, answers to it (none on the web).

## Behaviour

Full screen on the profile's stack, title "Send feedback", back arrow, as the coach's forms.

- Intro: "Tell us what doesn't work, what to improve or what is missing. Every message goes to the author of the
  app."
- **Name**, required (blank counts as missing), at most 100 characters; filled with the rider's name when the screen
  opens.
- **Type**, required: Bug, Something to improve, Suggestion, Other (`BUG`, `IMPROVEMENT`, `SUGGESTION`, `OTHER`) as
  single-select chips, none chosen at first.
- **Message**, required (blank counts as missing), at most 5000 characters, with the counter "{count} / 5000".
- **Send**: a missing field shows "Fill in this field." under it and nothing is sent. Otherwise
  `POST /api/feedback` with the trimmed name and message, the type, and `path` = `android/{versionName}` (e.g.
  `android/0.1.0`; the web sends its page, the app tells where the message comes from).
  - `201` → toast "Thank you for your feedback!" and back to the profile.
  - `429` → "You have sent a lot of feedback lately. Try again later."; other errors as usual (`toUiText`). The form
    keeps its values.
- Leaving the screen drops what was typed (as on the web, where every opening starts empty).

## Backend dependencies

Existing only: `POST /api/feedback` (`FeedbackRequest`: `name` ≤ 100, `type`, `message` ≤ 5000, `path` ≤ 500 or
`null`) → `201`; `400` on invalid fields, `429` when the account sent too much lately (cyclingapp spec 030). No
backend change.

## Screens

- `profile.FeedbackRoute` / `FeedbackScreen`: the coach forms' `FormScaffold`, `OutlinedTextField`s, `FilterChip`s
  for the type, `Button` Send with a progress bar while sending.
- Profile's account card: `OutlinedButton` "Send feedback" (icon `feedback`, new).
- Navigation: `ProfileFeedbackRoute` in the profile's graph.

## Data and storage

- `data/api`: `FeedbackRequest(name, type, message, path)` and a call `sendFeedback` (in a new `FeedbackApi`).
- Nothing stored on the device.

## Acceptance criteria

- [x] AC1: "Send feedback" in the profile opens the form with the rider's name filled in, no type and an empty
  message.
- [x] AC2: Missing (or blank) name, type or message shows "Fill in this field." and sends nothing; the name and the
  message are cut at their limits and the message shows its counter.
- [x] AC3: Send posts the trimmed name and message, the type and `android/{versionName}`; `201` shows the thanks and
  goes back.
- [x] AC4: `429` and other errors show their message and keep the values.
- [x] AC5: New strings exist in English and Polish.

## Open questions

- None.
