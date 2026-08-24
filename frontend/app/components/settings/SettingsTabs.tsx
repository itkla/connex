"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useTranslations } from "next-intl";
import { motion, useReducedMotion } from "motion/react";

import { usePermission } from "@/app/hooks/usePermissions";
import { cn } from "@/lib/utils";

const TABS = [
    { key: "tabGeneral", href: "/settings/general" },
    { key: "tabData", href: "/settings/data" },
] as const;

/**
 * The workspace settings tab strip, on its last two destinations (#1340 WS4.6).
 *
 * Seven of the nine tabs this strip carried are gone: their routes now forward to the canonical
 * scope-group destinations that absorbed them, and a strip linking addresses that redirect is a
 * navigation surface pointing at its own past. General and Data & privacy stay because they still
 * render — the canonical `/settings/workspace/general` and `/settings/workspace/data-privacy`
 * were never built — and two pages that serve need chrome and a way back to each other.
 *
 * This is a held remnant, not a design. What retires it is those two groups shipping, at which
 * point the strip, {@link WorkspaceSettingsChrome}, and the layout that renders them go together.
 *
 * General is gated on `WORKSPACE_SETTINGS`, the permission its endpoints enforce, so a member
 * without it is not offered a tab that can only answer with a refusal. The gate reads the shell's
 * server-resolved effective permissions, which are fail-closed, and the panel keeps its own denial
 * state for the case where the permission is lost while the page is open.
 */
export default function SettingsTabs() {
    const pathname = usePathname() ?? "";
    const t = useTranslations("WorkspaceSettings");
    const reduce = useReducedMotion() ?? false;
    const canManageSettings = usePermission("WORKSPACE_SETTINGS");
    const tabs = TABS.filter((tab) => (tab.key === "tabGeneral" ? canManageSettings : true));

    return (
        <nav aria-label={t("title")} className="-mx-1 overflow-x-auto px-1 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
            <div className="inline-flex w-max items-center gap-0.5 rounded-full bg-muted p-0.5 ring-1 ring-border/60">
                {tabs.map((tab) => {
                    const active = pathname === tab.href || pathname.startsWith(`${tab.href}/`);
                    return (
                        <Link
                            key={tab.href}
                            href={tab.href}
                            aria-current={active ? "page" : undefined}
                            className={cn(
                                "relative inline-flex h-8 items-center justify-center rounded-full px-3.5 text-sm font-medium whitespace-nowrap outline-none transition-[color,transform] active:scale-[0.97] focus-visible:ring-2 focus-visible:ring-brand/40",
                                active ? "text-foreground" : "text-muted-foreground hover:text-foreground",
                            )}
                        >
                            {active && (
                                <motion.span
                                    layoutId="settings-tab-pill"
                                    aria-hidden
                                    className="absolute inset-0 rounded-full bg-background shadow-sm ring-1 ring-border/60"
                                    transition={
                                        reduce ? { duration: 0 } : { type: "spring", stiffness: 520, damping: 42 }
                                    }
                                />
                            )}
                            <span className="relative z-10 inline-flex items-center gap-1.5">
                                {t(tab.key)}
                            </span>
                        </Link>
                    );
                })}
            </div>
        </nav>
    );
}
