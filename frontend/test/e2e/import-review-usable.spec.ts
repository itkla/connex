import { randomUUID } from 'node:crypto';
import { createTranslator } from 'next-intl';
import { expect, test, type Locator, type Page } from '@playwright/test';
import en from '@/messages/en/importExport.json';
import ja from '@/messages/ja/importExport.json';
import { activeWorkspaceId, csrfBootstrap, registerUser, seeder } from './support/api';
import { useLocale } from './support/locale';

test.use({ storageState: { cookies: [], origins: [] } });
test.setTimeout(120_000);

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

async function choose(page: Page, trigger: Locator, label: string) {
    await trigger.click();
    await page.getByRole('option', { name: label, exact: true }).click();
}

for (const locale of ['en', 'ja'] as const) {
    test(`reviews all rows, corrects errors and applies an explicit company choice in ${locale} @mobile`, async ({ page }) => {
        const key = `review${randomUUID().replaceAll('-', '').slice(0, 12)}`;
        await registerUser(page.request, { username: key, password: `Review!${key}A1`, email: `${key}@example.invalid` });
        const workspaceId = await activeWorkspaceId(page.request);
        const seed = seeder(page.request, workspaceId, await csrfBootstrap(page.request));
        const companyName = `Reviewed company ${key}`;
        const companies = { rows: [{ Name: companyName, Industry: 'First division' }, { Name: companyName, Industry: 'Second division' }],
            mapping: [{ column: 'Name', field: 'name' }, { column: 'Industry', field: 'industry' }] };
        const companyReview = await seed.post('/api/imports/companies/preview', companies);
        if (typeof companyReview.duplicateReviewProof !== 'string') throw new Error('Company review proof is missing');
        const companyResult = await seed.post('/api/imports/companies', { ...companies, duplicateReviewProof: companyReview.duplicateReviewProof });
        expect(companyResult.created).toBe(2);
        await seed.post('/api/custom-fields', { entityType: 'person', fieldKey: 'territory', label: 'Territory', fieldType: 'text' });
        await useLocale(page, locale);
        await page.emulateMedia({ colorScheme: locale === 'ja' ? 'dark' : 'light' });
        const t = createTranslator({ locale, messages: locale === 'en' ? en : ja, namespace: 'importExport' });
        await page.goto('/records/contacts?view=table');
        await page.getByRole('button', { name: t('moreActions'), exact: true }).click();
        const fieldsLoaded = page.waitForResponse((response) => response.url().includes('/api/custom-fields/schema?entityType=person') && response.status() === 200);
        await page.getByRole('menuitem', { name: t('openImport'), exact: true }).click();
        await fieldsLoaded;
        const dialog = page.getByRole('dialog');
        const lines = Array.from({ length: 121 }, (_, index) =>
            `Review person ${index + 1},${index === 120 ? 'invalid-email' : `${key}.${index}@example.invalid`},,${index < 2 ? companyName : ''},West`);
        await dialog.locator('input[type="file"]').setInputFiles({ name: 'review.csv', mimeType: 'text/csv',
            buffer: Buffer.from(['Name,Email,Phone,Company,Territory', ...lines].join('\n')) });
        const mapColumn = (column: string) => dialog.getByRole('combobox', { name: t('mapColumn', { column }), exact: true });
        await choose(page, mapColumn('Territory'), t('existingCustomField', { name: 'Territory' }));
        await choose(page, mapColumn('Phone'), t('fields.email'));
        await expect(dialog.getByText(t('duplicateMapping')).first()).toBeVisible();
        await expect(dialog.getByRole('button', { name: t('next'), exact: true })).toBeDisabled();
        await choose(page, mapColumn('Phone'), t('fields.phone'));
        await dialog.getByRole('button', { name: t('next'), exact: true }).click();
        const firstRow = dialog.locator('[data-import-row="0"]');
        await expect(firstRow.getByText(t('problems.COMPANY_CHOICE_REQUIRED'), { exact: true })).toBeVisible();
        const secondCandidate = firstRow.getByLabel(t('candidateListLabel')).locator('[data-import-candidate]').filter({ hasText: 'Second division' });
        await secondCandidate.getByRole('button', { name: t('candidateUse'), exact: true }).click();
        await firstRow.locator('summary').filter({ hasText: t('companyChoice', { name: companyName }) }).click();
        await firstRow.getByRole('button', { name: t('applyCompanyChoice', { count: 2 }), exact: true }).click();
        await dialog.getByRole('button', { name: t('refreshReview'), exact: true }).click();
        await expect(firstRow.getByText(t('problems.COMPANY_CHOICE_REQUIRED'), { exact: true })).toHaveCount(0);
        for (let pageIndex = 0; pageIndex < 6; pageIndex += 1) {
            await dialog.getByRole('button', { name: t('nextPage'), exact: true }).click();
        }
        const lastRow = dialog.locator('[data-import-row="120"]');
        await expect(lastRow.getByText('Review person 121', { exact: true })).toBeVisible();
        const problem = lastRow.getByText(t('problems.EMAIL_INVALID'), { exact: true });
        await expect(problem).toBeVisible();
        const box = await problem.boundingBox();
        if (!box) throw new Error('Row problem is not visible');
        expect(box.x).toBeGreaterThanOrEqual(0);
        expect(box.x + box.width).toBeLessThanOrEqual(page.viewportSize()?.width ?? 0);
        await lastRow.getByText(t('editRow'), { exact: true }).click();
        await lastRow.getByLabel('Email', { exact: true }).fill(`${key}.fixed@example.invalid`);
        await lastRow.getByLabel('Email', { exact: true }).blur();
        await expect(dialog.getByRole('button', { name: t('import'), exact: true })).toBeDisabled();
        await dialog.getByRole('button', { name: t('refreshReview'), exact: true }).click();
        await expect(lastRow.getByText(t('problems.EMAIL_INVALID'), { exact: true })).toHaveCount(0);
        await lastRow.getByRole('button', { name: t('skipRow'), exact: true }).click();
        await dialog.getByRole('button', { name: t('refreshReview'), exact: true }).click();
        await choose(page, dialog.getByRole('combobox', { name: t('reviewFilter'), exact: true }), `${t('reviewFilters.skip')} (1)`);
        await expect(lastRow.getByRole('button', { name: t('includeRow'), exact: true })).toBeVisible();
        await page.route('**/api/imports/persons', (route) => route.fulfill({ status: 400,
            contentType: 'application/json', body: JSON.stringify({ code: 'IMPORT_CUSTOM_COLLISION', message: 'Diagnostic must not be displayed' }) }), { times: 1 });
        await dialog.getByRole('button', { name: t('import'), exact: true }).click();
        await expect(dialog.getByRole('alert')).toHaveText(t('failures.CUSTOM_COLLISION'));
        await expect(dialog.getByText(t('reviewStale'), { exact: true })).toHaveCount(0);
        await expect(dialog.getByRole('button', { name: t('import'), exact: true })).toBeDisabled();
        await dialog.getByRole('button', { name: t('back'), exact: true }).click();
        await expect(mapColumn('Territory')).toHaveText(t('existingCustomField', { name: 'Territory' }));
        await dialog.getByRole('button', { name: t('next'), exact: true }).click();
        const commitResponse = page.waitForResponse((response) => response.url().endsWith('/api/imports/persons') && response.request().method() === 'POST');
        await dialog.getByRole('button', { name: t('import'), exact: true }).click();
        const committed = await commitResponse;
        expect(committed.status()).toBe(200);
        expect(await committed.json()).toMatchObject({ created: 120, skipped: 1, failed: [] });
        const payload: unknown = committed.request().postDataJSON();
        if (!isRecord(payload) || !isRecord(payload.companyLinks)) throw new Error('Company choices missing from commit');
        expect(payload.companyLinks['0']).toBe(payload.companyLinks['1']);
        expect(payload.skippedRows).toEqual([120]);
        let first: unknown;
        for (let pageIndex = 1; pageIndex <= 5; pageIndex += 1) {
            const people = await page.request.get(`/api/persons/page?page=${pageIndex}&size=25`, { headers: { 'X-Workspace-Id': String(workspaceId) } });
            expect(people.status()).toBe(200);
            const persisted: unknown = await people.json();
            if (!isRecord(persisted) || !Array.isArray(persisted.items)) throw new Error('Contact page is missing');
            expect(persisted.total).toBe(120);
            first = persisted.items.find((item: unknown) => isRecord(item) && item.name === 'Review person 1');
            if (first) break;
        }
        if (!isRecord(first) || !isRecord(first.company)) throw new Error('Selected company was not persisted');
        expect(first.company.id).toBe(payload.companyLinks['0']);
    });
}
