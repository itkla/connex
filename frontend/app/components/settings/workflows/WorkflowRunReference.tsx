"use client";

import { ClipboardDocumentIcon } from "@heroicons/react/24/outline";
import { useTranslations } from "next-intl";

import { toastError, toastSuccess } from "@/app/lib/toast";
import { workflowRunReferenceParts } from "@/app/components/settings/workflows/workflowRunKey";
import { Button } from "@/components/ui/button";

/**
 * Names a run to a person and keeps its support identifier one click away. The run key is an
 * internal identifier, so it is copied rather than displayed, and the accessible name opens with
 * the visible run name so the two never disagree.
 */
export default function WorkflowRunReference({ runKey }: { runKey: string }) {
    const t = useTranslations("WorkspaceWorkflows");
    const { earlier, number } = workflowRunReferenceParts(runKey);
    const reference = t(earlier ? "runs.legacyReference" : "runs.reference", { run: number });

    const copy = async () => {
        try {
            await navigator.clipboard.writeText(runKey);
            toastSuccess(t("runs.referenceCopied"));
        } catch {
            toastError(t("runs.referenceCopyFailed"));
        }
    };

    return (
        <Button variant="ghost" size="xs" aria-label={t("runs.copyReference", { reference })} onClick={() => void copy()}>
            <ClipboardDocumentIcon />
            {reference}
        </Button>
    );
}
