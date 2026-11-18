package app.climbra.android.activities

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.climbra.android.AppConfig
import app.climbra.android.R
import app.climbra.android.coach.FormScaffold
import app.climbra.android.coach.PickedFile
import app.climbra.android.coach.RideFileSource
import app.climbra.android.coach.part
import app.climbra.android.data.api.ImportApi
import app.climbra.android.data.api.ImportItem
import app.climbra.android.data.api.ImportItemStatus
import app.climbra.android.data.api.ImportJob
import app.climbra.android.data.api.ImportJobStatus
import app.climbra.android.data.net.ApiError
import app.climbra.android.data.net.ApiResult
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.apiCall
import app.climbra.android.rider.DataChanges
import app.climbra.android.rider.RiderRepository
import app.climbra.android.ui.common.ErrorBanner
import app.climbra.android.ui.common.UiText
import app.climbra.android.ui.common.openWebPage
import app.climbra.android.ui.common.toUiText
import app.climbra.android.ui.format.DateStyle
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.format.rememberFmt
import app.climbra.android.ui.theme.MonoFamily
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ImportMode { SINGLE, SEVERAL }

/** A file chosen for the import of several rides, with its size when the system tells it. */
data class ChosenFile(val file: PickedFile, val size: Long?)

data class ImportUiState(
    val loading: Boolean = true,
    /** The account has no rider: nothing can be imported. */
    val noRider: Boolean = false,
    val loadError: ApiError? = null,
    val mode: ImportMode = ImportMode.SINGLE,
    // Single ride
    val single: PickedFile? = null,
    val name: String = "",
    // Several rides
    val files: List<ChosenFile> = emptyList(),
    /** The names of the files last chosen that are not ride files. */
    val rejected: List<String> = emptyList(),
    val sending: Boolean = false,
    val error: ApiError? = null,
    /** The app's import of several rides, running or finished. */
    val job: ImportJob? = null,
    /** The stored ride of a single upload: the screen gives way to it. */
    val added: Long? = null,
    /** Files were shared for several rides while an import runs. */
    val sharedWhileRunning: Boolean = false,
) {
    val totalBytes: Long get() = files.sumOf { it.size ?: 0L }
    val overLimit: Boolean get() = totalBytes > MAX_IMPORT_BYTES
    val running: Boolean get() = job?.running == true
    val canAdd: Boolean get() = !loading && !noRider && single != null && !sending
    val canImport: Boolean get() = !loading && !noRider && files.isNotEmpty() && !overLimit && !sending && job == null
}

/**
 * The web's import dialog on a phone (spec 009): one ride, uploaded and analysed right away, or several files of up to
 * 50 MB as one import job, followed by the app-scoped [RideImport].
 */
