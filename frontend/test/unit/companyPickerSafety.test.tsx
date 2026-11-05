/** @vitest-environment jsdom */
import { useState } from 'react';
import { webcrypto } from 'node:crypto';
import { bindRenderedWorkspace, releaseRenderedWorkspace } from '@/app/lib/renderedWorkspace';
import { act, cleanup, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useCompanySearch } from '@/app/hooks/useCompanySearch';
import { NewContactForm } from '@/app/components/records/contacts/NewContactDialog';
import { Combobox, ComboboxContent, ComboboxEmpty, ComboboxInput, ComboboxItem, ComboboxList } from '@/components/ui/combobox';
import type { Company, CreateContactPayload, Page } from '@/app/lib/types';
import common from '@/messages/en/common.json';
import contacts from '@/messages/en/contacts.json';
import { company, deferred, shell, ORIGIN, stubBackend } from './helpers/workspaceChainBackend';

const api = vi.hoisted(() => ({
    page: vi.fn<typeof import('@/app/lib/api').getCompaniesPage>(),
    byIds: vi.fn<typeof import('@/app/lib/api').getCompaniesByIds>(),
    preflight: vi.fn<typeof import('@/app/lib/api').preflightPersonDuplicates>(),
}));
vi.mock('@/app/lib/api', async (importOriginal) => ({
    ...await importOriginal<typeof import('@/app/lib/api')>(),
    getCompaniesPage: api.page,
    getCompaniesByIds: api.byIds,
    preflightPersonDuplicates: api.preflight,
    getBusinessCardAvailability: async () => ({ scanning: false, importing: false }),
    getEffectivePermissions: async () => [],
}));
vi.mock('@/components/ui/dialog-status-cover', async (importOriginal) => ({
    ...await importOriginal<typeof import('@/components/ui/dialog-status-cover')>(), DialogStatusCover: () => null,
}));
vi.mock('next/navigation', () => ({ useRouter: () => ({ refresh: vi.fn(), push: vi.fn() }) }));
vi.mock('@/app/hooks/useWorkspace', () => ({ useWorkspace: () => ({ activeWorkspaceId: 9 }) }));

const acme: Company = { ...company, id: 1, name: 'Acme' };
const fuji: Company = { ...company, id: 2, name: 'Fuji Logistics', website: 'https://fuji.example.test' };
const zebra: Company = { ...company, id: 3, name: 'Zebra' };
function page(items: Company[]): Page<Company> {
    return { items, total: items.length };
}

beforeEach(() => {
    vi.stubGlobal('crypto', webcrypto);
    Object.defineProperty(navigator, 'locks', { configurable: true, value: { request: async (_name: string, _options: LockOptions, operation: () => unknown) => operation() } });
    bindRenderedWorkspace(shell, ORIGIN);
    document.cookie = 'connex_workspace=' + ORIGIN + '; path=/';
    stubBackend();
    api.page.mockReset().mockResolvedValue(page([acme, fuji]));
    api.byIds.mockReset().mockResolvedValue([]);
    api.preflight.mockReset().mockResolvedValue({ recordType: 'person', candidates: [], truncated: false, reviewToken: 'review' });
    vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} });
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => setTimeout(() => callback(0), 0));
    vi.stubGlobal('cancelAnimationFrame', clearTimeout);
    vi.stubGlobal('matchMedia', (media: string) => ({ media, matches: false, addEventListener() {}, removeEventListener() {} }));
    HTMLElement.prototype.scrollIntoView = vi.fn();
});
afterEach(() => {
    cleanup();
    releaseRenderedWorkspace(shell);
    Reflect.deleteProperty(navigator, 'locks');
    window.localStorage.clear();
    window.sessionStorage.clear();
    vi.unstubAllGlobals();
});

function CompanyPicker({ selected, onSelect = vi.fn() }: { selected?: Company; onSelect?: (value: Company | null) => void }) {
    const search = useCompanySearch(true, selected ? [selected.id] : [], selected ? [selected] : []);
    return <NextIntlClientProvider locale="en" messages={common}><Combobox items={search.options} itemsPending={search.loading} filter={null} autoHighlight
        defaultValue={selected} onInputValueChange={search.onInputValueChange} onValueChange={onSelect}
        itemToStringLabel={(item) => item.name}>
        <ComboboxInput aria-label="Company" />
        <ComboboxContent><ComboboxEmpty>No companies</ComboboxEmpty>
            <ComboboxList>{(item: Company) => <ComboboxItem key={item.id} value={item}>{item.name}</ComboboxItem>}</ComboboxList>
        </ComboboxContent>
    </Combobox></NextIntlClientProvider>;
}

