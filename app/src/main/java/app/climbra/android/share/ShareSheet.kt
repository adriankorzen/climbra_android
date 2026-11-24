package app.climbra.android.share

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.view.doOnLayout
import androidx.core.view.drawToBitmap
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.compose.LocalSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.climbra.android.R
import app.climbra.android.data.api.ShareKind
import app.climbra.android.ui.common.LocalOffline
import app.climbra.android.ui.common.OfflineNote
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.theme.ClimbraTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the sheet asks of its screen; one holder keeps the signatures readable. */
data class ShareActions(
    val onHidePower: (Boolean) -> Unit = {},
    val onHideHeartRate: (Boolean) -> Unit = {},
    val onShareLink: () -> Unit = {},
    /** The card was drawn into [Bitmap]; `null` when drawing failed. */
    val onImage: (Bitmap?) -> Unit = {},
    val onDismiss: () -> Unit = {},
)

/**
 * The share sheet of a ride or a week (spec 028): what to hide, then the image drawn from [card] or a public link.
 * The switches apply to both.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(
    state: ShareUiState,
    fmt: Fmt,
    actions: ShareActions,
    card: @Composable (hidePower: Boolean, hideHeartRate: Boolean) -> Unit,
) {
    val target = state.target ?: return
    val offline = LocalOffline.current
    var capturing by remember { mutableStateOf(false) }
    var drawingImage by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = actions.onDismiss, modifier = Modifier.testTag("share-sheet")) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(if (target.kind == ShareKind.RIDE) R.string.share_ride_title else R.string.share_week_title),
                style = MaterialTheme.typography.titleLarge,
            )
            if (target.kind == ShareKind.RIDE && target.hasPower) {
                SwitchRow(stringResource(R.string.share_hide_power), state.hidePower, actions.onHidePower, "share-hide-power")
            }
            if (target.kind == ShareKind.RIDE && target.hasHeartRate) {
                SwitchRow(stringResource(R.string.share_hide_heart_rate), state.hideHeartRate, actions.onHideHeartRate, "share-hide-heart-rate")
            }
            if (state.creating || drawingImage) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("share-progress"))
            Button(
                onClick = {
                    drawingImage = true
                    capturing = true
                },
                enabled = !drawingImage,
                modifier = Modifier.fillMaxWidth().testTag("share-image"),
            ) {
                Icon(painterResource(R.drawable.ic_image), contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.share_image), modifier = Modifier.padding(start = 8.dp))
            }
            OutlinedButton(
                onClick = actions.onShareLink,
                enabled = !state.creating && !offline,
                modifier = Modifier.fillMaxWidth().testTag("share-link"),
            ) {
                Icon(painterResource(R.drawable.ic_link), contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.share_link), modifier = Modifier.padding(start = 8.dp))
            }
            if (offline) OfflineNote()
            state.created?.let {
                Text(
                    stringResource(R.string.share_valid_until, fmt.instant(it.expiresAt, withTime = false)),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("share-expiry"),
                )
            }
            state.error?.let {
                Text(it.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("share-error"))
            }
            Text(stringResource(R.string.share_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (capturing) {
            CardCapture(
                onCaptured = { bitmap ->
                    capturing = false
                    drawingImage = false
                    actions.onImage(bitmap)
                },
            ) { card(state.hidePower, state.hideHeartRate) }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}

/**
 * Draws [content] into a 1080 × 1350 px bitmap in the light theme (spec 028): a view of its own, added off screen to
 * the window, laid out at that size and drawn explicitly once laid out. [onCaptured] gets `null` when that fails.
 */
@Composable
fun CardCapture(onCaptured: (Bitmap?) -> Unit, content: @Composable () -> Unit) {
    val host = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val savedStateOwner = LocalSavedStateRegistryOwner.current
    val latestContent by rememberUpdatedState(content)
    val latestCaptured by rememberUpdatedState(onCaptured)
    DisposableEffect(host) {
        // The activity's window, laid out like any screen; a sheet's own window only without an activity
        val root = (host.context.activity()?.window?.decorView ?: host.rootView) as? ViewGroup
        if (root == null) {
            latestCaptured(null)
            return@DisposableEffect onDispose { }
        }
        val view = ComposeView(host.context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedStateOwner)
            // Drawn by the window far off screen: never seen, drawn into the bitmap below
            translationX = OFF_SCREEN
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(SHARE_DENSITY, fontScale = 1f)) {
                    ClimbraTheme(darkTheme = false) {
                        Box(Modifier.fillMaxSize()) { latestContent() }
                    }
                }
            }
        }
        var handedOver = false
        root.addView(view, ViewGroup.LayoutParams(SHARE_IMAGE_WIDTH_PX, SHARE_IMAGE_HEIGHT_PX))
        view.doOnLayout {
            // After the composition's first frame has settled into this layout
            view.post {
                if (handedOver) return@post
                handedOver = true
                latestCaptured(runCatching { view.drawToBitmap() }.getOrNull())
            }
        }
        onDispose {
            handedOver = true
            root.removeView(view)
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

const val SHARE_IMAGE_WIDTH_PX = 1080
const val SHARE_IMAGE_HEIGHT_PX = 1350
private const val OFF_SCREEN = 100_000f

/** Writes the image and opens the system's share sheet for it; `false` when it could not be written. */
suspend fun shareImage(context: Context, bitmap: Bitmap, name: String): Boolean {
    val file = withContext(Dispatchers.IO) { runCatching { ShareFiles.savePng(context, bitmap, name) }.getOrNull() } ?: return false
    val intent = ShareFiles.imageIntent(ShareFiles.uri(context, file), context.getString(R.string.share_image))
    return runCatching { context.startActivity(intent) }.isSuccess
}

/** Opens the system's share sheet for a created link. */
fun shareLink(context: Context, title: String, url: String) {
    runCatching { context.startActivity(ShareFiles.linkIntent(title, url, context.getString(R.string.share_link))) }
}

/** The image's file name: the ride or the week, so a new image of it replaces the earlier one. */
fun imageName(target: ShareTarget): String =
    if (target.kind == ShareKind.RIDE) "ride-${target.activityId}" else "week-${target.weekStart}"

/** Ride detail and calendar: hands the image and the link of the sheet to the system's share sheet. */
@Composable
fun ShareEffects(state: ShareUiState, onLinkHandedOver: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(state.pendingLink) {
        val link = state.pendingLink ?: return@LaunchedEffect
        shareLink(context, state.target?.title.orEmpty(), link.url)
        onLinkHandedOver()
    }
}

/** The image action of [ShareActions]: writes and shares the bitmap, [onFailed] when that is not possible. */
@Composable
fun rememberImageSharer(state: ShareUiState, onFailed: () -> Unit): (Bitmap?) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return { bitmap ->
        val target = state.target
        if (bitmap == null || target == null) {
            onFailed()
        } else {
            scope.launch { if (!shareImage(context, bitmap, imageName(target))) onFailed() }
        }
    }
}