@HiltViewModel
class ImportViewModel @Inject constructor(
    private val riders: RiderRepository,
    private val api: ImportApi,
    private val rideImport: RideImport,
    private val files: RideFileSource,
    private val shared: SharedRideFiles,
    private val changes: DataChanges,
) : ViewModel() {

    internal var zone: ZoneId = ZoneId.systemDefault()

    private val mutableState = MutableStateFlow(ImportUiState(job = rideImport.job.value))
    val state: StateFlow<ImportUiState> = mutableState.asStateFlow()

    private var riderId: Long? = null

    init {
        viewModelScope.launch {
            rideImport.job.collect { job ->
                mutableState.update { it.copy(job = job, mode = if (job != null) ImportMode.SEVERAL else it.mode) }
            }
        }
        viewModelScope.launch { shared.pending.filterNotNull().collect { shared.take()?.let(::receiveShared) } }
        load()
    }

    fun load() {
        mutableState.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch {
            when (val result = riders.load()) {
                is ApiResult.Failure -> mutableState.update { it.copy(loading = false, loadError = result.error) }
                is ApiResult.Success -> {
                    riderId = result.value?.id
                    mutableState.update { it.copy(loading = false, noRider = result.value == null) }
                }
            }
        }
    }

    fun setMode(mode: ImportMode) = mutableState.update { if (it.sending) it else it.copy(mode = mode, error = null) }

    /** The single ride's file; another kind of file is named as skipped. */
    fun chooseSingle(uri: String) = setSingle(picked(uri))

    private fun setSingle(file: PickedFile) = mutableState.update {
        if (isImportFile(file.name)) {
            it.copy(single = file, rejected = emptyList(), error = null)
        } else {
            it.copy(single = null, rejected = listOf(file.name), error = null)
        }
    }

    fun setName(text: String) = mutableState.update { it.copy(name = text.take(MAX_NAME)) }

    /** Adds ride files to the selection, a name only once; other files are named as skipped. */
    fun addFiles(uris: List<String>) = addPicked(uris.map(::picked))

    private fun addPicked(picked: List<PickedFile>) = mutableState.update { state ->
        val accepted = picked.filter { isImportFile(it.name) }
        val added = accepted.filter { file -> state.files.none { it.file.name == file.name } }
            .distinctBy { it.name }
            .map { ChosenFile(it, files.size(it.uri)) }
        state.copy(
            files = state.files + added,
            rejected = picked.filterNot { isImportFile(it.name) }.map { it.name },
            error = null,
        )
    }

    fun removeFile(file: ChosenFile) = mutableState.update { state -> state.copy(files = state.files - file) }

    fun addRide() {
        val current = state.value
        val file = current.single ?: return
        val rider = riderId ?: return
        if (!current.canAdd) return
        mutableState.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            val name = current.name.trim().ifEmpty { null }
            when (val result = apiCall(Network.json) { api.upload(rider, files.part("file", file), name, zone.id) }) {
                is ApiResult.Success -> {
                    changes.changed()
                    mutableState.update { it.copy(sending = false, added = result.value.id, single = null, name = "") }
                }
                is ApiResult.Failure -> mutableState.update { it.copy(sending = false, error = result.error) }
            }
        }
    }

    fun startImport() {
        val current = state.value
        val rider = riderId ?: return
        if (!current.canImport) return
        mutableState.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            when (val result = rideImport.start(rider, current.files.map { it.file }, zone.id)) {
                is ApiResult.Success -> mutableState.update { it.copy(sending = false, files = emptyList(), rejected = emptyList()) }
                is ApiResult.Failure -> mutableState.update { it.copy(sending = false, error = result.error) }
            }
        }
    }

    /** Clears the result to choose files again. */
    fun importMore() {
        rideImport.clear()
        mutableState.update { it.copy(files = emptyList(), rejected = emptyList(), error = null, sharedWhileRunning = false) }
    }

    /** The screen is shown: the import's end is told here, not in the shell's snackbar. */
    fun setShown(shown: Boolean) {
        rideImport.shown = shown
    }

    /** The ride was opened. */
    fun addedShown() = mutableState.update { it.copy(added = null) }

    /** One shared file is a single ride; several wait for a running import, or replace a finished one's result. */
    private fun receiveShared(picked: List<PickedFile>) {
        when {
            picked.size == 1 -> {
                mutableState.update { it.copy(mode = ImportMode.SINGLE) }
                setSingle(picked.single())
            }
            state.value.running -> mutableState.update { it.copy(mode = ImportMode.SEVERAL, sharedWhileRunning = true) }
            else -> {
                if (state.value.job != null) importMore()
                mutableState.update { it.copy(mode = ImportMode.SEVERAL) }
                addPicked(picked)
            }
        }
    }

    private fun picked(uri: String) = PickedFile(uri, files.name(uri) ?: uri.substringAfterLast('/'))

    companion object {
        const val MAX_NAME = 255
    }
}

