/** @vitest-environment jsdom */
import { act, type ComponentProps, type ReactNode } from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { closestCorners, DndContext, getFirstCollision, KeyboardSensor, type ClientRect, type DroppableContainer, type KeyboardCoordinateGetter, type KeyboardSensorOptions, type UniqueIdentifier } from '@dnd-kit/core';
import { afterEach, expect, it, vi } from 'vitest';
import KanbanBoard from '@/app/components/kanban/KanbanBoard';
import { kanbanColumnId } from '@/app/components/kanban/kanbanAccessibility';

globalThis.IS_REACT_ACT_ENVIRONMENT = true;

const captured = vi.hoisted((): { props?: ComponentProps<typeof DndContext>; scrollRef: () => void } => ({ scrollRef: () => undefined }));
vi.mock('@dnd-kit/core', async (original) => {
    const core = await original<typeof import('@dnd-kit/core')>();
    return { ...core, DndContext: (props: ComponentProps<typeof DndContext>) => { captured.props = props; return <core.DndContext {...props} />; } };
});
vi.mock('@/app/hooks/useDragScroll', () => ({ useDragScroll: () => ({ ref: captured.scrollRef, edges: { left: false, right: false } }) }));
vi.mock('@/components/ui/icon-button', () => ({ IconButton: ({ label, children, ...props }: ComponentProps<'button'> & { label: string; children: ReactNode }) => <button aria-label={label} {...props}>{children}</button> }));
afterEach(cleanup);

class Containers extends Map<UniqueIdentifier, DroppableContainer> {
    override get(id: UniqueIdentifier | null | undefined) { return id == null ? undefined : super.get(id); }
    toArray() { return [...this.values()]; }
    getEnabled() { return this.toArray().filter((container) => !container.disabled); }
    getNodeFor(id: UniqueIdentifier | null | undefined) { return this.get(id)?.node.current ?? undefined; }
}

function rect(left: number, top: number, width = 320, height = 160): ClientRect {
    return { left, top, width, height, right: left + width, bottom: top + height };
}
function geometry(ids: string[], source: string, offset = 8, nonempty?: string): Parameters<KeyboardCoordinateGetter>[1] {
    const containers = new Containers();
    const rects = new Map<UniqueIdentifier, ClientRect>();
    for (const [index, id] of ids.entries()) {
        const bounds = rect(index * 336, 0);
        rects.set(`col:${id}`, bounds);
        containers.set(`col:${id}`, { id: `col:${id}`, key: id, data: { current: {} }, disabled: false, node: { current: document.createElement('div') }, rect: { current: bounds } });
    }
    const actual = rect(ids.indexOf(source) * 336 + 8, 8, 304, 120);
    const collision = rect(ids.indexOf(source) * 336 + offset, 8, 304, 120);
    const data = { current: { sortable: { containerId: `col:${source}`, index: 0, items: [1] } } };
    const node = document.createElement('div');
    containers.set(1, { id: 1, key: 'card', data, disabled: false, node: { current: node }, rect: { current: actual } });
    rects.set(1, actual);
    if (nonempty) {
        const bounds = rect(ids.indexOf(nonempty) * 336 + 8, 8, 304, 120);
        containers.set(2, { id: 2, key: 'peer', data: { current: { sortable: { containerId: `col:${nonempty}`, index: 0, items: [2] } } }, disabled: false, node: { current: document.createElement('div') }, rect: { current: bounds } });
        rects.set(2, bounds);
    }
    return {
        active: 1, currentCoordinates: { x: collision.left, y: collision.top },
        context: {
            activatorEvent: new KeyboardEvent('keydown'), active: { id: 1, data, rect: { current: { initial: actual, translated: collision } } },
            activeNode: node, collisionRect: collision, collisions: null, draggableNodes: new Map(), draggingNode: node, draggingNodeRect: actual,
            droppableContainers: containers, droppableRects: rects, over: { id: 1, data, rect: actual, disabled: false }, scrollableAncestors: [], scrollAdjustedTranslate: null,
        },
    };
}
function mount(ids: string[], source: string) {
    render(<KanbanBoard columns={ids.map((id) => ({ id, label: id }))} items={[{ id: 1, column: source }]}
        getId={(item) => item.id} getColumnId={(item) => item.column} getPosition={() => 0} dragLabel={() => 'Move Example'}
        renderCard={(_, handle) => <div>{handle}Example</div>} reduce countLabel={String} onMove={async () => true} />);
    const descriptor = captured.props?.sensors?.find((entry) => entry.sensor === KeyboardSensor);
    if (!descriptor) throw new Error('Missing keyboard sensor');
    const options: KeyboardSensorOptions = descriptor.options;
    if (!options.coordinateGetter) throw new Error('Missing coordinate getter');
    return options.coordinateGetter;
}
function destination(getter: KeyboardCoordinateGetter, code: string, args: Parameters<KeyboardCoordinateGetter>[1]) {
    const point = getter(new KeyboardEvent('keydown', { code }), args);
    if (!point || !args.context.collisionRect || !args.context.active) return undefined;
    const moved = rect(point.x, point.y, args.context.collisionRect.width, args.context.collisionRect.height);
    const id = getFirstCollision(closestCorners({
        active: args.context.active, collisionRect: moved, droppableContainers: args.context.droppableContainers.getEnabled(), droppableRects: args.context.droppableRects, pointerCoordinates: null,
    }), 'id');
    const target = args.context.droppableContainers.get(id);
    const bounds = id === null ? undefined : args.context.droppableRects.get(id);
    return target && bounds ? { id: target.id, data: target.data, rect: bounds, disabled: target.disabled } : undefined;
}

