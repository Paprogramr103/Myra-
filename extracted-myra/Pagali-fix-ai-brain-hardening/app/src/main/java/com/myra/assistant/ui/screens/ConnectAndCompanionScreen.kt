package com.myra.assistant.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.myra.assistant.data.db.PairedDeviceEntity
import com.myra.assistant.network.MyraConnectState
import com.myra.assistant.network.PairingCrypto
import com.myra.assistant.network.PcCompanionState
import com.myra.assistant.ui.theme.MyraBorderSubtle
import com.myra.assistant.ui.theme.MyraCyan
import com.myra.assistant.ui.theme.MyraGreen
import com.myra.assistant.ui.theme.MyraRed
import com.myra.assistant.ui.theme.MyraSurface
import com.myra.assistant.ui.theme.MyraTextPrimary
import com.myra.assistant.ui.theme.MyraTextSecondary
import com.myra.assistant.ui.theme.MyraVoid

@Composable
fun ConnectAndCompanionScreen(
    pcState: PcCompanionState,
    connectState: MyraConnectState,
    pairedDevices: List<PairedDeviceEntity>,
    onBack: () -> Unit,
    onTogglePcCompanion: (Boolean) -> Unit,
    onRegeneratePcPin: () -> Unit,
    onToggleMyraConnect: (Boolean) -> Unit,
    onPairAndSendPeer: (host: String, port: Int, pin: String, name: String, message: String) -> Unit,
    onRemovePairedDevice: (String) -> Unit,
) {
    var peerHost by remember { mutableStateOf("") }
    var peerPort by remember { mutableStateOf("") }
    var peerPin by remember { mutableStateOf("") }
    var peerName by remember { mutableStateOf("Friend MYRA") }
    var peerMsg by remember { mutableStateOf("Hello from MYRA Connect!") }

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
                modifier = Modifier.size(48.dp).testTag("connect_back_button"),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MyraTextPrimary)
            }
            Text("MYRA CONNECT & PC", style = MaterialTheme.typography.titleLarge, color = MyraRed)
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. PC Companion Section
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MyraSurface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, MyraBorderSubtle, RoundedCornerShape(16.dp)),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Computer, contentDescription = "PC Companion", tint = MyraRed)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text("PC COMPANION SERVER", style = MaterialTheme.typography.titleMedium, color = MyraTextPrimary)
                                    Text("LAN HTTP + WebSocket (PIN Auth)", style = MaterialTheme.typography.labelSmall, color = MyraTextSecondary)
                                }
                            }
                            Switch(
                                checked = pcState.isRunning,
                                onCheckedChange = onTogglePcCompanion,
                                colors = SwitchDefaults.colors(checkedThumbColor = MyraTextPrimary, checkedTrackColor = MyraRed),
                                modifier = Modifier.testTag("toggle_pc_companion"),
                            )
                        }

                        if (pcState.isRunning) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Open in PC browser: http://${pcState.hostIp}:${pcState.port}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MyraCyan,
                            )
                            Text(
                                "WebSocket: ws://${pcState.hostIp}:${pcState.port}/ws",
                                style = MaterialTheme.typography.labelSmall,
                                color = MyraTextSecondary,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "PAIRING PIN: ${pcState.pairingPin}",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MyraGreen,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedButton(onClick = onRegeneratePcPin, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Icon(Icons.Default.Refresh, contentDescription = "New PIN", modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("New PIN")
                                }
                            }

                            val qrBitmap = remember(pcState.hostIp, pcState.port, pcState.pairingPin) {
                                PairingCrypto.generateQrBitmap(
                                    "http://${pcState.hostIp}:${pcState.port}/?pin=${pcState.pairingPin}",
                                    280,
                                )
                            }
                            qrBitmap?.let { bmp ->
                                Spacer(Modifier.height(10.dp))
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "PC Companion Pairing QR Code",
                                    modifier = Modifier
                                        .size(160.dp)
                                        .align(Alignment.CenterHorizontally),
                                )
                            }

                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = if (pcState.authenticatedClients.isEmpty()) {
                                    "Waiting for PC connection..."
                                } else {
                                    "Connected PCs: ${pcState.authenticatedClients.joinToString()}"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MyraTextPrimary,
                            )
                        }
                    }
                }
            }

            // 2. MYRA Connect (Phone-to-Phone LAN NSD + HMAC)
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MyraSurface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, MyraBorderSubtle, RoundedCornerShape(16.dp)),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Devices, contentDescription = "MYRA Connect", tint = MyraCyan)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text("MYRA CONNECT (LAN NSD)", style = MaterialTheme.typography.titleMedium, color = MyraTextPrimary)
                                    Text("HMAC-SHA256 Authenticated Peer Sync", style = MaterialTheme.typography.labelSmall, color = MyraTextSecondary)
                                }
                            }
                            Switch(
                                checked = connectState.isHosting,
                                onCheckedChange = onToggleMyraConnect,
                                colors = SwitchDefaults.colors(checkedThumbColor = MyraTextPrimary, checkedTrackColor = MyraCyan),
                                modifier = Modifier.testTag("toggle_myra_connect"),
                            )
                        }

                        if (connectState.isHosting) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "This device: ${connectState.localIp}:${connectState.localPort} • PIN: ${connectState.pairingPin}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MyraGreen,
                            )
                            if (connectState.discoveredPeers.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text("Nearby MYRA Peers (tap to autofill):", style = MaterialTheme.typography.labelSmall, color = MyraTextSecondary)
                                connectState.discoveredPeers.forEach { peer ->
                                    Text(
                                        text = "• ${peer.serviceName} (${peer.host}:${peer.port})",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MyraCyan,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                peerHost = peer.host
                                                peerPort = peer.port.toString()
                                                peerName = peer.serviceName
                                            }
                                            .padding(vertical = 6.dp),
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = peerHost,
                            onValueChange = { peerHost = it },
                            label = { Text("Peer IP Address") },
                            singleLine = true,
                            colors = companionFieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = peerPort,
                                onValueChange = { peerPort = it.filter { c -> c.isDigit() } },
                                label = { Text("Port") },
                                singleLine = true,
                                colors = companionFieldColors(),
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = peerPin,
                                onValueChange = { peerPin = it.filter { c -> c.isDigit() } },
                                label = { Text("6-digit PIN") },
                                singleLine = true,
                                colors = companionFieldColors(),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = peerMsg,
                            onValueChange = { peerMsg = it },
                            label = { Text("Message / Command (OTPs blocked)") },
                            singleLine = true,
                            colors = companionFieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                val portInt = peerPort.toIntOrNull() ?: 0
                                if (peerHost.isNotBlank() && portInt > 0 && peerPin.length == 6) {
                                    onPairAndSendPeer(peerHost.trim(), portInt, peerPin, peerName, peerMsg)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MyraRed),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pair_peer_button"),
                        ) {
                            Text("Pair & Send Encrypted HMAC Message")
                        }
                    }
                }
            }

            // 3. Saved Paired Devices
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MyraSurface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, MyraBorderSubtle, RoundedCornerShape(16.dp)),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("PAIRED DEVICES (${pairedDevices.size})", style = MaterialTheme.typography.labelMedium, color = MyraRed)
                        Spacer(Modifier.height(6.dp))
                        if (pairedDevices.isEmpty()) {
                            Text("No paired devices yet.", style = MaterialTheme.typography.bodyMedium, color = MyraTextSecondary)
                        } else {
                            pairedDevices.forEach { dev ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("${dev.name} (${dev.deviceType})", color = MyraTextPrimary, style = MaterialTheme.typography.titleMedium)
                                        Text("${dev.host}:${dev.port}", color = MyraTextSecondary, style = MaterialTheme.typography.labelSmall)
                                    }
                                    IconButton(onClick = { onRemovePairedDevice(dev.deviceId) }, modifier = Modifier.size(48.dp)) {
                                        Icon(Icons.Default.Delete, contentDescription = "Unpair ${dev.name}", tint = MyraRed)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun companionFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MyraRed,
    unfocusedBorderColor = MyraBorderSubtle,
    focusedLabelColor = MyraRed,
    unfocusedLabelColor = MyraTextSecondary,
    focusedTextColor = MyraTextPrimary,
    unfocusedTextColor = MyraTextPrimary,
)
