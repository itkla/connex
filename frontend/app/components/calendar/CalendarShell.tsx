'use client';

import { useMemo, useState } from 'react';
import { useLocale, useTranslations } from 'next-intl';
import { ChevronLeftIcon, ChevronRightIcon } from '@heroicons/react/24/outline';

import Rise from '@/app/components/motion/Rise';
import type { Activity, Contact, Deal, Note, Task } from '@/app/lib/types';
import {
    buildEvents,
    dayKeyOf,
    groupByDay,
    startOfDay,
    startOfMonth,
    weekDays,
    type CalendarEvent,
} from '@/app/lib/calendar';
import { useCalendar, WEEK_STARTS_ON } from './useCalendar';
import ViewSwitcher from './ViewSwitcher';
import TypeFilter from './TypeFilter';
import MonthView from './MonthView';
import WeekView from './WeekView';
import DayView from './DayView';
import AgendaView from './AgendaView';
import EventDetailSheet from './EventDetailSheet';

export interface CalendarShellProps {
    activities?: Activity[];
    tasks?: Task[];
    persons?: Contact[];
    deals?: Deal[];
    notes?: Note[];
}

export default function CalendarShell({ activities, tasks, persons, deals, notes }: CalendarShellProps) {
    const t = useTranslations('Calendar');
    const locale = useLocale();
    const cal = useCalendar();
    const [today] = useState(() => startOfDay(new Date()));
    const [openEvent, setOpenEvent] = useState<CalendarEvent | null>(null);

    const personById = useMemo(() => {
        const map = new Map<number, Contact>();
        for (const p of persons ?? []) map.set(p.id, p);
        return map;
    }, [persons]);

    const dealById = useMemo(() => {
        const map = new Map<number, Deal>();
        for (const d of deals ?? []) map.set(d.id, d);
        return map;
    }, [deals]);

    const events = useMemo(
        () => buildEvents({ tasks, activities, deals, notes, persons }),
        [tasks, activities, deals, notes, persons],
    );

    const visibleEvents = useMemo(
        () => events.filter((e) => cal.visibleKinds.has(e.kind)),
        [events, cal.visibleKinds],
    );

    const eventsByDay = useMemo(() => groupByDay(visibleEvents), [visibleEvents]);

    const periodLabel = useMemo(() => {
        if (cal.view === 'month') {
            return new Intl.DateTimeFormat(locale, { month: 'long', year: 'numeric' }).format(
                startOfMonth(cal.anchor),
            );
        }
        if (cal.view === 'day') {
            return new Intl.DateTimeFormat(locale, {
                weekday: 'long',
                month: 'long',
                day: 'numeric',
                year: 'numeric',
            }).format(cal.anchor);
        }
        if (cal.view === 'week') {
            const days = weekDays(cal.anchor, WEEK_STARTS_ON);
            const start = new Intl.DateTimeFormat(locale, { month: 'short', day: 'numeric' }).format(days[0]);
            const end = new Intl.DateTimeFormat(locale, {
                month: 'short',
                day: 'numeric',
                year: 'numeric',
            }).format(days[6]);
            return `${start} - ${end}`;
        }
        return null;
    }, [cal.view, cal.anchor, locale]);

    const onOpenEvent = (event: CalendarEvent) => setOpenEvent(event);

    return (
        <div className="min-h-full bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-7xl flex-col gap-6">
                <Rise>
                    <header className="flex flex-col gap-3">
                        <div className="flex items-end justify-between gap-3">
                            <div>
                                <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">{t('title')}</h1>
                                {periodLabel && (
                                    <p className="mt-1 text-sm text-muted-foreground tabular-nums">{periodLabel}</p>
                                )}
                            </div>
                            <div className="flex shrink-0 items-center gap-1.5">
                                <button
                                    type="button"
                                    onClick={cal.goToday}
                                    className="h-8 rounded-full bg-muted px-4 text-xs font-medium text-foreground outline-none ring-1 ring-border transition active:scale-[0.97] hover:bg-background focus-visible:ring-2 focus-visible:ring-brand/40"
                                >
                                    {t('goToToday')}
                                </button>
                                {cal.view !== 'agenda' && (
                                    <>
                                        <button
                                            type="button"
                                            onClick={cal.goPrev}
                                            aria-label={t('prevPeriod')}
                                            className="grid size-8 place-items-center rounded-full bg-muted text-foreground outline-none ring-1 ring-border transition active:scale-[0.97] hover:bg-background focus-visible:ring-2 focus-visible:ring-brand/40"
                                        >
                                            <ChevronLeftIcon className="size-4" />
                                        </button>
                                        <button
                                            type="button"
                                            onClick={cal.goNext}
                                            aria-label={t('nextPeriod')}
                                            className="grid size-8 place-items-center rounded-full bg-muted text-foreground outline-none ring-1 ring-border transition active:scale-[0.97] hover:bg-background focus-visible:ring-2 focus-visible:ring-brand/40"
                                        >
                                            <ChevronRightIcon className="size-4" />
                                        </button>
                                    </>
                                )}
                            </div>
                        </div>
                        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                            <ViewSwitcher value={cal.view} onChange={cal.setView} />
                            <TypeFilter visibleKinds={cal.visibleKinds} onToggle={cal.toggleKind} />
                        </div>
                    </header>
                </Rise>

                <Rise delay={0.06}>
                    {cal.view === 'month' && (
                        <MonthView
                            anchor={cal.anchor}
                            selectedDay={cal.selectedDay}
                            today={today}
                            eventsByDay={eventsByDay}
                            locale={locale}
                            onSelectDay={cal.setSelectedDay}
                            onOpenEvent={onOpenEvent}
                        />
                    )}
                    {cal.view === 'week' && (
                        <WeekView
                            anchor={cal.anchor}
                            selectedDay={cal.selectedDay}
                            today={today}
                            eventsByDay={eventsByDay}
                            locale={locale}
                            onSelectDay={cal.setSelectedDay}
                            onOpenEvent={onOpenEvent}
                        />
                    )}
                    {cal.view === 'day' && (
                        <DayView
                            day={cal.anchor}
                            today={today}
                            eventsByDay={eventsByDay}
                            locale={locale}
                            onOpenEvent={onOpenEvent}
                        />
                    )}
                    {cal.view === 'agenda' && (
                        <AgendaView
                            events={visibleEvents}
                            today={today}
                            locale={locale}
                            onOpenEvent={onOpenEvent}
                        />
                    )}
                </Rise>
            </div>

            <EventDetailSheet
                event={openEvent}
                open={openEvent != null}
                onOpenChange={(next) => {
                    if (!next) setOpenEvent(null);
                }}
                locale={locale}
                personById={personById}
                dealById={dealById}
            />
        </div>
    );
}

/** Re-exported so tests and callers share the day-key helper the shell uses. */
export { dayKeyOf };