for (const terminal of ['Signed', 'Declined']) {
    it(`moves left from a padded ${terminal} card into the adjacent Proposal column in one keypress`, () => {
        const ids = ['Proposal', terminal];
        const over = destination(mount(ids, terminal), 'ArrowLeft', geometry(ids, terminal));
        expect(over && kanbanColumnId(over)).toBe('Proposal');
    });
}
it('moves right to the adjacent column when the current collision rectangle is shifted past its own column padding', () => {
    const ids = ['Proposal', 'Signed', 'Later'];
    const over = destination(mount(ids, 'Proposal'), 'ArrowRight', geometry(ids, 'Proposal', -8));
    expect(over && kanbanColumnId(over)).toBe('Signed');
});
it('enters an adjacent nonempty column without skipping to a farther column', () => {
    const ids = ['Proposal', 'Signed', 'Later'];
    const over = destination(mount(ids, 'Proposal'), 'ArrowRight', geometry(ids, 'Proposal', 8, 'Signed'));
    expect(over && kanbanColumnId(over)).toBe('Signed');
});
for (const [source, key] of [['Proposal', 'ArrowLeft'], ['Signed', 'ArrowRight']] as const) {
    it(`does not move past the ${source} edge on ${key}`, () => {
        const ids = ['Proposal', 'Signed'];
        expect(mount(ids, source)(new KeyboardEvent('keydown', { code: key }), geometry(ids, source))).toBeUndefined();
    });
}
it('uses the live column after reparenting while the original task props and sensor callback are retained', async () => {
    const ids = ['Proposal', 'Signed', 'Later'];
    const getter = mount(ids, 'Proposal');
    const args = geometry(ids, 'Proposal');
    const over = destination(getter, 'ArrowRight', args);
    if (!over || !args.context.active) throw new Error('Missing drag fixture');
    const event = { active: args.context.active, over, activatorEvent: new KeyboardEvent('keydown'), collisions: null, delta: { x: 336, y: 0 } };
    await act(async () => captured.props?.onDragStart?.(event));
    await act(async () => captured.props?.onDragOver?.(event));
    expect(screen.getByRole('listitem').closest('section')?.textContent).toContain('Signed');
    const next = destination(getter, 'ArrowRight', geometry(ids, 'Signed'));
    expect(next && kanbanColumnId(next)).toBe('Later');
});
