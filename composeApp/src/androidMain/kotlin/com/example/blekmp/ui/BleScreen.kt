package com.example.blekmp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.blekmp.model.BleDevice
import com.example.blekmp.model.ConnectionState

@Composable
fun BleScreen(viewModel: BleViewModel) {

    val scannedDevices by viewModel.scannedDevices.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val batteryLevel by viewModel.batteryLevel.collectAsState()
    val heartRate by viewModel.heartRate.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "KMP BLE Scanner",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        ConnectionStatusBanner(state = connectionState)

        Spacer(modifier = Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.startScan() },
                enabled = connectionState !is ConnectionState.Scanning
                        && connectionState !is ConnectionState.Connecting
                        && connectionState !is ConnectionState.Connected
            ) {
                Text("Scan")
            }

            if (connectionState is ConnectionState.Connected) {
                OutlinedButton(onClick = { viewModel.disconnect() }) {
                    Text("Disconnect")
                }
                OutlinedButton(onClick = { viewModel.refreshBattery() }) {
                    Text("Refresh Battery")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (connectionState is ConnectionState.Connected) {
            val device = (connectionState as ConnectionState.Connected).device
            ConnectedDeviceCard(
                device = device,
                batteryLevel = batteryLevel,
                heartRate = heartRate
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (connectionState is ConnectionState.Reconnecting) {
            val state = connectionState as ConnectionState.Reconnecting
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CD))
            ) {
                Text(
                    text = "Reconnecting to ${state.device.displayName}... (Attempt ${state.attempt}/5)",
                    modifier = Modifier.padding(12.dp),
                    color = Color(0xFF856404)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (scannedDevices.isNotEmpty()) {
            Text(
                text = "Nearby Devices (${scannedDevices.size})",
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(scannedDevices, key = { it.address }) { device ->
                    DeviceListItem(
                        device = device,
                        onClick = { viewModel.connectToDevice(device) }
                    )
                }
            }
        } else if (connectionState is ConnectionState.Scanning) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Scanning for devices...")
            }
        }
    }
}

@Composable
fun ConnectionStatusBanner(state: ConnectionState) {
    val (text, color) = when (state) {
        is ConnectionState.Disconnected  -> "Disconnected"    to Color(0xFF6C757D)
        is ConnectionState.Scanning      -> "Scanning..."     to Color(0xFF0D6EFD)
        is ConnectionState.Connecting    -> "Connecting..."   to Color(0xFFFFC107)
        is ConnectionState.Connected     -> "Connected"       to Color(0xFF198754)
        is ConnectionState.Reconnecting  -> "Reconnecting..." to Color(0xFFFF8C00)
        is ConnectionState.Error         -> "Error: ${state.message}" to Color(0xFFDC3545)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = color.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = text,
            color = color,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
fun ConnectedDeviceCard(
    device: BleDevice,
    batteryLevel: Int?,
    heartRate: Int?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (device.isConnectedInApp) "Connected in App" else "System Connected Device",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = if (device.isConnectedInApp) Color(0xFF198754) else Color(0xFF1565C0),
                modifier = Modifier.padding(bottom = 8.dp)
            )
            DeviceDetailRow("Name", device.displayName)
            DeviceDetailRow("Address", device.address)
            DeviceDetailRow("Signal (RSSI)", "${device.rssi} dBm")

            val batteryText = when {
                batteryLevel != null -> {
                    val emoji = when {
                        batteryLevel >= 80 -> "🔋"
                        batteryLevel >= 50 -> "⚡"
                        batteryLevel >= 20 -> "🔌"
                        else -> "⚠️"
                    }
                    "$emoji $batteryLevel%"
                }
                device.hasBatteryService -> "🔄 Reading..."
                else -> "Not Available"
            }

            DeviceDetailRow("Battery", batteryText)

            if (heartRate != null) {
                DeviceDetailRow("Heart Rate", "❤️ $heartRate BPM")
            }

            if (device.isSystemConnected && !device.isConnectedInApp) {
                Surface(
                    color = Color(0xFFE3F2FD),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(
                        text = "⚠️ This device is connected at system level, but not through this app. Tap 'Connect' to use it with this app.",
                        fontSize = 12.sp,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun DeviceDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = Color.Gray, fontSize = 14.sp)
        Text(text = value, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    }
}

@Composable
fun DeviceListItem(device: BleDevice, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                device.isConnectedInApp -> Color(0xFFC8E6C9) // Green for app-connected
                device.isSystemConnected && device.isAudioDevice -> Color(0xFFBBDEFB) // Light blue for system-connected audio
                device.isAudioDevice && device.isPaired -> Color(0xFFE3F2FD) // Light blue for paired audio
                device.isAudioDevice && !device.isPaired -> Color(0xFFFFF3CD) // Yellow for unpaired audio
                device.type != "BLE" -> Color(0xFFFFE0E0) // Light red for non-BLE
                else -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.displayName,
                    fontWeight = if (device.showAsConnected) FontWeight.Bold else FontWeight.Normal
                )
                Text(
                    text = device.address,
                    fontSize = 12.sp,
                    color = Color.Gray
                )

                // Show connection status badges
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = device.displayType,
                        fontSize = 10.sp,
                        color = when {
                            device.isConnectedInApp -> Color(0xFF2E7D32)
                            device.isSystemConnected -> Color(0xFF1565C0)
                            device.isAudioDevice && device.isPaired -> Color.Blue
                            device.isAudioDevice && !device.isPaired -> Color(0xFFB76E00)
                            else -> Color.Gray
                        }
                    )

                    if (device.isSystemConnected && !device.isConnectedInApp) {
                        Surface(
                            color = Color(0xFFE1F5FE),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = "System Connected",
                                fontSize = 8.sp,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (device.isConnectedInApp) {
                        Surface(
                            color = Color(0xFFC8E6C9),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = "App Connected",
                                fontSize = 8.sp,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${device.rssi} dBm",
                    fontSize = 12.sp,
                    color = when {
                        device.rssi >= -60 -> Color(0xFF198754)
                        device.rssi >= -80 -> Color(0xFFFFC107)
                        else -> Color(0xFFDC3545)
                    }
                )

                if (device.batteryLevel != null) {
                    Text(
                        text = "${device.batteryLevel}%",
                        fontSize = 10.sp,
                        color = Color(0xFF1976D2)
                    )
                }
            }
        }
    }
}