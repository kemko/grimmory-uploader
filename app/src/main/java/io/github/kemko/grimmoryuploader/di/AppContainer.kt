package io.github.kemko.grimmoryuploader.di

import android.content.Context
import java.io.File
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.kemko.grimmoryuploader.data.auth.AuthInterceptor
import io.github.kemko.grimmoryuploader.data.auth.AuthRepository
import io.github.kemko.grimmoryuploader.data.auth.EncryptedTokenStore
import io.github.kemko.grimmoryuploader.data.auth.OidcCoordinator
import io.github.kemko.grimmoryuploader.data.auth.TokenStore
import io.github.kemko.grimmoryuploader.data.network.GrimmoryApi
import io.github.kemko.grimmoryuploader.data.network.ServerUrl
import io.github.kemko.grimmoryuploader.data.settings.AppSettingsRepository
import io.github.kemko.grimmoryuploader.upload.StagingStore
import io.github.kemko.grimmoryuploader.upload.PendingJobReconciler
import io.github.kemko.grimmoryuploader.upload.TransferNotificationManager
import io.github.kemko.grimmoryuploader.upload.TransferScheduler
import io.github.kemko.grimmoryuploader.upload.UploadPipeline
import io.github.kemko.grimmoryuploader.upload.UploadQueueRepository
import io.github.kemko.grimmoryuploader.upload.db.UploadDatabase
import okhttp3.OkHttpClient

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsDataStore: DataStore<Preferences> =
        File(appContext.filesDir, "settings.preferences_pb")
            .let { file -> androidx.datastore.preferences.core.PreferenceDataStoreFactory.create { file } }

    val database: UploadDatabase = UploadDatabase.create(appContext)
    val staging: StagingStore = StagingStore(File(appContext.noBackupFilesDir, "pending"))

    val tokenStore: TokenStore = EncryptedTokenStore(appContext)

    val settings: AppSettingsRepository = AppSettingsRepository(settingsDataStore) {
        tokenStore.clear()
    }

    private val rawHttpClient: OkHttpClient = OkHttpClient.Builder().build()

    val downloadClient: OkHttpClient = rawHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    private val rawApi: GrimmoryApi = GrimmoryApi(rawHttpClient, serverUrl = {
        settings.requireCleartextConfirmation()
        settings.requireServerUrl()
    })

    val auth: AuthRepository = AuthRepository(rawApi, tokenStore)

    val httpClient: OkHttpClient = rawHttpClient.newBuilder()
        .addInterceptor(AuthInterceptor(auth))
        .build()

    val api: GrimmoryApi = GrimmoryApi(httpClient, serverUrl = {
        settings.requireCleartextConfirmation()
        settings.requireServerUrl()
    })

    val oidc: OidcCoordinator = OidcCoordinator(appContext, api, auth)

    val upload: UploadQueueRepository = UploadQueueRepository(database.jobs(), staging)

    val transferNotifications: TransferNotificationManager = TransferNotificationManager(appContext)
    val transferScheduler: TransferScheduler = TransferScheduler(appContext, upload)
    val pipeline: UploadPipeline = UploadPipeline(
        queue = upload,
        staging = staging,
        downloadClient = downloadClient,
        apiFor = { snapshot -> GrimmoryApi(httpClient, serverUrl = { ServerUrl.parse(snapshot) }) },
        cleartextConfirmed = { url -> settings.isCleartextConfirmed(url) },
    )
    val pendingJobReconciler: PendingJobReconciler = PendingJobReconciler(upload, staging)
}
