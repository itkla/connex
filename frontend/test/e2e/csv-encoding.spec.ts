import { expect, test, type Page } from '@playwright/test';

import { registerUser, activeWorkspaceId, csrfBootstrap, seeder } from './support/api';
import { useLocale as setLocale } from './support/locale';
import { message } from './support/messages';

type Locale = 'en' | 'ja';
type Encoding = 'utf-8' | 'utf-8-bom' | 'shift_jis';

const japaneseName = [0x8e, 0x52, 0x93, 0x63, 0x91, 0xbe, 0x98, 0x59];
const windowsCompany = [0x87, 0x8a, 0x83, 0x65, 0x83, 0x58, 0x83, 0x67];

test.use({ storageState: { cookies: [], origins: [] } });
test.setTimeout(120_000);

test.beforeEach(async ({ page }) => {
    const key = `csv${Date.now()}${Math.random().toString(36).slice(2, 7)}`;
    await registerUser(page.request, {
        username: key,
        password: `CsvHarness!${key}A1`,
        email: `${key}@example.invalid`,
    });
});

function text(locale: Locale, key: string): string {
    return message(locale, 'importExport', `importExport.${key}`);
}

function encodingNotice(locale: Locale, encoding: Encoding): string {
    return text(locale, 'detectedEncoding').replace(
        '{encoding}', text(locale, `encoding.${encoding === 'shift_jis' ? 'shift_jis' : 'utf-8'}`),
    );
}

function encodedJapanese(encoding: Encoding, value: string, bytes: number[]): Buffer {
    return encoding === 'shift_jis' ? Buffer.from(bytes) : Buffer.from(value, 'utf8');
}

function bom(encoding: Encoding): Buffer {
    return Buffer.from(encoding === 'utf-8-bom' ? [0xef, 0xbb, 0xbf] : []);
}

async function openImport(page: Page, locale: Locale, history: boolean): Promise<void> {
    await setLocale(page, locale);
    if (history) {
        await page.goto('/settings/workspace/data-privacy');
        await page.getByRole('button', {
            name: message(locale, 'workspace', 'WorkspaceData.historyAction'),
        }).click();
    } else {
        await page.goto('/records/contacts?view=table');
        await page.getByRole('button', { name: text(locale, 'moreActions') }).click();
        await page.getByRole('menuitem', { name: text(locale, 'openImport') }).click();
    }
    await expect(page.getByRole('heading', {
        name: text(locale, history ? 'history.title' : 'title.persons'),
    })).toBeVisible();
}

