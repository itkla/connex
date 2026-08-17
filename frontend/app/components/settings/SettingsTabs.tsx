"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useTranslations } from "next-intl";
import { motion, useReducedMotion } from "motion/react";
import { QuestionMarkCircleIcon } from "@heroicons/react/24/outline";

import { usePermission } from "@/app/hooks/usePermissions";
import type { CapabilityAvailability } from "@/app/lib/capabilityAvailability";
import { cn } from "@/lib/utils";

const TABS = [
    { key: "tabGeneral", href: "/settings/general" },
    { key: "tabMembers", href: "/settings/members" },
    { key: "tabRoles", href: "/settings/roles" },
    { key: "tabCustomFields", href: "/settings/custom-fields" },
    { key: "tabQualification", href: "/settings/qualification" },
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
 * @param mailManagementAvailability whether managed mail is enabled, disabled, or unresolved
 */
export default function SettingsTabs({
    mailManagementAvailability = "disabled",
}: {
    mailManagementAvailability?: CapabilityAvailability;
}) {
    const pathname = usePathname() ?? "";
    const t = useTranslations("WorkspaceSettings");
    const tCapability = useTranslations("CapabilityUnavailable");
    const reduce = useReducedMotion() ?? false;
    const canManageSettings = usePermission("WORKSPACE_SETTINGS");
    const tabs = TABS.filter((tab) => {
        if (tab.key === "tabEmail") return mailManagementAvailability !== "enabled";
        if (tab.key === "tabGeneral") return canManageSettings;
        if (tab.key === "tabDiagnostics") return canManageSettings;
        return true;
    });

    return (
        <nav aria-label={t("title")} className="-mx-1 overflow-x-auto px-1 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
            <div className="inline-flex w-max items-center gap-0.5 rounded-full bg-muted p-0.5 ring-1 ring-border/60">
                {tabs.map((tab) => {
                    const active = pathname === tab.href || pathname.startsWith(`${tab.href}/`);
                    const availabilityUnavailable = tab.key === "tabEmail"
                        && mailManagementAvailability === "unavailable";
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
                                {availabilityUnavailable ? (
                                    <span
                                        className="inline-flex text-muted-foreground"
                                        title={tCapability("title")}
                                    >
                                        <QuestionMarkCircleIcon aria-hidden className="size-4" />
                                        <span className="sr-only">{tCapability("title")}</span>
                                    </span>
                                ) : null}
                            </span>
                        </Link>
                    );
                })}
            </div>
        </nav>
    );
}
