'use client';

import { useMemo, useState } from 'react';
import { useTranslations } from 'next-intl';
import { ChevronDownIcon, CalendarDaysIcon } from '@heroicons/react/24/outline';

import { cn } from '@/lib/utils';
import { dayKeyOf, groupByDay, type CalendarEvent } from '@/app/lib/calendar';
import type { RelationshipTemperature } from '@/app/lib/types';
import EventChip from './EventChip';
import { WARMTH_LABEL_KEY, isAtRisk, warmthContactId } from './warmth';

interface DayGroup {
    key: string;
    date: Date;
    events: CalendarEvent[];
}

function toGroups(byDay: Map<string, CalendarEvent[]>, keys: string[]): DayGroup[] {
    return keys.map((key) => {
        const events = byDay.get(key) ?? [];
        return { key, date: new Date(events[0]?.startMs ?? Date.parse(key)), events };
    });
}

/**
 * Chronological list grouped by day: an Upcoming section (today onward, ascending)
 * and a collapsible Past section (before today, most-recent first). Sticky day
 * headers; each row opens the detail sheet.
 */
export default function AgendaView({
    events,
    today,
    locale,
    temperatureByContact,
    onOpenEvent,
}: {
    events: CalendarEvent[];
    today: Date;
    locale: string;
    temperatureByContact: Map<number, RelationshipTemperature>;
    onOpenEvent: (event: CalendarEvent, anchor: HTMLElement | null) => void;
}) {
    const t = useTranslations('Calendar');
    const [pastOpen, setPastOpen] = useState(false);

    const dateFmt = useMemo(
        () => new Intl.DateTimeFormat(locale, { weekday: 'long', month: 'long', day: 'numeric' }),
        [locale],
    );
    const timeFmt = useMemo(
        () => new Intl.DateTimeFormat(locale, { hour: 'numeric', minute: '2-digit' }),
        [locale],
    );

    const { upcoming, past } = useMemo(() => {
        const byDay = groupByDay(events);
        const keys = [...byDay.keys()].sort();
        const todayKey = dayKeyOf(today);
        const upcomingKeys = keys.filter((k) => k >= todayKey);
        const pastKeys = keys.filter((k) => k < todayKey).reverse();
        return { upcoming: toGroups(byDay, upcomingKeys), past: toGroups(byDay, pastKeys) };
    }, [events, today]);

    const todayKey = dayKeyOf(today);

    const renderGroup = (group: DayGroup) => (
        <section key={group.key} className="flex flex-col">
            <header className="sticky top-0 z-10 flex items-baseline gap-2 bg-background/95 py-1.5 backdrop-blur-sm">
                <h3 className="text-sm font-semibold text-foreground">
                    {group.key === todayKey ? t('today') : dateFmt.format(group.date)}
                </h3>
                {group.key === todayKey && (
                    <span className="text-xs text-muted-foreground">{dateFmt.format(group.date)}</span>
                )}
            </header>
            <ul className="flex flex-col gap-0.5 pb-2">
                {group.events.map((event) => {
                    const contactId = warmthContactId(event);
                    const temp = contactId != null ? temperatureByContact.get(contactId) : undefined;
                    const atRisk = temp && isAtRisk(temp.band) ? temp.band : undefined;
                    return (
                        <li key={event.id}>
                            <EventChip
                                event={event}
                                variant="row"
                                timeLabel={event.allDay ? undefined : timeFmt.format(event.startMs)}
                                warmthBand={atRisk}
                                warmthLabel={atRisk ? t(WARMTH_LABEL_KEY[atRisk]) : undefined}
                                onClick={(clicked) => onOpenEvent(event, clicked.currentTarget)}
                            />
                        </li>
                    );
                })}
            </ul>
        </section>
    );

    if (upcoming.length === 0 && past.length === 0) {
        return (
            <div className="mx-auto flex max-w-sm flex-col items-center gap-2 rounded-2xl border border-dashed border-border px-6 py-16 text-center">
                <div className="grid size-11 place-items-center rounded-full bg-muted text-muted-foreground">
                    <CalendarDaysIcon className="size-5" aria-hidden />
                </div>
                <p className="text-sm font-medium text-foreground">{t('emptyPeriod')}</p>
                <p className="text-xs text-muted-foreground">{t('emptyPeriodHint')}</p>
            </div>
        );
    }

    return (
        <div className="mx-auto flex w-full max-w-2xl flex-col gap-4">
            {upcoming.length > 0 ? (
                <div className="flex flex-col">{upcoming.map(renderGroup)}</div>
            ) : (
                <p className="py-6 text-center text-sm text-muted-foreground">{t('noUpcoming')}</p>
            )}

            {past.length > 0 && (
                <div className="flex flex-col">
                    <button
                        type="button"
                        onClick={() => setPastOpen((v) => !v)}
                        aria-expanded={pastOpen}
                        className="flex items-center gap-1.5 self-start rounded-full px-2 py-1 text-xs font-medium uppercase tracking-wide text-muted-foreground outline-none transition-colors hover:text-foreground focus-visible:ring-2 focus-visible:ring-brand/40"
                    >
                        <ChevronDownIcon
                            className={cn('size-4 transition-transform', pastOpen ? 'rotate-180' : 'rotate-0')}
                        />
                        {t('past')}
                    </button>
                    {pastOpen && <div className="mt-2 flex flex-col">{past.map(renderGroup)}</div>}
                </div>
            )}
        </div>
    );
}
