package com.example.blekmp.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.example.blekmp.model.BleDevice
import com.example.blekmp.model.ConnectionState
import com.example.blekmp.repository.BleRepository
import com.example.blekmp.repository.GattDataParser
import kotlinx.coroutines.flow.*
import java.io.IOException
import java.util.UUID

@SuppressLint("MissingPermission")
class AndroidBleRepository(private val context: Context) : BleRepository {

    companion object {
        private const val TAG = "AndroidBleRepo"
        val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")
        val BATTERY_LEVEL_UUID: UUID = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")
        val HEART_RATE_SERVICE_UUID: UUID = UUID.fromString("0000180D-0000-1000-8000-00805F9B34FB")
        val HEART_RATE_CHAR_UUID: UUID = UUID.fromString("00002A37-0000-1000-8000-00805F9B34FB")
        val CLIENT_CHAR_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        // UUID for Bluetooth Headset profile (for audio devices)
        private val HEADSET_UUID: UUID = UUID.fromString("00001108-0000-1000-8000-00805F9B34FB")
        private val A2DP_UUID: UUID = UUID.fromString("0000110A-0000-1000-8000-00805F9B34FB")
        private val RFCOMM_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        private const val MAX_RECONNECT_ATTEMPTS = 2
        private const val RECONNECT_DELAY_MS = 2000L
    }

    private val systemConnectedDevices = mutableSetOf<String>() // Track system-connected devices

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var reconnectAttempts = 0
    private var lastConnectedDevice: BleDevice? = null
    private var isManualDisconnect = false
    private var connectionType = "BLE" // "BLE" or "CLASSIC"
    private var isConnected = false

    private val _scannedDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    override val scannedDevices: StateFlow<List<BleDevice>> = _scannedDevices.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _batteryLevel = MutableStateFlow<Int?>(null)
    override val batteryLevel: Flow<Int?> = _batteryLevel.asStateFlow()

    private val _heartRate = MutableStateFlow<Int?>(null)
    override val heartRate: Flow<Int?> = _heartRate.asStateFlow()

