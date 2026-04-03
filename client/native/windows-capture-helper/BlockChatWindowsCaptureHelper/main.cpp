#include <windows.h>
#include <d3d11_4.h>
#include <dxgi1_6.h>
#include <dwmapi.h>
#include <ks.h>
#include <ksmedia.h>
#include <mfapi.h>
#include <mferror.h>
#include <mfidl.h>
#include <mfobjects.h>
#include <mfreadwrite.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <propvarutil.h>
#include <functiondiscoverykeys_devpkey.h>
#include <windows.graphics.capture.interop.h>
#include <windows.graphics.directx.direct3d11.interop.h>

#include <winrt/base.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Graphics.h>
#include <winrt/Windows.Graphics.Capture.h>
#include <winrt/Windows.Graphics.DirectX.h>
#include <winrt/Windows.Graphics.DirectX.Direct3D11.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <cwctype>
#include <filesystem>
#include <fstream>
#include <functional>
#include <iomanip>
#include <iostream>
#include <limits>
#include <mutex>
#include <optional>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <utility>
#include <vector>

using namespace winrt;
using namespace winrt::Windows::Foundation;
using namespace winrt::Windows::Graphics::Capture;
using namespace winrt::Windows::Graphics::DirectX;
using namespace winrt::Windows::Graphics::DirectX::Direct3D11;

