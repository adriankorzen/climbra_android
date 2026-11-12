package app.climbra.android.activities

import app.climbra.android.dashboard.label
import app.climbra.android.data.api.IntervalBlock
import app.climbra.android.data.api.IntervalSet
import app.climbra.android.data.api.Intervals
import app.climbra.android.ui.format.Fmt
import java.util.Locale
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 003: the little presentation logic the app has — no training figure is computed here. */
class RidePresentationTest {

    // AC7
    @Test
    fun theDetectionQueryHasOnlyTheFilledFieldsWithPercentsAsFractions() {
        assertEquals(emptyMap<String, String>(), DetectionForm().toQuery())
        assertEquals(
            mapOf("targetWatts" to "280", "relativeThreshold" to "0.9", "underThreshold" to "0.75"),
            DetectionForm(targetWatts = "280", thresholdPercent = "90", underPercent = "75").toQuery(),
        )
        assertEquals(
            mapOf("minDuration" to "60", "maxGap" to "10", "relativeThreshold" to "0.925"),
            DetectionForm(minDuration = " 60 ", maxGap = "10", thresholdPercent = "92,5").toQuery(),
        )
        assertNull(DetectionForm(targetWatts = "a lot").toQuery())
        assertTrue(DetectionForm().isEmpty)
    }

    private fun block(number: Int, start: Int, set: Int? = null) =
        IntervalBlock(number, start, start + 60, 60, 250.0, 300.0, setNumber = set)

    // AC5
    @Test
    fun withoutSetsTheRowsAreTheWorkBlocks() {
        val rows = intervalRows(Intervals(detected = true, blocks = listOf(block(1, 100), block(2, 400))))

        assertEquals(listOf("WORK-1", "WORK-2"), rows.map { it.key })
        assertTrue(rows.none { it.firstOfSet })
        assertEquals(emptyList<IntervalRow>(), intervalRows(null))
    }

    // AC5: overs, unders, repetitions and recoveries in ride order, a divider where another set starts
    @Test
    fun withSetsTheRowsAreInRideOrderMarkedByTheirSet() {
        fun set(number: Int, kind: String) =
            IntervalSet(number, kind, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0)
        val intervals = Intervals(
            detected = true,
            blocks = listOf(block(1, 100, set = 1), block(2, 300, set = 1), block(3, 900, set = 2), block(4, 1500)),
            unders = listOf(block(1, 200, set = 1)),
            recoveries = listOf(block(1, 1000, set = 2)),
            sets = listOf(set(1, "OVER_UNDER"), set(2, "REPEATS")),
        )

        val rows = intervalRows(intervals)

        assertEquals(listOf("OVER-1", "UNDER-1", "OVER-2", "REPETITION-3", "RECOVERY-1", "WORK-4"), rows.map { it.key })
        assertEquals(listOf(false, false, false, true, false, true), rows.map { it.firstOfSet })
    }

    // AC6: tapping an interval zooms the chart to it with a margin, inside the ride
    @Test
    fun theChartWindowStaysInsideTheRideAndZoomsToAnInterval() {
        assertEquals(ChartWindow(88.0, 172.0), windowAround(IntervalBlock(1, 100, 160, 60, 0.0, 0.0), 3600.0))
        // A block at the very start does not go before 0
        assertEquals(0.0, windowAround(IntervalBlock(1, 0, 20, 20, 0.0, 0.0), 3600.0).start, 0.0)
        // Never shorter than 30 s, never longer than the ride
        assertEquals(30.0, ChartWindow(100.0, 105.0).clampedTo(3600.0).length, 0.0)
        assertEquals(ChartWindow(0.0, 3600.0), ChartWindow(-500.0, 9000.0).clampedTo(3600.0))
        assertEquals(ChartWindow(3500.0, 3600.0), ChartWindow(3550.0, 3650.0).clampedTo(3600.0))
    }

