"use client";

import { useLocale, useTranslations } from "next-intl";

import type { DiagnosticsWorkspaceProviders, TenantDiagnostics } from "@/app/lib/types";
import { formatDateTime } from "@/app/lib/utils";
import { DiagnosticsSection } from "./DiagnosticsSection";
import { StatusPill, type DiagnosticTone } from "./StatusPill";

const CAPTURE_HEALTHY = new Set(["idle", "queued", "backfilling", "syncing"]);
const CAPTURE_ATTENTION = new Set(["retrying", "paused", "purging"]);

function captureTone(status: string): DiagnosticTone {
    if (CAPTURE_HEALTHY.has(status)) return "ok";
    if (CAPTURE_ATTENTION.has(status)) return "warn";
    return "bad";
}

function readyTone(ready: boolean): DiagnosticTone {
    return ready ? "ok" : "warn";
}

/**
 * Readiness for every provider the workspace depends on. Values are reported exactly as the
 * backend resolved them — nothing here probes a provider, so opening this page never sends
 * traffic to AI, mail, delivery, OCR, or capture endpoints.
 */
export function ProviderReadinessSection({
    data,
    loading,
    error,
}: {
    data: TenantDiagnostics | null;
    loading: boolean;
    error: string | null;
}) {
    const t = useTranslations("TenantDiagnostics");
    const locale = useLocale();
    const providers = data?.providers ?? null;
    const workspaces = providers?.workspaces ?? [];

    return (
        <DiagnosticsSection
            title={t("providersTitle")}
            description={t("providersDescription")}
            loading={loading}
            error={error}
            isEmpty={!loading && !error && !providers}
            emptyLabel={t("providersEmpty")}
        >
            <div className="space-y-4">
                <div className="grid gap-2 sm:grid-cols-2">
                    <div className="flex items-center justify-between rounded-lg border border-border bg-card px-4 py-3">
                        <span className="text-sm text-foreground">{t("providerAi")}</span>
                        <StatusPill
                            tone={readyTone(Boolean(providers?.ai.ready))}
                            label={providers?.ai.ready ? t("ready") : t("notReady")}
                        />
                    </div>
                    <div className="flex items-center justify-between rounded-lg border border-border bg-card px-4 py-3">
                        <span className="text-sm text-foreground">{t("providerOcr")}</span>
                        <StatusPill
                            tone={readyTone(Boolean(providers?.ocr.scanningAvailable))}
                            label={providers?.ocr.scanningAvailable ? t("ready") : t("notReady")}
                        />
                    </div>
                </div>

                {workspaces.map((workspace) => (
                    <WorkspaceProviders
                        key={workspace.workspaceId}
                        workspace={workspace}
                        locale={locale}
                        showHeading={workspaces.length > 1}
                    />
                ))}
            </div>
        </DiagnosticsSection>
    );
}

function WorkspaceProviders({
    workspace,
    locale,
    showHeading,
}: {
    workspace: DiagnosticsWorkspaceProviders;
    locale: string;
    showHeading: boolean;
}) {
    const t = useTranslations("TenantDiagnostics");

    return (
        <div className="space-y-2">
            {showHeading ? (
                <h3 className="text-xs font-medium text-muted-foreground">
                    {t("workspaceHeading", { id: workspace.workspaceId })}
                </h3>
            ) : null}
            <div className="divide-y divide-border rounded-lg border border-border bg-card">
                <div className="flex flex-wrap items-center justify-between gap-2 px-4 py-2.5">
                    <span className="text-sm text-foreground">{t("providerMail")}</span>
                    <div className="flex items-center gap-2">
                        <span className="text-xs text-muted-foreground">
                            {t(`mailMode.${workspace.mail.mode}`)}
                        </span>
                        <StatusPill
                            tone={readyTone(workspace.mail.configured)}
                            label={workspace.mail.configured ? t("configured") : t("notConfigured")}
                        />
                    </div>
                </div>

                {workspace.delivery.map((channel) => (
                    <div
                        key={channel.channel}
                        className="flex flex-wrap items-center justify-between gap-2 px-4 py-2.5"
                    >
                        <span className="text-sm text-foreground">
                            {t("deliveryChannel", { channel: channel.channel })}
                        </span>
                        <StatusPill
                            tone={
                                !channel.implemented ? "neutral" : readyTone(channel.ready)
                            }
                            label={
                                !channel.implemented
                                    ? t("notImplemented")
                                    : channel.ready
                                      ? t("ready")
                                      : t("notReady")
                            }
                        />
                    </div>
                ))}

                {workspace.capture.map((stream) => (
                    <div
                        key={`${stream.provider}:${stream.stream}:${stream.status}`}
                        className="flex flex-wrap items-center justify-between gap-2 px-4 py-2.5"
                    >
                        <div className="min-w-0">
                            <span className="text-sm text-foreground">
                                {t("captureStream", {
                                    provider: stream.provider,
                                    stream: stream.stream,
                                })}
                            </span>
                            <p className="text-xs text-muted-foreground">
                                {t("captureCounts", {
                                    streams: stream.stateCount,
                                    processed: stream.processedItems,
                                })}
                                {stream.lastSuccessAt
                                    ? ` · ${t("lastSuccess", {
                                          when: formatDateTime(stream.lastSuccessAt, locale),
                                      })}`
                                    : ""}
                            </p>
                        </div>
                        <StatusPill tone={captureTone(stream.status)} label={stream.status} />
                    </div>
                ))}
            </div>
        </div>
    );
}
