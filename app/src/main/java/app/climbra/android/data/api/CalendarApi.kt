package app.climbra.android.data.api

import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * The calendar (spec 005): plans, planned workouts and rides of a week, the actions on a workout and a plan, sending
 * to Garmin Connect and the Zwift file (`plan.PlanController`, `garminconnect.GarminConnectController`,
 * `zwift.ZwiftController` of the backend).
 */
interface CalendarApi {

    /** At most 100 days; [from] and [to] are ISO dates. */
    @GET("api/riders/{id}/calendar")
    suspend fun calendar(@Path("id") riderId: Long, @Query("from") from: String, @Query("to") to: String): CalendarResponse

    @DELETE("api/riders/{riderId}/plans/{planId}")
    suspend fun deletePlan(@Path("riderId") riderId: Long, @Path("planId") planId: Long)

    @POST("api/riders/{riderId}/plans/{planId}/suggestion/dismiss")
    suspend fun dismissSuggestion(@Path("riderId") riderId: Long, @Path("planId") planId: Long)

    @PUT("api/riders/{riderId}/planned-workouts/{workoutId}/activity")
    suspend fun linkRide(
        @Path("riderId") riderId: Long,
        @Path("workoutId") workoutId: Long,
        @Body request: LinkRideRequest,
    ): CalendarWorkout

    @DELETE("api/riders/{riderId}/planned-workouts/{workoutId}/activity")
    suspend fun unlinkRide(@Path("riderId") riderId: Long, @Path("workoutId") workoutId: Long): CalendarWorkout

    @PUT("api/riders/{riderId}/planned-workouts/{workoutId}/skipped")
    suspend fun skip(
        @Path("riderId") riderId: Long,
        @Path("workoutId") workoutId: Long,
        @Body request: SkipRequest,
    ): CalendarWorkout

    @GET("api/riders/{id}/garmin-connection")
    suspend fun garminConnection(@Path("id") riderId: Long): GarminConnection

    /** Only workouts that can be sent are listed. */
    @GET("api/riders/{id}/garmin-connection/workouts")
    suspend fun garminWorkouts(
        @Path("id") riderId: Long,
        @Query("from") from: String,
        @Query("to") to: String,
    ): GarminWorkoutStatuses

    /** The planned workouts of 2 weeks from today; what is out of date from today on is removed. */
    @POST("api/riders/{id}/garmin-connection/send")
    suspend fun sendPlanToGarmin(@Path("id") riderId: Long): GarminSendResult

    @POST("api/riders/{riderId}/planned-workouts/{workoutId}/garmin")
    suspend fun sendWorkoutToGarmin(@Path("riderId") riderId: Long, @Path("workoutId") workoutId: Long): GarminSendResult

    /** The workout as a `.zwo` file for Zwift (cyclingapp spec 048). */
    @Streaming
    @GET("api/riders/{riderId}/planned-workouts/{workoutId}/zwo")
    suspend fun zwo(@Path("riderId") riderId: Long, @Path("workoutId") workoutId: Long): retrofit2.Response<ResponseBody>
}

/** Mirrors `plan.CalendarResponse` (cyclingapp specs 009, 010, 054). */
@Serializable
data class CalendarResponse(
    val from: String = "",
    val to: String = "",
    val plans: List<TrainingPlan> = emptyList(),
    val plannedWorkouts: List<CalendarWorkout> = emptyList(),
    val activities: List<CalendarRide> = emptyList(),
    /** Every Monday-Sunday week of the range with its figures (cyclingapp spec 054). */
    val weeks: List<CalendarWeek> = emptyList(),
)

/** A planned workout; the figures, [status] and [comparison] are the backend's. */
@Serializable
data class CalendarWorkout(
    val id: Long,
    val planId: Long? = null,
    val date: String,
    val title: String,
    val type: String? = null,
    val indoor: Boolean = false,
    val plannedDurationMinutes: Int = 0,
    val plannedTss: Int = 0,
    val plannedIf: Double = 0.0,
    val targetRpe: Int? = null,
    val description: String? = null,
    val blocks: List<WorkoutBlock> = emptyList(),
    /** `PLANNED`, `COMPLETED`, `MISSED`, `SKIPPED` or `REST`, computed for today. */
    val status: String? = null,
    /** The linked ride, `null` when none. */
    val activityId: Long? = null,
    /** `AUTO` or `MANUAL`. */
    val matchSource: String? = null,
    val comparison: WorkoutComparison? = null,
) {
    val rest: Boolean get() = status == STATUS_REST || type == STATUS_REST

    /** A rest day or a workout without steps has nothing to ride (cyclingapp spec 048). */
    val hasSteps: Boolean get() = !rest && blocks.any { it.steps.isNotEmpty() }

    companion object {
        const val STATUS_REST = "REST"
        const val STATUS_SKIPPED = "SKIPPED"
    }
}

