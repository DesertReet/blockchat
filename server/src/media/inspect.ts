import type { AllowedContentType } from '../ws-protocol';

export type DetectedMediaMetadata = {
	mediaType: 'image' | 'video';
	contentType: AllowedContentType;
	width: number;
	height: number;
};

export class MediaInspectionError extends Error {
	constructor(message: string) {
		super(message);
		this.name = 'MediaInspectionError';
	}
}

interface ByteReader {
	readonly size: number;
	read(offset: number, length: number): Promise<Uint8Array>;
}

const PNG_SIGNATURE = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
const PNG_IEND_TRAILER = new Uint8Array([0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4e, 0x44, 0xae, 0x42, 0x60, 0x82]);
const GIF_87A = 'GIF87a';
const GIF_89A = 'GIF89a';
const JPEG_SOI = new Uint8Array([0xff, 0xd8]);
const JPEG_EOI = new Uint8Array([0xff, 0xd9]);
const JPEG_START_OF_FRAME_MARKERS = new Set([0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf]);
const MP4_COMPATIBLE_PREFIX_BOXES = new Set(['ftyp', 'free', 'skip', 'wide']);
const TEXT_DECODER = new TextDecoder();

const EBML_IDS = {
	ebml: 0x1a45dfa3,
	docType: 0x4282,
	segment: 0x18538067,
	tracks: 0x1654ae6b,
	trackEntry: 0xae,
	trackType: 0x83,
	video: 0xe0,
	pixelWidth: 0xb0,
	pixelHeight: 0xba,
} as const;

export async function inspectMediaBytes(bytes: ArrayBuffer | Uint8Array): Promise<DetectedMediaMetadata> {
	const view = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
	return inspectMedia(new InMemoryByteReader(view));
}

export async function inspectR2MediaObject(
	bucket: R2Bucket,
	key: string,
	size: number,
	chunkSize = 64 * 1024
): Promise<DetectedMediaMetadata> {
	return inspectMedia(new R2RangeByteReader(bucket, key, size, chunkSize));
}

async function inspectMedia(reader: ByteReader): Promise<DetectedMediaMetadata> {
	if (!Number.isFinite(reader.size) || reader.size <= 0) {
		throw new MediaInspectionError('Uploaded media was empty');
	}

	const sniffLength = Math.min(reader.size, 64);
	const head = await readExact(reader, 0, sniffLength);

	if (matchesAt(head, 0, PNG_SIGNATURE)) {
		return inspectPng(reader);
	}
	if (startsWithAscii(head, GIF_87A) || startsWithAscii(head, GIF_89A)) {
		return inspectGif(reader);
	}
	if (matchesAt(head, 0, JPEG_SOI)) {
		return inspectJpeg(reader);
	}
	if (await looksLikeMp4(reader)) {
		return inspectMp4(reader);
	}
	if (await looksLikeWebm(reader)) {
		return inspectWebm(reader);
	}

	throw new MediaInspectionError('Uploaded media format is not supported');
}

class InMemoryByteReader implements ByteReader {
	readonly size: number;

	constructor(private readonly bytes: Uint8Array) {
		this.size = bytes.byteLength;
	}

	async read(offset: number, length: number): Promise<Uint8Array> {
		const safeOffset = clampOffset(offset, this.size);
		const safeLength = clampLength(length, this.size - safeOffset);
		return this.bytes.subarray(safeOffset, safeOffset + safeLength);
	}
}

class R2RangeByteReader implements ByteReader {
	readonly size: number;
	private readonly cache = new Map<number, Uint8Array>();

	constructor(
		private readonly bucket: R2Bucket,
		private readonly key: string,
		size: number,
		private readonly chunkSize: number
	) {
		this.size = size;
	}

