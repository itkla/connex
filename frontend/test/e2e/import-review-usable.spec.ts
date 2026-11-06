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
    test(`recovers colliding custom mappings and a replaced field in ${locale} @mobile`, async ({ page }) => {
        const key = `fields${randomUUID().replaceAll('-', '').slice(0, 12)}`;
        await registerUser(page.request, { username: key, password: `Fields!${key}A1`, email: `${key}@example.invalid` });
        const workspaceId = await activeWorkspaceId(page.request);
        const csrf = await csrfBootstrap(page.request);
        const seed = seeder(page.request, workspaceId, csrf);
        const originalField = { entityType: 'person', fieldKey: 'territory', label: 'Territory', fieldType: 'text' };
        const old = await seed.post('/api/custom-fields', originalField);
        if (typeof old.id !== 'number') throw new Error('Original custom field is missing');
        const t = createTranslator({ locale, messages: locale === 'en' ? en : ja, namespace: 'importExport' });
        await useLocale(page, locale);
        await page.goto('/records/contacts?view=table');
        await page.getByRole('button', { name: t('moreActions'), exact: true }).click();
        const schemaLoaded = () => page.waitForResponse((response) => response.url().includes('/api/custom-fields/schema?entityType=person') && response.status() === 200);
        const initialSchema = schemaLoaded();
        await page.getByRole('menuitem', { name: t('openImport'), exact: true }).click();
        await initialSchema;
        const dialog = page.getByRole('dialog');
        const mapping = (column: string) => dialog.getByRole('combobox', { name: t('mapColumn', { column }), exact: true });
        await dialog.locator('input[type="file"]').setInputFiles({ name: 'field-recovery.csv', mimeType: 'text/csv',
            buffer: Buffer.from(`Name,Email,Region Code,Region-Code,Legacy\nField recovery,${key}@example.invalid,North,South,West\n`) });
        for (const column of ['Region Code', 'Region-Code']) {
            await choose(page, mapping(column), t('createCustomField'));
        }
        await choose(page, mapping('Legacy'), t('existingCustomField', { name: 'Territory' }));
        const refusedPreview = page.waitForResponse((response) => response.url().endsWith('/api/imports/persons/preview'));
        await dialog.getByRole('button', { name: t('next'), exact: true }).click();
        const refused = await refusedPreview;
        expect(refused.status()).toBe(400);
        expect(await refused.json()).toMatchObject({ code: 'IMPORT_MAPPING_DUPLICATE', fieldErrors: {
            'Region Code': 'IMPORT_MAPPING_DUPLICATE', 'Region-Code': 'IMPORT_MAPPING_DUPLICATE',
        } });
        for (const column of ['Region Code', 'Region-Code']) {
            await expect(dialog.locator(`[data-import-column="${column}"]`).getByRole('alert')).toHaveText(t('duplicateMapping'));
        }
        await expect(dialog.getByRole('button', { name: t('next'), exact: true })).toBeDisabled();
        await choose(page, mapping('Region-Code'), t('ignore'));
        const firstPreview = page.waitForResponse((response) => response.url().endsWith('/api/imports/persons/preview') && response.status() === 200);
        await dialog.getByRole('button', { name: t('next'), exact: true }).click();
        const firstReview: unknown = await (await firstPreview).json();
        if (!isRecord(firstReview) || typeof firstReview.duplicateReviewProof !== 'string') throw new Error('Initial review proof is missing');
        const archived = await page.request.put(`/api/custom-fields/${old.id}`, {
            headers: { 'X-Workspace-Id': String(workspaceId), [csrf.headerName]: csrf.token },
            data: { ...originalField, archived: true },
        });
        expect(archived.status()).toBe(200);
        const replacement = await seed.post('/api/custom-fields', { ...originalField, fieldKey: 'territory_replacement', label: 'Territory replacement' });
        if (typeof replacement.id !== 'number') throw new Error('Replacement custom field is missing');
        const refreshedSchema = schemaLoaded();
        const refusedCommit = page.waitForResponse((response) => response.url().endsWith('/api/imports/persons') && response.request().method() === 'POST');
        await dialog.getByRole('button', { name: t('import'), exact: true }).click();
        expect((await refusedCommit).status()).toBe(400);
        expect(await (await refusedCommit).json()).toMatchObject({ code: 'IMPORT_CUSTOM_UNAVAILABLE' });
        await refreshedSchema;
        await dialog.getByRole('button', { name: t('back'), exact: true }).click();
        await expect(mapping('Legacy')).toHaveText(t('unavailableCustomField'));
        await expect(dialog.getByText(t('unavailableCustomFieldHint'), { exact: true })).toBeVisible();
        await expect(dialog.getByRole('button', { name: t('next'), exact: true })).toBeDisabled();
        await expect(mapping('Region-Code')).toHaveText(t('ignore'));
        await choose(page, mapping('Legacy'), t('existingCustomField', { name: 'Territory replacement' }));
        const manualRefresh = schemaLoaded();
        await dialog.getByRole('button', { name: t('refreshCustomFields'), exact: true }).click();
        await manualRefresh;
        await expect(dialog.getByRole('button', { name: t('refreshCustomFields'), exact: true })).toBeEnabled();
        await expect(mapping('Legacy')).toHaveText(t('existingCustomField', { name: 'Territory replacement' }));
        const freshPreview = page.waitForResponse((response) => response.url().endsWith('/api/imports/persons/preview') && response.status() === 200);
        await dialog.getByRole('button', { name: t('next'), exact: true }).click();
        const fresh: unknown = await (await freshPreview).json();
        if (!isRecord(fresh)) throw new Error('Refreshed review is missing');
        expect(fresh.duplicateReviewProof).not.toBe(firstReview.duplicateReviewProof);
        const row = dialog.locator('[data-import-row="0"]');
        await row.getByText(t('editRow'), { exact: true }).click();
        await expect(row.getByLabel('Legacy', { exact: true })).toHaveValue('West');
        const committed = page.waitForResponse((response) => response.url().endsWith('/api/imports/persons') && response.request().method() === 'POST');
        await dialog.getByRole('button', { name: t('import'), exact: true }).click();
        const result = await committed;
        expect(result.status()).toBe(200);
        expect(await result.json()).toMatchObject({ created: 1, failed: [] });
        expect(result.request().postDataJSON()).toMatchObject({ mapping: expect.arrayContaining([
            { column: 'Legacy', field: `custom:${replacement.id}` },
        ]) });
    });

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
