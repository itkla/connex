import type { AskConnexProposalReviewLabels } from "@/app/components/ask-connex/AskConnexProposalReview";
import type { AskConnexToolCardLabels } from "@/app/components/ask-connex/AskConnexToolCard";
import type { AskConnexToolCardState } from "@/app/lib/askConnex";
import type { AiAssistantToolCall, AiAssistantToolCallChange } from "@/app/lib/types";

/**
 * The assistant card copy, in the shape the provider hands it over.
 *
 * Written out in full rather than stubbed per test so a label the component starts reading is a
 * compile error here instead of an empty string on screen, and so the static and interactive
 * suites are asserting against exactly the same words.
 */
export const askConnexCardLabels: AskConnexToolCardLabels = {
    actionFailed: "This action did not complete. Try again.",
    apply: "Apply",
    applyAria: (target) => `Apply the proposed change to ${target}`,
    applying: "Applying…",
    changeField: { owner: "Owner", stage: "Stage" },
    changeNotSet: "Not set",
    changeCurrentUnresolved: {
        owner: "Someone no longer in this workspace",
        stage: "A stage that no longer exists",
    },
    changeProposedUnresolved: "No longer exists",
    changeState: {
        unchanged: "This is already the current value.",
        recordChanged: "This record moved on, so ask for the change again.",
        permissionLost: "You no longer have permission to make this change.",
        unresolved: "The proposed value no longer exists in this workspace.",
    },
    diffAfter: "After",
    diffBefore: "Now",
    discard: "Discard",
    discardAria: (target) => `Discard the proposed change to ${target}`,
    discarding: "Discarding…",
    editOnRecord: "Change it on the record",
    editOnRecordAria: (target) => `Open ${target} to make this change there`,
    executedDetail: "This action has already been completed.",
    executedStatus: "Completed",
    expiredDetail: "This action completed, but its undo window is closed.",
    expiredStatus: "Undo expired",
    failedDetail: "This action did not complete.",
    failedStatus: "Failed",
    openCreatedRecord: (kind) => `Open ${kind}`,
    openCreatedRecordAria: (kind) => `Open the ${kind} this action created`,
    outcome: "Outcome",
    outcomeField: {
        type: "Type",
        subject: "Subject",
        start: "Starts",
        description: "Task",
        dueDate: "Due",
        title: "Title",
        visibility: "Visibility",
        tag: "Tag",
        stage: "Stage",
        owner: "Owner",
        other: "Detail",
    },
    outcomeValue: (field, value) => `${field}:${value}`,
    pendingDetail: "Nothing has happened yet.",
    pendingStatus: "Needs approval",
    proposalChanged: "The target changed after this proposal was shown.",
    proposalPermissionLost: "You no longer have permission to approve this proposal.",
    proposalUnavailable: "This proposal is no longer available.",
    proposedChange: "Proposed change",
    recordLink: (target) => `Open ${target}`,
    rejectedDetail: "This proposal was rejected. No change was made.",
    rejectedStatus: "Rejected",
    restrictedTarget: "a record you can't open",
    undo: "Undo",
    undoAria: (target) => `Undo the assistant action for ${target}`,
    undoConflict: "This record changed after the assistant created it.",
    undoneDetail: "The assistant-created record was removed.",
    undoneStatus: "Undone",
    undoing: "Undoing…",
    undoWindow: (deadline, remaining) => `You can undo this until ${deadline} — ${remaining}.`,
    summaries: {
        createActivity: "Create an activity",
        createTask: "Create a task",
        createNote: "Create a note",
        addTag: "Add an existing tag",
        changeDealStage: "Change the deal stage",
        changeDealStageTo: (value) => `Change deal stage to: ${value}`,
        assignOwner: "Assign an owner",
        assignOwnerTo: (value) => `Assign owner: ${value}`,
        removeOwner: "Remove the current owner",
        runWriteTool: "Run a write action",
        requestRejected: "Request rejected",
        requestFailed: "Request failed",
        createdRecordRemoved: "Created record removed",
        activityCreated: "Activity created",
        taskCreated: "Task created",
        noteCreated: "Note created",
        tagAdded: "Tag added",
        tagAlreadyPresent: "Tag was already present",
        dealStageChanged: "Deal stage changed",
        ownerRemoved: "Owner removed",
        ownerAssigned: "Owner assigned",
        requestCompleted: "Request completed",
    },
};

/** The grouped-review copy, in the shape the provider hands it over. */
export const askConnexReviewLabels: AskConnexProposalReviewLabels = {
    heading: (count) => `${count} changes need your review`,
    applicable: (applicable, count) => `${applicable} of ${count} can be applied now.`,
    selected: (count) => `${count} changes selected`,
    applySelected: (count) => `Apply ${count} changes`,
    applying: "Applying…",
    include: (target) => `Include the change to ${target}`,
    openFullView: "Open review in full view",
    noneApplicable: "None of these can be applied right now.",
    applied: (count) => `${count} applied`,
    discarded: (count) => `${count} discarded`,
    failed: (count) => `${count} could not be applied`,
};

/** One viewer-safe tool-call projection, as the server would send it. */
export function askConnexToolCall(
    overrides: Partial<AiAssistantToolCall> = {},
): AiAssistantToolCall {
    return {
        id: 31,
        toolName: "assign_owner",
        tier: "confirm",
        status: "proposed",
        target: { kind: "deal", id: 7, label: "Acme renewal" },
        requestSummary: "Assign owner: Grace Hopper",
        outcomeSummary: null,
        change: null,
        outcomeValues: [],
        createdRecord: null,
        messageId: 22,
        turnId: 9,
        undoExpiresAt: null,
        undoAvailable: false,
        createdAt: "2026-08-22T11:59:00Z",
        updatedAt: "2026-08-22T11:59:00Z",
        executedAt: null,
        ...overrides,
    };
}

/** One tool card, with the client interaction state layered over the server projection. */
export function askConnexCard(
    overrides: Partial<AskConnexToolCardState> = {},
): AskConnexToolCardState {
    return {
        ...askConnexToolCall(overrides),
        pendingAction: null,
        failure: null,
        undoBlocked: false,
        ...overrides,
    };
}

/** One proposed record change, defaulting to an ordinary applicable owner reassignment. */
export function askConnexChange(
    overrides: Partial<AiAssistantToolCallChange> = {},
): AiAssistantToolCallChange {
    return {
        field: "owner",
        currentValue: "Ada Owner",
        currentValueUnresolved: false,
        proposedValue: "Grace Hopper",
        state: "ready",
        ...overrides,
    };
}
