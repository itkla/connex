"use client";

import { useTranslations } from "next-intl";

import type { TenantDiagnostics } from "@/app/lib/types";
import { DiagnosticsSection } from "./DiagnosticsSection";
import { StatusPill, type DiagnosticTone } from "./StatusPill";

function capabilityTone(profileAllowed: boolean, available: boolean): DiagnosticTone {
    if (!profileAllowed) return "neutral";
    return available ? "ok" : "warn";
}

/**
 * Deployment profile and the effective capability matrix. A capability forbidden by the
 * deployment profile reads as unavailable-by-edition rather than as a misconfiguration, which
 * is the distinction operators most often need when triaging a missing feature.
 */
export function ProfileCapabilitiesSection({
    data,
    loading,
    error,
    referenceId,
    onRetry,
}: {
    data: TenantDiagnostics | null;
    loading: boolean;
    error: string | null;
    referenceId?: string | null;
    onRetry?: () => void;
}) {
    const t = useTranslations("TenantDiagnostics");
    const deployment = data?.deployment ?? null;
    const capabilities = deployment?.capabilities ?? [];

    return (
        <DiagnosticsSection
            title={t("profileTitle")}
            description={t("profileDescription")}
            loading={loading}
            error={error}
            referenceId={referenceId}
            onRetry={onRetry}
            isEmpty={!loading && !error && capabilities.length === 0}
            emptyLabel={t("profileEmpty")}
        >
            <div className="space-y-4">
                <div className="flex flex-wrap items-center gap-2 rounded-lg border border-border bg-card px-4 py-3">
                    <span className="text-sm text-muted-foreground">{t("profileLabel")}</span>
                    <span className="font-mono text-sm font-medium text-foreground">
                        {deployment?.profile ?? t("profileUnset")}
                    </span>
                    {deployment && !deployment.configured ? (
                        <StatusPill tone="warn" label={t("profileUnconfigured")} />
                    ) : null}
                </div>
                <ul className="divide-y divide-border rounded-lg border border-border bg-card">
                    {capabilities.map((capability) => (
                        <li
                            key={capability.capability}
                            className="flex flex-wrap items-center justify-between gap-2 px-4 py-2.5"
                        >
                            <span className="font-mono text-xs text-foreground">
                                {capability.capability}
                            </span>
                            <StatusPill
                                tone={capabilityTone(capability.profileAllowed, capability.available)}
                                label={
                                    !capability.profileAllowed
                                        ? t("capabilityForbidden")
                                        : capability.available
                                          ? t("capabilityAvailable")
                                          : t("capabilityUnavailable")
                                }
                            />
                        </li>
                    ))}
                </ul>
            </div>
        </DiagnosticsSection>
    );
}
