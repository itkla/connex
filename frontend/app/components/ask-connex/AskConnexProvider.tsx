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

import AskConnexDrawer from '@/app/components/ask-connex/AskConnexDrawer';
import { useActions } from '@/app/hooks/useActions';
import { useIsMobile } from '@/app/hooks/useIsMobile';
import { usePermissionCheck } from '@/app/hooks/usePermissions';
import { useWorkspace } from '@/app/hooks/useWorkspace';
import {
    ApiError,
    archiveAiChatSession,
    createAiChatSession,
    getActiveWorkspaceMembers,
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
    removeAiChatParticipant,
    resolveAcceptedAiGeneration,
    setAiChatSessionShared,
    startAiChatTurn,
    touchAiChatPresence,
    updateAiChatSession,
} from '@/app/lib/api';
import {
    EMPTY_ASK_CONNEX_TURN,
    activeRecordAskConnexContext,
    askConnexMessageContent,
    askConnexSessionStorageKey,
    askConnexTurnStorageKey,
    mergeAskConnexContext,
    parseStoredAskConnexSession,
    parseStoredAskConnexTurn,
    reduceAskConnexTurn,
    removeAskConnexAttachment,
    serializeStoredAskConnexTurn,
    type AskConnexAttachment,
    type StoredAskConnexTurn,
} from '@/app/lib/askConnex';
import { AiGenerationError } from '@/app/lib/aiGeneration';
import { createAiChatSocket } from '@/app/lib/realtime';
import { toastError, toastSuccess } from '@/app/lib/toast';
import type {
    AiChatCitation,
    AiChatMessage,
    AiChatParticipant,
    AiChatPresence,
    AiChatSession,
    AiChatTurn,
    AiChatTurnGenerationResult,
    WorkspaceMember,
} from '@/app/lib/types';

