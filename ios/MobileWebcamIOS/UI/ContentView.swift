import SwiftUI

struct ContentView: View {
    var body: some View {
        NavigationStack {
            Form {
                Section("Status") {
                    Label("iOS sender skeleton", systemImage: "iphone")
                    Text("Native camera and media transport are reserved for the iOS media spike.")
                        .foregroundStyle(.secondary)
                }

                Section("Architecture") {
                    Text("The future media engine will produce MPEG-TS over UDP for the shared Rust receiver.")
                    Text("Control, pairing, and session boundaries are present without a per-frame bridge.")
                }
            }
            .navigationTitle("Mobile Webcam")
        }
    }
}
