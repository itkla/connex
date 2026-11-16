import { act, createElement, useImperativeHandle, type ComponentProps, type Ref } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import EditContactSheet from '@/app/components/records/contacts/EditContactSheet';
import type QuickEditSheet from '@/app/components/records/contacts/QuickEditSheet';
import type ContactEditConflictDialog from '@/app/components/records/contacts/ContactEditConflictDialog';
import type { CustomFieldsEditHandle } from '@/app/components/records/CustomFieldsEditSection';
import type { Contact } from '@/app/lib/types';
import { ApiError } from '@/app/lib/api';
import { installInteractiveDocument } from './helpers/interactiveDocument';

type SheetProps = ComponentProps<typeof QuickEditSheet>;
type ConflictProps = ComponentProps<typeof ContactEditConflictDialog>;
const state = vi.hoisted(() => {
    let sheet: SheetProps | undefined;
    let conflict: ConflictProps | undefined;
    return { sheet, conflict, workspace: 17, customDirty: false };
});
const api = vi.hoisted(() => ({ update: vi.fn(), load: vi.fn(), picture: vi.fn(), custom: vi.fn() }));
const ui = vi.hoisted(() => ({ close: vi.fn(), refresh: vi.fn(), report: vi.fn(), success: vi.fn() }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ refresh: ui.refresh }) }));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key }));
vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => ui.report }));
vi.mock('@/app/lib/toast', () => ({ toastInfo: vi.fn(), toastError: vi.fn(), toastSuccess: ui.success }));
vi.mock('@/app/lib/renderedWorkspace', () => ({
    renderedWorkspaceId: () => state.workspace,
    workspacePinnedRequest: (id: number) => ({ headers: { 'X-Workspace-Id': String(id) } }),
}));
vi.mock('@/app/lib/api', async () => ({
    ...await vi.importActual<typeof import('@/app/lib/api')>('@/app/lib/api'),
    updateContact: api.update, getContactById: api.load, uploadContactPictureRecord: api.picture,
}));
vi.mock('@/app/components/records/contacts/QuickEditSheet', () => ({
    default: (props: SheetProps) => { state.sheet = props; return props.customFieldsSlot; },
}));
vi.mock('@/app/components/records/contacts/ContactEditConflictDialog', () => ({
    default: (props: ConflictProps) => { state.conflict = props; return null; },
}));
vi.mock('@/app/components/records/CustomFieldsEditSection', () => ({
    CustomFieldsEditSection: ({ ref }: { ref: Ref<CustomFieldsEditHandle> }) => {
        useImperativeHandle(ref, () => ({ hasChanges: () => state.customDirty, save: api.custom }));
        return null;
    },
}));

const base: Contact = {
    id: 7, workspaceId: 17, version: 4, name: 'Contact', email: '', phone: '111', title: 'Director',
    imageUrl: '', createdAt: '', updatedAt: '',
};
const latest = { ...base, version: 5, title: 'VP' };
function sheet() {
    if (!state.sheet) throw new Error('Sheet did not render');
    return state.sheet;
}
async function mount() {
    const { createRoot } = await import('react-dom/client');
    const installed = installInteractiveDocument();
    const root = createRoot(installed.container);
    const render = async (contact = base, open = true) => {
        await act(async () => root.render(createElement(EditContactSheet, { contact, open, onOpenChange: ui.close })));
    };
    await render();
    return { render, close: async () => { await act(async () => root.unmount()); } };
}

beforeEach(() => {
    vi.clearAllMocks();
    state.sheet = undefined;
    state.conflict = undefined;
    state.workspace = 17;
    state.customDirty = false;
    api.load.mockResolvedValue(latest);
    api.update.mockResolvedValue({ ...base, version: 5, phone: '222' });
    api.custom.mockResolvedValue(undefined);
});
afterEach(() => vi.unstubAllGlobals());

