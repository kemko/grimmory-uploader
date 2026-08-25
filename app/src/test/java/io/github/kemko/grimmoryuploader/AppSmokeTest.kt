package io.github.kemko.grimmoryuploader

import org.junit.Assert.assertNotNull
import org.junit.Test

class AppSmokeTest {
    @Test
    fun applicationAndWelcomeComposableAreCreated() {
        assertNotNull(GrimmoryUploaderApp::class.java)
        assertNotNull(MainActivity::class.java)
        assertNotNull(
            Class.forName("io.github.kemko.grimmoryuploader.MainActivityKt")
                .declaredMethods
                .singleOrNull { it.name == "WelcomeScreen" },
        )
    }
}
