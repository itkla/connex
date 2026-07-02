'use client';

import { useMemo } from 'react';
import { motion, useReducedMotion } from 'motion/react';
import { useTranslations } from 'next-intl';

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

interface MonthViewProps {
    anchor: Date;
    selectedDay: Date;
    today: Date;
    eventsByDay: Map<string, CalendarEvent[]>;
    locale: string;
    onSelectDay: (day: Date) => void;
    onOpenEvent: (event: CalendarEvent) => void;
}

export default function MonthView({
    anchor,
    selectedDay,
    today,
    eventsByDay,
    locale,
    onSelectDay,
    onOpenEvent,
}: MonthViewProps) {
    const t = useTranslations('Calendar');
    const reduce = useReducedMotion() ?? false;

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

    const selectedKey = dayKeyOf(selectedDay);
    const selectedEvents = eventsByDay.get(selectedKey) ?? [];

    return (
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
                    {cells.map((day) => {
                        const key = dayKeyOf(day);
                        const inMonth = day.getMonth() === monthStart.getMonth();
                        const isToday = sameDay(day, today);
                        const isSelected = sameDay(day, selectedDay);
                        const events = eventsByDay.get(key) ?? [];
                        const kinds = kindsForDay(events);
                        const extra = events.length - DESKTOP_CHIP_LIMIT;

                        return (
                            <div
                                key={key}
                                role="gridcell"
                                aria-selected={isSelected}
                                className={cn(
                                    'relative flex min-h-14 flex-col gap-1 p-1 md:min-h-28 md:p-1.5',
                                    inMonth ? 'bg-card' : 'bg-muted/50',
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
                                                    transition={
                                                        reduce
                                                            ? { duration: 0 }
                                                            : { type: 'spring', stiffness: 520, damping: 42 }
                                                    }
                                                />
                                            )}
                                            {isToday && !isSelected && (
                                                <span
                                                    aria-hidden
                                                    className="absolute inset-0 rounded-full ring-1 ring-inset ring-brand"
                                                />
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
                                                <span
                                                    key={kind}
                                                    className={cn('size-1.5 rounded-full', KIND_DOT_CLASS[kind])}
                                                />
                                            ))}
                                        </div>
                                    )}

                                    {events.length > 0 && (
                                        <div className="hidden flex-col gap-0.5 md:flex">
                                            {events.slice(0, DESKTOP_CHIP_LIMIT).map((event) => (
                                                <EventChip
                                                    key={event.id}
                                                    event={event}
                                                    variant="chip"
                                                    onClick={() => onOpenEvent(event)}
                                                    className="pointer-events-auto"
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
                    })}
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
    );
}
