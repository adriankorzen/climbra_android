package app.climbra.android.push

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.HTTP
import retrofit2.http.PUT

/** The device's push token on the backend (cyclingapp spec 056, `push.PushDeviceController`). */
interface PushApi {

    /** `204`; a token of another account moves to this one. */
    @PUT("api/push-devices")
    suspend fun register(@Body request: PushDeviceRequest)

    /** `204`, also for an unknown token. */
    @HTTP(method = "DELETE", path = "api/push-devices", hasBody = true)
    suspend fun unregister(@Body request: PushDeviceRemoval)
}

/** Mirrors `push.PushDeviceRequest`. */
@Serializable
data class PushDeviceRequest(val token: String, val platform: String) {
    companion object {
        /** Sent explicitly: the JSON leaves out default values, and the backend requires the platform. */
        fun android(token: String) = PushDeviceRequest(token, "ANDROID")
    }
}

/** Mirrors `push.PushDeviceRequest.Removal`. */
@Serializable
data class PushDeviceRemoval(val token: String)
