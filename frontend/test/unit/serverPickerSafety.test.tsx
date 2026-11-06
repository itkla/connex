/** @vitest-environment jsdom */
import { useState } from 'react';
import { act, cleanup, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import WorkflowSimulationDialog from '@/app/components/settings/workflows/WorkflowSimulationDialog';
import RecordSelect from '@/app/components/records/RecordSelect';
import ContactConnections from '@/app/components/records/contacts/ContactConnections';
import { NoteDialogForm } from '@/app/components/activity/notes/NoteDialog';
import { useContactTargetSearch, useDealTargetSearch } from '@/app/hooks/useRecordTargetSearch';
import { createServerPickerSearch } from '@/lib/server-picker-search';
import { Combobox, ComboboxContent, ComboboxEmpty, ComboboxInput, ComboboxItem, ComboboxList } from '@/components/ui/combobox';
import type { Contact, Deal, Page } from '@/app/lib/types';
import { contact, deal, deferred } from './helpers/workspaceChainBackend';

const api = vi.hoisted(() => ({
    contacts: vi.fn<typeof import('@/app/lib/api').getContactsPage>(),
    contact: vi.fn<typeof import('@/app/lib/api').getContactById>(),
    contactReferences: vi.fn<typeof import('@/app/lib/api').getContactReferences>(),
    deals: vi.fn<typeof import('@/app/lib/api').getDealsPage>(),
    deal: vi.fn<typeof import('@/app/lib/api').getDealById>(),
    add: vi.fn<typeof import('@/app/lib/api').addContactConnection>(),
}));
vi.mock('@/app/lib/api', async (importOriginal) => ({
    ...await importOriginal<typeof import('@/app/lib/api')>(),
    getContactsPage: api.contacts, getContactById: api.contact,
    getContactReferences: api.contactReferences,
    getDealsPage: api.deals, getDealById: api.deal, addContactConnection: api.add,
}));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ refresh: vi.fn() }) }));
vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => vi.fn() }));
vi.mock('@/components/ui/dialog-status-cover', async (importOriginal) => ({
    ...await importOriginal<typeof import('@/components/ui/dialog-status-cover')>(), DialogStatusCover: () => null,
}));
vi.mock('next/dynamic', () => ({ default: () => function Editor() { return <textarea aria-label="Body" />; } }));

const oldContact: Contact = { ...contact, id: 1, name: 'Cached contact' };
const freshContact: Contact = { ...contact, id: 2, name: 'Server matched contact' };
const oldDeal: Deal = { ...deal, id: 1, name: 'Cached deal' };
const freshDeal: Deal = { ...deal, id: 2, name: 'Server matched deal' };
const contactSeeds = [oldContact];
const dealSeeds = [oldDeal];
function page<T>(items: T[]): Page<T> { return { items, total: items.length }; }
function typeQuery(input: HTMLElement, value: string) {
    fireEvent.input(input, { inputType: 'insertText', target: { value } });
}
function tryEnter(input: HTMLElement) {
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    expect(fireEvent.keyDown(input, { key: 'Enter' })).toBe(false);
}

beforeEach(() => {
    api.contacts.mockReset().mockResolvedValue(page(contactSeeds));
    api.deals.mockReset().mockResolvedValue(page(dealSeeds));
    api.contact.mockReset().mockResolvedValue(oldContact);
    api.contactReferences.mockReset().mockResolvedValue([oldContact]);
    api.deal.mockReset().mockResolvedValue(oldDeal);
    api.add.mockReset();
    vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} });
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => setTimeout(() => callback(0), 0));
    vi.stubGlobal('cancelAnimationFrame', clearTimeout);
    vi.stubGlobal('matchMedia', (media: string) => ({ media, matches: false, addEventListener() {}, removeEventListener() {} }));
    HTMLElement.prototype.scrollIntoView = vi.fn();
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

