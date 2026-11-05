package app.knopka.vpn.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.knopka.vpn.ads.Ads
import app.knopka.vpn.vpn.KnopkaVpnService
import app.knopka.vpn.vpn.VpnState
import app.knopka.vpn.vpn.VpnStatus

class MainActivity : ComponentActivity() {

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) connect()
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("app", Context.MODE_PRIVATE)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
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
            KnopkaVpnService.stop(this)
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
        KnopkaVpnService.start(this)
        Ads.maybeShowInterstitial(this)
    }
}

private val Bg = Color(0xFF101418)
private val Off = Color(0xFF3A4048)
private val Busy = Color(0xFFE0A100)
private val On = Color(0xFF1E9E5A)
private val Bad = Color(0xFFC0392B)

@Composable
private fun MainScreen(status: VpnStatus, onToggle: () -> Unit) {
    val target = when (status) {
        VpnStatus.Disconnected -> Off
        VpnStatus.Connecting -> Busy
        is VpnStatus.Connected -> if (status.node == null) Busy else On
        is VpnStatus.Error -> Bad
    }
    val color by animateColorAsState(target, tween(400), label = "button")

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .systemBarsPadding()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Кнопка VPN", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)

        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(220.dp)
                    .clip(CircleShape)
                    .background(color)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onToggle,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                val busy = status is VpnStatus.Connecting ||
                    (status is VpnStatus.Connected && status.node == null)
                if (busy) {
                    CircularProgressIndicator(Modifier.size(64.dp), color = Color.White, strokeWidth = 5.dp)
                } else {
                    Text(
                        if (status is VpnStatus.Connected) "ВКЛ" else "ВЫКЛ",
                        color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
            StatusText(status)
        }

        Ads.provider.Banner(Modifier)
    }
}

@Composable
private fun StatusText(status: VpnStatus) {
    val (title, sub) = when (status) {
        VpnStatus.Disconnected -> "Не подключено" to "Нажмите на кнопку"
        VpnStatus.Connecting -> "Подключение…" to ""
        is VpnStatus.Connected ->
            if (status.node == null) "Подбираем лучший сервер…" to
                (if (status.total > 0) "Проверяем ${status.total} серверов" else "")
            else "Подключено" to "${status.node} · ${status.delayMs} мс\nРабочих серверов: ${status.alive}"
        is VpnStatus.Error -> "Не удалось подключиться" to status.message
    }
    Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    if (sub.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(sub, color = Color(0xFFB0B8C0), fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Disclaimer(onAccept: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = { TextButton(onClick = onAccept) { Text("Понятно") } },
        title = { Text("Перед началом") },
        text = {
            Text(
                "Приложение подключается к бесплатным публичным серверам из открытых списков. " +
                    "Мы не владеем ими и не можем гарантировать их скорость и надёжность.\n\n" +
                    "Российские сайты (банки, Госуслуги, маркетплейсы) открываются напрямую, " +
                    "мимо этих серверов.\n\n" +
                    "Не вводите пароли на сайтах без значка замка и не устанавливайте " +
                    "сертификаты по чьей-либо просьбе."
            )
        },
    )
}
