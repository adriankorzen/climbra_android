package app.climbra.android.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.QueryMap
import retrofit2.http.Streaming

/** The training log and a ride (`analysis.RideListController`, `activity.ActivityController`, ... of the backend). */
interface ActivityApi {

    /** cyclingapp spec 052: rows, totals and paging come from the backend. */
    @GET("api/riders/{id}/rides")
    suspend fun rides(
        @Path("id") riderId: Long,
        @Query("from") from: String,
        @Query("to") to: String,
        @Query("q") text: String?,
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): RideListResponse

    @GET("api/activities/{id}")
    suspend fun activity(@Path("id") id: Long): ActivityDetail

    /** The stored analysis, or with [detection] an ad hoc one that is not stored. */
    @GET("api/activities/{id}/analysis")
    suspend fun analysis(@Path("id") id: Long, @QueryMap detection: Map<String, String> = emptyMap()): AnalysisResponse

    @POST("api/activities/{id}/analysis")
    suspend fun reanalyze(@Path("id") id: Long): AnalysisResponse

    @PUT("api/activities/{id}/interval-source")
    suspend fun setIntervalSource(@Path("id") id: Long, @Body request: IntervalSourceRequest): AnalysisResponse

    @GET("api/activities/{id}/recording")
    suspend fun recording(@Path("id") id: Long, @Query("resolution") resolution: Int): Recording

    @Streaming
    @GET("api/activities/{id}/file")
    suspend fun file(@Path("id") id: Long): retrofit2.Response<ResponseBody>

    @DELETE("api/activities/{id}")
    suspend fun delete(@Path("id") id: Long)

    @GET("api/riders/{id}/calendar")
    suspend fun calendar(@Path("id") riderId: Long, @Query("from") from: String, @Query("to") to: String): CalendarResponse

    @PUT("api/riders/{riderId}/planned-workouts/{workoutId}/activity")
    suspend fun linkRide(
        @Path("riderId") riderId: Long,
        @Path("workoutId") workoutId: Long,
        @Body request: LinkRideRequest,
    )
}

// Training log (cyclingapp spec 052)

@Serializable
data class RideListResponse(
    val from: String,
    val to: String,
    val rows: List<RideRow> = emptyList(),
    val page: Int = 0,
    val size: Int = 0,
    val totalRows: Int = 0,
    val totals: RideTotals = RideTotals(),
    val types: List<String> = emptyList(),
    val hasRides: Boolean = true,
)

@Serializable
data class RideRow(
    val id: Long,
    val start: String,
    val date: String,
    val name: String? = null,
    val source: String? = null,
    val type: String? = null,
    val duration: Int? = null,
    val distance: Double? = null,
    val elevation: Double? = null,
    val avgPower: Double? = null,
    val np: Double? = null,
    @SerialName("if") val intensityFactor: Double? = null,
    val tss: Double? = null,
    val tssFromHeartRate: Boolean = false,
    val avgHr: Double? = null,
    val intervals: Int? = null,
    val kj: Double? = null,
)

@Serializable
data class RideTotals(
    val rides: Int = 0,
    val seconds: Long = 0,
    val meters: Double = 0.0,
    val elevation: Double = 0.0,
    val kj: Double = 0.0,
    val tss: Double = 0.0,
)

// A ride

/** Mirrors `activity.ActivityResponse`. */
@Serializable
data class ActivityDetail(
    val id: Long,
    val source: String? = null,
    val name: String? = null,
    val sportType: String? = null,
    val startTime: String,
    val startTimeLocal: String? = null,
    val durationSeconds: Int? = null,
    val distanceMeters: Double? = null,
    val elevationGainMeters: Double? = null,
    val deviceName: String? = null,
    val plannedWorkout: PlannedWorkoutLink? = null,
) {
    /** The ride's local day, as the backend dates it. */
    val day: String get() = (startTimeLocal ?: startTime).take(10)
}

@Serializable
data class PlannedWorkoutLink(
    val id: Long,
    val planId: Long? = null,
    val date: String,
    val title: String,
    val type: String,
    val plannedDurationMinutes: Int = 0,
    val plannedTss: Int = 0,
    val plannedIf: Double = 0.0,
    val matchSource: String? = null,
    val comparison: WorkoutComparison? = null,
)

/** Plan vs. ride, as the backend compares them (cyclingapp spec 010). */
@Serializable
data class WorkoutComparison(
    val level: ComplianceLevel = ComplianceLevel.OK,
    val duration: ComparisonRatio? = null,
    val tss: ComparisonRatio? = null,
    val intensityFactor: ComparisonDelta? = null,
    val intervals: ComparisonIntervals? = null,
    val tssSource: String? = null,
)

@Serializable
enum class ComplianceLevel { OK, MODERATE, LARGE }

/** For the duration, [planned] and [actual] are seconds. */
@Serializable
data class ComparisonRatio(val planned: Double, val actual: Double, val ratio: Double, val level: ComplianceLevel)

