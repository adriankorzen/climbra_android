package app.climbra.android.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.climbra.android.R
import app.climbra.android.activities.ZoneKind
import app.climbra.android.activities.zoneName
import app.climbra.android.data.api.CoachQuota
import app.climbra.android.data.api.GarminStatus
import app.climbra.android.data.api.IntervalsConnection
import app.climbra.android.data.api.IntervalsThresholds
import app.climbra.android.data.api.ThresholdEntry
import app.climbra.android.data.api.TrainingZone
import app.climbra.android.data.api.Zones
import app.climbra.android.ui.common.ErrorBanner
import app.climbra.android.ui.common.LocalOffline
import app.climbra.android.ui.common.OfflineNote
import app.climbra.android.ui.format.DateStyle
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.theme.MonoFamily
import app.climbra.android.ui.theme.ThemeMode
import app.climbra.android.ui.theme.extendedColors

/** A titled card of the profile. */
@Composable
internal fun ProfileCard(
    title: String,
    tag: String,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (action == null) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    action()
                }
            }
            content()
        }
    }
}

/** A section that failed to load: its message and Retry, while the other sections show their data. */
@Composable
internal fun SectionError(text: String, tag: String, onRetry: () -> Unit) {
    ErrorBanner(text, Modifier.testTag("$tag-error"))
    OutlinedButton(onClick = onRetry, modifier = Modifier.testTag("$tag-retry")) { Text(stringResource(R.string.retry)) }
}

@Composable
internal fun Faint(text: String, modifier: Modifier = Modifier) = Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = modifier,
)

