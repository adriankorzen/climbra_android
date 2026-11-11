package app.climbra.android.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.climbra.android.session.SessionRepository
import app.climbra.android.ui.common.UiText
import app.climbra.android.ui.common.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TermsUiState(
    val termsChecked: Boolean = false,
    val healthDataChecked: Boolean = false,
    val submitting: Boolean = false,
    val error: UiText? = null,
) {
    /** Both consents are required, as on the web (spec 001). */
    val canAccept: Boolean
        get() = termsChecked && healthDataChecked && !submitting
}

@HiltViewModel
class TermsViewModel @Inject constructor(private val session: SessionRepository) : ViewModel() {

    private val mutableState = MutableStateFlow(TermsUiState())
    val state: StateFlow<TermsUiState> = mutableState.asStateFlow()

    fun onTermsChecked(checked: Boolean) = mutableState.update { it.copy(termsChecked = checked) }

    fun onHealthDataChecked(checked: Boolean) = mutableState.update { it.copy(healthDataChecked = checked) }

    fun accept() {
        if (!state.value.canAccept) return
        mutableState.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val error = session.acceptTerms()
            mutableState.update { it.copy(submitting = false, error = error?.toUiText()) }
        }
    }

    fun logout() {
        viewModelScope.launch { session.logout() }
    }
}
