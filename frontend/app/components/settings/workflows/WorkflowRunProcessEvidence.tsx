"use client";

import { useLocale, useTranslations } from "next-intl";

import { formatWorkflowRunDateTime } from "@/app/components/settings/workflows/workflowRunStatus";
import type { WorkflowRunSummary } from "@/app/lib/types";

/** Displays the server-recorded enrollment, stop, and date-schedule evidence for one run. */
export default function WorkflowRunProcessEvidence({ run }: { run: WorkflowRunSummary }) {
    const t = useTranslations("WorkflowOperations");
    const locale = useLocale();
    if (!run.statusReason && !run.dateSchedule) return null;
    return <div className="space-y-1 text-sm text-muted-foreground">
        {run.statusReason ? <p>{t.has(`runReason.${run.statusReason}`) ? t(`runReason.${run.statusReason}`) : t("runReason.custom", { reason: run.statusReason.replaceAll("_", " ") })}</p> : null}
        {run.dateSchedule ? <>
            <p>{t("dateSchedule.sourceDate", { date: run.dateSchedule.sourceDate })}</p>
            <p>{t("dateSchedule.scheduled", { date: run.dateSchedule.scheduledLocalDate })}</p>
            <p>{t("dateSchedule.dueAt", { date: formatWorkflowRunDateTime(run.dateSchedule.dueAt, locale) })}</p>
        </> : null}
    </div>;
}
