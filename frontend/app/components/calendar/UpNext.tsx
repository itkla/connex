'use client';

import { useMemo } from 'react';
import { useTranslations } from 'next-intl';
import { ChevronRightIcon } from '@heroicons/react/24/outline';

import { cn } from '@/lib/utils';
import Rise from '@/app/components/motion/Rise';
import { useLiveNow } from '@/app/hooks/useNow';
import { addDays, dayKeyOf, type CalendarEvent } from '@/app/lib/calendar';
import { KIND_CHIP_CLASS, KIND_ICON } from './constants';

/**
 * Answer-forward "up next" strip: the soonest event from now onward, with a live relative
 * time. Reads the shared clock so the server render and hydration agree, then ticks each
 * minute. Renders nothing when there is nothing upcoming. Tapping it opens the event.
 */
export default function UpNext({
    events,
    locale,
    onOpenEvent,
}: {
    events: CalendarEvent[];
    locale: string;
    onOpenEvent: (event: CalendarEvent) => void;
}) {
    const t = useTranslations('Calendar');
    const now = useLiveNow();

    const rtf = useMemo(() => new Intl.RelativeTimeFormat(locale, { numeric: 'auto' }), [locale]);
    const dayTimeFmt = useMemo(
        () => new Intl.DateTimeFormat(locale, { weekday: 'short', hour: 'numeric', minute: '2-digit' }),
        [locale],
    );
    const dayFmt = useMemo(() => new Intl.DateTimeFormat(locale, { weekday: 'long', day: 'numeric' }), [locale]);

    const next = useMemo(() => {
        let best: CalendarEvent | null = null;
        let bestEff = Infinity;
        for (const e of events) {
            const eff = e.allDay ? e.startMs + 86_399_999 : e.startMs;
            if (eff < now) continue;
            if (eff < bestEff) {
                best = e;
                bestEff = eff;
            }
        }
        return best;
    }, [events, now]);

    if (!next) return null;

    const label = (() => {
        const diffMin = Math.round((next.startMs - now) / 60_000);
        if (!next.allDay && diffMin < 180) {
            if (diffMin < 1) return t('now');
            if (diffMin < 60) return rtf.format(diffMin, 'minute');
            return rtf.format(Math.round(diffMin / 60), 'hour');
        }
        const key = dayKeyOf(new Date(next.startMs));
        const nowDate = new Date(now);
        const todayKey = dayKeyOf(nowDate);
        const tomorrowKey = dayKeyOf(addDays(nowDate, 1));
        const day = key === todayKey ? t('today') : key === tomorrowKey ? t('tomorrow') : null;
        if (next.allDay) return day ?? dayFmt.format(next.startMs);
        const time = new Intl.DateTimeFormat(locale, { hour: 'numeric', minute: '2-digit' }).format(next.startMs);
        return day ? `${day}, ${time}` : dayTimeFmt.format(next.startMs);
    })();

    const Icon = KIND_ICON[next.kind];

    return (
        <Rise>
        <button
            type="button"
            onClick={() => onOpenEvent(next)}
            className="group flex w-full items-center gap-3 rounded-2xl border border-border bg-card px-3 py-2.5 text-left outline-none transition-colors hover:border-brand/30 focus-visible:ring-2 focus-visible:ring-brand/40"
        >
            <span className={cn('grid size-9 shrink-0 place-items-center rounded-full', KIND_CHIP_CLASS[next.kind])}>
                <Icon className="size-4" aria-hidden />
            </span>
            <span className="min-w-0 flex-1">
                <span className="block text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
                    {t('upNext')} · {label}
                </span>
                <span className="block truncate text-sm font-semibold text-foreground">{next.title}</span>
            </span>
            <ChevronRightIcon
                className="size-4 shrink-0 text-muted-foreground/60 transition-colors group-hover:text-muted-foreground"
                aria-hidden
            />
        </button>
        </Rise>
    );
}
