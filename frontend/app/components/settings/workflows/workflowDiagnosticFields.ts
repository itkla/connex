import type { WorkflowDiagnostic } from "@/app/lib/types";

export type WorkflowFieldProps = (fieldPath: string) => {
    "data-workflow-node": string;
    "data-workflow-field": string;
    "aria-invalid": boolean;
    "aria-describedby": string | undefined;
};

/** Identifies definition-level settings edited alongside the workflow trigger. */
export function workflowDiagnosticTargetsEntry(diagnostic: WorkflowDiagnostic): boolean {
    const path = diagnostic.fieldPath;
    return path != null && ["inputs", "enrollment", "stopConditions"].some((field) => path === field || path.startsWith(`${field}.`) || path.startsWith(`${field}[`));
}