namespace {

std::mutex gLogMutex;

constexpr UINT32 kAudioChannels = 2;
constexpr UINT32 kAudioSampleRate = 48'000;
constexpr UINT32 kAudioBitsPerSample = 16;
constexpr UINT32 kAudioChunkFrames = 1'024;
constexpr REFERENCE_TIME kHundredNanosecondsPerSecond = 10'000'000;
constexpr REFERENCE_TIME kAudioSafetyLag100ns = 1'000'000;
constexpr REFERENCE_TIME kDefaultAudioBuffer100ns = 2'000'000;
constexpr float kAudioNormalizationTriggerPeak = 0.20F;
constexpr float kAudioNormalizationTargetPeak = 0.50F;
constexpr float kAudioNormalizationMaxGain = 8.0F;
constexpr float kAudioSilenceFloor = 0.0001F;
constexpr UINT32 kDebugPacketLogLimit = 5;

struct Dimensions {
	UINT32 width = 0;
	UINT32 height = 0;
};

struct CaptureRegion {
	UINT32 left = 0;
	UINT32 top = 0;
	UINT32 width = 0;
	UINT32 height = 0;
};

struct Arguments {
	std::filesystem::path videoOut;
	std::filesystem::path readyFile;
	std::filesystem::path stopFile;
	std::filesystem::path debugAudioDir;
	DWORD capturePid = 0;
	int targetFps = 60;
	int maxVideoHeight = 1080;
	bool micEnabled = false;
	std::wstring microphoneDeviceId;
	std::wstring microphoneDeviceName;
};

struct AudioClientFormat {
	UINT32 sampleRate = kAudioSampleRate;
	UINT16 channelCount = kAudioChannels;
	UINT16 bitsPerSample = 32;
	bool isFloat = true;
};

struct AudioDebugSummary {
	std::wstring requestedSelector;
	std::wstring requestedPreferredName;
	std::wstring selectedDeviceId;
	std::wstring selectedDeviceName;
	std::wstring selectedDeviceDescription;
	std::wstring selectionMode;
	std::wstring initializeMode;
	std::wstring timelineMode;
	AudioClientFormat sourceFormat{};
	UINT64 packetCount = 0;
	UINT64 silentPacketCount = 0;
	UINT64 fallbackTimestampPacketCount = 0;
	INT64 totalCommittedFrames = 0;
	INT64 maxCommittedFrame = 0;
	float capturePeak = 0.0F;
	std::wstring error;
};

struct AudioBufferAnalysis {
	INT64 totalFrames = 0;
	UINT64 nonZeroSamples = 0;
	INT64 firstNonZeroFrame = -1;
	float peak = 0.0F;
	double rms = 0.0;
	double averageAbs = 0.0;
};

struct WindowCandidate {
	HWND hwnd = nullptr;
	bool foreground = false;
	RECT rect{};
	std::wstring title;
};

std::wstring Trim(std::wstring value) {
	auto notSpace = [](wchar_t ch) {
		return !iswspace(ch);
	};
	value.erase(value.begin(), std::find_if(value.begin(), value.end(), notSpace));
	value.erase(std::find_if(value.rbegin(), value.rend(), notSpace).base(), value.end());
	return value;
}

std::wstring ToLower(std::wstring value) {
	std::transform(value.begin(), value.end(), value.begin(), [](wchar_t ch) {
		return static_cast<wchar_t>(towlower(ch));
	});
	return value;
}

std::string WideToUtf8(const std::wstring& value) {
	if (value.empty()) {
		return {};
	}
	int required = WideCharToMultiByte(
		CP_UTF8,
		0,
		value.c_str(),
		static_cast<int>(value.size()),
		nullptr,
		0,
		nullptr,
		nullptr
	);
	if (required <= 0) {
		return {};
	}
	std::string result(static_cast<size_t>(required), '\0');
	WideCharToMultiByte(
		CP_UTF8,
		0,
		value.c_str(),
		static_cast<int>(value.size()),
		result.data(),
		required,
		nullptr,
		nullptr
	);
	return result;
}

std::wstring Utf8Literal(const char* value) {
	if (value == nullptr || *value == '\0') {
		return L"";
	}
	int required = MultiByteToWideChar(CP_UTF8, 0, value, -1, nullptr, 0);
	if (required <= 0) {
		return L"";
	}
	std::wstring result(static_cast<size_t>(required), L'\0');
	int converted = MultiByteToWideChar(CP_UTF8, 0, value, -1, result.data(), required);
	if (converted <= 0) {
		return L"";
	}
	if (!result.empty() && result.back() == L'\0') {
		result.pop_back();
	}
	return result;
}

void LogLine(const std::wstring& line) {
	std::lock_guard lock(gLogMutex);
	std::cerr << WideToUtf8(line) << std::endl;
}

[[noreturn]] void ThrowWithMessage(const std::wstring& message) {
	throw std::runtime_error(WideToUtf8(message));
}

[[noreturn]] void ThrowLastError(const std::wstring& prefix) {
	DWORD error = GetLastError();
	LPWSTR buffer = nullptr;
	FormatMessageW(
		FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
		nullptr,
		error,
		MAKELANGID(LANG_NEUTRAL, SUBLANG_DEFAULT),
		reinterpret_cast<LPWSTR>(&buffer),
		0,
		nullptr
	);
	std::wstring message = prefix;
	if (buffer != nullptr) {
		message += L": ";
		message += Trim(buffer);
		LocalFree(buffer);
	}
	ThrowWithMessage(message);
}

std::wstring JsonEscape(const std::wstring& value) {
	std::wostringstream out;
	for (wchar_t ch : value) {
		switch (ch) {
			case L'\\':
				out << L"\\\\";
				break;
			case L'"':
				out << L"\\\"";
				break;
			case L'\b':
				out << L"\\b";
				break;
			case L'\f':
				out << L"\\f";
				break;
			case L'\n':
				out << L"\\n";
				break;
			case L'\r':
				out << L"\\r";
				break;
			case L'\t':
				out << L"\\t";
				break;
			default:
				if (ch < 0x20) {
					out << L"\\u"
						<< std::hex
						<< std::setw(4)
						<< std::setfill(L'0')
						<< static_cast<int>(ch)
						<< std::dec
						<< std::setfill(L' ');
				} else {
					out << ch;
				}
		}
	}
	return out.str();
}

std::wstring RoleToString(ERole role) {
	switch (role) {
		case eConsole:
			return L"console";
		case eMultimedia:
			return L"multimedia";
		case eCommunications:
			return L"communications";
		default:
			return L"unknown";
	}
}

std::wstring AudioFormatToString(const AudioClientFormat& format) {
	std::wstringstream stream;
	stream << format.sampleRate << L"Hz/" << format.channelCount
		<< L"ch/" << format.bitsPerSample << (format.isFloat ? L"f" : L"i");
	return stream.str();
}

bool RectHasArea(const RECT& rect) {
	return rect.right > rect.left && rect.bottom > rect.top;
}

Dimensions DownscaleToMaxHeight(UINT32 width, UINT32 height, int maxHeight) {
	Dimensions result{std::max<UINT32>(2, width), std::max<UINT32>(2, height)};
	if (result.height <= static_cast<UINT32>(maxHeight)) {
		if ((result.width & 1U) != 0U) {
			result.width = std::max<UINT32>(2, result.width - 1U);
		}
		if ((result.height & 1U) != 0U) {
			result.height = std::max<UINT32>(2, result.height - 1U);
		}
		return result;
	}

	double scale = static_cast<double>(maxHeight) / static_cast<double>(result.height);
	UINT32 scaledWidth = static_cast<UINT32>(std::llround(static_cast<double>(result.width) * scale));
	result.width = std::max<UINT32>(2, scaledWidth);
	result.height = static_cast<UINT32>(maxHeight);
	if ((result.width & 1U) != 0U) {
		result.width = std::max<UINT32>(2, result.width - 1U);
	}
	if ((result.height & 1U) != 0U) {
		result.height = std::max<UINT32>(2, result.height - 1U);
	}
	return result;
}

CaptureRegion FullCaptureRegion(UINT32 width, UINT32 height) {
	return CaptureRegion{
		0,
		0,
		std::max<UINT32>(1, width),
		std::max<UINT32>(1, height)
	};
}

bool IsFullCaptureRegion(const CaptureRegion& region, UINT32 capturedWidth, UINT32 capturedHeight) {
	return region.left == 0 &&
		region.top == 0 &&
		region.width == capturedWidth &&
		region.height == capturedHeight;
}

bool TryGetWindowFrameBounds(HWND hwnd, RECT& boundsOut) {
	RECT extendedBounds{};
	if (SUCCEEDED(DwmGetWindowAttribute(
		hwnd,
		DWMWA_EXTENDED_FRAME_BOUNDS,
		&extendedBounds,
		static_cast<DWORD>(sizeof(extendedBounds))
	)) && RectHasArea(extendedBounds)) {
		boundsOut = extendedBounds;
		return true;
	}

	RECT windowRect{};
	if (GetWindowRect(hwnd, &windowRect) && RectHasArea(windowRect)) {
		boundsOut = windowRect;
		return true;
	}

	return false;
}

UINT32 ScaleWindowOffsetToCapture(LONG offset, LONG windowExtent, UINT32 captureExtent, bool ceilResult) {
	if (windowExtent <= 0 || captureExtent == 0) {
		return 0;
	}

	const double scaled = static_cast<double>(offset) * static_cast<double>(captureExtent) /
		static_cast<double>(windowExtent);
	double rounded = ceilResult ? std::ceil(scaled) : std::floor(scaled);
	rounded = std::clamp(rounded, 0.0, static_cast<double>(captureExtent));
	return static_cast<UINT32>(rounded);
}

CaptureRegion ComputeClientCaptureRegion(HWND hwnd, UINT32 capturedWidth, UINT32 capturedHeight) {
	CaptureRegion fullRegion = FullCaptureRegion(capturedWidth, capturedHeight);
	if (hwnd == nullptr || capturedWidth == 0 || capturedHeight == 0) {
		return fullRegion;
	}

	RECT frameBounds{};
	if (!TryGetWindowFrameBounds(hwnd, frameBounds)) {
		return fullRegion;
	}

	RECT clientRect{};
	if (!GetClientRect(hwnd, &clientRect) || !RectHasArea(clientRect)) {
		return fullRegion;
	}

	POINT clientTopLeft{clientRect.left, clientRect.top};
	POINT clientBottomRight{clientRect.right, clientRect.bottom};
	if (!ClientToScreen(hwnd, &clientTopLeft) || !ClientToScreen(hwnd, &clientBottomRight)) {
		return fullRegion;
	}

	const LONG frameWidth = frameBounds.right - frameBounds.left;
	const LONG frameHeight = frameBounds.bottom - frameBounds.top;
	if (frameWidth <= 0 || frameHeight <= 0) {
		return fullRegion;
	}

	const LONG clientLeft = std::clamp(clientTopLeft.x - frameBounds.left, 0L, frameWidth);
	const LONG clientTop = std::clamp(clientTopLeft.y - frameBounds.top, 0L, frameHeight);
	const LONG clientRight = std::clamp(clientBottomRight.x - frameBounds.left, 0L, frameWidth);
	const LONG clientBottom = std::clamp(clientBottomRight.y - frameBounds.top, 0L, frameHeight);
	if (clientRight <= clientLeft || clientBottom <= clientTop) {
		return fullRegion;
	}

	const UINT32 captureLeft = ScaleWindowOffsetToCapture(clientLeft, frameWidth, capturedWidth, false);
	const UINT32 captureTop = ScaleWindowOffsetToCapture(clientTop, frameHeight, capturedHeight, false);
	const UINT32 captureRight = ScaleWindowOffsetToCapture(clientRight, frameWidth, capturedWidth, true);
	const UINT32 captureBottom = ScaleWindowOffsetToCapture(clientBottom, frameHeight, capturedHeight, true);
	if (captureRight <= captureLeft || captureBottom <= captureTop) {
		return fullRegion;
	}

	return CaptureRegion{
		captureLeft,
		captureTop,
		captureRight - captureLeft,
		captureBottom - captureTop
	};
}

INT64 CurrentQpc100ns() {
	static LARGE_INTEGER frequency = [] {
		LARGE_INTEGER value{};
		QueryPerformanceFrequency(&value);
		return value;
	}();

	LARGE_INTEGER now{};
	QueryPerformanceCounter(&now);
	return static_cast<INT64>(
		(now.QuadPart * kHundredNanosecondsPerSecond) / frequency.QuadPart
	);
}

INT64 AudioFramesFromTime100ns(INT64 time100ns) {
	if (time100ns <= 0) {
		return 0;
	}
	return static_cast<INT64>(
		(time100ns * static_cast<INT64>(kAudioSampleRate)) / kHundredNanosecondsPerSecond
	);
}

INT64 AudioFrameToTime100ns(INT64 frameIndex) {
	if (frameIndex <= 0) {
		return 0;
	}
	return static_cast<INT64>(
		(frameIndex * kHundredNanosecondsPerSecond) / static_cast<INT64>(kAudioSampleRate)
	);
}

bool ParseBool(const std::wstring& value) {
	std::wstring lower = ToLower(value);
	if (lower == L"true" || lower == L"1" || lower == L"yes") {
		return true;
	}
	if (lower == L"false" || lower == L"0" || lower == L"no") {
		return false;
	}
	ThrowWithMessage(L"Invalid boolean value: " + value);
}

Arguments ParseArguments(int argc, wchar_t** argv) {
	Arguments args{};
	for (int index = 1; index < argc; ++index) {
		std::wstring flag = argv[index];
		auto requireValue = [&](const std::wstring& name) -> std::wstring {
			if (index + 1 >= argc) {
				ThrowWithMessage(L"Missing value for " + name);
			}
			return argv[++index];
		};

		if (flag == L"--video-out") {
			args.videoOut = requireValue(flag);
		} else if (flag == L"--ready-file") {
			args.readyFile = requireValue(flag);
		} else if (flag == L"--stop-file") {
			args.stopFile = requireValue(flag);
		} else if (flag == L"--debug-audio-dir") {
			requireValue(flag);
		} else if (flag == L"--capture-pid") {
			args.capturePid = static_cast<DWORD>(std::stoul(requireValue(flag)));
		} else if (flag == L"--target-fps") {
			args.targetFps = std::stoi(requireValue(flag));
		} else if (flag == L"--max-video-height") {
			args.maxVideoHeight = std::stoi(requireValue(flag));
		} else if (flag == L"--mic-enabled") {
			args.micEnabled = ParseBool(requireValue(flag));
		} else if (flag == L"--microphone-device-id") {
			args.microphoneDeviceId = requireValue(flag);
		} else if (flag == L"--microphone-device-name") {
			args.microphoneDeviceName = requireValue(flag);
		} else {
			ThrowWithMessage(L"Unknown argument: " + flag);
		}
	}

	if (args.videoOut.empty()) {
		ThrowWithMessage(L"Missing required --video-out");
	}
	if (args.readyFile.empty()) {
		ThrowWithMessage(L"Missing required --ready-file");
	}
	if (args.stopFile.empty()) {
		ThrowWithMessage(L"Missing required --stop-file");
	}
	if (args.capturePid == 0) {
		ThrowWithMessage(L"Missing required --capture-pid");
	}
	if (args.targetFps <= 0) {
		ThrowWithMessage(L"Invalid --target-fps");
	}
	if (args.maxVideoHeight <= 0) {
		ThrowWithMessage(L"Invalid --max-video-height");
	}

	return args;
}

std::wstring GetWindowTextValue(HWND hwnd) {
	int length = GetWindowTextLengthW(hwnd);
	if (length <= 0) {
		return L"";
	}
	std::wstring text(static_cast<size_t>(length) + 1U, L'\0');
	int copied = GetWindowTextW(hwnd, text.data(), length + 1);
	if (copied < 0) {
		return L"";
	}
	text.resize(static_cast<size_t>(copied));
	return text;
}

bool IsWindowCloaked(HWND hwnd) {
	DWORD cloaked = 0;
	if (SUCCEEDED(DwmGetWindowAttribute(hwnd, DWMWA_CLOAKED, &cloaked, sizeof(cloaked)))) {
		return cloaked != 0;
	}
	return false;
}

BOOL CALLBACK EnumWindowsProc(HWND hwnd, LPARAM lParam) {
	auto* context = reinterpret_cast<std::pair<DWORD, std::vector<WindowCandidate>*>*>(lParam);
	DWORD pid = 0;
	GetWindowThreadProcessId(hwnd, &pid);
	if (pid != context->first) {
		return TRUE;
	}
	if (!IsWindowVisible(hwnd)) {
		return TRUE;
	}
	if (GetAncestor(hwnd, GA_ROOT) != hwnd) {
		return TRUE;
	}
	if (GetWindow(hwnd, GW_OWNER) != nullptr) {
		return TRUE;
	}
	if ((GetWindowLongPtrW(hwnd, GWL_EXSTYLE) & WS_EX_TOOLWINDOW) != 0) {
		return TRUE;
	}
	if (IsWindowCloaked(hwnd)) {
		return TRUE;
	}

	RECT rect{};
	if (!GetWindowRect(hwnd, &rect)) {
		return TRUE;
	}
	if (rect.right <= rect.left || rect.bottom <= rect.top) {
		return TRUE;
	}

	WindowCandidate candidate{};
	candidate.hwnd = hwnd;
	candidate.foreground = (GetForegroundWindow() == hwnd);
	candidate.rect = rect;
	candidate.title = GetWindowTextValue(hwnd);
	context->second->push_back(std::move(candidate));
	return TRUE;
}

HWND FindBestWindowForProcess(DWORD pid, std::wstring& descriptionOut) {
	std::vector<WindowCandidate> candidates;
	std::pair<DWORD, std::vector<WindowCandidate>*> context{pid, &candidates};
	EnumWindows(EnumWindowsProc, reinterpret_cast<LPARAM>(&context));
	if (candidates.empty()) {
		ThrowWithMessage(L"No top-level visible window found for pid " + std::to_wstring(pid));
	}

	std::sort(candidates.begin(), candidates.end(), [](const WindowCandidate& left, const WindowCandidate& right) {
		if (left.foreground != right.foreground) {
			return left.foreground && !right.foreground;
		}
		const LONG leftArea = (left.rect.right - left.rect.left) * (left.rect.bottom - left.rect.top);
		const LONG rightArea = (right.rect.right - right.rect.left) * (right.rect.bottom - right.rect.top);
		return leftArea > rightArea;
	});

	const WindowCandidate& selected = candidates.front();
	std::wstringstream description;
	description << L"hwnd=0x" << std::hex << reinterpret_cast<uintptr_t>(selected.hwnd) << std::dec;
	if (!selected.title.empty()) {
		description << L" title=\"" << selected.title << L"\"";
	}
	descriptionOut = description.str();
	return selected.hwnd;
}

winrt::com_ptr<ID3D11Device> CreateD3DDevice(winrt::com_ptr<ID3D11DeviceContext>& contextOut) {
	UINT creationFlags = D3D11_CREATE_DEVICE_BGRA_SUPPORT | D3D11_CREATE_DEVICE_VIDEO_SUPPORT;
#if defined(_DEBUG)
	creationFlags |= D3D11_CREATE_DEVICE_DEBUG;
#endif

	D3D_FEATURE_LEVEL levels[] = {
		D3D_FEATURE_LEVEL_11_1,
		D3D_FEATURE_LEVEL_11_0,
		D3D_FEATURE_LEVEL_10_1,
		D3D_FEATURE_LEVEL_10_0,
	};

	winrt::com_ptr<ID3D11Device> device;
	D3D_FEATURE_LEVEL actualLevel{};
	HRESULT hr = D3D11CreateDevice(
		nullptr,
		D3D_DRIVER_TYPE_HARDWARE,
		nullptr,
		creationFlags,
		levels,
		static_cast<UINT>(std::size(levels)),
		D3D11_SDK_VERSION,
		device.put(),
		&actualLevel,
		contextOut.put()
	);
	if (FAILED(hr)) {
		hr = D3D11CreateDevice(
			nullptr,
			D3D_DRIVER_TYPE_WARP,
			nullptr,
			creationFlags,
			levels,
			static_cast<UINT>(std::size(levels)),
			D3D11_SDK_VERSION,
			device.put(),
			&actualLevel,
			contextOut.put()
		);
	}
	check_hresult(hr);
	return device;
}

IDirect3DDevice CreateDirect3DDevice(ID3D11Device* d3dDevice) {
	winrt::com_ptr<IDXGIDevice> dxgiDevice;
	check_hresult(d3dDevice->QueryInterface(dxgiDevice.put()));

	winrt::com_ptr<::IInspectable> inspectable;
	check_hresult(CreateDirect3D11DeviceFromDXGIDevice(dxgiDevice.get(), inspectable.put()));
	return inspectable.as<IDirect3DDevice>();
}

template <typename T>
winrt::com_ptr<T> GetDXGIInterfaceFromObject(const winrt::Windows::Foundation::IInspectable& object) {
	auto access = object.as<::Windows::Graphics::DirectX::Direct3D11::IDirect3DDxgiInterfaceAccess>();
	winrt::com_ptr<T> result;
	check_hresult(access->GetInterface(winrt::guid_of<T>(), result.put_void()));
	return result;
}

GraphicsCaptureItem CreateCaptureItemForWindow(HWND hwnd) {
	auto interop = winrt::get_activation_factory<GraphicsCaptureItem, IGraphicsCaptureItemInterop>();
	GraphicsCaptureItem item{nullptr};
	check_hresult(interop->CreateForWindow(hwnd, winrt::guid_of<GraphicsCaptureItem>(), winrt::put_abi(item)));
	return item;
}

std::wstring GetEndpointProperty(IMMDevice* device, const PROPERTYKEY& key) {
	winrt::com_ptr<IPropertyStore> store;
	check_hresult(device->OpenPropertyStore(STGM_READ, store.put()));
	PROPVARIANT value{};
	PropVariantInit(&value);
	const HRESULT hr = store->GetValue(key, &value);
	if (FAILED(hr)) {
		PropVariantClear(&value);
		return L"";
	}
	std::wstring result;
	if (value.vt == VT_LPWSTR && value.pwszVal != nullptr) {
		result = value.pwszVal;
	}
	PropVariantClear(&value);
	return result;
}

std::wstring GetEndpointId(IMMDevice* device) {
	LPWSTR rawId = nullptr;
	check_hresult(device->GetId(&rawId));
	std::wstring id = rawId != nullptr ? rawId : L"";
	if (rawId != nullptr) {
		CoTaskMemFree(rawId);
	}
	return id;
}

bool NormalizedEquals(const std::wstring& left, const std::wstring& right) {
	return ToLower(Trim(left)) == ToLower(Trim(right));
}

bool NormalizedContains(const std::wstring& haystack, const std::wstring& needle) {
	const std::wstring normalizedHaystack = ToLower(Trim(haystack));
	const std::wstring normalizedNeedle = ToLower(Trim(needle));
	return !normalizedNeedle.empty() && normalizedHaystack.find(normalizedNeedle) != std::wstring::npos;
}

std::wstring DeviceSelectorPrimaryToken(const std::wstring& selector) {
	size_t separator = selector.find(L'|');
	if (separator == std::wstring::npos) {
		return selector;
	}
	return selector.substr(0, separator);
}

std::vector<std::wstring> BuildCaptureSelectorCandidates(
	const std::wstring& selector,
	const std::wstring& preferredName
) {
	std::vector<std::wstring> candidates;

	auto appendCandidate = [&](const std::wstring& rawValue) {
		const std::wstring normalized = Trim(rawValue);
		if (normalized.empty()) {
			return;
		}
		for (const std::wstring& existing : candidates) {
			if (NormalizedEquals(existing, normalized)) {
				return;
			}
		}
		candidates.push_back(normalized);
	};

	appendCandidate(selector);
	appendCandidate(DeviceSelectorPrimaryToken(selector));
	appendCandidate(preferredName);

	size_t tokenStart = 0;
	while (tokenStart < selector.size()) {
		size_t tokenEnd = selector.find(L'|', tokenStart);
		std::wstring token = selector.substr(
			tokenStart,
			tokenEnd == std::wstring::npos ? std::wstring::npos : tokenEnd - tokenStart
		);
		appendCandidate(token);
		if (tokenEnd == std::wstring::npos) {
			break;
		}
		tokenStart = tokenEnd + 1;
	}

	return candidates;
}

winrt::com_ptr<IMMDevice> ResolveCaptureEndpoint(
	IMMDeviceEnumerator* enumerator,
	const std::wstring& selector,
	const std::wstring& preferredName,
	std::wstring& selectedNameOut,
	std::wstring* selectedIdOut = nullptr,
	std::wstring* selectedDescriptionOut = nullptr,
	std::wstring* selectionModeOut = nullptr
) {
	const std::vector<std::wstring> selectorCandidates = BuildCaptureSelectorCandidates(selector, preferredName);
	if (!selectorCandidates.empty()) {
		winrt::com_ptr<IMMDeviceCollection> collection;
		check_hresult(enumerator->EnumAudioEndpoints(eCapture, DEVICE_STATE_ACTIVE, collection.put()));

		UINT count = 0;
		check_hresult(collection->GetCount(&count));
		for (UINT index = 0; index < count; ++index) {
			winrt::com_ptr<IMMDevice> device;
			check_hresult(collection->Item(index, device.put()));
			std::wstring id = GetEndpointId(device.get());
			std::wstring friendly = GetEndpointProperty(device.get(), PKEY_Device_FriendlyName);
			std::wstring descriptor = GetEndpointProperty(device.get(), PKEY_Device_DeviceDesc);

			for (const std::wstring& candidate : selectorCandidates) {
				if (
					NormalizedEquals(id, candidate) ||
					NormalizedEquals(friendly, candidate) ||
					NormalizedEquals(descriptor, candidate) ||
					NormalizedContains(friendly, candidate) ||
					NormalizedContains(descriptor, candidate)
				) {
					selectedNameOut = friendly.empty() ? descriptor : friendly;
					if (selectedIdOut != nullptr) {
						*selectedIdOut = id;
					}
					if (selectedDescriptionOut != nullptr) {
						*selectedDescriptionOut = descriptor;
					}
					if (selectionModeOut != nullptr) {
						*selectionModeOut = L"selector_match(" + candidate + L")";
					}
					return device;
				}
			}
		}
	}

	for (ERole role : {eConsole, eCommunications, eMultimedia}) {
		winrt::com_ptr<IMMDevice> fallback;
		if (SUCCEEDED(enumerator->GetDefaultAudioEndpoint(eCapture, role, fallback.put())) && fallback) {
			selectedNameOut = GetEndpointProperty(fallback.get(), PKEY_Device_FriendlyName);
			if (selectedIdOut != nullptr) {
				*selectedIdOut = GetEndpointId(fallback.get());
			}
			if (selectedDescriptionOut != nullptr) {
				*selectedDescriptionOut = GetEndpointProperty(fallback.get(), PKEY_Device_DeviceDesc);
			}
			if (selectionModeOut != nullptr) {
				*selectionModeOut = L"default_capture_" + RoleToString(role);
			}
			return fallback;
		}
	}
	ThrowWithMessage(L"No default capture endpoint is available");
}

winrt::com_ptr<IMMDevice> ResolveLoopbackEndpoint(
	IMMDeviceEnumerator* enumerator,
	std::wstring& selectedNameOut,
	std::wstring* selectedIdOut = nullptr,
	std::wstring* selectedDescriptionOut = nullptr,
	std::wstring* selectionModeOut = nullptr
) {
	for (ERole role : {eConsole, eMultimedia, eCommunications}) {
		winrt::com_ptr<IMMDevice> device;
		if (SUCCEEDED(enumerator->GetDefaultAudioEndpoint(eRender, role, device.put())) && device) {
			selectedNameOut = GetEndpointProperty(device.get(), PKEY_Device_FriendlyName);
			if (selectedIdOut != nullptr) {
				*selectedIdOut = GetEndpointId(device.get());
			}
			if (selectedDescriptionOut != nullptr) {
				*selectedDescriptionOut = GetEndpointProperty(device.get(), PKEY_Device_DeviceDesc);
			}
			if (selectionModeOut != nullptr) {
				*selectionModeOut = L"default_render_" + RoleToString(role);
			}
			return device;
		}
	}
	ThrowWithMessage(L"No default render endpoint is available");
}

AudioClientFormat DescribeAudioFormat(const WAVEFORMATEX* format) {
	AudioClientFormat result{};
	result.sampleRate = format->nSamplesPerSec;
	result.channelCount = format->nChannels;
	result.bitsPerSample = format->wBitsPerSample;
	result.isFloat = false;

	if (format->wFormatTag == WAVE_FORMAT_IEEE_FLOAT) {
		result.isFloat = true;
	} else if (format->wFormatTag == WAVE_FORMAT_EXTENSIBLE) {
		const auto* extensible = reinterpret_cast<const WAVEFORMATEXTENSIBLE*>(format);
		if (extensible->SubFormat == KSDATAFORMAT_SUBTYPE_IEEE_FLOAT) {
			result.isFloat = true;
		}
		if (extensible->Samples.wValidBitsPerSample != 0) {
			result.bitsPerSample = extensible->Samples.wValidBitsPerSample;
		}
	}

	return result;
}

bool IsTargetAudioFormat(const AudioClientFormat& format) {
	return format.sampleRate == kAudioSampleRate &&
		format.channelCount == kAudioChannels &&
		format.isFloat &&
		format.bitsPerSample == 32;
}

void BuildDesiredCaptureFormat(WAVEFORMATEXTENSIBLE& format) {
	ZeroMemory(&format, sizeof(format));
	format.Format.wFormatTag = WAVE_FORMAT_EXTENSIBLE;
	format.Format.nChannels = kAudioChannels;
	format.Format.nSamplesPerSec = kAudioSampleRate;
	format.Format.wBitsPerSample = 32;
	format.Format.nBlockAlign = static_cast<WORD>(format.Format.nChannels * format.Format.wBitsPerSample / 8);
	format.Format.nAvgBytesPerSec = format.Format.nSamplesPerSec * format.Format.nBlockAlign;
	format.Format.cbSize = sizeof(WAVEFORMATEXTENSIBLE) - sizeof(WAVEFORMATEX);
	format.Samples.wValidBitsPerSample = format.Format.wBitsPerSample;
	format.dwChannelMask = SPEAKER_FRONT_LEFT | SPEAKER_FRONT_RIGHT;
	format.SubFormat = KSDATAFORMAT_SUBTYPE_IEEE_FLOAT;
}

float ReadIntegerSample(const BYTE* sample, UINT16 bitsPerSample) {
	switch (bitsPerSample) {
		case 16: {
			const auto value = *reinterpret_cast<const int16_t*>(sample);
			return static_cast<float>(value) / 32768.0F;
		}
		case 24: {
			const int32_t value =
				(static_cast<int32_t>(sample[0])) |
				(static_cast<int32_t>(sample[1]) << 8) |
				(static_cast<int32_t>(sample[2]) << 16) |
				((sample[2] & 0x80) != 0 ? 0xFF000000 : 0);
			return static_cast<float>(value) / 8388608.0F;
		}
		case 32: {
			const auto value = *reinterpret_cast<const int32_t*>(sample);
			return static_cast<float>(value) / 2147483648.0F;
		}
		default:
			return 0.0F;
	}
}

void ConvertPacketToTargetStereo(
	const BYTE* data,
	UINT32 sourceFrames,
	const AudioClientFormat& sourceFormat,
	UINT32 targetFrames,
	std::vector<float>& output
) {
	output.assign(static_cast<size_t>(targetFrames) * kAudioChannels, 0.0F);
	if (sourceFrames == 0 || targetFrames == 0) {
		return;
	}

	const UINT32 bytesPerSample = std::max<UINT32>(1, sourceFormat.bitsPerSample / 8);
	const UINT32 bytesPerFrame = std::max<UINT32>(bytesPerSample, bytesPerSample * sourceFormat.channelCount);

	auto readStereoFrame = [&](UINT32 frameIndex, float& left, float& right) {
		const BYTE* frameBase = data + static_cast<size_t>(frameIndex) * bytesPerFrame;
		if (sourceFormat.channelCount == 0) {
			left = 0.0F;
			right = 0.0F;
			return;
		}

		auto readSample = [&](UINT32 channelIndex) -> float {
			const BYTE* sampleBase = frameBase + static_cast<size_t>(channelIndex) * bytesPerSample;
			if (sourceFormat.isFloat && sourceFormat.bitsPerSample == 32) {
				return *reinterpret_cast<const float*>(sampleBase);
			}
			return ReadIntegerSample(sampleBase, sourceFormat.bitsPerSample);
		};

		left = readSample(0);
		if (sourceFormat.channelCount == 1) {
			right = left;
		} else {
			right = readSample(1);
		}
	};

	if (sourceFormat.sampleRate == kAudioSampleRate) {
		for (UINT32 frame = 0; frame < targetFrames && frame < sourceFrames; ++frame) {
			float left = 0.0F;
			float right = 0.0F;
			readStereoFrame(frame, left, right);
			output[static_cast<size_t>(frame) * 2] = left;
			output[static_cast<size_t>(frame) * 2 + 1] = right;
		}
		return;
	}

	const double ratio = static_cast<double>(sourceFrames) / static_cast<double>(targetFrames);
	for (UINT32 frame = 0; frame < targetFrames; ++frame) {
		double position = static_cast<double>(frame) * ratio;
		UINT32 leftIndex = static_cast<UINT32>(std::floor(position));
		UINT32 rightIndex = std::min<UINT32>(sourceFrames - 1, leftIndex + 1);
		double fraction = position - static_cast<double>(leftIndex);

		float leftA = 0.0F;
		float rightA = 0.0F;
		float leftB = 0.0F;
		float rightB = 0.0F;
		readStereoFrame(leftIndex, leftA, rightA);
		readStereoFrame(rightIndex, leftB, rightB);

		output[static_cast<size_t>(frame) * 2] =
			static_cast<float>(leftA + (leftB - leftA) * fraction);
		output[static_cast<size_t>(frame) * 2 + 1] =
			static_cast<float>(rightA + (rightB - rightA) * fraction);
	}
}

class MixedAudioTimelineBuffer {
public:
	void AddFrames(INT64 startFrame, const std::vector<float>& frames) {
		if (frames.empty()) {
			return;
		}
		const INT64 frameCount = static_cast<INT64>(frames.size() / kAudioChannels);
		if (frameCount <= 0) {
			return;
		}

		std::lock_guard lock(m_mutex);
		const INT64 endFrame = startFrame + frameCount;
		if (endFrame > m_maxFrame) {
			m_samples.resize(static_cast<size_t>(endFrame) * kAudioChannels, 0.0F);
			m_maxFrame = endFrame;
		}

		size_t destinationOffset = static_cast<size_t>(startFrame) * kAudioChannels;
		for (size_t index = 0; index < frames.size(); ++index) {
			m_samples[destinationOffset + index] += frames[index];
		}
	}

