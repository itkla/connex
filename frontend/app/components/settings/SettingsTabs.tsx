"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useTranslations } from "next-intl";
import { motion, useReducedMotion } from "motion/react";

import { usePermission } from "@/app/hooks/usePermissions";
import { cn } from "@/lib/utils";

const TABS = [
    { key: "tabMembers", href: "/settings/members" },
    { key: "tabRoles", href: "/settings/roles" },
    { key: "tabCustomFields", href: "/settings/custom-fields" },
    { key: "tabData", href: "/settings/data" },
    { key: "tabEmail", href: "/settings/email" },
    { key: "tabDelivery", href: "/settings/delivery" },
    { key: "tabDiagnostics", href: "/settings/diagnostics" },
] as const;

/**
 * The workspace settings tab strip.
 *
 * Diagnostics is gated on `WORKSPACE_SETTINGS`, the permission its endpoints enforce, so a member
 * without it is not offered a tab that can only answer with a refusal. The gate reads the shell's
 * server-resolved effective permissions, which are fail-closed, and the panel keeps its own denial
 * state for the case where the permission is lost while the page is open.
 *
 * @param mailManaged - whether the instance owns mail delivery, which retires the Email tab
 */
export default function SettingsTabs({ mailManaged = false }: { mailManaged?: boolean }) {
    const pathname = usePathname() ?? "";
    const t = useTranslations("WorkspaceSettings");
    const reduce = useReducedMotion() ?? false;
    const canManageSettings = usePermission("WORKSPACE_SETTINGS");
    const tabs = TABS.filter((tab) => {
        if (tab.key === "tabEmail") return !mailManaged;
        if (tab.key === "tabDiagnostics") return canManageSettings;
        return true;
    });

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
                            <span className="relative z-10">{t(tab.key)}</span>
                        </Link>
                    );
                })}
            </div>
        </nav>
    );
}
