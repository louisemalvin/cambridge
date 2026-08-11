import Foundation

public enum StreamRotation: CaseIterable, Codable, Equatable, Hashable, Sendable {
    case zero
    case ninety
    case oneEighty
    case twoSeventy

    public static var allCases: [StreamRotation] {
        [.zero, .ninety, .oneEighty, .twoSeventy]
    }

    public var degrees: Int {
        guard let index = Self.allCases.firstIndex(of: self),
              index < CamBridgeContract.Geometry.rotationDegrees.count else {
            return .zero
        }
        return CamBridgeContract.Geometry.rotationDegrees[index]
    }

    public var swapsDisplayDimensions: Bool {
        switch self {
        case .ninety, .twoSeventy:
            true
        case .zero, .oneEighty:
            false
        }
    }

    public init(degrees: Int) throws {
        for (index, candidate) in CamBridgeContract.Geometry.rotationDegrees.enumerated() {
            guard index < Self.allCases.count else { break }
            if candidate == degrees {
                self = Self.allCases[index]
                return
            }
        }
        throw StreamRotationError.unsupported(degrees)
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        try self.init(degrees: container.decode(Int.self))
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(degrees)
    }
}

public enum StreamRotationError: Error, Equatable, Sendable {
    case unsupported(Int)
}
