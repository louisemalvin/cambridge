import SwiftUI
import CamBridgeCore

struct VideoModeSelectionView: View {
    @Bindable var model: StreamSetupModel

    var body: some View {
        Section("Video") {
            Picker("Resolution and FPS", selection: $model.selectedModeID) {
                ForEach(VideoMode.productModes, id: \.id) { mode in
                    let capability = model.modeCapabilities.first(where: { $0.mode.id == mode.id })
                    Text("\(mode.codedWidth)×\(mode.codedHeight) · \(mode.fps) fps")
                        .tag(mode.id)
                        .disabled(capability?.supported == false)
                }
            }
            .onChange(of: model.selectedModeID) { _, modeID in
                model.selectMode(modeID)
            }
            .accessibilityIdentifier("video-mode-picker")

            Picker("Orientation", selection: $model.selectedOrientation) {
                Text("Landscape").tag(StreamRotation.zero)
                Text("Portrait clockwise").tag(StreamRotation.ninety)
                Text("Landscape reversed").tag(StreamRotation.oneEighty)
                Text("Portrait counter-clockwise").tag(StreamRotation.twoSeventy)
            }
            .onChange(of: model.selectedOrientation) { _, orientation in
                model.selectOrientation(orientation)
            }
            .accessibilityIdentifier("orientation-picker")

            Picker("Bitrate", selection: $model.selectedBitrateBps) {
                ForEach(model.bitrateOptions, id: \.self) { bitrate in
                    Text("\(bitrate / VideoModeSelectionMetrics.bitsPerMegabit) Mbps").tag(bitrate)
                }
            }
            .accessibilityIdentifier("bitrate-picker")

            Picker("Stabilization", selection: $model.selectedStabilization) {
                ForEach(model.stabilizationOptions, id: \.self) { preference in
                    Text(preference.displayName).tag(preference)
                }
            }
            .accessibilityIdentifier("stabilization-picker")

            if let capability = model.modeCapabilities.first(where: { $0.mode.id == model.selectedModeID }), !capability.supported {
                Label(capability.reason ?? "Mode unavailable", systemImage: "exclamationmark.triangle")
                    .font(.caption)
                    .foregroundStyle(.orange)
            }
        }
    }
}

private enum VideoModeSelectionMetrics {
    static let bitsPerMegabit = 1_000_000
}
