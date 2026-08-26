package io.github.kemko.grimmoryuploader.build

import io.github.kemko.grimmoryuploader.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Properties

class VersioningTest {
    @Test
    fun versionPropertiesAndAndroidVersionCodeStayDeterministic() {
        val versionFile = File("../version.properties")
        val properties = Properties()
        versionFile.inputStream().use(properties::load)
        val version = properties.getProperty("version")
        val parts = version.split('.').map(String::toInt)

        val versionSource = versionFile.readText()
        assertTrue(versionSource.contains("# x-release-please-start-version"))
        assertTrue(versionSource.contains("# x-release-please-end"))
        assertTrue(version.matches(Regex("\\d+\\.\\d+\\.\\d+")))
        assertEquals(version, BuildConfig.APP_VERSION)
        assertEquals(parts[0] * 1_000_000 + parts[1] * 1_000 + parts[2], BuildConfig.VERSION_CODE)
    }

    @Test
    fun debugBuildDoesNotRequireReleaseSigningSecrets() {
        assertTrue(!BuildConfig.RELEASE_SIGNING_REQUIRED)
    }

    @Test
    fun usesAndroidSdk37() {
        val buildScript = File("build.gradle.kts").readText()

        assertTrue(Regex("""release\(37\)\s*\{\s*minorApiLevel = 1""").containsMatchIn(buildScript))
        assertTrue(buildScript.contains("targetSdk = 37"))
    }
}
