/** The app's version (spec 017): [name] is `versionName`, [code] is `versionCode`. */
data class AppVersion(val name: String, val code: Int)

private val TAG = Regex("""v(\d+)\.(\d+)\.(\d+)""")

/**
 * The version from the latest `vX.Y.Z` tag, as `git describe --tags --match v* --abbrev=0` prints it:
 * `versionCode = X * 10000 + Y * 100 + Z`. No tag (a fresh clone, a shallow checkout) is `0.0.0` / `1`; a tag that is
 * not `vX.Y.Z` with Y and Z below 100 fails the build, so two releases never share a code.
 */
fun appVersion(tag: String?): AppVersion {
    val text = tag?.trim().orEmpty()
    if (text.isEmpty()) return AppVersion("0.0.0", 1)
    val match = TAG.matchEntire(text) ?: error("The tag '$text' is not vX.Y.Z (spec 017)")
    val (major, minor, patch) = match.destructured.toList().map { it.toInt() }
    require(minor < 100 && patch < 100) { "The tag '$text' needs minor and patch below 100 (spec 017)" }
    require(major <= 20000) { "The tag '$text' gives a versionCode above Google Play's limit (spec 017)" }
    return AppVersion("$major.$minor.$patch", maxOf(1, major * 10000 + minor * 100 + patch))
}
