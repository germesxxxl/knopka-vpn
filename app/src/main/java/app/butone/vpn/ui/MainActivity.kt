package app.butone.vpn.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.butone.vpn.ads.Ads
import app.butone.vpn.vpn.ButoneVpnService
import app.butone.vpn.vpn.VpnState

class MainActivity : ComponentActivity() {

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) connect()
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("app", Context.MODE_PRIVATE)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Palette.Green)) {
                var showDisclaimer by remember { mutableStateOf(!prefs.getBoolean("disclaimer_ok", false)) }
                val status by VpnState.status.collectAsStateWithLifecycle()

                MainScreen(status = status, onToggle = { toggle() })

                if (showDisclaimer) {
                    Disclaimer(onAccept = {
                        prefs.edit().putBoolean("disclaimer_ok", true).apply()
                        showDisclaimer = false
                    })
                }
            }
        }
    }

    private fun toggle() {
        if (VpnState.isActive) {
            ButoneVpnService.stop(this)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val prepare = VpnService.prepare(this)
        if (prepare != null) vpnPermission.launch(prepare) else connect()
    }

    private fun connect() {
        ButoneVpnService.start(this)
        Ads.maybeShowInterstitial(this)
    }
}
