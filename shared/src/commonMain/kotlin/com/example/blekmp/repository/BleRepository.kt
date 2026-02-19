package com.example.blekmp.repository

import com.example.blekmp.model.BleDevice
import com.example.blekmp.model.ConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface BleRepository {
    val scannedDevices: StateFlow<List<BleDevice>>
    val connectionState: StateFlow<ConnectionState>
    val batteryLevel: Flow<Int?>  // Changed to nullable
    val heartRate: Flow<Int?>      // Changed to nullable

    fun startScan()
    fun stopScan()
    fun connect(device: BleDevice)
    fun disconnect()
    fun readBatteryLevel()
    fun cleanup()
}