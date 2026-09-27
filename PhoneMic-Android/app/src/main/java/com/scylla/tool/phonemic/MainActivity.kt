package com.scylla.tool.phonemic

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.scylla.tool.phonemic.ui.theme.PhoneMicAccent
import com.scylla.tool.phonemic.ui.theme.PhoneMicAccent2
import com.scylla.tool.phonemic.ui.theme.PhoneMicCritical
import com.scylla.tool.phonemic.ui.theme.PhoneMicCriticalInk
import com.scylla.tool.phonemic.ui.theme.PhoneMicEdge
import com.scylla.tool.phonemic.ui.theme.PhoneMicGood
import com.scylla.tool.phonemic.ui.theme.PhoneMicInkFaint
import com.scylla.tool.phonemic.ui.theme.PhoneMicTheme
import com.scylla.tool.phonemic.ui.theme.PhoneMicWarn
import com.scylla.tool.phonemic.ui.theme.PlexMonoFamily

class MainActivity : ComponentActivity() {

    private var pendingStart: Pair<String, Int>? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        val pending = pendingStart
        pendingStart = null
        if (allGranted && pending != null) {
            beginStreaming(pending.first, pending.second)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhoneMicTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PhoneMicScreen(
                        onStartRequested = ::requestStart,
                        onStopRequested = ::stopStreaming
                    )
                }
            }
        }
    }

    private fun requestStart(host: String, port: Int) {
        Prefs.setHost(this, host)
        Prefs.setPort(this, port)

        val required = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            beginStreaming(host, port)
        } else {
            pendingStart = host to port
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun beginStreaming(host: String, port: Int) {
        val intent = Intent(this, MicStreamService::class.java).apply {
            action = MicStreamService.ACTION_START
            putExtra(MicStreamService.EXTRA_HOST, host)
            putExtra(MicStreamService.EXTRA_PORT, port)
            putExtra(MicStreamService.EXTRA_PIN, Prefs.getPin(this@MainActivity))
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopStreaming() {
        stopService(Intent(this, MicStreamService::class.java))
    }
}

private fun qualityLabel(quality: MicStreamService.Quality): String = when (quality) {
    MicStreamService.Quality.UNKNOWN -> "Connecting…"
    MicStreamService.Quality.EXCELLENT -> "Excellent"
    MicStreamService.Quality.GOOD -> "Good"
    MicStreamService.Quality.POOR -> "Poor"
    MicStreamService.Quality.LOST -> "Lost"
}

private fun qualityColor(quality: MicStreamService.Quality): Color = when (quality) {
    MicStreamService.Quality.UNKNOWN -> PhoneMicWarn
    MicStreamService.Quality.EXCELLENT, MicStreamService.Quality.GOOD -> PhoneMicGood
    MicStreamService.Quality.POOR -> PhoneMicWarn
    MicStreamService.Quality.LOST -> PhoneMicCritical
}

/** Soft radial glow blob — approximates the UI spec's `blur-3xl` decorative circles. */
@Composable
private fun GlowBlob(color: Color, alpha: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(
            Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent))
        )
    )
}

