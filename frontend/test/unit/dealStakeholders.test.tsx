/** @vitest-environment jsdom */
import { act, type ReactNode } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import DealStakeholders from '@/app/components/records/deals/DealStakeholders';
import { addDealPerson, removeDealPerson, updateDealPersonRole } from '@/app/lib/api';
import type { Contact } from '@/app/lib/types';
import messages from '@/messages/en/deals.json';

globalThis.IS_REACT_ACT_ENVIRONMENT = true;
const state = vi.hoisted(() => ({ canEdit: true, refresh: vi.fn(), showError: vi.fn() }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ refresh: state.refresh }) }));
vi.mock('@/app/hooks/usePermissions', () => ({ usePermission: () => state.canEdit }));
vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => state.showError }));
vi.mock('@/app/lib/renderedWorkspace', () => ({ renderedWorkspaceId: () => 11 }));
vi.mock('@/app/lib/toast', () => ({ toastSuccess: vi.fn() }));
vi.mock('@/app/lib/api', () => ({
    addDealPerson: vi.fn(), updateDealPersonRole: vi.fn(), removeDealPerson: vi.fn(),
    workspacePinnedRequest: (id: number) => ({ headers: { 'X-Workspace-Id': String(id) } }),
}));
vi.mock('@/app/hooks/useRecordTargetSearch', () => ({ useContactTargetSearch: () => ({
    contacts: [{ id: 7, name: 'Mika' }], serverSearch: {}, onInputValueChange: vi.fn(), error: null,
}) }));
vi.mock('@/app/components/records/contacts/ContactAvatar', () => ({ default: () => null }));
vi.mock('@/app/components/records/WarmthPill', () => ({ default: () => null }));
vi.mock('@/app/components/records/RecordSelect', () => ({ default: ({ id, value, onValueChange }: { id: string; value: string; onValueChange: (value: string) => void }) => (
    <select id={id} value={value} onChange={(event) => onValueChange(event.target.value)}><option value="">Choose</option><option value="7">Mika</option></select>
) }));
vi.mock('@/app/components/records/quick-edit/QuickEditSheetShell', () => ({ QuickEditSheetShell: ({ open, children, onSave, isSaving, interactionPending }: {
    open: boolean; children: ReactNode; onSave: () => void; isSaving: boolean; interactionPending: boolean;
}) => open ? <div data-pending={interactionPending}><div>{children}</div><button disabled={isSaving} onClick={onSave}>Save stakeholder</button></div> : null }));

let root: Root;
let container: HTMLDivElement;
const person: Contact = { id: 7, name: 'Mika', email: '', phone: '', title: '', imageUrl: '', createdAt: '', updatedAt: '' };

async function mount(people: { person: Contact; role: string | null }[] = []) {
    await act(async () => root.render(<NextIntlClientProvider locale="en" messages={messages} timeZone="UTC"><DealStakeholders dealId={5} people={people} temperatures={[]} /></NextIntlClientProvider>));
}
async function click(label: string) {
    const button = Array.from(container.querySelectorAll('button')).find((item) => item.textContent === label || item.getAttribute('aria-label') === label);
    if (!button) throw new Error(`Missing ${label}`);
    await act(async () => button.click());
}

beforeEach(() => {
    vi.clearAllMocks(); state.canEdit = true;
    container = document.createElement('div'); document.body.appendChild(container); root = createRoot(container);
    vi.mocked(addDealPerson).mockResolvedValue([]);
    vi.mocked(updateDealPersonRole).mockResolvedValue([]);
    vi.mocked(removeDealPerson).mockResolvedValue([]);
});
afterEach(async () => { await act(async () => root.unmount()); container.remove(); });

describe('direct deal stakeholders', () => {
    it('adds a workspace contact without a company or activity and pins the write', async () => {
        await mount(); await click('Add contact');
        const selector = container.querySelector('select');
        if (!selector) throw new Error('Missing contact picker');
        await act(async () => { selector.value = '7'; selector.dispatchEvent(new Event('change', { bubbles: true })); });
        await click('Save stakeholder');
        expect(addDealPerson).toHaveBeenCalledWith(5, 7, '', { headers: { 'X-Workspace-Id': '11' } });
        expect(state.refresh).toHaveBeenCalledOnce();
    });

    it('edits a role and removes only the selected stakeholder', async () => {
        await mount([{ person, role: 'Buyer' }]); await click('Edit role for Mika');
        await click('Save stakeholder');
        expect(updateDealPersonRole).toHaveBeenCalledWith(5, 7, 'Buyer', { headers: { 'X-Workspace-Id': '11' } });
        await click('Remove Mika from this deal');
        expect(removeDealPerson).toHaveBeenCalledWith(5, 7);
    });

    it('keeps the editor and role after a failed save', async () => {
        vi.mocked(updateDealPersonRole).mockRejectedValue(new Error('refused'));
        await mount([{ person, role: 'Buyer' }]); await click('Edit role for Mika');
        await click('Save stakeholder');
        expect(container.querySelector('input')?.value).toBe('Buyer');
        expect(state.showError).toHaveBeenCalledWith(expect.any(Error), 'saveFailed');
        expect(state.refresh).not.toHaveBeenCalled();
        expect(container.querySelector('input')?.maxLength).toBe(64);
    });

    it('locks the guarded sheet while a save is pending', async () => {
        let finish: (() => void) | undefined;
        vi.mocked(updateDealPersonRole).mockReturnValue(new Promise((resolve) => { finish = () => resolve([]); }));
        await mount([{ person, role: 'Buyer' }]); await click('Edit role for Mika');
        await click('Save stakeholder');
        expect(container.querySelector('[data-pending="true"]')).not.toBeNull();
        await act(async () => finish?.());
    });

    it('explains an empty contact selection without sending a request', async () => {
        await mount(); await click('Add contact'); await click('Save stakeholder');
        expect(container.querySelector('[role="alert"]')?.textContent).toBe('Choose a contact to add.');
        expect(addDealPerson).not.toHaveBeenCalled();
    });

    it('offers no mutation actions without deal update permission', async () => {
        state.canEdit = false;
        await mount([{ person, role: 'Buyer' }]);
        expect(container.querySelectorAll('button')).toHaveLength(0);
        expect(container.querySelector('a')?.getAttribute('href')).toBe('/records/contacts/7');
    });
});
