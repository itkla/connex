'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import { ChevronLeftIcon, ChevronRightIcon } from '@heroicons/react/16/solid';

import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { useIsMobile } from '@/app/hooks/useIsMobile';
import { addDays, addMonths, dayKeyOf, startOfMonth, startOfWeek } from '@/app/lib/calendar';
import { easeOut, instant } from '@/app/lib/motion';
import {
    YEAR_PAGE_SIZE,
    bandCaps,
    cellSeries,
    monthCellsForYear,
    parseDayKey,
    periodOverlaps,
    periodRange,
    spanPeriods,
    withinSpanLimit,
    yearCellsForPage,
    yearPageStart,
    type CalendarZoom,
    type DateRange,
    type DaySeries,
} from '@/app/lib/rangeCalendar';

/** Accessible names the range calendar cannot derive from `Intl`. */
export interface RangeCalendarLabels {
    /** Name of the calendar as a whole, e.g. "Date range". */
    grid: string;
    /** Name of the control that steps the view back one page. */
    previous: string;
    /** Name of the control that steps the view forward one page. */
    next: string;
    /** Name of the caption control that zooms out to the month grid. */
    zoomMonths: string;
    /** Name of the caption control that zooms out to the year grid. */
    zoomYears: string;
}

interface CellLayout {
    value: string;
    label: string;
    description: string;
    bars: number[];
    muted: boolean;
    today: boolean;
    date: Date;
}

interface CalendarCell extends CellLayout {
    member: boolean;
    selectable: boolean;
    endpoint: boolean;
}

interface ViewMotion {
    offset: number;
    scaleIn: number;
    scaleOut: number;
    blur: number;
    duration: number;
}

interface Draft {
    zoom: CalendarZoom;
    anchor: string;
    cursor: string;
}

const DAY_COLUMNS = 7;
const PERIOD_COLUMNS = 4;
const DAY_CELLS = 42;
const WEEK_STARTS_ON = 0;
const EMPTY_SERIES: DaySeries = new Map();

const STILL: ViewMotion = { offset: 0, scaleIn: 1, scaleOut: 1, blur: 0, duration: 0.16 };

const viewVariants = {
    enter: (spec: ViewMotion) => ({
        opacity: 0,
        x: spec.offset,
        scale: spec.scaleIn,
        filter: `blur(${spec.blur}px)`,
    }),
    center: { opacity: 1, x: 0, scale: 1, filter: 'blur(0px)' },
    exit: (spec: ViewMotion) => ({
        opacity: 0,
        x: -spec.offset,
        scale: spec.scaleOut,
        filter: `blur(${spec.blur}px)`,
    }),
};

/** The 42 cells of a fixed six-week month grid, so panel height never changes between months. */
function dayCells(monthStart: Date): Date[] {
    const start = startOfWeek(monthStart, WEEK_STARTS_ON);
    return Array.from({ length: DAY_CELLS }, (_, index) => addDays(start, index));
}

function cellValueAt(x: number, y: number): string | null {
    const target = document.elementFromPoint(x, y);
    if (!(target instanceof Element)) return null;
    const cell = target.closest<HTMLElement>('[data-cell-value]');
    if (!cell || cell.dataset.cellSelectable !== 'true') return null;
    return cell.dataset.cellValue ?? null;
}

/**
 * Five-step tints for a day cell, quantised against the tallest day in view. Outside a selection a
 * day reads as neutral texture showing where the workspace was busy; inside one it takes the brand
 * hue at the same intensity, so sweeping out a range blooms it without flattening its shape. Alphas
 * stay low enough that the cell's numeral keeps its contrast in both themes.
 */
const IDLE_HEAT = ['', 'bg-foreground/4', 'bg-foreground/7', 'bg-foreground/10', 'bg-foreground/13'];
const MEMBER_HEAT = [
    'bg-brand/12 dark:bg-brand/16',
    'bg-brand/20 dark:bg-brand/22',
    'bg-brand/30 dark:bg-brand/28',
    'bg-brand/40 dark:bg-brand/34',
    'bg-brand/50 dark:bg-brand/40',
];

