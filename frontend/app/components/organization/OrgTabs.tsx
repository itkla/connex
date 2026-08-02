"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useTranslations } from "next-intl";
import { motion, useReducedMotion } from "motion/react";

import { cn } from "@/lib/utils";

const TABS = [
    { key: "tabMembers", href: "/organization/members" },
    { key: "tabDomains", href: "/organization/allowed-domains" },
    { key: "tabSso", href: "/organization/sso" },
    { key: "tabAi", href: "/organization/ai" },
    { key: "tabDataRequests", href: "/organization/data-requests" },
    { key: "tabAudit", href: "/organization/audit" },
    { key: "tabDiagnostics", href: "/organization/diagnostics" },
] as const;

export default function OrgTabs({ ssoEnabled = false }: { ssoEnabled?: boolean }) {
    const pathname = usePathname() ?? "";
    const t = useTranslations("Organization");
    const reduce = useReducedMotion() ?? false;
    const tabs = TABS.filter((tab) => tab.key !== "tabSso" || ssoEnabled);

    return (
        <nav className="flex gap-1 border-b border-border" aria-label={t("title")}>
            {tabs.map((tab) => {
                const active = pathname === tab.href || pathname.startsWith(`${tab.href}/`);
                return (
                    <Link
                        key={tab.href}
                        href={tab.href}
                        aria-current={active ? "page" : undefined}
                        className={cn(
                            "relative -mb-px rounded-t-md px-3 py-2 text-sm font-medium outline-none transition-colors focus-visible:ring-2 focus-visible:ring-ring/50",
                            active ? "text-foreground" : "text-muted-foreground hover:text-foreground",
                        )}
                    >
                        {t(tab.key)}
                        {active && (
                            <motion.span
                                layoutId="org-tab-underline"
                                aria-hidden
                                className="absolute inset-x-2 -bottom-px h-0.5 rounded-full bg-brand"
                                transition={
                                    reduce ? { duration: 0 } : { type: "spring", stiffness: 520, damping: 42 }
                                }
                            />
                        )}
                    </Link>
                );
            })}
        </nav>
    );
}