	INT64 MaxFrame() const {
		std::lock_guard lock(m_mutex);
		return m_maxFrame;
	}

	void ReadFrames(INT64 startFrame, UINT32 frameCount, std::vector<float>& output) const {
		output.assign(static_cast<size_t>(frameCount) * kAudioChannels, 0.0F);
		if (frameCount == 0) {
			return;
		}

		std::lock_guard lock(m_mutex);
		if (startFrame >= m_maxFrame) {
			return;
		}

		INT64 endFrame = std::min<INT64>(m_maxFrame, startFrame + static_cast<INT64>(frameCount));
		if (endFrame <= startFrame) {
			return;
		}

		size_t sourceOffset = static_cast<size_t>(startFrame) * kAudioChannels;
		size_t sampleCount = static_cast<size_t>(endFrame - startFrame) * kAudioChannels;
		std::copy_n(m_samples.begin() + static_cast<std::ptrdiff_t>(sourceOffset), sampleCount, output.begin());
	}

private:
	mutable std::mutex m_mutex;
	std::vector<float> m_samples;
	INT64 m_maxFrame = 0;
};

AudioBufferAnalysis AnalyzeAudioBuffer(
	const MixedAudioTimelineBuffer& buffer,
	INT64 totalFrames,
	float gain = 1.0F
) {
	AudioBufferAnalysis result{};
	result.totalFrames = std::max<INT64>(0, totalFrames);
	if (result.totalFrames <= 0) {
		return result;
	}

	double sumSquares = 0.0;
	double sumAbs = 0.0;
	UINT64 sampleCount = 0;
	for (INT64 frameIndex = 0; frameIndex < result.totalFrames; frameIndex += kAudioChunkFrames) {
		UINT32 chunkFrames = static_cast<UINT32>(std::min<INT64>(kAudioChunkFrames, result.totalFrames - frameIndex));
		std::vector<float> frames;
		buffer.ReadFrames(frameIndex, chunkFrames, frames);
		for (size_t sampleIndex = 0; sampleIndex < frames.size(); ++sampleIndex) {
			float sample = std::clamp(frames[sampleIndex] * gain, -1.0F, 1.0F);
			float absSample = std::fabs(sample);
			result.peak = std::max(result.peak, absSample);
			sumSquares += static_cast<double>(sample) * static_cast<double>(sample);
			sumAbs += absSample;
			++sampleCount;
			if (absSample > kAudioSilenceFloor) {
				++result.nonZeroSamples;
				if (result.firstNonZeroFrame < 0) {
					result.firstNonZeroFrame =
						frameIndex + static_cast<INT64>(sampleIndex / static_cast<size_t>(kAudioChannels));
				}
			}
		}
	}

	if (sampleCount > 0) {
		result.rms = std::sqrt(sumSquares / static_cast<double>(sampleCount));
		result.averageAbs = sumAbs / static_cast<double>(sampleCount);
	}
	return result;
}

std::vector<int16_t> ConvertFramesToPcm16(const std::vector<float>& frames, float gain = 1.0F) {
	std::vector<int16_t> pcm(frames.size(), 0);
	for (size_t index = 0; index < pcm.size(); ++index) {
		float clamped = std::clamp(frames[index] * gain, -1.0F, 1.0F);
		pcm[index] = static_cast<int16_t>(std::lrintf(clamped * 32767.0F));
	}
	return pcm;
}

void WriteWaveFileFromBuffer(
	const std::filesystem::path& outputPath,
	const MixedAudioTimelineBuffer& buffer,
	INT64 totalFrames,
	float gain = 1.0F
) {
	if (outputPath.empty()) {
		return;
	}
	std::filesystem::create_directories(outputPath.parent_path());

	std::ofstream output(outputPath, std::ios::binary | std::ios::trunc);
	if (!output) {
		ThrowWithMessage(L"Failed to create debug WAV at " + outputPath.wstring());
	}

	const uint16_t channels = static_cast<uint16_t>(kAudioChannels);
	const uint32_t sampleRate = kAudioSampleRate;
	const uint16_t bitsPerSample = static_cast<uint16_t>(kAudioBitsPerSample);
	const uint16_t blockAlign = static_cast<uint16_t>(channels * (bitsPerSample / 8U));
	const uint32_t bytesPerSecond = sampleRate * blockAlign;
	const uint32_t dataSize = static_cast<uint32_t>(
		std::max<INT64>(0, totalFrames) * static_cast<INT64>(blockAlign)
	);
	const uint32_t riffSize = 36U + dataSize;

	output.write("RIFF", 4);
	output.write(reinterpret_cast<const char*>(&riffSize), sizeof(riffSize));
	output.write("WAVE", 4);
	output.write("fmt ", 4);
	const uint32_t fmtChunkSize = 16U;
	const uint16_t audioFormat = 1U;
	output.write(reinterpret_cast<const char*>(&fmtChunkSize), sizeof(fmtChunkSize));
	output.write(reinterpret_cast<const char*>(&audioFormat), sizeof(audioFormat));
	output.write(reinterpret_cast<const char*>(&channels), sizeof(channels));
	output.write(reinterpret_cast<const char*>(&sampleRate), sizeof(sampleRate));
	output.write(reinterpret_cast<const char*>(&bytesPerSecond), sizeof(bytesPerSecond));
	output.write(reinterpret_cast<const char*>(&blockAlign), sizeof(blockAlign));
	output.write(reinterpret_cast<const char*>(&bitsPerSample), sizeof(bitsPerSample));
	output.write("data", 4);
	output.write(reinterpret_cast<const char*>(&dataSize), sizeof(dataSize));

	for (INT64 frameIndex = 0; frameIndex < totalFrames; frameIndex += kAudioChunkFrames) {
		UINT32 chunkFrames = static_cast<UINT32>(std::min<INT64>(kAudioChunkFrames, totalFrames - frameIndex));
		std::vector<float> frames;
		buffer.ReadFrames(frameIndex, chunkFrames, frames);
		std::vector<int16_t> pcm = ConvertFramesToPcm16(frames, gain);
		output.write(reinterpret_cast<const char*>(pcm.data()), static_cast<std::streamsize>(pcm.size() * sizeof(int16_t)));
	}

	output.flush();
	if (!output) {
		ThrowWithMessage(L"Failed to flush debug WAV at " + outputPath.wstring());
	}
}

class AudioCaptureSource {
public:
	enum class Kind {
		SystemLoopback,
		Microphone
	};

