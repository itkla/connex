import type { AuthenticationResponseJSON } from '@simplewebauthn/browser';
import { afterEach, describe, expect, it, vi } from 'vitest';

const authenticate = vi.hoisted(() => vi.fn(async (): Promise<AuthenticationResponseJSON> => ({
    id: 'credential', rawId: 'credential', type: 'public-key', clientExtensionResults: {},
    response: { authenticatorData: 'data', clientDataJSON: 'data', signature: 'signature' },
})));
vi.mock('@simplewebauthn/browser', () => ({ startAuthentication: authenticate }));

function json(value: unknown, status = 200): Response {
    return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
}

function storage() {
    const values = new Map<string, string>();
    return {
        get length() { return values.size; },
        key: (index: number) => [...values.keys()][index] ?? null,
        getItem: (key: string) => values.get(key) ?? null,
        setItem: (key: string, value: string) => { values.set(key, value); },
        removeItem: (key: string) => { values.delete(key); },
    };
}

type Sent = { path: string; method: string; headers: Headers; body: unknown };
const releases: Array<() => void> = [];

async function browser(answer: (call: Sent) => Response = () => new Response(null, { status: 204 })) {
    const events: string[] = [];
    const sent: Sent[] = [];
    const click = vi.fn(() => { events.push('download'); });
    const anchor = { href: '', download: '', click, remove: vi.fn() };
    vi.stubGlobal('window', {
        location: { pathname: '/insights/reports/14', search: '' },
        addEventListener: vi.fn(), crypto: { randomUUID: () => 'identity-event' },
        localStorage: storage(), sessionStorage: storage(),
    });
    vi.stubGlobal('document', {
        cookie: 'connex_workspace=5; NEXT_LOCALE=ja',
        createElement: () => anchor, body: { appendChild: vi.fn() },
    });
    vi.stubGlobal('URL', class extends URL {
        static createObjectURL() { return 'blob:report'; }
        static revokeObjectURL() { return undefined; }
    });
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
        const path = new URL(String(input), 'https://connex.test').pathname;
        if (path === '/api/auth/csrf') return json({ token: 'csrf', headerName: 'X-XSRF-TOKEN', requestIdentity: 'identity-1' });
        const body: unknown = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined;
        const call = { path, method: init?.method ?? 'GET', headers: new Headers(init?.headers), body };
        sent.push(call);
        if (path.endsWith('/export-completion')) events.push('completion');
        return answer(call);
    }));
    const api = await import('@/app/lib/api');
    const rendered = await import('@/app/lib/renderedWorkspace');
    const owner = {};
    rendered.bindRenderedWorkspace(owner, 9);
    releases.push(() => rendered.releaseRenderedWorkspace(owner));
    api.synchronizeBrowserAccount(1);
    return { api, events, sent, click, anchor };
}

afterEach(() => {
    releases.splice(0).forEach((release) => release());
    vi.resetModules();
    vi.unstubAllGlobals();
    vi.clearAllMocks();
});

describe('report snapshot CSV release evidence', () => {
    it('sends one explicit POST with CSRF and the rendered workspace before downloading once', async () => {
        const { api, sent, click, anchor } = await browser(() => new Response('source_id,widget_id\r\n'));

        await api.exportReportSnapshotCsv(14, 3);

        expect(sent).toHaveLength(1);
        expect(sent[0].path).toBe('/api/reports/14/snapshots/3/export.csv');
        expect(sent[0].method).toBe('POST');
        expect(sent[0].headers.get('X-Workspace-Id')).toBe('9');
        expect(sent[0].headers.get('X-XSRF-TOKEN')).toBe('csrf');
        expect(click).toHaveBeenCalledOnce();
        expect(anchor.download).toBe('report-14-snapshot-3.csv');
    });

    it('retries only after required passkey verification and releases the successful CSV once', async () => {
        let attempts = 0;
        const { api, sent, click } = await browser(({ path }) => {
            if (path.endsWith('/options')) return json({ challenge: 'challenge' });
            if (path === '/api/auth/webauthn/step-up') return json({ user: { id: 7 } });
            attempts += 1;
            return attempts === 1 ? json({ code: 'RECENT_AUTHENTICATION_REQUIRED' }, 403) : new Response('csv');
        });

        await api.exportReportSnapshotCsv(14, 3);

        const exports = sent.filter(({ path }) => path.endsWith('/export.csv'));
        expect(exports).toHaveLength(2);
        for (const request of exports) {
            expect(request.method).toBe('POST');
            expect(request.headers.get('X-Workspace-Id')).toBe('9');
            expect(request.headers.get('X-XSRF-TOKEN')).toBe('csrf');
        }
        expect(authenticate).toHaveBeenCalledOnce();
        expect(click).toHaveBeenCalledOnce();
    });

    it('does not download a snapshot CSV when authorization fails', async () => {
        const { api, sent, click } = await browser(() => json({ code: 'FORBIDDEN' }, 403));

        await expect(api.exportReportSnapshotCsv(14, 3)).rejects.toMatchObject({ status: 403 });

        expect(sent).toHaveLength(1);
        expect(click).not.toHaveBeenCalled();
    });
});