	async read(offset: number, length: number): Promise<Uint8Array> {
		const safeOffset = clampOffset(offset, this.size);
		const safeLength = clampLength(length, this.size - safeOffset);
		if (safeLength === 0) {
			return new Uint8Array(0);
		}

		const startChunk = Math.floor(safeOffset / this.chunkSize);
		const endChunk = Math.floor((safeOffset + safeLength - 1) / this.chunkSize);
		const buffers: Uint8Array[] = [];
		let remaining = safeLength;
		let currentOffset = safeOffset;

		for (let chunkIndex = startChunk; chunkIndex <= endChunk; chunkIndex++) {
			const chunk = await this.readChunk(chunkIndex);
			const chunkStart = chunkIndex * this.chunkSize;
			const relativeStart = Math.max(0, currentOffset - chunkStart);
			const copyLength = Math.min(chunk.byteLength - relativeStart, remaining);
			buffers.push(chunk.subarray(relativeStart, relativeStart + copyLength));
			currentOffset += copyLength;
			remaining -= copyLength;
		}

		if (buffers.length === 1) {
			return buffers[0];
		}
		const merged = new Uint8Array(safeLength);
		let writeOffset = 0;
		for (const buffer of buffers) {
			merged.set(buffer, writeOffset);
			writeOffset += buffer.byteLength;
		}
		return merged;
	}

	private async readChunk(chunkIndex: number): Promise<Uint8Array> {
		const cached = this.cache.get(chunkIndex);
		if (cached) {
			return cached;
		}

		const offset = chunkIndex * this.chunkSize;
		const length = Math.min(this.chunkSize, this.size - offset);
		const object = await this.bucket.get(this.key, { range: { offset, length } });
		if (!object) {
			throw new MediaInspectionError('Uploaded media was not found in storage');
		}
		const bytes = await object.bytes();
		if (bytes.byteLength !== length) {
			throw new MediaInspectionError('Uploaded media was truncated in storage');
		}
		this.cache.set(chunkIndex, bytes);
		return bytes;
	}
}

async function inspectPng(reader: ByteReader): Promise<DetectedMediaMetadata> {
	if (reader.size < 33) {
		throw new MediaInspectionError('Uploaded PNG was truncated');
	}
	const header = await readExact(reader, 0, 33);
	if (!matchesAt(header, 0, PNG_SIGNATURE)) {
		throw new MediaInspectionError('Uploaded PNG had an invalid signature');
	}
	const ihdrLength = readUint32BE(header, 8);
	if (ihdrLength !== 13 || readAscii(header, 12, 4) !== 'IHDR') {
		throw new MediaInspectionError('Uploaded PNG was missing IHDR metadata');
	}
	const width = readUint32BE(header, 16);
	const height = readUint32BE(header, 20);
	if (width <= 0 || height <= 0) {
		throw new MediaInspectionError('Uploaded PNG had invalid dimensions');
	}
	const trailer = await readExact(reader, reader.size - PNG_IEND_TRAILER.byteLength, PNG_IEND_TRAILER.byteLength);
	if (!matchesAt(trailer, 0, PNG_IEND_TRAILER)) {
		throw new MediaInspectionError('Uploaded PNG was truncated');
	}
	return { mediaType: 'image', contentType: 'image/png', width, height };
}

async function inspectGif(reader: ByteReader): Promise<DetectedMediaMetadata> {
	if (reader.size < 14) {
		throw new MediaInspectionError('Uploaded GIF was truncated');
	}
	const header = await readExact(reader, 0, 10);
	const signature = readAscii(header, 0, 6);
	if (signature !== GIF_87A && signature !== GIF_89A) {
		throw new MediaInspectionError('Uploaded GIF had an invalid signature');
	}
	const width = readUint16LE(header, 6);
	const height = readUint16LE(header, 8);
	if (width <= 0 || height <= 0) {
		throw new MediaInspectionError('Uploaded GIF had invalid dimensions');
	}
	const trailer = await readExact(reader, reader.size - 1, 1);
	if (trailer[0] !== 0x3b) {
		throw new MediaInspectionError('Uploaded GIF was truncated');
	}
	return { mediaType: 'image', contentType: 'image/gif', width, height };
}

