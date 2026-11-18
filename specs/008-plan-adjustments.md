# 008: Plan adjustments with the coach

- **Status:** Implemented
- **Created:** 2026-11-15

## Problem

The web lets a rider ask the AI coach to adjust the rest of a saved plan, review the coach's proposal and accept or
reject it, and apply a review's "change the next workout" verdict to the plan (cyclingapp specs 010 and 020). In the
app, specs 005 and 006 left all of it on the web: the calendar's suggestion and proposal banners, the
`PLAN_ADJUSTMENT` conversations and the review's plan section carry "Open on climbra.app". Spec 006 reserved this
work as "spec 007"; that number went to the pull to sync, so it is this spec.

## Scope

- **Adjustment form** (the web's `adjustment-dialog`): reasons, fatigue 1–10, note, first day that may change,
  optional new availability; sends `POST /api/riders/{id}/plans/{planId}/adjustments`.
- Its entry points: "Adjust plan" on the calendar's plan card, "Ask the coach" on the adjustment suggestion
  (reasons preselected), "Adjust with coach" on a review's plan section (prefilled from the review).
- **Proposal** (the web's `proposal-view`): summary, changed days before → after, weekly planned TSS before / after,
  Accept / Reject; shown in the panel tab of a `PLAN_ADJUSTMENT` conversation, and opened from the calendar's
  proposal banner.
- **"Replace in plan"** on a review's plan section (`POST /api/coach/conversations/{id}/review/plan-change`).
- The "Open on climbra.app" fallbacks of these places go away.

### Out of scope

- Editing a proposal or single days of it; partial acceptance (the backend has neither).
- Editing workouts or plans by hand, moving workouts between days (spec 005).
- Notifications when the proposal is ready while the app is closed (no push, spec 006).

## Behaviour

### Adjustment form

Full screen on the stack of the tab it was opened from (as the training block form, spec 006), title "Adjust plan"
with the plan's name.

- **Reasons** (at least one, multi-select chips): Fatigue, Illness, Lack of time, Missed workouts, Deviation from the
  plan, Other (`FATIGUE`, `ILLNESS`, `LACK_OF_TIME`, `MISSED_WORKOUTS`, `DEVIATION`, `OTHER`).
- **Fatigue** 1–10, optional (a row of 10 toggle chips; tapping the selected one clears it).
- **Note**, optional, at most 2000 characters (counter near the limit).
- The health note of the coach forms (spec 006) with its link to the terms.
- **First day that may change**: date picker from today to the plan's end; default tomorrow, or today when the plan
  ends today.
- **Change training days** switch, off by default; on: the 7 days with a checkbox and minutes (15–600, step 15,
  default 60) each, as the training block form's day rows. Off, or on with no day chosen → `availability: null`
  (the plan's training days stay).
- The AI credits note for a plan adjustment (`CostKind.PLAN_ADJUSTMENT`) as in the other coach forms; Send is
  disabled when the credits do not cover it.
- Language: the app's language (`pl` / `en`), as the other coach requests.
- **Prefill:**
  - From the suggestion's "Ask the coach": reasons from the suggestion (`KEY_WORKOUT_MISSED` / `WORKOUTS_MISSED` →
    Missed workouts, any other → Deviation, the web's `reasonsOfSuggestion`), `includeSuggestion: true`; a line
    "Based on the suggestion of the plan" above the reasons.
  - From a review: reason Deviation, first day = the review's change day (tomorrow if earlier), note = "Coach's
    review of {title}: {reason}" (the web's review panel; cut to 2000), `includeSuggestion: false`.
  - From the plan card: nothing preselected, `includeSuggestion: false`.
- **Send** → `202` with the `PLAN_ADJUSTMENT` conversation: `CoachActivity.track` it, `DataChanges.changed()`, and
  the form is replaced by that conversation on the Coach tab (as after a training block request, spec 006); the
  conversation shows the coach working, and the shell's snackbar tells when the answer is there.
- **Errors** under the Send button: `400` → the usual message, `409 PLAN_FINISHED` "The plan has no workouts from
  this day on", `409 PLAN_ADJUSTMENT_RUNNING` "The coach is already adjusting this plan", `429` / `COACH_BUSY` /
  `COACH_MONTHLY_LIMIT` as the other coach forms. The form keeps its values.

### Calendar

- Plan card: "Adjust plan" button (icon `auto_awesome`), hidden while `adjustmentRunning` (the "The coach is
  adjusting the plan" line shows instead).
- Suggestion banner: "Ask the coach" (disabled while `adjustmentRunning`) next to Dismiss, in place of "Open on
  climbra.app".
- Proposal banner: "Show proposal" in place of "Open on climbra.app": opens the proposal's conversation on the Coach
  tab with its proposal tab selected. `PendingProposal` gains `conversationId` (sent by the backend already).

### Proposal

The panel tab of a `PLAN_ADJUSTMENT` conversation whose `latestProposal` is set; the tab is called "Proposal". It
loads `GET /api/riders/{id}/plans/{planId}/proposals/{proposalId}` (`planId` of the conversation) and loads again
when the conversation's latest proposal changes (a follow-up in the chat can make a new one) and after a decision.

- Head: status badge (Pending / Accepted / Rejected / Outdated) and "Changes from {date}".
- The summary (Markdown, as the coach's messages).
- **Changed days** (days with `change != UNCHANGED`), each: the weekday date, a badge (Added / Removed / Changed),
  "Done – stays as it is" when `completed`, then the workout before and after as the coach's workout tiles (tap opens
  the workout detail with steps, spec 006) or "–". No changed day: "The proposal changes no workout".
- **Weeks**: a table week number, planned TSS before, after, focus (after, else before); changed rows highlighted.
- **Pending**: Accept and Reject buttons (Reject asks first: "Reject the proposal? The plan stays as it is").
  - Accept → `POST …/accept`: snackbar "The plan was adjusted", `DataChanges.changed()`, the calendar loads again
    when shown, the proposal reloads (Accepted).
  - Reject → `POST …/reject`: snackbar "Proposal rejected", reloads (Rejected).
  - `409 PROPOSAL_OUTDATED` → "The plan changed after the proposal: ask the coach again" and the proposal reloads
    (Outdated). Other errors as usual. Buttons are disabled while one runs.
- Not pending: no buttons; the chat stays usable (a follow-up may produce a new proposal).
- Load failure: the error with Retry inside the tab.

### Review's plan section

On the review panel (spec 006) when the verdict is `CHANGE_WORKOUT` or `ADJUST_PLAN`:

- `reviewPlanChange.status == APPLICABLE` → "Replace in plan" (filled for `CHANGE_WORKOUT`, outlined for
  `ADJUST_PLAN`) → `POST /api/coach/conversations/{id}/review/plan-change`: snackbar "The workout was replaced in
  your plan", `DataChanges.changed()`, the conversation reloads (status becomes `APPLIED` → "In your plan").
  `409 REVIEW_NO_PLAN_CHANGE` / `PLAN_CHANGE_NOT_APPLICABLE` / `CONVERSATION_BUSY` → their messages.
- `reviewPlanChange.planId != null` → "Adjust with coach" (filled for `ADJUST_PLAN`, outlined otherwise) opens the
  adjustment form prefilled from the review. The form needs the plan (name, end date): it loads
  `GET /api/riders/{id}/calendar?from=<workoutDate>&to=<workoutDate>` and takes the plan with that id, as the web;
  not found → "The plan is no longer in your calendar" and no Send.

### Elsewhere

- New translated error codes: `PLAN_FINISHED`, `PLAN_ADJUSTMENT_RUNNING`, `PROPOSAL_OUTDATED`,
  `REVIEW_NO_PLAN_CHANGE`, `PLAN_CHANGE_NOT_APPLICABLE`.
- The "Decided on climbra.app" line of `PLAN_ADJUSTMENT` conversations goes away.

## Backend dependencies

Existing only (cyclingapp specs 010, 020): `POST /api/riders/{id}/plans/{planId}/adjustments`
(`PlanAdjustmentRequest` → `202` `CoachConversation`), `GET /api/riders/{id}/plans/{planId}/proposals/{proposalId}`
(`PlanProposal`), `POST …/proposals/{proposalId}/accept` (`200` plan, `409 PROPOSAL_OUTDATED`),
`POST …/proposals/{proposalId}/reject`, `POST /api/coach/conversations/{id}/review/plan-change`
(`ReviewPlanChange`), `GET /api/riders/{id}/calendar`. No backend change.

## Screens

- `coach.AdjustmentFormRoute` / `AdjustmentFormScreen`: `FilterChip`s for reasons and fatigue, `OutlinedTextField`,
  the date field and day rows of the training block form, `Switch`, the quota and health notes.
- `coach.ProposalPanel` in the conversation's panel tab: `Card`s per changed day, a simple table for the weeks,
  `Button` / `OutlinedButton`, `AlertDialog` for Reject.
- Calendar plan card and banners: `OutlinedButton` / `TextButton`.
- Navigation: a new route `AdjustmentFormRoute(planId, origin…)` in the calendar's and the coach's graphs; the
  conversation route gains an optional initial tab (`PANEL`).
- Icons: `auto_awesome` (new), existing `forum`, `chevron_right`.

## Data and storage

- `data/api`: `PlanAdjustmentRequest(reasons, fatigue, note, fromDate, availability, includeSuggestion, language)`,
  `PlanProposal` (id, planId, conversationId, status, createdAt, fromDate, summary, days[date, change, before, after,
  completed], weeks[weekNumber, plannedTssBefore, plannedTssAfter, focusBefore, focusAfter]); `PendingProposal` gains
  `conversationId`; Retrofit calls for the five endpoints above. Nothing stored on the device.
- The app computes nothing of the proposal: days, changes and TSS are the backend's.

## Acceptance criteria

- [x] AC1: "Adjust plan" on a plan card opens the form with nothing preselected and tomorrow as first day; it is
  hidden while the coach adjusts the plan.
- [x] AC2: "Ask the coach" on a suggestion opens the form with the suggestion's reasons mapped and sends
  `includeSuggestion: true`.
- [x] AC3: Send is disabled without a reason or without enough credits; the request carries reasons in the fixed
  order, fatigue, trimmed note or `null`, the first day, `availability` only when days were chosen, and the language.
- [x] AC4: An accepted request tracks the conversation, marks data changed and opens the conversation on the Coach
  tab; `PLAN_FINISHED`, `PLAN_ADJUSTMENT_RUNNING` and the credit errors show their messages and keep the form.
- [x] AC5: The proposal tab of a `PLAN_ADJUSTMENT` conversation shows status, from date, summary, only the changed
  days with before / after and the completed note, and the weeks with before / after TSS.
- [x] AC6: Accept and Reject (after its confirmation) send their requests, show their snackbars, mark data changed
  and reload the proposal; `PROPOSAL_OUTDATED` shows its message and reloads; a decided proposal has no buttons.
- [x] AC7: "Show proposal" on the calendar's proposal banner opens the conversation on the Coach tab with the
  proposal tab selected.
- [x] AC8: A review with an applicable change shows "Replace in plan", which applies it, shows its snackbar, marks
  data changed and reloads the conversation; the plan-change errors show their messages.
- [x] AC9: "Adjust with coach" on a review opens the form prefilled (Deviation, the change day or tomorrow, the
  note) with the plan found through the calendar; a missing plan disables Send with its message.
- [x] AC10: No "Open on climbra.app" is left on the suggestion and proposal banners or in `PLAN_ADJUSTMENT`
  conversations.
- [x] AC11: New strings and the new error codes exist in English and Polish.

Texts: the quoted texts above give the meaning; the app takes the web's wording (`strings.xml`, e.g. "Plan updated"
after Accept, "The planned workout was replaced." after "Replace in plan").

## Open questions

- None.
