package com.example.blekmp.repository

/**
 * Shared utility object for parsing GATT characteristic byte arrays.
 * Lives in commonMain so both Android and iOS use the same parsing logic.
 *
 * GATT UUIDs used:
 *  - Battery Service:    0x180F
 *  - Battery Level Char: 0x2A19  → single byte, value 0–100
 *  - Heart Rate Service: 0x180D
 *  - Heart Rate Char:    0x2A37  → flags byte + 1–2 byte HR value
 */
object GattDataParser {

    /**
     * Parse battery level from GATT characteristic byte array.
     * Battery Level characteristic (0x2A19) is a single unsigned byte: 0–100%.
     *
     * @param bytes Raw bytes from the characteristic
     * @return Battery percentage (0–100) or null if data is invalid
     */
    fun parseBatteryLevel(bytes: ByteArray): Int? {
        if (bytes.isEmpty()) return null
        val level = bytes[0].toInt() and 0xFF   // treat as unsigned
        return if (level in 0..100) level else null
    }

    /**
     * Parse heart rate from GATT characteristic byte array.
     * Heart Rate Measurement characteristic (0x2A37) format:
     *   Byte 0: Flags
     *     - Bit 0: 0 = HR value is UINT8, 1 = HR value is UINT16
     *   Byte 1 (or Bytes 1–2): Heart rate value
     *
     * @param bytes Raw bytes from the characteristic
     * @return Heart rate in BPM or null if data is invalid
     */
    fun parseHeartRate(bytes: ByteArray): Int? {
        if (bytes.isEmpty()) return null
        val flags = bytes[0].toInt()
        val isUint16 = (flags and 0x01) != 0

        return if (isUint16) {
            // HR value spans 2 bytes (little-endian)
            if (bytes.size < 3) return null
            (bytes[1].toInt() and 0xFF) or ((bytes[2].toInt() and 0xFF) shl 8)
        } else {
            // HR value is a single byte
            if (bytes.size < 2) return null
            bytes[1].toInt() and 0xFF
        }
    }

    /**
     * Filter BLE devices by name prefix.
     * Shared filtering logic used during scanning.
     *
     * @param deviceName  The device's advertised name
     * @param filterPrefix The prefix to filter by (case-insensitive)
     * @return true if the device matches the filter
     */
    fun matchesFilter(deviceName: String?, filterPrefix: String): Boolean {
        if (filterPrefix.isBlank()) return true
        return deviceName?.startsWith(filterPrefix, ignoreCase = true) == true
    }
}