function ContactForm({ onCreate, selected }: { onCreate: (payload: CreateContactPayload) => void; selected?: Company }) {
    const [payload, setPayload] = useState<CreateContactPayload>({ name: 'Draft contact', email: '', phone: '', title: '', companyId: selected?.id });
    return <NextIntlClientProvider locale="en" messages={{ ...common, ...contacts }}>
        <NewContactForm active onCancel={vi.fn()} newContactPayload={payload} setNewContactPayload={setPayload}
            imageFile={null} selectedCompany={selected} setImageFile={vi.fn()} isCreating={false} createNewContact={() => onCreate(payload)} />
    </NextIntlClientProvider>;
}

describe('server-query ownership of company choices', () => {
    it('invalidates cached rows before debounce and accepts only the response for the current query', async () => {
        const old = deferred<Page<Company>>();
        const fresh = deferred<Page<Company>>();
        api.page.mockImplementation((params) => params?.q === 'old' ? old.promise
            : params?.q === 'Zebra' ? fresh.promise : Promise.resolve(page([acme, fuji])));
        const { result } = renderHook(() => useCompanySearch(true));
        await waitFor(() => expect(result.current.options).toHaveLength(2));
        act(() => result.current.onInputValueChange('old'));
        expect(result.current.loading).toBe(true);
        expect(result.current.options).toEqual([]);
        await waitFor(() => expect(api.page).toHaveBeenCalledWith(expect.objectContaining({ q: 'old' }), expect.anything()));
        act(() => result.current.onInputValueChange('Zebra'));
        await act(async () => old.resolve(page([fuji])));
        expect(result.current.options).toEqual([]);
        await waitFor(() => expect(api.page).toHaveBeenCalledWith(expect.objectContaining({ q: 'Zebra' }), expect.anything()));
        await act(async () => fresh.resolve(page([zebra])));
        expect(result.current.options).toEqual([zebra]);
    });

    it('prevents Enter from choosing a cached or hydrated selection, then highlights the new response', async () => {
        const response = deferred<Page<Company>>();
        api.byIds.mockResolvedValue([acme]);
        api.page.mockImplementation((params) => params?.q === 'Zebra' ? response.promise : Promise.resolve(page([acme, fuji])));
        const onSelect = vi.fn();
        render(<CompanyPicker selected={acme} onSelect={onSelect} />);
        const input = screen.getByRole('combobox');
        await waitFor(() => expect(api.page).toHaveBeenCalled());
        act(() => input.focus());
        fireEvent.keyDown(input, { key: 'ArrowDown' });
        await screen.findByRole('option', { name: 'Acme' });
        fireEvent.input(input, { inputType: 'insertText', target: { value: 'Zebra' } });
        expect(screen.queryAllByRole('option')).toHaveLength(0);
        expect(fireEvent.keyDown(input, { key: 'Enter' })).toBe(false);
        expect(onSelect).not.toHaveBeenCalled();
        await waitFor(() => expect(api.page).toHaveBeenCalledWith(expect.objectContaining({ q: 'Zebra' }), expect.anything()));
        await act(async () => response.resolve(page([zebra])));
        await waitFor(() => expect(input.getAttribute('aria-activedescendant')).toBeTruthy());
        expect(screen.getAllByRole('option').map((node) => node.textContent)).toEqual(['Zebra']);
        expect(onSelect).not.toHaveBeenCalled();
        fireEvent.keyDown(input, { key: 'Enter' });
        expect(onSelect).toHaveBeenCalledWith(zebra, expect.anything());
    });

    it.each(['fuji.example.test', 'Aerospace', '03-1234-5678', 'Tokyo'])('keeps server matches for %s when the name differs', async (query) => {
        api.page.mockResolvedValue(page([fuji]));
        render(<CompanyPicker />);
        const input = screen.getByRole('combobox');
        act(() => input.focus());
        fireEvent.keyDown(input, { key: 'ArrowDown' });
        fireEvent.input(input, { inputType: 'insertText', target: { value: query } });
        await waitFor(() => expect(screen.getAllByRole('option').map((node) => node.textContent)).toEqual(['Fuji Logistics']));
        fireEvent.keyDown(input, { key: 'Enter' });
        expect(input).toHaveProperty('value', 'Fuji Logistics');
    });

    it('offers no hydrated selection after the current search fails', async () => {
        api.byIds.mockResolvedValue([acme]);
        api.page.mockRejectedValue(new Error('Search unavailable'));
        const { result } = renderHook(() => useCompanySearch(true, [1], [acme]));
        await waitFor(() => expect(result.current.error).toBeInstanceOf(Error));
        expect(result.current.options).toEqual([]);
        expect(result.current.companies).toEqual([acme]);
    });
});

