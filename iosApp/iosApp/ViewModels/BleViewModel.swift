import Foundation
import Shared
import Combine

class BleViewModel: ObservableObject {
    private let repository: BleRepository
    private var cancellables = Set<AnyCancellable>()

    @Published var scannedDevices: [BleDevice] = []
    @Published var connectionState: ConnectionState = ConnectionState.Disconnected()
    @Published var batteryLevel: Int? = nil
    @Published var heartRate: Int? = nil

    var isScanning: Bool {
        connectionState is ConnectionState.Scanning
    }

    var isConnected: Bool {
        connectionState is ConnectionState.Connected
    }

    var connectedDevice: BleDevice? {
        (connectionState as? ConnectionState.Connected)?.device
    }

    var errorMessage: String? {
        (connectionState as? ConnectionState.Error)?.message
    }

    init(repository: BleRepository) {
        self.repository = repository

        // Observe state flows
        observeScannedDevices()
        observeConnectionState()
        observeBatteryLevel()
        observeHeartRate()
    }

    private func observeScannedDevices() {
        FlowWrapper<KotlinArray<BleDevice>>(flow: repository.scannedDevices)
            .publisher
            .sink { [weak self] array in
                let devices = array?.toList() as? [BleDevice] ?? []
                DispatchQueue.main.async {
                    self?.scannedDevices = devices
                }
            }
            .store(in: &cancellables)
    }

    private func observeConnectionState() {
        FlowWrapper<ConnectionState>(flow: repository.connectionState)
            .publisher
            .sink { [weak self] state in
                DispatchQueue.main.async {
                    self?.connectionState = state ?? ConnectionState.Disconnected()
                }
            }
            .store(in: &cancellables)
    }

    private func observeBatteryLevel() {
        FlowWrapper<KotlinInt>(flow: repository.batteryLevel)
            .publisher
            .sink { [weak self] level in
                DispatchQueue.main.async {
                    self?.batteryLevel = level?.intValue
                }
            }
            .store(in: &cancellables)
    }

    private func observeHeartRate() {
        FlowWrapper<KotlinInt>(flow: repository.heartRate)
            .publisher
            .sink { [weak self] rate in
                DispatchQueue.main.async {
                    self?.heartRate = rate?.intValue
                }
            }
            .store(in: &cancellables)
    }

    func startScan() {
        repository.startScan()
    }

    func stopScan() {
        repository.stopScan()
    }

    func connectToDevice(_ device: BleDevice) {
        repository.connect(device: device)
    }

    func disconnect() {
        repository.disconnect()
    }

    func refreshBattery() {
        repository.readBatteryLevel()
    }
}

// Helper for converting Kotlin Flow to Combine Publisher
class FlowWrapper<T: AnyObject>: NSObject {
    let publisher = PassthroughSubject<T?, Never>()
    private var job: Kotlinx_coroutines_coreJob? = nil

    init(flow: Kotlinx_coroutines_coreFlow) {
        super.init()

        job = flow.collect(
            collector: Collector<T> { value in
                self.publisher.send(value)
                return KotlinUnit()
            },
            completionHandler: { error in
                if let error = error {
                    print("Flow collection error: \(error)")
                }
            }
        )
    }

    deinit {
        job?.cancel(cause: nil)
    }
}

class Collector<T: AnyObject>: Kotlinx_coroutines_coreFlowCollector {
    private let callback: (T?) -> KotlinUnit

    init(callback: @escaping (T?) -> KotlinUnit) {
        self.callback = callback
    }

    func emit(value: Any?, completionHandler: @escaping (Error?) -> Void) {
        let result = callback(value as? T)
        completionHandler(nil)
    }
}