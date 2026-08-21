import type { ActiveRecordRef } from '@/app/lib/actions/types';
import type {
    AiAssistantToolCall,
    AiAssistantToolCallMutation,
    AiChatCitation,
    AiChatMessage,
    AiChatPageContext,
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
};

/** Events that advance or clear the provider-owned assistant turn state. */
export type AskConnexTurnEvent =
    | { type: 'accepted'; sessionId: number; turnId: number; generationHandle: string; status: string }
    | { type: 'status'; status: string; reason?: string | null }
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
        && current.undoExpiresAt === incoming.undoExpiresAt;
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
        if (card.failure === 'proposalChanged' || card.failure === 'proposalPermissionLost') {
            return ['reject'];
        }
        if (card.failure === null || card.failure === 'actionFailed') {
            return ['reject', 'approve'];
        }
        return [];
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

/** Resolves a viewer-authorized assistant tool target to its record-detail route. */
export function askConnexToolTargetHref(target: AiAssistantToolCall['target']): string | null {
    if (target.id === null) return null;
    if (target.kind === 'person') return `/records/contacts/${target.id}`;
    if (target.kind === 'company') return `/records/companies/${target.id}`;
    return `/records/deals/${target.id}`;
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

/** Merges implicit page context with attached records and reports cap overflow explicitly. */
export function mergeAskConnexContext(
    record: ActiveRecordRef | null,
    content: string,
): { pageContext: AiChatPageContext[]; attachments: AskConnexAttachment[]; overflow: boolean } {
    const implicit = activeRecordAskConnexContext(record);
    const attachments = extractAskConnexAttachments(content);
    const merged = implicit ? [implicit, ...attachments] : attachments;
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
    return { ...state, phase, reason: event.reason ?? null };
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
