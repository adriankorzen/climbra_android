package app.climbra.android.data.api

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.PUT

/** Login, session and terms endpoints of the backend (`account.AuthController`, `account.AccountController`). */
interface AuthApi {

    @GET("api/auth/me")
    suspend fun me(): AccountResponse

    @GET("api/auth/providers")
    suspend fun providers(): AuthProviders

    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): AccountResponse

    @POST("api/auth/logout")
    suspend fun logout()

    /** cyclingapp spec 050. */
    @GET("api/auth/google/nonce")
    suspend fun googleNonce(): NonceResponse

    /** cyclingapp spec 050. */
    @POST("api/auth/google/id-token")
    suspend fun googleLogin(@Body request: GoogleIdTokenRequest): AccountResponse

    /** `202`; a taken address gets the same answer (its owner gets an email). Spec 013. */
    @POST("api/auth/register")
    suspend fun register(@Body request: RegisterRequest)

    /** `202`; spec 013. */
    @POST("api/auth/resend-verification")
    suspend fun resendVerification(@Body request: EmailRequest)

    /** `202` whether the account exists or not; spec 013. */
    @POST("api/auth/password-reset")
    suspend fun requestPasswordReset(@Body request: EmailRequest)

    /** The email link's token and the registration's password; starts a session (spec 021). */
    @POST("api/auth/verify-email")
    suspend fun verifyEmail(@Body request: VerifyEmailRequest): AccountResponse

    /** `204`; the backend ends every session of the account (spec 021). */
    @POST("api/auth/password-reset/confirm")
    suspend fun confirmPasswordReset(@Body request: PasswordResetConfirmRequest)

    /** `204`; the account is read again with [me]. */
    @POST("api/account/accept-terms")
    suspend fun acceptTerms(@Body request: AcceptTermsRequest)

    /** `204`; the backend ends the account's other sessions (cyclingapp spec 006). */
    @PUT("api/account/password")
    suspend fun changePassword(@Body request: PasswordChangeRequest)

    /** `204`; cyclingapp spec 044. */
    @PUT("api/account/language")
    suspend fun changeLanguage(@Body request: LanguageRequest)

    /** `204`; ends the session too (cyclingapp spec 014). */
    @HTTP(method = "DELETE", path = "api/account", hasBody = true)
    suspend fun deleteAccount(@Body request: DeleteAccountRequest)
}

@Serializable
data class PasswordChangeRequest(val currentPassword: String, val newPassword: String)

@Serializable
data class LanguageRequest(val language: String)

/** The password, or the email typed again by an account without a password. */
@Serializable
data class DeleteAccountRequest(val password: String? = null, val email: String? = null)

/** Mirrors `account.AccountResponse`. */
@Serializable
data class AccountResponse(
    val id: Long,
    val email: String,
    val role: String,
    val language: String? = null,
    val riderId: Long? = null,
    val hasPassword: Boolean = false,
    val googleLinked: Boolean = false,
    val termsAccepted: Boolean = false,
    val legalVersion: String? = null,
    val currentLegalVersion: String? = null,
) {
    /** Accepted an earlier version of the documents, which have changed since. */
    val legalDocumentsChanged: Boolean
        get() = legalVersion != null && legalVersion != currentLegalVersion
}

@Serializable
data class AuthProviders(val google: Boolean = false, val turnstileSiteKey: String? = null)

@Serializable
data class LoginRequest(val email: String, val password: String, val rememberMe: Boolean)

@Serializable
data class NonceResponse(val nonce: String)

@Serializable
data class GoogleIdTokenRequest(val idToken: String)

@Serializable
data class AcceptTermsRequest(val acceptTerms: Boolean, val acceptHealthData: Boolean)

/** Mirrors `account.AccountRequests.RegisterRequest`; [captchaToken] is `null` while the backend asks for none. */
@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val language: String,
    val acceptTerms: Boolean,
    val acceptHealthData: Boolean,
    val captchaToken: String?,
)

/** Mirrors `account.AccountRequests.EmailRequest`. */
@Serializable
data class EmailRequest(val email: String, val captchaToken: String?)

/** Mirrors `account.AccountRequests.VerifyEmailRequest`. */
@Serializable
data class VerifyEmailRequest(val token: String, val password: String)

/** Mirrors `account.AccountRequests.PasswordResetConfirmRequest`. */
@Serializable
data class PasswordResetConfirmRequest(val token: String, val password: String)
