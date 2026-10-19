import { afterEach, describe, expect, it, vi } from 'vitest';

const startAuthentication = vi.hoisted(() => vi.fn(async () => ({
    id: 'credential-id', rawId: 'credential-id', type: 'public-key',
    response: { authenticatorData: 'authenticator-data', clientDataJSON: 'client-data', signature: 'signature' },
    clientExtensionResults: {}, authenticatorAttachment: 'platform',
})));
vi.mock('@simplewebauthn/browser', () => ({ startAuthentication }));

type Recorded = { url: string; method: string; body: unknown; csrf: string | null; workspace: string | null };
function json(body: unknown, status = 200) {
    return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}
function backend(respond: (url: string, attempt: number) => Response = () => json({})) {
    vi.stubGlobal('window', { addEventListener: vi.fn(), crypto: { randomUUID: () => 'event-id' },
        localStorage: { setItem: vi.fn() }, location: { pathname: '/ask-connex/7', search: '' } });
    vi.stubGlobal('document', { cookie: 'connex_workspace=5; NEXT_LOCALE=en' });
    const requests: Recorded[] = [];
    let attempts = 0;
    vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
        const url = input instanceof Request ? input.url : String(input);
        const headers = new Headers(init?.headers);
        const body: unknown = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined;
        requests.push({ url, method: init?.method ?? 'GET', body, csrf: headers.get('X-XSRF-TOKEN'), workspace: headers.get('X-Workspace-Id') });
        if (url.endsWith('/api/auth/csrf')) return json({ token: 'csrf', headerName: 'X-XSRF-TOKEN', requestIdentity: 'request-identity' });
        if (url.endsWith('/api/auth/webauthn/step-up/options')) return json({ challenge: 'challenge' });
        if (url.endsWith('/api/auth/webauthn/step-up')) return json({ user: { id: 7 } });
        return respond(url, ++attempts);
    }));
    return requests;
}

