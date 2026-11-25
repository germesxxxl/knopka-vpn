package app.butone.vpn.ads

import androidx.compose.runtime.Composable

interface ScreenAds {
    @Composable
    fun Content(eligible: Boolean) {}

    @Composable
    fun PrivacyOptions() {}
}