	AudioCaptureSource(
		Kind kind,
		std::wstring selector,
		std::wstring preferredName,
		MixedAudioTimelineBuffer& mixedBuffer,
		INT64 startQpc100ns,
		bool debugEnabled
	)
		: m_kind(kind)
		, m_selector(std::move(selector))
		, m_preferredName(std::move(preferredName))
		, m_mixedBuffer(mixedBuffer)
		, m_startQpc100ns(startQpc100ns)
		, m_debugEnabled(debugEnabled)
		, m_stopEvent(CreateEventW(nullptr, TRUE, FALSE, nullptr))
		, m_sampleEvent(CreateEventW(nullptr, FALSE, FALSE, nullptr)) {
		m_debugSummary.requestedSelector = m_selector;
		m_debugSummary.requestedPreferredName = m_preferredName;
		if (!m_stopEvent || !m_sampleEvent) {
			ThrowLastError(L"Failed to allocate Windows audio capture events");
		}
	}

	void Start() {
		m_thread = std::thread([this] {
			ThreadMain();
		});

		std::unique_lock lock(m_stateMutex);
		if (!m_stateCv.wait_for(lock, std::chrono::seconds(5), [&] {
			return m_initializationFinished;
		})) {
			m_error = L"Timed out initializing audio endpoint";
			m_debugSummary.error = m_error;
			m_initializationFinished = true;
		}
	}

	void SignalStop() {
		if (m_stopEvent) {
			SetEvent(m_stopEvent.get());
		}
	}

	void Stop() {
		SignalStop();
		if (m_thread.joinable()) {
			m_thread.join();
		}
	}

	std::wstring Error() const {
		std::lock_guard lock(m_stateMutex);
		return m_error;
	}

	std::wstring SelectedDeviceName() const {
		std::lock_guard lock(m_stateMutex);
		return m_selectedDeviceName;
	}

	INT64 MaxCommittedFrame() const {
		return m_maxCommittedFrame.load();
	}

	bool GatesMixerTimeline() const {
		return m_initializationSucceeded.load() && !m_finished.load();
	}

	AudioDebugSummary DebugSummary() const {
		std::lock_guard lock(m_stateMutex);
		return m_debugSummary;
	}

	const MixedAudioTimelineBuffer& DebugBuffer() const {
		return m_debugBuffer;
	}

private:
	void ThreadMain();
	void CaptureLoop();
	void RecordError(const std::wstring& message);

	Kind m_kind;
	std::wstring m_selector;
	std::wstring m_preferredName;
	MixedAudioTimelineBuffer& m_mixedBuffer;
	INT64 m_startQpc100ns = 0;
	bool m_debugEnabled = false;
	winrt::handle m_stopEvent;
	winrt::handle m_sampleEvent;
	std::thread m_thread;
	std::atomic<INT64> m_maxCommittedFrame{0};
	std::atomic<bool> m_initializationSucceeded{false};
	std::atomic<bool> m_finished{false};
	MixedAudioTimelineBuffer m_debugBuffer;

	mutable std::mutex m_stateMutex;
	std::condition_variable m_stateCv;
	bool m_initializationFinished = false;
	std::wstring m_selectedDeviceName;
	std::wstring m_error;
	AudioClientFormat m_audioFormat{};
	AudioDebugSummary m_debugSummary{};
};

void AudioCaptureSource::ThreadMain() {
	try {
		winrt::init_apartment(apartment_type::multi_threaded);
		CaptureLoop();
	} catch (const winrt::hresult_error& error) {
		RecordError(error.message().c_str());
	} catch (const std::exception& error) {
		RecordError(Utf8Literal(error.what()));
	}
	m_finished.store(true);
}

void AudioCaptureSource::CaptureLoop() {
	winrt::com_ptr<IMMDeviceEnumerator> enumerator;
	check_hresult(CoCreateInstance(
		__uuidof(MMDeviceEnumerator),
		nullptr,
		CLSCTX_ALL,
		IID_PPV_ARGS(enumerator.put())
	));

	std::wstring selectedDeviceName;
	std::wstring selectedDeviceId;
	std::wstring selectedDeviceDescription;
	std::wstring selectionMode;
	winrt::com_ptr<IMMDevice> device =
		m_kind == Kind::SystemLoopback
			? ResolveLoopbackEndpoint(
				enumerator.get(),
				selectedDeviceName,
				&selectedDeviceId,
				&selectedDeviceDescription,
				&selectionMode
			)
			: ResolveCaptureEndpoint(
				enumerator.get(),
				m_selector,
				m_preferredName,
				selectedDeviceName,
				&selectedDeviceId,
				&selectedDeviceDescription,
				&selectionMode
			);

	winrt::com_ptr<IAudioClient> audioClient;
	check_hresult(device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, audioClient.put_void()));

	WAVEFORMATEXTENSIBLE desiredFormat{};
	BuildDesiredCaptureFormat(desiredFormat);
	DWORD streamFlags = AUDCLNT_STREAMFLAGS_EVENTCALLBACK |
		AUDCLNT_STREAMFLAGS_NOPERSIST;
	if (m_kind == Kind::SystemLoopback) {
		streamFlags |= AUDCLNT_STREAMFLAGS_LOOPBACK |
			AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM |
			AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY;
	}

	WAVEFORMATEX* captureFormatRaw = nullptr;
	auto loadMixFormat = [&] {
		if (captureFormatRaw == nullptr) {
			check_hresult(audioClient->GetMixFormat(&captureFormatRaw));
		}
	};

	HRESULT initializeResult = E_FAIL;
	std::wstring initializeMode = L"";
	if (m_kind == Kind::Microphone) {
		// Microphones are more reliable when opened in their native shared-mode mix format.
		loadMixFormat();
		initializeResult = audioClient->Initialize(
			AUDCLNT_SHAREMODE_SHARED,
			streamFlags,
			kDefaultAudioBuffer100ns,
			0,
			captureFormatRaw,
			nullptr
		);
		if (SUCCEEDED(initializeResult)) {
			initializeMode = L"shared_mix_format_first";
		}
		if (FAILED(initializeResult)) {
			CoTaskMemFree(captureFormatRaw);
			captureFormatRaw = nullptr;
		}
	}
	if (FAILED(initializeResult)) {
		initializeResult = audioClient->Initialize(
			AUDCLNT_SHAREMODE_SHARED,
			streamFlags,
			kDefaultAudioBuffer100ns,
			0,
			reinterpret_cast<WAVEFORMATEX*>(&desiredFormat),
			nullptr
		);
		if (SUCCEEDED(initializeResult)) {
			initializeMode = m_kind == Kind::SystemLoopback
				? L"forced_target_with_autoconvert"
				: L"forced_target_after_mix_attempt";
		}
	}
	if (FAILED(initializeResult)) {
		loadMixFormat();
		initializeResult = audioClient->Initialize(
			AUDCLNT_SHAREMODE_SHARED,
			streamFlags,
			kDefaultAudioBuffer100ns,
			0,
			captureFormatRaw,
			nullptr
		);
		if (SUCCEEDED(initializeResult)) {
			initializeMode = L"shared_mix_format_fallback";
		}
	}
	check_hresult(initializeResult);

	std::vector<BYTE> captureFormatStorage;
	if (captureFormatRaw != nullptr) {
		const size_t formatSize = sizeof(WAVEFORMATEX) + captureFormatRaw->cbSize;
		captureFormatStorage.assign(
			reinterpret_cast<const BYTE*>(captureFormatRaw),
			reinterpret_cast<const BYTE*>(captureFormatRaw) + static_cast<std::ptrdiff_t>(formatSize)
		);
		CoTaskMemFree(captureFormatRaw);
		captureFormatRaw = nullptr;
	} else {
		captureFormatStorage.resize(sizeof(WAVEFORMATEXTENSIBLE));
		std::memcpy(captureFormatStorage.data(), &desiredFormat, sizeof(WAVEFORMATEXTENSIBLE));
	}

	const auto* captureFormat = reinterpret_cast<const WAVEFORMATEX*>(captureFormatStorage.data());
	m_audioFormat = DescribeAudioFormat(captureFormat);

	check_hresult(audioClient->SetEventHandle(m_sampleEvent.get()));
	winrt::com_ptr<IAudioCaptureClient> captureClient;
	check_hresult(audioClient->GetService(IID_PPV_ARGS(captureClient.put())));

	{
		std::lock_guard lock(m_stateMutex);
		m_selectedDeviceName = selectedDeviceName;
		m_debugSummary.selectedDeviceId = selectedDeviceId;
		m_debugSummary.selectedDeviceName = selectedDeviceName;
		m_debugSummary.selectedDeviceDescription = selectedDeviceDescription;
		m_debugSummary.selectionMode = selectionMode;
		m_debugSummary.initializeMode = initializeMode;
		m_debugSummary.sourceFormat = m_audioFormat;
		m_initializationFinished = true;
	}
	m_initializationSucceeded.store(true);
	m_stateCv.notify_all();

