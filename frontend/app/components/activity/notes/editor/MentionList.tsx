"use client";

import { forwardRef, useEffect, useImperativeHandle, useState } from "react";
import {
    BriefcaseIcon,
    BuildingOffice2Icon,
    DocumentTextIcon,
    PaperClipIcon,
    UserIcon,
} from "@heroicons/react/24/outline";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import type { MentionItem, MentionType } from "./mentionData";
import type { SuggestionListHandle } from "./suggestionRenderer";

const ICON: Record<MentionType, typeof UserIcon> = {
    user: UserIcon,
    person: UserIcon,
    deal: BriefcaseIcon,
    company: BuildingOffice2Icon,
    note: DocumentTextIcon,
    file: PaperClipIcon,
};

type Props = { items: MentionItem[]; command: (item: MentionItem) => void };

export const MentionList = forwardRef<SuggestionListHandle, Props>(function MentionList(
    { items, command },
    ref,
) {
    const [active, setActive] = useState(0);

    useEffect(() => setActive(0), [items]);

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
                const Icon = ICON[item.type];
                return (
                    <button
                        key={`${item.type}:${item.id}`}
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
                        <Avatar size="sm" className="ring-1 ring-border">
                            {item.avatarUrl ? <AvatarImage src={item.avatarUrl} alt="" /> : null}
                            <AvatarFallback>
                                <Icon className="h-3.5 w-3.5 text-muted-foreground" />
                            </AvatarFallback>
                        </Avatar>
                        <span className="min-w-0 flex-1">
                            <span className="block truncate text-sm font-medium text-foreground">
                                {item.label}
                            </span>
                            <span className="block truncate text-xs text-muted-foreground">
                                {item.sublabel}
                            </span>
                        </span>
                    </button>
                );
            })}
        </div>
    );
});
