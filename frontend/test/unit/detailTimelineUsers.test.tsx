/** @vitest-environment jsdom */

import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import type { ComponentProps } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import TimelineContent from '@/app/components/me/TimelineContent';
import type TimelineRow from '@/app/components/me/TimelineRow';
import { DetailSectionsProvider, type DetailSectionResults } from '@/app/hooks/useDetailSections';
import type { Activity, ContactLifecycleHistoryEntry, Note, Task, UserReference } from '@/app/lib/types';
import enRecords from '@/messages/en/records.json';
import enMe from '@/messages/en/me.json';
import enDeals from '@/messages/en/deals.json';
import enContacts from '@/messages/en/contacts.json';
import jaRecords from '@/messages/ja/records.json';
import jaMe from '@/messages/ja/me.json';
import jaDeals from '@/messages/ja/deals.json';
import jaContacts from '@/messages/ja/contacts.json';

const api = vi.hoisted(() => ({ activities: vi.fn(), notes: vi.fn(), tasks: vi.fn(), history: vi.fn(), users: vi.fn(), report: vi.fn() }));
const scope = vi.hoisted(() => ({ workspaceId: 1 }));
vi.mock('@/app/lib/api', async (original) => ({
    ...await original<typeof import('@/app/lib/api')>(), getActivitiesForDeal: api.activities,
    getNotesForDeal: api.notes, getTasksForDeal: api.tasks, getContactLifecycleHistory: api.history, getUserReferences: api.users,
}));
vi.mock('@/app/lib/renderedWorkspace', async (original) => ({ ...await original<typeof import('@/app/lib/renderedWorkspace')>(), renderedWorkspaceId: () => scope.workspaceId }));
vi.mock('@/app/hooks/useWorkspace', () => ({ useWorkspace: () => ({ activeWorkspaceId: scope.workspaceId, switching: false }) }));
vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => api.report }));
vi.mock('@/app/components/me/TimelineDeepLinkFallback', () => ({ default: () => null }));
vi.mock('@/app/components/me/TimelineRow', () => ({ default: ({ entry, author }: ComponentProps<typeof TimelineRow>) => <li>
    <p>{entry.kind === 'activity' ? entry.activity.subject : entry.kind}</p>
    <span>{author?.displayName ?? 'Unknown author'}</span><span>{author?.profilePictureUrl}</span>
</li> }));

const colleague: UserReference = { id: 29, displayName: 'Recovered colleague', profilePictureUrl: '/api/users/29/profile-picture' };
const known: UserReference = { id: 7, displayName: 'Known colleague' };
const seeds = [known];
const activity: Activity = { id: 4, subject: 'Recovered meeting', type: 'meeting', createdById: colleague.id, timestamp: '2026-11-06T00:00:00Z' };
const task: Task = { id: 5, assignedToId: known.id, description: 'Known task', completed: false, status: 'todo', position: 0, createdAt: '', updatedAt: '' };
const note: Note = { id: 6, author: colleague.id, content: 'Recovered note', createdAt: '', updatedAt: '' };
const history: ContactLifecycleHistoryEntry = { id: 8, personId: 1, changedById: colleague.id, changedAt: '', fromStage: null, toStage: null, reason: null, reasonLabel: null, note: null };

function initialDeal(): DetailSectionResults {
    return { dealActivities: { kind: 'failed', status: 503 }, dealNotes: { kind: 'loaded', data: [] }, dealTasks: { kind: 'loaded', data: [task] } };
}

function view(initial: DetailSectionResults, locale: 'en' | 'ja' = 'en', detailKind: 'deal' | 'contact' = 'deal') {
    const messages = locale === 'en' ? { ...enRecords, ...enMe, ...enDeals, ...enContacts } : { ...jaRecords, ...jaMe, ...jaDeals, ...jaContacts };
    return <NextIntlClientProvider locale={locale} messages={messages} timeZone="UTC">
        <DetailSectionsProvider recordId={1} workspaceId={1} initial={initial}>
            <TimelineContent detailKind={detailKind} originWorkspaceId={1} tasks={[task]} activities={[]} notes={[]} users={seeds} />
        </DetailSectionsProvider>
    </NextIntlClientProvider>;
}

beforeEach(() => {
    vi.resetAllMocks();
    scope.workspaceId = 1;
    api.activities.mockResolvedValue([activity]);
    api.users.mockResolvedValue([colleague]);
});
afterEach(cleanup);

