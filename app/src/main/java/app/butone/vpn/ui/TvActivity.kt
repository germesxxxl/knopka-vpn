package app.butone.vpn.ui

import android.app.Activity
import android.content.Context
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.butone.vpn.vpn.ButoneVpnService
import app.butone.vpn.vpn.VpnState
import app.butone.vpn.vpn.VpnStatus
import kotlinx.coroutines.delay

/** Ten-foot interface used only by Android TV / Google TV launchers. */
class TvActivity : ComponentActivity() {
    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) ButoneVpnService.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("app", Context.MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Palette.Green)) {
                val status by VpnState.status.collectAsStateWithLifecycle()
                var accepted by remember { mutableStateOf(prefs.getBoolean("disclaimer_ok", false)) }
                if (accepted) {
                    TvScreen(status = status, onToggle = ::toggle)
                } else {
                    TvDisclaimer {
                        prefs.edit().putBoolean("disclaimer_ok", true).apply()
                        accepted = true
                    }
                }
            }
        }
    }

    private fun toggle() {
        if (VpnState.isActive) {
            ButoneVpnService.stop(this)
            return
        }
        val prepare = VpnService.prepare(this)
        if (prepare != null) vpnPermission.launch(prepare) else ButoneVpnService.start(this)
    }
}

@Composable
private fun TvScreen(status: VpnStatus, onToggle: () -> Unit) {
    val active = status is VpnStatus.Connecting || status is VpnStatus.Connected
    val ready = status is VpnStatus.Connected && status.node != null
    val error = status as? VpnStatus.Error
    val top by animateColorAsState(
        targetValue = when {
            error != null -> Color(0xFF4A1515)
            ready -> Color(0xFF07552D)
            active -> Color(0xFF49370D)
            else -> Color(0xFF18202A)
        },
        animationSpec = tween(600),
        label = "tvBackground",
    )
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(300)
        focusRequester.requestFocus()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.horizontalGradient(listOf(top, Color(0xFF05080C))))
            .padding(horizontal = 72.dp, vertical = 48.dp),
    ) {
        Text(
            "butone",
            color = Color.White,
            fontSize = 34.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
            modifier = Modifier.align(Alignment.TopStart),
        )

        Row(
            modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Button(
                onClick = onToggle,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (active) Color(0xFFDC3F3F) else Palette.Green,
                ),
                modifier = Modifier
                    .size(230.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused }
                    .scale(if (focused) 1.10f else 1f)
                    .border(
                        width = if (focused) 6.dp else 0.dp,
                        color = if (focused) Color.White else Color.Transparent,
                        shape = CircleShape,
                    ),
            ) {
                Text(
                    if (active) "ВЫКЛ" else "ВКЛ",
                    color = Color.White,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Black,
                )
            }

            Spacer(Modifier.width(64.dp))

            Column(
                modifier = Modifier.width(560.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = when {
                        error != null -> "Не удалось подключиться"
                        ready -> "VPN подключён"
                        active -> "Ищем рабочий сервер…"
                        else -> "VPN выключен"
                    },
                    color = Color.White,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(18.dp))
                val details = when (status) {
                    is VpnStatus.Connected -> status.node?.let { node ->
                        val parsed = parseNode(node)
                        buildString {
                            append(parsed.flag).append(' ').append(parsed.country)
                            if (parsed.city.isNotBlank()) append(" · ").append(parsed.city)
                            status.delayMs?.let { append("\n").append(it).append(" мс") }
                        }
                    } ?: "Проверяем ${status.total} серверов"
                    is VpnStatus.Error -> status.message
                    VpnStatus.Connecting -> "Переподключение выполняется автоматически"
                    VpnStatus.Disconnected -> "Нажмите центральную кнопку на пульте"
                }
                Text(details, color = Palette.TextDim, fontSize = 25.sp, lineHeight = 34.sp)
            }
        }

        Text(
            "Управление: стрелки пульта и центральная кнопка",
            color = Color(0xFF84918B),
            fontSize = 18.sp,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun TvDisclaimer(onAccept: () -> Unit) {
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(300)
        requester.requestFocus()
    }
    Box(
        Modifier.fillMaxSize().background(Color(0xFF080C11)).padding(80.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(900.dp)
                .background(Color(0xFF17202A), RoundedCornerShape(28.dp))
                .padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Перед началом", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))
            Text(
                "Приложение использует бесплатные публичные серверы. Их скорость и доступность могут меняться. " +
                    "Российские сайты открываются напрямую, минуя VPN-серверы.",
                color = Palette.TextDim,
                fontSize = 24.sp,
                lineHeight = 34.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(36.dp))
            Button(
                onClick = onAccept,
                modifier = Modifier
                    .focusRequester(requester)
                    .onFocusChanged { focused = it.isFocused }
                    .scale(if (focused) 1.08f else 1f)
                    .border(
                        if (focused) 4.dp else 0.dp,
                        if (focused) Color.White else Color.Transparent,
                        RoundedCornerShape(18.dp),
                    )
                    .padding(horizontal = 8.dp),
            ) {
                Text("ПРОДОЛЖИТЬ", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
