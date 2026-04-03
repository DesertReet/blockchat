import { describe, it, expect } from 'vitest';
import { MediaInspectionError, inspectMediaBytes } from '../src/media/inspect';

function makeMinimalPng(width: number, height: number): Uint8Array {
	return Uint8Array.from([
		0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
		0x00, 0x00, 0x00, 0x0d,
		0x49, 0x48, 0x44, 0x52,
		(width >>> 24) & 0xff, (width >>> 16) & 0xff, (width >>> 8) & 0xff, width & 0xff,
		(height >>> 24) & 0xff, (height >>> 16) & 0xff, (height >>> 8) & 0xff, height & 0xff,
		0x08, 0x02, 0x00, 0x00, 0x00,
		0x00, 0x00, 0x00, 0x00,
		0x00, 0x00, 0x00, 0x00,
		0x49, 0x45, 0x4e, 0x44,
		0xae, 0x42, 0x60, 0x82,
	]);
}

function makeMinimalGif(width: number, height: number): Uint8Array {
	return Uint8Array.from([
		0x47, 0x49, 0x46, 0x38, 0x39, 0x61,
		width & 0xff, (width >>> 8) & 0xff,
		height & 0xff, (height >>> 8) & 0xff,
		0x00, 0x00, 0x00,
		0x3b,
	]);
}

function makeMinimalJpeg(width: number, height: number): Uint8Array {
	return Uint8Array.from([
		0xff, 0xd8,
		0xff, 0xe0, 0x00, 0x10,
		0x4a, 0x46, 0x49, 0x46, 0x00,
		0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
		0xff, 0xc0, 0x00, 0x11,
		0x08,
		(height >>> 8) & 0xff, height & 0xff,
		(width >>> 8) & 0xff, width & 0xff,
		0x03,
		0x01, 0x11, 0x00,
		0x02, 0x11, 0x00,
		0x03, 0x11, 0x00,
		0xff, 0xd9,
	]);
}

describe('media inspection', () => {
	it('detects PNG dimensions from bytes', async () => {
		await expect(inspectMediaBytes(makeMinimalPng(4, 5))).resolves.toEqual({
			mediaType: 'image',
			contentType: 'image/png',
			width: 4,
			height: 5,
		});
	});

	it('detects GIF dimensions from bytes', async () => {
		await expect(inspectMediaBytes(makeMinimalGif(7, 3))).resolves.toEqual({
			mediaType: 'image',
			contentType: 'image/gif',
			width: 7,
			height: 3,
		});
	});

	it('detects JPEG dimensions from bytes', async () => {
		await expect(inspectMediaBytes(makeMinimalJpeg(9, 6))).resolves.toEqual({
			mediaType: 'image',
			contentType: 'image/jpeg',
			width: 9,
			height: 6,
		});
	});

	it('rejects truncated media', async () => {
		await expect(inspectMediaBytes(makeMinimalPng(1, 1).subarray(0, 20))).rejects.toBeInstanceOf(MediaInspectionError);
	});
});
