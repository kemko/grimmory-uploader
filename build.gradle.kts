import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.dependency.check) apply false
    alias(libs.plugins.ksp) apply false
}

val versionProperties = Properties().apply {
    rootProject.file("version.properties").inputStream().use(::load)
}
val appVersion = versionProperties.getProperty("version")
    ?: error("version.properties must contain version=<major>.<minor>.<patch>")
val versionParts = Regex("\\d+\\.\\d+\\.\\d+").matchEntire(appVersion)?.value
    ?.split('.')
    ?.map(String::toInt)
    ?: error("Only stable SemVer is supported: $appVersion")
require(versionParts.all { it >= 0 })
val appVersionCode = versionParts[0] * 1_000_000 + versionParts[1] * 1_000 + versionParts[2]
require(appVersionCode > 0) { "versionCode must be positive" }

rootProject.extra["appVersionName"] = appVersion
rootProject.extra["appVersionCode"] = appVersionCode

allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}
