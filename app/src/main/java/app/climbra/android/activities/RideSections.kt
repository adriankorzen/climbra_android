package app.climbra.android.activities

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.climbra.android.R
import app.climbra.android.data.api.CalendarWorkout
import app.climbra.android.data.api.IntervalBlock
import app.climbra.android.data.api.IntervalSet
import app.climbra.android.data.api.Intervals
import app.climbra.android.data.api.PlannedWorkoutLink
import app.climbra.android.data.api.ZoneTime
import app.climbra.android.ui.common.toUiText
import app.climbra.android.ui.format.DateStyle
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.theme.MonoFamily
import app.climbra.android.ui.theme.extendedColors

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, tag: String? = null, content: @Composable () -> Unit) {
    ElevatedCard(modifier = modifier.fillMaxWidth().then(if (tag != null) Modifier.testTag(tag) else Modifier)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** Time and percent per zone as horizontal bars in the zone colors (the web's `zone-bars`). */
@Composable
fun ZoneBars(zones: List<ZoneTime>, colors: List<Color>, fmt: Fmt, tag: String, kind: ZoneKind) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag(tag)) {
        val widest = zones.maxOfOrNull { it.percent }?.takeIf { it > 0 } ?: 100.0
        zones.forEach { zone ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Z${zone.number}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = MonoFamily,
                    modifier = Modifier.width(28.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(zoneName(kind, zone), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Box(Modifier.fillMaxWidth().height(10.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.extraSmall)) {
                        Box(
                            Modifier
                                .fillMaxWidth((zone.percent / widest).toFloat().coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(colors.getOrElse(zone.number - 1) { colors.last() }, MaterialTheme.shapes.extraSmall),
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(72.dp)) {
                    Text(fmt.duration(zone.seconds), style = MaterialTheme.typography.labelMedium, fontFamily = MonoFamily)
                    Text("${fmt.num(zone.percent)}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Summary of the detected intervals, as the web's `.summary` line. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IntervalSummary(intervals: Intervals, fmt: Fmt) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.testTag("interval-summary"),
    ) {
        when (intervals.structure) {
            "REPEATS" -> {
                Badge(stringResource(R.string.activity_repeat_sets))
                Figure(stringResource(R.string.activity_sets), fmt.num(intervals.sets.size))
                Figure(stringResource(R.string.activity_repetition_count), fmt.num(intervals.blocks.size))
            }
            "OVER_UNDER" -> {
                Badge(stringResource(R.string.activity_over_under))
                Figure(stringResource(R.string.activity_sets), fmt.num(intervals.sets.size))
                Figure(stringResource(R.string.activity_overs), fmt.num(intervals.blocks.size))
                Figure(stringResource(R.string.activity_unders), fmt.num(intervals.unders.size))
            }
            else -> Figure(stringResource(R.string.activity_blocks), fmt.num(intervals.blocks.size))
        }
        Figure(
            stringResource(R.string.activity_target),
            "${fmt.num(intervals.targetPower)} W",
            stringResource(if (intervals.targetSource == "MANUAL") R.string.activity_manual else R.string.activity_median),
        )
        intervals.relativeThreshold?.let { Figure(stringResource(R.string.activity_threshold), fmt.pct(it)) }
        Figure(stringResource(R.string.activity_cv_duration), fmt.pct(intervals.durationConsistency, 1))
        Figure(stringResource(R.string.activity_cv_power), fmt.pct(intervals.powerConsistency, 1))
        intervals.totalVo2StimulusSeconds?.let {
            Figure(
                stringResource(R.string.activity_vo2max),
                fmt.duration(it),
                stringResource(R.string.activity_vo2_threshold, fmt.num(intervals.vo2ThresholdBpm)),
            )
        }
        intervals.cardiacDriftPercent?.let { Figure(stringResource(R.string.activity_drift), "${fmt.num(it, 1)}%") }
    }
}

@Composable
private fun Badge(text: String) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer, shape = MaterialTheme.shapes.extraSmall) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun Figure(label: String, value: String, note: String? = null) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelLarge, fontFamily = MonoFamily, fontWeight = FontWeight.Medium)
        if (note != null) Text("($note)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.extendedColors.textFaint)
    }
}

/** The sets of over-unders or repeats; tapping one zooms the chart to it. */
@Composable
fun SetList(sets: List<IntervalSet>, fmt: Fmt, onSelect: (IntervalBlock) -> Unit) {
    val repeatsOnly = sets.isNotEmpty() && sets.all { it.kind == "REPEATS" }
    Column(modifier = Modifier.testTag("interval-sets")) {
        sets.forEachIndexed { index, set ->
            if (index > 0) HorizontalDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(set.asBlock()) }
                    .padding(vertical = 8.dp)
                    .testTag("set-${set.number}"),
            ) {
                Text(
                    "${stringResource(R.string.activity_set, set.number)} · ${fmt.duration(set.startSecond)} · ${fmt.duration(set.durationSeconds)} · " +
                        "${set.repetitions} ${stringResource(R.string.activity_repetitions)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    listOf(
                        "${stringResource(R.string.activity_avg_np)} ${fmt.num(set.normalizedPower)}",
                        "${stringResource(if (repeatsOnly) R.string.activity_work_power else R.string.activity_over_power)} " +
                            "${fmt.num(set.overAveragePower)} W (${fmt.num(set.overPercentFtp)}%)",
                        "${stringResource(if (repeatsOnly) R.string.activity_recovery_power else R.string.activity_under_power)} " +
                            "${fmt.num(set.underAveragePower)} W (${fmt.num(set.underPercentFtp)}%)",
                        "${stringResource(R.string.activity_above_ftp)} ${fmt.duration(set.timeAboveFtpSeconds)}",
                        "${stringResource(R.string.activity_hr_start_end)} ${fmt.num(set.heartRateStart)} → ${fmt.num(set.heartRateEnd)}",
                        "${stringResource(R.string.activity_max_hr)} ${fmt.num(set.maxHeartRate)}",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun IntervalSet.asBlock() = IntervalBlock(
    number = number,
    startSecond = startSecond,
    endSecond = endSecond,
    durationSeconds = durationSeconds,
    averagePower = overAveragePower,
    maxPower = overAveragePower,
)

/** Work blocks, overs, unders, repetitions and recoveries in ride order; tapping one zooms the chart to it. */
@Composable
fun BlockList(rows: List<IntervalRow>, fmt: Fmt, onSelect: (IntervalBlock) -> Unit) {
    Column(modifier = Modifier.testTag("interval-blocks")) {
        rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(thickness = if (row.firstOfSet) 2.dp else 1.dp)
            val block = row.block
            val kind = when (row.kind) {
                IntervalKind.WORK -> null
                IntervalKind.OVER -> stringResource(R.string.activity_over)
                IntervalKind.UNDER -> stringResource(R.string.activity_under)
                IntervalKind.REPETITION -> stringResource(R.string.activity_work_power)
                IntervalKind.RECOVERY -> stringResource(R.string.activity_recovery_power)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(block) }
                    .padding(vertical = 8.dp)
                    .testTag("block-${row.key}"),
            ) {
                Text(
                    listOfNotNull(
                        "#${block.number}",
                        kind,
                        block.setNumber?.let { stringResource(R.string.activity_set, it) },
                        fmt.duration(block.startSecond),
                        fmt.duration(block.durationSeconds),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    listOfNotNull(
                        "${fmt.num(block.averagePower)} / ${fmt.num(block.maxPower)} W",
                        block.percentOfTarget?.let { "${fmt.num(it)}% ${stringResource(R.string.activity_percent_of_target)}" },
                        "${stringResource(R.string.activity_hr_start_end)} ${fmt.num(block.heartRateStart)} → ${fmt.num(block.heartRateEnd)}",
                        "${stringResource(R.string.activity_max_hr)} ${fmt.num(block.maxHeartRate)}",
                        "${fmt.num(block.efficiency, 2)} ${stringResource(R.string.activity_efficiency)}",
                        block.vo2StimulusSeconds?.let { "${stringResource(R.string.activity_vo2max)} ${fmt.duration(it)}" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Laps of the file or detection from power (cyclingapp spec 017); shown when the file has laps. */
@Composable
fun IntervalSourceSwitch(useLaps: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    var hint by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().testTag("interval-source")) {
            SegmentedButton(
                selected = useLaps,
                onClick = { if (!useLaps) onChange(true) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                modifier = Modifier.testTag("source-laps"),
            ) { Text(stringResource(R.string.activity_source_laps)) }
            SegmentedButton(
                selected = !useLaps,
                onClick = { if (useLaps) onChange(false) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                modifier = Modifier.testTag("source-power"),
            ) { Text(stringResource(R.string.activity_source_power)) }
        }
        TextButton(onClick = { hint = !hint }) { Text(stringResource(R.string.activity_interval_source)) }
        if (hint) Muted(stringResource(R.string.activity_interval_source_hint))
    }
}

/** The web's detection form: target, threshold, minimum duration, maximum gap, under threshold. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetectionFormSection(
    form: DetectionForm,
    invalid: Boolean,
    adHoc: Boolean,
    busy: Boolean,
    onChange: (DetectionForm) -> Unit,
    onApply: () -> Unit,
    onReset: () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { open = !open }, modifier = Modifier.testTag("detection-toggle")) {
            Text(stringResource(R.string.activity_detection))
        }
        if (!open && !adHoc) return@Column
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            @Composable
            fun field(label: Int, value: String, tag: String, update: (String) -> DetectionForm) = OutlinedTextField(
                value = value,
                onValueChange = { onChange(update(it)) },
                label = { Text(stringResource(label)) },
                placeholder = { Text(stringResource(R.string.activity_auto)) },
                singleLine = true,
                isError = invalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(160.dp).testTag(tag),
            )
            field(R.string.activity_target_watts, form.targetWatts, "detection-target") { form.copy(targetWatts = it) }
            field(R.string.activity_threshold, form.thresholdPercent, "detection-threshold") { form.copy(thresholdPercent = it) }
            field(R.string.activity_min_duration, form.minDuration, "detection-min-duration") { form.copy(minDuration = it) }
            field(R.string.activity_max_gap, form.maxGap, "detection-max-gap") { form.copy(maxGap = it) }
            field(R.string.activity_under_threshold, form.underPercent, "detection-under") { form.copy(underPercent = it) }
        }
        if (invalid) Text(stringResource(R.string.activity_detection_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onApply, enabled = !busy, modifier = Modifier.testTag("detection-apply")) {
                Text(stringResource(R.string.activity_recalculate))
            }
            if (adHoc) {
                OutlinedButton(onClick = onReset, modifier = Modifier.testTag("detection-reset")) {
                    Text(stringResource(R.string.activity_reset))
                }
            }
        }
        Text(stringResource(R.string.activity_detection_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extendedColors.textFaint)
    }
}

/** The plan side of a ride (cyclingapp spec 010): the workout and its comparison, or linking an unplanned ride. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlannedWorkoutCard(
    planned: PlannedWorkoutLink?,
    candidates: List<CalendarWorkout>,
    linking: Boolean,
    linkError: app.climbra.android.data.net.ApiError?,
    fmt: Fmt,
    onLink: (Long) -> Unit,
    onOpenCalendar: (String) -> Unit = {},
) {
    if (planned != null) {
        SectionCard(stringResource(R.string.adherence_planned_workout), tag = "planned-workout") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(planned.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                AssistChip(onClick = {}, label = { Text(workoutTypeName(planned.type)) })
                Text(fmt.date(planned.date, DateStyle.WEEKDAY), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.extendedColors.textFaint)
            }
            planned.matchSource?.let {
                Muted(stringResource(if (it == "MANUAL") R.string.adherence_match_manual else R.string.adherence_match_auto))
            }
            planned.comparison?.let { comparison ->
                Text(complianceName(comparison.level), color = complianceColor(comparison.level), style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("comparison-level"))
                comparison.duration?.let {
                    ComparisonRow(stringResource(R.string.activity_duration), fmt.hm(it.planned), fmt.hm(it.actual), fmt.pct(it.ratio), complianceColor(it.level))
                }
                comparison.tss?.let {
                    ComparisonRow(stringResource(R.string.adherence_tss), fmt.num(it.planned), fmt.num(it.actual), fmt.pct(it.ratio), complianceColor(it.level))
                }
                comparison.intensityFactor?.let {
                    ComparisonRow(stringResource(R.string.adherence_if), fmt.num(it.planned, 2), fmt.num(it.actual, 2), fmt.signed(it.delta, 2), complianceColor(it.level))
                }
                comparison.intervals?.let {
                    ComparisonRow(stringResource(R.string.adherence_intervals), fmt.num(it.plannedRepetitions), fmt.num(it.detectedBlocks), fmt.pct(it.ratio), complianceColor(it.level))
                }
            }
            TextButton(onClick = { onOpenCalendar(planned.date) }, modifier = Modifier.testTag("open-calendar")) {
                Icon(painterResource(R.drawable.ic_calendar_month), null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.calendar_show_in_calendar))
            }
        }
    } else if (candidates.isNotEmpty()) {
        SectionCard(stringResource(R.string.adherence_unplanned_ride), tag = "unplanned-ride") {
            var expanded by rememberSaveable { mutableStateOf(false) }
            var selected by rememberSaveable { mutableStateOf<Long?>(null) }
            val chosen = candidates.firstOrNull { it.id == selected }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it && !linking }) {
                OutlinedTextField(
                    value = chosen?.let { "${fmt.date(it.date, DateStyle.WEEKDAY)} · ${it.title}" } ?: "–",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.adherence_link_to_workout)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).testTag("link-workout"),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    candidates.forEach { workout ->
                        DropdownMenuItem(
                            text = { Text("${fmt.date(workout.date, DateStyle.WEEKDAY)} · ${workout.title}") },
                            onClick = {
                                selected = workout.id
                                expanded = false
                            },
                            modifier = Modifier.testTag("link-option-${workout.id}"),
                        )
                    }
                }
            }
            Button(onClick = { selected?.let(onLink) }, enabled = selected != null && !linking, modifier = Modifier.testTag("link-submit")) {
                Text(stringResource(R.string.adherence_link))
            }
            linkError?.let { Text(it.toUiText().resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ComparisonRow(label: String, planned: String, actual: String, deviation: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("${stringResource(R.string.adherence_planned)} $planned", style = MaterialTheme.typography.labelMedium, fontFamily = MonoFamily, modifier = Modifier.width(96.dp))
        Text("${stringResource(R.string.adherence_ridden)} $actual", style = MaterialTheme.typography.labelMedium, fontFamily = MonoFamily, modifier = Modifier.width(96.dp))
        Text(deviation, style = MaterialTheme.typography.labelMedium, fontFamily = MonoFamily, color = color, modifier = Modifier.width(56.dp))
    }
}

/** Which names zones get: power zones, heart rate zones from LTHR or from max heart rate (the web's `zones.*`). */
enum class ZoneKind { POWER, HEART_RATE, HEART_RATE_MAX }

private val POWER_ZONE_NAMES = listOf(
    R.string.zone_power_1, R.string.zone_power_2, R.string.zone_power_3, R.string.zone_power_4,
    R.string.zone_power_5, R.string.zone_power_6, R.string.zone_power_7,
)
private val HR_ZONE_NAMES = listOf(R.string.zone_hr_1, R.string.zone_hr_2, R.string.zone_hr_3, R.string.zone_hr_4, R.string.zone_hr_5)
private val HR_MAX_ZONE_NAMES = listOf(
    R.string.zone_hr_max_1, R.string.zone_hr_max_2, R.string.zone_hr_max_3, R.string.zone_hr_max_4, R.string.zone_hr_max_5,
)

/** The translated name of a zone by its number; the backend's English name for a number the app does not know. */
@Composable
fun zoneName(kind: ZoneKind, zone: ZoneTime): String = zoneName(kind, zone.number, zone.name)

/** [name] is the backend's English name, for a [number] the app does not know. */
@Composable
fun zoneName(kind: ZoneKind, number: Int, name: String): String {
    val names = when (kind) {
        ZoneKind.POWER -> POWER_ZONE_NAMES
        ZoneKind.HEART_RATE -> HR_ZONE_NAMES
        ZoneKind.HEART_RATE_MAX -> HR_MAX_ZONE_NAMES
    }
    return names.getOrNull(number - 1)?.let { stringResource(it) } ?: name
}
