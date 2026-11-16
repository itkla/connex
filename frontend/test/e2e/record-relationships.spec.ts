import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import { csrfBootstrap, seeder } from './support/api';
import { runFixture } from './support/fixtures';
import { useLocale } from './support/locale';
import { message } from './support/messages';

function id(record: Record<string, unknown>): number {
    if (typeof record.id !== 'number') throw new Error('Missing record id');
    return record.id;
}

for (const locale of ['en', 'ja'] as const) {
    const copy = (file: string, key: string, name = '') => message(locale, file, key).replaceAll('{name}', name);

    test(`${locale}: tags expose membership, filtered contacts and persisted removal @mobile`, async ({ page }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);
        const csrf = await csrfBootstrap(page.request);
        const seed = seeder(page.request, fixture.workspaceId, csrf);
        const unique = randomUUID();
        const name = `Tag contact ${unique}`;
        const contact = await seed.post('/api/persons', { name, title: 'Buyer' });
        const tagName = `Follow-up ${unique}`;
        const tag = await seed.post('/api/tags', { name: tagName, color: '#339966' });
        const contactId = id(contact);
        const tagId = id(tag);
        const pin = { 'X-Workspace-Id': String(fixture.workspaceId) };
        await useLocale(page, locale);
        await page.emulateMedia({ colorScheme: testInfo.project.name === 'mobile-chromium' ? 'dark' : 'light' });
        await page.goto('/library/tags');
        await page.getByRole('button', { name: copy('activity', 'ActivityLibraryTags.membersAria', tagName), exact: true }).click();
        let sheet = page.getByRole('dialog', { name: copy('activity', 'TagMembers.title', tagName), exact: true });
        const contactPicker = sheet.getByRole('combobox', { name: copy('activity', 'TagMembers.add.contacts'), exact: true });
        await contactPicker.fill(name);
        const added = page.waitForResponse((response) => new URL(response.url()).pathname === `/api/persons/${contactId}/tags/${tagId}` && response.request().method() === 'POST');
        await page.getByRole('option', { name, exact: true }).click();
        expect((await added).ok()).toBe(true);
        await expect(sheet.getByRole('link', { name, exact: true })).toBeVisible();
        await sheet.getByRole('link', { name: copy('activity', 'TagMembers.viewContacts'), exact: true }).click();
        await expect(page).toHaveURL((url) => url.pathname === '/records/contacts' && url.searchParams.get('tag') === String(tagId));
        await expect(page.getByText(name, { exact: true }).first()).toBeVisible();
        await page.reload();
        await expect(page.getByText(name, { exact: true }).first()).toBeVisible();
        const filtered = await page.request.get(`/api/persons/page?tagIds=${tagId}&page=1&size=25`, { headers: pin });
        expect(filtered.ok()).toBe(true);
        expect(await filtered.json()).toMatchObject({ total: 1, items: [{ id: contactId }] });
        await page.goto('/library/tags');
        await page.getByRole('button', { name: copy('activity', 'ActivityLibraryTags.membersAria', tagName), exact: true }).click();
        sheet = page.getByRole('dialog', { name: copy('activity', 'TagMembers.title', tagName), exact: true });
        const removed = page.waitForResponse((response) => new URL(response.url()).pathname === `/api/persons/${contactId}/tags/${tagId}` && response.request().method() === 'DELETE');
        await sheet.getByRole('button', { name: copy('activity', 'TagMembers.removeFor', name), exact: true }).click();
        expect((await removed).ok()).toBe(true);
        await expect(sheet.getByRole('link', { name, exact: true })).toHaveCount(0);
        await page.reload();
        const persisted = await page.request.get(`/api/tags/${tagId}/people`, { headers: pin });
        expect(await persisted.json()).toEqual([]);
    });

    test(`${locale}: a deal without a company supports direct stakeholders and role changes @mobile`, async ({ page }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);
        const csrf = await csrfBootstrap(page.request);
        const seed = seeder(page.request, fixture.workspaceId, csrf);
        const unique = randomUUID();
        const contactName = `Stakeholder ${unique}`;
        const contact = await seed.post('/api/persons', { name: contactName, title: 'Director' });
        const pipeline = await seed.post('/api/pipelines', { name: `Relationships ${unique}` });
        const stage = await seed.post(`/api/pipelines/${id(pipeline)}/stages`, { name: 'Review', position: 0, success: false, failure: false });
        const name = `Relationship deal ${unique}`;
        const review = await seed.post('/api/duplicate-preflight/deals', { name, companyId: null });
        const deal = await seed.post('/api/deals', { name, pipeline: id(pipeline), stage: id(stage), company: null, value: 100, currency: 'JPY', duplicateReviewToken: review.reviewToken });
        const dealId = id(deal);
        const contactId = id(contact);
        const pin = { 'X-Workspace-Id': String(fixture.workspaceId) };
        await useLocale(page, locale);
        await page.goto(`/records/deals/${dealId}`);
        let panel = page.getByRole('region', { name: copy('deals', 'DealStakeholders.title'), exact: true });
        await panel.getByRole('button', { name: copy('deals', 'DealStakeholders.add'), exact: true }).click();
        let editor = page.getByRole('dialog', { name: copy('deals', 'DealStakeholders.add'), exact: true });
        await editor.getByRole('combobox', { name: copy('deals', 'DealStakeholders.contact'), exact: true }).fill(contactName);
        await page.getByRole('option', { name: contactName, exact: true }).click();
        await editor.getByLabel(copy('deals', 'DealStakeholders.role'), { exact: true }).fill('Champion');
        const added = page.waitForResponse((response) => new URL(response.url()).pathname === `/api/deals/${dealId}/people/${contactId}` && response.request().method() === 'POST');
        await editor.getByRole('button', { name: copy('deals', 'DealStakeholders.save'), exact: true }).click();
        expect((await added).ok()).toBe(true);
        await expect(editor).not.toBeVisible();
        await expect(panel.getByText('Champion', { exact: true })).toBeVisible();
        await page.reload();
        panel = page.getByRole('region', { name: copy('deals', 'DealStakeholders.title'), exact: true });
        await expect(panel.getByRole('link', { name: new RegExp(contactName) })).toHaveAttribute('href', `/records/contacts/${contactId}`);
        await panel.getByRole('button', { name: copy('deals', 'DealStakeholders.editRoleFor', contactName), exact: true }).click();
        editor = page.getByRole('dialog', { name: copy('deals', 'DealStakeholders.editRole'), exact: true });
        await editor.getByLabel(copy('deals', 'DealStakeholders.role'), { exact: true }).fill('Buyer');
        const changed = page.waitForResponse((response) => new URL(response.url()).pathname === `/api/deals/${dealId}/people/${contactId}` && response.request().method() === 'PUT');
        await editor.getByRole('button', { name: copy('deals', 'DealStakeholders.save'), exact: true }).click();
        expect((await changed).ok()).toBe(true);
        await expect(panel.getByText('Buyer', { exact: true })).toBeVisible();
        const removed = page.waitForResponse((response) => new URL(response.url()).pathname === `/api/deals/${dealId}/people/${contactId}` && response.request().method() === 'DELETE');
        await panel.getByRole('button', { name: copy('deals', 'DealStakeholders.removeFor', contactName), exact: true }).click();
        expect((await removed).ok()).toBe(true);
        await page.reload();
        const people = await page.request.get(`/api/deals/${dealId}/people`, { headers: pin });
        expect(await people.json()).toEqual([]);
        await expect(page.getByText(copy('deals', 'DealStakeholders.empty'), { exact: true })).toBeVisible();
    });

    test(`${locale}: workflow conditions describe the contact and preserve saved matching @mobile`, async ({ page, isMobile }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);
        const seed = seeder(page.request, fixture.workspaceId, await csrfBootstrap(page.request));
        const config = { match: 'all', negate: true, conditions: [
            { type: 'field', field: 'name', op: 'equals', value: 'Sakura', negate: true },
            { type: 'field', field: 'company', op: 'in', values: [String(fixture.companies.primary.id)], negate: true },
        ] };
        const workflow = await seed.post('/api/workflows', {
            name: `Contact conditions ${randomUUID()}`, recordType: 'person', executionMode: 'user',
            definition: { schemaVersion: 1, entryNodeId: 'trigger', nodes: [
                { id: 'trigger', type: 'TRIGGER', config: { type: 'entity_change', events: ['person.updated'] } },
                { id: 'condition', type: 'CONDITION', config }, { id: 'end', type: 'END' },
            ], edges: [
                { id: 'a', sourceNodeId: 'trigger', targetNodeId: 'condition', outcome: 'next' },
                { id: 'b', sourceNodeId: 'condition', targetNodeId: 'end', outcome: 'yes' },
                { id: 'c', sourceNodeId: 'condition', targetNodeId: 'end', outcome: 'no' },
            ] },
            canvas: { positions: { trigger: { x: 0, y: 0 }, condition: { x: 0, y: 180 }, end: { x: 0, y: 360 } }, viewport: { x: 0, y: 0, zoom: 1 } },
        });
        await useLocale(page, locale);
        await page.goto(`/workflows/${id(workflow)}`);
        if (!isMobile) await page.getByRole('button', { name: copy('workspace', 'WorkspaceWorkflows.authoringMode.outline'), exact: true }).click();
        const outline = page.getByRole('list', { name: copy('workspace', 'WorkspaceWorkflows.outlineLabel'), exact: true });
        const step = outline.getByRole('button').filter({ hasText: 'Sakura' });
        await expect(step).toContainText(copy('records', 'SmartSegments.recordName.person'));
        const nameCondition = copy('records', 'SmartSegments.chipField')
            .replace('{field}', copy('records', 'SmartSegments.recordName.person'))
            .replace('{op}', copy('records', 'SmartSegments.op.isNot')).replace('{value}', 'Sakura');
        const companyCondition = copy('records', 'SmartSegments.chipField')
            .replace('{field}', copy('records', 'SmartSegments.field.company'))
            .replace('{op}', copy('records', 'SmartSegments.op.notIn')).replace('{value}', fixture.companies.primary.name);
        const summary = copy('records', 'SmartSegments.summaryNot').replace('{conditions}', nameCondition + copy('records', 'SmartSegments.summaryAnd') + companyCondition);
        await expect(step).toContainText(summary);
        await step.click();
        await page.getByRole('button', { name: copy('records', 'SmartSegments.setCondition'), exact: false }).click();
        const builder = page.getByRole('dialog', { name: copy('records', 'SmartSegments.conditionTitle'), exact: true });
        await expect(builder).toContainText(copy('records', 'SmartSegments.conditionDescription.person'));
        await expect(builder).toContainText(copy('records', 'SmartSegments.recordName.person'));
        await expect(builder).toContainText(copy('records', 'SmartSegments.linkedCompanyHint'));
        await builder.getByRole('button', { name: copy('records', 'SmartSegments.done'), exact: true }).click();
        const saved = await page.request.get(`/api/workflows/${id(workflow)}`, { headers: { 'X-Workspace-Id': String(fixture.workspaceId) } });
        expect(saved.ok()).toBe(true);
        expect(await saved.json()).toMatchObject({ definition: { nodes: expect.arrayContaining([{ id: 'condition', type: 'CONDITION', config }]) } });
    });
}
