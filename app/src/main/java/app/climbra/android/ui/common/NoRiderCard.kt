package app.climbra.android.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.climbra.android.R

/**
 * The account has no rider profile yet (spec 022): the same card on every tab, whose button opens the profile, where
 * the rider form creates it.
 */
@Composable
fun NoRiderCard(onCreateProfile: () -> Unit, tag: String, modifier: Modifier = Modifier) {
    OutlinedCard(modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dashboard_no_rider_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.dashboard_no_rider_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onCreateProfile, modifier = Modifier.testTag("$tag-button")) {
                Text(stringResource(R.string.profile_create))
            }
        }
    }
}