/** Bucket of `value` on a 0-4 scale, where 0 means the day carried nothing at all. */
function heatLevel(value: number, peak: number): number {
    if (peak <= 0 || value <= 0) return 0;
    return Math.min(4, Math.ceil((value / peak) * 4));
}

/**
 * A period's magnitudes as a bar per sub-period: one bar per day inside a month cell, one bar per
 * month inside a year cell. Bars are inert texture until the cell falls inside the selection, at
 * which point they take the brand colour, so sweeping out a range lights up the data it contains.
 */
function Sparkline({
    values,
    peak,
    lit,
    className,
}: {
    values: number[];
    peak: number;
    lit: boolean;
    className: string;
}) {
    return (
        <span
            aria-hidden
            className={cn(
                'flex w-full items-end gap-px border-b',
                lit ? 'border-brand/40' : 'border-border',
                className,
            )}
        >
            {peak > 0
                ? values.map((value, index) => (
                    <span
                        key={index}
                        className={cn(
                            'min-w-0 flex-1 rounded-t-[1px] transition-colors duration-150',
                            value === 0 ? 'opacity-0' : lit ? 'bg-brand-dark dark:bg-brand' : 'bg-foreground/20',
                        )}
                        style={{ height: `${Math.max(6, Math.round((value / peak) * 100))}%` }}
                    />
                ))
                : null}
        </span>
    );
}

/**
 * A range calendar that reads at three magnifications and plots the caller's daily series at each
 * one: a day cell carries its own bar, a month cell one bar per day, a year cell one bar per
 * month. Picking a period is therefore an informed choice rather than a guess at where the data is.
 *
 * Every zoom selects by the same gesture: press to anchor, sweep to extend, release to commit. At
 * day zoom a press and release on a single cell leaves the range open so the second endpoint can be
 * clicked rather than dragged; at month and year zoom a single cell is already an unambiguous
 * range, so it commits on release.
 *
 * Selection is capped at `maxDays`. While a range is open, cells that would push it past the cap
 * stop responding, which turns a validation error into something the user cannot reach.
 */