    // Broadcast receiver for Bluetooth device connection state changes
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device: BluetoothDevice? = getDeviceFromIntent(intent)
                    device?.let {
                        Log.d(TAG, "System connected to device: ${it.name} (${it.address})")
                        systemConnectedDevices.add(it.address)

                        // Update any matching device in scanned list
                        updateDeviceSystemConnection(it.address, true)
                    }
                }

                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device: BluetoothDevice? = getDeviceFromIntent(intent)
                    device?.let {
                        Log.d(TAG, "System disconnected from device: ${it.name} (${it.address})")
                        systemConnectedDevices.remove(it.address)

                        // Update any matching device in scanned list
                        updateDeviceSystemConnection(it.address, false)
                    }
                }

                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device: BluetoothDevice? = getDeviceFromIntent(intent)
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                    device?.let {
                        when (bondState) {
                            BluetoothDevice.BOND_BONDED -> {
                                Log.d(TAG, "Device bonded: ${it.name}")
                                updateDevicePairedStatus(it.address, true)
                            }
                            BluetoothDevice.BOND_NONE -> {
                                Log.d(TAG, "Device unbonded: ${it.name}")
                                updateDevicePairedStatus(it.address, false)
                            }
                        }
                    }
                }
            }
        }
    }

    // Helper to get device from intent (compatible with all Android versions)
    private fun getDeviceFromIntent(intent: Intent): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    // Update a device's system connection status in the scanned list
    // Update a device's system connection status in the scanned list
    private fun updateDeviceSystemConnection(address: String, connected: Boolean) {
        val currentList = _scannedDevices.value.toMutableList()
        val index = currentList.indexOfFirst { it.address == address }
        if (index >= 0) {
            val oldDevice = currentList[index]
            // FIXED: Don't use lastConnectedDevice here, use oldDevice
            val updatedDevice = oldDevice.copy(isSystemConnected = connected)  // Changed this line
            currentList[index] = updatedDevice
            _scannedDevices.value = currentList
            Log.d(TAG, "Updated system connection for $address: $connected")
        }
    }

    // Update a device's paired status in the scanned list
    private fun updateDevicePairedStatus(address: String, paired: Boolean) {
        val currentList = _scannedDevices.value.toMutableList()
        val index = currentList.indexOfFirst { it.address == address }
        if (index >= 0) {
            val oldDevice = currentList[index]
            val updatedDevice = oldDevice.copy(isPaired = paired)
            currentList[index] = updatedDevice
            _scannedDevices.value = currentList
            Log.d(TAG, "Updated paired status for $address: $paired")
        }
    }

    init {
        // Register broadcast receiver for Bluetooth events
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        context.registerReceiver(bluetoothReceiver, filter)
    }

    private fun ensureBluetoothEnabled(): Boolean {
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not supported")
            _connectionState.value = ConnectionState.Error("Bluetooth not supported")
            return false
        }

        if (!bluetoothAdapter.isEnabled) {
            Log.d(TAG, "Bluetooth is disabled, requesting enable...")
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBtIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(enableBtIntent)
            return false
        }
        return true
    }

    private fun isDevicePaired(address: String): Boolean {
        val pairedDevices = bluetoothAdapter?.bondedDevices
        return pairedDevices?.any { it.address == address } == true
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val rssi = result.rssi

            val deviceType = when (device.type) {
                BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic Bluetooth"
                BluetoothDevice.DEVICE_TYPE_LE -> "BLE"
                BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual Mode (Audio)"
                else -> "Unknown"
            }

            val isPaired = isDevicePaired(device.address)
            val isSystemConnected = systemConnectedDevices.contains(device.address)
            val isAppConnected = isConnected && device.address == lastConnectedDevice?.address

            Log.d(TAG, "Device ${device.name} - System connected: $isSystemConnected, App connected: $isAppConnected")

            val bleDevice = BleDevice(
                name = device.name,
                address = device.address,
                rssi = rssi,
                type = deviceType,
                isAudioDevice = deviceType.contains("Dual") || deviceType.contains("Classic"),
                isPaired = isPaired,
                isConnectedInApp = isConnected && device.address == lastConnectedDevice?.address,  // <-- FIXED
                isSystemConnected = systemConnectedDevices.contains(device.address),  // <-- ADD THIS
                hasBatteryService = false,
                hasHeartRateService = false
            )

            val currentList = _scannedDevices.value.toMutableList()
            val existingIndex = currentList.indexOfFirst { it.address == bleDevice.address }
            if (existingIndex >= 0) {
                // Preserve battery level and service info if they exist
                val existing = currentList[existingIndex]
                val mergedDevice = bleDevice.copy(
                    batteryLevel = existing.batteryLevel,
                    hasBatteryService = existing.hasBatteryService,
                    hasHeartRateService = existing.hasHeartRateService
                )
                currentList[existingIndex] = mergedDevice
            } else {
                currentList.add(bleDevice)
            }
            _scannedDevices.value = currentList
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
            val errorMessage = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Application registration failed"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                else -> "Unknown error: $errorCode"
            }
            _connectionState.value = ConnectionState.Error("Scan failed: $errorMessage")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "GATT Connection state change: status=$status, newState=$newState")

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "GATT connection error: $status")
                handleConnectionFailure()
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Device connected successfully via GATT")
                    reconnectAttempts = 0
                    isManualDisconnect = false
                    isConnected = true
                    // Discover services after connection
                    gatt.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Device disconnected via GATT")
                    isConnected = false
                    if (!isManualDisconnect) {
                        handleDisconnection()
                    } else {
                        _connectionState.value = ConnectionState.Disconnected
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered successfully")
                val device = lastConnectedDevice ?: return

                val hasBatteryService = gatt.getService(BATTERY_SERVICE_UUID) != null
                val hasHeartRateService = gatt.getService(HEART_RATE_SERVICE_UUID) != null

                Log.d(TAG, "Device services - Battery: $hasBatteryService, HeartRate: $hasHeartRateService")

                val deviceName = gatt.device.name ?: device.name
                val updatedDevice = device.copy(
                    name = deviceName,
                    hasBatteryService = hasBatteryService,
                    hasHeartRateService = hasHeartRateService,
                    isConnectedInApp = true,  // <-- FIXED
                    batteryLevel = null
                )

                _connectionState.value = ConnectionState.Connected(updatedDevice)

                if (hasBatteryService) {
                    Log.d(TAG, "Battery service found, setting up notifications")
                    setupBatteryNotifications(gatt)
                    // Also read immediately
                    readBatteryLevel()
                    startBatteryUpdates()
                    readBatteryLevel()
                } else {
                    Log.d(TAG, "No battery service available on this device")
                    // Show default battery value or hide
                    _batteryLevel.value = null
                }

                if (hasHeartRateService) {
                    enableHeartRateNotifications(gatt)
                }
            } else {
                Log.e(TAG, "Service discovery failed with status: $status")
                _connectionState.value = ConnectionState.Error("Service discovery failed")
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Characteristic read successful: ${characteristic.uuid}")
                handleCharacteristicData(characteristic.uuid, value)
            } else {
                Log.e(TAG, "Characteristic read failed: ${characteristic.uuid}, status=$status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            Log.d(TAG, "Characteristic changed notification: ${characteristic.uuid}")
            handleCharacteristicData(characteristic.uuid, characteristic.value)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            Log.d(TAG, "Descriptor write: ${descriptor.uuid}, status=$status")
        }
    }

    override fun startScan() {
        Log.d(TAG, "startScan called")

        if (!ensureBluetoothEnabled()) {
            return
        }

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "Bluetooth LE scanner is null")
            _connectionState.value = ConnectionState.Error("BLE scanner not available")
            return
        }

        _scannedDevices.value = emptyList()
        _connectionState.value = ConnectionState.Scanning

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, scanCallback)
            Log.d(TAG, "Scan started successfully")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception during scan", e)
            _connectionState.value = ConnectionState.Error("Permission denied")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting scan", e)
            _connectionState.value = ConnectionState.Error("Failed to start scan: ${e.message}")
        }
    }

    override fun stopScan() {
        Log.d(TAG, "stopScan called")
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan", e)
        }

        if (_connectionState.value is ConnectionState.Scanning) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    override fun connect(device: BleDevice) {
        Log.d(TAG, "connect called for device: ${device.displayName}")

        if (!ensureBluetoothEnabled()) {
            return
        }

        stopScan()

        // If there was a previous app-connected device, mark it as disconnected
        if (lastConnectedDevice != null) {
            updateAppConnection(lastConnectedDevice!!.address, false)
        }

        lastConnectedDevice = device
        reconnectAttempts = 0
        isManualDisconnect = false

        // For audio devices (headphones, speakers)
        if (device.isAudioDevice) {
            Log.d(TAG, "Audio device detected: ${device.displayName}")

            if (!device.isPaired) {
                Log.d(TAG, "Device is not paired. Please pair first")
                _connectionState.value = ConnectionState.Error("Please pair the device first in system Bluetooth settings")
                val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            }

            _connectionState.value = ConnectionState.Connecting(device)

            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                // Mark as app-connected
                updateAppConnection(device.address, true)

                // FIXED: Create updated device correctly
                val updatedDevice = device.copy(
                    isConnectedInApp = true,  // Changed from isConnected
                    hasBatteryService = false
                )

                _connectionState.value = ConnectionState.Connected(updatedDevice)
                isConnected = true

                readBatteryLevelFromAudioDevice(bluetoothAdapter?.getRemoteDevice(device.address)!!)
            }, 1500)

        } else {
            connectionType = "BLE"
            connectGatt(device)
        }
    }

    // Helper to update app connection status in scanned list
    private fun updateAppConnection(address: String, connected: Boolean) {
        val currentList = _scannedDevices.value.toMutableList()
        val index = currentList.indexOfFirst { it.address == address }
        if (index >= 0) {
            val oldDevice = currentList[index]
            val updatedDevice = oldDevice.copy(isConnectedInApp = connected)
            currentList[index] = updatedDevice
            _scannedDevices.value = currentList
            Log.d(TAG, "Updated app connection for $address: $connected")
        }
    }

    private fun readBatteryLevelFromAudioDevice(device: BluetoothDevice) {
        Log.d(TAG, "Attempting to read battery from audio device: ${device.name}")

        // First, check if this is a modern audio device with BLE battery service
        // Try to connect via BLE specifically for battery, but with a short timeout
        try {
            // Use TRANSPORT_LE to force BLE connection
            val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTED -> {
                                Log.d(TAG, "BLE connected to audio device for battery")
                                gatt.discoverServices()
                            }
                            BluetoothProfile.STATE_DISCONNECTED -> {
                                Log.d(TAG, "BLE disconnected from audio device")
                                gatt.close()

                                // If we couldn't get battery via BLE, mark as unavailable
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    _batteryLevel.value = null
                                    val currentState = _connectionState.value
                                    if (currentState is ConnectionState.Connected) {
                                        val updatedDevice = currentState.device.copy(
                                            batteryLevel = null,
                                            hasBatteryService = false
                                        )
                                        _connectionState.value = ConnectionState.Connected(updatedDevice)
                                    }
                                }
                            }
                        }
                    }

                    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            val batteryService = gatt.getService(BATTERY_SERVICE_UUID)
                            if (batteryService != null) {
                                val batteryChar = batteryService.getCharacteristic(BATTERY_LEVEL_UUID)
                                if (batteryChar != null) {
                                    Log.d(TAG, "Battery service found, reading...")
                                    gatt.readCharacteristic(batteryChar)
                                } else {
                                    Log.d(TAG, "Battery characteristic not found")
                                    gatt.disconnect()
                                }
                            } else {
                                Log.d(TAG, "No battery service on this audio device")
                                gatt.disconnect()
                            }
                        } else {
                            gatt.disconnect()
                        }
                    }

                    override fun onCharacteristicRead(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        value: ByteArray,
                        status: Int
                    ) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            val level = value.firstOrNull()?.toInt()?.and(0xFF)
                            if (level != null && level in 0..100) {
                                Log.d(TAG, "✅ Battery level from audio device: $level%")
                                _batteryLevel.value = level

                                // Update the device in connection state
                                val currentState = _connectionState.value
                                if (currentState is ConnectionState.Connected) {
                                    val updatedDevice = currentState.device.copy(
                                        batteryLevel = level,
                                        hasBatteryService = true
                                    )
                                    _connectionState.value = ConnectionState.Connected(updatedDevice)
                                }
                            }
                        }
                        // Disconnect after reading
                        gatt.disconnect()
                        gatt.close()
                    }
                }, BluetoothDevice.TRANSPORT_LE) // Force LE transport
            } else {
                // For older Android versions, try regular connection
                device.connectGatt(context, false, object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            gatt.discoverServices()
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            gatt.close()
                        }
                    }

                    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            val batteryService = gatt.getService(BATTERY_SERVICE_UUID)
                            if (batteryService != null) {
                                val batteryChar = batteryService.getCharacteristic(BATTERY_LEVEL_UUID)
                                batteryChar?.let { gatt.readCharacteristic(it) }
                            } else {
                                gatt.disconnect()
                            }
                        } else {
                            gatt.disconnect()
                        }
                    }

                    override fun onCharacteristicRead(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        value: ByteArray,
                        status: Int
                    ) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            val level = value.firstOrNull()?.toInt()?.and(0xFF)
                            level?.let { _batteryLevel.value = it }
                        }
                        gatt.disconnect()
                        gatt.close()
                    }
                })
            }

            // Set a timeout for the connection
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    gatt?.disconnect()
                    gatt?.close()
                } catch (e: Exception) {
                    // Ignore
                }
            }, 5000) // 5 second timeout

        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception reading battery", e)
            showBatteryNotAvailable()
        } catch (e: Exception) {
            Log.e(TAG, "Error reading battery from audio device", e)
            showBatteryNotAvailable()
        }
    }

    private fun showBatteryNotAvailable() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            _batteryLevel.value = null
            val currentState = _connectionState.value
            if (currentState is ConnectionState.Connected) {
                val updatedDevice = currentState.device.copy(
                    batteryLevel = null,
                    hasBatteryService = false
                )
                _connectionState.value = ConnectionState.Connected(updatedDevice)
            }
        }
    }

    private fun connectGatt(device: BleDevice) {
        connectionType = "BLE"
        _connectionState.value = ConnectionState.Connecting(device)

        try {
            val remoteDevice = bluetoothAdapter?.getRemoteDevice(device.address)
            if (remoteDevice == null) {
                Log.e(TAG, "Remote device is null")
                _connectionState.value = ConnectionState.Error("Device not found")
                return
            }

            bluetoothGatt = remoteDevice.connectGatt(context, false, gattCallback)
            Log.d(TAG, "connectGatt called")
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception during connect", e)
            _connectionState.value = ConnectionState.Error("Permission denied")
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to device", e)
            _connectionState.value = ConnectionState.Error("Failed to connect: ${e.message}")
        }
    }

    override fun disconnect() {
        Log.d(TAG, "disconnect called")
        isManualDisconnect = true

        // Mark current app-connected device as disconnected
        lastConnectedDevice?.let { device ->
            updateAppConnection(device.address, false)
        }

        isConnected = false

        try {
            if (lastConnectedDevice?.isAudioDevice == true) {
                Log.d(TAG, "Audio device disconnect - updating UI only")
                _connectionState.value = ConnectionState.Disconnected
                lastConnectedDevice = null
                reconnectAttempts = 0
                return
            }

            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null

            bluetoothSocket?.close()
            bluetoothSocket = null

        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
        }

        _connectionState.value = ConnectionState.Disconnected
        lastConnectedDevice = null
        reconnectAttempts = 0
    }

    override fun readBatteryLevel() {
        Log.d(TAG, "readBatteryLevel called")

        if (lastConnectedDevice?.isAudioDevice == true) {
            // For audio devices, try to read battery
            val device = lastConnectedDevice?.let {
                bluetoothAdapter?.getRemoteDevice(it.address)
            }
            device?.let {
                readBatteryLevelFromAudioDevice(it)
            }
            return
        }

        val gatt = bluetoothGatt ?: run {
            Log.e(TAG, "Cannot read battery: GATT is null")
            return
        }

        val service = gatt.getService(BATTERY_SERVICE_UUID)
        if (service == null) {
            Log.d(TAG, "Battery service not available on this device")
            return
        }

        val characteristic = service.getCharacteristic(BATTERY_LEVEL_UUID) ?: run {
            Log.e(TAG, "Battery characteristic not found")
            return
        }

        try {
            val success = gatt.readCharacteristic(characteristic)
            Log.d(TAG, "Read battery characteristic initiated: $success")

            // If read fails immediately, schedule a retry
            if (!success) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (isConnected) {
                        Log.d(TAG, "Retrying battery read...")
                        gatt.readCharacteristic(characteristic)
                    }
                }, 500)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading battery", e)
        }
    }

    override fun cleanup() {
        Log.d(TAG, "cleanup called")
        try {
            context.unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
        disconnect()
    }

    private fun handleConnectionFailure() {
        Log.d(TAG, "handleConnectionFailure called")
        val device = lastConnectedDevice ?: run {
            _connectionState.value = ConnectionState.Disconnected
            return
        }

        try {
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing gatt", e)
        }
        bluetoothGatt = null

        // Show appropriate error message
        val errorMsg = if (device.isAudioDevice) {
            if (!device.isPaired) {
                "Please pair ${device.displayName} first in system Bluetooth settings"
            } else {
                "Failed to connect to ${device.displayName}. Make sure it's powered on and in range."
            }
        } else {
            "Failed to connect to ${device.displayName}. Device may not be BLE compatible or is out of range."
        }

        _connectionState.value = ConnectionState.Error(errorMsg)
    }

    private fun handleDisconnection() {
        Log.d(TAG, "handleDisconnection called")
        val device = lastConnectedDevice ?: run {
            _connectionState.value = ConnectionState.Disconnected
            return
        }

        try {
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing gatt", e)
        }
        bluetoothGatt = null

        if (!isManualDisconnect && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++
            Log.d(TAG, "Reconnecting, attempt $reconnectAttempts")
            _connectionState.value = ConnectionState.Reconnecting(device, reconnectAttempts)

            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (device.isAudioDevice && device.isPaired) {
                    connect(device)
                } else {
                    connectGatt(device)
                }
            }, RECONNECT_DELAY_MS)
        } else if (!isManualDisconnect) {
            Log.e(TAG, "Max reconnection attempts reached")
            _connectionState.value = ConnectionState.Error("Connection lost. Please try again.")
            lastConnectedDevice = null
            reconnectAttempts = 0
        }
    }

    private fun handleCharacteristicData(uuid: UUID, bytes: ByteArray) {
        when (uuid) {
            BATTERY_LEVEL_UUID -> {
                if (bytes.isNotEmpty()) {
                    val level = bytes[0].toInt() and 0xFF
                    Log.d(TAG, "Raw battery bytes: ${bytes.joinToString()} -> level: $level%")

                    // Validate battery level (should be 0-100)
                    val validLevel = if (level in 0..100) level else null

                    if (validLevel != null) {
                        Log.d(TAG, "✅ Valid battery level received: $validLevel%")
                        _batteryLevel.value = validLevel

                        // Update the device in connection state
                        val state = _connectionState.value
                        if (state is ConnectionState.Connected) {
                            val updatedDevice = state.device.copy(batteryLevel = validLevel)
                            _connectionState.value = ConnectionState.Connected(updatedDevice)
                        }

                        // Also update the last connected device
                        lastConnectedDevice = lastConnectedDevice?.copy(batteryLevel = validLevel)
                    } else {
                        Log.e(TAG, "❌ Invalid battery level: $level (expected 0-100)")
                    }
                } else {
                    Log.e(TAG, "Empty battery data received")
                }
            }

            HEART_RATE_CHAR_UUID -> {
                // Parse heart rate correctly
                val heartRate = GattDataParser.parseHeartRate(bytes)
                heartRate?.let {
                    Log.d(TAG, "Heart rate: $it BPM")
                    _heartRate.value = it

                    // Update the device in connection state
                    val state = _connectionState.value
                    if (state is ConnectionState.Connected) {
                        val updatedDevice = state.device.copy(heartRate = it)
                        _connectionState.value = ConnectionState.Connected(updatedDevice)
                    }
                }
            }
        }
    }

    fun testBatteryRead() {
        Log.d(TAG, "=== BATTERY DEBUG INFO ===")
        Log.d(TAG, "isConnected: $isConnected")
        Log.d(TAG, "bluetoothGatt: $bluetoothGatt")
        Log.d(TAG, "lastConnectedDevice: $lastConnectedDevice")

        if (bluetoothGatt != null) {
            val service = bluetoothGatt!!.getService(BATTERY_SERVICE_UUID)
            Log.d(TAG, "Battery service: $service")

            if (service != null) {
                val characteristic = service.getCharacteristic(BATTERY_LEVEL_UUID)
                Log.d(TAG, "Battery characteristic: $characteristic")
            }
        }
        Log.d(TAG, "==========================")
    }


    private fun enableHeartRateNotifications(gatt: BluetoothGatt) {
        val service = gatt.getService(HEART_RATE_SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(HEART_RATE_CHAR_UUID) ?: return

        try {
            gatt.setCharacteristicNotification(characteristic, true)

            val descriptor = characteristic.getDescriptor(CLIENT_CHAR_CONFIG_UUID)
            descriptor?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling heart rate notifications", e)
        }
    }

    private fun setupBatteryNotifications(gatt: BluetoothGatt) {
        try {
            val batteryService = gatt.getService(BATTERY_SERVICE_UUID) ?: run {
                Log.e(TAG, "Battery service not found")
                return
            }

            val batteryCharacteristic = batteryService.getCharacteristic(BATTERY_LEVEL_UUID) ?: run {
                Log.e(TAG, "Battery characteristic not found")
                return
            }

            // Enable notifications for battery level changes
            val success = gatt.setCharacteristicNotification(batteryCharacteristic, true)
            Log.d(TAG, "Set characteristic notification: $success")

            // Write descriptor to enable indications/notifications
            val descriptor = batteryCharacteristic.getDescriptor(CLIENT_CHAR_CONFIG_UUID)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                val descriptorWriteSuccess = gatt.writeDescriptor(descriptor)
                Log.d(TAG, "Write descriptor for notifications: $descriptorWriteSuccess")
            } else {
                Log.e(TAG, "Client characteristic config descriptor not found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up battery notifications", e)
        }
    }

    private fun startBatteryUpdates() {
        // Read battery every 30 seconds
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(object : Runnable {
            override fun run() {
                if (isConnected && !isManualDisconnect) {
                    Log.d(TAG, "Periodic battery update")
                    readBatteryLevel()

                    // Schedule next read
                    android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(this, 30000) // 30 seconds
                }
            }
        }, 5000) // First read after 5 seconds
    }
}