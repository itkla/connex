import { beforeEach, describe, expect, it, vi } from 'vitest';

const api = vi.hoisted(() => ({ user: vi.fn(), workspaces: vi.fn(), headers: vi.fn() }));
vi.mock('next/headers', () => ({ headers: api.headers }));
vi.mock('@/app/lib/api', () => ({
    getCurrentUserResultFromCookie: api.user,
    getMyWorkspacesResultFromCookie: api.workspaces,
}));
import { requestPreferences, requestTimeZone } from '@/app/lib/requestTimeZone';

beforeEach(() => {
    vi.clearAllMocks();
    api.headers.mockResolvedValue(new Headers({ cookie: 'JSESSIONID=test; connex_workspace=2' }));
    api.user.mockResolvedValue({ ok: true, data: { locale: 'en', timezone: 'Asia/Tokyo' } });
    api.workspaces.mockResolvedValue({ ok: true, data: { activeWorkspaceId: 2, workspaces: [
        { id: 1, timezone: 'Pacific/Honolulu' }, { id: 2, timezone: 'America/New_York' },
    ] } });
});

describe('request time zone', () => {
    it('uses the authenticated profile without fetching workspaces', async () => {
        expect(await requestTimeZone()).toBe('Asia/Tokyo');
        expect(api.user).toHaveBeenCalledWith('JSESSIONID=test; connex_workspace=2', 'en');
        expect(api.workspaces).not.toHaveBeenCalled();
    });
    it('uses only the active workspace when the profile zone is invalid', async () => {
        api.user.mockResolvedValue({ ok: true, data: { timezone: 'invalid' } });
        expect(await requestTimeZone()).toBe('America/New_York');
    });
    it.each([{ ok: false }, { ok: true, data: null }])('falls back to UTC for an unavailable or expired session', async (result) => {
        api.user.mockResolvedValue(result);
        expect(await requestTimeZone()).toBe('UTC');
        expect(api.workspaces).not.toHaveBeenCalled();
    });
    it('keeps public pages independent of backend availability without a session', async () => {
        api.headers.mockResolvedValue(new Headers({ cookie: 'NEXT_LOCALE=ja' }));
        expect(await requestTimeZone()).toBe('UTC');
        expect(api.user).not.toHaveBeenCalled();
    });
    it('uses UTC when workspace resolution is unavailable', async () => {
        api.user.mockResolvedValue({ ok: true, data: { timezone: '' } });
        api.workspaces.mockResolvedValue({ ok: false });
        expect(await requestTimeZone()).toBe('UTC');
    });
    it('shares one profile read for language and time zone', async () => {
        expect(await requestPreferences()).toEqual({ locale: 'en', timeZone: 'Asia/Tokyo' });
        expect(api.user).toHaveBeenCalledTimes(1);
        expect(api.workspaces).not.toHaveBeenCalled();
    });
    it('keeps the language preference when the workspace zone is needed', async () => {
        api.user.mockResolvedValue({ ok: true, data: { locale: 'ja', timezone: 'invalid' } });
        expect(await requestPreferences()).toEqual({ locale: 'ja', timeZone: 'America/New_York' });
        expect(api.user).toHaveBeenCalledTimes(1);
        expect(api.workspaces).toHaveBeenCalledTimes(1);
    });
    it('falls back safely when the profile read rejects', async () => {
        api.user.mockRejectedValue(new TypeError('Profile unavailable'));
        expect(await requestPreferences()).toEqual({ locale: null, timeZone: 'UTC' });
        expect(api.workspaces).not.toHaveBeenCalled();
    });
    it('keeps the profile language when the workspace read rejects', async () => {
        api.user.mockResolvedValue({ ok: true, data: { locale: 'en', timezone: '' } });
        api.workspaces.mockRejectedValue(new TypeError('Workspaces unavailable'));
        expect(await requestPreferences()).toEqual({ locale: 'en', timeZone: 'UTC' });
    });
    it('keeps an explicit cookie choice on profile and workspace bootstrap requests', async () => {
        const cookie = 'JSESSIONID=test; NEXT_LOCALE=en';
        api.headers.mockResolvedValue(new Headers({ cookie, 'accept-language': 'ja-JP' }));
        api.user.mockResolvedValue({ ok: true, data: { locale: 'ja', timezone: 'invalid' } });
        expect(await requestPreferences()).toEqual({ locale: 'ja', timeZone: 'America/New_York' });
        expect(api.user).toHaveBeenCalledExactlyOnceWith(cookie, 'en');
        expect(api.workspaces).toHaveBeenCalledExactlyOnceWith(cookie, 'en');
    });
});
