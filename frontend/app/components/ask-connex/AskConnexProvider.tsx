'use client';

import {
    createContext,
    useCallback,
    useContext,
    useEffect,
    useMemo,
    useReducer,
    useRef,
    useState,
    type ReactNode,
} from 'react';
import { useTranslations } from 'next-intl';
import { useRouter } from 'next/navigation';

import AskConnexDrawer from '@/app/components/ask-connex/AskConnexDrawer';
import { useActions } from '@/app/hooks/useActions';
import { useIsMobile } from '@/app/hooks/useIsMobile';
import { usePermissionCheck } from '@/app/hooks/usePermissions';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import {
    ApiError,
    approveAiAssistantToolCall,
    archiveAiChatSession,
    cancelAiChatTurn,
    createAiChatSession,
    deleteAiChatAttachment,
    getActiveWorkspaceMembers,
    getAiAssistantToolCall,
    getAiAssistantToolCalls,
    getAiChatAttachments,
    getAiChatInvitations,
    getAiChatParticipants,
    getAiChatPresence,
    getAiChatSession,
    getAiChatSessions,
    getAiChatTurn,
    getAiGenerationStatus,
    inviteAiChatParticipant,
    joinAiChatSession,
    leaveAiChatPresence,
    leaveAiChatSession,
    rejectAiAssistantToolCall,
    removeAiChatParticipant,
    resolveAcceptedAiGeneration,
    setAiChatSessionShared,
    startAiChatTurn,
    touchAiChatPresence,
    undoAiAssistantToolCall,
    uploadAiChatAttachment,
    updateAiChatSession,
} from '@/app/lib/api';
import {
    EMPTY_ASK_CONNEX_TOOL_CARDS,
    EMPTY_ASK_CONNEX_TURN,
    AskConnexFileRemovalError,
    actionableAskConnexToolCallIds,
    activeRecordAskConnexContext,
    askConnexMessageContent,
    askConnexSessionStorageKey,
    askConnexTurnStorageKey,
    hasPendingAskConnexFileOperation,
    loadAskConnexLatestMessages,
    mergeAskConnexToolCalls,
    mergeAskConnexContext,
    parseStoredAskConnexSession,
    parseStoredAskConnexTurn,
    reduceAskConnexToolCards,
    reduceAskConnexTurn,
    removeReadyAskConnexFile,
    removeAskConnexAttachment,
    serializeStoredAskConnexTurn,
    type AskConnexAttachment,
    type AskConnexFileAttachment,
    type AskConnexToolAction,
    type AskConnexToolCardFailure,
    type StoredAskConnexTurn,
} from '@/app/lib/askConnex';
import {
    applyAskConnexStreamDelta,
    createAskConnexFrameCoalescer,
    createAskConnexStream,
    createAskConnexStreamStore,
    failAskConnexStreamHydration,
    requestAskConnexTurnCancel,
    settleAskConnexStreamHydration,
    type AskConnexFrameCoalescer,
    type AskConnexStreamState,
} from '@/app/lib/askConnexStream';
import { AiGenerationError } from '@/app/lib/aiGeneration';
import { createAiChatSocket } from '@/app/lib/realtime';
import { toastError, toastSuccess } from '@/app/lib/toast';
import type {
    AiChatCitation,
    AiChatAttachment,
    AiChatDeltaFrame,
    AiChatMessage,
    AiChatParticipant,
    AiChatPresence,
    AiChatSession,
    AiChatTurn,
    AiChatTurnGenerationResult,
    WorkspaceMember,
} from '@/app/lib/types';

type OpenSource = 'standard' | 'keyboard';

const ASK_CONNEX_MESSAGE_PAGE_SIZE = 50;
const EMPTY_ASK_CONNEX_TOOL_CALL_IDS: ReadonlySet<number> = new Set();

type AskConnexContextValue = {
    open: boolean;
    instantOpen: boolean;
    working: boolean;
    openDrawer: (source?: OpenSource) => void;
    closeDrawer: () => void;
};

const AskConnexContext = createContext<AskConnexContextValue | null>(null);

function isActiveTurnStatus(status: string): boolean {
    return status === 'accepted' || status === 'queued' || status === 'running';
}

function delay(milliseconds: number, signal: AbortSignal): Promise<void> {
    return new Promise((resolve, reject) => {
        if (signal.aborted) {
            reject(signal.reason);
            return;
        }
        const timer = window.setTimeout(() => {
            signal.removeEventListener('abort', abort);
            resolve();
        }, milliseconds);
        const abort = () => {
            window.clearTimeout(timer);
            reject(signal.reason);
        };
        signal.addEventListener('abort', abort, { once: true });
    });
}

function deferredErrorToast(message: string): void {
    window.setTimeout(() => toastError(message), 0);
}

function toolCardFailure(error: unknown, action: AskConnexToolAction): AskConnexToolCardFailure {
    if (!(error instanceof ApiError)) return 'actionFailed';
    if (action === 'undo' && error.status === 409) return 'undoConflict';
    if (action !== 'undo' && error.status === 409) return 'proposalChanged';
    if (action !== 'undo' && error.status === 403) return 'proposalPermissionLost';
    if (action !== 'undo' && error.status === 404) return 'proposalUnavailable';
    return 'actionFailed';
}

function safeStorageSet(key: string, value: string): void {
    try {
        window.localStorage.setItem(key, value);
    } catch {}
}

function safeStorageRemove(key: string): void {
    try {
        window.localStorage.removeItem(key);
    } catch {}
}

function safeStorageGet(key: string): string | null {
    try {
        return window.localStorage.getItem(key);
    } catch {
        return null;
    }
}

function starterPromptKeys(kind: AskConnexAttachment['kind'] | null): string[] {
    if (kind === 'person') return ['starters.person.followUp', 'starters.person.activity', 'starters.person.stalled'];
    if (kind === 'company') return ['starters.company.relationships', 'starters.company.activity', 'starters.company.risks'];
    if (kind === 'deal') return ['starters.deal.summary', 'starters.deal.risks', 'starters.deal.nextStep'];
    return ['starters.workspace.followUps', 'starters.workspace.risks', 'starters.workspace.activity'];
}

function readyFileAttachment(attachment: AiChatAttachment): AskConnexFileAttachment {
    return {
        clientId: `stored:${attachment.id}`,
        id: attachment.id,
        fileName: attachment.fileName,
        contentType: attachment.contentType,
        size: attachment.size,
        kind: attachment.kind,
        status: 'ready',
        progress: 100,
        error: null,
    };
}

/** Reads the persistent Ask Connex drawer controller from the authenticated app shell. */
export function useAskConnex(): AskConnexContextValue {
    const value = useContext(AskConnexContext);
    if (value === null) throw new Error('useAskConnex must be used within AskConnexProvider');
    return value;
}

