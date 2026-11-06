/** @vitest-environment jsdom */
import { act, type ComponentProps, type ReactNode } from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { DndContext, type DragEndEvent } from '@dnd-kit/core';
import { afterEach, expect, it, vi } from 'vitest';
import KanbanBoard, { type KanbanBoardProps } from '@/app/components/kanban/KanbanBoard';

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

const captured = vi.hoisted((): { props?: ComponentProps<typeof DndContext>; scrollRef: () => void } => ({ scrollRef: () => undefined }));
vi.mock('@dnd-kit/core', async (original) => {
    const core = await original<typeof import('@dnd-kit/core')>();
    return { ...core, DndContext: (props: ComponentProps<typeof DndContext>) => {
        captured.props = props;
        return <core.DndContext {...props} />;
    } };
});
vi.mock('@/app/hooks/useDragScroll', () => ({ useDragScroll: () => ({ ref: captured.scrollRef, edges: { left: false, right: false } }) }));
vi.mock('@/components/ui/icon-button', () => ({ IconButton: ({ label, children, ...props }: ComponentProps<'button'> & { label: string; children: ReactNode }) => <button aria-label={label} {...props}>{children}</button> }));

afterEach(cleanup);
const columns = [{ id: 'old', label: 'Original' }, { id: 'next', label: 'Destination' }];
const items = [{ id: 1, column: 'old' }];
function content(onMove?: KanbanBoardProps<(typeof items)[number]>['onMove'], onBeforeMove?: KanbanBoardProps<(typeof items)[number]>['onBeforeMove'], boardItems = items) {
    return <KanbanBoard columns={columns} items={boardItems} getId={(item) => item.id} getColumnId={(item) => item.column}
        getPosition={() => 0} dragLabel={() => 'Move Example'} reduce countLabel={String} onMove={onMove} onBeforeMove={onBeforeMove} accessibility={{ moveRestored: (_, column, position) => `Restored ${column}, position ${position + 1}` }}
        renderCard={(_, handle) => <div>{handle}<button>Open Example</button><button>Example actions</button></div>} />;
}
function board(onMove?: KanbanBoardProps<(typeof items)[number]>['onMove'], onBeforeMove?: KanbanBoardProps<(typeof items)[number]>['onBeforeMove']) {
    return render(content(onMove, onBeforeMove));
}
function drag(): DragEndEvent {
    return {
        active: { id: 1, data: { current: {} }, rect: { current: { initial: null, translated: null } } },
        over: { id: 'col:next', data: { current: {} }, disabled: false, rect: { top: 0, left: 0, right: 10, bottom: 10, width: 10, height: 10 } },
        activatorEvent: new KeyboardEvent('keydown'), collisions: null, delta: { x: 0, y: 0 },
    };
}

it('keeps list semantics and exposes separate named drag, open, and action controls with a forced-colors focus outline', () => {
    board(async () => true);
    const card = screen.getByRole('listitem');
    expect(card.getAttribute('role')).toBeNull();
    expect(card.hasAttribute('tabindex')).toBe(false);
    const handle = screen.getByRole('button', { name: 'Move Example' });
    expect(handle.contains(screen.getByRole('button', { name: 'Example actions' }))).toBe(false);
    expect(screen.getAllByRole('button')).toHaveLength(3);
    expect(card.className).toContain('focus-within:outline-2');
    expect(card.className).toContain('forced-colors:focus-within:outline-[Highlight]');
});

