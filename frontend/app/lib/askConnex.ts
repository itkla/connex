import type { ActiveRecordRef, ActiveSelection, RecordType } from '@/app/lib/actions/types';
import { AI_CHAT_PROGRESS_SOURCES } from '@/app/lib/types';
import type {
    AiAssistantToolCall,
    AiAssistantToolCallChange,
    AiAssistantToolCallCreatedRecord,
    AiAssistantToolCallMutation,
    AiChatCitation,
    AiChatMessage,
    AiChatPageContext,
    AiChatPageContextKind,
    AiChatProgressItem,
    AiChatProgressSource,
    Page,
} from '@/app/lib/types';
import { viewPreferenceStorageKey } from '@/app/hooks/viewPreference';

const REFERENCE_TOKEN = /\[([^\]]+)]\((person|company|deal):([1-9]\d*)\)/g;
const RESOURCE_HANDLE = /(^|[^\p{L}\p{N}_])r[1-9]\d*($|[^\p{L}\p{N}_])/u;
const CONTROL_INSTRUCTION = /ignore\s+(?:all\s+)?(?:previous|prior|above)\s+instructions?|system\s+prompt|developer\s+(?:message|instructions?)|tool\s+(?:call|command)|crm_data|model_output|step\s+schema/i;
const ASK_CONNEX_SUGGESTION_LIMIT = 3;
const ASK_CONNEX_SUGGESTION_LENGTH = 160;

/** Maximum number of page-context records accepted by one assistant turn. */
export const ASK_CONNEX_CONTEXT_LIMIT = 10;

/** A supported attached record parsed from the mention editor's serialized value. */
export type AskConnexAttachment = AiChatPageContext & {
    label: string;
};

/** Explicit selected-row context, including unsupported types that must remain visible but unsent. */
export type AskConnexSelectionContext = {
    type: RecordType;
    count: number;
    available: boolean;
    unavailableReason: 'record_type' | 'scope' | 'invalid' | null;
    pageContext: AiChatPageContext[];
};

/** Stable source-page context retained while the routed workspace replaces its contributor page. */
export type AskConnexSourceContext = {
    record: ActiveRecordRef | null;
    selection: ActiveSelection | null;
};

/**
 * Everything the user has said about the context they were offered: the inferred page record and
 * selected rows they took out, and the records they pinned so navigating away no longer drops them.
 *
 * Dismissals are deliberately not persisted — they belong to the request being composed. Pins are,
 * because keeping a record across navigation is the whole point of pinning it.
 */
export type AskConnexContextCorrections = {
    pageDismissed: boolean;
    selectionDismissed: boolean;
    pinned: readonly AskConnexAttachment[];
};

/** No correction: the request carries exactly the context the page inferred. */
export const EMPTY_ASK_CONNEX_CORRECTIONS: AskConnexContextCorrections = {
    pageDismissed: false,
    selectionDismissed: false,
    pinned: [],
};

/**
 * The record count at which a request is broad enough that the user should see what it will cover
 * before it runs. One subject, or a subject plus a couple of comparisons, needs no confirmation;
 * a browser selection carried into a question does.
 */
export const ASK_CONNEX_SCOPE_PREVIEW_THRESHOLD = 5;

/** What a broad request will actually read, counted by record kind, in the order it will be sent. */
export type AskConnexScopePreview = {
    total: number;
    records: { kind: AiChatPageContextKind; count: number }[];
    files: number;
    /**
     * The exact records and files this scope carries, so a confirmation belongs to the scope the
     * user actually reviewed rather than to any scope of the same shape.
     */
    identity: string;
};

/** Client upload lifecycle for one assistant-session file chip. */
export type AskConnexFileAttachment = {
    clientId: string;
    id: number | null;
    fileName: string;
    contentType: string;
    size: number;
    kind: 'text' | 'image';
    status: 'uploading' | 'ready' | 'failed' | 'removing';
    progress: number;
    error: string | null;
    durableEchoPending?: boolean;
};

/** Returns whether an upload or removal must settle before the conversation can advance. */
export function hasPendingAskConnexFileOperation(
    attachments: readonly AskConnexFileAttachment[],
): boolean {
    return attachments.some(
        (attachment) => attachment.status === 'uploading' || attachment.status === 'removing',
    );
}

/**
 * Reconciles a fresh durable attachment read without erasing local upload, failure, or removal
 * feedback. Locally completed uploads survive one absent read so a refresh started before upload
 * completion cannot erase them, then expire if a later read still has no durable echo.
 */
export function reconcileAskConnexFileAttachments(
    current: readonly AskConnexFileAttachment[],
    persisted: readonly AskConnexFileAttachment[],
): AskConnexFileAttachment[] {
    const persistedById = new Map<number, AskConnexFileAttachment>();
    persisted.forEach((attachment) => {
        if (attachment.id !== null && attachment.status === 'ready') {
            persistedById.set(attachment.id, attachment);
        }
    });

    const reconciled: AskConnexFileAttachment[] = [];
    current.forEach((attachment) => {
        if (attachment.status !== 'ready') {
            if (attachment.id !== null) persistedById.delete(attachment.id);
            reconciled.push(attachment);
            return;
        }
        if (attachment.id === null) {
            reconciled.push(attachment);
            return;
        }
        const durable = persistedById.get(attachment.id);
        if (durable) {
            persistedById.delete(attachment.id);
            reconciled.push(durable);
            return;
        }
        if (attachment.durableEchoPending) {
            reconciled.push({ ...attachment, durableEchoPending: false });
        }
    });
    return [...reconciled, ...persistedById.values()];
}

/** Replaces a completed upload chip and removes any concurrently hydrated copy of the same file. */
export function completeAskConnexFileUpload(
    attachments: readonly AskConnexFileAttachment[],
    clientId: string,
    uploaded: AskConnexFileAttachment,
): AskConnexFileAttachment[] {
    const completed = { ...uploaded, clientId, durableEchoPending: true };
    const deduplicated = attachments.filter(
        (attachment) => attachment.clientId === clientId || attachment.id !== uploaded.id,
    );
    const index = deduplicated.findIndex((attachment) => attachment.clientId === clientId);
    if (index < 0) return [...deduplicated, completed];
    return deduplicated.map((attachment, attachmentIndex) => attachmentIndex === index
        ? completed
        : attachment);
}

/** Restores a failed removal only while its originating session epoch is still active. */
export function restoreAskConnexFileAfterFailedRemoval(
    attachments: readonly AskConnexFileAttachment[],
    fallback: AskConnexFileAttachment,
    operationEpoch: number,
    currentEpoch: number,
): AskConnexFileAttachment[] {
    if (operationEpoch !== currentEpoch) return [...attachments];
    return attachments.map((attachment) => attachment.clientId === fallback.clientId
        ? fallback
        : attachment);
}

