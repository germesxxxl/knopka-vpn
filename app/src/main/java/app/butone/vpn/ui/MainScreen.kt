package app.butone.vpn.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.butone.vpn.R
import app.butone.vpn.ads.Ads
import app.butone.vpn.vpn.VpnStatus
import kotlinx.coroutines.delay
import java.util.Locale

object Palette {
    val Green = Color(0xFF22C55E)
    val GreenDeep = Color(0xFF15803D)
    val GreenGlow = Color(0xFF4ADE80)
    val Amber = Color(0xFFF59E0B)
    val Red = Color(0xFFEF4444)
    val TextDim = Color(0xFFB6C2BC)
}

/** Visual phase of the screen; Connected-but-still-pinging counts as Searching. */
private enum class Phase { Off, Searching, On, Error }

private fun phaseOf(s: VpnStatus) = when (s) {
    VpnStatus.Disconnected -> Phase.Off
    VpnStatus.Connecting -> Phase.Searching
    is VpnStatus.Connected -> if (s.node == null) Phase.Searching else Phase.On
    is VpnStatus.Error -> Phase.Error
}

@Composable
fun MainScreen(status: VpnStatus, onToggle: () -> Unit, onReport: () -> Unit) {
    val phase = phaseOf(status)
    val anim = tween<Color>(700)
    val bgTop by animateColorAsState(
        when (phase) {
            Phase.Off -> Color(0xFF1B2028)
            Phase.Searching -> Color(0xFF2E2510)
            Phase.On -> Color(0xFF0C4A2A)
            Phase.Error -> Color(0xFF3F1616)
        }, anim, label = "bgTop",
    )
    val bgBottom by animateColorAsState(
        if (phase == Phase.On) Color(0xFF03160C) else Color(0xFF0A0D11), anim, label = "bgBottom",
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(bgTop, bgBottom)))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(
                    "butone",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
                TextButton(onClick = onReport, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Text("Отчёт", color = Palette.TextDim, fontSize = 13.sp)
                }
            }

            Spacer(Modifier.weight(1f))
            PowerButton(phase, onToggle)
            Spacer(Modifier.height(28.dp))

            Box(Modifier.height(190.dp), contentAlignment = Alignment.TopCenter) {
                Crossfade(targetState = phase, animationSpec = tween(400), label = "info") { p ->
                    Info(p, status)
                }
            }

            Spacer(Modifier.weight(1f))
            Ads.provider.Banner(Modifier)
        }
    }
}

