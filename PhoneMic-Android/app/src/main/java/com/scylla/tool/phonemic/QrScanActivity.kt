package com.scylla.tool.phonemic

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.scylla.tool.phonemic.ui.theme.PhoneMicAccent2
import com.scylla.tool.phonemic.ui.theme.PhoneMicBg
import com.scylla.tool.phonemic.ui.theme.PhoneMicEdge
import com.scylla.tool.phonemic.ui.theme.PhoneMicInk
import com.scylla.tool.phonemic.ui.theme.PhoneMicTheme

/**
 * Scans the QR the PC receiver displays (payload `host:port:pin`) or accepts the same
 * three values typed by hand when scanning isn't possible. There's no discovery/relay
 * server in this app - a bare numeric code can't resolve to a host on its own, so the
 * manual fallback asks for host+port+pin, not just a short code.
 */
class QrScanActivity : ComponentActivity() {

    private var onPermissionResult: ((Boolean) -> Unit)? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> onPermissionResult?.invoke(granted) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhoneMicTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    QrScanScreen(
                        hasCameraPermission = {
                            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                                PackageManager.PERMISSION_GRANTED
                        },
                        requestCameraPermission = { callback ->
                            onPermissionResult = callback
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                        onPaired = { host, port, pin ->
                            Prefs.setHost(this, host)
                            Prefs.setPort(this, port)
                            Prefs.setPin(this, pin)
                            setResult(RESULT_OK)
                            finish()
                        },
                        onCancel = { finish() }
                    )
                }
            }
        }
    }
}

/** Parses the PC receiver's QR payload: `host:port:pin`. Returns null if malformed. */
fun parsePairingCode(raw: String): Triple<String, Int, String>? {
    val parts = raw.trim().split(":")
    if (parts.size != 3) return null
    val host = parts[0].trim()
    val port = parts[1].trim().toIntOrNull()
    val pin = parts[2].trim()
    if (host.isEmpty() || port == null || port !in 1..65535 || pin.isEmpty()) return null
    return Triple(host, port, pin)
}

@Composable
private fun QrScanScreen(
    hasCameraPermission: () -> Boolean,
    requestCameraPermission: ((Boolean) -> Unit) -> Unit,
    onPaired: (host: String, port: Int, pin: String) -> Unit,
    onCancel: () -> Unit
) {
    var manualMode by remember { mutableStateOf(false) }
    var permissionGranted by remember { mutableStateOf(hasCameraPermission()) }
    var scanError by remember { mutableStateOf<String?>(null) }

    var hostText by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("") }
    var pinText by remember { mutableStateOf("") }
    var manualError by remember { mutableStateOf<String?>(null) }

    if (!manualMode) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (permissionGranted) {
                CameraPreview(
                    modifier = Modifier.fillMaxSize(),
                    onCodeScanned = { raw ->
                        val parsed = parsePairingCode(raw)
                        if (parsed != null) {
                            scanError = null
                            onPaired(parsed.first, parsed.second, parsed.third)
                        } else {
                            scanError = "That's not a Phone Mic code, try again"
                        }
                    }
                )
            } else {
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            }

            Column(
                modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (!permissionGranted) {
                    Text(
                        text = "Camera permission is needed to scan the code.",
                        color = PhoneMicInk,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.size(12.dp))
                    PillButton(
                        text = "Grant camera access",
                        onClick = { requestCameraPermission { permissionGranted = it } }
                    )
                } else {
                    ViewfinderCorners(modifier = Modifier.size(256.dp))
                    Spacer(modifier = Modifier.size(20.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = PhoneMicBg.copy(alpha = 0.85f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, PhoneMicEdge)
                    ) {
                        Text(
                            text = "Point the camera at the code shown on your desktop",
                            color = PhoneMicInk,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                    scanError?.let {
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(text = it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 42.dp, top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = PhoneMicBg.copy(alpha = 0.85f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, PhoneMicEdge),
                    modifier = Modifier.size(52.dp)
                ) {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = PhoneMicInk)
                    }
                }
                PillButton(
                    text = "Enter code manually",
                    ghost = true,
                    containerColor = PhoneMicBg.copy(alpha = 0.85f),
                    contentColor = MaterialTheme.colorScheme.secondary,
                    onClick = { manualMode = true },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TextButton(
                onClick = { manualMode = false },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("← Back to scanning", fontWeight = FontWeight.SemiBold)
            }
            OutlinedTextField(
                value = hostText,
                onValueChange = { hostText = it },
                label = { Text("PC IP address") },
                placeholder = { Text("192.168.1.42") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it },
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = pinText,
                onValueChange = { pinText = it },
                label = { Text("Pairing code") },
                placeholder = { Text("4792-8163") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            manualError?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }
            PillButton(
                text = "Pair",
                onClick = {
                    val port = portText.trim().toIntOrNull()
                    manualError = when {
                        hostText.trim().isEmpty() -> "Enter your PC's IP address"
                        port == null || port !in 1..65535 -> "Enter a valid port (1-65535)"
                        pinText.trim().isEmpty() -> "Enter the pairing code shown on your PC"
                        else -> null
                    }
                    if (manualError == null) {
                        onPaired(hostText.trim(), port!!, pinText.trim())
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraPreview(modifier: Modifier = Modifier, onCodeScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val scanner = remember {
        BarcodeScanning.getClient()
    }

    DisposableEffect(lifecycleOwner) {
        var handled = false
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysisUseCase ->
                    analysisUseCase.setAnalyzer(ContextCompat.getMainExecutor(context)) { imageProxy: ImageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage == null || handled) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                        scanner.process(image)
                            .addOnSuccessListener { barcodes ->
                                val value = barcodes.firstOrNull()?.rawValue
                                if (!handled && value != null) {
                                    handled = true
                                    onCodeScanned(value)
                                }
                            }
                            .addOnCompleteListener { imageProxy.close() }
                    }
                }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) {
                // camera bind failed (e.g. no camera hardware) - user can still use manual entry
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            if (providerFuture.isDone) providerFuture.get()?.unbindAll()
            scanner.close()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/** Four L-shaped corner brackets marking the QR capture area, drawn over the live camera feed. */
@Composable
private fun ViewfinderCorners(
    modifier: Modifier = Modifier,
    cornerLength: androidx.compose.ui.unit.Dp = 32.dp,
    strokeWidth: androidx.compose.ui.unit.Dp = 4.dp,
    color: Color = PhoneMicAccent2
) {
    Canvas(modifier = modifier) {
        val stroke = strokeWidth.toPx()
        val len = cornerLength.toPx()
        val w = size.width
        val h = size.height
        val cap = androidx.compose.ui.graphics.StrokeCap.Round

        drawLine(color, Offset(0f, 0f), Offset(len, 0f), stroke, cap)
        drawLine(color, Offset(0f, 0f), Offset(0f, len), stroke, cap)

        drawLine(color, Offset(w - len, 0f), Offset(w, 0f), stroke, cap)
        drawLine(color, Offset(w, 0f), Offset(w, len), stroke, cap)

        drawLine(color, Offset(0f, h - len), Offset(0f, h), stroke, cap)
        drawLine(color, Offset(0f, h), Offset(len, h), stroke, cap)

        drawLine(color, Offset(w - len, h), Offset(w, h), stroke, cap)
        drawLine(color, Offset(w, h - len), Offset(w, h), stroke, cap)
    }
}
