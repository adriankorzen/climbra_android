package app.climbra.android.activities

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.climbra.android.R
import app.climbra.android.coach.PickedFile
import app.climbra.android.data.api.ImportJob
import app.climbra.android.data.net.ApiError
import app.climbra.android.data.net.Network
import app.climbra.android.shell.Shell
import app.climbra.android.ui.common.UiText
import app.climbra.android.ui.format.Fmt
import app.climbra.android.ui.theme.ClimbraTheme
import java.util.Locale
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Spec 009 AC1 - AC11: the import screen and its place in the shell. */
@RunWith(AndroidJUnit4::class)
class ImportScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val fmt = Fmt(Locale.forLanguageTag("en"))

    private fun content(body: @Composable () -> Unit) = compose.setContent { ClimbraTheme(darkTheme = false) { body() } }

    private fun inside(tag: String, text: String) = compose.onNode(
        hasText(text, substring = true) and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))),
        useUnmergedTree = true,
    )

    private fun job(json: String): ImportJob = Network.json.decodeFromString(ImportJob.serializer(), json)

    private val ready = ImportUiState(loading = false)
    private val ride = PickedFile("content://x/ride.fit", "ride.fit")
    private val mb = 1024L * 1024

    // AC3: the single ride's form; Add ride needs a file
    @Test
    fun theSingleRideFormNeedsAFile() {
        val calls = mutableListOf<String>()
        var state by mutableStateOf(ready)
        content {
            ImportScreen(
                state,
                fmt,
                ImportActions(onChooseSingle = { calls += "choose" }, onName = { calls += "name $it" }, onAddRide = { calls += "add" }),
            )
        }

        compose.onNodeWithText("Import rides").assertIsDisplayed()
        compose.onNodeWithTag("mode-single").assertIsSelected()
        compose.onNodeWithTag("add-ride").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("choose-single").performClick()
        compose.onNodeWithTag("ride-name").performTextInput("Loop")

        state = ready.copy(single = ride)
        inside("single-name", "ride.fit").assertExists()
        compose.onNodeWithTag("add-ride").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("choose", "name Loop", "add"), calls)

        // AC2
        state = ready.copy(rejected = listOf("notes.txt"))
        inside("import-rejected", "Skipped unsupported files: notes.txt").assertExists()
    }

    // AC4: a duplicate opens the stored ride; the other answers' messages
    @Test
    fun refusalsShowTheirMessages() {
        var opened: Long? = null
        var state by mutableStateOf(ready.copy(single = ride, error = ApiError.Http(409, null, activityId = 12)))
        content { ImportScreen(state, fmt, ImportActions(onOpenRide = { opened = it })) }

        inside("import-error", "This ride is already stored.").assertExists()
        compose.onNodeWithTag("open-duplicate").performScrollTo().performClick()
        assertEquals(12L, opened)

        state = state.copy(error = ApiError.Http(413, null))
        inside("import-error", "The file is larger than 50 MB.").assertExists()
        compose.onNodeWithTag("open-duplicate").assertDoesNotExist()
        state = state.copy(error = ApiError.Http(422, null))
        inside("import-error", "The file can't be read as a ride.").assertExists()
        state = state.copy(error = ApiError.Http(503, "FILE_PROCESSING_BUSY"))
        inside("import-error", "Try again in a few seconds").assertExists()
    }

    // AC5: the chosen files with their sizes and count; over 50 MB the web
    @Test
    fun theSelectionShowsFilesSizesAndTheLimit() {
        var web = 0
        val removed = mutableListOf<String>()
        val files = listOf(ChosenFile(ride, 3 * mb), ChosenFile(PickedFile("content://x/b.tcx", "b.tcx"), mb / 2))
        var state by mutableStateOf(ready.copy(mode = ImportMode.SEVERAL, files = files))
        content { ImportScreen(state, fmt, ImportActions(onOpenWeb = { web++ }, onRemove = { removed += it.file.name })) }

        compose.onNodeWithTag("mode-several").assertIsSelected()
        inside("chosen-ride.fit", "3.0 MB").assertExists()
        inside("import-selected", "2 files · 3.5 MB").assertExists()
        compose.onNodeWithTag("start-import").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("remove-b.tcx").performClick()
        assertEquals(listOf("b.tcx"), removed)
        compose.onNodeWithTag("import-over-limit").assertDoesNotExist()

        state = state.copy(files = files + ChosenFile(PickedFile("content://x/all.zip", "all.zip"), 60 * mb))
        compose.onNodeWithTag("import-over-limit").performScrollTo()
        inside("import-over-limit", "Large exports (more than 50 MB) are imported on climbra.app.").assertExists()
        compose.onNodeWithTag("import-open-web").performClick()
        assertEquals(1, web)
        compose.onNodeWithTag("start-import").performScrollTo().assertIsNotEnabled()

        // AC6
        state = state.copy(files = files, error = ApiError.Http(409, "IMPORT_RUNNING"))
        inside("import-error", "An import is already running.").assertExists()
        state = state.copy(error = ApiError.Http(503, "IMPORT_QUEUE_FULL"))
        inside("import-error", "busy with other imports").assertExists()
        state = state.copy(error = ApiError.Http(413, null))
        inside("import-error", "The files are larger than 50 MB.").assertExists()
    }

    // AC7: queued, reading, then the backend's progress
    @Test
    fun theProgressFollowsTheJob() {
        var state by mutableStateOf(ready.copy(job = job(ImportFixtures.job("QUEUED")), mode = ImportMode.SEVERAL))
        content { ImportScreen(state, fmt, ImportActions()) }

        inside("import-progress", "The import is queued…").assertExists()
        inside("import-progress", "You can leave this screen").assertExists()
        state = state.copy(job = job(ImportFixtures.job("RUNNING")))
        inside("import-progress", "Reading the files…").assertExists()
        state = state.copy(job = job(ImportFixtures.job("RUNNING", processed = 12, selected = 40)))
        inside("import-progress", "Importing rides: 12 of 40").assertExists()
        compose.onNodeWithTag("start-import").assertDoesNotExist()

        // AC10
        state = state.copy(sharedWhileRunning = true)
        inside("shared-while-running", "An import is already running.").assertExists()
    }

    // AC8: counters, range, the files not imported; a duplicate opens its ride; Import more
    @Test
    fun theResultShowsTheBackendsOutcome() {
        var opened: Long? = null
        var more = 0
        val finished = job(ImportFixtures.job("COMPLETED", processed = 3, selected = 3, imported = 2, items = ImportFixtures.ITEMS))
        var state by mutableStateOf(ready.copy(mode = ImportMode.SEVERAL, job = finished))
        content { ImportScreen(state, fmt, ImportActions(onOpenRide = { opened = it }, onImportMore = { more++ })) }

        compose.onNodeWithTag("import-completed").assertIsDisplayed()
        inside("counter-imported", "2").assertExists()
        inside("counter-duplicates", "1").assertExists()
        inside("counter-skipped", "Not cycling").assertExists()
        inside("counter-failed", "1").assertExists()
        compose.onNodeWithTag("counter-over-limit").assertDoesNotExist()
        inside("import-range", "Rides from Oct 2, 2026 – Nov 15, 2026").assertExists()

        compose.onNodeWithTag("not-imported-toggle").performScrollTo()
        inside("not-imported-toggle", "Files not imported (3)").assertExists()
        compose.onNodeWithTag("item-0").assertDoesNotExist()
        compose.onNodeWithTag("not-imported-toggle").performClick()
        inside("item-0", "b.fit").assertExists()
        compose.onNode(hasText("Already stored") and hasAnyAncestor(hasTestTag("item-0")), useUnmergedTree = true).assertExists()
        inside("item-0", "Already stored as activity 12").assertExists()
        inside("item-1", "Not cycling").assertExists()
        inside("item-2", "Not saved, try again").assertExists()
        inside("item-2", "Bad header").assertExists()
        compose.onNodeWithTag("item-0").performScrollTo().performClick()
        assertEquals(12L, opened)
        compose.onNodeWithTag("import-more").performScrollTo().performClick()
        assertEquals(1, more)

        state = state.copy(job = job(ImportFixtures.job("FAILED", message = "Storage down")))
        inside("import-failed", "The import failed: Storage down").assertExists()
    }

    // AC11: no rider, no form
    @Test
    fun withoutARiderTheProfileIsOffered() {
        var profile = 0
        content { ImportScreen(ready.copy(noRider = true), fmt, ImportActions(onOpenProfile = { profile++ })) }

        inside("import-no-rider", "Create your rider profile first").assertExists()
        compose.onNodeWithTag("mode-single").assertDoesNotExist()
        compose.onNodeWithTag("import-open-profile").performClick()
        assertEquals(1, profile)
    }

    // AC1: the import opens on the training log's tab; AC3: a single ride gives way to its detail
    @Test
    fun theImportOpensOnTheTrainingLogAndASingleRideGivesWayToItsDetail() {
        content {
            Shell(
                dashboard = { nav -> Button(onClick = nav::openImport, modifier = Modifier.testTag("dashboard-import")) { Text("dashboard") } },
                activities = { Text("training log", Modifier.testTag("log")) },
                profile = { Text("profile") },
                ride = { Text("ride detail", Modifier.testTag("ride-detail")) },
                rideImport = { nav -> Button(onClick = { nav.rideAdded(88) }, modifier = Modifier.testTag("import-screen")) { Text("import") } },
            )
        }

        compose.onNodeWithTag("dashboard-import").performClick()
        compose.onNodeWithTag("nav-activities").assertIsSelected()
        compose.onNodeWithTag("import-screen").performClick()
        compose.onNodeWithTag("ride-detail").assertIsDisplayed()
        // Back leads to the training log, not to the import
        compose.onNodeWithTag("nav-activities").performClick()
        compose.onNodeWithTag("log").assertIsDisplayed()
    }

    // AC9, AC10: shared files open the import; its end is told with "Show"
    @Test
    fun sharedFilesAndTheImportsEndOpenTheImport() {
        val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val notices = MutableSharedFlow<UiText>(extraBufferCapacity = 1)
        content {
            Shell(
                dashboard = { Text("dashboard") },
                activities = { Text("training log", Modifier.testTag("log")) },
                profile = { Text("profile") },
                rideImport = { nav -> Button(onClick = nav::back, modifier = Modifier.testTag("import-screen")) { Text("import") } },
                importRequests = requests,
                importNotices = notices,
            )
        }

        requests.tryEmit(Unit)
        compose.onNodeWithTag("import-screen").assertIsDisplayed()
        compose.onNodeWithTag("nav-activities").assertIsSelected()
        compose.onNodeWithTag("import-screen").performClick()
        compose.onNodeWithTag("log").assertIsDisplayed()

        notices.tryEmit(UiText(R.plurals.import_done, listOf(3), quantity = 3))
        compose.onNodeWithText("Imported rides: 3").assertIsDisplayed()
        compose.onNodeWithText("Show").performClick()
        compose.onNodeWithTag("import-screen").assertIsDisplayed()
    }
}
