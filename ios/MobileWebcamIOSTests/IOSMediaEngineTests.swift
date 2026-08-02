import XCTest
@testable import MobileWebcamIOS

final class IOSMediaEngineTests: XCTestCase {
    func testValidConfigurationPassesBoundaryValidation() throws {
        let configuration = IOSMediaConfiguration(
            codec: .h264,
            profile: IOSVideoProfile(width: 1_920, height: 1_080, framesPerSecond: 30),
            bitrateBps: 10_000_000,
            keyframeIntervalSeconds: 1
        )

        XCTAssertNoThrow(try configuration.validate())
    }

    func testStubEngineDoesNotClaimACompletedMediaPipeline() async throws {
        let engine = StubIOSMediaEngine()
        let configuration = IOSMediaConfiguration(
            codec: .h264,
            profile: IOSVideoProfile(width: 1_920, height: 1_080, framesPerSecond: 30),
            bitrateBps: 10_000_000,
            keyframeIntervalSeconds: 1
        )
        let destination = IOSMediaDestination(
            sessionID: UUID(),
            host: "127.0.0.1",
            port: 50_000
        )

        do {
            try await engine.start(configuration: configuration, destination: destination)
            XCTFail("The iOS media engine is intentionally a stub")
        } catch let error as IOSMediaEngineError {
            XCTAssertEqual(error, .notImplemented)
        }
    }

    func testInvalidBitrateIsRejectedBeforeMediaImplementation() async {
        let engine = StubIOSMediaEngine()

        do {
            try await engine.updateBitrate(0)
            XCTFail("A zero bitrate must be rejected")
        } catch let error as IOSMediaEngineError {
            XCTAssertEqual(error, .invalidBitrate)
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }
}