	std::wstringstream startedMessage;
	startedMessage << (m_kind == Kind::SystemLoopback ? L"System loopback" : L"Microphone")
		<< L" started: "
		<< (selectedDeviceName.empty() ? L"<unnamed endpoint>" : selectedDeviceName)
		<< L" selection=" << selectionMode
		<< L" init=" << initializeMode
		<< L" format=" << AudioFormatToString(m_audioFormat);
	LogLine(startedMessage.str());

	check_hresult(audioClient->Start());
	UINT64 packetCount = 0;
	UINT64 silentPacketCount = 0;
	UINT64 fallbackTimestampPacketCount = 0;
	INT64 totalCommittedFrames = 0;
	float peakLevel = 0.0F;
	UINT32 packetDebugLogged = 0;
	UINT32 timestampFallbackLogCount = 0;
	UINT64 lastResolvedQpcPosition = 0;
	INT64 nextTimelineFrame = -1;
	bool usingFallbackTimeline = false;
	HANDLE waitHandles[] = {m_stopEvent.get(), m_sampleEvent.get()};
	while (true) {
		DWORD waitResult = WaitForMultipleObjects(2, waitHandles, FALSE, INFINITE);
		if (waitResult == WAIT_OBJECT_0) {
			break;
		}
		if (waitResult != WAIT_OBJECT_0 + 1) {
			continue;
		}

		for (;;) {
			UINT32 nextPacketFrames = 0;
			check_hresult(captureClient->GetNextPacketSize(&nextPacketFrames));
			if (nextPacketFrames == 0) {
				break;
			}

			BYTE* packetData = nullptr;
			UINT32 frameCount = 0;
			DWORD packetFlags = 0;
			UINT64 devicePosition = 0;
			UINT64 qpcPosition = 0;
			check_hresult(captureClient->GetBuffer(
				&packetData,
				&frameCount,
				&packetFlags,
				&devicePosition,
				&qpcPosition
			));

			const UINT32 targetFrames = m_audioFormat.sampleRate == 0
				? frameCount
				: std::max<UINT32>(
					1,
					static_cast<UINT32>(std::llround(
						static_cast<double>(frameCount) * static_cast<double>(kAudioSampleRate) /
						static_cast<double>(m_audioFormat.sampleRate)
					))
				);

			auto resolveFallbackStartFrame = [&](const wchar_t* reason) -> INT64 {
				const INT64 nowFrame = AudioFramesFromTime100ns(std::max<INT64>(0, CurrentQpc100ns() - m_startQpc100ns));
				INT64 candidate = nowFrame >= static_cast<INT64>(targetFrames)
					? nowFrame - static_cast<INT64>(targetFrames)
					: 0;
				if (nextTimelineFrame >= 0) {
					candidate = std::max(candidate, nextTimelineFrame);
				}
				nextTimelineFrame = candidate + static_cast<INT64>(targetFrames);
				usingFallbackTimeline = true;
				++fallbackTimestampPacketCount;
				if (m_debugEnabled && timestampFallbackLogCount < kDebugPacketLogLimit) {
					std::wstringstream fallbackMessage;
					fallbackMessage << (m_kind == Kind::SystemLoopback ? L"System loopback" : L"Microphone")
						<< L" timestamp fallback[" << timestampFallbackLogCount << L"] reason=" << reason
						<< L" raw_qpc=" << qpcPosition
						<< L" device_pos=" << devicePosition
						<< L" now_frame=" << nowFrame
						<< L" start_frame=" << candidate;
					LogLine(fallbackMessage.str());
					++timestampFallbackLogCount;
				}
				return candidate;
			};

			INT64 startFrame = 0;
			bool validQpcPosition = qpcPosition > 0 && qpcPosition < (1ULL << 63);
			if (validQpcPosition) {
				const INT64 packetQpc100ns = static_cast<INT64>(qpcPosition);
				if (packetQpc100ns <= m_startQpc100ns) {
					startFrame = resolveFallbackStartFrame(L"prestart_qpc");
				} else {
					const INT64 relativeQpc100ns = packetQpc100ns - m_startQpc100ns;
					startFrame = std::max<INT64>(0, AudioFramesFromTime100ns(relativeQpc100ns));
					if (lastResolvedQpcPosition != 0 && qpcPosition <= lastResolvedQpcPosition) {
						startFrame = resolveFallbackStartFrame(L"non_monotonic_qpc");
					} else if (nextTimelineFrame >= 0
						&& startFrame + static_cast<INT64>(targetFrames) <= nextTimelineFrame) {
						startFrame = resolveFallbackStartFrame(L"regressed_frame");
					} else {
						lastResolvedQpcPosition = qpcPosition;
						nextTimelineFrame = startFrame + static_cast<INT64>(targetFrames);
					}
				}
			} else {
				startFrame = resolveFallbackStartFrame(L"invalid_qpc");
			}

			std::vector<float> mixedFrames;
			++packetCount;
			if ((packetFlags & AUDCLNT_BUFFERFLAGS_SILENT) != 0 || packetData == nullptr) {
				++silentPacketCount;
				mixedFrames.assign(static_cast<size_t>(targetFrames) * kAudioChannels, 0.0F);
			} else if (IsTargetAudioFormat(m_audioFormat)) {
				const auto* samples = reinterpret_cast<const float*>(packetData);
				mixedFrames.assign(
					samples,
					samples + static_cast<std::ptrdiff_t>(frameCount) * static_cast<std::ptrdiff_t>(kAudioChannels)
				);
			} else {
				ConvertPacketToTargetStereo(packetData, frameCount, m_audioFormat, targetFrames, mixedFrames);
			}
			for (float sample : mixedFrames) {
				peakLevel = std::max(peakLevel, std::fabs(sample));
			}

			m_mixedBuffer.AddFrames(startFrame, mixedFrames);
			if (m_debugEnabled) {
				m_debugBuffer.AddFrames(startFrame, mixedFrames);
			}
			totalCommittedFrames += static_cast<INT64>(targetFrames);
			m_maxCommittedFrame.store(std::max<INT64>(
				m_maxCommittedFrame.load(),
				startFrame + static_cast<INT64>(targetFrames)
			));
			if (m_debugEnabled && packetDebugLogged < kDebugPacketLogLimit) {
				float packetPeak = 0.0F;
				for (float sample : mixedFrames) {
					packetPeak = std::max(packetPeak, std::fabs(sample));
				}
				std::wstringstream packetMessage;
				packetMessage << (m_kind == Kind::SystemLoopback ? L"System loopback" : L"Microphone")
					<< L" packet[" << packetDebugLogged << L"] start_frame=" << startFrame
					<< L" source_frames=" << frameCount
					<< L" target_frames=" << targetFrames
					<< L" flags=0x" << std::hex << packetFlags << std::dec
					<< L" device_pos=" << devicePosition
					<< L" qpc_100ns=" << qpcPosition
					<< L" peak=" << std::fixed << std::setprecision(4) << packetPeak;
				LogLine(packetMessage.str());
				++packetDebugLogged;
			}
			check_hresult(captureClient->ReleaseBuffer(frameCount));
		}
	}

	audioClient->Stop();
	{
		std::lock_guard lock(m_stateMutex);
		m_debugSummary.packetCount = packetCount;
		m_debugSummary.silentPacketCount = silentPacketCount;
		m_debugSummary.fallbackTimestampPacketCount = fallbackTimestampPacketCount;
		m_debugSummary.totalCommittedFrames = totalCommittedFrames;
		m_debugSummary.maxCommittedFrame = m_maxCommittedFrame.load();
		m_debugSummary.capturePeak = peakLevel;
		m_debugSummary.timelineMode = usingFallbackTimeline ? L"fallback_sequential" : L"qpc_timestamps";
	}
	std::wstringstream finishedMessage;
	finishedMessage << (m_kind == Kind::SystemLoopback ? L"System loopback" : L"Microphone")
		<< L" stopped: "
		<< (selectedDeviceName.empty() ? L"<unnamed endpoint>" : selectedDeviceName)
		<< L" packets=" << packetCount
		<< L" silent_packets=" << silentPacketCount
		<< L" fallback_timestamp_packets=" << fallbackTimestampPacketCount
		<< L" frames=" << totalCommittedFrames
		<< L" peak=" << std::fixed << std::setprecision(4) << peakLevel;
	LogLine(finishedMessage.str());
}

void AudioCaptureSource::RecordError(const std::wstring& message) {
	{
		std::lock_guard lock(m_stateMutex);
		if (m_error.empty()) {
			m_error = message;
		}
		if (m_debugSummary.error.empty()) {
			m_debugSummary.error = message;
		}
		m_initializationFinished = true;
	}
	m_stateCv.notify_all();

	std::wstringstream stream;
	stream << (m_kind == Kind::SystemLoopback ? L"System loopback" : L"Microphone")
		<< L" capture error: "
		<< message;
	LogLine(stream.str());
}

class CaptureController {
public:
	explicit CaptureController(Arguments args)
		: m_args(std::move(args))
		, m_frameInterval100ns(kHundredNanosecondsPerSecond / std::max(1, m_args.targetFps)) {
	}

	~CaptureController() {
		try {
			if (m_framePool) {
				m_framePool.FrameArrived(m_frameArrivedToken);
			}
		} catch (...) {
		}
		m_captureSession = nullptr;
		m_framePool = nullptr;
		if (m_systemAudio) {
			m_systemAudio->Stop();
		}
		if (m_microphoneAudio) {
			m_microphoneAudio->Stop();
		}
		if (m_stopWatcherThread.joinable()) {
			m_stopWatcherThread.join();
		}
		if (m_audioMixerThread.joinable()) {
			m_audioMixerThread.join();
		}
		if (m_mediaFoundationStarted) {
			MFShutdown();
		}
	}

	void Run();

private:
	void InitializeOutputPath();
	void ResolveTargetWindow();
	void InitializeMediaFoundation();
	void InitializeCaptureDevices();
	void StartAudioCapture();
	void StartStopWatcher();
	void StartCaptureSession();
	void WaitForReady();
	void WriteReadyFile();
	void WaitForStop();
	void RequestStop();
	void ShutdownCaptureSession();
	void StopAudioCapture();
	void StopMixerThread();
	void WriteDebugArtifacts();
	void FinalizeWriter();
	void FailCapture(const std::wstring& message);
	void EnsureStagingTexture(UINT32 width, UINT32 height);
	void CopyFrameToScratch(
		const Direct3D11CaptureFrame& frame,
		UINT32 sourceWidth,
		UINT32 sourceHeight,
		const CaptureRegion& sourceRegion
	);
	void WriteVideoSample(const std::vector<uint8_t>& pixels, INT64 sampleTime100ns, INT64 sampleDuration100ns);
	void FlushPendingVideoFrame();
	void WriteAudioSample(INT64 startFrame, UINT32 frameCount, const std::vector<float>& frames);
	void AudioMixerLoop();
	void OnFrameArrived(
		const Direct3D11CaptureFramePool& sender,
		const winrt::Windows::Foundation::IInspectable&
	);

	Arguments m_args;
	INT64 m_frameInterval100ns = 0;

	HWND m_targetHwnd = nullptr;
	std::wstring m_targetWindowDescription;

	bool m_mediaFoundationStarted = false;
	bool m_writerStarted = false;
	INT64 m_startQpc100ns = 0;
	INT64 m_stopTime100ns = 0;

	Dimensions m_outputSize{};
	CaptureRegion m_sourceRegion{};
	winrt::Windows::Graphics::SizeInt32 m_currentContentSize{};

	winrt::com_ptr<IMFSinkWriter> m_sinkWriter;
	DWORD m_videoStreamIndex = 0;
	DWORD m_audioStreamIndex = 0;
	std::mutex m_sinkMutex;

	winrt::com_ptr<ID3D11Device> m_d3dDevice;
	winrt::com_ptr<ID3D11DeviceContext> m_d3dContext;
	winrt::com_ptr<ID3D11Texture2D> m_stagingTexture;
	IDirect3DDevice m_captureDevice{nullptr};
	GraphicsCaptureItem m_captureItem{nullptr};
	Direct3D11CaptureFramePool m_framePool{nullptr};
	GraphicsCaptureSession m_captureSession{nullptr};
	winrt::event_token m_frameArrivedToken{};

	std::mutex m_stateMutex;
	std::condition_variable m_stateCv;
	bool m_ready = false;
	std::wstring m_fatalError;
	std::atomic<bool> m_stopRequested = false;

	std::mutex m_videoMutex;
	std::vector<uint8_t> m_pendingVideoBytes;
	std::vector<uint8_t> m_scratchVideoBytes;
	INT64 m_pendingVideoTimestamp100ns = -1;
	INT64 m_lastVideoDuration100ns = 0;
	INT64 m_nextAllowedFrameTimestamp100ns = 0;
	INT64 m_nextVideoSampleTime100ns = -1;

	MixedAudioTimelineBuffer m_mixedAudioBuffer;
	std::unique_ptr<AudioCaptureSource> m_systemAudio;
	std::unique_ptr<AudioCaptureSource> m_microphoneAudio;
	std::thread m_audioMixerThread;
	bool m_audioSourcesStopped = false;
	INT64 m_debugMixedTotalFrames = 0;
	float m_debugMixedSourcePeak = 0.0F;
	float m_debugMixedGain = 1.0F;
	float m_debugMixedWrittenPeak = 0.0F;

