package app.climbra.android.widget

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.climbra.android.MainActivity
import app.climbra.android.R
import app.climbra.android.data.api.CalendarWorkout
import app.climbra.android.data.api.Fitness
import app.climbra.android.data.api.FormRange
import app.climbra.android.dashboard.formName
import app.climbra.android.push.AndroidPushNotifier.Companion.intent
import app.climbra.android.push.PushTarget
import app.climbra.android.ui.format.DateStyle
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.theme.DarkColors
import app.climbra.android.ui.theme.DarkExtendedColors
import app.climbra.android.ui.theme.LightColors
import app.climbra.android.ui.theme.LightExtendedColors
import dagger.hilt.android.EntryPointAccessors
import android.content.Intent
import kotlinx.coroutines.launch

/**
 * The home screen widget (spec 027): today's planned workout and the form. Each session loads once; a newer
 * [TodayWidgetUpdates.version] loads again while the widget is shown. Glance keeps no state of it.
 */
class TodayWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(COMPACT, FULL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entry = EntryPointAccessors.fromApplication(context, TodayWidgetEntryPoint::class.java)
        val repository = entry.todayRepository()
        val updates = entry.todayWidgetUpdates()
        val loadedVersion = updates.version.value
        val first = repository.load()
        provideContent {
            var state by remember { mutableStateOf(first) }
            var refreshing by remember { mutableStateOf(false) }
            val version by updates.version.collectAsState()
            LaunchedEffect(version) {
                if (version == loadedVersion) return@LaunchedEffect
                refreshing = true
                state = repository.load()
                refreshing = false
            }
            val scope = rememberCoroutineScope()
            GlanceTheme(colors = TodayWidgetColors) {
                TodayContent(state, refreshing, onRefresh = { scope.launch { updates.refresh() } })
            }
        }
    }

    companion object {
        val COMPACT = DpSize(110.dp, 40.dp)
        val FULL = DpSize(180.dp, 110.dp)
    }
}

/** Places and removes the widget; the first one starts the background refreshes, the last one stops them. */
class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        schedule(context).start()
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        schedule(context).stop()
    }

    private fun schedule(context: Context) =
        EntryPointAccessors.fromApplication(context.applicationContext, TodayWidgetEntryPoint::class.java).todayWidgetSchedule()
}

/** The app's light and dark colors; the widget follows the system's theme. */
internal val TodayWidgetColors = ColorProviders(light = LightColors, dark = DarkColors)

internal object TodayTags {
    const val ROOT = "today_root"
    const val DAY = "today_day"
    const val WORKOUT = "today_workout"
    const val STATUS = "today_status"
    const val FORM = "today_form"
    const val FITNESS = "today_fitness"
    const val MESSAGE = "today_message"
    const val REFRESH = "today_refresh"
    const val REFRESHING = "today_refreshing"
}

/** The widget's content for [state]; the layout follows the widget's size. */
@Composable
internal fun TodayContent(state: TodayState, refreshing: Boolean, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val fmt = Fmt(context.resources.configuration.locales[0])
    val size = LocalSize.current
    val compact = size.width < TodayWidget.FULL.width || size.height < TodayWidget.FULL.height
    val open = actionStartActivity(
        if (state is TodayState.Ready) PushTarget.Dashboard.intent(context)
        else Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    Column(
        modifier = GlanceModifier.fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.surface)
            .systemCorners()
            .padding(if (compact) 8.dp else 12.dp)
            .clickable(open)
            .semantics { testTag = TodayTags.ROOT },
        verticalAlignment = if (state is TodayState.Ready) Alignment.Top else Alignment.CenterVertically,
    ) {
        when (state) {
            is TodayState.Ready -> if (compact) Compact(state, fmt) else Full(state, fmt, refreshing, onRefresh)
            else -> Message(state, compact, refreshing, onRefresh)
        }
    }
}

@Composable
private fun ColumnScope.Full(state: TodayState.Ready, fmt: Fmt, refreshing: Boolean, onRefresh: () -> Unit) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            fmt.date(state.day, DateStyle.WEEKDAY),
            style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight().semantics { testTag = TodayTags.DAY },
        )
        RefreshButton(refreshing, onRefresh)
    }
    Spacer(GlanceModifier.height(6.dp))
    WorkoutLines(state, fmt, compact = false)
    Spacer(GlanceModifier.defaultWeight())
    FormLine(state.fitness, fmt, compact = false)
}

@Composable
private fun ColumnScope.Compact(state: TodayState.Ready, fmt: Fmt) {
    WorkoutLines(state, fmt, compact = true)
    Spacer(GlanceModifier.defaultWeight())
    FormLine(state.fitness, fmt, compact = true)
}

