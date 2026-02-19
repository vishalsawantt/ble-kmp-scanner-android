package com.example.blekmp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.blekmp.ble.AndroidBleRepository
import com.example.blekmp.model.BleDevice
import com.example.blekmp.model.ConnectionState
import com.example.blekmp.repository.BleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BleViewModel(private val repository: BleRepository) : ViewModel() {

    val scannedDevices: StateFlow<List<BleDevice>> = repository.scannedDevices
    val connectionState: StateFlow<ConnectionState> = repository.connectionState

    private val _batteryLevel = MutableStateFlow<Int?>(null)
    val batteryLevel: StateFlow<Int?> = _batteryLevel.asStateFlow()

    private val _heartRate = MutableStateFlow<Int?>(null)
    val heartRate: StateFlow<Int?> = _heartRate.asStateFlow()

    init {
        viewModelScope.launch {
            repository.batteryLevel.collect { level ->
                _batteryLevel.value = level
            }
        }
        viewModelScope.launch {
            repository.heartRate.collect { bpm ->
                _heartRate.value = bpm
            }
        }
    }

    fun debugBattery() {
        // This will only work if repository is AndroidBleRepository
        // Use with caution - this is just for debugging
        if (repository is AndroidBleRepository) {
            repository.testBatteryRead()
        }
    }

    fun startScan() = repository.startScan()
    fun stopScan() = repository.stopScan()
    fun connectToDevice(device: BleDevice) = repository.connect(device)
    fun disconnect() = repository.disconnect()
    fun refreshBattery() = repository.readBatteryLevel()

    override fun onCleared() {
        super.onCleared()
        repository.cleanup()
    }
}