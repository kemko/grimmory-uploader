package io.github.kemko.grimmoryuploader.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.kemko.grimmoryuploader.data.auth.AesGcmTokenCipher
import io.github.kemko.grimmoryuploader.data.auth.AuthInterceptor
import io.github.kemko.grimmoryuploader.data.auth.AuthRepository
import io.github.kemko.grimmoryuploader.data.auth.EncryptedTokenStore
import io.github.kemko.grimmoryuploader.data.auth.OidcCoordinator
import io.github.kemko.grimmoryuploader.data.network.GrimmoryApi
import io.github.kemko.grimmoryuploader.data.network.PublicSettings
import io.github.kemko.grimmoryuploader.data.network.ServerUrl
import io.github.kemko.grimmoryuploader.data.settings.AppSettingsRepository
import io.github.kemko.grimmoryuploader.upload.PendingJobReconciler
import io.github.kemko.grimmoryuploader.upload.StagingStore
import io.github.kemko.grimmoryuploader.upload.TransferNotificationManager
import io.github.kemko.grimmoryuploader.upload.TransferScheduler
import io.github.kemko.grimmoryuploader.upload.UploadPipeline
import io.github.kemko.grimmoryuploader.upload.UploadQueueRepository
import io.github.kemko.grimmoryuploader.upload.db.UploadDatabase
import okhttp3.OkHttpClient
import java.io.File

class AppContainer(
    context: Context,
    tokenCipher: AesGcmTokenCipher? = null,
) {
    private val appContext = context.applicationContext
    private var authForSettings: AuthRepository? = null

    val settingsDataStore: DataStore<Preferences> =
        File(appContext.filesDir, "settings.preferences_pb")
            .let { file ->
                androidx.datastore.preferences.core.PreferenceDataStoreFactory
                    .create { file }
            }

    val database: UploadDatabase = UploadDatabase.create(appContext)
    val staging: StagingStore = StagingStore(File(appContext.noBackupFilesDir, "pending"))

    val tokenStore = EncryptedTokenStore(appContext, tokenCipher = tokenCipher)

    val settings: AppSettingsRepository =
        AppSettingsRepository(settingsDataStore) {
            checkNotNull(authForSettings).invalidateForServerChange()
            tokenStore.clearPendingOidc()
        }

    private val rawHttpClient: OkHttpClient = OkHttpClient.Builder().build()
    private val authHttpClient: OkHttpClient = OkHttpClient.Builder().build()

    val downloadClient: OkHttpClient =
        rawHttpClient
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

    private val rawApi: GrimmoryApi =
        GrimmoryApi(authHttpClient, serverUrl = {
            settings.requireCleartextConfirmation()
            settings.requireServerUrl()
        })

    val auth: AuthRepository =
        AuthRepository(
            rawApi,
            tokenStore,
            currentServerUrl = { settings.requireServerUrl().normalized },
        ).also { authForSettings = it }

    val onboardingProbe: suspend (ServerUrl) -> PublicSettings? = { server ->
        val candidate = GrimmoryApi(rawHttpClient, serverUrl = { server })
        candidate.healthcheck()
        runCatching { candidate.publicSettings() }.getOrNull()
    }

    val httpClient: OkHttpClient =
        rawHttpClient
            .newBuilder()
            .addInterceptor(
                AuthInterceptor(auth) {
                    settings.current().serverUrl?.let(ServerUrl::parse)
                },
            ).build()

    val api: GrimmoryApi =
        GrimmoryApi(httpClient, serverUrl = {
            settings.requireCleartextConfirmation()
            settings.requireServerUrl()
        })

    val oidc: OidcCoordinator = OidcCoordinator(appContext, rawApi, auth, tokenStore)

    val upload: UploadQueueRepository = UploadQueueRepository(database.jobs(), staging)

    val transferNotifications: TransferNotificationManager = TransferNotificationManager(appContext)
    val transferScheduler: TransferScheduler = TransferScheduler(appContext, upload, transferNotifications::cancel)
    val pipeline: UploadPipeline =
        UploadPipeline(
            queue = upload,
            staging = staging,
            downloadClient = downloadClient,
            apiFor = { snapshot -> GrimmoryApi(httpClient, serverUrl = { ServerUrl.parse(snapshot) }) },
        )
    val pendingJobReconciler: PendingJobReconciler =
        PendingJobReconciler(
            upload,
            staging,
        )
}
