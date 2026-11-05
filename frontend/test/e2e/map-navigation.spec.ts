import { randomUUID } from 'node:crypto';

import { expect, test } from './support/cleanup';
import { csrfBootstrap, seeder } from './support/api';
import { runFixture } from './support/fixtures';
import { useLocale } from './support/locale';
import { message } from './support/messages';

for (const locale of ['en', 'ja'] as const) {
    test(`focused map actions remain available within capacity (${locale}) @mobile`, async ({ page }, testInfo) => {
        test.setTimeout(120_000);
        const fixture = runFixture(testInfo.project.name);
        await useLocale(page, locale);
        const records = [
            { path: `/records/contacts/${fixture.contacts.peek.id}`, namespace: 'contacts', key: 'ContactsActionsMenu.viewInMap', focus: `contactId=${fixture.contacts.peek.id}` },
            { path: `/records/companies/${fixture.companies.primary.id}`, namespace: 'companies', key: 'CompaniesActionsMenu.viewInMap', focus: `companyId=${fixture.companies.primary.id}` },
            { path: `/records/deals/${fixture.deals.primary.id}`, namespace: 'deals', key: 'DealsActionsMenu.viewInMap', focus: `companyId=${fixture.companies.primary.id}` },
        ];
        for (const record of records) {
            await page.goto(record.path);
            const action = page.getByRole('button', { name: message(locale, record.namespace, record.key), exact: true });
            await expect(action).toBeVisible();
            await action.click();
            await expect(page).toHaveURL(`/intelligence/map?${record.focus}`);
            await expect(page.getByRole('button', { name: message(locale, 'map', 'MapSearch.open'), exact: true })).toBeVisible();
            await expect(page.getByRole('heading', { name: message(locale, 'map', 'MapPage.limitTitle'), exact: true })).toHaveCount(0);
            await expect(page.getByRole('heading', { name: message(locale, 'errors', 'ErrorState.title'), exact: true })).toHaveCount(0);
        }
    });

    test(`a deal without a company offers ordinary navigation without a dead map action (${locale}) @mobile`, async ({ page, cleanup }, testInfo) => {
        test.setTimeout(120_000);
        const fixture = runFixture(testInfo.project.name);
        const sourceResponse = await page.request.get(`/api/deals/${fixture.deals.primary.id}`, {
            headers: { 'X-Workspace-Id': String(fixture.workspaceId) },
        });
        expect(sourceResponse.ok()).toBe(true);
        const source: unknown = await sourceResponse.json();
        if (typeof source !== 'object' || source === null
            || !('pipeline' in source) || typeof source.pipeline !== 'number'
            || !('stage' in source) || typeof source.stage !== 'number') {
            throw new Error('The seeded deal needs a pipeline and stage');
        }
        const csrf = await csrfBootstrap(page.request);
        const created = await seeder(page.request, fixture.workspaceId, csrf).post('/api/deals', {
            name: `Unlinked map navigation ${randomUUID()}`,
            value: 1,
            currency: 'JPY',
            pipeline: source.pipeline,
            stage: source.stage,
        });
        if (typeof created.id !== 'number' || typeof created.name !== 'string') {
            throw new Error('The created deal needs an id and name');
        }
        const id = created.id;
        cleanup.add(async (api) => {
            const token = await csrfBootstrap(api);
            const response = await api.delete(`/api/deals/${id}`, {
                headers: { 'X-Workspace-Id': String(fixture.workspaceId), [token.headerName]: token.token },
            });
            expect(response.ok()).toBe(true);
        });

        await useLocale(page, locale);
        await page.goto(`/records/deals/${id}`);
        await expect(page.getByRole('heading', { name: created.name, exact: true })).toBeVisible();
        await expect(page.getByRole('button', { name: message(locale, 'deals', 'DealsActionsMenu.viewInMap'), exact: true })).toHaveCount(0);
        await page.getByRole('navigation', { name: message(locale, 'common', 'CommonBreadcrumb.ariaLabel'), exact: true })
            .getByRole('link', { name: message(locale, 'common', 'CommonBreadcrumb.deals'), exact: true }).click();
        await expect(page).toHaveURL(/\/records\/deals(?:\?|$)/);
    });
}
