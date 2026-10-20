import { randomUUID } from 'node:crypto';
import { createTranslator } from 'next-intl';
import enCommon from '../../messages/en/common.json';
import jaCommon from '../../messages/ja/common.json';
import type { AiAssistantToolCall } from '../../app/lib/types';
import { expect, test, type Page } from '@playwright/test';

import { activeWorkspaceId, configureScriptedAiProvider, csrfBootstrap, registerUser, seeder } from './support/api';
import { useLocale } from './support/locale';
import { message } from './support/messages';

function positiveId(value: unknown): number {
    if (typeof value !== 'object' || value === null || !('id' in value)
        || typeof value.id !== 'number' || !Number.isSafeInteger(value.id) || value.id <= 0) {
        throw new Error('Expected a positive server ID');
    }
    return value.id;
}

function state(value: unknown): string {
    if (typeof value !== 'object' || value === null || !('state' in value) || typeof value.state !== 'string') {
        throw new Error('Expected mission state');
    }
    return value.state;
}

/** Gives the freshly registered requester the provider-setting proof required by the normal UI. */
async function enrollPasskey(page: Page, password: string): Promise<() => Promise<void>> {
    const cdp = await page.context().newCDPSession(page);
    await cdp.send('WebAuthn.enable');
    const { authenticatorId } = await cdp.send('WebAuthn.addVirtualAuthenticator', { options: {
        protocol: 'ctap2', ctap2Version: 'ctap2_1', transport: 'internal', hasResidentKey: true,
        hasUserVerification: true, automaticPresenceSimulation: true, isUserVerified: true,
    } });
    await page.goto('/settings/personal/security');
    await page.getByRole('button', { name: message('en', 'account', 'AccountSecurity.add') }).first().click();
    const dialog = page.getByRole('dialog', { name: message('en', 'account', 'AccountSecurity.passwordTitle') });
    await dialog.getByLabel(message('en', 'account', 'AccountSecurity.passwordLabel')).fill(password);
    await dialog.getByRole('button', { name: message('en', 'account', 'AccountSecurity.continue'), exact: true }).click();
    await expect(page.getByText(message('en', 'account', 'AccountSecurity.added'), { exact: true })).toBeVisible();
    return async () => {
        if (!page.isClosed()) {
            await cdp.send('WebAuthn.removeVirtualAuthenticator', { authenticatorId });
            await cdp.send('WebAuthn.disable');
            await cdp.detach();
        }
    };
}

