package app.climbra.android.data.api

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * The profile (spec 004): rider data, threshold history, zones, the intervals.icu and Garmin Connect connections and
 * the AI credits (`rider.*`, `intervals.IntervalsController`, `garminconnect.GarminConnectController`,
 * `coach.CoachQuotaController` of the backend).
 */
interface ProfileApi {

    /** The account's rider profile; `409` when it has one already. */
    @POST("api/riders")
    suspend fun createRider(@Body request: RiderRequest): Rider

    @PUT("api/riders/{id}")
    suspend fun updateRider(@Path("id") riderId: Long, @Body request: RiderRequest): Rider

    /** Newest first. */
    @GET("api/riders/{id}/thresholds")
    suspend fun thresholds(@Path("id") riderId: Long): List<ThresholdEntry>

    /** Omitted values are carried over from the entry valid before; `409` for a second entry on one day. */
    @POST("api/riders/{id}/thresholds")
    suspend fun createThreshold(@Path("id") riderId: Long, @Body request: ThresholdRequest): ThresholdChange

    /** Replaces the entry: omitted values become empty. */
    @PUT("api/riders/{id}/thresholds/{thresholdId}")
    suspend fun updateThreshold(
        @Path("id") riderId: Long,
        @Path("thresholdId") thresholdId: Long,
        @Body request: ThresholdRequest,
    ): ThresholdChange

    @DELETE("api/riders/{id}/thresholds/{thresholdId}")
    suspend fun deleteThreshold(@Path("id") riderId: Long, @Path("thresholdId") thresholdId: Long): ThresholdChange

    /** The zones of the rider's today. */
    @GET("api/riders/{id}/zones")
    suspend fun zones(@Path("id") riderId: Long): Zones

    @GET("api/riders/{id}/intervals")
    suspend fun intervals(@Path("id") riderId: Long): IntervalsConnection

    /** `422` when intervals.icu refuses the key, `503` when the server has no intervals.icu integration. */
    @PUT("api/riders/{id}/intervals")
    suspend fun connectIntervals(@Path("id") riderId: Long, @Body request: IntervalsConnectRequest): IntervalsConnection

    @DELETE("api/riders/{id}/intervals")
    suspend fun disconnectIntervals(@Path("id") riderId: Long)

    /** Starts a sync as a background import job, followed with [importJob]. */
    @POST("api/riders/{id}/intervals/sync")
    suspend fun syncIntervals(@Path("id") riderId: Long): ImportJob

    @GET("api/riders/{id}/intervals/thresholds")
    suspend fun intervalsThresholds(@Path("id") riderId: Long): IntervalsThresholds

    @GET("api/imports/{id}")
    suspend fun importJob(@Path("id") jobId: Long): ImportJob

    @GET("api/riders/{id}/garmin-connection")
    suspend fun garminConnection(@Path("id") riderId: Long): GarminConnection

    @DELETE("api/riders/{id}/garmin-connection")
    suspend fun disconnectGarmin(@Path("id") riderId: Long)

    @GET("api/account/coach-quota")
    suspend fun coachQuota(): CoachQuota
}

/** Mirrors `rider.RiderRequest`; [timeZone] only when creating the rider (cyclingapp spec 049). */
@Serializable
data class RiderRequest(
    val name: String,
    val weightKg: Double,
    val restingHeartRateBpm: Int? = null,
    val timeZone: String? = null,
)

/** Mirrors `rider.ThresholdRequest`; [effectiveFrom] is an ISO day. */
@Serializable
data class ThresholdRequest(
    val effectiveFrom: String,
    val ftpWatts: Int? = null,
    val lthrBpm: Int? = null,
    val maxHeartRateBpm: Int? = null,
)

/** Mirrors `rider.ThresholdChangeResponse` (cyclingapp spec 053). */
@Serializable
data class ThresholdChange(
    /** `null` after a deletion. */
    val threshold: ThresholdEntry? = null,
    /** Analyses the backend recalculated at once because of the change. */
    val reanalysed: Int = 0,
)

