package com.myra.assistant.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.myra.assistant.model.AppCommand
import com.myra.assistant.model.ChatMessage
import com.myra.assistant.model.ConnectionState
import com.myra.assistant.model.MyraSettings
import com.myra.assistant.model.OrbState
import com.myra.assistant.model.OtpAlert
import com.myra.assistant.ui.components.MyraOrbCanvas
import com.myra.assistant.ui.components.WaveformVisualizer
import com.myra.assistant.ui.theme.MyraAmber
import com.myra.assistant.ui.theme.MyraBorderSubtle
import com.myra.assistant.ui.theme.MyraCyan
import com.myra.assistant.ui.theme.MyraGreen
import com.myra.assistant.ui.theme.MyraPrimeCard
import com.myra.assistant.ui.theme.MyraPurple
import com.myra.assistant.ui.theme.MyraRed
import com.myra.assistant.ui.theme.MyraSurface
import com.myra.assistant.ui.theme.MyraSurfaceElevated
import com.myra.assistant.ui.theme.MyraTextHint
import com.myra.assistant.ui.theme.MyraTextPrimary
import com.myra.assistant.ui.theme.MyraTextSecondary
import com.myra.assistant.ui.theme.MyraVoid
import com.myra.assistant.viewmodel.MainUiState

@Composable
fun MainDashboardScreen(
    uiState: MainUiState,
    settings: MyraSettings,
    chatMessages: List<ChatMessage>,
    otpAlert: OtpAlert?,
    onToggleMic: () -> Unit,
    onInterrupt: () -> Unit,
    onSendText: (String) -> Unit,
    onQuickCommand: (AppCommand) -> Unit,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onAcceptCall: () -> Unit,
    onRejectCall: () -> Unit,
    onDismissOtp: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenConnect: () -> Unit,
    onOpenScanner: () -> Unit,
    onOpenCamera: () -> Unit,
    onOpenMacros: () -> Unit,
) {
    val context = LocalContext.current
    var commandText by remember { mutableStateOf("") }
    val redOverlayAlpha by animateFloatAsState(
        targetValue = if (uiState.isActiveMode) 0.08f else 0f,
        animationSpec = tween(durationMillis = if (uiState.isActiveMode) 300 else 500),
        label = "red_overlay",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MyraVoid)
            .background(
                Brush.radialGradient(
                    colors = listOf(MyraRed.copy(alpha = 0.14f), Color.Transparent),
                    radius = 900f,
                ),
            ),
    ) {
        // Active Red Screen Tint Overlay
        if (redOverlayAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MyraRed.copy(alpha = redOverlayAlpha)),
            )
        }

        // State-reactive edge glow. This is UI-only and deliberately does not
        // touch AudioEngine, GeminiLiveClient, voice selection, or audio routing.
        val edgeColor = when (uiState.orbState) {
            OrbState.LISTENING -> MyraRed
            OrbState.THINKING -> MyraCyan
            OrbState.SPEAKING -> MyraPurple
            OrbState.IDLE -> MyraRed.copy(alpha = 0.18f)
        }
        val edgeAlpha by animateFloatAsState(
            targetValue = when (uiState.orbState) {
                OrbState.IDLE -> 0.10f
                OrbState.LISTENING -> 0.62f
                OrbState.THINKING -> 0.48f
                OrbState.SPEAKING -> 0.78f
            } * (0.75f + uiState.amplitude.coerceIn(0f, 1f) * 0.25f),
            animationSpec = tween(durationMillis = 180),
            label = "edge_lighting_alpha",
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stripWidth = 13.dp.toPx()
            val glowWidth = 34.dp.toPx()
            val tint = edgeColor.copy(alpha = edgeAlpha)
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(tint, tint.copy(alpha = 0.35f), Color.Transparent),
                    startX = 0f,
                    endX = glowWidth,
                ),
                topLeft = Offset.Zero,
                size = Size(glowWidth, size.height),
            )
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, tint.copy(alpha = 0.35f), tint),
                    startX = size.width - glowWidth,
                    endX = size.width,
                ),
                topLeft = Offset(size.width - glowWidth, 0f),
                size = Size(glowWidth, size.height),
            )
            drawRect(
                color = tint.copy(alpha = edgeAlpha * 0.30f),
                topLeft = Offset.Zero,
                size = Size(stripWidth, size.height),
            )
            drawRect(
                color = tint.copy(alpha = edgeAlpha * 0.30f),
                topLeft = Offset(size.width - stripWidth, 0f),
                size = Size(stripWidth, size.height),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            TopHudBar(
                uiState = uiState,
                settings = settings,
                onConnectionPillClick = {
                    if (uiState.connectionState == ConnectionState.CONNECTED) {
                        onDisconnectClick()
                    } else {
                        onConnectClick()
                    }
                },
                onOpenScanner = onOpenScanner,
                onOpenConnect = onOpenConnect,
                onOpenDiagnostics = onOpenDiagnostics,
                onOpenSettings = onOpenSettings,
            )

            // Ephemeral Local-Only OTP Alert Banner
            AnimatedVisibility(visible = otpAlert != null) {
                otpAlert?.let { alert ->
                    OtpGuardBanner(
                        alert = alert,
                        onCopy = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("OTP", alert.code))
                            Toast.makeText(context, "OTP copied locally", Toast.LENGTH_SHORT).show()
                        },
                        onDismiss = onDismissOtp,
                    )
                }
            }

            // Incoming Call Action Banner
            AnimatedVisibility(visible = uiState.isInCallMode) {
                IncomingCallBanner(
                    callerName = uiState.incomingCallerName ?: "Unknown",
                    onAccept = onAcceptCall,
                    onReject = onRejectCall,
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val isWide = maxWidth >= 680.dp
                val availableHeight = maxHeight
                val adaptiveOrbSize = when {
                    availableHeight < 420.dp -> 110.dp
                    availableHeight < 560.dp -> 150.dp
                    else -> 200.dp
                }
                if (isWide) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(0.95f)
                                .fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            OrbCenterpiece(uiState = uiState, orbSize = if (availableHeight < 480.dp) 160.dp else 230.dp)
                            Spacer(modifier = Modifier.height(14.dp))
                            MicActionControl(
                                uiState = uiState,
                                onToggleMic = onToggleMic,
                                onInterrupt = onInterrupt,
                            )
                        }
                        Column(
                            modifier = Modifier
                                .weight(1.05f)
                                .fillMaxHeight(),
                        ) {
                            QuickActionsStrip(
                                isTorchOn = uiState.systemStatus.isTorchOn,
                                onQuickCommand = onQuickCommand,
                                onOpenScanner = onOpenScanner,
                                onOpenCamera = onOpenCamera,
                                onOpenConnect = onOpenConnect,
                                onOpenMacros = onOpenMacros,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            ChatHistoryList(
                                messages = chatMessages,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            TextCommandInputBar(
                                value = commandText,
                                onValueChange = { commandText = it },
                                onSend = {
                                    onSendText(commandText)
                                    commandText = ""
                                },
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        OrbCenterpiece(uiState = uiState, orbSize = adaptiveOrbSize)
                        Spacer(modifier = Modifier.height(4.dp))
                        QuickActionsStrip(
                            isTorchOn = uiState.systemStatus.isTorchOn,
                            onQuickCommand = onQuickCommand,
                            onOpenScanner = onOpenScanner,
                            onOpenCamera = onOpenCamera,
                            onOpenConnect = onOpenConnect,
                            onOpenMacros = onOpenMacros,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        ChatHistoryList(
                            messages = chatMessages,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        TextCommandInputBar(
                            value = commandText,
                            onValueChange = { commandText = it },
                            onSend = {
                                onSendText(commandText)
                                commandText = ""
                            },
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        MicActionControl(
                            uiState = uiState,
                            onToggleMic = onToggleMic,
                            onInterrupt = onInterrupt,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopHudBar(
    uiState: MainUiState,
    settings: MyraSettings,
    onConnectionPillClick: () -> Unit,
    onOpenScanner: () -> Unit,
    onOpenConnect: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Left: Battery & RAM
        Column(modifier = Modifier.widthIn(min = 76.dp)) {
            Text(
                text = "BAT ${uiState.systemStatus.batteryPercent}%${if (uiState.systemStatus.isCharging) "⚡" else ""}",
                style = MaterialTheme.typography.labelMedium,
                color = MyraAmber,
            )
            Text(
                text = "${uiState.systemStatus.usedRamMb}/${uiState.systemStatus.totalRamMb}M",
                style = MaterialTheme.typography.labelSmall,
                color = MyraTextSecondary,
            )
        }

        // Center: MYRA + Connection Pill
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "MYRA",
                style = MaterialTheme.typography.titleLarge.copy(letterSpacing = 4.sp),
                color = MyraCyan,
            )
            Text(
                text = "YOUR PERSONAL AI COMPANION",
                style = MaterialTheme.typography.labelSmall,
                color = MyraTextHint,
            )
            val (dotColor, label) = when (uiState.connectionState) {
                ConnectionState.CONNECTED -> MyraGreen to "${settings.voice.voiceId} • LIVE"
                ConnectionState.CONNECTING -> MyraCyan to "CONNECTING"
                ConnectionState.RECONNECTING -> MyraAmber to "RECONNECTING"
                ConnectionState.ERROR -> MyraRed to "KEY/NET ERROR"
                ConnectionState.DISCONNECTED -> MyraTextHint to "STANDBY"
            }
            Surface(
                onClick = onConnectionPillClick,
                shape = RoundedCornerShape(50),
                color = MyraSurface,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .border(1.dp, dotColor.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .testTag("connection_status_pill"),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MyraTextPrimary,
                    )
                }
            }
        }

        // Right: Time + Tool Icons
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = uiState.systemStatus.timeLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MyraTextSecondary,
                modifier = Modifier.padding(end = 2.dp),
            )
            IconButton(
                onClick = onOpenConnect,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("connect_nav_button"),
            ) {
                Icon(Icons.Default.Hub, contentDescription = "MYRA Connect and PC Companion", tint = MyraTextSecondary)
            }
            IconButton(
                onClick = onOpenDiagnostics,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("diagnostics_nav_button"),
            ) {
                Icon(Icons.Default.MonitorHeart, contentDescription = "Permissions and Diagnostics", tint = MyraTextSecondary)
            }
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("settings_nav_button"),
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MyraTextPrimary)
            }
        }
    }
}

@Composable
private fun OrbCenterpiece(uiState: MainUiState, orbSize: androidx.compose.ui.unit.Dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        MyraOrbCanvas(
            orbState = uiState.orbState,
            amplitude = uiState.amplitude,
            orbSize = orbSize,
            modifier = Modifier.testTag("myra_orb"),
        )
        WaveformVisualizer(
            amplitude = uiState.amplitude,
            isActive = uiState.isActiveMode || uiState.orbState == OrbState.SPEAKING,
            accentColor = if (uiState.orbState == OrbState.SPEAKING) MyraPurple else MyraRed,
            modifier = Modifier
                .width(200.dp)
                .testTag("myra_waveform"),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = uiState.statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = MyraTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("status_text"),
        )
    }
}

