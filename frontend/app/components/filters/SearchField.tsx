"use client";

import { useEffect, useRef } from "react";
import { MagnifyingGlassIcon, XMarkIcon } from "@heroicons/react/24/outline";
import { cn } from "@/lib/utils";
import { isTypingTarget } from "@/app/lib/utils";

export default function SearchField({
    value,
    onChange,
    onClear,
    placeholder,
    searchAria,
    clearAria,
    shortcut = "/",
    inputRef,
    className,
}: {
    value: string;
    onChange: (v: string) => void;
    onClear: () => void;
    placeholder: string;
    searchAria: string;
    clearAria: string;
    shortcut?: string | null;
    inputRef?: React.RefObject<HTMLInputElement | null>;
    className?: string;
}) {
    const internalRef = useRef<HTMLInputElement>(null);
    const ref = inputRef ?? internalRef;

    useEffect(() => {
        if (!shortcut) return;
        function onKeyDown(e: KeyboardEvent) {
            if (e.key === shortcut && !isTypingTarget(e.target)) {
                e.preventDefault();
                ref.current?.focus();
            }
        }
        document.addEventListener("keydown", onKeyDown);
        return () => document.removeEventListener("keydown", onKeyDown);
    }, [shortcut, ref]);

    return (
        <div className={cn("relative w-72 max-w-full", className)}>
            <MagnifyingGlassIcon className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
            <input
                ref={ref}
                type="text"
                value={value}
                aria-label={searchAria}
                onChange={(e) => onChange(e.target.value)}
                onKeyDown={(e) => {
                    if (e.key === "Escape" && value) {
                        e.preventDefault();
                        onClear();
                    }
                }}
                placeholder={placeholder}
                className="h-9 w-full rounded-full bg-muted pl-9 pr-9 text-sm text-foreground outline-none ring-1 ring-border transition placeholder:text-muted-foreground focus:ring-2 focus:ring-brand"
            />
            {value ? (
                <button
                    type="button"
                    onClick={onClear}
                    aria-label={clearAria}
                    className="absolute right-2.5 top-1/2 grid size-5 -translate-y-1/2 place-items-center rounded-full text-muted-foreground outline-none transition hover:bg-background hover:text-foreground active:scale-90 focus-visible:ring-2 focus-visible:ring-brand/40"
                >
                    <XMarkIcon className="size-3.5" />
                </button>
            ) : shortcut ? (
                <kbd className="pointer-events-none absolute right-3 top-1/2 hidden -translate-y-1/2 select-none rounded border border-border bg-background px-1.5 text-[10px] font-medium text-muted-foreground sm:block">
                    {shortcut}
                </kbd>
            ) : null}
        </div>
    );
}
