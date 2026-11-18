package app.climbra.android.data.api

import kotlinx.serialization.Serializable
import retrofit2.http.POST
import retrofit2.http.Path

/** The owner's Garmin Personal API (spec 011, cyclingapp spec 006); role `ADMIN` only. */
interface GarminApi {
    /** Answers when the sync is done, from the latest imported activity on: it may take long. */
    @POST("api/riders/{riderId}/garmin/sync")
    suspend fun sync(@Path("riderId") riderId: Long): GarminSyncResult
}

/** Mirrors `garmin.GarminSyncResult`: [since] is the ISO date the sync started from. */
@Serializable
data class GarminSyncResult(val since: String? = null, val fetched: Int = 0, val imported: Int = 0)
