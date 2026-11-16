"use client";

import { useTranslations } from "next-intl";

import AccessDenied from "@/app/components/AccessDenied";

export { rowActionTrigger } from "@/app/components/settings/SettingsListPrimitives";

export function ListCard({ children }: { children: React.ReactNode }) {
    return (
        <ul className="divide-y divide-border overflow-hidden rounded-2xl border border-border bg-card">{children}</ul>
    );
}

export function EmptyRow({ children }: { children: React.ReactNode }) {
    return (
        <p className="rounded-2xl border border-border bg-card px-6 py-8 text-center text-sm text-muted-foreground">
            {children}
        </p>
    );
}

/** Shown when the backend refuses (403): the user is not an org administrator. */
export function NoAccessCard() {
    const t = useTranslations("Organization");
    return <AccessDenied variant="inline" title={t("noAccessTitle")} body={t("noAccessBody")} />;
}
