'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';

import {
    EVENT_KINDS,
    addDays,
    sameDay,
    startOfDay,
    startOfMonth,
    startOfWeek,
    type CalendarEventKind,
} from '@/app/lib/calendar';

export type CalendarView = 'month' | 'week' | 'day' | 'agenda';

/** 1 = moved forward, -1 = moved back, 0 = jumped (Today / view change) — drives slide direction. */
export type NavDirection = 1 | -1 | 0;

const VIEWS: readonly CalendarView[] = ['month', 'week', 'day', 'agenda'];
const VIEW_STORAGE_KEY = 'calendar:view';
const KINDS_STORAGE_KEY = 'calendar:kinds';

/** 0 = Sunday. Matches the previous calendar and the reference weekday header. */
export const WEEK_STARTS_ON = 0;

function isView(value: unknown): value is CalendarView {
    return typeof value === 'string' && (VIEWS as readonly string[]).includes(value);
}

function clampDayToMonth(year: number, month: number, day: number): Date {
    const lastOfMonth = new Date(year, month + 1, 0).getDate();
    return new Date(year, month, Math.min(day, lastOfMonth));
}

export interface UseCalendar {
    view: CalendarView;
    setView: (view: CalendarView) => void;
    navDirection: NavDirection;
    anchor: Date;
    selectedDay: Date;
    setSelectedDay: (day: Date, direction?: NavDirection) => void;
    goPrev: () => void;
    goNext: () => void;
    goToday: () => void;
    visibleKinds: Set<CalendarEventKind>;
    toggleKind: (kind: CalendarEventKind) => void;
    isKindVisible: (kind: CalendarEventKind) => boolean;
    /** True once localStorage preferences have hydrated — used to defer entrance motion. */
    hydrated: boolean;
}

/**
 * Owns calendar navigation and filter state: the active view, the anchor/selected
 * day per view, and which record kinds are visible. View and kind filters persist
 * to localStorage (hydrated after mount to stay SSR-safe); the anchor always opens
 * on today so the calendar never resumes on a stale month.
 */
export function useCalendar(): UseCalendar {
    const [view, setViewState] = useState<CalendarView>('month');
    const [navDirection, setNavDirection] = useState<NavDirection>(0);
    const [anchor, setAnchor] = useState<Date>(() => startOfDay(new Date()));
    const [selectedDay, setSelectedDayState] = useState<Date>(() => startOfDay(new Date()));
    const [visibleKinds, setVisibleKinds] = useState<Set<CalendarEventKind>>(() => new Set(EVENT_KINDS));
    const [hydrated, setHydrated] = useState(false);

    useEffect(() => {
        // Defer reading persisted prefs to after paint: the first client render must
        // match the server's defaults (SSR-safe), and applying stored values in a rAF
        // callback keeps the setState out of the synchronous effect body.
        const raf = window.requestAnimationFrame(() => {
            try {
                const storedView = window.localStorage.getItem(VIEW_STORAGE_KEY);
                if (isView(storedView)) setViewState(storedView);

                const storedKinds = window.localStorage.getItem(KINDS_STORAGE_KEY);
                if (storedKinds) {
                    const parsed: unknown = JSON.parse(storedKinds);
                    if (Array.isArray(parsed)) {
                        const next = parsed.filter((k): k is CalendarEventKind =>
                            (EVENT_KINDS as readonly string[]).includes(k as string),
                        );
                        if (next.length > 0) setVisibleKinds(new Set(next));
                    }
                }
            } catch {
                // ignore malformed storage; defaults already applied
            }
            setHydrated(true);
        });
        return () => window.cancelAnimationFrame(raf);
    }, []);

    useEffect(() => {
        if (!hydrated) return;
        try {
            window.localStorage.setItem(VIEW_STORAGE_KEY, view);
        } catch {
            // storage may be unavailable (private mode); preference is best-effort
        }
    }, [view, hydrated]);

    useEffect(() => {
        if (!hydrated) return;
        try {
            window.localStorage.setItem(KINDS_STORAGE_KEY, JSON.stringify([...visibleKinds]));
        } catch {
            // best-effort
        }
    }, [visibleKinds, hydrated]);

    const setSelectedDay = useCallback((day: Date, direction: NavDirection = 0) => {
        const normalized = startOfDay(day);
        setNavDirection(direction);
        setSelectedDayState(normalized);
        setAnchor(normalized);
    }, []);

    const setView = useCallback(
        (next: CalendarView) => {
            setNavDirection(0);
            setViewState(next);
            setAnchor((current) => startOfDay(current));
        },
        [],
    );

    const goToday = useCallback(() => {
        const today = startOfDay(new Date());
        setNavDirection(today.getTime() === anchor.getTime() ? 0 : today > anchor ? 1 : -1);
        setAnchor(today);
        setSelectedDayState(today);
    }, [anchor]);

    const step = useCallback(
        (direction: 1 | -1) => {
            setNavDirection(direction);
            if (view === 'month') {
                const monthStart = startOfMonth(anchor);
                const nextMonth = new Date(monthStart.getFullYear(), monthStart.getMonth() + direction, 1);
                const nextSelected = clampDayToMonth(
                    nextMonth.getFullYear(),
                    nextMonth.getMonth(),
                    selectedDay.getDate(),
                );
                setAnchor(nextSelected);
                setSelectedDayState(nextSelected);
            } else if (view === 'week') {
                setAnchor((d) => addDays(d, direction * 7));
                setSelectedDayState((d) => addDays(d, direction * 7));
            } else {
                const next = addDays(anchor, direction);
                setAnchor(next);
                setSelectedDayState(next);
            }
        },
        [view, anchor, selectedDay],
    );

    const goPrev = useCallback(() => step(-1), [step]);
    const goNext = useCallback(() => step(1), [step]);

    const toggleKind = useCallback((kind: CalendarEventKind) => {
        setVisibleKinds((prev) => {
            const next = new Set(prev);
            if (next.has(kind)) {
                if (next.size === 1) return prev;
                next.delete(kind);
            } else {
                next.add(kind);
            }
            return next;
        });
    }, []);

    const isKindVisible = useCallback((kind: CalendarEventKind) => visibleKinds.has(kind), [visibleKinds]);

    return useMemo(
        () => ({
            view,
            setView,
            navDirection,
            anchor,
            selectedDay,
            setSelectedDay,
            goPrev,
            goNext,
            goToday,
            visibleKinds,
            toggleKind,
            isKindVisible,
            hydrated,
        }),
        [
            view,
            setView,
            navDirection,
            anchor,
            selectedDay,
            setSelectedDay,
            goPrev,
            goNext,
            goToday,
            visibleKinds,
            toggleKind,
            isKindVisible,
            hydrated,
        ],
    );
}

/** Guards against toggling off the last visible kind — used to disable the last active filter chip. */
export function isOnlyVisibleKind(visibleKinds: Set<CalendarEventKind>, kind: CalendarEventKind): boolean {
    return visibleKinds.size === 1 && visibleKinds.has(kind);
}

export function allKindsVisible(visibleKinds: Set<CalendarEventKind>): boolean {
    return visibleKinds.size === EVENT_KINDS.length;
}

export { EVENT_KINDS, sameDay, startOfWeek };
