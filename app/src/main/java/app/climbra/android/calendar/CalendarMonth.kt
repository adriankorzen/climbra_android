package app.climbra.android.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.climbra.android.R
import app.climbra.android.activities.sportName
import app.climbra.android.data.api.CalendarRide
import app.climbra.android.data.api.CalendarWeek
import app.climbra.android.data.api.CalendarWorkout
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.theme.MonoFamily
import app.climbra.android.ui.theme.extendedColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit

/** From this width on the calendar opens in the month view (the app's wide layout, as the dashboard's). */
val WIDE_CALENDAR = 600.dp

/** Pages of the month pager: one per month since January 1970. */
private val FIRST_MONTH: YearMonth = YearMonth.of(1970, 1)

const val MONTH_PAGES = 1700

fun pageOfMonth(month: YearMonth): Int = ChronoUnit.MONTHS.between(FIRST_MONTH, month).toInt().coerceIn(0, MONTH_PAGES - 1)

fun monthOfPage(page: Int): YearMonth = FIRST_MONTH.plusMonths(page.toLong())

/** The most tiles of a day cell; the rest is "+n more". */
private const val TILES_PER_DAY = 3

/** Week / Month (spec 015). */
@Composable
internal fun ModeSwitch(mode: CalendarMode, onMode: (CalendarMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        CalendarMode.entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = mode == option,
                onClick = { onMode(option) },
                shape = SegmentedButtonDefaults.itemShape(index, CalendarMode.entries.size),
                modifier = Modifier.testTag("calendar-mode-${option.name.lowercase()}"),
            ) {
                Text(stringResource(if (option == CalendarMode.WEEK) R.string.calendar_mode_week else R.string.calendar_mode_month))
            }
        }
    }
}

/** The month view (spec 015, the web's `calendar-grid` in month mode), swiped or stepped through. */
@Composable
internal fun MonthPager(state: CalendarUiState, fmt: Fmt, actions: CalendarActions, wide: Boolean) {
    val pager = rememberPagerState(initialPage = pageOfMonth(state.month)) { MONTH_PAGES }
    val month by rememberUpdatedState(state.month)
    val showMonth by rememberUpdatedState(actions.onShowMonth)
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page ->
            val shown = monthOfPage(page)
            if (shown != month) showMonth(shown)
        }
    }
    LaunchedEffect(state.month) {
        val page = pageOfMonth(state.month)
        if (pager.currentPage != page) pager.scrollToPage(page)
    }
    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize().testTag("calendar-month-pager"), key = { it }) { page ->
        if (monthOfPage(page) == state.month) MonthPage(state, fmt, actions, wide) else Box(Modifier.fillMaxSize())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MonthPage(state: CalendarUiState, fmt: Fmt, actions: CalendarActions, wide: Boolean) {
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = actions.onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("calendar-month"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(16.dp),
        ) {
            if (!top(state, fmt, actions)) return@LazyColumn
            item(key = "weekdays") { Weekdays(fmt) }
            items(state.monthWeeks, key = { "week-${it.first().date}" }) { days ->
                MonthWeek(days, state.month, state.figuresOf(days.first().date), fmt, actions, wide)
            }
        }
    }
}

@Composable
private fun Weekdays(fmt: Fmt) {
    Row(Modifier.fillMaxWidth()) {
        DayOfWeek.entries.forEach { day ->
            Text(
                day.getDisplayName(TextStyle.SHORT, fmt.locale),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.extendedColors.textFaint,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
        }
    }
}

/** One row of the grid; the backend's week totals and plan week under it (or beside it on wide windows). */
@Composable
private fun MonthWeek(days: List<CalendarDay>, month: YearMonth, figures: CalendarWeek?, fmt: Fmt, actions: CalendarActions, wide: Boolean) {
    val monday = days.first().date
    Column(Modifier.fillMaxWidth().testTag("month-week-$monday"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            days.forEach { day -> DayCell(day, inMonth = YearMonth.from(day.date) == month, fmt, actions, Modifier.weight(1f).fillMaxHeight()) }
            if (wide) WeekFigures(figures, fmt, Modifier.width(120.dp).padding(start = 8.dp).testTag("month-week-figures-$monday"))
        }
        if (!wide) WeekFigures(figures, fmt, Modifier.fillMaxWidth().testTag("month-week-figures-$monday"))
    }
}

/** The backend's figures of a week (cyclingapp spec 054): plan week, ridden / planned TSS and time. */
@Composable
private fun WeekFigures(figures: CalendarWeek?, fmt: Fmt, modifier: Modifier) {
    val planWeek = figures?.planWeek
    val parts = buildList {
        planWeek?.let { add(stringResource(R.string.calendar_week_number, it.weekNumber)) }
        planWeek?.focus?.takeIf { it.isNotBlank() }?.let { add(it) }
        add("${fmt.num(figures?.riddenTss ?: 0)} / ${fmt.num(figures?.plannedTss ?: 0)} TSS")
        add("${fmt.hm(figures?.riddenSeconds ?: 0)} / ${fmt.hm(figures?.plannedSeconds ?: 0)}")
    }
    Text(
        parts.joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = MonoFamily,
        color = MaterialTheme.extendedColors.textFaint,
        modifier = modifier,
    )
}

/** A day of the grid: its number, up to [TILES_PER_DAY] tiles and "+n more"; a tap opens its week. */
@Composable
private fun DayCell(day: CalendarDay, inMonth: Boolean, fmt: Fmt, actions: CalendarActions, modifier: Modifier) {
    val tiles: List<@Composable () -> Unit> = buildList {
        day.workouts.forEach { item -> add { CompactWorkout(item.workout) { actions.onOpenWorkout(item.workout) } } }
        day.unplannedRides.forEach { ride -> add { CompactRide(ride, fmt) { actions.onOpenRide(ride.activityId) } } }
    }
    Column(
        modifier
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .clickable { actions.onOpenWeekOf(day.date) }
            .alpha(if (inMonth) 1f else 0.45f)
            .padding(3.dp)
            .heightIn(min = 72.dp)
            .testTag("month-day-${day.date}"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val number = day.date.dayOfMonth.toString()
        if (day.today) {
            Box(Modifier.size(22.dp).background(MaterialTheme.colorScheme.primary, CircleShape).testTag("month-today"), contentAlignment = Alignment.Center) {
                Text(number, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
            }
        } else {
            Text(number, style = MaterialTheme.typography.labelMedium, fontFamily = MonoFamily, modifier = Modifier.padding(start = 2.dp))
        }
        tiles.take(TILES_PER_DAY).forEach { it() }
        if (tiles.size > TILES_PER_DAY) {
            val more = tiles.size - TILES_PER_DAY
            Text(
                pluralStringResource(R.plurals.calendar_more, more, more),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("month-more-${day.date}"),
            )
        }
    }
}

@Composable
private fun CompactWorkout(workout: CalendarWorkout, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(onClick = onOpen).testTag("month-workout-${workout.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(statusColor(workout)))
        Text(
            workout.title,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 3.dp),
        )
    }
}

@Composable
private fun CompactRide(ride: CalendarRide, fmt: Fmt, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).testTag("month-ride-${ride.activityId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_directions_bike), null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            ride.trainingLoad?.let { "${fmt.num(it)} TSS" } ?: (ride.name ?: sportName(ride.sportType)),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = MonoFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 3.dp),
        )
    }
}

