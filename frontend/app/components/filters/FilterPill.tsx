"use client";

import { XMarkIcon } from "@heroicons/react/24/outline";
import { Button, type ButtonProps } from "@/components/ui/button";
import {
    DropdownMenu,
    DropdownMenuTrigger,
    DropdownMenuContent,
    DropdownMenuCheckboxItem,
    DropdownMenuRadioGroup,
    DropdownMenuRadioItem,
    DropdownMenuLabel,
    DropdownMenuSeparator,
    DropdownMenuItem,
} from "@/components/ui/dropdown-menu";
import { cn } from "@/lib/utils";

export type MultiSelectOption = { value: string; total?: number; label?: string };
export type RadioOption = { value: string; label: string; count?: number };

export type FilterTriggerProps = Omit<ButtonProps, "size" | "variant"> & {
    active: boolean;
};

/** Shared semantic trigger for toolbar filters and filter toggles. */
export function FilterTrigger({ active, className, ...props }: FilterTriggerProps) {
    return (
        <Button
            type="button"
            variant="ghost"
            size="page"
            aria-pressed={active}
            className={cn(
                "min-w-0 max-w-full bg-muted px-3 text-xs text-foreground ring-1 ring-border shadow-none hover:bg-accent hover:text-accent-foreground",
                active && "bg-brand-light/70 ring-brand-dark/20 hover:bg-brand-light/70",
                className,
            )}
            {...props}
        />
    );
}

function countOf(value: string, counts: Map<string, number> | undefined, total: number | undefined): number | null {
    if (counts) return counts.get(value) ?? 0;
    if (typeof total === "number") return total;
    return null;
}

export function MultiSelectFilter({
    label,
    ariaLabel,
    options,
    counts,
    selected,
    onToggle,
    onClear,
    clearLabel,
    capitalize,
    scroll,
}: {
    label: string;
    ariaLabel: string;
    options: MultiSelectOption[];
    counts?: Map<string, number>;
    selected: Set<string>;
    onToggle: (v: string) => void;
    onClear: () => void;
    clearLabel: string;
    capitalize?: boolean;
    scroll?: boolean;
}) {
    const active = selected.size > 0;
    return (
        <DropdownMenu>
            <DropdownMenuTrigger asChild>
                <FilterTrigger active={active} aria-label={ariaLabel} menu>
                    <span>{label}</span>
                    {active && (
                        <span className="grid size-4 place-items-center rounded-full bg-brand text-[10px] font-semibold leading-none text-brand-foreground tabular-nums">
                            {selected.size}
                        </span>
                    )}
                </FilterTrigger>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" className="w-60 data-open:animate-none data-closed:animate-none">
                <DropdownMenuLabel>{label}</DropdownMenuLabel>
                <DropdownMenuSeparator />
                <div className={cn(scroll && "max-h-72 overflow-y-auto")}>
                    {options.map((opt) => {
                        const checked = selected.has(opt.value);
                        const count = countOf(opt.value, counts, opt.total);
                        const text = opt.label ?? opt.value;
                        return (
                            <DropdownMenuCheckboxItem
                                key={opt.value}
                                checked={checked}
                                onSelect={(e) => {
                                    e.preventDefault();
                                    onToggle(opt.value);
                                }}
                            >
                                <span className={cn("flex flex-1 items-center justify-between gap-2", capitalize && "capitalize")}>
                                    <span className="truncate">{text}</span>
                                    {count !== null && (
                                        <span className="shrink-0 text-xs tabular-nums text-muted-foreground">{count}</span>
                                    )}
                                </span>
                            </DropdownMenuCheckboxItem>
                        );
                    })}
                </div>
                {active && (
                    <>
                        <DropdownMenuSeparator />
                        <DropdownMenuItem onSelect={onClear} className="text-muted-foreground">
                            <XMarkIcon className="size-4" />
                            {clearLabel}
                        </DropdownMenuItem>
                    </>
                )}
            </DropdownMenuContent>
        </DropdownMenu>
    );
}

export function RadioFilter({
    label,
    ariaLabel,
    value,
    onValueChange,
    onOpenChange,
    options,
}: {
    label: string;
    ariaLabel: string;
    value: string;
    onValueChange: (v: string) => void;
    onOpenChange?: (open: boolean) => void;
    options: RadioOption[];
}) {
    const active = value !== options[0]?.value;
    const selectedLabel = options.find((o) => o.value === value)?.label ?? label;
    return (
        <DropdownMenu onOpenChange={onOpenChange}>
            <DropdownMenuTrigger asChild>
                <FilterTrigger active={active} aria-label={ariaLabel} menu>
                    <span className="max-w-40 truncate">{active ? selectedLabel : label}</span>
                </FilterTrigger>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" className="w-56 data-open:animate-none data-closed:animate-none">
                <DropdownMenuLabel>{label}</DropdownMenuLabel>
                <DropdownMenuSeparator />
                <DropdownMenuRadioGroup value={value} onValueChange={onValueChange}>
                    {options.map((opt) => (
                        <DropdownMenuRadioItem key={opt.value} value={opt.value}>
                            <span className="flex min-w-0 flex-1 items-center justify-between gap-2">
                                <span className="truncate">{opt.label}</span>
                                {typeof opt.count === "number" && (
                                    <span className="shrink-0 text-xs tabular-nums text-muted-foreground">{opt.count}</span>
                                )}
                            </span>
                        </DropdownMenuRadioItem>
                    ))}
                </DropdownMenuRadioGroup>
            </DropdownMenuContent>
        </DropdownMenu>
    );
}
