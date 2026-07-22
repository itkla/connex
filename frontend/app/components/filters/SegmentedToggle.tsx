"use client";

import { useId } from "react";
import { motion, useReducedMotion } from "motion/react";
import { cn } from "@/lib/utils";

export type Segment<T extends string> = {
    value: T;
    label?: React.ReactNode;
    icon?: React.ReactNode;
    ariaLabel?: string;
};

export default function SegmentedToggle<T extends string>({
    value,
    onChange,
    options,
    ariaLabel,
    className,
}: {
    value: T;
    onChange: (v: T) => void;
    options: Segment<T>[];
    ariaLabel?: string;
    className?: string;
}) {
    const layoutId = useId();
    const reduce = useReducedMotion() ?? false;

    return (
        <div
            role="group"
            aria-label={ariaLabel}
            className={cn("inline-flex items-center rounded-full bg-muted p-0.5 ring-1 ring-border/60", className)}
        >
            {options.map((opt) => {
                const active = value === opt.value;
                return (
                    <button
                        key={opt.value}
                        type="button"
                        onClick={() => onChange(opt.value)}
                        aria-pressed={active}
                        aria-label={opt.ariaLabel}
                        className={cn(
                            "relative inline-flex h-8 items-center justify-center gap-1 rounded-full text-xs font-medium outline-none transition-colors active:scale-[0.97] focus-visible:ring-2 focus-visible:ring-brand/40",
                            opt.label ? "px-2.5" : "w-8",
                            active ? "text-foreground" : "text-muted-foreground hover:text-foreground",
                        )}
                    >
                        {active && (
                            <motion.span
                                layoutId={layoutId}
                                aria-hidden
                                className="absolute inset-0 rounded-full bg-background shadow-sm"
                                transition={reduce ? { duration: 0 } : { type: "spring", stiffness: 520, damping: 42 }}
                            />
                        )}
                        <span className="relative z-10 inline-flex items-center gap-1">
                            {opt.icon}
                            {opt.label}
                        </span>
                    </button>
                );
            })}
        </div>
    );
}
