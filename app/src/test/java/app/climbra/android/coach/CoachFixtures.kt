package app.climbra.android.coach

import app.climbra.android.data.api.CoachConversation
import app.climbra.android.data.api.CoachQuota
import app.climbra.android.data.net.Network
import java.io.ByteArrayInputStream
import kotlinx.serialization.json.JsonPrimitive
import java.io.FileNotFoundException
import java.io.InputStream

/** Coach answers as the backend writes them (spec 006), with fields the app does not know. */
object CoachFixtures {

    const val RIDERS = """[{"id":5,"name":"Anna","weightKg":60.0,"timeZone":"Europe/Warsaw"}]"""

    fun summary(
        id: Long,
        type: String = "ACTIVITY_REVIEW",
        status: String = "READY",
        activityId: Long? = null,
        planId: Long? = null,
        title: String = "Conversation $id",
        lastMessageAt: String = "2026-11-12T09:57:00Z",
        readOnly: Boolean = false,
    ) = """
        {"id":$id,"type":"$type","provider":"GEMINI","title":"$title","activityId":${activityId ?: "null"},"language":"en",
         "createdAt":"2026-11-12T09:00:00Z","lastMessageAt":"$lastMessageAt","status":"$status","readOnly":$readOnly,
         "planId":${planId ?: "null"},"somethingNew":1}
    """.trimIndent()

    fun list(vararg summaries: String) = summaries.joinToString(",", "[", "]")

    const val WORKOUT = """
        {"date":"2026-11-17","title":"Sweet spot 3×12","type":"SWEET_SPOT","indoor":true,"plannedDurationMinutes":75,
         "plannedTss":82,"plannedIf":0.86,"targetRpe":6,"description":"Steady sweet spot.",
         "blocks":[{"repetitions":1,"steps":[{"kind":"WARMUP","durationSeconds":600,"powerLowPctFtp":50,"powerHighPctFtp":70,
           "powerLowWatts":135,"powerHighWatts":189,"zone":2,"cadenceLow":null,"cadenceHigh":null,"note":null}]},
          {"repetitions":3,"steps":[{"kind":"WORK","durationSeconds":720,"powerLowPctFtp":88,"powerHighPctFtp":93,
           "powerLowWatts":238,"powerHighWatts":251,"zone":4,"cadenceLow":85,"cadenceHigh":95,"note":"Seated"}]}]}
    """

    const val REVIEW = """
        {"summary":"A solid sweet spot session.","sessionType":"SWEET_SPOT","estimatedRpe":6,"riderRpe":7,
         "rpeAssessment":"Felt harder than the data says.","strengths":["Even pacing"],"issues":[],
         "recommendations":[{"category":"RECOVERY","text":"Sleep well"},{"category":"PACING","text":"Start easier"},
           {"category":"RECOVERY","text":"Eat carbs"}],
         "nextSession":$WORKOUT,
         "planAssessment":{"verdict":"CHANGE_WORKOUT","workoutDate":"2026-11-17","reason":"Legs need a lighter day."}}
    """

    const val PLAN_CHANGE = """
        {"planId":9,"workoutDate":"2026-11-17","verdict":"CHANGE_WORKOUT","status":"APPLIED",
         "plannedWorkout":{"date":"2026-11-17","title":"VO2max 5×4","type":"VO2MAX","indoor":false,"plannedDurationMinutes":70,
           "plannedTss":90,"plannedIf":0.9,"targetRpe":8,"description":"","blocks":[]}}
    """

    const val BLOCK = """
        {"name":"Build FTP","goal":"Raise FTP","startDate":"2026-11-16","endDate":"2026-12-13","methodology":"SWEET_SPOT",
         "rationale":"Sweet spot builds FTP with little time.",
         "weeks":[{"weekNumber":1,"focus":"Base","plannedTss":320,"isRecoveryWeek":false,"workouts":[$WORKOUT]},
                  {"weekNumber":2,"focus":"Recover","plannedTss":200,"isRecoveryWeek":true,"workouts":[]}]}
    """