@Composable
fun PhoneMicScreen(
    onStartRequested: (host: String, port: Int) -> Unit,
    onStopRequested: () -> Unit
) {
    val context = LocalContext.current
    val state by MicStreamService.status.collectAsState()

    var hostText by remember { mutableStateOf(Prefs.getHost(context)) }
    var portText by remember { mutableStateOf(Prefs.getPort(context).toString()) }
    var hasSavedPairing by remember { mutableStateOf(Prefs.isPaired(context)) }
    var manualEntryExpanded by remember { mutableStateOf(!hasSavedPairing) }
    var validationError by remember { mutableStateOf<String?>(null) }

    var scenario by remember { mutableStateOf(Prefs.getScenario(context)) }
    var noiseReductionEnabled by remember { mutableStateOf(Prefs.isNoiseReductionEnabled(context)) }
    var monitoringEnabled by remember { mutableStateOf(Prefs.isMonitoringEnabled(context)) }
    var showHighGainWarning by remember { mutableStateOf(false) }
    var suppressHighGainNextTime by remember { mutableStateOf(false) }

    val qrLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            hostText = Prefs.getHost(context)
            portText = Prefs.getPort(context).toString()
            hasSavedPairing = Prefs.isPaired(context)
            manualEntryExpanded = false
            validationError = null
        }
    }

    fun attemptStart() {
        val port = portText.trim().toIntOrNull()
        val host = hostText.trim()
        validationError = when {
            host.isEmpty() -> "Enter your PC's IP address"
            port == null || port !in 1..65535 -> "Enter a valid port (1-65535)"
            else -> null
        }
        if (validationError == null) {
            onStartRequested(host, port!!)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GlowBlob(
            color = PhoneMicAccent2,
            alpha = 0.10f,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 64.dp, y = (-96).dp)
                .size(288.dp)
        )
        GlowBlob(
            color = PhoneMicAccent,
            alpha = 0.08f,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = (-80).dp, y = 160.dp)
                .size(256.dp)
        )

        Column(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(horizontal = 20.dp).padding(top = 56.dp)) {
                Text(text = "Phone Mic", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Stream this phone's microphone to your desktop over Wi-Fi.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 24.dp, bottom = 8.dp)
            ) {
                if (!state.isStreaming) {
                    if (hasSavedPairing && !manualEntryExpanded) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Computer,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = "$hostText:$portText",
                                            fontFamily = PlexMonoFamily,
                                            fontSize = 13.sp
                                        )
                                    }
                                    Pill(text = "Paired", color = PhoneMicGood)
                                }
                                Spacer(modifier = Modifier.height(14.dp))
                                HorizontalDivider(color = PhoneMicEdge)
                                Spacer(modifier = Modifier.height(14.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(
                                        onClick = { qrLauncher.launch(Intent(context, QrScanActivity::class.java)) },
                                        contentPadding = PaddingValues(0.dp),
                                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary)
                                    ) {
                                        Text("Scan QR", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                    }
                                    Text("•", color = PhoneMicInkFaint, fontSize = 12.sp)
                                    TextButton(
                                        onClick = { manualEntryExpanded = true },
                                        contentPadding = PaddingValues(0.dp),
                                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary)
                                    ) {
                                        Text("Enter manually", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(28.dp))
                    } else {
                        if (!hasSavedPairing) {
                            PillButton(
                                text = "Scan QR to pair",
                                ghost = true,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                onClick = { qrLauncher.launch(Intent(context, QrScanActivity::class.java)) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            TextButton(
                                onClick = { manualEntryExpanded = true },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary)
                            ) {
                                Text("Enter manually", fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        if (manualEntryExpanded) {
                            OutlinedTextField(
                                value = hostText,
                                onValueChange = { hostText = it },
                                label = { Text("PC IP address") },
                                placeholder = { Text("192.168.1.23") },
                                singleLine = true,
                                enabled = !state.isStreaming,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = portText,
                                onValueChange = { portText = it },
                                label = { Text("Port") },
                                singleLine = true,
                                enabled = !state.isStreaming,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (hasSavedPairing) {
                                Spacer(modifier = Modifier.height(4.dp))
                                TextButton(onClick = { manualEntryExpanded = false }) {
                                    Text("Hide")
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(28.dp))
                    }
                }

                Text(
                    text = "CAPTURE SCENARIO",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                SegmentedControl(
                    options = CaptureScenario.entries,
                    selected = scenario,
                    enabled = !state.isStreaming,
                    onSelected = {
                        scenario = it
                        Prefs.setScenario(context, it)
                    },
                    label = {
                        when (it) {
                            CaptureScenario.SPEAKER_NEARBY -> "Speaker nearby"
                            CaptureScenario.DISTANT_VOICE -> "Distant voice"
                            CaptureScenario.MUSIC -> "Music"
                        }
                    }
                )
                Spacer(modifier = Modifier.height(28.dp))

                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        ToggleRow(
                            label = "Noise reduction",
                            helper = "Filters background hiss and hum",
                            checked = noiseReductionEnabled,
                            enabled = !state.isStreaming,
                            onCheckedChange = {
                                noiseReductionEnabled = it
                                Prefs.setNoiseReductionEnabled(context, it)
                            }
                        )
                        HorizontalDivider(color = PhoneMicEdge)
                        ToggleRow(
                            label = "Hear yourself",
                            helper = "Monitor your mic through headphones",
                            checked = monitoringEnabled,
                            enabled = !state.isStreaming,
                            onCheckedChange = {
                                monitoringEnabled = it
                                Prefs.setMonitoringEnabled(context, it)
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                validationError?.let {
                    Text(text = it, color = MaterialTheme.colorScheme.error)
                }

                state.error?.let {
                    Text(text = "Error: $it", color = MaterialTheme.colorScheme.error)
                }

                if (state.isStreaming) {
                    Text(text = "Streaming to ${state.host}:${state.port}")
                    if (state.paired) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Pill(text = qualityLabel(state.quality), color = qualityColor(state.quality))
                    } else {
                        Text(text = "Waiting for PC…")
                    }
                    Text(text = "${state.packetsSent} packets sent")
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = "Mic level")
                    LinearProgressIndicator(
                        progress = { state.level },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                    )
                }
            }

            Column(modifier = Modifier.padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 34.dp)) {
                if (state.isStreaming || hasSavedPairing || manualEntryExpanded) {
                    PillButton(
                        text = if (state.isStreaming) "Stop Streaming" else "Start Streaming",
                        containerColor = if (state.isStreaming) PhoneMicCritical else MaterialTheme.colorScheme.primary,
                        contentColor = if (state.isStreaming) PhoneMicCriticalInk else MaterialTheme.colorScheme.onPrimary,
                        contentPadding = PaddingValues(vertical = 16.dp),
                        fontSize = 15.sp,
                        icon = {
                            Icon(
                                if (state.isStreaming) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        onClick = {
                            if (state.isStreaming) {
                                onStopRequested()
                            } else if (scenario == CaptureScenario.MUSIC && !Prefs.isHighGainWarningSuppressed(context)) {
                                showHighGainWarning = true
                            } else {
                                attemptStart()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(
                                elevation = if (state.isStreaming) 0.dp else 24.dp,
                                shape = MaterialTheme.shapes.large,
                                ambientColor = PhoneMicAccent.copy(alpha = 0.25f),
                                spotColor = PhoneMicAccent.copy(alpha = 0.25f)
                            )
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                if (!state.isStreaming && validationError == null && state.error == null) {
                    Text(
                        text = "●  Stopped",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = PlexMonoFamily,
                        color = PhoneMicInkFaint,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    if (showHighGainWarning) {
        AlertDialog(
            onDismissRequest = { showHighGainWarning = false },
            title = { Text("High gain selected") },
            text = {
                Column {
                    Text("Music capture skips voice noise reduction and can play back louder than expected. Use headphones on the PC side to avoid feedback.")
                    Spacer(modifier = Modifier.height(12.dp))
                    Row {
                        Checkbox(
                            checked = suppressHighGainNextTime,
                            onCheckedChange = { suppressHighGainNextTime = it }
                        )
                        Text(
                            text = "Don't warn me again",
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (suppressHighGainNextTime) {
                        Prefs.setHighGainWarningSuppressed(context, true)
                    }
                    showHighGainWarning = false
                    attemptStart()
                }) { Text("Start anyway") }
            },
            dismissButton = {
                TextButton(onClick = { showHighGainWarning = false }) { Text("Cancel") }
            }
        )
    }
}
