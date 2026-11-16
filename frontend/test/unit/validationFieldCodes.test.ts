import { afterEach, expect, it, vi } from 'vitest';
import { ApiError, register } from '@/app/lib/api';

afterEach(() => vi.unstubAllGlobals());

it.each([{ username: 'NotBlank' }, { username: 3 }, 'invalid', null])('reads only string constraint maps: %j', async (codes) => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
        code: 'VALIDATION_FAILED', message: 'Please fix the highlighted fields',
        fieldErrors: { username: 'Please enter a value' }, fieldErrorCodes: codes,
    }), { status: 400, headers: { 'Content-Type': 'application/json' } })));
    const error: unknown = await register({ username: '', email: '', displayName: '', password: '' }).catch((failure: unknown) => failure);
    expect(error).toBeInstanceOf(ApiError);
    if (!(error instanceof ApiError)) throw new Error('Expected validation failure');
    expect(error.fieldErrors).toEqual({ username: 'Please enter a value' });
    expect(error.fieldErrorCodes).toEqual(typeof codes === 'object' && codes !== null && typeof codes.username === 'string' ? codes : undefined);
});
