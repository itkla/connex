import type { WorkflowListItem } from "@/app/lib/types";

/** Filters the workflow register by purpose, primary record, and actionable lifecycle state. */
export function filterWorkflowRegister(
    workflows: WorkflowListItem[],
    filters: { query: string; recordType: string; state: string },
): WorkflowListItem[] {
    const query = filters.query.trim().toLocaleLowerCase();
    return workflows.filter((workflow) => {
        if (query && !`${workflow.name} ${workflow.description ?? ""}`.toLocaleLowerCase().includes(query)) return false;
        if (filters.recordType !== "all" && workflow.recordType !== filters.recordType) return false;
        switch (filters.state) {
            case "draft": return workflow.activeVersion === null;
            case "enabled": return workflow.enabled && workflow.intakePausedAt === null && workflow.activeVersion !== null;
            case "disabled": return !workflow.enabled;
            case "paused": return workflow.intakePausedAt !== null;
            case "attention": return workflow.latestRun?.status === "failed" || workflow.latestRun?.status === "intervention_required";
            default: return true;
        }
    });
}
