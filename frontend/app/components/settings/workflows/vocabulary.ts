import type { RuleAction } from "@/app/lib/types";

/**
 * The authoring vocabulary shared by every workflow surface, so the editor, the inspector, and
 * the run history can never drift. Mirrors the server-side validation in RuleService — a change
 * here must stay within what the backend accepts.
 */
export const RECORD_TYPES = ["deal", "company", "person", "task", "document"];

export const EVENTS: Record<string, string[]> = {
    deal: ["deal.created", "deal.stage_changed", "deal.updated", "deal.won", "deal.lost", "deal.owner_changed", "deal.value_changed"],
    company: ["company.created", "company.updated", "company.owner_changed"],
    person: ["person.created", "person.updated", "person.job_changed", "person.owner_changed", "person.lifecycle_changed", "person.first_response_overdue"],
    task: ["task.created", "task.completed"],
    document: ["document.approval_requested", "document.approved", "document.rejected", "document.finalized", "document.superseded"],
};

export const ACTIONS: Record<string, string[]> = {
    deal: ["create_task", "log_activity", "add_tag", "remove_tag", "create_note", "assign_owner", "change_stage", "notify"],
    company: ["add_tag", "remove_tag", "notify"],
    person: ["create_task", "log_activity", "add_tag", "remove_tag", "create_note", "assign_owner", "set_response_due", "notify"],
    task: ["notify"],
    document: ["notify", "create_task", "log_activity", "create_note"],
};

export const CADENCES = ["hourly", "daily", "weekly"];

export const EXECUTION_MODES = ["user", "system"] as const;

export const SEGMENT_RECORD_TYPES = ["company", "person", "deal"];

export const SCHEDULE_RECORD_TYPES = ["company", "person", "deal"];

/**
 * Record types a manual run can be scoped to. Mirrors `WorkflowManualRunService`, whose scope
 * resolution and the `workflow_invocation` check constraint cover only these three — a workflow of
 * any other record type is rejected by the server, so no launcher may offer one.
 */
export const MANUAL_RUN_RECORD_TYPES = ["company", "person", "deal"];

/**
 * Record types whose simulation subject can be found through workspace search. `document` is absent
 * because the search response carries generated documents as a bounded summary group of their own
 * (WS7 / issue #1343), which the record picker does not read; submitting one would send the server
 * an id of the wrong entity.
 */
export const SIMULATION_RECORD_TYPES = ["company", "person", "deal", "task"];

/** Entity-change events available for a record type. */
export function eventsFor(recordType: string): string[] {
    return EVENTS[recordType] ?? [];
}

/** Whether a manual run can be prepared for a record type; an unset record type never can. */
export function supportsManualRun(recordType: string | null): boolean {
    return recordType !== null && MANUAL_RUN_RECORD_TYPES.includes(recordType);
}

/** Whether a simulation subject can be chosen for a record type; an unset record type never can. */
export function supportsSimulation(recordType: string | null): boolean {
    return recordType !== null && SIMULATION_RECORD_TYPES.includes(recordType);
}

/** Actions available for a record type. */
export function actionsFor(recordType: string): string[] {
    return ACTIONS[recordType] ?? ["notify"];
}

/** Hours a new first-response SLA action asks for until the author changes it. */
export const DEFAULT_RESPONSE_DUE_HOURS = 4;

/**
 * A freshly selected action of the given type, carrying any value the server requires.
 *
 * Selecting a type replaces the whole config, so a required field the editor only renders as a
 * placeholder would never reach the server and the definition would be rejected on save. Both
 * authoring surfaces build their action here so the two cannot disagree about what a new action is.
 */
export function actionWithDefaults(type: string): RuleAction {
    return type === "set_response_due"
        ? { type, dueInHours: DEFAULT_RESPONSE_DUE_HOURS }
        : { type };
}

/** The default action appended when a step is added for a record type. */
export function defaultAction(recordType: string): RuleAction {
    return actionsFor(recordType).includes("notify") ? { type: "notify", title: "", body: "" } : actionWithDefaults(actionsFor(recordType)[0]);
}