for (const locale of ['en', 'ja'] as const) {
    test(`scripted mission prepares, reloads and independently applies or discards changes in ${locale} @mobile`, async ({ browser }, testInfo) => {
        test.setTimeout(180_000);
        const t = createTranslator({ locale, messages: locale === 'en' ? enCommon : jaCommon, namespace: 'AskConnex' });
        const context = await browser.newContext({ storageState: { cookies: [], origins: [] },
            baseURL: testInfo.project.use.baseURL, reducedMotion: 'reduce', locale: 'en-US', timezoneId: 'UTC' });
        const errors: string[] = [];
        const page = await context.newPage();
        page.on('pageerror', (error) => errors.push(error.message));
        page.on('console', (entry) => { if (entry.type() === 'error') errors.push(entry.text()); });
        let cleanup: (() => Promise<void>) | null = null;
        try {
            const api = context.request;
            const unique = randomUUID().replaceAll('-', '').slice(0, 12);
            const password = `E2eMission!${unique}A1`;
            await registerUser(api, { username: `e2e_mission_${unique}`, email: `e2e_mission_${unique}@example.com`, password });
            const workspaceId = await activeWorkspaceId(api);
            const csrf = await csrfBootstrap(api);
            const headers = { 'X-Workspace-Id': String(workspaceId), [csrf.headerName]: csrf.token };
            const contactId = positiveId(await seeder(api, workspaceId, csrf).post('/api/persons', {
                name: 'Marlowe Quillfeather', title: 'Operations Lead', email: `mission.${unique}@example.com`,
            }));
            cleanup = await enrollPasskey(page, password);
            await configureScriptedAiProvider(api, workspaceId, csrf, 'scripted-native');
            const session = await api.post('/api/ai/assistant/sessions', { headers, data: { title: 'Prepare record changes', autoTitle: false } });
            expect(session.status()).toBe(201);
            const sessionId = positiveId(await session.json());
            const created = await api.post(`/api/ai/assistant/sessions/${sessionId}/missions`, { headers, data: {
                clientRequestKey: randomUUID(), missionType: 'record_preparation_v1',
                goal: 'connex_script_browser_mission_proposals review this record and prepare changes',
                scope: { kind: 'record', recordKind: 'person', recordId: contactId },
            } });
            expect(created.status()).toBe(201);
            const runId = positiveId(await created.json());
            await expect.poll(async () => {
                const response = await api.get(`/api/ai/assistant/missions/${runId}`, { headers });
                expect(response.ok()).toBe(true);
                return state(await response.json());
            }, { timeout: 120_000 }).toBe('waiting_approval');
            const destinationName = `Mission destination ${unique}`;
            const destination = await api.post('/api/workspaces', { headers, data: { name: destinationName } });
            expect(destination.status()).toBe(200);
            const destinationId = positiveId(await destination.json());
            expect(destinationId).not.toBe(workspaceId);
            const selectedOrigin = await api.post(`/api/workspaces/${workspaceId}/switch`, {
                headers: { [csrf.headerName]: csrf.token },
            });
            expect(selectedOrigin.status()).toBe(200);
            const before: unknown = await (await api.get(`/api/persons/${contactId}`, { headers })).json();
            expect(before).toMatchObject({ title: 'Operations Lead' });
            const historical: AiAssistantToolCall[] = Array.from({ length: 101 }, (_, index) => ({
                id: index + 1000, toolName: 'assign_owner', tier: 'confirm', status: 'rejected',
                reviewState: 'discarded', reviewExpiresAt: null,
                target: { kind: 'person', id: contactId, label: 'Marlowe Quillfeather' },
                requestSummary: 'Assign owner', outcomeSummary: null, change: null, changes: [],
                outcomeValues: [], createdRecord: null, messageId: null, turnId: index + 1000,
                undoExpiresAt: null, undoAvailable: false, createdAt: '2026-08-01T10:00:00Z',
                updatedAt: '2026-08-01T10:00:00Z', executedAt: null,
            }));
            await page.route(new RegExp(`/api/ai/assistant/sessions/${sessionId}/tool-calls(?:\\?.*)?$`), async (route) => {
                const pending = new URL(route.request().url()).searchParams.get('pendingOnly') === 'true';
                await route.fulfill({ headers: { Date: new Date().toUTCString() }, json: pending ? [] : historical.slice(-100) });
            });
            await useLocale(context, locale);
            await page.emulateMedia({ colorScheme: locale === 'ja' ? 'dark' : 'light' });
            await page.goto('/me');
            await page.locator(`a[href='/ask-connex/${sessionId}?mission=${runId}']`).first().click();
            const review = page.getByRole('dialog', { name: message(locale, 'common', 'AskConnex.missions.preparation.outputTitle') });
            await expect(review).toBeVisible();
            await expect(review.getByText('Head of Partnerships', { exact: true })).toBeVisible();
            await page.reload();
            await expect(review).toBeVisible();
            const decision = page.waitForResponse((response) => response.request().method() === 'POST'
                && response.url().endsWith(`/api/ai/assistant/missions/${runId}/proposal-decisions`));
            const apply = review.getByRole('button', { name: t('proposalReview.applySelected', { count: 2 }) });
            await apply.focus();
            await page.keyboard.press('Enter');
            const result: unknown = await (await decision).json();
            expect(result).toMatchObject({ complete: false, items: expect.arrayContaining([
                expect.objectContaining({ outcome: 'executed' }), expect.objectContaining({ outcome: 'refused' }),
            ]) });
            const discard = review.getByRole('button', { name: message(locale, 'common', 'AskConnex.toolCards.actions.discardAria')
                .replace('{target}', 'Marlowe Quillfeather') });
            await discard.last().click();
            await expect(review.getByText(message(locale, 'common', 'AskConnex.missions.review.complete'), { exact: true })).toBeVisible();
            await page.keyboard.press('Escape');
            await expect(review).toHaveCount(0);
            await page.getByRole('button', { name: message(locale, 'workspace', 'WorkspaceSwitcher.switchAria') }).click();
            await page.getByRole('menuitem').filter({ hasText: destinationName }).click();
            await expect(page).toHaveURL(/\/dashboard$/);
            await expect(page.getByText('Head of Partnerships', { exact: true })).toHaveCount(0);
            const signedOut = await api.post('/api/auth/logout', { headers: { [csrf.headerName]: csrf.token } });
            expect(signedOut.ok()).toBe(true);
            await registerUser(api, { username: `e2e_mission_other_${unique}`,
                email: `e2e_mission_other_${unique}@example.com`, password });
            await page.goto('/ask-connex');
            await expect(review).toHaveCount(0);
            await expect(page.getByText('Head of Partnerships', { exact: true })).toHaveCount(0);
            expect(errors).toEqual([]);
        } finally {
            if (cleanup) await cleanup();
            await context.close();
        }
    });
}
