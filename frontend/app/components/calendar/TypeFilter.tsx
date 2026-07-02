'use client';

import { useTranslations } from 'next-intl';

import { cn } from '@/lib/utils';
import { EVENT_KINDS, type CalendarEventKind } from '@/app/lib/calendar';
import { KIND_DOT_CLASS, KIND_FILTER_KEY } from './constants';

/**
 * Row of kind toggles (Tasks / Activities / Deals / Notes). Each chip flips a kind's
 * visibility; the row scrolls horizontally on narrow screens. Turning off the last
 * visible kind is a no-op so the calendar is never fully blank. `data-no-swipe` keeps
 * horizontal scrolling from being read as a period swipe.
 */
export default function TypeFilter({
    visibleKinds,
    onToggle,
}: {
    visibleKinds: Set<CalendarEventKind>;
    onToggle: (kind: CalendarEventKind) => void;
}) {
    const t = useTranslations('Calendar');

    return (
        <div
            role="group"
            aria-label={t('filters')}
            data-no-swipe
            className="flex items-center gap-1.5 overflow-x-auto pb-0.5 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
        >
            {EVENT_KINDS.map((kind) => {
                const active = visibleKinds.has(kind);
                return (
                    <button
                        key={kind}
                        type="button"
                        aria-pressed={active}
                        onClick={() => onToggle(kind)}
                        className={cn(
                            'inline-flex h-8 shrink-0 items-center gap-1.5 rounded-full px-3 text-xs font-medium outline-none ring-1 transition active:scale-[0.97] focus-visible:ring-2 focus-visible:ring-brand/40',
                            active
                                ? 'bg-card text-foreground ring-border'
                                : 'bg-muted/50 text-muted-foreground ring-transparent hover:text-foreground',
                        )}
                    >
                        <span
                            className={cn(
                                'size-2 rounded-full transition-opacity',
                                KIND_DOT_CLASS[kind],
                                active ? 'opacity-100' : 'opacity-40',
                            )}
                            aria-hidden
                        />
                        {t(KIND_FILTER_KEY[kind])}
                    </button>
                );
            })}
        </div>
    );
}