/** Runs one ready-file deletion while preserving pending and stale-session semantics. */
export function removeReadyAskConnexFile(
    attachments: readonly AskConnexFileAttachment[],
    attachment: AskConnexFileAttachment,
    operationEpoch: number,
    currentEpoch: () => number,
    signal: AbortSignal,
    request: () => Promise<void>,
): {
    pending: AskConnexFileAttachment[];
    settled: Promise<AskConnexFileAttachment[] | null>;
} {
    const removing = attachments.map((item) => item.clientId === attachment.clientId
        ? { ...item, status: 'removing' as const }
        : item);
    const settled = (async () => {
        try {
            await request();
            if (signal.aborted || operationEpoch !== currentEpoch()) return null;
            return removing.filter((item) => item.clientId !== attachment.clientId);
        } catch (error) {
            if (signal.aborted || operationEpoch !== currentEpoch()) return null;
            throw new AskConnexFileRemovalError(
                restoreAskConnexFileAfterFailedRemoval(
                    removing,
                    attachment,
                    operationEpoch,
                    currentEpoch(),
                ),
                error,
            );
        }
    })();
    return { pending: removing, settled };
}

/** Carries the restored attachment state alongside one current-session removal failure. */
export class AskConnexFileRemovalError extends Error {
    readonly attachments: AskConnexFileAttachment[];
    readonly cause: unknown;

    constructor(attachments: AskConnexFileAttachment[], cause: unknown) {
        super('Ask Connex file removal failed');
        this.name = 'AskConnexFileRemovalError';
        this.attachments = attachments;
        this.cause = cause;
    }
}

/** Consecutive transcript messages authored by the same sender. */
export type AskConnexMessageGroup = {
    authorKind: string;
    authorUserId: number | null;
    messages: AiChatMessage[];
};

/** Viewer-safe transcript rows plus whether the compacted-history marker must be rendered. */
export type AskConnexTranscript = {
    messages: AiChatMessage[];
    historySummarized: boolean;
};

/** Persisted descriptor needed to reconcile one accepted assistant turn after refresh. */
export type StoredAskConnexTurn = {
    sessionId: number;
    turnId: number;
    generationHandle: string;
};

/** Provider-owned visual phase for one assistant turn. */
export type AskConnexTurnState = {
    phase: 'idle' | 'accepted' | 'running' | 'resolved' | 'failed' | 'timed_out' | 'cancelled';
    sessionId: number | null;
    turnId: number | null;
    generationHandle: string | null;
    reason: string | null;
    progress: AiChatProgressItem[];
    /**
     * Whether this client may stop the turn. A shared-session participant who opened the session
     * while another member's turn was running adopts that turn into the same state the requester
     * uses, and the cancellation endpoint rejects them, so the stop control is theirs only when the
     * server says the turn is their own.
     */
    cancellable: boolean;
};

/** Events that advance or clear the provider-owned assistant turn state. */
export type AskConnexTurnEvent =
    | {
        type: 'accepted';
        sessionId: number;
        turnId: number;
        generationHandle: string | null;
        status: string;
        progress?: AiChatProgressItem[];
        cancellable?: boolean;
    }
    | { type: 'status'; status: string; reason?: string | null; progress?: AiChatProgressItem[] }
    | { type: 'reset' };

/** User action exposed by an assistant tool-call card. */
export type AskConnexToolAction = 'approve' | 'reject' | 'undo';

/** Localized failure category retained on a card after an action rolls back. */
export type AskConnexToolCardFailure =
    | 'proposalChanged'
    | 'proposalPermissionLost'
    | 'proposalUnavailable'
    | 'undoConflict'
    | 'actionFailed';

/** Localized summaries for the complete current assistant write-tool catalog. */
export type AskConnexToolSummaryLabels = {
    createActivity: string;
    createTask: string;
    createNote: string;
    addTag: string;
    changeDealStage: string;
    changeDealStageTo: (value: string) => string;
    assignOwner: string;
    assignOwnerTo: (value: string) => string;
    removeOwner: string;
    runWriteTool: string;
    requestRejected: string;
    requestFailed: string;
    createdRecordRemoved: string;
    activityCreated: string;
    taskCreated: string;
    noteCreated: string;
    tagAdded: string;
    tagAlreadyPresent: string;
    dealStageChanged: string;
    ownerRemoved: string;
    ownerAssigned: string;
    requestCompleted: string;
};

/** Client interaction state layered over one viewer-safe tool-call projection. */
export type AskConnexToolCardState = AiAssistantToolCall & {
    pendingAction: AskConnexToolAction | null;
    failure: AskConnexToolCardFailure | null;
    undoBlocked: boolean;
};

/** Events that reconcile server tool-call state with local card actions. */
export type AskConnexToolCardsEvent =
    | { type: 'replace'; toolCalls: readonly AiAssistantToolCall[] }
    | { type: 'actionStarted'; toolCallId: number; action: AskConnexToolAction }
    | {
        type: 'actionFailed';
        toolCallId: number;
        action: AskConnexToolAction;
        failure: AskConnexToolCardFailure;
    }
    | {
        type: 'actionApplied';
        toolCallId: number;
        action: AskConnexToolAction;
        mutation: AiAssistantToolCallMutation;
    }
    | { type: 'actionSettled'; toolCall: AiAssistantToolCall }
    | { type: 'reset' };

/** Tool cards partitioned by their transcript message or nearest visible turn position. */
export type AskConnexToolCardAnchors = {
    byMessageId: ReadonlyMap<number, AskConnexToolCardState[]>;
    afterMessageId: ReadonlyMap<number, AskConnexToolCardState[]>;
    beforeMessages: AskConnexToolCardState[];
};

/** Initial assistant tool-card collection for an unloaded or empty session. */
export const EMPTY_ASK_CONNEX_TOOL_CARDS: AskConnexToolCardState[] = [];

/** Initial state for a provider with no active or recently completed turn. */
export const EMPTY_ASK_CONNEX_TURN: AskConnexTurnState = {
    phase: 'idle',
    sessionId: null,
    turnId: null,
    generationHandle: null,
    reason: null,
    progress: [],
    cancellable: false,
};

function toolCardState(toolCall: AiAssistantToolCall): AskConnexToolCardState {
    return {
        ...toolCall,
        pendingAction: null,
        failure: null,
        undoBlocked: false,
    };
}

function sameToolCallProjection(
    current: AskConnexToolCardState,
    incoming: AiAssistantToolCall,
): boolean {
    return current.status === incoming.status
        && current.updatedAt === incoming.updatedAt
        && current.undoAvailable === incoming.undoAvailable
        && current.undoExpiresAt === incoming.undoExpiresAt
        && current.change?.state === incoming.change?.state
        && current.change?.currentValue === incoming.change?.currentValue
        && current.change?.proposedValue === incoming.change?.proposedValue;
}

function compareToolCalls(
    left: AiAssistantToolCall,
    right: AiAssistantToolCall,
): number {
    if (left.turnId !== right.turnId) return left.turnId - right.turnId;
    const createdAt = Date.parse(left.createdAt) - Date.parse(right.createdAt);
    if (Number.isFinite(createdAt) && createdAt !== 0) return createdAt;
    return left.id - right.id;
}

/** Merges overlapping tool-call reads into stable turn chronology with the newest projection kept. */
export function mergeAskConnexToolCalls(
    ...collections: readonly (readonly AiAssistantToolCall[])[]
): AiAssistantToolCall[] {
    const byId = new Map<number, AiAssistantToolCall>();
    for (const collection of collections) {
        for (const toolCall of collection) byId.set(toolCall.id, toolCall);
    }
    return [...byId.values()].toSorted(compareToolCalls);
}