function RecordPicker({ kind, onSelect }: { kind: 'activity-contact' | 'activity-deal' | 'task-deal'; onSelect: (value: string) => void }) {
    const contacts = useContactTargetSearch(kind === 'activity-contact', [1], contactSeeds);
    const deals = useDealTargetSearch(kind !== 'activity-contact', [1], dealSeeds);
    const search = kind === 'activity-contact' ? contacts : deals;
    return <RecordSelect id="target" value="1" onValueChange={onSelect}
        options={search.targets.map((target) => ({ id: target.id, label: target.name }))}
        onInputValueChange={search.onInputValueChange} serverSearch={search.serverSearch} />;
}

function NotePicker() {
    const contacts = useContactTargetSearch(true, [1], contactSeeds);
    const deals = useDealTargetSearch(true, [1], dealSeeds);
    return <><output>{contacts.loading || deals.loading ? 'Loading targets' : 'Targets ready'}</output><NoteDialogForm note={null} persons={contacts.contacts} deals={deals.deals} currentUserId={1}
        defaultPerson={oldContact} defaultDeal={oldDeal} defaultContent=""
        personServerSearch={contacts.serverSearch} dealServerSearch={deals.serverSearch}
        onPersonQueryChange={contacts.onInputValueChange} onDealQueryChange={deals.onInputValueChange}
        onSubmittingChange={vi.fn()} onCancel={vi.fn()} onClose={vi.fn()} /></>;
}

