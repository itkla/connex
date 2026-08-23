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
import { useLocale, useTranslations } from 'next-intl';
import { useParams, usePathname, useRouter } from 'next/navigation';
import { ArrowsPointingOutIcon, SparklesIcon } from '@heroicons/react/24/outline';

import AskConnexDrawer from '@/app/components/ask-connex/AskConnexDrawer';
import { formatAnswerInstant } from '@/app/components/ask-connex/answerDocument';
import { useActions, useRegisterActions } from '@/app/hooks/useActions';
import { useApiErrorToast } from '@/app/hooks/useApiErrorToast';
import { useIsMobile } from '@/app/hooks/useIsMobile';
import { useLiveNow } from '@/app/hooks/useNow';
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
    activeSelectionAskConnexContext,
    askConnexContextCorrected,
    askConnexMessageContent,
    askConnexPinnedStorageKey,
    askConnexRetryPrompt,
    askConnexScopePreview,
    askConnexSessionStorageKey,
    askConnexTurnStorageKey,
    completeAskConnexFileUpload,
    hasPendingAskConnexFileOperation,
    isAskConnexPinned,
    loadAskConnexLatestMessages,
    mergeAskConnexToolCalls,
    mergeAskConnexContext,
    parseStoredAskConnexPins,
    parseStoredAskConnexSession,
    parseStoredAskConnexTurn,
    reconcileAskConnexFileAttachments,
    reduceAskConnexToolCards,
    reduceAskConnexTurn,
    removeReadyAskConnexFile,
    removeAskConnexAttachment,
    serializeAskConnexPins,
    serializeStoredAskConnexTurn,
    snapshotAskConnexSourceContext,
    toggleAskConnexPin,
    type AskConnexAttachment,
    type AskConnexContextCorrections,
    type AskConnexFileAttachment,
    type AskConnexScopePreview,
    type AskConnexSelectionContext,
    type AskConnexSourceContext,
    type AskConnexToolAction,
    type AskConnexToolCardFailure,
    type StoredAskConnexTurn,
} from '@/app/lib/askConnex';
import {
    ASK_CONNEX_DEFAULT_WIDTH,
    askConnexActiveState,
    askConnexWidthStorageKey,
    parseStoredAskConnexWidth,
    type AskConnexActiveState,
    type AskConnexSessionGroupKey,
    type AskConnexWidth,
} from '@/app/lib/askConnexSurface';
import {
    absorbAskConnexStreamPartial,
    applyAskConnexStreamDelta,
    createAskConnexFrameCoalescer,
    createAskConnexStream,
    createAskConnexStreamStore,
    failAskConnexStreamHydration,
    reconcileAskConnexSettledStream,
    requestAskConnexTurnCancel,
    settleAskConnexStreamHydration,
    shouldDropAskConnexStream,
    shouldResetAskConnexStream,
    type AskConnexFrameCoalescer,
    type AskConnexStreamState,
} from '@/app/lib/askConnexStream';
import type { AppAction } from '@/app/lib/actions/types';
import { AiGenerationError } from '@/app/lib/aiGeneration';
import { createAiChatSocket } from '@/app/lib/realtime';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { formatRelativeTime } from '@/app/lib/utils';
import type {
    AiChatCitation,
    AiChatAnswerBlockKind,
    AiChatAttachment,
    AiChatCoverage,
    AiChatDeltaFrame,
    AiChatMessage,
    AiChatParticipant,
    AiChatPresence,
    AiChatProgressItem,
    AiChatSession,
    AiChatTurn,
    AiChatTurnGenerationResult,
    WorkspaceMember,
} from '@/app/lib/types';

type OpenSource = 'standard' | 'keyboard';

const ASK_CONNEX_MESSAGE_PAGE_SIZE = 50;
const EMPTY_ASK_CONNEX_TOOL_CALL_IDS: ReadonlySet<number> = new Set();
const EMPTY_ASK_CONNEX_PINS: readonly AskConnexAttachment[] = [];
const noopCleanup = () => {};

type AskConnexContextValue = {
    open: boolean;
    instantOpen: boolean;
    /**
     * Whether the last change to {@link width} was a member resizing the panel, which the shell
     * column adopts without animating so it never disagrees with the panel about how wide the panel
     * is. Cleared whenever the panel opens or closes, which is animated.
     */
    instantWidth: boolean;
    working: boolean;
    workspace: boolean;
    /**
     * How wide the desktop panel currently runs. The app shell reads it so the column it opens
     * beside the page and the panel that fills that column are always the same width.
     */
    width: AskConnexWidth;
    openDrawer: (source?: OpenSource) => void;
    closeDrawer: () => void;
    openWorkspace: () => void;
};

/**
 * How much of the question a continuation message carries forward.
 *
 * Long enough that the continuation reads as the same request, short enough that it stays a message
 * the member can read at a glance before sending it.
 */
const ASK_CONNEX_CONTINUE_QUESTION_LIMIT = 240;

/**
 * Registration for the two host nodes the single Ask Connex controller portals into: the app
 * shell's desktop panel column and the routed workspace's full-bleed canvas.
 *
 * Both are React-owned elements that belong to a different subtree than the controller, so the
 * controller cannot look them up — `/ask-connex` and `/ask-connex/[sessionId]` are distinct route
 * segments, and moving between them mounts a *new* workspace host while discarding the old one.
 * Each callback is a cleanup-returning ref: React hands it the live element and the cleanup only
 * clears the slot when the element it captured is still the registered one, so a mount that lands
 * before its predecessor's unmount can never leave the controller portalling into a detached node.
 */
type AskConnexMountValue = {
    registerDesktopRoot: (node: HTMLElement | null) => () => void;
    registerWorkspaceRoot: (node: HTMLElement | null) => () => void;
};

const AskConnexContext = createContext<AskConnexContextValue | null>(null);
const AskConnexMountContext = createContext<AskConnexMountValue | null>(null);

function isActiveTurnStatus(status: string): boolean {
    return status === 'accepted' || status === 'queued' || status === 'running';
}