/** Reduces canonical tool-call refreshes and in-flight actions without re-arming terminal cards. */
export function reduceAskConnexToolCards(
    state: AskConnexToolCardState[],
    event: AskConnexToolCardsEvent,
): AskConnexToolCardState[] {
    if (event.type === 'reset') return EMPTY_ASK_CONNEX_TOOL_CARDS;
    if (event.type === 'replace') {
        const currentById = new Map(state.map((card) => [card.id, card]));
        return event.toolCalls.map((toolCall) => {
            const current = currentById.get(toolCall.id);
            if (!current || !sameToolCallProjection(current, toolCall)) {
                return toolCardState(toolCall);
            }
            return {
                ...toolCall,
                pendingAction: current.pendingAction,
                failure: current.failure,
                undoBlocked: current.undoBlocked,
            };
        });
    }
    if (event.type === 'actionSettled') {
        return state.map((card) => card.id === event.toolCall.id
            ? toolCardState(event.toolCall)
            : card);
    }
    return state.map((card) => {
        if (card.id !== event.toolCallId) return card;
        if (event.type === 'actionStarted') {
            return { ...card, pendingAction: event.action, failure: null };
        }
        if (card.pendingAction !== event.action) return card;
        if (event.type === 'actionFailed') {
            return {
                ...card,
                pendingAction: null,
                failure: event.failure,
                undoBlocked: card.undoBlocked || event.failure === 'undoConflict',
            };
        }
        if (event.type === 'actionApplied') {
            return {
                ...card,
                status: event.mutation.status,
                undoAvailable: event.mutation.undoAvailable,
                undoExpiresAt: event.mutation.undoExpiresAt,
                pendingAction: null,
                failure: null,
                undoBlocked: false,
            };
        }
        return card;
    });
}

/** Returns controls allowed by a card's tier, durable state, and live undo deadline. */
export function askConnexToolCardAffordances(
    card: AskConnexToolCardState,
    now: number,
): AskConnexToolAction[] {
    if (card.tier === 'confirm' && card.status === 'proposed') {
        if (card.failure === 'proposalUnavailable' || card.failure === 'undoConflict') return [];
        return askConnexProposalAppliable(card) ? ['reject', 'approve'] : ['reject'];
    }
    if (card.tier !== 'auto'
        || card.status !== 'executed'
        || !card.undoAvailable
        || card.undoBlocked
        || card.undoExpiresAt === null) return [];
    const expiresAt = Date.parse(card.undoExpiresAt);
    return Number.isFinite(expiresAt) && now <= expiresAt ? ['undo'] : [];
}

/** Derives the visual expiry state for an executed auto-tier card. */
export function askConnexToolCardStatus(
    card: AskConnexToolCardState,
    now: number,
): AskConnexToolCardState['status'] | 'expired' {
    if (card.tier !== 'auto' || card.status !== 'executed' || card.undoExpiresAt === null) {
        return card.status;
    }
    const expiresAt = Date.parse(card.undoExpiresAt);
    return Number.isFinite(expiresAt) && now > expiresAt ? 'expired' : card.status;
}

/**
 * Whether a proposal can still be applied as it was reviewed.
 *
 * Only a change the server established as applicable arms the apply control. A change that would
 * do nothing, one whose proposed value no longer exists in this workspace, one the viewer has lost
 * the permission to make, and one whose record has been written since the proposal was made are all
 * shown with their reason and without the control, so nobody presses a button whose only possible
 * answer is a refusal.
 *
 * A withheld change is not an applicable one. The server sends no change at all for a proposal
 * this viewer did not raise or whose target it cannot currently show them, and arming apply on
 * that absence would offer a control over a record the reader is not even allowed to see the
 * before-value of — the one case where the answer is certainly a refusal.
 */
export function askConnexChangeApplicable(change: AiAssistantToolCallChange | null): boolean {
    if (change === null) return false;
    return change.state === 'ready';
}

/**
 * Whether one reviewed proposal can still be part of what a member applies.
 *
 * The single rule behind both the standalone card's apply control and the grouped review's
 * selection, so a row cannot be counted into a batch that the card beside it would refuse to
 * offer. A proposal whose last attempt was turned away on its own terms — the target moved, the
 * permission went — is out until it is re-read; one that merely failed to reach the server stays
 * in, because retrying it is the whole point.
 */
export function askConnexProposalAppliable(card: AskConnexToolCardState): boolean {
    return (card.failure === null || card.failure === 'actionFailed')
        && askConnexChangeApplicable(card.change);
}

/** How an executed action's undo window reads right now. */
export type AskConnexUndoWindow =
    | { state: 'none' }
    | { state: 'open'; expiresAt: string; remainingMs: number }
    | { state: 'closed'; expiresAt: string };

/**
 * The real remaining undo availability, from the server's own deadline.
 *
 * The deadline is an absolute instant the server wrote when it ran the action, so the window
 * closes at the same moment for every client regardless of how long a transcript sat open. An
 * unparseable or absent deadline is reported as no window rather than as an open one: a card that
 * cannot state when undo stops being possible must not offer it.
 *
 * The window is also the sentence the card writes, so it is gated on the same capability the
 * control is. A viewer watching a colleague's action, one who has lost the permission the inverse
 * needs, one whose session has been archived, and one whose undo already failed against a changed
 * record all have a deadline and no way to act on it; telling them they "can undo this until" a
 * time when they cannot is the card lying about what it offers.
 */
export function askConnexUndoWindow(
    card: AskConnexToolCardState,
    now: number,
): AskConnexUndoWindow {
    if (card.tier !== 'auto'
        || card.status !== 'executed'
        || !card.undoAvailable
        || card.undoBlocked
        || card.undoExpiresAt === null) {
        return { state: 'none' };
    }
    const expiresAt = Date.parse(card.undoExpiresAt);
    if (!Number.isFinite(expiresAt)) return { state: 'none' };
    if (now > expiresAt) return { state: 'closed', expiresAt: card.undoExpiresAt };
    return { state: 'open', expiresAt: card.undoExpiresAt, remainingMs: expiresAt - now };
}

/**
 * One answer's pending proposals, reviewed together.
 *
 * Grouping is by the answer that raised them, because that is the decision the member is actually
 * making: "this reply wants to make three changes." Only proposals this member can act on join a
 * group — a card they are watching rather than deciding is not part of their review.
 *
 * The counts cover the whole answer, not just what is still waiting: an answer whose batch half
 * succeeded has to be able to say so, and a member who comes back to it later should read the same
 * account of it that they were given at the time.
 */
export type AskConnexProposalGroup = {
    turnId: number;
    messageId: number | null;
    cards: AskConnexToolCardState[];
    /** Proposals whose change the server established as still applicable. */
    applicable: number;
    /** Proposals the member has kept in the batch, by tool-call id. */
    included: ReadonlySet<number>;
    /** Included proposals that can actually be applied, which is what the footer counts. */
    selected: number;
    /** Proposals from this answer the member has already applied. */
    applied: number;
    /** Proposals from this answer the member discarded. */
    discarded: number;
    /** Still-pending proposals whose last attempt did not go through. */
    failed: number;
};

