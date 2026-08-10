'use client';

import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { CalendarDaysIcon } from '@heroicons/react/16/solid';

import { Button } from '@/components/ui/button';
import {
    Popover,
    PopoverContent,
    PopoverDescription,
    PopoverTitle,
    PopoverTrigger,
} from '@/components/ui/popover';
import { RangeCalendar } from '@/components/ui/range-calendar';
import { getActivityVolume } from '@/app/lib/api';
import { addDays, dayKeyOf } from '@/app/lib/calendar';
import { parseDayKey, rangeDays, splitIntoWindows, sumSeries, type DateRange } from '@/app/lib/rangeCalendar';
import {
    MAX_CUSTOM_RANGE_DAYS,
    parseCustomAnalyticsWindow,
    type AnalyticsWindow,
} from '@/app/components/overview/analytics/metrics';
import type { MemberScopeParams } from '@/app/lib/types';

/** Localized copy required by the Analytics custom-range popover. */
export type CustomRangeLabels = {
    custom: string;
    title: string;
    description: string;
    start: string;
    end: string;
    /** Pluralized span readout, e.g. `(43) => "43 days"`. */
    days: (count: number) => string;
    /** Pluralized count of activities inside the drafted range. */
    activities: (count: number) => string;
    grid: string;
    previous: string;
    next: string;
    zoomMonths: string;
    zoomYears: string;
    cancel: string;
    apply: string;
};

/** Extra context loaded either side of the visible months so paging rarely waits on a fetch. */
const PREFETCH_DAYS = 120;

function formatDay(day: string, formatter: Intl.DateTimeFormat): string {
    return formatter.format(new Date(`${day}T00:00:00Z`));
}

function formatWindow(window: AnalyticsWindow, formatter: Intl.DateTimeFormat): string {
    return `${formatDay(window.from, formatter)} – ${formatDay(window.to, formatter)}`;
}

/**
 * Anchored custom-range calendar for the Analytics period control. The calendar is backed by the
 * same day-bucketed activity volume the board charts, so the grid shows where the workspace was
 * actually busy and the drafted range reports what it contains before it is applied.
 */
