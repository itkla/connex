"use client";

import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
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

function Glyph({ name }: { name: string }) {
    return (
        <span
            aria-hidden="true"
            className="flex size-7 shrink-0 items-center justify-center rounded-lg bg-brand-light text-sm font-semibold text-brand-dark"
        >
            {name.trim().charAt(0).toUpperCase() || "?"}
        </span>
    );
}

export default function WorkspaceSwitcher() {
    const t = useTranslations("WorkspaceSwitcher");
    const router = useRouter();
    const { workspaces, activeWorkspaceId, activeWorkspace, switching, switchTo } = useWorkspace();

    const count =
        workspaces.length === 1
            ? t("oneWorkspace")
            : t("manyWorkspaces", { count: workspaces.length });

    return (
        <DropdownMenu>
            <DropdownMenuTrigger
                aria-label={t("switchAria")}
                disabled={switching}
                className="group flex min-w-0 flex-1 items-center gap-2.5 rounded-xl px-2 py-1.5 text-left transition-colors hover:bg-neutral-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand disabled:opacity-60"
            >
                <Glyph name={activeWorkspace?.name ?? "?"} />
                <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm font-semibold tracking-tight text-neutral-900">
                        {activeWorkspace?.name ?? t("label")}
                    </span>
                    <span className="block truncate text-xs text-neutral-400">{count}</span>
                </span>
                <ChevronUpDownIcon className="size-4 shrink-0 text-neutral-400 transition-colors group-hover:text-neutral-500" />
            </DropdownMenuTrigger>

            <DropdownMenuContent align="start" sideOffset={8} className="w-64 rounded-2xl">
                <DropdownMenuLabel className="px-2 text-xs font-medium text-neutral-400">
                    {t("label")}
                </DropdownMenuLabel>
                {workspaces.map((workspace) => {
                    const active = workspace.id === activeWorkspaceId;
                    return (
                        <DropdownMenuItem
                            key={workspace.id}
                            onSelect={() => void switchTo(workspace.id)}
                            className="flex cursor-pointer items-center gap-2.5 rounded-xl px-2 py-1.5 focus:bg-brand-light/50"
                        >
                            <Glyph name={workspace.name} />
                            <span
                                className={`min-w-0 flex-1 truncate text-sm ${active ? "font-semibold text-brand-dark" : "text-neutral-700"}`}
                            >
                                {workspace.name}
                            </span>
                            {active && <CheckIcon className="size-4 shrink-0 text-brand" />}
                        </DropdownMenuItem>
                    );
                })}
                <DropdownMenuSeparator />
                <DropdownMenuItem
                    onSelect={() => router.push("/onboarding")}
                    className="flex cursor-pointer items-center gap-2.5 rounded-xl px-2 py-1.5 text-sm text-neutral-700 focus:bg-neutral-100"
                >
                    <span
                        aria-hidden="true"
                        className="flex size-7 items-center justify-center rounded-lg bg-neutral-100 text-neutral-500"
                    >
                        <PlusIcon className="size-4" />
                    </span>
                    {t("createOrJoin")}
                </DropdownMenuItem>
            </DropdownMenuContent>
        </DropdownMenu>
    );
}
