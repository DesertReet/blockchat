import AppKit
import AVFAudio
import AVFoundation
import CoreGraphics
import CoreMedia
import Foundation
import ScreenCaptureKit

private enum HelperError: Error, LocalizedError {
	case missingValue(String)
	case invalidArgument(String)
	case noDisplayAvailable
	case unsupportedMacOSVersion
	case recordingNotStarted

	var errorDescription: String? {
		switch self {
		case .missingValue(let flag):
			return "Missing value for \(flag)"
		case .invalidArgument(let message):
			return message
		case .noDisplayAvailable:
			return "ScreenCaptureKit did not report any display to anchor capture"
		case .unsupportedMacOSVersion:
			return "BlockChat macOS video capture requires macOS 15 or newer"
		case .recordingNotStarted:
			return "ScreenCaptureKit recording output never started"
		}
	}
}

private struct Arguments {
	let videoOutput: URL
	let systemAudioOutput: URL
	let microphoneOutput: URL?
	let microphoneDeviceID: String?
	let readyFile: URL?
	let capturePID: Int32
	let maxVideoHeight: Int

	static func parse() throws -> Arguments {
		var iterator = CommandLine.arguments.dropFirst().makeIterator()
		var videoOutput: URL?
		var systemAudioOutput: URL?
		var microphoneOutput: URL?
		var microphoneDeviceID: String?
		var readyFile: URL?
		var capturePID: Int32?
		var maxVideoHeight = 1080

		while let arg = iterator.next() {
			switch arg {
			case "--video-out":
				guard let value = iterator.next() else {
					throw HelperError.missingValue(arg)
				}
				videoOutput = URL(fileURLWithPath: value)
			case "--system-audio-out":
				guard let value = iterator.next() else {
					throw HelperError.missingValue(arg)
				}
				systemAudioOutput = URL(fileURLWithPath: value)
			case "--microphone-out":
				guard let value = iterator.next() else {
					throw HelperError.missingValue(arg)
				}
				microphoneOutput = URL(fileURLWithPath: value)
			case "--microphone-device-id":
				guard let value = iterator.next() else {
					throw HelperError.missingValue(arg)
				}
				microphoneDeviceID = value
			case "--ready-file":
				guard let value = iterator.next() else {
					throw HelperError.missingValue(arg)
				}
				readyFile = URL(fileURLWithPath: value)
			case "--capture-pid":
				guard let value = iterator.next(), let parsed = Int32(value) else {
					throw HelperError.missingValue(arg)
				}
				capturePID = parsed
			case "--max-video-height":
				guard let value = iterator.next(), let parsed = Int(value), parsed > 0 else {
					throw HelperError.invalidArgument("Invalid value for --max-video-height")
				}
				maxVideoHeight = parsed
			default:
				throw HelperError.invalidArgument("Unknown argument: \(arg)")
			}
		}

		guard let videoOutput else {
			throw HelperError.invalidArgument("Missing required --video-out")
		}
		guard let systemAudioOutput else {
			throw HelperError.invalidArgument("Missing required --system-audio-out")
		}
		guard let capturePID else {
			throw HelperError.invalidArgument("Missing required --capture-pid")
		}
		if microphoneDeviceID != nil && microphoneOutput == nil {
			throw HelperError.invalidArgument("--microphone-device-id requires --microphone-out")
		}

		return Arguments(
			videoOutput: videoOutput,
			systemAudioOutput: systemAudioOutput,
			microphoneOutput: microphoneOutput,
			microphoneDeviceID: microphoneDeviceID,
			readyFile: readyFile,
			capturePID: capturePID,
			maxVideoHeight: maxVideoHeight
		)
	}
}

private final class SampleBufferWavWriter {
	private let outputURL: URL
	private var audioFile: AVAudioFile?
	private var audioFormat: AVAudioFormat?

	init(outputURL: URL) {
		self.outputURL = outputURL
	}

