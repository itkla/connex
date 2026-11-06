"use client";

import { useTranslations } from "next-intl";

import type { WorkflowRunFailure as Failure } from "@/app/lib/types";
import { ACTIONS } from "@/app/components/settings/workflows/vocabulary";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";

/** Explains only recognised failure codes; server diagnostics never become display copy. */
export default function WorkflowRunFailure({ failure }: { failure: Failure }) {
    const t = useTranslations("WorkspaceWorkflows");
    const tr = useTranslations("WorkflowAuthoring");
    const operations = useTranslations("WorkflowOperations");
    const actionType = Object.values(ACTIONS).flat().find((type) => type === failure.actionType);
    const reason = operations.has(`reason.${failure.code}`)
        ? operations(`reason.${failure.code}`)
        : t.has(`diagnostics.${failure.code}`)
            ? t(`diagnostics.${failure.code}`)
            : t("runs.failureUnknown");
    return (
        <Alert variant="destructive">
            <AlertTitle>
                {failure.actionNumber != null && actionType
                    ? t("runs.failedAction", { number: failure.actionNumber, action: tr(`action.${actionType}`) })
                    : t(failure.nodeId ? "runs.failureAtNode" : "runs.failureTitle")}
            </AlertTitle>
            <AlertDescription>
                <p>{reason}</p>
                <p>{t("runs.failureRecovery")}</p>
            </AlertDescription>
        </Alert>
    );
}
