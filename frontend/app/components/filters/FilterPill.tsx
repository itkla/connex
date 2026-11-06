"use client";

import { useRef } from "react";
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
import { Input } from "@/components/ui/input";
import type { ServerPickerSearch } from "@/lib/server-picker-search";
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
    search,
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
    search?: { value: string; onChange: (value: string) => void; state: ServerPickerSearch };
}) {
    const active = selected.size > 0;
    const inputRef = useRef<HTMLInputElement>(null);
    const contentRef = useRef<HTMLDivElement>(null);
    const composingRef = useRef(false);
    const menuItems = () => Array.from(contentRef.current?.querySelectorAll<HTMLElement>(
        '[role^="menuitem"]:not([data-disabled]):not([aria-disabled="true"])',
    ) ?? []);
    return (
        <DropdownMenu onOpenChange={(open) => { if (!open) composingRef.current = false; }}>
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
            <DropdownMenuContent ref={contentRef} align="start" className="w-60 data-open:animate-none data-closed:animate-none"
                onFocusCapture={(event) => {
                    if (search && event.target === event.currentTarget) {
                        queueMicrotask(() => inputRef.current?.focus());
                    }
                }}
                onEscapeKeyDown={(event) => {
                    if (search && (composingRef.current || event.isComposing || event.keyCode === 229)) event.preventDefault();
                }}
                onKeyDownCapture={(event) => {
                    if (!search || event.target === inputRef.current) return;
                    if (event.key === 'Tab' || (event.key === 'ArrowUp' && event.target === menuItems()[0])) {
                        event.preventDefault();
                        event.stopPropagation();
                        inputRef.current?.focus();
                    }
                }}>

                <DropdownMenuLabel>{label}</DropdownMenuLabel>
                <DropdownMenuSeparator />
                {search ? <Input ref={inputRef} onCompositionStart={() => { composingRef.current = true; }} onCompositionEnd={() => { composingRef.current = false; }} value={search.value} aria-label={label} placeholder={label} className="mb-2" onChange={(event) => search.onChange(event.target.value)}
                    onKeyDown={(event) => {
                        if (event.key === 'Escape') return;
                        event.stopPropagation();
                        if (composingRef.current || event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229) return;
                        if (['ArrowDown', 'ArrowUp', 'Tab'].includes(event.key)) {
                            event.preventDefault();
                            const items = menuItems();
                            const backwards = event.key === 'ArrowUp' || (event.key === 'Tab' && event.shiftKey);
                            (backwards ? items.at(-1) : items[0])?.focus();
                        } else if (event.key === 'Enter') event.preventDefault();
                    }} /> : null}
                <div className={cn(scroll && "max-h-72 overflow-y-auto")}>
                    {options.filter((opt) => !search || selected.has(opt.value) || search.state.accepts({ id: opt.value })).map((opt) => {
                        const checked = selected.has(opt.value);
                        const count = countOf(opt.value, counts, opt.total);
                        const text = opt.label ?? opt.value;
                        return (
                            <DropdownMenuCheckboxItem
                                key={opt.value}
                                checked={checked}
                                onSelect={(e) => {
                                    e.preventDefault();
                                    if (!search || selected.has(opt.value) || search.state.accepts({ id: opt.value })) onToggle(opt.value);
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
