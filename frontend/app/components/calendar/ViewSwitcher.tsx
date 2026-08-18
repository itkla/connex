'use client';

import { useTranslations } from 'next-intl';
import {
    CalendarDaysIcon,
    CalendarIcon,
    ListBulletIcon,
    ViewColumnsIcon,
} from '@heroicons/react/24/outline';
import type { ComponentType, SVGProps } from 'react';

import { SegmentedControl } from '@/components/ui/segmented-control';
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
 * Month/Week/Day/Agenda switcher on the canonical {@link SegmentedControl}. Labels collapse to
 * icon-only below `sm` so the control never overflows a narrow phone.
 */
export default function ViewSwitcher({
    value,
    onChange,
}: {
    value: CalendarView;
    onChange: (view: CalendarView) => void;
}) {
    const t = useTranslations('Calendar');

    return (
        <SegmentedControl<CalendarView>
            ariaLabel={t('viewSwitcherLabel')}
            value={value}
            onChange={onChange}
            options={ORDER.map((view) => {
                const Icon = VIEW_ICON[view];
                const label = t(VIEW_LABEL_KEY[view]);
                return {
                    value: view,
                    ariaLabel: label,
                    icon: <Icon className="size-3.5" />,
                    label: <span className="hidden sm:inline">{label}</span>,
                };
            })}
        />
    );
}
