'use client';

import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import { useLocale, useTranslations } from 'next-intl';
import { useRouter } from 'next/navigation';
import { toast } from 'sonner';
import { ChevronLeftIcon, ChevronRightIcon } from '@heroicons/react/24/outline';

import Rise from '@/app/components/motion/Rise';
import { completeTask, rescheduleDeal, rescheduleTask } from '@/app/lib/api';
import { parseCalendarDate } from '@/app/lib/utils';
import type { Activity, Contact, Deal, Note, RelationshipTemperature, Task } from '@/app/lib/types';
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
import { useMediaQuery } from './useMediaQuery';
import ViewSwitcher from './ViewSwitcher';
import TypeFilter from './TypeFilter';
import MonthView from './MonthView';
import WeekView from './WeekView';
import DayView from './DayView';
import AgendaView from './AgendaView';
import EventDetailSheet from './EventDetailSheet';
import QuickCreateHost from './QuickCreateHost';
import GoToDateDialog from './GoToDateDialog';
import CalendarShortcuts from './CalendarShortcuts';
import UpNext from './UpNext';
import { temperatureIndex } from './warmth';
import ActivityDialog from '@/app/components/activity/activities/ActivityDialog';

const SWIPE_OFFSET = 40;

/**
 * Direction-aware transition: `custom` is the nav direction. 1/-1 (prev/next period) slide
 * laterally; 0 (view change / day drill-in) scales in subtly so it reads as drilling into
 * the view rather than a sideways move.
 */
const SLIDE_VARIANTS = {
    enter: (dir: number) => ({
        x: dir > 0 ? SWIPE_OFFSET : dir < 0 ? -SWIPE_OFFSET : 0,
        scale: dir === 0 ? 0.98 : 1,
        opacity: 0,
    }),
    center: { x: 0, scale: 1, opacity: 1 },
    exit: (dir: number) => ({
        x: dir > 0 ? -SWIPE_OFFSET : dir < 0 ? SWIPE_OFFSET : 0,
        scale: 1,
        opacity: 0,
    }),
};

export interface CalendarShellProps {
    activities?: Activity[];
    tasks?: Task[];
    persons?: Contact[];
    deals?: Deal[];
    notes?: Note[];
    temperatures?: RelationshipTemperature[];
    currentUserId: number;
}

