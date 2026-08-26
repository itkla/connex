"use client";

import { ExclamationTriangleIcon, InboxIcon } from "@heroicons/react/24/outline";
import { useTranslations } from "next-intl";

import { EmptyState } from "@/app/components/EmptyState";
import { Skeleton } from "@/components/ui/skeleton";
import { SettingsSection } from "@/app/components/settings/SettingsSection";

/**
 * Frame for one diagnostics section.
 *
 * A section whose backend source failed renders as explicitly unavailable rather than as an
 * ordinary empty result. That distinction is the whole point on this page: an aggregation fault
 * and a genuinely idle instance both produce an empty list, and presenting the former as
 * "nothing recorded yet" would tell an operator the opposite of the truth.
 */
export function DiagnosticsSection({
    title,
    description,
    loading = false,
    unavailable,
    isEmpty,
    emptyLabel,
    children,
}: {
    title: string;
    description?: string;
    loading?: boolean;
    unavailable?: boolean;
    isEmpty?: boolean;
    emptyLabel?: string;
    children: React.ReactNode;
}) {
    const t = useTranslations("TenantDiagnostics");

    return (
        <SettingsSection title={title} description={description}>
            {loading ? (
                <div className="space-y-2" aria-busy="true" aria-live="polite">
                    <Skeleton className="h-9 w-full rounded-lg" />
                    <Skeleton className="h-9 w-4/5 rounded-lg" />
                    <Skeleton className="h-9 w-2/3 rounded-lg" />
                </div>
            ) : unavailable ? (
                <EmptyState
                    icon={ExclamationTriangleIcon}
                    tone="muted"
                    title={t("sectionUnavailable")}
                    body={t("sectionUnavailableBody")}
                />
            ) : isEmpty ? (
                <EmptyState icon={InboxIcon} tone="muted" title={emptyLabel ?? t("empty")} />
            ) : (
                children
            )}
        </SettingsSection>
    );
}