	winrt::handle m_captureProcessHandle;
	std::thread m_stopWatcherThread;
};

void CaptureController::Run() {
	InitializeOutputPath();
	ResolveTargetWindow();
	InitializeMediaFoundation();
	InitializeCaptureDevices();
	StartAudioCapture();
	StartStopWatcher();
	StartCaptureSession();
	WaitForReady();
	WriteReadyFile();
	WaitForStop();
	ShutdownCaptureSession();
	StopAudioCapture();
	FlushPendingVideoFrame();
	StopMixerThread();
	WriteDebugArtifacts();
	FinalizeWriter();
	LogLine(L"BlockChat Windows helper finished cleanly");
}

void CaptureController::InitializeOutputPath() {
	std::filesystem::create_directories(m_args.videoOut.parent_path());
	std::filesystem::create_directories(m_args.readyFile.parent_path());
	std::filesystem::create_directories(m_args.stopFile.parent_path());
	if (!m_args.debugAudioDir.empty()) {
		std::filesystem::create_directories(m_args.debugAudioDir);
		std::filesystem::remove(m_args.debugAudioDir / "windows-audio-debug.json");
		std::filesystem::remove(m_args.debugAudioDir / "windows-system-loopback-debug.wav");
		std::filesystem::remove(m_args.debugAudioDir / "windows-microphone-debug.wav");
		std::filesystem::remove(m_args.debugAudioDir / "windows-mixed-output-debug.wav");
	}
	std::filesystem::remove(m_args.videoOut);
	std::filesystem::remove(m_args.readyFile);
	std::filesystem::remove(m_args.stopFile);
}

void CaptureController::ResolveTargetWindow() {
	if (!GraphicsCaptureSession::IsSupported()) {
		ThrowWithMessage(L"Windows.Graphics.Capture is not supported on this system");
	}

	m_targetHwnd = FindBestWindowForProcess(m_args.capturePid, m_targetWindowDescription);
	m_captureItem = CreateCaptureItemForWindow(m_targetHwnd);
	auto size = m_captureItem.Size();
	if (size.Width <= 0 || size.Height <= 0) {
		ThrowWithMessage(L"Target Minecraft window reported an invalid capture size");
	}

	m_currentContentSize = size;
	m_sourceRegion = ComputeClientCaptureRegion(
		m_targetHwnd,
		static_cast<UINT32>(size.Width),
		static_cast<UINT32>(size.Height)
	);
	m_outputSize = DownscaleToMaxHeight(
		m_sourceRegion.width,
		m_sourceRegion.height,
		m_args.maxVideoHeight
	);

	std::wstringstream stream;
	stream << L"Selected capture target " << m_targetWindowDescription
		<< L" source=" << size.Width << L"x" << size.Height
		<< L" client=" << m_sourceRegion.width << L"x" << m_sourceRegion.height
		<< L" output=" << m_outputSize.width << L"x" << m_outputSize.height;
	if (!IsFullCaptureRegion(
		m_sourceRegion,
		static_cast<UINT32>(size.Width),
		static_cast<UINT32>(size.Height)
	)) {
		stream << L" crop=(" << m_sourceRegion.left << L"," << m_sourceRegion.top
			<< L")-" << (m_sourceRegion.left + m_sourceRegion.width)
			<< L"," << (m_sourceRegion.top + m_sourceRegion.height);
	}
	LogLine(stream.str());
}

void CaptureController::InitializeMediaFoundation() {
	check_hresult(MFStartup(MF_VERSION));
	m_mediaFoundationStarted = true;

	winrt::com_ptr<IMFAttributes> attributes;
	check_hresult(MFCreateAttributes(attributes.put(), 4));
	check_hresult(attributes->SetUINT32(MF_READWRITE_ENABLE_HARDWARE_TRANSFORMS, TRUE));
	check_hresult(attributes->SetUINT32(MF_SINK_WRITER_DISABLE_THROTTLING, TRUE));
	check_hresult(attributes->SetUINT32(MF_LOW_LATENCY, TRUE));
	check_hresult(attributes->SetGUID(MF_TRANSCODE_CONTAINERTYPE, MFTranscodeContainerType_MPEG4));

	check_hresult(MFCreateSinkWriterFromURL(
		m_args.videoOut.c_str(),
		nullptr,
		attributes.get(),
		m_sinkWriter.put()
	));

	winrt::com_ptr<IMFMediaType> videoOutputType;
	check_hresult(MFCreateMediaType(videoOutputType.put()));
	check_hresult(videoOutputType->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video));
	check_hresult(videoOutputType->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_H264));
	check_hresult(videoOutputType->SetUINT32(
		MF_MT_AVG_BITRATE,
		std::clamp<UINT32>(
			m_outputSize.width * m_outputSize.height * static_cast<UINT32>(m_args.targetFps) / 10U,
			6'000'000U,
			20'000'000U
		)
	));
	check_hresult(videoOutputType->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive));
	check_hresult(MFSetAttributeSize(videoOutputType.get(), MF_MT_FRAME_SIZE, m_outputSize.width, m_outputSize.height));
	check_hresult(MFSetAttributeRatio(videoOutputType.get(), MF_MT_FRAME_RATE, static_cast<UINT32>(m_args.targetFps), 1));
	check_hresult(MFSetAttributeRatio(videoOutputType.get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1));
	check_hresult(m_sinkWriter->AddStream(videoOutputType.get(), &m_videoStreamIndex));

	winrt::com_ptr<IMFMediaType> videoInputType;
	check_hresult(MFCreateMediaType(videoInputType.put()));
	check_hresult(videoInputType->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video));
	check_hresult(videoInputType->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_ARGB32));
	check_hresult(videoInputType->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive));
	check_hresult(MFSetAttributeSize(videoInputType.get(), MF_MT_FRAME_SIZE, m_outputSize.width, m_outputSize.height));
	check_hresult(MFSetAttributeRatio(videoInputType.get(), MF_MT_FRAME_RATE, static_cast<UINT32>(m_args.targetFps), 1));
	check_hresult(MFSetAttributeRatio(videoInputType.get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1));
	check_hresult(videoInputType->SetUINT32(MF_MT_FIXED_SIZE_SAMPLES, TRUE));
	check_hresult(videoInputType->SetUINT32(MF_MT_ALL_SAMPLES_INDEPENDENT, TRUE));
	check_hresult(videoInputType->SetUINT32(MF_MT_DEFAULT_STRIDE, static_cast<UINT32>(m_outputSize.width * 4U)));
	check_hresult(videoInputType->SetUINT32(MF_MT_SAMPLE_SIZE, m_outputSize.width * m_outputSize.height * 4U));
	check_hresult(m_sinkWriter->SetInputMediaType(m_videoStreamIndex, videoInputType.get(), nullptr));

	winrt::com_ptr<IMFMediaType> audioOutputType;
	check_hresult(MFCreateMediaType(audioOutputType.put()));
	check_hresult(audioOutputType->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Audio));
	check_hresult(audioOutputType->SetGUID(MF_MT_SUBTYPE, MFAudioFormat_AAC));
	check_hresult(audioOutputType->SetUINT32(MF_MT_AUDIO_BITS_PER_SAMPLE, kAudioBitsPerSample));
	check_hresult(audioOutputType->SetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND, kAudioSampleRate));
	check_hresult(audioOutputType->SetUINT32(MF_MT_AUDIO_NUM_CHANNELS, kAudioChannels));
	check_hresult(audioOutputType->SetUINT32(MF_MT_AUDIO_AVG_BYTES_PER_SECOND, 24'000));
	check_hresult(audioOutputType->SetUINT32(MF_MT_AAC_PAYLOAD_TYPE, 0));
	check_hresult(audioOutputType->SetUINT32(MF_MT_AAC_AUDIO_PROFILE_LEVEL_INDICATION, 0x29));
	check_hresult(m_sinkWriter->AddStream(audioOutputType.get(), &m_audioStreamIndex));

	winrt::com_ptr<IMFMediaType> audioInputType;
	check_hresult(MFCreateMediaType(audioInputType.put()));
	check_hresult(audioInputType->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Audio));
	check_hresult(audioInputType->SetGUID(MF_MT_SUBTYPE, MFAudioFormat_PCM));
	check_hresult(audioInputType->SetUINT32(MF_MT_AUDIO_BITS_PER_SAMPLE, kAudioBitsPerSample));
	check_hresult(audioInputType->SetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND, kAudioSampleRate));
	check_hresult(audioInputType->SetUINT32(MF_MT_AUDIO_NUM_CHANNELS, kAudioChannels));
	check_hresult(audioInputType->SetUINT32(MF_MT_AUDIO_BLOCK_ALIGNMENT, kAudioChannels * (kAudioBitsPerSample / 8U)));
	check_hresult(audioInputType->SetUINT32(
		MF_MT_AUDIO_AVG_BYTES_PER_SECOND,
		kAudioSampleRate * kAudioChannels * (kAudioBitsPerSample / 8U)
	));
	check_hresult(m_sinkWriter->SetInputMediaType(m_audioStreamIndex, audioInputType.get(), nullptr));

	check_hresult(m_sinkWriter->BeginWriting());
	m_writerStarted = true;
	m_startQpc100ns = CurrentQpc100ns();
}

void CaptureController::InitializeCaptureDevices() {
	m_d3dContext = nullptr;
	m_d3dDevice = CreateD3DDevice(m_d3dContext);
	m_captureDevice = CreateDirect3DDevice(m_d3dDevice.get());
}

void CaptureController::StartAudioCapture() {
	const bool debugEnabled = !m_args.debugAudioDir.empty();
	m_systemAudio = std::make_unique<AudioCaptureSource>(
		AudioCaptureSource::Kind::SystemLoopback,
		L"",
		L"",
		m_mixedAudioBuffer,
		m_startQpc100ns,
		debugEnabled
	);
	m_systemAudio->Start();
	if (!m_systemAudio->Error().empty()) {
		LogLine(L"System loopback unavailable; recording continues with silence for missing system audio");
	}

	if (m_args.micEnabled) {
		m_microphoneAudio = std::make_unique<AudioCaptureSource>(
			AudioCaptureSource::Kind::Microphone,
			m_args.microphoneDeviceId,
			m_args.microphoneDeviceName,
			m_mixedAudioBuffer,
			m_startQpc100ns,
			debugEnabled
		);
		m_microphoneAudio->Start();
		if (!m_microphoneAudio->Error().empty()) {
			LogLine(L"Microphone unavailable; recording continues without microphone input");
		}
	}

	m_audioMixerThread = std::thread([this] {
		AudioMixerLoop();
	});
}

void CaptureController::StartStopWatcher() {
	HANDLE rawProcessHandle = OpenProcess(SYNCHRONIZE | PROCESS_QUERY_LIMITED_INFORMATION, FALSE, m_args.capturePid);
	if (rawProcessHandle != nullptr) {
		m_captureProcessHandle.attach(rawProcessHandle);
	}

	m_stopWatcherThread = std::thread([this] {
		while (!m_stopRequested.load()) {
			if (std::filesystem::exists(m_args.stopFile)) {
				LogLine(L"Stop file detected");
				RequestStop();
				break;
			}
			if (m_captureProcessHandle && WaitForSingleObject(m_captureProcessHandle.get(), 0) == WAIT_OBJECT_0) {
				LogLine(L"Capture process exited; stopping helper");
				RequestStop();
				break;
			}
			std::this_thread::sleep_for(std::chrono::milliseconds(50));
		}
	});
}

void CaptureController::StartCaptureSession() {
	m_framePool = Direct3D11CaptureFramePool::CreateFreeThreaded(
		m_captureDevice,
		DirectXPixelFormat::B8G8R8A8UIntNormalized,
		2,
		m_currentContentSize
	);
	m_frameArrivedToken = m_framePool.FrameArrived({this, &CaptureController::OnFrameArrived});
	m_captureSession = m_framePool.CreateCaptureSession(m_captureItem);
	m_captureSession.IsCursorCaptureEnabled(true);
	m_captureSession.StartCapture();
}

void CaptureController::WaitForReady() {
	std::unique_lock lock(m_stateMutex);
	if (!m_stateCv.wait_for(lock, std::chrono::seconds(15), [&] {
		return m_ready || !m_fatalError.empty() || m_stopRequested.load();
	})) {
		ThrowWithMessage(L"Timed out waiting for the first Windows capture frame");
	}
	if (!m_fatalError.empty()) {
		ThrowWithMessage(L"Capture failed during startup: " + m_fatalError);
	}
	if (!m_ready) {
		ThrowWithMessage(L"Capture stopped before readiness");
	}
}