@Composable
private fun WorkoutLines(state: TodayState.Ready, fmt: Fmt, compact: Boolean) {
    val context = LocalContext.current
    val title = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = if (compact) 13.sp else 15.sp, fontWeight = FontWeight.Bold)
    val faint = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp)
    when (val workout = state.workout) {
        TodayWorkout.None -> Text(context.getString(R.string.widget_no_workout), style = title, maxLines = 1, modifier = tag(TodayTags.WORKOUT))
        TodayWorkout.Rest -> Text(context.getString(R.string.widget_rest_day), style = title, maxLines = 1, modifier = tag(TodayTags.WORKOUT))
        TodayWorkout.Unavailable -> Text(Fmt.DASH, style = title, maxLines = 1, modifier = tag(TodayTags.WORKOUT))
        is TodayWorkout.Planned -> {
            val planned = workout.workout
            val open = actionStartActivity(PushTarget.Workout(planned.id, planned.date).intent(context))
            Column(GlanceModifier.fillMaxWidth().clickable(open).semantics { testTag = TodayTags.WORKOUT }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (planned.status == STATUS_COMPLETED) {
                        Image(
                            ImageProvider(R.drawable.ic_check_circle_fill1),
                            contentDescription = context.getString(R.string.workout_status_completed),
                            colorFilter = ColorFilter.tint(SuccessColor),
                            modifier = GlanceModifier.size(16.dp),
                        )
                        Spacer(GlanceModifier.width(4.dp))
                    }
                    Text(planned.title, style = title, maxLines = 1)
                }
                if (!compact) {
                    Text(details(context, planned, fmt), style = faint, maxLines = 1)
                    status(planned.status)?.let { (text, color) ->
                        Text(context.getString(text), style = faint.copy(color = color, fontWeight = FontWeight.Medium), maxLines = 1, modifier = tag(TodayTags.STATUS))
                    }
                }
                if (workout.more > 0) {
                    Text(context.resources.getQuantityString(R.plurals.widget_more, workout.more, workout.more), style = faint, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun FormLine(fitness: Fitness, fmt: Fmt, compact: Boolean) {
    val context = LocalContext.current
    val base = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = if (compact) 12.sp else 13.sp)
    val form = context.getString(R.string.widget_form, fmt.signed(fitness.tsb), context.getString(formName(fitness.form)))
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (!compact) {
            Text(context.getString(R.string.widget_fitness, fmt.num(fitness.ctl)), style = base, maxLines = 1, modifier = tag(TodayTags.FITNESS))
            Spacer(GlanceModifier.width(12.dp))
        }
        Text(form, style = base.copy(color = formColor(fitness.form), fontWeight = FontWeight.Bold), maxLines = 1, modifier = tag(TodayTags.FORM))
    }
}

@Composable
private fun Message(state: TodayState, compact: Boolean, refreshing: Boolean, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val text = when (state) {
        TodayState.Loading -> R.string.widget_loading
        TodayState.LoggedOut -> R.string.widget_logged_out
        TodayState.NeedsTerms -> R.string.widget_terms
        TodayState.NoRider -> R.string.widget_no_rider
        TodayState.Failed, is TodayState.Ready -> R.string.widget_failed
    }
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            context.getString(text),
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = if (compact) 12.sp else 14.sp),
            maxLines = if (compact) 2 else 3,
            modifier = GlanceModifier.defaultWeight().semantics { testTag = TodayTags.MESSAGE },
        )
        if (state == TodayState.Failed) RefreshButton(refreshing, onRefresh)
    }
}

@Composable
private fun RefreshButton(refreshing: Boolean, onRefresh: () -> Unit) {
    if (refreshing) {
        CircularProgressIndicator(
            color = GlanceTheme.colors.primary,
            modifier = GlanceModifier.size(20.dp).semantics { testTag = TodayTags.REFRESHING },
        )
    } else {
        Image(
            ImageProvider(R.drawable.ic_refresh),
            contentDescription = LocalContext.current.getString(R.string.widget_refresh),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
            modifier = GlanceModifier.size(24.dp).clickable(onRefresh).semantics { testTag = TodayTags.REFRESH },
        )
    }
}

private fun tag(value: String) = GlanceModifier.semantics { testTag = value }

/** Duration · TSS · indoor, the backend's figures. */
internal fun details(context: Context, workout: CalendarWorkout, fmt: Fmt): String = listOfNotNull(
    fmt.hm(workout.plannedDurationMinutes * 60),
    context.getString(R.string.widget_tss, fmt.num(workout.plannedTss)),
    if (workout.indoor) context.getString(R.string.workout_indoor) else null,
).joinToString(" · ")

private const val STATUS_COMPLETED = "COMPLETED"

/** The status text and its color; nothing for a workout still planned. */
private fun status(status: String?): Pair<Int, ColorProvider>? = when (status) {
    STATUS_COMPLETED -> R.string.workout_status_completed to SuccessColor
    "MISSED" -> R.string.workout_status_missed to ColorProvider(day = LightColors.error, night = DarkColors.error)
    "SKIPPED" -> R.string.workout_status_skipped to ColorProvider(day = LightExtendedColors.textFaint, night = DarkExtendedColors.textFaint)
    else -> null
}

private val SuccessColor = ColorProvider(day = LightExtendedColors.success, night = DarkExtendedColors.success)

/** As the dashboard's TSB tile (spec 002). */
internal fun formColor(form: FormRange): ColorProvider = when (form) {
    FormRange.TRANSITION -> ColorProvider(day = LightExtendedColors.powerZones[3], night = DarkExtendedColors.powerZones[3])
    FormRange.FRESH -> SuccessColor
    FormRange.NEUTRAL -> ColorProvider(day = LightExtendedColors.textFaint, night = DarkExtendedColors.textFaint)
    FormRange.OPTIMAL -> ColorProvider(day = LightExtendedColors.info, night = DarkExtendedColors.info)
    FormRange.OVERLOAD -> ColorProvider(day = LightColors.error, night = DarkColors.error)
}

/** The launcher's widget corners; Glance rounds widgets from Android 12 only. */
@SuppressLint("InlinedApi")
private fun GlanceModifier.systemCorners(): GlanceModifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) cornerRadius(android.R.dimen.system_app_widget_background_radius) else this
