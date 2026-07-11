"use client";

import { useTranslations } from "next-intl";
import { BoltIcon } from "@heroicons/react/16/solid";
import { Loader2Icon } from "lucide-react";

import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuGroup,
    DropdownMenuItem,
    DropdownMenuLabel,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { ACTION_GROUPS } from "@/app/lib/actions/types";
import { useActions, useAvailableActions } from "@/app/hooks/useActions";

/**
 * A global launcher that renders the available registry actions grouped by kind and runs them through
 * the shared {@link useActions} contract. It is a first, minimal consumer that proves one action
 * definition drives multiple surfaces; Quick Create (#403) replaces this presentation while reusing
 * the same registry underneath.
 */
export default function GlobalActionsMenu() {
    const t = useTranslations("Actions");
    const { run, pendingIds } = useActions();
    const available = useAvailableActions();

    const sections = ACTION_GROUPS.map((group) => ({
        group,
        items: available.filter((action) => action.group === group),
    })).filter((section) => section.items.length > 0);

    return (
        <div className="mb-5 shrink-0">
            <DropdownMenu>
                <DropdownMenuTrigger asChild>
                    <button
                        type="button"
                        aria-label={t("launcher.aria")}
                        className="inline-flex w-full items-center gap-2 rounded-lg bg-brand px-3 py-2 text-sm font-semibold text-neutral-950 transition-[transform,background-color] duration-150 ease-out hover:bg-brand-hover active:scale-[0.99] motion-reduce:transition-none motion-reduce:active:scale-100"
                    >
                        <BoltIcon className="size-4" />
                        {t("launcher.label")}
                    </button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="start" className="w-64">
                    {sections.map((section, index) => (
                        <DropdownMenuGroup key={section.group}>
                            {index > 0 ? <DropdownMenuSeparator /> : null}
                            <DropdownMenuLabel>{t(`group.${section.group}`)}</DropdownMenuLabel>
                            {section.items.map((action) => {
                                const Icon = action.icon;
                                const pending = pendingIds.has(action.id);
                                return (
                                    <DropdownMenuItem
                                        key={action.id}
                                        disabled={pending}
                                        onSelect={(event) => {
                                            event.preventDefault();
                                            void run(action.id, { source: "menu" });
                                        }}
                                    >
                                        {pending ? (
                                            <Loader2Icon className="size-4 animate-spin text-muted-foreground" />
                                        ) : Icon ? (
                                            <Icon className="size-4 text-muted-foreground" />
                                        ) : null}
                                        <span>{t(action.labelKey)}</span>
                                    </DropdownMenuItem>
                                );
                            })}
                        </DropdownMenuGroup>
                    ))}
                </DropdownMenuContent>
            </DropdownMenu>
        </div>
    );
}
