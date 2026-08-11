import Foundation

public struct VideoGeometry: Codable, Equatable, Hashable, Sendable {
    public let codedWidth: Int
    public let codedHeight: Int

    public init(codedWidth: Int, codedHeight: Int) throws {
        guard codedWidth >= CamBridgeContract.Geometry.minimumDimension,
              codedHeight >= CamBridgeContract.Geometry.minimumDimension,
              codedWidth.isMultiple(of: CamBridgeContract.Geometry.dimensionAlignment),
              codedHeight.isMultiple(of: CamBridgeContract.Geometry.dimensionAlignment) else {
            throw VideoGeometryError.invalidDimensions(width: codedWidth, height: codedHeight)
        }
        guard Self.fitsReceiverBounds(longEdge: max(codedWidth, codedHeight), shortEdge: min(codedWidth, codedHeight)) else {
            throw VideoGeometryError.exceedsContract(width: codedWidth, height: codedHeight)
        }
        self.codedWidth = codedWidth
        self.codedHeight = codedHeight
    }

    public func displayDimensions(for rotation: StreamRotation) -> (width: Int, height: Int) {
        if rotation.swapsDisplayDimensions {
            return (codedHeight, codedWidth)
        }
        return (codedWidth, codedHeight)
    }

    public func fits(receiverMaxLongEdge: Int, receiverMaxShortEdge: Int, rotation: StreamRotation) -> Bool {
        let display = displayDimensions(for: rotation)
        let codedFits = Self.fitsReceiverBounds(
            longEdge: max(codedWidth, codedHeight),
            shortEdge: min(codedWidth, codedHeight),
            maximumLongEdge: receiverMaxLongEdge,
            maximumShortEdge: receiverMaxShortEdge
        )
        let displayFits = Self.fitsReceiverBounds(
            longEdge: max(display.width, display.height),
            shortEdge: min(display.width, display.height),
            maximumLongEdge: receiverMaxLongEdge,
            maximumShortEdge: receiverMaxShortEdge
        )
        return codedFits && displayFits
    }

    private static func fitsReceiverBounds(
        longEdge: Int,
        shortEdge: Int,
        maximumLongEdge: Int = CamBridgeContract.Geometry.maximumLongEdge,
        maximumShortEdge: Int = CamBridgeContract.Geometry.maximumShortEdge
    ) -> Bool {
        longEdge <= maximumLongEdge && shortEdge <= maximumShortEdge
    }
}

public enum VideoGeometryError: Error, Equatable, Sendable {
    case invalidDimensions(width: Int, height: Int)
    case exceedsContract(width: Int, height: Int)
}
