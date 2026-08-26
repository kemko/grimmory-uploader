package io.github.kemko.grimmoryuploader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ManifestFiltersTest {
    @Test
    fun exposesBookTypesForSendAndViewWithoutWebOrDjvuHandlers() {
        val document = manifest()
        val manifestText = File("src/main/AndroidManifest.xml").readText()
        val filters = mainActivityFilters(document)
        val send = filters.filter { it.actions.contains(ACTION_SEND) }
        val view = filters.filter { it.actions.contains(ACTION_VIEW) }

        assertTrue(send.flatMap { it.mimeTypes }.containsAll(BOOK_MIME_TYPES + "text/plain"))
        assertTrue(view.flatMap { it.mimeTypes }.containsAll(BOOK_MIME_TYPES))
        assertTrue(view.flatMap { it.suffixes }.containsAll(setOf(".fb2", ".fb2.zip", ".epub", ".pdf")))
        assertTrue(send.none { it.schemes.any { scheme -> scheme == "http" || scheme == "https" } })
        assertTrue(view.none { it.schemes.any { scheme -> scheme == "http" || scheme == "https" } })
        assertFalse(manifestText.contains("SEND_MULTIPLE"))
        assertFalse(manifestText.contains("djvu", ignoreCase = true))
    }

    @Test
    fun textPlainIsRegisteredOnlyForShare() {
        val filters = mainActivityFilters(manifest())
        assertTrue(filters.any { ACTION_SEND in it.actions && "text/plain" in it.mimeTypes })
        assertFalse(filters.any { ACTION_VIEW in it.actions && "text/plain" in it.mimeTypes })
    }

    private fun manifest(): Document =
        DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))

    private fun mainActivityFilters(document: Document): List<IntentFilter> {
        val activity =
            (0 until document.getElementsByTagName("activity").length)
                .map { document.getElementsByTagName("activity").item(it) as Element }
                .first { it.getAttribute("android:name") == ".MainActivity" }
        return (0 until activity.getElementsByTagName("intent-filter").length)
            .map { activity.getElementsByTagName("intent-filter").item(it) as Element }
            .map { filter ->
                IntentFilter(
                    actions =
                        (0 until filter.getElementsByTagName("action").length)
                            .map { filter.getElementsByTagName("action").item(it) as Element }
                            .map { it.getAttribute("android:name") }
                            .toSet(),
                    mimeTypes =
                        (0 until filter.getElementsByTagName("data").length)
                            .map { filter.getElementsByTagName("data").item(it) as Element }
                            .mapNotNull { it.getAttribute("android:mimeType").takeIf(String::isNotBlank) }
                            .toSet(),
                    schemes =
                        (0 until filter.getElementsByTagName("data").length)
                            .map { filter.getElementsByTagName("data").item(it) as Element }
                            .mapNotNull { it.getAttribute("android:scheme").takeIf(String::isNotBlank) }
                            .toSet(),
                    suffixes =
                        (0 until filter.getElementsByTagName("data").length)
                            .map { filter.getElementsByTagName("data").item(it) as Element }
                            .mapNotNull { it.getAttribute("android:pathSuffix").takeIf(String::isNotBlank) }
                            .toSet(),
                )
            }
    }

    private data class IntentFilter(
        val actions: Set<String>,
        val mimeTypes: Set<String>,
        val schemes: Set<String>,
        val suffixes: Set<String>,
    )

    private companion object {
        const val ACTION_SEND = "android.intent.action.SEND"
        const val ACTION_VIEW = "android.intent.action.VIEW"
        val BOOK_MIME_TYPES =
            setOf(
                "application/epub+zip",
                "application/pdf",
                "application/x-fictionbook+xml",
                "application/xml",
                "application/zip",
                "application/octet-stream",
            )
    }
}