@Serializable
data class ComparisonDelta(val planned: Double, val actual: Double, val delta: Double, val level: ComplianceLevel)

@Serializable
data class ComparisonIntervals(
    val plannedRepetitions: Int,
    val detectedBlocks: Int,
    val ratio: Double,
    val level: ComplianceLevel,
)

/** Mirrors `analysis.ActivityAnalysisResponse`. */
@Serializable
data class AnalysisResponse(
    val activityId: Long,
    val stored: Boolean = true,
    val analyzedAt: String? = null,
    val thresholds: ThresholdsUsed = ThresholdsUsed(),
    val analysis: ActivityAnalysis,
)

@Serializable
data class ThresholdsUsed(
    val ftpWatts: Int? = null,
    val lthrBpm: Int? = null,
    val maxHeartRateBpm: Int? = null,
    val targetWatts: Double? = null,
    /** cyclingapp spec 052. */
    val effectiveFrom: String? = null,
)

@Serializable
data class ActivityAnalysis(
    val durationSeconds: Int = 0,
    val distanceMeters: Double? = null,
    val power: PowerMetrics? = null,
    val heartRate: HeartRateMetrics? = null,
    val averageCadence: Double? = null,
    val powerZones: List<ZoneTime> = emptyList(),
    val heartRateZones: List<ZoneTime> = emptyList(),
    /** `LTHR` or `MAX_HR`. */
    val heartRateZoneBasis: String? = null,
    val intervals: Intervals? = null,
)

@Serializable
data class PowerMetrics(
    val averagePower: Double,
    val maxPower: Double,
    val normalizedPower: Double,
    val variabilityIndex: Double? = null,
    val intensityFactor: Double? = null,
    val trainingStressScore: Double? = null,
    val workKilojoules: Double = 0.0,
)

@Serializable
data class HeartRateMetrics(val average: Double, val max: Double, val stressScore: Double? = null)

@Serializable
data class ZoneTime(val number: Int, val name: String, val seconds: Int, val percent: Double)

@Serializable
data class Intervals(
    val detected: Boolean = false,
    val relativeThreshold: Double? = null,
    val durationConsistency: Double? = null,
    val powerConsistency: Double? = null,
    val targetPower: Double? = null,
    /** `AUTO` (the median of the blocks) or `MANUAL`. */
    val targetSource: String? = null,
    val blocks: List<IntervalBlock> = emptyList(),
    val vo2ThresholdBpm: Double? = null,
    val totalVo2StimulusSeconds: Int? = null,
    val cardiacDriftPercent: Double? = null,
    /** `INTERVALS`, `OVER_UNDER`, `REPEATS` or `null`. */
    val structure: String? = null,
    val unders: List<IntervalBlock> = emptyList(),
    val recoveries: List<IntervalBlock> = emptyList(),
    val sets: List<IntervalSet> = emptyList(),
    val lapsAvailable: Boolean = false,
    val useLaps: Boolean = true,
)

@Serializable
data class IntervalBlock(
    val number: Int,
    val startSecond: Int,
    val endSecond: Int,
    val durationSeconds: Int,
    val averagePower: Double,
    val maxPower: Double,
    val percentOfTarget: Double? = null,
    val heartRateStart: Double? = null,
    val heartRateEnd: Double? = null,
    val maxHeartRate: Double? = null,
    val efficiency: Double? = null,
    val vo2StimulusSeconds: Int? = null,
    val setNumber: Int? = null,
)

@Serializable
data class IntervalSet(
    val number: Int,
    /** `OVER_UNDER` or `REPEATS`. */
    val kind: String,
    val startSecond: Int,
    val endSecond: Int,
    val durationSeconds: Int,
    val repetitions: Int,
    val normalizedPower: Double,
    val overAveragePower: Double,
    val underAveragePower: Double,
    val overPercentFtp: Double,
    val underPercentFtp: Double,
    val timeAboveFtpSeconds: Int,
    val heartRateStart: Double? = null,
    val heartRateEnd: Double? = null,
    val maxHeartRate: Double? = null,
)

/** Samples averaged to windows of [resolutionSeconds], `null` where not recorded. */
@Serializable
data class Recording(val resolutionSeconds: Int, val samples: Samples)

@Serializable
data class Samples(
    /** Start of each window in seconds from the start of the ride. */
    val t: List<Int> = emptyList(),
    val power: List<Double?> = emptyList(),
    val heartRate: List<Double?> = emptyList(),
    val cadence: List<Double?> = emptyList(),
    /** m/s */
    val speed: List<Double?> = emptyList(),
    /** m */
    val altitude: List<Double?> = emptyList(),
)

@Serializable
data class IntervalSourceRequest(val useLaps: Boolean)

@Serializable
data class LinkRideRequest(val activityId: Long)
