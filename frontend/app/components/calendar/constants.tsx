import {
    BriefcaseIcon,
    ChatBubbleLeftRightIcon,
    ClipboardDocumentCheckIcon,
    PencilSquareIcon,
} from '@heroicons/react/24/outline';
import type { ComponentType, SVGProps } from 'react';

import type { CalendarEventKind } from '@/app/lib/calendar';

/** Tinted surface + readable ink per kind — month desktop chips and timeline blocks. */
export const KIND_CHIP_CLASS: Record<CalendarEventKind, string> = {
    task: 'bg-brand-light text-brand-dark',
    activity: 'bg-chart-2/15 text-foreground',
    deal: 'bg-chart-open/20 text-foreground',
    note: 'bg-muted text-foreground',
};

/** Solid dot color per kind — month compact clusters, week ribbon, agenda row accent. */
export const KIND_DOT_CLASS: Record<CalendarEventKind, string> = {
    task: 'bg-brand',
    activity: 'bg-chart-2',
    deal: 'bg-chart-open',
    note: 'bg-muted-foreground',
};

/** Heroicon per kind — detail sheet and agenda rows. */
export const KIND_ICON: Record<CalendarEventKind, ComponentType<SVGProps<SVGSVGElement>>> = {
    task: ClipboardDocumentCheckIcon,
    activity: ChatBubbleLeftRightIcon,
    deal: BriefcaseIcon,
    note: PencilSquareIcon,
};

/** Calendar-namespace i18n key for a kind's singular label. */
export const KIND_LABEL_KEY: Record<CalendarEventKind, 'itemTask' | 'itemActivity' | 'itemDeal' | 'itemNote'> = {
    task: 'itemTask',
    activity: 'itemActivity',
    deal: 'itemDeal',
    note: 'itemNote',
};

/** Calendar-namespace i18n key for a kind's filter toggle label. */
export const KIND_FILTER_KEY: Record<CalendarEventKind, 'filterTasks' | 'filterActivities' | 'filterDeals' | 'filterNotes'> = {
    task: 'filterTasks',
    activity: 'filterActivities',
    deal: 'filterDeals',
    note: 'filterNotes',
};
