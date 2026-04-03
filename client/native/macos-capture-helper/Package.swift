// swift-tools-version: 6.0
import PackageDescription

let package = Package(
	name: "BlockChatMacCaptureHelper",
	platforms: [
		.macOS(.v15)
	],
	products: [
		.executable(
			name: "BlockChatMacCaptureHelper",
			targets: ["BlockChatMacCaptureHelper"]
		)
	],
	targets: [
		.executableTarget(
			name: "BlockChatMacCaptureHelper"
		)
	]
)
