type TestModeEnv = Pick<Env, 'TEST_MODE'> | null | undefined;
type LogFields = Record<string, unknown>;
const STACK_PREVIEW_MAX_LINES = 6;
const STACK_PREVIEW_MAX_CHARS = 640;
const GENERIC_ERROR_FALLBACK = '<no error message>';

export function isTestMode(env: TestModeEnv): boolean {
	return String(env?.TEST_MODE ?? '') === '1';
}

export function testLog(env: TestModeEnv, scope: string, event: string, fields?: LogFields): void {
	if (!isTestMode(env)) {
		return;
	}

	const sanitized = sanitizeFields(fields);
	if (sanitized) {
		console.log(`[blockchat:test] ${scope} ${event}`, sanitized);
		return;
	}
	console.log(`[blockchat:test] ${scope} ${event}`);
}

export function testErrorLog(env: TestModeEnv, scope: string, event: string, fields?: LogFields): void {
	if (!isTestMode(env)) {
		return;
	}

	const sanitized = sanitizeFields(fields);
	if (sanitized) {
		console.error(`[blockchat:test] ${scope} ${event}`, sanitized);
		return;
	}
	console.error(`[blockchat:test] ${scope} ${event}`);
}

export function errorSummary(error: unknown): LogFields {
	if (error instanceof Error) {
		const summary: LogFields = {
			name: error.name || 'Error',
			message: firstNonBlank(error.message, error.toString(), GENERIC_ERROR_FALLBACK),
		};
		const stackPreview = stackPreviewFor(error.stack);
		if (stackPreview) {
			summary.stack_preview = stackPreview;
		}
		const causeSummary = causePreviewFor(error);
		if (causeSummary) {
			summary.cause = causeSummary;
		}
		return summary;
	}
	if (typeof error === 'object' && error !== null) {
		try {
			return {
				message: truncate(JSON.stringify(error), STACK_PREVIEW_MAX_CHARS),
			};
		} catch {
			return {
				message: Object.prototype.toString.call(error),
			};
		}
	}
	return {
		message: firstNonBlank(String(error), GENERIC_ERROR_FALLBACK),
	};
}

function sanitizeFields(fields?: LogFields): LogFields | null {
	if (!fields) {
		return null;
	}
	const entries = Object.entries(fields).filter(([, value]) => value !== undefined);
	if (entries.length === 0) {
		return null;
	}
	return Object.fromEntries(entries);
}

function stackPreviewFor(stack?: string): string | null {
	if (!stack) {
		return null;
	}
	const lines = stack
		.split('\n')
		.map((line) => line.trim())
		.filter((line) => line.length > 0)
		.slice(0, STACK_PREVIEW_MAX_LINES);
	if (lines.length === 0) {
		return null;
	}
	return truncate(lines.join(' | '), STACK_PREVIEW_MAX_CHARS);
}

function causePreviewFor(error: Error): string | null {
	const cause = error.cause;
	if (cause == null) {
		return null;
	}
	if (cause instanceof Error) {
		const name = firstNonBlank(cause.name, 'Error');
		const message = firstNonBlank(cause.message, cause.toString(), GENERIC_ERROR_FALLBACK);
		return `${name}: ${message}`;
	}
	return truncate(String(cause), STACK_PREVIEW_MAX_CHARS);
}

function truncate(text: string, max: number): string {
	if (text.length <= max) {
		return text;
	}
	return `${text.slice(0, Math.max(0, max - 3))}...`;
}

function firstNonBlank(...values: Array<string | null | undefined>): string {
	for (const value of values) {
		if (value != null && value.trim().length > 0) {
			return value;
		}
	}
	return '';
}
