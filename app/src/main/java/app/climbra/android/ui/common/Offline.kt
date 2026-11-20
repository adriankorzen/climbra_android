package app.climbra.android.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.climbra.android.R

/**
 * The app shows saved answers because the backend cannot be reached (spec 018): actions that send something are
 * disabled. Provided by the shell.
 */
val LocalOffline = compositionLocalOf { false }

/** "Not available offline" where an action is disabled offline; nothing online. */
@Composable
fun OfflineNote(modifier: Modifier = Modifier) {
    if (!LocalOffline.current) return
    Text(
        stringResource(R.string.offline_not_available),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.testTag("offline-note"),
    )
}
