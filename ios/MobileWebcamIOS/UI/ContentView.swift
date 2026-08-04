import SwiftUI

struct ContentView: View {
    @StateObject private var discovery = BonjourIOSReceiverDiscovery()

    var body: some View {
        NavigationStack {
            Form {
                Section("Status") {
                    Label("iOS sender skeleton", systemImage: "iphone")
                    Text("Native camera and media transport are reserved for the iOS media spike.")
                        .foregroundStyle(.secondary)
                }

                Section("Architecture") {
                    Text("The native media engine will produce video-only H.264 in MPEG-TS over encrypted SRT for the shared Rust receiver.")
                    Text("The sender creates and owns its receiver session through the shared v2 HTTP contract.")
                }

                Section("Nearby receivers") {
                    if discovery.receivers.isEmpty {
                        Text("Searching for Mobile Webcam receivers")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(discovery.receivers) { receiver in
                            VStack(alignment: .leading) {
                                Text(receiver.displayName)
                                Text(receiver.endpoint.host)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    Text("The eventual iOS pairing flow will keep manual receiver-origin entry as a fallback when local discovery is unavailable.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Mobile Webcam")
        }
        .onAppear { discovery.start() }
        .onDisappear { discovery.stop() }
    }
}
