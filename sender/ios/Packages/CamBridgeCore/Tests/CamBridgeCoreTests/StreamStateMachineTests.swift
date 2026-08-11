import Testing
import CamBridgeCore

@Test("state machine accepts one exact session and stops idempotently")
func stateMachineLifecycle() throws {
    let identity = try SessionIdentity(sessionId: CamBridgeTestFixtures.sessionId, generation: CamBridgeTestFixtures.generation)
    let configuration = try StreamConfiguration(mode: VideoMode.mode2k30, bitrateBps: 18_000_000, orientation: .ninety)
    var machine = StreamStateMachine()
    try machine.beginStart(identity: identity, configuration: configuration)
    try machine.accept(CamBridgeTestFixtures.accepted())
    if case .streaming = machine.state {} else { Issue.record("state did not become streaming") }
    let firstStop = machine.beginStop()
    let repeatedStop = machine.beginStop()
    #expect(firstStop)
    #expect(!repeatedStop)
    machine.finishStop()
    #expect(machine.state == .idle)
}

@Test("state machine rejects stale identity and profile responses")
func stateMachineValidation() throws {
    let identity = try SessionIdentity(sessionId: CamBridgeTestFixtures.sessionId, generation: CamBridgeTestFixtures.generation)
    let configuration = try StreamConfiguration(mode: VideoMode.mode2k30, bitrateBps: 18_000_000, orientation: .zero)
    var machine = StreamStateMachine()
    try machine.beginStart(identity: identity, configuration: configuration)
    #expect(throws: Error.self) {
        try machine.accept(.accepted(sessionId: "other", generation: identity.generation, profileId: configuration.mode.id, mediaPort: 55_032, maxLongEdge: 3840, maxShortEdge: 2160))
    }
}

@Test("generation allocator is monotonic")
func generationAllocation() throws {
    var allocator = try SessionIdentityAllocator(firstGeneration: 4)
    #expect(try allocator.allocate(sessionId: "first").generation == 4)
    #expect(try allocator.allocate(sessionId: "second").generation == 5)
}
