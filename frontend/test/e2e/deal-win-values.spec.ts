import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';

import { activeWorkspaceId, csrfBootstrap, registerUser, seeder } from './support/api';

test.use({ storageState: { cookies: [], origins: [] } });

/** Refuses malformed fixture identifiers instead of passing them into record routes. */
function positiveId(record: unknown): number {
    if (typeof record !== 'object' || record === null || !('id' in record)
        || typeof record.id !== 'number' || !Number.isInteger(record.id) || record.id < 1) {
        throw new Error('Expected a positive record identifier');
    }
    return record.id;
}

test('Mark won and Edit Outcome keep the stage and record the default or entered revenue', async ({ page }) => {
    test.setTimeout(180_000);
    const suffix = randomUUID().replaceAll('-', '').slice(0, 12);
    await registerUser(page.request, {
        username: `outcome_${suffix}`,
        password: `Outcome!${suffix}A1`,
        email: `outcome_${suffix}@example.com`,
    });
    const workspaceId = await activeWorkspaceId(page.request);
    const seed = seeder(page.request, workspaceId, await csrfBootstrap(page.request));
    const pipelineName = `Outcome pipeline ${suffix}`;
    const pipelineId = positiveId(await seed.post('/api/pipelines', { name: pipelineName }));
    const openId = positiveId(await seed.post(`/api/pipelines/${pipelineId}/stages`, {
        name: 'Proposal', position: 0, success: false, failure: false,
    }));
    const wonId = positiveId(await seed.post(`/api/pipelines/${pipelineId}/stages`, {
        name: 'Won', position: 1, success: true, failure: false,
    }));
    const taskTitle = `Won handoff ${suffix}`;
    await seed.post('/api/rules', {
        name: `Won handoff ${suffix}`, recordType: 'deal', enabled: true, executionMode: 'user',
        trigger: { type: 'entity_change', events: ['deal.won'] },
        actions: [{ type: 'create_task', title: taskTitle }],
    });
    async function createOpenDeal(name: string): Promise<number> {
        const review = await seed.post('/api/duplicate-preflight/deals', { name, companyId: null });
        if (typeof review.reviewToken !== 'string') throw new Error('Expected duplicate review token');
        return positiveId(await seed.post('/api/deals', {
            name, value: 25400, currency: 'USD', pipeline: pipelineId, stage: openId,
            duplicateReviewToken: review.reviewToken,
        }));
    }

    async function expectOneHandoff(id: number): Promise<void> {
        await expect.poll(async () => {
            const response = await page.request.get(`/api/deals/${id}/tasks`);
            expect(response.status()).toBe(200);
            const tasks: unknown = await response.json();
            if (!Array.isArray(tasks)) throw new Error('Expected task list');
            return tasks.filter((task: unknown) => typeof task === 'object' && task !== null
                && 'description' in task && task.description === taskTitle).length;
        }).toBe(1);
    }

    const markedId = await createOpenDeal(`Mark won ${suffix}`);
    await page.goto(`/records/deals/${markedId}`);
    await page.getByRole('button', { name: 'More actions', exact: true }).click();
    const closed = page.waitForResponse((response) =>
        response.url().endsWith(`/api/deals/${markedId}/close`) && response.request().method() === 'POST');
    await page.getByRole('menuitem', { name: 'Mark won', exact: true }).click();
    const closeResponse = await closed;
    expect(closeResponse.status()).toBe(200);
    expect(await closeResponse.json()).toMatchObject({ won: true, actualValue: 25400, stage: openId });
    await expectOneHandoff(markedId);

    for (const explicitZero of [false, true]) {
        const id = await createOpenDeal(`Edit outcome ${explicitZero ? 'zero' : 'default'} ${suffix}`);
        await page.goto(`/records/deals/${id}`);
        await page.getByRole('button', { name: 'Edit', exact: true }).click();
        const sheet = page.getByRole('dialog');
        await sheet.getByRole('button', { name: 'Won', exact: true }).click();
        await expect(sheet.getByLabel('Actual value', { exact: true })).toHaveValue('25400');
        if (explicitZero) await sheet.getByLabel('Actual value', { exact: true }).fill('0');
        const saved = page.waitForResponse((response) =>
            response.url().endsWith(`/api/deals/${id}`) && response.request().method() === 'PUT');
        await sheet.getByRole('button', { name: 'Save', exact: true }).click();
        const response = await saved;
        expect(response.status()).toBe(200);
        expect(await response.json()).toMatchObject({
            won: true, actualValue: explicitZero ? 0 : 25400, stage: openId,
        });
        const submitted: unknown = response.request().postDataJSON();
        if (explicitZero) expect(submitted).toMatchObject({ actualValue: 0 });
        else expect(submitted).not.toHaveProperty('actualValue');
        await expectOneHandoff(id);
    }

    await page.goto('/records/deals');
    await page.getByRole('main').getByRole('button', { name: 'Add deal', exact: true }).click();
    const createDialog = page.getByRole('dialog', { name: 'New deal', exact: true });
    await createDialog.getByLabel('Name', { exact: true }).fill(`Created won ${suffix}`);
    await createDialog.getByLabel('Value', { exact: true }).fill('25400');
    await createDialog.getByLabel('Currency', { exact: true }).fill('USD');
    await createDialog.getByLabel('Pipeline', { exact: true }).fill(pipelineName);
    await page.getByRole('option', { name: pipelineName, exact: true }).click();
    await createDialog.getByLabel('Stage', { exact: true }).fill('Won');
    await page.getByRole('option', { name: 'Won', exact: true }).click();
    const created = page.waitForResponse((response) =>
        response.url().endsWith('/api/deals') && response.request().method() === 'POST');
    await createDialog.getByRole('button', { name: 'Create', exact: true }).click();
    const createResponse = await created;
    expect(createResponse.status()).toBe(200);
    const createdDeal: unknown = await createResponse.json();
    expect(createdDeal).toMatchObject({ won: true, actualValue: 25400, stage: wonId });
    const creationPayload: unknown = createResponse.request().postDataJSON();
    expect(creationPayload).not.toHaveProperty('actualValue');
    await expectOneHandoff(positiveId(createdDeal));
});