describe('query-owned server picker choices', () => {
    it.each(['activity-contact', 'activity-deal', 'task-deal'] as const)('%s cannot select cached or hydrated targets during debounce or request', async (kind) => {
        const contactResponse = deferred<Page<Contact>>();
        const dealResponse = deferred<Page<Deal>>();
        api.contacts.mockImplementation((params) => params?.q ? contactResponse.promise : Promise.resolve(page(contactSeeds)));
        api.deals.mockImplementation((params) => params?.q ? dealResponse.promise : Promise.resolve(page(dealSeeds)));
        const onSelect = vi.fn();
        render(<RecordPicker kind={kind} onSelect={onSelect} />);
        const input = screen.getByRole('combobox');
        act(() => input.focus());
        fireEvent.keyDown(input, { key: 'ArrowDown' });
        const old = await screen.findByRole('option', { name: kind === 'activity-contact' ? oldContact.name : oldDeal.name });
        typeQuery(input, 'domain.example.test');
        expect(screen.queryAllByRole('option')).toHaveLength(0);
        tryEnter(input);
        fireEvent.click(old);
        expect(onSelect).not.toHaveBeenCalled();
        const request = kind === 'activity-contact' ? api.contacts : api.deals;
        await waitFor(() => expect(request).toHaveBeenCalledWith(expect.objectContaining({ q: 'domain.example.test' }), expect.anything()));
        tryEnter(input);
        fireEvent.click(old);
        expect(onSelect).not.toHaveBeenCalled();
        await act(async () => {
            contactResponse.resolve(page([freshContact]));
            dealResponse.resolve(page([freshDeal]));
        });
        const fresh = await screen.findByRole('option', { name: kind === 'activity-contact' ? freshContact.name : freshDeal.name });
        expect(screen.getAllByRole('option')).toHaveLength(1);
        if (kind === 'task-deal') fireEvent.click(fresh);
        else tryEnter(input);
        expect(onSelect).toHaveBeenCalledExactlyOnceWith('2');
    });

    it('ContactConnections cannot commit a cached contact by Enter or click', async () => {
        const response = deferred<Page<Contact>>();
        api.contacts.mockImplementation((params) => params?.q ? response.promise : Promise.resolve(page(contactSeeds)));
        render(<ContactConnections contactId={99} contactName="Target" initialConnections={[]}
            initialIntroPath={{ reachable: false, directlyKnown: false, steps: [] }} />);
        const input = screen.getByPlaceholderText('addPlaceholder');
        act(() => input.focus());
        fireEvent.keyDown(input, { key: 'ArrowDown' });
        const old = await screen.findByRole('option', { name: oldContact.name });
        typeQuery(input, 'domain.example.test');
        expect(screen.queryByRole('option')).toBeNull();
        tryEnter(input);
        fireEvent.click(old);
        expect(screen.getByLabelText('add', { exact: true })).toHaveProperty('disabled', true);
        await waitFor(() => expect(api.contacts).toHaveBeenCalledWith(expect.objectContaining({ q: 'domain.example.test' }), expect.anything()));
        tryEnter(input);
        expect(screen.getByLabelText('add', { exact: true })).toHaveProperty('disabled', true);
        await act(async () => response.resolve(page([freshContact])));
        fireEvent.click(await screen.findByRole('option', { name: freshContact.name }));
        expect(input).toHaveProperty('value', freshContact.name);
        expect(screen.getByLabelText('add', { exact: true })).toHaveProperty('disabled', false);
        expect(api.add).not.toHaveBeenCalled();
    });

    it.each(['personLabel', 'dealLabel'])('note %s uses query-owned choices while preserving its selected label', async (label) => {
        const contactResponse = deferred<Page<Contact>>();
        const dealResponse = deferred<Page<Deal>>();
        api.contacts.mockImplementation((params) => params?.q ? contactResponse.promise : Promise.resolve(page(contactSeeds)));
        api.deals.mockImplementation((params) => params?.q ? dealResponse.promise : Promise.resolve(page(dealSeeds)));
        render(<NotePicker />);
        await screen.findByText('Targets ready');
        const input = screen.getByRole('combobox', { name: label });
        act(() => input.focus());
        fireEvent.keyDown(input, { key: 'ArrowDown' });
        const old = await screen.findByRole('option');
        typeQuery(input, 'domain.example.test');
        expect(screen.queryByRole('option')).toBeNull();
        tryEnter(input);
        fireEvent.click(old);
        expect(input).toHaveProperty('value', 'domain.example.test');
        await act(async () => {
            contactResponse.resolve(page([freshContact]));
            dealResponse.resolve(page([freshDeal]));
        });
        const fresh = await screen.findByRole('option', { name: label === 'personLabel' ? freshContact.name : freshDeal.name });
        fireEvent.click(fresh);
        expect(input).toHaveProperty('value', label === 'personLabel' ? freshContact.name : freshDeal.name);
    });

    it('workflow simulation cannot choose a retained record for a different query', async () => {
        const onSimulate = vi.fn();
        const cached = [{ id: 1, label: 'Cached workflow record' }];
        const fresh = [{ id: 2, label: 'Server matched workflow record' }];
        function WorkflowPicker({ result }: { result: { query: string; items: typeof cached } }) {
            const [query, setQuery] = useState('');
            return <WorkflowSimulationDialog open records={result.items}
                serverSearch={createServerPickerSearch(query, result)} loading={false} supported result={null}
                diagnosticMessage={() => ''} onOpenChange={vi.fn()} onSearch={setQuery}
                onClear={vi.fn()} onSimulate={onSimulate} />;
        }
        const view = render(<WorkflowPicker result={{ query: '', items: cached }} />);
        const input = screen.getByRole('combobox');
        act(() => input.focus());
        fireEvent.keyDown(input, { key: 'ArrowDown' });
        const old = await screen.findByRole('option', { name: cached[0].label });
        typeQuery(input, 'current');
        expect(screen.queryByRole('option')).toBeNull();
        tryEnter(input);
        fireEvent.click(old);
        expect(screen.getByText('simulation.previewButton')).toHaveProperty('disabled', true);
        view.rerender(<WorkflowPicker result={{ query: 'current', items: fresh }} />);
        fireEvent.click(await screen.findByRole('option', { name: fresh[0].label }));
        fireEvent.click(screen.getByRole('button', { name: 'simulation.previewButton' }));
        expect(onSimulate).toHaveBeenCalledExactlyOnceWith(2);
    });

    it.each(['contact', 'deal'] as const)('an aborted %s response cannot republish selectable rows', async (kind) => {
        const oldContacts = deferred<Page<Contact>>();
        const newContacts = deferred<Page<Contact>>();
        const oldDeals = deferred<Page<Deal>>();
        const newDeals = deferred<Page<Deal>>();
        api.contacts.mockImplementation((params) => params?.q === 'old' ? oldContacts.promise : newContacts.promise);
        api.deals.mockImplementation((params) => params?.q === 'old' ? oldDeals.promise : newDeals.promise);
        const { result } = renderHook(() => {
            const contacts = useContactTargetSearch(kind === 'contact');
            const deals = useDealTargetSearch(kind === 'deal');
            return kind === 'contact' ? contacts : deals;
        });
        act(() => result.current.onInputValueChange('old'));
        const request = kind === 'contact' ? api.contacts : api.deals;
        await waitFor(() => expect(request).toHaveBeenCalledWith(expect.objectContaining({ q: 'old' }), expect.anything()));
        act(() => result.current.onInputValueChange('new'));
        await act(async () => { oldContacts.resolve(page(contactSeeds)); oldDeals.resolve(page(dealSeeds)); });
        expect(result.current.serverSearch.pending).toBe(true);
        expect(result.current.serverSearch.accepts(kind === 'contact' ? oldContact : oldDeal)).toBe(false);
        await waitFor(() => expect(request).toHaveBeenCalledWith(expect.objectContaining({ q: 'new' }), expect.anything()));
        await act(async () => { newContacts.resolve(page([freshContact])); newDeals.resolve(page([freshDeal])); });
        expect(result.current.serverSearch.pending).toBe(false);
        expect(result.current.serverSearch.accepts(kind === 'contact' ? freshContact : freshDeal)).toBe(true);
    });
});