@Composable
private fun QuickActionsStrip(
    isTorchOn: Boolean,
    onQuickCommand: (AppCommand) -> Unit,
    onOpenScanner: () -> Unit,
    onOpenCamera: () -> Unit,
    onOpenConnect: () -> Unit,
    onOpenMacros: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QuickActionChip(
            icon = if (isTorchOn) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff,
            label = if (isTorchOn) "Torch ON" else "Torch",
            testTag = "quick_torch",
            highlighted = isTorchOn,
            onClick = {
                onQuickCommand(
                    AppCommand(if (isTorchOn) AppCommand.FLASHLIGHT_OFF else AppCommand.FLASHLIGHT_ON),
                )
            },
        )
        QuickActionChip(
            icon = Icons.Default.LocalParking,
            label = "Save Park",
            testTag = "quick_save_parking",
            onClick = { onQuickCommand(AppCommand(AppCommand.SAVE_PARKING)) },
        )
        QuickActionChip(
            icon = Icons.Default.DirectionsCar,
            label = "Find Car",
            testTag = "quick_find_parking",
            onClick = { onQuickCommand(AppCommand(AppCommand.FIND_PARKING)) },
        )
        QuickActionChip(
            icon = Icons.Default.QrCodeScanner,
            label = "Scanner",
            testTag = "quick_scanner",
            onClick = onOpenScanner,
        )
        QuickActionChip(
            icon = Icons.Default.CameraAlt,
            label = "Vision",
            testTag = "quick_camera_vision",
            onClick = onOpenCamera,
        )
        QuickActionChip(
            icon = Icons.Default.Star,
            label = "Prime Call",
            testTag = "quick_prime_call",
            onClick = { onQuickCommand(AppCommand(AppCommand.PRIME_CALL, mapOf("index" to "0"))) },
        )
        QuickActionChip(
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            label = "Vol +",
            testTag = "quick_volume_up",
            onClick = { onQuickCommand(AppCommand(AppCommand.VOLUME_UP)) },
        )
        QuickActionChip(
            icon = Icons.Default.Hub,
            label = "PC / Sync",
            testTag = "quick_connect",
            onClick = onOpenConnect,
        )
        QuickActionChip(
            icon = Icons.Default.Star,
            label = "Macros",
            testTag = "quick_macros",
            onClick = onOpenMacros,
        )
    }
}

