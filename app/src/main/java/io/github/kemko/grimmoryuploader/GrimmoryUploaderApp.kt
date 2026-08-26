package io.github.kemko.grimmoryuploader

import android.app.Application
import io.github.kemko.grimmoryuploader.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

class GrimmoryUploaderApp : Application() {
    lateinit var container: AppContainer
        private set
    lateinit var startupReconciliation: Deferred<Unit>
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        startupReconciliation =
            CoroutineScope(SupervisorJob() + Dispatchers.IO).async {
                container.pendingJobReconciler.reconcile()
            }
    }
}
