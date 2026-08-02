"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";

import type { DnsAdvisoryStatus, MailDiagnosticTest, MailDnsAdvisoryRecord } from "@/app/lib/types";
import { ApiError, sendWorkspaceMailDiagnosticTest } from "@/app/lib/api";
import { usePasskeyStepUpErrorHandler } from "@/app/hooks/usePasskeyStepUpError";
import { Button } from "@/components/ui/button";
import { SettingsSection } from "@/app/components/settings/SettingsSection";
import { StatusPill, type DiagnosticTone } from "./StatusPill";

const DNS_TONE: Record<DnsAdvisoryStatus, DiagnosticTone> = {
    present: "ok",
    unknown: "neutral",
    not_configured: "neutral",
};

/**
 * Mail deliverability, including the self-serve send test. The test always sends to the signed-in
 * administrator's own address and never writes configuration, so it is safe under managed mail.
 * The DNS block is advisory: a lookup that cannot be resolved reports as unknown and never turns
 * a successful send into a failure.
 */
export function MailDeliverabilitySection({ workspaceId }: { workspaceId: number | null }) {
    const t = useTranslations("TenantDiagnostics");
    const handlePasskeyStepUpError = usePasskeyStepUpErrorHandler();
    const [pending, setPending] = useState(false);
    const [result, setResult] = useState<MailDiagnosticTest | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [referenceId, setReferenceId] = useState<string | null>(null);

    async function runTest() {
        if (workspaceId === null || pending) return;
        setPending(true);
        setError(null);
        setReferenceId(null);
        setResult(null);
        try {
            setResult(await sendWorkspaceMailDiagnosticTest(workspaceId));
        } catch (caught) {
            if (handlePasskeyStepUpError(caught)) return;
            if (caught instanceof ApiError) {
                setError(caught.message);
                setReferenceId(caught.correlationId ?? null);
            } else {
                setError(t("sendTestFailed"));
            }
        } finally {
            setPending(false);
        }
    }

    const transport = result?.transport ?? null;

    return (
        <SettingsSection
            title={t("mailTitle")}
            description={t("mailDescription")}
            action={
                workspaceId === null ? null : (
                    <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        onClick={runTest}
                        disabled={pending}
                        className="transition-transform duration-150 ease-out active:scale-[0.97]"
                    >
                        {pending ? t("sendTestPending") : t("sendTest")}
                    </Button>
                )
            }
        >
            <div className="space-y-3">
                {workspaceId === null ? (
                    <p className="rounded-lg border border-dashed border-border bg-card/40 px-4 py-3 text-sm text-muted-foreground">
                        {t("sendTestWorkspaceOnly")}
                    </p>
                ) : null}

                {error ? (
                    <div className="rounded-lg border border-border bg-card p-4">
                        <p className="text-sm text-foreground">{error}</p>
                        {referenceId ? (
                            <p className="mt-1 font-mono text-xs text-muted-foreground">
                                {t("referenceId", { id: referenceId })}
                            </p>
                        ) : null}
                    </div>
                ) : null}

                {transport ? (
                    <div className="divide-y divide-border rounded-lg border border-border bg-card">
                        <div className="flex flex-wrap items-center justify-between gap-2 px-4 py-2.5">
                            <span className="text-sm text-foreground">{t("transportOutcome")}</span>
                            <StatusPill
                                tone={transport.outcome === "succeeded" ? "ok" : "bad"}
                                label={t(`transportStatus.${transport.outcome}`)}
                            />
                        </div>
                        <dl className="grid gap-x-6 gap-y-1 px-4 py-2.5 text-xs sm:grid-cols-2">
                            <Row label={t("mailSender")} value={result?.sender.address ?? "—"} />
                            <Row label={t("mailModeLabel")} value={t(`mailMode.${transport.mode}`)} />
                            <Row label={t("mailHost")} value={transport.host ?? "—"} />
                            <Row
                                label={t("mailPort")}
                                value={
                                    transport.port === null || transport.port === undefined
                                        ? "—"
                                        : String(transport.port)
                                }
                            />
                            {transport.errorCode ? (
                                <Row label={t("mailErrorCode")} value={transport.errorCode} />
                            ) : null}
                            {result?.correlationId ? (
                                <Row label={t("correlationId")} value={result.correlationId} />
                            ) : null}
                        </dl>
                    </div>
                ) : null}

                {result ? (
                    <div className="rounded-lg border border-border bg-card">
                        <div className="flex items-center justify-between px-4 py-2.5">
                            <span className="text-sm text-foreground">{t("dnsTitle")}</span>
                            <span className="text-xs text-muted-foreground">{t("dnsAdvisory")}</span>
                        </div>
                        <div className="divide-y divide-border border-t border-border">
                            <DnsRow label="SPF" record={result.dns.spf} />
                            <DnsRow label="DKIM" record={result.dns.dkim} />
                            <DnsRow label="DMARC" record={result.dns.dmarc} />
                        </div>
                    </div>
                ) : null}
            </div>
        </SettingsSection>
    );
}

function Row({ label, value }: { label: string; value: string }) {
    return (
        <div className="flex gap-1.5">
            <dt className="text-muted-foreground">{label}</dt>
            <dd className="min-w-0 truncate font-mono text-foreground">{value}</dd>
        </div>
    );
}

function DnsRow({ label, record }: { label: string; record: MailDnsAdvisoryRecord }) {
    const t = useTranslations("TenantDiagnostics");

    return (
        <div className="flex flex-wrap items-center justify-between gap-2 px-4 py-2.5">
            <div className="min-w-0">
                <span className="text-sm text-foreground">{label}</span>
                <p className="truncate font-mono text-xs text-muted-foreground">{record.queryName}</p>
            </div>
            <StatusPill tone={DNS_TONE[record.status]} label={t(`dnsStatus.${record.status}`)} />
        </div>
    );
}
