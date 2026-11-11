package app.climbra.android.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.climbra.android.AppConfig
import app.climbra.android.R
import app.climbra.android.ui.common.ErrorBanner
import app.climbra.android.ui.common.openWebPage

@Composable
fun TermsRoute(documentsChanged: Boolean, config: AppConfig, viewModel: TermsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    TermsScreen(
        state = state,
        documentsChanged = documentsChanged,
        onTermsChecked = viewModel::onTermsChecked,
        onHealthDataChecked = viewModel::onHealthDataChecked,
        onAccept = viewModel::accept,
        onLogout = viewModel::logout,
        onOpenTerms = { openWebPage(context, config.webPage("terms")) },
        onOpenPrivacy = { openWebPage(context, config.webPage("privacy")) },
    )
}

@Composable
fun TermsScreen(
    state: TermsUiState,
    documentsChanged: Boolean,
    onTermsChecked: (Boolean) -> Unit,
    onHealthDataChecked: (Boolean) -> Unit,
    onAccept: () -> Unit,
    onLogout: () -> Unit,
    onOpenTerms: () -> Unit,
    onOpenPrivacy: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                Text(stringResource(R.string.terms_title), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(if (documentsChanged) R.string.terms_changed else R.string.terms_intro),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DocumentLink(stringResource(R.string.terms_open_terms), onOpenTerms)
                    DocumentLink(stringResource(R.string.terms_open_privacy), onOpenPrivacy)
                }
                Spacer(Modifier.height(16.dp))
                state.error?.let {
                    ErrorBanner(it.resolve())
                    Spacer(Modifier.height(16.dp))
                }
                Consent(
                    text = stringResource(R.string.terms_accept_terms),
                    checked = state.termsChecked,
                    enabled = !state.submitting,
                    onCheckedChange = onTermsChecked,
                    tag = "terms-accept-terms",
                )
                Spacer(Modifier.height(8.dp))
                Consent(
                    text = stringResource(R.string.terms_accept_health_data),
                    checked = state.healthDataChecked,
                    enabled = !state.submitting,
                    onCheckedChange = onHealthDataChecked,
                    tag = "terms-accept-health-data",
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onAccept,
                    enabled = state.canAccept,
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("terms-submit"),
                ) {
                    if (state.submitting) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.terms_submit))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(
                        stringResource(R.string.terms_refuse),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onLogout, enabled = !state.submitting) { Text(stringResource(R.string.logout)) }
                }
            }
        }
    }
}

@Composable
private fun DocumentLink(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(text)
        Spacer(Modifier.size(4.dp))
        Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun Consent(text: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit, tag: String) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .testTag(tag),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.size(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
    }
}
