"use client";

import { useLocale, useTranslations } from "next-intl";

import type { DiagnosticsJob, DiagnosticsJobRun, JobRunStatus, TenantDiagnostics } from "@/app/lib/types";
import { formatDateTime } from "@/app/lib/utils";
import { DiagnosticsSection } from "./DiagnosticsSection";
import { StatusPill, type DiagnosticTone } from "./StatusPill";

const STATUS_TONE: Record<JobRunStatus, DiagnosticTone> = {
    succeeded: "ok",
    failed: "bad",
    skipped: "neutral",
};

function detailEntries(run: DiagnosticsJobRun): string[] {
    if (!run.detail) return [];
    return Object.entries(run.detail).map(([key, value]) => `${key}: ${value}`);
}

/**
 * Scheduled-job bookkeeping: the most recent run plus the most recent success and failure for
 * every job the tenant can see. Instance-wide runs appear without a workspace and intentionally
 * carry no counts, so one tenant never learns the shape of another tenant's workload.
 */
export function JobRunsSection({
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
    const jobs = data?.jobs ?? [];

    return (
        <DiagnosticsSection
            title={t("jobsTitle")}
            description={t("jobsDescription")}
            loading={loading}
            error={error}
            referenceId={referenceId}
            onRetry={onRetry}
            isEmpty={!loading && !error && jobs.length === 0}
            emptyLabel={t("jobsEmpty")}
        >
            <ul className="divide-y divide-border rounded-lg border border-border bg-card">
                {jobs.map((job) => (
                    <JobRow key={job.jobName} job={job} />
                ))}
            </ul>
        </DiagnosticsSection>
    );
}

function JobRow({ job }: { job: DiagnosticsJob }) {
    const t = useTranslations("TenantDiagnostics");
    const locale = useLocale();
    const last = job.last;

    return (
        <li className="px-4 py-3">
            <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="font-mono text-xs text-foreground">{job.jobName}</span>
                {last ? (
                    <StatusPill tone={STATUS_TONE[last.status]} label={t(`jobStatus.${last.status}`)} />
                ) : (
                    <StatusPill tone="neutral" label={t("jobNeverRun")} />
                )}
            </div>
            {last ? (
                <dl className="mt-1.5 grid gap-x-6 gap-y-0.5 text-xs text-muted-foreground sm:grid-cols-3">
                    <div className="flex gap-1.5">
                        <dt>{t("jobLastRun")}</dt>
                        <dd className="text-foreground">{formatDateTime(last.startedAt, locale)}</dd>
                    </div>
                    <div className="flex gap-1.5">
                        <dt>{t("jobLastSuccess")}</dt>
                        <dd>
                            {job.lastSuccess
                                ? formatDateTime(job.lastSuccess.startedAt, locale)
                                : t("never")}
                        </dd>
                    </div>
                    <div className="flex gap-1.5">
                        <dt>{t("jobLastFailure")}</dt>
                        <dd>
                            {job.lastFailure
                                ? formatDateTime(job.lastFailure.startedAt, locale)
                                : t("never")}
                        </dd>
                    </div>
                </dl>
            ) : null}
            {last && detailEntries(last).length > 0 ? (
                <p className="mt-1 font-mono text-xs text-muted-foreground">
                    {detailEntries(last).join(" · ")}
                </p>
            ) : null}
        </li>
    );
}
