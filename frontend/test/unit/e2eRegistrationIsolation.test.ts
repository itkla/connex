import type { APIRequestContext } from '@playwright/test';
import { describe, expect, it, vi } from 'vitest';
import { registerUser } from '@/test/e2e/support/api';

const credentials = { username: 'registration-fixture', password: 'Fixture-passphrase-A1!', email: 'fixture@example.test' };

describe('E2E registration session isolation', () => {
    it.each([200, 403, 500])('refuses existing or indeterminate session status %s before replacing it', async (status) => {
        const get = vi.fn<(...args: Parameters<APIRequestContext['get']>) => Promise<{ status: () => number }>>()
            .mockResolvedValue({ status: () => status });
        const post = vi.fn<(...args: Parameters<APIRequestContext['post']>) => Promise<{ status: () => number; text: () => Promise<string> }>>()
            .mockResolvedValue({ status: () => 200, text: async () => '{}' });

        await expect(registerUser({ get, post }, credentials)).rejects.toThrow('fresh unauthenticated context');

        expect(get).toHaveBeenCalledWith('/api/auth/me', { timeout: 120_000 });
        expect(post).not.toHaveBeenCalled();
    });

    it('registers only after the server confirms an unauthenticated context', async () => {
        const get = vi.fn<(...args: Parameters<APIRequestContext['get']>) => Promise<{ status: () => number }>>()
            .mockResolvedValue({ status: () => 401 });
        const post = vi.fn<(...args: Parameters<APIRequestContext['post']>) => Promise<{ status: () => number; text: () => Promise<string> }>>()
            .mockResolvedValue({ status: () => 200, text: async () => '{}' });

        await registerUser({ get, post }, credentials);

        expect(get.mock.invocationCallOrder[0]).toBeLessThan(post.mock.invocationCallOrder[0]);
        expect(post).toHaveBeenCalledWith('/api/auth/register', expect.objectContaining({
            data: expect.objectContaining({ username: credentials.username }),
        }));
    });

    it('does not register when the session probe fails', async () => {
        const get = vi.fn<(...args: Parameters<APIRequestContext['get']>) => Promise<{ status: () => number }>>()
            .mockRejectedValue(new Error('Network unavailable'));
        const post = vi.fn<(...args: Parameters<APIRequestContext['post']>) => Promise<{ status: () => number; text: () => Promise<string> }>>()
            .mockResolvedValue({ status: () => 200, text: async () => '{}' });

        await expect(registerUser({ get, post }, credentials)).rejects.toThrow('Network unavailable');

        expect(post).not.toHaveBeenCalled();
    });
});