export function RangeCalendar({
    value,
    onChange,
    locale,
    labels,
    maxDays,
    today,
    series = EMPTY_SERIES,
    onVisibleRangeChange,
    className,
}: {
    value: DateRange;
    onChange: (range: DateRange) => void;
    locale: string;
    labels: RangeCalendarLabels;
    maxDays: number;
    /** Today in the consumer's timezone as `YYYY-MM-DD`; marked in the grid when visible. */
    today?: string;
    /** Daily magnitudes plotted behind the grid. Omit for a calendar with no data layer. */
    series?: DaySeries;
    /** Fires with the span the grid is showing, so the caller can load `series` to cover it. */
    onVisibleRangeChange?: (range: DateRange) => void;
    className?: string;
}) {
    const reduce = useReducedMotion() ?? false;
    const isMobile = useIsMobile();
    const panelCount = isMobile ? 1 : 2;

    const [zoom, setZoom] = useState<CalendarZoom>('day');
    const [anchorMonth, setAnchorMonth] = useState<Date>(
        () => startOfMonth(parseDayKey(value.from) ?? new Date()),
    );
    const [draft, setDraft] = useState<Draft | null>(null);
    const [focusValue, setFocusValue] = useState<string>(value.from);
    const [nav, setNav] = useState<ViewMotion>(STILL);

    const gridRef = useRef<HTMLDivElement>(null);
    const gestureRef = useRef<(Draft & { id: number; committing: boolean; moved: boolean }) | null>(null);
    const suppressClickRef = useRef(false);
    const keyboardRef = useRef(false);

    const rangeOf = useCallback(
        (candidate: Draft | null): DateRange => {
            if (!candidate) return value;
            const anchor = parseDayKey(candidate.anchor);
            const cursor = parseDayKey(candidate.cursor);
            if (!anchor || !cursor) return value;
            return spanPeriods(candidate.zoom, anchor, cursor);
        },
        [value],
    );

    const preview = useMemo(() => rangeOf(draft), [draft, rangeOf]);
    const spanAnchor = useMemo(() => (draft ? parseDayKey(draft.anchor) : null), [draft]);

    const formatters = useMemo(() => ({
        weekday: new Intl.DateTimeFormat(locale, { weekday: 'narrow' }),
        weekdayFull: new Intl.DateTimeFormat(locale, { weekday: 'long' }),
        month: new Intl.DateTimeFormat(locale, { month: 'long' }),
        monthShort: new Intl.DateTimeFormat(locale, { month: 'short' }),
        monthLong: new Intl.DateTimeFormat(locale, { year: 'numeric', month: 'long' }),
        year: new Intl.DateTimeFormat(locale, { year: 'numeric' }),
        day: new Intl.DateTimeFormat(locale, { day: 'numeric' }),
        full: new Intl.DateTimeFormat(locale, { year: 'numeric', month: 'long', day: 'numeric' }),
    }), [locale]);

    const layout = useMemo(() => {
        const build = (
            cellZoom: CalendarZoom,
            date: Date,
            label: string,
            description: string,
            muted: boolean,
        ): CellLayout => {
            const period = periodRange(cellZoom, date);
            return {
                value: period.from,
                label,
                description,
                bars: cellSeries(cellZoom, date, series),
                muted,
                today: today != null && today >= period.from && today <= period.to,
                date,
            };
        };
        if (zoom === 'day') {
            return Array.from({ length: panelCount }, (_, index) => {
                const monthStart = addMonths(anchorMonth, index);
                return {
                    key: dayKeyOf(monthStart),
                    caption: formatters.month.format(monthStart),
                    year: formatters.year.format(monthStart),
                    cells: dayCells(monthStart).map((date) =>
                        build(
                            'day',
                            date,
                            formatters.day.format(date),
                            formatters.full.format(date),
                            date.getMonth() !== monthStart.getMonth(),
                        ),
                    ),
                };
            });
        }
        if (zoom === 'month') {
            return [{
                key: `m${anchorMonth.getFullYear()}`,
                caption: '',
                year: formatters.year.format(anchorMonth),
                cells: monthCellsForYear(anchorMonth).map((date) =>
                    build('month', date, formatters.monthShort.format(date), formatters.monthLong.format(date), false),
                ),
            }];
        }
        const pageStart = yearPageStart(anchorMonth);
        return [{
            key: `y${pageStart.getFullYear()}`,
            caption: '',
            year: `${pageStart.getFullYear()} - ${pageStart.getFullYear() + YEAR_PAGE_SIZE - 1}`,
            cells: yearCellsForPage(anchorMonth).map((date) =>
                build('year', date, formatters.year.format(date), formatters.year.format(date), false),
            ),
        }];
    }, [anchorMonth, formatters, panelCount, series, today, zoom]);

    const peak = useMemo(
        () =>
            layout.reduce(
                (max, panel) =>
                    panel.cells.reduce(
                        (cellMax, cell) => cell.bars.reduce((barMax, bar) => Math.max(barMax, bar), cellMax),
                        max,
                    ),
                0,
            ),
        [layout],
    );

    const panels = useMemo(
        () =>
            layout.map((panel) => ({
                ...panel,
                cells: panel.cells.map((cell): CalendarCell => {
                    const period = periodRange(zoom, cell.date);
                    const member = periodOverlaps(zoom, cell.date, preview);
                    const reachable = spanAnchor
                        ? withinSpanLimit(zoom, spanAnchor, cell.date, maxDays)
                        : true;
                    return {
                        ...cell,
                        member,
                        selectable: reachable && !cell.muted,
                        endpoint: member && (period.from <= preview.from || period.to >= preview.to),
                    };
                }),
            })),
        [layout, maxDays, preview, spanAnchor, zoom],
    );

    const columns = zoom === 'day' ? DAY_COLUMNS : PERIOD_COLUMNS;
    const viewKey = `${zoom}:${panels.map((panel) => panel.key).join('|')}`;
    const selectableCells = panels.flatMap((panel) => panel.cells).filter((cell) => !cell.muted);
    const tabValue = selectableCells.some((cell) => cell.value === focusValue)
        ? focusValue
        : (selectableCells[0]?.value ?? focusValue);

    const visibleFrom = layout[0]?.cells[0]?.value;
    const visibleTo = useMemo(() => {
        const last = layout.at(-1)?.cells.at(-1);
        return last ? periodRange(zoom, last.date).to : undefined;
    }, [layout, zoom]);

    const visibleRangeRef = useRef(onVisibleRangeChange);
    useEffect(() => {
        visibleRangeRef.current = onVisibleRangeChange;
    });
    useEffect(() => {
        if (visibleFrom && visibleTo) visibleRangeRef.current?.({ from: visibleFrom, to: visibleTo });
    }, [visibleFrom, visibleTo]);

    const weekdays = useMemo(() => {
        const base = startOfWeek(new Date(2026, 0, 4), WEEK_STARTS_ON);
        return Array.from({ length: DAY_COLUMNS }, (_, index) => {
            const date = addDays(base, index);
            return { short: formatters.weekday.format(date), full: formatters.weekdayFull.format(date) };
        });
    }, [formatters]);

    const pageBy = useCallback(
        (delta: number, current: Date): Date => {
            if (zoom === 'day') return addMonths(current, delta);
            if (zoom === 'month') return new Date(current.getFullYear() + delta, 0, 1);
            return new Date(yearPageStart(current).getFullYear() + delta * YEAR_PAGE_SIZE, 0, 1);
        },
        [zoom],
    );

    const step = useCallback(
        (delta: number, silent: boolean) => {
            setNav(
                reduce || silent
                    ? STILL
                    : { offset: delta * 20, scaleIn: 1, scaleOut: 1, blur: 3, duration: 0.2 },
            );
            setAnchorMonth((current) => pageBy(delta, current));
        },
        [pageBy, reduce],
    );

    const changeZoom = useCallback(
        (next: CalendarZoom, outward: boolean) => {
            setNav(
                reduce
                    ? STILL
                    : {
                        offset: 0,
                        scaleIn: outward ? 1.1 : 0.92,
                        scaleOut: outward ? 0.92 : 1.1,
                        blur: 6,
                        duration: 0.28,
                    },
            );
            setZoom(next);
            setDraft(null);
        },
        [reduce],
    );

    const commit = useCallback(
        (range: DateRange) => {
            setDraft(null);
            onChange(range);
            setFocusValue(range.from);
            if (zoom !== 'day') {
                const start = parseDayKey(range.from);
                if (start) setAnchorMonth(startOfMonth(start));
                changeZoom('day', false);
            }
        },
        [changeZoom, onChange, zoom],
    );

    const activate = useCallback(
        (cellValue: string) => {
            const date = parseDayKey(cellValue);
            if (!date) return;
            if (draft && draft.zoom === zoom) {
                const anchor = parseDayKey(draft.anchor);
                if (anchor) commit(spanPeriods(zoom, anchor, date));
                return;
            }
            if (zoom === 'day') {
                setDraft({ zoom, anchor: cellValue, cursor: cellValue });
                setFocusValue(cellValue);
                return;
            }
            commit(periodRange(zoom, date));
        },
        [commit, draft, zoom],
    );

    const onPointerDown = (event: React.PointerEvent<HTMLDivElement>) => {
        suppressClickRef.current = false;
        if (gestureRef.current || (event.pointerType === 'mouse' && event.button !== 0)) return;
        const cellValue = cellValueAt(event.clientX, event.clientY);
        if (!cellValue) return;
        keyboardRef.current = false;
        const extending = draft != null && draft.zoom === zoom;
        const next: Draft = extending && draft
            ? { ...draft, cursor: cellValue }
            : { zoom, anchor: cellValue, cursor: cellValue };
        gestureRef.current = { ...next, id: event.pointerId, committing: extending, moved: false };
        gridRef.current?.setPointerCapture(event.pointerId);
        setDraft(next);
        setFocusValue(cellValue);
        if (event.pointerType === 'touch') {
            gridRef.current
                ?.querySelector<HTMLElement>(`[data-cell-value="${cellValue}"][data-cell-selectable="true"]`)
                ?.focus({ preventScroll: true });
        }
    };

    const onPointerMove = (event: React.PointerEvent<HTMLDivElement>) => {
        const gesture = gestureRef.current;
        if (gesture && gesture.id !== event.pointerId) return;
        if (!gesture && (!draft || event.pointerType !== 'mouse')) return;
        const cellValue = cellValueAt(event.clientX, event.clientY);
        if (!cellValue) return;
        if (gesture) {
            if (gesture.cursor === cellValue) return;
            gesture.cursor = cellValue;
            gesture.moved = true;
        }
        setDraft((current) =>
            current && current.cursor !== cellValue ? { ...current, cursor: cellValue } : current,
        );
    };

    const onPointerUp = (event: React.PointerEvent<HTMLDivElement>) => {
        const gesture = gestureRef.current;
        if (!gesture || gesture.id !== event.pointerId) return;
        gestureRef.current = null;
        suppressClickRef.current = true;
        if (gridRef.current?.hasPointerCapture(event.pointerId)) {
            gridRef.current.releasePointerCapture(event.pointerId);
        }
        if (gesture.committing || gesture.moved || gesture.zoom !== 'day') {
            commit(rangeOf({ zoom: gesture.zoom, anchor: gesture.anchor, cursor: gesture.cursor }));
        }
    };

    const onCellClick = (cellValue: string) => {
        if (suppressClickRef.current) {
            suppressClickRef.current = false;
            return;
        }
        activate(cellValue);
    };

    const moveFocus = (delta: number) => {
        const source = parseDayKey(tabValue);
        if (!source) return;
        const next =
            zoom === 'day'
                ? addDays(source, delta)
                : zoom === 'month'
                    ? new Date(source.getFullYear(), source.getMonth() + delta, 1)
                    : new Date(source.getFullYear() + delta, 0, 1);
        const nextValue = dayKeyOf(next);
        setFocusValue(nextValue);
        setDraft((current) =>
            current && current.zoom === zoom ? { ...current, cursor: nextValue } : current,
        );
        const onScreen = panels.some((panel) =>
            panel.cells.some((cell) => cell.value === nextValue && !cell.muted),
        );
        if (onScreen) return;
        setNav(STILL);
        setAnchorMonth((current) => pageBy(delta > 0 ? 1 : -1, current));
    };

    const onKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
        if (event.key === 'Escape' && draft) {
            event.stopPropagation();
            setDraft(null);
            return;
        }
        if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            keyboardRef.current = true;
            activate(tabValue);
            return;
        }
        const moves: Record<string, number> = {
            ArrowLeft: -1,
            ArrowRight: 1,
            ArrowUp: -columns,
            ArrowDown: columns,
        };
        const delta = moves[event.key];
        if (delta != null) {
            event.preventDefault();
            keyboardRef.current = true;
            moveFocus(delta);
            return;
        }
        if (event.key === 'PageUp' || event.key === 'PageDown') {
            event.preventDefault();
            keyboardRef.current = true;
            step(event.key === 'PageUp' ? -1 : 1, true);
        }
    };

    useEffect(() => {
        if (!keyboardRef.current) return;
        gridRef.current?.querySelector<HTMLElement>('[data-cell-value][tabindex="0"]')?.focus();
    }, [tabValue, panels]);

    const transition = reduce ? instant : { duration: nav.duration, ease: easeOut };

    return (
        <div className={cn('relative select-none', className)} role="group" aria-label={labels.grid}>
            <div className="absolute top-0 right-0 z-10 flex gap-0.5">
                <Button
                    type="button"
                    variant="ghost"
                    size="icon-sm"
                    aria-label={labels.previous}
                    onClick={() => step(-1, false)}
                >
                    <ChevronLeftIcon className="size-4" />
                </Button>
                <Button
                    type="button"
                    variant="ghost"
                    size="icon-sm"
                    aria-label={labels.next}
                    onClick={() => step(1, false)}
                >
                    <ChevronRightIcon className="size-4" />
                </Button>
            </div>
            <div
                ref={gridRef}
                className="relative h-80 touch-none overflow-hidden"
                onPointerDown={onPointerDown}
                onPointerMove={onPointerMove}
                onPointerUp={onPointerUp}
                onPointerCancel={onPointerUp}
                onKeyDown={onKeyDown}
                onPointerLeave={() => {
                    if (gestureRef.current) return;
                    setDraft((current) =>
                        current && current.cursor !== current.anchor
                            ? { ...current, cursor: current.anchor }
                            : current,
                    );
                }}
            >
                <AnimatePresence custom={nav} initial={false} mode="popLayout">
                    <motion.div
                        key={viewKey}
                        custom={nav}
                        variants={viewVariants}
                        initial="enter"
                        animate="center"
                        exit="exit"
                        transition={transition}
                        className={cn(
                            'grid gap-x-7',
                            zoom === 'day' && panelCount === 2 ? 'grid-cols-2' : 'grid-cols-1',
                        )}
                    >
                        {panels.map((panel) => (
                            <div key={panel.key}>
                                <div className="flex h-8 items-center gap-1.5">
                                    {panel.caption ? (
                                        <button
                                            type="button"
                                            aria-label={labels.zoomMonths}
                                            className="-mx-1 rounded-md px-1 text-[0.9375rem] font-semibold tracking-tight text-foreground transition-colors duration-150 hover:text-brand-dark focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none dark:hover:text-brand"
                                            onClick={() => changeZoom('month', true)}
                                        >
                                            {panel.caption}
                                        </button>
                                    ) : null}
                                    <button
                                        type="button"
                                        aria-label={labels.zoomYears}
                                        disabled={zoom === 'year'}
                                        className="-mx-1 rounded-md px-1 text-[0.9375rem] tracking-tight text-muted-foreground tabular-nums transition-colors duration-150 hover:text-brand-dark focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none disabled:pointer-events-none dark:hover:text-brand"
                                        onClick={() => changeZoom('year', true)}
                                    >
                                        {panel.year}
                                    </button>
                                </div>
                                <div role="grid" aria-label={`${panel.caption} ${panel.year}`.trim()}>
                                    {zoom === 'day' ? (
                                        <div role="row" className="grid h-6 grid-cols-7 items-center">
                                            {weekdays.map((weekday) => (
                                                <abbr
                                                    key={weekday.full}
                                                    title={weekday.full}
                                                    className="text-center text-[0.625rem] font-medium text-muted-foreground/70 no-underline"
                                                >
                                                    {weekday.short}
                                                </abbr>
                                            ))}
                                        </div>
                                    ) : null}
                                    <CellGrid
                                        cells={panel.cells}
                                        columns={columns}
                                        peak={peak}
                                        tall={zoom !== 'day'}
                                        tabValue={tabValue}
                                        onActivate={onCellClick}
                                        onFocusCell={setFocusValue}
                                    />
                                </div>
                            </div>
                        ))}
                    </motion.div>
                </AnimatePresence>
            </div>
        </div>
    );
}

