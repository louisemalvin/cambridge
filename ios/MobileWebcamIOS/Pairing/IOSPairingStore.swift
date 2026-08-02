import Foundation

struct IOSPairingRecord: Equatable {
    let senderID: UUID
    let receiverID: UUID
    let token: String
}

protocol IOSPairingStore: AnyObject {
    var senderID: UUID { get }

    func record(for receiverID: UUID) -> IOSPairingRecord?
    func save(_ record: IOSPairingRecord)
    func remove(receiverID: UUID)
}

final class InMemoryIOSPairingStore: IOSPairingStore {
    let senderID: UUID
    private var records: [UUID: IOSPairingRecord] = [:]

    init(senderID: UUID) {
        self.senderID = senderID
    }

    func record(for receiverID: UUID) -> IOSPairingRecord? {
        records[receiverID]
    }

    func save(_ record: IOSPairingRecord) {
        records[record.receiverID] = record
    }

    func remove(receiverID: UUID) {
        records.removeValue(forKey: receiverID)
    }
}
