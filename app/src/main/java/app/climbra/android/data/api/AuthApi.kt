package app.climbra.android.data.api

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

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

    /** `204`; the account is read again with [me]. */
    @POST("api/account/accept-terms")
    suspend fun acceptTerms(@Body request: AcceptTermsRequest)
}

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