function CellGrid({
    cells,
    columns,
    peak,
    tall,
    tabValue,
    onActivate,
    onFocusCell,
}: {
    cells: CalendarCell[];
    columns: number;
    peak: number;
    tall: boolean;
    tabValue: string;
    onActivate: (value: string) => void;
    onFocusCell: (value: string) => void;
}) {
    const member = cells.map((cell) => cell.member);
    const rows = Array.from({ length: Math.ceil(cells.length / columns) }, (_, row) =>
        cells.slice(row * columns, row * columns + columns),
    );

    return (
        <div className={cn('grid', columns === DAY_COLUMNS ? 'grid-cols-7' : 'grid-cols-4')}>
            {rows.map((row, rowIndex) => (
                <div key={row[0].value} role="row" className="col-span-full grid grid-cols-subgrid">
                    {row.map((cell, columnIndex) => {
                        const caps = bandCaps(member, rowIndex * columns + columnIndex, columns);
                        const level = heatLevel(cell.bars[0] ?? 0, peak);
                        return (
                            <div
                                key={cell.value}
                                role="gridcell"
                                aria-selected={cell.member}
                                className={cn('relative flex items-center', tall ? 'h-24' : 'h-11')}
                            >
                                {cell.member || (!tall && !cell.muted && level > 0) ? (
                                    <span
                                        aria-hidden
                                        className={cn(
                                            'pointer-events-none absolute inset-x-0 inset-y-0.5 transition-colors duration-150',
                                            cell.member ? MEMBER_HEAT[level] : IDLE_HEAT[level],
                                            caps.start && 'rounded-l-lg',
                                            caps.end && 'rounded-r-lg',
                                            !cell.member && 'rounded-md',
                                        )}
                                    />
                                ) : null}
                                <button
                                    type="button"
                                    data-cell-value={cell.value}
                                    data-cell-selectable={cell.selectable}
                                    tabIndex={cell.value === tabValue && !cell.muted ? 0 : -1}
                                    aria-disabled={!cell.selectable}
                                    aria-label={cell.description}
                                    onFocus={() => (cell.muted ? undefined : onFocusCell(cell.value))}
                                    onClick={() => (cell.selectable ? onActivate(cell.value) : undefined)}
                                    className={cn(
                                        'group relative flex h-full w-full rounded-md focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset focus-visible:outline-none',
                                        tall
                                            ? 'mx-0.5 my-1 flex-col justify-start gap-1.5 px-2 py-2'
                                            : 'items-center justify-center',
                                        !cell.selectable && !cell.muted && 'cursor-default opacity-30',
                                    )}
                                >
                                    <span className={cn('relative flex h-6 items-center', tall ? 'self-start px-1.5' : 'px-2')}>
                                        {cell.endpoint ? (
                                            <span
                                                aria-hidden
                                                className={cn(
                                                    'pointer-events-none absolute inset-y-0 rounded-full bg-brand',
                                                    tall ? 'inset-x-0' : 'left-1/2 aspect-square -translate-x-1/2',
                                                )}
                                            />
                                        ) : null}
                                        <span
                                            className={cn(
                                                'relative text-[0.8125rem] tabular-nums transition-colors duration-150',
                                                cell.endpoint
                                                    ? 'font-semibold text-brand-foreground'
                                                    : cell.muted
                                                        ? 'text-muted-foreground/40'
                                                        : cell.today
                                                            ? 'font-semibold text-brand-dark dark:text-brand'
                                                            : 'text-foreground',
                                                cell.selectable && !cell.endpoint && 'group-hover:text-brand-dark dark:group-hover:text-brand',
                                            )}
                                        >
                                            {cell.label}
                                        </span>
                                    </span>
                                    {tall ? (
                                        <Sparkline
                                            values={cell.bars}
                                            peak={peak}
                                            lit={cell.member}
                                            className="h-10"
                                        />
                                    ) : null}
                                </button>
                            </div>
                        );
                    })}
                </div>
            ))}
        </div>
    );
}
