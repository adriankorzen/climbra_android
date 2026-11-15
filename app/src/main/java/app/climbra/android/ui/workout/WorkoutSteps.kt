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
    val steps = blocks.flatMap { block -> List(block.repetitions.coerceAtLeast(1)) { block.steps }.flatten() }
    val colors = steps.map { zoneColor(it) }
    val axis = MaterialTheme.extendedColors.chartAxis
    val description = stringResource(R.string.workout_profile)
    val total = steps.sumOf { it.durationSeconds }.coerceAtLeast(1)
    val top = (steps.maxOfOrNull { it.powerHighPctFtp } ?: 100).coerceAtLeast(100) * 1.1f
    Canvas(modifier.testTag("workout-chart").semantics { contentDescription = description }) {
        var x = 0f
        steps.forEachIndexed { index, step ->
            val width = size.width * step.durationSeconds / total
            val height = size.height * step.powerHighPctFtp / top
            drawRect(colors[index].copy(alpha = 0.8f), topLeft = Offset(x, size.height - height), size = Size(width, height))
            x += width
        }
        // The FTP line
        val ftp = size.height - size.height * 100f / top
        drawLine(axis, Offset(0f, ftp), Offset(size.width, ftp), strokeWidth = 1.dp.toPx())
    }
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
