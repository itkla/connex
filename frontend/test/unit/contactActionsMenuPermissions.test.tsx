// @vitest-environment jsdom
import { act, type ReactNode } from 'react';
import { createRoot } from 'react-dom/client';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, expect, it, vi } from 'vitest';
import ContactActionsMenu from '@/app/components/records/contacts/ContactActionsMenu';
import { TooltipProvider } from '@/components/ui/tooltip';
import type { Contact } from '@/app/lib/types';
import en from '@/messages/en/contacts.json';

globalThis.IS_REACT_ACT_ENVIRONMENT = true;
const state = vi.hoisted(() => ({ allowed: false }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn(), refresh: vi.fn() }) }));
vi.mock('@/app/hooks/usePermissions', () => ({ usePermission: (permission: string) => permission === 'PERSON_UPDATE' && state.allowed }));
vi.mock('@/app/hooks/useWorkspace', () => ({ useWorkspace: () => ({ activeWorkspaceId: 17 }) }));
vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => vi.fn() }));
vi.mock('@/app/components/attachments/useAttachmentUploader', () => ({ useAttachmentUploader: () => ({ inputRef: { current: null }, uploading: false, openPicker: vi.fn(), onFilesSelected: vi.fn() }) }));
vi.mock('@/app/hooks/useRecordTargetSearch', () => ({ useContactTargetSearch: () => ({}), useDealTargetSearch: () => ({}) }));
vi.mock('@/app/components/records/contacts/ChangeCompanyDialog', () => ({ default: () => null }));
vi.mock('@/app/components/records/ArchiveRecordDialog', () => ({ default: () => null }));
vi.mock('@/app/components/records/ShareDialog', () => ({ default: () => null }));
vi.mock('@/app/components/records/BulkAssignOwnerDialog', () => ({ default: () => null }));
vi.mock('@/app/components/records/contacts/EditContactSheet', () => ({ default: () => null }));
vi.mock('@/app/components/records/contacts/RestrictionsDialog', () => ({ default: () => null }));
vi.mock('@/app/components/activity/notes/NoteDialog', () => ({ default: () => null }));
vi.mock('@/app/components/records/RecordComposers', () => ({ RecordActivityComposer: () => null, RecordTaskComposer: () => null }));
vi.mock('@/components/ui/dropdown-menu', () => {
    const Pass = ({ children }: { children?: ReactNode }) => <>{children}</>;
    return { DropdownMenu: Pass, DropdownMenuContent: Pass, DropdownMenuItem: Pass, DropdownMenuSeparator: () => null, DropdownMenuTrigger: Pass };
});
afterEach(() => vi.clearAllMocks());

it('offers privacy controls only with PERSON_UPDATE and an explicit matching workspace', async () => {
    const contact: Contact = { id: 7, workspaceId: 17, name: 'Contact', email: '', phone: '', title: '', imageUrl: '', createdAt: '', updatedAt: '' };
    const container = document.createElement('div');
    document.body.append(container);
    const root = createRoot(container);
    const render = async (record: Contact, allowed: boolean) => {
        state.allowed = allowed;
        await act(async () => root.render(<NextIntlClientProvider locale="en" messages={en}>
            <TooltipProvider><ContactActionsMenu contact={record} currentUserId={1} /></TooltipProvider>
        </NextIntlClientProvider>));
    };
    try {
        await render(contact, false);
        expect(container.textContent).not.toContain(en.ContactsActionsMenu.restrictions);
        await render(contact, true);
        expect(container.textContent).toContain(en.ContactsActionsMenu.restrictions);
        await render({ ...contact, workspaceId: 18 }, true);
        expect(container.textContent).not.toContain(en.ContactsActionsMenu.restrictions);
        await render({ ...contact, workspaceId: undefined }, true);
        expect(container.textContent).not.toContain(en.ContactsActionsMenu.restrictions);
    } finally {
        await act(async () => root.unmount());
        container.remove();
    }
});