void CaptureController::WriteReadyFile() {
	std::wofstream output(m_args.readyFile, std::ios::binary | std::ios::trunc);
	if (!output) {
		ThrowWithMessage(L"Failed to create ready file at " + m_args.readyFile.wstring());
	}

	std::wstring systemAudioName = m_systemAudio ? m_systemAudio->SelectedDeviceName() : L"";
	std::wstring microphoneAudioName = m_microphoneAudio ? m_microphoneAudio->SelectedDeviceName() : L"";

	output << L"{\n"
		<< L"  \"pid\": " << GetCurrentProcessId() << L",\n"
		<< L"  \"capturePid\": " << m_args.capturePid << L",\n"
		<< L"  \"videoPath\": \"" << JsonEscape(m_args.videoOut.wstring()) << L"\",\n"
		<< L"  \"captureTarget\": \"" << JsonEscape(m_targetWindowDescription) << L"\",\n"
		<< L"  \"width\": " << m_outputSize.width << L",\n"
		<< L"  \"height\": " << m_outputSize.height << L",\n"
		<< L"  \"targetFps\": " << m_args.targetFps << L",\n"
		<< L"  \"systemAudioDevice\": \"" << JsonEscape(systemAudioName) << L"\",\n"
		<< L"  \"microphoneEnabled\": " << (m_args.micEnabled ? L"true" : L"false") << L",\n"
		<< L"  \"microphoneDevice\": \"" << JsonEscape(microphoneAudioName) << L"\",\n"
		<< L"  \"debugAudioDir\": \"" << JsonEscape(m_args.debugAudioDir.wstring()) << L"\"\n"
		<< L"}\n";
	output.flush();
	if (!output) {
		ThrowWithMessage(L"Failed to flush ready file at " + m_args.readyFile.wstring());
	}
	LogLine(L"Ready file written");
}

void CaptureController::WaitForStop() {
	std::unique_lock lock(m_stateMutex);
	m_stateCv.wait(lock, [&] {
		return m_stopRequested.load();
	});
}

void CaptureController::RequestStop() {
	bool expected = false;
	if (!m_stopRequested.compare_exchange_strong(expected, true)) {
		return;
	}
	m_stopTime100ns = std::max<INT64>(0, CurrentQpc100ns() - m_startQpc100ns);
	if (m_systemAudio) {
		m_systemAudio->SignalStop();
	}
	if (m_microphoneAudio) {
		m_microphoneAudio->SignalStop();
	}
	m_stateCv.notify_all();
}

void CaptureController::ShutdownCaptureSession() {
	if (m_framePool) {
		m_framePool.FrameArrived(m_frameArrivedToken);
	}
	m_captureSession = nullptr;
	m_framePool = nullptr;

	if (m_stopWatcherThread.joinable()) {
		m_stopWatcherThread.join();
	}
}

void CaptureController::StopAudioCapture() {
	if (m_systemAudio) {
		m_systemAudio->Stop();
	}
	if (m_microphoneAudio) {
		m_microphoneAudio->Stop();
	}
	m_audioSourcesStopped = true;
}

void CaptureController::StopMixerThread() {
	if (m_audioMixerThread.joinable()) {
		m_audioMixerThread.join();
	}
}

void CaptureController::WriteDebugArtifacts() {
	if (m_args.debugAudioDir.empty()) {
		return;
	}

	try {
		std::filesystem::create_directories(m_args.debugAudioDir);

		const std::filesystem::path reportPath = m_args.debugAudioDir / "windows-audio-debug.json";
		const std::filesystem::path systemWavePath = m_args.debugAudioDir / "windows-system-loopback-debug.wav";
		const std::filesystem::path microphoneWavePath = m_args.debugAudioDir / "windows-microphone-debug.wav";
		const std::filesystem::path mixedWavePath = m_args.debugAudioDir / "windows-mixed-output-debug.wav";

		AudioDebugSummary systemSummary = m_systemAudio ? m_systemAudio->DebugSummary() : AudioDebugSummary{};
		AudioDebugSummary microphoneSummary = m_microphoneAudio ? m_microphoneAudio->DebugSummary() : AudioDebugSummary{};

		const INT64 systemFrames = m_systemAudio ? m_systemAudio->DebugBuffer().MaxFrame() : 0;
		const INT64 microphoneFrames = m_microphoneAudio ? m_microphoneAudio->DebugBuffer().MaxFrame() : 0;
		const INT64 mixedFrames = std::max<INT64>(0, m_debugMixedTotalFrames);

		if (m_systemAudio && systemFrames > 0) {
			WriteWaveFileFromBuffer(systemWavePath, m_systemAudio->DebugBuffer(), systemFrames);
		}
		if (m_microphoneAudio && microphoneFrames > 0) {
			WriteWaveFileFromBuffer(microphoneWavePath, m_microphoneAudio->DebugBuffer(), microphoneFrames);
		}
		if (mixedFrames > 0) {
			WriteWaveFileFromBuffer(mixedWavePath, m_mixedAudioBuffer, mixedFrames, m_debugMixedGain);
		}

		const AudioBufferAnalysis systemAnalysis =
			(m_systemAudio && systemFrames > 0)
				? AnalyzeAudioBuffer(m_systemAudio->DebugBuffer(), systemFrames)
				: AudioBufferAnalysis{};
		const AudioBufferAnalysis microphoneAnalysis =
			(m_microphoneAudio && microphoneFrames > 0)
				? AnalyzeAudioBuffer(m_microphoneAudio->DebugBuffer(), microphoneFrames)
				: AudioBufferAnalysis{};
		const AudioBufferAnalysis mixedRawAnalysis =
			mixedFrames > 0
				? AnalyzeAudioBuffer(m_mixedAudioBuffer, mixedFrames)
				: AudioBufferAnalysis{};
		const AudioBufferAnalysis mixedOutputAnalysis =
			mixedFrames > 0
				? AnalyzeAudioBuffer(m_mixedAudioBuffer, mixedFrames, m_debugMixedGain)
				: AudioBufferAnalysis{};

		std::wofstream output(reportPath, std::ios::binary | std::ios::trunc);
		if (!output) {
			ThrowWithMessage(L"Failed to create debug JSON at " + reportPath.wstring());
		}

		auto writeSourceSection = [&](const std::wstring& key,
			const AudioDebugSummary& summary,
			const AudioBufferAnalysis& analysis,
			const std::filesystem::path& wavePath,
			bool enabled) {
			output << L"  \"" << key << L"\": {\n"
				<< L"    \"enabled\": " << (enabled ? L"true" : L"false") << L",\n"
				<< L"    \"requestedSelector\": \"" << JsonEscape(summary.requestedSelector) << L"\",\n"
				<< L"    \"requestedPreferredName\": \"" << JsonEscape(summary.requestedPreferredName) << L"\",\n"
				<< L"    \"selectedDeviceId\": \"" << JsonEscape(summary.selectedDeviceId) << L"\",\n"
				<< L"    \"selectedDeviceName\": \"" << JsonEscape(summary.selectedDeviceName) << L"\",\n"
				<< L"    \"selectedDeviceDescription\": \"" << JsonEscape(summary.selectedDeviceDescription) << L"\",\n"
				<< L"    \"selectionMode\": \"" << JsonEscape(summary.selectionMode) << L"\",\n"
				<< L"    \"initializeMode\": \"" << JsonEscape(summary.initializeMode) << L"\",\n"
				<< L"    \"timelineMode\": \"" << JsonEscape(summary.timelineMode) << L"\",\n"
				<< L"    \"sourceFormat\": \"" << JsonEscape(AudioFormatToString(summary.sourceFormat)) << L"\",\n"
				<< L"    \"packetCount\": " << summary.packetCount << L",\n"
				<< L"    \"silentPacketCount\": " << summary.silentPacketCount << L",\n"
				<< L"    \"fallbackTimestampPacketCount\": " << summary.fallbackTimestampPacketCount << L",\n"
				<< L"    \"totalCommittedFrames\": " << summary.totalCommittedFrames << L",\n"
				<< L"    \"maxCommittedFrame\": " << summary.maxCommittedFrame << L",\n"
				<< L"    \"capturePeak\": " << std::fixed << std::setprecision(6) << summary.capturePeak << L",\n"
				<< L"    \"analysisFrames\": " << analysis.totalFrames << L",\n"
				<< L"    \"analysisPeak\": " << std::fixed << std::setprecision(6) << analysis.peak << L",\n"
				<< L"    \"analysisRms\": " << std::fixed << std::setprecision(6) << analysis.rms << L",\n"
				<< L"    \"analysisAverageAbs\": " << std::fixed << std::setprecision(6) << analysis.averageAbs << L",\n"
				<< L"    \"analysisNonZeroSamples\": " << analysis.nonZeroSamples << L",\n"
				<< L"    \"analysisFirstNonZeroFrame\": " << analysis.firstNonZeroFrame << L",\n"
				<< L"    \"error\": \"" << JsonEscape(summary.error) << L"\",\n"
				<< L"    \"debugWavePath\": \"" << JsonEscape(wavePath.wstring()) << L"\"\n"
				<< L"  }";
		};

		output << L"{\n"
			<< L"  \"videoPath\": \"" << JsonEscape(m_args.videoOut.wstring()) << L"\",\n"
			<< L"  \"debugAudioDir\": \"" << JsonEscape(m_args.debugAudioDir.wstring()) << L"\",\n"
			<< L"  \"stopTime100ns\": " << m_stopTime100ns << L",\n"
			<< L"  \"targetAudioFormat\": \"" << kAudioSampleRate << L"Hz/" << kAudioChannels << L"ch/" << kAudioBitsPerSample << L"i\",\n";
		writeSourceSection(L"systemLoopback", systemSummary, systemAnalysis, systemWavePath, m_systemAudio != nullptr);
		output << L",\n";
		writeSourceSection(L"microphone", microphoneSummary, microphoneAnalysis, microphoneWavePath, m_microphoneAudio != nullptr);
		output << L",\n"
			<< L"  \"mixedAudio\": {\n"
			<< L"    \"frames\": " << mixedFrames << L",\n"
			<< L"    \"sourcePeak\": " << std::fixed << std::setprecision(6) << m_debugMixedSourcePeak << L",\n"
			<< L"    \"gain\": " << std::fixed << std::setprecision(6) << m_debugMixedGain << L",\n"
			<< L"    \"writtenPeak\": " << std::fixed << std::setprecision(6) << m_debugMixedWrittenPeak << L",\n"
			<< L"    \"rawRms\": " << std::fixed << std::setprecision(6) << mixedRawAnalysis.rms << L",\n"
			<< L"    \"rawAverageAbs\": " << std::fixed << std::setprecision(6) << mixedRawAnalysis.averageAbs << L",\n"
			<< L"    \"rawNonZeroSamples\": " << mixedRawAnalysis.nonZeroSamples << L",\n"
			<< L"    \"rawFirstNonZeroFrame\": " << mixedRawAnalysis.firstNonZeroFrame << L",\n"
			<< L"    \"outputRms\": " << std::fixed << std::setprecision(6) << mixedOutputAnalysis.rms << L",\n"
			<< L"    \"outputAverageAbs\": " << std::fixed << std::setprecision(6) << mixedOutputAnalysis.averageAbs << L",\n"
			<< L"    \"outputNonZeroSamples\": " << mixedOutputAnalysis.nonZeroSamples << L",\n"
			<< L"    \"outputFirstNonZeroFrame\": " << mixedOutputAnalysis.firstNonZeroFrame << L",\n"
			<< L"    \"debugWavePath\": \"" << JsonEscape(mixedWavePath.wstring()) << L"\"\n"
			<< L"  }\n"
			<< L"}\n";
		output.flush();
		if (!output) {
			ThrowWithMessage(L"Failed to flush debug JSON at " + reportPath.wstring());
		}

		LogLine(L"Audio debug artifacts written: " + reportPath.wstring());
	} catch (const winrt::hresult_error& error) {
		LogLine(L"Audio debug artifact write failed: " + std::wstring(error.message()));
	} catch (const std::exception& error) {
		LogLine(L"Audio debug artifact write failed: " + Utf8Literal(error.what()));
	}
}

void CaptureController::FinalizeWriter() {
	if (m_sinkWriter && m_writerStarted) {
		std::lock_guard lock(m_sinkMutex);
		check_hresult(m_sinkWriter->Finalize());
	}
}

void CaptureController::FailCapture(const std::wstring& message) {
	{
		std::lock_guard lock(m_stateMutex);
		if (m_fatalError.empty()) {
			m_fatalError = message;
		}
	}
	LogLine(L"Capture failure: " + message);
	RequestStop();
}

void CaptureController::EnsureStagingTexture(UINT32 width, UINT32 height) {
	if (m_stagingTexture) {
		D3D11_TEXTURE2D_DESC existing{};
		m_stagingTexture->GetDesc(&existing);
		if (existing.Width == width && existing.Height == height) {
			return;
		}
	}

	D3D11_TEXTURE2D_DESC description{};
	description.Width = width;
	description.Height = height;
	description.MipLevels = 1;
	description.ArraySize = 1;
	description.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
	description.SampleDesc.Count = 1;
	description.Usage = D3D11_USAGE_STAGING;
	description.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
	check_hresult(m_d3dDevice->CreateTexture2D(&description, nullptr, m_stagingTexture.put()));
}

