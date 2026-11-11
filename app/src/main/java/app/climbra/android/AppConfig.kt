package app.climbra.android

/** Build settings the app reads; injected so tests can set them (values come from [BuildConfig]). */
data class AppConfig(
    val apiBaseUrl: String,
    val webBaseUrl: String,
    val googleServerClientId: String,
    val googleLoginEnabled: Boolean,
) {
    /** A page of the web app, e.g. `register`. */
    fun webPage(path: String): String = webBaseUrl.trimEnd('/') + "/" + path.trimStart('/')

    companion object {
        fun fromBuildConfig() = AppConfig(
            apiBaseUrl = BuildConfig.API_BASE_URL,
            webBaseUrl = BuildConfig.WEB_BASE_URL,
            googleServerClientId = BuildConfig.GOOGLE_SERVER_CLIENT_ID,
            googleLoginEnabled = BuildConfig.GOOGLE_LOGIN_ENABLED,
        )
    }
}
