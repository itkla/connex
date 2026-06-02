'use client';

import { motion, useReducedMotion } from 'motion/react';
import { type RangeKey } from '@/app/components/overview/analytics/metrics';

export default function RangeControl({
    value,
    onChange,
    options,
    label,
}: {
    value: RangeKey;
    onChange: (next: RangeKey) => void;
    options: { key: RangeKey; label: string }[];
    label: string;
}) {
    const reduce = useReducedMotion();
    return (
        <div
            role="group"
            aria-label={label}
            className="inline-flex rounded-full bg-neutral-100 p-0.5 ring-1 ring-black/5"
        >
            {options.map((opt) => {
                const active = opt.key === value;
                return (
                    <button
                        key={opt.key}
                        type="button"
                        onClick={() => onChange(opt.key)}
                        aria-pressed={active}
                        className={`relative rounded-full px-3.5 py-1.5 text-xs font-medium transition-colors duration-150 ${
                            active ? 'text-neutral-900' : 'text-neutral-500 hover:text-neutral-700'
                        }`}
                    >
                        {active && (
                            <motion.span
                                layoutId="analytics-range-thumb"
                                className="absolute inset-0 rounded-full bg-white shadow-sm ring-1 ring-black/5"
                                transition={
                                    reduce ? { duration: 0 } : { type: 'spring', stiffness: 420, damping: 34 }
                                }
                            />
                        )}
                        <span className="relative z-10">{opt.label}</span>
                    </button>
                );
            })}
        </div>
    );
}