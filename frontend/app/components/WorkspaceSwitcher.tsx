"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { cn } from "@/lib/utils";
import { ChevronUpDownIcon, PlusIcon } from "@heroicons/react/24/outline";
import { CheckIcon } from "@heroicons/react/20/solid";

import {
    DropdownMenu,
    DropdownMenuTrigger,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuLabel,
    DropdownMenuSeparator,
} from "@/components/ui/dropdown-menu";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import NewWorkspaceDialog from "@/app/components/NewWorkspaceDialog";

function Glyph({ name, size = "trigger" }: { name: string; size?: "trigger" | "item" }) {
    const trigger = size === "trigger";
    return (
        <span
            aria-hidden="true"
            className={
                trigger
                    ? "flex aspect-square size-8 shrink-0 items-center justify-center rounded-lg bg-brand text-sm font-semibold leading-none text-brand-foreground"
                    : "flex aspect-square size-6 shrink-0 items-center justify-center rounded-md border text-xs font-semibold leading-none"
            }
        >
            {name.trim().charAt(0).toUpperCase() || "?"}
        </span>
    );
}

export default function WorkspaceSwitcher({ compact = false }: { compact?: boolean }) {
    const t = useTranslations("WorkspaceSwitcher");
    const { workspaces, activeWorkspaceId, activeWorkspace, switching, switchTo } = useWorkspace();

    const [dialogOpen, setDialogOpen] = useState(false);

    const count =
        workspaces.length === 1
            ? t("oneWorkspace")
            : t("manyWorkspaces", { count: workspaces.length });

    return (
        <>
            <DropdownMenu>
                <DropdownMenuTrigger
                    aria-label={t("switchAria")}
                    disabled={switching}
                    className={cn(
                        "group flex items-center rounded-md outline-none transition-colors hover:bg-sidebar-accent hover:text-sidebar-accent-foreground focus-visible:ring-2 focus-visible:ring-brand disabled:opacity-60 data-[state=open]:bg-sidebar-accent data-[state=open]:text-sidebar-accent-foreground",
                        compact ? "justify-center p-1" : "h-12 min-w-0 flex-1 gap-2 overflow-hidden p-2 text-left text-sm",
                    )}
                >
                    <Glyph name={activeWorkspace?.name ?? "?"} />
                    {!compact && (
                        <>
                            <span className="grid flex-1 text-left leading-tight">
                                <span className="truncate font-medium text-sidebar-foreground">
                                    {activeWorkspace?.name ?? t("label")}
                                </span>
                                <span className="truncate text-xs text-muted-foreground">{count}</span>
                            </span>
                            <ChevronUpDownIcon className="ml-auto size-4 shrink-0 text-muted-foreground" />
                        </>
                    )}
                </DropdownMenuTrigger>

                <DropdownMenuContent
                    align="start"
                    side="bottom"
                    sideOffset={4}
                    className="w-[var(--radix-dropdown-menu-trigger-width)] min-w-56 rounded-lg"
                >
                    <DropdownMenuLabel className="text-xs text-muted-foreground">
                        {t("label")}
                    </DropdownMenuLabel>
                    {workspaces.map((workspace) => {
                        const active = workspace.id === activeWorkspaceId;
                        return (
                            <DropdownMenuItem
                                key={workspace.id}
                                onSelect={() => void switchTo(workspace.id)}
                                className="gap-2 p-2"
                            >
                                <Glyph name={workspace.name} size="item" />
                                <span
                                    className={`min-w-0 flex-1 truncate ${active ? "font-medium" : ""}`}
                                >
                                    {workspace.name}
                                </span>
                                {active && <CheckIcon className="size-4 shrink-0 text-brand" />}
                            </DropdownMenuItem>
                        );
                    })}
                    <DropdownMenuSeparator />
                    <DropdownMenuItem
                        onSelect={() => requestAnimationFrame(() => setDialogOpen(true))}
                        className="gap-2 p-2"
                    >
                        <span
                            aria-hidden="true"
                            className="flex size-6 items-center justify-center rounded-md border bg-transparent text-muted-foreground"
                        >
                            <PlusIcon className="size-4" />
                        </span>
                        <span className="font-medium text-muted-foreground">{t("createOrJoin")}</span>
                    </DropdownMenuItem>
                </DropdownMenuContent>
            </DropdownMenu>

            <NewWorkspaceDialog open={dialogOpen} onOpenChange={setDialogOpen} />
        </>
    );
}
