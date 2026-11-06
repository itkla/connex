/** @vitest-environment jsdom */
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ActivityDialogForm } from '@/app/components/activity/activities/ActivityDialog';
import { TaskDialogForm } from '@/app/components/activity/tasks/TaskDialog';
import { bindRenderedWorkspace, releaseRenderedWorkspace } from '@/app/lib/renderedWorkspace';
import { subscribeToRecordMutations } from '@/app/lib/record-mutation-events';
import { contact, deal } from './helpers/workspaceChainBackend';

const search = vi.hoisted(() => ({ contact: vi.fn(), deal: vi.fn() }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ refresh: vi.fn() }) }));
vi.mock('next-intl', () => ({ useTranslations: () => (key: string) => key, useTimeZone: () => 'UTC' }));
vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => vi.fn() }));
vi.mock('@/app/lib/toast', () => ({ toastSuccess: vi.fn(), toastError: vi.fn() }));
vi.mock('@/app/components/activity/notes/MentionEditor', () => ({
    default: ({ id, value, onChange }: { id?: string; value: string; onChange: (value: string) => void }) =>
        <textarea id={id} value={value} onChange={(event) => onChange(event.target.value)} />,
}));
vi.mock('@/components/ui/dialog-status-cover', async (importOriginal) => ({
    ...await importOriginal<typeof import('@/components/ui/dialog-status-cover')>(), DialogStatusCover: () => null,
}));
vi.mock('@/app/hooks/useRecordTargetSearch', () => ({
    useContactTargetSearch: (enabled: boolean) => { search.contact(enabled); return { contacts: [{ ...contact, id: 99, name: 'Other company contact' }] }; },
    useDealTargetSearch: (enabled: boolean) => { search.deal(enabled); return { deals: [{ ...deal, id: 99, name: 'Other company deal' }] }; },
    useMemberTargetSearch: () => ({ users: [] }),
}));

const shell = {};
beforeEach(() => {
    search.contact.mockClear();
    search.deal.mockClear();
    bindRenderedWorkspace(shell, 9);
    vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} });
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => setTimeout(() => callback(0), 0));
    vi.stubGlobal('cancelAnimationFrame', clearTimeout);
    vi.stubGlobal('matchMedia', (media: string) => ({ media, matches: false, addEventListener() {}, removeEventListener() {} }));
    HTMLElement.prototype.scrollIntoView = vi.fn();
});
afterEach(() => { cleanup(); releaseRenderedWorkspace(shell); vi.unstubAllGlobals(); });

describe('record-scoped composer choices', () => {
    it.each(['activity', 'task'] as const)('%s retains its anchor and limits choices to the supplied company', async (kind) => {
        const mutated = vi.fn();
        const unsubscribe = subscribeToRecordMutations(mutated);
        try {
        for (const anchor of ['person', 'deal'] as const) {
            const createRequest = vi.fn().mockResolvedValue({ id: 1 });
            const props = {
                persons: [contact], deals: [deal], currentUserId: 7, recordScope: anchor,
                defaultPerson: anchor === 'person' ? contact : null,
                defaultDeal: anchor === 'deal' ? deal : null,
                onSubmittingChange: vi.fn(), onCancel: vi.fn(), onClose: vi.fn(), createRequest,
            };
            const view = render(kind === 'activity'
                ? <ActivityDialogForm {...props} defaultSubject="Scoped activity" />
                : <TaskDialogForm {...props} users={[]} defaultDueDate="" defaultDescription="Scoped task" />);
            const anchorInput = screen.getByLabelText(anchor === 'person' ? 'personLabel' : 'dealLabel');
            expect(anchorInput.hasAttribute('disabled')).toBe(true);
            const otherInput = screen.getByLabelText(anchor === 'person' ? 'dealLabel' : 'personLabel');
            act(() => otherInput.focus());
            fireEvent.keyDown(otherInput, { key: 'ArrowDown' });
            const option = await screen.findByRole('option', { name: anchor === 'person' ? deal.name : contact.name });
            expect(screen.queryByRole('option', { name: /Other company/ })).toBeNull();
            fireEvent.click(option);
            const form = view.container.querySelector('form');
            if (!form) throw new Error('Missing composer form');
            fireEvent.submit(form);
            await waitFor(() => expect(createRequest).toHaveBeenCalledWith(expect.objectContaining({ personId: contact.id, dealId: deal.id }), expect.anything()));
            expect(search.contact.mock.calls.every(([enabled]) => enabled === false)).toBe(true);
            expect(search.deal.mock.calls.every(([enabled]) => enabled === false)).toBe(true);
            expect(mutated).toHaveBeenLastCalledWith(kind);
            view.unmount();
        }
        } finally { unsubscribe(); }
    });

    it.each(['activity', 'task'] as const)('%s with no company keeps the opposite picker empty', async (kind) => {
        const props = { persons: [contact], deals: [], currentUserId: 7, recordScope: 'person' as const,
            defaultPerson: contact, defaultDeal: null, onSubmittingChange: vi.fn(), onCancel: vi.fn(), onClose: vi.fn() };
        render(kind === 'activity' ? <ActivityDialogForm {...props} />
            : <TaskDialogForm {...props} users={[]} defaultDueDate="" defaultDescription="" />);
        const input = screen.getByLabelText('dealLabel');
        act(() => input.focus());
        fireEvent.keyDown(input, { key: 'ArrowDown' });
        expect(screen.queryAllByRole('option')).toHaveLength(0);
        expect(search.deal).toHaveBeenCalledWith(false);
    });
});
