package com.myra.assistant.ui.screens

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.myra.assistant.data.db.UserMemoryEntity
import com.myra.assistant.data.db.EmotionalMemoryEntity
import com.myra.assistant.data.db.PendingMemoryEntity
import com.myra.assistant.data.db.ActionAuditEntity
import com.myra.assistant.model.ConnectionMode
import com.myra.assistant.model.GeminiModelOption
import com.myra.assistant.model.MyraSettings
import com.myra.assistant.model.PermissionSnapshot
import com.myra.assistant.model.PersonalityMode
import com.myra.assistant.model.VoiceOption
import com.myra.assistant.service.MyraLiveWallpaperService
import com.myra.assistant.ui.theme.MyraAmber
import com.myra.assistant.ui.theme.MyraBorderSubtle
import com.myra.assistant.ui.theme.MyraCyan
import com.myra.assistant.ui.theme.MyraGreen
import com.myra.assistant.ui.theme.MyraPrimeCard
import com.myra.assistant.ui.theme.MyraPurple
import com.myra.assistant.ui.theme.MyraRed
import com.myra.assistant.ui.theme.MyraSurface
import com.myra.assistant.ui.theme.MyraTextHint
import com.myra.assistant.ui.theme.MyraTextPrimary
import com.myra.assistant.ui.theme.MyraTextSecondary
import com.myra.assistant.ui.theme.MyraVoid
import com.myra.assistant.viewmodel.MainUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: MyraSettings,
    uiState: MainUiState,
    wakeWordStatusNote: String,
    isEncryptedStorage: Boolean,
    memories: List<UserMemoryEntity>,
    emotionalMemories: List<EmotionalMemoryEntity>,
    pendingMemories: List<PendingMemoryEntity>,
    recentActionAudit: List<ActionAuditEntity>,
    onApproveMemory: (Long) -> Unit,
    onRejectMemory: (Long) -> Unit,
    onUpdateMemory: (String, String) -> Unit,
    onUpdateEmotionalMemory: (String, String, String) -> Unit,
    onDeleteMemory: (String) -> Unit,
    onDeleteEmotionalMemory: (String) -> Unit,
    onClearMemories: () -> Unit,
    onClearEmotionalMemories: () -> Unit,
    onBack: () -> Unit,
    onSave: (
        connectionMode: ConnectionMode,
        apiKey: String,
        proxyUrl: String,
        proxyAuthToken: String,
        userName: String,
        model: GeminiModelOption,
        voice: VoiceOption,
        personality: PersonalityMode,
        pcPort: Int,
    ) -> Unit,
    onClearCredentials: () -> Unit,
    onAddPrimeContact: (name: String, number: String) -> Unit,
    onRemovePrimeContact: (index: Int) -> Unit,
    onToggleWakeWord: (Boolean) -> Unit,
    onToggleOverlay: (Boolean) -> Unit,
    onToggleCallMonitor: (Boolean) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onClearChat: () -> Unit,
    onExportBackup: (String) -> Unit,
    onImportBackup: (String) -> Unit,
) {
    val context = LocalContext.current

    var connectionMode by remember(settings.connectionMode) { mutableStateOf(settings.connectionMode) }
    var apiKey by remember(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var showApiKey by remember { mutableStateOf(false) }
    var proxyUrl by remember(settings.proxyUrl) { mutableStateOf(settings.proxyUrl) }
    var proxyAuthToken by remember(settings.proxyAuthToken) { mutableStateOf(settings.proxyAuthToken) }
    var showProxyToken by remember { mutableStateOf(false) }
    var userName by remember(settings.userName) { mutableStateOf(settings.userName) }
    var selectedModel by remember(settings.model) { mutableStateOf(settings.model) }
    var selectedVoice by remember(settings.voice) { mutableStateOf(settings.voice) }
    var selectedPersonality by remember(settings.personality) { mutableStateOf(settings.personality) }
    var pcPortText by remember(settings.pcCompanionPort) { mutableStateOf(settings.pcCompanionPort.toString()) }

    var modelExpanded by remember { mutableStateOf(false) }
    var voiceExpanded by remember { mutableStateOf(false) }
    var showAddPrimeDialog by remember { mutableStateOf(false) }
    var showClearMemoriesDialog by remember { mutableStateOf(false) }
    var showClearEmotionalMemoriesDialog by remember { mutableStateOf(false) }
    var editingPersonalMemoryKey by remember { mutableStateOf<String?>(null) }
    var editingPersonalMemoryValue by remember { mutableStateOf("") }
    var editingEmotionalMemoryKey by remember { mutableStateOf<String?>(null) }
    var editingEmotion by remember { mutableStateOf("") }
    var editingEmotionalContext by remember { mutableStateOf("") }
    var backupPassphrase by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MyraVoid)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(48.dp).testTag("settings_back_button"),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MyraTextPrimary)
            }
            Text("MYRA SETTINGS", style = MaterialTheme.typography.titleLarge, color = MyraRed)
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SettingsSectionHeader("AI & VOICE", "Gemini connection, model, voice and personality") }

            // 1. Gemini API & Profile
            item {
                SettingsCard(title = "GEMINI LIVE & IDENTITY") {
                    Text(
                        text = "Connection Mode",
                        style = MaterialTheme.typography.labelMedium,
                        color = MyraTextSecondary,
                    )
                    ConnectionMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable { connectionMode = mode }
                                .testTag("connection_mode_${mode.key.lowercase()}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = connectionMode == mode,
                                onClick = { connectionMode = mode },
                                colors = RadioButtonDefaults.colors(selectedColor = MyraRed),
                            )
                            Text(mode.displayName, color = MyraTextPrimary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    if (connectionMode == ConnectionMode.DIRECT_USER_KEY) {
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text("Your Gemini API Key") },
                            placeholder = { Text("Paste personal AI Studio Gemini API Key") },
                            singleLine = true,
                            visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showApiKey = !showApiKey }) {
                                    Icon(
                                        if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Toggle API Key visibility",
                                    )
                                }
                            },
                            colors = fieldColors(),
                            modifier = Modifier.fillMaxWidth().testTag("api_key_input"),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Note: No API key is bundled in MYRA APK. User keys stored on-device use EncryptedSharedPreferences, but client-side keys cannot be 100% hidden on rooted devices — set Google Cloud API restrictions/quotas or use Backend Proxy for production.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MyraAmber,
                        )
                    } else {
                        OutlinedTextField(
                            value = proxyUrl,
                            onValueChange = { proxyUrl = it },
                            label = { Text("Backend Proxy WebSocket URL") },
                            placeholder = { Text("wss://your-myra-proxy.example.com/ws") },
                            singleLine = true,
                            colors = fieldColors(),
                            modifier = Modifier.fillMaxWidth().testTag("proxy_url_input"),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = proxyAuthToken,
                            onValueChange = { proxyAuthToken = it },
                            label = { Text("Proxy Bearer Token (Required)") },
                            placeholder = { Text("Paste the 32+ character server token") },
                            singleLine = true,
                            visualTransformation = if (showProxyToken) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showProxyToken = !showProxyToken }) {
                                    Icon(
                                        if (showProxyToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Toggle Proxy Token visibility",
                                    )
                                }
                            },
                            colors = fieldColors(),
                            modifier = Modifier.fillMaxWidth().testTag("proxy_token_input"),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Production mode: Gemini API Key stays strictly on your server; Android sends wss:// frames with Bearer auth.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MyraCyan,
                        )
                    }

                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (isEncryptedStorage) {
                                "Storage: Android Keystore-protected encrypted preferences"
                            } else {
                                "SECURE STORAGE UNAVAILABLE — API Key/Proxy Token will NOT be saved"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isEncryptedStorage) MyraGreen else MyraAmber,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                apiKey = ""
                                proxyAuthToken = ""
                                onClearCredentials()
                            },
                            modifier = Modifier.testTag("clear_credentials_button"),
                        ) {
                            Text("Clear Keys", color = MyraRed)
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = userName,
                        onValueChange = { userName = it },
                        label = { Text("Your Name") },
                        singleLine = true,
                        colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth().testTag("user_name_input"),
                    )
                }
            }

            // 2. Model & Voice
            item {
                SettingsCard(title = "MODEL & VOICE ENGINE") {
                    ExposedDropdownMenuBox(
                        expanded = modelExpanded,
                        onExpandedChange = { modelExpanded = !modelExpanded },
                    ) {
                        OutlinedTextField(
                            value = selectedModel.displayName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Gemini Live Model") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                            colors = fieldColors(),
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                                .testTag("model_dropdown"),
                        )
                        ExposedDropdownMenu(
                            expanded = modelExpanded,
                            onDismissRequest = { modelExpanded = false },
                        ) {
                            GeminiModelOption.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text("${option.displayName}\n${option.modelId}", style = MaterialTheme.typography.bodyMedium) },
                                    onClick = {
                                        selectedModel = option
                                        modelExpanded = false
                                    },
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    ExposedDropdownMenuBox(
                        expanded = voiceExpanded,
                        onExpandedChange = { voiceExpanded = !voiceExpanded },
                    ) {
                        OutlinedTextField(
                            value = selectedVoice.displayName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Voice (Default: Aoede)") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceExpanded) },
                            colors = fieldColors(),
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                                .testTag("voice_dropdown"),
                        )
                        ExposedDropdownMenu(
                            expanded = voiceExpanded,
                            onDismissRequest = { voiceExpanded = false },
                        ) {
                            VoiceOption.entries.forEach { v ->
                                DropdownMenuItem(
                                    text = { Text(v.displayName) },
                                    onClick = {
                                        selectedVoice = v
                                        voiceExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "MYRA automatically prefers a connected Bluetooth or wired headset for two-way voice. Bluetooth Connect permission is requested on Android 12+; otherwise Android's default audio route is used.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MyraTextSecondary,
                    )
                }
            }

            // 3. Personality Mode
            item {
                SettingsCard(title = "PERSONALITY MODE") {
                    PersonalityMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable { selectedPersonality = mode }
                                .testTag("personality_${mode.key.lowercase()}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedPersonality == mode,
                                onClick = { selectedPersonality = mode },
                                colors = RadioButtonDefaults.colors(selectedColor = MyraRed),
                            )
                            Text(mode.displayName, color = MyraTextPrimary)
                        }
                    }
                }
            }

            item { SettingsSectionHeader("PERSONALIZATION", "Your trusted contacts and assistant identity") }

            // 4. Prime Contacts
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MyraPrimeCard),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MyraRed.copy(alpha = 0.45f), RoundedCornerShape(16.dp)),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("PRIME CONTACTS", style = MaterialTheme.typography.labelMedium, color = MyraRed)
                            Button(
                                onClick = { showAddPrimeDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = MyraRed),
                                modifier = Modifier.heightIn(min = 48.dp).testTag("add_prime_contact_button"),
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Add Prime Contact", modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Add")
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        if (settings.primeContacts.isEmpty()) {
                            Text(
                                "No Prime Contacts added yet. Say 'close friend ko call karo' once added.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MyraTextSecondary,
                            )
                        } else {
                            settings.primeContacts.forEachIndexed { idx, contact ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("${idx + 1}. ${contact.name}", color = MyraTextPrimary, style = MaterialTheme.typography.titleMedium)
                                        Text(contact.number, color = MyraTextSecondary, style = MaterialTheme.typography.labelMedium)
                                    }
                                    IconButton(
                                        onClick = { onRemovePrimeContact(idx) },
                                        modifier = Modifier.size(48.dp),
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Remove ${contact.name}", tint = MyraRed)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item { SettingsSectionHeader("CONNECT & BACKGROUND", "Wake word, companion, overlay and live wallpaper") }

            // 5. Background Services & Live Wallpaper
            item {
                SettingsCard(title = "ANDROID SERVICES & LIVE WALLPAPER") {
                    ServiceToggleRow(
                        title = "Floating MYRA Orb Overlay",
                        subtitle = "Double-toggle power screen or persistent floating orb",
                        checked = settings.overlayEnabled || uiState.overlayRunning,
                        testTag = "toggle_overlay_service",
                        onCheckedChange = onToggleOverlay,
                    )
                    ServiceToggleRow(
                        title = "Incoming Call Monitor",
                        subtitle = "Monitors cellular call state; caller ID may be unavailable on newer Android versions. Voice answer/reject needs permission.",
                        checked = settings.callMonitorEnabled || uiState.callMonitorRunning,
                        testTag = "toggle_call_monitor",
                        onCheckedChange = onToggleCallMonitor,
                    )
                    ServiceToggleRow(
                        title = "Foreground Voice Trigger",
                        subtitle = "Uses OS SpeechRecognizer with a persistent notification ($wakeWordStatusNote). Prefers on-device speech recognition when Android supports it; otherwise may use network. Not a dedicated hotword model. Open MYRA after reboot to restart.",
                        checked = settings.wakeWordEnabled || uiState.wakeWordRunning,
                        testTag = "toggle_wake_word",
                        onCheckedChange = onToggleWakeWord,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pcPortText,
                        onValueChange = { pcPortText = it.filter { c -> c.isDigit() } },
                        label = { Text("PC Companion Port") },
                        singleLine = true,
                        colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                                putExtra(
                                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                                    ComponentName(context, MyraLiveWallpaperService::class.java),
                                )
                            }
                            runCatching { context.startActivity(intent) }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("set_live_wallpaper_button"),
                    ) {
                        Icon(Icons.Default.Wallpaper, contentDescription = "Set Live Wallpaper")
                        Spacer(Modifier.width(8.dp))
                        Text("Set MYRA Neural Orb Live Wallpaper")
                    }
                }
            }

            item { SettingsSectionHeader("AI BRAIN & PRIVACY", "Review, approve and manage what MYRA remembers") }

            // Explicit-consent memory controls
            item {
                SettingsCard(title = "MYRA MEMORY & PRIVACY") {
                    Text(
                        text = "MYRA keeps its own chat history on this device. Personal facts and emotional moments are proposed separately and are NOT saved until you approve them here. Chat history, personal facts and emotional memories can be deleted independently. Important: API credentials use Android Keystore-protected preferences when available; the Room chat and memory database is currently app-private but is not encrypted at the database level.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MyraTextSecondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("WAITING FOR YOUR APPROVAL (${pendingMemories.size})", color = MyraAmber, style = MaterialTheme.typography.labelMedium)
                    if (pendingMemories.isEmpty()) {
                        Text("No memory proposals waiting.", color = MyraTextSecondary, style = MaterialTheme.typography.bodySmall)
                    } else {
                        pendingMemories.forEach { pending ->
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Text(if (pending.category == "emotion") "Emotional moment proposal" else "Personal memory proposal", color = MyraCyan, style = MaterialTheme.typography.labelSmall)
                                Text(pending.value, color = MyraTextPrimary, style = MaterialTheme.typography.bodyMedium)
                                Text("Your words: “${pending.sourceText}”", color = MyraTextSecondary, style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { onApproveMemory(pending.id) }) { Text("Approve") }
                                    TextButton(onClick = { onRejectMemory(pending.id) }) { Text("Reject", color = MyraRed) }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("APPROVED PERSONAL FACTS (${memories.size})", color = MyraCyan, style = MaterialTheme.typography.labelMedium)
                    if (memories.isEmpty()) Text("No approved personal facts yet.", color = MyraTextSecondary, style = MaterialTheme.typography.bodySmall)
                    memories.forEach { memory ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(memory.category.replaceFirstChar { it.uppercase() }, color = MyraRed, style = MaterialTheme.typography.labelSmall)
                                Text(memory.value, color = MyraTextPrimary, style = MaterialTheme.typography.bodyMedium)
                                Text("Evidence: “${memory.sourceText}”", color = MyraTextSecondary, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = {
                                    editingPersonalMemoryKey = memory.memoryKey
                                    editingPersonalMemoryValue = memory.value
                                }) { Text("Edit") }
                            }
                            IconButton(onClick = { onDeleteMemory(memory.memoryKey) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete personal memory", tint = MyraRed)
                            }
                        }
                    }
                    if (memories.isNotEmpty()) {
                        TextButton(onClick = { showClearMemoriesDialog = true }) { Text("Delete all personal facts", color = MyraRed) }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("EMOTIONAL MOMENTS (${emotionalMemories.size})", color = MyraCyan, style = MaterialTheme.typography.labelMedium)
                    if (emotionalMemories.isEmpty()) Text("No approved emotional moments yet.", color = MyraTextSecondary, style = MaterialTheme.typography.bodySmall)
                    emotionalMemories.forEach { memory ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(memory.emotion.replaceFirstChar { it.uppercase() }, color = MyraRed, style = MaterialTheme.typography.labelSmall)
                                Text(memory.context, color = MyraTextPrimary, style = MaterialTheme.typography.bodyMedium)
                                Text("Date: ${java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(memory.createdAt))}", color = MyraTextSecondary, style = MaterialTheme.typography.bodySmall)
                                Text("Your words: “${memory.sourceText}”", color = MyraTextSecondary, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = {
                                    editingEmotionalMemoryKey = memory.memoryKey
                                    editingEmotion = memory.emotion
                                    editingEmotionalContext = memory.context
                                }) { Text("Edit") }
                            }
                            IconButton(onClick = { onDeleteEmotionalMemory(memory.memoryKey) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete emotional memory", tint = MyraRed)
                            }
                        }
                    }
                    if (emotionalMemories.isNotEmpty()) {
                        TextButton(onClick = { showClearEmotionalMemoriesDialog = true }) { Text("Delete all emotional moments", color = MyraRed) }
                    }
                }
            }

            item { SettingsSectionHeader("ACTIVITY & BACKUP", "Review actions and protect your MYRA data") }

            item {
                SettingsCard(title = "RECENT ANDROID ACTION HISTORY") {
                    Text(
                        "Records the command type, parameter names, result and time. Actual phone numbers and message contents are deliberately not copied into this audit list; the chat history is kept separately.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MyraTextSecondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (recentActionAudit.isEmpty()) {
                        Text("No Android actions recorded yet.", color = MyraTextSecondary, style = MaterialTheme.typography.bodySmall)
                    } else {
                        recentActionAudit.forEach { entry ->
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(entry.commandType, color = MyraTextPrimary, style = MaterialTheme.typography.labelMedium)
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        if (entry.success) "Success" else "Failed",
                                        color = if (entry.success) MyraGreen else MyraRed,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                Text("Parameters: ${entry.parameterNames.ifBlank { "none" }}", color = MyraTextSecondary, style = MaterialTheme.typography.bodySmall)
                                Text(entry.resultSummary, color = MyraTextSecondary, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.getDefault()).format(java.util.Date(entry.timestamp)),
                                    color = MyraTextHint,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }


            item {
                SettingsCard(title = "ENCRYPTED BACKUP & RESTORE") {
                    Text(
                        "Protect your MYRA chats and memories with a password. Use at least 12 characters and keep it safe; MYRA cannot recover a forgotten backup password.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MyraTextSecondary,
                    )
                    OutlinedTextField(
                        value = backupPassphrase,
                        onValueChange = { backupPassphrase = it.take(128) },
                        label = { Text("Backup password (12+ characters)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("backup_passphrase"),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { onExportBackup(backupPassphrase) },
                            enabled = backupPassphrase.length >= 12,
                            modifier = Modifier.weight(1f).testTag("export_backup"),
                        ) { Text("Create Backup") }
                        OutlinedButton(
                            onClick = { onImportBackup(backupPassphrase) },
                            enabled = backupPassphrase.length >= 12,
                            modifier = Modifier.weight(1f).testTag("import_backup"),
                        ) { Text("Restore Backup") }
                    }
                    Text(
                        "Included: saved chats, approved and pending memories, emotional moments, and recent action history. Excluded: API keys, proxy tokens, paired-device keys, diagnostics, and location data. Restore merges records; it does not erase current data.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MyraTextHint,
                    )
                }
            }

            item { SettingsSectionHeader("PERMISSIONS & SAFETY", "Android access and privacy controls") }

            // 6. Special Android Permissions Status
            item {
                SettingsCard(title = "SPECIAL SYSTEM ACCESS") {
                    SpecialAccessRow(
                        label = "Accessibility Service",
                        enabled = uiState.permissions.accessibility,
                        onClick = onOpenAccessibilitySettings,
                    )
                    SpecialAccessRow(
                        label = "Display Over Other Apps",
                        enabled = uiState.permissions.overlay,
                        onClick = onOpenOverlaySettings,
                    )
                    SpecialAccessRow(
                        label = "Notification Access (Local OTP Guard)",
                        enabled = uiState.permissions.notificationListener,
                        onClick = onOpenNotificationListenerSettings,
                    )
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = onClearChat) {
                        Text("Clear Saved Chat History", color = MyraRed)
                    }
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
        }

        // Save & Reconnect CTA
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .background(Brush.horizontalGradient(listOf(MyraRed, MyraPurple)), RoundedCornerShape(16.dp)),
        ) {
            Button(
                onClick = {
                    val port = pcPortText.toIntOrNull()?.coerceIn(1024, 65535) ?: 8765
                    onSave(
                        connectionMode,
                        apiKey,
                        proxyUrl,
                        proxyAuthToken,
                        userName,
                        selectedModel,
                        selectedVoice,
                        selectedPersonality,
                        port,
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .testTag("save_settings_button"),
            ) {
                Text("SAVE & APPLY CHANGES", style = MaterialTheme.typography.titleMedium, color = MyraTextPrimary)
            }
        }
    }

    if (editingPersonalMemoryKey != null) {
        AlertDialog(
            onDismissRequest = { editingPersonalMemoryKey = null },
            containerColor = MyraSurface,
            title = { Text("Edit personal memory", color = MyraTextPrimary) },
            text = {
                OutlinedTextField(
                    value = editingPersonalMemoryValue,
                    onValueChange = { editingPersonalMemoryValue = it.take(160) },
                    label = { Text("Saved fact") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    editingPersonalMemoryKey?.let { onUpdateMemory(it, editingPersonalMemoryValue) }
                    editingPersonalMemoryKey = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingPersonalMemoryKey = null }) { Text("Cancel") } },
        )
    }

    if (editingEmotionalMemoryKey != null) {
        AlertDialog(
            onDismissRequest = { editingEmotionalMemoryKey = null },
            containerColor = MyraSurface,
            title = { Text("Edit emotional memory", color = MyraTextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editingEmotion,
                        onValueChange = { editingEmotion = it.take(40) },
                        label = { Text("Emotion") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = editingEmotionalContext,
                        onValueChange = { editingEmotionalContext = it.take(180) },
                        label = { Text("Context") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editingEmotionalMemoryKey?.let { onUpdateEmotionalMemory(it, editingEmotion, editingEmotionalContext) }
                    editingEmotionalMemoryKey = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingEmotionalMemoryKey = null }) { Text("Cancel") } },
        )
    }

    if (showClearMemoriesDialog) {
        AlertDialog(
            onDismissRequest = { showClearMemoriesDialog = false },
            containerColor = MyraSurface,
            title = { Text("Delete all saved memories?", color = MyraTextPrimary) },
            text = { Text("MYRA will no longer use these approved personal facts. Emotional moments and chat history are not affected.", color = MyraTextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    onClearMemories()
                    showClearMemoriesDialog = false
                }) { Text("Delete all", color = MyraRed) }
            },
            dismissButton = {
                TextButton(onClick = { showClearMemoriesDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showClearEmotionalMemoriesDialog) {
        AlertDialog(
            onDismissRequest = { showClearEmotionalMemoriesDialog = false },
            containerColor = MyraSurface,
            title = { Text("Delete all emotional moments?", color = MyraTextPrimary) },
            text = { Text("This removes approved emotional event memories only. Personal facts and chat history remain unchanged.", color = MyraTextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    onClearEmotionalMemories()
                    showClearEmotionalMemoriesDialog = false
                }) { Text("Delete all", color = MyraRed) }
            },
            dismissButton = {
                TextButton(onClick = { showClearEmotionalMemoriesDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showAddPrimeDialog) {
        var nameInput by remember { mutableStateOf("") }
        var numberInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddPrimeDialog = false },
            containerColor = MyraSurface,
            title = { Text("Add Prime Contact", color = MyraTextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Name (e.g. Priya)") },
                        singleLine = true,
                        colors = fieldColors(),
                    )
                    OutlinedTextField(
                        value = numberInput,
                        onValueChange = { numberInput = it },
                        label = { Text("Phone Number") },
                        singleLine = true,
                        colors = fieldColors(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onAddPrimeContact(nameInput, numberInput)
                        showAddPrimeDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MyraRed),
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showAddPrimeDialog = false }) { Text("Cancel") }
            },
        )
    }
}


@Composable
private fun SettingsSectionHeader(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp)
            .background(MyraSurface, RoundedCornerShape(14.dp))
            .border(1.dp, MyraBorderSubtle, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = title,
            color = MyraCyan,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = subtitle,
            color = MyraTextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit,
) {
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
private fun ServiceToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MyraTextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MyraTextSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = MyraTextPrimary, checkedTrackColor = MyraRed),
            modifier = Modifier.testTag(testTag),
        )
    }
}

@Composable
private fun SpecialAccessRow(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MyraTextPrimary, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = if (enabled) "✅ ENABLED" else "❌ TAP TO ENABLE",
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) MyraGreen else MyraRed,
        )
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MyraRed,
    unfocusedBorderColor = MyraBorderSubtle,
    focusedLabelColor = MyraRed,
    unfocusedLabelColor = MyraTextSecondary,
    focusedTextColor = MyraTextPrimary,
    unfocusedTextColor = MyraTextPrimary,
)
