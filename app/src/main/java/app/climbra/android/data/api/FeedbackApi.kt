package app.climbra.android.data.api

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

/** Feedback to the author of the app (spec 010, cyclingapp spec 030). */
interface FeedbackApi {
    /** `201`; `400` on invalid fields, `429` when the account sent too much lately. */
    @POST("api/feedback")
    suspend fun send(@Body request: FeedbackRequest)
}

/** Mirrors `feedback.FeedbackRequest`; [path] tells where it was sent from. */
@Serializable
data class FeedbackRequest(val name: String, val type: String, val message: String, val path: String?)

/** The web's `FEEDBACK_TYPES`, in its order. */
val FEEDBACK_TYPES = listOf("BUG", "IMPROVEMENT", "SUGGESTION", "OTHER")
