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
    func cameraState() async -> CameraState
}

public actor CaptureService {
    public private(set) var state: CameraState = .initial

    private let cameraSelector: CameraDeviceSelector
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
    private var activeConfiguration: StreamConfiguration?
    private var zoomMapping: CameraZoomMapping?

    public init(cameraSelector: CameraDeviceSelector = CameraDeviceSelector()) {
        self.cameraSelector = cameraSelector
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

    public func prepare(
        configuration: StreamConfiguration,
        position: CameraPosition,
        onAccessUnit: @escaping @Sendable (Result<EncodedAccessUnit, VideoToolboxEncoderError>) -> Void
    ) async throws {
        guard session == nil, encoder == nil else {
            throw CaptureServiceError.configurationFailed("Capture service is already prepared")
        }
        guard authorizationState() == .authorized else { throw CaptureServiceError.permissionDenied }
        guard let device = cameraSelector.defaultDevice(position: position) else {
            throw CaptureServiceError.deviceUnavailable
        }
        guard let format = cameraSelector.compatibleFormat(
            for: configuration.resolution,
            fps: configuration.fps,
            on: device
        ) else {
            throw CaptureServiceError.formatUnavailable
        }
        do {
            try await configure(
                device: device,
                format: format,
                configuration: configuration
            )
        } catch {
            throw error
        }
        let encoder = VideoToolboxEncoder()
        encoder.onAccessUnit = onAccessUnit
        do {
            try encoder.prepare(configuration: configuration)
            guard let output = videoOutput else { throw CaptureServiceError.notPrepared }
            let delegate = CaptureOutputDelegate(encoder: encoder)
            sessionQueue.sync {
                output.setSampleBufferDelegate(delegate, queue: encoder.inputQueue)
            }
            outputDelegate = delegate
        } catch {
            encoder.invalidate()
            await stop()
            throw error
        }
        self.encoder = encoder
        activeConfiguration = configuration
    }

    public func start() async throws {
        guard let session else { throw CaptureServiceError.notPrepared }
        let isRunning = sessionQueue.sync {
            if !session.isRunning { session.startRunning() }
            return session.isRunning
        }
        guard isRunning else {
            throw CaptureServiceError.runtimeError("Capture session did not enter the running state")
        }
        // Request a fresh IDR after the session is running so the already-armed
        // RTP consumer starts OBS with a decodable access unit even if an
        // earlier callback raced capture startup.
        encoder?.requestKeyframe()
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
        activeConfiguration = nil
        zoomMapping = nil
        selectedPosition = .back
        state.position = .back
        state.selectedFormat = nil
        state.selectedDeviceID = nil
        state.minimumZoomRatio = CameraZoomPolicy.normalTarget
        state.maximumZoomRatio = CameraZoomPolicy.normalTarget
        state.zoomRatio = CameraZoomPolicy.normalTarget
        state.zoomTargets = [CameraZoomPolicy.normalTarget]
        state.activeStabilization = .off
        removeNotifications()
    }

    public func encoderMetrics() async -> VideoToolboxEncoderMetrics? {
        encoder?.metrics()
    }

    public func cameraState() async -> CameraState {
        state.activeStabilization = Self.activeStabilization(for: videoOutput?.connection(with: .video))
        return state
    }

    public func previewLayer() -> AVCaptureVideoPreviewLayer? {
        guard let session else { return nil }
        if let cachedPreviewLayer { return cachedPreviewLayer }
        let resolution = SessionOrientationResolver().resolve(rotation: selectedOrientation, cameraPosition: selectedPosition)
        let layer = sessionQueue.sync {
            let layer = AVCaptureVideoPreviewLayer(session: session)
            layer.videoGravity = .resizeAspectFill
            Self.configurePreviewConnection(
                layer.connection,
                position: selectedPosition,
                rotationAngle: resolution.previewRotationAngle
            )
            return layer
        }
        cachedPreviewLayer = layer
        return layer
    }

    public func setZoomRatio(_ ratio: Double) throws {
        guard let device, let zoomMapping else { throw CaptureServiceError.notPrepared }
        let rawRatio = zoomMapping.rawRatio(forUserRatio: ratio)
        try sessionQueue.sync {
            try device.lockForConfiguration()
            defer { device.unlockForConfiguration() }
            device.videoZoomFactor = CGFloat(rawRatio)
        }
        state.zoomRatio = zoomMapping.userRatio(forRawRatio: rawRatio)
    }

    public func switchCamera() async throws {
        guard let session,
              let currentDevice = device,
              let currentInput = videoInput,
              let output = videoOutput,
              let configuration = activeConfiguration,
              let encoder else {
            throw CaptureServiceError.notPrepared
        }
        let targetPosition = selectedPosition.opposite
        guard let targetDevice = cameraSelector.defaultDevice(position: targetPosition) else {
            throw CaptureServiceError.deviceUnavailable
        }
        guard let targetFormat = cameraSelector.compatibleFormat(
            for: configuration.resolution,
            fps: configuration.fps,
            on: targetDevice
        ) else {
            throw CaptureServiceError.formatUnavailable
        }
        let targetInput: AVCaptureDeviceInput
        do {
            targetInput = try AVCaptureDeviceInput(device: targetDevice)
        } catch {
            throw CaptureServiceError.configurationFailed(String(describing: error))
        }
        let switchResult = sessionQueue.sync { () -> CameraSwitchResult in
            let currentFormat = currentDevice.activeFormat
            session.beginConfiguration()
            session.removeInput(currentInput)
            guard session.canAddInput(targetInput) else {
                let restored = Self.restoreCameraInput(
                    currentInput,
                    device: currentDevice,
                    format: currentFormat,
                    fps: configuration.fps,
                    session: session,
                    output: output
                )
                session.commitConfiguration()
                return Self.restoredCameraInputIsUsable(
                    restored,
                    session: session,
                    output: output,
                    device: currentDevice,
                    format: currentFormat,
                    fps: configuration.fps
                ) ? .rejected : .rollbackFailed
            }
            session.addInput(targetInput)
            Self.configureOutputConnection(output.connection(with: .video))
            var targetZoomMapping: CameraZoomMapping
            do {
                targetZoomMapping = try Self.configure(
                    device: targetDevice,
                    format: targetFormat.format,
                    fps: configuration.fps
                )
            } catch {
                session.removeInput(targetInput)
                let restored = Self.restoreCameraInput(
                    currentInput,
                    device: currentDevice,
                    format: currentFormat,
                    fps: configuration.fps,
                    session: session,
                    output: output
                )
                session.commitConfiguration()
                return Self.restoredCameraInputIsUsable(
                    restored,
                    session: session,
                    output: output,
                    device: currentDevice,
                    format: currentFormat,
                    fps: configuration.fps
                ) ? .rejected : .rollbackFailed
            }
            session.commitConfiguration()
            if !Self.matchesRequestedCameraConfiguration(
                device: targetDevice,
                format: targetFormat.format,
                fps: configuration.fps
            ) {
                do {
                    targetZoomMapping = try Self.configureExactWithoutStabilization(
                        session: session,
                        output: output,
                        device: targetDevice,
                        format: targetFormat.format,
                        fps: configuration.fps
                    )
                } catch {
                    session.beginConfiguration()
                    session.removeInput(targetInput)
                    let restored = Self.restoreCameraInput(
                        currentInput,
                        device: currentDevice,
                        format: currentFormat,
                        fps: configuration.fps,
                        session: session,
                        output: output
                    )
                    session.commitConfiguration()
                    return Self.restoredCameraInputIsUsable(
                        restored,
                        session: session,
                        output: output,
                        device: currentDevice,
                        format: currentFormat,
                        fps: configuration.fps
                    ) ? .rejected : .rollbackFailed
                }
            }
            guard Self.matchesRequestedCameraConfiguration(
                device: targetDevice,
                format: targetFormat.format,
                fps: configuration.fps
            ) else {
                session.beginConfiguration()
                session.removeInput(targetInput)
                let restored = Self.restoreCameraInput(
                    currentInput,
                    device: currentDevice,
                    format: currentFormat,
                    fps: configuration.fps,
                    session: session,
                    output: output
                )
                session.commitConfiguration()
                return Self.restoredCameraInputIsUsable(
                    restored,
                    session: session,
                    output: output,
                    device: currentDevice,
                    format: currentFormat,
                    fps: configuration.fps
                ) ? .rejected : .rollbackFailed
            }
            return .switched(targetZoomMapping)
        }
        switch switchResult {
        case .rejected:
            throw CaptureServiceError.configurationFailed("AVCaptureSession rejected the opposite camera")
        case .rollbackFailed:
            state.runtimeError = "Camera switch failed and the prior input could not be restored"
            throw CaptureServiceError.runtimeError(state.runtimeError ?? "Camera switch rollback failed")
        case let .switched(targetZoomMapping):
            self.device = targetDevice
            videoInput = targetInput
            selectedPosition = targetPosition
            zoomMapping = targetZoomMapping
            state.position = targetPosition
            state.selectedDeviceID = targetDevice.uniqueID
            state.selectedFormat = targetFormat.descriptor
            state.minimumZoomRatio = targetZoomMapping.minimumUserRatio
            state.maximumZoomRatio = targetZoomMapping.maximumUserRatio
            state.zoomRatio = CameraZoomPolicy.normalTarget
            state.zoomTargets = targetZoomMapping.targets
        }
        state.runtimeError = nil
        state.activeStabilization = Self.activeStabilization(for: output.connection(with: .video))
        state.systemPressureLevel = CameraSystemPressureLevel(level: targetDevice.systemPressureState.level)
        observeSystemPressure(on: targetDevice)
        let orientation = SessionOrientationResolver().resolve(
            rotation: selectedOrientation,
            cameraPosition: targetPosition
        )
        sessionQueue.sync {
            Self.configurePreviewConnection(
                cachedPreviewLayer?.connection,
                position: targetPosition,
                rotationAngle: orientation.previewRotationAngle
            )
        }
        encoder.requestKeyframe()
    }

    public func selectedPreviewOrientation(_ orientation: StreamRotation) {
        selectedOrientation = orientation
        let resolution = SessionOrientationResolver().resolve(rotation: orientation, cameraPosition: selectedPosition)
        let layer = cachedPreviewLayer
        sessionQueue.sync {
            Self.configurePreviewConnection(
                layer?.connection,
                position: selectedPosition,
                rotationAngle: resolution.previewRotationAngle
            )
        }
    }

    private func configure(
        device: AVCaptureDevice,
        format: CameraFormatSelection,
        configuration: StreamConfiguration
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
        output.automaticallyConfiguresOutputBufferDimensions = false
        output.deliversPreviewSizedOutputBuffers = false
        let avFormat = format.format
        let configurationResult = sessionQueue.sync { () -> (error: CaptureServiceError?, zoomMapping: CameraZoomMapping?) in
            var configurationError: CaptureServiceError?
            var configuredZoomMapping: CameraZoomMapping?
            session.beginConfiguration()
            session.sessionPreset = .inputPriority
            if session.canAddInput(input) {
                session.addInput(input)
                do {
                    configuredZoomMapping = try Self.configure(
                        device: device,
                        format: avFormat,
                        fps: configuration.fps
                    )
                } catch {
                    configurationError = .configurationFailed(String(describing: error))
                }
                if configurationError == nil {
                    // AVFoundation validates explicit output dimensions against
                    // the device's current activeFormat. Select the compatible
                    // source first so a valid larger-source downscale cannot be
                    // rejected against the device's previous default format.
                    output.videoSettings = [
                        kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
                        kCVPixelBufferWidthKey as String: configuration.geometry.codedWidth,
                        kCVPixelBufferHeightKey as String: configuration.geometry.codedHeight,
                    ]
                    if session.canAddOutput(output) {
                        session.addOutput(output)
                        Self.configureOutputConnection(output.connection(with: .video))
                    } else {
                        configurationError = .configurationFailed("Capture session rejected the video output")
                    }
                }
            } else {
                configurationError = .configurationFailed("Capture session rejected the video input")
            }
            session.commitConfiguration()
            return (configurationError, configuredZoomMapping)
        }
        if let configurationError = configurationResult.error { throw configurationError }
        guard let configuredZoomMapping = configurationResult.zoomMapping else {
            throw CaptureServiceError.configurationFailed("Camera zoom mapping was not configured")
        }
        let exactZoomMapping: CameraZoomMapping
        if sessionQueue.sync(execute: {
            Self.matchesRequestedCameraConfiguration(device: device, format: avFormat, fps: configuration.fps)
        }) {
            exactZoomMapping = configuredZoomMapping
        } else {
            exactZoomMapping = try sessionQueue.sync {
                try Self.configureExactWithoutStabilization(
                    session: session,
                    output: output,
                    device: device,
                    format: avFormat,
                    fps: configuration.fps
                )
            }
        }
        self.session = session
        self.device = device
        self.videoInput = input
        self.videoOutput = output
        self.selectedPosition = device.position == .front ? .front : .back
        zoomMapping = exactZoomMapping
        selectedPreviewOrientation(configuration.orientation)
        state.authorization = authorizationState()
        state.position = selectedPosition
        state.selectedDeviceID = device.uniqueID
        state.selectedFormat = format.descriptor
        state.minimumZoomRatio = exactZoomMapping.minimumUserRatio
        state.maximumZoomRatio = exactZoomMapping.maximumUserRatio
        state.zoomRatio = exactZoomMapping.userRatio(forRawRatio: Double(device.videoZoomFactor))
        state.zoomTargets = exactZoomMapping.targets
        state.activeStabilization = Self.activeStabilization(for: output.connection(with: .video))
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
        if let device { observeSystemPressure(on: device) }
    }

    private func removeNotifications() {
        for token in notificationTokens { NotificationCenter.default.removeObserver(token) }
        notificationTokens.removeAll(keepingCapacity: true)
        systemPressureObservation?.invalidate()
        systemPressureObservation = nil
    }

    private func observeSystemPressure(on device: AVCaptureDevice) {
        systemPressureObservation?.invalidate()
        systemPressureObservation = device.observe(\AVCaptureDevice.systemPressureState, options: [.new]) { [weak self] _, _ in
            Task { await self?.updateSystemPressure() }
        }
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

    private static func configure(
        device: AVCaptureDevice,
        format: AVCaptureDevice.Format,
        fps: Int
    ) throws -> CameraZoomMapping {
        try device.lockForConfiguration()
        defer { device.unlockForConfiguration() }
        device.activeFormat = format
        let duration = CMTime(value: singleFrameDurationNumerator, timescale: CMTimeScale(fps))
        device.activeVideoMinFrameDuration = duration
        device.activeVideoMaxFrameDuration = duration
        guard device.activeVideoMinFrameDuration == duration,
              device.activeVideoMaxFrameDuration == duration else {
            throw CaptureServiceError.configurationFailed("Camera rejected the requested fixed frame duration")
        }
        let mapping = zoomMapping(for: device)
        device.videoZoomFactor = CGFloat(mapping.rawRatio(forUserRatio: CameraZoomPolicy.normalTarget))
        return mapping
    }

    private static func zoomMapping(for device: AVCaptureDevice) -> CameraZoomMapping {
        let displayMultiplier: Double
        if #available(iOS 18.0, *) {
            displayMultiplier = Double(device.displayVideoZoomFactorMultiplier)
        } else {
            // iOS 17 lacks Apple's display multiplier. On the logical cameras
            // that include ultra-wide, the first documented switch-over factor
            // is the raw factor for the normal 1× constituent.
            switch device.deviceType {
            case .builtInTripleCamera, .builtInDualWideCamera:
                if let wideSwitchFactor = device.virtualDeviceSwitchOverVideoZoomFactors.first?.doubleValue,
                   wideSwitchFactor > .zero {
                    displayMultiplier = CameraZoomPolicy.normalTarget / wideSwitchFactor
                } else {
                    displayMultiplier = CameraZoomPolicy.normalTarget
                }
            default:
                displayMultiplier = CameraZoomPolicy.normalTarget
            }
        }
        return CameraZoomMapping(
            rawMinimum: Double(device.minAvailableVideoZoomFactor),
            rawMaximum: Double(device.maxAvailableVideoZoomFactor),
            displayMultiplier: displayMultiplier
        )
    }

    private static func configureOutputConnection(_ connection: AVCaptureConnection?) {
        guard let connection else { return }
        connection.automaticallyAdjustsVideoMirroring = false
        if connection.isVideoMirroringSupported {
            connection.isVideoMirrored = false
        }
        if connection.isVideoRotationAngleSupported(unrotatedVideoAngle) {
            connection.videoRotationAngle = unrotatedVideoAngle
        }
        if connection.isVideoStabilizationSupported {
            connection.preferredVideoStabilizationMode = .auto
        }
    }

    private static func disableOutputStabilization(_ connection: AVCaptureConnection?) {
        guard let connection, connection.isVideoStabilizationSupported else { return }
        connection.preferredVideoStabilizationMode = .off
    }

    private static func activeStabilization(for connection: AVCaptureConnection?) -> CameraStabilizationPreference {
        guard let connection, connection.isVideoStabilizationSupported else { return .off }
        return CameraStabilizationPreference(mode: connection.activeVideoStabilizationMode)
    }

    private static func configureExactWithoutStabilization(
        session: AVCaptureSession,
        output: AVCaptureVideoDataOutput,
        device: AVCaptureDevice,
        format: AVCaptureDevice.Format,
        fps: Int
    ) throws -> CameraZoomMapping {
        var mapping: CameraZoomMapping?
        var configurationError: Error?
        session.beginConfiguration()
        disableOutputStabilization(output.connection(with: .video))
        do {
            mapping = try configure(device: device, format: format, fps: fps)
        } catch {
            configurationError = error
        }
        session.commitConfiguration()
        if let configurationError { throw configurationError }
        guard let mapping else {
            throw CaptureServiceError.configurationFailed("Camera zoom mapping was not configured")
        }
        guard matchesRequestedCameraConfiguration(device: device, format: format, fps: fps) else {
            throw CaptureServiceError.configurationFailed(
                "Camera changed the requested format or fixed frame duration"
            )
        }
        return mapping
    }

    private static func matchesRequestedCameraConfiguration(
        device: AVCaptureDevice,
        format: AVCaptureDevice.Format,
        fps: Int
    ) -> Bool {
        let duration = CMTime(value: singleFrameDurationNumerator, timescale: CMTimeScale(fps))
        return device.activeFormat === format
            && device.activeVideoMinFrameDuration == duration
            && device.activeVideoMaxFrameDuration == duration
    }

    private static func restoreCameraInput(
        _ input: AVCaptureDeviceInput,
        device: AVCaptureDevice,
        format: AVCaptureDevice.Format,
        fps: Int,
        session: AVCaptureSession,
        output: AVCaptureVideoDataOutput
    ) -> Bool {
        guard session.canAddInput(input) else { return false }
        session.addInput(input)
        configureOutputConnection(output.connection(with: .video))
        do {
            _ = try configure(device: device, format: format, fps: fps)
            return matchesRequestedCameraConfiguration(device: device, format: format, fps: fps)
        } catch {
            return false
        }
    }

    private static func restoredCameraInputIsUsable(
        _ restored: Bool,
        session: AVCaptureSession,
        output: AVCaptureVideoDataOutput,
        device: AVCaptureDevice,
        format: AVCaptureDevice.Format,
        fps: Int
    ) -> Bool {
        guard restored else { return false }
        if matchesRequestedCameraConfiguration(device: device, format: format, fps: fps) {
            return true
        }
        return (try? configureExactWithoutStabilization(
            session: session,
            output: output,
            device: device,
            format: format,
            fps: fps
        )) != nil
    }

    private static func configurePreviewConnection(
        _ connection: AVCaptureConnection?,
        position: CameraPosition,
        rotationAngle: CGFloat
    ) {
        guard let connection else { return }
        connection.automaticallyAdjustsVideoMirroring = false
        if connection.isVideoMirroringSupported {
            connection.isVideoMirrored = position == .front
        }
        if connection.isVideoRotationAngleSupported(rotationAngle) {
            connection.videoRotationAngle = rotationAngle
        }
    }

    private static let singleFrameDurationNumerator: Int64 = 1
    private static let unrotatedVideoAngle: CGFloat = 0

    private enum CameraSwitchResult {
        case switched(CameraZoomMapping)
        case rejected
        case rollbackFailed
    }
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
