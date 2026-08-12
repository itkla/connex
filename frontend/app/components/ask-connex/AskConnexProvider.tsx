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
    deleteAiChatAttachment,
    getAiChatAttachments,
    getAiChatSession,
    getAiChatSessions,
    getAiChatTurn,
    getAiGenerationStatus,
    resolveAcceptedAiGeneration,
    startAiChatTurn,
    uploadAiChatAttachment,
    updateAiChatSession,
} from '@/app/lib/api';
import {
    EMPTY_ASK_CONNEX_TURN,
    AskConnexFileRemovalError,
    activeRecordAskConnexContext,
    askConnexMessageContent,
    askConnexSessionStorageKey,
    askConnexTurnStorageKey,
    hasPendingAskConnexFileOperation,
    mergeAskConnexContext,
    parseStoredAskConnexSession,
    parseStoredAskConnexTurn,
    reduceAskConnexTurn,
    removeReadyAskConnexFile,
    removeAskConnexAttachment,
    serializeStoredAskConnexTurn,
    type AskConnexAttachment,
    type AskConnexFileAttachment,
    type StoredAskConnexTurn,
} from '@/app/lib/askConnex';
import { AiGenerationError } from '@/app/lib/aiGeneration';
import { toastError, toastSuccess } from '@/app/lib/toast';
import type {
    AiChatCitation,
    AiChatAttachment,
    AiChatMessage,
    AiChatSession,
    AiChatTurn,
    AiChatTurnGenerationResult,
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
    const { context } = useActions();
    const { activeWorkspaceId, switching } = useWorkspace();
    const permission = usePermissionCheck('AI_USE');
    const attachmentCreatePermission = usePermissionCheck('ATTACHMENT_CREATE');
    const attachmentDeletePermission = usePermissionCheck('ATTACHMENT_DELETE');
    const isMobile = useIsMobile();
    const userId = context.user?.id ?? null;
    const identity = `${userId ?? 'anon'}:${activeWorkspaceId ?? 'none'}`;
    const sessionKey = askConnexSessionStorageKey(userId, activeWorkspaceId);
    const turnKey = askConnexTurnStorageKey(userId, activeWorkspaceId);
    const implicitContext = activeRecordAskConnexContext(context.record);

    const [open, setOpen] = useState(false);
    const [instantOpen, setInstantOpen] = useState(false);
    const [stateIdentity, setStateIdentity] = useState<string | null>(null);
    const [sessions, setSessions] = useState<AiChatSession[]>([]);
    const [activeSession, setActiveSession] = useState<AiChatSession | null>(null);
    const [messages, setMessages] = useState<AiChatMessage[]>([]);
    const [freshMessageIds, setFreshMessageIds] = useState<ReadonlySet<number>>(new Set());
    const [loadState, setLoadState] = useState<'loading' | 'ready' | 'error' | 'forbidden'>('loading');
    const [loadError, setLoadError] = useState<Error | null>(null);
    const [composer, setComposer] = useState('');
    const [fileAttachments, setFileAttachments] = useState<AskConnexFileAttachment[]>([]);
    const [unavailableReason, setUnavailableReason] = useState<string | null>(null);
    const [submissionBlocked, setSubmissionBlocked] = useState(false);
    const [reloadVersion, setReloadVersion] = useState(0);
    const [turn, dispatchTurn] = useReducer(reduceAskConnexTurn, EMPTY_ASK_CONNEX_TURN);

    const messagesRef = useRef<AiChatMessage[]>([]);
    const identityControllerRef = useRef<AbortController | null>(null);
    const sessionControllerRef = useRef<AbortController | null>(null);
    const tempMessageIdRef = useRef(-1);
    const sessionCreationRef = useRef<Promise<AiChatSession> | null>(null);
    const sessionEpochRef = useRef(0);

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
    const working = turn.phase === 'accepted' || turn.phase === 'running' || fileOperationPending;
    const scoped = stateIdentity === identity && !switching;

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
        const page = await getAiChatSessions({ page: 1, size: 25 }, { signal });
        if (signal.aborted) return [];
        setSessions(page.items.filter((session) => !session.archived));
        return page.items;
    }, []);

    const refreshTranscript = useCallback(async (
        sessionId: number,
        signal: AbortSignal,
        animateNew: boolean,
    ): Promise<AiChatSession | null> => {
        const [detail, attachments] = await Promise.all([
            getAiChatSession(sessionId, { page: 1, size: 50 }, { signal }),
            getAiChatAttachments(sessionId, { signal }),
        ]);
        if (signal.aborted) return null;
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
        setFileAttachments(attachments.map(readyFileAttachment));
        setLoadState('ready');
        setLoadError(null);
        return detail.session;
    }, []);

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
            if (durable.status === 'failed') {
                deferredErrorToast(durable.terminalReason === 'image_input_unsupported'
                    ? t('turnImageUnsupported')
                    : t('toast.turnFailed'));
            }
            if (durable.status === 'timed_out') deferredErrorToast(t('toast.turnTimedOut'));
        } catch (error) {
            if (signal.aborted) return;
            if (error instanceof ApiError && error.status === 403) {
                setUnavailableReason(error.message);
                dispatchTurn({ type: 'status', status: 'failed', reason: error.message });
                deferredErrorToast(error.message);
                return;
            }
            setSubmissionBlocked(true);
            dispatchTurn({ type: 'status', status: 'failed', reason: 'reconciliation_failed' });
            deferredErrorToast(error instanceof ApiError ? error.message : t('toast.requestFailed'));
        }
    }, [pollDurableTurn, refreshSessions, refreshTranscript, t, turnKey]);

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
            await Promise.resolve();
            if (controller.signal.aborted) return;
            setStateIdentity(identity);
            setSessions([]);
            setActiveSession(null);
            messagesRef.current = [];
            setMessages([]);
            setFreshMessageIds(new Set());
            setComposer('');
            setFileAttachments([]);
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
                if (storedSessionId === null) {
                    setLoadState('ready');
                    return;
                }
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
                if (error instanceof ApiError && error.status === 404) {
                    safeStorageRemove(sessionKey);
                    safeStorageRemove(turnKey);
                    setLoadState('ready');
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
    }, [activeWorkspaceId, followTurn, identity, refreshSessions, refreshTranscript, reloadVersion, sessionKey, switching, t, turnKey, userId]);

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
        messagesRef.current = [];
        setMessages([]);
        setFreshMessageIds(new Set());
        setComposer('');
        setFileAttachments([]);
        setSubmissionBlocked(false);
        dispatchTurn({ type: 'reset' });
        setLoadState('loading');
        try {
            await refreshTranscript(session.id, signal, false);
        } catch (error) {
            if (signal.aborted) return;
            if (error instanceof ApiError && error.status === 403) {
                setUnavailableReason(error.message);
                setLoadState('forbidden');
            } else {
                setLoadError(error instanceof ApiError ? error : new Error(t('toast.requestFailed')));
                setLoadState('error');
            }
            deferredErrorToast(error instanceof ApiError ? error.message : t('toast.requestFailed'));
        }
    }, [refreshTranscript, sessionKey, t, turnKey, working]);

    const newChat = useCallback(() => {
        if (working) return;
        sessionEpochRef.current++;
        sessionControllerRef.current?.abort();
        safeStorageRemove(sessionKey);
        safeStorageRemove(turnKey);
        setActiveSession(null);
        messagesRef.current = [];
        setMessages([]);
        setFreshMessageIds(new Set());
        setComposer('');
        setFileAttachments([]);
        setSubmissionBlocked(false);
        dispatchTurn({ type: 'reset' });
        setLoadState('ready');
        setLoadError(null);
    }, [sessionKey, turnKey, working]);

    const ensureSession = useCallback(async (signal: AbortSignal): Promise<AiChatSession> => {
        if (activeSession !== null) return activeSession;
        if (sessionCreationRef.current !== null) return sessionCreationRef.current;
        const creation = createAiChatSession(t('newChatTitle'), { signal }).then((createdSession) => {
            if (signal.aborted) throw signal.reason;
            setActiveSession(createdSession);
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
        try {
            const updated = await updateAiChatSession(activeSession.id, { title });
            setActiveSession(updated);
            setSessions((current) => current.map((session) => session.id === updated.id ? updated : session));
            toastSuccess(t('toast.renamed'));
            return true;
        } catch (error) {
            toastError(error instanceof ApiError ? error.message : t('toast.requestFailed'));
            return false;
        }
    }, [activeSession, t]);

    const archiveSession = useCallback(async () => {
        if (!activeSession?.ownedByCurrentUser || working) return;
        try {
            await archiveAiChatSession(activeSession.id);
            setSessions((current) => current.filter((session) => session.id !== activeSession.id));
            toastSuccess(t('toast.archived'));
            newChat();
        } catch (error) {
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
            || contextOverflow
            || fileOperationPending
            || content.length === 0
            || content.length > 16_000
        ) return;

        let session: AiChatSession;
        try {
            session = await ensureSession(activeSignal);
        } catch (error) {
            if (!activeSignal.aborted) {
                toastError(error instanceof ApiError ? error.message : t('toast.requestFailed'));
            }
            return;
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
    }, [composer, contextOverflow, contextResult.pageContext, ensureSession, fileOperationPending, followTurn, permission, refreshTranscript, submissionBlocked, t, turnKey, unavailableReason, userId, working]);

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
        archive: t('archive'),
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
        jumpToLatest: t('jumpToLatest'),
        loadError: t('loadError'),
        messages: t('messages'),
        newChat: t('newChat'),
        noRecentSessions: t('noRecentSessions'),
        moreOptions: t('moreOptions'),
        recentSessions: t('recentSessions'),
        removeContext: (label: string) => t('removeContext', { label }),
        rename: t('rename'),
        renameCancel: t('renameCancel'),
        renameDescription: t('renameDescription'),
        renameLabel: t('renameLabel'),
        renameSave: t('renameSave'),
        renameSaving: t('renameSaving'),
        renameTitle: t('renameTitle'),
        retry: t('retry'),
        send: t('send'),
        title: t('title'),
        tooLong: t('tooLong'),
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
                activeSession={scoped ? activeSession : null}
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
                onRetry={() => setReloadVersion((version) => version + 1)}
                onComposerChange={setComposer}
                onRemoveAttachment={removeAttachment}
                onAttachFiles={(files) => void attachFiles(files)}
                onRemoveFileAttachment={(attachment) => void removeFileAttachment(attachment)}
                onSend={() => void send()}
            />
        </AskConnexContext.Provider>
    );
}
