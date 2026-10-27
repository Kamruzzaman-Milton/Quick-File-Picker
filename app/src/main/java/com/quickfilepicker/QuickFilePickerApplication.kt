package com.quickfilepicker

import android.app.Application
import com.quickfilepicker.data.db.AppDatabase
import com.quickfilepicker.data.repository.ShortcutRepository

class QuickFilePickerApplication : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var shortcutRepository: ShortcutRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = AppDatabase.getInstance(this)
        shortcutRepository = ShortcutRepository(
            this,
            database.shortcutDao(),
            database.recentFolderDao(),
            database.favoriteItemDao()
        )

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "app_launcher_channel",
                "App Shortcut Launcher",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Quick launch notifications for 3rd-party file managers"
            }
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    companion object {
        lateinit var instance: QuickFilePickerApplication
            private set
        
        fun getRepository(): ShortcutRepository {
            return instance.shortcutRepository
        }
    }
}
