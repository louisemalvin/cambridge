import Foundation

public struct SessionIdentity: Codable, Equatable, Hashable, Sendable {
    public let sessionId: String
    public let generation: UInt64

    public init(sessionId: String, generation: UInt64) throws {
        guard !sessionId.isEmpty, sessionId.count <= Self.maximumIdentifierLength else {
            throw SessionIdentityError.invalidSessionId
        }
        guard generation > .zero else { throw SessionIdentityError.invalidGeneration }
        self.sessionId = sessionId
        self.generation = generation
    }

    public func matches(sessionId: String, generation: UInt64) -> Bool {
        self.sessionId == sessionId && self.generation == generation
    }

    public func matches(_ other: SessionIdentity) -> Bool {
        self == other
    }

    private static let maximumIdentifierLength = CamBridgeContract.Validation.maximumIdentifierLength
}

public struct SessionIdentityAllocator: Sendable {
    private var nextGeneration: UInt64

    public init() {
        nextGeneration = UInt64(CamBridgeContract.Validation.minimumGeneration)
    }

    public init(firstGeneration: UInt64 = UInt64(CamBridgeContract.Validation.minimumGeneration)) throws {
        guard firstGeneration > .zero else { throw SessionIdentityError.invalidGeneration }
        self.nextGeneration = firstGeneration
    }

    public mutating func allocate(sessionId: String = UUID().uuidString) throws -> SessionIdentity {
        let identity = try SessionIdentity(sessionId: sessionId, generation: nextGeneration)
        guard nextGeneration < UInt64.max else {
            throw SessionIdentityError.generationExhausted
        }
        nextGeneration += Self.generationIncrement
        return identity
    }

    private static let generationIncrement: UInt64 = 1
}

public enum SessionIdentityError: Error, Equatable, Sendable {
    case invalidSessionId
    case invalidGeneration
    case generationExhausted
}
