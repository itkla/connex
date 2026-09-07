"use client";

import { useLocale, useTranslations } from "next-intl";

import { formatWorkflowRunDateTime } from "@/app/components/settings/workflows/workflowRunStatus";
import type { WorkflowDto } from "@/app/lib/types";

/** Displays server-reconciled date enrollment counts and the next persisted occurrence. */
export default function WorkflowDateScheduleStatus({ status }: { status: NonNullable<WorkflowDto["dateScheduleStatus"]> }) {
    const t = useTranslations("WorkspaceWorkflows");
    const locale = useLocale();
    return (
        <section className="space-y-2 border-b border-border px-4 py-3 text-sm" aria-label={t("date.statusTitle")}>
            <h2 className="font-semibold text-foreground">{t("date.statusTitle")}</h2>
            <dl className="flex flex-wrap gap-x-6 gap-y-2">
                {(["planned", "queued", "missed"] as const).map((state) => <div key={state} className="flex gap-2">
                    <dt className="text-muted-foreground">{t(`date.status.${state}`)}</dt><dd className="font-medium tabular-nums text-foreground">{status[`${state}Count`]}</dd>
                </div>)}
            </dl>
            {status.nextDueAt ? <p className="text-muted-foreground">{t("date.nextDue", { date: formatWorkflowRunDateTime(status.nextDueAt, locale) })}</p> : null}
            {status.lastReconciledAt ? <p className="text-muted-foreground">{t("date.lastReconciled", { date: formatWorkflowRunDateTime(status.lastReconciledAt, locale) })}</p> : null}
            {status.missedCount > 0 ? <p className="text-muted-foreground">{t("date.missedHelp")}</p> : null}
        </section>
    );
}
