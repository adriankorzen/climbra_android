package app.climbra.android.auth.google

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

sealed interface GoogleCredentialResult {
    data class Token(val idToken: String) : GoogleCredentialResult

    /** The user closed the account picker: no error is shown. */
    data object Cancelled : GoogleCredentialResult

    data object Failed : GoogleCredentialResult
}

/** Gets a Google ID token carrying [nonce]; an interface so tests replace the system UI. */
fun interface GoogleCredentialSource {
    suspend fun idToken(nonce: String): GoogleCredentialResult
}

/**
 * Credential Manager's "Sign in with Google" flow (the button flow, which also offers adding an account). The token's
 * audience is [serverClientId], the backend's web client id (cyclingapp spec 050).
 */
class CredentialManagerGoogleSource(
    private val activityContext: Context,
    private val serverClientId: String,
) : GoogleCredentialSource {

    override suspend fun idToken(nonce: String): GoogleCredentialResult {
        val option = GetSignInWithGoogleOption.Builder(serverClientId).setNonce(nonce).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val credential = CredentialManager.create(activityContext).getCredential(activityContext, request).credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                GoogleCredentialResult.Token(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } else {
                GoogleCredentialResult.Failed
            }
        } catch (e: GetCredentialCancellationException) {
            GoogleCredentialResult.Cancelled
        } catch (e: NoCredentialException) {
            // No Google account and adding one was not finished
            GoogleCredentialResult.Failed
        } catch (e: GetCredentialException) {
            GoogleCredentialResult.Failed
        } catch (e: GoogleIdTokenParsingException) {
            GoogleCredentialResult.Failed
        }
    }
}
