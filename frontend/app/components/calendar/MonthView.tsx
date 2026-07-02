'use client';

import { useMemo, useState } from 'react';
import { motion, useReducedMotion } from 'motion/react';
import { useTranslations } from 'next-intl';
import {
    DndContext,
    DragOverlay,
    KeyboardSensor,
    PointerSensor,
    closestCorners,
    pointerWithin,
    useDraggable,
    useDroppable,
    useSensor,
    useSensors,
    type Announcements,
    type CollisionDetection,
    type DragEndEvent,
    type DragStartEvent,
    type UniqueIdentifier,
} from '@dnd-kit/core';

import { cn } from '@/lib/utils';
import {
    dayKeyOf,
    kindsForDay,
    monthGridCells,
    sameDay,
    startOfMonth,
    type CalendarEvent,
} from '@/app/lib/calendar';
import { WEEK_STARTS_ON } from './useCalendar';
import { KIND_DOT_CLASS } from './constants';
import EventChip from './EventChip';
import DayAgendaList from './DayAgendaList';

const SELECTED_LAYOUT_ID = 'calendar-selected-day';
const DESKTOP_CHIP_LIMIT = 3;
const DAY_DROP_PREFIX = 'day:';

/** Pointer-first collision so the day cell under the cursor wins, falling back to nearest corner. */
const monthCollisionDetection: CollisionDetection = (args) => {
    const pointer = pointerWithin(args);
    return pointer.length > 0 ? pointer : closestCorners(args);
};

interface MonthViewProps {
    anchor: Date;
    selectedDay: Date;
    today: Date;
    eventsByDay: Map<string, CalendarEvent[]>;
    locale: string;
    pendingId: string | null;
    onSelectDay: (day: Date) => void;
    onOpenEvent: (event: CalendarEvent) => void;
    onReschedule: (event: CalendarEvent, dayKey: string) => void;
}

