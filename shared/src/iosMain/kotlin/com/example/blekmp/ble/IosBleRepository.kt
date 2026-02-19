package com.example.blekmp.ble

import com.example.blekmp.model.BleDevice
import com.example.blekmp.model.ConnectionState
import com.example.blekmp.repository.BleRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import platform.CoreBluetooth.*
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.darwin.NSObject

actual class IosBleRepository : BleRepository {

    private val coroutineScope = CoroutineScope(Dispatchers.Default)
    private var centralManager: CBCentralManager? = null
    private var connectedPeripheral: CBPeripheral? = null

    private val _scannedDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    override val scannedDevices: StateFlow<List<BleDevice>> = _scannedDevices.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _batteryLevel = MutableStateFlow<Int?>(null)
    override val batteryLevel: Flow<Int?> = _batteryLevel.asStateFlow()

    private val _heartRate = MutableStateFlow<Int?>(null)
    override val heartRate: Flow<Int?> = _heartRate.asStateFlow()

    private var reconnectAttempts = 0
    private var lastConnectedDevice: BleDevice? = null
    private var isManualDisconnect = false

    private val centralManagerDelegate = object : NSObject(), CBCentralManagerDelegateProtocol {

        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            when (central.state) {
                CBCentralManagerStatePoweredOn -> {
                    println("iOS BLE is ready")
                }
                CBCentralManagerStatePoweredOff -> {
                    _connectionState.value = ConnectionState.Error("Bluetooth is turned off")
                }
                CBCentralManagerStateUnauthorized -> {
                    _connectionState.value = ConnectionState.Error("Bluetooth permission denied")
                }
                else -> {}
            }
        }

        override fun centralManagerDidDiscoverPeripheral(
            central: CBCentralManager,
            peripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber
        ) {
            val name = peripheral.name ?: advertisementData["kCBAdvDataLocalName"] as? String

            val bleDevice = BleDevice(
                name = name,
                address = peripheral.identifier.UUIDString,
                rssi = RSSI.intValue,
                type = "BLE",
                isAudioDevice = false,
                isPaired = false,
                isConnected = (connectedPeripheral?.identifier == peripheral.identifier)
            )

            coroutineScope.launch {
                val currentList = _scannedDevices.value.toMutableList()
                val existingIndex = currentList.indexOfFirst { it.address == bleDevice.address }
                if (existingIndex >= 0) {
                    currentList[existingIndex] = bleDevice
                } else {
                    currentList.add(bleDevice)
                }
                _scannedDevices.value = currentList
            }
        }

        override fun centralManagerDidConnectPeripheral(central: CBCentralManager, peripheral: CBPeripheral) {
            connectedPeripheral = peripheral
            isManualDisconnect = false
            reconnectAttempts = 0

            peripheral.delegate = peripheralDelegate
            peripheral.discoverServices(null)

            coroutineScope.launch {
                val device = lastConnectedDevice?.copy(isConnected = true) ?: BleDevice(
                    name = peripheral.name,
                    address = peripheral.identifier.UUIDString,
                    rssi = 0,
                    isConnected = true
                )
                _connectionState.value = ConnectionState.Connected(device)
            }
        }

        override fun centralManagerDidDisconnectPeripheral(
            central: CBCentralManager,
            peripheral: CBPeripheral,
            error: NSError?
        ) {
            if (!isManualDisconnect) {
                handleDisconnection()
            } else {
                _connectionState.value = ConnectionState.Disconnected
            }
        }
    }

    private val peripheralDelegate = object : NSObject(), CBPeripheralDelegateProtocol {

        override fun peripheralDidDiscoverServices(peripheral: CBPeripheral) {
            peripheral.services?.forEach { service ->
                when (service.UUID.UUIDString) {
                    "180F" -> peripheral.discoverCharacteristics(null, service) // Battery
                    "180D" -> peripheral.discoverCharacteristics(null, service) // Heart Rate
                }
            }
        }

        override fun peripheralDidDiscoverCharacteristicsForService(
            peripheral: CBPeripheral,
            service: CBService,
            error: NSError?
        ) {
            service.characteristics?.forEach { characteristic ->
                when (characteristic.UUID.UUIDString) {
                    "2A19" -> { // Battery Level
                        peripheral.readValueForCharacteristic(characteristic)
                        peripheral.setNotifyValue(true, characteristic)
                    }
                    "2A37" -> { // Heart Rate Measurement
                        peripheral.setNotifyValue(true, characteristic)
                    }
                }
            }
        }

        override fun peripheralDidUpdateValueForCharacteristic(
            peripheral: CBPeripheral,
            characteristic: CBCharacteristic,
            error: NSError?
        ) {
            characteristic.value?.let { value ->
                when (characteristic.UUID.UUIDString) {
                    "2A19" -> {
                        val level = value.firstOrNull()?.toInt()?.and(0xFF)
                        _batteryLevel.value = level
                        updateConnectedDeviceBattery(level)
                    }
                    "2A37" -> {
                        if (value.size >= 2) {
                            val heartRate = value[1].toInt() and 0xFF
                            _heartRate.value = heartRate
                            updateConnectedDeviceHeartRate(heartRate)
                        }
                    }
                }
            }
        }
    }

    private fun updateConnectedDeviceBattery(level: Int?) {
        val currentState = _connectionState.value
        if (currentState is ConnectionState.Connected) {
            val updatedDevice = currentState.device.copy(batteryLevel = level)
            _connectionState.value = ConnectionState.Connected(updatedDevice)
        }
    }

    private fun updateConnectedDeviceHeartRate(heartRate: Int?) {
        val currentState = _connectionState.value
        if (currentState is ConnectionState.Connected) {
            val updatedDevice = currentState.device.copy(heartRate = heartRate)
            _connectionState.value = ConnectionState.Connected(updatedDevice)
        }
    }

    private fun handleDisconnection() {
        val device = lastConnectedDevice
        if (device != null && reconnectAttempts < 3) {
            reconnectAttempts++
            _connectionState.value = ConnectionState.Reconnecting(device, reconnectAttempts)

            coroutineScope.launch {
                kotlinx.coroutines.delay(2000)
                if (!isManualDisconnect) {
                    connect(device)
                }
            }
        } else {
            _connectionState.value = ConnectionState.Disconnected
            lastConnectedDevice = null
            reconnectAttempts = 0
        }
    }

    override fun startScan() {
        if (centralManager == null) {
            centralManager = CBCentralManager(centralManagerDelegate, null)
        }

        centralManager?.let { manager ->
            if (manager.state == CBCentralManagerStatePoweredOn) {
                _scannedDevices.value = emptyList()
                _connectionState.value = ConnectionState.Scanning
                manager.scanForPeripheralsWithServices(null, null)
            }
        }
    }

    override fun stopScan() {
        centralManager?.stopScan()
        if (_connectionState.value is ConnectionState.Scanning) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    override fun connect(device: BleDevice) {
        stopScan()
        lastConnectedDevice = device
        isManualDisconnect = false

        centralManager?.let { manager ->
            if (manager.state == CBCentralManagerStatePoweredOn) {
                _connectionState.value = ConnectionState.Connecting(device)

                val uuid = NSUUID(device.address)
                val peripherals = manager.retrievePeripheralsWithIdentifiers(listOf(uuid))
                val peripheral = peripherals.firstOrNull() as? CBPeripheral

                if (peripheral != null) {
                    manager.connectPeripheral(peripheral, null)
                } else {
                    _connectionState.value = ConnectionState.Error("Device not found")
                }
            }
        }
    }

    override fun disconnect() {
        isManualDisconnect = true
        connectedPeripheral?.let { peripheral ->
            centralManager?.cancelPeripheralConnection(peripheral)
        }
        connectedPeripheral = null
        _connectionState.value = ConnectionState.Disconnected
        lastConnectedDevice = null
        reconnectAttempts = 0
    }

    override fun readBatteryLevel() {
        // iOS will send notifications for battery level
    }

    override fun cleanup() {
        disconnect()
        centralManager?.stopScan()
        centralManager = null
    }
}