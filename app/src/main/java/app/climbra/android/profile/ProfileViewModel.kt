package app.climbra.android.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.climbra.android.R
import app.climbra.android.data.api.AccountResponse
import app.climbra.android.coach.CoachQuotaRepository
import app.climbra.android.data.api.CoachQuota
import app.climbra.android.data.api.GarminConnection
import app.climbra.android.data.api.GarminStatus
import app.climbra.android.data.api.IntervalsConnectRequest
import app.climbra.android.data.api.IntervalsConnection
import app.climbra.android.data.api.IntervalsThresholds
import app.climbra.android.data.api.ProfileApi
import app.climbra.android.data.api.Rider
import app.climbra.android.data.api.ThresholdChange
import app.climbra.android.data.api.ThresholdEntry
import app.climbra.android.data.api.Zones
import app.climbra.android.data.net.ApiError
import app.climbra.android.data.net.ApiResult
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.apiCall
import app.climbra.android.garmin.GarminConnectFlow
import app.climbra.android.garmin.GarminSync
import app.climbra.android.intervals.IntervalsSync
import app.climbra.android.intervals.SyncOrigin
import app.climbra.android.rider.DataChanges
import app.climbra.android.rider.RiderRepository
import app.climbra.android.session.SessionRepository
import app.climbra.android.session.SessionState
import app.climbra.android.ui.common.UiText
import app.climbra.android.ui.common.toUiText
import app.climbra.android.ui.theme.ThemeMode
import app.climbra.android.ui.theme.ThemeSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One independently loaded part of the profile: a failure shows its error while the others show their data. */
data class Section<T>(val loading: Boolean = false, val value: T? = null, val failed: Boolean = false)

/** Asked before an action that cannot be undone. */
sealed interface Confirmation {
    data class DeleteThreshold(val entry: ThresholdEntry) : Confirmation
    data object DisconnectIntervals : Confirmation
    data object DisconnectGarmin : Confirmation
}

/** The delete account dialog: the password, or the email for an account without a password. */
data class DeleteAccountForm(val value: String = "", val busy: Boolean = false, val error: UiText? = null)

/** Which section a Retry loads again. */
enum class ProfileSection { THRESHOLDS, ZONES, INTERVALS, INTERVALS_THRESHOLDS, GARMIN, QUOTA }

data class ProfileUiState(
    /** The first load of the rider; later loads are [refreshing]. */
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    /** The rider could not be loaded: nothing of the rider's sections is shown. */
    val riderFailed: Boolean = false,
    val rider: Rider? = null,
    /** The account has no rider profile yet: the rider form creates it. */
    val noRider: Boolean = false,
    val account: AccountResponse? = null,
    /** The admin's Garmin sync runs (spec 011). */
    val garminSyncing: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val thresholds: Section<List<ThresholdEntry>> = Section(),
    val zones: Section<Zones> = Section(),
    val intervals: Section<IntervalsConnection> = Section(),
    val intervalsThresholds: Section<IntervalsThresholds> = Section(),
    val garmin: Section<GarminConnection> = Section(),
    val quota: Section<CoachQuota> = Section(),
    val riderForm: RiderForm = RiderForm(),
    /** The threshold sheet is open. */
    val thresholdForm: ThresholdForm? = null,
    /** An error of the threshold sheet. */
    val thresholdError: UiText? = null,
    val intervalsForm: IntervalsForm = IntervalsForm(),
    val intervalsError: UiText? = null,
    /** An intervals.icu sync is followed. */
    val syncing: Boolean = false,
    /** A disconnect or delete is running. */
    val busy: Boolean = false,
    val passwordForm: PasswordForm = PasswordForm(),
    val passwordError: UiText? = null,
    val deleteAccount: DeleteAccountForm? = null,
    val confirmation: Confirmation? = null,
    val languageBusy: Boolean = false,
    /** A result for the snackbar; [ProfileViewModel.messageShown] clears it. */
    val message: UiText? = null,
)

