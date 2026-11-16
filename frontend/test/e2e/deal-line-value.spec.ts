import { randomUUID } from 'node:crypto';
import { expect, test, type APIResponse, type Page, type Response } from '@playwright/test';
import { activeWorkspaceId, csrfBootstrap, registerUser } from './support/api';
import { useLocale } from './support/locale';
import { message } from './support/messages';

test.use({ storageState: { cookies: [], origins: [] } });

function record(value: unknown): Record<string, unknown> {
    if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new Error('Expected an object');
    return Object.fromEntries(Object.entries(value));
}

async function body(response: APIResponse | Response): Promise<Record<string, unknown>> {
    expect(response.ok(), await response.text()).toBe(true);
    const value: unknown = await response.json();
    return record(value);
}

function id(value: Record<string, unknown>): number {
    if (typeof value.id !== 'number') throw new Error('Missing record id');
    return value.id;
}

function lineSaved(page: Page, dealId: number, method: 'POST' | 'PUT') {
    return page.waitForResponse((response) => response.url().includes(`/api/deals/${dealId}/line-items`)
        && response.request().method() === method
        && record(response.request().postDataJSON()).previewOnly !== true);
}

async function setup(page: Page, value: number) {
    const unique = randomUUID();
    await registerUser(page.request, { username: `lines-${unique}`, password: `Lines!${unique}A1`, email: `lines-${unique}@example.com` });
    const workspaceId = await activeWorkspaceId(page.request);
    const csrf = await csrfBootstrap(page.request);
    const headers = { 'X-Workspace-Id': String(workspaceId), [csrf.headerName]: csrf.token };
    const pipeline = await body(await page.request.post('/api/pipelines', { headers, data: { name: `Line values ${unique}` } }));
    const pipelineId = id(pipeline);
    const stage = await body(await page.request.post(`/api/pipelines/${pipelineId}/stages`, {
        headers, data: { name: 'Review', position: 0, success: false, failure: false },
    }));
    const name = `Line value ${unique}`;
    const review = await body(await page.request.post('/api/duplicate-preflight/deals', { headers, data: { name, companyId: null } }));
    const deal = await body(await page.request.post('/api/deals', { headers, data: {
        name, currency: 'USD', value, actualValue: 0, pipeline: pipelineId, stage: id(stage), company: null,
        expectedCloseDate: new Date().toISOString().slice(0, 10), duplicateReviewToken: review.reviewToken,
    } }));
    const dealId = id(deal);
    await page.goto(`/records/deals/${dealId}`);
    const lines = page.getByRole('region', { name: 'Line items', exact: true });
    await expect(lines).toHaveCount(1);
    await expect(lines).toBeVisible();
    const saved = async () => body(await page.request.get(`/api/deals/${dealId}`, { headers }));
    const figures = async () => {
        const paths = [
            '/api/deals/metrics', `/api/deals/metrics?pipelineId=${pipelineId}`,
            `/api/deals/board?pipelineId=${pipelineId}`, '/api/deals/pipeline-value?currency=USD',
            '/api/deals/revenue-timeseries?currency=USD&timezone=UTC',
            '/api/deals/revenue-timeseries?currency=USD&timezone=UTC&openOnly=true',
        ];
        return Promise.all(paths.map(async (path) => {
            const response = await page.request.get(path, { headers });
            expect(response.ok(), path).toBe(true);
            const result: unknown = await response.json();
            return result;
        }));
    };
    return { headers, dealId, lines, saved, figures };
}

test('blank draft, priced confirmation, cancel and final removal preserve deal value @mobile', async ({ page, isMobile }) => {
    test.setTimeout(120_000);
    const { lines, saved, figures, dealId, headers } = await setup(page, 114200);
    const before = await figures();
    const original = await saved();
    const writes: string[] = [];
    page.on('request', (request) => {
        if (request.url().includes(`/api/deals/${dealId}/line-items`) && request.method() !== 'GET') writes.push(request.method());
    });
    await test.step('Add line alone remains local and leaves all pipeline consumers unchanged', async () => {
        const add = lines.getByRole('button', { name: 'Add line', exact: true });
        await add.scrollIntoViewIfNeeded();
        await expect(add).toBeInViewport();
        await add.click();
        await expect(lines.getByRole('spinbutton', { name: 'Unit price', exact: true })).toBeEmpty();
        await expect(lines.getByRole('button', { name: 'Save line', exact: true })).toBeDisabled();
        expect(writes).toEqual([]);
        expect(await saved()).toEqual(original);
        expect(await figures()).toEqual(before);
    });
    await test.step('a priced line asks with the tax-inclusive total and cancel preserves the draft', async () => {
        await lines.getByRole('spinbutton', { name: 'Unit price', exact: true }).fill('120.50');
        await lines.getByRole('spinbutton', { name: 'Tax rate (%)', exact: true }).fill('10');
        await lines.getByRole('button', { name: 'Save line', exact: true }).click();
        const dialog = page.getByRole('dialog');
        await expect(dialog).toContainText('$114,200.00 to $132.55, including tax');
        expect(await saved()).toEqual(original);
        expect(await figures()).toEqual(before);
        await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
        await expect(lines.getByRole('spinbutton', { name: 'Unit price', exact: true })).toHaveValue('120.50');
        expect(await figures()).toEqual(before);
        const rejected = await page.request.post(`/api/deals/${dealId}/line-items`, {
            headers, data: { name: 'Unconfirmed', unitPrice: 0, quantity: 1 },
        });
        expect(rejected.status()).toBe(409);
        const refusal: unknown = await rejected.json();
        expect(record(refusal).code).toBe('DEAL_LINE_VALUE_CONSENT_REQUIRED');
        expect(await saved()).toEqual(original);
    });
    await test.step('confirmed total is saved and removing the final line restores the manual amount', async () => {
        await lines.getByRole('button', { name: 'Save line', exact: true }).click();
        const created = lineSaved(page, dealId, 'POST');
        await page.getByRole('dialog').getByRole('button', { name: 'Replace value and save' }).click();
        await body(await created);
        await expect.poll(async () => (await saved()).value).toBe(132.55);
        expect((await saved()).valueSource).toBe('line_items');
        const qty = lines.getByRole('spinbutton', { name: 'Qty', exact: true });
        await qty.fill('2.5');
        const quantitySaved = lineSaved(page, dealId, 'PUT');
        await qty.press('Tab');
        await body(await quantitySaved);
        await expect.poll(async () => (await saved()).value).toBe(331.38);
        await expect(page.getByRole('dialog')).toHaveCount(0);
        if (isMobile) {
            const editor = lines.getByRole('article').first();
            await expect(editor.getByRole('spinbutton', { name: 'Qty', exact: true })).toBeVisible();
            await expect(editor.getByRole('spinbutton', { name: 'Unit price', exact: true })).toBeVisible();
            await expect(editor.getByRole('spinbutton', { name: 'Discount value', exact: true })).toBeVisible();
            await expect(editor.getByRole('spinbutton', { name: 'Tax rate (%)', exact: true })).toBeVisible();
            await expect(editor).toContainText('$331.38');
            expect(await editor.evaluate((element) => element.scrollWidth <= element.clientWidth)).toBe(true);
        }
        const remove = lines.getByRole('button', { name: 'Remove line', exact: true });
        await remove.scrollIntoViewIfNeeded();
        await expect(remove).toBeInViewport();
        await remove.click();
        await expect.poll(async () => (await saved()).value).toBe(114200);
        expect((await saved()).valueSource).toBe('manual');
    });
});