@Composable
private fun Field(label: String, value: String, tag: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun Badge(text: String, tag: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.testTag(tag),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

// Rider

@Composable
internal fun RiderCard(state: ProfileUiState, fmt: Fmt, actions: ProfileActions) {
    val form = state.riderForm
    val rider = state.rider
    val offline = LocalOffline.current
    // Without a rider the form is always open: there is nothing to view or cancel (spec 024)
    val editing = rider == null || form.editing
    BackHandler(enabled = rider != null && form.editing && !form.saving) { actions.cancelRider() }
    ProfileCard(
        stringResource(R.string.profile_rider),
        "profile-rider",
        action = if (editing) null else {
            {
                TextButton(onClick = actions::editRider, enabled = !offline, modifier = Modifier.testTag("rider-edit")) {
                    Icon(painterResource(R.drawable.ic_edit), contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.edit), modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
    ) {
        if (rider != null && !form.editing) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Field(stringResource(R.string.profile_name), rider.name, "rider-view-name", Modifier.weight(1f))
                Field(stringResource(R.string.profile_weight), fmt.num(rider.weightKg, weightDigits(rider.weightKg)), "rider-view-weight")
                Field(stringResource(R.string.profile_resting_hr), fmt.num(rider.restingHeartRateBpm), "rider-view-resting-hr")
            }
            return@ProfileCard
        }
        OutlinedTextField(
            value = form.name,
            onValueChange = { actions.riderChange(form.copy(name = it)) },
            label = { Text(stringResource(R.string.profile_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("rider-name"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = form.weight,
                onValueChange = { actions.riderChange(form.copy(weight = it)) },
                label = { Text(stringResource(R.string.profile_weight)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f).testTag("rider-weight"),
            )
            OutlinedTextField(
                value = form.restingHr,
                onValueChange = { actions.riderChange(form.copy(restingHr = it)) },
                label = { Text(stringResource(R.string.profile_resting_hr)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f).testTag("rider-resting-hr"),
            )
        }
        if (form.invalid) {
            Text(
                stringResource(R.string.profile_rider_invalid),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("rider-invalid"),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (rider != null) {
                OutlinedButton(onClick = actions::cancelRider, enabled = !form.saving, modifier = Modifier.testTag("rider-cancel")) {
                    Text(stringResource(R.string.cancel))
                }
            }
            Button(onClick = actions::saveRider, enabled = !form.saving && !offline, modifier = Modifier.testTag("rider-save")) {
                Text(stringResource(if (rider == null) R.string.profile_create else R.string.save))
            }
        }
        OfflineNote()
    }
}

/** The weight as the rider typed it: up to two decimals, none for a whole number. */
private fun weightDigits(weight: Double?): Int =
    weight?.toBigDecimal()?.stripTrailingZeros()?.scale()?.coerceIn(0, 2) ?: 0

// Preferences

@Composable
internal fun PreferencesCard(state: ProfileUiState, actions: ProfileActions) {
    ProfileCard(stringResource(R.string.profile_preferences), "profile-preferences") {
        Text(stringResource(R.string.profile_language), style = MaterialTheme.typography.labelMedium)
        val languages = listOf("en" to R.string.language_en, "pl" to R.string.language_pl)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            languages.forEachIndexed { index, (language, label) ->
                SegmentedButton(
                    selected = state.account?.language == language,
                    onClick = { actions.setLanguage(language) },
                    enabled = !state.languageBusy,
                    shape = SegmentedButtonDefaults.itemShape(index, languages.size),
                    modifier = Modifier.testTag("language-$language"),
                ) { Text(stringResource(label)) }
            }
        }
        Text(stringResource(R.string.profile_theme), style = MaterialTheme.typography.labelMedium)
        val themes = listOf(
            ThemeMode.SYSTEM to R.string.theme_system,
            ThemeMode.LIGHT to R.string.theme_light,
            ThemeMode.DARK to R.string.theme_dark,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            themes.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = state.theme == mode,
                    onClick = { actions.setTheme(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, themes.size),
                    // No check mark: "Like the system" needs the room
                    icon = {},
                    modifier = Modifier.testTag("theme-${mode.name.lowercase()}"),
                ) { Text(stringResource(label), maxLines = 1, softWrap = false) }
            }
        }
    }
}

// Threshold history

@Composable
internal fun ThresholdsCard(state: ProfileUiState, fmt: Fmt, actions: ProfileActions) {
    val section = state.thresholds
    ProfileCard(stringResource(R.string.profile_history), "profile-thresholds") {
        Faint(stringResource(R.string.profile_threshold_hint))
        when {
            section.failed -> SectionError(stringResource(R.string.profile_thresholds_failed), "thresholds") {
                actions.retry(ProfileSection.THRESHOLDS)
            }
            section.value == null -> CircularProgressIndicator(Modifier.size(24.dp))
            section.value.isEmpty() -> Faint(stringResource(R.string.profile_no_thresholds), Modifier.testTag("thresholds-empty"))
            else -> section.value.forEachIndexed { index, entry ->
                if (index > 0) HorizontalDivider()
                ThresholdRow(entry, entry.id == state.rider?.currentThresholds?.id, fmt, actions)
            }
        }
        OutlinedButton(onClick = { actions.openThresholdForm(null) }, modifier = Modifier.testTag("threshold-add")) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.add), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun ThresholdRow(entry: ThresholdEntry, current: Boolean, fmt: Fmt, actions: ProfileActions) {
    var menu by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { actions.openThresholdForm(entry) }.testTag("threshold-${entry.id}"),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(fmt.date(entry.effectiveFrom, DateStyle.DAY_MONTH_YEAR), style = MaterialTheme.typography.bodyMedium)
                if (current) Badge(stringResource(R.string.profile_current), "threshold-current")
            }
            val maxHr = stringResource(R.string.profile_max_hr)
            Text(
                listOf(
                    "FTP " + (entry.ftpWatts?.let { "${fmt.num(it)} W" } ?: Fmt.DASH),
                    entry.ftpWattsPerKg?.let { stringResource(R.string.unit_watts_per_kg, fmt.num(it, 2)) } ?: "${Fmt.DASH} W/kg",
                    "LTHR " + (entry.lthrBpm?.let { "${fmt.num(it)} bpm" } ?: Fmt.DASH),
                    "$maxHr " + (entry.maxHeartRateBpm?.let { "${fmt.num(it)} bpm" } ?: Fmt.DASH),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = MonoFamily,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("threshold-${entry.id}-values"),
            )
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("threshold-${entry.id}-menu")) {
                Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.more_actions))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.edit)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_edit), contentDescription = null) },
                    onClick = {
                        menu = false
                        actions.openThresholdForm(entry)
                    },
                    modifier = Modifier.testTag("threshold-edit"),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
                    onClick = {
                        menu = false
                        actions.askDeleteThreshold(entry)
                    },
                    modifier = Modifier.testTag("threshold-delete"),
                )
            }
        }
    }
}

