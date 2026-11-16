import { expect, test, type Locator, type Page } from '@playwright/test';

import type { Company, Contact, Deal, RelationshipTemperature } from '../../app/lib/types';
import { runFixture } from './support/fixtures';
import { useLocale } from './support/locale';
import { message } from './support/messages';

type Resource = 'contacts' | 'companies' | 'deals';

function record(resource: Resource, id: number, name: string, title: string): Contact | Company | Deal {
    const shared = { id, name, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' };
    if (resource === 'contacts') return { ...shared, email: 'alexandra@example.test', title, phone: '', imageUrl: '' };
    if (resource === 'companies') return { ...shared, website: '', industry: '', phone: '', address: '', logoUrl: '' };
    return { ...shared, value: 100, actualValue: 0, currency: 'USD', pipeline: null, stage: null, position: 0, company: null };
}

async function stubRecords(page: Page, resource: Resource, id: number) {
    const state = { name: 'Alexandra Tanaka', title: 'Account Director' };
    const endpoint = resource === 'contacts' ? 'persons' : resource;
    await page.route(`**/api/${endpoint}/page?*`, (route) => route.fulfill({
        json: { items: [record(resource, id, state.name, state.title)], total: 1 },
    }));
    const temperature: RelationshipTemperature = { id, score: 0, band: 'cold', trend: 'steady', touchCount: 0 };
    if (resource === 'contacts') {
        await page.route('**/api/scoring/contacts?*', (route) => route.fulfill({ json: [temperature] }));
    } else if (resource === 'companies') {
        await page.route('**/api/scoring/companies/batch', (route) => route.fulfill({ json: [temperature] }));
    } else {
        await page.route('**/api/deals/risk?*', (route) => route.fulfill({ json: [] }));
        await page.route('**/api/deals/people/primary?*', (route) => route.fulfill({ json: [] }));
    }
    await page.route('**/api/comment-threads/indicators?*', (route) => route.fulfill({ json: [] }));
    return state;
}

async function waitForTable(page: Page, table: Locator, resource: Resource, name: string) {
    await expect(table.getByText(name, { exact: true })).toBeVisible();
    if (resource !== 'deals') await expect(table.getByText(message('en', 'common', 'Temperature.noHistory'), { exact: true })).toBeVisible();
    await page.evaluate(() => document.fonts.ready);
}

async function columnWidths(table: Locator) {
    return table.locator('thead th').evaluateAll((cells) => cells.map((cell) => cell.getBoundingClientRect().width));
}

async function assertStickyAlignment(table: Locator) {
    const scroller = table.locator('..');
    await scroller.evaluate((element) => { element.scrollLeft = element.scrollWidth; });
    await expect.poll(() => scroller.evaluate((element) => element.scrollLeft)).toBeGreaterThan(0);
    const header = table.locator('thead th').nth(2);
    const name = table.locator('tbody tr').first().locator('td').nth(2);
    await expect.poll(async () => {
        const positions = await Promise.all([header.boundingBox(), name.boundingBox()]);
        const [head, body] = positions;
        return head && body ? Math.abs(head.x - body.x) : Infinity;
    }).toBeLessThanOrEqual(1);
    const bounds = await name.boundingBox();
    const scrollBounds = await scroller.boundingBox();
    expect(bounds).not.toBeNull();
    expect(scrollBounds).not.toBeNull();
    if (!bounds || !scrollBounds) throw new Error('Missing sticky cell bounds');
    expect(bounds.x).toBeGreaterThanOrEqual(scrollBounds.x);
    expect(bounds.x + bounds.width).toBeLessThan(scrollBounds.x + scrollBounds.width);
}

for (const resource of ['contacts', 'companies', 'deals'] as const) {
    test(`${resource}: A11Y-25 REC-21 IMP-36 long values stay bounded and frozen columns align`, async ({ page }, testInfo) => {
        const fixture = runFixture(testInfo.project.name);
        const id = resource === 'contacts' ? fixture.contacts.peek.id : resource === 'companies' ? fixture.companies.primary.id : fixture.deals.primary.id;
        const state = await stubRecords(page, resource, id);
        await useLocale(page, 'en');
        await page.setViewportSize({ width: 1280, height: 900 });
        await page.goto(`/records/${resource}?view=table&q=width-check`);
        const table = page.locator('table[data-density]');
        await waitForTable(page, table, resource, state.name);
        await expect(table.locator('thead th').nth(2)).toHaveCSS('max-width', '320px');
        const normalWidths = await columnWidths(table);

        state.name = 'N'.repeat(300);
        state.title = 'T'.repeat(300);
        await page.reload();
        await waitForTable(page, table, resource, state.name);
        const nameCell = table.locator('tbody tr').first().locator('td').nth(2);
        const fullName = nameCell.getByTitle(state.name, { exact: true });
        await expect(fullName).toBeVisible();
        const text = fullName.locator('span.truncate').first();
        expect(await text.evaluate((element) => element.scrollWidth > element.clientWidth)).toBe(true);
        expect((await nameCell.boundingBox())?.width).toBeLessThanOrEqual(352);
        if (resource === 'contacts') {
            const fullTitle = table.getByTitle(state.title, { exact: true });
            await expect(fullTitle).toBeAttached();
            expect(await fullTitle.evaluate((element) => element.scrollWidth > element.clientWidth)).toBe(true);
            await expect(fullTitle.locator('xpath=ancestor::td')).toHaveCSS('max-width', '256px');
        }
        const longWidths = await columnWidths(table);
        const titleIndex = resource === 'contacts' ? 7 : -1;
        for (const [index, width] of longWidths.entries()) {
            if (index !== 2 && index !== titleIndex) expect(width).toBeLessThanOrEqual((normalWidths[index] ?? 0) + 1);
        }
        await assertStickyAlignment(table);
    });
}

for (const locale of ['en', 'ja'] as const) {
    test(`A11Y-26 ${locale} contacts retain eight visible name characters at 320px @mobile`, async ({ page }, testInfo) => {
        const id = runFixture(testInfo.project.name).contacts.peek.id;
        const state = await stubRecords(page, 'contacts', id);
        if (locale === 'ja') state.name = '長谷川アレクサンダー';
        await useLocale(page, locale);
        await page.setViewportSize({ width: 320, height: 800 });
        await page.goto('/records/contacts?view=table');
        const row = page.locator(`[data-record-row-id="${id}"]`);
        const name = row.getByTitle(state.name, { exact: true });
        const warmth = row.getByText(message(locale, 'common', 'Temperature.noHistory'), { exact: true });
        await expect(name).toBeVisible();
        await expect(warmth).toBeVisible();
        await page.evaluate(() => document.fonts.ready);
        const visibleCharacters = await name.evaluate((element) => {
            const text = element.firstChild;
            if (!text) return 0;
            const available = element.getBoundingClientRect();
            const styles = getComputedStyle(element);
            const lineHeight = Number.parseFloat(styles.lineHeight);
            const clipped = element.scrollWidth > element.clientWidth || element.scrollHeight > element.clientHeight + 1;
            const ellipsisWidth = clipped ? Number.parseFloat(styles.fontSize) : 0;
            let visible = 0;
            let offset = 0;
            for (const character of Array.from(text.textContent ?? '')) {
                const range = document.createRange();
                range.setStart(text, offset);
                offset += character.length;
                range.setEnd(text, offset);
                const glyph = range.getBoundingClientRect();
                const onLastLine = (glyph.top + glyph.bottom) / 2 > available.bottom - lineHeight / 2 - 0.5;
                const rightEdge = available.right - (onLastLine ? ellipsisWidth : 0);
                if (glyph.width === 0
                    || glyph.left < available.left - 0.5 || glyph.right > rightEdge + 0.5
                    || glyph.top < available.top - 0.5 || glyph.bottom > available.bottom + 0.5) break;
                visible += 1;
            }
            return visible;
        });
        expect(visibleCharacters).toBeGreaterThanOrEqual(8);
        const nameBounds = await name.boundingBox();
        const warmthBounds = await warmth.boundingBox();
        expect(nameBounds).not.toBeNull();
        expect(warmthBounds).not.toBeNull();
        if (!nameBounds || !warmthBounds) throw new Error('Missing mobile row bounds');
        expect(warmthBounds.y).toBeGreaterThanOrEqual(nameBounds.y + nameBounds.height);
        const lineHeight = await name.evaluate((element) => Number.parseFloat(getComputedStyle(element).lineHeight));
        expect(nameBounds.height).toBeLessThanOrEqual(lineHeight * 2 + 1);
        expect(nameBounds.x).toBeGreaterThanOrEqual(0);
        expect(nameBounds.x + nameBounds.width).toBeLessThanOrEqual(320);
        await row.click();
        await expect(page.locator('[data-record-peek]')).toBeVisible();
    });
}
