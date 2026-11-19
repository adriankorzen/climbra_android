package app.climbra.android.garmin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.climbra.android.R
import app.climbra.android.data.api.GarminStatus
import app.climbra.android.ui.common.openWebPage

/** Garmin's privacy notice, linked from the consent as on the web. */
const val GARMIN_PRIVACY_URL = "https://www.garmin.com/privacy/connect"

/**
 * The consent dialog and the Custom Tab of [flow] (spec 016), for the shell: any screen asks for it with
 * [GarminConnectFlow.ask].
 */
@Composable
fun GarminConnectHost(flow: GarminConnectFlow) {
    val context = LocalContext.current
    val consent by flow.consent.collectAsStateWithLifecycle()
    LaunchedEffect(flow) {
        flow.openings.collect { url -> openWebPage(context, url) }
    }
    consent?.let { shown ->
        GarminConsentDialog(
            consent = shown,
            onContinue = flow::continueToGarmin,
            onDismiss = flow::dismiss,
            onOpenPrivacy = { openWebPage(context, GARMIN_PRIVACY_URL) },
        )
    }
}

/**
 * The notice and express consent Garmin's agreement requires before any data goes to Garmin (the web's
 * `GarminConnectDialog`, spec 047 of cyclingapp): Continue needs the checkbox.
 */
@Composable
fun GarminConsentDialog(
    consent: GarminConsent,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
    onOpenPrivacy: () -> Unit,
) {
    var agreed by rememberSaveable(consent.riderId) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.garmin_connect_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val again = when (consent.status) {
                    GarminStatus.NO_PERMISSION -> R.string.garmin_status_no_permission
                    GarminStatus.NEEDS_RECONNECT -> R.string.garmin_status_needs_reconnect
                    else -> null
                }
                if (again != null) {
                    Text(
                        stringResource(again),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("garmin-consent-again"),
                    )
                }
                Text(stringResource(R.string.garmin_dialog_what))
                listOf(R.string.garmin_dialog_garmin_use, R.string.garmin_dialog_restricted, R.string.garmin_dialog_on_click)
                    .forEach { Text("•  " + stringResource(it), style = MaterialTheme.typography.bodyMedium) }
                TextButton(onClick = onOpenPrivacy, modifier = Modifier.testTag("garmin-privacy")) {
                    Text(stringResource(R.string.garmin_dialog_privacy_link))
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = agreed, enabled = !consent.starting, role = Role.Checkbox) { agreed = it }
                        .testTag("garmin-consent"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = agreed, onCheckedChange = null, enabled = !consent.starting)
                    Text(stringResource(R.string.garmin_dialog_consent), modifier = Modifier.padding(start = 8.dp))
                }
                consent.error?.let { error ->
                    Text(
                        error.resolve(),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("garmin-consent-error"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onContinue,
                enabled = agreed && !consent.starting,
                modifier = Modifier.testTag("garmin-consent-continue"),
            ) {
                if (consent.starting) {
                    CircularProgressIndicator(Modifier.size(18.dp).testTag("garmin-consent-progress"), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.garmin_dialog_continue))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !consent.starting) { Text(stringResource(R.string.cancel)) }
        },
        modifier = Modifier.testTag("garmin-consent-dialog"),
    )
}