describe('New contact unresolved company drafts', () => {
    it('keeps an unmatched query after Enter and blur, blocks submission, and permits explicit clearing', async () => {
        api.page.mockResolvedValue(page([]));
        const onCreate = vi.fn();
        render(<ContactForm onCreate={onCreate} />);
        const input = screen.getByRole('combobox', { name: 'Company' });
        await waitFor(() => expect(input).toHaveProperty('disabled', false));
        act(() => input.focus());
        fireEvent.keyDown(input, { key: 'ArrowDown' });
        fireEvent.input(input, { inputType: 'insertText', target: { value: 'No matching company' } });
        expect(fireEvent.keyDown(input, { key: 'Enter' })).toBe(false);
        act(() => screen.getByLabelText('Title', { exact: true }).focus());
        fireEvent.blur(input);
        await waitFor(() => expect(input).toHaveProperty('value', 'No matching company'));
        await waitFor(() => expect(screen.getByRole('button', { name: /^Create$/ })).toHaveProperty('disabled', false));
        const form = screen.getByRole('button', { name: /^Create$/ }).closest('form');
        if (!form) throw new Error('Missing contact form');
        fireEvent.submit(form);
        expect(onCreate).not.toHaveBeenCalled();
        expect(screen.getByRole('alert').textContent).toBe(contacts.ContactsNewContactDialog.companySelectionRequired);
        fireEvent.input(input, { inputType: 'insertText', target: { value: '' } });
        await waitFor(() => expect(screen.getByRole('button', { name: /^Create$/ })).toHaveProperty('disabled', false));
        fireEvent.click(screen.getByRole('button', { name: /^Create$/ }));
        await waitFor(() => expect(onCreate).toHaveBeenCalledOnce());
    });

    it('submits the selected server match after a domain query', async () => {
        api.page.mockResolvedValue(page([fuji]));
        const onCreate = vi.fn();
        render(<ContactForm onCreate={onCreate} />);
        const input = screen.getByRole('combobox', { name: 'Company' });
        await waitFor(() => expect(input).toHaveProperty('disabled', false));
        act(() => input.focus());
        fireEvent.keyDown(input, { key: 'ArrowDown' });
        fireEvent.input(input, { inputType: 'insertText', target: { value: 'fuji.example.test' } });
        await screen.findByRole('option', { name: 'Fuji Logistics' });
        await waitFor(() => expect(input.getAttribute('aria-activedescendant')).toBeTruthy());
        fireEvent.keyDown(input, { key: 'Enter' });
        await waitFor(() => expect(screen.getByRole('button', { name: /^Create$/ })).toHaveProperty('disabled', false));
        fireEvent.click(screen.getByRole('button', { name: /^Create$/ }));
        await waitFor(() => expect(onCreate).toHaveBeenCalledWith(expect.objectContaining({ companyId: fuji.id })));
    });

    it('restores an existing selection on Escape and permits subsequent collapsed Enter', async () => {
        api.byIds.mockResolvedValue([acme]);
        const onCreate = vi.fn();
        render(<ContactForm selected={acme} onCreate={onCreate} />);
        const input = screen.getByRole('combobox', { name: 'Company' });
        await waitFor(() => expect(input).toHaveProperty('disabled', false));
        act(() => input.focus());
        fireEvent.keyDown(input, { key: 'ArrowDown' });
        fireEvent.input(input, { inputType: 'insertText', target: { value: 'Zebra' } });
        fireEvent.keyDown(input, { key: 'Escape' });
        await waitFor(() => expect(input).toHaveProperty('value', 'Acme'));
        await waitFor(() => expect(input.getAttribute('aria-expanded')).toBe('false'));
        await waitFor(() => expect(screen.getByRole('button', { name: /^Create$/ })).toHaveProperty('disabled', false));
        expect(fireEvent.keyDown(input, { key: 'Enter' })).toBe(true);
        fireEvent.click(screen.getByRole('button', { name: /^Create$/ }));
        await waitFor(() => expect(onCreate).toHaveBeenCalledWith(expect.objectContaining({ companyId: acme.id })));
    });
});