	func append(_ sampleBuffer: CMSampleBuffer) throws {
		guard CMSampleBufferDataIsReady(sampleBuffer) else {
			return
		}
		guard let formatDescription = CMSampleBufferGetFormatDescription(sampleBuffer),
			  let asbdPointer = CMAudioFormatDescriptionGetStreamBasicDescription(formatDescription),
			  let avFormat = AVAudioFormat(streamDescription: asbdPointer) else {
			return
		}

		if audioFile == nil {
			try FileManager.default.createDirectory(
				at: outputURL.deletingLastPathComponent(),
				withIntermediateDirectories: true
			)
			audioFile = try AVAudioFile(
				forWriting: outputURL,
				settings: avFormat.settings,
				commonFormat: avFormat.commonFormat,
				interleaved: avFormat.isInterleaved
			)
			audioFormat = avFormat
		}

		guard let audioFile, let audioFormat else {
			return
		}

		let frameCount = AVAudioFrameCount(CMSampleBufferGetNumSamples(sampleBuffer))
		guard frameCount > 0,
			  let pcmBuffer = AVAudioPCMBuffer(
				pcmFormat: audioFormat,
				frameCapacity: frameCount
			  ) else {
			return
		}
		pcmBuffer.frameLength = frameCount

		let bufferList = pcmBuffer.mutableAudioBufferList
		let status = CMSampleBufferCopyPCMDataIntoAudioBufferList(
			sampleBuffer,
			at: 0,
			frameCount: Int32(frameCount),
			into: bufferList
		)
		guard status == noErr else {
			throw NSError(
				domain: NSOSStatusErrorDomain,
				code: Int(status),
				userInfo: [NSLocalizedDescriptionKey: "Failed to copy PCM sample buffer data"]
			)
		}

		try audioFile.write(from: pcmBuffer)
	}
}

private struct CaptureTarget {
	let filter: SCContentFilter
	let description: String
	let outputWidth: Int
	let outputHeight: Int
}

private final class CaptureController: NSObject, SCStreamOutput, SCStreamDelegate, SCRecordingOutputDelegate {
	private let arguments: Arguments
	private let callbackQueue = DispatchQueue(label: "blockchat.macos.helper.capture")
	private let systemAudioWriter: SampleBufferWavWriter
	private let microphoneWriter: SampleBufferWavWriter?

	private var stream: SCStream?
	private var recordingOutput: SCRecordingOutput?
	private var readySignaled = false
	private var stopping = false
	private var continuationResumed = false
	private var recordingStarted = false
	private var stopContinuation: CheckedContinuation<Void, Never>?
	private var intSource: DispatchSourceSignal?
	private var termSource: DispatchSourceSignal?
	private var captureTarget: CaptureTarget?
	private var systemAudioSampleCount: Int = 0
	private var microphoneSampleCount: Int = 0

	init(arguments: Arguments) {
		self.arguments = arguments
		self.systemAudioWriter = SampleBufferWavWriter(outputURL: arguments.systemAudioOutput)
		self.microphoneWriter = arguments.microphoneOutput.map(SampleBufferWavWriter.init)
		super.init()
	}

	func start() async throws {
		guard #available(macOS 15.0, *) else {
			throw HelperError.unsupportedMacOSVersion
		}

		fputs("BlockChatMacCaptureHelper: microphone_capture_requested=\(microphoneWriter != nil) microphone_device_id=\(arguments.microphoneDeviceID ?? "none") microphone_output=\(arguments.microphoneOutput?.path ?? "none")\n", stderr)

		// List available audio input devices for debugging
		let inputDevices = AVCaptureDevice.DiscoverySession(
			deviceTypes: [.microphone, .external],
			mediaType: .audio,
			position: .unspecified
		).devices
		fputs("BlockChatMacCaptureHelper: available_audio_input_devices=\(inputDevices.count)\n", stderr)
		for device in inputDevices {
			fputs("BlockChatMacCaptureHelper:   device uniqueID=\(device.uniqueID) localizedName=\(device.localizedName) modelID=\(device.modelID)\n", stderr)
		}

		let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
		let captureTarget = try resolveTarget(content: content, arguments: arguments)
		self.captureTarget = captureTarget

