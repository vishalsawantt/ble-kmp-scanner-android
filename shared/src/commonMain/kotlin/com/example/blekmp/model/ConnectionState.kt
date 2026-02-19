package com.example.blekmp.model

sealed class ConnectionState {

    /** No device connected, not scanning */
    object Disconnected : ConnectionState()

    /** Actively scanning for BLE devices nearby */
    object Scanning : ConnectionState()

    /** Attempting to connect to a selected device */
    data class Connecting(val device: BleDevice) : ConnectionState()

    /** Successfully connected to a device */
    data class Connected(val device: BleDevice) : ConnectionState()

    /** Connection lost, attempting to reconnect automatically */
    data class Reconnecting(val device: BleDevice, val attempt: Int) : ConnectionState()

    /** An error occurred during BLE operation */
    data class Error(val message: String) : ConnectionState()
}