type OpenSource = 'standard' | 'keyboard';

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
    const { context } = useActions();
    const { activeWorkspaceId, switching } = useWorkspace();
    const permission = usePermissionCheck('AI_USE');
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
    const [unavailableReason, setUnavailableReason] = useState<string | null>(null);
    const [submissionBlocked, setSubmissionBlocked] = useState(false);
    const [reloadVersion, setReloadVersion] = useState(0);
    const [turn, dispatchTurn] = useReducer(reduceAskConnexTurn, EMPTY_ASK_CONNEX_TURN);

    const messagesRef = useRef<AiChatMessage[]>([]);
    const identityControllerRef = useRef<AbortController | null>(null);
    const sessionControllerRef = useRef<AbortController | null>(null);
    const tempMessageIdRef = useRef(-1);
    const activeSessionRef = useRef<AiChatSession | null>(null);
    const typingRef = useRef(false);
    const sessionsRefreshVersionRef = useRef(0);
    const transcriptRefreshVersionRef = useRef(0);
    const realtimeRefreshQueueRef = useRef<Promise<void>>(Promise.resolve());

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
    const working = turn.phase === 'accepted' || turn.phase === 'running';
    const scoped = stateIdentity === identity && !switching;

    useEffect(() => {
        activeSessionRef.current = activeSession;
    }, [activeSession]);

    useEffect(() => {
        typingRef.current = composer.trim().length > 0;
    }, [composer]);

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
        const detail = await getAiChatSession(sessionId, { page: 1, size: 50 }, { signal });
        if (signal.aborted || refreshVersion !== transcriptRefreshVersionRef.current) return null;
        const known = new Set(messagesRef.current.map(
            (message) => `${message.seq}:${message.authorKind}:${message.content}`,
        ));
        const nextMessages = detail.messages.items;
        messagesRef.current = nextMessages;
        setMessages(nextMessages);
        setFreshMessageIds(animateNew
            ? new Set(nextMessages
                .filter((message) => !known.has(`${message.seq}:${message.authorKind}:${message.content}`))
                .map((message) => message.id))
            : new Set());
        setActiveSession(detail.session);
        activeSessionRef.current = detail.session;
        if (detail.session.visibility === 'shared') {
            await refreshCollaboration(sessionId, signal);
        } else {
            setParticipants([]);
            setPresence(null);
        }
        setLoadState('ready');
        setLoadError(null);
        return detail.session;
    }, [refreshCollaboration]);

    const clearActiveSession = useCallback(() => {
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
        setSubmissionBlocked(false);
        setUnavailableReason(null);
        dispatchTurn({ type: 'reset' });
        setLoadState('ready');
        setLoadError(null);
    }, [sessionKey, turnKey]);

    const pollDurableTurn = useCallback(async (
        sessionId: number,
        turnId: number,
        signal: AbortSignal,
        initial?: AiChatTurn,
    ): Promise<AiChatTurn> => {
        let current = initial ?? await getAiChatTurn(sessionId, turnId, { signal });
        while (isActiveTurnStatus(current.status)) {
            dispatchTurn({ type: 'status', status: current.status, reason: current.terminalReason });
            await delay(1_000, signal);
            current = await getAiChatTurn(sessionId, turnId, { signal });
        }
        return current;
    }, []);

    const followTurn = useCallback(async (
        stored: StoredAskConnexTurn,
        signal: AbortSignal,
    ): Promise<void> => {
        try {
            let durable = await getAiChatTurn(stored.sessionId, stored.turnId, { signal });
            if (signal.aborted) return;
            dispatchTurn({ type: 'status', status: durable.status, reason: durable.terminalReason });

            if (isActiveTurnStatus(durable.status)) {
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
                durable = await pollDurableTurn(stored.sessionId, stored.turnId, signal, durable);
            }

            if (signal.aborted) return;
            dispatchTurn({ type: 'status', status: durable.status, reason: durable.terminalReason });
            safeStorageRemove(turnKey);
            setSubmissionBlocked(false);
            await refreshTranscript(stored.sessionId, signal, true);
            await refreshSessions(signal);
            if (durable.status === 'failed') deferredErrorToast(t('toast.turnFailed'));
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
    }, [clearActiveSession, pollDurableTurn, refreshSessions, refreshTranscript, t, turnKey]);

    useEffect(() => {
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
            setUnavailableReason(null);
            setSubmissionBlocked(false);
            dispatchTurn({ type: 'reset' });
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
    }, [activeWorkspaceId, clearActiveSession, followTurn, identity, refreshSessions, refreshTranscript, reloadVersion, sessionKey, sharePermission, switching, t, turnKey, userId]);

    const selectSession = useCallback(async (session: AiChatSession) => {
        if (working) return;
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
        setSubmissionBlocked(false);
        dispatchTurn({ type: 'reset' });
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
    }, [clearActiveSession, refreshTranscript, sessionKey, t, turnKey, working]);

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
        });
        socket.activate();
        return () => socket.deactivate();
    }, [activeWorkspaceId, clearActiveSession, enqueueRealtimeRefresh, switching, userId]);

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

    const send = useCallback(async () => {
        const content = askConnexMessageContent(composer);
        const activeSignal = identityControllerRef.current?.signal;
        if (
            !activeSignal
            || activeSignal.aborted
            || permission !== 'granted'
            || unavailableReason !== null
            || submissionBlocked
            || working
            || contextResult.overflow
            || content.length === 0
            || content.length > 16_000
        ) return;

        let session = activeSession;
        if (session === null) {
            try {
                const createdSession = await createAiChatSession(t('newChatTitle'), { signal: activeSignal });
                if (activeSignal.aborted) return;
                session = createdSession;
                setActiveSession(createdSession);
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

        try {
            const accepted = await startAiChatTurn(session.id, {
                content,
                pageContext: contextResult.pageContext,
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
            try {
                await refreshTranscript(session.id, activeSignal, true);
            } catch {}
        }
    }, [activeSession, composer, contextResult.overflow, contextResult.pageContext, followTurn, permission, refreshTranscript, sessionKey, submissionBlocked, t, turnKey, unavailableReason, userDisplayName, userId, working]);

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
        citations: t('citations'),
        disclosureCreation: tDisclosure('sessionCreation'),
        disclosureList: tDisclosure('sessionList'),
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
        emptyBody: t('emptyBody'),
        emptyTitle: t('emptyTitle'),
        formerMember: t('formerMember'),
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
        title: t('title'),
        tooLong: t('tooLong'),
        typing: (names: string) => t('typing', { names }),
        unshare: t('unshare'),
        turnAccepted: t('turnAccepted'),
        turnFailed: t('turnFailed'),
        turnResolved: t('turnResolved'),
        turnTimedOut: t('turnTimedOut'),
        turnWorking: t('turnWorking'),
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
                contextOverflow={scoped && contextResult.overflow}
                contentTooLong={scoped && contentTooLong}
                turn={scoped ? turn : EMPTY_ASK_CONNEX_TURN}
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
                onSend={() => void send()}
            />
        </AskConnexContext.Provider>
    );
}
