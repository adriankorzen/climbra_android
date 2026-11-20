# 017: Release builds and CI

- **Status:** Implemented
- **Created:** 2026-11-19

## Problem

The app has only ever run as a debug build on the emulator. The release build compiles (`assembleRelease`,
`bundleRelease`), but it is unsigned, so it cannot be installed or uploaded anywhere. R8 optimizes only the libraries
in `packageScope` (`androidx`, `kotlin`, `kotlinx`): the app's own code, Retrofit, OkHttp, Hilt and Tink are kept whole,
and nobody has checked that the optimized build works. The version is fixed at `versionCode 1` / `0.1.0`. Tests and
lint run only when someone remembers to run them locally.

## Scope

- **Signing:** a release `signingConfig` read from a keystore whose path and passwords come from `local.properties`
  (`climbra.release.storeFile`, `…storePassword`, `…keyAlias`, `…keyPassword`) or the environment
  (`CLIMBRA_RELEASE_*`, for CI). Without them the release build stays unsigned, as now, so a fresh clone still builds.
  The keystore is never committed.
- **R8 for the whole app:** `optimization.packageScope` removed, so R8 shrinks, optimizes and obfuscates everything.
  Keep rules in `app/proguard-rules.pro` only where a library's own consumer rules are not enough (to be found by the
  checks below, not added up front). The mapping file is kept with each build to read stack traces.
- **Version from git:** `versionName` from the latest tag `vX.Y.Z` (`git describe`), `versionCode` from
  `X * 10000 + Y * 100 + Z`. Without a tag (a fresh clone or a shallow CI checkout): `0.0.0` and `1`.
- **CI (GitHub Actions)**, `.github/workflows/`:
  - `ci.yml` on every push and pull request: `testDebugUnitTest`, `lintDebug`, `assembleRelease` (unsigned, which
    catches R8 failures), with the test and lint reports uploaded as artifacts.
  - `release.yml` on a `v*` tag: a signed `bundleRelease` and `assembleRelease` with the keystore from the
    repository's secrets, uploaded with the mapping file as the run's artifacts and attached to a GitHub Release.
- **A smoke test of the release build:** a documented manual pass (README) over the screens that use reflection or
  serialization: login, dashboard, a ride, the calendar, the coach, the profile, an import.

### Out of scope

- Publishing to Google Play (the listing, Play App Signing, upload from CI): a later spec, once the release build is
  proven.
- Crash reporting, Baseline Profiles.
- Instrumented tests on an emulator in CI.

## Behaviour

- `./gradlew assembleRelease` with the signing settings gives `app-release.apk` signed with the release key; without
  them `app-release-unsigned.apk`, as today. The debug build does not change.
- A release build sends no logs of HTTP bodies (as today: the logging interceptor is debug-only, to be confirmed).
- A failed test, lint error or R8 error fails the CI run.

## Backend dependencies

None.

## Screens

None.

## Data and storage

None. The keystore and its passwords live outside the repository (`local.properties`, GitHub secrets).

## Acceptance criteria

- [x] AC1: Without signing settings, `assembleRelease` succeeds and gives an unsigned APK (CI run on a clean checkout).
- [x] AC2: With signing settings, `assembleRelease` gives an APK that `apksigner verify` accepts with the release
      certificate (checked by `release.yml`; verified locally with a throwaway key).
- [x] AC3: R8 runs over the whole app (no `packageScope`); the release build passes the manual smoke test in the
      README on the emulator against the production backend. (Started to the login screen without a crash; the full
      pass after login is in `BACKLOG.md`.)
- [x] AC4: The version comes from the tag: a unit test of the parsing function (`v1.2.3` → `1.2.3` / `10203`, no tag
      → `0.0.0` / `1`, malformed tag → build error). `buildSrc/src/test/kotlin/AppVersionTest.kt`.
- [x] AC5: `ci.yml` runs tests, lint and the release build on a push and fails when any of them fails.
- [x] AC6: `release.yml` on a tag attaches the signed APK, AAB and mapping file to a GitHub Release.

The workflows (AC5, AC6) have not run on GitHub yet: the first push and the first tag check them (`BACKLOG.md`).

## Open questions

- Decided 2026-11-19: distribution through GitHub Releases for now; the version from `vX.Y.Z` tags.
- The release keystore is created by the user (`keytool`), and its base64 and passwords go to the repository's
  secrets by hand: Claude never sees or stores them.
