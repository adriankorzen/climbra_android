package app.climbra.android.widget

import app.climbra.android.data.api.AccountResponse
import app.climbra.android.data.net.Network
import app.climbra.android.session.SessionState
import app.climbra.android.testing.TestBackend
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 027: when the widget loads again. */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayWidgetUpdatesTest {

    private val session = MutableStateFlow<SessionState>(SessionState.Loading)
    private val foreground = MutableStateFlow(false)
    private val rides = MutableStateFlow(0)
    private var placed = true
    private var updates = 0
    private val account = Network.json.decodeFromString(AccountResponse.serializer(), TestBackend.account())

    private fun TestScope.widgetUpdates() = TodayWidgetUpdates(
        session = session,
        foreground = foreground,
        ridesChanged = rides,
        scope = backgroundScope,
        placed = { placed },
        update = { updates++ },
    )

    @Test
    fun `the app going to the background refreshes, coming to the foreground does not`() = runTest(UnconfinedTestDispatcher()) {
        val widget = widgetUpdates()

        foreground.value = true
        assertEquals(0, updates)
        foreground.value = false

        assertEquals(1, updates)
        assertEquals(1, widget.version.value)
    }

    @Test
    fun `a change of the rides refreshes`() = runTest(UnconfinedTestDispatcher()) {
        widgetUpdates()

        rides.value = 1

        assertEquals(1, updates)
    }

    @Test
    fun `the session at start does not refresh, a login, a logout and the terms do`() = runTest(UnconfinedTestDispatcher()) {
        widgetUpdates()

        session.value = SessionState.LoggedIn(account)
        assertEquals(0, updates)
        session.value = SessionState.LoggedOut()
        assertEquals(1, updates)
        session.value = SessionState.Loading
        session.value = SessionState.NeedsTerms(account)
        assertEquals(2, updates)
        session.value = SessionState.LoggedIn(account)
        assertEquals(3, updates)
    }

    @Test
    fun `the refresh button and the workers refresh`() = runTest(UnconfinedTestDispatcher()) {
        val widget = widgetUpdates()

        widget.refresh()
        widget.refresh()

        assertEquals(2, updates)
        assertEquals(2, widget.version.value)
    }

    @Test
    fun `nothing happens while no widget is placed`() = runTest(UnconfinedTestDispatcher()) {
        placed = false
        val widget = widgetUpdates()

        rides.value = 1
        widget.refresh()

        assertEquals(0, updates)
        assertEquals(0, widget.version.value)
    }
}