// Zones

@Composable
internal fun PowerZonesCard(section: Section<Zones>, fmt: Fmt, actions: ProfileActions) {
    val zones = section.value
    val title = stringResource(R.string.profile_power_zones) + (zones?.ftpWatts?.let { " · FTP ${fmt.num(it)} W" }.orEmpty())
    ProfileCard(title, "profile-power-zones") {
        ZoneList(section, actions, empty = stringResource(R.string.profile_add_ftp), tag = "power-zones") {
            zones?.powerZones.orEmpty().forEach { zone ->
                ZoneRow(zone, ZoneKind.POWER, MaterialTheme.extendedColors.powerZones, "W", "", fmt)
            }
        }
    }
}

@Composable
internal fun HeartRateZonesCard(section: Section<Zones>, fmt: Fmt, actions: ProfileActions) {
    val zones = section.value
    val basis = when (zones?.heartRateZoneBasis) {
        "MAX_HR" -> " · ${stringResource(R.string.profile_max_hr)} ${fmt.num(zones.maxHeartRateBpm)} bpm"
        "LTHR" -> " · LTHR ${fmt.num(zones.lthrBpm)} bpm"
        else -> ""
    }
    val kind = if (zones?.heartRateZoneBasis == "MAX_HR") ZoneKind.HEART_RATE_MAX else ZoneKind.HEART_RATE
    val percentOf = if (zones?.heartRateZoneBasis == "MAX_HR") "HRmax" else "LTHR"
    ProfileCard(stringResource(R.string.profile_hr_zones) + basis, "profile-hr-zones") {
        ZoneList(section, actions, empty = stringResource(R.string.profile_add_lthr), tag = "hr-zones") {
            zones?.heartRateZones.orEmpty().forEach { zone ->
                ZoneRow(zone, kind, MaterialTheme.extendedColors.heartRateZones, "bpm", " $percentOf", fmt)
            }
        }
    }
}

@Composable
private fun ZoneList(
    section: Section<Zones>,
    actions: ProfileActions,
    empty: String,
    tag: String,
    rows: @Composable () -> Unit,
) {
    val zones = section.value
    val list = if (tag == "power-zones") zones?.powerZones else zones?.heartRateZones
    when {
        section.failed -> SectionError(stringResource(R.string.profile_zones_failed), tag) { actions.retry(ProfileSection.ZONES) }
        zones == null -> CircularProgressIndicator(Modifier.size(24.dp))
        list.isNullOrEmpty() -> Faint(empty, Modifier.testTag("$tag-empty"))
        else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag(tag)) { rows() }
    }
}

/** `● Z4 Threshold   91–105%   228–263 W`; the last zone is open: `> 150%`, `≥ 376 W`. */
@Composable
private fun ZoneRow(zone: TrainingZone, kind: ZoneKind, colors: List<Color>, unit: String, percentOf: String, fmt: Fmt) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag("zone-${zone.number}")) {
        Box(Modifier.size(10.dp).background(colors.getOrElse(zone.number - 1) { colors.last() }, CircleShape))
        Text(
            "Z${zone.number}",
            style = MaterialTheme.typography.labelMedium,
            fontFamily = MonoFamily,
            modifier = Modifier.padding(start = 8.dp).width(28.dp),
        )
        Text(zoneName(kind, zone.number, zone.name), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        val percent = zone.maxPercent?.let { "${zone.minPercent}–$it%" } ?: "> ${zone.minPercent - 1}%"
        Faint(percent + percentOf, Modifier.padding(horizontal = 8.dp))
        Text(
            zone.max?.let { "${fmt.num(zone.min)}–${fmt.num(it)} $unit" } ?: "≥ ${fmt.num(zone.min)} $unit",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = MonoFamily,
        )
    }
}

// intervals.icu

