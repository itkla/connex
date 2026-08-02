"use client";

import { useCallback, useEffect, useState } from "react";
import { useTranslations } from "next-intl";

import type { TenantDiagnostics } from "@/app/lib/types";
import { ApiError, getOrgDiagnostics, getWorkspaceDiagnostics } from "@/app/lib/api";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import SectionBoundary from "@/app/components/SectionBoundary";
import { Button } from "@/components/ui/button";
import { JobRunsSection } from "./JobRunsSection";
import { MailDeliverabilitySection } from "./MailDeliverabilitySection";
import { ProfileCapabilitiesSection } from "./ProfileCapabilitiesSection";
import { ProviderReadinessSection } from "./ProviderReadinessSection";
import { SecretStoreSection } from "./SecretStoreSection";
import { StatusPill } from "./StatusPill";

/**
 * Which plane the panel reports on. Only the workspace scope offers the mail send test: that
 * endpoint is gated on the target workspace's own settings permission, so exposing it from the
 * organization view would imply a cross-workspace bypass that the backend does not grant.
 */
export type DiagnosticsScope = "workspace" | "organization";

/**
 * Aggregated tenant diagnostics.
 *
 * The endpoint is a single aggregate, so the fetch has one failure mode: if the request itself
 * fails the page shows one error with one retry, rather than repeating the same doomed retry in
 * every section. Partial degradation is carried in the payload instead — the backend guards each
 * source independently and returns that section empty rather than failing the report — and each
 * section is additionally wrapped in a render boundary so one broken widget cannot blank the rest.
 * A failed retry never discards the last good payload.
 */
export default function DiagnosticsPanel({ scope }: { scope: DiagnosticsScope }) {
    const t = useTranslations("TenantDiagnostics");
    const { activeWorkspace } = useWorkspace();
    const [data, setData] = useState<TenantDiagnostics | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [referenceId, setReferenceId] = useState<string | null>(null);
    const [reloadToken, setReloadToken] = useState(0);

    const workspaceId = activeWorkspace?.id ?? null;
    const orgId = activeWorkspace?.orgId ?? null;
    const scopeId = scope === "workspace" ? workspaceId : orgId;

    useEffect(() => {
        if (scopeId === null) return;
        let cancelled = false;
        (async () => {
            setLoading(true);
            setError(null);
            setReferenceId(null);
            try {
                const next =
                    scope === "workspace"
                        ? await getWorkspaceDiagnostics(scopeId)
                        : await getOrgDiagnostics(scopeId);
                if (cancelled) return;
                setData(next);
            } catch (caught) {
                if (cancelled) return;
                if (caught instanceof ApiError) {
                    setError(caught.message);
                    setReferenceId(caught.correlationId ?? null);
                } else {
                    setError(t("loadFailed"));
                }
            } finally {
                if (!cancelled) setLoading(false);
            }
        })();
        return () => {
            cancelled = true;
        };
    }, [scope, scopeId, reloadToken, t]);

    const retry = useCallback(() => {
        setReloadToken((token) => token + 1);
    }, []);

    const findings = data?.findings ?? [];

    return (
        <div className="space-y-10">
            {findings.length > 0 ? (
                <div className="flex flex-wrap gap-2">
                    {findings.map((finding) => (
                        <StatusPill
                            key={`${finding.code}:${finding.workspaceId ?? ""}:${finding.channel ?? ""}`}
                            tone={finding.severity === "warning" ? "warn" : "neutral"}
                            label={t(`finding.${finding.code}`)}
                        />
                    ))}
                </div>
            ) : null}

            {error && !data ? (
                <div className="rounded-lg border border-border bg-card p-4">
                    <p className="text-sm text-foreground">{error}</p>
                    {referenceId ? (
                        <p className="mt-1 font-mono text-xs text-muted-foreground">
                            {t("referenceId", { id: referenceId })}
                        </p>
                    ) : null}
                    <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        className="mt-3"
                        onClick={retry}
                    >
                        {t("retry")}
                    </Button>
                </div>
            ) : (
                <>
                    {error ? (
                        <div className="rounded-lg border border-border bg-card px-4 py-3">
                            <p className="text-sm text-muted-foreground">{t("staleAfterRefresh")}</p>
                        </div>
                    ) : null}
                    <SectionBoundary resetKey={scopeId}>
                        <ProfileCapabilitiesSection data={data} loading={loading} error={null} />
                    </SectionBoundary>
                    <SectionBoundary resetKey={scopeId}>
                        <ProviderReadinessSection data={data} loading={loading} error={null} />
                    </SectionBoundary>
                    <SectionBoundary resetKey={scopeId}>
                        <JobRunsSection data={data} loading={loading} error={null} />
                    </SectionBoundary>
                    <SectionBoundary resetKey={scopeId}>
                        <MailDeliverabilitySection
                            workspaceId={scope === "workspace" ? workspaceId : null}
                        />
                    </SectionBoundary>
                    <SectionBoundary resetKey={scopeId}>
                        <SecretStoreSection data={data} loading={loading} error={null} />
                    </SectionBoundary>
                </>
            )}
        </div>
    );
}
