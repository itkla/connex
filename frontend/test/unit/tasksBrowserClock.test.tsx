/** @vitest-environment jsdom */
import { act, type PropsWithChildren, type SVGProps } from 'react';
import { createRoot } from 'react-dom/client';
import { within } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NowProvider, useNow } from '@/app/hooks/useNow';
import type { Task } from '@/app/lib/types';
import activityMessages from '@/messages/en/activity.json';
import commonMessages from '@/messages/en/common.json';

globalThis.IS_REACT_ACT_ENVIRONMENT = true;
const navigation = vi.hoisted(() => ({ pathname: '/dashboard' }));
vi.mock('next/navigation', () => ({
    useRouter: () => ({ refresh: vi.fn(), push: vi.fn(), replace: vi.fn() }),
    usePathname: () => navigation.pathname,
    useSearchParams: () => new URLSearchParams(),
}));
vi.mock('@/app/hooks/useWorkspace', () => ({ useWorkspace: () => ({ activeWorkspaceId: 9, switching: false }) }));
vi.mock('@/app/hooks/useIsMobile', () => ({ useIsMobile: () => false }));
vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => vi.fn() }));
vi.mock('@/app/components/activity/tasks/EditTaskSheet', () => ({ default: () => null }));
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
    FilterBar: ({ children }: PropsWithChildren) => <div>{children}</div>,
    SearchField: () => null,
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

function LayoutClock() {
    return <output aria-label="Original layout clock">{useNow()}</output>;
}

afterEach(() => {
    vi.useRealTimers();
    window.localStorage.clear();
});

describe('Tasks page navigation after profile midnight', () => {
    it('refreshes queues, counts and due labels while retaining the original provider timestamp', async () => {
        vi.useFakeTimers();
        const layoutTime = Date.parse('2026-11-04T14:59:00Z');
        vi.setSystemTime(layoutTime);
        navigation.pathname = '/dashboard';
        window.history.replaceState({}, '', '/dashboard');
        const deadlines: [string, string][] = [
            ['Yesterday deadline', '2026-11-04'],
            ['First today deadline', '2026-11-05'],
            ['Second today deadline', '2026-11-05'],
            ['Tomorrow deadline', '2026-11-06'],
        ];
        const tasks: Task[] = deadlines.map(([description, dueDate], index) => ({
            id: index + 1, description, dueDate, completed: false, status: 'todo', position: index,
            assignedToId: 7, createdAt: '2026-11-01T00:00:00', updatedAt: '2026-11-01T00:00:00',
        }));
        const container = document.createElement('div');
        document.body.appendChild(container);
        const root = createRoot(container);
        const renderPage = (tasksPage: boolean) => (
            <NextIntlClientProvider locale="en" timeZone="Asia/Tokyo" messages={{ ...commonMessages, ...activityMessages }}>
                <NowProvider value={layoutTime}>
                    <LayoutClock />
                    {tasksPage ? <TasksBrowser tasks={tasks} persons={[]} deals={[]} users={[]} currentUserId={7}
                        canDeleteTasks={false} originWorkspaceId={9} /> : <h1>Dashboard</h1>}
                </NowProvider>
            </NextIntlClientProvider>
        );
        try {
            await act(async () => root.render(renderPage(false)));
            vi.setSystemTime(Date.parse('2026-11-04T15:01:00Z'));
            navigation.pathname = '/activity/tasks';
            window.history.replaceState({}, '', '/activity/tasks');
            await act(async () => root.render(renderPage(true)));
            expect(within(container).getByLabelText('Original layout clock').textContent).toBe(String(layoutTime));
            const nav = within(container).getByRole('navigation');
            const dueToday = within(nav).getByRole('button', { name: /Due today/ });
            const overdue = within(nav).getByRole('button', { name: /Overdue/ });
            expect(dueToday.lastElementChild?.textContent).toBe('2');
            expect(overdue.lastElementChild?.textContent).toBe('1');
            const row = (name: string) => {
                const item = within(container).getAllByRole('button', { name: `Open task: ${name}` }).find((button) => button.hasAttribute('aria-keyshortcuts'))?.closest('li');
                if (!item) throw new Error('Task row missing: ' + name);
                return item;
            };
            expect(within(row('Yesterday deadline')).getByText('Yesterday', { exact: true })).toBeDefined();
            expect(within(row('First today deadline')).getByText('Today', { exact: true })).toBeDefined();
            expect(within(row('Tomorrow deadline')).getByText('Tomorrow', { exact: true })).toBeDefined();
            const groups = within(container).getAllByRole('heading', { level: 3 });
            expect(groups.map((heading) => heading.textContent)).toEqual(['Overdue', 'Today', 'Upcoming']);
            expect(groups.map((heading) => {
                const section = heading.closest('section');
                if (!section) throw new Error('Task group missing');
                return within(section).getAllByRole('listitem').length;
            })).toEqual([1, 2, 1]);
            await act(async () => overdue.click());
            expect(within(container).getAllByRole('listitem')).toHaveLength(1);
            expect(row('Yesterday deadline')).toBeDefined();
            await act(async () => dueToday.click());
            expect(within(container).getAllByRole('listitem')).toHaveLength(2);
            expect(row('First today deadline')).toBeDefined();
            expect(row('Second today deadline')).toBeDefined();
            expect(within(container).queryByRole('button', { name: 'Open task: Yesterday deadline' })).toBeNull();
        } finally {
            await act(async () => root.unmount());
            container.remove();
        }
    });
});
