package app.climbra.android.widget

import app.climbra.android.data.api.CalendarApi
import app.climbra.android.data.api.CalendarWorkout
import app.climbra.android.data.api.Fitness
import app.climbra.android.data.api.RiderApi
import app.climbra.android.data.net.ApiError
import app.climbra.android.data.net.ApiResult
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.apiCall
import app.climbra.android.session.SessionState

/** What the home screen widget shows (spec 027). */
sealed interface TodayState {
    data object Loading : TodayState

    data object LoggedOut : TodayState

    data object NeedsTerms : TodayState

    data object NoRider : TodayState

    data object Failed : TodayState

    /** [day] is the backend's `today` of the answer, an ISO day. */
    data class Ready(val day: String, val workout: TodayWorkout, val fitness: Fitness) : TodayState
}

/** Today's planned workout as the widget shows it. */
sealed interface TodayWorkout {
    data object None : TodayWorkout

    data object Rest : TodayWorkout

    /** The calendar could not be loaded. */
    data object Unavailable : TodayWorkout

    /** The first workout to ride, and how many more the day has. */
    data class Planned(val workout: CalendarWorkout, val more: Int) : TodayWorkout
}

/**
 * The widget's data (spec 027): the rider, the training load of today and today's calendar, in that order. Every
 * figure is the backend's; offline the saved answers come from the network stack (spec 018).
 */
class TodayRepository(
    private val riders: RiderApi,
    private val calendar: CalendarApi,
    private val session: () -> SessionState,
) {

    suspend fun load(): TodayState {
        if (session() is SessionState.LoggedOut) return TodayState.LoggedOut
        val rider = when (val result = apiCall(Network.json) { riders.riders() }) {
            is ApiResult.Failure -> return failure(result.error)
            is ApiResult.Success -> result.value.firstOrNull() ?: return TodayState.NoRider
        }
        val load = when (val result = apiCall(Network.json) { riders.trainingLoad(rider.id, days = 1, weeks = 1) }) {
            is ApiResult.Failure -> return failure(result.error)
            is ApiResult.Success -> result.value
        }
        val today = load.today
        val workout = when (val result = apiCall(Network.json) { calendar.calendar(rider.id, today, today) }) {
            is ApiResult.Failure -> TodayWorkout.Unavailable
            is ApiResult.Success -> workout(result.value.plannedWorkouts.filter { it.date == today })
        }
        return TodayState.Ready(today, workout, load.fitness)
    }

    private fun failure(error: ApiError): TodayState {
        val http = error as? ApiError.Http
        return when {
            http?.status == 401 -> TodayState.LoggedOut
            http?.status == 403 && http.code == TERMS_NOT_ACCEPTED -> TodayState.NeedsTerms
            else -> TodayState.Failed
        }
    }

    companion object {
        private const val TERMS_NOT_ACCEPTED = "TERMS_NOT_ACCEPTED"

        /** The first workout to ride; a day of only rest entries is a rest day. */
        fun workout(day: List<CalendarWorkout>): TodayWorkout {
            val rides = day.filterNot { it.rest }
            return when {
                rides.isNotEmpty() -> TodayWorkout.Planned(rides.first(), rides.size - 1)
                day.isNotEmpty() -> TodayWorkout.Rest
                else -> TodayWorkout.None
            }
        }
    }
}
