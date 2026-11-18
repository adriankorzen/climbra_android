package app.climbra.android.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.climbra.android.BuildConfig
import app.climbra.android.R
import app.climbra.android.coach.FieldError
import app.climbra.android.coach.FormScaffold
import app.climbra.android.coach.Label
import app.climbra.android.data.api.FEEDBACK_TYPES
import app.climbra.android.data.api.FeedbackApi
import app.climbra.android.data.api.FeedbackRequest
import app.climbra.android.data.net.ApiError
import app.climbra.android.data.net.ApiResult
import app.climbra.android.data.net.Network
import app.climbra.android.data.net.apiCall
import app.climbra.android.rider.RiderRepository
import app.climbra.android.ui.common.ErrorBanner
import app.climbra.android.ui.common.UiText
import app.climbra.android.ui.common.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FeedbackUiState(
    val name: String = "",
    val type: String? = null,
    val message: String = "",
    /** Send was tapped: missing fields show their error. */
    val submitted: Boolean = false,
    val sending: Boolean = false,
    val error: ApiError? = null,
    val sent: Boolean = false,
) {
    val nameMissing: Boolean get() = name.isBlank()
    val typeMissing: Boolean get() = type == null
    val messageMissing: Boolean get() = message.isBlank()
    val valid: Boolean get() = !nameMissing && !typeMissing && !messageMissing
}

/** The web's `feedback-dialog` (spec 010): name, type and message, sent with where the app is. */
@HiltViewModel
class FeedbackViewModel @Inject constructor(
    private val riders: RiderRepository,
    private val api: FeedbackApi,
) : ViewModel() {

    /** Sent as `path`: the app and its version. */
    internal var version: String = BuildConfig.VERSION_NAME

    private val mutableState = MutableStateFlow(FeedbackUiState())
    val state: StateFlow<FeedbackUiState> = mutableState.asStateFlow()

    init {
        // The rider's name, unless the rider typed one first
        viewModelScope.launch {
            val name = (riders.load() as? ApiResult.Success)?.value?.name.orEmpty()
            mutableState.update { if (it.name.isEmpty()) it.copy(name = name.take(MAX_NAME)) else it }
        }
    }

    fun setName(text: String) = mutableState.update { it.copy(name = text.take(MAX_NAME)) }

    fun setType(type: String) = mutableState.update { it.copy(type = type) }

    fun setMessage(text: String) = mutableState.update { it.copy(message = text.take(MAX_MESSAGE)) }

    fun send() {
        val current = state.value
        if (current.sending) return
        if (!current.valid) {
            mutableState.update { it.copy(submitted = true) }
            return
        }
        mutableState.update { it.copy(submitted = true, sending = true, error = null) }
        val request = FeedbackRequest(
            name = current.name.trim(),
            type = current.type!!,
            message = current.message.trim(),
            path = "android/$version".take(MAX_PATH),
        )
        viewModelScope.launch {
            when (val result = apiCall(Network.json) { api.send(request) }) {
                is ApiResult.Success -> mutableState.update { it.copy(sending = false, sent = true) }
                is ApiResult.Failure -> mutableState.update { it.copy(sending = false, error = result.error) }
            }
        }
    }

    companion object {
        const val MAX_NAME = 100
        const val MAX_MESSAGE = 5000
        const val MAX_PATH = 500
    }
}

@Composable
fun FeedbackRoute(onBack: () -> Unit, onSent: () -> Unit, viewModel: FeedbackViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.sent) { if (state.sent) onSent() }
    FeedbackScreen(
        state = state,
        actions = FeedbackActions(
            onBack = onBack,
            onName = viewModel::setName,
            onType = viewModel::setType,
            onMessage = viewModel::setMessage,
            onSend = viewModel::send,
        ),
    )
}

data class FeedbackActions(
    val onBack: () -> Unit = {},
    val onName: (String) -> Unit = {},
    val onType: (String) -> Unit = {},
    val onMessage: (String) -> Unit = {},
    val onSend: () -> Unit = {},
)

@Composable
fun feedbackTypeName(type: String): String = stringResource(
    when (type) {
        "BUG" -> R.string.feedback_type_bug
        "IMPROVEMENT" -> R.string.feedback_type_improvement
        "SUGGESTION" -> R.string.feedback_type_suggestion
        else -> R.string.feedback_type_other
    },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FeedbackScreen(state: FeedbackUiState, actions: FeedbackActions) {
    FormScaffold(stringResource(R.string.feedback_title), actions.onBack, state.sending) {
        Text(stringResource(R.string.feedback_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = state.name,
            onValueChange = actions.onName,
            label = { Text(stringResource(R.string.feedback_name)) },
            singleLine = true,
            isError = state.submitted && state.nameMissing,
            modifier = Modifier.fillMaxWidth().testTag("feedback-name"),
        )
        if (state.submitted && state.nameMissing) FieldError(stringResource(R.string.feedback_required), "feedback-name-error")
        Label(stringResource(R.string.feedback_type))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FEEDBACK_TYPES.forEach { type ->
                FilterChip(
                    selected = state.type == type,
                    onClick = { actions.onType(type) },
                    label = { Text(feedbackTypeName(type)) },
                    modifier = Modifier.testTag("feedback-type-${type.lowercase()}"),
                )
            }
        }
        if (state.submitted && state.typeMissing) FieldError(stringResource(R.string.feedback_required), "feedback-type-error")
        OutlinedTextField(
            value = state.message,
            onValueChange = actions.onMessage,
            label = { Text(stringResource(R.string.feedback_message)) },
            placeholder = { Text(stringResource(R.string.feedback_message_placeholder)) },
            supportingText = {
                Text(stringResource(R.string.feedback_chars, state.message.length, FeedbackViewModel.MAX_MESSAGE), modifier = Modifier.testTag("feedback-chars"))
            },
            isError = state.submitted && state.messageMissing,
            minLines = 6,
            modifier = Modifier.fillMaxWidth().testTag("feedback-message"),
        )
        if (state.submitted && state.messageMissing) FieldError(stringResource(R.string.feedback_required), "feedback-message-error")
        state.error?.let { ErrorBanner(feedbackErrorText(it).resolve(), Modifier.testTag("feedback-error")) }
        Button(onClick = actions.onSend, enabled = !state.sending, modifier = Modifier.fillMaxWidth().testTag("feedback-send")) {
            Text(stringResource(if (state.sending) R.string.feedback_sending else R.string.feedback_send))
        }
    }
}

fun feedbackErrorText(error: ApiError): UiText =
    if ((error as? ApiError.Http)?.status == 429) UiText(R.string.feedback_too_many) else error.toUiText()