void CaptureController::CopyFrameToScratch(
	const Direct3D11CaptureFrame& frame,
	UINT32 sourceWidth,
	UINT32 sourceHeight,
	const CaptureRegion& sourceRegion
) {
	EnsureStagingTexture(sourceWidth, sourceHeight);
	auto sourceTexture = GetDXGIInterfaceFromObject<ID3D11Texture2D>(frame.Surface());
	m_d3dContext->CopyResource(m_stagingTexture.get(), sourceTexture.get());

	D3D11_MAPPED_SUBRESOURCE mapped{};
	check_hresult(m_d3dContext->Map(m_stagingTexture.get(), 0, D3D11_MAP_READ, 0, &mapped));

	const size_t outputStride = static_cast<size_t>(m_outputSize.width) * 4;
	const size_t outputBytes = outputStride * static_cast<size_t>(m_outputSize.height);
	m_scratchVideoBytes.resize(outputBytes);

	const auto* sourceBytes = reinterpret_cast<const BYTE*>(mapped.pData);
	const UINT32 cropLeft = std::min<UINT32>(sourceRegion.left, sourceWidth - 1);
	const UINT32 cropTop = std::min<UINT32>(sourceRegion.top, sourceHeight - 1);
	const UINT32 croppedWidth = std::max<UINT32>(1, std::min<UINT32>(sourceRegion.width, sourceWidth - cropLeft));
	const UINT32 croppedHeight = std::max<UINT32>(1, std::min<UINT32>(sourceRegion.height, sourceHeight - cropTop));

	if (croppedWidth == m_outputSize.width && croppedHeight == m_outputSize.height) {
		for (UINT32 row = 0; row < m_outputSize.height; ++row) {
			const BYTE* rowSource = sourceBytes +
				static_cast<size_t>(cropTop + row) * mapped.RowPitch +
				static_cast<size_t>(cropLeft) * 4;
			std::memcpy(
				m_scratchVideoBytes.data() + static_cast<size_t>(row) * outputStride,
				rowSource,
				outputStride
			);
		}
	} else {
		for (UINT32 row = 0; row < m_outputSize.height; ++row) {
			UINT32 sourceRow = std::min<UINT32>(
				croppedHeight - 1,
				static_cast<UINT32>((static_cast<uint64_t>(row) * croppedHeight) / m_outputSize.height)
			);
			const BYTE* sourceRowBytes = sourceBytes +
				static_cast<size_t>(cropTop + sourceRow) * mapped.RowPitch;
			BYTE* destinationRow = m_scratchVideoBytes.data() + static_cast<size_t>(row) * outputStride;
			for (UINT32 column = 0; column < m_outputSize.width; ++column) {
				UINT32 sourceColumn = std::min<UINT32>(
					croppedWidth - 1,
					static_cast<UINT32>((static_cast<uint64_t>(column) * croppedWidth) / m_outputSize.width)
				);
				const BYTE* sourcePixel = sourceRowBytes +
					static_cast<size_t>(cropLeft + sourceColumn) * 4;
				BYTE* destinationPixel = destinationRow + static_cast<size_t>(column) * 4;
				destinationPixel[0] = sourcePixel[0];
				destinationPixel[1] = sourcePixel[1];
				destinationPixel[2] = sourcePixel[2];
				destinationPixel[3] = sourcePixel[3];
			}
		}
	}

	m_d3dContext->Unmap(m_stagingTexture.get(), 0);
}

void CaptureController::WriteVideoSample(
	const std::vector<uint8_t>& pixels,
	INT64 sampleTime100ns,
	INT64 sampleDuration100ns
) {
	winrt::com_ptr<IMFMediaBuffer> buffer;
	check_hresult(MFCreateMemoryBuffer(static_cast<DWORD>(pixels.size()), buffer.put()));

	BYTE* destination = nullptr;
	DWORD maxLength = 0;
	DWORD currentLength = 0;
	check_hresult(buffer->Lock(&destination, &maxLength, &currentLength));
	std::memcpy(destination, pixels.data(), pixels.size());
	check_hresult(buffer->Unlock());
	check_hresult(buffer->SetCurrentLength(static_cast<DWORD>(pixels.size())));

	winrt::com_ptr<IMFSample> sample;
	check_hresult(MFCreateSample(sample.put()));
	check_hresult(sample->AddBuffer(buffer.get()));
	check_hresult(sample->SetSampleTime(sampleTime100ns));
	check_hresult(sample->SetSampleDuration(std::max<INT64>(1, sampleDuration100ns)));

	std::lock_guard lock(m_sinkMutex);
	check_hresult(m_sinkWriter->WriteSample(m_videoStreamIndex, sample.get()));
}

void CaptureController::FlushPendingVideoFrame() {
	std::lock_guard lock(m_videoMutex);
	if (m_pendingVideoBytes.empty()) {
		return;
	}

	if (m_nextVideoSampleTime100ns < 0) {
		m_pendingVideoBytes.clear();
		return;
	}

	while (m_nextVideoSampleTime100ns < m_stopTime100ns) {
		WriteVideoSample(m_pendingVideoBytes, m_nextVideoSampleTime100ns, m_frameInterval100ns);
		m_nextVideoSampleTime100ns += m_frameInterval100ns;
	}

	m_pendingVideoBytes.clear();
	m_pendingVideoTimestamp100ns = -1;
	m_nextVideoSampleTime100ns = -1;
}

void CaptureController::WriteAudioSample(INT64 startFrame, UINT32 frameCount, const std::vector<float>& frames) {
	std::vector<int16_t> pcm = ConvertFramesToPcm16(frames);

	winrt::com_ptr<IMFMediaBuffer> buffer;
	check_hresult(MFCreateMemoryBuffer(static_cast<DWORD>(pcm.size() * sizeof(int16_t)), buffer.put()));

	BYTE* destination = nullptr;
	DWORD maxLength = 0;
	DWORD currentLength = 0;
	check_hresult(buffer->Lock(&destination, &maxLength, &currentLength));
	std::memcpy(destination, pcm.data(), pcm.size() * sizeof(int16_t));
	check_hresult(buffer->Unlock());
	check_hresult(buffer->SetCurrentLength(static_cast<DWORD>(pcm.size() * sizeof(int16_t))));

	winrt::com_ptr<IMFSample> sample;
	check_hresult(MFCreateSample(sample.put()));
	check_hresult(sample->AddBuffer(buffer.get()));
	check_hresult(sample->SetSampleTime(AudioFrameToTime100ns(startFrame)));
	check_hresult(sample->SetSampleDuration(
		std::max<INT64>(1, AudioFrameToTime100ns(startFrame + frameCount) - AudioFrameToTime100ns(startFrame))
	));

	std::lock_guard lock(m_sinkMutex);
	check_hresult(m_sinkWriter->WriteSample(m_audioStreamIndex, sample.get()));
}

void CaptureController::AudioMixerLoop() {
	while (!(m_stopRequested.load() && m_audioSourcesStopped)) {
		std::this_thread::sleep_for(std::chrono::milliseconds(10));
	}

	const INT64 totalFrames = std::max<INT64>(
		AudioFramesFromTime100ns(m_stopTime100ns),
		m_mixedAudioBuffer.MaxFrame()
	);
	m_debugMixedTotalFrames = totalFrames;
	if (totalFrames <= 0) {
		m_debugMixedSourcePeak = 0.0F;
		m_debugMixedGain = 1.0F;
		m_debugMixedWrittenPeak = 0.0F;
		LogLine(L"Audio mixer wrote: frames=0 source_peak=0.0000 gain=1.0000 written_peak=0.0000");
		return;
	}

	float sourcePeak = 0.0F;
	for (INT64 frameIndex = 0; frameIndex < totalFrames; frameIndex += kAudioChunkFrames) {
		UINT32 chunkFrames = static_cast<UINT32>(std::min<INT64>(kAudioChunkFrames, totalFrames - frameIndex));
		std::vector<float> frames;
		m_mixedAudioBuffer.ReadFrames(frameIndex, chunkFrames, frames);
		for (float sample : frames) {
			sourcePeak = std::max(sourcePeak, std::fabs(sample));
		}
	}

	float gain = 1.0F;
	if (sourcePeak > kAudioSilenceFloor && sourcePeak < kAudioNormalizationTriggerPeak) {
		gain = std::min(kAudioNormalizationMaxGain, kAudioNormalizationTargetPeak / sourcePeak);
	}
	m_debugMixedSourcePeak = sourcePeak;
	m_debugMixedGain = gain;

	float writtenPeak = 0.0F;
	for (INT64 frameIndex = 0; frameIndex < totalFrames; frameIndex += kAudioChunkFrames) {
		UINT32 chunkFrames = static_cast<UINT32>(std::min<INT64>(kAudioChunkFrames, totalFrames - frameIndex));
		std::vector<float> frames;
		m_mixedAudioBuffer.ReadFrames(frameIndex, chunkFrames, frames);
		if (gain != 1.0F) {
			for (float& sample : frames) {
				sample = std::clamp(sample * gain, -1.0F, 1.0F);
			}
		}
		for (float sample : frames) {
			writtenPeak = std::max(writtenPeak, std::fabs(sample));
		}
		WriteAudioSample(frameIndex, chunkFrames, frames);
	}
	m_debugMixedWrittenPeak = writtenPeak;

	std::wstringstream mixerMessage;
	mixerMessage << L"Audio mixer wrote: frames=" << totalFrames
		<< L" source_peak=" << std::fixed << std::setprecision(4) << sourcePeak
		<< L" gain=" << std::fixed << std::setprecision(4) << gain
		<< L" written_peak=" << std::fixed << std::setprecision(4) << writtenPeak;
	LogLine(mixerMessage.str());
}

void CaptureController::OnFrameArrived(
	const Direct3D11CaptureFramePool& sender,
	const winrt::Windows::Foundation::IInspectable&
) {
	if (m_stopRequested.load()) {
		return;
	}

	try {
		Direct3D11CaptureFrame frame = sender.TryGetNextFrame();
		if (!frame) {
			return;
		}

		const auto size = frame.ContentSize();
		if (size.Width <= 0 || size.Height <= 0) {
			return;
		}
		if (size.Width != m_currentContentSize.Width || size.Height != m_currentContentSize.Height) {
			m_currentContentSize = size;
			m_framePool.Recreate(
				m_captureDevice,
				DirectXPixelFormat::B8G8R8A8UIntNormalized,
				2,
				m_currentContentSize
			);
		}

		const INT64 timestamp100ns = std::max<INT64>(0, frame.SystemRelativeTime().count() - m_startQpc100ns);

		const UINT32 sourceWidth = static_cast<UINT32>(size.Width);
		const UINT32 sourceHeight = static_cast<UINT32>(size.Height);
		m_sourceRegion = ComputeClientCaptureRegion(m_targetHwnd, sourceWidth, sourceHeight);
		CopyFrameToScratch(frame, sourceWidth, sourceHeight, m_sourceRegion);

		std::lock_guard lock(m_videoMutex);
		if (m_pendingVideoBytes.empty()) {
			m_pendingVideoBytes.swap(m_scratchVideoBytes);
			m_pendingVideoTimestamp100ns = timestamp100ns;
			m_lastVideoDuration100ns = m_frameInterval100ns;
			WriteVideoSample(m_pendingVideoBytes, timestamp100ns, m_frameInterval100ns);
			m_nextVideoSampleTime100ns = timestamp100ns + m_frameInterval100ns;
		} else {
			while (m_nextVideoSampleTime100ns >= 0 && m_nextVideoSampleTime100ns < timestamp100ns) {
				WriteVideoSample(m_pendingVideoBytes, m_nextVideoSampleTime100ns, m_frameInterval100ns);
				m_lastVideoDuration100ns = m_frameInterval100ns;
				m_nextVideoSampleTime100ns += m_frameInterval100ns;
			}

			m_pendingVideoBytes.swap(m_scratchVideoBytes);
			m_pendingVideoTimestamp100ns = timestamp100ns;
		}

		{
			std::lock_guard stateLock(m_stateMutex);
			if (!m_ready) {
				m_ready = true;
			}
		}
		m_stateCv.notify_all();
	} catch (const winrt::hresult_error& error) {
		FailCapture(error.message().c_str());
	} catch (const std::exception& error) {
		FailCapture(Utf8Literal(error.what()));
	}
}

} // namespace

int wmain(int argc, wchar_t** argv) {
	try {
		SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
		SetProcessDPIAware();

		const Arguments args = ParseArguments(argc, argv);
		LogLine(L"BlockChat Windows helper starting");
		CaptureController controller(args);
		controller.Run();
		return 0;
	} catch (const winrt::hresult_error& error) {
		LogLine(L"BlockChat Windows helper fatal error: " + std::wstring(error.message()));
		return 1;
	} catch (const std::exception& error) {
		LogLine(L"BlockChat Windows helper fatal error: " + Utf8Literal(error.what()));
		return 1;
	}
}