describe('report PNG release evidence', () => {
    it('assembles the image, sends only the format under the rendered workspace, then downloads once', async () => {
        const { api, events, sent, click, anchor } = await browser();
        await api.exportReportPng(14, async () => {
            events.push('prepare');
            return new Blob(['image'], { type: 'image/png' });
        }, 'private-report-name.png');
        expect(events).toEqual(['prepare', 'completion', 'download']);
        expect(sent).toHaveLength(1);
        expect(sent[0].path).toBe('/api/reports/14/export-completion');
        expect(sent[0].body).toEqual({ format: 'png' });
        expect(sent[0].headers.get('X-Workspace-Id')).toBe('9');
        expect(sent[0].headers.get('X-XSRF-TOKEN')).toBe('csrf');
        expect(click).toHaveBeenCalledOnce();
        expect(anchor.download).toBe('private-report-name.png');
    });

    it('records nothing and downloads nothing when image assembly fails', async () => {
        const { api, sent, click } = await browser();
        await expect(api.exportReportPng(14, async () => { throw new Error('Canvas failed'); })).rejects.toThrow('Canvas failed');
        expect(sent).toEqual([]);
        expect(click).not.toHaveBeenCalled();
    });

    it('does not release the prepared image when the server refuses completion', async () => {
        const { api, sent, click } = await browser(() => json({ code: 'FORBIDDEN' }, 403));
        await expect(api.exportReportPng(14, async () => new Blob(['image']))).rejects.toMatchObject({ status: 403 });
        expect(sent).toHaveLength(1);
        expect(click).not.toHaveBeenCalled();
    });

    it('abandons the image before recording when the account changes during assembly', async () => {
        const { api, sent, click } = await browser();
        let releaseImage: (blob: Blob) => void = () => undefined;
        let started: () => void = () => undefined;
        const ready = new Promise<void>((resolve) => { started = resolve; });
        const image = new Promise<Blob>((resolve) => { releaseImage = resolve; });
        const pending = api.exportReportPng(14, () => { started(); return image; }).catch((error: unknown) => error);
        await ready;
        api.synchronizeBrowserAccount(2);
        releaseImage(new Blob(['image']));
        expect(await pending).toBeInstanceOf(Error);
        expect(sent).toEqual([]);
        expect(click).not.toHaveBeenCalled();
    });

    it('uses the existing passkey step-up and records a single successful PNG release', async () => {
        let attempts = 0;
        const { api, sent, click } = await browser(({ path }) => {
            if (path.endsWith('/options')) return json({ challenge: 'challenge' });
            if (path === '/api/auth/webauthn/step-up') return json({ user: { id: 7 } });
            attempts += 1;
            return attempts === 1 ? json({ code: 'RECENT_AUTHENTICATION_REQUIRED' }, 403) : new Response(null, { status: 204 });
        });
        const prepare = vi.fn(async () => new Blob(['image']));
        await api.exportReportPng(14, prepare);
        expect(prepare).toHaveBeenCalledOnce();
        expect(authenticate).toHaveBeenCalledOnce();
        expect(sent.filter(({ path }) => path.endsWith('/export-completion')).map(({ body }) => body)).toEqual([{ format: 'png' }, { format: 'png' }]);
        expect(click).toHaveBeenCalledOnce();
    });

    it('does not release an image when the required passkey step-up is cancelled', async () => {
        const { api, sent, click } = await browser(({ path }) => path.endsWith('/options')
            ? json({ challenge: 'challenge' }) : json({ code: 'RECENT_AUTHENTICATION_REQUIRED' }, 403));
        authenticate.mockRejectedValueOnce(new DOMException('Passkey cancelled', 'NotAllowedError'));
        const prepare = vi.fn(async () => new Blob(['image']));
        await expect(api.exportReportPng(14, prepare)).rejects.toMatchObject({ code: 'PASSKEY_STEP_UP_CANCELED' });
        expect(prepare).toHaveBeenCalledOnce();
        expect(authenticate).toHaveBeenCalledOnce();
        expect(sent.filter(({ path }) => path.endsWith('/export-completion'))).toHaveLength(1);
        expect(click).not.toHaveBeenCalled();
    });
});