/** Steps repeated [repetitions] times. */
@Serializable
data class WorkoutBlock(val repetitions: Int = 1, val steps: List<WorkoutStep> = emptyList())

/**
 * A step; [powerLowWatts] and [powerHighWatts] are resolved by the backend with the current FTP (absent without FTP),
 * [zone] is the power zone of the upper target (cyclingapp spec 054).
 */
@Serializable
data class WorkoutStep(
    /** `WARMUP`, `WORK`, `RECOVERY`, `COOLDOWN` or `STEADY`. */
    val kind: String,
    val durationSeconds: Int = 0,
    val powerLowPctFtp: Int = 0,
    val powerHighPctFtp: Int = 0,
    val powerLowWatts: Int? = null,
    val powerHighWatts: Int? = null,
    val cadenceLow: Int? = null,
    val cadenceHigh: Int? = null,
    val note: String? = null,
    val zone: Int? = null,
)

/** A ride of the calendar with the key metrics of its analysis. */
@Serializable
data class CalendarRide(
    val activityId: Long,
    val date: String,
    val name: String? = null,
    val sportType: String? = null,
    val durationSeconds: Int? = null,
    /** Power TSS, else hrTSS (cyclingapp spec 027). */
    val trainingLoad: Double? = null,
    /** `POWER` or `HEART_RATE`. */
    val trainingLoadSource: String? = null,
    /** The workout the ride is linked to, `null` for an unplanned ride. */
    val plannedWorkoutId: Long? = null,
)

/** Planned vs. ridden load of a calendar week, from the backend (cyclingapp spec 054). */
@Serializable
data class CalendarWeek(
    /** Monday. */
    val start: String,
    val plannedTss: Int = 0,
    val plannedSeconds: Int = 0,
    val riddenTss: Int = 0,
    val riddenSeconds: Int = 0,
    /** The plan week with most days in this week, `null` outside plans. */
    val planWeek: CalendarPlanWeek? = null,
)

@Serializable
data class CalendarPlanWeek(
    val planId: Long,
    val weekNumber: Int,
    val focus: String? = null,
    val recoveryWeek: Boolean = false,
)

/** Mirrors `plan.TrainingPlanResponse` as far as the calendar shows it. */
@Serializable
data class TrainingPlan(
    val id: Long,
    val name: String,
    val goal: String? = null,
    /** `AUTO`, `POLARIZED`, `PYRAMIDAL` or `SWEET_SPOT`. */
    val methodology: String? = null,
    val startDate: String,
    val endDate: String,
    /** The open suggestion to adjust the plan (cyclingapp spec 010). */
    val suggestion: PlanSuggestion? = null,
    /** The coach's proposal waiting for a decision. */
    val pendingProposal: PendingProposal? = null,
    /** The coach is adjusting the plan. */
    val adjustmentRunning: Boolean = false,
)

@Serializable
data class PlanSuggestion(val id: Long, val reasons: List<SuggestionReason> = emptyList())

/**
 * Why the plan needs an adjustment. [metric] is `duration` (seconds), `tss`, `intensityFactor`, `intervals` or
 * `count`.
 */
@Serializable
data class SuggestionReason(
    /** `WORKOUT_DEVIATION`, `KEY_WORKOUT_MISSED`, `WORKOUTS_MISSED`, `UNPLANNED_LOAD` or `WEEK_LOAD_DEVIATION`. */
    val code: String,
    val date: String,
    val plannedWorkoutId: Long? = null,
    val activityId: Long? = null,
    val weekNumber: Int? = null,
    val metric: String? = null,
    val planned: Double? = null,
    val actual: Double? = null,
)

@Serializable
data class PendingProposal(val id: Long, val fromDate: String)

@Serializable
data class SkipRequest(val skipped: Boolean)

/** Only workouts that can be sent. */
@Serializable
data class GarminWorkoutStatuses(val workouts: List<GarminWorkoutStatus> = emptyList())

/** [status] is `SENT`, `OUTDATED` or `NOT_SENT`. */
@Serializable
data class GarminWorkoutStatus(val workoutId: Long, val status: String)

/** What a send changed on Garmin Connect. */
@Serializable
data class GarminSendResult(
    val sent: Int = 0,
    val unchanged: Int = 0,
    val removed: Int = 0,
    val failed: List<GarminSendFailure> = emptyList(),
    /** The send stopped before the end; sending again continues. */
    val incomplete: Boolean = false,
    /** `GARMIN_RATE_LIMITED` or `GARMIN_UNAVAILABLE`. */
    val reason: String? = null,
)

/** [title] is `null` for a workout that is on Garmin but no longer in the plan. */
@Serializable
data class GarminSendFailure(val date: String, val title: String? = null, val code: String? = null)
