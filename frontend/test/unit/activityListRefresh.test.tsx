/** @vitest-environment jsdom */
import { act, type PropsWithChildren, type ReactNode, type SVGProps } from 'react';
import { createRoot } from 'react-dom/client';
import { waitFor, within } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NowProvider } from '@/app/hooks/useNow';
import { getTasksPage, getActivitiesPage, getTaskById } from '@/app/lib/api';
import type { Activity, Contact, Task, TasksPageParams } from '@/app/lib/types';
import activityMessages from '@/messages/en/activity.json';
import commonMessages from '@/messages/en/common.json';

globalThis.IS_REACT_ACT_ENVIRONMENT = true;
const navigation = vi.hoisted(() => ({ pathname: '/activity/tasks', search: '', storageReady: true }));
const fixture = vi.hoisted<{ tasks: Task[]; activities: Activity[]; contact: Contact | null }>(() => ({ tasks: [], activities: [], contact: null }));
vi.mock('@/app/lib/api', async (original) => ({
    ...await original<typeof import('@/app/lib/api')>(),
    getTaskById: vi.fn(),
    getActivitiesPage: vi.fn(async () => ({ items: fixture.activities, total: fixture.activities.length })),
    getTasksPage: vi.fn(async (params: TasksPageParams) => {
        const items = fixture.tasks.filter((task) => params.queue === 'unassigned' ? !task.assignedToId : params.queue === 'overdue' ? (task.dueDate ?? '') < '2026-11-05'
            : params.queue === 'dueToday' ? task.dueDate === '2026-11-05' : true);
        return { items, total: items.length };
    }),
}));
vi.mock('@/app/hooks/useRecordTargetSearch', () => {
    const result = { serverSearch: { query: '', pending: false, accepts: () => true }, onInputValueChange: vi.fn() };
    return {
        useContactTargetSearch: (_enabled: boolean, ids: Array<number | null>) => ({ ...result, contacts: fixture.contact && ids.includes(fixture.contact.id) ? [fixture.contact] : [] }),
        useDealTargetSearch: () => ({ ...result, deals: [] }),
        useMemberTargetSearch: () => ({ ...result, users: [] }),
    };
});
vi.mock('@/app/hooks/useCompanySearch', () => ({ useCompanySearch: () => ({ companies: [], serverSearch: { query: '', pending: false, accepts: () => true }, onInputValueChange: vi.fn() }) }));
vi.mock('@/app/components/activity/ActivityListPagination', () => ({ default: ({ total }: { total: number }) => <output aria-label="Paged total">{total}</output> }));
vi.mock('next/navigation', () => ({
    useRouter: () => ({ refresh: vi.fn(), push: vi.fn(), replace: vi.fn() }),
    usePathname: () => navigation.pathname,
    useSearchParams: () => new URLSearchParams(navigation.search),
}));
vi.mock('@/app/hooks/useWorkspace', () => ({ useWorkspace: () => ({ activeWorkspaceId: 9, switching: false }) }));
vi.mock('@/app/hooks/useIsMobile', () => ({ useIsMobile: () => false }));
vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => vi.fn() }));
vi.mock('@/app/components/activity/tasks/EditTaskSheet', () => ({ default: ({ companyId }: { companyId?: number | null }) => <output aria-label="Editor company">{companyId ?? 'none'}</output> }));
vi.mock('@/app/components/activity/tasks/TaskDialog', () => ({ default: () => null }));
vi.mock('@/app/components/activity/tasks/TaskFilterSheet', () => ({ default: () => null }));
vi.mock('@/app/components/activity/tasks/TasksKanban', () => ({ default: () => null }));
vi.mock('@/app/components/records/DeleteRecordDialog', () => ({ default: () => null }));
vi.mock('@/app/components/records/RecordActionMenu', () => ({
    RecordContextMenu: ({ children }: PropsWithChildren) => children,
    RecordActionMenuTrigger: () => null,
}));
vi.mock('@/app/components/activity/notes/NoteContent', () => ({ default: ({ content }: { content: string }) => <span>{content}</span> }));
vi.mock('@/app/components/motion/Rise', () => ({ default: ({ children }: PropsWithChildren) => children }));
vi.mock('@/app/components/PageShell', () => ({ PageShell: ({ children }: PropsWithChildren) => <main>{children}</main> }));
vi.mock('@/app/components/PageHeader', () => ({ PageHeader: ({ title }: { title: string }) => <h1>{title}</h1> }));
vi.mock('@/components/ui/tooltip', () => {
    const Content = ({ children }: PropsWithChildren) => children;
    return { Tooltip: Content, TooltipContent: Content, TooltipTrigger: Content };
});
vi.mock('@/app/components/filters', () => ({
    FilterBar: ({ children, search }: PropsWithChildren<{ search?: ReactNode }>) => <div>{search}{children}</div>,
    SearchField: ({ value, onChange }: { value: string; onChange: (value: string) => void }) => <input aria-label="Task search" value={value} onChange={(event) => onChange(event.target.value)} />,
    MultiSelectFilter: () => null,
}));
vi.mock('motion/react', () => ({
    useReducedMotion: () => true,
    AnimatePresence: ({ children }: PropsWithChildren) => children,
    motion: {
        circle: ({ cx, cy, r, stroke, strokeWidth, fill }: SVGProps<SVGCircleElement>) => <circle cx={cx} cy={cy} r={r} stroke={stroke} strokeWidth={strokeWidth} fill={fill} />,
        div: ({ children, className }: PropsWithChildren<{ className?: string }>) => <div className={className}>{children}</div>,
        span: ({ children, className }: PropsWithChildren<{ className?: string }>) => <span className={className}>{children}</span>,
        li: ({ children, className }: PropsWithChildren<{ className?: string }>) => <li className={className}>{children}</li>,
    },
}));

