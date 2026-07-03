'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import { useTranslations } from 'next-intl';

import { cn } from '@/lib/utils';
import {
    dayKeyOf,
    layoutTimedEvents,
    minutesSinceMidnight,
    sameDay,
    startOfDay,
    type CalendarEvent,
} from '@/app/lib/calendar';
import EventChip from './EventChip';

const HOUR_PX = 56;
const HOURS = Array.from({ length: 24 }, (_, i) => i);
const SLOT_MIN = 30;
const MAX_SLOT_MIN = 23 * 60 + 30;

/**
 * A single day's hour timeline: a pinned all-day row for date-only records, and an
 * hour grid where timed records are positioned by their time and split into lanes
 * when they overlap. A live "now" line is drawn when the day is today. The timeline
 * owns its own vertical scroll so it doesn't stretch the page.
 */
export default function DayTimeline({
    day,
    events,
    today,
    locale,
    onOpenEvent,
    onSlotCreate,
    className,
}: {
    day: Date;
    events: CalendarEvent[];
    today: Date;
    locale: string;
    onOpenEvent: (event: CalendarEvent) => void;
    /** Fine-pointer create: click an empty slot to add something at that time (ms). Omitted on touch. */
    onSlotCreate?: (startMs: number) => void;
    className?: string;
}) {
    const t = useTranslations('Calendar');
    const scrollRef = useRef<HTMLDivElement>(null);
    const scrolledDayRef = useRef<string | null>(null);
    const [nowMin, setNowMin] = useState<number | null>(null);

    const isToday = sameDay(day, today);
    const allDay = useMemo(() => events.filter((e) => e.allDay), [events]);
    const placements = useMemo(() => layoutTimedEvents(events), [events]);

    const hourFmt = useMemo(() => new Intl.DateTimeFormat(locale, { hour: 'numeric' }), [locale]);
    const timeFmt = useMemo(
        () => new Intl.DateTimeFormat(locale, { hour: 'numeric', minute: '2-digit' }),
        [locale],
    );
    const hourLabels = useMemo(
        () => HOURS.map((h) => hourFmt.format(new Date(2000, 0, 1, h))),
        [hourFmt],
    );

    useEffect(() => {
        if (!isToday) return;
        const tick = () => setNowMin(minutesSinceMidnight(Date.now()));
        const raf = window.requestAnimationFrame(tick);
        const id = window.setInterval(tick, 60_000);
        return () => {
            window.cancelAnimationFrame(raf);
            window.clearInterval(id);
        };
    }, [isToday]);

    useEffect(() => {
        const el = scrollRef.current;
        if (!el) return;
        if (isToday && nowMin == null) return;
        const key = dayKeyOf(day);
        if (scrolledDayRef.current === key) return;
        scrolledDayRef.current = key;
        const focusMin = isToday && nowMin != null ? nowMin : 8 * 60;
        el.scrollTop = Math.max(0, (focusMin / 60) * HOUR_PX - HOUR_PX);
    }, [day, isToday, nowMin]);

    return (
        <div className={cn('flex flex-col overflow-hidden rounded-2xl border border-border bg-card', className)}>
            {allDay.length > 0 && (
                <div className="flex items-start gap-2 border-b border-border px-3 py-2">
                    <span className="w-11 shrink-0 pt-1 text-[10px] font-semibold uppercase tracking-wide text-muted-foreground">
                        {t('allDayRow')}
                    </span>
                    <div className="flex min-w-0 flex-1 flex-wrap gap-1">
                        {allDay.map((event) => (
                            <EventChip
                                key={event.id}
                                event={event}
                                variant="chip"
                                onClick={() => onOpenEvent(event)}
                                className="w-auto max-w-full"
                            />
                        ))}
                    </div>
                </div>
            )}

            <div ref={scrollRef} className="max-h-[65vh] overflow-y-auto overscroll-contain">
                <div className="relative" style={{ height: HOURS.length * HOUR_PX }}>
                    {onSlotCreate && (
                        <button
                            type="button"
                            tabIndex={-1}
                            aria-label={t('createAtTime')}
                            onClick={(e) => {
                                const rect = e.currentTarget.getBoundingClientRect();
                                const min = Math.max(
                                    0,
                                    Math.min(
                                        MAX_SLOT_MIN,
                                        Math.round(((e.clientY - rect.top) / HOUR_PX) * 60 / SLOT_MIN) * SLOT_MIN,
                                    ),
                                );
                                onSlotCreate(startOfDay(day).getTime() + min * 60_000);
                            }}
                            className="absolute inset-0 z-0 cursor-copy rounded-none outline-none transition-colors hover:bg-brand/[0.03] focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand/40"
                        />
                    )}
                    {HOURS.map((hour, i) => (
                        <div
                            key={hour}
                            className="pointer-events-none absolute inset-x-0 border-t border-border/60"
                            style={{ top: hour * HOUR_PX }}
                        >
                            <span className="absolute -top-2 left-2 text-[10px] tabular-nums text-muted-foreground">
                                {i === 0 ? '' : hourLabels[hour]}
                            </span>
                        </div>
                    ))}

                    <div className="pointer-events-none absolute inset-y-0 left-14 right-2 z-10">
                        {placements.map(({ event, startMin, lane, laneCount }) => {
                            const top = (startMin / 60) * HOUR_PX;
                            const widthPct = 100 / laneCount;
                            return (
                                <div
                                    key={event.id}
                                    className="pointer-events-auto absolute px-0.5"
                                    style={{
                                        top,
                                        height: HOUR_PX - 6,
                                        left: `${lane * widthPct}%`,
                                        width: `${widthPct}%`,
                                    }}
                                >
                                    <EventChip
                                        event={event}
                                        variant="bar"
                                        timeLabel={timeFmt.format(event.startMs)}
                                        onClick={() => onOpenEvent(event)}
                                    />
                                </div>
                            );
                        })}
                    </div>

                    {isToday && nowMin != null && (
                        <div
                            className="pointer-events-none absolute inset-x-0 z-10 flex items-center"
                            style={{ top: (nowMin / 60) * HOUR_PX }}
                            aria-hidden
                        >
                            <span className="ml-11 size-2 rounded-full bg-destructive" />
                            <span className="h-px flex-1 bg-destructive" />
                        </div>
                    )}
                </div>
            </div>
        </div>
    );
}