export default function CustomRangePopover({
    active,
    value,
    locale,
    today,
    timezone,
    scope,
    labels,
    className,
    thumb,
    onApply,
}: {
    active: boolean;
    value: AnalyticsWindow;
    locale: string;
    /** Today in the workspace timezone as `YYYY-MM-DD`, marked in the calendar grid. */
    today: string;
    timezone: string;
    scope: MemberScopeParams;
    labels: CustomRangeLabels;
    className: string;
    thumb: ReactNode;
    onApply: (window: AnalyticsWindow) => void;
}) {
    const [open, setOpen] = useState(false);
    const [draft, setDraft] = useState<AnalyticsWindow>(value);
    const [series, setSeries] = useState<ReadonlyMap<string, number>>(() => new Map());
    const loadedRef = useRef<DateRange | null>(null);
    const requestRef = useRef<AbortController | null>(null);

    const formatters = useMemo(() => ({
        compact: new Intl.DateTimeFormat(locale, { month: 'short', day: 'numeric', timeZone: 'UTC' }),
        full: new Intl.DateTimeFormat(locale, {
            year: 'numeric',
            month: 'short',
            day: 'numeric',
            timeZone: 'UTC',
        }),
    }), [locale]);
    const parsed = useMemo(() => parseCustomAnalyticsWindow(draft.from, draft.to), [draft]);
    const days = rangeDays(draft);
    const activities = useMemo(() => sumSeries(series, draft), [draft, series]);

    useEffect(() => {
        if (open) return;
        loadedRef.current = null;
        requestRef.current?.abort();
        requestRef.current = null;
    }, [open]);

    useEffect(() => () => requestRef.current?.abort(), []);

    const loadSeries = useCallback(
        (visible: DateRange) => {
            const loaded = loadedRef.current;
            if (loaded && loaded.from <= visible.from && loaded.to >= visible.to) return;
            const start = parseDayKey(visible.from);
            const end = parseDayKey(visible.to);
            if (!start || !end) return;
            const window = {
                from: dayKeyOf(addDays(start, -PREFETCH_DAYS)),
                to: dayKeyOf(addDays(end, PREFETCH_DAYS)),
            };
            loadedRef.current = window;
            requestRef.current?.abort();
            const controller = new AbortController();
            requestRef.current = controller;
            Promise.all(
                splitIntoWindows(window, MAX_CUSTOM_RANGE_DAYS).map((chunk) =>
                    getActivityVolume(undefined, scope, { ...chunk, granularity: 'day', timezone }, {
                        signal: controller.signal,
                    }),
                ),
            )
                .then((pages) => {
                    setSeries((current) => {
                        const next = new Map(current);
                        for (const buckets of pages) {
                            for (const bucket of buckets) {
                                if (!bucket.periodStart) continue;
                                next.set(
                                    bucket.periodStart,
                                    bucket.call + bucket.email + bucket.meeting + bucket.note + bucket.other,
                                );
                            }
                        }
                        return next;
                    });
                })
                .catch(() => {
                    loadedRef.current = loaded;
                });
        },
        [scope, timezone],
    );

    const setOpenWithDraft = (next: boolean) => {
        if (next) {
            setDraft(value);
            loadSeries(value);
        }
        setOpen(next);
    };

    return (
        <Popover open={open} onOpenChange={setOpenWithDraft}>
            <PopoverTrigger
                type="button"
                aria-pressed={active}
                aria-label={active ? `${labels.custom}: ${formatWindow(value, formatters.full)}` : labels.custom}
                className={className}
            >
                {active ? thumb : null}
                <span className="relative z-10 inline-flex items-center gap-1.5">
                    <CalendarDaysIcon className="size-3.5 text-muted-foreground" />
                    {active ? formatWindow(value, formatters.compact) : labels.custom}
                </span>
            </PopoverTrigger>
            <PopoverContent align="end" className="w-[min(23rem,calc(100vw-2rem))] sm:w-[38rem]">
                <PopoverTitle className="text-sm font-semibold text-foreground">{labels.title}</PopoverTitle>
                <PopoverDescription className="mt-1 text-xs leading-relaxed text-muted-foreground">
                    {labels.description}
                </PopoverDescription>
                <RangeCalendar
                    className="mt-3"
                    value={draft}
                    onChange={setDraft}
                    locale={locale}
                    today={today}
                    maxDays={MAX_CUSTOM_RANGE_DAYS}
                    series={series}
                    onVisibleRangeChange={loadSeries}
                    labels={{
                        grid: labels.grid,
                        previous: labels.previous,
                        next: labels.next,
                        zoomMonths: labels.zoomMonths,
                        zoomYears: labels.zoomYears,
                    }}
                />
                <div className="mt-3 flex flex-wrap items-end justify-between gap-3 border-t border-border pt-3">
                    <div aria-live="polite">
                        <p className="text-sm font-medium text-foreground">
                            <span aria-label={labels.start}>{formatDay(draft.from, formatters.full)}</span>
                            <span aria-hidden className="px-1 text-muted-foreground">
                                –
                            </span>
                            <span aria-label={labels.end}>{formatDay(draft.to, formatters.full)}</span>
                        </p>
                        <p className="mt-0.5 text-xs text-muted-foreground tabular-nums">
                            {labels.days(days)}
                            {series.size > 0 ? ` · ${labels.activities(activities)}` : ''}
                        </p>
                    </div>
                    <div className="ml-auto flex gap-2">
                        <Button variant="ghost" size="sm" onClick={() => setOpen(false)}>
                            {labels.cancel}
                        </Button>
                        <Button
                            variant="brand"
                            size="sm"
                            disabled={!parsed}
                            onClick={() => {
                                if (!parsed) return;
                                onApply(parsed);
                                setOpen(false);
                            }}
                        >
                            {labels.apply}
                        </Button>
                    </div>
                </div>
            </PopoverContent>
        </Popover>
    );
}
