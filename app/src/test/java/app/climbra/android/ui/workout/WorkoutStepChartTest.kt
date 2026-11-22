package app.climbra.android.ui.workout

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.climbra.android.data.api.WorkoutBlock
import app.climbra.android.data.api.WorkoutStep
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.theme.ClimbraTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Spec 023 AC1 - AC4: picking a step on the workout chart and what its label says. */
@RunWith(AndroidJUnit4::class)
class WorkoutStepChartTest {

    @get:Rule
    val compose = createComposeRule()

    private val fmt = Fmt(Locale.forLanguageTag("en"))

    // 10 min warm-up, 2 × (5 min work, 5 min recovery), 10 min cool-down: 40 min
    private val blocks = listOf(
        WorkoutBlock(steps = listOf(WorkoutStep("WARMUP", 600, 50, 65, 140, 182, zone = 2))),
        WorkoutBlock(
            repetitions = 2,
            steps = listOf(
                WorkoutStep("WORK", 300, 105, 110, 294, 308, zone = 5),
                WorkoutStep("RECOVERY", 300, 55, 55, 154, 154, zone = 1),
            ),
        ),
        WorkoutBlock(steps = listOf(WorkoutStep("COOLDOWN", 600, 50, 50, zone = 1))),
    )

    private fun show() = compose.setContent {
        ClimbraTheme(darkTheme = false) {
            WorkoutStepChart(blocks, Modifier.width(400.dp).height(140.dp))
        }
    }

    private fun label() = compose.onNodeWithTag("workout-chart-label", useUnmergedTree = true)

    @Test
    fun repetitionsAreStepsOfTheirOwn() {
        val steps = chartSteps(blocks)
        assertEquals(6, steps.size)
        assertEquals(listOf(0, 600, 900, 1200, 1500, 1800), steps.map { it.start })
        assertEquals(2400, steps.last().end)
        assertEquals(1, stepAt(steps, 0.30f))
        assertEquals(2, stepAt(steps, 0.40f))
        assertEquals(3, stepAt(steps, 0.55f))
        assertEquals(5, stepAt(steps, 1f))
        assertNull(stepAt(steps, 1.2f))
    }

    // AC1, AC3
    @Test
    fun theLabelShowsTimeKindPercentAndWatts() {
        val steps = chartSteps(blocks)
        assertEquals("10:00–15:00 · Work · 105–110% FTP · 294–308 W", stepLabel(steps[1], "Work", fmt))
        assertEquals("15:00–20:00 · Recovery · 55% FTP · 154 W", stepLabel(steps[2], "Recovery", fmt))
        // No watts from the backend (no FTP): % FTP only
        assertEquals("30:00–40:00 · Cool-down · 50% FTP", stepLabel(steps[5], "Cool-down", fmt))
    }

    // AC1, AC2
    @Test
    fun aTapPicksTheStepAndAnotherTapClearsIt() {
        show()
        label().assertTextEquals("")

        // 30 % of 40 min is the first work step (10:00 – 15:00)
        compose.onNodeWithTag("workout-chart").performTouchInput { click(percentOffset(0.30f, 0.5f)) }
        label().assertTextEquals(stepLabel(chartSteps(blocks)[1], "Work", fmt))

        // Dragging moves the pick, to the second work step (20:00 – 25:00)
        compose.onNodeWithTag("workout-chart").performTouchInput {
            swipe(percentOffset(0.30f, 0.5f), percentOffset(0.55f, 0.5f), durationMillis = 300)
        }
        label().assertTextEquals(stepLabel(chartSteps(blocks)[3], "Work", fmt))

        compose.onNodeWithTag("workout-chart").performTouchInput { click(percentOffset(0.55f, 0.5f)) }
        label().assertTextEquals("")
    }

    // AC4
    @Test
    fun theDescriptionListsTheSteps() {
        show()
        compose.onNodeWithTag("workout-chart").assert(
            SemanticsMatcher("lists the steps") { node ->
                val text = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()
                text.contains("10:00–15:00") && text.contains("294–308 W") && text.count { it == ';' } == 5
            },
        )
    }
}
