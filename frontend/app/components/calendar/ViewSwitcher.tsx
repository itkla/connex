'use client';

import { useId } from 'react';
import { motion, useReducedMotion } from 'motion/react';
import { useTranslations } from 'next-intl';
import {
    CalendarDaysIcon,
    CalendarIcon,
    ListBulletIcon,
    ViewColumnsIcon,
} from '@heroicons/react/24/outline';
import type { ComponentType, SVGProps } from 'react';

import { cn } from '@/lib/utils';
import type { CalendarView } from './useCalendar';

const VIEW_ICON: Record<CalendarView, ComponentType<SVGProps<SVGSVGElement>>> = {
    month: CalendarDaysIcon,
    week: ViewColumnsIcon,
    day: CalendarIcon,
    agenda: ListBulletIcon,
};

const VIEW_LABEL_KEY: Record<CalendarView, 'viewMonth' | 'viewWeek' | 'viewDay' | 'viewAgenda'> = {
    month: 'viewMonth',
    week: 'viewWeek',
    day: 'viewDay',
    agenda: 'viewAgenda',
};

const ORDER: readonly CalendarView[] = ['month', 'week', 'day', 'agenda'];

/**
 * Segmented Month/Week/Day/Agenda switcher. Mirrors {@link SegmentedToggle}: a shared
 * `layoutId` pill glides under the active segment. Labels collapse to icon-only below
 * `sm` so the control never overflows a narrow phone.
 */
export default function ViewSwitcher({
    value,
    onChange,
}: {
    value: CalendarView;
    onChange: (view: CalendarView) => void;
}) {
    const t = useTranslations('Calendar');
    const layoutId = useId();
    const reduce = useReducedMotion() ?? false;

    return (
        <div
            role="group"
            aria-label={t('viewSwitcherLabel')}
            data-no-swipe
            className="inline-flex items-center rounded-full bg-muted p-0.5 ring-1 ring-border/60"
        >
            {ORDER.map((view) => {
                const active = value === view;
                const Icon = VIEW_ICON[view];
                const label = t(VIEW_LABEL_KEY[view]);
                return (
                    <button
                        key={view}
                        type="button"
                        onClick={() => onChange(view)}
                        aria-pressed={active}
                        aria-label={label}
                        className={cn(
                            'relative inline-flex h-8 items-center justify-center gap-1.5 rounded-full px-2.5 text-xs font-medium outline-none transition-colors active:scale-[0.97] focus-visible:ring-2 focus-visible:ring-brand/40',
                            active ? 'text-foreground' : 'text-muted-foreground hover:text-foreground',
                        )}
                    >
                        {active && (
                            <motion.span
                                layoutId={layoutId}
                                aria-hidden
                                className="absolute inset-0 rounded-full bg-background shadow-sm"
                                transition={reduce ? { duration: 0 } : { type: 'spring', stiffness: 520, damping: 42 }}
                            />
                        )}
                        <span className="relative z-10 inline-flex items-center gap-1.5">
                            <Icon className="size-3.5" />
                            <span className="hidden sm:inline">{label}</span>
                        </span>
                    </button>
                );
            })}
        </div>
    );
}