/** Owns Ask Connex continuity, transcript, accepted-turn reconciliation, and responsive surfaces. */
export default function AskConnexProvider({ children }: { children: ReactNode }) {
    const t = useTranslations('AskConnex');
    const tDisclosure = useTranslations('Assistant.disclosure');
    const router = useRouter();
    const { context } = useActions();
    const { activeWorkspaceId, switching } = useWorkspace();
    const permission = usePermissionCheck('AI_USE');
    const attachmentCreatePermission = usePermissionCheck('ATTACHMENT_CREATE');
    const attachmentDeletePermission = usePermissionCheck('ATTACHMENT_DELETE');
    const sharePermission = usePermissionCheck('AI_SESSION_SHARE');
    const isMobile = useIsMobile();
    const userId = context.user?.id ?? null;
    const userDisplayName = context.user?.displayName ?? null;
    const identity = `${userId ?? 'anon'}:${activeWorkspaceId ?? 'none'}`;
    const sessionKey = askConnexSessionStorageKey(userId, activeWorkspaceId);
    const turnKey = askConnexTurnStorageKey(userId, activeWorkspaceId);
    const implicitContext = activeRecordAskConnexContext(context.record);

    const [open, setOpen] = useState(false);
    const [instantOpen, setInstantOpen] = useState(false);
    const [stateIdentity, setStateIdentity] = useState<string | null>(null);
    const [sessions, setSessions] = useState<AiChatSession[]>([]);
    const [invitations, setInvitations] = useState<AiChatSession[]>([]);
    const [activeSession, setActiveSession] = useState<AiChatSession | null>(null);
    const [participants, setParticipants] = useState<AiChatParticipant[]>([]);
    const [presence, setPresence] = useState<AiChatPresence | null>(null);
    const [members, setMembers] = useState<WorkspaceMember[]>([]);
    const [messages, setMessages] = useState<AiChatMessage[]>([]);
    const [freshMessageIds, setFreshMessageIds] = useState<ReadonlySet<number>>(new Set());
    const [loadState, setLoadState] = useState<'loading' | 'ready' | 'error' | 'forbidden'>('loading');
    const [loadError, setLoadError] = useState<Error | null>(null);
    const [composer, setComposer] = useState('');
    const [fileAttachments, setFileAttachments] = useState<AskConnexFileAttachment[]>([]);
    const [unavailableReason, setUnavailableReason] = useState<string | null>(null);
    const [submissionBlocked, setSubmissionBlocked] = useState(false);
    const [submitting, setSubmitting] = useState(false);
    const [reloadVersion, setReloadVersion] = useState(0);
    const [streaming, setStreaming] = useState(false);
    const [cancelling, setCancelling] = useState(false);
    const [streamStore] = useState(createAskConnexStreamStore);
    const [turn, dispatchTurn] = useReducer(reduceAskConnexTurn, EMPTY_ASK_CONNEX_TURN);
    const [toolCalls, dispatchToolCalls] = useReducer(
        reduceAskConnexToolCards,
        EMPTY_ASK_CONNEX_TOOL_CARDS,
    );

    const messagesRef = useRef<AiChatMessage[]>([]);
    const identityControllerRef = useRef<AbortController | null>(null);
    const sessionControllerRef = useRef<AbortController | null>(null);
    const tempMessageIdRef = useRef(-1);
    const sessionCreationRef = useRef<Promise<AiChatSession> | null>(null);
    const sessionEpochRef = useRef(0);
    const activeSessionRef = useRef<AiChatSession | null>(null);
    const typingRef = useRef(false);
    const sessionsRefreshVersionRef = useRef(0);
    const transcriptRefreshVersionRef = useRef(0);
    const realtimeRefreshQueueRef = useRef<Promise<void>>(Promise.resolve());
    const submittingRef = useRef(false);
    const toolActionsRef = useRef<Set<number>>(new Set());
    const streamRef = useRef<AskConnexStreamState | null>(null);
    const streamCoalescerRef = useRef<AskConnexFrameCoalescer | null>(null);
    const streamingRef = useRef(false);
    const cancellingRef = useRef(false);
    const activeTurnRef = useRef<{ sessionId: number; turnId: number } | null>(null);

    const contextResult = useMemo(
        () => mergeAskConnexContext(context.record, composer),
        [context.record, composer],
    );
    const visibleAttachments = useMemo(
        () => contextResult.attachments.filter(
            (attachment) => !implicitContext
                || attachment.kind !== implicitContext.kind
                || attachment.id !== implicitContext.id,
        ),
        [contextResult.attachments, implicitContext],
    );
    const contentTooLong = askConnexMessageContent(composer).length > 16_000;
    const fileOperationPending = hasPendingAskConnexFileOperation(fileAttachments);
    const fileContextCount = fileAttachments.filter(
        (attachment) => attachment.status !== 'failed',
    ).length;
    const contextOverflow = contextResult.pageContext.length + fileContextCount > 10;
    const toolActionPending = toolCalls.some((toolCall) => toolCall.pendingAction !== null);
    const actionableToolCallIds = useMemo(
        () => actionableAskConnexToolCallIds(toolCalls, messages, userId),
        [messages, toolCalls, userId],
    );
    const working = submitting
        || turn.phase === 'accepted'
        || turn.phase === 'running'
        || fileOperationPending
        || toolActionPending;
    const scoped = stateIdentity === identity && !switching;

    useEffect(() => {
        activeSessionRef.current = activeSession;
    }, [activeSession]);

    useEffect(() => {
        typingRef.current = composer.trim().length > 0;
    }, [composer]);

    useEffect(() => {
        activeTurnRef.current = (turn.phase === 'accepted' || turn.phase === 'running')
            && turn.sessionId !== null
            && turn.turnId !== null
            ? { sessionId: turn.sessionId, turnId: turn.turnId }
            : null;
        if (turn.phase !== 'accepted' && turn.phase !== 'running') {
            cancellingRef.current = false;
            setCancelling(false);
        }
    }, [turn]);

    const publishStream = useCallback(() => {
        const stream = streamRef.current;
        if (stream === null || stream.text.length === 0) {
            streamStore.publish(null);
            return;
        }
        streamStore.publish({ turnId: stream.turnId, text: stream.text });
        if (!streamingRef.current) {
            streamingRef.current = true;
            setStreaming(true);
        }
    }, [streamStore]);

    const invalidateStream = useCallback(() => {
        if (typeof window === 'undefined') return;
        streamCoalescerRef.current ??= createAskConnexFrameCoalescer(
            publishStream,
            (callback) => window.requestAnimationFrame(callback),
            (handle) => window.cancelAnimationFrame(handle),
        );
        streamCoalescerRef.current.invalidate();
    }, [publishStream]);

    useEffect(() => () => streamCoalescerRef.current?.dispose(), []);

    const resetStream = useCallback(() => {
        streamRef.current = null;
        streamingRef.current = false;
        setStreaming(false);
        streamStore.publish(null);
    }, [streamStore]);

    const hydrateStream = useCallback(async (sessionId: number, turnId: number): Promise<void> => {
        const signal = identityControllerRef.current?.signal;
        if (!signal || signal.aborted) return;
        for (let attempt = 0; ; attempt++) {
            if (attempt > 0) {
                try {
                    await delay(500, signal);
                } catch {
                    return;
                }
            }
            let partial: string;
            try {
                const durable = await getAiChatTurn(sessionId, turnId, { signal });
                partial = durable.partialContent ?? '';
            } catch {
                const current = streamRef.current;
                if (!signal.aborted && current?.turnId === turnId) {
                    streamRef.current = failAskConnexStreamHydration(current);
                }
                return;
            }
            if (signal.aborted) return;
            const current = streamRef.current;
            if (current?.turnId !== turnId) return;
            const settled = settleAskConnexStreamHydration(current, partial);
            streamRef.current = settled.state;
            invalidateStream();
            if (!settled.hydrate) return;
        }
    }, [invalidateStream]);

    const handleStreamDelta = useCallback((frame: AiChatDeltaFrame) => {
        const active = activeTurnRef.current;
        if (active === null || frame.turnId !== active.turnId) return;
        const current = streamRef.current?.turnId === frame.turnId
            ? streamRef.current
            : createAskConnexStream(frame.turnId);
        const transition = applyAskConnexStreamDelta(current, frame);
        streamRef.current = transition.state;
        invalidateStream();
        if (transition.hydrate) void hydrateStream(active.sessionId, frame.turnId);
    }, [hydrateStream, invalidateStream]);

    const absorbTurnPartial = useCallback((durable: AiChatTurn) => {
        if (!isActiveTurnStatus(durable.status)) return;
        const partial = durable.partialContent ?? '';
        if (partial.length === 0) return;
        const current = streamRef.current?.turnId === durable.turnId
            ? streamRef.current
            : createAskConnexStream(durable.turnId);
        const settled = settleAskConnexStreamHydration(current, partial);
        streamRef.current = settled.state;
        invalidateStream();
        if (settled.hydrate) void hydrateStream(durable.sessionId, durable.turnId);
    }, [hydrateStream, invalidateStream]);

    const openDrawer = useCallback((source: OpenSource = 'standard') => {
        if (open) return;
        setInstantOpen(source === 'keyboard');
        setOpen(true);
    }, [open]);
    const closeDrawer = useCallback(() => setOpen(false), []);
    const closeDrawerInstant = useCallback(() => {
        setInstantOpen(true);
        setOpen(false);
    }, []);

    useEffect(() => {
        const onKeyDown = (event: KeyboardEvent) => {
            if (event.repeat || !event.shiftKey || event.altKey) return;
            if (!(event.metaKey || event.ctrlKey) || event.key.toLowerCase() !== 'a') return;
            event.preventDefault();
            openDrawer('keyboard');
        };
        document.addEventListener('keydown', onKeyDown);
        return () => document.removeEventListener('keydown', onKeyDown);
    }, [openDrawer]);

    const refreshSessions = useCallback(async (signal: AbortSignal): Promise<AiChatSession[]> => {
        const refreshVersion = ++sessionsRefreshVersionRef.current;
        const [page, invitationPage] = await Promise.all([
            getAiChatSessions({ page: 1, size: 25 }, { signal }),
            getAiChatInvitations({ page: 1, size: 25 }, { signal }),
        ]);
        if (signal.aborted || refreshVersion !== sessionsRefreshVersionRef.current) return [];
        setSessions(page.items.filter((session) => !session.archived));
        setInvitations(invitationPage.items.filter((session) => !session.archived));
        return page.items;
    }, []);

    const refreshCollaboration = useCallback(async (
        sessionId: number,
        signal: AbortSignal,
    ): Promise<void> => {
        const [nextParticipants, nextPresence] = await Promise.all([
            getAiChatParticipants(sessionId, { signal }),
            getAiChatPresence(sessionId, { signal }),
        ]);
        if (signal.aborted || activeSessionRef.current?.id !== sessionId) return;
        setParticipants(nextParticipants);
        setPresence(nextPresence);
    }, []);

    const refreshTranscript = useCallback(async (
        sessionId: number,
        signal: AbortSignal,
        animateNew: boolean,
    ): Promise<AiChatSession | null> => {
        const refreshVersion = ++transcriptRefreshVersionRef.current;
        const [firstDetail, attachments, historicalToolCalls, pendingToolCalls] = await Promise.all([
            getAiChatSession(
                sessionId,
                { page: 1, size: ASK_CONNEX_MESSAGE_PAGE_SIZE },
                { signal },
            ),
            getAiChatAttachments(sessionId, { signal }),
            getAiAssistantToolCalls(sessionId, {}, { signal }),
            getAiAssistantToolCalls(sessionId, { pendingOnly: true }, { signal }),
        ]);
        if (signal.aborted || refreshVersion !== transcriptRefreshVersionRef.current) return null;
        const nextMessages = await loadAskConnexLatestMessages(
            firstDetail.messages,
            ASK_CONNEX_MESSAGE_PAGE_SIZE,
            async (page) => {
                const detail = await getAiChatSession(
                    sessionId,
                    { page, size: ASK_CONNEX_MESSAGE_PAGE_SIZE },
                    { signal },
                );
                return detail.messages;
            },
        );
        if (signal.aborted || refreshVersion !== transcriptRefreshVersionRef.current) return null;
        const known = new Set(messagesRef.current.map(
            (message) => `${message.seq}:${message.authorKind}:${message.content}`,
        ));
        messagesRef.current = nextMessages;
        setMessages(nextMessages);
        setFreshMessageIds(animateNew
            ? new Set(nextMessages
                .filter((message) => !known.has(`${message.seq}:${message.authorKind}:${message.content}`))
                .map((message) => message.id))
            : new Set());
        dispatchToolCalls({
            type: 'replace',
            toolCalls: mergeAskConnexToolCalls(historicalToolCalls, pendingToolCalls),
        });
        setFileAttachments(attachments.map(readyFileAttachment));
        setActiveSession(firstDetail.session);
        activeSessionRef.current = firstDetail.session;
        if (firstDetail.session.visibility === 'shared') {
            await refreshCollaboration(sessionId, signal);
        } else {
            setParticipants([]);
            setPresence(null);
        }
        if (signal.aborted || refreshVersion !== transcriptRefreshVersionRef.current) return null;
        setLoadState('ready');
        setLoadError(null);
        return firstDetail.session;
    }, [refreshCollaboration]);

    const clearActiveSession = useCallback(() => {
        sessionEpochRef.current++;
        sessionControllerRef.current?.abort();
        transcriptRefreshVersionRef.current += 1;
        safeStorageRemove(sessionKey);
        safeStorageRemove(turnKey);
        setActiveSession(null);
        activeSessionRef.current = null;
        setParticipants([]);
        setPresence(null);
        messagesRef.current = [];
        setMessages([]);
        setFreshMessageIds(new Set());
        setComposer('');
        setFileAttachments([]);
        setSubmissionBlocked(false);
        setUnavailableReason(null);
        submittingRef.current = false;
        setSubmitting(false);
        resetStream();
        dispatchTurn({ type: 'reset' });
        dispatchToolCalls({ type: 'reset' });
        setLoadState('ready');
        setLoadError(null);
    }, [resetStream, sessionKey, turnKey]);

    const pollDurableTurn = useCallback(async (
        sessionId: number,
        turnId: number,
        signal: AbortSignal,
        initial?: AiChatTurn,
    ): Promise<AiChatTurn> => {
        let current = initial ?? await getAiChatTurn(sessionId, turnId, { signal });
        while (isActiveTurnStatus(current.status)) {
            dispatchTurn({ type: 'status', status: current.status, reason: current.terminalReason });
            absorbTurnPartial(current);
            await delay(1_000, signal);
            current = await getAiChatTurn(sessionId, turnId, { signal });
        }
        return current;
    }, [absorbTurnPartial]);

    const followTurn = useCallback(async (
        stored: StoredAskConnexTurn,
        signal: AbortSignal,
    ): Promise<void> => {
        try {
            let durable = await getAiChatTurn(stored.sessionId, stored.turnId, { signal });
            if (signal.aborted) return;
            dispatchTurn({ type: 'status', status: durable.status, reason: durable.terminalReason });
            absorbTurnPartial(durable);

            try {
                const initial = await getAiGenerationStatus<AiChatTurnGenerationResult>(
                    stored.generationHandle,
                    { signal },
                );
                if (initial.status === 'running') {
                    dispatchTurn({ type: 'status', status: 'running' });
                }
                const result = await resolveAcceptedAiGeneration(initial, { signal });
                if (result.turnId !== stored.turnId) {
                    throw new Error('Assistant generation resolved for a different turn');
                }
            } catch (error) {
                if (signal.aborted) return;
                if (error instanceof ApiError && error.status === 403) throw error;
                if (!(error instanceof AiGenerationError)) {
                    durable = await getAiChatTurn(stored.sessionId, stored.turnId, { signal });
                }
            }

            if (isActiveTurnStatus(durable.status)) {
                durable = await pollDurableTurn(stored.sessionId, stored.turnId, signal, durable);
            }

            if (signal.aborted) return;
            dispatchTurn({ type: 'status', status: durable.status, reason: durable.terminalReason });
            safeStorageRemove(turnKey);
            setSubmissionBlocked(false);
            await refreshTranscript(stored.sessionId, signal, true);
            resetStream();
            await refreshSessions(signal);
            if (durable.status === 'failed') {
                deferredErrorToast(
                    durable.terminalReason === 'image_input_unsupported'
                        ? t('turnImageUnsupported')
                        : durable.terminalReason === 'tool_result_budget_exhausted'
                            ? t('toolResultBudgetExhausted')
                            : durable.terminalReason === 'budget_exhausted'
                                ? t('budgetExhausted')
                                : t('toast.turnFailed'),
                );
            }
            if (durable.status === 'timed_out') deferredErrorToast(t('toast.turnTimedOut'));
        } catch (error) {
            if (signal.aborted) return;
            if (error instanceof ApiError && error.status === 403) {
                clearActiveSession();
                return;
            }
            setSubmissionBlocked(true);
            dispatchTurn({ type: 'status', status: 'failed', reason: 'reconciliation_failed' });
            deferredErrorToast(error instanceof ApiError ? error.message : t('toast.requestFailed'));
        }
    }, [absorbTurnPartial, clearActiveSession, pollDurableTurn, refreshSessions, refreshTranscript, resetStream, t, turnKey]);

    useEffect(() => {
        sessionEpochRef.current++;
        const controller = new AbortController();
        identityControllerRef.current?.abort();
        identityControllerRef.current = controller;
        sessionControllerRef.current?.abort();
        sessionControllerRef.current = null;
        const storedSessionId = parseStoredAskConnexSession(safeStorageGet(sessionKey));
        const storedTurn = parseStoredAskConnexTurn(safeStorageGet(turnKey));

        const initialize = async () => {
            let selectionLoadStarted = false;
            await Promise.resolve();
            if (controller.signal.aborted) return;
            setStateIdentity(identity);
            setSessions([]);
            setInvitations([]);
            setActiveSession(null);
            activeSessionRef.current = null;
            setParticipants([]);
            setPresence(null);
            setMembers([]);
            messagesRef.current = [];
            setMessages([]);
            setFreshMessageIds(new Set());
            setComposer('');
            setFileAttachments([]);
            setUnavailableReason(null);
            setSubmissionBlocked(false);
            submittingRef.current = false;
            setSubmitting(false);
            resetStream();
            dispatchTurn({ type: 'reset' });
            dispatchToolCalls({ type: 'reset' });
            setLoadState('loading');
            setLoadError(null);

            if (userId === null || activeWorkspaceId === null || switching) {
                setLoadState('ready');
                return;
            }

            try {
                await refreshSessions(controller.signal);
                if (sharePermission === 'granted') {
                    const workspaceMembers = await getActiveWorkspaceMembers({ signal: controller.signal });
                    if (!controller.signal.aborted) {
                        setMembers(workspaceMembers.filter((member) => member.status === 'active'));
                    }
                }
                if (storedSessionId === null) {
                    setLoadState('ready');
                    return;
                }
                selectionLoadStarted = true;
                await refreshTranscript(storedSessionId, controller.signal, false);
                if (storedTurn?.sessionId === storedSessionId) {
                    dispatchTurn({
                        type: 'accepted',
                        sessionId: storedTurn.sessionId,
                        turnId: storedTurn.turnId,
                        generationHandle: storedTurn.generationHandle,
                        status: 'accepted',
                    });
                    await followTurn(storedTurn, controller.signal);
                }
            } catch (error) {
                if (controller.signal.aborted) return;
                if (selectionLoadStarted && error instanceof ApiError
                        && (error.status === 403 || error.status === 404)) {
                    clearActiveSession();
                    return;
                }
                if (error instanceof ApiError && error.status === 403) {
                    setUnavailableReason(error.message);
                    setLoadState('forbidden');
                    deferredErrorToast(error.message);
                    return;
                }
                const nextError = error instanceof ApiError
                    ? error
                    : new Error(t('toast.requestFailed'));
                setLoadError(nextError);
                setLoadState('error');
                deferredErrorToast(nextError.message);
            }
        };

        void initialize();
        return () => controller.abort();
    }, [activeWorkspaceId, clearActiveSession, followTurn, identity, refreshSessions, refreshTranscript, reloadVersion, resetStream, sessionKey, sharePermission, switching, t, turnKey, userId]);

    const selectSession = useCallback(async (session: AiChatSession) => {
        if (working) return;
        sessionEpochRef.current++;
        sessionControllerRef.current?.abort();
        const controller = new AbortController();
        sessionControllerRef.current = controller;
        const identitySignal = identityControllerRef.current?.signal;
        const signal = identitySignal ? AbortSignal.any([controller.signal, identitySignal]) : controller.signal;
        safeStorageSet(sessionKey, String(session.id));
        safeStorageRemove(turnKey);
        setActiveSession(session);
        activeSessionRef.current = session;
        setParticipants([]);
        setPresence(null);
        messagesRef.current = [];
        setMessages([]);
        setFreshMessageIds(new Set());
        setComposer('');
        setFileAttachments([]);
        setSubmissionBlocked(false);
        submittingRef.current = false;
        setSubmitting(false);
        resetStream();
        dispatchTurn({ type: 'reset' });
        dispatchToolCalls({ type: 'reset' });
        setLoadState('loading');
        try {
            await refreshTranscript(session.id, signal, false);
        } catch (error) {
            if (signal.aborted) return;
            if (error instanceof ApiError && (error.status === 403 || error.status === 404)) {
                clearActiveSession();
            } else {
                setLoadError(error instanceof ApiError ? error : new Error(t('toast.requestFailed')));
                setLoadState('error');
                deferredErrorToast(error instanceof ApiError ? error.message : t('toast.requestFailed'));
            }
        }
    }, [clearActiveSession, refreshTranscript, resetStream, sessionKey, t, turnKey, working]);

    const newChat = useCallback(() => {
        if (working) return;
        clearActiveSession();
    }, [clearActiveSession, working]);

    const enqueueRealtimeRefresh = useCallback((sessionId: number, signal: AbortSignal) => {
        realtimeRefreshQueueRef.current = realtimeRefreshQueueRef.current.then(async () => {
            if (signal.aborted) return;
            try {
                await refreshSessions(signal);
                const session = activeSessionRef.current;
                if (session?.id === sessionId) {
                    await refreshTranscript(session.id, signal, true);
                }
            } catch (error) {
                if (signal.aborted) return;
                if (error instanceof ApiError && (error.status === 403 || error.status === 404)) {
                    clearActiveSession();
                }
            }
        });
    }, [clearActiveSession, refreshSessions, refreshTranscript]);

    useEffect(() => {
        if (userId === null || activeWorkspaceId === null || switching) return;
        const socket = createAiChatSocket({
            onFrame: (frame) => {
                if (frame.workspaceId !== activeWorkspaceId) return;
                const signal = identityControllerRef.current?.signal;
                if (!signal || signal.aborted) return;
                if (frame.status === 'revoked') {
                    sessionsRefreshVersionRef.current += 1;
                    setSessions((current) => current.filter(
                        (session) => session.id !== frame.sessionId,
                    ));
                    setInvitations((current) => current.filter(
                        (session) => session.id !== frame.sessionId,
                    ));
                    if (activeSessionRef.current?.id === frame.sessionId) {
                        clearActiveSession();
                    }
                }
                enqueueRealtimeRefresh(frame.sessionId, signal);
            },
            onDelta: handleStreamDelta,
        });
        socket.activate();
        return () => socket.deactivate();
    }, [activeWorkspaceId, clearActiveSession, enqueueRealtimeRefresh, handleStreamDelta, switching, userId]);

    useEffect(() => {
        const session = activeSession;
        if (!open || session === null || session.visibility !== 'shared' || loadState !== 'ready') {
            return;
        }
        const sessionId = session.id;
        const controller = new AbortController();
        let timeout: number | null = null;
        const heartbeat = async () => {
            const startedAt = performance.now();
            try {
                const snapshot = await touchAiChatPresence(
                    sessionId,
                    typingRef.current,
                    { signal: controller.signal },
                );
                if (!controller.signal.aborted
                        && activeSessionRef.current?.id === sessionId) {
                    setPresence(snapshot);
                }
            } catch {} finally {
                if (!controller.signal.aborted) {
                    const elapsed = performance.now() - startedAt;
                    timeout = window.setTimeout(
                        () => void heartbeat(), Math.max(0, 4_000 - elapsed),
                    );
                }
            }
        };
        void heartbeat();
        return () => {
            if (timeout !== null) window.clearTimeout(timeout);
            controller.abort();
            void leaveAiChatPresence(sessionId).catch(() => {});
        };
    }, [activeSession, loadState, open]);

    const shareSession = useCallback(async (shared: boolean): Promise<boolean> => {
        if (!activeSession?.ownedByCurrentUser || sharePermission !== 'granted') return false;
        const sessionId = activeSession.id;
        const signal = identityControllerRef.current?.signal;
        if (!signal || signal.aborted) return false;
        try {
            const updated = await setAiChatSessionShared(sessionId, shared, { signal });
            if (signal.aborted || activeSessionRef.current?.id !== sessionId) return false;
            setActiveSession(updated);
            activeSessionRef.current = updated;
            setSessions((current) => current.map((session) => session.id === updated.id ? updated : session));
            if (shared) {
                await refreshCollaboration(updated.id, signal);
            } else {
                setParticipants([]);
                setPresence(null);
            }
            if (signal.aborted || activeSessionRef.current?.id !== sessionId) return false;
            toastSuccess(t(shared ? 'toast.shared' : 'toast.private'));
            return true;
        } catch (error) {
            if (signal.aborted || activeSessionRef.current?.id !== sessionId) return false;
            toastError(error instanceof ApiError ? error.message : t('toast.requestFailed'));
            return false;
        }
    }, [activeSession, refreshCollaboration, sharePermission, t]);

    const inviteParticipant = useCallback(async (targetUserId: number): Promise<boolean> => {
        if (!activeSession?.ownedByCurrentUser || activeSession.visibility !== 'shared') return false;
        const sessionId = activeSession.id;
        const signal = identityControllerRef.current?.signal;
        if (!signal || signal.aborted) return false;
        try {
            const invited = await inviteAiChatParticipant(sessionId, targetUserId, { signal });
            if (signal.aborted || activeSessionRef.current?.id !== sessionId) return false;
            setParticipants((current) => [
                ...current.filter((participant) => participant.userId !== invited.userId),
                invited,
            ]);
            toastSuccess(t('toast.invited'));
            return true;
        } catch (error) {
            if (signal.aborted || activeSessionRef.current?.id !== sessionId) return false;
            toastError(error instanceof ApiError ? error.message : t('toast.requestFailed'));
            return false;
        }
    }, [activeSession, t]);

    const joinInvitation = useCallback(async (invitation: AiChatSession) => {
        if (working) return;
        const selectedSessionId = activeSessionRef.current?.id ?? null;
        const signal = identityControllerRef.current?.signal;
        if (!signal || signal.aborted) return;
        try {
            const joined = await joinAiChatSession(invitation.id, { signal });
            if (signal.aborted
                    || (activeSessionRef.current?.id ?? null) !== selectedSessionId) return;
            setInvitations((current) => current.filter((session) => session.id !== joined.id));
            setSessions((current) => [joined, ...current.filter((session) => session.id !== joined.id)]);
            await selectSession(joined);
            if (signal.aborted || activeSessionRef.current?.id !== joined.id) return;
            toastSuccess(t('toast.joined'));
        } catch (error) {
            if (signal.aborted
                    || (activeSessionRef.current?.id ?? null) !== selectedSessionId) return;
            toastError(error instanceof ApiError ? error.message : t('toast.requestFailed'));
        }
    }, [selectSession, t, working]);

    const leaveSession = useCallback(async () => {
        if (!activeSession || activeSession.ownedByCurrentUser || working) return;
        const sessionId = activeSession.id;
        const signal = identityControllerRef.current?.signal;
        if (!signal || signal.aborted) return;
        try {
            await leaveAiChatSession(sessionId, { signal });
            if (signal.aborted || activeSessionRef.current?.id !== sessionId) return;
            setSessions((current) => current.filter((session) => session.id !== sessionId));
            newChat();
            toastSuccess(t('toast.left'));
        } catch (error) {
            if (signal.aborted || activeSessionRef.current?.id !== sessionId) return;
            toastError(error instanceof ApiError ? error.message : t('toast.requestFailed'));
        }
    }, [activeSession, newChat, t, working]);

    const removeParticipant = useCallback(async (targetUserId: number) => {
        if (!activeSession?.ownedByCurrentUser) return;
        const sessionId = activeSession.id;
        const signal = identityControllerRef.current?.signal;
        if (!signal || signal.aborted) return;
        try {
            await removeAiChatParticipant(sessionId, targetUserId, { signal });
            if (signal.aborted || activeSessionRef.current?.id !== sessionId) return;
            setParticipants((current) => current.filter((participant) => participant.userId !== targetUserId));
            toastSuccess(t('toast.participantRemoved'));
        } catch (error) {
            if (signal.aborted || activeSessionRef.current?.id !== sessionId) return;
            toastError(error instanceof ApiError ? error.message : t('toast.requestFailed'));
        }
    }, [activeSession, t]);

    const ensureSession = useCallback(async (signal: AbortSignal): Promise<AiChatSession> => {
        if (activeSession !== null) return activeSession;
        if (sessionCreationRef.current !== null) return sessionCreationRef.current;
        const creation = createAiChatSession(
            t('newChatTitle'),
            true,
            { signal },
        ).then((createdSession) => {
            if (signal.aborted) throw signal.reason;
            setActiveSession(createdSession);
            activeSessionRef.current = createdSession;
            setSessions((current) => [
                createdSession,
                ...current.filter((item) => item.id !== createdSession.id),
            ]);
            safeStorageSet(sessionKey, String(createdSession.id));
            setLoadState('ready');
            return createdSession;
        }).finally(() => {
            if (sessionCreationRef.current === creation) sessionCreationRef.current = null;
        });
        sessionCreationRef.current = creation;
        return creation;
    }, [activeSession, sessionKey, t]);

    const uploadErrorMessage = useCallback((error: unknown): string => {
        if (!(error instanceof ApiError)) return t('upload.failed');
        if (error.status === 413) return t('upload.tooLarge');
        if (error.status === 415) return t('upload.unsupported');
        if (error.status === 409) return t('upload.limit');
        return t('upload.failed');
    }, [t]);

    const attachFiles = useCallback(async (files: File[]) => {
        const signal = identityControllerRef.current?.signal;
        if (
            !signal
            || signal.aborted
            || working
            || permission !== 'granted'
            || attachmentCreatePermission !== 'granted'
        ) return;
        for (const file of files) {
            const operationEpoch = sessionEpochRef.current;
            const clientId = crypto.randomUUID();
            const pending: AskConnexFileAttachment = {
                clientId,
                id: null,
                fileName: file.name,
                contentType: file.type || 'application/octet-stream',
                size: file.size,
                kind: file.type.startsWith('image/') ? 'image' : 'text',
                status: 'uploading',
                progress: 0,
                error: null,
            };
            setFileAttachments((current) => [...current, pending]);
            try {
                const session = await ensureSession(signal);
                const uploaded = await uploadAiChatAttachment(
                    session.id,
                    file,
                    (progress) => setFileAttachments((current) => current.map((attachment) =>
                        attachment.clientId === clientId
                            ? { ...attachment, progress }
                            : attachment)),
                    { signal },
                );
                if (signal.aborted || operationEpoch !== sessionEpochRef.current) continue;
                setFileAttachments((current) => current.map((attachment) =>
                    attachment.clientId === clientId
                        ? { ...readyFileAttachment(uploaded), clientId }
                        : attachment));
            } catch (error) {
                if (signal.aborted || operationEpoch !== sessionEpochRef.current) continue;
                setFileAttachments((current) => current.map((attachment) =>
                    attachment.clientId === clientId
                        ? {
                            ...attachment,
                            status: 'failed',
                            progress: 0,
                            error: uploadErrorMessage(error),
                        }
                        : attachment));
            }
        }
    }, [attachmentCreatePermission, ensureSession, permission, uploadErrorMessage, working]);

    const removeFileAttachment = useCallback(async (attachment: AskConnexFileAttachment) => {
        if (fileOperationPending) return;
        if (attachment.status === 'uploading' || attachment.status === 'removing') return;
        if (attachment.status === 'failed' || attachment.id === null || activeSession === null) {
            setFileAttachments((current) => current.filter(
                (item) => item.clientId !== attachment.clientId,
            ));
            return;
        }
        const signal = identityControllerRef.current?.signal;
        if (!signal || signal.aborted) return;
        const operationEpoch = sessionEpochRef.current;
        const sessionId = activeSession.id;
        const attachmentId = attachment.id;
        const removal = removeReadyAskConnexFile(
                fileAttachments,
                attachment,
                operationEpoch,
                () => sessionEpochRef.current,
                signal,
                () => deleteAiChatAttachment(sessionId, attachmentId, { signal }),
            );
        setFileAttachments(removal.pending);
        try {
            const updated = await removal.settled;
            if (updated !== null) setFileAttachments(updated);
        } catch (error) {
            if (!(error instanceof AskConnexFileRemovalError)) return;
            setFileAttachments(error.attachments);
            toastError(error.cause instanceof ApiError
                ? error.cause.message
                : t('upload.removeFailed'));
        }
    }, [activeSession, fileAttachments, fileOperationPending, t]);

    const renameSession = useCallback(async (title: string): Promise<boolean> => {
        if (!activeSession?.ownedByCurrentUser) return false;
        const sessionId = activeSession.id;
        const signal = identityControllerRef.current?.signal;
        if (!signal || signal.aborted) return false;
        try {
            const updated = await updateAiChatSession(sessionId, { title }, { signal });
            if (signal.aborted || activeSessionRef.current?.id !== sessionId) return false;
            setActiveSession(updated);
            setSessions((current) => current.map((session) => session.id === updated.id ? updated : session));
            toastSuccess(t('toast.renamed'));
            return true;
        } catch (error) {
            if (signal.aborted || activeSessionRef.current?.id !== sessionId) return false;
            toastError(error instanceof ApiError ? error.message : t('toast.requestFailed'));
            return false;
        }
    }, [activeSession, t]);

    const archiveSession = useCallback(async () => {
        if (!activeSession?.ownedByCurrentUser || working) return;
        const sessionId = activeSession.id;
        const signal = identityControllerRef.current?.signal;
        if (!signal || signal.aborted) return;
        try {
            await archiveAiChatSession(sessionId, { signal });
            if (signal.aborted || activeSessionRef.current?.id !== sessionId) return;
            setSessions((current) => current.filter((session) => session.id !== sessionId));
            toastSuccess(t('toast.archived'));
            newChat();
        } catch (error) {
            if (signal.aborted || activeSessionRef.current?.id !== sessionId) return;
            toastError(error instanceof ApiError ? error.message : t('toast.requestFailed'));
        }
    }, [activeSession, newChat, t, working]);

    const performToolAction = useCallback(async (
        toolCallId: number,
        action: AskConnexToolAction,
    ) => {
        const session = activeSessionRef.current;
        const signal = identityControllerRef.current?.signal;
        const card = toolCalls.find((toolCall) => toolCall.id === toolCallId);
        if (!session
            || !signal
            || signal.aborted
            || !card
            || !actionableToolCallIds.has(toolCallId)
            || toolActionsRef.current.has(toolCallId)) {
            return;
        }
        const operationEpoch = sessionEpochRef.current;
        toolActionsRef.current.add(toolCallId);
        dispatchToolCalls({ type: 'actionStarted', toolCallId, action });
        try {
            const mutation = action === 'approve'
                ? await approveAiAssistantToolCall(session.id, toolCallId, { signal })
                : action === 'reject'
                    ? await rejectAiAssistantToolCall(session.id, toolCallId, { signal })
                    : await undoAiAssistantToolCall(session.id, toolCallId, { signal });
            if (signal.aborted
                || operationEpoch !== sessionEpochRef.current
                || activeSessionRef.current?.id !== session.id) return;
            dispatchToolCalls({ type: 'actionApplied', toolCallId, action, mutation });
            if ((action === 'approve' && mutation.status === 'executed')
                || (action === 'undo' && mutation.status === 'undone')) {
                router.refresh();
            }
            try {
                const refreshed = await getAiAssistantToolCall(session.id, toolCallId, { signal });
                if (signal.aborted
                    || operationEpoch !== sessionEpochRef.current
                    || activeSessionRef.current?.id !== session.id) return;
                dispatchToolCalls({ type: 'actionSettled', toolCall: refreshed });
            } catch {
                if (!signal.aborted
                    && operationEpoch === sessionEpochRef.current
                    && activeSessionRef.current?.id === session.id) {
                    toastError(t('toast.toolActionRefreshFailed'));
                }
            }
        } catch (error) {
            if (signal.aborted
                || operationEpoch !== sessionEpochRef.current
                || activeSessionRef.current?.id !== session.id) return;
            const failure = toolCardFailure(error, action);
            dispatchToolCalls({ type: 'actionFailed', toolCallId, action, failure });
            toastError(t(`toolCards.failures.${failure}`));
            if (action !== 'undo'
                && error instanceof ApiError
                && error.status === 409) {
                try {
                    const refreshed = await getAiAssistantToolCall(session.id, toolCallId, { signal });
                    if (!signal.aborted
                        && operationEpoch === sessionEpochRef.current
                        && activeSessionRef.current?.id === session.id
                        && refreshed.status !== 'proposed') {
                        dispatchToolCalls({ type: 'actionSettled', toolCall: refreshed });
                    }
                } catch {}
            }
        } finally {
            toolActionsRef.current.delete(toolCallId);
        }
    }, [actionableToolCallIds, router, t, toolCalls]);

    const send = useCallback(async (contentOverride?: string) => {
        const requestContent = contentOverride ?? composer;
        const content = askConnexMessageContent(requestContent);
        const requestContext = mergeAskConnexContext(context.record, requestContent);
        const requestContextOverflow = requestContext.pageContext.length + fileContextCount > 10;
        const activeSignal = identityControllerRef.current?.signal;
        if (
            !activeSignal
            || activeSignal.aborted
            || permission !== 'granted'
            || unavailableReason !== null
            || submissionBlocked
            || submittingRef.current
            || turn.phase === 'accepted'
            || turn.phase === 'running'
            || fileOperationPending
            || requestContextOverflow
            || content.length === 0
            || content.length > 16_000
        ) return;

        submittingRef.current = true;
        setSubmitting(true);
        let session = activeSession;
        try {
            if (session === null) {
                try {
                    const createdSession = await createAiChatSession(
                        t('newChatTitle'),
                        true,
                        { signal: activeSignal },
                    );
                    if (activeSignal.aborted) return;
                    session = createdSession;
                    setActiveSession(createdSession);
                    activeSessionRef.current = createdSession;
                    setSessions((current) => [
                        createdSession,
                        ...current.filter((item) => item.id !== createdSession.id),
                    ]);
                    safeStorageSet(sessionKey, String(createdSession.id));
                    setLoadState('ready');
                } catch (error) {
                    if (!activeSignal.aborted) {
                        toastError(error instanceof ApiError ? error.message : t('toast.requestFailed'));
                    }
                    return;
                }
            }

            const accepted = await startAiChatTurn(session.id, {
                content,
                pageContext: requestContext.pageContext,
            });
            if (activeSignal.aborted) return;
            const stored = {
                sessionId: accepted.sessionId,
                turnId: accepted.turnId,
                generationHandle: accepted.generationHandle,
            };
            safeStorageSet(turnKey, serializeStoredAskConnexTurn(stored));
            resetStream();
            dispatchTurn({
                type: 'accepted',
                sessionId: accepted.sessionId,
                turnId: accepted.turnId,
                generationHandle: accepted.generationHandle,
                status: accepted.status,
            });
            const optimistic: AiChatMessage = {
                id: tempMessageIdRef.current,
                sessionId: accepted.sessionId,
                seq: (messagesRef.current.at(-1)?.seq ?? 0) + 1,
                authorKind: 'user',
                authorUserId: userId,
                authorDisplayName: userDisplayName,
                content,
                createdAt: new Date().toISOString(),
            };
            tempMessageIdRef.current -= 1;
            messagesRef.current = [...messagesRef.current, optimistic];
            setMessages(messagesRef.current);
            setFreshMessageIds(new Set([optimistic.id]));
            setComposer('');
            setSubmissionBlocked(false);
            await followTurn(stored, activeSignal);
        } catch (error) {
            if (activeSignal.aborted) return;
            if (error instanceof ApiError && error.status === 403) {
                setUnavailableReason(error.message);
            } else {
                setSubmissionBlocked(true);
            }
            dispatchTurn({
                type: 'status',
                status: 'failed',
                reason: error instanceof ApiError ? error.message : 'request_failed',
            });
            toastError(error instanceof ApiError ? error.message : t('toast.requestFailed'));
            if (session !== null) {
                try {
                    await refreshTranscript(session.id, activeSignal, true);
                } catch {}
            }
        } finally {
            submittingRef.current = false;
            setSubmitting(false);
        }
    }, [activeSession, composer, context.record, fileContextCount, fileOperationPending, followTurn, permission, refreshTranscript, resetStream, sessionKey, submissionBlocked, t, turn.phase, turnKey, unavailableReason, userDisplayName, userId]);

    const cancelTurn = useCallback(async () => {
        const signal = identityControllerRef.current?.signal;
        if (!signal || signal.aborted || cancellingRef.current) return;
        cancellingRef.current = true;
        setCancelling(true);
        const outcome = await requestAskConnexTurnCancel(
            turn,
            false,
            (sessionId, turnId) => cancelAiChatTurn(sessionId, turnId, { signal }),
            (error) => (error instanceof ApiError ? error.status : null),
        );
        if (signal.aborted) return;
        if (outcome === 'requested' || outcome === 'already_settled') return;
        cancellingRef.current = false;
        setCancelling(false);
        if (outcome === 'forbidden' || outcome === 'failed') {
            toastError(t('toast.cancelFailed'));
        }
    }, [t, turn]);

    const removeAttachment = useCallback((attachment: AskConnexAttachment) => {
        setComposer((current) => removeAskConnexAttachment(current, attachment));
    }, []);

    const unavailable = useMemo(() => {
        if (permission === 'denied') {
            return { title: t('unavailable.permissionTitle'), body: t('unavailable.permissionBody') };
        }
        if (permission === 'unavailable') {
            return { title: t('unavailable.lookupTitle'), body: t('unavailable.lookupBody') };
        }
        if (unavailableReason !== null) {
            return {
                title: t('unavailable.featureTitle'),
                body: t('unavailable.featureBody', { reason: unavailableReason }),
            };
        }
        if (submissionBlocked) {
            return { title: t('unavailable.uncertainTitle'), body: t('unavailable.uncertainBody') };
        }
        return null;
    }, [permission, submissionBlocked, t, unavailableReason]);

    const starterPrompts = useMemo(
        () => starterPromptKeys(implicitContext?.kind ?? null).map((key) => t(key)),
        [implicitContext?.kind, t],
    );
    const labels = useMemo(() => ({
        assistantAuthor: t('assistantAuthor'),
        archive: t('archive'),
        budgetExhausted: t('budgetExhausted'),
        toolResultBudgetExhausted: t('toolResultBudgetExhausted'),
        citations: t('citations'),
        disclosureCreation: tDisclosure('sessionCreation'),
        disclosureList: tDisclosure('sessionList'),
        imageDisclosure: tDisclosure('imageProvider'),
        citationKind: (kind: AiChatCitation['kind']) =>
            kind === 'person'
                ? t('citationKindPerson')
                : kind === 'company'
                    ? t('citationKindCompany')
                    : t('citationKindDeal'),
        close: t('close'),
        composerAria: t('composerAria'),
        composerHint: t('composerHint'),
        composerPlaceholder: t('composerPlaceholder'),
        context: t('context'),
        contextLimit: t('contextLimit'),
        addContext: t('addContext'),
        addRecordContext: t('addRecordContext'),
        attachFile: t('attachFile'),
        removeFile: (label: string) => t('removeFile', { label }),
        uploadProgress: (progress: number) => t('upload.progress', { progress }),
        uploadRemoving: t('upload.removing'),
        turnImageUnsupported: t('turnImageUnsupported'),
        emptyBody: t('emptyBody'),
        emptyTitle: t('emptyTitle'),
        formerMember: t('formerMember'),
        historySummarized: t('historySummarized'),
        invitation: t('invitation'),
        invitations: t('invitations'),
        invite: t('invite'),
        inviteMember: t('inviteMember'),
        invitePending: t('invitePending'),
        join: t('join'),
        leave: t('leave'),
        manageSharing: t('manageSharing'),
        memberAuthor: (id: number) => t('memberAuthor', { id }),
        jumpToLatest: t('jumpToLatest'),
        loadError: t('loadError'),
        messages: t('messages'),
        newChat: t('newChat'),
        noRecentSessions: t('noRecentSessions'),
        moreOptions: t('moreOptions'),
        participants: t('participants'),
        presence: t('presence'),
        recentSessions: t('recentSessions'),
        removeContext: (label: string) => t('removeContext', { label }),
        removeParticipant: (name: string) => t('removeParticipant', { name }),
        rename: t('rename'),
        renameCancel: t('renameCancel'),
        renameDescription: t('renameDescription'),
        renameLabel: t('renameLabel'),
        renameSave: t('renameSave'),
        renameSaving: t('renameSaving'),
        renameTitle: t('renameTitle'),
        retry: t('retry'),
        send: t('send'),
        shareCancel: t('shareCancel'),
        shareConfirm: t('shareConfirm'),
        shareDescription: t('shareDescription'),
        shared: t('shared'),
        shareTitle: t('shareTitle'),
        suggestedFollowUps: t('suggestedFollowUps'),
        thinking: t('thinking'),
        title: t('title'),
        tooLong: t('tooLong'),
        typing: (names: string) => t('typing', { names }),
        unshare: t('unshare'),
        stop: t('stop'),
        stopping: t('stopping'),
        turnAccepted: t('turnAccepted'),
        turnCancelled: t('turnCancelled'),
        turnFailed: t('turnFailed'),
        turnResolved: t('turnResolved'),
        turnStreaming: t('turnStreaming'),
        turnTimedOut: t('turnTimedOut'),
        turnWorking: t('turnWorking'),
        toolCard: {
            actionFailed: t('toolCards.failures.actionFailed'),
            approve: t('toolCards.actions.approve'),
            approveAria: (target: string) => t('toolCards.actions.approveAria', { target }),
            approving: t('toolCards.actions.approving'),
            beforeApproval: t('toolCards.change.beforeApproval'),
            executedDetail: t('toolCards.states.executedDetail'),
            executedStatus: t('toolCards.states.executed'),
            expiredDetail: t('toolCards.states.expiredDetail'),
            expiredStatus: t('toolCards.states.expired'),
            failedDetail: t('toolCards.states.failedDetail'),
            failedStatus: t('toolCards.states.failed'),
            ifApproved: t('toolCards.change.ifApproved'),
            noChangeYet: t('toolCards.change.noChangeYet'),
            outcome: t('toolCards.outcome'),
            pendingDetail: t('toolCards.states.pendingDetail'),
            pendingStatus: t('toolCards.states.pending'),
            proposalChanged: t('toolCards.failures.proposalChanged'),
            proposalPermissionLost: t('toolCards.failures.proposalPermissionLost'),
            proposalUnavailable: t('toolCards.failures.proposalUnavailable'),
            recordLink: (target: string) => t('toolCards.recordLink', { target }),
            reject: t('toolCards.actions.reject'),
            rejectAria: (target: string) => t('toolCards.actions.rejectAria', { target }),
            rejectedDetail: t('toolCards.states.rejectedDetail'),
            rejectedStatus: t('toolCards.states.rejected'),
            rejecting: t('toolCards.actions.rejecting'),
            restrictedTarget: t('toolCards.restrictedTarget'),
            undo: t('toolCards.actions.undo'),
            undoAria: (target: string) => t('toolCards.actions.undoAria', { target }),
            undoConflict: t('toolCards.failures.undoConflict'),
            undoneDetail: t('toolCards.states.undoneDetail'),
            undoneStatus: t('toolCards.states.undone'),
            undoing: t('toolCards.actions.undoing'),
            summaries: {
                createActivity: t('toolCards.summaries.createActivity'),
                createTask: t('toolCards.summaries.createTask'),
                createNote: t('toolCards.summaries.createNote'),
                addTag: t('toolCards.summaries.addTag'),
                changeDealStage: t('toolCards.summaries.changeDealStage'),
                changeDealStageTo: (value: string) => t('toolCards.summaries.changeDealStageTo', { value }),
                assignOwner: t('toolCards.summaries.assignOwner'),
                assignOwnerTo: (value: string) => t('toolCards.summaries.assignOwnerTo', { value }),
                removeOwner: t('toolCards.summaries.removeOwner'),
                runWriteTool: t('toolCards.summaries.runWriteTool'),
                requestRejected: t('toolCards.summaries.requestRejected'),
                requestFailed: t('toolCards.summaries.requestFailed'),
                createdRecordRemoved: t('toolCards.summaries.createdRecordRemoved'),
                activityCreated: t('toolCards.summaries.activityCreated'),
                taskCreated: t('toolCards.summaries.taskCreated'),
                noteCreated: t('toolCards.summaries.noteCreated'),
                tagAdded: t('toolCards.summaries.tagAdded'),
                tagAlreadyPresent: t('toolCards.summaries.tagAlreadyPresent'),
                dealStageChanged: t('toolCards.summaries.dealStageChanged'),
                ownerRemoved: t('toolCards.summaries.ownerRemoved'),
                ownerAssigned: t('toolCards.summaries.ownerAssigned'),
                requestCompleted: t('toolCards.summaries.requestCompleted'),
            },
        },
    }), [t, tDisclosure]);

    const value = useMemo<AskConnexContextValue>(
        () => ({ open, instantOpen, working, openDrawer, closeDrawer }),
        [closeDrawer, instantOpen, open, openDrawer, working],
    );

    return (
        <AskConnexContext.Provider value={value}>
            {children}
            <AskConnexDrawer
                open={open}
                instantOpen={instantOpen}
                isMobile={isMobile}
                showTab={scoped}
                sessions={scoped ? sessions : []}
                invitations={scoped ? invitations : []}
                activeSession={scoped ? activeSession : null}
                participants={scoped ? participants : []}
                presence={scoped ? presence : null}
                members={scoped ? members : []}
                canShare={sharePermission === 'granted'}
                messages={scoped ? messages : []}
                freshMessageIds={scoped ? freshMessageIds : new Set()}
                loadState={scoped ? loadState : 'loading'}
                loadError={scoped ? loadError : null}
                composer={scoped ? composer : ''}
                implicitContext={implicitContext}
                attachments={scoped ? visibleAttachments : []}
                fileAttachments={scoped ? fileAttachments : []}
                canAttachFiles={attachmentCreatePermission === 'granted'}
                canRemoveFiles={attachmentDeletePermission === 'granted'}
                contextOverflow={scoped && contextOverflow}
                contentTooLong={scoped && contentTooLong}
                working={scoped && working}
                turn={scoped ? turn : EMPTY_ASK_CONNEX_TURN}
                streamStore={streamStore}
                streaming={scoped && streaming}
                cancelling={scoped && cancelling}
                toolCalls={scoped ? toolCalls : EMPTY_ASK_CONNEX_TOOL_CARDS}
                actionableToolCallIds={scoped
                    ? actionableToolCallIds
                    : EMPTY_ASK_CONNEX_TOOL_CALL_IDS}
                unavailable={unavailable}
                starterPrompts={starterPrompts}
                labels={labels}
                onOpenChange={setOpen}
                onOpenChangeComplete={() => setInstantOpen(false)}
                onKeyboardClose={closeDrawerInstant}
                onSelectSession={(session) => void selectSession(session)}
                onNewChat={newChat}
                onRename={renameSession}
                onArchive={() => void archiveSession()}
                onJoinInvitation={(session) => void joinInvitation(session)}
                onShare={shareSession}
                onInvite={inviteParticipant}
                onLeave={() => void leaveSession()}
                onRemoveParticipant={(participantUserId) => void removeParticipant(participantUserId)}
                onRetry={() => setReloadVersion((version) => version + 1)}
                onComposerChange={setComposer}
                onRemoveAttachment={removeAttachment}
                onAttachFiles={(files) => void attachFiles(files)}
                onRemoveFileAttachment={(attachment) => void removeFileAttachment(attachment)}
                onSend={(content) => void send(content)}
                onCancelTurn={() => void cancelTurn()}
                onToolAction={(toolCallId, action) => void performToolAction(toolCallId, action)}
            />
        </AskConnexContext.Provider>
    );
}
