"use client";

import { useCallback, useEffect, useState } from "react";
import { useTranslations } from "next-intl";

import type { TenantDiagnostics } from "@/app/lib/types";
import { ApiError, getOrgDiagnostics, getWorkspaceDiagnostics } from "@/app/lib/api";
import { useWorkspace } from "@/app/hooks/useWorkspace";
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
 * Aggregated tenant diagnostics rendered as independently bounded sections. One request feeds
 * every section, but each one presents its own loading, error, and retry affordance so a partial
 * failure degrades a single area instead of the page.
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
                setData(null);
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

            <ProfileCapabilitiesSection
                data={data}
                loading={loading}
                error={error}
                referenceId={referenceId}
                onRetry={retry}
            />
            <ProviderReadinessSection
                data={data}
                loading={loading}
                error={error}
                referenceId={referenceId}
                onRetry={retry}
            />
            <JobRunsSection
                data={data}
                loading={loading}
                error={error}
                referenceId={referenceId}
                onRetry={retry}
            />
            <MailDeliverabilitySection
                workspaceId={scope === "workspace" ? workspaceId : null}
            />
            <SecretStoreSection
                data={data}
                loading={loading}
                error={error}
                referenceId={referenceId}
                onRetry={retry}
            />
        </div>
    );
}
