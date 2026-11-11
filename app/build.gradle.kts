import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/** Developer overrides from local.properties (not committed), e.g. `climbra.apiBaseUrl` or the Google client id. */
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun setting(name: String, default: String): String =
    (localProperties.getProperty(name) ?: providers.gradleProperty(name).orNull ?: default)

fun String.quoted() = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "app.climbra.android"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "app.climbra.android"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Web client id of the backend's Google Cloud project (spec 001, cyclingapp spec 050); empty hides Google login
        val googleClientId = setting("climbra.googleServerClientId", "")
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", googleClientId.quoted())
        buildConfigField("boolean", "GOOGLE_LOGIN_ENABLED", googleClientId.isNotBlank().toString())
    }

    buildTypes {
        debug {
            // The device reaches the backend on this PC through `adb reverse tcp:8080 tcp:8080` (and 4200 for the web
            // pages): localhost is not "local network", which Android 17 apps may not reach without a permission
            buildConfigField("String", "API_BASE_URL", setting("climbra.apiBaseUrl", "http://localhost:8080").quoted())
            buildConfigField("String", "WEB_BASE_URL", setting("climbra.webBaseUrl", "http://localhost:4200").quoted())
        }
        release {
            buildConfigField("String", "API_BASE_URL", "https://climbra.app".quoted())
            buildConfigField("String", "WEB_BASE_URL", "https://climbra.app".quoted())
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // English is the default in values/, Polish in values-pl/ (per-app language, spec 001)
        localeFilters += listOf("en", "pl")
        generateLocaleConfig = true
    }
    testOptions {
        unitTests {
            // Robolectric reads resources (strings, fonts) in JVM tests
            isIncludeAndroidResources = true
            // android.util.Log and similar answer defaults in plain JVM tests
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.tink.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.vico.compose.m3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
