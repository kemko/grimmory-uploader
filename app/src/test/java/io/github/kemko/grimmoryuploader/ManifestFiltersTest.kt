package io.github.kemko.grimmoryuploader

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestFiltersTest {
    @Test
    fun exposesOnlySingleFileBookAndShareFilters() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val actions = document.getElementsByTagName("action")
        val text = File("src/main/AndroidManifest.xml").readText()
        assertTrue((0 until actions.length).any { actions.item(it).attributes.getNamedItem("android:name")?.nodeValue == "android.intent.action.SEND" })
        assertTrue((0 until actions.length).any { actions.item(it).attributes.getNamedItem("android:name")?.nodeValue == "android.intent.action.VIEW" })
        assertFalse(text.contains("SEND_MULTIPLE"))
        assertFalse(text.contains("djvu", ignoreCase = true))
        assertTrue(text.contains("android:pathSuffix=\".fb2.zip\""))
        assertTrue(text.contains("android:mimeType=\"text/plain\""))
    }
}
