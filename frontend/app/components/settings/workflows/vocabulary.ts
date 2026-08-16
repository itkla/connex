import type { RuleAction } from "@/app/lib/types";

/**
 * The rule-engine authoring vocabulary, shared by the legacy RuleDialog and the workflows editor
 * so the two surfaces can never drift. Mirrors the server-side validation in RuleService — a
 * change here must stay within what the backend accepts.
 */
export const RECORD_TYPES = ["deal", "company", "person", "task"];

export const EVENTS: Record<string, string[]> = {
    deal: ["deal.created", "deal.stage_changed", "deal.updated", "deal.won", "deal.lost", "deal.owner_changed", "deal.value_changed"],
    company: ["company.created", "company.updated", "company.owner_changed"],
    person: ["person.created", "person.updated", "person.job_changed", "person.owner_changed", "person.lifecycle_changed"],
    task: ["task.created", "task.completed"],
};

export const ACTIONS: Record<string, string[]> = {
    deal: ["create_task", "log_activity", "add_tag", "remove_tag", "create_note", "assign_owner", "change_stage", "notify"],
    company: ["add_tag", "remove_tag", "notify"],
    person: ["create_task", "log_activity", "add_tag", "remove_tag", "create_note", "notify"],
    task: ["notify"],
};

export const CADENCES = ["hourly", "daily", "weekly"];

export const EXECUTION_MODES = ["user", "system"] as const;

export const SEGMENT_RECORD_TYPES = ["company", "person", "deal"];

export const SCHEDULE_RECORD_TYPES = ["company", "person", "deal"];

/** Entity-change events available for a record type. */
export function eventsFor(recordType: string): string[] {
    return EVENTS[recordType] ?? [];
}

/** Actions available for a record type. */
export function actionsFor(recordType: string): string[] {
    return ACTIONS[recordType] ?? ["notify"];
}

/** The default action appended when a step is added for a record type. */
export function defaultAction(recordType: string): RuleAction {
    return actionsFor(recordType).includes("notify") ? { type: "notify", title: "", body: "" } : { type: actionsFor(recordType)[0] };
}
