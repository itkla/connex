'use client';

import { cn } from '@/lib/utils';
import {
    ACTIVITY_TYPES,
    TYPE_META,
    normalizeType,
    type ActivityType,
} from '@/app/components/activity/activities/activityTypeMeta';

export { ACTIVITY_TYPES, TYPE_META, normalizeType };
export type { ActivityType, TypeMeta } from '@/app/components/activity/activities/activityTypeMeta';

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
                                : 'bg-card text-muted-foreground ring-border hover:bg-muted hover:text-foreground',
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