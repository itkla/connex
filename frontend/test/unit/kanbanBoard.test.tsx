/** @vitest-environment jsdom */
import { act, type ComponentProps, type ReactNode } from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { DndContext, type DragEndEvent } from '@dnd-kit/core';
import { afterEach, expect, it, vi } from 'vitest';
import KanbanBoard from '@/app/components/kanban/KanbanBoard';

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
function board(onMove?: (id: number, column: string, index: number) => Promise<boolean>) {
    return render(<KanbanBoard columns={columns} items={items} getId={(item) => item.id} getColumnId={(item) => item.column}
        getPosition={() => 0} dragLabel={() => 'Move Example'} reduce countLabel={String} onMove={onMove}
        renderCard={(_, handle) => <div>{handle}<button>Open Example</button><button>Example actions</button></div>} />);
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
    const save = vi.fn(() => new Promise<boolean>((resolve) => { decide = resolve; }));
    board(save);
    const event = drag();
    if (input === 'pointer') event.activatorEvent = new Event('pointerdown');
    await act(async () => captured.props?.onDragStart?.(event));
    await act(async () => captured.props?.onDragOver?.(event));
    await act(async () => captured.props?.onDragEnd?.(event));
    expect(save).toHaveBeenCalledWith(1, 'next', 0);
    expect(screen.getByRole('button', { name: 'Move Example' }).hasAttribute('disabled')).toBe(true);
    await act(async () => decide?.(false));
    const card = screen.getByRole('listitem');
    expect(card.closest('section')?.textContent).toContain('Original');
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Move Example' })));
});
}
