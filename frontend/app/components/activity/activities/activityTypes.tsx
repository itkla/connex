'use client';

import { type ComponentType } from 'react';
import {
    PhoneIcon,
    EnvelopeIcon,
    UserGroupIcon,
    PencilSquareIcon,
    SparklesIcon,
} from '@heroicons/react/24/outline';

import { cn } from '@/lib/utils';

type IconType = ComponentType<{ className?: string }>;

export const ACTIVITY_TYPES = ['Call', 'Email', 'Meeting', 'Note', 'Other'] as const;
export type ActivityType = (typeof ACTIVITY_TYPES)[number];

type TypeMeta = {
    Icon: IconType;
    chip: string;
    selected: string;
};

export const TYPE_META: Record<ActivityType, TypeMeta> = {
    Call: {
        Icon: PhoneIcon,
        chip: 'bg-emerald-50 text-emerald-600 ring-emerald-600/15',
        selected: 'bg-emerald-50 text-emerald-700 ring-emerald-600/30',
    },
    Email: {
        Icon: EnvelopeIcon,
        chip: 'bg-sky-50 text-sky-600 ring-sky-600/15',
        selected: 'bg-sky-50 text-sky-700 ring-sky-600/30',
    },
    Meeting: {
        Icon: UserGroupIcon,
        chip: 'bg-violet-50 text-violet-600 ring-violet-600/15',
        selected: 'bg-violet-50 text-violet-700 ring-violet-600/30',
    },
    Note: {
        Icon: PencilSquareIcon,
        chip: 'bg-amber-50 text-amber-600 ring-amber-600/15',
        selected: 'bg-amber-50 text-amber-700 ring-amber-600/30',
    },
    Other: {
        Icon: SparklesIcon,
        chip: 'bg-neutral-100 text-neutral-500 ring-black/5',
        selected: 'bg-neutral-100 text-neutral-700 ring-black/10',
    },
};

export function normalizeType(value?: string | null): ActivityType {
    if (!value) return 'Other';
    const match = ACTIVITY_TYPES.find((t) => t.toLowerCase() === value.toLowerCase());
    return match ?? 'Other';
}

export function ActivityTypePicker({
    value,
    onChange,
    getLabel,
    disabled,
}: {
    value: string;
    onChange: (type: ActivityType) => void;
    getLabel: (type: ActivityType) => string;
    disabled?: boolean;
}) {
    const current = normalizeType(value);
    return (
        <div role="radiogroup" className="grid grid-cols-5 gap-1.5">
            {ACTIVITY_TYPES.map((type) => {
                const { Icon, selected } = TYPE_META[type];
                const active = current === type;
                return (
                    <button
                        key={type}
                        type="button"
                        role="radio"
                        aria-checked={active}
                        disabled={disabled}
                        onClick={() => onChange(type)}
                        className={cn(
                            'flex flex-col items-center gap-1.5 rounded-xl px-1 py-2.5 text-[11px] font-medium ring-1 ring-inset transition duration-150 active:scale-[0.97] disabled:pointer-events-none disabled:opacity-50',
                            active
                                ? selected
                                : 'bg-white text-neutral-500 ring-black/5 hover:bg-neutral-50 hover:text-neutral-700',
                        )}
                    >
                        <Icon className="size-4" />
                        <span className="truncate">{getLabel(type)}</span>
                    </button>
                );
            })}
        </div>
    );
}