for (const encoding of ['utf-8', 'utf-8-bom', 'shift_jis'] as const) {
    test(`imports contacts and companies losslessly from ${encoding}`, async ({ page }) => {
        const errors: string[] = [];
        page.on('pageerror', (error) => errors.push(error.message));
        page.on('console', (entry) => {
            if (entry.type() === 'error') errors.push(entry.text());
        });
        await openImport(page, 'en', false);
        await page.locator('input[type="file"]').setInputFiles({
            name: `contacts-${encoding}.csv`,
            mimeType: 'text/csv',
            buffer: Buffer.concat([
                bom(encoding), Buffer.from('Name,Company\r\n'),
                encodedJapanese(encoding, '山田太郎', japaneseName), Buffer.from(','),
                encodedJapanese(encoding, '㈱テスト', windowsCompany), Buffer.from('\r\n'),
            ]),
        });

        await expect(page.getByText(encodingNotice('en', encoding))).toBeVisible();
        await expect(page.getByText('山田太郎', { exact: true })).toBeVisible();
        await expect(page.getByText('㈱テスト', { exact: true })).toBeVisible();
        await page.getByRole('button', { name: text('en', 'next'), exact: true }).click();
        await expect(page.getByText(encodingNotice('en', encoding))).toBeVisible();
        await expect(page.getByText('山田太郎', { exact: true })).toBeVisible();
        await page.getByRole('button', { name: text('en', 'import'), exact: true }).click();
        await expect(page.getByText(text('en', 'doneTitle'), { exact: true })).toBeVisible();

        const contacts = await page.request.get('/api/persons');
        const companies = await page.request.get('/api/companies');
        expect(contacts.ok()).toBe(true);
        expect(companies.ok()).toBe(true);
        expect(await contacts.text()).toContain('山田太郎');
        expect(await companies.text()).toContain('㈱テスト');
        expect(errors).toEqual([]);
    });

    test(`imports Japanese history through the shared parser from ${encoding}`, async ({ page }) => {
        const errors: string[] = [];
        page.on('pageerror', (error) => errors.push(error.message));
        page.on('console', (entry) => {
            if (entry.type() === 'error') errors.push(entry.text());
        });
        if (encoding === 'shift_jis') {
            await page.setViewportSize({ width: 390, height: 844 });
            await page.emulateMedia({ colorScheme: 'dark' });
        }
        const workspaceId = await activeWorkspaceId(page.request);
        const seed = seeder(page.request, workspaceId, await csrfBootstrap(page.request));
        await seed.post('/api/persons', { name: '山田太郎', email: 'history@example.invalid' });
        await openImport(page, 'ja', true);
        await page.locator('input[type="file"]').setInputFiles({
            name: `history-${encoding}.csv`,
            mimeType: 'text/csv',
            buffer: Buffer.concat([
                bom(encoding),
                Buffer.from('Occurred At,Participant Email,Source ID,Subject\n2026-01-12 09:30:00,history@example.invalid,csv-encoding,'),
                encodedJapanese(encoding, '㈱テスト', windowsCompany), Buffer.from('\n'),
            ]),
        });
        await expect(page.getByText(encodingNotice('ja', encoding))).toBeVisible();
        await expect(page.getByText('㈱テスト', { exact: true })).toBeVisible();
        await page.getByRole('button', { name: text('ja', 'history.review'), exact: true }).click();
        await expect(page.getByText(encodingNotice('ja', encoding))).toBeVisible();
        await expect(page.getByLabel(`${text('ja', 'history.counts.ready')}: 1`)).toBeVisible();
        await page.getByRole('button', { name: text('ja', 'history.import'), exact: true }).click();
        await expect(page.getByText(text('ja', 'history.doneTitle'), { exact: true })).toBeVisible();
        const activities = await page.request.get('/api/activities');
        expect(activities.ok()).toBe(true);
        expect(await activities.text()).toContain('㈱テスト');
        expect(errors).toEqual([]);
    });
}

for (const locale of ['en', 'ja'] as const) {
    for (const history of [false, true]) {
        test(`refuses undecodable ${history ? 'history' : 'contacts'} files before any writes in ${locale}`, async ({ page }) => {
            const writes: string[] = [];
            await openImport(page, locale, history);
            const beforeContacts = await (await page.request.get('/api/persons')).text();
            const beforeCompanies = await (await page.request.get('/api/companies')).text();
            const beforeActivities = await (await page.request.get('/api/activities')).text();
            page.on('request', (request) => {
                if (request.method() === 'POST' && new URL(request.url()).pathname.startsWith('/api/imports/')) {
                    writes.push(request.url());
                }
            });

            for (const buffer of [
                Buffer.concat([Buffer.from('Name,Company\n'), Buffer.from([0x81])]),
                Buffer.from('Name,Company\n山田太郎,\uFFFD\n', 'utf8'),
            ]) {
                await page.locator('input[type="file"]').setInputFiles({
                    name: 'invalid.csv', mimeType: 'text/csv', buffer,
                });
                await expect(page.getByText(text(locale, 'errorEncoding'))).toBeVisible();
                await expect(page.getByRole('button', {
                    name: text(locale, history ? 'history.review' : 'next'), exact: true,
                })).toHaveCount(0);
            }

            expect(writes).toEqual([]);
            expect(await (await page.request.get('/api/persons')).text()).toBe(beforeContacts);
            expect(await (await page.request.get('/api/companies')).text()).toBe(beforeCompanies);
            expect(await (await page.request.get('/api/activities')).text()).toBe(beforeActivities);
        });
    }
}
