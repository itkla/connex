'use client';

import { useMemo } from 'react';
import Link from 'next/link';
import { useTranslations } from 'next-intl';
import { ArrowUpRightIcon, UserIcon, BriefcaseIcon, CheckCircleIcon } from '@heroicons/react/24/outline';
import { Loader2Icon } from 'lucide-react';

import {
    Sheet,
    SheetContent,
    SheetHeader,
    SheetTitle,
    SheetDescription,
    SheetFooter,
} from '@/components/ui/sheet';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import type { Contact, Deal, RelationshipTemperature } from '@/app/lib/types';
import { addDays, dayKeyOf, startOfDay, type CalendarEvent } from '@/app/lib/calendar';
import { KIND_ICON, KIND_LABEL_KEY } from './constants';
import { WARMTH_CHIP_CLASS, WARMTH_DOT_CLASS, WARMTH_LABEL_KEY, WARMTH_TREND_KEY } from './warmth';

function linkedIds(event: CalendarEvent): { personId: number | null; dealId: number | null } {
    switch (event.kind) {
        case 'task':
        case 'activity':
            return { personId: event.raw.personId ?? null, dealId: event.raw.dealId ?? null };
        case 'note':
            return { personId: event.raw.person ?? null, dealId: event.raw.deal ?? null };
        case 'deal':
            return { personId: null, dealId: null };
    }
}

/**
 * Bottom-sheet detail for a tapped event. Shows kind, localized date/time, linked
 * contact/deal, a relationship-warmth card (band, trend, last touch, decay) when the
 * linked contact has a temperature, and quick actions: complete (tasks), reschedule chips
 * + a date picker (tasks, open deals) wired to the optimistic reschedule path, and an
 * "open record" link.
 */
