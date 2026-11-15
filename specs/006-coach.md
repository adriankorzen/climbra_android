# 006: AI coach

- **Status:** Implemented
- **Created:** 2026-11-12

## Problem

The app has no AI coach, the web app's main feature (`../cyclingapp/frontend/src/app/coach`, cyclingapp specs 001,
007, 008, 009, 011, 013, 015, 020, 021, 029, 031). Riders want to ask for a review of a ride right after it, read and
continue their conversations, and get a training block into their calendar from the phone. Specs 002, 003 and 005
left their coach parts for this spec.

## Scope

- **Coach tab** in the navigation bar between Calendar and Profile (5 tabs: Dashboard, Activities, Calendar, Coach,
  Profile), as the web's menu.
- **Conversation list:** the rider's conversations newest first with type, status (answering / failed), links to a
  ride or plan, the time of the last message; filter by type; delete (after a confirmation); the AI credits summary;
  start a training block or a file review; the web's welcome with the three ways to start when there is none.
- **Conversation:** the messages (the coach's as Markdown), files sent with them, the doctor referral notice, the
  request running in the background (queued / thinking with the elapsed time) or failed (retry, edit again, delete),
  a request refused at once (send again, edit), follow-ups with an optional FIT / ZIP file, the cost of a follow-up,
  the read-only note; the panel of the latest **review** or **training block** next to the chat.
- **Review panel:** session type, estimated and rider RPE, summary, RPE assessment, strengths, issues,
  recommendations by category, the next session; the coach's plan assessment (keep / change) shown read-only.
- **Training block panel:** name, dates, methodology, goal, rationale, one week at a time (pager), each week's TSS,
  recovery badge and focus, the workouts by weekday; a workout opens its detail (step chart, steps with watts).
  "Add to calendar" / "In calendar" / "Update in calendar", the overlap with another plan asked before replacing it.
- **Ride review from the ride detail:** "Ask the coach" (RPE, note, free first review or its cost) or "Continue
  review"; the coach card with the latest review's summary, session type and RPE.
- **New training block** form and **file review** form (a FIT / ZIP file that is not stored as a ride).
- **Background answers:** requests are followed while the app is open; when one ends, a snackbar
  "The coach answered …" / "… failed" with "Open" (not when that conversation is shown). One running request per
  account (cyclingapp spec 015): starting another is disabled meanwhile, with the web's hint.
- **AI credits:** the cost next to every button that sends a request, "not enough credits" with the reset date, the
  free first review of a ride; the credits reload after every request. The profile keeps its details (spec 004).
- **Dashboard:** the coach section of the web (the 3 newest conversations, "All", "New training block",
  "Review a FIT file").
- **Calendar:** "Create a training block" in the no-plan note and the plan's "Open conversation" link (spec 005 left
  them for this spec).
- The health note under every input to the coach (cyclingapp spec 031) with the terms link.

### Out of scope

- **Spec 007 (plan adjustments):** the adjustment dialog ("Adjust plan", "Ask the coach" of a suggestion, "Adjust with
  coach" of a review), showing / accepting / rejecting a proposal (the proposal tab of a `PLAN_ADJUSTMENT`
  conversation), the review's "Replace in plan". Until then the calendar's banners keep "Open on climbra.app"
  (spec 005), and a `PLAN_ADJUSTMENT` conversation shows its chat with "Open on climbra.app" in place of the proposal
  tab (Custom Tab to `{webBaseUrl}/coach/{id}`).
- System notifications when an answer is ready while the app is closed: the backend has no push; the conversation
  shows the answer on the next visit.
- Choosing the coach's model / provider (the backend's choice), conversations of other riders.
- Buying credits or a subscription (on the web, spec 004).
- A two-pane layout (list next to the conversation) for tablets.

## Behaviour

### Loading and the rider

- The rider comes from the rider context (spec 002); an account without a rider sees the profile's "Create your
  profile" hint as the other tabs.
