package app.climbra.android.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.climbra.android.data.api.Availability
import app.climbra.android.data.api.CoachApi
import app.climbra.android.data.api.TrainingBlockRequest
import app.climbra.android.data.net.ApiError
import app.climbra.android.data.net.ApiResult
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.apiCall
import app.climbra.android.rider.RiderRepository
import app.climbra.android.session.SessionRepository
import app.climbra.android.session.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The coach's methodologies, in the web's order. */
val METHODOLOGIES = listOf("AUTO", "POLARIZED", "PYRAMIDAL", "SWEET_SPOT")

/** The languages the coach answers in. */
val COACH_LANGUAGES = listOf("en", "pl")

/** The coach's language by default: the app's (the account's, else the device's). */
fun appLanguage(session: SessionRepository): String {
    val language = (session.state.value as? SessionState.LoggedIn)?.account?.language ?: Locale.getDefault().language
    return if (language in COACH_LANGUAGES) language else "en"
}

data class DayAvailability(val day: DayOfWeek, val available: Boolean, val minutes: Int, val indoor: Boolean = false)

/** The web's defaults: Tue 60, Thu 60, Sat 180, Sun 120. */
private val DEFAULT_MINUTES = mapOf(DayOfWeek.TUESDAY to 60, DayOfWeek.THURSDAY to 60, DayOfWeek.SATURDAY to 180, DayOfWeek.SUNDAY to 120)

fun defaultAvailability(): List<DayAvailability> = DayOfWeek.entries.map { day ->
    DayAvailability(day, available = day in DEFAULT_MINUTES, minutes = DEFAULT_MINUTES[day] ?: 60)
}

data class TrainingBlockFormState(
    val today: LocalDate,
    val startDate: LocalDate = today.plusDays(1),
    val weeks: Int = 4,
    val goal: String = "",
    val eventDate: LocalDate? = null,
    val days: List<DayAvailability> = defaultAvailability(),
    val longRideDay: DayOfWeek? = DayOfWeek.SATURDAY,
    val methodology: String = "AUTO",
    val notes: String = "",
    val language: String = "en",
    val limits: CoachLimits = CoachLimits(),
    /** Errors show after the first try to send. */
    val submitted: Boolean = false,
    val sending: Boolean = false,
    val error: ApiError? = null,
    /** The accepted conversation: it replaces the form. */
    val accepted: Long? = null,
) {
    val startInPast: Boolean get() = startDate.isBefore(today)
    val goalMissing: Boolean get() = goal.isBlank()
    val noDay: Boolean get() = days.none { it.available && it.minutes > 0 }
    val eventInPast: Boolean get() = eventDate?.isBefore(today) == true
    val valid: Boolean get() = !startInPast && !goalMissing && !noDay && !eventInPast && weeks in 4..6

    /** The web's request body: only the available days, trimmed texts, empty ones as `null`. */
    fun request(): TrainingBlockRequest {
        val available = days.filter { it.available && it.minutes > 0 }
        return TrainingBlockRequest(
            startDate = startDate.toString(),
            weeks = weeks,
            goal = goal.trim(),
            goalEventDate = eventDate?.toString(),
            availability = Availability(
                days = available.associate { it.day.name to it.minutes },
                longRideDay = longRideDay?.takeIf { day -> available.any { it.day == day } }?.name,
            ),
            indoorDays = available.filter { it.indoor }.map { it.day.name },
            methodology = methodology,
            notes = notes.trim().ifEmpty { null },
            language = language,
        )
    }
}

/** A new 4–6 week training block (spec 006, the web's `training-block-form`). */
@HiltViewModel
class TrainingBlockFormViewModel @Inject constructor(
    private val riders: RiderRepository,
    private val api: CoachApi,
    private val activity: CoachActivity,
    private val quotas: CoachQuotaRepository,
    session: SessionRepository,
    clock: Clock,
) : ViewModel() {

    internal var zone: ZoneId = ZoneId.systemDefault()

    private val mutableState = MutableStateFlow(
        TrainingBlockFormState(today = clock.instant().atZone(zone).toLocalDate(), language = appLanguage(session)),
    )
    val state: StateFlow<TrainingBlockFormState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { coachLimits(quotas, activity).collect { limits -> mutableState.update { it.copy(limits = limits) } } }
    }

    fun update(change: (TrainingBlockFormState) -> TrainingBlockFormState) = mutableState.update {
        change(it).let { next -> next.copy(goal = next.goal.take(MAX_GOAL), notes = next.notes.take(MAX_NOTES)) }
    }

    fun updateDay(day: DayOfWeek, change: (DayAvailability) -> DayAvailability) = update { state ->
        state.copy(days = state.days.map { if (it.day == day) change(it).let { d -> d.copy(minutes = d.minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)) } else it })
    }

    fun start() {
        val current = state.value
        mutableState.update { it.copy(submitted = true) }
        if (!current.valid || current.sending || !current.limits.allows(CostKind.TRAINING_BLOCK)) return
        mutableState.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            val rider = when (val result = riders.load()) {
                is ApiResult.Failure -> return@launch failed(result.error)
                is ApiResult.Success -> result.value ?: return@launch failed(ApiError.Unexpected)
            }
            when (val result = apiCall(Network.json) { api.startTrainingBlock(rider.id, current.request()) }) {
                is ApiResult.Failure -> failed(result.error)
                is ApiResult.Success -> {
                    activity.track(result.value)
                    mutableState.update { it.copy(sending = false, accepted = result.value.id) }
                    quotas.reload()
                }
            }
        }
    }

    private fun failed(error: ApiError) = mutableState.update { it.copy(sending = false, error = error) }

    companion object {
        const val MAX_GOAL = 500
        const val MAX_NOTES = 2000
        const val MIN_MINUTES = 15
        const val MAX_MINUTES = 720
        const val MINUTES_STEP = 15
    }
}
