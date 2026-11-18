package app.climbra.android.data.api

import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The AI coach (spec 006): conversations, ride and file reviews, training blocks, follow-ups, retries, saving a block
 * to the calendar and the AI credits (`coach.*Controller` of the backend, cyclingapp specs 001, 007, 008, 009, 013,
 * 015, 020, 021, 031). Every start, follow-up and retry is answered with `202` and the busy conversation.
 */
interface CoachApi {

    /** Newest first. */
    @GET("api/riders/{id}/coach/conversations")
    suspend fun conversations(@Path("id") riderId: Long): List<CoachConversationSummary>

    /** The raw answer: its `Date` header corrects the elapsed time of a running request. */
    @GET("api/coach/conversations/{id}")
    suspend fun conversation(@Path("id") conversationId: Long): Response<CoachConversation>

    @DELETE("api/coach/conversations/{id}")
    suspend fun deleteConversation(@Path("id") conversationId: Long)

    /** `409 COACH_REVIEW_EXISTS` with the ride's review conversation in `conversationId`. */
    @POST("api/activities/{id}/coach/conversations")
    suspend fun startActivityReview(@Path("id") activityId: Long, @Body request: ActivityReviewRequest): CoachConversation

    @Multipart
    @POST("api/riders/{id}/coach/conversations/file-review")
    suspend fun startFileReview(
        @Path("id") riderId: Long,
        @Part file: MultipartBody.Part,
        @Query("language") language: String,
        @Query("rpe") rpe: Int?,
        @Query("note") note: String?,
    ): CoachConversation

    @POST("api/riders/{id}/coach/conversations/training-block")
    suspend fun startTrainingBlock(@Path("id") riderId: Long, @Body request: TrainingBlockRequest): CoachConversation

    /** A follow-up, multipart with an optional FIT / ZIP file. */
    @Multipart
    @POST("api/coach/conversations/{id}/messages")
    suspend fun sendMessage(
        @Path("id") conversationId: Long,
        @Part("text") text: RequestBody,
        @Part file: MultipartBody.Part?,
    ): CoachConversation

    /** Runs the failed request again. */
    @POST("api/coach/conversations/{id}/retry")
    suspend fun retry(@Path("id") conversationId: Long): CoachConversation

    /** Saves the latest training block to the calendar or updates its plan; `409 PLAN_OVERLAP` with `overlappingPlan`. */
    @POST("api/coach/conversations/{id}/plan")
    suspend fun savePlan(@Path("id") conversationId: Long, @Body request: SavePlanRequest)

    /** Asks the coach to adjust the rest of a plan (spec 008); `202` with the `PLAN_ADJUSTMENT` conversation. */
    @POST("api/riders/{riderId}/plans/{planId}/adjustments")
    suspend fun requestAdjustment(
        @Path("riderId") riderId: Long,
        @Path("planId") planId: Long,
        @Body request: PlanAdjustmentRequest,
    ): CoachConversation

    @GET("api/riders/{riderId}/plans/{planId}/proposals/{proposalId}")
    suspend fun proposal(@Path("riderId") riderId: Long, @Path("planId") planId: Long, @Path("proposalId") proposalId: Long): PlanProposal

    /** `409 PROPOSAL_OUTDATED` when the plan changed after the proposal was made. */
    @POST("api/riders/{riderId}/plans/{planId}/proposals/{proposalId}/accept")
    suspend fun acceptProposal(@Path("riderId") riderId: Long, @Path("planId") planId: Long, @Path("proposalId") proposalId: Long)

    @POST("api/riders/{riderId}/plans/{planId}/proposals/{proposalId}/reject")
    suspend fun rejectProposal(@Path("riderId") riderId: Long, @Path("planId") planId: Long, @Path("proposalId") proposalId: Long)

    /**
     * Replaces the planned workout with the next session of the latest review (cyclingapp spec 020), no model call;
     * `409 REVIEW_NO_PLAN_CHANGE`, `PLAN_CHANGE_NOT_APPLICABLE` or `CONVERSATION_BUSY`.
     */
    @POST("api/coach/conversations/{id}/review/plan-change")
    suspend fun applyReviewPlanChange(@Path("id") conversationId: Long): ReviewPlanChange

