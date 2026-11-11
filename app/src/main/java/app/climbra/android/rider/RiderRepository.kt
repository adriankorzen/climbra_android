package app.climbra.android.rider

import app.climbra.android.data.api.Rider
import app.climbra.android.data.api.RiderApi
import app.climbra.android.data.api.TimeZoneRequest
import app.climbra.android.data.net.ApiResult
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.apiCall
import java.time.ZoneId

/**
 * The rider of the logged-in account (spec 002). Loading it also keeps the rider's time zone on the backend equal to
 * the device's, so the backend's "today" is the rider's (cyclingapp spec 049, as the web app does with the browser).
 */
class RiderRepository(
    private val api: RiderApi,
    private val deviceTimeZone: () -> String = { ZoneId.systemDefault().id },
) {

    /** The account's rider, `null` when the account has no rider profile yet. */
    suspend fun load(): ApiResult<Rider?> = when (val riders = apiCall(Network.json) { api.riders() }) {
        is ApiResult.Failure -> riders
        is ApiResult.Success -> {
            val rider = riders.value.firstOrNull()
            if (rider != null) {
                syncTimeZone(rider)
            }
            ApiResult.Success(rider)
        }
    }

    /** A failed request waits for the next load. */
    private suspend fun syncTimeZone(rider: Rider) {
        val zone = deviceTimeZone()
        if (zone.isNotBlank() && rider.timeZone != zone) {
            apiCall(Network.json) { api.setTimeZone(rider.id, TimeZoneRequest(zone)) }
        }
    }
}
