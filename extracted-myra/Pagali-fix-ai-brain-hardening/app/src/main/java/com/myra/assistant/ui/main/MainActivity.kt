package com.myra.assistant.ui.main

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.ByteArrayOutputStream
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.myra.assistant.service.CallMonitorService
import com.myra.assistant.service.MyraOverlayService
import com.myra.assistant.service.WakeWordService
import com.myra.assistant.ui.screens.BarcodeScannerScreen
import com.myra.assistant.ui.screens.CameraVisionScreen
import com.myra.assistant.ui.screens.ConnectAndCompanionScreen
import com.myra.assistant.ui.screens.DiagnosticsAndPermissionsScreen
import com.myra.assistant.ui.screens.MainDashboardScreen
import com.myra.assistant.ui.screens.MacroAutomationScreen
import com.myra.assistant.ui.screens.SettingsScreen
import com.myra.assistant.ui.theme.MyraTheme
import com.myra.assistant.viewmodel.MainViewModel
import kotlinx.serialization.Serializable

@Serializable
object RouteMain

@Serializable
object RouteSettings

@Serializable
object RouteMacros

@Serializable
object RouteDiagnostics

@Serializable
object RouteConnect

@Serializable
object RouteScanner

@Serializable
object RouteCameraVision

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.refreshPermissionsAndServices()
        mainHandler.postDelayed({ viewModel.initializeLiveSessionIfReady() }, 250L)
    }

    private var isActivityResumed = false

    private val incomingCallReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (isActivityResumed && intent?.action == CallMonitorService.ACTION_INCOMING_CALL) {
                handleIncomingIntent(intent)
            }
        }
    }

    private val callEndedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CallMonitorService.ACTION_CALL_ENDED) {
                viewModel.onCallEnded(resumeMicrophone = isActivityResumed)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        registerCallEndedReceiver()
        registerIncomingCallReceiver()
        requestCoreRuntimePermissionsIfNeeded()
        val wakeTriggered = intent?.getBooleanExtra(WakeWordService.EXTRA_WAKE_TRIGGERED, false) == true
        val overlayTriggered = intent?.getBooleanExtra(MyraOverlayService.EXTRA_FROM_OVERLAY, false) == true
        if (!wakeTriggered && !overlayTriggered) {
            mainHandler.postDelayed({ viewModel.initializeLiveSessionIfReady() }, 300L)
        }
        handleIncomingIntent(intent)

        setContent {
            var pendingBackupBytes by remember { mutableStateOf<ByteArray?>(null) }
            var pendingImportPassphrase by remember { mutableStateOf("") }
            val createBackupLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/octet-stream"),
            ) { uri ->
                val bytes = pendingBackupBytes
                pendingBackupBytes = null
                if (uri != null && bytes != null) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val saved = runCatching {
                            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                                ?: error("Could not open the selected backup destination.")
                        }
                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                if (saved.isSuccess) "Encrypted MYRA backup saved." else "Backup save failed: ${saved.exceptionOrNull()?.message}",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            }
            val openBackupLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) {
                    val password = pendingImportPassphrase
                    pendingImportPassphrase = ""
                    lifecycleScope.launch(Dispatchers.IO) {
                        val loaded = runCatching {
                            contentResolver.openInputStream(uri)?.use { input ->
                                val output = ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                var total = 0
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    require(total <= 28 * 1024 * 1024) { "Backup file is too large." }
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray()
                            } ?: error("Could not read the selected backup file.")
                        }
                        runOnUiThread {
                            if (loaded.isSuccess) {
                                viewModel.restoreEncryptedBackup(loaded.getOrThrow(), password)
                            } else {
                                Toast.makeText(this@MainActivity, loaded.exceptionOrNull()?.message ?: "Could not read backup.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                } else {
                    pendingImportPassphrase = ""
                }
            }
            MyraTheme {
                val navController = rememberNavController()
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val settings by viewModel.settings.collectAsStateWithLifecycle()
                val chatMessages by viewModel.chatMessages.collectAsStateWithLifecycle()
                val diagnosticLogs by viewModel.diagnosticLogs.collectAsStateWithLifecycle()
                val parkingSpot by viewModel.latestParkingSpot.collectAsStateWithLifecycle()
                val pairedDevices by viewModel.pairedDevices.collectAsStateWithLifecycle()
                val memories by viewModel.memories.collectAsStateWithLifecycle()
                val emotionalMemories by viewModel.emotionalMemories.collectAsStateWithLifecycle()
                val pendingMemories by viewModel.pendingMemories.collectAsStateWithLifecycle()
                val recentActionAudit by viewModel.recentActionAudit.collectAsStateWithLifecycle()
                val otpAlert by viewModel.otpAlert.collectAsStateWithLifecycle()
                val recentNotifications by viewModel.recentNotifications.collectAsStateWithLifecycle()
                val wakeWordStatus by viewModel.wakeWordStatusNote.collectAsStateWithLifecycle()
                val pcState by viewModel.pcCompanionState.collectAsStateWithLifecycle()
                val connectState by viewModel.myraConnectState.collectAsStateWithLifecycle()

                LaunchedEffect(Unit) {
                    viewModel.toastMessages.collect { msg ->
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    }
                }

                LaunchedEffect(Unit) {
                    viewModel.navigationRequests.collect { target ->
                        when (target) {
                            "scanner" -> navController.navigate(RouteScanner)
                            "camera" -> navController.navigate(RouteCameraVision)
                            "settings" -> navController.navigate(RouteSettings)
                            "diagnostics" -> navController.navigate(RouteDiagnostics)
                            "connect" -> navController.navigate(RouteConnect)
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = RouteMain,
                ) {
                    composable<RouteMain> {
                        MainDashboardScreen(
                            uiState = uiState,
                            settings = settings,
                            chatMessages = chatMessages,
                            otpAlert = otpAlert,
                            onToggleMic = { viewModel.toggleMic() },
                            onInterrupt = { viewModel.interruptMyraSpeaking() },
                            onSendText = { viewModel.sendUserText(it) },
                            onQuickCommand = { viewModel.runQuickCommand(it) },
                            onConnectClick = { viewModel.connectLiveSession() },
                            onDisconnectClick = { viewModel.disconnectLiveSession() },
                            onAcceptCall = { viewModel.acceptIncomingCall() },
                            onRejectCall = { viewModel.rejectIncomingCall() },
                            onDismissOtp = { viewModel.dismissOtpAlert() },
                            onOpenSettings = { navController.navigate(RouteSettings) },
                            onOpenMacros = { navController.navigate(RouteMacros) },
                            onOpenDiagnostics = { navController.navigate(RouteDiagnostics) },
                            onOpenConnect = { navController.navigate(RouteConnect) },
                            onOpenScanner = { navController.navigate(RouteScanner) },
                            onOpenCamera = { navController.navigate(RouteCameraVision) },
                        )
                    }

                    composable<RouteMacros> {
                        MacroAutomationScreen(onBack = { navController.popBackStack() })
                    }

                    composable<RouteSettings> {
                        SettingsScreen(
                            settings = settings,
                            uiState = uiState,
                            wakeWordStatusNote = wakeWordStatus,
                            isEncryptedStorage = viewModel.isSecureStorageAvailable,
                            memories = memories,
                            emotionalMemories = emotionalMemories,
                            pendingMemories = pendingMemories,
                            recentActionAudit = recentActionAudit,
                            onApproveMemory = { id -> viewModel.approveMemory(id) },
                            onRejectMemory = { id -> viewModel.rejectMemory(id) },
                            onUpdateMemory = { key, value -> viewModel.updateMemory(key, value) },
                            onUpdateEmotionalMemory = { key, emotion, context -> viewModel.updateEmotionalMemory(key, emotion, context) },
                            onDeleteMemory = { key -> viewModel.deleteMemory(key) },
                            onDeleteEmotionalMemory = { key -> viewModel.deleteEmotionalMemory(key) },
                            onClearMemories = { viewModel.clearAllMemories() },
                            onClearEmotionalMemories = { viewModel.clearAllEmotionalMemories() },
                            onBack = { navController.popBackStack() },
                            onSave = { mode, key, proxyUrl, proxyToken, name, model, voice, personality, port ->
                                viewModel.saveSettings(
                                    connectionMode = mode,
                                    apiKey = key,
                                    proxyUrl = proxyUrl,
                                    proxyAuthToken = proxyToken,
                                    userName = name,
                                    model = model,
                                    voice = voice,
                                    personality = personality,
                                    pcPort = port,
                                )
                                navController.popBackStack()
                            },
                            onClearCredentials = { viewModel.clearStoredCredentials() },
                            onAddPrimeContact = { n, p -> viewModel.addPrimeContact(n, p) },
                            onRemovePrimeContact = { idx -> viewModel.removePrimeContact(idx) },
                            onToggleWakeWord = { viewModel.toggleWakeWordService(it) },
                            onToggleOverlay = { viewModel.toggleOverlayService(it) },
                            onToggleCallMonitor = { viewModel.toggleCallMonitorService(it) },
                            onOpenAccessibilitySettings = { viewModel.openAccessibilitySettings() },
                            onOpenOverlaySettings = { viewModel.openOverlaySettings() },
                            onOpenNotificationListenerSettings = { viewModel.openNotificationListenerSettings() },
                            onClearChat = { viewModel.clearChatHistory() },
                            onExportBackup = { passphrase ->
                                viewModel.createEncryptedBackup(passphrase) { bytes ->
                                    if (bytes != null) {
                                        pendingBackupBytes = bytes
                                        createBackupLauncher.launch("MYRA_Backup_${System.currentTimeMillis()}.myrabak")
                                    }
                                }
                            },
                            onImportBackup = { passphrase ->
                                pendingImportPassphrase = passphrase
                                openBackupLauncher.launch(arrayOf("application/octet-stream", "application/*", "*/*"))
                            },
                        )
                    }

                    composable<RouteDiagnostics> {
                        DiagnosticsAndPermissionsScreen(
                            uiState = uiState,
                            logs = diagnosticLogs,
                            parkingSpot = parkingSpot,
                            recentNotifications = recentNotifications,
                            wakeWordStatusNote = wakeWordStatus,
                            isEncryptedStorage = viewModel.isSecureStorageAvailable,
                            onBack = { navController.popBackStack() },
                            onRequestRuntimePermissions = { requestAllRuntimePermissions() },
                            onOpenAccessibilitySettings = { viewModel.openAccessibilitySettings() },
                            onOpenOverlaySettings = { viewModel.openOverlaySettings() },
                            onOpenNotificationListenerSettings = { viewModel.openNotificationListenerSettings() },
                            onOpenAppSettings = { viewModel.openAppDetailsSettings() },
                            onClearParking = { viewModel.clearParkingSpot() },
                            onClearLogs = { viewModel.clearDiagnosticLogs() },
                        )
                    }

                    composable<RouteConnect> {
                        ConnectAndCompanionScreen(
                            pcState = pcState,
                            connectState = connectState,
                            pairedDevices = pairedDevices,
                            onBack = { navController.popBackStack() },
                            onTogglePcCompanion = { viewModel.togglePcCompanion(it) },
                            onRegeneratePcPin = { viewModel.regeneratePcPin() },
                            onToggleMyraConnect = { viewModel.toggleMyraConnectHosting(it) },
                            onPairAndSendPeer = { host, port, pin, name, msg ->
                                viewModel.pairAndSendToMyraPeer(host, port, pin, name, msg)
                            },
                            onRemovePairedDevice = { id -> viewModel.removePairedDevice(id) },
                        )
                    }

                    composable<RouteCameraVision> {
                        CameraVisionScreen(
                            isGeminiConnected = uiState.connectionState == com.myra.assistant.model.ConnectionState.CONNECTED,
                            onBack = { navController.popBackStack() },
                            onConnectGemini = { viewModel.connectLiveSession() },
                            onAnalyzeImage = { bytes -> viewModel.analyzeCapturedImage(bytes) },
                            onRequestCameraPermission = {
                                permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                            },
                        )
                    }

                    composable<RouteScanner> {
                        BarcodeScannerScreen(
                            onBack = { navController.popBackStack() },
                            onUseResultInMyra = { value -> viewModel.onBarcodeScanned(value) },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(CallMonitorService.EXTRA_INCOMING_CALL, false)) {
            val caller = intent.getStringExtra(CallMonitorService.EXTRA_CALLER_NAME) ?: "Unknown caller"
            viewModel.handleIncomingCall(caller)
        }
        if (intent.getBooleanExtra(WakeWordService.EXTRA_WAKE_TRIGGERED, false)) {
            viewModel.activateFromWakeWord()
        }
        if (intent.getBooleanExtra(MyraOverlayService.EXTRA_FROM_OVERLAY, false)) {
            viewModel.activateFromOverlay()
        }
    }

    private val permissionPromptPrefs by lazy {
        getSharedPreferences("myra_runtime_permission_prompts", MODE_PRIVATE)
    }

    private fun wasPermissionRequested(permission: String): Boolean =
        permissionPromptPrefs.getBoolean("requested:$permission", false)

    private fun markPermissionsRequested(permissions: List<String>) {
        permissionPromptPrefs.edit().apply {
            permissions.forEach { putBoolean("requested:$it", true) }
        }.apply()
    }

    private fun requestCoreRuntimePermissionsIfNeeded() {
        // Ask automatically only once per permission. After a denial, do not trap the user
        // in repeated dialogs on every Activity recreation; Diagnostics keeps a manual action.
        val corePermissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        val needed = corePermissions.filter { !hasPerm(it) && !wasPermissionRequested(it) }
        if (needed.isNotEmpty()) {
            markPermissionsRequested(needed)
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun requestAllRuntimePermissions() {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val missing = list.filterNot { hasPerm(it) }
        if (missing.isEmpty()) {
            Toast.makeText(this, "All runtime permissions already granted.", Toast.LENGTH_SHORT).show()
        } else {
            // This is a user-initiated retry from Diagnostics/Permissions.
            markPermissionsRequested(missing)
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun hasPerm(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun registerIncomingCallReceiver() {
        val filter = IntentFilter(CallMonitorService.ACTION_INCOMING_CALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(incomingCallReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(incomingCallReceiver, filter)
        }
    }

    private fun registerCallEndedReceiver() {
        val filter = IntentFilter(CallMonitorService.ACTION_CALL_ENDED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(callEndedReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(callEndedReceiver, filter)
        }
    }

    override fun onPause() {
        isActivityResumed = false
        viewModel.onAppPaused()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        viewModel.onAppResumed()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(callEndedReceiver) }
        runCatching { unregisterReceiver(incomingCallReceiver) }
        super.onDestroy()
    }
}
