"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useTranslations } from "next-intl";

import type { TenantDiagnostics } from "@/app/lib/types";
import { ApiError, getOrgDiagnostics, getWorkspaceDiagnostics } from "@/app/lib/api";
import { useWorkspace } from "@/app/hooks/useWorkspace";
import AccessDenied from "@/app/components/AccessDenied";
import SectionBoundary from "@/app/components/SectionBoundary";
import { NoAccessCard } from "@/app/components/organization/OrgPrimitives";
import { Button } from "@/components/ui/button";
import { JobRunsSection } from "./JobRunsSection";
import { MailDeliverabilitySection } from "./MailDeliverabilitySection";
import { ProfileCapabilitiesSection } from "./ProfileCapabilitiesSection";
import { ProviderReadinessSection } from "./ProviderReadinessSection";
import { SecretStoreSection } from "./SecretStoreSection";
import DiagnosticsPanelSkeleton from "./DiagnosticsPanelSkeleton";
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
 * every section. A refusal is not one of those failures. The caller lacks the permission the
 * endpoint requires, so the request is forbidden however many times it is sent and only a
 * permission grant changes that; a 403 therefore replaces the panel with the shared inline denial
 * instead of offering a retry that cannot succeed, in the same grammar the other settings and
 * organization tabs use. Partial degradation is carried in the payload — the backend guards each
 * source independently and names the ones that failed in `unavailableSections`, so a degraded
 * section is never presented as a healthy empty one — and each section is wrapped in a render
 * boundary keyed on the scope and reload count, so a tripped boundary recovers on the next
 * refresh. Refresh stays available on every state that renders the panel, and a failed refresh
 * keeps the last good payload behind a stale banner.
 *
 * The mail section owns its own endpoint and state, so it stays mounted even when the aggregate
 * fails: a broken aggregate is exactly when an administrator needs the send test. A refusal is the
 * one exception — the send test is gated on the same permission as the aggregate, so it leaves
 * with the rest of the panel rather than offering a member an action they cannot take.
 */
export default function DiagnosticsPanel({ scope }: { scope: DiagnosticsScope }) {
    const t = useTranslations("TenantDiagnostics");
    const { activeWorkspace } = useWorkspace();
    const [data, setData] = useState<TenantDiagnostics | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [referenceId, setReferenceId] = useState<string | null>(null);
    const [accessDenied, setAccessDenied] = useState(false);
    const [reloadToken, setReloadToken] = useState(0);

    const loadedScopeIdRef = useRef<number | null>(null);

    const workspaceId = activeWorkspace?.id ?? null;
    const orgId = activeWorkspace?.orgId ?? null;
    const scopeId = scope === "workspace" ? workspaceId : orgId;

    useEffect(() => {
        if (scopeId === null) return;
        let cancelled = false;
        if (loadedScopeIdRef.current !== scopeId) setData(null);
        (async () => {
            setLoading(true);
            setError(null);
            setReferenceId(null);
            setAccessDenied(false);
            try {
                const next =
                    scope === "workspace"
                        ? await getWorkspaceDiagnostics(scopeId)
                        : await getOrgDiagnostics(scopeId);
                if (cancelled) return;
                setData(next);
                loadedScopeIdRef.current = scopeId;
            } catch (caught) {
                if (cancelled) return;
                if (caught instanceof ApiError && caught.status === 403) {
                    setAccessDenied(true);
                } else if (caught instanceof ApiError) {
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

    const refresh = useCallback(() => {
        setReloadToken((token) => token + 1);
    }, []);

    if (accessDenied) {
        return scope === "organization" ? (
            <NoAccessCard />
        ) : (
            <AccessDenied variant="inline" body={t("noAccess")} />
        );
    }

    const showLoading = scopeId !== null && loading;
    if (showLoading && data === null) return <DiagnosticsPanelSkeleton scope={scope} />;

    const findings = showLoading ? [] : (data?.findings ?? []);
    const faulted = new Set((data?.unavailableSections ?? []).map((fault) => fault.section));
    const boundaryKey = `${scopeId ?? "none"}:${reloadToken}`;

    return (
        <div className="space-y-10">
            {findings.length > 0 ? (
                <div className="flex flex-wrap gap-2">
                    {findings.map((finding) => (
                        <StatusPill
                            key={[finding.code, finding.workspaceId ?? "", finding.capability ?? "", finding.provider ?? "", finding.channel ?? "", finding.stream ?? ""].join(":")}
                            tone={finding.severity === "warning" ? "warn" : "neutral"}
                            label={t(`finding.${finding.code}`)}
                        />
                    ))}
                </div>
            ) : null}

            <div className="flex items-center justify-end">
                <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={refresh}
                    disabled={showLoading}
                    className="transition-transform duration-150 ease-out active:scale-[0.97]"
                >
                    {showLoading ? t("refreshing") : t("refresh")}
                </Button>
            </div>

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
                        onClick={refresh}
                    >
                        {t("retry")}
                    </Button>
                </div>
            ) : (
                <>
                    {error ? (
                        <div className="rounded-lg border border-border bg-card px-4 py-3">
                            <p className="text-sm text-muted-foreground">{t("staleAfterRefresh")}</p>
                            {referenceId ? (
                                <p className="mt-1 font-mono text-xs text-muted-foreground">
                                    {t("referenceId", { id: referenceId })}
                                </p>
                            ) : null}
                        </div>
                    ) : null}
                    <SectionBoundary resetKey={boundaryKey}>
                        <ProfileCapabilitiesSection
                            data={data}
                            unavailable={faulted.has("deployment")}
                        />
                    </SectionBoundary>
                    <SectionBoundary resetKey={boundaryKey}>
                        <ProviderReadinessSection
                            data={data}
                            unavailable={faulted.has("jobs_providers")}
                        />
                    </SectionBoundary>
                    <SectionBoundary resetKey={boundaryKey}>
                        <JobRunsSection
                            data={data}
                            unavailable={faulted.has("jobs_providers")}
                        />
                    </SectionBoundary>
                    <SectionBoundary resetKey={boundaryKey}>
                        <SecretStoreSection
                            data={data}
                            unavailable={faulted.has("secret_store")}
                        />
                    </SectionBoundary>
                </>
            )}

            <SectionBoundary resetKey={boundaryKey}>
                <MailDeliverabilitySection
                    workspaceId={scope === "workspace" ? workspaceId : null}
                />
            </SectionBoundary>
        </div>
    );
}
