import SwiftUI
import Shared

struct ContentView: View {
    @StateObject private var viewModel: BleViewModel
    @State private var showingErrorAlert = false

    init() {
        let repository = IosBleRepository()
        _viewModel = StateObject(wrappedValue: BleViewModel(repository: repository))
    }

    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                // Connection Status Header
                ConnectionHeader(state: viewModel.connectionState)

                // Control Buttons
                ControlPanel(viewModel: viewModel)

                // Connected Device Info
                if viewModel.isConnected, let device = viewModel.connectedDevice {
                    ConnectedDeviceCard(
                        device: device,
                        batteryLevel: viewModel.batteryLevel,
                        heartRate: viewModel.heartRate,
                        onRefreshBattery: viewModel.refreshBattery
                    )
                    .padding()
                }

                // Device List
                if viewModel.scannedDevices.isEmpty && viewModel.isScanning {
                    ScanningView()
                } else {
                    DeviceListView(
                        devices: viewModel.scannedDevices,
                        connectedDeviceId: viewModel.connectedDevice?.address,
                        onDeviceSelected: { device in
                            viewModel.connectToDevice(device)
                        }
                    )
                }

                Spacer()
            }
            .navigationTitle("BLE Scanner")
            .navigationBarTitleDisplayMode(.inline)
            .alert("Error", isPresented: $showingErrorAlert) {
                Button("OK", role: .cancel) { }
            } message: {
                Text(viewModel.errorMessage ?? "Unknown error")
            }
            .onChange(of: viewModel.errorMessage) { error in
                showingErrorAlert = error != nil
            }
        }
    }
}

struct ConnectionHeader: View {
    let state: ConnectionState

    var body: some View {
        HStack {
            Circle()
                .fill(statusColor)
                .frame(width: 8, height: 8)

            Text(statusText)
                .font(.subheadline)
                .foregroundColor(.secondary)

            if state is ConnectionState.Scanning {
                Spacer()
                ProgressView()
                    .scaleEffect(0.8)
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
        .background(Color(.systemBackground))
    }

    private var statusText: String {
        if state is ConnectionState.Connected {
            return "Connected"
        } else if state is ConnectionState.Connecting {
            return "Connecting..."
        } else if state is ConnectionState.Scanning {
            return "Scanning for devices..."
        } else if state is ConnectionState.Reconnecting {
            return "Reconnecting..."
        } else if let error = state as? ConnectionState.Error {
            return "Error: \(error.message)"
        } else {
            return "Disconnected"
        }
    }

    private var statusColor: Color {
        if state is ConnectionState.Connected {
            return .green
        } else if state is ConnectionState.Connecting || state is ConnectionState.Scanning || state is ConnectionState.Reconnecting {
            return .orange
        } else if state is ConnectionState.Error {
            return .red
        } else {
            return .gray
        }
    }
}

struct ControlPanel: View {
    @ObservedObject var viewModel: BleViewModel

    var body: some View {
        HStack(spacing: 16) {
            Button(action: viewModel.startScan) {
                Label("Scan", systemImage: "antenna.radiowaves.left.and.right")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(viewModel.isScanning || viewModel.isConnected)
            .tint(.blue)

            if viewModel.isConnected {
                Button(action: viewModel.disconnect) {
                    Label("Disconnect", systemImage: "xmark.circle")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .tint(.red)
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
    }
}

struct ScanningView: View {
    var body: some View {
        VStack(spacing: 16) {
            ProgressView()
                .scaleEffect(1.5)
            Text("Scanning for BLE devices...")
                .foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.top, 50)
    }
}

struct ConnectedDeviceCard: View {
    let device: BleDevice
    let batteryLevel: Int?
    let heartRate: Int?
    let onRefreshBattery: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: "battery.100bolt")
                    .foregroundColor(.green)
                Text("Connected Device")
                    .font(.headline)
                Spacer()
                Button(action: onRefreshBattery) {
                    Image(systemName: "arrow.clockwise")
                }
            }

            Divider()

            InfoRow(label: "Name", value: device.displayName)
            InfoRow(label: "Address", value: device.address)
            InfoRow(label: "Signal", value: "\(device.rssi) dBm", color: signalColor(device.rssi))

            if let battery = batteryLevel {
                InfoRow(label: "Battery", value: "\(battery)%")
            }

            if let heartRate = heartRate {
                InfoRow(label: "Heart Rate", value: "\(heartRate) BPM")
            }
        }
        .padding()
        .background(Color(.secondarySystemBackground))
        .cornerRadius(12)
    }

    private func signalColor(_ rssi: Int32) -> Color {
        if rssi >= -60 {
            return .green
        } else if rssi >= -80 {
            return .orange
        } else {
            return .red
        }
    }
}

struct DeviceListView: View {
    let devices: [BleDevice]
    let connectedDeviceId: String?
    let onDeviceSelected: (BleDevice) -> Void

    var body: some View {
        List(devices, id: \.address) { device in
            DeviceRow(
                device: device,
                isConnected: device.address == connectedDeviceId,
                onSelect: onDeviceSelected
            )
        }
        .listStyle(.plain)
    }
}

struct DeviceRow: View {
    let device: BleDevice
    let isConnected: Bool
    let onSelect: (BleDevice) -> Void

    var body: some View {
        Button(action: { onSelect(device) }) {
            HStack {
                // Device Icon
                Image(systemName: deviceIcon)
                    .foregroundColor(iconColor)
                    .font(.title2)
                    .frame(width: 32)

                // Device Info
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(device.displayName)
                            .font(.headline)
                        if isConnected {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundColor(.green)
                                .font(.caption)
                        }
                    }

                    Text(device.address)
                        .font(.caption)
                        .foregroundColor(.secondary)

                    HStack {
                        Text(device.displayType)
                            .font(.caption2)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(typeColor.opacity(0.2))
                            .foregroundColor(typeColor)
                            .cornerRadius(4)

                        if device.batteryLevel != nil {
                            Image(systemName: "battery.25")
                                .font(.caption2)
                                .foregroundColor(.secondary)
                        }
                    }
                }

                Spacer()

                // RSSI Indicator
                VStack(alignment: .trailing) {
                    Text("\(device.rssi) dBm")
                        .font(.subheadline)
                        .foregroundColor(signalColor(device.rssi))

                    if device.isPaired {
                        Text("Paired")
                            .font(.caption2)
                            .foregroundColor(.blue)
                    }
                }
            }
            .padding(.vertical, 4)
        }
        .foregroundColor(.primary)
    }

    private var deviceIcon: String {
        if device.isAudioDevice {
            return "headphones"
        } else if device.type.contains("Heart Rate") {
            return "heart.fill"
        } else {
            return "dot.radiowaves.left.and.right"
        }
    }

    private var iconColor: Color {
        if isConnected {
            return .green
        } else if device.isAudioDevice {
            return .blue
        } else {
            return .gray
        }
    }

    private var typeColor: Color {
        if device.isAudioDevice {
            return .blue
        } else {
            return .gray
        }
    }

    private func signalColor(_ rssi: Int32) -> Color {
        if rssi >= -60 {
            return .green
        } else if rssi >= -80 {
            return .orange
        } else {
            return .red
        }
    }
}

struct InfoRow: View {
    let label: String
    let value: String
    var color: Color = .primary

    var body: some View {
        HStack {
            Text(label)
                .foregroundColor(.secondary)
            Spacer()
            Text(value)
                .foregroundColor(color)
                .fontWeight(.medium)
        }
        .font(.subheadline)
    }
}