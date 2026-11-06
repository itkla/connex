import { expect, test, type Page } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import type { WorkflowRunDetail } from '@/app/lib/types';
import { useLocale } from './support/locale';
import { message } from './support/messages';

const editors = [
    { key: 'report', path: '/insights/reports/new', destination: '/insights/reports', namespace: 'reports', name: 'Reports.builder.name', back: 'Reports.common.cancel' },
    { key: 'template', path: '/library/documents/new', destination: '/library/documents', namespace: 'document-templates', name: 'DocumentTemplateBuilder.name', back: 'DocumentTemplateBuilder.back' },
    { key: 'workflow', path: '/workflows/new', destination: '/workflows', namespace: 'workspace', name: 'WorkspaceWorkflows.nameLabel', back: 'WorkspaceWorkflows.backToList' },
] as const;

/** Records native event delivery and capture interception in CI traces without patching history. */
async function observeHistory(page: Page) {
    await page.addInitScript(() => {
        const bubbleReached = new WeakMap<Event, boolean>();
        let sequence = 0;
        window.addEventListener('popstate', (event) => {
            const destination = window.location.pathname + window.location.search + window.location.hash;
            const state: unknown = event.state;
            const eventNumber = ++sequence;
            console.info('[editor-history]', JSON.stringify({
                phase: 'received', sequence: eventNumber, destination, trusted: event.isTrusted,
                stateKind: state === null ? 'null' : typeof state,
                nativeIndex: window.navigation?.currentEntry?.index ?? null,
            }));
            bubbleReached.set(event, false);
            setTimeout(() => {
                const editor = [...document.querySelectorAll('#report-name')]
                    .find((element) => element instanceof HTMLElement && element.getClientRects().length > 0);
                console.info('[editor-history]', JSON.stringify({
                    phase: 'after-dispatch', sequence: eventNumber,
                    destination,
                    afterDispatch: window.location.pathname + window.location.search + window.location.hash,
                    bubbleReached: bubbleReached.get(event),
                    nextState: state !== null && typeof state === 'object' && '__NA' in state && state.__NA === true,
                    editorVisible: editor instanceof HTMLElement && editor.getClientRects().length > 0,
                    draftPresent: editor instanceof HTMLInputElement && editor.value.length > 0,
                }));
            });
        }, true);
        window.addEventListener('popstate', (event) => { bubbleReached.set(event, true); });
    });
}

for (const locale of ['en', 'ja'] as const) {
    test(`an untouched template returns to Documents without a discard prompt in ${locale} @mobile`, async ({ page }) => {
        await useLocale(page, locale);
        await page.goto('/library/documents/new');
        await expect(page.locator('.tiptap')).toBeVisible();
        await page.getByRole('button', { name: message(locale, 'document-templates', 'DocumentTemplateBuilder.back'), exact: true }).click();
        await expect(page).toHaveURL('/library/documents');
        await expect(page.getByRole('dialog')).toHaveCount(0);
    });
    for (const editor of editors) {
        test(`${editor.key} keeps dirty content and confirms its return destination in ${locale} @mobile`, async ({ page }) => {
            await useLocale(page, locale);
            await page.goto(editor.path);
            const input = page.getByRole('textbox', { name: message(locale, editor.namespace, editor.name), exact: true });
            const back = page.getByRole('button', { name: message(locale, editor.namespace, editor.back), exact: true });
            const dialog = page.getByRole('dialog', { name: message(locale, 'common', 'ConfirmDiscard.title') });
            const draft = `Unsaved ${editor.key}`;
            await input.fill(draft);
            await back.click();
            await expect(dialog).toBeVisible();
            await dialog.getByRole('button', { name: message(locale, 'common', 'ConfirmDiscard.keepEditing') }).click();
            await expect(input).toHaveValue(draft);
            await expect(back).toBeFocused();
            await expect(page).toHaveURL(editor.path);

            const warning = page.waitForEvent('dialog');
            const reload = page.evaluate(() => window.location.reload());
            const browserDialog = await warning;
            expect(browserDialog.type()).toBe('beforeunload');
            await browserDialog.dismiss();
            await reload;
            await expect(input).toHaveValue(draft);

            const closeWarning = page.waitForEvent('dialog');
            await page.close({ runBeforeUnload: true });
            const closeDialog = await closeWarning;
            expect(closeDialog.type()).toBe('beforeunload');
            await closeDialog.dismiss();
            expect(page.isClosed()).toBe(false);
            await expect(input).toHaveValue(draft);

            await back.click();
            await dialog.getByRole('button', { name: message(locale, 'common', 'ConfirmDiscard.discard') }).click();
            await expect(page).toHaveURL(editor.destination);
        });
    }
}

