"use client";

import { forwardRef, useEffect, useImperativeHandle, useRef, useState } from "react";
import type { SlashCommandItem } from "./slashCommands";
import type { SuggestionListHandle } from "./suggestionRenderer";

type Props = { items: SlashCommandItem[]; command: (item: SlashCommandItem) => void };

/**
 * Notion-style `/` command palette list. Mirrors {@link MentionList}: the
 * editor forwards key events through {@link SuggestionListHandle} while the mouse
 * can hover/click rows. Renders nothing when the query matches no command.
 */
export const SlashCommandList = forwardRef<SuggestionListHandle, Props>(function SlashCommandList(
    { items, command },
    ref,
) {
    const [active, setActive] = useState(0);
    const activeRef = useRef<HTMLButtonElement>(null);

    useEffect(() => setActive(0), [items]);

    useEffect(() => {
        activeRef.current?.scrollIntoView({ block: "nearest" });
    }, [active]);

    useImperativeHandle(
        ref,
        () => ({
            onKeyDown: ({ event }) => {
                if (!items.length) return false;
                if (event.key === "ArrowDown" || (event.key === "Tab" && !event.shiftKey)) {
                    setActive((index) => (index + 1) % items.length);
                    return true;
                }
                if (event.key === "ArrowUp" || (event.key === "Tab" && event.shiftKey)) {
                    setActive((index) => (index - 1 + items.length) % items.length);
                    return true;
                }
                if (event.key === "Enter") {
                    command(items[active]);
                    return true;
                }
                return false;
            },
        }),
        [items, active, command],
    );

    if (!items.length) return null;

    return (
        <div
            role="listbox"
            className="max-h-72 w-72 overflow-y-auto rounded-2xl bg-popover p-1 text-popover-foreground shadow-lg ring-1 ring-border"
        >
            {items.map((item, index) => {
                const Icon = item.icon;
                return (
                    <button
                        key={item.id}
                        ref={index === active ? activeRef : undefined}
                        type="button"
                        role="option"
                        aria-selected={index === active}
                        onMouseDown={(event) => {
                            event.preventDefault();
                            command(item);
                        }}
                        onMouseEnter={() => setActive(index)}
                        className={`flex w-full items-center gap-2.5 rounded-lg px-2 py-1.5 text-left transition-colors ${
                            index === active ? "bg-accent" : "hover:bg-accent/60"
                        }`}
                    >
                        <span className="flex size-8 shrink-0 items-center justify-center rounded-md bg-muted text-muted-foreground ring-1 ring-border">
                            <Icon className="size-4" />
                        </span>
                        <span className="min-w-0 flex-1">
                            <span className="block truncate text-sm font-medium text-foreground">
                                {item.title}
                            </span>
                            <span className="block truncate text-xs text-muted-foreground">
                                {item.subtitle}
                            </span>
                        </span>
                    </button>
                );
            })}
        </div>
    );
});