    @GET("api/account/coach-quota")
    suspend fun coachQuota(): CoachQuota
}

/** Conversation types (`ConversationType` of the web); unknown ones are shown humanized. */
object ConversationType {
    const val ACTIVITY_REVIEW = "ACTIVITY_REVIEW"
    const val FILE_REVIEW = "FILE_REVIEW"
    const val TRAINING_BLOCK = "TRAINING_BLOCK"
    const val PLAN_ADJUSTMENT = "PLAN_ADJUSTMENT"
}

/** Mirrors `coach.CoachConversationSummary`. */
@Serializable
data class CoachConversationSummary(
    val id: Long,
    val type: String = ConversationType.ACTIVITY_REVIEW,
    val title: String = "",
    val activityId: Long? = null,
    val language: String? = null,
    val createdAt: String = "",
    val lastMessageAt: String? = null,
    /** `READY`, `BUSY` or `FAILED`. */
    val status: String = STATUS_READY,
    val readOnly: Boolean = false,
    /** The plan a `PLAN_ADJUSTMENT` conversation adjusts. */
    val planId: Long? = null,
) {
    companion object {
        const val STATUS_READY = "READY"
        const val STATUS_BUSY = "BUSY"
        const val STATUS_FAILED = "FAILED"
    }
}

/** Mirrors `coach.CoachConversationResponse`. */
@Serializable
data class CoachConversation(
    val id: Long,
    val riderId: Long? = null,
    val type: String = ConversationType.ACTIVITY_REVIEW,
    val provider: String? = null,
    val model: String? = null,
    val activityId: Long? = null,
    val title: String = "",
    val language: String? = null,
    val createdAt: String = "",
    val updatedAt: String? = null,
    /** A request to the model is running. */
    val busy: Boolean = false,
    /** It used a retired provider or ride data files and can't continue (cyclingapp spec 011). */
    val readOnly: Boolean = false,
    val messages: List<CoachMessage> = emptyList(),
    val latestReview: ActivityReview? = null,
    val latestTrainingBlock: TrainingBlock? = null,
    /** The plan saved from the training block, `null` when none. */
    val plan: ConversationPlan? = null,
    /** The plan a `PLAN_ADJUSTMENT` conversation adjusts. */
    val planId: Long? = null,
    val latestProposal: LatestProposal? = null,
    /** The plan change of the latest review (cyclingapp spec 020). */
    val reviewPlanChange: ReviewPlanChange? = null,
    val meta: CoachMeta? = null,
    /** The request waiting for the model, or failed (cyclingapp spec 008). */
    val pendingRequest: PendingCoachRequest? = null,
)

@Serializable
data class CoachMessage(
    val id: Long,
    val sequence: Int = 0,
    /** `USER` or `ASSISTANT`. */
    val role: String = ROLE_USER,
    val text: String? = null,
    val files: List<String> = emptyList(),
    val createdAt: String = "",
    /** An answer that carries a training block (cyclingapp spec 021). */
    val trainingBlock: Boolean = false,
    /** An answer that refers the rider to a doctor (cyclingapp spec 031). */
    val medicalReferral: Boolean = false,
) {
    companion object {
        const val ROLE_USER = "USER"
        const val ROLE_ASSISTANT = "ASSISTANT"
    }
}

@Serializable
data class CoachMeta(
    val provider: String? = null,
    val model: String? = null,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val durationMs: Long = 0,
)

/** A request answered in the background (cyclingapp spec 008). */
@Serializable
data class PendingCoachRequest(
    /** `START` or `MESSAGE`. */
    val kind: String = KIND_START,
    /** `QUEUED`, `RUNNING` or `FAILED`. */
    val status: String = STATUS_RUNNING,
    /** The follow-up text, `null` for a start. */
    val text: String? = null,
    val fileNames: List<String> = emptyList(),
    val createdAt: String? = null,
    val startedAt: String? = null,
    val failedAt: String? = null,
    /** `PROVIDER_ERROR`, `PROVIDER_QUOTA`, `REFUSED`, `INVALID_ANSWER`, `TIMEOUT`, `INTERRUPTED`, … */
    val errorCode: String? = null,
) {
    val failed: Boolean get() = status == STATUS_FAILED

    companion object {
        const val KIND_START = "START"
        const val KIND_MESSAGE = "MESSAGE"
        const val STATUS_QUEUED = "QUEUED"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_FAILED = "FAILED"
    }
}

