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

/** The version from the latest `vX.Y.Z` tag (spec 017, `buildSrc/AppVersion.kt`); without git or a tag 0.0.0. */
val appVersion = appVersion(
    providers.exec {
        commandLine("git", "describe", "--tags", "--match", "v*", "--abbrev=0")
        isIgnoreExitValue = true
    }.standardOutput.asText.orNull,
)

/**
 * A release signing setting (spec 017): `climbra.release.<name>` in local.properties, or `CLIMBRA_RELEASE_<NAME>` in
 * the environment (CI). The keystore and its passwords are never committed.
 */
fun releaseSigning(name: String): String? =
    localProperties.getProperty("climbra.release.$name")
        ?: providers.environmentVariable("CLIMBRA_RELEASE_" + name.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()).orNull
            ?.takeIf { it.isNotBlank() }

android {
    namespace = "app.climbra.android"
    compileSdk {
        // 37.1: Compose 1.13 (BOM 2026.11) compiles only against it; targetSdk stays 37
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "app.climbra.android"
        minSdk = 29
        targetSdk = 37
        versionCode = appVersion.code
        versionName = appVersion.name

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Web client id of the backend's Google Cloud project (spec 001, cyclingapp spec 050); empty hides Google login
        val googleClientId = setting("climbra.googleServerClientId", "")
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", googleClientId.quoted())
        buildConfigField("boolean", "GOOGLE_LOGIN_ENABLED", googleClientId.isNotBlank().toString())

        // The Firebase project of the push notifications (spec 020, cyclingapp spec 056), set up in code instead of
        // google-services.json; without all four there is no Firebase and no notification
        val firebase = listOf("appId", "apiKey", "projectId", "senderId").associateWith { setting("climbra.firebase.$it", "") }
        buildConfigField("String", "FIREBASE_APP_ID", firebase.getValue("appId").quoted())
        buildConfigField("String", "FIREBASE_API_KEY", firebase.getValue("apiKey").quoted())
        buildConfigField("String", "FIREBASE_PROJECT_ID", firebase.getValue("projectId").quoted())
        buildConfigField("String", "FIREBASE_SENDER_ID", firebase.getValue("senderId").quoted())
        buildConfigField("boolean", "PUSH_ENABLED", firebase.values.all { it.isNotBlank() }.toString())
    }

    // Without the settings the release build stays unsigned, so a fresh clone and CI on a pull request still build
    val storeFile = releaseSigning("storeFile")
    if (storeFile != null) {
        signingConfigs {
            create("release") {
                this.storeFile = rootProject.file(storeFile)
                storePassword = releaseSigning("storePassword")
                keyAlias = releaseSigning("keyAlias")
                keyPassword = releaseSigning("keyPassword")
            }
        }
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
            signingConfig = signingConfigs.findByName("release")
            // R8 over the whole app (spec 017); the libraries' own consumer rules are enough so far
            optimization {
                enable = true
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

/** The Android image Robolectric runs the JVM tests on, resolved by Gradle (see `robolectricJars`). */
val robolectricAndroidAll: Configuration by configurations.creating

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.navigation.suite)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
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
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.markdown.renderer.m3)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    robolectricAndroidAll(libs.robolectric.android.all)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

/*
 * Robolectric's Android image comes from Gradle, with its retries and its cache, and Robolectric runs offline:
 * left to itself it downloads the image in the tests, and one failed download fails every Robolectric class (CI).
 */
val robolectricJars = tasks.register<Sync>("robolectricJars") {
    from(robolectricAndroidAll)
    into(layout.buildDirectory.dir("robolectric-jars"))
}

tasks.withType<Test>().configureEach {
    dependsOn(robolectricJars)
    val jars = robolectricJars.map { it.destinationDir.absolutePath }
    systemProperty("robolectric.offline", "true")
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Drobolectric.dependency.dir=${jars.get()}") })
}
