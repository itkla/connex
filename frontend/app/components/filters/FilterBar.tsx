"use client";

import { AnimatePresence, motion } from "motion/react";
import { XMarkIcon } from "@heroicons/react/24/outline";
import { cn } from "@/lib/utils";

export type FilterChipData = { id: string; label: string; onRemove: () => void };

const EASE_OUT: [number, number, number, number] = [0.23, 1, 0.32, 1];

export function FilterChip({ label, reduce, onRemove }: { label: string; reduce: boolean; onRemove: () => void }) {
    return (
        <motion.span
            layout={!reduce}
            initial={reduce ? false : { opacity: 0, scale: 0.9 }}
            animate={{ opacity: 1, scale: 1 }}
            exit={reduce ? { opacity: 0 } : { opacity: 0, scale: 0.9 }}
            transition={{ duration: 0.18, ease: EASE_OUT }}
            className="inline-flex h-6 items-center gap-1 rounded-full bg-muted px-2.5 text-xs font-medium text-foreground ring-1 ring-border"
        >
            <span className="max-w-40 truncate">{label}</span>
            <button
                type="button"
                onClick={onRemove}
                className="grid size-4 place-items-center rounded-full text-muted-foreground outline-none transition-colors motion-reduce:transition-none hover:bg-background hover:text-foreground focus-visible:ring-2 focus-visible:ring-brand/40"
                aria-label="Remove filter"
            >
                <XMarkIcon className="size-3" />
            </button>
        </motion.span>
    );
}

export default function FilterBar({
    search,
    children,
    trailing,
    chips,
    hasActiveFilters,
    onClearAll,
    clearAllLabel,
    reduce,
    className,
}: {
    search?: React.ReactNode;
    children?: React.ReactNode;
    trailing?: React.ReactNode;
    chips: FilterChipData[];
    hasActiveFilters: boolean;
    onClearAll: () => void;
    clearAllLabel: string;
    reduce: boolean;
    className?: string;
}) {
    return (
        <div className={cn("rounded-2xl py-2.5", className)}>
            <div className="flex flex-wrap items-center gap-2">
                {children && <div className="flex flex-wrap items-center gap-1.5">{children}</div>}
                {hasActiveFilters && (
                    <button
                        type="button"
                        onClick={onClearAll}
                        className="inline-flex h-8 items-center gap-1.5 rounded-full px-3 text-xs font-medium text-muted-foreground outline-none transition-colors motion-reduce:transition-none hover:bg-muted hover:text-foreground focus-visible:ring-2 focus-visible:ring-brand/40"
                    >
                        <XMarkIcon className="size-3.5" />
                        {clearAllLabel}
                    </button>
                )}
                <div className="ml-auto flex flex-wrap items-center gap-2">
                    {trailing}
                    {search}
                </div>
            </div>

            <AnimatePresence initial={false}>
                {chips.length > 0 && (
                    <motion.div
                        layout={!reduce}
                        className="mt-2 flex flex-wrap items-center gap-1.5 border-t border-border/60 pt-2"
                        initial={reduce ? false : { opacity: 0, height: 0 }}
                        animate={{ opacity: 1, height: "auto" }}
                        exit={reduce ? { opacity: 0 } : { opacity: 0, height: 0 }}
                        transition={{ duration: 0.2, ease: EASE_OUT }}
                    >
                        {chips.map((chip) => (
                            <FilterChip key={chip.id} label={chip.label} reduce={reduce} onRemove={chip.onRemove} />
                        ))}
                    </motion.div>
                )}
            </AnimatePresence>
        </div>
    );
}
