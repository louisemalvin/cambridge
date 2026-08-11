import Foundation
@preconcurrency import AVFoundation
import CoreMedia
import CamBridgeCore

public enum CaptureServiceError: Error, Equatable, Sendable {
    case permissionDenied
    case deviceUnavailable
    case formatUnavailable
    case configurationFailed(String)
    case notPrepared
    case runtimeError(String)
}

public protocol CameraSetupServicing: Sendable {
    func authorizationState() async -> CameraAuthorizationState
    func requestAuthorization() async -> CameraAuthorizationState
    func availableCameras() async -> [CameraDeviceDescriptor]
    func selectCamera(withID deviceID: String) async -> Bool
    func modeCapabilities(
        modes: [VideoMode],
        receiver: ReceiverCapabilities?,
        orientation: StreamRotation
    ) async -> [CameraModeCapability]
    func capabilitySnapshots(
        modes: [VideoMode],
        receiver: ReceiverCapabilities?,
        orientation: StreamRotation
    ) async -> [CameraCapabilitySnapshot]
    func cameraState() async -> CameraState
}

public actor CaptureService {
    public private(set) var state: CameraState = .initial

    private let capabilityProbe: CameraCapabilityProbe
    private let sessionQueue = DispatchQueue(label: "dev.cambridge.sender.capture")
    private var session: AVCaptureSession?
    private var device: AVCaptureDevice?
    private var videoInput: AVCaptureDeviceInput?
    private var videoOutput: AVCaptureVideoDataOutput?
    private var outputDelegate: CaptureOutputDelegate?
    private var encoder: VideoToolboxEncoder?
    private var notificationTokens: [NSObjectProtocol] = []
    private var systemPressureObservation: NSKeyValueObservation?
    private var cachedPreviewLayer: AVCaptureVideoPreviewLayer?
    private var selectedPosition: CameraPosition = .back
    private var selectedOrientation: StreamRotation = .zero

    public init(capabilityProbe: CameraCapabilityProbe = CameraCapabilityProbe()) {
        self.capabilityProbe = capabilityProbe
    }

    public func authorizationState() -> CameraAuthorizationState {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            .authorized
        case .denied:
            .denied
        case .restricted:
            .restricted
        case .notDetermined:
            .notDetermined
        @unknown default:
            .restricted
        }
    }

    public func requestAuthorization() async -> CameraAuthorizationState {
        guard authorizationState() == .notDetermined else { return authorizationState() }
        _ = await AVCaptureDevice.requestAccess(for: .video)
        let current = authorizationState()
        state.authorization = current
        return current
    }

    public func availableCameras() async -> [CameraDeviceDescriptor] {
        let cameras = capabilityProbe.rearCameraDescriptors()
        state.devices = cameras
        return cameras
    }

    public func selectCamera(withID deviceID: String) async -> Bool {
        guard capabilityProbe.device(withID: deviceID) != nil else { return false }
        state.selectedDeviceID = deviceID
        return true
    }

    public func modeCapabilities(
        modes: [VideoMode],
        receiver: ReceiverCapabilities?,
        orientation: StreamRotation
    ) async -> [CameraModeCapability] {
        guard let deviceID = state.selectedDeviceID,
              let device = capabilityProbe.device(withID: deviceID) else {
            return modes.map { CameraModeCapability(mode: $0, supported: false, reason: "No rear camera selected", formatID: nil) }
        }
        return capabilityProbe.capabilities(for: device, modes: modes, receiver: receiver, orientation: orientation)
    }

    public func capabilitySnapshots(
        modes: [VideoMode],
        receiver: ReceiverCapabilities?,
        orientation: StreamRotation
    ) async -> [CameraCapabilitySnapshot] {
        capabilityProbe.capabilitySnapshots(modes: modes, receiver: receiver, orientation: orientation)
    }

    public func prepare(
        configuration: StreamConfiguration,
        deviceID: String,
        onAccessUnit: @escaping @Sendable (Result<EncodedAccessUnit, VideoToolboxEncoderError>) -> Void
    ) async throws {
        guard session == nil, encoder == nil else {
            throw CaptureServiceError.configurationFailed("Capture service is already prepared")
        }
        guard authorizationState() == .authorized else { throw CaptureServiceError.permissionDenied }
        guard let device = capabilityProbe.device(withID: deviceID) else {
            throw CaptureServiceError.deviceUnavailable
        }
        guard let format = capabilityProbe.exactFormat(for: configuration.mode, on: device) else {
            throw CaptureServiceError.formatUnavailable
        }
        let encoder = VideoToolboxEncoder()
        encoder.onAccessUnit = onAccessUnit
        do {
            try encoder.prepare(configuration: configuration)
        } catch {
            encoder.invalidate()
            throw error
        }
        do {
            try await configure(
                device: device,
                format: format,
                configuration: configuration,
                encoder: encoder
            )
        } catch {
            encoder.invalidate()
            throw error
        }
        self.encoder = encoder
    }

    public func start() async throws {
        guard let session else { throw CaptureServiceError.notPrepared }
        sessionQueue.sync {
            guard !session.isRunning else { return }
            session.startRunning()
        }
    }

    public func stop() async {
        let session = self.session
        let output = self.videoOutput
        sessionQueue.sync {
            // Detach first on the same serial executor used for callbacks so
            // no new sample can enter VideoToolbox during teardown.
            output?.setSampleBufferDelegate(nil, queue: nil)
            if let session, session.isRunning { session.stopRunning() }
        }
        outputDelegate = nil
        encoder?.invalidate()
        encoder = nil
        cachedPreviewLayer = nil
        self.session = nil
        device = nil
        videoInput = nil
        videoOutput = nil
        state.selectedFormat = nil
        state.selectedDeviceID = nil
        state.minimumZoomRatio = CameraState.defaultZoomRatio
        state.maximumZoomRatio = CameraState.defaultZoomRatio
        state.zoomRatio = CameraState.defaultZoomRatio
        state.supportedStabilization = [.auto, .off]
        state.activeStabilization = .auto
        removeNotifications()
    }

    public func encoderMetrics() async -> VideoToolboxEncoderMetrics? {
        encoder?.metrics()
    }

    public func cameraState() async -> CameraState {
        state
    }

    public func previewLayer() -> AVCaptureVideoPreviewLayer? {
        guard let session else { return nil }
        if let cachedPreviewLayer { return cachedPreviewLayer }
        let resolution = SessionOrientationResolver().resolve(rotation: selectedOrientation, cameraPosition: selectedPosition)
        let layer = sessionQueue.sync {
            let layer = AVCaptureVideoPreviewLayer(session: session)
            layer.videoGravity = .resizeAspectFill
            if let connection = layer.connection,
               connection.isVideoRotationAngleSupported(resolution.previewRotationAngle) {
                connection.videoRotationAngle = resolution.previewRotationAngle
            }
            return layer
        }
        cachedPreviewLayer = layer
        return layer
    }

    public func setZoomRatio(_ ratio: Double) throws {
        guard let device else { throw CaptureServiceError.notPrepared }
        let bounded = min(max(ratio, Double(device.minAvailableVideoZoomFactor)), Double(device.maxAvailableVideoZoomFactor))
        try sessionQueue.sync {
            try device.lockForConfiguration()
            defer { device.unlockForConfiguration() }
            device.videoZoomFactor = CGFloat(bounded)
        }
        state.zoomRatio = bounded
    }

    public func setStabilization(_ preference: CameraStabilizationPreference) async throws {
        guard let device, let connection = videoOutput?.connection(with: .video) else {
            throw CaptureServiceError.notPrepared
        }
        guard let mode = preference.avFoundationMode else {
            throw CaptureServiceError.configurationFailed("Requested video stabilization mode requires a newer iOS version")
        }
        if preference != .off && !connection.isVideoStabilizationSupported {
            state.activeStabilization = .off
            return
        }
        guard device.activeFormat.isVideoStabilizationModeSupported(mode) else {
            throw CaptureServiceError.configurationFailed("Requested video stabilization mode is unavailable")
        }
        let activeMode = try sessionQueue.sync {
            connection.preferredVideoStabilizationMode = mode
            return connection.activeVideoStabilizationMode
        }
        state.activeStabilization = CameraStabilizationPreference(mode: activeMode)
    }

    public func selectedPreviewOrientation(_ orientation: StreamRotation) {
        selectedOrientation = orientation
        let resolution = SessionOrientationResolver().resolve(rotation: orientation, cameraPosition: selectedPosition)
        let layer = cachedPreviewLayer
        sessionQueue.sync {
            if let connection = layer?.connection,
               connection.isVideoRotationAngleSupported(resolution.previewRotationAngle) {
                connection.videoRotationAngle = resolution.previewRotationAngle
            }
        }
    }

    private func configure(
        device: AVCaptureDevice,
        format: CameraFormatDescriptor,
        configuration: StreamConfiguration,
        encoder: VideoToolboxEncoder
    ) async throws {
        let session = AVCaptureSession()
        let input: AVCaptureDeviceInput
        do {
            input = try AVCaptureDeviceInput(device: device)
        } catch {
            throw CaptureServiceError.configurationFailed(String(describing: error))
        }
        let output = AVCaptureVideoDataOutput()
        output.alwaysDiscardsLateVideoFrames = true
        output.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
        ]
        let delegate = CaptureOutputDelegate(encoder: encoder)
        guard let avFormat = device.formats.first(where: { candidate in
            String(describing: ObjectIdentifier(candidate as AnyObject)) == format.formatID
        }) else {
            throw CaptureServiceError.formatUnavailable
        }
        let canAddInputAndOutput = sessionQueue.sync {
            session.canAddInput(input) && session.canAddOutput(output)
        }
        guard canAddInputAndOutput else {
            throw CaptureServiceError.configurationFailed("Capture session rejected the video input or output")
        }
        let configurationResult = sessionQueue.sync { () -> (CaptureServiceError?, [CameraStabilizationPreference]) in
            var configurationError: CaptureServiceError?
            var supportedStabilization = [CameraStabilizationPreference.off]
            session.beginConfiguration()
            session.addInput(input)
            session.addOutput(output)
            do {
                try device.lockForConfiguration()
                defer { device.unlockForConfiguration() }
                device.activeFormat = avFormat
                let duration = CMTime(value: Self.singleFrameDurationNumerator, timescale: CMTimeScale(configuration.mode.fps))
                device.activeVideoMinFrameDuration = duration
                device.activeVideoMaxFrameDuration = duration
                guard device.activeVideoMinFrameDuration == duration,
                      device.activeVideoMaxFrameDuration == duration else {
                    throw CaptureServiceError.configurationFailed("Camera rejected the requested fixed frame duration")
                }
                supportedStabilization = CameraStabilizationPreference.allCases.filter { preference in
                    guard let mode = preference.avFoundationMode else { return false }
                    return avFormat.isVideoStabilizationModeSupported(mode)
                }
                if !supportedStabilization.contains(.off) {
                    supportedStabilization.insert(.off, at: .zero)
                }
                if let connection = output.connection(with: .video), !connection.isVideoStabilizationSupported {
                    supportedStabilization = [.off]
                }
            } catch {
                configurationError = .configurationFailed(String(describing: error))
            }
            if configurationError == nil {
                output.setSampleBufferDelegate(delegate, queue: encoder.inputQueue)
            }
            if configurationError == nil,
               let connection = output.connection(with: .video),
               connection.isVideoStabilizationSupported,
               supportedStabilization.contains(.standard) {
                connection.preferredVideoStabilizationMode = .standard
            }
            session.commitConfiguration()
            return (configurationError, supportedStabilization)
        }
        if let configurationError = configurationResult.0 { throw configurationError }
        let supportedStabilization = configurationResult.1
        self.session = session
        self.device = device
        self.videoInput = input
        self.videoOutput = output
        self.outputDelegate = delegate
        self.selectedPosition = device.position == .front ? .front : .back
        state.supportedStabilization = supportedStabilization
        selectedPreviewOrientation(configuration.orientation)
        state.authorization = authorizationState()
        state.selectedDeviceID = device.uniqueID
        state.selectedFormat = format
        state.minimumZoomRatio = Double(device.minAvailableVideoZoomFactor)
        state.maximumZoomRatio = Double(device.maxAvailableVideoZoomFactor)
        state.zoomRatio = Double(device.videoZoomFactor)
        if let connection = output.connection(with: .video), connection.isVideoStabilizationSupported {
            state.activeStabilization = CameraStabilizationPreference(mode: connection.activeVideoStabilizationMode)
        } else {
            state.activeStabilization = .off
        }
        state.interruption = nil
        state.runtimeError = nil
        state.systemPressureLevel = CameraSystemPressureLevel(level: device.systemPressureState.level)
        state.thermalState = String(describing: ProcessInfo.processInfo.thermalState)
        observeNotifications()
    }

    private func observeNotifications() {
        let center = NotificationCenter.default
        notificationTokens = [
            center.addObserver(forName: AVCaptureSession.wasInterruptedNotification, object: session, queue: nil) { [weak self] notification in
                let reasonValue = (notification.userInfo?[AVCaptureSessionInterruptionReasonKey] as? NSNumber)?.intValue
                Task { await self?.handleInterruption(reasonValue: reasonValue) }
            },
            center.addObserver(forName: AVCaptureSession.runtimeErrorNotification, object: session, queue: nil) { [weak self] notification in
                let message = (notification.userInfo?[AVCaptureSessionErrorKey] as? NSError)?.localizedDescription
                Task { await self?.handleRuntimeError(message: message) }
            },
            center.addObserver(forName: AVCaptureSession.interruptionEndedNotification, object: session, queue: nil) { [weak self] _ in
                Task { await self?.clearInterruption() }
            },
            center.addObserver(forName: ProcessInfo.thermalStateDidChangeNotification, object: nil, queue: nil) { [weak self] _ in
                Task { await self?.updateThermalState() }
            }
        ]
        if let device {
            systemPressureObservation = device.observe(\AVCaptureDevice.systemPressureState, options: [.new]) { [weak self] _, _ in
                Task { await self?.updateSystemPressure() }
            }
        }
    }

    private func removeNotifications() {
        for token in notificationTokens { NotificationCenter.default.removeObserver(token) }
        notificationTokens.removeAll(keepingCapacity: true)
        systemPressureObservation?.invalidate()
        systemPressureObservation = nil
    }

    private func handleInterruption(reasonValue: Int?) {
        let reason: CameraInterruptionReason
        switch reasonValue {
        case AVCaptureSession.InterruptionReason.videoDeviceInUseByAnotherClient.rawValue:
            reason = .videoDeviceInUse
        case AVCaptureSession.InterruptionReason.videoDeviceNotAvailableWithMultipleForegroundApps.rawValue:
            reason = .videoDeviceNotAvailable
        case AVCaptureSession.InterruptionReason.videoDeviceNotAvailableDueToSystemPressure.rawValue:
            reason = .systemPressure
        default:
            reason = .unknown
        }
        state.interruption = reason
    }

    private func handleRuntimeError(message: String?) {
        state.runtimeError = message
    }

    private func clearInterruption() {
        state.interruption = nil
    }

    private func updateSystemPressure() {
        state.systemPressureLevel = device.map { CameraSystemPressureLevel(level: $0.systemPressureState.level) }
    }

    private func updateThermalState() {
        state.thermalState = String(describing: ProcessInfo.processInfo.thermalState)
    }

    fileprivate static let defaultZoomRatio: Double = 1
    private static let singleFrameDurationNumerator: Int64 = 1
}

extension CaptureService: CameraSetupServicing {}

private final class CaptureOutputDelegate: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    private let encoder: VideoToolboxEncoder

    init(encoder: VideoToolboxEncoder) {
        self.encoder = encoder
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        encoder.submit(sampleBuffer: sampleBuffer)
    }
}
