package app.climbra.android.coach

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.climbra.android.AppConfig
import app.climbra.android.R
import app.climbra.android.calendar.methodologyName
import app.climbra.android.ui.common.openWebPage
import app.climbra.android.ui.format.DateStyle
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.format.rememberFmt
import app.climbra.android.ui.theme.MonoFamily
import app.climbra.android.ui.theme.extendedColors
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.TextStyle

@Composable
fun TrainingBlockFormRoute(
    config: AppConfig,
    onBack: () -> Unit,
    onAccepted: (Long) -> Unit,
    viewModel: TrainingBlockFormViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.accepted) { state.accepted?.let(onAccepted) }
    TrainingBlockFormScreen(
        state = state,
        fmt = rememberFmt(),
        onBack = onBack,
        onChange = viewModel::update,
        onDayChange = viewModel::updateDay,
        onStart = viewModel::start,
        onOpenTerms = { openWebPage(context, config.webPage("terms")) },
    )
}

/** The web's training block form as one scrolling screen (spec 006). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingBlockFormScreen(
    state: TrainingBlockFormState,
    fmt: Fmt,
    onBack: () -> Unit,
    onChange: ((TrainingBlockFormState) -> TrainingBlockFormState) -> Unit,
    onDayChange: (DayOfWeek, (DayAvailability) -> DayAvailability) -> Unit,
    onStart: () -> Unit,
    onOpenTerms: () -> Unit,
) {
    FormScaffold(stringResource(R.string.coach_new_block), onBack, state.sending) {
        DateField(
            label = stringResource(R.string.block_start_date),
            value = state.startDate,
            minDate = state.today,
            fmt = fmt,
            tag = "start-date",
            onPick = { date -> onChange { it.copy(startDate = date ?: it.startDate) } },
        )
        if (state.startInPast) FieldError(stringResource(R.string.block_start_in_past), "start-error")
        Label(stringResource(R.string.block_weeks))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(4, 5, 6).forEachIndexed { index, weeks ->
                SegmentedButton(
                    selected = state.weeks == weeks,
                    onClick = { onChange { it.copy(weeks = weeks) } },
                    shape = SegmentedButtonDefaults.itemShape(index, 3),
                    modifier = Modifier.testTag("weeks-$weeks"),
                ) { Text("$weeks") }
            }
        }
        OutlinedTextField(
            value = state.goal,
            onValueChange = { text -> onChange { it.copy(goal = text) } },
            label = { Text(stringResource(R.string.block_goal)) },
            placeholder = { Text(stringResource(R.string.block_goal_placeholder)) },
            isError = state.submitted && state.goalMissing,
            supportingText = if (state.submitted && state.goalMissing) {
                { Text(stringResource(R.string.block_goal_required), Modifier.testTag("goal-error")) }
            } else {
                null
            },
            minLines = 2,
            modifier = Modifier.fillMaxWidth().testTag("goal"),
        )
        DateField(
            label = stringResource(R.string.block_event_date),
            value = state.eventDate,
            minDate = state.today,
            fmt = fmt,
            tag = "event-date",
            onPick = { date -> onChange { it.copy(eventDate = date) } },
            clearable = true,
        )
        Label(stringResource(R.string.block_availability))
        state.days.forEach { day -> DayRow(day, fmt, onDayChange) }
        if (state.noDay) FieldError(stringResource(R.string.block_no_day), "no-day-error")
        LongRideDay(state, fmt, onChange)
        Label(stringResource(R.string.block_methodology))
        METHODOLOGIES.forEach { methodology ->
            MethodologyCard(methodology, selected = state.methodology == methodology) { onChange { it.copy(methodology = methodology) } }
        }
        OutlinedTextField(
            value = state.notes,
            onValueChange = { text -> onChange { it.copy(notes = text) } },
            label = { Text(stringResource(R.string.block_notes)) },
            placeholder = { Text(stringResource(R.string.block_notes_placeholder)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth().testTag("notes"),
        )
        LanguageChoice(state.language) { language -> onChange { it.copy(language = language) } }
        state.error?.let { CoachRequestError(it, Modifier.testTag("form-error")) }
        HealthNote(onOpenTerms)
        CoachCostLine(state.limits, CostKind.TRAINING_BLOCK, fmt)
        Button(
            onClick = onStart,
            enabled = !state.sending && state.limits.allows(CostKind.TRAINING_BLOCK),
            modifier = Modifier.fillMaxWidth().testTag("create-block"),
        ) { Text(stringResource(R.string.block_create)) }
    }
}

@Composable
internal fun DayRow(
    day: DayAvailability,
    fmt: Fmt,
    onDayChange: (DayOfWeek, (DayAvailability) -> DayAvailability) -> Unit,
    showIndoor: Boolean = true,
) {
    val name = day.day.name.lowercase()
    Row(Modifier.fillMaxWidth().testTag("day-$name"), verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = day.available,
            onCheckedChange = { checked -> onDayChange(day.day) { it.copy(available = checked) } },
            modifier = Modifier.testTag("day-available-$name"),
        )
        Spacer(Modifier.width(8.dp))
        Text(day.day.getDisplayName(TextStyle.SHORT, fmt.locale), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(44.dp))
        if (day.available) {
            FilledTonalIconButton(
                onClick = { onDayChange(day.day) { it.copy(minutes = it.minutes - TrainingBlockFormViewModel.MINUTES_STEP) } },
                enabled = day.minutes > TrainingBlockFormViewModel.MIN_MINUTES,
                modifier = Modifier.size(32.dp).testTag("day-less-$name"),
            ) { SymbolText("−", stringResource(R.string.block_less)) }
            Text(
                "${day.minutes} ${stringResource(R.string.block_minutes)}",
                style = MaterialTheme.typography.labelLarge,
                fontFamily = MonoFamily,
                modifier = Modifier.width(72.dp).padding(horizontal = 6.dp).testTag("day-minutes-$name"),
            )
            FilledTonalIconButton(
                onClick = { onDayChange(day.day) { it.copy(minutes = it.minutes + TrainingBlockFormViewModel.MINUTES_STEP) } },
                enabled = day.minutes < TrainingBlockFormViewModel.MAX_MINUTES,
                modifier = Modifier.size(32.dp).testTag("day-more-$name"),
            ) { SymbolText("+", stringResource(R.string.block_more)) }
            if (showIndoor) {
                Spacer(Modifier.weight(1f))
                Checkbox(
                    checked = day.indoor,
                    onCheckedChange = { checked -> onDayChange(day.day) { it.copy(indoor = checked) } },
                    modifier = Modifier.testTag("day-indoor-$name"),
                )
                Text(stringResource(R.string.workout_indoor), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** A text with a spoken label in place of the symbol. */