describe('Ask Connex mission API contracts', () => {
    afterEach(() => { vi.resetModules(); vi.unstubAllGlobals(); vi.clearAllMocks(); });

    it('preserves creation identity and decimal control versions through CSRF-protected requests', async () => {
        const requests = backend();
        const { createAiAgentRun, controlAiAgentRun } = await import('@/app/lib/api');
        const payload = { clientRequestKey: '2ec8b877-06f4-44a1-b315-e8ad20a61a8c', missionType: 'record_review_v1',
            goal: 'Review', scope: { kind: 'record', recordKind: 'person', recordId: 123 } } satisfies import('@/app/lib/types').AiAgentRunCreateRequest;
        await createAiAgentRun(7, payload);
        await controlAiAgentRun(12, 'pause', '9007199254740993');
        const mutations = requests.filter((item) => item.method === 'POST');
        expect(mutations).toHaveLength(2);
        expect(mutations[0]).toMatchObject({ url: '/api/ai/assistant/sessions/7/missions', body: payload, csrf: 'csrf', workspace: '5' });
        expect(mutations[1]).toMatchObject({ url: '/api/ai/assistant/missions/12/pause', body: { expectedStateVersion: '9007199254740993' }, csrf: 'csrf' });
    });

    it('pins mission writes to the rendered workspace while organization purge follows the cookie', async () => {
        const requests = backend();
        const { createAiAgentRun, controlAiAgentRun, purgeAiAgentHoldings } = await import('@/app/lib/api');
        const { bindRenderedWorkspace, releaseRenderedWorkspace } = await import('@/app/lib/renderedWorkspace');
        const shell = {};
        bindRenderedWorkspace(shell, 9);
        try {
            await createAiAgentRun(7, { clientRequestKey: crypto.randomUUID(), missionType: 'record_review_v1',
                goal: 'Review', scope: { kind: 'record', recordKind: 'person', recordId: 123 } });
            for (const action of ['pause', 'resume', 'cancel'] as const) await controlAiAgentRun(12, action, '1');
            await purgeAiAgentHoldings(3, 41, { idempotencyKey: crypto.randomUUID(), snapshotToken: 'dsr1.snapshot',
                scopeAcknowledgement: 'organization_mission_holdings_v1',
                cutoffPolicyAcknowledgement: 'contents_at_each_workspace_first_invalidation_v1' });
            const mutations = requests.filter((item) => item.method === 'POST');
            expect(mutations.map((item) => item.workspace)).toEqual(['9', '9', '9', '9', '5']);
        } finally {
            releaseRenderedWorkspace(shell);
        }
    });

    it('reports mission workspace refusal without replaying against a healed cookie', async () => {
        const requests = backend(() => json({ code: 'FORBIDDEN', message: 'Not a member of workspace 9' }, 403));
        const { createAiAgentRun, isWorkspaceUnavailableError } = await import('@/app/lib/api');
        const { bindRenderedWorkspace, releaseRenderedWorkspace } = await import('@/app/lib/renderedWorkspace');
        const shell = {};
        bindRenderedWorkspace(shell, 9);
        try {
            const failure: unknown = await createAiAgentRun(7, { clientRequestKey: crypto.randomUUID(),
                missionType: 'record_review_v1', goal: 'Review',
                scope: { kind: 'record', recordKind: 'person', recordId: 123 } }).catch((error: unknown) => error);
            expect(isWorkspaceUnavailableError(failure)).toBe(true);
            expect(requests.filter((item) => item.method === 'POST')).toEqual([
                expect.objectContaining({ url: '/api/ai/assistant/sessions/7/missions', workspace: '9' }),
            ]);
        } finally {
            releaseRenderedWorkspace(shell);
        }
    });

    it('loads list, detail and output independently of transcript and tool limits', async () => {
        const requests = backend();
        const { getAiAgentRuns, getAiAgentRun, getAiAgentOutput } = await import('@/app/lib/api');
        await getAiAgentRuns({ sessionId: 7, limit: 20, cursor: 'opaque+/=' });
        await getAiAgentRun(12);
        await getAiAgentOutput(12);
        expect(requests.map((item) => item.url)).toEqual([
            '/api/ai/assistant/missions?sessionId=7&limit=20&cursor=opaque%2B%2F%3D',
            '/api/ai/assistant/missions/12', '/api/ai/assistant/missions/12/output',
        ]);
        expect(requests.every((item) => item.method === 'GET')).toBe(true);
    });

    it('keeps the exact DSR acknowledgement and idempotency key through recent-authentication replay', async () => {
        const requests = backend((url, attempt) => url.endsWith('/mission-holdings-purge') && attempt === 1
            ? json({ code: 'RECENT_AUTHENTICATION_REQUIRED', message: 'Recent WebAuthn authentication required' }, 403)
            : json({ operationId: 'accepted-operation' }));
        const { purgeAiAgentHoldings, getAiAgentHoldingsPurge } = await import('@/app/lib/api');
        const body = { idempotencyKey: 'ba164b3b-bb5c-498c-a1bc-4552dcb7249a', snapshotToken: 'dsr1.snapshot',
            scopeAcknowledgement: 'organization_mission_holdings_v1',
            cutoffPolicyAcknowledgement: 'contents_at_each_workspace_first_invalidation_v1' } satisfies import('@/app/lib/types').AiAgentHoldingsPurgeRequest;
        await purgeAiAgentHoldings(3, 41, body);
        await getAiAgentHoldingsPurge(3, 41, 'accepted-operation');
        expect(startAuthentication).toHaveBeenCalledOnce();
        const accepted = requests.filter((item) => item.url.endsWith('/mission-holdings-purge'));
        expect(accepted).toHaveLength(2);
        expect(accepted.every((item) => item.method === 'POST' && item.csrf === 'csrf')).toBe(true);
        expect(accepted.map((item) => item.body)).toEqual([body, body]);
        expect(requests.at(-1)).toMatchObject({ method: 'GET', url: '/api/orgs/3/data-subject-requests/41/mission-holdings-purges/accepted-operation' });
    });
});
