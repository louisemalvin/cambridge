import Testing
import CamBridgeCore

@Test("newest-item buffer drops stale complete items at its bound")
func newestItemPolicy() throws {
    var buffer = try NewestItemBuffer<Int>(capacity: 2)
    #expect(buffer.insert(1).droppedItemCount == 0)
    #expect(buffer.insert(2).droppedItemCount == 0)
    #expect(buffer.insert(3).droppedItemCount == 2)
    #expect(buffer.occupancy == 1)
    #expect(buffer.maximumOccupancy == 2)
    #expect(buffer.dropCount == 2)
    #expect(buffer.removeNewest() == 3)
    #expect(buffer.removeNewest() == nil)
}

@Test("taking the newest item discards older queued work")
func newestItemTakePolicy() throws {
    var buffer = try NewestItemBuffer<Int>(capacity: 2)
    _ = buffer.insert(1)
    _ = buffer.insert(2)
    #expect(buffer.takeNewest() == 2)
    #expect(buffer.occupancy == 0)
    #expect(buffer.dropCount == 1)
}

@Test("explicit stale-work discard contributes to drop telemetry")
func explicitStaleWorkDiscard() throws {
    var buffer = try NewestItemBuffer<Int>(capacity: 2)
    _ = buffer.insert(1)
    _ = buffer.insert(2)
    #expect(buffer.discardAll() == 2)
    #expect(buffer.occupancy == 0)
    #expect(buffer.dropCount == 2)
}