@Composable
internal fun IntervalsCard(state: ProfileUiState, fmt: Fmt, actions: ProfileActions) {
    val section = state.intervals
    ProfileCard("intervals.icu", "profile-intervals") {
        val connection = section.value
        when {
            section.failed -> SectionError(stringResource(R.string.profile_intervals_failed), "intervals") {
                actions.retry(ProfileSection.INTERVALS)
            }
            connection == null -> CircularProgressIndicator(Modifier.size(24.dp))
            !connection.connected -> IntervalsConnect(state, actions)
            else -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(
                        stringResource(R.string.intervals_athlete),
                        listOfNotNull(connection.athleteName ?: Fmt.DASH, connection.athleteId).joinToString(" "),
                        "intervals-athlete",
                    )
                    Field(stringResource(R.string.intervals_api_key), "••••${connection.keyHint.orEmpty()}", "intervals-key")
                    Field(
                        stringResource(R.string.intervals_last_sync),
                        connection.lastSyncAt?.let { fmt.instant(it) } ?: stringResource(R.string.intervals_never),
                        "intervals-last-sync",
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = actions::syncIntervals,
                        enabled = !state.syncing && !state.busy,
                        modifier = Modifier.testTag("intervals-sync"),
                    ) {
                        if (state.syncing) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(painterResource(R.drawable.ic_sync), contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Text(
                            stringResource(if (state.syncing) R.string.intervals_syncing else R.string.intervals_sync_now),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    OutlinedButton(
                        onClick = actions::askDisconnectIntervals,
                        enabled = !state.busy,
                        modifier = Modifier.testTag("intervals-disconnect"),
                    ) {
                        Icon(painterResource(R.drawable.ic_link_off), contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.intervals_disconnect), modifier = Modifier.padding(start = 8.dp))
                    }
                }
                AutoSyncRow(connection, state.autoSyncBusy, actions)
                Faint(stringResource(R.string.intervals_sources_note))
                HorizontalDivider()
                IntervalsProposal(state, fmt, actions)
            }
        }
    }
}

