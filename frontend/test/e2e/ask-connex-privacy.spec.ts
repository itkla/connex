import { expect, test } from '@playwright/test';

import type { AiChatPrivacyFrame, AiChatRealtimeFrame, AiChatSessionDetail } from '../../app/lib/types';
import { activeWorkspaceId, csrfBootstrap } from './support/api';
import { useLocale } from './support/locale';
import { message } from './support/messages';

function positiveId(value: unknown): number {
    if (typeof value !== 'object' || value === null || !('id' in value)
            || typeof value.id !== 'number' || !Number.isSafeInteger(value.id) || value.id <= 0) {
        throw new Error('Expected a positive server identity');
    }
    return value.id;
}

for (const locale of ['en', 'ja'] as const) {
    test(`privacy withdrawal clears an open chat and refuses a late response in ${locale}`, async ({ page }) => {
        await useLocale(page, locale);
        const workspaceId = await activeWorkspaceId(page.request);
        const csrf = await csrfBootstrap(page.request);
        const user = await page.request.get('/api/auth/me');
        expect(user.ok()).toBe(true);
        const userId = positiveId(await user.json());
        const create = async () => {
            const response = await page.request.post('/api/ai/assistant/sessions', {
                headers: { 'X-Workspace-Id': String(workspaceId), [csrf.headerName]: csrf.token },
                data: { title: 'Privacy browser fixture', autoTitle: false },
            });
            expect(response.status()).toBe(201);
            return positiveId(await response.json());
        };
        const sessionId = await create();
        let send: ((frame: AiChatRealtimeFrame | AiChatPrivacyFrame) => void) | null = null;
        await page.routeWebSocket(/\/api\/ws(?:\?.*)?$/, (socket) => {
            socket.onMessage((data) => {
                const frame = data.toString();
                if (frame.startsWith('CONNECT\n') || frame.startsWith('STOMP\n')) {
                    socket.send('CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0');
                }
                if (frame.startsWith('SUBSCRIBE\n') && frame.includes('destination:/user/queue/ai-chat')) {
                    const subscription = /^id:(.+)$/m.exec(frame)?.[1];
                    if (!subscription) throw new Error('Missing chat subscription');
                    send = (value) => socket.send(`MESSAGE\nsubscription:${subscription}\nmessage-id:${crypto.randomUUID()}\ndestination:/user/queue/ai-chat\ncontent-type:application/json\n\n${JSON.stringify(value)}\0`);
                }
            });
        });
        const detail: AiChatSessionDetail = {
            session: { id: sessionId, workspaceId, createdByUserId: userId, organizationPrivacyGeneration: '1',
                title: 'Privacy browser fixture', visibility: 'private', status: 'active', archived: false,
                ownedByCurrentUser: true, historySummarized: false, participationStatus: null,
                lastMessageAt: null, archivedAt: null, createdAt: '2026-10-19T00:00:00Z', updatedAt: '2026-10-19T00:00:00Z' },
            messages: { items: [{ id: 1, sessionId, seq: 1, authorKind: 'assistant', authorUserId: null,
                authorDisplayName: null, content: 'Retained private browser text', createdAt: '2026-10-19T00:00:00Z' }], total: 1 },
            activeTurn: null,
        };
        let suspend = false;
        const reached = Promise.withResolvers<void>();
        const release = Promise.withResolvers<void>();
        await page.route(new RegExp(`/api/ai/assistant/sessions/${sessionId}(?:\\?.*)?$`), async (route) => {
            if (suspend) {
                reached.resolve();
                await release.promise;
            }
            await route.fulfill({ json: detail });
        });
        await page.goto(`/ask-connex/${sessionId}`);
        await expect(page.getByText('Retained private browser text', { exact: true })).toBeVisible();
        await expect.poll(() => send !== null).toBe(true);
        const emit = (frame: AiChatRealtimeFrame | AiChatPrivacyFrame) => {
            if (send === null) throw new Error('Chat socket is unavailable');
            send(frame);
        };
        suspend = true;
        emit({ workspaceId, sessionId, turnId: 99, kind: 'step', seq: 1,
            tool: null, status: 'running', reason: null });
        await reached.promise;
        try {
            emit({ workspaceId, kind: 'privacy_invalidated', cutoffGeneration: '1' });
            await expect(page.getByText('Retained private browser text', { exact: true })).toHaveCount(0);
            const unavailable = message(locale, 'common', 'AskConnex.privacyUnavailable');
            await expect(page.getByText(unavailable, { exact: true })).toBeVisible();
            release.resolve();
            await expect(page.getByText('Retained private browser text', { exact: true })).toHaveCount(0);
            await page.goto(`/ask-connex/${await create()}`);
            await expect(page.getByText(unavailable, { exact: true })).toHaveCount(0);
        } finally {
            release.resolve();
        }
    });
}
