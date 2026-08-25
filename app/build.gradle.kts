import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.dependency.check)
}

val appVersionName = rootProject.extra["appVersionName"] as String
val appVersionCode = rootProject.extra["appVersionCode"] as Int

android {
    namespace = "io.github.kemko.grimmoryuploader"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.kemko.grimmoryuploader"
        minSdk = 35
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        manifestPlaceholders["appAuthRedirectScheme"] = "io.github.kemko.grimmoryuploader"
        buildConfigField("String", "APP_VERSION", "\"$appVersionName\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("release") {
            providers.environmentVariable("ANDROID_SIGNING_STORE_FILE").orNull?.let { storeFile = file(it) }
            storePassword = providers.environmentVariable("ANDROID_SIGNING_STORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("ANDROID_SIGNING_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("ANDROID_SIGNING_KEY_PASSWORD").orNull
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "RELEASE_SIGNING_REQUIRED", "false")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "RELEASE_SIGNING_REQUIRED", "true")
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

ktlint {
    android.set(true)
    outputToConsole.set(true)
    ignoreFailures.set(false)
}

kover {
    reports {
        filters {
            excludes {
                classes(
                    "**.BuildConfig",
                    "**.R",
                    "**.R$*",
                    "**.ComposableSingletons$*",
                    "**.di.FoundationDatabase",
                    "**.upload.db.RoomUpload*",
                )
            }
        }
        verify {
            rule {
                minBound(80)
            }
        }
    }
}

dependencyCheck {
    autoUpdate = providers.gradleProperty("dependencyCheckAutoUpdate").map(String::toBoolean).orElse(false).get()
    failBuildOnCVSS = 7.0f
    failOnError = true
    formats = listOf("HTML", "SARIF")
    suppressionFile = rootProject.file("config/dependency-check-suppressions.xml").absolutePath
    hostedSuppressions {
        enabled = false
    }
    analyzers {
        assemblyEnabled = false
        nodeEnabled = false
        nodeAudit {
            enabled = false
        }
        nodePackage {
            enabled = false
        }
        ossIndex {
            enabled = false
        }
        retirejs {
            enabled = false
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    annotationProcessor(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.appauth)
    implementation(libs.androidx.browser)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui)
}