function routeSessionId(value: string | string[] | undefined): number | null {
    const candidate = Array.isArray(value) ? value[0] : value;
    if (candidate === undefined || !/^[1-9]\d*$/.test(candidate)) return null;
    const id = Number(candidate);
    return Number.isSafeInteger(id) ? id : null;
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

/** Reads the host-node registration a shell region or workspace route attaches its container to. */
export function useAskConnexMount(): AskConnexMountValue {
    const value = useContext(AskConnexMountContext);
    if (value === null) throw new Error('useAskConnexMount must be used within AskConnexProvider');
    return value;
}

/** Owns Ask Connex continuity, transcript, accepted-turn reconciliation, and responsive surfaces. */
export default function AskConnexProvider({ children }: { children: ReactNode }) {
    const t = useTranslations('AskConnex');
    const showApiError = useApiErrorToast('AskConnex');
    const deferApiError = useCallback(
        (error: unknown, fallbackKey?: string) => {
            window.setTimeout(() => showApiError(error, fallbackKey), 0);
        },
        [showApiError],
    );
    const tDisclosure = useTranslations('Assistant.disclosure');
    const locale = useLocale();
    const now = useLiveNow();
    const router = useRouter();
    const pathname = usePathname() ?? '';
    const params = useParams<{ sessionId?: string | string[] }>();
    const workspaceMode = pathname === '/ask-connex' || pathname.startsWith('/ask-connex/');
    const workspaceSessionId = workspaceMode ? routeSessionId(params.sessionId) : null;
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
    const pinnedKey = askConnexPinnedStorageKey(userId, activeWorkspaceId);
    const widthKey = askConnexWidthStorageKey(userId, activeWorkspaceId);
    const [workspaceSourceContext, setWorkspaceSourceContext] = useState<{
        identity: string;
        context: AskConnexSourceContext;
    } | null>(null);
    const currentWorkspaceSource = workspaceSourceContext?.identity === identity
        ? workspaceSourceContext.context
        : null;
    const sourceRecord = workspaceMode
        ? currentWorkspaceSource?.record ?? null
        : context.record;
    const sourceSelection = workspaceMode
        ? currentWorkspaceSource?.selection ?? null
        : context.selection;
    const [pinnedContext, setPinnedContext] = useState<AskConnexAttachment[]>([]);
    const [pinnedIdentity, setPinnedIdentity] = useState<string | null>(null);
    const [pageDismissed, setPageDismissed] = useState(false);
    const [selectionDismissed, setSelectionDismissed] = useState(false);
    const inferredPageContext = useMemo(
        () => activeRecordAskConnexContext(sourceRecord),
        [sourceRecord],
    );
    const implicitContext = pageDismissed ? null : inferredPageContext;
    const inferredSelectionContext = useMemo(
        () => activeSelectionAskConnexContext(sourceSelection),
        [sourceSelection],
    );
    const selectionContext = selectionDismissed ? null : inferredSelectionContext;
    const unsupportedPageContext = useMemo(
        () => !pageDismissed && sourceRecord !== null && inferredPageContext === null
            ? { type: sourceRecord.type, label: sourceRecord.label }
            : null,
        [inferredPageContext, pageDismissed, sourceRecord],
    );
    const pins = pinnedIdentity === identity ? pinnedContext : EMPTY_ASK_CONNEX_PINS;
    const pageContextPinned = isAskConnexPinned(pins, inferredPageContext);
    const pageContextKey = sourceRecord === null
        ? null
        : `${sourceRecord.type}:${String(sourceRecord.id)}`;
    const selectionContextKey = sourceSelection === null
        ? null
        : `${sourceSelection.type}:${[...sourceSelection.ids].map(String).sort().join(',')}`;
    const [offeredPageContextKey, setOfferedPageContextKey] = useState(pageContextKey);
    const [offeredSelectionContextKey, setOfferedSelectionContextKey] = useState(selectionContextKey);
    if (pageContextKey !== offeredPageContextKey) {
        setOfferedPageContextKey(pageContextKey);
        setPageDismissed(false);
    }
    if (selectionContextKey !== offeredSelectionContextKey) {
        setOfferedSelectionContextKey(selectionContextKey);
        setSelectionDismissed(false);
    }
    const corrections = useMemo<AskConnexContextCorrections>(
        () => ({ pageDismissed, selectionDismissed, pinned: pins }),
        [pageDismissed, pins, selectionDismissed],
    );
    const contextCorrected = askConnexContextCorrected(corrections);

    const [open, setOpen] = useState(false);
    const [instantOpen, setInstantOpen] = useState(false);
    const [instantWidth, setInstantWidth] = useState(false);
    const [width, setWidth] = useState<AskConnexWidth>(ASK_CONNEX_DEFAULT_WIDTH);
    const [desktopRoot, setDesktopRoot] = useState<HTMLElement | null>(null);
    const [workspaceRoot, setWorkspaceRoot] = useState<HTMLElement | null>(null);
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
    const [featureUnavailable, setFeatureUnavailable] = useState(false);
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
    const streamEpochRef = useRef(0);
    const streamCoalescerRef = useRef<AskConnexFrameCoalescer | null>(null);
    const streamingRef = useRef(false);
    const cancellingRef = useRef(false);
    const activeTurnRef = useRef<{ sessionId: number; turnId: number } | null>(null);
    const durableFollowerRef = useRef<number | null>(null);
    const workspaceReturnRef = useRef(false);
    const workspaceSessionIdRef = useRef(workspaceSessionId);

    const registerDesktopRoot = useCallback((node: HTMLElement | null) => {
        // A ref callback that returns a cleanup is never re-invoked with null; the parameter stays
        // nullable only because React's ref type covers both calling conventions.
        if (node === null) return noopCleanup;
        setDesktopRoot(node);
        return () => setDesktopRoot((current) => (current === node ? null : current));
    }, []);
    const registerWorkspaceRoot = useCallback((node: HTMLElement | null) => {
        if (node === null) return noopCleanup;
        setWorkspaceRoot(node);
        return () => setWorkspaceRoot((current) => (current === node ? null : current));
    }, []);
    const mountValue = useMemo<AskConnexMountValue>(
        () => ({ registerDesktopRoot, registerWorkspaceRoot }),
        [registerDesktopRoot, registerWorkspaceRoot],
    );

    const contextResult = useMemo(
        () => mergeAskConnexContext(sourceRecord, composer, sourceSelection, corrections),
        [composer, corrections, sourceRecord, sourceSelection],
    );
    const carriedPins = useMemo(
        () => pins.filter(
            (pin) => !implicitContext
                || pin.kind !== implicitContext.kind
                || pin.id !== implicitContext.id,
        ),
        [implicitContext, pins],
    );
    const visibleAttachments = useMemo(
        () => contextResult.attachments.filter(
            (attachment) => !(implicitContext
                    && attachment.kind === implicitContext.kind
                    && attachment.id === implicitContext.id)
                && !isAskConnexPinned(carriedPins, attachment),
        ),
        [carriedPins, contextResult.attachments, implicitContext],
    );
    const contentTooLong = askConnexMessageContent(composer).length > 16_000;
    const fileOperationPending = hasPendingAskConnexFileOperation(fileAttachments);
    const contextFiles = useMemo(
        () => fileAttachments.filter((attachment) => attachment.status !== 'failed'),
        [fileAttachments],
    );
    const fileContextCount = contextFiles.length;
    const contextOverflow = contextResult.pageContext.length + fileContextCount > 10;
    const scopePreview = useMemo(
        () => askConnexScopePreview(contextResult.pageContext, contextFiles),
        [contextFiles, contextResult.pageContext],
    );
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
    const presenceSessionId = activeSession?.visibility === 'shared' ? activeSession.id : null;
    /**
     * Whether the conversation is actually on screen, in the drawer or in the routed workspace.
     * Presence is published from here, so gating it on the drawer alone would make every member
     * reading a shared chat at `/ask-connex` invisible to the others once the backend TTL expired.
     */
    const surfaceVisible = open || workspaceMode;

    useEffect(() => {
        activeSessionRef.current = activeSession;
    }, [activeSession]);

    useEffect(() => {
        typingRef.current = composer.trim().length > 0;
    }, [composer]);

    useEffect(() => {
        workspaceSessionIdRef.current = workspaceSessionId;
    }, [workspaceSessionId]);

    const turnActive = turn.phase === 'accepted' || turn.phase === 'running';
    const activeTurn = useMemo(
        () => turnActive && turn.sessionId !== null && turn.turnId !== null
            ? { sessionId: turn.sessionId, turnId: turn.turnId }
            : null,
        [turn.sessionId, turn.turnId, turnActive],
    );

    useEffect(() => {
        activeTurnRef.current = activeTurn;
    }, [activeTurn]);

    useEffect(() => {
        if (turnActive) return;
        cancellingRef.current = false;
        setCancelling(false);
    }, [turnActive]);

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
        streamEpochRef.current += 1;
        streamRef.current = null;
        streamingRef.current = false;
        setStreaming(false);
        streamStore.publish(null);
    }, [streamStore]);

    const hydrateStream = useCallback(async (sessionId: number, turnId: number): Promise<void> => {
        const signal = identityControllerRef.current?.signal;
        if (!signal || signal.aborted) return;
        const streamEpoch = streamEpochRef.current;
        for (let attempt = 0; ; attempt++) {
            if (attempt > 0) {
                try {
                    await delay(500, signal);
                } catch {
                    return;
                }
                if (streamEpoch !== streamEpochRef.current) return;
            }
            let partial: string;
            try {
                const durable = await getAiChatTurn(sessionId, turnId, { signal });
                partial = durable.partialContent ?? '';
            } catch {
                const current = streamRef.current;
                if (!signal.aborted
                        && streamEpoch === streamEpochRef.current
                        && current?.turnId === turnId) {
                    streamRef.current = failAskConnexStreamHydration(current);
                }
                return;
            }
            if (signal.aborted || streamEpoch !== streamEpochRef.current) return;
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
        if (active === null
                || frame.workspaceId !== activeWorkspaceId
                || frame.sessionId !== active.sessionId
                || frame.turnId !== active.turnId) return;
        const current = streamRef.current?.turnId === frame.turnId
            ? streamRef.current
            : createAskConnexStream(frame.turnId);
        const transition = applyAskConnexStreamDelta(current, frame);
        streamRef.current = transition.state;
        invalidateStream();
        if (transition.hydrate) void hydrateStream(active.sessionId, frame.turnId);
    }, [activeWorkspaceId, hydrateStream, invalidateStream]);

    /**
     * Adopts the durable partial the server retains for this answer, including after it stopped —
     * a stopped answer leaves no transcript message, so its retained text is the only record of
     * what it had established. Repairing a gap is only worth doing while more text can still
     * arrive, so a settled answer takes the partial as it stands.
     */
    const absorbTurnPartial = useCallback((durable: AiChatTurn, streamEpoch: number) => {
        if (streamEpoch !== streamEpochRef.current) return;
        if (shouldDropAskConnexStream(durable.status, durable.terminalReason)) return;
        const partial = durable.partialContent ?? '';
        if (partial.length === 0) return;
        const current = streamRef.current?.turnId === durable.turnId
            ? streamRef.current
            : createAskConnexStream(durable.turnId);
        const settled = absorbAskConnexStreamPartial(current, partial);
        streamRef.current = settled.state;
        invalidateStream();
        if (settled.hydrate && isActiveTurnStatus(durable.status)) {
            void hydrateStream(durable.sessionId, durable.turnId);
        }
    }, [hydrateStream, invalidateStream]);

    useEffect(() => {
        if (userId === null || activeWorkspaceId === null) return;
        setWidth(parseStoredAskConnexWidth(safeStorageGet(widthKey)) ?? ASK_CONNEX_DEFAULT_WIDTH);
    }, [activeWorkspaceId, userId, widthKey]);

    /**
     * Applies a chosen panel width, and marks the change as one the shell must adopt without
     * animating. Resizing is a discrete preference rather than a movement: the panel and the column
     * the page reflows into are two elements whose widths have to agree at every instant, and
     * neither can reach a new width without relaying out its subtree on every frame it moves. The
     * shell reads this alongside the open/close animation, which stays animated because it carries
     * the panel in and out of the page.
     */
    const changeWidth = useCallback((next: AskConnexWidth) => {
        setInstantWidth(true);
        setWidth(next);
        safeStorageSet(widthKey, next);
    }, [widthKey]);

    const openDrawer = useCallback((source: OpenSource = 'standard') => {
        if (open) return;
        setInstantWidth(false);
        setInstantOpen(source === 'keyboard');
        setOpen(true);
    }, [open]);
    const closeDrawer = useCallback(() => {
        setInstantWidth(false);
        setOpen(false);
    }, []);
    const closeDrawerInstant = useCallback(() => {
        setInstantWidth(false);
        setInstantOpen(true);
        setOpen(false);
    }, []);
    const openWorkspace = useCallback(() => {
        workspaceReturnRef.current = true;
        setWorkspaceSourceContext({
            identity,
            context: snapshotAskConnexSourceContext(context.record, context.selection),
        });
        setInstantWidth(false);
        setOpen(false);
        const session = activeSessionRef.current;
        router.push(session ? `/ask-connex/${session.id}` : '/ask-connex');
    }, [context.record, context.selection, identity, router]);
    const closeWorkspace = useCallback(() => {
        if (workspaceReturnRef.current) {
            router.back();
            return;
        }
        router.push('/dashboard');
    }, [router]);

    useEffect(() => {
        if (workspaceMode || !workspaceReturnRef.current) return;
        workspaceReturnRef.current = false;
        setWorkspaceSourceContext(null);
        setInstantOpen(false);
        setInstantWidth(false);
        setOpen(true);
    }, [workspaceMode]);

    const contextualActions = useMemo<readonly AppAction[]>(() => {
        if (workspaceMode) return [];
        const label = selectionContext
            ? t('entryPoint.selection', {
                count: selectionContext.count,
                type: t(`recordTypes.${selectionContext.type}`),
            })
            : sourceRecord
                ? t('entryPoint.record', { label: sourceRecord.label })
                : t('entryPoint.workspace');
        return [
            {
                id: 'workspace.ask-connex',
                group: 'workspace',
                labelKey: 'askConnex',
                label,
                keywords: ['ask', 'ai', 'connex'],
                icon: SparklesIcon,
                order: 90,
                execute: () => openDrawer(),
            },
            {
                id: 'workspace.ask-connex-workspace',
                group: 'workspace',
                labelKey: 'askConnexWorkspace',
                label: t('openWorkspaceAction'),
                keywords: ['ask', 'ai', 'connex', 'workspace', 'full'],
                icon: ArrowsPointingOutIcon,
                order: 91,
                execute: () => openWorkspace(),
            },
        ];
    }, [openDrawer, openWorkspace, selectionContext, sourceRecord, t, workspaceMode]);
    useRegisterActions(contextualActions);

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
    ): Promise<{ session: AiChatSession; activeTurn: AiChatTurn | null } | null> => {
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
        setFileAttachments((current) => reconcileAskConnexFileAttachments(
            current,
            attachments.map(readyFileAttachment),
        ));
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
        return { session: firstDetail.session, activeTurn: firstDetail.activeTurn };
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
        setFeatureUnavailable(false);
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
        initial?: { durable: AiChatTurn; streamEpoch: number },
    ): Promise<AiChatTurn> => {
        let streamEpoch = initial?.streamEpoch ?? streamEpochRef.current;
        let current = initial?.durable ?? await getAiChatTurn(sessionId, turnId, { signal });
        while (isActiveTurnStatus(current.status)) {
            dispatchTurn({
                type: 'status',
                status: current.status,
                reason: current.terminalReason,
                progress: current.progress,
            });
            absorbTurnPartial(current, streamEpoch);
            await delay(1_000, signal);
            streamEpoch = streamEpochRef.current;
            current = await getAiChatTurn(sessionId, turnId, { signal });
        }
        return current;
    }, [absorbTurnPartial]);

    const followTurn = useCallback(async (
        stored: StoredAskConnexTurn,
        signal: AbortSignal,
    ): Promise<void> => {
        try {
            let durableStreamEpoch = streamEpochRef.current;
            let durable = await getAiChatTurn(stored.sessionId, stored.turnId, { signal });
            if (signal.aborted) return;
            dispatchTurn({
                type: 'status',
                status: durable.status,
                reason: durable.terminalReason,
                progress: durable.progress,
            });
            absorbTurnPartial(durable, durableStreamEpoch);

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
                    durableStreamEpoch = streamEpochRef.current;
                    durable = await getAiChatTurn(stored.sessionId, stored.turnId, { signal });
                }
            }

            if (isActiveTurnStatus(durable.status)) {
                durable = await pollDurableTurn(stored.sessionId, stored.turnId, signal, {
                    durable,
                    streamEpoch: durableStreamEpoch,
                });
            }

            if (signal.aborted) return;
            dispatchTurn({
                type: 'status',
                status: durable.status,
                reason: durable.terminalReason,
                progress: durable.progress,
            });
            absorbTurnPartial(durable, streamEpochRef.current);
            safeStorageRemove(turnKey);
            setSubmissionBlocked(false);
            await reconcileAskConnexSettledStream(
                durable.status,
                durable.terminalReason,
                resetStream,
                () => refreshTranscript(stored.sessionId, signal, true),
            );
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
            deferApiError(error, 'toast.requestFailed');
        }
    }, [absorbTurnPartial, clearActiveSession, deferApiError, pollDurableTurn, refreshSessions, refreshTranscript, resetStream, t, turnKey]);

    const followDurableTurn = useCallback(async (
        initial: AiChatTurn,
        signal: AbortSignal,
    ): Promise<void> => {
        if (durableFollowerRef.current === initial.turnId) return;
        durableFollowerRef.current = initial.turnId;
        dispatchTurn({
            type: 'accepted',
            sessionId: initial.sessionId,
            turnId: initial.turnId,
            generationHandle: null,
            status: initial.status,
            progress: initial.progress,
            cancellable: initial.requestedByCurrentUser === true,
        });
        absorbTurnPartial(initial, streamEpochRef.current);
        try {
            const durable = await pollDurableTurn(
                initial.sessionId,
                initial.turnId,
                signal,
                { durable: initial, streamEpoch: streamEpochRef.current },
            );
            if (signal.aborted) return;
            dispatchTurn({
                type: 'status',
                status: durable.status,
                reason: durable.terminalReason,
                progress: durable.progress,
            });
            absorbTurnPartial(durable, streamEpochRef.current);
            const stored = parseStoredAskConnexTurn(safeStorageGet(turnKey));
            if (stored?.turnId === durable.turnId) safeStorageRemove(turnKey);
            setSubmissionBlocked(false);
            await reconcileAskConnexSettledStream(
                durable.status,
                durable.terminalReason,
                resetStream,
                () => refreshTranscript(durable.sessionId, signal, true),
            );
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
            deferApiError(error, 'toast.requestFailed');
        } finally {
            if (durableFollowerRef.current === initial.turnId) {
                durableFollowerRef.current = null;
            }
        }
    }, [absorbTurnPartial, clearActiveSession, deferApiError, pollDurableTurn, refreshSessions, refreshTranscript, resetStream, t, turnKey]);

    useEffect(() => {
        sessionEpochRef.current++;
        const controller = new AbortController();
        identityControllerRef.current?.abort();
        identityControllerRef.current = controller;
        sessionControllerRef.current?.abort();
        sessionControllerRef.current = null;
        const storedSessionId = parseStoredAskConnexSession(safeStorageGet(sessionKey));
        const selectedSessionId = workspaceSessionIdRef.current ?? storedSessionId;
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
            setFeatureUnavailable(false);
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
                if (controller.signal.aborted) return;
                if (sharePermission === 'granted') {
                    const workspaceMembers = await getActiveWorkspaceMembers({ signal: controller.signal });
                    if (!controller.signal.aborted) {
                        setMembers(workspaceMembers.filter((member) => member.status === 'active'));
                    }
                }
                if (selectedSessionId === null) {
                    setLoadState('ready');
                    return;
                }
                selectionLoadStarted = true;
                const detail = await refreshTranscript(selectedSessionId, controller.signal, false);
                if (controller.signal.aborted) return;
                if (storedTurn?.sessionId === selectedSessionId) {
                    dispatchTurn({
                        type: 'accepted',
                        sessionId: storedTurn.sessionId,
                        turnId: storedTurn.turnId,
                        generationHandle: storedTurn.generationHandle,
                        status: 'accepted',
                        cancellable: true,
                    });
                    await followTurn(storedTurn, controller.signal);
                } else if (detail?.activeTurn) {
                    await followDurableTurn(detail.activeTurn, controller.signal);
                }
            } catch (error) {
                if (controller.signal.aborted) return;
                if (selectionLoadStarted && error instanceof ApiError
                        && (error.status === 403 || error.status === 404)) {
                    clearActiveSession();
                    return;
                }
                if (error instanceof ApiError && error.status === 403) {
                    setFeatureUnavailable(true);
                    setLoadState('forbidden');
                    deferApiError(error);
                    return;
                }
                const nextError = error instanceof ApiError
                    ? error
                    : new Error(t('toast.requestFailed'));
                setLoadError(nextError);
                setLoadState('error');
                deferApiError(error, 'toast.requestFailed');
            }
        };

        void initialize();
        return () => controller.abort();
    }, [activeWorkspaceId, clearActiveSession, deferApiError, followDurableTurn, followTurn, identity, refreshSessions, refreshTranscript, reloadVersion, resetStream, sessionKey, sharePermission, switching, t, turnKey, userId]);

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
            const detail = await refreshTranscript(session.id, signal, false);
            if (workspaceMode) router.replace(`/ask-connex/${session.id}`);
            if (detail?.activeTurn) void followDurableTurn(detail.activeTurn, signal);
        } catch (error) {
            if (signal.aborted) return;
            if (error instanceof ApiError && (error.status === 403 || error.status === 404)) {
                clearActiveSession();
            } else {
                setLoadError(error instanceof ApiError ? error : new Error(t('toast.requestFailed')));
                setLoadState('error');
                deferApiError(error, 'toast.requestFailed');
            }
        }
    }, [clearActiveSession, deferApiError, followDurableTurn, refreshTranscript, resetStream, router, sessionKey, t, turnKey, working, workspaceMode]);

    const newChat = useCallback(() => {
        if (working) return;
        clearActiveSession();
        if (workspaceMode) router.replace('/ask-connex');
    }, [clearActiveSession, router, working, workspaceMode]);

    useEffect(() => {
        if (!workspaceMode || stateIdentity !== identity || switching) return;
        if (activeTurn !== null) {
            const activeSessionId = activeSessionRef.current?.id ?? null;
            if (activeSessionId !== null && workspaceSessionId !== activeSessionId) {
                router.replace(`/ask-connex/${activeSessionId}`);
            }
            return;
        }
        if (workspaceSessionId === null
                || activeSessionRef.current?.id === workspaceSessionId) return;
        sessionEpochRef.current++;
        sessionControllerRef.current?.abort();
        const controller = new AbortController();
        sessionControllerRef.current = controller;
        const identitySignal = identityControllerRef.current?.signal;
        const signal = identitySignal
            ? AbortSignal.any([controller.signal, identitySignal])
            : controller.signal;
        safeStorageSet(sessionKey, String(workspaceSessionId));
        safeStorageRemove(turnKey);
        messagesRef.current = [];
        setMessages([]);
        setFreshMessageIds(new Set());
        setFileAttachments([]);
        resetStream();
        dispatchTurn({ type: 'reset' });
        dispatchToolCalls({ type: 'reset' });
        setLoadState('loading');
        const load = async () => {
            try {
                const detail = await refreshTranscript(workspaceSessionId, signal, false);
                if (detail?.activeTurn) void followDurableTurn(detail.activeTurn, signal);
            } catch (error) {
                if (signal.aborted) return;
                if (error instanceof ApiError && (error.status === 403 || error.status === 404)) {
                    clearActiveSession();
                    router.replace('/ask-connex');
                    return;
                }
                setLoadError(error instanceof ApiError ? error : new Error(t('toast.requestFailed')));
                setLoadState('error');
                deferApiError(error, 'toast.requestFailed');
            }
        };
        void load();
        return () => controller.abort();
    }, [activeTurn, clearActiveSession, deferApiError, followDurableTurn, identity, refreshTranscript, resetStream, router, sessionKey, stateIdentity, switching, t, turnKey, workspaceMode, workspaceSessionId]);

    const enqueueRealtimeRefresh = useCallback((sessionId: number, signal: AbortSignal) => {
        realtimeRefreshQueueRef.current = realtimeRefreshQueueRef.current.then(async () => {
            if (signal.aborted) return;
            try {
                await refreshSessions(signal);
                const session = activeSessionRef.current;
                if (session?.id === sessionId) {
                    const detail = await refreshTranscript(session.id, signal, true);
                    if (detail?.activeTurn
                            && activeTurnRef.current?.turnId !== detail.activeTurn.turnId) {
                        void followDurableTurn(detail.activeTurn, signal);
                    }
                }
            } catch (error) {
                if (signal.aborted) return;
                if (error instanceof ApiError && (error.status === 403 || error.status === 404)) {
                    clearActiveSession();
                }
            }
        });
    }, [clearActiveSession, followDurableTurn, refreshSessions, refreshTranscript]);

    useEffect(() => {
        if (userId === null || activeWorkspaceId === null || switching) return;
        let connectedBefore = false;
        const socket = createAiChatSocket({
            onFrame: (frame) => {
                if (frame.workspaceId !== activeWorkspaceId) return;
                const signal = identityControllerRef.current?.signal;
                if (!signal || signal.aborted) return;
                if (activeSessionRef.current?.id === frame.sessionId
                        && shouldResetAskConnexStream(streamRef.current, frame)) {
                    resetStream();
                }
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
            onStatusChange: (status) => {
                if (status !== 'connected') return;
                if (!connectedBefore) {
                    connectedBefore = true;
                    return;
                }
                const signal = identityControllerRef.current?.signal;
                if (!signal || signal.aborted) return;
                const sessionId = activeSessionRef.current?.id;
                if (sessionId !== undefined) {
                    enqueueRealtimeRefresh(sessionId, signal);
                    return;
                }
                void refreshSessions(signal).catch(() => undefined);
            },
        });
        socket.activate();
        return () => socket.deactivate();
    }, [activeWorkspaceId, clearActiveSession, enqueueRealtimeRefresh, handleStreamDelta, refreshSessions, resetStream, switching, userId]);

    useEffect(() => {
        if (!surfaceVisible || presenceSessionId === null || loadState !== 'ready') {
            return;
        }
        const sessionId = presenceSessionId;
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
    }, [loadState, presenceSessionId, surfaceVisible]);

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
            showApiError(error, 'toast.requestFailed');
            return false;
        }
    }, [activeSession, refreshCollaboration, sharePermission, showApiError, t]);

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
            showApiError(error, 'toast.requestFailed');
            return false;
        }
    }, [activeSession, showApiError, t]);

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
            showApiError(error, 'toast.requestFailed');
        }
    }, [selectSession, showApiError, t, working]);

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
            showApiError(error, 'toast.requestFailed');
        }
    }, [activeSession, newChat, showApiError, t, working]);

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
            showApiError(error, 'toast.requestFailed');
        }
    }, [activeSession, showApiError, t]);

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
            if (workspaceMode) router.replace(`/ask-connex/${createdSession.id}`);
            setLoadState('ready');
            return createdSession;
        }).finally(() => {
            if (sessionCreationRef.current === creation) sessionCreationRef.current = null;
        });
        sessionCreationRef.current = creation;
        return creation;
    }, [activeSession, router, sessionKey, t, workspaceMode]);

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
                setFileAttachments((current) => completeAskConnexFileUpload(
                    current,
                    clientId,
                    readyFileAttachment(uploaded),
                ));
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
            showApiError(error.cause, 'upload.removeFailed');
        }
    }, [activeSession, fileAttachments, fileOperationPending, showApiError]);

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
            showApiError(error, 'toast.requestFailed');
            return false;
        }
    }, [activeSession, showApiError, t]);

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
            showApiError(error, 'toast.requestFailed');
        }
    }, [activeSession, newChat, showApiError, t, working]);

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
        const requestContext = mergeAskConnexContext(
            sourceRecord,
            requestContent,
            sourceSelection,
            corrections,
        );
        const requestContextOverflow = requestContext.pageContext.length + fileContextCount > 10;
        const activeSignal = identityControllerRef.current?.signal;
        if (
            !activeSignal
            || activeSignal.aborted
            || permission !== 'granted'
            || featureUnavailable
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
        resetStream();
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
                    if (workspaceMode) router.replace(`/ask-connex/${createdSession.id}`);
                    setLoadState('ready');
                } catch (error) {
                    if (!activeSignal.aborted) {
                        showApiError(error, 'toast.requestFailed');
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
            dispatchTurn({
                type: 'accepted',
                sessionId: accepted.sessionId,
                turnId: accepted.turnId,
                generationHandle: accepted.generationHandle,
                status: accepted.status,
                cancellable: true,
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
                setFeatureUnavailable(true);
            } else {
                setSubmissionBlocked(true);
            }
            dispatchTurn({ type: 'status', status: 'failed', reason: 'request_failed' });
            showApiError(error, 'toast.requestFailed');
            if (session !== null) {
                try {
                    await refreshTranscript(session.id, activeSignal, true);
                } catch {}
            }
        } finally {
            submittingRef.current = false;
            setSubmitting(false);
        }
    }, [activeSession, composer, corrections, featureUnavailable, fileContextCount, fileOperationPending, followTurn, permission, refreshTranscript, resetStream, router, sessionKey, showApiError, sourceRecord, sourceSelection, submissionBlocked, t, turn.phase, turnKey, userDisplayName, userId, workspaceMode]);

    const retryPrompt = useMemo(() => askConnexRetryPrompt(messages), [messages]);
    const retryTurn = useCallback(() => {
        if (retryPrompt === null) return;
        void send(retryPrompt);
    }, [retryPrompt, send]);

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

    useEffect(() => {
        if (userId === null || activeWorkspaceId === null) return;
        setPinnedContext(parseStoredAskConnexPins(safeStorageGet(pinnedKey)));
        setPinnedIdentity(identity);
    }, [activeWorkspaceId, identity, pinnedKey, userId]);

    const commitPins = useCallback((next: AskConnexAttachment[]) => {
        setPinnedContext(next);
        safeStorageSet(pinnedKey, serializeAskConnexPins(next));
    }, [pinnedKey]);

    const togglePagePin = useCallback(() => {
        if (inferredPageContext === null || pinnedIdentity !== identity) return;
        commitPins(toggleAskConnexPin(pins, inferredPageContext));
    }, [commitPins, identity, inferredPageContext, pinnedIdentity, pins]);

    const unpinContext = useCallback((attachment: AskConnexAttachment) => {
        if (pinnedIdentity !== identity) return;
        commitPins(pins.filter(
            (pin) => pin.kind !== attachment.kind || pin.id !== attachment.id,
        ));
    }, [commitPins, identity, pinnedIdentity, pins]);

    const removePageContext = useCallback(() => setPageDismissed(true), []);
    const removeSelectionContext = useCallback(() => setSelectionDismissed(true), []);
    const resetContext = useCallback(() => {
        setPageDismissed(false);
        setSelectionDismissed(false);
    }, []);

    const unavailable = useMemo(() => {
        if (permission === 'denied') {
            return { title: t('unavailable.permissionTitle'), body: t('unavailable.permissionBody') };
        }
        if (permission === 'unavailable') {
            return { title: t('unavailable.lookupTitle'), body: t('unavailable.lookupBody') };
        }
        if (featureUnavailable) {
            return {
                title: t('unavailable.featureTitle'),
                body: t('unavailable.featureBody'),
            };
        }
        if (submissionBlocked) {
            return { title: t('unavailable.uncertainTitle'), body: t('unavailable.uncertainBody') };
        }
        return null;
    }, [permission, submissionBlocked, t, featureUnavailable]);

    const canRetryTurn = retryPrompt !== null
        && unavailable === null
        && !working
        && !contextOverflow;

    /**
     * The one thing this chat is doing that the header and its rail row should say out loud.
     *
     * Derived from state this client already follows — the answer it is watching and the proposals
     * it has been offered — rather than from anything the session list claims, so it is never a
     * guess about a chat that is not on screen.
     */
    const activeState = useMemo<AskConnexActiveState>(
        () => askConnexActiveState({
            phase: turn.phase,
            pendingApprovals: actionableToolCallIds.size,
        }),
        [actionableToolCallIds, turn.phase],
    );

    /**
     * The message that continues a stopped answer.
     *
     * A stopped answer leaves no assistant message behind, so nothing about it reaches the next
     * request on its own: continuing is an ordinary question, composed from the one that produced
     * the partial and handed to the member to read, edit, and send. Available only while a retry
     * would also be — the same question has to still be readable and the surface has to be able to
     * send it.
     */
    const continuePrompt = useMemo(() => {
        if (retryPrompt === null || !canRetryTurn) return null;
        const question = retryPrompt.length > ASK_CONNEX_CONTINUE_QUESTION_LIMIT
            ? `${retryPrompt.slice(0, ASK_CONNEX_CONTINUE_QUESTION_LIMIT)}…`
            : retryPrompt;
        return t('continuePrompt', { question });
    }, [canRetryTurn, retryPrompt, t]);

    const starterPromptKind = implicitContext?.kind
        ?? selectionContext?.pageContext[0]?.kind
        ?? null;
    const starterPrompts = useMemo(
        () => starterPromptKeys(starterPromptKind).map((key) => t(key)),
        [starterPromptKind, t],
    );
    const scopeList = useMemo(
        () => new Intl.ListFormat(locale, { style: 'long', type: 'conjunction' }),
        [locale],
    );
    const scopeSummary = useCallback((preview: AskConnexScopePreview) => {
        const parts = preview.records.map(
            ({ kind, count }) => t(`scopeRecordCounts.${kind}`, { count }),
        );
        if (preview.files > 0) parts.push(t('scopeFiles', { count: preview.files }));
        return t('scopeSummary', { scope: scopeList.format(parts) });
    }, [scopeList, t]);
    const citationKind = useCallback(
        (kind: AiChatCitation['kind']) =>
            kind === 'person'
                ? t('citationKindPerson')
                : kind === 'company'
                    ? t('citationKindCompany')
                    : t('citationKindDeal'),
        [t],
    );
    const labels = useMemo(() => ({
        answerDocument: {
            absoluteTime: (instant: string) => formatAnswerInstant(instant, locale),
            blockKind: (kind: AiChatAnswerBlockKind) => t(`answerDocument.blockKinds.${kind}`),
            boundedRows: (shown: number, total: number) =>
                t('answerDocument.boundedRows', { shown, total }),
            viewAll: t('answerDocument.viewAll'),
            citationKind,
            comparisonAgainst: t('answerDocument.comparisonAgainst'),
            comparisonValue: t('answerDocument.comparisonValue'),
            copyDraft: t('answerDocument.copyDraft'),
            copyDraftDone: t('answerDocument.copyDraftDone'),
            coverage: t('answerDocument.coverage'),
            coverageStatus: (status: AiChatCoverage['status']) =>
                t(`answerDocument.coverageStatuses.${status}`),
            diffAfter: t('answerDocument.diffAfter'),
            diffBefore: t('answerDocument.diffBefore'),
            dismiss: t('answerDocument.dismiss'),
            evidence: t('answerDocument.evidence'),
            evidenceDetail: t('answerDocument.evidenceDetail'),
            exclusions: t('answerDocument.exclusions'),
            exclusion: (exclusion: AiChatCoverage['exclusions'][number]) =>
                t(`answerDocument.exclusionsList.${exclusion}`),
            freshness: t('answerDocument.freshness'),
            freshnessCurrent: t('answerDocument.freshnessCurrent'),
            moreDetail: t('answerDocument.moreDetail'),
            openRecord: t('answerDocument.openRecord'),
            period: (start: string, end: string) =>
                t('answerDocument.period', { start, end }),
            progressCount: (count: number) => t('answerDocument.progressCount', { count }),
            progressSource: (source: AiChatProgressItem['source']) =>
                t(`answerDocument.progressSources.${source}`),
            progressStatus: (status: AiChatProgressItem['status']) =>
                t(`answerDocument.progressStatuses.${status}`),
            relativeTime: (instant: string) => formatRelativeTime(instant, locale, now),
            sourceLimits: t('answerDocument.sourceLimits'),
            sources: t('answerDocument.sources'),
            source: (source: AiChatCoverage['sources'][number]) =>
                t(`answerDocument.sourcesList.${source}`),
            truncated: t('answerDocument.truncated'),
            unsupported: t('answerDocument.unsupported'),
            whatChecked: t('answerDocument.whatChecked'),
            withheldEvidence: t('answerDocument.withheldEvidence'),
        },
        assistantAuthor: t('assistantAuthor'),
        archive: t('archive'),
        terminalMessage: {
            generic: t('turnFailed'),
            breadthSteps: t('stepCapExceeded'),
            breadthResults: t('toolResultBudgetExhausted'),
            skillBudget: t('skillBudgetExceeded'),
            toolAuthority: t('toolOutsideSkillAuthority'),
            budget: t('budgetExhausted'),
            capacity: t('capacityExhausted'),
            workspaceDisabled: t('workspaceDisabled'),
            accessRevoked: t('accessRevoked'),
            restrictionsChanged: t('restrictionsChanged'),
            imageUnsupported: t('turnImageUnsupported'),
        },
        citations: t('citations'),
        disclosureCreation: tDisclosure('sessionCreation'),
        disclosureList: tDisclosure('sessionList'),
        imageDisclosure: tDisclosure('imageProvider'),
        citationKind,
        close: t('close'),
        closeWorkspace: t('closeWorkspace'),
        composerAria: t('composerAria'),
        composerHint: t('composerHint'),
        composerPlaceholder: t('composerPlaceholder'),
        context: t('context'),
        contextFile: t('contextFile'),
        contextLimit: t('contextLimit'),
        contextMentioned: t('contextMentioned'),
        contextPage: t('contextPage'),
        contextPinned: t('contextPinned'),
        contextReset: t('contextReset'),
        pinContext: (label: string) => t('pinContext', { label }),
        unpinContext: (label: string) => t('unpinContext', { label }),
        scopeTitle: t('scopeTitle'),
        scopeSummary,
        scopeConfirm: t('scopeConfirm'),
        scopeEdit: t('scopeEdit'),
        contextSelected: (count: number, type: AskConnexSelectionContext['type']) =>
            t('contextSelected', { count, type: t(`recordTypes.${type}`) }),
        contextScopeUnsupported: t('contextScopeUnsupported'),
        contextUnavailable: t('contextUnavailable'),
        contextUnsupported: (type: AskConnexSelectionContext['type']) =>
            t('contextUnsupported', { type: t(`recordTypes.${type}`) }),
        addContext: t('addContext'),
        addRecordContext: t('addRecordContext'),
        attachFile: t('attachFile'),
        removeFile: (label: string) => t('removeFile', { label }),
        uploadProgress: (progress: number) => t('upload.progress', { progress }),
        uploadRemoving: t('upload.removing'),
        emptyBody: t('emptyBody'),
        emptyTitle: t('emptyTitle'),
        formerMember: t('formerMember'),
        contentWithheld: t('contentWithheld'),
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
        noMatchingSessions: t('noMatchingSessions'),
        moreOptions: t('moreOptions'),
        participants: t('participants'),
        presence: t('presence'),
        recentSessions: t('recentSessions'),
        searchSessions: t('searchSessions'),
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
        title: t('title'),
        tooLong: t('tooLong'),
        typing: (names: string) => t('typing', { names }),
        unshare: t('unshare'),
        stop: t('stop'),
        stopping: t('stopping'),
        turnAccepted: t('turnAccepted'),
        turnCancelled: t('turnCancelled'),
        turnResolved: t('turnResolved'),
        turnStreaming: t('turnStreaming'),
        turnTimedOut: t('turnTimedOut'),
        turnWorking: t('turnWorking'),
        partialAnswer: t('partialAnswer'),
        continueFromPartial: t('continueFromPartial'),
        narrowScope: t('narrowScope'),
        openWorkspace: t('openWorkspace'),
        width: t('width'),
        widthCompact: t('widthCompact'),
        widthComfortable: t('widthComfortable'),
        visibilityPrivate: t('visibilityPrivate'),
        visibilityShared: t('visibilityShared'),
        stateRunning: t('stateRunning'),
        stateAwaitingApproval: t('stateAwaitingApproval'),
        stateFailed: t('stateFailed'),
        contextSummary: (count: number) => t('contextSummary', { count }),
        participantCount: (count: number) => t('participantCount', { count }),
        sessionActivity: (time: string) => t('sessionActivity', { time }),
        sessionRail: t('sessionRail'),
        sessionGroup: (key: AskConnexSessionGroupKey) => t(`sessionGroups.${key}`),
        relativeTime: (instant: string) => formatRelativeTime(instant, locale, now),
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
    }), [citationKind, locale, now, scopeSummary, t, tDisclosure]);

    const value = useMemo<AskConnexContextValue>(
        () => ({
            open,
            instantOpen,
            instantWidth,
            working,
            workspace: workspaceMode,
            width,
            openDrawer,
            closeDrawer,
            openWorkspace,
        }),
        [closeDrawer, instantOpen, instantWidth, open, openDrawer, openWorkspace, width, working, workspaceMode],
    );

    return (
        <AskConnexContext.Provider value={value}>
            <AskConnexMountContext.Provider value={mountValue}>
                {children}
            </AskConnexMountContext.Provider>
            <AskConnexDrawer
                open={open}
                instantOpen={instantOpen}
                isMobile={isMobile}
                showTab={scoped}
                workspace={workspaceMode}
                desktopRoot={desktopRoot}
                workspaceRoot={workspaceRoot}
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
                selectionContext={selectionContext}
                unsupportedPageContext={unsupportedPageContext}
                pinnedContext={scoped ? carriedPins : EMPTY_ASK_CONNEX_PINS}
                pageContextPinned={pageContextPinned}
                contextCorrected={contextCorrected}
                scopePreview={scoped ? scopePreview : null}
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
                canRetryTurn={scoped && canRetryTurn}
                continuePrompt={scoped ? continuePrompt : null}
                width={width}
                activeState={scoped ? activeState : null}
                contextCount={scoped ? contextResult.pageContext.length + fileContextCount : 0}
                now={now}
                unavailable={unavailable}
                starterPrompts={starterPrompts}
                labels={labels}
                onWidthChange={changeWidth}
                onOpenChange={setOpen}
                onOpenChangeComplete={() => setInstantOpen(false)}
                onKeyboardClose={closeDrawerInstant}
                onOpenWorkspace={openWorkspace}
                onCloseWorkspace={closeWorkspace}
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
                onTogglePagePin={togglePagePin}
                onUnpinContext={unpinContext}
                onRemovePageContext={removePageContext}
                onRemoveSelectionContext={removeSelectionContext}
                onResetContext={resetContext}
                onAttachFiles={(files) => void attachFiles(files)}
                onRemoveFileAttachment={(attachment) => void removeFileAttachment(attachment)}
                onSend={(content) => void send(content)}
                onCancelTurn={() => void cancelTurn()}
                onRetryTurn={retryTurn}
                onToolAction={(toolCallId, action) => void performToolAction(toolCallId, action)}
            />
        </AskConnexContext.Provider>
    );
}
