package com.oceanreader

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.oceanreader.data.BookImporter
import com.oceanreader.data.BookRepository
import com.oceanreader.data.SettingsStore
import com.oceanreader.data.db.AppDatabase
import com.oceanreader.tts.SpeechController
import com.oceanreader.tts.SpeechHolder

/**
 * Hand-rolled service locator: the app has a handful of long-lived objects, so
 * a DI framework would cost more than it saves.
 */
class AppContainer(private val application: Application) {

    val database: AppDatabase by lazy { AppDatabase.get(application) }
    val settings: SettingsStore by lazy { SettingsStore(application) }
    val importer: BookImporter by lazy { BookImporter(application, database, settings) }
    val repository: BookRepository by lazy {
        BookRepository(application, database, settings, importer)
    }
    val speech: SpeechController by lazy { SpeechController(application) }

    fun startSpeechService() = com.oceanreader.tts.SpeechService.start(application)

    companion object {
        fun from(context: android.content.Context): AppContainer {
            val app = context.applicationContext as ReaderApp
            return app.container
        }
    }
}

class ReaderApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        container = AppContainer(this)
        SpeechHolder.controller = container.speech
    }
}