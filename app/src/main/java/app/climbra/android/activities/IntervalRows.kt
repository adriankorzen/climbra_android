package app.climbra.android.activities

import app.climbra.android.data.api.IntervalBlock
import app.climbra.android.data.api.Intervals

/** What a row of the blocks list is, as the web's `IntervalRow.kind`. */
enum class IntervalKind { WORK, OVER, UNDER, REPETITION, RECOVERY }

/** @param firstOfSet the row starts another set than the row before (a divider is drawn) */
data class IntervalRow(val kind: IntervalKind, val block: IntervalBlock, val firstOfSet: Boolean) {
    val key: String get() = "${kind.name}-${block.number}"
}

/**
 * The rows of the blocks list in ride order: the backend's work blocks, and with sets also the unders and
 * recoveries, the blocks marked as overs or repetitions by the kind of their set. Presentation only, the web's
 * `rows` of `activity-detail-page.ts`.
 */
fun intervalRows(intervals: Intervals?): List<IntervalRow> {
    if (intervals == null) return emptyList()
    if (intervals.sets.isEmpty()) {
        return intervals.blocks.map { IntervalRow(IntervalKind.WORK, it, firstOfSet = false) }
    }
    val setKind = intervals.sets.associate { it.number to it.kind }
    fun kindOf(block: IntervalBlock) = when (block.setNumber?.let { setKind[it] }) {
        "OVER_UNDER" -> IntervalKind.OVER
        "REPEATS" -> IntervalKind.REPETITION
        else -> IntervalKind.WORK
    }
    val rows = (
        intervals.blocks.map { kindOf(it) to it } +
            intervals.unders.map { IntervalKind.UNDER to it } +
            intervals.recoveries.map { IntervalKind.RECOVERY to it }
        ).sortedBy { it.second.startSecond }
    return rows.mapIndexed { index, (kind, block) ->
        IntervalRow(kind, block, firstOfSet = index > 0 && block.setNumber != rows[index - 1].second.setNumber)
    }
}
