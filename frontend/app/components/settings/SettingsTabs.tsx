"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useTranslations } from "next-intl";

import { cn } from "@/lib/utils";

const TABS = [
    { key: "tabMembers", href: "/settings/members" },
    { key: "tabRoles", href: "/settings/roles" },
] as const;

export default function SettingsTabs() {
    const pathname = usePathname() ?? "";
    const t = useTranslations("WorkspaceSettings");

    return (
        <nav className="flex gap-1 border-b border-border" aria-label={t("title")}>
            {TABS.map((tab) => {
                const active = pathname === tab.href || pathname.startsWith(`${tab.href}/`);
                return (
                    <Link
                        key={tab.href}
                        href={tab.href}
                        aria-current={active ? "page" : undefined}
                        className={cn(
                            "relative -mb-px rounded-t-md px-3 py-2 text-sm font-medium transition",
                            active ? "text-foreground" : "text-muted-foreground hover:text-foreground",
                        )}
                    >
                        {t(tab.key)}
                        {active && (
                            <span className="absolute inset-x-2 -bottom-px h-0.5 rounded-full bg-brand" />
                        )}
                    </Link>
                );
            })}
        </nav>
    );
}
