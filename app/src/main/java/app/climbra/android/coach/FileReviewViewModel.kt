package app.climbra.android.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.climbra.android.data.api.CoachApi
import app.climbra.android.data.net.ApiError
import app.climbra.android.data.net.ApiResult
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.apiCall
import app.climbra.android.rider.RiderRepository
import app.climbra.android.session.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Longest note the coach accepts with a review. */
const val MAX_NOTE = 2000

data class FileReviewState(
    val file: PickedFile? = null,
    /** The chosen file is not a FIT / ZIP file. */
    val fileError: Boolean = false,
    val rpe: Int? = null,
    val note: String = "",
    val language: String = "en",
    val limits: CoachLimits = CoachLimits(),
    val sending: Boolean = false,
    val error: ApiError? = null,
    val accepted: Long? = null,
) {
    val canStart: Boolean get() = file != null && !sending && limits.allows(CostKind.REVIEW)
}

/** A review of a FIT / ZIP file that is not stored as a ride (spec 006). */
@HiltViewModel
class FileReviewViewModel @Inject constructor(
    private val riders: RiderRepository,
    private val api: CoachApi,
    private val activity: CoachActivity,
    private val quotas: CoachQuotaRepository,
    private val files: RideFileSource,
    session: SessionRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(FileReviewState(language = appLanguage(session)))
    val state: StateFlow<FileReviewState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { coachLimits(quotas, activity).collect { limits -> mutableState.update { it.copy(limits = limits) } } }
    }

    /** Another type than FIT / ZIP is not taken. */
    fun choose(file: PickedFile) = mutableState.update {
        if (file.rideFile) it.copy(file = file, fileError = false) else it.copy(file = null, fileError = true)
    }

    fun update(change: (FileReviewState) -> FileReviewState) = mutableState.update { change(it).let { next -> next.copy(note = next.note.take(MAX_NOTE)) } }

    fun start() {
        val current = state.value
        val file = current.file ?: return
        if (!current.canStart) return
        mutableState.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            val rider = when (val result = riders.load()) {
                is ApiResult.Failure -> return@launch failed(result.error)
                is ApiResult.Success -> result.value ?: return@launch failed(ApiError.Unexpected)
            }
            val request = apiCall(Network.json) {
                api.startFileReview(rider.id, files.part("file", file), current.language, current.rpe, current.note.trim().ifEmpty { null })
            }
            when (request) {
                is ApiResult.Failure -> failed(request.error)
                is ApiResult.Success -> {
                    activity.track(request.value)
                    mutableState.update { it.copy(sending = false, accepted = request.value.id) }
                    quotas.reload()
                }
            }
        }
    }

    private fun failed(error: ApiError) = mutableState.update { it.copy(sending = false, error = error) }
}
