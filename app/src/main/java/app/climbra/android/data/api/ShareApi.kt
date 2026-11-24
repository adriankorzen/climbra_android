package app.climbra.android.data.api

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

// Mirror `share.ShareDtos` of the backend (cyclingapp spec 062) and `ShareRequest` / `ShareLink` / `ShareCreated` of
// the web

/** The rider's public links of a ride or a week (spec 028). */
interface ShareApi {

    /** A new link; its [ShareCreated.url] is answered only here. */
    @POST("api/riders/{id}/shares")
    suspend fun create(@Path("id") riderId: Long, @Body request: ShareRequest): ShareCreated

    /** The links that still work, newest first. */
    @GET("api/riders/{id}/shares")
    suspend fun active(@Path("id") riderId: Long): List<ShareLink>

    /** Makes a link stop working; also `204` when it already ended. */
    @DELETE("api/riders/{id}/shares/{shareId}")
    suspend fun revoke(@Path("id") riderId: Long, @Path("shareId") shareId: Long)
}

object ShareKind {
    const val RIDE = "RIDE"
    const val WEEK = "WEEK"
}

@Serializable
data class ShareRequest(
    /** [ShareKind]. */
    val kind: String,
    val activityId: Long? = null,
    /** The week's Monday, ISO date. */
    val weekStart: String? = null,
    val hidePower: Boolean = false,
    val hideHeartRate: Boolean = false,
)

/** A link of the rider, without its URL. */
@Serializable
data class ShareLink(
    val id: Long,
    val kind: String,
    val activityId: Long? = null,
    val activityName: String? = null,
    /** The ride's local day. */
    val activityDate: String? = null,
    val weekStart: String? = null,
    val hidePower: Boolean = false,
    val hideHeartRate: Boolean = false,
    val createdAt: String = "",
    val expiresAt: String = "",
)

@Serializable
data class ShareCreated(
    val id: Long,
    val kind: String,
    val url: String,
    val expiresAt: String = "",
)