/** Mirrors `zone.ZonesResponse`. */
@Serializable
data class Zones(
    val thresholdsEffectiveFrom: String? = null,
    val ftpWatts: Int? = null,
    val lthrBpm: Int? = null,
    val maxHeartRateBpm: Int? = null,
    val powerZones: List<TrainingZone> = emptyList(),
    /** `LTHR` or `MAX_HR`, `null` without heart rate zones. */
    val heartRateZoneBasis: String? = null,
    val heartRateZones: List<TrainingZone> = emptyList(),
)

/** A zone with its range in percent of the threshold and in W or bpm; `null` maxima for the open last zone. */
@Serializable
data class TrainingZone(
    val number: Int,
    val name: String = "",
    val minPercent: Int = 0,
    val maxPercent: Int? = null,
    val min: Int = 0,
    val max: Int? = null,
)

/** Mirrors `intervals.IntervalsConnectionResponse`; the API key itself is never returned. */
@Serializable
data class IntervalsConnection(
    val connected: Boolean = false,
    val athleteId: String? = null,
    val athleteName: String? = null,
    /** Last characters of the API key. */
    val keyHint: String? = null,
    val connectedAt: String? = null,
    val lastSyncAt: String? = null,
)

@Serializable
data class IntervalsConnectRequest(val athleteId: String? = null, val apiKey: String)

/** Mirrors `intervals.IntervalsThresholdsResponse`: a proposal for the threshold history. */
@Serializable
data class IntervalsThresholds(
    val ftpWatts: Int? = null,
    val indoorFtpWatts: Int? = null,
    val lthrBpm: Int? = null,
    val maxHeartRateBpm: Int? = null,
    val differsFromCurrent: Boolean = false,
)

/** Mirrors `upload.ActivityImportJobResponse` as far as the app reads it. */
@Serializable
data class ImportJob(
    val id: Long,
    /** Unknown values coerce to this default, which ends the polling. */
    val status: ImportJobStatus = ImportJobStatus.FAILED,
    val imported: Int = 0,
    val unavailable: Int = 0,
    val message: String? = null,
) {
    val running: Boolean
        get() = status == ImportJobStatus.QUEUED || status == ImportJobStatus.RUNNING
}

/** A status the app does not know yet reads as [FAILED]. */
@Serializable
enum class ImportJobStatus { QUEUED, RUNNING, COMPLETED, FAILED }

/** Mirrors `garminconnect.GarminConnectionResponse` as far as the app reads it. */
@Serializable
data class GarminConnection(
    /** The feature is configured on the server; when not, the app shows nothing of it. */
    val available: Boolean = false,
    val status: GarminStatus = GarminStatus.NOT_CONNECTED,
    val connectedAt: String? = null,
    /** Workouts from today on that are on Garmin. */
    val sent: Int = 0,
    /** Of them, those that differ from the plan. */
    val stale: Int = 0,
    /** Sendable workouts of the 2 weeks of an import, without a connection too (spec 005). */
    val sendable: Int = 0,
    /** The first and last day of the 2 weeks of an import. */
    val from: String? = null,
    val to: String? = null,
)

/** A status the app does not know yet reads as [NOT_CONNECTED]. */
@Serializable
enum class GarminStatus { NOT_CONNECTED, CONNECTED, NO_PERMISSION, NEEDS_RECONNECT }

/** Mirrors `coach.CoachQuotaResponse` as far as the profile shows it (cyclingapp specs 013, 029). */
@Serializable
data class CoachQuota(
    /** `FREE` or `SUBSCRIPTION`. */
    val plan: String = "FREE",
    val limit: Int = 0,
    val used: Int = 0,
    /** The whole balance, monthly and bonus credits. */
    val remaining: Int = 0,
    val unlimited: Boolean = false,
    val monthly: MonthlyCredits? = null,
    /** Earliest expiry first. */
    val bonus: List<BonusCredits> = emptyList(),
    val subscription: CreditSubscription? = null,
)

@Serializable
data class MonthlyCredits(val limit: Int = 0, val used: Int = 0, val remaining: Int = 0, val resetsAt: String? = null)

@Serializable
data class BonusCredits(val remaining: Int = 0, val expiresAt: String? = null)

/** [status] is `ACTIVE` or `CANCELED`. */
@Serializable
data class CreditSubscription(val status: String = "ACTIVE", val currentPeriodEnd: String? = null)
