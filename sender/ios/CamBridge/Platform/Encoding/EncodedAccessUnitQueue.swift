import Foundation
import CamBridgeCore

public struct EncodedAccessUnitQueueTelemetry: Equatable, Sendable {
    public let occupancy: Int
    public let maximumOccupancy: Int
    public let drops: Int
}

public enum EncodedAccessUnitQueueError: Error, Equatable, Sendable {
    case invalidCapacity(Int)
}

// VideoToolbox invokes its callback synchronously on the encoder's serial
// queue. The mailbox is deliberately one slot: it is the bounded hand-off into
// the actor and never creates one Task per encoded frame. A queued keyframe is
// retained until the consumer takes it so a newer delta frame cannot remove
// the decoder refresh point OBS needs after Start or a camera switch.
private final class EncodedAccessUnitIngress: @unchecked Sendable {
    private let lock = NSLock()
    private var item: EncodedAccessUnit?
    private var waiter: CheckedContinuation<EncodedAccessUnit?, Never>?
    private var finished = false
    private var drops = Int.zero
    private var maximumOccupancy = Int.zero

    func offer(_ accessUnit: EncodedAccessUnit) {
        let waiter: CheckedContinuation<EncodedAccessUnit?, Never>?
        lock.lock()
        guard !finished else {
            lock.unlock()
            return
        }
        if let queuedItem = item {
            if !queuedItem.isKeyframe || accessUnit.isKeyframe {
                item = accessUnit
            }
            drops += Self.oneItem
            waiter = nil
        } else if let pendingWaiter = self.waiter {
            self.waiter = nil
            waiter = pendingWaiter
        } else {
            item = accessUnit
            maximumOccupancy = max(maximumOccupancy, item == nil ? .zero : Self.oneItem)
            waiter = nil
        }
        lock.unlock()
        waiter?.resume(returning: accessUnit)
    }

    func next() async -> EncodedAccessUnit? {
        await withCheckedContinuation { continuation in
            var immediate: EncodedAccessUnit?
            var shouldFinish = false
            lock.lock()
            if finished {
                shouldFinish = true
            } else if let item {
                self.item = nil
                immediate = item
            } else {
                waiter = continuation
            }
            lock.unlock()
            if shouldFinish {
                continuation.resume(returning: nil)
            } else if let immediate {
                continuation.resume(returning: immediate)
            }
        }
    }

    func takeIfAvailable() -> EncodedAccessUnit? {
        lock.lock()
        let result = item
        item = nil
        lock.unlock()
        return result
    }

    func clear() {
        lock.lock()
        item = nil
        lock.unlock()
    }

    func finish() {
        lock.lock()
        guard !finished else {
            lock.unlock()
            return
        }
        finished = true
        item = nil
        let waiter = self.waiter
        self.waiter = nil
        lock.unlock()
        waiter?.resume(returning: nil)
    }

    func telemetry() -> EncodedAccessUnitQueueTelemetry {
        lock.lock()
        let result = EncodedAccessUnitQueueTelemetry(
            occupancy: item == nil ? .zero : Self.oneItem,
            maximumOccupancy: maximumOccupancy,
            drops: drops
        )
        lock.unlock()
        return result
    }

    private static let oneItem = 1
}

// The actor owns the policy and all session-visible telemetry. The synchronous
// ingress is only a one-slot callback hand-off; it is included in telemetry so
// the total retained access units never exceeds the configured capacity.
public actor EncodedAccessUnitQueue {
    private var buffer: NewestItemBuffer<EncodedAccessUnit>
    private let ingress: EncodedAccessUnitIngress
    private var finished = false
    private var maximumOccupancy = Int.zero

    public init(capacity: Int = CamBridgeContract.Media.maxInFlightAccessUnits) throws {
        guard capacity > Self.ingressCapacity,
              capacity <= CamBridgeContract.Media.maxInFlightAccessUnits else {
            throw EncodedAccessUnitQueueError.invalidCapacity(capacity)
        }
        ingress = EncodedAccessUnitIngress()
        buffer = try NewestItemBuffer(capacity: capacity - Self.ingressCapacity)
    }

    // Called directly by the synchronous VideoToolbox callback. This method
    // only performs a bounded mailbox replacement and never awaits.
    public nonisolated func offer(_ accessUnit: EncodedAccessUnit) {
        ingress.offer(accessUnit)
    }

    @discardableResult
    public func insert(_ accessUnit: EncodedAccessUnit) -> EncodedAccessUnitQueueTelemetry {
        guard !finished else { return telemetry() }
        _ = buffer.insert(accessUnit)
        return telemetry()
    }

    public func removeNewest() -> EncodedAccessUnit? {
        buffer.takeNewest()
    }

    public func clear() {
        buffer.removeAll()
        ingress.clear()
    }

    public func next() async -> EncodedAccessUnit? {
        guard !finished else { return nil }
        if let accessUnit = ingress.takeIfAvailable() {
            _ = buffer.insert(accessUnit)
        }
        if let accessUnit = buffer.takeNewest() { return accessUnit }
        return await ingress.next()
    }

    public func finish() {
        guard !finished else {
            return
        }
        finished = true
        buffer.removeAll()
        ingress.finish()
    }

    public func telemetry() -> EncodedAccessUnitQueueTelemetry {
        let bufferTelemetry = EncodedAccessUnitQueueTelemetry(
            occupancy: buffer.occupancy,
            maximumOccupancy: buffer.maximumOccupancy,
            drops: buffer.dropCount
        )
        let ingressTelemetry = ingress.telemetry()
        let occupancy = bufferTelemetry.occupancy + ingressTelemetry.occupancy
        maximumOccupancy = max(maximumOccupancy, occupancy)
        return EncodedAccessUnitQueueTelemetry(
            occupancy: occupancy,
            maximumOccupancy: max(maximumOccupancy, bufferTelemetry.maximumOccupancy + ingressTelemetry.maximumOccupancy),
            drops: bufferTelemetry.drops + ingressTelemetry.drops
        )
    }

    private static let ingressCapacity = 1
}