    // Spec 002 fix: the dashboard's axis never asks Vico for a blank label (it throws)
    @Test
    fun axisLabelsAreNeverBlank() {
        val labels = listOf("1 Oct", "2 Oct", "3 Oct")
        assertEquals("2 Oct", label(labels, 1.0))
        assertEquals("3 Oct", label(labels, 7.0))
        assertEquals("1 Oct", label(labels, -2.0))
        assertEquals("–", label(emptyList(), 0.0))
        assertEquals("–", label(listOf(""), 0.0))
    }

    // AC6
    @Test
    fun theValueAxesHaveRoundLabelsFromZeroAndAltitudeKeepsItsRange() {
        assertEquals(AxisScale(0.0, 500.0, 100.0), seriesScale(RideSeries.POWER, listOf(120.0, 410.0)))
        assertEquals(listOf(0.0, 100.0, 200.0, 300.0, 400.0, 500.0), seriesScale(RideSeries.POWER, listOf(410.0)).ticks)
        assertEquals(AxisScale(0.0, 200.0, 50.0), seriesScale(RideSeries.HEART_RATE, listOf(95.0, 176.0)))
        assertEquals(AxisScale(120.0, 480.0, 360.0), seriesScale(RideSeries.ALTITUDE, listOf(120.0, 480.0)))
        assertEquals(2.5, niceStep(2.1), 1e-9)
        assertEquals(1.0, niceStep(0.0), 1e-9)
    }

    // AC6
    @Test
    fun theTimeAxisUsesARoundStepThatFitsAndHoursForLongRides() {
        assertEquals(listOf(0.0, 1800.0, 3600.0, 5400.0, 7200.0), timeTicks(ChartWindow(0.0, 7300.0), 5))
        assertEquals(listOf(60.0, 90.0, 120.0), timeTicks(ChartWindow(50.0, 130.0), 4))
        val fmt = Fmt(Locale.ROOT)
        assertEquals("1:30", timeLabel(fmt, 5400.0, 1800.0, 7300.0))
        assertEquals("1:30", timeLabel(fmt, 90.0, 30.0, 7300.0))
        assertEquals("30:00", timeLabel(fmt, 1800.0, 600.0, 2400.0))
    }

    // AC6
    @Test
    fun pinchStepsAddUpAroundTheFingersAndDragMovesTheWindow() {
        var window = ChartWindow(0.0, 1800.0)
        // Ten small steps of a pinch around the middle, each from the window the last one left
        repeat(10) { window = window.transformed(focus = 0.5f, zoom = 1.1f, pan = 0f, duration = 1800.0) }
        assertEquals(1800.0 / 1.1.pow(10), window.length, 0.5)
        assertEquals(900.0, (window.start + window.end) / 2, 0.5)

        // Dragging a tenth of the width to the right shows earlier seconds
        val moved = ChartWindow(600.0, 1200.0).transformed(focus = 0.5f, zoom = 1f, pan = 0.1f, duration = 1800.0)
        assertEquals(540.0, moved.start, 1e-3)
        assertEquals(1140.0, moved.end, 1e-3)
        // Never past the ride, never shorter than the minimum
        assertEquals(ChartWindow(0.0, 600.0), ChartWindow(0.0, 600.0).transformed(0.5f, 1f, 0.5f, 1800.0))
        assertEquals(ChartWindow.MIN_SECONDS, ChartWindow(0.0, 60.0).transformed(0.5f, 10f, 0f, 1800.0).length, 1e-9)
    }

    // AC6
    @Test
    fun theRangeSliderSetsWholeSecondsAndKeepsTheMinimumLength() {
        assertEquals(ChartWindow(300.0, 1500.0), windowFromRange(299.6f, 1500.2f, 1800.0))
        assertEquals(ChartWindow(1000.0, 1030.0), windowFromRange(1000f, 1010f, 1800.0))
        assertEquals(ChartWindow(1770.0, 1800.0), windowFromRange(1795f, 1800f, 1800.0))
    }
}