/** The backend's automatic sync every 30 minutes (spec 025), as the web's box; the stored state, sent on a toggle. */
@Composable
private fun AutoSyncRow(connection: IntervalsConnection, busy: Boolean, actions: ProfileActions) {
    val offline = LocalOffline.current
    val keyRejected = connection.autoSyncStopped == IntervalsConnection.AUTO_SYNC_KEY_REJECTED
    ListItem(
        headlineContent = { Text(stringResource(R.string.intervals_auto_sync)) },
        supportingContent = if (keyRejected) {
            {
                Text(
                    stringResource(R.string.intervals_auto_sync_key_rejected),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("intervals-auto-sync-stopped"),
                )
            }
        } else {
            null
        },
        trailingContent = {
            Switch(
                checked = connection.autoSync,
                onCheckedChange = actions::setAutoSync,
                enabled = !busy && !offline,
                modifier = Modifier.testTag("intervals-auto-sync"),
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun IntervalsConnect(state: ProfileUiState, actions: ProfileActions) {
    val form = state.intervalsForm
    Text(stringResource(R.string.intervals_intro), style = MaterialTheme.typography.bodyMedium)
    Faint(stringResource(R.string.intervals_how_to))
    OutlinedTextField(
        value = form.athleteId,
        onValueChange = { actions.intervalsChange(form.copy(athleteId = it.take(20))) },
        label = { Text(stringResource(R.string.intervals_athlete_id)) },
        placeholder = { Text("i12345") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("intervals-athlete-id"),
    )
    OutlinedTextField(
        value = form.apiKey,
        onValueChange = { actions.intervalsChange(form.copy(apiKey = it.take(200))) },
        label = { Text(stringResource(R.string.intervals_api_key)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().testTag("intervals-api-key"),
    )
    state.intervalsError?.let { ErrorBanner(it.resolve(), Modifier.testTag("intervals-connect-error")) }
    Button(
        onClick = actions::connectIntervals,
        enabled = !form.busy && form.apiKey.isNotBlank(),
        modifier = Modifier.testTag("intervals-connect"),
    ) {
        Text(stringResource(if (form.busy) R.string.intervals_connecting else R.string.intervals_connect))
    }
}

@Composable
private fun IntervalsProposal(state: ProfileUiState, fmt: Fmt, actions: ProfileActions) {
    Text(stringResource(R.string.intervals_thresholds_title), style = MaterialTheme.typography.titleSmall)
    val section = state.intervalsThresholds
    val proposal = section.value
    when {
        section.failed -> SectionError(stringResource(R.string.intervals_thresholds_failed), "intervals-thresholds") {
            actions.retry(ProfileSection.INTERVALS_THRESHOLDS)
        }
        proposal == null -> CircularProgressIndicator(Modifier.size(24.dp))
        else -> {
            val current = state.rider?.currentThresholds
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("intervals-proposal")) {
                ProposalRow("", "intervals.icu", stringResource(R.string.intervals_current_values), header = true)
                val indoor = proposal.indoorFtpWatts?.let { " (${stringResource(R.string.intervals_indoor)} ${watts(it, fmt)})" }.orEmpty()
                ProposalRow("FTP", watts(proposal.ftpWatts, fmt) + indoor, watts(current?.ftpWatts, fmt))
                ProposalRow("LTHR", bpm(proposal.lthrBpm, fmt), bpm(current?.lthrBpm, fmt))
                ProposalRow(stringResource(R.string.profile_max_hr), bpm(proposal.maxHeartRateBpm, fmt), bpm(current?.maxHeartRateBpm, fmt))
            }
            if (proposal.differsFromCurrent) {
                OutlinedButton(onClick = { actions.proposeThresholds(proposal) }, modifier = Modifier.testTag("intervals-propose")) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.intervals_add_to_history), modifier = Modifier.padding(start = 8.dp))
                }
            } else {
                Faint(stringResource(R.string.intervals_same_thresholds), Modifier.testTag("intervals-same"))
            }
        }
    }
}

@Composable
private fun ProposalRow(label: String, proposed: String, current: String, header: Boolean = false) {
    val style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = style, modifier = Modifier.weight(0.8f))
        Text(proposed, style = style, fontFamily = MonoFamily, modifier = Modifier.weight(1.4f))
        Text(current, style = style, fontFamily = MonoFamily, modifier = Modifier.weight(1f))
    }
}

private fun watts(value: Int?, fmt: Fmt) = value?.let { "${fmt.num(it)} W" } ?: Fmt.DASH

private fun bpm(value: Int?, fmt: Fmt) = value?.let { "${fmt.num(it)} bpm" } ?: Fmt.DASH

// Garmin Connect

@Composable
internal fun GarminCard(state: ProfileUiState, fmt: Fmt, actions: ProfileActions) {
    val section = state.garmin
    if (section.failed) {
        ProfileCard("Garmin Connect", "profile-garmin") {
            SectionError(stringResource(R.string.profile_garmin_failed), "garmin") { actions.retry(ProfileSection.GARMIN) }
        }
        return
    }
    // Not offered by the server: nothing about it, as the web
    val connection = section.value?.takeIf { it.available } ?: return
    ProfileCard("Garmin Connect", "profile-garmin") {
        Faint(stringResource(R.string.garmin_intro))
        if (connection.status == GarminStatus.CONNECTED) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Field(stringResource(R.string.garmin_connected_at), fmt.instant(connection.connectedAt), "garmin-connected-at")
                Field(stringResource(R.string.garmin_on_garmin), fmt.num(connection.sent), "garmin-sent")
            }
            if (connection.stale > 0) Faint(stringResource(R.string.garmin_outdated), Modifier.testTag("garmin-stale"))
            OutlinedButton(
                onClick = actions::askDisconnectGarmin,
                enabled = !state.busy,
                modifier = Modifier.testTag("garmin-disconnect"),
            ) {
                Icon(painterResource(R.drawable.ic_link_off), contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.garmin_disconnect), modifier = Modifier.padding(start = 8.dp))
            }
        } else {
            when (connection.status) {
                GarminStatus.NO_PERMISSION -> ErrorBanner(stringResource(R.string.garmin_status_no_permission), Modifier.testTag("garmin-problem"))
                GarminStatus.NEEDS_RECONNECT -> ErrorBanner(stringResource(R.string.garmin_status_needs_reconnect), Modifier.testTag("garmin-problem"))
                else -> Unit
            }
            // Spec 016: the consent, then Garmin's page in a Custom Tab
            Button(onClick = actions::connectGarmin, modifier = Modifier.testTag("garmin-connect")) {
                Text(stringResource(R.string.garmin_connect))
            }
        }
    }
}

