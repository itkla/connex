import { expect, test } from '@playwright/test';

import type { AiAssistantToolCall, AiChatSessionDetail } from '../../app/lib/types';
import { activeWorkspaceId, csrfBootstrap } from './support/api';
import { runFixture } from './support/fixtures';
import { message } from './support/messages';

function requiredId(value: unknown): number {
    if (typeof value !== 'object' || value === null || !('id' in value)
        || typeof value.id !== 'number' || !Number.isSafeInteger(value.id) || value.id <= 0) {
        throw new Error('Expected a positive server ID');
    }
    return value.id;
}

/** Browser projection coverage; SQL starvation and authorization are covered by backend integration tests. */
test('reloadFindsFreshLegacyProposalWithoutFrame', async ({ page }, testInfo) => {
    const workspaceId = await activeWorkspaceId(page.request);
    const csrf = await csrfBootstrap(page.request);
    const userResponse = await page.request.get('/api/auth/me');
    expect(userResponse.ok()).toBe(true);
    const userId = requiredId(await userResponse.json());
    const sessionResponse = await page.request.post('/api/ai/assistant/sessions', {
        headers: { 'X-Workspace-Id': String(workspaceId), [csrf.headerName]: csrf.token },
        data: { title: 'Proposal review expiry', autoTitle: false },
    });
    expect(sessionResponse.status()).toBe(201);
    const sessionId = requiredId(await sessionResponse.json());
    const now = Date.now();
    const createdAt = new Date(now).toISOString();
    const contact = runFixture(testInfo.project.name).contacts.peek;
    const detail: AiChatSessionDetail = {
        session: {
            id: sessionId, workspaceId, createdByUserId: userId, title: 'Proposal review expiry',
            visibility: 'private', status: 'active', archived: false, ownedByCurrentUser: true,
            historySummarized: false, participationStatus: null, lastMessageAt: createdAt,
            archivedAt: null, createdAt, updatedAt: createdAt,
        },
        messages: {
            items: [
                { id: 1, sessionId, seq: 1, authorKind: 'user', authorUserId: userId,
                    authorDisplayName: null, content: 'Review these changes', createdAt },
                { id: 2, sessionId, seq: 2, authorKind: 'assistant', authorUserId: null,
                    authorDisplayName: null, content: 'These changes are ready for review.', createdAt },
            ],
            total: 2,
        },
        activeTurn: null,
    };
    const fresh: AiAssistantToolCall = {
        id: 102, toolName: 'assign_owner', tier: 'confirm', status: 'proposed',
        reviewState: 'open', reviewExpiresAt: new Date(now + 72 * 3_600_000).toISOString(),
        target: { kind: 'person', id: contact.id, label: contact.name },
        requestSummary: 'Assign owner', outcomeSummary: null,
        change: { field: 'owner', currentValue: null, currentValueUnresolved: false,
            proposedValue: 'Workspace owner', state: 'ready' },
        outcomeValues: [], createdRecord: null, messageId: 2, turnId: 102,
        undoExpiresAt: null, undoAvailable: false, createdAt, updatedAt: createdAt, executedAt: null,
    };
    const expired: AiAssistantToolCall[] = Array.from({ length: 101 }, (_, index) => ({
        ...fresh, id: index + 1, turnId: index + 1, reviewState: 'expired',
        reviewExpiresAt: new Date(now - 1).toISOString(),
        createdAt: new Date(now - 72 * 3_600_000 - 1).toISOString(),
    }));
    let pendingReads = 0;
    await page.route(new RegExp(`/api/ai/assistant/sessions/${sessionId}(?:\\?.*)?$`),
        (route) => route.fulfill({ json: detail }));
    await page.route(new RegExp(`/api/ai/assistant/sessions/${sessionId}/tool-calls(?:\\?.*)?$`),
        async (route) => {
            const pending = new URL(route.request().url()).searchParams.get('pendingOnly') === 'true';
            if (pending) pendingReads++;
            await route.fulfill({ json: pending ? [fresh] : expired.slice(0, 100) });
        });

    await page.goto(`/ask-connex/${sessionId}`);
    const apply = page.getByRole('button', { name: message('en', 'common', 'AskConnex.toolCards.actions.applyAria')
        .replace('{target}', contact.name), exact: true });
    const discard = page.getByRole('button', { name: message('en', 'common', 'AskConnex.toolCards.actions.discardAria')
        .replace('{target}', contact.name), exact: true });
    await expect(apply).toHaveCount(1);
    await expect(apply).toBeEnabled();
    await expect(discard).toHaveCount(1);
    const beforeReload = pendingReads;
    await page.reload();
    await expect(apply).toBeEnabled();
    await expect(discard).toBeEnabled();
    expect(pendingReads).toBeGreaterThan(beforeReload);
    await expect(page.getByText(message('en', 'common', 'AskConnex.toolCards.states.proposalExpired'),
        { exact: true })).toHaveCount(Math.min(expired.length, 100));

    let decisions = 0;
    let settlementReads = 0;
    await page.route(`**/api/ai/assistant/sessions/${sessionId}/tool-calls/${fresh.id}/approve`,
        async (route) => {
            decisions++;
            await route.fulfill({ status: 409, json: {
                code: 'AI_PROPOSAL_EXPIRED', message: 'Assistant proposal review window has expired',
            } });
        });
    await page.route(`**/api/ai/assistant/sessions/${sessionId}/tool-calls/${fresh.id}`,
        async (route) => {
            settlementReads++;
            await route.fulfill({ json: { ...fresh, reviewState: 'expired' } });
        });
    await apply.click();
    await expect(apply).toHaveCount(0);
    await expect(discard).toHaveCount(0);
    await expect.poll(() => settlementReads).toBe(1);
    expect(decisions).toBe(1);
});
