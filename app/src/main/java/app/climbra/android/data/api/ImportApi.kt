package app.climbra.android.data.api

import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/** Rides from files (spec 009, cyclingapp specs 004, 037, 040); `timeZone` is for rides without a local time. */
interface ImportApi {

    /**
     * One ride, stored and analysed right away: `201`; `409` with `activityId` when it is already stored, `413`, `422`,
     * `503 FILE_PROCESSING_BUSY`.
     */
    @Multipart
    @POST("api/riders/{riderId}/activities/upload")
    suspend fun upload(
        @Path("riderId") riderId: Long,
        @Part file: MultipartBody.Part,
        @Query("name") name: String?,
        @Query("timeZone") timeZone: String,
    ): UploadedRide

    /** Files of up to 50 MB in all as one import job: `202`; `409 IMPORT_RUNNING`, `413`, `503 IMPORT_QUEUE_FULL`. */
    @Multipart
    @POST("api/riders/{riderId}/imports")
    suspend fun startImport(
        @Path("riderId") riderId: Long,
        @Part files: List<MultipartBody.Part>,
        @Query("timeZone") timeZone: String,
    ): ImportJob

    /** The job with every file's outcome. */
    @GET("api/imports/{id}")
    suspend fun importJob(@Path("id") jobId: Long, @Query("items") items: Boolean = true): ImportJob
}

/** The stored ride of an upload, as far as the app reads `ActivityResponse`. */
@Serializable
data class UploadedRide(val id: Long)