@Composable
fun ImportRoute(
    config: AppConfig,
    onBack: () -> Unit,
    onOpenRide: (Long) -> Unit,
    onAdded: (Long) -> Unit,
    onOpenProfile: () -> Unit,
    viewModel: ImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    DisposableEffect(viewModel) {
        viewModel.setShown(true)
        onDispose { viewModel.setShown(false) }
    }
    LaunchedEffect(state.added) {
        state.added?.let {
            viewModel.addedShown()
            onAdded(it)
        }
    }
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.chooseSingle(it.toString()) }
    }
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.addFiles(uris.map { it.toString() })
    }
    ImportScreen(
        state = state,
        fmt = rememberFmt(),
        actions = ImportActions(
            onBack = onBack,
            onRetry = viewModel::load,
            onMode = viewModel::setMode,
            onChooseSingle = { pickOne.launch(arrayOf("*/*")) },
            onName = viewModel::setName,
            onAddRide = viewModel::addRide,
            onChooseFiles = { pickMany.launch(arrayOf("*/*")) },
            onRemove = viewModel::removeFile,
            onImport = viewModel::startImport,
            onImportMore = viewModel::importMore,
            onOpenRide = onOpenRide,
            onOpenWeb = { openWebPage(context, config.webPage("activities")) },
            onOpenProfile = onOpenProfile,
        ),
    )
}

data class ImportActions(
    val onBack: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onMode: (ImportMode) -> Unit = {},
    val onChooseSingle: () -> Unit = {},
    val onName: (String) -> Unit = {},
    val onAddRide: () -> Unit = {},
    val onChooseFiles: () -> Unit = {},
    val onRemove: (ChosenFile) -> Unit = {},
    val onImport: () -> Unit = {},
    val onImportMore: () -> Unit = {},
    val onOpenRide: (Long) -> Unit = {},
    val onOpenWeb: () -> Unit = {},
    val onOpenProfile: () -> Unit = {},
)

