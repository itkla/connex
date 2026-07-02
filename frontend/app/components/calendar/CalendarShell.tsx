'use client';

import { useMemo, useRef, useState, type ReactNode } from 'react';
import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
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
    startOfWeek,
    weekDays,
    type CalendarEvent,
} from '@/app/lib/calendar';
import { useCalendar, WEEK_STARTS_ON } from './useCalendar';
import { useCoarsePointer } from './useCoarsePointer';
import ViewSwitcher from './ViewSwitcher';
import TypeFilter from './TypeFilter';
import MonthView from './MonthView';
import WeekView from './WeekView';
import DayView from './DayView';
import AgendaView from './AgendaView';
import EventDetailSheet from './EventDetailSheet';
import QuickCreateHost from './QuickCreateHost';

const SWIPE_OFFSET = 40;

/** Direction-aware slide: `custom` is the nav direction (1 next, -1 prev, 0 jump/crossfade). */
const SLIDE_VARIANTS = {
    enter: (dir: number) => ({ x: dir > 0 ? SWIPE_OFFSET : dir < 0 ? -SWIPE_OFFSET : 0, opacity: 0 }),
    center: { x: 0, opacity: 1 },
    exit: (dir: number) => ({ x: dir > 0 ? -SWIPE_OFFSET : dir < 0 ? SWIPE_OFFSET : 0, opacity: 0 }),
};

export interface CalendarShellProps {
    activities?: Activity[];
    tasks?: Task[];
    persons?: Contact[];
    deals?: Deal[];
    notes?: Note[];
    currentUserId: number;
}

export default function CalendarShell({
    activities,
    tasks,
    persons,
    deals,
    notes,
    currentUserId,
}: CalendarShellProps) {
    const t = useTranslations('Calendar');
    const locale = useLocale();
    const cal = useCalendar();
    const reduce = useReducedMotion() ?? false;
    const coarse = useCoarsePointer();
    const swipeRef = useRef<HTMLDivElement>(null);
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

    const periodKey = useMemo(() => {
        switch (cal.view) {
            case 'month':
                return `m:${cal.anchor.getFullYear()}-${cal.anchor.getMonth()}`;
            case 'week':
                return `w:${dayKeyOf(startOfWeek(cal.anchor, WEEK_STARTS_ON))}`;
            case 'day':
                return `d:${dayKeyOf(cal.anchor)}`;
            case 'agenda':
                return 'a';
        }
    }, [cal.view, cal.anchor]);

    const enableSwipe = coarse && !reduce && cal.view !== 'agenda';

    const viewAnim = reduce
        ? {
              initial: { opacity: 0 },
              animate: { opacity: 1 },
              exit: { opacity: 0 },
              transition: { duration: 0.15 },
          }
        : {
              variants: SLIDE_VARIANTS,
              initial: 'enter' as const,
              animate: 'center' as const,
              exit: 'exit' as const,
              transition: {
                  x: { type: 'spring' as const, stiffness: 420, damping: 34 },
                  opacity: { duration: 0.2 },
              },
          };

    const renderView = (): ReactNode => {
        switch (cal.view) {
            case 'month':
                return (
                    <MonthView
                        anchor={cal.anchor}
                        selectedDay={cal.selectedDay}
                        today={today}
                        eventsByDay={eventsByDay}
                        locale={locale}
                        onSelectDay={cal.setSelectedDay}
                        onOpenEvent={onOpenEvent}
                    />
                );
            case 'week':
                return (
                    <WeekView
                        anchor={cal.anchor}
                        selectedDay={cal.selectedDay}
                        today={today}
                        eventsByDay={eventsByDay}
                        locale={locale}
                        onSelectDay={cal.setSelectedDay}
                        onOpenEvent={onOpenEvent}
                    />
                );
            case 'day':
                return (
                    <DayView
                        day={cal.anchor}
                        today={today}
                        eventsByDay={eventsByDay}
                        locale={locale}
                        onOpenEvent={onOpenEvent}
                    />
                );
            case 'agenda':
                return (
                    <AgendaView
                        events={visibleEvents}
                        today={today}
                        locale={locale}
                        onOpenEvent={onOpenEvent}
                    />
                );
        }
    };

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
                    <div ref={swipeRef} className="relative">
                        <motion.div
                            drag={enableSwipe ? 'x' : false}
                            dragDirectionLock
                            dragSnapToOrigin
                            dragElastic={0.2}
                            onDragEnd={(_event, info) => {
                                const width = swipeRef.current?.offsetWidth ?? 320;
                                const threshold = Math.min(120, width * 0.25);
                                if (info.offset.x <= -threshold || info.velocity.x < -500) cal.goNext();
                                else if (info.offset.x >= threshold || info.velocity.x > 500) cal.goPrev();
                            }}
                        >
                            <AnimatePresence mode="popLayout" custom={cal.navDirection} initial={false}>
                                <motion.div
                                    key={`${cal.view}:${periodKey}`}
                                    custom={cal.navDirection}
                                    {...viewAnim}
                                >
                                    {renderView()}
                                </motion.div>
                            </AnimatePresence>
                        </motion.div>
                    </div>
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

            <QuickCreateHost
                selectedDay={cal.selectedDay}
                persons={persons ?? []}
                deals={deals ?? []}
                currentUserId={currentUserId}
            />
        </div>
    );
}

/** Re-exported so tests and callers share the day-key helper the shell uses. */
export { dayKeyOf };
