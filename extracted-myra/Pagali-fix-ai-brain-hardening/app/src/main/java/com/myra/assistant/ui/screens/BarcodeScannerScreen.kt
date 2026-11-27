package com.myra.assistant.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.myra.assistant.ui.theme.MyraBorderSubtle
import com.myra.assistant.ui.theme.MyraGreen
import com.myra.assistant.ui.theme.MyraRed
import com.myra.assistant.ui.theme.MyraSurface
import com.myra.assistant.ui.theme.MyraTextPrimary
import com.myra.assistant.ui.theme.MyraTextSecondary
import com.myra.assistant.ui.theme.MyraVoid
import java.util.concurrent.Executors

@OptIn(ExperimentalGetImage::class)
@Composable
fun BarcodeScannerScreen(
    onBack: () -> Unit,
    onUseResultInMyra: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var scannedValue by remember { mutableStateOf<String?>(null) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
    }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val barcodeScanner = remember { BarcodeScanning.getClient() }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            barcodeScanner.close()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MyraVoid)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(48.dp).testTag("scanner_back_button"),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MyraTextPrimary)
            }
            Text("CAMERA & BARCODE SCANNER", style = MaterialTheme.typography.titleLarge, color = MyraRed)
        }

        if (!hasCameraPermission) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CameraAlt, contentDescription = "Camera Required", tint = MyraRed, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Camera permission is required to scan QR codes and barcodes.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MyraTextPrimary,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                        colors = ButtonDefaults.buttonColors(containerColor = MyraRed),
                        modifier = Modifier.heightIn(min = 48.dp).testTag("grant_camera_button"),
                    ) {
                        Text("Grant Camera Permission")
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .border(2.dp, MyraRed.copy(alpha = 0.7f), RoundedCornerShape(20.dp)),
            ) {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener(
                            {
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.surfaceProvider = previewView.surfaceProvider
                                }
                                val analysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                    val mediaImage = imageProxy.image
                                    if (mediaImage != null) {
                                        val inputImg = InputImage.fromMediaImage(
                                            mediaImage,
                                            imageProxy.imageInfo.rotationDegrees,
                                        )
                                        barcodeScanner.process(inputImg)
                                            .addOnSuccessListener { barcodes ->
                                                val value = barcodes.firstOrNull()?.rawValue
                                                if (!value.isNullOrBlank()) {
                                                    scannedValue = value
                                                }
                                            }
                                            .addOnCompleteListener {
                                                imageProxy.close()
                                            }
                                    } else {
                                        imageProxy.close()
                                    }
                                }

                                runCatching {
                                    cameraProvider.unbindAll()
                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview,
                                        analysis,
                                    )
                                }
                            },
                            ContextCompat.getMainExecutor(ctx),
                        )
                        previewView
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MyraSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .border(1.dp, MyraBorderSubtle, RoundedCornerShape(16.dp)),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    val result = scannedValue
                    if (result == null) {
                        Text("Point camera at a QR code or barcode...", color = MyraTextSecondary)
                    } else {
                        Text("DETECTED:", style = MaterialTheme.typography.labelSmall, color = MyraGreen)
                        Spacer(Modifier.height(4.dp))
                        Text(result, style = MaterialTheme.typography.bodyLarge, color = MyraTextPrimary)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    onUseResultInMyra(result)
                                    onBack()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MyraRed),
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("ask_myra_barcode_button"),
                            ) {
                                Text("Ask MYRA")
                            }
                            OutlinedButton(
                                onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("Barcode", result))
                                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                            }
                            if (result.startsWith("http://") || result.startsWith("https://")) {
                                OutlinedButton(
                                    onClick = {
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result)))
                                        }
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Icon(Icons.Default.OpenInBrowser, contentDescription = "Open URL")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
