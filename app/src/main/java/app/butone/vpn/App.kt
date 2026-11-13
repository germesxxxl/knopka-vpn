package app.butone.vpn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import app.butone.vpn.ads.Ads
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
                "${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                mapOf("thread" to thread.name, "stack" to error.stackTrace.take(8).joinToString(" <- ")),
            )
            previousHandler?.uncaughtException(thread, error)
        }
        Ads.provider.init(this)
    }

    companion object {
        const val CHANNEL_ID = "vpn"
    }
}