it('keeps opening available on a read-only board without a drag control', () => {
    board();
    expect(screen.queryByRole('button', { name: 'Move Example' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Open Example' })).toBeTruthy();
});

for (const input of ['keyboard', 'pointer']) {
it(`rolls back a cancelled terminal move and restores focus after a ${input} drag and asynchronous decision`, async () => {
    let decide: ((accepted: boolean) => void) | undefined;
    const save = vi.fn(async () => true);
    board(save, () => new Promise<boolean>((resolve) => { decide = resolve; }));
    const event = drag();
    if (input === 'pointer') event.activatorEvent = new Event('pointerdown');
    await act(async () => captured.props?.onDragStart?.(event));
    await act(async () => captured.props?.onDragOver?.(event));
    await act(async () => captured.props?.onDragEnd?.(event));
    expect(save).not.toHaveBeenCalled();
    expect(screen.getByRole('listitem').closest('section')?.textContent).toContain('Original');
    expect(screen.getByRole('button', { name: 'Move Example' }).hasAttribute('disabled')).toBe(true);
    await act(async () => decide?.(false));
    expect(screen.getAllByRole('status').filter((status) => status.textContent).map((status) => status.textContent)).toEqual(['Restored old, position 1']);
    expect(save).not.toHaveBeenCalled();
    const card = screen.getByRole('listitem');
    expect(card.closest('section')?.textContent).toContain('Original');
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Move Example' })));
});
}

it('shows the source until consent, then applies the move and keeps the server placement', async () => {
    let decide: ((accepted: boolean) => void) | undefined;
    const save = vi.fn(async () => ({ columnId: 'next', index: 0 }));
    board(save, () => new Promise<boolean>((resolve) => { decide = resolve; }));
    const event = drag();
    await act(async () => captured.props?.onDragStart?.(event));
    await act(async () => captured.props?.onDragOver?.(event));
    expect(screen.getByRole('listitem').closest('section')?.textContent).toContain('Destination');
    await act(async () => captured.props?.onDragEnd?.(event));
    expect(screen.getByRole('listitem').closest('section')?.textContent).toContain('Original');
    expect(save).not.toHaveBeenCalled();
    await act(async () => decide?.(true));
    expect(save).toHaveBeenCalledWith(1, 'next', 0);
    expect(screen.getByRole('listitem').closest('section')?.textContent).toContain('Destination');
});

it('removes a saved card from visible columns when the new outcome no longer matches the board', async () => {
    board(async () => ({ columnId: 'next', index: -1 }));
    const event = drag();
    await act(async () => captured.props?.onDragStart?.(event));
    await act(async () => captured.props?.onDragOver?.(event));
    await act(async () => captured.props?.onDragEnd?.(event));
    expect(screen.queryAllByRole('listitem')).toHaveLength(0);
    expect(screen.getByRole('heading', { name: 'Destination' }).parentElement?.textContent).toBe('Destination0');
});

it('adopts a refresh received during confirmation when consent is cancelled', async () => {
    let decide: ((accepted: boolean) => void) | undefined;
    const save = vi.fn(async () => true);
    const consent = () => new Promise<boolean>((resolve) => { decide = resolve; });
    const view = board(save, consent);
    const event = drag();
    await act(async () => captured.props?.onDragStart?.(event));
    await act(async () => captured.props?.onDragOver?.(event));
    await act(async () => captured.props?.onDragEnd?.(event));
    view.rerender(content(save, consent, [...items, { id: 2, column: 'next' }]));
    await act(async () => decide?.(false));
    expect(save).not.toHaveBeenCalled();
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
    const source = document.querySelector('[data-kanban-drag-handle="1"]');
    const refreshed = document.querySelector('[data-kanban-drag-handle="2"]');
    expect(source?.closest('section')?.textContent).toContain('Original');
    expect(refreshed?.closest('section')?.textContent).toContain('Destination');
});

it('uses the saved visible position instead of the requested destination index', async () => {
    const save = vi.fn(async () => ({ columnId: 'next', index: 0 }));
    render(content(save, undefined, [...items, { id: 2, column: 'next' }, { id: 3, column: 'next' }]));
    const event = drag();
    await act(async () => captured.props?.onDragStart?.(event));
    await act(async () => captured.props?.onDragOver?.(event));
    await act(async () => captured.props?.onDragEnd?.(event));
    expect(save).toHaveBeenCalledWith(1, 'next', 2);
    const destination = screen.getByRole('heading', { name: 'Destination' }).closest('section');
    expect([...destination?.querySelectorAll('[data-kanban-drag-handle]') ?? []].map((handle) => handle.getAttribute('data-kanban-drag-handle'))).toEqual(['1', '2', '3']);
});

it('keeps a saved destination alongside peers from a refresh received during persistence', async () => {
    let complete: ((placement: { columnId: string; index: number }) => void) | undefined;
    const save = () => new Promise<{ columnId: string; index: number }>((resolve) => { complete = resolve; });
    const view = board(save);
    const event = drag();
    await act(async () => captured.props?.onDragStart?.(event));
    await act(async () => captured.props?.onDragOver?.(event));
    await act(async () => captured.props?.onDragEnd?.(event));
    view.rerender(content(save, undefined, [...items, { id: 2, column: 'old' }]));
    await act(async () => complete?.({ columnId: 'next', index: 0 }));
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
    expect(document.querySelector('[data-kanban-drag-handle="1"]')?.closest('section')?.textContent).toContain('Destination');
    expect(document.querySelector('[data-kanban-drag-handle="2"]')?.closest('section')?.textContent).toContain('Original');
});