async function inspectJpeg(reader: ByteReader): Promise<DetectedMediaMetadata> {
	if (reader.size < 6) {
		throw new MediaInspectionError('Uploaded JPEG was truncated');
	}
	const start = await readExact(reader, 0, 2);
	const end = await readExact(reader, reader.size - 2, 2);
	if (!matchesAt(start, 0, JPEG_SOI)) {
		throw new MediaInspectionError('Uploaded JPEG had an invalid header');
	}
	if (!matchesAt(end, 0, JPEG_EOI)) {
		throw new MediaInspectionError('Uploaded JPEG was truncated');
	}

	let offset = 2;
	while (offset < reader.size) {
		const markerPrefix = await readExact(reader, offset, 1);
		if (markerPrefix[0] !== 0xff) {
			throw new MediaInspectionError('Uploaded JPEG had an invalid marker sequence');
		}
		offset += 1;

		let marker = 0xff;
		while (offset < reader.size) {
			marker = (await readExact(reader, offset, 1))[0];
			offset += 1;
			if (marker !== 0xff) {
				break;
			}
		}

		if (marker === 0xd9) {
			break;
		}
		if (marker === 0x00) {
			throw new MediaInspectionError('Uploaded JPEG had invalid scan data before frame metadata');
		}
		if (marker === 0xd8 || marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
			continue;
		}

		const segmentLengthBytes = await readExact(reader, offset, 2);
		const segmentLength = readUint16BE(segmentLengthBytes, 0);
		if (segmentLength < 2) {
			throw new MediaInspectionError('Uploaded JPEG contained an invalid segment');
		}
		if (offset + segmentLength > reader.size) {
			throw new MediaInspectionError('Uploaded JPEG was truncated');
		}

		if (isJpegStartOfFrameMarker(marker)) {
			const frameHeader = await readExact(reader, offset + 2, 5);
			const height = readUint16BE(frameHeader, 1);
			const width = readUint16BE(frameHeader, 3);
			if (width <= 0 || height <= 0) {
				throw new MediaInspectionError('Uploaded JPEG had invalid dimensions');
			}
			return { mediaType: 'image', contentType: 'image/jpeg', width, height };
		}

		if (marker === 0xda) {
			throw new MediaInspectionError('Uploaded JPEG was missing a frame header');
		}

		offset += segmentLength;
	}

	throw new MediaInspectionError('Uploaded JPEG was missing a frame header');
}

async function looksLikeMp4(reader: ByteReader): Promise<boolean> {
	if (reader.size < 12) {
		return false;
	}
	const box = await readMp4Box(reader, 0, reader.size);
	return box !== null && MP4_COMPATIBLE_PREFIX_BOXES.has(box.type);
}

async function inspectMp4(reader: ByteReader): Promise<DetectedMediaMetadata> {
	let offset = 0;
	let sawFtyp = false;
	while (offset < reader.size) {
		const box = await readMp4Box(reader, offset, reader.size);
		if (!box) {
			break;
		}
		if (!sawFtyp) {
			if (box.type === 'ftyp') {
				sawFtyp = true;
			} else if (!MP4_COMPATIBLE_PREFIX_BOXES.has(box.type)) {
				throw new MediaInspectionError('Uploaded MP4 was missing an ftyp box');
			}
		}
		if (box.type === 'moov') {
			if (!sawFtyp) {
				throw new MediaInspectionError('Uploaded MP4 was missing an ftyp box');
			}
			const track = await inspectMp4Moov(reader, box.payloadOffset, box.end);
			if (!track) {
				throw new MediaInspectionError('Uploaded MP4 did not contain a video track');
			}
			return { mediaType: 'video', contentType: 'video/mp4', width: track.width, height: track.height };
		}
		offset = box.end;
	}
	throw new MediaInspectionError('Uploaded MP4 was missing moov metadata');
}

async function inspectMp4Moov(
	reader: ByteReader,
	startOffset: number,
	endOffset: number
): Promise<{ width: number; height: number } | null> {
	let offset = startOffset;
	while (offset < endOffset) {
		const box = await readMp4Box(reader, offset, endOffset);
		if (!box) {
			break;
		}
		if (box.type === 'trak') {
			const track = await inspectMp4Track(reader, box.payloadOffset, box.end);
			if (track) {
				return track;
			}
		}
		offset = box.end;
	}
	return null;
}