/**
 * The number of proposals from one answer below which grouped review is not offered.
 *
 * Two changes are two single-record decisions, and single-record review is drawer work: a member
 * who asked one question and got two proposals must be able to answer both where they are, without
 * being sent to another surface first. Three is where an answer stops being a couple of decisions
 * and becomes a batch to work through, which is what the review surface is for.
 */
export const ASK_CONNEX_GROUPED_REVIEW_MINIMUM = 3;

/**
 * Collects each answer's pending proposals into one reviewable group.
 *
 * Exclusions arrive from the surface rather than being held here so the same set survives a card
 * refresh: a proposal the member took out of the batch must stay out while the transcript reloads
 * around it. Anything not excluded is included, which keeps a newly arrived proposal in the batch
 * by default and never silently drops one from the count.
 *
 * The membership of a group is deliberately not pinned when the review is first drawn. A proposal
 * that arrives while the member is still reading — only possible from their own turn, still
 * running, since a later turn forms its own group — joins the batch already selected. The
 * alternative is worse: a batch that quietly leaves out a change the answer asked for, with a count
 * the member reads as covering the whole answer. The new row is drawn above the control with its
 * own values and its own reason, and nothing is written until apply is pressed.
 */
export function askConnexProposalGroups(
    cards: readonly AskConnexToolCardState[],
    actionableToolCallIds: ReadonlySet<number>,
    excludedToolCallIds: ReadonlySet<number>,
): AskConnexProposalGroup[] {
    const byTurn = new Map<number, AskConnexToolCardState[]>();
    const decidedByTurn = new Map<number, AskConnexToolCardState[]>();
    for (const card of cards) {
        if (card.tier !== 'confirm' || !actionableToolCallIds.has(card.id)) continue;
        const bucket = card.status === 'proposed' ? byTurn : decidedByTurn;
        bucket.set(card.turnId, [...(bucket.get(card.turnId) ?? []), card]);
    }
    const groups: AskConnexProposalGroup[] = [];
    for (const [turnId, grouped] of byTurn) {
        if (grouped.length < ASK_CONNEX_GROUPED_REVIEW_MINIMUM) continue;
        const ordered = grouped.toSorted(compareToolCalls);
        const included = new Set<number>();
        for (const card of ordered) {
            if (!excludedToolCallIds.has(card.id)) included.add(card.id);
        }
        const decided = decidedByTurn.get(turnId) ?? [];
        groups.push({
            turnId,
            messageId: ordered.find((card) => card.messageId !== null)?.messageId ?? null,
            cards: ordered,
            applicable: ordered.filter(askConnexProposalAppliable).length,
            included,
            selected: ordered.filter(
                (card) => included.has(card.id) && askConnexProposalAppliable(card),
            ).length,
            applied: decided.filter((card) => card.status === 'executed').length,
            discarded: decided.filter((card) => card.status === 'rejected').length,
            failed: ordered.filter((card) => card.failure !== null).length,
        });
    }
    return groups.toSorted((left, right) => left.turnId - right.turnId);
}

/** Tool-call ids that a grouped review is presenting, so their cards are not also rendered alone. */
export function askConnexGroupedToolCallIds(
    groups: readonly AskConnexProposalGroup[],
): ReadonlySet<number> {
    const grouped = new Set<number>();
    for (const group of groups) {
        for (const card of group.cards) grouped.add(card.id);
    }
    return grouped;
}

/** Adds or removes one proposal from the batch a grouped review would apply. */
export function toggleAskConnexProposalExclusion(
    excluded: ReadonlySet<number>,
    toolCallId: number,
): Set<number> {
    const next = new Set(excluded);
    if (!next.delete(toolCallId)) next.add(toolCallId);
    return next;
}

/** Resolves a viewer-authorized assistant tool target to its record-detail route. */
export function askConnexToolTargetHref(target: AiAssistantToolCall['target']): string | null {
    if (target.id === null) return null;
    if (target.kind === 'person') return `/records/contacts/${target.id}`;
    if (target.kind === 'company') return `/records/companies/${target.id}`;
    return `/records/deals/${target.id}`;
}

/**
 * Resolves the record an assistant action created to its own detail route.
 *
 * A completed action's first offer should be the thing it made, not the record it hung it off:
 * "open task" is what a member who just had a task created wants, and the contact it belongs to is
 * one click further on from there. Every kind an action can create has a detail route, so this
 * never has to fall back — a kind that ever loses one would return null and leave the related
 * record as the card's link rather than fabricating a URL.
 */
export function askConnexCreatedRecordHref(
    createdRecord: AiAssistantToolCallCreatedRecord | null,
): string | null {
    if (createdRecord === null) return null;
    if (createdRecord.kind === 'activity') return `/activity/activities/${createdRecord.id}`;
    if (createdRecord.kind === 'task') return `/activity/tasks/${createdRecord.id}`;
    return `/activity/notes/${createdRecord.id}`;
}

/** The fields a completed assistant action reports values for, in the order they are shown. */
export const ASK_CONNEX_OUTCOME_FIELDS = [
    'type',
    'subject',
    'start',
    'description',
    'dueDate',
    'title',
    'visibility',
    'tag',
    'stage',
    'owner',
] as const;

/** One field a completed assistant action reports a value for. */
export type AskConnexOutcomeField = (typeof ASK_CONNEX_OUTCOME_FIELDS)[number];

/**
 * Narrows one reported field name to a field this client actually has words for.
 *
 * The server names outcome fields rather than pre-rendering them, which means a field it starts
 * reporting before this client learns the word for it must read as something honest rather than as
 * an English identifier dropped into a Japanese interface.
 */
export function isAskConnexOutcomeField(field: string): field is AskConnexOutcomeField {
    return (ASK_CONNEX_OUTCOME_FIELDS as readonly string[]).includes(field);
}

function summaryValue(summary: string, prefix: string): string | null {
    return summary.startsWith(prefix) && summary.length > prefix.length
        ? summary.slice(prefix.length).trim()
        : null;
}

/** Localizes one resolved tool request while retaining viewer-safe dynamic record values. */
export function askConnexToolRequestSummary(
    toolCall: AiAssistantToolCall,
    labels: AskConnexToolSummaryLabels,
): string {
    if (toolCall.toolName === 'create_activity') return labels.createActivity;
    if (toolCall.toolName === 'create_task') return labels.createTask;
    if (toolCall.toolName === 'create_note') return labels.createNote;
    if (toolCall.toolName === 'add_tag') return labels.addTag;
    if (toolCall.toolName === 'change_deal_stage') {
        const stage = summaryValue(toolCall.requestSummary, 'Change deal stage to:');
        return stage === null ? labels.changeDealStage : labels.changeDealStageTo(stage);
    }
    if (toolCall.toolName === 'assign_owner') {
        if (toolCall.requestSummary === 'Remove the current owner') return labels.removeOwner;
        const owner = summaryValue(toolCall.requestSummary, 'Assign owner:');
        return owner === null ? labels.assignOwner : labels.assignOwnerTo(owner);
    }
    return labels.runWriteTool;
}

