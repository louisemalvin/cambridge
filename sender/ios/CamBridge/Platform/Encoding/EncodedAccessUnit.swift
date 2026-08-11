import Foundation

public struct EncodedAccessUnit: Sendable, Equatable {
    public let data: Data
    public let presentationTimeMicroseconds: Int64
    public let isKeyframe: Bool

    public init(data: Data, presentationTimeMicroseconds: Int64, isKeyframe: Bool) {
        self.data = data
        self.presentationTimeMicroseconds = presentationTimeMicroseconds
        self.isKeyframe = isKeyframe
    }
}