test('a cross-document docs history jump confirms leave and renders the destination', async ({ page }) => {
    await page.goto('/docs');
    await page.locator('aside a[href="/docs/getting-started"]:visible').click();
    await expect(page).toHaveURL('/docs/getting-started');
    await page.locator('main a[href="/docs/getting-started/quickstart"]').click();
    await expect(page).toHaveURL('/docs/getting-started/quickstart');
    const articleUrl = page.url();
    await page.goto('/dashboard');
    await expect(page).toHaveURL('/dashboard');
    await page.locator('a[href="/insights/reports"]:visible').first().click();
    await page.locator('a[href="/insights/reports/new"]').first().click();
    await page.getByRole('textbox', { name: 'Name', exact: true }).fill('Public documentation history jump');
    const warning = page.waitForEvent('dialog');
    const jump = page.evaluate(() => window.history.go(-3));
    const nativeWarning = await warning;
    expect(nativeWarning.type()).toBe('beforeunload');
    await nativeWarning.accept();
    await jump;
    await expect(page).toHaveURL(articleUrl);
    await expect(page.getByRole('heading', { name: message('en', 'docs', 'DocsGettingStarted.articles.quickstart.title'), level: 1 })).toBeVisible();
    await expect(page.getByRole('textbox', { name: 'Name', exact: true })).toHaveCount(0);
    await expect(page.getByRole('dialog', { name: 'Discard unsaved changes?' })).toHaveCount(0);
});

test('a native skip-link entry followed by go(-2) keeps the list URL and rendered route synchronized', async ({ page }) => {
    await observeHistory(page);
    await page.addInitScript(() => Object.defineProperty(window, 'navigation', { configurable: true, value: undefined }));
    await page.goto('/insights/reports');
    await page.locator('a[href="/insights/reports/new"]').first().click();
    await page.getByRole('textbox', { name: 'Name', exact: true }).fill('Skip-link history draft');
    const skip = page.locator('a[href="#app-main"]');
    await skip.focus();
    await skip.press('Enter');
    await expect(page).toHaveURL('/insights/reports/new#app-main');
    await page.evaluate(() => window.history.go(-2));
    const discard = page.getByRole('dialog', { name: 'Discard unsaved changes?' });
    await expect(discard).toBeVisible();
    await expect(page).toHaveURL('/insights/reports/new#app-main');
    await discard.getByRole('button', { name: 'Discard changes', exact: true }).click();
    await expect(page).toHaveURL('/insights/reports');
    await expect(page.getByRole('textbox', { name: 'Name', exact: true })).toHaveCount(0);
    await expect(page.getByRole('dialog', { name: 'Discard unsaved changes?' })).toHaveCount(0);
});

test('Back to a native null-state skip-link entry renders its list after Discard', async ({ page }) => {
    await page.addInitScript(() => Object.defineProperty(window, 'navigation', { configurable: true, value: undefined }));
    await page.goto('/insights/reports');
    const skip = page.locator('a[href="#app-main"]');
    await skip.focus();
    await skip.press('Enter');
    await expect(page).toHaveURL('/insights/reports#app-main');
    expect(await page.evaluate(() => window.history.state ?? null)).toBeNull();
    await page.locator('a[href="/insights/reports/new"]').first().click();
    await page.getByRole('textbox', { name: 'Name', exact: true }).fill('Back to native fragment');
    await page.evaluate(() => window.history.back());
    const discard = page.getByRole('dialog', { name: 'Discard unsaved changes?' });
    await expect(discard).toBeVisible();
    await expect(page).toHaveURL('/insights/reports/new');
    await discard.getByRole('button', { name: 'Discard changes', exact: true }).click();
    await expect(page).toHaveURL('/insights/reports#app-main');
    await expect(page.getByRole('textbox', { name: 'Name', exact: true })).toHaveCount(0);
});

