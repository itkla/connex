"use client";

import { AnimatePresence, motion } from "motion/react";
import { XMarkIcon } from "@heroicons/react/24/outline";
import { useTranslations } from "next-intl";

import { Button } from "@/components/ui/button";
import { IconButton } from "@/components/ui/icon-button";
import { cn } from "@/lib/utils";

export type FilterChipData = { id: string; label: string; onRemove: () => void };

const EASE_OUT: [number, number, number, number] = [0.23, 1, 0.32, 1];

export function FilterChip({
    label,
    removeLabel,
    reduce,
    onRemove,
}: {
    label: string;
    removeLabel: string;
    reduce: boolean;
    onRemove: () => void;
}) {
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
            <IconButton
                label={removeLabel}
                type="button"
                variant="ghost"
                size="icon-inline"
                onClick={onRemove}
                className="text-muted-foreground hover:bg-background hover:text-foreground"
            >
                <XMarkIcon className="size-3" />
            </IconButton>
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
    const t = useTranslations("Filters");
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
                    <Button
                        type="button"
                        variant="ghost"
                        size="toolbar"
                        onClick={onClearAll}
                        className={cn(
                            "text-xs text-muted-foreground hover:text-foreground",
                            collapsible && "hidden md:flex",
                        )}
                    >
                        <XMarkIcon className="size-3.5" />
                        {clearAllLabel}
                    </Button>
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
                            <FilterChip
                                key={chip.id}
                                label={chip.label}
                                removeLabel={t("removeFilter", { label: chip.label })}
                                reduce={reduce}
                                onRemove={chip.onRemove}
                            />
                        ))}
                    </motion.div>
                )}
            </AnimatePresence>
        </div>
    );
}
