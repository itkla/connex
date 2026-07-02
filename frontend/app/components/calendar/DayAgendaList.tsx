'use client';

import { useMemo } from 'react';
import { motion, useReducedMotion } from 'motion/react';
import { useTranslations } from 'next-intl';

import { cn } from '@/lib/utils';
import type { CalendarEvent } from '@/app/lib/calendar';
import EventChip from './EventChip';

const EASE_OUT: [number, number, number, number] = [0.23, 1, 0.32, 1];

/**
 * The ordered event list for a single day, used as the selected-day pane in Month
 * and Week and as the body of the Day timeline's overflow section. Keyed by day so
 * switching days re-runs the entrance stagger. All-day rows drop their time label.
 */
export default function DayAgendaList({
    dayKey,
    events,
    locale,
    onOpenEvent,
    className,
}: {
    dayKey: string;
    events: CalendarEvent[];
    locale: string;
    onOpenEvent: (event: CalendarEvent) => void;
    className?: string;
}) {
    const t = useTranslations('Calendar');
    const reduce = useReducedMotion() ?? false;
    const timeFmt = useMemo(
        () => new Intl.DateTimeFormat(locale, { hour: 'numeric', minute: '2-digit' }),
        [locale],
    );

    if (events.length === 0) {
        return (
            <p className={cn('px-3 py-8 text-center text-sm text-muted-foreground', className)}>
                {t('noEventsDay')}
            </p>
        );
    }

    return (
        <ul key={dayKey} className={cn('flex flex-col gap-0.5', className)}>
            {events.map((event, index) => (
                <motion.li
                    key={event.id}
                    initial={reduce ? false : { opacity: 0, y: 6 }}
                    animate={{ opacity: 1, y: 0 }}
                    transition={
                        reduce
                            ? { duration: 0 }
                            : { duration: 0.2, delay: Math.min(index, 8) * 0.04, ease: EASE_OUT }
                    }
                >
                    <EventChip
                        event={event}
                        variant="row"
                        timeLabel={event.allDay ? undefined : timeFmt.format(event.startMs)}
                        onClick={() => onOpenEvent(event)}
                    />
                </motion.li>
            ))}
        </ul>
    );
}
