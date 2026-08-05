"use client";

import { useEffect, useId, useState, type FormEvent } from "react";
import type { Editor } from "@tiptap/core";
import { Paperclip, RotateCw } from "lucide-react";
import { PaperClipIcon } from "@heroicons/react/24/outline";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
    Popover,
    PopoverContent,
    PopoverDescription,
    PopoverTitle,
    PopoverTrigger,
} from "@/components/ui/popover";
import { cn } from "@/lib/utils";
import { insertMention } from "./Mention";
import { queryFileMentions, type MentionItem } from "./mentionData";

export type FileReferenceLabels = {
    file: string;
    fileTitle: string;
    fileDescription: string;
    fileSearchLabel: string;
    fileSearchPlaceholder: string;
    fileSearching: string;
    fileNoResults: string;
    fileSearchError: string;
    fileRetry: string;
    filePickerAria: string;
};

type Props = {
    editor: Editor;
    labels: FileReferenceLabels;
    open: boolean;
    onOpenChange: (open: boolean) => void;
};

function toolbarButtonClass(active = false) {
    return cn(
        "flex size-11 items-center justify-center rounded-md transition-[color,background-color,transform] duration-150 ease-out active:scale-[0.97] sm:size-8",
        active
            ? "bg-accent text-foreground"
            : "text-muted-foreground hover:bg-accent/60 hover:text-foreground",
    );
}

/**
 * Toolbar/slash affordance that inserts a workspace file as a canonical
 * `[Label](file:id)` mention chip — never a binary upload into the note body.
 */
export function FileReferencePopover({ editor, labels, open, onOpenChange }: Props) {
    const inputId = useId();
    const listId = useId();
    const [query, setQuery] = useState("");
    const [items, setItems] = useState<MentionItem[]>([]);
    const [active, setActive] = useState(0);
    const [status, setStatus] = useState<"idle" | "loading" | "ready" | "error">("idle");
    const [attempt, setAttempt] = useState(0);

    const handleOpenChange = (nextOpen: boolean) => {
        if (nextOpen) {
            setQuery("");
            setItems([]);
            setActive(0);
            setStatus("loading");
            setAttempt(0);
        }
        onOpenChange(nextOpen);
    };

    useEffect(() => {
        if (!open) return;
        const needle = query.trim();

        let cancelled = false;
        const handle = window.setTimeout(() => {
            void queryFileMentions(needle)
                .then((results) => {
                    if (cancelled) return;
                    setItems(results);
                    setActive(0);
                    setStatus("ready");
                })
                .catch(() => {
                    if (cancelled) return;
                    setItems([]);
                    setStatus("error");
                });
        }, needle ? 180 : 0);

        return () => {
            cancelled = true;
            window.clearTimeout(handle);
        };
    }, [open, query, attempt]);

    const updateQuery = (value: string) => {
        setQuery(value);
        setStatus("loading");
    };

    const selectItem = (item: MentionItem) => {
        insertMention(editor, { refType: "file", refId: item.id, label: item.label });
        handleOpenChange(false);
    };

    const handleSubmit = (event?: FormEvent) => {
        event?.preventDefault();
        const item = items[active];
        if (item) selectItem(item);
    };

    const activeOptionId = items[active] ? `${listId}-opt-${items[active].type}-${items[active].id}` : undefined;

    const statusLabel =
        status === "loading"
            ? labels.fileSearching
            : status === "error"
              ? labels.fileSearchError
              : items.length === 0 && status === "ready"
                ? labels.fileNoResults
                : labels.filePickerAria;

    return (
        <Popover open={open} onOpenChange={handleOpenChange}>
            <PopoverTrigger
                type="button"
                aria-label={labels.file}
                title={labels.file}
                onMouseDown={(event) => event.preventDefault()}
                className={toolbarButtonClass(open)}
            >
                <Paperclip className="size-4" />
            </PopoverTrigger>
            <PopoverContent align="start" className="w-80">
                <PopoverTitle className="text-sm font-semibold text-foreground">{labels.fileTitle}</PopoverTitle>
                <PopoverDescription className="mt-1 text-xs leading-relaxed text-muted-foreground">
                    {labels.fileDescription}
                </PopoverDescription>
                <form className="mt-4 grid gap-3" onSubmit={handleSubmit}>
                    <div className="grid gap-1.5">
                        <Label htmlFor={inputId}>{labels.fileSearchLabel}</Label>
                        <Input
                            id={inputId}
                            value={query}
                            onChange={(event) => updateQuery(event.target.value)}
                            placeholder={labels.fileSearchPlaceholder}
                            autoCapitalize="none"
                            autoCorrect="off"
                            spellCheck={false}
                            autoFocus
                            role="combobox"
                            aria-expanded={items.length > 0}
                            aria-controls={listId}
                            aria-autocomplete="list"
                            aria-activedescendant={activeOptionId}
                            onKeyDown={(event) => {
                                if (event.nativeEvent.isComposing || event.keyCode === 229) return;
                                if (!items.length) return;
                                if (event.key === "ArrowDown") {
                                    event.preventDefault();
                                    setActive((index) => (index + 1) % items.length);
                                    return;
                                }
                                if (event.key === "ArrowUp") {
                                    event.preventDefault();
                                    setActive((index) => (index - 1 + items.length) % items.length);
                                }
                            }}
                        />
                    </div>
                    <span className="sr-only" role="status" aria-live="polite" aria-atomic="true">
                        {statusLabel}
                    </span>
                    {status === "error" ? (
                        <Button
                            type="button"
                            variant="ghost"
                            size="sm"
                            className="justify-start"
                            onClick={() => setAttempt((value) => value + 1)}
                        >
                            <RotateCw className="size-4" />
                            {labels.fileRetry}
                        </Button>
                    ) : null}
                    {items.length > 0 ? (
                        <div
                            id={listId}
                            role="listbox"
                            aria-label={labels.filePickerAria}
                            className="max-h-56 overflow-y-auto rounded-lg ring-1 ring-border"
                        >
                            {items.map((item, index) => (
                                <button
                                    id={`${listId}-opt-${item.type}-${item.id}`}
                                    key={`${item.type}:${item.id}`}
                                    type="button"
                                    role="option"
                                    aria-selected={index === active}
                                    onMouseDown={(event) => {
                                        event.preventDefault();
                                        selectItem(item);
                                    }}
                                    onMouseEnter={() => setActive(index)}
                                    className={cn(
                                        "flex min-h-11 w-full items-center gap-2.5 px-2.5 py-2 text-left transition-colors",
                                        index === active ? "bg-accent" : "hover:bg-accent/60",
                                    )}
                                >
                                    <span className="flex size-8 shrink-0 items-center justify-center rounded-md bg-muted text-muted-foreground ring-1 ring-border">
                                        <PaperClipIcon className="size-3.5" />
                                    </span>
                                    <span className="min-w-0 flex-1">
                                        <span className="block truncate text-sm font-medium text-foreground">
                                            {item.label}
                                        </span>
                                        <span className="block truncate text-xs text-muted-foreground">
                                            {item.sublabel}
                                        </span>
                                    </span>
                                </button>
                            ))}
                        </div>
                    ) : null}
                </form>
            </PopoverContent>
        </Popover>
    );
}