test('searching for the current workflow leaves its draft and departure protection intact', async ({ page }) => {
    const savedName = `Search guard ${randomUUID()}`;
    await page.goto('/workflows/new');
    const name = page.getByRole('textbox', { name: 'Workflow name', exact: true });
    await name.fill(savedName);
    await page.getByRole('button', { name: 'Save draft', exact: true }).click();
    await expect(page).toHaveURL(/\/workflows\/\d+$/);
    await expect(name).toHaveValue(savedName);
    await name.fill(`${savedName} edited`);
    await page.getByPlaceholder('Search for anything').fill(savedName);
    await page.getByRole('option', { name: new RegExp(savedName) }).first().click();
    await expect(page.getByRole('dialog', { name: 'Discard unsaved changes?' })).toHaveCount(0);
    await expect(name).toHaveValue(`${savedName} edited`);

    const warning = page.waitForEvent('dialog');
    const reload = page.evaluate(() => window.location.reload());
    const nativeWarning = await warning;
    expect(nativeWarning.type()).toBe('beforeunload');
    await nativeWarning.dismiss();
    await reload;
    await expect(name).toHaveValue(`${savedName} edited`);
    await page.locator('a[href="/records/deals"]:visible').first().click();
    const discard = page.getByRole('dialog', { name: 'Discard unsaved changes?' });
    await expect(discard).toBeVisible();
    await discard.getByRole('button', { name: 'Keep editing' }).click();
    await expect(name).toHaveValue(`${savedName} edited`);
});

test('report Cancel from a direct link returns to reports without browser history', async ({ page }) => {
    await page.goto('/insights/reports/new');
    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    await expect(page).toHaveURL('/insights/reports');
    await expect(page.getByRole('dialog')).toHaveCount(0);
});

test('Keep editing preserves a known Forward destination and its deal search', async ({ page }) => {
    await page.goto('/insights/reports');
    await page.locator('a[href="/insights/reports/new"]').first().click();
    await page.locator('a[href="/records/deals"]:visible').first().click();
    await expect(page).toHaveURL('/records/deals');
    const search = page.getByPlaceholder(message('en', 'deals', 'DealsBrowser.searchPlaceholder'), { exact: true });
    await search.fill('History destination');
    await expect.poll(() => new URL(page.url()).searchParams.get('q')).toBe('History destination');
    const destination = page.url();
    expect(await page.evaluate(() => window.navigation?.currentEntry?.index)).toBeGreaterThanOrEqual(1);
    await page.goBack();
    const name = page.getByRole('textbox', { name: 'Name', exact: true });
    await name.fill('Preserved Forward draft');
    await page.evaluate(() => window.history.forward());
    const dialog = page.getByRole('dialog', { name: 'Discard unsaved changes?' });
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: 'Keep editing', exact: true }).click();
    await expect(name).toHaveValue('Preserved Forward draft');
    await expect(page).toHaveURL('/insights/reports/new');
    await page.evaluate(() => window.history.forward());
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: 'Discard changes', exact: true }).click();
    await expect(page).toHaveURL(destination);
    await expect(search).toHaveValue('History destination');
});

