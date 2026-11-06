package app.butone.vpn.ads

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Seam for an ad network (Yandex Mobile Ads, AdMob, AppLovin, ...).
 * To plug one in: implement this interface and assign it to [Ads.provider].
 */
interface AdProvider {
    fun init(context: Context) {}

    /** Banner at the bottom of the main screen. */
    @Composable
    fun Banner(modifier: Modifier)

    /** Full-screen ad only at a natural pause, never while connecting. */
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

/** No ad is shown until a moderated network and a real placement ID are configured. */
object PlaceholderAds : AdProvider {
    @Composable
    override fun Banner(modifier: Modifier) = Unit
}