/** Localizes one terminal tool outcome without exposing private result data. */
export function askConnexToolOutcomeSummary(
    toolCall: AiAssistantToolCall,
    labels: AskConnexToolSummaryLabels,
): string | null {
    if (toolCall.outcomeSummary === null) return null;
    if (toolCall.status === 'rejected') return labels.requestRejected;
    if (toolCall.status === 'failed') return labels.requestFailed;
    if (toolCall.status === 'undone') return labels.createdRecordRemoved;
    if (toolCall.toolName === 'create_activity') return labels.activityCreated;
    if (toolCall.toolName === 'create_task') return labels.taskCreated;
    if (toolCall.toolName === 'create_note') return labels.noteCreated;
    if (toolCall.toolName === 'add_tag') {
        if (toolCall.outcomeSummary === 'Tag added') return labels.tagAdded;
        if (toolCall.outcomeSummary === 'Tag was already present') return labels.tagAlreadyPresent;
        return labels.requestCompleted;
    }
    if (toolCall.toolName === 'change_deal_stage') return labels.dealStageChanged;
    if (toolCall.toolName === 'assign_owner') {
        return toolCall.outcomeSummary === 'Owner removed'
            ? labels.ownerRemoved
            : labels.ownerAssigned;
    }
    return labels.requestCompleted;
}

function toolCardRequesterMessage(
    card: AskConnexToolCardState,
    messages: readonly AiChatMessage[],
): AiChatMessage | null {
    if (card.messageId !== null) {
        const assistantMessage = messages.find((message) => message.id === card.messageId);
        if (!assistantMessage) return null;
        let requester: AiChatMessage | null = null;
        for (const message of messages) {
            if (message.authorKind !== 'user' || message.seq >= assistantMessage.seq) continue;
            if (requester === null || message.seq > requester.seq) requester = message;
        }
        return requester;
    }
    const toolCreatedAt = Date.parse(card.createdAt);
    if (!Number.isFinite(toolCreatedAt)) return null;
    let requester: AiChatMessage | null = null;
    for (const message of messages) {
        if (message.authorKind !== 'user') continue;
        const messageCreatedAt = Date.parse(message.createdAt);
        if (!Number.isFinite(messageCreatedAt) || messageCreatedAt > toolCreatedAt) continue;
        if (requester === null || message.seq > requester.seq) requester = message;
    }
    return requester;
}

/** Returns tool calls whose visible originating turn belongs to the current viewer. */
export function actionableAskConnexToolCallIds(
    cards: readonly AskConnexToolCardState[],
    messages: readonly AiChatMessage[],
    currentUserId: number | null,
): ReadonlySet<number> {
    if (currentUserId === null) return new Set();
    const actionable = new Set<number>();
    for (const card of cards) {
        const requester = toolCardRequesterMessage(card, messages);
        if (requester?.authorUserId === currentUserId) actionable.add(card.id);
    }
    return actionable;
}

/** Anchors cards to visible assistant messages or their originating user-message position. */
export function anchorAskConnexToolCards(
    cards: readonly AskConnexToolCardState[],
    messages: readonly AiChatMessage[],
): AskConnexToolCardAnchors {
    const visibleMessageIds = new Set(messages.map((message) => message.id));
    const byMessageId = new Map<number, AskConnexToolCardState[]>();
    const afterMessageId = new Map<number, AskConnexToolCardState[]>();
    const beforeMessages: AskConnexToolCardState[] = [];
    for (const card of cards) {
        if (card.messageId !== null) {
            if (!visibleMessageIds.has(card.messageId)) continue;
            byMessageId.set(card.messageId, [...(byMessageId.get(card.messageId) ?? []), card]);
            continue;
        }
        const requester = toolCardRequesterMessage(card, messages);
        if (requester === null) {
            beforeMessages.push(card);
            continue;
        }
        afterMessageId.set(
            requester.id,
            [...(afterMessageId.get(requester.id) ?? []), card],
        );
    }
    const orderedAfterMessageId = new Map<number, AskConnexToolCardState[]>();
    for (const message of messages.toSorted((left, right) => left.seq - right.seq)) {
        const anchored = afterMessageId.get(message.id);
        if (anchored) orderedAfterMessageId.set(message.id, anchored.toSorted(compareToolCalls));
    }
    return {
        byMessageId,
        afterMessageId: orderedAfterMessageId,
        beforeMessages: beforeMessages.toSorted(compareToolCalls),
    };
}

/** Builds the active-session key using the established user/workspace preference scheme. */
export function askConnexSessionStorageKey(userId: number | null, workspaceId: number | null): string {
    return viewPreferenceStorageKey('ask-connex:session', userId, workspaceId);
}

/** Builds the in-flight-turn key using the established user/workspace preference scheme. */
export function askConnexTurnStorageKey(userId: number | null, workspaceId: number | null): string {
    return viewPreferenceStorageKey('ask-connex:turn', userId, workspaceId);
}

/** Builds the pinned-context key using the established user/workspace preference scheme. */
export function askConnexPinnedStorageKey(userId: number | null, workspaceId: number | null): string {
    return viewPreferenceStorageKey('ask-connex:pinned', userId, workspaceId);
}

const ASK_CONNEX_PROGRESS_SOURCES: ReadonlySet<string> = new Set(AI_CHAT_PROGRESS_SOURCES);

/**
 * Narrows an untrusted realtime `tool` value to a known progress milestone.
 *
 * The allowlist is derived from {@link AI_CHAT_PROGRESS_SOURCES}, the same tuple the union is built
 * from, so the runtime check and the type can never disagree. A hand-written list here previously
 * omitted four backend sources, and because an unrecognized source discards the whole frame, those
 * milestones silently vanished from the live trail.
 * @param value untrusted value from a realtime frame
 * @returns whether the value names a known progress milestone
 */
export function isAskConnexProgressSource(value: unknown): value is AiChatProgressSource {
    return typeof value === 'string' && ASK_CONNEX_PROGRESS_SOURCES.has(value);
}

function isPinnedKind(value: unknown): value is AiChatPageContextKind {
    return value === 'person' || value === 'company' || value === 'deal';
}

/**
 * Parses persisted pins at the browser-storage trust boundary. Anything malformed is dropped
 * rather than repaired, so a tampered entry can never widen what a request reads.
 */
export function parseStoredAskConnexPins(value: string | null): AskConnexAttachment[] {
    if (value == null) return [];
    let parsed: unknown;
    try {
        parsed = JSON.parse(value);
    } catch {
        return [];
    }
    if (!Array.isArray(parsed)) return [];
    const pins: AskConnexAttachment[] = [];
    const seen = new Set<string>();
    for (const entry of parsed) {
        if (typeof entry !== 'object' || entry === null) continue;
        if (!('kind' in entry) || !('id' in entry) || !('label' in entry)) continue;
        const { kind, id, label } = entry;
        if (!isPinnedKind(kind)) continue;
        if (typeof id !== 'number' || !Number.isSafeInteger(id) || id <= 0) continue;
        if (typeof label !== 'string' || label.trim().length === 0) continue;
        const key = `${kind}:${id}`;
        if (seen.has(key)) continue;
        seen.add(key);
        pins.push({ kind, id, label: label.trim() });
        if (pins.length === ASK_CONNEX_CONTEXT_LIMIT) break;
    }
    return pins;
}