async function inspectMp4Track(
	reader: ByteReader,
	startOffset: number,
	endOffset: number
): Promise<{ width: number; height: number } | null> {
	let offset = startOffset;
	let dimensions: { width: number; height: number } | null = null;
	let isVideoTrack = false;
	while (offset < endOffset) {
		const box = await readMp4Box(reader, offset, endOffset);
		if (!box) {
			break;
		}
		if (box.type === 'tkhd') {
			dimensions = await inspectMp4TrackHeader(reader, box);
		} else if (box.type === 'mdia') {
			isVideoTrack = await inspectMp4MediaBox(reader, box.payloadOffset, box.end);
		}
		offset = box.end;
	}

	if (!isVideoTrack || !dimensions) {
		return null;
	}
	if (dimensions.width <= 0 || dimensions.height <= 0) {
		throw new MediaInspectionError('Uploaded MP4 had invalid dimensions');
	}
	return dimensions;
}

async function inspectMp4MediaBox(reader: ByteReader, startOffset: number, endOffset: number): Promise<boolean> {
	let offset = startOffset;
	while (offset < endOffset) {
		const box = await readMp4Box(reader, offset, endOffset);
		if (!box) {
			break;
		}
		if (box.type === 'hdlr') {
			const payload = await readExact(reader, box.payloadOffset, Math.min(box.payloadSize, 12));
			if (payload.byteLength < 12) {
				throw new MediaInspectionError('Uploaded MP4 had a truncated handler box');
			}
			return readAscii(payload, 8, 4) === 'vide';
		}
		offset = box.end;
	}
	return false;
}

async function inspectMp4TrackHeader(
	reader: ByteReader,
	box: Mp4Box
): Promise<{ width: number; height: number }> {
	if (box.payloadSize < 84) {
		throw new MediaInspectionError('Uploaded MP4 had a truncated track header');
	}
	const version = (await readExact(reader, box.payloadOffset, 1))[0];
	const requiredLength = version === 1 ? 96 : 84;
	const payload = await readExact(reader, box.payloadOffset, Math.min(requiredLength, box.payloadSize));
	const widthOffset = version === 1 ? 88 : 76;
	const heightOffset = version === 1 ? 92 : 80;
	if (payload.byteLength < heightOffset + 4) {
		throw new MediaInspectionError('Uploaded MP4 had a truncated track header');
	}
	const width = Math.round(readUint32BE(payload, widthOffset) / 65536);
	const height = Math.round(readUint32BE(payload, heightOffset) / 65536);
	return { width, height };
}

async function looksLikeWebm(reader: ByteReader): Promise<boolean> {
	if (reader.size < 4) {
		return false;
	}
	const head = await readExact(reader, 0, 4);
	return readUint32BE(head, 0) === EBML_IDS.ebml;
}

async function inspectWebm(reader: ByteReader): Promise<DetectedMediaMetadata> {
	const ebmlHeader = await readEbmlElementHeader(reader, 0, reader.size);
	if (ebmlHeader.id !== EBML_IDS.ebml) {
		throw new MediaInspectionError('Uploaded WebM was missing an EBML header');
	}
	const docType = await readWebmDocType(reader, ebmlHeader);
	if (docType !== 'webm') {
		throw new MediaInspectionError('Uploaded WebM had an unexpected DocType');
	}

	let offset = ebmlHeader.end;
	while (offset < reader.size) {
		const element = await readEbmlElementHeader(reader, offset, reader.size);
		if (element.id === EBML_IDS.segment) {
			const track = await inspectWebmSegment(reader, element.dataOffset, element.dataEnd);
			if (!track) {
				throw new MediaInspectionError('Uploaded WebM did not contain a video track');
			}
			return { mediaType: 'video', contentType: 'video/webm', width: track.width, height: track.height };
		}
		offset = element.end;
	}

	throw new MediaInspectionError('Uploaded WebM was missing a Segment element');
}

async function readWebmDocType(reader: ByteReader, ebmlHeader: EbmlElementHeader): Promise<string | null> {
	let offset = ebmlHeader.dataOffset;
	while (offset < ebmlHeader.dataEnd) {
		const element = await readEbmlElementHeader(reader, offset, ebmlHeader.dataEnd);
		if (element.id === EBML_IDS.docType) {
			const value = await readExact(reader, element.dataOffset, element.dataSize);
			return TEXT_DECODER.decode(value);
		}
		offset = element.end;
	}
	return null;
}