import TasksBrowser from '@/app/components/activity/tasks/TasksBrowser';


import ActivitiesBrowser from '@/app/components/activity/activities/ActivitiesBrowser';
import { publishRecordMutation } from '@/app/lib/record-mutation-events';
vi.mock('@/app/hooks/useAccountStorageGeneration', () => ({ useAccountStorageGeneration: () => () => navigation.storageReady }));
vi.mock('@/app/components/activity/activities/EditActivitySheet', () => ({ default: () => null }));
vi.mock('@/app/components/activity/activities/ActivityDialog', () => ({ default: () => null }));
vi.mock('@/app/hooks/useRecordReturnSelection', () => ({ useRecordReturnScroll: () => null }));
vi.mock('next/link', () => ({ default: ({ children, href }: PropsWithChildren<{ href: string }>) => <a href={href}>{children}</a> }));

const task: Task = { id: 81, workspaceId: 9, description: 'Global created task', assignedToId: 7, status: 'todo', position: 0,
    completed: false, createdAt: '', updatedAt: '' };
const activity: Activity = { id: 82, subject: 'Global created activity', type: 'Call', createdById: 7, timestamp: '2026-11-06 12:00:00' };
beforeEach(() => {
    fixture.tasks = []; fixture.activities = []; fixture.contact = null;
    navigation.search = ''; navigation.storageReady = true;
    vi.mocked(getTasksPage).mockClear(); vi.mocked(getActivitiesPage).mockClear();
    vi.mocked(getTaskById).mockReset();
    window.localStorage.clear();
});
afterEach(() => window.localStorage.clear());

function page(kind: 'task' | 'activity') {
    return <NextIntlClientProvider locale="en" timeZone="UTC" messages={{ ...commonMessages, ...activityMessages }}>
        <NowProvider value={Date.now()}>{kind === 'task'
            ? <TasksBrowser currentUserId={7} canDeleteTasks={false} originWorkspaceId={9} />
            : <ActivitiesBrowser currentUserId={7} originWorkspaceId={9} />}</NowProvider>
    </NextIntlClientProvider>;
}

describe('paged list mutations and hydration', () => {
    it.each(['task', 'activity'] as const)('%s list reloads rows and totals after a mutation outside its own dialogs', async (kind) => {
        navigation.pathname = kind === 'task' ? '/activity/tasks' : '/activity/all';
        const container = document.createElement('div'); document.body.appendChild(container);
        const root = createRoot(container);
        try {
            await act(async () => root.render(page(kind)));
            await waitFor(() => expect(within(container).getByLabelText('Paged total').textContent).toBe('0'));
            fixture.tasks = [task]; fixture.activities = [activity];
            await act(async () => publishRecordMutation(kind));
            await waitFor(() => expect(within(container).getByLabelText('Paged total').textContent).toBe('1'));
            expect(within(container).getByText(kind === 'task' ? task.description : activity.subject)).toBeDefined();
        } finally { await act(async () => root.unmount()); container.remove(); }
    });

    it('hydrates the linked contact of a task outside the current page and default picker results', async () => {
        navigation.pathname = '/activity/tasks'; navigation.search = 'task=81';
        fixture.contact = { id: 777, name: 'Contact outside picker page', companyId: 42, email: '', phone: '', title: '', imageUrl: '', createdAt: '', updatedAt: '' };
        vi.mocked(getTaskById).mockResolvedValue({ ...task, personId: 777 });
        const container = document.createElement('div'); document.body.appendChild(container);
        const root = createRoot(container);
        try {
            await act(async () => root.render(page('task')));
            await waitFor(() => expect(within(container).getByLabelText('Editor company').textContent).toBe('42'));
            expect(fixture.tasks).toEqual([]);
        } finally { await act(async () => root.unmount()); container.remove(); }
    });

    it('retains an activity type and page while account storage is binding', async () => {
        navigation.pathname = '/activity/all'; navigation.search = 'page=2&type=Call'; navigation.storageReady = false;
        const load = vi.mocked(getActivitiesPage); const original = load.getMockImplementation();
        load.mockResolvedValue({ items: [activity], total: 26 });
        const container = document.createElement('div'); document.body.appendChild(container);
        const root = createRoot(container);
        try {
            await act(async () => root.render(page('activity')));
            navigation.storageReady = true;
            await act(async () => root.render(page('activity')));
            await waitFor(() => expect(within(container).getByLabelText('Paged total').textContent).toBe('26'));
            expect(load.mock.calls.every(([params]) => params?.page === 2 && params.type === 'Call')).toBe(true);
        } finally {
            await act(async () => root.unmount()); container.remove();
            if (original) load.mockImplementation(original);
        }
    });

    it('retains page two while the URL queue becomes bound to account storage after hydration', async () => {
        navigation.pathname = '/activity/tasks'; navigation.search = 'page=2&queue=allOpen'; navigation.storageReady = false;
        const load = vi.mocked(getTasksPage); const original = load.getMockImplementation();
        load.mockResolvedValue({ items: [task], total: 26 });
        const container = document.createElement('div'); document.body.appendChild(container);
        const root = createRoot(container);
        try {
            await act(async () => root.render(page('task')));
            navigation.storageReady = true;
            await act(async () => root.render(page('task')));
            await waitFor(() => expect(within(container).getByLabelText('Paged total').textContent).toBe('26'));
            expect(load.mock.calls.every(([params]) => params?.page === 2 && params.queue === 'allOpen')).toBe(true);
        } finally {
            await act(async () => root.unmount()); container.remove();
            if (original) load.mockImplementation(original);
        }
    });
});
