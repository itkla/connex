import type { WorkflowValidation } from "@/app/lib/types";

/** The definition and publication checks must both pass before the draft is ready. */
export function workflowIsReadyToPublish(validation: WorkflowValidation): boolean {
    return validation.valid && validation.canPublish && validation.errors.length === 0;
}
