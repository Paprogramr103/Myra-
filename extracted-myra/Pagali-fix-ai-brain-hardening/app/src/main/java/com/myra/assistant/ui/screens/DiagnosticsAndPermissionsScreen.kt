package com.myra.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.myra.assistant.data.db.DiagnosticLogEntity
import com.myra.assistant.data.db.ParkingSpotEntity
import com.myra.assistant.model.NotificationSummary
import com.myra.assistant.ui.theme.MyraAmber
import com.myra.assistant.ui.theme.MyraBorderSubtle
import com.myra.assistant.ui.theme.MyraCyan
import com.myra.assistant.ui.theme.MyraGreen
import com.myra.assistant.ui.theme.MyraRed
import com.myra.assistant.ui.theme.MyraSurface
import com.myra.assistant.ui.theme.MyraTextPrimary
import com.myra.assistant.ui.theme.MyraTextSecondary
import com.myra.assistant.ui.theme.MyraVoid
import com.myra.assistant.viewmodel.MainUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsAndPermissionsScreen(
    uiState: MainUiState,
    logs: List<DiagnosticLogEntity>,
    parkingSpot: ParkingSpotEntity?,
    recentNotifications: List<NotificationSummary>,
    wakeWordStatusNote: String,
    isEncryptedStorage: Boolean,
    onBack: () -> Unit,
    onRequestRuntimePermissions: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onClearParking: () -> Unit,
    onClearLogs: () -> Unit,
) {
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
                modifier = Modifier.size(48.dp).testTag("diagnostics_back_button"),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MyraTextPrimary)
            }
            Text("PERMISSIONS & DIAGNOSTICS", style = MaterialTheme.typography.titleLarge, color = MyraRed)
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. Runtime & Special Permissions Matrix
            item {
                DiagCard(title = "PERMISSION MANAGER") {
                    val p = uiState.permissions
                    PermissionRow("Microphone (16kHz PCM)", p.microphone)
                    PermissionRow("Contacts (Caller & Dial Lookup)", p.contacts)
                    PermissionRow("Phone Call (ACTION_CALL)", p.phoneCall)
                    PermissionRow("Phone State (Incoming Ring)", p.phoneState)
                    PermissionRow("Answer / Reject Calls (API 28+)", p.answerCalls)
                    PermissionRow("Camera (Torch & Barcode)", p.camera)
                    PermissionRow("Location (Parking Spot)", p.location)
                    PermissionRow("Notifications (FGS)", p.notifications)
                    PermissionRow("Overlay (Floating Orb)", p.overlay)
                    PermissionRow("Accessibility Service", p.accessibility)
                    PermissionRow("Notification Listener (OTP Guard)", p.notificationListener)

                    Text(
                        text = "Android 13+ पर APK बाहर से install होने पर Accessibility या Notification Access के लिए Restricted Settings रोक सकती है। App Settings खोलें → ऊपर ⋮ → Allow restricted settings चुनें; फिर वापस आकर Accessibility / Notification Access चालू करें। MYRA इसे सुरक्षा कारणों से खुद ON नहीं कर सकती।",
                        style = MaterialTheme.typography.bodySmall,
                        color = MyraAmber,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = onRequestRuntimePermissions,
                        colors = ButtonDefaults.buttonColors(containerColor = MyraRed),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("request_permissions_button"),
                    ) {
                        Text("Request Runtime Permissions")
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = onOpenAccessibilitySettings, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text("Accessibility", maxLines = 1)
                        }
                        OutlinedButton(onClick = onOpenOverlaySettings, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text("Overlay", maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = onOpenNotificationListenerSettings, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text("Notif Access", maxLines = 1)
                        }
                        OutlinedButton(onClick = onOpenAppSettings, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text("App Settings", maxLines = 1)
                        }
                    }
                }
            }

            // 2. Security, Limitations & Engine Diagnostics
            item {
                DiagCard(title = "SYSTEM & SECURITY AUDIT") {
                    Text(
                        text = "• Credential Storage: ${if (isEncryptedStorage) "Android Keystore AES-256 EncryptedSharedPreferences (hardware backing depends on device)" else "Fallback private preferences — secrets are not saved while secure storage is unavailable"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isEncryptedStorage) MyraGreen else MyraAmber,
                    )
                    Text(
                        text = "• Chat & Memory Database: SQLCipher AES-256 encrypted Room database; its random key is wrapped by Android Keystore. If the key cannot be recovered, MYRA fails closed instead of creating a blank database.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MyraAmber,
                    )
                    Text(
                        text = "• Android Cloud Backup: Disabled for this app. Use MYRA's password-encrypted manual backup to move your data.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MyraGreen,
                    )
                    Text(
                        text = "• Microphone Privacy: Raw microphone audio is not saved to the MYRA database by default; recognized conversation text may be saved in chat history.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MyraCyan,
                    )
                    Text(
                        text = "• OTP Safety: Local volatile RAM only (60s TTL). Excluded from DB, Logs, Gemini, and PC/Device Sync.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MyraGreen,
                    )
                    Text(
                        text = "• Wi-Fi / Bluetooth: Opens official Android Settings Panels (silent toggle restricted on Android 10+).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MyraCyan,
                    )
                    Text(
                        text = "• Wake Word Engine: $wakeWordStatusNote",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MyraAmber,
                    )
                    Text(
                        text = "• Services: Overlay=${uiState.overlayRunning} | CallMon=${uiState.callMonitorRunning} | Wake=${uiState.wakeWordRunning} | PC=${uiState.pcCompanionRunning}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MyraTextSecondary,
                    )
                }
            }

            // 3. Saved Parking Spot
            item {
                DiagCard(title = "PARKING MEMORY") {
                    if (parkingSpot == null) {
                        Text("No parking coordinates saved.", style = MaterialTheme.typography.bodyMedium, color = MyraTextSecondary)
                    } else {
                        Text(
                            "Lat: ${"%.5f".format(parkingSpot.latitude)}, Lng: ${"%.5f".format(parkingSpot.longitude)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MyraGreen,
                        )
                        Text(parkingSpot.note, style = MaterialTheme.typography.bodyMedium, color = MyraTextPrimary)
                        TextButton(onClick = onClearParking) { Text("Clear Saved Parking", color = MyraRed) }
                    }
                }
            }

            // 4. Recent Non-OTP Notifications
            item {
                DiagCard(title = "NON-OTP NOTIFICATIONS (${recentNotifications.size})") {
                    if (recentNotifications.isEmpty()) {
                        Text(
                            if (uiState.permissions.notificationListener) "No active non-OTP notifications."
                            else "Notification Access is OFF.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MyraTextSecondary,
                        )
                    } else {
                        recentNotifications.take(5).forEach { n ->
                            Text(
                                "${n.appLabel}: ${n.title} — ${n.text}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MyraTextPrimary,
                                maxLines = 2,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            // 5. Diagnostic Logs Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("LIVE DIAGNOSTIC LOGS", style = MaterialTheme.typography.labelMedium, color = MyraRed)
                    TextButton(onClick = onClearLogs) { Text("Clear", color = MyraTextSecondary) }
                }
            }

            items(logs, key = { it.id }) { log ->
                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp))
                val color = when (log.level) {
                    "ERROR" -> MyraRed
                    "WARN" -> MyraAmber
                    else -> MyraCyan
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MyraSurface, RoundedCornerShape(10.dp))
                        .border(1.dp, MyraBorderSubtle, RoundedCornerShape(10.dp))
                        .padding(10.dp),
                ) {
                    Text("[$timeStr] ${log.level} • ${log.tag}", style = MaterialTheme.typography.labelSmall, color = color)
                    Text(log.message, style = MaterialTheme.typography.bodyMedium, color = MyraTextPrimary)
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun DiagCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MyraSurface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MyraBorderSubtle, RoundedCornerShape(16.dp)),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MyraRed)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MyraTextPrimary)
        Text(
            text = if (granted) "GRANTED" else "MISSING",
            style = MaterialTheme.typography.labelSmall,
            color = if (granted) MyraGreen else MyraRed,
        )
    }
}
