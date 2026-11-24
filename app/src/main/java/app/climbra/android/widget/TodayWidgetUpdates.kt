package app.climbra.android.widget

import app.climbra.android.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * When the widget loads again (spec 027), app-scoped: the app going to the background, a change of the rides, a
 * login or logout, the workers and the refresh button. [version] tells a shown widget to load again; [update] starts
 * the widgets that are not shown at the moment. Nothing happens while no widget is placed.
 */
class TodayWidgetUpdates(
    session: StateFlow<SessionState>,
    foreground: StateFlow<Boolean>,
    ridesChanged: StateFlow<Int>,
    scope: CoroutineScope,
    private val placed: suspend () -> Boolean,
    private val update: suspend () -> Unit,
) {
    private val mutableVersion = MutableStateFlow(0)
    val version: StateFlow<Int> = mutableVersion.asStateFlow()

    init {
        scope.launch {
            merge<Any>(
                foreground.drop(1).filter { !it },
                ridesChanged.drop(1),
                session.map { it.key() }.filterNotNull().distinctUntilChanged().drop(1),
            ).collect { refresh() }
        }
    }

    suspend fun refresh() {
        if (!placed()) return
        mutableVersion.update { it + 1 }
        update()
    }

    private companion object {
        /** What the widget shows depends on; `null` while it is not known yet. */
        fun SessionState.key(): Any? = when (this) {
            is SessionState.LoggedIn -> account.id
            is SessionState.NeedsTerms -> "terms"
            is SessionState.LoggedOut -> "out"
            SessionState.Loading, is SessionState.Unavailable -> null
        }
    }
}
