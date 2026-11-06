/** @vitest-environment jsdom */
import { act, type ComponentProps, type ReactNode } from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { DndContext, type DragEndEvent } from '@dnd-kit/core';
import { NextIntlClientProvider } from 'next-intl';
import { afterEach, beforeEach, expect, it, vi, type Mock } from 'vitest';
import TasksKanban from '@/app/components/activity/tasks/TasksKanban';
import type { Task } from '@/app/lib/types';
import en from '@/messages/en/activity.json';
import ja from '@/messages/ja/activity.json';

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

const mocks = vi.hoisted((): {
    props?: ComponentProps<typeof DndContext>;
    move: Mock<(id: number, status: string, position: number) => Promise<Task>>;
    error: Mock<(error: unknown, key: string) => void>;
    moved: Mock<() => void>;
    scrollRef: () => void;
} => ({ move: vi.fn(), error: vi.fn(), moved: vi.fn(), scrollRef: () => undefined }));
vi.mock('@dnd-kit/core', async (original) => {
    const core = await original<typeof import('@dnd-kit/core')>();
    return { ...core, DndContext: (props: ComponentProps<typeof DndContext>) => {
        mocks.props = props;
        return <core.DndContext {...props} />;
    } };
});
vi.mock('@/app/hooks/useDragScroll', () => ({ useDragScroll: () => ({ ref: mocks.scrollRef, edges: { left: false, right: false } }) }));
vi.mock('@/components/ui/icon-button', () => ({ IconButton: ({ label, children, ...props }: ComponentProps<'button'> & { label: string; children: ReactNode }) => <button aria-label={label} {...props}>{children}</button> }));
vi.mock('@/app/components/activity/notes/NoteContent', () => ({ default: ({ content }: { content: string }) => <span>{content}</span> }));
vi.mock('@/app/hooks/useNow', () => ({ useLiveNow: () => 0 }));
vi.mock('@/app/hooks/useApiErrorToast', () => ({ useApiErrorToast: () => mocks.error }));
vi.mock('@/app/lib/api', () => ({ moveTask: mocks.move }));

const task: Task = { id: 1, description: 'Example', status: 'todo', position: 4, completed: false, assignedToId: 1, createdAt: '', updatedAt: '' };
const drag = (column = 'in_progress'): DragEndEvent => ({
    active: { id: 1, data: { current: {} }, rect: { current: { initial: null, translated: null } } },
    over: { id: `col:${column}`, data: { current: {} }, disabled: false, rect: { top: 0, left: 0, right: 10, bottom: 10, width: 10, height: 10 } },
    activatorEvent: new KeyboardEvent('keydown'), collisions: null, delta: { x: 0, y: 0 },
});
function mount(locale: 'en' | 'ja') {
    render(<NextIntlClientProvider locale={locale} timeZone="UTC" messages={locale === 'en' ? en : ja}>
        <TasksKanban tasks={[task]} personById={new Map()} dealById={new Map()} userById={new Map()} onMoved={mocks.moved} onOpen={vi.fn()} reduce />
    </NextIntlClientProvider>);
}
async function drop(column = 'in_progress') {
    const event = drag(column);
    await act(async () => mocks.props?.onDragStart?.(event));
    await act(async () => mocks.props?.onDragOver?.(event));
    await act(async () => mocks.props?.onDragEnd?.(event));
    return event;
}
beforeEach(() => vi.clearAllMocks());
afterEach(cleanup);

for (const locale of ['en', 'ja'] as const) {
    const catalog = locale === 'en' ? en.ActivityTasks : ja.ActivityTasks;
    it(`${locale}: announces a requested task move, then its restored source and position on refusal`, async () => {
        let refuse: ((error: Error) => void) | undefined;
        mocks.move.mockImplementation(() => new Promise<Task>((_, reject) => { refuse = reject; }));
        mount(locale);
        const event = await drop();
        expect(mocks.props?.accessibility?.announcements?.onDragEnd(event)).toBeUndefined();
        const live = screen.getByText(catalog.a11yMoveRequested.replace('{name}', 'Example').replace('{column}', catalog.statusInProgress));
        expect(live.getAttribute('role')).toBe('status');
        const error = new Error('refused');
        await act(async () => refuse?.(error));
        const restored = catalog.a11yRestored.replace('{name}', 'Example').replace('{column}', catalog.statusTodo).replace('{position}', '5');
        await waitFor(() => expect(screen.getAllByRole('status').some((status) => status.textContent === restored)).toBe(true));
        expect(live.textContent).toBe(restored);
        expect(screen.getAllByRole('status').filter((status) => status.textContent)).toEqual([live]);
        expect(document.querySelector('[data-kanban-drag-handle="1"]')?.closest('section')?.textContent).toContain(catalog.statusTodo);
        expect(mocks.error).toHaveBeenCalledWith(error, 'moveFailed');
        expect(mocks.moved).not.toHaveBeenCalled();
    });
    it(`${locale}: restores the last saved task position after a successive move fails before refresh`, async () => {
        mocks.move.mockResolvedValueOnce({ ...task, status: 'in_progress', position: 0 });
        mount(locale);
        await drop();
        await waitFor(() => expect(mocks.moved).toHaveBeenCalledOnce());
        const error = new Error('second move refused');
        mocks.move.mockRejectedValueOnce(error);
        await drop('done');
        const restored = catalog.a11yRestored.replace('{name}', 'Example').replace('{column}', catalog.statusInProgress).replace('{position}', '1');
        await waitFor(() => expect(screen.getAllByRole('status').some((status) => status.textContent === restored)).toBe(true));
        expect(document.querySelector('[data-kanban-drag-handle="1"]')?.closest('section')?.textContent).toContain(catalog.statusInProgress);
        expect(mocks.error).toHaveBeenCalledWith(error, 'moveFailed');
        expect(mocks.moved).toHaveBeenCalledOnce();
    });
    it(`${locale}: announces the saved status and server position after task persistence resolves`, async () => {
        let save: ((task: Task) => void) | undefined;
        mocks.move.mockImplementation(() => new Promise<Task>((resolve) => { save = resolve; }));
        mount(locale);
        await drop();
        const live = screen.getByText(catalog.a11yMoveRequested.replace('{name}', 'Example').replace('{column}', catalog.statusInProgress));
        await act(async () => save?.({ ...task, status: 'in_progress', position: 2 }));
        const saved = catalog.a11ySaved.replace('{name}', 'Example').replace('{column}', catalog.statusInProgress).replace('{position}', '3');
        await waitFor(() => expect(screen.getAllByRole('status').some((status) => status.textContent === saved)).toBe(true));
        expect(live.textContent).toBe(saved);
        expect(screen.getAllByRole('status').filter((status) => status.textContent)).toEqual([live]);
        expect(document.querySelector('[data-kanban-drag-handle="1"]')?.closest('section')?.textContent).toContain(catalog.statusInProgress);
        expect(mocks.moved).toHaveBeenCalledOnce();
    });
}
