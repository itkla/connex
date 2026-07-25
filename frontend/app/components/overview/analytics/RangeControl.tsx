'use client';

import { motion, useReducedMotion } from 'motion/react';
import { CheckIcon, ChevronDownIcon } from '@heroicons/react/16/solid';

import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';

const SEGMENT_CLASS = 'relative rounded-full px-3.5 py-1.5 text-xs font-medium transition-colors duration-150';

/**
 * Segmented pill control for the analytics board. Renders one pill per {@code options}
 * entry plus, when {@code presets} are given, a trailing dropdown segment listing
 * calendar presets as rows; the active thumb (a shared-layout spring) covers whichever
 * segment holds the current value. Reused for both the time-range and granularity
 * controls via a distinct {@code layoutId} per instance.
 */
export default function RangeControl<K extends string>({
    value,
    onChange,
    options,
    presets,
    presetsLabel,
    label,
    layoutId = 'analytics-range-thumb',
}: {
    value: K;
    onChange: (next: K) => void;
    options: { key: K; label: string }[];
    presets?: { key: K; label: string }[];
    presetsLabel?: string;
    label: string;
    layoutId?: string;
}) {
    const reduce = useReducedMotion();
    const activePreset = presets?.find((preset) => preset.key === value);
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
            {presets && presets.length > 0 && (
                <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                        <button
                            type="button"
                            aria-pressed={activePreset != null}
                            className={`${SEGMENT_CLASS} ${
                                activePreset ? 'text-foreground' : 'text-muted-foreground hover:text-foreground'
                            }`}
                        >
                            {activePreset && thumb}
                            <span className="relative z-10 inline-flex items-center gap-1">
                                {activePreset?.label ?? presetsLabel}
                                <ChevronDownIcon className="size-3.5 text-muted-foreground" />
                            </span>
                        </button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end">
                        {presets.map((preset) => (
                            <DropdownMenuItem key={preset.key} onSelect={() => onChange(preset.key)}>
                                <span className={preset.key === value ? 'font-semibold' : ''}>{preset.label}</span>
                                {preset.key === value && <CheckIcon className="ml-auto size-4" />}
                            </DropdownMenuItem>
                        ))}
                    </DropdownMenuContent>
                </DropdownMenu>
            )}
        </div>
    );
}