/** A workout the coach proposes; the steps carry watts and zones from the backend. */
@Serializable
data class PlannedWorkout(
    val date: String = "",
    val title: String = "",
    val type: String? = null,
    val indoor: Boolean = false,
    val plannedDurationMinutes: Int = 0,
    val plannedTss: Int = 0,
    val plannedIf: Double = 0.0,
    val targetRpe: Int? = null,
    val description: String? = null,
    val blocks: List<WorkoutBlock> = emptyList(),
) {
    val rest: Boolean get() = type == "REST"
}

@Serializable
data class ActivityReview(
    val summary: String = "",
    val sessionType: String? = null,
    val estimatedRpe: Int? = null,
    val riderRpe: Int? = null,
    val rpeAssessment: String? = null,
    val strengths: List<String> = emptyList(),
    val issues: List<String> = emptyList(),
    val recommendations: List<Recommendation> = emptyList(),
    val nextSession: PlannedWorkout? = null,
    /** The coach's verdict on the plan (cyclingapp spec 020); absent in older reviews. */
    val planAssessment: PlanAssessment? = null,
)

/** [category] is `PACING`, `INTENSITY`, `VOLUME`, `RECOVERY`, `NUTRITION`, `TECHNIQUE` or `OTHER`. */
@Serializable
data class Recommendation(val category: String = "OTHER", val text: String = "")

/** [verdict] is `KEEP`, `CHANGE_WORKOUT` or `ADJUST_PLAN`. */
@Serializable
data class PlanAssessment(val verdict: String = VERDICT_KEEP, val workoutDate: String? = null, val reason: String = "") {
    companion object {
        const val VERDICT_KEEP = "KEEP"
    }
}

/** The plan change of a review computed from the plan as it is now; [status] is `APPLIED`, `APPLICABLE` or `NOT_APPLICABLE`. */
@Serializable
data class ReviewPlanChange(
    val planId: Long? = null,
    val workoutDate: String? = null,
    val verdict: String? = null,
    val status: String? = null,
    val plannedWorkout: PlannedWorkout? = null,
) {
    companion object {
        const val STATUS_APPLIED = "APPLIED"
        const val STATUS_APPLICABLE = "APPLICABLE"
        const val VERDICT_ADJUST_PLAN = "ADJUST_PLAN"
    }
}

/** The reasons of an adjustment, in the web's order (`ADJUSTMENT_REASONS`). */
val ADJUSTMENT_REASONS = listOf("FATIGUE", "ILLNESS", "LACK_OF_TIME", "MISSED_WORKOUTS", "DEVIATION", "OTHER")

/** Mirrors the web's `PlanAdjustmentRequest` (cyclingapp spec 010); `availability: null` keeps the plan's days. */
@Serializable
data class PlanAdjustmentRequest(
    val reasons: List<String>,
    val fatigue: Int?,
    val note: String?,
    val fromDate: String?,
    val availability: Availability?,
    val includeSuggestion: Boolean,
    val language: String,
)

/** Mirrors `plan.PlanProposalResponse`: the coach's adjustment of a plan, computed by the backend. */
@Serializable
data class PlanProposal(
    val id: Long,
    val planId: Long,
    val conversationId: Long? = null,
    /** `PENDING`, `ACCEPTED`, `REJECTED` or `OUTDATED`. */
    val status: String = STATUS_PENDING,
    val createdAt: String? = null,
    val fromDate: String,
    val summary: String? = null,
    val days: List<ProposalDay> = emptyList(),
    val weeks: List<ProposalWeek> = emptyList(),
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
    }
}