test('Discard after removing a workflow inspection query protects the retained draft again', async ({ page }) => {
    const savedName = `Inspection guard ${randomUUID()}`;
    await page.goto('/workflows/new');
    const name = page.getByRole('textbox', { name: 'Workflow name', exact: true });
    await name.fill(savedName);
    await page.getByRole('button', { name: 'Save draft', exact: true }).click();
    await expect(page).toHaveURL(/\/workflows\/\d+$/);
    const workflowUrl = page.url();
    const id = Number(new URL(workflowUrl).pathname.split('/').at(-1));
    const run = {
        workflowId: id, runKey: 'canonical-1', source: 'canonical', status: 'succeeded', legacyStatus: null,
        trigger: null, runtimeState: null, startedAt: '2026-11-05T00:00:00Z', finishedAt: null,
        durationMs: null, failure: null, stepDetailAvailable: false, execution: null, path: [],
        version: {
            id: 1, number: 1, definitionHash: 'inspection-fixture', publishedAt: '2026-11-05T00:00:00Z',
            definition: { schemaVersion: 1, entryNodeId: 'end', nodes: [{ id: 'end', type: 'END' }], edges: [] },
            canvas: { positions: { end: { x: 80, y: 40 } }, viewport: { x: 0, y: 0, zoom: 1 } },
        },
    } satisfies WorkflowRunDetail;
    await page.route(`**/api/workflows/${id}/runs/canonical-1`, (route) => route.fulfill({ json: run }));
    await page.evaluate(() => window.history.replaceState(null, '', `${window.location.pathname}?runKey=canonical-1`));
    await page.getByRole('button', { name: 'Return to draft', exact: true }).click();
    await name.fill(`${savedName} edited`);
    await page.getByPlaceholder('Search for anything').fill(savedName);
    await page.getByRole('option', { name: new RegExp(savedName) }).first().click();
    const discard = page.getByRole('dialog', { name: 'Discard unsaved changes?' });
    await expect(discard).toBeVisible();
    await discard.getByRole('button', { name: 'Discard changes', exact: true }).click();
    await expect(page).toHaveURL(workflowUrl);
    await expect(name).toHaveValue(`${savedName} edited`);
    const warning = page.waitForEvent('dialog');
    const reload = page.evaluate(() => window.location.reload());
    const nativeWarning = await warning;
    expect(nativeWarning.type()).toBe('beforeunload');
    await nativeWarning.dismiss();
    await reload;
    await expect(name).toHaveValue(`${savedName} edited`);
    await page.locator('a[href="/records/deals"]:visible').first().click();
    await expect(discard).toBeVisible();
    await discard.getByRole('button', { name: 'Discard changes', exact: true }).click();
    await expect(page).toHaveURL('/records/deals');
});

for (const navigationApi of [true, false]) {
test(`sidebar and browser Back/Forward retain a report until discard is confirmed (Navigation API: ${navigationApi})`, async ({ page }) => {
    await observeHistory(page);
    if (!navigationApi) {
        await page.addInitScript(() => Object.defineProperty(window, 'navigation', { configurable: true, value: undefined }));
    }
    await page.goto('/insights/reports');
    await page.locator('a[href="/insights/reports/new"]').first().click();
    const input = page.getByRole('textbox', { name: 'Name', exact: true });
    const dialog = page.getByRole('dialog', { name: 'Discard unsaved changes?' });
    await input.fill('Report kept after Back');
    await page.evaluate(() => window.history.back());
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: 'Keep editing' }).click();
    await expect(input).toHaveValue('Report kept after Back');
    await expect(input).toBeFocused();
    await expect(page).toHaveURL('/insights/reports/new');
    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    await dialog.getByRole('button', { name: 'Discard changes', exact: true }).click();
    await expect(page).toHaveURL('/insights/reports');
    await page.locator('a[href="/insights/reports/new"]').first().click();
    await input.fill('Report discarded after Back');
    await page.evaluate(() => window.history.back());
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: 'Discard changes', exact: true }).click();
    await expect(page).toHaveURL('/insights/reports');
    await page.goForward();
    await expect(input).toBeVisible();

    await input.fill('Sidebar draft');
    const deals = page.locator('a[href="/records/deals"]:visible').first();
    await deals.click();
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: 'Keep editing' }).click();
    await expect(input).toHaveValue('Sidebar draft');
    await expect(deals).toBeFocused();
    await deals.click();
    await dialog.getByRole('button', { name: 'Discard changes', exact: true }).click();
    await expect(page).toHaveURL('/records/deals');
    await page.goBack();
    await expect(input).toBeVisible();
    await input.fill('Report kept after Forward');
    await page.evaluate(() => window.history.forward());
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: 'Keep editing' }).click();
    await expect(input).toHaveValue('Report kept after Forward');
    await expect(input).toBeFocused();
    await expect(page).toHaveURL('/insights/reports/new');
    await deals.click();
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: 'Discard changes', exact: true }).click();
    await expect(page).toHaveURL('/records/deals');
    await page.goBack();
    await input.fill('Report discarded after Forward');
    await page.evaluate(() => window.history.forward());
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: 'Discard changes', exact: true }).click();
    await expect(page).toHaveURL('/records/deals');
});
}
