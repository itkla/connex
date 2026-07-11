"use client";

import { ChevronDownIcon } from "@heroicons/react/20/solid";
import { XMarkIcon } from "@heroicons/react/24/outline";
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

export function pillClass(active: boolean): string {
    return cn(
        "group inline-flex h-9 items-center gap-1.5 rounded-full px-3 text-xs font-medium ring-1 outline-none transition active:scale-[0.97] focus-visible:ring-2 focus-visible:ring-brand/40",
        active
            ? "bg-brand-light/70 text-brand-dark ring-brand-dark/20"
            : "bg-muted text-foreground ring-border hover:bg-accent hover:text-accent-foreground",
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
                <button type="button" aria-label={ariaLabel} aria-pressed={active} className={pillClass(active)}>
                    <span>{label}</span>
                    {active && (
                        <span className="grid size-4 place-items-center rounded-full bg-brand text-[10px] font-semibold leading-none text-brand-foreground tabular-nums">
                            {selected.size}
                        </span>
                    )}
                    <ChevronDownIcon className="size-3.5 text-muted-foreground transition-transform duration-200 ease-out group-data-[state=open]:rotate-180" />
                </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" className="w-60">
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
    options,
}: {
    label: string;
    ariaLabel: string;
    value: string;
    onValueChange: (v: string) => void;
    options: RadioOption[];
}) {
    const active = value !== options[0]?.value;
    const selectedLabel = options.find((o) => o.value === value)?.label ?? label;
    return (
        <DropdownMenu>
            <DropdownMenuTrigger asChild>
                <button type="button" aria-label={ariaLabel} aria-pressed={active} className={pillClass(active)}>
                    <span>{active ? selectedLabel : label}</span>
                    <ChevronDownIcon className="size-3.5 text-muted-foreground transition-transform duration-200 ease-out group-data-[state=open]:rotate-180" />
                </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" className="w-56">
                <DropdownMenuLabel>{label}</DropdownMenuLabel>
                <DropdownMenuSeparator />
                <DropdownMenuRadioGroup value={value} onValueChange={onValueChange}>
                    {options.map((opt) => (
                        <DropdownMenuRadioItem key={opt.value} value={opt.value}>
                            <span className="flex flex-1 items-center justify-between gap-2">
                                <span>{opt.label}</span>
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
