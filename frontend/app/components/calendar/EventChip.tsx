'use client';

import { forwardRef } from 'react';
import { ChevronRightIcon } from '@heroicons/react/24/outline';

import { cn } from '@/lib/utils';
import type { CalendarEvent } from '@/app/lib/calendar';
import { KIND_CHIP_CLASS, KIND_DOT_CLASS } from './constants';

/**
 * `chip` — compact tinted pill for month desktop cells.
 * `bar` — taller tinted block for the Day/Week hour timeline.
 * `row` — neutral agenda/list row with a kind dot, title and time.
 */
export type EventChipVariant = 'chip' | 'bar' | 'row';

interface EventChipProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
    event: CalendarEvent;
    variant?: EventChipVariant;
    /** Preformatted, locale-aware time (parent owns the locale). Omitted for all-day events. */
    timeLabel?: string;
    /** Visual lift while this chip is the active drag source. */
    dragging?: boolean;
}

/**
 * One event rendered as a tappable control. Presentational and locale-agnostic — the
 * parent supplies `timeLabel`. Forwards its ref and spreads button props so it can host
 * @dnd-kit draggable listeners.
 */
const EventChip = forwardRef<HTMLButtonElement, EventChipProps>(function EventChip(
    { event, variant = 'chip', timeLabel, dragging = false, className, ...rest },
    ref,
) {
    if (variant === 'row') {
        return (
            <button
                ref={ref}
                type="button"
                className={cn(
                    'group flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left outline-none transition-colors hover:bg-muted/60 focus-visible:ring-2 focus-visible:ring-brand/40',
                    dragging && 'opacity-40',
                    className,
                )}
                {...rest}
            >
                <span className={cn('size-2 shrink-0 rounded-full', KIND_DOT_CLASS[event.kind])} aria-hidden />
                <span className="min-w-0 flex-1 truncate text-sm font-medium text-foreground">{event.title}</span>
                {timeLabel ? (
                    <span className="shrink-0 text-xs tabular-nums text-muted-foreground">{timeLabel}</span>
                ) : null}
                <ChevronRightIcon className="size-4 shrink-0 text-muted-foreground/60 transition-colors group-hover:text-muted-foreground" />
            </button>
        );
    }

    if (variant === 'bar') {
        return (
            <button
                ref={ref}
                type="button"
                className={cn(
                    'flex h-full w-full flex-col justify-start gap-0.5 overflow-hidden rounded-lg px-2 py-1 text-left outline-none transition focus-visible:ring-2 focus-visible:ring-brand/40',
                    KIND_CHIP_CLASS[event.kind],
                    dragging && 'opacity-40',
                    className,
                )}
                {...rest}
            >
                <span className="truncate text-[11px] font-semibold leading-tight">{event.title}</span>
                {timeLabel ? <span className="text-[10px] tabular-nums opacity-80">{timeLabel}</span> : null}
            </button>
        );
    }

    return (
        <button
            ref={ref}
            type="button"
            className={cn(
                'block w-full truncate rounded-md px-2 py-0.5 text-left text-[11px] font-medium outline-none transition hover:brightness-[0.97] active:scale-[0.98] focus-visible:ring-2 focus-visible:ring-brand/40',
                KIND_CHIP_CLASS[event.kind],
                dragging && 'opacity-40',
                className,
            )}
            {...rest}
        >
            {event.title}
        </button>
    );
});

export default EventChip;
