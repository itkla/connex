import { act, createElement, Fragment, type ReactNode, type ButtonHTMLAttributes } from 'react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import ChangeCompanyDialog from '@/app/components/records/contacts/ChangeCompanyDialog';
import type { Company, Contact } from '@/app/lib/types';
import { ApiError } from '@/app/lib/api';
import { installInteractiveDocument } from './helpers/interactiveDocument';

const state = vi.hoisted(() => {
    let choose: ((company: Company | null) => void) | undefined;
    let reload: ((contact: Contact) => void) | undefined;
    return { choose, reload };
});
const api = vi.hoisted(() => ({ update: vi.fn(), load: vi.fn(), report: vi.fn(), success: vi.fn() }));
const companyC: Company = { id: 30, name: 'C', website: '', industry: '', phone: '', address: '', logoUrl: '', createdAt: '', updatedAt: '' };
const companyD: Company = { ...companyC, id: 40, name: 'D' };
const first: Contact = { id: 7, version: 4, name: 'First', email: '', phone: '', title: '', imageUrl: '', createdAt: '', updatedAt: '' };
const second: Contact = { ...first, id: 8, name: 'Second' };
const translate = (key: string) => key;
vi.mock('next/navigation', () => ({ useRouter: () => ({ refresh: vi.fn() }) }));
vi.mock('next-intl', () => ({ useTranslations: () => translate }));
vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => api.report }));
vi.mock('@/app/lib/toast', () => ({ toastError: vi.fn(), toastSuccess: api.success }));
vi.mock('@/app/lib/renderedWorkspace', () => ({ renderedWorkspaceId: () => 17, workspacePinnedRequest: () => ({ headers: { 'X-Workspace-Id': '17' } }) }));
vi.mock('@/app/lib/api', async () => ({
    ...await vi.importActual<typeof import('@/app/lib/api')>('@/app/lib/api'),
    updateContact: api.update, getContactById: api.load,
}));
vi.mock('@/app/hooks/useCompanySearch', () => ({
    useCompanySearch: () => ({ companies: [companyC, companyD], options: [companyC, companyD], serverSearch: false, onInputValueChange: vi.fn() }),
}));
vi.mock('@/app/components/records/contacts/ContactEditConflictDialog', () => ({
    default: ({ onReload }: { onReload: (contact: Contact) => void }) => { state.reload = onReload; return null; },
}));
vi.mock('@/components/ui/combobox', () => {
    const Pass = ({ children }: { children?: ReactNode }) => createElement(Fragment, null, children);
    return {
        Combobox: ({ children, onValueChange }: { children?: ReactNode; onValueChange: (company: Company | null) => void }) => {
            state.choose = onValueChange;
            return createElement(Fragment, null, children);
        },
        ComboboxContent: Pass, ComboboxEmpty: Pass, ComboboxInput: Pass, ComboboxItem: Pass,
        ComboboxList: () => null,
    };
});
vi.mock('@/components/ui/dialog', () => {
    const Pass = ({ children }: { children?: ReactNode }) => createElement(Fragment, null, children);
    return { Dialog: Pass, DialogClose: Pass, DialogContent: Pass, DialogDescription: Pass, DialogFooter: Pass, DialogHeader: Pass, DialogTitle: Pass };
});
vi.mock('@/components/ui/input-group', () => ({ InputGroupAddon: () => null }));
vi.mock('@/components/ui/label', () => ({ Label: () => null }));
vi.mock('@/components/ui/dialog-status-cover', () => ({ DialogStatusCover: () => null, resolveDialogStatus: () => 'idle' }));
vi.mock('@/components/ui/button', () => ({
    Button: ({ children, disabled, type }: ButtonHTMLAttributes<HTMLButtonElement>) => createElement('button', { disabled, type }, children),
}));

beforeEach(() => {
    vi.clearAllMocks();
    api.load.mockImplementation(async (id: number) => id === 7 ? first : second);
});
afterEach(() => vi.unstubAllGlobals());

it('revisits an acknowledged company batch row when the target changes after a later refusal', async () => {
    const { createRoot } = await import('react-dom/client');
    const installed = installInteractiveDocument();
    const root = createRoot(installed.container);
    const firstSaved = { ...first, companyId: 30, version: 5 };
    const changedSecond = { ...second, title: 'Competing title', version: 5 };
    api.update.mockResolvedValueOnce(firstSaved).mockRejectedValueOnce(new ApiError('Changed', 409, 'STALE_RECORD'));
    try {
        await act(async () => root.render(createElement(ChangeCompanyDialog, { contacts: [first, second], open: true, onOpenChange: vi.fn() })));
        const form = installed.elements.find((element) => element.tagName === 'FORM');
        if (!form || !state.choose) throw new Error('Company editor did not render');
        await act(async () => state.choose?.(companyC));
        api.load.mockResolvedValueOnce(changedSecond);
        await act(async () => installed.dispatch('submit', form));
        expect(api.update).toHaveBeenCalledTimes(2);
        expect(api.success).not.toHaveBeenCalled();
        if (!state.reload) throw new Error('Expected refused batch conflict');
        await act(async () => state.reload?.(changedSecond));
        await act(async () => state.choose?.(companyD));
        api.update.mockResolvedValueOnce({ ...firstSaved, companyId: 40, version: 6 })
            .mockRejectedValueOnce(new ApiError('Changed again', 409, 'STALE_RECORD'));
        await act(async () => installed.dispatch('submit', form));
        expect(api.update).toHaveBeenNthCalledWith(3, 7, expect.objectContaining({ companyId: 40, expectedVersion: 5 }), expect.anything());
        expect(api.update).toHaveBeenNthCalledWith(4, 8, expect.objectContaining({ companyId: 40, expectedVersion: 5, title: 'Competing title' }), expect.anything());
        expect(api.success).not.toHaveBeenCalled();
    } finally { await act(async () => root.unmount()); }
});
