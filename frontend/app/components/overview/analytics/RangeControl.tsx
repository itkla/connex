'use client';

import { motion, useReducedMotion } from 'motion/react';

import CustomRangePopover, {
    type CustomRangeLabels,
} from '@/app/components/overview/analytics/CustomRangePopover';
import type { AnalyticsWindow } from '@/app/components/overview/analytics/metrics';
import type { MemberScopeParams } from '@/app/lib/types';

const SEGMENT_CLASS = 'relative rounded-full px-3.5 py-1.5 text-xs font-medium transition-colors duration-150';

/**
 * Segmented pill control for the analytics board. The active thumb is shared across
 * its direct options and an optional explicit custom-range segment. Reused for both
 * the time-range and granularity controls via a distinct {@code layoutId} per instance.
 */
export default function RangeControl<K extends string>({
    value,
    onChange,
    options,
    customRange,
    label,
    layoutId = 'analytics-range-thumb',
}: {
    value: K;
    onChange: (next: K) => void;
    options: { key: K; label: string }[];
    customRange?: {
        key: K;
        value: AnalyticsWindow;
        locale: string;
        today: string;
        timezone: string;
        scope: MemberScopeParams;
        labels: CustomRangeLabels;
        onApply: (window: AnalyticsWindow) => void;
    };
    label: string;
    layoutId?: string;
}) {
    const reduce = useReducedMotion();
    const thumb = (
        <motion.span
            layoutId={layoutId}
            className="absolute inset-0 rounded-full bg-background shadow-sm ring-1 ring-border"
            transition={reduce ? { duration: 0 } : { type: 'spring', stiffness: 420, damping: 34 }}
        />
    );
    return (
        <div
            role="group"
            aria-label={label}
            className="inline-flex rounded-full bg-muted p-0.5 ring-1 ring-border"
        >
            {options.map((opt) => {
                const active = opt.key === value;
                return (
                    <button
                        key={opt.key}
                        type="button"
                        onClick={() => onChange(opt.key)}
                        aria-pressed={active}
                        className={`${SEGMENT_CLASS} ${
                            active ? 'text-foreground' : 'text-muted-foreground hover:text-foreground'
                        }`}
                    >
                        {active && thumb}
                        <span className="relative z-10">{opt.label}</span>
                    </button>
                );
            })}
            {customRange ? (
                <CustomRangePopover
                    active={customRange.key === value}
                    value={customRange.value}
                    locale={customRange.locale}
                    today={customRange.today}
                    timezone={customRange.timezone}
                    scope={customRange.scope}
                    labels={customRange.labels}
                    className={`${SEGMENT_CLASS} ${
                        customRange.key === value
                            ? 'text-foreground'
                            : 'text-muted-foreground hover:text-foreground'
                    }`}
                    thumb={thumb}
                    onApply={customRange.onApply}
                />
            ) : null}
        </div>
    );
}
