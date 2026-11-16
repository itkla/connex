/** @vitest-environment jsdom */

import { readFileSync } from 'node:fs';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import RecordsRenderView from '@/app/components/records/RecordsRenderView';
import ContactListRow from '@/app/components/records/contacts/ContactListRow';
import type { ColumnDef } from '@/app/components/records/types';
import type { Contact, RelationshipTemperature } from '@/app/lib/types';
import { NowProvider } from '@/app/hooks/useNow';
import { TooltipProvider } from '@/components/ui/tooltip';
import commonEn from '@/messages/en/common.json';
import commonJa from '@/messages/ja/common.json';
import recordsEn from '@/messages/en/records.json';
import recordsJa from '@/messages/ja/records.json';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('motion/react', async (importOriginal) => ({
    ...await importOriginal<typeof import('motion/react')>(),
    useReducedMotion: () => true,
}));

const contact: Contact = {
    id: 1, name: 'Alexandra'.repeat(34), title: 'Director'.repeat(40),
    email: `${'long'.repeat(70)}@example.test`, phone: '', imageUrl: '', createdAt: '', updatedAt: '',
};
const noHistory: RelationshipTemperature = { id: 1, score: 0, band: 'cold', trend: 'steady', touchCount: 0 };

beforeEach(() => {
    vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} });
    vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockImplementation(function (this: HTMLElement) {
        return this instanceof HTMLTableCellElement && this.cellIndex === 0 ? 50 : 64;
    });
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

function renderTable(columns: ColumnDef<Contact>[], readOnly = false) {
    return render(
        <NextIntlClientProvider locale="en" messages={{ ...commonEn, ...recordsEn }} timeZone="UTC">
            <TooltipProvider>
                <RecordsRenderView
                    data={[contact]} columns={columns} displayMode="table" renderCard={() => null}
                    renderAvatar={() => <span>Avatar</span>} emptyState={null} entityLabel="contact" entityLabelPlural="contacts"
                    selectedIds={new Set()} onSelectedIdsChange={vi.fn()} readOnly={readOnly}
                />
            </TooltipProvider>
        </NextIntlClientProvider>,
    );
}

function bodyCell() {
    const row = screen.getAllByRole('row')[1];
    if (!row) throw new Error('Missing record row');
    const cell = within(row).getAllByRole('cell')[2];
    if (!cell) throw new Error('Missing value cell');
    return cell;
}

