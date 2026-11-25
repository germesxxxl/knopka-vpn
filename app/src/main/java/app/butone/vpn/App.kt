package app.butone.vpn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import app.butone.vpn.diagnostics.EventJournal

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val journal = EventJournal(this)
        journal.record("app_start", "Приложение запущено", mapOf("version" to BuildConfig.VERSION_NAME))
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            journal.record(
                "crash",
                "Сбой приложения",
                mapOf("exception" to error.javaClass.simpleName),
            )
            previousHandler?.uncaughtException(thread, error)
        }
    }

    companion object {
        const val CHANNEL_ID = "vpn"
    }
}
