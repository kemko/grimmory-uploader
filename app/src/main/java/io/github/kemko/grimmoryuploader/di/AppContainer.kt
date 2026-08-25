package io.github.kemko.grimmoryuploader.di

import android.content.Context
import java.io.File
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.room.RoomDatabase
import okhttp3.OkHttpClient

interface AuthComponent

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

    val httpClient: OkHttpClient = OkHttpClient.Builder().build()

    val auth: AuthComponent = object : AuthComponent {}
    val upload: UploadComponent = object : UploadComponent {}
}
