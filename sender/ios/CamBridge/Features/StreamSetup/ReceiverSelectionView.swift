import SwiftUI

struct ReceiverSelectionView: View {
    @Bindable var model: StreamSetupModel

    var body: some View {
        Section("Receiver") {
            switch model.receiverStatus {
            case .checking:
                HStack {
                    ProgressView()
                    Text("Checking for OBS receivers…")
                }
            case .ready:
                Label(model.selectedReceiver?.displayName ?? "Receiver ready", systemImage: "checkmark.circle")
                    .foregroundStyle(.green)
            case .selectionRequired:
                Text("Choose an OBS receiver")
                    .foregroundStyle(.orange)
            case let .unavailable(message):
                Text(message)
                    .foregroundStyle(.secondary)
            }
            ForEach(model.receivers) { receiver in
                Button {
                    model.selectReceiver(receiver.id)
                } label: {
                    HStack {
                        Image(systemName: model.selectedReceiverID == receiver.id ? "checkmark.circle.fill" : "circle")
                        VStack(alignment: .leading) {
                            Text(receiver.displayName)
                            Text(receiver.endpoint.host)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .accessibilityIdentifier("receiver-\(receiver.id)")
            }
            TextField("OBS computer host", text: $model.manualHost)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .accessibilityIdentifier("manual-receiver-host")
            Button("Probe manual receiver") {
                Task { await model.probeManualReceiver() }
            }
            .accessibilityIdentifier("probe-manual-receiver")
        }
        .disabled(model.isStreamActive)
    }
}
