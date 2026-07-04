'use client';

import Link from 'next/link';
import { type ReactNode, useCallback, useRef, useState } from 'react';
import { useTranslations } from 'next-intl';
import { useReducedMotion } from 'motion/react';
import { ArrowPathIcon, PlusIcon, Squares2X2Icon } from '@heroicons/react/24/outline';
import {
    DndContext,
    DragOverlay,
    KeyboardSensor,
    PointerSensor,
    closestCenter,
    useSensor,
    useSensors,
    type Announcements,
    type DragEndEvent,
    type DragStartEvent,
    type UniqueIdentifier,
} from '@dnd-kit/core';
import {
    SortableContext,
    arrayMove,
    rectSortingStrategy,
    sortableKeyboardCoordinates,
    useSortable,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { GripVertical } from 'lucide-react';

import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import {
    Sheet,
    SheetContent,
    SheetDescription,
    SheetHeader,
    SheetTitle,
} from '@/components/ui/sheet';
import { toastError } from '@/app/lib/toast';
import { resetDashboardLayout, saveDashboardLayout } from '@/app/lib/api';
import type { DashboardWidgetInstance, DashboardWidgetType } from '@/app/lib/types';

import WidgetShell from './WidgetShell';
import { ALL_WIDGET_TYPES, WIDGET_META, defaultWidgets, newWidgetId } from './dashboardWidgets';

const PERSIST_DEBOUNCE_MS = 400;

type WidgetNodes = Partial<Record<DashboardWidgetType, ReactNode>>;

/**
 * The customizable dashboard: renders the user's widgets in a draggable 2-column grid and, in edit
 * mode, lets them reorder (drag), resize (1↔2 columns), remove, add (tray), and reset. Widget
 * bodies are rendered by the server component and passed in via `nodes`, so no data fetching lives
 * here. Layout changes persist optimistically (debounced) and roll back to the last saved state on
 * failure.
 */
export default function DashboardGrid({
    initialWidgets,
    nodes,
}: {
    initialWidgets: DashboardWidgetInstance[];
    nodes: WidgetNodes;
}) {
    const t = useTranslations('DashboardCustomize');
    const tp = useTranslations('DashboardPage');
    const reduce = useReducedMotion() ?? false;

    const [widgets, setWidgets] = useState<DashboardWidgetInstance[]>(initialWidgets);
    const [editMode, setEditMode] = useState(false);
    const [trayOpen, setTrayOpen] = useState(false);
    const [activeId, setActiveId] = useState<string | null>(null);

    const incomingKey = JSON.stringify(initialWidgets);
    const [syncedKey, setSyncedKey] = useState(incomingKey);
    if (syncedKey !== incomingKey && activeId == null) {
        setSyncedKey(incomingKey);
        setWidgets(initialWidgets);
    }

    const lastSavedRef = useRef<DashboardWidgetInstance[]>(initialWidgets);
    const saveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

    const persist = useCallback(
        (next: DashboardWidgetInstance[]) => {
            if (saveTimer.current) clearTimeout(saveTimer.current);
            saveTimer.current = setTimeout(() => {
                void saveDashboardLayout({ version: 1, widgets: next })
                    .then(() => {
                        lastSavedRef.current = next;
                    })
                    .catch(() => {
                        setWidgets(lastSavedRef.current);
                        toastError(t('saveFailed'));
                    });
            }, PERSIST_DEBOUNCE_MS);
        },
        [t],
    );

    const mutate = useCallback(
        (next: DashboardWidgetInstance[]) => {
            setWidgets(next);
            persist(next);
        },
        [persist],
    );

    const sensors = useSensors(
        useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
        useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
    );

    const titleFor = useCallback(
        (type: DashboardWidgetType) => tp(WIDGET_META[type].titleKey),
        [tp],
    );
    const nameFor = useCallback(
        (id: UniqueIdentifier) => {
            const widget = widgets.find((w) => w.id === String(id));
            return widget ? titleFor(widget.type) : '';
        },
        [widgets, titleFor],
    );

    const onDragStart = (event: DragStartEvent) => setActiveId(String(event.active.id));
    const onDragEnd = (event: DragEndEvent) => {
        const { active, over } = event;
        setActiveId(null);
        if (!over || active.id === over.id) return;
        const oldIndex = widgets.findIndex((w) => w.id === active.id);
        const newIndex = widgets.findIndex((w) => w.id === over.id);
        if (oldIndex < 0 || newIndex < 0) return;
        mutate(arrayMove(widgets, oldIndex, newIndex));
    };

    const toggleWidth = (id: string) => {
        mutate(
            widgets.map((w) => {
                if (w.id !== id) return w;
                const next = WIDGET_META[w.type].allowedSpans.find((s) => s !== w.span) ?? w.span;
                return { ...w, span: next };
            }),
        );
    };
    const removeWidget = (id: string) => mutate(widgets.filter((w) => w.id !== id));
    const addWidget = (type: DashboardWidgetType) =>
        mutate([...widgets, { id: newWidgetId(type), type, span: WIDGET_META[type].defaultSpan }]);

    const resetLayout = () => {
        const next = defaultWidgets();
        setWidgets(next);
        lastSavedRef.current = next;
        if (saveTimer.current) clearTimeout(saveTimer.current);
        void resetDashboardLayout().catch(() => toastError(t('saveFailed')));
    };

    const presentTypes = new Set(widgets.map((w) => w.type));
    const availableTypes = ALL_WIDGET_TYPES.filter((type) => !presentTypes.has(type));
    const activeWidget = activeId != null ? widgets.find((w) => w.id === activeId) : undefined;

    const actionFor = (type: DashboardWidgetType): ReactNode => {
        const meta = WIDGET_META[type];
        if (!meta.actionHref || !meta.actionLabelKey) return null;
        return (
            <Link href={meta.actionHref} className="text-xs text-brand hover:text-brand-hover">
                {tp(meta.actionLabelKey)}
            </Link>
        );
    };

    const announcements: Announcements = {
        onDragStart: ({ active }) => t('a11yLifted', { name: nameFor(active.id) }),
        onDragOver: ({ active, over }) =>
            over ? t('a11yOver', { name: nameFor(active.id), target: nameFor(over.id) }) : undefined,
        onDragEnd: ({ active, over }) =>
            over
                ? t('a11yDropped', { name: nameFor(active.id), target: nameFor(over.id) })
                : t('a11yCancelled', { name: nameFor(active.id) }),
        onDragCancel: ({ active }) => t('a11yCancelled', { name: nameFor(active.id) }),
    };

    return (
        <div className="flex flex-col gap-4">
            <div className="flex items-center justify-end gap-2 px-1">
                {editMode && (
                    <>
                        <Button variant="ghost" size="sm" onClick={resetLayout}>
                            <ArrowPathIcon />
                            {t('reset')}
                        </Button>
                        <Button
                            variant="outline"
                            size="sm"
                            onClick={() => setTrayOpen(true)}
                            disabled={availableTypes.length === 0}
                        >
                            <PlusIcon />
                            {t('addWidget')}
                        </Button>
                    </>
                )}
                <Button
                    variant={editMode ? 'default' : 'outline'}
                    size="sm"
                    onClick={() => setEditMode((v) => !v)}
                    aria-pressed={editMode}
                >
                    {!editMode && <Squares2X2Icon />}
                    {editMode ? t('done') : t('customize')}
                </Button>
            </div>

            {widgets.length === 0 ? (
                <div className="flex flex-col items-center justify-center gap-3 rounded-2xl border border-dashed border-border bg-card/40 px-6 py-16 text-center">
                    <Squares2X2Icon className="size-6 text-muted-foreground" />
                    <div>
                        <p className="text-sm font-medium text-foreground">{t('emptyTitle')}</p>
                        <p className="mt-1 text-sm text-muted-foreground">{t('emptyBody')}</p>
                    </div>
                    <Button
                        variant="outline"
                        size="sm"
                        onClick={() => {
                            setEditMode(true);
                            setTrayOpen(true);
                        }}
                    >
                        <PlusIcon />
                        {t('addWidget')}
                    </Button>
                </div>
            ) : (
                <DndContext
                    sensors={sensors}
                    collisionDetection={closestCenter}
                    onDragStart={onDragStart}
                    onDragEnd={onDragEnd}
                    onDragCancel={() => setActiveId(null)}
                    accessibility={{ announcements, screenReaderInstructions: { draggable: t('a11yInstructions') } }}
                >
                    <SortableContext items={widgets.map((w) => w.id)} strategy={rectSortingStrategy}>
                        <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
                            {widgets.map((w) => (
                                <SortableWidget
                                    key={w.id}
                                    instance={w}
                                    node={nodes[w.type]}
                                    title={titleFor(w.type)}
                                    action={actionFor(w.type)}
                                    editMode={editMode}
                                    reduce={reduce}
                                    dragHandleLabel={t('dragHandle', { name: titleFor(w.type) })}
                                    widthLabel={w.span === 2 ? t('collapse') : t('expand')}
                                    removeLabel={t('remove', { name: titleFor(w.type) })}
                                    canToggleWidth={WIDGET_META[w.type].allowedSpans.length > 1}
                                    onToggleWidth={() => toggleWidth(w.id)}
                                    onRemove={() => removeWidget(w.id)}
                                />
                            ))}
                        </div>
                    </SortableContext>
                    <DragOverlay>
                        {activeWidget ? (
                            <div className={reduce ? 'cursor-grabbing' : 'cursor-grabbing scale-[1.02] shadow-2xl'}>
                                <WidgetShell
                                    title={titleFor(activeWidget.type)}
                                    editMode={false}
                                    span={activeWidget.span}
                                    canToggleWidth={false}
                                    widthLabel=""
                                    removeLabel=""
                                >
                                    {nodes[activeWidget.type]}
                                </WidgetShell>
                            </div>
                        ) : null}
                    </DragOverlay>
                </DndContext>
            )}

            <Sheet open={trayOpen} onOpenChange={setTrayOpen}>
                <SheetContent side="right" className="w-full gap-0 sm:max-w-sm">
                    <SheetHeader>
                        <SheetTitle>{t('trayTitle')}</SheetTitle>
                        <SheetDescription>{t('trayDescription')}</SheetDescription>
                    </SheetHeader>
                    <div className="flex flex-col gap-2 overflow-y-auto p-4">
                        {availableTypes.length === 0 ? (
                            <p className="rounded-xl border border-dashed border-border px-4 py-8 text-center text-sm text-muted-foreground">
                                {t('trayEmpty')}
                            </p>
                        ) : (
                            availableTypes.map((type) => (
                                <div
                                    key={type}
                                    className="flex items-center justify-between gap-3 rounded-xl border border-border bg-card px-4 py-3"
                                >
                                    <span className="truncate text-sm font-medium text-foreground">
                                        {titleFor(type)}
                                    </span>
                                    <Button variant="outline" size="sm" onClick={() => addWidget(type)}>
                                        <PlusIcon />
                                        {t('add')}
                                    </Button>
                                </div>
                            ))
                        )}
                    </div>
                </SheetContent>
            </Sheet>
        </div>
    );
}

function SortableWidget({
    instance,
    node,
    title,
    action,
    editMode,
    reduce,
    dragHandleLabel,
    widthLabel,
    removeLabel,
    canToggleWidth,
    onToggleWidth,
    onRemove,
}: {
    instance: DashboardWidgetInstance;
    node: ReactNode;
    title: string;
    action: ReactNode;
    editMode: boolean;
    reduce: boolean;
    dragHandleLabel: string;
    widthLabel: string;
    removeLabel: string;
    canToggleWidth: boolean;
    onToggleWidth: () => void;
    onRemove: () => void;
}) {
    const { attributes, listeners, setNodeRef, setActivatorNodeRef, transform, transition, isDragging } =
        useSortable({ id: instance.id });
    const style = {
        transform: CSS.Transform.toString(transform),
        transition: reduce ? undefined : transition,
    };

    const dragHandle = (
        <button
            type="button"
            ref={setActivatorNodeRef}
            aria-label={dragHandleLabel}
            className="cursor-grab touch-none rounded-md p-1 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring active:cursor-grabbing"
            {...attributes}
            {...listeners}
        >
            <GripVertical className="size-4" aria-hidden />
        </button>
    );

    return (
        <div ref={setNodeRef} style={style} className={cn('min-w-0', instance.span === 2 && 'lg:col-span-2')}>
            <WidgetShell
                title={title}
                action={action}
                editMode={editMode}
                isDragging={isDragging}
                dragHandle={dragHandle}
                span={instance.span}
                canToggleWidth={canToggleWidth}
                widthLabel={widthLabel}
                removeLabel={removeLabel}
                onToggleWidth={onToggleWidth}
                onRemove={onRemove}
            >
                {node}
            </WidgetShell>
        </div>
    );
}