/** Serializes pins for scoped browser storage. */
export function serializeAskConnexPins(pins: readonly AskConnexAttachment[]): string {
    return JSON.stringify(pins);
}

/** Adds or removes one pin, keeping the newest first and never exceeding the per-request cap. */
export function toggleAskConnexPin(
    pins: readonly AskConnexAttachment[],
    attachment: AskConnexAttachment,
): AskConnexAttachment[] {
    const remaining = pins.filter(
        (pin) => pin.kind !== attachment.kind || pin.id !== attachment.id,
    );
    if (remaining.length !== pins.length) return remaining;
    return [attachment, ...remaining].slice(0, ASK_CONNEX_CONTEXT_LIMIT);
}

/** Whether a record is currently kept across navigation. */
export function isAskConnexPinned(
    pins: readonly AskConnexAttachment[],
    attachment: AskConnexAttachment | null,
): boolean {
    if (attachment === null) return false;
    return pins.some((pin) => pin.kind === attachment.kind && pin.id === attachment.id);
}

/** Whether the user has taken something out of the context the page offered. */
export function askConnexContextCorrected(corrections: AskConnexContextCorrections): boolean {
    return corrections.pageDismissed || corrections.selectionDismissed;
}

/**
 * Summarizes what a request will read, or null when it is narrow enough to run unannounced.
 *
 * The counts come from the records the request will actually carry, so the summary can never
 * promise a breadth the turn does not have. Attached files are reported but do not by themselves
 * make a request broad: their cost is bounded at upload, unlike a carried browser selection.
 */
export function askConnexScopePreview(
    pageContext: readonly AiChatPageContext[],
    files: readonly AskConnexFileAttachment[],
): AskConnexScopePreview | null {
    if (pageContext.length < ASK_CONNEX_SCOPE_PREVIEW_THRESHOLD) return null;
    const counts = new Map<AiChatPageContextKind, number>();
    for (const entry of pageContext) {
        counts.set(entry.kind, (counts.get(entry.kind) ?? 0) + 1);
    }
    const recordIdentity = [...new Set(
        pageContext.map((entry) => `${entry.kind}:${entry.id}`),
    )].sort();
    const fileIdentity = files.map((file) => file.clientId).sort();
    return {
        total: pageContext.length,
        records: [...counts.entries()].map(([kind, count]) => ({ kind, count })),
        files: files.length,
        identity: `${recordIdentity.join(',')}/${fileIdentity.join(',')}`,
    };
}

/**
 * The interpreted breadth of the filters a request declares, as the server evaluated them.
 *
 * Everything here comes from the server's own interpretation of the declared filters, so the
 * sentence built from it states the query that will actually run rather than the one that was
 * asked for.
 */
export type AskConnexDeclaredScope = {
    /** Stable identity of exactly these filters, asked in exactly this question, from this page. */
    identity: string;
    /** Whether the server asked for this breadth to be reviewed before it runs. */
    confirmationRecommended: boolean;
    /**
     * What the server established these filters cover, or null when it has established nothing.
     *
     * Null is the honest answer whenever the measurement does not describe the request that would
     * actually run: a preview still in flight, one the allowance turned away, one that failed, and
     * one taken for a different question all leave the breadth unknown. Unknown is never treated as
     * narrow — a request nobody could measure is announced rather than run.
     */
    matched: {
        count: number | null;
        /** Whether the cohort exceeded the cap and the request will read only part of it. */
        truncated: boolean;
        recordCap: number;
    } | null;
    /** Whether the missing measurement is still being read, rather than having failed outright. */
    measuring: boolean;
};

/**
 * Everything the next request will read: the records it carries and the filters it declares.
 *
 * Both halves are held together because they are one question to the member — a request narrowed by
 * filters and a request carrying a browser selection are both "what will this cover" — and because
 * agreeing to one breadth has to mean agreeing to that exact combination of the two.
 */
export type AskConnexRequestScope = {
    records: AskConnexScopePreview | null;
    declared: AskConnexDeclaredScope | null;
    /**
     * The identity confirmation belongs to, or null when this request is narrow enough to run
     * unannounced. Changing either half changes the identity and re-arms the confirmation.
     */
    identity: string | null;
};

/** No breadth worth announcing: an unfiltered question about one record or none. */
export const EMPTY_ASK_CONNEX_REQUEST_SCOPE: AskConnexRequestScope = {
    records: null,
    declared: null,
    identity: null,
};

/**
 * Combines what a request carries with what it declares into one reviewable breadth.
 *
 * Confirmation is armed by any of three things: enough carried records to be broad on their own,
 * filters the server itself asked to have reviewed, or filters whose breadth nothing established
 * before the request went out. That last one is the whole point of announcing breadth at all — a
 * scope that will be attached to the turn but was never measured is exactly the case where nobody
 * can say the request is narrow, so it is treated as broad rather than waved through.
 *
 * Filters that narrow a request rather than widening it are shown as chips but do not by themselves
 * hold the request back.
 */
export function askConnexRequestScope(
    records: AskConnexScopePreview | null,
    declared: AskConnexDeclaredScope | null,
): AskConnexRequestScope {
    const armed = records !== null
        || (declared !== null && (declared.confirmationRecommended || declared.matched === null));
    return {
        records,
        declared,
        identity: armed
            ? `${records?.identity ?? ''}/${declared?.identity ?? ''}`
            : null,
    };
}

/**
 * A stable identity for one interpreted scope.
 *
 * Agreeing to review a scope agrees to *that* scope, not to any scope of the same shape: the key is
 * the sorted set of record identities and attached files, so swapping five records for five
 * different ones re-arms the confirmation exactly as adding a sixth does. Files are included
 * because replacing an attachment changes what the request reads just as replacing a record does.
 */
export function askConnexScopePreviewKey(preview: AskConnexScopePreview | null): string | null {
    return preview === null ? null : preview.identity;
}

/** Parses a persisted positive integer id without accepting partial or unsafe numbers. */
export function parseStoredAskConnexSession(value: string | null): number | null {
    if (value == null || !/^[1-9]\d*$/.test(value)) return null;
    const parsed = Number(value);
    return Number.isSafeInteger(parsed) ? parsed : null;
}

/** Parses a persisted turn descriptor at the browser-storage trust boundary. */
export function parseStoredAskConnexTurn(value: string | null): StoredAskConnexTurn | null {
    if (value == null) return null;
    let parsed: unknown;
    try {
        parsed = JSON.parse(value);
    } catch {
        return null;
    }
    if (typeof parsed !== 'object' || parsed === null) return null;
    if (!('sessionId' in parsed) || !('turnId' in parsed) || !('generationHandle' in parsed)) return null;
    const { sessionId, turnId, generationHandle } = parsed;
    if (typeof sessionId !== 'number' || !Number.isSafeInteger(sessionId) || sessionId <= 0) return null;
    if (typeof turnId !== 'number' || !Number.isSafeInteger(turnId) || turnId <= 0) return null;
    if (typeof generationHandle !== 'string' || generationHandle.length === 0) return null;
    return { sessionId, turnId, generationHandle };
}

