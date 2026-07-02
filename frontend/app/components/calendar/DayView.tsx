'use client';

import { useMemo } from 'react';

import { dayKeyOf, type CalendarEvent } from '@/app/lib/calendar';
import DayTimeline from './DayTimeline';

/** Day view: a single day's hour timeline. The period title lives in the shell header. */
export default function DayView({
    day,
    today,
    eventsByDay,
    locale,
    onOpenEvent,
}: {
    day: Date;
    today: Date;
    eventsByDay: Map<string, CalendarEvent[]>;
    locale: string;
    onOpenEvent: (event: CalendarEvent) => void;
}) {
    const events = useMemo(() => eventsByDay.get(dayKeyOf(day)) ?? [], [eventsByDay, day]);
    return (
        <DayTimeline day={day} events={events} today={today} locale={locale} onOpenEvent={onOpenEvent} />
    );
}
