package app.climbra.android.activities

import app.climbra.android.dashboard.label
import app.climbra.android.data.api.IntervalBlock
import app.climbra.android.data.api.IntervalSet
import app.climbra.android.data.api.Intervals
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
}
