package com.example.blekmp.model

/**
 * Shared data model representing a BLE device.
 * Used across both Android and iOS platforms.
 *
 * @param name         Device name (nullable if not advertised)
 * @param address      MAC address on Android / UUID identifier on iOS
 * @param rssi         Signal strength in dBm
 * @param type         Device type (BLE, Classic, Dual, etc.)
 * @param isAudioDevice Whether this is an audio device (headphones, earbuds, etc.)
 * @param isPaired     Whether the device is already paired with the phone
 * @param isConnected  Whether the device is currently connected
 * @param batteryLevel Battery percentage (0–100), null if not yet read
 * @param heartRate    Optional heart rate in BPM from custom GATT service
 * @param hasBatteryService Whether the device has battery service
 * @param hasHeartRateService Whether the device has heart rate service
 */
data class BleDevice(
    val name: String?,
    val address: String,
    val rssi: Int,
    val type: String = "BLE",
    val isAudioDevice: Boolean = false,
    val isPaired: Boolean = false,
    val isConnected: Boolean = false,
    val batteryLevel: Int? = null,
    val heartRate: Int? = null,
    val hasBatteryService: Boolean = false,
    val hasHeartRateService: Boolean = false
) {
    /** Returns a display-friendly name, falling back to address */
    val displayName: String
        get() = if (!name.isNullOrBlank()) name else "Unknown Device"

    /** Returns a display-friendly type with compatibility info */
    val displayType: String
        get() = when {
            isAudioDevice && isPaired -> "$type (Paired)"
            isAudioDevice && !isPaired -> "$type (Not Paired)"
            type == "BLE" -> type
            else -> "$type (May not work)"
        }
}