describe('bounded record columns', () => {
    it('ACT-32 names the company select-all checkbox with the irregular plural', () => {
        const onSelectionChange = vi.fn();
        render(
            <NextIntlClientProvider locale="en" messages={{ ...commonEn, ...recordsEn }} timeZone="UTC">
                <TooltipProvider>
                    <RecordsRenderView
                        data={[{ id: 1, name: 'Grammar company' }]} columns={[]} displayMode="table"
                        renderCard={() => null} emptyState={null} entityLabel="company" entityLabelPlural="companies"
                        selectedIds={new Set()} onSelectedIdsChange={onSelectionChange}
                    />
                </TooltipProvider>
            </NextIntlClientProvider>,
        );
        const selectAll = screen.getByRole('checkbox', { name: 'Select all companies' });
        fireEvent.click(selectAll);
        expect(onSelectionChange).toHaveBeenCalledWith(new Set([1]));
    });

    it.each(['email', 'title'] as const)('A11Y-25 caps editable %s and preserves its full value and editor', (key) => {
        const save = vi.fn().mockResolvedValue(undefined);
        renderTable([{
            key, label: key, maxWidth: '16rem',
            editable: { getValue: (row) => row[key], save },
        }]);

        const cell = bodyCell();
        expect(screen.getByRole('columnheader', { name: key }).style.maxWidth).toBe('16rem');
        expect(cell.style.maxWidth).toBe('16rem');
        const value = within(cell).getByTitle(contact[key]);
        expect(value.classList.contains('truncate')).toBe(true);
        expect(value.classList.contains('block')).toBe(true);
        expect(value.textContent).toBe(contact[key]);
        expect(cell.firstElementChild).toBeInstanceOf(HTMLSpanElement);
        expect(cell.firstElementChild?.getAttribute('style')).toContain('max-width: 16rem');

        fireEvent.doubleClick(within(cell).getByRole('button'));
        expect(within(cell).getByRole('textbox').getAttribute('value')).toBe(contact[key]);
        fireEvent.keyDown(within(cell).getByRole('textbox'), { key: 'Escape' });
        expect(save).not.toHaveBeenCalled();
        expect(within(cell).getByTitle(contact[key]).textContent).toBe(contact[key]);
    });

    it.each(['display', 'copyable', 'disabled', 'readOnly'] as const)('REC-21 bounds the %s path and shares the measured sticky offset', (path) => {
        renderTable([{
            key: 'name', label: 'Name', maxWidth: '20rem',
            ...(path === 'copyable' ? { copyable: { label: 'Name', getValue: (row: Contact) => row.name } } : {}),
            ...(path === 'disabled' || path === 'readOnly' ? {
                editable: { getValue: (row: Contact) => row.name, save: vi.fn(), disabled: () => 'Saving' },
            } : {}),
        }], path === 'readOnly');

        const header = screen.getByRole('columnheader', { name: 'Name' });
        const cell = bodyCell();
        expect(header.style.maxWidth).toBe('20rem');
        expect(cell.style.maxWidth).toBe('20rem');
        expect(header.style.left).toBe('114px');
        expect(cell.style.left).toBe(header.style.left);
        expect(cell.classList.contains('md:sticky')).toBe(true);
        const value = within(cell).getByTitle(contact.name);
        expect(value.classList.contains('truncate')).toBe(true);
        expect(value.style.maxWidth).toBe('20rem');
    });

    it('IMP-36 bounds rich names without including discussion counts in the full-name title', () => {
        renderTable([{
            key: 'name', label: 'Name', maxWidth: '20rem', getTextValue: (row) => row.name,
            render: (row) => <span className="inline-flex max-w-full min-w-0"><span className="truncate">{row.name}</span><span>3</span></span>,
        }]);
        const value = within(bodyCell()).getByTitle(contact.name);
        expect(value.style.maxWidth).toBe('20rem');
        expect(value.classList.contains('truncate')).toBe(true);
        expect(within(value).getByText('3')).toBeDefined();
    });

    it.each(['contacts/ContactsBrowser', 'companies/CompaniesBrowser', 'deals/DealsBrowser'])('IMP-36 %s opts its identity column into the cap', (browser) => {
        const source = readFileSync(`app/components/records/${browser}.tsx`, 'utf8');
        const nameColumn = source.slice(source.indexOf("key: 'name'"), source.indexOf("key: 'name'") + 700);
        expect(nameColumn).toContain("maxWidth: '20rem'");
        expect(nameColumn).toMatch(/getTextValue: \([cd]\) => [cd]\.name/);
        expect(nameColumn).toContain('max-w-full');
    });

    it.each(['email', 'title'])('A11Y-25 contacts opts %s into the smaller cap', (key) => {
        const source = readFileSync('app/components/records/contacts/ContactsBrowser.tsx', 'utf8');
        expect(source).toMatch(new RegExp(`key: '${key}',\\s+label: t\\('[^']+'\\),\\s+maxWidth: '16rem'`));
    });
});

describe.each([
    { locale: 'en', messages: { ...commonEn, ...recordsEn } },
    { locale: 'ja', messages: { ...commonJa, ...recordsJa } },
])('narrow contact identity in $locale', ({ locale, messages }) => {
    it.each([true, false])('A11Y-26 reserves the name row while keeping warmth on the secondary line (secondary: %s)', (hasSecondary) => {
        const fullName = locale === 'ja' ? '長谷川アレクサンダー' : contact.name;
        render(
            <NextIntlClientProvider locale={locale} messages={messages} timeZone="UTC">
                <TooltipProvider>
                    <NowProvider value={Date.parse('2026-01-01T00:00:00Z')}>
                        <ContactListRow contact={{ ...contact, name: fullName, title: hasSecondary ? contact.title : '', email: '' }} temperature={noHistory} />
                    </NowProvider>
                </TooltipProvider>
            </NextIntlClientProvider>,
        );
        const name = screen.getByTitle(fullName);
        expect(name.classList.contains('col-span-2')).toBe(true);
        expect(name.classList.contains('line-clamp-2')).toBe(true);
        expect(name.classList.contains('wrap-break-word')).toBe(true);
        expect(name.classList.contains('whitespace-normal')).toBe(true);
        expect(name.classList.contains('truncate')).toBe(false);
        expect(name.classList.contains('break-all')).toBe(false);
        expect(name.classList.contains('min-[400px]:col-span-1')).toBe(true);
        expect(name.classList.contains('min-[400px]:line-clamp-none')).toBe(true);
        expect(name.classList.contains('min-[400px]:truncate')).toBe(true);
        const warmth = screen.getByText(messages.Temperature.noHistory).parentElement;
        expect(warmth?.classList.contains('row-start-2')).toBe(true);
        expect(warmth?.classList.contains('min-[400px]:row-start-1')).toBe(true);
        expect(name.parentElement?.classList.contains('grid')).toBe(true);
    });
});