async function inspectWebmSegment(
	reader: ByteReader,
	startOffset: number,
	endOffset: number
): Promise<{ width: number; height: number } | null> {
	let offset = startOffset;
	while (offset < endOffset) {
		const element = await readEbmlElementHeader(reader, offset, endOffset);
		if (element.id === EBML_IDS.tracks) {
			return inspectWebmTracks(reader, element.dataOffset, element.dataEnd);
		}
		offset = element.end;
	}
	return null;
}

async function inspectWebmTracks(
	reader: ByteReader,
	startOffset: number,
	endOffset: number
): Promise<{ width: number; height: number } | null> {
	let offset = startOffset;
	while (offset < endOffset) {
		const element = await readEbmlElementHeader(reader, offset, endOffset);
		if (element.id === EBML_IDS.trackEntry) {
			const entry = await inspectWebmTrackEntry(reader, element.dataOffset, element.dataEnd);
			if (entry) {
				return entry;
			}
		}
		offset = element.end;
	}
	return null;
}

async function inspectWebmTrackEntry(
	reader: ByteReader,
	startOffset: number,
	endOffset: number
): Promise<{ width: number; height: number } | null> {
	let offset = startOffset;
	let trackType: number | null = null;
	let dimensions: { width: number; height: number } | null = null;
	while (offset < endOffset) {
		const element = await readEbmlElementHeader(reader, offset, endOffset);
		if (element.id === EBML_IDS.trackType) {
			trackType = await readUnsignedElementValue(reader, element);
		} else if (element.id === EBML_IDS.video) {
			dimensions = await inspectWebmVideo(reader, element.dataOffset, element.dataEnd);
		}
		offset = element.end;
	}

	if (trackType !== 1 || !dimensions) {
		return null;
	}
	if (dimensions.width <= 0 || dimensions.height <= 0) {
		throw new MediaInspectionError('Uploaded WebM had invalid dimensions');
	}
	return dimensions;
}

async function inspectWebmVideo(
	reader: ByteReader,
	startOffset: number,
	endOffset: number
): Promise<{ width: number; height: number } | null> {
	let offset = startOffset;
	let width: number | null = null;
	let height: number | null = null;
	while (offset < endOffset) {
		const element = await readEbmlElementHeader(reader, offset, endOffset);
		if (element.id === EBML_IDS.pixelWidth) {
			width = await readUnsignedElementValue(reader, element);
		} else if (element.id === EBML_IDS.pixelHeight) {
			height = await readUnsignedElementValue(reader, element);
		}
		offset = element.end;
	}
	return width && height ? { width, height } : null;
}

async function readUnsignedElementValue(reader: ByteReader, element: EbmlElementHeader): Promise<number> {
	const value = await readExact(reader, element.dataOffset, element.dataSize);
	return readUnsignedInt(value, 0, value.byteLength);
}

type Mp4Box = {
	type: string;
	size: number;
	payloadSize: number;
	payloadOffset: number;
	end: number;
};

async function readMp4Box(reader: ByteReader, offset: number, endOffset: number): Promise<Mp4Box | null> {
	if (offset >= endOffset) {
		return null;
	}
	if (offset + 8 > endOffset) {
		throw new MediaInspectionError('Uploaded MP4 was truncated');
	}
	const header = await readExact(reader, offset, 8);
	const size32 = readUint32BE(header, 0);
	const type = readAscii(header, 4, 4);
	let size = size32;
	let headerSize = 8;

	if (size32 === 1) {
		const largeHeader = await readExact(reader, offset, 16);
		size = readUint64Number(largeHeader, 8);
		headerSize = 16;
	} else if (size32 === 0) {
		size = endOffset - offset;
	}

	if (size < headerSize || offset + size > endOffset) {
		throw new MediaInspectionError('Uploaded MP4 was truncated');
	}

	return {
		type,
		size,
		payloadSize: size - headerSize,
		payloadOffset: offset + headerSize,
		end: offset + size,
	};
}

type EbmlElementHeader = {
	id: number;
	idLength: number;
	dataOffset: number;
	dataSize: number;
	dataEnd: number;
	end: number;
};

