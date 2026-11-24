package app.climbra.android.widget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest
import androidx.glance.appwidget.testing.unit.assertHasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.isIndeterminateCircularProgressIndicator
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.color.ColorProvider
import androidx.glance.testing.unit.assertHasContentDescription
import androidx.glance.testing.unit.assertHasText
import androidx.glance.testing.unit.hasContentDescription
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.climbra.android.MainActivity
import app.climbra.android.data.api.CalendarWorkout
import app.climbra.android.data.api.Fitness
import app.climbra.android.data.api.FormRange
import app.climbra.android.push.AndroidPushNotifier.Companion.intent
import app.climbra.android.push.AndroidPushNotifier.Companion.pushTarget
import app.climbra.android.push.PushTarget
import app.climbra.android.ui.format.DateStyle
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.theme.DarkColors
import app.climbra.android.ui.theme.DarkExtendedColors
import app.climbra.android.ui.theme.LightColors
import app.climbra.android.ui.theme.LightExtendedColors
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Spec 027: what the widget shows and where its taps lead. */
@RunWith(AndroidJUnit4::class)
class TodayWidgetContentTest {

    private val app = ApplicationProvider.getApplicationContext<Context>()

    private fun context(locale: Locale = Locale.ENGLISH): Context =
        app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(locale) })

    private fun workout(status: String = "PLANNED", indoor: Boolean = true) = CalendarWorkout(
        id = 2, date = "2026-11-24", title = "Sweet spot", indoor = indoor, plannedDurationMinutes = 75, plannedTss = 82,
        status = status,
    )

    private fun ready(workout: TodayWorkout = TodayWorkout.Planned(workout(), 0), form: FormRange = FormRange.OPTIMAL) =
        TodayState.Ready("2026-11-24", workout, Fitness(ctl = 64.4, atl = 72.6, tsb = -8.2, form = form))

    private fun show(
        state: TodayState,
        size: DpSize = DpSize(250.dp, 180.dp),
        locale: Locale = Locale.ENGLISH,
        refreshing: Boolean = false,
        test: GlanceAppWidgetUnitTest.() -> Unit,
    ) = runGlanceAppWidgetUnitTest {
        setContext(context(locale))
        setAppWidgetSize(size)
        provideComposable { GlanceTheme(colors = TodayWidgetColors) { TodayContent(state, refreshing, onRefresh = {}) } }
        test()
    }

    @Test
    fun `the full layout shows the day, the workout's figures and the form`() = show(ready()) {
        onNode(hasTestTag(TodayTags.DAY)).assertHasText(Fmt(Locale.ENGLISH).date("2026-11-24", DateStyle.WEEKDAY))
        onNode(hasTestTag(TodayTags.WORKOUT)).assertExists()
        onNode(hasText("Sweet spot")).assertExists()
        onNode(hasText("1:15 · TSS 82 · Indoor")).assertExists()
        onNode(hasTestTag(TodayTags.FITNESS)).assertHasText("CTL 64")
        onNode(hasTestTag(TodayTags.FORM)).assertHasText("TSB -8 · Productive")
        onNode(hasTestTag(TodayTags.STATUS)).assertDoesNotExist()
        onNode(hasTestTag(TodayTags.REFRESH)).assertHasContentDescription("Refresh")
    }

    @Test
    fun `the compact layout shows the workout and the form only`() = show(ready(), size = DpSize(130.dp, 60.dp)) {
        onNode(hasText("Sweet spot")).assertExists()
        onNode(hasTestTag(TodayTags.FORM)).assertHasText("TSB -8 · Productive")
        onNode(hasTestTag(TodayTags.DAY)).assertDoesNotExist()
        onNode(hasTestTag(TodayTags.FITNESS)).assertDoesNotExist()
        onNode(hasText("1:15 · TSS 82 · Indoor")).assertDoesNotExist()
    }

    @Test
    fun `a completed workout shows the check and its status`() = show(ready(TodayWorkout.Planned(workout("COMPLETED"), 0))) {
        onNode(hasTestTag(TodayTags.STATUS)).assertHasText("Completed")
        onNode(hasContentDescription("Completed")).assertExists()
    }

    @Test
    fun `a missed and a skipped workout show their status`() {
        show(ready(TodayWorkout.Planned(workout("MISSED", indoor = false), 0))) {
            onNode(hasTestTag(TodayTags.STATUS)).assertHasText("Missed")
            onNode(hasText("1:15 · TSS 82")).assertExists()
        }
        show(ready(TodayWorkout.Planned(workout("SKIPPED"), 0))) {
            onNode(hasTestTag(TodayTags.STATUS)).assertHasText("Skipped")
        }
    }

    @Test
    fun `a rest day, no workout, a failed calendar and more workouts`() {
        show(ready(TodayWorkout.Rest)) { onNode(hasTestTag(TodayTags.WORKOUT)).assertHasText("Rest day") }
        show(ready(TodayWorkout.None)) { onNode(hasTestTag(TodayTags.WORKOUT)).assertHasText("No workout planned") }
        show(ready(TodayWorkout.Unavailable)) {
            onNode(hasTestTag(TodayTags.WORKOUT)).assertHasText("–")
            onNode(hasTestTag(TodayTags.FORM)).assertExists()
        }
        show(ready(TodayWorkout.Planned(workout(), 1))) { onNode(hasText("+ 1 more")).assertExists() }
    }

    @Test
    fun `the form shows each range's name, positive values with a sign`() {
        mapOf(
            FormRange.TRANSITION to "Transition",
            FormRange.FRESH to "Fresh",
            FormRange.NEUTRAL to "Neutral",
            FormRange.OPTIMAL to "Productive",
            FormRange.OVERLOAD to "High risk",
        ).forEach { (form, name) ->
            show(ready(form = form)) { onNode(hasTestTag(TodayTags.FORM)).assertHasText("TSB -8 · $name") }
        }
        show(TodayState.Ready("2026-11-24", TodayWorkout.None, Fitness(ctl = 70.0, tsb = 12.4, form = FormRange.FRESH))) {
            onNode(hasTestTag(TodayTags.FORM)).assertHasText("TSB +12 · Fresh")
        }
    }

    @Test
    fun `each range has the dashboard's color`() {
        assertEquals(ColorProvider(LightExtendedColors.powerZones[3], DarkExtendedColors.powerZones[3]), formColor(FormRange.TRANSITION))
        assertEquals(ColorProvider(LightExtendedColors.success, DarkExtendedColors.success), formColor(FormRange.FRESH))
        assertEquals(ColorProvider(LightExtendedColors.textFaint, DarkExtendedColors.textFaint), formColor(FormRange.NEUTRAL))
        assertEquals(ColorProvider(LightExtendedColors.info, DarkExtendedColors.info), formColor(FormRange.OPTIMAL))
        assertEquals(ColorProvider(LightColors.error, DarkColors.error), formColor(FormRange.OVERLOAD))
    }

    @Test
    fun `the workout opens it on the calendar, the rest of the widget the dashboard`() = show(ready()) {
        val context = context()
        onNode(hasTestTag(TodayTags.WORKOUT)).assertHasStartActivityClickAction(PushTarget.Workout(2, "2026-11-24").intent(context))
        onNode(hasTestTag(TodayTags.ROOT)).assertHasStartActivityClickAction(PushTarget.Dashboard.intent(context))
    }

    @Test
    fun `the targets survive the intent`() {
        assertEquals(PushTarget.Workout(2, "2026-11-24"), PushTarget.Workout(2, "2026-11-24").intent(app).pushTarget())
        assertEquals(PushTarget.Dashboard, PushTarget.Dashboard.intent(app).pushTarget())
    }

    @Test
    fun `the states without data show their texts`() {
        show(TodayState.LoggedOut) {
            onNode(hasTestTag(TodayTags.MESSAGE)).assertHasText("Log in to Climbra to see today's workout")
            onNode(hasTestTag(TodayTags.ROOT))
                .assertHasStartActivityClickAction(Intent(context(), MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            onNode(hasTestTag(TodayTags.REFRESH)).assertDoesNotExist()
        }
        show(TodayState.NeedsTerms) { onNode(hasTestTag(TodayTags.MESSAGE)).assertHasText("Open Climbra to accept the terms") }
        show(TodayState.NoRider) { onNode(hasTestTag(TodayTags.MESSAGE)).assertHasText("Create your rider profile in Climbra") }
        show(TodayState.Loading) { onNode(hasTestTag(TodayTags.MESSAGE)).assertHasText("Loading…") }
        show(TodayState.Failed) {
            onNode(hasTestTag(TodayTags.MESSAGE)).assertHasText("Cannot load today's training")
            onNode(hasTestTag(TodayTags.REFRESH)).assertExists()
        }
    }

    @Test
    fun `a refresh shows the progress indicator instead of the button`() = show(ready(), refreshing = true) {
        onNode(isIndeterminateCircularProgressIndicator()).assertExists()
        onNode(hasTestTag(TodayTags.REFRESH)).assertDoesNotExist()
    }

    @Test
    fun `texts, numbers and dates follow the app language`() {
        val polish = Locale.forLanguageTag("pl")
        show(ready(), locale = polish) {
            onNode(hasTestTag(TodayTags.FORM)).assertHasText("TSB -8 · Produktywnie")
            onNode(hasText("1:15 · TSS 82 · Trenażer")).assertExists()
            onNode(hasTestTag(TodayTags.DAY)).assertHasText(Fmt(polish).date("2026-11-24", DateStyle.WEEKDAY))
        }
        show(ready(TodayWorkout.None, form = FormRange.FRESH), locale = polish) {
            onNode(hasTestTag(TodayTags.WORKOUT)).assertHasText("Brak treningu w planie")
        }
        show(TodayState.Ready("2026-11-24", TodayWorkout.None, Fitness(ctl = 1234.5)), locale = polish) {
            // Polish grouping: a (narrow) no-break space, as the dashboard
            onNode(hasTestTag(TodayTags.FITNESS)).assertHasText("CTL " + Fmt(polish).num(1234.5))
        }
    }
}
