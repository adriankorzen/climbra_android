import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppVersionTest {

    @Test
    fun `a vX_Y_Z tag gives the name and the code`() {
        assertEquals(AppVersion("1.2.3", 10203), appVersion("v1.2.3"))
        assertEquals(AppVersion("0.1.0", 100), appVersion("v0.1.0\n"))
        assertEquals(AppVersion("12.99.99", 129999), appVersion("v12.99.99"))
    }

    @Test
    fun `no tag is version 0_0_0 with code 1`() {
        assertEquals(AppVersion("0.0.0", 1), appVersion(null))
        assertEquals(AppVersion("0.0.0", 1), appVersion(""))
        assertEquals(AppVersion("0.0.0", 1), appVersion("v0.0.0"))
    }

    @Test
    fun `a malformed tag fails the build`() {
        listOf("1.2.3", "v1.2", "v1.2.3-rc1", "v1.100.0", "v1.0.100").forEach { tag ->
            assertThrows(tag, RuntimeException::class.java) { appVersion(tag) }
        }
    }
}
