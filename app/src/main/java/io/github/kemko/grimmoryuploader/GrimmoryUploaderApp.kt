package io.github.kemko.grimmoryuploader

import android.app.Application
import io.github.kemko.grimmoryuploader.di.AppContainer

class GrimmoryUploaderApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