/**
 * The profile (spec 004): loads the rider and each section, and runs the changes. The figures (W/kg, the resolved FTP,
 * the re-analysed count, zones) are the backend's (cyclingapp spec 053).
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val riders: RiderRepository,
    private val api: ProfileApi,
    private val session: SessionRepository,
    private val changes: DataChanges,
    private val themeSettings: ThemeSettings,
    private val clock: Clock,
    private val quotas: CoachQuotaRepository,
    private val sync: IntervalsSync,
    private val garminSync: GarminSync,
    private val garminConnect: GarminConnectFlow,
) : ViewModel() {

    /** The device's zone decides "today" for the date picker; the backend's rider zone follows it (spec 002). */
    internal var zone: () -> ZoneId = { ZoneId.systemDefault() }

    private val mutableState = MutableStateFlow(ProfileUiState(account = account()))
    val state: StateFlow<ProfileUiState> = mutableState.asStateFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            session.state.collect { session ->
                (session as? SessionState.LoggedIn)?.let { logged -> mutableState.update { it.copy(account = logged.account) } }
            }
        }
        // The credits reload after every coach request (spec 006): the section shows the latest
        viewModelScope.launch {
            quotas.quota.filterNotNull().collect { quota -> mutableState.update { it.copy(quota = Section(value = quota)) } }
        }
        viewModelScope.launch {
            themeSettings.mode.collect { mode -> mutableState.update { it.copy(theme = mode) } }
        }
        viewModelScope.launch {
            garminSync.running.collect { running -> mutableState.update { it.copy(garminSyncing = running) } }
        }
        // The app's one sync (spec 007): "Sync now" shows it whoever started it; when it ends the status reloads
        viewModelScope.launch {
            sync.state.collect { sync -> mutableState.update { it.copy(syncing = sync.running) } }
        }
        viewModelScope.launch {
            sync.finished.drop(1).collect { state.value.rider?.let { loadIntervals(it.id) } }
        }
        // Connecting Garmin Connect ended (spec 016): the card shows the new status
        viewModelScope.launch {
            garminConnect.completed.drop(1).collect { state.value.rider?.let { loadGarmin(it.id) } }
        }
        load()
    }

    fun today(): LocalDate = clock.instant().atZone(zone()).toLocalDate()

    /** Pull to refresh and the rider's Retry: everything again. */
    fun refresh() = load()

    /** The app is back in the foreground: the Garmin status may have changed on the web. */
    fun onResumed() {
        val rider = state.value.rider ?: return
        if (!state.value.loading && !state.value.refreshing) loadGarmin(rider.id)
    }

    fun retry(section: ProfileSection) {
        val riderId = state.value.rider?.id
        when (section) {
            ProfileSection.QUOTA -> loadQuota()
            else -> if (riderId != null) when (section) {
                ProfileSection.THRESHOLDS -> loadThresholds(riderId)
                ProfileSection.ZONES -> loadZones(riderId)
                ProfileSection.INTERVALS -> loadIntervals(riderId)
                ProfileSection.INTERVALS_THRESHOLDS -> loadIntervalsThresholds(riderId)
                ProfileSection.GARMIN -> loadGarmin(riderId)
                ProfileSection.QUOTA -> Unit
            }
        }
    }

    fun messageShown() = mutableState.update { it.copy(message = null) }

    private fun load() {
        if (loadJob?.isActive == true) return
        mutableState.update { it.copy(refreshing = !it.loading, riderFailed = false, quota = it.quota.copy(loading = true)) }
        loadJob = viewModelScope.launch {
            loadQuota()
            when (val result = riders.load()) {
                is ApiResult.Failure -> mutableState.update { it.copy(riderFailed = true) }
                is ApiResult.Success -> showRider(result.value)
            }
            mutableState.update { it.copy(loading = false, refreshing = false) }
        }
    }

    /** The rider's sections are loading from the same update on, so no state shows a rider with nothing loading. */
    private fun showRider(rider: Rider?) {
        mutableState.update {
            it.copy(
                rider = rider,
                noRider = rider == null,
                // A reload keeps the fields being edited (spec 024)
                riderForm = when {
                    rider == null -> RiderForm()
                    it.riderForm.editing && it.rider != null -> it.riderForm
                    else -> riderForm(rider)
                },
                thresholds = it.thresholds.copy(loading = rider != null, failed = false),
                zones = it.zones.copy(loading = rider != null, failed = false),
                intervals = it.intervals.copy(loading = rider != null, failed = false),
                garmin = it.garmin.copy(loading = rider != null, failed = false),
            )
        }
        if (rider != null) {
            loadThresholds(rider.id)
            loadZones(rider.id)
            loadIntervals(rider.id)
            loadGarmin(rider.id)
        }
    }

    private fun riderForm(rider: Rider) = RiderForm(
        name = rider.name,
        weight = decimalText(rider.weightKg),
        restingHr = rider.restingHeartRateBpm?.toString().orEmpty(),
    )

    // Sections

    private fun loadThresholds(riderId: Long) = loadSection(
        get = { it.thresholds },
        set = { state, section -> state.copy(thresholds = section) },
    ) { api.thresholds(riderId) }

    private fun loadZones(riderId: Long) = loadSection(
        get = { it.zones },
        set = { state, section -> state.copy(zones = section) },
    ) { api.zones(riderId) }

    /** The connection, then its thresholds proposal when connected; the proposal is loading from the same update on. */
    private fun loadIntervals(riderId: Long) = viewModelScope.launch {
        mutableState.update { it.copy(intervals = it.intervals.copy(loading = true, failed = false)) }
        val result = call { api.intervals(riderId) }
        val connected = (result as? ApiResult.Success)?.value?.connected == true
        if (result is ApiResult.Success) sync.connectionChanged(connected, riderId)
        mutableState.update {
            it.copy(
                intervals = when (result) {
                    is ApiResult.Success -> Section(value = result.value)
                    is ApiResult.Failure -> it.intervals.copy(loading = false, failed = true)
                },
                intervalsThresholds = when {
                    connected -> it.intervalsThresholds.copy(loading = true, failed = false)
                    result is ApiResult.Success -> Section()
                    else -> it.intervalsThresholds
                },
            )
        }
        if (connected) {
            loadSectionNow(get = { it.intervalsThresholds }, set = { state, section -> state.copy(intervalsThresholds = section) }) {
                api.intervalsThresholds(riderId)
            }
        }
    }

    private fun loadIntervalsThresholds(riderId: Long) = loadSection(
        get = { it.intervalsThresholds },
        set = { state, section -> state.copy(intervalsThresholds = section) },
    ) { api.intervalsThresholds(riderId) }

    private fun loadGarmin(riderId: Long) = loadSection(
        get = { it.garmin },
        set = { state, section -> state.copy(garmin = section) },
    ) { api.garminConnection(riderId) }

    private fun loadQuota() = viewModelScope.launch {
        mutableState.update { it.copy(quota = it.quota.copy(loading = true, failed = false)) }
        when (val result = quotas.reload()) {
            is ApiResult.Success -> mutableState.update { it.copy(quota = Section(value = result.value)) }
            is ApiResult.Failure -> mutableState.update { it.copy(quota = it.quota.copy(loading = false, failed = true)) }
        }
    }

    private fun <T> loadSection(
        get: (ProfileUiState) -> Section<T>,
        set: (ProfileUiState, Section<T>) -> ProfileUiState,
        request: suspend () -> T,
    ) = viewModelScope.launch { loadSectionNow(get, set, request) }

    /** What was loaded before stays while loading again and after a failure. */
    private suspend fun <T> loadSectionNow(
        get: (ProfileUiState) -> Section<T>,
        set: (ProfileUiState, Section<T>) -> ProfileUiState,
        request: suspend () -> T,
    ) {
        mutableState.update { set(it, get(it).copy(loading = true, failed = false)) }
        val result = call(request)
        mutableState.update {
            val previous = get(it)
            set(
                it,
                when (result) {
                    is ApiResult.Success -> Section(value = result.value)
                    is ApiResult.Failure -> previous.copy(loading = false, failed = true)
                },
            )
        }
    }

    // Rider

    fun onRiderChange(form: RiderForm) = mutableState.update { it.copy(riderForm = form.copy(invalid = false)) }

    /** Opens the rider's fields with the saved values (spec 024). */
    fun editRider() = mutableState.update { state ->
        val rider = state.rider ?: return@update state
        state.copy(riderForm = riderForm(rider).copy(editing = true))
    }

    /** Drops the typed values and shows the saved ones. */
    fun cancelRider() = mutableState.update { state ->
        val rider = state.rider
        if (rider == null || state.riderForm.saving) state else state.copy(riderForm = riderForm(rider))
    }

    fun saveRider() {
        val current = state.value
        if (current.riderForm.saving) return
        val request = current.riderForm.request()
        if (request == null) {
            mutableState.update { it.copy(riderForm = it.riderForm.copy(invalid = true)) }
            return
        }
        val rider = current.rider
        mutableState.update { it.copy(riderForm = it.riderForm.copy(saving = true)) }
        viewModelScope.launch {
            val result = if (rider != null) {
                call { api.updateRider(rider.id, request) }
            } else {
                call { api.createRider(request.copy(timeZone = zone().id)) }
            }
            mutableState.update { it.copy(riderForm = it.riderForm.copy(saving = false)) }
            when (result) {
                is ApiResult.Failure -> message(result.error.toUiText())
                is ApiResult.Success -> {
                    message(UiText(if (rider != null) R.string.profile_saved else R.string.profile_created))
                    changes.changed()
                    if (rider != null) {
                        mutableState.update { it.copy(rider = result.value, riderForm = riderForm(result.value)) }
                    } else {
                        showRider(result.value)
                    }
                }
            }
        }
    }

    // Threshold history

    /** Opens the sheet for [entry], or for a new entry valid from today. */
    fun openThresholdForm(entry: ThresholdEntry? = null) = mutableState.update {
        it.copy(
            thresholdError = null,
            thresholdForm = if (entry == null) {
                ThresholdForm(effectiveFrom = today().toString())
            } else {
                ThresholdForm(
                    editingId = entry.id,
                    effectiveFrom = entry.effectiveFrom,
                    ftp = entry.ftpWatts?.toString().orEmpty(),
                    lthr = entry.lthrBpm?.toString().orEmpty(),
                    maxHr = entry.maxHeartRateBpm?.toString().orEmpty(),
                )
            },
        )
    }

    /** "Add to history" of the intervals.icu proposal: a new entry from today, saved by the rider. */
    fun proposeThresholds(proposal: IntervalsThresholds) = mutableState.update {
        it.copy(
            thresholdError = null,
            thresholdForm = ThresholdForm(
                effectiveFrom = today().toString(),
                ftp = proposal.ftpWatts?.toString().orEmpty(),
                lthr = proposal.lthrBpm?.toString().orEmpty(),
                maxHr = proposal.maxHeartRateBpm?.toString().orEmpty(),
            ),
        )
    }

    fun onThresholdChange(form: ThresholdForm) = mutableState.update { it.copy(thresholdForm = form.copy(invalid = false)) }

    fun closeThresholdForm() = mutableState.update { it.copy(thresholdForm = null, thresholdError = null) }

    fun saveThreshold() {
        val current = state.value
        val form = current.thresholdForm ?: return
        val riderId = current.rider?.id ?: return
        if (form.saving) return
        val request = form.request(today())
        if (request == null) {
            mutableState.update { it.copy(thresholdForm = form.copy(invalid = true)) }
            return
        }
        mutableState.update { it.copy(thresholdForm = form.copy(saving = true), thresholdError = null) }
        viewModelScope.launch {
            val id = form.editingId
            val result = call {
                if (id == null) api.createThreshold(riderId, request) else api.updateThreshold(riderId, id, request)
            }
            when (result) {
                is ApiResult.Failure -> mutableState.update {
                    it.copy(
                        thresholdForm = it.thresholdForm?.copy(saving = false),
                        thresholdError = thresholdError(result.error),
                    )
                }
                is ApiResult.Success -> {
                    mutableState.update { it.copy(thresholdForm = null) }
                    thresholdsChanged(riderId, result.value, R.plurals.profile_thresholds_saved)
                }
            }
        }
    }

    /** `409`: an entry valid from that day exists already. */
    private fun thresholdError(error: ApiError) =
        if ((error as? ApiError.Http)?.status == 409) UiText(R.string.profile_threshold_conflict) else error.toUiText()

    fun askDeleteThreshold(entry: ThresholdEntry) = mutableState.update { it.copy(confirmation = Confirmation.DeleteThreshold(entry)) }

    private fun deleteThreshold(entry: ThresholdEntry) {
        val riderId = state.value.rider?.id ?: return
        runBusy {
            when (val result = call { api.deleteThreshold(riderId, entry.id) }) {
                is ApiResult.Failure -> message(result.error.toUiText())
                is ApiResult.Success -> {
                    if (state.value.thresholdForm?.editingId == entry.id) closeThresholdForm()
                    thresholdsChanged(riderId, result.value, R.plurals.profile_threshold_deleted)
                }
            }
        }
    }

    /** Rides were re-analysed: the history, the zones and the rider change, and so may other screens. */
    private suspend fun thresholdsChanged(riderId: Long, change: ThresholdChange, text: Int) {
        message(UiText(text, listOf(change.reanalysed), quantity = change.reanalysed))
        changes.changed()
        loadThresholds(riderId)
        loadZones(riderId)
        (riders.load() as? ApiResult.Success)?.value?.let { rider -> mutableState.update { it.copy(rider = rider) } }
        // The proposal compares with the current thresholds
        if (state.value.intervals.value?.connected == true) loadIntervalsThresholds(riderId)
    }

    // Confirmations

    fun confirm() {
        val confirmation = state.value.confirmation ?: return
        mutableState.update { it.copy(confirmation = null) }
        when (confirmation) {
            is Confirmation.DeleteThreshold -> deleteThreshold(confirmation.entry)
            Confirmation.DisconnectIntervals -> disconnectIntervals()
            Confirmation.DisconnectGarmin -> disconnectGarmin()
        }
    }

    fun dismissConfirmation() = mutableState.update { it.copy(confirmation = null) }

    // intervals.icu

    fun onIntervalsChange(form: IntervalsForm) = mutableState.update { it.copy(intervalsForm = form, intervalsError = null) }

    fun connectIntervals() {
        val current = state.value
        val riderId = current.rider?.id ?: return
        val form = current.intervalsForm
        val apiKey = form.apiKey.trim()
        if (apiKey.isEmpty() || form.busy) return
        mutableState.update { it.copy(intervalsForm = form.copy(busy = true), intervalsError = null) }
        viewModelScope.launch {
            val request = IntervalsConnectRequest(athleteId = form.athleteId.trim().ifEmpty { null }, apiKey = apiKey)
            when (val result = call { api.connectIntervals(riderId, request) }) {
                is ApiResult.Failure -> mutableState.update {
                    it.copy(intervalsForm = it.intervalsForm.copy(busy = false), intervalsError = connectError(result.error))
                }
                is ApiResult.Success -> {
                    mutableState.update { it.copy(intervalsForm = IntervalsForm(), intervals = Section(value = result.value)) }
                    sync.connectionChanged(result.value.connected, riderId)
                    message(UiText(R.string.intervals_connected, listOf(result.value.athleteName.orEmpty())))
                    loadIntervalsThresholds(riderId)
                }
            }
        }
    }

    private fun connectError(error: ApiError) = when ((error as? ApiError.Http)?.status) {
        422 -> UiText(R.string.intervals_rejected)
        503 -> UiText(R.string.intervals_not_configured)
        else -> error.toUiText()
    }

    fun askDisconnectIntervals() = mutableState.update { it.copy(confirmation = Confirmation.DisconnectIntervals) }

    private fun disconnectIntervals() {
        val riderId = state.value.rider?.id ?: return
        runBusy {
            when (val result = call { api.disconnectIntervals(riderId) }) {
                is ApiResult.Failure -> message(result.error.toUiText())
                is ApiResult.Success -> {
                    mutableState.update {
                        it.copy(intervals = Section(value = IntervalsConnection()), intervalsThresholds = Section())
                    }
                    sync.connectionChanged(false, riderId)
                    message(UiText(R.string.intervals_disconnected))
                }
            }
        }
    }

    /**
     * "Sync now": the app's one sync (spec 007), followed in the app scope whichever screen is shown; its messages go
     * to the shell's snackbar, and the status reloads when it ends.
     */
    /** The admin's Garmin sync (spec 011). */
    fun syncGarmin() {
        val riderId = state.value.rider?.id ?: return
        garminSync.sync(riderId, SyncOrigin.BUTTON)
    }

    fun syncIntervals() {
        val riderId = state.value.rider?.id ?: return
        sync.sync(riderId, SyncOrigin.BUTTON)
    }

    // Garmin Connect

    /** The consent dialog, then Garmin's page (spec 016); "Connect again" for a status that needs it. */
    fun connectGarmin() {
        val riderId = state.value.rider?.id ?: return
        garminConnect.ask(riderId, state.value.garmin.value?.status ?: GarminStatus.NOT_CONNECTED)
    }

    fun askDisconnectGarmin() = mutableState.update { it.copy(confirmation = Confirmation.DisconnectGarmin) }

    private fun disconnectGarmin() {
        val riderId = state.value.rider?.id ?: return
        runBusy {
            when (val result = call { api.disconnectGarmin(riderId) }) {
                is ApiResult.Failure -> message(result.error.toUiText())
                is ApiResult.Success -> {
                    message(UiText(R.string.garmin_disconnected))
                    loadGarmin(riderId)
                }
            }
        }
    }

    // Preferences

    fun setLanguage(language: String) {
        val current = state.value.account?.language
        if (language == current || state.value.languageBusy) return
        mutableState.update { it.copy(languageBusy = true) }
        viewModelScope.launch {
            val error = session.changeLanguage(language)
            mutableState.update { it.copy(languageBusy = false) }
            if (error != null) message(error.toUiText())
        }
    }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { themeSettings.setMode(mode) }
    }

    // Account

    fun onPasswordChange(form: PasswordForm) = mutableState.update { it.copy(passwordForm = form, passwordError = null) }

    fun changePassword() {
        val current = state.value
        val form = current.passwordForm
        if (form.saving) return
        val email = current.account?.email.orEmpty()
        val invalid = form.current.isEmpty() || newPasswordProblem(form.password) != null ||
            repeatProblem(form.password, form.repeat, email) != null
        if (invalid) {
            mutableState.update { it.copy(passwordForm = form.copy(showErrors = true)) }
            return
        }
        mutableState.update { it.copy(passwordForm = form.copy(saving = true), passwordError = null) }
        viewModelScope.launch {
            val error = session.changePassword(form.current, form.password)
            if (error == null) {
                mutableState.update { it.copy(passwordForm = PasswordForm()) }
                message(UiText(R.string.password_changed))
            } else {
                mutableState.update { it.copy(passwordForm = it.passwordForm.copy(saving = false), passwordError = error.toUiText()) }
            }
        }
    }

    fun openDeleteAccount() = mutableState.update { it.copy(deleteAccount = DeleteAccountForm()) }

    fun onDeleteAccountChange(value: String) =
        mutableState.update { it.copy(deleteAccount = it.deleteAccount?.copy(value = value, error = null)) }

    fun closeDeleteAccount() = mutableState.update { if (it.deleteAccount?.busy == true) it else it.copy(deleteAccount = null) }

    /** On success the session ends and the login screen replaces the profile. */
    fun deleteAccount() {
        val current = state.value
        val form = current.deleteAccount ?: return
        val account = current.account ?: return
        if (form.busy || form.value.isBlank()) return
        mutableState.update { it.copy(deleteAccount = form.copy(busy = true, error = null)) }
        viewModelScope.launch {
            val error = if (account.hasPassword) {
                session.deleteAccount(password = form.value, email = null)
            } else {
                session.deleteAccount(password = null, email = form.value.trim())
            }
            if (error != null) {
                mutableState.update { it.copy(deleteAccount = it.deleteAccount?.copy(busy = false, error = error.toUiText())) }
            }
        }
    }

    fun logout() {
        viewModelScope.launch { session.logout() }
    }

    // Helpers

    private fun runBusy(action: suspend () -> Unit) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                action()
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private fun message(text: UiText) = mutableState.update { it.copy(message = text) }

    private fun account() = (session.state.value as? SessionState.LoggedIn)?.account

    private suspend fun <T> call(request: suspend () -> T) = apiCall(Network.json, request)

}
