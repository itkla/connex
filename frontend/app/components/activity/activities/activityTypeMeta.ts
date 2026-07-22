import { type ComponentType } from 'react';
import {
    PhoneIcon,
    EnvelopeIcon,
    UserGroupIcon,
    PencilSquareIcon,
    SparklesIcon,
} from '@heroicons/react/24/outline';

type IconType = ComponentType<{ className?: string }>;

/** The canonical activity types, in display order. */
export const ACTIVITY_TYPES = ['Call', 'Email', 'Meeting', 'Note', 'Other'] as const;
export type ActivityType = (typeof ACTIVITY_TYPES)[number];

/** Icon and theming for one activity type. Server-safe (pure data + icon components). */
export type TypeMeta = {
    Icon: IconType;
    chip: string;
    selected: string;
};

/** Per-type icon and chip/selected styling. Imported by both server pages and client components. */
export const TYPE_META: Record<ActivityType, TypeMeta> = {
    Call: {
        Icon: PhoneIcon,
        chip: 'bg-emerald-50 text-emerald-600 ring-emerald-600/15 dark:bg-emerald-950/40 dark:text-emerald-300 dark:ring-emerald-400/20',
        selected: 'bg-emerald-50 text-emerald-700 ring-emerald-600/30 dark:bg-emerald-950/40 dark:text-emerald-300 dark:ring-emerald-400/30',
    },
    Email: {
        Icon: EnvelopeIcon,
        chip: 'bg-sky-50 text-sky-600 ring-sky-600/15 dark:bg-sky-950/40 dark:text-sky-300 dark:ring-sky-400/20',
        selected: 'bg-sky-50 text-sky-700 ring-sky-600/30 dark:bg-sky-950/40 dark:text-sky-300 dark:ring-sky-400/30',
    },
    Meeting: {
        Icon: UserGroupIcon,
        chip: 'bg-violet-50 text-violet-600 ring-violet-600/15 dark:bg-violet-950/40 dark:text-violet-300 dark:ring-violet-400/20',
        selected: 'bg-violet-50 text-violet-700 ring-violet-600/30 dark:bg-violet-950/40 dark:text-violet-300 dark:ring-violet-400/30',
    },
    Note: {
        Icon: PencilSquareIcon,
        chip: 'bg-amber-50 text-amber-600 ring-amber-600/15 dark:bg-amber-950/40 dark:text-amber-300 dark:ring-amber-400/20',
        selected: 'bg-amber-50 text-amber-700 ring-amber-600/30 dark:bg-amber-950/40 dark:text-amber-300 dark:ring-amber-400/30',
    },
    Other: {
        Icon: SparklesIcon,
        chip: 'bg-muted text-muted-foreground ring-border',
        selected: 'bg-muted text-foreground ring-border',
    },
};

/** Coerces a free-text activity type to a known {@link ActivityType}, defaulting to `Other`. */
export function normalizeType(value?: string | null): ActivityType {
    if (!value) return 'Other';
    const match = ACTIVITY_TYPES.find((t) => t.toLowerCase() === value.toLowerCase());
    return match ?? 'Other';
}
