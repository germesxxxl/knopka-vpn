package app.butone.vpn.ads

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.currentStateAsState
import app.butone.vpn.BuildConfig
import app.butone.vpn.vpn.VpnState
import app.butone.vpn.vpn.VpnStatus
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.delay

fun createAds(activity: ComponentActivity): ScreenAds =
    if (BuildConfig.ADS_ENABLED) AdMobScreenAds(activity) else object : ScreenAds {}

private class AdMobScreenAds(private val activity: ComponentActivity) : ScreenAds {
    private val prefs = activity.getSharedPreferences("ad_preferences", Context.MODE_PRIVATE)
    private val consent = UserMessagingPlatform.getConsentInformation(activity)
    private var showing by mutableStateOf(false)
    private var adFreeUntil by mutableStateOf(prefs.getLong("ad_free_until", 0))
    private var requestAllowed by mutableStateOf(false)
    private var privacyRequired by mutableStateOf(false)
    private var initializing = false
    private var adsReady by mutableStateOf(false)
    private var reward by mutableStateOf<RewardedAd?>(null)
    private var loadedAt = 0L
    private var rewardLoading = false
    private var nextRewardRequest = 0L
    private var consentStarted = false

    @Composable
    override fun Content(eligible: Boolean) {
        val life by activity.lifecycle.currentStateAsState()
        var directNetwork by remember { mutableStateOf(false) }
        DisposableEffect(Unit) {
            val manager = activity.getSystemService(ConnectivityManager::class.java)
            fun update() {
                activity.runOnUiThread { directNetwork = hasDirectNetwork() }
            }
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update()
                override fun onLost(network: Network) = update()
            }
            val registered = runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess
            update()
            onDispose {
                if (registered) runCatching { manager.unregisterNetworkCallback(callback) }
            }
        }
        var idle by remember { mutableStateOf(false) }
        val mayStart = eligible && life == Lifecycle.State.RESUMED && directNetwork && !showing
        LaunchedEffect(mayStart) {
            idle = false
            if (mayStart) {
                delay(2_000)
                if (canUseNetwork()) {
                    idle = true
                    requestConsent()
                }
            }
        }
        val rewarded = adFreeUntil > 0
        LaunchedEffect(adFreeUntil) {
            if (rewarded) {
                delay((adFreeUntil - System.currentTimeMillis()).coerceAtLeast(0))
                adFreeUntil = 0
            }
        }
        val canShow = idle && mayStart && adsReady && requestAllowed && !rewarded && canUseNetwork()
        LaunchedEffect(canShow) {
            if (canShow) {
                while (true) {
                    if (reward != null && System.currentTimeMillis() - loadedAt >= REWARD_FRESH_MS) reward = null
                    loadReward()
                    delay(60_000)
                }
            }
        }
        if (mayStart && idle && rewarded) Text("Реклама отключена", modifier = Modifier.padding(8.dp))
        if (canShow) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (reward != null) {
                    TextButton(onClick = ::showReward) { Text("Смотреть видео · 24 часа без рекламы") }
                }
                AdaptiveBanner()
            }
        }
    }

    @Composable
    override fun PrivacyOptions() {
        if (privacyRequired && !BuildConfig.ADS_TEST) {
            TextButton(
                enabled = !showing,
                onClick = {
                    showing = true
                    requestAllowed = false
                    reward = null
                    UserMessagingPlatform.showPrivacyOptionsForm(activity) {
                        showing = false
                        updateConsentState()
                    }
                },
            ) { Text("Настройки рекламы") }
        }
    }

    private fun requestConsent() {
        if (consentStarted) return
        consentStarted = true
        if (BuildConfig.ADS_TEST) {
            updateConsentState()
            return
        }
        consent.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                if (canUseNetwork()) {
                    UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { updateConsentState() }
                } else {
                    consentStarted = false
                }
            },
            {
                updateConsentState()
                if (!requestAllowed) consentStarted = false
            },
        )
    }

    private fun updateConsentState() {
        requestAllowed = BuildConfig.ADS_TEST || consent.canRequestAds()
        privacyRequired = consent.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        if (requestAllowed && canUseNetwork() && !initializing) {
            initializing = true
            MobileAds.initialize(activity.applicationContext) {
                activity.runOnUiThread { adsReady = true }
            }
        } else if (requestAllowed && !initializing) {
            consentStarted = false
        }
    }

    private fun canUseNetwork(): Boolean {
        if (activity.isDestroyed || activity.isFinishing ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            VpnState.status.value != VpnStatus.Disconnected ||
            VpnState.apiSecret != null
        ) return false
        return hasDirectNetwork()
    }

    private fun hasDirectNetwork(): Boolean {
        val connectivity = activity.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }

    private fun loadReward() {
        val now = System.currentTimeMillis()
        if (rewardLoading || reward != null || now < nextRewardRequest || !canUseNetwork()) return
        rewardLoading = true
        nextRewardRequest = now + 60_000
        RewardedAd.load(activity, BuildConfig.AD_REWARDED_ID, AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardLoading = false
                    if (canUseNetwork() && requestAllowed && !showing) {
                        loadedAt = System.currentTimeMillis()
                        reward = ad
                    }
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardLoading = false
                }
            },
        )
    }

    private fun showReward() {
        if (!requestAllowed || showing || !canUseNetwork()) return
        val ad = reward ?: return
        reward = null
        if (System.currentTimeMillis() - loadedAt >= REWARD_FRESH_MS) return
        showing = true
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() { showing = false }
            override fun onAdFailedToShowFullScreenContent(error: AdError) { showing = false }
        }
        ad.show(activity) {
            adFreeUntil = System.currentTimeMillis() + AD_FREE_MS
            prefs.edit().putLong("ad_free_until", adFreeUntil).apply()
        }
    }

    @Composable
    private fun AdaptiveBanner() {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val width = maxWidth.value.toInt()
            if (width < 200) return@BoxWithConstraints
            val size = remember(width) {
                AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, width)
            }
            var loaded by remember(width) { mutableStateOf<AdView?>(null) }
            DisposableEffect(width) {
                val view = AdView(activity)
                var disposed = false
                view.adUnitId = BuildConfig.AD_BANNER_ID
                view.setAdSize(size)
                view.adListener = object : AdListener() {
                    override fun onAdLoaded() {
                        if (!disposed && canUseNetwork()) loaded = view
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        if (!disposed) loaded = null
                    }
                }
                if (canUseNetwork()) view.loadAd(AdRequest.Builder().build())
                onDispose {
                    disposed = true
                    view.destroy()
                }
            }
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxWidth().padding(top = 12.dp).height(size.height.coerceAtLeast(0).dp),
                contentAlignment = Alignment.Center,
            ) {
                loaded?.let { banner -> AndroidView(factory = { banner }) }
            }
        }
    }

    companion object {
        private const val AD_FREE_MS = 24 * 60 * 60 * 1_000L
        private const val REWARD_FRESH_MS = 50 * 60 * 1_000L
    }
}