describe('contact sheet concurrency', () => {
    it('keeps its original token on prop refresh, preserves refused input, and reloads only explicitly', async () => {
        api.update.mockRejectedValue(new ApiError('Changed', 409, 'STALE_RECORD'));
        const mounted = await mount();
        try {
            await act(async () => sheet().updateDraft(7, { phone: '222' }));
            await mounted.render(latest);
            expect(sheet().drafts[7]).toMatchObject({ expectedVersion: 4, title: 'Director', phone: '222' });
            await act(async () => sheet().saveEdits());
            expect(api.load).toHaveBeenCalledWith(7, { headers: { 'X-Workspace-Id': '17' } });
            expect(state.conflict?.conflict).toEqual({ base, current: latest });
            expect(ui.close).not.toHaveBeenCalled();
            const conflict = state.conflict;
            if (!conflict) throw new Error('Expected conflict dialog');
            await act(async () => conflict.onKeepEditing());
            expect(sheet().drafts[7]).toMatchObject({ expectedVersion: 4, phone: '222', title: 'Director' });
            await act(async () => sheet().saveEdits());
            expect(api.update).toHaveBeenLastCalledWith(7, expect.objectContaining({ expectedVersion: 4 }), expect.anything());
            const reopened = state.conflict;
            if (!reopened) throw new Error('Expected repeat conflict');
            await act(async () => reopened.onReload(latest));
            expect(sheet().drafts[7]).toMatchObject({ expectedVersion: 5, phone: '111', title: 'VP' });
        } finally { await mounted.close(); }
    });

    it('retains newer typing across a delayed save and retries against the acknowledged snapshot', async () => {
        const pending = Promise.withResolvers<Contact>();
        api.update.mockReturnValueOnce(pending.promise);
        const mounted = await mount();
        try {
            await act(async () => sheet().updateDraft(7, { phone: '222' }));
            let saving: Promise<void> | undefined;
            await act(async () => { saving = Promise.resolve(sheet().saveEdits()); });
            expect(sheet().isSaving).toBe(true);
            await act(async () => sheet().setEditSheetOpen(false));
            expect(ui.close).not.toHaveBeenCalled();
            await act(async () => sheet().updateDraft(7, { title: 'New typing' }));
            await act(async () => { pending.resolve({ ...base, version: 5, phone: '222' }); await saving; });
            expect(ui.close).not.toHaveBeenCalled();
            expect(sheet().drafts[7]).toMatchObject({ expectedVersion: 5, phone: '222', title: 'New typing' });
            api.update.mockResolvedValueOnce({ ...base, version: 6, phone: '222', title: 'New typing' });
            await act(async () => sheet().saveEdits());
            expect(api.update).toHaveBeenLastCalledWith(7, expect.objectContaining({ expectedVersion: 5, title: 'New typing' }), expect.anything());
            expect(ui.close).toHaveBeenCalledWith(false);
        } finally { await mounted.close(); }
    });

    it('pins a refused save and its failed latest read to the workspace that opened the form', async () => {
        api.update.mockRejectedValue(new ApiError('Changed', 409, 'STALE_RECORD'));
        api.load.mockRejectedValue(new TypeError('Offline'));
        state.customDirty = true;
        const mounted = await mount();
        try {
            await act(async () => sheet().updateDraft(7, { phone: '222' }));
            state.workspace = 99;
            await act(async () => sheet().saveEdits());
            const origin = { headers: { 'X-Workspace-Id': '17' } };
            expect(api.update).toHaveBeenLastCalledWith(7, expect.objectContaining({ expectedVersion: 4 }), origin);
            expect(api.load).toHaveBeenCalledWith(7, origin);
            expect(state.conflict?.conflict.current).toBeNull();
            expect(sheet().drafts[7].phone).toBe('222');
            expect(api.custom).not.toHaveBeenCalled();
        } finally { await mounted.close(); }
    });

    it('refuses to advance a draft past an intervening writer during a picture upload', async () => {
        const pending = Promise.withResolvers<Contact>();
        api.picture.mockReturnValueOnce(pending.promise);
        const mounted = await mount();
        try {
            await act(async () => sheet().updateImageFile?.(7, new File(['image'], 'image.png', { type: 'image/png' })));
            let saving: Promise<void> | undefined;
            await act(async () => { saving = Promise.resolve(sheet().saveEdits()); });
            await act(async () => sheet().updateDraft(7, { phone: '333' }));
            api.load.mockResolvedValueOnce({ ...base, phone: '222', title: 'Competing title', imageUrl: '/picture', version: 7 });
            await act(async () => { pending.resolve({ ...base, phone: '222', title: 'Competing title', imageUrl: '/picture', version: 7 }); await saving; });
            expect(sheet().drafts[7]).toMatchObject({ expectedVersion: 5, phone: '333', title: 'Director' });
            expect(state.conflict?.conflict.current).toMatchObject({ version: 7, title: 'Competing title' });
            expect(ui.close).not.toHaveBeenCalled();
            api.update.mockRejectedValueOnce(new ApiError('Changed', 409, 'STALE_RECORD'));
            await act(async () => sheet().saveEdits());
            expect(api.update).toHaveBeenLastCalledWith(7, expect.objectContaining({ expectedVersion: 5, phone: '333' }), expect.anything());
        } finally { await mounted.close(); }
    });

    it('retains the post-picture token when typing during a save keeps the sheet open', async () => {
        const pending = Promise.withResolvers<Contact>();
        api.picture.mockReturnValueOnce(pending.promise);
        const mounted = await mount();
        try {
            await act(async () => sheet().updateImageFile?.(7, new File(['image'], 'image.png', { type: 'image/png' })));
            let saving: Promise<void> | undefined;
            await act(async () => { saving = Promise.resolve(sheet().saveEdits()); });
            await act(async () => sheet().updateDraft(7, { title: 'During upload' }));
            await act(async () => { pending.resolve({ ...base, phone: '222', imageUrl: '/picture', version: 6 }); await saving; });
            expect(sheet().drafts[7]).toMatchObject({ expectedVersion: 6, title: 'During upload' });
            expect(ui.close).not.toHaveBeenCalled();
        } finally { await mounted.close(); }
    });
});
