import Foundation

public struct NewestItemBuffer<Item: Sendable>: Sendable {
    public struct PushResult: Equatable, Sendable {
        public let droppedItemCount: Int
        public let occupancy: Int

        fileprivate init(droppedItemCount: Int, occupancy: Int) {
            self.droppedItemCount = droppedItemCount
            self.occupancy = occupancy
        }
    }

    public let capacity: Int
    public private(set) var dropCount = Int.zero
    public private(set) var maximumOccupancy = Int.zero
    private var items: [Item] = []

    public init(capacity: Int = Self.defaultCapacity) throws {
        guard capacity > .zero, capacity <= Self.defaultCapacity else {
            throw NewestItemBufferError.invalidCapacity(capacity)
        }
        self.capacity = capacity
        items.reserveCapacity(capacity)
    }

    public var occupancy: Int { items.count }

    @discardableResult
    public mutating func insert(_ item: Item) -> PushResult {
        let dropped = items.count >= capacity ? items.count : .zero
        if dropped > .zero {
            items.removeAll(keepingCapacity: true)
            dropCount += dropped
        }
        items.append(item)
        maximumOccupancy = max(maximumOccupancy, items.count)
        return PushResult(droppedItemCount: dropped, occupancy: items.count)
    }

    public mutating func removeNewest() -> Item? {
        items.popLast()
    }

    /// Removes the newest item and discards older queued work so consumers never
    /// send stale media after a newer access unit is available.
    @discardableResult
    public mutating func takeNewest() -> Item? {
        let newest = items.popLast()
        guard !items.isEmpty else { return newest }
        dropCount += items.count
        items.removeAll(keepingCapacity: true)
        return newest
    }

    public mutating func removeAll() {
        items.removeAll(keepingCapacity: true)
    }

    /// Discards queued work because a newer complete item superseded it.
    @discardableResult
    public mutating func discardAll() -> Int {
        let discarded = items.count
        dropCount += discarded
        items.removeAll(keepingCapacity: true)
        return discarded
    }

    private static var defaultCapacity: Int { 2 }
}

public enum NewestItemBufferError: Error, Equatable, Sendable {
    case invalidCapacity(Int)
}