describe('recovered timeline authors', () => {
    it.each(['en', 'ja'] as const)('hydrates the missing colleague after activity retry in %s and retains known users', async (locale) => {
        api.activities.mockResolvedValue([activity, { ...activity, id: 9 }, { ...activity, id: 10, createdById: known.id }]);
        render(view(initialDeal(), locale));
        expect(api.users).not.toHaveBeenCalled();
        const records = locale === 'en' ? enRecords : jaRecords;
        fireEvent.click(screen.getByRole('button', { name: records.RecordListState.retry }));
        await screen.findAllByText(colleague.displayName);
        expect(api.users).toHaveBeenCalledExactlyOnceWith([colleague.id], expect.objectContaining({
            headers: { 'X-Workspace-Id': '1' }, signal: expect.any(AbortSignal),
        }));
        expect(screen.getAllByText(colleague.profilePictureUrl ?? '')).toHaveLength(2);
        expect(screen.getAllByText(known.displayName)).toHaveLength(2);
        expect(screen.queryByText('Unknown author')).toBeNull();
        expect(api.notes).not.toHaveBeenCalled();
        expect(api.tasks).not.toHaveBeenCalled();
    });

    it.each(['dealTasks', 'dealNotes', 'contactHistory'] as const)('hydrates author references from recovered %s', async (key) => {
        const initial = initialDeal();
        initial.dealActivities = { kind: 'loaded', data: [] };
        initial[key] = { kind: 'failed', status: 503 };
        api.tasks.mockResolvedValue([{ ...task, assignedToId: colleague.id }]);
        api.notes.mockResolvedValue([note]);
        api.history.mockResolvedValue([history]);
        render(view(initial, 'en', key === 'contactHistory' ? 'contact' : 'deal'));
        fireEvent.click(screen.getByRole('button', { name: enRecords.RecordListState.retry }));
        await screen.findByText(colleague.displayName);
        expect(api.users).toHaveBeenCalledExactlyOnceWith([colleague.id], expect.objectContaining({ headers: { 'X-Workspace-Id': '1' } }));
    });

    it('merges successive recovered authors without refetching a hydrated colleague', async () => {
        const later: UserReference = { id: 31, displayName: 'Later colleague' };
        let finishNotes: (notes: Note[]) => void = () => undefined;
        api.notes.mockImplementationOnce(() => new Promise<Note[]>((resolve) => { finishNotes = resolve; }));
        api.users.mockResolvedValueOnce([colleague]).mockResolvedValueOnce([later]);
        const initial = initialDeal();
        initial.dealNotes = { kind: 'failed', status: 503 };
        render(view(initial));
        fireEvent.click(screen.getByRole('button', { name: enRecords.RecordListState.retry }));
        await waitFor(() => expect(api.users).toHaveBeenCalledOnce());
        await act(async () => { finishNotes([{ ...note, author: later.id }]); });
        await screen.findByText(later.displayName);
        expect(screen.getByText(colleague.displayName)).toBeDefined();
        expect(screen.getByText(known.displayName)).toBeDefined();
        expect(api.users.mock.calls.map(([ids]) => ids)).toEqual([[colleague.id], [later.id]]);
    });

    it('makes no hydration calls for initially successful sections or already-known recovered authors', async () => {
        const initial = initialDeal();
        initial.dealActivities = { kind: 'loaded', data: [activity] };
        const { rerender } = render(view(initial));
        expect(api.users).not.toHaveBeenCalled();
        api.activities.mockResolvedValue([{ ...activity, createdById: known.id }]);
        rerender(view(initialDeal()));
        fireEvent.click(screen.getByRole('button', { name: enRecords.RecordListState.retry }));
        await screen.findByText(activity.subject);
        expect(api.users).not.toHaveBeenCalled();
        expect(screen.queryByText('Unknown author')).toBeNull();
    });

    it('keeps an omitted or invisible author unknown without repeatedly fetching its reference', async () => {
        api.users.mockResolvedValue([]);
        const initial = initialDeal();
        const { rerender } = render(view(initial));
        fireEvent.click(screen.getByRole('button', { name: enRecords.RecordListState.retry }));
        await screen.findByText(activity.subject);
        await waitFor(() => expect(api.users).toHaveBeenCalledOnce());
        rerender(view(initial));
        expect(screen.getByText('Unknown author')).toBeDefined();
        expect(screen.getByText(known.displayName)).toBeDefined();
        expect(api.users).toHaveBeenCalledOnce();
    });

    it('reports reference failure while preserving recovered entries and existing authors', async () => {
        const error = new Error('Reference lookup unavailable');
        api.users.mockRejectedValue(error);
        render(view(initialDeal()));
        fireEvent.click(screen.getByRole('button', { name: enRecords.RecordListState.retry }));
        await waitFor(() => expect(api.report).toHaveBeenCalledExactlyOnceWith(error));
        expect(screen.getByText(activity.subject)).toBeDefined();
        expect(screen.getByText(known.displayName)).toBeDefined();
        expect(screen.getByText('Unknown author')).toBeDefined();
    });

    it.each(['workspace', 'snapshot', 'unmount'] as const)('ignores a stale reference response after %s changes', async (change) => {
        let finish: (users: UserReference[]) => void = () => undefined;
        let signal: AbortSignal | undefined;
        api.users.mockImplementationOnce((_ids: number[], init: RequestInit) => {
            signal = init.signal ?? undefined;
            return new Promise<UserReference[]>((resolve) => { finish = resolve; });
        });
        const { rerender, unmount } = render(view(initialDeal()));
        fireEvent.click(screen.getByRole('button', { name: enRecords.RecordListState.retry }));
        await waitFor(() => expect(api.users).toHaveBeenCalledOnce());
        if (change === 'workspace') scope.workspaceId = 2;
        else if (change === 'unmount') unmount();
        else {
            const refreshed = initialDeal();
            refreshed.dealActivities = { kind: 'loaded', data: [activity] };
            rerender(view(refreshed));
        }
        await act(async () => { finish([colleague]); });
        expect(screen.queryByText(colleague.displayName)).toBeNull();
        expect(api.report).not.toHaveBeenCalled();
        if (change !== 'workspace') expect(signal?.aborted).toBe(true);
    });
});
