"use client";

import { useTranslations } from "next-intl";

import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { SettingsSection } from "@/app/components/settings/SettingsSection";

/**
 * Independently bounded frame for one diagnostics section. Each section owns its loading,
 * error, and empty presentation so a single failing area never blanks the rest of the page.
 * The reference id is surfaced verbatim when the backend supplied a correlation id.
 */
export function DiagnosticsSection({
    title,
    description,
    loading,
    error,
    referenceId,
    onRetry,
    isEmpty,
    emptyLabel,
    children,
}: {
    title: string;
    description?: string;
    loading: boolean;
    error: string | null;
    referenceId?: string | null;
    onRetry?: () => void;
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
            ) : error ? (
                <div className="rounded-lg border border-border bg-card p-4">
                    <p className="text-sm text-foreground">{error}</p>
                    {referenceId ? (
                        <p className="mt-1 font-mono text-xs text-muted-foreground">
                            {t("referenceId", { id: referenceId })}
                        </p>
                    ) : null}
                    {onRetry ? (
                        <Button
                            type="button"
                            variant="outline"
                            size="sm"
                            className="mt-3"
                            onClick={onRetry}
                        >
                            {t("retry")}
                        </Button>
                    ) : null}
                </div>
            ) : isEmpty ? (
                <div className="rounded-lg border border-dashed border-border bg-card/40 p-6 text-center">
                    <p className="text-sm text-muted-foreground">{emptyLabel ?? t("empty")}</p>
                </div>
            ) : (
                children
            )}
        </SettingsSection>
    );
}
