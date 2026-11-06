import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';

import { activeWorkspaceId, csrfBootstrap, registerUser, seeder } from './support/api';
import { useLocale } from './support/locale';
import { message } from './support/messages';

function recordId(record: Record<string, unknown>): number {
    if (typeof record.id !== 'number' || !Number.isSafeInteger(record.id) || record.id <= 0) {
        throw new Error('Expected a positive record identifier');
    }
    return record.id;
}

test.use({ storageState: { cookies: [], origins: [] } });

for (const [locale, theme] of [['en', 'light'], ['ja', 'dark']] as const) {
    test(`future-only stakeholder has no history without cold risk (${locale}) @mobile`, async ({ page, context }) => {
        const suffix = randomUUID().replaceAll('-', '').slice(0, 12);
        await registerUser(context.request, {
            username: `risk_${suffix}`, password: `Risk!${suffix}A1`, email: `risk_${suffix}@example.test`,
        });
        const workspaceId = await activeWorkspaceId(context.request);
        const csrf = await csrfBootstrap(context.request);
        const seed = seeder(context.request, workspaceId, csrf);
        const pipeline = recordId(await seed.post('/api/pipelines', { name: `Risk pipeline ${suffix}` }));
        const stage = recordId(await seed.post(`/api/pipelines/${pipeline}/stages`, {
            name: 'Discovery', position: 0, success: false, failure: false,
        }));
        const personId = recordId(await seed.post('/api/persons', { name: `New contact ${suffix}` }));
        const review = await seed.post('/api/duplicate-preflight/deals', { name: `Risk deal ${suffix}` });
        if (typeof review.reviewToken !== 'string') throw new Error('Expected a duplicate review token');
        const dealId = recordId(await seed.post('/api/deals', {
            name: `Risk deal ${suffix}`, value: 100, currency: 'JPY', pipeline, stage,
            duplicateReviewToken: review.reviewToken,
        }));
        await seed.post(`/api/deals/${dealId}/people/${personId}?role=Champion`, {});
        const tomorrow = new Date(Date.now() + 86_400_000).toISOString().slice(0, 19).replace('T', ' ');
        await seed.post('/api/activities', {
            type: 'Meeting', subject: 'First meeting planned', personId, dealId, timestamp: tomorrow,
        });
        const riskResponse = await context.request.get(`/api/deals/${dealId}/risk`, {
            headers: { 'X-Workspace-Id': String(workspaceId) },
        });
        expect(riskResponse.status()).toBe(200);
        expect(await riskResponse.json()).toMatchObject({ level: 'none', score: 0, factors: [] });
        await useLocale(context, locale);
        await context.addInitScript((choice) => localStorage.setItem('theme', choice), theme);
        await page.emulateMedia({ colorScheme: theme });
        await page.goto(`/records/deals/${dealId}`);
        const panel = page.getByRole('region', { name: message(locale, 'deals', 'DealRisk.panelTitle'), exact: true });
        await expect(panel).toContainText(message(locale, 'deals', 'DealRisk.clear'));
        await expect(panel).toContainText(message(locale, 'deals', 'DealRisk.noHistoryStakeholders'));
        await expect(panel.getByRole('list')).toHaveCount(0);
        await page.reload();
        await expect(panel).toContainText(message(locale, 'deals', 'DealRisk.noHistoryStakeholders'));
    });
}
