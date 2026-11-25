package app.butone.vpn.vpn

import android.content.Context
import app.butone.vpn.BuildConfig

object VpnDisclosureConsent {
    const val PRIVACY_URL = "https://github.com/germesxxxl/knopka-vpn/blob/main/release/android/privacy.md"
    private const val KEY = "vpn_disclosure_v1"

    fun isAccepted(context: Context): Boolean =
        BuildConfig.IS_TV || context.getSharedPreferences("app", Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun accept(context: Context) {
        context.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
    }
}
