import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';

import { activeWorkspaceId, csrfBootstrap, registerUser, seeder } from './support/api';
import { useLocale } from './support/locale';
import { message } from './support/messages';

test.use({ storageState: { cookies: [], origins: [] } });

for (const locale of ['en', 'ja'] as const) {
    test.describe(`Japanese search (${locale})`, () => {
        test.beforeEach(async ({ page }) => {
            const key = randomUUID();
            await registerUser(page.request, {
                username: `search_${key.replaceAll('-', '')}`,
                password: `Search!${key}A1`,
                email: `search-${key}@example.invalid`,
            });
            await useLocale(page, locale);
        });

        test('list and global search find name variants and formatted phone numbers', async ({ page }) => {
            const workspaceId = await activeWorkspaceId(page.request);
            const seed = seeder(page.request, workspaceId, await csrfBootstrap(page.request));
            const name = 'ﾔﾏﾀﾞ\u3000ﾀﾛｳ';
            await seed.post('/api/persons', { name, phone: '+81-90-9684-6208' });
            await page.goto('/records/contacts');
            const listInput = page.getByPlaceholder(message(locale, 'contacts', 'ContactsBrowser.searchPlaceholder'));
            for (const query of ['やまだたろう', 'ヤマダ タロウ', '09096846208', '96846208']) {
                const responsePromise = page.waitForResponse(response => {
                    const url = new URL(response.url());
                    return url.pathname === '/api/persons/page' && url.searchParams.get('q') === query;
                });
                await listInput.fill(query);
                const response = await responsePromise;
                expect(response.ok()).toBe(true);
                expect(await response.json()).toMatchObject({ total: 1 });
                await expect(page.getByRole('row').filter({ hasText: name })).toBeVisible();
            }
            const input = page.getByPlaceholder(message(locale, 'common', 'CommonSearchBar.placeholder'));
            for (const query of ['ヤマダタロウ', '090 9684 6208']) {
                await input.fill(query);
                await expect(page.getByRole('option').filter({ hasText: name })).toBeVisible();
            }
        });

        test('inline search waits for composition and ignores IME Enter and Escape', async ({ page }) => {
            await page.goto('/dashboard');
            const input = page.getByPlaceholder(message(locale, 'common', 'CommonSearchBar.placeholder'));
            await expect(input).toBeVisible();
            const initialUrl = page.url();
            const queries: string[] = [];
            page.on('request', request => {
                if (new URL(request.url()).pathname === '/api/search') {
                    queries.push(new URL(request.url()).searchParams.get('query') ?? '');
                }
            });
            await input.dispatchEvent('compositionstart');
            await input.fill('山田');
            await input.dispatchEvent('keydown', { key: 'Enter', keyCode: 13, isComposing: true });
            await input.press('Enter');
            await input.press('Escape');
            await expect(input).toHaveAttribute('aria-expanded', 'true');
            await expect(page.getByText(message(locale, 'common', 'CommonSearchBar.noResults')
                .replace('{query}', '山田'), { exact: true })).toBeHidden();
            await page.evaluate(() => new Promise(resolve => setTimeout(resolve, 400)));
            expect(queries).toEqual([]);
            expect(page.url()).toBe(initialUrl);
            await input.fill('山田 太郎');
            await input.dispatchEvent('compositionend', { data: '山田 太郎' });
            await input.dispatchEvent('keydown', { key: 'Enter', keyCode: 229, isComposing: false });
            expect(page.url()).toBe(initialUrl);
            await expect.poll(() => queries).toEqual(['山田 太郎']);
            await input.press('Enter');
            await expect(page).toHaveURL(/\/search\?query=/);
            expect(new URL(page.url()).searchParams.get('query')).toBe('山田 太郎');
        });

        test('command palette keeps IME confirmation and cancellation inside the input', async ({ page }) => {
            const workspaceId = await activeWorkspaceId(page.request);
            const seed = seeder(page.request, workspaceId, await csrfBootstrap(page.request));
            const name = '山田太郎';
            const contact = await seed.post('/api/persons', { name });
            const contactId = contact.id;
            if (typeof contactId !== 'number') throw new Error('Seeded contact has no numeric id');
            await page.goto('/dashboard');
            const dialog = page.getByRole('dialog');
            await expect.poll(async () => {
                await page.evaluate(() => window.dispatchEvent(new Event('connex:open-search')));
                return dialog.isVisible();
            }).toBe(true);
            const input = dialog.getByRole('combobox');
            const result = dialog.getByRole('option').filter({ hasText: name });
            await expect(input).toBeFocused();
            await input.fill('山田');
            await expect(result).toHaveAttribute('aria-selected', 'true');
            const initialUrl = page.url();
            const queries: string[] = [];
            page.on('request', request => {
                const url = new URL(request.url());
                if (url.pathname === '/api/search') queries.push(url.searchParams.get('query') ?? '');
            });
            await input.dispatchEvent('keydown', { key: 'Enter', keyCode: 13, isComposing: true });
            await input.dispatchEvent('keydown', { key: 'Enter', keyCode: 229 });
            await expect(result).toHaveAttribute('aria-selected', 'true');
            await expect(dialog).toBeVisible();
            expect(page.url()).toBe(initialUrl);
            await input.dispatchEvent('compositionstart');
            await input.fill('山田 太郎');
            await input.press('Enter');
            await input.press('Escape');
            await input.press('Tab');
            await expect(input).toBeFocused();
            await page.evaluate(() => new Promise(resolve => setTimeout(resolve, 400)));
            expect(queries).toEqual([]);
            await expect(dialog).toBeVisible();
            expect(page.url()).toBe(initialUrl);
            const committedResponse = page.waitForResponse(response => {
                const url = new URL(response.url());
                return url.pathname === '/api/search' && url.searchParams.get('query') === '山田 太郎';
            });
            await input.dispatchEvent('compositionend', { data: '山田 太郎' });
            expect((await committedResponse).ok()).toBe(true);
            await expect(result).toHaveAttribute('aria-selected', 'true');
            await input.dispatchEvent('keydown', { key: 'Enter', keyCode: 229 });
            await input.dispatchEvent('keydown', { key: 'Escape', keyCode: 229 });
            await expect(dialog).toBeVisible();
            expect(page.url()).toBe(initialUrl);
            await page.evaluate(() => new Promise(resolve => setTimeout(resolve, 400)));
            expect(queries).toEqual(['山田 太郎']);
            await input.press('Escape');
            await expect(dialog).toBeHidden();
            await page.keyboard.press('ControlOrMeta+k');
            await expect(result).toHaveAttribute('aria-selected', 'true');
            await input.press('Enter');
            await expect(page).toHaveURL(new RegExp(`/records/contacts/${contactId}$`));
        });
    });
}
