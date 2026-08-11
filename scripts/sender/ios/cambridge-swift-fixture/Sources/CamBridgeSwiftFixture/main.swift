import Foundation
import CamBridgeCore

#if os(Linux)
import Glibc
#else
import Darwin
#endif

private enum FixtureError: Error, CustomStringConvertible {
    case invalidArgument(String)
    case missingArgument(String)
    case invalidHost(String)
    case socketCreationFailed
    case socketConnectionFailed
    case socketSendFailed
    case socketReceiveFailed
    case invalidReceiverResponse(String)
    case invalidFixture(String)

    var description: String {
        switch self {
        case let .invalidArgument(message), let .missingArgument(message), let .invalidHost(message),
             let .invalidReceiverResponse(message), let .invalidFixture(message):
            message
        case .socketCreationFailed:
            "could not create a POSIX socket"
        case .socketConnectionFailed:
            "could not connect the POSIX socket"
        case .socketSendFailed:
            "could not send the POSIX socket payload"
        case .socketReceiveFailed:
            "could not receive the POSIX socket payload"
        }
    }
}

private struct FixtureOptions {
    var host = "127.0.0.1"
    var controlPort = CamBridgeContract.Defaults.controlPort
    var profileID = VideoMode.defaultMode.id
    var width = VideoMode.defaultMode.codedWidth
    var height = VideoMode.defaultMode.codedHeight
    var fps = VideoMode.defaultMode.fps
    var bitrateBps = VideoMode.defaultMode.defaultBitrateBps
    var rotation = StreamRotation.zero
    var accessUnitPath: String? = nil
    var repeatCount = 1
    var lingerMilliseconds = 1_000

    init(arguments: [String]) throws {
        var iterator = arguments.makeIterator()
        while let argument = iterator.next() {
            switch argument {
            case "--host":
                host = try Self.nextString(&iterator, option: argument)
            case "--control-port":
                controlPort = try Self.nextInt(&iterator, option: argument)
            case "--profile-id":
                profileID = try Self.nextString(&iterator, option: argument)
            case "--width":
                width = try Self.nextInt(&iterator, option: argument)
            case "--height":
                height = try Self.nextInt(&iterator, option: argument)
            case "--fps":
                fps = try Self.nextInt(&iterator, option: argument)
            case "--bitrate-bps":
                bitrateBps = try Self.nextInt(&iterator, option: argument)
            case "--rotation-degrees":
                let degrees = try Self.nextInt(&iterator, option: argument)
                rotation = try StreamRotation(degrees: degrees)
            case "--access-unit":
                accessUnitPath = try Self.nextString(&iterator, option: argument)
            case "--repeat":
                repeatCount = try Self.nextInt(&iterator, option: argument)
            case "--linger-ms":
                lingerMilliseconds = try Self.nextInt(&iterator, option: argument)
            case "--help", "-h":
                print(Self.usage)
                exit(EXIT_SUCCESS)
            default:
                throw FixtureError.invalidArgument("unknown option: \(argument)")
            }
        }
        guard let accessUnitPath else {
            throw FixtureError.missingArgument("--access-unit is required")
        }
        self.accessUnitPath = accessUnitPath
        guard !host.isEmpty,
              controlPort >= CamBridgeContract.Validation.minimumPort,
              controlPort <= CamBridgeContract.Validation.maximumPort,
              width > .zero,
              height > .zero,
              fps >= CamBridgeContract.Video.minimumFps,
              fps <= CamBridgeContract.Video.maximumFps,
              bitrateBps >= CamBridgeContract.Bitrate.minimumBps,
              bitrateBps <= CamBridgeContract.Bitrate.maximumBps,
              repeatCount > .zero,
              repeatCount <= Self.maximumRepeatCount,
              lingerMilliseconds <= Self.maximumLingerMilliseconds,
              lingerMilliseconds >= .zero else {
            throw FixtureError.invalidArgument("one or more fixture options are outside the contract bounds")
        }
        guard profileID.count <= Self.maximumProfileIdentifierLength else {
            throw FixtureError.invalidArgument("profile ID exceeds the contract identifier bound")
        }
        _ = try VideoGeometry(codedWidth: width, codedHeight: height)
    }

