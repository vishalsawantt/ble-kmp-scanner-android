package com.example.blekmp.model
// BleDevice.kt
data class BleDevice(
    val name: String?,
    val address: String,
    val rssi: Int,
    val type: String = "BLE",
    val isAudioDevice: Boolean = false,
    val isPaired: Boolean = false,
    val isConnectedInApp: Boolean = false,  // NEW: Connected via YOUR app
    val isSystemConnected: Boolean = false, // NEW: Connected at system level
    val batteryLevel: Int? = null,
    val heartRate: Int? = null,
    val hasBatteryService: Boolean = false,
    val hasHeartRateService: Boolean = false
) {
    val displayName: String
        get() = if (!name.isNullOrBlank()) name else "Unknown Device"

    val displayType: String
        get() = when {
            isAudioDevice && isPaired -> "$type (Paired)"
            isAudioDevice && !isPaired -> "$type (Not Paired)"
            type == "BLE" -> type
            else -> "$type (May not work)"
        }

    // Helper for UI to show connection status
    val showAsConnected: Boolean
        get() = isConnectedInApp || (isAudioDevice && isSystemConnected)
}