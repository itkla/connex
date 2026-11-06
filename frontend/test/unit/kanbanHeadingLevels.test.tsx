/** @vitest-environment jsdom */
import { cleanup, render, screen } from '@testing-library/react';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import KanbanBoard from '@/app/components/kanban/KanbanBoard';
import DealKanbanCard from '@/app/components/records/deals/DealKanbanCard';
import TasksKanban from '@/app/components/activity/tasks/TasksKanban';
import { TooltipProvider } from '@/components/ui/tooltip';
import type { Deal, Task } from '@/app/lib/types';
import dealsEn from '@/messages/en/deals.json';
import dealsJa from '@/messages/ja/deals.json';
import activityEn from '@/messages/en/activity.json';
import activityJa from '@/messages/ja/activity.json';
import commonEn from '@/messages/en/common.json';
import commonJa from '@/messages/ja/common.json';

const mocks = vi.hoisted(() => ({ scrollRef: () => undefined }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/app/hooks/useDragScroll', () => ({ useDragScroll: () => ({ ref: mocks.scrollRef, edges: { left: false, right: false } }) }));
vi.mock('@/app/hooks/useNow', () => ({ useLiveNow: () => 0 }));
vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => vi.fn() }));

beforeEach(() => {
    vi.stubGlobal('ResizeObserver', class {
        observe() {}
        unobserve() {}
        disconnect() {}
    });
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

const deal: Deal = { id: 1, name: 'Example deal', pipeline: 1, stage: 10, position: 0, value: 100, actualValue: 0, currency: 'JPY', company: null, createdAt: '', updatedAt: '' };
const task: Task = { id: 2, description: '### **Prepare proposal**', status: 'todo', position: 0, completed: false, assignedToId: 1, createdAt: '', updatedAt: '' };

for (const locale of ['en', 'ja'] as const) {
    const activity = locale === 'en' ? activityEn : activityJa;
    const messages = locale === 'en' ? { ...commonEn, ...activityEn, ...dealsEn } : { ...commonJa, ...activityJa, ...dealsJa };
    for (const board of ['deal', 'task'] as const) {
        it(`${locale}: reserves heading navigation for ${board} columns`, () => {
            render(<NextIntlClientProvider locale={locale} timeZone="UTC" messages={messages}>
                <TooltipProvider>
                    {board === 'deal' ? (
                        <KanbanBoard columns={[{ id: '10', label: 'Proposal' }, { id: '20', label: 'Signed' }]} items={[deal]}
                            getId={(item) => item.id} getColumnId={(item) => String(item.stage)} getPosition={(item) => item.position}
                            dragLabel={(item) => item.name} reduce countLabel={String}
                            renderCard={(item, dragHandle) => <DealKanbanCard deal={item} timezone="UTC" dragHandle={dragHandle} />} />
                    ) : (
                        <TasksKanban tasks={[task]} personById={new Map()} dealById={new Map()} userById={new Map()} onMoved={vi.fn()} onOpen={vi.fn()} reduce />
                    )}
                </TooltipProvider>
            </NextIntlClientProvider>);
            const columns = board === 'deal' ? ['Proposal', 'Signed'] : [activity.ActivityTasks.statusTodo, activity.ActivityTasks.statusInProgress, activity.ActivityTasks.statusDone];
            expect(screen.getAllByRole('heading', { level: 3 }).map((heading) => heading.textContent)).toEqual(columns);
            expect(screen.getAllByRole('heading').map((heading) => heading.textContent)).toEqual(columns);
            const title = screen.getByText(board === 'deal' ? deal.name : 'Prepare proposal');
            expect(title.closest('[data-kanban-card]')).not.toBeNull();
            expect(document.querySelectorAll('[data-kanban-card] h1, [data-kanban-card] h2, [data-kanban-card] h3, [data-kanban-card] h4, [data-kanban-card] h5, [data-kanban-card] h6')).toHaveLength(0);
            if (board === 'task') expect(title.tagName).toBe('STRONG');
        });
    }
}
