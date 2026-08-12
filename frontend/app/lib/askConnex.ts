import type { ActiveRecordRef } from '@/app/lib/actions/types';
import type { AiChatCitation, AiChatMessage, AiChatPageContext } from '@/app/lib/types';
import { viewPreferenceStorageKey } from '@/app/hooks/viewPreference';

const REFERENCE_TOKEN = /\[([^\]]+)]\((person|company|deal):([1-9]\d*)\)/g;

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
};

/** Returns whether an upload or removal must settle before the conversation can advance. */
export function hasPendingAskConnexFileOperation(
    attachments: readonly AskConnexFileAttachment[],
): boolean {
    return attachments.some(
        (attachment) => attachment.status === 'uploading' || attachment.status === 'removing',
    );
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

/** Persisted descriptor needed to reconcile one accepted assistant turn after refresh. */
export type StoredAskConnexTurn = {
    sessionId: number;
    turnId: number;
    generationHandle: string;
};

/** Provider-owned visual phase for one assistant turn. */
export type AskConnexTurnState = {
    phase: 'idle' | 'accepted' | 'running' | 'resolved' | 'failed' | 'timed_out';
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

/** Initial state for a provider with no active or recently completed turn. */
export const EMPTY_ASK_CONNEX_TURN: AskConnexTurnState = {
    phase: 'idle',
    sessionId: null,
    turnId: null,
    generationHandle: null,
    reason: null,
};

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

/** Groups consecutive messages by sender without changing transcript order. */
export function groupAskConnexMessages(messages: readonly AiChatMessage[]): AskConnexMessageGroup[] {
    const groups: AskConnexMessageGroup[] = [];
    for (const message of messages) {
        const current = groups.at(-1);
        if (current?.authorKind === message.authorKind && current.authorUserId === message.authorUserId) {
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