    private static func nextString(_ iterator: inout Array<String>.Iterator, option: String) throws -> String {
        guard let value = iterator.next(), !value.isEmpty else {
            throw FixtureError.missingArgument("missing value for \(option)")
        }
        return value
    }

    private static func nextInt(_ iterator: inout Array<String>.Iterator, option: String) throws -> Int {
        let value = try nextString(&iterator, option: option)
        guard let integer = Int(value) else {
            throw FixtureError.invalidArgument("invalid integer for \(option): \(value)")
        }
        return integer
    }

    private static let usage = """
    Usage: cambridge-swift-fixture --access-unit FILE [options]
      --host HOST                 IPv4 receiver address (default: 127.0.0.1)
      --control-port PORT         TCP control port
      --profile-id ID              phone-authored profile identifier
      --width PIXELS               coded width
      --height PIXELS              coded height
      --fps FPS                    fixed frame rate
      --bitrate-bps BPS            phone-authored bitrate
      --rotation-degrees DEGREES   0, 90, 180, or 270
      --repeat COUNT               number of access units to send
      --linger-ms MILLISECONDS     wait before sending the matching stop
    """

    private static let maximumProfileIdentifierLength = CamBridgeContract.Validation.maximumProfileIdentifierLength
    private static let maximumRepeatCount = 300
    private static let maximumLingerMilliseconds = 60_000
}

private final class POSIXSocket {
    private let descriptor: Int32

