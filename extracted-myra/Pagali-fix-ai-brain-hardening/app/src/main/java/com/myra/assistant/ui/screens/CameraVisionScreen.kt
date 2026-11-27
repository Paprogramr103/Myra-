package com.myra.assistant.ui.screens

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.myra.assistant.ui.theme.MyraCyan
import com.myra.assistant.ui.theme.MyraTextPrimary
import com.myra.assistant.ui.theme.MyraTextSecondary
import com.myra.assistant.ui.theme.MyraVoid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.min

@Composable
fun CameraVisionScreen(
    isGeminiConnected: Boolean,
    onBack: () -> Unit,
    onConnectGemini: () -> Unit,
    onAnalyzeImage: (ByteArray) -> Unit,
    onRequestCameraPermission: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = remember(context) {
        var candidate: Context? = context
        while (candidate != null && candidate !is LifecycleOwner) {
            candidate = (candidate as? ContextWrapper)?.baseContext
        }
        candidate as? LifecycleOwner
    }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var isCameraReady by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner) {
        val owner = lifecycleOwner ?: return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(hasPermission, lifecycleOwner) {
        if (!hasPermission || lifecycleOwner == null) return@LaunchedEffect
        try {
            val future = ProcessCameraProvider.getInstance(context)
            val provider = withContext(Dispatchers.IO) { future.get() }
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetResolution(Size(1280, 720))
                .setJpegQuality(82)
                .build()
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                capture,
            )
            cameraProvider = provider
            imageCapture = capture
            isCameraReady = true
            errorText = null
        } catch (error: Exception) {
            isCameraReady = false
            errorText = error.message?.take(180) ?: "Camera could not be started."
        }
    }

    DisposableEffect(cameraProvider) {
        onDispose {
            runCatching { cameraProvider?.unbindAll() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MyraVoid)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MyraTextPrimary)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("MYRA CAMERA VISION", color = MyraTextPrimary, style = MaterialTheme.typography.titleMedium)
                Text("Capture a scene and ask Gemini Live about it.", color = MyraTextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (hasPermission) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp)),
            )
        } else {
            Card(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MyraVoid),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, tint = MyraCyan)
                    Spacer(Modifier.height(12.dp))
                    Text("Camera permission is required.", color = MyraTextPrimary)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRequestCameraPermission) { Text("Allow Camera") }
                }
            }
        }

        if (!isGeminiConnected) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MyraVoid),
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Gemini Live is disconnected.", color = MyraTextSecondary, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onConnectGemini) { Text("Connect") }
                }
            }
        }

        if (errorText != null) {
            Text(errorText.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MyraVoid),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                "Privacy: the captured photo is compressed and sent to Gemini Live only when you tap Capture & Analyze. It is not saved in the gallery. Do not photograph OTPs, PINs, CVVs or banking screens.",
                color = MyraTextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }

        Button(
            onClick = {
                val capture = imageCapture ?: return@Button
                val file = File(context.cacheDir, "myra_vision_${System.currentTimeMillis()}.jpg")
                isCapturing = true
                val options = ImageCapture.OutputFileOptions.Builder(file).build()
                capture.takePicture(
                    options,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            val bytes = runCatching { compressForGemini(file) }.getOrNull()
                            runCatching { file.delete() }
                            isCapturing = false
                            if (bytes == null) {
                                errorText = "Could not process the captured photo."
                            } else {
                                onAnalyzeImage(bytes)
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            runCatching { file.delete() }
                            isCapturing = false
                            errorText = exception.message?.take(180) ?: "Camera capture failed."
                        }
                    },
                )
            },
            enabled = hasPermission && isCameraReady && isGeminiConnected && !isCapturing,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MyraCyan),
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = null)
            Text(if (isCapturing) "Capturing..." else "Capture & Analyze")
        }
    }
}

private fun compressForGemini(file: File): ByteArray? {
    val original = BitmapFactory.decodeFile(file.absolutePath) ?: return null
    val maxDimension = 1280f
    val scale = min(1f, maxDimension / maxOf(original.width, original.height).toFloat())
    val resized = if (scale < 1f) {
        Bitmap.createScaledBitmap(
            original,
            (original.width * scale).toInt().coerceAtLeast(1),
            (original.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    } else {
        original
    }
    return try {
        ByteArrayOutputStream().use { output ->
            resized.compress(Bitmap.CompressFormat.JPEG, 82, output)
            output.toByteArray()
        }
    } finally {
        if (resized !== original) resized.recycle()
        original.recycle()
    }
}