function SnapshotPicker({ query, result, onSelect }: {
    query: string;
    result: { query: string; items: Array<{ id: number | string; label: string }> } | null;
    onSelect: (value: unknown) => void;
}) {
    const [inputQuery, setInputQuery] = useState(query);
    const cached = { id: 1, label: 'Cached' };
    return <Combobox<{ id: number | string; label: string }> items={[cached, ...(result?.items ?? [])]} inputValue={inputQuery} serverSearch={createServerPickerSearch(inputQuery, result)}
        onInputValueChange={(value, details) => { if (details.reason === 'input-change') setInputQuery(value); }}
        onValueChange={onSelect} itemToStringLabel={(item) => item.label}>
        <ComboboxInput aria-label="Server search" />
        <ComboboxContent><ComboboxEmpty>Empty</ComboboxEmpty>
            <ComboboxList>{(item: { id: number | string; label: string }) => <ComboboxItem key={item.id} value={item}>{item.label}</ComboboxItem>}</ComboboxList>
            <ComboboxItem value={cached}>Retained stale node</ComboboxItem>
        </ComboboxContent>
    </Combobox>;
}

describe('shared server picker selection boundary', () => {
    it.each([null, { query: 'previous', items: [{ id: 1, label: 'Cached' }] }, { query: 'current', items: [{ id: 2, label: 'Fresh' }] }])('rejects stale clicks even if a stale node is still mounted: %j', (result) => {
        const onSelect = vi.fn();
        render(<SnapshotPicker query="current" result={result} onSelect={onSelect} />);
        const input = screen.getByRole('combobox');
        act(() => input.focus());
        fireEvent.keyDown(input, { key: 'ArrowDown' });
        fireEvent.click(screen.getByRole('option', { name: 'Retained stale node' }));
        expect(onSelect).not.toHaveBeenCalled();
    });

    it('guards IME Enter and accepts only a settled import retry option', async () => {
        const onSelect = vi.fn();
        render(<SnapshotPicker query="current" result={{ query: 'current', items: [{ id: 'retry', label: 'Retry' }] }} onSelect={onSelect} />);
        const input = screen.getByRole('combobox');
        act(() => input.focus());
        fireEvent.keyDown(input, { key: 'ArrowDown' });
        fireEvent.compositionStart(input);
        expect(fireEvent.keyDown(input, { key: 'Enter', isComposing: true })).toBe(false);
        expect(onSelect).not.toHaveBeenCalled();
        fireEvent.click(screen.getByRole('option', { name: 'Retry' }));
        expect(onSelect).not.toHaveBeenCalled();
        fireEvent.compositionEnd(input);
        fireEvent.click(screen.getByRole('option', { name: 'Retry' }));
        expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ id: 'retry' }), expect.anything());
    });
});