@Composable
private fun SymbolText(text: String, description: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { contentDescription = description },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LongRideDay(state: TrainingBlockFormState, fmt: Fmt, onChange: ((TrainingBlockFormState) -> TrainingBlockFormState) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val name: (DayOfWeek?) -> String = { it?.getDisplayName(TextStyle.FULL_STANDALONE, fmt.locale) ?: Fmt.DASH }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = name(state.longRideDay),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.block_long_ride_day)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).testTag("long-ride-day"),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (listOf(null) + DayOfWeek.entries).forEach { day ->
                DropdownMenuItem(
                    text = { Text(name(day)) },
                    onClick = {
                        expanded = false
                        onChange { it.copy(longRideDay = day) }
                    },
                    modifier = Modifier.testTag("long-ride-${day?.name?.lowercase() ?: "none"}"),
                )
            }
        }
    }
}

private val METHODOLOGY_HINTS = mapOf(
    "AUTO" to R.string.methodology_hint_auto,
    "POLARIZED" to R.string.methodology_hint_polarized,
    "PYRAMIDAL" to R.string.methodology_hint_pyramidal,
    "SWEET_SPOT" to R.string.methodology_hint_sweet_spot,
)

@Composable
private fun MethodologyCard(methodology: String, selected: Boolean, onSelect: () -> Unit) {
    OutlinedCard(
        onClick = onSelect,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("methodology-${methodology.lowercase()}"),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onSelect)
            Column {
                Text(methodologyName(methodology), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(stringResource(METHODOLOGY_HINTS.getValue(methodology)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun LanguageChoice(language: String, onChange: (String) -> Unit) {
    Label(stringResource(R.string.coach_language))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("language")) {
        COACH_LANGUAGES.forEachIndexed { index, code ->
            SegmentedButton(
                selected = language == code,
                onClick = { onChange(code) },
                shape = SegmentedButtonDefaults.itemShape(index, COACH_LANGUAGES.size),
                modifier = Modifier.testTag("language-$code"),
            ) { Text(stringResource(if (code == "pl") R.string.language_pl else R.string.language_en)) }
        }
    }
}

@Composable
internal fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { heading() })
}

@Composable
internal fun FieldError(text: String, tag: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(tag))
}

/** A day chosen in a date picker, today or later; [clearable] for an optional date. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateField(
    label: String,
    value: LocalDate?,
    minDate: LocalDate,
    fmt: Fmt,
    tag: String,
    onPick: (LocalDate?) -> Unit,
    clearable: Boolean = false,
    maxDate: LocalDate? = null,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                value?.let { fmt.date(it.toString(), DateStyle.LONG) } ?: Fmt.DASH,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("$tag-value"),
            )
        }
        if (clearable && value != null) {
            IconButton(onClick = { onPick(null) }, modifier = Modifier.testTag("$tag-clear")) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.block_clear_date))
            }
        }
        OutlinedButton(onClick = { open = true }, modifier = Modifier.testTag(tag)) {
            Icon(painterResource(R.drawable.ic_calendar_month), null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.edit))
        }
    }
    if (open) {
        val min = minDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val max = maxDate?.atStartOfDay()?.toInstant(ZoneOffset.UTC)?.toEpochMilli() ?: Long.MAX_VALUE
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = (value ?: minDate).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis in min..max
                override fun isSelectableYear(year: Int) = year >= minDate.year && (maxDate == null || year <= maxDate.year)
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        open = false
                        picker.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    },
                ) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(picker) }
    }
}

/** A coach form's frame: back, title, a progress bar while sending, a scrolling column. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FormScaffold(title: String, onBack: () -> Unit, sending: Boolean, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("form-back")) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (sending) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("form-sending"))
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { content() }
        }
    }
}

@Composable
fun FileReviewRoute(
    config: AppConfig,
    onBack: () -> Unit,
    onAccepted: (Long) -> Unit,
    viewModel: FileReviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.accepted) { state.accepted?.let(onAccepted) }
    val files = remember(context) { ContentResolverFileSource(context) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.choose(PickedFile(uri.toString(), files.name(uri.toString()) ?: uri.lastPathSegment.orEmpty()))
    }
    FileReviewScreen(
        state = state,
        fmt = rememberFmt(),
        onBack = onBack,
        onChooseFile = { picker.launch(RIDE_FILE_TYPES) },
        onChange = viewModel::update,
        onStart = viewModel::start,
        onOpenTerms = { openWebPage(context, config.webPage("terms")) },
    )
}

/** A FIT / ZIP file that is not stored as a ride, reviewed by the coach (spec 006, the web's `file-review-form`). */
@Composable
fun FileReviewScreen(
    state: FileReviewState,
    fmt: Fmt,
    onBack: () -> Unit,
    onChooseFile: () -> Unit,
    onChange: ((FileReviewState) -> FileReviewState) -> Unit,
    onStart: () -> Unit,
    onOpenTerms: () -> Unit,
) {
    FormScaffold(stringResource(R.string.coach_review_file), onBack, state.sending) {
        Text(stringResource(R.string.coach_review_file_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedCard(onClick = onChooseFile, enabled = !state.sending, modifier = Modifier.fillMaxWidth().testTag("choose-file")) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_upload_file), null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(
                    state.file?.name ?: stringResource(R.string.file_review_choose),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("chosen-file-name"),
                )
            }
        }
        if (state.fileError) FieldError(stringResource(R.string.coach_file_type), "file-error")
        RpeScale(state.rpe, { rpe -> onChange { it.copy(rpe = rpe) } }, enabled = !state.sending)
        OutlinedTextField(
            value = state.note,
            onValueChange = { text -> onChange { it.copy(note = text) } },
            label = { Text(stringResource(R.string.ask_coach_note)) },
            placeholder = { Text(stringResource(R.string.ask_coach_note_placeholder)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth().testTag("note"),
        )
        LanguageChoice(state.language) { language -> onChange { it.copy(language = language) } }
        state.error?.let { CoachRequestError(it, Modifier.testTag("form-error")) }
        HealthNote(onOpenTerms)
        CoachCostLine(state.limits, CostKind.REVIEW, fmt)
        Button(onClick = onStart, enabled = state.canStart, modifier = Modifier.fillMaxWidth().testTag("start-review")) {
            Text(stringResource(R.string.ask_coach_start))
        }
    }
}