@Composable
private fun QuickActionChip(
    icon: ImageVector,
    label: String,
    testTag: String,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(16.dp),
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (highlighted) MyraPrimeCard else MyraSurface,
            labelColor = MyraTextPrimary,
            leadingIconContentColor = if (highlighted) MyraRed else MyraTextSecondary,
        ),
        border = AssistChipDefaults.assistChipBorder(
            enabled = true,
            borderColor = if (highlighted) MyraRed else MyraBorderSubtle,
        ),
        modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag(testTag),
    )
}

@Composable
private fun ChatHistoryList(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    if (messages.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MyraSurface.copy(alpha = 0.5f))
                .border(1.dp, MyraBorderSubtle, RoundedCornerShape(16.dp))
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Tap the mic to talk with MYRA in Hindi / English, or type a command below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MyraTextSecondary,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.testTag("chat_history_list"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(messages, key = { "${it.id}_${it.timestamp}" }) { msg ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start,
            ) {
                if (!msg.isUser) {
                    Box(
                        modifier = Modifier
                            .padding(top = 10.dp, end = 6.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MyraRed),
                    )
                }
                Surface(
                    color = if (msg.isUser) MyraPrimeCard else MyraSurface,
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (msg.isUser) 16.dp else 4.dp,
                        bottomEnd = if (msg.isUser) 4.dp else 16.dp,
                    ),
                    modifier = Modifier
                        .widthIn(max = 310.dp)
                        .border(
                            1.dp,
                            if (msg.isUser) MyraRed.copy(alpha = 0.55f) else MyraBorderSubtle,
                            RoundedCornerShape(
                                topStart = 16.dp,
                                topEnd = 16.dp,
                                bottomStart = if (msg.isUser) 16.dp else 4.dp,
                                bottomEnd = if (msg.isUser) 4.dp else 16.dp,
                            ),
                        ),
                ) {
                    val context = LocalContext.current
                    val linkPattern = remember { Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE) }
                    val annotatedMessage = remember(msg.text) {
                        buildAnnotatedString {
                            var cursor = 0
                            linkPattern.findAll(msg.text).forEach { match ->
                                val rawUrl = match.value
                                val url = rawUrl.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
                                val trailing = rawUrl.substring(url.length)
                                if (url.isNotBlank()) {
                                    append(msg.text.substring(cursor, match.range.first))
                                    pushStringAnnotation(tag = "MYRA_URL", annotation = url)
                                    withStyle(SpanStyle(color = MyraCyan, textDecoration = TextDecoration.Underline)) {
                                        append(url)
                                    }
                                    pop()
                                    append(trailing)
                                    cursor = match.range.last + 1
                                }
                            }
                            append(msg.text.substring(cursor))
                        }
                    }
                    ClickableText(
                        text = annotatedMessage,
                        style = MaterialTheme.typography.bodyLarge.copy(color = MyraTextPrimary),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        onClick = { offset ->
                            annotatedMessage.getStringAnnotations("MYRA_URL", offset, offset)
                                .firstOrNull()?.item?.let { url ->
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    }.onFailure {
                                        Toast.makeText(context, "लिंक नहीं खुल पाया: $url", Toast.LENGTH_LONG).show()
                                    }
                                }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TextCommandInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(
                    "Type command or message...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MyraTextHint,
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MyraRed,
                unfocusedBorderColor = MyraBorderSubtle,
                focusedContainerColor = MyraSurface,
                unfocusedContainerColor = MyraSurface,
                focusedTextColor = MyraTextPrimary,
                unfocusedTextColor = MyraTextPrimary,
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .weight(1f)
                .testTag("text_command_input"),
        )
        IconButton(
            onClick = onSend,
            enabled = value.isNotBlank(),
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(if (value.isNotBlank()) MyraRed else MyraSurfaceElevated)
                .testTag("send_text_button"),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send text command",
                tint = MyraTextPrimary,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MicActionControl(
    uiState: MainUiState,
    onToggleMic: () -> Unit,
    onInterrupt: () -> Unit,
) {
    val isLiveUnmuted = !uiState.isMuted && uiState.connectionState == ConnectionState.CONNECTED
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(if (isLiveUnmuted) MyraPrimeCard else MyraSurface)
                .border(
                    width = 2.dp,
                    color = if (isLiveUnmuted) MyraRed else MyraTextHint,
                    shape = CircleShape,
                )
                .combinedClickable(
                    onClick = onToggleMic,
                    onLongClick = onInterrupt,
                )
                .testTag("mic_button"),
        ) {
            Icon(
                imageVector = if (isLiveUnmuted) Icons.Default.Mic else Icons.Default.MicOff,
                contentDescription = if (isLiveUnmuted) "Mute microphone" else "Unmute microphone",
                tint = if (isLiveUnmuted) MyraRed else MyraTextSecondary,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Tap: Mic • Long press: Stop speaking",
            style = MaterialTheme.typography.labelSmall,
            color = MyraTextHint,
        )
    }
}

@Composable
private fun OtpGuardBanner(
    alert: OtpAlert,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MyraPrimeCard),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(1.dp, MyraAmber.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .testTag("otp_guard_banner"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Shield, contentDescription = "OTP Guard", tint = MyraAmber)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Local OTP Detected (${alert.sender}): ${alert.code}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MyraTextPrimary,
                )
                Text(
                    text = "Volatile RAM only (60s) — never saved or forwarded",
                    style = MaterialTheme.typography.labelSmall,
                    color = MyraAmber,
                )
            }
            IconButton(onClick = onCopy, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy OTP locally", tint = MyraTextPrimary)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss OTP", tint = MyraTextSecondary)
            }
        }
    }
}

@Composable
private fun IncomingCallBanner(
    callerName: String,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MyraPrimeCard),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .border(1.dp, MyraRed, RoundedCornerShape(16.dp))
            .testTag("incoming_call_banner"),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("INCOMING CALL", style = MaterialTheme.typography.labelSmall, color = MyraRed)
                Text(callerName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onAccept,
                colors = ButtonDefaults.buttonColors(containerColor = MyraGreen, contentColor = MyraVoid),
                modifier = Modifier.heightIn(min = 48.dp).testTag("accept_call_button"),
            ) {
                Icon(Icons.Default.Call, contentDescription = "Accept Call", modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Uthao")
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onReject,
                colors = ButtonDefaults.buttonColors(containerColor = MyraRed, contentColor = MyraTextPrimary),
                modifier = Modifier.heightIn(min = 48.dp).testTag("reject_call_button"),
            ) {
                Icon(Icons.Default.CallEnd, contentDescription = "Reject Call", modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Reject")
            }
        }
    }
}