test('catalog tax example and editable discounts persist into approval checks @mobile', async ({ page }) => {
    test.setTimeout(120_000);
    const { headers, dealId, lines, saved } = await setup(page, 121600);
    await body(await page.request.post('/api/products', { headers, data: {
        name: 'Taxed service', sku: `tax-${randomUUID()}`, unitPrice: 120.5, currency: 'USD',
        taxRate: 10, billingFrequency: 'one_time', active: true,
    } }));
    await page.reload();
    await lines.getByRole('combobox').fill('Taxed service');
    await page.getByRole('option', { name: /Taxed service/ }).click();
    await lines.getByRole('button', { name: 'Save line', exact: true }).click();
    await expect(page.getByRole('dialog')).toContainText('$121,600.00 to $132.55, including tax');
    const created = lineSaved(page, dealId, 'POST');
    await page.getByRole('dialog').getByRole('button', { name: 'Replace value and save' }).click();
    await body(await created);
    await expect.poll(async () => (await saved()).value).toBe(132.55);
    const qty = lines.getByRole('spinbutton', { name: 'Qty', exact: true });
    await qty.fill('2.5');
    const quantitySaved = lineSaved(page, dealId, 'PUT');
    await qty.press('Tab');
    await body(await quantitySaved);
    await expect.poll(async () => (await saved()).value).toBe(331.38);
    await lines.getByRole('combobox', { name: 'Discount type', exact: true }).click();
    const typeSaved = lineSaved(page, dealId, 'PUT');
    await page.getByRole('option', { name: 'Percentage (%)', exact: true }).click();
    await body(await typeSaved);
    const discount = lines.getByRole('spinbutton', { name: 'Discount value', exact: true });
    await expect(discount).toBeEnabled();
    await discount.fill('20');
    const discountSaved = lineSaved(page, dealId, 'PUT');
    await discount.press('Tab');
    await body(await discountSaved);
    await expect.poll(async () => (await saved()).value).toBe(265.10);
    await body(await page.request.post('/api/approval-policies', { headers, data: {
        name: 'Discount review', active: true, documentType: 'quote', currency: 'USD', minDiscountPercent: 20,
        mode: 'sequential', separationOfDuties: 'strict', steps: [],
    } }));
    const template = await body(await page.request.post('/api/document-templates', { headers, data: {
        name: 'Line review quote', type: 'quote', locale: 'en', title: 'Quote', active: true,
    } }));
    const document = await body(await page.request.post(`/api/deals/${dealId}/documents`, { headers, data: { templateId: id(template) } }));
    expect(document.requiresApproval).toBe(true);
    const persisted = await body(await page.request.get(`/api/deals/${dealId}/line-items`, { headers }));
    if (!Array.isArray(persisted.items)) throw new Error('Missing line items');
    const item = record(persisted.items[0]);
    expect(item.discountType).toBe('percent');
    expect(item.discountValue).toBe(20);
    expect(item.taxRate).toBe(10);
    expect(item.lineTotal).toBe(265.10);
    await page.reload();
    await expect(lines.getByRole('spinbutton', { name: 'Discount value', exact: true })).toHaveValue('20');
    await expect(lines.getByRole('spinbutton', { name: 'Tax rate (%)', exact: true })).toHaveValue('10');
    await lines.getByRole('button', { name: 'Remove line', exact: true }).click();
    await expect.poll(async () => (await saved()).value).toBe(121600);
    await useLocale(page, 'ja');
    await page.reload();
    await expect(page.getByRole('button', { name: message('ja', 'deals', 'DealsLineItems.addLine'), exact: true })).toBeVisible();
});
