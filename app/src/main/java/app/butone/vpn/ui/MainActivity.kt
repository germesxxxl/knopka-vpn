package app.butone.vpn.ui

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.butone.vpn.vpn.ButoneVpnService
import app.butone.vpn.vpn.VpnState
import java.io.File

class MainActivity : ComponentActivity() {

    private var pendingReportFile: File? = null

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) connect()
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val saveReport =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            val source = pendingReportFile
            pendingReportFile = null
            if (uri == null || source == null) return@registerForActivityResult
            runCatching {
                source.inputStream().use { input ->
                    contentResolver.openOutputStream(uri, "w")!!.use { output -> input.copyTo(output) }
                }
            }.onSuccess {
                Toast.makeText(this, "Отчёт сохранён", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this, "Не удалось сохранить отчёт", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("app", Context.MODE_PRIVATE)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Palette.Green)) {
                var showDisclaimer by remember { mutableStateOf(!prefs.getBoolean("disclaimer_ok", false)) }
                var showReport by remember { mutableStateOf(false) }
                var report by remember { mutableStateOf<Diagnostics.GeneratedReport?>(null) }
                var reportDays by remember { mutableStateOf<Int?>(7) }
                val scope = rememberCoroutineScope()
                val status by VpnState.status.collectAsStateWithLifecycle()

                MainScreen(
                    status = status,
                    onToggle = { toggle() },
                    onReport = {
                        showReport = true
                        report = null
                        scope.launch {
                            report = withContext(Dispatchers.IO) { Diagnostics.createReport(this@MainActivity, reportDays) }
                        }
                    },
                )

                if (showReport) {
                    ReportDialog(
                        text = report?.preview,
                        periodDays = reportDays,
                        onPeriod = { days ->
                            reportDays = days
                            report = null
                            scope.launch {
                                report = withContext(Dispatchers.IO) { Diagnostics.createReport(this@MainActivity, days) }
                            }
                        },
                        onShare = {
                            report?.file?.let(::shareReport)
                        },
                        onSave = {
                            report?.file?.let { file ->
                                pendingReportFile = file
                                saveReport.launch(file.name)
                            }
                        },
                        onDismiss = { showReport = false },
                    )
                }

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
    }

    private fun shareReport(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri("butone report", uri)
        startActivity(Intent.createChooser(intent, "Отправить отчёт"))
    }
}
