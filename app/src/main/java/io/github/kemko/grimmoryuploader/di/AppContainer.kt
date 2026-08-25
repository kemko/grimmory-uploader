package io.github.kemko.grimmoryuploader.di

import android.content.Context
import java.io.File
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.kemko.grimmoryuploader.data.auth.AuthInterceptor
import io.github.kemko.grimmoryuploader.data.auth.AuthRepository
import io.github.kemko.grimmoryuploader.data.auth.EncryptedTokenStore
import io.github.kemko.grimmoryuploader.data.auth.OidcCoordinator
import io.github.kemko.grimmoryuploader.data.auth.TokenStore
import io.github.kemko.grimmoryuploader.data.network.GrimmoryApi
import io.github.kemko.grimmoryuploader.data.settings.AppSettingsRepository
import okhttp3.OkHttpClient

interface UploadComponent

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsDataStore: DataStore<Preferences> =
        File(appContext.filesDir, "settings.preferences_pb")
            .let { file -> androidx.datastore.preferences.core.PreferenceDataStoreFactory.create { file } }

    val database: RoomDatabase = Room.databaseBuilder(
        appContext,
        FoundationDatabase::class.java,
        "grimmory.db",
    ).build()

    val tokenStore: TokenStore = EncryptedTokenStore(appContext)

    val settings: AppSettingsRepository = AppSettingsRepository(settingsDataStore) {
        tokenStore.clear()
    }

    private val rawHttpClient: OkHttpClient = OkHttpClient.Builder().build()

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

    val upload: UploadComponent = object : UploadComponent {}
}