// Account

@Composable
internal fun GarminSyncCard(state: ProfileUiState, actions: ProfileActions) {
    if (state.account?.role != "ADMIN" || state.rider == null) return
    ProfileCard(stringResource(R.string.garmin_sync_title), "profile-garmin-sync") {
        Faint(stringResource(R.string.garmin_sync_intro))
        Button(onClick = actions::syncGarmin, enabled = !state.garminSyncing, modifier = Modifier.testTag("garmin-sync")) {
            Icon(painterResource(R.drawable.ic_sync), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                stringResource(if (state.garminSyncing) R.string.garmin_sync_syncing else R.string.garmin_sync_button),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** The account (spec 004), with the feedback form (spec 010). */
@Composable
internal fun AccountCard(state: ProfileUiState, fmt: Fmt, actions: ProfileActions, notificationsOn: Boolean? = null) {
    val account = state.account ?: return
    ProfileCard(stringResource(R.string.account_title), "profile-account") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Faint(stringResource(R.string.login_email))
            Text(account.email, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("account-email"))
            if (account.googleLinked) Badge(stringResource(R.string.account_google_linked), "account-google")
        }

        Text(stringResource(R.string.quota_title), style = MaterialTheme.typography.titleSmall)
        val quota = state.quota
        when {
            quota.failed -> SectionError(stringResource(R.string.profile_quota_failed), "quota") { actions.retry(ProfileSection.QUOTA) }
            quota.value == null -> CircularProgressIndicator(Modifier.size(24.dp))
            else -> Credits(quota.value, fmt)
        }

        Text(stringResource(R.string.account_change_password), style = MaterialTheme.typography.titleSmall)
        if (account.hasPassword) {
            PasswordChange(state, account.email, actions)
        } else {
            Faint(stringResource(R.string.account_no_password), Modifier.testTag("account-no-password"))
            TextButton(onClick = actions::forgotPassword, modifier = Modifier.testTag("account-forgot")) {
                Text(stringResource(R.string.login_forgot))
            }
        }

        Text(stringResource(R.string.account_documents), style = MaterialTheme.typography.titleSmall)
        Row {
            TextButton(onClick = actions::openTerms, modifier = Modifier.testTag("account-terms")) {
                Text(stringResource(R.string.terms_open_terms))
            }
            TextButton(onClick = actions::openPrivacy, modifier = Modifier.testTag("account-privacy")) {
                Text(stringResource(R.string.terms_open_privacy))
            }
        }

        // Spec 020: the system's settings decide; the row only shows and opens them
        if (notificationsOn != null) {
            Text(stringResource(R.string.push_profile_title), style = MaterialTheme.typography.titleSmall)
            Faint(
                stringResource(if (notificationsOn) R.string.push_profile_on else R.string.push_profile_off),
                Modifier.testTag("account-notifications-state"),
            )
            OutlinedButton(onClick = actions::openNotificationSettings, modifier = Modifier.testTag("account-notifications")) {
                Icon(painterResource(R.drawable.ic_notifications), contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.push_profile_open), modifier = Modifier.padding(start = 8.dp))
            }
        }

        // Spec 010
        Text(stringResource(R.string.feedback_title), style = MaterialTheme.typography.titleSmall)
        OutlinedButton(onClick = actions::openFeedback, modifier = Modifier.testTag("account-feedback")) {
            Icon(painterResource(R.drawable.ic_feedback), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.feedback_open), modifier = Modifier.padding(start = 8.dp))
        }

        Text(stringResource(R.string.delete_account_title), style = MaterialTheme.typography.titleSmall)
        Faint(stringResource(R.string.delete_account_intro))
        if (account.role == "ADMIN") {
            Faint(stringResource(R.string.delete_account_admin_note), Modifier.testTag("account-admin-note"))
        } else {
            OutlinedButton(onClick = actions::openDeleteAccount, modifier = Modifier.testTag("account-delete")) {
                Text(stringResource(R.string.delete_account_open), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** The web's `coach-quota` in its details mode. */
@Composable
private fun Credits(quota: CoachQuota, fmt: Fmt) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("quota")) {
        val plan = stringResource(if (quota.plan == "SUBSCRIPTION") R.string.quota_plan_subscription else R.string.quota_plan_free)
        val subscription = quota.subscription?.let { subscription ->
            val date = fmt.instant(subscription.currentPeriodEnd, withTime = false)
            " · " + stringResource(
                if (subscription.status == "CANCELED") R.string.quota_subscription_canceled else R.string.quota_subscription_active,
                date,
            )
        }.orEmpty()
        Field(stringResource(R.string.quota_plan_label), plan + subscription, "quota-plan")
        quota.monthly?.let { monthly ->
            Field(
                stringResource(R.string.quota_monthly_label),
                stringResource(
                    R.string.quota_monthly,
                    fmt.num(monthly.remaining),
                    fmt.num(monthly.limit),
                    fmt.instant(monthly.resetsAt, withTime = false),
                ),
                "quota-monthly",
            )
        }
        if (quota.bonus.isNotEmpty()) {
            Field(
                stringResource(R.string.quota_bonus_label),
                quota.bonus.map {
                    stringResource(R.string.quota_bonus_item, fmt.num(it.remaining), fmt.instant(it.expiresAt, withTime = false))
                }.joinToString("\n"),
                "quota-bonus",
            )
        }
        Field(
            stringResource(R.string.quota_balance_label),
            if (quota.unlimited) stringResource(R.string.quota_unlimited_short) else fmt.num(quota.remaining),
            "quota-balance",
        )
    }
}

@Composable
private fun PasswordChange(state: ProfileUiState, email: String, actions: ProfileActions) {
    val form = state.passwordForm
    val newProblem = newPasswordProblem(form.password).takeIf { form.showErrors }
    val repeatProblem = repeatProblem(form.password, form.repeat, email).takeIf { form.showErrors }
    PasswordField(
        value = form.current,
        onChange = { actions.passwordChange(form.copy(current = it)) },
        label = stringResource(R.string.password_current),
        error = if (form.showErrors && form.current.isEmpty()) stringResource(R.string.password_required) else null,
        tag = "password-current",
    )
    PasswordField(
        value = form.password,
        onChange = { actions.passwordChange(form.copy(password = it)) },
        label = stringResource(R.string.password_new),
        error = newProblem?.let { passwordProblemText(it) },
        hint = stringResource(R.string.password_hint),
        tag = "password-new",
    )
    PasswordField(
        value = form.repeat,
        onChange = { actions.passwordChange(form.copy(repeat = it)) },
        label = stringResource(R.string.password_repeat),
        error = repeatProblem?.let { passwordProblemText(it) },
        tag = "password-repeat",
    )
    state.passwordError?.let { ErrorBanner(it.resolve(), Modifier.testTag("password-error")) }
    Button(onClick = actions::changePassword, enabled = !form.saving, modifier = Modifier.testTag("password-submit")) {
        Text(stringResource(R.string.password_submit))
    }
}

@Composable
internal fun passwordProblemText(problem: PasswordProblem) = stringResource(
    when (problem) {
        PasswordProblem.REQUIRED -> R.string.password_required
        PasswordProblem.TOO_SHORT -> R.string.password_too_short
        PasswordProblem.TOO_LONG -> R.string.password_too_long
        PasswordProblem.MISMATCH -> R.string.password_mismatch
        PasswordProblem.EQUALS_EMAIL -> R.string.password_equals_email
    },
)

@Composable
internal fun PasswordField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    tag: String,
    error: String? = null,
    hint: String? = null,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = (error ?: hint)?.let { text -> { Text(text, modifier = Modifier.testTag("$tag-supporting")) } },
        visualTransformation = if (visible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    painterResource(if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                    contentDescription = stringResource(if (visible) R.string.login_hide_password else R.string.login_show_password),
                )
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}
