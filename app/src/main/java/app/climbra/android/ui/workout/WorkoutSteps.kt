package app.climbra.android.ui.workout

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.climbra.android.R
import app.climbra.android.calendar.stepKindName
import app.climbra.android.data.api.WorkoutBlock
import app.climbra.android.data.api.WorkoutStep
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.format.rememberFmt
import app.climbra.android.ui.theme.MonoFamily
import app.climbra.android.ui.theme.extendedColors

// The steps of a workout, shared by the calendar's workout detail (spec 005) and the coach's workout sheet (spec 006)

/** The power zone color of a step, from the backend's zone (cyclingapp spec 054). */
@Composable
private fun zoneColor(step: WorkoutStep): Color {
    val zones = MaterialTheme.extendedColors.powerZones
    return step.zone?.let { zones.getOrNull(it - 1) } ?: MaterialTheme.colorScheme.outlineVariant
}

/** The step profile: each step as wide as its duration and as high as its upper target, in its zone color. */
@Composable
fun WorkoutStepChart(blocks: List<WorkoutBlock>, modifier: Modifier = Modifier) {
    val steps = remember(blocks) { chartSteps(blocks) }
    val colors = steps.map { zoneColor(it.step) }
    val axis = MaterialTheme.extendedColors.chartAxis
    val fmt = rememberFmt()
    val kinds = steps.map { stepKindName(it.step.kind) }
    val description = stringResource(R.string.workout_profile) + ": " +
        steps.indices.joinToString("; ") { stepLabel(steps[it], kinds[it], fmt) }
    val total = steps.lastOrNull()?.end?.coerceAtLeast(1) ?: 1
    val top = (steps.maxOfOrNull { it.step.powerHighPctFtp } ?: 100).coerceAtLeast(100) * 1.1f
    // Spec 023: the step under the finger, its figures above the chart
    var picked by remember(blocks) { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxWidth()) {
        Text(
            picked?.let { stepLabel(steps[it], kinds[it], fmt) } ?: "",
            style = MaterialTheme.typography.labelMedium,
            fontFamily = MonoFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).testTag("workout-chart-label"),
        )
        Canvas(
            modifier.testTag("workout-chart").semantics { contentDescription = description }
                .pointerInput(steps) {
                    detectTapGestures { offset ->
                        val index = stepAt(steps, offset.x / size.width)
                        picked = if (index == picked) null else index
                    }
                }
                .pointerInput(steps) {
                    detectHorizontalDragGestures { change, _ -> picked = stepAt(steps, change.position.x / size.width) }
                },
        ) {
            steps.forEachIndexed { index, chartStep ->
                val x = size.width * chartStep.start / total
                val width = size.width * chartStep.step.durationSeconds / total
                val height = size.height * chartStep.step.powerHighPctFtp / top
                val alpha = when (picked) {
                    null -> 0.8f
                    index -> 1f
                    else -> 0.35f
                }
                drawRect(colors[index].copy(alpha = alpha), topLeft = Offset(x, size.height - height), size = Size(width, height))
            }
            // The FTP line
            val ftp = size.height - size.height * 100f / top
            drawLine(axis, Offset(0f, ftp), Offset(size.width, ftp), strokeWidth = 1.dp.toPx())
        }
    }
}

/** A step as drawn: the blocks with their repetitions, one after the other, from [start] to [end] seconds. */
data class ChartStep(val step: WorkoutStep, val start: Int, val end: Int)

fun chartSteps(blocks: List<WorkoutBlock>): List<ChartStep> {
    var at = 0
    return blocks.flatMap { block -> List(block.repetitions.coerceAtLeast(1)) { block.steps }.flatten() }.map { step ->
        ChartStep(step, at, at + step.durationSeconds).also { at = it.end }
    }
}

/** The step whose time span holds [fraction] of the chart's width, `null` outside it. */
fun stepAt(steps: List<ChartStep>, fraction: Float): Int? {
    val total = steps.lastOrNull()?.end ?: return null
    if (fraction < 0f || fraction > 1f || total <= 0) return null
    val second = fraction * total
    return steps.indexOfFirst { second >= it.start && second < it.end }.takeIf { it >= 0 } ?: steps.lastIndex
}

/** As the web's tooltip: the time span, the kind, % FTP and the backend's watts (left out without them). */
fun stepLabel(chartStep: ChartStep, kind: String, fmt: Fmt): String {
    val step = chartStep.step
    val watts = if (step.powerLowWatts != null && step.powerHighWatts != null) " · ${range(step.powerLowWatts, step.powerHighWatts)} W" else ""
    return "${fmt.duration(chartStep.start)}–${fmt.duration(chartStep.end)} · $kind · " +
        "${range(step.powerLowPctFtp, step.powerHighPctFtp)}% FTP$watts"
}

/** The steps with their repetitions, % FTP, the watts of the current FTP and the cadence. */
@Composable
fun WorkoutStepsTable(blocks: List<WorkoutBlock>, fmt: Fmt) {
    Column(Modifier.fillMaxWidth().testTag("workout-steps")) {
        Row(Modifier.padding(vertical = 4.dp)) {
            HeaderCell(stringResource(R.string.workout_step), Modifier.weight(1.6f))
            HeaderCell(stringResource(R.string.activity_duration), Modifier.weight(1f))
            HeaderCell("% FTP", Modifier.weight(1f))
            HeaderCell(stringResource(R.string.workout_watts), Modifier.weight(1.1f))
            HeaderCell(stringResource(R.string.workout_cadence), Modifier.weight(0.9f))
        }
        HorizontalDivider()
        blocks.forEachIndexed { blockIndex, block ->
            val repeated = block.repetitions > 1
            if (repeated) {
                Text(
                    stringResource(R.string.workout_repeat, block.repetitions),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp).testTag("repeat-$blockIndex"),
                )
            }
            block.steps.forEachIndexed { stepIndex, step ->
                Row(
                    Modifier.fillMaxWidth().padding(start = if (repeated) 12.dp else 0.dp, top = 4.dp, bottom = 4.dp).testTag("step-$blockIndex-$stepIndex"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(Modifier.weight(1.6f), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(zoneColor(step), CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(stepKindName(step.kind), style = MaterialTheme.typography.bodySmall)
                            step.note?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.extendedColors.textFaint)
                            }
                        }
                    }
                    Cell(fmt.duration(step.durationSeconds), Modifier.weight(1f))
                    Cell(range(step.powerLowPctFtp, step.powerHighPctFtp), Modifier.weight(1f))
                    Cell(
                        if (step.powerLowWatts != null && step.powerHighWatts != null) "${range(step.powerLowWatts, step.powerHighWatts)} W" else Fmt.DASH,
                        Modifier.weight(1.1f).testTag("step-watts-$blockIndex-$stepIndex"),
                    )
                    Cell(step.cadenceLow?.let { range(it, step.cadenceHigh ?: it) } ?: Fmt.DASH, Modifier.weight(0.9f))
                }
            }
        }
    }
}

private fun range(low: Int, high: Int): String = if (low == high) "$low" else "$low–$high"

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.extendedColors.textFaint, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Cell(text: String, modifier: Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium, fontFamily = MonoFamily, modifier = modifier)
}
