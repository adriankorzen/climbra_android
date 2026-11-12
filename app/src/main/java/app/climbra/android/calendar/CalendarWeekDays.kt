package app.climbra.android.calendar

import app.climbra.android.data.api.CalendarResponse
import app.climbra.android.data.api.CalendarRide
import app.climbra.android.data.api.CalendarWorkout
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A planned workout with the ride linked to it, shown under it. */
data class DayWorkout(val workout: CalendarWorkout, val ride: CalendarRide?)

/** One day of the week view: its workouts and the rides that are no planned workout. */
data class CalendarDay(
    val date: LocalDate,
    val today: Boolean,
    val past: Boolean,
    val workouts: List<DayWorkout>,
    val unplannedRides: List<CalendarRide>,
) {
    val empty: Boolean get() = workouts.isEmpty() && unplannedRides.isEmpty()
}

/** The Monday of [date]'s week (the calendar's weeks run Monday to Sunday, as the web's). */
fun mondayOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

/**
 * The seven days from [monday] with the workouts and rides of [data] placed on their dates — placing is all the app
 * does; every figure is the backend's (spec 005).
 */
fun calendarDays(monday: LocalDate, data: CalendarResponse?, today: LocalDate): List<CalendarDay> =
    (0L until 7L).map { offset ->
        val date = monday.plusDays(offset)
        val day = date.toString()
        val rides = data?.activities.orEmpty().filter { it.date == day }
        CalendarDay(
            date = date,
            today = date == today,
            past = date.isBefore(today),
            workouts = data?.plannedWorkouts.orEmpty().filter { it.date == day }.map { workout ->
                DayWorkout(workout, workout.activityId?.let { id -> data?.activities?.firstOrNull { it.activityId == id } })
            },
            unplannedRides = rides.filter { it.plannedWorkoutId == null },
        )
    }

/** Pages of the week pager: one per week since this Monday. */
private val FIRST_MONDAY: LocalDate = LocalDate.of(1970, 1, 5)

/** Enough weeks to reach far past any plan. */
const val WEEK_PAGES = 7000

fun pageOf(monday: LocalDate): Int = ChronoUnit.WEEKS.between(FIRST_MONDAY, monday).toInt().coerceIn(0, WEEK_PAGES - 1)

fun mondayOfPage(page: Int): LocalDate = FIRST_MONDAY.plusWeeks(page.toLong())

/**
 * A week another screen asks the calendar to show, e.g. the ride detail's planned workout (spec 005); the calendar
 * takes it and clears it.
 */
class CalendarRequests {
    private val mutableWeek = MutableStateFlow<LocalDate?>(null)
    val week: StateFlow<LocalDate?> = mutableWeek.asStateFlow()

    fun show(date: LocalDate) {
        mutableWeek.value = mondayOf(date)
    }

    fun consumed() {
        mutableWeek.value = null
    }
}
