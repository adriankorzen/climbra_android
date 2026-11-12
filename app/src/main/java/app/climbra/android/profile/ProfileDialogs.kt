package app.climbra.android.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.climbra.android.R
import app.climbra.android.data.api.AccountResponse
import app.climbra.android.ui.common.ErrorBanner
import app.climbra.android.ui.common.UiText
import app.climbra.android.ui.format.DateStyle
import app.climbra.android.ui.format.Fmt
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Adding or editing a threshold entry: the web's threshold form as a bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThresholdSheet(form: ThresholdForm, error: UiText?, fmt: Fmt, today: LocalDate, actions: ProfileActions) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var picking by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = actions::closeThresholdForm, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
                .navigationBarsPadding().testTag("threshold-sheet"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(if (form.editingId == null) R.string.profile_add_threshold else R.string.profile_edit_threshold),
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                value = fmt.date(form.effectiveFrom, DateStyle.DAY_MONTH_YEAR),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.profile_valid_from)) },
                trailingIcon = {
                    IconButton(onClick = { picking = true }, modifier = Modifier.testTag("threshold-pick-date")) {
                        Icon(painterResource(R.drawable.ic_calendar_month), contentDescription = stringResource(R.string.range_pick))
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("threshold-date"),
            )
            NumberField(form.ftp, { actions.thresholdChange(form.copy(ftp = it)) }, "FTP (W)", "threshold-ftp")
            NumberField(form.lthr, { actions.thresholdChange(form.copy(lthr = it)) }, "LTHR (bpm)", "threshold-lthr")
            Faint(stringResource(R.string.profile_lthr_optional))
            NumberField(
                form.maxHr,
                { actions.thresholdChange(form.copy(maxHr = it)) },
                stringResource(R.string.profile_max_hr) + " (bpm)",
                "threshold-max-hr",
            )
            Faint(stringResource(R.string.profile_threshold_hint))
            if (form.invalid) {
                Text(
                    stringResource(R.string.profile_threshold_invalid),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("threshold-invalid"),
                )
            }
            error?.let { ErrorBanner(it.resolve(), Modifier.testTag("threshold-error")) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                Button(onClick = actions::saveThreshold, enabled = !form.saving, modifier = Modifier.testTag("threshold-save")) {
                    Text(stringResource(if (form.editingId == null) R.string.add else R.string.save))
                }
                TextButton(onClick = actions::closeThresholdForm) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
    if (picking) {
        ThresholdDatePicker(form.effectiveFrom, today, onPick = {
            picking = false
            actions.thresholdChange(form.copy(effectiveFrom = it.toString()))
        }, onDismiss = { picking = false })
    }
}

/** The day an entry is valid from: at most [today] (the backend refuses future entries). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThresholdDatePicker(value: String, today: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val initial = runCatching { LocalDate.parse(value) }.getOrDefault(today)
    val lastMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= lastMillis
            override fun isSelectableYear(year: Int) = year <= today.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss()
            }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    ) {
        DatePicker(state = state)
    }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String, tag: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onChange(text.filter { it.isDigit() }.take(4)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

/** Deleting an entry, disconnecting intervals.icu or Garmin Connect: asked first, as the web's `confirm`. */
@Composable
internal fun ConfirmationDialog(confirmation: Confirmation, fmt: Fmt, actions: ProfileActions) {
    val text = when (confirmation) {
        is Confirmation.DeleteThreshold ->
            stringResource(R.string.profile_confirm_delete, fmt.date(confirmation.entry.effectiveFrom, DateStyle.DAY_MONTH_YEAR))
        Confirmation.DisconnectIntervals -> stringResource(R.string.intervals_confirm_disconnect)
        Confirmation.DisconnectGarmin -> stringResource(R.string.garmin_confirm_disconnect)
    }
    val action = stringResource(
        when (confirmation) {
            is Confirmation.DeleteThreshold -> R.string.delete
            Confirmation.DisconnectIntervals -> R.string.intervals_disconnect
            Confirmation.DisconnectGarmin -> R.string.garmin_disconnect
        },
    )
    AlertDialog(
        onDismissRequest = actions::dismissConfirmation,
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = actions::confirm, modifier = Modifier.testTag("confirm-ok")) {
                Text(action, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = actions::dismissConfirmation, modifier = Modifier.testTag("confirm-cancel")) {
                Text(stringResource(R.string.cancel))
            }
        },
        modifier = Modifier.testTag("confirm-dialog"),
    )
}

/** Confirms deleting the account with the password, or the email typed again without one (cyclingapp spec 014). */
@Composable
internal fun DeleteAccountDialog(form: DeleteAccountForm, account: AccountResponse, actions: ProfileActions) {
    AlertDialog(
        onDismissRequest = actions::closeDeleteAccount,
        title = { Text(stringResource(R.string.delete_account_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.delete_account_warning))
                if (account.hasPassword) {
                    PasswordField(
                        value = form.value,
                        onChange = actions::deleteAccountChange,
                        label = stringResource(R.string.delete_account_password),
                        tag = "delete-account-value",
                    )
                } else {
                    OutlinedTextField(
                        value = form.value,
                        onValueChange = actions::deleteAccountChange,
                        label = { Text(stringResource(R.string.delete_account_email, account.email)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth().testTag("delete-account-value"),
                    )
                }
                form.error?.let { ErrorBanner(it.resolve(), Modifier.testTag("delete-account-error")) }
            }
        },
        confirmButton = {
            Button(
                onClick = actions::deleteAccount,
                enabled = !form.busy && form.value.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("delete-account-submit"),
            ) { Text(stringResource(R.string.delete_account_submit)) }
        },
        dismissButton = {
            TextButton(onClick = actions::closeDeleteAccount, enabled = !form.busy) { Text(stringResource(R.string.cancel)) }
        },
        modifier = Modifier.testTag("delete-account-dialog"),
    )
}
