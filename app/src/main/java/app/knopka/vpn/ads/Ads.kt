package app.knopka.vpn.ads

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Seam for an ad network (Yandex Mobile Ads, AdMob, AppLovin, ...).
 * To plug one in: implement this interface and assign it to [Ads.provider].
 */
interface AdProvider {
    fun init(context: Context) {}

    /** Banner at the bottom of the main screen. */
    @Composable
    fun Banner(modifier: Modifier)

    /** Full-screen ad after a successful connect. Must call [onDone] in every case. */
    fun showInterstitial(activity: Activity, onDone: () -> Unit) = onDone()
}

object Ads {
    var provider: AdProvider = PlaceholderAds

    /** At most one interstitial per this interval, so ads don't drive users away. */
    private const val INTERSTITIAL_MIN_INTERVAL_MS = 10 * 60 * 1000L
    private var lastInterstitialAt = 0L

    fun maybeShowInterstitial(activity: Activity, onDone: () -> Unit = {}) {
        val now = System.currentTimeMillis()
        if (now - lastInterstitialAt < INTERSTITIAL_MIN_INTERVAL_MS) return onDone()
        lastInterstitialAt = now
        provider.showInterstitial(activity, onDone)
    }
}

/** Shows where the banner will be; replace with a real network before release. */
object PlaceholderAds : AdProvider {
    @Composable
    override fun Banner(modifier: Modifier) {
        Box(
            modifier
                .fillMaxWidth()
                .height(56.dp)
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("Место для рекламы", color = Color(0x66FFFFFF), fontSize = 13.sp)
        }
    }
}
