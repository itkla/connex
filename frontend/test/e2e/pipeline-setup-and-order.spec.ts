import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import { activeWorkspaceId, registerUser } from './support/api';
import { useLocale } from './support/locale';
import { message } from './support/messages';

test.use({ storageState: { cookies: [], origins: [] } });

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null;
}

for (const locale of ['en', 'ja'] as const) {
    test(`first deal keeps its draft through pipeline setup and saved keyboard/pointer ordering (${locale}) @mobile`, async ({ page, context }) => {
        const key = randomUUID();
        await registerUser(page.request, {
            username: `pipeline_${key.replaceAll('-', '')}`,
            password: `Pipeline!${key}A1`,
            email: `pipeline-${key}@example.invalid`,
        });
        const workspaceId = await activeWorkspaceId(page.request);
        await useLocale(context, locale);
        await page.addInitScript((theme) => localStorage.setItem('theme', theme), locale === 'ja' ? 'dark' : 'light');
        await page.goto('/records/deals');
        await page.getByRole('button', { name: message(locale, 'deals', 'DealsBrowser.addDeal'), exact: true }).click();
        const deal = page.getByRole('dialog', { name: message(locale, 'deals', 'DealsNewDialog.title'), exact: true });
        const name = `First renewal ${key}`;
        await deal.locator('#deal-name').fill(name);
        await deal.locator('#deal-value').fill('12345');
        await deal.locator('#deal-currency').fill('JPY');
        await deal.locator('#deal-close').fill('2027-01-01');
        await deal.locator('#deal-pipeline').click();
        await expect(page.getByText(message(locale, 'deals', 'DealsNewDialog.noPipelinesFound'), { exact: true })).toBeVisible();
        await deal.locator('#deal-pipeline').press('Escape');
        const setup = deal.getByRole('button', { name: message(locale, 'deals', 'DealsNewDialog.setupPipeline'), exact: true });
        await setup.focus();
        await setup.press('Enter');
        const pipelineDialog = page.getByRole('dialog', { name: message(locale, 'pipelines', 'PipelinesNewDialog.title'), exact: true });
        const pipelineName = `Sales ${key}`;
        await pipelineDialog.locator('#pipeline-name').fill(pipelineName);
        await pipelineDialog.getByRole('button', { name: message(locale, 'pipelines', 'PipelinesNewDialog.cancel'), exact: true }).click();
        await expect(pipelineDialog).not.toBeVisible();
        await expect(setup).toBeFocused();
        await expect(deal.locator('#deal-name')).toHaveValue(name);
        await setup.press('Enter');
        await expect(pipelineDialog.locator('#pipeline-name')).toHaveValue(pipelineName);
        const stagesSaved = page.waitForResponse((response) => /\/api\/pipelines\/\d+\/stages$/.test(response.url()) && response.request().method() === 'PUT');
        await pipelineDialog.getByRole('button', { name: message(locale, 'pipelines', 'PipelinesNewDialog.create'), exact: true }).click();
        const stageResponse = await stagesSaved;
        expect(stageResponse.status()).toBe(200);
        expect(stageResponse.request().headers()['x-workspace-id']).toBe(String(workspaceId));
        const initial: unknown = await stageResponse.json();
        if (!Array.isArray(initial) || !isRecord(initial[0]) || typeof initial[0].pipeline !== 'number') {
            throw new Error('Pipeline setup did not return its stages');
        }
        const pipelineId = initial[0].pipeline;
        await expect(pipelineDialog).not.toBeVisible();
        await expect(deal.locator('#deal-name')).toHaveValue(name);
        await expect(deal.locator('#deal-value')).toHaveValue('12345');
        await expect(deal.locator('#deal-currency')).toHaveValue('JPY');
        await expect(deal.locator('#deal-close')).toHaveValue('2027-01-01');
        await expect(deal.locator('#deal-pipeline')).toHaveValue(pipelineName);
        await expect(deal.locator('#deal-stage')).toHaveValue(message(locale, 'deals', 'DealsNewDialog.starterOpen'));
        await expect(deal.locator('#deal-stage')).toBeFocused();
        const created = page.waitForResponse((response) => response.url().endsWith('/api/deals') && response.request().method() === 'POST');
        await deal.getByRole('button', { name: message(locale, 'deals', 'DealsNewDialog.create'), exact: true }).click();
        expect((await created).status()).toBe(200);
        await expect(deal).not.toBeVisible();

        await page.getByRole('button', { name: message(locale, 'deals', 'DealsBrowser.addDeal'), exact: true }).click();
        await expect(deal.getByRole('button', { name: message(locale, 'deals', 'DealsNewDialog.setupPipeline'), exact: true })).not.toBeVisible();
        await deal.locator('#deal-pipeline').click();
        await expect(page.getByRole('option', { name: pipelineName, exact: true })).toBeVisible();
        await deal.locator('#deal-pipeline').press('Escape');
        await deal.getByRole('button', { name: message(locale, 'deals', 'DealsNewDialog.cancel'), exact: true }).click();
        await expect(deal).not.toBeVisible();

        await page.goto(`/records/pipelines?edit=${pipelineId}`);
        const editor = page.getByRole('dialog', { name: message(locale, 'pipelines', 'PipelinesQuickEditSheet.titleSingle'), exact: true });
        const add = editor.getByRole('button', { name: message(locale, 'pipelines', 'PipelinesQuickEditSheet.addStage'), exact: true });
        await add.focus();
        await add.press('Enter');
        const discovery = editor.locator(`#pipeline-stage-${pipelineId}-1`);
        await expect(discovery).toBeFocused();
        await discovery.fill('Discovery');
        await editor.locator(`#pipeline-stage-${pipelineId}-1-up`).click();
        await expect(editor.locator(`#pipeline-stage-${pipelineId}-0`)).toHaveValue('Discovery');
        await expect(editor.locator(`#pipeline-stage-${pipelineId}-0-up`)).toBeFocused();
        await editor.locator(`#pipeline-stage-${pipelineId}-0-down`).press('Enter');
        await expect(editor.locator(`#pipeline-stage-${pipelineId}-1-down`)).toBeFocused();
        await editor.locator(`#pipeline-stage-${pipelineId}-1-up`).press('Space');
        let rejectSave = true;
        await page.route(`**/api/pipelines/${pipelineId}/stages`, async (route) => {
            if (route.request().method() === 'PUT' && rejectSave) {
                rejectSave = false;
                await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ error: 'Unavailable' }) });
            } else {
                await route.continue();
            }
        });
        const rejected = page.waitForResponse((response) => response.url().endsWith(`/api/pipelines/${pipelineId}/stages`) && response.request().method() === 'PUT');
        await editor.getByRole('button', { name: message(locale, 'pipelines', 'PipelinesQuickEditSheet.save'), exact: true }).click();
        expect((await rejected).status()).toBe(503);
        await expect(editor.locator(`#pipeline-stage-${pipelineId}-0`)).toHaveValue(message(locale, 'deals', 'DealsNewDialog.starterOpen'));
        await expect(editor.locator(`#pipeline-stage-${pipelineId}-1`)).toHaveValue('Discovery');
        await expect(editor.getByRole('status').filter({ hasText: message(locale, 'pipelines', 'PipelineStageOrder.restored') })).toHaveText(message(locale, 'pipelines', 'PipelineStageOrder.restored'));
        await editor.locator(`#pipeline-stage-${pipelineId}-1-up`).press('Enter');
        const saved = page.waitForResponse((response) => response.url().endsWith(`/api/pipelines/${pipelineId}/stages`) && response.request().method() === 'PUT');
        await editor.getByRole('button', { name: message(locale, 'pipelines', 'PipelinesQuickEditSheet.save'), exact: true }).click();
        const savedResponse = await saved;
        expect(savedResponse.status()).toBe(200);
        const finalStages: unknown = await savedResponse.json();
        expect(finalStages).toMatchObject([
            { name: 'Discovery', position: 0, success: false, failure: false },
            { name: message(locale, 'deals', 'DealsNewDialog.starterOpen'), position: 1, success: false, failure: false },
            { name: message(locale, 'deals', 'DealsNewDialog.starterWon'), position: 2, success: true, failure: false },
            { name: message(locale, 'deals', 'DealsNewDialog.starterLost'), position: 3, success: false, failure: true },
        ]);
        await expect(editor).not.toBeVisible();
        await page.reload();
        await page.goto(`/records/pipelines?edit=${pipelineId}`);
        await expect(editor.locator(`#pipeline-stage-${pipelineId}-0`)).toHaveValue('Discovery');
        await expect(editor.locator(`#pipeline-stage-${pipelineId}-2`)).toHaveValue(message(locale, 'deals', 'DealsNewDialog.starterWon'));
        await expect(editor.locator(`#pipeline-stage-${pipelineId}-3`)).toHaveValue(message(locale, 'deals', 'DealsNewDialog.starterLost'));
    });
}
