"use client";

import Link from "next/link";
import { useLocale, useTranslations } from "next-intl";

import { formatWorkflowRunDateTime } from "@/app/components/settings/workflows/workflowRunStatus";
import type { WorkflowStepRun } from "@/app/lib/types";

/** Shows persisted wait evidence without interpreting an unresolved task as completed. */
export default function WorkflowWaitEvidence({ step }: { step: WorkflowStepRun }) {
    const t = useTranslations("WorkspaceWorkflows");
    const locale = useLocale();
    if (!step.wait) return null;
    const wait = step.wait;
    return (
        <div className="space-y-1 text-sm text-muted-foreground">
            <p>{t(`wait.resolution.${wait.resolution ?? "pending"}`)}</p>
            <p>{t("wait.deadline", { date: formatWorkflowRunDateTime(wait.timeoutAt, locale) })}</p>
            {wait.resolvedAt ? <p>{t("wait.resolvedAt", { date: formatWorkflowRunDateTime(wait.resolvedAt, locale) })}</p> : null}
            <Link href={`/activity/tasks?task=${wait.sourceTaskId}`} className="font-medium text-brand-dark underline underline-offset-4">{t("wait.viewTask")}</Link>
        </div>
    );
}
