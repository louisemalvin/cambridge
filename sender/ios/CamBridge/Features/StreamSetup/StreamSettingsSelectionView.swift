import SwiftUI
import CamBridgeCore

struct StreamSettingsSelectionView: View {
    @Bindable var model: StreamSetupModel

    var body: some View {
        Section("Video") {
            Picker("Resolution", selection: $model.selectedResolutionID) {
                ForEach(SenderVideoCatalog.resolutions, id: \.id) { resolution in
                    Text(resolution.displayName).tag(resolution.id)
                }
            }
            .accessibilityIdentifier("resolution-picker")

            Picker("Frame rate", selection: $model.selectedFPS) {
                ForEach(SenderVideoCatalog.frameRates, id: \.self) { fps in
                    Text("\(fps) fps").tag(fps)
                }
            }
            .accessibilityIdentifier("frame-rate-picker")

            HStack {
                TextField(
                    "Bitrate",
                    text: Binding(
                        get: { model.bitrateText },
                        set: { model.setBitrateText($0) }
                    )
                )
                .keyboardType(.numberPad)
                .accessibilityIdentifier("bitrate-input")
                Text("Mbps")
                    .foregroundStyle(.secondary)
            }

            if model.selectedBitrateBps == nil {
                Text("Enter a whole number from \(SenderVideoCatalog.minimumBitrateMbps) to \(SenderVideoCatalog.maximumBitrateMbps).")
                    .font(.caption)
                    .foregroundStyle(.orange)
                    .accessibilityIdentifier("bitrate-validation")
            }
        }
    }
}
