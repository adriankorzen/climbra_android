# Climbra for Android

The Android app of [Climbra](https://climbra.app), a cycling training application. It is a client of the backend in
the `cyclingapp` repository and looks like its web app, with Material 3 components and Android navigation.

Built spec first: see [`specs/`](specs) and [`CLAUDE.md`](CLAUDE.md).

## Features

| Spec | Feature |
|------|---------|
| [001](specs/001-app-foundation-and-login.md) | Login with email and password or Google, terms acceptance, logout, session kept between app starts, English / Polish (the account's language), bottom navigation with Dashboard, Activities and Profile (content in later specs) |
| [002](specs/002-dashboard.md) | Dashboard: the last 7 days against the 7 before, FTP, fitness / fatigue / form (CTL, ATL, TSB), performance management chart (42–365 days), weekly load, the 7 latest rides. All figures come from the backend (cyclingapp spec 051) |

Registration and password reset open the web pages in a Custom Tab.

## Requirements

- Android 10 (API 29) or newer.
- Building: Android Studio or the Android SDK with JDK 17+; Gradle is the wrapper.

## Running against a local backend

1. Start the backend in `../cyclingapp/backend` with `./mvnw spring-boot:run` (Docker must be running).
2. Forward the ports to the emulator or a phone on USB:
   `adb reverse tcp:8080 tcp:8080` (API) and `adb reverse tcp:4200 tcp:4200` (web pages, if the frontend runs).
3. `./gradlew installDebug` and open Climbra.

Debug builds use `http://localhost:8080`. Release builds use `https://climbra.app`.

Optional settings in `local.properties` (not committed):

| Property | Meaning |
|----------|---------|
| `climbra.apiBaseUrl` | Backend of debug builds, default `http://localhost:8080` |
| `climbra.webBaseUrl` | Web pages of debug builds (register, password reset, terms), default `http://localhost:4200` |
| `climbra.googleServerClientId` | The backend's Google OAuth **web** client id; without it the Google button is hidden. Google login also needs an OAuth client of type Android (package `app.climbra.android` + the signing key's SHA-1 from `./gradlew signingReport`) in the same Google Cloud project, and the backend with cyclingapp spec 050 |

## Tests

```bash
./gradlew testDebugUnitTest   # all tests, including the Compose screen tests (Robolectric), no emulator needed
./gradlew lintDebug
```

## Licenses

Fonts: Inter and JetBrains Mono, SIL Open Font License ([`licenses/`](licenses)). Icons: Material Symbols
(Apache License 2.0).