export default function CalendarShell({
    activities,
    tasks,
    persons,
    deals,
    notes,
    temperatures,
    currentUserId,
}: CalendarShellProps) {
    const t = useTranslations('Calendar');
    const locale = useLocale();
    const cal = useCalendar();
    const router = useRouter();
    const reduce = useReducedMotion() ?? false;
    const coarse = useCoarsePointer();
    const isWide = useMediaQuery('(min-width: 1024px)');
    const swipeRef = useRef<HTMLDivElement>(null);
    const [today] = useState(() => startOfDay(new Date()));
    const [openEventId, setOpenEventId] = useState<string | null>(null);
    const [overrides, setOverrides] = useState<Map<string, string>>(() => new Map());
    const [pendingIds, setPendingIds] = useState<Set<string>>(() => new Set());
    const [slotAt, setSlotAt] = useState<string | null>(null);
    const [helpOpen, setHelpOpen] = useState(false);
    const [goToOpen, setGoToOpen] = useState(false);
    const [createOpen, setCreateOpen] = useState(false);

    const personById = useMemo(() => {
        const map = new Map<number, Contact>();
        for (const p of persons ?? []) map.set(p.id, p);
        return map;
    }, [persons]);

    const temperatureByContact = useMemo(() => temperatureIndex(temperatures ?? []), [temperatures]);

    const dealById = useMemo(() => {
        const map = new Map<number, Deal>();
        for (const d of deals ?? []) map.set(d.id, d);
        return map;
    }, [deals]);

    const baseEvents = useMemo(
        () => buildEvents({ tasks, activities, deals, notes, persons }),
        [tasks, activities, deals, notes, persons],
    );

    if (overrides.size > 0) {
        const baseByEventId = new Map(baseEvents.map((e) => [e.id, e.dayKey]));
        let pruned: Map<string, string> | null = null;
        for (const [id, key] of overrides) {
            if (!pendingIds.has(id) && baseByEventId.get(id) === key) {
                if (!pruned) pruned = new Map(overrides);
                pruned.delete(id);
            }
        }
        if (pruned) setOverrides(pruned);
    }

    const events = useMemo(() => {
        if (overrides.size === 0) return baseEvents;
        return baseEvents.map((e) => {
            const key = overrides.get(e.id);
            if (!key || key === e.dayKey) return e;
            const ms = parseCalendarDate(key);
            if (Number.isNaN(ms)) return e;
            return { ...e, dayKey: key, startMs: ms };
        });
    }, [baseEvents, overrides]);

    const visibleEvents = useMemo(
        () => events.filter((e) => cal.visibleKinds.has(e.kind)),
        [events, cal.visibleKinds],
    );

    const applyReschedule = useCallback(
        async (event: CalendarEvent, key: string): Promise<boolean> => {
            setOverrides((prev) => new Map(prev).set(event.id, key));
            setPendingIds((prev) => new Set(prev).add(event.id));
            try {
                if (event.kind === 'task') {
                    await rescheduleTask(event.entityId, key);
                } else if (event.kind === 'deal') {
                    await rescheduleDeal(event.entityId, key);
                }
                router.refresh();
                return true;
            } catch {
                setOverrides((prev) => {
                    if (prev.get(event.id) !== key) return prev;
                    const next = new Map(prev);
                    next.delete(event.id);
                    return next;
                });
                return false;
            } finally {
                setPendingIds((prev) => {
                    if (!prev.has(event.id)) return prev;
                    const next = new Set(prev);
                    next.delete(event.id);
                    return next;
                });
            }
        },
        [router],
    );

    const handleReschedule = useCallback(
        async (event: CalendarEvent, newDayKey: string) => {
            if (!event.draggable || newDayKey === event.dayKey || Number.isNaN(parseCalendarDate(newDayKey))) {
                return;
            }
            const originalKey = event.dayKey;
            const ok = await applyReschedule(event, newDayKey);
            if (ok) {
                toast.success(t('rescheduled'), {
                    id: `reschedule-${event.id}`,
                    action: {
                        label: t('undo'),
                        onClick: () => {
                            void applyReschedule(event, originalKey).then((undone) => {
                                if (!undone) toast.error(t('rescheduleFailed'));
                            });
                        },
                    },
                });
            } else {
                toast.error(t('rescheduleFailed'));
            }
        },
        [applyReschedule, t],
    );

    const handleComplete = useCallback(
        async (event: CalendarEvent) => {
            if (event.kind !== 'task') return;
            try {
                await completeTask(event.entityId);
                toast.success(t('taskCompleted'));
                setOpenEventId(null);
                router.refresh();
            } catch {
                toast.error(t('completeFailed'));
            }
        },
        [router, t],
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

    const openEvent = useMemo(
        () => (openEventId ? events.find((e) => e.id === openEventId) ?? null : null),
        [openEventId, events],
    );
    const onOpenEvent = (event: CalendarEvent) => setOpenEventId(event.id);

    const onSelectDay = (day: Date) => {
        if (isWide) cal.selectDay(day);
        else cal.openDay(day);
    };

    const onSlotCreate = (startMs: number) => {
        const d = new Date(startMs);
        const pad = (n: number) => String(n).padStart(2, '0');
        setSlotAt(
            `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`,
        );
    };

    useEffect(() => {
        const onKey = (e: KeyboardEvent) => {
            if (e.metaKey || e.ctrlKey || e.altKey) return;
            const el = e.target as HTMLElement | null;
            if (el && (el.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(el.tagName))) return;
            if (
                document.querySelector(
                    '[data-state="open"][role="dialog"],[data-state="open"][role="alertdialog"],[data-state="open"][role="menu"]',
                )
            ) {
                return;
            }
            switch (e.key) {
                case 't': case 'T': cal.goToday(); break;
                case 'm': case 'M': cal.setView('month'); break;
                case 'w': case 'W': cal.setView('week'); break;
                case 'd': case 'D': cal.setView('day'); break;
                case 'a': case 'A': cal.setView('agenda'); break;
                case 'g': case 'G': setGoToOpen(true); break;
                case 'c': case 'C': setCreateOpen(true); break;
                case '?': setHelpOpen(true); break;
                case 'ArrowLeft': if (cal.view !== 'agenda') cal.goPrev(); else return; break;
                case 'ArrowRight': if (cal.view !== 'agenda') cal.goNext(); else return; break;
                default: return;
            }
            e.preventDefault();
        };
        window.addEventListener('keydown', onKey);
        return () => window.removeEventListener('keydown', onKey);
    }, [cal]);

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
                  scale: { duration: 0.22, ease: [0.23, 1, 0.32, 1] as const },
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
                        pendingIds={pendingIds}
                        dragEnabled={!coarse}
                        onSelectDay={onSelectDay}
                        onOpenEvent={onOpenEvent}
                        onReschedule={handleReschedule}
                        onSlotCreate={coarse ? undefined : onSlotCreate}
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
                        onSlotCreate={coarse ? undefined : onSlotCreate}
                    />
                );
            case 'agenda':
                return (
                    <AgendaView
                        events={visibleEvents}
                        today={today}
                        locale={locale}
                        temperatureByContact={temperatureByContact}
                        onOpenEvent={onOpenEvent}
                    />
                );
        }
    };

    return (
        <div className="min-h-full bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-[100rem] flex-col gap-6">
                <Rise>
                    <header className="flex flex-col gap-3">
                        <div className="flex items-end justify-between gap-3">
                            <div>
                                <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">{t('title')}</h1>
                                {periodLabel && (
                                    <button
                                        type="button"
                                        onClick={() => setGoToOpen(true)}
                                        aria-label={t('goToDate')}
                                        className="mt-1 rounded text-sm text-muted-foreground tabular-nums outline-none transition-colors hover:text-foreground focus-visible:ring-2 focus-visible:ring-brand/40"
                                    >
                                        {periodLabel}
                                    </button>
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

                <UpNext events={visibleEvents} locale={locale} onOpenEvent={onOpenEvent} />

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
                    if (!next) setOpenEventId(null);
                }}
                locale={locale}
                personById={personById}
                dealById={dealById}
                temperatureByContact={temperatureByContact}
                currentUserId={currentUserId}
                onReschedule={handleReschedule}
                onComplete={handleComplete}
                rescheduling={openEvent != null && pendingIds.has(openEvent.id)}
            />

            <QuickCreateHost
                selectedDay={cal.selectedDay}
                persons={persons ?? []}
                deals={deals ?? []}
                currentUserId={currentUserId}
                menuOpen={createOpen}
                onMenuOpenChange={setCreateOpen}
            />

            <ActivityDialog
                open={slotAt != null}
                onOpenChange={(next) => {
                    if (!next) setSlotAt(null);
                }}
                persons={persons ?? []}
                deals={deals ?? []}
                currentUserId={currentUserId}
                defaultTimestamp={slotAt ?? undefined}
            />

            <GoToDateDialog
                open={goToOpen}
                onOpenChange={setGoToOpen}
                initialDate={cal.anchor}
                onPick={cal.goToDate}
            />
            <CalendarShortcuts open={helpOpen} onOpenChange={setHelpOpen} />
        </div>
    );
}

/** Re-exported so tests and callers share the day-key helper the shell uses. */
export { dayKeyOf };