		let configuration = buildStreamConfiguration(for: captureTarget, arguments: arguments)
		fputs("BlockChatMacCaptureHelper: stream_config captureMicrophone=\(configuration.captureMicrophone) capturesAudio=\(configuration.capturesAudio) sampleRate=\(configuration.sampleRate) channelCount=\(configuration.channelCount) microphoneCaptureDeviceID=\(configuration.microphoneCaptureDeviceID ?? "default")\n", stderr)

		let stream = SCStream(filter: captureTarget.filter, configuration: configuration, delegate: self)
		try stream.addStreamOutput(self, type: .audio, sampleHandlerQueue: callbackQueue)
		if microphoneWriter != nil {
			try stream.addStreamOutput(self, type: .microphone, sampleHandlerQueue: callbackQueue)
			fputs("BlockChatMacCaptureHelper: added microphone stream output handler\n", stderr)
		}

		let recordingConfiguration = SCRecordingOutputConfiguration()
		recordingConfiguration.outputURL = arguments.videoOutput
		recordingConfiguration.videoCodecType = .h264
		recordingConfiguration.outputFileType = .mp4
		let recordingOutput = SCRecordingOutput(configuration: recordingConfiguration, delegate: self)
		try stream.addRecordingOutput(recordingOutput)

		self.stream = stream
		self.recordingOutput = recordingOutput
		installSignalHandlers()

		try FileManager.default.createDirectory(
			at: arguments.videoOutput.deletingLastPathComponent(),
			withIntermediateDirectories: true
		)
		try FileManager.default.createDirectory(
			at: arguments.systemAudioOutput.deletingLastPathComponent(),
			withIntermediateDirectories: true
		)
		if let microphoneOutput = arguments.microphoneOutput {
			try FileManager.default.createDirectory(
				at: microphoneOutput.deletingLastPathComponent(),
				withIntermediateDirectories: true
			)
		}