export default function EventDetailSheet({
    event,
    open,
    onOpenChange,
    locale,
    personById,
    dealById,
    temperatureByContact,
    currentUserId,
    onReschedule,
    onComplete,
    rescheduling = false,
}: {
    event: CalendarEvent | null;
    open: boolean;
    onOpenChange: (open: boolean) => void;
    locale: string;
    personById: Map<number, Contact>;
    dealById: Map<number, Deal>;
    temperatureByContact: Map<number, RelationshipTemperature>;
    currentUserId: number;
    onReschedule?: (event: CalendarEvent, dayKey: string) => void;
    onComplete?: (event: CalendarEvent) => void;
    rescheduling?: boolean;
}) {
    const t = useTranslations('Calendar');

    const whenLabel = useMemo(() => {
        if (!event) return '';
        const fmt = new Intl.DateTimeFormat(
            locale,
            event.allDay ? { dateStyle: 'full' } : { dateStyle: 'full', timeStyle: 'short' },
        );
        return fmt.format(event.startMs);
    }, [event, locale]);

    if (!event) return null;

    const Icon = KIND_ICON[event.kind];
    const { personId, dealId } = linkedIds(event);
    const person = personId != null ? personById.get(personId) : undefined;
    const deal = dealId != null ? dealById.get(dealId) : undefined;
    const temperature = personId != null ? temperatureByContact.get(personId) : undefined;

    const lastTouchLabel = (() => {
        if (!temperature) return null;
        const days = temperature.daysSinceTouch;
        if (days == null) return t('neverTouched');
        return t('lastTouch', { count: days });
    })();
    const coldLabel = (() => {
        if (!temperature) return null;
        if (temperature.band === 'cold') return t('alreadyCold');
        if (temperature.daysUntilCold == null) return null;
        return t('goesColdIn', { count: temperature.daysUntilCold });
    })();

    const now = startOfDay(new Date());
    const rescheduleChips = [
        { label: t('chipToday'), key: dayKeyOf(now) },
        { label: t('chipTomorrow'), key: dayKeyOf(addDays(now, 1)) },
        { label: t('chipNextWeek'), key: dayKeyOf(addDays(now, 7)) },
    ];

    return (
        <Sheet open={open} onOpenChange={onOpenChange}>
            <SheetContent
                side="bottom"
                onOpenAutoFocus={(e) => {
                    e.preventDefault();
                    (e.currentTarget as HTMLElement | null)?.focus();
                }}
                className="mx-auto max-h-[85vh] gap-0 rounded-t-2xl pb-[max(1rem,env(safe-area-inset-bottom))] sm:max-w-lg"
            >
                <SheetHeader className="gap-2">
                    <Badge variant="secondary" className="w-fit gap-1.5">
                        <Icon className="size-3.5" aria-hidden />
                        {t(KIND_LABEL_KEY[event.kind])}
                    </Badge>
                    <SheetTitle className="text-lg leading-snug">{event.title}</SheetTitle>
                    <SheetDescription className="tabular-nums">{whenLabel}</SheetDescription>
                </SheetHeader>

                {(person || deal) && (
                    <div className="flex flex-col gap-2 px-4 pb-1">
                        {person && (
                            <div className="flex items-center gap-2 text-sm text-foreground">
                                <UserIcon className="size-4 shrink-0 text-muted-foreground" />
                                <span className="truncate">{person.name}</span>
                            </div>
                        )}
                        {deal && (
                            <div className="flex items-center gap-2 text-sm text-foreground">
                                <BriefcaseIcon className="size-4 shrink-0 text-muted-foreground" />
                                <span className="truncate">{deal.name}</span>
                            </div>
                        )}
                    </div>
                )}

                {temperature && person && (
                    <div className="mx-4 mb-1 flex flex-col gap-1.5 rounded-xl bg-muted/40 p-3">
                        <div className="flex items-center justify-between gap-2">
                            <span
                                className={cn(
                                    'inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium',
                                    WARMTH_CHIP_CLASS[temperature.band],
                                )}
                            >
                                <span
                                    className={cn('size-1.5 rounded-full', WARMTH_DOT_CLASS[temperature.band])}
                                    aria-hidden
                                />
                                {t(WARMTH_LABEL_KEY[temperature.band])}
                            </span>
                            <span className="text-xs text-muted-foreground">
                                {t(WARMTH_TREND_KEY[temperature.trend])}
                            </span>
                        </div>
                        {(lastTouchLabel || coldLabel) && (
                            <div className="flex flex-wrap items-center gap-x-2 text-xs text-muted-foreground">
                                {lastTouchLabel && <span>{lastTouchLabel}</span>}
                                {lastTouchLabel && coldLabel && <span aria-hidden>·</span>}
                                {coldLabel && <span>{coldLabel}</span>}
                            </div>
                        )}
                    </div>
                )}

                {event.draggable && onReschedule && (
                    <div className="flex flex-col gap-2 px-4 py-3">
                        <div className="flex items-center justify-between gap-3">
                            <label htmlFor="calendar-reschedule" className="text-sm text-muted-foreground">
                                {t('reschedule')}
                            </label>
                            <div className="flex items-center gap-2">
                                {rescheduling && (
                                    <Loader2Icon className="size-4 animate-spin text-muted-foreground" />
                                )}
                                <input
                                    id="calendar-reschedule"
                                    type="date"
                                    value={event.dayKey}
                                    disabled={rescheduling}
                                    onChange={(e) => {
                                        if (e.target.value) onReschedule(event, e.target.value);
                                    }}
                                    className="rounded-lg border border-input bg-background px-2.5 py-1.5 text-sm tabular-nums text-foreground outline-none focus-visible:ring-2 focus-visible:ring-brand/40 disabled:opacity-60"
                                />
                            </div>
                        </div>
                        <div className="flex flex-wrap gap-1.5">
                            {rescheduleChips.map((chip) => (
                                <button
                                    key={chip.key}
                                    type="button"
                                    disabled={rescheduling || chip.key === event.dayKey}
                                    onClick={() => onReschedule(event, chip.key)}
                                    className="rounded-full bg-muted px-3 py-1 text-xs font-medium text-foreground outline-none ring-1 ring-border transition active:scale-[0.97] hover:bg-background focus-visible:ring-2 focus-visible:ring-brand/40 disabled:pointer-events-none disabled:opacity-40"
                                >
                                    {chip.label}
                                </button>
                            ))}
                        </div>
                    </div>
                )}

                <SheetFooter className="flex-row gap-2">
                    {event.kind === 'task' && onComplete && event.raw.assignedToId === currentUserId && (
                        <Button
                            variant="secondary"
                            className="flex-1"
                            onClick={() => onComplete(event)}
                        >
                            <CheckCircleIcon className="size-4" />
                            {t('markDone')}
                        </Button>
                    )}
                    <Button asChild className="flex-1">
                        <Link href={event.href} onClick={() => onOpenChange(false)}>
                            {t('openRecord')}
                            <ArrowUpRightIcon className="size-4" />
                        </Link>
                    </Button>
                </SheetFooter>
            </SheetContent>
        </Sheet>
    );
}
