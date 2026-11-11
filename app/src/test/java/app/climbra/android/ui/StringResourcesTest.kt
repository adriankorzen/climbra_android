package app.climbra.android.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Spec 001 AC10: English and Polish have the same strings (like the i18n test of the web app). */
class StringResourcesTest {

    private fun keys(folder: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/$folder/strings.xml"))
        val strings = document.getElementsByTagName("string")
        return (0 until strings.length).map { strings.item(it) as Element }
            .filter { it.getAttribute("translatable") != "false" }
            .associate { it.getAttribute("name") to it.textContent }
    }

    @Test
    fun englishAndPolishHaveIdenticalKeys() {
        val english = keys("values")
        val polish = keys("values-pl")
        assertTrue(english.isNotEmpty())
        assertEquals(english.keys.sorted(), polish.keys.sorted())
    }

    @Test
    fun placeholdersMatch() {
        val polish = keys("values-pl")
        val placeholder = Regex("%\\d+\\$[sd]")
        for ((key, text) in keys("values")) {
            assertEquals(key, placeholder.findAll(text).map { it.value }.toSet(),
                placeholder.findAll(polish.getValue(key)).map { it.value }.toSet())
        }
    }
}