export default function MonthView({
    anchor,
    selectedDay,
    today,
    eventsByDay,
    locale,
    pendingId,
    onSelectDay,
    onOpenEvent,
    onReschedule,
}: MonthViewProps) {
    const t = useTranslations('Calendar');
    const reduce = useReducedMotion() ?? false;
    const [activeId, setActiveId] = useState<string | null>(null);

    const monthStart = useMemo(() => startOfMonth(anchor), [anchor]);
    const cells = useMemo(() => monthGridCells(monthStart, WEEK_STARTS_ON), [monthStart]);

    const weekdayLabels = useMemo(() => {
        const fmt = new Intl.DateTimeFormat(locale, { weekday: 'short' });
        return Array.from({ length: 7 }, (_, i) => fmt.format(cells[i]));
    }, [locale, cells]);

    const selectedTitle = useMemo(
        () => new Intl.DateTimeFormat(locale, { weekday: 'long', month: 'long', day: 'numeric' }).format(selectedDay),
        [locale, selectedDay],
    );

    const eventById = useMemo(() => {
        const map = new Map<string, CalendarEvent>();
        for (const arr of eventsByDay.values()) for (const e of arr) map.set(e.id, e);
        return map;
    }, [eventsByDay]);

    const sensors = useSensors(
        useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
        useSensor(KeyboardSensor),
    );

    const dropDateLabel = useMemo(() => {
        const fmt = new Intl.DateTimeFormat(locale, { month: 'long', day: 'numeric' });
        return (id: UniqueIdentifier) => {
            const raw = String(id);
            if (!raw.startsWith(DAY_DROP_PREFIX)) return raw;
            return fmt.format(new Date(`${raw.slice(DAY_DROP_PREFIX.length)}T00:00:00`));
        };
    }, [locale]);

    const announcements: Announcements = {
        onDragStart: ({ active }) => t('a11yLifted', { name: eventById.get(String(active.id))?.title ?? '' }),
        onDragOver: ({ active, over }) =>
            over ? t('a11yOver', { name: eventById.get(String(active.id))?.title ?? '', date: dropDateLabel(over.id) }) : undefined,
        onDragEnd: ({ active, over }) =>
            over
                ? t('a11yDropped', { name: eventById.get(String(active.id))?.title ?? '', date: dropDateLabel(over.id) })
                : t('a11yCancelled', { name: eventById.get(String(active.id))?.title ?? '' }),
        onDragCancel: ({ active }) => t('a11yCancelled', { name: eventById.get(String(active.id))?.title ?? '' }),
    };

    const activeEvent = activeId ? eventById.get(activeId) ?? null : null;

    const onDragStart = (event: DragStartEvent) => setActiveId(String(event.active.id));

    const onDragEnd = (event: DragEndEvent) => {
        setActiveId(null);
        const { active, over } = event;
        if (!over) return;
        const overId = String(over.id);
        if (!overId.startsWith(DAY_DROP_PREFIX)) return;
        const newKey = overId.slice(DAY_DROP_PREFIX.length);
        const dragged = eventById.get(String(active.id));
        if (!dragged || !dragged.draggable || newKey === dragged.dayKey) return;
        onReschedule(dragged, newKey);
    };

    const selectedKey = dayKeyOf(selectedDay);
    const selectedEvents = eventsByDay.get(selectedKey) ?? [];

    return (
        <DndContext
            sensors={sensors}
            collisionDetection={monthCollisionDetection}
            accessibility={{ announcements, screenReaderInstructions: { draggable: t('a11yInstructions') } }}
            onDragStart={onDragStart}
            onDragEnd={onDragEnd}
            onDragCancel={() => setActiveId(null)}
        >
            <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_20rem]">
                <div className="overflow-hidden rounded-2xl border border-border bg-card">
                    <div className="grid grid-cols-7 border-b border-border">
                        {weekdayLabels.map((label, i) => (
                            <div
                                key={i}
                                className="px-1 py-2 text-center text-[10px] font-semibold uppercase tracking-[0.14em] text-muted-foreground"
                            >
                                {label}
                            </div>
                        ))}
                    </div>
                    <div className="grid grid-cols-7 gap-px bg-border">
                        {cells.map((day) => (
                            <MonthCell
                                key={dayKeyOf(day)}
                                day={day}
                                monthStart={monthStart}
                                today={today}
                                selectedDay={selectedDay}
                                events={eventsByDay.get(dayKeyOf(day)) ?? []}
                                pendingId={pendingId}
                                reduce={reduce}
                                onSelectDay={onSelectDay}
                                onOpenEvent={onOpenEvent}
                            />
                        ))}
                    </div>
                </div>

                <aside className="rounded-2xl border border-border bg-card lg:sticky lg:top-2 lg:self-start">
                    <header className="border-b border-border px-4 py-3">
                        <h2 className="text-sm font-semibold text-foreground">{selectedTitle}</h2>
                    </header>
                    <div className="p-2">
                        <DayAgendaList
                            dayKey={selectedKey}
                            events={selectedEvents}
                            locale={locale}
                            onOpenEvent={onOpenEvent}
                        />
                    </div>
                </aside>
            </div>

            <DragOverlay dropAnimation={reduce ? null : undefined}>
                {activeEvent ? (
                    <div className={reduce ? 'cursor-grabbing' : 'cursor-grabbing scale-[1.02]'}>
                        <EventChip event={activeEvent} variant="chip" className="pointer-events-none shadow-2xl" />
                    </div>
                ) : null}
            </DragOverlay>
        </DndContext>
    );
}

interface MonthCellProps {
    day: Date;
    monthStart: Date;
    today: Date;
    selectedDay: Date;
    events: CalendarEvent[];
    pendingId: string | null;
    reduce: boolean;
    onSelectDay: (day: Date) => void;
    onOpenEvent: (event: CalendarEvent) => void;
}

