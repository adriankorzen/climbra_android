package app.climbra.android.coach

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.climbra.android.coach.CoachFixtures.BLOCK
import app.climbra.android.coach.CoachFixtures.PLAN_CHANGE
import app.climbra.android.coach.CoachFixtures.REVIEW
import app.climbra.android.coach.CoachFixtures.conversation
import app.climbra.android.coach.CoachFixtures.latestProposal
import app.climbra.android.coach.CoachFixtures.proposal
import app.climbra.android.coach.CoachFixtures.message
import app.climbra.android.coach.CoachFixtures.parse
import app.climbra.android.coach.CoachFixtures.parseQuota
import app.climbra.android.coach.CoachFixtures.pending
import app.climbra.android.coach.CoachFixtures.quota
import app.climbra.android.coach.CoachFixtures.summary
import app.climbra.android.dashboard.DashboardScreen
import app.climbra.android.dashboard.DashboardUiState
import app.climbra.android.data.api.CoachConversationSummary
import app.climbra.android.data.api.PlanProposal
import app.climbra.android.data.api.Rider
import app.climbra.android.data.api.TrainingPlan
import app.climbra.android.calendar.CalendarFixtures
import app.climbra.android.shell.CoachConversationRoute
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.toRoute
import app.climbra.android.data.net.ApiError
import app.climbra.android.data.net.Network
import app.climbra.android.shell.Shell
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.theme.ClimbraTheme
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Spec 006 screens rendered with Robolectric against plain state; texts are English (the default resources). */
@RunWith(AndroidJUnit4::class)
class CoachScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val fmt = Fmt(Locale.forLanguageTag("en"))
    private val now = Instant.parse("2026-11-12T10:00:00Z")
    private val limits = CoachLimits(parseQuota(quota()))

    private fun content(body: @Composable () -> Unit) = compose.setContent { ClimbraTheme(darkTheme = false) { body() } }

    private fun inside(tag: String, text: String) = compose.onNode(
        hasText(text, substring = true) and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))),
        useUnmergedTree = true,
    )

    private fun summaries(vararg json: String) =
        json.map { Network.json.decodeFromString(CoachConversationSummary.serializer(), it) }

    private fun chat(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag("chat").performScrollToNode(hasTestTag(tag))

    // ---- List (AC2 - AC4)

    @Test
    fun theListShowsEachConversationWithItsTypeStatusLinksAndTime() {
        var opened: Long? = null
        var filter: ConversationFilter? = null
        val conversations = summaries(
            summary(1, status = "BUSY", activityId = 85, title = "Tempo ride", lastMessageAt = "2026-11-12T09:57:00Z"),
            summary(2, type = "TRAINING_BLOCK", status = "FAILED", title = "Build"),
            summary(3, type = "PLAN_ADJUSTMENT", planId = 9, title = "Adjust"),
        )
        content {
            CoachListScreen(
                CoachListUiState(loading = false, conversations = conversations),
                fmt,
                now,
                CoachNavigation(openConversation = { opened = it }),
                CoachListActions(onFilter = { filter = it }),
            )
        }

        inside("conversation-1", "Tempo ride").assertIsDisplayed()
        inside("conversation-1", "Ride review").assertExists()
        compose.onNodeWithTag("status-busy-1", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("ride-icon-1", useUnmergedTree = true).assertExists()
        inside("time-1", "3 minutes ago").assertExists()
        compose.onNodeWithTag("status-failed-2", useUnmergedTree = true).assertExists()
        inside("conversation-2", "Training block").assertExists()
        compose.onNodeWithTag("plan-icon-3", useUnmergedTree = true).assertExists()

        compose.onNodeWithTag("filter-training_block").performClick()
        assertEquals(ConversationFilter.TRAINING_BLOCK, filter)
        compose.onNodeWithTag("conversation-2").performClick()
        assertEquals(2L, opened)
    }

    @Test
    fun theFilterNarrowsTheList() {
        val conversations = summaries(summary(1), summary(2, type = "FILE_REVIEW"))
        content {
            CoachListScreen(CoachListUiState(loading = false, conversations = conversations, filter = ConversationFilter.FILE_REVIEW), fmt, now, CoachNavigation(), CoachListActions())
        }

        compose.onNodeWithTag("conversation-2").assertExists()
        compose.onNodeWithTag("conversation-1").assertDoesNotExist()
    }

    @Test
    fun anEmptyListWelcomesWithTheThreeWaysToStart() {
        val calls = mutableListOf<String>()
        content {
            CoachListScreen(
                CoachListUiState(loading = false),
                fmt,
                now,
                CoachNavigation(newTrainingBlock = { calls += "block" }, reviewFile = { calls += "file" }, openActivities = { calls += "ride" }),
                CoachListActions(),
            )
        }

        compose.onNodeWithText("Your coach").assertIsDisplayed()
        compose.onNodeWithTag("welcome-block").performClick()
        compose.onNodeWithTag("welcome-file").performClick()
        compose.onNodeWithTag("welcome-ride").performClick()
        assertEquals(listOf("block", "file", "ride"), calls)
    }

    @Test
    fun aFailedLoadShowsTheErrorWithRetryAndTheFabOffersTheForms() {
        var retries = 0
        val calls = mutableListOf<String>()
        content {
            CoachListScreen(
                CoachListUiState(loading = false, error = ApiError.Network),
                fmt,
                now,
                CoachNavigation(newTrainingBlock = { calls += "block" }, reviewFile = { calls += "file" }),
                CoachListActions(onRefresh = { retries++ }),
            )
        }

        compose.onNodeWithTag("coach-error").assertIsDisplayed()
        compose.onNodeWithTag("coach-retry").performClick()
        assertEquals(1, retries)
        compose.onNodeWithTag("coach-new").performClick()
        compose.onNodeWithTag("coach-new-block").performClick()
        compose.onNodeWithTag("coach-new").performClick()
        compose.onNodeWithTag("coach-new-file").performClick()
        assertEquals(listOf("block", "file"), calls)
    }

    // AC3
    @Test
    fun deletingAsksFirst() {
        var state by mutableStateOf(CoachListUiState(loading = false, conversations = summaries(summary(1, title = "Tempo"))))
        var confirmed = 0
        content {
            CoachListScreen(
                state,
                fmt,
                now,
                CoachNavigation(),
                CoachListActions(onAskDelete = { state = state.copy(deleting = it) }, onConfirmDelete = { confirmed++ }),
            )
        }

        compose.onNodeWithTag("menu-1").performClick()
        compose.onNodeWithTag("delete-1").performClick()
        compose.onNodeWithText("Delete the conversation “Tempo”?").assertIsDisplayed()
        compose.onNodeWithTag("confirm-delete").performClick()
        assertEquals(1, confirmed)
    }

    // AC4
    @Test
    fun theCreditsSummaryShowsWhatIsLeftOrThatNothingIs() {
        var json by mutableStateOf(
            quota().replace(""""bonus":[]""", """"bonus":[{"remaining":10,"expiresAt":"2027-01-31T00:00:00Z"},{"remaining":5,"expiresAt":"2027-02-28T00:00:00Z"}]"""),
        )
        content { CreditsSummary(parseQuota(json), fmt) }

        inside("credits-summary", "Free").assertExists()
        inside("credits-summary", "AI credits: 25 of 30 this month · renews on").assertExists()
        inside("credits-bonus", "+ bonus: 15").assertExists()

        json = quota(remaining = 0)
        compose.onNodeWithTag("credits-exhausted", useUnmergedTree = true).assertExists()

        json = quota(unlimited = true)
        inside("credits-summary", "AI credits: unlimited · 5 used this period").assertExists()
    }

    // AC17
    @Test
    fun theCostLineShowsTheCostFreeUnlimitedNotEnoughOrTheRunningRequest() {
        var state by mutableStateOf(Triple(limits, CostKind.TRAINING_BLOCK, false))
        content { CoachCostLine(state.first, state.second, fmt, free = state.third) }

        inside("cost-line", "Uses 5 of 25 remaining AI credits").assertExists()
        state = Triple(limits, CostKind.REVIEW, true)
        inside("cost-line", "Free – the first review of this ride").assertExists()
        state = Triple(CoachLimits(parseQuota(quota(unlimited = true))), CostKind.REVIEW, false)
        inside("cost-line", "Costs 2 AI credits (unlimited)").assertExists()
        state = Triple(CoachLimits(parseQuota(quota(remaining = 3))), CostKind.TRAINING_BLOCK, false)
        inside("cost-line", "Not enough AI credits: needs 5, 3 left").assertExists()
        state = Triple(limits.copy(atCapacity = true), CostKind.REVIEW, true)
        inside("cost-line", "The coach is answering another request").assertExists()
    }

    // ---- Conversation (AC5 - AC9, AC19)

    private fun conversationState(json: String, refused: RefusedRequest? = null, draft: String = "", limits: CoachLimits = this.limits) =
        ConversationUiState(loading = false, conversation = parse(json), refused = refused, draft = draft, limits = limits)

    private fun showConversation(state: ConversationUiState, actions: ConversationActions = ConversationActions()) =
        content { ConversationScreen(state, fmt, now, actions) }

    // AC5
    @Test
    fun theMessagesShowInOrderWithMarkdownFilesAndTheDoctorNotice() {
        val messages = "[" + listOf(
            message(1, "USER", "My knee hurts", ""","files":["ride.fit"]"""),
            message(2, "ASSISTANT", "## See a doctor\n\nThis is **important**:\n\n- rest\n- ice", ""","medicalReferral":true"""),
        ).joinToString(",") + "]"
        showConversation(conversationState(conversation(messages = messages)))

        chat("message-1")
        inside("message-1", "My knee hurts").assertIsDisplayed()
        compose.onNodeWithTag("file-ride.fit", useUnmergedTree = true).assertExists()
        chat("message-2")
        inside("markdown-2", "See a doctor").assertExists()
        inside("markdown-2", "important").assertExists()
        inside("markdown-2", "ice").assertExists()
        chat("medical-referral-2")
        inside("medical-referral-2", "The coach recommends seeing a doctor").assertIsDisplayed()
        compose.onNodeWithTag("composer").assertExists()
        compose.onNodeWithTag("health-note").assertExists()
    }

    // AC5
    @Test
    fun theBlockCallToActionTellsThePlansState() {
        val messages = "[" + message(1, "ASSISTANT", "Here is your block", ""","trainingBlock":true""") + "]"
        var plan by mutableStateOf("null")
        var tab: ConversationTab? = null
        content {
            ConversationScreen(
                conversationState(conversation(type = "TRAINING_BLOCK", messages = messages, block = BLOCK, plan = plan)),
                fmt,
                now,
                ConversationActions(onTab = { tab = it }),
            )
        }

        inside("block-cta-hint", "The training block is ready").assertExists()
        plan = """{"id":4,"outdated":true}"""
        inside("block-cta-hint", "The block was revised").assertExists()
        plan = """{"id":4,"outdated":false}"""
        inside("block-cta-hint", "The block is in your calendar.").assertExists()
        compose.onNodeWithTag("show-block").performClick()
        assertEquals(ConversationTab.PANEL, tab)
    }

    // AC6
    @Test
    fun aRunningRequestShowsTheRidersTextAndTheCoachThinking() {
        var status by mutableStateOf("RUNNING")
        content {
            ConversationScreen(conversationState(conversation(busy = true, pending = pending(status = status))), fmt, now, ConversationActions())
        }

        chat("pending-request")
        inside("pending-request", "And tomorrow?").assertExists()
        chat("waiting")
        inside("waiting-text", "The coach is thinking…").assertExists()
        compose.onNodeWithTag("elapsed", useUnmergedTree = true).assertExists()
        inside("waiting", "You can leave this screen").assertExists()
        compose.onNodeWithTag("send").assertIsNotEnabled()

        status = "QUEUED"
        inside("waiting-text", "Waiting in the queue…").assertExists()
    }

    // AC7
    @Test
    fun aFailedFollowUpOffersRetryAndEditAgain() {
        val calls = mutableListOf<String>()
        showConversation(
            conversationState(conversation(pending = pending(status = "FAILED", errorCode = "TIMEOUT"))),
            ConversationActions(onRetry = { calls += "retry" }, onEdit = { calls += "edit" }),
        )

        chat("request-failed")
        inside("request-failed", "The AI model took too long to answer.").assertExists()
        compose.onNodeWithTag("request-retry").assertIsEnabled().performClick()
        compose.onNodeWithTag("request-edit").performClick()
        compose.onNodeWithTag("request-delete").assertDoesNotExist()
        assertEquals(listOf("retry", "edit"), calls)
    }

    // AC7
    @Test
    fun aFailedStartOffersDeleteAndRetryWaitsForCredits() {
        var deleted = 0
        showConversation(
            conversationState(
                conversation(pending = pending(status = "FAILED", kind = "START", text = null, errorCode = "REFUSED")),
                limits = CoachLimits(parseQuota(quota(remaining = 0))),
            ),
            ConversationActions(onDelete = { deleted++ }),
        )

        chat("request-failed")
        compose.onNodeWithTag("request-retry").assertIsNotEnabled()
        compose.onNodeWithTag("request-edit").assertDoesNotExist()
        compose.onNodeWithTag("request-delete").performClick()
        assertEquals(1, deleted)
    }

    // AC8
    @Test
    fun aRefusedRequestShowsItsHeadlineAndDetailWithSendAgainAndEdit() {
        val calls = mutableListOf<String>()
        showConversation(
            conversationState(conversation(), refused = RefusedRequest("And tomorrow?", PickedFile("content://ride.fit", "ride.fit"), ApiError.Http(409, "COACH_BUSY"))),
            ConversationActions(onSendAgain = { calls += "again" }, onEdit = { calls += "edit" }),
        )

        chat("refused")
        inside("refused-text", "And tomorrow?").assertExists()
        inside("refused-error", "The coach is still answering, or the conversation reached its message limit.").assertExists()
        inside("refused-error", "A request to the coach is running.").assertExists()
        compose.onNodeWithTag("send-again").performClick()
        compose.onNodeWithTag("refused-edit").performClick()
        assertEquals(listOf("again", "edit"), calls)
    }

    // AC8
    @Test
    fun theComposerShowsTheChosenFileAndTheWrongTypeAndSends() {
        var state by mutableStateOf(conversationState(conversation(), draft = "Hello").copy(file = PickedFile("content://ride.fit", "ride.fit")))
        var sent = 0
        var removed = 0
        content { ConversationScreen(state, fmt, now, ConversationActions(onSend = { sent++ }, onRemoveFile = { removed++ })) }

        inside("chosen-file", "ride.fit").assertExists()
        compose.onNodeWithTag("chosen-file").performClick()
        compose.onNodeWithTag("send").assertIsEnabled().performClick()
        assertEquals(1, sent)
        assertEquals(1, removed)
        inside("cost-line", "Uses 1 of 25 remaining AI credits").assertExists()

        state = state.copy(file = null, fileError = true, draft = "")
        compose.onNodeWithText("Choose a FIT or ZIP file").assertExists()
        compose.onNodeWithTag("send").assertIsNotEnabled()
    }

    // AC9
    @Test
    fun aReadOnlyConversationHasNoInputAndAnotherRequestDisablesIt() {
        var state by mutableStateOf(conversationState(conversation(readOnly = true)))
        content { ConversationScreen(state, fmt, now, ConversationActions()) }

        compose.onNodeWithTag("read-only").assertIsDisplayed()
        compose.onNodeWithTag("composer").assertDoesNotExist()

        state = conversationState(conversation(), draft = "Hello", limits = limits.copy(atCapacity = true))
        compose.onNodeWithTag("send").assertIsNotEnabled()
        compose.onNodeWithTag("attach").assertIsNotEnabled()
        inside("cost-line", "The coach is answering another request").assertExists()
    }

    // AC19; spec 008 AC10: a plan adjustment without a proposal is its chat, with nothing left for the web
    @Test
    fun aPlanAdjustmentWithoutAProposalShowsItsChat() {
        showConversation(conversationState(conversation(type = "PLAN_ADJUSTMENT", planId = 7, activityId = null)))

        compose.onNodeWithTag("tab-panel").assertDoesNotExist()
        compose.onNodeWithTag("chat").assertExists()
        compose.onNodeWithText("climbra.app", substring = true).assertDoesNotExist()
    }

    // ---- Spec 008: proposal, review's plan section, adjustment form

    private fun proposalState(status: String = "PENDING", deciding: Boolean = false) =
        conversationState(conversation(type = "PLAN_ADJUSTMENT", planId = 7, activityId = null, proposal = latestProposal(status = status)))
            .copy(
                tab = ConversationTab.PANEL,
                proposal = Network.json.decodeFromString(PlanProposal.serializer(), proposal(status = status)),
                deciding = deciding,
            )

    // Spec 008 AC5, AC10
    @Test
    fun theProposalTabShowsStatusSummaryChangedDaysAndWeeks() {
        showConversation(proposalState())

        compose.onNodeWithTag("tab-panel").assertIsSelected()
        inside("tab-panel", "Proposal").assertExists()
        inside("proposal-status", "Waiting for your decision").assertExists()
        inside("proposal-from", "Changes from Nov 14, 2026").assertExists()
        inside("proposal-summary", "intensity").assertExists()
        // Only the changed days, with before → after
        inside("proposal-day-2026-11-14", "Changed").assertExists()
        inside("before-2026-11-14", "Sweet spot 3×12").assertExists()
        inside("after-2026-11-14", "Easy spin").assertExists()
        compose.onNodeWithTag("proposal-day-2026-11-15").assertDoesNotExist()
        compose.onNodeWithTag("proposal-day-2026-11-16").performScrollTo()
        inside("proposal-day-2026-11-16", "Removed").assertExists()
        inside("completed-2026-11-16", "The completed workout stays").assertExists()
        compose.onNodeWithTag("after-2026-11-16-none", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("proposal-day-2026-11-18").performScrollTo()
        inside("proposal-day-2026-11-18", "New").assertExists()
        compose.onNodeWithTag("before-2026-11-18-none", useUnmergedTree = true).assertExists()
        // The weeks' planned TSS from the backend
        compose.onNodeWithTag("proposal-weeks").performScrollTo()
        inside("proposal-week-2", "320").assertExists()
        inside("proposal-week-2", "250").assertExists()
        inside("proposal-week-2", "Recover").assertExists()
        inside("proposal-week-3", "Build").assertExists()
        compose.onNodeWithText("climbra.app", substring = true).assertDoesNotExist()
    }

    // Spec 008 AC5: a tile opens the workout's detail
    @Test
    fun aProposalTileOpensTheWorkout() {
        showConversation(proposalState())

        compose.onNodeWithTag("after-2026-11-14").performScrollTo().performClick()
        inside("workout-sheet", "Easy spin").assertExists()
    }

    // Spec 008 AC6: Accept; Reject asks first
    @Test
    fun aPendingProposalCanBeAcceptedOrRejectedAfterConfirmation() {
        val calls = mutableListOf<String>()
        var state by mutableStateOf(proposalState())
        content {
            ConversationScreen(
                state,
                fmt,
                now,
                ConversationActions(
                    onAccept = { calls += "accept" },
                    onAskReject = { calls += "ask"; state = state.copy(confirmingReject = true) },
                    onReject = { calls += "reject" },
                ),
            )
        }

        compose.onNodeWithTag("accept-proposal").performScrollTo().performClick()
        compose.onNodeWithTag("reject-proposal").performScrollTo().performClick()
        compose.onNodeWithText("Reject the proposal? The plan stays as it is.").assertIsDisplayed()
        compose.onNodeWithTag("confirm-reject").performClick()
        assertEquals(listOf("accept", "ask", "reject"), calls)

        state = proposalState(deciding = true)
        compose.onNodeWithTag("accept-proposal").assertIsNotEnabled()
        compose.onNodeWithTag("reject-proposal").assertIsNotEnabled()
    }

    // Spec 008 AC6: the decisions' snackbars; a decided proposal has no buttons
    @Test
    fun aDecidedProposalHasNoButtonsAndTheDecisionIsTold() {
        showConversation(proposalState(status = "ACCEPTED").copy(message = ConversationMessage.ProposalAccepted))

        compose.onNodeWithText("Plan updated").assertIsDisplayed()
        inside("proposal-status", "Accepted").assertExists()
        compose.onNodeWithTag("accept-proposal").assertDoesNotExist()
        compose.onNodeWithTag("reject-proposal").assertDoesNotExist()
        compose.onNodeWithTag("tab-chat").assertIsEnabled()
    }

    // Spec 008 AC6, AC11: an outdated proposal tells why
    @Test
    fun anOutdatedProposalTellsWhy() {
        showConversation(
            proposalState(status = "OUTDATED").copy(message = ConversationMessage.Failed(ApiError.Http(409, "PROPOSAL_OUTDATED"))),
        )

        compose.onNodeWithText("This proposal is outdated", substring = true).assertIsDisplayed()
        inside("proposal-status", "Outdated").assertExists()
    }

    // Spec 008 AC5: a failed load offers a retry
    @Test
    fun aProposalThatFailedToLoadOffersARetry() {
        var retries = 0
        showConversation(
            proposalState().copy(proposal = null, proposalError = ApiError.Http(503, null)),
            ConversationActions(onRetryProposal = { retries++ }),
        )

        compose.onNodeWithTag("proposal-error").assertExists()
        compose.onNodeWithTag("proposal-retry").performClick()
        assertEquals(1, retries)
    }

    private val applicable = PLAN_CHANGE.replace("APPLIED", "APPLICABLE")

    // Spec 008 AC8, AC9: an applicable change offers both actions; the adjustment is prefilled from the review
    @Test
    fun anApplicableChangeOffersReplaceAndAdjust() {
        var replaced = 0
        var args: AdjustmentArgs? = null
        showConversation(
            conversationState(conversation(review = REVIEW, planChange = applicable)).copy(tab = ConversationTab.PANEL),
            ConversationActions(onReplaceInPlan = { replaced++ }, onAdjust = { args = it }),
        )

        compose.onNodeWithTag("replace-in-plan").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("adjust-with-coach").performScrollTo().performClick()

        assertEquals(1, replaced)
        assertEquals(
            AdjustmentArgs(9, "2026-11-17", fromDate = "2026-11-17", note = "Coach's review of Ride review: Legs need a lighter day."),
            args,
        )
    }

    // Spec 008 AC8: applied, "In your plan" and no replace; while it runs the button waits
    @Test
    fun anAppliedChangeHasNoReplaceAndARunningOneWaits() {
        var state by mutableStateOf(
            conversationState(conversation(review = REVIEW, planChange = applicable)).copy(tab = ConversationTab.PANEL, replacing = true),
        )
        content { ConversationScreen(state, fmt, now, ConversationActions()) }
        compose.onNodeWithTag("replace-in-plan").performScrollTo().assertIsNotEnabled()

        state = conversationState(conversation(review = REVIEW, planChange = PLAN_CHANGE))
            .copy(tab = ConversationTab.PANEL, message = ConversationMessage.WorkoutReplaced)
        compose.onNodeWithText("The planned workout was replaced.").assertExists()
        compose.onNodeWithTag("in-plan").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("replace-in-plan").assertDoesNotExist()
        compose.onNodeWithTag("adjust-with-coach").assertExists()
    }

    // Spec 008 AC8, AC11: the plan-change errors
    @Test
    fun aRefusedReplacementTellsWhy() {
        showConversation(
            conversationState(conversation(review = REVIEW, planChange = applicable))
                .copy(tab = ConversationTab.PANEL, message = ConversationMessage.Failed(ApiError.Http(409, "PLAN_CHANGE_NOT_APPLICABLE"))),
        )

        compose.onNodeWithText("This workout can no longer be replaced", substring = true).assertIsDisplayed()
    }

    private val plan = Network.json.decodeFromString(TrainingPlan.serializer(), CalendarFixtures.PLAN)

    private fun adjustmentState(
        reasons: Set<String> = emptySet(),
        limits: CoachLimits = this.limits,
        fromSuggestion: Boolean = false,
    ) = AdjustmentFormState(
        today = LocalDate.of(2026, 11, 12),
        loading = false,
        plan = plan,
        reasons = reasons,
        fromSuggestion = fromSuggestion,
        limits = limits,
    )

    // Spec 008 AC1, AC3: the form; Send needs a reason and the credits
    @Test
    fun theAdjustmentFormNeedsAReasonAndCredits() {
        var state by mutableStateOf(adjustmentState())
        val toggled = mutableListOf<String>()
        content {
            AdjustmentFormScreen(
                state,
                fmt,
                object : AdjustmentFormActions {
                    override fun toggleReason(reason: String) {
                        toggled += reason
                        state = state.copy(reasons = state.reasons + reason)
                    }
                },
            )
        }

        compose.onNodeWithText("Adjust the plan: FTP build").assertIsDisplayed()
        compose.onNodeWithTag("from-suggestion").assertDoesNotExist()
        inside("from-date-value", "November 13, 2026").assertExists()
        compose.onNodeWithTag("send-adjustment").performScrollTo().assertIsNotEnabled()

        compose.onNodeWithTag("reason-fatigue").performScrollTo().performClick()
        assertEquals(listOf("FATIGUE"), toggled)
        compose.onNodeWithTag("send-adjustment").performScrollTo().assertIsEnabled()

        state = state.copy(limits = CoachLimits(parseQuota(quota(remaining = 2))))
        compose.onNodeWithTag("send-adjustment").performScrollTo().assertIsNotEnabled()
    }

    // Spec 008 AC2, AC3: from the suggestion its reasons are chosen; the days show when switched on
    @Test
    fun fromTheSuggestionTheReasonsAreChosenAndTheDaysShowWhenSwitchedOn() {
        var state by mutableStateOf(adjustmentState(reasons = setOf("MISSED_WORKOUTS", "DEVIATION"), fromSuggestion = true))
        content {
            AdjustmentFormScreen(
                state,
                fmt,
                object : AdjustmentFormActions {
                    override fun setChangeDays(on: Boolean) {
                        state = state.copy(changeDays = on)
                    }
                },
            )
        }

        compose.onNodeWithTag("from-suggestion").assertIsDisplayed()
        compose.onNodeWithTag("reason-missed_workouts").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("reason-deviation").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("day-monday").assertDoesNotExist()
        compose.onNodeWithTag("change-days").performScrollTo().performClick()
        compose.onNodeWithTag("day-monday").performScrollTo().assertIsDisplayed()
    }

    // Spec 008 AC4, AC9, AC11: errors under Send; a missing plan has no form
    @Test
    fun theFormShowsRefusalsAndAMissingPlan() {
        var state by mutableStateOf(adjustmentState(reasons = setOf("FATIGUE")).copy(error = ApiError.Http(409, "PLAN_ADJUSTMENT_RUNNING")))
        content { AdjustmentFormScreen(state, fmt, object : AdjustmentFormActions {}) }

        compose.onNodeWithTag("adjustment-error").performScrollTo()
        compose.onNodeWithText("The coach is already adjusting this plan", substring = true).assertExists()
        state = state.copy(error = ApiError.Http(409, "PLAN_FINISHED"))
        compose.onNodeWithText("The plan has no workouts left to change.", substring = true).assertExists()

        state = AdjustmentFormState(today = LocalDate.of(2026, 11, 12), loading = false, planMissing = true)
        compose.onNodeWithText("The plan is no longer in your calendar.").assertIsDisplayed()
        compose.onNodeWithTag("send-adjustment").assertDoesNotExist()
    }

    // The menu leads to the ride and the plan
    @Test
    fun theMenuOpensTheRideAndThePlan() {
        val calls = mutableListOf<String>()
        showConversation(
            conversationState(conversation(type = "TRAINING_BLOCK", block = BLOCK, plan = """{"id":4,"outdated":false}""")),
            ConversationActions(onOpenRide = { calls += "ride $it" }, onOpenCalendar = { calls += "plan $it" }),
        )

        compose.onNodeWithTag("conversation-menu").performClick()
        compose.onNodeWithTag("action-open-ride").performClick()
        compose.onNodeWithTag("conversation-menu").performClick()
        compose.onNodeWithTag("action-open-plan").performClick()
        assertEquals(listOf("ride 85", "plan 2026-11-16"), calls)
    }

    // ---- Panels (AC10 - AC12)

    // AC10
    @Test
    fun theReviewPanelShowsTheReviewAndTheChangeTheCoachSuggests() {
        showConversation(
            conversationState(conversation(review = REVIEW, planChange = PLAN_CHANGE)).copy(tab = ConversationTab.PANEL),
        )

        inside("review-session-type", "Sweet spot").assertExists()
        inside("review-estimated-rpe", "6").assertExists()
        inside("review-rider-rpe", "7").assertExists()
        inside("review-summary", "A solid sweet spot session.").assertExists()
        inside("review-assessment", "Felt harder").assertExists()
        inside("review-strengths", "Even pacing").assertExists()
        inside("review-issues", "–").assertExists()
        // Grouped in the order the categories first appear
        inside("recommendation-RECOVERY", "Sleep well").assertExists()
        inside("recommendation-RECOVERY", "Eat carbs").assertExists()
        inside("recommendation-PACING", "Start easier").assertExists()
        inside("suggests-change", "The coach suggests a change on").assertExists()
        compose.onNodeWithTag("planned-tile").performScrollTo()
        inside("planned-tile", "VO2max 5×4").assertExists()
        inside("proposed-tile", "Sweet spot 3×12").assertExists()
        compose.onNodeWithTag("in-plan").performScrollTo().assertIsDisplayed()
    }

    // AC10
    @Test
    fun aKeptPlanShowsTheNextPlannedWorkout() {
        val keep = REVIEW.replace(""""verdict":"CHANGE_WORKOUT"""", """"verdict":"KEEP"""")
        showConversation(conversationState(conversation(review = keep, planChange = PLAN_CHANGE)).copy(tab = ConversationTab.PANEL))

        compose.onNodeWithTag("keeps-plan").performScrollTo()
        inside("keeps-plan", "The coach keeps your plan: Legs need a lighter day.").assertExists()
        inside("planned-tile", "VO2max 5×4").assertExists()
        compose.onNodeWithTag("proposed-tile").assertDoesNotExist()
    }

    // AC10: a tile opens the workout's steps in watts
    @Test
    fun aWorkoutTileOpensTheDetailWithStepsInWatts() {
        content { WorkoutDetailContent(parse(conversation(review = REVIEW)).latestReview!!.nextSession!!, fmt) }

        inside("workout-sheet", "Sweet spot 3×12").assertExists()
        compose.onNodeWithTag("workout-chart").assertExists()
        inside("step-watts-1-0", "238–251 W").assertExists()
        inside("repeat-1", "3").assertExists()
    }

    // AC10
    @Test
    fun tappingATileShowsTheSheet() {
        val withoutAssessment = REVIEW.replace(Regex(""",\s*"planAssessment":\{[^}]*\}"""), "")
        showConversation(conversationState(conversation(review = withoutAssessment)).copy(tab = ConversationTab.PANEL))

        compose.onNodeWithTag("next-session").performScrollTo().performClick()
        compose.onNodeWithTag("workout-sheet").assertExists()
    }

    // AC11
    @Test
    fun theBlockPanelShowsOneWeekAtATime() {
        showConversation(conversationState(conversation(type = "TRAINING_BLOCK", block = BLOCK)).copy(tab = ConversationTab.PANEL))

        inside("block-name", "Build FTP").assertExists()
        inside("block-goal", "Raise FTP").assertExists()
        compose.onNodeWithTag("block-rationale").performClick()
        inside("block-rationale-text", "Sweet spot builds FTP").assertExists()
        inside("week-of", "Week 1 of 2").assertExists()
        inside("week-tss-1", "320 TSS").assertExists()
        // 2026-11-17 is a Tuesday
        compose.onNodeWithTag("block-workout-1-1-0").assertExists()
        compose.onNodeWithTag("week-previous").assertIsNotEnabled()

        compose.onNodeWithTag("week-next").performClick()
        inside("week-of", "Week 2 of 2").assertExists()
        compose.onNodeWithTag("week-recovery-2", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("week-next").assertIsNotEnabled()
    }

    // AC12
    @Test
    fun thePlanActionsFollowThePlansState() {
        var plan by mutableStateOf("null")
        val calls = mutableListOf<String>()
        content {
            ConversationScreen(
                conversationState(conversation(type = "TRAINING_BLOCK", block = BLOCK, plan = plan)).copy(tab = ConversationTab.PANEL),
                fmt,
                now,
                ConversationActions(onSavePlan = { calls += "save" }, onOpenCalendar = { calls += "calendar $it" }),
            )
        }

        compose.onNodeWithTag("add-to-calendar").performScrollTo().performClick()
        plan = """{"id":4,"outdated":true}"""
        compose.onNodeWithTag("update-in-calendar").performScrollTo().performClick()
        inside("outdated-hint", "The coach revised the block after you saved it").assertExists()
        compose.onNodeWithTag("in-calendar").performClick()
        plan = """{"id":4,"outdated":false}"""
        compose.onNodeWithTag("update-in-calendar").assertDoesNotExist()
        assertEquals(listOf("save", "save", "calendar 2026-11-16"), calls)
    }

    // AC12
    @Test
    fun anOverlapAsksBeforeReplacing() {
        var replaced = 0
        showConversation(
            conversationState(conversation(type = "TRAINING_BLOCK", block = BLOCK)).copy(overlap = "Autumn base"),
            ConversationActions(onReplacePlan = { replaced++ }),
        )

        compose.onNodeWithText("Replace the plan “Autumn base” from", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("overlap-replace").performClick()
        assertEquals(1, replaced)
    }

    // ---- Ride (AC13)

    @Test
    fun aRideOffersToAskTheCoachOrToContinue() {
        var state by mutableStateOf(AskCoachState())
        val calls = mutableListOf<String>()
        val actions = RideCoachActions(onAsk = { calls += "ask" }, onOpenConversation = { calls += "open $it" })
        content {
            androidx.compose.foundation.layout.Column {
                RideCoachButton(state, actions)
                RideCoachCard(state, fmt, actions)
            }
        }

        compose.onNodeWithTag("ask-coach").performClick()
        inside("no-review", "No review of this ride yet").assertExists()

        state = AskCoachState(conversationId = 31, review = parse(conversation(review = REVIEW)).latestReview)
        compose.onNodeWithTag("continue-review").performClick()
        inside("card-summary", "A solid sweet spot session.").assertExists()
        inside("card-rpe", "RPE 6 / 7").assertExists()
        compose.onNodeWithTag("open-review").performClick()

        state = state.copy(readOnly = true)
        compose.onNodeWithTag("ask-coach").performClick()
        assertEquals(listOf("ask", "open 31", "open 31", "ask"), calls)
    }

    @Test
    fun theSheetTakesRpeNoteAndLanguageAndShowsTheFreeReview() {
        var state by mutableStateOf(AskCoachState(limits = CoachLimits(parseQuota(quota(remaining = 0))), sheetOpen = true))
        var started = 0
        var free by mutableStateOf(true)
        content {
            AskCoachContent(
                state,
                free,
                fmt,
                RideCoachActions(onChange = { state = it(state) }, onStart = { started++ }),
            )
        }

        inside("rpe-description", "No RPE").assertExists()
        compose.onNodeWithTag("rpe-7").performClick()
        compose.onNodeWithTag("rpe-7").assertIsSelected()
        inside("rpe-description", "7 – very hard").assertExists()
        assertEquals(7, state.rpe)
        compose.onNodeWithTag("rpe-7").performClick()
        assertNull(state.rpe)
        compose.onNodeWithTag("language-pl").performScrollTo().performClick()
        assertEquals("pl", state.language)
        inside("cost-line", "Free – the first review of this ride").assertExists()
        compose.onNodeWithTag("health-note").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ask-start").performScrollTo().performClick()
        assertEquals(1, started)

        free = false
        compose.onNodeWithTag("ask-start").assertIsNotEnabled()
        inside("cost-line", "Not enough AI credits").assertExists()
    }

    // ---- Forms (AC14, AC15, AC20)

    @Test
    fun theBlockFormShowsItsErrorsAfterTheFirstTry() {
        var state by mutableStateOf(TrainingBlockFormState(today = LocalDate.of(2026, 11, 12), limits = limits))
        var starts = 0
        content {
            TrainingBlockFormScreen(state, fmt, {}, { state = it(state) }, { day, change -> state = state.copy(days = state.days.map { if (it.day == day) change(it) else it }) }, { starts++ }, {})
        }

        compose.onNodeWithTag("goal-error").assertDoesNotExist()
        compose.onNodeWithTag("weeks-5").performClick()
        assertEquals(5, state.weeks)
        compose.onNodeWithTag("create-block").performScrollTo().performClick()
        state = state.copy(submitted = true)
        compose.onNodeWithTag("goal").performScrollTo()
        compose.onNodeWithTag("goal-error", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("day-more-tuesday").performScrollTo().performClick()
        assertEquals(75, state.days.first { it.day == java.time.DayOfWeek.TUESDAY }.minutes)
        compose.onNodeWithTag("methodology-polarized").performScrollTo().performClick()
        assertEquals("POLARIZED", state.methodology)
        compose.onNodeWithTag("health-note").performScrollTo().assertIsDisplayed()
        inside("cost-line", "Uses 5 of 25 remaining AI credits").assertExists()
        assertEquals(1, starts)

        state = state.copy(days = state.days.map { it.copy(available = false) })
        compose.onNodeWithTag("no-day-error").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theFileReviewNeedsAFile() {
        var state by mutableStateOf(FileReviewState(limits = limits))
        var chosen = 0
        content { FileReviewScreen(state, fmt, {}, { chosen++ }, { state = it(state) }, {}, {}) }

        compose.onNodeWithTag("start-review").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("choose-file").performScrollTo().performClick()
        assertEquals(1, chosen)

        state = state.copy(fileError = true)
        compose.onNodeWithTag("file-error").assertExists()
        state = state.copy(file = PickedFile("content://ride.fit", "ride.fit"), fileError = false)
        inside("chosen-file-name", "ride.fit").assertExists()
        compose.onNodeWithTag("start-review").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("health-note").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theHealthNoteLinksToTheTerms() {
        var opened = 0
        content { HealthNote(onOpenTerms = { opened++ }) }

        compose.onNodeWithText("The AI coach doesn't replace a doctor", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("health-note-terms").performClick()
        assertEquals(1, opened)
    }

    // ---- Dashboard and shell (AC1, AC18)

    // AC18
    @Test
    fun theDashboardsCoachCardShowsTheNewestConversationsAndItsShortcuts() {
        val calls = mutableListOf<String>()
        var conversations by mutableStateOf(summaries(summary(1, title = "Tempo ride"), summary(2, type = "TRAINING_BLOCK", title = "Build")))
        content {
            DashboardScreen(
                state = DashboardUiState(loading = false, rider = Rider(id = 5, name = "Anna", weightKg = 60.0), conversations = conversations),
                fmt = fmt,
                onRefresh = {},
                onSelectRange = {},
                onOpenWeb = {},
                coach = CoachNavigation(
                    openConversation = { calls += "open $it" },
                    newTrainingBlock = { calls += "block" },
                    reviewFile = { calls += "file" },
                ),
                onOpenCoach = { calls += "all" },
                now = now,
            )
        }

        compose.onNodeWithTag("dashboard-coach").performScrollTo()
        inside("coach-conversation-2", "Build").performClick()
        compose.onNodeWithTag("coach-all").performClick()
        compose.onNodeWithTag("coach-new-block").performScrollTo().performClick()
        compose.onNodeWithTag("coach-review-file").performClick()
        assertEquals(listOf("open 2", "all", "block", "file"), calls)

        conversations = emptyList()
        inside("coach-none", "No conversations yet.").assertExists()
    }

    // AC1: five tabs; a conversation opened from another tab sits on the Coach tab above its list
    @Test
    fun theCoachIsTheFourthTabAndAConversationOpensOnItsStack() {
        content {
            Shell(
                dashboard = { nav -> Button(onClick = { nav.openConversation(12) }, modifier = Modifier.testTag("open-conversation")) { Text("dashboard") } },
                activities = { Text("activities") },
                profile = { Text("profile") },
                coach = { Text("coach list", Modifier.testTag("coach-list-screen")) },
                conversation = { nav -> Button(onClick = nav::back, modifier = Modifier.testTag("conversation-screen")) { Text("conversation") } },
            )
        }

        listOf("Dashboard", "Activities", "Calendar", "Coach", "Profile").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithTag("nav-coach").performClick()
        compose.onNodeWithTag("coach-list-screen").assertIsDisplayed()

        compose.onNodeWithTag("nav-dashboard").performClick()
        compose.onNodeWithTag("open-conversation").performClick()
        compose.onNodeWithTag("conversation-screen").assertIsDisplayed()
        compose.onNodeWithTag("nav-coach").assertIsSelected()
        compose.onNodeWithTag("conversation-screen").performClick()
        compose.onNodeWithTag("coach-list-screen").assertIsDisplayed()

        // Tapping the tab shows the list, not a conversation left open on it
        compose.onNodeWithTag("nav-dashboard").performClick()
        compose.onNodeWithTag("open-conversation").performClick()
        compose.onNodeWithTag("nav-coach").performClick()
        compose.onNodeWithTag("coach-list-screen").assertIsDisplayed()
    }

    /** The conversation route's tab, read from the back stack entry the slot is shown in. */
    @Composable
    private fun ConversationTabOfRoute() {
        val entry = LocalViewModelStoreOwner.current as NavBackStackEntry
        Text("tab ${entry.toRoute<CoachConversationRoute>().tab}", Modifier.testTag("conversation-screen"))
    }

    // Spec 008 AC7: "Show proposal" opens the conversation on the Coach tab, on its proposal tab
    @Test
    fun aProposalOpensOnTheCoachTabWithItsPanel() {
        content {
            Shell(
                dashboard = { Text("dashboard") },
                activities = { Text("activities") },
                profile = { Text("profile") },
                calendar = { nav -> Button(onClick = { nav.openProposal(21) }, modifier = Modifier.testTag("show-proposal")) { Text("calendar") } },
                coach = { Text("coach list", Modifier.testTag("coach-list-screen")) },
                conversation = { ConversationTabOfRoute() },
            )
        }

        compose.onNodeWithTag("nav-calendar").performClick()
        compose.onNodeWithTag("show-proposal").performClick()
        compose.onNodeWithTag("nav-coach").assertIsSelected()
        compose.onNodeWithText("tab PANEL").assertIsDisplayed()
    }

    // Spec 008 AC4: the form opens on the calendar's stack; accepted, its conversation opens on the Coach tab
    @Test
    fun anAcceptedAdjustmentOpensItsConversationOnTheCoachTab() {
        content {
            Shell(
                dashboard = { Text("dashboard") },
                activities = { Text("activities") },
                profile = { Text("profile") },
                calendar = { nav ->
                    Button(onClick = { nav.openAdjustment(AdjustmentArgs(7, "2026-11-12")) }, modifier = Modifier.testTag("adjust")) { Text("calendar") }
                },
                coach = { Text("coach list", Modifier.testTag("coach-list-screen")) },
                conversation = { ConversationTabOfRoute() },
                adjustment = { nav -> Button(onClick = { nav.adjustmentAccepted(40) }, modifier = Modifier.testTag("send")) { Text("form") } },
            )
        }

        compose.onNodeWithTag("nav-calendar").performClick()
        compose.onNodeWithTag("adjust").performClick()
        compose.onNodeWithTag("nav-calendar").assertIsSelected()
        compose.onNodeWithTag("send").performClick()
        compose.onNodeWithTag("nav-coach").assertIsSelected()
        compose.onNodeWithText("tab null").assertIsDisplayed()
        // The calendar's stack no longer holds the form
        compose.onNodeWithTag("nav-calendar").performClick()
        compose.onNodeWithTag("adjust").assertIsDisplayed()
    }
}
