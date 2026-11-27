package com.myra.assistant.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.myra.assistant.service.AccessibilityHelperService

@Composable
fun MacroAutomationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var serviceConnected by remember { mutableStateOf(AccessibilityHelperService.instance != null) }
    var macroName by remember { mutableStateOf("") }
    var macroNames by remember { mutableStateOf(AccessibilityHelperService.instance?.availableMacroNames().orEmpty()) }
    var recording by remember { mutableStateOf(AccessibilityHelperService.instance?.isMacroRecording() == true) }
    var pendingReplay by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Macros are stored temporarily in memory on this device.") }
    val accessibilityEnabled = AccessibilityHelperService.isEnabled(context)

    fun refresh() {
        val current = AccessibilityHelperService.instance
        macroNames = current?.availableMacroNames().orEmpty()
        recording = current?.isMacroRecording() == true
        serviceConnected = current != null
    }

    LaunchedEffect(Unit) {
        while (true) {
            refresh()
            delay(700L)
        }
    }

    if (pendingReplay != null) {
        AlertDialog(
            onDismissRequest = { pendingReplay = null },
            title = { Text("Run this macro?") },
            text = { Text("MYRA will open the recorded app and replay up to 30 recorded steps. Review the actions first. Do not use macros for payments, messages, calls, account changes, or other sensitive actions.") },
            confirmButton = {
                TextButton(onClick = {
                    val name = pendingReplay
                    pendingReplay = null
                    val started = name?.let { AccessibilityHelperService.instance?.launchAndReplayMacro(it) } == true
                    status = if (started) "Opening the target app. Playback starts only after MYRA verifies the foreground app." else "Could not start playback. Check Accessibility permission and the target app."
                    refresh()
                }) { Text("Confirm and run") }
            },
            dismissButton = { TextButton(onClick = { pendingReplay = null }) { Text("Cancel") } },
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Macro Automation", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("Back") }
        }
        Text(
            "Record simple visible clicks, scrolling and Back actions in one app. Text entry is never recorded.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!accessibilityEnabled || !serviceConnected) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Accessibility service is not connected.")
                    Text("Enable MYRA Accessibility in Android Settings, then return here.")
                    Button(onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }) { Text("Open Accessibility Settings") }
                }
            }
        } else {
            OutlinedTextField(
                value = macroName,
                onValueChange = { macroName = it.take(40) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Macro name") },
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !recording && macroName.isNotBlank(),
                    onClick = {
                        val started = AccessibilityHelperService.instance?.beginMacroRecording(macroName.trim()) == true
                        status = if (started) "Recording started. Switch to the target app and perform safe clicks or scrolling. Return here and tap Stop." else "Could not start recording. Keep MYRA in the foreground and try again."
                        refresh()
                    },
                ) { Text("Start recording") }
                OutlinedButton(
                    enabled = recording,
                    onClick = {
                        val saved = AccessibilityHelperService.instance?.stopMacroRecording() == true
                        status = if (saved) "Macro saved in memory." else "No safe actions were recorded."
                        refresh()
                    },
                ) { Text("Stop") }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Text(status, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall)
        }
        Text("Saved macros (${macroNames.size}/10)", style = MaterialTheme.typography.titleMedium)
        if (macroNames.isEmpty()) {
            Text("No macros saved yet.", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(macroNames, key = { it }) { name ->
                    Card {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = { pendingReplay = name }) { Text("Run") }
                            TextButton(onClick = {
                                AccessibilityHelperService.instance?.removeMacro(name)
                                status = "Deleted $name"
                                refresh()
                            }) { Text("Delete") }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "Safety: up to 30 steps per macro; max 10 macros. Playback is bound to the original app and stops if that app changes. Macros are temporary and are lost when the service/app process restarts.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
