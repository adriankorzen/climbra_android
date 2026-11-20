package app.climbra.android.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.climbra.android.R
import app.climbra.android.data.net.OfflineState
import app.climbra.android.ui.format.rememberFmt

/** The app is offline and shows saved data from [state]'s time (spec 018). */
@Composable
internal fun OfflineBanner(state: OfflineState, onRetry: () -> Unit) {
    val fmt = rememberFmt()
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().testTag("offline-banner")) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_cloud_off), contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                stringResource(R.string.offline_banner, fmt.instant(state.dataFrom.toString())),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry, modifier = Modifier.testTag("offline-retry")) { Text(stringResource(R.string.retry)) }
        }
    }
}
