"use client";

import { type ReactNode } from "react";
import { ExclamationTriangleIcon } from "@heroicons/react/24/outline";
import { useTranslations } from "next-intl";

import type { WorkflowDiagnostic, WorkflowValidation } from "@/app/lib/types";
import { Button } from "@/components/ui/button";

/** Accessible authoritative validation summary with focus navigation into node fields. */
export default function WorkflowValidationSummary({
    validation,
    diagnosticMessage,
    onSelectDiagnostic,
}: {
    validation: WorkflowValidation;
    diagnosticMessage: (diagnostic: WorkflowDiagnostic) => ReactNode;
    onSelectDiagnostic: (diagnostic: WorkflowDiagnostic) => void;
}) {
    const t = useTranslations("WorkspaceWorkflows");
    if (validation.valid) {
        return (
            <div role="status" className="border-b border-border bg-secondary px-4 py-2 text-sm text-secondary-foreground">
                {t("validationReady")}
            </div>
        );
    }
    return (
        <section className="border-b border-destructive/30 bg-destructive/10 px-4 py-3" aria-labelledby="workflow-validation-title">
            <div className="flex items-start gap-2">
                <ExclamationTriangleIcon aria-hidden className="mt-0.5 size-4 shrink-0 text-destructive" />
                <div className="min-w-0">
                    <h2 id="workflow-validation-title" className="text-sm font-semibold text-foreground">
                        {t("validationErrorTitle", { count: validation.errors.length })}
                    </h2>
                    <ul className="mt-1 flex flex-wrap gap-x-3 gap-y-1">
                        {validation.errors.map((diagnostic) => (
                            <li key={`${diagnostic.code}:${diagnostic.nodeId ?? "global"}:${diagnostic.edgeId ?? "no-edge"}:${diagnostic.fieldPath ?? "no-field"}`}>
                                {diagnostic.nodeId ? (
                                    <Button
                                        variant="link"
                                        size="xs"
                                        className="h-auto px-0 text-destructive"
                                        onClick={() => onSelectDiagnostic(diagnostic)}
                                    >
                                        {diagnosticMessage(diagnostic)}
                                    </Button>
                                ) : (
                                    <span className="text-sm text-destructive">{diagnosticMessage(diagnostic)}</span>
                                )}
                            </li>
                        ))}
                    </ul>
                </div>
            </div>
        </section>
    );
}
