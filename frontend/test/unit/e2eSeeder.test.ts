import { describe, expect, it, vi } from 'vitest';
import { seeder } from '@/test/e2e/support/api';

const csrf = { headerName: 'X-CSRF-TOKEN', token: 'fixture-csrf' };
const path = '/api/deals/12/people/34?role=Champion';

function fixtureApi(status: number, body: string) {
    const post = vi.fn<Parameters<typeof seeder>[0]['post']>().mockResolvedValue({
        ok: () => status >= 200 && status < 300,
        status: () => status,
        text: async () => body,
    });
    return { post };
}

describe('E2E workspace seeder response contracts', () => {
    it('reads a created record and keeps workspace and CSRF headers', async () => {
        const api = fixtureApi(201, '{"id":12,"name":"Deal"}');

        await expect(seeder(api, 7, csrf).post('/api/deals', { name: 'Deal' }))
            .resolves.toEqual({ id: 12, name: 'Deal' });
        expect(api.post).toHaveBeenCalledWith('/api/deals', {
            timeout: 120_000,
            headers: { 'X-Workspace-Id': '7', 'X-CSRF-TOKEN': csrf.token },
            data: { name: 'Deal' },
        });
    });

    it.each([200, 204])('accepts a successful empty HTTP %s response for a void endpoint', async (status) => {
        const api = fixtureApi(status, '');

        await expect(seeder(api, 7, csrf).postEmpty(path, {})).resolves.toBeUndefined();
        expect(api.post).toHaveBeenCalledWith(path, expect.objectContaining({
            headers: { 'X-Workspace-Id': '7', 'X-CSRF-TOKEN': csrf.token },
            data: {},
        }));
    });

    it.each([200, 204])('reports an unexpected empty HTTP %s JSON response', async (status) => {
        await expect(seeder(fixtureApi(status, ''), 7, csrf).post('/api/deals', {}))
            .rejects.toThrow(`POST /api/deals returned HTTP ${status}: <empty body>`);
    });

    it.each([400, 403, 500])('reports HTTP %s and the body before parsing either response type', async (status) => {
        const seed = seeder(fixtureApi(status, 'CSRF or permission failure'), 7, csrf);
        const diagnostic = `POST ${path} returned HTTP ${status}: CSRF or permission failure`;

        await expect(seed.post(path, {})).rejects.toThrow(diagnostic);
        await expect(seed.postEmpty(path, {})).rejects.toThrow(diagnostic);
    });

    it.each(['not-json', 'null', '[]', '12'])('rejects a non-object JSON fixture response: %s', async (body) => {
        await expect(seeder(fixtureApi(200, body), 7, csrf).post('/api/deals', {}))
            .rejects.toThrow(`Expected a JSON object. POST /api/deals returned HTTP 200: ${body}`);
    });

    it('refuses a body from an endpoint declared to return no content', async () => {
        await expect(seeder(fixtureApi(200, '{"id":12}'), 7, csrf).postEmpty(path, {}))
            .rejects.toThrow(`Expected an empty response. POST ${path} returned HTTP 200: {"id":12}`);
    });
});
