package app.knopka.vpn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import app.knopka.vpn.ads.Ads

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        Ads.provider.init(this)
    }

    companion object {
        const val CHANNEL_ID = "vpn"
    }
}