async function readEbmlElementHeader(
	reader: ByteReader,
	offset: number,
	parentEnd: number
): Promise<EbmlElementHeader> {
	if (offset >= parentEnd) {
		throw new MediaInspectionError('Uploaded WebM was truncated');
	}
	const id = await readEbmlVint(reader, offset, 4, false);
	const size = await readEbmlVint(reader, offset + id.length, 8, true);
	const dataOffset = offset + id.length + size.length;
	const dataSize = size.unknown ? parentEnd - dataOffset : size.value;
	const dataEnd = size.unknown ? parentEnd : dataOffset + size.value;
	if (dataOffset > parentEnd || dataEnd > parentEnd) {
		throw new MediaInspectionError('Uploaded WebM was truncated');
	}
	return {
		id: id.value,
		idLength: id.length,
		dataOffset,
		dataSize,
		dataEnd,
		end: dataEnd,
	};
}

async function readEbmlVint(
	reader: ByteReader,
	offset: number,
	maxLength: number,
	removeMarkerBit: boolean
): Promise<{ length: number; value: number; unknown: boolean }> {
	const firstByte = (await readExact(reader, offset, 1))[0];
	let mask = 0x80;
	let length = 1;
	while (length <= maxLength && (firstByte & mask) === 0) {
		mask >>= 1;
		length += 1;
	}
	if (length > maxLength) {
		throw new MediaInspectionError('Uploaded WebM had an invalid variable-length integer');
	}
	const bytes = await readExact(reader, offset, length);
	let value = removeMarkerBit ? bytes[0] & (mask - 1) : bytes[0];
	for (let index = 1; index < bytes.byteLength; index++) {
		value = (value * 256) + bytes[index];
	}
	const unknown = removeMarkerBit && bytes.every((byte, index) => {
		if (index === 0) {
			return (byte & (mask - 1)) === (mask - 1);
		}
		return byte === 0xff;
	});
	return { length, value, unknown };
}

function isJpegStartOfFrameMarker(marker: number): boolean {
	return JPEG_START_OF_FRAME_MARKERS.has(marker);
}

async function readExact(reader: ByteReader, offset: number, length: number): Promise<Uint8Array> {
	const bytes = await reader.read(offset, length);
	if (bytes.byteLength !== length) {
		throw new MediaInspectionError('Uploaded media was truncated');
	}
	return bytes;
}

function clampOffset(offset: number, size: number): number {
	if (!Number.isFinite(offset) || offset < 0) {
		return 0;
	}
	return Math.min(Math.floor(offset), size);
}

function clampLength(length: number, available: number): number {
	if (!Number.isFinite(length) || length <= 0) {
		return 0;
	}
	return Math.min(Math.floor(length), available);
}

function matchesAt(bytes: Uint8Array, offset: number, expected: Uint8Array): boolean {
	if (offset + expected.byteLength > bytes.byteLength) {
		return false;
	}
	for (let index = 0; index < expected.byteLength; index++) {
		if (bytes[offset + index] !== expected[index]) {
			return false;
		}
	}
	return true;
}

function startsWithAscii(bytes: Uint8Array, expected: string): boolean {
	if (bytes.byteLength < expected.length) {
		return false;
	}
	return readAscii(bytes, 0, expected.length) === expected;
}

function readAscii(bytes: Uint8Array, offset: number, length: number): string {
	return TEXT_DECODER.decode(bytes.subarray(offset, offset + length));
}

function readUint16BE(bytes: Uint8Array, offset: number): number {
	return (bytes[offset] << 8) | bytes[offset + 1];
}

function readUint16LE(bytes: Uint8Array, offset: number): number {
	return bytes[offset] | (bytes[offset + 1] << 8);
}

function readUint32BE(bytes: Uint8Array, offset: number): number {
	return (((bytes[offset] * 256) + bytes[offset + 1]) * 256 + bytes[offset + 2]) * 256 + bytes[offset + 3];
}

function readUint64Number(bytes: Uint8Array, offset: number): number {
	const high = readUint32BE(bytes, offset);
	const low = readUint32BE(bytes, offset + 4);
	return (high * 2 ** 32) + low;
}

function readUnsignedInt(bytes: Uint8Array, offset: number, length: number): number {
	let value = 0;
	for (let index = 0; index < length; index++) {
		value = (value * 256) + bytes[offset + index];
	}
	return value;
}