/** Serializes one accepted turn for scoped browser storage. */
export function serializeStoredAskConnexTurn(turn: StoredAskConnexTurn): string {
    return JSON.stringify(turn);
}

/** Extracts supported, de-duplicated record references from MentionEditor content. */
export function extractAskConnexAttachments(content: string): AskConnexAttachment[] {
    const references: AskConnexAttachment[] = [];
    const seen = new Set<string>();
    for (const match of content.matchAll(REFERENCE_TOKEN)) {
        const kind = match[2];
        const id = Number(match[3]);
        if (kind !== 'person' && kind !== 'company' && kind !== 'deal') continue;
        const key = `${kind}:${id}`;
        if (!Number.isSafeInteger(id) || seen.has(key)) continue;
        seen.add(key);
        references.push({ kind, id, label: match[1].trim() });
    }
    return references;
}

/**
 * Serializes one record as the reference chip the composer and the context strip both understand.
 *
 * The label is written without the bracket characters the token syntax uses, so a record named with
 * one cannot break the reference it appears in. Copy that needs the record inside a sentence takes
 * this as a value, which is the only way a Japanese sentence can put the name where it belongs.
 */
export function askConnexMentionToken(attachment: AskConnexAttachment): string {
    const label = attachment.label.replaceAll(/[[\]]/g, '').trim();
    return label.length === 0 ? '' : `[${label}](${attachment.kind}:${attachment.id})`;
}

/**
 * Composes the message a contextual entry point hands to the composer.
 *
 * Records the surface knows about but the page does not carry are appended as ordinary reference
 * chips — the same tokens the mention picker writes — so an entry point opened from a list or a
 * signal card adds context through the one mechanism the cockpit already shows and the member can
 * already take back out. A label is written without the bracket characters the token syntax uses, so
 * a record named with one cannot break the reference it appears in.
 *
 * @param prompt the job stated in the member's language
 * @param mentions records the prompt is about that the current page does not already carry
 * @returns composer content carrying the prompt and its references
 */
export function askConnexPromptContent(
    prompt: string,
    mentions: readonly AskConnexAttachment[] = [],
): string {
    const seen = new Set<string>();
    const references: string[] = [];
    for (const mention of mentions) {
        const key = `${mention.kind}:${mention.id}`;
        if (seen.has(key)) continue;
        seen.add(key);
        const token = askConnexMentionToken(mention);
        if (token.length === 0) continue;
        references.push(token);
    }
    return [prompt.trim(), ...references].filter((part) => part.length > 0).join(' ');
}

/**
 * Adds one offered job to whatever the composer already holds.
 *
 * The single rule every surface that offers a job follows — a record's entry point, the strip above
 * the composer, and the empty state alike — because a half-written question is the member's work and
 * an offer is never worth destroying it for. An empty composer takes the job as its whole content; a
 * composer with anything in it keeps it and the job joins it below.
 *
 * @param composer what is in the composer now
 * @param job the offered question, already in the member's language
 * @returns the composer's new content
 */
export function appendAskConnexPrompt(composer: string, job: string): string {
    if (job.length === 0) return composer;
    return composer.trim().length === 0 ? job : `${composer}\n\n${job}`;
}

/**
 * Whether a surface should land the member in the composer for a job just written into it.
 *
 * The outstanding request lives with the composer's own content, in the provider, rather than in the
 * surface that honours it: a phone does not keep the panel mounted, so a mark held inside the
 * surface dies with it and the next plain open replays a request that was already honoured — which
 * on a phone means raising the keyboard over a conversation nobody asked to type into. The provider
 * clears the request as it is consumed, so a surface that mounts with none takes no focus.
 *
 * @param open whether the surface is actually on screen
 * @param promptRequest the provider's outstanding job request, or zero when there is none
 * @returns whether to move focus to the end of the composer
 */
export function askConnexPromptFocusPending(open: boolean, promptRequest: number): boolean {
    return open && promptRequest > 0;
}

/** Converts serialized reference chips back to readable prompt text before submission. */
export function askConnexMessageContent(content: string): string {
    return content.replace(REFERENCE_TOKEN, '$1').trim();
}

/** Removes one attached record token from MentionEditor content. */
export function removeAskConnexAttachment(content: string, attachment: AskConnexAttachment): string {
    return content
        .replace(REFERENCE_TOKEN, (token, _label: string, kind: string, id: string) =>
            kind === attachment.kind && Number(id) === attachment.id ? '' : token)
        .replace(/[ \t]{2,}/g, ' ')
        .trimStart();
}

/** Converts the current action record to the assistant's supported context contract. */
export function activeRecordAskConnexContext(record: ActiveRecordRef | null): AskConnexAttachment | null {
    if (record == null || (record.type !== 'person' && record.type !== 'company' && record.type !== 'deal')) {
        return null;
    }
    const id = typeof record.id === 'number' ? record.id : Number(record.id);
    if (!Number.isSafeInteger(id) || id <= 0) return null;
    return { kind: record.type, id, label: record.label };
}

/** Converts the current list selection without silently dropping unsupported or invalid rows. */
export function activeSelectionAskConnexContext(
    selection: ActiveSelection | null,
): AskConnexSelectionContext | null {
    if (selection == null || selection.ids.size === 0) return null;
    const supportedType = selection.type === 'person'
        || selection.type === 'company'
        || selection.type === 'deal'
        ? selection.type
        : null;
    const ids = selection.scope.kind === 'single_record'
        ? [selection.scope.recordId]
        : selection.scope.kind === 'page_selection' || selection.scope.kind === 'explicit_selection'
            ? selection.scope.recordIds
            : null;
    const exactScope = ids !== null;
    const valid = ids !== null
        && ids.length > 0
        && ids.every((id) => Number.isSafeInteger(id) && id > 0)
        && new Set(ids).size === ids.length;
    return {
        type: selection.type,
        count: selection.ids.size,
        available: supportedType !== null && exactScope && valid,
        unavailableReason: supportedType === null
            ? 'record_type'
            : !exactScope
                ? 'scope'
                : valid
                    ? null
                    : 'invalid',
        pageContext: supportedType !== null && exactScope && valid
            ? ids.map((id) => ({ kind: supportedType, id }))
            : [],
    };
}

/** Copies transient action context before navigation unmounts the contributing record surface. */
export function snapshotAskConnexSourceContext(
    record: ActiveRecordRef | null,
    selection: ActiveSelection | null,
): AskConnexSourceContext {
    return {
        record: record === null ? null : { ...record },
        selection: selection === null
            ? null
            : { ...selection, ids: new Set(selection.ids) },
    };
}

/**
 * Merges implicit page context, kept records, selected rows, and mentions, and reports cap overflow
 * explicitly.
 *
 * Corrections are applied here rather than at the call sites so the chips a user sees and the
 * records a request carries are computed from one rule: anything the user took out is absent from
 * both, and anything pinned is present in both.
 */