@Composable
private fun PowerButton(phase: Phase, onToggle: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val t = rememberInfiniteTransition(label = "button")
    val breathe by t.animateFloat(
        1f, 1.045f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "breathe",
    )
    val wave by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "wave",
    )
    val spin by t.animateFloat(
        0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "spin",
    )

    val anim = tween<Color>(600)
    val c1 by animateColorAsState(
        when (phase) {
            Phase.Off -> Color(0xFF4B5563)
            Phase.Searching -> Color(0xFF6B5A2A)
            Phase.On -> Palette.GreenGlow
            Phase.Error -> Color(0xFFB91C1C)
        }, anim, label = "c1",
    )
    val c2 by animateColorAsState(
        when (phase) {
            Phase.Off -> Color(0xFF2B313B)
            Phase.Searching -> Color(0xFF3A3018)
            Phase.On -> Palette.GreenDeep
            Phase.Error -> Color(0xFF6B1111)
        }, anim, label = "c2",
    )

    Box(Modifier.size(310.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val base = 95.dp.toPx()
            when (phase) {
                // Rings expanding outward from the button while connected.
                Phase.On -> repeat(3) { i ->
                    val p = (wave + i / 3f) % 1f
                    drawCircle(
                        color = Palette.GreenGlow.copy(alpha = (1f - p) * 0.45f),
                        radius = base * (1f + p * 0.6f),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
                // Rotating arc while connecting / pinging servers.
                Phase.Searching -> {
                    val r = base + 14.dp.toPx()
                    drawArc(
                        color = Palette.Amber,
                        startAngle = spin,
                        sweepAngle = 110f,
                        useCenter = false,
                        topLeft = Offset(center.x - r, center.y - r),
                        size = Size(r * 2, r * 2),
                        style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
                else -> Unit
            }
        }

        val scale = if (phase == Phase.On) breathe else 1f
        Box(
            Modifier
                .size(190.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .shadow(
                    elevation = if (phase == Phase.On) 32.dp else 12.dp,
                    shape = CircleShape,
                    ambientColor = c1,
                    spotColor = c1,
                )
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(c1, c2)))
                .border(2.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggle()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_tile),
                contentDescription = if (phase == Phase.Off) "Включить VPN" else "Выключить VPN",
                tint = Color.White,
                modifier = Modifier.size(84.dp),
            )
        }
    }
}

@Composable
private fun Info(phase: Phase, status: VpnStatus) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (phase) {
            Phase.Off -> {
                Title("Нажмите, чтобы подключиться")
                Sub("Бесплатно · без регистрации")
            }
            Phase.Searching -> {
                Title("Подбираем лучший сервер…")
                val total = (status as? VpnStatus.Connected)?.total ?: 0
                Sub(if (total > 0) "Проверяем $total серверов" else "Подключаемся")
            }
            Phase.Error -> {
                Title("Не удалось подключиться")
                Sub((status as? VpnStatus.Error)?.message ?: "", maxWidth = true)
                Sub("Нажмите кнопку, чтобы попробовать ещё раз")
            }
            Phase.On -> {
                val s = status as? VpnStatus.Connected ?: return@Column
                val node = parseNode(s.node ?: "")
                Text(node.flag, fontSize = 44.sp)
                Text(node.country, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                if (node.city.isNotEmpty()) Sub(node.city)
                Spacer(Modifier.height(12.dp))
                s.delayMs?.let { ms ->
                    val (bars, word) = quality(ms)
                    Row(verticalAlignment = Alignment.Bottom) {
                        SignalBars(bars)
                        Spacer(Modifier.width(8.dp))
                        Text("$ms мс · $word", color = Color.White, fontSize = 16.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                s.since?.let { SessionTimer(it) }
            }
        }
    }
}

@Composable
private fun Title(text: String) =
    Text(text, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)

@Composable
private fun Sub(text: String, maxWidth: Boolean = false) {
    if (text.isEmpty()) return
    Spacer(Modifier.height(6.dp))
    Text(
        text,
        color = Palette.TextDim,
        fontSize = 15.sp,
        textAlign = TextAlign.Center,
        modifier = if (maxWidth) Modifier.padding(horizontal = 12.dp) else Modifier,
    )
}

@Composable
private fun SignalBars(level: Int) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (i in 1..4) {
            Box(
                Modifier
                    .width(5.dp)
                    .height((5 + i * 4).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i <= level) Palette.GreenGlow else Color.White.copy(alpha = 0.2f))
            )
        }
    }
}

@Composable
private fun SessionTimer(since: Long) {
    val now by produceState(System.currentTimeMillis(), since) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val sec = ((now - since) / 1000).coerceAtLeast(0)
    val text = "%02d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60)
    Text("⏱ $text в защите", color = Palette.TextDim, fontSize = 15.sp)
}

/** Unified-delay ping (includes the TLS handshake), so thresholds are generous. */
private fun quality(ms: Int): Pair<Int, String> = when {
    ms < 200 -> 4 to "отлично"
    ms < 400 -> 3 to "быстро"
    ms < 800 -> 2 to "нормально"
    else -> 1 to "медленно"
}

data class NodeLabel(val flag: String, val country: String, val city: String)

/** "🇩🇪 Germany, Frankfurt am Main" -> flag 🇩🇪, "Германия", "Frankfurt am Main". */
fun parseNode(pretty: String): NodeLabel {
    val cps = pretty.codePoints().toArray()
    val isRegional = { cp: Int -> cp in 0x1F1E6..0x1F1FF }
    if (cps.size >= 2 && isRegional(cps[0]) && isRegional(cps[1])) {
        val code = "${'A' + (cps[0] - 0x1F1E6)}${'A' + (cps[1] - 0x1F1E6)}"
        val rest = String(cps, 2, cps.size - 2).trim()
        @Suppress("DEPRECATION")
        val ru = Locale("", code).getDisplayCountry(Locale("ru"))
            .takeIf { it.isNotBlank() && it != code }
            ?: rest.substringBefore(",")
        return NodeLabel(
            flag = String(cps, 0, 2),
            country = ru.replaceFirstChar { it.uppercase() },
            city = rest.substringAfter(", ", "").trim(),
        )
    }
    return NodeLabel("🌐", pretty.substringBefore(",").ifBlank { "Сервер" }, pretty.substringAfter(", ", ""))
}

@Composable
fun ReportDialog(text: String?, onCopy: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onCopy, enabled = text != null) { Text("Скопировать") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        title = { Text("Отчёт для разработчика") },
        text = {
            Text(
                text ?: "Собираем…",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            )
        },
    )
}

@Composable
fun Disclaimer(onAccept: () -> Unit) {
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
