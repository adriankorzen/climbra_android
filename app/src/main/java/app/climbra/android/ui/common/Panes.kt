package app.climbra.android.ui.common

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.core.os.bundleOf
import androidx.lifecycle.DEFAULT_ARGS_KEY
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import app.climbra.android.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** An item shown in a tab's detail pane, next to its list (spec 019). */
@Serializable
sealed interface PaneItem {

    /** The arguments its detail screen's ViewModels read, as its route would give them. */
    fun args(): Bundle

    @Serializable
    data class Ride(val id: Long) : PaneItem {
        override fun args() = bundleOf("id" to id)
    }

    @Serializable
    data class Workout(val id: Long, val date: String) : PaneItem {
        override fun args() = bundleOf("id" to id, "date" to date)
    }

    /** [tab] `PANEL` opens the conversation on its panel (spec 008). */
    @Serializable
    data class Conversation(val id: Long, val tab: String? = null) : PaneItem {
        override fun args() = bundleOf("id" to id, "tab" to tab)
    }

    fun encode(): String = Json.encodeToString(serializer(), this)

    companion object {
        fun decode(text: String): PaneItem? = runCatching { Json.decodeFromString(serializer(), text) }.getOrNull()
    }
}

/** The item shown next to the list, for the list to highlight; `null` with one pane or nothing chosen. */
val LocalPaneItem = compositionLocalOf<PaneItem?> { null }

/** The screen is shown in a detail pane next to its list: it has no back arrow. */
val LocalInPane = staticCompositionLocalOf { false }

/** The top bar's back arrow of a detail screen; none in a detail pane, whose list is beside it. */
@Composable
fun DetailBackButton(onBack: () -> Unit, tag: String) {
    if (LocalInPane.current) return
    IconButton(onClick = onBack, modifier = Modifier.testTag(tag)) {
        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
    }
}

/** A row of a list whose item is shown in the detail pane: [color] is `secondaryContainer`. */
fun Modifier.paneSelected(selected: Boolean, color: Color): Modifier =
    if (selected) semantics { this.selected = true }.background(color) else this

/**
 * The ViewModels of the detail pane's screen: a store of their own for [item], dropped when another item is shown or
 * the list's screen goes, with [PaneItem.args] as their `SavedStateHandle`'s arguments, as a route would give them.
 */
@Composable
fun PaneViewModels(item: PaneItem, content: @Composable () -> Unit) {
    val parent = checkNotNull(LocalViewModelStoreOwner.current) { "A pane has a ViewModelStoreOwner" }
    val stores: PaneStores = viewModel(parent)
    val key = item.encode()
    val owner = remember(stores, key) { PaneOwner(stores.store(key), parent, item.args()) }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, LocalInPane provides true) { content() }
}

/** Nothing is shown in the pane: its ViewModels go. */
@Composable
fun ClearPaneViewModels() {
    val parent = LocalViewModelStoreOwner.current ?: return
    val stores: PaneStores = viewModel(parent)
    DisposableEffect(stores) {
        stores.clear()
        onDispose { }
    }
}

/** Holds the store of the item shown in the pane; one at a time. */
class PaneStores : ViewModel() {
    private var key: String? = null
    private var store: ViewModelStore? = null

    fun store(key: String): ViewModelStore {
        val current = store
        if (current != null && key == this.key) return current
        current?.clear()
        return ViewModelStore().also {
            this.key = key
            store = it
        }
    }

    fun clear() {
        store?.clear()
        store = null
        key = null
    }

    override fun onCleared() = clear()
}

private class PaneOwner(
    override val viewModelStore: ViewModelStore,
    private val parent: ViewModelStoreOwner,
    private val args: Bundle,
) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {

    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = (parent as HasDefaultViewModelProviderFactory).defaultViewModelProviderFactory

    override val defaultViewModelCreationExtras: CreationExtras
        get() = MutableCreationExtras((parent as? HasDefaultViewModelProviderFactory)?.defaultViewModelCreationExtras ?: CreationExtras.Empty).apply {
            set(DEFAULT_ARGS_KEY, args)
            set(VIEW_MODEL_STORE_OWNER_KEY, this@PaneOwner)
        }
}