    init(host: String, port: Int, socketType: Int32) throws {
        let descriptor = socket(AF_INET, socketType, .zero)
        guard descriptor >= .zero else {
            throw FixtureError.socketCreationFailed
        }
        self.descriptor = descriptor
        do {
            var address = try Self.address(host: host, port: port)
            let connected = withUnsafePointer(to: &address) { pointer in
                pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { socketAddress in
                    connect(descriptor, socketAddress, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
            guard connected == .zero else { throw FixtureError.socketConnectionFailed }
        } catch {
            close(descriptor)
            throw error
        }
    }

    deinit {
        close(descriptor)
    }

    func sendAll(_ data: Data) throws {
        var offset = data.startIndex
        while offset < data.endIndex {
            let sent = data.withUnsafeBytes { bytes in
                send(descriptor, bytes.baseAddress?.advanced(by: data.distance(from: data.startIndex, to: offset)), data.distance(from: offset, to: data.endIndex), .zero)
            }
            guard sent > .zero else { throw FixtureError.socketSendFailed }
            offset = data.index(offset, offsetBy: sent)
        }
    }

    func receiveExactly(count: Int) throws -> Data {
        var result = Data(capacity: count)
        while result.count < count {
            var byte: UInt8 = .zero
            let received = recv(descriptor, &byte, MemoryLayout<UInt8>.size, .zero)
            guard received == MemoryLayout<UInt8>.size else { throw FixtureError.socketReceiveFailed }
            result.append(byte)
        }
        return result
    }

    private static func address(host: String, port: Int) throws -> sockaddr_in {
        var address = sockaddr_in()
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = in_port_t(port).bigEndian
        let result = host.withCString { value in
            inet_pton(AF_INET, value, &address.sin_addr)
        }
        guard result == 1 else { throw FixtureError.invalidHost(host) }
        return address
    }
}

private func sendControl(_ message: ControlMessage, over socket: POSIXSocket) throws {
    let payload = try ControlMessageCodec.encode(message)
    try socket.sendAll(ControlFrameEncoder.frame(payload))
}

private func receiveControl(over socket: POSIXSocket) throws -> ControlMessage {
    let header = try socket.receiveExactly(count: MemoryLayout<UInt32>.size)
    let length = (Int(header[0]) << 24) | (Int(header[1]) << 16) | (Int(header[2]) << 8) | Int(header[3])
    guard length > .zero, length <= CamBridgeContract.Control.maximumMessageBytes else {
        throw FixtureError.invalidReceiverResponse("receiver returned an invalid control-frame length")
    }
    return try ControlMessageCodec.decode(socket.receiveExactly(count: length))
}

private func runFixture(_ options: FixtureOptions) throws {
    guard let accessUnitPath = options.accessUnitPath else {
        throw FixtureError.missingArgument("--access-unit is required")
    }
    let accessUnit = try Data(contentsOf: URL(fileURLWithPath: accessUnitPath))
    guard !accessUnit.isEmpty, accessUnit.count <= CamBridgeContract.Media.maxAccessUnitBytes else {
        throw FixtureError.invalidFixture("Annex-B access unit is empty or exceeds the contract bound")
    }
    let probeRequestID = "probe-\(UUID().uuidString)"
    let capabilities: ReceiverCapabilities
    do {
        let probeControl = try POSIXSocket(host: options.host, port: options.controlPort, socketType: Int32(SOCK_STREAM.rawValue))
        try sendControl(.probe(requestId: probeRequestID), over: probeControl)
        let capabilitiesResponse = try receiveControl(over: probeControl)
        guard case let .capabilities(responseRequestID, receiverID, displayName, maxLongEdge, maxShortEdge) = capabilitiesResponse,
              responseRequestID == probeRequestID else {
            throw FixtureError.invalidReceiverResponse("receiver did not return matching probe capabilities")
        }
        capabilities = try ReceiverCapabilities(
            receiverId: receiverID,
            displayName: displayName,
            maxLongEdge: maxLongEdge,
            maxShortEdge: maxShortEdge
        )
    }
    let control = try POSIXSocket(host: options.host, port: options.controlPort, socketType: Int32(SOCK_STREAM.rawValue))
    let geometry = try VideoGeometry(codedWidth: options.width, codedHeight: options.height)
    guard capabilities.supports(geometry, rotation: options.rotation) else {
        throw FixtureError.invalidReceiverResponse("receiver geometry cannot display the Swift fixture")
    }
    let sessionID = "swift-fixture-\(UUID().uuidString)"
    let generation = UInt64(CamBridgeContract.Validation.minimumGeneration)
    try sendControl(
        .hello(
            sessionId: sessionID,
            generation: generation,
            profileId: options.profileID,
            codedWidth: options.width,
            codedHeight: options.height,
            rotation: options.rotation,
            fps: options.fps,
            bitrateBps: options.bitrateBps
        ),
        over: control
    )
    let accepted = try receiveControl(over: control)
    guard case let .accepted(acceptedSessionID, acceptedGeneration, acceptedProfileID, mediaPort, _, _) = accepted,
          acceptedSessionID == sessionID,
          acceptedGeneration == generation,
          acceptedProfileID == options.profileID,
          mediaPort >= CamBridgeContract.Validation.minimumPort,
          mediaPort <= CamBridgeContract.Validation.maximumPort else {
        throw FixtureError.invalidReceiverResponse("receiver did not accept the Swift fixture session")
    }

    let media = try POSIXSocket(host: options.host, port: mediaPort, socketType: Int32(SOCK_DGRAM.rawValue))
    var packetizer = try RTPH264Packetizer()
    for frameIndex in 0..<options.repeatCount {
        let timestamp = Int64(frameIndex) * microsecondsPerSecond / Int64(options.fps)
        let packets = try packetizer.packetize(accessUnit, presentationTimeMicroseconds: timestamp)
        for packet in packets { try media.sendAll(packet) }
    }
    Thread.sleep(forTimeInterval: Double(options.lingerMilliseconds) / Double(millisecondsPerSecond))
    try sendControl(.stop(sessionId: sessionID, generation: generation), over: control)
    print("swift_fixture session=\(sessionID) profile=\(options.profileID) packets=\(packetizer.nextSequence)")
}

private let microsecondsPerSecond: Int64 = 1_000_000
private let millisecondsPerSecond: Double = 1_000

@main
private enum CamBridgeSwiftFixtureMain {
    static func main() {
        do {
            try runFixture(FixtureOptions(arguments: Array(CommandLine.arguments.dropFirst())))
        } catch {
            FileHandle.standardError.write(Data("swift fixture error: \(error)\n".utf8))
            exit(EXIT_FAILURE)
        }
    }
}
