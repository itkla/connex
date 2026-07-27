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

/**
 * The toolbar above a record list: facet controls, a clear-all shortcut, view/sort controls and the
 * search field, with the applied-filter chips beneath.
 *
 * Passing `collapsed` opts the bar into its phone layout: below the `md` breakpoint the facet
 * controls, the clear-all shortcut and `trailing` are hidden and `collapsed` takes their place — a
 * single trigger that opens them in a sheet, so the toolbar stays one row instead of wrapping into
 * several. The chips row is never collapsed: an applied filter must stay visible and removable on
 * every viewport.
 */
export default function FilterBar({
    search,
    children,
    trailing,
    collapsed,
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
    collapsed?: React.ReactNode;
    chips: FilterChipData[];
    hasActiveFilters: boolean;
    onClearAll: () => void;
    clearAllLabel: string;
    reduce: boolean;
    className?: string;
}) {
    const collapsible = collapsed != null;
    return (
        <div className={cn("rounded-2xl py-2.5", className)}>
            <div className="flex flex-wrap items-center gap-2">
                {children && (
                    <div className={cn("flex flex-wrap items-center gap-1.5", collapsible && "hidden md:flex")}>
                        {children}
                    </div>
                )}
                {hasActiveFilters && (
                    <button
                        type="button"
                        onClick={onClearAll}
                        className={cn(
                            "inline-flex h-8 items-center gap-1.5 rounded-full px-3 text-xs font-medium text-muted-foreground outline-none transition-colors motion-reduce:transition-none hover:bg-muted hover:text-foreground focus-visible:ring-2 focus-visible:ring-brand/40",
                            collapsible && "hidden md:inline-flex",
                        )}
                    >
                        <XMarkIcon className="size-3.5" />
                        {clearAllLabel}
                    </button>
                )}
                <div
                    className={cn(
                        "ml-auto flex flex-wrap items-center gap-2",
                        collapsible && "min-w-0 flex-1 flex-nowrap md:flex-initial md:flex-wrap",
                    )}
                >
                    {collapsible ? (
                        trailing && <div className="hidden items-center gap-2 md:flex">{trailing}</div>
                    ) : (
                        trailing
                    )}
                    {search}
                    {collapsed}
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
