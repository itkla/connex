'use client';

import { useMemo } from 'react';
import { useTranslations } from 'next-intl';

import { cn } from '@/lib/utils';
import {
    dayKeyOf,
    kindsForDay,
    sameDay,
    weekDays,
    type CalendarEvent,
} from '@/app/lib/calendar';
import { WEEK_STARTS_ON } from './useCalendar';
import { KIND_DOT_CLASS } from './constants';
import EventChip from './EventChip';
import DayTimeline from './DayTimeline';

/** State ring/fill for a date number, shared by the ribbon pills and the desktop column headers. */
function DayNumber({
    day,
    isToday,
    isSelected,
}: {
    day: Date;
    isToday: boolean;
    isSelected: boolean;
}) {
    return (
        <span className="relative grid size-7 place-items-center">
            {isSelected && <span aria-hidden className="absolute inset-0 rounded-full bg-brand" />}
            {isToday && !isSelected && (
                <span aria-hidden className="absolute inset-0 rounded-full ring-1 ring-inset ring-brand" />
            )}
            <span
                className={cn(
                    'relative z-10 text-sm tabular-nums',
                    isSelected ? 'font-semibold text-brand-foreground' : isToday ? 'font-semibold text-brand-dark' : 'text-foreground',
                )}
            >
                {day.getDate()}
            </span>
        </span>
    );
}

interface WeekViewProps {
    anchor: Date;
    selectedDay: Date;
    today: Date;
    eventsByDay: Map<string, CalendarEvent[]>;
    locale: string;
    onSelectDay: (day: Date) => void;
    onOpenEvent: (event: CalendarEvent) => void;
}

export default function WeekView({
    anchor,
    selectedDay,
    today,
    eventsByDay,
    locale,
    onSelectDay,
    onOpenEvent,
}: WeekViewProps) {
    const t = useTranslations('Calendar');
    const days = useMemo(() => weekDays(anchor, WEEK_STARTS_ON), [anchor]);
    const weekdayFmt = useMemo(() => new Intl.DateTimeFormat(locale, { weekday: 'short' }), [locale]);
    const timeFmt = useMemo(
        () => new Intl.DateTimeFormat(locale, { hour: 'numeric', minute: '2-digit' }),
        [locale],
    );

    const selectedEvents = eventsByDay.get(dayKeyOf(selectedDay)) ?? [];

    return (
        <div className="flex flex-col gap-4">
            <div className="grid grid-cols-7 gap-1 lg:hidden">
                {days.map((day) => {
                    const isSelected = sameDay(day, selectedDay);
                    const isToday = sameDay(day, today);
                    const kinds = kindsForDay(eventsByDay.get(dayKeyOf(day)) ?? []);
                    return (
                        <button
                            key={dayKeyOf(day)}
                            type="button"
                            onClick={() => onSelectDay(day)}
                            aria-pressed={isSelected}
                            className="flex flex-col items-center gap-1 rounded-xl py-2 outline-none transition active:scale-[0.97] focus-visible:ring-2 focus-visible:ring-brand/40"
                        >
                            <span className="text-[10px] font-medium uppercase tracking-wide text-muted-foreground">
                                {weekdayFmt.format(day)}
                            </span>
                            <DayNumber day={day} isToday={isToday} isSelected={isSelected} />
                            <span className="flex h-1.5 items-center gap-0.5" aria-hidden>
                                {kinds.slice(0, 4).map((kind) => (
                                    <span key={kind} className={cn('size-1 rounded-full', KIND_DOT_CLASS[kind])} />
                                ))}
                            </span>
                        </button>
                    );
                })}
            </div>

            <div className="lg:hidden">
                <DayTimeline
                    day={selectedDay}
                    events={selectedEvents}
                    today={today}
                    locale={locale}
                    onOpenEvent={onOpenEvent}
                />
            </div>

            <div className="hidden overflow-hidden rounded-2xl border border-border bg-card lg:block">
                <div className="grid grid-cols-7 divide-x divide-border">
                    {days.map((day) => {
                        const key = dayKeyOf(day);
                        const isToday = sameDay(day, today);
                        const isSelected = sameDay(day, selectedDay);
                        const dayEvents = eventsByDay.get(key) ?? [];
                        return (
                            <div key={key} className="flex min-h-72 flex-col">
                                <button
                                    type="button"
                                    onClick={() => onSelectDay(day)}
                                    className={cn(
                                        'flex flex-col items-center gap-1 border-b border-border py-2 outline-none transition-colors hover:bg-muted/40 focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand/40',
                                        isSelected && 'bg-muted/40',
                                    )}
                                >
                                    <span className="text-[10px] font-medium uppercase tracking-wide text-muted-foreground">
                                        {weekdayFmt.format(day)}
                                    </span>
                                    <DayNumber day={day} isToday={isToday} isSelected={isSelected} />
                                </button>
                                <div className="flex flex-1 flex-col gap-0.5 p-1">
                                    {dayEvents.length === 0 ? (
                                        <span className="px-1 pt-2 text-center text-[11px] text-muted-foreground/70">
                                            {t('noEventsShort')}
                                        </span>
                                    ) : (
                                        dayEvents.map((event) => (
                                            <EventChip
                                                key={event.id}
                                                event={event}
                                                variant="bar"
                                                timeLabel={event.allDay ? undefined : timeFmt.format(event.startMs)}
                                                onClick={() => onOpenEvent(event)}
                                                className="min-h-9"
                                            />
                                        ))
                                    )}
                                </div>
                            </div>
                        );
                    })}
                </div>
            </div>
        </div>
    );
}
