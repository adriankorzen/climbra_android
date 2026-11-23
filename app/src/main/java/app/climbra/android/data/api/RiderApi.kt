package app.climbra.android.data.api

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** The rider and the figures of the dashboard (`rider.RiderController`, `analysis.*Controller` of the backend). */
interface RiderApi {

    /** The account's riders; the first one is the account's rider (as `rider-context.ts` of the web app). */
    @GET("api/riders")
    suspend fun riders(): List<Rider>

    /** cyclingapp spec 049. */
    @PUT("api/riders/{id}/time-zone")
    suspend fun setTimeZone(@Path("id") riderId: Long, @Body request: TimeZoneRequest)

    /** cyclingapp spec 051: computed by the backend, up to the rider's today. */
    @GET("api/riders/{id}/training-load")
    suspend fun trainingLoad(
        @Path("id") riderId: Long,
        @Query("days") days: Int,
        @Query("weeks") weeks: Int,
    ): TrainingLoadResponse

    /** cyclingapp spec 061: [days] `28`, `90`, `365` or `all`; every figure is the backend's. */
    @GET("api/riders/{id}/power-curve")
    suspend fun powerCurve(@Path("id") riderId: Long, @Query("days") days: String): PowerCurveResponse

    /** cyclingapp spec 051: the latest analysed rides, oldest first. */
    @GET("api/riders/{id}/analyses")
    suspend fun latestAnalyses(@Path("id") riderId: Long, @Query("limit") limit: Int): List<AnalysisSummary>

    @GET("api/riders/{id}/activities")
    suspend fun activities(@Path("id") riderId: Long): List<ActivityRef>
}

/** Mirrors `rider.RiderResponse` as far as the app reads it. */
@Serializable
data class Rider(
    val id: Long,
    val name: String = "",
    val weightKg: Double? = null,
    val restingHeartRateBpm: Int? = null,
    /** The thresholds valid today, `null` without any. */
    val currentThresholds: ThresholdEntry? = null,
    val ftpWattsPerKg: Double? = null,
    /** IANA id of the rider's time zone, which decides what "today" is on the backend. */
    val timeZone: String? = null,
)

/** Mirrors `rider.ThresholdResponse`: one entry of the threshold history. */
@Serializable
data class ThresholdEntry(
    val id: Long = 0,
    /** ISO day the entry is valid from. */
    val effectiveFrom: String = "",
    val ftpWatts: Int? = null,
    val lthrBpm: Int? = null,
    val maxHeartRateBpm: Int? = null,
    /** The entry's own FTP relative to the current weight (cyclingapp spec 053). */
    val ftpWattsPerKg: Double? = null,
    /** The entry's values with omitted ones carried over from older entries (cyclingapp spec 053). */
    val resolved: ThresholdValues = ThresholdValues(),
)

@Serializable
data class ThresholdValues(val ftpWatts: Int? = null, val lthrBpm: Int? = null, val maxHeartRateBpm: Int? = null)

@Serializable
data class TimeZoneRequest(val timeZone: String)

/** Mirrors `analysis.TrainingLoadResponse`; dates are ISO days, changes relative and `null` against a previous 0. */
@Serializable
data class TrainingLoadResponse(
    val today: String,
    val days: List<DailyLoad> = emptyList(),
    val weeks: List<WeekLoad> = emptyList(),
    val last7Days: LoadPeriod = LoadPeriod(),
    val previous7Days: LoadPeriod = LoadPeriod(),
    val changes: LoadChanges = LoadChanges(),
    val fitness: Fitness = Fitness(),
)

@Serializable
data class DailyLoad(val date: String, val tss: Double = 0.0, val ctl: Double = 0.0, val atl: Double = 0.0, val tsb: Double = 0.0)

@Serializable
data class WeekLoad(val weekStart: String, val tss: Double = 0.0, val seconds: Long = 0, val rides: Int = 0)

@Serializable
data class LoadPeriod(val tss: Double = 0.0, val seconds: Long = 0, val meters: Double = 0.0, val rides: Int = 0)

@Serializable
data class LoadChanges(
    val tss: Double? = null,
    val seconds: Double? = null,
    val meters: Double? = null,
    val rides: Double? = null,
)

@Serializable
data class Fitness(
    val ctl: Double = 0.0,
    val atl: Double = 0.0,
    val tsb: Double = 0.0,
    val ctlChange: Double? = null,
    val atlChange: Double? = null,
    /** A range the app does not know yet reads as [FormRange.NEUTRAL]. */
    val form: FormRange = FormRange.NEUTRAL,
)

/** TrainingPeaks style ranges of TSB. */
enum class FormRange { TRANSITION, FRESH, NEUTRAL, OPTIMAL, OVERLOAD }

/** Mirrors `analysis.AnalysisSummary` as far as the app reads it. */
@Serializable
data class AnalysisSummary(
    val activityId: Long,
    val name: String? = null,
    val startTime: String,
    val startTimeLocal: String? = null,
    val durationSeconds: Int = 0,
    val normalizedPower: Double? = null,
    val intensityFactor: Double? = null,
    /** Power TSS when it exists, otherwise hrTSS. */
    val trainingLoad: Double? = null,
    val trainingLoadSource: String? = null,
) {
    val loadFromHeartRate: Boolean
        get() = trainingLoadSource == "HEART_RATE"
}

/** A ride, where only its existence matters. */
@Serializable
data class ActivityRef(val id: Long)