/** The web's `import-dialog` as one screen (spec 009). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(state: ImportUiState, fmt: Fmt, actions: ImportActions) {
    FormScaffold(stringResource(R.string.import_title), actions.onBack, state.sending) {
        when {
            state.loading -> LinearProgressIndicator(Modifier.fillMaxWidth().testTag("import-loading"))
            state.loadError != null -> {
                ErrorBanner(state.loadError.toUiText().resolve(), Modifier.testTag("import-load-error"))
                OutlinedButton(onClick = actions.onRetry) { Text(stringResource(R.string.retry)) }
            }
            state.noRider -> {
                Text(stringResource(R.string.import_no_rider), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("import-no-rider"))
                Button(onClick = actions.onOpenProfile, modifier = Modifier.testTag("import-open-profile")) {
                    Text(stringResource(R.string.import_open_profile))
                }
            }
            else -> {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ImportMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = state.mode == mode,
                            onClick = { actions.onMode(mode) },
                            enabled = !state.sending,
                            shape = SegmentedButtonDefaults.itemShape(index, ImportMode.entries.size),
                            modifier = Modifier.testTag("mode-${mode.name.lowercase()}"),
                        ) {
                            Text(stringResource(if (mode == ImportMode.SINGLE) R.string.import_single else R.string.import_several))
                        }
                    }
                }
                when (state.mode) {
                    ImportMode.SINGLE -> Single(state, actions)
                    ImportMode.SEVERAL -> Several(state, fmt, actions)
                }
            }
        }
    }
}

@Composable
private fun Single(state: ImportUiState, actions: ImportActions) {
    Hint(stringResource(R.string.import_single_hint))
    OutlinedButton(onClick = actions.onChooseSingle, enabled = !state.sending, modifier = Modifier.fillMaxWidth().testTag("choose-single")) {
        Icon(painterResource(R.drawable.ic_upload_file), null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(state.single?.name ?: stringResource(R.string.import_choose_file), modifier = Modifier.testTag("single-name"))
    }
    Rejected(state.rejected)
    OutlinedTextField(
        value = state.name,
        onValueChange = actions.onName,
        label = { Text(stringResource(R.string.import_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("ride-name"),
    )
    Hint(stringResource(R.string.import_sources_hint))
    ImportError(state.error, single = true, actions)
    Button(onClick = actions.onAddRide, enabled = state.canAdd, modifier = Modifier.fillMaxWidth().testTag("add-ride")) {
        Text(stringResource(if (state.sending) R.string.import_uploading else R.string.import_add_ride))
    }
}

@Composable
private fun Several(state: ImportUiState, fmt: Fmt, actions: ImportActions) {
    val job = state.job
    when {
        job != null && job.running -> {
            Progress(job, fmt)
            if (state.sharedWhileRunning) {
                ErrorBanner(stringResource(R.string.api_error_import_running), Modifier.testTag("shared-while-running"))
            }
        }
        job != null -> Result(job, fmt, actions)
        else -> Selection(state, fmt, actions)
    }
}

@Composable
private fun Selection(state: ImportUiState, fmt: Fmt, actions: ImportActions) {
    Hint(stringResource(R.string.import_several_hint))
    OutlinedButton(onClick = actions.onChooseFiles, enabled = !state.sending, modifier = Modifier.fillMaxWidth().testTag("choose-files")) {
        Icon(painterResource(R.drawable.ic_upload_file), null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.import_choose_files))
    }
    Rejected(state.rejected)
    state.files.forEach { chosen ->
        Row(Modifier.fillMaxWidth().testTag("chosen-${chosen.file.name}"), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(chosen.file.name, style = MaterialTheme.typography.bodyMedium)
                chosen.size?.let {
                    Text(stringResource(R.string.import_size_mb, fmt.num(it.toDouble() / MB, 1)), style = MaterialTheme.typography.bodySmall, fontFamily = MonoFamily)
                }
            }
            IconButton(onClick = { actions.onRemove(chosen) }, enabled = !state.sending, modifier = Modifier.testTag("remove-${chosen.file.name}")) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.import_remove))
            }
        }
    }
    if (state.files.isNotEmpty()) {
        Text(
            pluralStringResource(R.plurals.import_selected, state.files.size, state.files.size, fmt.num(state.totalBytes.toDouble() / MB, 1)),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("import-selected"),
        )
    }
    if (state.overLimit) {
        Column(Modifier.testTag("import-over-limit"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ErrorBanner(stringResource(R.string.import_over_limit))
            TextButton(onClick = actions.onOpenWeb, modifier = Modifier.testTag("import-open-web")) { Text(stringResource(R.string.dashboard_open_web)) }
        }
    }
    Hint(stringResource(R.string.import_thresholds_hint))
    ImportError(state.error, single = false, actions)
    Button(onClick = actions.onImport, enabled = state.canImport, modifier = Modifier.fillMaxWidth().testTag("start-import")) {
        Text(stringResource(if (state.sending) R.string.import_uploading else R.string.import_start))
    }
}

@Composable
private fun Progress(job: ImportJob, fmt: Fmt) {
    Column(Modifier.testTag("import-progress"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val total = job.selectedRides
        val text = when {
            job.status == ImportJobStatus.QUEUED -> stringResource(R.string.import_queued)
            total == null -> stringResource(R.string.import_scanning)
            else -> stringResource(R.string.import_progress, fmt.num(job.processedFiles), fmt.num(total))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium)
        if (total != null && total > 0 && job.status == ImportJobStatus.RUNNING) {
            LinearProgressIndicator(progress = { job.processedFiles.toFloat() / total }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Hint(stringResource(R.string.import_leave_hint))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Result(job: ImportJob, fmt: Fmt, actions: ImportActions) {
    Column(Modifier.testTag("import-result"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (job.status == ImportJobStatus.COMPLETED) {
            Text(stringResource(R.string.import_completed), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("import-completed"))
        } else {
            ErrorBanner(stringResource(R.string.import_failed, job.message.orEmpty()), Modifier.testTag("import-failed"))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Counter(stringResource(R.string.import_counter_imported), job.imported, fmt, "counter-imported")
            Counter(stringResource(R.string.import_counter_duplicates), job.duplicates, fmt, "counter-duplicates")
            Counter(stringResource(R.string.import_counter_skipped), job.skipped, fmt, "counter-skipped")
            Counter(stringResource(R.string.import_counter_failed), job.failed, fmt, "counter-failed")
            if (job.overLimit > 0) Counter(stringResource(R.string.import_counter_over_limit), job.overLimit, fmt, "counter-over-limit")
        }
        if (job.firstRideDate != null && job.lastRideDate != null) {
            Text(
                stringResource(
                    R.string.import_range,
                    fmt.date(job.firstRideDate, DateStyle.DAY_MONTH_YEAR),
                    fmt.date(job.lastRideDate, DateStyle.DAY_MONTH_YEAR),
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("import-range"),
            )
        }
        val notImported = job.items.filter { it.status != ImportItemStatus.IMPORTED }
        if (notImported.isNotEmpty()) NotImported(notImported, actions)
        Button(onClick = actions.onImportMore, modifier = Modifier.testTag("import-more")) { Text(stringResource(R.string.import_more)) }
    }
}

@Composable
private fun Counter(label: String, value: Int, fmt: Fmt, tag: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.testTag(tag)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(fmt.num(value), style = MaterialTheme.typography.titleMedium, fontFamily = MonoFamily)
        }
    }
}

@Composable
private fun NotImported(items: List<ImportItem>, actions: ImportActions) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.testTag("not-imported")) {
        TextButton(onClick = { open = !open }, modifier = Modifier.testTag("not-imported-toggle")) {
            Text(pluralStringResource(R.plurals.import_not_imported, items.size, items.size))
            Icon(painterResource(R.drawable.ic_expand_more), null, Modifier.size(18.dp))
        }
        if (open) {
            items.forEachIndexed { index, item ->
                val opens = item.status == ImportItemStatus.DUPLICATE && item.activityId != null
                Column(
                    Modifier.fillMaxWidth()
                        .then(if (opens) Modifier.clickable { actions.onOpenRide(item.activityId!!) } else Modifier)
                        .padding(vertical = 4.dp)
                        .testTag("item-$index"),
                ) {
                    Text(item.fileName, style = MaterialTheme.typography.bodyMedium)
                    Text(itemStatusName(item.status), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    item.message?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun itemStatusName(status: ImportItemStatus): String = stringResource(
    when (status) {
        ImportItemStatus.IMPORTED -> R.string.import_status_imported
        ImportItemStatus.DUPLICATE -> R.string.import_status_duplicate
        ImportItemStatus.NOT_CYCLING -> R.string.import_status_not_cycling
        ImportItemStatus.OVER_LIMIT -> R.string.import_status_over_limit
        ImportItemStatus.INVALID -> R.string.import_status_invalid
        ImportItemStatus.FAILED -> R.string.import_status_failed
        ImportItemStatus.TOO_LARGE -> R.string.import_status_too_large
        ImportItemStatus.UNAVAILABLE -> R.string.import_status_unavailable
        ImportItemStatus.DELETED -> R.string.import_status_deleted
    },
)

@Composable
private fun Rejected(names: List<String>) {
    if (names.isNotEmpty()) {
        Text(
            stringResource(R.string.import_unsupported, names.joinToString(", ")),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("import-rejected"),
        )
    }
}

/** The answers of the upload and the import, as the web words them. */
@Composable
private fun ImportError(error: ApiError?, single: Boolean, actions: ImportActions) {
    error ?: return
    val http = error as? ApiError.Http
    val duplicate = http?.takeIf { it.status == 409 }?.activityId
    Column(Modifier.testTag("import-error"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ErrorBanner(importErrorText(error, single).resolve())
        duplicate?.let { id ->
            TextButton(onClick = { actions.onOpenRide(id) }, modifier = Modifier.testTag("open-duplicate")) {
                Text(stringResource(R.string.import_open_ride))
            }
        }
    }
}

fun importErrorText(error: ApiError, single: Boolean): UiText {
    val http = error as? ApiError.Http ?: return error.toUiText()
    return when {
        http.status == 409 && http.activityId != null -> UiText(R.string.import_duplicate)
        http.status == 413 -> UiText(if (single) R.string.import_too_large_single else R.string.import_too_large)
        http.status == 422 -> UiText(R.string.import_unreadable)
        else -> error.toUiText()
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private const val MB = 1024.0 * 1024.0