export function mergeAskConnexContext(
    record: ActiveRecordRef | null,
    content: string,
    selection: ActiveSelection | null = null,
    corrections: AskConnexContextCorrections = EMPTY_ASK_CONNEX_CORRECTIONS,
): { pageContext: AiChatPageContext[]; attachments: AskConnexAttachment[]; overflow: boolean } {
    const implicit = corrections.pageDismissed ? null : activeRecordAskConnexContext(record);
    const selected = corrections.selectionDismissed ? null : activeSelectionAskConnexContext(selection);
    const attachments = extractAskConnexAttachments(content);
    const merged = [
        ...(implicit ? [implicit] : []),
        ...corrections.pinned,
        ...(selected?.available ? selected.pageContext : []),
        ...attachments,
    ];
    const unique = new Map(merged.map((item) => [`${item.kind}:${item.id}`, item]));
    const pageContext = [...unique.values()].map(({ kind, id }) => ({ kind, id }));
    return {
        pageContext,
        attachments,
        overflow: pageContext.length > ASK_CONNEX_CONTEXT_LIMIT,
    };
}

/** Reduces accepted and durable server states into visually distinct assistant phases. */
export function reduceAskConnexTurn(
    state: AskConnexTurnState,
    event: AskConnexTurnEvent,
): AskConnexTurnState {
    if (event.type === 'reset') return EMPTY_ASK_CONNEX_TURN;
    if (event.type === 'accepted') {
        return {
            phase: event.status === 'running' ? 'running' : 'accepted',
            sessionId: event.sessionId,
            turnId: event.turnId,
            generationHandle: event.generationHandle,
            reason: null,
            progress: event.progress ?? [],
            cancellable: event.cancellable ?? false,
        };
    }
    const phase = event.status === 'queued' || event.status === 'accepted'
        ? 'accepted'
        : event.status === 'running'
          ? 'running'
          : event.status === 'resolved'
            ? 'resolved'
            : event.status === 'timed_out'
              ? 'timed_out'
              : event.status === 'cancelled'
                ? 'cancelled'
                : 'failed';
    return {
        ...state,
        phase,
        reason: event.reason ?? null,
        progress: event.progress ?? state.progress,
    };
}

/** Route for one cited record, matching the records browser's detail paths. */
export function askConnexCitationHref(citation: AiChatCitation): string {
    if (citation.kind === 'person') return `/records/contacts/${citation.id}`;
    if (citation.kind === 'company') return `/records/companies/${citation.id}`;
    return `/records/deals/${citation.id}`;
}

/**
 * Citations to render beneath one assistant answer, de-duplicated by record identity and capped so a
 * long answer cannot flood the transcript.
 */
export function askConnexCitations(
    citations: AiChatCitation[] | null | undefined,
    limit = 8,
): AiChatCitation[] {
    if (!citations?.length) return [];
    const seen = new Set<string>();
    const unique: AiChatCitation[] = [];
    for (const citation of citations) {
        const key = `${citation.kind}:${citation.id}`;
        if (seen.has(key)) continue;
        seen.add(key);
        unique.push(citation);
        if (unique.length === limit) break;
    }
    return unique;
}

/** Returns the ascending transcript pages required to assemble the newest full message window. */
export function askConnexLatestMessagePages(total: number, pageSize: number): number[] {
    const latestPage = Math.max(1, Math.ceil(total / pageSize));
    if (latestPage === 1 || total % pageSize === 0) return [latestPage];
    return [latestPage - 1, latestPage];
}

/** Orders the fetched tail pages and returns at most one full newest-message window. */
export function askConnexLatestMessages(
    pages: readonly (readonly AiChatMessage[])[],
    pageSize: number,
): AiChatMessage[] {
    return pages
        .flatMap((page) => page)
        .toSorted((left, right) => left.seq - right.seq)
        .slice(-pageSize);
}

/** Removes server-only summary rows while retaining their visible transcript-marker state. */
export function askConnexTranscript(
    messages: readonly AiChatMessage[],
    sessionHistorySummarized: boolean,
): AskConnexTranscript {
    return {
        messages: messages.filter((message) => message.historySummarized !== true),
        historySummarized: sessionHistorySummarized
            || messages.some((message) => message.historySummarized === true),
    };
}

/** Loads a stable newest-message window, retrying when concurrent writes move the page boundary. */
export async function loadAskConnexLatestMessages(
    initialPage: Page<AiChatMessage>,
    pageSize: number,
    loadPage: (page: number) => Promise<Page<AiChatMessage>>,
): Promise<AiChatMessage[]> {
    let expectedTotal = initialPage.total;
    let reusableFirstPage: Page<AiChatMessage> | null = initialPage;
    for (;;) {
        const pageNumbers = askConnexLatestMessagePages(expectedTotal, pageSize);
        const pages = await Promise.all(pageNumbers.map((page) => {
            if (page === 1 && reusableFirstPage?.total === expectedTotal) {
                return reusableFirstPage;
            }
            return loadPage(page);
        }));
        if (pages.every((page) => page.total === expectedTotal)) {
            return askConnexLatestMessages(
                pages.map((page) => page.items),
                pageSize,
            );
        }
        expectedTotal = Math.max(...pages.map((page) => page.total));
        reusableFirstPage = null;
    }
}

/** Returns safe follow-up actions from only the latest settled assistant answer. */
export function latestAskConnexSuggestions(
    messages: readonly AiChatMessage[],
    working: boolean,
): string[] {
    if (working) return [];
    let latest: AiChatMessage | undefined;
    for (const message of messages) {
        if (latest === undefined || message.seq > latest.seq) latest = message;
    }
    if (latest?.authorKind !== 'assistant' || !latest.suggestions?.length) return [];
    const unique = new Set<string>();
    for (const suggestion of latest.suggestions) {
        const value = suggestion.trim();
        if (value.length === 0
            || value.length > ASK_CONNEX_SUGGESTION_LENGTH
            || value.includes('\n')
            || value.includes('\r')
            || RESOURCE_HANDLE.test(value)
            || CONTROL_INSTRUCTION.test(value)) continue;
        unique.add(value);
        if (unique.size === ASK_CONNEX_SUGGESTION_LIMIT) break;
    }
    return [...unique];
}

/**
 * The prompt a stopped answer offers to send again: the most recent thing the member themselves
 * wrote. A withheld or empty message carries nothing to resend, so it yields nothing to offer.
 */
export function askConnexRetryPrompt(messages: readonly AiChatMessage[]): string | null {
    for (let index = messages.length - 1; index >= 0; index--) {
        const message = messages[index];
        if (message === undefined || message.authorKind !== 'user') continue;
        if (message.contentWithheld === true) return null;
        const content = message.content.trim();
        return content.length === 0 ? null : content;
    }
    return null;
}

/** Groups consecutive messages by sender without crossing a required transcript insertion point. */
export function groupAskConnexMessages(
    messages: readonly AiChatMessage[],
    breakAfterMessageIds?: ReadonlySet<number>,
): AskConnexMessageGroup[] {
    const groups: AskConnexMessageGroup[] = [];
    for (const message of messages) {
        const current = groups.at(-1);
        const previous = current?.messages.at(-1);
        if (current?.authorKind === message.authorKind
            && current.authorUserId === message.authorUserId
            && (previous === undefined || !breakAfterMessageIds?.has(previous.id))) {
            current.messages.push(message);
        } else {
            groups.push({
                authorKind: message.authorKind,
                authorUserId: message.authorUserId,
                messages: [message],
            });
        }
    }
    return groups;
}