		try await stream.startCapture()
		await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
			self.stopContinuation = continuation
		}
	}

	func stop() {
		if stopping {
			return
		}
		stopping = true
		fputs("BlockChatMacCaptureHelper: stopping capture systemAudioSamples=\(systemAudioSampleCount) microphoneSamples=\(microphoneSampleCount)\n", stderr)

		// Report file sizes for audio sidecars
		let systemFileSize = (try? FileManager.default.attributesOfItem(atPath: arguments.systemAudioOutput.path)[.size] as? Int64) ?? -1
		fputs("BlockChatMacCaptureHelper: system_audio_wav_size=\(systemFileSize) path=\(arguments.systemAudioOutput.path)\n", stderr)
		if let micOutput = arguments.microphoneOutput {
			let micFileSize = (try? FileManager.default.attributesOfItem(atPath: micOutput.path)[.size] as? Int64) ?? -1
			fputs("BlockChatMacCaptureHelper: microphone_wav_size=\(micFileSize) path=\(micOutput.path)\n", stderr)
		} else {
			fputs("BlockChatMacCaptureHelper: no microphone output path configured\n", stderr)
		}

		let currentStream = stream
		Task {
			if let currentStream {
				try? await currentStream.stopCapture()
			}
			if !self.recordingStarted {
				self.finish()
			}
		}
	}

	func stream(_ stream: SCStream, didStopWithError error: Error) {
		fputs("BlockChatMacCaptureHelper stream stopped with error: \(error.localizedDescription)\n", stderr)
		stop()
	}

	func stream(
		_ stream: SCStream,
		didOutputSampleBuffer sampleBuffer: CMSampleBuffer,
		of outputType: SCStreamOutputType
	) {
		do {
			switch outputType {
			case .audio:
				systemAudioSampleCount += 1
				try systemAudioWriter.append(sampleBuffer)
			case .microphone:
				microphoneSampleCount += 1
				if microphoneSampleCount == 1 {
					let numSamples = CMSampleBufferGetNumSamples(sampleBuffer)
					let isReady = CMSampleBufferDataIsReady(sampleBuffer)
					fputs("BlockChatMacCaptureHelper: first microphone sample buffer received numSamples=\(numSamples) isReady=\(isReady)\n", stderr)
					if let formatDescription = CMSampleBufferGetFormatDescription(sampleBuffer),
					   let asbdPointer = CMAudioFormatDescriptionGetStreamBasicDescription(formatDescription) {
						let asbd = asbdPointer.pointee
						fputs("BlockChatMacCaptureHelper: mic format sampleRate=\(asbd.mSampleRate) channels=\(asbd.mChannelsPerFrame) bitsPerChannel=\(asbd.mBitsPerChannel) formatID=\(asbd.mFormatID)\n", stderr)
					} else {
						fputs("BlockChatMacCaptureHelper: WARNING could not read mic sample format description\n", stderr)
					}
				}
				try microphoneWriter?.append(sampleBuffer)
			default:
				break
			}
		} catch {
			fputs("BlockChatMacCaptureHelper write failure (type=\(outputType)): \(error.localizedDescription)\n", stderr)
			stop()
		}
	}

	func recordingOutputDidStartRecording(_ recordingOutput: SCRecordingOutput) {
		recordingStarted = true
		fputs("BlockChatMacCaptureHelper: recording output started, mic capture active=\(microphoneWriter != nil)\n", stderr)
		do {
			try writeReadyFile()
		} catch {
			fputs("BlockChatMacCaptureHelper ready file failure: \(error.localizedDescription)\n", stderr)
			stop()
		}
	}

	func recordingOutput(_ recordingOutput: SCRecordingOutput, didFailWithError error: Error) {
		fputs("BlockChatMacCaptureHelper recording failure: \(error.localizedDescription)\n", stderr)
		stop()
	}

	func recordingOutputDidFinishRecording(_ recordingOutput: SCRecordingOutput) {
		let videoFileSize = (try? FileManager.default.attributesOfItem(atPath: arguments.videoOutput.path)[.size] as? Int64) ?? -1
		fputs("BlockChatMacCaptureHelper: recording finished video_size=\(videoFileSize) systemAudioSamples=\(systemAudioSampleCount) microphoneSamples=\(microphoneSampleCount)\n", stderr)
		finish()
	}

	private func finish() {
		if continuationResumed {
			return
		}
		continuationResumed = true
		stopContinuation?.resume()
		stopContinuation = nil
	}

	@available(macOS 15.0, *)
	private func resolveTarget(content: SCShareableContent, arguments: Arguments) throws -> CaptureTarget {
		let matchingWindows = content.windows
			.filter { window in
				guard let application = window.owningApplication else {
					return false
				}
				return application.processID == arguments.capturePID
					&& window.isOnScreen
					&& window.windowLayer == 0
			}
			.sorted { lhs, rhs in
				if lhs.isActive != rhs.isActive {
					return lhs.isActive && !rhs.isActive
				}
				return windowArea(lhs) > windowArea(rhs)
			}

		if let window = matchingWindows.first {
			let filter = SCContentFilter(desktopIndependentWindow: window)
			let size = computeOutputSize(for: filter, defaultFrame: window.frame, maxVideoHeight: arguments.maxVideoHeight)
			return CaptureTarget(
				filter: filter,
				description: "window:\(window.windowID):\(window.title ?? "untitled")",
				outputWidth: size.width,
				outputHeight: size.height
			)
		}

		guard let display = content.displays.first else {
			throw HelperError.noDisplayAvailable
		}
		if let application = content.applications.first(where: { $0.processID == arguments.capturePID }) {
			let filter = SCContentFilter(display: display, including: [application], exceptingWindows: [])
			let size = computeOutputSize(for: filter, defaultFrame: display.frame, maxVideoHeight: arguments.maxVideoHeight)
			return CaptureTarget(
				filter: filter,
				description: "application:\(application.processID):\(application.applicationName)",
				outputWidth: size.width,
				outputHeight: size.height
			)
		}

		let filter = SCContentFilter(display: display, excludingApplications: [], exceptingWindows: [])
		let size = computeOutputSize(for: filter, defaultFrame: display.frame, maxVideoHeight: arguments.maxVideoHeight)
		return CaptureTarget(
			filter: filter,
			description: "display:\(display.displayID)",
			outputWidth: size.width,
			outputHeight: size.height
		)
	}

	@available(macOS 15.0, *)
	private func buildStreamConfiguration(for captureTarget: CaptureTarget, arguments: Arguments) -> SCStreamConfiguration {
		let configuration = SCStreamConfiguration()
		configuration.width = captureTarget.outputWidth
		configuration.height = captureTarget.outputHeight
		configuration.minimumFrameInterval = CMTime(value: 1, timescale: 60)
		configuration.showsCursor = true
		configuration.capturesAudio = true
		configuration.excludesCurrentProcessAudio = false
		configuration.sampleRate = 48_000
		configuration.channelCount = 2
		configuration.scalesToFit = false
		configuration.captureMicrophone = microphoneWriter != nil
		if let microphoneDeviceID = arguments.microphoneDeviceID, !microphoneDeviceID.isEmpty {
			let resolvedID = resolveMicrophoneDeviceID(javaDeviceID: microphoneDeviceID)
			if let resolvedID {
				configuration.microphoneCaptureDeviceID = resolvedID
				fputs("BlockChatMacCaptureHelper: resolved mic device ID: \(resolvedID)\n", stderr)
			} else {
				fputs("BlockChatMacCaptureHelper: WARNING could not resolve mic device ID, using system default mic\n", stderr)
			}
		}
		return configuration
	}

	/// Resolves a Java Sound mixer ID to a CoreAudio device uniqueID.
	/// Java Sound IDs look like: "Name|Vendor|Version|Description"
	/// CoreAudio uniqueIDs look like: "AppleUSBAudioEngine:Vendor:Model:Serial:channels"
	/// We try: exact match first, then name-based fuzzy match.
	private func resolveMicrophoneDeviceID(javaDeviceID: String) -> String? {
		let inputDevices = AVCaptureDevice.DiscoverySession(
			deviceTypes: [.microphone, .external],
			mediaType: .audio,
			position: .unspecified
		).devices

		// 1. Try exact match (in case a real CoreAudio ID was passed)
		if let exactMatch = inputDevices.first(where: { $0.uniqueID == javaDeviceID }) {
			fputs("BlockChatMacCaptureHelper: mic device exact match on uniqueID: \(exactMatch.localizedName)\n", stderr)
			return exactMatch.uniqueID
		}

		// 2. Extract the device name from the Java Sound ID format "Name|Vendor|Version|Description"
		let javaParts = javaDeviceID.split(separator: "|", maxSplits: 4).map { String($0) }
		let javaDeviceName = javaParts.first ?? javaDeviceID

		// 3. Try matching by localized name
		if let nameMatch = inputDevices.first(where: { $0.localizedName == javaDeviceName }) {
			fputs("BlockChatMacCaptureHelper: mic device matched by localizedName: \(nameMatch.localizedName) -> \(nameMatch.uniqueID)\n", stderr)
			return nameMatch.uniqueID
		}

		// 4. Try case-insensitive contains match
		let lowerName = javaDeviceName.lowercased()
		if let containsMatch = inputDevices.first(where: { $0.localizedName.lowercased().contains(lowerName) || lowerName.contains($0.localizedName.lowercased()) }) {
			fputs("BlockChatMacCaptureHelper: mic device fuzzy matched: \(containsMatch.localizedName) -> \(containsMatch.uniqueID)\n", stderr)
			return containsMatch.uniqueID
		}

		// 5. Try matching by model ID
		if let modelMatch = inputDevices.first(where: { $0.modelID.lowercased().contains(lowerName) || lowerName.contains($0.modelID.lowercased()) }) {
			fputs("BlockChatMacCaptureHelper: mic device matched by modelID: \(modelMatch.localizedName) -> \(modelMatch.uniqueID)\n", stderr)
			return modelMatch.uniqueID
		}

		fputs("BlockChatMacCaptureHelper: no mic device match found for javaDeviceID=\(javaDeviceID) name=\(javaDeviceName)\n", stderr)
		return nil
	}

	@available(macOS 15.0, *)
	private func computeOutputSize(for filter: SCContentFilter, defaultFrame: CGRect, maxVideoHeight: Int) -> (width: Int, height: Int) {
		let info = SCShareableContent.info(for: filter)
		let pixelScale = CGFloat(info.pointPixelScale)
		let baseWidth = max(2.0, info.contentRect.width * pixelScale)
		let baseHeight = max(2.0, info.contentRect.height * pixelScale)
		if baseHeight <= 0 {
			return downscaledSize(width: Int(defaultFrame.width), height: Int(defaultFrame.height), maxVideoHeight: maxVideoHeight)
		}
		return downscaledSize(width: Int(round(baseWidth)), height: Int(round(baseHeight)), maxVideoHeight: maxVideoHeight)
	}

	private func downscaledSize(width: Int, height: Int, maxVideoHeight: Int) -> (width: Int, height: Int) {
		let safeWidth = max(2, width)
		let safeHeight = max(2, height)
		let scale = safeHeight > maxVideoHeight
			? Double(maxVideoHeight) / Double(safeHeight)
			: 1.0
		let scaledWidth = even(max(2, Int((Double(safeWidth) * scale).rounded())))
		let scaledHeight = even(max(2, Int((Double(safeHeight) * scale).rounded())))
		return (scaledWidth, scaledHeight)
	}

	private func even(_ value: Int) -> Int {
		return value % 2 == 0 ? value : value - 1
	}

	private func windowArea(_ window: SCWindow) -> CGFloat {
		window.frame.width * window.frame.height
	}

	private func installSignalHandlers() {
		signal(SIGINT, SIG_IGN)
		signal(SIGTERM, SIG_IGN)

		let intSource = DispatchSource.makeSignalSource(signal: SIGINT, queue: callbackQueue)
		intSource.setEventHandler { [weak self] in
			self?.stop()
		}
		intSource.resume()
		self.intSource = intSource

		let termSource = DispatchSource.makeSignalSource(signal: SIGTERM, queue: callbackQueue)
		termSource.setEventHandler { [weak self] in
			self?.stop()
		}
		termSource.resume()
		self.termSource = termSource
	}

	private func writeReadyFile() throws {
		guard let readyFile = arguments.readyFile else {
			return
		}
		guard let captureTarget else {
			throw HelperError.recordingNotStarted
		}
		try FileManager.default.createDirectory(
			at: readyFile.deletingLastPathComponent(),
			withIntermediateDirectories: true
		)
		let payload: [String: Any?] = [
			"pid": ProcessInfo.processInfo.processIdentifier,
			"capturePID": arguments.capturePID,
			"videoPath": arguments.videoOutput.path,
			"systemAudioPath": arguments.systemAudioOutput.path,
			"microphonePath": arguments.microphoneOutput?.path,
			"microphoneDeviceID": arguments.microphoneDeviceID,
			"captureTarget": captureTarget.description,
			"width": captureTarget.outputWidth,
			"height": captureTarget.outputHeight
		]
		let sanitized = payload.reduce(into: [String: Any]()) { partialResult, entry in
			if let value = entry.value {
				partialResult[entry.key] = value
			}
		}
		let data = try JSONSerialization.data(withJSONObject: sanitized, options: [.prettyPrinted, .sortedKeys])
		try data.write(to: readyFile, options: .atomic)
		readySignaled = true
	}
}

@main
struct BlockChatMacCaptureHelperApp {
	static func main() async {
		do {
			initializeGraphicsContext()
			let arguments = try Arguments.parse()
			let controller = CaptureController(arguments: arguments)
			try await controller.start()
		} catch {
			fputs("BlockChatMacCaptureHelper fatal error: \(error.localizedDescription)\n", stderr)
			exit(1)
		}
	}

	@MainActor
	private static func initializeGraphicsContext() {
		_ = NSApplication.shared
		NSApp.setActivationPolicy(.prohibited)
		_ = CGMainDisplayID()
	}
}