/** [change] is `ADDED`, `REMOVED`, `CHANGED` or `UNCHANGED`; a [completed] day stays as it is. */
@Serializable
data class ProposalDay(
    val date: String,
    val change: String = CHANGE_UNCHANGED,
    val before: PlannedWorkout? = null,
    val after: PlannedWorkout? = null,
    val completed: Boolean = false,
) {
    companion object {
        const val CHANGE_UNCHANGED = "UNCHANGED"
    }
}

@Serializable
data class ProposalWeek(
    val weekNumber: Int,
    val plannedTssBefore: Int? = null,
    val plannedTssAfter: Int? = null,
    val focusBefore: String? = null,
    val focusAfter: String? = null,
)

@Serializable
data class TrainingWeek(
    val weekNumber: Int = 0,
    val focus: String? = null,
    val plannedTss: Int = 0,
    val isRecoveryWeek: Boolean = false,
    val workouts: List<PlannedWorkout> = emptyList(),
)

@Serializable
data class TrainingBlock(
    val name: String = "",
    val goal: String? = null,
    val startDate: String = "",
    val endDate: String = "",
    /** `AUTO`, `POLARIZED`, `PYRAMIDAL` or `SWEET_SPOT`. */
    val methodology: String? = null,
    val rationale: String? = null,
    val weeks: List<TrainingWeek> = emptyList(),
)

/** The plan saved from a training block; [outdated] when the latest block is newer than the save. */
@Serializable
data class ConversationPlan(val id: Long, val updatedAt: String? = null, val outdated: Boolean = false)

@Serializable
data class LatestProposal(val id: Long, val status: String? = null)

/** [language] is `pl` or `en`. */
@Serializable
data class ActivityReviewRequest(val rpe: Int?, val note: String?, val language: String)

/** Mirrors the web's `TrainingBlockRequest`; day names are `MONDAY` … `SUNDAY`. */
@Serializable
data class TrainingBlockRequest(
    val startDate: String,
    val weeks: Int,
    val goal: String,
    val goalEventDate: String?,
    val availability: Availability,
    val indoorDays: List<String>,
    val methodology: String,
    val notes: String?,
    val language: String,
)

/** Minutes per available weekday; missing days are unavailable. */
@Serializable
data class Availability(val days: Map<String, Int>, val longRideDay: String?)

@Serializable
data class SavePlanRequest(val replaceOverlapping: Boolean)

/** Mirrors `coach.CoachQuotaResponse`: the account's AI credits (cyclingapp specs 007, 013, 029). */
@Serializable
data class CoachQuota(
    /** `FREE` or `SUBSCRIPTION`. */
    val plan: String = "FREE",
    /** Credits of the current monthly grant. */
    val limit: Int = 0,
    val used: Int = 0,
    /** The whole balance, monthly and bonus credits. */
    val remaining: Int = 0,
    val costs: CoachCosts = CoachCosts(),
    /** Start of the next monthly period, `null` without a monthly grant. */
    val resetsAt: String? = null,
    val unlimited: Boolean = false,
    val monthly: MonthlyCredits? = null,
    /** Earliest expiry first. */
    val bonus: List<BonusCredits> = emptyList(),
    val subscription: CreditSubscription? = null,
)

/** Credits a request of each kind uses (cyclingapp spec 013). */
@Serializable
data class CoachCosts(
    /** Start of a ride review that is not free, and a file review. */
    val review: Int = 1,
    /** Follow-up in a ride or file review conversation. */
    val reviewMessage: Int = 1,
    val trainingBlock: Int = 1,
    val planAdjustment: Int = 1,
    /** Follow-up in a training block or plan adjustment conversation. */
    val blockMessage: Int = 1,
)

@Serializable
data class MonthlyCredits(val limit: Int = 0, val used: Int = 0, val remaining: Int = 0, val resetsAt: String? = null)

@Serializable
data class BonusCredits(val remaining: Int = 0, val expiresAt: String? = null)

/** [status] is `ACTIVE` or `CANCELED`. */
@Serializable
data class CreditSubscription(val status: String = "ACTIVE", val currentPeriodEnd: String? = null)
