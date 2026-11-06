/** @vitest-environment jsdom */
import { act, type ReactNode } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import TagMembersSheet from '@/app/components/library/tags/TagMembersSheet';
import { addContactTag, getCompaniesForTag, getDealsForTag, getPeopleForTag } from '@/app/lib/api';
import { bindRenderedWorkspace, releaseRenderedWorkspace } from '@/app/lib/renderedWorkspace';
import type { Contact, Tag } from '@/app/lib/types';
import messages from '@/messages/en/activity.json';

globalThis.IS_REACT_ACT_ENVIRONMENT = true;
vi.mock('@/app/hooks/usePermissions', () => ({ usePermission: () => true }));
vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => vi.fn() }));
vi.mock('@/app/lib/toast', () => ({ toastSuccess: vi.fn() }));
vi.mock('@/app/lib/api', () => ({
    addContactTag: vi.fn(), removeContactTag: vi.fn(), addCompanyTag: vi.fn(), removeCompanyTag: vi.fn(),
    addTagToDeal: vi.fn(), removeTagFromDeal: vi.fn(),
    getPeopleForTag: vi.fn(), getCompaniesForTag: vi.fn(), getDealsForTag: vi.fn(),
}));
vi.mock('@/app/hooks/useRecordTargetSearch', () => ({
    useContactTargetSearch: () => ({ contacts: [{ id: 7, name: 'Mika' }, { id: 8, name: 'Aya' }], serverSearch: {}, onInputValueChange: vi.fn(), error: null }),
    useDealTargetSearch: () => ({ deals: [], serverSearch: {}, onInputValueChange: vi.fn(), error: null }),
}));
vi.mock('@/app/hooks/useCompanySearch', () => ({ useCompanySearch: () => ({ companies: [], serverSearch: {}, onInputValueChange: vi.fn(), error: null }) }));
vi.mock('@/components/ui/drawer', () => ({
    Drawer: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    DrawerContent: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    DrawerHeader: ({ children }: { children: ReactNode }) => <div>{children}</div>,
    DrawerTitle: ({ children }: { children: ReactNode }) => <h1>{children}</h1>,
    DrawerDescription: ({ children }: { children: ReactNode }) => <p>{children}</p>,
}));
vi.mock('@/app/components/records/RecordListState', () => ({ RecordListState: ({ children }: { children: ReactNode }) => children }));
vi.mock('@/app/components/records/RecordSelect', () => ({ default: ({ id, options, onValueChange }: {
    id: string; options: { id: number; label: string }[]; onValueChange: (value: string) => void;
}) => <button id={id} onClick={() => { if (options[0]) onValueChange(String(options[0].id)); }}>{options.map((option) => option.label).join(', ')}</button> }));

const workspaceOwner = {};
const tag: Tag = { id: 3, name: 'Priority', color: '', createdAt: '', updatedAt: '' };
const member: Contact = { id: 7, name: 'Mika', email: '', phone: '', title: '', imageUrl: '', createdAt: '', updatedAt: '' };
let root: Root;
let container: HTMLDivElement;

beforeEach(() => {
    vi.clearAllMocks();
    bindRenderedWorkspace(workspaceOwner, 11);
    vi.mocked(getPeopleForTag).mockResolvedValue([]);
    vi.mocked(getCompaniesForTag).mockResolvedValue([]);
    vi.mocked(getDealsForTag).mockResolvedValue([]);
    vi.mocked(addContactTag).mockResolvedValue(undefined);
    container = document.createElement('div'); document.body.appendChild(container); root = createRoot(container);
});
afterEach(async () => {
    await act(async () => root.unmount()); container.remove(); releaseRenderedWorkspace(workspaceOwner);
});

async function mount() {
    await act(async () => root.render(<NextIntlClientProvider locale="en" messages={messages} timeZone="UTC"><TagMembersSheet tag={tag} onClose={vi.fn()} /></NextIntlClientProvider>));
}

describe('tag membership writes', () => {
    it('uses the real workspace pin helper instead of a mocked API export', async () => {
        await mount();
        const picker = container.querySelector<HTMLButtonElement>('#tag-member-contacts');
        if (!picker) throw new Error('Missing contact picker');
        await act(async () => picker.click());
        expect(addContactTag).toHaveBeenCalledWith(7, 3, expect.objectContaining({ headers: expect.any(Headers) }));
        expect(new Headers(vi.mocked(addContactTag).mock.calls[0]?.[2]?.headers).get('X-Workspace-Id')).toBe('11');
        expect(getPeopleForTag).toHaveBeenCalledTimes(2);
    });

    it('offers only contacts that do not already carry the tag', async () => {
        vi.mocked(getPeopleForTag).mockResolvedValue([member]);
        await mount();
        const picker = container.querySelector<HTMLButtonElement>('#tag-member-contacts');
        if (!picker) throw new Error('Missing contact picker');
        expect(picker.textContent).toBe('Aya');
        await act(async () => picker.click());
        expect(addContactTag).toHaveBeenCalledWith(8, 3, expect.objectContaining({ headers: expect.any(Headers) }));
        expect(new Headers(vi.mocked(addContactTag).mock.calls[0]?.[2]?.headers).get('X-Workspace-Id')).toBe('11');
    });
});