- The list loads `GET /api/riders/{id}/coach/conversations` (newest first, as the backend sorts) each time the tab is
  shown after a change (a request accepted, answered, a deletion) and on pull to refresh. Filter chips: All, Ride
  review, File, Training block (the web's; "All" also shows plan adjustments). The filter and the scroll position
  survive switching tabs.
- A conversation loads `GET /api/coach/conversations/{id}`; a `404` / `403` shows "Conversation not found". While
  `busy` it is loaded again every 5 s while the screen is shown (the app in the foreground), until the answer or the
  failure is there; then the list and the credits reload.
- The credits (`GET /api/account/coach-quota`) live in one app-wide `CoachQuotaRepository`, loaded with the session
  and again after every coach request and its end; the profile (spec 004) reads the same repository. Until they are
  loaded every request counts as covered (the backend decides), as on the web.

### Sending to the coach

- Every start and follow-up is answered with `202` and the busy conversation (cyclingapp spec 008): the app shows it
  at once and follows it; nothing blocks while the model answers, and leaving the screen does not stop it.
- **One request at a time** (spec 015): while the account has a running request (`CoachActivity.atCapacity`), every
  button that would start one is disabled and the cost line says "The coach is answering another request – wait until
  it finishes." A `409 COACH_BUSY` (another device) is shown as a refused request.
- **Cost line** (`coachQuota.*` of the web, cost mode, in its words): "Uses {cost} of {remaining} remaining AI
  credits", "Costs {cost} AI credits (unlimited)", "Free – the first review of this ride", or in the error color "Not
  enough AI credits: needs {cost}, {remaining} left · renews on {date}", which also disables the button. The cost kind follows the web:
  `review` for a ride review that is not free and a file review, `reviewMessage` for a follow-up of a review,
  `trainingBlock`, `blockMessage` for a follow-up of a training block or plan adjustment, the retry as its request.
- **Errors of a request** (the short `POST`), headline by status as the web's `coachErrorKey`: no connection,
  `400` validation, `409` conflict, `422` file not readable, `429` monthly limit, `502` / `504` provider, `503` not
  configured, otherwise "Something went wrong"; below it the backend's detail through `toUiText`.
- **Errors of a background request** (`pendingRequest.status == FAILED`): the text of `errorCode`
  (`coachRequest.errors.*`: provider error, provider quota, refused, invalid answer, timeout, interrupted, not
  configured, unexpected).

### Conversation list

- Each row: title, the type badge (short names of the web), "Answering…" / "Failed" for `BUSY` / `FAILED`, a bike icon
  when it has a ride, a calendar icon when it has a plan, the relative time of `lastMessageAt` ("3 min ago",
  `Fmt.relative`, counting on while shown). Tapping opens the conversation.
- Delete from the row's overflow menu: a dialog "Delete the conversation "{title}"?", then
  `DELETE /api/coach/conversations/{id}` → the list reloads, snackbar "Conversation deleted"; an error shows a
  snackbar. A plan saved from it stays in the calendar (backend).
- The top app bar shows "Coach" and below it the credits summary: the plan badge and "{remaining} of {limit} credits
  left · renews on {date}" (+ "{n} bonus credits until {date}"), "No credits left until {date}" in the error color,
  or "Unlimited ({used} used)".
- An extended FAB "New" opens a menu: "Training block", "Review a FIT file".
- Empty list: the web's welcome card (`coach.welcomeTitle`, `coach.welcome`) with the three actions: training block,
  FIT file review, "Review a ride" (switches to the Activities tab).

### Conversation screen

- Top app bar: back, the title (two lines at most), an overflow menu with "Open ride" (when `activityId`; the ride
  detail on the Coach tab's stack), "Open plan" (when `plan` or `planId`; the calendar tab at the plan's first week,
  through the shell's `openCalendar`), "Delete".
- Under it: the type badge and the start date and time. When there is a panel (`latestReview` or
  `latestTrainingBlock`), a `PrimaryTabRow` "Chat" | "Review" / "Training block"; the panel tab shows "Updated" for
  4 s when an answer to a request sent from this screen changed the review or the block.
- **Messages** (oldest first, the list starts at the end): the rider's on the right in a tonal bubble with their
  text, the coach's on the left, rendered as Markdown (headings, lists, bold / italics, tables, code, links open in a
  Custom Tab); files sent with a message as chips with an attachment icon; the time (`HH:mm`, the date when not
  today). After a message with `medicalReferral` the error-colored notice "The coach recommends seeing a doctor …"
  (cyclingapp spec 031). Under the newest message with `trainingBlock` (spec 021): the hint "The training block is
  ready" / "The block was revised – update it in the calendar" / "The block is in your calendar" (by `plan`) and
  "Show training block", which switches to the panel tab.
- **Running request:** its text (and files) as the rider's bubble, then the coach's bubble with a typing indicator,
  "Waiting in the queue…" (`QUEUED`) or "The coach is thinking…" and the elapsed time from `startedAt` / `createdAt`
  (corrected by the server's `Date` header), and the hint "You can leave this screen; you'll be told when the answer
  is ready."
- **Failed request:** its text as the rider's bubble, the error text, "Retry" (`POST …/retry`, disabled without
  credits or while another request runs; not in a read-only conversation), and for a follow-up "Edit again" (its text
  into the input) or for a failed start "Delete" (deletes the conversation, back to the list).
- **Refused request** (the `POST` failed): the text as a bubble in the error state, the headline and detail,
  "Send again" and "Edit". Nothing was stored; leaving the conversation forgets it.
- **Input** (not in a `readOnly` conversation, which shows the web's read-only note instead): a text field (up to
  10 000 characters, several lines), an attach button (system file picker, `application/octet-stream` and
  `application/zip`; a file not ending in `.fit` / `.zip` shows "Choose a FIT or ZIP file"), the chosen file as a chip
  that can be removed, send (enabled with text, credits and nothing running). Sending:
  `POST /api/coach/conversations/{id}/messages` (multipart `text`, optional `file`) clears the input. Below: the cost
  line and the health note.
- **Review panel** (the web's `review-panel`): the session type badge, "Estimated RPE" and "Your RPE" (`–` without),
  the summary, the RPE assessment, "Strengths" and "Issues" (`–` when empty), "Recommendations" grouped by category
  in the order the categories first appear, "Next session" as a workout tile. With `planAssessment`: `KEEP` → "Next
  planned workout" with `reviewPlanChange.plannedWorkout` and "The coach keeps your plan: {reason}"; otherwise "The
  coach suggests a change on {weekday date}" with the planned and proposed tiles, the reason, "In your plan" when
  `status == APPLIED` (the actions follow in spec 007). A workout tile opens the workout detail sheet.
- **Training block panel** (the web's `training-block-view`, phone layout): name, dates, the methodology badge, the
  goal, the rationale in an expandable section; a `HorizontalPager` of the weeks with "Week {n} of {count}" and
  previous / next buttons; a week: number, planned TSS, recovery badge, focus, the days Monday–Sunday with their
  workout tiles (`–` for a rest day). Under it, for a `TRAINING_BLOCK` conversation, the plan actions:
  - no `plan`: "Add to calendar" → `POST /api/coach/conversations/{id}/plan` `{replaceOverlapping: false}`;
  - `plan`: "In calendar" (the calendar tab at the block's first week), and when `plan.outdated` "Update in
    calendar" with the hint "The coach revised the block after you saved it";
  - `409 PLAN_OVERLAP`: a dialog "Replace the plan "{name}" from {date}?" → the same call with
    `replaceOverlapping: true`; other errors as a snackbar. Success: snackbar "Added to the calendar" / "Calendar
    updated", the conversation reloads, `DataChanges.changed()`.
  Disabled while saving or while the conversation is busy.
- **Workout detail sheet** (a `ModalBottomSheet`): title, type, long date, indoor, duration, TSS, IF, target RPE,
  description, the step profile chart and the steps table of the calendar's workout detail (spec 005), which become
  shared composables taking the blocks; watts from the backend's `powerLowWatts` / `powerHighWatts` (`–` without
  FTP), the zone colors from `step.zone`.

### Ride review (ride detail, spec 003)

- The ride detail loads the rider's conversations and takes the newest `ACTIVITY_REVIEW` of the ride; with one, also
  that conversation for its `latestReview`.
- Under the header: "Continue review" (`auto_awesome`) when that conversation is not read-only, which opens it;
  otherwise "Ask the coach".
- The coach card at the end of the detail: "Coach" with "Open conversation" when there is one; the latest review's
  summary, session type badge and "RPE {estimated} / {rider}"; without a review "No review of this ride yet" and
  "Ask the coach".
- "Ask the coach" opens a bottom sheet: RPE as a 1–10 scale (`SingleChoiceSegmentedButtonRow` in two rows of five,
  tapping the selected value clears it) with the CR10 description of the chosen value ("No RPE" without), a note (up
  to 2 000 characters, the web's placeholder), the health note, the cost line (free when `freeReviewAvailable`),
  "Cancel" and "Start review" → `POST /api/activities/{id}/coach/conversations` `{rpe, note, language}` (the app's
  language). Accepted: the sheet closes and the conversation opens on the Coach tab. `409 COACH_REVIEW_EXISTS`:
  snackbar "This ride already has a review" and its `conversationId` opens. Other errors stay in the sheet. Closing
  the sheet while the request is sent does not cancel it; the snackbar tells when the answer is ready.

### New training block

- The web's form, as one scrolling screen on the Coach tab's stack:
  - start date (date picker, today or later, default tomorrow; "The start can't be in the past");
  - weeks 4 / 5 / 6 (`SingleChoiceSegmentedButtonRow`, default 4);
  - goal (required, up to 500 characters; "Describe your goal");
  - goal event date (optional date picker, today or later, clearable);
  - availability: one row per weekday with a switch, minutes (15–720, steps of 15, a stepper / number field) and
    "Indoor" (checkbox); defaults Tue 60, Thu 60, Sat 180, Sun 120, the others off; "Choose at least one day" when
    none is available with minutes;
  - long ride day (dropdown, "–" or a weekday, default Saturday; sent only when that day is available);
  - methodology (four selectable cards with the web's hints: Auto, Polarized, Pyramidal, Sweet spot; default Auto);
  - notes (optional, up to 2 000 characters);
  - the coach's language (English / Polski, default the app's);
  - the health note, the cost line (`trainingBlock`), "Create training block".
- Sends `POST /api/riders/{id}/coach/conversations/training-block` with the web's body (only the available days with
  their minutes, `indoorDays` of those days, trimmed texts, empty ones as `null`). Accepted: the conversation replaces
  the form on the stack. An error shows the headline and detail above the button; the form keeps its values.

### File review

- A screen on the Coach tab's stack: the hint, a "Choose a file" card (system file picker, FIT / ZIP; the name when
  chosen, "Choose a FIT or ZIP file" for another type), RPE as in the ride sheet, the note, the language, the health
  note, the cost line (`review`), "Start review" (enabled with a file).
- Sends `POST /api/riders/{id}/coach/conversations/file-review` (multipart `file`, query `language`, optional `rpe`,
  `note`), read from the `ContentResolver` stream. Accepted: the conversation replaces the form. Errors as the
  training block form (`422` "The file can't be read").

### Background requests (`coach.CoachActivity`)

- An app-scoped repository with the running conversations (id → title). It learns of them from every accepted
  request and, when the session becomes ready, from the list (`status == BUSY`).
- While the app is in the foreground (`ProcessLifecycleOwner` started) it loads each running conversation that no
  screen shows every 5 s (a shown conversation is loaded by its screen, which reports when it is no longer busy). A
  conversation that is not busy any more is finished: removed, the credits reload, `CoachActivity.finished` is bumped
  (lists reload), and unless it is shown a snackbar of the shell says "The coach answered "{title}"" or "The coach
  request "{title}" failed" with "Open" (the conversation on the Coach tab). A `404` / `403` forgets it silently.
- Logout or a session end clears it.

### Navigation

- `CoachGraph` with `CoachRoute` (list), `CoachConversationRoute(id)`, `CoachTrainingBlockRoute`,
  `CoachFileReviewRoute`, `CoachRideRoute(id)`; the workout detail is a sheet, not a route.
- A conversation opened from elsewhere (ride detail, dashboard, calendar, snackbar) switches to the Coach tab with
  the list as its start and the conversation on top, so back returns to the list (as the web's `/coach/{id}`).
  Tapping the Coach tab shows the list (spec 003's tab behaviour).
- After a form is accepted the conversation replaces it (`popUpTo` the form, inclusive).

### Dashboard and calendar

- Dashboard (spec 002): a "Coach" card after the existing sections with the 3 newest conversations (title, type badge,
  relative time; tapping opens it), "All" (the Coach tab), "New training block" and "Review a FIT file"; "No
  conversations yet" when there is none. It loads the conversation list with the dashboard and after
  `CoachActivity.finished`.
- Calendar (spec 005): the no-plan note gets "Create a training block" (the form on the Coach tab); a plan with
  `conversationId` gets "Open conversation".

## Backend dependencies

- Existing endpoints (cyclingapp specs 001, 007, 008, 009, 013, 015, 020, 021, 031):
  `GET /api/riders/{id}/coach/conversations` (`CoachConversationSummary[]`),
  `GET /api/coach/conversations/{id}` (`CoachConversationResponse`), `DELETE /api/coach/conversations/{id}`,
  `POST /api/activities/{id}/coach/conversations` (`202`, `409 COACH_REVIEW_EXISTS` with `conversationId`),
  `POST /api/riders/{id}/coach/conversations/file-review` (multipart, `202`),
  `POST /api/riders/{id}/coach/conversations/training-block` (`202`),
  `POST /api/coach/conversations/{id}/messages` (multipart, `202`), `POST /api/coach/conversations/{id}/retry` (`202`),
  `POST /api/coach/conversations/{id}/plan` (`409 PLAN_OVERLAP` with `overlappingPlan`),
  `GET /api/account/coach-quota`; `freeReviewAvailable` of the activity; `conversationId` of a calendar plan.
  Codes: `COACH_BUSY`, `COACH_MONTHLY_LIMIT`, `COACH_REVIEW_EXISTS`, `CONVERSATION_BUSY`, `CONVERSATION_READ_ONLY`,
  `PLAN_OVERLAP`, `PLAN_NO_BLOCK`.
- No backend change.

## Screens

- Coach tab: icon `forum` / `forum_fill1`, label "Coach". `CoachListScreen`: `TopAppBar` with the credits line,
  `FilterChip` row, `LazyColumn` of `ListItem`s with an overflow menu, `PullToRefreshBox`, `ExtendedFloatingActionButton`
  with a `DropdownMenu`, the welcome card for an empty list.
- `ConversationScreen`: `TopAppBar` with overflow, `PrimaryTabRow`, a reversed `LazyColumn` of bubbles (Markdown
  through `com.mikepenz:multiplatform-markdown-renderer-m3`, styled with the app's typography and colors), `Surface`
  input bar with `IconButton`s (`attach_file`, `send`), `AssistChip`s for files; the panels in a scrolling column.
- `AskCoachSheet` (`ModalBottomSheet`), `TrainingBlockFormScreen`, `FileReviewScreen`, `WorkoutDetailSheet`,
  `RpeScale`, `HealthNote`, `CoachCostLine`, `CreditsSummary`, the overlap and delete `AlertDialog`s.
- Material Symbols: `forum`, `forum_fill1`, `auto_awesome`, `attach_file`, `send`, `upload_file`, `add`,
  `delete`, `more_vert`, `refresh`, `chevron_left`, `chevron_right`, `calendar_month`, `directions_bike`,
  `expand_more`, `close` (those not in `res/drawable` yet are added).
- Strings: the web's `coach.*`, `coachRequest.*`, `coachErrors.*`, `coachQuota.*` (cost and summary),
  `askCoach.*`, `block.*`, `methodology.hint.*`, `review.*` (the read-only parts), `rpe.*`, `healthNote.*`,
  `enums.conversationType*`, `enums.methodology.*`, `enums.recommendation.*`, `calendar.add` / `inCalendar` /
  `update` / `overlap*` keys, English and Polish; counts (credits, weeks, minutes ago) as plurals.

## Data and storage

- `data/api/CoachApi.kt`: the coach endpoints (multipart with `MultipartBody.Part`) and the DTOs mirroring the web's
  `models.ts`: `CoachConversation`, `CoachConversationSummary`, `CoachMessage`, `CoachMeta`, `PendingCoachRequest`,
  `ActivityReview` (+ `PlanAssessment`), `ReviewPlanChange`, `TrainingBlock`, `TrainingWeek`, `PlannedWorkout`,
  `ConversationPlan`, `ActivityReviewRequest`, `TrainingBlockRequest` (+ availability), `SavePlanRequest`. Enums as
  strings with an unknown fallback (`ConversationType`, `Methodology`, `RecommendationCategory`, `PlanVerdict`, …).
  `WorkoutBlock` / `WorkoutStep` of `CalendarApi` are reused.
- `CoachQuota` moves from `ProfileApi` to `CoachApi` with the fields it lacked (`costs`, `remaining`, `unlimited`,
  `resetsAt`, `used`, `limit`).
- `ProblemDetail` / `ApiError.Http` carry `conversationId` and `overlappingPlan.name` of the backend's problem details.
- `RideDetail` DTO: `freeReviewAvailable`. Calendar plan DTO: `conversationId` (if missing).
- `Fmt.relative(instant, now)` mirroring the web's `formatRelative` (ICU's relative format with `numeric: auto`:
  "now", "3 minutes ago", "2 hours ago", "yesterday", the date after 30 days); `Fmt.time` / `timeOrDate` for messages.
- A `409 COACH_REVIEW_EXISTS` is told by the shell's snackbar (`CoachActivity.notices`), over the conversation that
  opens.
- Nothing is stored on the device; a chosen file is read once when the request is sent.

## Acceptance criteria

- [x] AC1: The navigation bar has five tabs with Coach between Calendar and Profile; tapping Coach shows the list.
- [x] AC2: The list shows each conversation's title, type badge, busy / failed status, ride and plan icons and the
      relative time; the filter chips narrow it by type; pull to refresh loads it again; an empty list shows the
      welcome with its three actions; a failed load shows the error with Retry.
- [x] AC3: Deleting asks first, sends `DELETE`, reloads the list and shows the snackbar.
- [x] AC4: The credits summary shows the plan with remaining / limit and the reset date, the bonus credits, "No
      credits left" or "Unlimited".
- [x] AC5: A conversation shows the messages in order, the coach's rendered as Markdown, the files, the doctor notice
      after a `medicalReferral` message and the block call-to-action under the newest block message with the hint by
      the plan's state.
- [x] AC6: A busy conversation shows the running request (queued / thinking, elapsed time) and is loaded every 5 s
      until it is answered; then the list and the credits reload.
- [x] AC7: A failed background request shows its error text, Retry (disabled without credits or with another request
      running), and Edit again for a follow-up or Delete for a start.
- [x] AC8: A follow-up sends the multipart text with the optional FIT / ZIP file, clears the input and shows the
      request; a refused one shows the headline by status with the detail, Send again and Edit; a wrong file type is
      rejected before sending.
- [x] AC9: A read-only conversation shows its note and no input; send and attach are disabled while a request runs,
      while another request of the account runs, or without enough credits.
- [x] AC10: The review panel shows type, both RPEs, summary, assessment, strengths, issues, grouped recommendations,
      the next session and the plan assessment (keep / change with both tiles and "In your plan"); a tile opens the
      workout detail sheet with the step chart and the steps in watts.
- [x] AC11: The training block panel shows the block's head and one week at a time with its TSS, recovery badge,
      focus and the workouts by weekday; previous / next and swiping change the week.
- [x] AC12: "Add to calendar" saves the plan; a `PLAN_OVERLAP` asks and replaces with `replaceOverlapping: true`;
      a saved plan shows "In calendar" (opens the calendar at the block's week) and, when outdated, "Update in
      calendar" with its hint; saving reports `DataChanges.changed()`.
- [x] AC13: The ride detail offers "Ask the coach" or "Continue review" and the coach card with the latest review;
      the sheet sends RPE, note and language, shows the free review or the cost, opens the accepted conversation on
      the Coach tab, and a `409 COACH_REVIEW_EXISTS` opens the existing one with its snackbar.
- [x] AC14: The training block form validates start date, goal and availability like the web, builds the web's
      request body and opens the accepted conversation in place of the form; an error keeps the values.
- [x] AC15: The file review sends the chosen file with RPE, note and language and opens the accepted conversation in
      place of the form; without a file the button is disabled.
- [x] AC16: An accepted request is followed while the app is in the foreground; when it ends, the snackbar says
      answered or failed with Open, unless the conversation is shown; busy conversations found after login are
      followed too; logout clears them.
- [x] AC17: Every button that sends a request shows the cost line with its kind (free, unlimited, not enough
      credits) and the credits reload after each request and its end; the profile shows the same credits.
- [x] AC18: The dashboard's coach card shows the 3 newest conversations and its shortcuts; the calendar's no-plan note
      offers "Create a training block" and a plan with a conversation "Open conversation".
- [x] AC19: A `PLAN_ADJUSTMENT` conversation shows its chat and "Open on climbra.app" in place of the proposal.
- [x] AC20: Every input to the coach shows the health note with the terms link; `values` and `values-pl` have the
      same keys.

## Tests

- `CoachApiTest` (`TestBackend`): the DTOs of real backend JSON, multipart bodies of messages and file reviews, the
  problem details' `conversationId` and `overlappingPlan` (AC8, AC12, AC13, AC15).
- `CoachListViewModelTest`, `ConversationViewModelTest`, `TrainingBlockFormViewModelTest`,
  `FileReviewViewModelTest`, `AskCoachViewModelTest`: loading and errors, polling with a test dispatcher, retry /
  edit / delete, refused requests, plan save with overlap, request bodies, validation (AC2–AC3, AC6–AC9, AC12–AC15).
- `CoachActivityTest`: tracking, polling only unshown conversations, finishing with the snackbar event, discovery
  after login, clearing on logout (AC16). `CoachQuotaRepositoryTest`: covers / cost / free (AC17).
- Compose UI tests on Robolectric: list rows, filters, empty and error states, credits summary (AC2, AC4);
  conversation bubbles, Markdown, notices, running / failed / refused states, read-only, disabled input (AC5–AC9,
  AC19); review and block panels, the workout sheet, the pager (AC10, AC11); plan actions (AC12); the ride's coach
  card and sheet (AC13); the forms (AC14, AC15); the dashboard card and calendar links (AC18); the health note
  (AC20); the shell's five tabs and a conversation opened from another tab (AC1).
- `FmtTest`: `relative`. `StringResourcesTest`: the same keys.

## Open questions

- None (decisions 2026-11-12: two specs, 006 conversations / reviews / training blocks and 007 plan adjustments;
  Coach as the 5th tab; answers told by an in-app snackbar, no system notifications; Markdown through
  `multiplatform-markdown-renderer-m3`).