    /** A plan adjustment's proposal (spec 008): a changed, an unchanged, a completed removed and an added day. */
    fun proposal(id: Long = 9, status: String = "PENDING") = """
        {"id":$id,"planId":7,"conversationId":12,"status":"$status","createdAt":"2026-11-12T09:30:00Z","fromDate":"2026-11-14",
         "summary":"Less **intensity** this week.",
         "days":[{"date":"2026-11-14","change":"CHANGED","before":$WORKOUT,"after":${WORKOUT.replace("Sweet spot 3×12", "Easy spin")},"completed":false},
                 {"date":"2026-11-15","change":"UNCHANGED","before":$WORKOUT,"after":$WORKOUT,"completed":false},
                 {"date":"2026-11-16","change":"REMOVED","before":$WORKOUT,"after":null,"completed":true},
                 {"date":"2026-11-18","change":"ADDED","before":null,"after":$WORKOUT,"completed":false}],
         "weeks":[{"weekNumber":2,"plannedTssBefore":320,"plannedTssAfter":250,"focusBefore":"Build","focusAfter":"Recover"},
                  {"weekNumber":3,"plannedTssBefore":300,"plannedTssAfter":300,"focusBefore":"Build","focusAfter":null}],
         "unknown":1}
    """.trimIndent()

    /** A conversation's `latestProposal`. */
    fun latestProposal(id: Long = 9, status: String = "PENDING") = """{"id":$id,"status":"$status"}"""

    fun message(id: Long, role: String, text: String, extra: String = "") =
        """{"id":$id,"sequence":$id,"role":"$role","text":${Network.json.encodeToString(JsonPrimitive(text))},"files":[],"createdAt":"2026-11-12T09:0$id:00Z","trainingBlock":false,"medicalReferral":false$extra}"""

    fun conversation(
        id: Long = 12,
        type: String = "ACTIVITY_REVIEW",
        busy: Boolean = false,
        readOnly: Boolean = false,
        messages: String = """[${message(1, "USER", "How was it?")},${message(2, "ASSISTANT", "## Good\n\nA **solid** ride.")}]""",
        review: String = "null",
        block: String = "null",
        plan: String = "null",
        planChange: String = "null",
        pending: String = "null",
        proposal: String = "null",
        activityId: Long? = 85,
        planId: Long? = null,
        title: String = "Ride review",
    ) = """
        {"id":$id,"riderId":5,"type":"$type","provider":"GEMINI","model":"gemini-pro","activityId":${activityId ?: "null"},
         "planId":${planId ?: "null"},"title":"$title","language":"en","createdAt":"2026-11-12T09:00:00Z",
         "updatedAt":"2026-11-12T09:02:00Z","busy":$busy,"readOnly":$readOnly,"messages":$messages,
         "latestReview":$review,"latestTrainingBlock":$block,"plan":$plan,"latestProposal":$proposal,
         "reviewPlanChange":$planChange,"meta":null,"pendingRequest":$pending,"unknownField":true}
    """.trimIndent()

    fun pending(status: String = "RUNNING", kind: String = "MESSAGE", text: String? = "And tomorrow?", errorCode: String? = null) = """
        {"kind":"$kind","status":"$status","text":${text?.let { "\"$it\"" } ?: "null"},"fileNames":["ride.fit"],
         "createdAt":"2026-11-12T09:03:00Z","startedAt":"2026-11-12T09:03:10Z","failedAt":null,
         "errorCode":${errorCode?.let { "\"$it\"" } ?: "null"}}
    """.trimIndent()

    fun quota(remaining: Int = 25, unlimited: Boolean = false, review: Int = 2, block: Int = 5) = """
        {"plan":"FREE","limit":30,"used":5,"remaining":$remaining,
         "costs":{"review":$review,"reviewMessage":1,"trainingBlock":$block,"planAdjustment":3,"blockMessage":2},
         "resetsAt":"2026-12-01T00:00:00Z","unlimited":$unlimited,
         "monthly":{"limit":30,"used":5,"remaining":$remaining,"resetsAt":"2026-12-01T00:00:00Z"},
         "bonus":[],"subscription":null}
    """.trimIndent()

    fun parse(json: String): CoachConversation = Network.json.decodeFromString(CoachConversation.serializer(), json)

    fun parseQuota(json: String): CoachQuota = Network.json.decodeFromString(CoachQuota.serializer(), json)
}

/** Files the rider chose, by uri. */
class FakeFileSource(private val files: Map<String, ByteArray> = emptyMap()) : RideFileSource {
    override fun open(uri: String): InputStream = ByteArrayInputStream(files[uri] ?: throw FileNotFoundException(uri))

    override fun name(uri: String): String? = uri.substringAfterLast('/')
}
