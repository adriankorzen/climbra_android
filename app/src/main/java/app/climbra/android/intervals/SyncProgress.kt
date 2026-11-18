package app.climbra.android.intervals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.climbra.android.R
import app.climbra.android.ui.format.Fmt

/** A sync is shown: intervals.icu (spec 007) or the admin's Garmin sync (spec 011). */
val SyncState.shown: Boolean get() = running || garminRunning

/**
 * The running intervals.icu sync (spec 007): indeterminate until the backend knows the rides of the job; and the
 * admin's Garmin sync (spec 011), which tells no progress.
 */
@Composable
fun SyncProgress(sync: SyncState, fmt: Fmt, modifier: Modifier = Modifier) {
    if (!sync.shown) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (sync.running) {
            val total = sync.total
            Column(Modifier.fillMaxWidth().testTag("sync-progress"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (total == null) stringResource(R.string.sync_progress)
                    else stringResource(R.string.sync_progress_count, fmt.num(sync.processed), fmt.num(total)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (total == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { sync.processed.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        if (sync.garminRunning) {
            Column(Modifier.fillMaxWidth().testTag("garmin-sync-progress"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.garmin_sync_progress),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}
