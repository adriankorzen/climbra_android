package app.climbra.android.widget

import app.climbra.android.data.api.FormRange
import app.climbra.android.session.SessionState
import app.climbra.android.testing.TestBackend
import app.climbra.android.testing.TestBackend.Companion.json
import app.climbra.android.testing.TestBackend.Companion.problem
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 027: the widget's requests and what it makes of the answers. */
class TodayRepositoryTest {

    private val backend = TestBackend()
    private var session: SessionState = SessionState.Loading
    private val repository = TodayRepository(backend.riderApi, backend.calendarApi, session = { session })

    @After
    fun tearDown() = backend.close()

    private fun answer(calendar: String = calendar(PLANNED)) {
        backend.on("GET /api/riders", json(RIDERS))
        backend.on("GET /api/riders/5/training-load", json(LOAD))
        backend.on("GET /api/riders/5/calendar", json(calendar))
    }

    @Test
    fun `requests the rider, today's load and today's calendar once each and shows the backend's figures`() = runTest {
        answer()

        val state = repository.load() as TodayState.Ready

        assertEquals(1, backend.requests("GET /api/riders").size)
        val load = backend.requests("GET /api/riders/5/training-load").single()
        assertEquals("1", load.url.queryParameter("days"))
        assertEquals("1", load.url.queryParameter("weeks"))
        val calendar = backend.requests("GET /api/riders/5/calendar").single()
        assertEquals("2026-11-24", calendar.url.queryParameter("from"))
        assertEquals("2026-11-24", calendar.url.queryParameter("to"))
        assertEquals("2026-11-24", state.day)
        assertEquals(64.4, state.fitness.ctl, 0.0)
        assertEquals(-8.2, state.fitness.tsb, 0.0)
        assertEquals(FormRange.OPTIMAL, state.fitness.form)
        val workout = state.workout as TodayWorkout.Planned
        assertEquals("Sweet spot", workout.workout.title)
        assertEquals(0, workout.more)
    }

    @Test
    fun `the day is the answer's today, also an old one`() = runTest {
        backend.on("GET /api/riders", json(RIDERS))
        backend.on("GET /api/riders/5/training-load", json(LOAD.replace("2026-11-24", "2026-11-20")))
        backend.on("GET /api/riders/5/calendar", json(calendar()))

        val state = repository.load() as TodayState.Ready

        assertEquals("2026-11-20", state.day)
        assertEquals("2026-11-20", backend.requests("GET /api/riders/5/calendar").single().url.queryParameter("from"))
    }

    @Test
    fun `a second workout to ride counts as more, a rest day and an empty day are told apart`() = runTest {
        assertEquals(1, (TodayRepository.workout(workouts(PLANNED, PLANNED.replace("\"id\":2", "\"id\":3"))) as TodayWorkout.Planned).more)
        assertEquals(TodayWorkout.Rest, TodayRepository.workout(workouts(REST)))
        assertEquals(TodayWorkout.None, TodayRepository.workout(emptyList()))
        val ride = TodayRepository.workout(workouts(REST, PLANNED)) as TodayWorkout.Planned
        assertEquals(2L, ride.workout.id)
        assertEquals(0, ride.more)
    }

    @Test
    fun `only today's workouts are shown`() = runTest {
        answer(calendar(PLANNED.replace("2026-11-24", "2026-11-25")))

        assertEquals(TodayWorkout.None, (repository.load() as TodayState.Ready).workout)
    }

    @Test
    fun `a failed calendar shows the form without the workout`() = runTest {
        backend.on("GET /api/riders", json(RIDERS))
        backend.on("GET /api/riders/5/training-load", json(LOAD))
        backend.on("GET /api/riders/5/calendar", problem(500, null))

        val state = repository.load() as TodayState.Ready

        assertEquals(TodayWorkout.Unavailable, state.workout)
        assertEquals(64.4, state.fitness.ctl, 0.0)
    }

    @Test
    fun `logged out makes no request, a 401 is logged out too`() = runTest {
        session = SessionState.LoggedOut()
        assertEquals(TodayState.LoggedOut, repository.load())
        assertTrue(backend.requests.none { it.url.encodedPath.startsWith("/api/riders") })

        session = SessionState.Loading
        backend.on("GET /api/riders", problem(401, null))
        assertEquals(TodayState.LoggedOut, repository.load())
    }

    @Test
    fun `terms not accepted, no rider and errors have their states`() = runTest {
        backend.on("GET /api/riders", problem(403, "TERMS_NOT_ACCEPTED"))
        assertEquals(TodayState.NeedsTerms, repository.load())

        backend.replace("GET /api/riders", json("[]"))
        assertEquals(TodayState.NoRider, repository.load())

        backend.replace("GET /api/riders", problem(500, null))
        assertEquals(TodayState.Failed, repository.load())

        backend.replace("GET /api/riders", json(RIDERS))
        backend.on("GET /api/riders/5/training-load", problem(500, null))
        assertEquals(TodayState.Failed, repository.load())
    }

    @Test
    fun `offline the saved answers are shown, without them an error`() = runTest {
        answer()
        repository.load()
        backend.goOffline()

        val state = repository.load() as TodayState.Ready

        assertEquals("Sweet spot", (state.workout as TodayWorkout.Planned).workout.title)
        assertEquals(64.4, state.fitness.ctl, 0.0)
        backend.offlineCache.clear()
        assertEquals(TodayState.Failed, repository.load())
    }

    private fun workouts(vararg json: String) =
        app.climbra.android.data.net.Network.json.decodeFromString(
            app.climbra.android.data.api.CalendarResponse.serializer(),
            calendar(*json),
        ).plannedWorkouts

    companion object {
        const val RIDERS = """[{"id":5,"name":"Anna","timeZone":"Europe/Warsaw"}]"""

        const val LOAD = """{"today":"2026-11-24","days":[{"date":"2026-11-24","tss":0,"ctl":64.4,"atl":72.6,"tsb":-8.2}],
            "weeks":[],"fitness":{"ctl":64.4,"atl":72.6,"tsb":-8.2,"ctlChange":0.05,"atlChange":0.1,"form":"OPTIMAL"}}"""

        const val PLANNED = """{"id":2,"planId":7,"date":"2026-11-24","title":"Sweet spot","type":"SWEET_SPOT",
            "indoor":true,"plannedDurationMinutes":75,"plannedTss":82,"plannedIf":0.81,"blocks":[],"status":"PLANNED"}"""

        const val REST = """{"id":4,"planId":7,"date":"2026-11-24","title":"Rest","type":"REST",
            "plannedDurationMinutes":0,"plannedTss":0,"status":"REST"}"""

        fun calendar(vararg workouts: String) =
            """{"from":"2026-11-24","to":"2026-11-24","plans":[],"plannedWorkouts":[${workouts.joinToString(",")}],
            "activities":[],"weeks":[]}"""
    }
}