function MonthCell({
    day,
    monthStart,
    today,
    selectedDay,
    events,
    pendingId,
    reduce,
    onSelectDay,
    onOpenEvent,
}: MonthCellProps) {
    const t = useTranslations('Calendar');
    const key = dayKeyOf(day);
    const { setNodeRef, isOver, active } = useDroppable({ id: DAY_DROP_PREFIX + key });

    const inMonth = day.getMonth() === monthStart.getMonth();
    const isToday = sameDay(day, today);
    const isSelected = sameDay(day, selectedDay);
    const kinds = kindsForDay(events);
    const extra = events.length - DESKTOP_CHIP_LIMIT;
    const isDropTarget = isOver && active != null;

    return (
        <div
            ref={setNodeRef}
            role="gridcell"
            aria-selected={isSelected}
            className={cn(
                'relative flex min-h-14 flex-col gap-1 p-1 transition-colors md:min-h-28 md:p-1.5',
                inMonth ? 'bg-card' : 'bg-muted/50',
                isDropTarget && 'bg-brand/5 ring-2 ring-inset ring-brand/40',
            )}
        >
            <button
                type="button"
                aria-label={t('selectDay', { date: key })}
                onClick={() => onSelectDay(day)}
                className="absolute inset-0 z-0 outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand/40"
            />

            <div className="pointer-events-none relative z-10 flex flex-col gap-1">
                <div className="flex items-center justify-between md:justify-end">
                    <span className="relative grid size-6 place-items-center">
                        {isSelected && (
                            <motion.span
                                layoutId={SELECTED_LAYOUT_ID}
                                aria-hidden
                                className="absolute inset-0 rounded-full bg-brand"
                                transition={reduce ? { duration: 0 } : { type: 'spring', stiffness: 520, damping: 42 }}
                            />
                        )}
                        {isToday && !isSelected && (
                            <span aria-hidden className="absolute inset-0 rounded-full ring-1 ring-inset ring-brand" />
                        )}
                        <span
                            className={cn(
                                'relative z-10 text-xs tabular-nums',
                                isSelected
                                    ? 'font-semibold text-white'
                                    : isToday
                                      ? 'font-semibold text-brand-dark'
                                      : inMonth
                                        ? 'text-foreground'
                                        : 'text-muted-foreground/60',
                            )}
                        >
                            {day.getDate()}
                        </span>
                    </span>
                </div>

                {kinds.length > 0 && (
                    <div className="flex items-center gap-1 md:hidden" aria-hidden>
                        {kinds.map((kind) => (
                            <span key={kind} className={cn('size-1.5 rounded-full', KIND_DOT_CLASS[kind])} />
                        ))}
                    </div>
                )}

                {events.length > 0 && (
                    <div className="hidden flex-col gap-0.5 md:flex">
                        {events.slice(0, DESKTOP_CHIP_LIMIT).map((event) => (
                            <DraggableChip
                                key={event.id}
                                event={event}
                                pending={pendingId === event.id}
                                onOpenEvent={onOpenEvent}
                            />
                        ))}
                        {extra > 0 && (
                            <button
                                type="button"
                                onClick={() => onSelectDay(day)}
                                className="pointer-events-auto px-2 text-left text-[11px] font-medium text-muted-foreground hover:text-foreground"
                            >
                                {t('moreCount', { count: extra })}
                            </button>
                        )}
                    </div>
                )}
            </div>
        </div>
    );
}

interface DraggableChipProps {
    event: CalendarEvent;
    pending: boolean;
    onOpenEvent: (event: CalendarEvent) => void;
}

function DraggableChip({ event, pending, onOpenEvent }: DraggableChipProps) {
    const { attributes, listeners, setNodeRef, isDragging } = useDraggable({
        id: event.id,
        disabled: !event.draggable,
    });

    return (
        <EventChip
            ref={setNodeRef}
            event={event}
            variant="chip"
            dragging={isDragging}
            onClick={() => onOpenEvent(event)}
            className={cn(
                'pointer-events-auto',
                event.draggable && 'cursor-grab touch-none',
                pending && 'animate-pulse',
            )}
            {...attributes}
            {...listeners}
        />
    );
}
