package app.climbra.android.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.climbra.android.ui.theme.MonoFamily
import app.climbra.android.ui.theme.extendedColors

/**
 * One key figure, the Android form of the web's `kpi-tile`: label, value with unit, the change against the period
 * before (colored by direction), a hint and a sparkline.
 *
 * @param change text like `▲ 50%` and whether it is a rise; `null` shows none
 * @param tone color of the value and sparkline, e.g. the form range's color
 */
@Composable
fun KpiTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    change: Pair<String, Boolean?>? = null,
    hint: String? = null,
    tone: Color? = null,
    spark: List<Double> = emptyList(),
    tag: String? = null,
) {
    OutlinedCard(modifier = modifier.then(if (tag != null) Modifier.testTag(tag) else Modifier)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    value,
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = MonoFamily,
                    fontWeight = FontWeight.Medium,
                    color = tone ?: MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                if (unit != null) {
                    Text(
                        unit,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
            }
            if (change != null) {
                val (text, rise) = change
                Text(
                    text,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = MonoFamily,
                    color = when (rise) {
                        true -> MaterialTheme.extendedColors.success
                        false -> MaterialTheme.colorScheme.error
                        null -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.labelMedium,
                    color = tone ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (spark.size > 1) {
                Sparkline(spark, tone ?: MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** The trend of a figure, without axes; decorative, so hidden from screen readers. */
@Composable
private fun Sparkline(values: List<Double>, color: Color) {
    Canvas(modifier = Modifier.fillMaxWidth().height(24.dp).clearAndSetSemantics { }) {
        val min = values.min()
        val range = (values.max() - min).takeIf { it > 0 } ?: 1.0
        val step = size.width / (values.size - 1)
        val stroke = 1.5.dp.toPx()
        val path = Path()
        values.forEachIndexed { index, value ->
            val point = Offset(
                x = index * step,
                y = stroke + (size.height - 2 * stroke) * (1 - ((value - min) / range).toFloat()),
            )
            if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
